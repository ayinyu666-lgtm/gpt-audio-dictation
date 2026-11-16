package org.gptdictation.assistant;

/** Host-only checks for conservative new/existing/unknown chat classification. */
public final class SessionNamingPolicyTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void newChatRequiresPositiveEvidence() {
        check(SessionNamingPolicy.classify(2, false, true, false, false)
                        == SessionNamingPolicy.ChatState.NEW,
                "both suggestion rows identify a new chat");
        check(SessionNamingPolicy.classify(0, true, true, false, false)
                        == SessionNamingPolicy.ChatState.NEW,
                "the new-chat placeholder plus chat-page marker identifies a new chat");
        check(SessionNamingPolicy.classify(1, false, true, false, false)
                        == SessionNamingPolicy.ChatState.UNKNOWN,
                "one transient suggestion row is not enough");
        check(SessionNamingPolicy.classify(0, true, false, false, false)
                        == SessionNamingPolicy.ChatState.UNKNOWN,
                "placeholder without page context is not enough");
    }

    private static void existingEvidenceWinsAndResumes() {
        SessionNamingPolicy.Gate gate = new SessionNamingPolicy.Gate();
        check(gate.observe(2, true, true, true, true, true, false)
                        == SessionNamingPolicy.Action.CONTINUE,
                "existing composer wins over stale new-chat labels");
        check(gate.observe(0, false, false, false, true, false, true)
                        == SessionNamingPolicy.Action.CONTINUE,
                "already-recording controls identify a resume");
    }

    private static void unknownNeverFallsThroughToRecording() {
        SessionNamingPolicy.Gate gate = new SessionNamingPolicy.Gate();
        check(gate.observe(0, false, false, false, true, true, false) == SessionNamingPolicy.Action.WAIT,
                "empty or delayed tree with a visible entry still waits");
        gate.markNamingInvoked();
        check(gate.observe(0, false, false, false, true, true, false) == SessionNamingPolicy.Action.WAIT,
                "a prior chat's naming latch cannot resolve an unknown screen");
        check(gate.observe(0, false, false, false, true, false, false) == SessionNamingPolicy.Action.WAIT,
                "missing recording entry remains unresolved");
    }

    private static void newChatNamesBeforeRecordingAndOnlyOnce() {
        SessionNamingPolicy.Gate gate = new SessionNamingPolicy.Gate();
        check(gate.observe(2, false, true, false, true, true, false) == SessionNamingPolicy.Action.NAME_NOW,
                "new chat invokes naming before recording");
        check(gate.observe(2, false, true, false, true, false, false) == SessionNamingPolicy.Action.WAIT,
                "new chat waits if the composer/recording entry is unavailable");
        gate.markNamingInvoked();
        check(gate.observe(2, false, true, false, true, true, false) == SessionNamingPolicy.Action.CONTINUE,
                "after the naming action, recording flow can continue without a reply check");
        check(gate.namingInvoked(), "the latch records initializer action, not delivery status");
        SessionNamingPolicy.Gate withDraft = new SessionNamingPolicy.Gate();
        check(withDraft.observe(2, false, true, false, false, true, false)
                        == SessionNamingPolicy.Action.WAIT,
                "a nonempty user draft is never overwritten by the naming initializer");
    }

    private static void delayedClickIsBoundToCurrentDraft() {
        check(SessionNamingPolicy.pendingTextStillMatches("name", "name"),
                "click is allowed while the initializer remains in its composer");
        check(!SessionNamingPolicy.pendingTextStillMatches("name", "other"),
                "navigation to a different draft cancels the old click");
        check(!SessionNamingPolicy.pendingTextStillMatches("name", null),
                "an empty or unavailable composer cancels the old click");
    }

    public static void main(String[] args) {
        newChatRequiresPositiveEvidence();
        existingEvidenceWinsAndResumes();
        unknownNeverFallsThroughToRecording();
        newChatNamesBeforeRecordingAndOnlyOnce();
        delayedClickIsBoundToCurrentDraft();
        System.out.println("SessionNamingPolicy: tri-state naming gate passed");
    }
}
