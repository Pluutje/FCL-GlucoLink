package com.fclglucolink.app.sensor.simulator

import kotlin.random.Random

/**
 * 30/07/2026 (editor) — mock BG-generator voor de simulator: GEEN losse,
 * onafhankelijke toevalswaarden (dat zou een grillige zaagtand-grafiek geven
 * en is niets zoals een echte CGM), maar een simpele random-walk die meestal
 * rond een baseline blijft hangen met kleine stapjes, en af en toe een
 * maaltijdachtige stijging-en-daling doet — precies wat editor vroeg: "wel een
 * beetje een reële waarde t.o.v. de vorige, die een gewoon stabiel of
 * maaltijdpatroon volgt". Puur een testhulpmiddel, geen fysiologisch model.
 */
class RandomBgGenerator(
    startMgdl: Double = 120.0,
    private val baselineMgdl: Double = 110.0,
    private val random: Random = Random.Default
) {
    private var current = startMgdl
    private var mealTicksLeft = 0
    private var mealStepMgdl = 0.0

    /** Eén stap verder — te gebruiken per interval-tick (typisch elke 5 min). */
    fun next(): Double {
        if (mealTicksLeft > 0) {
            current += mealStepMgdl
            mealTicksLeft--
        } else if (random.nextDouble() < MEAL_CHANCE_PER_TICK) {
            // Nieuwe "maaltijd" starten: geleidelijke stijging over enkele
            // stappen, daarna trekt de gewone mean-reversion 'm vanzelf weer
            // terug richting baseline (net als insuline dat zou doen).
            val totalRise = random.nextDouble(40.0, 90.0)
            val riseTicks = random.nextInt(4, 8)
            mealStepMgdl = totalRise / riseTicks
            mealTicksLeft = riseTicks
            current += mealStepMgdl
            mealTicksLeft--
        } else {
            // Rustige toestand: kleine trek terug naar baseline + wat jitter,
            // zodat het een stabiel maar niet doodvlak lijntje geeft.
            val pullBack = (baselineMgdl - current) * MEAN_REVERSION_RATE
            val jitter = random.nextDouble(-JITTER_MGDL, JITTER_MGDL)
            current += pullBack + jitter
        }
        current = current.coerceIn(MIN_MGDL, MAX_MGDL)
        return current
    }

    companion object {
        private const val MEAL_CHANCE_PER_TICK = 0.04
        private const val MEAN_REVERSION_RATE = 0.08
        private const val JITTER_MGDL = 6.0
        private const val MIN_MGDL = 50.0
        private const val MAX_MGDL = 300.0
    }
}
