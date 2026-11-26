package com.shivarghya.upivoice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a payment for common Indian UPI scam patterns BEFORE it is handed to the UPI app.
 * Pure Java (phone facts are passed in), so the rules are unit-tested.
 *
 *   BLOCK  – payment is stopped, no way to continue
 *   DANGER – must tick "I'm sure" and wait 5 seconds
 *   WARN   – shown, can continue
 *   INFO   – tip
 */
final class ScamCheck {
    private ScamCheck() {}

    enum Level { INFO, WARN, DANGER, BLOCK }

    static final class Warning {
        final Level level;
        final String text;
        Warning(Level level, String text) { this.level = level; this.text = text; }
    }

    /** Facts about the phone right now. */
    static final class Phone {
        boolean inCall;
        List<String> remoteApps = new ArrayList<>();     // AnyDesk, TeamViewer… installed
        List<String> screenReaders = new ArrayList<>();  // other apps with Accessibility (screen reading) on
        List<String> riskyApps = new ArrayList<>();      // sideloaded apps that can read SMS/OTPs or the screen
        boolean newPayee;                                // never paid this UPI ID before
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Words scammers put in QR names/notes ("scan to receive refund", "KYC update"…). */
    private static final Pattern BAIT = Pattern.compile(
            "\\b(receive|receiving|refund|prize|lottery|winner|cashback|reward|kyc|verify|verification"
                    + "|claim|bonus|customs|disconnect|disconnection|army|olx"
                    + "|part[- ]?time|investment|loan approved|registration fee|unblock|blocked"
                    + "|penalty|police|cbi|income tax|crypto|deposit back|security deposit|digital arrest)\\b", F);

    private static final Set<String> HANDLES = new HashSet<>(Arrays.asList(
            "upi", "ybl", "ibl", "axl", "paytm", "ptyes", "ptaxis", "pthdfc", "ptsbi", "okaxis", "oksbi", "okhdfcbank",
            "okicici", "apl", "yapl", "rapl", "abfspay", "axisbank", "axisb", "icici", "hdfcbank", "hdfcbankjd", "sbi",
            "pnb", "kotak", "kbl", "federal", "fbl", "idfcbank", "idfcfirst", "indus", "airtel", "mairtel", "jio",
            "freecharge", "ikwik", "boi", "barodampay", "bob", "cnrb", "canara", "unionbank", "uboi", "unionbankofindia",
            "mahb", "centralbank", "cboi", "indianbank", "indbank", "iob", "pockets", "waicici", "wahdfcbank", "waaxis",
            "wasbi", "timecosmos", "naviaxis", "superyes", "yesbank", "yesbankltd", "yesg", "rbl", "dbs", "hsbc", "sc",
            "citi", "citigold", "aubank", "equitas", "jupiteraxis", "fam", "slc", "postbank", "dlb", "kvb", "cub",
            "tjsb", "utbi", "ezeepay", "eazypay", "bandhan", "dcb", "csbpay", "pingpay", "purz", "mbk", "nsdl",
            "ratn", "idbi", "psb", "ucobank", "uco", "sib", "jkb", "kaypay", "kmbl", "myicici", "icicipay", "goaxb",
            "aubankltd", "niyoicici", "kmb", "ubi", "allbank", "andb", "vijb", "syndicate", "obc", "united", "sliceaxis",
            "pz", "cred", "axisbankltd", "pinelabs", "payzapp", "hdfc", "zoicici", "amazon", "amazonpay"));

    private static final long LARGE = 10_000_00L;    // ₹10,000
    private static final long BIG = 2_000_00L;       // ₹2,000

