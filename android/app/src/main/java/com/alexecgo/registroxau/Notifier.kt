package com.alexecgo.registroxau

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
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
import org.json.JSONObject

/** Notificación fija con la señal abierta y avisos con sonido. */
object Notifier {
    const val ONGOING_ID = 1001
    const val FLOAT_ID = 1004
    private const val ALERT_ID = 1002
    private const val CH_ONGOING = "signal_ongoing"
    private const val CH_ALERT = "signal_alert"
    private const val CH_QUIET = "signal_quiet"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Avisos en silencio (No molestar)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            }
        )
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

    fun title(s: SignalState) = (if (s.side == "BUY") "🔵 " else "🔴 ") + "XAUUSD ${s.side} ${s.price}"

    fun body(s: SignalState): String {
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.openedAt))
        val prom = if (s.averages.isEmpty()) "Sin promedios" else "Promedios: " + s.averages.joinToString("  ")
        return "Abierta a las $hora · $prom"
    }

    /** Flotante con signo: "−56 $" (corto, para la isla) o "−56,34 $" (completo). */
    fun money(v: Double, short: Boolean = false): String {
        val sign = if (v < 0) "−" else "+"
        val n = java.text.NumberFormat.getNumberInstance(Locale("es", "ES")).apply {
            maximumFractionDigits = if (short) 0 else 2; minimumFractionDigits = if (short) 0 else 2
        }.format(kotlin.math.abs(v))
        return "$sign$n $"
    }

    /** Actualiza las dos notificaciones fijas (cada una con su isla): la señal y el flotante. */
    fun showOngoing(ctx: Context) {
        ensureChannels(ctx)
        showSignal(ctx)
        showFloating(ctx)
    }

    private fun promote(n: android.app.Notification, chip: String) {
        // Android 16: "actualización en directo" → aparece en la isla con este texto corto.
        n.extras.putBoolean("android.requestPromotedOngoing", true)
        n.extras.putCharSequence("android.shortCriticalText", chip)
    }

    /** Isla 1: la señal abierta de LIFT.SIGNALS. */
    private fun showSignal(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        val s = SignalStore(ctx).state()
        if (s == null) { nm.cancel(ONGOING_ID); return }
        if (!canNotify(ctx)) return
        val close = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(if (s.side == "BUY") R.drawable.ic_up else R.drawable.ic_down)
            .setContentTitle(title(s))
            .setContentText(body(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(s)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(sideColor(s))
            .setContentIntent(openApp(ctx))
            .setSortKey("1")
            .addAction(0, "Cerrar señal", close)
            .build()
        promote(n, (if (s.side == "BUY") "🔵 " else "🔴 ") + shortText(s))
        addHyperIsland(ctx, n, s)
        try { nm.notify(ONGOING_ID, n) } catch (e: SecurityException) { }
    }

    /** Isla 2: el flotante de Myfxbook (solo si hay operaciones abiertas). */
    private fun showFloating(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        val store = SignalStore(ctx)
        val f = if (store.mfxOn || store.manualTrades().isNotEmpty()) store.floating?.takeIf { kotlin.math.abs(it) > 0.005 } else null
        if (f == null) { nm.cancel(FLOAT_ID); return }
        if (!canNotify(ctx)) return
        val emoji = (if (store.mfxStale()) "⚠️" else "") + if (f < 0) "📉" else "📈"
        val lines = listOfNotNull(
            store.mfxDetail.takeIf { store.mfxAccount == "all" && it.contains("\n") },
            store.floatingUpdated.takeIf { it.isNotBlank() }?.let { "Myfxbook sincronizado: $it" }
        )
        val body = lines.joinToString("\n").ifEmpty { "Operaciones abiertas (Myfxbook)" }
        val n = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("$emoji Flotante ${money(f)}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(if (f < 0) 0xFFF0453A.toInt() else 0xFF22A06B.toInt())
            .setContentIntent(openApp(ctx))
            .setSortKey("2")
            .build()
        promote(n, "$emoji ${money(f, true)}")
        try { nm.notify(FLOAT_ID, n) } catch (e: SecurityException) { }
    }

    /** Icono ya pintado en un bitmap (la isla no siempre respeta el tinte). */
    private fun coloredIcon(ctx: Context, color: Int): Icon {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_stat_signal)!!.mutate()
        d.setTint(color)
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(bmp))
        return Icon.createWithBitmap(bmp)
    }

    /** Compra en azul, venta en rojo. */
    fun sideColor(s: SignalState): Int = if (s.side == "BUY") 0xFF2F7CF6.toInt() else 0xFFF0453A.toInt()
    private fun sideHex(s: SignalState) = String.format("#%06X", sideColor(s) and 0xFFFFFF)

    private fun shortText(s: SignalState) =
        "${s.side} ${s.price}" + if (s.averages.isEmpty()) "" else " · ${s.averages.size}P"

    /** HyperOS: datos para la Hyper Island (si el sistema la deja usar a esta app). */
    private fun addHyperIsland(ctx: Context, n: android.app.Notification, s: SignalState) {
        try {
            val pic = "miui.focus.pic_signal"
            val sub = if (s.averages.isEmpty()) "Sin promedios" else "Promedios: " + s.averages.joinToString(" ")
            val color = sideHex(s)
            val picInfo = JSONObject().put("type", 1).put("pic", pic)
            // Isla grande: texto a la izquierda, símbolo a la derecha. Compra en azul, venta en rojo.
            val island = JSONObject()
                .put("islandProperty", 1)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", JSONObject()
                        .put("type", 1)
                        .put("textInfo", JSONObject()
                            .put("title", "${s.side} ${s.price}")
                            .put("content", if (s.averages.isEmpty()) "" else "${s.averages.size} prom.")
                            .put("colorTitle", color)
                            .put("colorTitleDark", color)
                            .put("useHighLight", false)))
                    .put("picInfo", picInfo))
                .put("smallIslandArea", JSONObject().put("picInfo", picInfo))
            val param = JSONObject().put("param_v2", JSONObject()
                .put("protocol", 1)
                .put("business", "signal")
                .put("enableFloat", false)
                .put("updatable", true)
                .put("ticker", shortText(s))
                .put("tickerPic", pic)
                .put("aodTitle", shortText(s))
                .put("aodPic", pic)
                .put("param_island", island)
                .put("baseInfo", JSONObject()
                    .put("type", 2)
                    .put("title", "XAUUSD ${s.side} ${s.price}")
                    .put("content", sub)
                    .put("colorTitle", color)))
            val pics = Bundle()
            pics.putParcelable(pic, coloredIcon(ctx, sideColor(s)))
            n.extras.putBundle("miui.focus.pics", pics)
            n.extras.putString("miui.focus.param", param.toString())
        } catch (e: Exception) { }
    }

    /** Aviso sin sonido ni vibración (modo No molestar). */
    fun quiet(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(ALERT_ID, n) } catch (e: SecurityException) { }
    }

    /** Aviso puntual (abrir, promedio, cierre) con sonido por el canal de alarma. */
    fun alert(ctx: Context, title: String, text: String, allowAlarm: Boolean = true) {
        ensureChannels(ctx)
        val store = SignalStore(ctx)
        if (store.dnd) { quiet(ctx, title, text); return }
        if (allowAlarm && store.alarmActive()) { Alarm.start(ctx, title, text); return }
        if (canNotify(ctx)) {
            val n = NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_signal)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
