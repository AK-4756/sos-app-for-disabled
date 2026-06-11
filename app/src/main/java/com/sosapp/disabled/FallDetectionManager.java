package com.sosapp.disabled;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * FallDetectionManager — sensor-fusion fall detection using accelerometer + gyroscope.
 *
 * Algorithm (3-phase):
 *   PHASE 1 — FREE FALL:   Total acceleration drops below FREE_FALL_THRESHOLD for
 *                           FREE_FALL_MIN_MS. This indicates the user is airborne.
 *   PHASE 2 — IMPACT:      Within IMPACT_WINDOW_MS after free-fall ends, total
 *                           acceleration exceeds IMPACT_THRESHOLD. This is the
 *                           landing/collision event.
 *   PHASE 3 — INACTIVITY:  After impact, if the user shows no significant movement
 *                           for POST_IMPACT_INACTIVITY_MS, a fall is confirmed.
 *                           If they move, the event is cancelled (they stood up).
 *
 * Gyroscope assists in discriminating between intentional quick motion and
 * actual falls: a fall typically produces a high angular rate spike at impact.
 *
 * On confirmed fall: shows a 10-second user-confirmation countdown.
 * If not cancelled → triggers SOS via callback.
 * Confidence score (0.0-1.0) is attached so downstream can log it.
 */
public class FallDetectionManager implements SensorEventListener {

    private static final String TAG = "FallDetectionManager";

    // ── Thresholds ─────────────────────────────────────────────────────────
    private static final float FREE_FALL_THRESHOLD     = 3.0f;   // m/s² (near 0 = weightless)
    private static final long  FREE_FALL_MIN_MS        = 80L;    // must last at least this long
    private static final long  IMPACT_WINDOW_MS        = 800L;   // look for impact within this window
    private static final float IMPACT_THRESHOLD        = 25.0f;  // m/s² sudden spike
    private static final float GYRO_IMPACT_THRESHOLD   = 4.0f;   // rad/s angular spike
    private static final long  POST_IMPACT_INACTIVITY  = 2500L;  // ms of no movement → confirmed
    private static final float INACTIVITY_THRESHOLD    = 2.5f;   // m/s² below this = still
    private static final long  COOLDOWN_MS             = 15_000L;// min gap between triggers
    private static final int   CONFIRM_COUNTDOWN_S     = 10;     // seconds user has to cancel

    public interface FallCallback {
        /** Called on main thread when a fall is confirmed and user did not cancel. */
        void onFallConfirmed(float confidenceScore);
        /** Called each second during the confirmation countdown. */
        void onCountdownTick(int secondsRemaining);
        /** Called if user cancels the countdown. */
        void onCancelled();
    }

    // ── State machine ─────────────────────────────────────────────────────
    private enum State { IDLE, FREE_FALL, WAITING_IMPACT, POST_IMPACT, CONFIRMING }

    private State   state          = State.IDLE;
    private long    freeFallStart  = 0;
    private long    impactTime     = 0;
    private float   peakImpact     = 0;
    private float   peakGyro       = 0;
    private long    lastTrigger    = 0;
    private boolean cancelled      = false;

    private final Context       context;
    private final SensorManager sensorManager;
    private final Sensor        accel;
    private final Sensor        gyro;
    private final Handler       handler = new Handler(Looper.getMainLooper());
    private FallCallback        callback;

    private float[] accelValues = new float[3];
    private float[] gyroValues  = new float[3];
    private boolean registered  = false;

    public FallDetectionManager(Context context) {
        this.context = context.getApplicationContext();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public boolean isAvailable() { return accel != null; }

    public void setCallback(FallCallback cb) { this.callback = cb; }

    public void start() {
        if (registered || accel == null) return;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
        Log.d(TAG, "Fall detection started");
    }

    public void stop() {
        if (!registered) return;
        sensorManager.unregisterListener(this);
        registered = false;
        handler.removeCallbacksAndMessages(null);
        state = State.IDLE;
        Log.d(TAG, "Fall detection stopped");
    }

    /** Called by user tapping the "I'm OK" button during countdown. */
    public void cancelConfirmation() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        state = State.IDLE;
        if (callback != null) callback.onCancelled();
        Log.d(TAG, "Fall confirmation cancelled by user");
    }

