package com.fclglucolink.app.sensor.caresensair

import com.fclglucolink.app.sensor.SensorSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air-specifieke diagnostiek-melding per slot
 * ============================================================================
 *
 * 24/09/2026 (editor, RONDE 181, op verzoek na een crash-loop-caselog van een andere gebruiker:
 * "ik wil ... ook extra info zoals we nu bekijken als er weer iets mis gaat.
 * Zodat het voor de gebruiker duidelijk is dat de app wel data krijgt maar
 * dat die fouten levert") — tot nu toe stond deze informatie (sensor-eigen
 * foutcode, "hoelang al geen echte meting", crash-herstel via de Ronde
 * 179/180-skip) ALLEEN in het dagelijkse logbestand (DIAGI/DIAGE, zie
 * caresensair_bridge.cpp). Deze lichte in-memory brug — zelfde patroon als
 * [com.fclglucolink.app.sensor.ble.ConnectionStatusBridge] — geeft
 * [CareSensAirDriver] een plek om dat rechtstreeks aan de UI (het nieuwe
 * "Diagnostics"-blokje, zie StatusScreen.kt's DiagnosticsCard()) door te
 * geven, ALTIJD al vertaald naar gewone-mensen-taal: "ik wil geen echt
 * technische info zien waar een gemiddelde gebruiker niks mee kan" — dus
 * bewust geen ruwe foutcodes/sequentienummers/vaktaal in [Note.message],
 * die blijven alleen in het logbestand staan voor wie er (technisch) verder
 * induikt.
 */
object CareSensAirDiagnostics {

    data class Note(val message: String, val atMs: Long)

    private val _noteA = MutableStateFlow<Note?>(null)
    private val _noteB = MutableStateFlow<Note?>(null)

    fun note(slot: SensorSlot): StateFlow<Note?> =
        (if (slot == SensorSlot.A) _noteA else _noteB).asStateFlow()

    private fun publish(slot: SensorSlot, message: String) {
        val target = if (slot == SensorSlot.A) _noteA else _noteB
        target.value = Note(message, System.currentTimeMillis())
    }

    private fun clear(slot: SensorSlot) {
        val target = if (slot == SensorSlot.A) _noteA else _noteB
        target.value = null
    }

    /** Zelfde drempel als caresensair_bridge.cpp's `kStuckThresholdSecs` —
     *  onder dit aantal seconden is "nog geen nieuwe meting" volkomen normaal
     *  (elke sensor meet maar eens per ~5 minuten), pas erboven is het de
     *  moeite van het melden waard. */
    private const val STALL_MENTION_THRESHOLD_SECS = 62 * 5

    /** Aangeroepen door [CareSensAirDriver] bij elk resultaat van
     *  [CareSensAirNative.processGlucoseData] — vertaalt naar (of wist) de
     *  melding voor [slot]. */
    fun handle(slot: SensorSlot, result: CareSensAirNative.GlucoseFrameResult) {
        when (result) {
            is CareSensAirNative.GlucoseFrameResult.SensorError -> {
                publish(
                    slot,
                    "The sensor itself is reporting a problem reading your glucose. This is often " +
                        "temporary — if it doesn't clear up within a few minutes, try reconnecting " +
                        "the sensor."
                )
            }
            is CareSensAirNative.GlucoseFrameResult.RecordCountAnnounced -> {
                if (result.secondsWithoutRealReading > STALL_MENTION_THRESHOLD_SECS) {
                    val minutes = result.secondsWithoutRealReading / 60
                    publish(
                        slot,
                        "It's been about $minutes minutes since the last real reading. The app is " +
                            "still trying — this can happen with any sensor connection and often " +
                            "resolves on its own."
                    )
                }
            }
            is CareSensAirNative.GlucoseFrameResult.Processed -> {
                when {
                    result.recoveredFromCrash -> publish(
                        slot,
                        "The app had trouble processing one of the sensor's readings just now, but " +
                            "recovered automatically — no action needed."
                    )
                    // Een echte, geslaagde meting: het probleem (als er een
                    // was) is kennelijk voorbij — melding wissen i.p.v. laten
                    // hangen op een oude, nu achterhaalde tekst.
                    result.reading != null -> clear(slot)
                }
            }
            CareSensAirNative.GlucoseFrameResult.Ignored -> {}
        }
    }
}
