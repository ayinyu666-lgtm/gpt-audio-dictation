package org.gptdictation.assistant;

import java.util.Arrays;

/**
 * Experimental visual activity detector, independent of Android APIs.
 *
 * Input is an opaque ARGB crop of the NEWEST portion of the waveform only, without
 * buttons, cursor, rounded borders or other UI. The caller must verify the recording
 * UI, crop identity and screenshot success independently. A waveform is a visual
 * proxy that cannot reliably distinguish speech from noise, identify its source,
 * or prove the audio received by ChatGPT. The caller must communicate this in UI.
 *
 * Times are monotonic milliseconds. Pixels are row-major, width * height. Call
 * beginSegment after recording is confirmed and reset when UI/crop/route changes.
 * Call observe on failed screenshots too (pixels = null); never reuse old frames.
 * Identical frames are ambiguous: the default overload holds immediately. The
 * opt-in overload accepts identical frames only if the caller independently verifies
 * a fresh capture AND a live, correct recording UI; static pixels alone cannot prove it.
 * The owner must stop acting if no fresh result arrives; this class has no timer.
 * A SEND result is latched until beginSegment, and is only a recommendation.
 */
public final class WaveformDetector {
    public enum Signal { VISUAL_ACTIVE, VISUAL_QUIET, INVALID }
    public enum Decision { WAIT, SEND_PAUSE, HOLD_INVALID }

    public static final class Config {
        /** maxSegmentMs is retained for caller compatibility; the service owns the time limit.
         * frozenMs is retained for compatibility; unverified identical frames hold immediately. */
        public final long minVisualActiveMs, minSegmentMs, maxSegmentMs, maxFrameGapMs, frozenMs;
        public Config(long minVisualActiveMs, long minSegmentMs, long maxSegmentMs,
                      long maxFrameGapMs, long frozenMs) {
            if (minVisualActiveMs < 0 || minSegmentMs < 0 || maxSegmentMs <= 0
                    || minSegmentMs > maxSegmentMs || maxFrameGapMs < 100 || frozenMs < maxFrameGapMs)
                throw new IllegalArgumentException("invalid timing configuration");
            this.minVisualActiveMs = minVisualActiveMs; this.minSegmentMs = minSegmentMs;
            this.maxSegmentMs = maxSegmentMs; this.maxFrameGapMs = maxFrameGapMs;
            this.frozenMs = frozenMs;
        }
        public static Config defaults() { return new Config(1500, 3000, 300000, 2000, 5000); }
    }

    public static final class Result {
        public final Signal signal;
        public final Decision decision;
        public final String reason;
        /** Dimensionless fraction of crop height; not audio amplitude or dB. */
        public final double activity, noiseFloor, threshold, longActivity;
        /** Share of the strip's columns that are genuinely tall; tracks loudness on device. */
        public final double speechShare;
        public final long visualActiveMs, visualActiveRunMs, pauseMs, segmentMs;
        private Result(Signal signal, Decision decision, String reason, double activity,
                       double noiseFloor, double threshold, double longActivity, double speechShare,
                       long visualActiveMs, long visualActiveRunMs, long pauseMs, long segmentMs) {
            this.signal = signal; this.decision = decision; this.reason = reason;
            this.activity = activity; this.noiseFloor = noiseFloor; this.threshold = threshold;
            this.longActivity = longActivity; this.speechShare = speechShare;
            this.visualActiveMs = visualActiveMs; this.visualActiveRunMs = visualActiveRunMs;
            this.pauseMs = pauseMs; this.segmentMs = segmentMs;
        }
    }

