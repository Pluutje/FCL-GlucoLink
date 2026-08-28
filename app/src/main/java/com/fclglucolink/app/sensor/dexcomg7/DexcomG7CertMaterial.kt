package com.fclglucolink.app.sensor.dexcomg7

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+ certificaat-koppelroute: sleutelmateriaal
 * ============================================================================
 *
 * 28/08/2026 (editor, RONDE 144) — herkomst, exact en volledig, zodat dit
 * later navolgbaar blijft:
 *
 * Ronde 112-143 kwamen tot de conclusie dat de PIN/J-PAKE-koppelroute
 * (DexcomG7Crypto.kt/DexcomG7Protocol.kt's `buildAuthRequest`/
 * `buildAuthChallenge`/rondes 1-3) bij deze specifieke sensor WEL leidt tot
 * `AuthStatusRx.isAuthenticated == true`, maar NIET tot `isBonded == true` —
 * xDrip+'s eigen `Plugin.java` (`receivedResponse()`'s `ChallengeReply`-tak)
 * behandelt precies dat geval met een TWEEDE, aanvullende stap: een
 * certificaat-gebaseerde wederzijdse authenticatie (`SendCertificate0` t/m
 * `SendKeyChallengeOut`) vóórdat `TIME_EXTENDED` geschreven wordt. Zonder
 * die stap stuurt de sensor zijn bond-trigger-indicatie nooit — exact het
 * gedrag dat Ronde 141/142/143's live-tests lieten zien.
 *
 * Die stap heeft drie stukken sleutelmateriaal nodig (`context.partA`/
 * `partB`/`partC` in xDrip+'s `Context.java`, alleen bereikbaar via
 * `setPersistence(8/9/10, ...)`, gevuld vanuit de lokale voorkeuren
 * `keks_p1`/`keks_p2`/`keks_p3` — zie `Ob1G5CollectionService.java` regel
 * 1667). Onderzoek deze ronde liet zien: xDrip+'s EIGEN externe-plugin-
 * downloadpad (`Loader.getInstance()`/`Dialog.askIfNeeded()`) staat in de
 * broncode volledig uitgecommentarieerd — dus GEEN netwerk, GEEN download,
 * exact zoals de gebruiker aangaf ("er is 100% zeker geen internet nodig").
 * De actieve pairing-code (`Loader.getLocalInstance()`) gebruikt uitsluitend
 * lokaal, al aanwezig sleutelmateriaal.
 *
 * xDrip+ heeft daar zelf een ingebouwde, bewust-voor-dit-doel-gemaakte
 * exportfunctie voor: hoofdmenu → "Share config via QR code" →
 * "Export KEKS key to another phone" (zichtbaar zodra dat materiaal al op
 * het toestel aanwezig is — precies het geval bij een toestel waar G7-
 * koppeling met xDrip+ al eerder lukte). De gebruiker heeft dat scherm op
 * zijn EIGEN, al werkende xDrip+-installatie (Google Pixel 8, versie
 * 8cf1c99-2026.08.26) opgezocht en de resulterende QR-code (`xdp2:...`,
 * hash 14713F737C1099E1, screenshot 28/08/2026 12:58) aangeleverd. Decodering
 * (gzip + het simpele, publieke `QRcodeUtils.serializeBinaryPrefsMap`-
 * formaat — 2-byte teller, per veld 2-byte sleutellengte+sleutel +
 * 2-byte waardelengte+waarde) leverde de drie onderstaande DER-blokken op,
 * byte-voor-byte tegen die QR-code geverifieerd (zie sessielog).
 *
 * WAT DIT DAADWERKELIJK IS (ter documentatie, geen giswerk — direct uit de
 * DER-structuur af te lezen):
 * - [PART_A]: een X.509-certificaat, Issuer/Subject CN "DEX00PG1"/
 *   "DEX03PG1", met een CRL-distributiepunt bij
 *   `crl.dp.saas.primekey.com` (PrimeKey/EJBCA — Dexcom se eigen fabrieks-
 *   PKI). Dit is een CA-achtig, voor-verificatie-bedoeld certificaat — dat
 *   is naar zijn aard OPENBAAR materiaal (het hele nut van een CA-
 *   certificaat is dat het verspreid wordt om handtekeningen mee te kunnen
 *   controleren), geen geheim.
 * - [PART_B]: een tweede X.509-certificaat, ondertekend door DEX03PG1 —
 *   een tussenliggend/klasse-certificaat (niet aan dit ene fysieke exemplaar
 *   sensor gebonden, zie xDrip+'s `Context.validateParts()`: dit blijft
 *   ongewijzigd hergebruikt voor élke sensor die deze telefoon koppelt).
 * - [PART_C]: een PKCS8-DER-gecodeerde EC-privésleutel (curve secp256r1) —
 *   dit is het gevoelige deel, gebruikt om de sensor se "sign challenge"
 *   te ondertekenen ([DexcomG7Crypto.signWithCertPrivateKey]) en zo aan te
 *   tonen dat de koppelende app een door dit certificaat-paar erkende
 *   identiteit heeft.
 *
 * WAAROM DIT HIER OPGENOMEN IS (i.p.t. bv. een QR-scan-scherm in de app
 * zelf): dit is klasse-/identiteitsmateriaal — hetzelfde voor elke sensor,
 * niet per-sensor uniek (in tegenstelling tot de 4/6-cijferige koppelcode,
 * die per sensor via de UI wordt ingevoerd, zie SettingsScreen.kt). Precies
 * zoals xDrip+ het zelf hergebruikt uit een eenmalig gevulde lokale
 * voorkeur, hoeft dit niet elke koppelpoging opnieuw aangeleverd te worden.
 * Mocht dit ooit moeten wijzigen (bv. Dexcom vernieuwt het klasse-
 * certificaat), dan volstaat een nieuwe export vanuit xDrip+ en het
 * bijwerken van deze drie hex-strings.
 */
