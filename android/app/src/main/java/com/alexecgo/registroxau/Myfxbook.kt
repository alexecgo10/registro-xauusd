package com.alexecgo.registroxau

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Una cuenta de Myfxbook con su flotante (ya en dólares). */
data class MfxAccount(val id: String, val name: String, val number: String, val balance: Double, val equity: Double, val updated: String, val k: Double = 1.0) {
    val floating get() = equity - balance
}

/**
 * Lee el flotante de tus cuentas desde la API de Myfxbook.
 * Myfxbook se sincroniza con MT5 cada pocos minutos: el dato llega con ese retraso.
 */
object Myfxbook {
    private const val API = "https://www.myfxbook.com/api/"
    private const val EVERY_OPEN_MS = 60_000L      // con operaciones abiertas
    private const val EVERY_IDLE_MS = 5 * 60_000L  // sin nada abierto

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SenalesXAU")
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Inicia sesión y guarda la sesión. Lanza una excepción con un mensaje legible si falla. */
    fun login(ctx: Context, email: String, password: String) {
        val r = get("${API}login.json?email=${enc(email)}&password=${enc(password)}")
        if (r.optBoolean("error", true)) throw Exception(r.optString("message", "No se pudo iniciar sesión"))
        val st = SignalStore(ctx)
        st.mfxEmail = email; st.mfxPassword = password; st.mfxSession = r.getString("session")
    }

