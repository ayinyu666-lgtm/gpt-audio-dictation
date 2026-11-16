package org.gptdictation.assistant;

/**
 * Estimates how unstable the visual waveform signal is, so the detector can be
 * more conservative in a noisy room.
 *
 * This is a proxy, not an audio measurement. It counts how often the detector cannot
 * separate activity from background ("indistinguishable_activity", "baseline_shift").
 * A high share of those frames means the picture is churning without a clear rhythm,
 * which is exactly when a shallow dip must not be trusted.
 *
 * Two deliberate limits:
 *
 * 1. No verdict before {@link #MIN_SAMPLES} frames. A single ambiguous frame used to
 *    make ambiguousRatio() 1.0 and pauseFactor() 1.55 immediately, which added 1132 ms
 *    to the very first wait of every segment. With a minimum sample count the opening
 *    frames of a segment stay neutral, so a short question is judged on evidence.
 *
 * 2. This class never lengthens the pause itself. Noise is a statement about the
 *    picture, not about how long the speaker is allowed to hesitate. It feeds
 *    {@link #autoActivityMargin()} so the detector demands more separation before a
 *    frame counts as speech; the wait duration stays a property of the learned rhythm.
 *
 * The window is deliberately short so the setting follows a room that becomes noisy,
 * and decays back when the signal stabilises again.
 */
public final class NoiseAdaptation {
    /** Frames needed before this class is allowed to say anything at all. */
    public static final int MIN_SAMPLES = 4;
    private static final int WINDOW = 24;
    /** Below this share of ambiguous frames, nothing changes. */
    private static final double QUIET_RATIO = 0.15;
    /** At or above this share, the maximum conservatism applies. */
    private static final double NOISY_RATIO = 0.60;

    private final boolean[] ambiguous = new boolean[WINDOW];
    private int count, next;

    /** Record one detector outcome: true when the frame was ambiguous/unstable. */
    public void observe(boolean ambiguousSample) {
        ambiguous[next] = ambiguousSample;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    public void reset() {
        java.util.Arrays.fill(ambiguous, false);
        count = next = 0;
    }

    public int sampleCount() { return count; }

    /** Share of recent frames the detector could not classify. */
    public double ambiguousRatio() {
        if (count == 0) return 0.0;
        int bad = 0;
        for (int i = 0; i < count; i++) if (ambiguous[i]) bad++;
        return (double) bad / count;
    }

    /**
     * 0 at a calm signal, 1 at or above the noisy threshold.
     *
     * Gated by {@link #MIN_SAMPLES}: the wait must not react to the one or two frames a
     * segment opens with. This is the fix for the 1132 ms first-wait inflation.
     */
    public double severity() {
        if (count < MIN_SAMPLES) return 0.0;
        return rawSeverity();
    }

    /** The ungated reading. Only the detection margin may use it - never the wait. */
    private double rawSeverity() {
        double r = ambiguousRatio();
        return clamp((r - QUIET_RATIO) / (NOISY_RATIO - QUIET_RATIO));
    }

    /**
     * Multiplier retained for source compatibility. It is 1.0 calm and up to 1.55 in a
     * noisy room, but callers must not multiply a wait by it: noise affects the
     * detection margin only. Kept because older call sites still read it.
     */
    public double pauseFactor() {
        return 1.0 + 0.55 * severity();
    }

    /**
     * Extra activity margin in detector units: 0 calm, up to 0.05.
     *
     * Deliberately NOT gated by MIN_SAMPLES. Widening the margin only makes the detector
     * stricter about what counts as speech - it can delay a decision but never lengthens
     * the wait itself. Reacting to the first ambiguous frame is therefore safe, and the
     * rebaseline path already relies on that immediate reaction.
     */
    public double autoActivityMargin() {
        return 0.05 * rawSeverity();
    }

    private static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
