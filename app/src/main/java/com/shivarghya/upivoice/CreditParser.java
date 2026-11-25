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
        String t = raw.replace('\n', ' ').replace(' ', ' ');
        if (!CREDIT.matcher(t).find()) return -1;
        if (DEBIT.matcher(t).find()) return -1;
        if (IGNORE.matcher(t).find()) return -1;

        t = BALANCE.matcher(t).replaceAll(" ");

        Matcher m = CURRENCY_BEFORE.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        m = CURRENCY_AFTER.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        m = CREDITED_BY.matcher(t);
        if (m.find()) return toPaise(m.group(1));
        return -1;
    }

    private static long toPaise(String s) {
        try {
            String clean = s.replace(",", "");
            long p = new BigDecimal(clean).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            // Sanity range: ₹0.01 .. ₹10 crore
            return (p > 0 && p <= 10_000_000_000L) ? p : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
