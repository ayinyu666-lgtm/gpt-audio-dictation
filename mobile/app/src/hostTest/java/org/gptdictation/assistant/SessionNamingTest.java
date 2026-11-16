package org.gptdictation.assistant;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/** Host-only tests for the session naming message format. */
public final class SessionNamingTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static void formatIsStableAndParseable() {
        String m = SessionNaming.message(at(2026, 10, 31, 23, 0));
        check(m.equals("本会话命名为：2026-10-31-23:00"), "exact expected text, got " + m);
        check(SessionNaming.isNamingMessage(m), "must recognise its own message");
        check("2026-10-31-23:00".equals(SessionNaming.extractStamp(m)), "stamp extraction");
    }

    private static void suffixIsOptionalAndAppended() {
        String withSuffix = SessionNaming.message(at(2026, 10, 31, 9, 5), "课堂");
        check(withSuffix.equals("本会话命名为：2026-10-31-09:05-课堂"), "suffix appended, got " + withSuffix);
        check(SessionNaming.isNamingMessage(withSuffix), "suffix must still parse");
        String blank = SessionNaming.message(at(2026, 10, 31, 9, 5), "   ");
        check(blank.equals("本会话命名为：2026-10-31-09:05"), "blank suffix ignored");
    }

    private static void rejectsForeignText() {
        check(!SessionNaming.isNamingMessage(null), "null");
        check(!SessionNaming.isNamingMessage(""), "empty");
        check(!SessionNaming.isNamingMessage("本会话命名为："), "prefix only");
        check(!SessionNaming.isNamingMessage("本会话命名为：hello"), "no stamp");
        check(!SessionNaming.isNamingMessage("随便一句话"), "unrelated text");
    }

    private static void minutesAreZeroPadded() {
        check(SessionNaming.message(at(2026, 1, 2, 3, 4)).equals("本会话命名为：2026-01-02-03:04"),
                "single digit fields all padded");
    }

    /**
     * The message actually sent carries a recall request on the line below the stamp.
     * The stamp must stay first and the whole text must still parse as a naming message,
     * so the desktop side keeps recognising the session.
     */
    private static void recallLineKeepsTheStampFirst() {
        String m = SessionNaming.namingWithRecall(at(2026, 11, 1, 8, 46));
        check(m.equals("本会话命名为：2026-11-01-08:46\n" + SessionNaming.RECALL),
                "stamp first, recall second, got " + m);
        check(m.startsWith("本会话命名为：2026-11-01-08:46"), "stamp must lead the message");
        check(SessionNaming.isNamingMessage(m), "recall line must not break recognition");
        check("2026-11-01-08:46".equals(SessionNaming.extractStamp(m)), "stamp survives the recall line");
        check(SessionNaming.RECALL.contains("回忆"), "recall must ask for a recall");
        String withSuffix = SessionNaming.namingWithRecall(at(2026, 11, 1, 9, 5), "课堂");
        check("2026-11-01-09:05".equals(SessionNaming.extractStamp(withSuffix)), "suffix + recall still parse");
    }

    public static void main(String[] args) {
        formatIsStableAndParseable();
        recallLineKeepsTheStampFirst();
        suffixIsOptionalAndAppended();
        rejectsForeignText();
        minutesAreZeroPadded();
        System.out.println("SessionNaming: format, suffix, padding, recall line and rejection rules passed");
    }
}
