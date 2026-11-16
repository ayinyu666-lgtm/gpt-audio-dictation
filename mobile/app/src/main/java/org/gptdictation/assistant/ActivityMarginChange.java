package org.gptdictation.assistant;

/**
 * Decides whether a change in the noise-derived activity margin is large enough to
 * require a fresh comparison window.
 *
 * Raised margins are dangerous mid-sentence: speech that was clearly "active" can
 * suddenly fall below the new threshold and look like a pause. Acting on that would
 * send a segment while the teacher is still talking. Small jitter is ignored so the
 * detector is not reset every frame.
 */
final class ActivityMarginChange {
    /** Margin changes below this are noise and must not reset the detector. */
    static final double DEADBAND = 0.012;

    private ActivityMarginChange() { }

    /** True when the applied margin must be replaced and the comparison rebuilt. */
    static boolean requiresRebaseline(double applied, double target) {
        if (Double.isNaN(applied) || Double.isNaN(target)) return false;
        return Math.abs(target - applied) >= DEADBAND;
    }

    /** True when the change is upward, which is the direction that can fake a pause. */
    static boolean isTightening(double applied, double target) {
        return target > applied;
    }
}
