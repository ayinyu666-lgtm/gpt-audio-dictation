package org.gptdictation.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Settings and status screen.
 *
 * <p>Layout rules used here:
 * - The screen is built from three visual levels: page background, white cards, and
 *   section captions. The previous version was one flat column, which is why it read
 *   as a wall of controls.
 * - Every value has a label plus a one-line explanation, so the numbers are not
 *   something the user has to guess about.
 * - Only framework views are used (no AppCompat, no Material), because the app ships
 *   with the bare platform theme declared in the manifest.
 *
 * <p>Nothing in this class changes recording behaviour: it reads the same static
 * service API the old screen used and writes the same preference keys.
 */
public final class MainActivity extends Activity {
    private static final int REFRESH_MS = 1000;
    /** Activity result code for the MediaProjection screen-capture consent dialog. */
    private static final int REQUEST_PROJECTION = 12;

    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private float density;

    private TextView serviceBadge;
    private TextView envValue;
    private TextView envRounds;
    private View stateDot;
    private TextView stateValue;
    private Button startButton;
    private Button stopButton;
    private TextView eventText;
    private TextView serviceHint;
    private TextView diagnosticValue;
    private TextView probeResult;
    private TextView projectionStatus;

    private SeekBar secondsBar;
    private SeekBar pauseBar;
    private SeekBar noiseBar;
    private TextView secondsValue;
    private TextView pauseValue;
    private TextView noiseValue;

