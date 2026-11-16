package org.gptdictation.assistant;

/** Host-only tests for frozen-process detection and the resulting rebuild decision. */
public final class FreezeDetectorTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void firstTickIsFresh() {
        FreezeDetector d = new FreezeDetector();
        check(d.observe(1000), "the first tick has no history, so it must start clean");
        check(!d.observe(1250), "a normal 250 ms tick is not a freeze");
        check(!d.observe(1500), "steady ticks stay normal");
    }

    private static void normalJitterIsNotAFreeze() {
        FreezeDetector d = new FreezeDetector();
        d.observe(0);
        check(!d.observe(250), "250 ms");
        check(!d.observe(700), "450 ms scheduler jitter is tolerated");
        check(!d.observe(1400), "700 ms is still under the limit");
        check(!d.observe(3800), "2400 ms is just under the 2500 ms floor");
        check(d.observe(6301), "a 2501 ms gap crosses the floor and is a freeze");
    }

    private static void longGapsAreFreezes() {
        FreezeDetector d = new FreezeDetector();
        d.observe(0);
        check(d.observe(5000), "5 s stall must be treated as frozen");
        FreezeDetector e = new FreezeDetector();
        e.observe(0);
        check(e.observe(60000), "a minute asleep must be treated as frozen");
    }

    private static void clockGoingBackwardsIsFresh() {
        FreezeDetector d = new FreezeDetector();
        d.observe(10000);
        check(d.observe(5000), "a backwards clock must force a rebuild, never a negative gap");
    }

    private static void resetForgetsHistory() {
        FreezeDetector d = new FreezeDetector();
        d.observe(0);
        d.reset();
        check(d.observe(100000), "after reset the next tick is a fresh start");
    }

    /**
     * The consequence that matters: after a freeze the assistant must not act on
     * pre-freeze waveform evidence. A stale sample must be discarded, and a pause
     * submit must not be derived from it.
     */
    private static void staleEvidenceCannotCauseASend() {
        long captureTime = 1000;
        long afterFreeze = 90000;
        check(!WaveformSamplingPolicy.fresh(afterFreeze, captureTime),
                "a pre-freeze screenshot can never count as fresh");
        check(!WaveformSamplingPolicy.fresh(afterFreeze, afterFreeze - 2000),
                "a sample older than the freshness window is stale");
        SegmentSubmitDecision.Decision decision = new SegmentSubmitDecision(false, false, true).decide();
        check(decision == SegmentSubmitDecision.Decision.WAIT,
                "no fresh sample means no pause send, whatever the stale decision said");
        System.out.println("FreezeDetector: stale pre-freeze evidence cannot trigger a send");
    }

    public static void main(String[] args) {
        firstTickIsFresh();
        normalJitterIsNotAFreeze();
        longGapsAreFreezes();
        clockGoingBackwardsIsFresh();
        resetForgetsHistory();
        staleEvidenceCannotCauseASend();
        System.out.println("FreezeDetector: gap detection and stale-evidence guard passed");
    }
}
