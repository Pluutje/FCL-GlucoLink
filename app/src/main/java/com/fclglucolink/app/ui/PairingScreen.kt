package com.fclglucolink.app.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorRegistry
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.caresensair.CareSensAirScanResult
import com.fclglucolink.app.startBleConnectionService
import kotlinx.coroutines.launch

/**
 * 30/07/2026 (editor) — koppelscherm per sensor. Bewust dun: maakt de
 * SensorDriver voor het gekozen SensorType via SensorRegistry, roept
 * startPairing() aan, toont gevonden devices in een lijst. Werkt (nu al)
 * generiek voor elke toekomstige sensor zonder dit scherm te hoeven
 * aanpassen — alleen de driver-implementatie erachter verandert per sensor.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — puur voor TopAppBar hieronder,
 * die in Material3 al sinds de allereerste release achter deze opt-in-markering
 * zit (nog steeds zo bij Compose BOM 2024.06.00). Geen eigen experimentele
 * code hier, alleen bewust bevestigen dat we die instabiliteit accepteren.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    sensorType: SensorType,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: bewaart het bestaande, enkelvoudige gedrag
    // exact zoals het was totdat de echte tab-UI (taak #311) hier een
    // daadwerkelijke slot-keuze doorgeeft.
    slot: SensorSlot = SensorSlot.A,
    onPaired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    val driver = remember(sensorType, slot) { SensorRegistry.createDriver(sensorType, slot) }
    val connectionState by driver.connectionState.collectAsState()

    val foundDevices = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }

    // 01/08/2026 (editor, op verzoek) — sommige sensoren (bv. CareSens Air,
    // zie CareSensAirDriver.kt) weten via een eerdere barcode-scan al genoeg
    // om de koppellijst te verkleinen tot plausibele kandidaten. null =
    // sensor biedt geen filter (huidig gedrag, ongewijzigd voor sensoren die
    // dit niet overschrijven). showAllDevices laat de gebruiker dit filter
    // altijd opzij zetten — een naam-filter is een vuistregel, geen garantie.
    var pairingFilter by remember { mutableStateOf<((String?, String) -> Boolean)?>(null) }
    var showAllDevices by remember { mutableStateOf(false) }
    LaunchedEffect(sensorType) {
        pairingFilter = driver.buildPairingListFilter(context)
    }

    // 20/08/2026 (editor, RONDE 116, na live-melding — een tester typte de
    // PIN die Android's EIGEN Bluetooth-koppelscherm suggereerde ("probeer
    // 0000 of 1234") i.p.v. de echte, op de sensorverpakking afgedrukte PIN
    // uit de barcode-scan, waardoor het koppelen meermaals mislukte) — hier,
    // vlak vóór het tikken op een apparaat de OS-koppeldialoog daadwerkelijk
    // opent, nogmaals expliciet de juiste PIN tonen. Alleen relevant voor
    // CareSens Air (de enige sensor hier die een barcode-gescande PIN heeft
    // — zie CareSensAirScanScreen.kt's zelfde waarschuwing, één stap eerder
    // in de flow). `null` als er (nog) geen scanresultaat is voor deze slot
    // (bv. via CareSensAirChooseScreen.kt's "already running"-pad, dat
    // bewust geen scan opslaat — zie FclGlucoLinkNavHost.kt's kdoc daar).
    val careSensAirPin: CareSensAirScanResult? by if (sensorType == SensorType.CARESENS_AIR) {
        settings.careSensAirScan(slot).collectAsState(initial = null)
    } else {
        remember { mutableStateOf<CareSensAirScanResult?>(null) }
    }
    val displayedDevices = if (showAllDevices || pairingFilter == null) {
        foundDevices
    } else {
        foundDevices.filter { pairingFilter!!(safeDeviceName(context, it), it.address) }
    }
    // 31/07/2026 (editor, na een echte koppelpoging tegen een sensor die al
    // aan een ANDERE telefoon verbonden was) — de meeste CGM/meter-sensoren
    // staan maar 1 actieve BLE-verbinding tegelijk toe, dus "niets gevonden"
    // betekent vaak niet dat er iets stuk is. Deze hint verschijnt pas na
    // een tijdje zoeken zonder resultaat — meteen tonen zou de normale,
    // korte opstart-vertraging van een scan onnodig verontrustend maken.
    var showNothingFoundHint by remember { mutableStateOf(false) }

    DisposableEffect(sensorType) {
        onDispose { driver.stopPairing() }
    }

    fun startScan() {
        foundDevices.clear()
        scanning = true
        showNothingFoundHint = false
        driver.startPairing(context) { device ->
            if (foundDevices.none { it.address == device.address }) {
                foundDevices.add(device)
            }
        }
    }

    LaunchedEffect(sensorType) { startScan() }

    LaunchedEffect(scanning, foundDevices.size) {
        showNothingFoundHint = false
        if (scanning && foundDevices.isEmpty()) {
            delay(15_000L)
            if (scanning && foundDevices.isEmpty()) {
                showNothingFoundHint = true
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair ${sensorType.displayName}") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionState is ConnectionState.Error) {
                Text(
                    (connectionState as ConnectionState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(onClick = { startScan() }) {
                Text(if (scanning) "Search again" else "Search for sensor")
            }

            // 20/08/2026 (editor, RONDE 116) — zie kdoc hierboven bij
            // careSensAirPin. Tapt de gebruiker zo dadelijk op een apparaat
            // hieronder, dan opent Android's EIGEN Bluetooth-koppelscherm —
            // dat scherm zelf kunnen we niet aanpassen (systeem-UI), dus
            // hier nogmaals expliciet de juiste PIN vlak vóór dat moment.
            //
            // 20/08/2026 (editor, RONDE 117, op verzoek) — zelfde
            // opvallende-kaart-behandeling als CareSensAirScanScreen.kt's
            // PIN-kdoc hierboven beschrijft: was hier gewone kleine
            // secondary-tekst, nu een tertiaryContainer-kaart met icoon en
            // grote cijfers zodat de PIN opvalt vlak vóórdat Android's eigen
            // koppeldialoog (met zijn eigen, verkeerde suggestie) verschijnt.
            if (careSensAirPin != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Bluetooth pairing PIN",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                careSensAirPin!!.pin,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "Use this when Android asks, after you tap a " +
                                    "device below — not Android's own " +
                                    "suggestion (e.g. \"try 0000 or 1234\"), " +
                                    "that's just a generic guess and won't work.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            if (pairingFilter != null) {
                Row {
                    Switch(checked = showAllDevices, onCheckedChange = { showAllDevices = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Show all nearby devices (skip the name filter)")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displayedDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                        driver.stopPairing()
                        scope.launch {
                            settings.setSelectedSensor(slot, sensorType)
                            // 02/08/2026 (editor) — het opruimen van oude
                            // metingen van een VORIGE sensor gebeurt bewust
                            // niet hier (dat zou bij een normale
                            // sensorvervanging ook nog geldige, recente
                            // historie wegvegen) — zie
                            // GlucoseReadingStore.trimFrom()'s kdoc en de
                            // aanroep in BleConnectionService.kt, die pas
                            // opruimt zodra de EERSTE meting van deze nieuwe
                            // sensor daadwerkelijk binnenkomt.
                            settings.setDeviceAddress(slot, device.address)
                            // Meteen de koppelservice starten — niet wachten
                            // tot een volgende app-herstart (zie kdoc bij
                            // startBleConnectionService()/MainActivity).
                            startBleConnectionService(context)
                            onPaired()
                        }
                    }) {
                        ListItem(
                            headlineContent = { Text(safeDeviceName(context, device) ?: device.address) },
                            supportingContent = { Text(device.address) }
                        )
                    }
                }
            }

            if (scanning && foundDevices.isEmpty()) {
                CircularProgressIndicator()
                Text("Searching… make sure the sensor is in pairing mode.")
                if (showNothingFoundHint) {
                    Text(
                        "Still nothing? Most sensors only allow one active " +
                            "connection at a time — make sure it isn't already " +
                            "connected to another phone or app.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else if (scanning && displayedDevices.isEmpty() && !showAllDevices) {
                // 01/08/2026 (editor) — er ZIJN apparaten gevonden, maar het
                // naam-filter houdt ze allemaal tegen — waarschijnlijker een
                // filter dat net niet matcht dan dat de sensor er niet is,
                // dus wijs direct naar de schakelaar hierboven i.p.v. de
                // generieke "nog niets gevonden"-tekst te tonen.
                Text(
                    "${foundDevices.size} nearby device(s) found, but none match the " +
                        "expected name — turn on \"Show all nearby devices\" above to see them.",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun safeDeviceName(context: android.content.Context, device: BluetoothDevice): String? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
    // Op Android <12 is er geen BLUETOOTH_CONNECT-runtime-permissie (device.name
    // werkte toen al zonder extra check), dus alleen expliciet blokkeren als de
    // permissie WEL bestaat (API>=31) en NIET verleend is.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !granted) return null
    return try { device.name } catch (_: SecurityException) { null }
}
