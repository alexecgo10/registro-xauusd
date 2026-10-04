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
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.RadioGroup
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
        setupSwipe()
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
    private var signalOpen = false

    private fun setupSignal() {
        findViewById<Button>(R.id.btnAddTrade).setOnClickListener { tradeDialog(null) }
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_CLOSE))
        }
        findViewById<View>(R.id.cardSignal).setOnClickListener {
            if (store.state()?.averages?.isNotEmpty() == true) { signalOpen = !signalOpen; refresh() }
        }
        val sched = findViewById<MaterialSwitch>(R.id.switchSched)
        sched.isChecked = store.alarmSched
        sched.setOnCheckedChangeListener { _, on -> store.alarmSched = on; refresh() }
        val dnd = findViewById<MaterialSwitch>(R.id.switchDnd)
        dnd.isChecked = store.dnd
        dnd.setOnCheckedChangeListener { _, on ->
            store.dnd = on
            if (on) Alarm.stop(this)
            toast(if (on) "No molestar activado" else "No molestar desactivado")
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

        val nOn = findViewById<MaterialSwitch>(R.id.switchNews)
        val nMed = findViewById<MaterialSwitch>(R.id.switchNewsMedium)
        val nBefore = findViewById<EditText>(R.id.inputNewsBefore)
        val nAt = findViewById<MaterialSwitch>(R.id.switchNewsAt)
        val nBlock = findViewById<MaterialSwitch>(R.id.switchNewsBlock)
        nOn.isChecked = store.newsOn; nMed.isChecked = store.newsMedium
        nBefore.setText(store.newsBefore.toString())
        nAt.isChecked = store.newsAtRelease; nBlock.isChecked = store.newsBlock
        findViewById<Button>(R.id.btnSaveNews).setOnClickListener {
            store.newsOn = nOn.isChecked; store.newsMedium = nMed.isChecked
            store.newsBefore = (nBefore.text.toString().toIntOrNull() ?: 15).coerceIn(0, 240)
            nBefore.setText(store.newsBefore.toString())
            store.newsAtRelease = nAt.isChecked; store.newsBlock = nBlock.isChecked
            Myfxbook.forceNews = true; Myfxbook.kick(this)
            toast("Guardado"); refresh()
        }

        val rg = findViewById<RadioGroup>(R.id.radioInterval)
        rg.check(when (store.bgInterval) { 5 -> R.id.int5; 20 -> R.id.int20; else -> R.id.int10 })
        rg.setOnCheckedChangeListener { _, id ->
            store.bgInterval = when (id) { R.id.int5 -> 5; R.id.int20 -> 20; else -> 10 }
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

        val from = findViewById<EditText>(R.id.inputSchedFrom)
        val to = findViewById<EditText>(R.id.inputSchedTo)
        val fl = findViewById<MaterialSwitch>(R.id.switchFloatAlarm)
        val lim = findViewById<EditText>(R.id.inputFloatLimit)
        from.setText(store.schedFrom); to.setText(store.schedTo)
        fl.isChecked = store.floatAlarmOn
        if (store.floatAlarmLimit > 0) lim.setText(store.floatAlarmLimit.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() })
        findViewById<Button>(R.id.btnSaveAlarms).setOnClickListener {
            val re = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$")
            fun norm(t: String) = re.find(t.trim())?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
            val f = norm(from.text.toString()); val t = norm(to.text.toString())
            if (f == null || t == null) { toast("Escribe las horas así: 00:00"); return@setOnClickListener }
            store.schedFrom = f; store.schedTo = t
            from.setText(f); to.setText(t)
            val l = lim.text.toString().replace(",", ".").replace("-", "").replace("$", "").trim().toDoubleOrNull() ?: 0.0
            store.floatAlarmLimit = l
            store.floatAlarmOn = fl.isChecked && l > 0
            store.floatAlarmFired = false
            fl.isChecked = store.floatAlarmOn
            toast("Guardado")
            refresh()
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
        Myfxbook.uiVisible = true
        Myfxbook.kick(this)
        ContextCompat.registerReceiver(
            this, changed, IntentFilter(SignalListenerService.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        super.onStop()
        Myfxbook.uiVisible = false
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

        refreshMarket()
        findViewById<MaterialSwitch>(R.id.switchSched).text =
            "🌙 Alarma con horario  ·  ${store.schedFrom}–${store.schedTo}"

        val s = store.state()
        val hasAvg = s?.averages?.isNotEmpty() == true
        if (!hasAvg) signalOpen = false
        findViewById<TextView>(R.id.txtSignalChevron).text =
            if (hasAvg) "${s!!.averages.size} promedio${if (s.averages.size > 1) "s" else ""} " + (if (signalOpen) "▴" else "▾") else ""
        val avgBox = findViewById<LinearLayout>(R.id.boxSignalAvgs)
        avgBox.removeAllViews()
        avgBox.visibility = if (signalOpen) View.VISIBLE else View.GONE
        if (signalOpen && s != null) s.averages.forEachIndexed { i, p ->
            avgBox.addView(tableRow(listOf("Promedio ${i + 1}" to C_INK2, p to C_INK), floatArrayOf(1f, 1f)))
        }
        findViewById<TextView>(R.id.txtSignalTitle).text = s?.let { Notifier.title(it) } ?: "Sin señal abierta"
        val body = findViewById<TextView>(R.id.txtSignalBody)
        body.text = s?.let { "Abierta a las " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.openedAt)) } ?: ""
        body.visibility = if (s != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnClose).visibility = if (s != null) View.VISIBLE else View.GONE

        val h = store.history()
        findViewById<TextView>(R.id.txtHistory).text = if (h.isEmpty()) "—" else h.joinToString("\n")
    }

    // ---------- Deslizar para actualizar ----------
    private fun currentPage(): View = when (tab) {
        R.id.tabWeb -> web
        R.id.tabHistory -> findViewById(R.id.pageHistory)
        R.id.tabSettings -> findViewById(R.id.pageSettings)
        else -> findViewById(R.id.pageSignal)
    }

    private fun setupSwipe() {
        val sw = findViewById<SwipeRefreshLayout>(R.id.swipe)
        sw.setColorSchemeColors(ContextCompat.getColor(this, R.color.gold))
        sw.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface))
        sw.setOnChildScrollUpCallback { _, _ -> currentPage().canScrollVertically(-1) }
        sw.setOnRefreshListener {
            when (tab) {
                R.id.tabWeb -> web.reload()
                else -> { Myfxbook.forceNews = true; Myfxbook.kick(this) }
            }
            refresh()
            sw.postDelayed({ sw.isRefreshing = false; refresh() }, if (tab == R.id.tabWeb) 1500 else 2500)
        }
    }

    // ---------- Precio, operaciones y promedios ----------
    private val C_INK get() = ContextCompat.getColor(this, R.color.ink)
    private val C_INK2 get() = ContextCompat.getColor(this, R.color.ink2)
    private val C_BUY = 0xFF2F7CF6.toInt()
    private val C_SELL = 0xFFF0453A.toInt()
    private val C_POS = 0xFF22A06B.toInt()

    private fun cell(text: String, color: Int, weight: Float, end: Boolean = false, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = 14f
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            if (end) gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    private fun tableRow(cells: List<Pair<String, Int>>, weights: FloatArray, bold: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
            cells.forEachIndexed { i, (t, c) -> addView(cell(t, c, weights[i], end = i == cells.lastIndex && i > 0, bold = bold)) }
        }

    private fun refreshNews() {
        val card = findViewById<View>(R.id.cardNews)
        if (!store.newsOn) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        val box = findViewById<LinearLayout>(R.id.boxNews)
        box.removeAllViews()
        val now = System.currentTimeMillis()
        val next = News.events(store).filter { it.time + News.AFTER_MS >= now }.take(5)
        if (next.isEmpty()) {
            box.addView(tableRow(listOf((if (store.newsAt == 0L) "Cargando calendario…" else "Sin noticias importantes esta semana") to C_INK2), floatArrayOf(1f)))
            return
        }
        val w = floatArrayOf(1.1f, 0.4f, 3f)
        next.forEach { e ->
            val live = now >= e.time - store.newsBefore * 60_000L && now <= e.time + News.AFTER_MS
            val day = News.dayLabel(e.time)
            box.addView(tableRow(listOf(
                (if (day == "Hoy") News.hhmm(e.time) else "$day ${News.hhmm(e.time)}") to (if (live) C_SELL else C_INK2),
                e.dot to C_INK,
                (e.name + (e.forecast.takeIf { it.isNotBlank() }?.let { "  · prev. $it" } ?: "")) to (if (live) C_INK else C_INK)
            ), w, bold = live))
            News.hint(e.title)?.let { h ->
                val r = tableRow(listOf("" to C_INK2, "" to C_INK2, h to C_INK2), w)
                r.setPadding(0, 0, 0, 10)
                (r.getChildAt(2) as TextView).textSize = 12f
                (r.getChildAt(2) as TextView).gravity = android.view.Gravity.START
                box.addView(r)
            }
        }
    }

    private fun refreshMarket() {
        refreshNews()
        val q = store.quote
        val price = findViewById<TextView>(R.id.txtPrice)
        val info = findViewById<TextView>(R.id.txtPriceInfo)
        if (q == null) { price.text = "—"; info.text = "" } else {
            price.text = Market.fmtPrice(q.bid)
            val prev = store.prevBid
            price.setTextColor(when {
                prev == null || q.closed -> ContextCompat.getColor(this, R.color.band_ink)
                q.bid > prev -> C_POS
                q.bid < prev -> C_SELL
                else -> ContextCompat.getColor(this, R.color.band_ink)
            })
            val t = java.text.SimpleDateFormat(if (q.closed) "EEE HH:mm" else "HH:mm:ss", java.util.Locale("es", "ES")).format(java.util.Date(q.ts))
            info.text = if (q.closed) "Mercado cerrado · $t" else "Venta ${Market.fmtPrice(q.bid)} · Compra ${Market.fmtPrice(q.ask)}"
        }

        val mfx = store.mfxOn && store.mfxEmail.isNotEmpty()
        val trades = store.trades()
        val orders = store.orders()
        findViewById<View>(R.id.cardTrades).visibility = View.VISIBLE
        findViewById<View>(R.id.txtStale).visibility = if (mfx && store.mfxStale()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardOrders).visibility = if (mfx && orders.isNotEmpty()) View.VISIBLE else View.GONE

        // Operaciones abiertas: tipo · lotes · entrada · beneficio
        val tb = findViewById<LinearLayout>(R.id.boxTrades)
        tb.removeAllViews()
        val tw = floatArrayOf(0.9f, 0.8f, 1.3f, 1.3f)
        fun withActions(r: LinearLayout, t: Trade?): LinearLayout {
            r.gravity = android.view.Gravity.CENTER_VERTICAL
            r.addView(if (t == null) cell("", C_INK2, 0.45f) else toggle("✏️", true) { tradeDialog(t) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).weight = 0.45f; it.textSize = 14f })
            r.addView(if (t == null) cell("", C_INK2, 0.45f) else toggle("✕", true) { removeTrade(t) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).weight = 0.45f; it.textSize = 16f; it.setTextColor(C_INK2) })
            return r
        }
        if (trades.isEmpty()) tb.addView(tableRow(listOf("Sin operaciones abiertas" to C_INK2), floatArrayOf(1f)))
        else {
            tb.addView(withActions(tableRow(listOf("Tipo" to C_INK2, "Lotes" to C_INK2, "Entrada" to C_INK2, "Beneficio" to C_INK2), tw), null))
            var total = 0.0
            trades.sortedBy { it.open }.forEach { t ->
                val p = t.live(q); total += p
                val mark = if (t.manual) " ✋" else if (t.edited) " ✎" else ""
                tb.addView(withActions(tableRow(listOf(
                    ((if (t.buy) "BUY" else "SELL") + mark) to (if (t.buy) C_BUY else C_SELL),
                    Market.fmtLots(t.lots) to C_INK,
                    Market.fmtPrice(t.open) to C_INK,
                    Notifier.money(p) to (if (p < 0) C_SELL else C_POS)
                ), tw), t))
            }
            tb.addView(withActions(tableRow(listOf("Total" to C_INK, "" to C_INK, "" to C_INK,
                Notifier.money(total) to (if (total < 0) C_SELL else C_POS)), tw, bold = true), null))
        }

        // Promedios (órdenes pendientes): tipo · lotes · precio · distancia · 🔔 · ⏰
        val ob = findViewById<LinearLayout>(R.id.boxOrders)
        ob.removeAllViews()
        if (orders.isNotEmpty()) {
            val ow = floatArrayOf(1.4f, 0.8f, 1.3f, 1f)
            val head = tableRow(listOf("Tipo" to C_INK2, "Lotes" to C_INK2, "Precio" to C_INK2, "Falta" to C_INK2), ow)
            head.addView(cell("", C_INK2, 0.6f)); head.addView(cell("", C_INK2, 0.6f))
            ob.addView(head)
            val off = store.ordOff; val alarm = store.ordAlarm; val fired = store.ordFired
            orders.sortedBy { it.price }.forEach { o ->
                val row = tableRow(listOf(
                    o.type to (if (o.buy) C_BUY else C_SELL),
                    Market.fmtLots(o.lots) to C_INK,
                    Market.fmtPrice(o.price) to C_INK,
                    (if (o.key in fired) "Entró" else q?.let { Market.fmtPrice(o.distance(it)) } ?: "—") to C_INK2
                ), ow)
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.addView(toggle(if (o.key in off) "🔕" else "🔔", o.key !in off) {
                    store.ordOff = if (o.key in store.ordOff) store.ordOff - o.key else store.ordOff + o.key; refresh()
                })
                row.addView(toggle("⏰", o.key in alarm && o.key !in off) {
                    store.ordAlarm = if (o.key in store.ordAlarm) store.ordAlarm - o.key else store.ordAlarm + o.key; refresh()
                })
                ob.addView(row)
            }
        }
    }

    // ---------- Añadir / editar / quitar operaciones ----------
    private fun afterTradeChange() {
        Myfxbook.kick(this)   // recalcula flotante e isla al momento
        refresh()
    }

    private fun removeTrade(t: Trade) {
        val msg = if (t.manual) "¿Borrar esta operación añadida a mano?"
            else "¿Quitar esta operación de la lista?\nSi Myfxbook la tiene por error, dejará de contar en el flotante. Si de verdad está abierta, volverá a aparecer cuando Myfxbook la vuelva a enviar."
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("${if (t.buy) "BUY" else "SELL"} ${Market.fmtLots(t.lots)} · ${Market.fmtPrice(t.open)}")
            .setMessage(msg)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (t.manual) "Borrar" else "Quitar") { _, _ ->
                if (t.manual) store.saveManual(store.manualTrades().filter { it.key != t.key })
                else store.tradeHidden = store.tradeHidden + t.key
                afterTradeChange()
            }.show()
    }

    /** Ventana para añadir (t = null) o editar una operación. */
    private fun tradeDialog(t: Trade?) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val q = store.quote
        var buy = t?.buy ?: true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }

        // Compra / venta
        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bBuy = Button(this).apply { text = "BUY" }
        val bSell = Button(this).apply { text = "SELL" }
        val price = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(C_INK)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        fun current(): Double? = q?.let { if (buy) it.ask else it.bid }
        fun paint() {
            bBuy.setBackgroundColor(if (buy) C_BUY else 0x22000000); bBuy.setTextColor(if (buy) 0xFFFFFFFF.toInt() else C_INK2)
            bSell.setBackgroundColor(if (!buy) C_SELL else 0x22000000); bSell.setTextColor(if (!buy) 0xFFFFFFFF.toInt() else C_INK2)
        }
        fun parsePrice(): Double? {
            val raw = price.text.toString().replace(" ", "")
            // "4187.50" o "4187,50" o "4.187,50"
            val norm = if (raw.contains(",")) raw.replace(".", "").replace(",", ".") else raw
            return norm.toDoubleOrNull()
        }
        fun setPrice(v: Double) { price.setText(String.format(java.util.Locale.US, "%.2f", v)); price.setSelection(price.text.length) }
        bBuy.setOnClickListener { val follow = t == null && parsePrice() == current(); buy = true; paint(); if (follow) current()?.let { setPrice(it) } }
        bSell.setOnClickListener { val follow = t == null && parsePrice() == current(); buy = false; paint(); if (follow) current()?.let { setPrice(it) } }
        val lpHalf = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(4) }
        sideRow.addView(bBuy, lpHalf); sideRow.addView(bSell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(4) })
        root.addView(sideRow)

        // Lotes
        root.addView(TextView(this).apply { text = "Lotes"; setTextColor(C_INK2); setPadding(0, px(12), 0, 0) })
        val lots = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.2f", t?.lots ?: store.lastManualLots)); setTextColor(C_INK)
        }
        root.addView(lots)

        // Precio con −/+ a los lados
        root.addView(TextView(this).apply { text = "Precio de entrada"; setTextColor(C_INK2); setPadding(0, px(12), 0, 0) })
        val priceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        fun stepCol(sign: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(1, 5, 10, 50).forEach { n ->
                addView(Button(this@MainActivity, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                    text = (if (sign < 0) "−" else "+") + n
                    setTextColor(if (sign < 0) C_SELL else C_POS)
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(px(8), px(2), px(8), px(2))
                    setOnClickListener { (parsePrice() ?: current())?.let { setPrice(it + sign * n) } }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(38)))
            }
        }
        priceRow.addView(stepCol(-1))
        priceRow.addView(price, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        priceRow.addView(stepCol(+1))
        root.addView(priceRow)
        if (q != null) root.addView(Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
            text = "Usar precio actual"; setTextColor(C_INK2)
            setOnClickListener { current()?.let { setPrice(it) } }
        })

        paint()
        (t?.open ?: current())?.let { setPrice(it) }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (t == null) "Añadir operación" else "Editar operación")
            .setView(root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val l = lots.text.toString().replace(",", ".").toDoubleOrNull()
                val p = parsePrice()
                if (l == null || l <= 0 || p == null || p <= 0) { toast("Revisa los lotes y el precio"); return@setPositiveButton }
                when {
                    t == null -> {
                        val k = store.rawTrades().firstOrNull()?.k ?: 0.01   // tus cuentas son en céntimos
                        store.saveManual(store.manualTrades() + Trade("Manual", "XAUUSD", buy, l, p, 0.0, k,
                            key = "m|" + System.currentTimeMillis(), manual = true))
                        store.lastManualLots = l
                    }
                    t.manual -> store.saveManual(store.manualTrades().map { if (it.key == t.key) it.copy(buy = buy, lots = l, open = p) else it })
                    else -> store.editTrade(t.key, l, p)
                }
                afterTradeChange()
            }.show()
    }

    private fun toggle(icon: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 20f; gravity = android.view.Gravity.CENTER
        alpha = if (on) 1f else 0.3f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        setPadding(0, 4, 0, 4)
        setOnClickListener { click() }
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
