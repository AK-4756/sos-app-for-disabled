package com.sosapp.disabled;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * ShakeDetector — accelerometer-based shake trigger with strong false-alert prevention.
 *
 * Detection algorithm (tightened from v1):
 *   - Requires 4 threshold crossings (up from 3) within a 600 ms window.
 *   - Minimum 120 ms gap between crossings prevents a single sustained force counting twice.
 *   - 4-second cooldown after a trigger prevents rapid re-fires from a dropped phone.
 *   - Threshold raised to 3.2 g to filter out walking, driving, and desk vibration.
 *
 * These values were chosen to require a deliberate double-shake motion that is
 * very unlikely to happen accidentally during daily use.
 */
public class ShakeDetector implements SensorEventListener {

    public interface OnShakeListener {
        void onShake();
    }

    private static final float SHAKE_THRESHOLD_G   = 3.2f;   // gravitational force
    private static final int   MIN_CROSSINGS        = 4;      // required within window
    private static final long  WINDOW_MS            = 600L;   // detection window
    private static final long  MIN_CROSSING_GAP_MS  = 120L;   // debounce per crossing
    private static final long  COOLDOWN_MS          = 4_000L; // post-trigger cooldown

    private final SensorManager sensorManager;
    private final Sensor        accelerometer;
    private OnShakeListener     listener;

    private int  crossingCount  = 0;
    private long windowStart    = 0L;
    private long lastCrossing   = 0L;
    private long lastTrigger    = 0L;
    private boolean registered  = false;

    public ShakeDetector(SensorManager sensorManager) {
        this.sensorManager = sensorManager;
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public boolean isAvailable() { return accelerometer != null; }

    public void setOnShakeListener(OnShakeListener l) { this.listener = l; }

    public void start() {
        if (accelerometer != null && !registered) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME);  // faster than UI for responsiveness
            registered = true;
        }
    }

    public void stop() {
        if (registered) {
            sensorManager.unregisterListener(this);
            registered = false;
        }
        crossingCount = 0;
        windowStart   = 0L;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (listener == null) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float gForce = (float) (Math.sqrt(x * x + y * y + z * z)
                / SensorManager.GRAVITY_EARTH);

        if (gForce < SHAKE_THRESHOLD_G) return;

        long now = System.currentTimeMillis();

        // Cooldown guard
        if (now - lastTrigger < COOLDOWN_MS) return;

        // Debounce: ignore if too close to last crossing
        if (now - lastCrossing < MIN_CROSSING_GAP_MS) return;
        lastCrossing = now;

        // Start new window or reset expired window
        if (crossingCount == 0 || (now - windowStart) > WINDOW_MS) {
            crossingCount = 1;
            windowStart   = now;
            return;
        }

        crossingCount++;

        if (crossingCount >= MIN_CROSSINGS) {
            crossingCount = 0;
            windowStart   = 0L;
            lastTrigger   = now;
            listener.onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
