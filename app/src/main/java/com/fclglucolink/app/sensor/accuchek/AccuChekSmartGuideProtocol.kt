package com.fclglucolink.app.sensor.accuchek

import java.util.UUID
import kotlin.math.pow

/**
 * ============================================================================
 * FCLGlucoLink — Accu-Chek SmartGuide-protocol (RONDE 198, CGM Service-herbouw)
 * ============================================================================
 *
 * 01/10/2026 (editor, RONDE 198) — VERVANGT de RONDE 197-implementatie
 * volledig. RONDE 197 bouwde dit op het standaard Bluetooth SIG "Glucose
 * Profile" (GATT-service 0x1808, "GLS") — het profiel voor een LOSSE
 * bloedglucosemeter die alleen meet wanneer de gebruiker 'm gebruikt
 * (RACP-"pull" per losse meting). Dat bleek het VERKEERDE profiel: de
 * Accu-Chek SmartGuide is geen losse meter maar een echte CGM
 * (continue-glucosemonitor, zendt zelf automatisch elke ~5 minuten een
 * waarde, "push" i.p.v. "pull"). Verder onderzoek — xDrip+'s eigen
 * (open-source, GPL) `libglupro`-module
 * (`lwld/glucose/profile/GluProBle.java`,
 * github.com/NightscoutFoundation/xDrip) — wijst uit dat SmartGuide de
 * ECHTE Bluetooth SIG "Continuous Glucose Monitoring Service" (CGM Service,
 * 0x181F) implementeert, niet de GLS-meterservice. Zie README.md's Ronde
 * 198-sectie voor het volledige "waarom".
 *
 * Zuivere parse-/bouwlaag, geen Android-BLE-afhankelijkheden (zelfde opzet
 * als DexcomG7Protocol.kt t.o.v. DexcomG7Driver.kt) — maakt deze laag los
 * testbaar en houdt AccuChekSmartGuideDriver.kt zelf dun (GATT-
 * levenscyclus/coroutines). Logica (niet de Java-code zelf) overgenomen uit
 * `GluProBle.java`'s `InternalManager`-binnenklasse
 * (`isRequiredServiceSupported()`, `initialize()`, `readStaticParameters()`,
 * de `ContinuousGlucoseMeasurementDataCallback`/`CGMStatusDataCallback`/
 * `CGMSessionStartTimeDataCallback`/`CGMSessionRunTimeDataCallback`) en
 * `lwld/glucose/profile/packet/Status.java` (CGM Status-vlagbetekenissen),
 * herschreven in idiomatisch Kotlin — GEEN 1-op-1-transcriptie, en GEEN
 * afhankelijkheid van Nordic's `no.nordicsemi.android:ble` (xDrip's eigen
 * keuze) — deze codebase schrijft overal zelf een `BluetoothGattCallback`
 * (zie DexcomG6Driver.kt/DexcomG7Driver.kt/CareSensAirDriver.kt), dat
 * patroon zet AccuChekSmartGuideDriver.kt onveranderd voort.
 */
object AccuChekSmartGuideProtocol {

    // ===== Standaard Bluetooth SIG-UUID's — CGM Service + Device
    // Information Service. Alle UUID's geverifieerd tegen xDrip+'s eigen
    // `lwld/glucose/profile/config/Uuids.java`, stuk voor stuk standaard
    // Bluetooth SIG "assigned numbers" (patroon
    // 0000XXXX-0000-1000-8000-00805f9b34fb), dus generiek — niet
    // SmartGuide-specifiek, maar hier gebundeld omdat deze driver de enige
    // in FCLGlucoLink is die ze gebruikt. =====
    val SERVICE_CGM: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val SERVICE_DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    val CHAR_CGM_MEASUREMENT: UUID = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")
    val CHAR_RACP: UUID = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
    val CHAR_CGM_SPECIFIC_OPS: UUID = UUID.fromString("00002aac-0000-1000-8000-00805f9b34fb")
    val CHAR_CGM_FEATURE: UUID = UUID.fromString("00002aa8-0000-1000-8000-00805f9b34fb")
    val CHAR_CGM_STATUS: UUID = UUID.fromString("00002aa9-0000-1000-8000-00805f9b34fb")
    val CHAR_CGM_SESSION_START_TIME: UUID = UUID.fromString("00002aaa-0000-1000-8000-00805f9b34fb")
    val CHAR_CGM_SESSION_RUN_TIME: UUID = UUID.fromString("00002aab-0000-1000-8000-00805f9b34fb")

