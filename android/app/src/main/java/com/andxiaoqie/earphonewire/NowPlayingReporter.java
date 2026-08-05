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
        boolean requireRePairing(PairingStore.Pairing expected);
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
            pairing.recordUploadResult("需要重新配对");
            return;
        }
        UploadOutcome outcome = sender.upload(activePairing, sending);
        if (outcome == UploadOutcome.REPAIR_REQUIRED) {
            synchronized (this) {
                if (pending == sending) {
                    pending = null;
                    retryAttempt = 0;
                    if (scheduled != null) scheduled.cancel();
                    scheduled = null;
                }
            }
            if (pairing.requireRePairing(activePairing)) {
                pairing.recordUploadResult("上传令牌被拒绝，请重新配对");
            }
            return;
        }
        synchronized (this) {
            if (pending != sending) return;
            if (outcome == UploadOutcome.SUCCESS) {
                pending = null;
                retryAttempt = 0;
                pairing.recordUploadResult("上传成功");
                return;
            }
            if (outcome == UploadOutcome.SUPERSEDED) {
                pending = null;
                pairing.recordUploadResult("已被较新的状态替代");
                return;
            }
            if (outcome == UploadOutcome.RETRYABLE) {
                long delay = RetryPolicy.delayMillis(++retryAttempt);
                if (delay > 0) {
                    pairing.recordUploadResult("网络暂时异常，正在重试");
                    schedule(delay);
                } else {
                    pending = null;
                    pairing.recordUploadResult("上传暂时失败");
                }
                return;
            }
            pending = null;
            pairing.recordUploadResult("上传被拒绝");
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
