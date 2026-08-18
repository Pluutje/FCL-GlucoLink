package com.fclglucolink.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * FCLGlucoLink — sensor-wisselmoment (voor het icoontje op de BG-grafiek)
 * ============================================================================
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek: "handig om er een sensor wissel
 * icoontje op de grafiek bij het wissel moment bij te plaatsen wat dan bv
 * binnen het zelfde sensor type minder opvallend van kleur is en bij een
 * sensortype wissel een wat opvallende kleur heeft") — één rij per moment
 * waarop de EERSTE meting van een nieuwe sensor-sessie binnenkwam (zelfde
 * moment als GlucoseReadingStore.trimFrom() al gebruikt, zie
 * BleConnectionService.kt's `firstReadingThisSession`-blok). [crossType]
 * onderscheidt de twee gevallen die de gebruiker vroeg te onderscheiden:
 *  - `false`: nieuwe sensor/transmitter BINNEN hetzelfde sensor-TYPE (bv.
 *    een nieuwe G6-sensorcode op dezelfde transmitter, of een nieuwe
 *    CareSens Air-sensor) — subtiele kleur.
 *  - `true`: wissel naar een ANDER sensor-TYPE (bv. G6 -> CareSens Air) —
 *    opvallende kleur.
 * Bewust een aparte, kleine tabel i.p.v. een extra kolom op elke
 * GlucoseReadingEntity-rij: wisselmomenten zijn zeldzaam (hooguit een paar
 * per maand), een losse tabel met een handjevol rijen is eenvoudiger dan elke
 * meting een grotendeels ongebruikt veld te laten dragen.
 *
 * 10/08/2026 (editor, RONDE 84, BUGFIX na live-melding met screenshots — het
 * activeren van een nieuwe Dexcom G6-sensor plaatste de wisselmarker-lijn
 * niet alleen op Slot B's (Dexcom) grafiek, maar OOK op Slot A's (CareSens
 * Air, die toen al dagenlang ongestoord liep) grafiek) — deze tabel had tot
 * nu toe GEEN sensorType/slot-kolom, dus was er letterlijk niets om op te
 * filteren: elke marker was zichtbaar op ELK tabblad. Precies de bekende,
 * bewust-uitgestelde onvolkomenheid uit RONDE 79's kdoc bij
 * StatusScreen.kt's `switchEvents`-lezing ("bewust niet in deze ronde
 * opgelost") — nu wél. [sensorType] is nullable (net als
 * CalibrationEntryEntity.kt's gelijknamige veld, zelfde migratie-redenering:
 * `ALTER TABLE ... ADD COLUMN` i.p.v. destructive migration, bestaande rijen
 * van vóór deze update blijven simpelweg `null` en verschijnen daardoor
 * nergens meer — verwaarloosbaar, het zijn sowieso al verlopen markers).
 * Slaat de `SensorType`-enum-naam op (`.name`), zelfde stijl als
 * CalibrationEntryEntity/GlucoseReadingEntity.
 */
@Entity(tableName = "sensor_switch_events")
data class SensorSwitchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val crossType: Boolean,
    val sensorType: String? = null
)
