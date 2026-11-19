package com.fclglucolink.app.sensor.juggluco

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorDriver
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * RONDE 256 — "sensor" that has no Bluetooth of its own: it takes the readings that JugglucoNG
 * sends in the xDrip broadcast (see [JugglucoBroadcastReceiver]). Meant for a Libre 3 / 3 Plus that
 * runs in JugglucoNG, so FCLGlucoLink can store, smooth and analyse its 1-minute readings.
 *
 * Choose it in the free slot. That slot must NOT be the AAPS slot: nothing from this sensor should
 * go to AAPS. The other slot (for example the G6) stays the only source for AAPS.
 *
 * Not tested against a real Libre 3 yet.
 */
class JugglucoDriver(@Suppress("unused") private val slot: SensorSlot) : SensorDriver {

    override val sensorType: SensorType = SensorType.JUGGLUCO_LIBRE3

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<GlucoseReading>(extraBufferCapacity = 64)
    override val readings: SharedFlow<GlucoseReading> = _readings.asSharedFlow()

    private var scope: CoroutineScope? = null

    override fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit) {}
    override fun stopPairing() {}

    override fun connect(context: Context, deviceAddress: String) {
        scope?.cancel()
        val driverScope = CoroutineScope(SupervisorJob())
        scope = driverScope
        _connectionState.value = ConnectionState.Connected(
            deviceAddress = "juggluco",
            deviceName = "Juggluco (Libre 3, via broadcast)"
        )
        driverScope.launch {
            JugglucoBridge.readings.collect { r ->
                _readings.emit(
                    GlucoseReading(
                        glucoseMgdl = r.glucoseMgdl,
                        trendMgdlPerMin = r.trendMgdlPerMin,
                        timestampMs = r.timestampMs,
                        sensorStartedAtMs = if (r.sensorStartedAtMs > 0L) r.sensorStartedAtMs else r.timestampMs,
                        sensorType = SensorType.JUGGLUCO_LIBRE3
                    )
                )
            }
        }
    }

    override fun disconnect() {
        scope?.cancel()
        scope = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