    /** Descarga las cuentas. Si la sesión caducó (cambia la IP del móvil), vuelve a entrar sola. */
    fun accounts(ctx: Context): List<MfxAccount> {
        val st = SignalStore(ctx)
        if (st.mfxSession.isEmpty()) login(ctx, st.mfxEmail, st.mfxPassword)
        var r = get("${API}get-my-accounts.json?session=${enc(st.mfxSession)}")
        if (r.optBoolean("error", false)) {
            login(ctx, st.mfxEmail, st.mfxPassword)
            r = get("${API}get-my-accounts.json?session=${enc(st.mfxSession)}")
            if (r.optBoolean("error", false)) throw Exception(r.optString("message", "Error de Myfxbook"))
        }
        val arr = r.getJSONArray("accounts")
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val cur = a.optString("currency", "USD")
            val k = if (cur.contains("USC", true) || cur.contains("cent", true)) 0.01 else 1.0 // cuentas en céntimos
            MfxAccount(
                a.optString("id"), a.optString("name"), a.optString("accountId"),
                a.optDouble("balance", 0.0) * k, a.optDouble("equity", 0.0) * k, a.optString("lastUpdateDate", ""), k
            )
        }
    }

    private fun lotsOf(o: JSONObject): Double =
        o.optJSONObject("sizing")?.optString("value")?.replace(",", ".")?.toDoubleOrNull() ?: o.optDouble("sizing", 0.0)

    /** Operaciones abiertas de una cuenta (null si Myfxbook falla). */
    private fun openTrades(ctx: Context, acc: MfxAccount): List<Trade>? = try {
        val r = get("${API}get-open-trades.json?session=${enc(SignalStore(ctx).mfxSession)}&id=${enc(acc.id)}")
        if (r.optBoolean("error", false)) null else {
            val arr = r.optJSONArray("openTrades") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trade(acc.name, o.optString("symbol"), o.optString("action").startsWith("Buy", true),
                    lotsOf(o), o.optDouble("openPrice"), o.optDouble("profit", 0.0) * acc.k, acc.k,
                    key = "${acc.id}|${o.optString("action")}|${o.optDouble("openPrice")}|${o.optString("openTime")}")
            }
        }
    } catch (e: Exception) { null }

    /** Órdenes pendientes (límite/stop) de una cuenta. */
    private fun openOrders(ctx: Context, acc: MfxAccount): List<Order>? = try {
        val r = get("${API}get-open-orders.json?session=${enc(SignalStore(ctx).mfxSession)}&id=${enc(acc.id)}")
        if (r.optBoolean("error", false)) null else {
            val arr = r.optJSONArray("openOrders") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val type = o.optString("action")
                val price = o.optDouble("openPrice")
                Order("${acc.id}|$type|$price|${o.optString("openTime")}", acc.name, o.optString("symbol"), type, lotsOf(o), price)
            }
        }
    } catch (e: Exception) { null }

    /** Una lectura de Myfxbook: cuentas, operaciones abiertas, órdenes pendientes y flotante. */
    fun refresh(ctx: Context): String {
        val st = SignalStore(ctx)
        return try {
            val list = accounts(ctx)
            st.mfxAccounts = list.joinToString("|") { "${it.id}~${it.name} (${it.number})" }
            val sel = if (st.mfxAccount == "all") list else list.filter { it.id == st.mfxAccount }.ifEmpty { list.take(1) }
            val trades = mutableListOf<Trade>(); val orders = mutableListOf<Order>()
            var allOk = true
            val per = sel.map { a ->
                val t = openTrades(ctx, a)
                openOrders(ctx, a)?.let { orders += it }
                if (t != null) trades += t else allOk = false
                a to (t?.sumOf { it.profit } ?: a.floating)
            }
            if (allOk) st.saveTrades(trades)   // si alguna cuenta falla, se mantiene la lista anterior
            st.saveOrders(orders)
            st.mfxDetail = per.joinToString("\n") { (a, v) -> "• ${a.name}: ${Notifier.money(v)}" }
            val upd = sel.maxOfOrNull { it.updated } ?: ""
            st.noteMfxUpdate(upd)
            st.setFloating(per.sumOf { it.second }, upd)
            ""
        } catch (e: Exception) {
            st.mfxError = e.message ?: "Error"
            e.message ?: "Error"
        }
    }

    /** Vigilante en segundo plano mientras el lector de notificaciones está activo. */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (thread != null) return
        thread = HandlerThread("vigilante").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.post(loop)
    }

    fun stop() {
        thread?.quitSafely(); thread = null; handler = null
    }

    /** La app está en pantalla: precio cada pocos segundos. */
    @Volatile var uiVisible = false
    @Volatile private var forceMfx = false
    private var lastMfx = 0L

    /** Fuerza una lectura ahora (al abrir la app, al conectar…). */
    @Volatile var forceNews = false

    fun kick(ctx: Context) {
        appCtx = ctx.applicationContext
        forceMfx = true
        if (handler == null || thread?.isAlive != true) { thread = null; start(ctx) }
        else { handler!!.removeCallbacks(loop); handler!!.post(loop) }
    }

    private val loop = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            val st = SignalStore(c)
            val mfx = st.mfxOn && st.mfxEmail.isNotEmpty()
            val now = System.currentTimeMillis()
            try {
                if (mfx) {
                    val busy = st.trades().isNotEmpty() || st.orders().isNotEmpty() || st.state() != null
                    if (forceMfx || now - lastMfx >= (if (busy) EVERY_OPEN_MS else EVERY_IDLE_MS)) {
                        forceMfx = false; lastMfx = now; refresh(c)
                    }
                } else if (st.rawTrades().isNotEmpty() || st.orders().isNotEmpty()) {
                    st.saveTrades(emptyList()); st.saveOrders(emptyList())
                    if (st.manualTrades().isEmpty()) st.clearFloating()
                }
                val watching = st.trades().isNotEmpty() || (mfx && st.orders().isNotEmpty())
                if (uiVisible || watching) {
                    Market.fetchQuote()?.let { st.quote = it }
                    Market.evaluate(c)
                }
                Notifier.showOngoing(c)
            } catch (e: Exception) { }
            try {
                if (forceNews) { forceNews = false; News.fetchIfDue(c, force = true) }
                News.tick(c)
            } catch (e: Exception) { }
            c.sendBroadcast(android.content.Intent(SignalListenerService.ACTION_CHANGED).setPackage(c.packageName))
            val q = st.quote
            val next = when {
                uiVisible -> 3_000L
                st.trades().isNotEmpty() || (mfx && st.orders().isNotEmpty()) -> if (q?.closed == true) 120_000L else st.bgInterval * 1000L
                else -> 60_000L
            }
            handler?.postDelayed(this, next)
        }
    }
}
