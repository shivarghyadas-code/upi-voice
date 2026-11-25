package com.shivarghya.upivoice;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        SmsWatcher.start(this);
    }
}
