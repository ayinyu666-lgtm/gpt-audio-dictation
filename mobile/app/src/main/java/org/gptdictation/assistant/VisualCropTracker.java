package org.gptdictation.assistant;

import java.util.Arrays;

/**
 * Decides what to do with the measured waveform crop rectangle of one recording segment.
 * Pure class: no Android APIs, no UI assumptions beyond the rect values the caller measured.
 *
 * The crop is derived from live button bounds, so a keyboard, a reflow or a different
 * recording layout legitimately moves it. A moved crop no longer matches the learned
 * visual baseline, so callers must re-arm sampling rather than silently keep a stale
 * reference. Re-arming restarts learning, which makes a pause decision impossible until
 * enough fresh frames arrive; the caller's maximum-duration fallback still applies.
 *
 * Rectangles that keep moving during one segment mean the reference location is not
 * reliable enough to learn from. Only that case disables visual pause detection for the
 * rest of the segment; it never disables the timed fallback.
 */
final class VisualCropTracker {
    enum Action {
        /** Keep sampling the same rect; nothing was learned from a different one. */
        SAMPLE,
        /** Rect moved: drop the learned baseline, adopt the new rect and start over. */
        REARM,
        /** Rect moved too often to learn anything: fall back to the time limit only. */
        DISABLE,
        /** No usable rect right now; wait for valid nodes. */
        WAIT
    }

    /** Movement budget per segment. Three covers a keyboard opening plus two reflows. */
    static final int MAX_REARMS = 3;

    private static final int LENGTH = 4;

    private int[] segment;
    private int[] session;
    private int rearms;
    private boolean disabled;

    /** Call when the environment changes: screen, window, theme or capture route. */
    void resetSession() {
        session = null;
        resetSegment();
    }

    /** Call when a new recording segment starts. */
    void resetSegment() {
        segment = null;
        rearms = 0;
        disabled = false;
    }

    /**
     * crop is left, top, right, bottom in screen pixels, or null when the caller could
     * not measure it (missing nodes, off-screen). Call exactly once per tick.
     */
    Action observe(int[] crop) {
        if (!valid(crop)) return Action.WAIT;
        if (disabled) return Action.DISABLE;
        if (segment == null) {
            boolean sameAsSession = session != null && Arrays.equals(session, crop);
            segment = crop.clone();
            session = crop.clone();
            return sameAsSession ? Action.SAMPLE : Action.REARM;
        }
        if (Arrays.equals(segment, crop)) return Action.SAMPLE;
        if (++rearms > MAX_REARMS) {
            disabled = true;
            return Action.DISABLE;
        }
        segment = crop.clone();
        session = crop.clone();
        return Action.REARM;
    }

    /** Current rect of this segment, or null when nothing usable was measured yet. */
    int[] current() { return segment == null ? null : segment.clone(); }

    private static boolean valid(int[] rect) {
        return rect != null && rect.length == LENGTH && rect[0] < rect[2] && rect[1] < rect[3];
    }
}
