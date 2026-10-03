package com.alexecgo.prueba

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Pantalla mínima para probar la isla + la notificación de color. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        fun text(t: String, size: Float = 15f) = TextView(this).apply { text = t; textSize = size; setPadding(0, 16, 0, 16) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f(); refresh() } }

        box.addView(text("Prueba: isla + notificación de color", 20f))
        box.addView(text("Pulsa un botón, sal a la pantalla de inicio y mira la isla y el panel de notificaciones."))
        status = text("")
        box.addView(status)
        box.addView(button("Permitir notificaciones") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        box.addView(button("Activar actualizaciones en directo") {
            try { startActivity(Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        })
        box.addView(button("🔵 Señal BUY 4160") { Notis.side = "BUY"; Notis.price = "4160"; Notis.avgs.clear(); Notis.openedAt = System.currentTimeMillis(); Notis.show(this) })
        box.addView(button("🔴 Señal SELL 4185") { Notis.side = "SELL"; Notis.price = "4185"; Notis.avgs.clear(); Notis.openedAt = System.currentTimeMillis(); Notis.show(this) })
        box.addView(button("➕ Añadir promedio") {
            val base = Notis.price.toInt(); val step = (Notis.avgs.size + 1) * 10
            Notis.avgs.add((if (Notis.side == "BUY") base - step else base + step).toString()); Notis.show(this)
        })
        box.addView(Switch(this).apply {
            text = "Agrupar las dos notificaciones"; textSize = 15f; setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, on -> Notis.grouped = on; Notis.show(this@MainActivity) }
        })
        box.addView(button("✖ Quitar señal") { Notis.clear(this) })
        box.gravity = Gravity.TOP
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() { status.text = Notis.promotedStatus(this) }
}
