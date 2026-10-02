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
import android.widget.EditText
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
        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            openRegistro()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE))
        }

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

        val s = store.state()
        findViewById<TextView>(R.id.txtSignalTitle).text = s?.let { Notifier.title(it) } ?: "Sin señal abierta"
        findViewById<TextView>(R.id.txtSignalBody).text = s?.let { Notifier.body(it) }
            ?: "Cuando ${store.titleFilter} mande «XAUUSD BUY/SELL precio» aparecerá aquí y en una notificación fija."
        findViewById<Button>(R.id.btnClose).visibility = if (s != null) android.view.View.VISIBLE else android.view.View.GONE

        val h = store.history()
        findViewById<TextView>(R.id.txtHistory).text = if (h.isEmpty()) "Todavía no hay movimientos." else h.joinToString("\n")
    }

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
