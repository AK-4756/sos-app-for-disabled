package com.sosapp.disabled;

import android.app.Application;
import android.util.Log;

public class SosApplication extends Application {

    private static final String TAG = "SosApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // Warm up TTS engine on app start so it is ready before first SOS
        TtsManager.getInstance(this);

        // Create notification channels (no-op if already exist)
        NotificationHelper.createChannels(this);

        // Seed campus zones if first launch
        CampusZoneManager.getInstance(this);

        // Ensure anonymous Firebase auth (non-blocking)
        AuthManager.getInstance(this).ensureAnonymousAuth(() ->
            Log.d(TAG, "Auth ready"));

        Log.d(TAG, "SosApplication initialized — v7.0");
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        TtsManager.getInstance(this).shutdown();
    }
}
