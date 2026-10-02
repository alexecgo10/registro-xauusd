package com.alexecgo.registroxau

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botón "Cerrar señal" de la notificación fija, por si se perdió el mensaje de cierre. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CLOSE) {
            val store = SignalStore(context)
            if (store.state() != null) {
                store.saveState(null)
                store.log("Cerrada a mano")
            }
            Notifier.showOngoing(context)
            context.sendBroadcast(Intent(SignalListenerService.ACTION_CHANGED).setPackage(context.packageName))
        }
    }

    companion object {
        const val ACTION_CLOSE = "com.alexecgo.registroxau.CLOSE_SIGNAL"
    }
}