    private final Config config;
    private long start = -1, previous = -1, fingerprint;
    private long visualActiveMs, pauseMs, visualActiveRunMs;
    private int recoveryFrames;
    /**
     * Quiet time that has actually been OBSERVED, in milliseconds.
     *
     * This is not the same as {@link #pauseMs}. pauseMs credits the whole gap between two
     * samples, so a 1 s gap between two screenshots counts as 1 s of silence even though
     * only one quiet frame was ever seen. On the real 0.8.0 device log that let a segment
     * be submitted after only two or three quiet samples, sometimes while the user was
     * still speaking (segments 8 and 12 showed activity 0.109-0.129 against a floor of
     * 0.089 the whole time: quiet-looking frames in the middle of speech).
     *
     * This counter answers a different question: "how much verified quiet have we really
     * seen?" It advances by at most the sampling interval for each frame classified quiet,
     * so it can never outrun the number of quiet observations. A pause send requires
     * {@link #MIN_OBSERVED_QUIET_MS} of this, which implements the user's rule: rather
     * stay silent too long than cut the speaker off.
     */
    private long observedQuietMs;
    /**
     * Minimum verified quiet time before a pause may submit.
     *
     * This is a count of OBSERVATIONS, not a noise threshold and not a fixed value for the
     * room's background: it is compared against time that is credited once per quiet frame.
     * Two seconds of assumed sampling (2 x 500 ms) is the smallest amount that cannot be
     * produced by a single lucky frame.
     */
    static final long MIN_OBSERVED_QUIET_MS = 1000;
    private boolean wasVisualActive, hasFrame, latched, qualifiedActivity;
    private Decision latchedDecision;
    private final double[] recent = new double[60];
    private int recentCount, recentNext;
    private long invalidSince = -1;
    private double floor = Double.NaN, shortActivity = Double.NaN, longActivity = Double.NaN;
    /** Lowest / highest activity seen in the current segment; a stable pause reference. */
    private double segmentLow = Double.NaN, segmentHigh = Double.NaN;
    /**
     * Valid frames observed in this segment. The "no separable range" test needs a range
     * estimate, and a range built from one or two frames is not evidence of anything: the
     * very first frame of every segment has segmentLow == segmentHigh == activity by
     * construction. Requiring six frames (the same number the flat-quiet path already uses
     * before trusting a motionless picture) lets the range reflect several samples of the
     * room without delaying the degenerate verdict meaningfully: the 18:51 run had already
     * shown its full 0.079 span by the sixth frame.
     */
    private int segmentFrames;
    /** Valid frames needed before a flat picture is trusted as a pause. */
    private static final int FLAT_FRAMES_REQUIRED = 6;
    /** Peak-to-peak activity range across the recent window that counts as motionless. */
    private static final double FLAT_RANGE = 0.02;
    /** A flat picture must also be low to count as a pause, not a steady loud plateau. */
    private static final double FLAT_ACTIVITY_MAX = 0.055;
    private boolean baselineEstablished;
    /** True once a stored profile supplied the starting levels for this run. */
    private boolean hasPrior;
    /**
     * Hysteresis, copied from the browser extension: a frame already judged as speech
     * only stops being speech once it falls to this fraction of the active limit.
     * Without it, brief dips inside one utterance reset the pause clock every frame and
     * a pause-based send can never accumulate enough continuous quiet.
     */
    private static final double SPEECH_EXIT_FRACTION = 0.70;
    /**
     * A column counts as a "tall bar" when it reaches this fraction of the tallest column
     * in the same crop. Measured on the device: the short idle ticks sit at roughly a
     * third of the loud bars, so 0.62 separates them cleanly without needing a fixed
     * pixel height that would break on another screen size.
     */
    private static final double TALL_OF_PEAK = 0.62;
    /** A tall column must also reach this fraction of the strip's height. */
    private static final double MIN_TALL_ABSOLUTE = 0.45;
    /**
     * Share of the strip's columns that are genuinely tall, 0..1. Independent of
     * `activity`: the percentile height barely moves with loudness on this device while
     * this share changes about 20x (measured 0.0045 quiet vs 0.1256 loud). Used only as a
     * rescue when the percentile path calls the picture indistinguishable, never to
     * replace the existing decision chain.
     */
    private double speechShare = Double.NaN;
    /** How many consecutive frames the percentile path has called indistinguishable. */
    private int indistinguishableFrames;
    /** Consecutive frames of clear tall-bar evidence needed before the rescue engages. */
    private static final int SPEECH_SHARE_FRAMES = 2;
    /**
     * Recent speechShare values, used to place the rescue threshold from the room itself.
     * A fixed cutoff cannot work: with background music playing the measured share never
     * drops below ~0.08, while a quiet room idles at 0.00-0.03. The threshold therefore
     * follows the low end of the last minute the way the browser extension derives its
     * bar from measured percentiles.
     */
    private final double[] shareHist = new double[180];
    private int shareCount, shareNext;
    /** Smoothed bar; NaN until enough history exists. */
    private double shareThreshold = Double.NaN;
    /** Share is speech at or above the bar; it exits speech below this fraction of it. */
    private static final double SHARE_EXIT_FRACTION = 0.70;
    /** Share must also clear this absolute floor, so a silent room cannot qualify. */
    private static final double SPEECH_SHARE_FLOOR = 0.020;
    /** Extra activity margin from the user's noise-ignore level; 0 keeps legacy behaviour. */
    private double activityMargin;

    public WaveformDetector(Config config) {
        if (config == null) throw new IllegalArgumentException("config required");
        this.config = config;
    }

    /** Start only after the target app's recording UI has been independently verified. */
    public void beginSegment(long timestampMs) {
        if (timestampMs < 0) throw new IllegalArgumentException("negative timestamp");
        if (previous >= 0 && timestampMs < previous) throw new IllegalArgumentException("non-monotonic segment time");
        if (previous >= 0 && timestampMs - previous >= 30000) clearBaseline();
        start = timestampMs; previous = -1; hasFrame = false;
        visualActiveMs = pauseMs = visualActiveRunMs = 0; recoveryFrames = 0; wasVisualActive = false;
        observedQuietMs = 0;
        latched = false; latchedDecision = null; qualifiedActivity = false;
        shortActivity = Double.NaN;
        segmentLow = segmentHigh = Double.NaN; segmentFrames = 0;
    }

    /**
     * Drop the in-flight decision when the threshold moves, keeping the learned levels.
     *
     * The activity margin only shifts the bar; the background and the recent distribution are
     * still valid evidence. Clearing them too forced an eight-sample warm-up (eight seconds
     * at one sample per second) every time the margin adapted, which on a 15 s segment could
     * consume the whole segment.
     */
    public void resetDecisionOnly() {
        pauseMs = 0; visualActiveRunMs = 0; wasVisualActive = false;
        observedQuietMs = 0;
        latched = false; latchedDecision = null; qualifiedActivity = false;
        recoveryFrames = 2;
    }

    /** Clear learned visual baseline after a crop, screen, theme or capture route change. */
    public void reset() { start = -1; previous = -1; hasFrame = false; clearBaseline(); }

    /**
     * Extra separation above the learned background required to count as activity.
     * Positive values ignore more steady small noise; it never affects the timed
     * fallback or the pause duration itself.
     */
    public void setActivityMargin(double margin) {
        if (Double.isNaN(margin) || Double.isInfinite(margin)) throw new IllegalArgumentException("margin");
        this.activityMargin = Math.max(-0.05, Math.min(0.10, margin));
    }

