package org.gptdictation.assistant;

/**
 * Decides what to do when ChatGPT is not in the foreground.
 *
 * Key fact: ChatGPT keeps recording in the background. Leaving ChatGPT does not end
 * the segment, so the assistant must NOT reset its timers or declare the run over.
 * The user's own analogy: stepping out of the office for a moment and coming back -
 * the work continues, nothing was lost.
 *
 * So "away" is a third, non-destructive condition:
 *
 * - While away, the assistant performs no actions and takes no screenshots.
 * - Timers keep running, because the recording is still running.
 * - On return, it re-synchronises with whatever the UI shows and continues normally.
 * - Only a screen lock / screen off stops the run.
 *
 * Pure logic so the decision is testable without Android.
 */
final class AwayPolicy {
    enum Action {
        /** ChatGPT is visible and was never away: carry on with the normal work. */
        CONTINUE,
        /** Keep the run and keep timers; do nothing until ChatGPT is visible again. */
        SUSPEND_ACTIONS,
        /** ChatGPT is visible again: rebuild from the live UI and continue. */
        RESUME,
        /** Screen went off/locked: recording cannot be trusted, stop the run. */
        STOP
    }

    private AwayPolicy() { }

    /**
     * @param chatGptVisible current foreground belongs to ChatGPT
     * @param screenUsable   interactive and not locked
     * @param wasAway        the assistant already entered the suspended state
     */
    static Action decide(boolean chatGptVisible, boolean screenUsable, boolean wasAway) {
        if (!screenUsable) return Action.STOP;
        if (!chatGptVisible) return Action.SUSPEND_ACTIONS;
        // Visible. This is the normal case and must not suspend anything: the earlier
        // version returned SUSPEND_ACTIONS here, which froze the whole loop whenever
        // ChatGPT was on screen.
        return wasAway ? Action.RESUME : Action.CONTINUE;
    }

    /**
     * Whether a segment should keep its original timing across an away period.
     * Always true while the run is alive: the recording kept going in the background.
     */
    static boolean keepsSegmentTimer() {
        return true;
    }
}
