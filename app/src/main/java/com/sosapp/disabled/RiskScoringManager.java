package com.sosapp.disabled;

import android.content.Context;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RiskScoringManager — computes a 0–100 risk score for every SOS alert.
 *
 * Score is written to Firestore as riskScore + riskLevel so the dashboard
 * can colour-code and prioritise alerts appropriately.
 *
 * Factors and maximum contribution:
 *   Night-time (22:00–06:00)             +20
 *   Critical battery (<15%)              +15
 *   Fall detection triggered             +20  (scaled by confidence 0–1)
 *   Repeated triggers (≥3 in 24h)        +15
 *   Medical conditions on file           +10
 *   No volunteer/responder assigned      +10
 *   Campus isolation zone                +5
 *   Silent SOS mode                      +5
 *   ─────────────────────────────────────────
 *   Maximum                              100
 *
 * Risk levels:
 *   0–24   → LOW
 *   25–49  → MEDIUM
 *   50–74  → HIGH
 *   75–100 → CRITICAL
 */
public class RiskScoringManager {

    private static final String TAG = "RiskScoringManager";

    public static final String LEVEL_LOW      = "LOW";
    public static final String LEVEL_MEDIUM   = "MEDIUM";
    public static final String LEVEL_HIGH     = "HIGH";
    public static final String LEVEL_CRITICAL = "CRITICAL";

    public static final String FIELD_RISK_SCORE = "riskScore";
    public static final String FIELD_RISK_LEVEL = "riskLevel";

    private static RiskScoringManager instance;
    private final Context           context;
    private final FirebaseFirestore db;
    private final DatabaseHelper    dbHelper;

    private RiskScoringManager(Context context) {
        this.context  = context.getApplicationContext();
        this.dbHelper = new DatabaseHelper(context);
        FirebaseFirestore tmp = null;
        try { tmp = FirebaseFirestore.getInstance(); } catch (Exception ignored) {}
        this.db = tmp;
    }

    public static synchronized RiskScoringManager getInstance(Context context) {
        if (instance == null) instance = new RiskScoringManager(context);
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Computes risk score for an alert and writes it to Firestore.
     * All parameters may be default/zero if not available.
     *
     * @param firebaseAlertId  Firestore document ID of the alert
     * @param record           local AlertRecord (may be partially filled)
     * @param fallConfidence   0.0 if not a fall, 0.0–1.0 if fall detected
     * @param isAssigned       true if a volunteer/responder is already assigned
     * @param campusZoneName   name of detected campus zone, or null
     */
    public int computeAndStore(String firebaseAlertId, AlertRecord record,
                                float fallConfidence, boolean isAssigned,
                                String campusZoneName) {
        int score = 0;

        // ── Night-time ───────────────────────────────────────────────────────
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 22 || hour < 6) {
            score += 20;
            Log.d(TAG, "Night-time +20");
        }

        // ── Critical battery ─────────────────────────────────────────────────
        int battery = record.getBatteryAtTime();
        if (battery >= 0 && battery < 15) {
            score += 15;
            Log.d(TAG, "Critical battery +15");
        } else if (battery >= 0 && battery < 30) {
            score += 7;
        }

        // ── Fall detection confidence ────────────────────────────────────────
        if (AlertRecord.TRIGGER_FALL.equals(record.getTriggerType())) {
            int fallScore = Math.round(fallConfidence * 20f);
            score += Math.min(fallScore, 20);
            Log.d(TAG, "Fall confidence +" + fallScore);
        }

        // ── Repeated triggers (last 24h) ─────────────────────────────────────
        int recentCount = countRecentAlerts(24);
        if (recentCount >= 5)      score += 15;
        else if (recentCount >= 3) score += 10;
        else if (recentCount >= 2) score += 5;
        if (recentCount >= 2) Log.d(TAG, "Repeated triggers (" + recentCount + ") +pts");

        // ── Medical conditions on file ───────────────────────────────────────
        UserProfile profile = ProfileManager.getInstance(context).getProfile();
        if (!profile.getMedicalConditions().isEmpty()
                || !profile.getAllergies().isEmpty()) {
            score += 10;
            Log.d(TAG, "Medical conditions on file +10");
        }

        // ── No responder assigned ────────────────────────────────────────────
        if (!isAssigned) {
            score += 10;
        }

        // ── Campus isolation (night + isolated zone) ─────────────────────────
        boolean isolatedZone = campusZoneName != null &&
                (campusZoneName.toLowerCase().contains("hostel")
                        || campusZoneName.toLowerCase().contains("parking")
                        || campusZoneName.toLowerCase().contains("gate"));
        if (isolatedZone) {
            score += 5;
            Log.d(TAG, "Isolated campus zone +5");
        }

        // ── Silent SOS ───────────────────────────────────────────────────────
        if (record.isSilentMode()) {
            score += 5;
            Log.d(TAG, "Silent SOS +5");
        }

        // Cap at 100
        score = Math.min(score, 100);
        String level = scoreToLevel(score);
        Log.d(TAG, "Final risk score: " + score + " (" + level + ")");

        // Store in Firestore
        storeScore(firebaseAlertId, score, level);

        return score;
    }

    public static String scoreToLevel(int score) {
        if (score >= 75) return LEVEL_CRITICAL;
        if (score >= 50) return LEVEL_HIGH;
        if (score >= 25) return LEVEL_MEDIUM;
        return LEVEL_LOW;
    }

    /** Returns ARGB colour for a risk level (for UI badges). */
    public static int levelToColor(String level) {
        switch (level) {
            case LEVEL_CRITICAL: return 0xFFB71C1C; // deep red
            case LEVEL_HIGH:     return 0xFFE53935; // red
            case LEVEL_MEDIUM:   return 0xFFFF6F00; // amber
            default:             return 0xFF2E7D32; // green
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private int countRecentAlerts(int windowHours) {
        long cutoff = System.currentTimeMillis() - (windowHours * 3600_000L);
        List<AlertRecord> history = dbHelper.getAllAlertHistory();
        int count = 0;
        for (AlertRecord r : history) {
            if (r.getTimestamp() >= cutoff
                    && !AlertRecord.STATUS_FALSE_ALERT.equals(r.getStatus())
                    && !AlertRecord.STATUS_CANCELLED.equals(r.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private void storeScore(String firebaseAlertId, int score, String level) {
        if (db == null || firebaseAlertId == null || firebaseAlertId.isEmpty()) return;
        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_RISK_SCORE, score);
        update.put(FIELD_RISK_LEVEL, level);
        db.collection(FirebaseManager.COL_ALERTS).document(firebaseAlertId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "Risk score store failed", e));
    }
}
