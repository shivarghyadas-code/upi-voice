package com.shivarghya.upivoice;

import android.content.Context;

import java.util.LinkedHashSet;

/**
 * One place that handles a bank SMS, whichever way it reached the app:
 *  - the instant SMS broadcast (SmsReceiver), or
 *  - the inbox watcher (SmsWatcher) — catches SMS that MIUI hid from the broadcast.
 * The same SMS is processed only once, so it is never announced twice. Works fully offline.
 */
final class SmsHandler {
    private SmsHandler() {}

    /**
     * @param sentTs  bank's send time (SMS centre timestamp) — identical in both paths
     * @return true if it was handled now (false = already handled by the other path)
     */
    static boolean handle(Context c, String sender, String body, long sentTs, long recvTs, String via, Runnable onDone) {
        if (sender == null || body == null || !sender.matches(".*[A-Za-z].*")) {
            if (onDone != null) onDone.run();
            return false;                               // banks never send from phone numbers
        }
        String id = sentTs + ":" + Integer.toHexString(body.hashCode());
        if (!firstTime(c, id)) {
            if (onDone != null) onDone.run();
            return false;
        }
        String norm = Learned.normSender(sender);
        String kind = CreditParser.classify(body);
        long paise = Decide.credit(c, norm, body);

        BankLog.Entry e = new BankLog.Entry();
        e.time = recvTs;
        e.id = id;
        e.sender = norm;
        e.body = body;
        e.paise = paise > 0 ? paise : Math.max(0, CreditParser.amountOnly(body));

        if (paise > 0) {
            if (Prefs.enabled(c)) {
                boolean spoken = Announcer.get(c).offer(Sources.SMS, paise, onDone, "sms:" + sentTs + ":" + paise);
                e.status = spoken ? "ANNOUNCED" : "DUPLICATE";
                Prefs.diag(c, "SMS " + norm + " (" + via + ")", body, spoken ? "ANNOUNCED " + Speech.formatRupees(paise)
                        : "duplicate of " + Speech.formatRupees(paise) + " already spoken");
            } else {
                e.status = "OFF";
                if (onDone != null) onDone.run();
            }
        } else {
            e.status = "CREDIT".equals(kind) ? "TAUGHT_NOT" : kind;   // CREDIT here = user taught "not received"
            Prefs.diag(c, "SMS " + norm + " (" + via + ")", body, "not an incoming payment");
            if (onDone != null) onDone.run();
        }
        if (!"NONE".equals(e.status)) BankLog.add(c, e);
        return true;
    }

    private static synchronized boolean firstTime(Context c, String id) {
        String stored = Prefs.sp(c).getString("sms_seen", "");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : stored.split("\n")) if (!s.isEmpty()) set.add(s);
        if (set.contains(id)) return false;
        set.add(id);
        while (set.size() > 150) set.remove(set.iterator().next());
        Prefs.sp(c).edit().putString("sms_seen", String.join("\n", set)).commit();
        return true;
    }
}
