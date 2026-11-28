package com.shivarghya.upivoice;

import android.app.Application;

public class App extends Application {

    /** A new app installed from outside the Play Store that can read SMS/OTPs or the screen → warn at once. */
    private void watchNewApps() {
        android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_ADDED);
        f.addDataScheme("package");
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                if (i.getBooleanExtra(android.content.Intent.EXTRA_REPLACING, false) || i.getData() == null) return;
                String pkg = i.getData().getSchemeSpecificPart();
                Safety.invalidate();
                new Thread(() -> {
                    String can = Safety.assess(c, pkg);
                    if (can == null) return;
                    String label = pkg;
                    try {
                        android.content.pm.PackageManager pm = c.getPackageManager();
                        label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                    } catch (Exception ignored) {}
                    final String l = label;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ScamAlert.dangerousApp(c, pkg, l, can));
                }).start();
            }
        };
        try { registerReceiver(r, f); } catch (RuntimeException ignored) {}
    }
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        SmsWatcher.start(this);
        for (String own : Safety.OWN_APPS) {
            if (!Prefs.sp(this).getStringSet("trusted_apps", new java.util.HashSet<>()).contains(own)) Safety.trust(this, own);
        }
        watchNewApps();
    }
}
