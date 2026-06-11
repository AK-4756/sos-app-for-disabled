package com.sosapp.disabled;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SOSApp";
    private static final int    PERMISSION_REQUEST_CODE = 100;

    // ─── UI views ─────────────────────────────────────────────────────────────
    private Button      btnSOS;
    private Button      btnCancel;
    private TextView    tvStatus;
    private TextView    tvCountdown;
    private ProgressBar progressCountdown;
    private ImageButton btnVoice;
    private TextView    tvContactCount;
    private TextView    tvShakeHint;
    private TextView    tvVolumeHint;
    private android.widget.LinearLayout panelPanic;
    private TextView    tvPanicCountdown;
    private Button      btnImSafe;
    private Button      btnCheckIn;
    private Spinner     spinnerLocation;    // quick-select location

    // ─── Services ─────────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;
    private DatabaseHelper       dbHelper;
    private TtsManager           tts;
    private HapticManager        haptic;
    private ShakeDetector        shakeDetector;
    private SpeechRecognizer     speechRecognizer;
    private CountDownTimer       countDownTimer;
    private BroadcastReceiver    volumeSosReceiver;
    private BroadcastReceiver    smsAckReceiver;
    private SmsAckReceiver       rawSmsReceiver;   // stored so it can be unregistered
    private ActivityResultLauncher<Intent> qrLauncher;
    private AutoSosManager       autoSosManager;
    private FirebaseManager      firebase;
    private EscalationManager    escalation;
    private SmsDispatcher        smsDispatcher;
    private OfflineSyncManager   offlineSync;
    private UserModeManager      userMode;
    private SavedLocationManager locationMgr;
    private FallDetectionManager fallDetection;
    private FalseAlertManager    falseAlertMgr;
    private CampusZoneManager    campusZoneMgr;
    private ProfileManager       profileMgr;
    private VolunteerAssignmentManager volunteerMgr;
    private RiskScoringManager   riskMgr;
    private WearableTriggerManager wearableMgr;
    private RouteMonitorManager  routeMonitor;
    private ListenerRegistration firebaseListener;

    // ─── State ────────────────────────────────────────────────────────────────
    private boolean  isCountingDown    = false;
    private boolean  voiceListening    = false;
    private boolean  silentMode        = false;
    private Location lastKnownLocation = null;
    private int      lastAnnouncedSecond = -1;
    private String   currentTriggerType = AlertRecord.TRIGGER_BUTTON;
    private int      lastLocalAlertId   = -1;
    private String   lastFirebaseId     = null;
    private boolean  currentDarkMode;

    private static final String[] REQUIRED_PERMISSIONS;
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECEIVE_SMS
            };
        } else {
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.RECEIVE_SMS
            };
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        currentDarkMode = getPrefs().getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        super.onCreate(savedInstanceState);
        setupScreenWake();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // Register QR result launcher (must be done before onStart)
        qrLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && result.getData() != null) {
                        String zoneName = result.getData()
                                .getStringExtra(QrScannerActivity.EXTRA_ZONE_NAME);
                        if (zoneName != null) {
                            tts.speak(getString(R.string.qr_scan_success, zoneName));
                            refreshLocationSpinner();
                        }
                    }
                });

        initServices();
        initViews();
        checkAndRequestPermissions();
        updateContactCount();
        prefetchLocation();
        applyAccessibilityScale();

        handleIntent(getIntent());

        if (!silentMode) tts.speak(getString(R.string.tts_app_ready));

        // Sync user profile to Firebase
        firebase.syncUserProfile(this);
    }

    private void setupScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void applyTheme() {
        SharedPreferences prefs = getPrefs();
        boolean isDark = prefs.getBoolean("dark_mode", true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Theme refresh check
        boolean isDark = getPrefs().getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        if (isDark != currentDarkMode) {
            recreate();
            return;
        }

        updateContactCount();
        prefetchLocation();
        applyAccessibilityScale();
        refreshLocationSpinner();

        SharedPreferences prefs = getPrefs();
        if (prefs.getBoolean(SettingsActivity.KEY_SHAKE_TRIGGER, true)) {
            shakeDetector.start();
        }

        // Silent mode — read on every resume in case settings changed
        silentMode = prefs.getBoolean(SettingsActivity.KEY_SILENT_MODE, false)
                || userMode.isForcedSilent();

        registerVolumeSosReceiver();
        registerSmsAckReceiver();
        refreshVolumeHint();
        offlineSync.register();

        // Start fall detection if available
        if (fallDetection != null && fallDetection.isAvailable()) {
            fallDetection.start();
        }
        wearableMgr.register();

        List<Contact> contacts = dbHelper.getAllContacts();
        NotificationHelper.showStatusNotification(this,
                contacts.size() + " " + getString(R.string.status_contacts_ready));

        if (AutoSosManager.isCheckInEnabled(this) && !autoSosManager.isPanicActive()) {
            autoSosManager.startCheckIn(AutoSosManager.getCheckInInterval(this));
            if (btnCheckIn != null) btnCheckIn.setVisibility(View.VISIBLE);
        } else if (!AutoSosManager.isCheckInEnabled(this)) {
            if (btnCheckIn != null) btnCheckIn.setVisibility(View.GONE);
        }

        // Re-attach Firebase listener if we have an active alert
        if (lastFirebaseId != null) attachFirebaseListener(lastFirebaseId);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (VolumeButtonSosService.ACTION_VOLUME_SOS.equals(intent.getAction())) {
            if (!isCountingDown) {
                haptic.shakeDetected();
                if (!silentMode) tts.speakNow(getString(R.string.tts_volume_sos_detected));
                currentTriggerType = AlertRecord.TRIGGER_VOLUME;
                startSOSCountdown();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        shakeDetector.stop();
        unregisterVolumeSosReceiver();
        unregisterSmsAckReceiver();
        offlineSync.unregister();
        if (smsDispatcher != null) smsDispatcher.cleanup();
        if (fallDetection != null) fallDetection.stop();
        wearableMgr.unregister();
        if (firebaseListener != null) {
            firebaseListener.remove();
            firebaseListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (countDownTimer   != null) countDownTimer.cancel();
        if (dbHelper         != null) dbHelper.close();
        if (autoSosManager   != null) autoSosManager.destroy();
        if (escalation       != null) escalation.stop();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Init
    // ══════════════════════════════════════════════════════════════════════════

    private void initServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        dbHelper    = new DatabaseHelper(this);
        tts         = TtsManager.getInstance(this);
        haptic      = new HapticManager(this);
        firebase    = FirebaseManager.getInstance(this);
        escalation  = new EscalationManager(this);
        smsDispatcher = new SmsDispatcher(this, buildSmsCallback());
        offlineSync = new OfflineSyncManager(this);
        falseAlertMgr = FalseAlertManager.getInstance(this);
        campusZoneMgr = CampusZoneManager.getInstance(this);
        profileMgr    = ProfileManager.getInstance(this);
        volunteerMgr  = VolunteerAssignmentManager.getInstance(this);
        riskMgr       = RiskScoringManager.getInstance(this);
        wearableMgr   = new WearableTriggerManager(this);
        routeMonitor  = RouteMonitorManager.getInstance(this);

        // Wearable SOS bridge
        wearableMgr.setSOSTriggerListener(triggerType -> runOnUiThread(() -> {
            if (!isCountingDown) {
                haptic.shakeDetected();
                if (!silentMode) tts.speakNow(triggerType);
                currentTriggerType = triggerType;
                startSOSCountdown();
            }
        }));
        userMode    = new UserModeManager(this, tts, haptic);
        locationMgr = new SavedLocationManager(this);

        // Read initial silent mode
        silentMode = getPrefs().getBoolean(SettingsActivity.KEY_SILENT_MODE, false)
                || userMode.isForcedSilent();

        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        shakeDetector = new ShakeDetector(sm);
        shakeDetector.setOnShakeListener(() -> runOnUiThread(() -> {
            if (!isCountingDown) {
                haptic.shakeDetected();
                if (!silentMode) tts.speakNow(getString(R.string.tts_shake_detected));
                currentTriggerType = AlertRecord.TRIGGER_SHAKE;
                startSOSCountdown();
            }
        }));

        autoSosManager = new AutoSosManager(this, new AutoSosManager.AutoSosTriggerCallback() {
            @Override
            public void onAutoSosTrigger(String reason) {
                runOnUiThread(() -> {
                    if (!isCountingDown) {
                        if (!silentMode) tts.speakNow(getString(R.string.tts_auto_sos_triggered));
                        haptic.sosSent();
                        currentTriggerType = AlertRecord.TRIGGER_AUTO;
                        startSOSCountdown();
                    }
                });
            }

            @Override
            public void onPanicTimerTick(int secondsLeft) {
                runOnUiThread(() -> {
                    if (tvPanicCountdown != null) {
                        tvPanicCountdown.setText(String.valueOf(secondsLeft));
                    }
                });
                if (secondsLeft == 30 || secondsLeft == 20 || secondsLeft == 10
                        || secondsLeft <= 5) {
                    if (!silentMode) tts.speak(secondsLeft + " " + getString(R.string.tts_seconds));
                }
            }

            @Override
            public void onPanicTimerCancelled() {
                runOnUiThread(() -> {
                    if (panelPanic != null) panelPanic.setVisibility(View.GONE);
                    if (!silentMode) tts.speak(getString(R.string.tts_panic_cancelled));
                    setStatus(getString(R.string.status_idle));
                });
            }

            @Override
            public void onCheckInReminderDue() {
                runOnUiThread(() -> {
                    if (!silentMode) tts.speakNow(getString(R.string.tts_checkin_reminder));
                    haptic.shakeDetected();
                    NotificationHelper.showCheckInReminder(MainActivity.this);
                });
            }
        });

        // Fall detection — init now, start in onResume
        fallDetection = new FallDetectionManager(this);
        if (fallDetection.isAvailable()) {
            fallDetection.setCallback(new FallDetectionManager.FallCallback() {
                @Override
                public void onFallConfirmed(float confidence) {
                    runOnUiThread(() -> {
                        if (!isCountingDown) {
                            haptic.sosSent();
                            if (!silentMode) tts.speakNow(getString(R.string.fall_trigger_type));
                            currentTriggerType = AlertRecord.TRIGGER_FALL;
                            startSOSCountdown();
                        }
                    });
                }

                @Override
                public void onCountdownTick(int secondsRemaining) {
                    runOnUiThread(() -> {
                        String msg = getString(R.string.fall_countdown_tts, secondsRemaining);
                        if (!silentMode) tts.speak(msg);
                        setStatus(getString(R.string.fall_detected_msg, secondsRemaining));
                        haptic.countdownTick();
                    });
                }

                @Override
                public void onCancelled() {
                    runOnUiThread(() -> {
                        if (!silentMode) tts.speak(getString(R.string.fall_cancelled_tts));
                        setStatus(getString(R.string.status_idle));
                        haptic.cancelled();
                    });
                }
            });
        }
    }

    private void initViews() {
        btnSOS           = findViewById(R.id.btnSOS);
        btnCancel        = findViewById(R.id.btnCancel);
        tvStatus         = findViewById(R.id.tvStatus);
        tvCountdown      = findViewById(R.id.tvCountdown);
        progressCountdown = findViewById(R.id.progressCountdown);
        btnVoice         = findViewById(R.id.btnVoice);
        tvContactCount   = findViewById(R.id.tvContactCount);
        tvShakeHint      = findViewById(R.id.tvShakeHint);
        tvVolumeHint     = findViewById(R.id.tvVolumeHint);
        panelPanic       = findViewById(R.id.panelPanic);
        tvPanicCountdown = findViewById(R.id.tvPanicCountdown);
        btnImSafe        = findViewById(R.id.btnImSafe);
        btnCheckIn       = findViewById(R.id.btnCheckIn);
        spinnerLocation  = findViewById(R.id.spinnerLocation);

        btnImSafe.setOnClickListener(v -> {
            autoSosManager.userIsSafe();
            panelPanic.setVisibility(View.GONE);
        });
        btnCheckIn.setOnClickListener(v -> {
            autoSosManager.userCheckedIn();
            if (!silentMode) tts.speak(getString(R.string.tts_checkin_confirmed));
            haptic.cancelled();
            Toast.makeText(this, getString(R.string.tts_checkin_confirmed), Toast.LENGTH_SHORT).show();
        });

        btnSOS.setOnClickListener(v -> {
            currentTriggerType = AlertRecord.TRIGGER_BUTTON;
            startSOSCountdown();
        });
        btnSOS.setOnLongClickListener(v -> {
            if (!silentMode) tts.speakNow(getString(R.string.tts_long_press_confirm));
            haptic.longPressHold();
            currentTriggerType = AlertRecord.TRIGGER_LONG;
            startSOSCountdown();
            return true;
        });
        btnSOS.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !isAccessibilityEnabled()) {
                haptic.countdownTick();
            }
            return false;
        });
        btnCancel.setOnClickListener(v -> cancelSOS());
        btnVoice.setOnClickListener(v -> toggleVoiceListening());

        btnCancel.setVisibility(View.GONE);
        tvCountdown.setVisibility(View.GONE);
        progressCountdown.setVisibility(View.GONE);

        SharedPreferences prefs = getPrefs();
        tvShakeHint.setVisibility(prefs.getBoolean(SettingsActivity.KEY_SHAKE_TRIGGER, true)
                ? View.VISIBLE : View.GONE);
        refreshVolumeHint();

        // Apply User Mode visual adaptations
        userMode.applyMode(btnSOS, tvStatus);
    }

    private void applyAccessibilityScale() {
        SharedPreferences prefs = getPrefs();
        boolean large = prefs.getBoolean(SettingsActivity.KEY_LARGE_TEXT, false);
        if (!userMode.isForcedSilent()) { // HEARING mode manages TTS itself
            tts.setSpeechRate(large ? 0.85f : 1.0f);
        }
        float statusSize  = large ? 22f : 18f;
        float contactSize = large ? 20f : 16f;
        tvStatus.setTextSize(statusSize);
        tvContactCount.setTextSize(contactSize);

        // Re-apply mode (settings may have changed)
        userMode.applyMode(btnSOS, tvStatus);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Location Spinner
    // ══════════════════════════════════════════════════════════════════════════

    private void refreshLocationSpinner() {
        if (spinnerLocation == null) return;
        List<String> labels = new ArrayList<>();
        labels.add(SavedLocationManager.LABEL_LIVE);
        for (SavedLocationManager.SavedLocation loc : locationMgr.getAll()) {
            labels.add(loc.name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLocation.setAdapter(adapter);
    }

    private void prefetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) lastKnownLocation = location;
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Contact count banner
    // ══════════════════════════════════════════════════════════════════════════

    private void updateContactCount() {
        List<Contact> contacts = dbHelper.getAllContacts();
        int count = contacts.size();
        if (count == 0) {
            String msg = getString(R.string.status_no_contacts);
            tvContactCount.setText(msg);
            tvContactCount.setContentDescription(msg);
            tvContactCount.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else {
            String msg = count + " " + getString(R.string.status_contacts_ready);
            tvContactCount.setText(msg);
            tvContactCount.setContentDescription(msg);
            tvContactCount.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
        tvContactCount.setOnClickListener(v -> openContacts());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SOS Countdown
    // ══════════════════════════════════════════════════════════════════════════

    private void startSOSCountdown() {
        List<Contact> contacts = dbHelper.getAllContacts();
        if (contacts.isEmpty()) {
            if (!silentMode) tts.speakNow(getString(R.string.tts_no_contacts));
            haptic.error();
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_no_contacts_title))
                    .setMessage(getString(R.string.dialog_no_contacts_msg))
                    .setPositiveButton(getString(R.string.dialog_add_contact), (d, w) -> openContacts())
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
            return;
        }

        if (isCountingDown) return;
        isCountingDown = true;
        lastAnnouncedSecond = -1;

        SharedPreferences prefs = getPrefs();
        final int countdownSeconds = userMode.getEffectiveCountdown(
                prefs.getInt(SettingsActivity.KEY_COUNTDOWN, 5));

        btnSOS.setEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        tvCountdown.setVisibility(View.VISIBLE);
        progressCountdown.setVisibility(View.VISIBLE);
        progressCountdown.setMax(countdownSeconds * 10);
        progressCountdown.setProgress(countdownSeconds * 10);

        if (!silentMode) {
            String startMsg = getString(R.string.tts_sos_activated) + ". "
                    + countdownSeconds + " " + getString(R.string.tts_seconds_until_send);
            tts.speakNow(startMsg);
        }
        setStatus(getString(R.string.status_countdown_prefix) + " " + countdownSeconds + "s...");

        // Silent mode: show indicator in status
        if (silentMode) setStatus("🔕 " + getString(R.string.status_countdown_prefix)
                + " " + countdownSeconds + "s... (Silent)");

        countDownTimer = new CountDownTimer(countdownSeconds * 1000L, 100) {
            int totalTicks = countdownSeconds * 10;
            int ticksDone  = 0;

            @Override
            public void onTick(long millisUntilFinished) {
                ticksDone++;
                int secsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText(String.valueOf(secsLeft));
                progressCountdown.setProgress(totalTicks - ticksDone);

                if (secsLeft != lastAnnouncedSecond) {
                    lastAnnouncedSecond = secsLeft;
                    haptic.countdownTick();
                    if (!silentMode && secsLeft <= 3) tts.speakNow(String.valueOf(secsLeft));
                }
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("0");
                triggerSOS();
            }
        }.start();
    }

    private void cancelSOS() {
        if (countDownTimer != null) { countDownTimer.cancel(); countDownTimer = null; }
        isCountingDown = false;
        btnSOS.setEnabled(true);
        btnCancel.setVisibility(View.GONE);
        tvCountdown.setVisibility(View.GONE);
        progressCountdown.setVisibility(View.GONE);

        haptic.cancelled();
        if (!silentMode) tts.speakNow(getString(R.string.tts_sos_cancelled));
        setStatus(getString(R.string.status_idle));
        announceForAccessibility(getString(R.string.tts_sos_cancelled));

        AlertRecord record = new AlertRecord(System.currentTimeMillis(), currentTriggerType,
                AlertRecord.STATUS_CANCELLED, 0, null, BatteryHelper.getPercent(this));
        record.setSilentMode(silentMode);
        dbHelper.insertAlertRecord(record);
    }

    private void triggerSOS() {
        isCountingDown = false;
        btnCancel.setVisibility(View.GONE);
        tvCountdown.setVisibility(View.GONE);
        progressCountdown.setVisibility(View.GONE);

        if (!silentMode) tts.speakNow(getString(R.string.tts_sending));
        setStatus(silentMode ? "🔕 " + getString(R.string.status_sending)
                             : getString(R.string.status_sending));
        haptic.sosSent();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) lastKnownLocation = location;
                sendSOSMessages(lastKnownLocation);
            });
        } else {
            sendSOSMessages(null);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SMS Dispatch + Firebase + Escalation  (v5 — production grade)
    // ══════════════════════════════════════════════════════════════════════════

    private void sendSOSMessages(Location location) {
        List<Contact> contacts = dbHelper.getAllContacts();
        if (contacts.isEmpty()) {
            if (!silentMode) tts.speakNow(getString(R.string.tts_no_contacts));
            haptic.error();
            setStatus(getString(R.string.status_no_contacts_error));
            btnSOS.setEnabled(true);
            return;
        }

        // ── Duplicate suppression ────────────────────────────────────────────
        if (!falseAlertMgr.shouldAllow()) {
            tts.speak("Alert already active. Please wait.");
            btnSOS.setEnabled(true);
            return;
        }

        // ── Resolve location: campus zone > spinner > GPS ────────────────────
        SavedLocationManager.LocationResult tempLocResult;
        final CampusZone detectedZone = campusZoneMgr.resolveZone(location);
        if (detectedZone != null) {
            // Auto-detected campus zone takes priority
            tempLocResult = new SavedLocationManager.LocationResult(
                    detectedZone.toLocationString() + ": " + detectedZone.toMapUrl(),
                    detectedZone.toLocationString(),
                    detectedZone.getLatitude() + "," + detectedZone.getLongitude());
        } else {
            String selectedName = (spinnerLocation != null && spinnerLocation.getSelectedItem() != null)
                    ? spinnerLocation.getSelectedItem().toString() : null;
            tempLocResult = locationMgr.resolveLocation(selectedName, location);
        }
        final SavedLocationManager.LocationResult locResult = tempLocResult;

        // ── Location label with accuracy ────────────────────────────────────
        float accuracyM = (location != null && location.hasAccuracy())
                ? location.getAccuracy() : -1f;
        String locDisplay = locResult.text;
        if (accuracyM > 0 && accuracyM < 5000) {
            locDisplay += " (±" + Math.round(accuracyM) + "m)";
        }

        // ── Battery ─────────────────────────────────────────────────────────
        int batteryPct = BatteryHelper.getPercent(this);
        String batteryInfo = batteryPct >= 0
                ? getString(R.string.battery_label) + " " + batteryPct + "%" : "";

        // ── Build message ───────────────────────────────────────────────────
        SharedPreferences prefs = getPrefs();
        String template = prefs.getString(SettingsActivity.KEY_CUSTOM_MSG,
                getString(R.string.default_sos_message));
        String silentTag = silentMode ? "\n[Silent SOS — Do not call back, just come]" : "";
        String message = template
                .replace("{location}", locDisplay)
                .replace("{battery}", batteryInfo)
                + silentTag;
        if (!template.contains("{battery}") && !batteryInfo.isEmpty()) {
            message += "\n" + batteryInfo;
        }

        // ── Append medical summary if user has set it ────────────────────────
        UserProfile profile = profileMgr.getProfile();
        if (profile.isShareProfileOnSOS() && profile.isProfileComplete()) {
            String medical = profile.getMedicalSummary();
            if (!medical.isEmpty()) message += medical;
        }

        // ── Create local DB record (CREATED — pending Firebase) ─────────────
        AlertRecord record = new AlertRecord(System.currentTimeMillis(), currentTriggerType,
                AlertRecord.STATUS_CREATED, 0, locResult.latLngSnap, batteryPct);
        record.setLocationLabel(locResult.label);
        record.setLocationAccuracyM(accuracyM);
        record.setSilentMode(silentMode);
        record.setPendingFirebaseSync(true);          // mark for offline retry
        long localId = dbHelper.insertAlertRecord(record);
        lastLocalAlertId = (int) localId;
        lastFirebaseId   = null; // Reset until Firebase confirms creation

        final String finalMessage = message;
        final List<Contact> finalContacts = contacts;

        // ── 1. Dispatch SMS immediately (Production Priority #1) ─────────────
        doDispatchSms(finalContacts, finalMessage, (int) localId, null, batteryPct);

        // ── 2. Create Firebase Alert in parallel (Best effort) ───────────────
        firebase.createAlert(record, new FirebaseManager.OnAlertCreated() {
            @Override
            public void onCreated(String firebaseDocId) {
                lastFirebaseId = firebaseDocId;
                // Propagate ID to other managers so they can update Firestore later
                dbHelper.markFirebaseSynced((int) localId, firebaseDocId);
                dbHelper.updateAlertStatus((int) localId, AlertRecord.STATUS_CREATED, firebaseDocId);
                escalation.setFirebaseId(firebaseDocId);
                falseAlertMgr.onAlertSent((int) localId, firebaseDocId); // Update with FB ID
                attachFirebaseListener(firebaseDocId);

                // Auto-assign nearest available volunteer
                double alertLat = locResult.latLngSnap != null && locResult.latLngSnap.contains(",")
                        ? Double.parseDouble(locResult.latLngSnap.split(",")[0]) : 0;
                double alertLng = locResult.latLngSnap != null && locResult.latLngSnap.contains(",")
                        ? Double.parseDouble(locResult.latLngSnap.split(",")[1]) : 0;
                volunteerMgr.autoAssign(firebaseDocId, alertLat, alertLng,
                        detectedZone != null ? detectedZone.getType() : null,
                        new VolunteerAssignmentManager.AssignmentCallback() {
                            @Override
                            public void onAssigned(String id, String name, String phone) {
                                Log.d(TAG, "Volunteer assigned: " + name);
                                // Compute risk score now that we know assignment status
                                riskMgr.computeAndStore(firebaseDocId, record,
                                        0f, true,
                                        detectedZone != null ? detectedZone.getName() : null);
                            }
                            @Override
                            public void onNoVolunteerAvailable() {
                                riskMgr.computeAndStore(firebaseDocId, record,
                                        0f, false,
                                        detectedZone != null ? detectedZone.getName() : null);
                            }
                        });

                // Notify wearable
                wearableMgr.notifyWatchSosSent(finalContacts.size());
            }

            @Override
            public void onFailed(Exception e) {
                // Firebase unavailable — the pending_sync=1 flag stays; OfflineSyncManager will retry
                lastFirebaseId = null;
                Log.w(TAG, "Firebase unavailable — proceeding SMS-only, will sync later");
            }
        });
    }

    /**
     * Builds the SmsDispatcher.DispatchCallback that handles SENT/DELIVERED/FAILED
     * events and keeps DB + Firebase + UI consistent.
     */
    private SmsDispatcher.DispatchCallback buildSmsCallback() {
        return new SmsDispatcher.DispatchCallback() {

            @Override
            public void onContactSent(Contact contact, int sentCount, int totalCount) {
                runOnUiThread(() -> {
                    dbHelper.updateAlertStatus(lastLocalAlertId, AlertRecord.STATUS_SENT, lastFirebaseId);
                    firebase.markAlertSent(lastFirebaseId, sentCount);
                    String msg = getString(R.string.tts_sent_success, sentCount);
                    setStatus(msg + " (" + sentCount + "/" + totalCount + ")");
                    if (!silentMode) tts.speak(msg);
                    haptic.sosSent();
                    announceForAccessibility(msg);
                    NotificationHelper.showSosSentNotification(MainActivity.this, sentCount);
                });
            }

            @Override
            public void onContactDelivered(Contact contact) {
                runOnUiThread(() -> {
                    dbHelper.incrementDeliveredCount(lastLocalAlertId);
                    dbHelper.updateAlertStatus(lastLocalAlertId, AlertRecord.STATUS_DELIVERED, lastFirebaseId);
                    firebase.updateAlertStatus(lastFirebaseId, AlertRecord.STATUS_DELIVERED);
                    String msg = getString(R.string.tts_delivered, contact.getName());
                    setStatus(msg);
                    if (!silentMode) tts.speak(msg);
                    haptic.sosSent();
                });
            }

            @Override
            public void onContactFailed(Contact contact) {
                runOnUiThread(() -> {
                    Log.w(TAG, "SMS permanently failed to " + contact.getName());
                    String msg = getString(R.string.tts_sms_failed_contact, contact.getName());
                    setStatus(msg);
                    if (!silentMode) tts.speak(msg);
                    haptic.error();
                });
            }

            @Override
            public void onDispatchComplete(int successCount, int failCount) {
                runOnUiThread(() -> {
                    btnSOS.setEnabled(true);
                    if (successCount == 0 && failCount > 0) {
                        // Total failure — fallback to SMS app
                        dbHelper.updateAlertStatus(lastLocalAlertId,
                                AlertRecord.STATUS_FAILED, lastFirebaseId);
                        if (!silentMode) tts.speakNow(getString(R.string.tts_sent_failed));
                        haptic.error();
                        openSmsAppFallback();
                    }
                });
            }
        };
    }

    private void doDispatchSms(List<Contact> contacts, String message,
                                int localId, String firebaseId, int batteryPct) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            // No SMS permission — open SMS app as fallback immediately
            btnSOS.setEnabled(true);
            dbHelper.updateAlertStatus(localId, AlertRecord.STATUS_FAILED, firebaseId);
            openSmsAppFallback();
            return;
        }
        // Build a fresh dispatcher per alert to avoid state pollution between alerts
        smsDispatcher = new SmsDispatcher(this, buildSmsCallback());
        smsDispatcher.dispatch(contacts, message);

        // Arm escalation chain — passes first contact's phone for dedup
        String firstPhone = contacts.isEmpty() ? null : contacts.get(0).getPhone();
        escalation.startEscalation(message, localId, firebaseId, firstPhone);

        // Register with FalseAlertManager for duplicate suppression and post-send window
        falseAlertMgr.onAlertSent(localId, firebaseId);
    }

    private void openSmsAppFallback() {
        List<Contact> contacts = dbHelper.getAllContacts();
        if (contacts.isEmpty()) return;
        Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
        smsIntent.setData(android.net.Uri.parse("smsto:" + contacts.get(0).getPhone()));
        smsIntent.putExtra("sms_body", getString(R.string.default_sos_message)
                .replace("{location}", getString(R.string.location_unavailable))
                .replace("{battery}", ""));
        try { startActivity(smsIntent); } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase real-time status listener
    // ══════════════════════════════════════════════════════════════════════════

    private void attachFirebaseListener(String firebaseId) {
        if (firebaseListener != null) { firebaseListener.remove(); }
        firebaseListener = firebase.listenToAlert(firebaseId, (fId, newStatus) ->
                runOnUiThread(() -> onAlertStatusChanged(fId, newStatus)));
    }

    private void onAlertStatusChanged(String firebaseId, String newStatus) {
        Log.d(TAG, "Firebase status update: " + newStatus);
        dbHelper.updateAlertStatus(lastLocalAlertId, newStatus, firebaseId);
        wearableMgr.notifyWatchStatusUpdate(newStatus);

        switch (newStatus) {
            case AlertRecord.STATUS_ACKNOWLEDGED:
                escalation.acknowledge("Dashboard");
                if (!silentMode) tts.speakNow(getString(R.string.tts_acknowledged));
                haptic.sosSent();
                setStatus(getString(R.string.status_acknowledged));
                break;
            case AlertRecord.STATUS_ON_THE_WAY:
                if (!silentMode) tts.speakNow(getString(R.string.tts_on_the_way));
                haptic.sosSent();
                setStatus(getString(R.string.status_on_the_way));
                break;
            case AlertRecord.STATUS_RESOLVED:
                if (!silentMode) tts.speakNow(getString(R.string.tts_resolved));
                haptic.cancelled();
                setStatus(getString(R.string.status_resolved));
                if (firebaseListener != null) { firebaseListener.remove(); firebaseListener = null; }
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SMS Acknowledgement Receiver
    // ══════════════════════════════════════════════════════════════════════════

    private void registerSmsAckReceiver() {
        if (smsAckReceiver != null) return;
        smsAckReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!SmsAckReceiver.ACTION_SMS_ACKNOWLEDGED.equals(intent.getAction())) return;
                String sender = intent.getStringExtra(SmsAckReceiver.EXTRA_SENDER);
                Log.d(TAG, "SMS ACK from: " + sender);

                escalation.acknowledge(sender != null ? sender : "Unknown");
                dbHelper.updateAlertStatus(lastLocalAlertId, AlertRecord.STATUS_ACKNOWLEDGED, lastFirebaseId);
                firebase.markAlertAcknowledged(lastFirebaseId, sender);

                if (!silentMode) tts.speakNow(getString(R.string.tts_acknowledged));
                haptic.sosSent();
                setStatus(getString(R.string.status_acknowledged));
                announceForAccessibility(getString(R.string.tts_acknowledged));
                NotificationHelper.showSosSentNotification(MainActivity.this, 1);
            }
        };

        // Also register the raw SMS receiver
        rawSmsReceiver = new SmsAckReceiver();
        IntentFilter smsFilter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        smsFilter.setPriority(999);

        IntentFilter ackFilter = new IntentFilter(SmsAckReceiver.ACTION_SMS_ACKNOWLEDGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rawSmsReceiver, smsFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(smsAckReceiver, ackFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rawSmsReceiver, smsFilter);
            registerReceiver(smsAckReceiver, ackFilter);
        }
    }

    private void unregisterSmsAckReceiver() {
        if (smsAckReceiver != null) {
            try { unregisterReceiver(smsAckReceiver); } catch (Exception ignored) {}
            smsAckReceiver = null;
        }
        if (rawSmsReceiver != null) {
            try { unregisterReceiver(rawSmsReceiver); } catch (Exception ignored) {}
            rawSmsReceiver = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Voice Trigger
    // ══════════════════════════════════════════════════════════════════════════

    private void toggleVoiceListening() {
        if (voiceListening) {
            stopVoiceListening();
            if (!silentMode) tts.speak(getString(R.string.tts_voice_off));
        } else {
            startVoiceListening();
        }
    }

    private void startVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (!silentMode) tts.speak(getString(R.string.tts_mic_permission_needed));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (!silentMode) tts.speak(getString(R.string.tts_voice_unavailable));
            return;
        }
        voiceListening = true;
        btnVoice.setImageResource(android.R.drawable.presence_audio_online);
        btnVoice.setContentDescription(getString(R.string.cd_voice_active));
        setStatus(getString(R.string.status_listening));
        if (!silentMode) tts.speak(getString(R.string.tts_voice_on));

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onEndOfSpeech() {}

            @Override
            public void onResults(Bundle results) {
                if (!voiceListening) return;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    for (String phrase : matches) {
                        String lower = phrase.toLowerCase(Locale.ROOT);
                        if (lower.contains("sos") || lower.contains("help")
                                || lower.contains("emergency") || lower.contains("save me")
                                || lower.contains("madad") || lower.contains("bachao")) {
                            stopVoiceListening();
                            if (!silentMode) tts.speakNow(getString(R.string.tts_voice_sos_detected));
                            currentTriggerType = AlertRecord.TRIGGER_VOICE;
                            startSOSCountdown();
                            return;
                        }
                    }
                }
                restartVoiceListening();
            }

            @Override
            public void onError(int error) {
                if (voiceListening) {
                    btnVoice.postDelayed(() -> { if (voiceListening) restartVoiceListening(); }, 1000);
                }
            }
        });
        launchSpeechRecognition();
    }

    private void launchSpeechRecognition() {
        if (speechRecognizer == null) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        speechRecognizer.startListening(intent);
    }

    private void restartVoiceListening() {
        if (speechRecognizer != null && voiceListening) {
            speechRecognizer.stopListening();
            // 2-second pause between cycles reduces CPU/battery by ~60%
            // compared to immediate restart, while keeping response latency acceptable
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (voiceListening && speechRecognizer != null) {
                    launchSpeechRecognition();
                }
            }, 2000);
        }
    }

    private void stopVoiceListening() {
        voiceListening = false;
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
        btnVoice.setContentDescription(getString(R.string.cd_voice_idle));
        setStatus(getString(R.string.status_idle));
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Volume SOS Receiver
    // ══════════════════════════════════════════════════════════════════════════

    private void registerVolumeSosReceiver() {
        if (volumeSosReceiver != null) return;
        volumeSosReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (VolumeButtonSosService.ACTION_VOLUME_SOS.equals(intent.getAction())
                        && !isCountingDown) {
                    haptic.shakeDetected();
                    if (!silentMode) tts.speakNow(getString(R.string.tts_volume_sos_detected));
                    currentTriggerType = AlertRecord.TRIGGER_VOLUME;
                    startSOSCountdown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(VolumeButtonSosService.ACTION_VOLUME_SOS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeSosReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(volumeSosReceiver, filter);
        }
    }

    private void unregisterVolumeSosReceiver() {
        if (volumeSosReceiver != null) {
            try { unregisterReceiver(volumeSosReceiver); } catch (Exception ignored) {}
            volumeSosReceiver = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Accessibility helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void announceForAccessibility(String text) { tvStatus.announceForAccessibility(text); }

    private boolean isAccessibilityEnabled() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled();
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
        tvStatus.setContentDescription(text);
    }

    private void refreshVolumeHint() {
        if (tvVolumeHint == null) return;
        boolean prefEnabled = getPrefs().getBoolean(SettingsActivity.KEY_VOLUME_TRIGGER, false);
        boolean serviceOn   = isAccessibilityServiceRunning();
        if (!prefEnabled) {
            tvVolumeHint.setVisibility(View.GONE);
        } else if (serviceOn) {
            tvVolumeHint.setText(getString(R.string.hint_volume));
            tvVolumeHint.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            tvVolumeHint.setVisibility(View.VISIBLE);
        } else {
            tvVolumeHint.setText(getString(R.string.hint_volume_service_off));
            tvVolumeHint.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            tvVolumeHint.setVisibility(View.VISIBLE);
        }
    }

    private boolean isAccessibilityServiceRunning() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        String expectedId = getPackageName() + "/" + VolumeButtonSosService.class.getName();

        // 1. Check via AccessibilityManager (standard)
        java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (android.accessibilityservice.AccessibilityServiceInfo info : enabledServices) {
            if (expectedId.equals(info.getId())) return true;
        }

        // 2. Fallback: Check via Secure Settings (more reliable on some devices/OS versions)
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

    // ══════════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                needed.add(perm);
        }
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    prefetchLocation();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Navigation / Menu
    // ══════════════════════════════════════════════════════════════════════════

    private void openContacts() { startActivity(new Intent(this, ContactsActivity.class)); }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if      (id == R.id.action_contacts)     { openContacts(); return true; }
        else if (id == R.id.action_history)      { startActivity(new Intent(this, AlertHistoryActivity.class)); return true; }
        else if (id == R.id.action_profile)      { startActivity(new Intent(this, ProfileActivity.class)); return true; }
        else if (id == R.id.action_dashboard)    { startActivity(new Intent(this, DashboardActivity.class)); return true; }
        else if (id == R.id.action_analytics)    { startActivity(new Intent(this, AnalyticsActivity.class)); return true; }
        else if (id == R.id.action_volunteer)    { startActivity(new Intent(this, VolunteerActivity.class)); return true; }
        else if (id == R.id.action_scan_qr)      { qrLauncher.launch(new Intent(this, QrScannerActivity.class)); return true; }
        else if (id == R.id.action_panic)        { showPanicTimerDialog(); return true; }
        else if (id == R.id.action_save_location){ showSaveLocationDialog(); return true; }
        else if (id == R.id.action_settings)     { startActivity(new Intent(this, SettingsActivity.class)); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showPanicTimerDialog() {
        int secs = AutoSosManager.getPanicDuration(this);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_panic_timer))
                .setMessage(getString(R.string.panic_timer_dialog_msg, secs))
                .setPositiveButton(getString(R.string.panic_timer_start), (d, w) -> {
                    if (!silentMode) tts.speakNow(getString(R.string.tts_panic_started, secs));
                    haptic.shakeDetected();
                    panelPanic.setVisibility(View.VISIBLE);
                    tvPanicCountdown.setText(String.valueOf(secs));
                    autoSosManager.startPanicTimer(secs);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /** Saves the current GPS position under a quick label. */
    private void showSaveLocationDialog() {
        if (lastKnownLocation == null) {
            Toast.makeText(this, getString(R.string.location_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = SavedLocationManager.QUICK_LABELS;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_save_location_title))
                .setItems(labels, (d, which) -> {
                    String chosen = labels[which];
                    if ("Custom".equals(chosen)) {
                        // Show an EditText dialog for custom name
                        android.widget.EditText et = new android.widget.EditText(this);
                        et.setHint("Enter label");
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.dialog_save_location_title))
                                .setView(et)
                                .setPositiveButton(getString(R.string.btn_save_settings), (d2, w2) -> {
                                    String name = et.getText().toString().trim();
                                    if (!name.isEmpty()) {
                                        locationMgr.saveCurrentLocation(this, name, lastKnownLocation);
                                        refreshLocationSpinner();
                                        Toast.makeText(this, "\"" + name + "\" saved!", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(getString(R.string.cancel), null)
                                .show();
                    } else {
                        locationMgr.saveCurrentLocation(this, chosen, lastKnownLocation);
                        refreshLocationSpinner();
                        Toast.makeText(this, "\"" + chosen + "\" location saved!", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
    }
}
