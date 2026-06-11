package com.sosapp.disabled;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * EscalationManager — robust escalation chain with per-contact retry.
 *
 * Algorithm:
 *   1. Contact[0] is sent the SOS by MainActivity/SmsDispatcher.
 *   2. After ESCALATION_DELAY, check for acknowledgement.
 *   3. If not acknowledged: retry Contact[0] up to MAX_SAME_CONTACT_RETRIES times.
 *   4. If still not acknowledged after retries: move to Contact[1], repeat.
 *   5. Continue until all contacts exhausted or acknowledged.
 *
 * Duplicate-send guard: tracks sentSet to never SMS the same number twice.
 * Thread safety: all state mutations on main thread via Handler.
 */
public class EscalationManager {

    private static final String TAG = "EscalationManager";

    public static final String KEY_ESCALATION_DELAY = "escalation_delay_s";
    public static final int    DEFAULT_DELAY_S       = 60;

    /** Retries on the SAME contact before moving to next. */
    private static final int MAX_SAME_CONTACT_RETRIES = 1;

    private final Context       context;
    private final Handler       handler = new Handler(Looper.getMainLooper());
    private final DatabaseHelper dbHelper;
    private final FirebaseManager firebase;

    // ── Per-escalation state (reset on startEscalation) ──────────────────────
    private volatile boolean acknowledged = false;
    private volatile boolean active       = false;
    private int     contactIndex          = 0; // which contact we're currently escalating to
    private int     retryCount            = 0; // retries on current contact
    private String  currentMessage;
    private int     localAlertId          = -1;
    private String  firebaseId            = null;
    private final java.util.Set<String> sentNumbers = new java.util.HashSet<>();

    public EscalationManager(Context context) {
        this.context  = context.getApplicationContext();
        this.dbHelper = new DatabaseHelper(context);
        this.firebase = FirebaseManager.getInstance(context);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Arms the escalation chain.
     * Contact[0] already received the initial SOS from MainActivity.
     * We record their number as already sent so we don't double-send.
     */
    public void startEscalation(String message, int localAlertId, String firebaseId,
                                 String firstContactPhone) {
        if (active) stop(); // cancel any in-progress chain before starting new one

        this.currentMessage = message;
        this.localAlertId   = localAlertId;
        this.firebaseId     = firebaseId;
        this.acknowledged   = false;
        this.active         = true;
        this.contactIndex   = 0;
        this.retryCount     = 0;
        this.sentNumbers.clear();

        if (firstContactPhone != null) sentNumbers.add(normalise(firstContactPhone));

        List<Contact> contacts = dbHelper.getAllContacts();
        if (contacts.size() <= 1) {
            Log.d(TAG, "Single contact — escalation not needed");
            active = false;
            return;
        }

        int delaySecs = getEscalationDelay();
        Log.d(TAG, "Escalation armed: " + contacts.size() + " contacts, delay=" + delaySecs + "s");
        scheduleEscalationCheck(delaySecs * 1000L);
    }

    /** Backward-compat overload (no firstContactPhone). */
    public void startEscalation(String message, int localAlertId, String firebaseId) {
        startEscalation(message, localAlertId, firebaseId, null);
    }

    /** Stops all escalation immediately — call on acknowledge or activity destroy. */
    public void stop() {
        active = false;
        handler.removeCallbacksAndMessages(null);
    }

    /** Updates the Firebase ID associated with this escalation chain (used if synced post-send). */
    public void setFirebaseId(String firebaseId) {
        this.firebaseId = firebaseId;
    }

    /**
     * Marks the alert as acknowledged. Stops escalation and updates both
     * the local DB and Firebase.
     */
    public void acknowledge(String responderNumber) {
        if (acknowledged) return; // idempotent
        acknowledged = true;
        stop();

        if (localAlertId >= 0) {
            dbHelper.updateAlertStatus(localAlertId,
                    AlertRecord.STATUS_ACKNOWLEDGED, firebaseId);
        }
        if (firebaseId != null) {
            firebase.markAlertAcknowledged(firebaseId, responderNumber);
        }
        Log.d(TAG, "Alert acknowledged by: " + responderNumber);
    }

    public boolean isActive()       { return active; }
    public boolean isAcknowledged() { return acknowledged; }

    // ══════════════════════════════════════════════════════════════════════════
    // Internal escalation logic
    // ══════════════════════════════════════════════════════════════════════════

    private void scheduleEscalationCheck(long delayMs) {
        handler.postDelayed(this::runEscalationStep, delayMs);
    }

    private void runEscalationStep() {
        if (!active || acknowledged) {
            Log.d(TAG, "Escalation halted — active=" + active + " ack=" + acknowledged);
            active = false;
            return;
        }

        List<Contact> contacts = dbHelper.getAllContacts();
        if (contacts.isEmpty()) { active = false; return; }

        // Find the current target contact (skip index 0 — they already got the first SOS)
        int targetIndex = contactIndex + 1; // contactIndex 0 = first escalation target = contacts[1]
        if (targetIndex >= contacts.size()) {
            Log.d(TAG, "All contacts in chain exhausted.");
            active = false;
            return;
        }

        Contact target = contacts.get(targetIndex);
        String normPhone = normalise(target.getPhone());

        // Retry same contact before moving on
        if (sentNumbers.contains(normPhone)) {
            if (retryCount < MAX_SAME_CONTACT_RETRIES) {
                retryCount++;
                Log.d(TAG, "Retrying " + target.getName() + " (retry " + retryCount + ")");
                sendEscalationSms(target, /* isRetry= */ true);
                scheduleEscalationCheck(getEscalationDelay() * 1000L);
            } else {
                // Move to next contact
                retryCount = 0;
                contactIndex++;
                runEscalationStep(); // immediate — find next valid contact
            }
            return;
        }

        // First time reaching this contact
        sentNumbers.add(normPhone);
        sendEscalationSms(target, /* isRetry= */ false);
        Log.d(TAG, "Escalated to contact[" + targetIndex + "]: " + target.getName());

        // Update Firebase with ongoing-sent status
        if (firebaseId != null) {
            firebase.updateAlertStatus(firebaseId, AlertRecord.STATUS_SENT);
        }

        // Schedule next check
        if (targetIndex < contacts.size() - 1) {
            scheduleEscalationCheck(getEscalationDelay() * 1000L);
        } else {
            Log.d(TAG, "Last contact reached. Chain complete.");
            active = false;
        }
    }

    private void sendEscalationSms(Contact contact, boolean isRetry) {
        String prefix = isRetry
                ? "🚨 REMINDER — SOS still active. No response received yet.\n"
                : "🚨 ESCALATION — Previous contact has not responded.\n";

        String msg = prefix + currentMessage;

        try {
            SmsManager sms = getSmsManager();
            if (sms == null) return;
            ArrayList<String> parts = sms.divideMessage(msg);
            sms.sendMultipartTextMessage(contact.getPhone(), null, parts, null, null);
            Log.d(TAG, "Escalation SMS sent to " + contact.getName()
                    + " [retry=" + isRetry + "]");
        } catch (Exception e) {
            Log.e(TAG, "Escalation SMS failed to " + contact.getName(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Normalise phone number for dedup: strip spaces, dashes, leading zeros. */
    private String normalise(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[\\s\\-()]", "").replaceFirst("^0+", "");
    }

    private int getEscalationDelay() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ESCALATION_DELAY, DEFAULT_DELAY_S);
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }
}
