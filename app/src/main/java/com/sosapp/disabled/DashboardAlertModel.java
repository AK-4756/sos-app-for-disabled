package com.sosapp.disabled;

/**
 * DashboardAlertModel — flattened view of a Firestore alert document
 * used by DashboardAdapter to populate RecyclerView rows.
 *
 * Field names mirror exactly what FirebaseManager writes so
 * Firestore.toObject() (or manual mapping) works without transformation.
 */
public class DashboardAlertModel {

    private String  documentId;      // Firestore doc ID
    private String  userId;
    private String  userName;        // fetched from users/{userId}
    private String  triggerType;
    private String  status;
    private double  latitude;
    private double  longitude;
    private String  locationLabel;
    private float   locationAccuracyM;
    private long    timestamp;
    private int     battery;
    private boolean silentMode;
    private int     contactsNotified;
    private int     contactsDelivered;
    private String  responderName;
    private String  acknowledgedBy;
    private long    acknowledgedAt;

    public DashboardAlertModel() {}

    // ── Convenience ──────────────────────────────────────────────────────────

    public boolean isActive() {
        return status != null && !status.equals(AlertRecord.STATUS_RESOLVED)
                && !status.equals(AlertRecord.STATUS_CANCELLED)
                && !status.equals(AlertRecord.STATUS_FAILED)
                && !status.equals(AlertRecord.STATUS_FALSE_ALERT);
    }

    public boolean hasLocation() {
        return latitude != 0 || longitude != 0;
    }

    public String getMapUrl() {
        if (!hasLocation()) return null;
        return "https://maps.google.com/?q=" + latitude + "," + longitude;
    }

    public String getLocationDisplay() {
        String label = locationLabel != null ? locationLabel : "Location";
        if (locationAccuracyM > 0 && locationAccuracyM < 5000) {
            return label + " (±" + Math.round(locationAccuracyM) + "m)";
        }
        return label;
    }

    public String getStatusDisplay() {
        if (status == null) return "Unknown";
        switch (status) {
            case AlertRecord.STATUS_CREATED:             return "Created";
            case AlertRecord.STATUS_SENT:                return "Sent";
            case AlertRecord.STATUS_DELIVERED:           return "Delivered";
            case AlertRecord.STATUS_ACKNOWLEDGED:        return "Acknowledged";
            case AlertRecord.STATUS_RESPONDER_ASSIGNED:  return "Responder Assigned";
            case AlertRecord.STATUS_ON_THE_WAY:          return "Help On the Way";
            case AlertRecord.STATUS_RESOLVED:            return "Resolved";
            case AlertRecord.STATUS_FALSE_ALERT:         return "False Alert";
            case AlertRecord.STATUS_FAILED:              return "Failed";
            case AlertRecord.STATUS_CANCELLED:           return "Cancelled";
            default:                                     return status;
        }
    }

    /** Colour for the status badge (ARGB int). */
    public int getStatusColor() {
        if (status == null) return 0xFF9E9E9E;
        switch (status) {
            case AlertRecord.STATUS_CREATED:
            case AlertRecord.STATUS_SENT:             return 0xFFE53935; // red — urgent
            case AlertRecord.STATUS_DELIVERED:        return 0xFFFB8C00; // orange
            case AlertRecord.STATUS_ACKNOWLEDGED:     return 0xFF1E88E5; // blue
            case AlertRecord.STATUS_RESPONDER_ASSIGNED:
            case AlertRecord.STATUS_ON_THE_WAY:       return 0xFF00ACC1; // teal
            case AlertRecord.STATUS_RESOLVED:         return 0xFF43A047; // green
            case AlertRecord.STATUS_FALSE_ALERT:      return 0xFF757575; // grey
            default:                                  return 0xFF9E9E9E;
        }
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String  getDocumentId()         { return documentId; }
    public void    setDocumentId(String v) { documentId = v; }

    public String  getUserId()             { return userId; }
    public void    setUserId(String v)     { userId = v; }

    public String  getUserName()           { return userName != null ? userName : "Unknown User"; }
    public void    setUserName(String v)   { userName = v; }

    public String  getTriggerType()        { return triggerType != null ? triggerType : "—"; }
    public void    setTriggerType(String v){ triggerType = v; }

    public String  getStatus()             { return status != null ? status : AlertRecord.STATUS_CREATED; }
    public void    setStatus(String v)     { status = v; }

    public double  getLatitude()           { return latitude; }
    public void    setLatitude(double v)   { latitude = v; }

    public double  getLongitude()          { return longitude; }
    public void    setLongitude(double v)  { longitude = v; }

    public String  getLocationLabel()      { return locationLabel; }
    public void    setLocationLabel(String v) { locationLabel = v; }

    public float   getLocationAccuracyM()  { return locationAccuracyM; }
    public void    setLocationAccuracyM(float v) { locationAccuracyM = v; }

    public long    getTimestamp()          { return timestamp; }
    public void    setTimestamp(long v)    { timestamp = v; }

    public int     getBattery()            { return battery; }
    public void    setBattery(int v)       { battery = v; }

    public boolean isSilentMode()          { return silentMode; }
    public void    setSilentMode(boolean v){ silentMode = v; }

    public int     getContactsNotified()   { return contactsNotified; }
    public void    setContactsNotified(int v) { contactsNotified = v; }

    public int     getContactsDelivered()  { return contactsDelivered; }
    public void    setContactsDelivered(int v) { contactsDelivered = v; }

    public String  getResponderName()      { return responderName; }
    public void    setResponderName(String v) { responderName = v; }

    public String  getAcknowledgedBy()     { return acknowledgedBy; }
    public void    setAcknowledgedBy(String v) { acknowledgedBy = v; }

    public long    getAcknowledgedAt()     { return acknowledgedAt; }
    public void    setAcknowledgedAt(long v) { acknowledgedAt = v; }
}
