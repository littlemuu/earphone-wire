import {
  Client,
  StreamableHTTPClientTransport,
  type FetchLike,
} from "@modelcontextprotocol/client";

import worker, {
  hasPlaybackReadAccess,
  mcpApiHandler,
  mcpToolScopeChallenge,
  NowPlayingDurableObject,
} from "../src/index";
import type { WorkerEnv } from "../src/env";
import {
  SNAPSHOT_TTL_MS,
  nowPlayingSchema,
  toNowPlayingResult,
  type NowPlayingSnapshot,
} from "../src/now-playing";
import { handleNowPlayingUpload } from "../src/upload";

let passed = 0;

function check(condition: unknown, label: string): asserts condition {
  if (!condition) throw new Error(`Failed: ${label}`);
  passed += 1;
}

class MemoryNowPlayingStore {
  snapshot: NowPlayingSnapshot | null = null;
  writes = 0;

  async store(snapshot: NowPlayingSnapshot): Promise<"stored" | "idempotent" | "older"> {
    if (this.snapshot?.eventId === snapshot.eventId) return "idempotent";
    if (this.snapshot && Date.parse(snapshot.observedAt) < Date.parse(this.snapshot.observedAt)) {
      return "older";
    }
    this.snapshot = snapshot;
    this.writes += 1;
    return "stored";
  }

  async latest(now = Date.now()): Promise<NowPlayingSnapshot | null> {
    if (this.snapshot && Date.parse(this.snapshot.receivedAt) + SNAPSHOT_TTL_MS <= now) {
      this.snapshot = null;
    }
    return this.snapshot;
  }
}

type DoRow = {
  event_id: string;
  title: string;
  artist: string;
  playback_state: NowPlayingSnapshot["playbackState"];
  player_package: "com.tencent.qqmusic";
  observed_at: string;
  observed_at_ms: number;
  received_at: string;
  received_at_ms: number;
};

class FakeSqlStorage {
  row: DoRow | null = null;

  exec<T extends Record<string, string | number | ArrayBuffer | null>>(
    query: string,
    ...bindings: Array<string | number | ArrayBuffer | null>
  ): { toArray(): T[] } {
    if (query.includes("INSERT INTO now_playing_snapshot")) {
      const [eventId, title, artist, playbackState, playerPackage, observedAt, observedAtMs, receivedAt, receivedAtMs] = bindings;
      this.row = {
        event_id: String(eventId),
        title: String(title),
        artist: String(artist),
        playback_state: String(playbackState) as DoRow["playback_state"],
        player_package: "com.tencent.qqmusic",
        observed_at: String(observedAt),
        observed_at_ms: Number(observedAtMs),
        received_at: String(receivedAt),
        received_at_ms: Number(receivedAtMs),
      };
    } else if (query.includes("DELETE FROM now_playing_snapshot")) {
      this.row = null;
    }
    return { toArray: () => (query.includes("SELECT") && this.row ? [this.row as unknown as T] : []) };
  }
}

class FakeDurableObjectState {
  readonly sql = new FakeSqlStorage();
  alarm: number | null = null;
  readonly storage = {
    sql: this.sql,
    setAlarm: async (scheduledTime: number | Date) => {
      this.alarm = Number(scheduledTime);
    },
    deleteAlarm: async () => {
      this.alarm = null;
    },
  };

  blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T> {
    return callback();
  }
}

const uploadToken = crypto.randomUUID();
const allowedGithubUserId = String(Math.floor(Math.random() * 900_000_000) + 100_000_000);
const store = new MemoryNowPlayingStore();
const env: WorkerEnv = {
  NOW_PLAYING: { getByName: () => store } as unknown as DurableObjectNamespace<NowPlayingDurableObject>,
  OAUTH_KV: {} as KVNamespace,
  ANDROID_UPLOAD_TOKEN: uploadToken,
  ALLOWED_GITHUB_USER_ID: allowedGithubUserId,
  GITHUB_CLIENT_ID: crypto.randomUUID(),
  GITHUB_CLIENT_SECRET: crypto.randomUUID(),
  COOKIE_ENCRYPTION_KEY: crypto.randomUUID(),
  OAUTH_PROVIDER: {
    unwrapToken: async (token: string) => {
      if (token === "unit-valid-token") {
        return {
          userId: allowedGithubUserId,
          expiresAt: Math.floor(Date.now() / 1_000) + 60,
          audience: "http://localhost/mcp",
          scope: ["playback:read"],
          grant: { clientId: "unit-client", scope: ["playback:read"], props: { githubUserId: allowedGithubUserId } },
        };
      }
      if (token === "unit-no-scope-token") {
        return {
          userId: allowedGithubUserId,
          expiresAt: Math.floor(Date.now() / 1_000) + 60,
          audience: "http://localhost/mcp",
          scope: [],
          grant: { clientId: "unit-client", scope: ["playback:read"], props: { githubUserId: allowedGithubUserId } },
        };
      }
      return null;
    },
  } as WorkerEnv["OAUTH_PROVIDER"],
};

