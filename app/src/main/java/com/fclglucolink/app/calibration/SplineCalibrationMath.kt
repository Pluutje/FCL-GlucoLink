package com.fclglucolink.app.calibration

import kotlin.math.sqrt

/**
 * ============================================================================
 * FCLGlucoLink — spline-kalibratie-wiskunde (ronde 43, 110, 241)
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43) — poort van AAPS's `SplineCalibrationMath.kt`
 * (cubic Hermite-overgang tussen een laag en een hoog segment, met
 * veiligheidsvloer-blend bij extrapolatie boven het hoge segment).
 *
 * 16/08/2026 (editor, RONDE 110) — de overgangsband rond [SPLINE_SPLIT_MGDL]
 * werd vastgeklemd en de Fritsch-Carlson-straal verkleind, omdat de Hermite-
 * overgang bij ver uit elkaar liggende centroids zichtbaar doorboog.
 *
 * 20/10/2026 (editor, RONDE 241, na de melding dat de curve rond het buigpunt
 * soms een rare knik kreeg, en dat het aan- of uitzetten van één oud punt de
 * twee lineaire delen liet verspringen) — de opzet is omgedraaid:
 *
 * 1. Eerst wordt de gewone lineaire fit over ALLE punten berekend (helling a,
 *    offset b, zie [fitLinearCalibration]). Dat is het anker.
 * 2. Het onderstuk (punten tot [SPLINE_SPLIT_MGDL]) en het bovenstuk (daarboven)
 *    zijn die lijn met een KLEINE eigen afwijking:
 *      - offset: hoogstens [MAX_SEGMENT_OFFSET_MGDL] (0,5 mmol/L) van de lijn;
 *      - helling: hoogstens [MAX_SEGMENT_SLOPE_DEVIATION] van a.
 *    De afwijking wordt uit de resten van de punten van dat stuk geschat en bij
 *    weinig of dicht bij elkaar liggende punten richting nul getrokken (zie
 *    [fitSegmentLine]). Een stuk zonder punten volgt gewoon de lineaire lijn.
 *    Daardoor kan een segment met 2 punten de curve niet meer wild laten
 *    kantelen (voorheen kon de helling van elk stuk los tussen 0,55 en 1,6
 *    liggen, uit twee punten met minstens 1 mmol/L spreiding).
 * 3. Tussen de twee stukken loopt, over [TRANSITION_HALF_WIDTH_MGDL] aan elke
 *    kant van het splitspunt, één vloeiende Hermite-overgang. Omdat beide
 *    stukken dicht bij dezelfde lijn blijven, is die overgang klein.
 *
 * Gevolg: de spline faalt niet meer op "te weinig punten in het lage/hoge
 * bereik" of "helling buiten bereik". Alleen bij minder dan
 * [MIN_ENTRIES_FOR_SPLINE] punten, of als de overgang niet stijgend zou zijn,
 * valt de kalibratie terug op de gewone lijn.
 */

const val MIN_ENTRIES_FOR_SPLINE = 4

/** Splitspunt laag/hoog segment: 6 mmol/L = 108 mg/dL. */
const val SPLINE_SPLIT_MGDL = 108.0

/** De overgangsband loopt van splitspunt − dit tot splitspunt + dit (1,5 mmol/L). */
private const val TRANSITION_HALF_WIDTH_MGDL = 27.0

/** Maximale afwijking van de lineaire lijn per segment (0,5 mmol/L). */
private const val MAX_SEGMENT_OFFSET_MGDL = 9.0

/** Maximale afwijking van de lineaire helling per segment. */
private const val MAX_SEGMENT_SLOPE_DEVIATION = 0.15

/** Gewichtsmaat waarmee de offset-afwijking naar nul getrokken wordt: weinig gewicht = klein effect. */
private const val OFFSET_SHRINK_WEIGHT = 2.0

