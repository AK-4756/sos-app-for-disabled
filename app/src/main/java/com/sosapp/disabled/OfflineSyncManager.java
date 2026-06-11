package com.sosapp.disabled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * OfflineSyncManager — watches network state and retries any alert records
 * that failed to reach Firebase (flagged as pending_firebase_sync=1).
 *
 * Strategy:
 *   - On network regained → wait 2 s for stability → flush pending queue.
 *   - On each retry, update Firebase and clear the local flag on success.
 *   - Deduplication: firebaseId is stored in SQLite; if it already has one
 *     we update (not insert) the Firestore document.
 *   - Uses ConnectivityManager.NetworkCallback on API 21+ (no deprecated
 *     CONNECTIVITY_CHANGE broadcast needed on modern Android).
 *
 * Lifecycle: call register() in onResume, unregister() in onPause.
 */
public class OfflineSyncManager {

    private static final String TAG           = "OfflineSyncManager";
    private static final long   RETRY_DELAY   = 2_000L;  // wait after network up
    private static final int    MAX_BATCH      = 20;      // max records per flush

    private final Context         context;
    private final DatabaseHelper  dbHelper;
    private final FirebaseManager firebase;
    private final Handler         handler = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean registered = false;

    public OfflineSyncManager(Context context) {
        this.context  = context.getApplicationContext();
        this.dbHelper = new DatabaseHelper(context);
        this.firebase = FirebaseManager.getInstance(context);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    public void register() {
        if (registered) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.d(TAG, "Network available — scheduling sync flush");
                    handler.postDelayed(OfflineSyncManager.this::flushPendingQueue, RETRY_DELAY);
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, networkCallback);
        }
        registered = true;

        // Also try immediately in case we already have internet
        if (isConnected()) {
            handler.postDelayed(this::flushPendingQueue, 500);
        }
    }

    public void unregister() {
        if (!registered) return;
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && networkCallback != null) {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(networkCallback); }
                catch (Exception ignored) {}
            }
        }
        registered = false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flush pending queue
    // ══════════════════════════════════════════════════════════════════════════

    public void flushPendingQueue() {
        if (!firebase.isAvailable() || !isConnected()) return;

        List<AlertRecord> pending = dbHelper.getPendingSyncRecords();
        if (pending.isEmpty()) return;

        Log.d(TAG, "Flushing " + Math.min(pending.size(), MAX_BATCH) + " pending Firebase records");

        int count = 0;
        for (AlertRecord record : pending) {
            if (count++ >= MAX_BATCH) break;
            syncRecord(record);
        }
    }

    private void syncRecord(AlertRecord record) {
        if (record.getFirebaseId() != null && !record.getFirebaseId().isEmpty()) {
            // Already has a Firestore doc — just update status
            firebase.updateAlertStatus(record.getFirebaseId(), record.getStatus());
            dbHelper.markFirebaseSynced(record.getId(), record.getFirebaseId());
            Log.d(TAG, "Re-synced existing doc: " + record.getFirebaseId());
        } else {
            // No Firestore doc yet — create it
            firebase.createAlert(record, new FirebaseManager.OnAlertCreated() {
                @Override
                public void onCreated(String firebaseDocId) {
                    dbHelper.markFirebaseSynced(record.getId(), firebaseDocId);
                    Log.d(TAG, "Offline record synced to Firebase: " + firebaseDocId);
                }

                @Override
                public void onFailed(Exception e) {
                    Log.w(TAG, "Retry sync failed for record " + record.getId(), e);
                    // Will try again next time network comes up
                }
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }
}
