package com.shivarghya.upivoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Loud pop-up warning (+ optional spoken warning) for scam messages and dangerous new apps. */
final class ScamAlert {
    private ScamAlert() {}

    private static final String CHANNEL = "scam_alerts";
    private static final Map<String, Long> recent = new HashMap<>();   // de-dupe (SMS seen twice, WhatsApp re-posts)

    static void message(Context c, ScamText.Result r, String from, String text) {
        if (r == null || !Prefs.scamAlerts(c)) return;
        String snip = text.replace('\n', ' ').trim();
        if (snip.length() > 160) snip = snip.substring(0, 160) + "…";
        String key = from + "|" + r.reason.hashCode() + "|" + snip.hashCode();
        if (!fresh(key)) return;
        boolean danger = r.level == ScamText.Level.DANGER;
        post(c, key.hashCode(), (danger ? "⚠ SCAM WARNING — " : "⚠ Be careful — ") + from,
                r.reason + "\n\n\"" + snip + "\"\n\nDon't click links, don't install apps, never share OTP or UPI PIN. "
                        + "Lost money? Call 1930 at once.",
                new Intent(c, SafetyActivity.class), null);
        log(c, from, (danger ? "SCAM" : "CAREFUL") + ": " + r.reason, snip);
        if (danger && Prefs.scamVoice(c)) Announcer.get(c).warn();
    }

    static void dangerousApp(Context c, String pkg, String label, String abilities) {
        if (!Prefs.scamAlerts(c)) return;
        if (!fresh("app|" + pkg)) return;
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        post(c, ("app" + pkg).hashCode(), "⚠ Dangerous app installed: " + label,
                "\"" + label + "\" was installed from OUTSIDE the Play Store and can " + abilities + ".\n\n"
                        + "If someone sent you this file (WhatsApp, SMS, a caller), UNINSTALL it now — this is how bank "
                        + "accounts are emptied. Tap to uninstall.",
                uninstall, new Intent(c, SafetyActivity.class));
        log(c, label, "DANGEROUS APP: can " + abilities, pkg);
        if (Prefs.scamVoice(c)) Announcer.get(c).warn();
    }

    private static synchronized boolean fresh(String key) {
        long now = System.currentTimeMillis();
        Long last = recent.get(key);
        if (last != null && now - last < 30 * 60_000L) return false;
        recent.put(key, now);
        return true;
    }

    private static void post(Context c, int id, String title, String body, Intent tap, Intent second) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Scam warnings", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Pops up when a scam message or a dangerous app is detected");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(body.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(0xFFC62828)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, id, tap, flags));
        if (second != null) {
            second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(null, "Safety check",
                    PendingIntent.getActivity(c, id + 1, second, flags)).build());
        }
        try { nm.notify(id, b.build()); } catch (RuntimeException ignored) {}  // notifications blocked
    }

    private static void log(Context c, String from, String what, String detail) {
        String time = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date());
        String entry = time + " · " + from + "\n   " + what + (detail.isEmpty() ? "" : "\n   \"" + detail + "\"");
        String old = Prefs.sp(c).getString("scam_log", "");
        StringBuilder sb = new StringBuilder(entry);
        int n = 1;
        for (String e : old.split("\n\n")) {
            if (e.isEmpty() || n >= 30) continue;
            sb.append("\n\n").append(e);
            n++;
        }
        Prefs.putString(c, "scam_log", sb.toString());
    }

    static boolean notificationsAllowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
}
