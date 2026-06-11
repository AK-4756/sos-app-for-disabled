package com.sosapp.disabled;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite helper — version 3.
 *
 * Tables:
 *   contacts      — emergency contacts
 *   alert_history — SOS event log (v2 adds table; v3 adds firebase_id,
 *                   location_label, silent_mode columns via ALTER TABLE)
 *
 * Migrations are always additive — no data is ever lost on upgrade.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG             = "DatabaseHelper";
    private static final String DATABASE_NAME   = "SOSContacts.db";
    private static final int    DATABASE_VERSION = 4;

    // ─── contacts table ───────────────────────────────────────────────────────
    private static final String TABLE_CONTACTS = "contacts";
    private static final String COL_ID         = "id";
    private static final String COL_NAME       = "name";
    private static final String COL_PHONE      = "phone";

    private static final String CREATE_CONTACTS =
            "CREATE TABLE " + TABLE_CONTACTS + " (" +
                    COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_NAME  + " TEXT NOT NULL, " +
                    COL_PHONE + " TEXT NOT NULL)";

    // ─── alert_history table ─────────────────────────────────────────────────
    static final String TABLE_HISTORY     = "alert_history";
    static final String HIST_ID           = "id";
    static final String HIST_TIMESTAMP    = "timestamp";
    static final String HIST_TRIGGER      = "trigger_type";
    static final String HIST_STATUS       = "status";
    static final String HIST_CONTACTS     = "contacts_notified";
    static final String HIST_LOCATION     = "location_snap";
    static final String HIST_BATTERY      = "battery_at_time";
    // v3 additions
    static final String HIST_FIREBASE_ID  = "firebase_id";
    static final String HIST_LOC_LABEL    = "location_label";
    static final String HIST_SILENT       = "silent_mode";
    // v4 additions
    static final String HIST_PENDING_SYNC = "pending_firebase_sync"; // 1 = needs upload
    static final String HIST_LOC_ACCURACY = "location_accuracy_m";   // metres, -1 if unknown
    static final String HIST_DELIVERED    = "delivered_count";        // contacts confirmed delivered

    private static final String CREATE_HISTORY =
            "CREATE TABLE " + TABLE_HISTORY + " (" +
                    HIST_ID          + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    HIST_TIMESTAMP   + " INTEGER NOT NULL, " +
                    HIST_TRIGGER     + " TEXT, " +
                    HIST_STATUS      + " TEXT, " +
                    HIST_CONTACTS    + " INTEGER DEFAULT 0, " +
                    HIST_LOCATION    + " TEXT, " +
                    HIST_BATTERY     + " INTEGER DEFAULT -1, " +
                    HIST_FIREBASE_ID + " TEXT, " +
                    HIST_LOC_LABEL   + " TEXT, " +
                    HIST_SILENT      + " INTEGER DEFAULT 0, " +
                    HIST_PENDING_SYNC + " INTEGER DEFAULT 0, " +
                    HIST_LOC_ACCURACY + " REAL DEFAULT -1, " +
                    HIST_DELIVERED    + " INTEGER DEFAULT 0)";

    private static final int MAX_HISTORY_ROWS = 100;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_CONTACTS);
        db.execSQL(CREATE_HISTORY);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_HISTORY);
        }
        if (oldVersion < 3) {
            // Safely add v3 columns — each ALTER TABLE is isolated
            safeAddColumn(db, TABLE_HISTORY, HIST_FIREBASE_ID, "TEXT");
            safeAddColumn(db, TABLE_HISTORY, HIST_LOC_LABEL,   "TEXT");
            safeAddColumn(db, TABLE_HISTORY, HIST_SILENT,       "INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            safeAddColumn(db, TABLE_HISTORY, HIST_PENDING_SYNC, "INTEGER DEFAULT 0");
            safeAddColumn(db, TABLE_HISTORY, HIST_LOC_ACCURACY, "REAL DEFAULT -1");
            safeAddColumn(db, TABLE_HISTORY, HIST_DELIVERED,    "INTEGER DEFAULT 0");
        }
    }

    private void safeAddColumn(SQLiteDatabase db, String table, String col, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        } catch (Exception e) {
            Log.d(TAG, "Column already exists or error: " + col + " — " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Contacts CRUD
    // ══════════════════════════════════════════════════════════════════════════

    public long addContact(Contact contact) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME,  contact.getName());
        cv.put(COL_PHONE, contact.getPhone());
        long id = db.insert(TABLE_CONTACTS, null, cv);
        db.close();
        return id;
    }

    public int updateContact(Contact contact) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME,  contact.getName());
        cv.put(COL_PHONE, contact.getPhone());
        int rows = db.update(TABLE_CONTACTS, cv,
                COL_ID + "=?", new String[]{String.valueOf(contact.getId())});
        db.close();
        return rows;
    }

    public void deleteContact(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_CONTACTS, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public List<Contact> getAllContacts() {
        List<Contact> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_CONTACTS, null, null, null, null, null, COL_NAME + " ASC");
        if (c != null && c.moveToFirst()) {
            do {
                Contact contact = new Contact();
                contact.setId(c.getInt(c.getColumnIndexOrThrow(COL_ID)));
                contact.setName(c.getString(c.getColumnIndexOrThrow(COL_NAME)));
                contact.setPhone(c.getString(c.getColumnIndexOrThrow(COL_PHONE)));
                list.add(contact);
            } while (c.moveToNext());
            c.close();
        }
        db.close();
        return list;
    }

    public Contact getContactById(int id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_CONTACTS, null, COL_ID + "=?",
                new String[]{String.valueOf(id)}, null, null, null);
        Contact contact = null;
        if (c != null && c.moveToFirst()) {
            contact = new Contact();
            contact.setId(c.getInt(c.getColumnIndexOrThrow(COL_ID)));
            contact.setName(c.getString(c.getColumnIndexOrThrow(COL_NAME)));
            contact.setPhone(c.getString(c.getColumnIndexOrThrow(COL_PHONE)));
            c.close();
        }
        db.close();
        return contact;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Alert History CRUD
    // ══════════════════════════════════════════════════════════════════════════

    public long insertAlertRecord(AlertRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = buildContentValues(record);
        long newId = db.insert(TABLE_HISTORY, null, cv);

        // Trim oldest rows
        db.execSQL("DELETE FROM " + TABLE_HISTORY +
                " WHERE " + HIST_ID + " NOT IN (" +
                "  SELECT " + HIST_ID + " FROM " + TABLE_HISTORY +
                "  ORDER BY " + HIST_TIMESTAMP + " DESC LIMIT " + MAX_HISTORY_ROWS + ")");
        db.close();
        return newId;
    }

    /** Updates the status and firebaseId of an existing record (used by escalation + Firestore sync). */
    public void updateAlertStatus(int localId, String newStatus, String firebaseId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(HIST_STATUS, newStatus);
        if (firebaseId != null) cv.put(HIST_FIREBASE_ID, firebaseId);
        db.update(TABLE_HISTORY, cv, HIST_ID + "=?", new String[]{String.valueOf(localId)});
        db.close();
    }

    public List<AlertRecord> getAllAlertHistory() {
        List<AlertRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_HISTORY, null, null, null, null, null, HIST_TIMESTAMP + " DESC");
        if (c != null && c.moveToFirst()) {
            do { list.add(cursorToRecord(c)); } while (c.moveToNext());
            c.close();
        }
        db.close();
        return list;
    }

    /** Returns the most recent non-cancelled, non-failed alert record (for escalation). */
    public AlertRecord getLatestActiveRecord() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_HISTORY, null,
                HIST_STATUS + " NOT IN (?,?,?)",
                new String[]{AlertRecord.STATUS_CANCELLED, AlertRecord.STATUS_FAILED, AlertRecord.STATUS_RESOLVED},
                null, null, HIST_TIMESTAMP + " DESC", "1");
        AlertRecord rec = null;
        if (c != null && c.moveToFirst()) {
            rec = cursorToRecord(c);
            c.close();
        }
        db.close();
        return rec;
    }

    /** Returns all records flagged as needing Firebase upload. */
    public List<AlertRecord> getPendingSyncRecords() {
        List<AlertRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_HISTORY, null,
                HIST_PENDING_SYNC + "=1", null, null, null,
                HIST_TIMESTAMP + " ASC");
        if (c != null && c.moveToFirst()) {
            do { list.add(cursorToRecord(c)); } while (c.moveToNext());
            c.close();
        }
        db.close();
        return list;
    }

    /** Clears the pending-sync flag after successful Firebase upload. */
    public void markFirebaseSynced(int localId, String firebaseId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(HIST_PENDING_SYNC, 0);
        if (firebaseId != null) cv.put(HIST_FIREBASE_ID, firebaseId);
        db.update(TABLE_HISTORY, cv, HIST_ID + "=?", new String[]{String.valueOf(localId)});
        db.close();
    }

    /** Increments the delivered count for an alert (called by SmsDispatcher). */
    public void incrementDeliveredCount(int localId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_HISTORY +
                " SET " + HIST_DELIVERED + " = " + HIST_DELIVERED + " + 1" +
                " WHERE " + HIST_ID + " = " + localId);
        db.close();
    }

    public void clearAlertHistory() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_HISTORY, null, null);
        db.close();
    }

    private AlertRecord cursorToRecord(Cursor c) {
        AlertRecord r = new AlertRecord();
        r.setId(c.getInt(c.getColumnIndexOrThrow(HIST_ID)));
        r.setTimestamp(c.getLong(c.getColumnIndexOrThrow(HIST_TIMESTAMP)));
        r.setTriggerType(c.getString(c.getColumnIndexOrThrow(HIST_TRIGGER)));
        r.setStatus(c.getString(c.getColumnIndexOrThrow(HIST_STATUS)));
        r.setContactsNotified(c.getInt(c.getColumnIndexOrThrow(HIST_CONTACTS)));
        r.setLocationSnap(c.getString(c.getColumnIndexOrThrow(HIST_LOCATION)));
        r.setBatteryAtTime(c.getInt(c.getColumnIndexOrThrow(HIST_BATTERY)));
        // v3 columns — may be null on old rows
        int fbIdx = c.getColumnIndex(HIST_FIREBASE_ID);
        if (fbIdx >= 0) r.setFirebaseId(c.getString(fbIdx));
        int llIdx = c.getColumnIndex(HIST_LOC_LABEL);
        if (llIdx >= 0) r.setLocationLabel(c.getString(llIdx));
        int silIdx = c.getColumnIndex(HIST_SILENT);
        if (silIdx >= 0) r.setSilentMode(c.getInt(silIdx) == 1);
        int psIdx = c.getColumnIndex(HIST_PENDING_SYNC);
        if (psIdx >= 0) r.setPendingFirebaseSync(c.getInt(psIdx) == 1);
        int accIdx = c.getColumnIndex(HIST_LOC_ACCURACY);
        if (accIdx >= 0) r.setLocationAccuracyM(c.getFloat(accIdx));
        int delIdx = c.getColumnIndex(HIST_DELIVERED);
        if (delIdx >= 0) r.setDeliveredCount(c.getInt(delIdx));
        return r;
    }

    private ContentValues buildContentValues(AlertRecord r) {
        ContentValues cv = new ContentValues();
        cv.put(HIST_TIMESTAMP,  r.getTimestamp());
        cv.put(HIST_TRIGGER,    r.getTriggerType());
        cv.put(HIST_STATUS,     r.getStatus());
        cv.put(HIST_CONTACTS,   r.getContactsNotified());
        cv.put(HIST_LOCATION,   r.getLocationSnap());
        cv.put(HIST_BATTERY,    r.getBatteryAtTime());
        cv.put(HIST_FIREBASE_ID, r.getFirebaseId());
        cv.put(HIST_LOC_LABEL,  r.getLocationLabel());
        cv.put(HIST_SILENT,      r.isSilentMode() ? 1 : 0);
        cv.put(HIST_PENDING_SYNC, r.isPendingFirebaseSync() ? 1 : 0);
        cv.put(HIST_LOC_ACCURACY, r.getLocationAccuracyM());
        cv.put(HIST_DELIVERED,    r.getDeliveredCount());
        return cv;
    }
}
