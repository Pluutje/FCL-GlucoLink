package com.fclglucolink.app.sensor.simulator

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * FCLGlucoLink — BG-simulator command-bridge
 * ============================================================================
 *
 * 30/07/2026 (editor) — zelfde soort in-memory bridge-patroon als
 * ConnectionStatusBridge (service -> UI), maar dan de andere kant op: de UI
 * (ui/SimulatorSetupScreen.kt) geeft hier commando's aan, en de
 * SimulatorDriver-instantie die BleConnectionService heeft aangemaakt (via
 * SensorRegistry) luistert hierop. Nodig omdat de UI geen directe referentie
 * heeft naar DIE specifieke driver-instantie — die leeft in de service.
 *
 * Doel (editor, 30/07/2026): eerst het exportpad naar AAPS kunnen testen op een
 * reservetelefoon met virtuele pomp zonder dat er al een echte sensor werkt,
 * en later een eerder problematische BG-reeks exact kunnen herafspelen om een
 * FCLvNext-fix te valideren voordat die live gaat.
 */
sealed interface SimulatorCommand {
    /** Eén losse meting, meteen versturen. */
    data class SingleValue(val glucoseMgdl: Double, val trendMgdlPerMin: Float) : SimulatorCommand

    /** Dezelfde waarde blijven herhalen (om AAPS niet te laten klagen over
     *  een verouderde/ontbrekende BG tijdens een langere testsessie) totdat
     *  StopReplay komt. */
    data class RepeatValue(
        val glucoseMgdl: Double,
        val trendMgdlPerMin: Float,
        val intervalMs: Long
    ) : SimulatorCommand

    /** Speelt een lijst BG-waarden (mg/dL, chronologische volgorde) af, met
     *  intervalMs tussen elke waarde — de trend/slope wordt per stap
     *  berekend uit het verschil met de vorige waarde, precies zoals een
     *  echte sensor dat zou opleveren. Loopt oneindig door (begint weer
     *  vooraan na de laatste waarde) totdat StopReplay komt — editor's
     *  expliciete verzoek, zodat een test niet vanzelf stopt. */
    data class StartListReplay(val valuesMgdl: List<Double>, val intervalMs: Long) : SimulatorCommand

    /** Willekeurige-maar-realistische BG-generator: geen losse toevalswaarden
     *  maar een random-walk die meestal rond een baseline blijft hangen en af
     *  en toe een maaltijdachtige stijging-en-daling doet — zie
     *  RandomBgGenerator. Loopt door totdat StopReplay komt. */
    data class StartRandomWalk(val intervalMs: Long) : SimulatorCommand

    data object StopReplay : SimulatorCommand
}

/**
 * 30/07/2026 (editor, na feedback: "moet 15 dagen door kunnen draaien zonder
 * open scherm") — SimulatorCommand hierboven is een EENMALIG signaal (een
 * MutableSharedFlow zonder replay): prima zolang de driver die het
 * ontvangt continu blijft draaien, maar niet genoeg om 15 dagen onbeheerd
 * mee te draaien. Android kan BleConnectionService alsnog een keer stoppen
 * (geheugendruk, agressief batterijbeheer van sommige toestellen — zie
 * README) en dan opnieuw starten via START_STICKY; dat geeft een VERSE
 * SimulatorDriver-instantie die keurig opnieuw naar commando's luistert,
 * maar er komt geen nieuw commando aan omdat er niemand meer op het
 * setup-scherm op "start" drukt. Resultaat: de simulator "verbindt" wel,
 * maar genereert niets meer, exact het gerapporteerde symptoom (data komt
 * niet meer in AAPS terwijl de rest van de app gewoon lijkt te werken).
 *
 * Deze PersistedSimulatorMode is de oplossing: een klein, DataStore-
 * opgeslagen "wat was er actief"-vlaggetje (zie AppSettings), bijgewerkt
 * telkens als de gebruiker een modus start/stopt in SimulatorSetupScreen.
 * BleConnectionService leest dit na elke driver.connect() en stuurt zo
 * nodig automatisch het bijbehorende commando opnieuw, zonder dat de UI
 * open hoeft te staan — zie kdoc daar.
 */
sealed interface PersistedSimulatorMode {
    data object None : PersistedSimulatorMode
    data class Repeat(val glucoseMgdl: Double, val intervalMs: Long) : PersistedSimulatorMode
    data class RandomWalk(val intervalMs: Long) : PersistedSimulatorMode
    /** Waarden zelf staan niet hierin — die worden bij het hervatten opnieuw
     *  uit AppSettings.externalListUri gelezen (zie
     *  sensor/simulator/SimulatorListFile.kt), zodat een lijst die ondertussen
     *  gewijzigd is altijd de actuele inhoud gebruikt. */
    data class ListReplay(val intervalMs: Long) : PersistedSimulatorMode
}

sealed interface SimulatorReplayState {
    data object Idle : SimulatorReplayState
    data class RepeatingValue(val glucoseMgdl: Double) : SimulatorReplayState
    /** lap = hoeveelste keer de lijst van voor af aan begint (1 = eerste
     *  doorloop) — puur informatief in de UI, zodat duidelijk is dat het
     *  bewust blijft loopen en niet is vastgelopen. */
    data class PlayingList(val index: Int, val total: Int, val currentMgdl: Double, val lap: Int) :
        SimulatorReplayState
    data class GeneratingRandom(val currentMgdl: Double) : SimulatorReplayState
}

object SimulatorControlBridge {

    private val _commands = MutableSharedFlow<SimulatorCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<SimulatorCommand> = _commands.asSharedFlow()

    private val _replayState = MutableStateFlow<SimulatorReplayState>(SimulatorReplayState.Idle)
    val replayState: StateFlow<SimulatorReplayState> = _replayState.asStateFlow()

    /** Aangeroepen door SimulatorDriver zelf om de UI op de hoogte te houden
     *  (voortgang CSV-afspelen, of dat een herhaling actief is). */
    fun updateReplayState(state: SimulatorReplayState) {
        _replayState.value = state
    }

    suspend fun sendSingleValue(glucoseMgdl: Double, trendMgdlPerMin: Float = 0f) {
        _commands.emit(SimulatorCommand.SingleValue(glucoseMgdl, trendMgdlPerMin))
    }

    suspend fun startRepeating(glucoseMgdl: Double, intervalMs: Long) {
        _commands.emit(SimulatorCommand.RepeatValue(glucoseMgdl, 0f, intervalMs))
    }

    suspend fun startListReplay(valuesMgdl: List<Double>, intervalMs: Long) {
        _commands.emit(SimulatorCommand.StartListReplay(valuesMgdl, intervalMs))
    }

    suspend fun startRandomWalk(intervalMs: Long) {
        _commands.emit(SimulatorCommand.StartRandomWalk(intervalMs))
    }

    suspend fun stop() {
        _commands.emit(SimulatorCommand.StopReplay)
        _replayState.value = SimulatorReplayState.Idle
    }
}
