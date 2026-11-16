package org.gptdictation.assistant;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only, private, bounded snapshots. No raw UI text or event payload is written. */
final class Diagnostics {
    private static final int MAX_NODES = 1200;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long INTERVAL = 1000;
    private static final String GPT = "com.openai.chatgpt";
    private FileOutputStream out;
    private DiagnosticHasher hasher;
    private long started, lastSample = -INTERVAL;
    private int bytesWritten, events, eventCount, droppedEvents;
    private long eventWindow;
    private String status = "未开始";
    private String filename;

    String status() { return status; }
    void start(Context context) {
        close();
        try {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key); hasher = new DiagnosticHasher(key);
            byte[] id = new byte[12]; new SecureRandom().nextBytes(id);
            StringBuilder name = new StringBuilder("diag-");
            for (byte b : id) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            name.append(".jsonl");
            File dir = new File(context.getFilesDir(), "diagnostics");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("directory");
            File file = new File(dir, name.toString());
            if (!file.createNewFile()) throw new java.io.IOException("collision");
            out = new FileOutputStream(file, false); started = SystemClock.elapsedRealtime(); lastSample = -INTERVAL; bytesWritten = 0; events = 0; eventWindow = -1; eventCount = 0; droppedEvents = 0;
            filename = name.toString(); status = "记录中：" + filename;
            context.getSharedPreferences("diagnostics", 0).edit().putString("last_file", name.toString()).apply();
            JSONObject header = new JSONObject(); header.put("type", "header"); header.put("schema", 1); header.put("scope", "active_chatgpt_window"); header.put("hash", "HMAC-SHA256-ephemeral-per-run"); header.put("max_nodes", MAX_NODES); header.put("max_bytes", MAX_BYTES);
            write(header);
        } catch (Exception e) { fail(); }
    }
    void event(AccessibilityEvent e, String state, int segment) {
        if (out == null || e.getPackageName() == null || !GPT.contentEquals(e.getPackageName())) return;
        events |= e.getEventType();
        long elapsed = SystemClock.elapsedRealtime() - started, window = elapsed / 1000;
        if (window != eventWindow) { eventWindow = window; eventCount = 0; }
        if (++eventCount > 10) { droppedEvents++; return; }
        try {
            JSONObject item = new JSONObject(); item.put("type", "event"); item.put("ms", elapsed); item.put("state", state); item.put("segment", segment);
            item.put("event_type", e.getEventType()); item.put("content_change_types", e.getContentChangeTypes()); item.put("window_id", e.getWindowId()); write(item);
        } catch (Exception ex) { fail(); }
    }
    void snapshot(AccessibilityNodeInfo root, String state, int segment, boolean force, String phase) {
        if (out == null) return;
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (!force && elapsed - lastSample < INTERVAL) return;
        try {
            JSONObject sample = new JSONObject();
            sample.put("type", "snapshot"); sample.put("ms", elapsed); sample.put("state", state); sample.put("segment", segment); sample.put("phase", phase); sample.put("event_mask", events); sample.put("events_dropped", droppedEvents);
            events = 0; droppedEvents = 0;
            JSONArray nodes = new JSONArray(); ArrayDeque<Entry> queue = new ArrayDeque<>(); queue.add(new Entry(root, -1, 0));
            while (!queue.isEmpty() && nodes.length() < MAX_NODES) {
                Entry entry = queue.removeFirst(); AccessibilityNodeInfo node = entry.node;
                if (node.getPackageName() == null || !GPT.contentEquals(node.getPackageName())) continue;
                int id = nodes.length(); JSONObject o = new JSONObject(); Rect rect = new Rect(); node.getBoundsInScreen(rect);
                o.put("id", id); o.put("parent", entry.parent); o.put("index", entry.index);
                o.put("class", safeClass(node.getClassName())); o.put("bounds", new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
                o.put("visible", node.isVisibleToUser()); o.put("clickable", node.isClickable()); o.put("enabled", node.isEnabled()); o.put("editable", node.isEditable()); o.put("scrollable", node.isScrollable()); o.put("selected", node.isSelected());
                o.put("child_count", node.getChildCount());
                o.put("text_hash", hasher.hash(node.getText())); o.put("desc_hash", hasher.hash(node.getContentDescription())); o.put("resource_hash", hasher.hash(node.getViewIdResourceName()));
                o.put("role_hint", roleHint(node.getContentDescription()));
                nodes.put(o);
                for (int i = 0; i < node.getChildCount() && queue.size() + nodes.length() < MAX_NODES; i++) {
                    AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(new Entry(child, id, i));
                }
            }
            sample.put("nodes", nodes); sample.put("truncated", !queue.isEmpty() || nodes.length() >= MAX_NODES); write(sample); lastSample = elapsed;
        } catch (Exception e) { fail(); }
    }
    void marker(String type, boolean outcome) {
        if (out == null) return;
        try { JSONObject item = new JSONObject(); item.put("type", type); item.put("ms", SystemClock.elapsedRealtime() - started); item.put("outcome", outcome); write(item); }
        catch (Exception e) { fail(); }
    }

    /** Records one waveform verdict. Numbers only; no pixels or text are stored. */
    void waveform(WaveformDetector.Result r, int segment, long pauseRequiredMs) {
        if (out == null || r == null) return;
        try {
            JSONObject item = new JSONObject();
            item.put("type", "waveform");
            item.put("ms", SystemClock.elapsedRealtime() - started);
            item.put("segment", segment);
            item.put("signal", r.signal.name());
            item.put("decision", r.decision.name());
            item.put("reason", r.reason);
            item.put("activity", round(r.activity));
            item.put("floor", round(r.noiseFloor));
            item.put("threshold", round(r.threshold));
            item.put("pauseMs", r.pauseMs);
            item.put("requiredMs", pauseRequiredMs);
            item.put("activeMs", r.visualActiveMs);
            // speechShare is the one signal that tracks loudness on this device even when
            // the p75 activity cannot separate speech from background. It was missing from
            // the log, so the 18:51 degenerate run could not be diagnosed from the device
            // alone. Numbers only, like every other field here.
            item.put("share", round(r.speechShare));
            write(item);
        } catch (Exception e) { fail(); }
    }

    private static double round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? -1.0 : Math.round(v * 1000.0) / 1000.0;
    }
    private void write(JSONObject value) throws java.io.IOException {
        byte[] line = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytesWritten + line.length > MAX_BYTES) { status = "日志达到 8 MiB 上限，已停止记录"; close(); return; }
        out.write(line); out.flush(); bytesWritten += line.length;
    }
    void close() {
        if (out != null) { try { out.close(); } catch (java.io.IOException ignored) {} out = null; if (status.startsWith("记录中")) status = "已保存：" + filename; }
        hasher = null;
    }
    private void fail() { close(); status = "诊断写入失败或不可用；自动录音规则不变"; }
    private static String safeClass(CharSequence value) {
        if (value == null) return "unknown"; String c = value.toString();
        if (c.equals("android.view.View") || c.equals("android.widget.TextView") || c.equals("android.widget.EditText") || c.equals("android.widget.Button") || c.equals("android.widget.ImageView") || c.equals("android.widget.ScrollView") || c.equals("android.widget.FrameLayout") || c.equals("android.widget.LinearLayout") || c.equals("android.widget.ListView") || c.equals("android.widget.ImageButton")) return c;
        return "other";
    }
    private static String roleHint(CharSequence desc) {
        if (desc == null) return "";
        switch (desc.toString()) {
            case "语音输入": return "voice_input";
            case "点击发送录音": return "send_recording";
            case "取消录制": return "cancel_recording";
            case "停止录制": return "stop_recording";
            case "复制": return "copy";
            case "赞": return "positive_feedback";
            case "踩": return "negative_feedback";
            case "朗读": return "read_aloud";
            default: return "";
        }
    }
    private static final class Entry {
        final AccessibilityNodeInfo node; final int parent, index;
        Entry(AccessibilityNodeInfo n, int p, int i) { node = n; parent = p; index = i; }
    }
}
