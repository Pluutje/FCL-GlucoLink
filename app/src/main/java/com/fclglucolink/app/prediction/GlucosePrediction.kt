package com.fclglucolink.app.prediction

import com.fclglucolink.app.sensor.GlucoseReading
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * RONDE 160 — Bg-voorspelling voor het komende uur, op verzoek ("is dat ik
 * graag een voorspelling van de Bg wil zien waar die het komende uur naar
 * toe kan gaan ... 2 lijnen duidelijk afwijkend van de echte Bg grafiek
 * vanaf het laatste punt door te tekenen waarbinnen de Bg het komende uur
 * naar alle waarschijnlijkheid gaat bewegen ... twee lijnen naar rechts
 * getekend die van uit de oorsprong dus divergeren", zie README voor het
 * volledige citaat). Sensor-agnostisch (werkt identiek voor elk
 * SensorType/elke slot, wordt door zowel GlucoseChart als DualGlucoseChart
 * gebruikt) en bewust ONAFHANKELIJK van MPAndroidChart — puur wiskunde op
 * de bestaande [GlucoseReading]-lijst; de UI-laag (GlucoseChart.kt) zet de
 * uitkomst pas om in Entry's/LineDataSets.
 *
 * Aanpak (uitgelegd omdat hier geen kant-en-klare standaardformule voor
 * bestaat — de gebruiker liet de precieze methode expliciet aan mij over:
 * "De hoeveelheid historische data die nodig is laat ik aan jou over je
 * moet gewoon zo veel gebruiken om tot een redelijk betrouwbare
 * voorspelling te komen"):
 *
 * 1. Trend (richtingscoëfficiënt) via lineaire regressie (kleinste-
 *    kwadraten) over een recent historisch venster ([HISTORY_WINDOW_MINUTES]
 *    minuten) — langer dan "alleen de laatste 2 punten" dempt ruis in een
 *    enkele meting, maar niet zo lang dat een trendwissel van een uur
 *    geleden de voorspelling nog blijft beïnvloeden. 45 minuten (~9 punten
 *    bij de gebruikelijke 5-minuten-cadans) is de gekozen middenweg.
 * 2. De centrale voorspellingslijn is NIET de regressielijn zelf
 *    doorgetrokken (die loopt door meetruis vrijwel nooit exact door het
 *    laatste échte punt — dat zou een zichtbare "sprong" geven op het
 *    overgangspunt). In plaats daarvan: de regressie-HELLING (mg/dL per
 *    minuut) toegepast VANAF de laatste echte meting — dat is letterlijk
 *    de gevraagde "vanaf het laatste punt door te tekenen".
 *
 *    29/08/2026 (editor, RONDE 161, live-melding na het testen van v174:
 *    "op basis van de buigpunten en afvlakking moet dat ook beter kunnen")
 *    — de helling wordt niet meer de volle 60 minuten lang ONVERANDERD
 *    doorgetrokken (dat zou een kortstondige, snelle stijging/daling
 *    onrealistisch een vol uur laten doorlopen, tot ver buiten wat een BG-
 *    curve in de praktijk doet — een stijging vlakt vrijwel altijd af).
 *    In plaats daarvan wordt de helling exponentieel "afgebouwd" met een
 *    tijdconstante [FLATTENING_TAU_MINUTES]: de cumulatieve verplaatsing op
 *    tijdstip t is `slope * tau * (1 - e^(-t/tau))` i.p.v. `slope * t`. Voor
 *    kleine t (t << tau) gedraagt dit zich vrijwel identiek aan de rechte
 *    lijn van hiervoor (het buigpunt is nog niet bereikt); naarmate t tau
 *    nadert, vlakt de lijn zichtbaar af naar een horizontale asymptoot
 *    (`slope * tau`) — precies het gevraagde "buigpunt + afvlakking",
 *    zonder dat er een tweede, apart geschat "hoeveel vlakt het af"-getal
 *    nodig is (tau doet beide tegelijk).
 * 3. De onder-/bovengrens groeien uit elkaar (divergeren) LINEAIR met de
 *    verstreken tijd. Op t=0 is de marge exact 0 — de band begint als één
 *    punt, precies op de laatste meting ("vanuit de oorsprong ...
 *    divergeren"), en waaiert daarna uit.
 *
 *    29/08/2026 (editor, RONDE 161, live-melding met screenshots na het
 *    testen van v174: "de voorspellingen tonen 2 lijnen, maar die liggen nu
 *    zover uit elkaar dat het geen voorspelling meer is maar een 100%
 *    zekerheid [...] Zeker de eerste 15 minuten moet het veel dichter bij
 *    elkaar komen [...] een lineaire minimum en maximum voorspelling al veel
 *    beter met een kleinere marge") — dit was voorheen een groei evenredig
 *    met de WORTEL van de tijd (√t, de statistisch "juiste" vorm voor een
 *    random walk, zie de oorspronkelijke RONDE-160-redenering hieronder bij
 *    punt 4). Het probleem daarmee, zichtbaar in de meegestuurde
 *    screenshots: √t stijgt juist het SNELST vlak na t=0 en vlakt pas
 *    daarna af — bij 15 minuten was daardoor al de HELFT van de totale
 *    marge-groei tot een uur (√15 / √60 = 0,5) al "verbruikt", precies
 *    tegenovergesteld aan wat hier gevraagd is. Vervangen door een gewone
 *    lineaire groei (marge evenredig met t, dus op 15 min nog maar een kwart
 *    van de marge op 60 min) — eenvoudiger, en doet precies wat gevraagd is:
 *    "een lineaire minimum en maximum voorspelling". De statistische
 *    zuiverheid van √t is hier minder belangrijk dan dat de band aanvoelt
 *    als een voorspelling met toenemende onzekerheid, niet als een tweede
 *    paar harde randen die de curve meteen al lijkt te claimen.
 * 4. De schaal van die marge komt uit de RESIDUE-spreiding van de recente
 *    meetpunten t.o.v. hun eigen regressielijn (hoe "grillig" de laatste
 *    periode was) — een rustige, vlakke periode geeft dus een smallere
 *    band dan een net doorgemaakte snelle stijging/daling, plus een vaste
 *    ondergrens ([MIN_VOLATILITY_MGDL], een algemene CGM-meetruisvloer)
 *    zodat de band nooit tot een onrealistisch dun lijntje inklapt bij
 *    toevallig heel gladde recente data. [MARGIN_SCALE] is (RONDE 161) fors
 *    verlaagd t.o.v. de oorspronkelijke waarde — zie de kdoc daar.
 *
 *    29/08/2026 (editor, RONDE 162 — op verzoek, na een backtest tegen een
 *    echte FCLvNext-log met bekende afloop: "voer ze allebei maar door",
 *    zie de kdoc bij [LONG_TERM_VOLATILITY_WEIGHT] voor de volledige
 *    backtest-redenering) — de RONDE-161-marge bleek in die backtest een
 *    structureel te lage dekking te geven (~19% van de gecontroleerde
 *    toekomstpunten viel binnen de band): tijdens een rustige periode is de
 *    residue-spreiding over de laatste 45 minuten vrijwel per definitie
 *    heel klein, ook al kan de Bg daarna gewoon weer beginnen te bewegen
 *    (een net-begonnen maaltijdstijging kan de rustige 45 minuten ervoor
 *    natuurlijk niet aankondigen). De marge gebruikt daarom niet meer
 *    UITSLUITEND de korte-termijn (45 min) residue, maar het MAXIMUM van
 *    die korte-termijn-waarde EN een fractie van een LANGERE-termijn
 *    residue (zie [LONG_TERM_VOLATILITY_WEIGHT]/[LONG_TERM_WINDOW_MINUTES])
 *    — de trend/helling zelf (punt 1-2 hierboven) blijft wel uitsluitend op
 *    de korte 45-minuten-termijn gebaseerd, alleen de BANDBREEDTE kijkt
 *    verder terug.
 */

/** Eén berekend punt op de voorspellingsband, [minutesFromNow] minuten na
 *  de laatste echte meting. */
data class GlucosePredictionPoint(
    val minutesFromNow: Float,
    val lowerMgdl: Float,
    val upperMgdl: Float
)

/** 1 uur vooruit — expliciet bevestigd door de gebruiker ("1 uur vooruit
 *  is voldoende veel verder is toch te onbetrouwbaar"). */
const val PREDICTION_HORIZON_MINUTES = 60f

/** Stapgrootte tussen berekende punten — 5 minuten sluit aan bij de
 *  gebruikelijke sensor-cadans en is fijn genoeg voor een vloeiend ogende
 *  lijn zonder onnodig veel punten te genereren. */
private const val PREDICTION_STEP_MINUTES = 5f

/** Hoeveel recente historie de regressie/volatiliteit gebruikt — zie
 *  klasse-kdoc, punt 1. */
private const val HISTORY_WINDOW_MINUTES = 45L

/** Minimaal aantal punten in het venster nodig voor een zinvolle
 *  regressie — bij minder wordt geen voorspelling getoond (liever geen
 *  band dan een band gebaseerd op 1-2 toevallige punten). */
private const val MIN_HISTORY_POINTS = 4

/** Ondergrens voor de gebruikte "volatiliteit" (mg/dL) — zie klasse-kdoc
 *  punt 4. 29/08/2026 (editor, RONDE 162) — licht verhoogd (4 → 5): met de
 *  langere-termijn-component hieronder is deze vloer in de praktijk bijna
 *  nooit meer de bepalende factor (die component ligt vrijwel altijd hoger),
 *  maar blijft als achtervang staan voor het randgeval van een sensor met
 *  minder dan [LONG_TERM_WINDOW_MINUTES] aan historie. */
private const val MIN_VOLATILITY_MGDL = 5.0

/** 29/08/2026 (editor, RONDE 162) — venster (minuten) voor de LANGERE-
 *  termijn-volatiliteit, zie klasse-kdoc punt 4. 180 minuten (3 uur) is
 *  lang genoeg om een net doorgemaakte stijging/daling/omslagpunt mee te
 *  wegen (zodat een toevallig rustige laatste 45 minuten niet tot een
 *  overmoedig smalle band leidt), maar niet zo lang dat het middernacht-
 *  gedrag van gisteren de band vanmiddag nog zou beïnvloeden. Zelfde
 *  regressie-/residue-berekening als de korte termijn ([HISTORY_WINDOW_MINUTES]),
 *  hier alleen voor de RESIDUE-spreiding gebruikt — niet voor de
 *  trend/helling (die blijft kort-termijn, zie punt 1-2 hierboven): over 3
 *  uur is de curve vaak niet meer lineair (bv. een hele maaltijdpiek erin),
 *  dus een helling daarover zou geen zinvolle "huidige richting" meer zijn
 *  — maar de residuen t.o.v. zo'n lange-termijn-lijn zijn wél een goede,
 *  stabiele maat voor "hoeveel beweegt dit signaal doorgaans".
 */
private const val LONG_TERM_WINDOW_MINUTES = 180L

/** 29/08/2026 (editor, RONDE 162) — hoeveel van de langere-termijn-residue
 *  meetelt in de uiteindelijke volatiliteit (zie [computeGlucosePrediction]).
 *  Bepaald via een backtest tegen een echte FCLvNext-log
 *  (`FCLvNext_Log_v11.csv`, 22-29 aug): op 7 representatieve momenten in een
 *  echte stijg/daal-cyclus (dal, begin stijging, halverwege omhoog, tegen de
 *  top, net na de top, halverwege omlaag, tegen het volgende dal) is voor
 *  elk moment met UITSLUITEND de bestaande [HISTORY_WINDOW_MINUTES]-
 *  volatiliteit nagerekend hoe vaak de daadwerkelijke latere meting
 *  binnen de voorspelde band viel: slechts ~19% (16 van de 83
 *  gecontroleerde tijdstippen). Een raster over combinaties van dit gewicht,
 *  [MARGIN_SCALE] en [MIN_VOLATILITY_MGDL] liet een duidelijke afweging
 *  zien tussen dekking en bandbreedte (gewicht 1,0 gaf >95% dekking maar met
 *  een band zo breed als vóór RONDE 161 — exact het "100% zekerheid"-euvel
 *  dat toen juist opgelost moest worden). Gewicht 0,6 (samen met
 *  [MARGIN_SCALE]=4,5) bleek een redelijk evenwicht: ~58% dekking op
 *  hetzelfde testcijfer, met een bandbreedte op 60 minuten van gemiddeld
 *  ~5,7 mmol/L (i.p.v. ~2,4 mmol/L bij gewicht 0 óf ~11 mmol/L bij gewicht
 *  1,0). De resterende misses zaten vrijwel allemaal op de twee momenten
 *  waar een maaltijdstijging vanuit een écht vlakke basislijn BEGON — dat is
 *  inherent niet uit BG-historie alleen te voorspellen (geen enkel
 *  trend-model, hoe breed de band ook wordt, kan een nog-niet-begonnen
 *  maaltijd zien aankomen zonder IOB/maaltijd-info), dus verder verbreden
 *  om ook dát te vangen zou de band voor de rest van de tijd onnodig breed
 *  maken voor een fundamentele beperking die toch niet oplosbaar is. */
private const val LONG_TERM_VOLATILITY_WEIGHT = 0.6

/** 29/08/2026 (editor, RONDE 161) — tijdconstante (minuten) voor het
 *  "afvlakken" van de centrale trendlijn, zie klasse-kdoc punt 2. Bij t=tau
 *  is de cumulatieve verplaatsing al ~63% van de uiteindelijke asymptoot
 *  (`slope * tau`), bij t=3·tau (60 min bij tau=20) ~95% — dus de lijn is
 *  tegen het einde van het uur al duidelijk merkbaar aan het afvlakken,
 *  zonder de eerste ~10-15 minuten (t << tau) noemenswaardig anders te laten
 *  verlopen dan de oude, rechte extrapolatie. 20 minuten is een eerste,
 *  beargumenteerde keuze — hier bij te stellen als de bocht in de praktijk
 *  te vroeg/laat aanvoelt. */
private const val FLATTENING_TAU_MINUTES = 20.0

/** 29/08/2026 (editor, RONDE 161, live-melding — zie klasse-kdoc punt 3)
 *  — marge-schaalfactor op de LINEAIRE t-groei: de marge op t=60 minuten is
 *  `volatility * MARGIN_SCALE` (bij t=15 dus een kwart daarvan, enzovoort).
 *  29/08/2026 (editor, RONDE 162) — opnieuw bijgesteld (5,5 → 4,5) als
 *  onderdeel van dezelfde backtest-afweging als
 *  [LONG_TERM_VOLATILITY_WEIGHT] hierboven — nu de volatiliteit zelf vaker
 *  (via de langere-termijn-component) groter uitvalt, houdt een iets lagere
 *  schaalfactor de resulterende bandbreedte in een redelijke orde van
 *  grootte in plaats van dat beide effecten zich stapelen. */
private const val MARGIN_SCALE = 4.5

/** Fysiologische veiligheidsgrenzen — puur grafisch, om te voorkomen dat
 *  een korte, steile recente trend (bv. tijdens een snelle correctie) de
 *  lijn buiten elk plausibel bereik laat doorschieten; geen medische
 *  claim, alleen een clamp op de getekende waarden. */
private const val CLAMP_MIN_MGDL = 20.0
private const val CLAMP_MAX_MGDL = 500.0

/** Uitkomst van een lineaire regressie over een venster: de helling
 *  (mg/dL per minuut) en de standaarddeviatie van de residuen t.o.v. die
 *  lijn (mg/dL) — de laatste is de "hoe grillig was dit venster"-maat die
 *  zowel [HISTORY_WINDOW_MINUTES] als [LONG_TERM_WINDOW_MINUTES] hieronder
 *  op dezelfde manier berekenen (zie [computeGlucosePrediction]). */
private data class RegressionResult(val slopePerMinute: Double, val residualStdDevMgdl: Double)

/**
 * Lineaire regressie (kleinste-kwadraten) over alle punten in [sorted] die
 * niet ouder zijn dan [windowMinutes] vóór [anchorMs], met tijd uitgedrukt
 * in minuten t.o.v. [anchorMs] (negatief voor oudere punten). `null` als er
 * minder dan [MIN_HISTORY_POINTS] punten in dat venster vallen.
 */
private fun regressionOverWindow(
    sorted: List<GlucoseReading>,
    windowMinutes: Long,
    anchorMs: Long
): RegressionResult? {
    val windowStartMs = anchorMs - windowMinutes * 60_000L
    val window = sorted.filter { it.timestampMs in windowStartMs..anchorMs }
    if (window.size < MIN_HISTORY_POINTS) return null

    val xs = window.map { (it.timestampMs - anchorMs) / 60_000.0 }
    val ys = window.map { it.glucoseMgdl }
    val n = xs.size
    val meanX = xs.average()
    val meanY = ys.average()
    var sumXY = 0.0
    var sumXX = 0.0
    for (i in 0 until n) {
        val dx = xs[i] - meanX
        sumXY += dx * (ys[i] - meanY)
        sumXX += dx * dx
    }
    // sumXX kan alleen 0 zijn als alle punten op EXACT hetzelfde tijdstip
    // liggen (praktisch onmogelijk bij >=4 verschillende metingen) —
    // defensief toch afgevangen i.p.v. een deling-door-nul te riskeren.
    val slopePerMinute = if (sumXX > 0.0) sumXY / sumXX else 0.0

    var sumSquaredResiduals = 0.0
    for (i in 0 until n) {
        val predicted = meanY + slopePerMinute * (xs[i] - meanX)
        val residual = ys[i] - predicted
        sumSquaredResiduals += residual * residual
    }
    val residualStdDev = sqrt(sumSquaredResiduals / n)
    return RegressionResult(slopePerMinute, residualStdDev)
}

/**
 * Berekent de voorspellingsband voor het komende uur, of `null` als er te
 * weinig recente data is om een zinvolle uitspraak te doen.
 *
 * @param readings alle beschikbare metingen (mag méér dan de interne
 *   historie-vensters bevatten — deze functie filtert zelf op
 *   [HISTORY_WINDOW_MINUTES]/[LONG_TERM_WINDOW_MINUTES] vóór de laatste
 *   meting). Wordt hier defensief op tijd gesorteerd, ook al garanderen
 *   [GlucoseReadingStore]'s queries dat al (ASC) — zelfde defensieve
 *   aanpak als elders in de codebase (zie fingerstickEntries's `sortedBy`
 *   in GlucoseChart.kt).
 */
fun computeGlucosePrediction(readings: List<GlucoseReading>): List<GlucosePredictionPoint>? {
    val sorted = readings.sortedBy { it.timestampMs }
    if (sorted.size < MIN_HISTORY_POINTS) return null

    val lastReading = sorted.last()
    val shortTerm = regressionOverWindow(sorted, HISTORY_WINDOW_MINUTES, lastReading.timestampMs) ?: return null
    // 29/08/2026 (editor, RONDE 162) — zie klasse-kdoc punt 4 en de kdoc bij
    // [LONG_TERM_VOLATILITY_WEIGHT]: de helling/trend blijft UITSLUITEND
    // kort-termijn (shortTerm hierboven); alleen de residue-spreiding van dit
    // langere venster telt mee, en dan ook maar gedeeltelijk (het gewicht).
    // `null` (bv. sensor nog geen 3 uur actief) is prima — dan valt deze
    // term simpelweg weg via `?: 0.0`, precies zoals de RONDE-160-vloer dat
    // al deed vóór er een langere-termijn-component bestond.
    val longTerm = regressionOverWindow(sorted, LONG_TERM_WINDOW_MINUTES, lastReading.timestampMs)
    val longTermComponent = (longTerm?.residualStdDevMgdl ?: 0.0) * LONG_TERM_VOLATILITY_WEIGHT
    val volatility = maxOf(shortTerm.residualStdDevMgdl, longTermComponent, MIN_VOLATILITY_MGDL)
    val slopePerMinute = shortTerm.slopePerMinute

    val startMgdl = lastReading.glucoseMgdl
    val steps = (PREDICTION_HORIZON_MINUTES / PREDICTION_STEP_MINUTES).toInt()
    return (0..steps).map { step ->
        val t = step * PREDICTION_STEP_MINUTES
        // 29/08/2026 (editor, RONDE 161) — zie klasse-kdoc punt 2: de
        // cumulatieve verplaatsing vlakt exponentieel af i.p.v. de helling
        // de volle 60 minuten ongewijzigd door te trekken.
        val tDouble = t.toDouble()
        val decayedDisplacement = FLATTENING_TAU_MINUTES * (1.0 - exp(-tDouble / FLATTENING_TAU_MINUTES))
        val center = (startMgdl + slopePerMinute * decayedDisplacement).coerceIn(CLAMP_MIN_MGDL, CLAMP_MAX_MGDL)
        // 29/08/2026 (editor, RONDE 161) — zie klasse-kdoc punt 3: lineair in
        // t i.p.v. √t.
        val margin = volatility * MARGIN_SCALE * (tDouble / PREDICTION_HORIZON_MINUTES.toDouble())
        GlucosePredictionPoint(
            minutesFromNow = t,
            lowerMgdl = (center - margin).coerceIn(CLAMP_MIN_MGDL, CLAMP_MAX_MGDL).toFloat(),
            upperMgdl = (center + margin).coerceIn(CLAMP_MIN_MGDL, CLAMP_MAX_MGDL).toFloat()
        )
    }
}
