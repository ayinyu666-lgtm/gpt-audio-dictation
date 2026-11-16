package org.gptdictation.assistant;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-only state-machine tests for a whole recording round: pause send, immediate
 * re-arming for the next segment, maximum-duration fallback, missing screenshots, an
 * unverified previous delivery, node gaps and layout movement.
 */
public final class RecordingRoundTest {
    private static final int W = 120, H = 80;
    private static final int[] CROP = {559, 2299, 762, 2371};
    private static final int[] MOVED_CROP = {540, 2200, 743, 2272};
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static int gray(int y) { return 0xff000000 | y << 16 | y << 8 | y; }
    private static int[] frame(double size, int tick) {
        int[] p = new int[W * H];
        java.util.Arrays.fill(p, gray(45));
        int h = Math.max(4, (int) Math.round(size * H));
        for (int x = 3; x < W; x++) if (x % 10 < 5)
            for (int y = (H - h) / 2; y < (H + h) / 2; y++) p[y * W + x] = gray(155);
        p[(tick % 5) * W + tick % W] = gray(155);
        return p;
    }
    private static RecordingRound.Step step(long now, int[] pixels, boolean nodes, int[] crop,
                                            boolean verified, boolean deadline) {
        return new RecordingRound.Step(now, pixels, W, H, nodes, crop, verified, deadline);
    }
    private static RecordingRound round(long maxMs, int bias) {
        return new RecordingRound(new WaveformDetector.Config(1000, 2000, maxMs, 2500, 5000), bias, 0);
    }

    /**
     * Visual pauses are always required to submit at least once per segment.
     *
     * The ceiling is 20 s, not 120 s, because the deadline-pressure rule only relaxes
     * the pause requirement near the ceiling: at 20 s the curve reaches the learned
     * pause at f=0.95 (19 s) and the 1.2 s floor at 20 s. A 120 s ceiling would demand
     * a 30 s silence during the first 48 s, so the old fixture would be testing the old
     * rule. The fixture keeps its original intent: activity, then a real pause, must
     * submit, and the next segment must re-arm immediately.
     */
    private static void pauseSendThenRearm() {
        RecordingRound r = round(20000, 0);
        RecordingRound.Outcome outcome = RecordingRound.Outcome.WAITING;
        long t = 0;
        // Low activity first so the detector can observe a distinguishable reference.
        for (int i = 0; i < 8; i++, t += 500) outcome = r.tick(step(t, frame(.075, i), true, CROP, true, false));
        for (int i = 0; i < 24; i++, t += 500) {
            outcomeless(outcome);
            outcome = r.tick(step(t, frame(.40, i + 20), true, CROP, true, false));
        }
        // t is now 16 s; a 2 s+ silence lands in the aggressive last fifth of the window.
        for (int i = 0; i < 10 && outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE; i++, t += 500) {
            outcome = r.tick(step(t, frame(.075, i + 40), true, CROP, true, false));
        }
        check(outcome == RecordingRound.Outcome.SENT_VISUAL_PAUSE,
                "observable quiet after activity should submit, got " + outcome);

        // The next recording must start immediately with no lingering submit state.
        r.beginSegment(t);
        check(!r.submitted(), "new segment must be able to submit again");
        t += 500;
        outcome = r.tick(step(t, frame(.075, 1), true, CROP, true, false));
        check(outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE,
                "no visual pause without a fresh qualified activity period");
        t += 500;
        // An unacknowledged previous segment must NOT stop this one: the user asked for
        // continuation without any delivery judgement in between.
        outcome = r.tick(step(t, frame(.075, 2), true, CROP, true, true));
        check(outcome == RecordingRound.Outcome.SENT_TIMED_FALLBACK,
                "unacknowledged previous delivery must not block this segment");
        System.out.println("RecordingRound: pause submit then immediate re-arm passed");
    }

    private static void outcomeless(RecordingRound.Outcome outcome) {
        check(outcome == RecordingRound.Outcome.WAITING, "active frames must not submit: " + outcome);
    }

    /** Screenshots that stop arriving must fall back to the maximum duration. */
    private static void timeoutFallback() {
        RecordingRound r = round(6000, 0);
        RecordingRound.Outcome outcome = RecordingRound.Outcome.WAITING;
        long t = 0;
        for (int i = 0; i < 10; i++, t += 500) {
            outcome = r.tick(step(t, i % 2 == 0 ? frame(.075, i) : null, true, CROP, true, false));
        }
        check(outcome == RecordingRound.Outcome.WAITING, "no signal cannot submit early, got " + outcome);
        outcome = r.tick(step(t, null, true, CROP, true, true));
        check(outcome == RecordingRound.Outcome.SENT_TIMED_FALLBACK,
                "maximum duration must submit even without screenshots, got " + outcome);

        // An unacknowledged previous segment must not block the timed fallback either.
        r = round(6000, 0);
        for (int i = 0; i < 10; i++, t += 500) r.tick(step(t, null, true, CROP, true, false));
        outcome = r.tick(step(t, null, true, CROP, true, true));
        check(outcome == RecordingRound.Outcome.SENT_TIMED_FALLBACK,
                "unacknowledged previous delivery must not block the timed fallback");
        System.out.println("RecordingRound: maximum-duration fallback without delivery gating");
    }

