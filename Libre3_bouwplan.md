# Libre 3 / 3 Plus in FCLGlucoLink — bouwplan (concept, 17-11-2026)

Status: onderzoek, nog niets gebouwd. Alles hieronder is afgeleid uit de broncode van
JugglucoNG (GPL-3.0, https://github.com/ctqvva/JugglucoNG) en nog niet op een echte sensor getest.

## 1. Wat de bron laat zien

**Activatie (NFC)** — `libre3/NFC.java`
- Commando `02 <commandByte> 7A` + 10 bytes. Die 10 bytes komen uit de native functie `startTimeIDsum(buf, tijd, accountId)`:
  de huidige tijd en een LibreView-account-ID-nummer.
- Het antwoord gaat naar `interpret3NFC2` (native). Daaruit komen o.a. de sensorgegevens voor Bluetooth.
- Er is een "legacy fallback" voor sensoren die al geactiveerd zijn (`ESTABLISHED_ACTIVATION_FALLBACK`).

**Bluetooth-beveiliging** — `Libre3GattCallback.java`, `ECDHCrypto.java`
- Eerste verbinding: ECDH-handshake met certificaten (patchcertificaat 140 bytes, eigen sleutel 65 bytes,
  challenge, nonce). Uitkomst: een sleutel `kAuth` per sensor, die wordt bewaard (`Natives.getLibre3kAuth`).
- Volgende verbindingen: `kAuth` hergebruiken (`isPreAuthorized`), geen volledige handshake.
- Daarna gaan alle metingen versleuteld (`intEncrypt` / `intDecrypt`): live (`fast_data`, 1 minuut),
  historie en klinische data (backfill via `fillHistory`, `fillClinical`).
- In ECDHCrypto zitten uit de Abbott-app gehaalde certificaten en sleutels. Juridisch grijs; Abbott kan ze intrekken.

**Native deel**
- `processint`, `processbar`, `initcrypt`, `intDecrypt`, `saveLibre3MinuteL` e.d. zijn native (JNI).
- Ik vond in de repo geen bron voor de kern van de Libre 3-crypto (alleen o.a. `bcrypt/` met AES-CCM en `abbott.c`
  voor Libre 1/2). Waarschijnlijk is die kern een voorgebouwde bibliotheek. **Open vraag:** is die bibliotheek
  open te gebruiken (licentie, herkomst)?

## 2. Hoofdlijn

1. **Referentie (sensor 1)**: activeren en gebruiken met JugglucoNG. Nu al 1-minuut-data voor je algoritme.
   Tegelijk Bluetooth-verkeer opnemen (Android: ontwikkelaarsopties, HCI snoop log) en de Juggluco-log bewaren.
2. **Protocol vastleggen**: UUID's, volgorde van de handshake, berichtformaten. Uit bron + opname.
3. **Bouwen in FCLGlucoLink (nieuwe driver `sensor/libre3/`)**, in dezelfde stijl als G7/CareSens/SmartGuide:
   - 3a. Alleen Bluetooth + opgeslagen `kAuth` van een sensor die al met onze eigen activatie is gestart (nog niet mogelijk zonder 3b).
   - 3b. NFC-activatie (`startTimeIDsum` e.d.) — het moeilijkste stuk.
   - 3c. Handshake, versleutelde metingen, backfill.
4. **Test op sensor 2 (vrijdag)**: ongeopend, nooit met een andere app activeren.

## 3. Risico's

- Sensor 2 is mogelijk verloren als onze activatie mislukt of de sleutels niet kloppen.
- Een sensor die al door een andere app geactiveerd is, kunnen wij waarschijnlijk niet overnemen.
- Native kern onbekend (zie boven). Zonder die kern: alleen zelf nabouwen, dat kost veel tijd.
- Abbott kan het protocol of de sleutels wijzigen (firmware-updates).
- Eén sensor praat met één app tegelijk; de officiële Libre-app dus niet tegelijk gebruiken.

## 4. 1-minuut-data (30 dagen)

- De opslag is nu 48 uur en 5 minuten-metingen. Voor 30 dagen aan minuutmetingen (±43.000 punten):
  een apart exportbestand (CSV) naast de normale opslag, geen wijziging aan wat naar AAPS gaat.
- De G6 blijft AAPS-zendend; de FSL 3+ is een niet-zendend slot (bestaande slotkeuze).
- Lead-extrapolatie: eerst alleen offline op opgenomen data (replay), niet naar AAPS sturen.

## 5. Eerstvolgende stappen

1. Sensor 1 met JugglucoNG activeren + HCI-opname starten, log bewaren.
2. Uitzoeken waar de native Libre 3-kern vandaan komt (JugglucoNG-bron of voorgebouwd) en onder welke licentie.
3. Protocol-uitwerking (UUID's, berichten) uit de opname + bron.
4. Beslissen: native bibliotheek hergebruiken of zelf nabouwen.
5. Pas daarna code in FCLGlucoLink.
