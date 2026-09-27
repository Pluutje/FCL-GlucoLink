package com.fclglucolink.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 09/08/2026 (editor, RONDE 64) — zie SensorSwitchEventEntity.kt's kdoc.
 *
 *  10/08/2026 (editor, RONDE 84) — [recentEvents] (ongefilterd) vervangen
 *  door [recentEventsForSensorType]: zie SensorSwitchEventEntity.kt's
 *  RONDE-84-kdoc voor de cross-slot-lek die dit oploste. */
@Dao
interface SensorSwitchEventDao {

    @Insert
    suspend fun insert(event: SensorSwitchEventEntity)

    @Query(
        "SELECT * FROM sensor_switch_events WHERE timestampMs >= :sinceMs " +
            "AND sensorType = :sensorType ORDER BY timestampMs ASC"
    )
    fun recentEventsForSensorType(sinceMs: Long, sensorType: String): Flow<List<SensorSwitchEventEntity>>

    @Query("DELETE FROM sensor_switch_events WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
