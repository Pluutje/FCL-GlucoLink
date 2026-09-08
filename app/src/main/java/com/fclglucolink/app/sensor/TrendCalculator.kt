package com.fclglucolink.app.sensor

/**
 * ============================================================================
 * FCLGlucoLink — gemeten trend uit twee opeenvolgende metingen (RONDE 172/173)
 * ============================================================================
 *
 * 08/09/2026 (editor, RONDE 172, oorspronkelijk alleen in AlarmEvaluator.kt
 * gebouwd — zie dat bestand's klasse-kdoc voor de volledige analyse) —
 * [GlucoseReading.trendMgdlPerMin] komt rechtstreeks van een RUWE,
 * ongevalideerde transmitterbyte en kan losstaan van wat de sensor daarna
 * daadwerkelijk gemeten heeft (een enkele kapotte/rare byte, bv. vlak vóór
 * een sensor-error). Het GEMETEN verschil tussen de twee meest recente
 * ECHTE, opgeslagen metingen is daarentegen precies wat het thuisscherm zelf
 * al laat zien (StatusScreen.kt's `BgRingDisplay`: `deltaMgdl = latest -
 * previous`) — geen eigen regressie over veel punten (bewust buiten scope,
 * zie AlarmEvaluator.kt's Ronde 107-kdoc), puur het tweepuntsverschil dat
 * "trend" fundamenteel is.
 *
 * 08/09/2026 (editor, RONDE 173, VERVOLG) — de xDrip-broadcast
 * (XDripBroadcaster.kt) naar AAPS/het horloge liet een andere trendrichting
 * zien dan het thuisscherm, omdat die nog de ruwe trendbyte gebruikte.
 * Deze functie wordt sindsdien ook gebruikt om die broadcast te voeden,
 * i.p.v. de ruwe trendbyte. Bewust hierheen verplaatst (uit
 * AlarmEvaluator.kt) zodat beide gebruikers (alarm-corroboratie EN
 * broadcast) exact dezelfde berekening delen — en bewust generiek per
 * [GlucoseReading], dus dit werkt identiek voor alle sensortypes (G6/G7/
 * CareSens Air/simulator) zonder per-driver-code.
 */
object TrendCalculator {

    // Ondergrens 2 min: te dicht op elkaar is te ruisgevoelig (kleine
    // tijdsdelta versterkt kleine meetruis tot een grote schijnbare trend).
    // Bovengrens 15 min: bij drie gemiste 5-min-cycli is het gat te groot om
    // nog iets zinnigs over "de trend net vóór nu" te zeggen — de aanroeper
    // valt dan terug op zijn eigen fallback (ruwe trendbyte).
    private const val MIN_COMPARISON_GAP_MS = 2 * 60_000L
    private const val MAX_COMPARISON_GAP_MS = 15 * 60_000L

    /**
     * Het daadwerkelijk gemeten verschil (mg/dL/min) tussen [latest] en
     * [previous]. Geeft `null` terug als er geen bruikbare vorige meting is
     * (afwezig, te dichtbij, of te ver weg) — de aanroeper valt dan terug op
     * zijn eigen fallback-gedrag.
     */
    fun measuredMgdlPerMin(latest: GlucoseReading, previous: GlucoseReading?): Float? {
        if (previous == null) return null
        val deltaMs = latest.timestampMs - previous.timestampMs
        if (deltaMs < MIN_COMPARISON_GAP_MS || deltaMs > MAX_COMPARISON_GAP_MS) return null
        val deltaMinutes = deltaMs / 60_000f
        return ((latest.glucoseMgdl - previous.glucoseMgdl) / deltaMinutes).toFloat()
    }
}
