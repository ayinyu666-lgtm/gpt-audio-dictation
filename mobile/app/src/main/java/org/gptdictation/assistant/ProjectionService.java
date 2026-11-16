package org.gptdictation.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Foreground service that mirrors the screen into a half-resolution VirtualDisplay and
 * publishes the newest raw frame to {@link ProjectionCapture}.
 *
 * <p>Why a service: the MediaProjection token obtained by the activity is only usable from a
 * foreground service, and the mirror must keep running while the user records a lecture with
 * ChatGPT in front. The notification channel is low importance so the mirror does not
 * distract from that.
 *
 * <p>Android 14+ behaviour that is easy to get wrong, and is encoded below:
 * <ul>
 *   <li>{@code startForeground} runs before any heavy work, so the process is never
 *       background-limited while the display is being created.</li>
 *   <li>{@code registerCallback} MUST run before {@code createVirtualDisplay}, otherwise the
 *       system throws {@link IllegalStateException}.</li>
 *   <li>One MediaProjection token allows exactly ONE VirtualDisplay, so the display is
 *       created once at startup and never rebuilt per capture.</li>
 *   <li>A projection {@code onStop} may race the startup sequence. A sticky {@code stopping}
 *       flag makes the late "running = true" impossible, and the frame holder is cleared so
 *       the consumer sees {@link ProjectionCapture#isHealthy()} false and falls back.</li>
 * </ul>
 *
 * <p>Memory: the ImageReader frame is copied into one reusable {@code byte[]} (the previous
 * one is recycled through {@link ProjectionCapture#publishRaw}), so a whole run costs two
 * half-resolution buffers (about 2.7 MB each at 561x1221) and never an {@code int[]} of the
 * full frame. Pixel conversion to ARGB happens lazily in {@link ProjectionCapture}, only for
 * the small crop a request actually asks for.
 */
public final class ProjectionService extends Service {
    private static final String TAG = "ProjectionService";
    private static final String CHANNEL = "projection_capture";
    private static final int NOTIFICATION_ID = 42;

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    /** The virtual display renders at half the screen size to keep frames cheap. */
    private static final float SCALE = ProjectionCapture.SCALE;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private HandlerThread imageThread;

    /** True between a successful start and onDestroy; read by {@link #isRunning()}. */
    private static volatile boolean running;

    /**
     * Set by a projection {@code onStop} or by a failed start. The startup sequence checks it
     * after every step so a late callback cannot resurrect a dead session.
     */
    private volatile boolean stopping;

    /** Reused raw frame buffer; the previous one comes back from publishRaw. */
    private byte[] frameBuffer;

    /** Static view of {@link #running}, used by the frame source to choose a backend. */
    public static boolean isRunning() {
        return running;
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        // One MediaProjection token allows exactly ONE VirtualDisplay; a second start
        // (button pressed again) must not try to build another one.
        if (projection != null) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = resultData(intent);
        if (resultData == null) { stopSelf(); return START_NOT_STICKY; }

        // Foreground FIRST: everything after this runs with the process already promoted,
        // so a slow VirtualDisplay creation cannot get the service killed.
        if (!startCaptureForeground()) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection token;
        try {
            token = manager.getMediaProjection(resultCode, resultData);
        } catch (RuntimeException ex) {
            Log.e(TAG, "getMediaProjection failed", ex);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (token == null) { stopSelf(); return START_NOT_STICKY; }
        projection = token;

        captureThread = new HandlerThread("projection-capture");
        captureThread.start();
        Handler captureHandler = new Handler(captureThread.getLooper());

        // Android 14+ rejects createVirtualDisplay unless a callback was registered first.
        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    // Runs on the capture thread. Never let this throw: an exception here
                    // would kill the looper and leave the app believing the mirror is alive.
                    try {
                        Log.i(TAG, "projection stopped by the system");
                        stopping = true;
                        running = false;
                        ProjectionCapture.clear();
                        stopSelf();
                    } catch (RuntimeException ex) {
                        Log.e(TAG, "projection onStop failed", ex);
                    }
                }
            }, captureHandler);
        } catch (RuntimeException ex) {
            Log.e(TAG, "registerCallback failed", ex);
            failStart();
            return START_NOT_STICKY;
        }

        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display current = window.getDefaultDisplay();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        current.getRealMetrics(metrics);
        int width = Math.max(1, Math.round(metrics.widthPixels * SCALE));
        int height = Math.max(1, Math.round(metrics.heightPixels * SCALE));

        // Start the 3 s health clock at the moment we begin building the display, not after
        // the first frame: a producer that never delivers must be detected from here.
        ProjectionCapture.setRunning(true);

        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            imageThread = new HandlerThread("projection-image");
            imageThread.start();
            Handler imageHandler = new Handler(imageThread.getLooper());
            reader.setOnImageAvailableListener(this::onImageAvailable, imageHandler);

            display = projection.createVirtualDisplay("dictation-projection",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, captureHandler);
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay returned null");
                failStart();
                return START_NOT_STICKY;
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "createVirtualDisplay failed", ex);
            failStart();
            return START_NOT_STICKY;
        }
        if (stopping) {
            // onStop fired while we were building; do not advertise a dead mirror.
            failStart();
            return START_NOT_STICKY;
        }
        running = true;
        return START_NOT_STICKY;
    }

    /** Read the projection token; the extra key is new in API 33. */
    @SuppressWarnings("deprecation")
    private static Intent resultData(Intent intent) {
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        return data;
    }

    /**
     * Called on the proprietary image looper for every new frame.
     *
     * <p>Only one bulk copy happens here: the RGBA bytes of the newest frame are copied into
     * a reusable {@code byte[]}. No per-pixel conversion, no full-frame {@code int[]}; the
     * ARGB swizzle is deferred to {@link ProjectionCapture#request} and limited to the crop
     * that request needs.
     *
     * <p>Every step is wrapped: an exception must never escape into the image looper, which
     * would silently stop all future frames (and look exactly like "the bubble froze").
     */
    private void onImageAvailable(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return;
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0 || planes[0].getBuffer() == null) return;
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = Math.max(1, planes[0].getPixelStride());
            int rowStride = planes[0].getRowStride();
            long needed = (long) (height - 1) * rowStride + (long) width * pixelStride;
            if (needed <= 0 || needed > Integer.MAX_VALUE) return;

            int size = (int) needed;
            // Strict ping-pong: `frameBuffer` is always a SPARE buffer that is NOT currently
            // published. Steady state is exactly two buffers. After the first publish there
            // is no released buffer yet, so a second one is allocated then and never again.
            if (frameBuffer == null || frameBuffer.length < size) frameBuffer = new byte[size];
            byte[] next = frameBuffer;
            buffer.position(0);
            buffer.limit(size);
            buffer.get(next, 0, size);

            long stamp = SystemClock.elapsedRealtime();
            byte[] released = ProjectionCapture.publishRaw(next, width, height,
                    rowStride, pixelStride, stamp);
            if (released != null && released.length >= size && released != next) {
                frameBuffer = released;
            } else {
                // First frame, or a returned buffer too small: get a fresh spare so the next
                // copy can never write into memory that is still published and being read.
                frameBuffer = new byte[size];
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.e(TAG, "frame publish failed", error);
        } finally {
            if (image != null) {
                try { image.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Tear down a half-started service and make sure no stale "running" state survives. */
    private void failStart() {
        stopping = true;
        running = false;
        ProjectionCapture.clear();
        stopSelf();
    }

    private boolean startCaptureForeground() {
        try {
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("高速取帧已开启")
                    .setContentText("正在为听写助手提供屏幕帧")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException ex) {
            // Some ROMs refuse a mediaProjection foreground service. Report it instead of
            // crashing the process; the accessibility path keeps working.
            Log.e(TAG, "startForeground failed", ex);
            return false;
        }
    }

    private void createChannel() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "高速取帧",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("MediaProjection 屏幕镜像是前台服务，必须常驻通知");
            manager.createNotificationChannel(channel);
        } catch (RuntimeException ex) {
            Log.e(TAG, "createChannel failed", ex);
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        running = false;
        ProjectionCapture.clear();
        if (display != null) {
            try { display.release(); } catch (RuntimeException ignored) { }
            display = null;
        }
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
            try { reader.close(); } catch (RuntimeException ignored) { }
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        if (imageThread != null) { imageThread.quitSafely(); imageThread = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
