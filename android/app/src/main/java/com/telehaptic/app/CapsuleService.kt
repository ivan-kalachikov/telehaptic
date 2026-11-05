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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Связь с капсулой", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Телехаптика")
            .setContentText("Связь с капсулой включена")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            App.get().log.w("service", "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "link"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, CapsuleService::class.java))
            } catch (e: Exception) {
                App.get().log.w("service", "start failed", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CapsuleService::class.java))
        }
    }
}
