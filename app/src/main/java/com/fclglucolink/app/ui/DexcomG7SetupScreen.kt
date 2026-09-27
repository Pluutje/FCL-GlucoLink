package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+ koppel-stap 0/2: koppelcode invoeren
 * ============================================================================
 *
 * 17/08/2026 (editor, RONDE 112) — mirror van DexcomG6SetupScreen.kt, met
 * de twee verschillen die DexcomG7Driver.kt's kdoc ook noemt: (1) 4 CIJFERS
 * i.p.v. 6 alfanumerieke tekens (de code staat op de sensor-applicator, niet
 * op een aparte transmitter — de G7 heeft geen losse, herbruikbare
 * transmitter), en (2) deze code bepaalt NIET de BLE-scanfilter (zie
 * DexcomG7Driver.buildPairingListFilter — dat filtert breed op naam-prefix
 * "DXCM"/"DX01"/"DX02"), alleen het J-PAKE-wachtwoord.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomG7SetupScreen(onBack: () -> Unit, onConfirmed: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val normalized = input.trim()
    val isValid = normalized.length == 4 && normalized.all { it.isDigit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Dexcom G7 / ONE+") },
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
                "Enter the 4-digit pairing code printed on the sensor " +
                    "applicator (the same code you'd enter in xDrip+ or the " +
                    "official Dexcom app).",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) input = it },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )

            if (input.isNotEmpty() && !isValid) {
                Text(
                    "Pairing code must be exactly 4 digits (got ${normalized.length}).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 17/08/2026 (editor, RONDE 112) — zelfde herinnering als
            // DexcomG6SetupScreen.kt, hier net zo relevant.
            Text(
                "Note: FCLGlucoLink does its own calibration in-app — it " +
                    "never sends calibration data back to the transmitter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            // 17/08/2026 (editor, RONDE 112) — nog niet tegen een echte
            // sensor getest, zie DexcomG7Driver.kt's kdoc — bewust zichtbaar
            // gemaakt i.p.v. alleen in code-commentaar, zodat het bij de
            // eerste koppelpoging geen verrassing is als iets nog niet werkt.
            Text(
                "Dexcom G7/ONE+ support is new and hasn't been tested " +
                    "against a real sensor yet — if pairing fails, please " +
                    "report exactly what happened so it can be fixed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
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
