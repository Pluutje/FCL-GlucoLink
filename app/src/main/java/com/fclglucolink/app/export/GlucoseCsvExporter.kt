package com.fclglucolink.app.export

import android.content.Context
import com.fclglucolink.app.calibration.CalibrationEntryEntity
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.FclGlucoLinkDatabase
import com.fclglucolink.app.data.GlucoseReadingEntity
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.ui.AppLanguage
import com.fclglucolink.app.ui.GlucoseUnit
import com.fclglucolink.app.ui.mgdlToMmol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * RONDE 257 — CSV-uitvoer van de bloedglucosewaarden (Settings > Ondersteuning > CSV-uitvoer).
 *
 * Opbouw ("breed"): één regel per minuut waarop minstens één sensor een meting had, met per slot
 * een eigen groep kolommen (sensor, glucose, ruw, gekalibreerd, trend) en één gedeelde kolom
 * voor de vingerprik. Zo geeft Excel per slot een eigen reeks voor een grafiek, plus losse punten
 * voor de vingerprikken, zonder dat het bestand eerst omgebouwd hoeft te worden. Een sensor
 * zonder meting op die minuut laat zijn cellen leeg.
 *
 * Tijd: de minuut van de meting (afgerond op de dichtstbijzijnde minuut), dus metingen van twee
 * sensoren die een paar seconden verschillen komen op dezelfde regel. Staan er twee metingen van
 * dezelfde sensor in één minuut, dan blijft de laatste staan.
 *
 * Eenheid: die van het scherm (mg/dL of mmol/L). Decimale komma als de app op Nederlands staat,
 * anders een punt. Scheidingsteken `;`.
 *
 * Vingerprikken: komen op de EERSTE regel op of na hun tijd, maar alleen als die regel binnen
 * [FINGERSTICK_MAX_WAIT_MS] ligt. Bij twee vingerprikken op dezelfde regel blijft de laatste staan.
 */
object GlucoseCsvExporter {

    /** Een vingerprik wordt niet aan een regel gekoppeld die meer dan 15 minuten later komt. */
    private const val FINGERSTICK_MAX_WAIT_MS = 15 * 60 * 1000L
    private const val MINUTE_MS = 60_000L

    data class Result(val file: File?, val rows: Int, val fingersticksPlaced: Int)

    private class Line(val minuteMs: Long) {
        var firstReadingMs: Long = Long.MAX_VALUE
        val bySlot = HashMap<String, GlucoseReadingEntity>()
        var fingerstick: CalibrationEntryEntity? = null
    }

    /**
     * @param slot null = beide slots.
     * @param days aantal dagen terug; null = alles wat er is.
     */
    suspend fun export(context: Context, slot: SensorSlot?, days: Int?): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = FclGlucoLinkDatabase.getInstance(appContext)
        val settings = AppSettings(appContext)
        val unit = settings.getDisplayUnitOnce()
        val dutch = settings.getAppLanguageOnce() == AppLanguage.DUTCH

        val since = if (days == null) 0L else System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        // Rijen zonder slot (van vóór de 2-sensoren-opzet) kunnen niet aan een kolomgroep hangen.
        val readings = db.glucoseReadingDao().readingsForExport(since, slot?.name).filter { it.slot != null }
        if (readings.isEmpty()) return@withContext Result(null, 0, 0)

        // Welke slots krijgen een kolomgroep: A en/of B, alleen als er metingen van zijn.
        val slotNames = listOf(SensorSlot.A.name, SensorSlot.B.name).filter { name -> readings.any { it.slot == name } }

        // 1. Metingen op regels per minuut.
        val lines = TreeMap<Long, Line>()
        for (r in readings) {
            val key = Math.round(r.timestampMs.toDouble() / MINUTE_MS) * MINUTE_MS
            val line = lines.getOrPut(key) { Line(key) }
            line.bySlot[r.slot!!] = r
            if (r.timestampMs < line.firstReadingMs) line.firstReadingMs = r.timestampMs
        }
        val ordered = lines.values.toList()

        // 2. Vingerprikken op de eerste regel op of na hun tijd.
        val fingersticks = db.calibrationEntryDao().allOnce()
            .filter { it.timestampMs >= since - FINGERSTICK_MAX_WAIT_MS }
        var placed = 0
        for (f in fingersticks) {
            var lo = 0
            var hi = ordered.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (ordered[mid].firstReadingMs >= f.timestampMs) hi = mid else lo = mid + 1
            }
            if (lo < ordered.size && ordered[lo].firstReadingMs - f.timestampMs <= FINGERSTICK_MAX_WAIT_MS) {
                ordered[lo].fingerstick = f
            }
        }
        placed = ordered.count { it.fingerstick != null }

        // 3. Bestand schrijven.
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("fclglucolink_glucose_") }?.forEach { it.delete() }
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val file = File(
            dir,
            "fclglucolink_glucose_${dayFmt.format(Date(ordered.first().minuteMs))}_" +
                "${dayFmt.format(Date(ordered.last().minuteMs))}.csv"
        )
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val u = if (unit == GlucoseUnit.MMOL) "mmol_l" else "mgdl"

        val header = buildList {
            add(if (dutch) "tijd" else "time")
            for (s in slotNames) {
                add("sensor_$s")
                add("glucose_${s}_$u")
                add(if (dutch) "ruw_${s}_$u" else "raw_${s}_$u")
                add(if (dutch) "gekalibreerd_${s}_$u" else "calibrated_${s}_$u")
                add("trend_${s}_${u}_per_min")
            }
            add(if (dutch) "vingerprik_$u" else "fingerstick_$u")
        }.joinToString(";")

        fun num(mgdl: Double, decimalsMgdl: Int, decimalsMmol: Int): String {
            val text = if (unit == GlucoseUnit.MMOL) {
                String.format(Locale.US, "%.${decimalsMmol}f", mgdl.mgdlToMmol())
            } else {
                String.format(Locale.US, "%.${decimalsMgdl}f", mgdl)
            }
            return if (dutch) text.replace('.', ',') else text
        }

        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write(header)
            out.write("\n")
            for (line in ordered) {
                val cells = ArrayList<String>()
                cells.add(timeFmt.format(Date(line.minuteMs)))
                for (s in slotNames) {
                    val r = line.bySlot[s]
                    if (r == null) {
                        repeat(5) { cells.add("") }
                    } else {
                        cells.add(runCatching { SensorType.valueOf(r.sensorType).displayName }.getOrDefault(r.sensorType))
                        cells.add(num(r.glucoseMgdl, 1, 2))
                        cells.add(num(r.rawSensorMgdl ?: r.glucoseMgdl, 1, 2))
                        cells.add(num(r.calibratedMgdl ?: r.glucoseMgdl, 1, 2))
                        cells.add(num(r.trendMgdlPerMin.toDouble(), 2, 3))
                    }
                }
                cells.add(line.fingerstick?.let { num(it.fingerstickMgdl, 1, 2) } ?: "")
                out.write(cells.joinToString(";"))
                out.write("\n")
            }
        }
        Result(file, ordered.size, placed)
    }
}
