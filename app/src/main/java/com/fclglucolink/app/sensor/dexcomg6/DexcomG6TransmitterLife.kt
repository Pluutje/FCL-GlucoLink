package com.fclglucolink.app.sensor.dexcomg6

/**
 * 07/10/2026 (RONDE 217, op verzoek) — levensduur van de G6-TRANSMITTER zelf
 * (los van de sensorsessie van 10 dagen): een stock-transmitter loopt ~90
 * dagen; op dag 99 kan voor het laatst nog een sensor gestart worden, daarna
 * (tot ~109 dagen) loopt die laatste sessie nog uit.
 *
 * Leeftijd = `TransmitterTimeRx.currentTime` (seconden sinds activatie van de
 * transmitter), op het moment van uitlezen opgeslagen met het uitleesmoment;
 * de huidige leeftijd wordt daaruit geëxtrapoleerd (zie [ageDays]).
 * Alleen zinvol voor een stock-transmitter (Anubis wordt anders gebruikt).
 */
enum class DexcomG6TransmitterLifeLevel { OK, SOON, NEAR_END, LAST_START }

object DexcomG6TransmitterLife {
    const val WARN_DAY = 80
    const val NEAR_END_DAY = 90
    const val LAST_START_DAY = 99

    /** Geëxtrapoleerde leeftijd in dagen (met decimalen). */
    fun ageDays(ageSecsAtQuery: Long, queriedAtMs: Long, nowMs: Long): Double =
        (ageSecsAtQuery * 1000L + (nowMs - queriedAtMs).coerceAtLeast(0L)) / 86_400_000.0

    fun level(ageDays: Double): DexcomG6TransmitterLifeLevel = when {
        ageDays >= LAST_START_DAY -> DexcomG6TransmitterLifeLevel.LAST_START
        ageDays >= NEAR_END_DAY -> DexcomG6TransmitterLifeLevel.NEAR_END
        ageDays >= WARN_DAY -> DexcomG6TransmitterLifeLevel.SOON
        else -> DexcomG6TransmitterLifeLevel.OK
    }
}
