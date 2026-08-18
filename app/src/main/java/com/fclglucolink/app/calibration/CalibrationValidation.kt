package com.fclglucolink.app.calibration

import kotlin.math.abs

/**
 * ============================================================================
 * FCLGlucoLink — invoer-validatie voor een nieuwe kalibratie-entry
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek, "Ja zelfde vangnet [als AAPS],
 * waarbij de grens bij aaps nu geloof ik 0,3mmol/5min is, dat mag zeker naar
 * 0,35 en misschien naar 0,4 maar dan met een waarschuwing") — mirror van de
 * twee AAPS-preconditie-checks uit de aangeleverde
 * `LinearCalibrationPlugin.kt`/`SplineCalibrationPlugin.kt`
 * (`checkPreconditionsAt`): (a) een sensormeting binnen [PAIR_LOOKBACK_MS]
 * om de nieuwe vingerprik mee te koppelen, en (b) de BG mag op dat moment
 * niet te snel veranderen. AAPS's eigen harde grens is
 * `DELTA_GATE_MGDL_PER_5MIN = 5.0` (≈0,2775 mmol/L per 5 min — dicht bij de
 * "geloof ik 0,3" van de gebruiker). Hier verruimd naar twee niveaus i.p.v.
 * één harde afkap:
 *   - tot en met [WARN_THRESHOLD_MMOL_PER_5MIN] (0,35): stilzwijgend
 *     geaccepteerd, zoals altijd.
 *   - tussen [WARN_THRESHOLD_MMOL_PER_5MIN] en [REJECT_THRESHOLD_MMOL_PER_5MIN]
 *     (0,35-0,40): WEL geaccepteerd, maar met een waarschuwing in de UI.
 *   - boven [REJECT_THRESHOLD_MMOL_PER_5MIN] (0,40): geweigerd, zoals AAPS's
 *     eigen harde grens dat ook doet.
 * Zelfde schaal-nuance als AAPS: de grens schaalt mee met de actieve fit's
 * slope (`activeFit.slope`) als die beschikbaar is — een sensor die zelf al
 * `1,2×` compressie laat zien, mag ook een grotere RUWE delta tonen voor
 * dezelfde werkelijke BG-verandering.
 */

const val PAIR_LOOKBACK_MS = 10L * 60L * 1000L
const val WARN_THRESHOLD_MMOL_PER_5MIN = 0.35
const val REJECT_THRESHOLD_MMOL_PER_5MIN = 0.40
private const val MGDL_PER_MMOL = 18.0182

sealed interface CalibrationEntryOutcome {
    /** [sensorMgdlAtPairing] = de ruwe sensorwaarde van de gekoppelde recente
     *  meting — dat is wat daadwerkelijk in de nieuwe entry opgeslagen wordt. */
    data class Accepted(val sensorMgdlAtPairing: Double, val warningMmolPer5Min: Double? = null) : CalibrationEntryOutcome
    data object RejectedNoRecentReading : CalibrationEntryOutcome
    data class RejectedDeltaTooHigh(val deltaMmolPer5Min: Double, val thresholdMmolPer5Min: Double) : CalibrationEntryOutcome
}

/**
 * @param recentRawReadings (timestampMs, rawSensorMgdl)-paren, meest recente
 *        metingen van de huidige sensor-sessie — volgorde maakt niet uit,
 *        wordt hier zelf gesorteerd. Gebruik `rawSensorMgdl` (niet de
 *        eventueel al gekalibreerde `glucoseMgdl`) — de fit-wiskunde werkt
 *        zelf altijd met ruwe sensorwaarden.
 * @param activeFit de op dit moment actieve lineaire fit (of de spline's
 *        `linearFallback`), of null als er nog geen fit is — puur voor de
 *        delta-grens-schaling, zie klasse-kdoc.
 */
fun evaluateNewCalibrationEntry(
    now: Long,
    recentRawReadings: List<Pair<Long, Double>>,
    activeFit: CalibrationFit?
): CalibrationEntryOutcome {
    val sorted = recentRawReadings.sortedByDescending { it.first }
    val pair = sorted.firstOrNull { it.first in (now - PAIR_LOOKBACK_MS)..now }
        ?: return CalibrationEntryOutcome.RejectedNoRecentReading

    val previous = sorted.firstOrNull { it.first < pair.first }
    if (previous != null && previous.first < pair.first) {
        val dtMs = (pair.first - previous.first).coerceAtLeast(1L)
        val deltaMgdl = pair.second - previous.second
        val deltaMgdlPer5Min = deltaMgdl * (5L * 60_000.0 / dtMs)
        val slopeScale = activeFit?.takeIf { it.isApplicable }?.slope ?: 1.0
        val deltaMmolPer5Min = abs(deltaMgdlPer5Min / MGDL_PER_MMOL)
        val rejectThreshold = REJECT_THRESHOLD_MMOL_PER_5MIN * slopeScale
        val warnThreshold = WARN_THRESHOLD_MMOL_PER_5MIN * slopeScale
        if (deltaMmolPer5Min > rejectThreshold) {
            return CalibrationEntryOutcome.RejectedDeltaTooHigh(deltaMmolPer5Min, rejectThreshold)
        }
        if (deltaMmolPer5Min > warnThreshold) {
            return CalibrationEntryOutcome.Accepted(pair.second, warningMmolPer5Min = deltaMmolPer5Min)
        }
    }
    return CalibrationEntryOutcome.Accepted(pair.second)
}
