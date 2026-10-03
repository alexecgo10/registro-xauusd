package com.alexecgo.registroxau

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var store: SignalStore
    private lateinit var web: WebView
    private var webLoaded = false
    private var googleEmail = ""
    private var tab = R.id.tabSignal

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SignalStore(this)
        Notifier.ensureChannels(this)
        web = findViewById(R.id.web)

        findViewById<BottomNavigationView>(R.id.nav).setOnItemSelectedListener { showTab(it.itemId); true }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab == R.id.tabWeb && web.canGoBack() -> web.goBack()
                    tab != R.id.tabSignal -> findViewById<BottomNavigationView>(R.id.nav).selectedItemId = R.id.tabSignal
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        setupSignal()
        setupSettings()
        setupMyfxbook()
        loadWeb() // se carga en segundo plano: la pestaña Registro abre al instante
    }

    // ---------- Pestañas ----------
    private fun showTab(id: Int) {
        tab = id
        findViewById<View>(R.id.pageSignal).visibility = if (id == R.id.tabSignal) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageHistory).visibility = if (id == R.id.tabHistory) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageSettings).visibility = if (id == R.id.tabSettings) View.VISIBLE else View.GONE
        web.visibility = if (id == R.id.tabWeb) View.VISIBLE else View.GONE
        if (id == R.id.tabWeb) loadWeb()
        refresh()
    }

    // ---------- Señal ----------
    private fun setupSignal() {
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE))
        }
        val alarm = findViewById<MaterialSwitch>(R.id.switchAlarm)
        alarm.isChecked = store.alarmMode
        alarm.setOnCheckedChangeListener { _, on ->
            store.alarmMode = on
            toast(if (on) "Modo alarma activado" else "Modo alarma desactivado")
            refresh()
        }
    }

    // ---------- Ajustes ----------
    private fun setupSettings() {
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
        findViewById<Button>(R.id.btnFullScreen).setOnClickListener {
            try {
                startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").setData(Uri.parse("package:$packageName")))
            } catch (e: Exception) { openAppNotificationSettings() }
        }
        findViewById<Button>(R.id.btnGoogle).setOnClickListener {
            if (googleEmail.isNotEmpty()) {
                loadWeb(); web.evaluateJavascript("window.appSignOut && window.appSignOut()", null)
            } else googleSignIn()
        }

        val filter = findViewById<EditText>(R.id.inputTitle)
        val sound = findViewById<MaterialSwitch>(R.id.switchSound)
        filter.setText(store.titleFilter)
        sound.isChecked = store.soundOn
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            store.titleFilter = filter.text.toString()
            store.soundOn = sound.isChecked
            filter.setText(store.titleFilter)
            toast("Guardado")
        }

        findViewById<Button>(R.id.btnTestOpen).setOnClickListener { test("XAUUSD BUY 4160\n\norientativo:\nTP✅") }
        findViewById<Button>(R.id.btnTestAvg).setOnClickListener { test("Promedio 4150") }
        findViewById<Button>(R.id.btnTestCloseAvg).setOnClickListener { test("Cerramos el promedio este 4150\n\n-120 pips") }
        findViewById<Button>(R.id.btnTestClose).setOnClickListener { test("cerramos todo✅") }
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            toast("Bloquea el móvil: sonará en 10 segundos")
            android.os.Handler(mainLooper).postDelayed({
                Alarm.start(this, "Prueba de alarma", "Así sonará cuando llegue una señal")
            }, 10_000)
        }
    }

    private fun test(text: String) {
        SignalListenerService.process(this, text, test = true)
        refresh()
    }

    // ---------- Myfxbook ----------
    private var mfxIds: List<String> = emptyList()
    private var fillingSpinner = false

    private fun setupMyfxbook() {
        val sw = findViewById<MaterialSwitch>(R.id.switchMfx)
        val email = findViewById<EditText>(R.id.inputMfxEmail)
        val pass = findViewById<EditText>(R.id.inputMfxPass)
        email.setText(store.mfxEmail)
        if (store.mfxPassword.isNotEmpty()) pass.hint = "Contraseña guardada"
        sw.isChecked = store.mfxOn
        sw.setOnCheckedChangeListener { _, on ->
            store.mfxOn = on
            if (on && store.mfxEmail.isNotEmpty()) Myfxbook.kick(this) else Notifier.showOngoing(this)
            refresh()
        }
        findViewById<Button>(R.id.btnMfx).setOnClickListener {
            val e = email.text.toString().trim()
            val p = pass.text.toString().ifEmpty { store.mfxPassword }
            if (e.isEmpty() || p.isEmpty()) { toast("Falta el email o la contraseña"); return@setOnClickListener }
            findViewById<TextView>(R.id.txtMfx).text = "Conectando…"
            Thread {
                val err = try { Myfxbook.login(this, e, p); store.mfxOn = true; Myfxbook.refresh(this) } catch (x: Exception) { x.message ?: "Error" }
                runOnUiThread {
                    if (err.isEmpty()) {
                        pass.setText(""); pass.hint = "Contraseña guardada"
                        sw.isChecked = true; Myfxbook.kick(this); toast("Conectado a Myfxbook")
                    } else toast("Myfxbook: $err")
                    refresh()
                }
            }.start()
        }
        findViewById<Spinner>(R.id.spinnerMfx).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (fillingSpinner) return
                val sel = mfxIds.getOrNull(pos) ?: return
                if (sel != store.mfxAccount) { store.mfxAccount = sel; store.clearFloating(); Myfxbook.kick(this@MainActivity) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshMyfxbook() {
        val f = store.floating
        findViewById<TextView>(R.id.txtMfx).text = when {
            store.mfxEmail.isEmpty() -> "Sin conectar"
            !store.mfxOn -> "Desactivado"
            store.mfxError.isNotEmpty() && f == null -> "⚠️ ${store.mfxError}"
            f == null -> "Esperando datos…"
            else -> "Flotante: ${Notifier.money(f)}" + store.floatingUpdated.let { if (it.isNotBlank()) " · $it" else "" }
        }
        val items = store.mfxAccounts.split("|").filter { it.contains("~") }.map { it.substringBefore("~") to it.substringAfter("~") }
        mfxIds = listOf("all") + items.map { it.first }
        val labels = listOf("Todas (suma)") + items.map { it.second }
        val sp = findViewById<Spinner>(R.id.spinnerMfx)
        fillingSpinner = true
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(mfxIds.indexOf(store.mfxAccount).coerceAtLeast(0))
        sp.post { fillingSpinner = false }

        // Tarjeta de flotante en la pantalla principal
        val show = store.mfxOn && f != null && kotlin.math.abs(f) > 0.005
        findViewById<View>(R.id.cardFloat).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val t = findViewById<TextView>(R.id.txtFloat)
            t.text = Notifier.money(f!!)
            t.setTextColor(if (f < 0) 0xFFF0453A.toInt() else 0xFF22A06B.toInt())
            findViewById<TextView>(R.id.txtFloatInfo).text = listOfNotNull(
                store.mfxDetail.takeIf { store.mfxAccount == "all" && it.contains("\n") },
                store.floatingUpdated.takeIf { it.isNotBlank() }?.let { "Myfxbook $it" }
            ).joinToString("\n")
        }
    }

    // ---------- Registro (web dentro de la app) ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWeb() {
        if (webLoaded) return
        webLoaded = true
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        web.addJavascriptInterface(Bridge(), "SenalesApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "alexecgo10.github.io") return false
                startActivity(Intent(Intent.ACTION_VIEW, u)); return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { pickFile.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")); true }
                catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.loadUrl(WEB_URL)
    }

    /** Lo que la web puede pedirle a la app. */
    inner class Bridge {
        @JavascriptInterface fun googleSignIn() = runOnUiThread { this@MainActivity.googleSignIn() }
        @JavascriptInterface fun onAuth(email: String) = runOnUiThread { googleEmail = email; refresh() }
        @JavascriptInterface fun saveFile(name: String, content: String) = runOnUiThread { saveDownload(name, content) }
    }

    /** Inicio de sesión de Google nativo; el token se pasa al registro. */
    private fun googleSignIn() {
        val clientId = getString(R.string.google_web_client_id)
        if (!clientId.endsWith(".apps.googleusercontent.com")) {
            toast("Falta configurar el inicio de sesión de Google"); return
        }
        loadWeb()
        val req = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        CredentialManager.create(this).getCredentialAsync(this, req, null, ContextCompat.getMainExecutor(this),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val token = GoogleIdTokenCredential.createFrom(c.data).idToken
                        web.evaluateJavascript("window.nativeSignIn && window.nativeSignIn(${JSONObject.quote(token)})", null)
                    } else toast("Respuesta de Google no válida")
                }
                override fun onError(e: GetCredentialException) {
                    if (e is GetCredentialCancellationException) return
                    toast("Google: ${e.message ?: e.type}")
                    web.evaluateJavascript("window.nativeSignInError && window.nativeSignInError(${JSONObject.quote(e.message ?: "Error")})", null)
                }
            })
    }

    /** Guarda en Descargas lo que la web quiere descargar (copias de seguridad). */
    private fun saveDownload(name: String, content: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception("sin acceso")
                contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            } else {
                val f = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                f.writeText(content)
            }
            toast("Guardado en Descargas: $name")
        } catch (e: Exception) { toast("No se pudo guardar: ${e.message}") }
    }

    // ---------- Ciclo de vida ----------
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        if (store.mfxOn && store.mfxEmail.isNotEmpty()) Myfxbook.kick(this)
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

    private fun row(text: Int, button: Int, ok: Boolean?, label: String) {
        findViewById<TextView>(text).visibility = if (ok == null) View.GONE else View.VISIBLE
        findViewById<TextView>(text).text = (if (ok == true) "✅" else "❌") + "  " + label
        findViewById<Button>(button).visibility = if (ok == false) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val notifOn = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val fsOk: Boolean? = if (Build.VERSION.SDK_INT < 34) null
            else getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

        row(R.id.txtAccess, R.id.btnAccess, listenerOn, "Acceso a notificaciones")
        row(R.id.txtNotif, R.id.btnNotifPerm, notifOn, "Notificaciones")
        row(R.id.txtBattery, R.id.btnBattery, batteryOk, "Batería sin restricciones")
        row(R.id.txtLive, R.id.btnLive, canPostPromoted(), "Actualizaciones en directo (isla)")
        row(R.id.txtFullScreen, R.id.btnFullScreen, fsOk, "Alarma a pantalla completa")

        findViewById<TextView>(R.id.txtGoogle).text = if (googleEmail.isNotEmpty()) googleEmail else "Sin sesión"
        findViewById<Button>(R.id.btnGoogle).text = if (googleEmail.isNotEmpty()) "Cerrar sesión" else "Iniciar sesión con Google"

        refreshMyfxbook()

        val s = store.state()
        findViewById<TextView>(R.id.txtSignalTitle).text = s?.let { Notifier.title(it) } ?: "Sin señal abierta"
        val body = findViewById<TextView>(R.id.txtSignalBody)
        body.text = s?.let { Notifier.body(it) } ?: ""
        body.visibility = if (s != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnClose).visibility = if (s != null) View.VISIBLE else View.GONE

        val h = store.history()
        findViewById<TextView>(R.id.txtHistory).text = if (h.isEmpty()) "—" else h.joinToString("\n")
    }

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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        const val WEB_URL = "https://alexecgo10.github.io/registro-xauusd/"
    }
}
