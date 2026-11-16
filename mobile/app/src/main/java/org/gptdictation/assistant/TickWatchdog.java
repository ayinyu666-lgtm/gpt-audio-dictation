package org.gptdictation.assistant;

/**
 * Pure watchdog state for the service's main loop.
 *
 * The service runs its whole tick cycle from a single Runnable that re-posts itself at the end
 * of every tick. Several other places re-arm the same Runnable when an accessibility event or a
 * screenshot callback arrives. That design has no invariant guaranteeing that a tick is always
 * pending: an ordering that ends with a removeCallbacks() and no matching post leaves the run
 * silently dead while the service still reports "connected".
 *
 * The observed failure: CPU stopped advancing, diag.txt stopped at a fixed t=, the process was
 * alive and its main thread Sleeping, the accessibility service still Bound, and ChatGPT stayed
 * in the foreground for the rest of the lecture.
 *
 * This class holds only the decision logic, so it can be tested without Android:
 *   - {@link #onTick(long)} records progress;
 *   - {@link #stalled(long)} answers "has no tick completed for longer than STALL_MS?".
 *
 * The service's watchdog Runnable calls stalled() and, when true, only RE-ARMS the canonical
 * tick. It never calls tick() itself, so a slow tick can never run concurrently with a second one.
 */
final class TickWatchdog {
    /**
     * A healthy loop ticks every 400-500 ms, so 3000 ms is more than six missed ticks: it cannot
     * fire during normal operation, while a real stall is caught within three seconds instead of
     * costing the rest of a lecture.
     */
    static final long STALL_MS = 3000;

    /** Minimum spacing between watchdog log lines, so a persistent failure cannot flood diag. */
    static final long LOG_MIN_INTERVAL_MS = 10_000;

    private long lastTickAt;
    private long lastLogAt;
    private int recoveries;

    void reset(long now) {
        lastTickAt = now;
        lastLogAt = 0;
        recoveries = 0;
    }

    /** Called at the end of every completed tick. */
    void onTick(long now) {
        lastTickAt = now;
    }

    /** True when no tick has completed for longer than STALL_MS. */
    boolean stalled(long now) {
        return lastTickAt > 0 && now - lastTickAt > STALL_MS;
    }

    /** True when a recovery should be written to the log now (rate limited). */
    boolean shouldLog(long now) {
        return lastLogAt == 0 || now - lastLogAt >= LOG_MIN_INTERVAL_MS;
    }

    void onRecovered(long now) {
        recoveries++;
        if (shouldLog(now)) lastLogAt = now;
    }

    /** How many times the watchdog had to re-arm the loop. Exposed in the periodic diag line. */
    int recoveries() { return recoveries; }

    /** Milliseconds since the last completed tick, for diagnostics. */
    long sinceLastTick(long now) { return lastTickAt <= 0 ? -1 : now - lastTickAt; }
}
