package com.fclglucolink.app.sensor

import kotlin.math.abs

/**
 * ============================================================================
 * FCLGlucoLink — sensor-onafhankelijke databetrouwbaarheids-indicator
 * ============================================================================
 *
 * 24/09/2026 (editor, RONDE 181, op verzoek na een CareSens Air-caselog van een andere gebruiker
 * waarbij de sensor ruim een uur exact dezelfde waarde (10,0 mmol/L)
 * doorgaf) — dit bestand bepaalt of de recent binnengekomen metingen van
 * een sensor "Normal", "Noisy" of "Frozen" gedrag vertonen, ONGEACHT welk
 * sensortype (CareSens Air/G6/G7/simulator) de metingen aanlevert: het werkt
 * uitsluitend op de al-verwerkte [GlucoseReading]-stroom die elke driver toch
 * al oplevert, dus geen sensortype-specifieke kennis hier nodig.
 *
 * BELANGRIJK — waarom dit op [GlucoseReading.glucoseMgdl] vergelijkt en NIET
 * op de afgeronde mmol/L-weergavewaarde: een korte controle op de
 * gebruiker's eigen FCLvNext-CSV (referentie, gezonde sensor) liet zien dat
 * 3-11 identieke waarden op rij bij de 1-decimaal-mmol/L-afronding die AAPS
 * loggen TIENTALLEN keren per week gewoon voorkomen — dat is dus puur een
 * afrondingsartefact (0,1 mmol/L ≈ bijna 1,8 mg/dL brede "emmer" waar een
 * licht schommelende, gezonde sensor toevallig een paar keer op rij in
 * valt), geen aanwijzing voor een vastgelopen sensor. `glucoseMgdl` is de
 * VOLLEDIGE precisie die de app zelf al intern gebruikt (1 decimaal in
 * mg/dL, dus ~18x fijner) — bij DIE precisie is een toevallige exacte match
 * vrijwel onmogelijk voor een sensor die echt nog ruis heeft, waardoor een
 * paar keer op rij exact gelijk een veel betekenisvoller signaal is.
 *
 * Twee drempels (op verzoek): vanaf [FROZEN_WARNING_STREAK] identieke
 * metingen op rij een zachte waarschuwing ("looks frozen" — kan nog toeval
 * zijn), vanaf [FROZEN_CONFIRMED_STREAK] een bevestiging (op dat punt is
 * "toeval" praktisch uitgesloten, zie kdoc hierboven).
 */
sealed class SensorStability {
    object Normal : SensorStability()
    object Noisy : SensorStability()

    /** [streak] metingen op rij zijn exact [valueMgdl] — nog niet zeker,
     *  maar al opvallend. [sinceMs] is het tijdstip van de EERSTE meting in
     *  die reeks. */
    data class FrozenWarning(val streak: Int, val sinceMs: Long, val valueMgdl: Double) : SensorStability()

    /** Zelfde als [FrozenWarning], maar de reeks is inmiddels lang genoeg
     *  dat toeval praktisch uitgesloten is. */
    data class FrozenConfirmed(val streak: Int, val sinceMs: Long, val valueMgdl: Double) : SensorStability()
}

private const val FROZEN_WARNING_STREAK = 3
private const val FROZEN_CONFIRMED_STREAK = 5

// ~1 uur aan metingen bij de gangbare 5-minuten-cadans van elk ondersteund
// sensortype — ruim genoeg voor zowel de bevroren-reeks-detectie als de
// noisy-detectie hieronder, zonder te ver terug in de tijd te kijken.
private const val STABILITY_WINDOW_READINGS = 12

// Fysiologisch is een verandering van meer dan een paar mg/dL per minuut al
// zeldzaam (zie GlucoseChart.kt's/predictie-module's kdoc voor vergelijkbare
// aannames) — 8 mg/dL/min (~40 mg/dL over 5 minuten) ligt daar ruim boven,
// bewust royaal om valse "Noisy"-meldingen bij een gewoon snel dalende/
// stijgende curve te vermijden.
private const val IMPLAUSIBLE_RATE_MGDL_PER_MIN = 8.0
private const val NOISY_JUMP_COUNT_THRESHOLD = 2

