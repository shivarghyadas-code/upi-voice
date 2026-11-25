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
import java.util.Set;

/** Every bank SMS that mentions money, saved on the phone with what the app decided (offline). */
final class BankLog {
    private BankLog() {}

    static final class Entry {
        long time;
        String sender, status, body;
        long paise;
        String id;              // sent-time + text hash
    }

    private static final String FILE = "banksms.tsv";
    private static final int MAX = 300;

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Newest first. */
    static synchronized List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 6);
                if (p.length < 6) continue;
                try {
                    Entry e = new Entry();
                    e.time = Long.parseLong(p[0]);
                    e.id = p[1];
                    e.sender = p[2];
                    e.status = p[3];
                    e.paise = Long.parseLong(p[4]);
                    e.body = p[5];
                    out.add(e);
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** Adds the entry unless one with the same id exists. */
    static synchronized void add(Context c, Entry e) {
        List<Entry> all = load(c);
        for (Entry x : all) if (x.id.equals(e.id)) return;
        all.add(0, e);
        write(c, all);
    }

    static synchronized void addAll(Context c, List<Entry> list) {
        List<Entry> all = load(c);
        Set<String> ids = new HashSet<>();
        for (Entry x : all) ids.add(x.id);
        for (Entry e : list) if (ids.add(e.id)) all.add(e);
        all.sort((a, b) -> Long.compare(b.time, a.time));
        write(c, all);
    }

    static synchronized void setStatus(Context c, String id, String status, long paise) {
        List<Entry> all = load(c);
        for (Entry x : all) if (x.id.equals(id)) { x.status = status; x.paise = paise; }
        write(c, all);
    }

    private static void write(Context c, List<Entry> all) {
        if (all.size() > MAX) all = new ArrayList<>(all.subList(0, MAX));
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), false), StandardCharsets.UTF_8)) {
            for (Entry e : all) {
                w.write(e.time + "\t" + clean(e.id) + "\t" + clean(e.sender) + "\t" + clean(e.status) + "\t"
                        + e.paise + "\t" + clean(e.body) + "\n");
            }
        } catch (Exception ignored) {}
    }

    static synchronized void clear(Context c) { file(c).delete(); }
}
