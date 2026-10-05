package com.alexecgo.registroxau

/** Lo que significa un mensaje del canal de señales. */
sealed class SignalEvent {
    data class Open(val side: String, val price: String) : SignalEvent()
    data class Average(val price: String) : SignalEvent()
    data class CloseAverage(val price: String) : SignalEvent()
    object CloseAll : SignalEvent()
}

object SignalParser {
    private const val PRICE = "(\\d{3,6}(?:[.,]\\d+)?)"

    private val closeAll = Regex("(?i)\\bcerramos\\s+todo")
    private val closeAverage = Regex("(?i)cerramos\\s+el\\s+promedio\\D*?$PRICE")
    private val average = Regex("(?i)^\\s*promedios?\\s+$PRICE")
    // Al principio de una línea, o citado (p. ej. "LIFT.SIGNALS fijó “XAUUSD BUY 4156…”").
    private val open = Regex("(?im)(?:^|[“\"«'])\\s*XAUUSD\\s+(BUY|SELL)\\s+$PRICE")

    /** Devuelve el evento que contiene el texto, o null si no es un mensaje de señal. */
    fun parse(text: String?): SignalEvent? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (closeAll.containsMatchIn(t)) return SignalEvent.CloseAll
        closeAverage.find(t)?.let { return SignalEvent.CloseAverage(norm(it.groupValues[1])) }
        average.find(t)?.let { return SignalEvent.Average(norm(it.groupValues[1])) }
        open.find(t)?.let { return SignalEvent.Open(it.groupValues[1].uppercase(), norm(it.groupValues[2])) }
        return null
    }

    private fun norm(p: String) = p.replace(',', '.')
}
