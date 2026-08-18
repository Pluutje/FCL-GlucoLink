package com.fclglucolink.app.calibration

import android.content.Context
import com.fclglucolink.app.data.FclGlucoLinkDatabase
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 05/08/2026 (editor, RONDE 43) — dunne laag boven de Room-DAO, zelfde opzet
 * als GlucoseReadingStore.kt: houdt opslag-details buiten de UI/rekenlaag.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — elke functie is
 * nu VERPLICHT gescoped op een [SensorType]: zie CalibrationEntryEntity.kt's
 * kdoc bij het nieuwe [sensorType]-veld voor de cross-slot-vervuilings-/
 * wegveeg-bug die dit voorkomt (zonder scoping zou de kalibratie-fit van de
 * ene slot vingerprik-data van de andere meewegen, en zou een nieuwe-sensor-
 * detectie op de ene slot ook de nog geldige kalibratiedata van de andere
 * gelijktijdig actieve slot wegvegen bij [clearAll]).
 *
 * 11/08/2026 (editor, RONDE 90 — op verzoek: "een algemene lijst met alle
 * vingerprikken [...] waarvan de lijst dan zichtbaar is bij beide sensoren
 * (slots) en je een vinkje kunt zetten als je hem voor die sensor wilt
 * gebruiken") — elke vingerprik is nu ÉÉN gedeelde rij (i.p.v. impliciet
 * "eigendom" van precies één sensor), met per sensor een los aan/uitvinkje
 * (zie CalibrationEntryEntity.kt's kdoc). Dat heeft twee gevolgen:
 *
 * 1) [delete] verwijdert nu ALTIJD overal tegelijk — er is nog maar één rij
 *    per ingevoerde vingerprik, dus "verwijderen" en "overal weg" zijn nu
 *    hetzelfde, precies zoals gevraagd. Geen aparte code nodig.
 * 2) [clearAll]/[clearAllForSensorType] (nog steeds beschikbaar, zie de
 *    DAO's kdoc — een eventuele toekomstige "wis echt alles"-noodknop) worden
 *    NIET meer automatisch aangeroepen bij een nieuwe sensor-sessie (zie
 *    BleConnectionService.kt's RONDE-90-kdoc bij die aanroep-plek) — met een
 *    gedeelde rij zou dat nu een vingerprik kunnen wegvegen die de ANDERE,
 *    gelijktijdig actieve slot nog gebruikt. In plaats daarvan filtert
 *    [entries]/[listEntries] hieronder nu op [sinceMs] (de sensor-start-tijd
 *    van de bekijkende sessie, door de caller meegegeven) — exact het
 *    gevraagde gedrag: "bij de sensoren moeten alleen die vingerprikken
 *    getoond worden die kwa tijd na de sensor start liggen". Een oude
 *    vingerprik van vóór een sensorwissel wordt zo simpelweg niet meer
 *    OPGEHAALD voor de nieuwe sessie, zonder 'm te hoeven wissen — blijft
 *    intact voor de andere slot (of voor de oude sessie's eigen historie,
 *    mocht die ooit nog relevant zijn).
 */
class CalibrationStore(context: Context) {

    private val dao = FclGlucoLinkDatabase.getInstance(context).calibrationEntryDao()

    /**
     * [otherSensorType]/[otherSensorMgdlAtPairing]: de op dit moment
     * gelijktijdig actieve ANDERE slot's sensortype + zijn ruwe sensorwaarde
     * op hetzelfde tijdstip, opportunistisch meegegeven door de caller
     * (CalibrationScreen.kt's add-dialog) — `null` als die andere slot op
     * dat moment geen (recente) meting had. Zonder een raw-waarde kan die
     * andere sensor deze vingerprik nooit gebruiken (zie [entries]/
     * [listEntries]), ongeacht een eventueel aanvinkje.
     */
    suspend fun add(
        entry: CalibrationEntry,
        sensorType: SensorType,
        otherSensorType: SensorType?,
        otherSensorMgdlAtPairing: Double?
    ) {
        dao.insert(
            CalibrationEntryEntity(
                timestampMs = entry.timestampMs,
                fingerstickMgdl = entry.fingerstickMgdl,
                sensorMgdlAtPairing = entry.sensorMgdlAtPairing,
                sensorType = sensorType.name,
                otherSensorType = otherSensorType?.name,
                otherSensorMgdlAtPairing = otherSensorMgdlAtPairing,
                includedForOriginSensor = true,
                includedForOtherSensor = false
            )
        )
    }

    /** 11/08/2026 (editor, RONDE 90) — verwijdert de rij overal tegelijk,
     *  zie klasse-kdoc hierboven. */
    suspend fun delete(id: Long) = dao.delete(id)

    /** 11/08/2026 (editor, RONDE 90) — zet het aan/uitvinkje voor [sensorType]
     *  op deze rij. `isOrigin` bepaalt welke van de twee onafhankelijke
     *  vlaggen (herkomst- of andere-sensor) geraakt wordt — de caller
     *  (CalibrationStore's eigen resolve-logica, zie [toListEntryOrNull])
     *  bepaalt dat al, dus de UI hoeft dat zelf niet opnieuw uit te zoeken. */
    suspend fun setIncluded(id: Long, isOrigin: Boolean, included: Boolean) {
        if (isOrigin) dao.setIncludedForOrigin(id, included) else dao.setIncludedForOther(id, included)
    }

    /** Nog steeds beschikbaar (zie DAO's kdoc), maar sinds RONDE 90 niet
     *  meer automatisch aangeroepen bij een nieuwe sensor-sessie. */
    suspend fun clearAll(sensorType: SensorType) = dao.clearAllForSensorType(sensorType.name)

    /** Voor de fit-wiskunde/grafiek: alleen de rijen die voor [sensorType]
     *  zijn AANGEVINKT, en op of na [sinceMs] liggen. */
    fun entries(sensorType: SensorType, sinceMs: Long): Flow<List<CalibrationEntry>> =
        dao.allRelevantForSensorType(sensorType.name).map { list ->
            list.filter { it.timestampMs >= sinceMs }
                .mapNotNull { it.toCalibrationEntryOrNull(sensorType, requireChecked = true) }
        }

    suspend fun entriesOnce(sensorType: SensorType, sinceMs: Long): List<CalibrationEntry> =
        entries(sensorType, sinceMs).first()

    /** Voor de UI-rijlijst met aan/uitvinkjes: ELKE relevante rij (aangevinkt
     *  of niet), zie [FingerstickListEntry]'s kdoc. */
    fun listEntries(sensorType: SensorType, sinceMs: Long): Flow<List<FingerstickListEntry>> =
        dao.allRelevantForSensorType(sensorType.name).map { list ->
            list.filter { it.timestampMs >= sinceMs }
                .mapNotNull { it.toListEntryOrNull(sensorType) }
        }
}

/** `requireChecked = true` levert alleen aangevinkte rijen op (voor de
 *  fit-wiskunde); `false` levert elke relevante rij op ongeacht vinkje. */
private fun CalibrationEntryEntity.toCalibrationEntryOrNull(
    sensorType: SensorType,
    requireChecked: Boolean
): CalibrationEntry? {
    val isOrigin = sensorType.name == this.sensorType
    val isOther = !isOrigin && sensorType.name == this.otherSensorType
    val raw = when {
        isOrigin -> sensorMgdlAtPairing
        isOther -> otherSensorMgdlAtPairing
        else -> null
    } ?: return null
    val checked = if (isOrigin) includedForOriginSensor else includedForOtherSensor
    if (requireChecked && !checked) return null
    return CalibrationEntry(id = id, timestampMs = timestampMs, fingerstickMgdl = fingerstickMgdl, sensorMgdlAtPairing = raw)
}

private fun CalibrationEntryEntity.toListEntryOrNull(sensorType: SensorType): FingerstickListEntry? {
    val isOrigin = sensorType.name == this.sensorType
    val isOther = !isOrigin && sensorType.name == this.otherSensorType
    val raw = when {
        isOrigin -> sensorMgdlAtPairing
        isOther -> otherSensorMgdlAtPairing
        else -> null
    } ?: return null
    val checked = if (isOrigin) includedForOriginSensor else includedForOtherSensor
    return FingerstickListEntry(
        id = id,
        timestampMs = timestampMs,
        fingerstickMgdl = fingerstickMgdl,
        sensorMgdlAtPairing = raw,
        checked = checked,
        enteredOnThisSensor = isOrigin
    )
}
