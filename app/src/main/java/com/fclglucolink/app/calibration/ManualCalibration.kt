package com.fclglucolink.app.calibration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Grenzen van de handmatige correctie, bovenop de fit van de vingerprikken. */
const val MANUAL_SLOPE_MIN = 0.8
const val MANUAL_SLOPE_MAX = 1.2
const val MANUAL_OFFSET_MAX_MMOL = 1.5

/** Waarde (mg/dL) waaromheen de helling draait als er geen knikpunt is (zelfde als CENTER_MGDL). */
const val MANUAL_SLOPE_PIVOT_MGDL = CENTER_MGDL

/**
 * Een gewijzigde sensorstarttijd telt pas als nieuwe sensor als het verschil
 * groter is dan dit. Zo geeft een kleine schatting-bijstelling (bv. de G7-klok)
 * geen valse reset van de handmatige correctie.
 */
const val MANUAL_NEW_SENSOR_MIN_SHIFT_MS = 6L * 60L * 60L * 1000L

/**
 * RONDE 245 — handmatige correctie, ALTIJD bovenop de fit van de vingerprikken.
 *
 * - [offsetMmol]: verschuiving (mmol/L), geldt voor lineair en spline.
 * - [slope]: helling bij lineair, draait om [MANUAL_SLOPE_PIVOT_MGDL].
 * - [slopeLow] / [slopeHigh]: helling onder en boven het knikpunt bij spline.
 *   Ze draaien allebei om het knikpunt, dus de curve blijft daar aaneengesloten.
 */
data class ManualCalibration(
    val offsetMmol: Double = 0.0,
    val slope: Double = 1.0,
    val slopeLow: Double = 1.0,
    val slopeHigh: Double = 1.0
) {
    val slopesNeutral: Boolean get() = slope == 1.0 && slopeLow == 1.0 && slopeHigh == 1.0

    /** Past alleen de hellingen toe op [calibratedMgdl] (de offset zit al in die waarde). */
    fun applySlopes(calibratedMgdl: Double, mode: CalibrationMode, pivotMgdl: Double): Double {
        if (slopesNeutral) return calibratedMgdl
        val d = calibratedMgdl - pivotMgdl
        return if (mode == CalibrationMode.LINEAR) {
            calibratedMgdl + (clampSlope(slope) - 1.0) * d
        } else {
            calibratedMgdl + (clampSlope(slopeLow) - 1.0) * min(d, 0.0) + (clampSlope(slopeHigh) - 1.0) * max(d, 0.0)
        }
    }

    private fun clampSlope(v: Double) = v.coerceIn(MANUAL_SLOPE_MIN, MANUAL_SLOPE_MAX)
}

/** True als [storedSessionMs] bij dezelfde sensor hoort als [currentSessionMs]. `null` = oude opslag, telt als geldig. */
fun manualCalibrationStillValid(storedSessionMs: Long?, currentSessionMs: Long): Boolean =
    storedSessionMs == null || currentSessionMs <= 0L || abs(storedSessionMs - currentSessionMs) <= MANUAL_NEW_SENSOR_MIN_SHIFT_MS
