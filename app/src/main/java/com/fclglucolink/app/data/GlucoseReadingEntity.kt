package com.fclglucolink.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType

/**
 * 30/07/2026 (editor) — lokale opslag puur voor het status-/grafiekscherm
 * (recente uren). Dit is GEEN vervanging van AAPS's eigen geschiedenis —
 * de xDrip-broadcast (XDripBroadcaster) is en blijft de bron van waarheid
 * voor AAPS zelf; deze tabel bestaat alleen zodat FCLGlucoLink na een
 * herstart niet met een lege grafiek begint.
 */
@Entity(tableName = "glucose_readings")
data class GlucoseReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val glucoseMgdl: Double,
    val trendMgdlPerMin: Float,
    val timestampMs: Long,
    val sensorStartedAtMs: Long,
    val sensorType: String,
    // 05/08/2026 (editor, RONDE 43) — zie GlucoseReading.rawSensorMgdl's
    // kdoc. Room-migratie: nieuwe kolom met een NOT NULL-default gelijk aan
    // 0.0 zou bestaande rijen fout labelen (0 mg/dL is geen zinnig "raw"),
    // dus nullable i.p.v. een default — bestaande rijen (van vóór ronde 43)
    // krijgen gewoon null, en de UI valt dan terug op glucoseMgdl zelf (zie
    // toReading()) — functioneel identiek aan "geen kalibratie toegepast",
    // wat voor die oude rijen ook gewoon waar is.
    val rawSensorMgdl: Double? = null,
    // 21/08/2026 (editor, RONDE 119 — BUGFIX na live-melding: "Calibrated en
    // Filtered zijn continu gelijk") — [GlucoseReading.calibratedMgdl]
    // (Ronde 113) werd HELEMAAL NIET opgeslagen: deze kolom ontbrak
    // volledig, dus toReading() hieronder viel steeds terug op de klasse-
    // default (`= glucoseMgdl`, oftewel de FINALE, al-gesmoothde waarde).
    // StatusScreen.kt's "Calibrated"-kolom toonde zo bij elke lezing die via
    // GlucoseReadingStore ging (dus ALTIJD, ook de "laatste meting" op het
    // startscherm) in werkelijkheid gewoon de Filtered-waarde nogmaals,
    // onder het verkeerde label — vandaar dat ze nooit uit elkaar liepen.
    // Zelfde nullable-zonder-default-patroon als [rawSensorMgdl] hierboven:
    // bestaande rijen (van vóór deze ronde) krijgen null, UI valt terug op
    // glucoseMgdl — functioneel identiek aan "geen kalibratie toegepast",
    // wat voor die oude rijen (waar dit veld sowieso al verloren was) niet
    // meer te reconstrueren is.
    val calibratedMgdl: Double? = null,
    // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX — live-melding: twee
    // gelijktijdig gekoppelde CareSens Air-sensoren (slot A + slot B)
    // "lijken weer samen te vloeien [...] geen goede scheiding tussen de
    // beide slots") — vóór deze ronde had deze tabel GEEN kolom die
    // vastlegde uit WELKE slot een meting kwam, alleen [sensorType]. De
    // per-slot-filtering die sinds RONDE 79 bestaat (GlucoseReadingDao.
    // recentReadingsForSensorType()/latestReadingForSensorType(), nu
    // VERWIJDERD, zie dat bestand se kdoc) filterde dus ALLEEN op
    // sensortype — genoeg zolang de twee actieve slots verschillende
    // types draaiden (bv. G6 + CareSens Air, de oorspronkelijke opstelling
    // van de gebruiker zelf), maar zinloos zodra beide slots HETZELFDE
    // sensortype draaien: dan komen beide fysieke sensoren se metingen
    // onder exact dezelfde `sensorType`-waarde binnen, en kan geen enkele
    // sensorType-gefilterde query ze nog uit elkaar houden — precies het
    // gemelde symptoom. Nullable (zonder default, zelfde patroon als
    // [rawSensorMgdl]/[calibratedMgdl] hierboven): bestaande rijen van
    // vóór deze migratie hebben geen bekende slot en verdwijnen simpelweg
    // (niet: worden fout toegewezen) uit de per-slot-tabbladen totdat ze
    // buiten het 48u-venster vallen — een kortstondig, zichzelf
    // herstellend "gat" in de historie, geen blijvend dataverlies (de
    // "Combi"-tab, die ongefilterd blijft, toont ze gewoon door).
    val slot: String? = null
)

fun GlucoseReading.toEntity(slot: SensorSlot): GlucoseReadingEntity = GlucoseReadingEntity(
    glucoseMgdl = glucoseMgdl,
    trendMgdlPerMin = trendMgdlPerMin,
    timestampMs = timestampMs,
    sensorStartedAtMs = sensorStartedAtMs,
    sensorType = sensorType.name,
    rawSensorMgdl = rawSensorMgdl,
    calibratedMgdl = calibratedMgdl,
    slot = slot.name
)

fun GlucoseReadingEntity.toReading(): GlucoseReading = GlucoseReading(
    glucoseMgdl = glucoseMgdl,
    trendMgdlPerMin = trendMgdlPerMin,
    timestampMs = timestampMs,
    sensorStartedAtMs = sensorStartedAtMs,
    sensorType = runCatching { SensorType.valueOf(sensorType) }.getOrDefault(SensorType.CARESENS_AIR),
    rawSensorMgdl = rawSensorMgdl ?: glucoseMgdl,
    calibratedMgdl = calibratedMgdl ?: glucoseMgdl
)
