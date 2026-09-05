package com.fclglucolink.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fclglucolink.app.alarm.AlarmAlertMode
import com.fclglucolink.app.alarm.AlarmEscalation
import com.fclglucolink.app.alarm.AlarmType
import com.fclglucolink.app.calibration.CalibrationMode
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.caresensair.CareSensAirScanResult
import com.fclglucolink.app.sensor.simulator.PersistedSimulatorMode
import com.fclglucolink.app.smoothing.SmoothingStrength
import com.fclglucolink.app.ui.GlucoseUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 30/07/2026 (editor) — bewust simpele DataStore-preferences i.p.v. Room voor
 * instellingen: geen queries nodig, gewoon los opgeslagen velden.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — GROOT herontwerp:
 * elk veld dat "de actieve sensor" beschrijft (welk type, welk BLE-adres, en
 * alle CareSens-/G6-identiteitsvelden) was tot vandaag een enkele, kale
 * DataStore-sleutel — er was letterlijk geen plek om een tweede sensor
 * tegelijk in kwijt te kunnen. Vanaf nu heeft bijna elke per-sensor-functie
 * een `slot: SensorSlot`-parameter (zie SensorDriver.kt's kdoc bij
 * SensorSlot); de onderliggende sleutel wordt dan `"${basisnaam}_${slot.suffix}"`
 * (bv. `selected_sensor_a` / `selected_sensor_b`) via de `slotXxx()`-
 * hulpfuncties hieronder, i.p.v. 80+ los gedeclareerde sleutels in Keys.
 *
 * Wat NIET per slot is (bewust gedeeld/globaal, ongewijzigd): de
 * app-brede togglegs calibrationEnabled/smoothingEnabled/
 * bondLossAutoRecoveryEnabled/diagnosticFileLoggingEnabled/
 * batteryOptimizationLastPromptedAtMs — dat blijven, net als vandaag,
 * instellingen die voor de hele app gelden, niet per fysieke sensor.
 * (Kan later alsnog per-slot worden als daar behoefte aan blijkt; bewust
 * NIET vooruit-gebouwd zonder concreet verzoek.)
 *
 * 10/08/2026 (editor, RONDE 80, op verzoek na live-test — "functioneel moet
 * de calibratie ook sensor afhankelijk worden nu kan ik wel een ofset kiezen
 * maar die wordt dan gelijk bij zowel slot a als b gebruikt") —
 * calibrationMode/calibrationManualOffsetMmol zijn NU WEL per-slot (de
 * concrete behoefte die de kdoc hierboven al voorzag). calibrationEnabled
 * (de aan/uit-hoofdschakelaar, geen waarde) blijft bewust globaal — daar is
 * geen apart verzoek voor. Zie [migrateLegacyCalibrationToSlotAOnce] voor de
 * bijbehorende, SEPARAAT bewaakte migratie (de hoofdmigratie
 * [migrateLegacySingleSlotDataOnce] had op het moment van dit verzoek al op
 * bestaande installaties gedraaid, dus kon niet simpelweg uitgebreid worden).
 *
 * De oude blunte `broadcastEnabled`-schakelaar (simpel aan/uit, geen
 * bronkeuze) is vervangen door `aapsActiveSlot: SensorSlot?` — precies het
 * gevraagde model: "beide slots kunnen zenden naar AAPS waarbij er
 * uiteraard maar max 1 actief kan zijn, maar ze moeten ook beiden uit
 * kunnen" (null = geen van beide zendt).
 *
 * Migratie: [migrateLegacySingleSlotDataOnce] kopieert bij de eerste start
 * na deze update alle bestaande (pre-multi-slot) waarden één keer naar Slot
 * A, zodat een bestaande installatie niets kwijtraakt. Zie die functie's
 * kdoc voor het volledige mechanisme.
 */
private val Context.dataStore by preferencesDataStore(name = "fclglucolink_settings")

/** 29/08/2026 (editor, RONDE 163) — zie [AppSettings.simulatorBaselineMgdl]'s
 *  kdoc: 126 mg/dL (7,0 mmol/L), zelfde default als SimulatorSetupScreen.kt's
 *  bestaande "Manual value"-veld. */
private const val DEFAULT_SIMULATOR_BASELINE_MGDL = 126.0

class AppSettings(private val context: Context) {

    private object Keys {
        // ===== Niet-slot-gebonden (app-breed) =====
        val BATTERY_OPTIMIZATION_LAST_PROMPTED_AT_MS = longPreferencesKey("battery_optimization_last_prompted_at_ms")
        val DIAGNOSTIC_FILE_LOGGING_ENABLED = booleanPreferencesKey("diagnostic_file_logging_enabled")
        val CALIBRATION_ENABLED = booleanPreferencesKey("calibration_enabled")
        val SMOOTHING_ENABLED = booleanPreferencesKey("smoothing_enabled")

        // 29/08/2026 (editor, RONDE 160, op verzoek: "Aan/uit bij de settings
        // is een goede aanvulling" — voor de nieuwe 1-uur-Bg-voorspellingsband
        // op de grafiek, zie prediction/GlucosePrediction.kt) — app-breed
        // (geen per-slot), net als SMOOTHING_ENABLED/CALIBRATION_ENABLED
        // hierboven: dezelfde toggle geldt voor GlucoseChart (per-slot) EN
        // DualGlucoseChart (Combi-tab). Default UIT — zelfde conventie als
        // elke andere gedragswijzigende toggle in dit bestand (smoothing/
        // calibratie/break-in-filter): een bestaande gebruiker die de knop
        // niet aanraakt, ziet niets veranderen.
        val PREDICTION_ENABLED = booleanPreferencesKey("prediction_enabled")

        // 18/08/2026 (editor, RONDE 114, op verzoek: "een algemene filtering
        // sterkte 3 keuze schakelaar [...] onder de enable smoothing die dan
        // indien enable uitgeschakeld ook grijs wordt") — zie
        // smoothing/KalmanSmoother.kt's SmoothingStrength-kdoc. Bewust
        // GLOBAAL (net als SMOOTHING_ENABLED hierboven), niet per-slot —
        // dezelfde reden als daar: dit is een algemene, sensor-onafhankelijke
        // instelling.
        val SMOOTHING_STRENGTH = stringPreferencesKey("smoothing_strength")
        val BOND_LOSS_AUTO_RECOVERY_ENABLED = booleanPreferencesKey("bond_loss_auto_recovery_enabled")

        // 16/08/2026 (editor, RONDE 111, op verzoek: "zou er een instelbare
        // filtering mogelijk zijn die de eerste 2 dagen iets heftiger
        // filtert en dan langzaam afbouwt" — n.a.v. community-meldingen dat
        // CareSens Air de eerste dag(en) "springerig" kan zijn en AAPS/
        // FCLvNext daardoor onterecht kan reageren op ruisgevoelige
        // STIJGINGEN) — app-breed, geen per-slot (net als SMOOTHING_ENABLED
        // zelf): de gebruiker wil één instelling die voor elke sensor in elk
        // slot geldt, niet per sensortype (zie het gesprek — "in principe
        // heeft iedere sensor er last van"). Hangt bewust ONDER smoothing
        // (alleen relevant als smoothing zelf aan staat) — zie
        // smoothing/KalmanSmoother.kt's klasse-kdoc voor het volledige
        // mechanisme en de doorgerekende afweging.
        val SMOOTHING_BREAK_IN_FILTER_ENABLED = booleanPreferencesKey("smoothing_breakin_filter_enabled")

        // Duur in uren totdat de extra inloop-demping (nagenoeg) volledig is
        // afgebouwd — exponentieel, τ = deze waarde / 5 (zie KalmanSmoother.kt).
        val SMOOTHING_BREAK_IN_FILTER_DURATION_HOURS = doublePreferencesKey("smoothing_breakin_filter_duration_hours")

        // 18/08/2026 (editor, RONDE 113, op verzoek: "toon gefilterde data op
        // hoofdscherm") — losse, app-brede (niet per-slot) aan/uit-toggle voor
        // de nieuwe raw/gekalibreerd/gefilterd-regel op StatusScreen.kt/
        // CombiScreen.kt. Bewust ONAFHANKELIJK van of de waarden daadwerkelijk
        // verschillen — zie GlucoseReading.calibratedMgdl's kdoc en
        // StatusScreen.kt's SlotStatusContent voor de volledige aanleiding
        // (het gesprek verwierp expliciet het bestaande "alleen tonen bij
        // verschil"-patroon van de oude raw-indicator in BgRingDisplay).
        val SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN = booleanPreferencesKey("smoothing_show_pipeline_on_main_screen")

        // 24/08/2026 (editor, RONDE 125, op verzoek: "een breakout filter wat
        // eigenlijk precies omgekeerd werkt tov de breakin" — na CareSens
        // Air-meldingen dat sensoren de laatste dagen van hun looptijd weer
        // instabiel worden) — spiegelbeeld van SMOOTHING_BREAK_IN_FILTER_*
        // hierboven, app-breed net als die twee. Zie
        // BleConnectionService.kt's computeBreakOutDecayFactor() voor hoe de
        // "einde van de looptijd"-schatting per sensortype bepaald wordt en
        // smoothing/KalmanSmoother.kt's klasse-kdoc voor het volledige,
        // gedeelde demp-mechanisme.
        val SMOOTHING_BREAK_OUT_FILTER_ENABLED = booleanPreferencesKey("smoothing_breakout_filter_enabled")

        // Duur in uren VOOR het geschatte einde waarop de extra uitloop-
        // demping begint op te bouwen — zelfde exponentiële opbouw als
        // SMOOTHING_BREAK_IN_FILTER_DURATION_HOURS, alleen in de tijd
        // omgekeerd. UI-max 96u (SettingsScreen.kt).
        val SMOOTHING_BREAK_OUT_FILTER_DURATION_HOURS = doublePreferencesKey("smoothing_breakout_filter_duration_hours")

        // 13/08/2026 (editor, RONDE 104 — Fase 1, op verzoek: "een mg/dl vs
        // mmol/l knop") — app-breed, geen per-slot: de weergave-eenheid is een
        // voorkeur van de gebruiker, geen eigenschap van een fysieke sensor
        // (zie klasse-kdoc's globaal-vs-per-slot-regel bovenaan dit bestand).
        val DISPLAY_UNIT = stringPreferencesKey("display_unit")

        // 13/08/2026 (editor, RONDE 106, Fase 2 stap 1, op verzoek: "1 overal
        // knop om in 1 keer alle alarmen aan/uit te zetten") — hoofdschakelaar
        // voor het hele alarmsysteem, zie alarm/AlarmType.kt's klasse-kdoc.
        // De losse per-type instellingen (aan/uit/drempel/voorlooptijd/
        // geluid/trilling × 7 types) gebruiken de alarmXxx()-sleutelfabrieken
        // hieronder (zelfde patroon als slotXxx() voor sensoren) i.p.v. hier
        // tientallen losse Keys-velden te declareren.
        val ALARMS_MASTER_ENABLED = booleanPreferencesKey("alarms_master_enabled")

        // 10/08/2026 (editor, RONDE 80) — de OUDE, nu-legacy globale
        // calibratie-sleutels: alleen nog gelezen door
        // [migrateLegacyCalibrationToSlotAOnce], nooit meer beschreven. Per-
        // slot vervangers gaan via slotString("calibration_mode", slot) /
        // slotDouble("calibration_manual_offset_mmol", slot) hieronder — zelfde
        // basisnaam, dus de migratie is een simpele 1-op-1 kopie naar Slot A.
        val LEGACY_CALIBRATION_MODE = stringPreferencesKey("calibration_mode")
        val LEGACY_CALIBRATION_MANUAL_OFFSET_MMOL = doublePreferencesKey("calibration_manual_offset_mmol")

        // 10/08/2026 (editor, RONDE 80) — bewaakt dat
        // migrateLegacyCalibrationToSlotAOnce() precies één keer draait.
        // APART van MIGRATION_DUAL_SLOT_DONE: die hoofdmigratie had op
        // bestaande installaties (incl. de gebruiker's toestel) al gedraaid
        // vóórdat dit per-slot-calibratie-verzoek er was, dus een nieuwe,
        // eigen vlag is nodig — anders zou deze migratie op zulke toestellen
        // nooit meer lopen.
        val MIGRATION_CALIBRATION_PER_SLOT_DONE = booleanPreferencesKey("migration_calibration_per_slot_done")

        // 10/08/2026 (editor, RONDE 79) — vervangt BROADCAST_ENABLED (oude
        // blunte aan/uit-schakelaar zonder bronkeuze). "A"/"B"/afwezig=null.
        val AAPS_ACTIVE_SLOT = stringPreferencesKey("aaps_active_slot")

        // 20/08/2026 (editor, RONDE 115, op verzoek: "een knop in te voeren
        // die bij ingeschakeld iedere sensor (ook de virtuele) een
        // universele code mee geeft die zowel in aaps 3 als 4 werkt") — zie
        // XDripBroadcaster.kt's kdoc bij [XDripBroadcaster.sourceInfo] voor
        // de volledige analyse (AAPS v3.4 vs v4-dev SourceSensor-whitelists)
        // die tot de gekozen waarde leidde. Bewust GLOBAAL (net als
        // AAPS_ACTIVE_SLOT hierboven), niet per-slot — geldt voor elke
        // sensor die naar AAPS zendt.
        val XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED = booleanPreferencesKey("xdrip_universal_source_code_enabled")

        // 10/08/2026 (editor, RONDE 79) — bewaakt dat migrateLegacySingleSlotDataOnce()
        // precies één keer draait, zie die functie's kdoc.
        val MIGRATION_DUAL_SLOT_DONE = booleanPreferencesKey("migration_dual_slot_done")

        // ===== Legacy (pre-RONDE-79) sleutels — alleen nog gelezen door de
        // eenmalige migratie hieronder, nooit meer beschreven. Namen
        // ongewijzigd t.o.v. vóór deze ronde, zodat bestaande DataStore-data
        // op het toestel gewoon aansluit. =====
        val LEGACY_SELECTED_SENSOR = stringPreferencesKey("selected_sensor")
        val LEGACY_DEVICE_ADDRESS = stringPreferencesKey("device_address")
        val LEGACY_BROADCAST_ENABLED = booleanPreferencesKey("broadcast_enabled")
        val LEGACY_SENSOR_STARTED_AT_MS = longPreferencesKey("sensor_started_at_ms")
        val LEGACY_CARESENS_SENSOR_CODE = stringPreferencesKey("caresens_sensor_code")
        val LEGACY_CARESENS_SERIAL = stringPreferencesKey("caresens_serial")
        val LEGACY_CARESENS_PIN = stringPreferencesKey("caresens_pin")
        val LEGACY_CARESENS_EXPIRY_YYMMDD = stringPreferencesKey("caresens_expiry_yymmdd")
        val LEGACY_CARESENS_NEXT_SEQUENCE = intPreferencesKey("caresens_next_sequence")
        val LEGACY_CARESENS_SENSOR_STARTED_AT_MS = longPreferencesKey("caresens_sensor_started_at_ms")
        val LEGACY_CARESENS_LAST_CONNECTED_AT_MS = longPreferencesKey("caresens_last_connected_at_ms")
        val LEGACY_DEXCOM_G6_TRANSMITTER_ID = stringPreferencesKey("dexcom_g6_transmitter_id")
        val LEGACY_DEXCOM_G6_LAST_CONNECTED_AT_MS = longPreferencesKey("dexcom_g6_last_connected_at_ms")
        val LEGACY_DEXCOM_G6_LAST_CONFIRMED_SENSOR_CODE = stringPreferencesKey("dexcom_g6_last_confirmed_sensor_code")
        val LEGACY_DEXCOM_G6_SESSION_START_CONFIRMED_AT_MS = longPreferencesKey("dexcom_g6_session_start_confirmed_at_ms")
        val LEGACY_DEXCOM_G6_LAST_CALIBRATION_STATE = intPreferencesKey("dexcom_g6_last_calibration_state")
        val LEGACY_DEXCOM_G6_WARMUP_SECONDS = intPreferencesKey("dexcom_g6_warmup_seconds")
        val LEGACY_DEXCOM_G6_TYPICAL_SENSOR_DAYS = intPreferencesKey("dexcom_g6_typical_sensor_days")
        val LEGACY_DEXCOM_G6_VOLTAGE_A = intPreferencesKey("dexcom_g6_voltage_a")
        val LEGACY_DEXCOM_G6_VOLTAGE_B = intPreferencesKey("dexcom_g6_voltage_b")
        val LEGACY_DEXCOM_G6_TEMPERATURE_C = intPreferencesKey("dexcom_g6_temperature_c")
        val LEGACY_DEXCOM_G6_LAST_BATTERY_QUERY_AT_MS = longPreferencesKey("dexcom_g6_last_battery_query_at_ms")
        val LEGACY_CALIBRATION_CLEARED_FOR_DEVICE_ADDRESS = stringPreferencesKey("calibration_cleared_for_device_address")
        val LEGACY_SENSOR_SESSION_STARTED_FOR_DEVICE_ADDRESS = stringPreferencesKey("sensor_session_started_for_device_address")
        val LEGACY_SIMULATOR_MODE = stringPreferencesKey("simulator_active_mode")
        val LEGACY_SIMULATOR_REPEAT_MGDL = doublePreferencesKey("simulator_repeat_mgdl")
        val LEGACY_SIMULATOR_INTERVAL_MS = longPreferencesKey("simulator_interval_ms")
        val LEGACY_EXTERNAL_LIST_URI = stringPreferencesKey("simulator_external_list_uri")
        // Bewust NIET gemigreerd (blijven ongewijzigd/globaal, zie kdoc
        // bovenaan dit bestand): DEXCOM_G6_PENDING_NEW_SENSOR_CODE,
        // DEXCOM_G6_PENDING_STOP_BEFORE_START, DEXCOM_G6_PENDING_STOP_SENSOR_ONLY,
        // DEXCOM_G6_SESSION_START_FAIL_COUNT, PENDING_CROSS_TYPE_SWITCH — dit
        // zijn allemaal eenmalige/get-and-clear actie-vlaggen die na de
        // update toch leeg beginnen (er loopt op het moment van updaten nooit
        // een "pending" actie), dus niets om over te zetten.
    }

    // ===== Slot-sleutel-fabrieken =====
    // 10/08/2026 (editor, RONDE 79) — i.p.v. 80+ individueel gedeclareerde
    // Keys (elk basisveld × 2 slots): elke per-sensor-functie bouwt zijn
    // eigen sleutel met dezelfde basisnaam als vóór deze ronde, plus
    // "_a"/"_b". Zelfde basisnamen als de oude (nu LEGACY_*) sleutels
    // hierboven, dus de migratiefunctie kan simpelweg 1-op-1 overzetten.
    private fun slotString(base: String, slot: SensorSlot): Preferences.Key<String> =
        stringPreferencesKey("${base}_${slot.suffix}")
    private fun slotLong(base: String, slot: SensorSlot): Preferences.Key<Long> =
        longPreferencesKey("${base}_${slot.suffix}")
    private fun slotInt(base: String, slot: SensorSlot): Preferences.Key<Int> =
        intPreferencesKey("${base}_${slot.suffix}")
    private fun slotDouble(base: String, slot: SensorSlot): Preferences.Key<Double> =
        doublePreferencesKey("${base}_${slot.suffix}")

    // 13/08/2026 (editor, RONDE 106) — zelfde fabriekspatroon als slotXxx()
    // hierboven, nu voor per-alarmtype instellingen i.p.v. per-sensor-slot.
    // "alarm_" + basisnaam + het (lowercase) type-suffix, bv.
    // "alarm_threshold_mgdl_urgent_low".
    private fun alarmBoolean(base: String, type: AlarmType): Preferences.Key<Boolean> =
        booleanPreferencesKey("alarm_${base}_${type.name.lowercase()}")
    private fun alarmDouble(base: String, type: AlarmType): Preferences.Key<Double> =
        doublePreferencesKey("alarm_${base}_${type.name.lowercase()}")
    private fun alarmInt(base: String, type: AlarmType): Preferences.Key<Int> =
        intPreferencesKey("alarm_${base}_${type.name.lowercase()}")
    private fun alarmString(base: String, type: AlarmType): Preferences.Key<String> =
        stringPreferencesKey("alarm_${base}_${type.name.lowercase()}")
    private fun alarmLong(base: String, type: AlarmType): Preferences.Key<Long> =
        longPreferencesKey("alarm_${base}_${type.name.lowercase()}")

    // ============================================================
    // AAPS-routing (nieuw, RONDE 79) — vervangt broadcastEnabled
    // ============================================================

    /** 10/08/2026 (editor, RONDE 79, op verzoek: "beide slots moeten kunnen
     *  zenden naar aaps waarbij er uiteraard maar max 1 actief kan zijn,
     *  maar ze moeten ook beiden uit kunnen") — null = geen enkele slot
     *  zendt. `BleConnectionService` broadcast alleen readings van de slot
     *  die hier staat; de andere slot blijft gewoon lokaal verzamelen/tonen
     *  (hot standby). Wisselen is bewust één expliciete actie
     *  (setAapsActiveSlot), geen automatische arbitrage. */
    val aapsActiveSlot: Flow<SensorSlot?> = context.dataStore.data.map { prefs ->
        parseSlotOrNull(prefs[Keys.AAPS_ACTIVE_SLOT])
    }

    suspend fun setAapsActiveSlot(slot: SensorSlot?) {
        context.dataStore.edit { prefs ->
            if (slot == null) prefs.remove(Keys.AAPS_ACTIVE_SLOT) else prefs[Keys.AAPS_ACTIVE_SLOT] = slot.name
        }
    }

    /** Eenmalige lezing voor BleConnectionService's reading-pijplijn — zelfde
     *  reden als de bestaande isBroadcastEnabled()-stijl "once"-functies
     *  elders in dit bestand: geen losse Flow-collector nodig per meting. */
    suspend fun getAapsActiveSlotOnce(): SensorSlot? =
        parseSlotOrNull(context.dataStore.data.first()[Keys.AAPS_ACTIVE_SLOT])

    private fun parseSlotOrNull(raw: String?): SensorSlot? =
        raw?.let { runCatching { SensorSlot.valueOf(it) }.getOrNull() }

    /** RONDE 115 — zie [Keys.XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED]'s kdoc en
     *  XDripBroadcaster.kt's kdoc bij [XDripBroadcaster.sourceInfo]. Default
     *  UIT: net als de andere togglegs in dit bestand een bewuste opt-in,
     *  geen gedragswijziging voor bestaande gebruikers die niemand gevraagd
     *  heeft. */
    val xdripUniversalSourceCodeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED] ?: false
    }

    suspend fun setXdripUniversalSourceCodeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED] = enabled }
    }

    suspend fun isXdripUniversalSourceCodeEnabledOnce(): Boolean =
        context.dataStore.data.first()[Keys.XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED] ?: false

    // ============================================================
    // Niet-slot-gebonden (app-breed, ongewijzigd t.o.v. vóór RONDE 79)
    // ============================================================

    val diagnosticFileLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIAGNOSTIC_FILE_LOGGING_ENABLED] ?: false
    }

    suspend fun setDiagnosticFileLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.DIAGNOSTIC_FILE_LOGGING_ENABLED] = enabled }
    }

    suspend fun isDiagnosticFileLoggingEnabled(): Boolean =
        context.dataStore.data.first()[Keys.DIAGNOSTIC_FILE_LOGGING_ENABLED] ?: false

    val batteryOptimizationLastPromptedAtMs: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.BATTERY_OPTIMIZATION_LAST_PROMPTED_AT_MS]
    }

    suspend fun setBatteryOptimizationLastPromptedAtMs(value: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.BATTERY_OPTIMIZATION_LAST_PROMPTED_AT_MS] = value }
    }

    /** 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
     *  [GlucoseUnit]-kdoc voor de volledige aanleiding. `MMOL` is de default
     *  (behoudt het gedrag van vóór deze ronde voor bestaande installaties).
     *  Zelfde `runCatching { ... }.getOrNull() ?: default`-patroon als
     *  [parseCalibrationMode] hieronder, voor dezelfde reden: een onbekende/
     *  corrupte opgeslagen waarde mag nooit een crash geven, gewoon terugval
     *  naar de default. */
    val displayUnit: Flow<GlucoseUnit> = context.dataStore.data.map { prefs ->
        parseDisplayUnit(prefs[Keys.DISPLAY_UNIT])
    }

    suspend fun setDisplayUnit(unit: GlucoseUnit) {
        context.dataStore.edit { prefs -> prefs[Keys.DISPLAY_UNIT] = unit.name }
    }

    suspend fun getDisplayUnitOnce(): GlucoseUnit =
        parseDisplayUnit(context.dataStore.data.first()[Keys.DISPLAY_UNIT])

    private fun parseDisplayUnit(raw: String?): GlucoseUnit =
        runCatching { raw?.let { GlucoseUnit.valueOf(it) } }.getOrNull() ?: GlucoseUnit.MMOL

    val calibrationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CALIBRATION_ENABLED] ?: false
    }

    suspend fun setCalibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CALIBRATION_ENABLED] = enabled }
    }

    suspend fun isCalibrationEnabled(): Boolean =
        context.dataStore.data.first()[Keys.CALIBRATION_ENABLED] ?: false

    // 10/08/2026 (editor, RONDE 80) — per-slot (was globaal, zie kdoc bovenaan
    // dit bestand): elke slot heeft nu zijn eigen kalibratie-modus/-offset,
    // via dezelfde slotString/slotDouble-sleutelfabriek als de rest.
    fun calibrationMode(slot: SensorSlot): Flow<CalibrationMode> = context.dataStore.data.map { prefs ->
        parseCalibrationMode(prefs[slotString("calibration_mode", slot)])
    }

    suspend fun setCalibrationMode(slot: SensorSlot, mode: CalibrationMode) {
        context.dataStore.edit { prefs -> prefs[slotString("calibration_mode", slot)] = mode.name }
    }

    suspend fun getCalibrationModeOnce(slot: SensorSlot): CalibrationMode =
        parseCalibrationMode(context.dataStore.data.first()[slotString("calibration_mode", slot)])

    private fun parseCalibrationMode(raw: String?): CalibrationMode =
        runCatching { raw?.let { CalibrationMode.valueOf(it) } }.getOrNull() ?: CalibrationMode.SPLINE

    fun calibrationManualOffsetMmol(slot: SensorSlot): Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[slotDouble("calibration_manual_offset_mmol", slot)] ?: 0.0
    }

    suspend fun setCalibrationManualOffsetMmol(slot: SensorSlot, value: Double) {
        context.dataStore.edit { prefs -> prefs[slotDouble("calibration_manual_offset_mmol", slot)] = value }
    }

    suspend fun getCalibrationManualOffsetMmolOnce(slot: SensorSlot): Double =
        context.dataStore.data.first()[slotDouble("calibration_manual_offset_mmol", slot)] ?: 0.0

    val smoothingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_ENABLED] ?: false
    }

    suspend fun setSmoothingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_ENABLED] = enabled }
    }

    suspend fun isSmoothingEnabled(): Boolean =
        context.dataStore.data.first()[Keys.SMOOTHING_ENABLED] ?: false

    /** RONDE 160 — zie [Keys.PREDICTION_ENABLED]'s kdoc. */
    val predictionEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.PREDICTION_ENABLED] ?: false
    }

    suspend fun setPredictionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PREDICTION_ENABLED] = enabled }
    }

    /** RONDE 114 — zie [Keys.SMOOTHING_STRENGTH]'s kdoc en
     *  [SmoothingStrength]'s kdoc in KalmanSmoother.kt. Default MEDIUM
     *  (schaal ×1.0) — exact het bestaande gedrag van vóór deze ronde, dus
     *  geen gedragswijziging voor gebruikers die de nieuwe knop niet
     *  aanraken. Zelfde onbekende-waarde-fallback-patroon als
     *  [parseCalibrationMode] hieronder. */
    val smoothingStrength: Flow<SmoothingStrength> = context.dataStore.data.map { prefs ->
        parseSmoothingStrength(prefs[Keys.SMOOTHING_STRENGTH])
    }

    suspend fun setSmoothingStrength(strength: SmoothingStrength) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_STRENGTH] = strength.name }
    }

    suspend fun getSmoothingStrengthOnce(): SmoothingStrength =
        parseSmoothingStrength(context.dataStore.data.first()[Keys.SMOOTHING_STRENGTH])

    private fun parseSmoothingStrength(raw: String?): SmoothingStrength =
        runCatching { raw?.let { SmoothingStrength.valueOf(it) } }.getOrNull() ?: SmoothingStrength.MEDIUM

    /** RONDE 111 — zie [Keys.SMOOTHING_BREAK_IN_FILTER_ENABLED]'s kdoc.
     *  Default UIT: een gedragswijziging voor bestaande gebruikers moet
     *  bewust aangezet worden, net als smoothing zelf. */
    val breakInFilterEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_BREAK_IN_FILTER_ENABLED] ?: false
    }

    suspend fun setBreakInFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_BREAK_IN_FILTER_ENABLED] = enabled }
    }

    suspend fun isBreakInFilterEnabledOnce(): Boolean =
        context.dataStore.data.first()[Keys.SMOOTHING_BREAK_IN_FILTER_ENABLED] ?: false

    /** RONDE 111 — default 24 uur, zoals in het gesprek als voorbeeld
     *  genoemd ("een instelling van 24 uur betekent dat het na 24 uur
     *  volledig is uitgewerkt"). */
    val breakInFilterDurationHours: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_BREAK_IN_FILTER_DURATION_HOURS] ?: 24.0
    }

    suspend fun setBreakInFilterDurationHours(hours: Double) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_BREAK_IN_FILTER_DURATION_HOURS] = hours }
    }

    suspend fun getBreakInFilterDurationHoursOnce(): Double =
        context.dataStore.data.first()[Keys.SMOOTHING_BREAK_IN_FILTER_DURATION_HOURS] ?: 24.0

    /** RONDE 125 — zie [Keys.SMOOTHING_BREAK_OUT_FILTER_ENABLED]'s kdoc.
     *  Default UIT, zelfde reden als break-in: een gedragswijziging voor
     *  bestaande gebruikers moet bewust aangezet worden. */
    val breakOutFilterEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_BREAK_OUT_FILTER_ENABLED] ?: false
    }

    suspend fun setBreakOutFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_BREAK_OUT_FILTER_ENABLED] = enabled }
    }

    suspend fun isBreakOutFilterEnabledOnce(): Boolean =
        context.dataStore.data.first()[Keys.SMOOTHING_BREAK_OUT_FILTER_ENABLED] ?: false

    /** RONDE 125 — default 48 uur, een middenwaarde binnen de 0-96u-range
     *  die in het gesprek als max genoemd werd. */
    val breakOutFilterDurationHours: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_BREAK_OUT_FILTER_DURATION_HOURS] ?: 48.0
    }

    suspend fun setBreakOutFilterDurationHours(hours: Double) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_BREAK_OUT_FILTER_DURATION_HOURS] = hours }
    }

    suspend fun getBreakOutFilterDurationHoursOnce(): Double =
        context.dataStore.data.first()[Keys.SMOOTHING_BREAK_OUT_FILTER_DURATION_HOURS] ?: 48.0

    /** RONDE 113 — zie [Keys.SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN]'s kdoc.
     *  Default UIT: net als de andere smoothing-gerelateerde togglegs hierboven
     *  is dit een bewuste opt-in, geen gedragswijziging voor bestaande
     *  gebruikers die niemand gevraagd heeft. */
    val showFilteredPipelineOnMainScreen: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN] ?: false
    }

    suspend fun setShowFilteredPipelineOnMainScreen(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN] = enabled }
    }

    suspend fun isShowFilteredPipelineOnMainScreenOnce(): Boolean =
        context.dataStore.data.first()[Keys.SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN] ?: false

    val bondLossAutoRecoveryEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BOND_LOSS_AUTO_RECOVERY_ENABLED] ?: false
    }

    suspend fun setBondLossAutoRecoveryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BOND_LOSS_AUTO_RECOVERY_ENABLED] = enabled }
    }

    suspend fun isBondLossAutoRecoveryEnabledOnce(): Boolean =
        context.dataStore.data.first()[Keys.BOND_LOSS_AUTO_RECOVERY_ENABLED] ?: false

    // ============================================================
    // Per-slot identiteit — welk sensortype/adres zit in deze slot
    // ============================================================

    fun selectedSensor(slot: SensorSlot): Flow<SensorType?> = context.dataStore.data.map { prefs ->
        prefs[slotString("selected_sensor", slot)]?.let { name ->
            runCatching { SensorType.valueOf(name) }.getOrNull()
        }
    }

    suspend fun getSelectedSensorOnce(slot: SensorSlot): SensorType? =
        selectedSensor(slot).first()

    fun deviceAddress(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("device_address", slot)]
    }

    suspend fun getDeviceAddressOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("device_address", slot)]

    /**
     * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — handige
     * samenvatting voor de plekken die alleen willen weten OF er ergens een
     * sensor geconfigureerd is, om te beslissen of BleConnectionService
     * (opnieuw) gestart moet worden (MainActivity.kt's herstart-check,
     * ConnectionWatchdog.kt's wekker/boot-receiver) — die plekken hoeven zelf
     * niet te weten WELKE slot(s) dat zijn, dat bepaalt BleConnectionService's
     * eigen onStartCommand()-lus per slot onafhankelijk (zie dat bestand).
     */
    suspend fun hasAnySlotConfigured(): Boolean = SensorSlot.entries.any { slot ->
        getSelectedSensorOnce(slot) != null && getDeviceAddressOnce(slot) != null
    }

    /**
     * 10/08/2026 (editor, RONDE 79) — zelfde onderscheid als vóór deze ronde
     * (zie de uitgebreide historische kdoc die hier tot RONDE 78 stond, nu
     * verkort): bij het kiezen van een sensor-TYPE voor een slot wordt alleen
     * het GENERIEKE, sessie-gebonden deel gewist (device-adres van deze
     * slot, de generieke sensor_started_at_ms-fallback, een eventuele
     * pending G6-nieuwe-sensor-code) — de eigenlijke CareSens-/G6-
     * IDENTITEITSVELDEN (transmitter-ID, gescande barcode, batterij-info,
     * laatste-verbinding) blijven staan, want die horen bij het TYPE zelf
     * (al gescheiden via de sleutelnaam), niet bij "wat toevallig actief
     * is" — zo hoeft bv. een G6-transmitter-ID niet opnieuw getypt te
     * worden na tijdelijk terug- en weer-overschakelen. Alles hier is nu
     * bovendien slot-gebonden: Slot A en Slot B kunnen dus onafhankelijk
     * van elkaar, zelfs met hetzelfde sensortype, hun eigen identiteit
     * vasthouden.
     */
    suspend fun setSelectedSensor(slot: SensorSlot, sensorType: SensorType) {
        context.dataStore.edit { prefs ->
            val key = slotString("selected_sensor", slot)
            val previousSensor = prefs[key]
            val isRealSwitch = previousSensor != sensorType.name
            prefs[key] = sensorType.name
            if (!isRealSwitch) return@edit
            prefs.remove(slotString("device_address", slot))
            prefs.remove(slotLong("sensor_started_at_ms", slot))
            prefs.remove(slotString("dexcom_g6_pending_new_sensor_code", slot))
            prefs[booleanPreferencesKey("pending_cross_type_switch_${slot.suffix}")] = true
        }
    }

    /**
     * 10/08/2026 (editor, RONDE 80, letterlijk verzoek — "dat ik als sensor
     * ook geen kan kiezen bij de sensoren") — expliciete "None"-keuze voor een
     * slot: verwijdert zowel de gekozen sensor-TYPE-sleutel als het device-
     * adres van deze slot (zelfde adres-wis-stap als [clearDeviceAddress]/de
     * bestaande "Disconnect"-knoppen, zodat BleConnectionService's
     * onStartCommand()-lus voor deze slot niets meer vindt om mee te
     * verbinden). Laat, net als [setSelectedSensor]'s kdoc hierboven al voor
     * een type-WISSEL beschrijft, de type-specifieke IDENTITEITSVELDEN
     * (G6-transmitter-ID, CareSens-scan e.d.) bewust ongemoeid — die horen
     * bij het type zelf, niet bij "is dit slot nu actief", zodat later
     * opnieuw kiezen voor hetzelfde type de setup-wizard weer kan overslaan.
     *
     * Als deze slot toevallig de AAPS-zendende slot was, wordt [aapsActiveSlot]
     * ook meteen leeggemaakt — een lege slot kan nooit een geldige
     * AAPS-databron zijn, dus zonder deze stap zou de UI een tabblad als
     * "Sending to AAPS" blijven tonen terwijl er feitelijk niets meer aan
     * hangt.
     */
    suspend fun clearSelectedSensor(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            prefs.remove(slotString("selected_sensor", slot))
            prefs.remove(slotString("device_address", slot))
            if (parseSlotOrNull(prefs[Keys.AAPS_ACTIVE_SLOT]) == slot) {
                prefs.remove(Keys.AAPS_ACTIVE_SLOT)
            }
        }
    }

    suspend fun setDeviceAddress(slot: SensorSlot, address: String) {
        context.dataStore.edit { prefs -> prefs[slotString("device_address", slot)] = address }
    }

    /** Voor de "Verbinding verbreken"-actie: laat selectedSensor bewust
     *  staan (handig als "laatst gebruikt"), wist alleen het device-adres
     *  van déze slot. */
    suspend fun clearDeviceAddress(slot: SensorSlot) {
        context.dataStore.edit { prefs -> prefs.remove(slotString("device_address", slot)) }
    }

    suspend fun hasKnownDexcomG6TransmitterOnce(slot: SensorSlot): Boolean =
        getDexcomG6TransmitterIdOnce(slot) != null

    suspend fun hasKnownCareSensAirScanOnce(slot: SensorSlot): Boolean =
        getCareSensAirScanOnce(slot) != null

    /** Get-and-clear, per slot — zie setSelectedSensor()'s kdoc: signaal
     *  voor de sensor-wisselmarker op de BG-grafiek van déze slot. */
    suspend fun consumePendingCrossTypeSwitch(slot: SensorSlot): Boolean {
        val key = booleanPreferencesKey("pending_cross_type_switch_${slot.suffix}")
        var wasSet = false
        context.dataStore.edit { prefs ->
            wasSet = prefs[key] ?: false
            prefs.remove(key)
        }
        return wasSet
    }

    /** Generieke sessie-start-fallback (types zonder eigen "echt"
     *  startmoment, zoals de simulator) — per slot, éénmalig gezet zodra
     *  er voor het eerst mee verbonden wordt. */
    suspend fun getOrInitSensorStartedAtMs(slot: SensorSlot): Long {
        val key = slotLong("sensor_started_at_ms", slot)
        val existing = context.dataStore.data.first()[key]
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        context.dataStore.edit { prefs -> prefs[key] = now }
        return now
    }

    /** 11/08/2026 (editor, RONDE 90) — passieve, NIET-initialiserende variant
     *  van [getOrInitSensorStartedAtMs] — voor weergave-only contexten
     *  (CombiScreen.kt's fingerprik-markers) waar geen sensor-sessie hoeft
     *  te bestaan, en dus ook geen bijwerking mag optreden als 'm nog niet
     *  bestaat (in tegenstelling tot CalibrationScreen.kt, dat alleen
     *  bereikbaar is vanuit een tab MET een actieve sensor, en dus gewoon
     *  de suspend-variant hierboven blijft gebruiken). Geeft `null` terug
     *  als er nog geen sessie is geweest voor deze slot. */
    fun sensorStartedAtMsFlow(slot: SensorSlot): Flow<Long?> =
        context.dataStore.data.map { prefs -> prefs[slotLong("sensor_started_at_ms", slot)] }

    /** Sensortype-specifieke, ECHT bij elke NIEUWE fysieke sensor herziene
     *  starttijd (CareSens Air/Dexcom G6) — `null` voor types zonder eigen
     *  sessie-tracking (simulator, Dexcom G7 nog niet), zie
     *  [effectiveSensorSessionStartedAtMs]'s kdoc voor de aanleiding. */
    private fun typeSpecificSensorStartedAtMsFlow(slot: SensorSlot, sensorType: SensorType): Flow<Long?> =
        when (sensorType) {
            SensorType.CARESENS_AIR -> careSensAirSensorStartedAtMs(slot)
            SensorType.DEXCOM_G6 -> dexcomG6SessionStartConfirmedAtMs(slot)
            else -> flowOf(null)
        }

    /**
     * 22/08/2026 (editor, RONDE 122, CRITICAL FIX — op verzoek na live-
     * melding: "het viel me op dat de calibratie curve van de vorige sensor
     * nog steeds actief was nadat deze was gestart") — het EFFECTIEVE,
     * sensortype-bewuste sessie-startmoment voor deze slot: gebruikt bij
     * voorkeur de sensortype-specifieke, ECHT bij elke NIEUWE fysieke
     * sensor herziene starttijd (CareSens Air's [careSensAirSensorStartedAtMs]/
     * Dexcom G6's [dexcomG6SessionStartConfirmedAtMs] — die worden
     * daadwerkelijk opnieuw gezet bij elke stop/start-cyclus, zie hun eigen
     * aanroepsites in CareSensAirDriver.kt/DexcomG6Driver.kt), met
     * [getOrInitSensorStartedAtMs] als vangnet voor sensortypes zonder eigen
     * sessie-tracking (simulator, Dexcom G7 nog niet).
     *
     * Dit is EXACT dezelfde voorkeursvolgorde die
     * BleConnectionService.kt's computeBreakInDecayFactor() (Ronde 111) al
     * gebruikte voor het inloopfilter — nu hier gecentraliseerd en OOK
     * gebruikt door de kalibratie-toepassing zelf. Root cause van de
     * melding: `applyCalibrationIfEnabled()` (en CalibrationScreen.kt's
     * `sinceMs`) gebruikten tot deze ronde ALLEEN de generieke
     * [getOrInitSensorStartedAtMs] — die sleutel wordt uitsluitend bij een
     * sensor-TYPE-wissel gewist (zie [setSelectedSensor]'s kdoc), NIET bij
     * het starten van een NIEUWE FYSIEKE sensor van hetzelfde type. Gevolg:
     * vingerprik-entries (en dus de fit-curve) van een VORIGE fysieke
     * sensor bleven na een nieuwe-sensor-start gewoon meewegen, precies de
     * gemelde bug.
     */
    suspend fun effectiveSensorSessionStartedAtMs(slot: SensorSlot, sensorType: SensorType): Long =
        typeSpecificSensorStartedAtMsFlow(slot, sensorType).first() ?: getOrInitSensorStartedAtMs(slot)

    /** Passieve, NIET-initialiserende Flow-variant van
     *  [effectiveSensorSessionStartedAtMs] — voor weergave-only contexten
     *  (CombiScreen.kt's fingerprik-markers), zelfde reden als
     *  [sensorStartedAtMsFlow]'s kdoc: `null` als er nog geen sessie is,
     *  geen bijwerking. */
    fun effectiveSensorSessionStartedAtMsFlow(slot: SensorSlot, sensorType: SensorType): Flow<Long?> =
        combine(typeSpecificSensorStartedAtMsFlow(slot, sensorType), sensorStartedAtMsFlow(slot)) { typeSpecific, generic ->
            typeSpecific ?: generic
        }

    // ============================================================
    // CareSens Air — per slot
    // ============================================================

    suspend fun saveCareSensAirScan(slot: SensorSlot, result: CareSensAirScanResult) {
        context.dataStore.edit { prefs ->
            prefs[slotString("caresens_sensor_code", slot)] = result.sensorCode
            prefs[slotString("caresens_serial", slot)] = result.serial
            prefs[slotString("caresens_pin", slot)] = result.pin
            prefs[slotString("caresens_expiry_yymmdd", slot)] = result.expiryYyMmDd
        }
    }

    fun careSensAirScan(slot: SensorSlot): Flow<CareSensAirScanResult?> = context.dataStore.data.map { prefs ->
        val sensorCode = prefs[slotString("caresens_sensor_code", slot)]
        val serial = prefs[slotString("caresens_serial", slot)]
        val pin = prefs[slotString("caresens_pin", slot)]
        val expiry = prefs[slotString("caresens_expiry_yymmdd", slot)]
        if (sensorCode != null && serial != null && pin != null && expiry != null) {
            CareSensAirScanResult(sensorCode = sensorCode, serial = serial, pin = pin, expiryYyMmDd = expiry)
        } else {
            null
        }
    }

    suspend fun getCareSensAirScanOnce(slot: SensorSlot): CareSensAirScanResult? = careSensAirScan(slot).first()

    suspend fun getCareSensAirNextSequence(slot: SensorSlot): Int =
        context.dataStore.data.first()[slotInt("caresens_next_sequence", slot)] ?: 0

    suspend fun setCareSensAirNextSequence(slot: SensorSlot, value: Int) {
        context.dataStore.edit { prefs -> prefs[slotInt("caresens_next_sequence", slot)] = value }
    }

    suspend fun setCareSensAirSensorStartedAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("caresens_sensor_started_at_ms", slot)] = value }
    }

    fun careSensAirSensorStartedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("caresens_sensor_started_at_ms", slot)]
    }

    suspend fun setCareSensAirLastConnectedAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("caresens_last_connected_at_ms", slot)] = value }
    }

    fun careSensAirLastConnectedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("caresens_last_connected_at_ms", slot)]
    }

    suspend fun getCareSensAirLastConnectedAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("caresens_last_connected_at_ms", slot)]

    /** 28/08/2026 (editor, RONDE 154, CRITIEKE FIX — live-melding: "bij het
     *  koppelen van een nieuwe caresens sensor bakt hij de start en einde
     *  tijd van de oude vorige sensor nog op") — [careSensAirSensorStartedAtMs]
     *  wordt uitsluitend geschreven vanuit CareSensAirDriver.kt's handler
     *  voor het 0xC0/2-antwoord (StartSensorResponse), dus pas zodra de
     *  NIEUWE fysieke sensor daadwerkelijk een live GATT-uitwisseling heeft
     *  voltooid. De koppel-/wisselflow zelf (FclGlucoLinkNavHost.kt's
     *  ROUTE_CARESENS_AIR_CHOICE/ROUTE_CARESENS_AIR_SCAN) riep tot deze
     *  ronde nergens een reset aan — dus bleven de VORIGE sensor's Start-/
     *  End-tijd (en "Last connected") gewoon zichtbaar op
     *  CareSensAirStatusScreen.kt/StatusScreen.kt totdat die eerste nieuwe
     *  GATT-uitwisseling voltooid was. Zelfde soort proactieve
     *  cache-reset-bij-nieuwe-koppeling als Dexcom G7's
     *  [clearDexcomG7BatteryAndFirmwareInfo] (Ronde 152) — hier aangeroepen
     *  vanuit beide CareSens Air-koppelpaden (nieuw + al-lopend), vóór de
     *  navigatie naar PairingScreen. */
    suspend fun clearCareSensAirSensorSession(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            prefs.remove(slotLong("caresens_sensor_started_at_ms", slot))
            prefs.remove(slotLong("caresens_last_connected_at_ms", slot))
        }
    }

    // ============================================================
    // Dexcom G6 — per slot
    // ============================================================

    suspend fun setDexcomG6TransmitterId(slot: SensorSlot, id: String) {
        context.dataStore.edit { prefs -> prefs[slotString("dexcom_g6_transmitter_id", slot)] = id }
    }

    fun dexcomG6TransmitterId(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("dexcom_g6_transmitter_id", slot)]
    }

    suspend fun getDexcomG6TransmitterIdOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("dexcom_g6_transmitter_id", slot)]

    suspend fun setDexcomG6LastConnectedAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g6_last_connected_at_ms", slot)] = value }
    }

    fun dexcomG6LastConnectedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g6_last_connected_at_ms", slot)]
    }

    suspend fun getDexcomG6LastConnectedAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g6_last_connected_at_ms", slot)]

    suspend fun setDexcomG6PendingNewSensorCode(slot: SensorSlot, code: String) {
        context.dataStore.edit { prefs ->
            prefs[slotString("dexcom_g6_pending_new_sensor_code", slot)] = code
            prefs.remove(slotLong("dexcom_g6_session_start_confirmed_at_ms", slot))
            prefs.remove(slotInt("dexcom_g6_session_start_fail_count", slot))
            // 22/08/2026 (editor, RONDE 124, op verzoek — "als de starttijd
            // niet terug komt uit de transmitter dan moeten we gewoon de
            // starttijd [...] van het invoeren van de sensorcode
            // gebruiken") — bewaart het moment waarop DEZE code klaargezet
            // is, als terugvaloptie voor de "Started"/"End (est.)"-weergave
            // (DexcomG6StatusScreen.kt) wanneer de transmitter zelf nooit
            // een bevestigde start teruggeeft (bijv. de aanhoudende
            // infoCode=3 "Invalid"-afwijzingen). Bewust een APART veld i.p.v.
            // hergebruik van dexcom_g6_session_start_confirmed_at_ms: dat
            // laatste veld betekent overal elders in de app "ECHT door de
            // transmitter bevestigd" (warmup-aftelling, inloopfilter, zie
            // AppSettings.effectiveSensorSessionStartedAtMs()'s kdoc) — dat
            // zou ten onrechte een niet-bevestigde gok als harde waarheid
            // laten doorwerken in die berekeningen. Dit veld voedt UITSLUITEND
            // de "Started"/"End"-WEERGAVE, duidelijk gelabeld als schatting.
            prefs[slotLong("dexcom_g6_pending_new_sensor_code_queued_at_ms", slot)] = System.currentTimeMillis()
        }
    }

    fun dexcomG6PendingNewSensorCode(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("dexcom_g6_pending_new_sensor_code", slot)]
    }

    suspend fun getDexcomG6PendingNewSensorCodeOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("dexcom_g6_pending_new_sensor_code", slot)]

    suspend fun clearDexcomG6PendingNewSensorCode(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            prefs.remove(slotString("dexcom_g6_pending_new_sensor_code", slot))
            prefs.remove(slotLong("dexcom_g6_pending_new_sensor_code_queued_at_ms", slot))
        }
    }

    /** 22/08/2026 (editor, RONDE 124) — zie setDexcomG6PendingNewSensorCode()'s
     *  kdoc: het moment waarop de klaarstaande code is ingevoerd, puur als
     *  weergave-terugvaloptie. */
    fun dexcomG6PendingNewSensorCodeQueuedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g6_pending_new_sensor_code_queued_at_ms", slot)]
    }

    suspend fun setDexcomG6SessionStartConfirmedAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g6_session_start_confirmed_at_ms", slot)] = value }
    }

    suspend fun clearDexcomG6SessionStartConfirmedAtMs(slot: SensorSlot) {
        context.dataStore.edit { prefs -> prefs.remove(slotLong("dexcom_g6_session_start_confirmed_at_ms", slot)) }
    }

    fun dexcomG6SessionStartConfirmedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g6_session_start_confirmed_at_ms", slot)]
    }

    suspend fun getDexcomG6SessionStartConfirmedAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g6_session_start_confirmed_at_ms", slot)]

    suspend fun setDexcomG6LastConfirmedSensorCode(slot: SensorSlot, code: String) {
        context.dataStore.edit { prefs -> prefs[slotString("dexcom_g6_last_confirmed_sensor_code", slot)] = code }
    }

    fun dexcomG6LastConfirmedSensorCode(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("dexcom_g6_last_confirmed_sensor_code", slot)]
    }

    suspend fun incrementDexcomG6SessionStartFailCount(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            val key = slotInt("dexcom_g6_session_start_fail_count", slot)
            prefs[key] = (prefs[key] ?: 0) + 1
        }
    }

    suspend fun resetDexcomG6SessionStartFailCount(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            prefs.remove(slotInt("dexcom_g6_session_start_fail_count", slot))
            // 22/08/2026 (editor, RONDE 120) — de laatst-getoonde afwijzings-
            // reden hoort niet te blijven staan na een geslaagde start, zie
            // dexcomG6LastSessionStartInfoCode's kdoc hieronder.
            prefs.remove(slotInt("dexcom_g6_last_session_start_info_code", slot))
        }
    }

    fun dexcomG6SessionStartFailCount(slot: SensorSlot): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_session_start_fail_count", slot)] ?: 0
    }

    /**
     * 22/08/2026 (editor, RONDE 120, op verzoek — "kun je kijken wat er fout
     * gaat en dan bij de status in ieder ook wat meer info tonen [...] het
     * is nu een beetje een blackbox") — de RAUWE infoCode van de laatst
     * MISLUKTE SessionStart-poging (zie DexcomG6Protocol.kt's
     * `SessionStartRx.infoCode` en het nieuwe `sessionStartInfoMessage()`
     * dat 'm naar leesbare tekst vertaalt). Tot deze ronde toonde
     * DexcomG6StatusScreen.kt altijd dezelfde, hardcoded reden ("transmitter
     * may still see the old sensor as active", info 0x02) — een live-test
     * liet zien dat de transmitter in werkelijkheid info 0x03 ("Invalid")
     * teruggaf, een ANDERE betekenis. `null` = geen respons ontvangen
     * (timeout) i.p.v. een expliciete afwijzing — ook dat onderscheid is nu
     * zichtbaar te maken.
     */
    suspend fun setDexcomG6LastSessionStartInfoCode(slot: SensorSlot, infoCode: Int?) {
        context.dataStore.edit { prefs ->
            val key = slotInt("dexcom_g6_last_session_start_info_code", slot)
            if (infoCode == null) prefs.remove(key) else prefs[key] = infoCode
        }
    }

    fun dexcomG6LastSessionStartInfoCode(slot: SensorSlot): Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_last_session_start_info_code", slot)]
    }

    /** 22/08/2026 (editor, RONDE 120) — tijdstip van de laatste (mislukte óf
     *  geslaagde) SessionStart-poging, voor "last tried HH:mm" in de UI —
     *  zie dexcomG6LastSessionStartInfoCode's kdoc hierboven voor de
     *  aanleiding. Bewust NIET gewist bij succes (in tegenstelling tot de
     *  infoCode) — "wanneer voor het laatst geprobeerd" blijft zinvolle
     *  info, ook na een geslaagde start. */
    suspend fun setDexcomG6LastSessionStartAttemptAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g6_last_session_start_attempt_at_ms", slot)] = value }
    }

    fun dexcomG6LastSessionStartAttemptAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g6_last_session_start_attempt_at_ms", slot)]
    }

    /** 22/08/2026 (editor, RONDE 121) — tijdstip waarop de app, tijdens een
     *  klaarstaande nieuwe-sensor-poging, zelf automatisch een SessionStop
     *  verstuurde omdat de transmitter (via TransmitterTime, zie
     *  DexcomG6Driver.kt's runControlSequence()-kdoc) een nog lopende sessie
     *  meldde. Vergeleken met [dexcomG6LastSessionStartAttemptAtMs] in
     *  dexcomG6StatusText() (DexcomG6StatusScreen.kt) om te kunnen tonen
     *  "bezig met automatisch stoppen, start volgt bij de volgende
     *  verbinding" i.p.v. het generieke "Sending sensor start…" tijdens
     *  precies dát tussenmoment. */
    suspend fun setDexcomG6LastAutoStopAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g6_last_auto_stop_at_ms", slot)] = value }
    }

    fun dexcomG6LastAutoStopAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g6_last_auto_stop_at_ms", slot)]
    }

    suspend fun setDexcomG6LastCalibrationState(slot: SensorSlot, raw: Int) {
        context.dataStore.edit { prefs -> prefs[slotInt("dexcom_g6_last_calibration_state", slot)] = raw }
    }

    fun dexcomG6LastCalibrationState(slot: SensorSlot): Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_last_calibration_state", slot)]
    }

    suspend fun getDexcomG6LastCalibrationStateOnce(slot: SensorSlot): Int? =
        context.dataStore.data.first()[slotInt("dexcom_g6_last_calibration_state", slot)]

    /** `warmupSeconds` blijft nullable — zie de historische kdoc die hier
     *  tot RONDE 78 stond (xDrip+'s "short form"-antwoord is niet altijd
     *  betrouwbaar voor dit veld); `typicalSensorDays` wordt altijd
     *  opgeslagen. */
    suspend fun setDexcomG6WarmupCapability(slot: SensorSlot, warmupSeconds: Int?, typicalSensorDays: Int) {
        context.dataStore.edit { prefs ->
            if (warmupSeconds != null) prefs[slotInt("dexcom_g6_warmup_seconds", slot)] = warmupSeconds
            prefs[slotInt("dexcom_g6_typical_sensor_days", slot)] = typicalSensorDays
        }
    }

    fun dexcomG6WarmupSeconds(slot: SensorSlot): Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_warmup_seconds", slot)]
    }

    suspend fun getDexcomG6WarmupSecondsOnce(slot: SensorSlot): Int? =
        context.dataStore.data.first()[slotInt("dexcom_g6_warmup_seconds", slot)]

    fun dexcomG6TypicalSensorDays(slot: SensorSlot): Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_typical_sensor_days", slot)]
    }

    suspend fun getDexcomG6TypicalSensorDaysOnce(slot: SensorSlot): Int? =
        context.dataStore.data.first()[slotInt("dexcom_g6_typical_sensor_days", slot)]

    /** RONDE 125 — voor een G6 met Anubis-transmitter is de door de
     *  transmitter zelf gerapporteerde `typicalSensorDays` niet te
     *  vertrouwen (zie DexcomG6TransmitterType's kdoc — kan tot 60 dagen
     *  melden terwijl gebruikers in de praktijk 14-20+ dagen ervaren).
     *  Voor de uitloop-demping (computeBreakOutDecayFactor in
     *  BleConnectionService.kt) gebruiken we voor dat geval daarom deze
     *  PER-SLOT, door de gebruiker zelf ingestelde verwachte looptijd i.p.v.
     *  het transmitter-getal. Bewust per slot (niet app-breed, in
     *  tegenstelling tot de break-out-instellingen hierboven): dit is een
     *  eigenschap van DIT specifieke fysieke transmitter-exemplaar, geen
     *  algemene voorkeur — zie DexcomG6StatusScreen.kt waar dit veld getoond
     *  wordt (alleen zichtbaar als deze slot's transmitter als Anubis
     *  herkend is). Default 14 dagen, het eigen praktijkgetal uit het
     *  gesprek dat dit verzoek startte. */
    fun dexcomG6ExpectedLifespanDays(slot: SensorSlot): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[slotInt("dexcom_g6_expected_lifespan_days", slot)] ?: 14
    }

    suspend fun setDexcomG6ExpectedLifespanDays(slot: SensorSlot, days: Int) {
        context.dataStore.edit { prefs -> prefs[slotInt("dexcom_g6_expected_lifespan_days", slot)] = days }
    }

    suspend fun getDexcomG6ExpectedLifespanDaysOnce(slot: SensorSlot): Int =
        context.dataStore.data.first()[slotInt("dexcom_g6_expected_lifespan_days", slot)] ?: 14

    suspend fun getDexcomG6LastVersion2QueryAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g6_last_version2_query_at_ms", slot)]

    suspend fun setDexcomG6LastVersion2QueryAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g6_last_version2_query_at_ms", slot)] = value }
    }

    suspend fun setDexcomG6PendingStopBeforeStart(slot: SensorSlot, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[booleanPreferencesKey("dexcom_g6_pending_stop_before_start_${slot.suffix}")] = value }
    }

    suspend fun consumeDexcomG6PendingStopBeforeStart(slot: SensorSlot): Boolean {
        val key = booleanPreferencesKey("dexcom_g6_pending_stop_before_start_${slot.suffix}")
        var wasSet = false
        context.dataStore.edit { prefs ->
            wasSet = prefs[key] ?: false
            prefs.remove(key)
        }
        return wasSet
    }

    suspend fun setDexcomG6PendingStopSensorOnly(slot: SensorSlot, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[booleanPreferencesKey("dexcom_g6_pending_stop_sensor_only_${slot.suffix}")] = value }
    }

    suspend fun consumeDexcomG6PendingStopSensorOnly(slot: SensorSlot): Boolean {
        val key = booleanPreferencesKey("dexcom_g6_pending_stop_sensor_only_${slot.suffix}")
        var wasSet = false
        context.dataStore.edit { prefs ->
            wasSet = prefs[key] ?: false
            prefs.remove(key)
        }
        return wasSet
    }

    suspend fun setDexcomG6BatteryInfo(slot: SensorSlot, voltageA: Int, voltageB: Int, temperatureC: Int, atMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[slotInt("dexcom_g6_voltage_a", slot)] = voltageA
            prefs[slotInt("dexcom_g6_voltage_b", slot)] = voltageB
            prefs[slotInt("dexcom_g6_temperature_c", slot)] = temperatureC
            prefs[slotLong("dexcom_g6_last_battery_query_at_ms", slot)] = atMs
        }
    }

    data class DexcomG6BatteryInfo(val voltageA: Int, val voltageB: Int, val temperatureC: Int, val queriedAtMs: Long)

    fun dexcomG6BatteryInfo(slot: SensorSlot): Flow<DexcomG6BatteryInfo?> = context.dataStore.data.map { prefs ->
        val a = prefs[slotInt("dexcom_g6_voltage_a", slot)]
        val b = prefs[slotInt("dexcom_g6_voltage_b", slot)]
        val t = prefs[slotInt("dexcom_g6_temperature_c", slot)]
        val at = prefs[slotLong("dexcom_g6_last_battery_query_at_ms", slot)]
        if (a != null && b != null && t != null && at != null) DexcomG6BatteryInfo(a, b, t, at) else null
    }

    suspend fun getDexcomG6LastBatteryQueryAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g6_last_battery_query_at_ms", slot)]

    // ============================================================
    // Dexcom G7/ONE+ — per slot
    // ============================================================

    /** 17/08/2026 (editor, RONDE 112) — de 4-cijferige koppelcode op de
     *  sensor-applicator, zie ui/DexcomG7SetupScreen.kt. Dient TWEE doelen:
     *  het J-PAKE-wachtwoord (sensor/dexcomg7/DexcomG7Crypto.kt) ÉN (anders
     *  dan G6's transmitter-ID) GEEN rol in de BLE-naam-filter, zie
     *  DexcomG7Driver.kt's kdoc bij buildPairingListFilter(). */
    suspend fun setDexcomG7PairingCode(slot: SensorSlot, code: String) {
        context.dataStore.edit { prefs -> prefs[slotString("dexcom_g7_pairing_code", slot)] = code }
    }

    fun dexcomG7PairingCode(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("dexcom_g7_pairing_code", slot)]
    }

    suspend fun getDexcomG7PairingCodeOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("dexcom_g7_pairing_code", slot)]

    suspend fun hasKnownDexcomG7PairingCodeOnce(slot: SensorSlot): Boolean =
        getDexcomG7PairingCodeOnce(slot) != null

    /** 27/08/2026 (editor, RONDE 129, op verzoek na live-tests waarbij een
     *  opgeslagen koppelcode niet meer zichtbaar of controleerbaar was —
     *  zie DexcomG7StatusScreen.kt's "Forget pairing code"-knop) — expliciete
     *  tegenhanger van [setDexcomG7PairingCode]: verwijdert de opgeslagen
     *  code weer, zodat [hasKnownDexcomG7PairingCodeOnce] weer `false`
     *  oplevert en de gebruiker bij de volgende koppelpoging gewoon opnieuw
     *  om de code gevraagd wordt (zie ROUTE_SENSOR_SELECTION's
     *  `onSensorChosen`-tak voor DEXCOM_G7 in FclGlucoLinkNavHost.kt). */
    suspend fun clearDexcomG7PairingCode(slot: SensorSlot) {
        context.dataStore.edit { prefs -> prefs.remove(slotString("dexcom_g7_pairing_code", slot)) }
    }

    suspend fun setDexcomG7LastConnectedAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g7_last_connected_at_ms", slot)] = value }
    }

    fun dexcomG7LastConnectedAtMs(slot: SensorSlot): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[slotLong("dexcom_g7_last_connected_at_ms", slot)]
    }

    suspend fun getDexcomG7LastConnectedAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g7_last_connected_at_ms", slot)]

    /** 28/08/2026 (editor, RONDE 150) — letterlijke mirror van
     *  [setDexcomG6BatteryInfo]/[dexcomG6BatteryInfo]/
     *  [getDexcomG6LastBatteryQueryAtMsOnce] hierboven, alleen voor G7 — zie
     *  DexcomG7Protocol.kt's kdoc bij `buildBatteryInfoRequest`/
     *  `parseBatteryInfo` voor de protocol-herkomst en DexcomG7Driver.kt
     *  voor de aanroep/cache-gating. */
    /**
     * 29/08/2026 (editor, RONDE 159, op verzoek — "Ik wil hier in principe
     * alle info getoond kunnen hebben die de sensor zelf terug geeft") —
     * [status]/[resistance]/[runtimeDays] waren al langer beschikbaar in
     * DexcomG7Protocol.BatteryInfoRx (zie [parseBatteryInfo]) maar werden
     * hier nooit opgeslagen — alleen voltageA/voltageB/temperatureC. -1
     * betekent "niet aanwezig in dit antwoord" (10-byte "rev2"-lay-out, zie
     * die kdoc), niet "genegeerd".
     */
    suspend fun setDexcomG7BatteryInfo(
        slot: SensorSlot, voltageA: Int, voltageB: Int, temperatureC: Int, atMs: Long,
        status: Int = -1, resistance: Int = -1, runtimeDays: Int = -1
    ) {
        context.dataStore.edit { prefs ->
            prefs[slotInt("dexcom_g7_voltage_a", slot)] = voltageA
            prefs[slotInt("dexcom_g7_voltage_b", slot)] = voltageB
            prefs[slotInt("dexcom_g7_temperature_c", slot)] = temperatureC
            prefs[slotLong("dexcom_g7_last_battery_query_at_ms", slot)] = atMs
            prefs[slotInt("dexcom_g7_battery_status", slot)] = status
            prefs[slotInt("dexcom_g7_battery_resistance", slot)] = resistance
            prefs[slotInt("dexcom_g7_battery_runtime_days", slot)] = runtimeDays
        }
    }

    data class DexcomG7BatteryInfo(
        val voltageA: Int, val voltageB: Int, val temperatureC: Int, val queriedAtMs: Long,
        val status: Int = -1, val resistance: Int = -1, val runtimeDays: Int = -1
    )

    fun dexcomG7BatteryInfo(slot: SensorSlot): Flow<DexcomG7BatteryInfo?> = context.dataStore.data.map { prefs ->
        val a = prefs[slotInt("dexcom_g7_voltage_a", slot)]
        val b = prefs[slotInt("dexcom_g7_voltage_b", slot)]
        val t = prefs[slotInt("dexcom_g7_temperature_c", slot)]
        val at = prefs[slotLong("dexcom_g7_last_battery_query_at_ms", slot)]
        val status = prefs[slotInt("dexcom_g7_battery_status", slot)] ?: -1
        val resistance = prefs[slotInt("dexcom_g7_battery_resistance", slot)] ?: -1
        val runtimeDays = prefs[slotInt("dexcom_g7_battery_runtime_days", slot)] ?: -1
        if (a != null && b != null && t != null && at != null) {
            DexcomG7BatteryInfo(a, b, t, at, status, resistance, runtimeDays)
        } else null
    }

    suspend fun getDexcomG7LastBatteryQueryAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g7_last_battery_query_at_ms", slot)]

    /** 28/08/2026 (editor, RONDE 151, CRITIEKE FIX — na live-test van v164:
     *  de gebruiker's eigen sensor bleek het firmwareverzoek af te wijzen
     *  (zie DexcomG7Driver.kt's kdoc bij [queryFirmwareIfStale]), waardoor
     *  [getDexcomG7LastFirmwareQueryAtMsOnce] — die tot deze ronde alleen
     *  gevuld werd bij een GESLAAGDE uitvraag — never gevuld raakte, en de
     *  mislukte poging dus bij ELKE reconnect herhaald werd i.p.v. eens per
     *  30 dagen) — een APARTE "laatst GEPROBEERD"-tijdstempel, onafhankelijk
     *  van of het antwoord ooit succesvol geparsed werd. Mirror van
     *  DexcomG6Driver.kt's `setDexcomG6LastVersion2QueryAtMs`-patroon (die
     *  WEL al vóór het schrijven/wachten gezet wordt, precies om deze reden
     *  — dit bestand se G7-tegenhanger miste dat onderscheid abusievelijk). */
    suspend fun setDexcomG7BatteryQueryAttemptAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g7_battery_query_attempt_at_ms", slot)] = value }
    }

    suspend fun getDexcomG7BatteryQueryAttemptAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g7_battery_query_attempt_at_ms", slot)]

    suspend fun setDexcomG7FirmwareQueryAttemptAtMs(slot: SensorSlot, value: Long) {
        context.dataStore.edit { prefs -> prefs[slotLong("dexcom_g7_firmware_query_attempt_at_ms", slot)] = value }
    }

    suspend fun getDexcomG7FirmwareQueryAttemptAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g7_firmware_query_attempt_at_ms", slot)]

    /** 28/08/2026 (editor, RONDE 152, op verzoek — "een g7 gaat 10 dagen
     *  mee, dat zou dus sowieso bij iedere nieuwe sensor start moeten
     *  worden uitgevraagd [...] bij eerste opstart vult hij direct de
     *  batterij met ook de datum van de vorige test") — de gebruiker wees
     *  er terecht op dat [BATTERY_QUERY_INTERVAL_MS]/
     *  [FIRMWARE_QUERY_INTERVAL_MS] (8u/30 dagen) alleen zinnig zijn
     *  BINNEN het leven van ÉÉN fysieke sensor — zonder deze reset zou een
     *  NIEUWE G7 (elke ~10 dagen) de batterij-/firmwaregegevens van de VORIGE
     *  sensor blijven tonen, en bij firmware zelfs tot 30 dagen lang niet
     *  opnieuw uitgevraagd worden (langer dan de nieuwe sensor zélf meegaat).
     *  Aangeroepen vanuit `FclGlucoLinkNavHost.kt`'s
     *  ROUTE_DEXCOM_G7_SETUP-`onConfirmed`, op dezelfde plek waar
     *  [clearDeviceAddress] en [setDexcomG7PairingCode] al gebeuren voor een
     *  nieuwe koppelpoging. */
    suspend fun clearDexcomG7BatteryAndFirmwareInfo(slot: SensorSlot) {
        context.dataStore.edit { prefs ->
            prefs.remove(slotInt("dexcom_g7_voltage_a", slot))
            prefs.remove(slotInt("dexcom_g7_voltage_b", slot))
            prefs.remove(slotInt("dexcom_g7_temperature_c", slot))
            prefs.remove(slotLong("dexcom_g7_last_battery_query_at_ms", slot))
            prefs.remove(slotLong("dexcom_g7_battery_query_attempt_at_ms", slot))
            prefs.remove(slotString("dexcom_g7_firmware_version", slot))
            prefs.remove(slotString("dexcom_g7_bt_firmware_version", slot))
            prefs.remove(slotInt("dexcom_g7_hardware_version", slot))
            prefs.remove(slotLong("dexcom_g7_last_firmware_query_at_ms", slot))
            prefs.remove(slotLong("dexcom_g7_firmware_query_attempt_at_ms", slot))
        }
    }

    /** 28/08/2026 (editor, RONDE 150) — firmware-versiestring (xDrip+'s
     *  "Firmware Version"-label, bv. "32.192.109.40"), zie
     *  DexcomG7Protocol.buildFirmwareVersionRequest()/parseFirmwareVersion()
     *  voor de protocol-herkomst. Eenmalig per transmitter genoeg (een
     *  firmwareversie verandert niet tussen verbindingen) — vandaar dezelfde
     *  "laatst-opgevraagd"-staleness-aanpak als batterij/versie2 hierboven,
     *  zie DexcomG7Driver.kt. */
    /**
     * 29/08/2026 (editor, RONDE 159, op verzoek — "Ik wil hier in principe
     * alle info getoond kunnen hebben die de sensor zelf terug geeft") —
     * zeven nieuwe optionele velden, mirror van
     * DexcomG7Protocol.FirmwareVersionRx's RONDE-159-uitbreiding: dekt zowel
     * de 0x21-antwoordvariant (otherFirmwareVersion/asic) als de 0x4A/0x4B-
     * variant (buildVersion/versionCode/inactiveDays/maxRuntimeDays/
     * maxInactiveDays/serial) — welke velden daadwerkelijk gevuld zijn hangt
     * af van welke variant deze specifieke sensor accepteert (zie
     * queryFirmwareIfStale()'s prioriteitsvolgorde), -1/"" betekent "niet in
     * dit antwoord aanwezig".
     */
    suspend fun setDexcomG7FirmwareInfo(
        slot: SensorSlot,
        firmwareVersion: String,
        bluetoothFirmwareVersion: String,
        hardwareVersion: Int,
        atMs: Long,
        otherFirmwareVersion: String = "",
        asic: Int = -1,
        buildVersion: Long = -1,
        versionCode: Long = -1,
        inactiveDays: Int = -1,
        maxRuntimeDays: Int = -1,
        maxInactiveDays: Int = -1,
        serial: Long = -1
    ) {
        context.dataStore.edit { prefs ->
            prefs[slotString("dexcom_g7_firmware_version", slot)] = firmwareVersion
            prefs[slotString("dexcom_g7_bt_firmware_version", slot)] = bluetoothFirmwareVersion
            prefs[slotInt("dexcom_g7_hardware_version", slot)] = hardwareVersion
            prefs[slotLong("dexcom_g7_last_firmware_query_at_ms", slot)] = atMs
            prefs[slotString("dexcom_g7_other_firmware_version", slot)] = otherFirmwareVersion
            prefs[slotInt("dexcom_g7_asic", slot)] = asic
            prefs[slotLong("dexcom_g7_build_version", slot)] = buildVersion
            prefs[slotLong("dexcom_g7_version_code", slot)] = versionCode
            prefs[slotInt("dexcom_g7_inactive_days", slot)] = inactiveDays
            prefs[slotInt("dexcom_g7_max_runtime_days", slot)] = maxRuntimeDays
            prefs[slotInt("dexcom_g7_max_inactive_days", slot)] = maxInactiveDays
            prefs[slotLong("dexcom_g7_serial", slot)] = serial
        }
    }

    data class DexcomG7FirmwareInfo(
        val firmwareVersion: String,
        val bluetoothFirmwareVersion: String,
        val hardwareVersion: Int,
        val queriedAtMs: Long,
        val otherFirmwareVersion: String = "",
        val asic: Int = -1,
        val buildVersion: Long = -1,
        val versionCode: Long = -1,
        val inactiveDays: Int = -1,
        val maxRuntimeDays: Int = -1,
        val maxInactiveDays: Int = -1,
        val serial: Long = -1
    )

    fun dexcomG7FirmwareInfo(slot: SensorSlot): Flow<DexcomG7FirmwareInfo?> = context.dataStore.data.map { prefs ->
        val fw = prefs[slotString("dexcom_g7_firmware_version", slot)]
        val bt = prefs[slotString("dexcom_g7_bt_firmware_version", slot)]
        val hw = prefs[slotInt("dexcom_g7_hardware_version", slot)]
        val at = prefs[slotLong("dexcom_g7_last_firmware_query_at_ms", slot)]
        if (fw != null && bt != null && hw != null && at != null) {
            DexcomG7FirmwareInfo(
                firmwareVersion = fw,
                bluetoothFirmwareVersion = bt,
                hardwareVersion = hw,
                queriedAtMs = at,
                otherFirmwareVersion = prefs[slotString("dexcom_g7_other_firmware_version", slot)] ?: "",
                asic = prefs[slotInt("dexcom_g7_asic", slot)] ?: -1,
                buildVersion = prefs[slotLong("dexcom_g7_build_version", slot)] ?: -1,
                versionCode = prefs[slotLong("dexcom_g7_version_code", slot)] ?: -1,
                inactiveDays = prefs[slotInt("dexcom_g7_inactive_days", slot)] ?: -1,
                maxRuntimeDays = prefs[slotInt("dexcom_g7_max_runtime_days", slot)] ?: -1,
                maxInactiveDays = prefs[slotInt("dexcom_g7_max_inactive_days", slot)] ?: -1,
                serial = prefs[slotLong("dexcom_g7_serial", slot)] ?: -1
            )
        } else null
    }

    suspend fun getDexcomG7LastFirmwareQueryAtMsOnce(slot: SensorSlot): Long? =
        context.dataStore.data.first()[slotLong("dexcom_g7_last_firmware_query_at_ms", slot)]

    /**
     * 29/08/2026 (editor, RONDE 158, op verzoek — "Deze sensor geeft een
     * error het zou goed zijn als die bij sensor status getoond wordt") —
     * DexcomG7Driver.kt's `handleGlucoseResult()` berekende al ELKE cyclus
     * een `DexcomG6CalibrationState` (hergebruikt van G6, zie die klasse se
     * kdoc) uit de sensor se eigen statusbyte — precies de "SensorFailed7"
     * die in het diagnose-logboek al zichtbaar was — maar schreef die tot nu
     * toe nergens weg, alleen naar het logbestand. Simpele opslag, mirror
     * van [setDexcomG7FirmwareInfo] hierboven: één string + tijdstip, ELKE
     * cyclus overschreven (geen staleness-cache nodig, dit is per-cyclus-
     * informatie, geen eigenschap die stabiel blijft zoals firmware).
     */
    suspend fun setDexcomG7SensorStatus(slot: SensorSlot, status: String, atMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[slotString("dexcom_g7_sensor_status", slot)] = status
            prefs[slotLong("dexcom_g7_sensor_status_at_ms", slot)] = atMs
        }
    }

    data class DexcomG7SensorStatus(val status: String, val atMs: Long)

    fun dexcomG7SensorStatus(slot: SensorSlot): Flow<DexcomG7SensorStatus?> = context.dataStore.data.map { prefs ->
        val status = prefs[slotString("dexcom_g7_sensor_status", slot)]
        val at = prefs[slotLong("dexcom_g7_sensor_status_at_ms", slot)]
        if (status != null && at != null) DexcomG7SensorStatus(status, at) else null
    }

    /**
     * 29/08/2026 (editor, RONDE 158, op verzoek — "ik dacht dat er wel een
     * Bg waarde uit het Bg slot in de sensor wordt doorgegeven het zou fijn
     * zijn die ook op het status overzicht te tonen [...] maar als het een
     * foutieve waarde is niet door te zetten naar het hoofdscherm en ook
     * niet naar AAPS") — BEWUST een aparte opslagplek, LOS van de normale
     * [GlucoseReadingStore]/AAPS-broadcast-keten: `handleGlucoseResult()`
     * schrijft dit voor ELKE ontvangen meting (geaccepteerd of genegeerd,
     * zie [accepted]), terwijl `_readings.emit(...)` — de daadwerkelijke
     * weg naar hoofdscherm/grafiek/AAPS — ONVERANDERD alleen gebeurt als
     * de meting al bruikbaar was bevonden. Dit veld is dus puur diagnostisch/
     * informatief voor dit statusscherm, en kan NOOIT een foutieve waarde
     * ergens anders laten doorsijpelen.
     */
    /**
     * 29/08/2026 (editor, RONDE 159, op verzoek — "Ik wil hier in principe
     * alle info getoond kunnen hebben die de sensor zelf terug geeft") —
     * vier nieuwe velden, allemaal al aanwezig in
     * DexcomG7Protocol.GlucoseRx maar tot deze ronde nergens opgeslagen:
     * [trendMgdlPerMin] (null = "ongeldig/onbekend", zie die klasse se
     * kdoc), [predictedGlucoseMgdl], [transmitterStatusText] (via
     * [DexcomG7Protocol.transmitterStatusText], dezelfde statusbyte-
     * conventie als batterij/firmware) en [sequence] (de transmitter se
     * eigen oplopende measurement-teller, puur diagnostisch).
     */
    suspend fun setDexcomG7LastRawGlucose(
        slot: SensorSlot, mgdl: Double, atMs: Long, accepted: Boolean,
        trendMgdlPerMin: Double? = null, predictedGlucoseMgdl: Int = -1,
        transmitterStatusText: String = "", sequence: Int = -1
    ) {
        context.dataStore.edit { prefs ->
            prefs[slotString("dexcom_g7_last_raw_glucose_mgdl", slot)] = mgdl.toString()
            prefs[slotLong("dexcom_g7_last_raw_glucose_at_ms", slot)] = atMs
            prefs[booleanPreferencesKey("dexcom_g7_last_raw_glucose_accepted_${slot.name}")] = accepted
            prefs[slotString("dexcom_g7_last_raw_glucose_trend", slot)] = trendMgdlPerMin?.toString() ?: ""
            prefs[slotInt("dexcom_g7_last_raw_glucose_predicted", slot)] = predictedGlucoseMgdl
            prefs[slotString("dexcom_g7_last_raw_glucose_status", slot)] = transmitterStatusText
            prefs[slotInt("dexcom_g7_last_raw_glucose_sequence", slot)] = sequence
        }
    }

    data class DexcomG7LastRawGlucose(
        val mgdl: Double, val atMs: Long, val accepted: Boolean,
        val trendMgdlPerMin: Double? = null, val predictedGlucoseMgdl: Int = -1,
        val transmitterStatusText: String = "", val sequence: Int = -1
    )

    fun dexcomG7LastRawGlucose(slot: SensorSlot): Flow<DexcomG7LastRawGlucose?> = context.dataStore.data.map { prefs ->
        val mgdl = prefs[slotString("dexcom_g7_last_raw_glucose_mgdl", slot)]?.toDoubleOrNull()
        val at = prefs[slotLong("dexcom_g7_last_raw_glucose_at_ms", slot)]
        val accepted = prefs[booleanPreferencesKey("dexcom_g7_last_raw_glucose_accepted_${slot.name}")]
        if (mgdl != null && at != null && accepted != null) {
            DexcomG7LastRawGlucose(
                mgdl = mgdl,
                atMs = at,
                accepted = accepted,
                trendMgdlPerMin = prefs[slotString("dexcom_g7_last_raw_glucose_trend", slot)]?.toDoubleOrNull(),
                predictedGlucoseMgdl = prefs[slotInt("dexcom_g7_last_raw_glucose_predicted", slot)] ?: -1,
                transmitterStatusText = prefs[slotString("dexcom_g7_last_raw_glucose_status", slot)] ?: "",
                sequence = prefs[slotInt("dexcom_g7_last_raw_glucose_sequence", slot)] ?: -1
            )
        } else null
    }

    // ============================================================
    // Kalibratie-/sessie-bookkeeping — per slot (was device-adres-gated,
    // maar met 2 gelijktijdige devices moet dit nu ook per slot los
    // bijgehouden kunnen worden)
    // ============================================================

    suspend fun getCalibrationClearedForDeviceAddressOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("calibration_cleared_for_device_address", slot)]

    suspend fun setCalibrationClearedForDeviceAddress(slot: SensorSlot, address: String) {
        context.dataStore.edit { prefs -> prefs[slotString("calibration_cleared_for_device_address", slot)] = address }
    }

    suspend fun getSensorSessionStartedForDeviceAddressOnce(slot: SensorSlot): String? =
        context.dataStore.data.first()[slotString("sensor_session_started_for_device_address", slot)]

    suspend fun setSensorSessionStartedForDeviceAddress(slot: SensorSlot, address: String) {
        context.dataStore.edit { prefs -> prefs[slotString("sensor_session_started_for_device_address", slot)] = address }
    }

    // ============================================================
    // Simulator — per slot (twee simulators kunnen straks onafhankelijk
    // van elkaar draaien, elk met hun eigen modus/lijst-bestand)
    // ============================================================

    suspend fun setActiveSimulatorMode(slot: SensorSlot, mode: PersistedSimulatorMode) {
        context.dataStore.edit { prefs ->
            val modeKey = slotString("simulator_active_mode", slot)
            val repeatKey = slotDouble("simulator_repeat_mgdl", slot)
            val intervalKey = slotLong("simulator_interval_ms", slot)
            // 29/08/2026 (editor, RONDE 163) — aparte sleutel van [repeatKey]:
            // dat veld hoort bij de losse "Repeat"-modus (zie
            // SimulatorSetupScreen.kt's "Manual value"-kaart) en zou anders
            // door elkaar lopen met de baseline van een lijst-scenario.
            val listBaselineKey = slotDouble("simulator_list_replay_baseline_mgdl", slot)
            when (mode) {
                is PersistedSimulatorMode.None -> {
                    prefs[modeKey] = "NONE"
                    prefs.remove(repeatKey)
                    prefs.remove(intervalKey)
                    prefs.remove(listBaselineKey)
                }
                is PersistedSimulatorMode.Repeat -> {
                    prefs[modeKey] = "REPEAT"
                    prefs[repeatKey] = mode.glucoseMgdl
                    prefs[intervalKey] = mode.intervalMs
                }
                is PersistedSimulatorMode.RandomWalk -> {
                    prefs[modeKey] = "RANDOM_WALK"
                    prefs[intervalKey] = mode.intervalMs
                }
                is PersistedSimulatorMode.ListReplay -> {
                    prefs[modeKey] = "LIST_REPLAY"
                    prefs[intervalKey] = mode.intervalMs
                    prefs[listBaselineKey] = mode.baselineMgdl
                }
            }
        }
    }

    suspend fun readActiveSimulatorMode(slot: SensorSlot): PersistedSimulatorMode {
        val prefs = context.dataStore.data.first()
        val intervalMs = prefs[slotLong("simulator_interval_ms", slot)] ?: (5 * 60_000L)
        return when (prefs[slotString("simulator_active_mode", slot)]) {
            "REPEAT" -> {
                val mgdl = prefs[slotDouble("simulator_repeat_mgdl", slot)]
                if (mgdl != null) PersistedSimulatorMode.Repeat(mgdl, intervalMs) else PersistedSimulatorMode.None
            }
            "RANDOM_WALK" -> PersistedSimulatorMode.RandomWalk(intervalMs)
            // 29/08/2026 (editor, RONDE 163) — ontbrekende baseline (bv. een
            // op-schijf-staat van vóór deze ronde) valt terug op
            // simulatorBaselineMgdl's eigen default, niet op "geen scenario":
            // een lijst-afspeelmodus zonder geldige baseline zou anders
            // stilletjes NIETS hervatten (zie de vergelijkbare `?:
            // PersistedSimulatorMode.None`-val bij "REPEAT" hierboven, hier
            // bewust NIET gekopieerd — een ontbrekende baseline is anders dan
            // een ontbrekende herhaal-waarde: bij Repeat is het veld de HELE
            // betekenis van de modus, bij ListReplay is het een aanvullende,
            // altijd-met-een-zinnig-default-in te vullen instelling).
            "LIST_REPLAY" -> {
                val baselineMgdl = prefs[slotDouble("simulator_list_replay_baseline_mgdl", slot)]
                    ?: DEFAULT_SIMULATOR_BASELINE_MGDL
                PersistedSimulatorMode.ListReplay(baselineMgdl, intervalMs)
            }
            else -> PersistedSimulatorMode.None
        }
    }

    fun externalListUri(slot: SensorSlot): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[slotString("simulator_external_list_uri", slot)]
    }

    suspend fun setExternalListUri(slot: SensorSlot, uri: String) {
        context.dataStore.edit { prefs -> prefs[slotString("simulator_external_list_uri", slot)] = uri }
    }

    // 29/08/2026 (editor, RONDE 163, op verzoek — "3 keer een vaste
    // instelbare Bg te laten beginnen [...] daarna weer naar de ingestelde
    // waarde te springen") — de instelbare baseline-waarde zelf, los van
    // welke modus 'm gebruikt (alleen de lijst-scenario-modus doet dat
    // vandaag, zie SimulatorSetupScreen.kt). 126 mg/dL (7,0 mmol/L) — zelfde
    // default als het bestaande "Manual value"-veld op dat scherm, geen
    // nieuw, ongemotiveerd derde default-getal in deze codebase.
    fun simulatorBaselineMgdl(slot: SensorSlot): Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[slotDouble("simulator_baseline_mgdl", slot)] ?: DEFAULT_SIMULATOR_BASELINE_MGDL
    }

    suspend fun setSimulatorBaselineMgdl(slot: SensorSlot, value: Double) {
        context.dataStore.edit { prefs -> prefs[slotDouble("simulator_baseline_mgdl", slot)] = value }
    }

    // ============================================================
    // Expert-modus — welke SensorType's zichtbaar zijn in de sensorkeuze
    // (ui/SensorSelectionScreen.kt), RONDE 164, op verzoek: "het kunnen
    // kiezen van de virtuele sensor (en ook de andere) onder een expert
    // modus [...] alle sensoren staan met een selectie vakje er achter die
    // default op aan staan maar die je ook uit kunt zetten zodat als je in
    // 1 van de slots kiest je alleen de ingestelde/geactiveerde sensoren
    // ziet." Bewust GLOBAAL (niet per-slot) — dit gaat over welke
    // sensortypes een gebruiker in het algemeen wil kunnen kiezen (bv. de
    // testsensoren verbergen voor niet-expert-gebruik), niet over een
    // per-slot-keuze. Default AAN voor elk type (`?: true`) zodat een
    // bestaande gebruiker die deze knop nooit aanraakt precies dezelfde,
    // ongefilterde lijst blijft zien als vóór deze ronde.
    // ============================================================

    fun isSensorTypeEnabledInPicker(sensorType: SensorType): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("expert_mode_sensor_enabled_${sensorType.name}")] ?: true
        }

    suspend fun setSensorTypeEnabledInPicker(sensorType: SensorType, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("expert_mode_sensor_enabled_${sensorType.name}")] = enabled
        }
    }

    // ============================================================
    // Update-check (About-scherm), RONDE 165 — zie
    // update/UpdateChecker.kt's klasse-kdoc voor het volledige ontwerp.
    // Bewaart alleen het LAATST BEKENDE resultaat van een periodieke check
    // (uitgevoerd vanuit BleConnectionService.kt), zodat AboutScreen.kt
    // meteen iets zinnigs kan tonen zonder zelf een nieuwe netwerkaanroep te
    // hoeven doen bij elke keer openen. versionCode 0 = "geen update bekend"
    // (een geldige versionCode is altijd >= 1, zie build.gradle.kts), fileId
    // leeg = hetzelfde signaal voor dat veld.
    // ============================================================

    val availableUpdateVersionCode: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[intPreferencesKey("available_update_version_code")] ?: 0
    }
    val availableUpdateFileId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("available_update_file_id")] ?: ""
    }
    val availableUpdateFileName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("available_update_file_name")] ?: ""
    }
    val lastUpdateCheckAtMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[longPreferencesKey("last_update_check_at_ms")] ?: 0L
    }

    suspend fun setAvailableUpdate(versionCode: Int, fileId: String, fileName: String) {
        context.dataStore.edit { prefs ->
            prefs[intPreferencesKey("available_update_version_code")] = versionCode
            prefs[stringPreferencesKey("available_update_file_id")] = fileId
            prefs[stringPreferencesKey("available_update_file_name")] = fileName
        }
    }

    suspend fun clearAvailableUpdate() {
        context.dataStore.edit { prefs ->
            prefs[intPreferencesKey("available_update_version_code")] = 0
            prefs[stringPreferencesKey("available_update_file_id")] = ""
            prefs[stringPreferencesKey("available_update_file_name")] = ""
        }
    }

    suspend fun setLastUpdateCheckAt(millis: Long) {
        context.dataStore.edit { prefs -> prefs[longPreferencesKey("last_update_check_at_ms")] = millis }
    }

    // ============================================================
    // Alarmen (nieuw, RONDE 106, Fase 2 stap 1 — instellingen-laag) — zie
    // alarm/AlarmType.kt's klasse-kdoc voor het volledige ontwerp. Globaal
    // (niet per-slot): het AAPS-actieve slot bewaakt de alarmen (eerder
    // bevestigd), maar de gevarengrenzen zelf zijn een voorkeur van de
    // gebruiker, geen eigenschap van een fysieke sensor — zelfde redenering
    // als displayUnit hierboven.
    // ============================================================

    /** Hoofdschakelaar — op verzoek: "1 overal knop om in 1 keer alle
     *  alarmen aan/uit te zetten". Staat deze uit, dan wordt (zodra de
     *  evaluatie-motor in een latere ronde gebouwd is) geen enkel alarm
     *  afgevuurd, ongeacht de losse per-type aan/uit-standen hieronder — die
     *  blijven gewoon opgeslagen (op verzoek: "de laatst ingestelde waarde
     *  wel persistent over een restart dan wel app update"), zodat
     *  opnieuw inschakelen precies de vorige configuratie teruggeeft, geen
     *  enkele per-type instelling gaat verloren door de hoofdschakelaar om
     *  te zetten. Default UIT — een bewuste, expliciete opt-in (geen
     *  installatie draait vandaag al met alarmen, dus er is geen "bestaand
     *  gedrag behouden"-argument zoals bij displayUnit's MMOL-default;
     *  hier is UIT gewoon de veiligste eerste stand). */
    val alarmsMasterEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ALARMS_MASTER_ENABLED] ?: false
    }

    suspend fun setAlarmsMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ALARMS_MASTER_ENABLED] = enabled }
    }

    suspend fun isAlarmsMasterEnabledOnce(): Boolean =
        context.dataStore.data.first()[Keys.ALARMS_MASTER_ENABLED] ?: false

    /** Per-type aan/uit — AlarmSettingsScreen.kt maakt de rij pas
     *  interactief als [alarmsMasterEnabled] ook aan staat (UI-gate), maar
     *  de waarde zelf leeft hier onafhankelijk van de hoofdschakelaar, dus
     *  blijft intact als de hoofdschakelaar heen-en-weer gezet wordt. */
    fun alarmEnabled(type: AlarmType): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[alarmBoolean("enabled", type)] ?: false
    }

    suspend fun setAlarmEnabled(type: AlarmType, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[alarmBoolean("enabled", type)] = enabled }
    }

    suspend fun isAlarmEnabledOnce(type: AlarmType): Boolean =
        context.dataStore.data.first()[alarmBoolean("enabled", type)] ?: false

    /** Drempelwaarde in mg/dL — relevant voor AlarmCategory.THRESHOLD_LOW/
     *  THRESHOLD_HIGH-types. Valt terug op [AlarmType.defaultThresholdMgdl]
     *  (0.0 voor een type dat er geen heeft, wat in de praktijk nooit
     *  gelezen wordt voor zo'n type — PREDICTIVE_LOW/PREDICTIVE_HIGH/
     *  STALE_DATA gebruiken hun eigen velden hieronder). */
    fun alarmThresholdMgdl(type: AlarmType): Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[alarmDouble("threshold_mgdl", type)] ?: (type.defaultThresholdMgdl ?: 0.0)
    }

    suspend fun setAlarmThresholdMgdl(type: AlarmType, valueMgdl: Double) {
        context.dataStore.edit { prefs -> prefs[alarmDouble("threshold_mgdl", type)] = valueMgdl }
    }

    suspend fun getAlarmThresholdMgdlOnce(type: AlarmType): Double =
        context.dataStore.data.first()[alarmDouble("threshold_mgdl", type)] ?: (type.defaultThresholdMgdl ?: 0.0)

    /** Voorlooptijd in minuten — voor AlarmType.PREDICTIVE_LOW/PREDICTIVE_HIGH
     *  (hoeveel eerder dan de daadwerkelijke LOW- resp. HIGH-drempel-
     *  overschrijding de eerste waarschuwing mag komen, zie AlarmType.kt's
     *  kdoc). */
    fun alarmLeadTimeMinutes(type: AlarmType): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[alarmInt("lead_time_minutes", type)] ?: (type.defaultLeadTimeMinutes ?: 15)
    }

    suspend fun setAlarmLeadTimeMinutes(type: AlarmType, minutes: Int) {
        context.dataStore.edit { prefs -> prefs[alarmInt("lead_time_minutes", type)] = minutes }
    }

    /** "Geen verse waarde meer"-drempel in minuten — alleen voor
     *  AlarmType.STALE_DATA. */
    fun alarmStaleMinutes(type: AlarmType): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[alarmInt("stale_minutes", type)] ?: (type.defaultStaleMinutes ?: 20)
    }

    suspend fun setAlarmStaleMinutes(type: AlarmType, minutes: Int) {
        context.dataStore.edit { prefs -> prefs[alarmInt("stale_minutes", type)] = minutes }
    }

    /** 13/08/2026 (editor, RONDE 106b, op verzoek: "ik wil echter per
     *  alarmsoort een eigen geluid kunnen kiezen uit de geluiden op de
     *  telefoon (zoals je ook een ringtone voor de telefoon kunt kiezen)")
     *  — vervangt het oude, vaste "Urgent"/"Gentle"-geluidsprofiel uit
     *  RONDE 106. De waarde is de URI (als string) die Android's eigen
     *  RingtoneManager.ACTION_RINGTONE_PICKER teruggeeft (zie
     *  AlarmSettingsScreen.kt's SoundPickerRow) — `null` = nog geen keuze
     *  gemaakt, dan geldt het systeem-standaardalarmgeluid
     *  (RingtoneManager.getDefaultUri(TYPE_ALARM), bepaald in de UI-laag,
     *  niet hier — deze klasse weet niets van Android's RingtoneManager). */
    fun alarmSoundUri(type: AlarmType): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[alarmString("sound_uri", type)]
    }

    suspend fun setAlarmSoundUri(type: AlarmType, uri: String?) {
        context.dataStore.edit { prefs ->
            val key = alarmString("sound_uri", type)
            if (uri == null) prefs.remove(key) else prefs[key] = uri
        }
    }

    suspend fun getAlarmSoundUriOnce(type: AlarmType): String? =
        context.dataStore.data.first()[alarmString("sound_uri", type)]

    /** Los van [alarmSoundUri]: WELK bestand er klinkt vs. HOE het klinkt
     *  (direct op volle sterkte, of langzaam opbouwend) — zie AlarmType.kt's
     *  klasse-kdoc voor waarom dit sinds RONDE 106b twee onafhankelijke
     *  instellingen zijn i.p.v. één gekoppeld profiel. */
    fun alarmEscalation(type: AlarmType): Flow<AlarmEscalation> = context.dataStore.data.map { prefs ->
        parseAlarmEscalation(prefs[alarmString("escalation", type)]) ?: type.defaultEscalation
    }

    suspend fun setAlarmEscalation(type: AlarmType, escalation: AlarmEscalation) {
        context.dataStore.edit { prefs -> prefs[alarmString("escalation", type)] = escalation.name }
    }

    suspend fun getAlarmEscalationOnce(type: AlarmType): AlarmEscalation =
        parseAlarmEscalation(context.dataStore.data.first()[alarmString("escalation", type)]) ?: type.defaultEscalation

    private fun parseAlarmEscalation(raw: String?): AlarmEscalation? =
        raw?.let { runCatching { AlarmEscalation.valueOf(it) }.getOrNull() }

    /** 13/08/2026 (editor, RONDE 107b, op verzoek: "ik wil per alarm kunnen
     *  kiezen tussen alarm of vibrate of both") — vervangt de oude losse
     *  aan/uit-vibratieschakelaar (Ronde 106/107) door één 3-standen-keuze
     *  per type, zie alarm/AlarmType.kt's [AlarmAlertMode]-kdoc. Default
     *  BOTH — meest opvallend, zelfde bedoeling als de oude AAN-default. */
    fun alarmAlertMode(type: AlarmType): Flow<AlarmAlertMode> = context.dataStore.data.map { prefs ->
        parseAlarmAlertMode(prefs[alarmString("alert_mode", type)]) ?: AlarmAlertMode.BOTH
    }

    suspend fun setAlarmAlertMode(type: AlarmType, mode: AlarmAlertMode) {
        context.dataStore.edit { prefs -> prefs[alarmString("alert_mode", type)] = mode.name }
    }

    suspend fun getAlarmAlertModeOnce(type: AlarmType): AlarmAlertMode =
        parseAlarmAlertMode(context.dataStore.data.first()[alarmString("alert_mode", type)]) ?: AlarmAlertMode.BOTH

    private fun parseAlarmAlertMode(raw: String?): AlarmAlertMode? =
        raw?.let { runCatching { AlarmAlertMode.valueOf(it) }.getOrNull() }

    /** 13/08/2026 (editor, RONDE 107 — de alarm-EVALUATIEMOTOR) — runtime-
     *  boekhouding, geen gebruikersvoorkeur: het tijdstip (epoch-ms) tot
     *  wanneer dit alarmtype gedempt is, gezet door zowel de "Stop"- als de
     *  "Snooze"-knop op AlarmActivity.kt (zie AlarmController.kt) — een vast,
     *  type-afhankelijk afkoelmoment bij Stop, een door de gebruiker gekozen
     *  duur bij Snooze. `null` = niet gedempt. Bewust WEL in DataStore (dus
     *  persistent over een service-herstart binnenin dezelfde episode) i.p.v.
     *  puur in-memory — een door Android gedode/herstarte achtergrondservice
     *  mag een net-afgehandeld alarm niet meteen weer laten afgaan. */
    fun alarmMutedUntilMs(type: AlarmType): Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[alarmLong("muted_until_ms", type)]
    }

    suspend fun setAlarmMutedUntilMs(type: AlarmType, untilMs: Long) {
        context.dataStore.edit { prefs -> prefs[alarmLong("muted_until_ms", type)] = untilMs }
    }

    suspend fun clearAlarmMutedUntilMs(type: AlarmType) {
        context.dataStore.edit { prefs -> prefs.remove(alarmLong("muted_until_ms", type)) }
    }

    // ============================================================
    // Eenmalige migratie: bestaande (single-slot) data -> Slot A
    // ============================================================

    /**
     * 10/08/2026 (editor, RONDE 79) — bij de eerste opstart NA deze update
     * kopieert dit elke waarde die nog onder een LEGACY_*-sleutel staat
     * (zie Keys hierboven) één keer naar de bijbehorende Slot-A-sleutel,
     * zodat een bestaande installatie (jij, met een lopende G6/CareSens-
     * koppeling) niets kwijtraakt — de app gedraagt zich na de update
     * alsof je sensor altijd al in Slot A zat. Oude sleutels blijven
     * ongebruikt in de DataStore staan (onschadelijk, nooit meer gelezen
     * na deze migratie) i.p.v. actief verwijderd — kleinste, veiligste
     * wijziging.
     *
     * `broadcastEnabled` (default true = "zendt") wordt vertaald naar
     * `aapsActiveSlot`: als er een sensor gekozen was EN broadcast niet
     * expliciet uitstond, wordt Slot A de actieve AAPS-bron; anders blijft
     * aapsActiveSlot leeg (niemand zendt), precies zoals de situatie vóór
     * de update.
     *
     * MOET precies één keer per installatie draaien, VOORDAT enige andere
     * AppSettings-slot-functie gebruikt wordt — aangeroepen vanuit
     * FclGlucoLinkApp.onCreate() (zie kdoc daar).
     */
    suspend fun migrateLegacySingleSlotDataOnce() {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.MIGRATION_DUAL_SLOT_DONE] == true) return

        context.dataStore.edit { p ->
            fun <T> copy(from: Preferences.Key<T>, to: Preferences.Key<T>) {
                val value = p[from] ?: return
                p[to] = value
            }

            copy(Keys.LEGACY_SELECTED_SENSOR, slotString("selected_sensor", SensorSlot.A))
            copy(Keys.LEGACY_DEVICE_ADDRESS, slotString("device_address", SensorSlot.A))
            copy(Keys.LEGACY_SENSOR_STARTED_AT_MS, slotLong("sensor_started_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_SENSOR_CODE, slotString("caresens_sensor_code", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_SERIAL, slotString("caresens_serial", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_PIN, slotString("caresens_pin", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_EXPIRY_YYMMDD, slotString("caresens_expiry_yymmdd", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_NEXT_SEQUENCE, slotInt("caresens_next_sequence", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_SENSOR_STARTED_AT_MS, slotLong("caresens_sensor_started_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_CARESENS_LAST_CONNECTED_AT_MS, slotLong("caresens_last_connected_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_TRANSMITTER_ID, slotString("dexcom_g6_transmitter_id", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_LAST_CONNECTED_AT_MS, slotLong("dexcom_g6_last_connected_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_LAST_CONFIRMED_SENSOR_CODE, slotString("dexcom_g6_last_confirmed_sensor_code", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_SESSION_START_CONFIRMED_AT_MS, slotLong("dexcom_g6_session_start_confirmed_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_LAST_CALIBRATION_STATE, slotInt("dexcom_g6_last_calibration_state", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_WARMUP_SECONDS, slotInt("dexcom_g6_warmup_seconds", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_TYPICAL_SENSOR_DAYS, slotInt("dexcom_g6_typical_sensor_days", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_VOLTAGE_A, slotInt("dexcom_g6_voltage_a", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_VOLTAGE_B, slotInt("dexcom_g6_voltage_b", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_TEMPERATURE_C, slotInt("dexcom_g6_temperature_c", SensorSlot.A))
            copy(Keys.LEGACY_DEXCOM_G6_LAST_BATTERY_QUERY_AT_MS, slotLong("dexcom_g6_last_battery_query_at_ms", SensorSlot.A))
            copy(Keys.LEGACY_CALIBRATION_CLEARED_FOR_DEVICE_ADDRESS, slotString("calibration_cleared_for_device_address", SensorSlot.A))
            copy(Keys.LEGACY_SENSOR_SESSION_STARTED_FOR_DEVICE_ADDRESS, slotString("sensor_session_started_for_device_address", SensorSlot.A))
            copy(Keys.LEGACY_SIMULATOR_MODE, slotString("simulator_active_mode", SensorSlot.A))
            copy(Keys.LEGACY_SIMULATOR_REPEAT_MGDL, slotDouble("simulator_repeat_mgdl", SensorSlot.A))
            copy(Keys.LEGACY_SIMULATOR_INTERVAL_MS, slotLong("simulator_interval_ms", SensorSlot.A))
            copy(Keys.LEGACY_EXTERNAL_LIST_URI, slotString("simulator_external_list_uri", SensorSlot.A))

            val hadSelectedSensor = p[Keys.LEGACY_SELECTED_SENSOR] != null
            val wasBroadcasting = p[Keys.LEGACY_BROADCAST_ENABLED] ?: true
            if (hadSelectedSensor && wasBroadcasting) {
                p[Keys.AAPS_ACTIVE_SLOT] = SensorSlot.A.name
            }

            p[Keys.MIGRATION_DUAL_SLOT_DONE] = true
        }
    }

    /**
     * 10/08/2026 (editor, RONDE 80) — SEPARATE eenmalige migratie, los van
     * [migrateLegacySingleSlotDataOnce] hierboven. Aanleiding: calibrationMode/
     * calibrationManualOffsetMmol werden pas NU per-slot (zie kdoc bovenaan dit
     * bestand); de hoofdmigratie had op dat moment al gedraaid op bestaande
     * installaties (incl. de gebruiker's toestel, bevestigd via live
     * screenshots), dus die was géén plek meer om dit aan toe te voegen — zijn
     * MIGRATION_DUAL_SLOT_DONE-vlag staat immers al op true en de functie
     * keert dan meteen terug, VOORDAT nieuwe copy()-regels ooit bereikt
     * zouden worden.
     *
     * Kopieert de oude globale calibratiewaarden (als die er waren) één keer
     * naar Slot A — Slot B begint voor kalibratie leeg/op de standaardwaarden,
     * precies zoals de gebruiker's live-test-situatie was (kalibratie was tot
     * nu toe altijd impliciet "voor de hele app", dus voor de sensor die de
     * gebruiker feitelijk aan het kalibreren was, meestal Slot A).
     *
     * MOET, net als de hoofdmigratie, aangeroepen worden vanuit
     * FclGlucoLinkApp.onCreate(), VOORDAT enig scherm calibrationMode(slot)/
     * calibrationManualOffsetMmol(slot) leest.
     */
    suspend fun migrateLegacyCalibrationToSlotAOnce() {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.MIGRATION_CALIBRATION_PER_SLOT_DONE] == true) return

        context.dataStore.edit { p ->
            fun <T> copy(from: Preferences.Key<T>, to: Preferences.Key<T>) {
                val value = p[from] ?: return
                p[to] = value
            }

            copy(Keys.LEGACY_CALIBRATION_MODE, slotString("calibration_mode", SensorSlot.A))
            copy(Keys.LEGACY_CALIBRATION_MANUAL_OFFSET_MMOL, slotDouble("calibration_manual_offset_mmol", SensorSlot.A))

            p[Keys.MIGRATION_CALIBRATION_PER_SLOT_DONE] = true
        }
    }
}
