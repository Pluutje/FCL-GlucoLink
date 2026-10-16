package com.fclglucolink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6TransmitterLife
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6TransmitterLifeLevel

/**
 * 07/10/2026 (RONDE 217) — UI-hulpjes voor de G6-transmitterleeftijd
 * (zie DexcomG6TransmitterLife.kt). Gedeeld door het statusscherm en het
 * compacte hoofdscherm-kaartje, zodat kleur en tekst gelijk blijven.
 */
@Composable
fun transmitterLifeColor(level: DexcomG6TransmitterLifeLevel): Color? = when (level) {
    DexcomG6TransmitterLifeLevel.OK -> null
    DexcomG6TransmitterLifeLevel.SOON -> Color(0xFFFFA000)
    DexcomG6TransmitterLifeLevel.NEAR_END -> Color(0xFFF4511E)
    DexcomG6TransmitterLifeLevel.LAST_START -> MaterialTheme.colorScheme.error
}

/** Korte waarde, bv. "83 days". */
@Composable
fun transmitterAgeValueText(ageDays: Double): String {
    val d = ageDays.toInt()
    return tr("$d days", "$d dagen")
}

/** Waarschuwingstekst; `null` bij [DexcomG6TransmitterLifeLevel.OK]. */
@Composable
fun transmitterLifeWarningText(level: DexcomG6TransmitterLifeLevel): String? = when (level) {
    DexcomG6TransmitterLifeLevel.OK -> null
    DexcomG6TransmitterLifeLevel.SOON -> tr(
        "Transmitter is over ${DexcomG6TransmitterLife.WARN_DAY} days old (nominal life ~${DexcomG6TransmitterLife.NEAR_END_DAY} days) — prepare a replacement.",
        "Transmitter is ouder dan ${DexcomG6TransmitterLife.WARN_DAY} dagen (nominale levensduur ~${DexcomG6TransmitterLife.NEAR_END_DAY} dagen) — zorg voor een vervanger."
    )
    DexcomG6TransmitterLifeLevel.NEAR_END -> tr(
        "Transmitter is past ${DexcomG6TransmitterLife.NEAR_END_DAY} days — it can stop at any time. A new sensor can still be started until day ${DexcomG6TransmitterLife.LAST_START_DAY}.",
        "Transmitter is ouder dan ${DexcomG6TransmitterLife.NEAR_END_DAY} dagen — kan elk moment stoppen. Een nieuwe sensor kan nog gestart worden tot dag ${DexcomG6TransmitterLife.LAST_START_DAY}."
    )
    DexcomG6TransmitterLifeLevel.LAST_START -> tr(
        "Transmitter is past day ${DexcomG6TransmitterLife.LAST_START_DAY}: no new sensor can be started anymore. The running session may last until ~day 109. Replace the transmitter.",
        "Transmitter is voorbij dag ${DexcomG6TransmitterLife.LAST_START_DAY}: er kan geen nieuwe sensor meer gestart worden. De lopende sessie kan nog tot ~dag 109 doorlopen. Vervang de transmitter."
    )
}
