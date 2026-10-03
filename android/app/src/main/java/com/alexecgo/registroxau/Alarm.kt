package com.alexecgo.registroxau

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Modo alarma: suena en bucle (como un despertador) hasta que la paras tú. */
object Alarm {
    const val NOTIF_ID = 1003
    private const val CH = "signal_alarm"
    private const val MAX_MS = 10 * 60 * 1000L // por seguridad se para sola a los 10 minutos

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { appCtx?.let { stop(it) } }
    private var appCtx: Context? = null

    var lastTitle = ""
    var lastText = ""

    val ringing: Boolean get() = player != null

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Modo alarma", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarma a pantalla completa cuando el modo alarma está activado"
                setSound(null, null) // el sonido lo controla la app, en bucle
                enableVibration(false)
                setBypassDnd(true)
            }
        )
    }

    fun start(ctx: Context, title: String, text: String) {
        if (SignalStore(ctx).dnd) { Notifier.quiet(ctx, "⏰ $title", text); return }
        val c = ctx.applicationContext
        appCtx = c
        lastTitle = title; lastText = text
        channel(c)

        val full = PendingIntent.getActivity(
            c, 10, Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            c, 11, Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("⏰ $title")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setDeleteIntent(stop)
            .addAction(0, "Parar alarma", stop)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTIF_ID, n) } catch (e: SecurityException) { }

        playLoop(c)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_MS)
    }

    private fun playLoop(c: Context) {
        if (player == null) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(c, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) { player = null }
        }
        try {
            c.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)) // repite hasta parar
        } catch (e: Exception) { }
    }

    fun stop(ctx: Context) {
        val c = ctx.applicationContext
        handler.removeCallbacks(autoStop)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { c.getSystemService(Vibrator::class.java)?.cancel() } catch (e: Exception) { }
        NotificationManagerCompat.from(c).cancel(NOTIF_ID)
        c.sendBroadcast(Intent(AlarmActivity.ACTION_FINISH).setPackage(c.packageName))
    }
}
