package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6 koppel-stap 0/2: transmitter-ID invoeren
 * ============================================================================
 *
 * 08/08/2026 (editor, RONDE 55) — de G6 heeft, i.t.t. CareSens Air, GEEN
 * barcode-scanstap in deze app: de 6-karakter transmitter-ID staat gewoon op
 * de transmitter zelf gedrukt (dezelfde ID die ook in xDrip+/BYODA
 * ingevoerd wordt) en is de enige informatie die nodig is — zowel om de
 * verwachte BLE-advertentienaam te bepalen ("Dexcom" + laatste 2 tekens,
 * zie DexcomG6Driver.buildPairingListFilter) als om de AES-authenticatie-
 * sleutel af te leiden (DexcomG6Crypto.deriveKey). Dit scherm LEVERT alleen
 * de ingevoerde ID op (via onConfirmed) — het bewaren ervan en het
 * daadwerkelijk koppelen gebeurt in FclGlucoLinkNavHost.kt, exact zoals
 * CareSensAirScanScreen.kt dat voor zijn scanresultaat doet.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomG6SetupScreen(onBack: () -> Unit, onConfirmed: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val normalized = input.trim().uppercase()
    val isValid = normalized.length == 6

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Dexcom G6") },
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
                "Enter the 6-character transmitter ID printed on the back of " +
                    "the transmitter (the same ID you'd enter in xDrip+ or " +
                    "BYODA) — no barcode scan needed for the G6.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 6) input = it },
                label = { Text("Transmitter ID") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (input.isNotEmpty() && !isValid) {
                Text(
                    "Transmitter ID must be exactly 6 characters (got ${normalized.length}).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 08/08/2026 (editor, op uitdrukkelijk verzoek van de gebruiker)
            // — herinnering die hier bewust zichtbaar staat, niet alleen in
            // code-commentaar: FCLGlucoLink stuurt GEEN kalibratiecode terug
            // naar de transmitter — kalibratie gebeurt in deze app zelf (zie
            // Calibration-scherm).
            Text(
                "Note: FCLGlucoLink does its own calibration in-app — it " +
                    "never sends calibration data back to the transmitter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Button(
                onClick = { onConfirmed(normalized) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
        }
    }
}
