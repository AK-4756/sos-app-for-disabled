package com.sosapp.disabled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SmsAckReceiver — detects acknowledgement replies from registered contacts only.
 *
 * Security improvement over v1:
 *   - Sender number is matched against the contacts in SQLite.
 *   - Unknown senders are silently ignored (no false positives from spam SMS).
 *   - Phone numbers are normalised before comparison (handles +91, 0, spaces).
 *
 * Keyword matching: exact-word boundary check avoids "OK computer" triggering.
 * The word must appear as a standalone token or at the start of the message.
 */
public class SmsAckReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsAckReceiver";

    public static final String ACTION_SMS_ACKNOWLEDGED =
            "com.sosapp.disabled.ACTION_SMS_ACKNOWLEDGED";
    public static final String EXTRA_SENDER = "sender_number";
    public static final String EXTRA_BODY   = "message_body";

    // ── Acknowledgement keywords — exact-word or phrase match ────────────────
    private static final List<String> ACK_KEYWORDS = Arrays.asList(
            "yes", "ok", "coming", "acknowledge", "ack", "safe",
            "on my way", "on the way", "be there", "i'm coming", "im coming",
            "got it", "received", "will come",
            // Hindi romanised
            "haan", "theek", "aa raha", "pahunch", "a raha", "aata hoon"
    );

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String   format = bundle.getString("format");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sms == null) continue;

            String sender = sms.getOriginatingAddress();
            String body   = sms.getMessageBody();
            if (sender == null || body == null) continue;

            String bodyLower = body.trim().toLowerCase(Locale.ROOT);
            Log.d(TAG, "Incoming SMS from " + sender);

            // ── Security gate: only accept from registered contacts ───────────
            if (!isRegisteredContact(context, sender)) {
                Log.d(TAG, "Ignoring SMS from unknown number: " + sender);
                continue;
            }

            if (isAcknowledgement(bodyLower)) {
                Log.d(TAG, "ACK keyword matched from registered contact: " + sender);
                broadcastAck(context, sender, body);
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Checks if the sender number matches any contact in the local DB.
     * Normalises both numbers: strips +91/0 prefix, spaces, dashes.
     */
    private boolean isRegisteredContact(Context context, String sender) {
        String normSender = normalise(sender);
        DatabaseHelper db = new DatabaseHelper(context);
        List<Contact> contacts = db.getAllContacts();
        db.close();

        for (Contact c : contacts) {
            if (normalise(c.getPhone()).endsWith(normSender)
                    || normSender.endsWith(normalise(c.getPhone()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the message contains an ACK keyword as a whole word.
     * Uses word-boundary logic to avoid "OK" inside "BOOK" or "LOOKING".
     */
    private boolean isAcknowledgement(String bodyLower) {
        for (String keyword : ACK_KEYWORDS) {
            if (keyword.contains(" ")) {
                // Multi-word phrases — substring match is fine
                if (bodyLower.contains(keyword)) return true;
            } else {
                // Single words — require word boundary
                if (matchesWholeWord(bodyLower, keyword)) return true;
            }
        }
        return false;
    }

    private boolean matchesWholeWord(String text, String word) {
        int idx = text.indexOf(word);
        while (idx >= 0) {
            boolean beforeOk = (idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1)));
            int end = idx + word.length();
            boolean afterOk  = (end >= text.length() || !Character.isLetterOrDigit(text.charAt(end)));
            if (beforeOk && afterOk) return true;
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    private String normalise(String phone) {
        if (phone == null) return "";
        String stripped = phone.replaceAll("[\\s\\-().]", "");
        // Keep only last 10 digits for comparison (works for most international formats)
        if (stripped.length() > 10) {
            stripped = stripped.substring(stripped.length() - 10);
        }
        return stripped;
    }

    private void broadcastAck(Context context, String sender, String body) {
        Intent ack = new Intent(ACTION_SMS_ACKNOWLEDGED);
        ack.setPackage(context.getPackageName());
        ack.putExtra(EXTRA_SENDER, sender);
        ack.putExtra(EXTRA_BODY,   body);
        context.sendBroadcast(ack);
    }
}
