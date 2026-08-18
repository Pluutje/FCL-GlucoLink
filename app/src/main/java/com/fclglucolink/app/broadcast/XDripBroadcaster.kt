package com.fclglucolink.app.broadcast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorType

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
 * vullen pakketnaam) — op editor's expliciete verzoek. In plaats van een
 * hardcoded AAPS-pakketnaam (die per AAPS-build/-fork kan verschillen, en
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

    // 05/08/2026 (editor, RONDE 42 — op verzoek, na de gebruiker's eigen
    // upload van AAPS's ECHTE broncode: SourceSensor.kt,
    // SourceSensorExtensions.kt, XdripSourcePlugin.kt) — AAPS's
    // XdripSourcePlugin leest deze extra ("SourceInfo") in
    // `SourceSensor.fromString(...)`, wat de string tegen elke enum-waarde's
    // EIGEN `.text`-veld matcht (SourceSensor.kt: `entries.firstOrNull
    // { it.text == source } ?: UNKNOWN`) — geen slimme "lijkt op"-matching,
    // een letterlijke, exacte string-vergelijking. Onze oude waarde
    // "CareSenseAir" matcht GEEN ENKELE `.text` uit die enum, dus kwam
    // altijd op UNKNOWN uit.
    //
    // Of "SMB always" mag, hangt af van `SourceSensor.advancedFiltering
    // Supported()` (SourceSensorExtensions.kt): TRUE alleen voor een vaste,
    // hardcoded whitelist van SourceSensor-waarden (DEXCOM_*, LIBRE_2/
    // LIBRE_2_NATIVE/LIBRE_3, SYAI_TAG, RANDOM) — UNKNOWN zit daar dus
    // NOOIT in. Er bestaat geen "CareSens Air"-waarde in AAPS's enum (en
    // die er zelf aan toevoegen zou wél een AAPS-wijziging vergen, precies
    // wat de gebruiker uitdrukkelijk wil vermijden) — dus een string kiezen
    // die zowel eerlijk "CareSens" zegt ALS al vertrouwd is, is met een
    // ongewijzigde AAPS gewoonweg niet mogelijk. Van de whitelist is
    // `RANDOM` ("Random") de enige waarde die geen bestaand ANDER
    // sensormerk imiteert (in tegenstelling tot bv. "G6 Native"/"Libre2",
    // wat AAPS/Nightscout/de gebruiker zelf achteraf op het verkeerde been
    // zou zetten over welk fysiek apparaat er daadwerkelijk gekoppeld was)
    // — vandaar die keuze hier. Nog niet bevestigd met een live AAPS-test:
    // of `RANDOM` verder nog ergens anders speciaal behandeld wordt in AAPS
    // (buiten `advancedFilteringSupported()` om) is met alleen deze drie
    // bestanden niet te zien — eerste live test zal dat uitwijzen.
    //
    // 10/08/2026 (editor, RONDE 87 — op verzoek, na live-melding: "de
    // dexcom g6 geeft unknown binnen aaps... caresens geeft Random en heeft
    // dus wel smb always") — bug bevestigd tegen de daadwerkelijke
    // SourceSensor.kt-enum: hier stond "G6" voor Dexcom G6, maar die string
    // matcht GEEN ENKELE `.text`-waarde in AAPS's enum (alleen G7 heeft een
    // kale "G7"-waarde, via DEXCOM_G7_XDRIP — dat is dus wél al correct,
    // ongewijzigd gelaten). Eerst hier gezet op `DEXCOM_G6_NATIVE_XDRIP`'s
    // exacte waarde ("G6 Native") — semantisch de nauwkeurigste match voor
    // "een G6, aangeleverd via een externe app die het xDrip-protocol
    // spreekt", precies wat FCLGlucoLink doet.
    //
    // 11/08/2026 (editor, RONDE 88 — gebruiker heeft dit zelf op het
    // toestel getest en aangepast) — "G6 Native" gaf op het toestel nog
    // steeds "Unknown" in AAPS; met "AAPS-Dexcom" (`DEXCOM_NATIVE_UNKNOWN`
    // in SourceSensor.kt) toont AAPS nu wél de string zelf (geen "Unknown"
    // meer). Die waarde zit ook in `advancedFilteringSupported()`'s
    // whitelist, dus SMB Always zou hiermee ook moeten werken — vermoedelijk
    // matcht AAPS's XdripSourcePlugin de SourceInfo-extra ergens strenger of
    // net iets anders dan de kale `SourceSensor.fromString()`-vergelijking
    // uit de aangeleverde bestanden liet zien (bijvoorbeeld alleen bepaalde
    // waarden daadwerkelijk aanvaardt via de xDrip-broadcast-weg specifiek,
    // los van de vergelijking zelf) — niet met zekerheid te herleiden zonder
    // XdripSourcePlugin.kt's VOLLEDIGE broncode (alleen een fragment was
    // aangeleverd). Semantische kanttekening: "AAPS-Dexcom" hoort in AAPS's
    // eigen enum eigenlijk bij AAPS's NATIVE Dexcom-plugin (een andere
    // koppelweg dan xDrip-broadcast) met een niet-gespecificeerd G5/G6/G7-
    // model — functioneel geen probleem hier (de whitelist-toets kijkt
    // alleen naar de enum-waarde, niet naar de daadwerkelijke koppelweg),
    // maar wie later in AAPS/Nightscout naar de broncode-naam kijkt ziet dus
    // "AAPS-Dexcom" i.p.v. een naam die verwijst naar xDrip/FCLGlucoLink.
    // Gebruiker heeft dit zelf getest en bevonden dat het werkt — aangehouden.
    private fun sourceInfo(sensorType: SensorType): String = when (sensorType) {
        SensorType.CARESENS_AIR -> "Random"
        SensorType.DEXCOM_G6 -> "AAPS-Dexcom"
        SensorType.DEXCOM_G7 -> "G7"
        SensorType.ACCUCHEK_SMARTGUIDE -> "AccuChek"
        SensorType.SIMULATOR -> "FCLGlucoLinkSimulator"
    }

    /** 05/08/2026 (editor, RONDE 41 — op verzoek, "richtingspijl toont
     *  dubbel vraagteken") — was ALL_CAPS_SNAKE_CASE ("DOUBLE_DOWN" etc.),
     *  dat matcht NIET de Nightscout/Dexcom-Share "direction"-conventie die
     *  xDrip+ hier daadwerkelijk verwacht (PascalCase: "DoubleUp", "Flat",
     *  "FortyFiveDown", ...) — vandaar dat xDrip+ de string niet herkende
     *  en terugviel op zijn eigen "onbekende richting"-weergave ("??"). De
     *  drempels zelf (mg/dL per minuut) zijn ongewijzigd, alleen de
     *  geëxporteerde strings zijn nu de juiste PascalCase-namen. */
    private fun trendName(mgdlPerMin: Float): String = when {
        mgdlPerMin <= -3f -> "DoubleDown"
        mgdlPerMin <= -2f -> "SingleDown"
        mgdlPerMin <= -1f -> "FortyFiveDown"
        mgdlPerMin < 1f -> "Flat"
        mgdlPerMin < 2f -> "FortyFiveUp"
        mgdlPerMin < 3f -> "SingleUp"
        else -> "DoubleUp"
    }

    private fun buildBundle(reading: GlucoseReading): Bundle = Bundle().apply {
        putDouble(EXTRA_BG_ESTIMATE, reading.glucoseMgdl)
        putString(EXTRA_BG_SLOPE_NAME, trendName(reading.trendMgdlPerMin))
        // xDrip verwacht de slope in mg/dL PER MILLISECONDE (vandaar /60000,
        // zelfde omrekening als Juggluco's eigen mkGlucosebundle()).
        putDouble(EXTRA_BG_SLOPE, reading.trendMgdlPerMin.toDouble() / 60000.0)
        putLong(EXTRA_TIMESTAMP, reading.timestampMs)
        putLong(EXTRA_SENSOR_STARTED_AT, reading.sensorStartedAtMs)
        putString(EXTRA_DATA_SOURCE_INFO, sourceInfo(reading.sensorType))
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

    fun broadcast(context: Context, reading: GlucoseReading) {
        val packages = resolveReceiverPackages(context)
        if (packages.isEmpty()) {
            Log.w(TAG, "Geen enkele app met een xDrip-broadcast-ontvanger gevonden — draait AAPS?")
            return
        }
        val bundle = buildBundle(reading)
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
