package org.gptdictation.assistant;

/** Run with javac/java; timestamps and pauses are milliseconds. */
public final class AdaptivePausePolicyTest {
    private static void check(boolean b, String s) { if (!b) throw new AssertionError(s); }
    private static long cycle(AdaptivePausePolicy p, long t, long duration) {
        p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t, true);
        p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 100, true);
        p.observe(WaveformDetector.Signal.VISUAL_QUIET, t + 200, true);
        long step = Math.min(1000, duration / 2);
        for (long x = step; x < duration; x += step) p.observe(WaveformDetector.Signal.VISUAL_QUIET, t + 200 + x, true);
        p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 200 + duration, true);
        p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 300 + duration, true);
        return t + duration + 500;
    }
    private static void near(double actual, double expected, double tol, String why) {
        check(Math.abs(actual - expected) <= tol, why + " (expected " + expected + " +/- " + tol + ", got " + actual + ")");
    }

    /**
     * "Rather slow than interrupt": a short segment must NOT be faster than a long one.
     * At 10 s of speech the full learned/DEFAULT value applies - no 1600 ms cap.
     */
    private static void shortSegmentsAreNotFaster() {
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        long at10 = p.requiredPauseMs(2, 10_000);
        check(at10 >= AdaptivePausePolicy.DEFAULT_MS * Math.pow(2.0, -2.0 / 9.0) - 1,
                "at 10 s the wait must be at least DEFAULT*scale, got " + at10);
        near(at10, Math.round(AdaptivePausePolicy.DEFAULT_MS * Math.pow(2.0, -2.0 / 9.0)), 2,
                "10 s at bias=+2 is the full cold start");
        long at200 = p.requiredPauseMs(2, 200_000);
        check(at200 < at10, "a long segment must not be slower than a short one: 200s=" + at200 + " 10s=" + at10);
        check(p.requiredPauseMs(2, 10_000) <= at10, "60 s must not exceed the 10 s value");
        // Safeguard: must stay well above the deleted 1371 ms short cap.
        check(at10 >= 1400, "must not regress to the deleted short-question cap, got " + at10);
    }

    /** The ramp only shortens LONG segments; the opening 30 s keeps the full wait. */
    private static void longLectureRamp() {
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        check(p.requiredPauseMs(0, 0) == AdaptivePausePolicy.DEFAULT_MS, "start of segment is full default");
        check(p.requiredPauseMs(0, 30_000) == AdaptivePausePolicy.DEFAULT_MS, "flat through 30 s");
        check(p.requiredPauseMs(0, 30_001) == AdaptivePausePolicy.DEFAULT_MS, "no cliff at 30 s");
        check(p.requiredPauseMs(0, 120_000) == Math.max(AdaptivePausePolicy.MIN_PAUSE_MS,
                        Math.round(AdaptivePausePolicy.DEFAULT_MS * 0.55)),
                "0.55 at 120 s, got " + p.requiredPauseMs(0, 120_000));
        check(p.requiredPauseMs(0, 200_000) <= p.requiredPauseMs(0, 120_000),
                "still falling at 200 s");
        check(p.requiredPauseMs(0, 300_000) == Math.max(AdaptivePausePolicy.MIN_PAUSE_MS,
                        Math.round(AdaptivePausePolicy.DEFAULT_MS * 0.30)),
                "0.30 at 300 s, got " + p.requiredPauseMs(0, 300_000));
        check(p.requiredPauseMs(0, 600_000) == p.requiredPauseMs(0, 300_000), "flat after 300 s");
    }

    /** The speech-length ramp must be monotonic and continuous at every join. */
    private static void rampIsMonotonicAndContinuous() {
        double prev = AdaptivePausePolicy.speechLengthRamp(0);
        check(prev == 1.0, "ramp starts flat at 1.0");
        for (int sec = 1; sec <= 400; sec++) {
            double v = AdaptivePausePolicy.speechLengthRamp(sec);
            check(v <= prev + 1e-12, "ramp must never rise: " + sec + " s went " + prev + " -> " + v);
            check(v >= 0.30 - 1e-12, "ramp must not fall below 0.30");
            prev = v;
        }
        near(AdaptivePausePolicy.speechLengthRamp(30), 1.0, 1e-12, "value at 30 s");
        near(AdaptivePausePolicy.speechLengthRamp(120), 0.55, 1e-12, "value at 120 s");
        near(AdaptivePausePolicy.speechLengthRamp(300), 0.30, 1e-12, "value at 300 s");
        // Continuity: a tiny step across each join must not jump.
        double eps = 1e-6;
        near(AdaptivePausePolicy.speechLengthRamp(30 - eps), AdaptivePausePolicy.speechLengthRamp(30 + eps),
                1e-6, "no step across 30 s");
        near(AdaptivePausePolicy.speechLengthRamp(120 - eps), AdaptivePausePolicy.speechLengthRamp(120 + eps),
                1e-6, "no step across 120 s");
        near(AdaptivePausePolicy.speechLengthRamp(300 - eps), AdaptivePausePolicy.speechLengthRamp(300 + eps),
                1e-4, "no step across 300 s");
        // Slope continuity: the smoothstep has zero derivative at the joins.
        double h = 0.01;
        double slope30 = (AdaptivePausePolicy.speechLengthRamp(30 + h) - AdaptivePausePolicy.speechLengthRamp(30 - h)) / (2 * h);
        double slope120 = (AdaptivePausePolicy.speechLengthRamp(120 + h) - AdaptivePausePolicy.speechLengthRamp(120 - h)) / (2 * h);
        check(Math.abs(slope30) < 1e-4, "zero slope at 30 s, got " + slope30);
        check(Math.abs(slope120) < 1e-3, "zero slope at 120 s, got " + slope120);
    }

    /** Noise must never lengthen the wait; it is a detection-margin concept only. */
    private static void noiseFactorIsIgnored() {
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        // Long segment so the short-question ceiling does not mask the comparison.
        long calm = p.requiredPauseMs(0, 60_000, 1.0);
        long noisy = p.requiredPauseMs(0, 60_000, 1.55);
        check(calm == noisy, "noise factor must not change the wait: calm=" + calm + " noisy=" + noisy);
        check(p.requiredPauseMs(2, 60_000, 1.55) == p.requiredPauseMs(2, 60_000, 1.0),
                "noise factor ignored at +2 as well");
        boolean rejected = false;
        try { p.requiredPauseMs(0, 0, 0.5); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "out-of-range factor is still rejected");
        rejected = false;
        try { p.requiredPauseMs(0, 0, 2.5); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "factor above the valid range is still rejected");
    }

    /**
     * EARLY_SAMPLES = 2: the blend must start at the second completed cycle, and the
     * cold-start value must be the measured-backed 1800, not the legacy 2400.
     */
    private static void learningStartsAtSecondCycle() {
        check(AdaptivePausePolicy.EARLY_SAMPLES == 2, "EARLY_SAMPLES must be 2");
        check(AdaptivePausePolicy.MIN_SAMPLES == 8, "MIN_SAMPLES stays 8 for full confidence");
        check(AdaptivePausePolicy.DEFAULT_MS == 1800, "cold start is the measured-backed 1800");
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        long t = 0;
        t = cycle(p, t, 1000);
        check(p.sampleCount() == 1, "one cycle");
        check(p.requiredPauseMs(0, 10_000) == 1800, "count=1 is still the full cold start");
        t = cycle(p, t, 1000);
        check(p.sampleCount() == 2, "two cycles");
        long at2 = p.requiredPauseMs(0, 10_000);
        check(at2 < 1800, "count=2 must already blend the measured rhythm, got " + at2);
        check(at2 > AdaptivePausePolicy.MIN_PAUSE_MS,
                "count=2 must not collapse to the floor (confidence is only 1/7), got " + at2);
        for (int i = 0; i < 6; i++) t = cycle(p, t, 1000);
        check(p.sampleCount() == 8, "eight cycles");
        long at8 = p.requiredPauseMs(0, 10_000);
        check(at8 < at2, "eight cycles must be closer to the measured rhythm than two, at2=" + at2 + " at8=" + at8);
    }

    /**
     * SAFEGUARDS after lowering DEFAULT_MS to 1800 and EARLY_SAMPLES to 2:
     * the short-question path must not regress to the deleted 1371 cap, a long lecture
     * must still bottom at MIN_PAUSE_MS, and the ramp direction must stay conservative
     * early / shorter later.
     */
    private static void defaultChangeSafeguards() {
        AdaptivePausePolicy p = new AdaptivePausePolicy();
        long short10 = p.requiredPauseMs(2, 10_000);
        check(short10 >= 1400,
                "a 10 s question at bias=+2 must stay >= 1400 ms (no 1371 short cap), got " + short10);
        near(short10, 1543, 2, "10 s cold start at bias=+2 is 1800*2^(-2/9)");
        check(p.requiredPauseMs(2, 300_000) == AdaptivePausePolicy.MIN_PAUSE_MS,
                "a 300 s lecture still bottoms at MIN_PAUSE_MS, got " + p.requiredPauseMs(2, 300_000));
        check(p.requiredPauseMs(2, 10_000) > p.requiredPauseMs(2, 200_000),
                "ramp direction must stay conservative early, shorter later");
        check(p.requiredPauseMs(2, 60_000) < p.requiredPauseMs(2, 10_000),
                "60 s is already shorter than 10 s");
        check(AdaptivePausePolicy.MIN_PAUSE_MS == 1000, "MIN_PAUSE_MS must not change");
        check(AdaptivePausePolicy.minimumEvidenceMs() == 1000, "MIN_OBSERVED_QUIET_MS must not change");
        System.out.println("AdaptivePausePolicy: safeguards 10s=" + short10
                + " ms, 300s=" + p.requiredPauseMs(2, 300_000) + " ms");
    }

    /**
     * The fast-rhythm floor must survive the bias scale at every bias value.
     * Old defect: base was capped at 1400 BEFORE scaling, so +2 gave 1400*0.857 = 1200 ms.
     */
    private static void fastRhythmFloorSurvivesBias() {
        for (int bias : new int[]{-9, 0, 2, 9}) {
            AdaptivePausePolicy p = new AdaptivePausePolicy();
            long t = 0;
            // Three brief gaps vote the speaker into the fast-rhythm path.
            for (int i = 0; i < 3; i++) {
                p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t, true);
                p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 1000, true);
                p.observe(WaveformDetector.Signal.VISUAL_QUIET, t + 2000, true);
                p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 3000, true);
                p.observe(WaveformDetector.Signal.VISUAL_ACTIVE, t + 4000, true);
                t += 5000;
            }
            check(p.sampleCount() == 0, "brief gaps give no exact duration");
            long v = p.requiredPauseMs(bias, 10_000);
            check(v >= 1400, "fast-rhythm wait at bias=" + bias + " must stay >= 1400 ms, got " + v);
            check(v >= AdaptivePausePolicy.FAST_PAUSE_FLOOR_MS,
                    "fast-rhythm floor violated at bias=" + bias + ", got " + v);
        }
        System.out.println("AdaptivePausePolicy: fast-rhythm floor >= 1400 ms holds at bias -9/0/+2/+9");
    }

    public static void main(String[] args) {
        AdaptivePausePolicy fast = new AdaptivePausePolicy(), slow = new AdaptivePausePolicy();
        long f = 0, s = 0;
        // EARLY_SAMPLES is now 2: the first completed cycle still uses the full cold start,
        // the second one already blends in the measured rhythm.
        f = cycle(fast, f, 400); s = cycle(slow, s, 2400);
        check(fast.sampleCount() == 1 && fast.requiredPauseMs(0, 10_000) == AdaptivePausePolicy.DEFAULT_MS,
                "the first cycle uses the full cold-start value");
        f = cycle(fast, f, 400); s = cycle(slow, s, 2400);
        check(fast.sampleCount() == 2, "second cycle counted");
        check(fast.requiredPauseMs(0, 10_000) < AdaptivePausePolicy.DEFAULT_MS,
                "learning starts from the second completed cycle, got " + fast.requiredPauseMs(0, 10_000));
        check(slow.requiredPauseMs(0, 10_000) > fast.requiredPauseMs(0, 10_000), "different rhythms separate early");

        for (int i = 0; i < 10; i++) { f = cycle(fast, f, 400); s = cycle(slow, s, 2400); }
        check(fast.sampleCount() == 12 && slow.sampleCount() == 12, "learn twelve complete cycles");
        check(fast.requiredPauseMs(0, 10_000) <= 1400, "fast speaker stays near the fast bound, got " + fast.requiredPauseMs(0, 10_000));
        check(slow.requiredPauseMs(0, 10_000) > AdaptivePausePolicy.DEFAULT_MS, "slow speaker lengthens, got " + slow.requiredPauseMs(0, 10_000));
        check(slow.requiredPauseMs(3, 10_000) < slow.requiredPauseMs(0, 10_000), "faster bias");
        check(slow.requiredPauseMs(-3, 10_000) > slow.requiredPauseMs(0, 10_000), "conservative bias");
        check(fast.requiredPauseMs(0, 60_000, 1.0) >= AdaptivePausePolicy.MIN_PAUSE_MS,
                "never below the sampling floor");

        // Full confidence at MIN_SAMPLES (8), not 12: an 8-cycle median must already be
        // fully applied, so adding four more identical cycles cannot change the result.
        AdaptivePausePolicy conf = new AdaptivePausePolicy();
        long c = 0;
        for (int i = 0; i < 8; i++) c = cycle(conf, c, 2000);
        check(conf.sampleCount() == 8, "eight cycles");
        long atEight = conf.requiredPauseMs(0, 10_000);
        for (int i = 0; i < 4; i++) c = cycle(conf, c, 2000);
        check(conf.requiredPauseMs(0, 10_000) == atEight, "confidence must be full at 8 samples");

        long beforeUntrusted = f;
        fast.observe(WaveformDetector.Signal.VISUAL_ACTIVE, beforeUntrusted, false);
        fast.observe(WaveformDetector.Signal.VISUAL_QUIET, beforeUntrusted + 300, false);
        fast.observe(WaveformDetector.Signal.VISUAL_ACTIVE, beforeUntrusted + 700, false);
        check(fast.sampleCount() == 12, "unverified recording UI cannot train");

        slow.beginSegment(); check(slow.sampleCount() == 12, "retain across segments");
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s, true);
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s + 100, true);
        slow.observe(WaveformDetector.Signal.VISUAL_QUIET, s + 200, true);
        slow.observe(WaveformDetector.Signal.INVALID, s + 300, true);
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s + 400, true);
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s + 500, true);
        check(slow.sampleCount() == 12, "invalid cycle cannot learn");
        slow.observe(WaveformDetector.Signal.VISUAL_QUIET, s + 600, true);
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s + 4000, true);
        slow.observe(WaveformDetector.Signal.VISUAL_ACTIVE, s + 4100, true);
        check(slow.sampleCount() == 12, "gap cannot learn");
        slow.observe(WaveformDetector.Signal.INVALID, s + 4200, true);
        for (int t = 5200; t <= 35200; t += 1000) slow.observe(WaveformDetector.Signal.INVALID, s + t, true);
        check(slow.sampleCount() == 0 && slow.requiredPauseMs(0, 10_000) == AdaptivePausePolicy.DEFAULT_MS, "long invalid clears history");

        for (int i = 0; i < 230; i++) f = cycle(fast, f, 400);
        check(fast.sampleCount() == 200, "bounded history");

        // One quiet frame per cycle is at the sampling limit: it cannot give a duration,
        // but three such cycles are evidence of a fast rhythm and cap the wait.
        AdaptivePausePolicy brief = new AdaptivePausePolicy();
        long at = 0;
        for (int i = 0; i < 3; i++) {
            brief.observe(WaveformDetector.Signal.VISUAL_ACTIVE, at, true);
            brief.observe(WaveformDetector.Signal.VISUAL_ACTIVE, at + 1000, true);
            brief.observe(WaveformDetector.Signal.VISUAL_QUIET, at + 2000, true);
            brief.observe(WaveformDetector.Signal.VISUAL_ACTIVE, at + 3000, true);
            brief.observe(WaveformDetector.Signal.VISUAL_ACTIVE, at + 4000, true);
            at += 5000;
        }
        check(brief.sampleCount() == 0, "one quiet sample cannot give an exact duration");
        check(brief.requiredPauseMs(0, 10_000) == AdaptivePausePolicy.FAST_PAUSE_MS,
                "three brief gaps must cap the wait for a fast speaker, got " + brief.requiredPauseMs(0, 10_000));
        check(brief.requiredPauseMs(-3, 10_000) > brief.requiredPauseMs(0, 10_000),
                "conservative bias still applies on top of a fast rhythm");

        // A single brief gap must not change anything.
        AdaptivePausePolicy single = new AdaptivePausePolicy();
        single.observe(WaveformDetector.Signal.VISUAL_ACTIVE, 0, true);
        single.observe(WaveformDetector.Signal.VISUAL_ACTIVE, 1000, true);
        single.observe(WaveformDetector.Signal.VISUAL_QUIET, 2000, true);
        single.observe(WaveformDetector.Signal.VISUAL_ACTIVE, 3000, true);
        single.observe(WaveformDetector.Signal.VISUAL_ACTIVE, 4000, true);
        check(single.requiredPauseMs(0, 10_000) == AdaptivePausePolicy.DEFAULT_MS, "one short gap is not evidence");

        // A clearly long pause resets the fast-rhythm votes.
        AdaptivePausePolicy recovered = new AdaptivePausePolicy();
        long r = 0;
        for (int i = 0; i < 3; i++) {
            recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r, true);
            recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 1000, true);
            recovered.observe(WaveformDetector.Signal.VISUAL_QUIET, r + 2000, true);
            recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 3000, true);
            recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 4000, true);
            r += 5000;
        }
        recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r, true);
        recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 1000, true);
        for (int i = 2; i <= 6; i++) recovered.observe(WaveformDetector.Signal.VISUAL_QUIET, r + i * 1000L, true);
        recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 7000, true);
        recovered.observe(WaveformDetector.Signal.VISUAL_ACTIVE, r + 8000, true);
        check(recovered.requiredPauseMs(0, 10_000) > AdaptivePausePolicy.FAST_PAUSE_MS,
                "a long pause must clear the fast-rhythm cap");

        fast.reset(); check(fast.sampleCount() == 0, "environment reset");
        boolean rejected = false;
        try { fast.requiredPauseMs(10, 0); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "bias above +9 rejected");
        rejected = false;
        try { fast.requiredPauseMs(-10, 0); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "bias below -9 rejected");
        check(fast.requiredPauseMs(9, 0) < fast.requiredPauseMs(3, 0), "wider range keeps monotonic direction");
        check(fast.requiredPauseMs(-9, 0) > fast.requiredPauseMs(-3, 0), "wider range keeps conservative direction");
        rejected = false;
        try { fast.requiredPauseMs(0, 0, 0.5); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "noise factor below 1 rejected");

        shortSegmentsAreNotFaster();
        longLectureRamp();
        rampIsMonotonicAndContinuous();
        noiseFactorIsIgnored();
        learningStartsAtSecondCycle();
        defaultChangeSafeguards();
        fastRhythmFloorSurvivesBias();
        check(AdaptivePausePolicy.minimumEvidenceMs() == 1000,
                "minimum observed quiet evidence is 1000 ms, got " + AdaptivePausePolicy.minimumEvidenceMs());

        System.out.println("AdaptivePausePolicy: 1800 cold start, 2nd-cycle learning, safeguards, bounds passed");
    }
}
