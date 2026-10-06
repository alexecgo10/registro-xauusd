package com.alexecgo.registroxau

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Precio del oro (compra/venta) en un momento dado. */
data class Quote(val bid: Double, val ask: Double, val ts: Long) {
    /** Sin cambios desde hace más de 10 minutos: mercado cerrado (fin de semana, pausa diaria). */
    val closed get() = System.currentTimeMillis() - ts > 10 * 60_000L
}

/** Tus dos cuentas: "P" (Principal ·4318) y "S" (Secundaria ·2198). */
object Accounts {
    fun of(number: String, name: String): String = when {
        number.endsWith("4318") -> "P"
        number.endsWith("2198") -> "S"
        else -> name
    }
    fun label(k: String) = when (k) { "P", "Manual" -> "Principal"; "S" -> "Secundaria"; else -> k }
}

/** Operación abierta (de Myfxbook o añadida a mano). Importes en dólares. */
data class Trade(val acc: String, val sym: String, val buy: Boolean, val lots: Double, val open: Double,
                 val profit: Double, val k: Double, val key: String = "", val manual: Boolean = false, val edited: Boolean = false) {
    val gold get() = sym.contains("XAU", true)
    /** Cuenta a la que pertenece ("P", "S"…). Las antiguas manuales sin cuenta van a la Principal. */
    val page get() = if (acc == "Manual") "P" else acc
    /** Beneficio en directo con el precio actual (sin swap). */
    fun live(q: Quote?): Double =
        if (q == null || !gold) profit
        else (if (buy) q.bid - open else open - q.ask) * lots * 100 * k

    fun toJson(): JSONObject = JSONObject().put("acc", acc).put("sym", sym).put("buy", buy)
        .put("lots", lots).put("open", open).put("profit", profit).put("k", k).put("key", key).put("manual", manual)

    companion object {
        fun of(o: JSONObject) = Trade(o.optString("acc"), o.optString("sym"), o.optBoolean("buy"),
            o.optDouble("lots"), o.optDouble("open"), o.optDouble("profit"), o.optDouble("k", 1.0),
            o.optString("key"), o.optBoolean("manual"))
    }
}

/** Orden pendiente (límite o stop) de Myfxbook. */
data class Order(val key: String, val acc: String, val sym: String, val type: String, val lots: Double, val price: Double) {
    val buy get() = type.startsWith("Buy", true)
    val gold get() = sym.contains("XAU", true)
    private val stop get() = type.contains("Stop", true)

    /** ¿Ha llegado el precio a la orden? */
    fun reached(q: Quote): Boolean = when {
        buy && !stop -> q.ask <= price
        !buy && !stop -> q.bid >= price
        buy && stop -> q.ask >= price
        else -> q.bid <= price
    }

    fun distance(q: Quote): Double = kotlin.math.abs((if (buy) q.ask else q.bid) - price)

    fun toJson(): JSONObject = JSONObject().put("key", key).put("acc", acc).put("sym", sym)
        .put("type", type).put("lots", lots).put("price", price)

    companion object {
        fun of(o: JSONObject) = Order(o.optString("key"), o.optString("acc"), o.optString("sym"),
            o.optString("type"), o.optDouble("lots"), o.optDouble("price"))
    }
}

object Market {
    private const val FEED = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD"

    /** Precio XAU/USD en directo (Swissquote, gratuito). */
    fun fetchQuote(): Quote? = try {
        val c = URL(FEED).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        val arr = try { JSONArray(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        var best: Quote? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val prices = o.optJSONArray("spreadProfilePrices") ?: continue
            if (prices.length() == 0) continue
            val p = prices.getJSONObject(0)
            val q = Quote(p.getDouble("bid"), p.getDouble("ask"), o.optLong("ts"))
            if (best == null || q.ts > best.ts) best = q
        }
        best
    } catch (e: Exception) { null }

    /**
     * Cada vuelta del vigilante: flotante en directo, avisos de órdenes que entran
     * y alarma por flotante.
     */
    fun evaluate(ctx: Context) {
        val st = SignalStore(ctx)
        val q = st.quote ?: return
        val trades = st.trades()

        // Flotante en directo (Myfxbook corregido a mano + operaciones manuales)
        if (st.mfxOn || st.manualTrades().isNotEmpty()) {
            val f = trades.sumOf { it.live(q) }
            val byAcc = trades.groupBy { it.acc }
            val byPage = trades.groupBy { it.page }
            if (byPage.size > 1) st.mfxDetail = byPage.entries.joinToString("\n") { (a, l) -> "• ${Accounts.label(a)}: ${Notifier.money(l.sumOf { it.live(q) })}" }
            st.setFloating(f, st.floatingUpdated)

            // Alarma por flotante: suena una vez al cruzar el límite; se rearma al recuperarse.
            val lim = st.floatAlarmLimit
            if (st.floatAlarmOn && lim > 0) {
                if (!st.floatAlarmFired && f <= -lim) {
                    st.floatAlarmFired = true
                    Alarm.start(ctx, "Flotante ${Notifier.money(f)}", "Ha pasado tu límite de −${Notifier.money(lim).drop(1)}")
                } else if (st.floatAlarmFired && f > -lim + maxOf(5.0, lim * 0.05)) st.floatAlarmFired = false
            }
        }

        // Órdenes pendientes que entran en mercado
        if (q.closed) return
        val orders = st.orders()
        val fired = st.ordFired.toMutableSet()
        fired.retainAll(orders.map { it.key }.toSet())
        for (o in orders) {
            if (!o.gold || o.key in fired || o.key in st.ordOff || !o.reached(q)) continue
            fired.add(o.key)
            val title = "✅ ${o.type} ${fmtPrice(o.price)} ha entrado"
            val text = "${fmtLots(o.lots)} lotes · ${o.acc}"
            val news = News.blocking(st)
            when {
                news != null -> Notifier.quiet(ctx, title, "$text · en silencio por ${news.name}")
                o.key in st.ordAlarm -> Alarm.start(ctx, title, text)
                else -> Notifier.alert(ctx, title, text)
            }
            st.log("Entró ${o.type} ${fmtPrice(o.price)}")
        }
        st.ordFired = fired
    }

    fun fmtPrice(v: Double): String = java.text.NumberFormat.getNumberInstance(java.util.Locale("es", "ES"))
        .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)

    fun fmtLots(v: Double): String = java.text.NumberFormat.getNumberInstance(java.util.Locale("es", "ES"))
        .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)
}
