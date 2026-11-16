package org.gptdictation.assistant;

/**
 * Decides what a user touch inside ChatGPT means.
 *
 * The user is allowed to operate ChatGPT by hand at any time. The most common case is
 * "this sentence needs translating right now": they tap send themselves and expect the
 * assistant to notice the segment ended and prepare to keep recording. Older code
 * treated every touch as a reason to stop, so a manual send left the assistant stuck in
 * "暂停中" — the exact opposite of what was wanted.
 *
 * A single touch is therefore not evidence of anything yet. Only the next observations
 * decide: if the recording UI is gone (the user submitted or cancelled), the segment is
 * over and the assistant should wait for the next entry. If the recording is still
 * running, the touch was incidental and must not silently kill the run.
 */
final class ManualTouchPolicy {
    enum Action {
        /** Not enough information yet: keep the segment and re-check next tick. */
        RECHECK,
        /** The recording controls disappeared after a touch: the user submitted. */
        TREAT_AS_SUBMIT,
        /** The user touched and the recording is still live: pause and let them handle it. */
        PAUSE,
        /** Touch happened while nothing was being recorded: ignore it. */
        IGNORE
    }

    /** How long after a touch we keep re-checking before deciding. */
    static final long GRACE_MS = 1200;

    private ManualTouchPolicy() { }

    /**
     * @param touchPending     a user touch was seen and has not been resolved yet
     * @param msSinceTouch     milliseconds since that touch
     * @param recordingVisible the recording controls are currently present
     * @param voiceVisible     the 语音输入 entry is currently present
     */
    static Action decide(boolean touchPending, long msSinceTouch, boolean recordingVisible, boolean voiceVisible) {
        if (!touchPending) return Action.IGNORE;
        // The user submitted (or cancelled): recording controls gone, entry back or not.
        if (!recordingVisible) return Action.TREAT_AS_SUBMIT;
        // Still recording right after the touch — give it the grace period before acting,
        // because a send causes a brief re-render where controls can look present.
        if (msSinceTouch < GRACE_MS) return Action.RECHECK;
        if (voiceVisible) return Action.TREAT_AS_SUBMIT;
        return Action.PAUSE;
    }
}
