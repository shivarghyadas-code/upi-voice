package com.shivarghya.upivoice;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Did ₹X come in?" — answered from what is already ON the phone (bank SMS + payment alerts),
 * so it works with no mobile data and no Wi-Fi. Keeps waiting and turns green when the SMS arrives.
 */
public class PaymentCheckActivity extends Activity implements PaymentBus.Listener {

    private static final int GREEN = 0xFF1B8A5A, RED = 0xFFC62828, ORANGE = 0xFFE65100, GREY = 0xFF5F6368;
    private static final long[] WINDOWS = {10 * 60_000L, 60 * 60_000L, -1};       // -1 = today
    private static final String[] WINDOW_NAMES = {"Last 10 min", "Last 1 hour", "Today"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView net, result, recent;
    private EditText amount;
    private final Button[] winBtns = new Button[3];
    private int win = 0;
    private long want = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderNet();
            if (want > 0) check(false);
            main.postDelayed(this, 3000);
        }
    };

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

        TextView title = text("Check payment", 26, 0xFF202124, true);
        root.addView(title);
        net = text("", 15, GREY, true);
        net.setPadding(0, dp(4), 0, dp(10));
        root.addView(net);

        root.addView(text("Amount the customer says they paid:", 15, 0xFF202124, false));
        amount = new EditText(this);
        amount.setHint("₹ 0");
        amount.setTextSize(30);
        amount.setSingleLine(true);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        amount.setOnEditorActionListener((v, a, e) -> { check(true); return true; });
        root.addView(amount);

        LinearLayout wins = new LinearLayout(this);
        wins.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            winBtns[i] = button(WINDOW_NAMES[i], v -> { win = k; renderWins(); if (want > 0) check(false); });
            wins.addView(winBtns[i], new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(wins);
        renderWins();

        Button go = button("✔  CHECK", v -> check(true));
        go.setTextSize(20);
        root.addView(go);

        result = text("", 24, Color.WHITE, true);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(14), dp(22), dp(14), dp(22));
        result.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(12);
        root.addView(result, rlp);

        root.addView(text("\n⚠ Don't trust the customer's phone screen or screenshot — fake payment screens are "
                + "a common trick. Only trust this check, the voice, or your bank SMS.", 13, ORANGE, false));

        TextView rh = text("\nMoney received recently", 18, 0xFF202124, true);
        root.addView(rh);
        recent = text("", 15, 0xFF3C4043, false);
        root.addView(recent);

        long pre = getIntent().getLongExtra("paise", -1);
        if (pre > 0) {
            amount.setText(Speech.formatRupees(pre).substring(1).replace(",", ""));
            check(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaymentBus.add(this);
        main.post(tick);
        renderRecent();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PaymentBus.remove(this);
        main.removeCallbacks(tick);
    }

    @Override
    public void onPayment(long paise, String source) {
        main.postDelayed(() -> { renderRecent(); if (want > 0) check(false); }, 300);   // after it is saved
    }

    // ------------------------------------------------------------------ the check

    private void check(boolean fromUser) {
        if (fromUser) {
            want = parse(amount.getText().toString());
            if (want <= 0) {
                show(GREY, "Enter the amount first");
                return;
            }
        }
        long since = since();
        long now = System.currentTimeMillis();
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);

        // 1) Bank SMS — the bank's own confirmation (works offline)
        BankLog.Entry sms = null;
        for (BankLog.Entry e : BankLog.load(this)) {
            if (e.time < since) break;
            if (e.paise == want && isMoneyIn(e.status)) { sms = e; break; }
        }
        // 2) Payment app alert / any recorded credit
        History.Item alert = null;
        for (History.Item h : History.load(this)) {
            if (h.time < since) break;
            if (h.paise == want) { alert = h; break; }
        }

        String amt = Speech.formatRupees(want);
        if (sms != null) {
            show(GREEN, "✅ " + amt + " CREDITED\n" + t.format(new Date(sms.time))
                    + " · confirmed by bank SMS (" + sms.sender + ")" + ago(now - sms.time));
        } else if (alert != null) {
            show(GREEN, "✅ " + amt + " RECEIVED\n" + t.format(new Date(alert.time)) + " · " + alert.source
                    + ago(now - alert.time) + "\nBank SMS not arrived yet");
        } else {
            boolean online = online(this);
            show(RED, "❌ " + amt + " NOT credited yet\n" + (online
                    ? "Waiting… this updates by itself."
                    : "Waiting for the bank SMS… works without internet; this updates by itself."));
        }
    }

    private static boolean isMoneyIn(String status) {
        switch (status) {
            case "ANNOUNCED": case "DUPLICATE": case "OFF": case "CREDIT": case "TAUGHT_IN": return true;
            default: return false;
        }
    }

    private long since() {
        long w = WINDOWS[win];
        if (w > 0) return System.currentTimeMillis() - w;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String ago(long ms) {
        long m = ms / 60_000;
        return m < 1 ? " (just now)" : " (" + m + " min ago)";
    }

    // ------------------------------------------------------------------ display

    private void show(int color, String s) {
        result.setVisibility(View.VISIBLE);
        result.setText(s);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(color);
        result.setBackground(bg);
    }

    private void renderNet() {
        boolean on = online(this);
        net.setText(on ? "📶 Online — checking bank SMS and payment-app alerts"
                : "📴 No internet / Wi-Fi — checking bank SMS (works offline)");
        net.setTextColor(on ? GREEN : ORANGE);
    }

    private void renderWins() {
        for (int i = 0; i < 3; i++) {
            winBtns[i].setTypeface(i == win ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            winBtns[i].setText((i == win ? "● " : "") + WINDOW_NAMES[i]);
        }
    }

    private void renderRecent() {
        long since = System.currentTimeMillis() - 60 * 60_000L;
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        StringBuilder sb = new StringBuilder();
        List<History.Item> items = History.load(this);
        for (History.Item h : items) {
            if (h.time < since) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t.format(new Date(h.time))).append("   ").append(Speech.formatRupees(h.paise))
                    .append("   · ").append(h.source);
        }
        recent.setText(sb.length() == 0 ? "Nothing in the last hour." : sb.toString());
    }

    /** True only if the phone has working internet right now. */
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long parse(String s) {
        try {
            long p = new BigDecimal(s.trim().replace(",", "").replace("₹", ""))
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return p > 0 ? p : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
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
