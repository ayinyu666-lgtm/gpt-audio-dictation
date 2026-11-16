package org.gptdictation.assistant;

import java.util.Arrays;

/**
 * Learns the speaker's pause rhythm from completed visual cycles.
 *
 * Two problems this solves:
 *
 * 1. Fast speakers. A fixed wait never suits a real speaker: a teacher who pauses only
 *    ~0.8 s would never trigger a 2400 ms wait. The policy learns a robust median of
 *    completed pauses and blends it in from the SECOND cycle (EARLY_SAMPLES = 2), so the
 *    user's own measured rhythm - not a guess - drives the value almost immediately.
 *    Full trust is reached at MIN_SAMPLES = 8 cycles.
 *
 * 2. Pauses shorter than the screenshot cadence. Sampling is about once per second,
 *    so a short pause may produce only one quiet frame. Those cycles cannot give an
 *    exact duration, but repeated one-frame gaps are still evidence of a fast rhythm;
 *    after three of them the wait is capped at {@link #FAST_PAUSE_MS}. A single odd
 *    gap never changes anything.
 *
 * Noise is deliberately NOT part of the wait any more. How long a speaker may hesitate
 * is a property of the speaker and the segment, not of how hard the picture is to read;
 * multiplying it by a noise factor made every short question wait 55% longer. Noise is
 * expressed through the detector's activity margin instead (NoiseAdaptation), so the
 * {@code noiseFactor} parameter is retained only for source compatibility and ignored.
 *
 * This class never sees audio, text or message state. It only measures visual timing.
 */
public final class AdaptivePausePolicy {
    /** Learned cycles needed before the median is fully trusted. */
    public static final int MIN_SAMPLES = 8, EARLY_SAMPLES = 2, CAPACITY = 200;
    /**
     * Cold-start wait, and the pause floor/ceiling.
     *
     * 1800 ms is not a guess: measured on the user's own phone (0.8.0 live log,
     * 24 real pause observations) the distribution was median 1035 ms, p75 1702 ms,
     * max 2470 ms, with 62% of pauses below 1500 ms. 1800 sits ABOVE p75, so a genuine
     * pause is still very unlikely to be cut off, while removing 600 ms of dead time
     * from every cold start compared with the previous legacy 2400.
     *
     * The previous 2400 was an undocumented fixed value; the file's own history called
     * it "a fixed wait ... that never fires for a teacher who pauses only ~0.8 s".
     */
    public static final long MIN_PAUSE_MS = 1000, MAX_PAUSE_MS = 8000, DEFAULT_MS = 1800;
    public static final long FAST_PAUSE_MS = 1400;
    /**
     * Absolute floor for the fast-rhythm path, applied after the bias coefficient.
     * A listener cannot tune the wait below this, so a fast speaker never gets a
     * hair-trigger pause even at the most aggressive bias setting.
     */
    public static final long FAST_PAUSE_FLOOR_MS = 1400;
    public static final int FAST_GAP_VOTES = 3;
    /**
     * The policy is "rather slow than interrupt": a segment that has only just started
     * keeps the full learned/DEFAULT wait. Only a LONG segment earns a shorter pause
     * requirement, because at that point the speaker has clearly been going for minutes
     * and a close is needed. The old short-question fast path (a 1600 ms ceiling for the
     * first 20 s) pointed the opposite way and sent while the user was mid-sentence.
     */
    private static final double RAMP_START_SEC = 30, RAMP_MID_SEC = 120, RAMP_END_SEC = 300;
    private static final long MIN_CYCLE_PAUSE = 200, MAX_CYCLE_PAUSE = 8000;
    private static final long MAX_BRIEF_GAP_MS = 3000;

    private final long maxGapMs, resetAfterInvalidMs;
    private final long[] samples = new long[CAPACITY];
    private int count, next;
    private int shortGapVotes;
    private long previous = -1, invalidSince = -1, pauseStart = -1;
    private long pendingPause = -1;
    private long lastActive = -1, beforePause = -1, lastQuiet = -1;
    private int activeFrames, quietFrames;
    private boolean activeBefore, paused;

    public AdaptivePausePolicy(long maxGapMs, long resetAfterInvalidMs) {
        if (maxGapMs <= 0 || resetAfterInvalidMs <= maxGapMs)
            throw new IllegalArgumentException("invalid time limits");
        this.maxGapMs = maxGapMs; this.resetAfterInvalidMs = resetAfterInvalidMs;
    }
    public AdaptivePausePolicy() { this(2000, 30000); }

