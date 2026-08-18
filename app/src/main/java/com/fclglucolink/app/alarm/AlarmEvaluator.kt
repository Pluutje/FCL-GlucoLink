package com.fclglucolink.app.alarm

import com.fclglucolink.app.sensor.GlucoseReading

/**
 * ============================================================================
 * FCLGlucoLink — alarm-evaluatie (RONDE 107, Fase 2 stap 2: de motor)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107, vervolg op Ronde 106/106b's instellingen-
 * laag, op verzoek: "Ja dit is correct zo. graag verder met de
 * implementatie") — dit bestand is de PURE beslislogica: gegeven de huidige
 * instellingen (per type, al ingelezen — zie [ResolvedAlarmConfig]) en de
 * laatste meting, welke alarmtypes zouden op dit moment moeten afgaan?
 * Bewust gescheiden van AlarmMonitor.kt (dat DataStore/Room leest en deze
 * functie aanroept) zodat de beslisregels zelf makkelijk te overzien en te
 * testen zijn, los van suspend/Android-afhankelijkheden.
 *
 * Drempelalarmen (Urgent Low/Low/High/Urgent High): simpele vergelijking
 * van de laatste meting tegen de ingestelde drempel.
 *
 * Predictief (Low/High): "eenvoudig model", precies zoals gevraagd in de
 * meedenk-ronde — GEEN eigen lineaire-regressie-berekening over meerdere
 * punten, maar het al door de sensor-driver berekende [GlucoseReading.
 * trendMgdlPerMin] (dezelfde eenheid als xDrip's "slope", zie
 * SensorDriver.kt's kdoc) rechtstreeks doorgetrokken: projectedMgdl =
 * huidige waarde + trend × voorlooptijd.
 *
 * 13/08/2026 (editor, RONDE 108, op verzoek: "Kun je de predictive alarms
 * nog zo zetten dat daar een Bg waarde wordt ingevoerd ipv de koppeling
 * aan low en high dat geeft meer vrijheid") — Predictive Low/High gebruiken
 * sinds deze ronde hun EIGEN `config.thresholdMgdl` (zie AlarmType.kt's
 * klasse-kdoc) i.p.v. een cross-lookup naar het LOW/HIGH-type se drempel —
 * simpeler EN vrijer: geen afhankelijkheid meer tussen configs onderling.
 * Predictive Low vuurt als (a) de trend daadwerkelijk dalend is, (b) de
 * huidige waarde nog BOVEN de eigen streefwaarde zit (zodra de waarde er
 * al onder zit is er niets meer te "voorspellen" — de eigen streefwaarde is
 * al bereikt), en (c) de doorgetrokken lijn die streefwaarde binnen de
 * voorlooptijd bereikt. Predictive High is het spiegelbeeld.
 *
 * Stale data: geen verse meting binnen de ingestelde minuten — inclusief
 * het geval "nog nooit een meting gehad" ([latestReading] == null), dat
 * telt hier ook als stale (een net-geconfigureerde, nooit-verbonden sensor
 * hoort net zo goed een waarschuwing te kunnen geven als een sensor die
 * stopte met zenden).
 */
data class ResolvedAlarmConfig(
    val type: AlarmType,
    val enabled: Boolean,
    val thresholdMgdl: Double,
    val leadTimeMinutes: Int,
    val staleMinutes: Int
)

object AlarmEvaluator {

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
        nowMs: Long
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
                    predictiveLowFires(latestReading, config)
                AlarmCategory.PREDICTIVE_HIGH ->
                    predictiveHighFires(latestReading, config)
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
    private fun predictiveLowFires(latest: GlucoseReading?, config: ResolvedAlarmConfig): Boolean {
        val reading = latest ?: return false
        // Al onder de eigen streefwaarde -> niets meer te voorspellen.
        if (reading.glucoseMgdl <= config.thresholdMgdl) return false
        // Niet dalend -> geen zinvolle voorspelling richting de streefwaarde.
        if (reading.trendMgdlPerMin >= 0f) return false
        val projectedMgdl = reading.glucoseMgdl + reading.trendMgdlPerMin * config.leadTimeMinutes
        return projectedMgdl <= config.thresholdMgdl
    }

    /** RONDE 108: zie [predictiveLowFires]'s kdoc — spiegelbeeld. */
    private fun predictiveHighFires(latest: GlucoseReading?, config: ResolvedAlarmConfig): Boolean {
        val reading = latest ?: return false
        if (reading.glucoseMgdl >= config.thresholdMgdl) return false
        if (reading.trendMgdlPerMin <= 0f) return false
        val projectedMgdl = reading.glucoseMgdl + reading.trendMgdlPerMin * config.leadTimeMinutes
        return projectedMgdl >= config.thresholdMgdl
    }
}
