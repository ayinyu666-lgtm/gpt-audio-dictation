package org.gptdictation.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-only test: the tall-bar speech threshold must be learnable, storable and reusable
 * without any hand-tuned constant, and the stored format must stay back-compatible.
 */
public final class ShareBarLearningTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** A learning window must derive its own bar from the frames it saw. */
    private static void learningDerivesItsOwnBar() {
        EnvLearnSession s = new EnvLearnSession(1000);
        long t = 0;
        // Background music only: shares around 0.02-0.06 (a room that is never silent).
        double[] background = {.02, .03, .04, .05, .06, .03, .04, .05, .02, .06};
        for (double v : background) { s.observe(0.10, t); s.observeShare(v); t += 100; }
        // A voice over that background: shares 0.15-0.35.
        double[] voice = {.15, .20, .25, .30, .35, .18, .22, .28, .32, .26};
        for (double v : voice) { s.observe(0.40, t); s.observeShare(v); t += 100; }
        EnvLearnSession.Result r = s.finish(t);
        check(r != null, "learning window must produce a result");
        check(!Double.isNaN(r.shareBar), "share bar must be measured");
        check(r.shareBar > 0.02 && r.shareBar < 0.30,
                "bar must land between the two populations, got " + r.shareBar);
        check(r.shareLow < r.shareHigh, "background must read below voice");
        System.out.printf("ShareBar: background<=%.3f bar=%.3f voice>=%.3f%n",
                r.shareLow, r.shareBar, r.shareHigh);
    }

    /** Stored bars must survive a round trip, and summaries without one must still load. */
    private static void storageRoundTripKeepsBackCompatibility() {
        List<EnvProfile.Summary> list = new ArrayList<>();
        list.add(new EnvProfile.Summary(EnvProfile.Env.BGM_LIGHT, .10, .45, 2400, 40, 1000, 1.0, .085));
        list.add(new EnvProfile.Summary(EnvProfile.Env.BGM_LIGHT, .12, .50, 2600, 42, 2000, 1.0, .090));
        String encoded = EnvProfile.encode(list);
        List<EnvProfile.Summary> decoded = EnvProfile.decode(encoded);
        check(decoded.size() == 2, "two summaries must survive the round trip");
        check(decoded.get(0).hasShareBar(), "stored bar must be present");
        double bar = EnvProfile.weightedShareBar(decoded);
        check(bar > .08 && bar < .095, "aggregated bar must be the median, got " + bar);

        // The original seven-field format must still decode, just without a bar.
        String legacy = "QUIET,0.0200,0.4000,2400,40,1000,1.0000";
        List<EnvProfile.Summary> old = EnvProfile.decode(legacy);
        check(old.size() == 1, "legacy line must still decode");
        check(!old.get(0).hasShareBar(), "legacy line has no bar");
        check(Double.isNaN(EnvProfile.weightedShareBar(old)), "no bar to aggregate");
        System.out.println("ShareBar: format round-trips and old profiles still load");
    }

    /** A seeded bar must be used, not immediately replaced by the generic floor. */
    private static void seedingHoldsTheLearnBar() {
        WaveformDetector d = new WaveformDetector(new WaveformDetector.Config(1000, 2000, 60000, 2500, 5000));
        d.seedShareBar(0.09);
        double seeded = d.shareThreshold();
        check(!Double.isNaN(seeded), "seeded bar must be set");
        check(Math.abs(seeded - 0.09) < 1e-9, "seeded bar must be exactly what was learned, got " + seeded);
        System.out.println("ShareBar: a stored bar is adopted at segment start");
    }

    public static void main(String[] args) {
        learningDerivesItsOwnBar();
        storageRoundTripKeepsBackCompatibility();
        seedingHoldsTheLearnBar();
        System.out.println("ShareBar: learning, storage and seeding all behave");
    }
}
