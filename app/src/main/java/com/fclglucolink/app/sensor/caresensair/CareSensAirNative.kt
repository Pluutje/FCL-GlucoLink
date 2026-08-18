package com.fclglucolink.app.sensor.caresensair

import android.content.Context
import java.io.File

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air native kalibratiebrug (Kotlin-kant)
 * ============================================================================
 *
 * 01/08/2026 (editor) — dunne JNI-wrapper rond
 * `app/src/main/cpp/caresensair_bridge.cpp` (zie dat bestand voor de
 * volledige achtergrond/kdoc). Eén ding hier expliciet gedocumenteerd: de
 * omzetstap ruwe-sensordata -> mg/dL loopt via een closed-source
 * bibliotheek (`libCALCULATION.so`, gebundeld in `jniLibs/arm64-v8a/`, uit
 * de gebruiker's eigen geïnstalleerde Juggluco-app gehaald) — dit is de
 * propriëtaire FABRIEKSkalibratie die elke CareSens Air-sensor nodig heeft
 * om zijn eigen ruwe elektrochemische signaal te vertalen naar een
 * glucosewaarde, los van elke gebruikerskalibratie (fingerstick-bijstelling
 * gebeurt toch al in AAPS).
 *
 * Statusbeheer: [state] is een handle naar een stuk native geheugen dat de
 * kalibratiegeschiedenis van ÉÉN sensor bijhoudt (bouwt op over metingen
 * heen — NIET stateless per verbinding). [persist]/[restore] bewaren die
 * status als rauwe bytes in een bestand in de app's eigen opslag, zodat een
 * herstart van FCLGlucoLink niet betekent dat de sensor's kalibratie-
 * geschiedenis kwijtraakt (functioneel hetzelfde doel als Juggluco's eigen
 * mmap-bestanden, alleen simpeler — zie caresensair_bridge.cpp's kdoc).
 */
object CareSensAirNative {

    init {
        System.loadLibrary("caresensair_bridge")
    }

    private external fun nativeLoadCalculationLibrary(soPath: String): Boolean
    private external fun nativeCreateState(): Long
    private external fun nativeDestroyState(handle: Long)
    private external fun nativeExportState(handle: Long): ByteArray
    private external fun nativeImportState(handle: Long, blob: ByteArray): Boolean
    private external fun nativeGetLastSequence(handle: Long): Int
    private external fun nativeSaveSensorInfoChunk1(handle: Long, value: ByteArray): Boolean
    private external fun nativeSaveSensorInfoChunk2(handle: Long, value: ByteArray): Boolean
    private external fun nativeSaveStartSensor(handle: Long, eapp: Float, vref: Float, elapsedSecs: Int)
    private external fun nativeProcessGlucoseData(handle: Long, value: ByteArray, nowMs: Long): LongArray

    /** Resultaat van [processGlucoseData] — zie caresensair_bridge.cpp's kdoc
     *  bij nativeProcessGlucoseData voor de exacte veldbetekenis. */
    sealed class GlucoseFrameResult {
        /** 0xC4-bericht: er staan [newRecords] nieuwe records klaar — stuur
         *  het "aantal-records-opvragen"-commando (zie
         *  [buildNumberRecordsCommand]) als [newRecords] > 0, anders is er
         *  simpelweg niets nieuws. */
        data class RecordCountAnnounced(val newRecords: Int) : GlucoseFrameResult()

        /** 0xC5-bericht verwerkt. [reading] is null als het algoritme geen
         *  bruikbare (binnen het plausibele bereik vallende) waarde
         *  opleverde voor dit specifieke record — normaal bij historische
         *  vulrecords, geen fout. */
        data class Processed(val reading: NativeGlucoseReading?) : GlucoseFrameResult()

        /** De sensor zelf meldt een foutstatus (bv. einde levensduur,
         *  sensorfout) — geen bruikbare data te verwachten deze verbinding. */
        object SensorError : GlucoseFrameResult()

        /** Bericht kon niet verwerkt worden (te kort, onverwacht
         *  berichttype, of de kalibratiebibliotheek is nog niet geladen). */
        object Ignored : GlucoseFrameResult()
    }

    data class NativeGlucoseReading(
        val glucoseMgdl: Double,
        val epochSecs: Long,
        /** mg/dL per minuut, of null als de sensor zelf geen bruikbare
         *  trend kon berekenen (bv. vlak na het opwarmen). */
        val trendMgdlPerMin: Double?,
        val sequenceNumber: Int
    )

    /**
     * Moet vóór het eerste gebruik van [processGlucoseData] e.a. succesvol
     * zijn geweest. `soPath` = `context.applicationInfo.nativeLibraryDir +
     * "/libCALCULATION.so"` — dat pad wijst naar de kopie die als onderdeel
     * van de FCLGlucoLink-apk zelf geïnstalleerd is (zie
     * app/src/main/jniLibs/arm64-v8a/), niet naar Juggluco's installatie.
     */
    fun loadCalculationLibrary(context: Context): Boolean {
        val soPath = File(context.applicationInfo.nativeLibraryDir, "libCALCULATION.so").absolutePath
        return nativeLoadCalculationLibrary(soPath)
    }

    fun createState(): Long = nativeCreateState()

    fun destroyState(handle: Long) = nativeDestroyState(handle)

    fun getLastSequence(handle: Long): Int = nativeGetLastSequence(handle)

    fun saveSensorInfoChunk1(handle: Long, value: ByteArray): Boolean = nativeSaveSensorInfoChunk1(handle, value)

    fun saveSensorInfoChunk2(handle: Long, value: ByteArray): Boolean = nativeSaveSensorInfoChunk2(handle, value)

    fun saveStartSensor(handle: Long, eapp: Float, vref: Float, elapsedSecs: Int) =
        nativeSaveStartSensor(handle, eapp, vref, elapsedSecs)

    fun processGlucoseData(handle: Long, value: ByteArray, nowMs: Long = System.currentTimeMillis()): GlucoseFrameResult {
        val r = nativeProcessGlucoseData(handle, value, nowMs)
        if (r.size < 6) return GlucoseFrameResult.Ignored
        return when (r[0]) {
            1L -> GlucoseFrameResult.RecordCountAnnounced(newRecords = r[1].toInt())
            2L -> {
                if (r[1] == 1L) {
                    val trend = if (r[4] == Long.MIN_VALUE) null else r[4] / 1000.0
                    GlucoseFrameResult.Processed(
                        NativeGlucoseReading(
                            glucoseMgdl = r[2] / 10.0,
                            epochSecs = r[3],
                            trendMgdlPerMin = trend,
                            sequenceNumber = r[5].toInt()
                        )
                    )
                } else {
                    GlucoseFrameResult.Processed(reading = null)
                }
            }
            3L -> GlucoseFrameResult.SensorError
            else -> GlucoseFrameResult.Ignored
        }
    }

    // --- Persistentie: kalibratiegeschiedenis overleeft een herstart van
    // FCLGlucoLink. Eén bestand per gekoppelde sensor (bestandsnaam op
    // sensor-serienummer), zodat het wisselen van sensor (nieuwe pleister)
    // niet de oude kalibratiegeschiedenis van de vorige sensor hergebruikt —
    // die is per sensor-eenheid uniek en zou het algoritme in de war
    // brengen. ---

    private fun stateFile(context: Context, sensorSerial: String): File {
        val dir = File(context.filesDir, "caresensair_state")
        if (!dir.exists()) dir.mkdirs()
        // Serienummer bevat geen padtekens (GS1-alfanumeriek, zie
        // CareSensAirBarcode.kt), dus direct als bestandsnaam bruikbaar.
        return File(dir, "$sensorSerial.bin")
    }

    fun persist(context: Context, handle: Long, sensorSerial: String) {
        val blob = nativeExportState(handle)
        runCatching { stateFile(context, sensorSerial).writeBytes(blob) }
    }

    /** @return true als er een eerder-opgeslagen kalibratiegeschiedenis voor
     *  DEZE sensor teruggevonden en ingeladen is; false als dit voor
     *  FCLGlucoLink een nieuwe/onbekende sensor is (dan begint de status
     *  leeg, zoals bij een verse `createState()`). */
    fun restore(context: Context, handle: Long, sensorSerial: String): Boolean {
        val file = stateFile(context, sensorSerial)
        if (!file.exists()) return false
        val blob = runCatching { file.readBytes() }.getOrNull() ?: return false
        return nativeImportState(handle, blob)
    }
}
