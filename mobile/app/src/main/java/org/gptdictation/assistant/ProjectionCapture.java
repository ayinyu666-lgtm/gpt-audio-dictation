package org.gptdictation.assistant;

import android.graphics.Rect;
import android.os.SystemClock;

/**
 * Frame source backed by the newest full-screen frame published by {@link ProjectionService}.
 *
 * <p>This class never talks to the projection service over IPC. The producer stores the raw
 * RGBA bytes of the newest frame, and a request converts only the requested sub-rectangle to
 * ARGB ints synchronously on the caller's thread. That makes the round trip cost a small
 * memcpy plus a crop-sized swizzle instead of a binder call, which is the whole point of the
 * MediaProjection path.
 *
 * <p><b>Resolution contract (honest, never upscaled).</b> The virtual display in
 * {@link ProjectionService} renders at {@link #SCALE} of the physical screen, so frames here
 * are genuinely {@code SCALE} times smaller. Callers pass a crop in PHYSICAL SCREEN PIXELS
 * (the space {@code getBoundsInScreen()} reports); {@link #request} scales that rectangle
 * into frame space, clamps it to the frame bounds, copies exactly that region, and reports its
 * TRUE width/height. It never resamples, never fabricates pixels and never claims to be
 * full-resolution. Consumers that need to compare a screen-space expectation with the actual
 * source size must use {@link #scale()} or {@link #lastFrameSize()} explicitly.
 *
 * <p><b>Memory.</b> The producer keeps exactly two reusable raw byte buffers and swaps them
 * through {@link #publishRaw} (the previous one is handed back for reuse), so a whole run
 * costs two half-resolution buffers instead of one {@code int[]} per frame. A full-frame
 * {@code int[]} is never created: the only int array allocated is the crop itself, when a
 * request actually asks for one.
 *
 * <p><b>Health.</b> {@link #isHealthy()} is the fallback signal for {@code DictationService}:
 * false when the service is not running, when no frame was published within
 * {@link #STARTUP_GRACE_MS} of being asked to start, or when the newest frame is older than
 * {@link #STALL_TIMEOUT_MS}. Callers can then switch to {@link WaveformCapture} without
 * changing any recording state.
 */
final class ProjectionCapture implements FrameSource {
    /**
     * The virtual display renders at a quarter of the screen size.
     *
     * <p>Why 0.25 and not 0.5: the waveform crop is 669x101 screen pixels (measured on the
     * Honor Magic4: cancel [60,2287][155,2382], stop [830,2299][902,2371], padded), so at
     * 0.25 it is still 167x25 source pixels. {@code WaveformDetector} rejects a frame below
     * 24x12 and every one of its verdicts is a RATIO of the crop height or width
     * ({@code TALL_OF_PEAK=0.62}, {@code MIN_TALL_ABSOLUTE=0.45}, {@code activity}=p75 of
     * per-column extents, {@code occupied>=width/16}), so halving the linear resolution
     * changes the quantisation step, not the measured quantity: 25 rows still give 22 usable
     * levels between the 0.025 floor and the 0.92 cap, and a 0.45 tall bar spans 11 of 25
     * rows. Going to 0.10 would leave only 10 rows and the detector would start rejecting
     * legitimate frames as {@code invalid_frame}.
     *
     * <p>Cost: the mirrored pixel count falls from 561x1221 (685k px) to 281x611 (172k px),
     * a 4x reduction in VirtualDisplay composition and SurfaceFlinger bandwidth, which is
     * the dominant CPU cost of the projection path.
     */
    static final float SCALE = 0.25f;

