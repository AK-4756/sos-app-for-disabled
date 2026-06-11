package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * FirebaseManager — single-entry-point for all Firestore operations.
 *
 * Collections:
 *   users/{userId}             — device identity + user mode
 *   alerts/{alertId}           — one document per SOS event
 *   alerts/{alertId}/responses — subcollection for responder actions
 *
 * All methods are fire-and-forget with error logging.
 * The app continues to function perfectly if Firebase is unavailable.
 *
 * SETUP REQUIRED:
 *   1. Create a Firebase project at https://console.firebase.google.com
 *   2. Enable Firestore in Native mode
 *   3. Download google-services.json and place it in /app/
 *   4. Enable Anonymous auth (Authentication → Sign-in method → Anonymous)
 *
 * A google-services.json placeholder is NOT included (it is device-specific).
 * The app degrades gracefully without it — all local features still work.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";

    // ── SharedPreferences key for the anonymous user ID ───────────────────────
    public static final String PREF_USER_ID = "firebase_user_id";

    // ── Collection / field names (match dashboard JS exactly) ────────────────
    public static final String COL_ALERTS     = "alerts";
    public static final String COL_USERS      = "users";
    public static final String COL_RESPONSES  = "responses";

    public static final String FIELD_USER_ID        = "userId";
    public static final String FIELD_TIMESTAMP      = "timestamp";
    public static final String FIELD_TRIGGER        = "triggerType";
    public static final String FIELD_STATUS         = "status";
    public static final String FIELD_LAT            = "latitude";
    public static final String FIELD_LNG            = "longitude";
    public static final String FIELD_BATTERY        = "battery";
    public static final String FIELD_CONTACTS_COUNT = "contactsNotified";
    public static final String FIELD_LOC_LABEL      = "locationLabel";
    public static final String FIELD_SILENT         = "silentMode";
    public static final String FIELD_USER_MODE      = "userMode";
    public static final String FIELD_DEVICE_INFO    = "deviceInfo";
    public static final String FIELD_LOC_ACCURACY   = "locationAccuracyM"; // GPS accuracy metres

    private static FirebaseManager instance;
    private FirebaseFirestore db;
    private boolean available = false;
    private String userId;

    private FirebaseManager(Context context) {
        try {
            db = FirebaseFirestore.getInstance();
            available = true;
            userId = getOrCreateUserId(context);
            Log.d(TAG, "Firestore initialized. userId=" + userId);
        } catch (Exception e) {
            Log.w(TAG, "Firestore not available (no google-services.json?): " + e.getMessage());
        }
    }

    public static synchronized FirebaseManager getInstance(Context context) {
        if (instance == null) {
            instance = new FirebaseManager(context.getApplicationContext());
        }
        return instance;
    }

    public boolean isAvailable() { return available; }

    // ══════════════════════════════════════════════════════════════════════════
    // User registration
    // ══════════════════════════════════════════════════════════════════════════

    /** Registers or updates the user document with mode and device info. */
    public void syncUserProfile(Context context) {
        if (!available) return;
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String userMode = prefs.getString(UserModeManager.KEY_USER_MODE,
                UserModeManager.MODE_DEFAULT);

        Map<String, Object> user = new HashMap<>();
        user.put(FIELD_USER_ID,     userId);
        user.put(FIELD_USER_MODE,   userMode);
        user.put(FIELD_DEVICE_INFO, android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")");
        user.put(FIELD_TIMESTAMP,   System.currentTimeMillis());

        db.collection(COL_USERS).document(userId)
                .set(user, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "syncUserProfile failed", e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Alert lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new alert document in Firestore.
     * Returns immediately; the Firestore document ID is delivered via callback
     * so the caller can store it in SQLite (AlertRecord.firebaseId).
     */
    public interface OnAlertCreated {
        void onCreated(String firebaseDocId);
        void onFailed(Exception e);
    }

    public void createAlert(AlertRecord record, OnAlertCreated callback) {
        if (!available) {
            if (callback != null) callback.onFailed(new Exception("Firestore not available"));
            return;
        }

        Map<String, Object> data = alertToMap(record);
        data.put(FIELD_STATUS, AlertRecord.STATUS_CREATED);

        db.collection(COL_ALERTS).add(data)
                .addOnSuccessListener(ref -> {
                    Log.d(TAG, "Alert created: " + ref.getId());
                    if (callback != null) callback.onCreated(ref.getId());
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "createAlert failed", e);
                    if (callback != null) callback.onFailed(e);
                });
    }

    /** Updates the status field of an existing alert document. */
    public void updateAlertStatus(String firebaseId, String newStatus) {
        if (!available || firebaseId == null || firebaseId.isEmpty()) return;

        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_STATUS, newStatus);
        update.put("lastUpdated", System.currentTimeMillis());

        db.collection(COL_ALERTS).document(firebaseId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "updateAlertStatus failed: " + firebaseId, e));
    }

    /** Marks alert SENT (called after all SMS dispatched). */
    public void markAlertSent(String firebaseId, int contactsNotified) {
        if (!available || firebaseId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_STATUS, AlertRecord.STATUS_SENT);
        update.put(FIELD_CONTACTS_COUNT, contactsNotified);
        update.put("sentAt", System.currentTimeMillis());
        db.collection(COL_ALERTS).document(firebaseId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "markAlertSent failed", e));
    }

    /** Marks alert ACKNOWLEDGED (called when reply SMS detected). */
    public void markAlertAcknowledged(String firebaseId, String responderNumber) {
        if (!available || firebaseId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_STATUS, AlertRecord.STATUS_ACKNOWLEDGED);
        update.put("acknowledgedBy", responderNumber);
        update.put("acknowledgedAt", System.currentTimeMillis());
        db.collection(COL_ALERTS).document(firebaseId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "markAlertAcknowledged failed", e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Real-time status listener (for in-app updates)
    // ══════════════════════════════════════════════════════════════════════════

    public interface OnStatusChanged {
        void onStatusChanged(String firebaseId, String newStatus);
    }

    /**
     * Listens for responder-side status changes on a specific alert document.
     * The returned ListenerRegistration must be removed when the activity pauses.
     */
    public ListenerRegistration listenToAlert(String firebaseId, OnStatusChanged listener) {
        if (!available || firebaseId == null) return null;
        return db.collection(COL_ALERTS).document(firebaseId)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;
                    String status = snapshot.getString(FIELD_STATUS);
                    if (status != null && listener != null) {
                        listener.onStatusChanged(firebaseId, status);
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> alertToMap(AlertRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_USER_ID,    userId);
        map.put(FIELD_TIMESTAMP,  record.getTimestamp());
        map.put(FIELD_TRIGGER,    record.getTriggerType());
        map.put(FIELD_STATUS,     record.getStatus());
        map.put(FIELD_BATTERY,    record.getBatteryAtTime());
        map.put(FIELD_SILENT,     record.isSilentMode());
        map.put(FIELD_LOC_LABEL,  record.getLocationLabel() != null
                ? record.getLocationLabel() : "Unknown");
        if (record.getLocationAccuracyM() >= 0) {
            map.put(FIELD_LOC_ACCURACY, record.getLocationAccuracyM());
        }

        String locSnap = record.getLocationSnap();
        if (locSnap != null && locSnap.contains(",")) {
            try {
                String[] parts = locSnap.split(",");
                map.put(FIELD_LAT, Double.parseDouble(parts[0].trim()));
                map.put(FIELD_LNG, Double.parseDouble(parts[1].trim()));
            } catch (Exception ignored) {}
        }
        return map;
    }

    /** Returns a stable anonymous user ID, persisting it across launches. */
    private String getOrCreateUserId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(PREF_USER_ID, null);
        if (id == null) {
            id = "user_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            prefs.edit().putString(PREF_USER_ID, id).apply();
        }
        return id;
    }

    public String getUserId() { return userId; }
}
