package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Notification;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The system keeps this service bound, so it stays alive without a foreground notification.
 * Each callback does a cheap (cached) package check first; only UPI-app notifications are parsed.
 */
public class PaymentListenerService extends NotificationListenerService {

    static volatile boolean connected;

    /** Notifications already handled (apps often re-post/update the same one). */
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 60; }
    };

    @Override
    public void onListenerConnected() {
        connected = true;
        Sources.refresh();                 // re-detect UPI apps (new installs / uninstalls)
        Announcer.get(this).warmUp();      // load the voice now, so payments are spoken instantly
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
        if (seen.containsKey(key)) return;
        seen.put(key, Boolean.TRUE);

        long paise = CreditParser.parse(text);
        if (paise > 0) Announcer.get(this).offer(source, paise, null);
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }
}
