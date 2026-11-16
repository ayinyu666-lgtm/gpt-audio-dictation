package org.gptdictation.assistant;

/**
 * Host-only tests for the noisy-signal adaptation used by the detector.
 *
 * The central contract since the short-question work: this class may not say anything
 * at all until it has seen MIN_SAMPLES frames. A single ambiguous frame at the start
 * of a segment used to pin pauseFactor() at 1.55 and inflated the very first wait of
 * every segment by 1132 ms.
 */
public final class NoiseAdaptationTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void near(double actual, double expected, String why) {
        check(Math.abs(actual - expected) < 1e-9, why + " (expected " + expected + ", got " + actual + ")");
    }

    private static void calmRoomChangesNothing() {
        NoiseAdaptation n = new NoiseAdaptation();
        for (int i = 0; i < 24; i++) n.observe(false);
        near(n.ambiguousRatio(), 0.0, "calm ratio");
        near(n.severity(), 0.0, "calm severity");
        near(n.pauseFactor(), 1.0, "calm must not lengthen the pause");
        near(n.autoActivityMargin(), 0.0, "calm must not raise the activity margin");
    }

    /**
     * The regression this class exists for: one ambiguous frame at segment start must
     * leave the factor neutral. The old code returned bad/count = 1/1 = 1.0 ratio and
     * therefore 1.55 immediately.
     */
    private static void oneAmbiguousFrameAtSegmentStartIsNeutral() {
        NoiseAdaptation n = new NoiseAdaptation();
        n.observe(true);
        check(n.sampleCount() == 1, "one frame recorded");
        near(n.ambiguousRatio(), 1.0, "one bad frame is still a ratio of 1.0");
        near(n.severity(), 0.0, "but severity must stay neutral before MIN_SAMPLES");
        near(n.pauseFactor(), 1.0, "ONE ambiguous frame must not inflate the pause factor");
        // The margin is intentionally ungated: widening it only makes detection stricter,
        // and the rebaseline path depends on reacting to the first ambiguous frame.
        check(n.autoActivityMargin() > 0.0, "the margin may react before MIN_SAMPLES");

        // Two and three frames are still below the gate.
        n.observe(true);
        n.observe(true);
        check(n.sampleCount() == 3, "three frames recorded");
        near(n.pauseFactor(), 1.0, "three ambiguous frames still neutral");
        near(n.severity(), 0.0, "three ambiguous frames still neutral severity");
    }

    /** At MIN_SAMPLES the gate opens and a fully ambiguous window is maximum severity. */
    private static void fourAmbiguousFramesReachMaximum() {
        NoiseAdaptation n = new NoiseAdaptation();
        for (int i = 0; i < NoiseAdaptation.MIN_SAMPLES; i++) n.observe(true);
        check(n.sampleCount() == NoiseAdaptation.MIN_SAMPLES, "gate reached");
        near(n.ambiguousRatio(), 1.0, "all ambiguous");
        near(n.severity(), 1.0, "maximum severity");
        near(n.pauseFactor(), 1.55, "4 ambiguous frames must yield the maximum 1.55 factor");
        near(n.autoActivityMargin(), 0.05, "4 ambiguous frames must raise the margin fully");

        // MIN_SAMPLES is exactly 4 in this build; guard the constant itself.
        check(NoiseAdaptation.MIN_SAMPLES == 4, "MIN_SAMPLES must be 4, got " + NoiseAdaptation.MIN_SAMPLES);
    }

    private static void noisyRoomIsConservative() {
        NoiseAdaptation n = new NoiseAdaptation();
        for (int i = 0; i < 24; i++) n.observe(i % 2 == 0);
        near(n.ambiguousRatio(), 0.5, "half ambiguous");
        double factor = n.pauseFactor();
        check(factor > 1.15 && factor < 1.55, "noisy room raises the factor, got " + factor);
        check(n.autoActivityMargin() > 0.02 && n.autoActivityMargin() <= 0.05,
                "noisy room raises the activity margin, got " + n.autoActivityMargin());

        NoiseAdaptation worst = new NoiseAdaptation();
        for (int i = 0; i < 24; i++) worst.observe(true);
        near(worst.severity(), 1.0, "all ambiguous is maximum severity");
        near(worst.pauseFactor(), 1.55, "maximum pause factor");
        near(worst.autoActivityMargin(), 0.05, "maximum activity margin");
    }

    private static void followsChangesAndIsBounded() {
        NoiseAdaptation n = new NoiseAdaptation();
        for (int i = 0; i < 24; i++) n.observe(true);
        near(n.severity(), 1.0, "noisy first");
        for (int i = 0; i < 24; i++) n.observe(false);
        near(n.severity(), 0.0, "recovers when the room calms down");
        check(n.sampleCount() == 24, "bounded window");

        NoiseAdaptation empty = new NoiseAdaptation();
        check(empty.ambiguousRatio() == 0.0 && empty.pauseFactor() == 1.0, "no samples is neutral");
        empty.reset();
        check(empty.sampleCount() == 0, "reset clears the window");

        // After reset the gate closes again: the first ambiguous frame is neutral.
        empty.observe(true);
        near(empty.pauseFactor(), 1.0, "reset must re-close the sample gate");
    }

    /** Noise must never change the wait itself; it only widens the detector margin. */
    private static void pausePolicyIgnoresTheFactor() {
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        long t = 0;
        for (int i = 0; i < 12; i++) {
            p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t, true);
            p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 1000, true);
            p.observe(WaveformDetector.Signal.VISUAL_QUIET, t + 2000, true);
            p.observe(WaveformDetector.Signal.VISUAL_QUIET, t + 3000, true);
            p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 4000, true);
            p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 5000, true);
            t += 6000;
        }
        // A long segment so the short-question ceiling does not mask the comparison.
        long calm = p.requiredPauseMs(0, 60_000, 1.0);
        long noisy = p.requiredPauseMs(0, 60_000, 1.55);
        check(noisy == calm, "noise factor must not lengthen the wait: calm=" + calm + " noisy=" + noisy);
        check(noisy <= AdaptivePausePolicy.MAX_PAUSE_MS, "still bounded");
        System.out.println("NoiseAdaptation: calm " + calm + " ms, noisy " + noisy + " ms at the same rhythm");
    }

    public static void main(String[] args) {
        calmRoomChangesNothing();
        oneAmbiguousFrameAtSegmentStartIsNeutral();
        fourAmbiguousFramesReachMaximum();
        noisyRoomIsConservative();
        followsChangesAndIsBounded();
        pausePolicyIgnoresTheFactor();
        System.out.println("NoiseAdaptation: ratios, MIN_SAMPLES gate, severity, pause factor and bounds passed");
    }
}