    /**
     * Start from previously learned levels instead of measuring them again.
     *
     * This is what makes the second and later runs fast: without it the detector has to see
     * eight samples (eight seconds at one per second) before it can decide anything, and a
     * fifteen-second segment was mostly spent warming up. The comparison window is filled with
     * a plausible distribution - mostly background, a couple of speech-level values - so the
     * very first real frame lands in a window that already has a low and a high reference.
     *
     * Rejected when the values are clearly unusable, in which case the caller keeps the normal
     * cold-start behaviour.
     */
    public void seedPrior(double quietLevel, double speechLevel) {
        if (!(quietLevel > 0) || !(speechLevel > quietLevel) || speechLevel > 1.0) return;
        floor = quietLevel;
        shortActivity = quietLevel;
        longActivity = speechLevel;
        // The window must contain a realistic MIX, not almost only background: the limits are
        // read from its 10th and 90th percentiles, so a window with two speech samples out of
        // sixty reports the speech level as background and every frame stays "unknown".
        // Roughly seven quiet to three talking matches a normal segment of speech.
        int talking = Math.max(2, recent.length * 3 / 10);
        for (int i = 0; i < recent.length; i++) {
            recent[i] = i < recent.length - talking ? quietLevel : speechLevel;
        }
        recentCount = recent.length; recentNext = 0;
        hasPrior = true;
        baselineEstablished = true;
        // The two-fresh-frames requirement exists to distrust a frame right after a gap. A
        // stored profile is exactly the evidence that gap never happened.
        recoveryFrames = 2;
    }

    /** True when this run started from stored levels rather than from nothing. */
    public boolean hasPrior() { return hasPrior; }

    /**
     * Credit verified quiet evidence for one frame classified quiet.
     *
     * The credit is bounded by the measured gap since the previous frame, and clamped to
     * the nominal sampling interval so a long stall cannot be mistaken for a long silence.
     * A quiet frame can therefore never add more evidence than one sample interval.
     */
    private void creditObservedQuiet(long dt) {
        if (dt <= 0) return;
        long credit = Math.min(dt, 1000L);
        observedQuietMs += credit;
    }

    /** True when this segment has seen enough verified quiet to justify a pause submit. */
    private boolean quietEvidenceEnough() {
        return qualifiedActivity && observedQuietMs >= MIN_OBSERVED_QUIET_MS;
    }

    private void clearBaseline() {
        recentCount = recentNext = 0; floor = longActivity = Double.NaN; invalidSince = -1;
        baselineEstablished = false; hasPrior = false;
    }

    /**
     * pauseMsRequired is the user's desired continuous pause in milliseconds, 300..30000.
     * More time means more conservative. Choose the preference externally; it may change
     * between calls. This value is deliberately independent from the visual threshold.
     * Call with null/invalid frame on capture failure; invalid results never trigger
     * pause-based sending. The service independently enforces maximum duration after
     * verifying the recording UI. Times advance only from valid consecutive samples.
     */
    public Result observe(int[] pixels, int width, int height, long timestampMs, long pauseMsRequired) {
        return observe(pixels, width, height, timestampMs, pauseMsRequired, false);
    }

