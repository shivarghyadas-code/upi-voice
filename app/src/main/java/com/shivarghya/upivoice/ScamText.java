package com.shivarghya.upivoice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the common Indian cyber-scam messages in SMS / WhatsApp / Telegram text.
 * Pure Java, offline, unit-tested. Returns null when the message looks harmless.
 */
final class ScamText {
    private ScamText() {}

    enum From { BANK_SENDER, PHONE_SMS, CHAT }
    enum Level { WARN, DANGER }

    static final class Result {
        final Level level;
        final String reason;
        Result(Level l, String r) { level = l; reason = r; }
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static Pattern p(String s) { return Pattern.compile(s, F); }

    private static final Pattern APK = p("\\.apk\\b");
    private static final Pattern FORWARD = p("\\*401\\*|\\*\\*21\\*|\\*21\\*|\\*\\*6[127]\\*");
    private static final Pattern ARREST = p("digital arrest|arrest warrant|money laundering|narcotics"
            + "|drugs?\\s+(?:found|in your parcel)|cbi officer|police (?:case|complaint) (?:against|registered)"
            + "|parcel (?:seized|held|detained)|customs (?:duty|clearance|officer)|fedex");
    private static final Pattern MISTAKE = p("(?:by mistake|wrongly|accidentally|galti se|ভুল করে)[^.]{0,50}"
            + "(?:sent|credited|transferred|paid|bhej|পাঠ)");
    private static final Pattern ELECTRIC = p("(?:electricity|bijli|electric|power)[^.]{0,60}"
            + "(?:disconnect|cut|cutoff|will be off|tonight)");
    private static final Pattern INVEST = p("(?:task|like|subscribe|youtube|telegram|review|rating)[^.]{0,50}"
            + "(?:earn|income|profit|salary|commission)|daily profit|guaranteed (?:return|profit)"
            + "|double (?:your|the) money|crypto (?:profit|trading)|stock tips? (?:group|vip)|work from home[^.]{0,40}(?:earn|daily)");
    private static final Pattern URL = p("(?:https?://|www\\.|\\b(?:bit\\.ly|tinyurl\\.com|cutt\\.ly|rb\\.gy|is\\.gd|shorturl\\.at|t\\.ly)/"
            + "|\\b[a-z0-9-]+\\.(?:xyz|top|site|online|click|link|live|shop|icu|buzz|cfd|sbs|cyou|rest|monster|info)\\b)");
    private static final Pattern BAIT = p("\\b(kyc|pan(?: card)? (?:update|link)|aadhaa?r (?:update|link)|blocked|suspended"
            + "|freeze|frozen|expired?|reward points?|redeem|refund|challan|parcel|courier|lottery|prize|winner"
            + "|lucky draw|job|part[- ]time|loan approved|pre-approved|verify|update now|click here|claim|electricity bill)\\b");
    private static final Pattern OTP_ASK = p("\\botp\\b[^.]{0,40}\\b(?:share|send|tell|forward|batao|bhejo)\\b"
            + "|\\b(?:share|send|tell|forward|batao|bhejo)\\b[^.]{0,30}\\botp\\b");
    private static final Pattern OTP_SAFE = p("(?:do not|don't|never|not to|mat)\\s+(?:share|disclose|tell)");
    private static final Pattern CREDIT = p("credited|received|deposited|has been added");
    private static final Pattern MONEY = p("₹|\\brs\\.?\\s*\\d|\\binr\\s*\\d");

    static Result check(String text, From from) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = text.replace('\n', ' ');
        boolean stranger = from != From.BANK_SENDER;     // bank headers are DLT-registered; numbers/chats are not

        if (APK.matcher(t).find())
            return new Result(Level.DANGER, "Contains an APP FILE (.apk). Installing it lets scammers read your "
                    + "OTPs and empty your bank account. Do not open or install it.");
        if (FORWARD.matcher(t).find())
            return new Result(Level.DANGER, "Asks you to dial a CALL-FORWARDING code (*401*, **21*). Scammers use it "
                    + "to receive your bank OTP calls. Never dial it.");
        if (from == From.PHONE_SMS && CREDIT.matcher(t).find() && MONEY.matcher(t).find())
            return new Result(Level.DANGER, "FAKE CREDIT SMS: real bank messages never come from a 10-digit phone "
                    + "number. No money has come. Check your bank SMS or UPI Voice.");
        if (MISTAKE.matcher(t).find())
            return new Result(Level.DANGER, "'Sent by mistake, please return' is a common scam. Check your real bank "
                    + "balance first. Never send money back through a link or QR they give.");
        Matcher m = ARREST.matcher(t);
        if (m.find())
            return new Result(stranger ? Level.DANGER : Level.WARN, "Mentions \"" + m.group() + "\". Police, CBI or "
                    + "customs NEVER arrest anyone on a video call or ask for money. Hang up and call 1930.");
        if (stranger) {
            Matcher o = OTP_ASK.matcher(t);
            if (o.find() && !OTP_SAFE.matcher(t).find())
                return new Result(Level.DANGER, "Someone is asking for your OTP. Never share an OTP with anyone, "
                        + "not even someone saying they are from the bank.");
        }
        if (ELECTRIC.matcher(t).find() && (stranger || URL.matcher(t).find()))
            return new Result(stranger ? Level.DANGER : Level.WARN, "Electricity-disconnection threat. Electricity "
                    + "boards don't send personal numbers to call. Pay only in the official app.");
        if (INVEST.matcher(t).find())
            return new Result(stranger ? Level.DANGER : Level.WARN, "Easy-money / task / investment offer. These "
                    + "always end with you paying and losing money.");
        Matcher u = URL.matcher(t);
        Matcher b = BAIT.matcher(t);
        if (u.find() && b.find())
            return new Result(stranger ? Level.DANGER : Level.WARN, "Link with \"" + b.group(1) + "\". Banks, PAN, "
                    + "Aadhaar, courier and traffic police never ask you to update or pay through an SMS link. Don't open it.");
        return null;
    }
}
