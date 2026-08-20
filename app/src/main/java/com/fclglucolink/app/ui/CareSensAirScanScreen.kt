package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.sensor.caresensair.CareSensAirScanOutcome
import com.fclglucolink.app.sensor.caresensair.CareSensAirScanResult
import com.fclglucolink.app.sensor.caresensair.decodeCareSensAirBarcode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState
    data class Scanned(val result: CareSensAirScanResult) : ScanUiState
    // 31/07/2026 (editor, na eerste test tegen een echte sensor: parser
    // faalde op "missing or malformed expiry date (AI 17)") — de aanname
    // over CareSens Air's exacte barcode-indeling (gebaseerd op Juggluco's
    // sensoren.hpp) klopt kennelijk niet 1-op-1 met wat er in de praktijk
    // gescand wordt. `rawScanned` toont de ONBEWERKTE scanneruitvoer erbij
    // (selecteerbaar) zodat die teruggestuurd kan worden om de parser exact
    // op de echte data af te stemmen, i.p.v. verder te gokken.
    data class Error(val message: String, val rawScanned: String? = null) : ScanUiState
}

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air koppel-stap 1/4: barcode-scan
 * ============================================================================
 *
 * 31/07/2026 (editor) — CareSens Air wordt NIET gekoppeld via een BLE-
 * scanlijst zoals PairingScreen.kt dat generiek aanbiedt: de sensor draagt
 * een GS1-barcode (sensorcode/PIN/serienummer/vervaldatum) die eerst
 * gescand moet worden, exact zoals Juggluco dat ook doet vóórdat er een
 * BLE-verbinding gelegd wordt. Zie sensor/caresensair/CareSensAirBarcode.kt
 * voor de decodering en de kdoc bij CareSensAirDriver.kt voor de rest van
 * het koppelplan (stap 2: BLE-verbinding, stap 3: native kalibratiemodule,
 * stap 4: alles samenvoegen).
 *
 * Gebruikt Google's kant-en-klare code-scanner (play-services-code-scanner)
 * i.p.v. een eigen CameraX-preview + ML Kit-detector: die vraagt GEEN
 * camera-runtime-permissie aan DEZE app aan — het scannen gebeurt in een
 * geïsoleerd Play Services-proces dat alleen het resultaat teruggeeft. Geen
 * losse permissie-UX nodig, en consistent met Juggluco's eigen "scanGoogle"-
 * pad (zie PhotoScan.java).
 *
 * Dit scherm LEVERT alleen het scanresultaat op (via onScanned) — het
 * bewaren ervan en het daadwerkelijk verbinden gebeuren elders (zie
 * FclGlucoLinkNavHost.kt en, straks, CareSensAirDriver.kt).
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareSensAirScanScreen(onBack: () -> Unit, onScanned: (CareSensAirScanResult) -> Unit) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }

    fun startScan() {
        uiState = ScanUiState.Scanning
        // FORMAT_DATA_MATRIX + FORMAT_QR_CODE — Juggluco's eigen scanner
        // accepteert ook beide, zie PhotoScan.java's GmsBarcodeScannerOptions
        // (GS1-barcodes op medische verpakking staan meestal als Data
        // Matrix afgedrukt, soms als QR).
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw == null) {
                    uiState = ScanUiState.Error("Empty barcode — try scanning again.")
                    return@addOnSuccessListener
                }
                when (val outcome = decodeCareSensAirBarcode(raw)) {
                    is CareSensAirScanOutcome.Success -> uiState = ScanUiState.Scanned(outcome.result)
                    is CareSensAirScanOutcome.InvalidBarcode -> uiState = ScanUiState.Error(
                        "This doesn't look like a CareSens Air sensor barcode " +
                            "(${outcome.reason}). Make sure you're scanning the " +
                            "code on the sensor packaging, not something else.",
                        rawScanned = raw
                    )
                }
            }
            .addOnCanceledListener {
                uiState = ScanUiState.Idle
            }
            .addOnFailureListener { e ->
                uiState = ScanUiState.Error(e.message ?: "Scan failed — try again.")
            }
    }

    // Automatisch bij binnenkomst starten — scheelt een extra tik in de
    // meest voorkomende flow (net als PairingScreen's LaunchedEffect die
    // meteen begint te scannen).
    LaunchedEffect(Unit) { startScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan CareSens Air sensor") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Scan the barcode printed on the CareSens Air sensor (the " +
                    "same one the CareSens app asks you to scan) — it carries " +
                    "the sensor's identity and expiry date, no manual entry " +
                    "needed.",
                style = MaterialTheme.typography.bodyMedium
            )

            when (val state = uiState) {
                is ScanUiState.Idle -> {
                    Button(onClick = { startScan() }) { Text("Scan barcode") }
                }
                is ScanUiState.Scanning -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Opening scanner…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is ScanUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    // 31/07/2026 (editor) — toont de onbewerkte scanneruitvoer
                    // zodat die (via screenshot/kopiëren) teruggestuurd kan
                    // worden om de parser op de echte CareSens Air-barcode af
                    // te stemmen — zie kdoc bij ScanUiState.Error hierboven.
                    if (state.rawScanned != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Raw scanned data (for debugging):",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        state.rawScanned,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                // 31/07/2026 (editor) — de gewone Text hierboven
                                // toont onzichtbare/besturingstekens (bv. het
                                // echte GS1-scheidingsteken ASCII 0x1D, of een
                                // eventueel FNC1-teken vóór de eerste AI) niet
                                // — die verdwijnen gewoon visueel, waardoor je
                                // niet kunt zien of ze er wel/niet staan. Deze
                                // "gevisualiseerde" versie maakt elk teken
                                // onder 0x20 (of 0x7F) zichtbaar als "[xx]"
                                // zodat we dat exact kunnen aflezen i.p.v.
                                // gokken.
                                Text(
                                    "Length: ${state.rawScanned.length} chars",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "With control chars visible:",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        state.rawScanned.map { c ->
                                            val code = c.code
                                            if (code < 0x20 || code == 0x7F) {
                                                "[%02X]".format(code)
                                            } else {
                                                c.toString()
                                            }
                                        }.joinToString(""),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    Button(onClick = { startScan() }) { Text("Try again") }
                }
                is ScanUiState.Scanned -> {
                    val expiryText = state.result.expiryEpochMs()?.let {
                        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(it))
                    } ?: "unknown"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InfoRow("Sensor code", state.result.sensorCode)
                            InfoRow("Serial", state.result.serial)
                            InfoRow("Expires", expiryText)
                        }
                    }
                    // 20/08/2026 (editor, RONDE 117, op verzoek na Ronde 116 —
                    // de PIN stond daar als gewone InfoRow-tekstregel tussen
                    // de andere velden en de waarschuwing eronder in klein
                    // grijs, wat volgens een schermmockup makkelijk over het
                    // hoofd te zien was. De PIN krijgt nu een eigen opvallende
                    // kaart (tertiaryContainer — dezelfde "let op dit"-kleur
                    // die Material3 daarvoor heeft, past zich vanzelf aan het
                    // dark theme aan net als de rest van het scherm) met
                    // icoon en grote cijfers, in plaats van één regel tussen
                    // de rest.
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
                                    state.result.pin,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "Use this when your phone asks for a " +
                                        "Bluetooth pairing PIN in the next step " +
                                        "(also printed on the sensor packaging " +
                                        "as \"PINCODE\" / \"CODE PIN\") — not " +
                                        "whatever Android itself suggests (e.g. " +
                                        "\"try 0000 or 1234\"), that's just a " +
                                        "generic guess and won't work.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    Button(onClick = { onScanned(state.result) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Use this sensor")
                    }
                    Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan again")
                    }
                }
            }
        }
    }
}
