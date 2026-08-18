package com.fclglucolink.app.sensor

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ============================================================================
 * FCLGlucoLink — sensor-abstractie
 * ============================================================================
 *
 * 30/07/2026 (editor) — elke sensor (CareSens Air, Dexcom G7, Accu-Chek
 * SmartGuide, en later evt. meer) implementeert precies deze ene interface.
 * De UI/BLE-service/broadcaster kennen alleen dit contract, nooit een
 * sensor-specifieke klasse rechtstreeks — dat is wat een nieuwe sensor later
 * toevoegen tot "een nieuwe SensorDriver-implementatie + een regel in
 * SensorRegistry" maakt, in plaats van UI/service-code te moeten aanraken.
 */
enum class SensorType(val displayName: String, val implemented: Boolean) {
    CARESENS_AIR("CareSens Air", implemented = true),
    // 17/08/2026 (editor, RONDE 112, op verzoek: "code zover in orde brengen
    // dat zodra ik er eentje krijg ik gelijk kan beginnen met testen") —
    // `implemented = true` vóór de eerste live-test tegen een echte sensor,
    // exact zoals bij DEXCOM_G6 destijds (zie dat commentaar hieronder en
    // DexcomG6Driver.kt's eigen kdoc: "nog niet tegen een echte G6-
    // transmitter geverifieerd — verwacht bijstellen na de eerste live-
    // test"). Zie sensor/dexcomg7/DexcomG7Driver.kt voor de volledige
    // implementatie en wat daar bewust nog niet in zit.
    DEXCOM_G7("Dexcom G7 / ONE+", implemented = true),
    ACCUCHEK_SMARTGUIDE("Accu-Chek SmartGuide", implemented = false),
    // 08/08/2026 (editor, RONDE 55) — G6 stond hier tot vandaag bewust NIET
    // in (zie git-geschiedenis: "Juggluco heeft er zelf ook geen
    // ondersteuning voor, en editor's G6-voorraad faseert vanzelf uit").
    // Dat is achterhaald: de gebruiker heeft BYODA (een gemodificeerde
    // Dexcom-app, transmitter loopt door na 10 dagen) en de volledige
    // xDrip+-broncode aangeleverd als referentie ("dat heb ik eigenlijk
    // altijd gebruikt en was gewoon 99,9% stabiel") en expliciet gevraagd
    // om te bouwen. Zie sensor/dexcomg6/DexcomG6Driver.kt's kdoc voor de
    // fase-1-scope.
    DEXCOM_G6("Dexcom G6", implemented = true),

    // 30/07/2026 (editor) — GEEN echte sensor: laat je handmatig of via een
    // afgespeelde CSV-lijst fictieve BG-waarden versturen door precies
    // dezelfde pijplijn (BleConnectionService -> GlucoseReadingStore ->
    // XDripBroadcaster) als een echte sensor. Bedoeld om (a) het exportpad
    // naar AAPS te testen op een reservetelefoon met virtuele pomp, zonder
    // dat er al een echte sensor-driver hoeft te werken, en (b) een eerder
    // problematische BG-reeks exact te kunnen laten herafspelen om een
    // FCLvNext-fix te valideren voordat die live gaat.
    SIMULATOR("BG simulator (testing)", implemented = true)
}

/**
 * 10/08/2026 (editor, RONDE 79 — start van de 2-sensoren-architectuur, op
 * verzoek: "ik wil graag verder met de koppeling van 2 sensoren binnen de
 * app") — twee onafhankelijke, gelijktijdig verbonden sensor-"plekken".
 * Bewust generiek (A/B, geen "CARESENS_SLOT"/"G6_SLOT") zodat elke slot elk
 * [SensorType] kan bevatten, inclusief twee keer HETZELFDE type (bv. twee
 * G6-transmitters tegelijk tijdens een sensor-wissel-overlap, op
 * uitdrukkelijk verzoek: "gedurende de laatste dagen van de lopende G6
 * alvast een nieuwe kan starten die dan stabiel kan worden"). Slot A/B
 * hebben verder geen betekenisverschil — welke van de twee naar AAPS zendt
 * is een aparte, wisselbare keuze (zie AppSettings.aapsActiveSlot), niet aan
 * de slot zelf gekoppeld. [suffix] is de DataStore-sleutel-suffix
 * (AppSettings.kt bouwt elke per-sensor sleutel als "${basisnaam}_${suffix}").
 */
enum class SensorSlot(val suffix: String, val displayLabel: String) {
    A("a", "Slot A"),
    B("b", "Slot B")
}

