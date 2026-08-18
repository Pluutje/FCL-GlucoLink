package com.fclglucolink.app.calibration

/**
 * 05/08/2026 (editor, RONDE 43) — lokale tegenhanger van AAPS's `CAL`-model
 * (`app.aaps.core.data.model.CAL`), puur de drie velden die de aangeleverde
 * `CalibrationMath.kt`/`SplineCalibrationMath.kt` nodig hebben. `id` is
 * alleen relevant voor de UI (selecteren/verwijderen van een specifieke
 * regel) en wordt door de fit-wiskunde zelf genegeerd.
 */
data class CalibrationEntry(
    val id: Long = 0,
    val timestampMs: Long,
    val fingerstickMgdl: Double,
    val sensorMgdlAtPairing: Double
)

/**
 * 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-database) — rijker
 * UI-model voor de rijlijst met aan/uitvinkjes op CalibrationScreen.kt:
 * anders dan [CalibrationEntry] (dat alleen de AANGEVINKTE rijen bevat, en
 * rechtstreeks de fit-wiskunde in gaat) toont deze ELKE relevante rij voor
 * de bekeken sensor — aangevinkt of niet — zodat de gebruiker een
 * uitgevinkte vingerprik alsnog kan aanvinken. [checked] weerspiegelt
 * `includedForOriginSensor`/`includedForOtherSensor` (afhankelijk van
 * welke kant matcht, zie CalibrationStore.kt) voor de sensor die op dit
 * scherm bekeken wordt. [enteredOnThisSensor] is puur informatief (bv. om
 * in de UI te tonen "hier ingevoerd" vs "ook beschikbaar") — de herkomst-
 * rij kán net als de andere-sensor-rij aan/uitgevinkt worden, er is geen
 * harde beperking die de herkomst-rij verplicht aangevinkt houdt.
 */
data class FingerstickListEntry(
    val id: Long,
    val timestampMs: Long,
    val fingerstickMgdl: Double,
    val sensorMgdlAtPairing: Double,
    val checked: Boolean,
    val enteredOnThisSensor: Boolean
)