    val CHAR_FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CHAR_MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CHAR_SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val CHAR_HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val CHAR_SYSTEM_ID: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ===== IEEE 11073-20601 SFLOAT (16-bit) — gedeeld door CGM Measurement,
    // CGM Status, en in theorie elke andere CGM-characteristic die een
    // SFLOAT-veld draagt. =====

    /** Resultaat van [decodeSfloat] — bewust een sealed class i.p.v. een kale
     *  `Double?` (zoals de RONDE 197-GLS-decoder deed): deze driver moet
     *  -INFINITY apart van NaN/+INFINITY kunnen behandelen (zie
     *  [decodeSfloat]'s kdoc hieronder — -INFINITY betekent bij deze sensor
     *  specifiek "LO", geen sensorfout). */
    sealed class SfloatValue {
        data class Value(val value: Double) : SfloatValue()
        data object NaN : SfloatValue()
        data object PositiveInfinity : SfloatValue()
        data object NegativeInfinity : SfloatValue()
        data object Reserved : SfloatValue()
    }

    /**
     * IEEE 11073-20601 SFLOAT: onderste 12 bits = signed mantissa, bovenste
     * 4 bits = signed exponent, waarde = mantissa * 10^exponent. Speciale
     * mantissewaarden (vóór sign-extend gecontroleerd — 0x0800 e.v. worden
     * ná sign-extend negatieve getallen en zijn dan niet meer van een
     * "normale" negatieve mantisse te onderscheiden) betekenen NaN/
     * +INFINITY/-INFINITY/gereserveerd.
     *
     * 01/10/2026 (editor, RONDE 198) — anders dan de RONDE 197-GLS-decoder
     * rekent deze NIET om naar mg/dL via een kg/L-of-mol/L-vlag: binnen het
     * CGM-profiel staat de Glucose Concentration altijd al in mg/dL (zie
     * klasse-kdoc, geen eenheids-ambiguïteit zoals bij GLS).
     *
     * Bekende real-world uitzondering (xDrip+'s eigen GitHub-geschiedenis,
     * commit "GluPro: fix race condition",
     * github.com/NightscoutFoundation/xDrip/discussions/4296): deze sensor
     * stuurt soms een letterlijke -INFINITY-SFLOAT om "LO" (< 40 mg/dL,
     * Accu-Cheks eigen gedocumenteerde gedrag) te betekenen, GEEN
     * sensorfout — zie [AccuChekSmartGuideProtocol.LOW_FLOOR_MGDL]'s kdoc
     * voor hoe de aanroeper dat vertaalt.
     */
    fun decodeSfloat(lo: Byte, hi: Byte): SfloatValue {
        val raw = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
        val rawMantissa = raw and 0x0FFF
        return when (rawMantissa) {
            0x07FF -> SfloatValue.NaN
            0x0800 -> SfloatValue.PositiveInfinity
            0x0801 -> SfloatValue.NegativeInfinity
            0x0802 -> SfloatValue.Reserved
            else -> {
                val mantissa = if (rawMantissa >= 0x0800) rawMantissa - 0x1000 else rawMantissa
                val rawExponent = (raw shr 12) and 0x0F
                val exponent = if (rawExponent >= 0x08) rawExponent - 0x10 else rawExponent
                SfloatValue.Value(mantissa * 10.0.pow(exponent))
            }
        }
    }

    /**
     * 01/10/2026 (editor, RONDE 198) — Accu-Cheks eigen gedocumenteerde "LO"-
     * grens (< 40 mg/dL meet de sensor zelf niet exact meer) — een
     * -INFINITY-SFLOAT in een CGM Measurement wordt hiermee omgezet naar een
     * bruikbare, net-onder-de-grens waarde i.p.v. als onbruikbaar/kapot
     * overgeslagen te worden (zie [decodeSfloat]'s kdoc). GEEN bestaande
     * codebase-conventie voor "onmeetbaar laag" gevonden bij G6/G7 (die
     * sturen zelf nooit een vergelijkbare sentinel-waarde) — dit is dus een
     * nieuwe, op zichzelf staande keuze voor deze driver, te herzien zodra
     * een live-test laat zien wat de gebruiker hier het liefst ziet.
     */
    const val LOW_FLOOR_MGDL: Double = 39.0

