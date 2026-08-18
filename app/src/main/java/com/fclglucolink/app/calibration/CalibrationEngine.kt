package com.fclglucolink.app.calibration

/**
 * ============================================================================
 * FCLGlucoLink — kalibratie-engine: één berekening, geen aparte plugins
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek: "Het moeten dus geen 2 plugin
 * achtige dingen worden maar gewoon 1 berekening die bij lineair gewoon
 * lineair gedwongen wordt") — in tegenstelling tot AAPS's eigen opzet (twee
 * losse plugins, `LinearCalibrationPlugin` en `SplineCalibrationPlugin`, elk
 * met hun eigen aan/uit-schakeling) is dit hier ÉÉN functie met een
 * modus-vlag:
 *   - [CalibrationMode.LINEAR]: altijd [fitLinearCalibration], nooit een
 *     spline-poging — "geforceerd lineair" zoals gevraagd.
 *   - [CalibrationMode.SPLINE]: eerst [fitSplineCalibration] proberen, bij
 *     mislukking terugvallen op de lineaire fit — identiek aan AAPS's eigen
 *     `SplineCalibrationPlugin.calibrate()`-gedrag.
 *
 * Extra t.o.v. AAPS: als er nog HELEMAAL GEEN kalibratie-entry is (dus geen
 * fit mogelijk, `Full`/`SlopeClamped`/`OffsetOnly` allemaal onbereikbaar),
 * werkt de handmatige offset alsnog — een pure lineaire curve `y = x +
 * offset` — expliciet op verzoek: "De offset moet ook werken zonder dat er
 * een kalibratie waarde is ingevoerd". AAPS zelf doet dit niet (daar is
 * zonder fit gewoon identity, ook als er een manual offset staat), maar hier
 * is dat bewust wél zo.
 */
enum class CalibrationMode { LINEAR, SPLINE }

data class CalibrationResult(
    val calibratedMgdl: Double,
    /** True als er daadwerkelijk een fit (lineair of spline) is toegepast —
     *  false als alleen de handmatige offset (of niets) is toegepast. Puur
     *  informatief voor de UI-statustekst. */
    val fitApplied: Boolean,
    val splineActive: Boolean,
    val linearFit: CalibrationFit?,
    val splineFit: SplineFit?,
    val splineFailureReason: SplineFailureReason?
)

/**
 * Berekent de gekalibreerde waarde voor één ruwe sensormeting (mg/dL).
 * `manualOffsetMgdl` wordt ALTIJD als laatste stap opgeteld, ook als er geen
 * fit beschikbaar is (zie klasse-kdoc hierboven).
 */
fun computeCalibration(
    sensorMgdl: Double,
    entries: List<CalibrationEntry>,
    mode: CalibrationMode,
    manualOffsetMgdl: Double,
    now: Long
): CalibrationResult {
    if (entries.isEmpty()) {
        return CalibrationResult(
            calibratedMgdl = sensorMgdl + manualOffsetMgdl,
            fitApplied = false,
            splineActive = false,
            linearFit = null,
            splineFit = null,
            splineFailureReason = null
        )
    }

    if (mode == CalibrationMode.LINEAR) {
        val linear = fitLinearCalibration(entries, now)
        return if (linear != null && linear.isApplicable) {
            CalibrationResult(
                calibratedMgdl = linear.slope * sensorMgdl + linear.offset + manualOffsetMgdl,
                fitApplied = true,
                splineActive = false,
                linearFit = linear,
                splineFit = null,
                splineFailureReason = null
            )
        } else {
            // Fit niet (meer) betrouwbaar toepasbaar (zie CalibrationFit.
            // isApplicable) — val terug op alleen de offset, nooit een
            // ongebreidelde correctie doorzetten.
            CalibrationResult(
                calibratedMgdl = sensorMgdl + manualOffsetMgdl,
                fitApplied = false,
                splineActive = false,
                linearFit = linear,
                splineFit = null,
                splineFailureReason = null
            )
        }
    }

    // CalibrationMode.SPLINE — spline eerst, lineaire terugval, precies zoals
    // AAPS's SplineCalibrationPlugin.calibrate().
    val splineResult = fitSplineCalibration(entries, now)
    val spline = splineResult.fit
    if (spline != null) {
        return CalibrationResult(
            calibratedMgdl = spline.apply(sensorMgdl, manualOffsetMgdl),
            fitApplied = true,
            splineActive = true,
            linearFit = spline.linearFallback,
            splineFit = spline,
            splineFailureReason = null
        )
    }
    val linear = fitLinearCalibration(entries, now)
    return if (linear != null && linear.isApplicable) {
        CalibrationResult(
            calibratedMgdl = linear.slope * sensorMgdl + linear.offset + manualOffsetMgdl,
            fitApplied = true,
            splineActive = false,
            linearFit = linear,
            splineFit = null,
            splineFailureReason = splineResult.reason
        )
    } else {
        CalibrationResult(
            calibratedMgdl = sensorMgdl + manualOffsetMgdl,
            fitApplied = false,
            splineActive = false,
            linearFit = linear,
            splineFit = null,
            splineFailureReason = splineResult.reason
        )
    }
}
