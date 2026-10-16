package com.fclglucolink.app.broadcast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.TrendCalculator

/**
 * ============================================================================
 * FCLGlucoLink — xDrip-broadcast naar AAPS
 * ============================================================================
 *
 * 30/07/2026 (editor) — Kotlin-port van Juggluco's SendLikexDrip.java (zelfde
 * GPLv3-project waar FCLGlucoLink z'n sensor-koppelcode van afleidt). Zelfde
 * intent-actie/extra's als xDrip/Juggluco, dus AAPS's bestaande "xDrip"-
 * broncode-plugin herkent dit zonder enige AAPS-wijziging.
 *
 * BEWUST NIET instelbaar (geen instellingenscherm, geen handmatig in te
 * vullen pakketnaam). In plaats van een hardcoded AAPS-pakketnaam (die per
 * AAPS-build/-fork kan verschillen, en
 * dus op een dag stil zou kunnen breken) wordt, net als in Juggluco zelf,
 * dynamisch opgezocht welke geïnstalleerde apps daadwerkelijk een ontvanger
 * voor deze actie hebben geregistreerd (queryBroadcastReceivers) — dat is
 * altijd correct, ongeacht welke AAPS-variant editor draait, zonder dat er
 * ooit iets ingesteld hoeft te worden.
 */
object XDripBroadcaster {

    private const val TAG = "XDripBroadcaster"
    const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"

    private const val EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    private const val EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    private const val EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    private const val EXTRA_SENSOR_STARTED_AT = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
    private const val EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
    private const val EXTRA_DATA_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"

