package org.gptdictation.assistant;

/**
 * Maps the user's manual "noise ignore" level to extra margins on the visual
 * activity detector.
 *
 * The waveform drawn by ChatGPT is a normalised comparison, not a sound-pressure
 * meter: quiet keyboard taps can look tall when everything else is quiet. A higher
 * level therefore asks for a larger separation above the learned background before
 * a frame counts as activity, and for a longer qualifying activity run. It never
 * touches the maximum-duration fallback.
 *
 * Levels run -9..+9 with a step of 1. The step size is unchanged from the earlier
 * -3..+3 version; the usable range is three times wider, so a room can be tuned
 * much more gradually without losing precision.
 */
public final class NoiseIgnorePolicy {
    public static final int MIN = -9, MAX = 9;
    /** Per-step change in required separation; unchanged from the earlier version. */
    public static final double MARGIN_PER_STEP = 0.012;
    /** Per-step change in required continuous activity; unchanged. */
    public static final long ACTIVE_STEP_MS = 250;

    private NoiseIgnorePolicy() { }

    public static int clamp(int level) {
        return Math.max(MIN, Math.min(MAX, level));
    }

    /**
     * Extra margin added to the activity threshold, in the same dimensionless
     * fraction-of-crop-height unit the detector uses.
     *
     * One step is always {@link #MARGIN_PER_STEP}, but the effect compresses above
     * level 3: activity is measured as a fraction of crop height, so a linear
     * 9-step margin would eventually exceed what a real waveform can produce and
     * the most conservative setting would ignore genuine speech. The first three
     * steps are the old behaviour; beyond that each step adds less, capping the
     * total margin so clear activity still qualifies.
     */
    public static double thresholdMargin(int level) {
        int v = clamp(level);
        int step = Math.abs(v);
        double linear = step * MARGIN_PER_STEP;
        double margin = step <= 3 ? linear : 3 * MARGIN_PER_STEP + (linear - 3 * MARGIN_PER_STEP) * 0.45;
        return v < 0 ? -margin : margin;
    }

    /**
     * Minimum continuous visual activity before a later pause may submit, in
     * milliseconds, on top of the detector's own minimum.
     */
    public static long minVisualActiveMs(int level, long baseMs) {
        if (baseMs < 0) throw new IllegalArgumentException("baseMs");
        return baseMs + clamp(level) * ACTIVE_STEP_MS;
    }

    public static String label(int level) {
        int v = clamp(level);
        if (v == 0) return "自动 0";
        return v < 0 ? "少忽略 " + v : "多忽略 +" + v;
    }
}
