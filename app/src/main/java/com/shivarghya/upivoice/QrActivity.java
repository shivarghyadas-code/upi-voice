package com.shivarghya.upivoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * "UPI QR" — the customer-facing screen. Shows the QR + UPI ID; the shopkeeper can type the amount
 * so the customer doesn't have to. The moment UPI Voice detects the payment, this screen flashes
 * "✓ ₹X received" (same process, same instant as the voice).
 */
public class QrActivity extends Activity implements PaymentBus.Listener {

    private static final int REQ_PHOTO = 21;
    private static final int GREEN = 0xFF1B8A5A, ORANGE = 0xFFE65100;

    private List<QrStore.Entry> entries;
    private int index;
    private long expected;            // amount in paise put into the QR, 0 = any amount

    private TextView tvName, tvVpa, tvAmount, tvStatus, ovIcon, ovAmount, ovText;
    private ImageView ivQr;
    private LinearLayout overlay, content, nav;
    private Button btnAmount, btnClear;
    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;                     // bright screen = faster scanning
        getWindow().setAttributes(lp);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaymentBus.add(this);
        Announcer.get(this).warmUp();
        entries = QrStore.load(this);
        index = Math.max(0, Math.min(Prefs.sp(this).getInt("qr_index", 0), entries.size() - 1));
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PaymentBus.remove(this);
    }

    // ------------------------------------------------------------------ payment received

    @Override
    public void onPayment(long paise, String source) {
        boolean ok = expected == 0 || paise == expected;
        overlay.setBackgroundColor(ok ? GREEN : ORANGE);
        ovIcon.setText(ok ? "✓" : "!");
        ovAmount.setText(Speech.formatRupees(paise));
        ovText.setText(ok ? "received" : "received\n(expected " + Speech.formatRupees(expected) + ")");
        overlay.setVisibility(View.VISIBLE);
        overlay.removeCallbacks(hideOverlay);
        overlay.postDelayed(hideOverlay, 6000);
        if (ok && expected > 0) {      // ready for the next customer
            expected = 0;
            render();
        }
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(16);
        content.setPadding(p, dp(20), p, p);

        TextView head = text("Scan & Pay with any UPI app", 15, 0xFF5F6368);
        content.addView(head);
        tvName = text("", 26, 0xFF202124);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(tvName);
        tvVpa = text("", 16, 0xFF3C4043);
        tvVpa.setTextIsSelectable(true);
        content.addView(tvVpa);

        ivQr = new ImageView(this);
        int side = qrSide();
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(side, side);
        qlp.topMargin = dp(10);
        qlp.bottomMargin = dp(6);
        content.addView(ivQr, qlp);

        tvAmount = text("", 30, GREEN);
        tvAmount.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(tvAmount);
        tvStatus = text("", 14, 0xFF5F6368);
        tvStatus.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        content.addView(tvStatus);

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(10), 0, 0);
        Button prev = button("◀", v -> move(-1));
        btnAmount = button("₹ Amount", v -> askAmount());
        btnClear = button("Any amount", v -> { expected = 0; render(); });
        Button next = button("▶", v -> move(1));
        nav.addView(prev);
        nav.addView(btnAmount);
        nav.addView(btnClear);
        nav.addView(next);
        content.addView(nav);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(button("⚙  Manage UPI IDs / QR", v -> manage()));
        bottom.addView(button("📜 History", v -> startActivity(new Intent(this, HistoryActivity.class))));
        content.addView(bottom);
        Button chk = button("✔  Check payment (works offline)", v -> startActivity(
                new Intent(this, PaymentCheckActivity.class).putExtra("paise", expected)));
        content.addView(chk);

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(v -> overlay.setVisibility(View.GONE));
        ovIcon = text("✓", 110, Color.WHITE);
        ovAmount = text("", 64, Color.WHITE);
        ovAmount.setTypeface(Typeface.DEFAULT_BOLD);
        ovText = text("", 30, Color.WHITE);
        overlay.addView(ovIcon);
        overlay.addView(ovAmount);
        overlay.addView(ovText);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void render() {
        boolean voiceReady = notificationAccess()
                || checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean online = PaymentCheckActivity.online(this);
        tvStatus.setText(!voiceReady ? "⚠ Voice not set up — tap here to open UPI Voice"
                : online ? "🔊 Payment is confirmed on this screen and by voice"
                : "📴 No internet — payment is still confirmed from the bank SMS");
        tvStatus.setTextColor(voiceReady ? 0xFF5F6368 : ORANGE);

        if (entries.isEmpty()) {
            tvName.setText("Add your UPI ID");
            tvVpa.setText("Tap ⚙ below to add a UPI ID (e.g. name@sbi) or a photo of your QR sticker.");
            ivQr.setImageDrawable(null);
            tvAmount.setText("");
            nav.setVisibility(View.GONE);
            return;
        }
        nav.setVisibility(View.VISIBLE);
        QrStore.Entry e = entries.get(index);
        tvName.setText(e.name.isEmpty() ? "Scan to pay" : e.name);
        String sub = e.isPhoto() ? "" : e.vpa;
        if (!e.label.isEmpty()) sub = e.label + (sub.isEmpty() ? "" : "  ·  " + sub);
        if (entries.size() > 1) sub += "   (" + (index + 1) + "/" + entries.size() + ")";
        tvVpa.setText(sub);

        btnAmount.setEnabled(!e.isPhoto());
        btnClear.setEnabled(!e.isPhoto() && expected > 0);
        try {
            Bitmap bm = e.isPhoto() ? QrStore.loadPhoto(this, e.image, qrSide())
                    : QrStore.qr(QrStore.upiUri(e, expected), qrSide());
            ivQr.setImageBitmap(bm);
        } catch (Exception ex) {
            ivQr.setImageDrawable(null);
            Toast.makeText(this, "Could not show QR: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (e.isPhoto()) tvAmount.setText("");
        else tvAmount.setText(expected > 0 ? "Pay " + Speech.formatRupees(expected) : "Any amount");
    }

    private void move(int d) {
        if (entries.isEmpty()) return;
        index = (index + d + entries.size()) % entries.size();
        Prefs.putInt(this, "qr_index", index);
        render();
    }

    // ------------------------------------------------------------------ amount

    private void askAmount() {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setTextSize(28);
        in.setHint("₹ 0");
        new AlertDialog.Builder(this)
                .setTitle("Amount for this customer")
                .setView(pad(in))
                .setPositiveButton("Show QR", (d, w) -> {
                    try {
                        long p = new BigDecimal(in.getText().toString().trim())
                                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                        if (p > 0) { expected = p; render(); }
                    } catch (Exception ex) {
                        Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        in.requestFocus();
        in.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(in, 0);
        }, 150);
    }

    // ------------------------------------------------------------------ manage list

    private void manage() {
        String[] items = new String[entries.size()];
        for (int i = 0; i < items.length; i++) {
            QrStore.Entry e = entries.get(i);
            items[i] = "🗑  " + (e.label.isEmpty() ? "" : e.label + " · ")
                    + (e.isPhoto() ? "QR photo" : e.vpa) + (e.name.isEmpty() ? "" : "  (" + e.name + ")");
        }
        new AlertDialog.Builder(this)
                .setTitle(entries.isEmpty() ? "Add a payment method" : "Tap one to remove it")
                .setItems(items, (d, which) -> confirmDelete(which))
                .setPositiveButton("+ UPI ID", (d, w) -> addVpa("", "", ""))
                .setNeutralButton("+ QR photo", (d, w) -> pickPhoto())
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmDelete(int i) {
        new AlertDialog.Builder(this)
                .setMessage("Remove this payment method?")
                .setPositiveButton("Remove", (d, w) -> {
                    QrStore.Entry e = entries.remove(i);
                    if (e.isPhoto()) QrStore.deletePhoto(this, e.image);
                    QrStore.save(this, entries);
                    index = 0;
                    Prefs.putInt(this, "qr_index", 0);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addVpa(String name0, String vpa0, String label0) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        EditText name = field("Name shown to customer (e.g. Jayanta Das)", name0, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText vpa = field("UPI ID (e.g. 98xxxxxx@sbi)", vpa0, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText label = field("Bank / app (optional, e.g. SBI)", label0, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        f.addView(name);
        f.addView(vpa);
        f.addView(label);
        new AlertDialog.Builder(this)
                .setTitle("Add UPI ID")
                .setView(pad(f))
                .setPositiveButton("Save", (d, w) -> {
                    String v = vpa.getText().toString().trim();
                    if (!QrStore.validVpa(v)) {
                        Toast.makeText(this, "That UPI ID doesn't look right (it must contain @)", Toast.LENGTH_LONG).show();
                        addVpa(name.getText().toString(), v, label.getText().toString());
                        return;
                    }
                    QrStore.Entry e = new QrStore.Entry();
                    e.name = name.getText().toString().trim();
                    e.vpa = v;
                    e.label = label.getText().toString().trim();
                    entries.add(e);
                    QrStore.save(this, entries);
                    index = entries.size() - 1;
                    Prefs.putInt(this, "qr_index", index);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_PHOTO);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            String file = QrStore.importPhoto(this, data.getData());
            EditText label = field("Name (e.g. PhonePe QR)", "", InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            new AlertDialog.Builder(this)
                    .setTitle("Name this QR")
                    .setView(pad(label))
                    .setPositiveButton("Save", (d, w) -> {
                        QrStore.Entry e = new QrStore.Entry();
                        e.name = "";
                        e.label = label.getText().toString().trim();
                        e.image = file;
                        entries = QrStore.load(this);
                        entries.add(e);
                        QrStore.save(this, entries);
                        index = entries.size() - 1;
                        Prefs.putInt(this, "qr_index", index);
                        render();
                    })
                    .setNegativeButton("Cancel", (d, w) -> QrStore.deletePhoto(this, file))
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not add photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean notificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName() + "/");
    }

    private int qrSide() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels - dp(40), (int) (dm.heightPixels * 0.5f));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private EditText field(String hint, String value, int extraType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | extraType);
        return e;
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = dp(20);
        f.setPadding(p, dp(8), p, 0);
        f.addView(v);
        return f;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
