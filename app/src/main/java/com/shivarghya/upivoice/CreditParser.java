package com.shivarghya.upivoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a notification / SMS text is "money came IN" and extracts the amount.
 * Returns the amount in paise (₹1 = 100), or -1 if the text is not an incoming payment.
 *
 * Deliberately conservative: it is better to stay quiet than to announce a debit,
 * a payment request, an offer or a cashback as if a customer had paid.
 */
final class CreditParser {
    private CreditParser() {}

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** At least one of these must appear. */
    private static final Pattern CREDIT = Pattern.compile(
            "received|credited|credit of|deposited|paid you|sent you|added to your|money added|you got", F);

    /** Money going OUT (e.g. sender-side SMS "debited ... credited to X"). */
    private static final Pattern DEBIT = Pattern.compile(
            "\\b(debited|you paid|you sent|spent|withdrawn|withdrawal|deducted|transferred to(?! you))\\b", F);

    /** Requests, offers, failures, future credits etc. — never announce. */
    private static final Pattern IGNORE = Pattern.compile(
            "\\b(request|requested|requesting|requests|collect|otp|cashback|offer|offers|win|won|reward|rewards"
                    + "|coupon|scratch|reminder|emi|loan|failed|declined|unsuccessful|pending"
                    + "|will be credited|to be credited|expected|recharge|autopay|mandate|refer|referral"
                    + "|invite|kyc|reversal|reversed)\\b", F);

    /** "Avl Bal Rs.5,000.00" etc. — removed before looking for the amount. */
    private static final Pattern BALANCE = Pattern.compile(
            "(?:balance|bal)\\b[^0-9]{0,20}[0-9][0-9,]*(?:\\.[0-9]{1,2})?", F);

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";

    /** ₹500, Rs.500, Rs 1,200.50, INR500 */
    private static final Pattern CURRENCY_BEFORE = Pattern.compile(
            "(?:₹|(?<![a-z])rs\\.?|(?<![a-z])inr)\\s*:?\\s*" + NUM, F);

    /** 500 rupees, 500 Rs, 500/- */
    private static final Pattern CURRENCY_AFTER = Pattern.compile(
            NUM + "\\s*(?:rupees?\\b|rs\\b|inr\\b|/-)", F);

    /** SBI style: "credited by 500.0" (no currency symbol) */
    private static final Pattern CREDITED_BY = Pattern.compile(
            "credited\\s+(?:by|with|for)\\s*:?\\s*" + NUM, F);

    static long parse(String raw) {
        if (raw == null || raw.isEmpty()) return -1;
        String t = clean(raw);
        if (!CREDIT.matcher(t).find()) return -1;
        if (DEBIT.matcher(t).find()) return -1;
        if (IGNORE.matcher(t).find()) return -1;
        return amountOnly(t);
    }

    /** The money amount in the text (balance ignored), without judging credit/debit. -1 if none. */
    static long amountOnly(String raw) {
        if (raw == null) return -1;
        String t = BALANCE.matcher(clean(raw)).replaceAll(" ");
        Matcher m = CURRENCY_BEFORE.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        m = CURRENCY_AFTER.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        m = CREDITED_BY.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        m = MOVED_BY.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        return -1;
    }

    private static final Pattern MOVED_BY = Pattern.compile(
            "(?:credited|debited|deposited|received)\\s+(?:by|with|for|of)?\\s*:?\\s*" + NUM, F);
    private static final Pattern MONEY_OUT = Pattern.compile(
            "\\b(debited|debit|paid to|sent to|spent|withdrawn|withdrawal|purchase|you paid|you sent|transferred to)\\b", F);

    /** For the bank-message log: CREDIT, DEBIT, IGNORED (offer/OTP/failed…), UNKNOWN (has money, not understood), NONE. */
    static String classify(String raw) {
        if (raw == null) return "NONE";
        if (parse(raw) > 0) return "CREDIT";
        String t = clean(raw);
        if (MONEY_OUT.matcher(t).find()) return "DEBIT";
        boolean money = amountOnly(t) > 0;
        if (money && IGNORE.matcher(t).find()) return "IGNORED";
        return money ? "UNKNOWN" : "NONE";
    }

    private static String clean(String raw) {
        return raw.replace('\n', ' ').replace('\u00A0', ' ');
    }

    private static long toPaise(String s) {
        try {
            String clean = s.replace(",", "");
            long p = new BigDecimal(clean).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            // No upper limit: a long holds up to ~₹92,000 lakh crore, more money than exists.
            return p > 0 ? p : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
