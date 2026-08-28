package com.fclglucolink.app.data

import android.content.Context
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorSlot
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

    /** 28/08/2026 (editor, RONDE 153) — [slot] nu VERPLICHT: zie
     *  GlucoseReadingEntity.kt's kdoc bij `slot` — zonder deze kolom kon een
     *  meting van twee gelijktijdig gekoppelde sensoren van HETZELFDE type
     *  (bv. CareSens Air + CareSens Air) niet meer aan de juiste slot
     *  toegeschreven worden. */
    suspend fun record(reading: GlucoseReading, slot: SensorSlot) {
        dao.insert(reading.toEntity(slot))
        // Huishouding hier (i.p.v. een aparte periodieke taak) — simpel en
        // vaak genoeg: elke meting is een goed moment om oude data op te
        // ruimen. 49u i.p.v. exact 48u — zie kdoc hierboven.
        dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(49))
    }

    /**
     * 10/08/2026 (editor, RONDE 79) — `slot == null` (default) geeft de
     * bestaande, ongefilterde weergave (voor de "Combi"-tab); een concrete
     * slot geeft alleen die slot's eigen historie (voor de Dexcom G6-/
     * CareSens-tabs).
     *
     * 28/08/2026 (editor, RONDE 153, CRITIEKE FIX — live-melding: twee
     * gelijktijdig gekoppelde CareSens Air-sensoren "lijken weer samen te
     * vloeien [...] geen goede scheiding tussen de beide slots") — deze
     * functie filterde voorheen op [SensorType] i.p.v. [SensorSlot]. Dat
     * werkte toevallig zolang de twee actieve slots verschillende
     * sensortypes draaiden, maar zodra beide slots HETZELFDE type draaien
     * (zoals hier gemeld) is `sensorType` geen bruikbare discriminator meer
     * — beide fysieke sensoren se metingen staan onder identiek dezelfde
     * `sensorType`-waarde, dus een sensorType-filter kan ze niet uit elkaar
     * houden. [SensorSlot] is dat per definitie WEL, ongeacht welk
     * sensortype er toevallig in beide slots draait — zie
     * GlucoseReadingEntity.kt's kdoc bij `slot` voor de volledige analyse.
     */
    fun recentReadings(hours: Long = 48, slot: SensorSlot? = null): Flow<List<GlucoseReading>> {
        val since = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours)
        val flow = if (slot == null) {
            dao.recentReadings(since)
        } else {
            dao.recentReadingsForSlot(since, slot.name)
        }
        return flow.map { list -> list.map { it.toReading() } }
    }

    /** Zie [recentReadings]'s kdoc voor de RONDE 153-fix (slot i.p.v.
     *  sensorType als filtersleutel). */
    fun latestReading(slot: SensorSlot? = null): Flow<GlucoseReading?> {
        val flow = if (slot == null) dao.latestReading() else dao.latestReadingForSlot(slot.name)
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
    /** 10/08/2026 (editor, RONDE 79) — nu VERPLICHT gescoped, zodat een
     *  nieuwe-sessie-trim op de ene slot niet ook de nog geldige historie
     *  van een gelijktijdig actieve ANDERE slot wegveegt.
     *
     *  28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — was gescoped op
     *  [SensorType] (zie GlucoseReadingDao.deleteFromForSlot()'s kdoc) —
     *  zelfde bugklasse als [recentReadings]: met twee gelijktijdig actieve
     *  slots van HETZELFDE sensortype zou een trim op de ene slot ook de
     *  nog geldige historie van de andere slot van dat type hebben
     *  weggeveegd. Nu gescoped op [SensorSlot], per definitie uniek. */
    suspend fun trimFrom(timestampMs: Long, slot: SensorSlot) {
        dao.deleteFromForSlot(timestampMs, slot.name)
    }
}
