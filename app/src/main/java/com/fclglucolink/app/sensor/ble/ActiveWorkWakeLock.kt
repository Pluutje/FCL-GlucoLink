package com.fclglucolink.app.sensor.ble

import android.content.Context
import android.os.PowerManager

/**
 * ============================================================================
 * FCLGlucoLink — kortstondige wakelock rond actief BLE-werk (RONDE 89)
 * ============================================================================
 *
 * 11/08/2026 (editor, op verzoek — "beduidend sneller leeg lopen van de
 * batterij... hoog batterijverbruik") — vervangt de PARTIAL_WAKE_LOCK die
 * `BleConnectionService.kt` sinds 30/07/2026 de VOLLEDIGE service-levensduur
 * vasthield (`acquire(20 dagen)`, pas losgelaten in onDestroy()).
 *
 * Waarom die er toen kwam (zie BleConnectionService.kt's klasse-kdoc voor het
 * volledige citaat): een kale coroutine-`delay()` in SimulatorDriver's
 * afspeellus vuurde te laat af zodra de CPU tijdens Doze in slaap viel — een
 * permanente wakelock loste dat destijds op.
 *
 * Waarom dat NU overbodig is voor het reguliere sensorpad: sinds Ronde 36
 * (04/08/2026) loopt de daadwerkelijke herverbind-timing niet meer via een
 * kale `delay(cooldownMs)`, maar via `PredictiveReconnectAlarm`
 * (`AlarmManager.setExactAndAllowWhileIdle()`) — een mechanisme dat per
 * ontwerp de CPU zelf wekt op het exacte moment, Doze of niet, zonder dat
 * er tussendoor iets wakker gehouden hoeft te worden. Analyse van een volle
 * dag diagnostic-log (11/08) laat zien dat van elke ~300s-cyclus maar
 * ~10-15s daadwerkelijk actief BLE-werk is (scan -> verbinden -> handshake
 * -> meting -> disconnect) — de overige ~95% was de CPU dus 24/7 wakker
 * gehouden voor NIETS. Dat verklaart een hoog battery-verbruik heel direct:
 * een permanente PARTIAL_WAKE_LOCK schakelt Doze/App Standby voor de volle
 * looptijd van de service volledig uit, ongeacht daadwerkelijke activiteit.
 *
 * Nieuw gedrag: geen enkele wakelock tijdens de wachtperiode tussen cycli
 * (`PredictiveReconnectAlarm` wekt de CPU zelf wel op tijd) — pas
 * `keepAwake()` aangeroepen vlak vóór het daadwerkelijke scanwerk begint
 * (in beide drivers' `scheduleScanAttempt()`, ná de cooldown- én
 * ScanRateLimiter-wachttijd, vlak vóór `startConnectScan()`), met een
 * kortlopende, ZELF-VERLOPENDE timeout (`acquire(durationMs)`, geen losse
 * `release()`-aanroep nodig — dat voorkomt een hele nieuwe klasse "vergeten
 * los te laten"-lekken). Ruim marge boven het gedocumenteerde worst-case
 * scanpad (Ronde 34/35: tot 117s scan-dispatch-vertraging in diepe Doze,
 * plus CareSens Air's eigen ~30s open-verbindingsvenster) — en mocht een
 * scan toch langer duren dan dat, dan roept `scheduleRearm()` (Ronde 26/76)
 * sowieso opnieuw `scheduleScanAttempt()` aan, wat de wakelock dan gewoon
 * ververst.
 *
 * `ConnectionWatchdog.kt` (Ronde 28, elke 6 min) en `PredictiveReconnectAlarm`
 * zelf blijven ONGEWIJZIGD — die zijn al AlarmManager-gebaseerd en waren
 * nooit van deze wakelock afhankelijk voor hun eigen wektiming.
 *
 * Eén gedeelde instantie voor BEIDE slots tegelijk (net als ScanRateLimiter/
 * PredictiveReconnectAlarm) — `setReferenceCounted(false)` betekent dat een
 * nieuwe `acquire()`-aanroep vanuit slot B simpelweg de bestaande periode
 * verlengt/vervangt i.p.v. een eigen telling bij te houden; met twee
 * onafhankelijke sloten die elk om de ~5 minuten kort actief zijn, is dat
 * precies het gewenste gedrag (CPU wakker zolang minstens één slot ergens
 * middenin actief BLE-werk zit).
 */
object ActiveWorkWakeLock {

    private const val TAG_NAME = "FCLGlucoLink::ActiveBleWork"

    // Zie klasse-kdoc: ruim boven het gedocumenteerde worst-case scanpad.
    private const val DEFAULT_DURATION_MS = 180_000L // 3 minuten.

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /** Aanroepen vanuit BleConnectionService.onCreate() — maakt de
     *  onderliggende PowerManager.WakeLock eenmalig aan (zonder 'm meteen
     *  vast te houden; dat gebeurt pas per aanroep van [keepAwake]). */
    @Synchronized
    fun ensure(context: Context) {
        if (wakeLock != null) return
        val powerManager = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_NAME)
            ?.apply { setReferenceCounted(false) }
    }

    /** Aanroepen vlak vóór daadwerkelijk BLE-scanwerk begint. Zelf-verlopend
     *  — geen release() nodig, zie klasse-kdoc. */
    @Synchronized
    fun keepAwake(durationMs: Long = DEFAULT_DURATION_MS) {
        runCatching { wakeLock?.acquire(durationMs) }
    }

    /** Aanroepen vanuit BleConnectionService.onDestroy() — vangnet voor het
     *  zeldzame geval dat er nog een acquire()-periode loopt op het moment
     *  dat de service echt stopt. */
    @Synchronized
    fun releaseAll() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }
}