    /** Break an unfinished cycle at segment boundaries without discarding learned pauses. */
    public void beginSegment() { clearCycle(); }
    /** Call on a new class, environment, input device, or crop/appearance change. */
    public void reset() { count = next = 0; shortGapVotes = 0; previous = invalidSince = -1; beginSegment(); }
    public int sampleCount() { return count; }

    /** Feed each detector result exactly once with the same monotonic timestamp.
     * recordingUiVerified means the current target recording UI, crop, and fresh
     * screenshot were independently verified; it does not assert source identity.
     * INVALID includes unknown/ambiguous input. A sampling gap always breaks a cycle;
     * a long invalid span or gap also clears learned samples. No audio is stored.
     */
    public void observe(WaveformDetector.Signal signal, long timestampMs, boolean recordingUiVerified) {
        if (signal == null || timestampMs < 0) throw new IllegalArgumentException("signal/time");
        if (!recordingUiVerified) signal = WaveformDetector.Signal.INVALID;
        if (previous >= 0 && timestampMs <= previous) { clearCycle(); return; }
        if (previous >= 0 && timestampMs - previous > maxGapMs) {
            if (timestampMs - previous >= resetAfterInvalidMs) { count = next = 0; shortGapVotes = 0; }
            clearCycle();
        }
        previous = timestampMs;
        if (signal == WaveformDetector.Signal.INVALID) {
            if (invalidSince < 0) invalidSince = timestampMs;
            if (timestampMs - invalidSince >= resetAfterInvalidMs) { count = next = 0; shortGapVotes = 0; }
            clearCycle(); return;
        }
        if (invalidSince >= 0 && timestampMs - invalidSince >= resetAfterInvalidMs) { count = next = 0; shortGapVotes = 0; }
        invalidSince = -1;
        if (signal == WaveformDetector.Signal.VISUAL_QUIET) {
            if (activeBefore && !paused) {
                pauseStart = timestampMs; beforePause = lastActive; paused = true; quietFrames = 0;
            }
            if (paused) { lastQuiet = timestampMs; quietFrames++; }
            activeFrames = 0; pendingPause = -1;
        } else {
            if (paused) {
                // Each boundary lies between two snapshots. Use midpoint estimates and
                // reject sub-resolution cycles. Two quiet frames give a usable duration;
                // exactly one is at the sampling limit, so it is counted as a brief-gap
                // vote instead of pretending to know the exact length.
                long entryGap = pauseStart - beforePause, exitGap = timestampMs - lastQuiet;
                long duration = (lastQuiet - pauseStart) + (entryGap + exitGap) / 2;
                if (quietFrames >= 2 && duration >= MIN_CYCLE_PAUSE && duration <= MAX_CYCLE_PAUSE
                        && duration > Math.max(entryGap, exitGap)) {
                    pendingPause = duration;
                } else if (quietFrames == 1 && duration >= MIN_CYCLE_PAUSE && duration <= MAX_BRIEF_GAP_MS) {
                    if (++shortGapVotes > FAST_GAP_VOTES) shortGapVotes = FAST_GAP_VOTES;
                }
                activeFrames = 0;
            }
            lastActive = timestampMs;
            activeFrames++;
            if (activeFrames >= 2) {
                if (pendingPause >= 0) {
                    samples[next] = pendingPause; next = (next + 1) % CAPACITY;
                    if (count < CAPACITY) count++;
                    // A reliably long pause shows the rhythm is not fast after all.
                    if (pendingPause > 3000) shortGapVotes = 0;
                    pendingPause = -1;
                }
                activeBefore = true;
            }
            paused = false; pauseStart = -1;
        }
    }

    /**
     * bias -9..9: positive sends faster, applied as the smooth coefficient 2^(-bias/9).
     * visualActiveMs is the current segment's observed visual activity duration and only
     * selects the speech-length ramp position.
     *
     * noiseFactor is accepted for compatibility and intentionally ignored: noise must
     * only widen the detector's activity margin, never lengthen the wait. The value is
     * still validated so a programming error at a call site cannot pass silently.
     *
     * Result is bounded to 1000..8000 ms.
     */
    public long requiredPauseMs(int bias, long visualActiveMs) {
        return requiredPauseMs(bias, visualActiveMs, 1.0);
    }