    public boolean isConfirming() { return state == State.CONFIRMING; }

    // ══════════════════════════════════════════════════════════════════════════
    // Sensor processing
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelValues = event.values.clone();
            processAccel();
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroValues = event.values.clone();
        }
    }

    private void processAccel() {
        float total = magnitude(accelValues);
        long  now   = System.currentTimeMillis();

        switch (state) {
            case IDLE:
                if (now - lastTrigger < COOLDOWN_MS) return;
                if (total < FREE_FALL_THRESHOLD) {
                    state        = State.FREE_FALL;
                    freeFallStart = now;
                    peakImpact   = 0;
                    peakGyro     = 0;
                }
                break;

            case FREE_FALL:
                if (total < FREE_FALL_THRESHOLD) {
                    // Still in free fall — update duration
                    if (now - freeFallStart < FREE_FALL_MIN_MS) return; // not long enough yet
                } else {
                    long duration = now - freeFallStart;
                    if (duration >= FREE_FALL_MIN_MS) {
                        // Valid free-fall detected — wait for impact
                        state = State.WAITING_IMPACT;
                        Log.d(TAG, "Free fall detected (" + duration + "ms)");
                    } else {
                        state = State.IDLE; // too short, reset
                    }
                }
                break;

            case WAITING_IMPACT:
                peakImpact = Math.max(peakImpact, total);
                peakGyro   = Math.max(peakGyro, magnitude(gyroValues));

                if (total > IMPACT_THRESHOLD) {
                    impactTime = now;
                    state      = State.POST_IMPACT;
                    Log.d(TAG, "Impact detected: " + total + " m/s², gyro peak=" + peakGyro);
                } else if (now - freeFallStart > FREE_FALL_MIN_MS + IMPACT_WINDOW_MS) {
                    // No impact in window — false alarm
                    state = State.IDLE;
                }
                break;

            case POST_IMPACT:
                // Check for inactivity — if still, schedule confirmation
                if (total > INACTIVITY_THRESHOLD) {
                    // User is moving — not a fall, or they got up
                    if (now - impactTime > 500) { // give 500ms grace
                        Log.d(TAG, "User moving after impact — not a fall");
                        state = State.IDLE;
                    }
                } else {
                    // Inactivity detected — wait for sustained period
                    if (now - impactTime >= POST_IMPACT_INACTIVITY) {
                        state = State.CONFIRMING;
                        startConfirmationCountdown();
                    }
                }
                break;

            case CONFIRMING:
                // Nothing to do — countdown handler is running
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Confirmation countdown
    // ══════════════════════════════════════════════════════════════════════════

    private void startConfirmationCountdown() {
        cancelled = false;
        lastTrigger = System.currentTimeMillis();
        Log.d(TAG, "Starting fall confirmation countdown (" + CONFIRM_COUNTDOWN_S + "s)");
        tickCountdown(CONFIRM_COUNTDOWN_S);
    }

    private void tickCountdown(int secsLeft) {
        if (cancelled || state != State.CONFIRMING) return;
        if (callback != null) callback.onCountdownTick(secsLeft);

        if (secsLeft <= 0) {
            state = State.IDLE;
            float confidence = computeConfidence();
            Log.d(TAG, "Fall confirmed — confidence=" + confidence);
            if (callback != null) callback.onFallConfirmed(confidence);
        } else {
            handler.postDelayed(() -> tickCountdown(secsLeft - 1), 1000);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private float computeConfidence() {
        // Higher impact + gyro spike → higher confidence it was a real fall
        float impactScore = Math.min(peakImpact / 40f, 1.0f);   // 40 m/s² = 100%
        float gyroScore   = gyro != null
                ? Math.min(peakGyro / GYRO_IMPACT_THRESHOLD, 1.0f) : 0.5f;
        return (impactScore * 0.6f + gyroScore * 0.4f);
    }

    private float magnitude(float[] v) {
        return (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
