package org.gptdictation.assistant;

/** Run with javac/java; no Android runtime or phone is needed. */
public final class RecordingGuardTest {
    private static void expect(RecordingGuard.Result actual, RecordingGuard.Result expected) {
        if (actual != expected) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        RecordingGuard g = new RecordingGuard();
        expect(g.observe(0, true), RecordingGuard.Result.READY);
        expect(g.observe(100, false), RecordingGuard.Result.RECHECK);
        expect(g.observe(300, true), RecordingGuard.Result.RECHECK);
        expect(g.observe(549, true), RecordingGuard.Result.RECHECK);
        expect(g.observe(550, true), RecordingGuard.Result.READY);
        expect(g.observe(600, true), RecordingGuard.Result.READY);
        expect(g.observe(1000, false), RecordingGuard.Result.RECHECK);
        expect(g.observe(1100, true), RecordingGuard.Result.RECHECK);
        expect(g.observe(1250, false), RecordingGuard.Result.RECHECK);
        expect(g.observe(1300, true), RecordingGuard.Result.RECHECK);
        expect(g.observe(1549, true), RecordingGuard.Result.RECHECK);
        expect(g.observe(1550, true), RecordingGuard.Result.READY);
        expect(g.observe(2000, false), RecordingGuard.Result.RECHECK);
        expect(g.observe(4999, false), RecordingGuard.Result.RECHECK);
        expect(g.observe(5000, true), RecordingGuard.Result.PAUSE);
        g.reset();
        expect(g.observe(5001, true), RecordingGuard.Result.READY);
        System.out.println("RecordingGuard: transient gap, recovery, flapping and timeout passed");
    }
}
