package com.shivarghya.upivoice;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

/**
 * App lock using the phone's own fingerprint / PIN / pattern. Once unlocked, the app stays
 * unlocked for 2 minutes of use. Payment detection and voice keep working while locked.
 */
final class AppLock {
    private AppLock() {}

    static final int REQ_UNLOCK = 9901;
    private static final long GRACE_MS = 2 * 60_000L;
    private static long unlockedUntil;
    private static boolean prompting;

    static boolean enabled(Context c) {
        return Prefs.sp(c).getBoolean("app_lock", true) && deviceSecure(c);
    }

    static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    static boolean unlocked() { return SystemClock.elapsedRealtime() < unlockedUntil; }

    private static void markUnlocked() { unlockedUntil = SystemClock.elapsedRealtime() + GRACE_MS; }

    /** Block screenshots / screen recording / Recents preview, and ignore taps through overlays. */
    static void protect(Activity a) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        View content = a.findViewById(android.R.id.content);
        if (content != null) content.setFilterTouchesWhenObscured(true);
    }

    /**
     * Ask for fingerprint / screen lock if needed, then run {@code then}.
     * @param closeOnFail true = close the screen if the user cancels (whole-screen lock);
     *                    false = just don't run the action
     */
    static void guard(Activity a, boolean closeOnFail, Runnable then) {
        View content = a.findViewById(android.R.id.content);
        if (!enabled(a) || unlocked()) {
            if (unlocked()) markUnlocked();          // extend while in use
            if (content != null) content.setVisibility(View.VISIBLE);
            if (then != null) then.run();
            return;
        }
        if (closeOnFail && content != null) content.setVisibility(View.INVISIBLE);
        if (prompting) return;
        prompting = true;

        Runnable ok = () -> {
            prompting = false;
            markUnlocked();
            if (content != null) content.setVisibility(View.VISIBLE);
            if (then != null) then.run();
        };
        Runnable fail = () -> {
            prompting = false;
            if (closeOnFail) a.finish();
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(a)
                    .setTitle("Unlock UPI Voice")
                    .setSubtitle("Use your fingerprint or phone screen lock");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            try {
                b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { ok.run(); }
                            @Override public void onAuthenticationError(int code, CharSequence msg) { fail.run(); }
                        });
            } catch (RuntimeException e) {
                prompting = false;
                ok.run();      // lock can't be shown on this phone: don't lock the owner out
            }
        } else {
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent("Unlock UPI Voice", "Use your phone screen lock");
            if (i == null) { prompting = false; ok.run(); return; }
            pendingOk = ok;
            pendingFail = fail;
            a.startActivityForResult(i, REQ_UNLOCK);
        }
    }

    private static Runnable pendingOk, pendingFail;

    /** For Android 8–9 only: forward onActivityResult here. */
    static void onActivityResult(int req, int res) {
        if (req != REQ_UNLOCK) return;
        Runnable r = res == Activity.RESULT_OK ? pendingOk : pendingFail;
        pendingOk = pendingFail = null;
        if (r != null) r.run();
    }
}