    static List<Warning> check(UpiRequest r, long paise, Phone ph) {
        List<Warning> w = new ArrayList<>();

        // ---- BLOCK
        if (!r.validPayee()) {
            w.add(new Warning(Level.BLOCK, "This QR has no valid UPI ID. It is not a real payment QR."));
        }
        String cu = r.get("cu");
        if (!cu.isEmpty() && !cu.equalsIgnoreCase("INR")) {
            w.add(new Warning(Level.BLOCK, "This QR asks for payment in \"" + cu + "\", not Indian Rupees."));
        }

        for (String app : ph.riskyApps) {
            w.add(new Warning(Level.BLOCK, "\"" + app + "\" was installed from outside the Play Store and can read "
                    + "your OTPs or screen — this is how scammers empty bank accounts. Open UPI Voice → Safety check "
                    + "to uninstall it (or mark it trusted if you installed it yourself)."));
        }

        // ---- DANGER
        String text = (r.name() + " " + r.note() + " " + r.get("purpose")).trim();
        Matcher m = BAIT.matcher(text);
        if (m.find()) {
            w.add(new Warning(Level.DANGER, "The QR says \"" + m.group(1).toLowerCase(Locale.ENGLISH)
                    + "\". Scanning a QR or entering your UPI PIN ALWAYS SENDS money out of your account. "
                    + "Nobody needs your PIN or a QR scan to send you money, a refund or a prize."));
        }
        if (ph.inCall) {
            w.add(new Warning(Level.DANGER, "You are on a phone call. Scammers stay on the call and tell victims "
                    + "what to scan and pay. If a caller asked you to pay, hang up first."));
        }
        for (String app : ph.remoteApps) {
            w.add(new Warning(Level.DANGER, app + " is installed on this phone. It lets someone else see and control "
                    + "your screen. Never open it because a caller asked; uninstall it if you don't need it."));
        }
        for (String app : ph.screenReaders) {
            w.add(new Warning(Level.DANGER, "\"" + app + "\" can read your screen (Accessibility is ON for it). "
                    + "If you didn't turn this on yourself, it may be stealing your details. Check Settings → Accessibility."));
        }
        if (paise >= LARGE && ph.newPayee) {
            w.add(new Warning(Level.DANGER, "Large payment (" + Speech.formatRupees(paise) + ") to someone you have never "
                    + "paid before. Confirm with the person face-to-face or on a number you already know."));
        }

        // ---- WARN
        if (ph.newPayee && paise < LARGE) {
            w.add(new Warning(Level.WARN, "First payment to this UPI ID."));
        }
        String pa = r.payee();
        int at = pa.lastIndexOf('@');
        if (at > 0 && !HANDLES.contains(pa.substring(at + 1).toLowerCase(Locale.ENGLISH))) {
            w.add(new Warning(Level.WARN, "Unusual UPI handle \"@" + pa.substring(at + 1) + "\". Double-check the UPI ID."));
        }
        String url = r.get("url");
        if (!url.isEmpty() && !url.toLowerCase(Locale.ENGLISH).startsWith("https://")) {
            w.add(new Warning(Level.WARN, "The QR contains a web link: " + url + ". Don't open links from payment QRs."));
        }
        if (paise >= BIG && paise < LARGE) {
            w.add(new Warning(Level.WARN, "Amount is " + Speech.formatRupees(paise) + ". Check the digits carefully."));
        }

        // ---- INFO
        if (!r.name().isEmpty()) {
            w.add(new Warning(Level.INFO, "The name \"" + r.name() + "\" is written inside the QR by whoever made it. "
                    + "Your UPI app will show the bank-verified name; make sure it matches before entering the PIN."));
        }
        return w;
    }

    /** For a scanned text that is NOT a UPI payment link. Returns a BLOCK/DANGER warning, or null if harmless text. */
    static Warning notUpi(String text) {
        String t = text == null ? "" : text.trim();
        String l = t.toLowerCase(Locale.ENGLISH);
        if (l.startsWith("upi://mandate") || l.contains("mandate")) {
            return new Warning(Level.DANGER, "This is a UPI AutoPay MANDATE QR. Approving it lets someone take money "
                    + "from your account again and again. Only continue if YOU started a subscription.");
        }
        if (l.startsWith("upi://collect")) {
            return new Warning(Level.BLOCK, "This QR is a COLLECT request — it asks you to approve money going OUT. Blocked.");
        }
        if (l.startsWith("http://") || l.startsWith("https://") || l.startsWith("www.")) {
            boolean apk = l.contains(".apk");
            return new Warning(Level.BLOCK, (apk ? "This QR downloads an APP (.apk). " : "This QR opens a WEBSITE, not a UPI payment. ")
                    + "Scammers use such QRs to steal UPI PINs, bank logins or install spy apps. Blocked.\n\n" + t);
        }
        if (l.startsWith("tel:") || l.startsWith("sms") || l.startsWith("intent:") || l.startsWith("market:")) {
            return new Warning(Level.BLOCK, "This QR is not a payment QR (" + t.split(":")[0] + "). Blocked.");
        }
        return null;
    }

    static Level worst(List<Warning> w) {
        Level l = Level.INFO;
        for (Warning x : w) if (x.level.ordinal() > l.ordinal()) l = x.level;
        return l;
    }
}
