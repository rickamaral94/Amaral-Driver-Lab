package com.amaral.driverlab;

import android.app.Application;
import android.content.Context;

public final class DriverLabApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageManager.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LanguageManager.initialize(this);
    }
}
