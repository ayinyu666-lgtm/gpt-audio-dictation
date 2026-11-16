package org.gptdictation.assistant;

/** Tracks the physical screenshot slot, including an abandoned system request. */
final class CaptureLease {
    private long serial, active;
    private boolean abandoned;
    long begin() {
        if (active != 0) return 0;
        active = ++serial; abandoned = false; return active;
    }
    void abandon() { if (active != 0) abandoned = true; }
    boolean finish(long token) {
        if (token == 0 || token != active) return false;
        boolean usable = !abandoned; active = 0; abandoned = false; return usable;
    }
    boolean busy() { return active != 0; }
}
