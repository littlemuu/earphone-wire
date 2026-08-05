package com.andxiaoqie.earphonewire;

public final class RetryPolicy {
    private RetryPolicy() {
    }

    public static long delayMillis(int retryAttempt) {
        if (retryAttempt < 1 || retryAttempt > 5) return -1L;
        return Math.min(60_000L, 2_000L << (retryAttempt - 1));
    }
}
