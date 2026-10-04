package com.alexecgo.registroxau

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Noticia del calendario económico. */
data class NewsEvent(
    val title: String, val country: String, val time: Long,
    val impact: String, val forecast: String, val previous: String
) {
    val key get() = "$country|$title|$time"
    val high get() = impact.equals("High", true)
    val dot get() = if (high) "🔴" else "🟠"
    val name get() = News.translate(title)

    fun toJson(): JSONObject = JSONObject().put("title", title).put("country", country).put("time", time)
        .put("impact", impact).put("forecast", forecast).put("previous", previous)

    companion object {
        fun of(o: JSONObject) = NewsEvent(o.optString("title"), o.optString("country"), o.optLong("time"),
            o.optString("impact"), o.optString("forecast"), o.optString("previous"))
    }
}

/** Calendario de Forex Factory: USD de impacto alto (y medio si se elige). */
object News {
    private const val FEED = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
    private const val EVERY_MS = 6 * 60 * 60_000L   // Forex Factory bloquea si se descarga muy a menudo
    private const val MIN_GAP_MS = 30 * 60_000L
    const val AFTER_MS = 15 * 60_000L                 // la noticia "dura" 15 min después de salir

    /** Descarga el calendario de la semana si toca. */
    fun fetchIfDue(ctx: Context, force: Boolean = false) {
        val st = SignalStore(ctx)
        val age = System.currentTimeMillis() - st.newsAt
        if (age < (if (force) MIN_GAP_MS else EVERY_MS)) return
        try {
            val c = URL(FEED).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "SenalesXAU")
            val arr = try { JSONArray(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val impact = o.optString("impact")
                if (!o.optString("country").equals("USD", true)) continue
                if (!impact.equals("High", true) && !impact.equals("Medium", true)) continue
                val t = try { java.time.OffsetDateTime.parse(o.optString("date")).toInstant().toEpochMilli() } catch (e: Exception) { continue }
                out.put(NewsEvent(o.optString("title"), "USD", t, impact, o.optString("forecast"), o.optString("previous")).toJson())
            }
            st.newsJson = out.toString()
            st.newsAt = System.currentTimeMillis()
        } catch (e: Exception) { }
    }

    /** Noticias según el filtro elegido, ordenadas por hora. */
    fun events(st: SignalStore): List<NewsEvent> = try {
        val a = JSONArray(st.newsJson)
        (0 until a.length()).map { NewsEvent.of(a.getJSONObject(it)) }
            .filter { it.high || st.newsMedium }
            .sortedBy { it.time }
    } catch (e: Exception) { emptyList() }

    /** ¿Estamos en la ventana de una noticia (X min antes … 15 min después)? */
    fun blocking(st: SignalStore, now: Long = System.currentTimeMillis()): NewsEvent? {
        if (!st.newsOn || !st.newsBlock) return null
        val before = st.newsBefore * 60_000L
        return events(st).firstOrNull { now >= it.time - before && now <= it.time + AFTER_MS }
    }

    /** Cada vuelta del vigilante: descarga si toca y avisa antes y al salir el dato. */
    fun tick(ctx: Context) {
        val st = SignalStore(ctx)
        if (!st.newsOn) return
        fetchIfDue(ctx)
        val now = System.currentTimeMillis()
        val before = st.newsBefore * 60_000L
        val done = st.newsDone.toMutableSet()
        val evs = events(st)
        done.retainAll(evs.flatMap { listOf("pre|${it.key}", "now|${it.key}") }.toSet())
        // Las noticias que coinciden a la misma hora se avisan juntas.
        evs.groupBy { it.time }.forEach { (t, group) ->
            val names = group.joinToString(" · ") { it.name }
            val dot = if (group.any { it.high }) "🔴" else "🟠"
            val detail = group.mapNotNull { e ->
                listOfNotNull(e.forecast.takeIf { it.isNotBlank() }?.let { "Prev. $it" }, e.previous.takeIf { it.isNotBlank() }?.let { "Ant. $it" })
                    .joinToString(" · ").takeIf { it.isNotBlank() }
            }.joinToString("  |  ")
            val hora = hhmm(t)
            val preKey = "pre|${group.first().key}"
            val nowKey = "now|${group.first().key}"
            if (before > 0 && preKey !in done && now >= t - before && now < t) {
                done += preKey
                Notifier.alert(ctx, "$dot En ${((t - now + 59_999) / 60_000)} min: $names", "$hora · $detail".trimEnd(' ', '·'), allowAlarm = false)
            }
            if (st.newsAtRelease && nowKey !in done && now >= t && now < t + 5 * 60_000L) {
                done += nowKey; done += preKey
                Notifier.alert(ctx, "$dot Ahora: $names", "Sale el dato · cuidado con la volatilidad", allowAlarm = false)
            }
        }
        st.newsDone = done
    }

    fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    fun dayLabel(t: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val d = f.format(Date(t)); val today = f.format(Date())
        val tomorrow = f.format(Date(System.currentTimeMillis() + 86_400_000L))
        return when (d) {
            today -> "Hoy"
            tomorrow -> "Mañana"
            else -> SimpleDateFormat("EEE d", Locale("es", "ES")).format(Date(t)).replaceFirstChar { it.uppercase() }
        }
    }

    private val NAMES = listOf(
        "ADP Non-Farm" to "Empleo ADP",
        "Non-Farm Employment Change" to "Nóminas no agrícolas (NFP)",
        "Unemployment Rate" to "Tasa de paro",
        "Average Hourly Earnings" to "Salario por hora",
        "Unemployment Claims" to "Subsidios por desempleo",
        "JOLTS Job Openings" to "Vacantes JOLTS",
        "Core CPI" to "IPC subyacente",
        "CPI" to "IPC",
        "Core PCE Price Index" to "PCE subyacente",
        "PCE Price Index" to "PCE",
        "Core PPI" to "IPP subyacente",
        "PPI" to "IPP",
        "Core Retail Sales" to "Ventas minoristas subyacentes",
        "Retail Sales" to "Ventas minoristas",
        "Advance GDP" to "PIB (avance)",
        "Prelim GDP" to "PIB (preliminar)",
        "Final GDP" to "PIB (final)",
        "ISM Manufacturing PMI" to "ISM manufacturero",
        "ISM Services PMI" to "ISM servicios",
        "Federal Funds Rate" to "Decisión de tipos Fed",
        "FOMC Statement" to "Comunicado FOMC",
        "FOMC Press Conference" to "Rueda de prensa FOMC",
        "FOMC Meeting Minutes" to "Actas FOMC",
        "FOMC Economic Projections" to "Proyecciones FOMC",
        "Fed Chair" to "Discurso presidente Fed",
        "UoM Consumer Sentiment" to "Confianza Michigan",
        "UoM Inflation Expectations" to "Expectativas inflación Michigan",
        "CB Consumer Confidence" to "Confianza del consumidor",
        "Durable Goods Orders" to "Pedidos de bienes duraderos",
        "Treasury Currency Report" to "Informe del Tesoro",
    )

    /** Nombre en español (si lo conocemos), manteniendo m/m, y/y… */
    fun translate(title: String): String {
        val hit = NAMES.firstOrNull { title.contains(it.first, true) } ?: return title
        val suffix = Regex("\\b(m/m|y/y|q/q)\\b", RegexOption.IGNORE_CASE).find(title)?.value
        return hit.second + (suffix?.let { " $it" } ?: "")
    }
}