    // 05/08/2026 (editor, RONDE 42, tegen AAPS's echte broncode: SourceSensor.kt,
    // SourceSensorExtensions.kt, XdripSourcePlugin.kt) — AAPS's XdripSourcePlugin
    // leest deze extra ("SourceInfo") in `SourceSensor.fromString(...)`, wat de
    // string tegen elke enum-waarde's EIGEN `.text`-veld matcht (SourceSensor.kt:
    // `entries.firstOrNull { it.text == source } ?: UNKNOWN`) — een letterlijke,
    // exacte string-vergelijking, geen "lijkt op"-matching. De oude waarde
    // "CareSenseAir" matchte GEEN ENKELE `.text` uit die enum, dus kwam altijd op
    // UNKNOWN uit.
    //
    // Of "SMB always" mag, hangt af van `SourceSensor.advancedFilteringSupported()`
    // (SourceSensorExtensions.kt): TRUE alleen voor een vaste, hardcoded whitelist
    // van SourceSensor-waarden (DEXCOM_*, LIBRE_2/LIBRE_2_NATIVE/LIBRE_3, SYAI_TAG,
    // RANDOM) — UNKNOWN zit daar dus NOOIT in. Er bestaat geen "CareSens Air"-
    // waarde in AAPS's enum, en die toevoegen zou een AAPS-wijziging vergen — dus
    // een string kiezen die zowel eerlijk "CareSens" zegt ALS al vertrouwd is, is
    // met een ongewijzigde AAPS niet mogelijk. Van de whitelist is `RANDOM`
    // ("Random") de enige waarde die geen bestaand ANDER sensormerk imiteert (in
    // tegenstelling tot bv. "G6 Native"/"Libre2", wat AAPS/Nightscout op het
    // verkeerde been zou zetten over welk fysiek apparaat er gekoppeld was) —
    // vandaar die keuze hier.
    //
    // 10/08/2026 (editor, RONDE 87, na een live-melding dat de Dexcom G6 "Unknown"
    // gaf in AAPS terwijl CareSens Air wél "Random" en dus SMB Always kreeg) — bug
    // bevestigd tegen de echte SourceSensor.kt-enum: hier stond "G6" voor Dexcom
    // G6, maar die string matcht GEEN ENKELE `.text`-waarde in AAPS's enum (alleen
    // G7 had al een kale "G7"-waarde, via DEXCOM_G7_XDRIP, en bleef ongewijzigd).
    // Eerst gezet op `DEXCOM_G6_NATIVE_XDRIP`'s exacte waarde ("G6 Native") — de
    // semantisch nauwkeurigste match voor een G6 die via een externe app het
    // xDrip-protocol spreekt.
    //
    // 11/08/2026 (editor, RONDE 88, na een live-test op het toestel) — "G6 Native"
    // gaf op het toestel nog steeds "Unknown" in AAPS; met "AAPS-Dexcom"
    // (`DEXCOM_NATIVE_UNKNOWN` in SourceSensor.kt) toont AAPS wél de string zelf.
    // Die waarde zit ook in `advancedFilteringSupported()`'s whitelist, dus SMB
    // Always werkt hiermee ook — vermoedelijk matcht AAPS's XdripSourcePlugin de
    // SourceInfo-extra ergens net iets anders dan de kale `SourceSensor.
    // fromString()`-vergelijking uit de aangeleverde bestanden liet zien; niet met
    // zekerheid te herleiden zonder XdripSourcePlugin.kt's volledige broncode.
    // Semantische kanttekening: "AAPS-Dexcom" hoort in AAPS's eigen enum eigenlijk
    // bij AAPS's NATIVE Dexcom-plugin (een andere koppelweg dan xDrip-broadcast)
    // — functioneel geen probleem (de whitelist-toets kijkt alleen naar de
    // enum-waarde), maar wie in AAPS/Nightscout naar de broncode-naam kijkt ziet
    // dus "AAPS-Dexcom" i.p.v. een naam die naar xDrip/FCLGlucoLink verwijst.
    // Live bevestigd werkend, aangehouden.
    //
    // 20/08/2026 (editor, RONDE 115, volledige analyse tegen de aangeleverde AAPS-
    // broncode voor zowel V3.4 als V4 dev: SourceSensor.kt, XdripSourcePlugin.kt,
    // SafetyPlugin.kt, ConstraintsChecker.kt e.a., naar aanleiding van een melding
    // dat SMB Always bij V3-gebruikers niet werkte) — bevindingen:
    //
    // 1) "SMB Always" (`isAdvancedFilteringEnabled`) wordt in V3 en V4 op
    //    FUNDAMENTEEL verschillende manieren bepaald: V3's SafetyPlugin vraagt
    //    `activePlugin.activeBgSource.advancedFilteringSupported()` op — bij
    //    XdripSourcePlugin een gecachte boolean die alleen gezet wordt door
    //    `detectSource()` tegen een HARDCODED array in dat bestand zelf. V4's
    //    SafetyPlugin vraagt in plaats daarvan `persistenceLayer.
    //    isAdvancedFilteringSupported()` op — een databank-brede check die de
    //    nieuwe, losstaande extensie-functie `SourceSensor.
    //    advancedFilteringSupported()` uit SourceSensorExtensions.kt gebruikt.
    // 2) Ondanks dat mechanische verschil is wat hier telt identiek: WELKE
    //    `SourceSensor.text`-strings als "vertrouwd" gelden. V3's hardcoded array
    //    (DEXCOM_NATIVE_UNKNOWN, DEXCOM_G6_NATIVE, DEXCOM_G7_NATIVE,
    //    DEXCOM_G6_NATIVE_XDRIP, DEXCOM_G7_NATIVE_XDRIP, DEXCOM_G7_XDRIP, LIBRE_2,
    //    LIBRE_2_NATIVE, LIBRE_3) is een STRIKTE deelverzameling van V4's
    //    ADVANCED_FILTERING_SENSORS-set (dezelfde negen, plus SYAI_TAG en — nieuw
    //    — RANDOM).
    // 3) Dat verklaart de melding exact: CareSens Air stuurt "Random" (RANDOM-
    //    waarde) — in V4 sinds kort wél vertrouwd, in V3 NOOIT. Accu-Chek/
    //    Simulator sturen strings die in GEEN ENKELE AAPS-versie een SourceSensor
    //    matchen (komen altijd op UNKNOWN uit, nooit vertrouwd). Alleen Dexcom G6
    //    ("AAPS-Dexcom") en G7 ("G7") gebruikten al een waarde die in BEIDE
    //    whitelists zit.
    // 4) Doorsnede van beide whitelists (dus gegarandeerd werkend op zowel V3 als
    //    V4-dev): AAPS-Dexcom/AAPS-DexcomG6/AAPS-DexcomG7/G6 Native/G7 Native/G7/
    //    Libre2/Libre2 Native/Libre3. Voor de nieuwe universele code is "AAPS-
    //    Dexcom" gekozen (i.p.v. bv. "G6 Native"): naast dat 'ie in de doorsnede
    //    zit, is dit de ENIGE van de negen die al live bevestigd werkend was
    //    getest (Ronde 88 hierboven) — een echte meting weegt zwaarder dan de
    //    statische code-analyse alleen.
    //
    // [universalSourceCode] (AppSettings.xdripUniversalSourceCodeEnabled, door de
    // aanroeper gelezen — zie SlotRuntime/BleConnectionService.kt, zelfde "buiten
    // deze klasse gelezen, hier alleen het kale resultaat binnenkrijgen"-patroon
    // als smoothing/KalmanSmoother.kt's breakInDecayFactor/strength): AAN ->
    // "AAPS-Dexcom" voor ELKE sensortype, ook de simulator. UIT (default) -> de
    // bestaande, per-sensortype "best kloppende" omschrijving hieronder,
    // ongewijzigd.
    private fun sourceInfo(sensorType: SensorType, universalSourceCode: Boolean): String {
        if (universalSourceCode) return "AAPS-Dexcom"
        return when (sensorType) {
            SensorType.CARESENS_AIR -> "Random"
            SensorType.DEXCOM_G6 -> "AAPS-Dexcom"
            SensorType.DEXCOM_G7 -> "G7"
            SensorType.ACCUCHEK_SMARTGUIDE -> "AccuChek"
            SensorType.SIMULATOR -> "FCLGlucoLinkSimulator"
        }
    }

