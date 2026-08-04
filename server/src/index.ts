import { OAuthProvider, type TokenSummary } from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler, getMcpAuthContext } from "agents/mcp/server";

import type { WorkerEnv } from "./env";
import {
  handleAuthorizationRequest,
  handleGitHubCallback,
  PLAYBACK_SCOPE,
} from "./github-oauth";
import { nowPlayingOutputSchema, toNowPlayingResult } from "./now-playing";
import { constantTimeEqual } from "./security";
import { handleNowPlayingUpload, noStore } from "./upload";

export { NowPlayingDurableObject } from "./now-playing-do";

type FetchHandler = {
  fetch(request: Request, env: WorkerEnv, ctx: ExecutionContext): Response | Promise<Response>;
};

type TokenProps = { githubUserId: string };
type McpAuthInfo = {
  token?: unknown;
  clientId?: unknown;
  scopes?: unknown;
  expiresAt?: unknown;
  resource?: unknown;
};
type VerifiedMcpAccess = {
  token: string;
  clientId: string;
  scopes: string[];
  expiresAt: number;
  resource: string;
  props: TokenProps;
};

// This is the context contract consumed by the current agents/createMcpHandler
// implementation. It is populated only after this Worker revalidates the
// provider's stored access-token record for this exact request.
const VERIFIED_OAUTH_CONTEXT = Symbol.for(
  "cloudflare.workers-oauth-provider.verified-context.v1",
);
const TOOL_SECURITY_SCHEMES = [{ type: "oauth2", scopes: [PLAYBACK_SCOPE] }];

function mcpResource(request: Request): string {
  return `${new URL(request.url).origin}/mcp`;
}

function protectedResourceMetadata(request: Request): string {
  const metadata = new URL(
    "/.well-known/oauth-protected-resource/mcp",
    request.url,
  ).toString();
  return metadata;
}

function mcpHttpChallenge(request: Request): string {
  return `Bearer error="invalid_token", error_description="Authentication is required.", resource_metadata="${protectedResourceMetadata(request)}"`;
}

export function mcpToolScopeChallenge(request: Request): string {
  return `Bearer error="insufficient_scope", error_description="This tool requires the playback:read scope.", scope="${PLAYBACK_SCOPE}", resource_metadata="${protectedResourceMetadata(request)}"`;
}

function unauthorizedMcp(request: Request): Response {
  return noStore(
    new Response("Unauthorized", {
      status: 401,
      headers: { "WWW-Authenticate": mcpHttpChallenge(request) },
    }),
  );
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("authorization");
  const match = value?.match(/^Bearer ([^\s]+)$/iu);
  return match?.[1] ?? null;
}

function tokenAudienceIncludes(record: TokenSummary<TokenProps>, resource: string): boolean {
  const audiences = record.audience === undefined
    ? []
    : Array.isArray(record.audience)
      ? record.audience
      : [record.audience];
  return audiences.includes(resource);
}

function hasPlaybackScope(scopes: unknown): scopes is string[] {
  return (
    Array.isArray(scopes) &&
    scopes.every((scope) => typeof scope === "string") &&
    scopes.includes(PLAYBACK_SCOPE)
  );
}

function asTokenProps(value: unknown): TokenProps | null {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    !("githubUserId" in value) ||
    typeof value.githubUserId !== "string" ||
    !/^\d+$/u.test(value.githubUserId)
  ) {
    return null;
  }
  return { githubUserId: value.githubUserId };
}

export async function hasPlaybackReadAccess(
  authInfo: McpAuthInfo | undefined,
  props: Record<string, unknown> | undefined,
  env: WorkerEnv,
  expectedResource: string,
): Promise<boolean> {
  const identity = asTokenProps(props);
  return (
    typeof authInfo?.token === "string" &&
    typeof authInfo.clientId === "string" &&
    typeof authInfo.expiresAt === "number" &&
    Number.isFinite(authInfo.expiresAt) &&
    authInfo.expiresAt > Math.floor(Date.now() / 1_000) &&
    authInfo.resource instanceof URL &&
    authInfo.resource.toString() === expectedResource &&
    hasPlaybackScope(authInfo.scopes) &&
    identity !== null &&
    /^\d+$/u.test(env.ALLOWED_GITHUB_USER_ID) &&
    (await constantTimeEqual(identity.githubUserId, env.ALLOWED_GITHUB_USER_ID))
  );
}

