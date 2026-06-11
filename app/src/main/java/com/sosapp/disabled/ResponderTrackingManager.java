package com.sosapp.disabled;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * ResponderTrackingManager — tracks a responder's live location and feeds
 * distance + ETA back to the alert user in real time.
 *
 * Two sides:
 *
 * RESPONDER SIDE (DashboardActivity calls pushLocation):
 *   Every 20 seconds while "On the Way", responder's device pushes
 *   its GPS coordinates to Firestore responders/{uid}/location.
 *
 * USER SIDE (MainActivity calls startTracking):
 *   Listens on the alert document for responderId, then opens a
 *   Firestore snapshot on that responder's location. Computes Haversine
 *   distance and a rough ETA (assuming average campus walking ~4 km/h).
 *   Calls TrackingCallback with updates.
 *
 * No paid API used. Haversine formula only.
 */
public class ResponderTrackingManager {

    private static final String TAG              = "ResponderTracking";
    private static final long   UPDATE_INTERVAL  = 20_000L;  // 20 seconds
    private static final double WALKING_SPEED_KMH = 4.0;     // campus walking

    public interface TrackingCallback {
        /** Called on main thread when responder location is updated. */
        void onLocationUpdate(double distanceKm, int etaMinutes, String responderName);
        void onTrackingStopped();
    }

    private static ResponderTrackingManager instance;
    private final Context           context;
    private final FirebaseFirestore db;
    private final Handler           handler = new Handler(Looper.getMainLooper());

    // User tracking state
    private ListenerRegistration responderListener;
    private TrackingCallback     trackingCallback;
    private double               userLat, userLng;
    private String               trackedResponderId;

    // Responder push state
    private Runnable pushRunnable;
    private String   activeAlertId;

    private ResponderTrackingManager(Context context) {
        this.context = context.getApplicationContext();
        FirebaseFirestore tmp = null;
        try { tmp = FirebaseFirestore.getInstance(); } catch (Exception ignored) {}
        this.db = tmp;
    }

    public static synchronized ResponderTrackingManager getInstance(Context context) {
        if (instance == null) instance = new ResponderTrackingManager(context);
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USER SIDE — listen for incoming responder location
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Start watching a responder's location for the given alert.
     * @param responderId   Firestore UID of assigned responder
     * @param userLat       Alert user's latitude
     * @param userLng       Alert user's longitude
     * @param responderName Display name for TTS / UI
     * @param callback      Receives updates on main thread
     */
    public void startTracking(String responderId, double userLat, double userLng,
                               String responderName, TrackingCallback callback) {
        if (db == null || responderId == null) return;
        stopTracking();

        this.trackedResponderId = responderId;
        this.userLat            = userLat;
        this.userLng            = userLng;
        this.trackingCallback   = callback;

        Log.d(TAG, "Tracking responder: " + responderId);

        responderListener = db.collection(AuthManager.COL_RESPONDERS)
                .document(responderId)
                .addSnapshotListener((doc, e) -> {
                    if (e != null || doc == null || !doc.exists()) return;
                    Object lat = doc.get("latitude");
                    Object lng = doc.get("longitude");
                    if (!(lat instanceof Number) || !(lng instanceof Number)) return;

                    double rLat = ((Number) lat).doubleValue();
                    double rLng = ((Number) lng).doubleValue();
                    double distKm  = haversineKm(userLat, userLng, rLat, rLng);
                    int    etaMins = distanceToEtaMinutes(distKm);

                    Log.d(TAG, "Responder distance: " + distKm + "km ETA: " + etaMins + "min");
                    if (callback != null) {
                        callback.onLocationUpdate(distKm, etaMins, responderName);
                    }
                });
    }

    public void stopTracking() {
        if (responderListener != null) {
            responderListener.remove();
            responderListener = null;
        }
        if (trackingCallback != null) {
            trackingCallback.onTrackingStopped();
            trackingCallback = null;
        }
        trackedResponderId = null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RESPONDER SIDE — push location to Firestore periodically
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called by DashboardActivity when responder marks "On the Way".
     * Pushes their location every UPDATE_INTERVAL ms.
     * Location is obtained externally and passed in (avoids permission complexity here).
     */
    public void startPushingLocation(String responderId, String alertId) {
        this.activeAlertId = alertId;
        stopPushingLocation();

        pushRunnable = new Runnable() {
            @Override
            public void run() {
                // Location is pushed by the dashboard when available
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        handler.post(pushRunnable);
        Log.d(TAG, "Responder location push started for alert: " + alertId);
    }

    /** Called by DashboardActivity with fresh coordinates from FusedLocationProvider. */
    public void pushResponderLocation(String responderId, double lat, double lng) {
        if (db == null || responderId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("latitude",         lat);
        update.put("longitude",        lng);
        update.put("locationUpdatedAt", System.currentTimeMillis());
        db.collection(AuthManager.COL_RESPONDERS).document(responderId)
                .update(update)
                .addOnFailureListener(e -> Log.w(TAG, "Push location failed", e));
    }

    public void stopPushingLocation() {
        if (pushRunnable != null) {
            handler.removeCallbacks(pushRunnable);
            pushRunnable = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
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

    private int distanceToEtaMinutes(double distKm) {
        if (distKm < 0.05) return 0; // essentially here
        return (int) Math.ceil((distKm / WALKING_SPEED_KMH) * 60.0);
    }

    public boolean isTracking() { return responderListener != null; }
}
