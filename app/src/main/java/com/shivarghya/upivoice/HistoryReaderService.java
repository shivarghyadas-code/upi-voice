package com.shivarghya.upivoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the payment history that UPI apps show on screen (the only way Android allows one app to
 * see another app's history). Strict limits:
 *  - only listens to the installed UPI / bank apps,
 *  - does nothing unless "Read app history" was started in History (auto-stops after 10 min),
 *  - only stores money RECEIVED (amount, date, app) in this app's own history,
 *  - this app has no internet permission, so nothing can leave the phone.
 */
public class HistoryReaderService extends AccessibilityService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable capture = this::capture;
    private long lastToast;

    @Override
    protected void onServiceConnected() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            List<String[]> apps = Sources.installedApps(this);
            if (info != null && !apps.isEmpty()) {
                String[] pkgs = new String[apps.size()];
                for (int i = 0; i < pkgs.length; i++) pkgs[i] = apps.get(i)[0];
                info.packageNames = pkgs;                 // watch UPI apps only
                setServiceInfo(info);
            }
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!scanActive()) return;
        main.removeCallbacks(capture);
        main.postDelayed(capture, 500);          // wait until scrolling settles
    }

    @Override
    public void onInterrupt() {}

    private boolean scanActive() {
        return System.currentTimeMillis() < Prefs.sp(this).getLong("scan_until", 0);
    }

    private void capture() {
        if (!scanActive()) return;
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (RuntimeException e) { return; }
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        if (!Sources.isPaymentApp(this, pkg)) return;

        List<String> rows = rows(root);
        List<ScreenParser.Found> found = ScreenParser.parse(rows, pkg, System.currentTimeMillis());
        if (found.isEmpty()) return;

        String label = "Read from " + Sources.label(this, pkg);
        List<History.Item> items = new ArrayList<>();
        for (ScreenParser.Found f : found) {
            History.Item it = new History.Item();
            it.time = f.time;
            it.paise = f.paise;
            it.source = label + (f.dayOnly ? " (date only)" : "");
            it.key = f.key;
            items.add(it);
        }
        int added = History.addAll(this, items);
        if (added > 0 && System.currentTimeMillis() - lastToast > 2500) {
            lastToast = System.currentTimeMillis();
            Toast.makeText(this, "UPI Voice: saved " + added + " received payment" + (added == 1 ? "" : "s")
                    + " from " + Sources.label(this, pkg), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ screen → rows

    /** One string per list row. Uses the scrolling list on screen; falls back to all texts in order. */
    private static List<String> rows(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> lists = new ArrayList<>();
        findScrollables(root, lists, 0);
        List<String> rows = new ArrayList<>();
        for (AccessibilityNodeInfo list : lists) {
            for (int i = 0; i < list.getChildCount(); i++) {
                AccessibilityNodeInfo child = list.getChild(i);
                if (child == null) continue;
                StringBuilder sb = new StringBuilder();
                collect(child, sb, 0);
                if (sb.length() > 0) rows.add(sb.toString());
            }
        }
        if (rows.isEmpty()) {
            // No list found: slide a window over all texts so each amount keeps its neighbours.
            List<String> texts = new ArrayList<>();
            flatten(root, texts, 0);
            for (int i = 0; i < texts.size(); i++) {
                if (ScreenParser.amount(texts.get(i)) <= 0) {
                    rows.add(texts.get(i));          // lets date headers through
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (int j = Math.max(0, i - 3); j <= Math.min(texts.size() - 1, i + 3); j++) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(texts.get(j));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    private static void findScrollables(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 30) return;
        if (n.isScrollable() && n.getChildCount() > 1) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) findScrollables(n.getChild(i), out, depth + 1);
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 25) return;
        append(sb, n.getText());
        if (n.getText() == null) append(sb, n.getContentDescription());
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private static void flatten(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 30) return;
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (t != null && t.length() > 0) out.add(t.toString());
        for (int i = 0; i < n.getChildCount(); i++) flatten(n.getChild(i), out, depth + 1);
    }

    private static void append(StringBuilder sb, CharSequence cs) {
        if (cs == null || cs.length() == 0) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(cs);
    }
}
