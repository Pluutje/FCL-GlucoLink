# NOTICE — licentie en herkomst

FCLGlucoLink wordt uitgegeven onder de **GNU General Public License, versie 3**
(zie `LICENSE`). Iedereen die de app (de APK) krijgt, mag de broncode inzien, aanpassen
en doorgeven onder dezelfde licentie. De broncode staat in deze repository.

De app is geen medisch hulpmiddel en komt zonder enige garantie (zie GPL-3.0, artikelen 15 en 16).
Gebruik hem niet als enige basis voor behandeling of insulinedosering.

## Gebruikte of overgenomen onderdelen van andere projecten

| Onderdeel in FCLGlucoLink | Herkomst | Licentie |
|---|---|---|
| CareSens Air-kalibratie (`app/src/main/cpp/caresensair_bridge.cpp`, `libCALCULATION.so`) | Juggluco — https://github.com/j-kaltes/Juggluco (de bibliotheek is uit de Juggluco-APK gehaald; die staat niet in deze repository, maar wordt wel met de app meegeleverd) | GPL-3.0 |
| xDrip-broadcast (`broadcast/XDripBroadcaster.kt`) | Kotlin-port van Juggluco's `SendLikexDrip.java` | GPL-3.0 |
| Dexcom G6- en G7-protocol | Volgt xDrip+ — https://github.com/NightscoutFoundation/xDrip | GPL-3.0 |

Auteursrechten van de overgenomen delen blijven bij hun auteurs (o.a. Jaap Korthals Altes voor Juggluco).

## Broncode van meegeleverde binaire bestanden

De bijbehorende broncode van `libCALCULATION.so` is de broncode van Juggluco
(https://github.com/j-kaltes/Juggluco). Zie GPL-3.0 artikel 6 voor wat dat inhoudt.
