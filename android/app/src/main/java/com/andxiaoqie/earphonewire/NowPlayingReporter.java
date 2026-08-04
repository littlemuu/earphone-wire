package com.andxiaoqie.earphonewire;

import android.content.Context;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one in-memory pending snapshot. New observations replace it; retries
 * retain its event ID so server-side idempotency remains effective.
 */
public final class NowPlayingReporter {
    private static final long DEBOUNCE_MILLIS = 750L;
    private final PairingStore pairingStore;
    private final NowPlayingUploader uploader;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Object lock = new Object();
    private NowPlayingPayload pending;
    private int retryAttempt;
    private ScheduledFuture<?> scheduledSend;

    public NowPlayingReporter(Context context) {
        this(new PairingStore(context), new NowPlayingUploader());
    }

    NowPlayingReporter(PairingStore store, NowPlayingUploader uploader) {
        this.pairingStore = store;
        this.uploader = uploader;
    }

    public void observe(String title, String artist, String playbackState) {
        if (title == null || title.trim().isEmpty()) return;
        final NowPlayingPayload next;
        try {
            next = NowPlayingPayload.create(title, artist, playbackState);
        } catch (IllegalArgumentException error) {
            return;
        }
        synchronized (lock) {
            pending = next;
            retryAttempt = 0;
            scheduleLocked(DEBOUNCE_MILLIS);
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (scheduledSend != null) scheduledSend.cancel(false);
            scheduledSend = null;
            pending = null;
        }
        executor.shutdownNow();
    }

    private void scheduleLocked(long delayMillis) {
        if (scheduledSend != null) scheduledSend.cancel(false);
        scheduledSend = executor.schedule(this::sendPending, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void sendPending() {
        final NowPlayingPayload sending;
        synchronized (lock) {
            sending = pending;
            scheduledSend = null;
        }
        if (sending == null) return;
        PairingStore.Pairing pairing = pairingStore.load();
        if (pairing == null) {
            pairingStore.recordUploadResult("Pairing required");
            return;
        }
        UploadOutcome outcome = uploader.upload(pairing, sending);
        synchronized (lock) {
            if (pending != sending) return; // A newer observation already replaced this one.
            switch (outcome) {
                case SUCCESS:
                    pending = null;
                    retryAttempt = 0;
                    pairingStore.recordUploadResult("Uploaded");
                    return;
                case REPAIR_REQUIRED:
                    pending = null;
                    pairingStore.recordUploadResult("Upload token rejected — pair again");
                    return;
                case SUPERSEDED:
                    pending = null;
                    pairingStore.recordUploadResult("Superseded by a newer report");
                    return;
                case RETRYABLE:
                    retryAttempt++;
                    long delay = RetryPolicy.delayMillis(retryAttempt);
                    if (delay > 0) {
                        pairingStore.recordUploadResult("Temporary network error — retrying");
                        scheduleLocked(delay);
                    } else {
                        pending = null;
                        pairingStore.recordUploadResult("Temporary upload failure");
                    }
                    return;
                default:
                    pending = null;
                    pairingStore.recordUploadResult("Upload rejected");
            }
        }
    }
}
