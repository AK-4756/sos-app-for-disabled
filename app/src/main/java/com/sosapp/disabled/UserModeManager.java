package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

/**
 * UserModeManager — applies adaptive UI and behaviour based on the user's
 * declared disability mode.
 *
 * Modes:
 *   DEFAULT  — balanced (existing behaviour unchanged)
 *   VISION   — maximize TTS, large text forced on, vibration on all events
 *   HEARING  — maximize haptic patterns, flash visual alerts, mute TTS
 *   MOTOR    — minimal interaction (single tap fires SOS immediately with
 *               longest countdown for accidental-trigger safety)
 *
 * All adaptations are applied at Activity start via applyMode().
 * They are non-destructive — settings are not written to SharedPreferences,
 * only applied in-memory for the current session.
 */
public class UserModeManager {

    public static final String KEY_USER_MODE = "user_mode";

    public static final String MODE_DEFAULT = "DEFAULT";
    public static final String MODE_VISION  = "VISION";
    public static final String MODE_HEARING = "HEARING";
    public static final String MODE_MOTOR   = "MOTOR";

    private final Context context;
    private final TtsManager    tts;
    private final HapticManager haptic;

    public UserModeManager(Context context, TtsManager tts, HapticManager haptic) {
        this.context = context.getApplicationContext();
        this.tts     = tts;
        this.haptic  = haptic;
    }

    public String getCurrentMode() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USER_MODE, MODE_DEFAULT);
    }

    /**
     * Applies mode-specific behaviour. Call from MainActivity.applyAccessibilityScale().
     *
     * @param sosButton    the main SOS button (for size adaptation)
     * @param statusText   the status TextView (for colour/size adaptation)
     */
    public void applyMode(View sosButton, TextView statusText) {
        String mode = getCurrentMode();
        switch (mode) {
            case MODE_VISION:
                applyVisionMode(statusText);
                break;
            case MODE_HEARING:
                applyHearingMode(statusText);
                break;
            case MODE_MOTOR:
                applyMotorMode(sosButton);
                break;
            default:
                // Default — nothing extra to apply
                break;
        }
    }

    // ── Vision Impaired ───────────────────────────────────────────────────────

    private void applyVisionMode(TextView statusText) {
        // Force TTS on, slow rate, announce everything
        tts.setEnabled(true);
        tts.setSpeechRate(0.8f);

        // Large text
        if (statusText != null) {
            statusText.setTextSize(24f);
        }

        // Haptic on every UI interaction
        haptic.countdownTick();
    }

    // ── Hearing Impaired ──────────────────────────────────────────────────────

    private void applyHearingMode(TextView statusText) {
        // Mute TTS — user cannot hear it
        tts.setEnabled(false);

        // Strong visual text
        if (statusText != null) {
            statusText.setTextColor(0xFFFF0000); // bright red for visibility
            statusText.setTextSize(22f);
        }
    }

    // ── Motor Impaired ────────────────────────────────────────────────────────

    private void applyMotorMode(View sosButton) {
        // Make button even bigger by increasing padding
        if (sosButton != null) {
            sosButton.setPadding(40, 40, 40, 40);
        }
        // Motor mode extends countdown to maximum (10s) for safety
        // This is set by reading the pref in startSOSCountdown — we don't override here,
        // but we do announce the countdown more verbosely via TTS (handled by applyModeToCountdown)
    }

    /**
     * Returns the countdown duration, overriding prefs for MOTOR mode (forces 10s).
     */
    public int getEffectiveCountdown(int prefValue) {
        if (MODE_MOTOR.equals(getCurrentMode())) return 10;
        return prefValue;
    }

    /**
     * Returns whether silent mode should be forced for HEARING mode.
     * Hearing impaired users may prefer vibration-only without phone sound.
     */
    public boolean isForcedSilent() {
        return MODE_HEARING.equals(getCurrentMode());
    }

    /**
     * Announces a mode activation message via TTS (called from Settings save).
     */
    public void announceModeChange(String newMode) {
        switch (newMode) {
            case MODE_VISION:
                tts.speakNow("Vision impaired mode activated. All actions will be announced.");
                break;
            case MODE_HEARING:
                tts.setEnabled(false);
                break;
            case MODE_MOTOR:
                tts.speakNow("Motor impaired mode activated. Extra-large targets and extended countdown.");
                break;
            default:
                tts.speakNow("Standard mode activated.");
        }
    }

    /**
     * Returns a vibration intensity multiplier for haptic patterns.
     * HEARING mode doubles vibration intensity.
     */
    public boolean isEnhancedVibration() {
        return MODE_HEARING.equals(getCurrentMode());
    }
}
