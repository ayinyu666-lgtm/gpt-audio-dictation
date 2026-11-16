package org.gptdictation.assistant;

/** Pure geometry and freshness checks for ephemeral waveform samples. */
final class WaveformSamplingPolicy {
    /**
     * Sampling period. Every decision latency is counted in SAMPLES (the warm-up is eight of
     * them, a recovered frame needs two more), so at one sample per second the detector spent
     * its first eight seconds of a fifteen-second segment unable to say anything. Halving the
     * period halves all of those waits while still being cheap: one screenshot every 500 ms.
     */
    static final long INTERVAL_MS = 500, TIMEOUT_MS = 2000, FRESH_MS = 1500;
    enum Trigger { WAIT, VISUAL_PAUSE, TIMED_FALLBACK }
    static Trigger trigger(boolean deadlineReached, boolean fresh, boolean visualPause) {
        if (deadlineReached) return Trigger.TIMED_FALLBACK;
        return fresh && visualPause ? Trigger.VISUAL_PAUSE : Trigger.WAIT;
    }

    // cancel/stop are left, top, right, bottom in screen pixels; returns L,T,R,B.
    static int[] crop(int[] cancel, int[] stop, int screenWidth, int screenHeight) {
        return crop(cancel, stop, screenWidth, screenHeight, sourceScale);
    }

    /**
     * Scale of the frame source that will consume this crop, in source pixels per screen
     * pixel. Defaults to 1.0 (accessibility screenshots are full resolution) and is set by
     * whichever source is active. Kept as a plain field rather than a call into
     * {@code ProjectionCapture} so this class stays Android-free and host-testable.
     */
    private static volatile float sourceScale = 1.0f;

    /** Called by the active frame source; 1.0 restores screen-resolution semantics. */
    static void setSourceScale(float scale) {
        if (scale > 0 && scale <= 1) sourceScale = scale;
    }

    /**
     * Scale-aware crop. The returned rectangle stays in SCREEN pixels; {@code sourceScale}
     * only tightens the guards so an unusable source crop can never be produced.
     *
     * <p>The old floors ({@code width>=80}, {@code height>=24}) were written for a
     * half-resolution frame source, where 80x24 screen pixels became 40x12 source pixels and
     * just cleared {@code WaveformDetector}'s {@code width>=24 || height>=12} guard. At quarter
     * resolution the same crop becomes 20x6, so every frame is rejected as
     * {@code invalid_frame} — silently and permanently on narrow-strip devices.
     *
     * <p>Floor derivation: {@code minScreenW = max(80, ceil(24/sourceScale))},
     * {@code minScreenH = max(24, ceil(12/sourceScale))}. At 0.25 that is 96x48 screen pixels
     * (24x12 source); at 0.5 it is 80x24 (40x12 source); at 1.0 it is 80x24.
     *
     * <p>The floor is applied to the FINAL rectangle, i.e. after the {@code pad} margin is
     * subtracted from both sides, which is what the consumer actually receives.
     */
    static int[] crop(int[] cancel, int[] stop, int screenWidth, int screenHeight,
                      float sourceScale) {
        if (cancel == null || stop == null || cancel.length != 4 || stop.length != 4
                || screenWidth <= 0 || screenHeight <= 0) return null;
        if (sourceScale <= 0 || sourceScale > 1) return null;
        int minScreenW = Math.max(80, (int) Math.ceil(24.0 / sourceScale));
        int minScreenH = Math.max(24, (int) Math.ceil(12.0 / sourceScale));
        if (cancel[0] < 0 || cancel[1] < 0 || cancel[2] > screenWidth || cancel[3] > screenHeight
                || stop[0] < 0 || stop[1] < 0 || stop[2] > screenWidth || stop[3] > screenHeight
                || cancel[0] >= cancel[2] || cancel[1] >= cancel[3]
                || stop[0] >= stop[2] || stop[1] >= stop[3]) return null;
        int gap = stop[0] - cancel[2], height = stop[3] - stop[1];
        // Cheap pre-check: the button gap can never yield a usable strip.
        if (gap < 80 || height < 12
                || Math.min(cancel[3], stop[3]) <= Math.max(cancel[1], stop[1])) return null;
        // Use the WHOLE waveform strip between the two round buttons, not a narrow
        // slice of it. The old 30%-wide, button-height crop produced activity values
        // in the 0.6-0.96 range on a real phone, where the detector's own sanity limit
        // rejects anything above 0.85 — so a large share of real frames were thrown
        // away as "unexpected_waveform" and pause detection could never complete.
        // A wider crop includes empty margins, keeping activity in a usable band.
        // The send button's height is the waveform's full scale: that is how tall a bar
        // is when the signal peaks. Normalising by it makes "activity" a true fraction
        // of full scale. The old crop used the cancel button box, which is taller than
        // the bars, so every value came out compressed and noisy.
        int fullScale = Math.max(24, Math.min(stop[3] - stop[1], cancel[3] - cancel[1]));
        // A few pixels of margin on every side keeps anti-aliasing and the rounded
        // button edges out of the measurement without shrinking the usable width.
        int pad = Math.max(2, Math.round(fullScale * 0.04f));
        int left = cancel[2] + pad;
        int right = stop[0] - pad;
        int top = Math.max(0, Math.min(cancel[1], stop[1]) - pad);
        int bottom = Math.min(screenHeight, Math.max(cancel[3], stop[3]) + pad);
        left = Math.max(0, left); right = Math.min(screenWidth, right);
        if (right - left < minScreenW || bottom - top < minScreenH
                || left < cancel[2] || right > stop[0]) return null;
        return new int[]{left, top, right, bottom};
    }

    static boolean fresh(long now, long sampleTime) {
        return sampleTime > 0 && now >= sampleTime && now - sampleTime <= FRESH_MS;
    }

    /**
     * Whether a snapshot may be used to verify a screenshot that is arriving now.
     *
     * The old test was `lastSnapshotAt < captureRequestedAt` (or no snapshot at all). That
     * is not strict enough: the tick that issued the request also stored its own scan with
     * the SAME timestamp, so `lastSnapshotAt == captureRequestedAt` passed the check and the
     * callback verified against a tree that was read BEFORE the screenshot was even taken.
     * A crop that moved in between was then compared with its own stale coordinates.
     *
     * Snapshots are stamped with a monotonically increasing serial taken at scan time.
     * Requiring `snapshotSerial > requestedSerial` means the tree must have been read after
     * the request. When no such snapshot exists the caller falls back to a fresh scan.
     *
     * @param snapshotSerial   serial of the snapshot the tick currently holds, -1 if none
     * @param requestedSerial  serial that was current when the screenshot was requested
     */
    static boolean snapshotNewerThanRequest(long snapshotSerial, long requestedSerial) {
        return snapshotSerial > requestedSerial;
    }

    static boolean callbackCurrent(int requestRun, int run, int requestSegment, int segment,
                                   int requestWindow, int window, boolean recording, boolean timedOut) {
        return recording && !timedOut && requestRun == run && requestSegment == segment && requestWindow == window;
    }
}
