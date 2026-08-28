package com.fclglucolink.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseReadingDao {

    @Insert
    suspend fun insert(reading: GlucoseReadingEntity)

    /** Laatste 24 uur, oplopend in tijd — precies wat de grafiek nodig heeft. */
    @Query("SELECT * FROM glucose_readings WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun recentReadings(sinceMs: Long): Flow<List<GlucoseReadingEntity>>

    @Query("SELECT * FROM glucose_readings ORDER BY timestampMs DESC LIMIT 1")
    fun latestReading(): Flow<GlucoseReadingEntity?>

    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — GEÏNTRODUCEERD
    // als type-gefilterde varianten voor de per-slot-tabs.
    //
    // 28/08/2026 (editor, RONDE 153, VERWIJDERD — CRITIEKE FIX na live-
    // melding: twee gelijktijdig gekoppelde CareSens Air-sensoren "lijken
    // weer samen te vloeien") — deze `sensorType`-gefilterde queries
    // (`recentReadingsForSensorType`/`latestReadingForSensorType`) waren de
    // eigenlijke OORZAAK van die bug: `sensorType` alleen is GEEN
    // betrouwbare slot-discriminator zodra beide slots hetzelfde sensortype
    // draaien (bv. CareSens Air + CareSens Air) — dan filteren deze queries
    // helemaal niets meer, en komen beide fysieke sensoren se metingen op
    // ELK tabblad terecht. Vervangen door [recentReadingsForSlot]/
    // [latestReadingForSlot] hieronder, die op de nieuwe [GlucoseReadingEntity.
    // slot]-kolom filteren — DIE is per definitie altijd uniek per fysieke
    // koppeling, ongeacht welk sensortype er toevallig in beide slots
    // draait. Zie GlucoseReadingEntity.kt's kdoc bij `slot` voor de
    // volledige analyse.
    @Query("SELECT * FROM glucose_readings WHERE timestampMs >= :sinceMs AND slot = :slot ORDER BY timestampMs ASC")
    fun recentReadingsForSlot(sinceMs: Long, slot: String): Flow<List<GlucoseReadingEntity>>

    @Query("SELECT * FROM glucose_readings WHERE slot = :slot ORDER BY timestampMs DESC LIMIT 1")
    fun latestReadingForSlot(slot: String): Flow<GlucoseReadingEntity?>

    /** Huishouding — voorkomt dat de tabel onbeperkt blijft groeien.
     *  Aangeroepen bij elke insert vanuit GlucoseReadingStore. */
    @Query("DELETE FROM glucose_readings WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    /** 02/08/2026 (editor, na live-test — "de oude waarden van de virtuele
     *  sensor die daarvoor draaide" bleven zichtbaar in de grafiek, door
     *  elkaar met de echte CareSens Air-historie) — metingen worden nergens
     *  naar sensor-type getagd opgeslagen, dus bij het wisselen van sensor
     *  (bv. simulator -> echte sensor) bleven oude metingen van de vorige
     *  sensor gewoon staan en werden ze samen met de nieuwe, echte historie
     *  getoond.
     *
     *  02/08/2026 (editor, controlevraag van de gebruiker: "bij een normale
     *  sensor wissel heeft de nieuwe sensor amper historische data ... hij
     *  zou dan alleen de data uit het geheugen moeten wissen vanaf het
     *  tijdstip van de eerste nieuwe sensor waarde, zodat de historie wel
     *  zichtbaar blijft") — een eerdere versie van deze fix wiste bij ELKE
     *  sensorwissel meteen de VOLLEDIGE tabel (zie git-geschiedenis/README:
     *  `deleteAll()`), wat ook bij een gewone vervanging van dezelfde
     *  sensor (bv. oude CareSens Air -> nieuwe CareSens Air) de nog geldige
     *  recente historie van de oude sensor wegveegde en een lege grafiek
     *  gaf tot de nieuwe sensor zijn eerste meting aanleverde. Terecht
     *  bezwaar: correct in plaats daarvan is pas wissen VANAF het moment
     *  van de eerste nieuwe meting (aangeroepen door
     *  BleConnectionService.kt zodra de eerste meting van een nieuw
     *  gestarte driver-sessie binnenkomt) — dat behoudt de oude,
     *  chronologisch eerdere historie (naadloze aansluiting in de
     *  grafiek), en ruimt alleen op wat overlapt met of ná de nieuwe
     *  sensor z'n eigen data-bereik valt (voorkomt het door-elkaar-lopen
     *  van twee sensoren in hetzelfde tijdvak). */
    @Query("DELETE FROM glucose_readings WHERE timestampMs >= :fromMs")
    suspend fun deleteFrom(fromMs: Long)

    /** 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, latente bug
     *  gevonden tijdens de refactor) — [deleteFrom] hierboven veegt ALLE
     *  rijen vanaf [fromMs] weg, ongeacht sensorType. Met twee gelijktijdig
     *  actieve slots (bv. G6 in Slot A + CareSens Air in Slot B) zou een
     *  "eerste meting van een nieuwe sessie"-trim op de ÉÉN slot dus ook de
     *  nog geldige, lopende historie van de ANDERE slot wegvegen — een
     *  daadwerkelijk dataverlies-risico dat vóór de 2-sensoren-architectuur
     *  niet kon optreden (er was toch maar één actieve sensor tegelijk).
     *  BleConnectionService.kt roept vanaf nu deze type-gefilterde variant
     *  aan i.p.v. [deleteFrom].
     *
     *  28/08/2026 (editor, RONDE 153, VERWIJDERD) — zelfde reden als
     *  [recentReadingsForSlot]'s kdoc hierboven: `sensorType` alleen is geen
     *  betrouwbare slot-scoping zodra beide slots hetzelfde sensortype
     *  draaien (dan zou een nieuwe-sessie-trim op de ene slot ook de nog
     *  geldige historie van de ANDERE, gelijktijdig actieve slot van
     *  hetzelfde type wegvegen — exact de bugklasse die deze functie's
     *  eigen kdoc destijds probeerde te voorkomen, alleen onvolledig).
     *  Vervangen door [deleteFromForSlot]. */
    @Query("DELETE FROM glucose_readings WHERE timestampMs >= :fromMs AND slot = :slot")
    suspend fun deleteFromForSlot(fromMs: Long, slot: String)
}
