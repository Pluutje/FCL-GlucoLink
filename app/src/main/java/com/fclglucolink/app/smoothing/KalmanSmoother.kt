package com.fclglucolink.app.smoothing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================
 * FCLGlucoLink — real-time glucose smoothing (ronde 49)
 * ============================================================================
 *
 * 06/08/2026 (editor, RONDE 49 — op verzoek, "nu graag de smoothing bouwen",
 * na de eerder besproken en bevestigde volgorde "eerst kalibratie, dan
 * smoothing") — geïnspireerd op/overgenomen van de door de gebruiker
 * aangeleverde AAPS-bron `UnscentedKalmanFilterPlugin.kt`: hetzelfde
 * fysische model (toestand = [glucose, snelheid], vaste procesruis Q,
 * adaptieve meetruis R met robuuste, getrimde-gemiddelde-schatting,
 * chi-kwadraat-gebaseerde uitschieter-detectie, 2-van-3-teken-poort +
 * tijdelijke Q-opblazing om echte trends (maaltijden/insuline) niet te laten
 * vertragen), maar bewust ANDERS georganiseerd dan het origineel op twee
 * punten:
 *
 * 1) GEEN Unscented-sigma-punten-machinerie. AAPS's procesmodel
 *    (glucose_{t+1} = glucose_t + snelheid_t·dt, snelheid_{t+1} =
 *    snelheid_t·demping) ÉN het meetmodel (h(x) = glucose) zijn allebei
 *    LINEAIR in de toestand — de Unscented Transform is wiskundig bewezen
 *    exact gelijk aan een gewoon lineair Kalman-filter voor een lineair
 *    systeem (dat is precies de reden dat een UKF voor een lineair systeem
 *    nooit nauwkeuriger kan zijn dan een gewoon KF: sigma-punten voegen daar
 *    niets aan toe, alleen rekenwerk). Deze klasse berekent dezelfde
 *    voorspelling/update dus rechtstreeks met de gesloten-vorm F·P·Fᵀ+Q /
 *    Kalman-winst-formules — identieke uitkomst, veel minder code.
 *
 * 2) GEEN batch-herverwerking met terugwaartse RTS-smoothing. AAPS's
 *    `smooth()` verwerkt telkens een heel VENSTER van historische metingen
 *    opnieuw (voor een mooiere grafiek van het RECENTE VERLEDEN) — maar de
 *    ACTUEEL weergegeven/uitgezonden waarde (het nieuwste punt in dat
 *    venster) wordt door die terugwaartse pas NOOIT aangeraakt (zie AAPS's
 *    eigen `smoothedResults[0]`, die na de terugwaartse lus gelijk blijft
 *    aan `forwardResults[0]` — de lus loopt daar bewust pas vanaf index 1).
 *    Die terugwaartse pas is dus puur cosmetisch voor OUDERE punten, niet
 *    relevant voor de live waarde die deze app nodig heeft. FCLGlucoLink
 *    verwerkt sowieso al één meting tegelijk (zie BleConnectionService.kt's
 *    `driver.readings.collect{}`), dus deze klasse is een zuiver
 *    VOORWAARTS, per-meting bijgewerkt filter — precies wat er voor de live
 *    waarde toe doet, zonder de overhead van een steeds opnieuw herverwerkt
 *    venster.
 *
 * Sessiebeheer (wanneer het geleerde R en de toestand gereset moeten worden)
 * gebruikt hier NIET AAPS's therapy-event-observatie (deze app heeft geen
 * "therapy events"-database) maar wordt van BUITENAF aangeroepen via
 * [reset], door BleConnectionService.kt, op EXACT hetzelfde moment als
 * waarop de kalibratiegeschiedenis geleegd wordt (zie de kdoc daar, ronde
 * 46's device-adres-vergelijking) — een echt nieuwe fysieke sensor, niet
 * zomaar een app-/service-herstart. Deze toestand hoeft zelf geen
 * app-herstart te overleven (in tegenstelling tot kalibratie-entries, die
 * WEL persistente, door de gebruiker ingevoerde klinische data zijn): een
 * vers gestart filter convergeert vanzelf binnen enkele metingen, dus een
 * incidentele reset bij een herstart (deze klasse leeft gewoon als een
 * gewoon veld op BleConnectionService, dat toch al bij elke herstart
 * opnieuw aangemaakt wordt) is onschuldig — heel iets anders dan
 * kalibratie-entries die de gebruiker zelf handmatig heeft ingevoerd en die
 * dus NOOIT stilzwijgend mogen verdwijnen (zie ronde 46's bugfix).
 *
 * 15/08/2026 (editor, RONDE 109, op verzoek: "Iemand gaf aan dat de adaptive
 * smoothing een hele goede optie was [...] als er voordelen te behalen zijn
 * (belangrijk voordeel is snellere stijgingsdetectie zonder gelijk meer
 * ruis te krijgen) zou het een optie zijn om die over te nemen" — na
 * vergelijking met AIMI's `AdaptiveSmoothingPlugin.kt`) — ÉÉN mechanisme
 * daaruit is overgenomen: naast de bestaande 2-van-3-teken-poort hierboven
 * (die WACHT op 2 van de laatste 3 metingen die in dezelfde richting >2σ
 * afwijken voor 'ie de procesruis opblaast) is er nu ook een ONMIDDELLIJKE
 * trigger die op één enkele meting al reageert zodra die meer dan
 * [immediateTriggerThreshold] (2,5σ) van de voorspelling afwijkt — dan
 * wordt de procesruis in één klap veel forser opgeblazen (snelheid
 * ×[immediateQRateScale]=50, glucose ×[immediateQGlucoseScale]=2) zodat de
 * Kalman-winst die ene afwijkende meting bijna volledig overneemt i.p.v.
 * 'm te dempen. Doorgerekend (200 ruisrealisaties, abrupte-instap-scenario
 * + een aparte ruis-only Monte Carlo-test, zie het gesprek voor de volledige
 * cijfers) vóór het bouwen: bij een geleidelijke stijging (opbouw over
 * 10+ minuten) maakt dit vrijwel niets uit — de gewone Kalman-winst vangt
 * dat al soepel op — maar bij een echt abrupte knik binnen één 5-minuten-
 * sample scheelt het ~1 minuut eerdere detectie én ~0,3-0,4 mg/dL minder
 * overshoot rond de piek, ZONDER de uitvoer merkbaar ruiziger te maken bij
 * normale sensorruis (0% valse triggers, identieke snelheids-RMSE).
 *
 * BEWUST SYMMETRISCH (in tegenstelling tot AIMI's origineel, dat deze
 * trigger alleen bij STIJGINGEN gebruikt en dalingen via een aparte,
 * dosis-gerelateerde "kinetic hypo"-noodrem afhandelt): AIMI's asymmetrie
 * bestaat om te voorkomen dat een te snel reagerend filter een AAPS-
 * doseeralgoritme bij een daling laat overdoseren — die reden geldt hier
 * niet, FCLGlucoLink doseert zelf niets, het geeft alleen de waarde door.
 * Symmetrisch scoorde in de doorrekening op alle metingen gelijk-aan-of-
 * beter-dan de asymmetrische variant — onder andere doordat een snellere,
 * accuratere afvlakking rond de top (die zich toont als een NEGATIEVE
 * afwijking t.o.v. de nog-stijgende voorspelling, en dus door een
 * stijging-only-trigger genegeerd zou worden) zo ook eerder wordt herkend.
 *
 * R-leren wordt tijdens deze trigger net als bij de bestaande poort
 * overgeslagen (zie [skipRUpdate] in [smooth]) — een eenmalige, correct
 * geabsorbeerde uitschieter mag niet blijvend doorwerken in "de sensor is
 * nu structureel ruizig".
 *
 * 16/08/2026 (editor, RONDE 111, op verzoek — n.a.v. community-meldingen
 * dat CareSens Air de eerste dag(en) "springerig" kan zijn en de trend
 * "nogal dramatisch" kan doen, waardoor AndroidAPS/FCLvNext soms heftig
 * reageert: "wat ik met name wil voorkomen is dat ruisgevoelige stijgingen
 * het doseeralgoritme onterecht triggert [...] dalingen zijn in mijn ogen
 * dus minder van belang [...] een aan/uit knop en [...] een aantal dagen
 * (of uren) instelling [...] het hoeft niet sensor afhankelijk [...] een
 * aflopende (lineair of logaritmische) correctie [...] de calibratie kant
 * moet erbuiten blijven") — een OPTIONELE, tijdelijke extra demping vlak
 * na een sensorstart, exponentieel afbouwend naar het bestaande (ongewijzigde)
 * gedrag.
 *
 * Bewust ASYMMETRISCH (het enige asymmetrische mechanisme in deze klasse —
 * alle andere hierboven zijn expliciet symmetrisch gehouden, zie RONDE 109):
 * alleen STIJGENDE afwijkingen (normRaw > 0) worden gedempt, dalingen lopen
 * altijd via het normale (ongedempte) pad. Reden: het doel is niet "een
 * springerige sensor gladstrijken" in het algemeen, maar specifiek
 * voorkomen dat een ruisgevoelige stijging een SMB/bolus-beslissing bij
 * OpenAPSSMB of FCLvNext onterecht triggert — een gemiste of vertraagde
 * DALING is voor die doseerbeslissing zelf niet het probleem (zie het
 * gesprek: FCLvNext stopt toch al met doseren zodra een échte daling
 * inzet), dus daar is geen reden om de normale, al doorgerekende
 * responsiviteit (RONDE 109) op te offeren.
 *
 * Twee, aan elkaar gekoppelde ingrepen (zie [breakInExtraRMgdlSq]/
 * [breakInThresholdBoost] in [smooth]), beide alleen bij een stijgende
 * afwijking en beide geschaald met [breakInDecayFactor]:
 * 1. Extra meetruis bovenop de bestaande Huber-achtige rEff-opblazing —
 *    dempt de GEWONE Kalman-winst, dus ook geleidelijke/2-van-3-bevestigde
 *    stijgingen.
 * 2. Een hogere drempel voor de RONDE-109-trigger — die reageert normaal al
 *    op één enkele meting; tijdens de inloopperiode moet een stijging veel
 *    hardnekkiger zijn voordat die trigger 'm gelooft. (Alleen extra R zou
 *    hier niet genoeg zijn: die trigger werkt via een sterk opgeblazen
 *    procesruis Q, niet via R, dus een gematigde R-ophoging zou de
 *    resulterende Kalman-winst nauwelijks temperen.)
 *
 * [breakInDecayFactor] zelf (0..1) wordt BUITEN deze klasse berekend
 * (BleConnectionService.kt) uit de tijd sinds de sensor-startsleutel (per
 * slot, sensortype-specifiek waar beschikbaar — CareSens Air/Dexcom G6
 * hebben elk hun eigen, al bestaande "sessie gestart op"-tijdstip) en de
 * ingestelde duur (AppSettings.breakInFilterDurationHours, standaard 24u):
 * exponentieel, τ = duur / 5, dus na precies de ingestelde duur is nog maar
 * ~0,7% van de correctie over — voor de gebruiker niet te onderscheiden van
 * "helemaal uitgewerkt" (letterlijk verzoek: "een instelling van 24 uur
 * betekent dat het na 24 uur volledig is uitgewerkt"), terwijl de afbouw
 * zelf toch vloeiend blijft, geen knik op het afbouwmoment. Bewust GLOBAAL,
 * niet per sensortype (letterlijk verzoek: "in principe heeft iedere
 * sensor er last van [...] het hoeft niet sensor afhankelijk") — deze
 * klasse zelf weet niets van instellingen of sensortype, precies zoals
 * [AlarmSoundPlayer.start]'s `alertMode`-parameter: de instelling wordt
 * buiten gelezen, hier komt alleen het kale, al-berekende getal binnen.
 *
 * BEWUST BUITEN SCOPE: de kalibratiekant (CalibrationEngine/
 * SplineCalibrationMath) blijft ongemoeid — letterlijk verzoek ("niet
 * iedereen zal calibratie gebruiken"), en calibratie heeft met
 * MIN_ENTRIES_FOR_SPLINE hoe dan ook al een eigen, deels overlappende
 * vroege-voorzichtigheid (spline lukt simpelweg niet met te weinig punten).
 *
 * 18/08/2026 (editor, RONDE 114, op verzoek: "wat we nu nog niet hebben is
 * een algemene filtering sterkte 3 keuze schakelaar [...] die dan indien
 * enable uitgeschakeld ook grijs wordt") — dit was tijdens Ronde 113's
 * gesprek nog een bewust NIET-geïmplementeerd, opengelaten idee (het
 * gesprek concludeerde toen dat er eerst zichtbaarheid van de pijplijn
 * nodig was voordat een sterkte-instelling zinvol te beoordelen zou zijn —
 * zie [SmoothingStrength] hieronder en StatusScreen.kt's PipelineValuesRow
 * voor die eerdere stap). Nu die zichtbaarheid er is, hier de sterkte-knop
 * zelf: [SmoothingStrength] schaalt de BASIS-procesruis Q (niet R) met een
 * vaste factor. Q (niet R) is bewust de gekozen hendel: Q staat voor hoeveel
 * de ONDERLIGGENDE toestand tussen metingen mag veranderen — hoger Q laat
 * het filter een nieuwe meting sneller geloven (minder gladstrijken), lager
 * Q dwingt het filter de toestand trager te laten meebewegen (meer
 * gladstrijken, meer vertraging). R aanpassen was bewust NIET gekozen: R's
 * ondergrens/bovengrens ([rMin]/[rMax]) en de chi-kwadraat-uitschieter-
 * drempel zijn onderling op elkaar afgestemd (zie [adaptMeasurementNoise]),
 * en RONDE 111's inloop-demping werkt zelf ook al via R — een tweede,
 * permanente R-schaalfactor zou daarmee kunnen interfereren. Een schaal op Q
 * is zuiver ORTHOGONAAL aan beide bestaande mechanismen: RONDE 109's
 * onmiddellijke trigger en de 2-van-3-teken-poort schalen zelf ook al
 * multiplicatief bovenop qGlucose/qRate (zie [smooth]), dus een extra
 * basis-schaal daaronder blijft consistent vermenigvuldigen zonder ergens
 * een aparte tak toe te voegen.
 *
 * Bewust NIET in de constructor gebakken (in tegenstelling tot hoe je een
 * "vaste sterkte-instelling" misschien zou verwachten): exact hetzelfde
 * patroon als [breakInDecayFactor] hierboven — de sterkte wordt BUITEN deze
 * klasse gelezen (BleConnectionService.kt, uit AppSettings.smoothingStrength)
 * en bij elke aanroep van [smooth] meegegeven, zodat een wijziging in
 * Settings direct op de eerstvolgende meting doorwerkt, zonder de sensor
 * opnieuw te hoeven koppelen of de service te herstarten.
 *
 * 24/08/2026 (editor, RONDE 125, op verzoek: "een breakout filter wat
 * eigenlijk precies omgekeerd werkt tov de breakin [...] boven op de basis
 * (ongeacht welke stand gekozen is) en even sterk als break in dus in
 * principe een omgekeerde kopie" — na CareSens Air-meldingen dat sensoren
 * de laatste dagen van hun looptijd weer instabiel worden) — [smooth] krijgt
 * er een tweede, gelijkwaardig parameter [breakOutDecayFactor] bij. Beide
 * factoren worden gecombineerd tot één `edgeStrength` (het maximum van de
 * twee — ze horen bij niet-overlappende delen van de looptijd, begin resp.
 * eind, dus er is nooit een reden om ze op te tellen) en delen vanaf daar
 * LETTERLIJK dezelfde twee ingrepen ([breakInExtraRMgdlSq]/
 * [breakInThresholdBoost]) als de bestaande inloop-demping — een spiegel-
 * kopie in tijd, geen los mechanisme met eigen constantes. Net als
 * [breakInDecayFactor] wordt [breakOutDecayFactor] zelf BUITEN deze klasse
 * berekend (BleConnectionService.kt's `computeBreakOutDecayFactor()`), nu
 * uit het aantal uren TOT een geschatte eind-datum i.p.v. uren SINDS de
 * start — zie die functie's kdoc voor hoe die einddatum per sensortype
 * bepaald wordt (vaste looptijd voor CareSens Air/stock-G6, een handmatig
 * ingestelde verwachte looptijd voor een G6 met Anubis-transmitter).
 *
 * Tweede uitbreiding uit hetzelfde verzoek, na doorvragen ("Nu, dit lezende
 * denk ik toch alleen beide op de stijging, en de dalingen als die verdacht
 * ogen"): naast stijgingen dempen beide edge-filters nu ook "verdachte"
 * dalingen. Bewust GEEN nieuwe, aparte grootte-drempel hiervoor uitgevonden
 * — "verdacht" hergebruikt de al bestaande 2-van-3-tekenbevestiging
 * ([sameSignCount]/[qInflateAllowed] in [smooth]): een dalende afwijking telt
 * als verdacht zolang de laatste 2 van 3 grote afwijkingen NIET ook een
 * bevestigde daling laten zien, en verliest die status zodra dat wél zo is.
 * Dat behoudt precies de veiligheidsafweging uit de klasse-kdoc hierboven
 * (een echte, aanhoudende daling mag nooit lang vertraagd worden — hooguit
 * de eerste, nog onbevestigde meting van een nieuwe daling krijgt de extra
 * R-demping/hogere trigger-drempel, niet de daaropvolgende bevestigende
 * metingen) terwijl een geïsoleerde ruis-uitschieter naar beneden tijdens
 * het inloop-/uitloop-venster wél gedempt wordt, net als bij stijgingen.
 */
enum class SmoothingStrength(val qScale: Double, val displayLabel: String) {
    WEAK(1.8, "Weak"),
    MEDIUM(1.0, "Medium"),
    STRONG(0.5, "Strong")
}

class KalmanSmoother {

    // ---- Vaste procesruis Q (mg/dL²), letterlijk overgenomen van AAPS. ----
    private val qGlucose = 1.0
    private val qRate = 0.35

    // ---- Adaptieve meetruis R (variantie, mg/dL²). ----
    private val rInit = 25.0
    private val rMin = 16.0
    private val rMax = 225.0
    private val rEffMax = 400.0
    private val innovationWindow = 18 // ≈90 minuten bij 5-minutentakt.

    // ---- Uitschieter-detectie (chi-kwadraat, 99,99%-betrouwbaarheid). ----
    private val chiSquaredThreshold = 15.13
    private val outlierAbsoluteMgdl = 65.0

    // ---- Covariantiegrenzen. ----
    private val maxGlucoseVariance = 400.0
    private val maxRateVariance = 4.0

    // ---- Validatie van geleerde parameters. ----
    private val innovationResetThreshold = 12.0
    private val innovationValidationSamples = 15

    // ---- Gat-afhandeling. ----
    private val minorGapMinutes = 7.0
    private val majorGapMinutes = 60.0
    private val rateDecayTauMinutes = 30.0
    private val rateClampMgdlPerMin = 4.0

    // ---- RONDE 109: onmiddellijke, symmetrische Q-trigger (zie klasse-kdoc). ----
    private val immediateTriggerThreshold = 2.5
    private val immediateQGlucoseScale = 2.0
    private val immediateQRateScale = 50.0

    // ---- RONDE 111: inloop-demping voor een nog niet gestabiliseerde
    // (net geplaatste) sensor — zie klasse-kdoc. Bij volledige sterkte
    // (edgeStrength = 1.0, vlak na sensorstart of vlak vóór het geschatte
    // einde) gelden beide constantes hieronder; ze schalen lineair mee naar
    // 0 af. RONDE 125 — hergebruikt ONGEWIJZIGD door de nieuwe uitloop-
    // demping (spiegelkopie in tijd) en uitgebreid van "alleen stijgingen"
    // naar "stijgingen + verdachte (nog onbevestigde) dalingen" — zie
    // klasse-kdoc's RONDE-125-paragraaf voor de precieze afweging.
    private val breakInExtraRMgdlSq = 80.0     // extra meetruis-variantie (mg/dL²) bovenop de normale rEff.
    private val breakInThresholdBoost = 2.0    // onmiddellijke-trigger-drempel wordt tot ×3 (1 + 2×1.0) hoger.

    private fun rateDamp(dtMinutes: Double): Double = exp(-dtMinutes / rateDecayTauMinutes)

    // ---- Toestand. ----
    private var initialized = false
    private var glucose = 0.0
    private var rate = 0.0
    private var pGlucose = 16.0
    private var pRate = 1.0
    private var pCross = 0.0
    private var learnedR = rInit
    private var lastTimestampMs = 0L

    private val innovationsSq = ArrayDeque<Double>()       // ν²/(P+R), genormaliseerd.
    private val rawInnovationsSq = ArrayDeque<Double>()    // ν², ruw.
    private val predVarHistory = ArrayDeque<Double>()      // P_pred[glucose]-geschiedenis.
    private val recentSigns = ArrayDeque<Int>()            // 2-van-3-teken-poort.

    data class SmoothingOutput(
        val glucoseMgdl: Double,
        val rateMgdlPerMin: Double,
        val wasOutlier: Boolean
    )

    /** Zie klasse-kdoc — aangeroepen door BleConnectionService.kt bij een
     *  daadwerkelijk NIEUWE fysieke sensor, niet bij een gewone herstart. */
    fun reset() {
        initialized = false
        glucose = 0.0
        rate = 0.0
        pGlucose = 16.0
        pRate = 1.0
        pCross = 0.0
        learnedR = rInit
        lastTimestampMs = 0L
        innovationsSq.clear()
        rawInnovationsSq.clear()
        predVarHistory.clear()
        recentSigns.clear()
    }

    /**
     * @param measurementMgdl de GEKALIBREERDE waarde (zie klasse-kdoc voor de
     *        "eerst kalibratie, dan smoothing"-volgorde) van deze meting.
     * @param breakInDecayFactor RONDE 111 — 0.0 (standaard, geen extra
     *        demping) tot 1.0 (net-nieuwe sensor, volle inloop-demping).
     *        Wordt BUITEN deze klasse berekend (BleConnectionService.kt, uit
     *        de sensor-startsleutel + de ingestelde duur — zie klasse-kdoc)
     *        zodat deze klasse zelf geen kennis van instellingen/klok nodig
     *        heeft. Van invloed op stijgingen en verdachte dalingen (zie
     *        klasse-kdoc's RONDE-125-paragraaf).
     * @param breakOutDecayFactor RONDE 125 — zelfde schaal/betekenis als
     *        [breakInDecayFactor] (0.0..1.0), maar dan voor de UITLOOP-
     *        demping richting een geschat einde van de sensor-looptijd.
     *        Wordt eveneens BUITEN deze klasse berekend
     *        (BleConnectionService.kt's `computeBreakOutDecayFactor()`) en
     *        met [breakInDecayFactor] gecombineerd tot één `edgeStrength`
     *        (zie klasse-kdoc) — beide delen dezelfde constantes en dezelfde
     *        stijging-plus-verdachte-daling-logica.
     * @param strength RONDE 114 — schaalt [qGlucose]/[qRate] (zie
     *        [SmoothingStrength]'s kdoc voor waarom Q i.p.v. R). Default
     *        [SmoothingStrength.MEDIUM] (schaal ×1.0) — exact het bestaande,
     *        ongewijzigde gedrag van vóór deze ronde, zodat een aanroeper die
     *        dit argument niet meegeeft geen enkele gedragswijziging ziet.
     */
    fun smooth(
        measurementMgdl: Double,
        timestampMs: Long,
        breakInDecayFactor: Double = 0.0,
        breakOutDecayFactor: Double = 0.0,
        strength: SmoothingStrength = SmoothingStrength.MEDIUM
    ): SmoothingOutput {
        val effQGlucose = qGlucose * strength.qScale
        val effQRate = qRate * strength.qScale
        if (!initialized) {
            glucose = measurementMgdl
            rate = 0.0
            pGlucose = 16.0
            pRate = 1.0
            pCross = 0.0
            lastTimestampMs = timestampMs
            initialized = true
            return SmoothingOutput(glucose, rate, wasOutlier = false)
        }

        val dtMinutes = (timestampMs - lastTimestampMs) / 60_000.0
        if (dtMinutes < 0.0 || dtMinutes > majorGapMinutes) {
            // Klok ging terug, of een groot gat (>1u) — geen zinnig
            // voorspelbaar traject meer, vers beginnen bij deze meting i.p.v.
            // een oude, inmiddels betekenisloze toestand te extrapoleren.
            reset()
            glucose = measurementMgdl
            initialized = true
            lastTimestampMs = timestampMs
            return SmoothingOutput(glucose, rate, wasOutlier = false)
        }
        lastTimestampMs = timestampMs

        pGlucose = pGlucose.coerceIn(0.1, maxGlucoseVariance)
        pRate = pRate.coerceIn(0.001, maxRateVariance)

        // Klein gat binnen dezelfde sessie (7-60 min) — demp de
        // snelheidscomponent extra vóór de voorspelling, zoals AAPS ook doet
        // bij het overbruggen van een gat binnen een segment.
        if (dtMinutes > minorGapMinutes) {
            rate *= rateDamp(dtMinutes)
        }

        val basePrediction = predict(dtMinutes, effQGlucose, effQRate)

        val innovationRaw = measurementMgdl - basePrediction.glucosePred
        val innovationVarianceRaw = basePrediction.pGlucosePred + learnedR
        val normRaw = innovationRaw / sqrt(innovationVarianceRaw)
        val absNorm = abs(normRaw)

        // 2-van-3-teken-poort — persistente afwijking in dezelfde richting
        // (>2σ) duidt op een ECHTE trend (maaltijd/insuline), niet ruis; dan
        // mag de procesruis tijdelijk omhoog zodat de snelheid sneller
        // mee-versnelt i.p.v. te blijven naijlen.
        val sign = when {
            normRaw > 0.0 -> 1
            normRaw < 0.0 -> -1
            else -> 0
        }
        if (recentSigns.size == 3) recentSigns.removeLast()
        recentSigns.addFirst(if (absNorm > 2.0) sign else 0)
        val sameSignCount = if (sign == 0) 0 else recentSigns.count { it == sign }
        val qInflateAllowed = sameSignCount >= 2

        // RONDE 111 — inloop-/uitloop-demping (zie klasse-kdoc): een
        // STIJGENDE afwijking wordt altijd meegeteld; een DALENDE afwijking
        // alleen zolang die nog "verdacht" is (RONDE 125 — nog niet door de
        // 2-van-3-poort hierboven bevestigd als aanhoudende trend). Zodra
        // sameSignCount voor een dalend teken >=2 haalt, is de daling
        // bevestigd en telt 'ie niet langer mee — zo wordt een échte,
        // aanhoudende daling nooit langer dan de eerste, nog onbevestigde
        // meting vertraagd.
        val risingDeviation = normRaw > 0.0
        val fallingDeviation = normRaw < 0.0
        val suspiciousFallingDeviation = fallingDeviation && !(sign == -1 && qInflateAllowed)
        val edgeTriggered = risingDeviation || suspiciousFallingDeviation
        // RONDE 125 — inloop- en uitloop-demping delen dezelfde sterkte-
        // schaal: het maximum van beide (nooit opgeteld, ze horen bij
        // niet-overlappende delen van de looptijd — zie klasse-kdoc).
        val edgeStrength = max(breakInDecayFactor, breakOutDecayFactor).coerceIn(0.0, 1.0)

        // RONDE 109 — onmiddellijke trigger: één enkele meting die al meer
        // dan [immediateTriggerThreshold] afwijkt hoeft niet op de 2-van-3-
        // bevestiging hierboven te wachten (zie klasse-kdoc voor de
        // doorgerekende cijfers). Symmetrisch — beide richtingen tellen mee.
        // RONDE 111/125: tijdens de inloop-/uitloopperiode ligt die drempel
        // voor stijgingen en verdachte dalingen tijdelijk hoger (tot ×3 bij
        // volle sterkte) — een ruisgevoelige sensor moet een veel grotere,
        // hardnekkigere afwijking laten zien voordat deze trigger 'm alsnog
        // gelooft.
        val immediateEffectiveThreshold = if (edgeTriggered) {
            immediateTriggerThreshold * (1.0 + breakInThresholdBoost * edgeStrength)
        } else {
            immediateTriggerThreshold
        }
        val immediateTrigger = absNorm > immediateEffectiveThreshold

        // Huber-achtige R-opblazing: een enkele uitschieter weegt minder mee
        // in de update, zonder 'm volledig te negeren (dat zou een echte
        // snelle daling/stijging kunnen missen).
        val rScale = 1.0 + max(0.0, absNorm - 2.0)
        var rEff = min(learnedR * rScale, min(learnedR + 100.0, rEffMax))
        // RONDE 111/125: extra, tijdelijke meetruis bovenop rEff — bij een
        // stijgende afwijking of een nog onbevestigde ("verdachte") daling —
        // zodat ook de gewone (niet-getriggerde) Kalman-winst minder ver
        // "meetrekt" op een ruisgevoelige afwijking.
        if (edgeTriggered) {
            rEff += breakInExtraRMgdlSq * edgeStrength
        }

        val zScore = absNorm.coerceAtLeast(1.0)
        val qScale = if (qInflateAllowed) zScore.coerceIn(1.0, 3.0) else 1.0
        val prediction = when {
            immediateTrigger -> predict(dtMinutes, effQGlucose * immediateQGlucoseScale, effQRate * immediateQRateScale)
            qScale > 1.0 -> predict(dtMinutes, effQGlucose * min(qScale, 2.0), effQRate * qScale)
            else -> basePrediction
        }

        val innovation = measurementMgdl - prediction.glucosePred
        val innovationVarianceEff = prediction.pGlucosePred + rEff
        val mahalanobisSq = (innovation * innovation) / innovationVarianceEff

        predVarHistory.addFirst(prediction.pGlucosePred)
        if (predVarHistory.size > innovationWindow) predVarHistory.removeLast()

        // --- Kalman-update (lineair, zie klasse-kdoc — meetmodel h(x)=glucose). ---
        val kGlucose = prediction.pGlucosePred / innovationVarianceEff
        val kRate = prediction.pCrossPred / innovationVarianceEff
        glucose = prediction.glucosePred + kGlucose * innovation
        rate = (prediction.ratePred + kRate * innovation).coerceIn(-rateClampMgdlPerMin, rateClampMgdlPerMin)
        pGlucose = max(prediction.pGlucosePred - kGlucose * innovationVarianceEff * kGlucose, 0.1)
        pCross = prediction.pCrossPred - kGlucose * innovationVarianceEff * kRate
        pRate = max(prediction.pRatePred - kRate * innovationVarianceEff * kRate, 0.001)

        innovationsSq.addFirst(mahalanobisSq)
        rawInnovationsSq.addFirst(innovation * innovation)
        if (innovationsSq.size > innovationWindow) innovationsSq.removeLast()
        if (rawInnovationsSq.size > innovationWindow) rawInnovationsSq.removeLast()

        // Leren van R onderbreken tijdens een echte trend of een extreme
        // uitschieter — anders leert R zelf de trend/uitschieter mee in
        // "de sensor is nu structureel ruizig", wat 'm nodeloos hoog zou
        // laten hangen na afloop.
        val skipRUpdate = immediateTrigger || qInflateAllowed || absNorm > 3.0
        if (!skipRUpdate) {
            learnedR = adaptMeasurementNoise(learnedR)
        }

        // Innovatie-gebaseerde validatie: als het gemiddeld genormaliseerde
        // residu structureel te hoog blijft, is R zelf waarschijnlijk
        // gecorrumpeerd — reset dan naar de veilige startwaarde i.p.v.
        // daarop door te blijven bouwen.
        if (innovationsSq.size >= innovationValidationSamples &&
            innovationsSq.average() > innovationResetThreshold
        ) {
            learnedR = rInit
            innovationsSq.clear()
            rawInnovationsSq.clear()
            predVarHistory.clear()
        }

        val wasOutlier = mahalanobisSq > chiSquaredThreshold || abs(innovation) > outlierAbsoluteMgdl
        return SmoothingOutput(glucose, rate, wasOutlier)
    }

    private data class PredictResult(
        val glucosePred: Double,
        val ratePred: Double,
        val pGlucosePred: Double,
        val pCrossPred: Double,
        val pRatePred: Double
    )

    /** Voorspelstap — gesloten-vorm F·P·Fᵀ+Q, zie klasse-kdoc voor waarom dit
     *  exact gelijk is aan AAPS's sigma-punten-versie voor dit lineaire model. */
    private fun predict(dtMinutes: Double, qG: Double, qR: Double): PredictResult {
        val damp = rateDamp(dtMinutes)
        val glucosePred = glucose + rate * dtMinutes
        val ratePred = rate * damp

        // F = [[1, dt], [0, damp]]; P_pred = F·P·Fᵀ + Q (alleen diagonaal Q,
        // zoals AAPS's eigen predict() ook doet — zie q[] daar).
        var pGlucosePred = pGlucose + 2 * dtMinutes * pCross + dtMinutes * dtMinutes * pRate
        var pCrossPred = damp * (pCross + dtMinutes * pRate)
        var pRatePred = damp * damp * pRate

        val qScale = dtMinutes / 5.0
        pGlucosePred += qG * qScale
        pRatePred += qR * qScale

        pGlucosePred = max(pGlucosePred, 0.1)
        pRatePred = max(pRatePred, 0.001)

        return PredictResult(glucosePred, ratePred, pGlucosePred, pCrossPred, pRatePred)
    }

    /**
     * Robuuste, getrimde-gemiddelde adaptieve R-schatting — letterlijk
     * overgenomen van AAPS's `adaptMeasurementNoise()` (zelfde asymmetrische
     * winsten en per-stap-klemmen om "ping-pong" te voorkomen).
     */
    private fun adaptMeasurementNoise(currentR: Double): Double {
        if (innovationsSq.size < 12 || predVarHistory.isEmpty()) return currentR

        fun trimmedMean(values: List<Double>, trim: Double = 0.20): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val k = (sorted.size * trim).toInt().coerceAtMost((sorted.size - 1) / 2)
            return sorted.subList(k, sorted.size - k).average()
        }

        val n = innovationsSq.size
        val meanRawSq = trimmedMean(rawInnovationsSq.take(n))
        val meanPredVar = trimmedMean(predVarHistory.take(n))

        val targetRRaw = (meanRawSq - meanPredVar).coerceAtLeast(rMin)
        val targetR = targetRRaw.coerceIn(rMin, rMax)

        val goingUp = targetR > currentR
        val gain = if (goingUp) 0.18 else 0.12
        val step = currentR + gain * (targetR - currentR)

        val upCap = if (goingUp) 1.20 else 1.00
        val downCap = if (goingUp) 1.00 else 0.90
        val clamped = step.coerceIn(currentR * downCap, currentR * upCap).coerceIn(rMin, rMax)

        val eta = 0.25
        return (1.0 - eta) * currentR + eta * clamped
    }
}
