package com.shivarghya.upivoice;

import java.util.ArrayList;

/**
 * Direct, in-process link between payment detection and the QR screen.
 * Same process and same thread as the voice, so the screen updates at the exact moment speech starts.
 */
final class PaymentBus {
    private PaymentBus() {}

    interface Listener { void onPayment(long paise, String source); }

    private static final ArrayList<Listener> listeners = new ArrayList<>();

    static void add(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void remove(Listener l) { listeners.remove(l); }

    static void post(long paise, String source) {
        for (Listener l : new ArrayList<>(listeners)) l.onPayment(paise, source);
    }
}
