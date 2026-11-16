package org.gptdictation.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;
import java.util.concurrent.Executor;

/** Captures only an ephemeral crop; callers must discard callbacks from old runs. */
final class WaveformCapture implements FrameSource {
    interface Callback {
        void onFrame(int[] pixels, int width, int height, long timeMs, String error);
    }

    private final AccessibilityService service;
    private final CaptureLease lease = new CaptureLease();
    /**
     * Screenshot decoding runs here, never on the main thread.
     *
     * takeScreenshot() delivers its result on the executor it was given, and decoding means
     * wrapping a hardware buffer, cropping, copying to ARGB_8888 and pulling ~67k pixels
     * out -- twice a second for a whole lecture. On the main executor that work competes
     * with the accessibility loop and the UI, which shows up as jank and heat. One private
     * thread is enough because CaptureLease already serialises requests.
     */
    private final HandlerThread decodeThread;
    private final Executor decodeExecutor;

    WaveformCapture(AccessibilityService service) {
        this.service = service;
        this.decodeThread = new HandlerThread("waveform-decode");
        this.decodeThread.start();
        this.decodeExecutor = new Handler(decodeThread.getLooper())::post;
        /*
         * This source returns full-resolution screen pixels, so the crop guards must not keep
         * a projection scale left behind by a previous projection session. Without this reset
         * the fallback path (DictationService swaps in a WaveformCapture when the projection
         * goes unhealthy) would keep sourceScale=0.25 while every crop here is 1:1 — and a
         * narrow waveform strip, which passes the 80x24 screen floor but fails the 96x48
         * quarter-scale floor, would be silently disabled for the rest of the run.
         *
         * Resetting in the constructor — rather than at each call site — makes it impossible
         * for a future fallback path to forget it, and it runs before the first request().
         */
        WaveformSamplingPolicy.setSourceScale(1.0f);
    }

    /** Stop the decode thread when the service goes away. */
    @Override public void shutdown() {
        if (decodeThread.isAlive()) decodeThread.quitSafely();
    }

    // Android exposes no cancellation for an outstanding screenshot. Abandon its
    // result but keep the physical slot occupied until the system calls back.
    @Override public void abandon() { lease.abandon(); }

    @Override public boolean request(Rect crop, Callback callback) {
        if (lease.busy()) return false;
        if (Build.VERSION.SDK_INT < 30 || crop == null || crop.width() < 24 || crop.height() < 12) {
            callback.onFrame(null, 0, 0, SystemClock.elapsedRealtime(), "unsupported_or_invalid_crop");
            return false;
        }
        Rect area = new Rect(crop);
        final long requestId = lease.begin();
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, decodeExecutor,
                    new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    if (!lease.finish(requestId)) { buffer.close(); return; }
                    Bitmap wrapped = null, cropped = null, software = null;
                    int[] pixels = null;
                    String error = null;
                    try {
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (wrapped == null || area.left < 0 || area.top < 0 ||
                                area.right > wrapped.getWidth() || area.bottom > wrapped.getHeight()) {
                            error = "invalid_screenshot_or_bounds";
                        } else {
                            // Crop the hardware-backed image before copying pixels to
                            // software memory; no full-screen software copy is needed.
                            cropped = Bitmap.createBitmap(wrapped, area.left, area.top,
                                    area.width(), area.height());
                            software = cropped.copy(Bitmap.Config.ARGB_8888, false);
                            if (software == null) error = "copy_failed";
                            else {
                                pixels = new int[area.width() * area.height()];
                                software.getPixels(pixels, 0, area.width(), 0, 0,
                                        area.width(), area.height());
                            }
                        }
                    } catch (RuntimeException | OutOfMemoryError ex) {
                        error = "screenshot_processing_failed";
                        pixels = null;
                    } finally {
                        if (software != null) software.recycle();
                        if (cropped != null && cropped != wrapped) cropped.recycle();
                        if (wrapped != null) wrapped.recycle();
                        buffer.close();
                    }
                    // Decoding finished on the background thread; the callback reads the
                    // accessibility tree and updates the UI, so hand it back to the main
                    // thread explicitly. Only the bitmap work stays off the main thread.
                    final int[] outPixels = pixels;
                    final String outError = error;
                    service.getMainExecutor().execute(() ->
                            callback.onFrame(outPixels, area.width(), area.height(),
                                    SystemClock.elapsedRealtime(), outError));
                }
                @Override public void onFailure(int code) {
                    if (!lease.finish(requestId)) return;
                    service.getMainExecutor().execute(() ->
                            callback.onFrame(null, 0, 0, SystemClock.elapsedRealtime(),
                                    "screenshot_error_" + code));
                }
            });
            return true;
        } catch (RuntimeException ex) {
            if (lease.finish(requestId)) {
                service.getMainExecutor().execute(() ->
                        callback.onFrame(null, 0, 0, SystemClock.elapsedRealtime(),
                                "screenshot_request_failed"));
            }
            return false;
        }
    }
}
