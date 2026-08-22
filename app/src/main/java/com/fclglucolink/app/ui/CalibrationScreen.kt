package com.fclglucolink.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fclglucolink.app.calibration.CalibrationEntry
import com.fclglucolink.app.calibration.CalibrationEntryOutcome
import com.fclglucolink.app.calibration.CalibrationFit
import com.fclglucolink.app.calibration.CalibrationMode
import com.fclglucolink.app.calibration.CalibrationStore
import com.fclglucolink.app.calibration.FingerstickListEntry
import com.fclglucolink.app.calibration.SplineFit
import com.fclglucolink.app.calibration.evaluateNewCalibrationEntry
import com.fclglucolink.app.calibration.fitLinearCalibration
import com.fclglucolink.app.calibration.fitSplineCalibration
import com.fclglucolink.app.calibration.weightFor
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ============================================================================
 * FCLGlucoLink — kalibratiescherm (ronde 43)
 * ============================================================================
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek, "vergelijkbaar met het
 * screenshot" van AAPS's eigen spline-kalibratiescherm) — bewust GEEN
 * ViewModel-laag (dit project gebruikt die nergens, zie SettingsScreen.kt/
 * SimulatorSetupScreen.kt: gewoon `remember`/`collectAsState`/`scope.launch`
 * rechtstreeks in de Composable) — en bewust ÉÉN scherm voor zowel lineair
 * als spline (geen twee losse schermen/plugins), met een modus-schakelaar
 * bovenin die precies bepaalt welke van CalibrationEngine's twee paden
 * gebruikt wordt. Geen "Log sensor change"-knop (op expliciet verzoek).
 *
 * 11/08/2026 (editor, RONDE 90, CORRECTIE op de kdoc-zin hierboven die tot
 * deze ronde nog stond) — de kalibratiedata van de vorige sensor wordt NIET
 * meer automatisch GEWIST bij een nieuwe sensor-sessie (CalibrationStore.
 * clearAll() wordt sindsdien nergens meer automatisch aangeroepen, zie dat
 * bestand's eigen Ronde-90-kdoc) — in plaats daarvan wordt oudere data
 * simpelweg niet meer OPGEHAALD voor de nieuwe sessie, via [sinceMs]
 * hieronder (blijft intact voor eventuele toekomstige historie/de andere
 * slot). 22/08/2026 (editor, RONDE 122) — zie
 * AppSettings.effectiveSensorSessionStartedAtMs()'s kdoc voor een kritieke
 * fix van [sinceMs] zelf: tot die ronde gebruikte dit scherm de generieke,
 * NOOIT-per-fysieke-sensor-herziene getOrInitSensorStartedAtMs(), waardoor
 * vingerprikken van een VORIGE fysieke sensor (van hetzelfde type) na een
 * nieuwe-sensor-start gewoon bleven meetellen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit, slot: SensorSlot = SensorSlot.A) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val calibrationStore = remember { CalibrationStore(context) }
    val readingStore = remember { GlucoseReadingStore(context) }
    val scope = rememberCoroutineScope()

    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — kalibratie is
    // nu VERPLICHT gescoped op het SensorType dat in [slot] draait (zie
    // CalibrationStore.kt's kdoc) — zonder gekozen sensor is er simpelweg
    // niets om te kalibreren, vandaar de lege-lijst-fallback hieronder i.p.v.
    // een crash/early-return (Composables mogen niet voorwaardelijk stoppen
    // vóór andere hooks/state, zie de rest van deze functie).
    val sensorType by settings.selectedSensor(slot).collectAsState(initial = null)

    // 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-database) — "Bij de
    // sensoren moeten uiteraard alleen die vingerprikken getoond worden die
    // kwa tijd na de sensor start liggen" — deze sessie's eigen sensor-
    // start-tijd, opgehaald zodra [sensorType] verandert (dus ook bij het
    // eerste tekenen van dit scherm).
    //
    // 22/08/2026 (editor, RONDE 122, CRITICAL FIX — zie
    // AppSettings.effectiveSensorSessionStartedAtMs()'s kdoc) — was
    // `settings.getOrInitSensorStartedAtMs(slot)`: de generieke sleutel die
    // alleen bij een sensor-TYPE-wissel gewist wordt, niet bij een nieuwe
    // FYSIEKE sensor van hetzelfde type. `effectiveSensorSessionStartedAtMs`
    // geeft voorrang aan de sensortype-specifieke, wél-per-fysieke-sensor
    // herziene waarde (Dexcom G6/CareSens Air), en valt alleen terug op de
    // generieke sleutel voor types zonder eigen tracking — nog steeds veilig
    // als suspend-aanroep, om dezelfde reden als voorheen (dit scherm is
    // alleen bereikbaar vanuit een tab MET een actieve sensor).
    var sinceMs by remember { mutableStateOf(0L) }
    LaunchedEffect(sensorType) {
        sinceMs = sensorType?.let { settings.effectiveSensorSessionStartedAtMs(slot, it) } ?: 0L
    }

    // remember() geeft één stabiele Flow-referentie i.p.v. bij elke
    // recompositie een nieuwe (calibrationStore.entries() is een
    // functie-aanroep, geen property) — voorkomt onnodige her-collects.
    //
    // 11/08/2026 (editor, RONDE 90) — [entries] blijft de AANGEVINKTE
    // deelverzameling (voedt de fit-wiskunde/grafiek, ongewijzigde vorm);
    // [listEntries] hieronder is nieuw en voedt de rijlijst met
    // aan/uitvinkjes (toont ELKE relevante rij, aangevinkt of niet).
    val entriesFlow = remember(calibrationStore, sensorType, sinceMs) {
        sensorType?.let { calibrationStore.entries(it, sinceMs) } ?: flowOf(emptyList())
    }
    val entries by entriesFlow.collectAsState(initial = emptyList())

    val listEntriesFlow = remember(calibrationStore, sensorType, sinceMs) {
        sensorType?.let { calibrationStore.listEntries(it, sinceMs) } ?: flowOf(emptyList())
    }
    val listEntries by listEntriesFlow.collectAsState(initial = emptyList())
    // 10/08/2026 (editor, RONDE 80, op verzoek na live-test — "die [offset]
    // wordt dan gelijk bij zowel slot a als b gebruikt") — mode/offset zijn
    // nu per-slot (zie AppSettings.kt's kdoc), i.p.v. de oude globale
    // settings.calibrationMode/calibrationManualOffsetMmol properties.
    val mode by settings.calibrationMode(slot).collectAsState(initial = CalibrationMode.SPLINE)
    val manualOffsetMmol by settings.calibrationManualOffsetMmol(slot).collectAsState(initial = 0.0)
    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
    // [GlucoseUnit]-kdoc. In Ronde 104 alleen gebruikt voor het invoerveld/
    // de rijlijst (AddCalibrationDialog/CalibrationEntryRow); in RONDE 105
    // uitgebreid naar de rest van dit scherm — OffsetSlider,
    // CalibrationScatterChart's as-labels, StatusCard's spline-melding, en
    // de delta-validatiemeldingen hieronder tonen nu ook allemaal de
    // gekozen eenheid. De OPSLAG blijft overal ongewijzigd mg/dL resp.
    // mmol/L (manualOffsetMmol, CalibrationValidation.kt's drempels) — zie
    // de kdoc's bij OffsetSlider/formatRatePer5Min voor waarom dat
    // bewust NIET meeverandert. README Ronde 104/105 voor de volledige
    // scope-afweging.
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)
    // 06/08/2026 (editor, RONDE 47, op verzoek: "als ik een calibratie
    // toevoeg dat in het invul scherm alsvast de sensor waarde is
    // ingevuld") — de actuele ruwe sensorwaarde, voor het invoerscherm
    // hieronder. Zelfde `remember()`-reden als entriesFlow hierboven
    // (latestReading() is ook een functie-aanroep, geen property).
    //
    // 10/08/2026 (editor, RONDE 80, BUGFIX na live-melding — "de delta was
    // -0,3 en hij gaf aan 4,6") — was ONGEFILTERD (`readingStore.
    // latestReading()`), dus de gecombineerde stream van BEIDE slots — zie
    // StatusScreen.kt's SlotStatusContent() kdoc voor de volledige uitleg
    // van dit type bug. Met Slot A (Dexcom, ~8,8 mmol/L) en Slot B
    // (simulator, ~6,8 mmol/L) allebei gelijktijdig actief pakte het
    // invoerscherm hier soms de LAATSTE binnengekomen meting van de ANDERE
    // slot als startwaarde, en zag de delta-validatie hieronder (regel 297)
    // hetzelfde mengsel — geen mg/dl-vs-mmol-eenheidsbug (CalibrationValidation.kt's
    // wiskunde is nagerekend en correct), gewoon twee sensoren door elkaar.
    //
    // 10/08/2026 (editor, RONDE 81, tweede BUGFIX, live-melding — "als een
    // slot op geen sensor wordt gezet" toonde dit scherm alsnog data) —
    // `sensorType = sensorType` loste de EERSTE fix hierboven op (twee
    // gekozen sensoren door elkaar), maar liet een tweede, verwante staart
    // over: als `sensorType` zelf `null` is (deze slot heeft nog GEEN sensor
    // gekozen), behandelt GlucoseReadingStore `sensorType = null` als "geen
    // filter" — dus weer de VOLLE, gecombineerde stream van beide slots
    // i.p.v. terecht niets. Nu expliciet: alleen bij een echt gekozen
    // sensorType een query doen, anders een vaste lege/`null`-flow.
    val latestReadingFlow = remember(readingStore, sensorType) {
        sensorType?.let { readingStore.latestReading(sensorType = it) } ?: flowOf(null)
    }
    val latestReading by latestReadingFlow.collectAsState(initial = null)

    var selectedEntryId by remember { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    // 06/08/2026 (editor, RONDE 47) — vastgezet op het moment dat de dialoog
    // OPENT (in de FAB's onClick hieronder), niet reactief herberekend
    // terwijl de dialoog al open staat — anders zou het startpunt onder de
    // vingers van de gebruiker kunnen verspringen als er precies dan een
    // nieuwe meting binnenkomt.
    // 13/08/2026 (editor, RONDE 104) — mg/dL i.p.v. mmol/L (99 mg/dL ≈ het
    // oude default van 5,5 mmol/L), zie AddCalibrationDialog()'s kdoc.
    var addDialogInitialMgdl by remember { mutableStateOf(99.0) }
    var addWarningText by remember { mutableStateOf<String?>(null) }
    var addErrorText by remember { mutableStateOf<String?>(null) }

    // Houd de selectie geldig — als de geselecteerde entry verwijderd is,
    // val terug op de meest recente (zelfde gedrag als AAPS's
    // SplineCalibrationViewModel.recomputeSuspend()).
    // 11/08/2026 (editor, RONDE 90) — tegen [listEntries] (ALLE relevante
    // rijen) i.p.v. alleen de aangevinkte [entries] — anders zou het
    // selecteren van een uitgevinkte rij in de lijst hieronder de selectie
    // meteen weer terugzetten naar de laatste aangevinkte.
    LaunchedEffect(listEntries) {
        if (selectedEntryId == null || listEntries.none { it.id == selectedEntryId }) {
            selectedEntryId = listEntries.lastOrNull()?.id
        }
    }

    val now = System.currentTimeMillis()
    val linearFit = remember(entries, now) { fitLinearCalibration(entries, now) }
    val splineResult = remember(entries, now, mode) {
        if (mode == CalibrationMode.SPLINE) fitSplineCalibration(entries, now) else null
    }
    val splineFit = splineResult?.fit

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // 06/08/2026 (editor, RONDE 51, na live-melding: "de add
                // calibration knop staat over de rij met vingerprik entries
                // heen [...] die knop kan beter boven in achter de terug
                // knop") — was een Scaffold-`floatingActionButton` (vast
                // rechtsonder): de LazyColumn hieronder krijgt GEEN eigen
                // padding om ruimte voor zo'n FAB vrij te houden, dus zodra
                // de lijst lang genoeg was om de onderkant te raken, viel de
                // laatste rij letterlijk ONDER de FAB — precies het
                // gerapporteerde symptoom. Nu een gewone actie-knop in de
                // TopAppBar zelf (naast/na de terug-pijl, standaard
                // actions-plek rechtsboven) — daar is nooit scroll-inhoud
                // overheen te leggen, dus dit soort overlap kan hier
                // structureel niet meer voorkomen.
                actions = {
                    IconButton(onClick = {
                        addWarningText = null
                        addErrorText = null
                        // 06/08/2026 (editor, RONDE 47) — startpunt van de
                        // dialoog: de actuele ruwe sensorwaarde (mmol), of de
                        // vorige waarde als er nog nooit een meting was — zie
                        // addDialogInitialMgdl's kdoc hierboven.
                        addDialogInitialMgdl = latestReading?.rawSensorMgdl ?: addDialogInitialMgdl
                        showAddDialog = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add calibration")
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
            StatusCard(
                mode = mode,
                entries = entries,
                linearFit = linearFit,
                splineFit = splineFit,
                unit = displayUnit,
                splineFailureReason = splineResult?.reason
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == CalibrationMode.LINEAR,
                    onClick = { scope.launch { settings.setCalibrationMode(slot, CalibrationMode.LINEAR) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Linear") }
                SegmentedButton(
                    selected = mode == CalibrationMode.SPLINE,
                    onClick = { scope.launch { settings.setCalibrationMode(slot, CalibrationMode.SPLINE) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Spline") }
            }

            // 06/08/2026 (editor, RONDE 44, op verzoek: "de grafiek mag iets
            // kleiner zodat de lijst met waarden eronder iets groter kan
            // worden") — was 260.dp; nu 190.dp, samen met de nieuwe
            // .weight(1f) hieronder op de LazyColumn (die er voorheen niet
            // was — zonder weight kreeg de lijst gewoon zoveel ruimte als
            // 'm content nodig had, niet de daadwerkelijk resterende ruimte,
            // dus werd 'm nooit groter dan zijn eigen inhoud én kon 'm bij
            // veel entries buiten beeld vallen zonder zelf te scrollen).
            CalibrationScatterChart(
                entries = entries,
                selectedEntryId = selectedEntryId,
                mode = mode,
                linearFit = linearFit,
                splineFit = splineFit,
                manualOffsetMgdl = manualOffsetMmol.mmolToMgdl(),
                now = now,
                unit = displayUnit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            )

            OffsetSlider(
                manualOffsetMmol = manualOffsetMmol,
                unit = displayUnit,
                onChange = { value -> scope.launch { settings.setCalibrationManualOffsetMmol(slot, value) } }
            )

            // 11/08/2026 (editor, RONDE 90) — telt nu ALLE relevante rijen
            // (aangevinkt of niet), zie [listEntries]'s kdoc.
            Text("Fingerstick entries (${listEntries.size})", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(listEntries.reversed(), key = { it.id }) { entry ->
                    // 06/08/2026 (editor, RONDE 44, op verzoek: "in de lijst
                    // wil ik naast de stick en sensor waarde ook de
                    // gekalibreerde waarde kunnen lezen") — dezelfde
                    // curve-selectielogica als de grafiek hieronder
                    // (activeCalibratedMgdl(), gedeeld zodat de grafieklijn
                    // en deze kolom nooit uit elkaar kunnen lopen), toegepast
                    // op de RUWE sensorwaarde die bij deze entry hoort.
                    val calibratedMgdl = activeCalibratedMgdl(
                        sensorMgdl = entry.sensorMgdlAtPairing,
                        mode = mode,
                        linearFit = linearFit,
                        splineFit = splineFit,
                        manualOffsetMgdl = manualOffsetMmol.mmolToMgdl()
                    )
                    CalibrationEntryRow(
                        entry = entry,
                        calibratedMgdl = calibratedMgdl,
                        selected = entry.id == selectedEntryId,
                        unit = displayUnit,
                        onSelect = { selectedEntryId = entry.id },
                        onToggleChecked = { checked ->
                            scope.launch {
                                calibrationStore.setIncluded(entry.id, isOrigin = entry.enteredOnThisSensor, included = checked)
                            }
                        },
                        onDelete = { scope.launch { calibrationStore.delete(entry.id) } }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddCalibrationDialog(
            initialMgdl = addDialogInitialMgdl,
            unit = displayUnit,
            onDismiss = { showAddDialog = false },
            onConfirm = { fingerstickMgdl ->
                scope.launch {
                    val since = System.currentTimeMillis() - 15L * 60_000L
                    // 10/08/2026 (editor, RONDE 80, BUGFIX — zelfde bugklasse
                    // als latestReadingFlow hierboven) — was ongefilterd,
                    // moet gescoped zijn op deze slot's eigen sensor, anders
                    // wordt de delta-validatie hieronder gevoed met een
                    // mengsel van twee sensoren's onafhankelijke waarden.
                    //
                    // 10/08/2026 (editor, RONDE 81, tweede BUGFIX) — zelfde
                    // null-staart als latestReadingFlow hierboven: `sensorType
                    // = sensorType` loste het EERSTE mengsel-probleem op, maar
                    // als [sensorType] zelf `null` is (geen sensor gekozen
                    // voor deze slot) behandelde GlucoseReadingStore dat nog
                    // steeds als "geen filter" — nu expliciet leeg bij een
                    // niet-gekozen sensor (kan in de praktijk sowieso niet
                    // gebeuren dat de dialoog hier open staat zonder gekozen
                    // sensor, zie de sensorType?.let hieronder bij Accepted,
                    // maar defensief consistent met latestReadingFlow).
                    val recentRaw = sensorType?.let { type ->
                        readingStore.recentReadings(hours = 1, sensorType = type).first()
                    }.orEmpty()
                        .filter { it.timestampMs >= since }
                        .map { it.timestampMs to it.rawSensorMgdl }
                    // 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-
                    // database) — de op dit moment gelijktijdig actieve
                    // ANDERE slot's sensortype + zijn ruwe sensorwaarde op
                    // hetzelfde tijdstip, opportunistisch meegevangen zodat
                    // deze vingerprik later ook voor DIE sensor aangevinkt
                    // kan worden (zie CalibrationStore.kt's klasse-kdoc).
                    // Bewust GEEN eigen delta/pairing-validatie hier — dat
                    // blijft voorbehouden aan de herkomst-sensor
                    // (evaluateNewCalibrationEntry hieronder); de andere
                    // sensor is puur optioneel/aanvullend, de gebruiker vinkt
                    // 'm zelf pas later expliciet aan.
                    val otherSlot = if (slot == SensorSlot.A) SensorSlot.B else SensorSlot.A
                    val otherSensorType = settings.selectedSensor(otherSlot).first()
                    val otherRecentRaw = otherSensorType?.let { type ->
                        readingStore.recentReadings(hours = 1, sensorType = type).first()
                    }.orEmpty()
                        .filter { it.timestampMs >= since }
                        .maxByOrNull { it.timestampMs }
                        ?.rawSensorMgdl
                    val outcome = evaluateNewCalibrationEntry(
                        now = System.currentTimeMillis(),
                        recentRawReadings = recentRaw,
                        activeFit = splineFit?.linearFallback ?: linearFit
                    )
                    when (outcome) {
                        is CalibrationEntryOutcome.Accepted -> {
                            // 10/08/2026 (editor, RONDE 79) — geen sensor gekozen voor
                            // deze slot -> er is niets om aan te koppelen, negeer stil
                            // (kan in de praktijk niet gebeuren: dit scherm is alleen
                            // bereikbaar vanuit een tab met een actieve sensor).
                            sensorType?.let { type ->
                                calibrationStore.add(
                                    CalibrationEntry(
                                        timestampMs = System.currentTimeMillis(),
                                        fingerstickMgdl = fingerstickMgdl,
                                        sensorMgdlAtPairing = outcome.sensorMgdlAtPairing
                                    ),
                                    sensorType = type,
                                    otherSensorType = otherSensorType,
                                    otherSensorMgdlAtPairing = otherRecentRaw
                                )
                            }
                            showAddDialog = false
                            addWarningText = outcome.warningMmolPer5Min?.let {
                                "Added, but BG was changing quickly at the time (${formatRatePer5Min(it, displayUnit)}) — this point may be less reliable."
                            }
                        }
                        CalibrationEntryOutcome.RejectedNoRecentReading -> {
                            addErrorText = "No sensor reading in the last 10 minutes to pair this with — try again once a fresh value has come in."
                        }
                        is CalibrationEntryOutcome.RejectedDeltaTooHigh -> {
                            addErrorText = "BG is changing too quickly right now (${formatRatePer5Min(outcome.deltaMmolPer5Min, displayUnit)}, limit ${formatRatePer5Min(outcome.thresholdMmolPer5Min, displayUnit)}) — wait until it settles."
                        }
                    }
                }
            },
            errorText = addErrorText
        )
    }
}

@Composable
private fun StatusCard(
    mode: CalibrationMode,
    entries: List<CalibrationEntry>,
    linearFit: com.fclglucolink.app.calibration.CalibrationFit?,
    splineFit: SplineFit?,
    splineFailureReason: com.fclglucolink.app.calibration.SplineFailureReason?,
    // 13/08/2026 (editor, RONDE 105, op verzoek: "de calibratie moet
    // uiteraard ook de waarden op het scherm in mg/dl weergeven") — de
    // "knot at 6.0 mmol/L"-tekst is een vaste, niet-instelbare parameter van
    // SplineCalibrationMath.kt (het knikpunt van de spline-fit), maar de
    // MELDING zelf hoort net als al het andere op-het-scherm-getal de
    // gekozen weergave-eenheid te volgen — 6.0 mmol/L is hardcoded hier
    // omdat het de spline-wiskunde zelf is (die blijft ongewijzigd), alleen
    // de tekst zet 'm nu om naar mg/dL indien gekozen.
    unit: GlucoseUnit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val splineKnotMmol = 6.0
            val statusText = when {
                entries.isEmpty() -> "No calibration entries yet — only the manual offset (if any) is applied."
                mode == CalibrationMode.SPLINE && splineFit != null ->
                    "Spline calibration applied (knot at ${splineKnotMmol.mmolToMgdl().formatForDisplayWithUnit(unit)})."
                mode == CalibrationMode.SPLINE && splineFit == null ->
                    "Spline not available yet (${splineFailureReason?.let { reasonText(it) } ?: "not enough data"}) — using linear."
                linearFit != null && linearFit.isApplicable -> "Linear calibration applied."
                else -> "Not enough data for a reliable fit yet — using the manual offset only."
            }
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun reasonText(reason: com.fclglucolink.app.calibration.SplineFailureReason): String = when (reason) {
    com.fclglucolink.app.calibration.SplineFailureReason.TOO_FEW_ENTRIES -> "need at least 4 entries"
    com.fclglucolink.app.calibration.SplineFailureReason.TOO_FEW_LOW_SEGMENT -> "need more low-range entries"
    com.fclglucolink.app.calibration.SplineFailureReason.TOO_FEW_HIGH_SEGMENT -> "need more high-range entries"
    com.fclglucolink.app.calibration.SplineFailureReason.SLOPE_OUT_OF_RANGE -> "segment slope out of range"
    com.fclglucolink.app.calibration.SplineFailureReason.SEGMENTS_TOO_CLOSE -> "entries too close together"
    com.fclglucolink.app.calibration.SplineFailureReason.NOT_MONOTONE -> "curve would not be monotone"
}

// 13/08/2026 (editor, RONDE 105) — de OPSLAG (manualOffsetMmol, via
// settings.calibrationManualOffsetMmol) blijft bewust mmol-gebaseerd, exact
// zoals ervoor — dat voedt rechtstreeks activeCalibratedMgdl() en dus wat
// er via AAPS gedoseerd wordt, en die opslag-representatie hoort niet
// stilzwijgend te wijzigen met een UI-instelling. Alleen de WEERGAVE (het
// getal naast "Manual offset" én de Slider's zichtbare bereik/stapgrootte/
// positie) wordt hier omgezet naar de gekozen eenheid, precies aan de
// randen van deze Composable — [onChange] levert nog steeds gewoon mmol op.
private fun formatOffsetLabel(manualOffsetMmol: Double, unit: GlucoseUnit): String {
    val sign = if (manualOffsetMmol >= 0) "+" else ""
    return when (unit) {
        GlucoseUnit.MGDL -> "%s%.1f mg/dL".format(sign, manualOffsetMmol.mmolToMgdl())
        GlucoseUnit.MMOL -> "%s%.2f mmol/L".format(sign, manualOffsetMmol)
    }
}

@Composable
private fun OffsetSlider(manualOffsetMmol: Double, unit: GlucoseUnit, onChange: (Double) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Manual offset", style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatOffsetLabel(manualOffsetMmol, unit),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            // Bereik/stapgrootte in de weergave-eenheid: ±1,5 mmol/L
            // (ongewijzigd) resp. het mg/dL-equivalent daarvan (≈±27 mg/dL),
            // met een stapgrootte van 0,05 mmol/L resp. 1 mg/dL — vergelijkbare
            // fijnheid, geen wijziging in het aantal bruikbare Slider-posities.
            val rangeMmol = 1.5
            val rangeDisplay = if (unit == GlucoseUnit.MGDL) rangeMmol.mmolToMgdl().toFloat() else rangeMmol.toFloat()
            val stepDisplay = if (unit == GlucoseUnit.MGDL) 1.0f else 0.05f
            val valueDisplay = if (unit == GlucoseUnit.MGDL) {
                manualOffsetMmol.mmolToMgdl().toFloat()
            } else {
                manualOffsetMmol.toFloat()
            }
            Slider(
                value = valueDisplay,
                onValueChange = { newDisplay ->
                    val newMmol = if (unit == GlucoseUnit.MGDL) {
                        newDisplay.toDouble().mgdlToMmol()
                    } else {
                        newDisplay.toDouble()
                    }
                    onChange(newMmol)
                },
                valueRange = -rangeDisplay..rangeDisplay,
                steps = (((2f * rangeDisplay) / stepDisplay).roundToInt() - 1)
            )
            Text(
                "Works on its own even without any fingerstick entries — a plain shift of the sensor curve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 44) — [calibratedMgdl]: de waarde die de op dit
 * moment ACTIEVE curve (zie activeCalibratedMgdl() hieronder — dezelfde
 * functie als de grafiek gebruikt) teruggeeft voor de ruwe sensorwaarde van
 * deze entry. Nuttig om te zien hoe goed de huidige fit deze specifieke
 * historische vingerprik nog benadert.
 *
 * 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-database) — [entry] is
 * nu een [FingerstickListEntry] (i.p.v. [CalibrationEntry]): toont ELKE
 * relevante rij, aangevinkt of niet, met een expliciet vinkje
 * ([onToggleChecked]) i.p.v. impliciet "aanwezig = meegeteld". "Entered
 * here" verschijnt bij een herkomst-rij (ter info, verandert niets aan de
 * bediening — ook die rij kan uitgevinkt worden).
 */
@Composable
private fun CalibrationEntryRow(
    entry: FingerstickListEntry,
    calibratedMgdl: Double,
    selected: Boolean,
    // 13/08/2026 (editor, RONDE 104) — zie ui/Units.kt's [GlucoseUnit]-kdoc.
    unit: GlucoseUnit,
    onSelect: () -> Unit,
    onToggleChecked: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = entry.checked, onCheckedChange = onToggleChecked)
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    timeFormat.format(Date(entry.timestampMs)) + if (entry.enteredOnThisSensor) "" else "  (also seen on other sensor)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "stick ${entry.fingerstickMgdl.formatForDisplay(unit)}   " +
                        "sensor ${entry.sensorMgdlAtPairing.formatForDisplay(unit)}   " +
                        "cal ${calibratedMgdl.formatForDisplay(unit)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 47, op verzoek: "ik wil als ik een calibratie
 * toevoeg dat in het invul scherm alsvast de sensor waarde is ingevuld en
 * dat ik met een plus min knop de waarde kan aanpassen naar de
 * vingerprikwaarde, dit om type fouten te voorkomen") — [initialMmol] (de
 * actuele ruwe sensorwaarde, zie CalibrationScreen()'s FAB-onClick) staat
 * meteen in het veld, en de +/− knoppen tikken in stappen van 0,1 mmol/L
 * (dezelfde precisie als elders in de app, zie formatMmol()) naar de
 * werkelijke meterwaarde toe — typen vanaf nul (en dus élk cijfer zelf
 * moeten invoeren) is foutgevoeliger dan een paar keer bijstellen vanaf een
 * al-bijna-juist startpunt. Het tekstveld blijft ook gewoon direct
 * bewerkbaar (bv. voor een grotere sprong).
 */
@Composable
private fun AddCalibrationDialog(
    initialMgdl: Double,
    // 13/08/2026 (editor, RONDE 104, op verzoek: "de eenheidtoggle geldt ook
    // voor alle invoervelden zoals vingerprik") — het veld toont/parseert nu
    // in de gekozen eenheid; intern werkt deze dialoog nog steeds in mg/dL
    // (net als de rest van de app na deze ronde), [onConfirm] levert dus nu
    // meteen mg/dL op i.p.v. het vroegere mmol/L + een aparte mmolToMgdl()-
    // omzetting bij de aanroeper (CalibrationScreen()'s onConfirm-blok).
    unit: GlucoseUnit,
    onDismiss: () -> Unit,
    onConfirm: (fingerstickMgdl: Double) -> Unit,
    errorText: String?
) {
    var text by remember { mutableStateOf(initialMgdl.formatForDisplay(unit)) }
    // Stapgrootte in de weergave-eenheid: 0,1 mmol/L (ongewijzigd) resp.
    // 2 mg/dL (vergelijkbare fijnheid — 0,1 mmol/L ≈ 1,8 mg/dL).
    val step = if (unit == GlucoseUnit.MGDL) 2.0 else 0.1
    val minValue = if (unit == GlucoseUnit.MGDL) 2.0 else 0.1
    fun adjust(delta: Double) {
        val current = text.trim().replace(',', '.').toDoubleOrNull() ?: initialMgdl.formatForDisplay(unit).toDouble()
        val next = (current + delta).coerceAtLeast(minValue)
        text = if (unit == GlucoseUnit.MGDL) "%.0f".format(next) else "%.1f".format(next)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add calibration") },
        text = {
            Column {
                Text("Fingerstick value (${unit.suffix})", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = { adjust(-step) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease")
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { adjust(step) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase")
                    }
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mgdl = text.parseToMgdl(unit)
                if (mgdl != null && mgdl > 0) onConfirm(mgdl)
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * 06/08/2026 (editor, RONDE 44) — de curve-selectielogica die zowel de
 * grafiek (CalibrationScatterChart, bemonsterd over het hele venster) als de
 * "cal"-kolom in de entry-lijst (CalibrationEntryRow, één punt) gebruiken —
 * één functie zodat die twee weergaves nooit uit elkaar kunnen lopen.
 * Zelfde beslisboom als CalibrationEngine.computeCalibration(): spline
 * eerst (alleen in SPLINE-modus, als er een fit is), anders lineair (als
 * toepasbaar), anders puur de handmatige offset — precies zoals hier al
 * impliciet stond in de vorige, alleen-in-de-grafiek-levende curveFn, nu
 * verplaatst zodat 'm ook buiten de Canvas bruikbaar is. Geeft (i.t.t. de
 * oude curveFn) altijd een waarde terug, nooit null — bij géén bruikbare fit
 * gewoon identiteit + offset, zoals CalibrationEngine dat ook doet.
 */
// 13/08/2026 (editor, RONDE 105) — de delta-validatie (CalibrationValidation.kt,
// evaluateNewCalibrationEntry()) rekent intern in mmol/L per 5 min (die
// wiskunde/drempel wordt hier NIET aangeraakt) — deze functie zet alleen de
// gemelde snelheid om naar de gekozen weergave-eenheid; het is een pure
// schaalfactor-omzetting (geen offset, zoals bij een absolute BG-waarde),
// dus mmolToMgdl()/mgdlToMmol() zijn hier net zo geldig op een snelheid als
// op een concentratie.
private fun formatRatePer5Min(rateMmolPer5Min: Double, unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> "%.1f mg/dL per 5 min".format(rateMmolPer5Min.mmolToMgdl())
    GlucoseUnit.MMOL -> "%.2f mmol/L per 5 min".format(rateMmolPer5Min)
}

private fun activeCalibratedMgdl(
    sensorMgdl: Double,
    mode: CalibrationMode,
    linearFit: CalibrationFit?,
    splineFit: SplineFit?,
    manualOffsetMgdl: Double
): Double = when {
    mode == CalibrationMode.SPLINE && splineFit != null -> splineFit.apply(sensorMgdl, manualOffsetMgdl)
    linearFit != null && linearFit.isApplicable -> linearFit.slope * sensorMgdl + linearFit.offset + manualOffsetMgdl
    else -> sensorMgdl + manualOffsetMgdl
}

/**
 * 05/08/2026 (editor, RONDE 43) — Canvas-scatterplot: vingerprik-vs-
 * sensorwaarde-punten, de diagonale referentielijn (y=x), en de actief
 * gefitte curve (lineair of spline, afhankelijk van [mode]). Bewust een
 * lichte, zelfgetekende Canvas i.p.v. MPAndroidChart's CombinedChart hier
 * (dat project gebruikt MPAndroidChart specifiek voor de tijd-as-BG-grafiek,
 * zie GlucoseChart.kt — dit is een andere soort plot, X/Y allebei BG-
 * waarden, geen tijd-as, dus geen van MPAndroidChart's tijd-as-voordelen
 * gelden hier).
 *
 * 06/08/2026 (editor, RONDE 44, op verzoek: "ik wil in de calibratie
 * grafiek ook de assen zien en raster lijnen [...] mogen de stippen in de
 * grafiek een verloop in kleur krijgen naar ouderdom, hoe ouder de waarde
 * hoe lager het gewicht in het mee tellen voor de calibratie [...] als ik
 * maar 1 dag calibreer en dan 14 dagen niet verloopt de grafiek uiteraard
 * niet omdat alles dan gelijkmatig ouder wordt") — drie toevoegingen:
 * (a) een dun kader + genummerde raster-/aslijnen (ronde mmol-stappen,
 * automatisch 1/2/4 afhankelijk van de spreiding) met tekstlabels via
 * nativeCanvas — de plot-inhoud (referentielijn/curve/punten) is nu
 * ingesprongen binnen [leftMarginPx]/[bottomMarginPx] i.p.v. de volle
 * Canvas te vullen, zodat de labels ruimte hebben; (b) de kleur van elk punt
 * (behalve het geselecteerde, dat blijft de volle accentkleur voor
 * vindbaarheid) is nu een lineaire menging tussen een "vers" en een "oud"
 * kleur, gestuurd door [now] via dezelfde `weightFor()` uit
 * CalibrationMath.kt die de FIT zelf ook gebruikt om oudere punten minder
 * te laten meetellen — dus expliciet NIET genormaliseerd op de
 * min/max-leeftijd binnen de huidige puntenset (dat zou bij een groot gat
 * tussen twee kalibratiesessies de kleuren van een oude, onderling
 * gelijktijdige cluster punten uit elkaar trekken, puur omdat ze toevallig
 * de "oudste in beeld" zijn) — een enkele kalibratiesessie van bv. 1 dag
 * blijft daardoor, ook 14 dagen later, onderling ÉÉN gelijkmatige tint
 * behouden (ze zijn allemaal even oud t.o.v. `now`, dus krijgen allemaal
 * hetzelfde, inmiddels lagere gewicht) — precies het gevraagde gedrag.
 */
@Composable
private fun CalibrationScatterChart(
    entries: List<CalibrationEntry>,
    selectedEntryId: Long?,
    mode: CalibrationMode,
    linearFit: CalibrationFit?,
    splineFit: SplineFit?,
    manualOffsetMgdl: Double,
    now: Long,
    // 13/08/2026 (editor, RONDE 105) — zelfde "plot altijd in mg/dL, alleen
    // de as-TEKST zet om" patroon als GlucoseChart.kt's yAxisValueFormatter:
    // xPx()/yPx()/de gefitte curve/de scatterpunten hieronder blijven
    // ONGEWIJZIGD rekenen in mg/dL — alleen de rasterstap/-labels (voorheen
    // altijd mmol) worden nu in de gekozen eenheid bepaald en getekend.
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val axisTextColor = MaterialTheme.colorScheme.secondary
    val axisTextColorArgb = axisTextColor.toArgb()
    val frameColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    // 06/08/2026 (editor, RONDE 45, op verzoek: "alle meetellende punten
    // dezelfde kleur krijgen [...] als ze ouder worden dan moeten ze
    // langzaam vervagen (transparantie dus toenemen)") — was een lerp()
    // tussen twee losse Color-objecten (freshPointColor/oldPointColor);
    // functioneel bijna hetzelfde (beide waren dezelfde onSurface-kleur met
    // alleen een andere alpha, dus de lerp bleef al binnen dezelfde tint),
    // maar nu expliciet ÉÉN vaste basiskleur ([pointBaseColor]) met alleen
    // een per-punt `.copy(alpha = ...)` — geen kleurmenging meer, puur
    // transparantie. De alpha-mapping hieronder gebruikt bovendien niet meer
    // rechtstreeks het lineaire genormaliseerde gewicht, maar een vierde-
    // macht daarvan: bij τ=2 dagen ligt het gewicht van entries die een paar
    // uur uit elkaar liggen nog zeer dicht bij elkaar (exp(-uren/48u) daalt
    // traag), dus een lineaire mapping liet ze in de praktijk bijna
    // identiek ondoorzichtig ogen — geen zichtbaar "vervagen". De vierde
    // macht drukt normalized-waarden die niet dicht bij 1 liggen sneller
    // omlaag, wat de spreiding tussen bv. "2 uur oud" en "14 uur oud"
    // duidelijk zichtbaar maakt, terwijl de volgorde (en dus welk punt
    // uiteindelijk het meest vervaagd is) nog steeds zuiver op het echte
    // tijd-vervalgewicht (weightFor(), absoluut t.o.v. `now`) gebaseerd
    // blijft — zie de klasse-kdoc hierboven voor waarom dat NIET
    // genormaliseerd wordt op de min/max-leeftijd binnen de huidige set.
    val pointBaseColor = MaterialTheme.colorScheme.onSurface
    val pointMinAlpha = 0.12f
    val pointMaxAlpha = 0.85f
    val selectedColor = MaterialTheme.colorScheme.primary
    val curveColor = MaterialTheme.colorScheme.primary
    val refLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Ruimte voor de as-labels — links (Y, vingerprik) breder dan
            // onder (X, sensor) omdat getallen zoals "12,0" links horizontaal
            // getekend worden, tegen de klok in vanaf de plotrand.
            val leftMarginPx = 34.dp.toPx()
            val bottomMarginPx = 18.dp.toPx()
            val plotLeft = leftMarginPx
            val plotRight = size.width
            val plotTop = 4.dp.toPx()
            val plotBottom = size.height - bottomMarginPx
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

            val allValues = (entries.flatMap { listOf(it.sensorMgdlAtPairing, it.fingerstickMgdl) })
                .ifEmpty { listOf(72.0, 216.0) } // 4.0 - 12.0 mmol default venster
            val loMgdl = (allValues.min() - 36.0).coerceAtLeast(36.0)
            val hiMgdl = allValues.max() + 36.0
            val span = (hiMgdl - loMgdl).coerceAtLeast(18.0)

            fun xPx(mgdl: Double) = (plotLeft + ((mgdl - loMgdl) / span) * plotWidth).toFloat()
            fun yPx(mgdl: Double) = (plotBottom - ((mgdl - loMgdl) / span) * plotHeight).toFloat()

            // Kader om het plotgebied.
            drawRect(
                color = frameColor,
                topLeft = Offset(plotLeft, plotTop),
                size = androidx.compose.ui.geometry.Size(plotWidth, plotHeight),
                style = Stroke(width = 1.5f)
            )

            // Rasterlijnen + as-labels op ronde stappen in de gekozen
            // weergave-eenheid — 1/2/4 mmol resp. 25/50/100 mg/dL bij
            // toenemende spreiding, zodat het er nooit te druk (te veel
            // lijnen) of te leeg (te weinig) uitziet, in beide eenheden.
            val spanDisplay = if (unit == GlucoseUnit.MGDL) span else span.mgdlToMmol()
            val stepDisplay = if (unit == GlucoseUnit.MGDL) {
                when {
                    spanDisplay <= 100.0 -> 25.0
                    spanDisplay <= 200.0 -> 50.0
                    else -> 100.0
                }
            } else {
                when {
                    spanDisplay <= 6.0 -> 1.0
                    spanDisplay <= 12.0 -> 2.0
                    else -> 4.0
                }
            }
            val loDisplay = if (unit == GlucoseUnit.MGDL) loMgdl else loMgdl.mgdlToMmol()
            val hiDisplay = if (unit == GlucoseUnit.MGDL) hiMgdl else hiMgdl.mgdlToMmol()
            val labelPaint = android.graphics.Paint().apply {
                color = axisTextColorArgb
                textSize = 10.sp.toPx()
                isAntiAlias = true
            }
            var tickDisplay = ceil(loDisplay / stepDisplay) * stepDisplay
            while (tickDisplay <= hiDisplay) {
                val tickMgdl = if (unit == GlucoseUnit.MGDL) tickDisplay else tickDisplay.mmolToMgdl()
                val vx = xPx(tickMgdl)
                val vy = yPx(tickMgdl)
                // Verticale rasterlijn (constante sensorwaarde = X) + label onderaan.
                drawLine(gridColor, Offset(vx, plotTop), Offset(vx, plotBottom), strokeWidth = 1f)
                // Horizontale rasterlijn (constante vingerprikwaarde = Y) + label links.
                drawLine(gridColor, Offset(plotLeft, vy), Offset(plotRight, vy), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.apply {
                    val label = "%.0f".format(tickDisplay)
                    labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                    drawText(label, vx, size.height, labelPaint)
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    drawText(label, leftMarginPx - 4.dp.toPx(), vy + 4.dp.toPx(), labelPaint)
                }
                tickDisplay += stepDisplay
            }

            // Referentielijn y = x (gestippeld)
            drawLine(
                color = refLineColor,
                start = Offset(xPx(loMgdl), yPx(loMgdl)),
                end = Offset(xPx(hiMgdl), yPx(hiMgdl)),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            // Gefitte curve — bemonsterd over het venster, geldt voor zowel
            // lineair als spline (SplineFit.apply werkt ook lineair buiten
            // zijn segmenten, zie SplineCalibrationMath.kt) én voor
            // "alleen de handmatige offset" (activeCalibratedMgdl() geeft
            // altijd een waarde terug, zie kdoc daar) — dus de curve wordt nu
            // ook getekend als er nog helemaal geen kalibratie-entries zijn.
            val steps = 40
            var prev: Offset? = null
            for (i in 0..steps) {
                val x = loMgdl + span * i / steps
                val y = activeCalibratedMgdl(x, mode, linearFit, splineFit, manualOffsetMgdl)
                val point = Offset(xPx(x), yPx(y))
                if (prev != null) {
                    drawLine(curveColor, prev, point, strokeWidth = 4f, cap = StrokeCap.Round)
                }
                prev = point
            }

            // Scatterpunten — alle niet-geselecteerde punten delen dezelfde
            // basiskleur ([pointBaseColor]); alleen de TRANSPARANTIE
            // verandert met het tijd-vervalgewicht (weightFor(), zie kdoc
            // hierboven), niet hun relatieve positie in de huidige lijst.
            for (entry in entries) {
                val isSelected = entry.id == selectedEntryId
                val color = if (isSelected) {
                    selectedColor
                } else {
                    val weight = weightFor(entry.timestampMs, now)
                    val normalized = ((weight - 0.10) / 0.90).coerceIn(0.0, 1.0)
                    // Vierde macht — zie kdoc hierboven: versterkt het
                    // zichtbare verschil tussen bv. "2 uur oud" en "14 uur
                    // oud", die met een lineaire mapping bij τ=2 dagen bijna
                    // niet uit elkaar te houden waren.
                    val amplified = normalized.pow(4)
                    val alpha = (pointMinAlpha + (pointMaxAlpha - pointMinAlpha) * amplified).toFloat()
                    pointBaseColor.copy(alpha = alpha)
                }
                drawCircle(
                    color = color,
                    radius = if (isSelected) 10f else 6f,
                    center = Offset(xPx(entry.sensorMgdlAtPairing), yPx(entry.fingerstickMgdl))
                )
            }
        }
    }
}
