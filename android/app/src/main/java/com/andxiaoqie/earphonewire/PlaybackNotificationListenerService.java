package com.andxiaoqie.earphonewire;

import android.content.ComponentName;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.List;

/** Reads only QQ Music's active MediaSession, never notification bodies. */
public final class PlaybackNotificationListenerService extends NotificationListenerService {
    private static final String QQ_MUSIC_PACKAGE = NowPlayingPayload.QQ_MUSIC_PACKAGE;
    private HandlerThread sessionThread;
    private Handler sessionHandler;
    private SessionThreadDispatcher sessionDispatcher;
    private MediaSessionManager sessionManager;
    private NowPlayingReporter reporter;
    private SessionLifecycleCoordinator<MediaController> lifecycle;
    private boolean stopping;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            postToSession(this::refresh);
            if (sessionHandler != null) sessionHandler.postDelayed(this, HeartbeatPolicy.INTERVAL_MILLIS);
        }
        private void refresh() { refreshSession(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = controllers -> {
        postToSession(PlaybackNotificationListenerService.this::refreshSession);
    };
    private final PairingEvents.Listener pairingListener = () -> {
        postToSession(() -> lifecycle.pairingChanged());
    };
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { postObserve(); }
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState state) { postObserve(); }
        @Override public void onSessionDestroyed() {
            postToSession(this::postRefresh);
        }
        private void postRefresh() { refreshSession(); }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        stopping = false;
        sessionThread = new HandlerThread("earphone-wire-media-session");
        sessionThread.start();
        sessionHandler = new Handler(sessionThread.getLooper());
        sessionDispatcher = new SessionThreadDispatcher(action -> sessionHandler.post(action));
        reporter = new NowPlayingReporter(this);
        lifecycle = new SessionLifecycleCoordinator<>(new SessionLifecycleCoordinator.Hooks<MediaController>() {
            @Override public boolean sameSession(MediaController left, MediaController right) {
                MediaSession.Token leftToken = left.getSessionToken();
                return leftToken != null && leftToken.equals(right.getSessionToken());
            }
            @Override public void attach(MediaController controller) {
                controller.registerCallback(mediaCallback, sessionHandler);
            }
            @Override public void detach(MediaController controller) {
                controller.unregisterCallback(mediaCallback);
            }
            @Override public void observe(MediaController controller) { reportCurrent(controller); }
            @Override public void requestRebind() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
                try {
                    NotificationListenerService.requestRebind(
                            new ComponentName(PlaybackNotificationListenerService.this,
                                    PlaybackNotificationListenerService.class));
                } catch (RuntimeException ignored) {
                    // The user or system may have revoked notification access.
                }
            }
        });
        PairingEvents.register(pairingListener);
        postToSession(() -> {
            sessionManager = getSystemService(MediaSessionManager.class);
            if (sessionManager != null) {
                sessionManager.addOnActiveSessionsChangedListener(
                        sessionsChanged,
                        new ComponentName(this, PlaybackNotificationListenerService.class),
                        sessionHandler);
            }
            refreshSession();
            sessionHandler.postDelayed(heartbeat, HeartbeatPolicy.INTERVAL_MILLIS);
        });
    }

    @Override
    public void onListenerDisconnected() {
        stopOnSessionThread(true);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        stopOnSessionThread(false);
        super.onDestroy();
    }

    private void refreshSession() {
        if (sessionHandler == null || Thread.currentThread() != sessionHandler.getLooper().getThread()) return;
        try {
            if (sessionManager == null) return;
            List<MediaController> controllers = sessionManager.getActiveSessions(
                    new ComponentName(this, PlaybackNotificationListenerService.class));
            MediaController found = null;
            if (controllers != null) {
                for (MediaController controller : controllers) {
                    if (QQ_MUSIC_PACKAGE.equals(controller.getPackageName())) {
                        found = controller;
                        break;
                    }
                }
            }
            lifecycle.replace(found);
        } catch (RuntimeException ignored) {
            // Do not log session, title, artist, token, or notification data.
        }
    }

    private void postObserve() {
        postToSession(() -> lifecycle.heartbeat());
    }

    private void postToSession(Runnable action) {
        SessionThreadDispatcher dispatcher = sessionDispatcher;
        if (dispatcher != null) dispatcher.dispatch(action);
    }

    private void reportCurrent(MediaController controller) {
        if (sessionHandler == null || Thread.currentThread() != sessionHandler.getLooper().getThread()) return;
        if (reporter == null) return;
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return;
        MediaDescription description = metadata.getDescription();
        CharSequence title = firstNonEmpty(
                metadata.getText(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                description == null ? null : description.getTitle());
        if (TextUtils.isEmpty(title)) return;
        CharSequence artist = firstNonEmpty(
                metadata.getText(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                description == null ? null : description.getSubtitle());
        reporter.observe(title.toString(), artist == null ? "" : artist.toString(),
                PlaybackStateMapper.toUploadState(controller.getPlaybackState()));
    }

    private CharSequence firstNonEmpty(CharSequence... values) {
        for (CharSequence value : values) if (!TextUtils.isEmpty(value)) return value;
        return null;
    }

    private synchronized void stopOnSessionThread(boolean requestRebind) {
        if (stopping) return;
        stopping = true;
        Handler handler = sessionHandler;
        if (handler == null) return;
        PairingEvents.unregister(pairingListener);
        handler.post(() -> {
            handler.removeCallbacks(heartbeat);
            if (sessionManager != null) sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged);
            if (lifecycle != null) {
                if (requestRebind) lifecycle.disconnected();
                else lifecycle.replace(null);
            }
            if (reporter != null) reporter.shutdown();
            reporter = null;
            sessionManager = null;
            sessionDispatcher = null;
            HandlerThread thread = sessionThread;
            sessionHandler = null;
            sessionThread = null;
            if (thread != null) thread.quitSafely();
        });
    }
}