function context(props: Record<string, unknown> = {}): ExecutionContext {
  return {
    waitUntil() {},
    passThroughOnException() {},
    props,
  } as unknown as ExecutionContext;
}

function uploadRequest(
  payload: unknown,
  token = uploadToken,
  contentType = "application/json",
): Request {
  return new Request("http://localhost/api/v1/now-playing", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "content-type": contentType,
    },
    body: JSON.stringify(payload),
  });
}

function payload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    eventId: crypto.randomUUID(),
    title: "Test title",
    artist: "Test artist",
    playbackState: "playing",
    playerPackage: "com.tencent.qqmusic",
    observedAt: new Date(Date.now() + 10_000).toISOString(),
    ...overrides,
  };
}

const noAuthorization = await handleNowPlayingUpload(
  new Request("http://localhost/api/v1/now-playing", { method: "POST" }),
  env,
);
check(noAuthorization.status === 401, "missing upload authorization is 401");

const wrongToken = await handleNowPlayingUpload(uploadRequest(payload(), crypto.randomUUID()), env);
check(wrongToken.status === 401, "wrong upload token is 401");

const wrongContentType = await handleNowPlayingUpload(uploadRequest(payload(), uploadToken, "text/plain"), env);
check(wrongContentType.status === 415, "non-JSON upload is 415");

const tooLarge = await handleNowPlayingUpload(
  new Request("http://localhost/api/v1/now-playing", {
    method: "POST",
    headers: { Authorization: `Bearer ${uploadToken}`, "content-type": "application/json" },
    body: "x".repeat(4_097),
  }),
  env,
);
check(tooLarge.status === 413, "oversized upload is 413");

for (const [label, invalid] of [
  ["missing field", (() => { const value = payload(); delete value.title; return value; })()],
  ["extra field", payload({ available: true })],
  ["invalid state", payload({ playbackState: "not-playing" })],
  ["wrong package", payload({ playerPackage: "other.package" })],
  ["invalid timestamp", payload({ observedAt: "2026-01-01T00:00:00" })],
] as const) {
  const response = await handleNowPlayingUpload(uploadRequest(invalid), env);
  check(response.status === 400, `${label} is 400`);
}

const firstPayload = payload({ observedAt: new Date(Date.now() + 20_000).toISOString() });
const firstUpload = await handleNowPlayingUpload(uploadRequest(firstPayload), env);
check(firstUpload.status === 204 && firstUpload.headers.get("cache-control") === "no-store", "valid upload is a no-store 204");
const writesAfterFirst = store.writes;

const retry = await handleNowPlayingUpload(uploadRequest(firstPayload), env);
check(retry.status === 204 && store.writes === writesAfterFirst, "same event ID retry is idempotent");

const older = await handleNowPlayingUpload(
  uploadRequest(payload({ observedAt: new Date(Date.now() - 20_000).toISOString(), title: "Older title" })),
  env,
);
check(older.status === 409 && store.writes === writesAfterFirst, "older event cannot overwrite current snapshot");

const replacement = payload({ observedAt: new Date(Date.now() + 30_000).toISOString(), title: "Replacement title" });
const newer = await handleNowPlayingUpload(uploadRequest(replacement), env);
check(newer.status === 204 && store.snapshot?.title === "Replacement title", "newer event atomically replaces snapshot");

const empty = toNowPlayingResult(null);
check(!empty.available && empty.title === null && !empty.stale && empty.source === "phone", "empty snapshot output");
const freshSnapshot = { ...store.snapshot!, receivedAt: new Date().toISOString() };
check(toNowPlayingResult(freshSnapshot).available, "snapshot within 120 seconds is fresh");
const staleSnapshot = { ...freshSnapshot, receivedAt: new Date(Date.now() - 121_000).toISOString() };
const stale = toNowPlayingResult(staleSnapshot);
check(!stale.available && stale.stale && stale.title === "Replacement title", "stale snapshot is last report");
const expiredSnapshot = { ...freshSnapshot, receivedAt: new Date(Date.now() - SNAPSHOT_TTL_MS).toISOString() };
check(toNowPlayingResult(expiredSnapshot).title === null, "24-hour-old snapshot is unavailable");
store.snapshot = expiredSnapshot;
check((await store.latest(Date.now())) === null, "24-hour expiry deletes the stored snapshot");
await store.store(freshSnapshot);

