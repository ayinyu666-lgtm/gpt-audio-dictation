package org.gptdictation.assistant;

/**
 * The screen-space crop to frame-space size conversion.
 *
 * <p>This used to live inline in {@code DictationService} and was the 0.9.0 bug: a
 * projection frame was compared against the full-resolution screen crop, so every frame
 * failed the length check and was discarded as {@code capture_expired} (260 times on the
 * device, with zero {@code wave sig=} lines). Pulling the arithmetic out here makes the
 * exact rule testable without an Android device.
 *
 * <p>The rule mirrors the copy path in {@code ProjectionCapture.request()} exactly:
 * {@code round(right * scale) - round(left * scale)}, clamped to at least one pixel. It is
 * deliberately NOT {@code round((right - left) * scale)} — the two disagree by one pixel
 * whenever a scaled edge lands on a .5 boundary, which is roughly a quarter of all crops.
 *
 * <p>Pure Java on purpose: no Android types, so the host JVM tests can cover it.
 */
final class ProjectionGeometry {

    private ProjectionGeometry() { }

    /** Expected frame width for a screen-space crop, given the projection scale. */
    static int expectedWidth(int screenLeft, int screenRight, double scale) {
        return Math.max(1, (int) Math.round(screenRight * scale) - (int) Math.round(screenLeft * scale));
    }

    /** Expected frame height for a screen-space crop, given the projection scale. */
    static int expectedHeight(int screenTop, int screenBottom, double scale) {
        return Math.max(1, (int) Math.round(screenBottom * scale) - (int) Math.round(screenTop * scale));
    }

    /**
     * True when the delivered frame size matches what the crop should have produced.
     *
     * @param screenCrop {left, top, right, bottom} in physical screen pixels
     * @param scale      source pixels per screen pixel: {@code ProjectionCapture.SCALE}
     *                   (0.25 for the quarter-resolution projection), or 1.0 for
     *                   accessibility screenshots, which are full resolution
     * @param gotWidth   width actually returned by the frame source
     * @param gotHeight  height actually returned by the frame source
     */
    static boolean sizeMatches(int[] screenCrop, double scale, int gotWidth, int gotHeight) {
        if (screenCrop == null || screenCrop.length != 4) return false;
        return gotWidth == expectedWidth(screenCrop[0], screenCrop[2], scale)
                && gotHeight == expectedHeight(screenCrop[1], screenCrop[3], scale);
    }
}
