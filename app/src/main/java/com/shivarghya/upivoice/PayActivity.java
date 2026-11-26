package com.shivarghya.upivoice;

import android.Manifest;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Scan & Pay": scans any UPI QR (also in low light / from a photo) or takes a UPI ID / mobile
 * number, checks it for scams, then hands the payment to the user's UPI app (which asks the PIN).
 * Locked with fingerprint / phone lock; screenshots & screen recording are blocked here.
 */
public class PayActivity extends ComponentActivity {

    private static final int REQ_CAM = 41, REQ_PHOTO = 42, REQ_PAY = 43, REQ_LOCK = 44;
    private static final long UNLOCK_VALID_MS = 2 * 60_000L;
    private static final int RED = 0xFFC62828, ORANGE = 0xFFE65100, AMBER = 0xFFF9A825, GREEN = 0xFF1B8A5A;

    private static long unlockedAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService exec;
    private BarcodeScanner scanner;
    private ProcessCameraProvider provider;
    private Camera camera;

    private PreviewView preview;
    private TextView hint, light;
    private Button torchBtn;
    private View lockView;

    private volatile boolean busy;           // a dialog is open → don't scan
    private volatile long darkSince;
    private volatile boolean autoLightDone;
    private boolean torchOn, authInProgress;
    private String pendingPayee;
    private long pendingPaise;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // No screenshots / screen recording / screen-sharing of this screen.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        exec = Executors.newSingleThreadExecutor();
        buildUi();
        try {
            scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
        } catch (Throwable t) {
            CrashLog.save(this, "Scan & Pay scanner", t);
            hint.setText("QR scanner failed to start. Use \"UPI ID / No.\" below.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (System.currentTimeMillis() - unlockedAt < UNLOCK_VALID_MS) ready();
        else if (!authInProgress) {
            lockView.setVisibility(View.VISIBLE);
            authenticate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        if (scanner != null) scanner.close();
    }

    private void ready() {
        lockView.setVisibility(View.GONE);
        new Thread(() -> Safety.riskyApps(getApplicationContext())).start();   // warm the check before paying
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { startCamera(); }
            catch (Throwable t) { CrashLog.save(this, "Scan & Pay camera", t); hint.setText("Camera failed to start."); }
        }
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    }

    // ================================================================== app lock

    private void authenticate() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent i = null;
        try {
            if (km != null && km.isDeviceSecure())
                i = km.createConfirmDeviceCredentialIntent("Unlock Scan & Pay", "Use your fingerprint, PIN or pattern");
        } catch (RuntimeException ignored) {}
        if (i == null) {
            // No screen lock on this phone (or the lock screen can't be shown): open, with a tip.
            unlockedAt = System.currentTimeMillis();
            if (km == null || !km.isDeviceSecure())
                Toast.makeText(this, "Tip: set a screen lock (PIN/fingerprint) so nobody else can pay from this phone",
                        Toast.LENGTH_LONG).show();
            ready();
            return;
        }
        authInProgress = true;
        try {
            startActivityForResult(i, REQ_LOCK);
        } catch (RuntimeException e) {
            authInProgress = false;
            unlockedAt = System.currentTimeMillis();
            ready();
        }
    }

    // ================================================================== camera + low light

