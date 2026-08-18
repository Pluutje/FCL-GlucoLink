package com.fclglucolink.app.data

import android.content.Context
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * 30/07/2026 (editor) — dunne laag boven de Room-DAO: houdt de opslag-details
 * (entity/mapping, huishouding) buiten de UI-laag. GEEN caching in het
 * geheugen nodig — Room's Flow geeft toch al reactief bijgewerkte data aan
 * de UI door.
 *
 * 30/07/2026 (editor, na feedback: "wil in de grafiek tot zeker 24u, liever
 * 48u terug kunnen swipen") — bewaarde eerder maar 24u (en StatusScreen.kt
 * vroeg daar zelfs maar 6u van op, zie daar) — nu 48u, met een kleine marge
 * (49u) in de opruimgrens zodat de oudste nog-gevraagde meting niet precies
 * op de grens verdwijnt tussen opvragen en opruimen door. Bij een meting
 * elke 5 minuten is 48u ~576 rijen — verwaarloosbaar voor SQLite.
 */
class GlucoseReadingStore(context: Context) {

    private val dao = FclGlucoLinkDatabase.getInstance(context).glucoseReadingDao()

    suspend fun record(reading: GlucoseReading) {
        dao.insert(reading.toEntity())
        // Huishouding hier (i.p.v. een aparte periodieke taak) — simpel en
        // vaak genoeg: elke meting is een goed moment om oude data op te
        // ruimen. 49u i.p.v. exact 48u — zie kdoc hierboven.
        dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(49))
    }

    /** 10/08/2026 (editor, RONDE 79) — `sensorType == null` (default) geeft de
     *  bestaande, ongefilterde weergave (voor de "Combi"-tab); een concreet
     *  type geeft alleen die slot's eigen historie (voor de Dexcom G6-/
     *  CareSens-tabs). */
    fun recentReadings(hours: Long = 48, sensorType: SensorType? = null): Flow<List<GlucoseReading>> {
        val since = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours)
        val flow = if (sensorType == null) {
            dao.recentReadings(since)
        } else {
            dao.recentReadingsForSensorType(since, sensorType.name)
        }
        return flow.map { list -> list.map { it.toReading() } }
    }

    fun latestReading(sensorType: SensorType? = null): Flow<GlucoseReading?> {
        val flow = if (sensorType == null) dao.latestReading() else dao.latestReadingForSensorType(sensorType.name)
        return flow.map { it?.toReading() }
    }

    /** 02/08/2026 (editor) — zie kdoc bij GlucoseReadingDao.deleteFrom(): bij
     *  het wisselen van sensor (nieuwe/andere fysieke sensor, of van
     *  simulator naar echt en omgekeerd) hoort de oude, chronologisch
     *  EERDERE historie zichtbaar te blijven (naadloze aansluiting in de
     *  grafiek) — alleen wat overlapt met of ná [fromMs] valt wordt
     *  opgeruimd, aangeroepen zodra de EERSTE meting van een nieuw gestarte
     *  sensor-sessie binnenkomt (zie BleConnectionService.kt). Bewust GEEN
     *  volledige wipe meer bij het simpelweg kiezen/pairen van een sensor
     *  — dat zou bij een normale sensorvervanging (nieuwe sensor van
     *  hetzelfde type, amper eigen historie) de nog geldige recente data
     *  van de oude sensor onnodig wegvegen. */
    /** 10/08/2026 (editor, RONDE 79) — nu VERPLICHT gescoped op [sensorType]:
     *  zie GlucoseReadingDao.deleteFromForSensorType()'s kdoc — zonder deze
     *  scoping zou een nieuwe-sessie-trim op de ene slot ook de nog geldige
     *  historie van een gelijktijdig actieve ANDERE slot wegvegen. */
    suspend fun trimFrom(timestampMs: Long, sensorType: SensorType) {
        dao.deleteFromForSensorType(timestampMs, sensorType.name)
    }
}