    /** Vertaalt een [SfloatValue] naar een bruikbare mg/dL-waarde, of `null`
     *  als er geen bruikbare meting in zit (NaN/+INFINITY/gereserveerd —
     *  zie [decodeSfloat]'s kdoc, -INFINITY wordt hier NIET als onbruikbaar
     *  behandeld, zie [LOW_FLOOR_MGDL]). ALLEEN voor de Glucose
     *  Concentration-veld (zie [rawOrNull]'s kdoc voor waarom trend/
     *  kwaliteit dit NIET mogen gebruiken). */
    fun sfloatToGlucoseMgdl(value: SfloatValue): Double? = when (value) {
        is SfloatValue.Value -> value.value
        SfloatValue.NegativeInfinity -> LOW_FLOOR_MGDL
        SfloatValue.NaN, SfloatValue.PositiveInfinity, SfloatValue.Reserved -> null
    }

    /**
     * 01/10/2026 (editor, RONDE 200, bugfix na live-test) — [sfloatToGlucoseMgdl]
     * is GLUCOSE-CONCENTRATION-specifiek: de -INFINITY -> [LOW_FLOOR_MGDL]
     * (39.0)-substitutie is een business-regel die alleen voor een
     * glucosewaarde zinvol is. De CGM Trend Information (mg/dL/min) en CGM
     * Quality (percentage)-velden gebruikten in RONDE 198/199 PER ONGELUK
     * diezelfde functie — gevolg: een letterlijke -INFINITY-trend/-kwaliteit
     * werd stilletjes 39.0, een onzinnige waarde voor die twee velden. Deze
     * functie haalt in plaats daarvan de kale numerieke waarde eruit, en
     * behandelt NaN/+INFINITY/-INFINITY/gereserveerd allemaal gelijk als
     * "geen bruikbare waarde" (`null`) — geen van IEEE 11073's speciale
     * SFLOAT-waarden heeft een zinnige letterlijke betekenis als trend- of
     * kwaliteitsgetal. */
    fun SfloatValue.rawOrNull(): Double? = (this as? SfloatValue.Value)?.value

    // ===== CGM Status (0x2AA9, gelezen) + Sensor Status Annunciation (3
    // octetten, zowel hierin als conditioneel in elke CGM Measurement) —
    // vlagbetekenissen 1-op-1 overgenomen uit xDrip+'s eigen
    // `lwld/glucose/profile/packet/Status.java`. =====

    /** Eén gedecodeerde statusvlaggenset (Status-octet + Cal/Temp-octet +
     *  Warning-octet, samen 3 bytes — "Sensor Status Annunciation" per de
     *  CGM-spec). Elk veld hieronder is een los, onafhankelijk bit. */
    data class CgmStatusFlags(
        val sessionStopped: Boolean,
        val deviceBatteryLow: Boolean,
        val sensorTypeIncorrect: Boolean,
        val sensorMalfunction: Boolean,
        val deviceSpecificAlert: Boolean,
        val generalDeviceFault: Boolean,
        val timeSyncRequired: Boolean,
        val calibrationNotAllowed: Boolean,
        val calibrationRecommended: Boolean,
        val calibrationRequired: Boolean,
        val sensorTemperatureTooHigh: Boolean,
        val sensorTemperatureTooLow: Boolean,
        val resultLowerThanDeviceCanProcess: Boolean,
        val resultHigherThanDeviceCanProcess: Boolean,
        val sensorRateOfDecreaseExceeded: Boolean,
        val sensorRateOfIncreaseExceeded: Boolean,
        val deviceResultLowerThanPatientLowLevel: Boolean,
        val deviceResultHigherThanPatientHighLevel: Boolean
    ) {
        /** `true` als er GEEN enkele vlag gezet is — gebruikt om een
         *  "alles ok"-rij op het statusscherm te tonen i.p.v. een lege lijst. */
        val isAllClear: Boolean
            get() = !sessionStopped && !deviceBatteryLow && !sensorTypeIncorrect && !sensorMalfunction &&
                !deviceSpecificAlert && !generalDeviceFault && !timeSyncRequired && !calibrationNotAllowed &&
                !calibrationRecommended && !calibrationRequired && !sensorTemperatureTooHigh &&
                !sensorTemperatureTooLow && !resultLowerThanDeviceCanProcess &&
                !resultHigherThanDeviceCanProcess && !sensorRateOfDecreaseExceeded &&
                !sensorRateOfIncreaseExceeded && !deviceResultLowerThanPatientLowLevel &&
                !deviceResultHigherThanPatientHighLevel
    }

