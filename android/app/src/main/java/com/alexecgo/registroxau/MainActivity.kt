package com.alexecgo.registroxau

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.androidbrowserhelper.trusted.QualityEnforcer
import com.google.androidbrowserhelper.trusted.TwaLauncher

class MainActivity : AppCompatActivity() {

    private lateinit var store: SignalStore
    private var twa: TwaLauncher? = null

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SignalStore(this)
        Notifier.ensureChannels(this)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotifPerm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } else openAppNotificationSettings()
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.btnLive).setOnClickListener {
            try {
                startActivity(Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (e: Exception) { openAppNotificationSettings() }
        }
        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            openRegistro()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE))
        }

        val alarm = findViewById<MaterialSwitch>(R.id.switchAlarm)
        alarm.isChecked = store.alarmMode
        alarm.setOnCheckedChangeListener { _, on ->
            store.alarmMode = on
            Toast.makeText(this, if (on) "Modo alarma activado" else "Modo alarma desactivado", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.btnFullScreen).setOnClickListener {
            try {
                startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").setData(Uri.parse("package:$packageName")))
            } catch (e: Exception) { openAppNotificationSettings() }
        }
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            Toast.makeText(this, "Bloquea el móvil: sonará en 10 segundos", Toast.LENGTH_LONG).show()
            android.os.Handler(mainLooper).postDelayed({
                Alarm.start(this, "Prueba de alarma", "Así sonará cuando llegue una señal")
            }, 10_000)
        }

        setupMyfxbook()

        val filter = findViewById<EditText>(R.id.inputTitle)
        val sound = findViewById<MaterialSwitch>(R.id.switchSound)
        filter.setText(store.titleFilter)
        sound.isChecked = store.soundOn
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            store.titleFilter = filter.text.toString()
            store.soundOn = sound.isChecked
            filter.setText(store.titleFilter)
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
        }

        // Pruebas: pasan por el mismo camino que un mensaje real de Telegram.
        findViewById<Button>(R.id.btnTestOpen).setOnClickListener { test("XAUUSD BUY 4160\n\norientativo:\nTP✅") }
        findViewById<Button>(R.id.btnTestAvg).setOnClickListener { test("Promedio 4150") }
        findViewById<Button>(R.id.btnTestCloseAvg).setOnClickListener { test("Cerramos el promedio este 4150\n\n-120 pips") }
        findViewById<Button>(R.id.btnTestClose).setOnClickListener { test("cerramos todo✅") }
    }

    /** Abre el registro web dentro de la app, a pantalla completa (con la sesión de Google de Chrome). */
    private fun openRegistro() {
        val band = ContextCompat.getColor(this, R.color.band)
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(band).setNavigationBarColor(band).build()
        val builder = TrustedWebActivityIntentBuilder(Uri.parse(WEB_URL)).setDefaultColorSchemeParams(colors)
        try {
            twa?.destroy()
            twa = TwaLauncher(this).also { it.launch(builder, QualityEnforcer(), null, null) }
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL)))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        twa?.destroy()
    }

    private fun test(text: String) {
        SignalListenerService.process(this, text, test = true)
        refresh()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, changed, IntentFilter(SignalListenerService.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(changed) } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Notifier.showOngoing(this)
        refresh()
    }

    private fun refresh() {
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val notifOn = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        findViewById<TextView>(R.id.txtAccess).text =
            (if (listenerOn) "✅" else "❌") + "  Acceso a notificaciones (leer Telegram)"
        findViewById<Button>(R.id.btnAccess).visibility = if (listenerOn) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.txtNotif).text =
            (if (notifOn) "✅" else "❌") + "  Permiso para mostrar notificaciones"
        findViewById<Button>(R.id.btnNotifPerm).visibility = if (notifOn) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.txtBattery).text =
            (if (batteryOk) "✅" else "⚠️") + "  Batería sin restricciones"
        findViewById<Button>(R.id.btnBattery).visibility = if (batteryOk) android.view.View.GONE else android.view.View.VISIBLE

        // Android 16+: "actualizaciones en directo" (chip arriba / isla). En versiones anteriores no aplica.
        val live = canPostPromoted()
        findViewById<TextView>(R.id.txtLive).visibility = if (live == null) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.txtLive).text = (if (live == true) "✅" else "❌") + "  Actualizaciones en directo (isla)"
        findViewById<Button>(R.id.btnLive).visibility = if (live == false) android.view.View.VISIBLE else android.view.View.GONE

        val fsOk = Build.VERSION.SDK_INT < 34 ||
            getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        findViewById<Button>(R.id.btnFullScreen).visibility =
            if (store.alarmMode && !fsOk) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<Button>(R.id.btnTestAlarm).visibility =
            if (store.alarmMode) android.view.View.VISIBLE else android.view.View.GONE

        refreshMyfxbook()

        val s = store.state()
        findViewById<TextView>(R.id.txtSignalTitle).text = s?.let { Notifier.title(it) } ?: "Sin señal abierta"
        findViewById<TextView>(R.id.txtSignalBody).text = s?.let { Notifier.body(it) }
            ?: "Cuando ${store.titleFilter} mande «XAUUSD BUY/SELL precio» aparecerá aquí y en una notificación fija."
        findViewById<Button>(R.id.btnClose).visibility = if (s != null) android.view.View.VISIBLE else android.view.View.GONE

        val h = store.history()
        findViewById<TextView>(R.id.txtHistory).text = if (h.isEmpty()) "Todavía no hay movimientos." else h.joinToString("\n")
    }

    // ---------- Myfxbook ----------
    private var mfxIds: List<String> = emptyList()
    private var fillingSpinner = false

    private fun setupMyfxbook() {
        val sw = findViewById<MaterialSwitch>(R.id.switchMfx)
        val email = findViewById<EditText>(R.id.inputMfxEmail)
        val pass = findViewById<EditText>(R.id.inputMfxPass)
        email.setText(store.mfxEmail)
        if (store.mfxPassword.isNotEmpty()) pass.hint = "Contraseña guardada (escríbela para cambiarla)"
        sw.isChecked = store.mfxOn
        sw.setOnCheckedChangeListener { _, on ->
            store.mfxOn = on
            if (on && store.mfxEmail.isNotEmpty()) Myfxbook.kick(this) else Notifier.showOngoing(this)
            refresh()
        }
        findViewById<Button>(R.id.btnMfx).setOnClickListener {
            val e = email.text.toString().trim()
            val p = pass.text.toString().ifEmpty { store.mfxPassword }
            if (e.isEmpty() || p.isEmpty()) { toast("Escribe el email y la contraseña de Myfxbook"); return@setOnClickListener }
            findViewById<TextView>(R.id.txtMfx).text = "Conectando…"
            Thread {
                val err = try { Myfxbook.login(this, e, p); store.mfxOn = true; Myfxbook.refresh(this) } catch (x: Exception) { x.message ?: "Error" }
                runOnUiThread {
                    if (err.isEmpty()) {
                        pass.setText(""); pass.hint = "Contraseña guardada (escríbela para cambiarla)"
                        sw.isChecked = true; Myfxbook.kick(this); toast("Conectado a Myfxbook")
                    } else toast("Myfxbook: $err")
                    refresh()
                }
            }.start()
        }
        findViewById<Spinner>(R.id.spinnerMfx).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (fillingSpinner) return
                val sel = mfxIds.getOrNull(pos) ?: return
                if (sel != store.mfxAccount) { store.mfxAccount = sel; store.clearFloating(); Myfxbook.kick(this@MainActivity) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshMyfxbook() {
        val on = store.mfxOn
        findViewById<android.view.View>(R.id.boxMfx).visibility =
            if (on || store.mfxEmail.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val f = store.floating
        findViewById<TextView>(R.id.txtMfx).text = when {
            store.mfxEmail.isEmpty() -> "Conecta tu cuenta de Myfxbook para ver el flotante en la isla. Se actualiza cada minuto, con el retraso con el que Myfxbook se sincroniza con MT5."
            !on -> "Desactivado."
            store.mfxError.isNotEmpty() && f == null -> "⚠️ ${store.mfxError}"
            f == null -> "Esperando datos de Myfxbook…"
            else -> "Flotante ahora: ${Notifier.money(f)}" + store.floatingUpdated.let { if (it.isNotBlank()) " · actualizado en Myfxbook $it" else "" }
        }
        // Selector de cuenta
        val items = store.mfxAccounts.split("|").filter { it.contains("~") }.map { it.substringBefore("~") to it.substringAfter("~") }
        mfxIds = listOf("all") + items.map { it.first }
        val labels = listOf("Todas (suma)") + items.map { it.second }
        val sp = findViewById<Spinner>(R.id.spinnerMfx)
        fillingSpinner = true
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(mfxIds.indexOf(store.mfxAccount).coerceAtLeast(0))
        sp.post { fillingSpinner = false }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** null si el sistema no tiene esta función (Android 15 o anterior). */
    private fun canPostPromoted(): Boolean? = try {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.javaClass.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
    } catch (e: Exception) { null }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    companion object {
        const val WEB_URL = "https://alexecgo10.github.io/registro-xauusd/"
        @Suppress("unused") private val LISTENER = ComponentName("com.alexecgo.registroxau", "com.alexecgo.registroxau.SignalListenerService")
    }
}
