package com.fclglucolink.app.sensor.caresensair

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air BLE-protocol (koppel-stap 2/4, HERSCHREVEN)
 * ============================================================================
 *
 * 01/08/2026 (editor) — VOLLEDIGE HERZIENING. De vorige versie van dit
 * bestand ging ervan uit dat CareSens Air grotendeels de STANDAARD
 * Bluetooth SIG "Glucose Profile" (0x1808/0x2A18/0x2A52) praat. Dat bleek
 * FOUT: nRF Connect-onderzoek tegen een echte CSAir 0224-sensor toonde
 * geen spoor van die standaard-service — in plaats daarvan drie
 * propriëtaire services/karakteristieken met UUID's die LETTERLIJK
 * overeenkomen met Juggluco's `AirGattCallback.java` (GPL-3, credit al in
 * ui/AboutScreen.kt) — dat bestand blijkt (ondanks een verouderd
 * headercommentaar dat alleen Freestyle Libre 2/3 noemt) ook het CareSens
 * Air-pad te zijn (`AppID = "csair"` verraadt dat). Dit bestand is nu een
 * letterlijke poort van AirGattCallback.java's commando's/responsparsers
 * (protocol-laag; de BLE-verbindingslogica zelf staat in
 * CareSensAirDriver.kt, en de ruwe-data-naar-mg/dL-omzetting in de nieuwe
 * native laag, zie app/src/main/cpp/caresensair_bridge.cpp).
 *
 * BEKENDE VEREENVOUDIGINGEN t.o.v. Juggluco (bewust, gedocumenteerd — geen
 * van alle raakt de kernfunctionaliteit voor een moderne sensor):
 *  - Geen CRC-verificatie op het kalibratieprofiel (0xC2/0x03-bericht) —
 *    Juggluco zelf disconnect ook pas als BEIDE crc-varianten falen, dus dit
 *    is sowieso een zachte controle, geen harde vereiste.
 *  - Alleen het pad voor swRevision >= "1.5" (AppID-handshake via
 *    [buildAppIdHandshakeCommand]) en >= "1.3" (float-parsing in
 *    [parseStartSensorResponse]) — oudere sensor-firmwarevarianten niet
 *    geport. Deze sensor is een recente eenheid, dus dit dekt het
 *    relevante geval; als een toekomstige sensor een oudere swRevision
 *    terugmeldt, geeft de driver een duidelijke foutmelding i.p.v. stil te
 *    falen (zie CareSensAirDriver.kt).
 */

// --- Services. GATT-discovery zelf matcht op karakteristiek-UUID, niet op
// service-UUID (zie de karakteristieken hieronder) — maar deze drie worden
// SINDS RONDE 30 wél degelijk actief gebruikt, als ScanFilter in
// CareSensAirDriver.kt's startConnectScan(). Een eerdere kdoc hier
// (vóór 01/08/2026, dus vóór deze UUID's zelfs bekend waren) concludeerde
// nog "adverteert geen matchbare service-UUID" — dat was gebaseerd op een
// test met de VERKEERDE, standaard Bluetooth Glucose Profile-UUID (0x1808)
// en nooit herhaald met deze echte, propriëtaire UUID's. Live-logcat
// bevestigde op 03/08/2026 dat de sensor `CSAIR_SERVICE_2` daadwerkelijk in
// zijn raw advertisement uitzendt (`serviceUuids=[c4de9a20-...]`) — een
// ScanFilter hierop maakt hardware-offloaded (chip-side) scannen mogelijk,
// wat significant Doze-bestendiger is dan het eerdere ongefilterde,
// software-side scannen. ---
val CSAIR_SERVICE_1: UUID = UUID.fromString("c4de7bda-5a9d-11e9-8647-d663bd873d93")
val CSAIR_SERVICE_2: UUID = UUID.fromString("c4de9a20-5a9d-11e9-8647-d663bd873d93")
val CSAIR_SERVICE_3: UUID = UUID.fromString("c4de9dc2-5a9d-11e9-8647-d663bd873d93")

// --- Karakteristieken. Namen/rollen 1-op-1 uit AirGattCallback.java's
// discover()/characterRead()/onChar11Changed/onChar21Changed/onChar22Changed
// afgeleid. ---

/** Serienummer (String, leesbaar) — enige "extra info"-read die release-
 *  builds van Juggluco daadwerkelijk doen (askExtraInfo=doLog=false slaat
 *  charact1/2/4/5/7 over). */
val CHAR_SERIAL: UUID = UUID.fromString("c4de8544-5a9d-11e9-8647-d663bd873d93")

/** Software-revisie (String, bv. "1.6") — bepaalt welke handshake-variant
 *  nodig is, zie kdoc bovenaan. */
val CHAR_SW_REVISION: UUID = UUID.fromString("c4de89ae-5a9d-11e9-8647-d663bd873d93")

