package org.gptdictation.assistant;

/** Host-only tests for the geometric send-button locator. */
public final class SendButtonGeometryTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    // A composer roughly like ChatGPT's: x 60..900, y 2290..2380 (height 90).
    private static final int CL = 60, CT = 2290, CR = 900, CB = 2380;

    private static void acceptsTheArrowToTheRight() {
        check(SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 950, 2300, 1010, 2360),
                "an icon on the same row to the right is the send button");
    }

    private static void rejectsControlsElsewhere() {
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 950, 2000, 1010, 2060),
                "far above the composer row");
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 100, 2300, 160, 2360),
                "inside/left of the composer is not the send arrow");
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 100, 2340, 500, 2380),
                "a wide element is not a send icon");
    }

    private static void rejectsDegenerateInputs() {
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CT, 950, 2300, 1010, 2360),
                "zero-height composer cannot be judged");
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 950, 2300, 950, 2360),
                "zero-width candidate is invalid");
    }

    private static void rowToleranceIsBounded() {
        // Slightly off-centre but within one composer height is fine.
        check(SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 950, 2310, 1010, 2370),
                "small vertical offset accepted");
        // A whole composer height away is not the same row.
        check(!SendButtonGeometry.looksLikeSendButton(CL, CT, CR, CB, 950, 2390, 1010, 2450),
                "one row below is rejected");
    }

    public static void main(String[] args) {
        acceptsTheArrowToTheRight();
        rejectsControlsElsewhere();
        rejectsDegenerateInputs();
        rowToleranceIsBounded();
        System.out.println("SendButtonGeometry: position-based send-button detection passed");
    }
}