    /** 05/08/2026 (editor, RONDE 41, na een melding dat de richtingspijl een
     *  dubbel vraagteken toonde) — was ALL_CAPS_SNAKE_CASE ("DOUBLE_DOWN"
     *  etc.), wat NIET de Nightscout/Dexcom-Share "direction"-conventie
     *  matcht die xDrip+ hier verwacht (PascalCase: "DoubleUp", "Flat",
     *  "FortyFiveDown", ...) — xDrip+ herkende de string niet en viel terug
     *  op zijn eigen "onbekende richting"-weergave ("??"). De drempels zelf
     *  (mg/dL per minuut) zijn ongewijzigd, alleen de geëxporteerde strings
     *  zijn nu de juiste PascalCase-namen. */
    private fun trendName(mgdlPerMin: Float): String = when {
        mgdlPerMin <= -3f -> "DoubleDown"
        mgdlPerMin <= -2f -> "SingleDown"
        mgdlPerMin <= -1f -> "FortyFiveDown"
        mgdlPerMin < 1f -> "Flat"
        mgdlPerMin < 2f -> "FortyFiveUp"
        mgdlPerMin < 3f -> "SingleUp"
        else -> "DoubleUp"
    }

    // 08/09/2026 (editor, RONDE 173, na een melding dat de richting die naar
    // AAPS/het horloge werd gestuurd niet overeenkwam met wat het
    // thuisscherm zelf toonde) — [reading.trendMgdlPerMin] is de RUWE,
    // ongevalideerde transmitterbyte (zie AlarmEvaluator.kt's Ronde 171/172-
    // kdoc voor de volledige analyse van hoe fout die kan zitten); het
    // thuisscherm zelf toont daarentegen altijd het GEMETEN verschil tussen
    // de twee laatste opgeslagen metingen (StatusScreen.kt's
    // `BgRingDisplay`). Die twee konden dus uit elkaar lopen. Nu gebruikt de
    // broadcast dezelfde gemeten-trend-berekening als het thuisscherm
    // ([TrendCalculator.measuredMgdlPerMin], gedeeld met AlarmEvaluator.kt
    // sinds Ronde 172) i.p.v. de ruwe byte, met de ruwe byte alleen nog als
    // terugval als er geen bruikbare vorige meting is (sensor net gestart,
    // groot meetgat) — zie dat bestand's kdoc voor de exacte 2-15
    // minuten-marge. Bewust generiek per [GlucoseReading] (geen
    // sensorType-onderscheid): werkt zo identiek voor G6/G7/CareSens
    // Air/simulator.
    private fun buildBundle(
        reading: GlucoseReading,
        universalSourceCode: Boolean,
        previousReading: GlucoseReading?
    ): Bundle = Bundle().apply {
        val effectiveTrendMgdlPerMin = TrendCalculator.measuredMgdlPerMin(reading, previousReading)
            ?: reading.trendMgdlPerMin
        putDouble(EXTRA_BG_ESTIMATE, reading.glucoseMgdl)
        putString(EXTRA_BG_SLOPE_NAME, trendName(effectiveTrendMgdlPerMin))
        // xDrip verwacht de slope in mg/dL PER MILLISECONDE (vandaar /60000,
        // zelfde omrekening als Juggluco's eigen mkGlucosebundle()).
        putDouble(EXTRA_BG_SLOPE, effectiveTrendMgdlPerMin.toDouble() / 60000.0)
        putLong(EXTRA_TIMESTAMP, reading.timestampMs)
        putLong(EXTRA_SENSOR_STARTED_AT, reading.sensorStartedAtMs)
        putString(EXTRA_DATA_SOURCE_INFO, sourceInfo(reading.sensorType, universalSourceCode))
    }

