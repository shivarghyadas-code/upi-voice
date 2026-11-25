package com.shivarghya.upivoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Bank credit SMS. Android wakes the app for this broadcast even if MIUI killed it,
 * so this is the most reliable path.
 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] msgs;
        try {
            msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (RuntimeException e) {
            return;
        }
        if (msgs == null || msgs.length == 0 || msgs[0] == null) return;
        String sender = msgs[0].getOriginatingAddress();
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
        }
        if (sender == null || !sender.matches(".*[A-Za-z].*")) {
            Prefs.diag(context, "SMS from a phone number", null, "ignored (banks never use phone numbers)");
            return;
        }
        // Keep the receiver alive until the announcement has been spoken (max ~9 s).
        final PendingResult pending = goAsync();
        SmsHandler.handle(context, sender, body.toString(), msgs[0].getTimestampMillis(),
                System.currentTimeMillis(), "instant", pending::finish);
    }
}
