package com.sosapp.disabled;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * VolunteerActivity — self-registration, duty status toggle,
 * and personal incident history for volunteers.
 *
 * Volunteers are stored in responders/{uid} with role=VOLUNTEER.
 * Requires the user to be signed in with email/password via Firebase Auth.
 */
public class VolunteerActivity extends AppCompatActivity {

    private static final int PERM_LOCATION = 201;

    // ── Registration views ────────────────────────────────────────────────────
    private View        panelRegister, panelDashboard;
    private EditText    etVolName, etVolEmail, etVolPassword, etVolPhone, etVolZone;
    private Button      btnRegister;
    private ProgressBar pbRegister;
    private TextView    tvRegisterError;

    // ── Dashboard views ───────────────────────────────────────────────────────
    private TextView    tvVolWelcome, tvVolStats, tvVolHistory, tvVolDistance;
    private SwitchCompat switchDuty;
    private Button      btnUpdateLocation, btnLogout;

    private FirebaseAuth        auth;
    private FirebaseFirestore   db;
    private FusedLocationProviderClient fusedClient;
    private AuthManager         authManager;

    private String currentUid    = null;
    private boolean currentDuty  = false;

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volunteer);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.volunteer_title));
        }

        auth        = FirebaseAuth.getInstance();
        db          = FirebaseFirestore.getInstance();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        authManager = AuthManager.getInstance(this);

        bindViews();

        if (auth.getCurrentUser() != null && !auth.getCurrentUser().isAnonymous()) {
            currentUid = auth.getCurrentUser().getUid();
            showDashboard();
            loadVolunteerData();
        } else {
            showRegistration();
        }
    }

    private void bindViews() {
        panelRegister    = findViewById(R.id.panelVolRegister);
        panelDashboard   = findViewById(R.id.panelVolDashboard);
        etVolName        = findViewById(R.id.etVolName);
        etVolEmail       = findViewById(R.id.etVolEmail);
        etVolPassword    = findViewById(R.id.etVolPassword);
        etVolPhone       = findViewById(R.id.etVolPhone);
        etVolZone        = findViewById(R.id.etVolZone);
        btnRegister      = findViewById(R.id.btnVolRegister);
        pbRegister       = findViewById(R.id.pbVolRegister);
        tvRegisterError  = findViewById(R.id.tvVolRegisterError);
        tvVolWelcome     = findViewById(R.id.tvVolWelcome);
        tvVolStats       = findViewById(R.id.tvVolStats);
        tvVolHistory     = findViewById(R.id.tvVolHistory);
        tvVolDistance    = findViewById(R.id.tvVolDistance);
        switchDuty       = findViewById(R.id.switchVolDuty);
        btnUpdateLocation = findViewById(R.id.btnVolUpdateLocation);
        btnLogout        = findViewById(R.id.btnVolLogout);

        btnRegister.setOnClickListener(v -> doRegister());
        switchDuty.setOnCheckedChangeListener((btn, isChecked) -> updateDutyStatus(isChecked));
        btnUpdateLocation.setOnClickListener(v -> updateLocation());
        btnLogout.setOnClickListener(v -> doLogout());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Registration
    // ══════════════════════════════════════════════════════════════════════════

    private void doRegister() {
        String name     = etVolName.getText().toString().trim();
        String email    = etVolEmail.getText().toString().trim();
        String password = etVolPassword.getText().toString();
        String phone    = etVolPhone.getText().toString().trim();
        String zone     = etVolZone.getText().toString().trim();

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            tvRegisterError.setText(getString(R.string.error_fields_required));
            tvRegisterError.setVisibility(View.VISIBLE);
            return;
        }

        pbRegister.setVisibility(View.VISIBLE);
        btnRegister.setEnabled(false);
        tvRegisterError.setVisibility(View.GONE);

        authManager.registerResponder(email, password, name,
                AuthManager.ROLE_VOLUNTEER, new AuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(String role, String displayName) {
                        currentUid = auth.getCurrentUser() != null
                                ? auth.getCurrentUser().getUid() : null;
                        // Add phone + zone to responder doc
                        if (currentUid != null) {
                            Map<String, Object> extras = new HashMap<>();
                            extras.put("phone",        phone);
                            extras.put("assignedZone", zone);
                            extras.put("available",    false);
                            extras.put("activeAlerts", 0);
                            extras.put("latitude",     0.0);
                            extras.put("longitude",    0.0);
                            db.collection(AuthManager.COL_RESPONDERS)
                                    .document(currentUid)
                                    .update(extras);
                        }
                        runOnUiThread(() -> {
                            pbRegister.setVisibility(View.GONE);
                            btnRegister.setEnabled(true);
                            showDashboard();
                            loadVolunteerData();
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        runOnUiThread(() -> {
                            pbRegister.setVisibility(View.GONE);
                            btnRegister.setEnabled(true);
                            tvRegisterError.setText(errorMessage);
                            tvRegisterError.setVisibility(View.VISIBLE);
                        });
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard
    // ══════════════════════════════════════════════════════════════════════════

    private void loadVolunteerData() {
        if (currentUid == null) return;

        db.collection(AuthManager.COL_RESPONDERS).document(currentUid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    String name = doc.getString("displayName");
                    Boolean avail = doc.getBoolean("available");
                    currentDuty = avail != null && avail;

                    tvVolWelcome.setText(getString(R.string.volunteer_welcome,
                            name != null ? name : "Volunteer"));
                    switchDuty.setChecked(currentDuty);

                    // Load incident stats
                    loadStats();
                });
    }

    private void loadStats() {
        if (currentUid == null) return;

        db.collection(FirebaseManager.COL_ALERTS)
                .whereEqualTo("assignedVolunteerId", currentUid)
                .orderBy(FirebaseManager.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .addOnSuccessListener(query -> {
                    int total = query.size();
                    int resolved = 0;
                    StringBuilder history = new StringBuilder();

                    for (DocumentSnapshot doc : query.getDocuments()) {
                        String status = doc.getString(FirebaseManager.FIELD_STATUS);
                        if (AlertRecord.STATUS_RESOLVED.equals(status)) resolved++;
                        Long ts = doc.getLong(FirebaseManager.FIELD_TIMESTAMP);
                        String trigger = doc.getString(FirebaseManager.FIELD_TRIGGER);
                        if (ts != null) {
                            history.append(SDF.format(new Date(ts)))
                                    .append(" — ").append(status)
                                    .append(" (").append(trigger).append(")\n");
                        }
                    }

                    tvVolStats.setText(getString(R.string.volunteer_stats, total, resolved));
                    tvVolHistory.setText(history.length() > 0
                            ? history.toString().trim()
                            : getString(R.string.volunteer_no_history));
                });
    }

    private void updateDutyStatus(boolean onDuty) {
        if (currentUid == null) return;
        currentDuty = onDuty;

        Map<String, Object> update = new HashMap<>();
        update.put("available", onDuty);
        update.put("dutyUpdatedAt", System.currentTimeMillis());

        db.collection(AuthManager.COL_RESPONDERS).document(currentUid)
                .update(update)
                .addOnSuccessListener(v -> Toast.makeText(this,
                        onDuty ? getString(R.string.volunteer_on_duty)
                               : getString(R.string.volunteer_off_duty),
                        Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.dashboard_update_failed),
                        Toast.LENGTH_SHORT).show());

        // If going on-duty, push current location immediately
        if (onDuty) updateLocation();
    }

    private void updateLocation() {
        if (currentUid == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERM_LOCATION);
            return;
        }

        fusedClient.getLastLocation().addOnSuccessListener(loc -> {
            if (loc == null) {
                Toast.makeText(this, getString(R.string.location_unavailable),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Map<String, Object> update = new HashMap<>();
            update.put("latitude",         loc.getLatitude());
            update.put("longitude",        loc.getLongitude());
            update.put("locationUpdatedAt", System.currentTimeMillis());
            db.collection(AuthManager.COL_RESPONDERS).document(currentUid)
                    .update(update)
                    .addOnSuccessListener(v -> tvVolDistance.setText(
                            getString(R.string.volunteer_location_updated)));
        });
    }

    private void doLogout() {
        // Go off-duty before logging out
        if (currentDuty) updateDutyStatus(false);
        authManager.logout();
        currentUid  = null;
        currentDuty = false;
        showRegistration();
    }

    private void showRegistration() {
        panelRegister.setVisibility(View.VISIBLE);
        panelDashboard.setVisibility(View.GONE);
    }

    private void showDashboard() {
        panelRegister.setVisibility(View.GONE);
        panelDashboard.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }
}
