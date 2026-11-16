package org.gptdictation.assistant;

import android.graphics.Rect;

/**
 * A source of ephemeral waveform crops.
 *
 * <p>Two implementations exist: {@link WaveformCapture} pulls an accessibility screenshot
 * (slow, one outstanding capture at a time) and {@link ProjectionCapture} reads the newest
 * frame of a running MediaProjection (synchronous, no IPC). Callers keep the same
 * single-flight contract either way.
 */
interface FrameSource {
    /**
     * Ask for one crop. Returns true when the crop was accepted; false when it cannot be
     * served (unsupported, busy, or no frame yet), in which case the callback has already
     * been invoked with a null frame and an error tag.
     */
    boolean request(Rect crop, WaveformCapture.Callback callback);

    /** Stop waiting for an outstanding request without changing configured state. */
    void abandon();

    /** Release any threads or native resources owned by this source. */
    void shutdown();
}
