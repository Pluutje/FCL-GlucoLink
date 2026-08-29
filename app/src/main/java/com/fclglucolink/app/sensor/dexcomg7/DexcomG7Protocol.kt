package com.fclglucolink.app.sensor.dexcomg7

import com.fclglucolink.app.sensor.dexcomg6.DexcomG6Crypto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+ BLE-protocol (UUID's + pakket-op-/decodering)
 * ============================================================================
 *
 * 17/08/2026 (editor, RONDE 112) — geport van xDrip+'s `g5model/
 * BluetoothServices.java` (UUID's, gedeeld met G5/G6, zie DexcomG6Protocol.kt
 * — de G7 gebruikt LETTERLIJK hetzelfde GATT-profiel, alleen een ander
 * koppel-/authenticatieprotocol erbovenop) plus `jamorham.keks.Config.java`/
 * `jamorham.keks.message.*.java` (de auth-handshake-berichten) en
 * `cgm/dex/g7/EGlucoseRxMessage.java`/`BackfillControlRx.java` (de G7-
 * specifieke glucosedata, andere opcode/indeling dan G6's eigen
 * GlucoseRxMessage). Cross-gecheckt tegen Juggluco's `DexGattCallback.java`
 * (dezelfde 4 karakteristieken, dezelfde volgorde, dezelfde 160-byte-in-
 * stukjes-van-20-aanpak voor de J-PAKE-rondepakketten) en tegen een publieke
 * GitHub-discussie (gui-dos/DiaBLE#17) die de byte-voor-byte koppelvolgorde
 * bevestigt.
 *
 * BELANGRIJK VERSCHIL MET G6's PROTOCOL-LAAG (DexcomG6Protocol.kt): G6-
 * pakketten hebben een CRC16-staart (zie DexcomG6Protocol.appendCrc/
 * checkCrc). De G7-auth-handshake-berichten HEBBEN DAT NIET — de Java-bron
 * (`jamorham.keks.message.*`) bouwt exact `opcode + payload`, geen CRC-veld.
 * Bewust dus GEEN crc16()-aanroep hieronder — dat zou tegen een echte G7
 * verkeerde bytes versturen.
 *
 * WAT HIER WEL/NIET in zit: de volledige PIN-koppelroute (opcodes 0x02/0x04/
 * 0x05, de drie J-PAKE-rondepakketten via DexcomG7Crypto.kt, de "TIME_
 * EXTENDED"-bond-triggerbytes) plus het glucoseverzoek/-antwoord (opcode
 * 0x4E). NIET geport: de backfill-payload-indeling na opcode 0x59 — xDrip+'s
 * EIGEN bron markeert die met een letterlijke `// TODO more to parse here`,
 * dus zelfs de meest volledige publiek beschikbare referentie heeft 'm niet
 * volledig uitgeplozen. [parseBackfillControl] hieronder herkent alleen OF
 * er een backfill-afsluitpakket binnenkwam, leest de inhoud nog niet.
 *
 * 28/08/2026 (editor, RONDE 144) — de certificaat-koppelroute (opcodes
 * 0x0b/0x0c/0x0d) IS inmiddels wél geport, zie [buildCertInfoRequest]/
 * [parseCertInfoResponse]/[buildSignChallenge]/[CHALLENGE_OUT] hieronder en
 * DexcomG7CertMaterial.kt/DexcomG7Crypto.signWithCertPrivateKey voor de
 * herkomst/het waarom.
 */
object DexcomG7Protocol {

    // ---- BLE-service/characteristic-UUID's — zelfde GATT-profiel als G5/G6,
    // zie DexcomG6Protocol.kt's kdoc voor de herkomst. ----
    val CGM_SERVICE: UUID = UUID.fromString("F8083532-849E-531C-C594-30F1F86A4EA5")
    val CONTROL: UUID = UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5")
    val AUTHENTICATION: UUID = UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5")
    val PROBABLY_BACKFILL: UUID = UUID.fromString("F8083536-849E-531C-C594-30F1F86A4EA5")

    /** 17/08/2026 (editor, RONDE 112) — draagt tijdens de koppelhandshake de
     *  drie (elk 160-byte) J-PAKE-rondepakketten, in stukjes van hoogstens 20
     *  bytes (BLE-MTU) — G6 gebruikt deze characteristic niet (geen J-PAKE),
     *  vandaar geen equivalent in DexcomG6Protocol.kt. */
    val EXTRA_DATA: UUID = UUID.fromString("F8083538-849E-531C-C594-30F1F86A4EA5")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // ============================================================
    // Auth-handshake — uitgaand (TX)
    // ============================================================

    /** opcode 0x02 — AuthRequestTxMessage2: 8-byte eigen willekeurig token +
     *  1 slotbyte. [token] wordt door de aanroeper bewaard om straks (na de
     *  sensor se antwoord) tegen diens uitdaging-hash te vergelijken (zie
     *  DexcomG7Crypto.DexcomG7Jpake.calculateHash's gebruik in
     *  DexcomG7Driver.kt).
     *
     * 27/08/2026 (editor, RONDE 135, na een live-test die — dankzij Ronde
     * 134's partij-ID-fix — voor het eerst alle drie de J-PAKE-rondes zag
     * SLAGEN, maar daarna 3x op rij vastliep op EXACT dezelfde plek: de
     * sensor beantwoordde de auth-aanvraag nooit en verbrak de verbinding
     * (status=19) binnen ~200ms) — de slotbyte stond hier op 0. De echte
     * bron (`jamorham.keks.message.AuthRequestTxMessage2`, rechtstreeks
     * opgehaald) laat zien dat dat NOOIT 0 is:
     *   this(token_size, (alt ? endByteAlt : endByteStd)
     *           + (chal.length > 2 ? chal[2] : 0));
     *   // endByteStd = 0x2, endByteAlt = 0x1
     * Bij een gewone (eerste) koppelpoging is `alt` altijd `false` (die
     * wordt alleen op `true` gezet via een apart datakanaal dat xDrip+ zelf
     * ook nooit voor deze route gebruikt) en `chal` altijd leeg (`chal.
     * length > 2` is dan `false`) — dus de slotbyte is in de praktijk altijd
     * gewoon `endByteStd` = **2**, nooit 0. Vermoedelijk negeert/verwerpt de
     * sensor een auth-aanvraag met een onherkende slotwaarde stilzwijgend
     * (geen antwoord, gewoon de verbinding verbreken) — precies het
     * waargenomen symptoom. */
    fun buildAuthRequest(token: ByteArray): ByteArray {
        require(token.size == 8) { "token moet 8 bytes zijn" }
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x02)
        buffer.put(token)
        buffer.put(2) // slot — AuthRequestTxMessage2's endByteStd (0x2), zie kdoc
        return buffer.array()
    }

    fun randomToken(): ByteArray {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /** opcode 0x04 — AuthChallengeTxMessage: ons antwoord op de uitdaging die
     *  de sensor ons stuurde ([DexcomG7Crypto.DexcomG7Jpake.calculateHash],
     *  8 bytes). */
    fun buildAuthChallenge(challengeHash: ByteArray): ByteArray {
        require(challengeHash.size == 8) { "challengeHash moet 8 bytes zijn" }
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x04)
        buffer.put(challengeHash)
        return buffer.array()
    }

    /** Config.java's `KEYCMD`(0x0A) + rondenummer-parameter (0, 1 of 2 voor
     *  ronde 1/2/3) — het KORTE commando dat via de Authentication-
     *  characteristic geschreven wordt, telkens vlak vóór het bijbehorende
     *  (lange, via ExtraData verstuurde) rondepakket. */
    fun buildRoundCommand(roundIndexZeroBased: Int): ByteArray = byteArrayOf(0x0A, roundIndexZeroBased.toByte())

    /** Config.java's drie TIME_EXTENDED-varianten — xDrip+ herkent een
     *  binnenkomende Authentication-indicatie die exact met een van deze
     *  overeenkomt als "sensor vraagt om nu te bonden" ([isBondTrigger]), en
     *  schrijft zelf ook de eerste variant terug als afsluiting van de
     *  ChallengeReply-stap (zie DexcomG7Driver.kt's pairing-state-machine). */
    val TIME_EXTENDED: ByteArray = byteArrayOf(0x06, 0x19)
    private val TIME_EXTENDED_2: ByteArray = byteArrayOf(0xFF.toByte(), 0x06, 0x01)
    private val TIME_EXTENDED_3: ByteArray = byteArrayOf(0x06, 0x00)

    fun isBondTrigger(data: ByteArray): Boolean =
        data.contentEquals(TIME_EXTENDED) || data.contentEquals(TIME_EXTENDED_2) || data.contentEquals(TIME_EXTENDED_3)

    /** Config.java's `GETDATA2` (0x4E, één byte) — het glucoseverzoek voor
     *  een 4-cijferige (G7-stijl) koppelcode. `GETDATA` (0x4E 0x0A 0xA9, drie
     *  bytes) is Config.java's variant voor langere wachtwoorden en dus hier
     *  bewust niet gebruikt — zie DexcomG7Crypto.DexcomG7JpakeContext's kdoc
     *  bij `passwordBytes`. */
    fun buildGlucoseRequest(): ByteArray = byteArrayOf(0x4E)

    // ============================================================
    // Auth-handshake — inkomend (RX)
    // ============================================================

    /**
     * Antwoord op [buildAuthRequest], verwacht >= 17 bytes:
     * byte 0: opcode-echo (genegeerd), bytes 1-8: de sensor se hash-bewijs
     * (te vergelijken met onze eigen [DexcomG7Crypto.DexcomG7Jpake.calculateHash]
     * over ONS ZELF verstuurde token), bytes 9-16: de sensor se NIEUWE
     * uitdaging (waar wij op onze beurt een hash overheen moeten sturen via
     * [buildAuthChallenge]). Zie `jamorham.keks.Plugin.verifyChallenge()`/
     * `receivedResponse()`'s `RequestAuth`-tak voor het origineel.
     */
    data class AuthRequestResponse(val theirProofHash: ByteArray, val theirChallenge: ByteArray)

    fun parseAuthRequestResponse(data: ByteArray): AuthRequestResponse? {
        if (data.size < 17) return null
        return AuthRequestResponse(
            theirProofHash = data.copyOfRange(1, 9),
            theirChallenge = data.copyOfRange(9, 17)
        )
    }

    /** opcode 0x05 — AuthStatusRxMessage: of de J-PAKE-uitwisseling
     *  daadwerkelijk gelukt is, en of het toestel al een OS-bond met de
     *  sensor heeft (bonded=3 betekent "opnieuw beginnen", zie
     *  `needsRefresh`). */
    data class AuthStatusRx(val authenticated: Int, val bonded: Int) {
        val isAuthenticated: Boolean get() = authenticated == 1
        val isBonded: Boolean get() = bonded == 1
        val needsRefresh: Boolean get() = bonded == 3
    }

    fun parseAuthStatus(data: ByteArray): AuthStatusRx? {
        if (data.size < 3 || data[0] != 0x05.toByte()) return null
        return AuthStatusRx(authenticated = data[1].toInt(), bonded = data[2].toInt())
    }

    // ============================================================
    // Certificaat-koppelroute (opcodes 0x0b/0x0c/0x0d) — RONDE 144
    // ============================================================

    /** opcode 0x0b — CertInfoTxMessage: kondigt aan dat we [which] (0=deel A,
     *  1=deel B, zie DexcomG7CertMaterial.kt) van [length] bytes gaan sturen.
     *  6 bytes: opcode, which, length (4-byte little-endian int) — letterlijk
     *  `jamorham.keks.message.CertInfoTxMessage.expectMyCert()`. */
    fun buildCertInfoRequest(which: Int, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x0b)
        buffer.put(which.toByte())
        buffer.putInt(length)
        return buffer.array()
    }

    /** Antwoord op [buildCertInfoRequest] — 7 bytes: opcode, state, which,
     *  size (2-byte little-endian; de laatste 2 bytes van het pakket blijven
     *  bewust ongebruikt, xDrip+'s eigen `CertInfoRxMessage` doet dat ook
     *  letterlijk zo — kdoc daar: "might be an int but just ignore later
     *  bytes"). */
    data class CertInfoRx(val state: Int, val which: Int, val size: Int)

    fun parseCertInfoResponse(data: ByteArray): CertInfoRx? {
        if (data.size != 7 || data[0] != 0x0b.toByte()) return null
        val state = data[1].toInt()
        val which = data[2].toInt()
        val size = ((data[4].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        return CertInfoRx(state, which, size)
    }

    /** opcode 0x0c — SignChallengeTxMessage: 16 willekeurige bytes die de
     *  sensor in zijn beurt gebruikt/beantwoordt (zie
     *  DexcomG7Crypto.signWithCertPrivateKey's kdoc voor het vervolg). */
    fun buildSignChallenge(challenge16: ByteArray): ByteArray {
        require(challenge16.size == 16) { "challenge moet 16 bytes zijn" }
        val buffer = ByteBuffer.allocate(17)
        buffer.put(0x0c)
        buffer.put(challenge16)
        return buffer.array()
    }

    fun randomSignChallenge(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /** Config.java's vaste `CHALLENGE_OUT` (0x0d,0x00,0x02) — afsluiting van
     *  de certificaatstap; gaat samen met onze berekende handtekening (via
     *  ExtraData, chunked, zie DexcomG7Driver.kt's `runCertificateExchange`). */
    val CHALLENGE_OUT: ByteArray = byteArrayOf(0x0d, 0x00, 0x02)

    // ============================================================
    // Glucose — inkomend (RX), opcode 0x4E
    // ============================================================

    /**
     * EGlucoseRxMessage.java, letterlijk geport: little-endian, minimaal 19
     * bytes. [glucoseMgdl] is de onderste 12 bits van het glucose-veld (de
     * bovenste bits zijn een "alleen-voor-weergave"-vlag, xDrip+'s eigen
     * `glucoseIsDisplayOnly`) — precies zoals xDrip+ 'm gebruikt.
     */
    data class GlucoseRx(
        val statusRaw: Int,
        val clock: Long,
        val sequence: Int,
        val ageSeconds: Int,
        val glucoseMgdl: Int,
        val glucoseIsDisplayOnly: Boolean,
        val calibrationStateRaw: Int,
        val trendRaw: Int,
        val predictedGlucoseMgdl: Int
    ) {
        /** trend=127 betekent "ongeldig/onbekend" in xDrip+'s eigen indeling. */
        val trendMgdlPerMin: Double? get() = if (trendRaw != 127) trendRaw / 10.0 else null
    }

    fun parseGlucose(packet: ByteArray): GlucoseRx? {
        if (packet.size < 19) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != 0x4E.toByte()) return null
        val statusRaw = buffer.get().toInt() and 0xFF
        val clock = buffer.int.toLong() and 0xFFFFFFFFL
        val sequence = buffer.short.toInt() and 0xFFFF
        buffer.short // "bogus"/gereserveerd veld, zie EGlucoseRxMessage.java — bewust genegeerd.
        val age = buffer.short.toInt() and 0xFFFF
        val glucoseBytes = buffer.short.toInt() and 0xFFFF
        val glucoseIsDisplayOnly = (glucoseBytes and 0xF000) > 0
        val glucoseMgdl = glucoseBytes and 0xFFF
        val state = buffer.get().toInt() and 0xFF
        val trend = buffer.get().toInt()
        val predicted = buffer.short.toInt() and 0x03FF
        return GlucoseRx(
            statusRaw = statusRaw,
            clock = clock,
            sequence = sequence,
            ageSeconds = age,
            glucoseMgdl = glucoseMgdl,
            glucoseIsDisplayOnly = glucoseIsDisplayOnly,
            calibrationStateRaw = state,
            trendRaw = trend,
            predictedGlucoseMgdl = predicted
        )
    }

    /** opcode 0x59 — herkent alleen dat er een backfill-afsluitpakket
     *  binnenkwam; leest de inhoud (nog) niet, zie klasse-kdoc. */
    fun isBackfillControlPacket(packet: ByteArray): Boolean =
        packet.isNotEmpty() && packet[0] == 0x59.toByte()

    // ============================================================
    // Batterij-/firmwareversie (opcodes 0x22/0x23 batterij, 0x20/0x21
    // firmware) — RONDE 150, op verzoek: "of hij dan ook de data als
    // batterij en firmware version terug geeft zoals xdrip ook netjes doet".
    // ============================================================

    /**
     * 28/08/2026 (editor, RONDE 150) — BELANGRIJK, in tegenstelling tot de
     * klasse-kdoc hierboven over de auth-handshake-berichten (die BEWUST
     * GEEN CRC hebben): xDrip+'s eigen bron (`g5model/BatteryInfoTxMessage.
     * java`/`VersionRequestTxMessage.java`/`BaseMessage.java`) laat zien dat
     * dít stel berichten wél het KLASSIEKE G5/G6-envelop gebruikt —
     * opcode(1) + CRC16(2, little-endian) — over hetzelfde `Control`-kanaal
     * dat nu al voor het glucoseverzoek (opcode 0x4E) gebruikt wordt.
     * `Ob1G5StateMachine.checkVersionAndBattery(parent, connection)` is
     * ONVOORWAARDELIJK gedeeld tussen G5/G6/G7 (bevestigd via een
     * nabijgelegen commentaar dat G5/G6/G7 onderscheidt via `usingG6() ?
     * (shortTxId() ? "G7" : "G6") : "G5"` — G7 IS dus een `usingG6()`-tak,
     * geen aparte G7-only code). Vandaar hier een eigen, kleine CRC16-
     * helper i.p.v. de CRC-loze aanpak van de rest van dit bestand — en
     * bewust hergebruik van DexcomG6Crypto.crc16 (dezelfde CCITT-16-tabel,
     * al bewezen werkend voor G6's eigen batterijverzoek) i.p.v. een eigen
     * kopie te maken.
     *
     * VERTROUWENSNIVEAU — expliciet lager dan de rest van dit bestand: dit
     * is architectuur-bewijs uit xDrip+'s gedeelde broncode (dezelfde
     * opcodes/CRC-envelop/Control-kanaal als het al bewezen glucoseverzoek),
     * maar NOG NIET byte-voor-byte bevestigd tegen een echte G7-sensor via
     * een HCI-capture (in tegenstelling tot bijv. het glucoseverzoek zelf,
     * dat al meerdere keren rechtstreeks in de gebruiker's eigen bugreports
     * is teruggezien). Zie DexcomG7Driver.kt's kdoc bij de aanroep hiervan.
     */
    private fun appendCrc(buffer: ByteBuffer): ByteArray {
        val withoutCrc = buffer.array()
        val crc = DexcomG6Crypto.crc16(withoutCrc, 0, withoutCrc.size - 2)
        withoutCrc[withoutCrc.size - 2] = crc[0]
        withoutCrc[withoutCrc.size - 1] = crc[1]
        return withoutCrc
    }

    /** opcode 0x22 — BatteryInfoTxMessage: vraagt batterijspanning (A/B) +
     *  temperatuur op. 3 bytes: opcode+CRC16. Antwoord komt terug als opcode
     *  0x22 óf 0x23 (transmitter-firmware-afhankelijk, zie
     *  [parseBatteryInfo]) — letterlijk dezelfde vorm als
     *  DexcomG6Protocol.buildBatteryInfoRequest(). */
    fun buildBatteryInfoRequest(): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x22)
        return appendCrc(buf)
    }

    /** Zelfde veldindeling als DexcomG6Protocol.BatteryInfoRx — zie dat
     *  bestand's kdoc voor de herkomst (xDrip+'s BatteryInfoRxMessage).
     *  `resistance`/`runtimeDays` zijn -1 als niet aanwezig (10-byte
     *  "rev2"-lay-out, geen weerstandsveld). */
    data class BatteryInfoRx(
        val status: Int,
        val voltageA: Int,
        val voltageB: Int,
        val resistance: Int,
        val runtimeDays: Int,
        val temperatureC: Int
    )

    /** Opcodes 0x22 én 0x23 komen als antwoord voor. GEEN CRC-check (mirror
     *  van xDrip+'s eigen parser — DexcomG6Protocol.parseBatteryInfo() doet
     *  dat om dezelfde reden ook niet). */
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
        val temperature = buf.get().toInt() // signed
        return BatteryInfoRx(status, voltageA, voltageB, resistance, runtime, temperature)
    }

    /**
     * VersionRequestTxMessage, alle drie de voor G7 relevante varianten —
     * opcode+CRC16, 3 bytes. Antwoord komt (bij succes) terug als opcode
     * 0x21 (VersionRequestRxMessage) — LET OP: dit is een ANDER bericht dan
     * DexcomG6Protocol's buildVersionRequest2()/opcode 0x52 (die vraagt
     * opwarmtijd/sensor-levensduur op, geen firmware-versiestring).
     *
     * 28/08/2026 (editor, RONDE 152, CORRECTIE op RONDE 150) — Ronde 150
     * koos [version]=0 (opcode 0x20) als "eenvoudigste/meest-compatibele
     * variant", een AANNAME die nooit tegen xDrip+'s eigen broncode
     * geverifieerd was. Een live-test tegen de gebruiker's echte G7-sensor
     * wees die aanname af (opcode 0x20 werd afgewezen — zie
     * DexcomG7Driver.kt's kdoc bij [queryFirmwareIfStale]). Bij het
     * uitzoeken WAAROM xDrip+ zelf blijkbaar wél lukt, bleek
     * `Ob1G5StateMachine.requiredNextFirmwareDetailsType()` (rechtstreeks
     * nagelezen in de vendored bron) een HEEL ANDERE prioriteitsvolgorde te
     * hanteren dan aangenomen: **versie 1 (opcode 0x4A) EERST**, altijd,
     * voor élke transmitter — pas ALS dat mislukt/nog niet geprobeerd is EN
     * de transmitter-ID 6 tekens is (xDrip+'s eigen G7-detectie, zie
     * `txid.length() == 6`-check) volgt versie 0 (opcode 0x20) als tweede
     * poging, en versie 2 (opcode 0x52) als laatste redmiddel. Versie 0 was
     * dus NOOIT xDrip+'s eerste keus voor een G7 — dat verklaart
     * waarschijnlijk waarom deze specifieke sensor 'm afwees.
     */
    fun buildFirmwareVersionRequest(version: Int): ByteArray {
        val opcode = when (version) {
            0 -> 0x20
            1 -> 0x4A
            2 -> 0x52
            else -> throw IllegalArgumentException("Onbekende firmware-request-versie: $version")
        }
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(opcode.toByte())
        return appendCrc(buf)
    }

    /** Letterlijke poort van xDrip+'s VersionRequestRxMessage — dotted-
     *  string-velden zijn precies zoals xDrip's eigen "Firmware Version"-
     *  label ze toont (bv. "32.192.109.40"). */
    data class FirmwareVersionRx(
        val status: Int,
        val firmwareVersion: String,
        val bluetoothFirmwareVersion: String,
        val hardwareVersion: Int,
        val otherFirmwareVersion: String,
        val asic: Int
    )

    /** Leest [length] bytes vanaf de huidige positie en plakt ze als
     *  ongetekende decimale waardes aan elkaar met ".", mirror van xDrip+'s
     *  `dottedStringFromData()` (o.a. gebruikt in zowel `g5model/
     *  VersionRequestRxMessage.java` als `cgm/dex/g7/BaseMessage.java`). */
    private fun dottedStringFromData(buf: ByteBuffer, length: Int): String {
        val parts = ArrayList<Int>(length)
        repeat(length) { parts.add(buf.get().toInt() and 0xff) }
        return parts.joinToString(".")
    }

    /** opcode 0x21, vereist >= 18 bytes (mirror van xDrip+'s
     *  `VersionRequestRxMessage`'s eigen `packet.length >= 18`-check). GEEN
     *  CRC-check — zelfde reden als [parseBatteryInfo]. */
    fun parseFirmwareVersion(packet: ByteArray): FirmwareVersionRx? {
        if (packet.size < 18 || packet[0] != 0x21.toByte()) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val status = buf.get().toInt() and 0xff
        val firmwareVersion = dottedStringFromData(buf, 4)
        val bluetoothFirmwareVersion = dottedStringFromData(buf, 4)
        val hardwareVersion = buf.get().toInt() and 0xff
        val otherFirmwareVersion = dottedStringFromData(buf, 3)
        val asic = buf.short.toInt() and 0xffff
        return FirmwareVersionRx(
            status, firmwareVersion, bluetoothFirmwareVersion,
            hardwareVersion, otherFirmwareVersion, asic
        )
    }

    /**
     * 29/08/2026 (editor, RONDE 157, KRITIEKE FIX — live-bevestiging door de
     * gebruiker: "je conclusie dat de sensor hem niet terugkoppelt is
     * onjuist want xdrip geeft hem wel aan") — [parseFirmwareVersion]
     * hierboven verwacht UITSLUITEND opcode 0x21 (xDrip+'s
     * `VersionRequestRxMessage`, het antwoord op vraag-variant 0/opcode
     * 0x20). Maar [FIRMWARE_REQUEST_VERSION_ORDER] in DexcomG7Driver.kt
     * probeert vraag-variant 1 (opcode 0x4A) ALTIJD als EERSTE — en xDrip+'s
     * eigen `VersionRequest1RxMessage.java` (rechtstreeks nagelezen in de
     * vendored bron, `uploads/xDrip-2026.08.08.zip`) laat zien dat het
     * antwoord daarop een COMPLEET ANDER bytepatroon heeft, onder opcode
     * 0x4B (of een letterlijke echo van 0x4A zelf) — niet 0x21.
     * `DexcomG7Driver.kt`'s `handleControlNotification()`-dispatch herkende
     * opcode 0x4A/0x4B nergens, viel in de "onherkend"-tak, en gooide het
     * antwoord dus weg als afwijzing — exact het gemelde symptoom
     * ("firmware blijft leeg"), terwijl de sensor wél gewoon netjes
     * antwoordde. Handmatige decodering van een echt ontvangen pakket uit
     * de meegestuurde log
     * (74,0,32,-64,109,40,42,52,0,0,49,71,65,65,-77,87,-92,-72,-47,0)
     * volgens xDrip+'s lay-out voor de opcode2(0x4A)-tak geeft
     * firmwareVersion="32.192.109.40" — exact het xDrip-voorbeeld dat al in
     * dit bestand's eigen kdoc bij [FirmwareVersionRx] stond, geen toeval.
     *
     * Alleen [firmwareVersion] wordt betrouwbaar gevuld: dat is het enige
     * veld dat DexcomG7StatusScreen.kt daadwerkelijk toont. De overige
     * VersionRequest1RxMessage-velden (build_version/inactive_days/
     * version_code/serial, en die lay-out verschilt bovendien nog eens
     * tussen de 0x4A- en 0x4B-tak) worden bewust NIET 1-op-1 in de
     * bestaande [FirmwareVersionRx]-velden gepropt — dat zou alleen maar
     * misleidende schijn-precisie geven voor data die nergens gebruikt
     * wordt.
     */
    fun parseFirmwareVersion1(packet: ByteArray): FirmwareVersionRx? {
        if (packet.size < 18) return null
        val opcode = packet[0]
        if (opcode != 0x4A.toByte() && opcode != 0x4B.toByte()) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val status = buf.get().toInt() and 0xff
        val firmwareVersion = dottedStringFromData(buf, 4)
        return FirmwareVersionRx(
            status = status,
            firmwareVersion = firmwareVersion,
            bluetoothFirmwareVersion = "",
            hardwareVersion = 0,
            otherFirmwareVersion = "",
            asic = 0
        )
    }
}