/**
 * De helling-afwijking wordt teruggetrokken alsof er [SLOPE_SHRINK_WEIGHT] extra
 * punten (gewicht) op [SLOPE_SHRINK_SPREAD_MGDL] van het midden lagen die géén
 * afwijking laten zien. Weinig spreiding of weinig punten = bijna geen afwijking.
 */
private const val SLOPE_SHRINK_WEIGHT = 2.0
private const val SLOPE_SHRINK_SPREAD_MGDL = 36.0

private const val TIGHT_FC_RADIUS = 1.5

data class SplineFit(
    val knotX: Double,
    val knotY: Double,

    val cx_low: Double, val cy_low: Double, val slope_low: Double,
    val cx_high: Double, val cy_high: Double, val slope_high: Double,

    val linearFallback: CalibrationFit
) {
    fun apply(sensorMgdl: Double, manualOffsetMgdl: Double = 0.0): Double {
        val fitted = when {
            sensorMgdl <= cx_low -> cy_low + slope_low * (sensorMgdl - cx_low)
            sensorMgdl >= cx_high -> extrapolateAboveHigh(sensorMgdl)
            else -> hermite(sensorMgdl, cx_low, cy_low, slope_low, cx_high, cy_high, slope_high)
        }
        return fitted + manualOffsetMgdl
    }

    /** Extrapolatie boven cx_high met veiligheidsvloer slope 1.0, geleidelijk
     *  ingefaseerd over BLEND_WIDTH_MGDL zodat waarde én afgeleide naadloos
     *  aansluiten (geen knik) — zie de originele AAPS-kdoc voor de volledige
     *  bugfix-achtergrond (21/06/2026). */
    private fun extrapolateAboveHigh(sensorMgdl: Double): Double {
        val dx = sensorMgdl - cx_high
        if (slope_high >= 1.0) {
            return cy_high + slope_high * dx
        }
        val blend = BLEND_WIDTH_MGDL
        return if (dx <= blend) {
            cy_high + slope_high * dx + 0.5 * (1.0 - slope_high) / blend * dx * dx
        } else {
            val blendEndY = cy_high + slope_high * blend + 0.5 * (1.0 - slope_high) * blend
            blendEndY + 1.0 * (dx - blend)
        }
    }

    val correctionAtKnot: Double get() = knotY - knotX
}

private const val BLEND_WIDTH_MGDL = 36.0

enum class SplineFailureReason {
    TOO_FEW_ENTRIES,
    NOT_MONOTONE
}

data class SplineFitResult(
    val fit: SplineFit?,
    val reason: SplineFailureReason?
)

fun fitSplineCalibration(entries: List<CalibrationEntry>, now: Long): SplineFitResult {
    if (entries.size < MIN_ENTRIES_FOR_SPLINE)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_ENTRIES)

    val linear = fitLinearCalibration(entries, now)
        ?: return SplineFitResult(null, SplineFailureReason.TOO_FEW_ENTRIES)

    val low = entries.filter { it.sensorMgdlAtPairing <= SPLINE_SPLIT_MGDL }
    val high = entries.filter { it.sensorMgdlAtPairing > SPLINE_SPLIT_MGDL }
    val lowLine = fitSegmentLine(low, linear, now)
    val highLine = fitSegmentLine(high, linear, now)

    val cxLow = SPLINE_SPLIT_MGDL - TRANSITION_HALF_WIDTH_MGDL
    val cxHigh = SPLINE_SPLIT_MGDL + TRANSITION_HALF_WIDTH_MGDL
    val cyLow = lowLine.valueAt(cxLow, linear)
    val cyHigh = highLine.valueAt(cxHigh, linear)

    val delta = (cyHigh - cyLow) / (cxHigh - cxLow)
    if (delta <= 0.0) return SplineFitResult(null, SplineFailureReason.NOT_MONOTONE)

    val (slopeLow, slopeHigh) = rescaleTangents(
        linear.slope + lowLine.slopeDeviation,
        linear.slope + highLine.slopeDeviation,
        delta,
        TIGHT_FC_RADIUS
    )

    val knotX = SPLINE_SPLIT_MGDL
    val knotY = hermite(knotX, cxLow, cyLow, slopeLow, cxHigh, cyHigh, slopeHigh)

    return SplineFitResult(
        SplineFit(
            knotX = knotX, knotY = knotY,
            cx_low = cxLow, cy_low = cyLow, slope_low = slopeLow,
            cx_high = cxHigh, cy_high = cyHigh, slope_high = slopeHigh,
            linearFallback = linear
        ),
        reason = null
    )
}

