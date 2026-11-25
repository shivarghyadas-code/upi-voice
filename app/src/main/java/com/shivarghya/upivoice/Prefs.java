package com.shivarghya.upivoice;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny settings store. */
final class Prefs {
    private Prefs() {}

    private static final int LOG_MAX = 15;

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

    static void putBool(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    static void putString(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    static void addLog(Context c, long paise, String source) {
        String time = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date());
        String line = time + "   " + Announcer.formatRupees(paise) + "   · " + Sources.label(source);
        String old = sp(c).getString("log", "");
        StringBuilder sb = new StringBuilder(line);
        int n = 1;
        for (String l : old.split("\n")) {
            if (l.isEmpty() || n >= LOG_MAX) continue;
            sb.append('\n').append(l);
            n++;
        }
        sp(c).edit().putString("log", sb.toString()).apply();
    }

    static String log(Context c) { return sp(c).getString("log", ""); }
}
