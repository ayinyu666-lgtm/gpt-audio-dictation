package org.gptdictation.assistant;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.WindowManager;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.security.SecureRandom;

public final class DictationService extends AccessibilityService {
    private static final String GPT = "com.openai.chatgpt";
    private static final String STOP = "org.gptdictation.assistant.STOP";
    private static final long TRANSITION_TIMEOUT = 20000;
    /**
     * Waiting for the voice entry to come back after a submit. This covers ChatGPT's
     * transcription of the whole segment, which scales with how long that segment was;
     * a fixed 20 s would abort a legitimate long recording's transcription. The value
     * is capped so a genuinely broken UI still stops within a bounded time.
     */
    private static final long WAIT_ENTRY_MIN_MS = 30000;
    private static final long WAIT_ENTRY_MAX_MS = 420000;
    /** How long ChatGPT may be unreadable before it counts as really gone/obscured. */
    private static final long FOREGROUND_LOST_MS = 3000;
    /**
     * getRootInActiveWindow() intermittently returns null while ChatGPT rebuilds its
     * window (every voice tick does this). One bad read must not be treated as "the
     * user left", or the service flaps between running and suspended.
     */
    private static final long AWAY_CONFIRM_MS = 4000;
    /**
     * Diagnostic switch. When true every tick is written to files/diag.txt (read it with
     * `adb shell run-as org.gptdictation.assistant cat files/diag.txt`). This is what
     * identified the inverted foreground test and the idle-band deadlock on the real
     * phone.
     *
     * KEPT ON while the pause detector is still being tuned: without these lines a real
     * lecture run leaves no evidence of why a segment ended. The cost is one 8 KB rewrite
     * every 8 ticks; with the event storm fixed (EVENT_KICK_MIN_MS) that is a few per
     * second at most, not the dozens it used to be.
     */
    private static final boolean DEBUG_VISIBILITY = true;
    /**
     * Minimum spacing between accessibility-event kicks of the main loop.
     *
     * This service registers for window/content events globally, and ChatGPT repaints its
     * waveform continuously while recording. Before this guard every such event cancelled
     * the pending tick and posted a new one immediately: measured on the device the loop ran
     * every ~1.7 ms instead of the intended 400 ms, roughly 235x the designed rate. That is
     * what made the phone hot: each tick walks the accessibility tree several times.
     *
     * 250 ms matches the normal ARMED cadence, so event-driven ticks can now only make the
     * loop *earlier* up to four times a second, never faster.
     */
    private static final long EVENT_KICK_MIN_MS = 250;
    /**
     * Watchdog period. It must be shorter than STALL_MS so a real stall is noticed promptly,
     * and long enough to be free: one message per 1.5 s is nothing next to a 400 ms tick.
     */
    private static final long WATCHDOG_PERIOD_MS = 1500;
    private long lastEventKickAt;
    /** How long the window list is reused before asking the framework again. */
    private static final long WINDOW_CACHE_MS = 1000;
    private java.util.List<android.view.accessibility.AccessibilityWindowInfo> windowCache;
    private long windowCacheAt;
    /** Diagnostic window summary is refreshed this often, not every tick. */
    private static final long WINDOW_SUMMARY_MS = 5000;
    private String windowSummaryCache = "";
    private long windowSummaryAt;
    private static final String NL = String.valueOf((char) 10);
    private enum State { IDLE, ARMED, WAIT_RECORD, RECORDING, WAIT_SEND }
    private static DictationService instance;
    private static String message = "服务未连接；请在设置中启用";
    private final Handler handler = new Handler();
    private final RecordingGuard recordingGuard = new RecordingGuard();
    private final MicProbe micProbe = new MicProbe();
    private final Diagnostics diagnostics = new Diagnostics();
    private final VisualCropTracker cropTracker = new VisualCropTracker();
    private State state = State.IDLE;
    private long deadline, recordUntil, missingSince, incompleteSince = -1;
    private int duration, segment;
    private boolean inTick;
    private int generation, windowId = -1;
    private Runnable loop;
    /**
     * Separate identity for event-driven re-arms.
     *
     * The canonical tick is `loop`, and only it may schedule the next tick. A kick used to be
     * `handler.removeCallbacks(loop); handler.post(loop)`, which means an event arriving at the
     * wrong moment could remove the one pending tick; if the re-post was then itself removed by
     * a screenshot callback, nothing was left and the run stalled for the rest of the lecture
     * while the UI still said "connected". `kick` is a different Runnable, so a kick can never
     * cancel the canonical tick.
     */
    private Runnable kick;
    /** Watchdog state: detects a loop that stopped re-arming itself. */
    private final TickWatchdog tickWatchdog = new TickWatchdog();
    private Runnable watchdog;
    private int probeCount;
    private volatile boolean probeRunning;
    /** Elapsed time of the last touch that landed on our own bubble. */
    private volatile long overlayTouchedAt;
    private FloatingControl floating;
    /** Settings used for the next start; the floating panel edits these. */
    private RecordingSettings settings = RecordingSettings.defaults();
    private boolean receiverRegistered;
    /** Set when a user touch landed inside ChatGPT; resolved by the next observations. */
    private long manualTouchAt;
    /** Shared per-run classifier and naming latch; host tests exercise this exact gate. */
    private final SessionNamingPolicy.Gate sessionNamingGate = new SessionNamingPolicy.Gate();
    /** Set while the naming message is being typed and sent. */
    private boolean namingInFlight;
    /** Exact text inserted by our naming initializer; used to bind its delayed click. */
    private String pendingNamingText;
    /** Prevents the generic leftover-draft submitter from resending our initializer. */
    private String namingTextForRun;
    /** True while ChatGPT is out of the foreground and actions are suspended. */
    private boolean suspended;
    /** Last tick that produced a readable ChatGPT root; drives the grace window. */
    private long lastGptRootAt;
    /** Last attempt to submit leftover composer text, so clicks cannot spam. */
    private long lastDraftSubmitAt;
    /**
     * Last composer text observed and when it last changed. A draft is only submitted
     * once it has stopped changing, so text the user is still typing is not sent
     * half-finished. Cleared whenever the composer is empty.
     */
    private String draftSeen;
    private long draftChangedAt;
    /** When the wait for the voice entry began; used for the status text only. */
    private long waitingSince;
    /** When the run entered the suspended state, so timers can be shifted on return. */
    private long suspendedAt;
    private int connectCount;
    /** True only while the system is tearing the service down. */
    private boolean tearingDown;

    // ---- environment learning -------------------------------------------------
    /** Collects activity during the first ten seconds of a run (and on demand). */
    private EnvLearnSession learnSession;
    /** Result of the window that completed this run, written when the run ends. */
    private EnvLearnSession.Result learnResult;
    /** Environment currently believed to be in effect. */
    private EnvProfile.Env currentEnv;
    /** Longest pause this run actually waited before submitting, for the stored pause stat. */
    private long observedPauseMs;
    /** Rolling window used to notice that the environment changed mid-run. */
    private final java.util.ArrayDeque<Double> envWindow = new java.util.ArrayDeque<>();
    private int envSwitchStreak;
    private int envFramesSinceCheck;
    /** One-line description of the current environment, shown in the notification. */
    private String envDescription = "未识别";
    /** True while the current learning window was requested by hand. */
    private boolean learnManual;
    /**
     * A run exists and the user parked it. The bubble stays on screen reading 暂停中, the
     * loop makes no observations and no timers advance. Tapping the bubble resumes.
     */
    private boolean softPaused;
    /** When the run was parked, so the timers can be shifted on resume. */
    private long softPausedAt;
    private static final long DRAFT_SUBMIT_COOLDOWN_MS = 2000;
    /**
     * How long the composer text must stay unchanged before it counts as leftover text
     * rather than a sentence still being typed. Long enough to cover normal typing
     * pauses, short enough that a lecture is not blocked by a stray draft.
     */
    private static final long DRAFT_SETTLE_MS = 6000;
    /** How many times the naming send is retried before giving up for this session. */
    private static final int NAMING_SEND_ATTEMPTS = 6;
    private final StringBuilder diagBuf = new StringBuilder();
    private int diagPending;
    /**
     * The per-run diagnostic text file currently being appended to.
     *
     * The old single files/diag.txt was rewritten in place on every flush and truncated to
     * the last 8000 characters, so every run kept only its own tail and the previous run
     * was gone. Evidence from a real measurement therefore no longer existed by the time it
     * was quoted. Each run now writes its own timestamped append-only file; the only thing
     * still bounded is the number of files kept.
     */
    private java.io.File diagFile;
    /** One diag-*.txt stops growing here; a fresh file is started instead. */
    private static final long DIAG_MAX_BYTES = 2000000L;
    /** How many diag-*.txt files to keep; older ones are deleted before a new one starts. */
    private static final int DIAG_KEEP_FILES = 20;


    private FrameSource waveformCapture;

