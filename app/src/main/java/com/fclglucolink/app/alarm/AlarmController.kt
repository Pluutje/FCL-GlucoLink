package com.fclglucolink.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fclglucolink.app.data.AppSettings
import kotlinx.coroutines.flow.first

/**
 * ============================================================================
 * FCLGlucoLink — alarm-orkestratie (RONDE 107)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107) — de schakel tussen AlarmMonitor.kt (bepaalt
 * WANNEER een alarm moet klinken) en AlarmSoundPlayer.kt/AlarmActivity.kt
 * (bepalen HOE dat klinkt/eruitziet): start/stop het geluid, toont de
 * full-screen-notificatie die AlarmActivity opent, en verwerkt de Stop/
 * Snooze-knoppen van dat scherm.
 *
 * "Stop" vs. "Snooze" vs. automatisch opgelost — drie verschillende
 * dempingen, alle via [AppSettings.alarmMutedUntilMs]:
 * - [snooze]: de gebruiker kiest zelf hoe lang (AlarmActivity.kt toont een
 *   schuif — 15 tot 90 min, Urgent Low max 30). Vervalt bij herstel van de waarde.
 * - [stop]: stil zolang de episode duurt, pas weer na herstel van de waarde
 *   (en nooit binnen 15 min); Urgent Low herhaalt na 30 min. GEEN
 *   permanente stilte voor Urgent Low (veiligheid).
 * - [clear]: GEEN demping — het alarm stopt omdat de onderliggende
 *   conditie vanzelf niet meer geldt (AlarmMonitor.kt ziet het type niet
 *   meer in de vurende lijst), of omdat de hoofdschakelaar/AAPS-actieve
 *   slot ondertussen uit-/omgezet is. Een schone, meteen-weer-vurende
 *   toestand, geen wachttijd.
 */
object AlarmController {

    private const val CHANNEL_ID = "fclglucolink_alarms"
    private const val NOTIFICATION_ID = 2

    suspend fun trigger(context: Context, type: AlarmType) {
        if (AlarmRuntimeState.currentlySoundingType != null) return
        AlarmRuntimeState.currentlySoundingType = type

        val settings = AppSettings(context)
        val soundUriString = settings.getAlarmSoundUriOnce(type)
        val soundUri = soundUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val escalation = settings.alarmEscalation(type).first()
        val alertMode = settings.alarmAlertMode(type).first()

        AlarmSoundPlayer.start(context.applicationContext, soundUri, escalation, alertMode)
        showFullScreenNotification(context, type)
    }

    /** "Stop"-knop op AlarmActivity.kt. */
    /**
     * "Stop": het alarm komt niet terug zolang de episode duurt; pas na herstel
     * van de waarde (zie AlarmMonitor) en dan nooit binnen [STOP_MIN_GAP_MS].
     * Uitzondering Urgent Low (veiligheid): geen episode-stop, maar na
     * [URGENT_LOW_REPEAT_MS] opnieuw als de waarde nog steeds te laag is.
     */
    suspend fun stop(context: Context, type: AlarmType) {
        val settings = AppSettings(context)
        val now = System.currentTimeMillis()
        if (type == AlarmType.URGENT_LOW) {
            settings.setAlarmMutedUntilMs(type, now + URGENT_LOW_REPEAT_MS)
            settings.setAlarmStoppedEpisode(type, false)
        } else {
            settings.setAlarmMutedUntilMs(type, now + STOP_MIN_GAP_MS)
            settings.setAlarmStoppedEpisode(type, true)
        }
        settings.setAlarmSnoozeActive(type, false)
        finishAlarm(context)
    }

    /** "Snooze"-knop op AlarmActivity.kt — [minutes] is een van de vaste
     *  keuzes die dat scherm toont. */
    suspend fun snooze(context: Context, type: AlarmType, minutes: Int) {
        val settings = AppSettings(context)
        val capped = minutes.coerceAtMost(maxSnoozeMinutes(type))
        settings.setAlarmMutedUntilMs(type, System.currentTimeMillis() + capped * 60_000L)
        settings.setAlarmSnoozeActive(type, true)
        settings.setAlarmStoppedEpisode(type, false)
        settings.setAlarmLastSnoozeMinutes(capped)
        finishAlarm(context)
    }

