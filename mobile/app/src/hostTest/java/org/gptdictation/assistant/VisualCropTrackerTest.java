package org.gptdictation.assistant;

/** Host-only checks for keeping a usable waveform reference across layout movement. */
public final class VisualCropTrackerTest {
    private static final int[] FIRST = {559, 2299, 762, 2371};
    private static final int[] MOVED = {540, 2200, 743, 2272};
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void expect(VisualCropTracker.Action actual, VisualCropTracker.Action expected) {
        check(actual == expected, "expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        VisualCropTracker t = new VisualCropTracker();
        expect(t.observe(null), VisualCropTracker.Action.WAIT);
        expect(t.observe(new int[]{1, 1}), VisualCropTracker.Action.WAIT);
        expect(t.observe(new int[]{9, 9, 1, 1}), VisualCropTracker.Action.WAIT);
        expect(t.observe(FIRST), VisualCropTracker.Action.REARM);
        expect(t.observe(FIRST), VisualCropTracker.Action.SAMPLE);
        check(t.current() != null && t.current()[0] == FIRST[0], "stable rect kept");
        expect(t.observe(MOVED), VisualCropTracker.Action.REARM);
        expect(t.observe(MOVED), VisualCropTracker.Action.SAMPLE);
        expect(t.observe(MOVED), VisualCropTracker.Action.SAMPLE);
        // A keyboard opening mid-segment must not permanently disable pause detection.
        expect(t.observe(FIRST), VisualCropTracker.Action.REARM);
        expect(t.observe(FIRST), VisualCropTracker.Action.SAMPLE);
        expect(t.observe(MOVED), VisualCropTracker.Action.REARM);
        expect(t.observe(FIRST), VisualCropTracker.Action.DISABLE);
        expect(t.observe(FIRST), VisualCropTracker.Action.DISABLE);
        expect(t.observe(MOVED), VisualCropTracker.Action.DISABLE);
        t.resetSegment();
        // The last rect before giving up was not the one now measured: learn it again.
        expect(t.observe(FIRST), VisualCropTracker.Action.REARM);
        expect(t.observe(FIRST), VisualCropTracker.Action.SAMPLE);

        // A rect that repeats across segments needs a fresh reference only once.
        t = new VisualCropTracker();
        expect(t.observe(FIRST), VisualCropTracker.Action.REARM);
        t.resetSegment();
        expect(t.observe(FIRST), VisualCropTracker.Action.SAMPLE);
        t.resetSegment();
        expect(t.observe(MOVED), VisualCropTracker.Action.REARM);
        t.resetSession();
        expect(t.observe(MOVED), VisualCropTracker.Action.REARM);

        // Null nodes must not be mistaken for movement.
        t.resetSegment();
        expect(t.observe(null), VisualCropTracker.Action.WAIT);
        expect(t.observe(MOVED), VisualCropTracker.Action.SAMPLE);
        System.out.println("VisualCropTracker: layout movement, recovery, repeated rects and give-up passed");
    }
}
