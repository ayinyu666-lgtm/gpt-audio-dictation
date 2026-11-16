package org.gptdictation.assistant;

public final class CaptureLeaseTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        CaptureLease lease = new CaptureLease();
        long a = lease.begin(); check(a != 0 && lease.busy(), "first request");
        check(lease.begin() == 0, "no concurrent screenshot");
        lease.abandon(); check(lease.begin() == 0, "timed-out physical request remains occupied");
        check(!lease.finish(a) && !lease.busy(), "late abandoned result discarded and released");
        long b = lease.begin(); check(b != 0 && b != a, "new request after release");
        check(!lease.finish(a) && lease.busy(), "stale callback cannot clear new request");
        check(lease.finish(b) && !lease.busy(), "current result accepted");
        check(!lease.finish(b), "duplicate callback ignored");
        System.out.println("CaptureLease: timeout, late result and request isolation passed");
    }
}
