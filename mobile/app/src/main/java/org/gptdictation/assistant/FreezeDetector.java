package org.gptdictation.assistant;

/**
 * Detects that our process was frozen, suspended or otherwise did not get scheduled
 * for much longer than one loop period.
 *
 * Vendor ROMs (Honor/Huawei/Xiaomi and others) freeze background processes even when
 * an accessibility service is running. While frozen, our Handler gets no CPU, so on
 * resume every stale timestamp, screenshot request and deadline is meaningless. Worse,
 * a screenshot callback that was in flight before the freeze can return afterwards with
 * an old capture time and look like a valid pause.
 *
 * The rule here is deliberately blunt: if the gap between two ticks is far larger than
 * the scheduled period, discard all visual state and rebuild from the live UI instead of
 * trying to reason about what happened while we were asleep.
 *
 * Pure logic so it can be tested without Android.
 */
final class FreezeDetector {
    /** Normal tick period is 250 ms; anything beyond 6x that means we were not scheduled. */
    static final long GAP_FACTOR = 6;
    /** Never treat less than this as a freeze, even for slow devices. */
    static final long MIN_GAP_MS = 2500;

    private long lastTickAt = -1;

    void reset() { lastTickAt = -1; }

    /**
     * @param now monotonic milliseconds (SystemClock.elapsedRealtime)
     * @return true when the caller should treat this tick as a fresh start
     */
    boolean observe(long now) {
        if (now < 0) throw new IllegalArgumentException("now");
        long previous = lastTickAt;
        lastTickAt = now;
        if (previous < 0) return true;          // first tick is always a fresh start
        long gap = now - previous;
        if (gap < 0) return true;               // clock went backwards: treat as fresh
        long limit = Math.max(MIN_GAP_MS, 250L * GAP_FACTOR);
        return gap > limit;
    }

    /** Milliseconds since the previous tick, or -1 when there was none. */
    long lastGapMs(long now) {
        return lastTickAt < 0 ? -1 : now - lastTickAt;
    }
}
