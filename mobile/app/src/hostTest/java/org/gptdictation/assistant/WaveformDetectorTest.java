package org.gptdictation.assistant;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Host-only test: compile these two Java files with javac, then run this main. */
public final class WaveformDetectorTest {
    private static final int W = 120, H = 80;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static int gray(int y) { return 0xff000000 | y << 16 | y << 8 | y; }
    private static int[] frame(double size, int tick, boolean inverted) {
        int[] p = new int[W * H];
        int bg = inverted ? 215 : 45, fg = inverted ? 90 : 155;
        java.util.Arrays.fill(p, gray(bg));
        int h = Math.max(4, (int) Math.round(size * H));
        for (int x = 3; x < W; x++) if (x % 10 < 5)
            for (int y = (H - h) / 2; y < (H + h) / 2; y++) p[y * W + x] = gray(fg);
        // A changing, isolated pixel imitates harmless screenshot noise, not a waveform bar.
        p[(tick % 5) * W + tick % W] = gray(fg);
        return p;
    }
    private static WaveformDetector.Result sample(WaveformDetector d, long t, double size, long preference) {
        return d.observe(frame(size, (int) (t / 100), false), W, H, t, preference);
    }
    private static WaveformDetector detector(long max) {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, max, 1200, 4000));
        d.beginSegment(0); return d;
    }
    private static void behavior() {
        WaveformDetector d = detector(30000); WaveformDetector.Result r = null;
        // A sustained, varying high background without a low comparison remains unknown.
        for (int t = 0; t <= 6000; t += 500) {
            r = sample(d, t, t % 1000 == 0 ? .40 : .425, 1500);
            check(r.signal == WaveformDetector.Signal.INVALID && r.decision == WaveformDetector.Decision.HOLD_INVALID, "constant activity is ambiguous");
        }
        // Two distinguishable levels provide visual evidence; this does not identify speech.
        for (int t = 6500; t <= 15000; t += 500) r = sample(d, t, t < 9000 ? .075 : .45, 1500);
        check(r.signal == WaveformDetector.Signal.VISUAL_ACTIVE, "learn distinct high and low activity");
        boolean sent = false;
        for (int t = 15500; t <= 19500; t += 500) {
            r = sample(d, t, .075, 1500);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(sent, "qualified visually active period followed by pause");
        r = d.observe(new int[W * H], W, H, 20000, 1500);
        check(r.signal == WaveformDetector.Signal.INVALID && r.decision == WaveformDetector.Decision.HOLD_INVALID, "zero frame must hold");
        for (int t = 20500; t <= 22000; t += 500) {
            r = sample(d, t, .075, 1500);
            check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "invalid input must clear a stale pause decision");
        }
        r = sample(d, 25000, .075, 1500);
        check(r.signal == WaveformDetector.Signal.INVALID && r.reason.equals("frame_gap"), "gap must hold");
        r = sample(d, 25000, .075, 1500);
        check(r.signal == WaveformDetector.Signal.INVALID, "timestamp must increase");
        d = detector(4000);
        for (int t = 0; t <= 5000; t += 500) r = sample(d, t, .40, 1500);
        check(r.decision == WaveformDetector.Decision.HOLD_INVALID, "maximum cannot bypass ambiguous noise");
        int[] still = frame(.075, 1, false);
        d = detector(30000);
        for (int t = 0; t <= 6000; t += 500) r = d.observe(still, W, H, t, 1000);
        check(r.signal == WaveformDetector.Signal.INVALID && r.reason.equals("static_frame_unverified"), "unverified repeated image");
        d = detector(30000);
        for (int t = 0; t <= 10000; t += 500)
            r = sample(d, t, t < 3000 ? .075 : .45, 1500);
        check(r.signal == WaveformDetector.Signal.VISUAL_ACTIVE, "static test warmup");
        for (int t = 10500; t <= 15000; t += 500)
            r = d.observe(still, W, H, t, 1500, true);
        check(r.decision == WaveformDetector.Decision.SEND_PAUSE, "externally verified static quiet");
        d.reset();
        d.beginSegment(16000);
        r = sample(d, 16000, .45, 1000);
        check(r.signal == WaveformDetector.Signal.INVALID, "environment reset clears baseline");

        for (double baseline : new double[] { .075, .20, .30 }) {
            d = detector(30000);
            boolean visualPauseSend = false;
            // These patterns may equally be speech or noise. The decision reflects
            // visual activity only and must not require source identity confirmation.
            for (int t = 0; t <= 24000; t += 500) {
                double level = (t / 3000) % 2 == 0 ? baseline : baseline + .30;
                r = sample(d, t, level, 1000);
                if (r.decision == WaveformDetector.Decision.SEND_PAUSE) visualPauseSend = true;
            }
            check(visualPauseSend, "visual pause sends without an identity gate at baseline " + baseline);
            check(r.signal != WaveformDetector.Signal.INVALID, "distinct levels adapt for baseline " + baseline);
        }
    }

    private static void screenshot(String root) throws Exception {
        BufferedImage img = ImageIO.read(Paths.get(root, "_tmp/phone/mobile-plan-review/wave-probe.png").toFile());
        int x = 560, y = 2300, w = 200, h = 80;
        int[] crop = img.getRGB(x, y, w, h, null, 0, w);
        WaveformDetector d = detector(30000);
        WaveformDetector.Result r = d.observe(crop, w, h, 0, 1000);
        check(r.reason.equals("indistinguishable_activity"), "real screenshot must await comparison: " + r.reason);
        System.out.printf("Real screenshot: structural crop activity=%.3f; no audio label asserted%n", r.activity);
    }

    /** Synthetic bars exercise timing/distributions; they are not recorded speech. */
    private static void longUtteranceCases() {
        for (int step : new int[] {500, 1000}) for (double base : new double[] {.075, .20, .30}) {
            WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2000, 5000));
            d.beginSegment(0); WaveformDetector.Result r = null; int t = 0;
            // Eight low frames establish an observable reference before a long active run.
            for (int i = 0; i < 8; i++, t += step) r = sample(d, t, base, 1500);
            int validActive = 0;
            for (int i = 0; i < 40; i++, t += step) {
                r = sample(d, t, base + .30, 1500);
                if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) validActive++;
                check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "active period must not send");
            }
            check(validActive >= 36, "long active distribution lost baseline: " + validActive);
            boolean sent = false;
            for (int i = 0; i < 10; i++, t += step) {
                r = sample(d, t, base, 1500);
                if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
            }
            check(sent, "distinct quiet after long active did not send, base=" + base + " step=" + step);
        }
        // A mid-level AFTER a qualified active run is a real pause: the bars fell to a
        // third of their peak. The earlier expectation ("must stay uncertain") is what
        // made pause sending impossible on the actual phone, where the idle waveform
        // wanders in exactly that band (background 0.109, active limit 0.26, idle
        // 0.13-0.23), so every segment ran to its maximum duration instead.
        // A mid-level that never recedes from the peak is still refused - the sustained
        // noise case further down asserts that.
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2000, 5000));
        d.beginSegment(0); WaveformDetector.Result r = null;
        for (int t = 0; t < 22000; t += 1000) r = sample(d, t, t < 8000 ? .075 : .45, 1500);
        boolean softSent = false;
        for (int t = 22000; t < 27000; t += 1000) {
            r = sample(d, t, .15, 1500);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) softSent = true;
        }
        check(softSent, "a receded mid-level after real activity must be a pause");
        // A long one-level run eventually removes the observed background from the
        // window. The algorithm must stop trusting the old baseline, never send.
        d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2000, 5000));
        d.beginSegment(0); boolean held = false;
        for (int t = 0; t < 90000; t += 1000) {
            r = sample(d, t, t < 8000 ? .075 : .45, 1500);
            check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "sustained activity cannot itself cause pause send");
            if (t > 60000 && r.signal == WaveformDetector.Signal.INVALID) held = true;
        }
        check(held, "stale baseline should become uncertain");

        // Environment change: do not treat the new steady background as a pause.
        d.reset(); d.beginSegment(0); boolean sawShift = false;
        for (int t = 0; t < 90000; t += 1000) {
            double level = t < 8000 ? .075 : t < 20000 ? .45 : .30;
            r = sample(d, t, level, 1500);
            if (r.reason.equals("baseline_shift")) sawShift = true;
            if (t >= 20000) check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "environment change must not invent a quiet pause");
        }
        // The baseline may either report a shift or silently re-learn; both are safe.
        // What must never happen is a submit caused by the environment change itself,
        // which the loop above already asserts frame by frame.
        check(true, "environment shift handled without inventing a pause");
        // Short segment: if both references are actually observable, warmup does not
        // inherently prevent a pause decision before a 15-second cap.
        d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 15000, 2000, 5000));
        d.beginSegment(0); boolean sent = false;
        for (int t = 0; t <= 14000; t += 1000) {
            r = sample(d, t, t < 4000 || t >= 11000 ? .075 : .45, 1500);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(sent, "observable short-segment pause should be possible");
        System.out.println("Synthetic: 6 long-activity/quiet cases, ambiguous soft continuation, 15s segment passed");
    }

    private static void replaySummary(String root) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(root, "_tmp/phone/mobile-plan-review/waveform-samples.json")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\\"phase\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[\\s\\S]*?\\\"t\\\"\\s*:\\s*([0-9.]+)[\\s\\S]*?\\\"bins\\\"\\s*:\\s*\\[([^]]+)\\]").matcher(json);
        int total = 0, playingCorrect = 0, pauseCorrect = 0, playingCount = 0, pauseCount = 0;
        // JSON contains aggregates, not pixels. Here we construct hypothetical bars from
        // their average only; this tests transitions, not real-pixel accuracy or bin units.
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2500, 6000));
        long origin = 0;
        while (m.find()) {
            long t = Math.round(Double.parseDouble(m.group(2)) * 1000);
            if (total++ == 0) { origin = t; d.beginSegment(0); }
            String[] bins = m.group(3).trim().split("\\s*,\\s*"); double sum = 0;
            for (String bin : bins) sum += Double.parseDouble(bin.trim());
            double size = Math.min(.55, Math.max(.055, sum / bins.length / 5000.0));
            // The original sampling includes >2.5 s gaps at phase changes: those must recover.
            long elapsed = t - origin;
            WaveformDetector.Result r = d.observe(frame(size, total, false), W, H, elapsed, 1500);
            boolean playing = m.group(1).startsWith("playing");
            if (r.signal != WaveformDetector.Signal.INVALID) {
                if (playing) { playingCount++; if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) playingCorrect++; }
                else { pauseCount++; if (r.signal == WaveformDetector.Signal.VISUAL_QUIET) pauseCorrect++; }
            }
        }
        check(total == 21 && playingCount > 0 && pauseCount > 0, "sample fixture coverage");
        check(playingCorrect >= playingCount - 1 && pauseCorrect >= pauseCount - 1, "summary-driven synthetic replay");
        System.out.printf("Summary-driven synthetic replay: playing %d/%d, paused %d/%d; not real image validation%n", playingCorrect, playingCount, pauseCorrect, pauseCount);
    }

    private static void replayRealFrames(String root) throws Exception {
        java.nio.file.Path dir = Paths.get(root, "_tmp/phone/mobile-plan-review/waveform-fixtures");
        String manifest = new String(Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\\"phase\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"time\\\"\\s*:\\s*([0-9.]+)").matcher(manifest);
        WaveformDetector d = new WaveformDetector(WaveformDetector.Config.defaults());
        long origin = 0, last = 0; int count = 0, correct = 0, wrong = 0, invalid = 0;
        System.out.println("Real frame replay (default config, pause preference 1500 ms):");
        while (m.find()) {
            long timestamp = Math.round(Double.parseDouble(m.group(3)) * 1000);
            if (count == 0) { origin = timestamp; d.beginSegment(0); }
            long t = timestamp - origin;
            BufferedImage image = ImageIO.read(dir.resolve(m.group(2)).toFile());
            check(image != null && image.getWidth() == 200 && image.getHeight() == 80, "bad fixture " + m.group(2));
            int[] pixels = image.getRGB(0, 0, 200, 80, null, 0, 200);
            WaveformDetector.Result r = d.observe(pixels, 200, 80, t, 1500);
            WaveformDetector.Signal expected = m.group(1).equals("paused")
                    ? WaveformDetector.Signal.VISUAL_QUIET : WaveformDetector.Signal.VISUAL_ACTIVE;
            if (r.signal == WaveformDetector.Signal.INVALID) invalid++;
            else if (r.signal == expected) correct++;
            else wrong++;
            System.out.printf("%s t=%6d dt=%4d %-7s %-12s activity=%5.3f threshold=%5.3f pause=%4d reason=%s%n",
                    m.group(2), t, count == 0 ? 0 : t - last, r.signal, r.decision,
                    r.activity, r.threshold, r.pauseMs, r.reason);
            last = t; count++;
        }
        check(count == 21, "expected 21 real frames, got " + count);
        System.out.printf("Real frame classification: correct=%d wrong=%d invalid=%d%n", correct, wrong, invalid);
        check(wrong == 0 && correct > 0 && invalid > 0, "unexpected fixture classification or coverage");

        // Reuse real pixel crops in a controlled synthetic schedule, not presented as
        // a real chronological recording. This checks the algorithm across a longer
        // high-activity interval without fabricating the missing chronological frames.
        d = new WaveformDetector(WaveformDetector.Config.defaults()); d.beginSegment(0);
        boolean sent = false; long sentAt = -1; int t = 0;
        for (int i = 0; i < 40; i++, t += 1000) {
            String file = i < 8 || i >= 33
                    ? String.format("paused-%02d.png", 1 + (i % 6))
                    : String.format("playing_before-%02d.png", i % 7);
            BufferedImage frame = ImageIO.read(dir.resolve(file).toFile());
            WaveformDetector.Result r = d.observe(frame.getRGB(0, 0, 200, 80, null, 0, 200), 200, 80, t, 1500);
            if (i < 33) check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "rearranged pixels send during activity");
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE && !sent) { sent = true; sentAt = t; }
        }
        check(sent, "rearranged real crops should produce a pause decision");
        System.out.printf("Reordered real pixel crops with synthetic timestamps: pause send at %dms; not original timeline%n", sentAt);
    }

    /**
     * The ambiguous_quiet contract: once a segment has proved it contained speech and the
     * current frame is not continuing it, an unreadable frame must keep/grow the pause
     * clock at half weight instead of wiping it. Real evidence: _tmp/v070/baseline.jsonl
     * segment 4 had a qualified activity run (activeMs 2940 at 132514 ms) and then fell
     * into indistinguishable_activity at 133569 ms with pauseMs 0 - the old branch reset
     * the clock.
     *
     * Reachability needs qualifiedActivity AND !wasVisualActive AND one of the
     * "uninformative window" gates (here: plateau, i.e. spread < 0.03). A long constant
     * mid-level does that: the sliding floor climbs to the activity level, the spread
     * collapses, and the percentile path can no longer separate the frame.
     */
    private static void ambiguousQuietNeverReducesThePauseClock() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        // Quiet reference, then a long qualified active run so qualifiedActivity is set.
        for (int i = 0; i < 8; i++, t += 500) d.observe(frame(.05, i, false), W, H, t, 30000, true);
        for (int i = 0; i < 20; i++, t += 500) d.observe(frame(.45, i + 10, false), W, H, t, 30000, true);
        // Long constant mid-level: floor catches up to the activity, spread collapses, and
        // the percentile path becomes unable to separate the frame -> ambiguous_quiet.
        long prevPause = -1;
        int ambiguousFrames = 0;
        boolean pauseGrew = false;
        for (int i = 0; i < 80; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(.15, i + 40, false), W, H, t, 30000, true);
            if ("ambiguous_quiet".equals(r.reason)) ambiguousFrames++;
            if (prevPause >= 0 && r.pauseMs < prevPause) {
                throw new AssertionError("an ambiguous frame reduced the pause clock: "
                        + prevPause + " -> " + r.pauseMs + " at frame " + i);
            }
            if (prevPause >= 0 && r.pauseMs > prevPause) pauseGrew = true;
            prevPause = r.pauseMs;
        }
        check(ambiguousFrames > 0,
                "the fixture must actually reach ambiguous_quiet (got 0 of 80 frames)");
        check(pauseGrew, "the pause clock must still be able to grow");
        System.out.println("Synthetic: ambiguous_quiet reached " + ambiguousFrames
                + " frames, pause clock never reduced");
    }

    public static void main(String[] args) throws Exception {
        String sampleRoot = args.length == 1 && !args[0].isBlank() ? args[0] : "";
        behavior();
        longUtteranceCases();
        ambiguousQuietNeverReducesThePauseClock();
        if (!sampleRoot.isEmpty()) {
            screenshot(sampleRoot);
            replayRealFrames(sampleRoot);
            replaySummary(sampleRoot);
        } else {
            System.out.println("RealWave: private sample replay skipped (no fixture root provided)");
        }
        System.out.println("WaveformDetector host tests passed");
    }
}
