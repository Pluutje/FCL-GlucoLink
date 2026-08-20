package com.fclglucolink.app

import android.app.Application
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 30/07/2026 (editor) — bewust minimaal: AppSettings/GlucoseReadingStore zijn
 * allebei al zelf lichte wrappers om een singleton (DataStore-extension-
 * property resp. Room's companion-object-singleton), dus er is geen losse
 * Application-brede container/DI-framework (Hilt e.d.) nodig — elke plek
 * die ze gebruikt construeert 'm gewoon zelf met een Context, net zoals
 * FCLvNext's eigen "Bridge"-objecten (FclProfileBridge e.d.) het licht
 * houden.
 *
 * 04/08/2026 (editor, RONDE 35) — enige uitzondering op "bewust minimaal"
 * hierboven: DiagnosticFileLogger.init() moet één keer, zo vroeg mogelijk in
 * het procesleven, de laatst opgeslagen schakelaarstand inlezen — zie de
 * kdoc bij DiagnosticFileLogger.setEnabled() voor waarom dit een in-memory
 * vlaggetje is (niet bij elke logregel een suspend DataStore-lezing, dat
 * zou de BLE-callback-thread kunnen vertragen). DataStore-lezing is
 * suspend, dus via een korte gelanceerde coroutine — de paar milliseconden
 * vóórdat dit doorkomt zijn nooit een probleem, er gebeurt toch nog niets
 * BLE-gerelateerds zo vroeg in de app-start.
 *
 */
class FclGlucoLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — MOET
        // synchroon vóór alles hieronder én vóór MainActivity's eigen
        // settings-lezingen voltooid zijn: zie AppSettings.
        // migrateLegacySingleSlotDataOnce()'s kdoc — deze eenmalige migratie
        // kopieert de oude, niet-slot-specifieke instellingen naar Slot A
        // zodat een bestaande gebruiker na de dual-slot-update precies
        // verder gaat waar hij gebleven was (geen "sensor kwijt"-verrassing
        // bij de eerste opstart na de update). Bewust `runBlocking` (i.p.v.
        // een gewone gelanceerde coroutine zoals de diagnostic-logging
        // hieronder) — een race waarbij MainActivity's LaunchedEffect(Unit)
        // settings.selectedSensor(SensorSlot.A) leest VOORDAT deze migratie
        // voltooid is, zou een bestaande sensor bij die eerste opstart
        // gewoon niet vinden. Kost hier maar een paar DataStore-lezingen/
        // -schrijvingen (milliseconden), eenmalig — de
        // MIGRATION_DUAL_SLOT_DONE-vlag zorgt voor een meteen-terugkerende
        // no-op bij elke volgende app-start daarna.
        // 10/08/2026 (editor, RONDE 80) — TWEEDE, apart bewaakte migratie
        // (zie AppSettings.migrateLegacyCalibrationToSlotAOnce()'s kdoc):
        // calibrationMode/calibrationManualOffsetMmol werden pas nu per-slot,
        // NA dat de hoofdmigratie hierboven al op bestaande installaties had
        // gedraaid — kon dus niet simpelweg aan die functie worden
        // toegevoegd. Zelfde runBlocking-redenering: moet voltooid zijn
        // vóórdat enig scherm de nieuwe per-slot calibratie-instellingen
        // leest.
        runBlocking {
            val settings = AppSettings(this@FclGlucoLinkApp)
            settings.migrateLegacySingleSlotDataOnce()
            settings.migrateLegacyCalibrationToSlotAOnce()
        }
        DiagnosticFileLogger.init(this, initiallyEnabled = false)
        CoroutineScope(Dispatchers.IO).launch {
            val settings = AppSettings(this@FclGlucoLinkApp)
            DiagnosticFileLogger.setEnabled(settings.isDiagnosticFileLoggingEnabled())
        }
    }
}
