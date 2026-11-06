package com.telehaptic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the app process alive while connected, so the link and a running recording survive
 * a switched-off screen. Type connectedDevice (Android 14+ needs a Bluetooth permission for it).
 */
class CapsuleService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, text)
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            App.get().log.w("service", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // Stopping goes through here too: startForeground must run once for every startForegroundService call.
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "link"
        private const val ACTION_STOP = "stop"
        @Volatile private var text = "Connecting to the capsule…"

        private fun notification(ctx: Context, t: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capsule link", NotificationManager.IMPORTANCE_LOW))
            // Brings the one app window forward (singleTask), never a second copy of it.
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Telehaptic")
                .setContentText(t)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** The notification says what the link is doing now: with the screen off it is the one status in sight. */
        fun update(ctx: Context, t: String) {
            if (t == text) return
            text = t
            try { ctx.getSystemService(NotificationManager::class.java).notify(1, notification(ctx, t)) } catch (_: Exception) {}
        }

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, CapsuleService::class.java))
            } catch (e: Exception) {
                App.get().log.w("service", "start failed", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, CapsuleService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                ctx.stopService(Intent(ctx, CapsuleService::class.java))
            }
        }
    }
}
