package com.andxiaoqie.earphonewire;

import android.media.session.PlaybackState;

public final class PlaybackStateMapper {
    private PlaybackStateMapper() {
    }

    public static String toUploadState(PlaybackState state) {
        if (state == null) return "unknown";
        return toUploadState(state.getState());
    }

    static String toUploadState(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_CONNECTING: return "connecting";
            case PlaybackState.STATE_ERROR: return "error";
            default: return "unknown";
        }
    }
}
