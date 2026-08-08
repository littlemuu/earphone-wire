import type { AuthRequest, OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { env } from "cloudflare:workers";
import { reset } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { WorkerEnv } from "../src/env";
import {
  handleAuthorizationRequest,
  OFFLINE_ACCESS_SCOPE,
  PLAYBACK_SCOPE,
} from "../src/github-oauth";
import { worker } from "../src/index";

const ORIGIN = "https://relay.test";
const RESOURCE = `${ORIGIN}/mcp`;
const TEST_USER_ID = "123456789";
const CLIENT_REDIRECT_URI = "https://client.test/callback";
const CODE_VERIFIER = "earphone-wire-refresh-test-code-verifier-000000000000000";

type RegisteredClient = {
  client_id: string;
  redirect_uris: string[];
  token_endpoint_auth_method: string;
};

type TokenSet = {
  access_token: string;
  token_type: string;
  expires_in: number;
  refresh_token?: string;
  scope?: string;
};

function testContext(): ExecutionContext {
  return {
    props: {},
    waitUntil() {},
    passThroughOnException() {},
  } as ExecutionContext;
}

function authorizationRequest(scopes: string[]): Request {
  const url = new URL("/authorize", ORIGIN);
  url.searchParams.set("scope", scopes.join(" "));
  url.searchParams.set("resource", RESOURCE);
  return new Request(url);
}

function oauthHelpers(): OAuthHelpers {
  const helpers = {
    parseAuthRequest: async (request: Request): Promise<AuthRequest> => {
      const url = new URL(request.url);
      const resources = url.searchParams.getAll("resource");
      return {
        responseType: "code",
        clientId: "integration-client",
        redirectUri: CLIENT_REDIRECT_URI,
        scope: (url.searchParams.get("scope") ?? "").split(" ").filter(Boolean),
        state: "integration-state",
        resource: resources.length === 0
          ? undefined
          : resources.length === 1
            ? resources[0]
            : resources,
      };
    },
    lookupClient: async () => ({ clientId: "integration-client" }),
  };
  return helpers as OAuthHelpers;
}

function testEnv(oauth: OAuthHelpers): WorkerEnv {
  return {
    ...(env as WorkerEnv),
    OAUTH_PROVIDER: oauth,
  };
}

async function s256Challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  let binary = "";
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/gu, "-").replace(/\//gu, "_").replace(/=+$/gu, "");
}

async function registerClient(): Promise<RegisteredClient> {
  const response = await worker.fetch(
    new Request(`${ORIGIN}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        client_name: "Earphone Wire refresh integration test",
        redirect_uris: [CLIENT_REDIRECT_URI],
        grant_types: ["authorization_code", "refresh_token"],
        response_types: ["code"],
        token_endpoint_auth_method: "none",
      }),
    }),
    env as WorkerEnv,
    testContext(),
  );
  expect(response.status).toBe(201);
  return response.json() as Promise<RegisteredClient>;
}

async function postToken(params: URLSearchParams): Promise<{ response: Response; tokens?: TokenSet }> {
  const response = await worker.fetch(
    new Request(`${ORIGIN}/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: params,
    }),
    env as WorkerEnv,
    testContext(),
  );
  return {
    response,
    tokens: response.ok ? await response.clone().json() as TokenSet : undefined,
  };
}

function authenticatedMcpRequest(token: string): Request {
  return new Request(RESOURCE, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "content-type": "application/json",
      Accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/list",
      params: {},
    }),
  });
}

afterEach(async () => {
  vi.unstubAllGlobals();
  await reset();
});