    /** Decodeert de 3-byte "Sensor Status Annunciation" (Status-octet,
     *  Cal/Temp-octet, Warning-octet — exact de bit-indeling uit xDrip+'s
     *  `Status.java`). Gebruikt zowel door [parseCgmStatus] (de gepolde
     *  CGM Status-characteristic) als door [parseCgmMeasurement] (de
     *  per-meting variant binnen een notificatie). */
    fun decodeStatusFlags(statusByte: Int, calTempByte: Int, warningByte: Int): CgmStatusFlags = CgmStatusFlags(
        sessionStopped = statusByte and 0x01 != 0,
        deviceBatteryLow = statusByte and 0x02 != 0,
        sensorTypeIncorrect = statusByte and 0x04 != 0,
        sensorMalfunction = statusByte and 0x08 != 0,
        deviceSpecificAlert = statusByte and 0x10 != 0,
        generalDeviceFault = statusByte and 0x20 != 0,
        timeSyncRequired = calTempByte and 0x01 != 0,
        calibrationNotAllowed = calTempByte and 0x02 != 0,
        calibrationRecommended = calTempByte and 0x04 != 0,
        calibrationRequired = calTempByte and 0x08 != 0,
        sensorTemperatureTooHigh = calTempByte and 0x10 != 0,
        sensorTemperatureTooLow = calTempByte and 0x20 != 0,
        resultLowerThanDeviceCanProcess = warningByte and 0x01 != 0,
        resultHigherThanDeviceCanProcess = warningByte and 0x02 != 0,
        sensorRateOfDecreaseExceeded = warningByte and 0x04 != 0,
        sensorRateOfIncreaseExceeded = warningByte and 0x08 != 0,
        deviceResultLowerThanPatientLowLevel = warningByte and 0x10 != 0,
        deviceResultHigherThanPatientHighLevel = warningByte and 0x20 != 0
    )

    /** CGM Status (0x2AA9) — layout per Bluetooth CGM-spec: byte 0-1 = Time
     *  Offset (uint16 LE, minuten sinds sessiestart), byte 2-4 = de 3-byte
     *  Sensor Status Annunciation (zie [decodeStatusFlags]). */
    data class CgmStatus(val timeOffsetMinutes: Int, val flags: CgmStatusFlags)

    fun parseCgmStatus(bytes: ByteArray): CgmStatus? {
        if (bytes.size < 5) return null
        val timeOffsetMinutes = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val flags = decodeStatusFlags(
            bytes[2].toInt() and 0xFF,
            bytes[3].toInt() and 0xFF,
            bytes[4].toInt() and 0xFF
        )
        return CgmStatus(timeOffsetMinutes, flags)
    }

    // ===== CGM Measurement (0x2AA7, notificatie). =====