async function verifyMcpAccess(
  request: Request,
  env: WorkerEnv,
  props: Record<string, unknown> | undefined,
): Promise<VerifiedMcpAccess | null> {
  const token = bearerToken(request);
  if (!token || !env.OAUTH_PROVIDER) return null;

  const record = await env.OAUTH_PROVIDER.unwrapToken<TokenProps>(token);
  const resource = mcpResource(request);
  const recordProps = asTokenProps(record?.grant.props);
  const requestProps = asTokenProps(props);
  if (
    !record ||
    record.expiresAt <= Math.floor(Date.now() / 1_000) ||
    !tokenAudienceIncludes(record, resource) ||
    !hasPlaybackScope(record.scope) ||
    !recordProps ||
    !requestProps ||
    record.userId !== recordProps.githubUserId ||
    requestProps.githubUserId !== record.userId ||
    !/^\d+$/u.test(env.ALLOWED_GITHUB_USER_ID) ||
    !(await constantTimeEqual(record.userId, env.ALLOWED_GITHUB_USER_ID))
  ) {
    return null;
  }

  return {
    token,
    clientId: record.grant.clientId,
    scopes: [...record.scope],
    expiresAt: record.expiresAt,
    resource,
    // Keep the exact request-context object; agents verifies object identity.
    props: requestProps,
  };
}

function attachVerifiedMcpContext(ctx: ExecutionContext, verified: VerifiedMcpAccess): void {
  const mutable = ctx as ExecutionContext & {
    [VERIFIED_OAUTH_CONTEXT]?: unknown;
  };
  mutable[VERIFIED_OAUTH_CONTEXT] = {
    version: 1,
    token: verified.token,
    clientId: verified.clientId,
    scopes: verified.scopes,
    expiresAt: verified.expiresAt,
    resource: verified.resource,
    props: ctx.props,
  };
}

function createServer(env: WorkerEnv, request: Request): McpServer {
  const server = new McpServer(
    { name: "earphone-wire-mcp", version: "0.2.0" },
    {
      instructions:
        "This is a private, read-only QQ Music playback relay. It reports only the latest phone snapshot. A stale result is the last report and must never be described as current playback.",
    },
  );
  const expectedResource = mcpResource(request);
  const challenge = mcpToolScopeChallenge(request);

  server.registerTool(
    "get_now_playing",
    {
      title: "读取当前播放状态",
      description:
        "Read the latest QQ Music playback snapshot reported by the authorized phone. If stale is true, this is the last report rather than evidence that playback is still active.",
      inputSchema: {},
      outputSchema: nowPlayingOutputSchema,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
      },
      // The MCP SDK version in this repository does not yet serialize the
      // draft tool-level field itself; addToolSecuritySchemes publishes it in
      // the wire response below.
      _meta: { securitySchemes: TOOL_SECURITY_SCHEMES },
    },
    async (_args, context) => {
      const props = getMcpAuthContext()?.props;
      const authInfo = (
        context as unknown as { http?: { authInfo?: McpAuthInfo } }
      ).http?.authInfo;
      if (!(await hasPlaybackReadAccess(authInfo, props, env, expectedResource))) {
        return {
          isError: true,
          content: [{ type: "text" as const, text: "Access denied." }],
          _meta: { "mcp/www_authenticate": [challenge] },
        };
      }

      const snapshot = await env.NOW_PLAYING
        .getByName("earphone-wire-single-user-now-playing-v1")
        .latest();
      const result = toNowPlayingResult(snapshot);
      const text = result.stale
        ? "This is the last phone report and is more than 120 seconds old; it does not indicate that playback is still active."
        : result.available
          ? "Returned a phone playback report received within 120 seconds."
          : "No phone playback snapshot is available.";

      return {
        structuredContent: result,
        content: [{ type: "text" as const, text }],
      };
    },
  );

  return server;
}

