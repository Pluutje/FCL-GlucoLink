package com.fclglucolink.app.calibration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationEntryDao {

    @Insert
    suspend fun insert(entry: CalibrationEntryEntity)

    /** Oplopend in tijd — zowel de fit-wiskunde als de UI-lijst/grafiek
     *  werken prettiger met een vaste, voorspelbare volgorde. */
    @Query("SELECT * FROM calibration_entries ORDER BY timestampMs ASC")
    fun all(): Flow<List<CalibrationEntryEntity>>

    @Query("DELETE FROM calibration_entries WHERE id = :id")
    suspend fun delete(id: Long)

    /** 05/08/2026 (editor, RONDE 43) — aangeroepen zodra een nieuwe
     *  sensor-sessie start (zelfde hook als GlucoseReadingDao.deleteFrom()
     *  in BleConnectionService.kt) — kalibratiedata hoort per sensor, niet
     *  over een sensorwissel heen. Zie CalibrationEntryEntity's kdoc voor
     *  waarom dit een volledige leging is i.p.v. een tijd-gebaseerde trim. */
    @Query("DELETE FROM calibration_entries")
    suspend fun clearAll()

    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — type-gefilterde
    // varianten, zie CalibrationEntryEntity's kdoc bij [sensorType]: BleConnectionService.kt
    // gebruikt vanaf nu uitsluitend deze twee i.p.v. [all]/[clearAll] hierboven,
    // zodat kalibratie van twee gelijktijdig actieve slots elkaar niet meer kan
    // vervuilen of wegvegen. [all]/[clearAll] blijven bestaan voor eventueel
    // toekomstig gebruik (bv. een "wis echt alles"-noodknop).
    @Query("SELECT * FROM calibration_entries WHERE sensorType = :sensorType ORDER BY timestampMs ASC")
    fun allForSensorType(sensorType: String): Flow<List<CalibrationEntryEntity>>

    @Query("DELETE FROM calibration_entries WHERE sensorType = :sensorType")
    suspend fun clearAllForSensorType(sensorType: String)

    // 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-database) — een rij
    // is relevant voor [sensorType] zodra dat OF de herkomst-sensor OF de
    // "andere slot"-sensor is, zie CalibrationEntryEntity.kt's kdoc. De
    // eigenlijke aangevinkt/uitgevinkt-filtering en de "alleen na sensor-
    // start"-tijdfilter gebeuren bewust in CalibrationStore.kt (Kotlin),
    // niet hier in SQL — het aantal rijen is altijd klein (een handvol
    // vingerprikken per sensor-sessie), dus leesbaarheid weegt hier zwaarder
    // dan een complexere SQL CASE-expressie.
    @Query("SELECT * FROM calibration_entries WHERE sensorType = :sensorType OR otherSensorType = :sensorType ORDER BY timestampMs ASC")
    fun allRelevantForSensorType(sensorType: String): Flow<List<CalibrationEntryEntity>>

    @Query("UPDATE calibration_entries SET includedForOriginSensor = :included WHERE id = :id")
    suspend fun setIncludedForOrigin(id: Long, included: Boolean)

    @Query("UPDATE calibration_entries SET includedForOtherSensor = :included WHERE id = :id")
    suspend fun setIncludedForOther(id: Long, included: Boolean)
}
