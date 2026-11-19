package com.fclglucolink.app.sensor.juggluco

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fclglucolink.app.logging.DiagnosticFileLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One reading as JugglucoNG (or xDrip) sends it in the xDrip broadcast. */
data class JugglucoReading(
    val glucoseMgdl: Double,
    /** mg/dL per minute (the broadcast has mg/dL per millisecond). */
    val trendMgdlPerMin: Float,
    val timestampMs: Long,
    val sensorStartedAtMs: Long,
    val sourceInfo: String?
)

/**
 * RONDE 256 — passes broadcast readings from the receiver to [JugglucoDriver].
 * The receiver also writes every reading to a CSV file by itself (see [JugglucoBroadcastReceiver]),
 * so no minute is lost when the driver is not running.
 */
object JugglucoBridge {
    private val _readings = MutableSharedFlow<JugglucoReading>(extraBufferCapacity = 512)
    val readings: SharedFlow<JugglucoReading> = _readings.asSharedFlow()

    fun offer(reading: JugglucoReading) {
        _readings.tryEmit(reading)
    }
}

/**
 * RONDE 256 — receives the xDrip broadcast (`com.eveningoutpost.dexdrip.BgEstimate`) that JugglucoNG
 * sends to the apps chosen in its settings. This is the same message FCLGlucoLink sends to AAPS.
 *
 * What it does with a reading:
 *  1. Writes it as one line to `libre3_minuut_<date>.csv` in the log folder (kept forever, 30 days
 *     of 1-minute data is about 1.2 MB). The line has the receive time, the measurement time, the
 *     value, the trend and the source text, so gaps and delays can be analysed later.
 *  2. Hands it to [JugglucoBridge]. [JugglucoDriver] (sensor "Juggluco (Libre 3)" in a free slot)
 *     turns it into a normal reading for the chart, the smoothing and the statistics.
 *
 * It never sends anything to AAPS. [com.fclglucolink.app.broadcast.XDripBroadcaster] leaves this
 * app out of its list of receivers, so the two cannot loop.
 */
class JugglucoBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extras = intent.extras ?: return
        val mgdl = extras.getDouble(EXTRA_BG_ESTIMATE, 0.0)
        val timeMs = extras.getLong(EXTRA_TIMESTAMP, 0L)
        if (mgdl <= 0.0 || timeMs <= 0L) return
        val slopePerMs = extras.getDouble(EXTRA_BG_SLOPE, 0.0)
        val source = extras.getString(EXTRA_SOURCE_INFO)
        val reading = JugglucoReading(
            glucoseMgdl = mgdl,
            trendMgdlPerMin = (slopePerMs * 60_000.0).toFloat(),
            timestampMs = timeMs,
            sensorStartedAtMs = extras.getLong(EXTRA_SENSOR_STARTED_AT, 0L),
            sourceInfo = source
        )
        // Juggluco can send the same reading twice (live and backfill). One line per time stamp.
        val first = synchronized(lock) {
            if (timeMs == lastTimeMs) false else { lastTimeMs = timeMs; true }
        }
        if (!first) return
        if (!loggedFirst) {
            loggedFirst = true
            DiagnosticFileLogger.log(
                "Juggluco-ontvanger: eerste meting ontvangen — $mgdl mg/dL, bron=${source ?: "?"}, " +
                    "tijdstip=${TIME.format(Date(timeMs))}"
            )
        }
        runCatching { appendCsv(context, reading) }
            .onFailure { DiagnosticFileLogger.log("Juggluco-ontvanger: CSV schrijven mislukt: ${it.message}") }
        JugglucoBridge.offer(reading)
    }

    private fun appendCsv(context: Context, r: JugglucoReading) {
        val dir = File(context.getExternalFilesDir(null), "log")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return
        val now = Date()
        val file = File(dir, "libre3_minuut_${DAY.format(now)}.csv")
        val header = !file.exists()
        val sb = StringBuilder()
        if (header) sb.append("ontvangen;gemeten;mgdl;trend_mgdl_per_min;bron\n")
        sb.append(FULL.format(now)).append(';')
            .append(FULL.format(Date(r.timestampMs))).append(';')
            .append(r.glucoseMgdl).append(';')
            .append(String.format(Locale.US, "%.2f", r.trendMgdlPerMin)).append(';')
            .append(r.sourceInfo ?: "").append('\n')
        file.appendText(sb.toString())
    }

    private companion object {
        const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
        const val EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
        const val EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
        const val EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
        const val EXTRA_SENSOR_STARTED_AT = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
        const val EXTRA_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"

        val lock = Any()
        var lastTimeMs = 0L
        @Volatile var loggedFirst = false
        val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val FULL = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
