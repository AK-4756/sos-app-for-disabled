package com.sosapp.disabled;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CampusAnalyticsManager — computes analytics from the Firestore alerts
 * collection and delivers them via callback for the dashboard to render.
 *
 * All computation is client-side (no Cloud Functions needed) using a single
 * Firestore query for the last N days and local aggregation. Suitable for
 * hackathon/IDS demo where cost and scale are not primary concerns.
 *
 * Metrics produced:
 *   totalAlerts        — total count in window
 *   resolvedAlerts     — STATUS_RESOLVED count
 *   falseAlerts        — STATUS_FALSE_ALERT count
 *   avgResponseTimeSec — mean (acknowledgedAt - timestamp) for acked alerts
 *   activeVolunteers   — count of volunteers with available=true in Firestore
 *   triggerBreakdown   — Map<triggerType, count>
 *   locationHotspots   — Map<locationLabel, count> (top 5)
 *   hourlyDistribution — Map<hour(0-23), count> for heatmap
 */
public class CampusAnalyticsManager {

    private static final String TAG          = "CampusAnalytics";
    private static final int    WINDOW_DAYS  = 30;
    private static final int    MAX_HOTSPOTS = 5;

    public static class AnalyticsResult {
        public int                  totalAlerts        = 0;
        public int                  resolvedAlerts     = 0;
        public int                  falseAlerts        = 0;
        public long                 avgResponseTimeSec = 0;
        public int                  activeVolunteers   = 0;
        public Map<String, Integer> triggerBreakdown   = new LinkedHashMap<>();
        public Map<String, Integer> locationHotspots   = new LinkedHashMap<>();
        public Map<Integer, Integer> hourlyDistribution = new LinkedHashMap<>();
    }

    public interface AnalyticsCallback {
        void onResult(AnalyticsResult result);
        void onError(Exception e);
    }

    private final FirebaseFirestore db;

    public CampusAnalyticsManager() {
        FirebaseFirestore tmp = null;
        try { tmp = FirebaseFirestore.getInstance(); } catch (Exception ignored) {}
        this.db = tmp;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Main fetch
    // ══════════════════════════════════════════════════════════════════════════

    public void fetchAnalytics(AnalyticsCallback callback) {
        if (db == null) {
            if (callback != null) callback.onError(new Exception("Firestore not available"));
            return;
        }

        long cutoff = System.currentTimeMillis() - (long) WINDOW_DAYS * 86_400_000L;

        db.collection(FirebaseManager.COL_ALERTS)
                .whereGreaterThan(FirebaseManager.FIELD_TIMESTAMP, cutoff)
                .orderBy(FirebaseManager.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(query -> {
                    AnalyticsResult result = new AnalyticsResult();
                    long totalResponseTime = 0;
                    int  ackedCount        = 0;

                    Map<String, Integer> triggerMap   = new HashMap<>();
                    Map<String, Integer> locationMap  = new HashMap<>();
                    Map<Integer, Integer> hourlyMap   = new HashMap<>();

                    for (QueryDocumentSnapshot doc : query) {
                        result.totalAlerts++;

                        String status = doc.getString(FirebaseManager.FIELD_STATUS);
                        if (AlertRecord.STATUS_RESOLVED.equals(status))   result.resolvedAlerts++;
                        if (AlertRecord.STATUS_FALSE_ALERT.equals(status)) result.falseAlerts++;

                        // Response time
                        Object ackAt = doc.get("acknowledgedAt");
                        Object ts    = doc.get(FirebaseManager.FIELD_TIMESTAMP);
                        if (ackAt instanceof Number && ts instanceof Number) {
                            long responseMs = ((Number) ackAt).longValue()
                                    - ((Number) ts).longValue();
                            if (responseMs > 0 && responseMs < 3_600_000L) {
                                totalResponseTime += responseMs / 1000L;
                                ackedCount++;
                            }
                        }

                        // Trigger breakdown
                        String trigger = doc.getString(FirebaseManager.FIELD_TRIGGER);
                        if (trigger != null && !trigger.isEmpty()) {
                            triggerMap.put(trigger, triggerMap.getOrDefault(trigger, 0) + 1);
                        }

                        // Location hotspot
                        String locLabel = doc.getString(FirebaseManager.FIELD_LOC_LABEL);
                        if (locLabel != null && !locLabel.isEmpty()
                                && !locLabel.equals("Unknown")
                                && !locLabel.equals(SavedLocationManager.LABEL_LIVE)) {
                            locationMap.put(locLabel,
                                    locationMap.getOrDefault(locLabel, 0) + 1);
                        }

                        // Hourly distribution
                        if (ts instanceof Number) {
                            long time = ((Number) ts).longValue();
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.setTimeInMillis(time);
                            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                            hourlyMap.put(hour, hourlyMap.getOrDefault(hour, 0) + 1);
                        }
                    }

                    // Average response time
                    if (ackedCount > 0) result.avgResponseTimeSec = totalResponseTime / ackedCount;

                    // Top trigger types
                    result.triggerBreakdown = sortByValueDesc(triggerMap);

                    // Top locations (hotspots)
                    Map<String, Integer> sortedLoc = sortByValueDesc(locationMap);
                    int count = 0;
                    for (Map.Entry<String, Integer> e : sortedLoc.entrySet()) {
                        if (count++ >= MAX_HOTSPOTS) break;
                        result.locationHotspots.put(e.getKey(), e.getValue());
                    }

                    // Fill all 24 hours (0 if no data)
                    for (int h = 0; h < 24; h++) {
                        result.hourlyDistribution.put(h, hourlyMap.getOrDefault(h, 0));
                    }

                    // Fetch active volunteers separately
                    fetchActiveVolunteers(result, callback);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Analytics fetch failed", e);
                    if (callback != null) callback.onError(e);
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Active volunteer count
    // ══════════════════════════════════════════════════════════════════════════

    private void fetchActiveVolunteers(AnalyticsResult result, AnalyticsCallback callback) {
        db.collection(AuthManager.COL_RESPONDERS)
                .whereEqualTo(VolunteerAssignmentManager.F_AVAILABLE, true)
                .get()
                .addOnSuccessListener(q -> {
                    result.activeVolunteers = q.size();
                    if (callback != null) callback.onResult(result);
                })
                .addOnFailureListener(e -> {
                    // Don't fail the whole analytics for this
                    if (callback != null) callback.onResult(result);
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Integer> sortByValueDesc(Map<String, Integer> input) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(input.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }
}
