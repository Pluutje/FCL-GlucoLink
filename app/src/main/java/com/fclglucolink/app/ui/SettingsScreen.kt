package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.smoothing.SmoothingStrength
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 31/07/2026 (editor, na feedback over de menu-indeling) — algemene
 * instellingen, losgetrokken van sensor-specifieke communicatie (die zit nu
 * op SensorManagementScreen.kt, geopend via de sensorkaart op het
 * statusscherm). Geopend via het ⋮-menu rechtsboven op het statusscherm.
 *
 * 06/08/2026 (editor, RONDE 53, op verzoek: "ik wil graag de 'about' knop
 * ergens anders [...] beter om het onder het laatste hoofdstuk te zetten
 * in de manual en dus niet meer bij de setting") — de link naar het
 * About-scherm die hier onderaan stond is verplaatst naar de laatste
 * pagina van de handleiding (ManualTopic.BEST_RESULTS, zie
 * ManualScreen.kt's kdoc) — dit scherm heeft dus geen `onOpenAbout`-
 * parameter meer nodig.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAlarms: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — vervangt de
    // oude, globale broadcastEnabled aan/uit-schakelaar: zie
    // AAPS-slotkiezer's kdoc verderop in dit bestand.
    val aapsActiveSlot by settings.aapsActiveSlot.collectAsState(initial = null)
    // 20/08/2026 (editor, RONDE 115) — zie XDripBroadcaster.kt's kdoc bij
    // sourceInfo().
    val xdripUniversalSourceCodeEnabled by settings.xdripUniversalSourceCodeEnabled.collectAsState(initial = false)
    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
    // [GlucoseUnit]-kdoc.
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)
    // 04/08/2026 (editor, RONDE 35) — zie DiagnosticFileLogger.kt's kdoc.
    val diagnosticLoggingEnabled by settings.diagnosticFileLoggingEnabled.collectAsState(initial = false)
    // 05/08/2026 (editor, RONDE 43) — zie CalibrationScreen.kt's kdoc.
    val calibrationEnabled by settings.calibrationEnabled.collectAsState(initial = false)
    // 06/08/2026 (editor, RONDE 49) — zie smoothing/KalmanSmoother.kt's kdoc.
    val smoothingEnabled by settings.smoothingEnabled.collectAsState(initial = false)
    // 29/08/2026 (editor, RONDE 160) — zie AppSettings.kt's
    // PREDICTION_ENABLED-kdoc en de nieuwe "Bg prediction"-kaart hieronder.
    val predictionEnabled by settings.predictionEnabled.collectAsState(initial = false)
    // 18/08/2026 (editor, RONDE 114) — zie SmoothingStrength's kdoc in
    // KalmanSmoother.kt.
    val smoothingStrength by settings.smoothingStrength.collectAsState(initial = SmoothingStrength.MEDIUM)
    // 17/08/2026 (editor, RONDE 111, op verzoek: "een (instelbare filtering
    // mogelijk die de eerste 2 dagen iets heftiger filtert en dan langzaam
    // afbouwt gedurende de loop tijd [...] dalingen zijn in mijn ogen dus
    // minder van belang") — zie smoothing/KalmanSmoother.kt's kdoc (het
    // asymmetrische, alleen-bij-stijgingen "break-in filter") en
    // BleConnectionService.kt's computeBreakInDecayFactor() voor hoe deze
    // twee waarden uiteindelijk worden toegepast.
    val breakInFilterEnabled by settings.breakInFilterEnabled.collectAsState(initial = false)
    val breakInFilterDurationHours by settings.breakInFilterDurationHours.collectAsState(initial = 24.0)
    // 24/08/2026 (editor, RONDE 125, op verzoek: "een breakout filter wat
    // eigenlijk precies omgekeerd werkt tov de breakin" — na CareSens
    // Air-meldingen dat sensoren de laatste dagen van hun looptijd weer
    // instabiel worden) — zie smoothing/KalmanSmoother.kt's klasse-kdoc
    // (RONDE-125-paragraaf) en BleConnectionService.kt's
    // computeBreakOutDecayFactor() voor het volledige mechanisme.
    val breakOutFilterEnabled by settings.breakOutFilterEnabled.collectAsState(initial = false)
    val breakOutFilterDurationHours by settings.breakOutFilterDurationHours.collectAsState(initial = 48.0)
    // 18/08/2026 (editor, RONDE 113, op verzoek: "toon gefilterde data op
    // hoofdscherm") — zie AppSettings.kt's kdoc bij Keys.
    // SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN en StatusScreen.kt's
    // SlotStatusContent voor waar dit uiteindelijk gelezen wordt.
    val showFilteredPipelineOnMainScreen by settings.showFilteredPipelineOnMainScreen.collectAsState(initial = false)
    // 08/08/2026 (editor, RONDE 57) — zie sensor/ble/BondLossRecovery.kt's kdoc.
    val bondLossAutoRecoveryEnabled by settings.bondLossAutoRecoveryEnabled.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // 06/08/2026 (editor, RONDE 51, na live-melding: "de settings pagina
        // scrollt niet waardoor de laatste regel niet leesbaar is") — deze
        // Column miste een `.verticalScroll(...)`, dus zodra de kaarten
        // samen hoger zijn dan het scherm (met de nieuwe Smoothing-kaart uit
        // ronde 49 erbij, plus de waarschuwingsregels uit ronde 50, was dat
        // hier het geval) viel de rest gewoon buiten beeld zonder enige
        // manier om ernaartoe te scrollen. Zelfde patroon als
        // CalibrationScreen.kt/ManualScreen.kt gebruiken.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur,
                    // op verzoek: "beide slots moeten kunnen zenden naar aaps
                    // waarbij er uiteraard maar max 1 actief kan zijn, maar ze
                    // moeten ook beiden uit kunnen") — vervangt de oude,
                    // enkelvoudige "Send BG to AAPS"-schakelaar door een
                    // 3-standen-kiezer: Slot A / Slot B / Off, nooit meer dan
                    // één tegelijk (SingleChoiceSegmentedButtonRow dwingt dat
                    // al af). Interim-bediening totdat de echte tab-UI (taak
                    // #311) hier een visuele groen/rood-indicator per tab
                    // van maakt — functioneel al volledig: AppSettings.
                    // aapsActiveSlot is de enige bron van waarheid die
                    // BleConnectionService.kt raadpleegt vóór elke broadcast.
                    Text(
                        "Choose which slot's BG values are sent to AAPS via the " +
                            "xDrip protocol — at most one at a time, or neither. " +
                            "Turn this off (or point it at a different slot) if " +
                            "you're using this slot to test a sensor while a " +
                            "separate xDrip app is the one actually feeding AAPS " +
                            "— otherwise AAPS would receive conflicting values " +
                            "from two sources at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text("Send BG to AAPS from", style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = aapsActiveSlot == SensorSlot.A,
                            onClick = { scope.launch { settings.setAapsActiveSlot(SensorSlot.A) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text("Slot A") }
                        SegmentedButton(
                            selected = aapsActiveSlot == SensorSlot.B,
                            onClick = { scope.launch { settings.setAapsActiveSlot(SensorSlot.B) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text("Slot B") }
                        SegmentedButton(
                            selected = aapsActiveSlot == null,
                            onClick = { scope.launch { settings.setAapsActiveSlot(null) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text("Off") }
                    }

                    HorizontalDivider()

                    // 20/08/2026 (editor, RONDE 115, op verzoek: "een knop [...]
                    // die bij ingeschakeld iedere sensor (ook de virtuele)
                    // een universele code mee geeft die zowel in aaps 3 als
                    // 4 werkt [...] en als hij is uitgeschakeld dan mag
                    // gewoon de best kloppende omschrijving worden
                    // meegestuurd") — zie XDripBroadcaster.kt's kdoc bij
                    // sourceInfo() voor de volledige AAPS v3-vs-v4-analyse
                    // die tot "AAPS-Dexcom" als universele waarde leidde.
                    // Zelfde kopje/toelichting/switch-volgorde als de
                    // Smoothing-kaart (RONDE 114c).
                    Text(
                        "Universal trusted source code",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Send every sensor (including the simulator) as a " +
                            "single source description that's trusted for " +
                            "\"SMB Always\" on both AAPS 3 and AAPS 4 — at " +
                            "the cost of AAPS/Nightscout showing a generic " +
                            "Dexcom label instead of the actual sensor. Off " +
                            "sends the best-matching description per sensor " +
                            "instead, which may not enable SMB Always on " +
                            "every AAPS version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Switch(
                            checked = xdripUniversalSourceCodeEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setXdripUniversalSourceCodeEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 13/08/2026 (editor, RONDE 104, Fase 1, op verzoek: "een mg/dl vs
            // mmol/l knop [...] intern hoeft er dan niks te veranderen maar in
            // de ui zou da weer gegeven Bg waarden dan moeten kunnen
            // veranderen") — zie ui/Units.kt's [GlucoseUnit]-kdoc voor de
            // volledige achtergrond/scope van deze ronde.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Display", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Which unit BG values are shown in — charts, the status " +
                            "ring, fingerstick/simulator input, and the connection " +
                            "notification. Storage and the AAPS broadcast always " +
                            "stay mg/dL regardless of this setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = displayUnit == GlucoseUnit.MMOL,
                            onClick = { scope.launch { settings.setDisplayUnit(GlucoseUnit.MMOL) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("mmol/L") }
                        SegmentedButton(
                            selected = displayUnit == GlucoseUnit.MGDL,
                            onClick = { scope.launch { settings.setDisplayUnit(GlucoseUnit.MGDL) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("mg/dL") }
                    }
                }
            }

            // 13/08/2026 (editor, RONDE 106, Fase 2 stap 1, op verzoek: "ik
            // wil in ieder geval 1 overal knop om in 1 keer alle alarmen
            // aan/uit te zetten [...] indien die is ingeschakeld dat dan de
            // afzonderlijke alarmen kunnen worden ingesteld maar ook ieder
            // afzonderlijk aan en uit kunnen") — bewust een KORT kaartje
            // hier, alleen met een link naar het nieuwe, uitgebreide
            // AlarmSettingsScreen.kt — zelfde opzet als Calibration
            // hierboven (dat ook een eigen scherm heeft voor de details).
            // Bij Alarms staat zelfs de "overal knop" zelf op het eigen
            // scherm i.p.v. hier, puur omdat 'm daar direct boven de 6
            // losse per-type schakelaars staat — in één oogopslag
            // duidelijker dan een schakelaar hier en de details een scherm
            // verderop.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Alarms", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Low/high glucose alerts, a predictive early warning, and " +
                            "a stale-data alert — each with its own on/off switch, " +
                            "threshold, sound, and vibration, plus one master " +
                            "switch for all of them at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenAlarms) { Text("Configure alarms") }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Debug", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Writes connection/scan diagnostics to a text file " +
                            "(Android/data/com.fclglucolink.app/files/log/) " +
                            "instead of only logcat, so a test can run for hours " +
                            "during normal phone use without a cable attached. " +
                            "Off by default — only turn this on while actively " +
                            "investigating something.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Diagnostic log to file", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = diagnosticLoggingEnabled,
                            onCheckedChange = { enabled ->
                                // Meteen het in-memory vlaggetje omzetten (zie
                                // DiagnosticFileLogger.setEnabled()'s kdoc voor
                                // waarom dit apart van de DataStore-write staat)
                                // zodat de eerstvolgende BLE-cyclus het al
                                // meeneemt, niet pas na een herstart.
                                DiagnosticFileLogger.setEnabled(enabled)
                                scope.launch { settings.setDiagnosticFileLoggingEnabled(enabled) }
                            }
                        )
                    }
                    // 04/08/2026 (editor, RONDE 38 — de ronde-36-noodgreep
                    // "Always rescan immediately" (Juggluco-standaardpad,
                    // cooldownMs=0) hier weer verwijderd. Ronde 37's
                    // LOW_LATENCY+MATCH_MODE_AGGRESSIVE-scanfix (op basis van
                    // de gedecompileerde ECHTE fabrikants-app) bracht de
                    // "tax" terug van een trimodaal patroon (25-33s/85-92s/
                    // 148-152s/tot 270s) naar een strakke ~26-30s-baseline —
                    // zie README.md's ronde-38-sectie voor de logbestand-
                    // vergelijking. Op die baseline is er geen ruimte meer
                    // voor "meteen doorscannen zonder cooldown" om nog iets
                    // te winnen (de scan is al continu/agressief zodra 'm
                    // start); de schakelaar kostte alleen nog onnodig
                    // batterij zonder enig voordeel. `AlwaysScanMode.kt` is
                    // verwijderd; de DataStore-sleutel wordt gewoon genegeerd
                    // op bestaande installaties (geen migratie nodig, was
                    // sowieso standaard UIT).
                }
            }

            // 05/08/2026 (editor, RONDE 43 — "Bij het menu. komt een
            // kalibratie aan/uit knop") — het "menu aan/uit knop"-onderdeel
            // van de kalibratiefunctie. Zet AppSettings.calibrationEnabled;
            // StatusScreen.kt toont de "Calibration"-knop op het hoofdscherm
            // alleen als dit aan staat (zie kdoc daar).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Calibration", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Adjust sensor readings using your own fingerstick " +
                            "values (linear offset or spline fit). When on, a " +
                            "\"Calibration\" button appears on the home screen. " +
                            "Calibration data is cleared automatically whenever " +
                            "you start a new sensor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 06/08/2026 (editor, RONDE 50, op verzoek: "duidelijk
                    // vermeld [...] dat als de calibratie en/of smoothing is
                    // ingeschakeld dat die dan in aaps moet worden
                    // uitgeschakeld") — bewust in de errorkleur i.p.v. de
                    // gewone secondary-kleur hierboven, precies om dit
                    // regeltje visueel te laten opvallen tussen de rest van
                    // de (neutrale) uitleg. Dezelfde boodschap staat
                    // uitgebreider in ManualScreen.kt's WarningCard.
                    Text(
                        "If you turn this on, also turn off AAPS's own " +
                            "calibration — otherwise the same correction is " +
                            "applied twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable calibration", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = calibrationEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setCalibrationEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 06/08/2026 (editor, RONDE 49, op verzoek: "de aan/uit knop
            // daarvoor kan gewoon onder de drie puntjes komen") — de "aan/
            // uit"-helft van de smoothing-functie, precies zoals de
            // gebruiker vroeg: hier bij de rest van het ⋮-menu, in dezelfde
            // Card-stijl als de Calibration-schakelaar hierboven. Zet
            // AppSettings.smoothingEnabled; BleConnectionService past het
            // Kalman-filter alleen toe als dit aan staat (zie
            // applySmoothingIfEnabled()'s kdoc daar).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Smoothing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Smooths out sensor noise and single-reading spikes " +
                            "using a Kalman filter, applied after calibration. " +
                            "Improves stability for looping without meaningfully " +
                            "delaying real trend changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 06/08/2026 (editor, RONDE 50) — zie kdoc bij de
                    // gelijkaardige regel in de Calibration-kaart hierboven.
                    Text(
                        "If you turn this on, also turn off AAPS's own " +
                            "smoothing (e.g. its Unscented Kalman Filter " +
                            "plugin) — otherwise the same correction is " +
                            "applied twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable smoothing", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = smoothingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setSmoothingEnabled(enabled) }
                            }
                        )
                    }

                    // 18/08/2026 (editor, RONDE 114, op verzoek: "wat we nu
                    // nog niet hebben is een algemene filtering sterkte 3
                    // keuze schakelaar. onder de enable smoothing die dan
                    // indien enable uitgeschakeld ook grijs wordt") — zelfde
                    // SegmentedButton-opzet als AlarmSettingsScreen.kt's
                    // escalatie-/alert-keuzes. `enabled = smoothingEnabled`
                    // op elke SegmentedButton geeft Material3's automatische
                    // uitgrijs-gedrag (geen handmatige alpha nodig zoals bij
                    // de break-in-Text/Slider hieronder, SegmentedButton
                    // grijst zelf al net als Switch dat doet).
                    //
                    // 18/08/2026 (editor, RONDE 114b/c, twee live-meldingen op
                    // rij met screenshot) — 114b: deze rij stond zonder eigen
                    // toelichting direct BOVEN de break-in-filter-tekst,
                    // waardoor die tekst leek te horen bij "Filtering
                    // strength" i.p.v. bij "Break-in filter for new sensors"
                    // eronder — opgelost met een eigen toelichting + een
                    // HorizontalDivider. 114c, op verzoek: "kan volgens mij
                    // nog duidelijker als we de volgorde: Kopje (vet gedrukt),
                    // uitleg en dan switch aanhouden [...] het komt ook door
                    // de eerste woorden 'Filters noisy rise....' dat wekt de
                    // indruk dat het ergens op slaat wat daarvoor al besproken
                    // is" — de kern van het (herhaalde) probleem was dat
                    // Break-in filter/Show-filtered-data hun toelichtende
                    // TEKST vóór hun eigen (vetgedrukte) kopje toonden i.p.v.
                    // erna, waardoor die tekst als vervolg op het VORIGE
                    // blok leek. Alle drie de sub-secties in deze kaart
                    // (Filtering strength/Break-in filter/Show filtered data)
                    // volgen nu consequent dezelfde volgorde: vetgedrukt
                    // kopje -> toelichting -> schakelaar/besturing, elk
                    // gescheiden door een HorizontalDivider. "Enable
                    // smoothing" hierboven blijft bewust in het bestaande
                    // kopje+switch-op-één-regel-patroon (zelfde als "Enable
                    // calibration" in de Calibration-kaart hierboven) — dat
                    // is de kaart-brede hoofdschakelaar, niet een van de drie
                    // sub-features, en heeft al een eigen toelichting via de
                    // algemene kaart-intro bovenaan.
                    Text(
                        "Filtering strength",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (smoothingEnabled) 1.0f else 0.38f)
                    )
                    Text(
                        "How strongly ALL readings are smoothed, all the " +
                            "time — separate from the break-in filter below, " +
                            "which only adds extra damping right after a new " +
                            "sensor starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = if (smoothingEnabled) 1.0f else 0.38f)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SmoothingStrength.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = smoothingStrength == option,
                                enabled = smoothingEnabled,
                                onClick = { scope.launch { settings.setSmoothingStrength(option) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = SmoothingStrength.entries.size)
                            ) {
                                Text(option.displayLabel)
                            }
                        }
                    }

                    HorizontalDivider()

                    // 17/08/2026 (editor, RONDE 111, op verzoek: "Visueel bij
                    // de settings zie ik het onder de knop 'enable smoothing'
                    // in het zelfde kader en als smoothing uit staat beide
                    // uitgegrijsd") — zelfde Card/Column als hierboven, dus
                    // geen aparte Card. `enabled = smoothingEnabled` op de
                    // Switch geeft Material3's automatische uitgrijs-gedrag
                    // (zie AlarmSettingsScreen.kt's idioom); de labels/
                    // Slider hieronder grijzen we zelf bij via een expliciete
                    // content-alpha, aangezien Text/Slider dat niet vanzelf
                    // doen zoals Switch dat wel doet.
                    //
                    // 18/08/2026 (editor, RONDE 114c) — kopje/toelichting/
                    // switch nu in die volgorde, zie de kdoc hierboven bij
                    // "Filtering strength".
                    val breakInDimAlpha = if (smoothingEnabled) 1.0f else 0.38f
                    Text(
                        "Break-in filter for new sensors",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDimAlpha)
                    )
                    Text(
                        "Filters noisy rises more heavily right after a new " +
                            "physical sensor is started, then eases off over " +
                            "the duration below. Only affects rises, not " +
                            "falls — meant to stop break-in noise from " +
                            "falsely triggering SMB/dosing decisions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = breakInDimAlpha)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Switch(
                            checked = breakInFilterEnabled,
                            enabled = smoothingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setBreakInFilterEnabled(enabled) }
                            }
                        )
                    }
                    val breakInDurationInteractive = smoothingEnabled && breakInFilterEnabled
                    val breakInDurationAlpha = if (breakInDurationInteractive) 1.0f else 0.38f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Duration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDurationAlpha)
                        )
                        Text(
                            "${breakInFilterDurationHours.roundToInt()}h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDurationAlpha)
                        )
                    }
                    Slider(
                        value = breakInFilterDurationHours.toFloat().coerceIn(1f, 72f),
                        onValueChange = { newValue ->
                            scope.launch { settings.setBreakInFilterDurationHours(newValue.roundToInt().toDouble()) }
                        },
                        valueRange = 1f..72f,
                        steps = 70,
                        enabled = breakInDurationInteractive
                    )

                    HorizontalDivider()

                    // 24/08/2026 (editor, RONDE 125, op verzoek: "een
                    // breakout filter wat eigenlijk precies omgekeerd werkt
                    // tov de breakin [...] boven op de basis (ongeacht welke
                    // stand gekozen is) en even sterk als break in dus in
                    // principe een omgekeerde kopie" — na CareSens Air-
                    // meldingen dat sensoren de laatste dagen van hun
                    // looptijd weer instabiel worden) — zelfde
                    // kopje/toelichting/switch/duur-opzet als break-in
                    // hierboven. Enige visuele verschil: de duration-Slider
                    // is bewust rechts-naar-links getekend (RTL-
                    // CompositionLocalProvider om ALLEEN de Slider, niet de
                    // labels ernaast) — op uitdrukkelijk verzoek, zodat
                    // "langer maken" ook visueel naar links trekken is, als
                    // duidelijke aanwijzing dat deze duur vanaf het EINDE
                    // terugtelt i.p.v. vanaf het begin optelt zoals break-in.
                    val breakOutDimAlpha = if (smoothingEnabled) 1.0f else 0.38f
                    Text(
                        "Break-out filter for aging sensors",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakOutDimAlpha)
                    )
                    Text(
                        "Mirrors the break-in filter above, but counts down " +
                            "to a sensor's estimated end of life instead of " +
                            "up from its start — filtering builds up over " +
                            "the duration below, right before the estimated " +
                            "end. Filters both rises and suspicious-looking " +
                            "dips (an isolated drop not yet confirmed by " +
                            "further readings); a sustained real decline is " +
                            "never delayed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = breakOutDimAlpha)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Switch(
                            checked = breakOutFilterEnabled,
                            enabled = smoothingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setBreakOutFilterEnabled(enabled) }
                            }
                        )
                    }
                    val breakOutDurationInteractive = smoothingEnabled && breakOutFilterEnabled
                    val breakOutDurationAlpha = if (breakOutDurationInteractive) 1.0f else 0.38f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Duration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakOutDurationAlpha)
                        )
                        Text(
                            "${breakOutFilterDurationHours.roundToInt()}h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakOutDurationAlpha)
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Slider(
                            value = breakOutFilterDurationHours.toFloat().coerceIn(1f, 96f),
                            onValueChange = { newValue ->
                                scope.launch { settings.setBreakOutFilterDurationHours(newValue.roundToInt().toDouble()) }
                            },
                            valueRange = 1f..96f,
                            steps = 94,
                            enabled = breakOutDurationInteractive
                        )
                    }

                    HorizontalDivider()

                    // 18/08/2026 (editor, RONDE 113, op verzoek: "een extra
                    // optie met 'toon gefilterde data op hoofdscherm' [...]
                    // en het zichtbaar er van niet afhankelijk van het effect
                    // te maken" + "Als iemand smoothing uitzet dan moet het
                    // vinkje van het tonen ook gelijk grijs worden en moet
                    // hij uiteraard niet getoond worden") — zelfde
                    // uitgrijs-idioom als de break-in-Switch hierboven:
                    // `enabled = smoothingEnabled` op de Switch zelf,
                    // handmatige alpha op het label ernaast. Bewust géén
                    // eigen `breakInFilterEnabled`-afhankelijkheid: dit geldt
                    // voor de hele smoothing-pijplijn (raw/gekalibreerd/
                    // gefilterd), niet specifiek voor het break-in-filter.
                    //
                    // 18/08/2026 (editor, RONDE 114c) — kopje/toelichting/
                    // switch nu in die volgorde, zie de kdoc hierboven bij
                    // "Filtering strength".
                    Text(
                        "Show filtered data on main screen",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDimAlpha)
                    )
                    Text(
                        "Adds a row below the sensor info on the status " +
                            "screen showing raw, calibrated and filtered " +
                            "values side by side, so you can see exactly " +
                            "what each processing step changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = breakInDimAlpha)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Switch(
                            checked = showFilteredPipelineOnMainScreen,
                            enabled = smoothingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setShowFilteredPipelineOnMainScreen(enabled) }
                            }
                        )
                    }
                }
            }

            // 29/08/2026 (editor, RONDE 160, op verzoek: "een voorspelling
            // van de Bg wil zien waar die het komende uur naar toe kan
            // gaan [...] Aan/uit bij de settings is een goede aanvulling")
            // — zelfde Card-/Switch-opzet als de Smoothing-schakelaar
            // hierboven. Geldt voor de grafiek op ELK per-slot-tabblad EN de
            // Combi-tab (zie GlucoseChart.kt/CombiScreen.kt) — één globale
            // instelling, geen per-slot-keuze, want de gebruiker vroeg dit
            // expliciet "voor de beide slots" tegelijk.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Bg prediction", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shows a 1-hour forecast on the glucose graph: a vertical line at " +
                            "the last reading, and two diverging bounds showing the likely " +
                            "range the Bg could move into. Based only on the recent trend " +
                            "and its volatility (no IOB/meal data is available), so treat it " +
                            "as a rough indication, not a precise prediction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Bg prediction", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = predictionEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setPredictionEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 08/08/2026 (editor, RONDE 57, op verzoek: "is het ook mogelijk
            // om in plaats van tik op opnieuw koppelen de app dat
            // automatisch te laten doen") — geldt voor beide sensoren
            // (CareSens Air + Dexcom G6), zie
            // sensor/ble/BondLossRecovery.kt's kdoc voor het volledige
            // verhaal, inclusief het OS-brede removeBond()-risico.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Automatic re-pair", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "If the phone's Bluetooth pairing with your sensor/transmitter is " +
                            "unexpectedly lost after it worked before (some Android phones do " +
                            "this to other apps' sensors too), FCLGlucoLink will try to " +
                            "silently re-pair instead of waiting for you to reconnect " +
                            "manually. Only acts when a previous successful connection is on " +
                            "record — never on a brand-new, never-yet-paired sensor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        "This removes and re-creates the phone's Bluetooth pairing, which " +
                            "affects the whole phone, not just this app — if another app " +
                            "(e.g. xDrip+) is also paired with the same sensor, its pairing " +
                            "breaks too. Every attempt is written to the diagnostic log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable automatic re-pair", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = bondLossAutoRecoveryEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setBondLossAutoRecoveryEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 29/08/2026 (editor, RONDE 164, op verzoek — "het kunnen kiezen
            // van de virtuele sensor (en ook de andere) onder een expert
            // modus te zetten. Bij de settings komt dan een knop 'expert
            // modus' waarbij alle sensoren staan met een selectie vakje er
            // achter die default op aan staan maar die je ook uit kunt
            // zetten zodat als je in 1 van de slots kiest je alleen de
            // ingestelde/geactiveerde sensoren ziet") — de "knop" is hier een
            // in-/uitklap-schakelaar (i.p.v. een apart navigatiescherm, om
            // geen nieuwe route in FclGlucoLinkNavHost.kt nodig te hebben
            // voor iets dat verder gewoon bij de rest van de instellingen
            // hoort): dichtgeklapt standaard, zodat de meeste gebruikers de
            // testsensor-schakelaars nooit hoeven te zien. Zie
            // ui/SensorSelectionScreen.kt voor waar dit daadwerkelijk
            // gefilterd wordt.
            var expertModeExpanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Expert mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose which sensor types show up in the sensor picker for " +
                            "each slot — e.g. hide the BG simulator (testing) once you " +
                            "no longer need it, so it can't be picked by accident.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(onClick = { expertModeExpanded = !expertModeExpanded }) {
                        Text(if (expertModeExpanded) "Hide sensor visibility settings" else "Show sensor visibility settings")
                    }
                    if (expertModeExpanded) {
                        SensorType.entries.forEach { sensor ->
                            val enabled by settings.isSensorTypeEnabledInPicker(sensor).collectAsState(initial = true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(sensor.displayName, style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch { settings.setSensorTypeEnabledInPicker(sensor, checked) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
