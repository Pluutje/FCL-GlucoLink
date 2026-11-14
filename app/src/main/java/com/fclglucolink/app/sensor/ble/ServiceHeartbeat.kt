package com.fclglucolink.app.sensor.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.fclglucolink.app.logging.DiagnosticFileLogger

/**
 * Stilte-detector voor de sensorservice. Eigen thread (geen coroutine, zodat
 * hij ook werkt als de dispatchers vastzitten) die elke 30 s:
 *  1. controleert of er sinds de vorige tik ongewoon veel tijd verstreken is
 *     (proces bevroren/gepauzeerd door Android, Doze, energiebesparing) en dat
 *     logt, met de Doze-/batterijbesparingsstatus;
 *  2. de hoofdthread een kleine taak geeft en meet of die binnen 10 s uitgevoerd
 *     wordt; zo niet, dan wordt de stack van de hoofdthread (en van threads die
 *     op een lock wachten) gelogd, zodat te zien is waar het vastzit.
 * Op een gezonde telefoon schrijft dit niets naar het log, behalve één regel
 * bij het starten.
 */
object ServiceHeartbeat {

    private const val TICK_MS = 30_000L
    private const val LATE_TICK_MS = 90_000L
    private const val MAIN_THREAD_TIMEOUT_MS = 10_000L
    private const val MAX_FRAMES = 25

    @Volatile private var thread: Thread? = null
    @Volatile private var mainThreadAnswerAt = 0L

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        val t = Thread({ loop(app) }, "fcl-heartbeat").apply { isDaemon = true }
        thread = t
        t.start()
        DiagnosticFileLogger.log("Hartslag gestart: elke ${TICK_MS / 1000}s controle op stilte en vastgelopen hoofdthread")
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop(context: Context) {
        val main = Handler(Looper.getMainLooper())
        var last = System.currentTimeMillis()
        var lastMainBlockedLogAt = 0L
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            val elapsed = now - last
            last = now
            if (elapsed > LATE_TICK_MS) {
                DiagnosticFileLogger.log(
                    "STILTE: de service heeft ${elapsed / 1000}s niet gedraaid (verwacht ${TICK_MS / 1000}s). " +
                        powerState(context)
                )
            }

            val sentAt = System.currentTimeMillis()
            mainThreadAnswerAt = 0L
            main.post { mainThreadAnswerAt = System.currentTimeMillis() }
            try {
                Thread.sleep(MAIN_THREAD_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (mainThreadAnswerAt == 0L && now - lastMainBlockedLogAt > 60_000L) {
                lastMainBlockedLogAt = now
                logBlocked(sentAt)
            }
        }
    }

    private fun logBlocked(sentAt: Long) {
        val sb = StringBuilder("HOOFDTHREAD VASTGELOPEN: taak van ${(System.currentTimeMillis() - sentAt) / 1000}s geleden niet uitgevoerd. Stack hoofdthread:\n")
        Looper.getMainLooper().thread.stackTrace.take(MAX_FRAMES).forEach { sb.append("    at ").append(it).append('\n') }
        runCatching {
            Thread.getAllStackTraces().forEach { (th, frames) ->
                if (th !== Looper.getMainLooper().thread && th.state == Thread.State.BLOCKED) {
                    sb.append("Thread '").append(th.name).append("' BLOCKED:\n")
                    frames.take(12).forEach { sb.append("    at ").append(it).append('\n') }
                }
            }
        }
        DiagnosticFileLogger.log(sb.toString().trimEnd())
    }

    private fun powerState(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val idle = runCatching { pm?.isDeviceIdleMode }.getOrNull()
        val saver = runCatching { pm?.isPowerSaveMode }.getOrNull()
        val ignoring = runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
        return "Doze=$idle batterijbesparing=$saver geenBatterijoptimalisatie=$ignoring"
    }
}
