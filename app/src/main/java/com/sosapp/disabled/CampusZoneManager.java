package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CampusZoneManager — manages a list of named campus zones.
 *
 * Persists to SharedPreferences as a JSON array (offline-capable).
 * Provides auto-detection: given a GPS location, returns the nearest zone
 * within its radius, or null if the user is not inside any known zone.
 *
 * Integration:
 *   - MainActivity's location spinner is populated from getAll().
 *   - When an SOS is sent, resolveZone(location) gives a human-readable
 *     campus location string instead of raw coordinates.
 *   - QrLocationParser calls saveZone() to register QR-scanned rooms.
 */
public class CampusZoneManager {

    private static final String TAG      = "CampusZoneManager";
    private static final String PREF_KEY = "campus_zones_json";

    private static CampusZoneManager instance;
    private final Context context;
    private final Gson    gson = new Gson();

    private CampusZoneManager(Context context) {
        this.context = context.getApplicationContext();
        seedDefaultZones(); // add predefined zones if first run
    }

    public static synchronized CampusZoneManager getInstance(Context context) {
        if (instance == null) instance = new CampusZoneManager(context.getApplicationContext());
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CRUD
    // ══════════════════════════════════════════════════════════════════════════

    public List<CampusZone> getAll() {
        String json = prefs().getString(PREF_KEY, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<CampusZone>>(){}.getType();
            List<CampusZone> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
            return new ArrayList<>();
        }
    }

    public void saveZone(CampusZone zone) {
        if (zone.getId() == null || zone.getId().isEmpty()) {
            zone.setId(UUID.randomUUID().toString());
        }
        List<CampusZone> list = getAll();
        // Replace if same ID
        list.removeIf(z -> z.getId().equals(zone.getId()));
        list.add(0, zone);
        persist(list);
        Log.d(TAG, "Zone saved: " + zone.getName());
    }

    public void saveZoneFromLocation(String name, String type, Location location,
                                      String building, String floor, String room) {
        if (location == null) return;
        CampusZone zone = new CampusZone(
                UUID.randomUUID().toString(), name, type,
                location.getLatitude(), location.getLongitude());
        zone.setBuilding(building);
        zone.setFloor(floor);
        zone.setRoomNumber(room);
        zone.setPredefined(false);
        saveZone(zone);
    }

    public void deleteZone(String id) {
        List<CampusZone> list = getAll();
        list.removeIf(z -> z.getId().equals(id));
        persist(list);
    }

    public List<CampusZone> getUserZones() {
        List<CampusZone> result = new ArrayList<>();
        for (CampusZone z : getAll()) {
            if (!z.isPredefined()) result.add(z);
        }
        return result;
    }

    public List<CampusZone> getPredefinedZones() {
        List<CampusZone> result = new ArrayList<>();
        for (CampusZone z : getAll()) {
            if (z.isPredefined()) result.add(z);
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Auto-detection
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the zone the user is currently inside (nearest zone within radius),
     * or null if no zone matches. Works offline — no network required.
     */
    public CampusZone resolveZone(Location location) {
        if (location == null) return null;
        double lat = location.getLatitude();
        double lng = location.getLongitude();

        CampusZone nearest   = null;
        float      nearestDist = Float.MAX_VALUE;

        for (CampusZone zone : getAll()) {
            float dist = zone.distanceTo(lat, lng);
            if (dist <= zone.getRadiusMetres() && dist < nearestDist) {
                nearest    = zone;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    /**
     * Returns a human-readable location string for SOS messages.
     * Tries campus zone first, then falls back to GPS coordinates.
     */
    public SavedLocationManager.LocationResult resolveForAlert(Location location) {
        CampusZone zone = resolveZone(location);
        if (zone != null) {
            String label = zone.toLocationString();
            String url   = zone.toMapUrl();
            String snap  = zone.getLatitude() + "," + zone.getLongitude();
            return new SavedLocationManager.LocationResult(label + ": " + url, label, snap);
        }
        // Fallback to raw GPS
        if (location != null) {
            String snap = location.getLatitude() + "," + location.getLongitude();
            String url  = "https://maps.google.com/?q=" + snap;
            String label = SavedLocationManager.LABEL_LIVE;
            return new SavedLocationManager.LocationResult(label + ": " + url, label, snap);
        }
        return new SavedLocationManager.LocationResult("Location unavailable", "Unknown", null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Default zones seeding
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Seeds 8 predefined zones at 0,0 (placeholder coordinates).
     * Users can edit coordinates by re-saving their location inside each zone.
     * Only runs on first launch (checks if list is empty).
     */
    private void seedDefaultZones() {
        if (!getAll().isEmpty()) return;

        String[] types = {
                CampusZone.TYPE_CLASSROOM, CampusZone.TYPE_LAB, CampusZone.TYPE_HOSTEL,
                CampusZone.TYPE_LIBRARY,   CampusZone.TYPE_CANTEEN, CampusZone.TYPE_GATE,
                CampusZone.TYPE_PARKING,   CampusZone.TYPE_MEDICAL
        };

        List<CampusZone> list = new ArrayList<>();
        for (String type : types) {
            CampusZone z = new CampusZone(UUID.randomUUID().toString(), type, type, 0.0, 0.0);
            z.setPredefined(true);
            z.setRadiusMetres(100f); // larger default for predefined
            list.add(z);
        }
        persist(list);
        Log.d(TAG, "Seeded " + list.size() + " default campus zones");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════════

    private void persist(List<CampusZone> list) {
        prefs().edit().putString(PREF_KEY, gson.toJson(list)).apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