    /*
     * There is deliberately NO sampling-interval constant here.
     *
     * The cadence is owned entirely by DictationService: requestWaveform() runs inside
     * tick(), and tickIntervalMs() re-arms only after the tick body finishes, so the real
     * spacing between request() calls is (tick period + tick body time) = 200 ms on the
     * fast path / 400 ms on the normal path / 250 ms when an accessibility event kicks the
     * loop. Any interval constant smaller than the tick period could never bind, so it would
     * be a lie that reads like an implemented feature. Lowering the capture cost (one
     * 167x25 copy, ~17 KB) is what makes a shorter tick affordable; changing the tick itself
     * is a DictationService decision, not this class's.
     */

    /**
     * Startup timeout: how long after {@link #setRunning(boolean)} the producer may take to
     * deliver its first frame before this source declares itself unhealthy. This is a
     * timeout, not a quality threshold. A healthy ImageReader on the target device publishes
     * within milliseconds; 3 s is many frame intervals of slack.
     */
    static final long STARTUP_GRACE_MS = 3000;

    /**
     * Stalled-producer timeout. A MediaProjection producer only publishes when the screen
     * changes, so a still screen legitimately produces no new frame; this bound is much
     * larger than any sampling interval so "nothing changed" is not misread as "dead".
     * It only catches a producer that has genuinely stopped delivering.
     */
    static final long STALL_TIMEOUT_MS = 30000;

    /**
     * How recently {@link #request} must have been called for a quiet producer to count as
     * "stalled" rather than "idle". A MediaProjection display publishes nothing while the
     * screen is still, so during a long wait (ChatGPT composing an answer, no sampling in
     * progress) a healthy producer legitimately goes quiet for minutes. Without this window
     * that quiet stretch would be misread as a dead producer and permanently downgrade the
     * session to the slow accessibility path. This is an activity window, not a quality bar.
     */
    static final long ACTIVE_REQUEST_WINDOW_MS = 5000;

    /** Guards the published raw frame, the run flag and the statistics. */
    private static final Object LOCK = new Object();
    /** Raw RGBA_8888 bytes of the newest frame; never an int[] copy of the whole frame. */
    private static byte[] frameBytes;
    private static int frameWidth;
    private static int frameHeight;
    private static int frameRowStride;
    private static int framePixelStride;
    /** {@link SystemClock#elapsedRealtime()} of the frame, not of the request. */
    private static long frameTimeMs;
    private static boolean running;
    /** When the producer was asked to start; 0 while not running. */
    private static long startedAtMs;
    /** Last time the consumer asked for a crop; drives the active-sampling check. */
    private static long lastRequestAtMs;
    /** Frame stamp the last time health was evaluated while idle (diagnostics only). */
    private static long lastObservedFrameTimeMs;

    // ------------------------------------------------------------- diagnostics
    private static long publishedFrames;
    private static long okRequests;
    private static long badRequests;
    /** Screen-space crop of the last successful copy (what the caller asked for). */
    private static int lastCropLeft = -1, lastCropTop = -1, lastCropRight = -1, lastCropBottom = -1;
    /** Frame-space rectangle actually copied by the last successful request. */
    private static int lastSrcLeft = -1, lastSrcTop = -1, lastSrcRight = -1, lastSrcBottom = -1;
    private static int lastOutWidth, lastOutHeight;
    private static String lastError = "none";
    /** Reused crop output; one allocation for the whole run, not one per sample. */
    private static int[] cropBuffer;

    private static final ProjectionCapture INSTANCE = new ProjectionCapture();

    /** The one shared instance; the service owns its lifetime, not this object. */
    static ProjectionCapture get() { return INSTANCE; }

    /** True from service start until the projection is gone. */
    static boolean isRunning() {
        synchronized (LOCK) { return running; }
    }

    /** True once at least one full frame has been published for the current run. */
    static boolean hasFrame() {
        synchronized (LOCK) { return frameBytes != null; }
    }

