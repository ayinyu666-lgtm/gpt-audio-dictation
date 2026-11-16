package org.gptdictation.assistant;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds the conversation-naming message that lets the desktop side recognise a
 * session for archiving.
 *
 * The format is deliberately machine-readable and stable:
 *
 *     本会话命名为：2026-10-31-23:00
 *
 * A time zone and an optional suffix can be appended later without breaking older
 * parsers, as long as the prefix "本会话命名为：" and the YYYY-MM-DD-HH:mm stamp stay
 * at the front.
 *
 * Pure logic so the format can be tested without Android.
 */
public final class SessionNaming {
    public static final String PREFIX = "本会话命名为：";
    /** Pattern the desktop side should match: prefix + YYYY-MM-DD-HH:mm. */
    public static final String STAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}-\\d{2}:\\d{2}";
    /**
     * Appended on the line below the stamp so ChatGPT recalls this account's standing
     * instructions for meetings, classes and translation. It is a request to recall, not
     * content we provide: the memory lives in the account, not in this app.
     *
     * The stamp stays on the first line, so a desktop parser that looks at the start of
     * the message keeps working unchanged.
     */
    public static final String RECALL = "请回忆关于会议、课堂和翻译的相关要求。";

    private SessionNaming() { }

    /** The naming text for a given moment, e.g. "本会话命名为：2026-10-31-23:00". */
    public static String message(long epochMillis, String suffix) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH:mm", Locale.US).format(new Date(epochMillis));
        String clean = suffix == null ? "" : suffix.trim();
        return PREFIX + stamp + (clean.isEmpty() ? "" : "-" + clean);
    }

    public static String message(long epochMillis) {
        return message(epochMillis, null);
    }

    /**
     * The naming message plus the recall request on its own line.
     * Used for the message that is actually sent; {@link #message} stays clean so the
     * naming format itself remains exactly as specified and tested.
     */
    public static String namingWithRecall(long epochMillis, String suffix) {
        return message(epochMillis, suffix) + "\n" + RECALL;
    }

    public static String namingWithRecall(long epochMillis) {
        return namingWithRecall(epochMillis, null);
    }

    /** True when the text looks like one of our naming messages. */
    public static boolean isNamingMessage(String text) {
        if (text == null) return false;
        String trimmed = firstLine(text);
        if (!trimmed.startsWith(PREFIX)) return false;
        String rest = trimmed.substring(PREFIX.length());
        return rest.matches(STAMP_PATTERN + "(?:-.+)?");
    }

    /** The stamp line only: anything after the first newline is not part of the name. */
    private static String firstLine(String text) {
        String trimmed = text.trim();
        int cut = trimmed.indexOf('\n');
        return cut < 0 ? trimmed : trimmed.substring(0, cut).trim();
    }

    /**
     * Extracts the timestamp part, or null when the text is not a naming message.
     * Returns "YYYY-MM-DD-HH:mm".
     */
    public static String extractStamp(String text) {
        if (!isNamingMessage(text)) return null;
        String rest = firstLine(text).substring(PREFIX.length());
        return rest.substring(0, 16);
    }
}
