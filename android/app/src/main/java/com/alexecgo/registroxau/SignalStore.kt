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

    /** Modo alarma (para la noche): suena en bucle hasta pararla. */
    var alarmMode: Boolean
        get() = prefs.getBoolean("alarmMode", false)
        set(v) = prefs.edit().putBoolean("alarmMode", v).apply()

    // --- Myfxbook (flotante). Se guarda solo en este móvil. ---
    var mfxOn: Boolean
        get() = prefs.getBoolean("mfxOn", false)
        set(v) = prefs.edit().putBoolean("mfxOn", v).apply()
    var mfxEmail: String
        get() = prefs.getString("mfxEmail", "") ?: ""
        set(v) = prefs.edit().putString("mfxEmail", v).apply()
    var mfxPassword: String
        get() = prefs.getString("mfxPassword", "") ?: ""
        set(v) = prefs.edit().putString("mfxPassword", v).apply()
    var mfxSession: String
        get() = prefs.getString("mfxSession", "") ?: ""
        set(v) = prefs.edit().putString("mfxSession", v).apply()
    var mfxAccount: String
        get() = prefs.getString("mfxAccount", "all") ?: "all"
        set(v) = prefs.edit().putString("mfxAccount", v).apply()
    /** Lista "id~nombre|id~nombre" de la última lectura, para el selector. */
    var mfxAccounts: String
        get() = prefs.getString("mfxAccounts", "") ?: ""
        set(v) = prefs.edit().putString("mfxAccounts", v).apply()
    var mfxDetail: String
        get() = prefs.getString("mfxDetail", "") ?: ""
        set(v) = prefs.edit().putString("mfxDetail", v).apply()
    var mfxError: String
        get() = prefs.getString("mfxError", "") ?: ""
        set(v) = prefs.edit().putString("mfxError", v).apply()

    /** Flotante en $ si la última lectura tiene menos de 3 horas (si la app estuvo dormida, mejor el último dato que nada). */
    val floating: Double?
        get() {
            if (!prefs.contains("floating")) return null
            if (System.currentTimeMillis() - prefs.getLong("floatingAt", 0) > 3 * 60 * 60_000L) return null
            return prefs.getFloat("floating", 0f).toDouble()
        }
    val floatingUpdated: String get() = prefs.getString("floatingUpd", "") ?: ""
    fun setFloating(v: Double, updated: String) = prefs.edit()
        .putFloat("floating", v.toFloat()).putLong("floatingAt", System.currentTimeMillis())
        .putString("floatingUpd", updated).remove("mfxError").apply()
    fun clearFloating() = prefs.edit().remove("floating").remove("floatingAt").apply()

    /** App apagada a mano: no lee Telegram ni consulta nada hasta que se vuelva a abrir. */
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).commit().let { }

    /** No molestar: todo sigue funcionando, pero sin sonidos ni alarmas. */
    var dnd: Boolean
        get() = prefs.getBoolean("dnd", false)
        set(v) = prefs.edit().putBoolean("dnd", v).apply()

    /** Cada cuántos segundos se consulta el precio en segundo plano. */
    var bgInterval: Int
        get() = prefs.getInt("bgInterval", 10)
        set(v) = prefs.edit().putInt("bgInterval", v).apply()

    /** Myfxbook "atascado": su última sincronización no cambia desde hace más de 45 min con el mercado abierto. */
    fun noteMfxUpdate(updated: String) {
        if (updated != prefs.getString("mfxUpdVal", null))
            prefs.edit().putString("mfxUpdVal", updated).putLong("mfxUpdSeen", System.currentTimeMillis()).apply()
    }
    fun mfxStale(): Boolean {
        val q = quote ?: return false
        if (q.closed || !mfxOn || !prefs.contains("mfxUpdSeen")) return false
        return System.currentTimeMillis() - prefs.getLong("mfxUpdSeen", 0) > 45 * 60_000L
    }

    // --- Noticias ---
    var newsOn: Boolean
        get() = prefs.getBoolean("newsOn", true)
        set(v) = prefs.edit().putBoolean("newsOn", v).apply()
    var newsMedium: Boolean
        get() = prefs.getBoolean("newsMedium", false)
        set(v) = prefs.edit().putBoolean("newsMedium", v).apply()
    var newsBefore: Int
        get() = prefs.getInt("newsBefore", 15)
        set(v) = prefs.edit().putInt("newsBefore", v).apply()
    var newsAtRelease: Boolean
        get() = prefs.getBoolean("newsAtRelease", true)
        set(v) = prefs.edit().putBoolean("newsAtRelease", v).apply()
    var newsBlock: Boolean
        get() = prefs.getBoolean("newsBlock", true)
        set(v) = prefs.edit().putBoolean("newsBlock", v).apply()
    var newsJson: String
        get() = prefs.getString("newsJson", "[]") ?: "[]"
        set(v) = prefs.edit().putString("newsJson", v).apply()
    var newsAt: Long
        get() = prefs.getLong("newsAt", 0)
        set(v) = prefs.edit().putLong("newsAt", v).apply()
    var newsDone: Set<String>
        get() = prefs.getStringSet("newsDone", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("newsDone", HashSet(v)).apply()

    // --- Alarmas ---
    var alarmSched: Boolean
        get() = prefs.getBoolean("alarmSched", false)
        set(v) = prefs.edit().putBoolean("alarmSched", v).apply()
    var schedFrom: String
        get() = prefs.getString("schedFrom", "00:00") ?: "00:00"
        set(v) = prefs.edit().putString("schedFrom", v).apply()
    var schedTo: String
        get() = prefs.getString("schedTo", "08:00") ?: "08:00"
        set(v) = prefs.edit().putString("schedTo", v).apply()

    /** Modo alarma manual, o el horario si estamos dentro de él (admite cruzar la medianoche). */
    fun alarmActive(): Boolean {
        if (alarmMode) return true
        if (!alarmSched) return false
        fun mins(t: String) = t.split(":").let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val c = java.util.Calendar.getInstance()
        val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val a = mins(schedFrom); val b = mins(schedTo)
        return if (a <= b) now in a until b else now >= a || now < b
    }

    var floatAlarmOn: Boolean
        get() = prefs.getBoolean("floatAlarmOn", false)
        set(v) = prefs.edit().putBoolean("floatAlarmOn", v).apply()
    var floatAlarmLimit: Double
        get() = prefs.getFloat("floatAlarmLimit", 0f).toDouble()
        set(v) = prefs.edit().putFloat("floatAlarmLimit", v.toFloat()).apply()
    var floatAlarmFired: Boolean
        get() = prefs.getBoolean("floatAlarmFired", false)
        set(v) = prefs.edit().putBoolean("floatAlarmFired", v).apply()

    // --- Precio del oro y posiciones ---
    var quote: Quote?
        get() = if (!prefs.contains("pxBid")) null else
            Quote(prefs.getFloat("pxBid", 0f).toDouble(), prefs.getFloat("pxAsk", 0f).toDouble(), prefs.getLong("pxTs", 0))
        set(q) {
            val e = prefs.edit()
            if (q == null) e.remove("pxBid").remove("pxAsk").remove("pxTs")
            else {
                if (prefs.contains("pxBid") && prefs.getFloat("pxBid", 0f) != q.bid.toFloat()) e.putFloat("pxPrev", prefs.getFloat("pxBid", 0f))
                e.putFloat("pxBid", q.bid.toFloat()).putFloat("pxAsk", q.ask.toFloat()).putLong("pxTs", q.ts)
            }
            e.apply()
        }
    val prevBid: Double? get() = if (prefs.contains("pxPrev")) prefs.getFloat("pxPrev", 0f).toDouble() else null

    /** Operaciones tal como las da Myfxbook. */
    fun rawTrades(): List<Trade> = try {
        val a = JSONArray(prefs.getString("trades", "[]")); (0 until a.length()).map { Trade.of(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    fun saveTrades(l: List<Trade>) {
        // Limpia ocultas/ediciones de operaciones que Myfxbook ya no tiene.
        val keys = l.map { it.key }.toSet()
        val e = prefs.edit().putString("trades", JSONArray(l.map { it.toJson() }).toString())
        if (l.isNotEmpty() || prefs.contains("trades")) {
            e.putStringSet("tradeHidden", HashSet(tradeHidden.filter { it in keys }))
            val ed = tradeEdits; val keep = JSONObject()
            ed.keys().forEach { k -> if (k in keys) keep.put(k, ed.get(k)) }
            e.putString("tradeEdits", keep.toString())
        }
        e.apply()
    }

    /** Operaciones que se muestran: Myfxbook (sin las ocultas, con tus correcciones) + las añadidas a mano. */
    fun trades(): List<Trade> {
        val hidden = tradeHidden; val ed = tradeEdits
        val fromMfx = rawTrades().filter { it.key !in hidden }.map { t ->
            ed.optJSONObject(t.key)?.let { o -> t.copy(lots = o.optDouble("lots", t.lots), open = o.optDouble("open", t.open), edited = true) } ?: t
        }
        return fromMfx + manualTrades()
    }

    var tradeHidden: Set<String>
        get() = prefs.getStringSet("tradeHidden", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("tradeHidden", HashSet(v)).apply()
    val tradeEdits: JSONObject get() = try { JSONObject(prefs.getString("tradeEdits", "{}")) } catch (e: Exception) { JSONObject() }
    fun editTrade(key: String, lots: Double, open: Double) =
        prefs.edit().putString("tradeEdits", tradeEdits.put(key, JSONObject().put("lots", lots).put("open", open)).toString()).apply()

    var lastManualLots: Double
        get() = prefs.getFloat("lastManualLots", 1f).toDouble()
        set(v) = prefs.edit().putFloat("lastManualLots", v.toFloat()).apply()

    fun manualTrades(): List<Trade> = try {
        val a = JSONArray(prefs.getString("manualTrades", "[]")); (0 until a.length()).map { Trade.of(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    fun saveManual(l: List<Trade>) = prefs.edit().putString("manualTrades", JSONArray(l.map { it.toJson() }).toString()).apply()

    fun orders(): List<Order> = try {
        val a = JSONArray(prefs.getString("orders", "[]")); (0 until a.length()).map { Order.of(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    fun saveOrders(l: List<Order>) = prefs.edit().putString("orders", JSONArray(l.map { it.toJson() }).toString()).apply()

    /** Órdenes con el aviso desactivado / con alarma / ya avisadas. */
    var ordOff: Set<String>
        get() = prefs.getStringSet("ordOff", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("ordOff", HashSet(v)).apply()
    var ordAlarm: Set<String>
        get() = prefs.getStringSet("ordAlarm", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("ordAlarm", HashSet(v)).apply()
    var ordFired: Set<String>
        get() = prefs.getStringSet("ordFired", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("ordFired", HashSet(v)).apply()

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
