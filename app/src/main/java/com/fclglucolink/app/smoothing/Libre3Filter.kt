package com.fclglucolink.app.smoothing

import kotlin.math.pow

/**
 * RONDE 260 (TEST) — filter for the 1-minute readings of the Libre 3 (via JugglucoNG).
 *
 * It is a causal filter: it only uses readings from the past. It has two steps:
 *  1. Smoothing: an exponential average. [alpha] is the weight of a new reading per minute
 *     (0.7 = follow the sensor closely, 0.3 = smooth a lot). It is time-based, so a reading that
 *     comes 2 minutes after the last one counts as two steps.
 *  2. Lead: the value is moved forward by [leadMinutes] minutes along the slope of the smoothed
 *     curve (the slope over about 3 minutes). The move is never more than [maxLeadMgdl]. This gives
 *     a rise or fall a little earlier, at the price of a larger overshoot near a peak.
 *
 * The numbers come from the offline analysis in `tools/libre3_filter_analyse.py`
 * (RONDE 260 chose alpha 0.7 and lead 2 minutes for speed. RONDE 261 changed the defaults to alpha 0.15
 * and no lead, because that choice was 3 times less calm than the Dexcom G6). They are still a test.
 * The raw 1-minute data is always kept (`libre3_minuut_*.csv` and the raw value in the database),
 * so another filter can be tried on the same data later.
 *
 * Not thread safe: use one instance per slot, from one coroutine.
 */
class Libre3Filter {

    data class Output(
        /** The value to show and store: smoothed level plus the lead. */
        val glucoseMgdl: Double,
        /** The smoothed level, without the lead. */
        val levelMgdl: Double,
        /** Slope of the smoothed level in mg/dL per minute (0 while there is not enough history). */
        val slopeMgdlPerMin: Double
    )

    private var level: Double? = null
    private var lastTimestampMs: Long = 0L
    private val history = ArrayDeque<Pair<Long, Double>>()

    /** Forget everything. Call this when a new physical sensor starts. */
    fun reset() {
        level = null
        lastTimestampMs = 0L
        history.clear()
    }

    /**
     * @param alpha weight of a new reading per minute, 0.05..1.0.
     * @param leadMinutes how far to move the value forward, 0 = no lead.
     * @param maxLeadMgdl the largest move in mg/dL, in both directions.
     */
    fun filter(
        rawMgdl: Double,
        timestampMs: Long,
        alpha: Double,
        leadMinutes: Double,
        maxLeadMgdl: Double
    ): Output {
        val previous = level
        // A reading that is not newer than the last one (backfill, double delivery) does not
        // change the state. It is returned as it is.
        if (previous != null && timestampMs <= lastTimestampMs) {
            return Output(rawMgdl, rawMgdl, 0.0)
        }
        val dtMin = (timestampMs - lastTimestampMs) / 60_000.0
        val newLevel = if (previous == null || dtMin > MAX_GAP_MINUTES) {
            // First reading, or a long gap: start again at the raw value.
            history.clear()
            rawMgdl
        } else {
            val a = alpha.coerceIn(MIN_ALPHA, 1.0)
            val k = 1.0 - (1.0 - a).pow(dtMin)
            previous + k * (rawMgdl - previous)
        }
        level = newLevel
        lastTimestampMs = timestampMs
        history.addLast(timestampMs to newLevel)
        while (history.isNotEmpty() && timestampMs - history.first().first > HISTORY_MS) history.removeFirst()

        // Slope: smoothed level now minus the level about 3 minutes ago.
        var slope = 0.0
        val reference = history.lastOrNull { timestampMs - it.first >= SLOPE_MIN_AGE_MS }
        if (reference != null) {
            slope = (newLevel - reference.second) / ((timestampMs - reference.first) / 60_000.0)
        }
        val lead = (leadMinutes * slope).coerceIn(-maxLeadMgdl, maxLeadMgdl)
        return Output(newLevel + lead, newLevel, slope)
    }

    companion object {
        // RONDE 261 — the defaults aim at STABILITY first. On the 5-minute values, alpha 0.15 is about
        // as calm as the Dexcom G6 before its own smoothing (alpha 0.10 matches the G6 after smoothing),
        // and a lead makes the value less calm. Speed will come later from a separate signal.
        const val DEFAULT_ALPHA = 0.15
        const val DEFAULT_LEAD_MINUTES = 0.0
        const val DEFAULT_MAX_LEAD_MGDL = 15.0

        private const val MIN_ALPHA = 0.05
        private const val MAX_GAP_MINUTES = 5.0
        private const val HISTORY_MS = 6 * 60_000L
        // The slope looks back at least 2 minutes, normally about 3 (the oldest reading is at most 6 min old).
        private const val SLOPE_MIN_AGE_MS = 150_000L
    }
}