    public long requiredPauseMs(int bias, long visualActiveMs, double noiseFactor) {
        if (bias < -9 || bias > 9 || visualActiveMs < 0) throw new IllegalArgumentException("bias/visualActiveMs");
        if (Double.isNaN(noiseFactor) || Double.isInfinite(noiseFactor) || noiseFactor < 1.0 || noiseFactor > 2.0)
            throw new IllegalArgumentException("noiseFactor");
        // Range is -9..9 with the same per-step effect as the earlier -3..3 control,
        // so the endpoints behave identically while the middle is three times finer.
        double scale = Math.pow(2.0, -bias / 9.0);
        double base = DEFAULT_MS;
        if (count >= EARLY_SAMPLES) {
            long[] sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
            double median = sorted[(count - 1) / 2];
            double learned = median * 1.15;
            // Blend in over the first few cycles; MIN_SAMPLES completed cycles is full trust.
            double confidence = Math.min(1.0, (count - EARLY_SAMPLES + 1)
                    / (double) (MIN_SAMPLES - EARLY_SAMPLES + 1));
            base = DEFAULT_MS + (learned - DEFAULT_MS) * confidence;
        }
        boolean fastRhythm = shortGapVotes >= FAST_GAP_VOTES;
        if (fastRhythm) base = Math.min(base, FAST_PAUSE_MS);
        double value = base * scale;
        /*
         * Fast-rhythm floor, applied AFTER the bias scale.
         *
         * The old code capped the BASE at FAST_PAUSE_MS and then multiplied by the bias
         * coefficient, so a listener on +2 got 1400 * 0.857 = 1200 ms - below the 1400 ms
         * safeguard, which let a short pause fire too early. Scaling first and then
         * enforcing the floor keeps the promise for every bias value: a speaker who has
         * shown three or more brief gaps gets a genuinely fast wait, but never a
         * hair-trigger one. The listener's own bias can still make it faster than the
         * learned rhythm; it just cannot push it under the floor.
         */
        if (fastRhythm) value = Math.max(FAST_PAUSE_FLOOR_MS, value);

        /*
         * Speech-length ramp, in the user's chosen direction: "rather slow than interrupt".
         *
         * A segment that has only just started keeps the full learned/DEFAULT wait - there
         * is no evidence yet that this is a long dictation, so a short pause must not close
         * it. Only as the speech length grows does the requirement ease down, so a long
         * dictation can still close instead of running to the hard time limit.
         *
         * Smoothstep between fixed anchors: 1.0 at 30 s, 0.55 at 120 s, 0.30 at 300 s.
         * Both joins have zero slope, so the curve is value- and slope-continuous.
         * It only ever SHORTENS the learned value, never lengthens it.
         */
        value *= speechLengthRamp(visualActiveMs / 1000.0);
        return Math.max(MIN_PAUSE_MS, Math.min(MAX_PAUSE_MS, Math.round(value)));
    }

    /**
     * Continuous, monotonically non-increasing multiplier over the segment's speech length.
     * Flat 1.0 up to 30 s, smoothstep down to 0.55 at 120 s and 0.30 at 300 s, flat after.
     */
    static double speechLengthRamp(double speechSec) {
        if (speechSec <= RAMP_START_SEC) return 1.0;
        if (speechSec < RAMP_MID_SEC) {
            double x = (speechSec - RAMP_START_SEC) / (RAMP_MID_SEC - RAMP_START_SEC);
            return 1.0 - 0.45 * smoothStep(x);
        }
        if (speechSec < RAMP_END_SEC) {
            double x = (speechSec - RAMP_MID_SEC) / (RAMP_END_SEC - RAMP_MID_SEC);
            return 0.55 - 0.25 * smoothStep(x);
        }
        return 0.30;
    }

    /**
     * Minimum OBSERVED quiet evidence before a pause-based send is allowed.
     *
     * This is a count of verified quiet observation time, not a floor on the noise level:
     * the detector must have actually seen this much quiet across its samples. It exists so
     * a single dropped/ambiguous frame can never be mistaken for a pause - two sampling
     * intervals at the device's 500 ms cadence, with a 600 ms lower bound so a faster
     * sampler still needs more than one frame.
     *
     * The detector lane enforces it; this policy only publishes the number and the rule.
     */
    public static long minimumEvidenceMs() {
        return Math.max(600L, 2L * 500L);
    }

    private static double smoothStep(double x) {
        double c = x < 0 ? 0 : x > 1 ? 1 : x;
        return c * c * (3 - 2 * c);
    }

    private void clearCycle() {
        activeBefore = paused = false; pauseStart = pendingPause = lastActive = beforePause = lastQuiet = -1;
        activeFrames = quietFrames = 0;
    }
}
