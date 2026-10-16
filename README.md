# FCLGlucoLink

Kleine, losse Android-app die een CGM-sensor koppelt aan AAPS. De app praat
met de sensor over Bluetooth en stuurt elke meting door als xDrip-broadcast
(`com.eveningoutpost.dexdrip.BgEstimate`). AAPS ontvangt die als bron
"xDrip+". De app bevat geen doseerlogica en geen AAPS-plugin.

De app is gemaakt omdat Juggluco te groot is voor wat hier nodig is: een paar
sensoren, stabiel doorsturen naar AAPS, en goede logs om fouten te vinden.

Huidige versie: zie `app/build.gradle.kts` (`versionCode` en `versionName`).
De Over-kaart in de app toont ook het bouwtijdstip.

## Ondersteunde sensoren

| Sensor | Hoe | Opmerking |
|---|---|---|
| Dexcom G6 | Eigen Kotlin-driver (`sensor/dexcomg6/`) | Transmitter-ID invoeren. Haalt gemiste metingen achteraf op (backfill). |
| Dexcom G7 / ONE+ | Eigen Kotlin-driver (`sensor/dexcomg7/`) | Koppelcode van de applicator. J-PAKE en certificaatstap volgen xDrip. |
| CareSens Air | Kotlin + C++ (`sensor/caresensair/`, `app/src/main/cpp/`) | Gebruikt Juggluco's kalibratiecode. Koppelen met de QR-code op de sensor. |
| Accu-Chek SmartGuide | Kotlin (`sensor/accuchek/`) | Eenvoudige, al gekalibreerde waarde. |
| BG-simulator | `sensor/simulator/` | Geen echte sensor. Voor het testen van de route naar AAPS. |

Er zijn twee sensorplekken (slot A en slot B) die tegelijk kunnen draaien.
Eén slot is de "AAPS-zendende" sensor. Het andere slot wacht dan op zijn beurt,
zodat de twee Bluetooth-verbindingen elkaar niet storen (`AapsSlotSchedule`).

## Wat de app verder doet

- Statusscherm met BG-grafiek en voorspelling, per sensor een statusscherm.
- Gladmaken van de metingen (Kalman) met instelbare sterkte.
- Kalibratie (lineair of spline) en inloopfilter voor een nieuwe sensor.
- Zeven alarmen: zeer laag, laag, hoog, zeer hoog, voorspeld laag, voorspeld
  hoog en geen gegevens meer. Geluid, trillen of beide.
- Eenheden mg/dL of mmol/L, taal Engels of Nederlands.
- Meldingen op het vergrendelscherm via de foreground-service.
- Update-controle in het Over-scherm (map op Google Drive) en een
  "Wat is nieuw"-lijst.
- Logbestand per dag (`fclglucolink_JJJJ-MM-DD.txt`), te versturen met de knop
  "Log sturen" in de instellingen.

## Code-indeling

Alles staat onder `app/src/main/java/com/fclglucolink/app/`.

| Map | Inhoud |
|---|---|
| `sensor/` | `SensorDriver`, `SensorRegistry` en een map per sensor. `sensor/ble/` heeft de gedeelde Bluetooth-hulpklassen (slot-planning, GATT-slot, scan-limiet, voorspellende herverbinding). |
| `broadcast/` | `XDripBroadcaster`: de koppeling naar AAPS. |
| `smoothing/`, `calibration/`, `prediction/` | Gladmaken, kalibreren en voorspellen. |
| `alarm/` | Alarmregels en geluid. |
| `data/` | Room-database en instellingen (`AppSettings`). |
| `logging/` | Dagelijks logbestand en log-upload. |
| `update/` | Update-controle en "Wat is nieuw". |
| `ui/` | Alle schermen (Jetpack Compose). `Localization.kt` heeft `tr("English", "Nederlands")`. |

De C++ voor CareSens Air staat in `app/src/main/cpp/` (CMake, JNI).

## Bekende beperkingen en open punten

- G7: na een reeks mislukte koppelpogingen kan de sensor ongeveer 15 minuten
  niet reageren. De app wacht daarom 15 minuten na elke 3 mislukte eerste
  pogingen (alleen voor een sensor die nog nooit een meting gaf).
- G7: herverbinden na een herstart van de app met een bestaande
  Bluetooth-koppeling is nog niet altijd betrouwbaar.
- G6: de transmitter antwoordt soms 's nachts niet op het inlogverzoek. De
  logregels `verbinding duurde …` helpen om de oorzaak te vinden.
- "Running mode" op het G6-scherm klopt niet bij een lang verbroken
  transmitter.
- Samsung's Now Bar toont de melding niet; dat is niet door de app te sturen.

## Tests en logs

- Een foutmelding of afwijking: stuur direct daarna het logbestand. Een nieuw
  bestand van dezelfde dag vervangt het vorige.
- Handige zoektermen in het log: `verbinding duurde`, `auth status`,
  `backfill`, `schrijfactie geweigerd`, `meettijd vastgezet`, `computeReconnectCooldownMs`.

## Herkomst en licentie

De xDrip-broadcast is een Kotlin-port van Juggluco's `SendLikexDrip.java`.
De CareSens Air-ondersteuning gebruikt Juggluco's native kalibratiecode. De G6-
en G7-protocollen volgen xDrip+ (GPL-3.0). Dank aan beide projecten.

De volledige ontwikkelhistorie (verslagen per testronde tot en met v223) staat
niet meer in de repo. Een lokale kopie staat in `_archief/` (niet in git).
