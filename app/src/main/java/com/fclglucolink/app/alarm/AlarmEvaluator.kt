package com.fclglucolink.app.alarm

import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.TrendCalculator

/**
 * ============================================================================
 * FCLGlucoLink — alarm-evaluatie (RONDE 107, Fase 2 stap 2: de motor)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107) — dit bestand is de PURE beslislogica:
 * gegeven de huidige instellingen (per type, al ingelezen — zie
 * [ResolvedAlarmConfig]) en de laatste meting, welke alarmtypes zouden op
 * dit moment moeten afgaan? Bewust gescheiden van AlarmMonitor.kt (dat
 * DataStore/Room leest en deze functie aanroept) zodat de beslisregels
 * zelf makkelijk te overzien en te testen zijn, los van suspend/Android-
 * afhankelijkheden.
 *
 * Drempelalarmen (Urgent Low/Low/High/Urgent High): simpele vergelijking
 * van de laatste meting tegen de ingestelde drempel.
 *
 * Predictief (Low/High): eenvoudig model, GEEN eigen lineaire-regressie-
 * berekening over meerdere punten, maar het al door de sensor-driver
 * berekende [GlucoseReading.trendMgdlPerMin] (dezelfde eenheid als xDrip's
 * "slope", zie SensorDriver.kt's kdoc) rechtstreeks doorgetrokken:
 * projectedMgdl = huidige waarde + trend × voorlooptijd.
 *
 * 13/08/2026 (editor, RONDE 108) — Predictive Low/High gebruiken sinds deze
 * ronde hun EIGEN `config.thresholdMgdl` (zie AlarmType.kt's klasse-kdoc)
 * i.p.v. een cross-lookup naar het LOW/HIGH-type se drempel — simpeler EN
 * vrijer: geen afhankelijkheid meer tussen configs onderling. Predictive
 * Low vuurt als (a) de trend daadwerkelijk dalend is, (b) de huidige
 * waarde nog BOVEN de eigen streefwaarde zit (zodra de waarde er al onder
 * zit is er niets meer te voorspellen — de eigen streefwaarde is al
 * bereikt), en (c) de doorgetrokken lijn die streefwaarde binnen de
 * voorlooptijd bereikt. Predictive High is het spiegelbeeld.
 *
 * Stale data: geen verse meting binnen de ingestelde minuten — inclusief
 * het geval "nog nooit een meting gehad" ([latestReading] == null), dat
 * telt hier ook als stale (een net-geconfigureerde, nooit-verbonden sensor
 * hoort net zo goed een waarschuwing te kunnen geven als een sensor die
 * stopte met zenden).
 *
 * 08/09/2026 (editor, RONDE 171, CRITIEKE FIX — na een live-melding over
 * een predictive High-alarm dat afging terwijl de Bg-waarde daar in de
 * praktijk niet bij paste) — root cause: [GlucoseReading.trendMgdlPerMin]
 * komt rechtstreeks van een RUWE, ongevalideerde transmitterbyte (bv.
 * DexcomG6Protocol.trendByteToMgdlPerMin() — ruwe_byte / 10) en werd hier
 * ONBEGRENSD gebruikt in de lineaire doortrekking. In het gemelde geval was
 * een trend van ~12,6 mg/dL/min nodig om de voorspelling te laten kloppen —
 * meer dan 4× de eigen "DoubleUp"-grens van deze app (zie
 * XDripBroadcaster.kt's trendName(), 3 mg/dL/min) en fysiologisch
 * onmogelijk. Een enkele beschadigde/rare ruwe trendbyte (bv. een
 * eigenaardigheid van een Anubis-kloon-transmitter, of gewoon BLE-ruis)
 * kan zo'n absurde waarde opleveren, en niets in de hele keten (geen
 * enkele driver, geen validatie op [GlucoseReading] zelf) begrensde 'm
 * ooit — bevestigd voor alle vier sensortypes (G6/G7/CareSens/simulator).
 *
 * Fix: [PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN] begrenst de trend die de
 * PREDICTIEVE alarmen gebruiken tot een ruime maar fysiologisch zinnige
 * marge (ruim boven de eigen "DoubleUp"-conventie, zodat een echt zeer
 * snelle stijging/daling nooit onterecht wordt afgekapt) — puur hier in de
 * alarm-evaluatie, NIET in [GlucoseReading] zelf of de drivers: de
 * getoonde trendpijl/xDrip-broadcast-slope blijven bewust ongemoeid (die
 * kdoc noemt dat veld toch al geen kritiek invoerveld, puur voor de
 * UI-trendpijl — de drempelalarmen (Urgent Low/Low/High/Urgent High)
 * gebruiken sowieso geen trend, dus zijn hier al nooit gevoelig voor
 * geweest).
 *
 * 08/09/2026 (editor, RONDE 172, VERVOLG-FIX — na een nieuwe live-melding
 * op v184: een predictive High-alarm ging af tijdens een dalende Bg) — de
 * Ronde 171-clamp (8 mg/dL/min) begrenst alleen de GROOTTE van de ruwe
 * trendbyte, niet of de RICHTING ervan klopt. De laatste twee echte
 * metingen daalden aantoonbaar (9,7 -> 9,0 mmol/L), terwijl de ruwe
 * trendbyte een sterke stijging beweerde — 8 mg/dL/min sustained is
 * precies genoeg om vanaf 9,7 mmol/L (174,6 mg/dL) binnen 15 minuten de
 * 15,0 mmol/L (270 mg/dL)-drempel te halen (174,6 + 8×15 = 294,6), dus de
 * clamp alleen voorkwam dit incident niet. Een verdere verlaging van de
 * clamp lost dit niet fundamenteel op — een kapotte/rare byte kan net zo
 * goed toevallig ONDER een lagere grens uitkomen, en een te lage grens zou
 * ook echte snelle stijgingen (bv. na snelwerkende koolhydraten) onterecht
 * gaan afkappen.
 *
 * Fix: naast de bestaande clamp wordt de ruwe trend nu ook gecorroboreerd
 * tegen wat de laatste twee ECHTE opgeslagen metingen daadwerkelijk laten
 * zien ([com.fclglucolink.app.sensor.TrendCalculator.measuredMgdlPerMin]) —
 * geen eigen regressie over veel punten (dat bleef bewust buiten scope, zie
 * Ronde 107 hierboven), maar simpelweg het gemeten verschil tussen de twee
 * meest recente punten, alleen gebruikt als sanity-check op de RICHTING. Als
 * die twee metingen recent genoeg zijn (2-15 minuten uit elkaar — te
 * dichtbij is te ruisgevoelig, te ver uit elkaar betekent een meetgat
 * waarover geen zinnige uitspraak te doen is) en ze spreken de richting van
 * de ruwe trend tegen (voor Predictive High: de meting daalt of blijft
 * gelijk terwijl de ruwe trend stijgend zegt, en spiegelbeeld voor
 * Predictive Low), dan vuurt het alarm niet — de ruwe trendbyte wordt dan
 * behandeld als (waarschijnlijk) een sensor-glitch. Ontbreekt een bruikbare
 * vorige meting (sensor net gestart, groot meetgat) dan valt dit terug op
 * het oude, clamp-only gedrag van Ronde 171 — geen regressie voor die
 * gevallen.
 *
 * 08/09/2026 (editor, RONDE 173) — de tweepunts-berekening hierboven is
 * verhuisd naar `sensor/TrendCalculator.kt` (gelijknamige functie): de
 * xDrip-broadcast (XDripBroadcaster.kt) gebruikt sindsdien exact dezelfde
 * berekening om AAPS/het horloge dezelfde richting te laten tonen als deze
 * app's eigen thuisscherm — zie dat bestand's kdoc voor de aanleiding.
 * Puur een verhuizing, geen gedragswijziging hier.
 */
