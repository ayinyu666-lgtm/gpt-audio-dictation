package org.gptdictation.assistant;

/**
 * Host JVM tests for {@link DeadlinePressurePolicy}. Run with javac/java, no JUnit.
 *
 * <p>Every assertion prints one PASS/FAIL line; the process exits with code 1 if any
 * assertion fails. The production class is frozen, so these checks pin the accepted
 * curve: the 30 s cap through ARM_START, a smoothstep down to the learned pause at
 * FULL_AT, and a final smoothstep down to the 1200 ms floor at the ceiling.</p>
 */
public final class DeadlinePressurePolicyTest {
    private static int passed;
    private static int failed;

    private DeadlinePressurePolicyTest() {
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("PASS " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void near(long actual, long expected, long tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance,
                what + " (expected " + expected + " +/- " + tolerance + " ms, got " + actual + ")");
    }

    private static void nearD(double actual, double expected, double tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance,
                what + " (expected " + expected + " +/- " + tolerance + ", got " + actual + ")");
    }

    /** The frozen contract: moving any of these changes the accepted design. */
    private static void frozenConstants() {
        check(DeadlinePressurePolicy.CAP_MS == 30_000L, "CAP_MS is 30000");
        check(DeadlinePressurePolicy.ARM_START == 0.40, "ARM_START is 0.40");
        check(DeadlinePressurePolicy.FULL_AT == 0.95, "FULL_AT is 0.95");
        check(DeadlinePressurePolicy.FLOOR_MS == 1_200L, "FLOOR_MS is 1200");
        check(DeadlinePressurePolicy.BIAS_SHIFT == 0.015, "BIAS_SHIFT is 0.015");
    }

    /** Requirement 1: the cap still applies at and before ARM_START. */
    private static void capUntilArmStart() {
        double[] fractions = {0.0, 0.2, 0.4};
        for (double fraction : fractions) {
            near(DeadlinePressurePolicy.requiredPauseMs(fraction, 1800, 0),
                    DeadlinePressurePolicy.CAP_MS, 0L,
                    "f=" + fraction + " bias=0 returns exactly CAP_MS");
        }
        // The join is inclusive on the cap side: 0.40 is still the cap, not the ramp.
        check(DeadlinePressurePolicy.requiredPauseMs(0.40, 1800, 0) == DeadlinePressurePolicy.CAP_MS,
                "ARM_START is inclusive (f=0.40 is still CAP_MS)");
        check(DeadlinePressurePolicy.requiredPauseMs(0.40 - 1e-9, 1800, 0) == DeadlinePressurePolicy.CAP_MS,
                "just below ARM_START is still CAP_MS");
    }

    /** Requirement 2: the curve may never rise as the segment progresses. */
    private static void monotoneSweep(int bias) {
        long previous = DeadlinePressurePolicy.requiredPauseMs(0.0, 1800, bias);
        int violations = 0;
        String firstViolation = null;
        for (int i = 1; i <= 100; i++) {
            double fraction = i / 100.0;
            long value = DeadlinePressurePolicy.requiredPauseMs(fraction, 1800, bias);
            if (value > previous) {
                violations++;
                if (firstViolation == null) {
                    firstViolation = " at f=" + fraction + ": " + previous + " -> " + value;
                }
            }
            previous = value;
        }
        check(violations == 0, "monotone non-increasing over f=0..1 step 0.01 at bias " + bias
                + " (101 samples)" + (firstViolation == null ? "" : firstViolation));
    }

    /** Requirement 3: no cliff at either join (the joins have zero slope by design). */
    private static void continuityAtJoins() {
        double[] joins = {DeadlinePressurePolicy.ARM_START, DeadlinePressurePolicy.FULL_AT};
        for (double join : joins) {
            long below = DeadlinePressurePolicy.requiredPauseMs(join - 0.001, 1800, 0);
            long at = DeadlinePressurePolicy.requiredPauseMs(join, 1800, 0);
            long above = DeadlinePressurePolicy.requiredPauseMs(join + 0.001, 1800, 0);
            check(Math.abs(at - below) <= 1,
                    "no step > 1 ms across f=" + join + " from below (" + below + " -> " + at + ")");
            check(Math.abs(above - at) <= 1,
                    "no step > 1 ms across f=" + join + " to above (" + at + " -> " + above + ")");
            long tightBelow = DeadlinePressurePolicy.requiredPauseMs(join - 0.0001, 1800, 0);
            long tightAbove = DeadlinePressurePolicy.requiredPauseMs(join + 0.0001, 1800, 0);
            check(Math.abs(tightAbove - tightBelow) <= 1,
                    "continuous at f=" + join + " at 0.0001 resolution (" + tightBelow + " -> " + tightAbove + ")");
        }
    }

