# FCLGlucoLink — native module for CareSens Air.
#
# 01/08/2026 (editor) — dit CMake-bestand hoort bij taak "Design + build
# CareSens Air native calibration bridge" (zie README voor de volledige
# aanleiding: nRF Connect-onderzoek tegen de echte sensor toonde aan dat
# CareSens Air GEEN standaard Bluetooth Glucose Profile gebruikt, maar een
# eigen protocol waarbij de ruwe sensordata pas via een closed-source
# kalibratie-algoritme (`air1_opcal4_algorithm`, uit `libCALCULATION.so`,
# gebundeld in de officiële Juggluco-app) omgezet wordt naar een echte
# mg/dL-waarde).
#
# Bewust MINIMAAL gehouden — dit bouwt alleen `caresensair_bridge.cpp`, een
# nieuw eigen JNI-laagje. Het bouwt NIET `libCALCULATION.so` zelf (dat is een
# prebuilt, closed-source bibliotheek, gehaald uit de gebruiker's eigen geïnstalleerde
# Juggluco-apk — zie `app/src/main/jniLibs/arm64-v8a/libCALCULATION.so`) — die
# wordt runtime via dlopen/dlsym geladen (zelfde aanpak als Juggluco's eigen
# `air/java.cpp` doet), niet build-time gelinkt. Dat vermijdt elke aanname
# over welke NDK/toolchain-versie de bibliotheek ooit gebouwd heeft (dlopen/
# dlsym werkt onafhankelijk daarvan, zolang het CPU-type overeenkomt).
#
# `air.hpp` is een LETTERLIJKE, ongewijzigde kopie van Juggluco's eigen
# header (GPL-3, zie AboutScreen.kt voor de bronvermelding) — bevat alleen
# struct-definities (geen andere Juggluco-bestanden nodig, zelfstandig
# bruikbaar). Bewust gekopieerd i.p.v. met de hand overgetypt: de status-
# struct die het algoritme gebruikt heeft driehonderd-plus velden — met de
# hand overtypen zou een reëel risico op een verkeerd uitgelijnde struct
# geven, wat zich pas als een stille, subtiel foute glucosewaarde zou tonen.
cmake_minimum_required(VERSION 3.22.1)

project("caresensair_bridge")

add_library(
    caresensair_bridge
    SHARED
    caresensair_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    caresensair_bridge
    ${log-lib}
    dl
)
