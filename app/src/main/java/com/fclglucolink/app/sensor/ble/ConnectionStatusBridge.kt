package com.fclglucolink.app.sensor.ble

import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 30/07/2026 (editor) — lichte in-memory brug tussen BleConnectionService en de
 * Compose-UI, zelfde patroon als FCLvNext's eigen FclProfileBridge: de UI
 * heeft geen bindService()-gedoe nodig om te weten of/hoe er verbonden is,
 * de service publiceert 'm hier gewoon bij elke wijziging.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — was één enkele
 * StateFlow voor "de" verbinding; nu één StateFlow per [SensorSlot], zodat
 * de nieuwe tab-UI (Dexcom G6-tab / CareSens-tab) elk zijn eigen slot's
 * status onafhankelijk kan tonen.
 */
object ConnectionStatusBridge {
    private val _stateA = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _stateB = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    fun state(slot: SensorSlot): StateFlow<ConnectionState> =
        (if (slot == SensorSlot.A) _stateA else _stateB).asStateFlow()

    fun update(slot: SensorSlot, newState: ConnectionState) {
        val target = if (slot == SensorSlot.A) _stateA else _stateB
        target.value = newState
    }
}
