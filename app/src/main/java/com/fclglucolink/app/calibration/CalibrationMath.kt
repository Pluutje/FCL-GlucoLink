package com.fclglucolink.app.calibration

import kotlin.math.exp

/**
 * ============================================================================
 * FCLGlucoLink — kalibratie-wiskunde (ronde 43)
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek, "kalibratie optie toevoegen...
 * vergelijkbaar met die in aaps") — LETTERLIJKE poort van de door de
 * gebruiker aangeleverde AAPS-broncode `CalibrationMath.kt` (gewogen
 * kleinste-kwadraten lineaire fit, tijd-verval, veiligheidsklemmen). Enige
 * wijziging: AAPS's `CAL`-model (`app.aaps.core.data.model.CAL`) is
 * vervangen door de lokale `CalibrationEntry` — verder ongewijzigd, inclusief
 * de exacte constantes.
 */

const val TIME_DECAY_TAU_DAYS = 2L

// Slope-grenzen, zelfde als xDrip+'s LiParametersNonFixed (breedste
// mainstream-profiel) — laat compressiepatronen toe zonder absurde fits
// op één vingerprik te accepteren.
const val SLOPE_MIN = 0.55
const val SLOPE_MAX = 1.6

// Referentie-BG (mg/dL) om de correctiegrootte van de kalibratielijn op te klemmen.
const val CENTER_MGDL = 100.0
const val CORRECTION_AT_CENTER_MIN = -45.0
const val CORRECTION_AT_CENTER_MAX = 45.0

const val MIN_ENTRIES_FOR_FIT = 2

// Minimale spreiding van sensorwaarden (mg/dL) nodig om een slope-schatting
// te vertrouwen. Onder deze grens is de leverage te laag: ruis in de
// vingerprikwaarden domineert de slope, wat dan wild extrapoleert buiten de
// cluster. Valt terug op alleen-offset.
const val MIN_SENSOR_RANGE_FOR_SLOPE = 54.0

enum class FitMode {
    /** Gewogen kleinste-kwadraten — slope én offset beide vrij gefit. */
    Full,

    /** Vrije slope viel buiten [SLOPE_MIN, SLOPE_MAX]; slope geklemd op de
     *  grens, offset opnieuw gefit met die vaste slope. */
    SlopeClamped,

    /** Sensorbereik te smal voor een betrouwbare slope; slope vastgezet op 1.0. */
    OffsetOnly
}

data class CalibrationFit(
    val slope: Double,
    val offset: Double,
    val mode: FitMode = FitMode.Full
) {
    /** Correctie (mg/dL) toegepast bij sensor = [CENTER_MGDL]. */
    val correctionAtCenter: Double get() = (slope - 1) * CENTER_MGDL + offset

    val slopeInRange: Boolean get() = slope in SLOPE_MIN..SLOPE_MAX
    val correctionInRange: Boolean get() = correctionAtCenter in CORRECTION_AT_CENTER_MIN..CORRECTION_AT_CENTER_MAX
    val isApplicable: Boolean get() = slopeInRange && correctionInRange
}

/** Tijd-vervalgewicht (0..1] voor een entry met leeftijd `now - timestamp`. */
internal fun weightFor(timestamp: Long, now: Long): Double {
    if (timestamp >= now) return 1.0
    val tauMs = TIME_DECAY_TAU_DAYS * 24L * 60L * 60L * 1000L
    // Minimumgewicht 0.10: oude punten wegen minder maar vallen nooit
    // volledig weg.
    return exp(-(now - timestamp) / tauMs.toDouble()).coerceAtLeast(0.10)
}

/**
 * Gewogen kleinste-kwadraten fit van (sensorMgdlAtPairing -> fingerstickMgdl)
 * koppels. Geeft null bij minder dan [MIN_ENTRIES_FOR_FIT] entries of bij een
 * gedegenereerde noemer (alle sensorwaarden identiek).
 */
fun fitLinearCalibration(entries: List<CalibrationEntry>, now: Long): CalibrationFit? {
    if (entries.size < MIN_ENTRIES_FOR_FIT) return null

    val sensorRange = entries.maxOf { it.sensorMgdlAtPairing } - entries.minOf { it.sensorMgdlAtPairing }
    if (sensorRange < MIN_SENSOR_RANGE_FOR_SLOPE) {
        var sumW = 0.0
        var sumWDelta = 0.0
        for (e in entries) {
            val w = weightFor(e.timestampMs, now)
            sumW += w
            sumWDelta += w * (e.fingerstickMgdl - e.sensorMgdlAtPairing)
        }
        if (sumW == 0.0) return null
        return CalibrationFit(slope = 1.0, offset = sumWDelta / sumW, mode = FitMode.OffsetOnly)
    }

    var sumW = 0.0
    var sumWX = 0.0
    var sumWY = 0.0
    var sumWXX = 0.0
    var sumWXY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestampMs, now)
        val x = e.sensorMgdlAtPairing
        val y = e.fingerstickMgdl
        sumW += w
        sumWX += w * x
        sumWY += w * y
        sumWXX += w * x * x
        sumWXY += w * x * y
    }
    val denom = sumW * sumWXX - sumWX * sumWX
    if (denom == 0.0) return null
    val rawSlope = (sumW * sumWXY - sumWX * sumWY) / denom
    val rawOffset = (sumWXX * sumWY - sumWX * sumWXY) / denom

    val clampedSlope = rawSlope.coerceIn(SLOPE_MIN, SLOPE_MAX)
    return if (clampedSlope == rawSlope) {
        CalibrationFit(rawSlope, rawOffset, mode = FitMode.Full)
    } else {
        val offsetForClampedSlope = (sumWY - clampedSlope * sumWX) / sumW
        CalibrationFit(clampedSlope, offsetForClampedSlope, mode = FitMode.SlopeClamped)
    }
}
