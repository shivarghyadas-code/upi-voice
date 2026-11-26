package com.shivarghya.upivoice;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Phone safety checks against the common Indian cyber-scam setups. Runs offline. */
final class Safety {
    private Safety() {}

    static final class Finding {
        boolean danger;
        String title, detail;
        String action;      // LOCK, UNINSTALL, ACCESSIBILITY, NOTIF, ADMIN, DEV
        String pkg;
    }

    /** App stores — apps from these are not "sideloaded". */
    private static final Set<String> STORES = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.google.android.feedback", "com.xiaomi.mipicks", "com.xiaomi.market",
            "com.xiaomi.discover", "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket",
            "com.oppo.market", "com.heytap.market", "com.vivo.appstore", "com.oneplus.market"));

    static final String[][] REMOTE_APPS = {
            {"com.anydesk.anydeskandroid", "AnyDesk"}, {"com.anydesk.adcontrol.ad1", "AnyDesk plugin"},
            {"com.teamviewer.quicksupport.market", "TeamViewer QuickSupport"},
            {"com.teamviewer.teamviewer.market.mobile", "TeamViewer"}, {"com.carriez.flutter_hbb", "RustDesk"},
            {"com.sand.airdroid", "AirDroid"}, {"com.sand.airdroidbiz", "AirDroid Business"},
            {"com.sand.airmirror", "AirMirror"}, {"com.rsupport.mobizen.sec", "Mobizen"},
            {"com.splashtop.streamer.addon.knox", "Splashtop"}, {"com.remotepc.android", "RemotePC"}};

    // ------------------------------------------------------------------ dangerous sideloaded apps

    private static List<String[]> riskyCache;
    private static long riskyAt;

    /** Sideloaded apps that can read SMS/OTPs, the screen or notifications, or are device admin: {pkg, label, abilities}. */
    static synchronized List<String[]> riskyApps(Context c) {
        if (riskyCache != null && System.currentTimeMillis() - riskyAt < 5 * 60_000L) return riskyCache;
        List<String[]> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Set<String> trusted = Prefs.sp(c).getStringSet("trusted_apps", new HashSet<>());
        List<ApplicationInfo> apps;
        try { apps = pm.getInstalledApplications(0); } catch (RuntimeException e) { return out; }
        for (ApplicationInfo ai : apps) {
            if (isSystem(ai) || ai.packageName.equals(c.getPackageName()) || trusted.contains(ai.packageName)) continue;
            String abilities = assess(c, ai.packageName);
            if (abilities != null) out.add(new String[]{ai.packageName, label(pm, ai), abilities});
        }
        riskyCache = out;
        riskyAt = System.currentTimeMillis();
        return out;
    }

    static synchronized void invalidate() { riskyCache = null; }

    /** Returns what a SIDELOADED app can do that scammers abuse, or null if it's from a store / harmless. */
    static String assess(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (isSystem(ai) || pkg.equals(c.getPackageName())) return null;
            String inst = installer(pm, pkg);
            if (inst != null && STORES.contains(inst)) return null;
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS);
            List<String> can = new ArrayList<>();
            Set<String> perms = new HashSet<>();
            if (pi.requestedPermissions != null) perms.addAll(Arrays.asList(pi.requestedPermissions));
            if (perms.contains(Manifest.permission.READ_SMS) || perms.contains(Manifest.permission.RECEIVE_SMS))
                can.add("read your SMS and bank OTPs");
            if (pi.services != null) {
                for (ServiceInfo s : pi.services) {
                    if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(s.permission) && !can.contains("read and control your screen"))
                        can.add("read and control your screen");
                    if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(s.permission) && !can.contains("read all notifications (OTPs)"))
                        can.add("read all notifications (OTPs)");
                }
            }
            if (pi.receivers != null) {
                for (ActivityInfo r : pi.receivers) {
                    if ("android.permission.BIND_DEVICE_ADMIN".equals(r.permission)) { can.add("lock itself as device admin"); break; }
                }
            }
            if (can.isEmpty()) return null;
            if (perms.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES)) can.add("install more apps");
            return String.join(", ", can);
        } catch (Exception e) {
            return null;
        }
    }

    static String installer(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            return pm.getInstallerPackageName(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isSystem(ApplicationInfo ai) {
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    static String label(PackageManager pm, ApplicationInfo ai) {
        try { return pm.getApplicationLabel(ai).toString(); } catch (RuntimeException e) { return ai.packageName; }
    }

    // ------------------------------------------------------------------ full check

    static List<Finding> scan(Context c) {
        invalidate();
        List<Finding> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Set<String> trusted = Prefs.sp(c).getStringSet("trusted_apps", new HashSet<>());

        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && !km.isDeviceSecure())
            out.add(f(true, "No screen lock", "Anyone who picks up this phone can open your UPI apps. Set a PIN or fingerprint.", "LOCK", null));

        for (String[] r : REMOTE_APPS) {
            if (trusted.contains(r[0])) continue;
            try {
                pm.getApplicationInfo(r[0], 0);
                out.add(f(true, r[1] + " is installed", "Remote-control app: lets a stranger see and control this "
                        + "phone. Scammers ask victims to install it. Uninstall unless YOU use it for work.", "UNINSTALL", r[0]));
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        for (String[] a : riskyApps(c)) {
            out.add(f(true, a[1] + " — installed from outside Play Store", "Can " + a[2] + ". If someone sent you "
                    + "this app file, uninstall it now.", "UNINSTALL", a[0]));
        }

        for (String pkg : enabledPackages(c, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)) {
            out.add(f(false, appName(pm, pkg) + " can read your screen", "It has Accessibility turned on. If you "
                    + "don't know why, turn it off.", "ACCESSIBILITY", pkg));
        }
        for (String pkg : enabledPackages(c, "enabled_notification_listeners")) {
            out.add(f(false, appName(pm, pkg) + " can read all notifications", "That includes bank OTPs. If you "
                    + "don't know why, turn it off.", "NOTIF", pkg));
        }
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        List<ComponentName> admins = dpm == null ? null : dpm.getActiveAdmins();
        if (admins != null) {
            for (ComponentName cn : admins) {
                if (systemPkg(pm, cn.getPackageName()) || trusted.contains(cn.getPackageName())
                        || cn.getPackageName().equals(c.getPackageName())) continue;
                out.add(f(false, appName(pm, cn.getPackageName()) + " is a device admin", "Device-admin apps are hard "
                        + "to uninstall. Remove it if you don't know it.", "ADMIN", cn.getPackageName()));
            }
        }
        try {
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1)
                out.add(f(false, "USB debugging is ON", "Lets a computer control this phone. Turn it off unless you are developing apps.", "DEV", null));
        } catch (RuntimeException ignored) {}
        return out;
    }

    /** Non-system, non-trusted packages in a "pkg/cls:pkg/cls" secure setting, excluding this app. */
    private static List<String> enabledPackages(Context c, String setting) {
        List<String> out = new ArrayList<>();
        String s = Settings.Secure.getString(c.getContentResolver(), setting);
        if (s == null) return out;
        PackageManager pm = c.getPackageManager();
        Set<String> trusted = Prefs.sp(c).getStringSet("trusted_apps", new HashSet<>());
        for (String part : s.split(":")) {
            int slash = part.indexOf('/');
            if (slash <= 0) continue;
            String pkg = part.substring(0, slash);
            if (pkg.equals(c.getPackageName()) || out.contains(pkg) || trusted.contains(pkg) || systemPkg(pm, pkg)) continue;
            out.add(pkg);
        }
        return out;
    }

    private static boolean systemPkg(PackageManager pm, String pkg) {
        try { return isSystem(pm.getApplicationInfo(pkg, 0)); } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static String appName(PackageManager pm, String pkg) {
        try { return label(pm, pm.getApplicationInfo(pkg, 0)); } catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private static Finding f(boolean danger, String title, String detail, String action, String pkg) {
        Finding x = new Finding();
        x.danger = danger;
        x.title = title;
        x.detail = detail;
        x.action = action;
        x.pkg = pkg;
        return x;
    }

    static void trust(Context c, String pkg) {
        Set<String> s = new HashSet<>(Prefs.sp(c).getStringSet("trusted_apps", new HashSet<>()));
        s.add(pkg);
        Prefs.sp(c).edit().putStringSet("trusted_apps", s).apply();
        invalidate();
    }
}
