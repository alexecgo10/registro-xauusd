package com.alexecgo.registroxau

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Lee la pestaña "Operaciones" de MT5 (móvil) a partir de una captura. */
object TradeShot {
    data class Row(val sym: String, val buy: Boolean, val lots: Double, val open: Double)

    fun read(ctx: Context, uri: Uri, done: (List<Row>?, String?) -> Unit) {
        val img = try { InputImage.fromFilePath(ctx, uri) } catch (e: Exception) { done(null, "No se pudo abrir la imagen"); return }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
            .addOnSuccessListener { t -> done(parse(rows(t)), null) }
            .addOnFailureListener { e -> done(null, e.message ?: "No se pudo leer la captura") }
    }

    /** Reconstruye las filas de la pantalla juntando los trozos de texto que están a la misma altura. */
    fun rows(t: Text): List<String> {
        val lines = t.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.centerY() }
        val out = mutableListOf<MutableList<Text.Line>>()
        for (l in lines) {
            val last = out.lastOrNull()
            val ref = last?.first()?.boundingBox
            if (ref != null && kotlin.math.abs(l.boundingBox!!.centerY() - ref.centerY()) < ref.height() * 0.6) last.add(l)
            else out.add(mutableListOf(l))
        }
        return out.map { g -> g.sortedBy { it.boundingBox!!.left }.joinToString("  ") { it.text } }
    }

    private val header = Regex("(?i)\\b([A-Z]{3,}[A-Z0-9.\\-]*)\\s*,?\\s*(buy|sell)\\s+(\\d+(?:[.,]\\d+)?)")
    private val num = Regex("\\d+[.,]\\d{2,3}")

    /** "4 531.05" → "4531.05" */
    private fun joinThousands(s: String) = s.replace(Regex("(\\d) (?=\\d{3}\\b)"), "$1")

    fun parse(rows: List<String>): List<Row> {
        val out = mutableListOf<Row>()
        rows.forEachIndexed { i, r ->
            val h = header.find(r) ?: return@forEachIndexed
            val lots = h.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@forEachIndexed
            // Precio de entrada: primer número de la fila siguiente ("4 531.05 → 4 156.06").
            val cand = listOfNotNull(rows.getOrNull(i + 1), rows.getOrNull(i + 2))
                .map { joinThousands(it) }
                .firstOrNull { header.find(it) == null && num.containsMatchIn(it) } ?: return@forEachIndexed
            val open = num.findAll(cand).map { it.value.replace(',', '.').toDouble() }.firstOrNull { it > 100 } ?: return@forEachIndexed
            out += Row(h.groupValues[1], h.groupValues[2].equals("buy", true), lots, open)
        }
        return out
    }
}
