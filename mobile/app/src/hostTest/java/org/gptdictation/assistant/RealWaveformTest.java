package org.gptdictation.assistant;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Host-only regression test built from REAL waveform crops captured on the device.
 *
 * The older synthetic fixtures draw uniform stripes, which no real recording produces and
 * which therefore cannot catch the failure that actually happened: every frame reading the
 * same "activity" no matter how loud the room was. These fixtures are the app's own crop
 * rectangle taken from three moments of one real recording (quiet / medium / loud).
 */
public final class RealWaveformTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static WaveformDetector.Result sample(String name, int tick) throws Exception {
        String base = System.getProperty("waveform.dir");
        BufferedImage img = ImageIO.read(new File(base, name + ".png"));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 60000, 2500, 5000));
        d.beginSegment(0);
        return d.observe(px, w, h, 500L + tick * 500L, 1500);
    }

    /** Loudness must move the activity reading, which the old p75 metric did not. */
    private static void loudIsClearlyAboveQuiet() throws Exception {
        double q = sample("quiet", 0).speechShare;
        double m = sample("mid", 0).speechShare;
        double l = sample("loud", 0).speechShare;
        check(l > q * 3.0, "loud frame must read far above quiet: quiet=" + q + " loud=" + l);
        check(m > q, "medium frame must read above quiet: quiet=" + q + " mid=" + m);
        check(l > m, "loud frame must read above medium: mid=" + m + " loud=" + l);
        System.out.printf("RealWave: quiet=%.4f mid=%.4f loud=%.4f (%.1fx)%n", q, m, l, l / Math.max(q, 1e-9));
    }

    public static void main(String[] args) throws Exception {
        loudIsClearlyAboveQuiet();
        System.out.println("RealWave: real captured crops preserve the loudness ordering");
    }
}
