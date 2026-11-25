package com.shivarghya.upivoice;

import android.content.Context;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves the last error so it can be shown in UPI Voice and copied to the developer. */
final class CrashLog {
    private CrashLog() {}

    static void save(Context c, String where, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = new SimpleDateFormat("d MMM h:mm:ss a", Locale.ENGLISH).format(new Date())
                + " | " + where + " | v" + BuildConfigVersion.name(c)
                + " | " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE
                + "\n" + sw;
        if (s.length() > 6000) s = s.substring(0, 6000);
        // commit(): the process may be about to die
        Prefs.sp(c).edit().putString("crash", s).commit();
    }

    static String get(Context c) { return Prefs.sp(c).getString("crash", ""); }

    static void clear(Context c) { Prefs.sp(c).edit().remove("crash").apply(); }

    /** Catch any crash anywhere in the app. */
    static void install(Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try { save(app, "crash in " + th.getName(), ex); } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    static final class BuildConfigVersion {
        static String name(Context c) {
            try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }
}
