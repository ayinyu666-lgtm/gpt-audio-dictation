package org.gptdictation.assistant;

/**
 * How often to take a screenshot, based on whether the room currently has a voice in it.
 *
 * The rule the user asked for, in their words: any pause must be caught, so sampling is
 * fast while there is sound; while the recording is genuinely silent there is nothing to
 * catch, so sampling may slow down; as soon as sound comes back, sampling speeds up again.
 *
 * That ordering matters. "Talk for a long time" is NOT a reason to slow down, because a
 * long stretch of speech can end in a pause at any moment. Only measured silence may slow
 * the cadence. This is why the state is driven by the detector's own speech/quiet signal
 * rather than by elapsed time.
 *
 * Pure Java so the transitions are host-testable without a device.
 */
final class AdaptiveSamplingPolicy {
    /** Fast cadence: used while speech is present and right after it stops. */
    static final long FAST_MS = 500;
    /** Slower steps for a room that has been silent for a while. */
    static final long SLOW_MS = 900;
    /**
     * Slowest cadence, used only after a long measured silence.
     *
     * It MUST stay below {@link WaveformSamplingPolicy#FRESH_MS} (1500) with enough
     * margin for the 400 ms recording tick. DictationService.watchCapture() runs before
     * requestWaveform() in the same tick and invalidates a sample older than FRESH_MS;
     * a real invalid frame resets the detector's pauseMs. At 1500 the next sample landed
     * 1600 ms later (4 ticks), so pauseMs was zeroed before every capture and could never
     * reach the measured requiredPauseMs (2057 ms) - SEND_PAUSE was impossible at the
     * slowest tier. 1200 leaves 300 ms of margin: samples land every 3 ticks (1200 ms).
     */
    static final long IDLE_MS = 1200;
    /** Silent time before stepping down, and before stepping down again. */
    static final long SLOW_AFTER_MS = 3000;
    static final long IDLE_AFTER_MS = 8000;
    /**
     * How long a detector verdict stays trusted while every new frame is unreadable.
     *
     * An INVALID frame is not evidence of silence: it means the picture could not be
     * classified. Ignoring it outright was still wrong, because the last VISUAL_QUIET
     * stayed latched forever: one quiet frame followed by twenty seconds of INVALID kept
     * intervalMs() at IDLE_MS while someone may well have been talking.
     *
     * The threshold cannot be the naive "3 ticks" (1200 ms): in the slowest tier a valid
     * verdict only arrives once per capture cycle, i.e. every 1200 ms at best plus the
     * screenshot callback latency. A 1200 ms threshold would therefore expire just before
     * each legitimate slow-tier verdict and the slow tier could never exist at all.
     * 2400 ms is two full slowest-tier cycles (six 400 ms recording ticks): long enough
     * that normal slow operation never trips it, short enough that a run of unreadable
     * frames drops back to the fast cadence within about two seconds. Faster sampling is
     * always the safe direction: it cannot make a pause be missed.
     */
    static final long VERDICT_STALE_MS = 2400;

    private long quietSince = -1;
    private boolean speaking;
    /** Timestamp of the last readable (true/false) verdict; -1 until the first one. */
    private long lastValidAt = -1;

    /** Call when a new segment starts: no history to carry over. */
    void reset() {
        quietSince = -1;
        speaking = false;
        lastValidAt = -1;
    }

    /**
     * Feed one detector verdict.
     *
     * @param voiced true when the detector says there is sound, false when it says quiet,
     *               null when this frame could not be classified (ambiguous)
     */
    void observe(Boolean voiced, long now) {
        if (voiced == null) return;          // an unreadable frame does not move quietSince
        lastValidAt = now;
        if (voiced) {
            speaking = true;
            quietSince = -1;
        } else {
            speaking = false;
            if (quietSince < 0) quietSince = now;
        }
    }

    /** Current screenshot spacing in milliseconds. */
    long intervalMs(long now) {
        if (speaking) return FAST_MS;
        if (quietSince < 0) return FAST_MS;  // nothing measured yet: stay responsive
        // No readable verdict for too long: the last one is no longer evidence.
        // Treat the state as unknown and sample fast until the detector speaks again.
        if (lastValidAt >= 0 && now - lastValidAt > VERDICT_STALE_MS) return FAST_MS;
        long quietFor = now - quietSince;
        if (quietFor >= IDLE_AFTER_MS) return IDLE_MS;
        if (quietFor >= SLOW_AFTER_MS) return SLOW_MS;
        return FAST_MS;
    }

    /** True while the current cadence is the slow one, for diagnostics. */
    boolean slowed(long now) { return intervalMs(now) > FAST_MS; }
}