    /**
     * Age of the newest published frame in milliseconds, or -1 when no frame exists yet.
     *
     * <p>This is the honest staleness axis: a large age on a running projection means the
     * screen has not changed (information), while -1 means the producer has not delivered
     * anything at all (not usable). Neither case is faked into freshness.
     */
    static long lastFrameAgeMs() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (frameBytes == null || frameTimeMs <= 0) return -1;
            return Math.max(0, now - frameTimeMs);
        }
    }

    /**
     * Size of the last published frame as {@code {width, height}}, or {@code null} when no
     * frame has arrived. This is the TRUE source size (half-resolution), not the screen size.
     */
    static int[] lastFrameSize() {
        synchronized (LOCK) {
            if (frameBytes == null || frameWidth <= 0 || frameHeight <= 0) return null;
            return new int[]{frameWidth, frameHeight};
        }
    }

    /**
     * Projection scale factor: source pixels per physical screen pixel. Always
     * {@link #SCALE} for this build; consumers must use this instead of assuming the source
     * dimensions equal the screen crop dimensions.
     *
     * <p>The screen-crop to frame-size conversion lives in {@link ProjectionGeometry}, which
     * is pure Java and host-tested; it is deliberately not duplicated here.
     */
    static double scale() {
        return SCALE;
    }

    /**
     * Whether this source may still be used.
     *
     * <p>False when the projection is not running, or when no frame arrived within
     * {@link #STARTUP_GRACE_MS} of the start request (the "no frame within 3 s" rule).
     *
     * <p>Once a frame exists it returns true unless the consumer has been actively sampling
     * (a request within {@link #ACTIVE_REQUEST_WINDOW_MS}) and the newest frame has not
     * advanced for {@link #STALL_TIMEOUT_MS}. The activity gate is what stops a legitimately
     * quiet screen (ChatGPT composing an answer between segments) from being mistaken for a
     * dead producer — that mistake would permanently downgrade the session to the slow path.
     *
     * <p>Never throws, and never mutates the running state: it is a pure query over
     * {@code running}, {@code startedAtMs}, {@code frameTimeMs} and {@code lastRequestAtMs}.
     */
    static boolean isHealthy() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) { return isHealthyLocked(now); }
    }

    private static boolean isHealthyLocked(long now) {
        if (!running) return false;
        if (frameBytes != null && frameTimeMs > 0) {
            // A frame exists. Only call it stalled when the consumer is actively sampling and
            // the picture still has not advanced; a quiet producer between segments is normal.
            boolean activelySampling = lastRequestAtMs > 0
                    && now - lastRequestAtMs <= ACTIVE_REQUEST_WINDOW_MS;
            if (!activelySampling) {
                lastObservedFrameTimeMs = frameTimeMs;
                return true;
            }
            return now - frameTimeMs <= STALL_TIMEOUT_MS;
        }
        // No frame at all yet: healthy only while the startup grace period is still open.
        return startedAtMs > 0 && now - startedAtMs <= STARTUP_GRACE_MS;
    }

    /**
     * One-line diagnostic.
     *
     * <p>Format:
     * {@code frames=N lastAgeMs=X crop=[l,t,r,b] src=[l,t,r,b] out=WxH ok=M bad=K err=TAG healthy=B}
     *
     * <p>{@code crop} is the last screen-space rectangle requested (values reported by the
     * accessibility tree); {@code src} is the frame-space rectangle actually copied after
     * scaling and clamping; {@code out} is the true size returned to the caller. Coordinates
     * are -1 before the first successful copy. {@code frames} counts published producer
     * frames, {@code ok}/{@code bad} count accepted/rejected requests, {@code healthy}
     * mirrors {@link #isHealthy()}.
     */
    static String stats() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            long age = (frameBytes == null || frameTimeMs <= 0) ? -1 : Math.max(0, now - frameTimeMs);
            return "frames=" + publishedFrames
                    + " lastAgeMs=" + age
                    + " crop=[" + lastCropLeft + "," + lastCropTop + "," + lastCropRight + "," + lastCropBottom + "]"
                    + " src=[" + lastSrcLeft + "," + lastSrcTop + "," + lastSrcRight + "," + lastSrcBottom + "]"
                    + " out=" + lastOutWidth + "x" + lastOutHeight
                    + " ok=" + okRequests
                    + " bad=" + badRequests
                    + " err=" + lastError
                    + " healthy=" + isHealthyLocked(now);
        }
    }

    /** Clear diagnostic counters so {@link #stats()} always describes the current run. */
    private static void resetStatsLocked() {
        publishedFrames = 0; okRequests = 0; badRequests = 0;
        lastCropLeft = lastCropTop = lastCropRight = lastCropBottom = -1;
        lastSrcLeft = lastSrcTop = lastSrcRight = lastSrcBottom = -1;
        lastOutWidth = lastOutHeight = 0;
        lastError = "none";
    }

    // ----------------------------------------------------------- producer side

    /**
     * Publish one raw RGBA_8888 frame and return the previously published buffer so the
     * producer can reuse it. This keeps exactly two buffers alive for the whole run and
     * performs no per-pixel conversion at frame rate.
     *
     * <p>Called from the ImageReader thread. The buffer passed in is producer-owned until
     * this call; afterwards it must not be written again unless it is the buffer returned
     * here (which is no longer published).
     */
    static byte[] publishRaw(byte[] data, int width, int height, int rowStride,
                             int pixelStride, long timestampMs) {
        int stride = Math.max(1, pixelStride);
        if (data == null || width <= 0 || height <= 0 || rowStride < width * stride) return null;
        long needed = (long) (height - 1) * rowStride + (long) width * stride;
        // Reject a buffer that cannot actually hold the declared geometry: accepting it
        // would make every later crop read past the end of the array.
        if (needed <= 0 || needed > data.length) return null;
        byte[] previous;
        synchronized (LOCK) {
            previous = frameBytes;
            frameBytes = data;
            frameWidth = width;
            frameHeight = height;
            frameRowStride = rowStride;
            framePixelStride = Math.max(1, pixelStride);
            frameTimeMs = timestampMs;
            running = true;
            if (startedAtMs == 0) startedAtMs = timestampMs;
            publishedFrames++;
        }
        return previous;
    }

    /** Mark the producer as started, before the first frame has arrived. */
    static void setRunning(boolean value) {
        synchronized (LOCK) {
            running = value;
            startedAtMs = value ? SystemClock.elapsedRealtime() : 0;
            lastRequestAtMs = 0;
            lastObservedFrameTimeMs = 0;
            frameBytes = null;
            frameWidth = 0;
            frameHeight = 0;
            frameTimeMs = 0;
            resetStatsLocked();
        }
        // Keep the crop guard in step with who actually consumes the rectangle: while this
        // source is live the consumer sees SCALE-sized pixels, not screen-sized ones.
        WaveformSamplingPolicy.setSourceScale(value ? SCALE : 1.0f);
    }

    /** Mark the producer as gone and drop the frame so stale pixels cannot be served. */
    static void clear() {
        synchronized (LOCK) {
            running = false;
            startedAtMs = 0;
            lastRequestAtMs = 0;
            lastObservedFrameTimeMs = 0;
            frameBytes = null;
            frameWidth = 0;
            frameHeight = 0;
            frameTimeMs = 0;
        }
        // Back to full-resolution semantics so the accessibility path is never over-guarded.
        WaveformSamplingPolicy.setSourceScale(1.0f);
    }

    private ProjectionCapture() { }

    // ------------------------------------------------------------ FrameSource

    @Override public boolean request(Rect crop, WaveformCapture.Callback callback) {
        long now = SystemClock.elapsedRealtime();
        int[] out = null;
        int outWidth = 0;
        int outHeight = 0;
        long sampleTimeMs = 0;
        String error = null;
        synchronized (LOCK) {
            lastRequestAtMs = SystemClock.elapsedRealtime();
            if (frameBytes == null || frameWidth <= 0 || frameHeight <= 0) {
                error = "no_projection_frame";
            } else if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
                error = "invalid_crop";
            } else {
                // Screen-space rectangle -> frame space: scale by SCALE, round, clamp.
                int left = Math.round(crop.left * SCALE);
                int top = Math.round(crop.top * SCALE);
                int right = Math.round(crop.right * SCALE);
                int bottom = Math.round(crop.bottom * SCALE);
                left = Math.max(0, Math.min(frameWidth - 1, left));
                top = Math.max(0, Math.min(frameHeight - 1, top));
                right = Math.max(left + 1, Math.min(frameWidth, right));
                bottom = Math.max(top + 1, Math.min(frameHeight, bottom));
                outWidth = right - left;
                outHeight = bottom - top;
                if (outWidth <= 0 || outHeight <= 0) {
                    error = "invalid_crop";
                } else {
                    /*
                     * Reuse one crop buffer across requests. A fresh int[167*25] per sample
                     * would be ~33k allocations per 3-minute segment at the projection
                     * cadence: harmless in bytes but pure GC pressure on a phone. The
                     * callback consumes the array synchronously before request() returns, so
                     * sharing the buffer is safe as long as the consumer does not retain it;
                     * DictationService hands it straight to WaveformDetector.observe(), which
                     * reads it fully before returning.
                     *
                     * A size change (crop moved/resized) allocates once more and keeps the
                     * larger array, so steady state is one allocation.
                     */
                    if (cropBuffer == null || cropBuffer.length < outWidth * outHeight) {
                        cropBuffer = new int[outWidth * outHeight];
                    }
                    out = cropBuffer;
                    try {
                        copyCropLocked(out, left, top, outWidth, outHeight);
                        sampleTimeMs = frameTimeMs;
                        lastCropLeft = crop.left; lastCropTop = crop.top;
                        lastCropRight = crop.right; lastCropBottom = crop.bottom;
                        lastSrcLeft = left; lastSrcTop = top;
                        lastSrcRight = right; lastSrcBottom = bottom;
                        lastOutWidth = outWidth; lastOutHeight = outHeight;
                    } catch (RuntimeException ex) {
                        out = null;
                        error = "copy_failed";
                    }
                }
            }
            if (error == null) okRequests++; else { badRequests++; lastError = error; }
        }
        // Never call back under LOCK: the listener scans the accessibility tree and can take
        // tens of milliseconds, which would stall the producer's publishRaw().
        if (error != null) {
            try {
                callback.onFrame(null, 0, 0, now, error);
            } catch (RuntimeException ignored) {
                // A listener exception must never escape into the projection thread.
            }
            return false;
        }
        // True producer timestamp, never the request time. A still screen can therefore look
        // stale; that is the truth and the caller decides what it means.
        try {
            callback.onFrame(out, outWidth, outHeight, sampleTimeMs, null);
        } catch (RuntimeException ignored) {
            // Same rule as above: the caller's UI work must not kill this thread.
        }
        return true;
    }

    /**
     * Convert one crop-sized region from raw RGBA bytes to ARGB ints. Called under
     * {@link #LOCK}; the array is the only allocation, and it is crop-sized.
     */
    private static void copyCropLocked(int[] out, int left, int top, int width, int height) {
        byte[] src = frameBytes;
        int rowStride = frameRowStride;
        int pixelStride = framePixelStride;
        int outRow = 0;
        for (int y = 0; y < height; y++) {
            int base = (top + y) * rowStride + left * pixelStride;
            int index = base;
            for (int x = 0; x < width; x++) {
                int r = src[index] & 0xff;
                int g = src[index + 1] & 0xff;
                int b = src[index + 2] & 0xff;
                out[outRow + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                index += pixelStride;
            }
            outRow += width;
        }
    }

    @Override public void abandon() {
        // No in-flight work: a request is answered before it returns.
    }

    @Override public void shutdown() {
        // The producer owns the frame; this source never holds resources of its own.
    }
}
