package com.andxiaoqie.earphonewire;

/** Pure lifecycle state machine; callers serialize all methods on one Looper. */
final class SessionLifecycleCoordinator<T> {
    interface Hooks<T> {
        boolean sameSession(T left, T right);
        void attach(T session);
        void detach(T session);
        void observe(T session);
        void requestRebind();
    }

    private final Hooks<T> hooks;
    private T current;

    SessionLifecycleCoordinator(Hooks<T> hooks) {
        this.hooks = hooks;
    }

    void replace(T next) {
        if (current != null && next != null && hooks.sameSession(current, next)) {
            hooks.observe(current);
            return;
        }
        if (current != null) hooks.detach(current);
        current = next;
        if (current != null) {
            hooks.attach(current);
            hooks.observe(current);
        }
    }

    void heartbeat() {
        if (current != null) hooks.observe(current);
    }

    void pairingChanged() {
        if (current != null) hooks.observe(current);
    }

    void disconnected() {
        if (current != null) hooks.detach(current);
        current = null;
        hooks.requestRebind();
    }

    T current() {
        return current;
    }
}
