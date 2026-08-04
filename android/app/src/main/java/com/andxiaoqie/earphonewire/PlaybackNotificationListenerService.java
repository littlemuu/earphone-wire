package com.andxiaoqie.earphonewire;

import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Reads only QQ Music's active MediaSession, never notification bodies. */
public final class PlaybackNotificationListenerService extends NotificationListenerService {
    private static final String QQ_MUSIC_PACKAGE = NowPlayingPayload.QQ_MUSIC_PACKAGE;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { reportCurrent(); }
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState state) { reportCurrent(); }
        @Override public void onSessionDestroyed() { refreshController(); }
    };
    private MediaController qqMusic;
    private ScheduledFuture<?> heartbeat;
    private NowPlayingReporter reporter;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        reporter = new NowPlayingReporter(this);
        startHeartbeat();
        refreshController();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshController();
        return START_NOT_STICKY;
    }

    @Override
    public void onListenerDisconnected() {
        stopObserving();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        stopObserving();
        heartbeatExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                this::refreshController, 0L, HeartbeatPolicy.INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void refreshController() {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            if (manager == null) return;
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(this, PlaybackNotificationListenerService.class));
            MediaController found = null;
            for (MediaController controller : controllers) {
                if (QQ_MUSIC_PACKAGE.equals(controller.getPackageName())) {
                    found = controller;
                    break;
                }
            }
            if (found == qqMusic) {
                reportCurrent(); // Required 60 second heartbeat while the session exists.
                return;
            }
            if (qqMusic != null) qqMusic.unregisterCallback(mediaCallback);
            qqMusic = found;
            if (qqMusic != null) {
                qqMusic.registerCallback(mediaCallback);
                reportCurrent();
            }
        } catch (RuntimeException ignored) {
            // Access can be revoked by the system. Do not log session data.
        }
    }

    private void reportCurrent() {
        MediaController controller = qqMusic;
        NowPlayingReporter activeReporter = reporter;
        if (controller == null || activeReporter == null) return;
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return;
        MediaDescription description = metadata.getDescription();
        CharSequence title = firstNonEmpty(
                metadata.getText(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                description == null ? null : description.getTitle());
        if (TextUtils.isEmpty(title)) return; // Never fabricate a title.
        CharSequence artist = firstNonEmpty(
                metadata.getText(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                description == null ? null : description.getSubtitle());
        activeReporter.observe(title.toString(), artist == null ? "" : artist.toString(),
                PlaybackStateMapper.toUploadState(controller.getPlaybackState()));
    }

    private CharSequence firstNonEmpty(CharSequence... values) {
        for (CharSequence value : values) if (!TextUtils.isEmpty(value)) return value;
        return null;
    }

    private void stopObserving() {
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = null;
        if (qqMusic != null) qqMusic.unregisterCallback(mediaCallback);
        qqMusic = null;
        if (reporter != null) reporter.shutdown();
        reporter = null;
    }
}