/** Glucosedata: schrijf hierop commando's (nieuwe-records-tellen opvragen,
 *  historie opvragen), notificaties hierop bevatten de ruwe AirData-
 *  payloads (0xC4/0xC5-voorvoegsel) die naar de native laag gaan. */
val CHAR_GLUCOSE_DATA: UUID = UUID.fromString("c4de9b74-5a9d-11e9-8647-d663bd873d93")

/** App-info/tijd-sync/kalibratieprofiel: schrijf hierop de
 *  handshake-vervolgcommando's, notificaties hierop leveren het
 *  kalibratieprofiel (0xC2-berichten, naar de native laag) en tijdsync-
 *  bevestigingen. */
val CHAR_APP_INFO: UUID = UUID.fromString("c4de9ee4-5a9d-11e9-8647-d663bd873d93")

/** AppID-handshake ("csair") — moet als EERSTE stap succesvol zijn, anders
 *  weigert de sensor de rest van het gesprek. */
val CHAR_APP_ID: UUID = UUID.fromString("c4dec61c-5a9d-11e9-8647-d663bd873d93")

val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val ENABLE_NOTIFICATION_VALUE: ByteArray = byteArrayOf(0x01, 0x00)

// --- AppID-handshake (charact22 / CHAR_APP_ID) ---

private const val CSAIR_APP_ID = "csair"

/**
 * Eerste stap van de koppel-handshake: stuurt "csair" als applicatie-ID.
 * De sensor accepteert de rest van het gesprek alleen als dit matcht — zie
 * [parseAppIdResponse]. 35 bytes: 0xC0,0x03,'c','s','a','i','r', daarna
 * nullbytes, met byte 34 = 1 als dit een sensor is waarvoor nog GEEN
 * kalibratiegeschiedenis bestaat (Juggluco's `unusedSensor`-vlag, bepaald
 * via `Natives.airGetLast(dataptr)<=0` — hier de Kotlin-tegenhanger
 * [unusedSensor], gebaseerd op `CareSensAirNative.getLastSequence()`).
 *
 * 01/08/2026 (editor, na live-test — de sensor brak de verbinding meteen
 * af ná dit commando, met een reconnect-achtige afwijzing) — deze byte
 * werd voorheen ALTIJD op 0 gelaten ("dit is geen nieuwe sensor voor deze
 * app"), ook bij de allereerste koppeling ooit met deze sensor. Voor een
 * sensor zonder enige eerdere kalibratiegeschiedenis (native
 * lastSequence <= 0) verwacht de sensor kennelijk byte 34 = 1 — met 0
 * claimt de app ten onrechte een eerdere sessie te hebben, wat de sensor
 * afwijst. 1-op-1 overgenomen van AirGattCallback's eigen logica i.p.v.
 * de eerdere (foutieve) aanname dat 0 altijd veilig is.
 */
fun buildAppIdHandshakeCommand(unusedSensor: Boolean): ByteArray {
    val buf = ByteArray(35)
    val prefix = byteArrayOf(0xC0.toByte(), 0x03, 'c'.code.toByte(), 's'.code.toByte(), 'a'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte())
    prefix.copyInto(buf, 0)
    if (unusedSensor) {
        buf[34] = 1
    }
    return buf
}

enum class AppIdOutcome { OK, DEVICE_MATCH_FAILED, APPID_MATCH_FAILED, RECONNECT_FAILED, WRONG_APP_ID, TOO_SHORT }

/**
 * Decodeert het antwoord op [buildAppIdHandshakeCommand] — mirror van
 * AirGattCallback.onChar22Changed. Laatste byte = statuscode (0=ok,
 * 1/2/anders=diverse faalredenen), en bij succes staat "csair" als tekst
 * op byte-offset 2.
 */
fun parseAppIdResponse(value: ByteArray): AppIdOutcome {
    if (value.size < 35) return AppIdOutcome.TOO_SHORT
    if ((value[0].toInt() and 0xFF) != 0xC0 || value[1].toInt() != 3) return AppIdOutcome.TOO_SHORT
    val last = value[value.size - 1]
    if (last > 0) {
        return when (last.toInt()) {
            1 -> AppIdOutcome.DEVICE_MATCH_FAILED
            2 -> AppIdOutcome.APPID_MATCH_FAILED
            else -> AppIdOutcome.RECONNECT_FAILED
        }
    }
    // 01/08/2026 (editor, na live-test — een geslaagde handshake
    // (last=0) werd toch als WRONG_APP_ID afgewezen) — de sensor vult
    // deze 32 bytes op met "csair" gevolgd door nulbytes (0x00), en
    // Juggluco's eigen Java-code (`new String(value,2,value.length-3)
    // .trim()`) knipt die nulbytes gewoon weg: Java's `String.trim()`
    // verwijdert ELK teken met codepoint <= 0x20 (dus ook 0x00), niet
    // alleen "echte" whitespace. Kotlin's `String.trim()` gebruikt
    // `Char.isWhitespace()`, en een NUL-teken telt daarin NIET als
    // whitespace — dus bleef "csair  ... " over, wat
    // nooit gelijk kon zijn aan CSAIR_APP_ID. Puur een taalverschil
    // tussen Java's en Kotlin's trim()-semantiek, niets aan het
    // sensor-protocol zelf. Fix: [javaStyleTrim] bootst Java's eigen
    // trim()-regel exact na i.p.v. Kotlin's ingebouwde trim() te
    // gebruiken.
    val appId = javaStyleTrim(String(value, 2, value.size - 3))
    return if (appId == CSAIR_APP_ID) AppIdOutcome.OK else AppIdOutcome.WRONG_APP_ID
}

