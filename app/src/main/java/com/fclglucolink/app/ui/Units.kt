package com.fclglucolink.app.ui

/**
 * 30/07/2026 (editor) — GlucoseReading zelf blijft intern in mg/dL (dat is wat
 * de xDrip-broadcast naar AAPS verwacht, zie XDripBroadcaster), maar editor
 * werkt zelf altijd in mmol/L (zie alle FCLvNext-schermen/-logs) — dus de UI
 * rekent puur voor WEERGAVE om, nooit voor de broadcast zelf.
 *
 * 13/08/2026 (editor, RONDE 104 — Fase 1 van 2, op verzoek: "een mg/dl vs
 * mmol/l knop [...] intern hoeft er dan niks te veranderen maar in de ui
 * zou da weer gegeven Bg waarden dan moeten kunnen veranderen") — tot deze
 * ronde was de hele UI hardcoded mmol/L, verspreid over zo'n 6 losse
 * schermen (`formatMmol()` had geen mg/dL-tegenhanger, nergens werd een
 * voorkeur gelezen). [GlucoseUnit] + de onderstaande [formatForDisplay]/
 * [Double.parseFromDisplayUnit] zijn nu de ENE centrale plek die weet hoe een
 * mg/dL-waarde eruitziet in de door de gebruiker gekozen eenheid — de losse
 * schermen roepen voortaan dit aan i.p.v. zelf `formatMmol()` +
 * hardcoded " mmol/L"-tekst te combineren (zie AppSettings.kt's
 * `displayUnit` voor waar de voorkeur zelf leeft, en GlucoseChart.kt's
 * RONDE-104-kdoc voor waarom de grafiek z'n as-/kleurgrenzen ook naar
 * canonieke mg/dL-constanten is verhuisd i.p.v. losse mmol-getallen).
 */
private const val MGDL_PER_MMOL = 18.0182

fun Double.mgdlToMmol(): Double = this / MGDL_PER_MMOL

fun Double.formatMmol(): String = "%.1f".format(this.mgdlToMmol())

/** 30/07/2026 (editor) — omgekeerde richting, nodig voor de BG-simulator: editor
 *  voert waarden in mmol/L in (zoals overal), de rest van de pijplijn
 *  (GlucoseReading, XDripBroadcaster) werkt intern uitsluitend in mg/dL. */
fun Double.mmolToMgdl(): Double = this * MGDL_PER_MMOL

/**
 * 13/08/2026 (editor, RONDE 104) — de door de gebruiker gekozen
 * weergave-eenheid. Puur een UI-aangelegenheid (zie klasse-kdoc hierboven) —
 * [GlucoseReading]/[XDripBroadcaster] kennen dit type niet en hoeven het ook
 * nooit te kennen. `MMOL` is de standaardwaarde (zie AppSettings.kt's
 * `displayUnit`) zodat een bestaande installatie precies hetzelfde blijft
 * tonen als vóór deze ronde.
 */
enum class GlucoseUnit(val displayName: String, val suffix: String) {
    MGDL("mg/dL", "mg/dL"),
    MMOL("mmol/L", "mmol/L")
}

/**
 * Centrale plek om een mg/dL-waarde te tonen in [unit] — vervangt de losse
 * `"${x.formatMmol()} mmol/L"`-aanroepen. `MGDL` toont een heel getal (zoals
 * elke mg/dL-CGM-app dat doet, geen zinvolle decimalen op die schaal),
 * `MMOL` de bestaande 1-decimaal-opmaak.
 */
fun Double.formatForDisplay(unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> "%.0f".format(this)
    GlucoseUnit.MMOL -> this.formatMmol()
}

/** Zelfde als [formatForDisplay], maar inclusief eenheid-achtervoegsel (" mg/dL"
 *  / " mmol/L") — voor de plekken die nu `"$waarde mmol/L"` hardcoded doen. */
fun Double.formatForDisplayWithUnit(unit: GlucoseUnit): String =
    "${this.formatForDisplay(unit)} ${unit.suffix}"

/**
 * 21/08/2026 (editor, RONDE 118, op verzoek — na de vraag waarom Raw/
 * Calibrated/Filtered op StatusScreen.kt's pipeline-rij (PipelineValuesRow)
 * continu gelijk lijken) — die rij bestaat juist om de STAPPEN in de
 * pijplijn te kunnen onderscheiden, dus heeft eerder MEER precisie nodig
 * dan de hoofdcirkel (die bewust afgerond is op 1 decimaal/hele getallen
 * voor leesbaarheid). Bij mmol/L is 1 decimaal ≈ 1,8 mg/dL per stap — kleiner
 * dan dat verschilt het Kalman-filter vaak wél degelijk, maar was het na
 * afronding onzichtbaar. Losse functie i.p.v. [formatForDisplay] zelf een
 * precisie-parameter geven: de hoofdweergave (cirkel, grafiek, overal
 * elders) moet overal in de app exact hetzelfde blijven afronden, dit is
 * uitsluitend voor die ene diagnostische rij.
 */
fun Double.formatForDisplayPrecise(unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> "%.1f".format(this)
    GlucoseUnit.MMOL -> "%.2f".format(this.mgdlToMmol())
}

/**
 * Omgekeerde richting voor invoervelden (fingerstick, simulator/handmatige
 * BG-invoer): de gebruiker typt een getal in [unit], dit levert de mg/dL-
 * waarde op die intern/voor opslag gebruikt wordt. `null` bij een leeg/
 * onleesbaar veld — bewust geen default, de caller beslist zelf hoe een
 * ongeldige invoer behandeld wordt (zie CalibrationScreen.kt/
 * SimulatorSetupScreen.kt/ManualScreen.kt).
 */
fun String.parseToMgdl(unit: GlucoseUnit): Double? {
    val value = this.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return when (unit) {
        GlucoseUnit.MGDL -> value
        GlucoseUnit.MMOL -> value.mmolToMgdl()
    }
}
