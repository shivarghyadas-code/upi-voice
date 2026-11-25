package com.shivarghya.upivoice;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Transaction history of payments received, stored privately on the phone (a small text file).
 * Filled live by every announcement, and by importing past bank SMS.
 */
final class History {
    private History() {}

    static final class Item {
        long time;      // when received (ms)
        long paise;
        String source;  // e.g. "Google Pay", "Bank SMS · SBIUPI"
        String key;     // de-duplication key
        boolean announced;  // spoken by UPI Voice when it arrived
    }

    private static final String FILE = "history.tsv";
    private static final int MAX = 3000;
    private static final long SAME_PAYMENT_MS = 10 * 60_000L;

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** Newest first. */
    static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                try {
                    Item it = new Item();
                    it.time = Long.parseLong(p[0]);
                    it.paise = Long.parseLong(p[1]);
                    it.source = p[2];
                    it.key = p[3];
                    it.announced = p.length > 4 && "A".equals(p[4]);
                    out.add(it);
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** Append one live payment (it was just announced). */
    static synchronized void add(Context c, long time, long paise, String source, String key) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            w.write(time + "\t" + paise + "\t" + clean(source) + "\t" + clean(key) + "\tA\n");
        } catch (Exception ignored) {}
    }

    /**
     * Add imported items, skipping ones already in the history: same key, or the same payment
     * already recorded from a DIFFERENT source (live alert "n:", bank SMS "sms:", screen "acc:"):
     * same amount within 10 minutes — or on the same day when only the date is known.
     * @return how many were added
     */
    static synchronized int addAll(Context c, List<Item> items) {
        List<Item> all = load(c);
        HashSet<String> keys = new HashSet<>();
        for (Item it : all) keys.add(it.key);
        HashSet<Item> claimed = new HashSet<>();
        int added = 0;
        items.sort((a, b) -> Long.compare(a.time, b.time));
        for (Item n : items) {
            if (keys.contains(n.key)) continue;
            Item match = null;
            for (Item e : all) {
                if (e.paise != n.paise || claimed.contains(e) || kind(e.key).equals(kind(n.key))) continue;
                boolean dayOnly = e.key.startsWith("acc:d:") || n.key.startsWith("acc:d:");
                if (dayOnly ? sameDay(e.time, n.time) : Math.abs(e.time - n.time) <= SAME_PAYMENT_MS) {
                    match = e;
                    break;
                }
            }
            if (match != null) { claimed.add(match); continue; }
            all.add(n);
            keys.add(n.key);
            added++;
        }
        if (added > 0) rewrite(c, all);
        return added;
    }

    private static String kind(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar x = java.util.Calendar.getInstance(), y = java.util.Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR)
                && x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static void rewrite(Context c, List<Item> all) {
        all.sort((a, b) -> Long.compare(b.time, a.time));
        if (all.size() > MAX) all = new ArrayList<>(all.subList(0, MAX));
        all.sort((a, b) -> Long.compare(a.time, b.time));
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), false), StandardCharsets.UTF_8)) {
            for (Item it : all) w.write(it.time + "\t" + it.paise + "\t" + clean(it.source) + "\t" + clean(it.key)
                    + (it.announced ? "\tA" : "\t") + "\n");
        } catch (Exception ignored) {}
    }

    static synchronized void clear(Context c) {
        file(c).delete();
        Prefs.sp(c).edit().remove("log").apply();
    }
}
