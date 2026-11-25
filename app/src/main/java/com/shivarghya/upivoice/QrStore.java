package com.shivarghya.upivoice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The saved payment methods: typed UPI IDs (we draw the QR) or photos of existing QR stickers. */
final class QrStore {
    private QrStore() {}

    static final class Entry {
        String name;        // payee name shown on screen, e.g. "Jayanta Das"
        String vpa;         // UPI ID, e.g. "name@sbi" (null for photo entries)
        String image;       // file name in app storage (photo entries)
        String label;       // e.g. "SBI", "PhonePe"
        boolean isPhoto() { return image != null; }
    }

    private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9.\\-]{1,63}$");

    static boolean validVpa(String v) { return v != null && VPA.matcher(v.trim()).matches(); }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("qr_list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.name = o.optString("name", "");
                e.vpa = o.has("vpa") ? o.getString("vpa") : null;
                e.image = o.has("image") ? o.getString("image") : null;
                e.label = o.optString("label", "");
                out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void save(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("label", e.label);
                if (e.vpa != null) o.put("vpa", e.vpa);
                if (e.image != null) o.put("image", e.image);
                a.put(o);
            }
        } catch (Exception ignored) {}
        Prefs.sp(c).edit().putString("qr_list", a.toString()).apply();
    }

    /** Copy a picked QR photo into private storage so it stays available. Returns the stored file name. */
    static String importPhoto(Context c, Uri uri) throws Exception {
        String fileName = "qr_" + System.currentTimeMillis() + ".img";
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(new File(c.getFilesDir(), fileName))) {
            if (in == null) throw new Exception("Cannot open image");
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return fileName;
    }

    static void deletePhoto(Context c, String fileName) {
        if (fileName != null) new File(c.getFilesDir(), fileName).delete();
    }

    static Bitmap loadPhoto(Context c, String fileName, int maxSide) {
        File f = new File(c.getFilesDir(), fileName);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o2);
    }

    /** Standard UPI payment link understood by every UPI app. Amount is optional. */
    static String upiUri(Entry e, long paise) {
        StringBuilder sb = new StringBuilder("upi://pay?pa=").append(Uri.encode(e.vpa.trim()));
        if (e.name != null && !e.name.isEmpty()) sb.append("&pn=").append(Uri.encode(e.name.trim()));
        if (paise > 0) sb.append("&am=").append(String.format(Locale.ENGLISH, "%d.%02d", paise / 100, paise % 100));
        sb.append("&cu=INR");
        return sb.toString();
    }

    static Bitmap qr(String text, int size) throws Exception {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) px[off + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
