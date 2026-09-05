package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.flow.combine

/**
 * 30/07/2026 (editor) — sensorkeuzemenu. Niet-geïmplementeerde sensoren
 * (SensorType.implemented == false) staan gewoon zichtbaar in de lijst maar
 * uitgegrijsd met een duidelijke reden — beter dan ze helemaal te verbergen,
 * zodat je meteen ziet welke drie sensoren dit uiteindelijk gaat
 * ondersteunen, ook voordat G7/Accu-Chek af zijn.
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek: "de sensor knop naast de Bg
 * waarde is bedoeld om van sensor type te wisselen. Als je daar een andere
 * sensor type aanklikt terwijl er nog een andere actief is moet hij dan
 * uiteraard eerst een melding maken [...] en of je zeker weet dat je wilt
 * wisselen") — dit scherm is nu die TYPE-wissel-ingang, geen kale lijst meer:
 *  - Tikken op het AL ACTIEVE type opent gewoon direct dat type's eigen
 *    statusscherm (onReopenActive) — geen destructieve actie, dus geen
 *    bevestiging nodig; dit is puur "laat me het huidige type nog eens
 *    zien", niet "wissel".
 *  - Tikken op een ANDER type dan het actieve toont eerst een
 *    bevestigingsdialoog (de oude sensor wordt losgekoppeld bij een "ja") —
 *    tikken op een type terwijl er nog NIETS actief is heeft geen dialoog
 *    nodig (niets om te verliezen).
 * [activeSensor]/[activeSensorConnected] bepalen samen de dialoogtekst (welk
 * type is nu actief, is dat ook daadwerkelijk verbonden of alleen maar
 * "gekozen"). De daadwerkelijke loskoppel-/opruimstappen en de vraag of het
 * NIEUWE type al een bekende identiteit heeft (dus de setup-wizard kan
 * overslaan) horen niet hier thuis — dat is navigatie-orkestratie, zie
 * FclGlucoLinkNavHost.kt's ROUTE_SENSOR_SELECTION.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 *
 * 10/08/2026 (editor, RONDE 80, letterlijk verzoek — "dat ik als sensor ook
 * geen kan kiezen bij de sensoren") — nieuwe [onClearSensor]-actie erbij: een
 * extra "None"-kaart bovenaan de lijst (zie [NoneSensorCard]) naast de drie
 * echte sensortypes, waarmee een slot expliciet leeggemaakt kan worden i.p.v.
 * alleen tussen types te kunnen WISSELEN. Zelfde bevestigingslogica als een
 * gewone type-wissel hierboven (tikken terwijl er al een sensor actief is ->
 * eerst een bevestigingsdialoog, want dat ontkoppelt 'm) — alleen de
 * DOELWAARDE is nu "geen sensor" i.p.v. een ander SensorType, dus een losse
 * `pendingClear`-boolean i.p.v. hergebruik van `pendingSwitchTarget`
 * (SensorType? kan geen "None" uitdrukken zonder een nep-enum-waarde erbij te
 * verzinnen — bewust vermeden om SensorType's exhaustive when-blokken elders
 * in de app niet overal een geval "SensorType.NONE" te hoeven laten
 * afhandelen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorSelectionScreen(
    activeSensor: SensorType?,
    onReopenActive: () -> Unit,
    onSensorChosen: (SensorType) -> Unit,
    onClearSensor: () -> Unit
) {
    var pendingSwitchTarget by remember { mutableStateOf<SensorType?>(null) }
    var pendingClear by remember { mutableStateOf(false) }

    // 29/08/2026 (editor, RONDE 164, op verzoek — "het kunnen kiezen van de
    // virtuele sensor (en ook de andere) onder een expert modus te zetten
    // [...] zodat als je in 1 van de slots kiest je alleen de ingestelde/
    // geactiveerde sensoren ziet") — zie ui/SettingsScreen.kt's "Expert
    // mode"-kaart en AppSettings.isSensorTypeEnabledInPicker()'s kdoc voor
    // de volledige achtergrond. `combine` i.p.v. los per type collectAsState
    // in een forEach: één stabiele, samengevoegde Flow, geen herhaalde
    // composable-aanroepen binnen een lambda nodig.
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val enabledSensorTypesFlow = remember(settings) {
        combine(SensorType.entries.map { settings.isSensorTypeEnabledInPicker(it) }) { enabledFlags ->
            SensorType.entries.filterIndexed { index, _ -> enabledFlags[index] }.toSet()
        }
    }
    val enabledSensorTypes by enabledSensorTypesFlow.collectAsState(initial = SensorType.entries.toSet())
    // Het momenteel actieve type blijft ALTIJD zichtbaar, ook als het net in
    // Expert mode is uitgevinkt — anders zou je de sensor die daadwerkelijk
    // actief is niet meer kunnen terugvinden/beheren vanuit dit scherm.
    val visibleSensorTypes = SensorType.entries.filter { it == activeSensor || it in enabledSensorTypes }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Choose your sensor") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NoneSensorCard(
                isActive = activeSensor == null,
                onClick = {
                    // Al "None" -> niets om te doen (er is toch al niets
                    // actief, geen status-scherm om naar terug te gaan zoals
                    // bij onReopenActive() hierboven).
                    if (activeSensor != null) pendingClear = true
                }
            )
            visibleSensorTypes.forEach { sensor ->
                SensorCard(
                    sensor = sensor,
                    isActive = sensor == activeSensor,
                    onClick = {
                        if (!sensor.implemented) return@SensorCard
                        when {
                            sensor == activeSensor -> onReopenActive()
                            activeSensor == null -> onSensorChosen(sensor)
                            else -> pendingSwitchTarget = sensor
                        }
                    }
                )
            }
        }
    }

    val target = pendingSwitchTarget
    if (target != null && activeSensor != null) {
        AlertDialog(
            onDismissRequest = { pendingSwitchTarget = null },
            title = { Text("Switch sensor?") },
            text = {
                Text(
                    "${activeSensor.displayName} is currently active. Switching to " +
                        "${target.displayName} disconnects it — you can switch back " +
                        "later, its settings are kept. Are you sure?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitchTarget = null
                    onSensorChosen(target)
                }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitchTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (pendingClear && activeSensor != null) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("Clear this slot?") },
            text = {
                Text(
                    "${activeSensor.displayName} is currently active. Clearing sets this " +
                        "slot to no sensor and disconnects it — you can pick a sensor for " +
                        "it again later. Are you sure?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = false
                    onClearSensor()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * 10/08/2026 (editor, RONDE 80) — zelfde kaart-opzet als [SensorCard]
 * hieronder (bewust een losse, kleinere composable i.p.v. [SensorCard] met
 * een `sensor: SensorType?`-parameter uit te breiden — SensorType is hier
 * overal verder een non-null type, dat zo houden voorkomt een hoop overbodige
 * null-checks door de rest van dit bestand).
 */
