package com.fclglucolink.app.calibration

import kotlin.math.abs

/**
 * ============================================================================
 * FCLGlucoLink — automatisch overslaan van afwijkende vingerprikken (RONDE 241)
 * ============================================================================
 *
 * 20/10/2026 (editor, RONDE 241) — op verzoek: een vingerprik die duidelijk niet
 * past bij de rest hoort de curve niet te verstoren, en dat moet vanzelf gaan
 * zonder dat de gebruiker punten met de hand uit hoeft te vinken. Regels:
 *
 * 1. Het NIEUWSTE punt wordt nooit beoordeeld. We weten pas bij de volgende
 *    controle of het een eenmalige afwijking was.
 * 2. Verschuiving: bevestigen de twee nieuwste punten elkaar (zelfde richting,
 *    ongeveer even groot, allebei buiten de marge t.o.v. de oudere punten), dan
 *    is de sensor echt veranderd. Dan vallen de OUDERE punten af die bij de oude
 *    toestand horen, en bepalen de nieuwe punten de curve. Blijven er minder
 *    dan 4 over, dan valt de kalibratie vanzelf terug op een gewone lijn
 *    (zie [computeCalibration]).
 * 3. Anders: een ouder punt dat ver van de lijn van de ANDERE punten ligt
 *    (leave-one-out) wordt overgeslagen. Het nieuwste punt telt hierbij niet mee
 *    als bewijs, zodat één afwijkende nieuwe prik de oudere punten niet kan
 *    wegdrukken. Een punt aan de rand van het bereik (weinig steun van andere
 *    punten) wordt niet beoordeeld.
 *
 * Er wordt NIETS opgeslagen: de selectie wordt bij elke berekening opnieuw
 * gemaakt, het vinkje van de gebruiker blijft zoals het was, en een overgeslagen
 * punt komt vanzelf terug als de rest er later toch bij past. Dat betekent ook
 * dat er geen wijziging in de database nodig is.
 *
 * Grenzen: de marge schaalt met de spreiding van de punten (tussen 1 en 1,7
 * mmol/L, zodat een uitschieter haar niet onbeperkt kan oprekken) maar is nooit
 * kleiner dan 15% van de glucosewaarde (bij 12 mmol/L is 1,8 mmol/L verschil
 * normaal). Een verschuiving van de sensor wordt pas aangenomen bij minstens
 * 2 mmol/L. Er worden hoogstens een kwart van de punten
 * overgeslagen en er blijven er minstens 3 over.
 */

enum class AutoSkipReason {
    /** Ver van de lijn van de andere punten, en het volgende punt bevestigt het niet. */
    OUTLIER,

    /** Hoort bij de toestand van vóór een bevestigde verschuiving van de sensor. */
    BEFORE_SHIFT
}

/**
 * [used] gaat de kalibratiewiskunde in. [skipped] geeft per overgeslagen
 * entry-id de reden, voor de UI.
 */
data class EntrySelection(
    val used: List<CalibrationEntry>,
    val skipped: Map<Long, AutoSkipReason>
)

private const val MIN_ENTRIES_FOR_SELECTION = 4
private const val MIN_DEVIATION_MGDL = 18.0
private const val MAX_DEVIATION_MGDL = 30.0

/** Een vingerprik mag ook ruim afwijken van de lijn zolang dat binnen de gewone
 *  meetnauwkeurigheid van sensor en meter valt (ruwweg 15%) — bij 12 mmol/L is
 *  1,2 mmol/L verschil dus normaal. */
private const val RELATIVE_DEVIATION = 0.15

/** Een verschuiving van de sensor wordt pas aangenomen bij minstens 2 mmol/L. */
private const val MIN_SHIFT_MGDL = 36.0
private const val SIGMA_FACTOR = 2.5
private const val EDGE_MARGIN_MGDL = 18.0
private const val MIN_ENTRIES_KEPT = 3
private const val MAD_TO_SIGMA = 1.4826

fun selectUsableEntries(
    entries: List<CalibrationEntry>,
    now: Long,
    // Entry-ids die de gebruiker zelf wil meetellen: nooit overslaan.
    forcedIds: Set<Long> = emptySet()
): EntrySelection {
    val sorted = entries.sortedBy { it.timestampMs }
    if (sorted.size < MIN_ENTRIES_FOR_SELECTION) return EntrySelection(sorted, emptyMap())
    detectShift(sorted, now, forcedIds)?.let { return it }
    return dropOutliers(sorted, now, forcedIds)
}

private fun residual(entry: CalibrationEntry, fit: CalibrationFit): Double =
    entry.fingerstickMgdl - (fit.slope * entry.sensorMgdlAtPairing + fit.offset)

