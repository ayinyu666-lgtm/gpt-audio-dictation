package org.gptdictation.assistant;

public final class RecordingFlowPolicyTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        check(!RecordingFlowPolicy.actionReady(false, true, true), "disabled waits");
        check(!RecordingFlowPolicy.actionReady(true, false, true), "invisible waits");
        check(!RecordingFlowPolicy.actionReady(true, true, false), "empty bounds wait");
        check(RecordingFlowPolicy.actionReady(true, true, true), "ready can attempt once");
        check(RecordingFlowPolicy.visualPauseFeasible(60000, 1000, 2400), "60s learns references and waits a pause");
        check(!RecordingFlowPolicy.visualPauseFeasible(10000, 1000, 2400), "10s cannot learn then wait");
        check(!RecordingFlowPolicy.visualPauseFeasible(15000, 1000, 9000), "long pause needs a longer cap");
        check(!RecordingFlowPolicy.visualPauseFeasible(60000, 0, 2400), "missing cadence is not feasible");
        check(!RecordingFlowPolicy.visualPauseFeasible(0, 1000, 2400), "missing maximum is not feasible");
        System.out.println("RecordingFlowPolicy: button readiness and pause feasibility passed");
    }
}
