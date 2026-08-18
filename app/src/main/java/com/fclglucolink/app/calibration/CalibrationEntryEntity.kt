package com.fclglucolink.app.calibration

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * FCLGlucoLink — kalibratie-invoer (ronde 43)
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek, "kalibratie optie toevoegen",
 * gebaseerd op de door de gebruiker aangeleverde AAPS-broncode
 * `CalibrationMath.kt`/`SplineCalibrationMath.kt`/`*CalibrationPlugin.kt`)
 * — één vingerprik-tegen-sensor-koppel. `sensorMgdlAtPairing` is de RUWE
 * (ongekalibreerde) sensorwaarde op het moment van de vingerprik — exact
 * zoals AAPS's `CAL`-model dat ook vastlegt (`fingerstickMgdl` vs
 * `sensorMgdlAtPairing`), zodat de fit-wiskunde uit `CalibrationMath.kt`/
 * `SplineCalibrationMath.kt` vrijwel ongewijzigd overgenomen kon worden
 * (alleen het AAPS-type `CAL` vervangen door deze lokale entiteit).
 *
 * Bewust GEEN `sensorSessionId`-koppeling — in plaats daarvan wordt bij een
 * nieuwe sensor-sessie geleegd wat bij dat SENSORTYPE hoort (zie
 * `CalibrationStore.clearAll()`'s aanroep in `BleConnectionService.kt`,
 * dezelfde plek als `GlucoseReadingStore.trimFrom()`).
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, latente bug
 * gevonden tijdens dezelfde refactor als GlucoseReadingDao's
 * deleteFromForSensorType()) — vóór vandaag was er geen [sensorType]-kolom:
 * met twee gelijktijdig actieve slots (bv. G6 in Slot A + CareSens Air in
 * Slot B) zou (a) de kalibratie-fit van de ÉÉN sensor vingerprik-data van de
 * ANDERE sensor meewegen (onzin — elke fysieke sensor heeft zijn eigen
 * afwijkingskarakteristiek) en erger nog (b) een nieuwe-sensor-detectie op
 * de ÉNE slot zou via `clearAll()` ook de nog geldige kalibratiedata van de
 * ANDERE, gelijktijdig actieve slot volledig wegvegen. Nullable/geen
 * DEFAULT nodig — zelfde precedent als `rawSensorMgdl` in MIGRATION_1_2:
 * bestaande rijen van vóór deze kolom blijven gewoon null (onschadelijke,
 * niet meer opgehaalde rommel, geen migratie-crash).
 *
 * 11/08/2026 (editor, RONDE 90 — op verzoek: één gedeelde vingerprik-
 * database waar BEIDE slots uit kunnen putten, met een aan/uit-vinkje per
 * sensor) — vier nieuwe velden, alle met een backward-compatible default
 * zodat bestaande rijen zich exact gedragen als vóór deze ronde:
 *
 * [sensorType]/[sensorMgdlAtPairing] blijven de "herkomst"-sensor (waar de
 * vingerprik daadwerkelijk tegen ingevoerd is) — ongewijzigd. [otherSensorType]/
 * [otherSensorMgdlAtPairing] zijn de ANDERE, gelijktijdig actieve slot's
 * sensortype + ruwe sensorwaarde op HETZELFDE moment (opportunistisch
 * meegevangen bij het invoeren, zie CalibrationScreen.kt's add-dialog —
 * `null` als die andere slot op dat moment geen (recente) meting had, in
 * welk geval deze vingerprik simpelweg niet voor die sensor bruikbaar is).
 *
 * [includedForOriginSensor] (default true — "de sensor waar je 'm invoert
 * staat 'm automatisch aangevinkt") en [includedForOtherSensor] (default
 * false — "de andere sensor komt 'm wel in de lijst maar standaard
 * uitgevinkt") sturen per sensor aan of deze entry meetelt in de fit — zie
 * CalibrationStore.kt's kdoc voor hoe deze twee vlaggen samen met de
 * "alleen na sensor-start"-tijdfilter de oude, hardere `clearAllForSensorType()`-
 * aanpak (die de HELE rij wegveegde bij een nieuwe sensor-sessie) vervangen.
 */
@Entity(tableName = "calibration_entries")
data class CalibrationEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val fingerstickMgdl: Double,
    val sensorMgdlAtPairing: Double,
    val sensorType: String? = null,
    val otherSensorType: String? = null,
    val otherSensorMgdlAtPairing: Double? = null,
    val includedForOriginSensor: Boolean = true,
    val includedForOtherSensor: Boolean = false
)
