package com.fclglucolink.app.sensor.ble

/**
 * ============================================================================
 * FCLGlucoLink — VERWIJDERD (ronde 38), dit bestand bewust leeg gelaten
 * ============================================================================
 *
 * 04/08/2026 (editor, ronde 38) — de ronde-36-noodgreep "Always rescan
 * immediately" (Juggluco-standaardpad, cooldownMs=0 na elke disconnect)
 * is hier verwijderd. Ronde 37's `SCAN_MODE_LOW_LATENCY` +
 * `MATCH_MODE_AGGRESSIVE`-scanfix in `CareSensAirDriver.kt` (op basis van
 * de gedecompileerde ECHTE fabrikants-app, com.isens.csair v1.2.14) bracht
 * de gemeten "tax" (het verschil tussen de geplande en de daadwerkelijke
 * scan-tot-match-tijd) terug van een trimodaal patroon (25-33s/85-92s/
 * 148-152s/tot 270s uitschieters) naar een strakke ~26-30s-baseline, direct
 * bevestigd met een logbestand-vergelijking vóór/na de fix (zie README.md).
 * Op die baseline was er geen ruimte meer om nog iets te winnen met
 * "meteen doorscannen zonder cooldown" — de schakelaar kostte alleen nog
 * onnodig batterijverbruik zonder enig voordeel, dus is 'm weer weggehaald
 * uit `SettingsScreen.kt`, `CareSensAirDriver.kt` en `FclGlucoLinkApp.kt`.
 *
 * Dit bestand kan de werk-omgeving niet daadwerkelijk verwijderen (alleen
 * overschrijven), vandaar dat het hier bewust leeg achterblijft i.p.v.
 * gewoon te verdwijnen — geen enkele andere plek in de codebase importeert
 * of gebruikt nog iets uit dit bestand.
 */
