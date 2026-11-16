package org.gptdictation.assistant;

/**
 * Decides only WHEN to click send for one recording segment. Shared with the host tests
 * so both exercise the production rule.
 *
 * It deliberately knows nothing about whether the text was transcribed or whether the
 * submission reached the server: that is ChatGPT's own business. Whether the PREVIOUS
 * segment was acknowledged is likewise not a reason to stop this one -- the user asked
 * for immediate continuation, and stopping to "verify delivery" was an unnecessary
 * restriction added earlier.
 *
 * What it does guard against is acting on unreliable input: a sample that is stale,
 * unclassified, or absent must never trigger a submit. The maximum duration is always
 * a valid fallback trigger.
 */
final class SegmentSubmitDecision {
    enum Decision {
        /** Keep recording; nothing is ready. */
        WAIT,
        /** Submit because a qualified visual pause was observed. */
        SEND_PAUSE,
        /** Submit because the maximum duration was reached. */
        SEND_TIMED
    }

    private final boolean deadlineReached;
    /** The latest waveform sample exists, is fresh and was classified. */
    private final boolean fresh;
    /** The latest waveform sample recommended a pause-based submit. */
    private final boolean visualPause;

    SegmentSubmitDecision(boolean deadlineReached, boolean fresh, boolean visualPause) {
        this.deadlineReached = deadlineReached;
        this.fresh = fresh;
        this.visualPause = visualPause;
    }

    Decision decide() {
        // A pause may only trigger a submit while the signal is trustworthy.
        if (!deadlineReached && !(fresh && visualPause)) return Decision.WAIT;
        return !deadlineReached ? Decision.SEND_PAUSE : Decision.SEND_TIMED;
    }
}
