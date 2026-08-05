package com.andxiaoqie.earphonewire;

import java.util.concurrent.CopyOnWriteArraySet;

/** Process-local pairing-change signal. No exported component or broadcast is used. */
public final class PairingEvents {
    public interface Listener { void onPairingChanged(); }
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private PairingEvents() {
    }

    public static void register(Listener listener) { LISTENERS.add(listener); }
    public static void unregister(Listener listener) { LISTENERS.remove(listener); }
    public static void notifyChanged() {
        for (Listener listener : LISTENERS) listener.onPairingChanged();
    }
}