    /**
     * Set staticFrameVerifiedLive only with independent capture freshness AND live
     * recording-UI evidence. It is a caller assertion, not inferred from the pixels.
     * Even a fresh screenshot can contain an internally frozen app animation.
     */
    public Result observe(int[] pixels, int width, int height, long timestampMs,
                          long pauseMsRequired, boolean staticFrameVerifiedLive) {
        if (start < 0) throw new IllegalStateException("beginSegment required");
        if (pauseMsRequired < 300 || pauseMsRequired > 30000)
            throw new IllegalArgumentException("pause preference must be 300..30000 ms");
        if (timestampMs < start || (previous >= 0 && timestampMs <= previous))
            return invalidate("non_monotonic_time", timestampMs, false);
        long dt = previous < 0 ? 0 : timestampMs - previous;
        previous = timestampMs;
        if (dt > config.maxFrameGapMs) {
            if (dt >= 30000) clearBaseline();
            return invalidate("frame_gap", timestampMs, true);
        }
        if (width < 24 || height < 12 || width > 4096 || height > 1024
                || pixels == null || (long) width * height != pixels.length)
            return invalidate("invalid_frame", timestampMs, true);

        // Median luminance removes global background brightness and works in light/dark UI.
        int[] hist = new int[256]; long hash = 0xcbf29ce484222325L;
        for (int c : pixels) {
            if ((c >>> 24) < 250) return invalidate("transparent_frame", timestampMs, true);
            int y = luminance(c); hist[y]++; hash = (hash ^ (c & 0xffffffffL)) * 0x100000001b3L;
        }
        int median = percentile(hist, pixels.length / 2);
        int low = percentile(hist, pixels.length / 50);
        int high = percentile(hist, pixels.length - 1 - pixels.length / 50);
        if (high - low < 18) return invalidate("flat_or_black_frame", timestampMs, true);
        if (hasFrame && hash == fingerprint && !staticFrameVerifiedLive)
            return invalidate("static_frame_unverified", timestampMs, false);
        fingerprint = hash; hasFrame = true;

        // Per-column extent of pixels contrasting with the crop background. Exclude
        // sparse noise and sort columns: the upper quartile retains multiple bars,
        // while isolated specks and one bright cursor do not dominate.
        double[] extents = new double[width]; int occupied = 0;
        int contrast = Math.max(15, Math.min(35, (high - low) / 4));
        for (int x = 0; x < width; x++) {
            int count = 0;
            for (int y = 0; y < height; y++)
                if (Math.abs(luminance(pixels[y * width + x]) - median) >= contrast) count++;
            if (count >= 2) occupied++;
            extents[x] = (double) count / height;
        }
        if (occupied < width / 16 || occupied > width * 9 / 10)
            return invalidate("unexpected_crop", timestampMs, true);
        Arrays.sort(extents);
        double activity = extents[(width * 3) / 4];
        if (activity < 0.025 || activity > 0.92)
            return invalidate("unexpected_waveform", timestampMs, true);
        /*
         * Loudness, measured a second way because the percentile above cannot see it.
         *
         * Measured on a real Honor Magic4 with audio playing, using this same crop:
         *   quiet frame: 3 columns reach 60% of the strip; p75 height 0.208
         *   loud  frame: 59 columns reach 60% of the strip; p75 height 0.228
         * The percentile is nearly identical (which is why the phone read every frame as
         * "indistinguishable"), while the COUNT of genuinely tall columns changes 20x.
         *
         * The wave shape is a row of short idle ticks with the real bars growing out of
         * it, so the useful measure is how much of the row is above that idle floor.
         * This is kept separate from `activity` on purpose: activity drives the existing
         * pause and baseline logic and must keep its old meaning; speechShare is an
         * additional, independent reading of the same picture.
         */
        double peak = extents[extents.length - 1];
        int tall = 0;
        if (peak > 0.04) {
            double tallBar = Math.max(peak * TALL_OF_PEAK, MIN_TALL_ABSOLUTE);
            for (double e : extents) if (e >= tallBar) tall++;
        }
        speechShare = (double) tall / width;
        // Estimate a recent low and high activity distribution, without presuming a
        // constant signal is speech or quiet. No speech identity is inferred here.
        shortActivity = Double.isNaN(shortActivity) ? activity : 0.55 * activity + 0.45 * shortActivity;
        longActivity = Double.isNaN(longActivity) ? activity : 0.04 * activity + 0.96 * longActivity;
        recent[recentNext] = activity; recentNext = (recentNext + 1) % recent.length;
        if (recentCount < recent.length) recentCount++;
        double[] sorted = Arrays.copyOf(recent, recentCount); Arrays.sort(sorted);
        double lowLevel = sorted[(int) Math.floor((recentCount - 1) * 0.10)];
        double highLevel = sorted[(int) Math.floor((recentCount - 1) * 0.90)];
        // Once a distinct baseline was observed, sustained visual activity must not
        // drag it upwards. When the whole recent distribution moves away, however,
        // the old baseline is no longer trustworthy: hold instead of guessing.
        // Follow the background smoothly instead of refusing to work when the room
        // gets louder. A wrong pause submit is harmless: if ChatGPT has no transcript
        // to send, clicking send does nothing. Being unable to submit at all is the
        // real failure, so the baseline tracks the room rather than locking itself out.
        // The background must fall fast and rise slowly. Measured on the device, a
        // symmetric 0.75/0.25 average left the floor stuck near the loud level it was first
        // learned at (0.411 -> 0.325 -> 0.261 -> ...), so for many seconds every frame sat
        // in the grey band between the quiet and active limits and nothing was ever decided.
        // Falling quickly means the first real silence sets the background at once; rising
        // slowly means a passing loud passage cannot drag it up and mute later speech.
        if (Double.isNaN(floor)) floor = lowLevel;
        else if (lowLevel < floor) floor = 0.5 * floor + 0.5 * lowLevel;
        else floor = 0.80 * floor + 0.20 * lowLevel;
        // Track this segment's observed range first: the speech bar below is capped by the
        // loudest frame seen so far, so it must include the current frame before the cap
        // is applied. Using the previous frame here would lag one sample behind on the
        // very peaks the cap exists to follow.
        if (Double.isNaN(segmentLow) || activity < segmentLow) segmentLow = activity;
        if (Double.isNaN(segmentHigh) || activity > segmentHigh) segmentHigh = activity;
        segmentFrames++;
        double spread = highLevel - floor;
        double quietLimit = floor + Math.max(0.025, spread * 0.12);
        double activeLimit = Math.max(0.02, floor + Math.max(0.06, spread * 0.38) + activityMargin);
        /*
         * Cap the bar below the loudest thing this segment has actually seen.
         *
         * Measured on the real device: the bar reached 0.217 while every frame sat at
         * 0.129-0.188, so not one frame in 31 was ever called speech and the pause path
         * could not even start. The sliding background rises in a room where people keep
         * talking, and because activeLimit is derived from it, the bar climbs away from the
         * very signal it is supposed to detect.
         *
         * The browser extension has the same guard (threshold is capped at
         * loud x loudCap); this is its counterpart. Once the segment has seen real peaks,
         * the bar may never sit above LOUD_CAP x that peak, so a drift can no longer
         * outrun the speech it should be catching. Before any peak exists the plain
         * learned limit applies, so a silent room still cannot qualify anything.
         */
        double segmentPeak = Double.isNaN(segmentHigh) ? Double.NaN : segmentHigh;
        if (!Double.isNaN(segmentPeak) && segmentPeak > 0.05) {
            activeLimit = Math.min(activeLimit, Math.max(0.02, segmentPeak * 0.85));
        }
        double threshold = (quietLimit + activeLimit) * 0.5;
        // A pause is the bars coming back DOWN, not a value sitting in a theoretical gap
        // between two learned limits. Measured on the real device: background 0.109,
        // active limit 0.26, while the idle waveform wanders 0.13-0.23 - i.e. exactly in
        // that gap. Treating the whole gap as "unknown" reset the pause clock on every
        // frame after real speech, so a pause-based send could never fire and each segment
        // ran to its maximum duration. Halfway between the observed background and the
        // recent peak is the level a genuine pause falls below, and continuous noise that
        // never recedes stays classified as unknown.
        // Pause reference: the middle of the whole segment's observed range.
        //
        // Two things must hold at once on a real phone. A genuine pause has to be
        // recognised: the room's idle waveform sits around 0.17 while the background is
        // 0.109 and the active limit 0.26, so an absolute "below the quiet limit" test
        // never fires and every segment ran to its maximum duration instead. At the same
        // time, an environment that merely got quieter (loud 0.45 -> steady 0.30) is NOT
        // a pause and must not submit.
        //
        // The segment's own low..high range separates the two: true idle falls clearly
        // below the middle of the range, while a steadier new level sits above it. Using
        // the segment rather than the sliding window also keeps the reference from
        // chasing whatever level the room has settled at.
        // Segment range already updated above; derive the pause reference from it.
        double pauseCut = segmentLow + (segmentHigh - segmentLow) * 0.5;
        boolean receded = activity <= pauseCut;

        // A very flat picture is not "unknown": when the crop has been essentially
        // motionless across the whole recent window, that is the strongest possible
        // evidence of a pause. The old code treated a small spread as "indistinguishable"
        // and held forever, which is why a silent room (video paused, nobody talking)
        // never produced a pause-based send and every segment ran to its maximum.
        boolean flat = spread < 0.10;
        if (flat && recentCount >= FLAT_FRAMES_REQUIRED) {
            double range = sorted[recentCount - 1] - sorted[0];
            // "Motionless" is only a pause if the whole picture is also LOW. A steady
            // high plateau (continuous loud noise) has a small range too; treating it
            // as silence would submit in the middle of a continuous sound.
            // "Motionless" here is the room's idle waveform, which is not blank: measured
            // on the device it sits around 0.17 of full scale. The old 0.055 ceiling only
            // accepted an essentially empty crop, so after every real utterance the
            // motionless idle band was reported as unknown and the segment ran to its
            // maximum instead of submitting on the pause. Once the segment has shown a
            // qualified active run, a motionless picture that has receded below the
            // segment's mid level is a pause. A steady loud plateau does not recede, so it
            // is still refused.
            if (range <= FLAT_RANGE
                    && (activity <= FLAT_ACTIVITY_MAX
                        || (qualifiedActivity && (receded
                            || activity <= Math.max(FLAT_ACTIVITY_MAX, segmentHigh * 0.55))))) {
                // Treat as qualified quiet: clear any pending activity bookkeeping.
                visualActiveRunMs = 0;
                visualActiveMs = 0;
                qualifiedActivity = true;
                pauseMs += (previous >= 0 && dt > 0 && dt <= config.maxFrameGapMs) ? dt : 0;
                creditObservedQuiet(dt);
                wasVisualActive = false;
                baselineEstablished = true;
                Signal flatSignal = Signal.VISUAL_QUIET;
                Decision flatDecision = Decision.WAIT;
                if (latched) flatDecision = latchedDecision;
                else if (timestampMs - start >= config.minSegmentMs && pauseMs >= pauseMsRequired
                        && quietEvidenceEnough())
                    flatDecision = Decision.SEND_PAUSE;
                if (flatDecision == Decision.SEND_PAUSE) { latched = true; latchedDecision = flatDecision; }
                return result(flatSignal, flatDecision, "flat_quiet", activity, threshold, timestampMs);
            }
        }
        boolean midBand = activity > quietLimit && activity < activeLimit;
        // Only an essentially motionless window is uninformative. Measured with a video
        // playing (speech over background music): activity moved 0.564 (talking) -> 0.485
        // (silent), floor 0.467, active limit 0.534 - a usable signal - but spread came out
        // 0.097 against the old 0.10 "flat" threshold, so every frame was discarded as
        // "indistinguishable" and the pause clock never started. A merely narrow window
        // still supports the level comparison below; a plateau really does not, and that is
        // what keeps a never-changing loud background from being read as silence.
        // Two regimes need different evidence:
        //  - low background (a quiet room): the bars are tiny, so a 0.10 swing is mostly
        //    sensor noise. Measured there: floor 0.109, activity wandering 0.129-0.228.
        //    Trusting that would submit in a silent room, so require a wide recent range.
        //  - high background (speech over music, a loud room): the waveform is already
        //    spanning the strip. Measured there: floor 0.467, activity 0.485 (silent) vs
        //    0.564 (talking) - only 0.097 apart, yet a real difference, so the level
        //    comparison is trustworthy without demanding a wide range.
        boolean plateau = spread < 0.03;
        boolean lowBackground = floor < 0.30;
        // ...but only until the segment has proved it contains speech. In a quiet room the
        // spread collapses as soon as the talking stops - exactly when we need to measure a
        // pause - so requiring a wide window forever meant the pause clock was reset on every
        // frame and nothing was ever submitted by pause. qualifiedActivity is only set after
        // a real sustained active run, so a silent room still cannot trigger anything.
        boolean windowTooNarrow = lowBackground && spread < 0.10 && !qualifiedActivity;
        if (recentCount < 8 || plateau || windowTooNarrow || (midBand && !receded)) {
            /*
             * Rescue path for the failure this device actually shows.
             *
             * On the phone the percentile picture never becomes distinguishable: every
             * frame reads ~0.2 no matter how loud the room is, so the pause clock never
             * starts and every segment falls back to the time limit. The tall-bar share
             * (speechShare) does track loudness here -- 0.0045 in a quiet moment against
             * 0.1256 while audio played -- so when the normal path gives up, use it.
             *
             * It is deliberately conservative: it needs two consecutive frames of clear
             * evidence, and it reuses the same speech/quiet bookkeeping (visualActiveMs,
             * pauseMs, qualifiedActivity) so the existing timing rules still decide WHEN
             * a pause is long enough. It only answers "was there sound", which is the one
             * question this device's percentile reading cannot answer.
             */
            if (!Double.isNaN(speechShare)) {
                double bar = updateShareThreshold(speechShare);
                boolean shareVoiced = wasVisualActive
                        ? speechShare >= bar * SHARE_EXIT_FRACTION
                        : speechShare >= bar;
                if (shareVoiced) {
                    if (++indistinguishableFrames >= SPEECH_SHARE_FRAMES) {
                        boolean rescueVoiced = true;
                        visualActiveRunMs = wasVisualActive ? visualActiveRunMs + dt : 0;
                        visualActiveMs += wasVisualActive ? dt : 0;
                        pauseMs = 0;
                        if (visualActiveRunMs >= config.minVisualActiveMs) qualifiedActivity = true;
                        wasVisualActive = rescueVoiced;
                        baselineEstablished = true;
                        return result(Signal.VISUAL_ACTIVE, Decision.WAIT, "speech_share",
                                activity, threshold, timestampMs);
                    }
                } else if (wasVisualActive && indistinguishableFrames >= SPEECH_SHARE_FRAMES) {
                    visualActiveRunMs = 0;
                    pauseMs = pauseMs + dt;
                    wasVisualActive = false;
                    baselineEstablished = true;
                    Decision rdec = Decision.WAIT;
                    long rage = timestampMs - start;
                    if (latched) rdec = latchedDecision;
                    else if (rage >= config.minSegmentMs && qualifiedActivity
                            && pauseMs >= pauseMsRequired) rdec = Decision.SEND_PAUSE;
                    if (rdec == Decision.SEND_PAUSE) { latched = true; latchedDecision = rdec; }
                    return result(Signal.VISUAL_QUIET, rdec, "speech_share_quiet",
                            activity, threshold, timestampMs);
                } else {
                    indistinguishableFrames = 0;
                }
            }
            /*
             * The percentile path cannot tell this frame apart, but that is a statement
             * about the picture, not about the pause clock. On the device a genuine pause
             * had already accumulated 3.7 s when one ambiguous frame arrived and wiped it,
             * so the threshold was never reached. Keep the accumulated quiet time; only a
             * real observation may change it.
             *
             * Once this segment has already proved it contained speech and the current
             * frame is not continuing that speech, an unreadable frame is weak evidence of
             * a pause rather than of nothing at all. Count it at half weight so a genuinely
             * noisy room cannot drive the clock as fast as clean silence, and let the normal
             * latch/required-pause rule decide. This is what lets a real post-speech
             * stretch close instead of running every segment to its maximum duration.
             *
             * The picture must not be the degenerate "background met signal" case: there
             * the whole segment has no separable range, so an unreadable frame is evidence
             * of a loud plateau, not of a pause. Accumulating it would re-open exactly the
             * hole the loud-plateau guard exists to close.
             */
            if (qualifiedActivity && !wasVisualActive
                    && !isBackgroundMetSignal(activity, floor, segmentLow, segmentHigh, segmentFrames)) {
                pauseMs += Math.max(0, dt) / 2;
                creditObservedQuiet(dt);
                Decision adec = Decision.WAIT;
                if (latched) adec = latchedDecision;
                else if (timestampMs - start >= config.minSegmentMs && pauseMs >= pauseMsRequired
                        && quietEvidenceEnough())
                    adec = Decision.SEND_PAUSE;
                if (adec == Decision.SEND_PAUSE) { latched = true; latchedDecision = adec; }
                return result(Signal.VISUAL_QUIET, adec, "ambiguous_quiet",
                        activity, threshold, timestampMs);
            }
            if (invalidSince < 0) invalidSince = timestampMs;
            if (timestampMs - invalidSince >= 30000) clearBaseline();
            return result(Signal.INVALID, Decision.HOLD_INVALID, "indistinguishable_activity", activity, threshold, timestampMs);
        }
        indistinguishableFrames = 0;
        baselineEstablished = true;
        // Hysteresis, same rule as the browser extension: entering speech needs the full
        // active limit, leaving it only needs SPEECH_EXIT_FRACTION of that limit. This is
        // what keeps a short dip inside one sentence from being read as a pause.
        //
        // Only a run that was actually QUALIFIED as speech may use the relaxed exit bar,
        // and the eased bar must stay clearly above the quiet reference this segment has
        // measured. Without the second condition a room whose whole signal sits at a high
        // baseline plus a regular loud passage (0.30 / 0.60) keeps every frame "voiced",
        // no pause ever accumulates, and the segment runs to its time limit.
        //
        // THE DEADLOCK THIS CLOSES (real 0.8.0 device log, 17 segments):
        // the old second term `segmentLow + range * 0.30` collapses whenever the segment
        // never shows a wide range. With segmentLow 0.069 / segmentHigh 0.129 it evaluated
        // to 0.087, while the learned floor was 0.089 and idle activity sat at 0.089. Every
        // silent frame then stayed >= relaxedExit, so `voiced` never turned false, pauseMs
        // stayed 0 and the segment ran to the 30 s timeout.
        //
        // Every bound below is a fraction of a quantity measured in THIS segment. No
        // absolute offset against the noise floor is used: the floor drifts from room to
        // room, so a fixed "+0.02" would work here and break in the next room.
        //
        //  1. `segmentLow + range * 0.30` is the raw shape term; clamp it up to
        //     `quietLimit`, the segment's own quiet reference, so it can never sink below
        //     the level that already counts as background.
        //  2. `activeLimit * SPEECH_EXIT_FRACTION` keeps the original hysteresis rule.
        //  3. `quietLimit` itself is the final lower bound for the same reason.
        //
        // The loud-plateau protection is untouched: a steady loud signal does not recede,
        // and the `background_met_signal` / `flat` gates below still refuse it.
        double rangeTerm = segmentLow + (segmentHigh - segmentLow) * 0.30;
        // The clamp applies only where the deadlock can actually occur: a segment with no
        // usable dynamic range. A segment that HAS a wide range is not ambiguous, and
        // clamping there would drag a genuinely steady loud level below the exit bar and
        // invent a pause out of an environment change.
        if (segmentHigh - segmentLow < RANGE_MIN) {
            rangeTerm = Math.max(rangeTerm, quietLimit);
        }
        double relaxedExit = Math.max(activeLimit * SPEECH_EXIT_FRACTION, rangeTerm);
        if (segmentHigh - segmentLow < RANGE_MIN) {
            relaxedExit = Math.max(relaxedExit, quietLimit);
        }
        boolean voiced = (wasVisualActive && qualifiedActivity)
                ? activity >= relaxedExit
                : activity >= activeLimit;

        /*
         * When the background has risen to meet the signal, the picture cannot separate
         * speech from steady sound: report UNKNOWN instead of claiming speech.
         *
         * This is the 18:51 failure. The segment ran 28 s and reported VISUAL_ACTIVE for
         * 48 of 49 frames. Its activity took only four discrete values (0.149, 0.188,
         * 0.208, 0.228) and never fell back, while `floor` climbed 0.089 -> 0.200 until it
         * met the activity. At the end the gap between the current activity and the
         * learned background was 0.228 - 0.200 = 0.028: the waveform bars were being
         * measured against a "background" that had become as tall as the bars themselves.
         * In that regime any relative threshold (a fraction of the active limit, or a
         * fraction of the segment range) is meaningless, because the measured levels are
         * dominated by the background itself.
         *
         * The test combines the two things the 18:51 run actually showed, so it cannot
         * misfire on a genuinely separable segment or on real silence:
         *
         *  1. The segment has almost no dynamic range. 18:51 spanned only 0.079 of
         *     activity across 49 frames (four discrete values); a real talk/silence
         *     segment spans 0.40 (0.05 -> 0.45). RANGE_MIN = 0.10 separates those two
         *     populations with a factor of four, and every healthy segment in the 18:56
         *     log that produced real quiet frames spanned 0.139-0.198.
         *  2. The background has risen to meet the signal: activity is within 0.045 of
         *     the learned floor. 18:51 ended at 0.228 - 0.200 = 0.028; its healthy first
         *     36 frames measured 0.099-0.139, and the 18:56 run's healthy frames
         *     measured 0.097-0.166.
         *
         * Genuine silence is handled earlier by the flat-quiet path (activity at or below
         * FLAT_ACTIVITY_MAX), so it never reaches this test. This branch therefore only
         * catches the case the detector cannot honestly classify: a loud, steady picture
         * whose bars no longer stand above their own background.
         */
        boolean backgroundMetSignal = isBackgroundMetSignal(
                activity, floor, segmentLow, segmentHigh, segmentFrames);
        if (backgroundMetSignal) {
            // Keep any accumulated quiet time: an unreadable frame is not evidence of
            // speech, and wiping the clock here is what once made real pauses unsendable.
            if (invalidSince < 0) invalidSince = timestampMs;
            return result(Signal.INVALID, Decision.HOLD_INVALID, "background_met_signal",
                    activity, threshold, timestampMs);
        }

        // After any gap/invalid frame, two fresh valid observations are required.
        if (++recoveryFrames < 2) { wasVisualActive = voiced; return result(Signal.INVALID, Decision.HOLD_INVALID,
                "recovering", activity, threshold, timestampMs); }
        if (voiced) {
            visualActiveRunMs = wasVisualActive ? visualActiveRunMs + dt : 0;
            visualActiveMs += wasVisualActive ? dt : 0; pauseMs = 0;
            observedQuietMs = 0;
            if (visualActiveRunMs >= config.minVisualActiveMs) qualifiedActivity = true;
        } else {
            visualActiveRunMs = 0; pauseMs = wasVisualActive ? 0 : pauseMs + dt;
            creditObservedQuiet(dt);
        }
        wasVisualActive = voiced;
        Signal signal = voiced ? Signal.VISUAL_ACTIVE : Signal.VISUAL_QUIET;
        Decision decision = Decision.WAIT;
        long age = timestampMs - start;
        if (latched) decision = latchedDecision;
        else if (!voiced && age >= config.minSegmentMs && qualifiedActivity
                && pauseMs >= pauseMsRequired && quietEvidenceEnough()) decision = Decision.SEND_PAUSE;
        if (decision == Decision.SEND_PAUSE) {
            latched = true; latchedDecision = decision;
        }
        return result(signal, decision, decision.name(), activity, threshold, timestampMs);
    }

