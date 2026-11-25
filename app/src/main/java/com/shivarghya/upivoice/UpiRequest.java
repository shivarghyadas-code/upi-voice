package com.shivarghya.upivoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A UPI payment request ("upi://pay?pa=…&pn=…&am=…"), from a QR code or typed in. Pure Java. */
final class UpiRequest {

    static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9.\\-]{1,63}$");

    final String raw;                                    // original text (kept intact for signed QRs)
    final Map<String, String> p = new LinkedHashMap<>(); // lower-case keys

    private UpiRequest(String raw) { this.raw = raw; }

    /** Parse "upi://pay?…" (any letter case). Returns null if it isn't a UPI payment link. */
    static UpiRequest parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ENGLISH);
        if (!lower.startsWith("upi://pay")) return null;
        UpiRequest r = new UpiRequest(t);
        int q = t.indexOf('?');
        if (q >= 0) {
            for (String part : t.substring(q + 1).split("&")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : part.substring(eq + 1);
                r.p.put(decode(k).toLowerCase(Locale.ENGLISH).trim(), decode(v).trim());
            }
        }
        return r;
    }

    /** Typed-in payment to a UPI ID. */
    static UpiRequest manual(String vpa, String note) {
        UpiRequest r = new UpiRequest(null);
        r.p.put("pa", vpa.trim());
        if (note != null && !note.trim().isEmpty()) r.p.put("tn", note.trim());
        r.p.put("cu", "INR");
        return r;
    }

    String get(String k) { String v = p.get(k); return v == null ? "" : v; }
    String payee() { return get("pa"); }
    String name() { return get("pn"); }
    String note() { return get("tn"); }
    boolean validPayee() { return VPA.matcher(payee()).matches(); }
    boolean fromQr() { return raw != null; }

    /** Amount written in the QR, in paise, or -1. */
    long qrAmount() {
        String am = get("am");
        if (am.isEmpty()) return -1;
        try {
            long v = new BigDecimal(am.replace(",", "")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return v > 0 ? v : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Link to hand to the UPI app. The original QR text is kept unchanged when possible (signed merchant QRs). */
    String toUri(long paise) {
        if (raw != null && qrAmount() == paise) return raw;
        StringBuilder sb = new StringBuilder("upi://pay?");
        boolean first = true;
        Map<String, String> m = new LinkedHashMap<>(p);
        m.put("am", String.format(Locale.ENGLISH, "%d.%02d", paise / 100, paise % 100));
        if (!m.containsKey("cu")) m.put("cu", "INR");
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (!first) sb.append('&');
            sb.append(e.getKey()).append('=').append(encode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%40", "@"); }
        catch (Exception e) { return s; }
    }
}
