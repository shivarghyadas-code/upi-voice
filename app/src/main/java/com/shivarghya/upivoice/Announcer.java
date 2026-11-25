package com.shivarghya.upivoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/**
 * Speaks "₹X received". Designed to cost nothing while idle:
 *  - the text-to-speech engine is started only when a payment arrives and shut down
 *    90 s after the last announcement (frees ~20-40 MB),
 *  - a short wake lock keeps the CPU awake only while speaking,
 *  - phone volume is raised just for the announcement and then restored,
 *  - the same payment reported by both the UPI app and the bank SMS is spoken once.
 *
 * Everything runs on the main thread, so no locking is needed.
 */
final class Announcer implements TextToSpeech.OnInitListener {

    private static final long DEDUPE_WINDOW_MS = 3 * 60_000L;
    private static final long IDLE_SHUTDOWN_MS = 90_000L;
    private static final long CALLBACK_TIMEOUT_MS = 9_000L;   // SMS receiver must finish < 10 s
    private static final long SPEECH_SAFETY_MS = 25_000L;
    private static final long INIT_TIMEOUT_MS = 6_000L;

    private static Announcer instance;

    static Announcer get(Context c) {
        if (instance == null) instance = new Announcer(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final PowerManager.WakeLock wakeLock;

    private TextToSpeech tts;
    private boolean ready;
    private String speakLang = "en";
    private int seq;

    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final HashSet<String> speaking = new HashSet<>();
    private final ArrayList<Runnable> doneCallbacks = new ArrayList<>();

    // de-duplication: per source, the recent (time, paise) events; plus what we already announced
    private final HashMap<String, ArrayDeque<long[]>> sourceEvents = new HashMap<>();
    private final ArrayDeque<long[]> announced = new ArrayDeque<>();

    private boolean audioActive;
    private AudioFocusRequest focus;
    private int savedStream = -1, savedVolume = -1;

    private final Runnable shutdownRunnable = this::shutdownIfIdle;
    private final Runnable endAudioRunnable = this::endAudio;
    private final Runnable safetyRunnable = () -> {
        speaking.clear();
        queue.clear();
        endAudio();
        runCallbacks();
    };
    private final Runnable initTimeout = () -> {
        if (!ready) {
            killTts();
            queue.clear();
            runCallbacks();
        }
    };

    private Announcer(Context app) {
        this.app = app;
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpiVoice:speak");
        wakeLock.setReferenceCounted(false);
    }

    // ---------------------------------------------------------------- public API

    /** A payment was detected by {@code source}. {@code onDone} (may be null) runs when speech ends. */
    void offer(String source, long paise, Runnable onDone) {
        if (onDone != null) addCallback(onDone);
        if (!isNew(source, paise)) {
            if (queue.isEmpty() && speaking.isEmpty()) runCallbacks();
            return;
        }
        Prefs.addLog(app, paise, source);
        enqueue(paise);
    }

    void test(long paise) { enqueue(paise); }

    /** Language / channel changed in settings. */
    void settingsChanged() {
        if (tts != null && ready) applySettings();
    }

    // ---------------------------------------------------------------- de-duplication

    /**
     * The UPI app notification and the bank SMS describe the SAME payment, so we count:
     * announce only if this source has now reported this amount more times (in the window)
     * than we have announced it. Two genuine ₹10 payments are still both spoken.
     */
    private boolean isNew(String source, long paise) {
        long now = SystemClock.elapsedRealtime();
        prune(announced, now);
        for (ArrayDeque<long[]> d : sourceEvents.values()) prune(d, now);
        ArrayDeque<long[]> ev = sourceEvents.get(source);
        if (ev == null) {
            ev = new ArrayDeque<>();
            sourceEvents.put(source, ev);
        }
        ev.add(new long[]{now, paise});
        if (count(ev, paise) > count(announced, paise)) {
            announced.add(new long[]{now, paise});
            return true;
        }
        return false;
    }

    private static void prune(ArrayDeque<long[]> d, long now) {
        Iterator<long[]> it = d.iterator();
        while (it.hasNext()) if (now - it.next()[0] > DEDUPE_WINDOW_MS) it.remove();
    }

    private static int count(ArrayDeque<long[]> d, long paise) {
        int n = 0;
        for (long[] e : d) if (e[1] == paise) n++;
        return n;
    }

    // ---------------------------------------------------------------- TTS lifecycle

    private void enqueue(long paise) {
        main.removeCallbacks(shutdownRunnable);
        queue.add(paise);
        if (tts == null) {
            ready = false;
            try {
                tts = new TextToSpeech(app, this);
                main.postDelayed(initTimeout, INIT_TIMEOUT_MS);
            } catch (RuntimeException e) {
                tts = null;
                queue.clear();
                runCallbacks();
            }
            return;
        }
        if (ready) drain();
    }

    @Override
    public void onInit(int status) {
        main.removeCallbacks(initTimeout);
        if (tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            killTts();
            queue.clear();
            runCallbacks();
            return;
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(() -> utteranceEnded(id)); }
            @Override public void onError(String id) { main.post(() -> utteranceEnded(id)); }
            @Override public void onError(String id, int code) { main.post(() -> utteranceEnded(id)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> utteranceEnded(id)); }
        });
        applySettings();
        ready = true;
        drain();
    }

