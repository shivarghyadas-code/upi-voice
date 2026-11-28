package com.shivarghya.upivoice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;

/**
 * Direct line from the "Private Messages" SMS app: the moment it saves a bank SMS it pokes us
 * with the message's inbox id, so the amount is spoken without waiting for Android's normal
 * SMS broadcast (which reaches other apps only after the SMS app has finished).
 *
 * Safe against fake announcements: we never trust the text in the poke. We read the SMS from
 * the phone's own inbox, which only the real default SMS app can write to.
 */
public class CompanionSmsReceiver extends BroadcastReceiver {
    static final String ACTION = "com.shivarghya.upivoice.BANK_SMS";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        long id = intent.getLongExtra("sms_id", -1);
        if (id <= 0 || c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        String sender = null, body = null;
        long date = 0, sent = 0;
        try (Cursor cur = c.getContentResolver().query(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                        Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE}, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return;
            if (cur.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX) return;
            sender = cur.getString(0);
            body = cur.getString(1);
            date = cur.getLong(2);
            sent = cur.getLong(3);
        } catch (RuntimeException e) {
            return;
        }
        if (System.currentTimeMillis() - date > 10 * 60_000L) return;   // only fresh messages
        final PendingResult pending = goAsync();
        // Same id as the normal SMS path (send time + text), so it is spoken only once.
        SmsHandler.handle(c, sender, body, sent > 0 ? sent : date, date, "messages-app", pending::finish);
    }
}
