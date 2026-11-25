package com.shivarghya.upivoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the rows of a UPI app's history screen (as text, read by the accessibility service)
 * into "money received" entries. Pure Java, so it can be tested off the phone.
 *
 * Works on the words apps actually show: "Received from", "Credited to", "+₹500", bank
 * statement "CR" / "UPI/CR/…" — and ignores anything paid, sent, debited, failed or pending.
 */
final class ScreenParser {
    private ScreenParser() {}

    static final class Found {
        long time;          // ms; if dayOnly, noon of that day
        boolean dayOnly;    // the screen showed a date but no time
        long paise;
        String key;
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CREDIT = Pattern.compile(
            "received|credited|\\bcr\\b|/cr/|\\bcredit\\b|\\bdeposit|\\+\\s*(?:₹|rs\\.?|inr)|paid you|sent you|refund", F);
    private static final Pattern DEBIT = Pattern.compile(
            "paid to|sent to|debited|\\bdr\\b|/dr/|\\bdebit\\b|\\bwithdraw|transferred to|you paid|you sent"
                    + "|(?:^|[\\s|])[-−–]\\s*(?:₹|rs\\.?|inr)|\\bpaid\\b(?!\\s+you)|\\bsent\\b(?!\\s+you)", F);
    private static final Pattern IGNORE = Pattern.compile(
            "\\b(failed|pending|declined|cancel+ed|request|requested|expired|processing|reversed|cashback|reward"
                    + "|offer|scratch|will be credited|in progress)\\b", F);
    private static final Pattern BALANCE = Pattern.compile(
            "(?:balance|bal)\\b[^0-9]{0,20}[0-9][0-9,]*(?:\\.[0-9]{1,2})?", F);

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";
    private static final Pattern AMT_CUR = Pattern.compile("(?:₹|(?<![a-z])rs\\.?|(?<![a-z])inr)\\s*:?\\s*" + NUM, F);
    private static final Pattern AMT_CR = Pattern.compile(NUM + "\\s*\\(?cr\\)?\\b", F);

    private static final String MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern D_TEXT = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*" + MON + "[\\s\\-/.,']*(\\d{4}|\\d{2}(?![:\\d]))?", F);
    private static final Pattern D_TEXT_US = Pattern.compile("\\b" + MON + "\\s+(\\d{1,2})(?:,?\\s*(\\d{4}))?", F);
    private static final Pattern D_NUM = Pattern.compile("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4}|\\d{2})\\b");
    private static final Pattern D_TODAY = Pattern.compile("\\btoday\\b", F);
    private static final Pattern D_YDAY = Pattern.compile("\\byesterday\\b", F);
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*(am|pm)?\\b", F);

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /**
     * @param rows  one string per list row (texts inside the row joined with " | ")
     * @param pkg   the app the rows came from
     * @param now   current time (ms)
     */
    static List<Found> parse(List<String> rows, String pkg, long now) {
        List<Found> out = new ArrayList<>();
        Calendar header = null;           // date from a section header like "25 Nov 2026"
        for (String raw : rows) {
            if (raw == null) continue;
            String row = raw.replace('\n', ' ').trim();
            if (row.isEmpty()) continue;

            Calendar date = date(row, now);
            long paise = amount(row);
            if (paise <= 0) {                     // no amount: maybe a date header
                if (date != null) header = date;
                continue;
            }
            if (!CREDIT.matcher(row).find() || DEBIT.matcher(row).find() || IGNORE.matcher(row).find()) continue;
            if (date == null && header != null) date = (Calendar) header.clone();
            if (date == null) continue;           // can't place it in time → skip

            Found f = new Found();
            f.paise = paise;
            Matcher t = TIME.matcher(row);
            if (t.find()) {
                int h = Integer.parseInt(t.group(1)) % 24, m = Integer.parseInt(t.group(2));
                String ap = t.group(3);
                if (ap != null) {
                    h = h % 12;
                    if (ap.equalsIgnoreCase("pm")) h += 12;
                }
                date.set(Calendar.HOUR_OF_DAY, h);
                date.set(Calendar.MINUTE, m);
                f.dayOnly = false;
            } else {
                date.set(Calendar.HOUR_OF_DAY, 12);
                date.set(Calendar.MINUTE, 0);
                f.dayOnly = true;
            }
            date.set(Calendar.SECOND, 0);
            date.set(Calendar.MILLISECOND, 0);
            f.time = date.getTimeInMillis();
            String norm = row.toLowerCase(Locale.ENGLISH).replaceAll("\\s+", " ");
            f.key = "acc:" + (f.dayOnly ? "d:" : "t:") + pkg + ":" + Integer.toHexString(norm.hashCode()) + ":" + paise;
            out.add(f);
        }
        return out;
    }

    static long amount(String row) {
        String t = BALANCE.matcher(row).replaceAll(" ");
        Matcher m = AMT_CUR.matcher(t);
        if (m.find()) return paise(m.group(1));
        m = AMT_CR.matcher(t);
        if (m.find()) return paise(m.group(1));
        return -1;
    }

    private static long paise(String s) {
        try {
            long p = new BigDecimal(s.replace(",", "")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return p > 0 ? p : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Date shown in the row, or null. Missing year = this year (last year if that would be in the future). */
    static Calendar date(String row, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (D_TODAY.matcher(row).find()) return midnight(c);
        if (D_YDAY.matcher(row).find()) { c.add(Calendar.DAY_OF_MONTH, -1); return midnight(c); }

        Matcher m = D_TEXT.matcher(row);
        if (m.find()) return build(now, Integer.parseInt(m.group(1)), month(m.group(2)), m.group(3));
        m = D_TEXT_US.matcher(row);
        if (m.find()) return build(now, Integer.parseInt(m.group(2)), month(m.group(1)), m.group(3));
        m = D_NUM.matcher(row);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(2)) - 1;
            if (mo >= 0 && mo < 12) return build(now, Integer.parseInt(m.group(1)), mo, m.group(3));
        }
        return null;
    }

    private static int month(String s) {
        String k = s.substring(0, 3).toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(k)) return i;
        return -1;
    }

    private static Calendar build(long now, int day, int month, String year) {
        if (month < 0 || day < 1 || day > 31) return null;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int y = c.get(Calendar.YEAR);
        boolean explicit = year != null && !year.isEmpty();
        if (explicit) {
            y = Integer.parseInt(year);
            if (y < 100) y += 2000;
        }
        c.set(y, month, day, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (!explicit && c.getTimeInMillis() > now + 86_400_000L) c.add(Calendar.YEAR, -1);
        return c;
    }

    private static Calendar midnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
