import type {
  AuthRequest,
  OAuthHelpers,
} from "@cloudflare/workers-oauth-provider";

import type { WorkerEnv } from "./env";
import {
  constantTimeEqual,
  fromBase64Url,
  parseCookie,
  toBase64Url,
} from "./security";
import { noStore } from "./upload";

const LOGIN_COOKIE = "__Host-earphone-wire-oauth";
const LOGIN_TTL_MS = 10 * 60 * 1_000;
const PLAYBACK_SCOPE = "playback:read";

type LoginCookie = {
  request: AuthRequest;
  githubState: string;
  expiresAt: number;
};

function text(status: number, message: string): Response {
  return noStore(
    new Response(message, {
      status,
      headers: { "content-type": "text/plain; charset=utf-8" },
    }),
  );
}

async function cookieKey(secret: string): Promise<CryptoKey> {
  const material = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  return crypto.subtle.importKey("raw", material, "AES-GCM", false, ["encrypt", "decrypt"]);
}

async function sealCookie(value: LoginCookie, secret: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      await cookieKey(secret),
      new TextEncoder().encode(JSON.stringify(value)),
    ),
  );
  const packed = new Uint8Array(iv.length + encrypted.length);
  packed.set(iv);
  packed.set(encrypted, iv.length);
  return toBase64Url(packed);
}

async function unsealCookie(value: string, secret: string): Promise<LoginCookie | null> {
  const packed = fromBase64Url(value);
  if (!packed || packed.length <= 12) return null;
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: packed.slice(0, 12) },
      await cookieKey(secret),
      packed.slice(12),
    );
    const parsed: unknown = JSON.parse(new TextDecoder().decode(plaintext));
    if (
      !parsed ||
      typeof parsed !== "object" ||
      !("request" in parsed) ||
      !("githubState" in parsed) ||
      !("expiresAt" in parsed) ||
      typeof parsed.githubState !== "string" ||
      typeof parsed.expiresAt !== "number"
    ) {
      return null;
    }
    return parsed as LoginCookie;
  } catch {
    return null;
  }
}

function loginCookie(value: string, maxAge: number): string {
  return `${LOGIN_COOKIE}=${value}; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=${maxAge}`;
}

function isSupportedScope(scope: string[]): boolean {
  return scope.length > 0 && scope.every((entry) => entry === PLAYBACK_SCOPE);
}

async function githubUserId(code: string, origin: string, env: WorkerEnv): Promise<string | null> {
  const tokenResponse = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "content-type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      client_id: env.GITHUB_CLIENT_ID,
      client_secret: env.GITHUB_CLIENT_SECRET,
      code,
      redirect_uri: `${origin}/callback`,
    }),
  });
  if (!tokenResponse.ok) return null;

  const tokenPayload: unknown = await tokenResponse.json().catch(() => null);
  if (
    !tokenPayload ||
    typeof tokenPayload !== "object" ||
    !("access_token" in tokenPayload) ||
    typeof tokenPayload.access_token !== "string" ||
    tokenPayload.access_token.length === 0
  ) {
    return null;
  }

  // The GitHub token is used only for this identity lookup and is never stored.
  const userResponse = await fetch("https://api.github.com/user", {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${tokenPayload.access_token}`,
      "User-Agent": "earphone-wire-mcp",
    },
  });
  if (!userResponse.ok) return null;
  const userPayload: unknown = await userResponse.json().catch(() => null);
  if (
    !userPayload ||
    typeof userPayload !== "object" ||
    !("id" in userPayload) ||
    typeof userPayload.id !== "number" ||
    !Number.isSafeInteger(userPayload.id) ||
    userPayload.id <= 0
  ) {
    return null;
  }
  return String(userPayload.id);
}

export async function handleAuthorizationRequest(
  request: Request,
  env: WorkerEnv,
  oauth: OAuthHelpers,
): Promise<Response> {
  if (request.method !== "GET") return text(405, "Method not allowed");

  let authRequest: AuthRequest;
  try {
    authRequest = await oauth.parseAuthRequest(request);
  } catch {
    return text(400, "Invalid authorization request");
  }
  if (!isSupportedScope(authRequest.scope) || !(await oauth.lookupClient(authRequest.clientId))) {
    return text(400, "Authorization denied");
  }

  const githubState = toBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  const sealed = await sealCookie(
    { request: authRequest, githubState, expiresAt: Date.now() + LOGIN_TTL_MS },
    env.COOKIE_ENCRYPTION_KEY,
  );
  if (sealed.length > 3_800) return text(400, "Authorization request too large");

  const github = new URL("https://github.com/login/oauth/authorize");
  github.search = new URLSearchParams({
    client_id: env.GITHUB_CLIENT_ID,
    redirect_uri: `${new URL(request.url).origin}/callback`,
    state: githubState,
  }).toString();
  const response = Response.redirect(github.toString(), 302);
  response.headers.set("Set-Cookie", loginCookie(sealed, LOGIN_TTL_MS / 1_000));
  return noStore(response);
}

export async function handleGitHubCallback(
  request: Request,
  env: WorkerEnv,
  oauth: OAuthHelpers,
): Promise<Response> {
  if (request.method !== "GET") return text(405, "Method not allowed");

  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const sealed = parseCookie(request.headers.get("cookie"), LOGIN_COOKIE);
  if (!code || !state || !sealed) return text(400, "Invalid login callback");

  const login = await unsealCookie(sealed, env.COOKIE_ENCRYPTION_KEY);
  if (!login || login.expiresAt < Date.now() || !(await constantTimeEqual(state, login.githubState))) {
    return text(400, "Invalid login callback");
  }

  const userId = await githubUserId(code, url.origin, env);
  if (!userId || !/^\d+$/u.test(env.ALLOWED_GITHUB_USER_ID) || !(await constantTimeEqual(userId, env.ALLOWED_GITHUB_USER_ID))) {
    return text(403, "Access denied");
  }
  if (!isSupportedScope(login.request.scope) || !(await oauth.lookupClient(login.request.clientId))) {
    return text(403, "Access denied");
  }

  const { redirectTo } = await oauth.completeAuthorization({
    request: login.request,
    userId,
    metadata: { githubUserId: userId },
    scope: [PLAYBACK_SCOPE],
    props: { githubUserId: userId, scopes: [PLAYBACK_SCOPE] },
  });
  const response = Response.redirect(redirectTo, 302);
  response.headers.set("Set-Cookie", loginCookie("", 0));
  return noStore(response);
}

export { PLAYBACK_SCOPE };
