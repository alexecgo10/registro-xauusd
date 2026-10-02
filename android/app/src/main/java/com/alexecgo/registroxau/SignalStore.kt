package com.alexecgo.registroxau

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Señal abierta en este momento. */
data class SignalState(
    val side: String,
    val price: String,
    val openedAt: Long,
    val averages: List<String>
)

/** Guarda en el móvil la señal abierta, el historial y los ajustes. */
class SignalStore(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("signals", Context.MODE_PRIVATE)

    var titleFilter: String
        get() = prefs.getString("titleFilter", DEFAULT_TITLE) ?: DEFAULT_TITLE
        set(v) = prefs.edit().putString("titleFilter", v.trim().ifEmpty { DEFAULT_TITLE }).apply()

    var soundOn: Boolean
        get() = prefs.getBoolean("soundOn", true)
        set(v) = prefs.edit().putBoolean("soundOn", v).apply()

    fun state(): SignalState? {
        val raw = prefs.getString("state", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val avg = o.optJSONArray("averages") ?: JSONArray()
            SignalState(
                o.getString("side"), o.getString("price"), o.getLong("openedAt"),
                (0 until avg.length()).map { avg.getString(it) }
            )
        } catch (e: Exception) { null }
    }

    fun saveState(s: SignalState?) {
        if (s == null) { prefs.edit().remove("state").apply(); return }
        val o = JSONObject()
            .put("side", s.side).put("price", s.price).put("openedAt", s.openedAt)
            .put("averages", JSONArray(s.averages))
        prefs.edit().putString("state", o.toString()).apply()
    }

    /** Aplica un evento a la señal guardada. Devuelve true si algo cambió. */
    fun apply(ev: SignalEvent, now: Long = System.currentTimeMillis()): Boolean {
        val cur = state()
        when (ev) {
            is SignalEvent.Open -> {
                if (cur != null && cur.side == ev.side && cur.price == ev.price) return false
                saveState(SignalState(ev.side, ev.price, now, emptyList()))
                log("Abierta ${ev.side} ${ev.price}")
            }
            is SignalEvent.Average -> {
                if (cur == null) { log("Promedio ${ev.price} (sin señal abierta)"); return false }
                if (cur.averages.contains(ev.price)) return false
                saveState(cur.copy(averages = cur.averages + ev.price))
                log("Promedio ${ev.price}")
            }
            is SignalEvent.CloseAverage -> {
                if (cur == null || !cur.averages.contains(ev.price)) { log("Cerrado promedio ${ev.price}"); return false }
                saveState(cur.copy(averages = cur.averages - ev.price))
                log("Cerrado promedio ${ev.price}")
            }
            SignalEvent.CloseAll -> {
                if (cur == null) return false
                saveState(null)
                log("Cerrado todo (${cur.side} ${cur.price})")
            }
        }
        return true
    }

    /** Evita procesar dos veces el mismo mensaje cuando Telegram vuelve a publicar la notificación. */
    fun alreadySeen(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = text.trim().hashCode().toString()
        val seen = JSONObject(prefs.getString("seen", "{}") ?: "{}")
        val last = seen.optLong(key, 0L)
        val fresh = JSONObject()
        seen.keys().forEach { k -> val t = seen.getLong(k); if (now - t < WINDOW_MS) fresh.put(k, t) }
        if (last != 0L && now - last < WINDOW_MS) {
            prefs.edit().putString("seen", fresh.toString()).apply()
            return true
        }
        fresh.put(key, now)
        prefs.edit().putString("seen", fresh.toString()).apply()
        return false
    }

    fun history(): List<String> {
        val a = JSONArray(prefs.getString("history", "[]") ?: "[]")
        return (0 until a.length()).map { a.getString(it) }
    }

    fun log(msg: String) {
        val line = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()) + " · " + msg
        val list = listOf(line) + history()
        prefs.edit().putString("history", JSONArray(list.take(60)).toString()).apply()
    }

    companion object {
        const val DEFAULT_TITLE = "LIFT.SIGNALS"
        private const val WINDOW_MS = 10 * 60 * 1000L
    }
}