/**
 * @param readingsChronological OUDSTE eerst, NIEUWSTE laatst (zelfde volgorde
 *   als [com.fclglucolink.app.data.GlucoseReadingStore.recentReadings]
 *   teruggeeft). `null` terug als er nog te weinig metingen zijn om iets
 *   zinnigs te zeggen (bv. net na het koppelen van een nieuwe sensor).
 */
fun computeSensorStability(readingsChronological: List<GlucoseReading>): SensorStability? {
    if (readingsChronological.size < FROZEN_WARNING_STREAK) return null
    val recent = readingsChronological.takeLast(STABILITY_WINDOW_READINGS)

    var streak = 1
    for (i in recent.size - 1 downTo 1) {
        if (recent[i].glucoseMgdl == recent[i - 1].glucoseMgdl) streak++ else break
    }
    if (streak >= FROZEN_CONFIRMED_STREAK) {
        val first = recent[recent.size - streak]
        return SensorStability.FrozenConfirmed(streak, first.timestampMs, first.glucoseMgdl)
    }
    if (streak >= FROZEN_WARNING_STREAK) {
        val first = recent[recent.size - streak]
        return SensorStability.FrozenWarning(streak, first.timestampMs, first.glucoseMgdl)
    }

    var implausibleJumps = 0
    for (i in 1 until recent.size) {
        val deltaMgdl = abs(recent[i].glucoseMgdl - recent[i - 1].glucoseMgdl)
        val deltaMinutes = (recent[i].timestampMs - recent[i - 1].timestampMs) / 60_000.0
        // Alleen cycli met een aannemelijke tussenafstand meenemen (0,5-20
        // min) — een grote tijdsprong (bv. na een lange verbindingsonderbreking)
        // levert vanzelf een groot mg/dL-verschil op zonder dat dat iets over
        // ruis zegt, en is dus bewust uitgesloten.
        if (deltaMinutes in 0.5..20.0 && deltaMgdl / deltaMinutes > IMPLAUSIBLE_RATE_MGDL_PER_MIN) {
            implausibleJumps++
        }
    }
    return if (implausibleJumps >= NOISY_JUMP_COUNT_THRESHOLD) SensorStability.Noisy else SensorStability.Normal
}

/** Korte tekst voor het compacte hoofdscherm-kaartje. */
fun SensorStability.shortLabel(): String = when (this) {
    SensorStability.Normal -> "Running mode: normal"
    SensorStability.Noisy -> "Running mode: noisy"
    is SensorStability.FrozenWarning -> "Running mode: sensor looks frozen"
    is SensorStability.FrozenConfirmed -> "Running mode: frozen"
}

/**
 * Langere uitleg voor de "Diagnostics"-kaart op het volle statusscherm —
 * `null` bij Normal/Noisy (het korte label zegt daar al genoeg).
 *
 * 24/09/2026 (editor, RONDE 181, op verzoek: "ik wil geen echt technische
 * info zien waar een gemiddelde gebruiker niks mee kan ... de omschrijving
 * moet begrijpelijk zijn voor de gebruiker") — bewust GEEN cijfers/vaktaal
 * (geen "streak", geen mg/dL-waarde, geen "reading count") — alleen wat het
 * voor de gebruiker BETEKENT en wat die eraan kan doen. De precieze
 * getallen (streak/valueMgdl) blijven wel in de datamodel-klasse zelf staan
 * (bruikbaar voor eventuele latere logging), alleen deze weergavetekst
 * verbergt ze.
 */
fun SensorStability.frozenDetail(): String? = when (this) {
    is SensorStability.FrozenWarning ->
        "The last few readings have been exactly the same. That can happen once in a while, so " +
            "no action needed yet — just keep an eye on it."
    is SensorStability.FrozenConfirmed ->
        "The sensor has been sending the exact same reading for a while now. A real sensor " +
            "always has a little natural variation, so this usually means the sensor or its " +
            "connection is stuck. Try disconnecting and reconnecting it; if that doesn't help, " +
            "the sensor may need to be replaced."
    else -> null
}
