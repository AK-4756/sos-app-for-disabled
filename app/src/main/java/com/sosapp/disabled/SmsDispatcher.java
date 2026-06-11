package com.sosapp.disabled;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmsDispatcher — sends SOS SMS with full SENT + DELIVERED tracking.
 *
 * Each contact gets its own PendingIntent pair so we know exactly which
 * contact received the message and which did not.
 *
 * Retry policy:
 *   On SENT failure → retry up to MAX_RETRIES times with RETRY_DELAY_MS gap.
 *   If all retries exhausted → calls onContactFailed(contact).
 *
 * Caller receives callbacks on the main thread via DispatchCallback.
 */
public class SmsDispatcher {

    private static final String TAG = "SmsDispatcher";

    // ── Intent actions (unique per dispatch session to avoid cross-contamination)
    private static final String ACTION_SENT      = "com.sosapp.disabled.SMS_SENT_";
    private static final String ACTION_DELIVERED = "com.sosapp.disabled.SMS_DELIVERED_";

    private static final int  MAX_RETRIES     = 2;
    private static final long RETRY_DELAY_MS  = 8_000L;

    public interface DispatchCallback {
        /** Called when SMS to a contact is confirmed sent by the radio. */
        void onContactSent(Contact contact, int sentCount, int totalCount);
        /** Called when SMS is confirmed delivered to the handset. */
        void onContactDelivered(Contact contact);
        /** Called when all retries for a contact are exhausted. */
        void onContactFailed(Contact contact);
        /** Called when every contact has been processed (success or fail). */
        void onDispatchComplete(int successCount, int failCount);
    }

    private final Context          context;
    private final DispatchCallback callback;

    // Track receivers so we can unregister them after use
    private final Map<String, BroadcastReceiver> sentReceivers      = new HashMap<>();
    private final Map<String, BroadcastReceiver> deliveredReceivers = new HashMap<>();

    // Progress tracking
    private int totalContacts = 0;
    private final AtomicInteger processed  = new AtomicInteger(0);
    private final AtomicInteger sentOk     = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    public SmsDispatcher(Context context, DispatchCallback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dispatch an SOS message to a list of contacts.
     * Each contact gets its own SENT + DELIVERED tracking.
     */
    public void dispatch(java.util.List<Contact> contacts, String message) {
        totalContacts = contacts.size();
        processed.set(0);
        sentOk.set(0);
        failedCount.set(0);

        for (int i = 0; i < contacts.size(); i++) {
            sendWithTracking(contacts.get(i), message, 0);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Internal send + retry
    // ══════════════════════════════════════════════════════════════════════════

    private void sendWithTracking(Contact contact, String message, int attempt) {
        String sentAction      = ACTION_SENT      + contact.getId() + "_" + attempt;
        String deliveredAction = ACTION_DELIVERED + contact.getId() + "_" + attempt;

        // ── SENT receiver ─────────────────────────────────────────────────────
        BroadcastReceiver sentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                unregisterSafe(sentAction, sentReceivers);
                int resultCode = getResultCode();
                Log.d(TAG, "SMS SENT result for " + contact.getName()
                        + " attempt=" + attempt + " code=" + resultCode);

                if (resultCode == android.app.Activity.RESULT_OK) {
                    // Success
                    int count = sentOk.incrementAndGet();
                    if (callback != null) {
                        callback.onContactSent(contact, count, totalContacts);
                    }
                } else {
                    // Failed — retry or give up
                    unregisterSafe(deliveredAction, deliveredReceivers);
                    if (attempt < MAX_RETRIES) {
                        Log.w(TAG, "SMS failed (code=" + resultCode + "), retrying in "
                                + RETRY_DELAY_MS + "ms — attempt " + (attempt + 1));
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> sendWithTracking(contact, message, attempt + 1),
                                        RETRY_DELAY_MS);
                    } else {
                        Log.e(TAG, "SMS permanently failed for " + contact.getName()
                                + " after " + MAX_RETRIES + " retries");
                        failedCount.incrementAndGet();
                        if (callback != null) callback.onContactFailed(contact);
                        checkComplete();
                    }
                }
            }
        };

        // ── DELIVERED receiver ────────────────────────────────────────────────
        BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                unregisterSafe(deliveredAction, deliveredReceivers);
                Log.d(TAG, "SMS DELIVERED to " + contact.getName());
                if (callback != null) callback.onContactDelivered(contact);
                checkComplete();
            }
        };

        // Register with correct flags
        IntentFilter sentFilter      = new IntentFilter(sentAction);
        IntentFilter deliveredFilter = new IntentFilter(deliveredAction);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentReceiver,      sentFilter,      Context.RECEIVER_NOT_EXPORTED);
            context.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(sentReceiver,      sentFilter);
            context.registerReceiver(deliveredReceiver, deliveredFilter);
        }

        sentReceivers.put(sentAction,           sentReceiver);
        deliveredReceivers.put(deliveredAction, deliveredReceiver);

        // ── Build PendingIntents ───────────────────────────────────────────────
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent sentPI = PendingIntent.getBroadcast(
                context,
                contact.getId() * 100 + attempt,
                new Intent(sentAction).setPackage(context.getPackageName()),
                piFlags);

        PendingIntent deliveredPI = PendingIntent.getBroadcast(
                context,
                contact.getId() * 100 + attempt + 50,
                new Intent(deliveredAction).setPackage(context.getPackageName()),
                piFlags);

        // ── Send ──────────────────────────────────────────────────────────────
        try {
            SmsManager smsManager = getSmsManager();
            if (smsManager == null) {
                failedCount.incrementAndGet();
                if (callback != null) callback.onContactFailed(contact);
                checkComplete();
                return;
            }
            ArrayList<String> parts       = smsManager.divideMessage(message);
            ArrayList<PendingIntent> sentPIs      = new ArrayList<>();
            ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentPIs.add(i == 0 ? sentPI : null);           // track first part only
                deliveredPIs.add(i == parts.size() - 1 ? deliveredPI : null); // last part = full delivery
            }
            smsManager.sendMultipartTextMessage(
                    contact.getPhone(), null, parts, sentPIs, deliveredPIs);
        } catch (Exception e) {
            Log.e(TAG, "sendWithTracking exception for " + contact.getName(), e);
            unregisterSafe(sentAction,      sentReceivers);
            unregisterSafe(deliveredAction, deliveredReceivers);
            failedCount.incrementAndGet();
            if (callback != null) callback.onContactFailed(contact);
            checkComplete();
        }
    }

    private void checkComplete() {
        // Complete when failed contacts + delivered contacts = total
        // We use processed counter: each terminal event (delivered or permanent fail) increments it
        int done = processed.incrementAndGet();
        if (done >= totalContacts) {
            if (callback != null) {
                callback.onDispatchComplete(sentOk.get(), failedCount.get());
            }
            cleanupAllReceivers();
        }
    }

    /** Unregisters all receivers — call on Activity destroy. */
    public void cleanup() {
        cleanupAllReceivers();
    }

    private void cleanupAllReceivers() {
        for (BroadcastReceiver r : sentReceivers.values()) {
            try { context.unregisterReceiver(r); } catch (Exception ignored) {}
        }
        for (BroadcastReceiver r : deliveredReceivers.values()) {
            try { context.unregisterReceiver(r); } catch (Exception ignored) {}
        }
        sentReceivers.clear();
        deliveredReceivers.clear();
    }

    private void unregisterSafe(String action, Map<String, BroadcastReceiver> map) {
        BroadcastReceiver r = map.remove(action);
        if (r != null) {
            try { context.unregisterReceiver(r); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }
}
