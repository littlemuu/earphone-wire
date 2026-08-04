package com.andxiaoqie.earphonewire;

import android.content.Context;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-pending-snapshot reporter with deterministic, injectable state transitions. */
public final class NowPlayingReporter {
    private static final long DEBOUNCE_MILLIS = 750L;

    interface PairingAccess {
        PairingStore.Pairing loadForUpload();
        void requireRePairing();
        void recordUploadResult(String status);
    }

    interface Sender { UploadOutcome upload(PairingStore.Pairing pairing, NowPlayingPayload payload); }
    interface ScheduledTask { void cancel(); }
    interface Scheduler { ScheduledTask schedule(Runnable task, long delayMillis); void shutdown(); }

    private final PairingAccess pairing;
    private final Sender sender;
    private final Scheduler scheduler;
    private NowPlayingPayload pending;
    private int retryAttempt;
    private ScheduledTask scheduled;

    public NowPlayingReporter(Context context) {
        this(new PairingStore(context), new NowPlayingUploader(), new ExecutorScheduler());
    }

    NowPlayingReporter(PairingAccess pairing, Sender sender, Scheduler scheduler) {
        this.pairing = pairing;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    public synchronized void observe(String title, String artist, String playbackState) {
        if (title == null || title.trim().isEmpty()) return;
        try {
            pending = NowPlayingPayload.create(title, artist, playbackState);
            retryAttempt = 0;
            schedule(DEBOUNCE_MILLIS);
        } catch (IllegalArgumentException ignored) {
            // A malformed or title-less MediaSession observation is never uploaded.
        }
    }

    synchronized void sendNowForTest() { sendPending(); }

    public synchronized void shutdown() {
        if (scheduled != null) scheduled.cancel();
        scheduled = null;
        pending = null;
        scheduler.shutdown();
    }

    private synchronized void schedule(long delayMillis) {
        if (scheduled != null) scheduled.cancel();
        scheduled = scheduler.schedule(this::sendPending, delayMillis);
    }

    private void sendPending() {
        final NowPlayingPayload sending;
        synchronized (this) {
            sending = pending;
            scheduled = null;
        }
        if (sending == null) return;
        PairingStore.Pairing activePairing = pairing.loadForUpload();
        if (activePairing == null) {
            pairing.recordUploadResult("Pairing required");
            return;
        }
        UploadOutcome outcome = sender.upload(activePairing, sending);
        if (outcome == UploadOutcome.REPAIR_REQUIRED) {
            synchronized (this) {
                pending = null;
                retryAttempt = 0;
                if (scheduled != null) scheduled.cancel();
                scheduled = null;
            }
            pairing.requireRePairing();
            pairing.recordUploadResult("Upload token rejected - pair again");
            return;
        }
        synchronized (this) {
            if (pending != sending) return;
            switch (outcome) {
                case SUCCESS:
                    pending = null;
                    retryAttempt = 0;
                    pairing.recordUploadResult("Uploaded");
                    return;
                case REPAIR_REQUIRED:
                    pending = null;
                    pairing.requireRePairing();
                    pairing.recordUploadResult("Upload token rejected — pair again");
                    return;
                case SUPERSEDED:
                    pending = null;
                    pairing.recordUploadResult("Superseded by a newer report");
                    return;
                case RETRYABLE:
                    long delay = RetryPolicy.delayMillis(++retryAttempt);
                    if (delay > 0) {
                        pairing.recordUploadResult("Temporary network error — retrying");
                        schedule(delay);
                    } else {
                        pending = null;
                        pairing.recordUploadResult("Temporary upload failure");
                    }
                    return;
                default:
                    pending = null;
                    pairing.recordUploadResult("Upload rejected");
            }
        }
    }

    private static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        @Override public ScheduledTask schedule(Runnable task, long delayMillis) {
            ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override public void shutdown() { executor.shutdownNow(); }
    }
}
