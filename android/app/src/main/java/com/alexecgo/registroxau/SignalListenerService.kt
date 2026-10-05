package com.alexecgo.registroxau

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lee las notificaciones de Telegram del canal de señales y actualiza la notificación fija. */
class SignalListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (SignalStore(this).paused) { requestUnbind(); return }
        instance = this
        SignalStore(this).logSeen("— lector conectado —")
        Notifier.showOngoing(this)
        Myfxbook.start(this) // flotante de Myfxbook (si está activado)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Myfxbook.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (e: Exception) { SignalStore(this).log("Error: ${e.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Si se quita nuestra notificación fija con una señal abierta, la volvemos a poner.
        if (sbn.packageName == packageName && (sbn.id == Notifier.ONGOING_ID || sbn.id == Notifier.FLOAT_ID)) {
            Notifier.forget(sbn.id)
            Notifier.showOngoing(this)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in TELEGRAM) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val store = SignalStore(this)
        val title = titleOf(n.extras)
        // Se busca el canal en todo lo que trae la notificación (título, conversación, remitentes…),
        // ignorando puntos, espacios y emojis: "LIFT.SIGNALS" = "Lift Signals" = "LIFT·SIGNALS".
        val hay = norm(listOfNotNull(title, n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(60)).joinToString(" ") +
            " " + sendersOf(n.extras).joinToString(" "))
        val match = hay.contains(norm(store.titleFilter))
        store.logSeen((if (match) "✔ " else "· ") + title.take(28) + " | " +
            (textOf(n.extras) ?: "").replace("\n", " ").take(40))
        if (!match) return
        // Telegram agrupa varios mensajes en la misma notificación: se procesan todos los nuevos, en orden.
        val msgs = messagesOf(n.extras)
        if (msgs.isEmpty()) {
            val text = textOf(n.extras) ?: return
            store.logRead(text); process(this, text); return
        }
        val last = store.tgLastTime
        val since = if (last == 0L) System.currentTimeMillis() - 15 * 60_000L else last
        var newest = last
        for ((time, text) in msgs.sortedBy { it.first }) {
            if (time > 0L && time <= since) continue
            // Sin hora fiable: se evita repetir con el control de duplicados por texto.
            if (time == 0L && store.alreadySeen("raw|$text")) continue
            newest = maxOf(newest, time)
            store.logRead(text)
            process(this, text)
        }
        if (newest > last) store.tgLastTime = newest
    }

    companion object {
        @Volatile var instance: SignalListenerService? = null
        val TELEGRAM = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
            "org.telegram.plus", "org.thunderdog.challegram", "tw.nekomimi.nekogram", "nekox.messenger"
        )

        /** Aplica un texto (real o de prueba) y avisa si cambia la señal. */
        fun process(ctx: Context, text: String, test: Boolean = false) {
            val store = SignalStore(ctx)
            val ev = SignalParser.parse(plain(text)) ?: return
            if (!test && store.alreadySeen(text)) return
            val hadSignal = store.state() != null
            val changed = store.apply(ev)
            Notifier.showOngoing(ctx)
            // "Cerramos todo" avisa siempre: aunque la app no tuviera la señal, puedes tener la operación abierta.
            if (!changed && ev == SignalEvent.CloseAll) {
                store.log("Cerramos todo (sin señal abierta en la app)")
                Notifier.alert(ctx, "✅ Cerramos todo", "LIFT.SIGNALS: cierra lo que tengas abierto")
                ctx.sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(ctx.packageName))
                return
            }
            if (!changed) return
            val s = store.state()
            when (ev) {
                is SignalEvent.Open -> Notifier.alert(ctx, "Nueva señal: ${ev.side} ${ev.price}", "LIFT.SIGNALS")
                is SignalEvent.Average -> Notifier.alert(ctx, "Promedio ${ev.price}", s?.let { Notifier.title(it) } ?: "")
                is SignalEvent.CloseAverage -> Notifier.alert(ctx, "Cerrado promedio ${ev.price}", s?.let { Notifier.title(it) } ?: "")
                SignalEvent.CloseAll -> Notifier.alert(ctx, "✅ Señal cerrada", "LIFT.SIGNALS: cerramos todo")
            }
            ctx.sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(ctx.packageName))
        }

        const val ACTION_CHANGED = "com.alexecgo.registroxau.SIGNAL_CHANGED"

        fun titleOf(extras: Bundle): String =
            (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: "").toString()

        /** Pasa letras "de adorno" (𝗟𝗜𝗙𝗧, 𝐋𝐈𝐅𝐓, ｌｉｆｔ…) a letras normales y quita puntos, espacios y emojis. */
        fun plain(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        fun norm(s: String) = plain(s).uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

        /** Nombres de remitente de los mensajes (en canales suele ser el nombre del canal). */
        fun sendersOf(extras: Bundle): List<String> {
            val msgs: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            else @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            return msgs.orEmpty().mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                b.getCharSequence("sender")?.toString()
                    ?: (if (Build.VERSION.SDK_INT >= 28) (b.getParcelable("sender_person") as? android.app.Person)?.name?.toString() else null)
            }
        }

        /** Todos los mensajes (hora, texto) de una notificación de estilo conversación. */
        fun messagesOf(extras: Bundle): List<Pair<Long, String>> {
            val msgs: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            else
                @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            return msgs.orEmpty().mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val t = b.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                b.getLong("time", 0L) to t
            }
        }

        /** Último mensaje de la notificación (estilo conversación) o su texto normal. */
        fun textOf(extras: Bundle): String? {
            val msgs: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            else
                @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull() as? Bundle
            val fromMessages = last?.getCharSequence("text")?.toString()
            if (!fromMessages.isNullOrBlank()) return fromMessages
            return (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        }
    }
}
