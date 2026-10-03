package com.alexecgo.prueba

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Notis.clear(context)
}
