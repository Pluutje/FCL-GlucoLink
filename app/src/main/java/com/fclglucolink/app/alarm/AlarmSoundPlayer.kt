package com.fclglucolink.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FCLGlucoLink — alarmgeluid + trilling (RONDE 107)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107) — speelt het per-type gekozen geluid
 * (AppSettings.alarmSoundUri, gekozen via AlarmSettingsScreen.kt's
 * SoundPickerRow) af via `AudioAttributes.USAGE_ALARM` (de STREAM_ALARM-
 * equivalent op de moderne AudioAttributes-API) — bewust NIET de
 * notificatiestroom, precies zoals in de meedenk-ronde afgestemd: alarm-
 * geluid moet blijven klinken ook als het toestel op stil/DND staat op een
 * manier die de gebruiker als "een echt alarm" herkent (zelfde categorie
 * als een wekker), niet als een onderdrukbare notificatie.
 *
 * [AlarmEscalation.IMMEDIATE]: direct op volle sterkte. [AlarmEscalation.
 * GRADUAL]: begint zacht, klimt geleidelijk (elke 5s +10%) naar vol —
 * tempo bewust hardcoded/niet instelbaar (letterlijk verzoek: "daarbij
 * hoeft de opbouw tempo niet instelbaar te zijn").
 *
 * Bewust een EIGEN, object-brede CoroutineScope ([playerScope]) i.p.v. de
 * scope van de aanroeper (AlarmController.trigger(), zelf aangeroepen
 * vanuit AlarmMonitor's periodieke check) — het geluid/de volume-opbouw
 * moet blijven doorlopen ONAFHANKELIJK van hoe lang die ene check-aanroep
 * duurt; die coroutine is allang klaar terwijl het alarm nog minutenlang
 * kan blijven klinken.
 *
 * Vereist de `VIBRATE`-permissie in het manifest (normale permissie, geen
 * runtime-prompt).
 */
object AlarmSoundPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var playerScope: CoroutineScope? = null
    private var volumeJob: Job? = null

    private const val START_VOLUME_GRADUAL = 0.15f
    private const val VOLUME_STEP = 0.10f
    private const val VOLUME_STEP_INTERVAL_MS = 5_000L

    /**
     * 13/08/2026 (editor, RONDE 107b, op verzoek: "ik wil per alarm kunnen
     * kiezen tussen alarm of vibrate of both") — [alertMode] vervangt de
     * oude `vibrationEnabled: Boolean`-parameter: [AlarmAlertMode.SOUND]
     * slaat het opzetten van de Vibrator hieronder helemaal over,
     * [AlarmAlertMode.VIBRATE] slaat MediaPlayer helemaal over (geen
     * stille MediaPlayer die toch draait) — geen van beide doet onnodig
     * werk als de gebruiker 'm niet wil.
     */
    fun start(context: Context, soundUri: Uri, escalation: AlarmEscalation, alertMode: AlarmAlertMode) {
        stop()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        playerScope = scope

        if (alertMode == AlarmAlertMode.SOUND || alertMode == AlarmAlertMode.BOTH) {
            val startVolume = if (escalation == AlarmEscalation.IMMEDIATE) 1.0f else START_VOLUME_GRADUAL
            val player = MediaPlayer()
            runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(context, soundUri)
                player.isLooping = true
                player.setVolume(startVolume, startVolume)
                player.prepare()
                player.start()
            }.onFailure {
                // Gekozen geluid niet meer leesbaar (bv. een app die de
                // ringtone leverde is ondertussen verwijderd) — geen crash,
                // gewoon geen geluid; trilling (indien gekozen) en het
                // full-screen scherm zelf werken nog gewoon.
                runCatching { player.release() }
            }
            mediaPlayer = player

            if (escalation == AlarmEscalation.GRADUAL) {
                volumeJob = scope.launch {
                    var volume = startVolume
                    while (volume < 1.0f) {
                        delay(VOLUME_STEP_INTERVAL_MS)
                        volume = (volume + VOLUME_STEP).coerceAtMost(1.0f)
                        runCatching { mediaPlayer?.setVolume(volume, volume) }
                    }
                }
            }
        }

        if (alertMode == AlarmAlertMode.VIBRATE || alertMode == AlarmAlertMode.BOTH) {
            startVibration(context)
        }
    }

    fun stop() {
        volumeJob?.cancel()
        volumeJob = null
        playerScope?.cancel()
        playerScope = null
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    fun isPlaying(): Boolean = mediaPlayer != null

    /** Herhalend patroon (500ms aan, 500ms uit) tot [stop] aangeroepen wordt
     *  — `VibrationEffect.createWaveform(pattern, repeatIndex)` met
     *  repeatIndex 0 herhaalt vanaf het begin van de array, precies dit
     *  aan/uit-ritme. */
    private fun startVibration(context: Context) {
        val pattern = longArrayOf(0, 500, 500)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vib?.vibrate(effect) }
        vibrator = vib
    }
}
