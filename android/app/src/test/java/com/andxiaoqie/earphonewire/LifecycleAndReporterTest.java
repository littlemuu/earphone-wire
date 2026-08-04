package com.andxiaoqie.earphonewire;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class LifecycleAndReporterTest {
    @Test public void sameSessionTokenDoesNotReattachCallbackAndHeartbeatObservesOnPath() {
        RecordingHooks hooks = new RecordingHooks();
        SessionLifecycleCoordinator<String> coordinator = new SessionLifecycleCoordinator<>(hooks);
        coordinator.replace("session-token-a");
        coordinator.replace("session-token-a");
        coordinator.heartbeat();
        assertEquals(1, hooks.attachCount);
        assertEquals(0, hooks.detachCount);
        assertEquals(3, hooks.observeCount);
    }

    @Test public void heartbeatWorkIsQueuedThroughTheSessionHandlerPath() {
        RecordingHooks hooks = new RecordingHooks();
        SessionLifecycleCoordinator<String> coordinator = new SessionLifecycleCoordinator<>(hooks);
        coordinator.replace("qq-session-token");
        ManualPoster poster = new ManualPoster();
        SessionThreadDispatcher dispatcher = new SessionThreadDispatcher(poster);
        dispatcher.dispatch(coordinator::heartbeat);
        assertEquals(1, hooks.observeCount);
        assertEquals(1, poster.actions.size());
        poster.runNext();
        assertEquals(2, hooks.observeCount);
    }

    @Test public void newlyAppearingSessionIsObservedImmediatelyAndDisconnectCleansUpAndRebinds() {
        RecordingHooks hooks = new RecordingHooks();
        SessionLifecycleCoordinator<String> coordinator = new SessionLifecycleCoordinator<>(hooks);
        coordinator.replace(null);
        coordinator.replace("qq-session-token");
        assertEquals(1, hooks.attachCount);
        assertEquals(1, hooks.observeCount);
        coordinator.disconnected();
        assertEquals(1, hooks.detachCount);
        assertEquals(1, hooks.rebindCount);
        assertEquals(null, coordinator.current());
    }

    @Test public void retryRetainsEventIdAndNewObservationGetsANewOne() {
        FakePairing pairing = new FakePairing();
        ManualScheduler scheduler = new ManualScheduler();
        RecordingSender sender = new RecordingSender(UploadOutcome.RETRYABLE, UploadOutcome.SUCCESS, UploadOutcome.SUCCESS);
        NowPlayingReporter reporter = new NowPlayingReporter(pairing, sender, scheduler);
        reporter.observe("song one", "artist", "playing");
        scheduler.runNext();
        scheduler.runNext();
        assertEquals(2, sender.events.size());
        assertEquals(sender.events.get(0), sender.events.get(1));
        reporter.observe("song two", "artist", "paused");
        scheduler.runNext();
        assertNotEquals(sender.events.get(1), sender.events.get(2));
    }

    @Test public void unauthorizedBlocksFurtherUploadsUntilPairingIsSavedAgain() {
        FakePairing pairing = new FakePairing();
        ManualScheduler scheduler = new ManualScheduler();
        RecordingSender sender = new RecordingSender(UploadOutcome.REPAIR_REQUIRED, UploadOutcome.SUCCESS);
        NowPlayingReporter reporter = new NowPlayingReporter(pairing, sender, scheduler);
        reporter.observe("song one", "artist", "playing");
        scheduler.runNext();
        assertTrue(pairing.blocked);
        assertEquals(1, sender.events.size());
        reporter.observe("song two", "artist", "paused");
        scheduler.runNext();
        assertEquals(1, sender.events.size());
        pairing.blocked = false;
        reporter.observe("song three", "artist", "playing");
        scheduler.runNext();
        assertEquals(2, sender.events.size());
    }

    private static final class RecordingHooks implements SessionLifecycleCoordinator.Hooks<String> {
        int attachCount; int detachCount; int observeCount; int rebindCount;
        @Override public boolean sameSession(String left, String right) { return left.equals(right); }
        @Override public void attach(String session) { attachCount++; }
        @Override public void detach(String session) { detachCount++; }
        @Override public void observe(String session) { observeCount++; }
        @Override public void requestRebind() { rebindCount++; }
    }

    private static final class FakePairing implements NowPlayingReporter.PairingAccess {
        boolean blocked;
        final PairingStore.Pairing pairing = new PairingStore.Pairing("https://relay.test", "");
        @Override public PairingStore.Pairing loadForUpload() { return blocked ? null : pairing; }
        @Override public void requireRePairing() { blocked = true; }
        @Override public void recordUploadResult(String status) { }
    }

    private static final class RecordingSender implements NowPlayingReporter.Sender {
        final ArrayDeque<UploadOutcome> outcomes = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        RecordingSender(UploadOutcome... values) { for (UploadOutcome value : values) outcomes.add(value); }
        @Override public UploadOutcome upload(PairingStore.Pairing pairing, NowPlayingPayload payload) {
            events.add(payload.eventId);
            return outcomes.remove();
        }
    }

    private static final class ManualScheduler implements NowPlayingReporter.Scheduler {
        final ArrayDeque<Task> tasks = new ArrayDeque<>();
        @Override public NowPlayingReporter.ScheduledTask schedule(Runnable runnable, long delayMillis) {
            Task task = new Task(runnable); tasks.add(task); return () -> task.cancelled = true;
        }
        @Override public void shutdown() { tasks.clear(); }
        void runNext() {
            while (!tasks.isEmpty()) {
                Task task = tasks.remove();
                if (!task.cancelled) { task.runnable.run(); return; }
            }
            throw new AssertionError("No scheduled task");
        }
        private static final class Task {
            final Runnable runnable; boolean cancelled;
            Task(Runnable runnable) { this.runnable = runnable; }
        }
    }

    private static final class ManualPoster implements SessionThreadDispatcher.Poster {
        final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        @Override public void post(Runnable action) { actions.add(action); }
        void runNext() { actions.remove().run(); }
    }
}