    /**
     * A keyboard opening must re-learn, not lose pause detection for the whole class.
     * Ceiling 20 s for the same reason as pauseSendThenRearm: the pause has to land in
     * the late, aggressive part of the deadline-pressure curve to be able to fire.
     */
    private static void keyboardReflow() {
        RecordingRound r = round(20000, 0);
        long t = 0;
        // Build a baseline, then speak long enough that the late-pressure window is open
        // (t = 8 s -> 16 s) before the layout moves at t = 16.5 s.
        for (int i = 0; i < 8; i++, t += 500) r.tick(step(t, frame(.075, i), true, CROP, true, false));
        for (int i = 0; i < 16; i++, t += 500) r.tick(step(t, frame(.40, i + 30), true, CROP, true, false));
        // Layout moves once; learning restarts and a pause must still be reachable.
        t += 500;
        RecordingRound.Outcome outcome = r.tick(step(t, frame(.40, 50), true, MOVED_CROP, true, false));
        check(outcome == RecordingRound.Outcome.WAITING, "reflow itself must not submit");
        for (int i = 0; i < 8; i++, t += 500) {
            outcome = r.tick(step(t, frame(.075, i + 60), true, MOVED_CROP, true, false));
            check(outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE, "re-learning cannot submit yet");
        }
        // Re-establish activity so the detector has a qualified speech run again before
        // the pause that is supposed to recover detection.
        for (int i = 0; i < 8 && outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE; i++, t += 500) {
            outcome = r.tick(step(t, frame(.40, i + 70), true, MOVED_CROP, true, false));
        }
        check(outcome == RecordingRound.Outcome.WAITING, "activity after reflow must hold, got " + outcome);
        for (int i = 0; i < 10 && outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE; i++, t += 500) {
            outcome = r.tick(step(t, frame(.075, i + 80), true, MOVED_CROP, true, false));
        }
        check(outcome == RecordingRound.Outcome.SENT_VISUAL_PAUSE,
                "pause detection must recover after one layout move, got " + outcome);
        System.out.println("RecordingRound: keyboard reflow recovery passed");
    }

    /** Node gaps pause; short gaps only delay. Failed screenshots must never send. */
    private static void nodeGapsAndFailures() {
        RecordingRound r = round(6000, 0);
        long t = 0;
        check(r.tick(step(0, null, false, CROP, false, false)) == RecordingRound.Outcome.WAITING,
                "brief node gap waits");
        for (int i = 1; i <= 12; i++) r.tick(step(t + i * 500L, null, false, null, false, false));
        RecordingRound.Outcome outcome = r.tick(step(t + 6500, null, false, null, false, false));
        check(outcome == RecordingRound.Outcome.PAUSED, "sustained missing nodes must pause, got " + outcome);
        System.out.println("RecordingRound: node gap and failed-screenshot handling passed");
    }

    /** Real recorded frames: a paused-then-resumed schedule must behave like a pause send. */
    private static void realFrames(String root) throws Exception {
        Path dir = Paths.get(root, "_tmp/phone/mobile-plan-review/waveform-fixtures");
        String manifest = new String(Files.readAllBytes(dir.resolve("manifest.json")), java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\\"phase\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"time\\\"\\s*:\\s*([0-9.]+)").matcher(manifest);
        int[][] paused = new int[7][], active = new int[14][];
        int p = 0, a = 0;
        while (m.find()) {
            BufferedImage image = javax.imageio.ImageIO.read(dir.resolve(m.group(2)).toFile());
            int[] pixels = image.getRGB(0, 0, 200, 80, null, 0, 200);
            if ("paused".equals(m.group(1))) { if (p < paused.length) paused[p++] = pixels; }
            else if (a < active.length) active[a++] = pixels;
        }
        check(p == 7 && a == 14, "fixture layout changed: paused=" + p + " active=" + a);

        RecordingRound r = new RecordingRound(new WaveformDetector.Config(1000, 2000, 20000, 2500, 5000), 0, 0);
        RecordingRound.Outcome outcome = RecordingRound.Outcome.WAITING;
        long t = 0;
        // Synthetic replay using real pixels: paused frames build the reference, then
        // active frames are followed by paused frames again. Not the original timeline.
        // 20 s ceiling: f=0.95 falls at 19 s, so the synthetic pause below lands inside
        // the aggressive window instead of being rejected by the 30 s early requirement.
        for (int i = 0; i < 8; i++, t += 1000)
            outcome = r.tick(new RecordingRound.Step(t, paused[i % p], 200, 80, true, CROP, true, false));
        for (int i = 0; i < 14; i++, t += 1000) {
            check(outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE, "real active pixels must not submit");
            outcome = r.tick(new RecordingRound.Step(t, active[i], 200, 80, true, CROP, true, false));
        }
        for (int i = 0; i < 10 && outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE; i++, t += 1000) {
            outcome = r.tick(new RecordingRound.Step(t, paused[i % p], 200, 80, true, CROP, true, false));
        }
        check(outcome == RecordingRound.Outcome.SENT_VISUAL_PAUSE,
                "real pixel pause after activity should submit, got " + outcome);
        System.out.println("RecordingRound: real-frame schedule produced a pause submit; replay order is synthetic");
    }