    /**
     * True when the current picture cannot separate speech from its own background.
     *
     * Two independent conditions, both derived from this segment's own measurements:
     *   - the segment shows almost no dynamic range (segmentHigh - segmentLow is small);
     *   - the current activity has risen to within BACKGROUND_GAP_MIN of the learned floor.
     *
     * Only the combination indicates the failure mode measured on the device at 18:51,
     * where activity held at 0.208-0.228 in a 0.079 band while floor climbed 0.089 -> 0.200.
     * A genuinely separable segment has a wide range and a large activity-over-floor gap,
     * and a real silence is handled by the flat-quiet path before this test is reached.
     *
     * Package-private so the host tests can assert the rule against the exact logged values.
     */
    static boolean isBackgroundMetSignal(double activity, double floor,
                                         double segmentLow, double segmentHigh, int segmentFrames) {
        // A range estimate from fewer than FLAT_FRAMES_REQUIRED frames is not evidence:
        // the first frame of a segment has low == high == activity by construction.
        if (segmentFrames < FLAT_FRAMES_REQUIRED) return false;
        if (Double.isNaN(activity) || Double.isNaN(floor)
                || Double.isNaN(segmentLow) || Double.isNaN(segmentHigh)) return false;
        return (segmentHigh - segmentLow) < RANGE_MIN
                && (activity - floor) < BACKGROUND_GAP_MIN;
    }

