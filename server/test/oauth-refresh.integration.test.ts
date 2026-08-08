import type { AuthRequest, OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { env } from "cloudflare:workers";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { WorkerEnv } from "../src/env";
import {
  handleAuthorizationRequest,
  handleGitHubCallback,
  OFFLINE_ACCESS_SCOPE,
  PLAYBACK_SCOPE,
} from "../src/github-oauth";
import { worker } from "../src/index";

const ORIGIN = "https://relay.test";
const RESOURCE = `${ORIGIN}/mcp`;
const TEST_USER_ID = "123456789";

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

function oauthHelpers(onComplete?: (scope: string[]) => void): OAuthHelpers {
  const helpers = {
    parseAuthRequest: async (request: Request): Promise<AuthRequest> => {
      const url = new URL(request.url);
      const resources = url.searchParams.getAll("resource");
      return {
        responseType: "code",
        clientId: "integration-client",
        redirectUri: "https://client.test/callback",
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
    completeAuthorization: async (
      options: Parameters<OAuthHelpers["completeAuthorization"]>[0],
    ) => {
      onComplete?.([...options.scope]);
      return { redirectTo: "https://client.test/callback?code=integration-code" };
    },
  };
  return helpers as OAuthHelpers;
}

function testEnv(oauth: OAuthHelpers): WorkerEnv {
  return {
    ...(env as WorkerEnv),
    OAUTH_PROVIDER: oauth,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("OAuth refresh compatibility", () => {
  it("advertises offline_access and the refresh-token grant without widening MCP resource scopes", async () => {
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

  it("preserves offline_access through GitHub login and grants it with playback:read", async () => {
    let completedScopes: string[] | undefined;
    const oauth = oauthHelpers((scope) => {
      completedScopes = scope;
    });
    const environment = testEnv(oauth);

    const authorize = await handleAuthorizationRequest(
      authorizationRequest([PLAYBACK_SCOPE, OFFLINE_ACCESS_SCOPE]),
      environment,
      oauth,
    );
    expect(authorize.status).toBe(302);
    const cookie = authorize.headers.get("set-cookie");
    expect(cookie).toBeTruthy();
    const state = new URL(authorize.headers.get("location")!).searchParams.get("state");
    expect(state).toBeTruthy();

    const realFetch = globalThis.fetch;
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      if (url.origin === "https://github.com") return Response.json({ access_token: "test-only" });
      if (url.origin === "https://api.github.com") return Response.json({ id: Number(TEST_USER_ID) });
      return realFetch(input, init);
    });

    const callback = await handleGitHubCallback(
      new Request(`${ORIGIN}/callback?code=test-code&state=${encodeURIComponent(state!)}`, {
        headers: { cookie: cookie! },
      }),
      environment,
      oauth,
    );
    expect(callback.status).toBe(302);
    expect(completedScopes).toEqual([PLAYBACK_SCOPE, OFFLINE_ACCESS_SCOPE]);
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