@Composable
private fun NoneSensorCard(isActive: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        ListItem(
            headlineContent = { Text(if (isActive) "None  ·  Active" else "None") },
            supportingContent = {
                Text(
                    if (isActive) {
                        "No sensor chosen for this slot."
                    } else {
                        "Clear this slot — no sensor, nothing connected."
                    },
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            },
            leadingContent = { Icon(Icons.Filled.Block, contentDescription = null) }
        )
    }
}

@Composable
private fun SensorCard(sensor: SensorType, isActive: Boolean, onClick: () -> Unit) {
    // 30/07/2026 (editor, bugfix) — was fillMaxSize(): in een gewone Column
    // (geen weight/scroll) claimt dat de VOLLEDIGE resterende schermhoogte
    // voor het EERSTE kaartje, waardoor alle volgende kaartjes (Dexcom G7,
    // Accu-Chek, BG-simulator) buiten beeld geduwd worden — precies waarom
    // alleen "CareSens Air" zichtbaar was. fillMaxWidth() (wrap content
    // height) is wat hier bedoeld was.
    //
    // 09/08/2026 (editor, RONDE 64) — nieuwe [isActive]-parameter: een
    // duidelijk zichtbaar "Active" label bij het huidige type, zodat het
    // onderscheid tussen "tik = terug naar dit scherm" (actief type) en
    // "tik = wissel-bevestiging" (ander type) ook visueel logisch aanvoelt.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (sensor.implemented)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ),
        onClick = onClick
    ) {
        ListItem(
            headlineContent = {
                Text(if (isActive) "${sensor.displayName}  ·  Active" else sensor.displayName)
            },
            supportingContent = {
                when {
                    !sensor.implemented -> Text(
                        "Not available yet",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    isActive -> Text(
                        "Currently active — tap to open its status screen.",
                        color = MaterialTheme.colorScheme.primary
                    )
                    sensor == SensorType.SIMULATOR -> Text(
                        "Not a real sensor — for testing without hardware",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 03/08/2026 (editor) — nu een keuze tussen barcode-scan
                    // (nieuwe sensor) en direct uit de Bluetooth-lijst
                    // (al-lopende sensor), zie CareSensAirChooseScreen.kt.
                    sensor == SensorType.CARESENS_AIR -> Text(
                        "New sensor: scan the barcode. Already running: pick it straight from Bluetooth.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 08/08/2026 (editor, RONDE 55) — geen barcode-scan, zie
                    // DexcomG6SetupScreen.kt.
                    sensor == SensorType.DEXCOM_G6 -> Text(
                        "Enter the 6-character transmitter ID, then pick it from Bluetooth.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 17/08/2026 (editor, RONDE 112) — geen barcode-scan, zie
                    // DexcomG7SetupScreen.kt. Nog niet tegen een echte sensor
                    // getest — zie DexcomG7Driver.kt's kdoc.
                    sensor == SensorType.DEXCOM_G7 -> Text(
                        "Enter the 4-digit pairing code, then pick it from Bluetooth. New, untested against real hardware yet.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            leadingContent = { Icon(Icons.Filled.Bluetooth, contentDescription = null) }
        )
    }
}
