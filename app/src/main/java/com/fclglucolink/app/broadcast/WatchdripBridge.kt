package com.fclglucolink.app.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint
import com.eveningoutpost.dexdrip.services.broadcastservice.models.Settings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.TrendCalculator
import com.fclglucolink.app.ui.mgdlToMmol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * FCLGlucoLink — WatchDrip+ bridge (Mi Band / Amazfit / Zepp OS watches)
 * ============================================================================
 *
 * WatchDrip+ does not read the BgEstimate broadcast that goes to AAPS. It uses the
 * "Broadcast Service API" of xDrip+. This object copies the xDrip+ side of
 * that API, so WatchDrip+ can use FCLGlucoLink as its glucose source.
 * Sources: xDrip `BroadcastService.java` and the WatchDrip+ app code
 * (`BroadcastService.java`, `xDripReceiver.java`, `BgData.java`, `AapsGraphCache.java`).
 *
 * How it works:
 * 1. WatchDrip+ sends the action [ACTION_RECEIVER] with FUNCTION = `set_settings`
 *    or `update_bg_force`, its own package name in PACKAGE and a `Settings`
 *    parcel (see the models package). We remember that package.
 * 2. For `update_bg_force` we answer at once. WatchDrip+ waits only 10 seconds
 *    and then shows "xDrip does not respond".
 * 3. After every new reading we send `update_bg` to each remembered package.
 * 4. The answer goes to action [ACTION_SENDER] and MUST be addressed to the
 *    package of WatchDrip+ (setPackage). WatchDrip+ ignores it otherwise.
 *
 * This is only a display feed. It never changes the AAPS broadcast. It is
 * switched on or off with the switch in Settings (AppSettings.watchdripEnabled).
 * Not tested with a real watch yet.
 */
object WatchdripBridge {

    private const val TAG = "WatchdripBridge"

    const val ACTION_RECEIVER = "com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_RECEIVER"
    const val ACTION_SENDER = "com.eveningoutpost.dexdrip.watch.wearintegration.BROADCAST_SERVICE_SENDER"

    private const val KEY_FUNCTION = "FUNCTION"
    private const val KEY_PACKAGE = "PACKAGE"
    private const val KEY_SETTINGS = "SETTINGS"

    private const val CMD_START = "start"
    private const val CMD_SET_SETTINGS = "set_settings"
    private const val CMD_UPDATE_BG_FORCE = "update_bg_force"
    private const val CMD_UPDATE_BG = "update_bg"

    /** Same value as xDrip's BgGraphBuilder.FUZZER and WatchDrip+'s BgGraphCompontens.FUZZER. */
    private const val FUZZER = 500 * 15 * 5

    private const val STALE_AFTER_MS = 15 * 60_000L
    private const val DEFAULT_GRAPH_START_MS = 2 * 60 * 60_000L
    private const val MAX_GRAPH_HOURS = 24L

    // Colours as used by xDrip+ / WatchDrip+ for the dots.
    private const val COLOR_LOW = 0xFFC30909.toInt()
    private const val COLOR_HIGH = 0xFFFFBB33.toInt()
    private const val COLOR_IN_RANGE = 0xFF33B5E6.toInt()

    /** What the service must give us to build one answer. Readings: oldest first, newest last. */
    data class WatchSnapshot(
        val readings: List<GlucoseReading>,
        val doMgdl: Boolean,
        val lowMgdl: Double,
        val highMgdl: Double
    )

    private class Registration(val displayGraph: Boolean, val graphStartMs: Long, val graphEndMs: Long)

    private val registrations = ConcurrentHashMap<String, Registration>()

    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var provider: (suspend (hours: Long) -> WatchSnapshot?)? = null

    fun isActive(): Boolean = receiver != null

