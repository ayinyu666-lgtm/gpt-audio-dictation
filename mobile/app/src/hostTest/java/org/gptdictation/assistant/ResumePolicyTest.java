package org.gptdictation.assistant;

/** Host-only tests for the start/resume decision. */
public final class ResumePolicyTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void adoptsARecordingThatIsAlreadyRunning() {
        // ChatGPT is recording (cancel/stop + send present, no 语音输入 entry).
        // The old logic waited for the entry, which only appears after a send, so
        // "continue" did nothing until it timed out. It must adopt instead.
        check(ResumePolicy.decide(false, true, true) == ResumePolicy.Action.ADOPT_RECORDING,
                "an in-progress recording must be adopted");
    }

    private static void startsWhenOnlyTheEntryIsAvailable() {
        check(ResumePolicy.decide(true, false, false) == ResumePolicy.Action.START_RECORDING,
                "a free entry must be clicked");
    }

    private static void waitsWhenNothingIsUsable() {
        check(ResumePolicy.decide(false, false, false) == ResumePolicy.Action.WAIT, "empty screen waits");
        check(ResumePolicy.decide(false, false, true) == ResumePolicy.Action.WAIT,
                "a send control alone is not a recording");
        check(ResumePolicy.decide(true, true, true) == ResumePolicy.Action.WAIT,
                "contradictory evidence must wait, never click");
        check(ResumePolicy.decide(true, true, false) == ResumePolicy.Action.WAIT,
                "entry plus recording controls is contradictory");
    }

    private static void neverActsOnContradictions() {
        // Exhaustive: no combination may produce an action other than the two safe ones,
        // and any contradictory state must be WAIT.
        for (int mask = 0; mask < 8; mask++) {
            boolean voice = (mask & 1) != 0, recording = (mask & 2) != 0, send = (mask & 4) != 0;
            ResumePolicy.Action action = ResumePolicy.decide(voice, recording, send);
            if (voice && recording) check(action == ResumePolicy.Action.WAIT,
                    "voice+recording contradiction must wait: " + mask);
            if (recording && !send && !voice) check(action == ResumePolicy.Action.WAIT,
                    "recording without a send control is not adoptable: " + mask);
        }
    }

    /** The user-visible label never exposes internal stages. */
    private static void onlyTwoLabels() {
        check(OverlayStatus.label(true, false).equals("录音中"), "running label");
        check(OverlayStatus.label(false, false).equals("暂停中"), "paused label");
        check(OverlayStatus.RUNNING.equals(OverlayStatus.label(true, false)), "constant matches");
        check(OverlayStatus.PAUSED.equals(OverlayStatus.label(false, false)), "constant matches paused");
        // A parked run still shows the bubble, and it must read 暂停中.
        check(OverlayStatus.PAUSED.equals(OverlayStatus.label(true, true)), "parked run reads paused");
        // Not running is not rendered at all; the label is only a fallback.
        check(!OverlayStatus.RUNNING.equals(OverlayStatus.label(false, false)), "idle is never 录音中");
    }

    public static void main(String[] args) {
        adoptsARecordingThatIsAlreadyRunning();
        startsWhenOnlyTheEntryIsAvailable();
        waitsWhenNothingIsUsable();
        neverActsOnContradictions();
        onlyTwoLabels();
        System.out.println("ResumePolicy: adopt/start/wait and the two-state label passed");
    }
}
