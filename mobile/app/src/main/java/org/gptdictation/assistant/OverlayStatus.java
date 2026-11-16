package org.gptdictation.assistant;

/**
 * What the floating bubble says. Exactly two states, on purpose.
 *
 * The service has several internal stages (waiting for ChatGPT, waiting for the
 * recording entry, recording, waiting for the entry to come back). Exposing those
 * to the user produced "等待" vs "录音" vs "继续" and made it impossible to tell
 * whether the assistant was actually working. To the user there are only two
 * meaningful answers: it is working, or it is paused.
 */
public final class OverlayStatus {
    public static final String RUNNING = "录音中";
    public static final String PAUSED = "暂停中";

    private OverlayStatus() { }

    /**
     * @param running the assistant is on (a run exists; the bubble is shown)
     * @param paused  the run exists but is parked by the user
     *
     * Not running is not shown at all: no bubble means the assistant is off.
     */
    public static String label(boolean running, boolean paused) {
        return running && !paused ? RUNNING : PAUSED;
    }
}
