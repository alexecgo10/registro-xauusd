package com.alexecgo.prueba

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prueba: una notificación sencilla para la isla y otra de color con diseño propio. */
object Notis {
    const val ID_ISLA = 1
    const val ID_COLOR = 2
    const val ID_GRUPO = 3
    private const val CH_ISLA = "isla"
    private const val CH_COLOR = "color"
    private const val GROUP = "senal"

    var side = "BUY"
    var price = "4160"
    val avgs = mutableListOf<String>()
    var grouped = false
    var openedAt = System.currentTimeMillis()

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ISLA, "Isla (señal)", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_COLOR, "Tarjeta de color", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buy() = side == "BUY"
    private fun icon() = if (buy()) R.drawable.ic_up else R.drawable.ic_down
    private fun color() = if (buy()) 0xFF2F7CF6.toInt() else 0xFFE5483D.toInt()
    private fun shortText() = "$side $price" + if (avgs.isEmpty()) "" else " · ${avgs.size}P"

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun show(ctx: Context) {
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val emoji = if (buy()) "🔵" else "🔴"

        // 1) Sencilla, estándar: es la que Android pone en la isla.
        val isla = Notification.Builder(ctx, CH_ISLA)
            .setSmallIcon(icon())
            .setContentTitle("$emoji XAUUSD $side $price")
            .setContentText(if (avgs.isEmpty()) "Sin promedios" else "${avgs.size} promedio(s)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(color())
            .setContentIntent(openApp(ctx))
            .setSortKey("a")
        if (grouped) isla.setGroup(GROUP)
        val n1 = isla.build()
        n1.extras.putBoolean("android.requestPromotedOngoing", true)
        n1.extras.putCharSequence("android.shortCriticalText", "$emoji ${shortText()}")
        nm.notify(ID_ISLA, n1)

        // 2) Personalizada: fondo de color con el detalle.
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(openedAt))
        val rv = RemoteViews(ctx.packageName, R.layout.notif_big).apply {
            setInt(R.id.root, "setBackgroundResource", if (buy()) R.drawable.bg_buy else R.drawable.bg_sell)
            setTextViewText(R.id.side, "XAUUSD · ${if (buy()) "COMPRA" else "VENTA"} · $hora")
            setTextViewText(R.id.price, price)
            setTextViewText(R.id.avgs, if (avgs.isEmpty()) "Sin promedios" else "Promedios: " + avgs.joinToString("  "))
            setImageViewResource(R.id.arrow, icon())
        }
        val close = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, CloseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tarjeta = Notification.Builder(ctx, CH_COLOR)
            .setSmallIcon(icon())
            .setColor(color())
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(null, "Cerrar señal", close).build())
            .setSortKey("b")
        if (grouped) tarjeta.setGroup(GROUP)
        nm.notify(ID_COLOR, tarjeta.build())

        // Resumen del grupo (solo si se agrupan).
        if (grouped) {
            nm.notify(ID_GRUPO, Notification.Builder(ctx, CH_COLOR)
                .setSmallIcon(icon()).setColor(color())
                .setContentTitle("$emoji XAUUSD $side $price")
                .setGroup(GROUP).setGroupSummary(true).setOngoing(true).build())
        } else nm.cancel(ID_GRUPO)
    }

    fun clear(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_ISLA); nm.cancel(ID_COLOR); nm.cancel(ID_GRUPO)
        avgs.clear()
    }

    fun promotedStatus(ctx: Context): String = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ok = nm.javaClass.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
        if (ok) "✅ Actualizaciones en directo permitidas" else "❌ Actualizaciones en directo desactivadas"
    } catch (e: Exception) { "Este Android no tiene actualizaciones en directo" }
}
