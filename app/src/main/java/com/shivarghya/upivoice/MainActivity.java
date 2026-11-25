package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** One simple screen: setup checklist + a few settings + recent announcements. */
public class MainActivity extends Activity {

    private static final int REQ_SMS = 11;
    private static final int GREEN = 0xFF1B8A5A, RED = 0xFFC62828;

    private LinearLayout root;
    private TextView stNotif, stSms, stBattery, stListening, stVoice, logView;
    private Button btnVoice;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, dp(48));
        sv.addView(root);
        setContentView(sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Self-heal: if access is on but MIUI dropped the connection, ask for a rebind.
        if (listenerEnabled() && !PaymentListenerService.connected) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, PaymentListenerService.class));
            } catch (RuntimeException ignored) {}
        }
        refresh();
        root.postDelayed(this::refresh, 1500);
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        TextView title = text("UPI Voice", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Speaks out every UPI payment received on this phone — like a shop soundbox.", 14));

        section("1. Setup  (make everything ✅)");
        stNotif = row("Turn on", v -> openNotificationAccess());
        stSms = row("Allow", v -> requestSms());
        stBattery = row("Fix", v -> openBatteryOptimization());
        stListening = text("", 14);
        stListening.setPadding(0, dp(6), 0, 0);
        root.addView(stListening);

        if (isXiaomi()) {
            section("MIUI / Poco — keep it alive");
            TextView a = row("Open", v -> openMiuiAutostart());
            a.setText("Autostart → turn ON for UPI Voice");
            TextView b = row("Open", v -> openMiuiBatterySaver());
            b.setText("Battery saver → \"No restrictions\"");
            root.addView(text("Last step: open Recent apps, long-press UPI Voice and tap the 🔒 lock icon.", 14));
        }

        section("2. Announcement");
        root.addView(toggle("Announcements ON", Prefs.enabled(this), on -> Prefs.putBool(this, "enabled", on)));

        root.addView(text("Language", 15));
        RadioGroup rg = new RadioGroup(this);
        String[] codes = {"bn", "hi", "en"};
        String[] labels = {"বাংলা  (Bengali)", "हिन्दी  (Hindi)", "English"};
        int checked = -1;
        for (int i = 0; i < codes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            rb.setTextSize(16);
            rb.setTag(codes[i]);
            rg.addView(rb);
            if (codes[i].equals(Prefs.lang(this))) checked = rb.getId();
        }
        if (checked != -1) rg.check(checked);
        rg.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = g.findViewById(id);
            if (rb == null) return;
            Prefs.putString(this, "lang", (String) rb.getTag());
            Prefs.putBool(this, "lang_ok", true);
            Announcer.get(this).settingsChanged();
            refresh();
        });
        root.addView(rg);

        stVoice = text("", 13);
        stVoice.setTextColor(RED);
        root.addView(stVoice);
        btnVoice = button("Install voice data", v -> openTtsInstall());
        root.addView(btnVoice);

        final TextView volLabel = text("", 15);
        volLabel.setPadding(0, dp(12), 0, 0);
        root.addView(volLabel);
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(Prefs.volume(this));
        volLabel.setText(volumeLabel(Prefs.volume(this)));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { volLabel.setText(volumeLabel(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { Prefs.putInt(MainActivity.this, "volume", s.getProgress()); }
        });
        root.addView(vol);

        root.addView(toggle("Speak even when phone is on silent", Prefs.alarm(this), on -> {
            Prefs.putBool(this, "alarm", on);
            Announcer.get(this).settingsChanged();
        }));
        root.addView(toggle("Say it twice", Prefs.repeat(this), on -> Prefs.putBool(this, "repeat", on)));

        Button test = button("▶  Test: ₹500 received", v -> {
            Announcer.get(this).test(50000);
            root.postDelayed(this::refresh, 3000);
        });
        test.setTextSize(18);
        root.addView(test);

        section("Recent payments announced");
        logView = text("", 14);
        root.addView(logView);
    }

    private void refresh() {
        boolean notif = listenerEnabled();
        boolean sms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());

        status(stNotif, notif, "Notification access (GPay, PhonePe, Paytm…)");
        status(stSms, sms, "Bank SMS (most reliable)");
        status(stBattery, battery, "Battery: unrestricted");

        if (!notif && !sms) {
            stListening.setText("⚠ Not listening yet — finish the setup above.");
            stListening.setTextColor(RED);
        } else if (notif && !PaymentListenerService.connected) {
            stListening.setText("⏳ Connecting… if this stays, turn notification access OFF and ON again.");
            stListening.setTextColor(Color.DKGRAY);
        } else {
            stListening.setText("🔊 Listening for payments.");
            stListening.setTextColor(GREEN);
        }

        boolean ok = Prefs.langOk(this);
        stVoice.setVisibility(ok ? View.GONE : View.VISIBLE);
        btnVoice.setVisibility(ok ? View.GONE : View.VISIBLE);
        stVoice.setText("⚠ This language's voice isn't installed, so it is speaking English. "
                + "Tap below → Speech Services by Google → install Bengali / Hindi, then test again.");

        String log = Prefs.log(this);
        logView.setText(log.isEmpty() ? "None yet." : log);
    }

    // ------------------------------------------------------------------ actions

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName() + "/");
    }

    private void openNotificationAccess() {
        if (!open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) openAppDetails();
        Toast.makeText(this, "Find \"UPI Voice\" and turn it ON", Toast.LENGTH_LONG).show();
    }

    private void requestSms() {
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, REQ_SMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_SMS && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Allow SMS in App info → Permissions", Toast.LENGTH_LONG).show();
            openAppDetails();
        }
        refresh();
    }

    private void openBatteryOptimization() {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        if (!open(i) && !open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails();
    }

    private void openMiuiAutostart() {
        Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (!open(i)) openAppDetails();
    }

    private void openMiuiBatterySaver() {
        Intent i = new Intent().setComponent(new ComponentName("com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", getPackageName())
                .putExtra("package_label", getString(R.string.app_name));
        if (!open(i)) openAppDetails();
    }

    private void openTtsInstall() {
        if (!open(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                && !open(new Intent("com.android.settings.TTS_SETTINGS"))) {
            Toast.makeText(this, "Settings → Additional settings → Languages → Text-to-speech", Toast.LENGTH_LONG).show();
        }
    }

    private void openAppDetails() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private boolean open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        return m.contains("xiaomi") || m.contains("poco") || m.contains("redmi");
    }

    // ------------------------------------------------------------------ view helpers

    private interface OnToggle { void set(boolean on); }

    private String volumeLabel(int v) {
        return v == 0 ? "Loudness: use phone's current volume" : "Loudness during announcement: " + v + "%";
    }

    private void section(String s) {
        TextView t = text(s, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        root.addView(t);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF202124);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** A status line with an action button on the right. Returns the label. */
    private TextView row(String action, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("", 15);
        r.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(button(action, l));
        root.addView(r);
        return label;
    }

    private void status(TextView t, boolean ok, String label) {
        t.setText((ok ? "✅  " : "❌  ") + label);
        t.setTextColor(ok ? GREEN : RED);
    }

    private Switch toggle(String label, boolean on, OnToggle cb) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(16);
        s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, c) -> cb.set(c));
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
