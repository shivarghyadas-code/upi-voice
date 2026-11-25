package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Notification;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashSet;

/**
 * The system keeps this service bound, so it stays alive without a foreground notification.
 * Each callback does a cheap (cached) package check first; only UPI-app notifications are parsed.
 */
public class PaymentListenerService extends NotificationListenerService {

    static volatile boolean connected;

    private static final long CATCH_UP_MS = 15 * 60_000L;

    @Override
    public void onListenerConnected() {
        connected = true;
        Sources.refresh();                 // re-detect UPI apps (new installs / uninstalls)
        Announcer.get(this).warmUp();      // load the voice now, so payments are spoken instantly
        catchUp();
    }

    /**
     * If MIUI stopped the app (or the phone was off), payment alerts that arrived meanwhile are still
     * in the notification shade. Announce the recent ones we haven't handled yet.
     */
    private void catchUp() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            long now = System.currentTimeMillis();
            for (StatusBarNotification sbn : active) {
                if (now - sbn.getPostTime() < CATCH_UP_MS) onNotificationPosted(sbn);
            }
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        try {
            requestRebind(new ComponentName(this, PaymentListenerService.class));
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        String source;
        if (Sources.isPaymentApp(this, pkg)) {
            source = pkg;
        } else if (Sources.isSmsApp(this, pkg) && !hasSmsPermission()) {
            source = Sources.SMS;      // fallback only; normally SmsReceiver handles SMS
        } else {
            return;                     // the vast majority of notifications exit here
        }
        if (!Prefs.enabled(this)) return;

        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        String text = Sources.text(n);
        if (text.isEmpty()) return;
        String key = sbn.getKey() + '|' + text.hashCode();
        if (alreadySeen(key)) return;

        long paise = CreditParser.parse(text);
        String label = Sources.label(this, source);
        if (paise > 0) {
            boolean spoken = Announcer.get(this).offer(source, paise, null);
            Prefs.diag(this, label, text, spoken ? "ANNOUNCED " + Speech.formatRupees(paise)
                    : "duplicate of " + Speech.formatRupees(paise) + " already spoken");
        } else {
            Prefs.diag(this, label, text, "not an incoming payment");
        }
    }

    /** Remembers handled notifications across app restarts (apps often re-post/update the same one). */
    private boolean alreadySeen(String key) {
        String k = key.replace('\n', ' ');
        String stored = Prefs.sp(this).getString("seen", "");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : stored.split("\n")) if (!s.isEmpty()) set.add(s);
        if (set.contains(k)) return true;
        set.add(k);
        while (set.size() > 80) set.remove(set.iterator().next());
        Prefs.putString(this, "seen", String.join("\n", set));
        return false;
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }
}