    // 01/10/2026 (editor, RONDE 200, bugfix na live-test — VERVANGT de RONDE
    // 198-aanname) — de RONDE 198-vlaggentabel/veldvolgorde bleek de
    // oorzaak van de volstrekt onzinnige trend-/kwaliteitswaarden uit de
    // live-log (bv. trend=77800.0, kwaliteit=-5290000%): RONDE 198 las
    // trend+kwaliteit VÓÓR de annunciation-octetten, en gebruikte bovendien
    // verkeerde vlagbits (0x08/0x20 gecombineerd als "één 3-byte-blok") voor
    // die octetten. Nu geverifieerd tegen Nordic Semiconductor's officiële,
    // open-source `no.nordicsemi.android.ble.common`-bibliotheek (de
    // Android-BLE-Library die xDrip+'s eigen `GluProBle.java` zelf ook
    // gebruikt voor exact deze parse, zie
    // `ContinuousGlucoseMeasurementDataCallback.java::onDataReceived()`,
    // github.com/NordicSemiconductor/Android-BLE-Library) — de ECHTE
    // veldvolgorde na Time Offset is: (1) Sensor Status Annunciation-
    // octetten, ÉÉN voor ÉÉN conditioneel op hun EIGEN vlagbit (niet als
    // gecombineerd blok), (2) dan pas CGM Trend Information, (3) dan pas
    // CGM Quality. Drie onafhankelijke octet-vlaggen i.p.v. twee:
    // bit 0x20 = eerste octet (sessie/batterij/type/malfunction/alert/fault
    // — wat deze driver [decodeStatusFlags]'s `statusByte`-parameter noemt),
    // bit 0x40 = tweede octet (tijdsync/kalibratie/temperatuur —
    // `calTempByte`), bit 0x80 = derde octet (resultaat-bereik/rate-of-
    // change — `warningByte`). Blijft, zoals RONDE 198/199 al eerlijk
    // meldden, een REFERENTIE-gebaseerde correctie — niet 1-op-1 bevestigd
    // tegen een eigen rauwe hex-capture van DEZE sensor (zie de nieuwe
    // raw-hex-logregel in AccuChekSmartGuideDriver.kt's
    // `handleCgmMeasurement()` voor de volgende diagnosepas, plus de
    // plausibiliteitsklem hieronder als vangnet zolang dat niet bevestigd
    // is).
    private const val FLAG_TREND_INFO_PRESENT = 0x01
    private const val FLAG_QUALITY_PRESENT = 0x02
    private const val FLAG_WARNING_OCTET_PRESENT = 0x20 // eerste annunciation-octet (statusByte-inhoud)
    private const val FLAG_CAL_TEMP_OCTET_PRESENT = 0x40 // tweede annunciation-octet (calTempByte-inhoud)
    private const val FLAG_SENSOR_STATUS_OCTET_PRESENT = 0x80 // derde annunciation-octet (warningByte-inhoud)

    /** Eén geparste CGM Measurement-record (0x2AA7). */
    data class CgmMeasurement(
        val timestampMs: Long,
        val glucoseMgdl: Double?,
        val trendMgdlPerMin: Double?,
        val qualityPercent: Double?,
        val statusFlags: CgmStatusFlags?
    )

