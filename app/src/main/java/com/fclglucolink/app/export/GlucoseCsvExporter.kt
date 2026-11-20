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
import java.util.concurrent.TimeUnit

/**
 * RONDE 257 — CSV-uitvoer van de bloedglucosewaarden (Settings > Ondersteuning > CSV-uitvoer).
 *
 * Bestand: één regel per meting, `;` als scheidingsteken. Waarden staan in de eenheid van het
 * scherm (mg/dL of mmol/L). Decimale komma als de app op Nederlands staat, anders een punt.
 *
 * Vingerprikken: een aparte kolom. Elke vingerprik komt op de EERSTE meting op of na zijn tijd
 * (per slot), maar alleen als die meting binnen [FINGERSTICK_MAX_WAIT_MS] ligt. Bij twee
 * vingerprikken op dezelfde meting blijft de laatste staan.
 */
object GlucoseCsvExporter {

    /** Een vingerprik wordt niet aan een meting gekoppeld die meer dan 15 minuten later komt. */
    private const val FINGERSTICK_MAX_WAIT_MS = 15 * 60 * 1000L

    data class Result(val file: File?, val rows: Int, val fingersticksPlaced: Int)

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
        val readings = db.glucoseReadingDao().readingsForExport(since, slot?.name)
        if (readings.isEmpty()) return@withContext Result(null, 0, 0)

        val fingersticks = db.calibrationEntryDao().allOnce()
            .filter { it.timestampMs >= since - FINGERSTICK_MAX_WAIT_MS }
        val placed = placeFingersticks(readings, fingersticks)

        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("fclglucolink_glucose_") }?.forEach { it.delete() }

        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val file = File(
            dir,
            "fclglucolink_glucose_${dayFmt.format(Date(readings.first().timestampMs))}_" +
                "${dayFmt.format(Date(readings.last().timestampMs))}.csv"
        )
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val u = if (unit == GlucoseUnit.MMOL) "mmol_l" else "mgdl"
        val header = if (dutch) {
            "tijd;slot;sensor;glucose_$u;ruw_$u;gekalibreerd_$u;trend_${u}_per_min;vingerprik_$u"
        } else {
            "time;slot;sensor;glucose_$u;raw_$u;calibrated_$u;trend_${u}_per_min;fingerstick_$u"
        }

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
            readings.forEachIndexed { i, r ->
                val sensor = runCatching { SensorType.valueOf(r.sensorType).displayName }.getOrDefault(r.sensorType)
                val finger = placed[i]
                out.write(timeFmt.format(Date(r.timestampMs)))
                out.write(";")
                out.write(r.slot ?: "")
                out.write(";")
                out.write(sensor)
                out.write(";")
                out.write(num(r.glucoseMgdl, 1, 2))
                out.write(";")
                out.write(num(r.rawSensorMgdl ?: r.glucoseMgdl, 1, 2))
                out.write(";")
                out.write(num(r.calibratedMgdl ?: r.glucoseMgdl, 1, 2))
                out.write(";")
                out.write(num(r.trendMgdlPerMin.toDouble(), 2, 3))
                out.write(";")
                out.write(if (finger == null) "" else num(finger.fingerstickMgdl, 1, 2))
                out.write("\n")
            }
        }
        Result(file, readings.size, placed.count { it != null })
    }

    /**
     * Geeft per meting (zelfde volgorde als [readings]) de vingerprik die erop terechtkomt, of null.
     * Per slot wordt apart gezocht, zodat bij "beide slots" elke sensor dezelfde vingerprik krijgt.
     */
    private fun placeFingersticks(
        readings: List<GlucoseReadingEntity>,
        fingersticks: List<CalibrationEntryEntity>
    ): Array<CalibrationEntryEntity?> {
        val result = arrayOfNulls<CalibrationEntryEntity>(readings.size)
        if (fingersticks.isEmpty()) return result
        val bySlot = readings.indices.groupBy { readings[it].slot ?: "" }
        for ((_, indices) in bySlot) {
            for (f in fingersticks) {
                // Eerste meting in deze slot op of na de vingerprik (indices staan oplopend in tijd).
                var lo = 0
                var hi = indices.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (readings[indices[mid]].timestampMs >= f.timestampMs) hi = mid else lo = mid + 1
                }
                if (lo < indices.size) {
                    val idx = indices[lo]
                    if (readings[idx].timestampMs - f.timestampMs <= FINGERSTICK_MAX_WAIT_MS) {
                        result[idx] = f
                    }
                }
            }
        }
        return result
    }
}
