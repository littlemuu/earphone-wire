import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import type { NowPlayingDurableObject } from "./now-playing-do";

export interface WorkerEnv {
  NOW_PLAYING: DurableObjectNamespace<NowPlayingDurableObject>;
  OAUTH_KV: KVNamespace;
  ANDROID_UPLOAD_TOKEN: string;
  ALLOWED_GITHUB_USER_ID: string;
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
  COOKIE_ENCRYPTION_KEY: string;
  OAUTH_PROVIDER?: OAuthHelpers;
}
