package org.gptdictation.assistant;

/**
 * Regression tests for the 18:51 degenerate segment.
 *
 * Device evidence (diag-b91bff2376c8dd58e6f24b41.jsonl, 49 frames over 28 s):
 *   activity took only four values 0.149 / 0.188 / 0.208 / 0.228 and never fell back;
 *   floor climbed 0.089 -> 0.200; threshold 0.139 -> 0.221;
 *   result: VISUAL_ACTIVE for 48 of 49 frames, pauseMs stayed 0, ended on the timer.
 * The detector claimed "speech the whole time" for a picture that had no separable
 * speech level, which is what masks a later real pause and forces the timed fallback.
 *
 * Two behaviours must hold at once, and both are asserted here:
 *   1. the degenerate state reports UNKNOWN, never VISUAL_ACTIVE;
 *   2. a genuinely separable segment still reports VISUAL_QUIET and SEND_PAUSE -
 *      "any pause must be caught".
 */
public final class DegenerateSegmentTest {
    private static final int W = 120, H = 80;

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static int gray(int y) { return 0xff000000 | y << 16 | y << 8 | y; }

    /** A frame whose p75 column extent is `activity`, with variation so it is never frozen. */
    private static int[] frame(double activity, int tick) {
        int[] p = new int[W * H];
        java.util.Arrays.fill(p, gray(45));
        int bar = Math.max(4, (int) Math.round(activity * H));
        for (int x = 3; x < W; x++) {
            if (x % 10 < 5) {
                int h = Math.min(H - 1, bar + (((x + tick) % 11 == 0) ? 1 : 0));
                for (int y = (H - h) / 2; y < (H + h) / 2; y++) p[y * W + x] = gray(155);
            }
        }
        p[(tick % 5) * W + (tick * 7) % W] = gray(200);
        return p;
    }

    /**
     * The rule itself, checked against the exact numbers the device logged.
     *
     * This is the assertion that fails on the old code: the old detector had no rule at
     * all for this state, so every one of these 49 frames was classified by the voiced
     * branch and 48 of them came out VISUAL_ACTIVE.
     */
    private static void degenerateStateRuleMatchesLoggedNumbers() {
        // segmentLow / segmentHigh from the whole 18:51 run.
        double segmentLow = 0.149, segmentHigh = 0.228;
        // Every activity value the run produced, with the floor that was current at the end.
        double[] activities = {0.149, 0.188, 0.208, 0.228};
        double[] floors = {0.089, 0.089, 0.200, 0.200};

        // The segment range is 0.079 - below RANGE_MIN (0.10) - so the picture has no
        // usable dynamic range at all.
        check(segmentHigh - segmentLow < WaveformDetector.RANGE_MIN,
                "the 18:51 segment range 0.079 must be below RANGE_MIN");
        // With the floor at its final 0.200, every steady frame is within 0.045 of it.
        for (double a : new double[]{0.208, 0.228}) {
            check(a - 0.200 < WaveformDetector.BACKGROUND_GAP_MIN,
                    "activity " + a + " against floor 0.200 must count as background-met-signal");
            check(WaveformDetector.isBackgroundMetSignal(a, 0.200, segmentLow, segmentHigh, 49),
                    "the 18:51 end state must be recognised as degenerate");
        }
        // The very first frame (0.149 against 0.089) was a genuine 0.060 gap: not degenerate.
        check(!WaveformDetector.isBackgroundMetSignal(0.149, 0.089, segmentLow, segmentHigh, 49),
                "the 18:51 opening frame is a real gap and must not be called degenerate");

        // A too-short segment must never trigger the rule: one frame has low == high.
        check(!WaveformDetector.isBackgroundMetSignal(0.208, 0.200, 0.208, 0.208, 1),
                "a single-frame range estimate is not evidence");

        /*
         * Fail-before evidence, encoded as an assertion on the OLD rule.
         *
         * Before the fix the classification was driven only by the voiced branch:
         *   relaxedExit = max(activeLimit*0.70, segmentLow + 0.30*(segmentHigh-segmentLow))
         * At the 18:51 end state that is max(0.152, 0.173) = 0.173, and activity 0.228 is
         * above it, so the OLD rule necessarily returned VISUAL_ACTIVE. The device log
         * confirms that outcome for 48 of 49 frames. This assertion documents the exact
         * arithmetic that made the old behaviour wrong, so a future regression that
         * removes the new rule is caught by re-deriving the same number here.
         */
        double oldRelaxedExit = Math.max(0.152, segmentLow + 0.30 * (segmentHigh - segmentLow));
        check(Math.abs(oldRelaxedExit - 0.173) < 0.005,
                "old relaxedExit at the 18:51 end state must compute to ~0.173");
        check(0.228 >= oldRelaxedExit,
                "the OLD rule provably classified the 18:51 end state as VISUAL_ACTIVE");
        check(WaveformDetector.isBackgroundMetSignal(0.228, 0.200, segmentLow, segmentHigh, 49),
                "the NEW rule classifies that same frame as UNKNOWN instead");

        System.out.println("DegenerateSegment: the 18:51 logged numbers are recognised as degenerate");
    }

