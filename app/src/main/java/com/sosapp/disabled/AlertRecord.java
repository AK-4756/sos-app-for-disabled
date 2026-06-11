package com.sosapp.disabled;

/**
 * Represents one SOS alert event.
 * Stored locally in SQLite and synced to Firebase Firestore.
 *
 * Full lifecycle status:
 *   CREATED → SENT → DELIVERED → ACKNOWLEDGED → ON_THE_WAY → RESOLVED
 */
public class AlertRecord {

    // ── Trigger types ─────────────────────────────────────────────────────────
    public static final String TRIGGER_BUTTON  = "Button";
    public static final String TRIGGER_VOICE   = "Voice";
    public static final String TRIGGER_SHAKE   = "Shake";
    public static final String TRIGGER_VOLUME  = "Volume";
    public static final String TRIGGER_LONG    = "LongPress";
    public static final String TRIGGER_AUTO    = "AutoSOS";
    public static final String TRIGGER_FALL    = "FallDetected";

    // ── Full lifecycle statuses ───────────────────────────────────────────────
    public static final String STATUS_CREATED      = "CREATED";
    public static final String STATUS_SENT         = "SENT";
    public static final String STATUS_DELIVERED    = "DELIVERED";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_ON_THE_WAY   = "ON_THE_WAY";
    public static final String STATUS_RESOLVED     = "RESOLVED";

    // ── Legacy / special statuses (kept for backward compat) ─────────────────
    public static final String STATUS_RESPONDER_ASSIGNED = "RESPONDER_ASSIGNED";
    public static final String STATUS_FALSE_ALERT  = "FALSE_ALERT";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private int    id;
    private long   timestamp;
    private String triggerType;
    private String status;
    private int    contactsNotified;
    private String locationSnap;        // "lat,lon" or null
    private int    batteryAtTime;       // 0-100 or -1
    private String firebaseId;          // Firestore document ID (null until synced)
    private String locationLabel;       // "Live Location", "Last Known", "Home", etc.
    private boolean silentMode;         // was alert sent in silent mode?
    private boolean pendingFirebaseSync = false;
    private String  responderName = null;    // assigned responder display name
    private String  responderPhone = null;   // assigned responder phone // true until Firestore upload confirms
    private float   locationAccuracyM  = -1f;   // GPS accuracy in metres
    private int     deliveredCount     = 0;      // contacts who received delivery receipt

    public AlertRecord() {}

    public AlertRecord(long timestamp, String triggerType, String status,
                       int contactsNotified, String locationSnap, int batteryAtTime) {
        this.timestamp        = timestamp;
        this.triggerType      = triggerType;
        this.status           = status;
        this.contactsNotified = contactsNotified;
        this.locationSnap     = locationSnap;
        this.batteryAtTime    = batteryAtTime;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public int    getId()                        { return id; }
    public void   setId(int id)                  { this.id = id; }

    public long   getTimestamp()                 { return timestamp; }
    public void   setTimestamp(long t)           { this.timestamp = t; }

    public String getTriggerType()               { return triggerType; }
    public void   setTriggerType(String t)       { this.triggerType = t; }

    public String getStatus()                    { return status; }
    public void   setStatus(String s)            { this.status = s; }

    public int    getContactsNotified()          { return contactsNotified; }
    public void   setContactsNotified(int c)     { this.contactsNotified = c; }

    public String getLocationSnap()              { return locationSnap; }
    public void   setLocationSnap(String l)      { this.locationSnap = l; }

    public int    getBatteryAtTime()             { return batteryAtTime; }
    public void   setBatteryAtTime(int b)        { this.batteryAtTime = b; }

    public String getFirebaseId()                { return firebaseId; }
    public void   setFirebaseId(String id)       { this.firebaseId = id; }

    public String getLocationLabel()             { return locationLabel; }
    public void   setLocationLabel(String l)     { this.locationLabel = l; }

    public boolean isSilentMode()                { return silentMode; }
    public void    setSilentMode(boolean s)      { this.silentMode = s; }

    public String  getResponderName()              { return responderName; }
    public void    setResponderName(String n)      { this.responderName = n; }
    public String  getResponderPhone()             { return responderPhone; }
    public void    setResponderPhone(String p)     { this.responderPhone = p; }

    public boolean isPendingFirebaseSync()             { return pendingFirebaseSync; }
    public void    setPendingFirebaseSync(boolean p)   { this.pendingFirebaseSync = p; }

    public float   getLocationAccuracyM()              { return locationAccuracyM; }
    public void    setLocationAccuracyM(float a)       { this.locationAccuracyM = a; }

    public int     getDeliveredCount()                 { return deliveredCount; }
    public void    setDeliveredCount(int d)            { this.deliveredCount = d; }

    /** Human-readable status for display */
    public String getStatusDisplay() {
        switch (status) {
            case STATUS_CREATED:      return "Created";
            case STATUS_SENT:         return "Sent";
            case STATUS_DELIVERED:    return "Delivered";
            case STATUS_ACKNOWLEDGED:      return "Acknowledged";
            case STATUS_RESPONDER_ASSIGNED: return "Responder Assigned";
            case STATUS_ON_THE_WAY:   return "Help Coming";
            case STATUS_RESOLVED:     return "Resolved";
            case STATUS_FALSE_ALERT:  return "False Alert";
            case STATUS_FAILED:       return "Failed";
            case STATUS_CANCELLED:    return "Cancelled";
            default:                  return status;
        }
    }
}
