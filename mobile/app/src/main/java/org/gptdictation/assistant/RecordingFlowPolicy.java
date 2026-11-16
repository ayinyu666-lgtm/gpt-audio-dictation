package org.gptdictation.assistant;

final class RecordingFlowPolicy {
    // This is checked before performing an action; a failed action must never be retried blindly.
    static boolean actionReady(boolean enabled, boolean visible, boolean hasBounds) {
        return enabled && visible && hasBounds;
    }

    /**
     * Frames needed before any activity level can be classified, taken from the
     * detector's own baseline requirement. See WaveformDetector.
     */
    static final int BASELINE_FRAMES = 8;

    /**
     * Whether a visual pause can realistically be observed inside one segment.
     *
     * The detector cannot classify anything until enough frames establish a
     * distinguishable reference, and a submit additionally needs the user's requested
     * pause to elapse afterwards. Short maximum durations therefore cannot use visual
     * pauses at all; the timed fallback still applies. Telling the user this is honest;
     * silently pretending a pause was detected would not be.
     *
     * The estimate assumes roughly one usable frame per sampling interval and leaves
     * one frame of slack for a gap, which is what the service's own cadence produces.
     */
    static boolean visualPauseFeasible(long maxDurationMs, long sampleIntervalMs, long requiredPauseMs) {
        if (maxDurationMs <= 0 || sampleIntervalMs <= 0 || requiredPauseMs < 0) return false;
        long framesToLearn = (BASELINE_FRAMES + 1) * sampleIntervalMs;
        long minimum = framesToLearn + requiredPauseMs;
        return maxDurationMs >= minimum;
    }

    /**
     * Minimum maximum-duration worth attempting pause detection on. Below this the
     * service says plainly that only the timed fallback applies, so the user is not
     * left waiting for a "preparing" state that can never finish.
     */
    static long minimumFeasibleDurationMs(long sampleIntervalMs, long requiredPauseMs) {
        if (sampleIntervalMs <= 0 || requiredPauseMs < 0) return Long.MAX_VALUE;
        return (BASELINE_FRAMES + 1) * sampleIntervalMs + requiredPauseMs;
    }
}