    /**
     * Starts listening and tells WatchDrip+ that we are ready. [provider] gives the
     * newest readings for the last `hours` hours. Safe to call twice.
     */
    @Synchronized
    fun start(context: Context, scope: CoroutineScope, provider: suspend (hours: Long) -> WatchSnapshot?) {
        if (receiver != null) return
        val appContext = context.applicationContext
        this.scope = scope
        this.provider = provider
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { handleIncoming(appContext, intent) }
                    .onFailure { DiagnosticFileLogger.log("$TAG: fout bij verwerken van bericht: ${it.message}") }
            }
        }
        // EXPORTED: WatchDrip+ is another app and must be able to reach us.
        ContextCompat.registerReceiver(
            appContext, r, IntentFilter(ACTION_RECEIVER), ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
        DiagnosticFileLogger.log("$TAG: gestart, wacht op WatchDrip+")
        sendStart(appContext)
    }

    @Synchronized
    fun stop(context: Context) {
        val r = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(r) }
        receiver = null
        scope = null
        provider = null
        registrations.clear()
        DiagnosticFileLogger.log("$TAG: gestopt")
    }

    /** Sends the newest value to every WatchDrip+ that has registered. Call after each new reading. */
    fun publish(context: Context, snapshot: WatchSnapshot) {
        if (receiver == null || registrations.isEmpty()) return
        val appContext = context.applicationContext
        for ((pkg, registration) in registrations) {
            send(appContext, CMD_UPDATE_BG, pkg, buildBgBundle(appContext, snapshot, registration))
        }
    }

    /** The longest graph that any registered app wants, in whole hours (2..24). */
    fun hoursNeeded(): Long {
        val ms = registrations.values.filter { it.displayGraph }.maxOfOrNull { effectiveGraphStart(it) } ?: 0L
        val hours = (ms + 3_599_999L) / 3_600_000L
        return hours.coerceIn(2L, MAX_GRAPH_HOURS)
    }

    private fun effectiveGraphStart(r: Registration): Long =
        if (r.graphStartMs <= 0L) DEFAULT_GRAPH_START_MS else r.graphStartMs

    private fun handleIncoming(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECEIVER) return
        val function = intent.getStringExtra(KEY_FUNCTION) ?: return
        val pkg = intent.getStringExtra(KEY_PACKAGE) ?: return
        if (function != CMD_SET_SETTINGS && function != CMD_UPDATE_BG_FORCE) return

        val settings = readSettings(intent)
        if (settings == null) {
            DiagnosticFileLogger.log("$TAG: $function van $pkg zonder leesbare instellingen, genegeerd")
            return
        }
        val registration = Registration(settings.displayGraph, settings.graphStart, settings.graphEnd)
        registrations[pkg] = registration
        DiagnosticFileLogger.log(
            "$TAG: $function van $pkg (grafiek=${settings.displayGraph}, " +
                "start=${settings.graphStart / 60_000} min, einde=${settings.graphEnd / 60_000} min)"
        )

        if (function == CMD_UPDATE_BG_FORCE) {
            val s = scope ?: return
            val p = provider ?: return
            s.launch {
                val hours = if (registration.displayGraph) {
                    ((effectiveGraphStart(registration) + 3_599_999L) / 3_600_000L).coerceIn(2L, MAX_GRAPH_HOURS)
                } else 2L
                val snapshot = runCatching { p(hours) }.getOrNull()
                // No data yet: still answer, with an empty bundle. WatchDrip+ then shows
                // "no data" and not "xDrip does not respond".
                val bundle = if (snapshot == null || snapshot.readings.isEmpty()) {
                    Bundle().apply {
                        putBoolean("doMgdl", snapshot?.doMgdl ?: true)
                        putInt("phoneBattery", batteryLevel(context))
                    }
                } else {
                    buildBgBundle(context, snapshot, registration)
                }
                send(context, CMD_UPDATE_BG, pkg, bundle)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readSettings(intent: Intent): Settings? = try {
        intent.setExtrasClassLoader(Settings::class.java.classLoader)
        intent.getParcelableExtra<Settings>(KEY_SETTINGS)
    } catch (e: Exception) {
        DiagnosticFileLogger.log("$TAG: instellingen niet te lezen: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun buildBgBundle(context: Context, snapshot: WatchSnapshot, registration: Registration): Bundle {
        val readings = snapshot.readings
        val latest = readings.last()
        val previous = readings.getOrNull(readings.size - 2)
        val now = System.currentTimeMillis()

        // Delta per 5 minutes, like the xDrip+ delta. 0 when the earlier value is too old.
        val minutes = previous?.let { (latest.timestampMs - it.timestampMs) / 60_000.0 } ?: 0.0
        val deltaPer5Min = if (previous != null && minutes >= 0.5 && minutes <= 15.0) {
            (latest.glucoseMgdl - previous.glucoseMgdl) * 5.0 / minutes
        } else 0.0
        val trend = TrendCalculator.measuredMgdlPerMin(latest, previous) ?: latest.trendMgdlPerMin

        return Bundle().apply {
            putBoolean("doMgdl", snapshot.doMgdl)
            putInt("phoneBattery", batteryLevel(context))
            putString("bg.deltaName", XDripBroadcaster.trendName(trend))
            putDouble("bg.valueMgdl", latest.glucoseMgdl)
            putBoolean("bg.isHigh", latest.glucoseMgdl >= snapshot.highMgdl)
            putBoolean("bg.isLow", latest.glucoseMgdl <= snapshot.lowMgdl)
            putLong("bg.timeStamp", latest.timestampMs)
            putBoolean("bg.isStale", now - latest.timestampMs > STALE_AFTER_MS)
            putString("bg.plugin", "FCLGlucoLink")
            putDouble("bg.deltaValueMgdl", deltaPer5Min)
            if (registration.displayGraph) addGraph(this, snapshot, registration, now)
        }
    }

    /**
     * Graph lines in the xDrip+ format. X = timestamp / FUZZER (as Float), Y in the
     * display unit. The inRange / low / high dot lines follow the same rule as WatchDrip+'s
     * own AAPS graph: low is below the low limit, high is at or above the high limit.
     */
    private fun addGraph(bundle: Bundle, snapshot: WatchSnapshot, registration: Registration, now: Long) {
        val start = now - effectiveGraphStart(registration)
        val end = now + registration.graphEndMs
        val points = snapshot.readings.filter { it.timestampMs in start..end }

        fun y(mgdl: Double): Float = (if (snapshot.doMgdl) mgdl else mgdl.mgdlToMmol()).toFloat()
        fun x(ts: Long): Float = (ts / FUZZER.toDouble()).toFloat()
        fun dots(color: Int, accept: (Double) -> Boolean): GraphLine =
            GraphLine(points.filter { accept(it.glucoseMgdl) }.map { GraphPoint(x(it.timestampMs), y(it.glucoseMgdl)) }, color)
        fun flat(mgdl: Double, color: Int): GraphLine =
            if (points.isEmpty()) GraphLine(emptyList(), color)
            else GraphLine(
                listOf(GraphPoint(x(points.first().timestampMs), y(mgdl)), GraphPoint(x(points.last().timestampMs), y(mgdl))),
                color
            )

        bundle.putInt("fuzzer", FUZZER)
        bundle.putLong("start", start)
        bundle.putLong("end", end)
        bundle.putParcelable("graph.inRange", dots(COLOR_IN_RANGE) { it >= snapshot.lowMgdl && it < snapshot.highMgdl })
        bundle.putParcelable("graph.low", dots(COLOR_LOW) { it < snapshot.lowMgdl })
        bundle.putParcelable("graph.high", dots(COLOR_HIGH) { it >= snapshot.highMgdl })
        bundle.putParcelable("graph.lowLine", flat(snapshot.lowMgdl, COLOR_LOW))
        bundle.putParcelable("graph.highLine", flat(snapshot.highMgdl, COLOR_HIGH))
    }

    private fun batteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 0
    }

    private fun send(context: Context, function: String, pkg: String?, extras: Bundle?) {
        val intent = Intent(ACTION_SENDER).apply {
            putExtra(KEY_FUNCTION, function)
            if (pkg != null) setPackage(pkg)
            if (extras != null) putExtras(extras)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Tells WatchDrip+ that we are ready, so it asks for data at once. xDrip+ sends this
     * to everybody. On modern Android a manifest receiver gets no broadcast without a target
     * package, so we also send it to each app that listens for [ACTION_SENDER].
     */
    private fun sendStart(context: Context) {
        send(context, CMD_START, null, null)
        @Suppress("DEPRECATION")
        val listeners = context.packageManager
            .queryBroadcastReceivers(Intent(ACTION_SENDER), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
        for (pkg in listeners) send(context, CMD_START, pkg, null)
        DiagnosticFileLogger.log("$TAG: start-bericht gestuurd (ontvangers: ${listeners.joinToString().ifEmpty { "geen gevonden" }})")
    }
}
