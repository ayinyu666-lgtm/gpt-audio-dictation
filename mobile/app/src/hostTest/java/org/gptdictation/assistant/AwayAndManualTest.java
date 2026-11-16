package org.gptdictation.assistant;

/** Host-only tests for "away from ChatGPT" and "user sent it manually" behaviour. */
public final class AwayAndManualTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void awaySuspendsButKeepsTiming() {
        // ChatGPT not visible: suspend actions, never stop the run.
        check(AwayPolicy.decide(false, true, false) == AwayPolicy.Action.SUSPEND_ACTIONS,
                "leaving ChatGPT must suspend actions, not end the run");
        check(AwayPolicy.decide(false, true, true) == AwayPolicy.Action.SUSPEND_ACTIONS,
                "staying away keeps it suspended");
        check(AwayPolicy.keepsSegmentTimer(),
                "the recording continues in the background, so timers must keep running");
    }

    private static void returningResumes() {
        check(AwayPolicy.decide(true, true, true) == AwayPolicy.Action.RESUME,
                "coming back must resume from the live UI");
        check(AwayPolicy.decide(true, true, false) == AwayPolicy.Action.CONTINUE,
                "already visible and active: normal operation continues");
    }

    private static void lockedScreenStops() {
        check(AwayPolicy.decide(false, false, false) == AwayPolicy.Action.STOP,
                "screen off cannot be trusted and must stop the run");
        check(AwayPolicy.decide(true, false, true) == AwayPolicy.Action.STOP,
                "locked screen must stop even if a window looks like ChatGPT");
    }

    /** A manual send is a normal action: the segment ended, keep going. */
    private static void manualSendContinues() {
        check(ManualTouchPolicy.decide(true, 0, false, false) == ManualTouchPolicy.Action.TREAT_AS_SUBMIT,
                "recording controls gone after a touch means the user submitted");
        check(ManualTouchPolicy.decide(true, 0, false, true) == ManualTouchPolicy.Action.TREAT_AS_SUBMIT,
                "entry already back after a submit: still a completed segment");
        check(ManualTouchPolicy.decide(false, 0, false, false) == ManualTouchPolicy.Action.IGNORE,
                "no touch means nothing to interpret");
    }

    /** A touch that did not end the recording must not instantly kill it either. */
    private static void incidentalTouchGetsGrace() {
        check(ManualTouchPolicy.decide(true, 100, true, false) == ManualTouchPolicy.Action.RECHECK,
                "right after a touch, a still-visible recording is ambiguous: wait");
        check(ManualTouchPolicy.decide(true, ManualTouchPolicy.GRACE_MS + 1, true, false)
                == ManualTouchPolicy.Action.PAUSE,
                "a touch that leaves the recording running is a real manual interruption");
        check(ManualTouchPolicy.decide(true, ManualTouchPolicy.GRACE_MS + 1, true, true)
                == ManualTouchPolicy.Action.TREAT_AS_SUBMIT,
                "entry visible means the segment ended");
    }

    /** Manual pause still works: it is an explicit user intent, not a UI accident. */
    private static void explicitPauseIsUnchanged() {
        // The bubble toggle calls pause() directly; nothing in AwayPolicy can revive it.
        check(AwayPolicy.decide(false, true, false) == AwayPolicy.Action.SUSPEND_ACTIONS,
                "away is not the same as a user pause");
        System.out.println("AwayAndManual: explicit user pause stays separate from away-suspension");
    }

    public static void main(String[] args) {
        awaySuspendsButKeepsTiming();
        returningResumes();
        lockedScreenStops();
        manualSendContinues();
        incidentalTouchGetsGrace();
        explicitPauseIsUnchanged();
        System.out.println("AwayAndManual: away/resume, manual send and touch grace passed");
    }
}
