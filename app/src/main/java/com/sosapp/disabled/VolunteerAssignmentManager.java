package com.sosapp.disabled;

import android.content.Context;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VolunteerAssignmentManager — automatically selects and assigns the best
 * available volunteer when a new SOS alert is created.
 *
 * Firestore schema expected in volunteers/{uid}:
 *   available    : boolean  — true = on duty
 *   latitude     : double   — last known location
 *   longitude    : double   — last known location
 *   assignedZone : String   — campus zone type they cover (optional)
 *   displayName  : String
 *   phone        : String
 *   activeAlerts : int      — current assignment count (0 = free)
 *
 * Selection algorithm (ranked, lower score = better):
 *   1. Must be available == true AND activeAlerts == 0
 *   2. Rank by Haversine distance from alert location
 *   3. Prefer volunteers whose assignedZone matches the alert's campus zone
 *
 * On assignment:
 *   - Updates volunteers/{uid}: activeAlerts += 1, lastAssignedAlertId
 *   - Updates alerts/{alertId}: assignedVolunteerId, assignedVolunteerName,
 *                                assignedVolunteerPhone, assignedTime,
 *                                status → RESPONDER_ASSIGNED
 *   - Dashboard Firestore listener picks up the change in real time.
 */
public class VolunteerAssignmentManager {

    private static final String TAG = "VolunteerAssignment";

    // Firestore field names for volunteers collection
    public static final String F_AVAILABLE      = "available";
    public static final String F_LATITUDE       = "latitude";
    public static final String F_LONGITUDE      = "longitude";
    public static final String F_ASSIGNED_ZONE  = "assignedZone";
    public static final String F_DISPLAY_NAME   = "displayName";
    public static final String F_PHONE          = "phone";
    public static final String F_ACTIVE_ALERTS  = "activeAlerts";
    public static final String F_LAST_ALERT_ID  = "lastAssignedAlertId";

    // Max distance (km) to consider a volunteer reachable
    private static final double MAX_DISTANCE_KM = 5.0;

    public interface AssignmentCallback {
        void onAssigned(String volunteerId, String volunteerName, String volunteerPhone);
        void onNoVolunteerAvailable();
    }

    private static VolunteerAssignmentManager instance;
    private final Context           context;
    private final FirebaseFirestore db;
    private final FirebaseManager   firebaseMgr;

    private VolunteerAssignmentManager(Context context) {
        this.context    = context.getApplicationContext();
        this.firebaseMgr = FirebaseManager.getInstance(context);
        FirebaseFirestore tmp = null;
        try { tmp = FirebaseFirestore.getInstance(); } catch (Exception ignored) {}
        this.db = tmp;
    }

