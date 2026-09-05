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

    /**
     * 29/08/2026 (editor, RONDE 163, op verzoek — "Die blijft nu de lijst
     * oneindig herhalen. Ik denk dat het nuttiger is om hem met 3 keer een
     * vaste instelbare Bg te laten beginnen. Dan de lijst af te spelen en
     * daarna weer naar de ingestelde waarde te springen. Op die manier kun
     * je het systeem virtueel met een bepaalde iob laten beginnen [...] met
     * het oude algoritme, dan installeer je de test versie (zet de iob weer
     * gelijk) en draait hetzelfde scenario 1 keer om te kijken wat de
     * verschillen zijn") — drie fasen, ÉÉN doorloop (was: oneindig herhalen
     * van alleen de lijst, zie de oude kdoc-tekst hieronder voor de
     * eerdere reden daarvoor, die nu vervangen is door dit A/B-testdoel):
     * 1. [baselineMgdl] [BASELINE_REPEAT_COUNT] keer versturen — een stabiele
     *    "voor-scenario"-periode zodat AAPS/FCLvNext eerst een bekende IOB
     *    opbouwt vanaf een vast startpunt, vóórdat het eigenlijke testscenario
     *    (de stijging/daling die de insuline-afgifte bepaalt) begint.
     * 2. [valuesMgdl] precies ÉÉN keer afspelen (niet meer looped).
     * 3. Daarna teruggesprongen naar en blijven hangen op [baselineMgdl] —
     *    zodat de test niet in het niets eindigt (AAPS geen "stale BG" gaat
     *    melden) en meteen duidelijk is dat het scenario is afgerond, zodat
     *    je bv. de testversie kunt installeren, de IOB weer gelijk kunt
     *    zetten, en exact hetzelfde scenario nog een keer kunt afspelen om de
     *    twee resultaten te vergelijken.
     *
     * Zie SimulatorDriver.kt's startListReplay() voor de implementatie.
     */
    data class StartListReplay(
        val baselineMgdl: Double,
        val valuesMgdl: List<Double>,
        val intervalMs: Long
    ) : SimulatorCommand

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
     *  gewijzigd is altijd de actuele inhoud gebruikt. [baselineMgdl] (RONDE
     *  163) staat er WEL bij — die komt niet uit een bestand maar is een
     *  losse instelling, dus zonder dit veld zou een service-herstart
     *  halverwege de baseline-opwarmfase of de "blijven hangen"-eindfase
     *  terugvallen op een verkeerde/verouderde waarde. */
    data class ListReplay(val baselineMgdl: Double, val intervalMs: Long) : PersistedSimulatorMode
}

sealed interface SimulatorReplayState {
    data object Idle : SimulatorReplayState
    /** 29/08/2026 (editor, RONDE 163) — ook gebruikt voor de fase NA een
     *  lijst-scenario waarin de simulator op [glucoseMgdl] (de baseline)
     *  blijft hangen, zie SimulatorCommand.StartListReplay's kdoc — dat is
     *  functioneel exact hetzelfde als de losse "Repeat"-modus, dus geen
     *  eigen state-variant nodig. */
    data class RepeatingValue(val glucoseMgdl: Double) : SimulatorReplayState
    /** 29/08/2026 (editor, RONDE 163) — de opwarmfase vóór het scenario zelf:
     *  [BASELINE_REPEAT_COUNT] herhalingen van de ingestelde baseline-waarde,
     *  zie SimulatorCommand.StartListReplay's kdoc. */
    data class PlayingBaselineWarmup(val step: Int, val total: Int, val glucoseMgdl: Double) :
        SimulatorReplayState
    /** 29/08/2026 (editor, RONDE 163) — `lap` verwijderd: het scenario speelt
     *  nu precies ÉÉN keer af (was: oneindig herhalend, vandaar de vorige
     *  "welke doorloop"-teller) — zie SimulatorCommand.StartListReplay's
     *  kdoc voor de volledige reden. */
    data class PlayingList(val index: Int, val total: Int, val currentMgdl: Double) :
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

    suspend fun startListReplay(baselineMgdl: Double, valuesMgdl: List<Double>, intervalMs: Long) {
        _commands.emit(SimulatorCommand.StartListReplay(baselineMgdl, valuesMgdl, intervalMs))
    }

    suspend fun startRandomWalk(intervalMs: Long) {
        _commands.emit(SimulatorCommand.StartRandomWalk(intervalMs))
    }

    suspend fun stop() {
        _commands.emit(SimulatorCommand.StopReplay)
        _replayState.value = SimulatorReplayState.Idle
    }
}
