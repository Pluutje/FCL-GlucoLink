package com.fclglucolink.app.alarm

import android.content.Context
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import kotlinx.coroutines.flow.first

/**
 * ============================================================================
 * FCLGlucoLink — alarm-bewaking (RONDE 107)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107) — periodiek aangeroepen (elke
 * [CHECK_INTERVAL_MS], zie BleConnectionService.kt's `onCreate()`) i.p.v.
 * gekoppeld aan de aankomst van een nieuwe meting: dat is bewust
 * ONAFHANKELIJK van welke driver toevallig net een `readings`-emissie deed,
 * om twee redenen — (1) het staledata-alarm moet júist kunnen afgaan
 * wanneer er GEEN nieuwe meting meer binnenkomt, dus een aan-nieuwe-
 * metingen-gekoppelde check zou dat per definitie missen; (2) dit blijft zo
 * volledig los van de al zeer delicaat afgestemde scan-/verbindingstiming
 * in CareSensAirDriver.kt/DexcomG6Driver.kt/AapsSlotSchedule.kt — geen
 * enkele wijziging daar, geen enkel risico daarop.
 *
 * Bewust alleen het AAPS-actieve slot (settings.aapsActiveSlot) — eerder
 * al afgestemd: "het aaps actieve slot bewaakt de alarmen".
 *
 * Elke tik: (1) als alarmen uit staan, of er geen AAPS-actieve slot/sensor
 * is, stop een eventueel nog klinkend alarm (de gebruiker zette ondertussen
 * alarmen uit, of wisselde van AAPS-slot) en stop; (2) anders: lees alle 7
 * alarmtypes' instellingen, evalueer (AlarmEvaluator.evaluate), en als er
 * al iets klinkt maar dat type vuurt niet meer -> stil (conditie vanzelf
 * opgelost); (3) als er nog niets klinkt en er is een vurend type dat niet
 * (meer) gedempt is (AppSettings.alarmMutedUntilMs) -> AlarmController.trigger().
 *
 * Herstart-veiligheid: als het proces herstart terwijl er een alarm hoorde
 * te klinken, ziet de eerstvolgende tik dat gewoon opnieuw als "vurend, niet
 * gedempt" en start het alarm opnieuw — bewust de veilige kant om op te
 * falen (zie ook AlarmRuntimeState.kt's kdoc).
 */
class AlarmMonitor(private val context: Context) {

    private val settings = AppSettings(context)
    private val readingStore = GlucoseReadingStore(context)

    suspend fun checkOnce() {
        val masterEnabled = settings.isAlarmsMasterEnabledOnce()
        val slot = settings.getAapsActiveSlotOnce()
        val sensorType = slot?.let { settings.getSelectedSensorOnce(it) }

        if (!masterEnabled || sensorType == null) {
            if (AlarmRuntimeState.currentlySoundingType != null) {
                AlarmController.clear(context)
            }
            return
        }

        val nowMs = System.currentTimeMillis()
        val configs = AlarmType.entries.associateWith { type ->
            ResolvedAlarmConfig(
                type = type,
                enabled = settings.isAlarmEnabledOnce(type),
                thresholdMgdl = settings.getAlarmThresholdMgdlOnce(type),
                leadTimeMinutes = settings.alarmLeadTimeMinutes(type).first(),
                staleMinutes = settings.alarmStaleMinutes(type).first()
            )
        }

        val latestReading = readingStore.latestReading(sensorType = sensorType).first()
        val firing = AlarmEvaluator.evaluate(configs, latestReading, nowMs)

        val sounding = AlarmRuntimeState.currentlySoundingType
        if (sounding != null) {
            if (sounding !in firing) {
                AlarmController.clear(context)
            }
            // Er klinkt al iets (en het vuurt nog steeds, of we hebben 'm
            // net stilgezet) — nooit een tweede alarm er bovenop starten
            // binnen dezelfde tik.
            return
        }

        val candidate = AlarmEvaluator.highestPriority(firing) ?: return
        val mutedUntilMs = settings.alarmMutedUntilMs(candidate).first()
        if (mutedUntilMs != null && nowMs < mutedUntilMs) return

        AlarmController.trigger(context, candidate)
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
    }
}
