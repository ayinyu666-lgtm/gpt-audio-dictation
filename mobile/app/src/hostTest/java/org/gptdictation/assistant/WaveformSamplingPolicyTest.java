package org.gptdictation.assistant;

import java.util.Arrays;

/** Host-only checks: javac the policy and this file, then run main. */
public final class WaveformSamplingPolicyTest {
    private static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    public static void main(String[] args) {
        // Real device geometry (Honor Magic4, 1122x2442): cancel [60,2287][155,2382],
        // stop [830,2299][902,2371]. The crop spans the whole waveform strip between
        // the buttons with a small margin, normalised by the send button's 72 px height.
        int[] cancel = {60, 2287, 155, 2382}, stop = {830, 2299, 902, 2371};
        check(Arrays.equals(WaveformSamplingPolicy.crop(cancel, stop, 1122, 2442),
                new int[]{158, 2284, 827, 2385}), "measured geometry");
        check(WaveformSamplingPolicy.crop(cancel, new int[]{160,2299,232,2371},1122,2442) == null, "narrow gap");
        check(WaveformSamplingPolicy.crop(cancel, new int[]{830,2100,902,2172},1122,2442) == null, "vertical mismatch");
        check(WaveformSamplingPolicy.crop(cancel, new int[]{830,2299,1200,2371},1122,2442) == null, "off screen");
        check(WaveformSamplingPolicy.crop(null, stop,1122,2442) == null, "missing node");
        check(WaveformSamplingPolicy.fresh(2500,1000), "fresh boundary");
        check(!WaveformSamplingPolicy.fresh(2501,1000), "stale result");
        check(!WaveformSamplingPolicy.fresh(999,1000), "future timestamp");
        check(!WaveformSamplingPolicy.fresh(1000,0), "missing sample");
        check(WaveformSamplingPolicy.trigger(true, false, false) == WaveformSamplingPolicy.Trigger.TIMED_FALLBACK, "maximum with invalid frame");
        check(WaveformSamplingPolicy.trigger(true, true, true) == WaveformSamplingPolicy.Trigger.TIMED_FALLBACK, "maximum has priority");
        check(WaveformSamplingPolicy.trigger(false, true, true) == WaveformSamplingPolicy.Trigger.VISUAL_PAUSE, "fresh visual pause");
        check(WaveformSamplingPolicy.trigger(false, false, true) == WaveformSamplingPolicy.Trigger.WAIT, "stale pause cannot send");
        check(WaveformSamplingPolicy.trigger(false, false, false) == WaveformSamplingPolicy.Trigger.WAIT, "invalid frame before maximum");
        check(WaveformSamplingPolicy.callbackCurrent(1,1,2,2,3,3,true,false), "current callback");
        check(!WaveformSamplingPolicy.callbackCurrent(1,2,2,2,3,3,true,false), "old run");
        check(!WaveformSamplingPolicy.callbackCurrent(1,1,2,3,3,3,true,false), "old segment");
        check(!WaveformSamplingPolicy.callbackCurrent(1,1,2,2,3,4,true,false), "changed window");
        check(!WaveformSamplingPolicy.callbackCurrent(1,1,2,2,3,3,false,false), "stopped");
        check(!WaveformSamplingPolicy.callbackCurrent(1,1,2,2,3,3,true,true), "timed out");
        // Snapshot identity for the screenshot verification.
        // The bug this covers: the tick that issues the request also stores its own scan with
        // the same serial/timestamp, so requiring only "not older" accepted a tree that was
        // read BEFORE the picture was taken. The tree must be strictly newer.
        check(!WaveformSamplingPolicy.snapshotNewerThanRequest(5, 5),
                "the snapshot taken by the requesting tick must not verify its own screenshot");
        check(WaveformSamplingPolicy.snapshotNewerThanRequest(6, 5),
                "a snapshot taken on a later tick may verify the screenshot");
        check(!WaveformSamplingPolicy.snapshotNewerThanRequest(-1, 5), "no snapshot must trigger a fresh scan");
        check(!WaveformSamplingPolicy.snapshotNewerThanRequest(0, 0), "initial serials are not newer");
        check(WaveformSamplingPolicy.snapshotNewerThanRequest(1, 0), "first scan after a request is newer");
        System.out.println("WaveformSamplingPolicy: geometry, freshness, callbacks, fallback and snapshot identity passed");
    }
}
