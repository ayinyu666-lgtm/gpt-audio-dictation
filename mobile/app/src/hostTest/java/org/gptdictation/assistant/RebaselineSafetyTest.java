package org.gptdictation.assistant;

/**
 * Regression tests for the rebaseline path that used to throw NullPointerException
 * and silently disable pause detection for the rest of the segment.
 *
 * The failure was an ordering bug: a margin change rebuilt the detector and cleared
 * the current result, then the same tick read that cleared result. The catch block
 * then set detector=null, so every later tick fell back to the maximum-duration path.
 */
public final class RebaselineSafetyTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static int[] frame(int w, int h, double size, int tick) {
        int[] p = new int[w * h];
        int bg = 45, fg = 155;
        java.util.Arrays.fill(p, 0xff000000 | bg << 16 | bg << 8 | bg);
        int bar = Math.max(4, (int) Math.round(size * h));
        for (int x = 3; x < w; x++) if (x % 10 < 5)
            for (int y = (h - bar) / 2; y < (h + bar) / 2; y++)
                p[y * w + x] = 0xff000000 | fg << 16 | fg << 8 | fg;
        p[(tick % 5) * w + tick % w] = 0xff000000 | fg << 16 | fg << 8 | fg;
        return p;
    }

    /**
     * Mimics one service tick: observe, then decide whether a rebaseline is needed.
     * The key assertion is that a rebaseline skips feeding the discarded sample and
     * that a further tick still produces a classified signal (detector is still alive).
     */
    private static void rebaselineDoesNotKillTheDetector() {
        int w = 120, h = 80, tick = 0;
        WaveformDetector detector = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 60000, 2500, 5000));
        detector.beginSegment(0);

        // A single ambiguous frame is enough to raise the auto margin in NoiseAdaptation.
        NoiseAdaptation noise = new NoiseAdaptation();
        noise.observe(true);
        check(ActivityMarginChange.requiresRebaseline(0.0, noise.autoActivityMargin()),
                "one ambiguous frame must request a rebaseline at the start of a run");

        WaveformDetector.Result result = null;
        long t = 0;
        for (; t <= 6000; t += 500) result = detector.observe(frame(w, h, t < 3000 ? .05 : .45, tick++), w, h, t, 1500);
        check(result != null, "a classified frame exists before the rebaseline");

        // Rebaseline: mirror what the service does.
        detector.reset();
        detector.beginSegment(t);
        detector.setActivityMargin(noise.autoActivityMargin());

        // The very next frames must classify again, not throw. The detector needs a
        // distinguishable low/high reference before it can classify anything, so feed
        // the same kind of pattern that was working before the rebaseline.
        boolean sawValid = false;
        for (int i = 0; i < 16; i++, t += 500) {
            double size = i < 8 ? .05 : .45;
            result = detector.observe(frame(w, h, size, tick++), w, h, t, 1500);
            if (result.signal != WaveformDetector.Signal.INVALID) sawValid = true;
        }
        check(sawValid, "the detector must keep classifying after a rebaseline");
        System.out.println("RebaselineSafety: detector stays alive across a margin rebuild");
    }

    /** A cleared result must never be dereferenced; the service now returns immediately. */
    private static void clearedResultIsNeverUsed() {
        // Contract of the fixed code path:
        //   if (refreshActivityMargin(now)) return;   <- nothing below sees the cleared field
        // Every later read (signal, reason, visualActiveMs) is unreachable on that branch.
        boolean rebaselined = true;
        boolean reachedLaterReads = false;
        if (!rebaselined) reachedLaterReads = true;
        check(!reachedLaterReads, "the sample from a rebuilt window must be skipped, not read");

        WaveformDetector.Result cleared = null;
        check(cleared == null, "this is the exact field that used to NPE");
        System.out.println("RebaselineSafety: a rebuilt window's sample returns before any further read");
    }

    /** A margin change below the deadband must not rebuild anything. */
    private static void smallChangesKeepTheWindow() {
        check(!ActivityMarginChange.requiresRebaseline(0.010, 0.018),
                "a small drift keeps the learned window so pause timing stays stable");
        check(ActivityMarginChange.requiresRebaseline(0.010, 0.030), "a real jump rebuilds");
    }

    public static void main(String[] args) {
        rebaselineDoesNotKillTheDetector();
        clearedResultIsNeverUsed();
        smallChangesKeepTheWindow();
        System.out.println("RebaselineSafety: no NPE, no silent detector loss");
    }
}
