package org.gptdictation.assistant;

/**
 * Placeholder-free geometry helper: decides whether a candidate control sits where a
 * composer's send button would be.
 *
 * Extracted so the geometric fallback used by findSendMessage can be tested without
 * Android. ChatGPT's send control is an icon with no reliable label, so position is the
 * only dependable signal.
 */
final class SendButtonGeometry {
    private SendButtonGeometry() { }

    /**
     * @param composerL composer left, composerT top, composerR right, composerB bottom
     * @param candidateL candidate left, candidateT top, candidateR right, candidateB bottom
     */
    static boolean looksLikeSendButton(int composerL, int composerT, int composerR, int composerB,
                                       int candidateL, int candidateT, int candidateR, int candidateB) {
        int composerHeight = composerB - composerT;
        if (composerHeight <= 0) return false;
        if (candidateR <= candidateL || candidateB <= candidateT) return false;
        int composerCenterY = (composerT + composerB) / 2;
        int candidateCenterY = (candidateT + candidateB) / 2;
        // Half a composer height keeps the send arrow (same row) in while rejecting
        // controls on the row above/below, which the earlier full-height tolerance let in.
        boolean sameRow = Math.abs(candidateCenterY - composerCenterY) < composerHeight / 2;
        boolean toTheRight = candidateL >= composerR - composerHeight;
        boolean plausibleSize = (candidateR - candidateL) <= composerHeight * 2
                && (candidateB - candidateT) <= composerHeight * 2;
        return sameRow && toTheRight && plausibleSize;
    }
}