internal object DexcomG7CertMaterial {

    private fun hex(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "hex-string moet een even aantal tekens hebben" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** X.509-certificaat "DEX00PG1"/"DEX03PG1" (494 bytes) — zie klasse-kdoc. */
    val PART_A: ByteArray = hex(
        "308201ea3082018fa00302010202142f3c52b6eb08701046d45d78ce81784c9dfe5240300a06082a8648ce3d" +
            "04030230133111300f06035504030c084445583030504731301e170d3230313033303135353930345a170d" +
            "3335313032373135353930345a30133111300f06035504030c0844455830335047313059301306072a8648" +
            "ce3d020106082a8648ce3d03010703420004fb1aca21d8aeec9a4eb51f85304953d977a1ad569799250ff86" +
            "3987f42a3cd9fa4ff571eb568bc6c396277c3dcb51dedaee85513c80a5c4435538a19f5a96348a381c03081" +
            "bd300f0603551d130101ff040530030101ff301f0603551d230418301680149e0f1e36f3f276a701fe8e88" +
            "3a6e26a635bd6afc305a0603551d1f04533051304fa034a0328630687474703a2f2f63726c2e64702e7361" +
            "61732e7072696d656b65792e636f6d2f63726c2f44455830305047312e63726ca217a41530133111300f06" +
            "035504030c084445583030504731301d0603551d0e0416041488f61e81bc4b17f05c6b1be2991d60087cce" +
            "dd79300e0603551d0f0101ff040403020186300a06082a8648ce3d0403020349003046022100aa69cd897e" +
            "c663af5f9e158187df6851ff0756f00c401624564f81a19f5a0785022100daebb9fdb163b731eb0661f1c0" +
            "a1932871a50e399ad1c6f519eabd4c9e7ba013"
    )

    /** X.509-certificaat, ondertekend door DEX03PG1 (465 bytes) — zie klasse-kdoc. */
    val PART_B: ByteArray = hex(
        "308201cd30820174a003020102021419052fcc17530bfa56e49dcafcdacf853ce5ba73300a06082a8648ce3d" +
            "04030230133111300f06035504030c084445583033504731301e170d3233303431343130323831345a170d" +
            "3235303431333130323831335a303a3138303606035504030c2f30312c303030302c303330304c51454343" +
            "7a4142417741412c63696f69653356625132686c5a4d6a64556d357267413059301306072a8648ce3d0201" +
            "06082a8648ce3d030107034200045118c35e9e41e7e0654fee801c52a9c5dfc510ef09597d5cca8461e4af" +
            "9c666714834f2bc903f16fabfc45755b0183f1a09745cdffcb4e2f799e50bed9a6b58ca37f307d300c0603" +
            "551d130101ff04023000301f0603551d2304183016801488f61e81bc4b17f05c6b1be2991d60087ccedd79" +
            "301d0603551d250416301406082b0601050507030206082b06010505070301301d0603551d0e0416041"+
            "4d309e75c0725412d7a7922e3aacfb27f7ebd6be0300e0603551d0f0101ff0404030205a0300a06082a86" +
            "48ce3d0403020347003044022048d4868cf393d9044101b6f07fd68d7f0642805f85da74e2fe9de8dd3507" +
            "f02702201cd1bf7c6c7edd59435e324925fcf0ebb3cae2110d79407c77aa3b93b7bc04cb"
    )

    /** PKCS8-DER EC-privésleutel (secp256r1, 138 bytes) — zie klasse-kdoc; het
     *  gevoelige deel van dit drietal, gebruikt om de sensor se "sign
     *  challenge" te ondertekenen (nooit als string/log gedumpt buiten dit
     *  bestand). */
    val PART_C: ByteArray = hex(
        "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104200"+
            "07cfbd596f6e74477b8c0e9f6f7a174275e101ef6bf7d18caf01181d127b579a144034200" +
            "045118c35e9e41e7e0654fee801c52a9c5dfc510ef09597d5cca8461e4af9c666714834f2" +
            "bc903f16fabfc45755b0183f1a09745cdffcb4e2f799e50bed9a6b58c"
    )
}