describe("OAuth refresh compatibility", () => {
  it("advertises offline_access and refresh support without widening MCP resource scopes", async () => {
    const authorizationMetadataResponse = await worker.fetch(
      new Request(`${ORIGIN}/.well-known/oauth-authorization-server`),
      env as WorkerEnv,
      testContext(),
    );
    expect(authorizationMetadataResponse.status).toBe(200);
    const authorizationMetadata = await authorizationMetadataResponse.json() as {
      scopes_supported?: string[];
      grant_types_supported?: string[];
    };
    expect(authorizationMetadata.scopes_supported).toEqual(
      expect.arrayContaining([PLAYBACK_SCOPE, OFFLINE_ACCESS_SCOPE]),
    );
    expect(authorizationMetadata.grant_types_supported).toContain("refresh_token");

    const resourceMetadataResponse = await worker.fetch(
      new Request(`${ORIGIN}/.well-known/oauth-protected-resource/mcp`),
      env as WorkerEnv,
      testContext(),
    );
    expect(resourceMetadataResponse.status).toBe(200);
    const resourceMetadata = await resourceMetadataResponse.json() as {
      scopes_supported?: string[];
    };
    expect(resourceMetadata.scopes_supported).toEqual([PLAYBACK_SCOPE]);
  });

  it("issues, rotates, and accepts refresh-backed tokens after GitHub authorization", async () => {
    const client = await registerClient();
    expect(client.redirect_uris).toContain(CLIENT_REDIRECT_URI);
    expect(client.token_endpoint_auth_method).toBe("none");

    const authorize = new URL("/authorize", ORIGIN);
    authorize.search = new URLSearchParams({
      response_type: "code",
      client_id: client.client_id,
      redirect_uri: CLIENT_REDIRECT_URI,
      scope: `${PLAYBACK_SCOPE} ${OFFLINE_ACCESS_SCOPE}`,
      state: "earphone-wire-client-state",
      code_challenge: await s256Challenge(CODE_VERIFIER),
      code_challenge_method: "S256",
      resource: RESOURCE,
    }).toString();

    const authorizationResponse = await worker.fetch(
      new Request(authorize),
      env as WorkerEnv,
      testContext(),
    );
    expect(authorizationResponse.status).toBe(302);
    const loginCookie = authorizationResponse.headers.get("set-cookie")?.split(";", 1)[0];
    const githubLocation = authorizationResponse.headers.get("location");
    expect(loginCookie).toBeTruthy();
    expect(githubLocation).toBeTruthy();
    const githubState = new URL(githubLocation!).searchParams.get("state");
    expect(githubState).toBeTruthy();

    const realFetch = globalThis.fetch;
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      if (url.origin === "https://github.com") return Response.json({ access_token: "test-only" });
      if (url.origin === "https://api.github.com") return Response.json({ id: Number(TEST_USER_ID) });
      return realFetch(input, init);
    });

    const callbackResponse = await worker.fetch(
      new Request(`${ORIGIN}/callback?code=test-code&state=${encodeURIComponent(githubState!)}`, {
        headers: { cookie: loginCookie! },
      }),
      env as WorkerEnv,
      testContext(),
    );
    expect(callbackResponse.status).toBe(302);
    const clientRedirect = new URL(callbackResponse.headers.get("location")!);
    expect(clientRedirect.origin + clientRedirect.pathname).toBe(CLIENT_REDIRECT_URI);
    expect(clientRedirect.searchParams.get("state")).toBe("earphone-wire-client-state");
    const authorizationCode = clientRedirect.searchParams.get("code");
    expect(authorizationCode).toBeTruthy();

    const exchange = await postToken(new URLSearchParams({
      grant_type: "authorization_code",
      code: authorizationCode!,
      redirect_uri: CLIENT_REDIRECT_URI,
      code_verifier: CODE_VERIFIER,
      client_id: client.client_id,
      resource: RESOURCE,
    }));
    expect(exchange.response.status).toBe(200);
    expect(exchange.tokens?.access_token).toBeTruthy();
    expect(exchange.tokens?.refresh_token).toBeTruthy();
    expect(exchange.tokens?.scope?.split(" ")).toEqual(
      expect.arrayContaining([PLAYBACK_SCOPE, OFFLINE_ACCESS_SCOPE]),
    );

    const originalRefreshToken = exchange.tokens!.refresh_token!;
    const refresh = await postToken(new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: originalRefreshToken,
      client_id: client.client_id,
      resource: RESOURCE,
    }));
    expect(refresh.response.status).toBe(200);
    expect(refresh.tokens?.access_token).toBeTruthy();
    expect(refresh.tokens?.refresh_token).toBeTruthy();
    expect(refresh.tokens?.refresh_token).not.toBe(originalRefreshToken);

    const authorizedMcp = await worker.fetch(
      authenticatedMcpRequest(refresh.tokens!.access_token),
      env as WorkerEnv,
      testContext(),
    );
    expect(authorizedMcp.status).toBe(200);
  });

  it("still rejects authorization without playback:read or with an unrelated scope", async () => {
    const oauth = oauthHelpers();
    const environment = testEnv(oauth);
    const cases = [
      [OFFLINE_ACCESS_SCOPE],
      [PLAYBACK_SCOPE, OFFLINE_ACCESS_SCOPE, "files:read"],
    ];

    for (const scopes of cases) {
      const response = await handleAuthorizationRequest(
        authorizationRequest(scopes),
        environment,
        oauth,
      );
      expect(response.status).toBe(400);
      await expect(response.text()).resolves.toBe("Authorization denied");
    }
  });
});
