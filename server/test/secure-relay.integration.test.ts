import { env } from "cloudflare:workers";
import { runInDurableObject, reset } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";

import { defaultHandler, mcpApiHandler, NowPlayingDurableObject } from "../src/index";
import type { WorkerEnv } from "../src/env";
import type { NowPlayingSnapshot } from "../src/now-playing";
import { handleNowPlayingUpload } from "../src/upload";

const ORIGIN = "https://relay.test";
const RESOURCE = `${ORIGIN}/mcp`;
const TEST_USER_ID = "123456789";

function testContext(props: Record<string, unknown> = {}): ExecutionContext {
  return {
    props,
    waitUntil() {},
    passThroughOnException() {},
  } as ExecutionContext;
}

function snapshot(overrides: Partial<NowPlayingSnapshot> = {}): NowPlayingSnapshot {
  return {
    eventId: crypto.randomUUID(),
    title: "Integration test title",
    artist: "Integration test artist",
    playbackState: "playing",
    playerPackage: "com.tencent.qqmusic",
    observedAt: new Date().toISOString(),
    receivedAt: new Date().toISOString(),
    source: "phone",
    ...overrides,
  };
}

function oauthHelpers() {
  return {
    parseAuthRequest: async (request: Request) => {
      const url = new URL(request.url);
      const resources = url.searchParams.getAll("resource");
      return {
        responseType: "code",
        clientId: "integration-client",
        redirectUri: "https://client.test/callback",
        scope: ["playback:read"],
        state: "integration-state",
        resource: resources.length === 0 ? undefined : resources.length === 1 ? resources[0] : resources,
      };
    },
    lookupClient: async () => ({ clientId: "integration-client" }),
    completeAuthorization: async () => ({ redirectTo: "https://client.test/callback?code=integration-code" }),
  };
}

function tokenRecord(overrides: Record<string, unknown> = {}) {
  return {
    userId: TEST_USER_ID,
    expiresAt: Math.floor(Date.now() / 1_000) + 300,
    audience: RESOURCE,
    scope: ["playback:read"],
    grant: {
      clientId: "integration-client",
      scope: ["playback:read"],
      props: { githubUserId: TEST_USER_ID },
    },
    ...overrides,
  };
}

function relayEnv(records: Record<string, Record<string, unknown> | null> = {}): WorkerEnv {
  return {
    ...(env as WorkerEnv),
    OAUTH_PROVIDER: {
      unwrapToken: async (token: string) => records[token] ?? null,
    } as WorkerEnv["OAUTH_PROVIDER"],
  };
}

