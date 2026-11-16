package org.gptdictation.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects activity samples for a fixed window and turns them into an {@link EnvProfile.Summary}.
 *
 * Used for two things:
 *  - automatic learning: the first ten seconds of every run, without asking the user;
 *  - manual learning: the "学习当前环境" button in the settings screen.
 *
 * Pure Java so the statistics and the classification are host-testable.
 */
public final class EnvLearnSession {
    /** Ten seconds: long enough to see a pause in speech, short enough to stay unobtrusive. */
    public static final long DEFAULT_WINDOW_MS = 10000;
    /** Below this the window is not worth storing. */
    private static final int MIN_SAMPLES = 8;

    private final long windowMs;
    private final List<Double> frames = new ArrayList<>();
    /**
     * The tall-bar share of each frame, when the detector could measure it.
     *
     * This is the reading that actually tracks loudness on the device. Learning its
     * distribution here is what lets the app place its own speech bar instead of relying
     * on a constant: the same value means "music only" in one room and "somebody talking"
     * in another, so only the room's own percentiles can separate them.
     */
    private final List<Double> shares = new ArrayList<>();
    private long start = -1;
    private long last = -1;

    public EnvLearnSession() { this(DEFAULT_WINDOW_MS); }

    public EnvLearnSession(long windowMs) {
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs");
        this.windowMs = windowMs;
    }

    public void observe(double activity, long now) {
        if (Double.isNaN(activity) || activity < 0) return;
        if (start < 0) start = now;
        last = now;
        frames.add(Math.min(1.0, activity));
    }

    /**
     * Record the tall-bar share for the same frame. Optional: callers that only have the
     * old activity reading can skip it and the summary simply carries no share data.
     */
    public void observeShare(double share) {
        if (Double.isNaN(share) || share < 0 || share > 1) return;
        shares.add(share);
    }

    public boolean started() { return start >= 0; }

    /** 0..1 for the progress hint on the settings screen. */
    public double progress(long now) {
        if (start < 0) return 0;
        return Math.max(0, Math.min(1, (double) (now - start) / windowMs));
    }

    public boolean ready(long now) {
        return start >= 0 && now - start >= windowMs && frames.size() >= MIN_SAMPLES;
    }

    public int samples() { return frames.size(); }

    /** Final statistics. Returns null when there is not enough evidence to store anything. */
    public Result finish(long now) {
        if (frames.size() < MIN_SAMPLES) return null;
        List<Double> sorted = new ArrayList<>(frames);
        sorted.sort(Double::compare);
        double p10 = percentile(sorted, 0.10);
        double p50 = percentile(sorted, 0.50);
        double p90 = percentile(sorted, 0.90);

        // "Never looks idle" is measured against the window's own median, not an absolute
        // level: a quiet room and a loud room are both steady, but only the loud one has a
        // high median. A frame counts as idle when it falls well below that median.
        double idleCut = Math.max(0.02, p50 * 0.6);
        int idle = 0;
        for (double v : frames) if (v <= idleCut) idle++;
        double quietFraction = (double) idle / frames.size();

        double sum = 0;
        for (int i = 1; i < frames.size(); i++) sum += Math.abs(frames.get(i) - frames.get(i - 1));
        double modulation = frames.size() > 1 ? sum / (frames.size() - 1) / Math.max(0.05, p50) : 0;

        EnvProfile.Env env = EnvProfile.classify(p50, quietFraction, modulation);
        long paused = last >= 0 && start >= 0 ? last - start : windowMs;
        /*
         * Learn the tall-bar share distribution too. The low percentile is what this room
         * looks like with only its background (music, air conditioning, distant talk) and
         * the high percentile is what it looks like with a voice over that background.
         * Storing both lets the next run start with a bar already placed for this room
         * instead of guessing, which is the point of the environment library.
         */
        double shareLow = Double.NaN, shareHigh = Double.NaN, shareBar = Double.NaN;
        if (shares.size() >= MIN_SAMPLES) {
            List<Double> ss = new ArrayList<>(shares);
            ss.sort(Double::compare);
            shareLow = percentile(ss, 0.20);
            shareHigh = percentile(ss, 0.85);
            shareBar = percentile(ss, 0.40);
        }
        return new Result(env, p10, p50, p90, quietFraction, modulation, frames.size(), paused,
                shareLow, shareHigh, shareBar);
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.round((sorted.size() - 1) * q);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /** Classify an arbitrary sample window; used for the mid-run environment check too. */
    public static EnvProfile.Env classify(List<Double> samples) {
        if (samples == null || samples.size() < 4) return null;
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compare);
        double p50 = percentile(sorted, 0.50);
        double idleCut = Math.max(0.02, p50 * 0.6);
        int idle = 0;
        for (double v : samples) if (v <= idleCut) idle++;
        double quietFraction = (double) idle / samples.size();
        double sum = 0;
        for (int i = 1; i < samples.size(); i++) sum += Math.abs(samples.get(i) - samples.get(i - 1));
        double modulation = sum / (samples.size() - 1) / Math.max(0.05, p50);
        return EnvProfile.classify(p50, quietFraction, modulation);
    }

    /** What one learning window observed. */
    public static final class Result {
        public final EnvProfile.Env env;
        /** Low percentile: what the room looks like with nobody talking. */
        public final double quiet;
        /** Median. */
        public final double level;
        /** High percentile: what it looks like while somebody talks. */
        public final double speech;
        public final double quietFraction;
        public final double modulation;
        public final int frames;
        public final long spanMs;
        /** Tall-bar share with only the room's background; NaN when not measured. */
        public final double shareLow;
        /** Tall-bar share with a voice over that background; NaN when not measured. */
        public final double shareHigh;
        /** Suggested speech bar = the valley between those two populations. */
        public final double shareBar;

        Result(EnvProfile.Env env, double quiet, double level, double speech,
               double quietFraction, double modulation, int frames, long spanMs,
               double shareLow, double shareHigh, double shareBar) {
            this.env = env;
            this.quiet = quiet;
            this.level = level;
            this.speech = speech;
            this.quietFraction = quietFraction;
            this.modulation = modulation;
            this.frames = frames;
            this.spanMs = spanMs;
            this.shareLow = shareLow;
            this.shareHigh = shareHigh;
            this.shareBar = shareBar;
        }

        public EnvProfile.Summary toSummary(long now) {
            return new EnvProfile.Summary(env, quiet, speech, 0, frames, now, 1.0, shareBar);
        }

        public String describe() { return EnvProfile.describe(env, level, quietFraction); }
    }
}
