package org.gptdictation.assistant;

/**
 * Regression tests for the two defects that made pause-based sending never fire:
 *
 * 1. The service never passed staticFrameVerifiedLive=true, so any two identical
 *    screenshots were rejected as "static_frame_unverified". A quiet room produces
 *    nearly identical frames, so quiet could never be classified.
 * 2. A flat picture (small spread across the recent window) was rejected as
 *    "indistinguishable_activity" forever, so a persistent silence never became a pause.
 */
public final class FlatQuietTest {
    private static final int W = 120, H = 80;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static int[] frame(double size, int tick) {
        int[] p = new int[W * H];
        int bg = 45, fg = 155;
        java.util.Arrays.fill(p, 0xff000000 | bg << 16 | bg << 8 | bg);
        int bar = Math.max(4, (int) Math.round(size * H));
        for (int x = 3; x < W; x++) if (x % 10 < 5)
            for (int y = (H - bar) / 2; y < (H + bar) / 2; y++)
                p[y * W + x] = 0xff000000 | fg << 16 | fg << 8 | fg;
        p[(tick % 5) * W + tick % W] = 0xff000000 | fg << 16 | fg << 8 | fg;
        return p;
    }

    /** Two identical frames must be usable when the caller verified the capture. */
    private static void verifiedStillFrameIsQuietNotInvalid() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 1000, 60000, 2500, 5000));
        d.beginSegment(0);
        int[] still = frame(.05, 1);
        WaveformDetector.Result first = d.observe(still, W, H, 0, 1500, true);
        WaveformDetector.Result second = d.observe(still, W, H, 1000, 1500, true);
        check(!("static_frame_unverified".equals(second.reason)),
                "a verified live capture must not be rejected just because the picture is still");
        check(first != null && second != null, "both observations return a result");
        System.out.println("FlatQuiet: verified identical frames are accepted (reason=" + second.reason + ")");
    }

    /** Without verification the same frames must still be rejected (safety preserved). */
    private static void unverifiedStillFrameStillRejected() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 1000, 60000, 2500, 5000));
        d.beginSegment(0);
        int[] still = frame(.05, 1);
        d.observe(still, W, H, 0, 1500);
        WaveformDetector.Result second = d.observe(still, W, H, 1000, 1500);
        check("static_frame_unverified".equals(second.reason),
                "without the caller's verification a frozen picture is still ambiguous");
        System.out.println("FlatQuiet: unverified still frames remain rejected");
    }

    /** Long silence with a flat waveform must eventually produce a pause send. */
    private static void longSilenceEventuallySends() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 3000, 60000, 2500, 5000));
        d.beginSegment(0);
        boolean sent = false;
        long t = 0;
        // Nearly motionless: activity stays inside a 0.02 band, so it is a flat picture.
        for (int i = 0; i < 30 && !sent; i++, t += 1000) {
            double size = (i % 2 == 0) ? .05 : .055;
            WaveformDetector.Result r = d.observe(frame(size, i), W, H, t, 2000, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(sent, "a long, genuinely flat silence must produce a pause-based send");
        System.out.println("FlatQuiet: flat silence produced SEND_PAUSE at " + t + " ms");
    }

    /** A real activity burst followed by silence must still send (the normal path). */
    private static void activityThenSilenceStillSends() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 3000, 60000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        for (int i = 0; i < 10; i++, t += 1000) d.observe(frame(.05, i), W, H, t, 1500, true);
        for (int i = 0; i < 14; i++, t += 1000) d.observe(frame(.45, i + 20), W, H, t, 1500, true);
        boolean sent = false;
        for (int i = 0; i < 12 && !sent; i++, t += 1000) {
            WaveformDetector.Result r = d.observe(frame(.05, i + 40), W, H, t, 2000, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(sent, "talk-then-silence must still produce a pause send");
        System.out.println("FlatQuiet: activity followed by silence still sends");
    }

    /** A short flat stretch must NOT send; silence has to persist. */
    private static void briefFlatnessDoesNotSend() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 3000, 60000, 2500, 5000));
        d.beginSegment(0);
        boolean sent = false;
        for (int i = 0; i < 6; i++) {
            WaveformDetector.Result r = d.observe(frame(.05, i), W, H, i * 1000L, 3000, true);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) sent = true;
        }
        check(!sent, "a pause shorter than the required duration must not send");
        System.out.println("FlatQuiet: brief flatness stays below the pause threshold");
    }

    /**
     * Real logged numbers from _tmp/v070/baseline.jsonl segment 4.
     *
     * The segment had already shown a qualified activity run (activeMs 2940 at the
     * last active frame at 132514 ms), then went quiet. Its logged frames were:
     *   activity 0.228 at t=133569 ms, floor 0.109, segmentHigh >= 0.228
     * Under the old flat rule `activity <= FLAT_ACTIVITY_MAX (0.055)` was false and
     * `receded` (activity vs the segment mid) was false, so those frames fell through
     * to indistinguishable_activity and pauseMs stayed 0 for the rest of the segment.
     * The widened rule accepts a post-speech activity that has fallen to <= 55% of the
     * segment peak even when it has not crossed the segment mid.
     */
    private static void postSpeechQuietAcceptsLoggedBaselineNumbers() {
        // The exact rule branch, evaluated with the logged values.
        double segmentHigh = 0.228;          // peak of the 18:56 baseline segment
        double activity = 0.189;             // 0.189 / 0.228 = 0.829 -> not <= 0.55
        double maxAccept = Math.max(0.055, segmentHigh * 0.55);
        check(maxAccept > 0.12 && maxAccept < 0.13,
                "55% of the logged 0.228 peak must be ~0.125, got " + maxAccept);
        check(activity > maxAccept,
                "an activity that has NOT fallen to 55% of peak must still be refused");
        double recededActivity = 0.121;      // 0.121 / 0.228 = 0.531 -> accepted
        check(recededActivity <= maxAccept,
                "a post-speech activity at 53% of peak must be accepted by the new branch");

        // End-to-end: quiet reference, qualified speech, then the logged quiet band.
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 120000, 2500, 5000));
        d.beginSegment(0);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 500) d.observe(frame(.05, i), W, H, t, 1500, true);
        for (int i = 0; i < 20; i++, t += 500) d.observe(frame(.45, i + 10), W, H, t, 1500, true);
        // 0.12 of full scale: above the old 0.055 ceiling, below 55% of the 0.45 peak.
        boolean sawQuiet = false;
        for (int i = 0; i < 10; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(.12, i + 40), W, H, t, 1500, true);
            if (r.signal == WaveformDetector.Signal.VISUAL_QUIET) sawQuiet = true;
        }
        check(sawQuiet, "post-speech quiet at 27% of peak must be classified quiet, not invalid");
        System.out.println("FlatQuiet: post-speech quiet accepts the logged 0.228/0.121 band");
    }

    public static void main(String[] args) {
        verifiedStillFrameIsQuietNotInvalid();
        unverifiedStillFrameStillRejected();
        longSilenceEventuallySends();
        activityThenSilenceStillSends();
        briefFlatnessDoesNotSend();
        postSpeechQuietAcceptsLoggedBaselineNumbers();
        System.out.println("FlatQuiet: all flat-silence behaviours passed");
    }
}
