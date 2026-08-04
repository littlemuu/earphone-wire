import {
  nowPlayingUploadSchema,
  type NowPlayingSnapshot,
} from "./now-playing";
import type { WorkerEnv } from "./env";
import { constantTimeEqual } from "./security";

const MAX_UPLOAD_BYTES = 4 * 1024;
const SINGLETON_DO_NAME = "earphone-wire-single-user-now-playing-v1";

export function noStore(response: Response): Response {
  response.headers.set("Cache-Control", "no-store");
  return response;
}

function simpleResponse(status: number, message: string): Response {
  return noStore(
    new Response(message, {
      status,
      headers: { "content-type": "text/plain; charset=utf-8" },
    }),
  );
}

async function readBodyAtMost(request: Request): Promise<Uint8Array | "too-large"> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && /^\d+$/u.test(declaredLength) && Number(declaredLength) > MAX_UPLOAD_BYTES) {
    return "too-large";
  }

  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_UPLOAD_BYTES) {
        await reader.cancel();
        return "too-large";
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

function bearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  const match = authorization?.match(/^Bearer ([^\s]+)$/u);
  return match?.[1] ?? null;
}

function isJsonContentType(request: Request): boolean {
  return request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() === "application/json";
}

export async function handleNowPlayingUpload(
  request: Request,
  env: WorkerEnv,
): Promise<Response> {
  if (request.method !== "POST") return simpleResponse(405, "Method not allowed");

  const suppliedToken = bearerToken(request);
  if (!suppliedToken || !env.ANDROID_UPLOAD_TOKEN || !(await constantTimeEqual(suppliedToken, env.ANDROID_UPLOAD_TOKEN))) {
    return simpleResponse(401, "Unauthorized");
  }
  if (!isJsonContentType(request)) return simpleResponse(415, "Unsupported media type");

  const rawBody = await readBodyAtMost(request);
  if (rawBody === "too-large") return simpleResponse(413, "Payload too large");

  let payload: unknown;
  try {
    payload = JSON.parse(new TextDecoder().decode(rawBody));
  } catch {
    return simpleResponse(400, "Invalid payload");
  }

  const parsed = nowPlayingUploadSchema.safeParse(payload);
  if (!parsed.success) return simpleResponse(400, "Invalid payload");

  const snapshot: NowPlayingSnapshot = {
    ...parsed.data,
    source: "phone",
    receivedAt: new Date().toISOString(),
  };
  const outcome = await env.NOW_PLAYING.getByName(SINGLETON_DO_NAME).store(snapshot);
  if (outcome === "older") return simpleResponse(409, "Older event rejected");
  return noStore(new Response(null, { status: 204 }));
}