/**
 * Eén glucosemeting, sensor-onafhankelijk. mgdl (niet mmol/l) omdat dat het
 * eenheid is waarin de xDrip-broadcast naar AAPS verwacht wordt — zie
 * broadcast/XDripBroadcaster.kt.
 *
 * 05/08/2026 (editor, RONDE 43) — [glucoseMgdl] is de waarde die overal
 * "telt" (scherm, grafiek, xDrip-broadcast): zodra kalibratie aanstaat is
 * dit de GEKALIBREERDE waarde, zie BleConnectionService.kt's toepassing van
 * CalibrationEngine.computeCalibration(). Elke sensor-driver kent kalibratie
 * zelf niet — die emit't hier gewoon de RUWE sensorwaarde in beide velden
 * ([rawSensorMgdl] standaard gelijk aan [glucoseMgdl], zie het default-
 * argument hieronder); BleConnectionService overschrijft [glucoseMgdl] pas
 * NA emissie met een `.copy(...)` zodra kalibratie actief is, en laat
 * [rawSensorMgdl] dan ongewijzigd staan — zodat de UI de twee naast elkaar
 * kan tonen (StatusScreen.kt: grote gekleurde cirkel = gekalibreerd, kleine
 * grijze open cirkel = ruw, alleen zichtbaar als ze daadwerkelijk verschillen).
 *
 * 18/08/2026 (editor, RONDE 113, op verzoek — losse regel raw/gekalibreerd/
 * gefilterd onder de sensor-infokaart) — nieuw veld [calibratedMgdl]: de
 * waarde NA kalibratie maar VOOR smoothing. Tot nu toe ging die tussenstap
 * verloren — BleConnectionService.kt's applySmoothingIfEnabled() overschreef
 * `glucoseMgdl` met de gladgestreken waarde bovenop wat
 * applyCalibrationIfEnabled() er net in gezet had, zonder de gekalibreerde
 * tussenwaarde ergens te bewaren. Zelfde default-patroon als [rawSensorMgdl]
 * hierboven ([calibratedMgdl] standaard gelijk aan [glucoseMgdl]): als
 * kalibratie uitstaat blijft dit veld dus gewoon gelijk aan de ruwe waarde,
 * precies het gedrag dat StatusScreen.kt's nieuwe pipeline-regel nodig heeft
 * om de "gekalibreerd"-kolom netjes weg te laten wanneer kalibratie niet
 * actief is.
 */
data class GlucoseReading(
    val glucoseMgdl: Double,
    /** mg/dL per minuut — zelfde eenheid als xDrip's "slope". */
    val trendMgdlPerMin: Float,
    val timestampMs: Long,
    val sensorStartedAtMs: Long,
    val sensorType: SensorType,
    val rawSensorMgdl: Double = glucoseMgdl,
    val calibratedMgdl: Double = glucoseMgdl
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceAddress: String) : ConnectionState
    data class Connected(val deviceAddress: String, val deviceName: String?) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

interface SensorDriver {

    val sensorType: SensorType

    val connectionState: StateFlow<ConnectionState>

    /** Elke geldige, geparste meting komt hier voorbij — de BLE-service
     *  (BleConnectionService) luistert hierop en stuurt elke meting door
     *  naar zowel de lokale opslag (data/GlucoseReadingStore) als de
     *  xDrip-broadcast (broadcast/XDripBroadcaster). */
    val readings: SharedFlow<GlucoseReading>

    /** Start scannen naar koppelbare devices van dit sensor-type.
     *  onDeviceFound wordt per gevonden device aangeroepen (UI toont een
     *  keuzelijst, zie ui/PairingScreen.kt) — er wordt hier nog NIET
     *  automatisch verbonden. */
    fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit)

    fun stopPairing()

    /** Verbindt met een eerder gekozen/gekoppeld device-adres — aangeroepen
     *  zowel na een handmatige koppeling als bij elke herstart (met het
     *  opgeslagen adres uit AppSettings). */
    fun connect(context: Context, deviceAddress: String)

    fun disconnect()

    /**
     * 01/08/2026 (editor, op verzoek na de CareSens Air-koppel-tests) —
     * optioneel: bouwt een filter voor de koppellijst (ui/PairingScreen.kt).
     * Standaard `null` = geen filter, alle gevonden apparaten tonen (huidig
     * gedrag voor sensoren die dit niet overschrijven). Een sensor die WEL
     * al vooraf weet welk apparaat het moet zijn (bv. via een eerdere
     * barcode-scan) kan hier een `(deviceName, deviceAddress) -> Boolean`
     * teruggeven — zie CareSensAirDriver.kt voor een voorbeeld (matcht op
     * "CSAir" in de naam of de laatste cijfers van het gescande
     * serienummer). PairingScreen.kt biedt de gebruiker altijd een
     * "toon alle apparaten"-schakelaar om dit filter opzij te zetten — een
     * filter op apparaatNAAM is een vuistregel, geen garantie (kan per
     * sensor-firmware/regio verschillen), dus nooit een harde blokkade.
     * `suspend` omdat dit doorgaans AppSettings (DataStore) moet raadplegen.
     */
    suspend fun buildPairingListFilter(context: Context): ((deviceName: String?, deviceAddress: String) -> Boolean)? = null
}
