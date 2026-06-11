package com.sosapp.disabled;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    // ─── SharedPreferences keys (all public so MainActivity can read them) ─────
    public static final String PREFS_NAME          = "SOSPrefs";
    public static final String KEY_COUNTDOWN       = "countdown_seconds";
    public static final String KEY_CUSTOM_MSG      = "custom_message";
    public static final String KEY_LARGE_TEXT      = "large_text";
    public static final String KEY_TTS_ENABLED     = "tts_enabled";
    public static final String KEY_SHAKE_TRIGGER   = "shake_trigger";
    public static final String KEY_SPEECH_RATE     = "speech_rate";    // stored as int 0-100
    public static final String KEY_LANGUAGE        = "language";       // "en" or "hi"
    public static final String KEY_VOLUME_TRIGGER  = "volume_trigger"; // boolean
    // IDS-level additions
    public static final String KEY_SILENT_MODE      = "silent_mode";      // boolean
    public static final String KEY_USER_MODE        = "user_mode";        // String: DEFAULT/VISION/HEARING/MOTOR
    public static final String KEY_ESCALATION_DELAY = "escalation_delay_s"; // int seconds
    public static final String KEY_DARK_MODE        = "dark_mode";        // boolean

    // ─── Views ────────────────────────────────────────────────────────────────
    private RadioGroup  rgCountdown;
    private RadioButton rb3s, rb5s, rb10s;
    private EditText    etCustomMessage;
    private RadioGroup  rgTextSize;
    private RadioButton rbNormalText, rbLargeText;
    private SwitchCompat switchTts;
    private SwitchCompat switchShake;
    private SwitchCompat switchVolume;
    private Button      btnOpenAccessibility;
    // Auto-SOS
    private SeekBar     seekPanicDuration;
    private TextView    tvPanicDurationLabel;
    private SwitchCompat switchCheckIn;
    private SeekBar     seekCheckInInterval;
    private TextView    tvCheckInIntervalLabel;
    private SeekBar     seekSpeechRate;
    private TextView    tvSpeechRateLabel;
    private RadioGroup  rgLanguage;
    private RadioButton rbLangEn, rbLangHi;
    private Button      btnSaveSettings;
    private Button      btnTestTts;
    private SwitchCompat switchDarkMode;
    // IDS-level additions
    private SwitchCompat switchSilentMode;
    private RadioGroup  rgUserMode;
    private RadioButton rbModeDefault, rbModeVision, rbModeHearing, rbModeMotor;
    private SeekBar     seekEscalationDelay;
    private TextView    tvEscalationDelayLabel;

    private SharedPreferences prefs;
    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.action_settings));
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        tts   = TtsManager.getInstance(this);

        bindViews();
        loadSettings();

        seekSpeechRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSpeechRateLabel.setText(getSpeechRateLabel(progress));
            }
        });

        btnTestTts.setOnClickListener(v -> {
            boolean on = switchTts.isChecked();
            tts.setEnabled(on);
            float rate = seekBarToRate(seekSpeechRate.getProgress());
            tts.setSpeechRate(rate);
            String lang = rbLangHi.isChecked() ? "hi" : "en";
            tts.setLocale(new Locale(lang));
            tts.speakNow(getString(R.string.tts_test_message));
        });

        btnOpenAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Panic duration seek (steps: 30,60,90,120,180,240,300 s)
        seekPanicDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvPanicDurationLabel.setText(panicStepToSeconds(p) + "s");
            }
        });

        // Check-in interval seek (steps: 15,30,60,120,240,480 min)
        seekCheckInInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvCheckInIntervalLabel.setText(checkInStepToMinutes(p) + " min");
            }
        });

        btnSaveSettings.setOnClickListener(v -> saveSettings());

        // Escalation delay seekbar
        if (seekEscalationDelay != null) {
            seekEscalationDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (tvEscalationDelayLabel != null)
                        tvEscalationDelayLabel.setText(escalationStepToSeconds(p) + "s");
                }
            });
        }

        // Dark Mode switch — set listener AFTER loadSettings to avoid infinite recreation loop
        if (switchDarkMode != null) {
            switchDarkMode.setOnCheckedChangeListener((v, isChecked) -> {
                boolean current = prefs.getBoolean(KEY_DARK_MODE, true);
                if (isChecked != current) {
                    prefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply();
                    recreate();
                }
            });
        }
    }

    private void applyTheme() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    private void bindViews() {
        switchDarkMode    = findViewById(R.id.switchDarkMode);
        rgCountdown       = findViewById(R.id.rgCountdown);
        rb3s              = findViewById(R.id.rb3s);
        rb5s              = findViewById(R.id.rb5s);
        rb10s             = findViewById(R.id.rb10s);
        etCustomMessage   = findViewById(R.id.etCustomMessage);
        rgTextSize        = findViewById(R.id.rgTextSize);
        rbNormalText      = findViewById(R.id.rbNormalText);
        rbLargeText       = findViewById(R.id.rbLargeText);
        switchTts         = findViewById(R.id.switchTts);
        switchShake       = findViewById(R.id.switchShake);
        seekSpeechRate    = findViewById(R.id.seekSpeechRate);
        tvSpeechRateLabel = findViewById(R.id.tvSpeechRateLabel);
        rgLanguage        = findViewById(R.id.rgLanguage);
        rbLangEn          = findViewById(R.id.rbLangEn);
        rbLangHi          = findViewById(R.id.rbLangHi);
        btnSaveSettings   = findViewById(R.id.btnSaveSettings);
        btnTestTts        = findViewById(R.id.btnTestTts);
        switchVolume         = findViewById(R.id.switchVolume);
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility);
        // IDS additions
        switchSilentMode     = findViewById(R.id.switchSilentMode);
        rgUserMode           = findViewById(R.id.rgUserMode);
        rbModeDefault        = findViewById(R.id.rbModeDefault);
        rbModeVision         = findViewById(R.id.rbModeVision);
        rbModeHearing        = findViewById(R.id.rbModeHearing);
        rbModeMotor          = findViewById(R.id.rbModeMotor);
        seekEscalationDelay  = findViewById(R.id.seekEscalationDelay);
        tvEscalationDelayLabel = findViewById(R.id.tvEscalationDelayLabel);
        seekPanicDuration    = findViewById(R.id.seekPanicDuration);
        tvPanicDurationLabel = findViewById(R.id.tvPanicDurationLabel);
        switchCheckIn        = findViewById(R.id.switchCheckIn);
        seekCheckInInterval  = findViewById(R.id.seekCheckInInterval);
        tvCheckInIntervalLabel = findViewById(R.id.tvCheckInIntervalLabel);
    }

    private void loadSettings() {
        int countdown = prefs.getInt(KEY_COUNTDOWN, 5);
        if (countdown == 3)  rb3s.setChecked(true);
        else if (countdown == 10) rb10s.setChecked(true);
        else rb5s.setChecked(true);

        String defaultMsg = getString(R.string.default_sos_message);
        etCustomMessage.setText(prefs.getString(KEY_CUSTOM_MSG, defaultMsg));

        boolean largeText = prefs.getBoolean(KEY_LARGE_TEXT, false);
        if (largeText) rbLargeText.setChecked(true);
        else rbNormalText.setChecked(true);

        switchTts.setChecked(prefs.getBoolean(KEY_TTS_ENABLED, true));
        switchShake.setChecked(prefs.getBoolean(KEY_SHAKE_TRIGGER, true));
        switchVolume.setChecked(prefs.getBoolean(KEY_VOLUME_TRIGGER, false));

        // IDS additions
        if (switchSilentMode != null)
            switchSilentMode.setChecked(prefs.getBoolean(KEY_SILENT_MODE, false));

        String userModeVal = prefs.getString(KEY_USER_MODE, UserModeManager.MODE_DEFAULT);
        if (rgUserMode != null) {
            if      (UserModeManager.MODE_VISION.equals(userModeVal))  rbModeVision.setChecked(true);
            else if (UserModeManager.MODE_HEARING.equals(userModeVal)) rbModeHearing.setChecked(true);
            else if (UserModeManager.MODE_MOTOR.equals(userModeVal))   rbModeMotor.setChecked(true);
            else                                                        rbModeDefault.setChecked(true);
        }

        int escalDelay = prefs.getInt(KEY_ESCALATION_DELAY, EscalationManager.DEFAULT_DELAY_S);
        int escalStep  = secondsToEscalationStep(escalDelay);
        if (seekEscalationDelay != null) {
            seekEscalationDelay.setProgress(escalStep);
            if (tvEscalationDelayLabel != null)
                tvEscalationDelayLabel.setText(escalDelay + "s");
        }

        // Auto-SOS
        int panicSecs = prefs.getInt(AutoSosManager.KEY_PANIC_DURATION, AutoSosManager.DEFAULT_PANIC_SECS);
        int panicStep = secondsToPanicStep(panicSecs);
        seekPanicDuration.setProgress(panicStep);
        tvPanicDurationLabel.setText(panicSecs + "s");

        boolean checkInOn = prefs.getBoolean(AutoSosManager.KEY_CHECKIN_ENABLED, false);
        switchCheckIn.setChecked(checkInOn);
        int checkInMin = prefs.getInt(AutoSosManager.KEY_CHECKIN_INTERVAL, AutoSosManager.DEFAULT_CHECKIN_MIN);
        int checkInStep = minutesToCheckInStep(checkInMin);
        seekCheckInInterval.setProgress(checkInStep);
        tvCheckInIntervalLabel.setText(checkInMin + " min");

        if (switchDarkMode != null) {
            switchDarkMode.setChecked(prefs.getBoolean(KEY_DARK_MODE, true));
        }

        int rateProgress = prefs.getInt(KEY_SPEECH_RATE, 50);
        seekSpeechRate.setProgress(rateProgress);
        tvSpeechRateLabel.setText(getSpeechRateLabel(rateProgress));

        String lang = prefs.getString(KEY_LANGUAGE, "en");
        if ("hi".equals(lang)) rbLangHi.setChecked(true);
        else rbLangEn.setChecked(true);
    }

    private void saveSettings() {
        int countdown = 5;
        int selId = rgCountdown.getCheckedRadioButtonId();
        if (selId == R.id.rb3s)  countdown = 3;
        else if (selId == R.id.rb10s) countdown = 10;

        String customMsg = etCustomMessage.getText().toString().trim();
        if (customMsg.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_empty_message), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean largeText  = rgTextSize.getCheckedRadioButtonId() == R.id.rbLargeText;
        boolean ttsEnabled = switchTts.isChecked();
        boolean shakeOn    = switchShake.isChecked();
        boolean volumeOn   = switchVolume.isChecked();
        int     rateProgress = seekSpeechRate.getProgress();
        String  lang       = rbLangHi.isChecked() ? "hi" : "en";

        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_COUNTDOWN,     countdown);
        editor.putString(KEY_CUSTOM_MSG, customMsg);
        editor.putBoolean(KEY_LARGE_TEXT,    largeText);
        editor.putBoolean(KEY_TTS_ENABLED,   ttsEnabled);
        editor.putBoolean(KEY_SHAKE_TRIGGER, shakeOn);
        editor.putBoolean(KEY_VOLUME_TRIGGER, volumeOn);

        // IDS additions
        boolean silentOn = switchSilentMode != null && switchSilentMode.isChecked();
        editor.putBoolean(KEY_SILENT_MODE, silentOn);

        String userModeVal = UserModeManager.MODE_DEFAULT;
        if (rgUserMode != null) {
            int sel = rgUserMode.getCheckedRadioButtonId();
            if      (sel == R.id.rbModeVision)  userModeVal = UserModeManager.MODE_VISION;
            else if (sel == R.id.rbModeHearing) userModeVal = UserModeManager.MODE_HEARING;
            else if (sel == R.id.rbModeMotor)   userModeVal = UserModeManager.MODE_MOTOR;
        }
        editor.putString(KEY_USER_MODE, userModeVal);

        int escalDelay = escalationStepToSeconds(
                seekEscalationDelay != null ? seekEscalationDelay.getProgress() : 2);
        editor.putInt(KEY_ESCALATION_DELAY, escalDelay);
        editor.putInt(KEY_SPEECH_RATE,    rateProgress);
        editor.putString(KEY_LANGUAGE,    lang);
        // Auto-SOS
        int panicSecs   = panicStepToSeconds(seekPanicDuration.getProgress());
        int checkInMins = checkInStepToMinutes(seekCheckInInterval.getProgress());
        boolean checkInOn = switchCheckIn.isChecked();
        editor.putInt(AutoSosManager.KEY_PANIC_DURATION,    panicSecs);
        editor.putInt(AutoSosManager.KEY_CHECKIN_INTERVAL,  checkInMins);
        editor.putBoolean(AutoSosManager.KEY_CHECKIN_ENABLED, checkInOn);
        editor.apply();

        // If volume trigger was just enabled, remind user to grant Accessibility
        if (volumeOn && !isAccessibilityServiceRunning()) {
            tts.speak(getString(R.string.tts_accessibility_needed));
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_accessibility_title))
                    .setMessage(getString(R.string.dialog_accessibility_msg))
                    .setPositiveButton(getString(R.string.dialog_open_accessibility), (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        }

        // Apply TTS changes immediately
        tts.setEnabled(ttsEnabled);
        tts.setSpeechRate(seekBarToRate(rateProgress));
        tts.setLocale(new Locale(lang));

        tts.speak(getString(R.string.tts_settings_saved));
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
        finish();
    }

    /** SeekBar 0-100 → TTS rate 0.5–2.0 */
    private float seekBarToRate(int progress) {
        return 0.5f + (progress / 100f) * 1.5f;
    }

    private String getSpeechRateLabel(int progress) {
        float rate = seekBarToRate(progress);
        if (rate < 0.75f) return getString(R.string.rate_slow);
        if (rate < 1.15f) return getString(R.string.rate_normal);
        if (rate < 1.5f)  return getString(R.string.rate_fast);
        return getString(R.string.rate_very_fast);
    }

    // Panic duration steps: 0→30s, 1→60s, 2→90s, 3→120s, 4→180s, 5→240s, 6→300s
    private static final int[] PANIC_STEPS = {30, 60, 90, 120, 180, 240, 300};

    private int panicStepToSeconds(int step) {
        return PANIC_STEPS[Math.min(step, PANIC_STEPS.length - 1)];
    }

    private int secondsToPanicStep(int secs) {
        for (int i = 0; i < PANIC_STEPS.length; i++) {
            if (PANIC_STEPS[i] >= secs) return i;
        }
        return 1; // default
    }

    // Check-in interval steps: 0→15, 1→30, 2→60, 3→120, 4→240, 5→480 min
    private static final int[] CHECKIN_STEPS = {15, 30, 60, 120, 240, 480};

    private int checkInStepToMinutes(int step) {
        return CHECKIN_STEPS[Math.min(step, CHECKIN_STEPS.length - 1)];
    }

    private int minutesToCheckInStep(int mins) {
        for (int i = 0; i < CHECKIN_STEPS.length; i++) {
            if (CHECKIN_STEPS[i] >= mins) return i;
        }
        return 1;
    }

    // Escalation steps: 0→30s, 1→45s, 2→60s, 3→90s, 4→120s, 5→180s
    private static final int[] ESCALATION_STEPS = {30, 45, 60, 90, 120, 180};

    private int escalationStepToSeconds(int step) {
        return ESCALATION_STEPS[Math.min(step, ESCALATION_STEPS.length - 1)];
    }

    private int secondsToEscalationStep(int secs) {
        for (int i = 0; i < ESCALATION_STEPS.length; i++) {
            if (ESCALATION_STEPS[i] >= secs) return i;
        }
        return 2; // default 60s
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Returns true if VolumeButtonSosService is currently enabled. */
    private boolean isAccessibilityServiceRunning() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        String expectedId = getPackageName() + "/" + VolumeButtonSosService.class.getName();

        // 1. Check via AccessibilityManager
        java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (android.accessibilityservice.AccessibilityServiceInfo info : enabledServices) {
            if (expectedId.equals(info.getId())) return true;
        }

        // 2. Fallback: Check via Secure Settings
        try {
            String enabledSetting = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledSetting != null) {
                return enabledSetting.toLowerCase().contains(getPackageName().toLowerCase())
                        && enabledSetting.contains(VolumeButtonSosService.class.getName());
            }
        } catch (Exception ignored) {}

        return false;
    }
}
