package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Offline log of bank SMS + what UPI Voice decided, with "teach" for formats it got wrong. */
public class BankSmsActivity extends Activity {

    private static final int REQ_READ = 51;
    private static final int GREEN = 0xFF1B8A5A, ORANGE = 0xFFE65100, GREY = 0xFF5F6368, DARK = 0xFF202124;

    private LinearLayout list;
    private TextView summary;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, dp(40));
        sv.addView(root);
        setContentView(sv);

        TextView title = text("Bank messages (offline)", 24, DARK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Every bank SMS that mentions money is saved here — no internet needed — with what "
                + "UPI Voice decided. If one is wrong, tap it and teach the right answer: similar messages "
                + "from that bank will then be announced correctly.", 14, GREY));
        summary = text("", 14, GREEN);
        summary.setPadding(0, dp(8), 0, dp(4));
        root.addView(summary);

        root.addView(button("⬇  Load bank SMS from last 7 days", v -> loadRecent()));
        root.addView(button("🗑  Clear this log", v -> new AlertDialog.Builder(this)
                .setMessage("Clear the bank message log? (Your SMS inbox is not touched.)")
                .setPositiveButton("Clear", (d, w) -> { BankLog.clear(this); render(); })
                .setNegativeButton("Cancel", null).show()));
        root.addView(button("↺  Forget everything I taught", v -> new AlertDialog.Builder(this)
                .setMessage("Forget all " + Learned.count(this) + " taught message formats?")
                .setPositiveButton("Forget", (d, w) -> { Learned.clear(this); render(); })
                .setNegativeButton("Cancel", null).show()));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<BankLog.Entry> all = BankLog.load(this);
        list.removeAllViews();
        int unknown = 0;
        for (BankLog.Entry e : all) if ("UNKNOWN".equals(e.status)) unknown++;
        summary.setText(all.size() + " bank messages · " + Learned.count(this) + " taught formats"
                + (unknown > 0 ? "\n⚠ " + unknown + " not understood — tap them to teach" : ""));
        summary.setTextColor(unknown > 0 ? ORANGE : GREEN);
        if (all.isEmpty()) {
            list.addView(text("\nNo bank messages yet. They appear here as they arrive, or tap \"Load bank SMS\".", 15, GREY));
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
        for (BankLog.Entry e : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            String[] st = describe(e);
            TextView head = text(st[0], 15, Integer.parseInt(st[1]));
            head.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(head);
            row.addView(text(f.format(new Date(e.time)) + "  ·  " + e.sender, 13, GREY));
            String body = e.body.length() > 160 ? e.body.substring(0, 160) + "…" : e.body;
            row.addView(text(body, 14, 0xFF3C4043));
            row.setOnClickListener(v -> teach(e));
            list.addView(row);
            View line = new View(this);
            line.setBackgroundColor(0x22000000);
            list.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }
    }

    /** {label, color} for a status. */
    private String[] describe(BankLog.Entry e) {
        String amt = e.paise > 0 ? " " + Speech.formatRupees(e.paise) : "";
        switch (e.status) {
            case "ANNOUNCED":   return new String[]{"🔊 Announced" + amt, String.valueOf(GREEN)};
            case "DUPLICATE":   return new String[]{"🔊 Already announced from the app alert" + amt, String.valueOf(GREEN)};
            case "OFF":         return new String[]{"🔇 Money in" + amt + " (announcements were OFF)", String.valueOf(ORANGE)};
            case "CREDIT":      return new String[]{"💰 Money in" + amt, String.valueOf(GREEN)};
            case "TAUGHT_IN":   return new String[]{"💰 Money in" + amt + " (you taught this)", String.valueOf(GREEN)};
            case "TAUGHT_NOT":  return new String[]{"✋ Not money in (you taught this)", String.valueOf(GREY)};
            case "DEBIT":       return new String[]{"↗ Money out" + amt + " — not announced", String.valueOf(GREY)};
            case "IGNORED":     return new String[]{"— Offer / OTP / failed — ignored", String.valueOf(GREY)};
            default:            return new String[]{"⚠ Not understood" + amt + " — tap to teach", String.valueOf(ORANGE)};
        }
    }

    private void teach(BankLog.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("What is this message?")
                .setMessage(e.sender + ":\n\n" + e.body + "\n\nYour answer is remembered for similar messages from " + e.sender + ".")
                .setPositiveButton("💰 Money received", (d, w) -> {
                    long p = CreditParser.amountOnly(e.body);
                    if (p <= 0) {
                        Toast.makeText(this, "Couldn't find an amount in this message", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Learned.teach(this, e.sender, e.body, true);
                    BankLog.setStatus(this, e.id, "TAUGHT_IN", p);
                    Toast.makeText(this, "Learned. Similar messages from " + e.sender + " will now be announced.",
                            Toast.LENGTH_LONG).show();
                    render();
                })
                .setNegativeButton("✋ Not money received", (d, w) -> {
                    Learned.teach(this, e.sender, e.body, false);
                    BankLog.setStatus(this, e.id, "TAUGHT_NOT", e.paise);
                    Toast.makeText(this, "Learned. Similar messages won't be announced.", Toast.LENGTH_LONG).show();
                    render();
                })
                .setNeutralButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ load past SMS (no announcing)

    private void loadRecent() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_SMS}, REQ_READ);
            return;
        }
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            List<BankLog.Entry> found = new ArrayList<>();
            long since = System.currentTimeMillis() - 7L * 86_400_000L;
            try (Cursor cur = ctx.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                    Telephony.Sms.DATE + " > ?", new String[]{String.valueOf(since)}, Telephony.Sms.DATE + " DESC")) {
                while (cur != null && cur.moveToNext() && found.size() < 300) {
                    String addr = cur.getString(0), body = cur.getString(1);
                    if (addr == null || body == null || !addr.matches(".*[A-Za-z].*")) continue;
                    long date = cur.getLong(2), sent = cur.getLong(3);
                    String norm = Learned.normSender(addr);
                    String kind = CreditParser.classify(body);
                    long p = Decide.credit(ctx, norm, body);
                    if ("NONE".equals(kind) && p <= 0) continue;
                    BankLog.Entry e = new BankLog.Entry();
                    e.time = date;
                    e.id = (sent > 0 ? sent : date) + ":" + Integer.toHexString(body.hashCode());
                    e.sender = norm;
                    e.body = body;
                    e.status = p > 0 ? "CREDIT" : ("CREDIT".equals(kind) ? "TAUGHT_NOT" : kind);
                    e.paise = p > 0 ? p : Math.max(0, CreditParser.amountOnly(body));
                    found.add(e);
                }
            } catch (RuntimeException ex) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't read SMS — allow all SMS permissions for UPI Voice (MIUI)",
                        Toast.LENGTH_LONG).show());
                return;
            }
            BankLog.addAll(ctx, found);
            runOnUiThread(() -> {
                Toast.makeText(this, "Loaded " + found.size() + " bank messages", Toast.LENGTH_SHORT).show();
                render();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_READ && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            SmsWatcher.start(this);
            loadRecent();
        }
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
