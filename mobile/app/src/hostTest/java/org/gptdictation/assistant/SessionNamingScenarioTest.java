package org.gptdictation.assistant;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/** Host scenarios for new-chat initialization, resume, missing trees and navigation. */
public final class SessionNamingScenarioTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Gate {
        final SessionNamingPolicy.Gate productionGate = new SessionNamingPolicy.Gate();
        int names;

        SessionNamingPolicy.Action observe(int rows, boolean newPlaceholder, boolean tabs,
                                           boolean existingPlaceholder, boolean recording,
                                           boolean voice) {
            SessionNamingPolicy.Action action = productionGate.observe(rows, newPlaceholder,
                    tabs, existingPlaceholder, true, voice, recording);
            if (action == SessionNamingPolicy.Action.NAME_NOW) {
                names++;
                productionGate.markNamingInvoked();
            }
            return action;
        }
    }

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static void freshChatIsNamedBeforeRecording() {
        Gate g = new Gate();
        check(g.observe(2, false, true, false, false, true)
                        == SessionNamingPolicy.Action.NAME_NOW,
                "new chat must initialize before recording");
        check(g.observe(2, false, true, false, false, true)
                        == SessionNamingPolicy.Action.CONTINUE,
                "the same new chat is not named a second time");
        check(g.names == 1, "exactly one naming action for a new chat");
    }

    private static void existingChatAndRecoveryDoNotGetRenamed() {
        Gate g = new Gate();
        check(g.observe(0, false, true, true, false, true)
                        == SessionNamingPolicy.Action.CONTINUE,
                "an established conversation resumes directly");
        check(g.names == 0, "existing session receives no initializer");

        // A new service run has no in-memory latch. Positive existing-chat evidence must
        // still allow recovery without inserting a duplicate first message.
        Gate recovered = new Gate();
        check(recovered.observe(0, false, true, true, false, true)
                        == SessionNamingPolicy.Action.CONTINUE,
                "service recovery in an existing session does not rename it");
        check(recovered.names == 0, "recovery did not invoke naming");
    }

    private static void emptyAndNavigatingTreesWait() {
        Gate g = new Gate();
        check(g.observe(0, false, false, false, false, true)
                        == SessionNamingPolicy.Action.WAIT,
                "empty tree is not proof the chat has already been named");
        check(g.observe(1, false, true, false, false, true)
                        == SessionNamingPolicy.Action.WAIT,
                "partial labels during a route transition cannot start recording");
        check(g.names == 0, "unknown states never issue a naming action");
    }

    private static void navigationResolvesPerObservedChat() {
        Gate g = new Gate();
        check(g.observe(2, false, true, false, false, true)
                        == SessionNamingPolicy.Action.NAME_NOW,
                "first blank chat is initialized");
        check(g.observe(0, false, true, true, false, true)
                        == SessionNamingPolicy.Action.CONTINUE,
                "navigation to an existing chat resolves it without naming");
        check(g.observe(2, false, true, false, false, true)
                        == SessionNamingPolicy.Action.NAME_NOW,
                "a separately observed new chat is initialized in the same run");
        check(g.names == 2, "each positively observed new chat is initialized once");
    }

    private static void generatedTextMatchesContract() {
        String m = SessionNaming.message(at(2026, 10, 31, 23, 0), null);
        check(m.equals("本会话命名为：2026-10-31-23:00"), "exact expected text, got " + m);
        check(SessionNaming.isNamingMessage(m), "round-trips through the parser");
        check("2026-10-31-23:00".equals(SessionNaming.extractStamp(m)), "stamp extraction");
        check(SessionNaming.message(at(2026, 10, 31, 9, 5), "课堂")
                .equals("本会话命名为：2026-10-31-09:05-课堂"), "suffix appended");
    }

    public static void main(String[] args) {
        freshChatIsNamedBeforeRecording();
        existingChatAndRecoveryDoNotGetRenamed();
        emptyAndNavigatingTreesWait();
        navigationResolvesPerObservedChat();
        generatedTextMatchesContract();
        System.out.println("SessionNamingScenario: fresh/resume/unknown/navigation/recovery passed");
    }
}