const doState = new FakeDurableObjectState();
const actualDo = new NowPlayingDurableObject(doState as unknown as DurableObjectState, env);
check((await actualDo.store(freshSnapshot)) === "stored", "Durable Object stores its first snapshot");
check((await actualDo.store(freshSnapshot)) === "idempotent", "Durable Object event retry is idempotent");
check(
  (await actualDo.store({ ...freshSnapshot, eventId: crypto.randomUUID(), observedAt: new Date(Date.parse(freshSnapshot.observedAt) - 1).toISOString() })) === "older",
  "Durable Object rejects an older observedAt",
);
const actualReplacement = { ...freshSnapshot, eventId: crypto.randomUUID(), title: "DO replacement", observedAt: new Date(Date.parse(freshSnapshot.observedAt) + 1).toISOString() };
check((await actualDo.store(actualReplacement)) === "stored" && (await actualDo.latest())?.title === "DO replacement", "Durable Object replaces the single row");
check(
  (await actualDo.latest(Date.parse(actualReplacement.receivedAt) + SNAPSHOT_TTL_MS)) === null && doState.sql.row === null,
  "Durable Object removes an expired row",
);

const anonymousMcp = await worker.fetch(
  new Request("http://localhost/mcp", {
    method: "POST",
    headers: { "content-type": "application/json", Accept: "application/json, text/event-stream", host: "localhost" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "unit", version: "1.0.0" } },
    }),
  }),
  env,
  context(),
);
check(anonymousMcp.status === 200, "anonymous MCP initialize reaches the discovery handler");
const toolScopeChallenge = mcpToolScopeChallenge(new Request("http://localhost/mcp"));
check(
  toolScopeChallenge.includes("resource_metadata=") &&
    toolScopeChallenge.includes('scope="playback:read"') &&
    toolScopeChallenge.includes('error="insufficient_scope"') &&
    toolScopeChallenge.includes('error_description="'),
  "tool OAuth challenge advertises the required scope without sensitive details",
);

check(
  !(await hasPlaybackReadAccess(
    { token: "unit-valid-token", clientId: "unit-client", scopes: ["playback:read"], expiresAt: Math.floor(Date.now() / 1_000) + 60, resource: new URL("http://localhost/mcp") },
    { githubUserId: String(Number(allowedGithubUserId) + 1) },
    env,
    "http://localhost/mcp",
  )),
  "non-allowed GitHub numeric ID is rejected",
);
check(
  !(await hasPlaybackReadAccess(
    { token: "unit-valid-token", clientId: "unit-client", scopes: [], expiresAt: Math.floor(Date.now() / 1_000) + 60, resource: new URL("http://localhost/mcp") },
    { githubUserId: allowedGithubUserId },
    env,
    "http://localhost/mcp",
  )),
  "missing playback:read scope is rejected",
);
check(
  await hasPlaybackReadAccess(
    { token: "unit-valid-token", clientId: "unit-client", scopes: ["playback:read"], expiresAt: Math.floor(Date.now() / 1_000) + 60, resource: new URL("http://localhost/mcp") },
    { githubUserId: allowedGithubUserId },
    env,
    "http://localhost/mcp",
  ),
  "current token-derived authorization is accepted",
);

const forbiddenMcp = await mcpApiHandler.fetch(
  new Request("http://localhost/mcp", { method: "POST", headers: { Authorization: "Bearer unit-no-scope-token" }, body: "{}" }),
  env,
  context({ githubUserId: allowedGithubUserId }),
);
check(
  forbiddenMcp.status === 401 &&
    forbiddenMcp.headers.get("www-authenticate")?.includes("resource_metadata") &&
    forbiddenMcp.headers.get("www-authenticate")?.includes('error="invalid_token"') &&
    forbiddenMcp.headers.get("www-authenticate")?.includes('error_description="Authentication is required."'),
  "MCP HTTP failures use a generic invalid-token challenge",
);

const authorizedProps = { githubUserId: allowedGithubUserId };
const localFetch: FetchLike = async (input, init) => {
  const incoming = input instanceof Request ? new Request(input, init) : new Request(input.toString(), init);
  const headers = new Headers(incoming.headers);
  headers.set("host", "localhost");
  headers.set("authorization", "Bearer unit-valid-token");
  return mcpApiHandler.fetch(new Request(incoming, { headers }), env, context(authorizedProps));
};
const client = new Client({ name: "earphone-wire-test", version: "0.2.0" });
try {
  await client.connect(new StreamableHTTPClientTransport(new URL("http://localhost/mcp"), { fetch: localFetch }));
  const { tools } = await client.listTools();
  const tool = tools.find((entry) => entry.name === "get_now_playing");
  check(
    tool?.annotations?.readOnlyHint === true &&
      tool.annotations?.destructiveHint === false &&
      tool.annotations?.openWorldHint === false,
    "MCP tool safety annotations are preserved",
  );
  const result = await client.callTool({ name: "get_now_playing", arguments: {} });
  const parsed = nowPlayingSchema.safeParse(result.structuredContent);
  check(parsed.success && parsed.data.title === "Replacement title", "authorized MCP call returns the current output schema");
} finally {
  await client.close();
}

const health = await worker.fetch(new Request("http://localhost/health"), env, context());
const healthBody = await health.text();
check(
  health.status === 200 && !healthBody.includes("Replacement title") && health.headers.get("cache-control") === "no-store",
  "health endpoint exposes only service state",
);

console.log(`Passed ${passed} security and relay checks.`);