    /**
     * Parseert één CGM Measurement-notificatie — Bluetooth SIG-standaardlayout,
     * ECHTE veldvolgorde (zie de vlaggen-kdoc hierboven): byte 0 = Size
     * (totale lengte incl. deze byte, puur voor validatie), byte 1 = Flags
     * (1 octet), bytes 2-3 = Glucose Concentration (SFLOAT, al in mg/dL —
     * zie [decodeSfloat]'s kdoc), bytes 4-5 = Time Offset (uint16 LE,
     * minuten sinds sessiestart), DAARNA conditioneel, in DEZE volgorde:
     * (1) de Sensor Status Annunciation-octetten (elk los, op hun eigen
     * vlagbit: 0x20/0x40/0x80 — zie [decodeStatusFlags]), (2) CGM Trend
     * Information (SFLOAT, mg/dL/min, via [SfloatValue.rawOrNull] — GEEN
     * [sfloatToGlucoseMgdl], zie die functie's kdoc), (3) CGM Quality
     * (SFLOAT, percentage, zelfde behandeling als trend).
     *
     * 01/10/2026 (editor, RONDE 200) — géén plausibiliteitsklem HIER: deze
     * laag blijft een zuivere byte-vertaling (zie klasse-kdoc, "geen
     * Android-BLE-afhankelijkheden"/geen logging hier). De plausibiliteits-
     * klem + raw-hex-diagnostiek zit bewust in AccuChekSmartGuideDriver.kt's
     * `handleCgmMeasurement()`, de enige plek die al [DiagnosticFileLogger]
     * gebruikt.
     *
     * [sensorStartTimeMs] = epoch-tijdstip waarop de sensorsessie begon (zie
     * [CgmStatus]/AccuChekSmartGuideDriver.kt's `sensorStartTimeMs`) — samen
     * met Time Offset levert dat [CgmMeasurement.timestampMs] op.
     */
    fun parseCgmMeasurement(bytes: ByteArray, sensorStartTimeMs: Long): CgmMeasurement? {
        if (bytes.size < 6) return null
        val size = bytes[0].toInt() and 0xFF
        if (size > bytes.size) return null // kennelijk afgekapte notificatie
        val flags = bytes[1].toInt() and 0xFF

        val glucoseMgdl = sfloatToGlucoseMgdl(decodeSfloat(bytes[2], bytes[3]))
        val timeOffsetMinutes = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
        val timestampMs = sensorStartTimeMs + timeOffsetMinutes * 60_000L

        var cursor = 6

        // (1) Annunciation-octetten — elk los, vóór trend/kwaliteit (zie
        // kdoc hierboven — dit is de kern van de RONDE 200-volgordefix).
        var warningOctet: Int? = null
        var calTempOctet: Int? = null
        var sensorStatusOctet: Int? = null
        if (flags and FLAG_WARNING_OCTET_PRESENT != 0 && cursor < bytes.size) {
            warningOctet = bytes[cursor].toInt() and 0xFF
            cursor += 1
        }
        if (flags and FLAG_CAL_TEMP_OCTET_PRESENT != 0 && cursor < bytes.size) {
            calTempOctet = bytes[cursor].toInt() and 0xFF
            cursor += 1
        }
        if (flags and FLAG_SENSOR_STATUS_OCTET_PRESENT != 0 && cursor < bytes.size) {
            sensorStatusOctet = bytes[cursor].toInt() and 0xFF
            cursor += 1
        }
        val statusFlags = if (warningOctet != null || calTempOctet != null || sensorStatusOctet != null) {
            decodeStatusFlags(warningOctet ?: 0, calTempOctet ?: 0, sensorStatusOctet ?: 0)
        } else {
            null
        }

        // (2) CGM Trend Information.
        var trendMgdlPerMin: Double? = null
        if (flags and FLAG_TREND_INFO_PRESENT != 0) {
            if (cursor + 2 > bytes.size) return CgmMeasurement(timestampMs, glucoseMgdl, null, null, statusFlags)
            trendMgdlPerMin = decodeSfloat(bytes[cursor], bytes[cursor + 1]).rawOrNull()
            cursor += 2
        }

        // (3) CGM Quality.
        var qualityPercent: Double? = null
        if (flags and FLAG_QUALITY_PRESENT != 0) {
            if (cursor + 2 > bytes.size) return CgmMeasurement(timestampMs, glucoseMgdl, trendMgdlPerMin, null, statusFlags)
            qualityPercent = decodeSfloat(bytes[cursor], bytes[cursor + 1]).rawOrNull()
            cursor += 2
        }

        return CgmMeasurement(timestampMs, glucoseMgdl, trendMgdlPerMin, qualityPercent, statusFlags)
    }

    // ===== CGM Specific Ops Control Point (0x2AAC) — communicatie-interval
    // expliciet instellen (betrouwbaarheidseis deze ronde, zie klasse-kdoc
    // en README.md's Ronde 198-sectie) + respons-decoding. Logica overgenomen
    // uit `GluProBle.java`'s `readOrChangeReportingPeriod(int period)`. =====

    private const val OPCODE_SET_COMMUNICATION_INTERVAL = 0x01
    private const val OPCODE_GET_COMMUNICATION_INTERVAL = 0x02
    private const val OPCODE_COMMUNICATION_INTERVAL_RESPONSE = 0x03
    const val OPCODE_RESPONSE_CODE = 0x1C
    const val RESPONSE_CODE_SUCCESS = 0x01

    /** Stuurt "Set CGM Communication Interval" — operand in HELE MINUTEN
     *  (`0xFF` = snelst mogelijke interval, `0x00` = periodiek uitschakelen).
     *  Deze driver gebruikt 'm uitsluitend om 5 (minuten) te forceren — zie
     *  klasse-kdoc. */
    fun buildSetCommunicationIntervalCommand(minutes: Int): ByteArray =
        byteArrayOf(OPCODE_SET_COMMUNICATION_INTERVAL.toByte(), minutes.coerceIn(0, 0xFF).toByte())

