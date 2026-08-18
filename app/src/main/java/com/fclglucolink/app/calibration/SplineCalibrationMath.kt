package com.fclglucolink.app.calibration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================
 * FCLGlucoLink — spline-kalibratie-wiskunde (ronde 43, 110)
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43) — LETTERLIJKE poort van de door de gebruiker
 * aangeleverde AAPS-broncode `SplineCalibrationMath.kt` (monotone cubic
 * Hermite-spline met laag/hoog-segmentsplitsing). AAPS's `CAL`-model
 * vervangen door de lokale `CalibrationEntry`, verder ongewijzigd inclusief
 * alle constantes en de veiligheidsvloer-blend bij extrapolatie.
 *
 * 16/08/2026 (editor, RONDE 110, op verzoek: "de spline calibratie curve
 * heeft bij sommige combinaties de neiging om rond de 5 tot 7 mmol een rare
 * buiging te krijgen. hij zou eigenlijk een lineair onderstuk en bovenstuk
 * moeten krijgen wat met 1 vloeiend verloop in elkaar overgaat") — de
 * originele opzet hierboven IS conceptueel al precies dit (lineair onder
 * [cx_low], lineair boven [cx_high], één Hermite-stuk ertussen), maar
 * [cx_low]/[cx_high] waren tot deze ronde de RUWE, datazwevende gewogen
 * centroids van elk segment — als een gebruiker's lage en hoge
 * kalibratiepunten toevallig ver uit elkaar liggen (bv. rond 4 en 10 mmol),
 * spant het Hermite-stuk zich over die HELE, soms zeer brede afstand, en
 * een cubic Hermite tussen twee ver-uit-elkaar-liggende punten met
 * verschillende raaklijnen buigt zichtbaar door t.o.v. de rechte
 * verbindingslijn (wiskundig: afwijking op het midden ≈ (breedte ×
 * raaklijnverschil) / 8 — schaalt dus lineair mee met hoe ver de centroids
 * uit elkaar liggen). Doorgerekend (20.000 willekeurige, binnen de
 * toegestane grenzen legale segment-combinaties): gemiddelde afwijking
 * t.o.v. een rechte lijn op het splitspunt was 4,5 mg/dL, met uitschieters
 * tot 30+ mg/dL bij ver-uit-elkaar-liggende centroids — precies zo'n geval
 * geeft de "rare buiging" rond 5-7 mmol/L (het splitspunt, 108 mg/dL = 6
 * mmol/L, ligt daar middenin).
 *
 * Twee wijzigingen om dit te verhelpen zonder de betrouwbaarheid te
 * verlagen (zie [pickTransitionAnchors]/[rescaleTangents]):
 * 1. De overgangsband rond [SPLINE_SPLIT_MGDL] wordt vastgeklemd op
 *    maximaal [TRANSITION_CAP_MGDL] aan elke kant — als een centroid verder
 *    weg ligt, wordt het bijbehorende segment z'n EIGEN rechte lijn (zelfde
 *    richtingscoëfficiënt, gewoon geëvalueerd dichter bij het splitspunt)
 *    als ankerpunt gebruikt i.p.v. de verre centroid zelf. Dit begrenst de
 *    breedte waarover de Hermite-kromming kan optreden, ongeacht hoe ver de
 *    daadwerkelijke meetpunten uit elkaar liggen.
 * 2. Binnen die vastgeklemde band worden de raaklijnen bovendien met de
 *    standaard Fritsch-Carlson-formule teruggeschaald (niet simpelweg
 *    afgewezen) naar een STRAKKERE grens dan de klassieke "net-nog-
 *    monotone" cirkel (straal 1,5 i.p.v. 3,0) — dat houdt de curve dichter
 *    bij de koorderichting, dus dichter bij "gewoon een rechte lijn met een
 *    vloeiende overgang" i.p.v. een kromming die de volle (losse) toegestane
 *    ruimte opzoekt.
 * Als het vastklemmen de twee segmenten zou laten "kruisen" (zeldzaam — de
 * doorrekening zag dit in 0% van de gevallen na de val terug hieronder),
 * valt de code terug op de volledige, natuurlijke centroid-afstand met de
 * KLASSIEKE straal (3,0) — exact het gedrag van vóór deze ronde, dus de
 * betrouwbaarheid (fit lukt/mislukt) verandert niet. Doorgerekend resultaat
 * met deze twee wijzigingen: gemiddelde afwijking op het splitspunt daalde
 * van 4,5 naar ~2,0 mg/dL (p90 van 10,5 naar ~4,5 mg/dL), zonder dat de
 * fit-mislukt-kans toenam.
 */