data class ResolvedAlarmConfig(
    val type: AlarmType,
    val enabled: Boolean,
    val thresholdMgdl: Double,
    val leadTimeMinutes: Int,
    val staleMinutes: Int
)

object AlarmEvaluator {

    // 08/09/2026 (editor, RONDE 171) — zie klasse-kdoc. 8 mg/dL/min is ruim
    // meer dan het dubbele van deze app's eigen "DoubleUp"-grens
    // (3 mg/dL/min, zie XDripBroadcaster.kt's trendName()) — genoeg marge
    // voor een echt extreme, genuine snelle verandering, maar begrenst een
    // duidelijk-onmogelijke ruwe-byte-uitschieter (in dit incident zou
    // ~12,6 mg/dL/min nodig zijn geweest, ruim boven deze grens).
    private const val PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN = 8f

    /** Meest urgent eerst — AlarmMonitor.kt gebruikt dit om, als er
     *  toevallig meerdere tegelijk zouden vuren, er maar ÉÉN daadwerkelijk
     *  te laten klinken (geen twee alarmgeluiden/full-screen-schermen door
     *  elkaar). */
    private val PRIORITY_ORDER = listOf(
        AlarmType.URGENT_LOW,
        AlarmType.URGENT_HIGH,
        AlarmType.LOW,
        AlarmType.HIGH,
        AlarmType.PREDICTIVE_LOW,
        AlarmType.PREDICTIVE_HIGH,
        AlarmType.STALE_DATA
    )

