package com.fclglucolink.app.sensor.dexcomg6

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6 BLE-protocol (UUID's + pakket-op-/decodering)
 * ============================================================================
 *
 * 08/08/2026 (editor, RONDE 55) — geport van xDrip+'s `g5model`-package
 * (`BluetoothServices.java`, `BaseMessage.java` + alle losse `*TxMessage`/
 * `*RxMessage`-klassen). Dit bestand bevat GEEN verbindingslogica (dat is
 * DexcomG6Driver.kt) — puur de bytes-in/bytes-uit-vertaling, precies zoals
 * CareSensAirGattProtocol.kt dat voor CareSens Air doet.
 *
 * Elk pakket: opcode(1 byte) + payload + CRC16(2 bytes, little-endian, over
 * alles daarvoor) — zie DexcomG6Crypto.crc16() (LET OP: op 08/08/2026,
 * RONDE 56, gecorrigeerd van een verkeerde bit-variant naar de echte
 * CCITT-16-tabel-variant, zie DexcomG6Crypto.kt's kdoc). Multi-byte velden
 * zijn little-endian (`ByteOrder.LITTLE_ENDIAN`).
 *
 * 08/08/2026 (editor, RONDE 56 — CORRECTIE op RONDE 55) — de vorige ronde
 * schreef hier "de G6-kalibratiecode is bewust niet geport, kalibratie
 * gebeurt al in CalibrationEngine.kt". Dat klopt voor de vingerprik-
 * kalibratie-terugstuurstap (CalibrateTxMessage — inderdaad niet nodig,
 * blijft weggelaten), maar NIET voor de per-sensor "sensor-code" die bij
 * het starten van een NIEUWE G6-sensor hoort (4 cijfers op de doos, xDrip+'s
 * `G6CalibrationParameters`/`SessionStartTxMessage`-met-code-pad) — dat is
 * geen kalibratie in de FCLGlucoLink-zin, maar een fabrieksparameter die de
 * transmitter nodig heeft om ruwe signalen sowieso naar mg/dL te kunnen
 * omrekenen. Zie DexcomG6CalibrationCode.kt voor de volledige uitleg en de
 * geporte tabel; [buildSessionStart] hieronder ondersteunt dat pad nu wél.
 *
 * 08/08/2026 (editor, RONDE 56 — TWEEDE CORRECTIE) — [COMMUNICATION]
 * (F8083533) bleek, na het uitpluizen van xDrip+'s daadwerkelijke
 * lees-/schrijfaanroepen (niet alleen de UUID-constante-lijst in
 * `BluetoothServices.java`, die alle bekende UUID's opsomt ongeacht of ze
 * ooit gebruikt worden), NERGENS in xDrip+'s actieve G5/G6-verkeer
 * voor te komen — sessie starten/stoppen, glucose opvragen, batterij/
 * versie-verzoeken lopen ALLEMAAL via [CONTROL] (F8083534). RONDE 55's
 * driver schreef/las dit ten onrechte via COMMUNICATION, wat tegen een
 * echte transmitter nooit iets teruggegeven zou hebben — zie
 * DexcomG6Driver.kt's kdoc voor de volledige correctie. COMMUNICATION blijft
 * hieronder staan als documentatie/volledigheid (voor het geval een
 * toekomstige firmwarevariant 'm ooit wél gebruikt), maar wordt door de
 * driver niet meer aangesproken.
 */
object DexcomG6Protocol {

    // ---- BLE-service/characteristic-UUID's — letterlijk overgenomen van
    // xDrip+'s BluetoothServices.java. ----
    val CGM_SERVICE: UUID = UUID.fromString("F8083532-849E-531C-C594-30F1F86A4EA5")

    /** 08/08/2026 (editor, RONDE 56) — in xDrip+'s bronlijst aanwezig, maar
     *  in de praktijk ONGEBRUIKT — zie klasse-kdoc. Niet aangesproken door
     *  DexcomG6Driver.kt. */
    val COMMUNICATION: UUID = UUID.fromString("F8083533-849E-531C-C594-30F1F86A4EA5")

    /** 08/08/2026 (editor, RONDE 56) — de ECHTE data-characteristic: sessie
     *  starten/stoppen, glucose/EGlucose-verzoek+antwoord, batterij- en
     *  versie-verzoek+antwoord lopen hier allemaal overheen (mirror van
     *  xDrip+'s `doGetData()`/`checkVersionAndBattery()`). */
    val CONTROL: UUID = UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5")
    val AUTHENTICATION: UUID = UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5")
    val BACKFILL: UUID = UUID.fromString("F8083536-849E-531C-C594-30F1F86A4EA5")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private fun appendCrc(buffer: ByteBuffer): ByteArray {
        val withoutCrc = buffer.array()
        val crc = DexcomG6Crypto.crc16(withoutCrc, 0, withoutCrc.size - 2)
        withoutCrc[withoutCrc.size - 2] = crc[0]
        withoutCrc[withoutCrc.size - 1] = crc[1]
        return withoutCrc
    }

    private fun checkCrc(packet: ByteArray): Boolean {
        if (packet.size < 3) return false
        val crc = DexcomG6Crypto.crc16(packet, 0, packet.size - 2)
        return crc[0] == packet[packet.size - 2] && crc[1] == packet[packet.size - 1]
    }

    // ============================================================
    // Uitgaand (TX) — telkens een kant-en-klare ByteArray om te schrijven.
    // ============================================================

    /**
     * opcode 0x01 — start van de auth-handshake: eigen 8-byte token + één
     * afsluitende byte.
     *
     * 09/08/2026 (editor, RONDE 62, CRITIEKE FIX — live-test met correct
     * gefilterde logcat, "FCLGlucoLink"+"BtGatt" samen, liet voor het eerst
     * het VOLLEDIGE plaatje zien: connect lukt, CCCD-indicatie op
     * Authentication lukt ("CCCD write ok"), déze AuthRequestTx-schrijfactie
     * lukt zelf ook op ATT-niveau ("write ok") — maar de transmitter
     * antwoordt VERVOLGENS NOOIT (geen enkele "auth status"/challenge-
     * logregel, geen enkele onCharacteristicChanged voor Authentication) en
     * verbreekt de verbinding binnen ~100-800ms (status 19), stelselmatig,
     * bij elke koppelpoging. Een write die op BLE/ATT-niveau slaagt maar
     * waar de transmitter's EIGEN protocol-logica nooit op reageert, wijst
     * op een ongeldige PAYLOAD, niet op een verbindingsprobleem.
     *
     * Was hier: laatste byte = `slot` met default 0 (dus letterlijk 0x00).
     * Verificatie tegen de door de gebruiker aangeleverde ECHTE xDrip+-bron
     * (`g5model/AuthRequestTxMessage.java`) laat zien dat deze laatste byte
     * NOOIT 0x00 is — de klasse kent slechts twee mogelijke vaste waarden:
     * `endByteStd = 0x02` (standaardpad, gebruikt wanneer NIET op Wear EN
     * geen "immediate bonding"-voorkeur staat — exact ons scenario) of
     * `endByteAlt = 0x01` (alternatief pad). `Ob1G5StateMachine.java` regel
     * 346 roept `new AuthRequestTxMessage(getTokenSize(), usingAlt())` aan
     * — met `usingAlt()` standaard false in een normale, niet-Wear-
     * installatie zonder "immediate bonding" aangezet, dus `endByteStd` =
     * **0x02**. 0x00 is in de hele xDrip+-broncode nergens een geldige
     * waarde voor dit veld — een transmitter die zo'n pakket ontvangt kan
     * 'm niet als geldige AuthRequestTx herkennen, verklaart precies waarom
     * er nooit een AuthChallengeRx terugkwam.
     *
     * Nu: default naar 0x02 (het standaardpad, mirror van `endByteStd`),
     * expliciet als losse constante i.p.v. een generieke "slot"-parameter —
     * die naam suggereerde ten onrechte een vrij te kiezen slotnummer,
     * terwijl het in werkelijkheid een vaste protocol-vlag is met precies
     * twee geldige waarden.
     */
    private const val AUTH_REQUEST_END_BYTE_STANDARD: Int = 0x02
    fun buildAuthRequest(token: ByteArray, endByte: Int = AUTH_REQUEST_END_BYTE_STANDARD): ByteArray {
        require(token.size == 8)
        val buf = ByteBuffer.allocate(1 + 8 + 1).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x01)
        buf.put(token)
        buf.put(endByte.toByte())
        return buf.array() // geen CRC op dit bericht — xDrip+ voegt hier ook geen appendCRC() toe.
    }

    /** opcode 0x04 — antwoord op de transmitter's AuthChallengeRxMessage,
     *  bevat de door ons berekende 8-byte hash. Géén CRC (9 bytes totaal,
     *  mirror van xDrip+'s BaseAuthChallengeTxMessage). */
    fun buildAuthChallengeResponse(challengeHash: ByteArray): ByteArray {
        require(challengeHash.size == 8)
        val buf = ByteBuffer.allocate(1 + 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x04)
        buf.put(challengeHash)
        return buf.array()
    }

    /** opcode 0x07 — vraagt de transmitter een OS-bond te initiëren (die
     *  zelf een pairing-verzoek naar Android stuurt); alleen nodig als
     *  AuthStatusRxMessage.bonded==false na een geslaagde auth. */
    fun buildBondRequest(): ByteArray = byteArrayOf(0x07)

    /** 08/08/2026 (editor, RONDE 56) — opcode 0x06, "houd de verbinding
     *  open"-signaal, mirror van xDrip+'s KeepAliveTxMessage. GEEN CRC (2
     *  bytes totaal: opcode + tijd-in-seconden). Verstuurd via de
     *  Authentication-characteristic (zelfde als de auth-berichten), zie
     *  DexcomG6Driver.kt's periodieke keep-alive-taak. */
    fun buildKeepAlive(seconds: Int = 60): ByteArray =
        byteArrayOf(0x06, seconds.toByte())

    /**
     * opcode 0x26 — sessie starten. `dexTime` is de transmitter's eigen
     * interne kloktijd (seconden sinds transmitter-activatie, NIET
     * kalender-Unix-tijd), `startTimeUnixSec` een gewone Unix-timestamp in
     * seconden.
     *
     * 08/08/2026 (editor, RONDE 56) — [sensorCode] toegevoegd: de 4-cijferige
     * per-sensor fabriekscode (zie DexcomG6CalibrationCode.kt), NIET de
     * vingerprik-kalibratie (die blijft terecht weggelaten). Drie
     * byte-indelingen, exact zoals xDrip+'s `SessionStartTxMessage`:
     *  - sensorCode == null: 11 bytes (opcode+dexTime+startTime+CRC) — voor
     *    een sessie die al loopt hoeft er normaliter niets gestuurd te
     *    worden (zie DexcomG6Driver.kt), maar dit pad blijft beschikbaar
     *    voor een expliciete "opnieuw proberen"-actie zonder code.
     *  - geldige code, "null-code" (bv. "0000", paramB==0): 13 bytes — GEEN
     *    paramA/paramB, wel de afsluitende 0x0000.
     *  - geldige code, normale code: 17 bytes — MET paramA/paramB (elk een
     *    16-bit short) + afsluitende 0x0000.
     * Gooit een IllegalArgumentException bij een onbekende/ongeldige code —
     * de aanroeper moet vooraf DexcomG6CalibrationCode.checkCode() gebruiken
     * (zie ui/DexcomG6NewSensorScreen.kt), dit is een laatste vangnet.
     */
    fun buildSessionStart(dexTime: Int, startTimeUnixSec: Int, sensorCode: String? = null): ByteArray {
        val usingCode = sensorCode != null
        val params = sensorCode?.let { DexcomG6CalibrationCode.lookup(it) }
        if (params != null && !params.isValid) {
            throw IllegalArgumentException("Invalid G6 sensor code in buildSessionStart: $sensorCode")
        }
        val includeParamAB = params != null && !params.isNullCode
        val length = 1 + 4 + 4 + (if (includeParamAB) 4 else 0) + (if (usingCode) 2 else 0) + 2
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x26)
        buf.putInt(dexTime)
        buf.putInt(startTimeUnixSec)
        if (includeParamAB) {
            buf.putShort(params!!.paramA.toShort())
            buf.putShort(params.paramB.toShort())
        }
        if (usingCode) {
            buf.putShort(0x0000)
        }
        return appendCrc(buf)
    }

    /** opcode 0x28 — sessie stoppen (bv. bij het loskoppelen van deze
     *  sensor in de app, vóór het koppelen van een nieuwe). */
    fun buildSessionStop(dexTime: Int): ByteArray {
        val buf = ByteBuffer.allocate(1 + 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x28)
        buf.putInt(dexTime)
        return appendCrc(buf)
    }

    /** opcode 0x50 — historische metingen opvragen tussen twee dex-tijden
     *  (na een verbindingsgat), mirror van xDrip+'s BackFillTxMessage. */
    fun buildBackfillRequest(startDexTime: Int, endDexTime: Int): ByteArray {
        val buf = ByteBuffer.allocate(1 + 1 + 1 + 1 + 4 + 4 + 6 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x50)
        buf.put(0x5)
        buf.put(0x2)
        buf.put(0x0)
        buf.putInt(startDexTime)
        buf.putInt(endDexTime)
        buf.put(ByteArray(6))
        return appendCrc(buf)
    }

    /** 08/08/2026 (editor, RONDE 56) — opcode 0x30, VRAAGT een glucosemeting
     *  op (het antwoord, opcode 0x31, komt pas ná dit verzoek — de G6 duwt
     *  niet ongevraagd data, zie DexcomG6Driver.kt's kdoc). 3 bytes:
     *  opcode+CRC, mirror van xDrip+'s GlucoseTxMessage. */
    fun buildGlucoseRequest(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x30)
        return appendCrc(buf)
    }

    /** 08/08/2026 (editor, RONDE 56) — opcode 0x4e, vraagt de "verrijkte"
     *  glucosemelding (met voorspelling) op, antwoord opcode 0x4f. Momenteel
     *  ongebruikt (DexcomG6Driver.kt gebruikt het gewone [buildGlucoseRequest]
     *  pad) — bewaard voor eventueel later gebruik, mirror van xDrip+'s
     *  EGlucoseTxMessage. */
    fun buildEGlucoseRequest(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x4e)
        return appendCrc(buf)
    }

    /** 08/08/2026 (editor, RONDE 56) — opcode 0x22, vraagt batterijstatus
     *  (spanning A/B, temperatuur) op — antwoord opcode 0x22 of 0x23 (beide
     *  komen voor, zie xDrip+'s BatteryInfoRxMessage.opcode/opcode2). 3
     *  bytes: opcode+CRC, mirror van xDrip+'s BatteryInfoTxMessage. */
    fun buildBatteryInfoRequest(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x22)
        return appendCrc(buf)
    }

    /**
     * 09/08/2026 (editor, RONDE 66, op verzoek — "een anubis transmitter
     * [...] klopt dat niet [de aanname van 2 uur opwarmtijd] [...] xdrip
     * heild hier rekening mee") — opcode 0x52 (xDrip+'s
     * `VersionRequestTxMessage`-"versie 2"-variant), 3 bytes: opcode+CRC,
     * zelfde eenvoudige vorm als [buildGlucoseRequest]/[buildBatteryInfoRequest].
     * Vraagt de transmitter's EIGEN, werkelijke opwarmtijd + sensor-
     * levensduur op (zie [parseVersionRequest2]) — dit is precies hoe
     * xDrip+ zowel een standaard-G6 (2u/10 dagen), G6+ (1u/14 dagen) als een
     * getweakte Anubis (variabel, door de gebruiker zelf ingesteld) correct
     * krijgt: NIET via een tabel met aannames per transmitter-model, maar
     * door het de transmitter simpelweg zelf te vragen. Slechts éénmalig
     * nodig per transmitter (een firmware-/hardware-eigenschap verandert
     * niet tussen verbindingen) — zie DexcomG6Driver.kt's runControlSequence()
     * voor de caching.
     */
    fun buildVersionRequest2(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x52)
        return appendCrc(buf)
    }

    data class VersionRequest2Rx(
        val warmupSeconds: Int?,
        val typicalSensorDays: Int
    )

    /**
     * 09/08/2026 (editor, RONDE 68, CORRECTIE — na live-test: "ik zie
     * namelijk geen info over de transmitter" op v72, ondanks een geldige
     * BLE-verbinding en werkende battery-/glucose-uitwisseling) — de fout
     * zat hier: xDrip+'s eigen `VersionRequest2RxMessage.java` accepteert
     * TWEE verschillende antwoordvormen op precies dezelfde 3-byte-
     * versie=2-aanvraag (de TRANSMITTER kiest welke vorm 'ie terugstuurt,
     * niet iets waar de app zelf om vraagt):
     *  - "long form" — opcode **0x52** terug (xDrip's `opcode2`), 15 bytes:
     *    status(1) + lifeSeconds(4) + warmupSeconds(2) + version1(4) +
     *    version2(1) + typicalSensorDaysRaw(2). Dit was tot ronde 67 de
     *    ENIGE vorm die herkend werd.
     *  - "short form"/"type2" — opcode **0x53** terug (xDrip's `opcode`,
     *    LET OP: ANDER opcode dan de 0x52-aanvraag zelf), 9 bytes:
     *    status(1) + typicalSensorDays(1, direct als dagen, geen
     *    lifeSeconds-omrekening) + featureBits(2) + warmupSeconds(2).
     * Ronde 66/67's parser accepteerde uitsluitend een 0x52-antwoord
     * (`packet[0] != 0x52.toByte()` faalde de check) — een transmitter die
     * met 0x53 antwoordt (blijkbaar precies wat hier gebeurde, mogelijk
     * hardware-/firmware-afhankelijk) werd zo simpelweg genegeerd: er kwam
     * wél een geldig antwoord terug, maar de app gooide het weg en bleef
     * `warmupSeconds`/`typicalSensorDays` als "nog nooit opgevraagd" zien —
     * vandaar dat de nieuwe transmitter-capability-regel in
     * DexcomG6StatusScreen.kt nooit verscheen. Nu worden beide vormen
     * herkend en geparsed (zie DexcomG6Driver.kt's `handleControlNotification()`
     * voor de bijbehorende dispatch-uitbreiding naar opcode 0x53).
     *
     * GEEN CRC-controle (xDrip+'s eigen parser laat dat hier ook achterwege
     * — "TODO check CRC??" in de bron), dus bewust een ruime
     * lengte-ondergrens per vorm i.p.v. een exacte match.
     */
    fun parseVersionRequest2(packet: ByteArray): VersionRequest2Rx? {
        if (packet.isEmpty()) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            packet[0] == 0x52.toByte() && packet.size >= 15 -> {
                buf.position(2) // sla opcode + status over
                val lifeSeconds = buf.int.toLong() and 0xffffffffL
                val warmupSeconds = buf.short.toInt() and 0xffff
                buf.int // version1, ongebruikt
                buf.get() // version2, ongebruikt
                val typicalSensorDaysRaw = (buf.short.toInt() and 0xffff).toLong()
                val typicalSensorDays = minOf(typicalSensorDaysRaw, lifeSeconds / 86400L).toInt()
                VersionRequest2Rx(warmupSeconds, typicalSensorDays)
            }
            // 09/08/2026 (editor, RONDE 69, CORRECTIE — na live-test:
            // warmup werd op "0m" getoond, terwijl xDrip+ voor dezelfde
            // (Anubis-)transmitter altijd "50m" liet zien) — xDrip+'s eigen
            // `VersionRequest2RxMessage` markeert deze korte vorm alleen als
            // "type2" (`packet.length == 9`, EXACT, geen ondergrens) en
            // waarschuwt zelf expliciet in commentaar dat `warmupSeconds`
            // "only valid in type 2" is: bij een AFWIJKENDE lengte voor
            // hetzelfde opcode 0x53 (xDrip's bron noemt zelf "12 more bytes
            // of unknown data" als mogelijke langere variant) staat er op
            // dezelfde byte-positie kennelijk iets anders, en zou het
            // "0m"-resultaat precies zo'n misinterpretatie kunnen zijn.
            // `typicalSensorDays` blijft wél altijd vertrouwd (xDrip+ leest
            // dat ONVOORWAARDELIJK in dezelfde tak, zonder type2-voorbehoud)
            // — alleen warmupSeconds wordt nu `null` (= "nog niet
            // betrouwbaar bekend") bij een niet-exact-9-byte antwoord, i.p.v.
            // een mogelijk foutief getal te tonen.
            packet[0] == 0x53.toByte() && packet.size >= 9 -> {
                buf.position(2) // sla opcode + status over
                val typicalSensorDays = buf.get().toInt() and 0xff
                buf.short // featureBits, ongebruikt
                val warmupSecondsRaw = buf.short.toInt() and 0xffff
                val isType2 = packet.size == 9
                VersionRequest2Rx(if (isType2) warmupSecondsRaw else null, typicalSensorDays)
            }
            else -> null
        }
    }

    // ============================================================
    // Inkomend (RX) — parsers, geven null terug bij verkeerde
    // opcode/lengte/CRC (net als xDrip+'s "valid"-vlaggen).
    // ============================================================

    data class AuthChallengeRx(val tokenHash: ByteArray, val challenge: ByteArray)

    /** opcode 0x03 — GEEN CRC-check (xDrip+'s eigen AuthChallengeRxMessage
     *  controleert 'm hier ook niet, dit bericht heeft er geen). */
    fun parseAuthChallenge(packet: ByteArray): AuthChallengeRx? {
        if (packet.size < 17 || packet[0] != 0x03.toByte()) return null
        return AuthChallengeRx(
            tokenHash = packet.copyOfRange(1, 9),
            challenge = packet.copyOfRange(9, 17)
        )
    }

    data class AuthStatusRx(val authenticated: Boolean, val bonded: Boolean)

    fun parseAuthStatus(packet: ByteArray): AuthStatusRx? {
        if (packet.size < 3 || packet[0] != 0x05.toByte()) return null
        return AuthStatusRx(authenticated = packet[1] == 1.toByte(), bonded = packet[2] == 1.toByte())
    }

    data class SessionStartRx(
        val ok: Boolean,
        val infoCode: Int,
        val requestedStartTime: Int,
        val sessionStartTime: Int,
        val transmitterTime: Int
    ) {
        /** 09/08/2026 (editor, RONDE 66) — info 0x02 betekent dat de
         *  transmitter een NIEUWE sessie-start heeft AFGEWEZEN omdat er al
         *  een sessie loopt — de zojuist meegestuurde sensor-code (indien
         *  aanwezig) is dan NIET toegepast op een nieuwe fysieke sensor,
         *  het is gewoon een bevestiging dat de OUDE sessie nog actief is.
         *  Zie DexcomG6Driver.kt's runControlSequence() voor de afhandeling
         *  (waarschuwing + expliciete stop vóór een nieuwe start). */
        val alreadyStarted: Boolean get() = infoCode == 0x02
    }

    /**
     * opcode 0x27, 17 bytes vast.
     *
     * 09/08/2026 (editor, RONDE 66, CORRECTIE — geverifieerd tegen xDrip+'s
     * echte `SessionStartRxMessage.isOkay()`) — deze functie rekende
     * `info == 0x02` ("already started") eerder ten onrechte mee als
     * `ok = true`. xDrip+'s eigen `isOkay()` is expliciet: alleen
     * `status == 0x00 && (info == 0x01 || info == 0x05 || info == 0x06) &&
     * sessionStartTime != 0` telt als een ECHTE, geslaagde sessie-start.
     * info 0x02 wordt door xDrip+ apart afgehandeld (`message()`:
     * "Already started") — precies het geval waarin een net verstuurde
     * nieuwe sensor-code NIET is toegepast, omdat de transmitter al een
     * sessie had lopen. Met de oude, te ruime `ok`-berekening dacht
     * DexcomG6Driver.kt's runControlSequence() ten onrechte dat de nieuwe
     * code geaccepteerd was (wiste 'm meteen, toonde "Sensor started" met
     * een opwarm-aftelling) terwijl er in werkelijkheid niets gebeurd was.
     *
     * 09/08/2026 (editor, RONDE 73, KRITIEKE CORRECTIE — gevonden via de
     * Diagnostic File Logging-export na live-test) — de `sessionStartTime
     * != 0`-eis hierboven bleek voor DEZE Anubis-transmitter simpelweg
     * ALTIJD waar te zijn te falen: elke sessie-start-poging in het log gaf
     * exact `status=0x00, info=5, sessionStartTime=0` terug — dus `info`
     * zat steeds keurig in de geaccepteerde set (0x05!), maar
     * `sessionStartTime` bleef stelselmatig 0, ook bij een ECHTE, geslaagde
     * start: de eerstvolgende glucose-polls in hetzelfde log lieten daarna
     * telkens `state=WarmingUp` zien (via het onafhankelijke
     * kalibratiebyte, zie DexcomG6CalibrationState.kt) — het onmiskenbare
     * bewijs dat de sessie WEL degelijk gestart was. Met de oude, te
     * strenge check werd dit dus VOOR ALTIJD als mislukking behandeld: de
     * code werd nooit gewist (zie ok==true-tak in runControlSequence()), en
     * — sinds ronde 71's eigen fix — werd het stop-before-start-vlaggetje
     * bij elke "mislukking" juist opnieuw gezet, wat een SCHADELIJKE
     * oneindige lus opleverde: elke ~5 minuten stopte de app een prima
     * opwarmende sensor gewoon weer, en begon opnieuw — precies de klacht
     * "als ik dan weer eentje start begint de cycle opnieuw" en de
     * hardnekkige "Warmup: —" (de sensor kreeg letterlijk nooit de kans om
     * ver genoeg op te warmen, want de app onderbrak 'm zelf steeds
     * opnieuw). Zelfde categorie fout als de VersionRequest2-velden
     * (warmupSeconds/typicalSensorDays) die deze transmitter ook niet altijd
     * volledig invult — een firmware-eigenaardigheid, geen protocolfout van
     * onze kant. Fix: `sessionStartTime != 0` NIET meer als harde eis;
     * `status==0x00 && info in {0x01,0x05,0x06}` (xDrip+'s eigen
     * info-whitelist) is voldoende voor `ok`. `sessionStartTime` blijft wel
     * gewoon meegegeven in de data class voor eventuele toekomstige
     * diagnostiek, alleen niet langer een blokkerende voorwaarde.
     */
    fun parseSessionStart(packet: ByteArray): SessionStartRx? {
        if (packet.size != 17 || packet[0] != 0x27.toByte() || !checkCrc(packet)) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get(1)
        val info = buf.get(2).toInt() and 0xff
        val requestedStartTime = buf.getInt(3)
        val sessionStartTime = buf.getInt(7)
        val transmitterTime = buf.getInt(11)
        val ok = status == 0x00.toByte() && (info == 0x01 || info == 0x05 || info == 0x06)
        return SessionStartRx(ok, info, requestedStartTime, sessionStartTime, transmitterTime)
    }

    data class SessionStopRx(
        val ok: Boolean,
        val sessionStartTime: Int,
        val sessionStopTime: Int,
        val transmitterTime: Int
    )

    /** 09/08/2026 (editor, RONDE 66) — opcode 0x29, 17 bytes vast, mirror
     *  van xDrip+'s `SessionStopRxMessage` — antwoord op [buildSessionStop].
     *  Byte 2 ("received") wordt, net als in xDrip+'s eigen `isOkay()`,
     *  bewust niet meegewogen — alleen `status == 0x00` bepaalt succes. */
    fun parseSessionStop(packet: ByteArray): SessionStopRx? {
        if (packet.size != 17 || packet[0] != 0x29.toByte() || !checkCrc(packet)) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get(1)
        val sessionStopTime = buf.getInt(3)
        val sessionStartTime = buf.getInt(7)
        val transmitterTime = buf.getInt(11)
        val ok = status == 0x00.toByte()
        return SessionStopRx(ok, sessionStartTime, sessionStopTime, transmitterTime)
    }

    data class GlucoseRx(
        val sequence: Int,
        val dexTimestamp: Int,
        val glucoseMgdl: Int,
        val glucoseIsDisplayOnly: Boolean,
        val stateRaw: Int,
        val trendRaw: Int,
        /** alleen gevuld bij EGlucoseRx (0x4f) — de transmitter's eigen
         *  algoritme-voorspelling, momenteel ongebruikt maar bewaard voor
         *  eventuele latere diagnostiek. */
        val predictedGlucoseMgdl: Int? = null
    )

    /** opcode 0x31 — "klassieke" glucosemelding, mirror van xDrip+'s
     *  GlucoseRxMessage. */
    fun parseGlucose(packet: ByteArray): GlucoseRx? {
        if (packet.size < 14 || packet[0] != 0x31.toByte() || !checkCrc(packet)) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buf.getInt(2)
        val timestamp = buf.getInt(6)
        val glucoseBytes = buf.getShort(10).toInt() and 0xffff
        val displayOnly = (glucoseBytes and 0xf000) > 0
        val glucose = glucoseBytes and 0xfff
        val state = buf.get(12).toInt() and 0xff
        val trend = buf.get(13).toInt()
        return GlucoseRx(sequence, timestamp, glucose, displayOnly, state, trend)
    }

    /** opcode 0x4f — "verrijkte" glucosemelding met transmitter-voorspelling
     *  (mirror van xDrip+'s EGlucoseRxMessage). Velden liggen 1 byte
     *  eerder dan bij [parseGlucose] (geen apart status_raw-byte). */
    fun parseEGlucose(packet: ByteArray): GlucoseRx? {
        if (packet.size < 16 || packet[0] != 0x4f.toByte() || !checkCrc(packet)) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        buf.get() // status-byte, ongebruikt (mirror: alleen via TransmitterStatus.getBatteryLevel gebruikt, niet nodig hier)
        val sequence = buf.int
        val timestamp = buf.int
        val glucoseBytes = buf.short.toInt() and 0xffff
        val displayOnly = (glucoseBytes and 0xf000) > 0
        val glucose = glucoseBytes and 0xfff
        val state = buf.get().toInt() and 0xff
        val trend = buf.get().toInt()
        val predicted = buf.short.toInt() and 0x03ff
        return GlucoseRx(sequence, timestamp, glucose, displayOnly, state, trend, predicted)
    }

    /** Herkent en delegeert op basis van de eerste byte (opcode) — [CONTROL]
     *  kan zowel 0x31- als 0x4f-berichten sturen, zie DexcomG6Driver.kt's
     *  notificatie-handler. */
    fun parseAnyGlucose(packet: ByteArray): GlucoseRx? =
        if (packet.isNotEmpty() && packet[0] == 0x4f.toByte()) parseEGlucose(packet) else parseGlucose(packet)

    /** Vertaalt xDrip+'s ruwe `trend`-byte (signed, mg/dL per minuut * iets
     *  — xDrip+ gebruikt 'm intern vooral als ruwe richtingsindicator) naar
     *  een simpele mg/dL-per-minuut-schatting voor GlucoseReading.trendMgdlPerMin.
     *  Dexcom's eigen schaal is 1 raw-eenheid ≈ 0,1 mg/dL/min; dit is bewust
     *  een eenvoudige, ongeveer-kloppende aanname — geen kritiek invoerveld
     *  (FCLvNext berekent zijn eigen slope uit de bg-geschiedenis, zie
     *  FCLvNextBgHistoryProvider.kt), puur voor de UI-trendpijl.
     */
    fun trendByteToMgdlPerMin(trendRaw: Int): Float = trendRaw / 10f

    /** 08/08/2026 (editor, RONDE 56) — batterij-/temperatuurstatus, mirror
     *  van xDrip+'s BatteryInfoRxMessage. `resistance`/`runtimeDays` zijn
     *  -1 als niet aanwezig (10-byte "rev2"-lay-out, het gebruikelijke
     *  G6-formaat zonder weerstandsveld) — zie [parseBatteryInfo]'s kdoc. */
    data class BatteryInfoRx(
        val status: Int,
        val voltageA: Int,
        val voltageB: Int,
        val resistance: Int,
        val runtimeDays: Int,
        val temperatureC: Int
    )

    /** opcodes 0x22 én 0x23 komen als antwoord voor (afhankelijk van
     *  transmitter-firmwarerevisie, zie xDrip+'s BatteryInfoRxMessage.opcode/
     *  opcode2) — GEEN CRC-check nodig/aanwezig in xDrip+'s eigen parser,
     *  dus hier ook niet (het bericht heeft er in de praktijk geen; puur op
     *  lengte/opcode gevalideerd, net als xDrip+ zelf doet). Twee
     *  pakketlengtes: 10 bytes (G6/"rev2", geen weerstandsveld, laatste byte
     *  vóór CRC is dan runtime EN telt niet als "runtime" — xDrip+ zet 'm
     *  dan op -1) of 12 bytes (ouder "rev1"-formaat mét weerstandsveld). */
    fun parseBatteryInfo(packet: ByteArray): BatteryInfoRx? {
        if (packet.size < 10) return null
        val opcode = packet[0]
        if (opcode != 0x22.toByte() && opcode != 0x23.toByte()) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val status = buf.get().toInt() and 0xff
        val voltageA = buf.short.toInt() and 0xffff
        val voltageB = buf.short.toInt() and 0xffff
        val hasResistance = packet.size != 10
        val resistance = if (hasResistance) (buf.short.toInt() and 0xffff) else -1
        val runtimeRaw = buf.get().toInt() and 0xff
        val runtime = if (packet.size == 10) -1 else runtimeRaw
        val temperature = buf.get().toInt() // signed, mirror van xDrip+'s eigen onzekerheid hierover
        return BatteryInfoRx(status, voltageA, voltageB, resistance, runtime, temperature)
    }
}
