package org.gptdictation.assistant;

/**
 * Host-only checks for the screen-crop to frame-size conversion.
 *
 * <p>This is the regression test for the 0.9.0 device failure: the projection path returned
 * a legitimate half-resolution buffer but DictationService compared it against the
 * full-resolution screen crop, so every frame was rejected as {@code capture_expired}
 * (260 occurrences, zero processed frames) and pause detection never ran.
 *
 * <p>No Android types are used, so this runs on the plain host JVM.
 */
public final class ProjectionGeometryTest {
    private static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    private static void checkEq(int actual, int expected, String what) {
        if (actual != expected) throw new AssertionError(what + ": expected " + expected + " got " + actual);
    }

    public static void main(String[] args) {
        final double HALF = 0.5;

        // ---- all four left/right parity combinations at scale 0.5 ------------------
        // With scale 0.5, Math.round(n*0.5) == ceil(n/2) for n >= 0, so the correct
        // width is ceil(right/2) - ceil(left/2). Concrete values, exact assertions.
        checkEq(ProjectionGeometry.expectedWidth(0, 100, HALF), 50, "even/even l=0 r=100");
        checkEq(ProjectionGeometry.expectedWidth(2, 200, HALF), 99, "even/even l=2 r=200");
        checkEq(ProjectionGeometry.expectedWidth(1, 101, HALF), 50, "odd/odd l=1 r=101");
        checkEq(ProjectionGeometry.expectedWidth(3, 201, HALF), 99, "odd/odd l=3 r=201");
        checkEq(ProjectionGeometry.expectedWidth(1, 100, HALF), 49, "odd/even l=1 r=100");
        checkEq(ProjectionGeometry.expectedWidth(3, 200, HALF), 98, "odd/even l=3 r=200");
        checkEq(ProjectionGeometry.expectedWidth(0, 101, HALF), 51, "even/odd l=0 r=101");
        checkEq(ProjectionGeometry.expectedWidth(2, 201, HALF), 100, "even/odd l=2 r=201");

        // ---- heights use the same rule -------------------------------------------
        checkEq(ProjectionGeometry.expectedHeight(0, 100, HALF), 50, "height even/even");
        checkEq(ProjectionGeometry.expectedHeight(1, 101, HALF), 50, "height odd/odd");
        checkEq(ProjectionGeometry.expectedHeight(1, 100, HALF), 49, "height odd/even");
        checkEq(ProjectionGeometry.expectedHeight(0, 101, HALF), 51, "height even/odd");

        // ---- the 0.9.0 bug: old formula differs by exactly one pixel -------------
        // round((right-left)*scale) = round(99*0.5) = round(49.5) = 50
        // correct            = round(100*0.5) - round(1*0.5) = 50 - 1 = 49
        // The old value is 1 too large, so a perfect 49-pixel frame was rejected.
        int oldBuggy = (int) Math.round((100 - 1) * HALF);
        int correct = ProjectionGeometry.expectedWidth(1, 100, HALF);
        checkEq(oldBuggy, 50, "old formula value for the 0.9.0 case");
        checkEq(correct, 49, "correct half-res width for the 0.9.0 case");
        check(correct != oldBuggy, "the 0.9.0 bug must reproduce: old formula must differ by 1");
        check(oldBuggy - correct == 1, "the 0.9.0 discrepancy must be exactly one pixel");

        // Real device crop from the logs: [158, 2284, 827, 2385].
        checkEq(ProjectionGeometry.expectedWidth(158, 827, HALF), 335, "device crop width");
        checkEq(ProjectionGeometry.expectedHeight(2284, 2385, HALF), 51, "device crop height");
        check(ProjectionGeometry.sizeMatches(new int[]{158, 2284, 827, 2385}, HALF, 335, 51),
                "real device half-res frame is accepted");

        // ---- clamping: a one-pixel-wide crop must not become 0 -------------------
        checkEq(ProjectionGeometry.expectedWidth(5, 6, HALF), 1, "1 px crop clamps to 1");
        checkEq(ProjectionGeometry.expectedHeight(5, 6, HALF), 1, "1 px crop clamps to 1 (height)");
        checkEq(ProjectionGeometry.expectedWidth(0, 1, HALF), 1, "edge 1 px crop clamps to 1");

        // ---- sizeMatches: the real failure mode is a one-pixel miss --------------
        int[] crop = {158, 2284, 827, 2385};
        check(ProjectionGeometry.sizeMatches(crop, HALF, 335, 51), "exact match accepted");
        check(!ProjectionGeometry.sizeMatches(crop, HALF, 336, 51), "width off by +1 rejected");
        check(!ProjectionGeometry.sizeMatches(crop, HALF, 334, 51), "width off by -1 rejected");
        check(!ProjectionGeometry.sizeMatches(crop, HALF, 335, 52), "height off by +1 rejected");
        check(!ProjectionGeometry.sizeMatches(crop, HALF, 335, 50), "height off by -1 rejected");
        // The old screen-space comparison: 827-158 = 669, which is not 335.
        check(!ProjectionGeometry.sizeMatches(crop, HALF, 669, 101),
                "screen-space size must be rejected for a half-res source");

        // ---- scale 1.0 full resolution stays exact ------------------------------
        check(ProjectionGeometry.sizeMatches(crop, 1.0, 669, 101), "full-res frame accepted at scale 1.0");
        check(!ProjectionGeometry.sizeMatches(crop, 1.0, 668, 101), "full-res off by one rejected");
        checkEq(ProjectionGeometry.expectedWidth(158, 827, 1.0), 669, "scale 1.0 width is exact");

        // ---- malformed input is not a match -------------------------------------
        check(!ProjectionGeometry.sizeMatches(null, HALF, 335, 51), "null crop rejected");
        check(!ProjectionGeometry.sizeMatches(new int[]{1, 2, 3}, HALF, 1, 1), "short crop rejected");

        System.out.println("ProjectionGeometry: parity table, 0.9.0 one-pixel bug, clamping and sizeMatches passed");
    }
}
