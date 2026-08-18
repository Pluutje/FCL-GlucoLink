package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6CalibrationCode
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6CalibrationState
import com.fclglucolink.app.startBleConnectionService
import com.fclglucolink.app.stopBleConnectionService
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6: nieuwe sensor starten
 * ============================================================================
 *
 * 08/08/2026 (editor, RONDE 56, op verzoek — "ik start een sensor op in
 * xdrip [...] met een specifieke sensor code") — NIET hetzelfde scherm als
 * DexcomG6SetupScreen.kt (dat koppelt de TRANSMITTER, eenmalig). Dit scherm
 * is voor het starten van een NIEUWE FYSIEKE SENSOR op een al gekoppelde
 * transmitter (elke ~10-60 dagen, afhankelijk van transmitter/hack) — de
 * 4-cijferige code staat op de sensorverpakking, zie
 * sensor/dexcomg6/DexcomG6CalibrationCode.kt's kdoc voor het verschil met
 * de transmitter-ID en waarom deze code (i.t.t. vingerprik-kalibratie) wél
 * naar de transmitter gestuurd moet worden.
 *
 * Slaat de code alleen op als "klaarstaand" (AppSettings.
 * setDexcomG6PendingNewSensorCode) — de daadwerkelijke verzending gebeurt
 * bij de eerstvolgende geslaagde BLE-verbinding (zie DexcomG6Driver.kt's
 * runControlSequence). Forceert hier een verse verbindpoging (stop+start
 * de service) zodat dat niet per ongeluk tot 5 minuten op de voorspellende
 * herverbind-cooldown hoeft te wachten.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 *
 * 09/08/2026 (editor, RONDE 60, op verzoek — "als je een sensor koppelt
 * springt hij na invoeren van de code weer op het zelfde scherm met de kans
 * dat je het nog een keer doet als er niks gebeurt") — was: `onStarted()`
 * riep direct `navController.popBackStack()` aan, zonder enige bevestiging
 * dat de code daadwerkelijk klaargezet is. Dat gaf twee problemen: (1) geen
 * zichtbare feedback dat de tik iets gedaan heeft, (2) niets hield de
 * gebruiker tegen om — als het scherm "zomaar" weer verdwijnt — de knop
 * gewoon nogmaals te gebruiken. Nu: de knop wordt na de eerste tik meteen
 * uitgeschakeld (geen dubbele indiening meer mogelijk) en het formulier
 * wordt vervangen door een expliciete bevestigingskaart — de gebruiker moet
 * zelf op "Back to Sensor" tikken om terug te gaan, in plaats van dat het
 * scherm automatisch dichtklapt.
 *
 * 09/08/2026 (editor, RONDE 66, op verzoek — "als je nu zegt start new
 * sensor stopt hij dan automatisch de lopende? Zoja, dan hoeft er geen stop
 * sensor knop te komen maar moet er wel een waarschuwing komen of je de
 * oude wel wilt stoppen") — nee, tot deze ronde stopte de app de lopende
 * sessie NOOIT (queued gewoon een nieuwe start-met-code, wat de transmitter
 * — mirror van xDrip+'s eigen handmatige procedure, zie
 * DexcomG6Protocol.parseSessionStart()'s kdoc — dan simpelweg afwijst met
 * infoCode 0x02 "already started" zonder de nieuwe code toe te passen).
 * `sessionAppearsActive` hieronder leest het laatst bekende, door de
 * transmitter zelf gerapporteerde sensor-statusbyte (zie
 * DexcomG6CalibrationState.kt) — als dat NIET op gestopt/verlopen/mislukt
 * staat, toont de "Start sensor"-knop eerst deze waarschuwing; pas na
 * bevestiging wordt zowel de nieuwe code ALS het "stop eerst"-vlaggetje
 * klaargezet (DexcomG6Driver.kt's runControlSequence() voert dan binnen
 * dezelfde verbindcyclus eerst een SessionStop uit, dan pas de nieuwe
 * SessionStart). Geen apart "Stop sensor"-knop nodig — dit dekt precies het
 * enige scenario waarin stoppen zinvol is (een nieuwe sensor starten terwijl
 * de oude nog loopt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomG6NewSensorScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: zie PairingScreen.kt's identieke kdoc bij zijn
    // eigen [slot]-parameter.
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var input by remember { mutableStateOf("") }
    val isValid = input.length == 4 && DexcomG6CalibrationCode.checkCode(input)
    var showInvalidHint by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var showActiveSessionWarning by remember { mutableStateOf(false) }
    val lastCalibrationStateRaw by settings.dexcomG6LastCalibrationState(slot).collectAsState(initial = null)
    val sessionAppearsActive = lastCalibrationStateRaw?.let {
        DexcomG6CalibrationState.fromRaw(it).sensorStarted()
    } == true

    fun queueSensorStart(stopFirst: Boolean) {
        scope.launch {
            if (stopFirst) {
                settings.setDexcomG6PendingStopBeforeStart(slot, true)
            }
            settings.setDexcomG6PendingNewSensorCode(slot, input)
            stopBleConnectionService(context)
            startBleConnectionService(context)
            submitted = true
        }
    }

    if (showActiveSessionWarning) {
        AlertDialog(
            onDismissRequest = { showActiveSessionWarning = false },
            title = { Text("Sensor already active?") },
            text = {
                Text(
                    "The transmitter last reported an active sensor session. " +
                        "Starting a new sensor will first stop that one — any " +
                        "readings from it will end. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showActiveSessionWarning = false
                    queueSensorStart(stopFirst = true)
                }) {
                    Text("Stop old sensor and start new one")
                }
            },
            dismissButton = {
                TextButton(onClick = { showActiveSessionWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (submitted) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Start new G6 sensor") },
                    navigationIcon = {
                        IconButton(onClick = onStarted) {
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
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Code $input queued.",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "FCLGlucoLink is reconnecting to the transmitter now and will " +
                        "send this code as soon as the link is back up. Check the " +
                        "Sensor screen — \"Last connected\" will update once it's " +
                        "actually gone through.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onStarted, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Sensor")
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Start new G6 sensor") },
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
                "Enter the 4-digit sensor code printed on the new sensor's " +
                    "packaging (the same code you'd enter in xDrip+). This is " +
                    "different from the transmitter ID — it's specific to " +
                    "this one physical sensor and lets the transmitter " +
                    "convert its raw signal into mg/dL. Only needed when " +
                    "starting a brand new sensor, not on ordinary reconnects.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = input,
                onValueChange = {
                    if (it.length <= 4 && it.all(Char::isDigit)) {
                        input = it
                        showInvalidHint = false
                    }
                },
                label = { Text("Sensor code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )

            if (showInvalidHint) {
                Text(
                    "That doesn't look like a valid sensor code — double-check the digits.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "The code is sent to the transmitter on the next connection " +
                    "attempt, which this screen kicks off right away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Button(
                onClick = {
                    if (input.length == 4 && !isValid) {
                        showInvalidHint = true
                        return@Button
                    }
                    if (sessionAppearsActive) {
                        showActiveSessionWarning = true
                    } else {
                        queueSensorStart(stopFirst = false)
                    }
                },
                enabled = input.length == 4,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start sensor")
            }
        }
    }
}