    /**
     * Which frame source this service talks to.
     *
     * Projection-based capture is much faster than pulling accessibility screenshots, but
     * its lifetime belongs to ProjectionService: this service only borrows the running
     * instance. Otherwise the accessibility screenshot path stays the source, exactly as
     * before.
     */
    private FrameSource newFrameSource() {
        if (ProjectionCapture.isRunning() && ProjectionCapture.isHealthy()) return ProjectionCapture.get();
        return new WaveformCapture(this);
    }
    /** Decides the screenshot cadence from whether the room currently has sound. */
    private final AdaptiveSamplingPolicy sampling = new AdaptiveSamplingPolicy();
    /** The snapshot the current tick took, reused by the screenshot verification. */
    private Snapshot lastSnapshot;
    /**
     * Serial of {@link #lastSnapshot}. Bumped on every scan, so the screenshot callback can
     * require a tree that was read strictly AFTER the screenshot was requested. A timestamp
     * comparison cannot express that: the tick that issues the request also stamps its own
     * scan with the same millisecond.
     */
    private long lastSnapshotSerial;
    /** Serial value at the moment the outstanding screenshot was requested. */
    private long captureSnapshotSerial;
    /**
     * Child-index path from root to the recording-bar container, learned from a successful
     * anchored search.
     *
     * The recording bar sits ~40 levels deep with almost every ancestor at index 0, so a
     * fresh BFS finds the anchors only after visiting nearly every node (measured: 212 of
     * 212 on the real dumps). Reusing the path turns that into ~41 getChild() calls plus
     * the 27-node subtree, and it is verified by the same checks as a fresh search: the
     * subtree must still contain exactly one cancel, one stop, one send and no editable
     * node. A mismatch discards the path and falls back to a full anchored search.
     */
    private int[] recordingPath;
    private WaveformDetector detector;
    private AdaptivePausePolicy adaptivePause;
    private final NoiseAdaptation noiseAdaptation = new NoiseAdaptation();
    private final FreezeDetector freezeDetector = new FreezeDetector();
    private double appliedActivityMargin;
    private WaveformDetector.Result lastWaveform;
    private int pausePreference, requestSerial;
    private boolean capturePending, captureTimedOut, recordingReady, contextResetPending;
    /** Consecutive RECORDING scans that reported "not recording"; drives the desync guard. */
    private int recordingAbsentTicks;
    /** Tick counter for the RECORDING heartbeat diagnostic. */
    private int tickCount;
    private long captureRequestedAt, lastCaptureAt, lastWaveformAt;
    /** lastCaptureAt as it was when the outstanding capture was requested, for cap= in the log. */
    private long prevCaptureAt;
    private long segmentVisualActiveMs;
    private boolean visualPauseFeasible;
    private String waveformStatus;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) pause("屏幕关闭，已暂停；请检查当前录音");
            else if (STOP.equals(intent.getAction())) pause("已停止；当前录音由你处理");
        }
    };

    public static String status() { return message; }
    /** Settings currently used for the next start, shared with the floating panel. */
    public static RecordingSettings currentSettings() {
        return instance == null ? RecordingSettings.defaults() : instance.settings;
    }
    public static boolean diagnosticsEnabled() {
        return instance != null && instance.getSharedPreferences("settings", 0).getBoolean("diagnostics", false);
    }
    public static String diagnosticStatus() { return instance == null ? "服务未连接" : instance.diagnostics.status(); }
    public static boolean start(int seconds) {
        return start(seconds, 0, false);
    }
    public static boolean start(int seconds, int preference) {
        return start(seconds, preference, true);
    }
    /** Microphone probing is a separate manual action and never runs during recording. */
    public static boolean start(int seconds, int preference, boolean continuous) {
        if (instance == null) return false;
        if (instance.probeRunning) { instance.update("麦克风测试尚未结束，请稍后再开始"); return false; }
        if (instance.state != State.IDLE) { instance.update("已在运行，请先停止"); return false; }
        if (seconds < 5 || seconds > 1800) { instance.update("请输入 5–1800 秒"); return false; }
        // Nothing about any earlier segment blocks starting: continuous recording is the
        // requested behaviour, and this service makes no claim about transcription or
        // whether a submission succeeded -- that is ChatGPT's own business.
        instance.pausePreference = Math.max(RecordingSettings.MIN_PAUSE_BIAS,
                Math.min(RecordingSettings.MAX_PAUSE_BIAS, preference));
        return instance.begin(seconds);
    }
    /**
     * One-shot microphone check for the user to run manually. It only reports whether
     * THIS app received non-silent audio and immediately releases the microphone. It
     * never runs automatically and therefore never competes with ChatGPT unasked.
     */
    public static void probeMicrophone(java.util.function.Consumer<String> callback) {
        DictationService service = instance;
        if (service == null) { callback.accept("请先启用听写助手无障碍服务"); return; }
        if (service.state != State.IDLE || service.probeRunning) {
            callback.accept("请先停止自动操作并等待麦克风测试结束"); return;
        }
        service.probeRunning = true;
        new Thread(() -> {
            String result;
            try {
                MicProbe.Result start = service.micProbe.start();
                MicProbe.Result sample = start == MicProbe.Result.STARTED
                        ? service.micProbe.sampleOnce(1000) : MicProbe.Result.UNAVAILABLE;
                switch (sample) {
                    case AUDIO_PRESENT: result = "本助手收到非静音数据；这不代表 ChatGPT 同时也在录音"; break;
                    case SILENT: result = "本助手收到静音流；可能未抢到麦克风，请改用波形方式"; break;
                    default: result = "麦克风不可用或读取失败；不影响波形方式";
                }
            } catch (RuntimeException e) {
                result = "麦克风不可用或读取失败；不影响波形方式";
            } finally {
                service.micProbe.stop();
                service.probeRunning = false;
            }
            final String text = result;
            service.handler.post(() -> callback.accept(text));
        }, "microphone-probe").start();
    }
    public static void stop(String why) { if (instance != null) instance.pause(why); else message = why; }

    @Override protected void onServiceConnected() {
        instance = this;
        if (waveformCapture == null) waveformCapture = newFrameSource();
        IntentFilter filter = new IntentFilter(); filter.addAction(STOP); filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, filter);
            receiverRegistered = true;
        }
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("dictation", "听写助手状态", NotificationManager.IMPORTANCE_LOW));
        settings = loadSettings();
        if (floating == null) {
            floating = new FloatingControl(this, new FloatingControl.Host() {
                @Override public boolean isRunning() { return state != State.IDLE; }
                @Override public boolean isPaused() { return softPaused; }
                @Override public void onOverlayToggle() { toggleFromOverlay(); }
                @Override public void onOverlayTouch() { overlayTouchedAt = SystemClock.elapsedRealtime(); }
                @Override public void onSettingsSaved(RecordingSettings s, boolean diagnosticsEnabled) {
                    settings = s;
                    getSharedPreferences("settings", 0).edit()
                            .putInt("seconds", s.maxDurationSeconds)
                            .putInt("pause_bias", s.pauseBias)
                            .putInt("noise_ignore", s.noiseIgnore)
                            .putBoolean("diagnostics", diagnosticsEnabled).apply();
                    update("设置已保存，下次开始时生效");
                    if (floating != null) floating.refresh();
                }
                @Override public RecordingSettings currentSettings() { return settings; }
                @Override public boolean diagnosticsEnabled() {
                    return getSharedPreferences("settings", 0).getBoolean("diagnostics", false);
                }
            });
        }
        // The bubble is an in-use indicator, not a permanent fixture. Show it only when a
        // run is actually live; an idle assistant must leave the screen untouched.
        if (floating != null && (state != State.IDLE || wantsRun())) {
            floating.show(); floating.refresh();
        }
        if (DEBUG_VISIBILITY) { diagBuf.append("CONNECT #").append(++connectCount).append(" state=").append(state).append('\n'); flushDiag(); }
        // A reconnect must not relabel a run that is already under way: the notification
        // is the only status the user sees, and "服务已连接" while recording is a lie.
        // A reconnect is not a run. Restoring the run here made every service rebind start a
        // fresh run and put the bubble back on screen, so a flapping binding turned into a
        // bubble that kept appearing and disappearing. The user starts runs; the service only
        // reports that it is connected.
        if (state != State.IDLE) update("服务已重连；运行继续");
        else update("服务已连接；可在助手中开始");
    }

    private RecordingSettings loadSettings() {
        android.content.SharedPreferences p = getSharedPreferences("settings", 0);
        Integer seconds = RecordingSettings.parseSeconds(String.valueOf(p.getInt("seconds", RecordingSettings.DEFAULT_SECONDS)));
        return new RecordingSettings(
                seconds == null ? RecordingSettings.DEFAULT_SECONDS : seconds,
                p.getInt("pause_bias", RecordingSettings.DEFAULT_PAUSE_BIAS),
                p.getInt("noise_ignore", RecordingSettings.DEFAULT_NOISE_IGNORE));
    }

    /**
     * Bubble label. Three states in total, two of them visible on the bubble: not running
     * means no bubble at all, and a live run shows 录音中 unless the user parked it, which
     * reads 暂停中.
     */
    private String overlayLabel() {
        return OverlayStatus.label(state != State.IDLE, softPaused);
    }

    /**
     * Single tap on the bubble: start when idle, pause when running. Pausing never
     * clicks anything inside ChatGPT; the current recording is left for the user.
     */
    private void toggleFromOverlay() {
        if (state != State.IDLE && softPaused) {
            softResumeRun();
            return;
        }
        if (state != State.IDLE) {
            softPauseRun();
            return;
        }
        pausePreference = settings.pauseBias;
        if (!begin(settings.maxDurationSeconds)) {
            update("无法开始，请检查设置");
        } else {
            Intent launch = getPackageManager().getLaunchIntentForPackage(GPT);
            if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
        }
        if (floating != null) floating.refresh();
    }

    /**
     * How often to look at the accessibility tree.
     *
     * Waiting for the 语音输入 entry is the one place where latency directly costs a
     * segment: the entry can appear and the user expects recording to resume at once.
     * Those states poll fast. While a segment is actually recording there is nothing
     * to click until the waveform says so, and the waveform itself is sampled once a
     * second, so scanning the whole tree four times a second would be pure waste.
     */
    private long tickIntervalMs() {
        switch (state) {
            case ARMED:
            case WAIT_RECORD:
            case WAIT_SEND:
                return 250;
            case RECORDING:
                // A pause can only be caught once the bar has gone quiet, and the tick that
                // notices it is also the tick that decides to send. Poll twice as often in
                // exactly that window; the rest of a segment keeps the cheaper 400 ms cadence.
                return pausePlausible() ? RECORDING_TICK_FAST_MS : RECORDING_TICK_MS;
            default:
                return 250;
        }
    }

    /**
     * True while the last waveform result suggests a pause is being measured - the bar is
     * quiet, or the detector has already accumulated quiet time.
     *
     * INVALID never qualifies. An unreadable frame is not evidence of silence, so speeding
     * up on it would buy nothing and could only add scans; the detector keeps any quiet time
     * it had already banked, so a stale pauseMs must not be read as "quiet is happening now".
     * A normal talking segment never pays for the faster tick either.
     */
    private boolean pausePlausible() {
        WaveformDetector.Result w = lastWaveform;
        return w != null
                && w.signal != WaveformDetector.Signal.INVALID
                && (w.signal == WaveformDetector.Signal.VISUAL_QUIET || w.pauseMs > 0);
    }
    /** Normal tree-poll cadence while recording. */
    private static final long RECORDING_TICK_MS = 400;
    /** Faster poll used only while a pause is plausible; see {@link #pausePlausible()}. */
    private static final long RECORDING_TICK_FAST_MS = 200;
    /** Capture interval while a pause is plausible: half the normal fast interval. */
    private static final long CAPTURE_PAUSE_MS = 250;
    /**
     * How old a projection frame may be and still count as evidence.
     *
     * A projection producer only publishes when the screen changes, and it reports the real
     * producer timestamp. On a silent screen no new frame arrives, so the newest frame is
     * legitimately older than {@link WaveformSamplingPolicy#FRESH_MS}; rejecting it would
     * reject exactly the silence the pause detector is looking for. This much larger bound
     * is what separates "nothing changed" from "the producer stopped delivering".
     */
    private static final long PROJECTION_STATIC_MAX_AGE_MS = 10000;
    /**
     * Consecutive RECORDING ticks whose fresh scan says "not recording" before the run is
     * handed to RecordingGuard. Three ticks at the RECORDING cadence is ~1.2 s, longer than
     * a transient reflow and far shorter than the guard's own 3 s gap.
     */
    private static final int RECORDING_DESYNC_TICKS = 3;
    /** Heartbeat period while RECORDING, in ticks (~400 ms each, so ~40 s). */
    private static final int HEARTBEAT_EVERY_TICKS = 100;

    /** How long to wait for the voice entry after submitting, scaled to segment length. */
    private long waitEntryTimeoutMs() {
        long scaled = duration * 1000L;
        return Math.max(WAIT_ENTRY_MIN_MS, Math.min(WAIT_ENTRY_MAX_MS, scaled));
    }

    /** Persisted "the user wants the assistant running"; survives service rebinds. */
    private static final String KEY_RUN = "run_active";

    /** The intent is only valid for a short time, so a reboot or tomorrow's session never
     *  starts the assistant by itself. */
    private static final String KEY_RUN_AT = "run_active_at";
    private static final String KEY_INSTALL = "run_install_stamp";
    private static final long RESUME_WINDOW_MS = 5 * 60 * 1000L;

    private boolean wantsRun() {
        android.content.SharedPreferences p = getSharedPreferences("settings", 0);
        if (!p.getBoolean(KEY_RUN, false)) return false;
        long at = p.getLong(KEY_RUN_AT, 0);
        if (at <= 0) return false;
        long age = System.currentTimeMillis() - at;
        // A new build invalidates the old intent: reinstalling must not silently start a
        // recording just because one was running before the update.
        if (p.getLong(KEY_INSTALL, -1) != installStamp()) {
            setWantsRun(false);
            return false;
        }
        if (age < 0 || age > RESUME_WINDOW_MS) {
            setWantsRun(false);
            return false;
        }
        return true;
    }

    /** lastUpdateTime of this package, used to detect that a new build was installed. */
    private long installStamp() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private void setWantsRun(boolean on) {
        getSharedPreferences("settings", 0).edit()
                .putBoolean(KEY_RUN, on)
                .putLong(KEY_RUN_AT, on ? System.currentTimeMillis() : 0)
                .putLong(KEY_INSTALL, on ? installStamp() : -1)
                .apply();
    }

    /** Park a live run: keep the bubble (it is still "on"), stop all observation. */
    private void softPauseRun() {
        if (softPaused) return;
        softPaused = true;
        softPausedAt = SystemClock.elapsedRealtime();
        if (loop != null) handler.removeCallbacks(loop);
        if (kick != null) handler.removeCallbacks(kick);
        if (watchdog != null) handler.removeCallbacks(watchdog);
        abandonCapture(); recordingReady = false;
        if (floating != null) floating.refresh();
        update("已暂停；点悬浮窗继续");
    }

    /** Continue a parked run with the timers shifted by the time spent parked. */
    private void softResumeRun() {
        if (!softPaused) return;
        long parked = SystemClock.elapsedRealtime() - softPausedAt;
        if (parked > 0) {
            if (deadline > 0) deadline += parked;
            if (recordUntil > 0) recordUntil += parked;
        }
        softPaused = false; softPausedAt = 0;
        abandonCapture(); recordingReady = false; lastCaptureAt = 0;
        recordingGuard.reset(); cropTracker.resetSession();
        if (detector != null) detector.resetDecisionOnly();
        if (adaptivePause != null) adaptivePause.beginSegment();
        segmentVisualActiveMs = 0; lastWaveform = null; lastWaveformAt = 0;
        if (floating != null) floating.refresh();
        update("已继续；录音照常进行");
        if (loop != null) handler.post(loop);
        else begin(settings.maxDurationSeconds);
        if (watchdog != null) { handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS); }
        tickWatchdog.reset(SystemClock.elapsedRealtime());
    }

    private boolean begin(int seconds) {
        if (loop != null) handler.removeCallbacks(loop);
        // Re-read saved settings at the start of every run. The settings screen writes
        // SharedPreferences directly; without this the service kept whatever it loaded
        // at connection time, so changing "噪音忽略" appeared to do nothing until the
        // accessibility service was restarted.
        settings = loadSettings();
        abandonCapture(); cropTracker.resetSession();
        final int run = ++generation;
        loop = new Runnable() { @Override public void run() {
            /*
             * The canonical tick must never disappear silently.
             *
             * Every `return` inside tick() is harmless: tick() is void and returns to this
             * frame, which then re-arms below. The only real hazard is an unchecked
             * Throwable escaping tick() — the old body would then skip postDelayed and the
             * loop died with the bubble still showing "recording". The finally block makes
             * the reschedule unconditional for a live run; generation/IDLE still stop it,
             * because that is a legitimate end of the run, not a stall.
             */
            try {
                if (run != generation || state == State.IDLE) return;
                tick();
                tickWatchdog.onTick(SystemClock.elapsedRealtime());
            } finally {
                if (run == generation && state != State.IDLE) handler.postDelayed(this, tickIntervalMs());
            }
        }};
        /*
         * The kick is a different Runnable identity from `loop` on purpose. It only asks for an
         * immediate tick; it cannot remove the canonical tick that `loop` schedules, so the two
         * can never cancel each other.
         */
        kick = new Runnable() { @Override public void run() {
            if (run != generation || state == State.IDLE || softPaused) return;
            tick();
        }};
        /*
         * The watchdog is the safety net for orderings we have not found.
         *
         * It never calls tick(): it only checks whether a tick has completed recently and, if
         * not, re-arms the canonical tick. Running tick() here could overlap with a slow tick
         * already in progress; re-arming cannot, because inTick remains the re-entrancy guard.
         */
        watchdog = new Runnable() { @Override public void run() {
            if (run != generation || state == State.IDLE) return;
            long now = SystemClock.elapsedRealtime();
            if (!softPaused && tickWatchdog.stalled(now)) {
                handler.removeCallbacks(loop);
                handler.post(loop);
                if (tickWatchdog.shouldLog(now)) {
                    diagBuf.append("TICK_STALL_RECOVERED gap=").append(tickWatchdog.sinceLastTick(now))
                            .append("ms state=").append(state).append('\n');
                    diagPending++;
                }
                tickWatchdog.onRecovered(now);
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }};
        tickWatchdog.reset(SystemClock.elapsedRealtime());
        duration = seconds; segment = 0; missingSince = 0; incompleteSince = -1; windowId = -1; suspended = false; manualTouchAt = 0;
        sessionNamingGate.reset(); namingInFlight = false; pendingNamingText = null; namingTextForRun = null;
        lastGptRootAt = SystemClock.elapsedRealtime();
        lastDraftSubmitAt = 0; waitingSince = 0; suspendedAt = 0;
        softPaused = false; softPausedAt = 0;
        if (DEBUG_VISIBILITY) { diagBuf.append("BEGIN\n"); flushDiag(); }
        contextResetPending = false;
        recordingGuard.reset();
        freezeDetector.reset();
        // Prime the freeze detector. Its first observe() after reset() always reports a
        // fresh start, and tick() treats that as a freeze: it rewrites the ARMED deadline
        // to TRANSITION_TIMEOUT (20 s). begin() has just promised the user 60 s, so
        // without this priming every run got 20 s instead, and a cold ChatGPT start
        // failed with "等待录音入口超时".
        freezeDetector.observe(SystemClock.elapsedRealtime());
        detector = new WaveformDetector(new WaveformDetector.Config(1500, Math.min(3000, duration * 1000L), duration * 1000L, 2500, 5000));
        detector.setActivityMargin(NoiseIgnorePolicy.thresholdMargin(settings.noiseIgnore));
        appliedActivityMargin = NoiseIgnorePolicy.thresholdMargin(settings.noiseIgnore);
        adaptivePause = new AdaptivePausePolicy();
        noiseAdaptation.reset();
        // With a short maximum, the visual reference cannot be learned in time. Say so
        // once at the start instead of silently relying on the timed fallback all along.
        // Feasibility is a property of the segment length and the sampling interval, so it
        // must be judged against the NORMAL pause. The deadline-pressure curve deliberately
        // starts at CAP_MS (30 s), which would call every ordinary segment infeasible and
        // permanently disable visual pausing.
        long normalAtBegin = adaptivePause == null ? AdaptivePausePolicy.DEFAULT_MS
                : adaptivePause.requiredPauseMs(pausePreference, 0);
        visualPauseFeasible = RecordingFlowPolicy.visualPauseFeasible(
                duration * 1000L, WaveformSamplingPolicy.INTERVAL_MS, normalAtBegin);
        diagnostics.close();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 && getSharedPreferences("settings", 0).getBoolean("diagnostics", false)) diagnostics.start(this);
        state = State.ARMED; deadline = SystemClock.elapsedRealtime() + 60000;
        setWantsRun(true);
        learnSession = new EnvLearnSession();
        learnResult = null; observedPauseMs = 0;
        envWindow.clear(); envSwitchStreak = 0; envFramesSinceCheck = 0;
        seedFromStore();
        if (floating != null) { floating.show(); floating.refresh(); }
        update("等待 ChatGPT 前台；请保持亮屏（60 秒内）"); handler.post(loop);
        if (watchdog != null) { handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS); }
        return true;
    }
    /**
     * Enters RECORDING for a segment whose recording UI is already visible.
     *
     * @param adopted true when ChatGPT was already recording and the assistant takes it
     *                over (resume case). Timing starts now either way, because the
     *                assistant has no evidence of when an adopted segment actually began.
     */
    private void enterRecording(long now, boolean adopted) {
        state = State.RECORDING; recordUntil = now + duration * 1000L; segment++;
        recordingGuard.reset();
        if (detector == null) detector = new WaveformDetector(new WaveformDetector.Config(1500, Math.min(3000, duration * 1000L), duration * 1000L, 2500, 5000));
        detector.setActivityMargin(NoiseIgnorePolicy.thresholdMargin(settings.noiseIgnore)
                + noiseAdaptation.autoActivityMargin());
        appliedActivityMargin = NoiseIgnorePolicy.thresholdMargin(settings.noiseIgnore)
                + noiseAdaptation.autoActivityMargin();
        // Keep the learned levels across a UI/window change: activity is normalised by the
        // crop height, so the history stays valid, and clearing it re-entered the eight-sample
        // warm-up at the start of every segment.
        if (contextResetPending) { if (adaptivePause != null) adaptivePause.reset(); cropTracker.resetSession(); contextResetPending = false; }
        detector.beginSegment(now); lastWaveform = null; lastWaveformAt = 0; lastCaptureAt = 0; recordingReady = true;
        recordingAbsentTicks = 0;
        // Re-learn the recording-bar path for this segment: the previous one may refer to a
        // container that no longer exists (new chat, keyboard state, layout rebuild).
        recordingPath = null;
        // A new segment starts responsive: nothing is known about this room yet, so sample
        // fast until the detector reports real silence.
        sampling.reset();
        noiseAdaptation.reset();
        cropTracker.resetSegment(); segmentVisualActiveMs = 0;
        if (adaptivePause != null) adaptivePause.beginSegment();
        waveformStatus = null;
        showWaveformStatus(visualPauseFeasible ? "停顿检测准备中" : "本段时长较短，停顿检测可能来不及；最长时长兜底");
        if (adopted) update("已接管正在进行的录音；从此刻开始计时");
    }
    // ------------------------------------------------------------- environment learning

    private static final String KEY_ENV_LAST = "env_last";
    private static final String KEY_ENV_PREFIX = "env_";

    private android.content.SharedPreferences prefs() { return getSharedPreferences("settings", 0); }

    /**
     * Seed the detector from the levels learned on previous runs.
     *
     * Called once per run, before any frame is looked at. Without it every run starts by
     * measuring the room from scratch, which is the eight-second warm-up that made pause
     * detection impossible on a fifteen-second segment.
     */
    private void seedFromStore() {
        String last = prefs().getString(KEY_ENV_LAST, null);
        EnvProfile.Env env = last == null ? null : EnvProfile.Env.byName(last);
        if (DEBUG_VISIBILITY) {
            diagBuf.append("  seedFromStore last=").append(last)
                    .append(" detector=").append(detector != null).append(NL);
            flushDiag();
        }
        if (env == null) { envDescription = "未识别（本轮学习）"; return; }
        java.util.List<EnvProfile.Summary> list =
                EnvProfile.decode(prefs().getString(KEY_ENV_PREFIX + env.name(), ""));
        double quiet = EnvProfile.weightedMedian(list, false);
        double speech = EnvProfile.weightedMedian(list, true);
        if (Double.isNaN(quiet) || Double.isNaN(speech) || speech <= quiet) {
            envDescription = env.label + "（库还不可用）";
            return;
        }
        if (detector != null) detector.seedPrior(quiet, speech);
        // Seed the speech bar too when this environment ever measured one. The old
        // activity levels above only describe the percentile picture, which on this device
        // barely moves with loudness; the share bar is the one that actually separates a
        // voice from this room's background.
        double shareBar = EnvProfile.weightedShareBar(list);
        if (detector != null && !Double.isNaN(shareBar)) detector.seedShareBar(shareBar);
        if (DEBUG_VISIBILITY) {
            diagBuf.append("  seed env=").append(env.name())
                    .append(" quiet=").append(String.format(java.util.Locale.US, "%.4f", quiet))
                    .append(" speech=").append(String.format(java.util.Locale.US, "%.4f", speech))
                    .append(" shareBar=").append(Double.isNaN(shareBar) ? "n/a"
                            : String.format(java.util.Locale.US, "%.4f", shareBar))
                    .append(" rounds=").append(list.size())
                    .append(" applied=").append(detector != null && detector.hasPrior()).append(NL);
            flushDiag();
        }
        currentEnv = env;
        envDescription = String.format(java.util.Locale.US, "%s（先验 %.2f/%.2f，%d 轮）",
                env.label, quiet, speech, list.size());
    }

    /** Feed one classified frame into the learner and the environment watcher. */
    private void observeEnvironment(WaveformDetector.Result r, long now) {
        if (r == null || r.signal == WaveformDetector.Signal.INVALID) return;
        if (r.pauseMs > observedPauseMs) observedPauseMs = r.pauseMs;
        if (learnSession == null) learnSession = new EnvLearnSession();
        learnSession.observe(r.activity, now);
        // The tall-bar share is the reading that actually moves with loudness here, so the
        // learning window must see it too; otherwise the stored profile describes a signal
        // that cannot separate this room's background from a voice over it.
        learnSession.observeShare(r.speechShare);
        if (learnSession.ready(now)) completeLearning(now);

        envWindow.addLast(r.activity);
        while (envWindow.size() > 30) envWindow.removeFirst();
        if (++envFramesSinceCheck >= 15 && envWindow.size() >= 20) {
            envFramesSinceCheck = 0;
            checkEnvironmentSwitch(now);
        }
    }

    /** A learning window finished: store it now when asked for by hand, otherwise keep it. */
    private void completeLearning(long now) {
        EnvLearnSession.Result result = learnSession == null ? null : learnSession.finish(now);
        boolean manual = learnManual;
        learnSession = new EnvLearnSession();
        learnManual = false;
        if (result == null) { update("学习样本不足，稍后再试"); return; }
        learnResult = result;
        currentEnv = result.env;
        envDescription = result.describe();
        if (manual) {
            storeSummary(result, System.currentTimeMillis(), 1.0);
            update("已学习当前环境：" + result.describe());
        } else {
            update("环境已识别：" + result.describe());
        }
    }

    /** Mid-run auto-switch: three agreeing classifications in a row are needed. */
    private void checkEnvironmentSwitch(long now) {
        EnvProfile.Env seen = EnvLearnSession.classify(new java.util.ArrayList<>(envWindow));
        if (seen == null) return;
        if (seen == currentEnv) { envSwitchStreak = 0; return; }
        if (++envSwitchStreak < 3) return;
        envSwitchStreak = 0;
        EnvProfile.Env previous = currentEnv;
        currentEnv = seen;
        envDescription = seen.label;
        applyStoredPrior(seen);
        update("环境切换为 " + seen.label + (previous == null ? "" : "（原 " + previous.label + "）"));
    }

    /** Adopt stored levels for an environment, if any are good enough to use. */
    private void applyStoredPrior(EnvProfile.Env env) {
        java.util.List<EnvProfile.Summary> list =
                EnvProfile.decode(prefs().getString(KEY_ENV_PREFIX + env.name(), ""));
        double quiet = EnvProfile.weightedMedian(list, false);
        double speech = EnvProfile.weightedMedian(list, true);
        if (detector != null && !Double.isNaN(quiet) && !Double.isNaN(speech) && speech > quiet) {
            detector.seedPrior(quiet, speech);
        }
    }

    /** Append one run's numbers to its environment, keeping only the newest few. */
    private void storeSummary(EnvLearnSession.Result result, long now, double weight) {
        EnvProfile.Env env = result.env;
        java.util.List<EnvProfile.Summary> list =
                EnvProfile.decode(prefs().getString(KEY_ENV_PREFIX + env.name(), ""));
        EnvProfile.Summary summary = new EnvProfile.Summary(
                env, result.quiet, result.speech, observedPauseMs, result.frames, now, weight,
                result.shareBar);
        list = EnvProfile.add(list, summary);
        prefs().edit()
                .putString(KEY_ENV_PREFIX + env.name(), EnvProfile.encode(list))
                .putString(KEY_ENV_LAST, env.name())
                .apply();
        currentEnv = env;
        envDescription = env.label;
    }

    /** Write what this run learned, so the next run can start from it. */
    private void finishEnvironmentLearning() {
        EnvLearnSession.Result result = learnResult;
        learnResult = null;
        if (result == null) return;
        storeSummary(result, System.currentTimeMillis(), 1.0);
    }

    /** Manual "learn this environment now" from the settings screen. */
    public static boolean requestEnvironmentLearning() {
        if (instance == null || instance.state == State.IDLE) return false;
        instance.startManualLearning();
        return true;
    }

    private void startManualLearning() {
        learnSession = new EnvLearnSession();
        learnManual = true;
        update("正在学习当前环境（10 秒）…");
    }

    /** Text for the settings screen: what the assistant currently thinks the room is. */
    public static String environmentSummary() {
        if (instance == null) return "服务未连接";
        StringBuilder b = new StringBuilder(instance.envDescription);
        if (instance.learnSession != null && instance.learnSession.started()) {
            b.append(" · 学习中 ").append(Math.round(instance.learnSession.progress(
                    SystemClock.elapsedRealtime()) * 100)).append('%');
        }
        return b.toString();
    }

    /** How many stored runs exist for each environment, for the settings screen. */
    public static String learnedRounds() {
        if (instance == null) return "";
        StringBuilder b = new StringBuilder();
        for (EnvProfile.Env env : EnvProfile.Env.values()) {
            java.util.List<EnvProfile.Summary> list =
                    EnvProfile.decode(instance.prefs().getString(KEY_ENV_PREFIX + env.name(), ""));
            if (list.isEmpty()) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(env.label).append(' ').append(list.size());
        }
        return b.length() == 0 ? "还没有学习记录" : b.toString();
    }

    private void pause(String reason) {
        diagBuf.append("PAUSE ").append(reason).append('\n'); flushDiag();
        if (!tearingDown) setWantsRun(false);
        diagnostics.marker("pause", false); diagnostics.close();
        abandonCapture();
        micProbe.stop();
        state = State.IDLE; generation++; suspended = false; manualTouchAt = 0; namingInFlight = false;
        softPaused = false; softPausedAt = 0;
        if (loop != null) handler.removeCallbacks(loop);
        if (kick != null) handler.removeCallbacks(kick);
        if (watchdog != null) handler.removeCallbacks(watchdog);
        if (detector != null) detector.reset(); detector = null;
        if (adaptivePause != null) adaptivePause.reset(); adaptivePause = null;
        lastWaveform = null; cropTracker.resetSession();
        update(reason);
        finishEnvironmentLearning();
        // Idle means nothing to show: take the bubble away until the user starts again.
        if (!tearingDown && floating != null) floating.hide();
    }
    private void update(String text) {
        message = text;
        if (floating != null) floating.refresh();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(STOP).setPackage(getPackageName());
        PendingIntent action = PendingIntent.getBroadcast(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "dictation").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("听写助手").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(content).setOnlyAlertOnce(true).setOngoing(state != State.IDLE);
        if (state != State.IDLE) builder.addAction(new Notification.Action.Builder(null, "停止", action).build());
        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1, builder.build()); } catch (SecurityException ignored) { }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        diagnostics.event(event, state.name(), segment);
        // This service is registered for window/touch events globally. Without this
        // filter, swiping in any other app would be treated as "the user touched
        // ChatGPT" and silently pause a good recording.
        if (event.getPackageName() == null || !GPT.contentEquals(event.getPackageName())) return;
        if (state != State.IDLE && state != State.ARMED && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            abandonCapture(); contextResetPending = true;
            if (state == State.RECORDING) resetVisualContext(SystemClock.elapsedRealtime());
        }
        if (state != State.IDLE && state != State.ARMED && event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            // Touching our own bubble is not the user touching ChatGPT. Without this
            // check a tap both toggled the assistant and paused it, which is why the
            // control felt like it "did not respond".
            long sinceOverlayTouch = SystemClock.elapsedRealtime() - overlayTouchedAt;
            if (sinceOverlayTouch > 1200) {
                // A touch may be the user sending manually on purpose. Do not decide here:
                // mark it and let the next scans see whether the recording actually ended.
                manualTouchAt = SystemClock.elapsedRealtime();
            }
        }
        // Throttle the event-driven re-post. See EVENT_KICK_MIN_MS: without this every
        // waveform repaint restarted the loop and the tick rate exploded.
        // A kick uses its own Runnable identity, so it cannot cancel the canonical tick.
        if (state != State.IDLE && !softPaused && !inTick) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastEventKickAt >= EVENT_KICK_MIN_MS) {
                lastEventKickAt = now;
                handler.removeCallbacks(kick);
                handler.post(kick);
            }
        }
    }
    @Override public void onInterrupt() { pause("无障碍服务中断，已暂停"); }
    @Override public void onDestroy() {
        tearingDown = true;
        if (floating != null) { floating.hide(); floating = null; }
        pause("无障碍服务已断开");
        // Stop the screenshot decode thread; leaving it alive would leak a looper per
        // service reconnect. A projection-backed source is owned by ProjectionService and
        // survives this service, so it must never be shut down from here.
        if (waveformCapture != null) {
            if (waveformCapture instanceof WaveformCapture) waveformCapture.shutdown();
            waveformCapture = null;
        }
        tearingDown = false;
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        instance = null; super.onDestroy();
    }

    /** Which frame source is in use right now, for diagnostics. */
    private String sourceLabel() {
        return waveformCapture instanceof ProjectionCapture ? "proj" : "a11y";
    }

    private void tick() {
        if (state == State.IDLE || inTick) return;
        inTick = true;
        try {
            long now = SystemClock.elapsedRealtime();
            /*
             * Projection health fallback.
             *
             * A dead or stalled projection must not silently starve the loop of frames.
             * Swap to the accessibility path, drop the outstanding request (bumping the
             * serial so any late callback is ignored) and force a UI re-check: the
             * previous recordingReady answer belonged to a source that no longer works.
             */
            if (waveformCapture instanceof ProjectionCapture && !ProjectionCapture.isHealthy()) {
                waveformCapture = new WaveformCapture(this);
                requestSerial++;
                capturePending = false;
                recordingReady = false;
                invalidateWaveform(now, "projection_unhealthy");
                showWaveformStatus("高速取帧不可用，已回退无障碍截图");
                lastCaptureAt = 0;
            }
            // Heartbeat: proves the loop is alive even when no wave lines are produced.
            // Without it a silent stall is indistinguishable from "loop running, no frames".
            if (++tickCount % HEARTBEAT_EVERY_TICKS == 0 && state == State.RECORDING) {
                diagBuf.append("hb t=").append(now / 250 % 100000)
                        .append(" state=").append(state)
                        .append(" src=").append(sourceLabel())
                        .append('\n');
                flushDiag();
            }
            if (freezeDetector.observe(now)) {
                // The process was frozen, the service was just (re)connected, or the
                // clock jumped. Everything we knew about the previous tick is stale:
                // drop screenshots in flight and rebuild the visual reference from the
                // live UI instead of acting on pre-freeze evidence.
                abandonCapture(); invalidateWaveform(now, "recording_recheck");
                lastCaptureAt = 0;
                if (state == State.RECORDING) {
                    recordingReady = false; recordingGuard.reset(); cropTracker.resetSession();
                    if (detector != null) detector.resetDecisionOnly();
                    if (adaptivePause != null) adaptivePause.beginSegment();
                    segmentVisualActiveMs = 0;
                    // Deadlines are absolute monotonic times and keep running while the
                    // process is frozen. Without this the first resumed tick would see
                    // now >= recordUntil and submit instantly, while the user has only
                    // just come back. Move the clock start to the resume point.
                    recordUntil = now + duration * 1000L;
                } else if (state == State.ARMED || state == State.WAIT_RECORD || state == State.WAIT_SEND) {
                    deadline = now + TRANSITION_TIMEOUT;
                }
            }
            inspect();
        } catch (RuntimeException e) { pause("读取界面失败，已暂停；请检查当前录音"); } finally { inTick = false; }
    }
    /**
     * The accessibility root belonging to ChatGPT.
     *
     * On this Honor device getRootInActiveWindow() returns null or an unrelated window
     * while ChatGPT is plainly in front - measured directly: uiautomator read the
     * 语音输入 control at [830,2299][902,2371] at the exact moment the service reported
     * "ChatGPT 不在前台". Walking getWindows() and taking the ChatGPT window is stable,
     * so that is the primary source; the old call stays as a fallback.
     */
    private AccessibilityNodeInfo chatGptRoot() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = windows();
            if (windows != null) {
                // Prefer the window the user is actually looking at. A ChatGPT window can
                // still linger in the list while a dialog or another app is on top, and
                // acting on that one would click into a screen nobody sees.
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    if (w != null && w.isActive()) {
                        AccessibilityNodeInfo r = w.getRoot();
                        CharSequence pkg = r == null ? null : r.getPackageName();
                        if (pkg != null && GPT.contentEquals(pkg)) return r;
                    }
                }
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence pkg = r.getPackageName();
                    if (pkg != null && GPT.contentEquals(pkg)) return r;
                }
            }
        } catch (RuntimeException ignored) { }
        return getRootInActiveWindow();
    }

    /**
     * Window list, cached briefly.
     *
     * Both the foreground check and the diagnostic line need it, and on this device it
     * holds ~17 windows; asking for each window's root is a cross-process call, so doing
     * the walk twice per tick was paying that cost twice. ONE_SECOND is short enough that
     * a real window switch is still noticed well within the away-grace window (4 s), and
     * long enough to share one walk between every consumer in the same tick.
     */
    private java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows() {
        long now = SystemClock.elapsedRealtime();
        if (windowCache != null && now - windowCacheAt < WINDOW_CACHE_MS) return windowCache;
        try { windowCache = getWindows(); } catch (RuntimeException e) { windowCache = null; }
        windowCacheAt = now;
        return windowCache;
    }

    /** One compact line per tick in files/diag.txt; pulled with adb run-as. */
    private void diag(long now, AccessibilityNodeInfo root, boolean rootIsChatGpt, boolean visible) {
        if (!DEBUG_VISIBILITY) return;
        CharSequence pkg = root == null ? null : root.getPackageName();
        diagBuf.append("t=").append(now / 250 % 100000)
                .append(" st=").append(state)
                .append(" root=").append(root == null ? "null" : pkg)
                .append(" gpt=").append(rootIsChatGpt)
                .append(" vis=").append(visible)
                // How often the watchdog had to re-arm a loop that stopped ticking. Any
                // non-zero value on a normal lecture means an ordering we have not found is
                // still able to lose the tick, and must be investigated.
                .append(" wd=").append(tickWatchdog.recoveries())
                .append(" win=").append(windowSummary())
                .append('\n');
        if (++diagPending >= 8) { diagPending = 0; flushDiag(); }
    }

    /**
     * Window list for the diagnostic line, refreshed at most every WINDOW_SUMMARY_MS.
     *
     * The window list changes on the order of once per user action, not per tick, so
     * rebuilding it every 400 ms bought nothing and cost a full cross-process walk of ~17
     * windows on top of the one the foreground check already does.
     */
    private String windowSummary() {
        long now = SystemClock.elapsedRealtime();
        if (now - windowSummaryAt < WINDOW_SUMMARY_MS && !windowSummaryCache.isEmpty()) {
            return windowSummaryCache;
        }
        windowSummaryAt = now;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = windows();
            if (ws == null) { windowSummaryCache = "null"; return windowSummaryCache; }
            StringBuilder b = new StringBuilder();
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                if (w == null) { b.append("[null]"); continue; }
                AccessibilityNodeInfo r = w.getRoot();
                b.append('[').append(w.getType()).append(':')
                        .append(r == null ? "?" : r.getPackageName()).append(']');
            }
            windowSummaryCache = b.toString();
        } catch (RuntimeException e) { windowSummaryCache = "err"; }
        return windowSummaryCache;
    }

    private void flushDiag() {
        if (!DEBUG_VISIBILITY) return;
        if (diagBuf.length() == 0) return;
        try {
            if (diagFile == null || diagFile.length() >= DIAG_MAX_BYTES) diagFile = nextDiagFile();
            if (diagFile == null) return;
            byte[] bytes = diagBuf.toString().getBytes("UTF-8");
            java.io.FileOutputStream out = new java.io.FileOutputStream(diagFile, true);
            try { out.write(bytes); } finally { out.close(); }
            diagBuf.setLength(0);
        } catch (Exception ignored) { }
    }

    /** Opens the diagnostic file for this run, or a fresh one when the current is full. */
    private java.io.File nextDiagFile() {
        java.io.File dir = getFilesDir();
        pruneDiagFiles(dir);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        java.io.File f = new java.io.File(dir, "diag-" + stamp + ".txt");
        for (int i = 1; f.exists() && i < 100; i++) {
            f = new java.io.File(dir, "diag-" + stamp + "-" + i + ".txt");
        }
        return f;
    }

    /**
     * Keeps at most {@link #DIAG_KEEP_FILES} of our own diag-*.txt logs, oldest first.
     *
     * Both the prefix and the .txt suffix are required, so the diagnostics JSONL files
     * (diag-<hex>.jsonl) and every unrelated file in the directory are never touched.
     */
    private static void pruneDiagFiles(java.io.File dir) {
        try {
            java.io.File[] all = dir.listFiles();
            if (all == null) return;
            java.util.TreeMap<String, java.io.File> mine = new java.util.TreeMap<>();
            for (java.io.File f : all) {
                String n = f == null ? "" : f.getName();
                if (f != null && f.isFile() && n.startsWith("diag-") && n.endsWith(".txt")) mine.put(n, f);
            }
            java.util.ArrayList<java.io.File> ordered = new java.util.ArrayList<>(mine.values());
            for (int i = 0; i <= ordered.size() - DIAG_KEEP_FILES; i++) ordered.get(i).delete();
        } catch (Exception ignored) { }
    }

    private void inspect() {
        long now = SystemClock.elapsedRealtime();
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked()) { pause("屏幕关闭或锁定，已暂停；请检查当前录音"); return; }
        AccessibilityNodeInfo root = chatGptRoot();
        boolean rootIsChatGpt = root != null && GPT.contentEquals(root.getPackageName());
        if (rootIsChatGpt) lastGptRootAt = now;
        // Only a readable ChatGPT tree may be acted on. A momentarily unreadable tree is
        // NOT "the user left" - ChatGPT rebuilds its window constantly while recording -
        // so a short grace window keeps the run alive through those gaps. Anything longer
        // counts as genuinely away and parks the loop without touching any timer.
        boolean chatGptVisible = rootIsChatGpt || now - lastGptRootAt < AWAY_CONFIRM_MS;
        diag(now, root, rootIsChatGpt, chatGptVisible);
        AwayPolicy.Action away = AwayPolicy.decide(chatGptVisible, true, suspended);
        if (away == AwayPolicy.Action.SUSPEND_ACTIONS) {
            if (!suspended) {
                // ChatGPT keeps recording in the background. Stop touching the screen,
                // but keep every timer running so returning is seamless - like stepping
                // out of the office and coming back to the same work.
                suspended = true;
                abandonCapture(); recordingReady = false;
                update("ChatGPT 不在前台；已暂停操作，计时继续，回到 ChatGPT 后自动继续");
            }
            if (DEBUG_VISIBILITY && (suspendedAt == 0 || now - suspendedAt > 3000)) {
                diagBuf.append("  away untilMs=").append(recordUntil - now)
                        .append(" deadlineMs=").append(deadline - now).append('\n');
                flushDiag();
            }
            if (state == State.RECORDING) { recordingGuard.observe(now, false); invalidateWaveform(now, "recording_recheck"); }
            // Time spent away must not consume the transition timers: returning after a
            // short trip used to find every deadline already expired, so the run paused
            // instead of continuing. Shift them forward by the elapsed away time.
            if (suspendedAt == 0) {
                suspendedAt = now;
            } else {
                long awayMs = now - suspendedAt;
                if (awayMs > 0) {
                    if (deadline > 0) deadline += awayMs;
                    if (recordUntil > 0) recordUntil += awayMs;
                }
                suspendedAt = now;
            }
            return;
        }
        if (away == AwayPolicy.Action.RESUME && suspended) {
            // Back in ChatGPT: discard everything measured while away and rebuild from
            // the live UI. Timers are untouched because the recording never stopped.
            suspended = false;
            // The last away tick may be older than the moment of return (the process can be
            // throttled in the background), so close the remaining gap before resetting.
            if (suspendedAt > 0) {
                long tail = now - suspendedAt;
                if (tail > 0) {
                    if (deadline > 0) deadline += tail;
                    if (recordUntil > 0) recordUntil += tail;
                }
                suspendedAt = 0;
            }
            abandonCapture(); lastCaptureAt = 0; recordingReady = false;
            recordingGuard.reset(); cropTracker.resetSession();
            if (detector != null) detector.resetDecisionOnly();
            if (adaptivePause != null) adaptivePause.beginSegment();
            segmentVisualActiveMs = 0; lastWaveform = null; lastWaveformAt = 0;
            if (DEBUG_VISIBILITY) {
                diagBuf.append("  resume untilMs=").append(recordUntil - now)
                        .append(" deadlineMs=").append(deadline - now).append('\n');
                flushDiag();
            }
            update("已回到 ChatGPT；重新同步界面");
        }
        if (!rootIsChatGpt) return;
        missingSince = 0;
        if (windowId == -1) windowId = root.getWindowId();
        else if (windowId != root.getWindowId()) {
            // A new window id inside ChatGPT is usually a dialog, keyboard or layout
            // rebuild, not a different conversation. Rebuild the visual reference and
            // keep the run; the control checks below still refuse to act on unclear UI.
            windowId = root.getWindowId();
            contextResetPending = true;
            if (state == State.RECORDING) {
                abandonCapture(); recordingReady = false; invalidateWaveform(now, "layout_changed");
                if (detector != null) detector.resetDecisionOnly();
                cropTracker.resetSession(); segmentVisualActiveMs = 0;
            }
        }
        missingSince = 0;
        diagnostics.snapshot(root, state.name(), segment, false, "periodic");
        // RECORDING only needs the recording bar. Everywhere else the draft, the tabs and
        // the new-chat suggestion rows matter, so those states keep the full scan.
        Snapshot s = state == State.RECORDING ? scanRecordingArea(root) : scan(root);
        // Remember what this tick saw: the screenshot verification reuses it rather than
        // walking the same tree a second time. The serial proves the tree was read after a
        // pending screenshot request, which a timestamp cannot (same millisecond).
        lastSnapshot = s; lastSnapshotSerial++;
        // Real duplicate controls stay fatal: acting on the wrong one submits the wrong thing.
        if (s.ambiguous) { pause("发现多个同名录音控件，已暂停；请手动处理当前录音"); return; }
        // A draft only matters before starting a NEW segment: typing over text the user
        // already wrote would be destructive. Once recording is under way the composer is
        // ChatGPT's business and must never stop the loop - recording continues as long as
        // the recording controls are present.
        // What is in the text box is the user's business, never a precondition. Leftover
        // text must not stop the run and must not block starting a segment: submit it once
        // and carry on recording. Skipped while our own naming message is being typed, and
        // rate-limited so a send that does not take effect cannot spam clicks.
        //
        // The text must have stopped changing first. Submitting the instant any text
        // appears would send a sentence the user is still typing, which is destructive in
        // exactly the way the old comment above was worried about.
        //
        // A localized recording-bar scan does not contain the composer, so "no draft" from
        // it is not evidence. Only a full scan may update the draft bookkeeping; otherwise a
        // settled draft would be cleared on every recording tick and never submitted.
        if (!s.recordingArea) {
            if (!s.draft) {
                draftSeen = null; draftChangedAt = 0;
            } else if (!s.draftText.equals(draftSeen)) {
                draftSeen = s.draftText; draftChangedAt = now;
            }
        }
        boolean draftSettled = s.draft && draftSeen != null && draftChangedAt > 0
                && now - draftChangedAt >= DRAFT_SETTLE_MS;
        boolean namingDraft = namingTextForRun != null && namingTextForRun.equals(s.draftText);
        if (draftSettled && !namingDraft && !namingInFlight
                && now - lastDraftSubmitAt >= DRAFT_SUBMIT_COOLDOWN_MS) {
            lastDraftSubmitAt = now;
            AccessibilityNodeInfo send = findSendMessage(root);
            if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                draftSeen = null; draftChangedAt = 0;
                update("输入框有文字且已停止变动，已尝试发送；随后继续录音");
                return;
            }
        }
        // A tree that could not be fully read is not evidence about the UI state.
        // Skipping the tick keeps a transient read from ending a good recording.
        //
        // A null child during a reflow is routine while ChatGPT redraws; it is NOT proof
        // that the user left or that recording stopped. Treating a four-second run of them
        // as a fatal condition paused healthy recordings in the middle of a lecture while
        // ChatGPT kept recording, leaving the bubble saying 暂停中 and the timers stopped.
        // Only wait-state deadlines may end a run here; RECORDING keeps recording and the
        // real checks (foreground window, recording controls, maximum duration) still run
        // on the next complete tree.
        if (s.incomplete) {
            if (state == State.RECORDING) {
                abandonCapture(); recordingReady = false;
                recordingGuard.observe(now, false); invalidateWaveform(now, "tree_stale");
                if (DEBUG_VISIBILITY) {
                    diagBuf.append("  scan incomplete in RECORDING: skipped frame, still recording");
                    diagPending++;
                }
            }
            if (incompleteSince < 0) incompleteSince = now;
            if (state == State.ARMED || state == State.WAIT_RECORD || state == State.WAIT_SEND) {
                if (now >= deadline) pause("界面持续读取不完整，等待超时；请检查当前录音");
            }
            return;
        }
        incompleteSince = -1;
        if (state == State.ARMED) {
            if (DEBUG_VISIBILITY) {
                diagBuf.append("  scan 语音=").append(s.voice != null)
                        .append(" 发送=").append(s.send != null)
                        .append(" 录音=").append(s.recording)
                        .append(" 草稿=").append(s.draft)
                        .append(" 建议行=").append(s.newChatSuggestionRows)
                        .append('\n');
            }
            if (now >= deadline) { pause("等待录音入口超时"); return; }
            // Classify this screen before recording. Absence of new-chat labels is not
            // proof of an existing conversation: during rendering/navigation it is UNKNOWN,
            // and recording must wait until a fresh complete tree resolves it.
            if (DEBUG_VISIBILITY) {
                diagBuf.append("  naming rows=").append(s.newChatSuggestionRows)
                        .append(" placeholder=").append(s.newChatPlaceholder)
                        .append(" existingPlaceholder=").append(s.existingChatPlaceholder)
                        .append(" tabs=").append(s.chatTabs)
                        .append(" voice=").append(s.voice != null)
                        .append(" rec=").append(s.recording)
                        .append(" namingInvoked=").append(sessionNamingGate.namingInvoked()).append("\n");
                flushDiag();
            }
            if (namingInFlight) return;
            SessionNamingPolicy.Action namingAction = sessionNamingGate.observe(
                    s.newChatSuggestionRows, s.newChatPlaceholder, s.chatTabs,
                    s.existingChatPlaceholder, !s.draft, s.voice != null && !s.recording,
                    s.recording || s.send != null);
            if (namingAction == SessionNamingPolicy.Action.WAIT) return;
            if (namingAction == SessionNamingPolicy.Action.NAME_NOW) {
                // Missing composer/send controls are transient evidence, not permission to
                // fall through to recording. Retry on the next complete ARMED observation.
                if (sendSessionName(now)) return;
                return;
            }
            /*
             * Do NOT start recording while a naming message is in flight.
             *
             * The naming send is asynchronous: sendSessionName() types the text and schedules
             * attemptNamingSend() 600 ms later to click the send arrow, because the composer
             * repaints while it accepts the text. The state machine used to ignore that and
             * immediately fall through to startRecording(). Recording replaces the composer
             * with the recording bar (取消录制 / 停止录制 / 点击发送录音 and NO EditText), so by
             * the time attemptNamingSend() ran there was no send arrow left to click, all six
             * attempts failed, and the message was silently dropped.
             *
             * Measured on the device with 0.7.0: at the naming moment the tree contained
             * cancel/stop/send-recording anchors and zero EditText nodes; the chat stayed
             * empty and `named` stayed false, while the log did show rows=2 tabs=true - i.e.
             * detection worked and only the ordering was wrong.
             *
             * Waiting here is bounded by attemptNamingSend(). Reaching its click action
             * initializes the chat; no assistant reply or message-delivery confirmation is
             * part of this gate.
             */
            if (namingInFlight) return;
            ResumePolicy.Action action = ResumePolicy.decide(
                    s.voice != null && !s.recording, s.recording, s.send != null);
            if (action == ResumePolicy.Action.ADOPT_RECORDING) {
                // ChatGPT is already recording: take it over and time from now instead of
                // waiting for a 语音输入 entry that only appears after a send.
                enterRecording(now, true);
            } else if (action == ResumePolicy.Action.START_RECORDING) {
                startRecording(now, root);
            }
        } else if (state == State.WAIT_RECORD) {
            if (s.recording && s.send != null && s.voice == null) {
                enterRecording(now, false);
            } else if (now >= deadline) pause("未确认进入录音态，已暂停；请检查界面");
        } else if (state == State.RECORDING) {
            // The user is allowed to operate ChatGPT by hand at any time. A touch alone
            // proves nothing - only the next observations decide what it meant. If the
            // recording controls vanished right after the touch, they submitted this
            // segment themselves, so wait for the voice entry and keep the run alive.
            // ManualTouchPolicy already existed with tests but had never been wired into
            // the state machine: manualTouchAt was recorded and then ignored, so a manual
            // send looked like "recording UI unconfirmed" and the run was paused.
            ManualTouchPolicy.Action manual = ManualTouchPolicy.decide(
                    manualTouchAt > 0,
                    manualTouchAt > 0 ? now - manualTouchAt : 0,
                    s.recording, s.voice != null);
            if (DEBUG_VISIBILITY && manualTouchAt > 0) {
                diagBuf.append("  manual ").append(manual)
                        .append(" dt=").append(now - manualTouchAt)
                        .append(" rec=").append(s.recording)
                        .append(" voice=").append(s.voice != null).append('\n');
                flushDiag();
            }
            if (manual == ManualTouchPolicy.Action.TREAT_AS_SUBMIT) {
                manualTouchAt = 0;
                abandonCapture(); recordingReady = false;
                state = State.WAIT_SEND; deadline = now + waitEntryTimeoutMs(); waitingSince = now;
                update("第 " + segment + " 段由你手动发送；等待录音入口后继续录音");
                return;
            }
            // A user touch NEVER changes the run state by itself. The old code paused here
            // when the recording controls were still visible 1.2 s after the touch, which is
            // exactly what a manual send looks like while the entry is still hidden: the
            // bubble dropped to 暂停中 a few seconds after the user pressed send. The user
            // operating ChatGPT by hand must be invisible to the assistant.
            watchCapture(now);
            /*
             * State-machine desync guard.
             *
             * Device QA showed the service sitting in RECORDING while the real screen was a
             * normal composer and the bubble read 暂停中 — and the tick loop then stopped
             * writing entirely. A run must never stay in RECORDING on evidence that says it
             * is not recording. Count consecutive fresh scans that disagree; once the count
             * crosses the threshold, log it and hand the decision to the existing
             * RecordingGuard, which already owns the RECHECK -> PAUSE transition. No new
             * state machine is introduced here.
             */
            if (!s.recording) {
                // Do NOT reset RecordingGuard here: that would restart its 3 s gap window on
                // every tick and it could never reach PAUSE. Letting the guard's own clock
                // run is what actually performs the RECORDING -> WAIT_SEND transition.
                if (++recordingAbsentTicks == RECORDING_DESYNC_TICKS) {
                    diagBuf.append("state_desync state=RECORDING absentTicks=").append(recordingAbsentTicks)
                            .append(" voice=").append(s.voice != null)
                            .append(" send=").append(s.send != null)
                            .append(" src=").append(sourceLabel())
                            .append('\n');
                    flushDiag();
                    recordingReady = false;
                }
            } else {
                recordingAbsentTicks = 0;
            }
            RecordingGuard.Result check = recordingGuard.observe(now, s.recording && s.send != null && s.voice == null);
            if (check == RecordingGuard.Result.PAUSE) {
                // The recording controls disappearing is never a reason to end the run.
                // It is what a send looks like: by hand, by the keyboard, or by ChatGPT
                // finishing the segment. Ending the run here is precisely the reported bug
                // ("manual send turns the bubble to 暂停中 a few seconds later"). Only a
                // locked screen or the user's own pause may stop the run.
                recordingGuard.reset();
                abandonCapture(); recordingReady = false;
                if (s.voice != null && !s.recording) {
                    startRecording(now, root);   // entry is already back: next segment starts now
                    return;
                }
                state = State.WAIT_SEND; deadline = now + waitEntryTimeoutMs(); waitingSince = now;
                update("第 " + segment + " 段已结束；等待录音入口后继续录音");
                return;
            }
            if (check == RecordingGuard.Result.RECHECK) {
                recordingReady = false; invalidateWaveform(now, "recording_recheck");
                showWaveformStatus("停顿信号不可用，按最长时长发送（录音界面复查中）"); return;
            }
            recordingReady = true;
            VisualCropTracker.Action cropAction = cropTracker.observe(cropFor(s));
            if (cropAction == VisualCropTracker.Action.REARM) {
                // Keep the learned level statistics across a crop change: activity is
                // normalised by the crop height, so the history stays meaningful, and
                // clearing it re-entered the 8-sample warm-up every time the UI shifted.
                // Re-arming happened often enough that no segment ever produced a decision.
                if (detector != null) detector.beginSegment(now);
                if (adaptivePause != null) adaptivePause.reset();
                segmentVisualActiveMs = 0; invalidateWaveform(now, "crop_changed");
                showWaveformStatus("声波区域变化，重新建立波形参考");
            }
            int[] crop = cropAction == VisualCropTracker.Action.SAMPLE ? cropTracker.current() : null;
            if (crop == null) {
                invalidateWaveform(now, "crop_changed");
                showWaveformStatus(cropAction == VisualCropTracker.Action.DISABLE
                        ? "停顿信号不可用，按最长时长发送（声波区域反复变化）"
                        : "停顿信号不可用，按最长时长发送（声波区域不明确）");
            }
            boolean fresh = WaveformSamplingPolicy.fresh(now, lastWaveformAt) && lastWaveform != null
                    && lastWaveform.signal != WaveformDetector.Signal.INVALID;
            // A pause-triggered send is only warranted on a frame the detector actually saw
            // as QUIET. SEND_PAUSE alone can be latched from another code path, and sending
            // on that would end a segment mid-sentence.
            boolean pauseReady = fresh && lastWaveform.decision == WaveformDetector.Decision.SEND_PAUSE
                    && lastWaveform.signal == WaveformDetector.Signal.VISUAL_QUIET;
            // Shared rule, also used by the host tests: decides only when to click send.
            SegmentSubmitDecision.Decision decision = new SegmentSubmitDecision(
                    now >= recordUntil, fresh, pauseReady).decide();
            if (decision != SegmentSubmitDecision.Decision.WAIT) {
                sendSegment(root, now, decision == SegmentSubmitDecision.Decision.SEND_PAUSE, fresh);
            } else if (crop != null && detector != null) requestWaveform(crop, now);
        } else if (state == State.WAIT_SEND) {
            // Waiting for the entry is normally not a failure: ChatGPT keeps the voice
            // entry hidden while it answers, and a long answer used to hit a fixed 20 s
            // deadline and pause the whole run. The deadline is now scaled by the segment
            // length (see waitEntryTimeoutMs), so it is the real bound for this wait.
            //
            // It MUST be enforced. Without it a run could sit here forever while the
            // bubble still said "录音中", i.e. the lecture silently stopped recording and
            // the user had no way to notice. That is the one failure mode worth ending
            // the run for: a visible pause is recoverable, a silent one loses the lecture.
            if (s.voice != null && !s.recording) {
                startRecording(now, root);
            } else if (deadline > 0 && now >= deadline) {
                pause("等待录音入口超时；当前录音由你处理");
            } else if (now - waitingSince >= 5000) {
                long left = deadline > 0 ? Math.max(0, (deadline - now + 999) / 1000) : 0;
                showWaveformStatus("等待 ChatGPT 回复结束，录音入口回来后继续（" + left + " 秒后超时）");
            }
        }
    }
    private enum ActionResult { NOT_READY, SUCCESS, UNCERTAIN, ABORT }

    /**
     * Performs one submission attempt. Called only from the RECORDING branch after
     * {@link SegmentSubmitDecision} allowed it. Never retries a failed click.
     */
    private void sendSegment(AccessibilityNodeInfo root, long now, boolean visualPause, boolean fresh) {
        // A transiently incomplete tree means no click was attempted, so remain in
        // RECORDING and let the next tick revalidate the same one-shot decision.
        // Reuse the tree this tick already read instead of enumerating every window again.
        ActionResult action = clickFresh(false, root);
        if (action == ActionResult.NOT_READY) {
            recordingReady = false; recordingGuard.observe(now, false); invalidateWaveform(now, "send_not_ready");
            return;
        }
        if (action != ActionResult.SUCCESS) {
            diagnostics.marker("send_action_result", false);
            pause("发送点击状态不明确，已停止以免重复操作；当前录音由你处理");
            return;
        }
        state = State.WAIT_SEND; deadline = now + waitEntryTimeoutMs(); waitingSince = now;
        String source = visualPause ? "检测到视觉波形停顿（试验）"
                : (!fresh ? "停顿信号不可用，按最长时长发送" : "最长时间到，使用定时兜底");
        update("第 " + segment + " 段：" + source + "；倾向：" + preferenceLabel() + "；已点击发送，等待录音入口");
        diagnostics.marker(visualPause ? "visual_pause" : "timed_fallback", true);
        diagnostics.marker("send_action_result", true);
    }
    /**
     * Types the archiving name into the empty composer and sends it.
     *
     * Uses ACTION_SET_TEXT on the editable node, then clicks the send button. Only
     * called for positively identified new chats with an empty composer. The delayed click
     * is bound to the exact text inserted here so navigation cannot redirect it to another
     * conversation. Returns true when the attempt was started.
     */
    private boolean sendSessionName(long now) {
        AccessibilityNodeInfo root = chatGptRoot();
        if (root == null || !GPT.contentEquals(root.getPackageName())) return false;
        AccessibilityNodeInfo composer = findEditable(root);
        if (composer == null) return false;
        String text = SessionNaming.namingWithRecall(System.currentTimeMillis());
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
        pendingNamingText = text;
        namingTextForRun = text;
        namingInFlight = true;
        update("正在为会话命名并请求回忆要求");
        // The composer repaints while it accepts the text, so the send control can be
        // missing for a few hundred milliseconds. A single attempt was not enough: on the
        // device that one try regularly found no button and the naming was silently
        // dropped, so retry a few times before giving up.
        handler.postDelayed(() -> attemptNamingSend(1), 600);
        return true;
    }

    /** One bounded attempt to click send for the naming message. */
    private void attemptNamingSend(int attempt) {
        AccessibilityNodeInfo root = chatGptRoot();
        AccessibilityNodeInfo composer = root == null ? null : findEditable(root);
        CharSequence currentText = composer == null ? null : composer.getText();
        if (!SessionNamingPolicy.pendingTextStillMatches(pendingNamingText,
                currentText == null ? null : currentText.toString())) {
            // The original composer disappeared or changed while the callback was pending.
            // Do not click a send control belonging to the newly visible conversation.
            pendingNamingText = null;
            namingInFlight = false;
            sessionNamingGate.reset();
            if (kick != null) { handler.removeCallbacks(kick); handler.post(kick); }
            return;
        }
        AccessibilityNodeInfo send = findSendMessage(root);
        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            sessionNamingGate.markNamingInvoked();
            pendingNamingText = null;
            namingInFlight = false;
            // The click action is the initialization boundary. Recording resumes through
            // the normal ARMED path as soon as ChatGPT exposes its voice entry; no response
            // or delivery status is checked.
            deadline = SystemClock.elapsedRealtime() + waitEntryTimeoutMs();
            update("已发起会话命名；等待录音入口后续录");
            if (kick != null) { handler.removeCallbacks(kick); handler.post(kick); }
            return;
        }
        if (attempt >= NAMING_SEND_ATTEMPTS) {
            // Bounded UI action retries are not a delivery check. Do not turn a missing
            // send control into a permanent gate or let the leftover initializer text be
            // mistaken for user draft text later in this run.
            sessionNamingGate.markNamingInvoked();
            pendingNamingText = null;
            namingInFlight = false;
            update("命名发送控件暂不可用；继续录音");
            if (kick != null) { handler.removeCallbacks(kick); handler.post(kick); }
            return;
        }
        final int next = attempt + 1;
        handler.postDelayed(() -> attemptNamingSend(next), 400);
    }

    /** First editable, visible node inside ChatGPT. */
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 5000) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n.isEditable() && n.isVisibleToUser() && GPT.contentEquals(n.getPackageName())) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /**
     * The control that submits typed text (not the recording one).
     *
     * ChatGPT's send button is usually an icon with no usable content description, so
     * matching a label alone is fragile. Strategy, in order:
     *   1. an explicit 发送消息 / Send message label, if this build exposes one;
     *   2. a clickable, enabled, visible control on roughly the same row as the composer
     *      and to its right - the geometric position of the send arrow.
     * Returns null when neither is provable; the caller then clears the composer rather
     * than leaving text behind.
     */
    private AccessibilityNodeInfo findSendMessage(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo composer = findEditable(root);
        Rect composerBounds = new Rect();
        if (composer != null) composer.getBoundsInScreen(composerBounds);

        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        AccessibilityNodeInfo geometric = null;
        while (!queue.isEmpty() && guard++ < 5000) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n.isVisibleToUser() && n.isEnabled()) {
                CharSequence d = n.getContentDescription();
                String label = d == null ? "" : d.toString();
                if ("发送消息".contentEquals(label) || "Send message".contentEquals(label)) {
                    AccessibilityNodeInfo clickable = n;
                    while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
                    if (clickable != null && clickable.isEnabled()) return clickable;
                }
                if (composer != null && n.isClickable()) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (geometric == null && SendButtonGeometry.looksLikeSendButton(
                            composerBounds.left, composerBounds.top, composerBounds.right, composerBounds.bottom,
                            b.left, b.top, b.right, b.bottom)) {
                        geometric = n;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return geometric;
    }

    /** @param knownRoot the tree already read this tick, to skip a second enumeration. */
    private void startRecording(long now, AccessibilityNodeInfo knownRoot) {
        ActionResult result = clickFresh(true, knownRoot);
        if (result == ActionResult.SUCCESS) {
            state = State.WAIT_RECORD; deadline = now + TRANSITION_TIMEOUT;
        } else if (result == ActionResult.UNCERTAIN || result == ActionResult.ABORT) {
            // Keep trying. The caller stays in its waiting state, which has its own bound
            // (ARMED) or none at all (waiting for the entry), so this cannot spin forever
            // without the user noticing. Ending the run here is what made "come back from
            // the home screen" look like it paused itself.
            recordingReady = false;
            update("开录动作暂时不明确，正在重试");
        }
    }
    private ActionResult clickFresh(boolean voice) {
        return clickFresh(voice, null);
    }

    /**
     * @param knownRoot the tree this tick already read, when the caller has one. Passing
     *                  it avoids a second full window enumeration on every recording tick;
     *                  the enumeration walks every window and asks each for its root.
     */
    private ActionResult clickFresh(boolean voice, AccessibilityNodeInfo knownRoot) {
        AccessibilityNodeInfo root = knownRoot != null ? knownRoot : chatGptRoot();
        if (root == null) return ActionResult.NOT_READY;
        if (!GPT.contentEquals(root.getPackageName())) return ActionResult.ABORT;
        if (root.getWindowId() != windowId) {
            // The window id changes whenever ChatGPT rebuilds its window - which is exactly
            // what happens when the user comes back from another app. Treating that as a
            // fatal ABORT is what paused the run right after returning. Re-learn it and
            // retry on the next tick instead.
            windowId = root.getWindowId();
            contextResetPending = true;
            return ActionResult.NOT_READY;
        }
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked()) return ActionResult.ABORT;
        Snapshot fresh = scan(root);
        if (fresh.ambiguous) return ActionResult.ABORT;
        // The composer is ignored here on purpose: whether the box has text has no
        // bearing on whether recording may start.
        if (fresh.incomplete) return ActionResult.NOT_READY;
        if (voice && (fresh.voice == null || fresh.recording || fresh.send != null)) return ActionResult.NOT_READY;
        if (!voice && (fresh.send == null || !fresh.recording || fresh.voice != null)) return ActionResult.NOT_READY;
        AccessibilityNodeInfo target = voice ? fresh.voice : fresh.send;
        if (!target.refresh()) return ActionResult.NOT_READY;
        return click(target);
    }
    private ActionResult click(AccessibilityNodeInfo label) {
        AccessibilityNodeInfo node = label;
        while (node != null) {
            if (node.isClickable()) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (!RecordingFlowPolicy.actionReady(node.isEnabled(), node.isVisibleToUser(), !bounds.isEmpty())) return ActionResult.NOT_READY;
                if (!GPT.contentEquals(node.getPackageName())) return ActionResult.ABORT;
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? ActionResult.SUCCESS : ActionResult.UNCERTAIN;
            }
            node = node.getParent();
        }
        return ActionResult.NOT_READY;
    }
    private static final class Snapshot {
        AccessibilityNodeInfo voice, send, cancel, stop;
        boolean recording, draft;
        /** Text currently in the composer, or null when it is empty. Never logged. */
        String draftText;
        /** The composer still shows its empty-chat placeholder. */
        boolean emptyComposer;
        /** How many of the new-chat suggestion rows are visible (0, 1 or 2). */
        int newChatSuggestionRows;
        /** The composer shows the brand-new-chat placeholder (询问 ChatGPT). */
        boolean newChatPlaceholder;
        /** The composer says 回复 ChatGPT / Message ChatGPT: prior conversation exists. */
        boolean existingChatPlaceholder;
        /** The 聊天 / 工作 tabs are both on screen: this is the chat page. */
        boolean chatTabs;
        /** Real duplicate controls: genuinely ambiguous, never safe to act on. */
        boolean ambiguous;
        /** Tree traversal was incomplete this tick; retry instead of stopping. */
        boolean incomplete;
        /**
         * This snapshot came from the localized recording-bar scan, not the whole screen.
         * Fields that live outside the bar (draft, tabs, suggestion rows) are not observed
         * and must not be read as absent.
         */
        boolean recordingArea;
    }
    /**
     * Snapshot of the whole screen. Used outside RECORDING, where the draft, the tabs and
     * the new-chat suggestion rows all matter.
     */
    private Snapshot scan(AccessibilityNodeInfo root) {
        return scan(root, false);
    }

    /**
     * Scan the recording bar only, falling back to the full tree.
     *
     * While a segment is recording the tick only needs the recording controls: their
     * presence proves the UI is still recording, and the crop rectangle is derived from
     * their bounds. Walking the entire screen for that is 130-227 nodes with several
     * cross-process calls each, every 400 ms - the dominant CPU cost of a lecture run.
     *
     * The anchor is the contentDescription labels themselves (取消录制 / 停止录制), never a
     * screen position: the on-screen keyboard moves the bar from y~2299 to y~1419 and a
     * coordinate rule would break. The common ancestor of the three controls is the
     * container; on the real dumps it holds 27 nodes instead of 113-227.
     *
     * Correctness comes first. This returns a full scan unless ALL of these hold:
     *  - exactly one 取消录制 and one 停止录制 exist, and they share a container
     *  - 点击发送录音 exists and is inside that same container subtree
     *  - the container subtree contains no editable node (the composer is outside it)
     *  - the subtree contains no duplicate control
     * Otherwise the caller gets the same result it always had.
     */
    private Snapshot scanRecordingArea(AccessibilityNodeInfo root) {
        // Fast path: reuse the container path learned on an earlier tick, then verify the
        // subtree with exactly the same checks a fresh search would apply.
        if (recordingPath != null) {
            AccessibilityNodeInfo container = resolvePath(root, recordingPath);
            // A cached path that resolves back to the whole screen saves nothing; treat it
            // exactly like a missing container so the normal anchored search runs.
            if (ScanLabels.isUsableRecordingContainer(container != null, container != null && container.equals(root))) {
                AccessibilityNodeInfo[] found = inspectRecordingSubtree(container);
                if (found != null) {
                    if (DEBUG_VISIBILITY) {
                        diagBuf.append("  local scan path depth=").append(recordingPath.length).append('\n');
                        diagPending++;
                    }
                    return scan(container, true);
                }
            }
            recordingPath = null;   // layout changed: fall back and re-learn
        }
        AccessibilityNodeInfo cancel = null, stop = null;
        int cancelCount = 0, stopCount = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty()) {
            if (++count > 5000) return fallbackScan(root, "anchor_walk_incomplete");
            AccessibilityNodeInfo node = queue.removeFirst();
            if (GPT.contentEquals(node.getPackageName())) {
                CharSequence desc = node.getContentDescription();
                String label = desc == null ? "" : desc.toString();
                if ("取消录制".equals(label)) { cancel = node; cancelCount++; }
                else if ("停止录制".equals(label)) { stop = node; stopCount++; }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        // Exactly one of each: a duplicate makes the decision ambiguous, never localize.
        if (cancelCount != 1 || stopCount != 1) return fallbackScan(root, "anchor_count=" + cancelCount + "/" + stopCount);
        AccessibilityNodeInfo container = commonAncestor(cancel, stop);
        if (!ScanLabels.isUsableRecordingContainer(container != null, container != null && container.equals(root))) {
            return fallbackScan(root, "no_container");
        }
        if (inspectRecordingSubtree(container) == null) return fallbackScan(root, "subtree_incomplete");
        recordingPath = pathOf(root, container);
        if (DEBUG_VISIBILITY) {
            diagBuf.append("  local scan learned path depth=").append(recordingPath.length).append('\n');
            diagPending++;
        }
        return scan(container, true);
    }

    /**
     * Verifies a candidate container really is the recording bar. Returns the three controls
     * when it is, null when it is not (the caller then scans the whole tree).
     *
     * This is the single gate for both the cached-path fast route and a freshly found
     * container, so the fast route can never be weaker than the slow one.
     */
    private AccessibilityNodeInfo[] inspectRecordingSubtree(AccessibilityNodeInfo container) {
        boolean sendInside = false, editableInside = false;
        int cancelInside = 0, stopInside = 0, nodes = 0;
        AccessibilityNodeInfo cancel = null, stop = null, send = null;
        ArrayDeque<AccessibilityNodeInfo> sub = new ArrayDeque<>();
        sub.add(container);
        while (!sub.isEmpty()) {
            if (++nodes > 5000) return null;
            AccessibilityNodeInfo node = sub.removeFirst();
            if (GPT.contentEquals(node.getPackageName())) {
                CharSequence desc = node.getContentDescription();
                String label = desc == null ? "" : desc.toString();
                if ("点击发送录音".equals(label)) { sendInside = true; send = node; }
                if ("取消录制".equals(label)) { cancelInside++; cancel = node; }
                if ("停止录制".equals(label)) { stopInside++; stop = node; }
                if (node.isEditable()) editableInside = true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) sub.add(child);
            }
        }
        if (!ScanLabels.recordingSubtreeCoversAll(true, cancelInside, stopInside, sendInside, editableInside)) {
            return null;
        }
        return new AccessibilityNodeInfo[]{cancel, stop, send};
    }

    /** Follow a cached child-index path from root. Null when the layout no longer matches. */
    private static AccessibilityNodeInfo resolvePath(AccessibilityNodeInfo root, int[] path) {
        if (!ScanLabels.isUsableCachedPath(path)) return null;
        AccessibilityNodeInfo cur = root;
        for (int index : path) {
            if (cur == null || !ScanLabels.childIndexInRange(index, cur.getChildCount())) return null;
            cur = cur.getChild(index);
        }
        return cur;
    }

    /** Child-index path from root down to a node, as used by {@link #resolvePath}. */
    private static int[] pathOf(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        java.util.ArrayList<Integer> reversed = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo cur = node; cur != null && !cur.equals(root); cur = cur.getParent()) {
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) return null;
            int index = -1;
            for (int i = 0; i < parent.getChildCount(); i++) {
                AccessibilityNodeInfo child = parent.getChild(i);
                if (child != null && child.equals(cur)) { index = i; break; }
            }
            if (index < 0) return null;
            reversed.add(index);
        }
        int[] path = new int[reversed.size()];
        for (int i = 0; i < path.length; i++) path[i] = reversed.get(path.length - 1 - i);
        return path;
    }

    /** Full scan after a failed localization, with a diagnostic so the rate is visible. */
    private Snapshot fallbackScan(AccessibilityNodeInfo root, String reason) {
        if (DEBUG_VISIBILITY) {
            diagBuf.append("  local scan fallback: ").append(reason).append('\n');
            diagPending++;
        }
        return scan(root);
    }

    /** Walk parent links until two nodes meet. Null when they are in different windows. */
    private static AccessibilityNodeInfo commonAncestor(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        java.util.HashSet<AccessibilityNodeInfo> seen = new java.util.HashSet<>();
        for (AccessibilityNodeInfo n = a; n != null; n = n.getParent()) seen.add(n);
        for (AccessibilityNodeInfo n = b; n != null; n = n.getParent()) if (seen.contains(n)) return n;
        return null;
    }

    /**
     * @param recordingArea true when {@code root} is the recording-bar container rather than
     *                      the whole screen. In that mode the draft/tab/suggestion bookkeeping
     *                      is skipped: the composer is outside the bar, so its absence there
     *                      must not be read as "no draft".
     */
    private Snapshot scan(AccessibilityNodeInfo root, boolean recordingArea) {
        Snapshot s = new Snapshot();
        s.recordingArea = recordingArea;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0, recordingCount = 0, chatTab = 0, workTab = 0;
        // A control can be reported as "not visible to user" while it is plainly painted
        // on screen. Measured during a real recording: uiautomator listed 取消录制 and
        // 停止录制, while this service's own tree did not, so the run decided the recording
        // screen was unconfirmed and stopped in the middle of a sentence. Keeping a
        // bounds-only candidate per label is the fallback; a visible node always wins, and
        // only visible duplicates count as ambiguity.
        AccessibilityNodeInfo voiceAlt = null, sendAlt = null, cancelAlt = null, stopAlt = null;
        while (!queue.isEmpty()) {
            if (++count > 5000) { s.incomplete = true; break; }
            AccessibilityNodeInfo node = queue.removeFirst();
            if (GPT.contentEquals(node.getPackageName())) {
                /*
                 * Cheap checks first.
                 *
                 * The previous version called getBoundsInScreen(), isVisibleToUser(),
                 * getText(), getContentDescription(), getHintText() and isEditable() on
                 * EVERY node in the tree -- about six cross-process calls per node, 219
                 * nodes on this screen, every tick. That is the dominant CPU cost of a
                 * lecture run.
                 *
                 * The labels we act on are content descriptions, and only a handful of
                 * nodes carry one. Reading the description once and testing it before any
                 * of the expensive calls cuts the per-node work to one call for the vast
                 * majority of nodes.
                 */
                CharSequence desc = node.getContentDescription();
                String label = desc == null ? "" : desc.toString();
                boolean interesting = ScanLabels.isInterestingLabel(label);
                boolean editable = node.isEditable();
                /*
                 * Text must be read for more than the labelled controls.
                 *
                 * Several things this scan needs are drawn as ordinary TextViews whose only
                 * content is their TEXT, with an empty content description: the 聊天 / 工作
                 * tabs and the "撰写或编辑" / "创建图像或贴纸" suggestion rows. Reading the
                 * label alone made all of them invisible, which silently disabled the tab
                 * page marker and the suggestion-row path that session naming depends on.
                 * Verified against a real device dump: 4 of 130 nodes carry text, 6 carry a
                 * description, and none carries both.
                 *
                 * The read is still gated cheaply: only nodes with an empty description (or
                 * one of our known labels) are read. A node with an unrelated description is
                 * a button such as 菜单 / 附件 / 临时聊天 / 开始语音对话, and the scan never
                 * matches those against text, so one cross-process call is skipped there.
                 */
                boolean readText = ScanLabels.needsText(label, node.getClassName() == null ? "" : node.getClassName().toString());
                CharSequence text = readText ? node.getText() : null;
                String ownText = text == null ? "" : text.toString();
                boolean needGeometry = interesting || editable || !ownText.isEmpty();
                Rect bounds = null;
                boolean painted = false, visible = false;
                if (needGeometry) {
                    bounds = new Rect(); node.getBoundsInScreen(bounds);
                    painted = !bounds.isEmpty();
                    visible = node.isVisibleToUser();
                } else if (label.isEmpty() && ownText.isEmpty()) {
                    // Nothing this scan cares about on this node.
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child); else s.incomplete = true;
                    }
                    continue;
                }
                if (visible || painted || (!interesting && editable)) {
                    if ("语音输入".contentEquals(label)) {
                        if (visible) { if (s.voice != null) s.ambiguous = true; else s.voice = node; }
                        else if (voiceAlt == null) voiceAlt = node;
                    }
                    if ("点击发送录音".contentEquals(label)) {
                        if (visible) { if (s.send != null) s.ambiguous = true; else s.send = node; }
                        else if (sendAlt == null) sendAlt = node;
                    }
                    if ("取消录制".contentEquals(label)) {
                        s.recording = true;
                        if (visible) { s.cancel = node; if (++recordingCount > 1) s.ambiguous = true; }
                        else if (cancelAlt == null) cancelAlt = node;
                    }
                    if ("停止录制".contentEquals(label)) {
                        if (visible) { if (s.stop != null) s.ambiguous = true; else s.stop = node; }
                        else if (stopAlt == null) stopAlt = node;
                    }
                    // The chat screen carries the 聊天 / 工作 tabs at the top. That pair is a
                    // far more stable page marker than the suggestion cards, which this
                    // build omits from many brand-new chats. A recording-bar scan never
                    // contains them, so it must not report them as absent either.
                    if (!recordingArea) {
                        if ("聊天".contentEquals(label) || "聊天".contentEquals(ownText)) chatTab++;
                        if ("工作".contentEquals(label) || "工作".contentEquals(ownText)) workTab++;
                    }
                    if (!recordingArea && editable && !node.isShowingHintText()
                            && text != null && text.toString().trim().length() > 0) {
                        s.draft = true;
                        s.draftText = text.toString();
                    }
                    // The empty-chat placeholder appears either as hint text or as the
                    // node text. Either way it means nothing has been typed yet.
                    if (!recordingArea) {
                        CharSequence hint = node.getHintText();
                        String hintText = hint == null ? "" : hint.toString();
                        if (editable && (ScanLabels.isExistingChatPlaceholder(hintText)
                                || ScanLabels.isExistingChatPlaceholder(ownText))) {
                            s.existingChatPlaceholder = true;
                        }
                        // The new-chat prompt is drawn as its own label, NOT as the composer's
                        // hint text. Looking only inside the editable node found nothing, so a
                        // pristine chat was never named (measured: placeholder=false on a page
                        // where the label was plainly there). Check every node.
                        if (!s.newChatPlaceholder && isBrandNewChatPlaceholder(ownText)) {
                            s.newChatPlaceholder = true;
                        }
                        if (editable && (isComposerPlaceholder(hintText) || isComposerPlaceholder(ownText))) {
                            s.emptyComposer = true;
                            if (isBrandNewChatPlaceholder(hintText) || isBrandNewChatPlaceholder(ownText)) {
                                s.newChatPlaceholder = true;
                            }
                        }
                        // A brand-new chat is also identified by its two suggestion rows above
                        // the composer: "撰写或编辑" and "创建图像或贴纸". Nothing is read or
                        // counted from message text; only these fixed labels are looked for.
                        if (isNewChatRow(label) || isNewChatRow(ownText)) {
                            s.newChatSuggestionRows++;
                        }
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                // A recycled child during layout is transient. Marking it ambiguous here
                // aborted real recordings ("控件不唯一") while ChatGPT was still fine.
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child); else s.incomplete = true;
            }
        }
        if (s.voice == null) s.voice = voiceAlt;
        if (s.send == null) s.send = sendAlt;
        if (s.cancel == null) s.cancel = cancelAlt;
        if (s.stop == null) s.stop = stopAlt;
        s.chatTabs = chatTab > 0 && workTab > 0;
        return s;
    }

    /**
     * Label / text rules live in {@link ScanLabels} so the host tests can cover them
     * without Android classes. These wrappers keep the scan readable.
     */
    private static boolean isInterestingLabel(String label) { return ScanLabels.isInterestingLabel(label); }

    /** The two suggestion rows ChatGPT shows above the composer in an empty chat. */
    private static boolean isNewChatRow(String text) { return ScanLabels.isNewChatRow(text); }

    /** Placeholders ChatGPT shows in an untouched composer. */
    private static boolean isComposerPlaceholder(String text) { return ScanLabels.isComposerPlaceholder(text); }

    /**
     * Placeholder of a chat that has no messages yet. This build says 询问 ChatGPT here
     * and 回复 ChatGPT once the conversation has content, so the wording separates a
     * brand-new session from a continued one without reading any message text.
     */
    private static boolean isBrandNewChatPlaceholder(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("询问 ChatGPT") || t.startsWith("Ask anything");
    }

    private int[] cropFor(Snapshot s) {
        if (s.cancel == null || s.stop == null) return null;
        Rect a = new Rect(), b = new Rect(); s.cancel.getBoundsInScreen(a); s.stop.getBoundsInScreen(b);
        // Bounds are the authority here, not the visibility flag: the recording controls
        // report isVisibleToUser=false on this device while being fully drawn.
        if (a.isEmpty() || b.isEmpty()) return null;
        Point screen = new Point(); ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(screen);
        return WaveformSamplingPolicy.crop(new int[]{a.left,a.top,a.right,a.bottom},
                new int[]{b.left,b.top,b.right,b.bottom}, screen.x, screen.y);
    }

    private void invalidateWaveform(long now, String reason) {
        invalidateWaveform(now, reason, null);
    }

    /**
     * @param detail optional extra fields appended to the wave-invalid line, e.g. the expected
     *               vs actual frame size. Null keeps the line exactly as before.
     */
    private void invalidateWaveform(long now, String reason, String detail) {
        lastWaveform = null; lastWaveformAt = 0;
        if (DEBUG_VISIBILITY) {
            // Why the visual verdict was dropped. Without this every invalid frame looked
            // identical in the log and the cause had to be guessed after the run.
            diagBuf.append("  wave-invalid reason=").append(reason);
            if (detail != null) diagBuf.append(' ').append(detail);
            diagBuf.append('\n');
            diagPending++;
        }
        if (detector != null) {
            try { detector.observe(null, 0, 0, now, requiredPauseMs(now)); }
            catch (RuntimeException e) { detector = null; }
        }
        if (adaptivePause != null) adaptivePause.observe(WaveformDetector.Signal.INVALID, now, false);
    }

    private void resetVisualContext(long now) {
        // A window/layout change is not a reason to forget the room. The learned level
        // distribution stays valid (activity is normalised by the crop height); only the
        // unfinished decision is dropped. This path runs on every window event, so resetting
        // everything here meant the detector re-entered its eight-sample warm-up over and over
        // and effectively never produced a verdict. The environment is learned once, when the
        // assistant starts.
        if (detector != null) detector.resetDecisionOnly();
        if (adaptivePause != null) adaptivePause.reset();
        segmentVisualActiveMs = 0; cropTracker.resetSession(); contextResetPending = false;
        recordingPath = null;   // the window/layout this path referred to is gone
        invalidateWaveform(now, "layout_changed");
        showWaveformStatus("界面变化，重新检测停顿信号");
    }

    private void showWaveformStatus(String status) {
        if (status.equals(waveformStatus)) return;
        waveformStatus = status;
        if (state == State.RECORDING) {
            update("第 " + segment + " 段录音中；" + status + "；倾向：" + preferenceLabel()
                    );
        }
    }
    private String preferenceLabel() { return pausePreference < 0 ? "保守 " + pausePreference : pausePreference > 0 ? "灵敏 +" + pausePreference : "标准"; }
    /** The learned normal pause, before any deadline pressure is applied. */
    private long normalPauseMs() {
        double factor = noiseAdaptation.pauseFactor();
        return adaptivePause == null ? Math.round(AdaptivePausePolicy.DEFAULT_MS * factor)
                : adaptivePause.requiredPauseMs(pausePreference, segmentVisualActiveMs, factor);
    }

    /**
     * Pause the detector must observe at this instant.
     *
     * The learned value is the ceiling-side anchor; how far the segment has progressed
     * decides what is actually demanded. Deriving progress from {@code recordUntil - now}
     * (not {@code segmentVisualActiveMs}) keeps the curve monotone across crop resets and
     * noise rebaselines. Before a segment exists {@code recordUntil} is 0, which is treated
     * as "no progress yet" so the cap applies instead of a bogus fraction.
     */
    private long requiredPauseMs(long now) {
        long normal = normalPauseMs();
        long maxMs = duration * 1000L;
        long remaining = recordUntil <= 0 ? maxMs : Math.max(0, recordUntil - now);
        double f = DeadlinePressurePolicy.elapsedFraction(remaining, maxMs);
        return DeadlinePressurePolicy.requiredPauseMs(f, normal, pausePreference);
    }

    /** Keep the detector's extra margin in step with the manual level plus live noise.
     * @return true when the margin changed enough to rebuild the comparison window */
    private boolean refreshActivityMargin(long now) {
        if (detector == null) return false;
        double target = NoiseIgnorePolicy.thresholdMargin(settings.noiseIgnore) + noiseAdaptation.autoActivityMargin();
        if (!ActivityMarginChange.requiresRebaseline(appliedActivityMargin, target)) return false;
        boolean tightening = ActivityMarginChange.isTightening(appliedActivityMargin, target);
        appliedActivityMargin = target;
        detector.setActivityMargin(target);
        // Raising the bar mid-sentence can reclassify ongoing speech as "quiet". Acting
        // on that instant would be a false pause send, so start a fresh comparison
        // window instead. Learned pause samples are kept; only the unfinished cycle drops.
        if (state == State.RECORDING) {
            detector.resetDecisionOnly();
            if (adaptivePause != null) adaptivePause.beginSegment();
            segmentVisualActiveMs = 0; lastWaveform = null; lastWaveformAt = 0;
            showWaveformStatus(tightening ? "噪音升高，重新建立波形参考" : "噪音降低，重新建立波形参考");
            return true;
        }
        return false;
    }

    private void watchCapture(long now) {
        if (capturePending && !captureTimedOut && now - captureRequestedAt >= WaveformSamplingPolicy.TIMEOUT_MS) {
            captureTimedOut = true; abandonCapture(); invalidateWaveform(now, "capture_timeout"); diagnostics.marker("waveform_timeout", false);
            showWaveformStatus("停顿信号不可用，按最长时长发送（截图超时）");
        }
        if (lastWaveformAt != 0 && !WaveformSamplingPolicy.fresh(now, lastWaveformAt)) {
            invalidateWaveform(now, "capture_expired"); showWaveformStatus("停顿信号不可用，按最长时长发送（采样过期）");
        }
    }

    private void abandonCapture() {
        requestSerial++; capturePending = false;
        if (waveformCapture != null) waveformCapture.abandon();
    }

    /**
     * Translate a detector result into the only question the sampler asks: is there sound?
     * Null means "cannot tell", which must not change the cadence in either direction.
     */
    private static Boolean signalVerdict(WaveformDetector.Result r) {
        if (r == null) return null;
        if (r.signal == WaveformDetector.Signal.VISUAL_ACTIVE) return Boolean.TRUE;
        if (r.signal == WaveformDetector.Signal.VISUAL_QUIET) return Boolean.FALSE;
        return null;
    }

    private void requestWaveform(int[] crop, long now) {
        // Still single-flight: one capture outstanding at a time, never re-entered.
        if (capturePending) return;
        long minGap = pausePlausible() ? CAPTURE_PAUSE_MS : sampling.intervalMs(now);
        if (lastCaptureAt != 0 && now - lastCaptureAt < minGap) return;
        if (waveformCapture == null) { capturePending = false; return; }
        final int run = generation, part = segment, win = windowId, serial = ++requestSerial;
        final int[] expected = crop.clone();
        final boolean projectionSource = waveformCapture instanceof ProjectionCapture;
        capturePending = true; captureTimedOut = false; captureRequestedAt = now; prevCaptureAt = lastCaptureAt; lastCaptureAt = now;
        captureSnapshotSerial = lastSnapshotSerial;
        boolean accepted = waveformCapture.request(new Rect(crop[0], crop[1], crop[2], crop[3]), (pixels, width, height, timeMs, error) -> {
            if (serial != requestSerial) return;
            capturePending = false;
            if (!WaveformSamplingPolicy.callbackCurrent(run, generation, part, segment, win, windowId,
                    state == State.RECORDING, captureTimedOut) || detector == null) return;
            try {
            long current = SystemClock.elapsedRealtime();
            if (!recordingReady) { invalidateWaveform(current, "recording_recheck"); showWaveformStatus("停顿信号不可用，按最长时长发送（录音界面复查中）"); return; }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
            KeyguardManager lock = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if (root == null || !GPT.contentEquals(root.getPackageName()) || root.getWindowId() != win
                    || !power.isInteractive() || lock.isKeyguardLocked()) { invalidateWaveform(current, "tree_stale"); return; }
            /*
             * Reuse the snapshot the tick just took instead of walking the whole tree again.
             *
             * This verification only needs to know that the recording UI is still the same
             * shape it was when the screenshot was requested: same controls present, same
             * crop rectangle. The tick's own scan already answered that, a few hundred
             * milliseconds earlier, and the window id above still matches. Scanning a third
             * time per tick was the single largest remaining CPU cost of a lecture run.
             *
             * If the tick has not produced a snapshot since this capture was requested, fall
             * back to a fresh scan so the check is never skipped outright. The strict
             * comparison matters: the tick's own scan carries the same timestamp as the
             * request, and accepting it verified the screenshot against a tree read before
             * the picture was even taken.
             */
            Snapshot fresh = lastSnapshot;
            if (fresh == null || !WaveformSamplingPolicy.snapshotNewerThanRequest(
                    lastSnapshotSerial, captureSnapshotSerial)) fresh = scan(root);
            /*
             * Freshness rule, split by frame source.
             *
             * The accessibility path is unchanged: a screenshot is taken on request, so a
             * sample must be genuinely fresh by FRESH_MS or it is not evidence.
             *
             * A projection frame is NOT taken on request. ProjectionCapture serves the
             * newest published frame and reports its real producer timestamp; a still
             * screen publishes nothing, so during a genuine pause the newest frame is
             * legitimately older than FRESH_MS. Rejecting that frame would blind the pause
             * detector exactly when it matters, which is the whole point of this layer.
             * Accepting it is correct because a waveform that stops changing IS the pause
             * signal. The risk being guarded against is not "old pixels" but "frozen app or
             * dead producer": the 10 s bound catches a dead service, and the live
             * recording-UI check below plus the unchanged crop identity still prove the
             * picture belongs to the recording bar we are looking at now.
             */
            boolean sampleTimely = !projectionSource
                    || WaveformSamplingPolicy.fresh(current, timeMs)
                    || (ProjectionCapture.hasFrame()
                        && ProjectionCapture.lastFrameAgeMs() >= 0
                        && ProjectionCapture.lastFrameAgeMs() <= PROJECTION_STATIC_MAX_AGE_MS);
            boolean projectionStatic = projectionSource
                    && !WaveformSamplingPolicy.fresh(current, timeMs);
            String sourceTag = projectionSource ? (projectionStatic ? "proj_static" : "proj") : "a11y";
            if (fresh.ambiguous || fresh.incomplete || !fresh.recording || fresh.voice != null || fresh.send == null
                    || !Arrays.equals(expected, cropFor(fresh)) || !sampleTimely
                    || !WaveformSamplingPolicy.fresh(current, captureRequestedAt)) {
                invalidateWaveform(current, "recording_recheck"); showWaveformStatus("停顿信号不可用，按最长时长发送（界面或截图已变化）"); return;
            }
            /*
             * The frame source may not return the crop at screen resolution.
             *
             * ProjectionCapture renders the virtual display at 0.5x the physical screen, so
             * its buffer is legitimately half-size: comparing against the screen-space crop
             * rejected EVERY projection frame as "capture_expired" (260 on the device, zero
             * wave lines). Expect the SOURCE's real size instead.
             *
             * The conversion lives in ProjectionGeometry so the exact rounding rule is
             * covered by host tests. It must stay round(right*scale) - round(left*scale):
             * round((right-left)*scale) differs by one pixel whenever a scaled edge lands
             * on a .5 boundary, which is the 0.9.0 bug that rejected a quarter of all good
             * frames. Accessibility screenshots stay pixel-exact. Pixels are never
             * upscaled: the detector normalises amplitudes by pixel height, so
             * manufacturing full-res pixels would fabricate measurements.
             */
            int expectedWidth, expectedHeight;
            if (projectionSource) {
                double scale = ProjectionCapture.scale();
                expectedWidth = ProjectionGeometry.expectedWidth(expected[0], expected[2], scale);
                expectedHeight = ProjectionGeometry.expectedHeight(expected[1], expected[3], scale);
            } else {
                expectedWidth = expected[2] - expected[0];
                expectedHeight = expected[3] - expected[1];
            }
            if (error != null || pixels == null || width != expectedWidth || height != expectedHeight) {
                // Log the exact numbers: any residual mismatch is then visible in one line.
                invalidateWaveform(current, "capture_expired", "exp=" + expectedWidth + "x" + expectedHeight
                        + " got=" + width + "x" + height
                        + " crop=[" + expected[0] + "," + expected[1] + "," + expected[2] + "," + expected[3] + "]");
                showWaveformStatus("停顿信号不可用，按最长时长发送（截图失败）"); return;
            }
            // staticFrameVerifiedLive=true is justified here: this callback has already
            // checked capture freshness (strict FRESH_MS for accessibility, or the bounded
            // static-frame rule above for projection), the live recording UI, the crop
            // identity and an error-free bitmap. A still picture in that context means
            // "no waveform movement" — quiet — rather than "the app froze". Passing false
            // made every quiet stretch unclassifiable, so pause-based sending could never
            // fire and every segment ended at the maximum duration instead.
            //
            // The detector's clock must be the observation time, not the producer stamp.
            // A projection producer publishes nothing while the screen is still, so every
            // request during a pause carries the SAME producer timestamp; feeding that to
            // the detector would trip its non_monotonic_time guard (timestampMs <= previous)
            // and wipe pauseMs on every frame — the pause could never accumulate. The real
            // producer timestamp is still what the freshness rule above evaluates; this
            // clock only says when we observed the picture, and for a static screen the
            // picture we observe now is the one that is still on screen now.
            long sampleTime = projectionSource ? current : timeMs;
            long pauseRequired = requiredPauseMs(current);
            lastWaveform = detector.observe(pixels, width, height, sampleTime, pauseRequired, true);
            diagnostics.waveform(lastWaveform, segment, pauseRequired);
            observeEnvironment(lastWaveform, sampleTime);
            // Drive the screenshot cadence: sound present -> keep sampling fast, because a
            // pause can start at any moment; measured silence -> slow down, since there is
            // nothing to catch until the voice returns. An unclassifiable frame passes null
            // and leaves the cadence alone.
            sampling.observe(signalVerdict(lastWaveform), sampleTime);
            segmentVisualActiveMs = lastWaveform.visualActiveMs;
            // A frame the detector cannot separate from background is the honest signal
            // that the room is noisy or the picture is unstable. Count it, then let the
            // margin and the required pause follow it (both bounded, both reversible).
            if (DEBUG_VISIBILITY) {
                diagBuf.append("  wave sig=").append(lastWaveform.signal)
                        .append(" dec=").append(lastWaveform.decision)
                        .append(" reason=").append(lastWaveform.reason)
                        .append(" act=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.activity))
                        .append(" floor=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.noiseFloor))
                        .append(" thr=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.threshold))
                        .append(" long=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.longActivity))
                        .append('\n');
                flushDiag();
            }
            boolean ambiguousFrame = lastWaveform.signal == WaveformDetector.Signal.INVALID
                    && ("indistinguishable_activity".equals(lastWaveform.reason)
                        || "baseline_shift".equals(lastWaveform.reason));
            if (DEBUG_VISIBILITY && lastWaveform != null) {
                diagBuf.append("  wave sig=").append(lastWaveform.signal)
                        .append(" reason=").append(lastWaveform.reason)
                        .append(" act=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.activity))
                        .append(" floor=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.noiseFloor))
                        .append(" thr=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.threshold))
                        .append(" long=").append(String.format(java.util.Locale.US, "%.3f", lastWaveform.longActivity))
                        .append(" pause=").append(lastWaveform.pauseMs)
                        // Which screenshot cadence tier was in use. This is the number to
                        // watch when tuning power: a run stuck in the fast tier while the
                        // room is silent means the slow ladder is not engaging.
                        .append(" cad=").append(sampling.slowed(current) ? sampling.intervalMs(current) : 0)
                        // Measured gap between this capture request and the previous one.
                        // The configured value is only a gate; this is what actually happened.
                        .append(" cap=").append(prevCaptureAt == 0 ? -1 : captureRequestedAt - prevCaptureAt)
                        // Which frame source produced this sample: the projection service
                        // (fresh frame, or a still screen accepted by the bounded static
                        // rule) or the accessibility screenshot fallback.
                        .append(" src=").append(sourceTag)
                        // Cadence gate configured for this sample. requestWaveform() only
                        // runs inside tick(), so the effective interval is the tick period;
                        // this field reports the gate that was actually applied.
                        .append(projectionSource ? " gap=" + minGap : "")
                        .append('\n');
                flushDiag();
            }
            noiseAdaptation.observe(ambiguousFrame);
            // A rebaseline clears the detector and the current result, so this sample's
            // verdict belongs to a comparison window that no longer exists. Stop here:
            // feeding it on would train the pause policy on a discarded state, and every
            // later read of lastWaveform in this block would be a null dereference.
            if (refreshActivityMargin(current)) return;
            if (adaptivePause != null) adaptivePause.observe(lastWaveform.signal, sampleTime, true);
            lastWaveformAt = lastWaveform.signal == WaveformDetector.Signal.INVALID ? 0 : sampleTime;
            showWaveformStatus(lastWaveform.signal == WaveformDetector.Signal.INVALID
                    ? "停顿信号不可用，按最长时长发送（波形不明确）" : "视觉停顿检测中（试验）");
            if (state == State.RECORDING && loop != null) { handler.removeCallbacks(kick); handler.post(kick); }
            } catch (RuntimeException ex) {
                lastWaveform = null; lastWaveformAt = 0; detector = null;
                showWaveformStatus("停顿信号不可用，按最长时长发送（波形处理出错）");
            }
        });
        // Some synchronous failures invoke the callback before returning false.
        if (!accepted && capturePending && serial == requestSerial) {
            capturePending = false; invalidateWaveform(SystemClock.elapsedRealtime(), "capture_expired");
            showWaveformStatus("停顿信号不可用，按最长时长发送（截图请求失败）");
        }
    }

}
