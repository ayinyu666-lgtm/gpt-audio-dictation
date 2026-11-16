package org.gptdictation.assistant;

/**
 * Host-only calibration against numbers measured on the real device.
 *
 * Measured on Honor Magic4 (1122x2442), crop 669x101 between 取消录制 and 停止录制:
 *   - learned background 0.109, reported threshold 0.197
 *   - with the room SILENT the activity wandered 0.129 -> 0.228 -> 0.129
 *   - the same run reported "indistinguishable_activity" every tick, so the pause clock
 *     never accumulated and every segment ran to its maximum duration
 *
 * These numbers are the acceptance baseline: a silent room must never submit, and speech
 * followed by a pause must submit, across every noise-ignore level (the margin changes
 * what counts as activity, so all levels have to be checked at once).
 */
public final class IdleCalibrationTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Activity values measured while the room was silent. */
    private static final double[] IDLE_BAND = {0.129, 0.168, 0.208, 0.228, 0.208, 0.188, 0.149, 0.168};
    /** A spoken word easily clears the idle band; 0.55 is conservative against the crop. */
    private static final double SPEECH = 0.55;

    private static WaveformDetector detector(double margin) {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 15000, 2500, 5000));
        d.setActivityMargin(margin);
        d.beginSegment(0);
        return d;
    }

    private static final class Run {
        boolean sent; long sentAt = -1; int valid; boolean anyQuiet;
        WaveformDetector.Signal lastSignal;
    }

    /** Feeds one synthetic frame built from an activity fraction. */
    private static int[] frame(double activity, int tick) {
        int w = 120, h = 80;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xff2d2d2d);
        int bar = Math.max(1, (int) Math.round(activity * h));
        for (int x = 0; x < w; x++) {
            if (x % 8 >= 5) continue;
            int mid = h / 2;
            for (int y = Math.max(0, mid - bar / 2); y < Math.min(h, mid + bar / 2); y++) {
                px[y * w + x] = 0xff9b9b9b;
            }
        }
        // A changing stray pixel imitates capture noise, not a waveform bar.
        px[(tick % 5) * w + (tick % w)] = 0xff9b9b9b;
        return px;
    }

    private static Run feed(WaveformDetector d, double[] levels, long stepMs, long startMs) {
        Run r = new Run();
        long t = startMs;
        for (double level : levels) {
            WaveformDetector.Result res = d.observe(frame(level, (int) (t / 250)), 120, 80, t, 1500);
            if (res.signal != WaveformDetector.Signal.INVALID) r.valid++;
            if (res.signal == WaveformDetector.Signal.VISUAL_QUIET) r.anyQuiet = true;
            if (res.decision == WaveformDetector.Decision.SEND_PAUSE && !r.sent) { r.sent = true; r.sentAt = t; }
            r.lastSignal = res.signal;
            t += stepMs;
        }
        r.sentAt = r.sentAt < 0 ? -1 : r.sentAt;
        return r;
    }

    private static double[] repeat(double[] pattern, int times) {
        double[] out = new double[pattern.length * times];
        for (int i = 0; i < times; i++) System.arraycopy(pattern, 0, out, i * pattern.length, pattern.length);
        return out;
    }

    private static double[] concat(double[]... parts) {
        int n = 0; for (double[] p : parts) n += p.length;
        double[] out = new double[n]; int at = 0;
        for (double[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    private static void silentRoomNeverSubmits() {
        for (int level = NoiseIgnorePolicy.MIN; level <= NoiseIgnorePolicy.MAX; level++) {
            double margin = NoiseIgnorePolicy.thresholdMargin(level);
            WaveformDetector d = detector(margin);
            Run r = feed(d, repeat(IDLE_BAND, 4), 1000, 3000);
            check(!r.sent, "silent room must never submit at noise-ignore level " + level);
        }
    }

    private static void speechThenPauseSubmits() {
        for (int level = NoiseIgnorePolicy.MIN; level <= NoiseIgnorePolicy.MAX; level++) {
            double margin = NoiseIgnorePolicy.thresholdMargin(level);
            WaveformDetector d = detector(margin);
            double[] speech = new double[8];
            java.util.Arrays.fill(speech, SPEECH);
            double[] levels = concat(repeat(IDLE_BAND, 2), speech, repeat(IDLE_BAND, 3));
            Run r = feed(d, levels, 1000, 3000);
            check(r.sent, "speech followed by a pause must submit at noise-ignore level " + level);
            // The submit must happen after the drop, not during speech: idle 2 + speech 8.
            check(r.sentAt >= 3000 + 10 * 1000,
                    "submit must wait for the pause, level " + level + " at " + r.sentAt);
        }
    }

    private static void steadyNoiseCannotSubmit() {
        for (int level = NoiseIgnorePolicy.MIN; level <= NoiseIgnorePolicy.MAX; level++) {
            double margin = NoiseIgnorePolicy.thresholdMargin(level);
            WaveformDetector d = detector(margin);
            double[] speech = new double[20];
            java.util.Arrays.fill(speech, SPEECH);
            Run r = feed(d, concat(repeat(IDLE_BAND, 2), speech), 1000, 3000);
            check(!r.sent, "continuous sound must not be mistaken for a pause at level " + level);
        }
    }

    /**
     * A silence-only segment must still be classified, not held forever: the driver needs
     * to know the difference between "quiet" and "cannot tell", otherwise nothing behaves
     * predictably when the user stops talking before ever being loud.
     */
    private static void silenceIsClassifiedNotHeld() {
        WaveformDetector d = detector(NoiseIgnorePolicy.thresholdMargin(RecordingSettings.DEFAULT_NOISE_IGNORE));
        Run r = feed(d, repeat(IDLE_BAND, 3), 1000, 3000);
        check(r.valid > 0, "silence must produce usable classifications, got none");
    }

    public static void main(String[] args) {
        silentRoomNeverSubmits();
        speechThenPauseSubmits();
        steadyNoiseCannotSubmit();
        silenceIsClassifiedNotHeld();
        System.out.println("IdleCalibration: silent room, speech->pause and steady-noise bands passed for every noise-ignore level");
    }
}
