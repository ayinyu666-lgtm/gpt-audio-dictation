package org.gptdictation.assistant;

/**
 * Host-only harness that replays a whole recording round against the pure decision
 * classes, in the same order the service calls them. It does not touch Android APIs and
 * does not pretend to be the service: it covers the interaction between the detector,
 * the adaptive pause policy, the crop tracker, the sampling policy and the guard.
 *
 * Times are monotonic milliseconds like SystemClock.elapsedRealtime().
 */
final class RecordingRound {
    private final WaveformDetector detector;
    private final AdaptivePausePolicy adaptive = new AdaptivePausePolicy();
    private final VisualCropTracker crop = new VisualCropTracker();
    private final RecordingGuard guard = new RecordingGuard();
    /**
     * Segment ceiling and start, mirroring the service's {@code duration * 1000L} and
     * {@code recordUntil}. The ceiling comes from the detector config because that is the
     * only segment-length notion this harness receives.
     */
    private final long maxSegmentMs;
    private long segmentStartMs;

    /** What the round decided at each tick. */
    enum Outcome {
        WAITING,
        /** Submitted because a visual pause was observed. */
        SENT_VISUAL_PAUSE,
        /** Submitted because the maximum duration was reached. */
        SENT_TIMED_FALLBACK,
        /** Stopped without submitting; the current recording is still running. */
        PAUSED
    }

    /** One observation handed to the round, mirroring one service tick. */
    static final class Step {
        final long now;
        /** null when the screenshot failed or was not requested. */
        final int[] pixels;
        final int width, height;
        final boolean recordingNodesPresent;
        /** null when the crop could not be measured. */
        final int[] measuredCrop;
        /** false when the recording UI was not independently verified this tick. */
        final boolean recordingUiVerified;
        final boolean deadlineReached;

        Step(long now, int[] pixels, int width, int height, boolean recordingNodesPresent,
             int[] measuredCrop, boolean recordingUiVerified, boolean deadlineReached) {
            this.now = now; this.pixels = pixels; this.width = width; this.height = height;
            this.recordingNodesPresent = recordingNodesPresent; this.measuredCrop = measuredCrop;
            this.recordingUiVerified = recordingUiVerified; this.deadlineReached = deadlineReached;
        }
    }

    private long lastWaveformAt;
    private WaveformDetector.Result lastWaveform;
    private long segmentVisualActiveMs;
    private int bias;
    private boolean submitted;

    RecordingRound(WaveformDetector.Config config, int bias, long startMs) {
        this.detector = new WaveformDetector(config);
        this.maxSegmentMs = config.maxSegmentMs;
        this.segmentStartMs = startMs;
        this.bias = bias;
        detector.beginSegment(startMs);
        crop.resetSession();
        crop.resetSegment();
    }

    void beginSegment(long now) {
        detector.beginSegment(now);
        adaptive.beginSegment();
        crop.resetSegment();
        guard.reset();
        segmentStartMs = now;
        lastWaveform = null; lastWaveformAt = 0; segmentVisualActiveMs = 0; submitted = false;
    }

    void setBias(int value) { bias = value; }

    /**
     * The pause the detector must observe, computed exactly as DictationService does:
     * the learned value is the ceiling-side anchor and the deadline-pressure curve decides
     * what is actually demanded at this point of the segment. Without this the harness
     * would keep simulating the old, pressure-free rule and its tests would prove nothing.
     */
    private long requiredPauseMs(long now) {
        long normal = adaptive.requiredPauseMs(bias, segmentVisualActiveMs);
        long elapsed = Math.max(0, now - segmentStartMs);
        double f = DeadlinePressurePolicy.elapsedFraction(maxSegmentMs - elapsed, maxSegmentMs);
        return DeadlinePressurePolicy.requiredPauseMs(f, normal, bias);
    }

    boolean submitted() { return submitted; }

    Outcome tick(Step step) {
        if (submitted) return Outcome.WAITING;
        // Order matters: the guard decides whether the UI is trustworthy at all.
        RecordingGuard.Result check = guard.observe(step.now, step.recordingNodesPresent);
        if (check == RecordingGuard.Result.PAUSE) return Outcome.PAUSED;
        boolean recordingReady = check == RecordingGuard.Result.READY;
        if (!recordingReady) { invalidate(step.now); return Outcome.WAITING; }

        VisualCropTracker.Action cropAction = crop.observe(step.measuredCrop);
        if (cropAction == VisualCropTracker.Action.REARM) {
            detector.reset();
            detector.beginSegment(step.now);
            adaptive.reset();
            segmentVisualActiveMs = 0;
            invalidate(step.now);
        }
        int[] activeCrop = cropAction == VisualCropTracker.Action.SAMPLE ? crop.current() : null;

        if (step.pixels != null && activeCrop != null && step.recordingUiVerified) {
            lastWaveform = detector.observe(step.pixels, step.width, step.height, step.now,
                    requiredPauseMs(step.now));
            segmentVisualActiveMs = lastWaveform.visualActiveMs;
            adaptive.observe(lastWaveform.signal, step.now, true);
            lastWaveformAt = lastWaveform.signal == WaveformDetector.Signal.INVALID ? 0 : step.now;
        } else {
            invalidate(step.now);
        }

        boolean fresh = WaveformSamplingPolicy.fresh(step.now, lastWaveformAt) && lastWaveform != null
                && lastWaveform.signal != WaveformDetector.Signal.INVALID;
        boolean pauseReady = fresh && lastWaveform.decision == WaveformDetector.Decision.SEND_PAUSE
                && lastWaveform.signal == WaveformDetector.Signal.VISUAL_QUIET;
        // The production rule, shared with DictationService: nothing is re-decided here.
        SegmentSubmitDecision.Decision decision = new SegmentSubmitDecision(
                step.deadlineReached, fresh, pauseReady).decide();
        switch (decision) {
            case WAIT: return Outcome.WAITING;
            case SEND_PAUSE: submitted = true; return Outcome.SENT_VISUAL_PAUSE;
            default: submitted = true; return Outcome.SENT_TIMED_FALLBACK;
        }
    }

    private void invalidate(long now) {
        lastWaveform = null; lastWaveformAt = 0;
        detector.observe(null, 0, 0, now, requiredPauseMs(now));
        adaptive.observe(WaveformDetector.Signal.INVALID, now, false);
    }
}
