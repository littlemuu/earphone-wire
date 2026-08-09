import type { WorkerEnv } from "./env";
import { toNowPlayingResult } from "./now-playing";
import { constantTimeEqual } from "./security";
import { noStore } from "./upload";

const SINGLETON_DO_NAME = "earphone-wire-single-user-now-playing-v1";

function bearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  const match = authorization?.match(/^Bearer ([^\s]+)$/u);
  return match?.[1] ?? null;
}

function text(status: number, message: string, extraHeaders: HeadersInit = {}): Response {
  return noStore(
    new Response(message, {
      status,
      headers: {
        "content-type": "text/plain; charset=utf-8",
        ...extraHeaders,
      },
    }),
  );
}

/**
 * A server-to-server read path for the private website relay.
 * It deliberately has no CORS headers and uses a credential distinct from
 * both the Android uploader and the MCP OAuth grant.
 */
export async function handleSiteNowPlayingRead(
  request: Request,
  env: WorkerEnv,
): Promise<Response> {
  if (request.method !== "GET") {
    return text(405, "Method not allowed", { Allow: "GET" });
  }

  const token = bearerToken(request);
  if (
    !token ||
    !env.SITE_READ_TOKEN ||
    !(await constantTimeEqual(token, env.SITE_READ_TOKEN))
  ) {
    return text(401, "Unauthorized", { "WWW-Authenticate": "Bearer" });
  }

  const snapshot = await env.NOW_PLAYING
    .getByName(SINGLETON_DO_NAME)
    .latest();
  const response = Response.json(toNowPlayingResult(snapshot));
  response.headers.set("Vary", "Authorization");
  return noStore(response);
}

