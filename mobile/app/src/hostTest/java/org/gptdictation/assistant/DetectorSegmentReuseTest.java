package org.gptdictation.assistant;

/** Checks that a single detector retains its learned visual baseline across segments. */
public final class DetectorSegmentReuseTest {
    private static final int W = 120, H = 80;
    private static int[] frame(int height, int tick) {
        int[] pixels = new int[W * H]; java.util.Arrays.fill(pixels, 0xff303030);
        for (int x = 3; x < W; x++) if (x % 10 < 5)
            for (int y = (H-height)/2; y < (H+height)/2; y++) pixels[y*W+x] = 0xffb0b0b0;
        pixels[tick % pixels.length] = 0xffb0b0b0; return pixels;
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000,2000,30000,2500,5000));
        d.beginSegment(1000);
        for (int i=0; i<14; i++) d.observe(frame(i%2 == 0 ? 10 : 44, i), W, H, 1000+i*500, 2000);
        d.beginSegment(8500);
        WaveformDetector.Result retained = d.observe(frame(44, 31), W, H, 9000, 2000);
        check(!"indistinguishable_activity".equals(retained.reason), "baseline should survive beginSegment");
        d.reset(); d.beginSegment(9500);
        WaveformDetector.Result reset = d.observe(frame(44, 32), W, H, 10000, 2000);
        check("indistinguishable_activity".equals(reset.reason), "layout reset must discard baseline");
        System.out.println("DetectorSegmentReuse: retained across segments; reset clears baseline");
    }
}
