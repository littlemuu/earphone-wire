package com.andxiaoqie.earphonewire;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/** Debug-only, in-process diagnostics for inspecting QQ Music MediaSession metadata. */
final class MetadataDiagnostics {
    static final String TAG = "EarphoneWireMetadataDiag";
    private static final long NO_EVENT = 0L;
    private static final AtomicLong NEXT_EVENT_ID = new AtomicLong();

    private MetadataDiagnostics() {
    }

    static long logInput(String source, MediaController controller, MediaMetadata metadata) {
        if (!BuildConfig.DEBUG) return NO_EVENT;

        long eventId = NEXT_EVENT_ID.incrementAndGet();
        MediaDescription description = metadata == null ? null : metadata.getDescription();
        Log.d(TAG, "event=" + eventId
                + " stage=input"
                + " source=" + source
                + " package=" + value(controller == null ? null : controller.getPackageName())
                + " playbackState=" + PlaybackStateMapper.toUploadState(
                        controller == null ? null : controller.getPlaybackState())
                + " title=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_TITLE)
                + " displayTitle=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                + " artist=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_ARTIST)
                + " displaySubtitle=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                + " albumArtist=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                + " album=" + metadataValue(metadata, MediaMetadata.METADATA_KEY_ALBUM)
                + " descriptionTitle=" + value(description == null ? null : description.getTitle())
                + " descriptionSubtitle=" + value(description == null ? null : description.getSubtitle())
                + " descriptionDescription=" + value(
                        description == null ? null : description.getDescription()));
        return eventId;
    }

    static void logOutput(long eventId, String source, QqMusicMetadataMapper.Result result) {
        if (!BuildConfig.DEBUG || eventId == NO_EVENT) return;
        Log.d(TAG, "event=" + eventId
                + " stage=output"
                + " source=" + source
                + " title=" + value(result == null ? null : result.title)
                + " artist=" + value(result == null ? null : result.artist));
    }

    private static String metadataValue(MediaMetadata metadata, String key) {
        return value(metadata == null ? null : metadata.getText(key));
    }

    private static String value(CharSequence candidate) {
        if (candidate == null) return "<null>";
        String raw = candidate.toString();
        if (raw.isEmpty()) return "<empty>";
        String escaped = escape(raw);
        return raw.trim().isEmpty() ? "<whitespace:\"" + escaped + "\">" : "\"" + escaped + "\"";
    }

    private static String escape(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                case '"': escaped.append("\\\""); break;
                default:
                    if (Character.isISOControl(character)) {
                        String hex = Integer.toHexString(character);
                        escaped.append("\\u");
                        for (int padding = hex.length(); padding < 4; padding++) escaped.append('0');
                        escaped.append(hex);
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}
