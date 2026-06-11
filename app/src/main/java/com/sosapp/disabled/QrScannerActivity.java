package com.sosapp.disabled;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

/**
 * QrScannerActivity — scans campus QR codes to automatically identify and
 * save the user's current indoor location.
 *
 * Expected QR payload format (JSON or pipe-delimited):
 *   JSON:  {"zone":"Classroom","building":"Block A","floor":"2","room":"204"}
 *   Pipe:  SOSZONE|Classroom|Block A|2|204
 *
 * On scan success:
 *   1. Parses the QR payload.
 *   2. Saves the zone to CampusZoneManager (using the device's current GPS
 *      as the zone anchor, or 0,0 if GPS is unavailable).
 *   3. Returns the zone name to the caller via Intent extra.
 *
 * Returns RESULT_OK with EXTRA_ZONE_NAME if successful.
 * Returns RESULT_CANCELED if the user backs out or QR is invalid.
 */
public class QrScannerActivity extends AppCompatActivity {

    public static final String EXTRA_ZONE_NAME = "zone_name";
    public static final String EXTRA_ZONE_TYPE = "zone_type";
    public static final String EXTRA_BUILDING  = "building";
    public static final String EXTRA_FLOOR     = "floor";
    public static final String EXTRA_ROOM      = "room";

    // QR pipe prefix that identifies a campus SOS zone code
    private static final String PIPE_PREFIX = "SOSZONE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.qr_scan_title));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Launch ZXing scanner immediately
        new IntentIntegrator(this)
                .setPrompt(getString(R.string.qr_scan_hint))
                .setBeepEnabled(true)
                .setOrientationLocked(true)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents != null) {
                ParsedZone zone = parseQrPayload(contents);
                if (zone != null) {
                    saveAndReturn(zone);
                } else {
                    Toast.makeText(this, getString(R.string.qr_scan_invalid),
                            Toast.LENGTH_SHORT).show();
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                }
            } else {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QR payload parsing
    // ══════════════════════════════════════════════════════════════════════════

    private ParsedZone parseQrPayload(String payload) {
        if (payload == null || payload.isEmpty()) return null;

        // Try pipe format: SOSZONE|ZoneType|Building|Floor|Room
        if (payload.startsWith(PIPE_PREFIX + "|")) {
            String[] parts = payload.split("\\|", -1);
            if (parts.length < 2) return null;
            ParsedZone z = new ParsedZone();
            z.type     = parts.length > 1 ? parts[1] : CampusZone.TYPE_CUSTOM;
            z.building = parts.length > 2 ? parts[2] : "";
            z.floor    = parts.length > 3 ? parts[3] : "";
            z.room     = parts.length > 4 ? parts[4] : "";
            z.name     = buildName(z);
            return z;
        }

        // Try JSON format: {"zone":"...","building":"...","floor":"...","room":"..."}
        if (payload.startsWith("{")) {
            try {
                com.google.gson.JsonObject obj = new com.google.gson.Gson()
                        .fromJson(payload, com.google.gson.JsonObject.class);
                ParsedZone z = new ParsedZone();
                z.type     = getStr(obj, "zone",     CampusZone.TYPE_CUSTOM);
                z.building = getStr(obj, "building", "");
                z.floor    = getStr(obj, "floor",    "");
                z.room     = getStr(obj, "room",     "");
                z.name     = buildName(z);
                return z;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private String buildName(ParsedZone z) {
        StringBuilder sb = new StringBuilder(z.type);
        if (!z.building.isEmpty()) sb.append(", ").append(z.building);
        if (!z.room.isEmpty())     sb.append(" R").append(z.room);
        return sb.toString();
    }

    private String getStr(com.google.gson.JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Save zone and return to caller
    // ══════════════════════════════════════════════════════════════════════════

    private void saveAndReturn(ParsedZone parsed) {
        CampusZoneManager mgr = CampusZoneManager.getInstance(this);
        // Save with 0,0 coordinates — the user should tap "Save Current Location"
        // inside the zone to set the real GPS anchor. QR just identifies the room.
        mgr.saveZoneFromLocation(parsed.name, parsed.type, null,
                parsed.building, parsed.floor, parsed.room);

        Toast.makeText(this, getString(R.string.qr_scan_success, parsed.name),
                Toast.LENGTH_SHORT).show();

        Intent result = new Intent();
        result.putExtra(EXTRA_ZONE_NAME, parsed.name);
        result.putExtra(EXTRA_ZONE_TYPE, parsed.type);
        result.putExtra(EXTRA_BUILDING,  parsed.building);
        result.putExtra(EXTRA_FLOOR,     parsed.floor);
        result.putExtra(EXTRA_ROOM,      parsed.room);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private static class ParsedZone {
        String name, type, building, floor, room;
    }

    @Override
    public boolean onSupportNavigateUp() {
        setResult(Activity.RESULT_CANCELED);
        finish();
        return true;
    }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }
}