private fun robustSigma(residuals: List<Double>): Double {
    if (residuals.size < 3) return 0.0
    val sorted = residuals.map { abs(it) }.sorted()
    val n = sorted.size
    val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    return MAD_TO_SIGMA * median
}

/** Toegestane afwijking bij glucosewaarde [levelMgdl]: de spreiding van de punten
 *  (begrensd tussen 1 en 1,7 mmol/L), maar nooit minder dan 15% van de waarde. */
private fun threshold(sigma: Double, levelMgdl: Double): Double =
    maxOf((SIGMA_FACTOR * sigma).coerceIn(MIN_DEVIATION_MGDL, MAX_DEVIATION_MGDL), RELATIVE_DEVIATION * levelMgdl)

/** Zie punt 2 in de klasse-kdoc. Geeft null als er geen verschuiving is. */
private fun detectShift(sorted: List<CalibrationEntry>, now: Long, forcedIds: Set<Long>): EntrySelection? {
    val old = sorted.dropLast(2)
    val second = sorted[sorted.size - 2]
    val newest = sorted.last()
    val fitOld = fitLinearCalibration(old, now) ?: return null
    val level = (newest.fingerstickMgdl + second.fingerstickMgdl) / 2.0
    val limit = maxOf(MIN_SHIFT_MGDL, threshold(robustSigma(old.map { residual(it, fitOld) }), level))

    val rNewest = residual(newest, fitOld)
    val rSecond = residual(second, fitOld)
    val bothOutside = abs(rNewest) > limit && abs(rSecond) > limit
    val sameDirection = rNewest * rSecond > 0.0
    val agree = abs(rNewest - rSecond) <= limit
    if (!(bothOutside && sameDirection && agree)) return null

    val meanShift = (rNewest + rSecond) / 2.0
    val run = mutableListOf(second, newest)
    var rest = old
    while (rest.isNotEmpty()) {
        val candidate = rest.last()
        val base = rest.dropLast(1)
        val fitBase = if (base.size >= 2) fitLinearCalibration(base, now) else fitOld
        if (fitBase == null) break
        val r = residual(candidate, fitBase)
        val joinsNewState = abs(r) > limit && r * meanShift > 0.0 && abs(r - meanShift) <= limit
        if (!joinsNewState) break
        run.add(0, candidate)
        rest = base
    }
    val (keptByUser, dropped) = rest.partition { it.id in forcedIds }
    return EntrySelection(
        used = (keptByUser + run).sortedBy { it.timestampMs },
        skipped = dropped.associate { it.id to AutoSkipReason.BEFORE_SHIFT }
    )
}

/** Zie punt 3 in de klasse-kdoc. */
private fun dropOutliers(sorted: List<CalibrationEntry>, now: Long, forcedIds: Set<Long>): EntrySelection {
    val current = sorted.toMutableList()
    val skipped = LinkedHashMap<Long, AutoSkipReason>()
    val maxSkips = sorted.size / 4

    for (round in 0 until maxSkips) {
        if (current.size <= MIN_ENTRIES_KEPT) break
        // Het nieuwste punt wordt niet beoordeeld en telt niet mee als bewijs.
        val judged = current.dropLast(1)
        val residuals = ArrayList<Double>(judged.size)
        var worstIndex = -1
        var worstRatio = 0.0
        val fits = HashMap<Int, Double>()
        for (i in judged.indices) {
            val rest = judged.filterIndexed { index, _ -> index != i }
            val fit = fitLinearCalibration(rest, now) ?: continue
            val r = residual(judged[i], fit)
            residuals.add(r)
            fits[i] = r
        }
        val sigma = robustSigma(residuals)
        for ((i, r) in fits) {
            val rest = judged.filterIndexed { index, _ -> index != i }
            val x = judged[i].sensorMgdlAtPairing
            val low = rest.minOf { it.sensorMgdlAtPairing } - EDGE_MARGIN_MGDL
            val high = rest.maxOf { it.sensorMgdlAtPairing } + EDGE_MARGIN_MGDL
            if (x !in low..high) continue
            if (judged[i].id in forcedIds) continue
            val ratio = abs(r) / threshold(sigma, judged[i].fingerstickMgdl)
            if (ratio > worstRatio) {
                worstIndex = i
                worstRatio = ratio
            }
        }
        if (worstIndex < 0) break
        if (worstRatio <= 1.0) break
        val removed = judged[worstIndex]
        skipped[removed.id] = AutoSkipReason.OUTLIER
        current.remove(removed)
    }
    return EntrySelection(used = current, skipped = skipped)
}
