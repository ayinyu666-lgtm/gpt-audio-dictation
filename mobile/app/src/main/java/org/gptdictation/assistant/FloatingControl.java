package org.gptdictation.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Always-on-top control: one tap starts or pauses, a long press opens the settings.
 *
 * Touch reliability notes (after real-device feedback that taps were often lost):
 *
 * - The tap target is deliberately larger than the drawn circle. The old 56 dp view
 *   made edge taps miss.
 * - "Drag" uses the platform touch slop, doubled. The old fixed 6-8 dp threshold
 *   treated ordinary finger wobble as a drag and silently discarded the tap.
 * - A tap is decided on release from total displacement and duration, not from a
 *   running flag, so a small wobble can never eat the click.
 * - Long press needs 750 ms. The old 500 ms fired during unhurried taps.
 * - Pressing fades the bubble so the user can see the touch landed.
 * - Every accepted tap calls performClick() as well, so accessibility sees a click.
 */
final class FloatingControl {
    interface Host {
        boolean isRunning();

        /** A run exists but the user parked it: the bubble still shows, reading 暂停中. */
        boolean isPaused();

        void onOverlayToggle();

        /** Called on every touch-down on the bubble, so the service can ignore it. */
        void onOverlayTouch();

        void onSettingsSaved(RecordingSettings settings, boolean diagnosticsEnabled);

        RecordingSettings currentSettings();

        boolean diagnosticsEnabled();
    }

    private static final int PANEL_BG = 0xEEFFFFFF;
    private static final int TEXT = 0xFF202124;
    private static final long LONG_PRESS_MS = 750;

    private final AccessibilityService service;
    private final Host host;
    private final Handler handler = new Handler();
    private final WindowManager wm;

    private View bubble;
    private WindowManager.LayoutParams bubbleParams;
    private TextView bubbleText;
    private View panel;
    private boolean attached;

    private float downX, downY;
    private int startX, startY;
    private long downAt;
    private boolean dragging;
    private boolean longPressFired;
    private final Runnable longPress = this::openPanel;

    FloatingControl(AccessibilityService service, Host host) {
        this.service = service;
        this.host = host;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    void show() {
        if (attached) return;
        bubble = buildBubble();
        bubbleParams = bubbleParams();
        try {
            wm.addView(bubble, bubbleParams);
            attached = true;
            refresh();
        } catch (RuntimeException e) {
            bubble = null; bubbleParams = null;
        }
    }

    void hide() {
        handler.removeCallbacksAndMessages(null);
        removePanel();
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
        }
        bubble = null; bubbleParams = null; bubbleText = null; attached = false;
    }

    void refresh() {
        if (bubbleText == null || bubble == null) return;
        bubbleText.setText(OverlayStatus.label(host.isRunning(), host.isPaused()));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(host.isRunning() ? 0xF2E8F0FE : 0xF2FFFFFF);
        bg.setStroke(dp(2), host.isRunning() ? 0xFF1A73E8 : 0xFF5F6368);
        bubble.setBackground(bg);
    }

    private View buildBubble() {
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        bubbleText = new TextView(service);
        bubbleText.setText(OverlayStatus.label(host.isRunning(), host.isPaused()));
        bubbleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bubbleText.setTextColor(TEXT);
        bubbleText.setGravity(Gravity.CENTER);
        box.addView(bubbleText);
        box.setContentDescription("听写助手：单击开始或暂停，长按打开设置");
        box.setOnTouchListener((v, event) -> onBubbleTouch(event));
        return box;
    }

