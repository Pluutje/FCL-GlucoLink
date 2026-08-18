package com.fclglucolink.app.sensor.dexcomg7

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
 * 0x4E). NIET geport: de QR-code-certificaat-koppelroute (opcodes 0x0b/0x0c,
 * zie DexcomG7Crypto.kt's klasse-kdoc) en de backfill-payload-indeling na
 * opcode 0x59 — xDrip+'s EIGEN bron markeert die laatste met een letterlijke
 * `// TODO more to parse here`, dus zelfs de meest volledige publiek
 * beschikbare referentie heeft 'm niet volledig uitgeplozen. [parseBackfillControl]
 * hieronder herkent alleen OF er een backfill-afsluitpakket binnenkwam, leest
 * de inhoud nog niet.
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
     *  1 slotbyte (altijd 0 — het "specifiedSlot"-motor-modus in xDrip+ is
     *  hier niet van toepassing). [token] wordt door de aanroeper bewaard om
     *  straks (na de sensor se antwoord) tegen diens uitdaging-hash te
     *  vergelijken (zie DexcomG7Crypto.DexcomG7Jpake.calculateHash's
     *  gebruik in DexcomG7Driver.kt). */
    fun buildAuthRequest(token: ByteArray): ByteArray {
        require(token.size == 8) { "token moet 8 bytes zijn" }
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x02)
        buffer.put(token)
        buffer.put(0) // slot
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
}
