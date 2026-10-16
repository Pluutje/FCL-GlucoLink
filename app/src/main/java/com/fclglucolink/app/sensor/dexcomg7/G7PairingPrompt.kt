package com.fclglucolink.app.sensor.dexcomg7

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fclglucolink.app.MainActivity
import com.fclglucolink.app.logging.DiagnosticFileLogger

/**
 * RONDE 228 — melding "Tik nu op Koppelen" voor het G7/ONE+ koppelverzoek.
 *
 * Waarom: de sensor wacht maar ongeveer 25 seconden (gemeten in logs) op het antwoord op Android's
 * koppelverzoek. Is het scherm uit, of kijkt de gebruiker niet, dan is de sensor
 * al weer weg voordat het verzoek is bevestigd (status 19). Deze melding heeft
 * hoge prioriteit met geluid en trillen, en probeert het scherm aan te zetten.
 * Hij verdwijnt zodra de koppeling klaar is of mislukt.
 */
object G7PairingPrompt {
    private const val CHANNEL_ID = "fclglucolink_g7_pairing"
    private const val NOTIFICATION_ID = 7421

    fun show(context: Context, sensorName: String?) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Koppelverzoek G7 / ONE+", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Vraagt je om het koppelverzoek van de sensor snel te bevestigen."
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
            val openIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val name = sensorName?.takeIf { it.isNotBlank() } ?: "de sensor"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Tik nu op Koppelen")
                .setContentText("Bevestig het Bluetooth-koppelverzoek van $name binnen ongeveer 25 seconden.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000L)
                .setContentIntent(openIntent)
                .setFullScreenIntent(openIntent, true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            DiagnosticFileLogger.log("DexcomG7: melding 'Tik nu op Koppelen' getoond (RONDE 228)")
        }.onFailure {
            DiagnosticFileLogger.log("DexcomG7: koppel-melding tonen mislukt: ${it.message}")
        }
    }

    fun cancel(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }
}