    fun evaluate(
        configs: Map<AlarmType, ResolvedAlarmConfig>,
        latestReading: GlucoseReading?,
        nowMs: Long,
        // 08/09/2026 (editor, RONDE 172) — alleen gebruikt door de
        // predictieve alarmen, als corroboratie op de ruwe trendbyte — zie
        // klasse-kdoc. Default `null` zodat bestaande aanroepen (en tests)
        // buiten AlarmMonitor.kt niet breken; zonder deze meegegeven te
        // krijgen valt het gedrag simpelweg terug op de oude clamp-only
        // Ronde 171-logica.
        previousReading: GlucoseReading? = null
    ): List<AlarmType> {
        val firing = mutableListOf<AlarmType>()
        for (config in configs.values) {
            if (!config.enabled) continue
            val fires = when (config.type.category) {
                AlarmCategory.THRESHOLD_LOW ->
                    latestReading != null && latestReading.glucoseMgdl < config.thresholdMgdl
                AlarmCategory.THRESHOLD_HIGH ->
                    latestReading != null && latestReading.glucoseMgdl > config.thresholdMgdl
                AlarmCategory.PREDICTIVE_LOW ->
                    predictiveLowFires(latestReading, previousReading, config)
                AlarmCategory.PREDICTIVE_HIGH ->
                    predictiveHighFires(latestReading, previousReading, config)
                AlarmCategory.STALE_DATA ->
                    latestReading == null || (nowMs - latestReading.timestampMs) >= config.staleMinutes * 60_000L
            }
            if (fires) firing += config.type
        }
        return firing
    }

    /** Zie klasse-kdoc: geeft de belangrijkste van [firing] terug (of
     *  `null` als niets vuurt) volgens [PRIORITY_ORDER]. */
    fun highestPriority(firing: List<AlarmType>): AlarmType? =
        PRIORITY_ORDER.firstOrNull { it in firing }

    /** RONDE 108: [config.thresholdMgdl] is nu de eigen, onafhankelijke
     *  streefwaarde van dit Predictive Low-alarm zelf — zie klasse-kdoc. */
    private fun predictiveLowFires(
        latest: GlucoseReading?,
        previous: GlucoseReading?,
        config: ResolvedAlarmConfig
    ): Boolean {
        val reading = latest ?: return false
        // Al onder de eigen streefwaarde -> niets meer te voorspellen.
        if (reading.glucoseMgdl <= config.thresholdMgdl) return false
        val clampedTrend = reading.trendMgdlPerMin
            .coerceIn(-PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN, PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN)
        // Niet dalend -> geen zinvolle voorspelling richting de streefwaarde.
        if (clampedTrend >= 0f) return false
        // RONDE 172: de laatste twee ECHTE metingen spreken een dalende
        // ruwe trend tegen (blijven gelijk of stijgen juist) -> waarschijnlijk
        // een kapotte/rare trendbyte, niet vertrouwen. Zie klasse-kdoc.
        val measuredTrend = TrendCalculator.measuredMgdlPerMin(reading, previous)
        if (measuredTrend != null && measuredTrend >= 0f) return false
        val projectedMgdl = reading.glucoseMgdl + clampedTrend * config.leadTimeMinutes
        return projectedMgdl <= config.thresholdMgdl
    }

    /** RONDE 108: zie [predictiveLowFires]'s kdoc — spiegelbeeld. */
    private fun predictiveHighFires(
        latest: GlucoseReading?,
        previous: GlucoseReading?,
        config: ResolvedAlarmConfig
    ): Boolean {
        val reading = latest ?: return false
        if (reading.glucoseMgdl >= config.thresholdMgdl) return false
        val clampedTrend = reading.trendMgdlPerMin
            .coerceIn(-PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN, PLAUSIBLE_TREND_CLAMP_MGDL_PER_MIN)
        if (clampedTrend <= 0f) return false
        // RONDE 172: zie [predictiveLowFires] — spiegelbeeld.
        val measuredTrend = TrendCalculator.measuredMgdlPerMin(reading, previous)
        if (measuredTrend != null && measuredTrend <= 0f) return false
        val projectedMgdl = reading.glucoseMgdl + clampedTrend * config.leadTimeMinutes
        return projectedMgdl >= config.thresholdMgdl
    }
}
