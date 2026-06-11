package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RouteMonitorManager — detects when the user deviates significantly from
 * their expected route between saved campus waypoints.
 *
 * Battery optimisation:
 *   Uses PRIORITY_BALANCED_POWER_ACCURACY (cell + WiFi only, no GPS)
 *   with a 5-minute interval. GPS kicks in only when deviation is suspected.
 *
 * Route definition:
 *   User saves up to 5 named waypoints (Home → Hostel → Classroom etc.)
 *   stored in SharedPreferences as a JSON map {name → {lat, lng}}.
 *
 * Deviation logic:
 *   At each poll, the app checks if the user is within SAFE_RADIUS_M of
 *   any waypoint on their expected route. If they are >DEVIATION_THRESHOLD_M
 *   from ALL waypoints for DEVIATION_STRIKES consecutive checks → triggers
 *   a safety check notification. If the user ignores it for IGNORE_TIMEOUT_MS
 *   → SOS recommendation fires via DeviationCallback.
 */
public class RouteMonitorManager {

    private static final String TAG = "RouteMonitor";

    // ── Tuning constants ──────────────────────────────────────────────────────
    private static final float SAFE_RADIUS_M          = 150f;   // within this = on-route
    private static final float DEVIATION_THRESHOLD_M  = 400f;   // beyond this = suspicious
    private static final int   DEVIATION_STRIKES       = 2;     // consecutive out-of-route polls
    private static final long  POLL_INTERVAL_MS        = 5 * 60_000L; // 5 minutes
    private static final long  IGNORE_TIMEOUT_MS       = 3 * 60_000L; // 3 minutes to respond

    private static final String PREF_WAYPOINTS = "route_waypoints_json";

    public interface DeviationCallback {
        void onDeviationSuspected(double lat, double lng, float deviationMetres);
        void onSosRecommended(double lat, double lng);
        void onRouteResumed();
    }

    // ── Waypoint model ────────────────────────────────────────────────────────
    public static class Waypoint {
        public String name;
        public double lat, lng;
        public Waypoint(String name, double lat, double lng) {
            this.name = name; this.lat = lat; this.lng = lng;
        }
    }

    private static RouteMonitorManager instance;
    private final Context                   context;
    private final FusedLocationProviderClient fusedClient;
    private final Handler                   handler = new Handler(Looper.getMainLooper());
    private DeviationCallback               callback;

    private boolean       monitoring     = false;
    private int           strikeCount    = 0;
    private boolean       awaitingResponse = false;
    private Runnable      ignoreTimer;
    private LocationCallback locationCallback;

    private RouteMonitorManager(Context context) {
        this.context      = context.getApplicationContext();
        this.fusedClient  = LocationServices.getFusedLocationProviderClient(context);
    }

    public static synchronized RouteMonitorManager getInstance(Context context) {
        if (instance == null) instance = new RouteMonitorManager(context);
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Waypoint management
    // ══════════════════════════════════════════════════════════════════════════

    public void saveWaypoint(String name, double lat, double lng) {
        Map<String, double[]> map = loadWaypointMap();
        map.put(name, new double[]{lat, lng});
        if (map.size() > 5) {
            String oldest = map.keySet().iterator().next();
            map.remove(oldest);
        }
        prefs().edit().putString(PREF_WAYPOINTS, new Gson().toJson(map)).apply();
        Log.d(TAG, "Waypoint saved: " + name + " @ " + lat + "," + lng);
    }

    public void saveWaypointFromLocation(String name, Location location) {
        if (location != null) saveWaypoint(name, location.getLatitude(), location.getLongitude());
    }

    public void deleteWaypoint(String name) {
        Map<String, double[]> map = loadWaypointMap();
        map.remove(name);
        prefs().edit().putString(PREF_WAYPOINTS, new Gson().toJson(map)).apply();
    }

    public Map<String, double[]> getWaypoints() { return loadWaypointMap(); }

    // ══════════════════════════════════════════════════════════════════════════
    // Monitoring lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    public void startMonitoring(DeviationCallback cb) {
        if (monitoring) return;
        this.callback = cb;
        monitoring    = true;
        strikeCount   = 0;

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, POLL_INTERVAL_MS)
                .setMinUpdateIntervalMillis(POLL_INTERVAL_MS / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc != null) checkDeviation(loc);
            }
        };

        try {
            fusedClient.requestLocationUpdates(request, locationCallback,
                    Looper.getMainLooper());
            Log.d(TAG, "Route monitoring started");
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted for route monitor");
            monitoring = false;
        }
    }

    public void stopMonitoring() {
        monitoring = false;
        if (locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        cancelIgnoreTimer();
        Log.d(TAG, "Route monitoring stopped");
    }

    /** Call this when user confirms they are safe (from notification action). */
    public void userConfirmedSafe() {
        strikeCount = 0;
        awaitingResponse = false;
        cancelIgnoreTimer();
        if (callback != null) callback.onRouteResumed();
    }

    public boolean isMonitoring() { return monitoring; }

    // ══════════════════════════════════════════════════════════════════════════
    // Deviation detection
    // ══════════════════════════════════════════════════════════════════════════

    private void checkDeviation(Location loc) {
        Map<String, double[]> waypoints = loadWaypointMap();
        if (waypoints.isEmpty()) return;

        float nearestDist = Float.MAX_VALUE;
        for (double[] wp : waypoints.values()) {
            float[] results = new float[1];
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                    wp[0], wp[1], results);
            nearestDist = Math.min(nearestDist, results[0]);
        }

        if (nearestDist <= SAFE_RADIUS_M) {
            // On route — reset
            if (strikeCount > 0) {
                Log.d(TAG, "Back on route, resetting strike count");
                strikeCount = 0;
                awaitingResponse = false;
                cancelIgnoreTimer();
                if (callback != null) callback.onRouteResumed();
            }
        } else if (nearestDist > DEVIATION_THRESHOLD_M) {
            strikeCount++;
            Log.d(TAG, "Off-route strike " + strikeCount + " (dist=" + nearestDist + "m)");

            if (strikeCount >= DEVIATION_STRIKES && !awaitingResponse) {
                awaitingResponse = true;
                if (callback != null) {
                    callback.onDeviationSuspected(
                            loc.getLatitude(), loc.getLongitude(), nearestDist);
                }
                // If no response within IGNORE_TIMEOUT_MS → escalate
                ignoreTimer = () -> {
                    if (awaitingResponse && callback != null) {
                        callback.onSosRecommended(loc.getLatitude(), loc.getLongitude());
                    }
                    awaitingResponse = false;
                };
                handler.postDelayed(ignoreTimer, IGNORE_TIMEOUT_MS);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, double[]> loadWaypointMap() {
        String json = prefs().getString(PREF_WAYPOINTS, "{}");
        try {
            Map<String, Object> raw = new Gson().fromJson(json, Map.class);
            Map<String, double[]> result = new LinkedHashMap<>();
            if (raw != null) {
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (e.getValue() instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) e.getValue();
                        if (list.size() >= 2) {
                            double lat = ((Number) list.get(0)).doubleValue();
                            double lng = ((Number) list.get(1)).doubleValue();
                            result.put(e.getKey(), new double[]{lat, lng});
                        }
                    }
                }
            }
            return result;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private void cancelIgnoreTimer() {
        if (ignoreTimer != null) { handler.removeCallbacks(ignoreTimer); ignoreTimer = null; }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
