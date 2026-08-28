package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air-specifiek status-/beheerscherm
 * ============================================================================
 *
 * 09/08/2026 (editor, RONDE 64) — vervangt het CareSens-deel van het vroegere,
 * gedeelde SensorManagementScreen.kt (nu vervallen — zie
 * FclGlucoLinkNavHost.kt's kdoc). CareSens Air kent, i.t.t. Dexcom G6, GEEN
 * apart "transmitter"-concept — sensor en zender zijn hier één wegwerpbaar
 * geheel, dus maar één beheer-actie nodig: "Start / switch sensor" (nieuwe
 * QR scannen, of direct een al lopende sensor uit de Bluetooth-lijst pakken)
 * — zie CareSensAirChooseScreen.kt, hier hergebruikt.
 *
 * Geopend vanaf StatusScreen.kt's (i)-knop op de compacte samenvatting
 * (alleen wanneer CareSens Air al het actieve type is) — voor het WISSELEN
 * naar CareSens Air als een ander type actief is, zie
 * SensorSelectionScreen.kt.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareSensAirStatusScreen(
    onBack: () -> Unit,
    onManageSensor: () -> Unit,
    onDisconnect: () -> Unit,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: zie PairingScreen.kt's identieke kdoc bij zijn
    // eigen [slot]-parameter.
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val store = remember { GlucoseReadingStore(context) }
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317) —
    // was ongefilterd (`store.latestReading()`), dus de gecombineerde
    // stream van BEIDE slots — zie StatusScreen.kt's SlotStatusContent()
    // kdoc voor de volledige uitleg van dit type bug. Dit scherm is alleen
    // zichtbaar wanneer CareSens Air al het actieve type van [slot] is
    // (zie FclGlucoLinkNavHost.kt's statusBaseFor()), dus rechtstreeks scopen op
    // SensorType.CARESENS_AIR i.p.v. eerst nog selectedSensor(slot) op te
    // vragen.
    //
    // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX — live-melding: twee
    // gelijktijdig gekoppelde CareSens Air-sensoren "lijken weer samen te
    // vloeien") — was `sensorType = SensorType.CARESENS_AIR`: exact de
    // situatie die GlucoseReadingStore.kt's kdoc bij latestReading()
    // beschrijft — met TWEE CareSens Air-sensoren tegelijk (slot A + slot B)
    // filtert `sensorType` niets meer, beide fysieke sensoren delen immers
    // dezelfde `sensorType`-waarde. Nu gescoped op [slot] zelf, per
    // definitie uniek ongeacht welk sensortype er toevallig draait.
    val latest by store.latestReading(slot = slot).collectAsState(initial = null)
    val connectionState by ConnectionStatusBridge.state(slot).collectAsState()
    val scan by settings.careSensAirScan(slot).collectAsState(initial = null)
    val sensorStartedAtMs by settings.careSensAirSensorStartedAtMs(slot).collectAsState(initial = null)
    val lastConnectedAtMs by settings.careSensAirLastConnectedAtMs(slot).collectAsState(initial = null)
    val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000
    val endDateText = sensorStartedAtMs?.let {
        SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(it + fifteenDaysMs))
    } ?: "—"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CareSens Air") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SensorInfoBlock(
                selectedSensor = SensorType.CARESENS_AIR,
                connectionState = connectionState,
                latest = latest,
                endDateText = endDateText,
                serialNumber = scan?.serial,
                sensorStartedAtMs = sensorStartedAtMs,
                lastConnectedAtMs = lastConnectedAtMs
            )

            OutlinedButton(onClick = onManageSensor, modifier = Modifier.fillMaxWidth()) {
                Text("Start / switch sensor")
            }

            if (connectionState !is ConnectionState.Disconnected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            }
        }
    }
}

/**
 * 09/08/2026 (editor, RONDE 64) — zie kdoc bij
 * DexcomG6StatusScreen.kt's dexcomG6CompactSummaryText(): zelfde idee,
 * CareSens-kant.
 *
 * 09/08/2026 (editor, RONDE 75, op verzoek — "Bij de caresens staat er nu
 * op het hoofdscherm connected en dan het serienr dat moet worden de
 * lastconnected info net als bij de dexcom") — was `"Connected · #serial"`
 * zolang `connectionState is ConnectionState.Connected`, wat op het
 * hoofdscherm nogal wisselvallig oogde (CareSens Air verbindt/ontkoppelt
 * net als de G6 periodiek per poll, zie ConnectionStatusBridge) en niet
 * hetzelfde zei als het G6-kaartje ernaast. `serialNumber`-parameter
 * hierdoor ook vervallen — het serienummer blijft gewoon zichtbaar op het
 * volle CareSensAirStatusScreen (SensorInfoBlock), alleen niet meer hier op
 * dit compacte hoofdscherm-kaartje. Nu dezelfde prioriteit als
 * dexcomG6StatusText()'s staart: scanning/connecting eerst, anders altijd
 * "Last connected ..." (blijft ook zichtbaar TIJDENS een actieve
 * Connected-poll, ipv kort naar "Connected" te flitsen en weer terug).
 */
fun careSensAirCompactSummaryText(
    connectionState: ConnectionState,
    lastConnectedAtMs: Long?
): String = when {
    connectionState is ConnectionState.Scanning -> "Searching for sensor…"
    connectionState is ConnectionState.Connecting -> "Connecting…"
    lastConnectedAtMs != null ->
        "Last connected " + SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(lastConnectedAtMs))
    else -> "Not connected yet"
}