/**
 * Nabootsing van Java's `String.trim()` (zoals gebruikt in Juggluco's
 * AirGattCallback.java): verwijdert aan begin/eind elk teken met
 * codepoint <= U+0020 — dus ook NUL-bytes (0x00), spaties, tabs, etc. —
 * in tegenstelling tot Kotlin's `String.trim()`, die alleen "echte"
 * Unicode-whitespace (`Char.isWhitespace()`) verwijdert en NUL-tekens
 * met rust laat. Zie [parseAppIdResponse]'s kdoc voor de bug die dit
 * verschil veroorzaakte.
 */
private fun javaStyleTrim(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].code <= 0x20) start++
    while (end > start && s[end - 1].code <= 0x20) end--
    return s.substring(start, end)
}

// --- App-info/handshake-vervolg (charact21 / CHAR_APP_INFO) ---

private const val CSAIR_KEY = "tq1Tg265o4UFD8tfPvNqUCiYyCxkhdZV"

/**
 * Eerste schrijfactie op [CHAR_APP_INFO] na een geslaagde AppID-handshake:
 * de sensor-serienummer AES-versleuteld terugsturen, ter authenticatie —
 * mirror van AirGattCallback's `charact21`-schrijfactie in onDescriptorWrite
 * (het swRevision>="1.4"-pad; de oudere-firmware-variant is bewust niet
 * geport, zie kdoc bovenaan). IV = laatste 6 cijfers van het serienummer
 * TWEE keer + laatste 4 cijfers (16 tekens/bytes — geldige AES-blokgrootte),
 * sleutel is een vaste, in Juggluco's bron gevonden AES-256-sleutel (geen
 * per-toestel geheim, dus geen probleem om hier letterlijk over te nemen —
 * zelfde licentie-afweging als bij de tijdsync-opcode, zie
 * CareSensAirDriver.kt's kdoc).
 */
fun buildAppInfoHandshakeCommand(serial: String): ByteArray {
    require(serial.length >= 6) { "Serienummer te kort voor CareSens Air-handshake: $serial" }
    val lastSix = serial.substring(serial.length - 6)
    val lastFour = serial.substring(serial.length - 4)
    val iv = (lastSix + lastSix + lastFour).toByteArray(Charsets.UTF_8)
    require(iv.size == 16) { "Onverwachte IV-lengte (${iv.size}, verwacht 16) voor serienummer $serial" }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val keySpec = SecretKeySpec(CSAIR_KEY.toByteArray(Charsets.UTF_8), "AES")
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
    val cipherText = cipher.doFinal(serial.toByteArray(Charsets.UTF_8))
    return byteArrayOf(0xC0.toByte(), 0x01) + cipherText
}

/** Antwoord op [buildAppInfoHandshakeCommand] (192/1) — geeft aan of de
 *  klok van de sensor gesynchroniseerd moet worden, en levert wat metadata
 *  (niet allemaal gebruikt door FCLGlucoLink, maar wel uitgelezen om de
 *  byte-offsets van het bericht correct te laten aansluiten). */
data class AppInfoResponse(
    val deviceTimeSecs: Long,
    val needsTimeSync: Boolean,
    val keyCheckFailed: Boolean
)

