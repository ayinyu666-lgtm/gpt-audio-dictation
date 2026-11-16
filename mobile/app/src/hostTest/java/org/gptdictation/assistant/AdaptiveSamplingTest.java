package org.gptdictation.assistant;

/**
 * Host-only test for the screenshot cadence.
 *
 * The rule that matters and that the user stated explicitly: any pause must still be
 * caught. So sound must always keep sampling fast, no matter how long the segment has been
 * running; only measured SILENCE may slow it down; and the moment sound returns the fast
 * cadence must come back immediately.
 */
public final class AdaptiveSamplingTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Speaking always samples fast, even after a very long time in the segment. */
    private static void speechStaysFastForever() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        // Ten minutes of continuous speech at 500 ms per frame.
        for (long t = 0; t <= 600_000; t += 500) p.observe(Boolean.TRUE, t);
        check(p.intervalMs(600_000) == AdaptiveSamplingPolicy.FAST_MS,
                "speech must stay on the fast cadence no matter how long it lasts");
    }

    /** Silence slows down in steps. */
    private static void silenceSlowsInSteps() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        // A real slow run keeps producing readable quiet frames; feed them at the actual
        // capture rhythm (a verdict per capture cycle) instead of only one at t=0, otherwise
        // the stale-verdict guard correctly treats the state as unknown again.
        for (long t = 0; t <= AdaptiveSamplingPolicy.SLOW_AFTER_MS; t += 900) {
            p.observe(Boolean.FALSE, t);
        }
        check(p.intervalMs(1000) == AdaptiveSamplingPolicy.FAST_MS, "just stopped: stay fast");
        check(p.intervalMs(AdaptiveSamplingPolicy.SLOW_AFTER_MS) == AdaptiveSamplingPolicy.SLOW_MS,
                "a few seconds of silence: slow down");
        for (long t = AdaptiveSamplingPolicy.SLOW_AFTER_MS + 1200; t <= AdaptiveSamplingPolicy.IDLE_AFTER_MS; t += 1200) {
            p.observe(Boolean.FALSE, t);
        }
        check(p.intervalMs(AdaptiveSamplingPolicy.IDLE_AFTER_MS) == AdaptiveSamplingPolicy.IDLE_MS,
                "long silence: slowest");
    }

    /** The instant sound returns, fast cadence returns with it. */
    private static void soundRestoresFastImmediately() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        // Long, continuously-observed silence: one quiet verdict per slow capture cycle.
        for (long t = 0; t <= 10_000; t += 1200) p.observe(Boolean.FALSE, t);
        p.observe(Boolean.FALSE, 10_000);
        check(p.intervalMs(10_000) == AdaptiveSamplingPolicy.IDLE_MS, "long silence is slow before sound");
        p.observe(Boolean.TRUE, 10_000);
        check(p.intervalMs(10_100) == AdaptiveSamplingPolicy.FAST_MS,
                "sound must restore the fast cadence immediately, not after a delay");
    }

    /** An unreadable frame must not be mistaken for silence. */
    private static void ambiguousFramesChangeNothing() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        for (long t = 0; t <= 20_000; t += 500) p.observe(Boolean.TRUE, t);
        long before = p.intervalMs(20_000);
        p.observe(null, 20_500);
        check(p.intervalMs(20_500) == before, "an ambiguous frame must not slow the cadence");
    }

    /**
     * The slowest cadence must still allow a pause to be measured.
     *
     * This mirrors the RECORDING branch of DictationService.tick(): watchCapture() runs
     * BEFORE requestWaveform() in the same tick, and an over-age sample is invalidated
     * there. A real invalid frame resets WaveformDetector's pauseMs, so if the chosen
     * cadence leaves the sample older than WaveformSamplingPolicy.FRESH_MS at the next
     * tick, the pause clock is wiped before every single capture and SEND_PAUSE can
     * never fire. With IDLE_MS=1500 the 400 ms tick put samples 1600 ms apart and that
     * is exactly what happened (observed requiredPauseMs on device: 2057 ms).
     *
     * The simulation uses the real policy constants, so it fails if IDLE_MS is ever
     * raised back to or above FRESH_MS.
     */
    private static void slowestTierLetsPauseAccumulate() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        // The room has been quiet long enough to reach the slowest tier; feed the quiet
        // verdicts at the real slow-tier capture rhythm so the stale-verdict guard does
        // not treat this legitimate state as unknown.
        for (long t = 0; t <= AdaptiveSamplingPolicy.IDLE_AFTER_MS; t += 1200) {
            p.observe(Boolean.FALSE, t);
        }
        p.observe(Boolean.FALSE, AdaptiveSamplingPolicy.IDLE_AFTER_MS);

        long now = AdaptiveSamplingPolicy.IDLE_AFTER_MS;
        check(p.intervalMs(now) == AdaptiveSamplingPolicy.IDLE_MS,
                "test setup: expected the slowest tier after IDLE_AFTER_MS of silence");
        check(p.intervalMs(now) < WaveformSamplingPolicy.FRESH_MS,
                "slowest cadence (" + AdaptiveSamplingPolicy.IDLE_MS + " ms) must stay below the "
                        + WaveformSamplingPolicy.FRESH_MS + " ms freshness limit, with margin for the 400 ms tick");

        final long tickMs = 400, requiredPauseMs = 2057, maxFrameGapMs = 2000;
        long previous = -1;          // WaveformDetector's last observe() timestamp
        long lastSampleAt = 0;       // service's lastWaveformAt: only set by a VALID frame
        boolean hasSample = false;
        long lastCaptureAt = -1;
        long pauseMs = 0, maxPauseMs = 0, firedAt = -1;

        for (long t = now; t <= now + 60_000; t += tickMs) {
            // watchCapture(now): a sample older than FRESH_MS is invalidated, which resets pauseMs.
            if (hasSample && !WaveformSamplingPolicy.fresh(t, lastSampleAt)) {
                pauseMs = 0; previous = t; hasSample = false;
            }
            check(p.intervalMs(t) < WaveformSamplingPolicy.FRESH_MS || !hasSample,
                    "at no tick in the slowest tier may the chosen interval exceed the freshness limit (t=" + t + ")");
            // requestWaveform(now): at most one capture per cadence interval; continuous VISUAL_QUIET.
            if (lastCaptureAt < 0 || t - lastCaptureAt >= p.intervalMs(t)) {
                long dt = previous < 0 ? 0 : t - previous;
                previous = t;
                if (dt > maxFrameGapMs) pauseMs = 0; else pauseMs += dt;
                hasSample = true; lastSampleAt = t; lastCaptureAt = t;
                if (pauseMs > maxPauseMs) maxPauseMs = pauseMs;
                if (firedAt < 0 && pauseMs >= requiredPauseMs) firedAt = t;
            }
        }
        check(firedAt >= 0,
                "a continuously quiet detector must accumulate pauseMs past requiredPauseMs=" + requiredPauseMs
                        + " even at the slowest cadence; max reached was " + maxPauseMs + " ms");
    }

    /**
     * One VISUAL_QUIET followed by a long run of INVALID frames must not pin the cadence
     * at the slowest tier forever.
     *
     * This was the real failure: observe(null, now) returned without touching any state,
     * so quietSince survived from a single quiet frame and intervalMs() reported IDLE_MS
     * twenty seconds later while the room may have been full of speech (measured: 112 of
     * 197 frames were INVALID). An unreadable frame is not evidence of silence, so after
     * a bounded time the state must be treated as unknown and sampling must go fast.
     */
    private static void invalidFramesCannotRatchetDownForever() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        // A genuine quiet stretch: quiet verdicts at the real slow-tier rhythm up to 19.6 s,
        // with the last one a tick before the check.
        for (long t = 0; t <= 19_600; t += 1200) p.observe(Boolean.FALSE, t);
        check(p.intervalMs(20_000) == AdaptiveSamplingPolicy.IDLE_MS,
                "test setup: a genuinely observed quiet stretch must still reach the slowest tier");

        // Now the detector goes unreadable: twenty seconds of INVALID at the recording tick.
        for (long t = 20_400; t <= 40_000; t += 400) p.observe(null, t);
        check(p.intervalMs(40_000) == AdaptiveSamplingPolicy.FAST_MS,
                "after 20 s without a readable verdict the cadence must be back at FAST_MS, not latched slow");

        // The return must be bounded, not merely true eventually: find the first tick
        // after which the cadence is fast and require it within VERDICT_STALE_MS + one tick.
        AdaptiveSamplingPolicy q = new AdaptiveSamplingPolicy();
        q.reset();
        for (long t = 0; t <= 19_600; t += 1200) q.observe(Boolean.FALSE, t);
        long recoveredAt = -1;
        for (long t = 20_400; t <= 40_000; t += 400) {
            q.observe(null, t);
            if (q.intervalMs(t) == AdaptiveSamplingPolicy.FAST_MS) { recoveredAt = t; break; }
        }
        check(recoveredAt >= 0 && recoveredAt - 19_600 <= AdaptiveSamplingPolicy.VERDICT_STALE_MS + 400,
                "cadence must reach FAST_MS within VERDICT_STALE_MS + one tick after the last valid verdict; took "
                        + (recoveredAt < 0 ? "never" : (recoveredAt - 19_600) + " ms"));
    }

    /**
     * The stale-verdict guard must not destroy the slow tier it is guarding.
     *
     * In the slowest tier a valid verdict only arrives once per capture cycle: 1200 ms of
     * cadence plus the screenshot callback, i.e. about three recording ticks (400 ms). If
     * VERDICT_STALE_MS were as short as three ticks (1200 ms), the guard would fire just
     * before every legitimate verdict and intervalMs() would collapse to FAST_MS forever.
     * This test feeds verdicts at the real slow-tier spacing and asserts the tier holds.
     */
    private static void slowTierSurvivesNormalVerdictSpacing() {
        AdaptiveSamplingPolicy p = new AdaptiveSamplingPolicy();
        p.reset();
        p.observe(Boolean.FALSE, 0);
        // A capture every 1200 ms; the callback lands one tick later, so a verdict every 1200 ms.
        // The interval is not the slowest tier until quietSince has aged past IDLE_AFTER_MS;
        // a 1200 ms verdict spacing must keep it at IDLE_MS from then on.
        long reachedIdleAt = -1;
        for (long t = 1200; t <= 60_000; t += 1200) {
            p.observe(Boolean.FALSE, t);
            if (p.intervalMs(t) == AdaptiveSamplingPolicy.IDLE_MS) {
                if (reachedIdleAt < 0) reachedIdleAt = t;
            } else if (reachedIdleAt >= 0) {
                throw new AssertionError("a slow-tier run lost its tier at t=" + t);
            }
        }
        check(reachedIdleAt > 0 && reachedIdleAt <= AdaptiveSamplingPolicy.IDLE_AFTER_MS + 1200,
                "a continuously observed quiet run must reach the slowest tier; reached at " + reachedIdleAt + " ms");
    }

    public static void main(String[] args) {
        speechStaysFastForever();
        silenceSlowsInSteps();
        soundRestoresFastImmediately();
        ambiguousFramesChangeNothing();
        slowestTierLetsPauseAccumulate();
        invalidFramesCannotRatchetDownForever();
        slowTierSurvivesNormalVerdictSpacing();
        System.out.println("AdaptiveSampling: fast while speaking, slower only in silence, instant recovery, slowest tier still catches pauses, stale verdicts reset to fast");
    }
}