    private void startCamera() {
        if (provider != null) return;
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis a = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                a.setAnalyzer(exec, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, a);
                torchBtn.setEnabled(camera.getCameraInfo().hasFlashUnit());
            } catch (Throwable e) {
                CrashLog.save(this, "Scan & Pay camera", e);
                hint.setText("Camera not available. Use \"Photo\" or \"UPI ID / No.\" below.");
            }
        }, main::post);
    }

    @ExperimentalGetImage
    private void analyze(ImageProxy img) {
        if (busy || scanner == null) { img.close(); return; }
        checkLight(img);
        Image media = img.getImage();
        if (media == null) { img.close(); return; }
        InputImage in = InputImage.fromMediaImage(media, img.getImageInfo().getRotationDegrees());
        scanner.process(in)
                .addOnSuccessListener(list -> {
                    if (busy) return;
                    for (Barcode b : list) {
                        String raw = b.getRawValue();
                        if (raw != null && !raw.isEmpty()) {
                            busy = true;
                            onScanned(raw);
                            return;
                        }
                    }
                })
                .addOnCompleteListener(t -> img.close());
    }

    /** Average brightness of the camera image; if dark for 0.7 s, switch on the torch and brighten exposure. */
    private void checkLight(ImageProxy img) {
        if (autoLightDone) return;
        ByteBuffer y = img.getPlanes()[0].getBuffer();
        int n = y.remaining();
        long sum = 0;
        int cnt = 0;
        for (int i = 0; i < n; i += 97) { sum += y.get(i) & 0xFF; cnt++; }
        int avg = cnt > 0 ? (int) (sum / cnt) : 255;
        long now = System.currentTimeMillis();
        if (avg < 50) {
            if (darkSince == 0) darkSince = now;
            else if (now - darkSince > 700) {
                autoLightDone = true;
                main.post(this::lowLightBoost);
            }
        } else {
            darkSince = 0;
        }
    }

    private void lowLightBoost() {
        if (camera == null) return;
        if (camera.getCameraInfo().hasFlashUnit()) {
            setTorch(true);
            light.setText("🌙 Low light — torch turned on");
        } else {
            light.setText("🌙 Low light — brightening");
        }
        try {
            ExposureState es = camera.getCameraInfo().getExposureState();
            if (es.isExposureCompensationSupported())
                camera.getCameraControl().setExposureCompensationIndex(es.getExposureCompensationRange().getUpper());
        } catch (RuntimeException ignored) {}
    }

    private void setTorch(boolean on) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = on;
        camera.getCameraControl().enableTorch(on);
        torchBtn.setText(on ? "🔦 Off" : "🔦 Torch");
    }

    private void resume() {
        busy = false;
    }

    // ================================================================== what was scanned

    private void onScanned(String raw) {
        UpiRequest r = UpiRequest.parse(raw);
        if (r == null) {
            ScamCheck.Warning w = ScamCheck.notUpi(raw);
            if (w != null) {
                blocked(w.level == ScamCheck.Level.BLOCK ? "⛔ Blocked: not a safe payment QR" : "⚠ AutoPay QR — not opened",
                        w.text + (w.level == ScamCheck.Level.BLOCK ? "" : "\n\nScan & Pay does not open AutoPay/mandate QRs."));
                return;
            }
            String t = raw.trim();
            if (UpiRequest.VPA.matcher(t).matches() || digits(t).length() >= 10) {
                askManual(t);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Not a payment QR")
                    .setMessage("This QR only contains text:\n\n" + t)
                    .setPositiveButton("OK", null)
                    .setOnDismissListener(d -> resume())
                    .show();
            return;
        }
        long amt = r.qrAmount();
        if (amt > 0) confirm(r, amt);
        else askAmount(r);
    }

    private void blocked(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK, don't pay", null)
                .setOnDismissListener(d -> resume())
                .show();
    }

    // ================================================================== amount / manual entry

    private void askAmount(UpiRequest r) {
        LinearLayout f = vbox();
        f.addView(text("To: " + r.payee(), 16, 0xFF202124, true));
        if (!r.name().isEmpty()) f.addView(text("Name in QR: " + r.name(), 14, 0xFF5F6368, false));
        EditText amt = amountField();
        f.addView(amt);
        new AlertDialog.Builder(this)
                .setTitle("Enter amount")
                .setView(pad(f))
                .setPositiveButton("Next", (d, w) -> {
                    long p = parseAmount(amt.getText().toString());
                    if (p > 0) confirm(r, p);
                    else { toast("Enter a valid amount"); resume(); }
                })
                .setNegativeButton("Cancel", (d, w) -> resume())
                .setOnCancelListener(d -> resume())
                .show();
        focus(amt);
    }

    /** Pay to a UPI ID or a mobile number typed by hand (or found in a QR). */
    private void askManual(String prefill) {
        LinearLayout f = vbox();
        EditText to = new EditText(this);
        to.setHint("UPI ID (name@bank) or 10-digit mobile no.");
        to.setSingleLine(true);
        to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (prefill != null) to.setText(prefill);
        EditText amt = amountField();
        EditText note = new EditText(this);
        note.setHint("Note (optional)");
        note.setSingleLine(true);
        f.addView(to);
        f.addView(amt);
        f.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Pay to UPI ID / mobile number")
                .setView(pad(f))
                .setPositiveButton("Next", (d, w) -> {
                    String who = to.getText().toString().trim();
                    long p = parseAmount(amt.getText().toString());
                    if (p <= 0) { toast("Enter a valid amount"); resume(); return; }
                    if (UpiRequest.VPA.matcher(who).matches()) {
                        confirm(UpiRequest.manual(who, note.getText().toString()), p);
                    } else {
                        String num = mobile(who);
                        if (num == null) { toast("Enter a UPI ID like name@sbi or a 10-digit mobile number"); resume(); return; }
                        UpiRequest m = UpiRequest.manual(num + "@upi", note.getText().toString());
                        m.p.put("_mobile", num);
                        confirm(m, p);
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> resume())
                .setOnCancelListener(d -> resume())
                .show();
    }

    // ================================================================== scam check + confirm

    private void confirm(UpiRequest r, long paise) {
        List<ScamCheck.Warning> ws = ScamCheck.check(r, paise, phoneFacts(r.payee()));
        ScamCheck.Level worst = ScamCheck.worst(ws);

        LinearLayout f = vbox();
        TextView amount = text(Speech.formatRupees(paise), 34, 0xFF202124, true);
        f.addView(amount);
        f.addView(text("To: " + (r.mobile().isEmpty() ? r.payee() : "mobile " + r.mobile()), 16, 0xFF202124, true));
        if (!r.isMerchant())
            f.addView(text("Personal payment: you'll finish it inside your UPI app (PhonePe / Google Pay block "
                    + "personal payments opened from other apps).", 13, 0xFF5F6368, false));
        for (ScamCheck.Warning w : ws) f.addView(warningBox(w));
        f.addView(text("Your UPI app will ask for your UPI PIN. Check the name it shows before entering the PIN. "
                + "UPI limits are set by your bank (usually ₹1 lakh per day).", 13, 0xFF5F6368, false));

        CheckBox sure = new CheckBox(this);
        TextView countdown = text("", 13, ORANGE, false);
        if (worst == ScamCheck.Level.DANGER) {
            sure.setText("I have checked: this is NOT a scam, and nobody on a call told me to pay");
            f.addView(sure);
            f.addView(countdown);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(pad(f));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(worst == ScamCheck.Level.BLOCK ? "⛔ Payment blocked"
                        : worst == ScamCheck.Level.DANGER ? "⚠ Possible SCAM — stop and check" : "Confirm payment")
                .setView(sv)
                .setNegativeButton(worst == ScamCheck.Level.BLOCK ? "Close" : "Cancel", (d, w) -> resume())
                .setOnCancelListener(d -> resume());
        if (worst != ScamCheck.Level.BLOCK) {
            b.setPositiveButton("Pay with UPI app", (d, w) -> launchPay(r, paise));
            b.setNeutralButton("No internet: *99#", (d, w) -> ussd(r.mobile().isEmpty() ? r.payee() : r.mobile(), paise));
        }
        AlertDialog dlg = b.show();

        if (worst == ScamCheck.Level.DANGER) {
            Button pay = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            Button off = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            pay.setEnabled(false);
            off.setEnabled(false);
            final int[] left = {5};
            Runnable tick = new Runnable() {
                @Override public void run() {
                    if (!dlg.isShowing()) return;
                    if (left[0] > 0) {
                        countdown.setText("Take a moment… " + left[0] + " s");
                        left[0]--;
                        main.postDelayed(this, 1000);
                    } else {
                        countdown.setText(sure.isChecked() ? "" : "Tick the box above to continue");
                        pay.setEnabled(sure.isChecked());
                        off.setEnabled(sure.isChecked());
                    }
                }
            };
            main.post(tick);
            sure.setOnCheckedChangeListener((c, on) -> {
                if (left[0] <= 0) {
                    pay.setEnabled(on);
                    off.setEnabled(on);
                    countdown.setText(on ? "" : "Tick the box above to continue");
                }
            });
        }
    }

    private View warningBox(ScamCheck.Warning w) {
        int color;
        String icon;
        switch (w.level) {
            case BLOCK:  color = RED;    icon = "⛔ "; break;
            case DANGER: color = RED;    icon = "⚠ ";  break;
            case WARN:   color = ORANGE; icon = "⚠ ";  break;
            default:     color = 0xFF5F6368; icon = "ℹ "; break;
        }
        TextView t = text(icon + w.text, 14, color, w.level.ordinal() >= ScamCheck.Level.DANGER.ordinal());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), color);
        bg.setColor(w.level == ScamCheck.Level.INFO ? 0xFFF1F3F4 : 0x14000000 | (color & 0x00FFFFFF));
        t.setBackground(bg);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Facts about the phone right now that scammers rely on. */
    private ScamCheck.Phone phoneFacts(String payee) {
        ScamCheck.Phone ph = new ScamCheck.Phone();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            int mode = am.getMode();
            ph.inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        }
        PackageManager pm = getPackageManager();
        String[][] remote = {
                {"com.anydesk.anydeskandroid", "AnyDesk"}, {"com.anydesk.adcontrol.ad1", "AnyDesk plugin"},
                {"com.teamviewer.quicksupport.market", "TeamViewer QuickSupport"},
                {"com.teamviewer.teamviewer.market.mobile", "TeamViewer"}, {"com.carriez.flutter_hbb", "RustDesk"},
                {"com.sand.airdroid", "AirDroid"}, {"com.sand.airdroidbiz", "AirDroid Business"},
                {"com.sand.airmirror", "AirMirror"}, {"com.rsupport.mobizen.sec", "Mobizen"},
                {"com.splashtop.streamer.addon.knox", "Splashtop"}, {"com.remotepc.android", "RemotePC"}};
        for (String[] r : remote) {
            try { pm.getApplicationInfo(r[0], 0); ph.remoteApps.add(r[1]); }
            catch (PackageManager.NameNotFoundException ignored) {}
        }
        String acc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (acc != null) {
            Set<String> seen = new HashSet<>();
            for (String s : acc.split(":")) {
                int slash = s.indexOf('/');
                if (slash <= 0) continue;
                String pkg = s.substring(0, slash);
                if (pkg.equals(getPackageName()) || isSystemReader(pkg) || !seen.add(pkg)) continue;
                String label = pkg;
                try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
                catch (PackageManager.NameNotFoundException ignored) {}
                ph.screenReaders.add(label);
            }
        }
        for (String[] a : Safety.riskyApps(this)) ph.riskyApps.add(a[1]);
        ph.newPayee = !Prefs.sp(this).getStringSet("payees", new HashSet<>())
                .contains(payee.toLowerCase(Locale.ENGLISH));
        return ph;
    }

    private static boolean isSystemReader(String pkg) {
        return pkg.startsWith("com.google.android.marvin") || pkg.startsWith("com.google.android.accessibility")
                || pkg.startsWith("com.google.android.apps.accessibility") || pkg.startsWith("com.android.")
                || pkg.startsWith("com.miui.") || pkg.startsWith("com.xiaomi.") || pkg.startsWith("com.samsung.")
                || pkg.startsWith("com.google.android.tts");
    }

    // ================================================================== pay

    private UpiRequest pendingReq;

    private void launchPay(UpiRequest r, long paise) {
        pendingReq = r;
        pendingPayee = r.payee();
        pendingPaise = paise;
        if (!r.isMerchant()) {            // personal UPI ID / mobile → would be declined by the UPI app
            payInsideApp(r, paise);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(r.toUri(paise)));
        unlockedAt = System.currentTimeMillis();
        try {
            startActivityForResult(Intent.createChooser(i, "Pay " + Speech.formatRupees(paise) + " with"), REQ_PAY);
        } catch (ActivityNotFoundException e) {
            toast("No UPI app found on this phone");
            resume();
        }
    }

    /**
     * Personal payments: copy the UPI ID / mobile number, then open the user's UPI app to pay there.
     * The scam checks have already been done.
     */
    private void payInsideApp(UpiRequest r, long paise) {
        boolean mob = !r.mobile().isEmpty();
        String target = mob ? r.mobile() : r.payee();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(mob ? "Mobile number" : "UPI ID", target));

        LinearLayout f = vbox();
        f.addView(text("✅ Scam check done", 15, GREEN, true));
        f.addView(text("Copied: " + target, 19, 0xFF202124, true));
        f.addView(text("Amount: " + Speech.formatRupees(paise), 19, 0xFF202124, true));
        f.addView(text("\n1. Tap your UPI app below\n"
                + "2. Choose \"" + (mob ? "To mobile number" : "Pay to UPI ID") + "\"\n"
                + "3. Long-press the box → Paste\n"
                + "4. Check the NAME it shows, enter " + Speech.formatRupees(paise) + ", then your UPI PIN\n", 15, 0xFF3C4043, false));
        final AlertDialog[] dlg = new AlertDialog[1];
        PackageManager pm = getPackageManager();
        int shown = 0;
        for (String[] a : Sources.installedApps(this)) {
            if (a[1].toLowerCase(Locale.ENGLISH).contains("business")) continue;   // business apps can't pay
            Intent launch = pm.getLaunchIntentForPackage(a[0]);
            if (launch == null) continue;
            Button b = new Button(this);
            b.setText("Open " + a[1]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                rememberPayee();
                unlockedAt = System.currentTimeMillis();
                if (dlg[0] != null) dlg[0].dismiss();
                try { startActivity(launch); } catch (RuntimeException e) { toast("Couldn't open " + a[1]); }
            });
            f.addView(b);
            shown++;
        }
        if (shown == 0) f.addView(text("No UPI app found on this phone.", 15, RED, true));
        ScrollView sv = new ScrollView(this);
        sv.addView(pad(f));
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Pay " + Speech.formatRupees(paise) + " in your UPI app")
                .setView(sv)
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> resume())
                .show();
    }

    /** No internet: official UPI over the phone signal (*99#). */
    private void ussd(String payee, long paise) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("UPI ID", payee));
        new AlertDialog.Builder(this)
                .setTitle("Pay without internet (*99#)")
                .setMessage("Works on any signal, no mobile data needed.\n\n"
                        + "1. Tap Call → choose \"Send Money\"\n"
                        + "2. Choose \"" + (payee.contains("@") ? "UPI ID" : "Mobile No.") + "\" and type: " + payee + " (copied)\n"
                        + "3. Amount: " + Speech.formatRupees(paise) + "\n"
                        + "4. Enter your UPI PIN\n\n"
                        + "Limit ₹5,000 per payment (set by NPCI). Your bank may charge a small fee.")
                .setPositiveButton("Call *99#", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*99#")))); }
                    catch (RuntimeException e) { toast("Dialer not available"); }
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> resume())
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOCK) {
            authInProgress = false;
            if (res == RESULT_OK) { unlockedAt = System.currentTimeMillis(); ready(); }
            else lockView.setVisibility(View.VISIBLE);
            return;
        }
        if (req == REQ_PHOTO) {
            if (res == RESULT_OK && data != null && data.getData() != null) scanPhoto(data.getData());
            return;
        }
        if (req != REQ_PAY) return;
        unlockedAt = System.currentTimeMillis();
        String resp = data == null ? null : data.getStringExtra("response");
        if (resp == null && data != null) resp = data.getStringExtra("Status");
        String status = status(resp);
        String title, msg;
        if ("SUCCESS".equals(status)) {
            rememberPayee();
            title = "✅ Payment successful";
            msg = Speech.formatRupees(pendingPaise) + " paid to " + pendingPayee + ".";
        } else if ("SUBMITTED".equals(status) || "PENDING".equals(status)) {
            rememberPayee();
            title = "⏳ Payment submitted";
            msg = "The bank is still processing it (slow network?). Check your UPI app in a few minutes.";
        } else if ("FAILURE".equals(status) || "FAILED".equals(status)) {
            title = "❌ Payment failed or cancelled";
            msg = "No money was sent. If money was debited, it is returned automatically by the bank.";
        } else {
            title = "Check your UPI app";
            msg = "The UPI app didn't report the result. Open it to see whether the payment went through.";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null)
                .setOnDismissListener(d -> resume());
        final boolean declined = !"SUCCESS".equals(status) && !"SUBMITTED".equals(status) && !"PENDING".equals(status);
        if (declined && pendingReq != null) {
            final UpiRequest r = pendingReq;
            final long p = pendingPaise;
            b.setNeutralButton("Pay inside the app instead", (d, w) -> main.post(() -> { busy = true; payInsideApp(r, p); }));
        }
        b.show();
    }

    private static String status(String resp) {
        if (resp == null) return null;
        for (String part : resp.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("status"))
                return part.substring(eq + 1).trim().toUpperCase(Locale.ENGLISH);
        }
        String u = resp.trim().toUpperCase(Locale.ENGLISH);
        return u.isEmpty() ? null : u;
    }

    private void rememberPayee() {
        if (pendingPayee == null) return;
        Set<String> s = new HashSet<>(Prefs.sp(this).getStringSet("payees", new HashSet<>()));
        s.add(pendingPayee.toLowerCase(Locale.ENGLISH));
        Prefs.sp(this).edit().putStringSet("payees", s).apply();
    }

    // ================================================================== photo

    private void pickPhoto() {
        busy = true;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(i, REQ_PHOTO); }
        catch (RuntimeException e) { toast("No gallery app"); resume(); }
    }

    private void scanPhoto(Uri uri) {
        if (scanner == null) { toast("QR scanner not available"); resume(); return; }
        try {
            InputImage in = InputImage.fromFilePath(this, uri);
            scanner.process(in).addOnSuccessListener(list -> {
                for (Barcode b : list) {
                    if (b.getRawValue() != null) { onScanned(b.getRawValue()); return; }
                }
                toast("No QR code found in this photo");
                resume();
            }).addOnFailureListener(e -> { toast("Couldn't read this photo"); resume(); });
        } catch (Exception e) {
            toast("Couldn't open this photo");
            resume();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_CAM) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else hint.setText("Camera permission is off. You can still use \"Photo\" or \"UPI ID / No.\"");
    }

    // ================================================================== UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        View frame = new View(this);
        GradientDrawable fd = new GradientDrawable();
        fd.setStroke(dp(3), Color.WHITE);
        fd.setCornerRadius(dp(18));
        frame.setBackground(fd);
        int side = (int) (getResources().getDisplayMetrics().widthPixels * 0.7f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(side, side, Gravity.CENTER);
        root.addView(frame, flp);

        LinearLayout top = vbox();
        top.setPadding(dp(16), dp(28), dp(16), dp(8));
        top.setBackgroundColor(0x88000000);
        hint = text("Point at any UPI QR — works in low light too", 17, Color.WHITE, true);
        light = text("🛡 Every QR is checked for scams before you pay", 13, 0xFFB9F6CA, false);
        top.addView(hint);
        top.addView(light);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xAA000000);
        bar.setPadding(dp(6), dp(8), dp(6), dp(20));
        torchBtn = barButton("🔦 Torch", v -> setTorch(!torchOn));
        bar.addView(torchBtn, weight());
        bar.addView(barButton("🖼 Photo", v -> pickPhoto()), weight());
        bar.addView(barButton("⌨ UPI ID / No.", v -> { busy = true; askManual(null); }), weight());
        bar.addView(barButton("📶 *99#", v -> { busy = true; askManualUssd(); }), weight());
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout lock = vbox();
        lock.setGravity(Gravity.CENTER);
        lock.setBackgroundColor(Color.WHITE);
        lock.setClickable(true);
        lock.addView(text("🔒", 64, 0xFF202124, false));
        lock.addView(text("Scan & Pay is locked", 20, 0xFF202124, true));
        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        unlock.setOnClickListener(v -> { if (!authInProgress) authenticate(); });
        lock.addView(unlock);
        lockView = lock;
        root.addView(lock, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    /** *99# straight from the bar: ask who and how much first. */
    private void askManualUssd() {
        LinearLayout f = vbox();
        EditText to = new EditText(this);
        to.setHint("UPI ID (name@bank)");
        to.setSingleLine(true);
        EditText amt = amountField();
        f.addView(to);
        f.addView(amt);
        new AlertDialog.Builder(this)
                .setTitle("Pay without internet (*99#)")
                .setView(pad(f))
                .setPositiveButton("Check & continue", (d, w) -> {
                    String who = to.getText().toString().trim();
                    long p = parseAmount(amt.getText().toString());
                    if (!UpiRequest.VPA.matcher(who).matches() || p <= 0) {
                        toast("Enter a valid UPI ID and amount");
                        resume();
                        return;
                    }
                    confirm(UpiRequest.manual(who, null), p);   // same scam checks; then choose *99#
                })
                .setNegativeButton("Cancel", (d, w) -> resume())
                .setOnCancelListener(d -> resume())
                .show();
    }

    // ================================================================== helpers

    private static String digits(String s) { return s.replaceAll("\\D", ""); }

    /** "+91 98765-43210" → "9876543210", or null. */
    private static String mobile(String s) {
        String d = digits(s);
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        return d.length() == 10 && "6789".indexOf(d.charAt(0)) >= 0 ? d : null;
    }

    private static long parseAmount(String s) {
        try {
            long p = new BigDecimal(s.trim().replace(",", "")).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            return p > 0 ? p : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private EditText amountField() {
        EditText e = new EditText(this);
        e.setHint("Amount ₹");
        e.setTextSize(24);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private void focus(EditText e) {
        e.requestFocus();
        e.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(e, 0);
        }, 150);
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), dp(4));
        f.addView(v);
        return f;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button barButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(4, 0, 4, 0);
        return lp;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