/** De lineaire lijn plus de kleine afwijking van één segment (zie klasse-kdoc, punt 2). */
private data class SegmentLine(
    val offset: Double,
    val slopeDeviation: Double,
    val centerX: Double
) {
    fun valueAt(x: Double, linear: CalibrationFit): Double =
        linear.slope * x + linear.offset + offset + slopeDeviation * (x - centerX)
}

private fun fitSegmentLine(segment: List<CalibrationEntry>, linear: CalibrationFit, now: Long): SegmentLine {
    if (segment.isEmpty()) return SegmentLine(0.0, 0.0, SPLINE_SPLIT_MGDL)

    var sumW = 0.0
    var sumWX = 0.0
    var sumWR = 0.0
    for (e in segment) {
        val w = weightFor(e.timestampMs, now)
        val r = e.fingerstickMgdl - (linear.slope * e.sensorMgdlAtPairing + linear.offset)
        sumW += w
        sumWX += w * e.sensorMgdlAtPairing
        sumWR += w * r
    }
    if (sumW == 0.0) return SegmentLine(0.0, 0.0, SPLINE_SPLIT_MGDL)
    val centerX = sumWX / sumW
    val meanResidual = sumWR / sumW

    val offset = (meanResidual * sumW / (sumW + OFFSET_SHRINK_WEIGHT))
        .coerceIn(-MAX_SEGMENT_OFFSET_MGDL, MAX_SEGMENT_OFFSET_MGDL)

    var sumXX = 0.0
    var sumXR = 0.0
    for (e in segment) {
        val w = weightFor(e.timestampMs, now)
        val dx = e.sensorMgdlAtPairing - centerX
        val r = e.fingerstickMgdl - (linear.slope * e.sensorMgdlAtPairing + linear.offset)
        sumXX += w * dx * dx
        sumXR += w * dx * (r - meanResidual)
    }
    val shrink = SLOPE_SHRINK_WEIGHT * SLOPE_SHRINK_SPREAD_MGDL * SLOPE_SHRINK_SPREAD_MGDL
    val slopeDeviation = (sumXR / (sumXX + shrink))
        .coerceIn(-MAX_SEGMENT_SLOPE_DEVIATION, MAX_SEGMENT_SLOPE_DEVIATION)

    return SegmentLine(offset, slopeDeviation, centerX)
}

/**
 * Standaard Fritsch-Carlson-raaklijncorrectie: SCHAALT [slopeLow]/[slopeHigh]
 * terug i.p.v. de hele fit af te wijzen zodra ze te ver van de
 * koorderichting [delta] afwijken (cirkel met straal [radius]).
 */
private fun rescaleTangents(slopeLow: Double, slopeHigh: Double, delta: Double, radius: Double): Pair<Double, Double> {
    val alpha = slopeLow / delta
    val beta = slopeHigh / delta
    val s = alpha * alpha + beta * beta
    if (s <= radius * radius) return slopeLow to slopeHigh
    val tau = radius / sqrt(s)
    return (tau * alpha * delta) to (tau * beta * delta)
}

private fun hermite(
    x: Double,
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Double {
    val h = x1 - x0
    if (h == 0.0) return y0
    val t = (x - x0) / h
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2 * t3 - 3 * t2 + 1
    val h10 = t3 - 2 * t2 + t
    val h01 = -2 * t3 + 3 * t2
    val h11 = t3 - t2
    return h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
}
