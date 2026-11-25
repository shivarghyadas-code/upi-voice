package com.shivarghya.upivoice;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny settings store. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("upivoice", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return sp(c).getBoolean("enabled", true); }
    /** "bn", "hi" or "en" */
    static String lang(Context c) { return sp(c).getString("lang", "bn"); }
    /** 0 = don't touch phone volume, else 1..100 % of max during the announcement */
    static int volume(Context c) { return sp(c).getInt("volume", 85); }
    /** true = play on the alarm channel, so it's heard even when the phone is on silent */
    static boolean alarm(Context c) { return sp(c).getBoolean("alarm", true); }
    static boolean repeat(Context c) { return sp(c).getBoolean("repeat", false); }
    static boolean langOk(Context c) { return sp(c).getBoolean("lang_ok", true); }
    static boolean voiceOffline(Context c) { return sp(c).getBoolean("voice_offline", true); }

    static void putBool(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    static void putString(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    /** Troubleshooting log: every bank SMS / payment notification seen, and what we decided. */
    static void diag(Context c, String source, String text, String result) {
        String time = new SimpleDateFormat("h:mm:ss a", Locale.ENGLISH).format(new Date());
        String snip = text == null ? "" : text.replace('\n', ' ');
        if (snip.length() > 110) snip = snip.substring(0, 110) + "…";
        String entry = time + " · " + source + " → " + result + (snip.isEmpty() ? "" : "\n   \"" + snip + "\"");
        String old = sp(c).getString("diag", "");
        StringBuilder sb = new StringBuilder(entry);
        int n = 1;
        for (String e : old.split("\n\n")) {
            if (e.isEmpty() || n >= 20) continue;
            sb.append("\n\n").append(e);
            n++;
        }
        sp(c).edit().putString("diag", sb.toString()).apply();
    }

    static String diagLog(Context c) { return sp(c).getString("diag", ""); }
}