    /** Cache van pakketnamen met een geregistreerde ontvanger — ververst bij
     *  elke broadcast(): dat is goedkoop genoeg (één keer per meting, dus
     *  hooguit eens per paar minuten) en voorkomt dat een AAPS-herinstallatie/
     *  -update tijdens gebruik een verouderde, lege lijst laat hangen. */
    private fun resolveReceiverPackages(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(ACTION)
        // 30/07/2026 (editor, bugfix na logcat "Geen enkele app gevonden" +
        // vergelijking met Juggluco's ECHTE bron, Broadcasts.java#actionListeners()):
        // was MATCH_DEFAULT_ONLY. Die vlag laat ALLEEN ontvangers door die
        // hun intent-filter met <category android:name="...DEFAULT"/>
        // declareren — voor een aangepaste actie als xDrip's BgEstimate doet
        // vrijwel geen enkele ontvanger dat (dat is voor impliciete intents,
        // niet voor dit soort punt-tot-punt broadcasts), dus de lijst kwam
        // altijd leeg terug, ook met AAPS actief en met het <queries>-blok
        // op zijn plek. Juggluco gebruikt hier gewoon 0 (geen filter) —
        // exact wat hier ook hoort te staan.
        @Suppress("DEPRECATION") // queryBroadcastReceivers(Intent,Int) — de
        // ResolveInfoFlags-variant is pas sinds API 33 beschikbaar; dit
        // project heeft minSdk 26, dus de oude overload blijft nodig.
        val resolveInfos = pm.queryBroadcastReceivers(intent, 0)
        return resolveInfos.mapNotNull { it.activityInfo?.packageName }.distinct()
    }

    // 20/08/2026 (editor, RONDE 115) — nieuwe parameter [universalSourceCode],
    // zie [sourceInfo]'s kdoc. Default `false` zodat bestaande aanroepen
    // (als die er ooit los van BleConnectionService.kt zouden zijn)
    // ongewijzigd blijven werken.
    //
    // 08/09/2026 (editor, RONDE 173) — nieuwe parameter [previousReading],
    // zie [buildBundle]'s kdoc. Default `null` zodat een eventuele andere
    // aanroeper zonder deze context nog steeds werkt (valt dan simpelweg
    // terug op de ruwe trendbyte, het oude gedrag).
    fun broadcast(
        context: Context,
        reading: GlucoseReading,
        universalSourceCode: Boolean = false,
        previousReading: GlucoseReading? = null
    ) {
        val packages = resolveReceiverPackages(context)
        if (packages.isEmpty()) {
            Log.w(TAG, "Geen enkele app met een xDrip-broadcast-ontvanger gevonden — draait AAPS?")
            return
        }
        val bundle = buildBundle(reading, universalSourceCode, previousReading)
        for (packageName in packages) {
            val intent = Intent(ACTION).apply {
                putExtras(bundle)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(packageName)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "broadcast naar $packageName: ${reading.glucoseMgdl} mg/dL")
        }
    }
}
