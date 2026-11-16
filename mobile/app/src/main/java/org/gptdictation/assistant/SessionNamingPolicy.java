package org.gptdictation.assistant;

/**
 * Decides whether the visible chat is a brand-new, still-empty conversation, which is
 * the only moment the archiving name may be sent.
 *
 * The decisive signal is the pair of suggestion rows ChatGPT shows above the composer in
 * an empty chat: "撰写或编辑" and "创建图像或贴纸". Those rows disappear as soon as the
 * conversation has any content, so they distinguish a new chat from an old one without
 * reading a single message.
 *
 * Nothing in this class inspects message text, transcripts or delivery state.
 */
final class SessionNamingPolicy {
    enum ChatState {
        /** Positive empty-chat evidence: naming is required before recording. */
        NEW,
        /** Positive existing-chat or already-recording evidence: continue without naming. */
        EXISTING,
        /** The current accessibility snapshot cannot identify the chat safely. */
        UNKNOWN
    }

    enum Action {
        /** A proven new conversation: invoke its naming message before recording. */
        NAME_NOW,
        /** A proven existing conversation, or a new one already initialized. */
        CONTINUE,
        /** Evidence is incomplete or still rendering: wait for another snapshot. */
        WAIT
    }

    /** Both suggestion rows must be present for a confident "new chat". */
    static final int REQUIRED_SUGGESTION_ROWS = 2;

    private SessionNamingPolicy() { }

    static ChatState classify(int newChatSuggestionRows, boolean newChatPlaceholder,
                              boolean chatPageMarker, boolean existingChatPlaceholder,
                              boolean recordingControlsPresent) {
        // Explicit evidence that the composer belongs to an existing chat takes priority
        // over transient suggestion labels elsewhere in the accessibility tree.
        if (existingChatPlaceholder || recordingControlsPresent) return ChatState.EXISTING;
        if (newChatSuggestionRows >= REQUIRED_SUGGESTION_ROWS
                || (newChatPlaceholder && chatPageMarker)) return ChatState.NEW;
        return ChatState.UNKNOWN;
    }

    static Action decide(ChatState chatState, boolean voiceEntryPresent,
                         boolean recordingControlsPresent, boolean namingInvokedForChat) {
        if (recordingControlsPresent || chatState == ChatState.EXISTING) return Action.CONTINUE;
        if (chatState == ChatState.UNKNOWN || !voiceEntryPresent) return Action.WAIT;
        return namingInvokedForChat ? Action.CONTINUE : Action.NAME_NOW;
    }

    /** Per-run state used by production and host scenarios to share the same gate. */
    static final class Gate {
        private boolean namingInvoked;

        void reset() { namingInvoked = false; }

        boolean namingInvoked() { return namingInvoked; }

        void markNamingInvoked() { namingInvoked = true; }

        Action observe(int newChatSuggestionRows, boolean newChatPlaceholder,
                       boolean chatPageMarker, boolean existingChatPlaceholder,
                       boolean composerEmpty, boolean voiceEntryPresent,
                       boolean recordingControlsPresent) {
            ChatState chatState = classify(newChatSuggestionRows, newChatPlaceholder,
                    chatPageMarker, existingChatPlaceholder, recordingControlsPresent);
            if (chatState == ChatState.EXISTING) namingInvoked = false;
            if (chatState == ChatState.NEW && !composerEmpty) return Action.WAIT;
            return decide(chatState, voiceEntryPresent, recordingControlsPresent, namingInvoked);
        }
    }

    /** A delayed send click is safe only while our exact initializer text remains in composer. */
    static boolean pendingTextStillMatches(String pendingText, String composerText) {
        return pendingText != null && pendingText.equals(composerText);
    }
}
