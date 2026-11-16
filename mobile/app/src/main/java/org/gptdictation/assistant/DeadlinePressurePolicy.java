package org.gptdictation.assistant;

/**
 * Maps "how far a segment has progressed toward its fallback ceiling" to the pause the
 * detector must actually observe before a pause-triggered send is allowed.
 *
 * Why this exists
 * ---------------
 * The user's binding constraint is the number of messages per hour: ChatGPT applies an
 * hourly conversation-round limit, and the measured cadence of the old rule was ~70
 * sends/hour (median interval 32.5 s, 92.6 % of them pause-triggered). A long lecture
 * therefore exhausted the hour's rounds. The fix is to accumulate a long utterance per
 * message instead of closing on the first qualified pause.
 *
 * The user's wording is directional, not a binary gate: early in a segment the DESIRE TO
 * SEND must be low, and the closer the segment gets to the fallback ceiling the MORE
 * AGGRESSIVELY the assistant should hunt for a pause. So this class is a continuous,
 * monotonically non-increasing function of the elapsed fraction:
 *
 *   early fraction  -> {@link #CAP_MS} (30 s), effectively unreachable, so an ordinary
 *                      conversation pause cannot end the message;
 *   near the ceiling -> the learned normal pause, and then below it, down to
 *                      {@link #FLOOR_MS}, so the last part of the window is hunted hard.
 *
 * There is deliberately no `armed` boolean and no threshold cliff: a gate would make the
 * first pause after the gate fire immediately regardless of how long it was. A continuous
 * curve instead means the requirement falls smoothly and a long silence that began early
 * fires exactly when the falling curve meets the accumulated quiet.
 *
 * Why the fraction, not absolute milliseconds
 * -------------------------------------------
 * The same curve must behave identically at a 30 s ceiling and at a 400 s ceiling (the
 * user plans to use 200/300/400 s later). Everything here is expressed as a fraction of
 * the ceiling, so the policy is ceiling-agnostic; only the fraction enters the formula.
 *
 * This class is pure Java with no Android imports so the host tests can run the exact
 * production rule on the JVM. It never sees pixels, audio, text or message state.
 */
final class DeadlinePressurePolicy {
    /**
     * Largest pause the detector accepts (WaveformDetector.observe validates 300..30000).
     * At the start of a segment the requirement sits here, which is practically "never":
     * only a continuous 30 s of visual silence could satisfy it.
     */
    static final long CAP_MS = 30000;
    /**
     * Elapsed fraction below which the cap applies unchanged. 0.40 of a 200 s ceiling is
     * 80 s, so the first 40 % of a classroom message is protected from short pauses.
     */
    static final double ARM_START = 0.40;
    /**
     * Elapsed fraction at and above which the learned normal pause applies unchanged.
     * 0.95 of a 200 s ceiling is 190 s. Between ARM_START and FULL_AT the requirement
     * slides smoothly from CAP_MS down to the learned value.
     */
    static final double FULL_AT = 0.95;
    /**
     * Absolute floor near the ceiling. Below ~1.2 s a pause is indistinguishable from one
     * or two sampling intervals of ordinary speech gaps; sending on that would turn the
     * "hunt for a pause" into "send while the speaker is mid-sentence". 1200 ms is also
     * comfortably above the detector's 1000 ms minimum evidence rule, so the last window
     * is genuinely aggressive without being a hair trigger.
     */
    static final long FLOOR_MS = 1200;
    /**
     * Bias shift per step, applied to the fraction before the curve. Positive bias means
     * "more sensitive / send sooner", so it moves the effective progress forward and the
     * threshold down; negative bias moves it backward and the threshold up. 0.015 over the
     * full -9..+9 range shifts the curve by +-0.135, i.e. about +-27 s at a 200 s ceiling.
     */
    static final double BIAS_SHIFT = 0.015;

    private DeadlinePressurePolicy() {
    }

    /**
     * Elapsed fraction of the segment, 0 at segment start and 1 at the fallback ceiling.
     *
     * Production derives this from {@code recordUntil - now} (the ceiling is authoritative),
     * never from {@code segmentVisualActiveMs}: that counter is reset by crop changes,
     * noise rebaselining, layout changes and suspend/resume, which would jump the pressure
     * curve back to its start mid-segment.
     */
    static double elapsedFraction(long remainingMs, long maxMs) {
        if (maxMs <= 0) return 0.0;
        double remaining = Math.max(0, Math.min(maxMs, remainingMs));
        double fraction = 1.0 - remaining / (double) maxMs;
        return fraction < 0 ? 0 : fraction > 1 ? 1 : fraction;
    }

    /**
     * The pause the detector must observe at this point of the segment.
     *
     * @param elapsedFraction 0..1, see {@link #elapsedFraction}
     * @param normalPauseMs   the learned/biased pause from {@link AdaptivePausePolicy}
     * @param bias            -9..9; positive = more aggressive (earlier / lower threshold)
     * @return required pause in milliseconds, always inside [{@link #FLOOR_MS}, {@link #CAP_MS}]
     */
    static long requiredPauseMs(double elapsedFraction, long normalPauseMs, int bias) {
        double fraction = clamp01(elapsedFraction);
        // The learned value is the target at FULL_AT; clamp it into the legal band first so
        // a policy value outside 1200..30000 can never push the curve out of range.
        double anchor = Math.max(FLOOR_MS, Math.min(CAP_MS, normalPauseMs));
        double adjusted = clamp01(fraction + BIAS_SHIFT * bias);
        double value;
        if (adjusted <= ARM_START) {
            // Early: the cap. This is the "desire to send is low" part of the requirement.
            value = CAP_MS;
        } else if (adjusted <= FULL_AT) {
            // Smoothstep between the cap and the learned pause. Both joins have zero slope,
            // so there is no cliff where a one-frame change suddenly makes a send possible.
            double x = (adjusted - ARM_START) / (FULL_AT - ARM_START);
            value = CAP_MS + (anchor - CAP_MS) * smoothStep(x);
        } else if (adjusted < 1.0) {
            // The last part of the window: go below the learned pause and hunt aggressively,
            // but never below the absolute floor.
            double x = (adjusted - FULL_AT) / (1.0 - FULL_AT);
            value = anchor + (FLOOR_MS - anchor) * smoothStep(x);
        } else {
            value = FLOOR_MS;
        }
        return Math.max(FLOOR_MS, Math.min(CAP_MS, Math.round(value)));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }

    /** Same zero-slope construction used by AdaptivePausePolicy.speechLengthRamp. */
    private static double smoothStep(double x) {
        double c = clamp01(x);
        return c * c * (3 - 2 * c);
    }
}
