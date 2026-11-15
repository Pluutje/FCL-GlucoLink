package com.fclglucolink.app.sensor.ble

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fclglucolink.app.MainActivity
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.ui.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Melding "koppeling lijkt kapot": de sensor accepteert de login wel maar verbreekt
 * meteen zonder meting, terwijl er al meer dan een uur geen meting is. Android vraagt
 * zelf om bevestiging bij opnieuw koppelen, dus de gebruiker moet dat doen; deze
 * melding zorgt dat hij het binnen een uur merkt. Hoogstens 1x per 6 uur.
 */
object BrokenPairingNotifier {
    private const val NOTIFICATION_ID = 40
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "fclglucolink_updates"
    @Volatile private var lastNotifiedAtMs = 0L

    fun notifyIfDue(context: Context, sensorName: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAtMs < MIN_INTERVAL_MS) return
        lastNotifiedAtMs = now
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val nl = runCatching { AppSettings(app).getAppLanguageOnce() == AppLanguage.DUTCH }.getOrDefault(false)
            val title = if (nl) "Koppeling met sensor lijkt kapot" else "Sensor pairing seems broken"
            val text = if (nl)
                "$sensorName: al meer dan een uur geen meting, de sensor verbreekt direct na het inloggen. Koppel de sensor opnieuw (statusscherm, koppeling verwijderen)."
            else
                "$sensorName: no reading for over an hour, the sensor disconnects right after login. Pair the sensor again (status screen, remove pairing)."
            val open = PendingIntent.getActivity(app, 6, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n) }
            DiagnosticFileLogger.log("Melding getoond: koppeling met $sensorName lijkt kapot (geen meting >1 uur, sensor verbreekt na login)")
        }
    }
}