    /** Conditie is vanzelf opgelost (of alarmen/AAPS-slot staan ondertussen
     *  uit) — zie klasse-kdoc: GEEN mute-tijdstip, gewoon stil. Aangeroepen
     *  vanuit AlarmMonitor.kt, niet vanuit AlarmActivity.kt zelf. */
    fun clear(context: Context) {
        finishAlarm(context)
    }

    private fun finishAlarm(context: Context) {
        AlarmSoundPlayer.stop()
        AlarmRuntimeState.currentlySoundingType = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Zie klasse-kdoc — drempelalarmen krijgen het kortste afkoelmoment
     *  (het zijn de meest kritieke situaties, dus de kortste tijd voordat
     *  een aanhoudende situatie opnieuw waarschuwt), predictief/stale-data
     *  een langer moment (minder acuut, meer ruimte om zelf te handelen
     *  voordat er opnieuw gemeld wordt). */
    const val SNOOZE_STEP_MINUTES = 15
    const val STOP_MIN_GAP_MS = 15 * 60_000L
    const val URGENT_LOW_REPEAT_MS = 30 * 60_000L

    /** Urgent Low mag maximaal 30 min gesluimerd worden, de rest 90. */
    fun maxSnoozeMinutes(type: AlarmType): Int = if (type == AlarmType.URGENT_LOW) 30 else 90

    /**
     * 13/08/2026 (editor, RONDE 107) — de eigenlijke "wek dit toestel"-stap:
     * een hoge-prioriteit-notificatie met `setFullScreenIntent()`, Android's
     * mechanisme voor wekker-achtige, scherm-doorbrekende meldingen (zelfde
     * categorie als een bel-inkomend-scherm). Het geluid/de trilling komen
     * NIET van deze notificatie zelf (het notificatiekanaal heeft bewust
     * `setSound(null, null)`/`enableVibration(false)`) — die lopen via
     * AlarmSoundPlayer.kt op STREAM_ALARM, zodat er nooit dubbel geluid/
     * trilling ontstaat.
     *
     * BEKENDE BEPERKING (RONDE 107, nog niet opgelost): op Android 14
     * (API 34) is `USE_FULL_SCREEN_INTENT` voor gewone (niet als beller/
     * wekker-app geregistreerde) apps niet meer automatisch toegestaan —
     * de gebruiker moet dat mogelijk zelf via Instellingen > Apps >
     * FCLGlucoLink > Speciale toegang > Volledig scherm-meldingen
     * inschakelen, anders valt dit terug op een gewone (niet scherm-
     * doorbrekende) hoge-prioriteit-melding. Zie README's Ronde 107-sectie
     * — een knop die de gebruiker daar direct naartoe stuurt (zelfde
     * patroon als de bestaande batterij-optimalisatie-knop) is een
     * logische vervolgstap, nog niet gebouwd.
     */
    private fun showFullScreenNotification(context: Context, type: AlarmType) {
        ensureChannel(context)

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(AlarmActivity.EXTRA_ALARM_TYPE, type.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(type.displayName)
            .setContentText(type.description)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            // RONDE 229 — wegvegen = Stop; ook knoppen in de melding zelf.
            .setDeleteIntent(actionIntent(context, AlarmActionReceiver.ACTION_STOP, 1))
            .addAction(0, "Stop", actionIntent(context, AlarmActionReceiver.ACTION_STOP, 2))
            .addAction(0, "Snooze…", snoozePickerIntent(context, type))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Opent het alarmscherm met de sluimerkeuze al open. */
    private fun snoozePickerIntent(context: Context, type: AlarmType): PendingIntent =
        PendingIntent.getActivity(
            context,
            4,
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_TYPE, type.name)
                putExtra(AlarmActivity.EXTRA_OPEN_SNOOZE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Glucose alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen alerts when a glucose alarm fires"
                setBypassDnd(true)
                // Geluid/trilling lopen bewust via AlarmSoundPlayer.kt
                // (STREAM_ALARM, per-type instelbaar geluid) — hier
                // uitgeschakeld om dubbel geluid/dubbele trilling te
                // voorkomen.
                enableVibration(false)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
