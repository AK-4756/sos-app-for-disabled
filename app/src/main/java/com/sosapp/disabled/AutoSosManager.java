package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * AutoSosManager — two independent timer-based safety features:
 *
 * 1. PANIC TIMER
 *    User activates a "Panic" countdown (e.g. 60 s). If they do not press
 *    "I'm Safe" before it expires, SOS fires automatically. Useful when
 *    entering an unsafe situation and wanting a dead-man's switch.
 *
 * 2. GUARDIAN CHECK-IN
 *    Fires a repeating reminder at a user-defined interval (e.g. every 30 min).
 *    If the user misses two consecutive check-ins, SOS fires automatically.
 *    Useful for users living alone who want periodic passive monitoring.
 *
 * Both features call the provided {@link AutoSosTriggerCallback} when they
 * decide to fire, so MainActivity's existing startSOSCountdown() is reused.
 */
public class AutoSosManager {

    private static final String TAG = "AutoSosManager";

    public interface AutoSosTriggerCallback {
        /** Called on the main thread when the timer decides to fire SOS. */
        void onAutoSosTrigger(String reason);

        /** Called on the main thread with seconds remaining, for UI update. */
        void onPanicTimerTick(int secondsLeft);

        /** Called when the panic timer finishes normally (user checked in). */
        void onPanicTimerCancelled();

        /** Called when a guardian check-in reminder is due. */
        void onCheckInReminderDue();
    }

    // SharedPreferences keys
    public static final String KEY_PANIC_DURATION   = "panic_duration_s";    // int, seconds
    public static final String KEY_CHECKIN_INTERVAL = "checkin_interval_min"; // int, minutes
    public static final String KEY_CHECKIN_ENABLED  = "checkin_enabled";      // boolean
    public static final int    DEFAULT_PANIC_SECS   = 60;
    public static final int    DEFAULT_CHECKIN_MIN  = 30;

    private final Context  context;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());
    private AutoSosTriggerCallback callback;

    // Panic timer state
    private CountDownTimer panicTimer;
    private boolean        panicActive = false;

    // Check-in state
    private Runnable  checkInRunnable;
    private int       missedCheckIns = 0;
    private static final int MAX_MISSED_CHECKINS = 2;

    public AutoSosManager(Context context, AutoSosTriggerCallback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panic Timer
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Starts the panic timer for {@code durationSeconds} seconds.
     * Call {@link #userIsSafe()} to cancel before expiry.
     */
    public void startPanicTimer(int durationSeconds) {
        cancelPanicTimer();
        panicActive = true;
        Log.d(TAG, "Panic timer started: " + durationSeconds + "s");

        panicTimer = new CountDownTimer(durationSeconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secsLeft = (int) (millisUntilFinished / 1000);
                if (callback != null) callback.onPanicTimerTick(secsLeft);
            }

            @Override
            public void onFinish() {
                if (!panicActive) return;
                panicActive = false;
                Log.d(TAG, "Panic timer expired — triggering SOS");
                if (callback != null) {
                    callback.onAutoSosTrigger(AlertRecord.TRIGGER_AUTO);
                }
            }
        }.start();
    }

    /** User confirmed they are safe — cancels the panic timer. */
    public void userIsSafe() {
        cancelPanicTimer();
        Log.d(TAG, "User marked safe — panic timer cancelled");
        if (callback != null) callback.onPanicTimerCancelled();
    }

    public boolean isPanicActive() {
        return panicActive;
    }

    private void cancelPanicTimer() {
        if (panicTimer != null) {
            panicTimer.cancel();
            panicTimer = null;
        }
        panicActive = false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Guardian Check-In
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Starts repeating check-in reminders every {@code intervalMinutes} minutes.
     * User must call {@link #userCheckedIn()} within each interval; otherwise
     * after {@link #MAX_MISSED_CHECKINS} consecutive misses, SOS fires.
     */
    public void startCheckIn(int intervalMinutes) {
        stopCheckIn();
        missedCheckIns = 0;
        Log.d(TAG, "Guardian check-in started: every " + intervalMinutes + " min");
        scheduleNextCheckIn(intervalMinutes * 60_000L);
    }

    /** User tapped "I'm OK" — resets missed-count and reschedules. */
    public void userCheckedIn() {
        missedCheckIns = 0;
        Log.d(TAG, "User checked in — missed count reset");
    }

    public void stopCheckIn() {
        if (checkInRunnable != null) {
            mainHandler.removeCallbacks(checkInRunnable);
            checkInRunnable = null;
        }
        missedCheckIns = 0;
    }

    private void scheduleNextCheckIn(long delayMs) {
        checkInRunnable = () -> {
            missedCheckIns++;
            Log.d(TAG, "Check-in missed. Count=" + missedCheckIns);

            if (missedCheckIns >= MAX_MISSED_CHECKINS) {
                Log.d(TAG, "Max missed check-ins reached — triggering SOS");
                stopCheckIn();
                if (callback != null) {
                    callback.onAutoSosTrigger(AlertRecord.TRIGGER_AUTO);
                }
            } else {
                // Remind and reschedule
                if (callback != null) callback.onCheckInReminderDue();
                scheduleNextCheckIn(delayMs);
            }
        };
        mainHandler.postDelayed(checkInRunnable, delayMs);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cleanup
    // ══════════════════════════════════════════════════════════════════════════

    public void destroy() {
        cancelPanicTimer();
        stopCheckIn();
        callback = null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Preferences helpers
    // ══════════════════════════════════════════════════════════════════════════

    public static int getPanicDuration(Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PANIC_DURATION, DEFAULT_PANIC_SECS);
    }

    public static int getCheckInInterval(Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CHECKIN_INTERVAL, DEFAULT_CHECKIN_MIN);
    }

    public static boolean isCheckInEnabled(Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHECKIN_ENABLED, false);
    }
}