async function addToolSecuritySchemes(isToolsList: boolean, response: Response): Promise<Response> {
  if (!isToolsList) return response;

  const addToPayload = (payload: unknown): boolean => {
    const typed = payload as {
      result?: { tools?: Array<{ name?: unknown; securitySchemes?: unknown }> };
    } | null;
    const tool = typed?.result?.tools?.find((entry) => entry.name === "get_now_playing");
    if (!tool) return false;
    tool.securitySchemes = TOOL_SECURITY_SCHEMES;
    return true;
  };
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    const payload = await response.clone().json().catch(() => null);
    if (!addToPayload(payload)) return response;
    const headers = new Headers(response.headers);
    headers.set("content-type", "application/json");
    return new Response(JSON.stringify(payload), {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  }
  if (!contentType.includes("text/event-stream")) return response;

  const eventStream = await response.text();
  let changed = false;
  const rewritten = eventStream.replace(/^data:\s*(.+)$/gmu, (line, json: string) => {
    const payload = JSON.parse(json) as {
    result?: { tools?: Array<{ name?: unknown; securitySchemes?: unknown }> };
    };
    if (!addToPayload(payload)) return line;
    changed = true;
    return `data: ${JSON.stringify(payload)}`;
  });
  if (!changed) return new Response(eventStream, response);
  const headers = new Headers(response.headers);
  return new Response(rewritten, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export const mcpApiHandler: FetchHandler = {
  async fetch(request, env, ctx): Promise<Response> {
    const verified = await verifyMcpAccess(request, env, ctx.props as Record<string, unknown>);
    if (!verified) return unauthorizedMcp(request);
    attachVerifiedMcpContext(ctx, verified);
    const body = await request.clone().json().catch(() => null) as { method?: unknown } | null;

    const response = await createMcpHandler(() => createServer(env, request), {
      route: "/mcp",
      corsOptions: false,
    })(request, env, ctx);
    return addToolSecuritySchemes(body?.method === "tools/list", response);
  },
};

export const defaultHandler: FetchHandler = {
  async fetch(request, env, _ctx): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return noStore(
        Response.json({ ok: true, service: "earphone-wire-mcp", phase: "secure-relay" }),
      );
    }
    if (url.pathname === "/api/v1/now-playing") return handleNowPlayingUpload(request, env);
    if (url.pathname === "/authorize") {
      if (!env.OAUTH_PROVIDER) return noStore(new Response("Authorization unavailable", { status: 503 }));
      return handleAuthorizationRequest(request, env, env.OAUTH_PROVIDER);
    }
    if (url.pathname === "/callback") {
      if (!env.OAUTH_PROVIDER) return noStore(new Response("Authorization unavailable", { status: 503 }));
      return handleGitHubCallback(request, env, env.OAUTH_PROVIDER);
    }
    if (request.method === "GET" && url.pathname === "/") {
      return noStore(
        new Response("Earphone Wire private MCP relay is running.\n", {
          headers: { "content-type": "text/plain; charset=utf-8" },
        }),
      );
    }
    return noStore(new Response("Not found", { status: 404 }));
  },
};

const oauthProvider = new OAuthProvider<WorkerEnv>({
  apiRoute: "/mcp",
  apiHandler: mcpApiHandler,
  defaultHandler,
  authorizeEndpoint: "/authorize",
  tokenEndpoint: "/oauth/token",
  clientRegistrationEndpoint: "/oauth/register",
  clientIdMetadataDocumentEnabled: true,
  scopesSupported: [PLAYBACK_SCOPE],
  allowPlainPKCE: false,
  resourceMetadata: {
    scopes_supported: [PLAYBACK_SCOPE],
    bearer_methods_supported: ["header"],
    resource_name: "Earphone Wire private playback MCP",
  },
  onError: ({ status, headers }) =>
    noStore(new Response("OAuth request rejected", { status, headers })),
});

export const worker: FetchHandler = {
  async fetch(request, env, ctx): Promise<Response> {
    return noStore(await oauthProvider.fetch(request, env, ctx));
  },
};

export default worker;
