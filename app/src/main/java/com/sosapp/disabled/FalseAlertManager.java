package com.sosapp.disabled;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * FalseAlertManager — three responsibilities:
 *
 * 1. FALSE ALERT MARKING
 *    When a user or responder marks an alert as false, update both
 *    SQLite and Firestore with STATUS_FALSE_ALERT. Notify via TTS.
 *
 * 2. DUPLICATE SUPPRESSION
 *    If a new SOS is triggered within DUPLICATE_WINDOW_MS of the last one,
 *    and the last alert is still active, suppress it (return false from
 *    shouldAllow()). This prevents rapid accidental re-triggers.
 *
 * 3. CANCELLATION WINDOW
 *    The existing 3-10s countdown already provides a cancel window.
 *    This class additionally tracks the "post-send" window (10s after SMS
 *    is dispatched) during which the user can still self-cancel an alert
 *    without it being counted as a confirmed emergency.
 */
public class FalseAlertManager {

    private static final String TAG                = "FalseAlertManager";
    private static final long   DUPLICATE_WINDOW_MS = 30_000L; // 30 seconds
    private static final long   POST_SEND_CANCEL_MS = 10_000L; // 10s post-send cancel window

    private static FalseAlertManager instance;

    private final Context         context;
    private final DatabaseHelper  dbHelper;
    private final FirebaseManager firebase;
    private final TtsManager      tts;
    private final Handler         handler = new Handler(Looper.getMainLooper());

    private long   lastAlertTime   = 0L;
    private int    lastLocalId     = -1;
    private String lastFirebaseId  = null;
    private boolean postSendWindowOpen = false;
    private Runnable postSendWindowCloser;

    // Track alerts reported as false by responders (docId → true)
    private final Map<String, Boolean> falseAlertSet = new HashMap<>();

    private FalseAlertManager(Context context) {
        this.context  = context.getApplicationContext();
        this.dbHelper = new DatabaseHelper(context);
        this.firebase = FirebaseManager.getInstance(context);
        this.tts      = TtsManager.getInstance(context);
    }

    public static synchronized FalseAlertManager getInstance(Context context) {
        if (instance == null) instance = new FalseAlertManager(context.getApplicationContext());
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Duplicate suppression
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if this SOS should proceed, false if it is a duplicate.
     * Call BEFORE starting the countdown.
     */
    public boolean shouldAllow() {
        long now = System.currentTimeMillis();
        if (lastAlertTime > 0 && (now - lastAlertTime) < DUPLICATE_WINDOW_MS) {
            Log.w(TAG, "Duplicate SOS suppressed — " + (now - lastAlertTime) + "ms since last");
            return false;
        }
        return true;
    }

    /** Must be called immediately when an alert is confirmed sent. */
    public void onAlertSent(int localId, String firebaseId) {
        lastAlertTime  = System.currentTimeMillis();
        lastLocalId    = localId;
        lastFirebaseId = firebaseId;
        openPostSendWindow();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Post-send cancellation window
    // ══════════════════════════════════════════════════════════════════════════

    private void openPostSendWindow() {
        postSendWindowOpen = true;
        if (postSendWindowCloser != null) handler.removeCallbacks(postSendWindowCloser);
        postSendWindowCloser = () -> {
            postSendWindowOpen = false;
            Log.d(TAG, "Post-send cancel window closed");
        };
        handler.postDelayed(postSendWindowCloser, POST_SEND_CANCEL_MS);
        Log.d(TAG, "Post-send cancel window open for " + (POST_SEND_CANCEL_MS/1000) + "s");
    }

    public boolean isPostSendWindowOpen() { return postSendWindowOpen; }

    /**
     * User self-cancels within POST_SEND_CANCEL_MS of sending.
     * Marks the alert as FALSE_ALERT in DB and Firebase.
     */
    public void selfCancelAfterSend() {
        if (!postSendWindowOpen) return;
        postSendWindowOpen = false;
        if (postSendWindowCloser != null) handler.removeCallbacks(postSendWindowCloser);
        markFalseAlert(lastLocalId, lastFirebaseId, "Self-cancelled");
        tts.speakNow("Alert cancelled. Marking as false alarm.");
        Log.d(TAG, "Self-cancelled post-send");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. False alert marking (by user or responder)
    // ══════════════════════════════════════════════════════════════════════════

    public void markFalseAlert(int localId, String firebaseId, String markedBy) {
        if (localId >= 0) {
            dbHelper.updateAlertStatus(localId, AlertRecord.STATUS_FALSE_ALERT, firebaseId);
        }
        if (firebaseId != null && !firebaseId.isEmpty()) {
            firebase.updateAlertStatus(firebaseId, AlertRecord.STATUS_FALSE_ALERT);
            falseAlertSet.put(firebaseId, true);
        }
        Log.d(TAG, "Alert marked as false by: " + markedBy);
    }

    public boolean isFalseAlert(String firebaseId) {
        return firebaseId != null && falseAlertSet.containsKey(firebaseId);
    }

    /** Resets the duplicate-suppression timer (e.g. after confirmed resolution). */
    public void resetTimer() {
        lastAlertTime = 0L;
    }
}
