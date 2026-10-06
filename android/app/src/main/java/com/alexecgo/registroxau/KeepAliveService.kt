package com.alexecgo.registroxau

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano que mantiene viva la app (HyperOS la mata en segundo plano)
 * y vuelve a conectar el lector de Telegram si el sistema lo desconecta.
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            check(this@KeepAliveService)
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SignalStore(this).paused) { stopSelf(); return START_NOT_STICKY }
        channel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Señales XAU activa")
            .setContentText("Vigilando el canal")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        Myfxbook.start(this)
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    companion object {
        private const val CH = "keepalive"
        private const val ID = 1005

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "App activa (segundo plano)", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                })
        }

        /** Si el lector está permitido pero desconectado, se le pide a Android que lo reconecte. */
        fun check(ctx: Context) {
            val st = SignalStore(ctx)
            if (st.paused) return
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            if (enabled && SignalListenerService.instance == null) {
                try { NotificationListenerService.requestRebind(ComponentName(ctx, SignalListenerService::class.java)) }
                catch (e: Exception) { }
            }
        }

        fun start(ctx: Context) {
            if (SignalStore(ctx).paused) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { }
        }
    }
}
