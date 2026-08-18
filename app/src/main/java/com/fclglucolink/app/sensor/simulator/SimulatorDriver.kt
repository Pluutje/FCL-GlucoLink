package com.fclglucolink.app.sensor.simulator

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorDriver
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 30/07/2026 (editor) — geen echte sensor: geen BLE, geen device om mee te
 * koppelen. connect() "verbindt" meteen (device-adres is altijd de vaste
 * string "simulator", zie ui/SimulatorSetupScreen.kt) en luistert daarna op
 * SimulatorControlBridge.commands voor wat er daadwerkelijk verstuurd moet
 * worden. Verder identiek behandeld door BleConnectionService als elke
 * andere SensorDriver — dezelfde opslag + dezelfde xDrip-broadcast, dat is
 * precies het punt: het exportpad naar AAPS testen zonder dat er al een
 * echte sensor-driver hoeft te bestaan.
 */
class SimulatorDriver(@Suppress("unused") private val slot: SensorSlot) : SensorDriver {
    // 10/08/2026 (editor, RONDE 79 -- 2-sensoren-architectuur) -- [slot] hier
    // puur voor signatuur-symmetrie met DexcomG6Driver/CareSensAirDriver (zie
    // hun kdoc bij hun eigen [slot]-parameter), zodat SensorRegistry.
    // createDriver(sensorType, slot) generiek dezelfde aanroep-vorm voor elk
    // sensortype kan gebruiken. Ongebruikt hierbinnen -- de simulator heeft
    // geen eigen per-slot AppSettings-velden (SimulatorControlBridge is nog
    // een gedeelde, niet-slot-bewuste commando-bridge, zie
    // BleConnectionService.kt's kdoc bij resumeSimulatorIfNeeded()).

    override val sensorType: SensorType = SensorType.SIMULATOR

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<GlucoseReading>(extraBufferCapacity = 8)
    override val readings: SharedFlow<GlucoseReading> = _readings.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var replayJob: Job? = null

    // 30/07/2026 (editor) — puur een placeholder: BleConnectionService
    // overschrijft sensorStartedAtMs altijd met de waarde uit
    // AppSettings.getOrInitSensorStartedAtMs() vóórdat een meting wordt
    // opgeslagen/gebroadcast. Zie kdoc daar — dit veld hier zou anders bij
    // elke nieuwe SimulatorDriver-instantie (dus bij elke herverbinding)
    // opnieuw "nu" worden, ook als er niets écht opnieuw gestart is.
    private val simulatorStartedAtMs = System.currentTimeMillis()

    // Geen echte devices — de simulator heeft niets om te "vinden".
    override fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit) {}
    override fun stopPairing() {}

    override fun connect(context: Context, deviceAddress: String) {
        val driverScope = CoroutineScope(SupervisorJob())
        scope = driverScope
        _connectionState.value = ConnectionState.Connected(
            deviceAddress = "simulator",
            deviceName = "BG simulator (testing)"
        )
        driverScope.launch {
            SimulatorControlBridge.commands.collect { command ->
                when (command) {
                    is SimulatorCommand.SingleValue -> {
                        replayJob?.cancel()
                        emitReading(command.glucoseMgdl, command.trendMgdlPerMin)
                    }
                    is SimulatorCommand.RepeatValue -> startRepeat(command, driverScope)
                    is SimulatorCommand.StartListReplay -> startListReplay(command, driverScope)
                    is SimulatorCommand.StartRandomWalk -> startRandomWalk(command, driverScope)
                    SimulatorCommand.StopReplay -> {
                        replayJob?.cancel()
                        SimulatorControlBridge.updateReplayState(SimulatorReplayState.Idle)
                    }
                }
            }
        }
    }

    override fun disconnect() {
        replayJob?.cancel()
        scope?.cancel()
        scope = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun emitReading(glucoseMgdl: Double, trendMgdlPerMin: Float) {
        _readings.emit(
            GlucoseReading(
                glucoseMgdl = glucoseMgdl,
                trendMgdlPerMin = trendMgdlPerMin,
                timestampMs = System.currentTimeMillis(),
                sensorStartedAtMs = simulatorStartedAtMs,
                sensorType = SensorType.SIMULATOR
            )
        )
    }

    private fun startRepeat(command: SimulatorCommand.RepeatValue, driverScope: CoroutineScope) {
        replayJob?.cancel()
        replayJob = driverScope.launch {
            while (true) {
                emitReading(command.glucoseMgdl, 0f)
                SimulatorControlBridge.updateReplayState(
                    SimulatorReplayState.RepeatingValue(command.glucoseMgdl)
                )
                delay(command.intervalMs)
            }
        }
    }

    private fun startListReplay(command: SimulatorCommand.StartListReplay, driverScope: CoroutineScope) {
        replayJob?.cancel()
        val values = command.valuesMgdl
        if (values.isEmpty()) return
        replayJob = driverScope.launch {
            var previous = values.first()
            var lap = 1
            // Loopt oneindig door (begint na de laatste waarde weer vooraan)
            // totdat StopReplay komt — editor's expliciete verzoek, zodat een
            // testrun niet vanzelf stopt zolang je 'm niet zelf stopt.
            while (true) {
                values.forEachIndexed { index, mgdl ->
                    // Trend uit het verschil met de vorige waarde — zelfde
                    // eenheid (mg/dL/min) als een echte sensor zou opleveren.
                    val minutesPerStep = command.intervalMs / 60_000.0
                    val trend = if (minutesPerStep > 0) ((mgdl - previous) / minutesPerStep).toFloat() else 0f
                    emitReading(mgdl, trend)
                    previous = mgdl
                    SimulatorControlBridge.updateReplayState(
                        SimulatorReplayState.PlayingList(index + 1, values.size, mgdl, lap)
                    )
                    delay(command.intervalMs)
                }
                lap++
            }
        }
    }

    private fun startRandomWalk(command: SimulatorCommand.StartRandomWalk, driverScope: CoroutineScope) {
        replayJob?.cancel()
        val generator = RandomBgGenerator()
        val minutesPerStep = command.intervalMs / 60_000.0
        replayJob = driverScope.launch {
            var previous: Double? = null
            while (true) {
                val current = generator.next()
                // Trend uit het verschil met de vorige stap, zelfde patroon
                // als bij de lijst-replay — 0 (vlak) bij de allereerste waarde.
                val trend = previous?.let { prev ->
                    if (minutesPerStep > 0) ((current - prev) / minutesPerStep).toFloat() else 0f
                } ?: 0f
                emitReading(current, trend)
                SimulatorControlBridge.updateReplayState(SimulatorReplayState.GeneratingRandom(current))
                previous = current
                delay(command.intervalMs)
            }
        }
    }
}
