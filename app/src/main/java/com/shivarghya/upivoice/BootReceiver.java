package com.shivarghya.upivoice;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

/** After a reboot or app update, ask the system to reconnect the notification listener. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            NotificationListenerService.requestRebind(new ComponentName(context, PaymentListenerService.class));
        } catch (RuntimeException ignored) {}
    }
}
