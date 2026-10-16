package com.fclglucolink.app.ui

// 09/08/2026 (editor, RONDE 64) — VERVALLEN, NIET MEER GEBRUIKT. Vervangen
// door DexcomG6StatusScreen.kt/CareSensAirStatusScreen.kt (via
// FclGlucoLinkNavHost.kt's ROUTE_DEXCOM_G6_STATUS/ROUTE_CARESENS_STATUS) —
// zie de uitgebreide kdoc bij FclGlucoLinkNavHost() voor het volledige
// herstructureringsverhaal.
//
// 10/08/2026 (editor, RONDE 82, BUGFIX na live-melding — Android Studio gaf
// compile-errors op dit bestand: "Function invocation 'state(...)' expected"
// e.d. op regels 76/77/81/91/92/97/98) — de kdoc hierboven zei al sinds
// Ronde 64 dat dit bestand puur dode code was "bewust NIET meegenomen" bij
// het bouwen van de zip, maar dat klopte niet meer: de zip-bouwstap die dit
// sessie gebruikt (een simpele recursieve `zip` van de hele projectmap) sloot
// dit bestand NIET expliciet uit, dus het zat gewoon in de laatst geleverde
// zip mee — en Gradle compileert domweg ALLE .kt-bestanden onder
// app/src/main/java, dode code of niet. Het bevatte nog aanroepen van de
// VOOR-dual-slot API's (bv. `ConnectionStatusBridge.state` en
// `settings.selectedSensor` zonder een `slot`-argument) die tijdens de
// dual-slot-migratie (Ronde 78/79) overal elders wél zijn omgezet naar
// `state(slot)`/`selectedSensor(slot)` — dit bestand niet, want het werd toen
// (terecht) als niet-meer-aangeroepen dode code beschouwd en overgeslagen.
// Bevestigd met een projectbrede grep: nergens een ECHTE aanroep
// `SensorManagementScreen(...)` (alleen kdoc-vermeldingen van de bestandsnaam
// in andere schermen, ter documentatie van de geschiedenis) — dus veilig om
// de samengestelde-functie hier volledig weg te halen i.p.v. alle zeven
// aanroepen één voor één te repareren voor een functie die toch nooit
// aangeroepen wordt. Alleen dit lege bestand blijft staan (schrijf-
// beveiligde outputs-map laat verwijderen niet toe) — negeer verder.