    /** A segment with less dynamic range than this cannot support a pause decision. */
    static final double RANGE_MIN = 0.10;
    /** Activity within this distance of the learned floor is not separable from it. */
    static final double BACKGROUND_GAP_MIN = 0.045;

    /**
     * Place the speechShare bar from the last minute of this room.
     *
     * Measured with background music playing: shares ran 0.00-0.24 with a median of 0.083,
     * while the quiet gaps sat at 0.00-0.03. A fixed 0.035 bar would call the music itself
     * speech forever. Taking the 40th percentile of the recent window puts the bar in the
     * valley between "room with music only" and "room with a voice over it", and it moves
     * with the room: a silent room settles near the absolute floor, a noisy one lifts.
     */
    private double updateShareThreshold(double share) {
        shareHist[shareNext] = share;
        shareNext = (shareNext + 1) % shareHist.length;
        if (shareCount < shareHist.length) shareCount++;
        double target = SPEECH_SHARE_FLOOR;
        if (shareCount >= 10) {
            double[] sorted = Arrays.copyOf(shareHist, shareCount);
            Arrays.sort(sorted);
            target = Math.max(SPEECH_SHARE_FLOOR, sorted[(int) Math.floor((shareCount - 1) * 0.40)]);
        }
        if (Double.isNaN(shareThreshold)) shareThreshold = target;
        else shareThreshold = shareThreshold * 0.85 + target * 0.15;
        return shareThreshold;
    }

