package com.fclglucolink.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.simulator.PersistedSimulatorMode
import com.fclglucolink.app.sensor.simulator.SimulatorControlBridge
import com.fclglucolink.app.sensor.simulator.SimulatorReplayState
import com.fclglucolink.app.sensor.simulator.queryUriDisplayName
import com.fclglucolink.app.sensor.simulator.readMmolValuesFromUri
import com.fclglucolink.app.startBleConnectionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val INTERVAL_REALTIME_MS = 5 * 60_000L
// 31/07/2026 (editor, na feedback: "5sec-5min instelbaar -> zet dat op 5
// minuten (default) en 1 minuut") — was 5_000L (5 sec); nu 1 minuut, voor
// beide simulator-sensoren (Random values + External list) die deze
// constante delen.
private const val INTERVAL_FAST_MS = 60_000L

/**
 * 30/07/2026 (editor) — "sensor" om het exportpad naar AAPS te testen zonder
 * echte hardware. Drie manieren om waarden te versturen:
 *  1. Handmatige waarde (eenmalig of herhalend).
 *  2. Willekeurige-maar-realistische waarden (RandomBgGenerator) — voor
 *     langere connectiviteitstests zonder zelf steeds waarden te verzinnen.
 *  3. Externe lijst, sequentieel afgespeeld en LOOPEND (begint na de laatste
 *     waarde weer vooraan) — om een eerder probleemscenario exact te kunnen
 *     herafspelen. De gekozen lijst wordt onthouden (persistable URI-
 *     permissie) zodat je 'm niet elke sessie opnieuw hoeft te kiezen.
 *
 * Geen koppelscherm nodig (geen BLE) — deze schrijft de instellingen direct
 * weg en start de service, net als PairingScreen doet na een geslaagde
 * koppeling.
 *
 * Lijst-formaat: bewust zo simpel mogelijk — één BG-waarde in mmol/L per
 * regel, chronologische volgorde, punt of komma als decimaalteken.
 * Niet-numerieke regels (bv. een headerregel) worden genegeerd. Geen
 * tijdstempel-kolom: de vaste interval (echte snelheid of versneld) bepaalt
 * de afspeelsnelheid, dat komt het dichtst bij hoe een CGM daadwerkelijk
 * elke ~5 minuten een waarde aanlevert.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorSetupScreen(
    onDone: () -> Unit,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: zie PairingScreen.kt's identieke kdoc bij zijn
    // eigen [slot]-parameter.
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    val replayState by SimulatorControlBridge.replayState.collectAsState()
    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
    // [GlucoseUnit]-kdoc. Alleen het "Manual value"-tekstveld en de
    // statusregels hieronder volgen dit — de externe-lijst-bestandsindeling
    // (readMmolValuesFromUri) blijft bewust altijd mmol/L, ongeacht deze
    // instelling: dat is een opslagformaat-afspraak (bestaande bestanden op
    // schijf), geen live invoerveld.
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)

    // Simulator gekozen -> instellingen meteen vastleggen + service starten,
    // net zoals PairingScreen dat doet na een geslaagde koppeling. Geen
    // apart "koppelen"-moment nodig, er is niets om mee te koppelen.
    //
    // 02/08/2026 (editor) — het opruimen van oude metingen van een vorige
    // sensor gebeurt bewust NIET hier, zie kdoc bij
    // GlucoseReadingStore.trimFrom() en de aanroep in
    // BleConnectionService.kt (pas zodra de eerste meting van de simulator
    // daadwerkelijk binnenkomt, zodat nog geldige oudere historie niet
    // onnodig verdwijnt).
    LaunchedEffect(Unit) {
        settings.setSelectedSensor(slot, SensorType.SIMULATOR)
        settings.setDeviceAddress(slot, "simulator")
        startBleConnectionService(context)
    }

    // 13/08/2026 (editor, RONDE 104) — startwaarde in de weergave-eenheid
    // (126 mg/dL ≈ het oude vaste "7.0" mmol/L-default).
    var manualValueText by remember { mutableStateOf(if (displayUnit == GlucoseUnit.MGDL) "126" else "7.0") }
    var repeatEnabled by remember { mutableStateOf(false) }

    var randomFast by remember { mutableStateOf(false) }

    var listUri by remember { mutableStateOf<Uri?>(null) }
    var listFileName by remember { mutableStateOf<String?>(null) }
    var listValueCount by remember { mutableStateOf(0) }
    var listError by remember { mutableStateOf<String?>(null) }
    var listFast by remember { mutableStateOf(false) }
    // 29/08/2026 (editor, RONDE 163) — instelbare baseline-waarde voor de
    // nieuwe 3-fasen-afspeelvolgorde (opwarmen -> scenario -> baseline
    // vasthouden), zie SimulatorControlBridge.kt's kdoc bij
    // SimulatorCommand.StartListReplay. Startwaarde in de weergave-eenheid,
    // zelfde patroon als manualValueText hierboven; overschreven door de
    // opgeslagen waarde zodra die hieronder is ingeladen.
    var baselineValueText by remember { mutableStateOf(if (displayUnit == GlucoseUnit.MGDL) "126" else "7.0") }

    // Eerder gekozen lijst (persistable URI) terugladen bij het openen van
    // dit scherm, zodat editor 'm niet elke sessie opnieuw hoeft te kiezen.
    LaunchedEffect(Unit) {
        val savedUri = settings.externalListUri(slot).first()?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (savedUri != null) {
            val values = runCatching { readMmolValuesFromUri(context, savedUri) }.getOrElse {
                listError = "Could not read the previously chosen file: ${it.message}"
                emptyList()
            }
            if (values.isNotEmpty()) {
                listUri = savedUri
                listFileName = queryUriDisplayName(context, savedUri)
                listValueCount = values.size
            }
        }
        // 29/08/2026 (editor, RONDE 163) — opgeslagen baseline terugladen,
        // zodat een eerder ingestelde waarde (bv. "startpunt van mijn
        // A/B-testscenario") niet elke sessie opnieuw ingetikt hoeft te
        // worden — zelfde soort persistentie-gemak als de lijst-URI hierboven.
        val savedBaselineMgdl = settings.simulatorBaselineMgdl(slot).first()
        baselineValueText = savedBaselineMgdl.formatForDisplay(displayUnit)
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        listError = null
        // Permissie laten overleven na app-herstart — anders is de URI bij
        // de volgende sessie ongeldig en moet er alsnog opnieuw gekozen
        // worden. Bewust géén vast pad (MANAGE_EXTERNAL_STORAGE is een
        // zware, apart te verantwoorden permissie) — kies één keer een
        // bestand uit bv. Documenten/AAPS-analyse, daarna onthoudt de app 'm.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val values = runCatching { readMmolValuesFromUri(context, uri) }.getOrElse {
            listError = "Could not read file: ${it.message}"
            emptyList()
        }
        listUri = uri
        listFileName = queryUriDisplayName(context, uri)
        listValueCount = values.size
        if (values.isEmpty() && listError == null) {
            listError = "No valid values found in this file."
        } else if (values.isNotEmpty()) {
            scope.launch { settings.setExternalListUri(slot, uri.toString()) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BG simulator") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Sends fictitious BG values via exactly the same path as a " +
                    "real sensor (local storage + xDrip broadcast to AAPS) — " +
                    "handy for testing the export path without real hardware.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Manual value", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = manualValueText,
                        onValueChange = { manualValueText = it },
                        label = { Text("BG (${displayUnit.suffix})") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = repeatEnabled,
                            onCheckedChange = { repeatEnabled = it }
                        )
                        Text("Repeat automatically every 5 minutes (avoids a \"stale BG\" warning in AAPS)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val mgdl = manualValueText.parseToMgdl(displayUnit) ?: return@Button
                            scope.launch {
                                if (repeatEnabled) {
                                    SimulatorControlBridge.startRepeating(mgdl, INTERVAL_REALTIME_MS)
                                    // Onthouden zodat een service-herstart (bv.
                                    // door geheugendruk terwijl het scherm dicht
                                    // is) dit vanzelf hervat — zie kdoc bij
                                    // PersistedSimulatorMode/BleConnectionService.
                                    settings.setActiveSimulatorMode(slot, 
                                        PersistedSimulatorMode.Repeat(mgdl, INTERVAL_REALTIME_MS)
                                    )
                                } else {
                                    // Eenmalige waarde: niets om te hervatten,
                                    // dus een eventueel eerder actieve modus
                                    // wissen (SimulatorDriver annuleert 'm hier
                                    // toch al, zie replayJob?.cancel()).
                                    SimulatorControlBridge.sendSingleValue(mgdl)
                                    settings.setActiveSimulatorMode(slot, PersistedSimulatorMode.None)
                                }
                            }
                        }) { Text(if (repeatEnabled) "Start repeating" else "Send now") }

                        OutlinedButton(onClick = {
                            scope.launch {
                                SimulatorControlBridge.stop()
                                settings.setActiveSimulatorMode(slot, PersistedSimulatorMode.None)
                            }
                        }) {
                            Text("Stop")
                        }
                    }
                }
            }

            HorizontalDivider()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Random values", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Generates a new, realistic BG relative to the previous " +
                            "value at every step — usually stable, occasionally a " +
                            "meal-like rise and fall. No independent random jumps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = !randomFast, onClick = { randomFast = false })
                        Text("Real-time speed (every 5 min)")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = randomFast, onClick = { randomFast = true })
                        Text("Accelerated (every 1 min)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val interval = if (randomFast) INTERVAL_FAST_MS else INTERVAL_REALTIME_MS
                            scope.launch {
                                SimulatorControlBridge.startRandomWalk(interval)
                                settings.setActiveSimulatorMode(slot, PersistedSimulatorMode.RandomWalk(interval))
                            }
                        }) { Text("Start generating") }

                        OutlinedButton(onClick = {
                            scope.launch {
                                SimulatorControlBridge.stop()
                                settings.setActiveSimulatorMode(slot, PersistedSimulatorMode.None)
                            }
                        }) {
                            Text("Stop")
                        }
                    }
                }
            }

            HorizontalDivider()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("External list scenario", style = MaterialTheme.typography.titleMedium)
                    // 29/08/2026 (editor, RONDE 163, op verzoek — zie
                    // SimulatorControlBridge.kt's kdoc bij
                    // SimulatorCommand.StartListReplay voor het volledige
                    // A/B-testdoel) — was "loops continuously"; nu drie
                    // duidelijk afgebakende fasen i.p.v. een oneindige lus,
                    // zodat een testrun reproduceerbaar is (bv. eerst met het
                    // oude FCLvNext-algoritme, dan — na de testversie
                    // installeren en de IOB weer gelijkzetten — nog een keer
                    // met de nieuwe).
                    Text(
                        "One BG value (mmol/L) per line, chronological order " +
                            "— e.g. an earlier problem episode from your FCLvNext logs. " +
                            "Pick a file from, say, Documents/AAPS-analysis; that choice " +
                            "is remembered. Playback: starts with the baseline BG below " +
                            "(repeated 3×, so AAPS/FCLvNext can build up a known IOB " +
                            "first), then plays the list once, then jumps back to and " +
                            "holds the baseline — handy for running the exact same " +
                            "scenario twice (before/after an algorithm change) and " +
                            "comparing the results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    OutlinedTextField(
                        value = baselineValueText,
                        onValueChange = { baselineValueText = it },
                        label = { Text("Baseline BG (${displayUnit.suffix})") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(onClick = {
                        openDocument.launch(arrayOf("text/*", "text/comma-separated-values", "text/csv"))
                    }) {
                        Text(if (listUri == null) "Choose file" else "Choose different file")
                    }

                    if (listUri != null) {
                        Text("Current list: ${listFileName ?: "(unknown name)"} — $listValueCount value(s).")
                    }
                    listError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    Text("Playback speed", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = !listFast, onClick = { listFast = false })
                        Text("Real-time speed (1 value per 5 min)")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = listFast, onClick = { listFast = true })
                        Text("Accelerated (1 value per minute, for quick runs)")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = listValueCount > 0,
                            onClick = {
                                val uri = listUri ?: return@Button
                                val baselineMgdl = baselineValueText.parseToMgdl(displayUnit) ?: return@Button
                                val values = runCatching { readMmolValuesFromUri(context, uri) }.getOrDefault(emptyList())
                                val mgdlValues = values.map { it.mmolToMgdl() }
                                val interval = if (listFast) INTERVAL_FAST_MS else INTERVAL_REALTIME_MS
                                scope.launch {
                                    settings.setSimulatorBaselineMgdl(slot, baselineMgdl)
                                    SimulatorControlBridge.startListReplay(baselineMgdl, mgdlValues, interval)
                                    settings.setActiveSimulatorMode(
                                        slot,
                                        PersistedSimulatorMode.ListReplay(baselineMgdl, interval)
                                    )
                                }
                            }
                        ) { Text("Start playback") }

                        OutlinedButton(onClick = {
                            scope.launch {
                                SimulatorControlBridge.stop()
                                settings.setActiveSimulatorMode(slot, PersistedSimulatorMode.None)
                            }
                        }) {
                            Text("Stop")
                        }
                    }

                    Text(replayStatusText(replayState, displayUnit), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("To status screen")
            }
        }
    }
}

// 13/08/2026 (editor, RONDE 104) — eenheid-bewust i.p.v. hardcoded mmol/L,
// zie ui/Units.kt's [GlucoseUnit]-kdoc.
private fun replayStatusText(state: SimulatorReplayState, unit: GlucoseUnit): String = when (state) {
    is SimulatorReplayState.Idle -> "Nothing active."
    is SimulatorReplayState.RepeatingValue ->
        "Repeating ${state.glucoseMgdl.formatForDisplayWithUnit(unit)}…"
    // 29/08/2026 (editor, RONDE 163) — de nieuwe opwarmfase vóór een
    // lijst-scenario, zie SimulatorReplayState.PlayingBaselineWarmup's kdoc.
    is SimulatorReplayState.PlayingBaselineWarmup ->
        "Baseline warm-up ${state.step}/${state.total}: " +
            "${state.glucoseMgdl.formatForDisplayWithUnit(unit)}…"
    // 29/08/2026 (editor, RONDE 163) — geen "lap" meer, het scenario speelt
    // nu precies één keer af (zie kdoc bij SimulatorCommand.StartListReplay).
    is SimulatorReplayState.PlayingList ->
        "Playing scenario: ${state.index}/${state.total} " +
            "(${state.currentMgdl.formatForDisplayWithUnit(unit)})"
    is SimulatorReplayState.GeneratingRandom ->
        "Generating: ${state.currentMgdl.formatForDisplayWithUnit(unit)}…"
}

// readMmolValuesFromUri()/queryUriDisplayName() zijn verhuisd naar
// sensor/simulator/SimulatorListFile.kt — BleConnectionService heeft ze nu
// ook nodig (zie kdoc daar) om een actieve lijst-afspeelmodus te kunnen
// herstarten na een service-herstart, dus horen ze niet meer alleen bij dit
// UI-scherm.
