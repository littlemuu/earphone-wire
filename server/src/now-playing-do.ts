import {
  SNAPSHOT_TTL_MS,
  type NowPlayingSnapshot,
} from "./now-playing";
import type { WorkerEnv } from "./env";

type SnapshotRow = {
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

export type StoreSnapshotResult = "stored" | "idempotent" | "older";

/** Stores the only permitted playback record: the current single-user snapshot. */
export class NowPlayingDurableObject {
  private readonly ctx: DurableObjectState;

  constructor(ctx: DurableObjectState, env: WorkerEnv) {
    this.ctx = ctx;
    ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS now_playing_snapshot (
          singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
          event_id TEXT NOT NULL,
          title TEXT NOT NULL,
          artist TEXT NOT NULL,
          playback_state TEXT NOT NULL,
          player_package TEXT NOT NULL,
          observed_at TEXT NOT NULL,
          observed_at_ms INTEGER NOT NULL,
          received_at TEXT NOT NULL,
          received_at_ms INTEGER NOT NULL
        )
      `);
    });
  }

  async store(snapshot: NowPlayingSnapshot): Promise<StoreSnapshotResult> {
    const current = this.currentRow();
    const observedAtMs = Date.parse(snapshot.observedAt);

    if (current?.event_id === snapshot.eventId) {
      return "idempotent";
    }
    if (current && observedAtMs < current.observed_at_ms) {
      return "older";
    }

    // A single SQL statement replaces the one snapshot atomically. There are no
    // awaits before this commit, and a Durable Object serializes calls per ID.
    this.ctx.storage.sql.exec(
      `INSERT INTO now_playing_snapshot (
        singleton, event_id, title, artist, playback_state, player_package,
        observed_at, observed_at_ms, received_at, received_at_ms
      ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(singleton) DO UPDATE SET
        event_id = excluded.event_id,
        title = excluded.title,
        artist = excluded.artist,
        playback_state = excluded.playback_state,
        player_package = excluded.player_package,
        observed_at = excluded.observed_at,
        observed_at_ms = excluded.observed_at_ms,
        received_at = excluded.received_at,
        received_at_ms = excluded.received_at_ms`,
      snapshot.eventId,
      snapshot.title,
      snapshot.artist,
      snapshot.playbackState,
      snapshot.playerPackage,
      snapshot.observedAt,
      observedAtMs,
      snapshot.receivedAt,
      Date.parse(snapshot.receivedAt),
    );

    await this.ctx.storage.setAlarm(Date.parse(snapshot.receivedAt) + SNAPSHOT_TTL_MS);
    return "stored";
  }

  async latest(now = Date.now()): Promise<NowPlayingSnapshot | null> {
    const row = this.currentRow();
    if (!row) return null;

    if (row.received_at_ms + SNAPSHOT_TTL_MS <= now) {
      this.ctx.storage.sql.exec("DELETE FROM now_playing_snapshot WHERE singleton = 1");
      await this.ctx.storage.deleteAlarm();
      return null;
    }

    return this.toSnapshot(row);
  }

  async alarm(): Promise<void> {
    const row = this.currentRow();
    if (!row) return;

    const expiresAt = row.received_at_ms + SNAPSHOT_TTL_MS;
    if (expiresAt <= Date.now()) {
      this.ctx.storage.sql.exec("DELETE FROM now_playing_snapshot WHERE singleton = 1");
      return;
    }

    await this.ctx.storage.setAlarm(expiresAt);
  }

  private currentRow(): SnapshotRow | undefined {
    return this.ctx.storage.sql
      .exec<SnapshotRow>("SELECT * FROM now_playing_snapshot WHERE singleton = 1")
      .toArray()[0];
  }

  private toSnapshot(row: SnapshotRow): NowPlayingSnapshot {
    return {
      eventId: row.event_id,
      title: row.title,
      artist: row.artist,
      playbackState: row.playback_state,
      playerPackage: row.player_package,
      observedAt: row.observed_at,
      receivedAt: row.received_at,
      source: "phone",
    };
  }
}