    public static synchronized VolunteerAssignmentManager getInstance(Context context) {
        if (instance == null) instance = new VolunteerAssignmentManager(context);
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Auto-assign
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Queries Firestore for available volunteers and assigns the best one
     * to the given alert. Call this after the alert document is created.
     *
     * @param alertId      Firestore alert document ID
     * @param alertLat     alert latitude (from GPS or campus zone)
     * @param alertLng     alert longitude
     * @param alertZone    campus zone type (may be null)
     * @param callback     receives assignment result on main thread
     */
    public void autoAssign(String alertId, double alertLat, double alertLng,
                            String alertZone, AssignmentCallback callback) {
        if (db == null || alertId == null) {
            if (callback != null) callback.onNoVolunteerAvailable();
            return;
        }

        db.collection(AuthManager.COL_RESPONDERS)    // same collection as responders
                .whereEqualTo(F_AVAILABLE, true)
                .whereEqualTo(F_ACTIVE_ALERTS, 0)
                .get()
                .addOnSuccessListener(query -> {
                    List<VolunteerCandidate> candidates = new ArrayList<>();

                    for (QueryDocumentSnapshot doc : query) {
                        Object lat = doc.get(F_LATITUDE);
                        Object lng = doc.get(F_LONGITUDE);
                        if (!(lat instanceof Number) || !(lng instanceof Number)) continue;

                        double vLat  = ((Number) lat).doubleValue();
                        double vLng  = ((Number) lng).doubleValue();
                        double distKm = haversineKm(alertLat, alertLng, vLat, vLng);

                        if (distKm > MAX_DISTANCE_KM) continue;

                        String name  = doc.getString(F_DISPLAY_NAME);
                        String phone = doc.getString(F_PHONE);
                        String zone  = doc.getString(F_ASSIGNED_ZONE);
                        boolean zoneMatch = alertZone != null && alertZone.equalsIgnoreCase(zone);

                        candidates.add(new VolunteerCandidate(
                                doc.getId(), name, phone, distKm, zoneMatch));
                    }

                    if (candidates.isEmpty()) {
                        Log.d(TAG, "No volunteers available within " + MAX_DISTANCE_KM + "km");
                        if (callback != null) callback.onNoVolunteerAvailable();
                        return;
                    }

                    // Sort: zone match first, then by distance
                    candidates.sort((a, b) -> {
                        if (a.zoneMatch != b.zoneMatch) return a.zoneMatch ? -1 : 1;
                        return Double.compare(a.distanceKm, b.distanceKm);
                    });

                    VolunteerCandidate best = candidates.get(0);
                    Log.d(TAG, "Assigning volunteer: " + best.name + " (" + best.distanceKm + "km)");
                    commitAssignment(alertId, best, callback);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Volunteer query failed", e);
                    if (callback != null) callback.onNoVolunteerAvailable();
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Write assignment to Firestore
    // ══════════════════════════════════════════════════════════════════════════

    private void commitAssignment(String alertId, VolunteerCandidate volunteer,
                                   AssignmentCallback callback) {
        long now = System.currentTimeMillis();

        // Update alert document
        Map<String, Object> alertUpdate = new HashMap<>();
        alertUpdate.put(FirebaseManager.FIELD_STATUS,    AlertRecord.STATUS_RESPONDER_ASSIGNED);
        alertUpdate.put("assignedVolunteerId",   volunteer.uid);
        alertUpdate.put("assignedVolunteerName", volunteer.name);
        alertUpdate.put("assignedVolunteerPhone", volunteer.phone);
        alertUpdate.put("assignedTime",          now);
        alertUpdate.put("lastUpdated",           now);

        db.collection(FirebaseManager.COL_ALERTS).document(alertId)
                .update(alertUpdate)
                .addOnSuccessListener(v -> {
                    // Increment volunteer's activeAlerts
                    Map<String, Object> volunteerUpdate = new HashMap<>();
                    volunteerUpdate.put(F_ACTIVE_ALERTS, 1);
                    volunteerUpdate.put(F_LAST_ALERT_ID, alertId);
                    db.collection(AuthManager.COL_RESPONDERS).document(volunteer.uid)
                            .update(volunteerUpdate)
                            .addOnFailureListener(e -> Log.w(TAG, "Volunteer counter update failed", e));

                    if (callback != null) {
                        callback.onAssigned(volunteer.uid, volunteer.name, volunteer.phone);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Assignment commit failed", e);
                    if (callback != null) callback.onNoVolunteerAvailable();
                });
    }

    /** Release volunteer when alert is resolved or closed. */
    public void releaseVolunteer(String volunteerId) {
        if (db == null || volunteerId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put(F_ACTIVE_ALERTS, 0);
        db.collection(AuthManager.COL_RESPONDERS).document(volunteerId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "Release volunteer failed", e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper
    // ══════════════════════════════════════════════════════════════════════════

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private static class VolunteerCandidate {
        String  uid, name, phone;
        double  distanceKm;
        boolean zoneMatch;

        VolunteerCandidate(String uid, String name, String phone,
                            double distKm, boolean zoneMatch) {
            this.uid         = uid;
            this.name        = name != null ? name : "Volunteer";
            this.phone       = phone != null ? phone : "";
            this.distanceKm  = distKm;
            this.zoneMatch   = zoneMatch;
        }
    }
}
