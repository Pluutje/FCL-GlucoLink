package com.fclglucolink.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fclglucolink.app.logging.DiagnosticFileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * RONDE 229 — verwerkt de acties van de alarmmelding zonder het alarmscherm te openen:
 *  - [ACTION_STOP]: de gebruiker veegt de melding weg (deleteIntent) of tikt "Stop".
 *    Doet precies hetzelfde als de knop Stop op het alarmscherm.
 *  - [ACTION_SNOOZE]: "Snooze 15 min" in de melding.
 * Zonder dit bleef het geluid doorlopen als de melding werd weggeveegd, en was er
 * nergens meer een knop om het alarm te stoppen.
 */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = AlarmRuntimeState.currentlySoundingType ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> AlarmController.snooze(appContext, type, SNOOZE_MINUTES)
                    else -> AlarmController.stop(appContext, type)
                }
                DiagnosticFileLogger.log("Alarm: ${intent.action} vanuit melding verwerkt voor ${type.name} (RONDE 229)")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.fclglucolink.app.alarm.ACTION_STOP"
        const val ACTION_SNOOZE = "com.fclglucolink.app.alarm.ACTION_SNOOZE"
        const val SNOOZE_MINUTES = 15
    }
}
