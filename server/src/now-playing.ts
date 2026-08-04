import { z } from "zod";

export const playbackStateValues = [
  "playing",
  "paused",
  "stopped",
  "buffering",
  "connecting",
  "error",
  "unknown",
] as const;

export const SNAPSHOT_TTL_MS = 24 * 60 * 60 * 1_000;
export const FRESH_SNAPSHOT_MS = 120 * 1_000;

const iso8601WithTimezone = z
  .string()
  .refine(
    (value) =>
      /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) && !Number.isNaN(Date.parse(value)),
    "observedAt must be an ISO 8601 timestamp with a timezone",
  )
  .transform((value) => new Date(value).toISOString());

export const nowPlayingUploadSchema = z
  .object({
    eventId: z.uuid(),
    title: z.string().trim().min(1).max(300),
    artist: z.string().trim().max(300),
    playbackState: z.enum(playbackStateValues),
    playerPackage: z.literal("com.tencent.qqmusic"),
    observedAt: iso8601WithTimezone,
  })
  .strict();

export type NowPlayingUpload = z.infer<typeof nowPlayingUploadSchema>;

export interface NowPlayingSnapshot extends NowPlayingUpload {
  source: "phone";
  receivedAt: string;
}

export const nowPlayingOutputSchema = {
  available: z.boolean(),
  title: z.string().nullable(),
  artist: z.string().nullable(),
  playbackState: z.enum(playbackStateValues).nullable(),
  playerPackage: z.literal("com.tencent.qqmusic").nullable(),
  observedAt: z.iso.datetime().nullable(),
  receivedAt: z.iso.datetime().nullable(),
  stale: z.boolean(),
  source: z.literal("phone"),
};

export const nowPlayingSchema = z.object(nowPlayingOutputSchema);
export type NowPlayingResult = z.infer<typeof nowPlayingSchema>;

export function emptyNowPlaying(): NowPlayingResult {
  return {
    available: false,
    title: null,
    artist: null,
    playbackState: null,
    playerPackage: null,
    observedAt: null,
    receivedAt: null,
    stale: false,
    source: "phone",
  };
}

export function toNowPlayingResult(
  snapshot: NowPlayingSnapshot | null,
  now = Date.now(),
): NowPlayingResult {
  if (!snapshot || Date.parse(snapshot.receivedAt) + SNAPSHOT_TTL_MS <= now) {
    return emptyNowPlaying();
  }

  const stale = Date.parse(snapshot.receivedAt) + FRESH_SNAPSHOT_MS < now;
  return {
    available: !stale,
    title: snapshot.title,
    artist: snapshot.artist,
    playbackState: snapshot.playbackState,
    playerPackage: snapshot.playerPackage,
    observedAt: snapshot.observedAt,
    receivedAt: snapshot.receivedAt,
    stale,
    source: "phone",
  };
}
