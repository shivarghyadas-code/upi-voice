package com.shivarghya.upivoice;

import java.util.Locale;

/**
 * Turns an amount into the sentence that is spoken. Works for any amount from ₹0.01 upward.
 *
 * Large numbers are split the Indian way (crore / lakh / thousand / hundred) by us, and only
 * the small parts (0–999) are left as digits for the voice to read. That way every voice reads
 * "1 করোড় 25 লাখ" correctly instead of "twelve million five hundred thousand",
 * and crore counts themselves can be any size (it is applied recursively).
 *
 * The amount comes FIRST so the listener hears the important part immediately.
 */
final class Speech {
    private Speech() {}

    static String phrase(String lang, long paise) {
        long r = paise / 100, p = paise % 100;
        String amount = words(r, lang);
        switch (lang) {
            case "hi":
                return amount + " रुपये" + (p > 0 ? " " + p + " पैसे" : "") + " प्राप्त हुए";
            case "bn":
                return amount + " টাকা" + (p > 0 ? " " + p + " পয়সা" : "") + " পাওয়া গেছে";
            default:
                return amount + (r == 1 && p == 0 ? " rupee" : " rupees")
                        + (p > 0 ? " " + p + " paise" : "") + " received";
        }
    }

    /** Indian-system words for n >= 0, e.g. 12345678 → "1 crore 23 lakh 45 thousand 678". */
    static String words(long n, String lang) {
        if (n < 1000) return Long.toString(n);    // every voice reads 0-999 naturally
        String crore, lakh, thousand;
        switch (lang) {
            case "hi": crore = "करोड़"; lakh = "लाख"; thousand = "हज़ार"; break;
            case "bn": crore = "কোটি"; lakh = "লাখ"; thousand = "হাজার"; break;
            default:   crore = "crore"; lakh = "lakh"; thousand = "thousand";
        }
        long c = n / 10_000_000L;  n %= 10_000_000L;
        long l = n / 100_000L;     n %= 100_000L;
        long t = n / 1_000L;
        long rest = n % 1_000L;

        StringBuilder sb = new StringBuilder();
        if (c > 0) append(sb, words(c, lang) + " " + crore);   // recursive: no upper limit
        if (l > 0) append(sb, l + " " + lakh);
        if (t > 0) append(sb, t + " " + thousand);
        if (rest > 0) append(sb, Long.toString(rest));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(s);
    }

    static String formatRupees(long paise) {
        long r = paise / 100, p = paise % 100;
        // Indian digit grouping: 1,23,45,678
        String s = Long.toString(r);
        StringBuilder g = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            g.append(s.charAt(i));
            int left = len - 1 - i;
            if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) g.append(',');
        }
        return "₹" + g + (p > 0 ? String.format(Locale.ENGLISH, ".%02d", p) : "");
    }
}
