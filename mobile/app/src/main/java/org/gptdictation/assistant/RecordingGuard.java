package org.gptdictation.assistant;

/** Bounds transient accessibility-tree gaps without permitting a click on stale evidence. */
final class RecordingGuard {
    enum Result { READY, RECHECK, PAUSE }
    private static final long MAX_GAP_MS = 3000;
    private static final long STABLE_MS = 250;
    private long gapSince = -1;
    private long stableSince = -1;

    void reset() { gapSince = -1; stableSince = -1; }

    Result observe(long now, boolean recordingNodesPresent) {
        if (gapSince < 0) {
            if (recordingNodesPresent) return Result.READY;
            gapSince = now;
            return Result.RECHECK;
        }
        if (now - gapSince >= MAX_GAP_MS) return Result.PAUSE;
        if (!recordingNodesPresent) {
            stableSince = -1;
            return Result.RECHECK;
        }
        if (stableSince < 0) stableSince = now;
        if (now - stableSince < STABLE_MS) return Result.RECHECK;
        reset();
        return Result.READY;
    }
}
