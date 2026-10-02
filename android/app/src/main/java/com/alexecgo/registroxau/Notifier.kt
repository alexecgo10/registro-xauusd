package com.alexecgo.registroxau

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notificación fija con la señal abierta y avisos con sonido. */
object Notifier {
    const val ONGOING_ID = 1001
    private const val ALERT_ID = 1002
    private const val CH_ONGOING = "signal_ongoing"
    private const val CH_ALERT = "signal_alert"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Señal abierta", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notificación fija con la señal abierta de LIFT.SIGNALS"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avisos de señales", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aviso cuando se abre, promedia o cierra una señal"
                setSound(null, null) // el sonido lo reproducimos nosotros por el canal de alarma
                enableVibration(false)
            }
        )
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun title(s: SignalState) = (if (s.side == "BUY") "🟢 " else "🔴 ") + "XAUUSD ${s.side} ${s.price}"

    fun body(s: SignalState): String {
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.openedAt))
        val prom = if (s.averages.isEmpty()) "Sin promedios" else "Promedios: " + s.averages.joinToString("  ")
        return "Abierta a las $hora · $prom"
    }

    /** Muestra, actualiza o quita la notificación fija según el estado guardado. */
    fun showOngoing(ctx: Context) {
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val s = SignalStore(ctx).state()
        if (s == null) { nm.cancel(ONGOING_ID); return }
        if (!canNotify(ctx)) return
        val close = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title(s))
            .setContentText(body(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(s)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(if (s.side == "BUY") 0xFF15835A.toInt() else 0xFFC13A32.toInt())
            .setContentIntent(openApp(ctx))
            .addAction(0, "Cerrar señal", close)
            .build()
        try { nm.notify(ONGOING_ID, n) } catch (e: SecurityException) { }
    }

    /** Aviso puntual (abrir, promedio, cierre) con sonido por el canal de alarma. */
    fun alert(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val store = SignalStore(ctx)
        if (canNotify(ctx)) {
            val n = NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_signal)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(ctx))
                .build()
            try { NotificationManagerCompat.from(ctx).notify(ALERT_ID, n) } catch (e: SecurityException) { }
        }
        if (store.soundOn) playSound(ctx)
    }

    private fun playSound(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM) // suena aunque esté "No molestar" si las alarmas están permitidas
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
        } catch (e: Exception) { }
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        } catch (e: Exception) { }
    }
}
