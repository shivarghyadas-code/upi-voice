package com.shivarghya.upivoice;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which apps we listen to.
 *
 * AUTO-DETECTION: every UPI app on the phone (GPay, PhonePe, Paytm, BHIM, bank apps, new
 * ones installed later…) registers to open "upi://pay" links — NPCI requires it. So the
 * first time a notification arrives from an unknown app, we ask Android once whether that
 * app handles upi://pay, and remember the answer. No list to maintain; a newly installed
 * UPI app is picked up automatically.
 *
 * A small built-in list adds merchant / business apps that may not handle upi://pay.
 */
final class Sources {
    private Sources() {}

    static final String SMS = "sms";

    private static final Map<String, String> KNOWN = new LinkedHashMap<>();
    private static final Map<String, Boolean> SMS_APPS = new HashMap<>();
    /** Chat apps that also handle upi://pay — chats like "received ₹500, thanks" must not trigger. */
    private static final Map<String, Boolean> EXCLUDED = new HashMap<>();

    /** package → is it a UPI app? (filled lazily, main thread only) */
    private static final Map<String, Boolean> cache = new HashMap<>();
    private static String defaultSmsApp;

    static {
        KNOWN.put("com.google.android.apps.nbu.paisa.merchant", "Google Pay for Business");
        KNOWN.put("com.phonepe.merchant", "PhonePe Business");
        KNOWN.put("com.phonepe.app.business", "PhonePe Business");
        KNOWN.put("com.paytm.business", "Paytm for Business");
        KNOWN.put("com.google.android.apps.nbu.paisa.user", "Google Pay");
        KNOWN.put("com.phonepe.app", "PhonePe");
        KNOWN.put("net.one97.paytm", "Paytm");
        KNOWN.put("in.org.npci.upiapp", "BHIM");
        // Banks — always connected, even if the app doesn't register for upi:// links
        KNOWN.put("com.sbi.lotusintouch", "YONO SBI");
        KNOWN.put("com.sbi.SBIFreedomPlus", "YONO Lite SBI");
        KNOWN.put("com.sbi.upi", "BHIM SBI Pay");
        KNOWN.put("com.Version1", "PNB ONE");

        SMS_APPS.put("com.android.mms", true);
        SMS_APPS.put("com.google.android.apps.messaging", true);
        SMS_APPS.put("com.samsung.android.messaging", true);
        SMS_APPS.put("com.android.messaging", true);

        EXCLUDED.put("com.whatsapp", true);
        EXCLUDED.put("com.whatsapp.w4b", true);
        EXCLUDED.put("org.telegram.messenger", true);
        EXCLUDED.put("com.truecaller", true);
    }

    /** Call when apps may have changed (listener reconnect, app opened). */
    static void refresh() {
        cache.clear();
        defaultSmsApp = null;
    }

    static boolean isPaymentApp(Context c, String pkg) {
        if (KNOWN.containsKey(pkg)) return true;
        Boolean b = cache.get(pkg);
        if (b == null) {
            b = !EXCLUDED.containsKey(pkg) && handlesUpi(c, pkg);
            cache.put(pkg, b);
        }
        return b;
    }

    static boolean isSmsApp(Context c, String pkg) {
        if (SMS_APPS.containsKey(pkg)) return true;
        if (defaultSmsApp == null) {
            try {
                String d = Telephony.Sms.getDefaultSmsPackage(c);
                defaultSmsApp = d != null ? d : "";
            } catch (RuntimeException e) {
                defaultSmsApp = "";
            }
        }
        return pkg.equals(defaultSmsApp);
    }

    private static Intent upiIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"));
    }

    private static boolean handlesUpi(Context c, String pkg) {
        try {
            return !c.getPackageManager().queryIntentActivities(upiIntent().setPackage(pkg), 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** All UPI / payment apps installed right now, as {package, name}. For the setup screen. */
    static List<String[]> installedApps(Context c) {
        PackageManager pm = c.getPackageManager();
        LinkedHashMap<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : KNOWN.entrySet()) {
            try {
                pm.getApplicationInfo(e.getKey(), 0);
                found.put(e.getKey(), appLabel(c, e.getKey()));
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(upiIntent(), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!EXCLUDED.containsKey(pkg) && !found.containsKey(pkg)) found.put(pkg, appLabel(c, pkg));
            }
        } catch (RuntimeException ignored) {}
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : found.entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
        return out;
    }

    static String label(Context c, String source) {
        if (SMS.equals(source)) return "Bank SMS";
        if ("test".equals(source)) return "Test";
        return appLabel(c, source);
    }

    private static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (PackageManager.NameNotFoundException ignored) {}
        String k = KNOWN.get(pkg);
        return k != null ? k : pkg;
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
