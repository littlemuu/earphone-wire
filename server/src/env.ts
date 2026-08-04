import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";

import type {
  NowPlayingSnapshot,
} from "./now-playing";
import type { StoreSnapshotResult } from "./now-playing-do";

export interface NowPlayingStore {
  store(snapshot: NowPlayingSnapshot): Promise<StoreSnapshotResult>;
  latest(now?: number): Promise<NowPlayingSnapshot | null>;
}

export interface NowPlayingNamespace {
  getByName(name: string): NowPlayingStore;
}

export interface WorkerEnv {
  NOW_PLAYING: NowPlayingNamespace;
  OAUTH_KV: KVNamespace;
  ANDROID_UPLOAD_TOKEN: string;
  ALLOWED_GITHUB_USER_ID: string;
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
  COOKIE_ENCRYPTION_KEY: string;
  OAUTH_PROVIDER?: OAuthHelpers;
}
