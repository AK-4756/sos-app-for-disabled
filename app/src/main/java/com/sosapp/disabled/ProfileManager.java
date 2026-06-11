package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * ProfileManager — single source of truth for UserProfile.
 *
 * Storage:
 *   Local  → SharedPreferences (JSON blob, instant read, offline-capable)
 *   Remote → Firestore users/{userId} (synced when online)
 *
 * The profile is automatically applied to SettingsActivity keys so that
 * accessibility preferences set in the profile take effect app-wide.
 */
public class ProfileManager {

    private static final String TAG      = "ProfileManager";
    private static final String PREF_KEY = "user_profile_json";

    private static ProfileManager instance;
    private final Context context;
    private UserProfile cached;

    private ProfileManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized ProfileManager getInstance(Context context) {
        if (instance == null) instance = new ProfileManager(context);
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Read / Write
    // ══════════════════════════════════════════════════════════════════════════

    public UserProfile getProfile() {
        if (cached != null) return cached;
        String json = prefs().getString(PREF_KEY, null);
        cached = UserProfile.fromJson(json);
        // Ensure userId is always set
        if (cached.getUserId() == null || cached.getUserId().isEmpty()) {
            String uid = prefs().getString(FirebaseManager.PREF_USER_ID, null);
            if (uid != null) cached.setUserId(uid);
        }
        return cached;
    }

    public void saveProfile(UserProfile profile) {
        cached = profile;
        prefs().edit().putString(PREF_KEY, profile.toJson()).apply();
        applyProfileToSettings(profile);
        syncToFirestore(profile);
        Log.d(TAG, "Profile saved locally for: " + profile.getFullName());
    }

    public boolean isProfileComplete() {
        return getProfile().isProfileComplete();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Apply profile → SettingsActivity SharedPreferences
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the profile's accessibility preferences into the main settings
     * SharedPreferences so MainActivity picks them up without any extra code.
     */
    private void applyProfileToSettings(UserProfile profile) {
        SharedPreferences.Editor ed = context
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit();

        ed.putString(SettingsActivity.KEY_USER_MODE,   profile.toUserMode());
        ed.putString(SettingsActivity.KEY_LANGUAGE,    profile.getPreferredLanguage());
        ed.putBoolean(SettingsActivity.KEY_LARGE_TEXT, profile.isUseLargeText());
        ed.putBoolean(SettingsActivity.KEY_TTS_ENABLED, profile.isUseTts());
        ed.putBoolean(SettingsActivity.KEY_SILENT_MODE, profile.isSilentModeDefault());
        ed.apply();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase sync
    // ══════════════════════════════════════════════════════════════════════════

    private void syncToFirestore(UserProfile profile) {
        FirebaseManager fm = FirebaseManager.getInstance(context);
        if (!fm.isAvailable() || profile.getUserId() == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("userId",             profile.getUserId());
        data.put("fullName",           profile.getFullName());
        data.put("phone",              profile.getPhone());
        data.put("email",              profile.getEmail());
        data.put("institutionName",    profile.getInstitutionName());
        data.put("rollNumber",         profile.getRollNumber());
        data.put("disabilityType",     profile.getDisabilityType());
        data.put("preferredTrigger",   profile.getPreferredTrigger());
        data.put("preferredLanguage",  profile.getPreferredLanguage());
        data.put("bloodGroup",         profile.getBloodGroup());
        data.put("allergies",          profile.getAllergies());
        data.put("medicalConditions",  profile.getMedicalConditions());
        data.put("currentMedications", profile.getCurrentMedications());
        data.put("doctorName",         profile.getDoctorName());
        data.put("doctorPhone",        profile.getDoctorPhone());
        data.put("hospitalName",       profile.getHospitalName());
        data.put("emergencyNotes",     profile.getEmergencyNotes());
        data.put("shareProfileOnSOS",  profile.isShareProfileOnSOS());
        data.put("updatedAt",          profile.getUpdatedAt());

        FirebaseFirestore.getInstance()
                .collection(FirebaseManager.COL_USERS)
                .document(profile.getUserId())
                .set(data, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "Firestore profile sync failed", e));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fetch from Firestore (on login / restore)
    // ══════════════════════════════════════════════════════════════════════════

    public interface OnProfileFetched {
        void onFetched(UserProfile profile);
        void onFailed(Exception e);
    }

    public void fetchFromFirestore(String userId, OnProfileFetched callback) {
        FirebaseManager fm = FirebaseManager.getInstance(context);
        if (!fm.isAvailable()) {
            if (callback != null) callback.onFetched(getProfile());
            return;
        }
        FirebaseFirestore.getInstance()
                .collection(FirebaseManager.COL_USERS)
                .document(userId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        UserProfile p = getProfile();
                        p.setUserId(userId);
                        if (doc.getString("fullName") != null) p.setFullName(doc.getString("fullName"));
                        if (doc.getString("phone") != null)    p.setPhone(doc.getString("phone"));
                        if (doc.getString("bloodGroup") != null) p.setBloodGroup(doc.getString("bloodGroup"));
                        if (doc.getString("disabilityType") != null) p.setDisabilityType(doc.getString("disabilityType"));
                        if (doc.getString("emergencyNotes") != null) p.setEmergencyNotes(doc.getString("emergencyNotes"));
                        saveProfile(p);
                        if (callback != null) callback.onFetched(p);
                    } else {
                        if (callback != null) callback.onFetched(getProfile());
                    }
                })
                .addOnFailureListener(e -> { if (callback != null) callback.onFailed(e); });
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
