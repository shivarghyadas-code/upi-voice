package com.shivarghya.upivoice;

import android.app.Notification;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/** Which apps we listen to, and how to read their notification text. */
final class Sources {
    private Sources() {}

    static final String SMS = "sms";

    private static final Map<String, String> PAYMENT_APPS = new HashMap<>();
    private static final Map<String, String> SMS_APPS = new HashMap<>();

    static {
        // UPI apps
        PAYMENT_APPS.put("com.google.android.apps.nbu.paisa.user", "Google Pay");
        PAYMENT_APPS.put("com.google.android.apps.nbu.paisa.merchant", "Google Pay Business");
        PAYMENT_APPS.put("com.phonepe.app", "PhonePe");
        PAYMENT_APPS.put("com.phonepe.merchant", "PhonePe Business");
        PAYMENT_APPS.put("com.phonepe.app.business", "PhonePe Business");
        PAYMENT_APPS.put("net.one97.paytm", "Paytm");
        PAYMENT_APPS.put("com.paytm.business", "Paytm Business");
        PAYMENT_APPS.put("in.org.npci.upiapp", "BHIM");
        PAYMENT_APPS.put("in.amazon.mShop.android.shopping", "Amazon Pay");
        PAYMENT_APPS.put("com.dreamplug.androidapp", "CRED");
        PAYMENT_APPS.put("com.mobikwik_new", "MobiKwik");
        PAYMENT_APPS.put("com.naviapp", "Navi");
        PAYMENT_APPS.put("money.jupiter", "Jupiter");
        // Bank apps
        PAYMENT_APPS.put("com.sbi.lotusintouch", "YONO SBI");
        PAYMENT_APPS.put("com.sbi.upi", "BHIM SBI Pay");
        PAYMENT_APPS.put("com.snapwork.hdfc", "HDFC Bank");
        PAYMENT_APPS.put("com.csam.icici.bank.imobile", "ICICI iMobile");
        PAYMENT_APPS.put("com.axis.mobile", "Axis Bank");
        PAYMENT_APPS.put("com.msf.kbank.mobile", "Kotak");
        PAYMENT_APPS.put("com.bankofbaroda.mconnect", "Bank of Baroda");
        PAYMENT_APPS.put("com.canarabank.mobility", "Canara Bank");
        PAYMENT_APPS.put("com.infrasoft.uboi", "Union Bank");
        PAYMENT_APPS.put("com.Version1", "PNB One");

        // SMS apps — only used when the SMS permission is NOT granted (fallback)
        SMS_APPS.put("com.android.mms", "Messages");
        SMS_APPS.put("com.google.android.apps.messaging", "Messages");
        SMS_APPS.put("com.samsung.android.messaging", "Messages");
        SMS_APPS.put("com.android.messaging", "Messages");
    }

    static boolean isPaymentApp(String pkg) { return PAYMENT_APPS.containsKey(pkg); }
    static boolean isSmsApp(String pkg) { return SMS_APPS.containsKey(pkg); }

    static String label(String source) {
        if (SMS.equals(source)) return "Bank SMS";
        if ("test".equals(source)) return "Test";
        String l = PAYMENT_APPS.get(source);
        return l != null ? l : source;
    }

    /** Title + main text of a notification (big text preferred, inbox lines only as a fallback). */
    static String text(Notification n) {
        Bundle ex = n.extras;
        if (ex == null) return "";
        StringBuilder sb = new StringBuilder();
        add(sb, ex.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null || body.length() == 0) body = ex.getCharSequence(Notification.EXTRA_TEXT);
        add(sb, body);
        if (body == null || body.length() == 0) {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) add(sb, lines[lines.length - 1]);
        }
        if (sb.length() == 0 && n.tickerText != null) add(sb, n.tickerText);
        return sb.toString();
    }

    private static void add(StringBuilder sb, CharSequence cs) {
        if (cs == null || cs.length() == 0) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(cs);
    }
}