    /** Requirement 4: FULL_AT lands on the learned pause; the ceiling lands on the floor. */
    private static void fullAtAndCeiling() {
        near(DeadlinePressurePolicy.requiredPauseMs(0.95, 1800, 0), 1800L, 1L,
                "f=0.95 with normal=1800 returns exactly 1800");
        check(DeadlinePressurePolicy.requiredPauseMs(1.0, 1800, 0) == DeadlinePressurePolicy.FLOOR_MS,
                "f=1.0 returns exactly FLOOR_MS 1200");
        // Past FULL_AT the requirement must keep falling below the learned pause.
        check(DeadlinePressurePolicy.requiredPauseMs(0.98, 1800, 0) < 1800,
                "f=0.98 hunts below the learned pause");
        check(DeadlinePressurePolicy.requiredPauseMs(0.95, 30_000, 0) == DeadlinePressurePolicy.CAP_MS,
                "normal above CAP_MS is clamped to CAP_MS at FULL_AT");
    }

    /** Requirement 5: extreme inputs still land inside [FLOOR_MS, CAP_MS]. */
    private static void clampExtremes() {
        long[] normals = {0L, -500L, 999_999L};
        double[] fractions = {-5.0, -0.001, 0.0, 0.25, 0.6, 0.95, 1.0, 1.5, 5.0};
        int[] biases = {-9, 0, 9};
        int outside = 0;
        int samples = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        String firstOutside = null;
        for (long normal : normals) {
            for (double fraction : fractions) {
                for (int bias : biases) {
                    long value = DeadlinePressurePolicy.requiredPauseMs(fraction, normal, bias);
                    samples++;
                    if (value < DeadlinePressurePolicy.FLOOR_MS || value > DeadlinePressurePolicy.CAP_MS) {
                        outside++;
                        if (firstOutside == null) {
                            firstOutside = " first: normal=" + normal + " f=" + fraction
                                    + " bias=" + bias + " -> " + value;
                        }
                    }
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            }
        }
        check(outside == 0, "all " + samples + " extreme combinations stay in [1200, 30000]"
                + " (observed [" + min + ", " + max + "])" + (firstOutside == null ? "" : firstOutside));
        check(DeadlinePressurePolicy.requiredPauseMs(-5, 0, 0) == DeadlinePressurePolicy.CAP_MS,
                "normal=0 and f=-5 clamps to CAP_MS");
        check(DeadlinePressurePolicy.requiredPauseMs(5, 999_999, 0) == DeadlinePressurePolicy.FLOOR_MS,
                "normal=999999 and f=+5 clamps to FLOOR_MS");
        check(DeadlinePressurePolicy.requiredPauseMs(5, 999_999, 0) <= DeadlinePressurePolicy.CAP_MS,
                "f=+5 never exceeds CAP_MS");
    }

    /** Index of the first 0.001 step that reaches FLOOR_MS, or -1 if never in [0, 1]. */
    private static int firstFloorFraction(int bias) {
        for (int i = 0; i <= 1000; i++) {
            if (DeadlinePressurePolicy.requiredPauseMs(i / 1000.0, 1800, bias)
                    == DeadlinePressurePolicy.FLOOR_MS) {
                return i;
            }
        }
        return -1;
    }

    /** Requirement 6: positive bias is more aggressive and reaches the floor earlier. */
    private static void biasMonotonicity() {
        long aggressive = DeadlinePressurePolicy.requiredPauseMs(0.7, 1800, 9);
        long neutral = DeadlinePressurePolicy.requiredPauseMs(0.7, 1800, 0);
        long conservative = DeadlinePressurePolicy.requiredPauseMs(0.7, 1800, -9);
        check(aggressive <= neutral, "f=0.7: bias +9 (" + aggressive + ") <= bias 0 (" + neutral + ")");
        check(neutral <= conservative, "f=0.7: bias 0 (" + neutral + ") <= bias -9 (" + conservative + ")");
        check(aggressive < conservative,
                "f=0.7: positive bias is strictly more aggressive (" + aggressive + " < " + conservative + ")");

        int fastFirst = firstFloorFraction(9);
        int slowFirst = firstFloorFraction(-9);
        check(fastFirst >= 0, "bias +9 reaches FLOOR_MS at some fraction (first at "
                + (fastFirst / 1000.0) + ")");
        // A conservative bias never enters the sub-FULL_AT hunting branch within [0, 1],
        // so "never" counts as a higher fraction than any finite +9 entry point.
        check(slowFirst < 0 || slowFirst > fastFirst,
                "bias +9 reaches FLOOR_MS at a lower fraction (" + (fastFirst / 1000.0)
                        + ") than bias -9 (" + (slowFirst < 0 ? "never in [0,1]" : String.valueOf(slowFirst / 1000.0)) + ")");
        check(fastFirst >= 0
                        && DeadlinePressurePolicy.requiredPauseMs(fastFirst / 1000.0, 1800, -9)
                                > DeadlinePressurePolicy.FLOOR_MS,
                "at the +9 floor fraction, bias -9 is still above the floor");
        check(DeadlinePressurePolicy.requiredPauseMs(1.0, 1800, -9) > 1800,
                "bias -9 never hunts below the learned pause, even at the ceiling");
    }

    /** Requirement 7: the policy is ceiling-agnostic because only the fraction enters. */
    private static void ceilingAgnostic() {
        double at200 = DeadlinePressurePolicy.elapsedFraction(40_000, 200_000);
        double at300 = DeadlinePressurePolicy.elapsedFraction(60_000, 300_000);
        nearD(at200, 0.8, 1e-12, "elapsedFraction(40000, 200000) is 0.8");
        nearD(at300, 0.8, 1e-12, "elapsedFraction(60000, 300000) is 0.8");
        check(at200 == at300, "identical progress gives bit-identical fractions");
        near(DeadlinePressurePolicy.requiredPauseMs(at200, 1800, 0),
                DeadlinePressurePolicy.requiredPauseMs(at300, 1800, 0), 0L,
                "200 s and 300 s ceilings give the same required pause at the same fraction");

        double at400 = DeadlinePressurePolicy.elapsedFraction(120_000, 400_000);
        nearD(at400, 0.7, 1e-12, "elapsedFraction(120000, 400000) is 0.7");
        near(DeadlinePressurePolicy.requiredPauseMs(at400, 1800, 0),
                DeadlinePressurePolicy.requiredPauseMs(0.7, 1800, 0), 0L,
                "400 s ceiling agrees with the literal 0.7 fraction");
    }

    /** Requirement 8: elapsedFraction boundary behaviour. */
    private static void elapsedFractionBasics() {
        nearD(DeadlinePressurePolicy.elapsedFraction(200_000, 200_000), 0.0, 0.0,
                "remaining=maxMs gives 0.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(0, 200_000), 1.0, 0.0,
                "remaining=0 gives 1.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(-5_000, 200_000), 1.0, 0.0,
                "negative remaining clamps to 1.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(250_000, 200_000), 0.0, 0.0,
                "remaining above maxMs clamps to 0.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(100_000, 0), 0.0, 0.0,
                "maxMs=0 gives 0.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(100_000, -1), 0.0, 0.0,
                "maxMs<0 gives 0.0");
        nearD(DeadlinePressurePolicy.elapsedFraction(0, 0), 0.0, 0.0,
                "maxMs=0 at remaining=0 still gives 0.0");
    }

    public static void main(String[] args) {
        frozenConstants();
        capUntilArmStart();
        monotoneSweep(0);
        monotoneSweep(-9);
        monotoneSweep(9);
        continuityAtJoins();
        fullAtAndCeiling();
        clampExtremes();
        biasMonotonicity();
        ceilingAgnostic();
        elapsedFractionBasics();

        int total = passed + failed;
        System.out.println("DeadlinePressurePolicy: " + total + " assertions, "
                + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
