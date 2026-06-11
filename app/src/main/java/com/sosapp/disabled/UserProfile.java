package com.sosapp.disabled;

import com.google.gson.Gson;

/**
 * UserProfile — stores everything about the SOS user that helps responders
 * and caregivers provide accurate, targeted assistance.
 *
 * Persistence: serialised to JSON in SharedPreferences (local) and
 * mirrored to Firestore users/{userId} (online). ProfileManager handles both.
 *
 * Accessibility: disability type drives UserModeManager's adaptive profile.
 * Medical info is embedded in the SOS message when caregivers request it.
 */
public class UserProfile {

    // ── Disability types (maps to UserModeManager modes) ─────────────────────
    public static final String DISABILITY_NONE     = "NONE";
    public static final String DISABILITY_VISION   = "VISION";
    public static final String DISABILITY_HEARING  = "HEARING";
    public static final String DISABILITY_MOTOR    = "MOTOR";
    public static final String DISABILITY_COGNITIVE = "COGNITIVE";
    public static final String DISABILITY_MULTIPLE = "MULTIPLE";

    // ── Preferred trigger types ───────────────────────────────────────────────
    public static final String TRIGGER_PREF_BUTTON = "BUTTON";
    public static final String TRIGGER_PREF_VOICE  = "VOICE";
    public static final String TRIGGER_PREF_SHAKE  = "SHAKE";
    public static final String TRIGGER_PREF_VOLUME = "VOLUME";

    // ── Blood groups ──────────────────────────────────────────────────────────
    public static final String[] BLOOD_GROUPS = {"A+","A−","B+","B−","AB+","AB−","O+","O−","Unknown"};

    // ─── Core identity ────────────────────────────────────────────────────────
    private String userId;          // matches FirebaseManager userId
    private String fullName;
    private String phone;
    private String email;
    private String institutionName; // college / school / workplace
    private String rollNumber;      // student / employee ID

    // ─── Disability & accessibility ───────────────────────────────────────────
    private String disabilityType = DISABILITY_NONE;
    private String preferredTrigger = TRIGGER_PREF_BUTTON;
    private String preferredLanguage = "en";
    private boolean useLargeText = false;
    private boolean useEnhancedVibration = false;
    private boolean useTts = true;
    private boolean silentModeDefault = false;

    // ─── Medical information ──────────────────────────────────────────────────
    private String bloodGroup = "Unknown";
    private String allergies = "";           // comma-separated
    private String currentMedications = "";  // comma-separated
    private String medicalConditions = "";   // chronic conditions
    private String doctorName = "";
    private String doctorPhone = "";
    private String hospitalName = "";

    // ─── Emergency notes ─────────────────────────────────────────────────────
    private String emergencyNotes = "";      // free text for first responders
    private boolean shareProfileOnSOS = true; // if true, medical summary appended to SOS

    // ─── Timestamps ──────────────────────────────────────────────────────────
    private long createdAt;
    private long updatedAt;

    public UserProfile() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Getters / Setters
    // ══════════════════════════════════════════════════════════════════════════

    public String getUserId()               { return userId; }
    public void   setUserId(String v)       { userId = v; updatedAt = now(); }

    public String getFullName()             { return fullName != null ? fullName : ""; }
    public void   setFullName(String v)     { fullName = v; updatedAt = now(); }

    public String getPhone()                { return phone != null ? phone : ""; }
    public void   setPhone(String v)        { phone = v; updatedAt = now(); }

    public String getEmail()                { return email != null ? email : ""; }
    public void   setEmail(String v)        { email = v; updatedAt = now(); }

    public String getInstitutionName()      { return institutionName != null ? institutionName : ""; }
    public void   setInstitutionName(String v) { institutionName = v; updatedAt = now(); }

    public String getRollNumber()           { return rollNumber != null ? rollNumber : ""; }
    public void   setRollNumber(String v)   { rollNumber = v; updatedAt = now(); }

    public String getDisabilityType()       { return disabilityType; }
    public void   setDisabilityType(String v) { disabilityType = v; updatedAt = now(); }

    public String getPreferredTrigger()     { return preferredTrigger; }
    public void   setPreferredTrigger(String v) { preferredTrigger = v; updatedAt = now(); }

