package com.sosapp.disabled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * WearableTriggerManager — architecture bridge for smartwatch SOS integration.
 *
 * This class provides the integration layer between a future WearOS companion
 * app and the existing PULSE SOS system. No WearOS SDK dependency is added
 * here; the bridge uses explicit local Broadcasts so a companion app or
 * Bluetooth proxy can trigger SOS without coupling to the phone app's internals.
 *
 * Integration points:
 *
 *   INBOUND (watch → phone):
 *     A WearOS companion sends:
 *       Intent action: com.sosapp.disabled.WEARABLE_SOS
 *       Extra: "trigger_type" (String) — e.g. "WearableButton", "WearableFall"
 *     WearableTriggerManager receives it and calls the registered SOSTriggerListener.
 *
 *   OUTBOUND (phone → watch):
 *     On SOS sent, phone broadcasts:
 *       Intent action: com.sosapp.disabled.SOS_SENT_TO_WATCH
 *       Extras: status (String), timestamp (long), contactCount (int)
 *     A WearOS companion app listens for this to vibrate/display the status.
 *
 *   ALERT STATUS (Firestore → watch):
 *     When alert status changes (ACKNOWLEDGED, ON_THE_WAY), phone broadcasts:
 *       Intent action: com.sosapp.disabled.ALERT_STATUS_UPDATE
 *       Extra: "status" (String)
 *     Companion app shows a watch face notification.
 *
 * To build the companion WearOS app, implement:
 *   1. A WearOS Tile or complication that sends WEARABLE_SOS on button press.
 *   2. A BroadcastReceiver listening for SOS_SENT_TO_WATCH and ALERT_STATUS_UPDATE.
 *   3. Pair via same package name or MessageClient (Wearable.getMessageClient).
 *
 * Registration: call register() in MainActivity.onResume(), unregister() in onPause().
 */
public class WearableTriggerManager {

    private static final String TAG = "WearableTrigger";

    // ── Broadcast actions ─────────────────────────────────────────────────────
    public static final String ACTION_WEARABLE_SOS      = "com.sosapp.disabled.WEARABLE_SOS";
    public static final String ACTION_SOS_SENT_TO_WATCH = "com.sosapp.disabled.SOS_SENT_TO_WATCH";
    public static final String ACTION_STATUS_UPDATE     = "com.sosapp.disabled.ALERT_STATUS_UPDATE";

    public static final String EXTRA_TRIGGER_TYPE  = "trigger_type";
    public static final String EXTRA_STATUS        = "status";
    public static final String EXTRA_TIMESTAMP     = "timestamp";
    public static final String EXTRA_CONTACT_COUNT = "contactCount";

    public interface SOSTriggerListener {
        void onWearableSosTrigger(String triggerType);
    }

    private final Context            context;
    private SOSTriggerListener       listener;
    private BroadcastReceiver        wearableReceiver;
    private boolean                  registered = false;

    public WearableTriggerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setSOSTriggerListener(SOSTriggerListener l) { this.listener = l; }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    public void register() {
        if (registered) return;

        wearableReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_WEARABLE_SOS.equals(intent.getAction())) return;
                String triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE);
                if (triggerType == null) triggerType = "WearableButton";
                Log.d(TAG, "Wearable SOS received: " + triggerType);
                if (listener != null) listener.onWearableSosTrigger(triggerType);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_WEARABLE_SOS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wearableReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(wearableReceiver, filter);
        }
        registered = true;
        Log.d(TAG, "Wearable trigger registered");
    }

    public void unregister() {
        if (!registered || wearableReceiver == null) return;
        try { context.unregisterReceiver(wearableReceiver); } catch (Exception ignored) {}
        wearableReceiver = null;
        registered       = false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Outbound broadcasts
    // ══════════════════════════════════════════════════════════════════════════

    /** Call after SOS SMS is sent — notifies companion watch app. */
    public void notifyWatchSosSent(int contactCount) {
        Intent intent = new Intent(ACTION_SOS_SENT_TO_WATCH);
        intent.putExtra(EXTRA_CONTACT_COUNT, contactCount);
        intent.putExtra(EXTRA_TIMESTAMP,     System.currentTimeMillis());
        context.sendBroadcast(intent);
        Log.d(TAG, "SOS sent broadcast → watch");
    }

    /** Call when alert status changes — updates watch display. */
    public void notifyWatchStatusUpdate(String newStatus) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS,    newStatus);
        intent.putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
        context.sendBroadcast(intent);
        Log.d(TAG, "Status update → watch: " + newStatus);
    }

    public boolean isRegistered() { return registered; }
}
