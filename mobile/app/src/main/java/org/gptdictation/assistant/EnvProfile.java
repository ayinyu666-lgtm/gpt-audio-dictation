package org.gptdictation.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent per-environment statistics for the pause detector.
 *
 * The problem this solves: judging a pause needs two levels (how the room looks when nobody
 * talks, and how it looks while talking), and every sample costs a screenshot. Estimating
 * them from scratch each run meant the detector spent its warm-up refusing to decide, so a
 * fifteen-second segment never produced a verdict. One run's numbers are now enough for the
 * NEXT run to start from: the newest few summaries are averaged per environment.
 *
 * A "summary" is what one run (or one explicit learning session) observed. Keeping several
 * means one unusual run - the corridor, a loud advert - cannot poison the stored values.
 *
 * Pure Java on purpose: all of this is host-testable without a device.
 */
public final class EnvProfile {
    /** How many past runs are averaged per environment. */
    public static final int MAX_PER_ENV = 5;
    /** A summary needs at least this many frames to count as real evidence. */
    public static final int MIN_FRAMES = 20;

    /** Environments the detector knows about. BGM variants are separated by steady level. */
    public enum Env {
        QUIET("安静"), NORMAL("一般"), NOISY("嘈杂"),
        BGM_LIGHT("BGM-轻"), BGM_MEDIUM("BGM-中"), BGM_HEAVY("BGM-重");

        public final String label;
        Env(String label) { this.label = label; }

        public boolean isBgm() { return this == BGM_LIGHT || this == BGM_MEDIUM || this == BGM_HEAVY; }

        public static Env byName(String name) {
            for (Env e : values()) if (e.name().equals(name)) return e;
            return null;
        }
    }

    /** One run's observation of one environment. */
    public static final class Summary {
        public final Env env;
        public final double quiet;      // activity while nobody was talking
        public final double speech;     // activity while somebody was talking
        public final long pauseMs;      // typical pause length that ended a segment
        public final int frames;
        public final long at;           // epoch millis
        public final double weight;     // <1 for runs that were cut short
        /**
         * Tall-bar share that separates this room's background from a voice over it.
         * NaN when this summary predates the measurement or the run never saw it.
         */
        public final double shareBar;

        public Summary(Env env, double quiet, double speech, long pauseMs, int frames, long at, double weight) {
            this(env, quiet, speech, pauseMs, frames, at, weight, Double.NaN);
        }

        public Summary(Env env, double quiet, double speech, long pauseMs, int frames, long at,
                       double weight, double shareBar) {
            if (env == null) throw new IllegalArgumentException("env");
            this.env = env;
            this.quiet = clamp01(quiet);
            this.speech = clamp01(speech);
            this.pauseMs = Math.max(0, pauseMs);
            this.frames = Math.max(0, frames);
            this.at = at;
            this.weight = clamp01(weight);
            this.shareBar = (Double.isNaN(shareBar) || shareBar < 0 || shareBar > 1) ? Double.NaN : shareBar;
        }

        public boolean usable() { return frames >= MIN_FRAMES && speech > quiet; }
        /** True when this summary carries a learned tall-bar bar. */
        public boolean hasShareBar() { return !Double.isNaN(shareBar); }
    }

    private EnvProfile() { }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * Weighted median of the newest summaries. A median rather than a mean so a single odd
     * run cannot drag the starting point; recency weighting so a room that slowly changes is
     * followed instead of being anchored to its first measurement.
     */
    public static double weightedMedian(List<Summary> summaries, boolean speech) {
        if (summaries == null || summaries.isEmpty()) return Double.NaN;
        List<Summary> sorted = new ArrayList<>(summaries);
        sorted.sort((a, b) -> Double.compare(speech ? a.speech : a.quiet, speech ? b.speech : b.quiet));
        double total = 0;
        for (Summary s : sorted) total += s.weight;
        if (total <= 0) return Double.NaN;
        double half = total / 2.0, acc = 0;
        double last = Double.NaN;
        for (Summary s : sorted) {
            acc += s.weight;
            last = speech ? s.speech : s.quiet;
            if (acc >= half) return last;
        }
        return last;
    }

    /** Weighted median pause length, or 0 when nothing was ever measured. */
    public static long weightedPauseMs(List<Summary> summaries) {
        if (summaries == null || summaries.isEmpty()) return 0;
        List<Summary> sorted = new ArrayList<>(summaries);
        sorted.sort((a, b) -> Long.compare(a.pauseMs, b.pauseMs));
        double total = 0;
        for (Summary s : sorted) total += s.weight;
        if (total <= 0) return 0;
        double half = total / 2.0, acc = 0;
        for (Summary s : sorted) {
            acc += s.weight;
            if (acc >= half) return s.pauseMs;
        }
        return sorted.get(sorted.size() - 1).pauseMs;
    }

