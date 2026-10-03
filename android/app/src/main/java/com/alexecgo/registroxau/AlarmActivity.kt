package com.alexecgo.registroxau

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Pantalla de alarma (se enciende aunque el móvil esté bloqueado). */
class AlarmActivity : AppCompatActivity() {

    private val finish = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(ContextCompat.getColor(this@AlarmActivity, R.color.band))
        }
        box.addView(TextView(this).apply {
            text = "⏰"; textSize = 64f; gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = Alarm.lastTitle; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.band_ink))
            setPadding(0, 24, 0, 12)
        })
        box.addView(TextView(this).apply {
            text = Alarm.lastText; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.band_dim))
        })
        box.addView(Button(this).apply {
            text = "Parar alarma"; textSize = 22f
            setBackgroundColor(ContextCompat.getColor(this@AlarmActivity, R.color.gold))
            setTextColor(ContextCompat.getColor(this@AlarmActivity, R.color.band))
            setPadding(48, 36, 48, 36)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 96 }
            setOnClickListener { Alarm.stop(this@AlarmActivity); finish() }
        })
        setContentView(box)
        ContextCompat.registerReceiver(this, finish, IntentFilter(ACTION_FINISH), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!Alarm.ringing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(finish) } catch (e: Exception) { }
    }

    companion object {
        const val ACTION_FINISH = "com.alexecgo.registroxau.ALARM_FINISH"
    }
}
