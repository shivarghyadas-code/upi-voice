package com.shivarghya.upivoice;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bank message formats taught by the user ("this is money received" / "this is NOT").
 * Bank SMS are fixed templates where only numbers and names change, so a new message is
 * compared word-by-word with the taught ones from the same sender.
 */
final class Learned {
    private Learned() {}

    static final double MATCH = 0.70;
    private static final int MAX = 100;

    static final class Template {
        boolean received;
        String sender;          // normalised sender (e.g. "SBIUPI") or app package
        Set<String> words;
    }

    /** "VM-SBIUPI-S" / "AD-SBIUPI" → "SBIUPI". */
    static String normSender(String s) {
        if (s == null) return "";
        String u = s.trim().toUpperCase(Locale.ENGLISH);
        if (u.length() > 3 && u.charAt(2) == '-') u = u.substring(3);
        if (u.length() > 2 && u.charAt(u.length() - 2) == '-') u = u.substring(0, u.length() - 2);
        return u;
    }

    /** Lower-case words of 2+ letters; numbers, amounts and symbols are ignored. */
    static Set<String> words(String text) {
        Set<String> w = new HashSet<>();
        if (text == null) return w;
        for (String t : text.toLowerCase(Locale.ENGLISH).split("[^a-z]+")) if (t.length() >= 2) w.add(t);
        return w;
    }

    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return inter / (double) (a.size() + b.size() - inter);
    }

    /** Best verdict for this message among templates (sender null = any sender). Pure. */
    static Boolean match(List<Template> templates, String sender, String text) {
        Set<String> w = words(text);
        double best = 0;
        Boolean verdict = null;
        for (Template t : templates) {
            if (sender != null && !sender.equals(t.sender)) continue;
            double s = similarity(w, t.words);
            if (s >= MATCH && s > best) { best = s; verdict = t.received; }
        }
        return verdict;
    }

    // ------------------------------------------------------------------ storage

    static List<Template> load(Context c) {
        List<Template> out = new ArrayList<>();
        for (String line : Prefs.sp(c).getString("learned", "").split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length != 3) continue;
            Template t = new Template();
            t.received = "R".equals(p[0]);
            t.sender = p[1];
            t.words = new HashSet<>(Arrays.asList(p[2].split(" ")));
            out.add(t);
        }
        return out;
    }

    static Boolean match(Context c, String sender, String text) {
        return match(load(c), sender, text);
    }

    static void teach(Context c, String sender, String text, boolean received) {
        List<Template> all = load(c);
        Set<String> w = words(text);
        // replace an existing near-identical template from the same sender
        all.removeIf(t -> t.sender.equals(sender) && similarity(t.words, w) >= 0.9);
        Template t = new Template();
        t.received = received;
        t.sender = sender;
        t.words = w;
        all.add(t);
        while (all.size() > MAX) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Template x : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x.received ? "R" : "N").append('|').append(x.sender.replace("|", "")).append('|')
                    .append(String.join(" ", x.words));
        }
        Prefs.putString(c, "learned", sb.toString());
    }

    static int count(Context c) { return load(c).size(); }

    static void clear(Context c) { Prefs.sp(c).edit().remove("learned").apply(); }
}