    private String versionName = "未知";
    private long versionCode = 0;
    private Boolean lastConnected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        loadVersion();
        setContentView(buildScreen());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    // ---------------------------------------------------------------- screen

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.gad_background));
        // targetSdk 35 forces edge-to-edge on Android 15, and the bare platform theme
        // draws no action bar, so the content must claim the status-bar inset itself.
        scroll.setFitsSystemWindows(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int edge = (int) getResources().getDimension(R.dimen.gad_space_page);
        page.setPadding(edge, edge, edge, edge * 2);
        scroll.addView(page);

        page.addView(buildHeader(), matchWrap());
        addSection(page, R.string.section_status, buildStatusCard());
        addSection(page, R.string.section_environment, buildEnvironmentCard());
        addSection(page, R.string.section_recording, buildRecordingCard());
        addSection(page, R.string.section_advanced, buildAdvancedCard());
        addSection(page, R.string.section_about, buildAboutCard());
        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this, null, 0, R.style.GadAppTitle);
        title.setText(R.string.app_name);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = new TextView(this, null, 0, R.style.GadChip);
        version.setText("v" + versionName);
        version.setTextColor(getColor(R.color.gad_text_secondary));
        version.setBackground(pill(getColor(R.color.gad_surface), getColor(R.color.gad_divider)));
        titleRow.addView(version);
        header.addView(titleRow);

        TextView tagline = new TextView(this, null, 0, R.style.GadSubtitle);
        tagline.setText(R.string.app_tagline);
        LinearLayout.LayoutParams taglineParams = matchWrap();
        taglineParams.topMargin = dp(4);
        header.addView(tagline, taglineParams);
        return header;
    }

    private View buildStatusCard() {
        LinearLayout card = card();

        LinearLayout top = row();
        TextView serviceLabel = new TextView(this, null, 0, R.style.GadItemTitle);
        serviceLabel.setText(R.string.label_service);
        top.addView(serviceLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        serviceBadge = new TextView(this, null, 0, R.style.GadChip);
        top.addView(serviceBadge);
        card.addView(top);

        LinearLayout stateRow = row();
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(10);
        stateRow.setLayoutParams(stateParams);

        View dot = new View(this);
        dot.setBackgroundResource(R.drawable.gad_dot_status);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(8);
        dotParams.gravity = Gravity.CENTER_VERTICAL;
        stateRow.addView(dot, dotParams);
        stateDot = dot;

        TextView stateLabel = new TextView(this, null, 0, R.style.GadItemTitle);
        stateLabel.setText(R.string.label_current_state);
        stateRow.addView(stateLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stateValue = new TextView(this, null, 0, R.style.GadValue);
        stateRow.addView(stateValue);
        card.addView(stateRow);

        TextView eventLabel = new TextView(this, null, 0, R.style.GadItemDesc);
        eventLabel.setText(R.string.label_last_pause);
        LinearLayout.LayoutParams eventLabelParams = matchWrap();
        eventLabelParams.topMargin = dp(14);
        card.addView(eventLabel, eventLabelParams);

        eventText = new TextView(this, null, 0, R.style.GadStatusMessage);
        LinearLayout.LayoutParams eventParams = matchWrap();
        eventParams.topMargin = dp(2);
        card.addView(eventText, eventParams);

        serviceHint = new TextView(this, null, 0, R.style.GadItemDesc);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(6);
        card.addView(serviceHint, hintParams);

        card.addView(divider());

        LinearLayout actions = row();
        Button start = new Button(this, null, 0, R.style.GadButtonPrimary);
        start.setText(R.string.btn_start);
        start.setOnClickListener(v -> start());
        actions.addView(start, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f));
        Button stop = new Button(this, null, 0, R.style.GadButton);
        stop.setText(R.string.btn_pause);
        stop.setOnClickListener(v -> DictationService.stop("已停止；当前录音由你处理"));
        stopButton = stop;
        startButton = start;
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        stopParams.leftMargin = dp(8);
        actions.addView(stop, stopParams);
        card.addView(actions);

        Button accessibility = new Button(this, null, 0, R.style.GadButtonLink);
        accessibility.setText(R.string.open_accessibility);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams accessParams = matchWrap();
        accessParams.topMargin = dp(8);
        card.addView(accessibility, accessParams);

        // High-speed frame source: optional and separately authorised. MediaProjection
        // needs a per-start consent dialog, so the button exists for the user to opt in;
        // without it the accessibility screenshot path keeps working exactly as before.
        Button projection = new Button(this, null, 0, R.style.GadButton);
        projection.setText("启用高速取帧（需授权录屏）");
        projection.setOnClickListener(v -> requestProjection());
        LinearLayout.LayoutParams projectionParams = matchWrap();
        projectionParams.topMargin = dp(8);
        card.addView(projection, projectionParams);

        projectionStatus = new TextView(this, null, 0, R.style.GadItemDesc);
        projectionStatus.setTextColor(getColor(R.color.gad_text_secondary));
        LinearLayout.LayoutParams projectionStatusParams = matchWrap();
        projectionStatusParams.topMargin = dp(6);
        card.addView(projectionStatus, projectionStatusParams);
        return card;
    }

    /** Ask for the one-shot MediaProjection consent; the service starts from onActivityResult. */
    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            projectionStatus.setText("此设备不支持录屏取帧，继续使用无障碍截图");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    /**
     * Environment learning: what room the assistant thinks it is in, and a manual trigger.
     *
     * Learning happens automatically in the first ten seconds of every run; the button exists
     * for the case where the room changed and the user does not want to wait for the next run.
     */
    private View buildEnvironmentCard() {
        LinearLayout card = card();

        envValue = new TextView(this, null, 0, R.style.GadValue);
        envValue.setText("未识别");
        envValue.setTextColor(getColor(R.color.gad_text_primary));
        card.addView(itemHeader(R.string.label_environment, envValue));

        envRounds = new TextView(this, null, 0, R.style.GadItemDesc);
        envRounds.setTextColor(getColor(R.color.gad_text_secondary));
        LinearLayout.LayoutParams roundsParams = matchWrap();
        roundsParams.topMargin = dp(6);
        card.addView(envRounds, roundsParams);

        Button learn = new Button(this, null, 0, R.style.GadButton);
        learn.setText(R.string.btn_learn_env);
        learn.setOnClickListener(v -> {
            if (DictationService.requestEnvironmentLearning()) {
                eventText.setText(R.string.env_learning_started);
            } else {
                eventText.setText(R.string.env_learning_need_run);
            }
        });
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(10);
        card.addView(learn, buttonParams);

        TextView hint = new TextView(this, null, 0, R.style.GadItemDesc);
        hint.setText(R.string.hint_environment);
        hint.setTextColor(getColor(R.color.gad_text_secondary));
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(8);
        card.addView(hint, hintParams);

        return card;
    }

    private View buildRecordingCard() {
        LinearLayout card = card();
        RecordingSettings current = loadSettingsForUi();

        // --- longest segment -------------------------------------------------
        card.addView(itemHeader(R.string.label_max_seconds,
                secondsValue = valueView()));
        secondsValue.setText(getString(R.string.value_seconds, current.maxDurationSeconds));

        secondsBar = slider(RecordingSettings.MAX_SECONDS - RecordingSettings.MIN_SECONDS,
                current.maxDurationSeconds - RecordingSettings.MIN_SECONDS);
        secondsBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int seconds = RecordingSettings.MIN_SECONDS + progress;
                secondsValue.setText(getString(R.string.value_seconds, seconds));
                saveInt("seconds", seconds);
            }
        });
        LinearLayout.LayoutParams secondsParams = matchWrap();
        secondsParams.topMargin = dp(8);
        card.addView(secondsBar, secondsParams);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] presetValues = {30, 60, 120, 200, 300, 400};
        for (int i = 0; i < presetValues.length; i++) {
            final int preset = presetValues[i];
            TextView chip = new TextView(this, null, 0, R.style.GadPreset);
            chip.setText(preset + " 秒");
            chip.setOnClickListener(v -> {
                secondsBar.setProgress(preset - RecordingSettings.MIN_SECONDS);
                secondsValue.setText(getString(R.string.value_seconds, preset));
                saveInt("seconds", preset);
            });
            LinearLayout.LayoutParams chipParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) chipParams.leftMargin = dp(6);
            presets.addView(chip, chipParams);
        }
        LinearLayout.LayoutParams presetParams = matchWrap();
        presetParams.topMargin = dp(10);
        card.addView(presets, presetParams);

        card.addView(description(R.string.desc_max_seconds));
        card.addView(divider());

        // --- pause bias ------------------------------------------------------
        card.addView(itemHeader(R.string.label_pause_bias,
                pauseValue = valueView()));
        pauseBar = slider(RecordingSettings.MAX_PAUSE_BIAS - RecordingSettings.MIN_PAUSE_BIAS,
                current.pauseBias - RecordingSettings.MIN_PAUSE_BIAS);
        pauseBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int bias = RecordingSettings.MIN_PAUSE_BIAS + progress;
                pauseValue.setText(biasLabel(bias));
                saveInt("pause_bias", bias);
            }
        });
        LinearLayout.LayoutParams pauseParams = matchWrap();
        pauseParams.topMargin = dp(8);
        card.addView(pauseBar, pauseParams);

        LinearLayout pauseEnds = new LinearLayout(this);
        pauseEnds.setOrientation(LinearLayout.HORIZONTAL);
        pauseEnds.addView(endLabel("保守 · 等更久"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView pauseRight = endLabel("灵敏 · 更快发送");
        pauseRight.setGravity(Gravity.END);
        pauseEnds.addView(pauseRight, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(pauseEnds);

        card.addView(description(R.string.desc_pause_bias));
        card.addView(divider());

        // --- noise ignore ----------------------------------------------------
        card.addView(itemHeader(R.string.label_noise_ignore,
                noiseValue = valueView()));
        noiseBar = slider(NoiseIgnorePolicy.MAX - NoiseIgnorePolicy.MIN,
                current.noiseIgnore - NoiseIgnorePolicy.MIN);
        noiseBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int level = NoiseIgnorePolicy.MIN + progress;
                noiseValue.setText(noiseLabel(level));
                saveInt("noise_ignore", level);
            }
        });
        LinearLayout.LayoutParams noiseParams = matchWrap();
        noiseParams.topMargin = dp(8);
        card.addView(noiseBar, noiseParams);

        LinearLayout noiseEnds = new LinearLayout(this);
        noiseEnds.setOrientation(LinearLayout.HORIZONTAL);
        noiseEnds.addView(endLabel("少忽略 · 更灵敏"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView noiseRight = endLabel("多忽略 · 更保守");
        noiseRight.setGravity(Gravity.END);
        noiseEnds.addView(noiseRight, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(noiseEnds);

        card.addView(description(R.string.desc_noise_ignore));

        TextView liveHint = new TextView(this, null, 0, R.style.GadItemDesc);
        liveHint.setText(R.string.hint_settings_live);
        LinearLayout.LayoutParams liveParams = matchWrap();
        liveParams.topMargin = dp(10);
        card.addView(liveHint, liveParams);

        pauseValue.setText(biasLabel(current.pauseBias));
        noiseValue.setText(noiseLabel(current.noiseIgnore));
        refreshSliderTints();
        return card;
    }

    private View buildAdvancedCard() {
        LinearLayout card = card();

        card.addView(itemTitle(R.string.label_mic_probe));
        card.addView(description(R.string.desc_mic_probe));

        Button probe = new Button(this, null, 0, R.style.GadButton);
        probe.setText(R.string.btn_probe);
        probe.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
            } else {
                runProbe();
            }
        });
        LinearLayout.LayoutParams probeParams = matchWrap();
        probeParams.topMargin = dp(10);
        card.addView(probe, probeParams);

        probeResult = new TextView(this, null, 0, R.style.GadItemDesc);
        LinearLayout.LayoutParams resultParams = matchWrap();
        resultParams.topMargin = dp(8);
        card.addView(probeResult, resultParams);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            card.addView(divider());

            CheckBox diagnostic = new CheckBox(this);
            diagnostic.setText(R.string.label_diagnostics);
            diagnostic.setTextSize(15);
            diagnostic.setTextColor(getColor(R.color.gad_text_primary));
            diagnostic.setChecked(DictationService.diagnosticsEnabled());
            diagnostic.setOnCheckedChangeListener((button, checkedValue) ->
                    getSharedPreferences("settings", 0).edit().putBoolean("diagnostics", checkedValue).apply());
            card.addView(diagnostic);

            card.addView(description(R.string.desc_diagnostics));

            LinearLayout diagRow = row();
            LinearLayout.LayoutParams diagParams = matchWrap();
            diagParams.topMargin = dp(8);
            diagRow.setLayoutParams(diagParams);
            TextView diagLabel = new TextView(this, null, 0, R.style.GadItemDesc);
            diagLabel.setText(R.string.label_diag_status);
            diagRow.addView(diagLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            diagnosticValue = new TextView(this, null, 0, R.style.GadItemDesc);
            diagnosticValue.setGravity(Gravity.END);
            diagRow.addView(diagnosticValue);
            card.addView(diagRow);
        }
        return card;
    }

    private View buildAboutCard() {
        LinearLayout card = card();

        LinearLayout versionRow = row();
        TextView versionLabel = new TextView(this, null, 0, R.style.GadItemTitle);
        versionLabel.setText(R.string.label_version);
        versionRow.addView(versionLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView versionValue = new TextView(this, null, 0, R.style.GadValue);
        versionValue.setText(getString(R.string.version_line, versionName, versionCode));
        versionRow.addView(versionValue);
        card.addView(versionRow);

        card.addView(divider());
        card.addView(linkRow(R.string.label_changelog, R.raw.changelog, R.string.dialog_changelog_title));
        card.addView(divider());
        card.addView(linkRow(R.string.label_guide, R.raw.guide, R.string.dialog_guide_title));
        card.addView(divider());

        TextView footer = new TextView(this, null, 0, R.style.GadFooter);
        footer.setText(R.string.about_footer);
        card.addView(footer);
        return card;
    }

    private View linkRow(int titleRes, int rawRes, int dialogTitleRes) {
        LinearLayout row = new LinearLayout(this, null, 0, R.style.GadRowLink);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        TextView title = new TextView(this, null, 0, R.style.GadLinkRow);
        title.setText(titleRes);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(getColor(R.color.gad_text_hint));
        row.addView(chevron);

        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(dialogTitleRes)
                .setMessage(readRaw(rawRes))
                .setPositiveButton(R.string.dialog_close, null)
                .show());
        return row;
    }

    // ------------------------------------------------------------ status text

    private void renderStatus() {
        if (stateValue == null || serviceBadge == null || eventText == null) return;
        String message = DictationService.status();
        boolean connected = !message.contains("服务未连接");
        if (lastConnected == null || lastConnected != connected) {
            lastConnected = connected;
            serviceBadge.setText(connected ? R.string.state_service_on : R.string.state_service_off);
            serviceBadge.setTextColor(connected ? getColor(R.color.gad_success) : getColor(R.color.gad_warning));
            serviceBadge.setBackground(pill(
                    connected ? getColor(R.color.gad_success_soft) : getColor(R.color.gad_warning_soft),
                    connected ? getColor(R.color.gad_success) : getColor(R.color.gad_warning)));
        }
        stateValue.setText(stateLabel(message));
        stateValue.setTextColor(stateColor(message));
        if (stateDot != null) {
            stateDot.setBackgroundTintList(ColorStateList.valueOf(stateColor(message)));
        }
        eventText.setText(message);
        serviceHint.setText(connected ? R.string.service_on_hint : R.string.service_off_hint);
        if (startButton != null) startButton.setEnabled(connected);
        if (stopButton != null) stopButton.setEnabled(connected);
        if (diagnosticValue != null) diagnosticValue.setText(DictationService.diagnosticStatus());
        if (envValue != null) envValue.setText(DictationService.environmentSummary());
        if (envRounds != null) envRounds.setText(DictationService.learnedRounds());
        if (projectionStatus != null) {
            if (ProjectionService.isRunning()) {
                // Live capture diagnostics: how many frames arrived, how stale the newest one
                // is, and the last crop actually copied. Without this, "no wave lines" in the
                // field could only be diagnosed from an adb log after the fact.
                projectionStatus.setText("高速取帧运行中：" + ProjectionCapture.stats());
            } else {
                projectionStatus.setText("高速取帧未开启：当前使用无障碍截图（较慢）");
            }
        }
    }

    private String stateLabel(String message) {
        if (message.contains("服务未连接")) return getString(R.string.state_not_connected);
        if (message.contains("已暂停") || message.contains("已停止")) return getString(R.string.state_paused);
        if (message.contains("录音中")) return getString(R.string.state_running);
        if (message.contains("等待")) return getString(R.string.state_waiting);
        return getString(R.string.state_idle);
    }

    private int stateColor(String message) {
        if (message.contains("服务未连接")) return getColor(R.color.gad_text_hint);
        if (message.contains("已暂停") || message.contains("已停止")) return getColor(R.color.gad_warning);
        if (message.contains("录音中")) return getColor(R.color.gad_success);
        if (message.contains("等待")) return getColor(R.color.gad_accent);
        return getColor(R.color.gad_success);
    }

    // ------------------------------------------------------------- behaviours

    private void start() {
        Integer value = RecordingSettings.parseSeconds(String.valueOf(secondsBar.getProgress() + RecordingSettings.MIN_SECONDS));
        if (value == null) {
            eventText.setText(R.string.hint_max_seconds_range);
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if (launch == null) {
            eventText.setText("未找到 ChatGPT，请先安装并登录");
            return;
        }
        if (!DictationService.start(value, pauseBias())) {
            eventText.setText(DictationService.status());
            return;
        }
        getSharedPreferences("settings", 0).edit().putInt("seconds", value).apply();
        startActivity(launch);
    }

    private int pauseBias() {
        SharedPreferences prefs = getSharedPreferences("settings", 0);
        return Math.max(RecordingSettings.MIN_PAUSE_BIAS,
                Math.min(RecordingSettings.MAX_PAUSE_BIAS, prefs.getInt("pause_bias", RecordingSettings.DEFAULT_PAUSE_BIAS)));
    }

    private void runProbe() {
        probeResult.setText(R.string.probe_running);
        DictationService.probeMicrophone(result -> probeResult.setText(result));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 11 && probeResult != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) runProbe();
            else probeResult.setText(R.string.probe_permission_denied);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            if (projectionStatus != null) {
                projectionStatus.setText("未授权录屏，继续使用无障碍截图（较慢）");
            }
            return;
        }
        Intent service = new Intent(this, ProjectionService.class);
        service.putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ProjectionService.EXTRA_RESULT_DATA, data);
        try {
            startForegroundService(service);
            if (projectionStatus != null) projectionStatus.setText("高速取帧已启动");
        } catch (RuntimeException error) {
            // Some ROMs refuse a mediaProjection foreground service started this way.
            if (projectionStatus != null) projectionStatus.setText("启动失败，继续使用无障碍截图");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        renderStatus();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ------------------------------------------------------------------ values

    private RecordingSettings loadSettingsForUi() {
        SharedPreferences prefs = getSharedPreferences("settings", 0);
        RecordingSettings fallback = DictationService.currentSettings();
        String rawSeconds = String.valueOf(prefs.getInt("seconds", fallback.maxDurationSeconds));
        Integer seconds = RecordingSettings.parseSeconds(rawSeconds);
        return new RecordingSettings(
                seconds == null ? RecordingSettings.DEFAULT_SECONDS : seconds,
                prefs.getInt("pause_bias", fallback.pauseBias),
                prefs.getInt("noise_ignore", fallback.noiseIgnore));
    }

    private String biasLabel(int value) {
        if (value == 0) return getString(R.string.value_bias_standard);
        return value < 0
                ? getString(R.string.value_bias_conservative, value)
                : getString(R.string.value_bias_sensitive, value);
    }

    private String noiseLabel(int value) {
        int level = NoiseIgnorePolicy.clamp(value);
        if (level == 0) return getString(R.string.value_noise_auto);
        return level < 0
                ? getString(R.string.value_noise_less, level)
                : getString(R.string.value_noise_more, level);
    }

    private void saveInt(String key, int value) {
        getSharedPreferences("settings", 0).edit().putInt(key, value).apply();
    }

    private void loadVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the fallback labels; the screen still works.
        }
    }

    private String readRaw(int resId) {
        StringBuilder text = new StringBuilder();
        try (InputStream in = getResources().openRawResource(resId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        } catch (IOException e) {
            return "读取失败";
        }
        return text.toString().trim();
    }

    // ------------------------------------------------------------------ views

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this, null, 0, R.style.GadCard);
        card.setOrientation(LinearLayout.VERTICAL);
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addSection(LinearLayout page, int titleRes, View card) {
        TextView title = new TextView(this, null, 0, R.style.GadSectionTitle);
        title.setText(titleRes);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(22);
        page.addView(title, titleParams);

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(2);
        page.addView(card, cardParams);
    }

    private TextView itemTitle(int textRes) {
        TextView view = new TextView(this, null, 0, R.style.GadItemTitle);
        view.setText(textRes);
        return view;
    }

    private TextView description(int textRes) {
        TextView view = new TextView(this, null, 0, R.style.GadItemDesc);
        view.setText(textRes);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        view.setLayoutParams(params);
        return view;
    }

    private TextView valueView() {
        return new TextView(this, null, 0, R.style.GadValue);
    }

    private View itemHeader(int labelRes, TextView value) {
        LinearLayout header = row();
        TextView label = new TextView(this, null, 0, R.style.GadItemTitle);
        label.setText(labelRes);
        header.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(value);
        return header;
    }

    private TextView endLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(getColor(R.color.gad_text_hint));
        view.setSingleLine(true);
        return view;
    }

    private SeekBar slider(int max, int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.max(1, max));
        bar.setProgress(Math.max(0, Math.min(max, progress)));
        bar.setPadding(0, dp(8), 0, dp(8));
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.gad_accent)));
        bar.setThumbTintList(ColorStateList.valueOf(getColor(R.color.gad_accent)));
        return bar;
    }

    private void refreshSliderTints() {
        // Kept as a single hook so the three sliders stay visually identical.
        if (secondsBar != null) secondsBar.setContentDescription(getString(R.string.content_desc_slider_range,
                RecordingSettings.MIN_SECONDS, RecordingSettings.MAX_SECONDS));
        if (pauseBar != null) pauseBar.setContentDescription(getString(R.string.content_desc_slider_range,
                RecordingSettings.MIN_PAUSE_BIAS, RecordingSettings.MAX_PAUSE_BIAS));
        if (noiseBar != null) noiseBar.setContentDescription(getString(R.string.content_desc_slider_range,
                NoiseIgnorePolicy.MIN, NoiseIgnorePolicy.MAX));
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.gad_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(10);
        params.bottomMargin = dp(10);
        divider.setLayoutParams(params);
        return divider;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(20));
        shape.setColor(fill);
        shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** Avoids implementing all three SeekBar callbacks at every call site. */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) { }
        @Override public void onStopTrackingTouch(SeekBar bar) { }
    }
}