    /**
     * Weighted median tall-bar share for an environment, or NaN when no stored summary
     * carries one. This is what the detector seeds its speech bar from: it was learned
     * from this room's own frames, so it needs no constant chosen by hand.
     */
    public static double weightedShareBar(List<Summary> summaries) {
        if (summaries == null || summaries.isEmpty()) return Double.NaN;
        List<Summary> valid = new ArrayList<>();
        for (Summary s : summaries) if (s != null && s.hasShareBar()) valid.add(s);
        if (valid.isEmpty()) return Double.NaN;
        valid.sort((a, b) -> Double.compare(a.shareBar, b.shareBar));
        double total = 0;
        for (Summary s : valid) total += s.weight;
        if (total <= 0) return Double.NaN;
        double half = total / 2.0, acc = 0;
        for (Summary s : valid) {
            acc += s.weight;
            if (acc >= half) return s.shareBar;
        }
        return valid.get(valid.size() - 1).shareBar;
    }

    /** Add a summary to an environment's list, keeping only the newest {@link #MAX_PER_ENV}. */
    public static List<Summary> add(List<Summary> existing, Summary fresh) {
        List<Summary> out = new ArrayList<>();
        if (existing != null) out.addAll(existing);
        if (fresh != null && fresh.usable()) out.add(fresh);
        while (out.size() > MAX_PER_ENV) out.remove(0);
        return out;
    }

    // ------------------------------------------------------------------ classification

    /**
     * Classify what the room sounds like, from activity frames only.
     *
     * The decisive question for background music is not "how loud" but "does it ever go
     * quiet". Music that keeps playing never produces a silent picture, so its steady level
     * becomes the baseline and speech is measured as an increment above it. A room with
     * people in it also rarely goes quiet, but its level jumps around far more; keeping the
     * two apart is why the modulation figure is included.
     *
     * @param level        median activity across the window
     * @param quietFraction fraction of frames that looked idle
     * @param modulation   mean absolute frame-to-frame change, normalised by the level
     */
    public static Env classify(double level, double quietFraction, double modulation) {
        double l = clamp01(level), q = clamp01(quietFraction), m = Math.max(0, modulation);
        // Below this the room simply is quiet, whatever else is true.
        if (l < 0.18) return Env.QUIET;
        boolean continuous = q < 0.15;         // it almost never looks idle
        // Jumpy high level is people; steady high level is music. This is the only place the
        // two can be told apart, so modulation is checked before the BGM branch.
        if (m >= 0.22 || l >= 0.40) {
            if (continuous && m < 0.18) return bgmFor(l);
            return Env.NOISY;
        }
        if (continuous) return bgmFor(l);
        return Env.NORMAL;
    }

    private static Env bgmFor(double level) {
        if (level >= 0.50) return Env.BGM_HEAVY;
        if (level >= 0.35) return Env.BGM_MEDIUM;
        return Env.BGM_LIGHT;
    }

    /** Human-readable one-liner for the settings screen. */
    public static String describe(Env env, double level, double quietFraction) {
        if (env == null) return "未识别";
        return String.format(java.util.Locale.US, "%s（电平 %.2f，静默帧 %d%%）",
                env.label, level, Math.round(quietFraction * 100));
    }

    // ------------------------------------------------------------------ serialisation

    /** One summary per line: env,quiet,speech,pause,frames,at,weight[,shareBar] */
    public static String encode(List<Summary> summaries) {
        StringBuilder b = new StringBuilder();
        if (summaries != null) {
            for (Summary s : summaries) {
                if (b.length() > 0) b.append('\n');
                b.append(s.env.name()).append(',')
                        .append(fmt(s.quiet)).append(',')
                        .append(fmt(s.speech)).append(',')
                        .append(s.pauseMs).append(',')
                        .append(s.frames).append(',')
                        .append(s.at).append(',')
                        .append(fmt(s.weight));
                // Only written when measured, so older builds and older lines keep working.
                if (s.hasShareBar()) b.append(',').append(fmt(s.shareBar));
            }
        }
        return b.toString();
    }

    public static List<Summary> decode(String text) {
        List<Summary> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        for (String line : text.split("\n")) {
            String[] p = line.trim().split(",");
            // 7 fields is the original format; 8 adds the learned tall-bar share.
            if (p.length != 7 && p.length != 8) continue;
            Env env = Env.byName(p[0]);
            if (env == null) continue;
            try {
                double shareBar = p.length == 8 ? Double.parseDouble(p[7]) : Double.NaN;
                out.add(new Summary(env, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Long.parseLong(p[3]), Integer.parseInt(p[4]), Long.parseLong(p[5]),
                        Double.parseDouble(p[6]), shareBar));
            } catch (NumberFormatException ignored) {
                // A corrupt line is dropped rather than failing the whole profile.
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }
}
