package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SavedLocationManager — stores named preset locations so users can
 * quickly attach "Home", "Hostel", "Classroom" etc. to their SOS alert
 * even when GPS is poor (e.g. inside buildings).
 *
 * Each saved location has:
 *   name     — display label ("Home", "Hostel", …)
 *   lat/lng  — coordinates captured when user saved it
 *   address  — optional human-readable description
 *
 * Persisted as a JSON array in SharedPreferences (no extra DB table needed).
 */
public class SavedLocationManager {

    private static final String PREF_SAVED_LOCS = "saved_locations_json";
    private static final int    MAX_SAVED       = 10;

    // Built-in quick labels the user can assign to their current GPS position
    public static final String[] QUICK_LABELS = {
            "Home", "Work", "Hostel", "Classroom", "Hospital",
            "Relative's Home", "Custom"
    };

    // Special label for real-time GPS
    public static final String LABEL_LIVE     = "Live Location";
    public static final String LABEL_LAST     = "Last Known Location";

    public static class SavedLocation {
        public String name;
        public double lat;
        public double lng;
        public String address;

        public SavedLocation(String name, double lat, double lng, String address) {
            this.name    = name;
            this.lat     = lat;
            this.lng     = lng;
            this.address = address != null ? address : "";
        }

        public String toMapUrl() {
            return "https://maps.google.com/?q=" + lat + "," + lng;
        }

        /** Label shown in SMS: "Home: https://maps.google.com/?q=..." */
        public String toAlertString() {
            return name + ": " + toMapUrl();
        }
    }

    private final Context context;

    public SavedLocationManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CRUD
    // ══════════════════════════════════════════════════════════════════════════

    public List<SavedLocation> getAll() {
        List<SavedLocation> list = new ArrayList<>();
        String json = prefs().getString(PREF_SAVED_LOCS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new SavedLocation(
                        o.getString("name"),
                        o.getDouble("lat"),
                        o.getDouble("lng"),
                        o.optString("address", "")));
            }
        } catch (JSONException ignored) {}
        return list;
    }

    public void saveLocation(String name, double lat, double lng, String address) {
        List<SavedLocation> list = getAll();

        // Replace existing entry with same name
        list.removeIf(l -> l.name.equalsIgnoreCase(name));

        // Enforce max cap
        if (list.size() >= MAX_SAVED) list.remove(list.size() - 1);

        list.add(0, new SavedLocation(name, lat, lng, address));
        persist(list);
    }

    public void saveCurrentLocation(Context ctx, String name, Location location) {
        if (location == null) return;
        saveLocation(name, location.getLatitude(), location.getLongitude(), null);
    }

    public void deleteLocation(String name) {
        List<SavedLocation> list = getAll();
        list.removeIf(l -> l.name.equalsIgnoreCase(name));
        persist(list);
    }

    public SavedLocation getByName(String name) {
        for (SavedLocation l : getAll()) {
            if (l.name.equalsIgnoreCase(name)) return l;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Alert message building
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the {location} substitution string for the SOS message.
     *
     * If a saved location is selected → "Home: https://maps.google.com/?q=..."
     * If live GPS available           → "Live Location: https://maps.google.com/?q=..."
     * If only last known              → "Last Known Location: https://maps.google.com/?q=..."
     * If nothing                      → "Location unavailable"
     *
     * @param selectedName  name of chosen quick location, or null to use GPS
     * @param liveLocation  current GPS fix (may be null)
     */
    public static class LocationResult {
        public String text;
        public String label;
        public String latLngSnap; // "lat,lng" for storage

        LocationResult(String text, String label, String latLngSnap) {
            this.text      = text;
            this.label     = label;
            this.latLngSnap = latLngSnap;
        }
    }

    public LocationResult resolveLocation(String selectedName, Location liveLocation) {
        // Quick-select overrides GPS
        if (selectedName != null && !selectedName.isEmpty()
                && !LABEL_LIVE.equals(selectedName)
                && !LABEL_LAST.equals(selectedName)) {
            SavedLocation saved = getByName(selectedName);
            if (saved != null) {
                return new LocationResult(
                        saved.toAlertString(),
                        saved.name,
                        saved.lat + "," + saved.lng);
            }
        }

        // GPS
        if (liveLocation != null) {
            String url  = "https://maps.google.com/?q=" +
                    liveLocation.getLatitude() + "," + liveLocation.getLongitude();
            String snap = liveLocation.getLatitude() + "," + liveLocation.getLongitude();
            return new LocationResult(LABEL_LIVE + ": " + url, LABEL_LIVE, snap);
        }

        return new LocationResult("Location unavailable", "Unknown", null);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void persist(List<SavedLocation> list) {
        JSONArray arr = new JSONArray();
        for (SavedLocation l : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name",    l.name);
                o.put("lat",     l.lat);
                o.put("lng",     l.lng);
                o.put("address", l.address);
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        prefs().edit().putString(PREF_SAVED_LOCS, arr.toString()).apply();
    }
}
