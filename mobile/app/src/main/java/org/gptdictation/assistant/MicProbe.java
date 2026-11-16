package org.gptdictation.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Optional, user-started microphone probe that only measures whether THIS app's
 * AudioRecord receives non-silent audio. It never stores, uploads or transcribes audio.
 *
 * Android audio concurrency is deliberately uncertain and this class does not pretend
 * otherwise. Since Android 10 the platform may share the input between apps, but the
 * most recent app to start recording generally wins, and many devices mute or hand a
 * silent stream to a background or second recorder. Therefore:
 *
 * - It is OFF by default and only runs when the user explicitly starts a probe.
 * - A result says only "this app received non-silent data", never "ChatGPT heard it".
 * - If the platform grants a silent stream or refuses the source, the probe reports
 *   that honestly and callers fall back to the waveform screenshot path.
 * - Nothing here proves GPT is still recording; that needs a real-device test.
 *
 * Callers must stop it before it can interfere with any recording they care about.
 */
final class MicProbe {
    /** Result names are deliberately about this app's own capture only. */
    enum Result {
        /** The recorder started and is ready for the bounded sample window. */
        STARTED,
        /** Non-silent samples were received by this app. */
        AUDIO_PRESENT,
        /** Capture ran but stayed below the silence threshold. */
        SILENT,
        /** The platform refused or gave a unusable stream; not evidence about the room. */
        UNAVAILABLE,
        /** Not started, or the caller lacks permission. */
        NOT_STARTED
    }

    private static final int RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    /** Rough loudness gate; intentionally coarse, not a calibrated level. */
    private static final int SILENCE_AMPLITUDE = 500;

    private AudioRecord recorder;
    private boolean running;

    /** True only while a probe is active. */
    synchronized boolean isRunning() { return running; }

    /**
     * Starts a short capture. Returns NOT_STARTED when the source cannot be used, which
     * is the normal outcome on devices that will not share the microphone.
     */
    synchronized Result start() {
        stop();
        int min = AudioRecord.getMinBufferSize(RATE, CHANNEL, ENCODING);
        if (min <= 0) return Result.UNAVAILABLE;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, CHANNEL, ENCODING, min * 2);
        } catch (RuntimeException | OutOfMemoryError e) {
            recorder = null;
            return Result.UNAVAILABLE;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { release(); return Result.UNAVAILABLE; }
        try {
            recorder.startRecording();
        } catch (RuntimeException e) {
            release(); return Result.UNAVAILABLE;
        }
        running = true;
        return Result.STARTED;
    }

    /**
     * Reads and discards samples for at most timeoutMs. Call this off the main thread.
     */
    synchronized Result sampleOnce(long timeoutMs) {
        if (!running || recorder == null) return Result.NOT_STARTED;
        short[] buffer = new short[1024];
        long end = android.os.SystemClock.elapsedRealtime() + Math.max(100L, timeoutMs);
        boolean receivedSamples = false;
        while (running && android.os.SystemClock.elapsedRealtime() < end) {
            int read;
            try {
                read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            } catch (RuntimeException e) {
                return Result.UNAVAILABLE;
            }
            if (read < 0) return Result.UNAVAILABLE;
            if (read > 0) receivedSamples = true;
            int peak = 0;
            for (int i = 0; i < read; i++) {
                int v = buffer[i] < 0 ? -buffer[i] : buffer[i];
                if (v > peak) peak = v;
            }
            if (peak > SILENCE_AMPLITUDE) return Result.AUDIO_PRESENT;
            if (read == 0) {
                try { Thread.sleep(20L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return Result.UNAVAILABLE; }
            }
        }
        return receivedSamples ? Result.SILENT : Result.UNAVAILABLE;
    }

    /** Stops and releases the microphone. Safe to call repeatedly. */
    synchronized void stop() {
        running = false;
        release();
    }

    private void release() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            try { recorder.release(); } catch (RuntimeException ignored) { }
            recorder = null;
        }
    }
}
