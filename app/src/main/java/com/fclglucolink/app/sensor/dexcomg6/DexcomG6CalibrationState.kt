package com.fclglucolink.app.sensor.dexcomg6

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6 sensor-/kalibratiestatus (uit de transmitter zelf)
 * ============================================================================
 *
 * 09/08/2026 (editor, RONDE 66, op verzoek — "kijk nog eens na [...] welke
 * gegevens xdrip uit de transmitter haalde") — geport van xDrip+'s
 * `g5model/CalibrationState.java` (volledige tabel, letterlijk overgenomen
 * incl. de numerieke codes). Dit is het byte dat elk glucose-antwoord al
 * meestuurt (`DexcomG6Protocol.GlucoseRx.stateRaw`, gelezen in
 * `parseGlucose()`/`parseEGlucose()`) — tot ronde 66 werd dat veld wel
 * doorgegeven maar NERGENS geïnterpreteerd. Dit is het ENIGE betrouwbare,
 * door de transmitter zelf gerapporteerde signaal voor "is deze sensor nog
 * aan het opwarmen, actief, verlopen, of mislukt" — in tegenstelling tot een
 * lokaal aangenomen tijdsduur (zie DexcomG6StatusScreen.kt's
 * `dexcomG6StatusText()`), werkt dit voor ELKE transmitter-variant
 * (standaard G6, G6+, een getweakte Anubis) zonder enige aanname over hoe
 * lang de opwarmtijd of sensor-levensduur precies is.
 *
 * Bewust een `enum class` met een `value: Int` + lookup-tabel i.p.v. een
 * `when` op de ruwe byte overal verspreid — zelfde reden als xDrip+'s eigen
 * aanpak: één centrale plek die weet welke ruwe waarde bij welke betekenis
 * hoort, en een aantal kant-en-klare predicaten (`warmingUp()`, `ok()`,
 * `sensorFailed()`, `sensorStarted()`) die de aanroepers (DexcomG6Driver.kt,
 * DexcomG6StatusScreen.kt) kunnen gebruiken zonder zelf ruwe getallen te
 * hoeven vergelijken.
 */
enum class DexcomG6CalibrationState(val value: Int) {
    Unknown(0x00),
    Stopped(0x01),
    WarmingUp(0x02),
    ExcessNoise(0x03),
    NeedsFirstCalibration(0x04),
    NeedsSecondCalibration(0x05),
    Ok(0x06),
    NeedsCalibration(0x07),
    CalibrationConfused1(0x08),
    CalibrationConfused2(0x09),
    NeedsDifferentCalibration(0x0a),
    SensorFailed(0x0b),
    SensorFailed2(0x0c),
    UnusualCalibration(0x0d),
    InsufficientCalibration(0x0e),
    Ended(0x0f),
    SensorFailed3(0x10),
    TransmitterProblem(0x11),
    Errors(0x12),
    SensorFailed4(0x13),
    SensorFailed5(0x14),
    SensorFailed6(0x15),
    SensorFailedStart(0x16),
    SensorFailedStart2(0x17),
    SensorExpired(0x18),
    SensorFailed7(0x19),
    SensorStopped2(0x1A),
    SensorFailed8(0x1B),
    SensorFailed9(0x1C),
    SensorFailed10(0x1D),
    SensorFailed11(0x1E),
    SensorStarted(0xC1),
    SensorStopped(0xC2),
    CalibrationSent(0xC3);

    companion object {
        private val byValue = entries.associateBy { it.value }

        /** Onbekende/toekomstige codes vallen terug op [Unknown] i.p.v. een
         *  crash — mirror van xDrip+'s eigen `parse()`-gedrag. */
        fun fromRaw(raw: Int): DexcomG6CalibrationState = byValue[raw] ?: Unknown

        private val failedStates = setOf(
            SensorFailed, SensorFailed2, SensorFailed3, SensorFailed4, SensorFailed5,
            SensorFailed6, SensorFailed7, SensorFailed8, SensorFailed9, SensorFailed10,
            SensorFailed11, SensorFailedStart, SensorFailedStart2
        )
        private val stoppedStates = setOf(
            Stopped, Ended, SensorExpired, SensorStopped, SensorStopped2
        ) + failedStates
    }

    fun warmingUp(): Boolean = this == WarmingUp
    fun ok(): Boolean = this == Ok
    fun warmUpOrOkay(): Boolean = this == WarmingUp || this == Ok
    fun sensorFailed(): Boolean = this in failedStates
    /** true zolang de sensor niet expliciet gestopt/verlopen/mislukt is —
     *  mirror van xDrip+'s `sensorStarted()` (dubbele ontkenning: "niet in
     *  de stopped-verzameling"). */
    fun sensorStarted(): Boolean = this !in stoppedStates

    /**
     * 09/08/2026 (editor, RONDE 69, CRITICAL — na live-test: BG-waarde bleef
     * op 0,3 mmol/L "hangen" met een erratisch verloop, geen echte hypo) —
     * mirror van xDrip+'s `CalibrationState.usableGlucose()`. Het cruciale,
     * tot deze ronde ONTBREKENDE inzicht (rechtstreeks nagekeken in xDrip+'s
     * `BaseGlucoseRxMessage.usable()`/`processGlucoseRxMessage()`): de
     * transmitter stuurt bij VRIJWEL ELK glucose-antwoord een getal mee in
     * het glucoseveld, ook tijdens toestanden als WarmingUp,
     * NeedsFirstCalibration, of een van de Failed-varianten — maar dat getal
     * is dan GEEN echte meetwaarde, vaak letterlijk een intern statuscode-
     * getal (Dexcom's protocol gebruikt zeer lage mg/dL-waarden als 1, 2, 3,
     * 5, 6, 9, 10, 12 of 13 als gereserveerde foutcodes, precies het bereik
     * dat "0,3 mmol/L" — 0,3 × 18,0182 ≈ 5,4 mg/dL — verklaart). xDrip+
     * slaat daarom NOOIT een BgReading op tenzij de staat exact `Ok` of
     * `NeedsCalibration` is (zie DexcomG6Driver.kt's `handleGlucoseResult()`
     * voor de nieuwe gate die dit toepast). */
    fun usableGlucose(): Boolean = this == Ok || this == NeedsCalibration

    /** mirror van xDrip+'s `insufficientCalibration()` — in xDrip+ zelf ook
     *  standaard als bruikbaar behandeld (voorkeur "ob1_g5_use_
     *  insufficiently_calibrated", default AAN) — hier bewust hetzelfde
     *  gedrag zonder aparte instelling, zie DexcomG6Driver.kt. */
    fun insufficientCalibration(): Boolean = this == InsufficientCalibration

    /** Korte, gebruikersgerichte tekst voor de statusregel — alleen voor de
     *  toestanden die de UI daadwerkelijk apart wil tonen (zie
     *  dexcomG6StatusText() in DexcomG6StatusScreen.kt); overige waarden
     *  laat de aanroeper bewust onaangeroerd (die vallen terug op
     *  "Last connected …" — een ietwat cryptische kalibratie-tussenstatus
     *  is minder nuttig dan gewoon te laten zien dat de verbinding werkt).
     */
    fun shortUserText(): String? = when (this) {
        WarmingUp -> "Warming up"
        SensorExpired -> "Sensor expired"
        Ended -> "Sensor ended"
        Stopped, SensorStopped, SensorStopped2 -> "Sensor stopped"
        else -> if (sensorFailed()) "Sensor failed — replace sensor" else null
    }
}

/**
 * 09/08/2026 (editor, RONDE 74, op verzoek — "als die [warmupSeconds] niet
 * uit de transmitter komt dan moet hij bij een anubis gewoon 30 minuten
 * pakken en anders 1 uur [...] de waarden mogen pas getoond worden resp. 30
 * en 60 minuten nadat de sensor is gestart") — dit type-onderscheid bestond
 * al als losse, inline `when`-blokken in zowel DexcomG6StatusScreen.kt
 * (voor het "Type"-label) als potentieel elders — hier ÉÉN centrale,
 * herbruikbare plek voor zowel de Anubis/Original-classificatie (zelfde
 * 15-dagen-heuristiek als ronde 67: een gemodificeerde/Anubis-achtige
 * transmitter rapporteert een `typicalSensorDays` ruim boven een originele
 * G6's 10 dagen) als de bijbehorende fallback-opwarmtijd.
 *
 * BELANGRIJK — dit is uitdrukkelijk een gebruikerskeuze, geen vaste
 * fysieke waarheid: xDrip+'s eigen documentatie noemt voor stock G6 een
 * standaard opwarmtijd van 2 uur en voor Anubis-achtige mods vaak ~50
 * minuten — de gebruiker heeft hier bewust gekozen voor kortere, eigen
 * waarden (30/60 min) omdat de transmitter in de praktijk al veel eerder
 * bruikbare data lijkt te leveren en de langere officiële tijden
 * vermoedelijk een conservatieve veiligheidsmarge zijn i.p.v. een harde
 * technische ondergrens. Wordt UITSLUITEND gebruikt als de transmitter
 * zelf GEEN bruikbare `warmupSeconds` teruggeeft (zie
 * DexcomG6Driver.kt's `handleGlucoseResult()` en
 * DexcomG6StatusScreen.kt's `dexcomG6StatusText()`/warmupText — een
 * daadwerkelijk door de transmitter opgegeven waarde heeft altijd
 * voorrang, dit is puur het vangnet voor wanneer die informatie er niet
 * is, zoals bij deze specifieke Anubis-transmitter (zie ronde 71's kdoc:
 * `warmupSeconds` blijft voor deze hardware structureel `null`).
 */
enum class DexcomG6TransmitterType {
    ANUBIS, ORIGINAL;

    companion object {
        /** Zelfde 15-dagen-drempel als ronde 67's `typeText`-berekening in
         *  DexcomG6StatusScreen.kt (stock G6: 10 dagen, Anubis: doorgaans
         *  tot 60 dagen). `null` (nog onbekend) blijft `null`. */
        fun fromTypicalSensorDays(typicalSensorDays: Int?): DexcomG6TransmitterType? = when {
            typicalSensorDays == null -> null
            typicalSensorDays > 15 -> ANUBIS
            else -> ORIGINAL
        }
    }

    /** De gebruiker-gekozen fallback-opwarmtijd in seconden — zie deze
     *  klasse's kdoc voor de achtergrond. */
    val fallbackWarmupSeconds: Int
        get() = when (this) {
            ANUBIS -> 30 * 60
            ORIGINAL -> 60 * 60
        }
}

/** Kortere, direct bruikbare ingang voor aanroepers die alleen de
 *  fallback-opwarmtijd nodig hebben (zie [DexcomG6TransmitterType]'s kdoc)
 *  — `null` als het transmitter-type nog niet bekend is (dan is er ook
 *  geen zinnige fallback te bepalen). */
fun dexcomG6FallbackWarmupSeconds(typicalSensorDays: Int?): Int? =
    DexcomG6TransmitterType.fromTypicalSensorDays(typicalSensorDays)?.fallbackWarmupSeconds
