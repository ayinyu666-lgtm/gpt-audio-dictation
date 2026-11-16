package org.gptdictation.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-only tests for the cross-run environment learning.
 *
 * The point of the whole feature: one run measures, the next run starts from those numbers
 * instead of measuring again. These tests pin that down without a device.
 */
public final class EnvLearningTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static EnvProfile.Summary summary(EnvProfile.Env env, double quiet, double speech, long pause, int frames) {
        return new EnvProfile.Summary(env, quiet, speech, pause, frames, 1000L, 1.0);
    }

    private static void serialisationRoundTrip() {
        List<EnvProfile.Summary> list = new ArrayList<>();
        list.add(summary(EnvProfile.Env.QUIET, 0.11, 0.45, 1600, 40));
        list.add(summary(EnvProfile.Env.BGM_MEDIUM, 0.42, 0.56, 0, 35));
        String text = EnvProfile.encode(list);
        List<EnvProfile.Summary> back = EnvProfile.decode(text);
        check(back.size() == 2, "round trip keeps both entries");
        check(back.get(0).env == EnvProfile.Env.QUIET, "env survives");
        check(Math.abs(back.get(0).quiet - 0.11) < 1e-6, "quiet survives");
        check(back.get(1).pauseMs == 0, "pause survives");
        check(EnvProfile.decode("").isEmpty(), "empty text decodes to nothing");
        check(EnvProfile.decode("garbage").isEmpty(), "garbage is dropped, not thrown");
    }

    private static void aggregationPrefersTheNewest() {
        List<EnvProfile.Summary> list = new ArrayList<>();
        // older runs measured a quieter room
        list.add(new EnvProfile.Summary(EnvProfile.Env.NORMAL, 0.10, 0.40, 1500, 40, 1000L, 0.2));
        list.add(new EnvProfile.Summary(EnvProfile.Env.NORMAL, 0.12, 0.42, 1500, 40, 2000L, 0.2));
        // the newest runs measured a louder one
        list.add(new EnvProfile.Summary(EnvProfile.Env.NORMAL, 0.30, 0.62, 1500, 40, 3000L, 1.0));
        list.add(new EnvProfile.Summary(EnvProfile.Env.NORMAL, 0.32, 0.64, 1500, 40, 4000L, 1.0));
        double quiet = EnvProfile.weightedMedian(list, false);
        double speech = EnvProfile.weightedMedian(list, true);
        check(quiet > 0.20, "recent louder runs must dominate the median, got " + quiet);
        check(speech > 0.55, "speech median follows the recent runs, got " + speech);
        check(EnvProfile.weightedPauseMs(list) == 1500, "pause median");
    }

    private static void keepsOnlyTheNewestFive() {
        List<EnvProfile.Summary> list = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            list = EnvProfile.add(list, summary(EnvProfile.Env.QUIET, 0.1 + i * 0.01, 0.5, 0, 40));
        }
        check(list.size() == EnvProfile.MAX_PER_ENV, "capped at " + EnvProfile.MAX_PER_ENV);
        check(list.get(list.size() - 1).quiet > list.get(0).quiet, "oldest dropped first");
        // Too few frames is not evidence and must not enter the store.
        List<EnvProfile.Summary> none = EnvProfile.add(new ArrayList<>(), summary(EnvProfile.Env.QUIET, 0.1, 0.5, 0, 3));
        check(none.isEmpty(), "a three-frame run is not stored");
    }

    private static void classificationCoversEveryEnvironment() {
        check(EnvProfile.classify(0.12, 0.7, 0.05) == EnvProfile.Env.QUIET, "quiet room");
        check(EnvProfile.classify(0.25, 0.4, 0.10) == EnvProfile.Env.NORMAL, "ordinary room");
        check(EnvProfile.classify(0.45, 0.30, 0.35) == EnvProfile.Env.NOISY, "people talking");
        // Music: steady and never idle. The level only picks the volume tier.
        check(EnvProfile.classify(0.28, 0.05, 0.05) == EnvProfile.Env.BGM_LIGHT, "quiet music");
        check(EnvProfile.classify(0.42, 0.04, 0.06) == EnvProfile.Env.BGM_MEDIUM, "medium music");
        check(EnvProfile.classify(0.60, 0.03, 0.05) == EnvProfile.Env.BGM_HEAVY, "loud music");
    }

    private static void learningWindowProducesAUsableSummary() {
        EnvLearnSession session = new EnvLearnSession(1000);
        // ten frames of a quiet room at half a second apart
        for (int i = 0; i < 12; i++) session.observe(0.12 + (i % 3) * 0.01, i * 500L);
        check(!session.ready(500), "not ready before the window elapses");
        check(session.ready(12000), "ready after ten seconds");
        EnvLearnSession.Result r = session.finish(12000);
        check(r != null, "summary produced");
        check(r.env == EnvProfile.Env.QUIET, "classified as quiet, got " + r.env);
        check(r.frames == 12, "frame count");

        EnvLearnSession music = new EnvLearnSession(1000);
        // steady high level, never drops: background music
        for (int i = 0; i < 20; i++) music.observe(0.46 + (i % 2) * 0.005, i * 500L);
        EnvLearnSession.Result m = music.finish(20000);
        check(m.env.isBgm(), "steady high level is music, got " + m.env);
    }

    /** The whole reason for the feature: the second run must judge from the first frame. */
    private static void priorMakesTheFirstFrameUsable() {
        int w = 120, h = 80;
        WaveformDetector cold = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 15000, 2500, 5000));
        cold.beginSegment(0);
        WaveformDetector.Result firstCold = cold.observe(frame(w, h, 0.45, 1), w, h, 0, 1500);
        check(firstCold.signal == WaveformDetector.Signal.INVALID,
                "without a stored profile the first frame cannot be judged yet");

        WaveformDetector seeded = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 15000, 2500, 5000));
        seeded.beginSegment(0);
        seeded.seedPrior(0.11, 0.45);
        check(seeded.hasPrior(), "prior accepted");
        WaveformDetector.Result firstSeeded = seeded.observe(frame(w, h, 0.45, 1), w, h, 0, 1500);
        check(firstSeeded.signal != WaveformDetector.Signal.INVALID,
                "with a stored profile the first frame is already judged, got " + firstSeeded.reason);

        // A nonsense profile must be refused rather than producing garbage thresholds.
        WaveformDetector rejects = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 15000, 2500, 5000));
        rejects.beginSegment(0);
        rejects.seedPrior(0.5, 0.4);   // speech below background
        check(!rejects.hasPrior(), "inverted profile rejected");
    }

    /** Speech followed by silence must submit, starting from a stored profile. */
    private static void seededDetectorStillSubmitsOnPause() {
        int w = 120, h = 80;
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1500, 3000, 15000, 2500, 5000));
        d.beginSegment(0);
        d.seedPrior(0.11, 0.45);
        boolean sent = false;
        long t = 0;
        for (int i = 0; i < 10; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(w, h, 0.50, i), w, h, t, 1500);
            check(r.decision != WaveformDetector.Decision.SEND_PAUSE, "must not submit while talking");
        }
        for (int i = 0; i < 14; i++, t += 500) {
            WaveformDetector.Result r = d.observe(frame(w, h, 0.11, 100 + i), w, h, t, 1500);
            if (r.decision == WaveformDetector.Decision.SEND_PAUSE) { sent = true; break; }
        }
        check(sent, "a pause after speech must submit when started from a stored profile");
    }

    private static int[] frame(int w, int h, double size, int tick) {
        int[] p = new int[w * h];
        java.util.Arrays.fill(p, 0xff2d2d2d);
        int bar = Math.max(1, (int) Math.round(size * h));
        for (int x = 0; x < w; x++) {
            if (x % 8 >= 5) continue;
            int mid = h / 2;
            for (int y = Math.max(0, mid - bar / 2); y < Math.min(h, mid + bar / 2); y++) p[y * w + x] = 0xff9b9b9b;
        }
        p[(tick % 5) * w + (tick % w)] = 0xff9b9b9b;
        return p;
    }

    public static void main(String[] args) {
        serialisationRoundTrip();
        aggregationPrefersTheNewest();
        keepsOnlyTheNewestFive();
        classificationCoversEveryEnvironment();
        learningWindowProducesAUsableSummary();
        priorMakesTheFirstFrameUsable();
        seededDetectorStillSubmitsOnPause();
        System.out.println("EnvLearning: storage, aggregation, classification, seeding and pause submit passed");
    }
}
