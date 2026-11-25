package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** All payments received: daily totals, import from bank SMS, open UPI apps, clear. */
public class HistoryActivity extends Activity implements PaymentBus.Listener {

    private static final int REQ_READ_SMS = 31;
    private static final int SHOW_MAX = 300;
    private static final int GREEN = 0xFF1B8A5A;

    private LinearLayout list;
    private TextView summary;
    private Button btnImport;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, dp(40));
        sv.addView(root);
        setContentView(sv);

        TextView title = text("Transaction history", 24, 0xFF202124);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        summary = text("", 17, GREEN);
        summary.setPadding(0, dp(6), 0, dp(6));
        root.addView(summary);

        btnImport = button("⬇  Import from bank SMS (last 30 days)", v -> importSms());
        root.addView(btnImport);
        root.addView(button("🗑  Clear transaction history", v -> confirmClear()));

        // Other apps' own history can't be read by any app (Android keeps it private),
        // so give one-tap access to each installed UPI app instead.
        section(root, "Check history inside your UPI apps");
        root.addView(text("Android doesn't let one app read another app's history. Tap to open the app:", 13, 0xFF5F6368));
        PackageManager pm = getPackageManager();
        for (String[] a : Sources.installedApps(this)) {
            Intent launch = pm.getLaunchIntentForPackage(a[0]);
            if (launch == null) continue;
            root.addView(button("Open " + a[1], v -> {
                try { startActivity(launch); } catch (RuntimeException ignored) {}
            }));
        }

        section(root, "Payments received");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaymentBus.add(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PaymentBus.remove(this);
    }

    @Override
    public void onPayment(long paise, String source) {
        list.postDelayed(this::render, 200);   // after it has been written
    }

    // ------------------------------------------------------------------ list

    private void render() {
        List<History.Item> items = History.load(this);
        list.removeAllViews();

        long todayStart = dayStart(System.currentTimeMillis());
        long todayTotal = 0;
        int todayCount = 0;
        for (History.Item it : items) {
            if (it.time >= todayStart) { todayTotal += it.paise; todayCount++; }
        }
        summary.setText("Today: " + Speech.formatRupees(todayTotal) + "  from " + todayCount
                + (todayCount == 1 ? " payment" : " payments"));

        if (items.isEmpty()) {
            list.addView(text("No payments yet. Tap \"Import from bank SMS\" to load past payments.", 15, 0xFF5F6368));
            return;
        }

        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH);
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        long currentDay = -1;
        TextView dayHeader = null;
        long dayTotal = 0;
        int dayCount = 0;
        int shown = 0;
        for (History.Item it : items) {
            if (shown >= SHOW_MAX) break;
            long d = dayStart(it.time);
            if (d != currentDay) {
                if (dayHeader != null) setDayHeader(dayHeader, currentDay, dayTotal, dayCount, todayStart, dayFmt);
                currentDay = d;
                dayTotal = 0;
                dayCount = 0;
                dayHeader = text("", 16, 0xFF202124);
                dayHeader.setTypeface(Typeface.DEFAULT_BOLD);
                dayHeader.setPadding(0, dp(16), 0, dp(4));
                list.addView(dayHeader);
            }
            dayTotal += it.paise;
            dayCount++;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView left = text(timeFmt.format(new Date(it.time)) + "   " + it.source, 15, 0xFF3C4043);
            TextView right = text("+" + Speech.formatRupees(it.paise), 16, GREEN);
            right.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(right);
            list.addView(row);
            shown++;
        }
        if (dayHeader != null) setDayHeader(dayHeader, currentDay, dayTotal, dayCount, todayStart, dayFmt);
        if (items.size() > SHOW_MAX) {
            list.addView(text("Showing the latest " + SHOW_MAX + " of " + items.size() + ".", 13, 0xFF5F6368));
        }
    }

    private void setDayHeader(TextView t, long day, long total, int count, long todayStart, SimpleDateFormat f) {
        String name = day == todayStart ? "Today" : day == todayStart - 86_400_000L ? "Yesterday" : f.format(new Date(day));
        // (yesterday check is approximate across DST; India has no DST)
        t.setText(name + "  —  " + Speech.formatRupees(total) + "  (" + count + ")");
    }

    private static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------------ import from SMS

    private void importSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_SMS}, REQ_READ_SMS);
            return;
        }
        btnImport.setEnabled(false);
        btnImport.setText("Reading bank SMS…");
        final android.content.Context ctx = getApplicationContext();
        new Thread(() -> {
            List<History.Item> found = new ArrayList<>();
            String error = null;
            long since = System.currentTimeMillis() - 30L * 86_400_000L;
            try (Cursor cur = ctx.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                    Telephony.Sms.DATE + " > ?", new String[]{String.valueOf(since)}, Telephony.Sms.DATE + " DESC")) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String addr = cur.getString(0);
                        String body = cur.getString(1);
                        long date = cur.getLong(2), sent = cur.getLong(3);
                        if (addr == null || !addr.matches(".*[A-Za-z].*")) continue;   // banks only
                        long p = CreditParser.parse(body);
                        if (p <= 0) continue;
                        History.Item it = new History.Item();
                        it.time = date;
                        it.paise = p;
                        it.source = "Bank SMS · " + shortSender(addr);
                        it.key = "sms:" + (sent > 0 ? sent : date) + ":" + p;
                        found.add(it);
                    }
                }
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            int added = History.addAll(ctx, found);
            final String err = error;
            final int total = found.size();
            runOnUiThread(() -> {
                btnImport.setEnabled(true);
                btnImport.setText("⬇  Import from bank SMS (last 30 days)");
                if (err != null) {
                    Toast.makeText(this, "Could not read SMS. On MIUI allow all SMS permissions for UPI Voice.", Toast.LENGTH_LONG).show();
                } else if (total == 0) {
                    Toast.makeText(this, "No bank credit SMS found in the last 30 days. "
                            + "(MIUI: allow \"Notification/Service SMS\" permission)", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Found " + total + " payments, added " + added + " new", Toast.LENGTH_LONG).show();
                }
                render();
            });
        }).start();
    }

    /** "VM-SBIUPI" → "SBIUPI" */
    private static String shortSender(String a) {
        int dash = a.indexOf('-');
        return (dash >= 0 && dash < 4) ? a.substring(dash + 1) : a;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_READ_SMS) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) importSms();
        else Toast.makeText(this, "Allow SMS access to import past payments", Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ clear

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear transaction history?")
                .setMessage("This deletes the payment list in this app only. Your bank and UPI apps keep their own records.")
                .setPositiveButton("Clear", (d, w) -> {
                    History.clear(this);
                    render();
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ helpers

    private void section(LinearLayout root, String s) {
        TextView t = text(s, 18, 0xFF202124);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