    private boolean onBubbleTouch(MotionEvent event) {
        if (bubble == null || bubbleParams == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY();
                startX = bubbleParams.x; startY = bubbleParams.y;
                downAt = SystemClock.elapsedRealtime();
                dragging = false; longPressFired = false;
                bubble.setAlpha(0.55f);
                host.onOverlayTouch();
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && Math.hypot(dx, dy) > touchSlopPx() * 2) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                }
                if (dragging) {
                    bubbleParams.x = startX + (int) dx;
                    bubbleParams.y = startY + (int) dy;
                    try { wm.updateViewLayout(bubble, bubbleParams); } catch (RuntimeException ignored) { }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                bubble.setAlpha(1f);
                boolean moved = Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > touchSlopPx() * 2;
                long held = SystemClock.elapsedRealtime() - downAt;
                if (!dragging && !moved && !longPressFired && held < LONG_PRESS_MS) {
                    bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    bubble.performClick();
                    host.onOverlayToggle();
                    refresh();
                }
                dragging = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (bubble != null) bubble.setAlpha(1f);
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    private void openPanel() {
        longPressFired = true;
        if (bubble != null) bubble.setAlpha(1f);
        if (bubble != null) bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (panel != null) { removePanel(); return; }
        panel = buildPanel();
        try {
            wm.addView(panel, panelParams());
        } catch (RuntimeException e) {
            panel = null;
        }
    }

    private View buildPanel() {
        LinearLayout column = new LinearLayout(service);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(PANEL_BG);
        bg.setStroke(dp(1), 0xFF9AA0A6);
        column.setBackground(bg);

        TextView title = new TextView(service);
        title.setText("听写助手设置");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        column.addView(title);

        TextView hint = new TextView(service);
        hint.setText("保存后从下一次开始时生效");
        hint.setTextColor(0xFF5F6368);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        column.addView(hint);

        RecordingSettings current = host.currentSettings();
        final int[] seconds = {current.maxDurationSeconds};
        final int[] bias = {current.pauseBias};
        final int[] noise = {current.noiseIgnore};

        column.addView(stepper("每段最长秒数（5–1800）", () -> seconds[0] + " 秒",
                () -> seconds[0] = Math.min(RecordingSettings.MAX_SECONDS, seconds[0] + 5),
                () -> seconds[0] = Math.max(RecordingSettings.MIN_SECONDS, seconds[0] - 5)));

        column.addView(stepper("停顿激进程度（−9 … +9）", () -> biasLabel(bias[0]),
                () -> bias[0] = Math.min(RecordingSettings.MAX_PAUSE_BIAS, bias[0] + 1),
                () -> bias[0] = Math.max(RecordingSettings.MIN_PAUSE_BIAS, bias[0] - 1)));

        column.addView(stepper("噪音忽略（−9 … +9）", () -> NoiseIgnorePolicy.label(noise[0]),
                () -> noise[0] = Math.min(NoiseIgnorePolicy.MAX, noise[0] + 1),
                () -> noise[0] = Math.max(NoiseIgnorePolicy.MIN, noise[0] - 1)));

        CheckBox diagnostics = new CheckBox(service);
        diagnostics.setText("记录测试诊断");
        diagnostics.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diagnostics.setTextColor(TEXT);
        diagnostics.setChecked(host.diagnosticsEnabled());
        column.addView(diagnostics);

        LinearLayout buttons = new LinearLayout(service);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button save = new Button(service);
        save.setText("保存");
        save.setOnClickListener(v -> {
            host.onSettingsSaved(new RecordingSettings(seconds[0], bias[0], noise[0]), diagnostics.isChecked());
            removePanel();
            refresh();
        });
        buttons.addView(save);

        Button close = new Button(service);
        close.setText("关闭");
        close.setOnClickListener(v -> removePanel());
        buttons.addView(close);
        column.addView(buttons);

        ScrollView scroll = new ScrollView(service);
        scroll.addView(column);
        return scroll;
    }

    /** Label plus − value + row. No text field, so the overlay never needs the keyboard. */
    private View stepper(String label, java.util.function.Supplier<String> valueText,
                         Runnable plus, Runnable minus) {
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(service);
        title.setText(label);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(title);

        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button less = new Button(service);
        less.setText("−");
        less.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        less.setContentDescription(label + " 减少");

        TextView value = new TextView(service);
        value.setText(valueText.get());
        value.setTextColor(TEXT);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(120));

        Button more = new Button(service);
        more.setText("+");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        more.setContentDescription(label + " 增加");

        less.setOnClickListener(v -> { minus.run(); value.setText(valueText.get()); });
        more.setOnClickListener(v -> { plus.run(); value.setText(valueText.get()); });

        row.addView(less);
        row.addView(value);
        row.addView(more);
        box.addView(row);
        return box;
    }

    private static String biasLabel(int value) {
        if (value == 0) return "标准 0";
        return value < 0 ? "保守 " + value : "灵敏 +" + value;
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) { }
        }
        panel = null;
    }

    private float touchSlopPx() {
        return ViewConfiguration.get(service).getScaledTouchSlop();
    }

    private WindowManager.LayoutParams bubbleParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(72), dp(72),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = dp(140);
        return p;
    }

    private WindowManager.LayoutParams panelParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(310), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = dp(220);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
