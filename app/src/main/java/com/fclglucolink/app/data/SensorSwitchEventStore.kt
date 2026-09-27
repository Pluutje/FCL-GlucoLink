package com.fclglucolink.app.data

import android.content.Context
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * 09/08/2026 (editor, RONDE 64) — dunne laag boven de Room-DAO, zelfde patroon
 * als GlucoseReadingStore.kt. Zie SensorSwitchEventEntity.kt's kdoc voor het
 * volledige verhaal.
 *
 * 10/08/2026 (editor, RONDE 84, BUGFIX) — beide functies VERPLICHT
 * gescoped op [SensorType], zie SensorSwitchEventEntity.kt's kdoc voor de
 * cross-slot-lek die dit oploste (een wisselmarker van de ene slot
 * verscheen ook op de andere slot's grafiek).
 */
class SensorSwitchEventStore(context: Context) {

    private val dao = FclGlucoLinkDatabase.getInstance(context).sensorSwitchEventDao()

    suspend fun record(timestampMs: Long, crossType: Boolean, sensorType: SensorType) {
        dao.insert(
            SensorSwitchEventEntity(
                timestampMs = timestampMs,
                crossType = crossType,
                sensorType = sensorType.name
            )
        )
        // Zelfde opruim-horizon als GlucoseReadingStore (49u) — een
        // wisselmarker ouder dan wat de grafiek toch al kan tonen heeft geen
        // nut meer.
        dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(49))
    }

    fun recentEvents(hours: Long = 48, sensorType: SensorType): Flow<List<SensorSwitchEvent>> {
        val since = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours)
        return dao.recentEventsForSensorType(since, sensorType.name).map { list ->
            list.map { SensorSwitchEvent(it.timestampMs, it.crossType) }
        }
    }
}

/** UI-vriendelijk model, los van de Room-entity — zelfde reden als
 *  GlucoseReading vs. GlucoseReadingEntity elders in dit package. */
data class SensorSwitchEvent(val timestampMs: Long, val crossType: Boolean)
