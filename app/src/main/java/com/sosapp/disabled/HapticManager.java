package com.sosapp.disabled;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * All vibration patterns live here.
 * Each pattern is designed to be recognisable without visual cues:
 *
 *  COUNTDOWN_TICK  — single short pulse (100 ms) once per second
 *  SOS_SENT        — three long pulses (SOS in Morse rhythm)
 *  CANCELLED       — one gentle double-tap
 *  ERROR           — two sharp buzzes
 *  SHAKE_DETECTED  — rapid triple pulse (alert / attention)
 *  LONG_PRESS_HOLD — continuous gentle buzz while finger is held
 */
public class HapticManager {

    private final Vibrator vibrator;

    // Pattern format: {delay, on, off, on, off, ...} ms
    private static final long[] PATTERN_COUNTDOWN_TICK  = {0, 80};
    private static final long[] PATTERN_SOS_SENT        = {0, 300, 150, 300, 150, 300};
    private static final long[] PATTERN_CANCELLED       = {0, 80, 60, 80};
    private static final long[] PATTERN_ERROR           = {0, 200, 100, 200};
    private static final long[] PATTERN_SHAKE_DETECTED  = {0, 100, 60, 100, 60, 100};
    private static final long[] PATTERN_LONG_PRESS_HOLD = {0, 600};

    public HapticManager(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void countdownTick()  { fire(PATTERN_COUNTDOWN_TICK); }
    public void sosSent()        { fire(PATTERN_SOS_SENT); }
    public void cancelled()      { fire(PATTERN_CANCELLED); }
    public void error()          { fire(PATTERN_ERROR); }
    public void shakeDetected()  { fire(PATTERN_SHAKE_DETECTED); }
    public void longPressHold()  { fire(PATTERN_LONG_PRESS_HOLD); }

    private void fire(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            //noinspection deprecation
            vibrator.vibrate(pattern, -1);
        }
    }

    public void cancel() {
        if (vibrator != null) vibrator.cancel();
    }
}
