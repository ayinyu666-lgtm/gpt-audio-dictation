package org.gptdictation.assistant;

/**
 * Pure rules for reading the accessibility tree.
 *
 * These used to live as private methods inside DictationService, which needs Android
 * classes and therefore cannot be exercised by the host test suite. They describe what
 * the scan must recognise - which labels it acts on, which text means "brand-new chat",
 * and whether a node's text even needs to be read - so they are extracted here and
 * covered by host tests against a real device dump.
 *
 * No Android types: callers pass the already-read strings.
 */
final class ScanLabels {
    private ScanLabels() { }

    /**
     * True when this content description is one the scan acts on or counts.
     *
     * Exists so the scan can reject the ~200 unlabelled nodes after a single cheap read
     * instead of calling bounds/visibility/text on each of them.
     */
    static boolean isInterestingLabel(String label) {
        if (label == null || label.isEmpty()) return false;
        switch (label) {
            case "语音输入": case "点击发送录音": case "取消录制": case "停止录制":
            case "聊天": case "工作":
            case "撰写或编辑": case "创建图像或贴纸":
            case "Write or edit": case "Create image or sticker":
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the scan must read this node's text.
     *
     * The labels the scan needs are NOT all content descriptions. On the real device the
     * 聊天 / 工作 tabs and the "撰写或编辑" / "创建图像或贴纸" suggestion rows are plain
     * TextViews with content-desc="" and only a text attribute (verified in the device
     * dump): 4 of 130 nodes carry text, 6 carry a description, none carries both.
     *
     * Reading getText() on every node is one cross-process call per node and partly undoes
     * the cheap-check work, so the read is gated two ways:
     *  - a node with an unrelated non-empty description (菜单 / 附件 / 临时聊天 / 开始语音对话)
     *    is never matched against text, so skip it;
     *  - a node whose class cannot hold text (android.view.View and the layout containers)
     *    cannot be a tab or a suggestion row either. In the device dump 119 of the 124
     *    empty-description nodes are exactly those classes; the only nodes that ever carry
     *    text are TextView (the tabs and rows) and EditText (the composer).
     *
     * The class check is deliberately a *positive list* of text-bearing classes rather than
     * a blocklist of known-empty ones: a future ChatGPT build that draws a label in some
     * other class would then still be read, which fails safe (a few extra reads) instead of
     * silently losing the label again.
     */
    static boolean needsText(String label, String className) {
        if (label != null && !label.isEmpty()) return isInterestingLabel(label);
        return canCarryText(className);
    }

    /** Classes that can expose a label through getText() on this app. */
    private static boolean canCarryText(String className) {
        if (className == null || className.isEmpty()) return true;   // unknown: read it
        return className.endsWith("TextView") || className.endsWith("EditText")
                || className.endsWith("Button") || className.endsWith("CheckBox")
                || className.endsWith("RadioButton") || className.endsWith("Switch");
    }

    /**
     * The two suggestion rows ChatGPT shows above the composer in an empty chat.
     * Real-device dumps expose them as TextView text (contentDescription is empty), so
     * the caller checks both properties.
     */
    static boolean isNewChatRow(String text) {
        if (text == null) return false;
        String t = text.trim();
        return "撰写或编辑".equals(t) || "创建图像或贴纸".equals(t)
                || "Write or edit".equals(t) || "Create image or sticker".equals(t);
    }

    /** Placeholders ChatGPT shows in an untouched composer. */
    static boolean isComposerPlaceholder(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("回复 ChatGPT") || t.startsWith("Message ChatGPT")
                || t.startsWith("向 ChatGPT 提问") || t.startsWith("Ask ChatGPT")
                || t.startsWith("询问 ChatGPT") || t.startsWith("Ask anything");
    }

    /**
     * Placeholder of a chat that has no messages yet. This build says 询问 ChatGPT here
     * and 回复 ChatGPT once the conversation has content, so the wording separates a
     * brand-new session from a continued one without reading any message text.
     */
    static boolean isBrandNewChatPlaceholder(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("询问 ChatGPT") || t.startsWith("Ask anything");
    }

    /** Composer wording that positively identifies a conversation with prior content. */
    static boolean isExistingChatPlaceholder(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("回复 ChatGPT") || t.startsWith("Message ChatGPT");
    }

    /** One of the two recording anchors. These exist exactly while ChatGPT is recording. */
    static boolean isRecordingAnchorLabel(String label) {
        return "取消录制".equals(label) || "停止录制".equals(label);
    }

    /**
     * Whether a candidate recording-bar subtree is safe to scan instead of the whole screen.
     *
     * The localized scan must never lose a control or mistake the composer's absence for an
     * empty draft, so every one of these must hold:
     *  - exactly one 取消录制 and one 停止录制 were found outside the subtree walk;
     *  - the subtree contains 点击发送录音 (it may sit in a sibling container, which is why
     *    the subtree is verified rather than assumed);
     *  - the subtree contains exactly one of each recording control again (no duplicate);
     *  - the subtree contains no editable node: the composer lives outside the bar, and its
     *    absence inside must not be read as "no draft".
     * Any doubt returns false and the caller falls back to the full scan.
     */
    static boolean recordingSubtreeCoversAll(boolean exactlyOneCancelAndStop,
                                             int cancelInside, int stopInside,
                                             boolean sendInside, boolean editableInside) {
        return exactlyOneCancelAndStop && cancelInside == 1 && stopInside == 1
                && sendInside && !editableInside;
    }

    /**
     * Whether a parent chain can be the localized container. A container equal to the whole
     * screen saves nothing and means the two anchors are in different windows; treat it as
     * "not found" so the caller uses the full scan.
     */
    static boolean isUsableRecordingContainer(boolean found, boolean isWholeScreen) {
        return found && !isWholeScreen;
    }

    /**
     * Whether a cached child-index path can be used at all. A path that is empty or too deep
     * is never a valid recording-bar container, and must fall back to a fresh anchored
     * search rather than navigating blindly.
     */
    static boolean isUsableCachedPath(int[] path) {
        return path != null && path.length > 0 && path.length <= 128;
    }

    /**
     * Whether a child index is inside a parent's current child list. Kept separate from the
     * navigation loop so the bounds rule is testable without Android nodes.
     */
    static boolean childIndexInRange(int index, int childCount) {
        return index >= 0 && index < childCount;
    }
}
