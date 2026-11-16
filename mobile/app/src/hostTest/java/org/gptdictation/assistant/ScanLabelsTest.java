package org.gptdictation.assistant;

/**
 * Host test for the accessibility-tree label rules that live in {@link ScanLabels}.
 *
 * These rules decide whether the scan can see the 聊天 / 工作 tabs and the
 * "撰写或编辑" / "创建图像或贴纸" suggestion rows at all. They are plain TextViews on the
 * real device: contentDescription is empty and only the text attribute carries the label
 * (verified in the device dump under _tmp/phone/nc.xml: 4 nodes with text, 6 with a
 * description, 0 with both). A regression here silently disables session naming, so it
 * gets its own test rather than relying on a device run.
 */
public final class ScanLabelsTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** The tab and suggestion-row labels must be recognised as interesting. */
    private static void tabsAndSuggestionRowsAreInteresting() {
        String[] labels = {"聊天", "工作", "撰写或编辑", "创建图像或贴纸",
                "Write or edit", "Create image or sticker"};
        for (String label : labels) {
            check(ScanLabels.isInterestingLabel(label), "label must be interesting: " + label);
        }
    }

    /** The controls the scan acts on must be recognised as interesting. */
    private static void actionControlsAreInteresting() {
        String[] labels = {"语音输入", "点击发送录音", "取消录制", "停止录制"};
        for (String label : labels) {
            check(ScanLabels.isInterestingLabel(label), "action label must be interesting: " + label);
        }
    }

    /** Ordinary UI descriptions must not be treated as interesting. */
    private static void ordinaryDescriptionsAreNotInteresting() {
        String[] labels = {"菜单", "附件", "临时聊天", "开始语音对话", "分享", ""};
        for (String label : labels) {
            check(!ScanLabels.isInterestingLabel(label), "label must NOT be interesting: " + label);
        }
        check(!ScanLabels.isInterestingLabel(null), "null must not be interesting");
    }

    /**
     * The exact regression from the plan: a tab or suggestion row has an empty description
     * and only text, so the scan must read its text. On the device those are TextViews.
     */
    private static void textOnlyNodesRequireReadingText() {
        String[] textOnlyLabels = {"聊天", "工作", "撰写或编辑", "创建图像或贴纸"};
        for (String label : textOnlyLabels) {
            check(ScanLabels.needsText("", "android.widget.TextView"),
                    "a text-only TextView (label=" + label + ") must have its text read");
            check(ScanLabels.needsText(null, "android.widget.TextView"),
                    "a null description must have its text read (label=" + label + ")");
        }
        // And the text itself must be recognised by the row rule.
        for (String label : textOnlyLabels) {
            check(ScanLabels.isNewChatRow(label) || "聊天".equals(label) || "工作".equals(label),
                    "text must be recognised as a new-chat row: " + label);
        }
    }

    /** Known action labels are read as well, so their descendants' text stays consistent. */
    private static void interestingLabelsStillReadText() {
        check(ScanLabels.needsText("语音输入", "android.view.View"), "a known action label must still read text");
        check(ScanLabels.needsText("取消录制", "android.view.View"), "a known action label must still read text");
    }

    /**
     * The two accepted skips: an unrelated non-empty description, and a class that cannot
     * carry text. Those nodes are buttons (菜单 / 附件 / 临时聊天 / 开始语音对话) and plain
     * layout Views; the scan never matches them against text.
     */
    private static void unrelatedDescriptionsSkipText() {
        String[] labels = {"菜单", "附件", "临时聊天", "开始语音对话"};
        for (String label : labels) {
            check(!ScanLabels.needsText(label, "android.view.View"),
                    "an unrelated description must skip getText(): " + label);
        }
    }

    /**
     * A node with an empty description whose class cannot hold text must be skipped: in the
     * real dump those are 104 android.view.View plus layout containers, and reading text on
     * every one of them is what costs an IPC per node.
     */
    private static void nonTextClassesSkipReading() {
        String[] classes = {"android.view.View", "android.widget.FrameLayout",
                "android.widget.LinearLayout", "android.widget.ScrollView",
                "androidx.compose.ui.platform.ComposeView"};
        for (String cls : classes) {
            check(!ScanLabels.needsText("", cls), "class cannot carry text, must skip getText(): " + cls);
        }
        // Positive list must still read the classes that can carry a label.
        String[] textClasses = {"android.widget.TextView", "android.widget.EditText",
                "android.widget.Button", "android.widget.CheckBox",
                "android.widget.RadioButton", "android.widget.Switch"};
        for (String cls : textClasses) {
            check(ScanLabels.needsText("", cls), "class can carry text, must read getText(): " + cls);
        }
        // Unknown class: fail safe, read it.
        check(ScanLabels.needsText("", ""), "an unknown class must be read (fail safe)");
        check(ScanLabels.needsText("", null), "a null class must be read (fail safe)");
    }

    /** Composer placeholders keep the session-naming logic working. */
    private static void placeholdersAreRecognised() {
        check(ScanLabels.isBrandNewChatPlaceholder("询问 ChatGPT"), "brand-new placeholder must be recognised");
        check(ScanLabels.isBrandNewChatPlaceholder("Ask anything"), "brand-new placeholder must be recognised");
        check(!ScanLabels.isBrandNewChatPlaceholder("回复 ChatGPT"), "ongoing placeholder is not brand-new");
        check(ScanLabels.isExistingChatPlaceholder("回复 ChatGPT"), "Chinese existing-chat placeholder must be recognised");
        check(ScanLabels.isExistingChatPlaceholder("Message ChatGPT"), "English existing-chat placeholder must be recognised");
        check(!ScanLabels.isExistingChatPlaceholder("询问 ChatGPT"), "new-chat placeholder is not existing-chat evidence");
        check(ScanLabels.isComposerPlaceholder("回复 ChatGPT"), "ongoing placeholder must be recognised");
        check(ScanLabels.isComposerPlaceholder("Ask anything"), "composer placeholder must be recognised");
        check(!ScanLabels.isComposerPlaceholder("你好"), "real text is not a placeholder");
    }

    /** The recording anchors the localized scan will use must be exact. */
    private static void recordingAnchorsAreExact() {
        check(ScanLabels.isRecordingAnchorLabel("取消录制"), "取消录制 must be a recording anchor");
        check(ScanLabels.isRecordingAnchorLabel("停止录制"), "停止录制 must be a recording anchor");
        check(!ScanLabels.isRecordingAnchorLabel("点击发送录音"), "点击发送录音 is not a stop/cancel anchor");
        check(!ScanLabels.isRecordingAnchorLabel(null), "null is not an anchor");
    }

    /**
     * A localized recording-bar scan is only allowed when its subtree provably covers the
     * recording UI. Each missing piece must fall back to the full scan.
     */
    private static void recordingSubtreeRules() {
        // Happy path: one cancel, one stop, send inside, no composer inside.
        check(ScanLabels.recordingSubtreeCoversAll(true, 1, 1, true, false),
                "a complete recording bar may be scanned locally");
        // 点击发送录音 in a different container: must fall back.
        check(!ScanLabels.recordingSubtreeCoversAll(true, 1, 1, false, false),
                "a bar without the send control must fall back to the full scan");
        // The composer inside the subtree: the draft bookkeeping would break, fall back.
        check(!ScanLabels.recordingSubtreeCoversAll(true, 1, 1, true, true),
                "a subtree containing the composer must fall back to the full scan");
        // Duplicate controls: ambiguous, fall back.
        check(!ScanLabels.recordingSubtreeCoversAll(true, 2, 1, true, false), "duplicate cancel falls back");
        check(!ScanLabels.recordingSubtreeCoversAll(true, 1, 2, true, false), "duplicate stop falls back");
        check(!ScanLabels.recordingSubtreeCoversAll(false, 1, 1, true, false),
                "more than one anchor outside the subtree falls back");
        // Container selection.
        check(!ScanLabels.isUsableRecordingContainer(false, false), "missing container falls back");
        check(!ScanLabels.isUsableRecordingContainer(true, true),
                "a container equal to the whole screen saves nothing and must not be used");
        check(ScanLabels.isUsableRecordingContainer(true, false), "a real sub-container is usable");
        // Cached child-index path rules: anything malformed must fall back, never navigate.
        check(!ScanLabels.isUsableCachedPath(null), "a null path must fall back");
        check(!ScanLabels.isUsableCachedPath(new int[0]), "an empty path must fall back");
        check(ScanLabels.isUsableCachedPath(new int[]{0, 0, 0}), "a real path is usable");
        check(!ScanLabels.isUsableCachedPath(new int[129]), "an absurdly deep path must fall back");
        check(ScanLabels.childIndexInRange(0, 3), "index 0 of 3 is valid");
        check(ScanLabels.childIndexInRange(2, 3), "index 2 of 3 is valid");
        check(!ScanLabels.childIndexInRange(3, 3), "index 3 of 3 is out of range");
        check(!ScanLabels.childIndexInRange(-1, 3), "a negative index is out of range");
    }

    public static void main(String[] args) {
        tabsAndSuggestionRowsAreInteresting();
        actionControlsAreInteresting();
        ordinaryDescriptionsAreNotInteresting();
        textOnlyNodesRequireReadingText();
        interestingLabelsStillReadText();
        unrelatedDescriptionsSkipText();
        nonTextClassesSkipReading();
        placeholdersAreRecognised();
        recordingAnchorsAreExact();
        recordingSubtreeRules();
        System.out.println("ScanLabels: tabs and suggestion rows recognised from text-only nodes; localized-scan safety rules enforced");
    }
}
