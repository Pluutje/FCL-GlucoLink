package com.fclglucolink.app.sensor.caresensair

import java.util.Calendar

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air barcode decoding (koppel-stap 1/4)
 * ============================================================================
 *
 * 31/07/2026 (editor) — CareSens Air wordt niet gekoppeld via een BLE-
 * scanlijst maar door de barcode op de sensor te scannen (zie
 * ui/CareSensAirScanScreen.kt en de kdoc bij CareSensAirDriver.kt). Die
 * barcode is een GS1 Application-Identifier-gecodeerde string — hetzelfde
 * publieke, internationale streepjescode-formaat dat op vrijwel elke
 * medische-hulpmiddelenverpakking staat (UDI-vereiste), GEEN CareSens-eigen
 * geheime encodering. Elk stukje data wordt voorafgegaan door een 2-, 3- of
 * 4-cijferige "AI"-code (bv. "01" = GTIN, "17" = vervaldatum), gevolgd door
 * óf een vaste lengte óf een variabele lengte die eindigt bij een GS-
 * scheidingsteken (ASCII 29) of het einde van de string.
 *
 * Dit is een CLEAN-ROOM implementatie van die publieke GS1-standaard — geen
 * overgenomen code van Juggluco's `BarCode.hpp`. Wel is Juggluco's
 * `sensoren.hpp` (`makeAirSensorindex()`) gebruikt om te VERIFIËREN welke
 * AI-codes en exacte veldlengtes CareSens Air specifiek gebruikt (Expiry=6,
 * PIN=6, SensorCode=16, Serial=12) — dat zijn feitelijke eigenschappen van
 * het barcode-formaat, geen auteursrechtelijk beschermde code-expressie.
 */

/** Eén GS1 Application Identifier-scheidingsteken (ASCII 29, "Group
 *  Separator") — markeert het einde van een variabele-lengte-veld. Sommige
 *  scanners/apps geven dit weer als de leestekens "^]" i.p.v. het echte
 *  byte — zie normalizeGroupSeparators() hieronder. */
private const val GROUP_SEPARATOR = '\u001D'

private data class AiFieldSpec(val length: Int?, val fixed: Boolean)

/** Alleen de AI's die voor CareSens Air relevant zijn — zie kdoc hierboven.
 *  Een onbekende AI in de barcode betekent simpelweg dat parseGs1Barcode()
 *  daar stopt (de rest van zo'n regel kunnen we toch niet betrouwbaar
 *  segmenteren zonder de lengte van dat onbekende veld te kennen). */
private val KNOWN_AIS: Map<String, AiFieldSpec> = mapOf(
    "01" to AiFieldSpec(14, fixed = true),    // GTIN
    "11" to AiFieldSpec(6, fixed = true),     // Productiedatum YYMMDD
    "17" to AiFieldSpec(6, fixed = true),     // Vervaldatum YYMMDD
    "10" to AiFieldSpec(null, fixed = false), // Lot/batch
    "21" to AiFieldSpec(null, fixed = false), // Serienummer
    "240" to AiFieldSpec(null, fixed = false), // PIN
    "250" to AiFieldSpec(null, fixed = false)  // Sensorcode (secundair serienummer)
)

data class ParsedGs1Barcode(
    val gtin: String? = null,
    val productionDate: String? = null,
    val expiry: String? = null,
    val lot: String? = null,
    val serial: String? = null,
    val pin: String? = null,
    val sensorCode: String? = null
)

private fun normalizeGroupSeparators(raw: String): String =
    raw.replace("^]", GROUP_SEPARATOR.toString())

private fun readAi(s: String, pos: Int): Pair<String, Int>? {
    for (len in intArrayOf(4, 3, 2)) {
        if (pos + len <= s.length) {
            val candidate = s.substring(pos, pos + len)
            if (KNOWN_AIS.containsKey(candidate)) return candidate to (pos + len)
        }
    }
    return null
}

private fun readValue(s: String, pos: Int, spec: AiFieldSpec): Pair<String, Int> {
    if (spec.fixed && spec.length != null) {
        val end = (pos + spec.length).coerceAtMost(s.length)
        return s.substring(pos, end) to end
    }
    var end = pos
    while (end < s.length && s[end] != GROUP_SEPARATOR) end++
    val value = s.substring(pos, end)
    val afterSeparator = if (end < s.length && s[end] == GROUP_SEPARATOR) end + 1 else end
    return value to afterSeparator
}

/** Parseert een ruwe GS1-barcodestring naar de erin gecodeerde velden — zie
 *  kdoc bovenaan dit bestand. Stopt zodra een onherkende AI wordt
 *  tegengekomen; wat er tot dan toe gevonden is, blijft geldig. */
