package com.andxiaoqie.earphonewire;

/** Guards a current service runtime from cleanup work queued by an older connection. */
final class ConnectionGeneration {
    private long lastIssued;
    private long current;

    long open() {
        current = ++lastIssued;
        return current;
    }

    boolean isCurrent(long generation) {
        return current == generation;
    }

    /** Returns true only when this retiring runtime is still the current one. */
    boolean completeCleanup(long generation) {
        if (!isCurrent(generation)) return false;
        current = 0L;
        return true;
    }
}
