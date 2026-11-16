package org.gptdictation.assistant;

/** Host-only tests for the margin-change guard that prevents false pause sends. */
public final class ActivityMarginChangeTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void deadbandIgnoresJitter() {
        check(!ActivityMarginChange.requiresRebaseline(0.0, 0.005), "tiny rise is jitter");
        check(!ActivityMarginChange.requiresRebaseline(0.05, 0.045), "tiny fall is jitter");
        check(!ActivityMarginChange.requiresRebaseline(0.0, 0.011), "just under the deadband");
        check(ActivityMarginChange.requiresRebaseline(0.0, 0.012), "at the deadband it must rebuild");
        check(ActivityMarginChange.requiresRebaseline(0.05, 0.0), "a large fall also rebuilds");
    }

    private static void tighteningIsIdentified() {
        check(ActivityMarginChange.isTightening(0.0, 0.03), "raising the bar is tightening");
        check(!ActivityMarginChange.isTightening(0.03, 0.0), "lowering the bar is loosening");
        check(!ActivityMarginChange.isTightening(0.02, 0.02), "no change is not tightening");
    }

    private static void nanIsIgnored() {
        check(!ActivityMarginChange.requiresRebaseline(Double.NaN, 0.02), "NaN applied must not reset");
        check(!ActivityMarginChange.requiresRebaseline(0.02, Double.NaN), "NaN target must not reset");
    }

    /**
     * The scenario that motivated this guard: a segment already classified as active
     * must not produce a pause decision in the same tick that the margin jumps upward.
     */
    private static void raisingTheBarCannotSendImmediately() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 3000, 60000, 2500, 5000));
        d.beginSegment(0);
        int w = 120, h = 80, tick = 0;
        WaveformDetector.Result r = null;
        for (long t = 0; t <= 6000; t += 500) r = d.observe(frame(w, h, t < 3000 ? .05 : .45, tick++), w, h, t, 1500);
        check(r.signal == WaveformDetector.Signal.VISUAL_ACTIVE, "warmup should be active");

        // Simulate the service's reaction to a margin jump: rebuild, then feed the same
        // mid-speech picture again. It must be unknown, never a pause send.
        d.reset(); d.beginSegment(6500); d.setActivityMargin(0.05);
        for (long t = 6500; t <= 9500; t += 500) {
            r = d.observe(frame(w, h, .45, tick++), w, h, t, 1500);
            check(r.decision != WaveformDetector.Decision.SEND_PAUSE,
                    "a mid-sentence threshold change must never trigger a send");
        }
        check(ActivityMarginChange.requiresRebaseline(0.0, 0.05), "this jump must be treated as a rebaseline");
        System.out.println("ActivityMarginChange: deadband, tightening and no-instant-send passed");
    }

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

    public static void main(String[] args) {
        deadbandIgnoresJitter();
        tighteningIsIdentified();
        nanIsIgnored();
        raisingTheBarCannotSendImmediately();
        System.out.println("ActivityMarginChange: guard behaviour passed");
    }
}
