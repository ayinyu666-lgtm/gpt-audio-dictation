package org.gptdictation.assistant;

/**
 * Decides what to do when the assistant starts or resumes while ChatGPT may already
 * be in some recording state.
 *
 * The earlier code always went to "wait for the voice entry". If the user resumed
 * while ChatGPT was already recording, that entry never appears, so the assistant
 * sat there doing nothing until it timed out. That is why "继续" looked broken.
 *
 * Pure logic so the decision can be tested without Android.
 */
final class ResumePolicy {
    enum Action {
        /** ChatGPT is already recording this segment: adopt it and start timing now. */
        ADOPT_RECORDING,
        /** The recording entry is available: click it to start a segment. */
        START_RECORDING,
        /** Neither: keep waiting for a usable interface. */
        WAIT
    }

    private ResumePolicy() { }

    /**
     * @param voiceEntryPresent a unique, usable 语音输入 control is visible
     * @param alreadyRecording  cancel/stop controls are present (ChatGPT is recording)
     * @param sendPresent       the 点击发送录音 control is present
     */
    static Action decide(boolean voiceEntryPresent, boolean alreadyRecording, boolean sendPresent) {
        // A live recording segment wins: the user pressed resume to continue working,
        // and starting another recording on top would be wrong.
        if (alreadyRecording && sendPresent && !voiceEntryPresent) return Action.ADOPT_RECORDING;
        if (voiceEntryPresent && !alreadyRecording) return Action.START_RECORDING;
        return Action.WAIT;
    }
}
