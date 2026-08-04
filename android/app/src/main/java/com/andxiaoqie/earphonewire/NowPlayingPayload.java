package com.andxiaoqie.earphonewire;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** The only fields accepted by POST /api/v1/now-playing. */
public final class NowPlayingPayload {
    public static final String QQ_MUSIC_PACKAGE = "com.tencent.qqmusic";
    private static final int MAX_BODY_BYTES = 4 * 1024;

    public final String eventId;
    public final String title;
    public final String artist;
    public final String playbackState;
    public final String playerPackage;
    public final String observedAt;

    private NowPlayingPayload(String eventId, String title, String artist, String playbackState,
                               String playerPackage, String observedAt) {
        this.eventId = eventId;
        this.title = title;
        this.artist = artist;
        this.playbackState = playbackState;
        this.playerPackage = playerPackage;
        this.observedAt = observedAt;
    }

    public static NowPlayingPayload create(String title, String artist, String playbackState) {
        return create(UUID.randomUUID().toString(), title, artist, playbackState, isoNow());
    }

    public static NowPlayingPayload create(String eventId, String title, String artist,
                                           String playbackState, String observedAt) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanArtist = artist == null ? "" : artist.trim();
        if (!isUuid(eventId) || cleanTitle.isEmpty() || cleanTitle.length() > 300
                || cleanArtist.length() > 300 || !isPlaybackState(playbackState)
                || !isOffsetIsoTimestamp(observedAt)) {
            throw new IllegalArgumentException("Invalid now-playing payload.");
        }
        NowPlayingPayload payload = new NowPlayingPayload(
                eventId, cleanTitle, cleanArtist, playbackState, QQ_MUSIC_PACKAGE, observedAt
        );
        if (payload.toJson().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Now-playing payload exceeds 4 KiB.");
        }
        return payload;
    }

    public String toJson() {
        return "{\"eventId\":\"" + escape(eventId) + "\",\"title\":\"" + escape(title)
                + "\",\"artist\":\"" + escape(artist) + "\",\"playbackState\":\""
                + escape(playbackState) + "\",\"playerPackage\":\"" + QQ_MUSIC_PACKAGE
                + "\",\"observedAt\":\"" + escape(observedAt) + "\"}";
    }

    public static boolean isPlaybackState(String value) {
        return "playing".equals(value) || "paused".equals(value) || "stopped".equals(value)
                || "buffering".equals(value) || "connecting".equals(value) || "error".equals(value)
                || "unknown".equals(value);
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private static boolean isOffsetIsoTimestamp(String value) {
        return value != null && value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$");
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String escape(String value) {
        StringBuilder output = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': output.append("\\\\"); break;
                case '"': output.append("\\\""); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (c < 0x20) output.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else output.append(c);
            }
        }
        return output.toString();
    }
}