    /** Different rooms: three background levels all reach a usable learned pause. */
    private static void noiseLevels() {
        for (double base : new double[] {.075, .20, .30}) {
            // 20 s ceiling so the pause window (f >= 0.95 at 19 s) is actually reachable
            // within a short fixture; a longer ceiling would demand a 30 s silence here.
            RecordingRound r = round(20000, 0);
            long t = 0;
            for (int i = 0; i < 8; i++, t += 500)
                r.tick(step(t, frame(base, i), true, CROP, true, false));
            for (int i = 0; i < 24; i++, t += 500)
                r.tick(step(t, frame(base + .30, i + 20), true, CROP, true, false));
            RecordingRound.Outcome outcome = RecordingRound.Outcome.WAITING;
            for (int i = 0; i < 10 && outcome != RecordingRound.Outcome.SENT_VISUAL_PAUSE; i++, t += 500)
                outcome = r.tick(step(t, frame(base, i + 40), true, CROP, true, false));
            check(outcome == RecordingRound.Outcome.SENT_VISUAL_PAUSE,
                    "learned background " + base + " must reach a pause submit, got " + outcome);
        }
        System.out.println("RecordingRound: background levels .075/.20/.30 all adapted");
    }

    /**
     * The user's main goal: right after a submit, the next recording starts as soon as
     * the entry point is usable again, with no delivery verification in between.
     */
    private static void immediateContinuation() {
        RecordingRound r = round(6000, 0);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 500) r.tick(step(t, null, true, CROP, true, false));
        check(r.tick(step(t, null, true, CROP, true, true))
                        == RecordingRound.Outcome.SENT_TIMED_FALLBACK, "maximum submits");
        // Next segment: unacknowledged previous delivery must not block recording.
        r.beginSegment(t + 500);
        for (int i = 0; i < 8; i++, t += 500) {
            RecordingRound.Outcome o = r.tick(step(t, null, true, CROP, true, false));
            check(o == RecordingRound.Outcome.WAITING, "unacknowledged must not stop this segment");
        }
        check(r.tick(step(t, null, true, CROP, true, true))
                        == RecordingRound.Outcome.SENT_TIMED_FALLBACK,
                "unacknowledged previous delivery must not block the next submit");
        System.out.println("RecordingRound: immediate continuation without delivery verification");
    }

    /**
     * A whole lesson's worth of segments: many consecutive segments each submit at their
     * own maximum and immediately re-arm, with no dependency on any acknowledgement or
     * saved preference slowing them down.
     */
    private static void manyConsecutiveSegments() {
        RecordingRound r = round(10000, 0);
        long t = 0;
        for (int seg = 1; seg <= 12; seg++) {
            r.beginSegment(t);
            RecordingRound.Outcome outcome = RecordingRound.Outcome.WAITING;
            for (int i = 0; i < 6 && outcome == RecordingRound.Outcome.WAITING; i++, t += 500) {
                outcome = r.tick(step(t, null, true, CROP, true, false));
            }
            outcome = r.tick(step(t, null, true, CROP, true, true));
            check(outcome == RecordingRound.Outcome.SENT_TIMED_FALLBACK,
                    "segment " + seg + " must submit at its maximum, got " + outcome);
            t += 500;
        }
        System.out.println("RecordingRound: 12 consecutive segments each submitted and re-armed");
    }

    public static void main(String[] args) throws Exception {
        pauseSendThenRearm();
        timeoutFallback();
        keyboardReflow();
        nodeGapsAndFailures();
        noiseLevels();
        immediateContinuation();
        manyConsecutiveSegments();
        if (args.length == 1 && !args[0].isBlank()) {
            realFrames(args[0]);
        } else {
            System.out.println("RecordingRound: private real-frame replay skipped (no fixture root provided)");
        }
        System.out.println("RecordingRound: full-round state machine tests passed");
    }
}