    /** Stuurt "Get CGM Communication Interval" — geen operand. */
    fun buildGetCommunicationIntervalCommand(): ByteArray =
        byteArrayOf(OPCODE_GET_COMMUNICATION_INTERVAL.toByte())

    /** Resultaat van een indicatie op de CGM Specific Ops Control Point. */
    sealed class CgmOpsResponse {
        /** Antwoord op "Get Communication Interval" (opcode 0x03): het
         *  huidige interval in minuten. */
        data class CommunicationInterval(val minutes: Int) : CgmOpsResponse()

        /** Generieke "Response Code" (opcode 0x1C): [requestOpCode] +
         *  [responseCode] (0x01 = success) — zelfde soort envelop als RACP's
         *  eigen Response Code hieronder. */
        data class ResponseCode(val requestOpCode: Int, val responseCode: Int) {
            val isSuccess: Boolean get() = responseCode == RESPONSE_CODE_SUCCESS
        }
    }

    /** Parseert een indicatie op 0x2AAC — `null` als het bericht te kort is
     *  of een onbekende opcode draagt. Byte-offsets voor de Response Code-
     *  variant (opcode, dan request-opcode, dan response-code) zijn
     *  overgenomen van hoe deze driver RACP's eigen Response Code elders al
     *  decodeert (zelfde envelopvorm) — NIET tegen een echte sensor-capture
     *  bevestigd, zie finale rapportage. */
    fun parseCgmOpsResponse(bytes: ByteArray): Any? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xFF) {
            OPCODE_COMMUNICATION_INTERVAL_RESPONSE -> {
                if (bytes.size < 2) return null
                CgmOpsResponse.CommunicationInterval(bytes[1].toInt() and 0xFF)
            }
            OPCODE_RESPONSE_CODE -> {
                if (bytes.size < 3) return null
                CgmOpsResponse.ResponseCode(bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
            }
            else -> null
        }
    }

    // ===== Record Access Control Point (RACP, 0x2A52) — binnen de CGM
    // Service gebruikt voor backfill/historie-opvraag na een disconnect
    // (xDrip+'s `backFill()`/`reportStoredRecordsFromRange()` in
    // `GluProBle.java`) — ZELFDE byte-envelop als het GLS-profiel (RACP is
    // standaard, los van welke service 'm host), dus de RONDE 197-logica
    // blijft hier 1-op-1 bruikbaar (alleen hernoemd in context). =====

    private const val RACP_OPCODE_REPORT_STORED_RECORDS = 1
    private const val RACP_OPERATOR_GREATER_THAN_OR_EQUAL = 3
    private const val RACP_FILTER_TYPE_SEQUENCE_NUMBER = 1
    private const val RACP_OPCODE_RESPONSE_CODE = 6

    /** RACP "Report Stored Records" >= [sequenceNumber]. */
    fun buildRacpReportRecordsGreaterOrEqual(sequenceNumber: Int): ByteArray {
        val seq = sequenceNumber.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            RACP_OPCODE_REPORT_STORED_RECORDS.toByte(),
            RACP_OPERATOR_GREATER_THAN_OR_EQUAL.toByte(),
            RACP_FILTER_TYPE_SEQUENCE_NUMBER.toByte(),
            (seq and 0xFF).toByte(),
            ((seq shr 8) and 0xFF).toByte()
        )
    }

    data class RacpResponse(val requestOpCode: Int, val responseCode: Int) {
        val isSuccess: Boolean get() = responseCode == 1
        val isNoRecordsFound: Boolean get() = responseCode == 6
    }

    fun parseRacpResponse(bytes: ByteArray): RacpResponse? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != RACP_OPCODE_RESPONSE_CODE) return null
        val requestOpCode = bytes[2].toInt() and 0xFF
        val responseCode = bytes[3].toInt() and 0xFF
        return RacpResponse(requestOpCode, responseCode)
    }

    // ===== Device Information Service — simpele tekst/uint-uitlezingen. =====

    fun parseUtf8String(bytes: ByteArray): String? = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() }
}
