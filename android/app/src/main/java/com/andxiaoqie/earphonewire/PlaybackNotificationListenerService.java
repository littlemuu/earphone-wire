package com.andxiaoqie.earphonewire;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;

import java.util.List;

/** Reads only QQ Music's active MediaSession, never notification bodies. */
public final class PlaybackNotificationListenerService extends NotificationListenerService {
    private static final String QQ_MUSIC_PACKAGE = NowPlayingPayload.QQ_MUSIC_PACKAGE;
    private final ConnectionGeneration generations = new ConnectionGeneration();
    private ConnectionRuntime currentRuntime;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        final ConnectionRuntime oldRuntime;
        final ConnectionRuntime newRuntime;
        synchronized (this) {
            oldRuntime = currentRuntime;
            newRuntime = new ConnectionRuntime(generations.open());
            currentRuntime = newRuntime;
        }
        if (oldRuntime != null) oldRuntime.shutdown(false);
        newRuntime.start();
    }

    @Override
    public void onListenerDisconnected() {
        ConnectionRuntime runtime;
        synchronized (this) {
            runtime = currentRuntime;
        }
        if (runtime != null) runtime.shutdown(true);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        ConnectionRuntime runtime;
        synchronized (this) {
            runtime = currentRuntime;
        }
        if (runtime != null) runtime.shutdown(false);
        super.onDestroy();
    }

    private synchronized boolean isCurrent(ConnectionRuntime runtime) {
        return currentRuntime == runtime && generations.isCurrent(runtime.generation);
    }

    private void finishRuntime(ConnectionRuntime runtime, boolean requestRebind) {
        final boolean shouldRequestRebind;
        synchronized (this) {
            shouldRequestRebind = currentRuntime == runtime
                    && generations.completeCleanup(runtime.generation);
            if (shouldRequestRebind) currentRuntime = null;
        }
        runtime.thread.quitSafely();
        if (requestRebind && shouldRequestRebind) requestNotificationRebind();
    }

    private void requestNotificationRebind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, PlaybackNotificationListenerService.class));
        } catch (RuntimeException ignored) {
            // The user or system may have revoked notification access.
        }
    }

    /** One connection owns every mutable media-session object it uses. */
    private final class ConnectionRuntime {
        final long generation;
        final HandlerThread thread;
        final Handler handler;
        final SessionThreadDispatcher dispatcher;
        final NowPlayingReporter reporter;
        final SessionLifecycleCoordinator<MediaController> lifecycle;
        final Runnable heartbeat;
        final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged;
        final PairingEvents.Listener pairingListener;
        final MediaController.Callback mediaCallback;
        MediaSessionManager manager;
        private boolean stopping;

        ConnectionRuntime(long generation) {
            this.generation = generation;
            thread = new HandlerThread("earphone-wire-media-session-" + generation);
            thread.start();
            handler = new Handler(thread.getLooper());
            dispatcher = new SessionThreadDispatcher(handler::post);
            reporter = new NowPlayingReporter(PlaybackNotificationListenerService.this);
            mediaCallback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { dispatch(lifecycle::heartbeat); }
                @Override public void onPlaybackStateChanged(android.media.session.PlaybackState state) { dispatch(lifecycle::heartbeat); }
                @Override public void onSessionDestroyed() { dispatch(ConnectionRuntime.this::refreshSession); }
            };
            lifecycle = new SessionLifecycleCoordinator<>(new SessionLifecycleCoordinator.Hooks<MediaController>() {
                @Override public boolean sameSession(MediaController left, MediaController right) {
                    MediaSession.Token leftToken = left.getSessionToken();
                    return leftToken != null && leftToken.equals(right.getSessionToken());
                }

                @Override public void attach(MediaController controller) {
                    controller.registerCallback(mediaCallback, handler);
                }

                @Override public void detach(MediaController controller) {
                    controller.unregisterCallback(mediaCallback);
                }

                @Override public void observe(MediaController controller) {
                    reportCurrent(controller);
                }
            });
            heartbeat = new Runnable() {
                @Override public void run() {
                    if (!isRunning()) return;
                    dispatch(ConnectionRuntime.this::refreshSession);
                    handler.postDelayed(this, HeartbeatPolicy.INTERVAL_MILLIS);
                }
            };
            sessionsChanged = controllers -> dispatch(ConnectionRuntime.this::refreshSession);
            pairingListener = () -> dispatch(lifecycle::pairingChanged);
        }

        void start() {
            PairingEvents.register(pairingListener);
            dispatch(() -> {
                if (!isRunning()) return;
                manager = getSystemService(MediaSessionManager.class);
                if (manager != null) {
                    manager.addOnActiveSessionsChangedListener(
                            sessionsChanged,
                            new ComponentName(PlaybackNotificationListenerService.this,
                                    PlaybackNotificationListenerService.class),
                            handler);
                }
                refreshSession();
                handler.postDelayed(heartbeat, HeartbeatPolicy.INTERVAL_MILLIS);
            });
        }

        void shutdown(boolean requestRebind) {
            synchronized (this) {
                if (stopping) return;
                stopping = true;
            }
            PairingEvents.unregister(pairingListener);
            handler.post(() -> {
                handler.removeCallbacks(heartbeat);
                if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
                lifecycle.replace(null);
                reporter.shutdown();
                manager = null;
                finishRuntime(this, requestRebind);
            });
        }

        private boolean isRunning() {
            synchronized (this) {
                return !stopping && isCurrent(this);
            }
        }

        private void dispatch(Runnable action) {
            if (!isRunning()) return;
            dispatcher.dispatch(action);
        }

        private void refreshSession() {
            if (Thread.currentThread() != handler.getLooper().getThread() || !isRunning()) return;
            try {
                if (manager == null) return;
                List<MediaController> controllers = manager.getActiveSessions(
                        new ComponentName(PlaybackNotificationListenerService.this,
                                PlaybackNotificationListenerService.class));
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

        private void reportCurrent(MediaController controller) {
            if (Thread.currentThread() != handler.getLooper().getThread() || !isRunning()) return;
            MediaMetadata metadata = controller.getMetadata();
            QqMusicMetadataMapper.Result normalized = QqMusicMetadataMapper.map(metadata);
            if (normalized.title == null) return;
            reporter.observe(normalized.title, normalized.artist == null ? "" : normalized.artist,
                    PlaybackStateMapper.toUploadState(controller.getPlaybackState()));
        }
    }
}