private fun readUInt8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
private fun readUInt16LE(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
private fun readUInt32LE(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

fun parseAppInfoResponse(value: ByteArray, nowEpochSecs: Long): AppInfoResponse? {
    if (value.size < 32) return null
    val deviceTimeSecs = readUInt32LE(value, 2)
    val adcInterval = readUInt16LE(value, 11)
    val needsTimeSync = Math.abs(nowEpochSecs - deviceTimeSecs) >= (adcInterval / 5000)
    val keyCheckResult = readUInt8(value, 31)
    return AppInfoResponse(deviceTimeSecs = deviceTimeSecs, needsTimeSync = needsTimeSync, keyCheckFailed = keyCheckResult == 0)
}

/** Commando "zet applicatie-info" (userID altijd 0 — FCLGlucoLink
 *  onderscheidt geen meerdere gebruikers per sensor) — 192,2 gevolgd door
 *  4 nulbytes (uint32 userID = 0). */
fun buildSetAppInfoCommand(): ByteArray = byteArrayOf(0xC0.toByte(), 0x02, 0, 0, 0, 0)

/** Antwoord op [buildSetAppInfoCommand] (192/2): sensor-start-parameters —
 *  alleen het swRevision>="1.3"-pad geport (float, little-endian), zie kdoc
 *  bovenaan. */
data class StartSensorInfo(val eapp: Float, val vref: Float, val elapsedSecs: Int)

fun parseStartSensorResponse(value: ByteArray): StartSensorInfo? {
    if (value.size < 14) return null
    val eapp = java.nio.ByteBuffer.wrap(value, 2, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
    val vref = java.nio.ByteBuffer.wrap(value, 6, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
    val elapsedSecs = readUInt32LE(value, 10).toInt()
    return StartSensorInfo(eapp = eapp, vref = vref, elapsedSecs = elapsedSecs)
}

/** Commando "vraag kalibratieprofiel op" — 194,1. */
fun buildAskSensorInfoCommand(): ByteArray = byteArrayOf(0xC2.toByte(), 0x01)

/** Commando "synchroniseer sensorklok" — 0xC3,0x02 gevolgd door het huidige
 *  Unix-tijdstip (seconden, little-endian uint32). */
fun buildSyncTimeCommand(nowEpochMs: Long = System.currentTimeMillis()): ByteArray {
    val secs = nowEpochMs / 1000L
    return byteArrayOf(
        0xC3.toByte(), 0x02,
        (secs and 0xFF).toByte(), ((secs shr 8) and 0xFF).toByte(),
        ((secs shr 16) and 0xFF).toByte(), ((secs shr 24) and 0xFF).toByte()
    )
}

// --- Glucosedata (charact11 / CHAR_GLUCOSE_DATA) ---

/** Commando "hoeveel nieuwe records staan klaar?" — 197,1. */
fun buildNumberRecordsCommand(): ByteArray = byteArrayOf(197.toByte(), 0x01)

/** Commando "stuur data vanaf sequentienummer X" — 196,1 gevolgd door X als
 *  little-endian uint32 (X = het laatst ontvangen sequentienummer, uit de
 *  native laag via CareSensAirNative.getLastSequence()).
 *
 *  02/08/2026 (editor, na live-test — zie README: 26s stilte + disconnect
 *  ná een correct bevestigde 197,1-schrijfactie) — GEVONDEN OORZAAK: de
 *  native laag initialiseert `lastAir` op -1 (mirror van Juggluco's eigen
 *  sentinel voor "nog nooit een record verwerkt", zie caresensair_
 *  bridge.cpp). Elke aanroeper gaf dat -1 hier voorheen ONGEWIJZIGD door.
 *  `(-1).toLong() and 0xFFFFFFFFL` = 0xFFFFFFFF (4294967295) — d.w.z. het
 *  daadwerkelijk verstuurde commando bij een VERSE koppeling was
 *  "196,1,255,255,255,255" ("stuur alles NA sequentienummer 4294967295"),
 *  niet "196,1,0,0,0,0" ("stuur alles vanaf het begin") zoals eerder
 *  aangenomen zonder de exacte bytes gelogd te hebben. Een sensor met
 *  al dagenlange geschiedenis heeft vanzelfsprekend niets ná het
 *  maximaal mogelijke sequentienummer — de "196,1,0,0" (0 nieuwe
 *  records)-aankondiging die de sensor daarop terugstuurde was dus een
 *  correct antwoord op een onbedoeld onzinnig verzoek, geen sensor- of
 *  protocolfout. Juggluco's eigen `requestData()` beschermt hiertegen met
 *  een expliciete `if(lastval<0) disconnect();`-check vóórdat het
 *  wat dan ook verstuurt — hier lossen we het bij de bron op door een
 *  negatieve/nog-onbekende sequentie simpelweg als 0 te behandelen
 *  ("stuur alles, dit is de eerste keer"), in lijn met wat de gebruiker
 *  zelf al vermoedde: bij de allereerste koppeling moet gewoon de actueel
 *  beschikbare data opgevraagd worden, niet een sentinel-waarde die "geen
 *  enkel record" betekent. */
fun buildRequestDataCommand(lastReceivedSequence: Int): ByteArray {
    val clamped = if (lastReceivedSequence < 0) 0 else lastReceivedSequence
    val v = clamped.toLong() and 0xFFFFFFFFL
    return byteArrayOf(
        196.toByte(), 0x01,
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}