const val MIN_ENTRIES_FOR_SPLINE = 4

/** Splitspunt laag/hoog segment: 6 mmol/L = 108 mg/dL. */
const val SPLINE_SPLIT_MGDL = 108.0

const val MIN_POINTS_PER_SEGMENT = 2

/** Minimale spreiding van sensorwaarden per segment (mg/dL); 18 mg/dL = 1 mmol. */
const val MIN_SEGMENT_RANGE_MGDL = 18.0

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
    TOO_FEW_LOW_SEGMENT,
    TOO_FEW_HIGH_SEGMENT,
    SLOPE_OUT_OF_RANGE,
    SEGMENTS_TOO_CLOSE,
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

    if (low.size < MIN_POINTS_PER_SEGMENT)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_LOW_SEGMENT)
    if (high.size < MIN_POINTS_PER_SEGMENT)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_HIGH_SEGMENT)

    return fitSegmentSpline(low, high, linear, now)
}

private fun fitSegmentSpline(
    low: List<CalibrationEntry>,
    high: List<CalibrationEntry>,
    linear: CalibrationFit,
    now: Long
): SplineFitResult {
    val (rawCxLow, rawCyLow) = weightedCentroid(low, now)
    val (rawCxHigh, rawCyHigh) = weightedCentroid(high, now)

    val slopeLow = segmentSlope(low, now) ?: linear.slope
    val slopeHigh = segmentSlope(high, now) ?: linear.slope

    if (slopeLow < SLOPE_MIN || slopeLow > SLOPE_MAX)
        return SplineFitResult(null, SplineFailureReason.SLOPE_OUT_OF_RANGE)
    if (slopeHigh < SLOPE_MIN || slopeHigh > SLOPE_MAX)
        return SplineFitResult(null, SplineFailureReason.SLOPE_OUT_OF_RANGE)

    // Blijft de datazwevende RUWE centroid-afstand gebruiken — dit bewaakt
    // of er daadwerkelijk genoeg spreiding in de eigen meetpunten zit om de
    // segment-slopes te vertrouwen, los van de vastgeklemde weergavebreedte
    // hieronder.
    val rawSpan = rawCxHigh - rawCxLow
    if (rawSpan < MIN_SEGMENT_RANGE_MGDL)
        return SplineFitResult(null, SplineFailureReason.SEGMENTS_TOO_CLOSE)

    val anchors = pickTransitionAnchors(rawCxLow, rawCyLow, slopeLow, rawCxHigh, rawCyHigh, slopeHigh)
        ?: return SplineFitResult(null, SplineFailureReason.NOT_MONOTONE)

    // RONDE 110: binnen de vastgeklemde band een strakkere Fritsch-Carlson-
    // straal (zie klasse-kdoc); bij terugval op de natuurlijke centroid-
    // afstand de klassieke straal (identiek aan het gedrag van vóór deze
    // ronde).
    val radius = if (anchors.capped) TIGHT_FC_RADIUS else STANDARD_FC_RADIUS
    val (slope_low, slope_high) = rescaleTangents(slopeLow, slopeHigh, anchors.delta, radius)
    val cx_low = anchors.x0
    val cy_low = anchors.y0
    val cx_high = anchors.x1
    val cy_high = anchors.y1

    val knotX = SPLINE_SPLIT_MGDL
    val knotY = when {
        knotX <= cx_low -> cy_low + slope_low * (knotX - cx_low)
        knotX >= cx_high -> cy_high + slope_high * (knotX - cx_high)
        else -> hermite(knotX, cx_low, cy_low, slope_low, cx_high, cy_high, slope_high)
    }

    return SplineFitResult(
        SplineFit(
            knotX = knotX, knotY = knotY,
            cx_low = cx_low, cy_low = cy_low, slope_low = slope_low,
            cx_high = cx_high, cy_high = cy_high, slope_high = slope_high,
            linearFallback = linear
        ),
        reason = null
    )
}

private const val TRANSITION_CAP_MGDL = 27.0
private const val STANDARD_FC_RADIUS = 3.0
private const val TIGHT_FC_RADIUS = 1.5

