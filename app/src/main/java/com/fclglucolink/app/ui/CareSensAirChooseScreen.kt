package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * FCLGlucoLink — CareSens Air koppel-stap 0/4: nieuw vs. al lopend sensor
 * ============================================================================
 *
 * 03/08/2026 (editor, op verzoek — geen barcode bij de hand onderweg) —
 * vóór de barcode-scan (CareSensAirScanScreen.kt) komt nu deze keuze. Twee
 * paden:
 *  - "New sensor": ongewijzigd, gaat naar de barcode-scan (nodig om een
 *    NIEUWE sensor voor het eerst te herkennen/activeren).
 *  - "Already-running sensor": slaat de barcode-scan volledig over en gaat
 *    rechtstreeks naar het generieke koppelscherm (PairingScreen.kt). Dit
 *    werkt zonder enige aanpassing daar: CareSensAirDriver.buildPairingList
 *    Filter() matcht al op "CSAIR" in de BLE-advertentienaam (los van een
 *    eventueel gescand serienummer, zie de kdoc daar), en het serienummer
 *    zelf wordt tijdens de GATT-handshake ALTIJD rechtstreeks van de sensor
 *    zelf gelezen (CHAR_SERIAL, zie CareSensAirGattProtocol.kt/
 *    CareSensAirDriver.kt regel ~978-993) — nooit uit de barcode. De
 *    barcode's overige velden (PIN, vervaldatum) worden nergens anders in
 *    de app meer gebruikt (PIN werd nooit in het GATT-protocol gebruikt;
 *    de "Package expiry"-rij is op 02/08/2026 al verwijderd uit de UI, zie
 *    StatusScreen.kt/SensorManagementScreen.kt). Deze route is dus veilig:
 *    geen enkel stuk functionaliteit hangt af van de barcode-scan zelf, die
 *    is puur een gebruiksvriendelijke manier om de koppellijst alvast te
 *    verkleinen tot de juiste sensor als er meerdere CSAir's in de buurt
 *    zijn.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareSensAirChooseScreen(
    onBack: () -> Unit,
    onNewSensor: () -> Unit,
    onExistingSensor: () -> Unit
) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Is this a brand new sensor, or one that's already running " +
                    "(e.g. paired before, or paired to another app right now)?",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth(), onClick = onNewSensor) {
                ListItem(
                    headlineContent = { Text("New sensor") },
                    supportingContent = {
                        Text(
                            "Scan the barcode on the sensor — needed the first " +
                                "time a sensor is activated.",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = onExistingSensor
            ) {
                ListItem(
                    headlineContent = { Text("Already-running sensor") },
                    supportingContent = {
                        Text(
                            "No barcode needed — pick it straight from the " +
                                "nearby Bluetooth devices. Serial number and " +
                                "everything else is read from the sensor itself " +
                                "once connected.",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    }
                )
            }
        }
    }
}
