package com.sosapp.disabled;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Centralises all notification work.
 *
 * Channels (Android 8+):
 *   CHANNEL_SOS_ALERT   — high priority, for fired SOS confirmation
 *   CHANNEL_STATUS      — low priority, for persistent "app active" bar
 *   CHANNEL_CHECKIN     — default priority, for guardian check-in reminders
 *
 * Notification IDs:
 *   NOTIF_STATUS  (1) — persistent status bar entry (ongoing)
 *   NOTIF_ALERT   (2) — SOS sent confirmation (auto-dismisses after a few seconds)
 *   NOTIF_CHECKIN (3) — guardian check-in reminder
 */
public class NotificationHelper {

    public static final String CHANNEL_SOS_ALERT = "sos_alert";
    public static final String CHANNEL_STATUS    = "sos_status";
    public static final String CHANNEL_CHECKIN   = "sos_checkin";

    public static final int NOTIF_STATUS  = 1;
    public static final int NOTIF_ALERT   = 2;
    public static final int NOTIF_CHECKIN = 3;

    /**
     * Must be called once — from SosApplication.onCreate().
     * Safe to call repeatedly; createNotificationChannel is a no-op if the
     * channel already exists.
     */
    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // SOS Alert — max importance, cannot be silenced by user in channel settings
        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_SOS_ALERT,
                context.getString(R.string.notif_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription(context.getString(R.string.notif_channel_alert_desc));
        alertChannel.enableVibration(true);
        alertChannel.enableLights(true);
        nm.createNotificationChannel(alertChannel);

        // Persistent status — min importance so it stays in bar but makes no sound
        NotificationChannel statusChannel = new NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notif_channel_status_name),
                NotificationManager.IMPORTANCE_MIN);
        statusChannel.setDescription(context.getString(R.string.notif_channel_status_desc));
        nm.createNotificationChannel(statusChannel);

        // Check-in reminder — default importance (makes a sound)
        NotificationChannel checkInChannel = new NotificationChannel(
                CHANNEL_CHECKIN,
                context.getString(R.string.notif_channel_checkin_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        checkInChannel.setDescription(context.getString(R.string.notif_channel_checkin_desc));
        nm.createNotificationChannel(checkInChannel);
    }

    /** Shows/updates the persistent "SOS App is active" status bar entry. */
    public static void showStatusNotification(Context context, String contactCount) {
        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notif_status_title))
                .setContentText(context.getString(R.string.notif_status_text, contactCount))
                .setContentIntent(pi)
                .setOngoing(true)           // cannot be swiped away
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        NotificationManagerCompat.from(context).notify(NOTIF_STATUS, notif);
    }

    /** Posts a prominent notification confirming the SOS alert was sent. */
    public static void showSosSentNotification(Context context, int contactCount) {
        Intent tapIntent = new Intent(context, AlertHistoryActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 1, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(context, CHANNEL_SOS_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notif_alert_title))
                .setContentText(context.getString(R.string.notif_alert_text, contactCount))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build();

        NotificationManagerCompat.from(context).notify(NOTIF_ALERT, notif);
    }

    /** Posts a check-in reminder notification. */
    public static void showCheckInReminder(Context context) {
        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 2, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(context, CHANNEL_CHECKIN)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notif_checkin_title))
                .setContentText(context.getString(R.string.notif_checkin_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        NotificationManagerCompat.from(context).notify(NOTIF_CHECKIN, notif);
    }

    public static void cancelStatusNotification(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_STATUS);
    }
}