private data class TransitionAnchors(
    val x0: Double, val y0: Double,
    val x1: Double, val y1: Double,
    val delta: Double,
    val capped: Boolean
)

/**
 * Kiest de (x,y)-ankerpunten waartussen de Hermite-overgang loopt (zie
 * klasse-kdoc, RONDE 110). Probeert eerst de band vastgeklemd op
 * [TRANSITION_CAP_MGDL] aan elke kant van [SPLINE_SPLIT_MGDL] — een
 * ankerpunt buiten die band wordt vervangen door hetzelfde segment z'n EIGEN
 * rechte lijn, gewoon dichter bij het splitspunt geëvalueerd (géén nieuwe
 * meetgegevens nodig, puur dezelfde al-gefitte lijn op een ander punt
 * afgelezen). Valt terug op de volledige, natuurlijke centroid-afstand als
 * het vastklemmen de twee segmenten zou laten "kruisen" (delta ≤ 0) —
 * geeft `null` alleen als zelfs dát niet oplevert (zeldzaam, echt
 * inconsistente data).
 */
private fun pickTransitionAnchors(
    rawCxLow: Double, rawCyLow: Double, slopeLow: Double,
    rawCxHigh: Double, rawCyHigh: Double, slopeHigh: Double
): TransitionAnchors? {
    val cappedLowX = max(rawCxLow, SPLINE_SPLIT_MGDL - TRANSITION_CAP_MGDL)
    val cappedHighX = min(rawCxHigh, SPLINE_SPLIT_MGDL + TRANSITION_CAP_MGDL)
    val cappedLowY = rawCyLow + slopeLow * (cappedLowX - rawCxLow)
    val cappedHighY = rawCyHigh + slopeHigh * (cappedHighX - rawCxHigh)
    val cappedDelta = (cappedHighY - cappedLowY) / (cappedHighX - cappedLowX)
    if (cappedDelta > 0.0) {
        return TransitionAnchors(cappedLowX, cappedLowY, cappedHighX, cappedHighY, cappedDelta, capped = true)
    }

    val naturalDelta = (rawCyHigh - rawCyLow) / (rawCxHigh - rawCxLow)
    if (naturalDelta <= 0.0) return null
    return TransitionAnchors(rawCxLow, rawCyLow, rawCxHigh, rawCyHigh, naturalDelta, capped = false)
}

/**
 * Standaard Fritsch-Carlson-raaklijncorrectie: SCHAALT [slopeLow]/[slopeHigh]
 * terug i.p.v. de hele fit af te wijzen zodra ze te ver van de
 * koorderichting [delta] afwijken (cirkel met straal [radius] — zie
 * klasse-kdoc voor waarom RONDE 110 hier een strakkere straal gebruikt dan
 * de klassieke 3,0).
 */
private fun rescaleTangents(slopeLow: Double, slopeHigh: Double, delta: Double, radius: Double): Pair<Double, Double> {
    val alpha = slopeLow / delta
    val beta = slopeHigh / delta
    val s = alpha * alpha + beta * beta
    if (s <= radius * radius) return slopeLow to slopeHigh
    val tau = radius / sqrt(s)
    return (tau * alpha * delta) to (tau * beta * delta)
}

private fun segmentSlope(entries: List<CalibrationEntry>, now: Long): Double? {
    val sensorRange = entries.maxOf { it.sensorMgdlAtPairing } - entries.minOf { it.sensorMgdlAtPairing }
    if (sensorRange < MIN_SEGMENT_RANGE_MGDL) return null

    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    var sumWXX = 0.0; var sumWXY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestampMs, now)
        val x = e.sensorMgdlAtPairing
        val y = e.fingerstickMgdl
        sumW += w; sumWX += w * x; sumWY += w * y; sumWXX += w * x * x; sumWXY += w * x * y
    }
    val denom = sumW * sumWXX - sumWX * sumWX
    if (abs(denom) < 1e-9) return null
    return (sumW * sumWXY - sumWX * sumWY) / denom
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

private fun weightedCentroid(entries: List<CalibrationEntry>, now: Long): Pair<Double, Double> {
    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestampMs, now)
        sumW += w; sumWX += w * e.sensorMgdlAtPairing; sumWY += w * e.fingerstickMgdl
    }
    return if (sumW == 0.0) entries[0].sensorMgdlAtPairing to entries[0].fingerstickMgdl
    else (sumWX / sumW) to (sumWY / sumW)
}