    private void applySettings() {
        String want = Prefs.lang(app);
        String eff = "en";
        if (!"en".equals(want)) {
            int r = tts.setLanguage(locale(want));
            if (r >= TextToSpeech.LANG_AVAILABLE) eff = want;
        }
        if ("en".equals(eff)) {
            int r = tts.setLanguage(new Locale("en", "IN"));
            if (r < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US);
        }
        speakLang = eff;
        Prefs.putBool(app, "lang_ok", eff.equals(want));
        tts.setAudioAttributes(attributes());
        tts.setSpeechRate(0.95f);
    }

    private void drain() {
        if (queue.isEmpty()) return;
        beginAudio();
        boolean twice = Prefs.repeat(app);
        while (!queue.isEmpty()) {
            String text = phrase(speakLang, queue.poll());
            speakOne(text);
            if (twice) {
                tts.playSilentUtterance(600, TextToSpeech.QUEUE_ADD, "gap" + (++seq));
                speakOne(text);
            }
            tts.playSilentUtterance(350, TextToSpeech.QUEUE_ADD, "gap" + (++seq));
        }
        if (speaking.isEmpty()) {          // every speak() call failed
            endAudio();
            runCallbacks();
        }
    }

    private void speakOne(String text) {
        String id = "u" + (++seq);
        Bundle p = new Bundle();
        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream());
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, p, id) == TextToSpeech.SUCCESS) speaking.add(id);
    }

    private void utteranceEnded(String id) {
        if (!speaking.remove(id)) return;          // silent gaps etc.
        if (speaking.isEmpty() && queue.isEmpty()) {
            main.postDelayed(endAudioRunnable, 400); // let the last syllable finish
            runCallbacks();
            main.removeCallbacks(shutdownRunnable);
            main.postDelayed(shutdownRunnable, IDLE_SHUTDOWN_MS);
        }
    }

    private void shutdownIfIdle() {
        if (speaking.isEmpty() && queue.isEmpty()) killTts();
    }

    private void killTts() {
        if (tts != null) {
            try { tts.shutdown(); } catch (RuntimeException ignored) {}
        }
        tts = null;
        ready = false;
    }

    // ---------------------------------------------------------------- audio

    private int stream() {
        return Prefs.alarm(app) ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(Prefs.alarm(app) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private void beginAudio() {
        main.removeCallbacks(endAudioRunnable);
        main.removeCallbacks(safetyRunnable);
        main.postDelayed(safetyRunnable, SPEECH_SAFETY_MS);
        if (audioActive) return;
        audioActive = true;
        wakeLock.acquire(SPEECH_SAFETY_MS + 5_000);

        int s = stream();
        int pct = Prefs.volume(app);
        if (pct > 0) {
            try {
                int max = audio.getStreamMaxVolume(s);
                int target = Math.max(1, Math.round(max * pct / 100f));
                int cur = audio.getStreamVolume(s);
                if (cur != target) {
                    savedStream = s;
                    savedVolume = cur;
                    audio.setStreamVolume(s, target, 0);
                }
            } catch (RuntimeException ignored) { /* e.g. Do Not Disturb restrictions */ }
        }
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .build();
        try { audio.requestAudioFocus(focus); } catch (RuntimeException ignored) {}
    }

    private void endAudio() {
        main.removeCallbacks(safetyRunnable);
        main.removeCallbacks(endAudioRunnable);
        if (!audioActive) return;
        audioActive = false;
        if (focus != null) {
            try { audio.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) {}
            focus = null;
        }
        if (savedVolume >= 0) {
            try { audio.setStreamVolume(savedStream, savedVolume, 0); } catch (RuntimeException ignored) {}
            savedVolume = -1;
        }
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // ---------------------------------------------------------------- callbacks

    private void addCallback(Runnable r) {
        final boolean[] fired = {false};
        Runnable once = () -> {
            if (!fired[0]) {
                fired[0] = true;
                r.run();
            }
        };
        doneCallbacks.add(once);
        main.postDelayed(once, CALLBACK_TIMEOUT_MS);
    }

    private void runCallbacks() {
        if (doneCallbacks.isEmpty()) return;
        ArrayList<Runnable> copy = new ArrayList<>(doneCallbacks);
        doneCallbacks.clear();
        for (Runnable r : copy) {
            main.removeCallbacks(r);
            r.run();
        }
    }

    // ---------------------------------------------------------------- words

    private static Locale locale(String lang) {
        switch (lang) {
            case "hi": return new Locale("hi", "IN");
            case "bn": return new Locale("bn", "IN");
            default:   return new Locale("en", "IN");
        }
    }

    /** The sentence that is spoken. Numbers are left as digits: the voice reads them in its own language. */
    static String phrase(String lang, long paise) {
        long r = paise / 100, p = paise % 100;
        switch (lang) {
            case "hi":
                return r + " रुपये" + (p > 0 ? " " + p + " पैसे" : "") + " प्राप्त हुए";
            case "bn":
                return r + " টাকা" + (p > 0 ? " " + p + " পয়সা" : "") + " পাওয়া গেছে";
            default:
                return "Received " + r + (r == 1 ? " rupee" : " rupees") + (p > 0 ? " and " + p + " paise" : "");
        }
    }

    static String formatRupees(long paise) {
        long r = paise / 100, p = paise % 100;
        return "₹" + r + (p > 0 ? String.format(Locale.ENGLISH, ".%02d", p) : "");
    }
}