    public String getPreferredLanguage()    { return preferredLanguage; }
    public void   setPreferredLanguage(String v) { preferredLanguage = v; updatedAt = now(); }

    public boolean isUseLargeText()         { return useLargeText; }
    public void    setUseLargeText(boolean v) { useLargeText = v; updatedAt = now(); }

    public boolean isUseEnhancedVibration() { return useEnhancedVibration; }
    public void    setUseEnhancedVibration(boolean v) { useEnhancedVibration = v; updatedAt = now(); }

    public boolean isUseTts()               { return useTts; }
    public void    setUseTts(boolean v)     { useTts = v; updatedAt = now(); }

    public boolean isSilentModeDefault()    { return silentModeDefault; }
    public void    setSilentModeDefault(boolean v) { silentModeDefault = v; updatedAt = now(); }

    public String getBloodGroup()           { return bloodGroup; }
    public void   setBloodGroup(String v)   { bloodGroup = v; updatedAt = now(); }

    public String getAllergies()            { return allergies; }
    public void   setAllergies(String v)   { allergies = v; updatedAt = now(); }

    public String getCurrentMedications()  { return currentMedications; }
    public void   setCurrentMedications(String v) { currentMedications = v; updatedAt = now(); }

    public String getMedicalConditions()   { return medicalConditions; }
    public void   setMedicalConditions(String v) { medicalConditions = v; updatedAt = now(); }

    public String getDoctorName()          { return doctorName; }
    public void   setDoctorName(String v)  { doctorName = v; updatedAt = now(); }

    public String getDoctorPhone()         { return doctorPhone; }
    public void   setDoctorPhone(String v) { doctorPhone = v; updatedAt = now(); }

    public String getHospitalName()        { return hospitalName; }
    public void   setHospitalName(String v) { hospitalName = v; updatedAt = now(); }

    public String getEmergencyNotes()      { return emergencyNotes; }
    public void   setEmergencyNotes(String v) { emergencyNotes = v; updatedAt = now(); }

    public boolean isShareProfileOnSOS()   { return shareProfileOnSOS; }
    public void    setShareProfileOnSOS(boolean v) { shareProfileOnSOS = v; updatedAt = now(); }

    public long getCreatedAt()             { return createdAt; }
    public long getUpdatedAt()             { return updatedAt; }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a compact medical summary to append to the SOS message.
     * Only included if shareProfileOnSOS is true and any field is non-empty.
     */
    public String getMedicalSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- MEDICAL INFO ---");
        if (!isEmpty(fullName))          sb.append("\nName: ").append(fullName);
        if (!isEmpty(bloodGroup) && !"Unknown".equals(bloodGroup))
                                          sb.append("\nBlood: ").append(bloodGroup);
        if (!isEmpty(allergies))          sb.append("\nAllergies: ").append(allergies);
        if (!isEmpty(medicalConditions))  sb.append("\nConditions: ").append(medicalConditions);
        if (!isEmpty(currentMedications)) sb.append("\nMeds: ").append(currentMedications);
        if (!isEmpty(doctorPhone))        sb.append("\nDoctor: ").append(doctorName)
                                           .append(" ").append(doctorPhone);
        if (!isEmpty(emergencyNotes))     sb.append("\nNotes: ").append(emergencyNotes);
        return sb.length() > 20 ? sb.toString() : "";
    }

    public boolean isProfileComplete() {
        return !isEmpty(fullName) && !isEmpty(phone);
    }

    /**
     * Maps disability type to UserModeManager mode string.
     */
    public String toUserMode() {
        switch (disabilityType) {
            case DISABILITY_VISION:   return UserModeManager.MODE_VISION;
            case DISABILITY_HEARING:  return UserModeManager.MODE_HEARING;
            case DISABILITY_MOTOR:
            case DISABILITY_COGNITIVE: return UserModeManager.MODE_MOTOR;
            default:                  return UserModeManager.MODE_DEFAULT;
        }
    }

    // ── JSON serialisation (for SharedPreferences) ────────────────────────────

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static UserProfile fromJson(String json) {
        if (json == null || json.isEmpty()) return new UserProfile();
        try { return new Gson().fromJson(json, UserProfile.class); }
        catch (Exception e) { return new UserProfile(); }
    }

    private long now() { return System.currentTimeMillis(); }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
