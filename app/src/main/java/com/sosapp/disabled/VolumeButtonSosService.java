package com.sosapp.disabled;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility Service — Volume Button SOS Trigger
 *
 * Detection logic:
 *  • Listens for KeyEvent.KEYCODE_VOLUME_UP and KEYCODE_VOLUME_DOWN via
 *    onKeyEvent() — the only reliable cross-app way to intercept hardware
 *    volume buttons without consuming them on every press.
 *  • Requires PRESS_THRESHOLD presses inside WINDOW_MS milliseconds.
 *  • After detection, broadcasts ACTION_VOLUME_SOS so MainActivity can
 *    call its existing startSOSCountdown() through a registered receiver.
 *  • The service PASSES every key event through (returns false) so normal
 *    volume control is never broken.
 *  • The toggle in Settings writes KEY_VOLUME_TRIGGER to SharedPreferences;
 *    the service checks this flag on every qualifying press so the user's
 *    choice takes effect immediately without restarting the service.
 */
public class VolumeButtonSosService extends AccessibilityService {

    private static final String TAG            = "VolumeSosService";

    /** Broadcast action — MainActivity registers a receiver for this. */
    public static final String ACTION_VOLUME_SOS =
            "com.sosapp.disabled.ACTION_VOLUME_SOS";

    /** Number of volume presses required to fire. */
    private static final int  PRESS_THRESHOLD  = 4;

    /** Time window in which all presses must occur (ms). */
    private static final long WINDOW_MS        = 3000L;

    /** Minimum gap between two successive key-down events counted (ms).
     *  Prevents a single long-held button from counting as multiple presses. */
    private static final long MIN_PRESS_GAP_MS = 150L;

    /** Cooldown after a trigger fires before the counter resets (ms). */
    private static final long COOLDOWN_MS      = 5000L;

    private int    pressCount      = 0;
    private long   firstPressTime  = 0L;
    private long   lastPressTime   = 0L;
    private boolean inCooldown     = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable resetRunnable = () -> {
        pressCount = 0;
        firstPressTime = 0L;
        Log.d(TAG, "Counter reset by timeout");
    };

    private final Runnable cooldownEndRunnable = () -> {
        inCooldown = false;
        Log.d(TAG, "Cooldown ended — ready to detect again");
    };

    // ══════════════════════════════════════════════════════════════════════════
    // Service lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // We only need key events — request no event types to minimise overhead
        info.eventTypes         = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType       = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags              = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "VolumeButtonSosService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed — we work via onKeyEvent only
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "VolumeButtonSosService interrupted");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Key interception
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // Only care about volume buttons
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false; // pass through — do not consume
        }

        // Only count key-down events (not key-up) to avoid double-counting
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // Check user toggle first — fast path, no trigger if disabled
        if (!isVolumeTriggerEnabled()) {
            return false;
        }

        if (inCooldown) {
            return false; // pass through during cooldown
        }

        long now = System.currentTimeMillis();

        // Ignore key repeat events (held button)
        if (event.getRepeatCount() > 0) {
            return false;
        }

        // Ignore presses too close together (held button generating fast repeats
        // on some devices even with repeatCount == 0)
        if (now - lastPressTime < MIN_PRESS_GAP_MS) {
            return false;
        }
        lastPressTime = now;

        // Start or continue the counting window
        if (pressCount == 0) {
            firstPressTime = now;
            // Schedule automatic reset at end of window
            handler.removeCallbacks(resetRunnable);
            handler.postDelayed(resetRunnable, WINDOW_MS);
        }

        // Check if we have fallen outside the window since the first press
        if (now - firstPressTime > WINDOW_MS) {
            // Window expired — restart from this press
            handler.removeCallbacks(resetRunnable);
            pressCount     = 1;
            firstPressTime = now;
            handler.postDelayed(resetRunnable, WINDOW_MS);
            Log.d(TAG, "Window expired, restarting. count=1");
            return false;
        }

        pressCount++;
        Log.d(TAG, "Volume press count=" + pressCount
                + " elapsed=" + (now - firstPressTime) + "ms");

        if (pressCount >= PRESS_THRESHOLD) {
            // ── TRIGGER ──────────────────────────────────────────────────────
            Log.d(TAG, "SOS threshold reached! Broadcasting.");
            handler.removeCallbacks(resetRunnable);
            pressCount  = 0;
            firstPressTime = 0L;

            // Enter cooldown so rapid extra presses can't re-fire immediately
            inCooldown = true;
            handler.postDelayed(cooldownEndRunnable, COOLDOWN_MS);

            broadcastSosTrigger();
        }

        // Always return false — volume level must still change normally
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void broadcastSosTrigger() {
        // 0. Wake the device if screen is off
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null) {
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK |
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP |
                            android.os.PowerManager.ON_AFTER_RELEASE, "SOSApp:WakeLock");
            wl.acquire(10000); // 10 seconds
        }

        // 1. Send broadcast for foreground activity (if running)
        Intent broadcastIntent = new Intent(ACTION_VOLUME_SOS);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);

        // 2. Start MainActivity for background/closed state
        Intent activityIntent = new Intent(this, MainActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activityIntent.setAction(ACTION_VOLUME_SOS);
        startActivity(activityIntent);
    }

    private boolean isVolumeTriggerEnabled() {
        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(SettingsActivity.KEY_VOLUME_TRIGGER, false);
    }
}
