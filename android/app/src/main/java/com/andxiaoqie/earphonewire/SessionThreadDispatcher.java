package com.andxiaoqie.earphonewire;

/** Small testable boundary that keeps MediaSession work on the service Handler's Looper. */
final class SessionThreadDispatcher {
    interface Poster { void post(Runnable action); }

    private final Poster poster;

    SessionThreadDispatcher(Poster poster) {
        this.poster = poster;
    }

    void dispatch(Runnable action) {
        poster.post(action);
    }
}
