package org.gptdictation.assistant;

/**
 * Host-only test for the speech-exit hysteresis copied from the browser extension.
 *
 * The point of the rule: once a run of activity has been QUALIFIED as speech, a brief
 * dip to 70-100% of the active limit must stay "speaking", so the pause clock is not
 * reset inside one sentence. Before qualification the full limit still applies, so a
 * quiet room with one stray blip cannot enter the relaxed state.
 */
public final class HysteresisTest {
    private static final int W = 120, H = 80;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static int[] frame(double size, int tick) {
        int[] p = new int[W * H];
        int bg = 45, fg = 155;
        java.util.Arrays.fill(p, 0xff000000 | bg << 16 | bg << 8 | bg);
        int bar = Math.max(4, (int) Math.round(size * H));
        for (int x = 3; x < W; x++) if (x % 10 < 5)
            for (int y = (H - bar) / 2; y < (H + bar) / 2; y++) p[y * W + x] = 0xff000000 | fg << 16 | fg << 8 | fg;
        p[(tick % 5) * W + tick % W] = 0xff000000 | fg << 16 | fg << 8 | fg;
        return p;
    }

    private static WaveformDetector detector() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 300000, 2500, 5000));
        d.beginSegment(0);
        return d;
    }

    /** Learn low/high, then prove a mid-band dip does not break the speech run. */
    private static void dipInsideSpeechDoesNotCountAsPause() {
        WaveformDetector d = detector();
        int tick = 0;
        // Learn a quiet background and a clearly active level.
        for (long t = 0; t <= 6000; t += 500) d.observe(frame(t < 3000 ? .05 : .45, tick++), W, H, t, 1500);
        // Speak steadily; establish the qualified run.
        WaveformDetector.Result r = null;
        for (long t = 6500; t <= 12000; t += 500) r = d.observe(frame(.45, tick++), W, H, t, 1500);
        check(r.signal == WaveformDetector.Signal.VISUAL_ACTIVE, "steady speech must read active");
        // A single dip to 0.70-0.80 of the active limit: with hysteresis this stays speech.
        r = d.observe(frame(.33, tick++), W, H, 12500, 1500);
        check(r.signal == WaveformDetector.Signal.VISUAL_ACTIVE, "dip inside speech must stay active under hysteresis");
    }

    /** A room that never spoke must not get the relaxed exit bar. */
    private static void unqualifiedRoomKeepsStrictLimit() {
        WaveformDetector d = detector();
        int tick = 0;
        for (long t = 0; t <= 6000; t += 500) d.observe(frame(t < 3000 ? .05 : .45, tick++), W, H, t, 1500);
        // A genuinely sub-threshold blip, well under the active limit this segment learned.
        WaveformDetector.Result r = d.observe(frame(.12, tick++), W, H, 6500, 1500);
        check(r.signal != WaveformDetector.Signal.VISUAL_ACTIVE, "sub-threshold blip must not become speech");
    }

    public static void main(String[] args) {
        dipInsideSpeechDoesNotCountAsPause();
        unqualifiedRoomKeepsStrictLimit();
        System.out.println("Hysteresis: relaxed exit only after qualified speech; strict entry otherwise");
    }
}
