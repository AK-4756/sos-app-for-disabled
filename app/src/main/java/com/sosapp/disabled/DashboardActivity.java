package com.sosapp.disabled;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashboardActivity — real-time caregiver/responder alert management screen.
 *
 * Flow:
 *   1. If not logged in → show login panel.
 *   2. On login → attach Firestore snapshot listener for last 48h alerts.
 *   3. Each alert card shows status, location, actions based on role.
 *   4. All status updates are written directly to Firestore.
 */
public class DashboardActivity extends AppCompatActivity
        implements DashboardAdapter.ActionListener {

    private static final int  MAX_ALERTS_SHOWN = 50;
    private static final long WINDOW_MS        = 48L * 3600 * 1000;

    // ── Login panel ──────────────────────────────────────────────────────────
    private View          loginPanel;
    private EditText      etEmail, etPassword;
    private Button        btnLogin;
    private ProgressBar   pbLogin;
    private TextView      tvLoginError;

    // ── Dashboard panel ──────────────────────────────────────────────────────
    private View          dashPanel;
    private TextView      tvWelcome;
    private TextView      tvActiveCount;
    private RecyclerView  rvAlerts;
    private Button        btnLogout, btnFilterAll, btnFilterActive;
    private ProgressBar   pbLoading;

    private DashboardAdapter adapter;
    private AuthManager      authManager;
    private FirebaseFirestore db;
    private ListenerRegistration snapshotListener;

    private boolean showActiveOnly = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.dashboard_title));
        }

        authManager = AuthManager.getInstance(this);

        try { db = FirebaseFirestore.getInstance(); }
        catch (Exception e) {
            Toast.makeText(this, getString(R.string.dashboard_firebase_unavailable),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        bindViews();
        setupRecyclerView();

        if (authManager.isLoggedIn()) {
            showDashboard();
        } else {
            showLogin();
        }
    }

    private void bindViews() {
        loginPanel    = findViewById(R.id.panelLogin);
        dashPanel     = findViewById(R.id.panelDashboard);
        etEmail       = findViewById(R.id.etDashEmail);
        etPassword    = findViewById(R.id.etDashPassword);
        btnLogin      = findViewById(R.id.btnDashLogin);
        pbLogin       = findViewById(R.id.pbLogin);
        tvLoginError  = findViewById(R.id.tvLoginError);
        tvWelcome     = findViewById(R.id.tvDashWelcome);
        tvActiveCount = findViewById(R.id.tvDashActiveCount);
        rvAlerts      = findViewById(R.id.rvDashAlerts);
        btnLogout     = findViewById(R.id.btnDashLogout);
        btnFilterAll  = findViewById(R.id.btnFilterAll);
        btnFilterActive = findViewById(R.id.btnFilterActive);
        pbLoading     = findViewById(R.id.pbDashLoading);

        btnLogin.setOnClickListener(v -> doLogin());
        btnLogout.setOnClickListener(v -> doLogout());
        btnFilterAll.setOnClickListener(v -> { showActiveOnly = false; filterChanged(); });
        btnFilterActive.setOnClickListener(v -> { showActiveOnly = true; filterChanged(); });
    }

    private void setupRecyclerView() {
        adapter = new DashboardAdapter(this);
        rvAlerts.setLayoutManager(new LinearLayoutManager(this));
        rvAlerts.setAdapter(adapter);
        adapter.setCanAction(authManager.canAcknowledge());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Login / Logout
    // ══════════════════════════════════════════════════════════════════════════

    private void showLogin() {
        loginPanel.setVisibility(View.VISIBLE);
        dashPanel.setVisibility(View.GONE);
    }

    private void showDashboard() {
        loginPanel.setVisibility(View.GONE);
        dashPanel.setVisibility(View.VISIBLE);
        tvWelcome.setText(getString(R.string.dashboard_welcome,
                authManager.getCurrentDisplayName(),
                authManager.getCurrentRole()));
        adapter.setCanAction(authManager.canAcknowledge());
        startListening();
    }

    private void doLogin() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            tvLoginError.setText(getString(R.string.error_fields_required));
            tvLoginError.setVisibility(View.VISIBLE);
            return;
        }
        pbLogin.setVisibility(View.VISIBLE);
        btnLogin.setEnabled(false);
        tvLoginError.setVisibility(View.GONE);

        authManager.loginResponder(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(String role, String displayName) {
                runOnUiThread(() -> {
                    pbLogin.setVisibility(View.GONE);
                    btnLogin.setEnabled(true);
                    showDashboard();
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                runOnUiThread(() -> {
                    pbLogin.setVisibility(View.GONE);
                    btnLogin.setEnabled(true);
                    tvLoginError.setText(errorMessage);
                    tvLoginError.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void doLogout() {
        stopListening();
        authManager.logout();
        adapter.setItems(new ArrayList<>());
        showLogin();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firestore real-time listener
    // ══════════════════════════════════════════════════════════════════════════

    private void startListening() {
        pbLoading.setVisibility(View.VISIBLE);
        long cutoff = System.currentTimeMillis() - WINDOW_MS;

        Query query = db.collection(FirebaseManager.COL_ALERTS)
                .whereGreaterThan(FirebaseManager.FIELD_TIMESTAMP, cutoff)
                .orderBy(FirebaseManager.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                .limit(MAX_ALERTS_SHOWN);

        snapshotListener = query.addSnapshotListener((snapshots, error) -> {
            pbLoading.setVisibility(View.GONE);
            if (error != null || snapshots == null) return;

            for (DocumentChange change : snapshots.getDocumentChanges()) {
                DashboardAlertModel model = documentToModel(
                        change.getDocument().getId(),
                        change.getDocument().getData());

                switch (change.getType()) {
                    case ADDED:
                    case MODIFIED:
                        fetchUserName(model, () -> runOnUiThread(() -> adapter.updateItem(model)));
                        break;
                    case REMOVED:
                        runOnUiThread(() -> adapter.removeItem(change.getDocument().getId()));
                        break;
                }
            }
            updateActiveCount();
        });
    }

    private void stopListening() {
        if (snapshotListener != null) {
            snapshotListener.remove();
            snapshotListener = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Action handlers (DashboardAdapter.ActionListener)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onAcknowledge(DashboardAlertModel alert) {
        updateStatus(alert, AlertRecord.STATUS_ACKNOWLEDGED,
                getString(R.string.dashboard_ack_toast));
    }

    @Override
    public void onAssignSelf(DashboardAlertModel alert) {
        Map<String, Object> update = new HashMap<>();
        update.put(FirebaseManager.FIELD_STATUS, AlertRecord.STATUS_RESPONDER_ASSIGNED);
        update.put("responderName",  authManager.getCurrentDisplayName());
        update.put("responderEmail", authManager.getCurrentEmail());
        update.put("assignedAt",     System.currentTimeMillis());
        writeUpdate(alert.getDocumentId(), update,
                getString(R.string.dashboard_assign_toast));
    }

    @Override
    public void onMarkOnTheWay(DashboardAlertModel alert) {
        updateStatus(alert, AlertRecord.STATUS_ON_THE_WAY,
                getString(R.string.dashboard_onway_toast));
    }

    @Override
    public void onResolve(DashboardAlertModel alert) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dashboard_resolve_confirm_title))
                .setMessage(getString(R.string.dashboard_resolve_confirm_msg))
                .setPositiveButton(getString(R.string.dashboard_resolve_btn), (d, w) ->
                        updateStatus(alert, AlertRecord.STATUS_RESOLVED,
                                getString(R.string.dashboard_resolved_toast)))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onMarkFalseAlert(DashboardAlertModel alert) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dashboard_false_confirm_title))
                .setMessage(getString(R.string.dashboard_false_confirm_msg))
                .setPositiveButton(getString(R.string.dashboard_false_btn), (d, w) ->
                        updateStatus(alert, AlertRecord.STATUS_FALSE_ALERT,
                                getString(R.string.dashboard_false_toast)))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onOpenMap(DashboardAlertModel alert) {
        String url = alert.getMapUrl();
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.history_no_map), Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase write helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void updateStatus(DashboardAlertModel alert, String newStatus, String toastMsg) {
        Map<String, Object> update = new HashMap<>();
        update.put(FirebaseManager.FIELD_STATUS, newStatus);
        update.put("lastUpdated",    System.currentTimeMillis());
        update.put("updatedBy",      authManager.getCurrentDisplayName());
        if (AlertRecord.STATUS_ACKNOWLEDGED.equals(newStatus)) {
            update.put("acknowledgedBy", authManager.getCurrentDisplayName());
            update.put("acknowledgedAt", System.currentTimeMillis());
        }
        writeUpdate(alert.getDocumentId(), update, toastMsg);
    }

    private void writeUpdate(String docId, Map<String, Object> data, String toastMsg) {
        db.collection(FirebaseManager.COL_ALERTS).document(docId)
                .update(data)
                .addOnSuccessListener(v -> runOnUiThread(() ->
                        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()))
                .addOnFailureListener(e -> runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.dashboard_update_failed),
                                Toast.LENGTH_SHORT).show()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private DashboardAlertModel documentToModel(String docId, Map<String, Object> data) {
        DashboardAlertModel m = new DashboardAlertModel();
        m.setDocumentId(docId);
        m.setUserId(str(data, FirebaseManager.FIELD_USER_ID));
        m.setTriggerType(str(data, FirebaseManager.FIELD_TRIGGER));
        m.setStatus(str(data, FirebaseManager.FIELD_STATUS));
        m.setLocationLabel(str(data, FirebaseManager.FIELD_LOC_LABEL));
        m.setSilentMode(bool(data, FirebaseManager.FIELD_SILENT));
        m.setResponderName(str(data, "responderName"));
        m.setAcknowledgedBy(str(data, "acknowledgedBy"));

        Object lat = data.get(FirebaseManager.FIELD_LAT);
        Object lng = data.get(FirebaseManager.FIELD_LNG);
        if (lat instanceof Number) m.setLatitude(((Number) lat).doubleValue());
        if (lng instanceof Number) m.setLongitude(((Number) lng).doubleValue());

        Object ts  = data.get(FirebaseManager.FIELD_TIMESTAMP);
        if (ts instanceof Number) m.setTimestamp(((Number) ts).longValue());

        Object bat = data.get(FirebaseManager.FIELD_BATTERY);
        if (bat instanceof Number) m.setBattery(((Number) bat).intValue());

        Object acc = data.get(FirebaseManager.FIELD_LOC_ACCURACY);
        if (acc instanceof Number) m.setLocationAccuracyM(((Number) acc).floatValue());

        Object cnt = data.get(FirebaseManager.FIELD_CONTACTS_COUNT);
        if (cnt instanceof Number) m.setContactsNotified(((Number) cnt).intValue());

        return m;
    }

    private void fetchUserName(DashboardAlertModel model, Runnable onDone) {
        if (model.getUserId() == null || model.getUserId().isEmpty()) {
            model.setUserName("Unknown User");
            onDone.run();
            return;
        }
        db.collection(FirebaseManager.COL_USERS).document(model.getUserId())
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String name = doc.getString("fullName");
                        model.setUserName(name != null ? name : model.getUserId().substring(0, 8));
                    }
                    onDone.run();
                })
                .addOnFailureListener(e -> onDone.run());
    }

    private void updateActiveCount() {
        // Recount from adapter items (already loaded)
        // This is called after snapshot updates
        runOnUiThread(() -> {
            // count done via adapter — simplified
            tvActiveCount.setText(getString(R.string.dashboard_showing_alerts));
        });
    }

    private void filterChanged() {
        // In a full implementation, re-query Firestore with status filter
        // For now, toggle button visuals only — snapshot handles display
        btnFilterAll.setAlpha(showActiveOnly ? 0.5f : 1.0f);
        btnFilterActive.setAlpha(showActiveOnly ? 1.0f : 0.5f);
    }

    private String  str(Map<String, Object> d, String key) {
        Object v = d.get(key); return v instanceof String ? (String) v : "";
    }
    private boolean bool(Map<String, Object> d, String key) {
        Object v = d.get(key); return v instanceof Boolean && (Boolean) v;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }
}