function mcpRequest(method: string, token: string): Request {
  return new Request(`${RESOURCE}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "content-type": "application/json",
      Accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params: method === "tools/call" ? { name: "get_now_playing", arguments: {} } : {} }),
  });
}

async function mcpPayload(response: Response): Promise<unknown> {
  if (response.headers.get("content-type")?.includes("application/json")) return response.json();
  const event = (await response.text()).match(/^data:\s*(.+)$/mu)?.[1];
  if (!event) throw new Error("MCP response did not contain a data event");
  return JSON.parse(event);
}

afterEach(async () => {
  vi.unstubAllGlobals();
  await reset();
});

describe("secure relay in Workerd/Miniflare", () => {
  it("returns a mutable-safe authorization redirect with cookie and no-store", async () => {
    const response = await defaultHandler.fetch(
      new Request(`${ORIGIN}/authorize?resource=${encodeURIComponent(RESOURCE)}`),
      { ...(env as WorkerEnv), OAUTH_PROVIDER: oauthHelpers() as WorkerEnv["OAUTH_PROVIDER"] },
      testContext(),
    );

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toContain("github.com/login/oauth/authorize");
    expect(response.headers.get("set-cookie")).toContain("__Host-earphone-wire-oauth=");
    expect(response.headers.get("cache-control")).toBe("no-store");
  });

  it("returns a callback redirect and clears its sealed login cookie", async () => {
    const testEnv = { ...(env as WorkerEnv), OAUTH_PROVIDER: oauthHelpers() as WorkerEnv["OAUTH_PROVIDER"] };
    const authorize = await defaultHandler.fetch(
      new Request(`${ORIGIN}/authorize?resource=${encodeURIComponent(RESOURCE)}`),
      testEnv,
      testContext(),
    );
    const cookie = authorize.headers.get("set-cookie");
    expect(cookie).toBeTruthy();
    const state = new URL(authorize.headers.get("location")!).searchParams.get("state")!;

    const realFetch = globalThis.fetch;
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      if (url.origin === "https://github.com") return Response.json({ access_token: "test-only" });
      if (url.origin === "https://api.github.com") return Response.json({ id: Number(TEST_USER_ID) });
      return realFetch(input, init);
    });
    const callback = await defaultHandler.fetch(
      new Request(`${ORIGIN}/callback?code=test-code&state=${state}`, { headers: { cookie: cookie! } }),
      testEnv,
      testContext(),
    );

    expect(callback.status).toBe(302);
    expect(callback.headers.get("location")).toContain("https://client.test/callback");
    expect(callback.headers.get("set-cookie")).toContain("Max-Age=0");
    expect(callback.headers.get("cache-control")).toBe("no-store");
  });

  it("rejects missing, mismatched, and multiple authorization resources", async () => {
    const testEnv = { ...(env as WorkerEnv), OAUTH_PROVIDER: oauthHelpers() as WorkerEnv["OAUTH_PROVIDER"] };
    for (const search of ["", "?resource=https%3A%2F%2Fother.test%2Fmcp", `?resource=${encodeURIComponent(RESOURCE)}&resource=${encodeURIComponent(RESOURCE)}`]) {
      const response = await defaultHandler.fetch(
        new Request(`${ORIGIN}/authorize${search}`),
        testEnv,
        testContext(),
      );
      expect(response.status).toBe(400);
    }
  });

  it("writes through a real Durable Object RPC and reads the resulting state via MCP", async () => {
    const testEnv = relayEnv({ valid: tokenRecord() });
    const uploaded = snapshot();
    const { receivedAt: _receivedAt, source: _source, ...uploadPayload } = uploaded;
    const upload = await handleNowPlayingUpload(
      new Request(`${ORIGIN}/api/v1/now-playing`, {
        method: "POST",
        headers: { Authorization: `Bearer ${(env as WorkerEnv).ANDROID_UPLOAD_TOKEN}`, "content-type": "application/json" },
        body: JSON.stringify(uploadPayload),
      }),
      testEnv,
    );
    expect(upload.status).toBe(204);

    const list = await mcpApiHandler.fetch(
      mcpRequest("tools/list", "valid"),
      testEnv,
      testContext({ githubUserId: TEST_USER_ID }),
    );
    expect(list.status).toBe(200);
    const listPayload = await mcpPayload(list) as { result: { tools: Array<{ name: string; securitySchemes?: unknown }> } };
    const tool = listPayload.result.tools.find((entry) => entry.name === "get_now_playing");
    expect(tool?.securitySchemes).toEqual([{ type: "oauth2", scopes: ["playback:read"] }]);

    const result = await mcpApiHandler.fetch(
      mcpRequest("tools/call", "valid"),
      testEnv,
      testContext({ githubUserId: TEST_USER_ID }),
    );
    expect(result.status).toBe(200);
    const payload = await mcpPayload(result) as { result: { structuredContent: { available: boolean; source: string } } };
    expect(payload.result.structuredContent).toMatchObject({ available: true, source: "phone" });
  });

  it("returns a protected-resource challenge for missing audience, wrong audience, or missing actual token scope", async () => {
    const cases = {
      "no-audience": tokenRecord({ audience: undefined }),
      "wrong-audience": tokenRecord({ audience: "https://other.test/mcp" }),
      "no-token-scope": tokenRecord({ scope: [] }),
    };
    const testEnv = relayEnv(cases);
    for (const token of Object.keys(cases)) {
      const response = await mcpApiHandler.fetch(
        mcpRequest("tools/list", token),
        testEnv,
        testContext({ githubUserId: TEST_USER_ID }),
      );
      expect(response.status).toBe(401);
      expect(response.headers.get("www-authenticate")).toContain(`${ORIGIN}/.well-known/oauth-protected-resource/mcp`);
    }
  });

  it("re-arms a real Durable Object alarm when an idempotent retry follows a failed alarm write", async () => {
    const stub = (env as WorkerEnv).NOW_PLAYING.getByName("integration-alarm-retry");
    const value = snapshot();
    await runInDurableObject(stub, async (instance, state) => {
      const storage = state.storage as DurableObjectState["storage"] & { setAlarm: (time: number | Date) => Promise<void> };
      const original = storage.setAlarm.bind(storage);
      let failOnce = true;
      Object.defineProperty(storage, "setAlarm", {
        configurable: true,
        value: async (time: number | Date) => {
          if (failOnce) {
            failOnce = false;
            throw new Error("test alarm failure");
          }
          await original(time);
        },
      });
      await expect(instance.store(value)).rejects.toThrow("test alarm failure");
      await expect(instance.store(value)).resolves.toBe("idempotent");
      Object.defineProperty(storage, "setAlarm", { configurable: true, value: original });
    });
  });
});
