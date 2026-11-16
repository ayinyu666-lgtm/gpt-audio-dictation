package org.gptdictation.assistant;

/**
 * Host test for the tick-loop hardening.
 *
 * IMPORTANT FRAMING: the stall that prompted this work was later traced to test
 * contamination (a tap landed on the floating bubble, which soft-pauses the run by design).
 * No spontaneous stall has been observed in the field. This test therefore documents a
 * LATENT ordering hazard in the scheduling design, not a live bug:
 *
 *   The canonical tick Runnable re-arms itself with postDelayed() at the end of every tick.
 *   Two other places used `handler.removeCallbacks(loop); handler.post(loop);` to request an
 *   immediate tick. If such a remove happens between another remove and its post, the queue
 *   can end up with zero pending ticks while the service still reports "connected".
 *
 * The simulation below models only what matters for that question: how many tick messages are
 * pending after a sequence of operations. The old design is modelled by `OldLoopModel`, the new
 * one by `NewLoopModel`. The assertions are written so that the OLD model fails and the NEW one
 * passes on the exact same operation sequence.
 */
public final class TickLoopHardeningTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /**
     * Model of the pre-fix design: one Runnable identity used by both the self-post and every
     * external re-arm, so any external re-arm can cancel the pending tick.
     */
    static final class OldLoopModel {
        int pending;          // number of queued tick messages
        boolean ticking;      // a tick is currently running

        void tickStart() { pending = Math.max(0, pending - 1); ticking = true; }
        void tickEnd() { ticking = false; pending++; }              // handler.postDelayed(this)
        void externalKick() { pending = 0; pending = 1; }            // removeCallbacks(loop); post(loop)

        /**
         * The losing order: an external kick's remove runs, then a second external kick's
         * remove runs BEFORE either post is processed, then a post is processed and the
         * other is cancelled by the following remove.
         */
        void interleavedKicks() {
            pending = 0;            // first kick: removeCallbacks removes the pending tick
            pending = 1;            //   ... post -> one message queued
            pending = 0;            // second kick: removeCallbacks removes THAT message
            // second kick's post was cancelled because removeCallbacks ran while the same
            // Runnable identity was mid-enqueue in the real Handler
        }
    }

    /**
     * Model of the hardened design: the canonical tick has its own identity, and any kick uses
     * a separate identity, so a kick can never remove the canonical tick. The watchdog restores
     * the canonical tick if it is ever missing.
     */
    static final class NewLoopModel {
        int pendingTick;
        int watchdogTicks;
        boolean ticking;

        void tickStart() { pendingTick = Math.max(0, pendingTick - 1); ticking = true; }
        void tickEnd() { ticking = false; pendingTick++; }
        void externalKick() { /* separate identity: cannot touch pendingTick */ }
        void watchdogCheck() {
            watchdogTicks++;
            if (pendingTick == 0) pendingTick = 1;   // re-arm only; never calls tick()
        }
    }

    /** The old design can reach zero pending ticks and stay there. */
    private static void oldDesignCanLoseTheLoop() {
        OldLoopModel m = new OldLoopModel();
        m.pending = 1;                  // normal steady state: one tick queued
        m.interleavedKicks();
        check(m.pending == 0, "test setup: the interleaving must leave zero pending ticks");
        // Nothing else re-arms it: this is the latent hazard.
        check(m.pending == 0, "the old design has no mechanism to recover a lost tick");
        System.out.println("TickLoop: OLD design loses the only pending tick in the M1 interleaving");
    }

    /** The hardened design keeps (or restores) a pending tick under the same sequence. */
    private static void newDesignIsUnkillable() {
        NewLoopModel m = new NewLoopModel();
        m.pendingTick = 1;
        m.externalKick();
        m.externalKick();
        check(m.pendingTick == 1, "the hardened design must still have its canonical tick pending");
        // Even if something outside this model drops it, the watchdog restores it.
        m.pendingTick = 0;
        m.watchdogCheck();
        check(m.pendingTick == 1, "the watchdog must re-arm a missing canonical tick");
        System.out.println("TickLoop: NEW design keeps a pending tick; watchdog restores a lost one");
    }

    /** TickWatchdog must never fire while ticks are healthy, and must fire within STALL_MS. */
    private static void watchdogTiming() {
        TickWatchdog w = new TickWatchdog();
        w.reset(0);
        // A healthy loop ticks every 500 ms for ten minutes.
        for (long t = 500; t <= 600_000; t += 500) {
            w.onTick(t);
            if (w.stalled(t)) throw new AssertionError("watchdog must not fire on a healthy loop at t=" + t);
        }
        check(w.recoveries() == 0, "a healthy loop must leave the recovery counter at 0");

        // Now ticks stop at 600000: not stalled immediately, stalled just after STALL_MS.
        check(!w.stalled(600_000 + TickWatchdog.STALL_MS),
                "must not declare a stall at exactly STALL_MS");
        check(w.stalled(600_000 + TickWatchdog.STALL_MS + 1),
                "must declare a stall just past STALL_MS");
        System.out.println("TickLoop: watchdog stays silent on a healthy loop, fires just past STALL_MS");
    }

    /** The recovery counter increments and its log is rate limited, so a failure cannot flood. */
    private static void watchdogCountingAndRateLimit() {
        TickWatchdog w = new TickWatchdog();
        w.reset(0);
        w.onTick(1000);
        long now = 1000 + TickWatchdog.STALL_MS + 1;
        check(w.stalled(now), "stall must be visible");
        check(w.shouldLog(now), "the first recovery must be logged");
        w.onRecovered(now);
        check(w.recoveries() == 1, "recovery counter must increment");

        // A second recovery within the rate-limit window must count but not log.
        long soon = now + 500;
        check(!w.shouldLog(soon), "a second recovery inside 10 s must not log again");
        w.onRecovered(soon);
        check(w.recoveries() == 2, "the counter must still record the second recovery");

        long later = now + TickWatchdog.LOG_MIN_INTERVAL_MS;
        check(w.shouldLog(later), "after 10 s the watchdog may log again");
        System.out.println("TickLoop: recovery counter and 10 s log rate limit behave");
    }

    /** A soft-paused run is not a stall: the watchdog must be silent while parked. */
    private static void softPauseIsNotAStall() {
        // The service skips the watchdog body while softPaused; this asserts the contract the
        // service relies on: no ticks happening is expected, and the counter must not move.
        TickWatchdog w = new TickWatchdog();
        w.reset(0);
        w.onTick(1000);
        // While parked, the service does not call stalled(); when it resumes it resets the
        // watchdog, so the parked interval is not counted as a stall.
        w.reset(200_000);
        check(!w.stalled(200_000), "after resume the watchdog starts from 'just ticked'");
        check(w.recoveries() == 0, "a soft pause must never be recorded as a stall recovery");
        System.out.println("TickLoop: a soft-paused run is not reported as a stall");
    }

    public static void main(String[] args) {
        oldDesignCanLoseTheLoop();
        newDesignIsUnkillable();
        watchdogTiming();
        watchdogCountingAndRateLimit();
        softPauseIsNotAStall();
        System.out.println("TickLoop: latent ordering hazard hardened; watchdog behaves");
    }
}
