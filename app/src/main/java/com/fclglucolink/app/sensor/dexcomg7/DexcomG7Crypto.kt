package com.fclglucolink.app.sensor.dexcomg7

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+ EC-J-PAKE koppel-handshake (RONDE 112)
 * ============================================================================
 *
 * 17/08/2026 (editor, RONDE 112, op verzoek: "wil ik graag verder met de
 * verdere implementatie van de dexcom g7 [...] code zover in orde brengen
 * dat zodra ik er eentje krijg ik gelijk kan beginnen met testen. [...] Heb
 * je daarvoor nog de xdrip of juggluco code nodig") — de gebruiker had de
 * volledige xDrip+-broncode (`uploads/xDrip-2026.08.08.zip`, hetzelfde
 * archief dat destijds ook voor de G6-driver als referentie diende) én
 * Juggluco's broncode (`uploads/Juggluco.zip`) al eerder aangeleverd; dit
 * bestand is een zo direct mogelijke Kotlin-vertaling van xDrip+'s eigen
 * `libkeks`-module (package `jamorham.keks`, auteur "JamOrHam" — xDrip+'s
 * langjarige maintainer), specifiek de klassen `Curve.java`/`KeyPair.java`/
 * `JECPoint.java`/`Packet.java`/`Context.java`/`Calc.java`. Onafhankelijk
 * gekruischeckt tegen Juggluco's eigen (native, closed-source) G7-
 * implementatie: beide gebruiken exact dezelfde BLE-karakteristiek-UUID's en
 * opcodes (zie DexcomG7Protocol.kt), wat een sterke aanwijzing is dat de
 * hieronder geporte wiskunde ook daadwerkelijk klopt — Juggluco's auteur zelf
 * bevestigt in een publieke GitHub-discussie (gui-dos/DiaBLE#17) dat xDrip+'s
 * J-PAKE-implementatie "succesvol" is.
 *
 * WAAROM J-PAKE (i.t.t. G6's vaste-AES-sleutel, zie DexcomG6Crypto.kt): de
 * G6-transmitter deelt zijn AES-sleutel via een simpele, vaste afleiding uit
 * de 6-karakter transmitter-ID. De G7 gebruikt in plaats daarvan een
 * "Elliptic Curve Password-Authenticated Key Exchange" (RFC-ontwerp
 * draft-cragie-tls-ecjpake-01, curve secp256r1): beide kanten (telefoon en
 * sensor) bewijzen aan elkaar dat ze de 4-cijferige koppelcode kennen ZONDER
 * die code zelf over de lucht te versturen, en leiden daarna een gedeelde
 * AES-sleutel af die geen enkele afluisteraar kan reconstrueren, zelfs niet
 * met alle verzonden BLE-pakketten in bezit. Vier ronden komen elk overeen
 * met een stap in het protocol (zie DexcomG7PairingStateMachine.kt voor hoe
 * dit precies aansluit op de daadwerkelijke BLE-schrijf/notify-volgorde):
 * Round 1, Round 2 (beide kanten wisselen een "commitment" aan hun eigen
 * sleutelpaar uit, met een zero-knowledge-bewijs (Schnorr-stijl) dat ze de
 * bijbehorende privésleutel kennen zonder 'm te onthullen), Round 3 (beide
 * kanten combineren de vier publieke punten uit Ronde 1+2 met hun eigen
 * privésleutel EN de (nooit verzonden) koppelcode, en bewijzen wéér met een
 * zero-knowledge-bewijs dat ze dat correct deden), en tot slot de
 * sleutelafleiding zelf (SHA-256 van de x-coördinaat van het resulterende
 * gedeelde punt, ingekort tot 16 bytes voor gebruik als AES-128-sleutel).
 *
 * BEWUSTE VEREENVOUDIGINGEN t.o.v. de Java-bron (geen protocol-afwijkingen,
 * puur implementatie-detail):
 * - SHA-256: de Java-bron heeft een eigen, handgeschreven SHA-256 (`jamorham.
 *   libkeks.SHA256`, puur om zonder extra dependency te kunnen). Hier
 *   gewoon `java.security.MessageDigest.getInstance("SHA-256")` — wiskundig
 *   identiek, een kant-en-klare, uitgebreid geteste JDK-implementatie i.p.v.
 *   zelfgeschreven bit-schuif-code overzetten (minder foutkans).
 * - Sleutelpaar-generatie: de Java-bron genereert een vers EC-sleutelpaar via
 *   Bouncy Castle's JCE-SPI-klassen (`KeyPairGeneratorSpi.EC`, met een cast
 *   naar `BCECPublicKey`/`BCECPrivateKey`) — een indirecte, provider-
 *   afhankelijke omweg. Hier rechtstreeks: een willekeurige privésleutel
 *   `d` in [1, Q-1] kiezen en het publieke punt berekenen als `G·d` — exact
 *   wat die SPI-klassen ONDER DE MOTORKAP ook doen, maar zonder de
 *   Android-gevoelige provider-cast (zie hieronder).
 * - De QR-code-certificaat-koppelroute (`DSAChallenger.java`,
 *   `CertInfoTxMessage.java`/`CertInfoRxMessage.java` in de Java-bron) is
 *   NIET geport — dat is xDrip+'s fallback-pad voor het zeldzame geval dat
 *   de normale PIN-koppeling ondanks succesvolle authenticatie geen OS-bond
 *   oplevert, en vereist een los, met een QR-scan verkregen fabrieks-
 *   certificaat. Zelfs xDrip+'s eigen `Plugin.java` behandelt dit als
 *   duidelijk secundair pad (`throw new InvalidParameterException("Missing
 *   QR code")` als het niet beschikbaar is). Bewust uitgesteld, zie
 *   DexcomG7PairingStateMachine.kt's kdoc.
 *
 * BELANGRIJKE ANDROID-VAL, BEWUST VERMEDEN: Android heeft van huis uit een
 * eigen, uitgeklede "BC"-provider ingebouwd die in naam conflicteert met
 * Bouncy Castle's eigen provider-registratie (`Security.addProvider(new
 * BouncyCastleProvider())` kan op Android tot verwarrende
 * `NoSuchAlgorithmException`'s leiden). xDrip+'s `libkeks` vermijdt dit door
 * Bouncy Castle's klassen NOOIT via de JCE-provider-registratie aan te
 * roepen (dus nooit `KeyPairGenerator.getInstance("EC", "BC")`), maar altijd
 * rechtstreeks te importeren en te instantiëren (`ECNamedCurveTable`,
 * `ECCurve`, `ECPoint`, kale klassen, geen provider-lookup) — dat patroon is
 * hier exact overgenomen.
 *
 * Nog NIET tegen een echte G7/ONE+-sensor geverifieerd (de gebruiker heeft
 * er nog geen) — verwacht bijstellen na de eerste live-test, precies zoals
 * DexcomG6Driver.kt's eigen kdoc destijds al aangaf voor de G6.
 */

/** Curve.java */
internal object DexcomG7Curve {
    private val random = SecureRandom()
    private const val CURVE_NAME = "secp256r1"
    val curveSpec: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
    val G: ECPoint = curveSpec.g
    val curve: ECCurve = curveSpec.curve
    val Q: BigInteger = curve.order
    private val qMinus1: BigInteger = Q.subtract(BigInteger.ONE)
    val curveBits: Int = curve.fieldSize
    val fieldSize: Int = (curveBits + 7) / 8
    val packetSize: Int = fieldSize * 5

    /** Willekeurige exponent in [1, Q-1] — gebruikt voor zowel verse
     *  sleutelparen als de zero-knowledge-bewijzen se eigen "v". */
    fun randomExponent(): BigInteger = BigIntegers.createRandomInRange(BigInteger.ONE, qMinus1, random)
}

/** KeyPair.java — hier alleen de databehouder + verse-generatie-functie;
 *  de DER-(de)serialisatie-constructors (nodig voor de QR-cert-route) zijn
 *  bewust niet geport, zie klasse-kdoc hierboven. */
internal class DexcomG7KeyPair(val privateKey: BigInteger, val publicKey: ECPoint) {
    companion object {
        /** Vers, willekeurig sleutelpaar — zie klasse-kdoc voor waarom dit
         *  rechtstreeks (i.p.v. via Bouncy Castle's KeyPairGeneratorSpi) gebeurt. */
        fun generate(): DexcomG7KeyPair {
            val d = DexcomG7Curve.randomExponent()
            val q = DexcomG7Curve.G.multiply(d).normalize()
            return DexcomG7KeyPair(d, q)
        }
    }
}

/** JECPoint.java — (de)serialisatie van een EC-punt naar/van een vast-lange
 *  byte-array (fieldSize x-coördinaat + fieldSize y-coördinaat). */
private fun pointFromBytes(xBytes: ByteArray, yBytes: ByteArray): ECPoint =
    DexcomG7Curve.curve.createPoint(
        BigIntegers.fromUnsignedByteArray(xBytes),
        BigIntegers.fromUnsignedByteArray(yBytes)
    )

private fun ECPoint.toFixedLengthBytes(): ByteArray {
    val normalized = this.normalize()
    val x = normalized.xCoord.encoded
    val y = normalized.yCoord.encoded
    val out = ByteArray(x.size + y.size)
    System.arraycopy(x, 0, out, 0, x.size)
    System.arraycopy(y, 0, out, x.size, y.size)
    return out
}

/**
 * Packet.java — één J-PAKE-rondepakket: twee EC-punten (het publieke
 * sleuteldeel en het zero-knowledge-bewijs se "commitment") + de bewijs-hash
 * zelf. `output()`/`parse()` zijn elkaars inverse en samen precies
 * [DexcomG7Curve.packetSize] (160) bytes — de exacte hoeveelheid data die
 * xDrip+/Juggluco per ronde over de ExtraData-karakteristiek (F8083538)
 * sturen, in stukjes van hoogstens 20 bytes (BLE-MTU), zie
 * DexcomG7PairingStateMachine.kt.
 */
internal class DexcomG7Packet(val hash: BigInteger, val point1: ECPoint, val point2: ECPoint) {

    fun output(): ByteArray {
        val fieldSize = DexcomG7Curve.fieldSize
        val buffer = ByteBuffer.allocate(DexcomG7Curve.packetSize)
        buffer.put(point1.toFixedLengthBytes())
        buffer.put(point2.toFixedLengthBytes())
        buffer.put(BigIntegers.asUnsignedByteArray(fieldSize, hash))
        val array = buffer.array()
        check(array.size == DexcomG7Curve.packetSize) { "Invalid J-PAKE packet size" }
        return array
    }

    companion object {
        /** Volgorde exact zoals Packet.java's `ID_LIST`: punt1.x, punt1.y,
         *  punt2.x, punt2.y, hash — elk [DexcomG7Curve.fieldSize] bytes. */
        fun parse(packet: ByteArray): DexcomG7Packet? {
            if (packet.size < DexcomG7Curve.packetSize) return null
            val fieldSize = DexcomG7Curve.fieldSize
            val buf = ByteBuffer.wrap(packet)
            fun next(): ByteArray {
                val b = ByteArray(fieldSize)
                buf.get(b)
                return b
            }
            val p1x = next(); val p1y = next(); val p2x = next(); val p2y = next(); val h = next()
            return DexcomG7Packet(
                hash = BigIntegers.fromUnsignedByteArray(h),
                point1 = pointFromBytes(p1x, p1y),
                point2 = pointFromBytes(p2x, p2y)
            )
        }
    }
}

/**
 * Context.java — de sessiestatus van één koppelpoging: onze twee
 * sleutelparen (`keyA`/`keyB`, corresponderend met de twee "rondes" die WIJ
 * naar de sensor sturen), het wachtwoord (de 4-cijferige koppelcode van de
 * sensor-applicator), de vaste protocol-ID's "alice"/"bob" (zie
 * DexcomG7Jpake's kdoc), en de PAKKETTEN DIE WE VAN DE SENSOR ONTVANGEN
 * (`receivedRound1/2/3` — expliciet los van onze eigen `keyA`/`keyB`, niet
 * te verwarren: [DexcomG7Jpake.getRoundXPacket] bouwt WAT WIJ STUREN uit
 * `keyA`/`keyB`, terwijl `receivedRoundX` bewaart wat de sensor TERUGSTUURT).
 */
internal class DexcomG7JpakeContext(pairingCode: String) {

    /** Vaste, protocol-brede party-ID's — letterlijke bytes uit Config.java's
     *  ALICE_B/BOB_B ("wij" resp. "de sensor"), puur gebruikt als domain-
     *  separation-input in de zero-knowledge-hash, geen geheime waarde. */
    val alice: ByteArray = hexToBytes("36C69656E647")
    val bob: ByteArray = hexToBytes("375627675627")

    val keyA: DexcomG7KeyPair = DexcomG7KeyPair.generate()
    val keyB: DexcomG7KeyPair = DexcomG7KeyPair.generate()

    /** Context.java's `getPasswordBytes()`: G7's 4-cijferige code krijgt GEEN
     *  prefix (die is alleen voor de 6-karakter G6-achtige variant, hier niet
     *  van toepassing — zie Plugin.java's `passwordBytes.length > 4`-check in
     *  DexcomG7PairingStateMachine.kt's kdoc, waar dit onderscheid bepaalt
     *  welke afsluitende "geef data"-opcode gebruikt wordt). */
    val passwordBytes: ByteArray = pairingCode.toByteArray(StandardCharsets.UTF_8)
    val passwordBigInteger: BigInteger get() = BigIntegers.fromUnsignedByteArray(passwordBytes)

    var receivedRound1: DexcomG7Packet? = null
    var receivedRound2: DexcomG7Packet? = null
    var receivedRound3: DexcomG7Packet? = null

    /** 8-byte uitdaging die de sensor terugstuurt tijdens RequestAuth — nodig
     *  om [DexcomG7Jpake.calculateHash] te kunnen berekenen. */
    var challenge: ByteArray? = null

    /** Zodra gezet: de afgeleide 16-byte AES-sleutel, zie
     *  [DexcomG7Jpake.getShortSharedKey]. Bewaard zodat een volgende
     *  verbinding met dezelfde sensor niet opnieuw hoeft te onderhandelen. */
    var savedKey: ByteArray? = null

    companion object {
        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().uppercase()
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val idx = i * 2
                out[i] = ((Character.digit(clean[idx], 16) shl 4) + Character.digit(clean[idx + 1], 16)).toByte()
            }
            return out
        }
    }
}

/**
 * Calc.java — de daadwerkelijke J-PAKE-wiskunde. Elke functie hieronder komt
 * regel-voor-regel overeen met haar Java-naamgenoot; zie de klasse-kdoc
 * bovenaan dit bestand voor de protocol-uitleg op hoog niveau.
 */
internal object DexcomG7Jpake {

    /** Ons Round 1-pakket (te versturen), opgebouwd uit `context.keyA`. */
    fun getRound1Packet(context: DexcomG7JpakeContext): DexcomG7Packet =
        buildRoundPacket(DexcomG7Curve.G, context.keyA, context.alice)

    /** Ons Round 2-pakket (te versturen), opgebouwd uit `context.keyB`. */
    fun getRound2Packet(context: DexcomG7JpakeContext): DexcomG7Packet =
        buildRoundPacket(DexcomG7Curve.G, context.keyB, context.alice)

    private fun buildRoundPacket(g: ECPoint, key: DexcomG7KeyPair, party: ByteArray): DexcomG7Packet {
        val zkp = Zkp(g, key, party)
        return DexcomG7Packet(zkp.proof, key.publicKey, zkp.gv)
    }

    /** Valideert een pakket dat de SENSOR naar ons stuurde, tegen `context.bob`
     *  (het party-ID dat de sensor voor zichzelf gebruikt). Round 1 en Round 2
     *  worden op precies dezelfde manier gevalideerd (Calc.java hergebruikt
     *  hiervoor letterlijk dezelfde functie voor beide). */
    fun validateReceivedRound1Or2(packet: DexcomG7Packet?, bob: ByteArray): Boolean {
        if (packet == null) return false
        return validateZeroKnowledgeProof(DexcomG7Curve.G, packet.point1, packet.point2, packet.hash, bob)
    }

    fun validateRound1Packet(context: DexcomG7JpakeContext): Boolean =
        validateReceivedRound1Or2(context.receivedRound1, context.bob)

    fun validateRound2Packet(context: DexcomG7JpakeContext): Boolean =
        validateReceivedRound1Or2(context.receivedRound2, context.bob)

    /** Ons Round 3-pakket: combineert de VIER publieke punten (onze
     *  keyA/keyB + de sensor se ontvangen round1/round2-punten) met de nooit-
     *  verzonden koppelcode. */
    fun getRound3Packet(context: DexcomG7JpakeContext): DexcomG7Packet {
        val packet1 = requireNotNull(context.receivedRound1) { "Round1 nog niet ontvangen" }
        val packet2 = requireNotNull(context.receivedRound2) { "Round2 nog niet ontvangen" }
        val x1 = context.keyA.publicKey
        val x2 = context.keyB.privateKey
        val x3 = packet1.point1
        val x4 = packet2.point1
        val s = context.passwordBigInteger
        val x2s = x2.multiply(s).mod(DexcomG7Curve.Q)
        val x134 = x1.add(x3).add(x4).normalize()
        val a = x134.multiply(x2s).normalize()
        val zkp = Zkp(x134, DexcomG7KeyPair(x2s, a), context.alice)
        return DexcomG7Packet(zkp.proof, a, zkp.gv)
    }

    /** Valideert de sensor se Round 3-pakket — dit is de stap die daadwerkelijk
     *  bevestigt dat de sensor de JUISTE koppelcode kende (via `s` in de
     *  wiskunde), zonder die code zelf ooit over de lucht te zien. */
    fun validateRound3Packet(context: DexcomG7JpakeContext): Boolean {
        val packet = context.receivedRound3 ?: return false
        val x1 = context.keyA.publicKey
        val x2 = context.keyB.publicKey
        val x3 = requireNotNull(context.receivedRound1) { "Round1 nog niet ontvangen" }.point1
        val g = x1.add(x2).add(x3).normalize()
        return validateZeroKnowledgeProof(g, packet.point1, packet.point2, packet.hash, context.bob)
    }

    /** De volle 32-byte gedeelde sleutel (SHA-256 van de x-coördinaat van het
     *  resulterende punt) — alleen [getShortSharedKey] (eerste 16 bytes,
     *  bruikbaar als AES-128-sleutel) wordt daadwerkelijk gebruikt. */
    fun getSharedKey(context: DexcomG7JpakeContext): ByteArray? {
        val point1 = context.receivedRound3?.point1 ?: return null
        val x2 = context.keyB.privateKey
        val x4 = requireNotNull(context.receivedRound2) { "Round2 nog niet ontvangen" }.point1
        val s = context.passwordBigInteger
        val key = point1.subtract(x4.multiply(x2.multiply(s).mod(DexcomG7Curve.Q))).multiply(x2).normalize()
        return sha256(key.normalize().xCoord.encoded)
    }

    fun getShortSharedKey(context: DexcomG7JpakeContext): ByteArray? =
        getSharedKey(context)?.copyOf(16)

    /** De 8-byte challenge-response-hash: AES-128-ECB(sleutel, uitdaging ||
     *  uitdaging), eerste 8 bytes van het resultaat — gebruikt in BEIDE
     *  richtingen: wij sturen 'm naar de sensor (AuthChallengeTx) en
     *  verifiëren de sensor se eigen, spiegelbeeldige antwoord ermee (zie
     *  DexcomG7PairingStateMachine.kt). */
    fun calculateHash(context: DexcomG7JpakeContext): ByteArray? {
        val data = context.challenge ?: return null
        val key = context.savedKey ?: getShortSharedKey(context) ?: return null
        val doubleData = ByteArray(16)
        System.arraycopy(data, 0, doubleData, 0, 8)
        System.arraycopy(data, 0, doubleData, 8, 8)
        val aesBytes = aesEcbEncryptNoPadding(key, doubleData) ?: return null
        return aesBytes.copyOf(8)
    }

    private fun aesEcbEncryptNoPadding(key: ByteArray, plaintext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(plaintext)
    } catch (e: Exception) {
        null
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** ZKP.java (Calc.java se binnenklasse) — het Schnorr-achtige zero-
     *  knowledge-bewijs "ik ken de privésleutel `x` bij publieke sleutel
     *  `key.publicKey`, zonder 'm te onthullen": kies een verse willekeurige
     *  `v`, stuur `gv = g^v` mee, en een "proof" `r = v - h·x mod Q` waarbij
     *  `h` een hash is over (g, gv, publicKey, party) — de ontvanger kan
     *  daarna `g^r · publicKey^h == gv` verifiëren zonder `x` ooit te zien. */
    private class Zkp(private val g: ECPoint, private val keyPair: DexcomG7KeyPair, private val party: ByteArray) {
        private val exponent: BigInteger = DexcomG7Curve.randomExponent()
        val gv: ECPoint by lazy { g.multiply(exponent).normalize() }
        val proof: BigInteger by lazy {
            exponent.subtract(
                getZeroKnowledgeHash(g, gv, keyPair.publicKey, party).multiply(keyPair.privateKey)
            ).mod(DexcomG7Curve.Q)
        }
    }

    private fun validateZeroKnowledgeProof(
        g: ECPoint,
        publicKey: ECPoint,
        gv: ECPoint,
        proof: BigInteger,
        party: ByteArray
    ): Boolean {
        val hash = getZeroKnowledgeHash(g, gv, publicKey, party)
        val check = g.multiply(proof).add(publicKey.multiply(hash)).normalize()
        return check.equals(gv.normalize())
    }

    private fun getZeroKnowledgeHash(g: ECPoint, gv: ECPoint, gx: ECPoint, party: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
        updateWithLength(digest, g.getEncoded(false))
        updateWithLength(digest, gv.getEncoded(false))
        updateWithLength(digest, gx.getEncoded(false))
        updateWithLength(digest, party)
        return BigIntegers.fromUnsignedByteArray(digest.digest()).mod(DexcomG7Curve.Q)
    }

    private fun updateWithLength(digest: MessageDigest, bytes: ByteArray) {
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
}
