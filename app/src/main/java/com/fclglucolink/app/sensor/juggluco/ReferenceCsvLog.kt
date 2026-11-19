package com.fclglucolink.app.sensor.juggluco

import android.content.Context
import com.fclglucolink.app.sensor.GlucoseReading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RONDE 256 — one CSV line per reading of the reference sensor (the AAPS-sending slot, for example
 * the G6), next to `libre3_minuut_<date>.csv` of the Juggluco test. Same log folder, so the "send log"
 * dialog finds it. It only writes a file. It never changes a reading or what goes to AAPS.
 *
 * Columns: time, slot, sensor type, raw (as the sensor gave it), calibrated, filtered (this is what
 * goes to AAPS), trend in mg/dL per minute.
 */
object ReferenceCsvLog {

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(
        context: Context,
        slotName: String,
        raw: GlucoseReading,
        calibrated: GlucoseReading,
        filtered: GlucoseReading
    ) {
        val dir = File(context.getExternalFilesDir(null), "log")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return
        val file = File(dir, "referentie_${day.format(Date(filtered.timestampMs))}.csv")
        val sb = StringBuilder()
        if (!file.exists()) sb.append("tijd;slot;sensor;ruw_mgdl;gekalibreerd_mgdl;gefilterd_mgdl;trend_mgdl_per_min\n")
        sb.append(full.format(Date(filtered.timestampMs))).append(';')
            .append(slotName).append(';')
            .append(filtered.sensorType.name).append(';')
            .append(String.format(Locale.US, "%.1f", raw.rawSensorMgdl)).append(';')
            .append(String.format(Locale.US, "%.1f", calibrated.glucoseMgdl)).append(';')
            .append(String.format(Locale.US, "%.1f", filtered.glucoseMgdl)).append(';')
            .append(String.format(Locale.US, "%.2f", filtered.trendMgdlPerMin)).append('\n')
        file.appendText(sb.toString())
    }
}
