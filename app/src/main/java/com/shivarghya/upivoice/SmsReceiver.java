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
        if (!Prefs.enabled(context)) return;

        SmsMessage[] msgs;
        try {
            msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (RuntimeException e) {
            return;
        }
        if (msgs == null || msgs.length == 0 || msgs[0] == null) return;

        // Banks send from registered headers like "VM-SBIUPI" / "AD-HDFCBK".
        // Ignore SMS from plain phone numbers: anyone can type a fake "credited" text.
        String sender = msgs[0].getOriginatingAddress();
        if (sender == null || !sender.matches(".*[A-Za-z].*")) {
            Prefs.diag(context, "SMS from a phone number", null, "ignored (banks never use phone numbers)");
            return;
        }

        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
        }

        String text = body.toString();
        long paise = CreditParser.parse(text);
        if (paise <= 0) {
            Prefs.diag(context, "SMS " + sender, text, "not an incoming payment");
            return;
        }

        // Keep the receiver alive until the announcement has been spoken (max ~9 s).
        final PendingResult pending = goAsync();
        boolean spoken = Announcer.get(context).offer(Sources.SMS, paise, pending::finish);
        Prefs.diag(context, "SMS " + sender, text, spoken ? "ANNOUNCED " + Speech.formatRupees(paise)
                : "duplicate of " + Speech.formatRupees(paise) + " already spoken");
    }
}
