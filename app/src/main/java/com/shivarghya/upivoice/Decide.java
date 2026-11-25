package com.shivarghya.upivoice;

import android.content.Context;

/** One decision for every message: taught formats first, then the built-in rules. */
final class Decide {
    private Decide() {}

    /** Amount received in paise, or -1. {@code sender}: normalised SMS sender or app package (null = any). */
    static long credit(Context c, String sender, String text) {
        Boolean taught = Learned.match(c, sender, text);
        if (Boolean.FALSE.equals(taught)) return -1;           // user said: not money received
        long p = CreditParser.parse(text);
        if (p > 0) return p;
        if (Boolean.TRUE.equals(taught)) return CreditParser.amountOnly(text);
        return -1;
    }
}
