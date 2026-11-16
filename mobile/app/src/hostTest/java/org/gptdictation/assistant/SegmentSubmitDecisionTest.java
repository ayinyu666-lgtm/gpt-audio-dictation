package org.gptdictation.assistant;

/**
 * Host-only tests of the rule the service actually applies. SegmentSubmitDecision is
 * called directly by DictationService, so these checks cover the production decision.
 *
 * The decision answers exactly one question: is it time to click send? It does not judge
 * transcription success or delivery, and the previous segment being unacknowledged is
 * deliberately not a reason to stop.
 */
public final class SegmentSubmitDecisionTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static SegmentSubmitDecision.Decision decide(boolean deadline, boolean fresh, boolean pause) {
        return new SegmentSubmitDecision(deadline, fresh, pause).decide();
    }
    private static void expect(SegmentSubmitDecision.Decision actual, SegmentSubmitDecision.Decision expected) {
        check(actual == expected, "expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        // Nothing trustworthy yet: keep recording.
        expect(decide(false, false, false), SegmentSubmitDecision.Decision.WAIT);
        expect(decide(false, true, false), SegmentSubmitDecision.Decision.WAIT);
        // A pause detected on a fresh, classified sample triggers a submit.
        expect(decide(false, true, true), SegmentSubmitDecision.Decision.SEND_PAUSE);
        // A stale or unclassified sample cannot submit on its own.
        expect(decide(false, false, true), SegmentSubmitDecision.Decision.WAIT);
        // The maximum duration always triggers, even with no usable signal at all.
        expect(decide(true, false, false), SegmentSubmitDecision.Decision.SEND_TIMED);
        expect(decide(true, true, true), SegmentSubmitDecision.Decision.SEND_TIMED);
        System.out.println("SegmentSubmitDecision: pause route, timed fallback, unreliable-signal guard passed");
    }
}
