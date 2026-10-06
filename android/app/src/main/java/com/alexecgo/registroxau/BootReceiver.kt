package com.alexecgo.registroxau

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al encender el móvil o actualizar la app, vuelve a arrancar el vigilante. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeepAliveService.start(context)
        KeepAliveService.check(context)
    }
}
