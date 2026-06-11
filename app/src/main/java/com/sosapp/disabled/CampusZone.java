package com.sosapp.disabled;

/**
 * CampusZone — represents a named area within a campus or institution.
 *
 * Zones can be:
 *   - PREDEFINED: shipped with the app (Classroom, Lab, Hostel, etc.)
 *   - CUSTOM: created by the user by saving their current GPS position
 *
 * A zone has a GPS anchor point + a radius so the app can auto-detect
 * when a user is inside it (geofencing-lite, computed locally without
 * Google Geofencing API so it works offline).
 */
public class CampusZone {

    // ── Predefined zone types ─────────────────────────────────────────────────
    public static final String TYPE_CLASSROOM = "Classroom";
    public static final String TYPE_LAB       = "Lab";
    public static final String TYPE_HOSTEL    = "Hostel";
    public static final String TYPE_LIBRARY   = "Library";
    public static final String TYPE_CANTEEN   = "Canteen";
    public static final String TYPE_GATE      = "Gate";
    public static final String TYPE_PARKING   = "Parking";
    public static final String TYPE_MEDICAL   = "Medical Centre";
    public static final String TYPE_AUDITORIUM = "Auditorium";
    public static final String TYPE_SPORTS    = "Sports Ground";
    public static final String TYPE_CUSTOM    = "Custom";

    // ── Default zone radius (metres) ─────────────────────────────────────────
    public static final float DEFAULT_RADIUS_M = 50f;

    private String  id;          // UUID
    private String  name;        // display name, e.g. "Block A Lab"
    private String  type;        // one of the TYPE_ constants
    private double  latitude;
    private double  longitude;
    private float   radiusMetres;
    private boolean predefined;  // true = ships with app, false = user-created
    private String  building;    // optional building name
    private String  floor;       // optional floor/level
    private String  roomNumber;  // optional room

    public CampusZone() { this.radiusMetres = DEFAULT_RADIUS_M; }

    public CampusZone(String id, String name, String type, double lat, double lng) {
        this.id           = id;
        this.name         = name;
        this.type         = type;
        this.latitude     = lat;
        this.longitude    = lng;
        this.radiusMetres = DEFAULT_RADIUS_M;
    }

    // ── Location helpers ──────────────────────────────────────────────────────

    /** Returns true if the given point is within this zone's radius. */
    public boolean contains(double lat, double lng) {
        return distanceTo(lat, lng) <= radiusMetres;
    }

    /** Haversine distance in metres between zone centre and given point. */
    public float distanceTo(double lat, double lng) {
        double R  = 6_371_000; // Earth radius in metres
        double dLat = Math.toRadians(lat - latitude);
        double dLng = Math.toRadians(lng - longitude);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(latitude))
                * Math.cos(Math.toRadians(lat))
                * Math.sin(dLng/2) * Math.sin(dLng/2);
        return (float)(2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1-a)));
    }

    /** Human-readable location string for SOS messages. */
    public String toLocationString() {
        StringBuilder sb = new StringBuilder(name);
        if (building != null && !building.isEmpty()) sb.append(", ").append(building);
        if (floor != null && !floor.isEmpty()) sb.append(", Floor ").append(floor);
        if (roomNumber != null && !roomNumber.isEmpty()) sb.append(", Room ").append(roomNumber);
        return sb.toString();
    }

    public String toMapUrl() {
        return "https://maps.google.com/?q=" + latitude + "," + longitude;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String  getId()                   { return id; }
    public void    setId(String v)           { id = v; }

    public String  getName()                 { return name != null ? name : ""; }
    public void    setName(String v)         { name = v; }

    public String  getType()                 { return type != null ? type : TYPE_CUSTOM; }
    public void    setType(String v)         { type = v; }

    public double  getLatitude()             { return latitude; }
    public void    setLatitude(double v)     { latitude = v; }

    public double  getLongitude()            { return longitude; }
    public void    setLongitude(double v)    { longitude = v; }

    public float   getRadiusMetres()         { return radiusMetres; }
    public void    setRadiusMetres(float v)  { radiusMetres = v; }

    public boolean isPredefined()            { return predefined; }
    public void    setPredefined(boolean v)  { predefined = v; }

    public String  getBuilding()             { return building != null ? building : ""; }
    public void    setBuilding(String v)     { building = v; }

    public String  getFloor()               { return floor != null ? floor : ""; }
    public void    setFloor(String v)        { floor = v; }

    public String  getRoomNumber()           { return roomNumber != null ? roomNumber : ""; }
    public void    setRoomNumber(String v)   { roomNumber = v; }

    @Override
    public String toString() { return name + " (" + type + ")"; }
}
