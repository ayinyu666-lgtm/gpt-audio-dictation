package org.gptdictation.assistant;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** End-to-end replay of captured real frames: quiet -> sound -> quiet must send. */
public final class RealFrameProbe {
    private static final int[] CANCEL = {60, 2287, 155, 2382};
    private static final int[] STOP = {830, 2299, 902, 2371};

    public static void main(String[] args) throws Exception {
        int[] c = WaveformSamplingPolicy.crop(CANCEL, STOP, 1122, 2442);
        int l = c[0], t = c[1];
        int w = c[2] - l, h = c[3] - t;
        System.out.println("crop=" + java.util.Arrays.toString(c) + " " + w + "x" + h);

        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1200, 2000, 180000, 2500, 5000));
        d.beginSegment(0);
        long ts = 0;
        String[] seq = {"q0","q1","q2","q0","q1","q2","q0","q1","q2","q0",
                        "n0","n1","n2","n3","n4","n5","n6","n7",
                        "q0","q1","q2","q0","q1","q2"};
        boolean sent = false;
        for (String name : seq) {
            BufferedImage img = ImageIO.read(new File("_tmp/phone/" + name + ".png"));
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = img.getRGB(l + x, t + y);
            WaveformDetector.Result r = d.observe(px, w, h, ts, 2000, true);
            System.out.printf("%-3s t=%5d %-13s %-22s act=%.3f pause=%4d%n",
                    name, ts, r.signal, r.reason, r.activity, r.pauseMs);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) {
                System.out.println(">>> SEND_PAUSE at t=" + ts + "ms");
                sent = true;
                break;
            }
            ts += 1000;
        }
        System.out.println(sent ? "PASS: pause send fired on real frames" : "FAIL: no pause send");
    }
}