    /**
     * Start this segment from a bar the environment library measured on an earlier run.
     *
     * The stored value is the 40th percentile of that room's tall-bar shares - i.e. the
     * valley between "background only" and "voice over background". Seeding it means the
     * first seconds of a lecture are judged with the right scale instead of the generic
     * floor, which is what makes a library useful at all. Learning continues from there,
     * so a room that changed since the last run corrects itself within a minute.
     */
    public void seedShareBar(double bar) {
        if (Double.isNaN(bar) || bar < 0 || bar > 1) return;
        shareThreshold = Math.max(SPEECH_SHARE_FLOOR, bar);
        shareCount = 0; shareNext = 0;
        // Fill the window with the seeded value so the smoothing does not immediately
        // drag the bar back to the generic floor while real frames are still arriving.
        Arrays.fill(shareHist, shareThreshold);
        shareCount = shareHist.length;
        shareNext = 0;
    }

    /** Current tall-bar speech threshold; exposed for diagnostics and the environment log. */
    public double shareThreshold() { return shareThreshold; }

    private Result invalidate(String reason, long timestampMs, boolean clearFrame) {
        /*
         * "recovering" is not a real observation, it is the two frames of grace after a
         * gap. Wiping the pause clock here is what kept a genuine pause from ever
         * accumulating on the phone: the picture would drop from 0.45 to 0.07 (a real
         * pause), one frame landed in "recovering", and the 2.5 s already counted was
         * thrown away before the next quiet frame could add to it.
         *
         * A real invalid frame still resets everything as before.
         */
        boolean recovering = "recovering".equals(reason);
        recoveryFrames = 0;
        if (!recovering) {
            pauseMs = 0; visualActiveRunMs = 0; wasVisualActive = false;
            observedQuietMs = 0;
            qualifiedActivity = false; latched = false; latchedDecision = null;
        }
        shortActivity = Double.NaN;
        if (invalidSince < 0) invalidSince = timestampMs;
        if (timestampMs >= invalidSince && timestampMs - invalidSince >= 30000) clearBaseline();
        if (clearFrame) hasFrame = false;
        return result(Signal.INVALID, Decision.HOLD_INVALID, reason, Double.NaN, Double.NaN, timestampMs);
    }
    private Result result(Signal signal, Decision decision, String reason, double activity,
                          double threshold, long timestampMs) {
        if (signal != Signal.INVALID) invalidSince = -1;
        return new Result(signal, decision, reason, activity, floor, threshold, longActivity, speechShare, visualActiveMs,
                visualActiveRunMs, pauseMs, Math.max(0, timestampMs - start));
    }
    private static int luminance(int c) {
        return (77 * ((c >>> 16) & 255) + 150 * ((c >>> 8) & 255) + 29 * (c & 255)) >>> 8;
    }
    private static int percentile(int[] hist, int rank) {
        int sum = 0; for (int i = 0; i < hist.length; i++) { sum += hist[i]; if (sum > rank) return i; }
        return 255;
    }
}