fun parseGs1Barcode(raw: String): ParsedGs1Barcode {
    val normalized = normalizeGroupSeparators(raw)
    val fields = mutableMapOf<String, String>()
    var pos = 0
    while (pos < normalized.length) {
        // 31/07/2026 (editor, na eerste praktijktest tegen een echte
        // CareSens Air-sensor: parser gaf "missing or malformed expiry date
        // (AI 17)" terwijl de barcode zelf prima was) — Google's
        // barcodescanner geeft bij deze GS1-DataMatrix-code een GS-
        // scheidingsteken (ASCII 0x1D) VÓÓR de allereerste AI-code terug
        // (vermoedelijk de FNC1-vlag die aangeeft "dit is GS1-data").
        // readValue() hield al rekening met een GS ná een variabele-lengte-
        // waarde, maar deze leidende (en voor de zekerheid: eventuele
        // dubbele) GS'en werden nergens overgeslagen, waardoor readAi()
        // meteen op pos 0 struikelde en de HELE barcode ongelezen bleef.
        // Bevestigd op te lossen met een echte scan (zie
        // CareSensAirBarcodeTest-achtige verificatie in de PR-notities).
        while (pos < normalized.length && normalized[pos] == GROUP_SEPARATOR) pos++
        if (pos >= normalized.length) break
        val (ai, posAfterAi) = readAi(normalized, pos) ?: break
        val spec = KNOWN_AIS.getValue(ai)
        val (value, posAfterValue) = readValue(normalized, posAfterAi, spec)
        fields[ai] = value
        pos = posAfterValue
    }
    return ParsedGs1Barcode(
        gtin = fields["01"],
        productionDate = fields["11"],
        expiry = fields["17"],
        lot = fields["10"],
        serial = fields["21"],
        pin = fields["240"],
        sensorCode = fields["250"]
    )
}

/** Geldig gedecodeerd CareSens Air-scanresultaat — precies de velden die
 *  BleConnectionService/CareSensAirDriver later nodig hebben (koppel-stap
 *  2/3): sensorcode + serienummer om straks het juiste BLE-apparaat te
 *  herkennen, PIN voor de nog te bouwen "CareSense time sync"-handshake
 *  (zie Juggluco's GlucoseMeterGatt.java), en de vervaldatum voor de
 *  "End date"-regel in SensorInfoBlock (StatusScreen.kt). */
data class CareSensAirScanResult(
    val sensorCode: String,
    val serial: String,
    val pin: String,
    val expiryYyMmDd: String
) {
    /** YYMMDD -> epoch-ms van middernacht die dag, met een pragmatische
     *  2000+ eeuw-aanname (medische-productiedata, altijd 20xx). Retourneert
     *  null als het formaat toch niet klopt (zou hier al gevalideerd moeten
     *  zijn, maar geen crash riskeren op een edge-case barcode). */
    fun expiryEpochMs(): Long? {
        if (expiryYyMmDd.length != 6) return null
        val yy = expiryYyMmDd.substring(0, 2).toIntOrNull() ?: return null
        val mm = expiryYyMmDd.substring(2, 4).toIntOrNull() ?: return null
        val dd = expiryYyMmDd.substring(4, 6).toIntOrNull() ?: return null
        return runCatching {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(2000 + yy, mm - 1, dd)
            cal.timeInMillis
        }.getOrNull()
    }
}

sealed interface CareSensAirScanOutcome {
    data class Success(val result: CareSensAirScanResult) : CareSensAirScanOutcome
    data class InvalidBarcode(val reason: String) : CareSensAirScanOutcome
}

/**
 * Valideert de geparste velden tegen de exacte lengtes die CareSens Air
 * specifiek gebruikt (geverifieerd via Juggluco's makeAirSensorindex(), zie
 * kdoc bovenaan) — een barcode die WEL als GS1 parseert maar niet aan deze
 * lengtes voldoet is vermoedelijk van een ander product (bv. per ongeluk de
 * verpakking van een ander hulpmiddel gescand).
 */
fun decodeCareSensAirBarcode(raw: String): CareSensAirScanOutcome {
    val parsed = parseGs1Barcode(raw)
    val expiry = parsed.expiry
    val pin = parsed.pin
    val sensorCode = parsed.sensorCode
    val serial = parsed.serial
    return when {
        expiry == null || expiry.length != 6 ->
            CareSensAirScanOutcome.InvalidBarcode("missing or malformed expiry date (AI 17)")
        pin == null || pin.length != 6 ->
            CareSensAirScanOutcome.InvalidBarcode("missing or malformed PIN (AI 240)")
        sensorCode == null || sensorCode.length != 16 ->
            CareSensAirScanOutcome.InvalidBarcode("missing or malformed sensor code (AI 250)")
        serial == null || serial.length != 12 ->
            CareSensAirScanOutcome.InvalidBarcode("missing or malformed serial number (AI 21)")
        else -> CareSensAirScanOutcome.Success(
            CareSensAirScanResult(sensorCode = sensorCode, serial = serial, pin = pin, expiryYyMmDd = expiry)
        )
    }
}
