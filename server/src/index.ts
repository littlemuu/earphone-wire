import { OAuthProvider } from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler, getMcpAuthContext } from "agents/mcp/server";

import type { WorkerEnv } from "./env";
import {
  handleAuthorizationRequest,
  handleGitHubCallback,
  PLAYBACK_SCOPE,
} from "./github-oauth";
import {
  nowPlayingOutputSchema,
  toNowPlayingResult,
} from "./now-playing";
import { constantTimeEqual } from "./security";
import { handleNowPlayingUpload, noStore } from "./upload";

export { NowPlayingDurableObject } from "./now-playing-do";

type FetchHandler = ExportedHandler<WorkerEnv> & {
  fetch: NonNullable<ExportedHandler<WorkerEnv>["fetch"]>;
};

export async function hasPlaybackReadAccess(
  props: Record<string, unknown> | undefined,
  env: WorkerEnv,
): Promise<boolean> {
  const githubUserId = props?.githubUserId;
  const scopes = props?.scopes;
  return (
    typeof githubUserId === "string" &&
    /^\d+$/u.test(githubUserId) &&
    /^\d+$/u.test(env.ALLOWED_GITHUB_USER_ID) &&
    Array.isArray(scopes) &&
    scopes.every((scope) => typeof scope === "string") &&
    scopes.includes(PLAYBACK_SCOPE) &&
    (await constantTimeEqual(githubUserId, env.ALLOWED_GITHUB_USER_ID))
  );
}

function createServer(env: WorkerEnv): McpServer {
  const server = new McpServer(
    { name: "earphone-wire-mcp", version: "0.2.0" },
    {
      instructions:
        "This is a private, read-only QQ Music playback relay. It reports only the latest phone snapshot. A stale result is the last report and must never be described as current playback.",
    },
  );

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
    },
    async () => {
      // Re-check authorization at tool invocation, rather than relying on tool
      // instructions or on the initial MCP routing decision.
      if (!(await hasPlaybackReadAccess(getMcpAuthContext()?.props, env))) {
        return {
          isError: true,
          content: [{ type: "text" as const, text: "Access denied." }],
        };
      }

      const snapshot = await env.NOW_PLAYING
        .getByName("earphone-wire-single-user-now-playing-v1")
        .latest();
      const result = toNowPlayingResult(snapshot);
      const text = result.stale
        ? "该结果是最后一次上报，已超过 120 秒；不能表明手机仍在播放。"
        : result.available
          ? "已返回 120 秒内的手机上报播放状态。"
          : "没有可用的手机播放快照。";

      return {
        structuredContent: result,
        content: [{ type: "text" as const, text }],
      };
    },
  );

  return server;
}

export const mcpApiHandler: FetchHandler = {
  async fetch(request, env, ctx): Promise<Response> {
    const props = (ctx.props ?? {}) as Record<string, unknown>;
    if (!(await hasPlaybackReadAccess(props, env))) {
      return noStore(new Response("Access denied", { status: 403 }));
    }

    return createMcpHandler(() => createServer(env), {
      route: "/mcp",
      corsOptions: false,
      authContext: { props },
    })(request, env, ctx);
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
    if (url.pathname === "/api/v1/now-playing") {
      return handleNowPlayingUpload(request, env);
    }
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
  // The provider's default error observer logs OAuth failures. Keep failures
  // generic and silent so credentials and request details never reach logs.
  onError: ({ status, headers }) =>
    noStore(new Response("OAuth request rejected", { status, headers })),
});

export const worker: FetchHandler = {
  async fetch(request, env, ctx): Promise<Response> {
    return noStore(await oauthProvider.fetch(request, env, ctx));
  },
};

export default worker;
