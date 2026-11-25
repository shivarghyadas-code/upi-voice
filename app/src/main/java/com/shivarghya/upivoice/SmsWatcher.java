package com.shivarghya.upivoice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Second, offline way to catch bank SMS: watches the phone's SMS inbox itself.
 * On MIUI the instant SMS broadcast can be blocked for other apps while the message still lands
 * in the inbox — this catches those. Runs whenever the app's process is alive (the notification
 * listener keeps it alive).
 */
final class SmsWatcher extends ContentObserver {

    private static SmsWatcher instance;

    static synchronized void start(Context c) {
        if (instance != null) return;
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        Context app = c.getApplicationContext();
        SmsWatcher w = new SmsWatcher(app);
        try {
            app.getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, w);
            instance = w;
            w.io.execute(w::initLastId);
        } catch (RuntimeException ignored) {}
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable scan = () -> io.execute(this::scanNew);

    private SmsWatcher(Context app) {
        super(new Handler(Looper.getMainLooper()));
        this.app = app;
    }

    @Override
    public void onChange(boolean selfChange) {
        main.removeCallbacks(scan);
        main.postDelayed(scan, 600);       // let the SMS app finish writing
    }

    /** First run: start from the newest SMS, so old messages are not announced. */
    private void initLastId() {
        if (Prefs.sp(app).contains("sms_last_id")) return;
        Prefs.sp(app).edit().putLong("sms_last_id", maxId()).apply();
    }

    private long maxId() {
        try (Cursor cur = app.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms._ID}, null, null, Telephony.Sms._ID + " DESC")) {
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (RuntimeException ignored) {}
        return 0;
    }

    private void scanNew() {
        long last = Prefs.sp(app).getLong("sms_last_id", -1);
        if (last < 0) { initLastId(); return; }
        List<String[]> found = new ArrayList<>();
        long newest = last;
        try (Cursor cur = app.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                        Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                Telephony.Sms._ID + " > ?", new String[]{String.valueOf(last)}, Telephony.Sms._ID + " ASC")) {
            if (cur == null) return;
            int n = 0;
            while (cur.moveToNext() && n++ < 50) {
                long id = cur.getLong(0);
                newest = Math.max(newest, id);
                long date = cur.getLong(3), sent = cur.getLong(4);
                // ignore anything older than 30 minutes (e.g. a restored backup)
                if (System.currentTimeMillis() - date > 30 * 60_000L) continue;
                found.add(new String[]{cur.getString(1), cur.getString(2),
                        String.valueOf(sent > 0 ? sent : date), String.valueOf(date)});
            }
        } catch (RuntimeException e) {
            return;
        }
        Prefs.sp(app).edit().putLong("sms_last_id", newest).apply();
        if (found.isEmpty()) return;
        main.post(() -> {
            for (String[] s : found) {
                SmsHandler.handle(app, s[0], s[1], Long.parseLong(s[2]), Long.parseLong(s[3]), "inbox", null);
            }
        });
    }
}
