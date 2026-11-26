package com.shivarghya.upivoice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** 🛡 Safety check: scam-setup scan with fix buttons, scam-alert settings & log, 1930 helpline. */
public class SafetyActivity extends Activity {

    private static final int RED = 0xFFC62828, ORANGE = 0xFFE65100, GREEN = 0xFF1B8A5A, GREY = 0xFF5F6368, DARK = 0xFF202124;

    private LinearLayout results;
    private TextView status, log;

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

        root.addView(text("🛡 Safety check", 26, DARK, true));
        status = text("Checking…", 16, GREY, true);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);
        root.addView(button("↻  Check again", v -> runScan()));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        section(root, "Scam message alerts");
        root.addView(text("Checks every SMS, WhatsApp and Telegram message on this phone (offline, nothing is sent "
                + "anywhere) and pops up a warning for scam messages and dangerous app files.", 13, GREY, false));
        root.addView(toggle("Warn me about scam messages and dangerous apps", Prefs.scamAlerts(this),
                on -> Prefs.putBool(this, "scam_alerts", on)));
        root.addView(toggle("Also speak the warning aloud", Prefs.scamVoice(this),
                on -> Prefs.putBool(this, "scam_voice", on)));
        if (Build.VERSION.SDK_INT >= 33 && !ScamAlert.notificationsAllowed(this)) {
            root.addView(button("Allow warning pop-ups", v -> requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 61)));
        }

        section(root, "Got cheated or a suspicious call?");
        Button call = button("📞  Call 1930 — National Cyber Fraud Helpline", v -> open(
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))));
        call.setTextColor(RED);
        root.addView(call);
        root.addView(text("Call within the first hour — the bank can often freeze the money. You can also report at "
                + "cybercrime.gov.in, and report scam calls/SMS in the Sanchar Saathi app (Chakshu).", 13, GREY, false));
        root.addView(button("Open cybercrime.gov.in", v -> open(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in")))));

        section(root, "Remember — these are ALWAYS scams");
        root.addView(text(
                "• Scanning a QR or entering UPI PIN to RECEIVE money\n"
                        + "• Anyone asking for OTP, UPI PIN, or card CVV — even \"bank\" or \"police\"\n"
                        + "• \"Digital arrest\" / CBI / customs on a video call\n"
                        + "• \"Install this app\" (.apk) from WhatsApp, SMS or a caller\n"
                        + "• \"Sent you money by mistake, please return\"\n"
                        + "• Electricity cut tonight — call this number\n"
                        + "• KYC / PAN / account blocked — click this link\n"
                        + "• Like videos / tasks / trading group = easy money\n"
                        + "• Customer showing a payment screenshot — check UPI Voice instead\n"
                        + "• Dialing *401* or **21* because a caller said so", 15, DARK, false));

        section(root, "Recent warnings");
        log = text("", 13, DARK, false);
        log.setTextIsSelectable(true);
        root.addView(log);
        root.addView(button("🗑  Clear warnings list", v -> {
            Prefs.sp(this).edit().remove("scam_log").apply();
            renderLog();
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        runScan();
        renderLog();
    }

    private void runScan() {
        status.setText("Checking…");
        status.setTextColor(GREY);
        new Thread(() -> {
            List<Safety.Finding> f = Safety.scan(getApplicationContext());
            runOnUiThread(() -> render(f));
        }).start();
    }

    private void render(List<Safety.Finding> list) {
        results.removeAllViews();
        int danger = 0;
        for (Safety.Finding f : list) if (f.danger) danger++;
        if (list.isEmpty()) {
            status.setText("✅ No problems found on this phone");
            status.setTextColor(GREEN);
            return;
        }
        status.setText(danger > 0 ? "⚠ " + danger + " danger" + (danger == 1 ? "" : "s") + " found — fix now"
                : "Some things to check (" + list.size() + ")");
        status.setTextColor(danger > 0 ? RED : ORANGE);
        for (Safety.Finding f : list) results.addView(card(f));
    }

    private View card(Safety.Finding f) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), f.danger ? RED : ORANGE);
        bg.setColor(f.danger ? 0x10C62828 : 0x10E65100);
        c.setBackground(bg);
        c.addView(text((f.danger ? "⛔ " : "⚠ ") + f.title, 16, f.danger ? RED : ORANGE, true));
        c.addView(text(f.detail, 14, DARK, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button fix = button(fixLabel(f.action), v -> fix(f));
        row.addView(fix);
        if (f.pkg != null && !"LOCK".equals(f.action) && !"DEV".equals(f.action)) {
            row.addView(button("I trust this app", v -> new AlertDialog.Builder(this)
                    .setMessage("Only trust it if YOU installed it on purpose and know who made it. Stop warning about it?")
                    .setPositiveButton("Trust", (d, w) -> { Safety.trust(this, f.pkg); runScan(); })
                    .setNegativeButton("Cancel", null).show()));
        }
        c.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private static String fixLabel(String a) {
        switch (a) {
            case "UNINSTALL": return "Uninstall";
            case "LOCK": return "Set screen lock";
            case "DEV": return "Open developer options";
            case "ADMIN": return "Open device admin apps";
            default: return "Open settings to turn off";
        }
    }

    private void fix(Safety.Finding f) {
        Intent i;
        switch (f.action) {
            case "UNINSTALL": i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + f.pkg)); break;
            case "LOCK": i = new Intent(Settings.ACTION_SECURITY_SETTINGS); break;
            case "ACCESSIBILITY": i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "NOTIF": i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); break;
            case "DEV": i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); break;
            default: i = new Intent("android.settings.DEVICE_ADMIN_SETTINGS");
        }
        if (!open(i)) open(new Intent(Settings.ACTION_SECURITY_SETTINGS));
    }

    private void renderLog() {
        String l = Prefs.sp(this).getString("scam_log", "");
        log.setText(l.isEmpty() ? "No warnings yet." : l);
    }

    // ------------------------------------------------------------------ helpers

    private interface OnToggle { void set(boolean on); }

    private Switch toggle(String label, boolean on, OnToggle cb) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setChecked(on);
        s.setPadding(0, dp(6), 0, dp(6));
        s.setOnCheckedChangeListener((b, c) -> cb.set(c));
        return s;
    }

    private boolean open(Intent i) {
        try { startActivity(i); return true; } catch (RuntimeException e) { return false; }
    }

    private void section(LinearLayout root, String s) {
        TextView t = text(s, 18, DARK, true);
        t.setPadding(0, dp(22), 0, dp(4));
        root.addView(t);
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
