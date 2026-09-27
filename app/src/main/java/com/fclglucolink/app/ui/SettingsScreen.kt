package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.logging.LogUploader
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
 * 06/08/2026 (editor, RONDE 53, op verzoek om de "about"-knop elders te
 * plaatsen, bij voorkeur onder het laatste hoofdstuk in de manual en niet
 * meer bij de settings) — de link naar het About-scherm die hier onderaan
 * stond is destijds verplaatst naar de handleiding.
 *
 * 11/09/2026 (editor, RONDE 174, op verzoek om de update-knop weer terug
 * naar Settings te verplaatsen, samen met een nieuwe "Send log files"-knop,
 * en om de losse diagnose-logging-aan/uit-schakelaar te laten vervallen nu
 * logging altijd aan staat) — die eerdere verplaatsing (RONDE 53/165) is
 * hiermee grotendeels teruggedraaid: [onOpenAbout] is terug als parameter,
 * en een nieuwe "Support"-kaart onderaan dit scherm bevat zowel de link naar
 * het About-scherm (app-info, versie, update-check) als de nieuwe
 * "Send log files"-knop (zie [SendLogFilesDialog]/LogUploader.kt) — de
 * oude "Debug"-kaart met de aan/uit-schakelaar is vervallen, zie
 * DiagnosticFileLogger.kt's kdoc voor waarom logging niet meer uitgezet
 * kan worden. ManualScreen.kt's eigen "About FCLGlucoLink"-rij is in
 * dezelfde ronde verwijderd, zie de kdoc daar.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAlarms: () -> Unit, onOpenAbout: () -> Unit) {
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
    // 25/09/2026 (editor, RONDE 189) — zie ui/Localization.kt's klasse-kdoc:
    // deze knop verandert alleen wélke tekst getoond wordt (via [tr] overal
    // in de app, doorgegeven via FclGlucoLinkNavHost's
    // CompositionLocalProvider) — geen enkele andere instelling of het
    // AAPS-broadcast-gedrag hangt hiervan af.
    val appLanguage by settings.appLanguage.collectAsState(initial = AppLanguage.ENGLISH)
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
    // 17/08/2026 (editor, RONDE 111, op verzoek voor een instelbare filtering
    // die de eerste 2 dagen iets heftiger filtert en dan langzaam afbouwt
    // gedurende de looptijd, met minder gewicht voor dalingen) — zie smoothing/KalmanSmoother.kt's kdoc (het
    // asymmetrische, alleen-bij-stijgingen "break-in filter") en
    // BleConnectionService.kt's computeBreakInDecayFactor() voor hoe deze
    // twee waarden uiteindelijk worden toegepast.
    val breakInFilterEnabled by settings.breakInFilterEnabled.collectAsState(initial = false)
    val breakInFilterDurationHours by settings.breakInFilterDurationHours.collectAsState(initial = 24.0)
    // 24/08/2026 (editor, RONDE 125, op verzoek voor een breakout-filter dat
    // precies omgekeerd werkt t.o.v. het break-in-filter — na CareSens
    // Air-meldingen dat sensoren de laatste dagen van hun looptijd weer
    // instabiel worden) — zie smoothing/KalmanSmoother.kt's klasse-kdoc
    // (RONDE-125-paragraaf) en BleConnectionService.kt's
    // computeBreakOutDecayFactor() voor het volledige mechanisme.
    val breakOutFilterEnabled by settings.breakOutFilterEnabled.collectAsState(initial = false)
    val breakOutFilterDurationHours by settings.breakOutFilterDurationHours.collectAsState(initial = 48.0)
    // 18/08/2026 (editor, RONDE 113, op verzoek om gefilterde data op het
    // hoofdscherm te tonen) — zie AppSettings.kt's kdoc bij Keys.
    // SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN en StatusScreen.kt's
    // SlotStatusContent voor waar dit uiteindelijk gelezen wordt.
    val showFilteredPipelineOnMainScreen by settings.showFilteredPipelineOnMainScreen.collectAsState(initial = false)
    // 08/08/2026 (editor, RONDE 57) — zie sensor/ble/BondLossRecovery.kt's kdoc.
    val bondLossAutoRecoveryEnabled by settings.bondLossAutoRecoveryEnabled.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Settings", "Instellingen")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Terug"))
                    }
                }
            )
        }
    ) { padding ->
        // 06/08/2026 (editor, RONDE 51, na live-melding dat de settingspagina
        // niet scrolt waardoor de laatste regel niet leesbaar is) — deze
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
                    Text(tr("Connection", "Verbinding"), style = MaterialTheme.typography.titleMedium)
                    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur,
                    // op verzoek dat beide slots naar AAPS moeten kunnen
                    // zenden, met maximaal 1 actief tegelijk maar ook beide
                    // uit) — vervangt de oude,
                    // enkelvoudige "Send BG to AAPS"-schakelaar door een
                    // 3-standen-kiezer: Slot A / Slot B / Off, nooit meer dan
                    // één tegelijk (SingleChoiceSegmentedButtonRow dwingt dat
                    // al af). Interim-bediening totdat de echte tab-UI (taak
                    // #311) hier een visuele groen/rood-indicator per tab
                    // van maakt — functioneel al volledig: AppSettings.
                    // aapsActiveSlot is de enige bron van waarheid die
                    // BleConnectionService.kt raadpleegt vóór elke broadcast.
                    Text(
                        tr(
                            "Choose which slot's BG values are sent to AAPS via the " +
                                "xDrip protocol — at most one at a time, or neither. " +
                                "Turn this off (or point it at a different slot) if " +
                                "you're using this slot to test a sensor while a " +
                                "separate xDrip app is the one actually feeding AAPS " +
                                "— otherwise AAPS would receive conflicting values " +
                                "from two sources at once.",
                            "Kies van welk slot de BG-waarden naar AAPS gestuurd " +
                                "worden via het xDrip-protocol — maximaal één " +
                                "tegelijk, of geen van beide. Zet dit uit (of wijs " +
                                "een ander slot aan) als je dit slot gebruikt om een " +
                                "sensor te testen terwijl een aparte xDrip-app AAPS " +
                                "daadwerkelijk voedt — anders krijgt AAPS " +
                                "tegenstrijdige waarden van twee bronnen tegelijk."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(tr("Send BG to AAPS from", "Zend BG naar AAPS vanaf"), style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = aapsActiveSlot == SensorSlot.A,
                            onClick = { scope.launch { settings.setAapsActiveSlot(SensorSlot.A) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text(tr("Slot A", "Slot A")) }
                        SegmentedButton(
                            selected = aapsActiveSlot == SensorSlot.B,
                            onClick = { scope.launch { settings.setAapsActiveSlot(SensorSlot.B) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text(tr("Slot B", "Slot B")) }
                        SegmentedButton(
                            selected = aapsActiveSlot == null,
                            onClick = { scope.launch { settings.setAapsActiveSlot(null) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text(tr("Off", "Uit")) }
                    }

                    HorizontalDivider()

                    // 20/08/2026 (editor, RONDE 115, op verzoek voor een knop
                    // die, indien ingeschakeld, elke sensor (ook virtuele)
                    // een universele code meegeeft die zowel in AAPS 3 als 4
                    // werkt, en anders gewoon de best passende omschrijving
                    // meestuurt) — zie XDripBroadcaster.kt's kdoc bij
                    // sourceInfo() voor de volledige AAPS v3-vs-v4-analyse
                    // die tot "AAPS-Dexcom" als universele waarde leidde.
                    // Zelfde kopje/toelichting/switch-volgorde als de
                    // Smoothing-kaart (RONDE 114c).
                    Text(
                        tr("Universal trusted source code", "Universele vertrouwde broncode"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        tr(
                            "Send every sensor (including the simulator) as a " +
                                "single source description that's trusted for " +
                                "\"SMB Always\" on both AAPS 3 and AAPS 4 — at " +
                                "the cost of AAPS/Nightscout showing a generic " +
                                "Dexcom label instead of the actual sensor. Off " +
                                "sends the best-matching description per sensor " +
                                "instead, which may not enable SMB Always on " +
                                "every AAPS version.",
                            "Stuurt elke sensor (inclusief de simulator) als één " +
                                "brontype-omschrijving die vertrouwd wordt voor " +
                                "\"SMB Always\" op zowel AAPS 3 als AAPS 4 — ten " +
                                "koste van een generieke Dexcom-label in AAPS/" +
                                "Nightscout in plaats van de echte sensor. Uit " +
                                "stuurt in plaats daarvan per sensor de best " +
                                "passende omschrijving, wat mogelijk niet op elke " +
                                "AAPS-versie SMB Always inschakelt."
                        ),
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

            // 13/08/2026 (editor, RONDE 104, Fase 1, op verzoek voor een
            // mg/dL-vs-mmol/L-knop die alleen de UI-weergave van Bg-waarden
            // omschakelt, zonder interne wijzigingen) — zie ui/Units.kt's [GlucoseUnit]-kdoc voor de
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
                    Text(tr("Display", "Weergave"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Which unit BG values are shown in — charts, the status " +
                                "ring, fingerstick/simulator input, and the connection " +
                                "notification. Storage and the AAPS broadcast always " +
                                "stay mg/dL regardless of this setting.",
                            "In welke eenheid BG-waarden getoond worden — grafieken, de " +
                                "statuscirkel, vingerprik-/simulatorinvoer, en de " +
                                "verbindingsmelding. Opslag en de AAPS-uitzending blijven " +
                                "altijd mg/dL, ongeacht deze instelling."
                        ),
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

            // 25/09/2026 (editor, RONDE 189, op verzoek van de gebruiker
            // namens een gezinslid — zie ui/Localization.kt's klasse-kdoc voor het
            // volledige ontwerp) — taalkeuze-kaart, zelfde
            // SegmentedButton-patroon als de Display-kaart hierboven.
            // Bewust GEEN systeemtaal-detectie: een expliciete keuze in de
            // app, onafhankelijk van de eigen taalinstelling van het toestel
            // (zie de kdoc voor de reden).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tr("Language", "Taal"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Which language the app's text is shown in. Technical " +
                                "sensor diagnostics (sensor life, battery voltage, " +
                                "temperature, etc.) stay in English.",
                            "In welke taal de tekst van de app getoond wordt. " +
                                "Technische sensordiagnostiek (sensor life, battery " +
                                "voltage, temperature, e.d.) blijft in het Engels."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = appLanguage == AppLanguage.ENGLISH,
                            onClick = { scope.launch { settings.setAppLanguage(AppLanguage.ENGLISH) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("English") }
                        SegmentedButton(
                            selected = appLanguage == AppLanguage.DUTCH,
                            onClick = { scope.launch { settings.setAppLanguage(AppLanguage.DUTCH) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("Nederlands") }
                    }
                }
            }

            // 13/08/2026 (editor, RONDE 106, Fase 2 stap 1, op verzoek voor
            // één hoofdschakelaar om alle alarmen tegelijk aan/uit te
            // zetten, met daaronder per alarm een eigen aan/uit-schakelaar
            // en instellingen) — bewust een KORT kaartje
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
                    Text(tr("Alarms", "Alarmen"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Low/high glucose alerts, a predictive early warning, and " +
                                "a stale-data alert — each with its own on/off switch, " +
                                "threshold, sound, and vibration, plus one master " +
                                "switch for all of them at once.",
                            "Lage/hoge glucosemeldingen, een voorspellende " +
                                "vroegtijdige waarschuwing, en een verouderde-" +
                                "data-melding — elk met een eigen aan/uit-" +
                                "schakelaar, drempel, geluid en trilling, plus " +
                                "één hoofdschakelaar voor ze allemaal tegelijk."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenAlarms) { Text(tr("Configure alarms", "Alarmen instellen")) }
                    }
                }
            }

            // 11/09/2026 (editor, RONDE 174, op verzoek om de update-knop
            // terug naar Settings te verplaatsen, samen met een nieuwe
            // "Send log files"-knop, en om de losse diagnose-logging-
            // aan/uit-schakelaar te laten vervallen nu logging altijd aan
            // staat) — vervangt de oude "Debug"-kaart hierboven.
            // Diagnostic logging zelf heeft geen schakelaar meer, zie
            // DiagnosticFileLogger.kt's kdoc: altijd aan, met automatische
            // opruiming na 16 dagen (DiagnosticFileLogger.pruneOldLogs(),
            // aangeroepen vanuit FclGlucoLinkApp.kt) zodat de logmap niet
            // ongelimiteerd doorgroeit.
            var showSendLogDialog by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tr("Support", "Ondersteuning"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Connection/scan diagnostics are always written to a " +
                                "text file (one per day, Android/data/" +
                                "com.fclglucolink.app/files/log/) — files older " +
                                "than 16 days are deleted automatically. Use " +
                                "\"Send log files\" below to share one while " +
                                "troubleshooting.",
                            "Verbindings-/scandiagnostiek wordt altijd naar een " +
                                "tekstbestand geschreven (één per dag, Android/" +
                                "data/com.fclglucolink.app/files/log/) — " +
                                "bestanden ouder dan 16 dagen worden automatisch " +
                                "verwijderd. Gebruik \"Logbestanden verzenden\" " +
                                "hieronder om er één te delen bij het oplossen " +
                                "van problemen."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 25/09/2026 (editor, RONDE 188, op verzoek na de
                    // meegestuurde screenshot van een gezinslid van een
                    // Samsung S24 — dit was
                    // taak #180, "About & update"-knop verdwijnt op smalle
                    // schermen) — root cause: deze `Row` gebruikte
                    // `Arrangement.SpaceBetween` met de tekst op zijn
                    // ONBEPERKTE natuurlijke breedte. Zodra de tekst +
                    // knop samen breder zijn dan het scherm, verschuift
                    // Compose de knop simpelweg voorbij de rechterrand
                    // i.p.v. terug te laten wrappen — de knop bestaat dan
                    // nog wel, maar staat onzichtbaar buiten beeld. Fix:
                    // de tekst krijgt `Modifier.weight(1f)` (schikt in het
                    // resterende, gegarandeerd beschikbare deel) plus
                    // `maxLines`/`overflow` (nette afkapping i.p.v.
                    // clipping), zodat de knop zelf altijd zijn volledige,
                    // vaste breedte behoudt en dus altijd zichtbaar blijft.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tr("App info, credits, and software update", "App-info, credits en software-update"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onOpenAbout) { Text(tr("About & update", "Over & update")) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSendLogDialog = true }) { Text(tr("Send log files", "Logbestanden verzenden")) }
                    }
                }
            }

            if (showSendLogDialog) {
                SendLogFilesDialog(onDismiss = { showSendLogDialog = false })
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
                    Text(tr("Calibration", "Kalibratie"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Adjust sensor readings using your own fingerstick " +
                                "values (linear offset or spline fit). When on, a " +
                                "\"Calibration\" button appears on the home screen. " +
                                "Calibration data is cleared automatically whenever " +
                                "you start a new sensor.",
                            "Past sensormetingen aan met je eigen vingerprik-" +
                                "waarden (lineaire offset of spline-fit). Als dit " +
                                "aan staat, verschijnt een \"Kalibratie\"-knop op " +
                                "het hoofdscherm. Kalibratiegegevens worden " +
                                "automatisch gewist zodra je een nieuwe sensor " +
                                "start."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 06/08/2026 (editor, RONDE 50, op verzoek om duidelijk
                    // te vermelden dat calibratie/smoothing in AAPS zelf
                    // uitgeschakeld moet worden als deze functies hier aan
                    // staan) — bewust in de errorkleur i.p.v. de
                    // gewone secondary-kleur hierboven, precies om dit
                    // regeltje visueel te laten opvallen tussen de rest van
                    // de (neutrale) uitleg. Dezelfde boodschap staat
                    // uitgebreider in ManualScreen.kt's WarningCard.
                    Text(
                        tr(
                            "If you turn this on, also turn off AAPS's own " +
                                "calibration — otherwise the same correction is " +
                                "applied twice.",
                            "Als je dit aanzet, zet dan ook AAPS' eigen " +
                                "kalibratie uit — anders wordt dezelfde " +
                                "correctie twee keer toegepast."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Enable calibration", "Kalibratie inschakelen"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = calibrationEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setCalibrationEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 06/08/2026 (editor, RONDE 49, op verzoek om de aan/uit-knop
            // gewoon onder het ⋮-menu te plaatsen) — de "aan/
            // uit"-helft van de smoothing-functie, hier bij de rest van het ⋮-menu, in dezelfde
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
                    Text(tr("Smoothing", "Filtering"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Smooths out sensor noise and single-reading spikes " +
                                "using a Kalman filter, applied after calibration. " +
                                "Improves stability for looping without meaningfully " +
                                "delaying real trend changes.",
                            "Vlakt sensorruis en losse-meting-uitschieters af " +
                                "met een Kalman-filter, toegepast na kalibratie. " +
                                "Verbetert de stabiliteit voor looping zonder " +
                                "echte trendveranderingen merkbaar te vertragen."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 06/08/2026 (editor, RONDE 50) — zie kdoc bij de
                    // gelijkaardige regel in de Calibration-kaart hierboven.
                    Text(
                        tr(
                            "If you turn this on, also turn off AAPS's own " +
                                "smoothing (e.g. its Unscented Kalman Filter " +
                                "plugin) — otherwise the same correction is " +
                                "applied twice.",
                            "Als je dit aanzet, zet dan ook AAPS' eigen " +
                                "filtering uit (bv. de Unscented Kalman Filter-" +
                                "plugin) — anders wordt dezelfde correctie twee " +
                                "keer toegepast."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Enable smoothing", "Filtering inschakelen"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = smoothingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setSmoothingEnabled(enabled) }
                            }
                        )
                    }

                    // 18/08/2026 (editor, RONDE 114, op verzoek voor een
                    // algemene 3-keuze filteringsterkte-schakelaar onder
                    // "Enable smoothing", die meegrijst als smoothing
                    // uitgeschakeld is) — zelfde
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
                    // HorizontalDivider. 114c, op verzoek om de volgorde
                    // kopje (vetgedrukt) -> uitleg -> switch consequent aan
                    // te houden, omdat de openingswoorden van de uitleg
                    // anders de indruk wekten bij het vorige blok te horen —
                    // de kern van het (herhaalde) probleem was dat
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
                        tr("Filtering strength", "Filtersterkte"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (smoothingEnabled) 1.0f else 0.38f)
                    )
                    Text(
                        tr(
                            "How strongly ALL readings are smoothed, all the " +
                                "time — separate from the break-in filter below, " +
                                "which only adds extra damping right after a new " +
                                "sensor starts.",
                            "Hoe sterk ALLE metingen voortdurend gefilterd " +
                                "worden — los van het inloopfilter hieronder, dat " +
                                "alleen extra demping toevoegt vlak na het " +
                                "starten van een nieuwe sensor."
                        ),
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

                    // 17/08/2026 (editor, RONDE 111, op verzoek om dit
                    // visueel onder "Enable smoothing" in hetzelfde kader te
                    // plaatsen, en beide uit te grijzen als smoothing uit
                    // staat) — zelfde Card/Column als hierboven, dus
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
                        tr("Break-in filter for new sensors", "Inloopfilter voor nieuwe sensoren"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDimAlpha)
                    )
                    Text(
                        tr(
                            "Filters noisy rises more heavily right after a new " +
                                "physical sensor is started, then eases off over " +
                                "the duration below. Only affects rises, not " +
                                "falls — meant to stop break-in noise from " +
                                "falsely triggering SMB/dosing decisions.",
                            "Filtert ruizige stijgingen zwaarder vlak na het " +
                                "starten van een nieuwe fysieke sensor, en bouwt " +
                                "dat daarna af over de duur hieronder. Werkt " +
                                "alleen op stijgingen, niet op dalingen — " +
                                "bedoeld om te voorkomen dat inloopruis ten " +
                                "onrechte SMB-/doseerbeslissingen triggert."
                        ),
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
                            tr("Duration", "Duur"),
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

                    // 24/08/2026 (editor, RONDE 125, op verzoek voor een
                    // breakout-filter dat precies omgekeerd werkt t.o.v. het
                    // break-in-filter — bovenop de gekozen basisinstelling,
                    // even sterk, in feite een omgekeerde kopie — na CareSens Air-
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
                        tr("Break-out filter for aging sensors", "Uitloopfilter voor verouderende sensoren"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakOutDimAlpha)
                    )
                    Text(
                        tr(
                            "Mirrors the break-in filter above, but counts down " +
                                "to a sensor's estimated end of life instead of " +
                                "up from its start — filtering builds up over " +
                                "the duration below, right before the estimated " +
                                "end. Filters both rises and suspicious-looking " +
                                "dips (an isolated drop not yet confirmed by " +
                                "further readings); a sustained real decline is " +
                                "never delayed.",
                            "Spiegelbeeld van het inloopfilter hierboven, maar " +
                                "telt af naar het geschatte einde van de " +
                                "looptijd van een sensor in plaats van op te " +
                                "tellen vanaf de start — filtering bouwt op " +
                                "over de duur hieronder, vlak voor het " +
                                "geschatte einde. Filtert zowel stijgingen als " +
                                "verdacht uitziende dips (een geïsoleerde daling " +
                                "die nog niet bevestigd is door verdere " +
                                "metingen); een aanhoudende echte daling wordt " +
                                "nooit vertraagd."
                        ),
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
                            tr("Duration", "Duur"),
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

                    // 18/08/2026 (editor, RONDE 113, op verzoek voor een
                    // extra optie "toon gefilterde data op hoofdscherm", die
                    // meegrijst en uitgeschakeld wordt zodra smoothing uit
                    // staat) — zelfde
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
                        tr("Show filtered data on main screen", "Toon gefilterde data op hoofdscherm"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = breakInDimAlpha)
                    )
                    Text(
                        tr(
                            "Adds a row below the sensor info on the status " +
                                "screen showing raw, calibrated and filtered " +
                                "values side by side, so you can see exactly " +
                                "what each processing step changed.",
                            "Voegt een regel onder de sensorinfo op het " +
                                "statusscherm toe die ruwe, gekalibreerde en " +
                                "gefilterde waarden naast elkaar toont, zodat je " +
                                "precies ziet wat elke verwerkingsstap " +
                                "veranderde."
                        ),
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

            // 29/08/2026 (editor, RONDE 160, op verzoek voor een voorspelling
            // van waar de Bg het komende uur naartoe kan gaan, met een
            // aan/uit-schakelaar bij de settings als goede aanvulling)
            // — zelfde Card-/Switch-opzet als de Smoothing-schakelaar
            // hierboven. Geldt voor de grafiek op ELK per-slot-tabblad EN de
            // Combi-tab (zie GlucoseChart.kt/CombiScreen.kt) — één globale
            // instelling, geen per-slot-keuze, als aanvulling voor beide
            // slots tegelijk.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tr("Bg prediction", "Bg-voorspelling"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Shows a 1-hour forecast on the glucose graph: a vertical line at " +
                                "the last reading, and two diverging bounds showing the likely " +
                                "range the Bg could move into. Based only on the recent trend " +
                                "and its volatility (no IOB/meal data is available), so treat it " +
                                "as a rough indication, not a precise prediction.",
                            "Toont een 1-uursvoorspelling op de glucosegrafiek: " +
                                "een verticale lijn bij de laatste meting, en " +
                                "twee uiteenlopende grenzen die het " +
                                "waarschijnlijke bereik tonen waarin de Bg zich " +
                                "kan bewegen. Uitsluitend gebaseerd op de " +
                                "recente trend en zijn volatiliteit (geen IOB-/" +
                                "maaltijdgegevens beschikbaar), dus behandel het " +
                                "als een ruwe indicatie, niet als een precieze " +
                                "voorspelling."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Show Bg prediction", "Toon Bg-voorspelling"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = predictionEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setPredictionEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 08/08/2026 (editor, RONDE 57, op verzoek om, in plaats van
            // handmatig opnieuw koppelen, de app dit automatisch te laten
            // doen) — geldt voor beide sensoren
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
                    Text(tr("Automatic re-pair", "Automatisch opnieuw koppelen"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "If the phone's Bluetooth pairing with your sensor/transmitter is " +
                                "unexpectedly lost after it worked before (some Android phones do " +
                                "this to other apps' sensors too), FCLGlucoLink will try to " +
                                "silently re-pair instead of waiting for you to reconnect " +
                                "manually. Only acts when a previous successful connection is on " +
                                "record — never on a brand-new, never-yet-paired sensor.",
                            "Als de Bluetooth-koppeling van de telefoon met je " +
                                "sensor/transmitter onverwacht verdwijnt nadat " +
                                "die eerder werkte (sommige Android-toestellen " +
                                "doen dit ook bij sensoren van andere apps), " +
                                "probeert FCLGlucoLink stilletjes opnieuw te " +
                                "koppelen in plaats van te wachten tot jij " +
                                "handmatig opnieuw verbindt. Grijpt alleen in " +
                                "als er een eerdere geslaagde verbinding " +
                                "geregistreerd staat — nooit bij een " +
                                "gloednieuwe, nog nooit gekoppelde sensor."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        tr(
                            "This removes and re-creates the phone's Bluetooth pairing, which " +
                                "affects the whole phone, not just this app — if another app " +
                                "(e.g. xDrip+) is also paired with the same sensor, its pairing " +
                                "breaks too. Every attempt is written to the diagnostic log.",
                            "Dit verwijdert en herbouwt de Bluetooth-koppeling " +
                                "van de telefoon, wat de hele telefoon " +
                                "beïnvloedt, niet alleen deze app — als een " +
                                "andere app (bv. xDrip+) ook gekoppeld is met " +
                                "dezelfde sensor, breekt die koppeling ook. " +
                                "Elke poging wordt in het diagnostieklogboek " +
                                "vastgelegd."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Enable automatic re-pair", "Automatisch opnieuw koppelen inschakelen"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = bondLossAutoRecoveryEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setBondLossAutoRecoveryEnabled(enabled) }
                            }
                        )
                    }
                }
            }

            // 29/08/2026 (editor, RONDE 164, op verzoek om het kiezen van de
            // virtuele sensor (en de andere) onder een expert-modus te
            // zetten: een "expert modus"-knop bij de settings met alle
            // sensoren en een selectievakje per stuk, standaard aan maar
            // uitschakelbaar, zodat bij het kiezen per slot alleen de
            // ingestelde/geactiveerde sensoren zichtbaar zijn) — de "knop" is hier een
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
                    Text(tr("Expert mode", "Expertmodus"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(
                            "Choose which sensor types show up in the sensor picker for " +
                                "each slot — e.g. hide the BG simulator (testing) once you " +
                                "no longer need it, so it can't be picked by accident.",
                            "Kies welke sensortypes verschijnen in de " +
                                "sensorkeuze per slot — bv. verberg de BG-" +
                                "simulator (testen) zodra je die niet meer " +
                                "nodig hebt, zodat hij niet per ongeluk gekozen " +
                                "kan worden."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(onClick = { expertModeExpanded = !expertModeExpanded }) {
                        Text(
                            if (expertModeExpanded) tr("Hide sensor visibility settings", "Sensorzichtbaarheid verbergen")
                            else tr("Show sensor visibility settings", "Sensorzichtbaarheid tonen")
                        )
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

/**
 * 11/09/2026 (editor, RONDE 174) — dialoog voor SettingsScreen.kt's "Send
 * log files"-knop: laat de gebruiker zelf bestaande dag-logbestanden
 * aanvinken en uploaden (zie DiagnosticFileLogger.listLogFiles()/
 * LogUploader.kt). Bewust een simpele AlertDialog met een vinklijst, zelfde
 * patroon als AboutScreen.kt's "What's new"-dialoog (heightIn +
 * verticalScroll i.p.v. een apart navigatiescherm) — dit hoort bij dit ene
 * scherm, geen eigen route nodig. Uploadt de aangevinkte bestanden één voor
 * één (i.p.v. gelijktijdig): simpeler statusbeheer per bestand, en de
 * Apps Script-kant verwerkt toch maar één upload tegelijk zinvol.
 */
@Composable
private fun SendLogFilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logFiles = remember { DiagnosticFileLogger.listLogFiles() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val statusByFile = remember { mutableStateMapOf<String, String>() }
    var isUploading by remember { mutableStateOf(false) }
    // 25/09/2026 (editor, RONDE 189) — de statusregels hieronder worden
    // gezet vanuit een `scope.launch { ... }`-coroutine, geen @Composable-
    // context, dus [tr] (een @Composable-functie, zie ui/Localization.kt)
    // kan daar niet rechtstreeks aangeroepen worden. `language` wordt hier,
    // WEL in @Composable-context, één keer gelezen; `trNow` is een gewone
    // (niet-@Composable) lokale functie die die vastgelegde waarde gebruikt
    // — overal in deze functie inzetbaar, ook binnen `scope.launch { ... }`.
    val language = LocalAppLanguage.current
    fun trNow(en: String, nl: String): String = if (language == AppLanguage.DUTCH) nl else en

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(trNow("Close", "Sluiten")) }
        },
        title = { Text(trNow("Send log files", "Logbestanden verzenden")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (logFiles.isEmpty()) {
                    Text(
                        trNow("No log files found yet.", "Nog geen logbestanden gevonden."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        trNow(
                            "Pick one or more files, then tap \"Upload selected\".",
                            "Kies één of meer bestanden en tik dan op \"Geselecteerde uploaden\"."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    logFiles.forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected[file.name] ?: false,
                                enabled = !isUploading,
                                onCheckedChange = { checked -> selected[file.name] = checked }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                val sizeKb = (file.length() / 1024).coerceAtLeast(1)
                                Text(
                                    statusByFile[file.name] ?: trNow("$sizeKb KB", "$sizeKb kB"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        TextButton(
                            enabled = !isUploading && selected.any { it.value },
                            onClick = {
                                val toUpload = logFiles.filter { selected[it.name] == true }
                                isUploading = true
                                scope.launch {
                                    for (file in toUpload) {
                                        statusByFile[file.name] = trNow("Uploading…", "Uploaden…")
                                        when (val result = LogUploader.upload(context, file)) {
                                            is LogUploader.UploadResult.Success ->
                                                statusByFile[file.name] = trNow("Sent as ${result.fileName}", "Verzonden als ${result.fileName}")
                                            is LogUploader.UploadResult.Error ->
                                                statusByFile[file.name] = trNow("Failed: ${result.message}", "Mislukt: ${result.message}")
                                            LogUploader.UploadResult.NotConfigured ->
                                                statusByFile[file.name] = trNow("Sending logs isn't set up yet.", "Logbestanden verzenden is nog niet ingesteld.")
                                        }
                                    }
                                    isUploading = false
                                }
                            }
                        ) {
                            Text(trNow("Upload selected", "Geselecteerde uploaden"))
                        }
                    }
                }
            }
        }
    )
}
