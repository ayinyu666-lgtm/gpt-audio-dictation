package org.gptdictation.assistant;

/**
 * Validated user settings for one recording run.
 *
 * Deliberately contains no transcript, message, delivery or "unverified" state:
 * the assistant only decides when to click send and starts the next segment as
 * soon as the recording entry is usable again.
 */
public final class RecordingSettings {
    public static final int MIN_SECONDS = 5, MAX_SECONDS = 1800;
    public static final int DEFAULT_SECONDS = 200;
    /** Three times the earlier +-3 span, same one-step granularity. */
    public static final int MIN_PAUSE_BIAS = -9, MAX_PAUSE_BIAS = 9;
    public static final int DEFAULT_PAUSE_BIAS = 0;
    public static final int DEFAULT_NOISE_IGNORE = 0;

    public final int maxDurationSeconds, pauseBias, noiseIgnore;

    public RecordingSettings(int maxDurationSeconds, int pauseBias, int noiseIgnore) {
        if (maxDurationSeconds < MIN_SECONDS || maxDurationSeconds > MAX_SECONDS)
            throw new IllegalArgumentException("max duration out of range");
        this.maxDurationSeconds = maxDurationSeconds;
        this.pauseBias = Math.max(MIN_PAUSE_BIAS, Math.min(MAX_PAUSE_BIAS, pauseBias));
        this.noiseIgnore = NoiseIgnorePolicy.clamp(noiseIgnore);
    }

    public static RecordingSettings defaults() {
        return new RecordingSettings(DEFAULT_SECONDS, DEFAULT_PAUSE_BIAS, DEFAULT_NOISE_IGNORE);
    }

    /** Parses a user-entered duration, returning null when it is not acceptable. */
    public static Integer parseSeconds(String text) {
        if (text == null) return null;
        try {
            int value = Integer.parseInt(text.trim());
            return value < MIN_SECONDS || value > MAX_SECONDS ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
