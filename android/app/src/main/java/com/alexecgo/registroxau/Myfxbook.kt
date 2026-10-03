package com.alexecgo.registroxau

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
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

    /** Suma el "beneficio neto" de las operaciones abiertas (lo mismo que el Total de la web). */
    private fun openProfit(ctx: Context, acc: MfxAccount, k: Double): Double? = try {
        val st = SignalStore(ctx)
        val r = get("${API}get-open-trades.json?session=${enc(st.mfxSession)}&id=${enc(acc.id)}")
        if (r.optBoolean("error", false)) null else {
            val arr = r.optJSONArray("openTrades")
            if (arr == null) null else (0 until arr.length()).sumOf { arr.getJSONObject(it).optDouble("profit", 0.0) } * k
        }
    } catch (e: Exception) { null }

    /** Una lectura: guarda el flotante de la cuenta elegida (o la suma) y actualiza la isla. */
    fun refresh(ctx: Context): String {
        val st = SignalStore(ctx)
        return try {
            val list = accounts(ctx)
            st.mfxAccounts = list.joinToString("|") { "${it.id}~${it.name} (${it.number})" }
            val sel = if (st.mfxAccount == "all") list else list.filter { it.id == st.mfxAccount }.ifEmpty { list.take(1) }
            // Flotante por cuenta: operaciones abiertas (como la web); si falla, equity − balance.
            val per = sel.map { a -> a to (openProfit(ctx, a, a.k) ?: a.floating) }
            val f = per.sumOf { it.second }
            st.mfxDetail = per.joinToString("\n") { (a, v) -> "• ${a.name}: ${Notifier.money(v)}" }
            st.setFloating(f, sel.maxOfOrNull { it.updated } ?: "")
            Notifier.showOngoing(ctx)
            ""
        } catch (e: Exception) {
            st.mfxError = e.message ?: "Error"
            e.message ?: "Error"
        }
    }

    /** Bucle en segundo plano mientras el lector de notificaciones está activo. */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (thread != null) return
        thread = HandlerThread("myfxbook").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.post(loop)
    }

    fun stop() {
        thread?.quitSafely(); thread = null; handler = null
    }

    /** Fuerza una lectura ahora (p. ej. al conectar desde la app). */
    fun kick(ctx: Context) {
        appCtx = ctx.applicationContext
        if (handler == null) start(ctx) else { handler!!.removeCallbacks(loop); handler!!.post(loop) }
    }

    private val loop = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            val st = SignalStore(c)
            var next = EVERY_IDLE_MS
            if (st.mfxOn && st.mfxEmail.isNotEmpty()) {
                refresh(c)
                if (kotlin.math.abs(st.floating ?: 0.0) > 0.005 || st.state() != null) next = EVERY_OPEN_MS
            } else if (st.floating != null) { st.clearFloating(); Notifier.showOngoing(c) }
            c.sendBroadcast(android.content.Intent(SignalListenerService.ACTION_CHANGED).setPackage(c.packageName))
            handler?.postDelayed(this, next)
        }
    }
}