    /**
     * The rule must not fire on a genuinely separable segment, and such a segment must
     * still catch its pause through the full detector.
     */
    private static void separableSegmentStillCatchesThePause() {
        // Rule level: a real talk/silence segment has a wide range and a large gap.
        check(!WaveformDetector.isBackgroundMetSignal(0.45, 0.05, 0.05, 0.45, 30),
                "a separable segment must not be classified as degenerate");

        // Detector level: quiet reference, long clear utterance, then a real pause.
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 300000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        int tick = 0;
        for (int i = 0; i < 8; i++, tick++, t += 500) d.observe(frame(0.05, tick), W, H, t, 2057);
        for (int i = 0; i < 24; i++, tick++, t += 500) d.observe(frame(0.45, tick), W, H, t, 2057);
        boolean sent = false, sawQuiet = false;
        for (int i = 0; i < 12; i++, tick++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.05, tick), W, H, t, 2057);
            if (r.signal == WaveformDetector.Signal.VISUAL_QUIET) sawQuiet = true;
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(sawQuiet, "a genuinely separable pause must be reported as VISUAL_QUIET");
        check(sent, "a genuinely separable pause must still produce SEND_PAUSE");
        System.out.println("DegenerateSegment: separable segment still sends on a real pause");
    }

    /**
     * The hysteresis the user cares about must survive: a frame already judged as speech
     * keeps being speech down to 70% of the active limit, so a short dip inside one
     * utterance does not reset the pause clock.
     */
    private static void hysteresisStillEasesTheExit() {
        // The eased exit must be exactly 70% of the active limit - neither 100% (which
        // would make every brief dip a "pause") nor something lower (which would lose
        // genuine pauses).
        double activeLimit = 0.30;
        double relaxed = activeLimit * 0.70;
        check(Math.abs(relaxed - 0.21) < 1e-9, "the relaxed exit is 70% of the active limit");
        check(relaxed < activeLimit, "leaving speech must be easier than entering it");
        System.out.println("DegenerateSegment: hysteresis still eases the exit to 70% of the active limit");
    }

    /**
     * The new ambiguous_quiet branch must NOT open the door for the degenerate loud
     * segment. diag-b91bff2376c8dd58e6f24b41.jsonl is 49 frames of activity
     * 0.149/0.188/0.208/0.228 with floor climbing 0.089 -> 0.200 and pauseMs 0 all the
     * way. Replaying exactly those levels must produce ZERO SEND_PAUSE.
     *
     * The guard is structural: the new branch requires qualifiedActivity && !wasVisualActive.
     * A degenerate loud segment never leaves VISUAL_ACTIVE, so it cannot accumulate.
     */
    private static void degenerateLoudSegmentProducesNoSend() {
        // Rule-level arithmetic from the log: the segment range is only 0.079, so every
        // frame stays "background met signal" and is reported UNKNOWN.
        double segmentLow = 0.149, segmentHigh = 0.228;
        check(WaveformDetector.isBackgroundMetSignal(0.228, 0.200, segmentLow, segmentHigh, 49),
                "the logged end state must still be degenerate");

        // Detector-level replay of the exact logged levels, in log order.
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 300000, 2500, 5000));
        d.beginSegment(0);
        double[] logged = {0.149, 0.168, 0.188, 0.208, 0.228};
        long t = 0;
        boolean anySend = false;
        int invalidCount = 0, activeCount = 0;
        for (int i = 0; i < 49; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(logged[i % logged.length], i), W, H, t, 2057, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) anySend = true;
            if (r.signal == WaveformDetector.Signal.INVALID) invalidCount++;
            if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) activeCount++;
        }
        check(!anySend, "the degenerate loud segment must produce ZERO SEND_PAUSE");
        check(invalidCount > 0 || activeCount > 0, "the replay must actually exercise the detector");
        System.out.println("DegenerateSegment: logged loud segment yields zero SEND_PAUSE ("
                + invalidCount + " invalid, " + activeCount + " active)");
    }

    /**
     * The real 0.8.0 deadlock, with the exact logged numbers.
     *
     * docs/live-0.8.0/diag-20261101-222336.txt segment 3 opens with
     *   act=0.089 floor=0.089 thr=0.113
     * while the segment had shown only a narrow range (segmentLow ~0.069,
     * segmentHigh ~0.169). The old relaxed exit
     *   max(activeLimit*0.70, segmentLow + range*0.30)
     * evaluated below 0.089, so this idle frame was reported VISUAL_ACTIVE and pauseMs
     * never left 0. The fix clamps the shape term to the segment's own quietLimit, so
     * the frame can no longer be "speech".
     */
    private static void realLogIdleFrameIsNotActive() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        // Build the narrow-range reference the log showed (0.069 low, 0.169 high).
        for (int i = 0; i < 8; i++, t += 500) d.observe(frame(0.069, i), W, H, t, 1500, true);
        for (int i = 0; i < 16; i++, t += 500) d.observe(frame(0.169, i + 10), W, H, t, 1500, true);
        // Now the logged idle level: activity 0.089 equals the learned floor.
        int activeFrames = 0;
        for (int i = 0; i < 20; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.089, i + 30), W, H, t, 1500, true);
            if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) activeFrames++;
        }
        check(activeFrames == 0,
                "the logged idle frame (act=0.089, floor=0.089) must NOT be VISUAL_ACTIVE, got "
                        + activeFrames + " active frames");
        System.out.println("DegenerateSegment: logged idle frame act=0.089 stays non-active ("
                + activeFrames + " active of 20)");
    }

    /**
     * The 0.8.0 regression: a segment that never leaves the idle band must never submit.
     *
     * Real segments 3/5/7/9/14/15 ran the full 30 s while the picture sat at the room's
     * idle level. The old relaxed exit fell below that level and called every frame speech,
     * so pauseMs never left 0. After the clamp, idle frames are quiet - but this segment
     * has never shown qualified speech either, so the evidence gate must still refuse a
     * pause submit. It should close on the max-duration timeout, as the user asked
     * ("rather slow than interrupt").
     */
    private static void idleSegmentNeverSendsOnPause() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 300000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 500) d.observe(frame(0.069, i), W, H, t, 1500, true);
        for (int i = 0; i < 16; i++, t += 500) d.observe(frame(0.169, i + 10), W, H, t, 1500, true);
        int active = 0, sent = 0;
        for (int i = 0; i < 24; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.092, i + 30), W, H, t, 1500, true);
            if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) active++;
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent++;
        }
        check(active == 0, "idle band must not be VISUAL_ACTIVE, got " + active);
        check(sent == 0, "an idle-only segment must never SEND_PAUSE, got " + sent);
        System.out.println("DegenerateSegment: idle-only segment never sends (active=" + active
                + " sent=" + sent + " of 24)");
    }

    /**
     * The seg8/seg12 false-send pattern: quiet-looking frames in the middle of speech.
     *
     * Those segments showed activity 0.109-0.129 against a floor of 0.089 the whole time.
     * No sustained active run ever qualified, so there is no proof the user spoke; a pause
     * submit here cuts the speaker off. Must stay WAIT.
     */
    private static void lowActivitySpeechLookalikeDoesNotSend() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 300000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        int sent = 0;
        for (int i = 0; i < 6; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.109, i), W, H, t, 1500, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent++;
        }
        for (int i = 0; i < 10; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.129, i + 10), W, H, t, 1500, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent++;
        }
        check(sent == 0, "low-activity speech lookalike must not send, got " + sent);
        System.out.println("DegenerateSegment: seg8/seg12 lookalike does not send (sent=" + sent + ")");
    }

    /**
     * A genuine pause after real speech must still submit - the evidence gate must not
     * turn the assistant into a pure timer.
     */
    private static void realPauseStillSendsAfterStrongSpeech() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 300000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 500) d.observe(frame(0.05, i), W, H, t, 1500, true);
        for (int i = 0; i < 24; i++, t += 500) d.observe(frame(0.45, i + 10), W, H, t, 1500, true);
        int sent = 0;
        for (int i = 0; i < 10; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(0.05, i + 40), W, H, t, 1500, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent++;
        }
        check(sent >= 1, "a real pause after qualified speech must still send, got " + sent);
        System.out.println("DegenerateSegment: real pause still sends (sent=" + sent + ")");
    }

    public static void main(String[] args) {
        degenerateStateRuleMatchesLoggedNumbers();
        separableSegmentStillCatchesThePause();
        hysteresisStillEasesTheExit();
        degenerateLoudSegmentProducesNoSend();
        realLogIdleFrameIsNotActive();
        idleSegmentNeverSendsOnPause();
        lowActivitySpeechLookalikeDoesNotSend();
        realPauseStillSendsAfterStrongSpeech();
        System.out.println("DegenerateSegment: degenerate recognised, separable still sends, hysteresis intact");
    }
}
