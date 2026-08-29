package com.fclglucolink.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.data.SensorSwitchEventStore
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 30/07/2026 (editor, na feedback) — dit is nu het STARTSCHERM van de app (zie
 * FclGlucoLinkNavHost.kt): geen automatische doorverwijzing meer naar
 * koppelen. Toont de BG-curve/status altijd, ook als er nog geen sensor
 * gekozen is.
 *
 * 31/07/2026 (editor, na feedback over de menu-indeling) — de sensor-acties
 * (kiezen/wisselen, loskoppelen) stonden eerder verspreid over het
 * ⋮-menu; nu allemaal samen op SensorManagementScreen.kt, geopend door op
 * de sensorkaart hieronder te tikken. Het ⋮-menu opent nu Settings
 * (algemene instellingen: xDrip-broadcast aan/uit + About), niet meer
 * sensor-specifieke acties — zie SettingsScreen.kt/AboutScreen.kt.
 *
 * 02/08/2026 (editor, op verzoek: "info direct op het hoofdscherm tonen
 * samen met sensor connected") — de VOLLEDIGE sensor-info (type/serienr/
 * status/start/eind/laatste verbinding, zie SensorInfoBlock) staat nu
 * direct hier op het startscherm, niet meer alleen achter een tik op een
 * compacte samenvattingskaart — alleen de koppel-ACTIES (wisselen/
 * loskoppelen) blijven op SensorManagementScreen.kt, tikken op de kaart
 * hieronder opent dat scherm nog steeds.
 *
 * Leest UITSLUITEND uit GlucoseReadingStore + ConnectionStatusBridge +
 * AppSettings — geen rechtstreekse toegang tot een SensorDriver hier, dat
 * hoort allemaal bij BleConnectionService (zie kdoc daar).
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 *
 * 05/08/2026 (editor, RONDE 43 — "Bij het menu. komt een kalibratie aan/uit
 * knop. Als die wordt aan gezet verschijnt er op het hoofdscherm een
 * kalibratie knop") — nieuwe parameter [onOpenCalibration], alleen benut
 * (knop getoond) als AppSettings.calibrationEnabled aan staat, zie
 * SettingsScreen.kt voor de aan/uit-schakelaar zelf. Zie ook BgRingDisplay's
 * kdoc voor de bijbehorende ruwe/gekalibreerde dubbele weergave.
 *
 * 06/08/2026 (editor, RONDE 50, op verzoek: "de 3 puntjes wil ik dan boven
 * sensor als 'settings' knop [...] nog een 'info' knop die het mooist
 * rechts onderin kan") — twee wijzigingen: (1) het ⋮-icoontje dat eerder in
 * de TopAppBar stond (opende hetzelfde Settings-scherm) is vervangen door
 * een gewone "Settings"-knop, in dezelfde kolom en stijl als "Sensor"/
 * "Calibration" — zie de kdoc bij die Row hieronder voor de volledige
 * knoppen-herstyling. (2) nieuwe parameter [onOpenManual]: een rond
 * info-knopje rechtsonder in beeld (zie de Box-wrapper om Scaffold's content
 * hieronder) dat naar het nieuwe ManualScreen.kt navigeert — de volledige
 * gebruiksaanwijzing, zie kdoc daar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    // 09/08/2026 (editor, RONDE 64, op verzoek — zie de uitgebreide kdoc bij
    // FclGlucoLinkNavHost() voor het volledige herstructureringsverhaal) —
    // was één onOpenSensorManagement; nu twee duidelijk gescheiden acties:
    // onSwitchSensorType ("Sensor"-knop rechtsboven — type WISSELEN, met
    // bevestiging als er al iets anders actief is, zie
    // SensorSelectionScreen.kt) en onOpenSensorStatus (de (i)-knop op de
    // compacte samenvatting hieronder — het HUIDIGE type's eigen
    // status-/beheerscherm openen, geen wissel).
    onSwitchSensorType: () -> Unit,
    onOpenSensorStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenManual: () -> Unit,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: zie PairingScreen.kt's identieke kdoc bij zijn
    // eigen [slot]-parameter. Dit scherm blijft t/m taak #311 dus Slot A's
    // eigen weergave — de echte gecombineerde/tab-weergave komt daar.
    slot: SensorSlot = SensorSlot.A
) {
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317) —
    // was hier de VOLLEDIGE per-slot state (store/settings-flows/tick-timer)
    // rechtstreeks gedeclareerd, gebruikt door de Column-inhoud die verderop
    // in dit bestand stond; die inhoud (en dus ook deze state) is nu
    // geëxtraheerd naar SlotStatusContent() (zie kdoc daar) — StatusScreen()
    // zelf heeft nu geen eigen state meer nodig, geeft gewoon [slot] door.
    Scaffold(
        topBar = {
            // 06/08/2026 (editor, RONDE 50) — het ⋮-icoontje dat hier stond
            // (opende Settings) is vervangen door een gewone "Settings"-knop
            // in de knoppenkolom hieronder, zie de kdoc bij StatusScreen()
            // hierboven. TopAppBar blijft verder puur de titelbalk.
            TopAppBar(title = { Text("FCLGlucoLink") })
        }
    ) { padding ->
        // 06/08/2026 (editor, RONDE 50, op verzoek: "nog een 'info' knop die
        // het mooist rechts onderin kan") — Box i.p.v. rechtstreeks de
        // scrollende Column als Scaffold-content: zo kan het info-knopje
        // ONAFHANKELIJK van de scrollpositie vast rechtsonder in het
        // zichtbare scherm blijven staan (Alignment.BottomEnd), i.p.v. mee
        // te scrollen als gewoon laatste item in de Column.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak
            // #317 "tab UI") — de eigenlijke inhoud (ring/knoppen/compacte
            // samenvatting/grafiek) is geëxtraheerd naar SlotStatusContent()
            // hieronder, zodat CombiScreen.kt (het nieuwe, tab-gebaseerde
            // startscherm) 'm per tabblad kan hergebruiken zonder deze hele
            // functie (incl. eigen TopAppBar/FAB) te dupliceren.
            // StatusScreen zelf blijft verder ongewijzigd werken: geeft
            // onOpenSettings hier WEL door, dus SlotStatusContent toont de
            // "Settings"-knop nog gewoon als eerste in de knoppenkolom,
            // precies zoals voorheen (StatusScreen zelf wordt sinds deze
            // ronde niet meer als route gebruikt — CombiScreen is nu het
            // startscherm — maar blijft als op-zichzelf-staand, werkend
            // scherm bestaan, zie CombiScreen.kt's kdoc voor waarom
            // volledige verwijdering niet mogelijk was).
            SlotStatusContent(
                slot = slot,
                onSwitchSensorType = onSwitchSensorType,
                onOpenSensorStatus = onOpenSensorStatus,
                onOpenCalibration = onOpenCalibration,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize()
            )

            // 06/08/2026 (editor, RONDE 50) — zie kdoc hierboven bij StatusScreen()
            // en bij de Box-wrapper: bewust een los, klein rond knopje (net als
            // de bestaande secundaire knoppen NIET de felle primary-kleur) i.p.v.
            // een grote standaard FloatingActionButton — dat zou hier te veel
            // nadruk krijgen t.o.v. wat verder een rustig, informatief scherm is.
            SmallFloatingActionButton(
                onClick = onOpenManual,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(Icons.Filled.Info, contentDescription = "Help / manual")
            }
        }
    }
}

/**
 * ============================================================================
 * SlotStatusContent — de eigenlijke per-slot inhoud, los van Scaffold/TopAppBar/FAB
 * ============================================================================
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317) —
 * geëxtraheerd uit StatusScreen() hierboven (zie de uitgebreide kdoc daar
 * voor de volledige ontwerpgeschiedenis van de ring/knoppen/kaartjes): BG-
 * ring, Sensor/[Calibration]-knoppen, compacte sensor-samenvatting,
 * grafiekkaart, "Connect sensor"-knop. Bewust GEEN eigen Scaffold/TopAppBar/
 * FAB hier — dat blijft bij de AANROEPER (StatusScreen zelf, of
 * CombiScreen.kt per tabblad), want die twee verschillen juist in HOE ze de
 * FAB/instellingen-knop rond deze inhoud plaatsen (StatusScreen: eigen FAB +
 * "Settings" bovenaan de knoppenkolom; CombiScreen: gedeelde onderste rij
 * ÉÉN keer voor alle tabbladen samen, zie kdoc daar).
 *
 * [onOpenSettings] is bewust optioneel (`null` toegestaan): StatusScreen
 * geeft 'm door (toont de "Settings"-knop bovenaan de knoppenkolom, exact
 * het gedrag van vóór deze extractie); CombiScreen laat 'm weg omdat
 * Settings daar al in de gedeelde onderste rij staat — een tweede
 * "Settings"-knop per tabblad zou daar overbodig/verwarrend zijn.
 */
@Composable
fun SlotStatusContent(
    slot: SensorSlot,
    onSwitchSensorType: () -> Unit,
    onOpenSensorStatus: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { GlucoseReadingStore(context) }
    // 09/08/2026 (editor, RONDE 64) — zie GlucoseChart.kt's kdoc bij
    // switchEvents.
    val switchEventStore = remember { SensorSwitchEventStore(context) }
    val settings = remember { AppSettings(context) }
    val selectedSensor by settings.selectedSensor(slot).collectAsState(initial = null)
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317) —
    // KRITIEKE fix t.o.v. het gedrag van vóór deze extractie: was
    // `store.latestReading()`/`store.recentReadings(hours = 48)` — dus
    // ONGEFILTERD, de gecombineerde stream van BEIDE slots door elkaar
    // (GlucoseReadingEntity's sensorType-veld en de bijbehorende
    // sensorType-parameter op GlucoseReadingStore's functies bestonden al,
    // zie kdoc daar, maar werden hier nog niet benut). Met twee gelijktijdig
    // actieve slots zou dit hier BEIDE tabbladen exact dezelfde, door elkaar
    // gehusselde meest-recente meting/grafiek laten zien — welke van de twee
    // sensoren toevallig het laatst gemeten heeft, niet per se DEZE slot's
    // eigen sensor.
    //
    // 10/08/2026 (editor, RONDE 81, TWEEDE bugfix in dezelfde klasse, live-
    // melding met screenshot — een slot zonder gekozen sensor toonde alsnog
    // de ANDERE slot's ring/grafiek) — de vorige versie van deze kdoc
    // beweerde dat `sensorType = selectedSensor` hier voldoende was omdat
    // GlucoseReadingStore's `sensorType: SensorType? = null` "toch al als
    // ongefilterd" telt zodra er geen sensor gekozen is — dat is precies
    // verkeerd gebleken: "ongefilterd" betekent bij die store letterlijk "van
    // ALLE slots tegelijk", niet "niets". Dus zodra [selectedSensor] écht
    // `null` was (geen sensor voor déze slot), viel dit terug op de
    // combinatie van beide slots i.p.v. een lege staat. Nu expliciet
    // gescoped: alleen queryen als er WEL een sensor gekozen is, anders een
    // vaste `null`/lege flow — zelfde patroon als CalibrationScreen.kt's
    // latestReadingFlow (RONDE 81).
    // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — was `sensorType = it`:
    // zie GlucoseReadingStore.kt's kdoc bij latestReading() voor de volledige
    // analyse (sensorType alleen is geen betrouwbare slot-discriminator
    // zodra beide slots hetzelfde sensortype draaien). De null-guard op
    // selectedSensor blijft ongewijzigd nodig.
    val latestFlow = remember(store, selectedSensor) {
        selectedSensor?.let { store.latestReading(slot = slot) } ?: flowOf(null)
    }
    val latest by latestFlow.collectAsState(initial = null)
    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
    // [GlucoseUnit]-kdoc; default MMOL matcht AppSettings.displayUnit's
    // eigen default (nooit een flits van de verkeerde eenheid vóór de
    // eerste collectie).
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)
    // 05/08/2026 (editor, RONDE 43) — zie kdoc hierboven bij StatusScreen().
    val calibrationEnabled by settings.calibrationEnabled.collectAsState(initial = false)
    // 18/08/2026 (editor, RONDE 113) — beide nodig voor de nieuwe
    // raw/gekalibreerd/gefilterd-regel (PipelineValuesRow hieronder): of
    // smoothing überhaupt aan staat (anders is er geen "gefilterd" om te
    // tonen) én of de gebruiker de regel expliciet aan heeft gezet in
    // Settings. Zie AppSettings.kt's kdoc bij beide velden.
    val smoothingEnabled by settings.smoothingEnabled.collectAsState(initial = false)
    // 29/08/2026 (editor, RONDE 160) — zie prediction/GlucosePrediction.kt en
    // AppSettings.kt's PREDICTION_ENABLED-kdoc.
    val predictionEnabled by settings.predictionEnabled.collectAsState(initial = false)
    val showFilteredPipelineOnMainScreen by settings.showFilteredPipelineOnMainScreen.collectAsState(initial = false)
    // 30/07/2026 (editor, na feedback: 24-48u terug kunnen swipen in de
    // grafiek) — was hours = 6, wat sowieso al hoe ver je ooit kon
    // terugswipen begrensde, los van wat GlucoseReadingStore zelf bewaart
    // (nu 48u, zie daar). Nu in lijn daarmee.
    //
    // 10/08/2026 (editor, RONDE 81) — zelfde null-guard als latestFlow
    // hierboven, zelfde reden.
    // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — zelfde reden als
    // latestFlow hierboven.
    val recentFlow = remember(store, selectedSensor) {
        selectedSensor?.let { store.recentReadings(hours = 48, slot = slot) } ?: flowOf(emptyList())
    }
    val recent by recentFlow.collectAsState(initial = emptyList())
    // 10/08/2026 (editor, RONDE 84, BUGFIX na live-melding met screenshots —
    // het activeren van een nieuwe Dexcom G6-sensor plaatste de
    // wisselmarker-lijn ook op de CareSens Air-tab's grafiek, die toen al
    // dagenlang ongestoord liep) — precies de hierboven beschreven,
    // destijds bewust uitgestelde onvolkomenheid: SensorSwitchEventStore
    // kent nu wél een sensorType-kolom (zie SensorSwitchEventEntity.kt's
    // RONDE-84-kdoc), dus zelfde null-guarded scoping-patroon als
    // latestFlow/recentFlow hierboven.
    val switchEventsFlow = remember(switchEventStore, selectedSensor) {
        selectedSensor?.let { switchEventStore.recentEvents(hours = 48, sensorType = it) } ?: flowOf(emptyList())
    }
    val switchEvents by switchEventsFlow.collectAsState(initial = emptyList())
    val connectionState by ConnectionStatusBridge.state(slot).collectAsState()

    // 30/07/2026 (editor, na feedback) — "Just now"/"X minutes ago" werd
    // eerder alleen herberekend zodra er een NIEUWE meting binnenkwam; deze
    // losse tick-state triggert elke 30s een recompositie zodat de
    // "Xm ago"-tekst ook tussen twee metingen in blijft doortikken.
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTickMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 30/07/2026 (editor, na feedback: "voorkeur voor de weergave zoals
        // die nu in AAPS is") — zie kdoc bij BgRingDisplay hieronder voor de
        // volledige AAPS-ring-geschiedenis.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            BgRingDisplay(
                latest = latest,
                previousMgdl = recent.dropLast(1).lastOrNull()?.glucoseMgdl,
                nowMs = nowTickMs,
                unit = displayUnit
            )
            Spacer(modifier = Modifier.weight(1f))
            // 06/08/2026 (editor, RONDE 50) — width(IntrinsicSize.Max) op de
            // Column + fillMaxWidth() op elke knop: de Column krijgt zo de
            // breedte van zijn BREEDSTE kind, elke knop rekt vervolgens uit
            // tot precies die breedte — "even groot" zonder een magic-
            // number breedte te hoeven kiezen. [onOpenSettings] alleen
            // getoond als de aanroeper 'm meegeeft, zie kdoc hierboven bij
            // SlotStatusContent().
            Column(
                modifier = Modifier.width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onOpenSettings != null) {
                    HomeSecondaryButton(
                        text = "Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HomeSecondaryButton(
                    text = "Sensor",
                    onClick = onSwitchSensorType,
                    modifier = Modifier.fillMaxWidth()
                )
                // 05/08/2026 (editor, RONDE 43) — zie kdoc hierboven bij
                // StatusScreen(): alleen zichtbaar als de schakelaar op
                // SettingsScreen.kt aan staat.
                if (calibrationEnabled) {
                    HomeSecondaryButton(
                        text = "Calibration",
                        onClick = onOpenCalibration,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 09/08/2026 (editor, RONDE 64) — zie CompactSensorSummary()'s kdoc
        // onderaan dit bestand voor waarom dit ÉÉN samenvattende regel is
        // i.p.v. een hele rijenlijst.
        CompactSensorSummary(
            selectedSensor = selectedSensor,
            connectionState = connectionState,
            nowMs = nowTickMs,
            onOpenDetails = onOpenSensorStatus,
            slot = slot
        )

        // 18/08/2026 (editor, RONDE 113, op verzoek — zie PipelineValuesRow's
        // kdoc onderaan dit bestand voor het volledige ontwerpgesprek) —
        // alleen getoond zodra BEIDE waar zijn: smoothing zelf aan (anders is
        // er niets "gefilterd" om te tonen — mirrort de grijs-uit-logica van
        // de bijbehorende Settings-schakelaar) én de gebruiker heeft de regel
        // expliciet aangevinkt. Bewust GEEN "alleen tonen bij verschil"-check
        // (zoals de net verwijderde raw-indicator in BgRingDisplay deed) —
        // zie PipelineValuesRow's kdoc.
        if (smoothingEnabled && showFilteredPipelineOnMainScreen) {
            latest?.let { reading ->
                PipelineValuesRow(
                    reading = reading,
                    calibrationEnabled = calibrationEnabled,
                    unit = displayUnit
                )
            }
        }

        // 30/07/2026 (editor, bugfix) — was fillMaxSize(): binnen deze
        // Column (geen weight) claimde dat de volledige resterende hoogte
        // voor het EERSTE kaartje, waardoor dit grafiekkaartje (en alles
        // eronder) buiten beeld viel. Vaste hoogte + fillMaxWidth() i.p.v.
        // fillMaxSize(). elevation = 0.dp + expliciete containerColor: zie
        // Theme.kt's kdoc over Card's standaard surfaceVariant-gebruik.
        Card(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Glucose", style = MaterialTheme.typography.titleSmall)
                GlucoseChart(
                    readings = recent,
                    switchEvents = switchEvents,
                    unit = displayUnit,
                    predictionEnabled = predictionEnabled,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (selectedSensor == null) {
            Button(onClick = onSwitchSensorType) { Text("Connect sensor") }
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 47, op verzoek: "de knop moet dat iets minder
 * afgerond en minder opvallend kwa kleur [zijn]") — bewust een ANDERE stijl
 * dan de grote, volledig ronde (pill-vormige), primary-gekleurde knoppen
 * elders op dit scherm ("Connect sensor") — een kleinere hoekradius (10dp
 * i.p.v. de standaard volledig ronde vorm) en `surfaceVariant`/
 * `onSurfaceVariant` i.p.v. de felle primary-kleur, zodat deze knoppen
 * duidelijk ondergeschikt ogen aan de BG-waarde ernaast, niet als
 * gelijkwaardige call-to-actions.
 *
 * 06/08/2026 (editor, RONDE 48, op verzoek: "de knoppen mogen iets meer
 * knop uiterlijk krijgen [...] boven de knop 'calibration' [mag] een knop
 * 'sensor' komen") — was `CalibrationEntryButton` (één vaste knop, alleen
 * "Calibration"); nu generiek gemaakt (`text`-parameter) zodat StatusScreen
 * 'm ook voor de nieuwe "Sensor"-knop kan hergebruiken — beide moeten
 * immers dezelfde, samen herkenbare stijl delen. "Iets meer knop uiterlijk"
 * — een dunne rand (`BorderStroke`) toegevoegd; de vlakke `surfaceVariant`-
 * achtergrond alleen gaf te weinig contrast met de kaarten eromheen om
 * meteen als knop herkenbaar te zijn.
 *
 * 06/08/2026 (editor, RONDE 50, op verzoek: "de knoppen [...] iets meer
 * knop vorm maken en even groot [...] dichter bij elkaar") — twee dingen:
 * (1) hoekradius 10dp -> 14dp, samen met een tikje meer verticale padding
 * (8dp -> 10dp) — samen met de bestaande rand oogt dat net iets meer als
 * een "echte" knop, minder als een plat label met een randje. (2) nieuwe
 * `modifier`-parameter (standaard leeg) zodat StatusScreen's knoppenkolom
 * er nu `Modifier.fillMaxWidth()` aan kan doorgeven — zie de kdoc bij die
 * Column voor hoe dat samen met `width(IntrinsicSize.Max)` de knoppen
 * "even groot" maakt.
 *
 * 06/08/2026 (editor, RONDE 51, na live-melding: "de knoppen [...] moeten
 * echt meer het uiterlijk van een knop krijgen") — de ronde-50-aanpassingen
 * hierboven (hoekradius/padding) losten het probleem niet echt op: de
 * ROOTCAUSE was dat `containerColor = surfaceVariant` hier feitelijk exact
 * dezelfde kleur was als de Cards eromheen (zie Theme.kt's kdoc — Card
 * gebruikt óók surfaceVariant, en die stond hier gelijkgezet aan surface).
 * Vulkleur nu `secondaryContainer`/`onSecondaryContainer` — een bewust
 * duidelijk lichtere, specifiek voor dit soort knoppen bedoelde kleur (zie
 * ButtonSurfaceDark's kdoc in Color.kt) — plus een iets steviger randje
 * (alpha 0.5 -> 0.7) en een lichte schaduw-elevatie (`ButtonDefaults.
 * buttonElevation`, 0dp had Button() hier voorheen impliciet standaard
 * niet expliciet gezet, dus geen zichtbare "optilling" van de omgeving),
 * zodat het niet langer als plat label met randje oogt.
 */
@Composable
private fun HomeSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 30/07/2026 (editor, na feedback: "voorkeur voor de weergave zoals die nu
 * in AAPS is") — AAPS toont de huidige BG als een gekleurde RING (kleur
 * volgt of de waarde binnen bereik is) met delta boven, de waarde in het
 * midden en "Xm ago" eronder, plus een driehoekige "vlag" tegen de ring aan
 * die de trendrichting aangeeft.
 *
 * Bewust vereenvoudigd t.o.v. het AAPS-origineel: geen voortschrijdende
 * "tijd-sinds-meting"-boog getekend in de ring zelf — de ring hier is
 * gewoon effen gekleurd. De vlag zelf wordt (sinds de derde poging om 'm
 * goed tegen de ring geplakt te krijgen, zie kdoc bij
 * TrendChevronCanvas) wél met een zelfgetekende Canvas-Path gedaan, een
 * simpel symmetrisch driehoekje i.p.v. AAPS' exacte, licht gebogen
 * vlagvorm.
 *
 * 05/08/2026 (editor, RONDE 43 — op verzoek: "Hij moet overal gebruikt
 * worden [...] Op het scherm wil ik ook de ruwe waarden blijven zien
 * vergelijkbaar met zoals aaps hem toont op het hoofd scherm, de
 * gekalibreerde waarde gewoon volledig en ruwe waarde er bij maar dan veel
 * minder opvallend dus open cirkel en misschien gewoon licht grijs ipv
 * groen of rood of oranje") — [latest.glucoseMgdl] is (dankzij
 * BleConnectionService's applyCalibrationIfEnabled(), zie kdoc daar) al de
 * gekalibreerde waarde zodra kalibratie aan staat; die blijft de hoofdwaarde
 * in de ring, volledig formaat, met de normale bereikskleur. Wanneer
 * [GlucoseReading.rawSensorMgdl] daarvan afwijkt (dus alleen als er
 * daadwerkelijk gekalibreerd is — bij uitgeschakelde kalibratie zijn beide
 * gelijk en verschijnt er niets extra's) verschijnt eronder een klein,
 * bewust ondergeschikt regeltje: een open/lege cirkel (geen vulling) plus de
 * ruwe waarde, allebei effen lichtgrijs — nadrukkelijk NIET de
 * groen/amber/rood-bereikskleur, om te voorkomen dat het oog per ongeluk
 * naar de ruwe i.p.v. de gekalibreerde waarde getrokken wordt.
 */
@Composable
private fun BgRingDisplay(
    latest: GlucoseReading?,
    previousMgdl: Double?,
    nowMs: Long,
    // 13/08/2026 (editor, RONDE 104) — zie ui/Units.kt's [GlucoseUnit]-kdoc.
    unit: GlucoseUnit = GlucoseUnit.MMOL,
    modifier: Modifier = Modifier
) {
    if (latest == null) {
        Box(
            modifier = modifier
                .size(140.dp)
                .border(6.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No reading yet",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    val ringColor = bgRangeColor(latest.glucoseMgdl)
    val deltaMgdl = previousMgdl?.let { latest.glucoseMgdl - it }
    // 30/07/2026 (editor, na feedback: delta en tijd wit, net als in AAPS) —
    // alleen de grote BG-waarde blijft in de bereikskleur (groen/amber/
    // rood); delta en "Xm ago" gebruiken nu allebei dezelfde neutrale
    // tekstkleur i.p.v. de delta in de bereikskleur en de tijd in grijs.
    val neutralTextColor = MaterialTheme.colorScheme.onSurface

    // 31/07/2026 (editor, na feedback: "cirkel mag iets kleiner") — was
    // 140.dp/36.dp.
    val ringSize = 120.dp
    val chevronSize = 30.dp

    // 30/07/2026 (editor, bevestigd via schets in het gesprek: ring en
    // driehoekje draaien samen als één star geheel om het middelpunt van de
    // ring, het aanhechtpunt zelf schuift dus mee rond de rand — zie kdoc
    // bij TrendChevronCanvas) — het aanhechtpunt kan hierdoor nu ook
    // boven-rechts (12 uur, bij snel stijgend) of onder-rechts (6 uur, bij
    // snel dalend) van de ring uitkomen, niet alleen recht rechts. Daarom nu
    // ook verticale marge (chevronSize erbij, boven én onder) rond de ring,
    // niet alleen horizontaal — anders zou de punt van de driehoek bij die
    // standen buiten deze Box vallen. De ring wordt met CenterStart verticaal
    // gecentreerd in deze hogere Box gezet; TrendChevronCanvas rekent zijn
    // eigen middelpunt uit op basis van de ACTUELE canvasgrootte (die exact
    // deze Box vult, zie matchParentSize), niet op een aanname over waar de
    // ring precies staat.
    Box(
        modifier = modifier.size(width = ringSize + chevronSize, height = ringSize + chevronSize)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(ringSize)
                .border(6.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    deltaMgdl?.let { formatDelta(it, unit) } ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neutralTextColor
                )
                Text(
                    latest.glucoseMgdl.formatForDisplay(unit),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = ringColor
                )
                Text(
                    minutesAgoText(latest.timestampMs, nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = neutralTextColor
                )
                // 05/08/2026 (editor, RONDE 43) — hier stond tot RONDE 113 een
                // kleine open-cirkel-indicator met de ruwe waarde, alleen
                // zichtbaar zodra kalibratie de waarde daadwerkelijk
                // veranderde (`abs(raw - final) > 0.01`).
                //
                // 18/08/2026 (editor, RONDE 113, op verzoek: "ik zit me nu ook
                // aftevragen of we het ongekalibreerde getal [...] wel in de
                // cirkel moeten tonen want dat is in de grafiek ook duidelijk
                // zichtbaar als open-bolletjes-lijn") — VERWIJDERD: dubbelop
                // met de open-bolletjes-raw-lijn die GlucoseChart.kt al toont,
                // én had een niet-instelbare, onzichtbare drempel (dezelfde
                // soort probleem dat elders in dit gesprek net was afgekeurd
                // voor een sterkte-slider). Vervangen door een generieke,
                // altijd-per-instelling-getoonde raw/gekalibreerd/gefilterd-
                // regel onder de sensor-infokaart, zie SlotStatusContent's
                // aanroep van PipelineValuesRow hieronder.
            }
        }
        // 30/07/2026 (editor, compile-fix) — matchParentSize() is een MEMBER
        // van BoxScope zelf (niet een los top-level extension function op
        // Modifier), vandaar geen aparte import nodig/mogelijk — resolvet
        // hier automatisch omdat deze regel binnen de content-lambda van de
        // buitenste Box{} staat (BoxScope als impliciete receiver).
        // 31/07/2026 (editor, na feedback: "bij +0,2 lijkt hij al op 45
        // graden te staan, dat zou ik eerder op 30 graden zetten") — de
        // rotatiehoek werd tot nu toe gestuurd door latest.trendMgdlPerMin
        // (de per-MINUUT-genormaliseerde helling van de sensor-driver), die
        // een ANDERE grootheid is dan de hierboven getoonde delta-tekst (het
        // rauwe mmol-verschil met de vorige meting). Bij de simulator, die
        // kennelijk vaker dan elke 5 minuten een meting geeft, versterkt die
        // normalisatie een klein delta tot een grote hoek. Nu wordt dezelfde
        // deltaMgdl (in mmol) gebruikt die ook de teksts hierboven bepaalt,
        // met nieuwe, in mmol gekalibreerde drempels — zie kdoc bij
        // TrendChevronCanvas.
        TrendChevronCanvas(
            deltaMmol = deltaMgdl?.mgdlToMmol()?.toFloat() ?: 0f,
            tint = ringColor,
            ringSize = ringSize,
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * 18/08/2026 (editor, RONDE 113) — losse, generieke (sensor-onafhankelijke)
 * regel onder de sensor-infokaart (CompactSensorSummary) die de
 * verwerkingsstappen van een meting van boven naar beneden — dat wil zeggen:
 * van links naar rechts, elke kolom is het eindresultaat van weer één stap
 * meer — naast elkaar toont: ruw -> [gekalibreerd ->] gefilterd.
 *
 * Ontstaan uit een langer gesprek (zie SlotStatusContent's aanroep hierboven
 * voor de aanroepcontext) dat begon bij de vraag of de smoothing-sterkte
 * instelbaar moest worden (sterkte-slider/3-knoppen), maar strandde op het
 * ontbreken van een manier om het EFFECT van zo'n instelling ooit te
 * beoordelen — er was nergens een plek die liet zien wat smoothing nu
 * eigenlijk met een meting doet. Dat werd het eigenlijke onderwerp: niet een
 * sterkte-dial, maar zichtbaarheid van de bestaande pijplijn.
 *
 * Twee bewuste ontwerpkeuzes, beide expliciet door de gebruiker gecorrigeerd
 * t.o.v. mijn eerste voorstel:
 *  1. De oude raw-indicator in de BgRingDisplay-cirkel (kleine open cirkel +
 *     ruwe waarde) is VERWIJDERD, niet hierheen verplaatst — die was toch al
 *     dubbelop met de open-bolletjes-lijn die GlucoseChart.kt al toont.
 *  2. Zichtbaarheid is UITSLUITEND gekoppeld aan de nieuwe Settings-
 *     schakelaar "Show filtered data on main screen"
 *     (AppSettings.showFilteredPipelineOnMainScreen), NIET aan of de waarden
 *     daadwerkelijk verschillen. Mijn eerste voorstel (auto-tonen bij
 *     verschil, zoals de net verwijderde raw-indicator deed) werd expliciet
 *     afgewezen: dat zou exact dezelfde "onzichtbare, niet-instelbare
 *     drempel"-kritiek herintroduceren die eerder in hetzelfde gesprek al de
 *     kern van het bezwaar tegen een sterkte-slider was.
 *
 * Kolommen: [calibrationEnabled] bepaalt of de gekalibreerd-kolom getoond
 * wordt (bij kalibratie uit is [GlucoseReading.calibratedMgdl] toch gelijk
 * aan de ruwe waarde — een aparte kolom zou daar niets aan toevoegen). De
 * caller (SlotStatusContent) toont deze composable alleen als smoothing zelf
 * aan staat, dus de gefilterd-kolom (`reading.glucoseMgdl`, het eindresultaat
 * na eventuele kalibratie+smoothing) is hier altijd aanwezig.
 */
@Composable
private fun PipelineValuesRow(
    reading: GlucoseReading,
    calibrationEnabled: Boolean,
    unit: GlucoseUnit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        PipelineValueColumn(label = "Raw", value = reading.rawSensorMgdl, unit = unit)
        if (calibrationEnabled) {
            PipelineValueColumn(label = "Calibrated", value = reading.calibratedMgdl, unit = unit)
        }
        PipelineValueColumn(label = "Filtered", value = reading.glucoseMgdl, unit = unit)
    }
}

@Composable
private fun PipelineValueColumn(label: String, value: Double, unit: GlucoseUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        // 21/08/2026 (editor, RONDE 118) — formatForDisplayPrecise() i.p.v.
        // formatForDisplay(): zie Units.kt's kdoc daar. Alleen hier, deze rij
        // bestaat om de pijplijnstappen te kunnen onderscheiden.
        Text(
            value.formatForDisplayPrecise(unit),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Groen binnen het normale bereik (4-10 mmol/L, zelfde grens als de
 *  gevulde band in de grafiek), amber net erbuiten, rood ver erbuiten.
 *
 *  13/08/2026 (editor, RONDE 104) — mg/dL-grenzen i.p.v. de oude mmol-
 *  getallen (4,0/10,0/3,0/14,0 mmol -> 70/180/54/252 mg/dL), zie
 *  GlucoseChart.kt's klasse-kdoc voor dezelfde redenering. Puur een
 *  interne drempel op de altijd-mg/dL-waarde — hangt niet af van de
 *  weergave-eenheid, dus geen `unit`-parameter nodig. */
@Composable
private fun bgRangeColor(glucoseMgdl: Double): Color {
    return when {
        glucoseMgdl in 70.0..180.0 -> MaterialTheme.colorScheme.primary
        glucoseMgdl in 54.0..252.0 -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.error
    }
}

/** 13/08/2026 (editor, RONDE 104) — eenheid-bewust: mg/dL toont een heel
 *  getal met teken, mmol/L de bestaande 1-decimaal-opmaak. */
private fun formatDelta(deltaMgdl: Double, unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> "%+.0f".format(deltaMgdl)
    GlucoseUnit.MMOL -> "%+.1f".format(deltaMgdl.mgdlToMmol())
}

/**
 * ============================================================================
 * StatusScreen — compacte, sensortype-bewuste samenvatting boven de BG-grafiek
 * ============================================================================
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek: "het beknopte status schermpje
 * boven de Bg grafiek [...] moet dus ook sensortype specifieke info kunnen
 * krijgen indien van toepassing [...] met een knop/i erop waarmee het status
 * scherm van die sensor wordt geopend") — dit vervangt het vroegere, altijd-
 * volledig-uitgeklapte SensorInfoBlock op het startscherm (zie kdoc daar,
 * hieronder — dat blijft bestaan, maar nu alleen nog gebruikt op de
 * type-specifieke statusschermen zelf, niet meer hier).
 *
 * Bewust ÉÉN samenvattende regel i.p.v. een hele rijenlijst: StatusScreen
 * zelf kent de sensortype-specifieke VELDEN niet (geen serienummer/
 * batterijspanning-kennis hier) — dat weet alleen het bestand van dat type
 * zelf (dexcomG6CompactSummaryText() in DexcomG6StatusScreen.kt,
 * careSensAirCompactSummaryText() in CareSensAirStatusScreen.kt). Voor de
 * simulator (geen eigen statusscherm-bestand, gewoon de bestaande
 * SimulatorSetupScreen) volstaat de generieke connectionStatusText().
 */
@Composable
private fun CompactSensorSummary(
    selectedSensor: SensorType?,
    connectionState: ConnectionState,
    nowMs: Long,
    onOpenDetails: () -> Unit,
    slot: SensorSlot
) {
    if (selectedSensor == null) {
        Text(
            "No sensor chosen yet — tap \"Sensor\" above.",
            color = MaterialTheme.colorScheme.secondary
        )
        return
    }

    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    // 09/08/2026 (editor, RONDE 75, op verzoek — "dan wil ik bij beide (en
    // ook de toekomstige) sensoren daar onder ook de looptijd van de sensor.
    // Dus de huidige tijd min de starttijd uitgedrukt in dagen en uren") —
    // `summaryText` (de bestaande statusregel) en `sensorStartedAtMs` (de
    // bevestigde sensor-startmoment, waaruit de looptijd hieronder berekend
    // wordt via sensorRuntimeText()) samen per sensortype bepaald — bewust
    // ÉÉN when-blok i.p.v. twee losse, zodat toekomstige sensortypes
    // (CareSens Air/G6's buren, zie #73/#74) beide tegelijk aanleveren en
    // niemand vergeet de tweede regel toe te voegen.
    val (summaryText, sensorStartedAtMs) = when (selectedSensor) {
        SensorType.DEXCOM_G6 -> {
            // 09/08/2026 (editor, RONDE 65) — zie dexcomG6StatusText()'s kdoc
            // in DexcomG6StatusScreen.kt: was dexcomG6CompactSummaryText()
            // (ruwe ConnectionState.Error-tekst + batterijspanning
            // aangeplakt), nu dezelfde xDrip-stijl prioriteit als het volle
            // statusscherm (sending/started+warmup/last-connected), zodat
            // beide plekken altijd hetzelfde zeggen.
            val lastConnectedAtMs by settings.dexcomG6LastConnectedAtMs(slot).collectAsState(initial = null)
            val pendingSensorStartCode by settings.dexcomG6PendingNewSensorCode(slot).collectAsState(initial = null)
            val sessionStartConfirmedAtMs by settings.dexcomG6SessionStartConfirmedAtMs(slot).collectAsState(initial = null)
            // 09/08/2026 (editor, RONDE 66) — zelfde transmitter-gerapporteerde
            // CalibrationState + echte warmupSeconds als het volle statusscherm
            // (zie DexcomG6StatusScreen.kt's dexcomG6StatusText() kdoc), i.p.v.
            // de oude vaste 2h-aanname — houdt dit kaartje en dat scherm gelijk.
            val lastCalibrationStateRaw by settings.dexcomG6LastCalibrationState(slot).collectAsState(initial = null)
            val warmupSeconds by settings.dexcomG6WarmupSeconds(slot).collectAsState(initial = null)
            // 09/08/2026 (editor, RONDE 71) — zie dexcomG6StatusText()'s kdoc
            // in DexcomG6StatusScreen.kt: houdt dit compacte kaartje en het
            // volle statusscherm consistent bij herhaalde sessie-start-
            // mislukkingen.
            val sessionStartFailCount by settings.dexcomG6SessionStartFailCount(slot).collectAsState(initial = 0)
            // 09/08/2026 (editor, RONDE 74) — zie dexcomG6StatusText()'s kdoc:
            // nodig zodat dit compacte kaartje dezelfde fallback-opwarm-
            // aftelling toont als het volle statusscherm, i.p.v. daar zonder
            // aftelling te blijven hangen.
            val typicalSensorDays by settings.dexcomG6TypicalSensorDays(slot).collectAsState(initial = null)
            // 22/08/2026 (editor, RONDE 124, CRITICAL FIX — op verzoek na
            // live-melding: "de info die terug komt klopt niet", dit
            // kaartje toonde het generieke "no response from the
            // transmitter (timeout)" terwijl het volle statusscherm
            // gelijktijdig de ECHTE reden ("invalid") toonde) — deze drie
            // Ronde-120-parameters ontbraken hier sinds hun introductie:
            // dexcomG6StatusText() valt zonder ze terug op de timeout-tekst,
            // ongeacht de daadwerkelijke infoCode. Zie
            // DexcomG6StatusScreen.kt's identieke collectAsState-aanroepen
            // voor dezelfde drie velden.
            val lastSessionStartInfoCode by settings.dexcomG6LastSessionStartInfoCode(slot).collectAsState(initial = null)
            val lastSessionStartAttemptAtMs by settings.dexcomG6LastSessionStartAttemptAtMs(slot).collectAsState(initial = null)
            val readingStoreForStatus = remember { GlucoseReadingStore(context) }
            // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — was
            // `latestReading(SensorType.DEXCOM_G6)`: zie GlucoseReadingStore.
            // kt's kdoc bij latestReading() voor de volledige analyse.
            val lastRealReadingForStatus by readingStoreForStatus.latestReading(slot = slot).collectAsState(initial = null)
            val text = dexcomG6StatusText(
                connectionState = connectionState,
                lastConnectedAtMs = lastConnectedAtMs,
                pendingSensorStartCode = pendingSensorStartCode,
                sessionStartConfirmedAtMs = sessionStartConfirmedAtMs,
                lastCalibrationStateRaw = lastCalibrationStateRaw,
                warmupSeconds = warmupSeconds,
                nowMs = nowMs,
                sessionStartFailCount = sessionStartFailCount,
                typicalSensorDays = typicalSensorDays,
                lastSessionStartInfoCode = lastSessionStartInfoCode,
                lastSessionStartAttemptAtMs = lastSessionStartAttemptAtMs,
                lastRealReadingAtMs = lastRealReadingForStatus?.timestampMs
            )
            // Looptijd = sinds de bevestigde sessionStart (zelfde moment als
            // de "Started"-rij op DexcomG6StatusScreen.kt), niet sinds de
            // eerste verbinding — dat is exact wanneer de FYSIEKE sensor
            // volgens de app is gestart.
            text to sessionStartConfirmedAtMs
        }
        SensorType.CARESENS_AIR -> {
            val lastConnectedAtMs by settings.careSensAirLastConnectedAtMs(slot).collectAsState(initial = null)
            // 09/08/2026 (editor, RONDE 75) — zie careSensAirCompactSummaryText()'s
            // kdoc: het serienummer wordt hier niet meer aangeplakt, dus de
            // `scan`-state hoeft hier niet meer opgehaald te worden voor de
            // statusregel — wél nog voor de looptijd hieronder is dat niet
            // nodig, careSensAirSensorStartedAtMs staat los van de scan-data.
            val sensorStartedAtMs by settings.careSensAirSensorStartedAtMs(slot).collectAsState(initial = null)
            val text = careSensAirCompactSummaryText(connectionState, lastConnectedAtMs)
            text to sensorStartedAtMs
        }
        else -> connectionStatusText(connectionState) to null
    }
    val runtimeText = sensorRuntimeText(sensorStartedAtMs, nowMs)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetails),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(selectedSensor.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (runtimeText != null) {
                    Text(
                        runtimeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            IconButton(onClick = onOpenDetails) {
                Icon(Icons.Filled.Info, contentDescription = "Sensor details")
            }
        }
    }
}

/**
 * 09/08/2026 (editor, RONDE 75, op verzoek — "dan wil ik bij beide (en ook
 * de toekomstige) sensoren daar onder ook de looptijd van de sensor. Dus de
 * huidige tijd min de starttijd uitgedrukt in dagen en uren") — bewust hier,
 * op top-level i.p.v. binnen CompactSensorSummary() zelf, zodat een
 * toekomstig sensortype (Accu-Chek SmartGuide/G7, zie taken #73/#74) 'm
 * simpelweg kan hergebruiken zonder duplicatie. `null` bij een onbekende
 * starttijd (nog geen sensor gestart) — dan toont CompactSensorSummary()
 * gewoon geen derde regel, i.p.v. een verwarrende "0d 0h".
 */
private fun sensorRuntimeText(startedAtMs: Long?, nowMs: Long): String? {
    if (startedAtMs == null) return null
    val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0)
    val totalHours = elapsedMs / (60 * 60 * 1000L)
    val days = totalHours / 24
    val hours = totalHours % 24
    return "Running ${days}d ${hours}h"
}

/**
 * 30/07/2026 (editor, na feedback: sensorinfo apart van de BG-weergave,
 * zoals in AAPS) — "Sensor started" stond eerder in de BG-kaart; nu hier
 * samen met de overige metadata. Sensor-nummer/echte einddatum zijn nog
 * niet overal beschikbaar (alleen de simulator is af, die heeft geen vast
 * device-serienummer of vaste levensduur) — "Device" toont het opgeslagen
 * adres (voor de simulator altijd "simulator"), "End date" is een
 * plaatshouder totdat CareSens Air/G7/Accu-Chek hun eigen, echte verwachte
 * levensduur kunnen aanleveren.
 *
 * 31/07/2026 (editor, na feedback over de menu-indeling) — was `private`;
 * nu ook gebruikt door meerdere schermen, dus geen file-scoped private
 * meer. Kotlin's `private` op top-level is file-scoped, niet
 * package-scoped — vandaar dat dit expliciet moest veranderen, ook al
 * staan alle bestanden in hetzelfde package.
 *
 * 09/08/2026 (editor, RONDE 64) — was rechtstreeks (volledig uitgeklapt) op
 * StatusScreen zelf; StatusScreen gebruikt nu de compactere
 * CompactSensorSummary() hierboven. Dit blok leeft door als de RENDERING
 * die de type-specifieke statusschermen (DexcomG6StatusScreen.kt/
 * CareSensAirStatusScreen.kt) zelf aanroepen — bewust een dom,
 * geparametriseerd component (geen if/else op sensortype hierbinnen), zodat
 * de "welk veld hoort bij welk type"-kennis alleen bij de AANROEPER zit.
 */
@Composable
fun SensorInfoBlock(
    selectedSensor: SensorType?,
    connectionState: ConnectionState,
    latest: GlucoseReading?,
    endDateText: String = "—",
    // 02/08/2026 (editor, op verzoek: "type en nr sensor met start en
    // einddatum ... connected ... laatste connecting tijd") — vier nieuwe,
    // optionele parameters (allemaal met een neutrale standaardwaarde, dus
    // geen bestaande aanroeper breekt): serialNumber (uit de barcode-scan,
    // AppSettings.careSensAirScan — dezelfde fysieke sensor als waarmee
    // verbonden wordt, zie CareSensAirDriver's eigen serienummer-controle),
    // sensorStartedAtMs (het ECHTE fysieke activatiemoment via de sensor
    // zelf i.p.v. wanneer de app voor het eerst verbond — zie kdoc bij
    // AppSettings.Keys.CARESENS_SENSOR_STARTED_AT_MS), en
    // lastConnectedAtMs (laatste geslaagde BLE-verbinding, apart van "Xm
    // ago" dat over de laatste MEETWAARDE gaat).
    //
    // 02/08/2026 (editor, op verzoek: "je hebt de expiry datum weer
    // opgenomen maar die is alleen interessant bij plaatsing sensor om te
    // checken maar dan lees je hem gewoon op de verpakking dus hij hoeft
    // niet op het scherm getoond te worden") — was hier ook een
    // packageExpiryText-parameter (fabrieks-/verpakkingsvervaldatum uit de
    // barcode) met een eigen "Package expiry"-rij; beide vervallen — die
    // datum staat toch al op de doos zelf, geen reden om 'm ook nog op dit
    // scherm te herhalen.
    serialNumber: String? = null,
    sensorStartedAtMs: Long? = null,
    lastConnectedAtMs: Long? = null,
    // 08/08/2026 (editor, RONDE 56, op verzoek — "de status info die xdrip
    // bij de G6 ook weergeeft over de transmitter zoals laatste verbinding,
    // de spanning van de batterij, de temperatuur") — G6-specifiek, net als
    // serialNumber/sensorStartedAtMs hierboven zijn voor CareSens Air.
    // Ruwe transmitter-eenheden (mV/°C), rechtstreeks van
    // DexcomG6Protocol.BatteryInfoRx — geen verdere interpretatie/kleuring
    // hier, dat kan een latere ronde toevoegen als daar behoefte aan blijkt.
    batteryVoltageA: Int? = null,
    batteryVoltageB: Int? = null,
    temperatureC: Int? = null,
    // 02/08/2026 (editor, op verzoek: "info direct op het hoofdscherm
    // tonen ... samen met sensor connected") — optioneel, zodat
    // StatusScreen.kt dit blok nu ZELF op het startscherm kan tonen (tikbaar
    // -> SensorManagementScreen voor de acties) i.p.v. alleen het eerder
    // hier gebruikte compacte SensorSummaryCard. SensorManagementScreen.kt
    // zelf geeft niets door (daar staat dit blok al op een eigen scherm,
    // een geneste klik-actie zou daar geen zin hebben).
    onClick: (() -> Unit)? = null,
    // 09/08/2026 (editor, RONDE 65, op verzoek — "no connection wil ik ook
    // niet zien") — optioneel: als gezet, vervangt dit de "Status"-rij
    // hieronder VOLLEDIG (tekst én kleur — geen rode foutkleuring meer),
    // i.p.v. de generieke connectionStatusText(connectionState) die anders
    // rechtstreeks een ConnectionState.Error's ruwe boodschap zou tonen.
    // Alleen DexcomG6StatusScreen.kt geeft dit door (zie dexcomG6StatusText()
    // daar) — CareSens Air blijft ongewijzigd op de generieke tekst, die
    // daar niet hetzelfde "elke disconnect = Error"-probleem heeft.
    statusOverrideText: String? = null
) {
    if (selectedSensor == null) {
        Text(
            "No sensor chosen yet — tap \"Choose sensor\" below.",
            color = MaterialTheme.colorScheme.secondary
        )
        return
    }

    Card(
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InfoRow("Sensor type", selectedSensor.displayName)
            if (serialNumber != null) {
                InfoRow("Serial number", serialNumber)
            }
            InfoRow(
                "Status",
                statusOverrideText ?: connectionStatusText(connectionState),
                valueColor = if (statusOverrideText == null && connectionState is ConnectionState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    null
                }
            )
            // 02/08/2026 (editor, op verzoek: "ipv het kanaal kan daar beter
            // het serienr van de sensor staan") — de losse "Device"/"Device
            // (connecting)"-regel die hier stond (het ruwe Bluetooth-MAC-
            // adres, bv. "2C:D3:AD:54:BF:AA") is vervallen: dat adres zegt de
            // gebruiker niets, en "Serial number" hierboven (het echte,
            // op de sensor zelf afgedrukte nummer) is al het bruikbare
            // identificerende veld. Het MAC-adres zelf blijft gewoon in
            // logcat te vinden (zie CareSensAirDriver.kt) voor het geval dat
            // ooit weer nodig is bij het debuggen van een koppelprobleem.
            //
            // 02/08/2026 (editor, op verzoek: "start en eind tijd ... op 1
            // regel") — was twee losse rijen ("Started"/"End date"); dat las
            // ook los van elkaar niet lekker (twee keer bijna dezelfde
            // datum-tijd-notatie onder elkaar). sensorStartedAtMs komt uit
            // AppSettings.careSensAirSensorStartedAtMs, gezet zodra de
            // handshake ver genoeg komt (0xC0/2-antwoord, zie
            // CareSensAirDriver.kt) — dus normaal al zichtbaar ruim vóór de
            // eerste meting; alleen tijdens de allereerste, nog lopende
            // koppelpoging van een nooit eerder gekoppelde sensor staat dit
            // kort op "—" totdat die stap voltooid is. endDateText is
            // start + 15 dagen (CareSens Air's eigen draagtijd, zie
            // SensorManagementScreen.kt), in hetzelfde dd-MM HH:mm-formaat
            // (geen jaartal, zie de aanroeper).
            InfoRow(
                "Started – End",
                "${sensorStartedAtMs?.let { formatTime(it) } ?: "—"} – $endDateText"
            )
            if (lastConnectedAtMs != null) {
                InfoRow("Last connected", formatTime(lastConnectedAtMs))
            }
            // 08/08/2026 (editor, RONDE 56) — zie kdoc bij de parameters
            // hierboven; alleen zichtbaar zodra er daadwerkelijk een
            // batterijantwoord binnen is geweest (DexcomG6Driver.kt vraagt
            // dit ~elke 8 uur op).
            if (batteryVoltageA != null && batteryVoltageB != null) {
                InfoRow("Battery voltage", "$batteryVoltageA mV / $batteryVoltageB mV")
            }
            if (temperatureC != null) {
                InfoRow("Transmitter temperature", "$temperatureC °C")
            }
        }
    }
}

// 31/07/2026 (editor) — was `private`; nu ook gebruikt door
// CareSensAirScanScreen.kt (zie kdoc bij SensorInfoBlock hierboven voor
// dezelfde reden: top-level `private` is file-scoped in Kotlin).
@Composable
fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 30/07/2026 (editor, geschiedenis van 4 pogingen om de trendpijl tegen de
 * ring geplakt te laten lijken, na herhaalde feedback dat hij losraakte):
 * 1) `Icon` + `graphicsLayer(transformOrigin=...)` — compileerde niet op
 *    deze build ("Unresolved reference 'graphicsLayer'").
 * 2) `Icon` + dubbelbrede onzichtbare "pivot-doos" (CenterEnd-uitlijning +
 *    offset + rotate) — compileerde wel, maar gaf in de praktijk NIET het
 *    bedoelde resultaat.
 * 3) Canvas + zelf uitgerekende driehoek, maar met een VAST aanhechtpunt
 *    (altijd rechts, 3 uur) waarvan alleen de PUNT meedraaide — bleek
 *    achteraf (bevestigd via een schets, zie gesprek) niet wat er gevraagd
 *    was: dat oogt als "de driehoek draait om zijn eigen as", niet als
 *    "ring en driehoek draaien samen".
 * 4) DIT — bevestigd gewenst gedrag: ring en driehoekje vormen samen ÉÉN
 *    star geheel dat om het MIDDELPUNT VAN DE RING draait. Concreet: het
 *    AANHECHTPUNT zelf verschuift met de hoek mee langs de rand van de
 *    ring (bij 0° rechts/3 uur, bij -90° bovenaan/12 uur, bij +90°
 *    onderaan/6 uur, enzovoort) — i.p.v. altijd op dezelfde plek te
 *    blijven zitten. Het driehoekje wijst daarbij altijd RADIAAL NAAR
 *    BUITEN vanaf dat (verschuivende) aanhechtpunt, met zijn BASIS (de
 *    lijn tussen de twee brede hoekpunten) tegen de rand van de ring aan
 *    (rakend aan de cirkel op dat punt) en de PUNT naar buiten wijzend —
 *    dus de basis tegen de ring, niet de punt, zoals expliciet gevraagd.
 *    Een cirkel ziet er identiek uit ongeacht rotatie om zijn eigen
 *    middelpunt, dus het "ring+driehoekje draaien samen"-effect ontstaat
 *    volledig door alleen het aanhechtpunt (en de wijsrichting, dezelfde
 *    hoek) mee te laten schuiven — de ring zelf hoeft niet apart getekend
 *    of geroteerd te worden.
 *
 * (Sign-fix: SensorDriver.kt's kdoc zegt expliciet dat trendMgdlPerMin
 * dezelfde richting als xDrip's "slope" heeft — positief = stijgend,
 * negatief = dalend — dus hoek 0°=rechts/vlak, negatieve hoek=omhoog
 * (stijgend), positieve hoek=omlaag (dalend), in screen-coördinaten waar Y
 * omlaag toeneemt. Dezelfde tekens gelden voor deltaMmol (rauw
 * mmol-verschil met de vorige meting), zie kdoc bij BgRingDisplay.)
 *
 * 31/07/2026 (editor, ronde 14, na feedback: "bij +0,2 lijkt hij al op 45
 * graden te staan, dat zou ik eerder op 30 graden zetten" + "driehoek moet
 * dichter op de cirkel, geen zwart ertussen" + "basis mag iets groter dan
 * de hoogte") — drie aanpassingen: (a) drempels nu gebaseerd op deltaMmol
 * i.p.v. trendMgdlPerMin, zie kdoc bij BgRingDisplay voor waarom dat een
 * andere/kleinere grootheid is; (b) aanhechtpunt-straal met de helft van de
 * ringrand (6dp) naar binnen getrokken, zodat de basis van de driehoek in
 * de gekleurde rand overlapt i.p.v. er nét buiten te raken; (c) hoogte
 * verkleind naar 20dp en basis vergroot naar 28dp (was gelijk, 26dp/26dp).
 */
@Composable
private fun TrendChevronCanvas(
    deltaMmol: Float,
    tint: Color,
    ringSize: Dp,
    modifier: Modifier = Modifier
) {
    val rotationDegrees = when {
        deltaMmol >= 0.8f -> -90f
        deltaMmol >= 0.5f -> -60f
        deltaMmol >= 0.3f -> -45f
        deltaMmol >= 0.1f -> -30f
        deltaMmol > -0.1f -> 0f
        deltaMmol > -0.3f -> 30f
        deltaMmol > -0.5f -> 45f
        deltaMmol > -0.8f -> 60f
        else -> 90f
    }
    Canvas(modifier = modifier) {
        val ringRadiusPx = ringSize.toPx() / 2f
        // Middelpunt van de ring binnen deze Canvas (die exact de ouder-Box
        // vult, zie matchParentSize in BgRingDisplay): de ring-Box staat
        // daar horizontaal aan het begin (x=0, dus middelpunt op
        // ringRadiusPx) en verticaal gecentreerd (Alignment.CenterStart),
        // dus zijn middelpunt-y is gewoon het midden van de HELE Canvas-
        // hoogte, niet per se ringRadiusPx (de Canvas is inmiddels hoger
        // dan de ring zelf, zie kdoc bij BgRingDisplay over de verticale
        // marge voor de 12/6-uur-standen).
        val centerX = ringRadiusPx
        val centerY = size.height / 2f
        val angleRad = rotationDegrees * (PI / 180.0)
        // Radiale richting bij deze hoek — zowel de plek van het
        // aanhechtpunt op de rand ALS de wijsrichting van de punt gebruiken
        // dezelfde (dirX, dirY): bij 0° is dat rechts, bij -90° omhoog, bij
        // +90° omlaag — het aanhechtpunt schuift dus letterlijk mee rond de
        // rand naarmate de hoek verandert.
        val dirX = cos(angleRad).toFloat()
        val dirY = sin(angleRad).toFloat()
        // Aanhechtpunt-straal iets kleiner dan de ring-straal (half de
        // 6dp-randbreedte naar binnen) — zie kdoc hierboven: sluit het
        // eerder zichtbare gaatje tussen ring en driehoekbasis.
        val attachRadiusPx = ringRadiusPx - 3.dp.toPx()
        val attachX = centerX + dirX * attachRadiusPx
        val attachY = centerY + dirY * attachRadiusPx
        val triangleLengthPx = 20.dp.toPx()
        val baseHalfWidthPx = 14.dp.toPx()
        val tipX = attachX + dirX * triangleLengthPx
        val tipY = attachY + dirY * triangleLengthPx
        // Loodrecht op de radiale richting (dirX, dirY) is (-dirY, dirX) —
        // standaard 90°-rotatie van een 2D-vector — raakt de cirkel op dat
        // punt (tangentieel), dus de basis ligt vlak tegen de rand aan.
        val baseAx = attachX - dirY * baseHalfWidthPx
        val baseAy = attachY + dirX * baseHalfWidthPx
        val baseBx = attachX + dirY * baseHalfWidthPx
        val baseBy = attachY - dirX * baseHalfWidthPx
        val path = Path().apply {
            moveTo(baseAx, baseAy)
            lineTo(tipX, tipY)
            lineTo(baseBx, baseBy)
            close()
        }
        drawPath(path, color = tint)
    }
}

// isIgnoringBatteryOptimizations()/requestIgnoreBatteryOptimizations() zijn
// verhuisd naar MainActivity.kt — die controleert nu bij elke start opnieuw
// (met een 24u-cooldown) i.p.v. via een terugkerende menu-optie hier, zie
// kdoc daar en AppSettings.batteryOptimizationLastPromptedAtMs.

/** 30/07/2026 (editor, na feedback: AAPS-stijl) — was "Just now"/"1 minute
 *  ago"/"$n minutes ago"; nu altijd het compacte "Xm ago"-formaat dat AAPS
 *  ook gebruikt. */
private fun minutesAgoText(timestampMs: Long, nowMs: Long): String {
    val minutesAgo = TimeUnit.MILLISECONDS.toMinutes(nowMs - timestampMs).coerceAtLeast(0)
    return "${minutesAgo}m ago"
}

private fun formatTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "unknown"
    return SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

// 02/08/2026 (editor, op verzoek: "bij status kan dan beter alleen
// 'connected', de tussen haakjes caresensair voegt niks toe") — was
// "Connected" + " ($deviceName)" (bv. "Connected (CareSens Air)"); de
// sensortype staat al apart op de "Sensor type"-rij hierboven, dus die
// herhaling hier voegde niets toe.
private fun connectionStatusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Not connected"
    is ConnectionState.Scanning -> "Searching for sensor…"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Error -> state.message
}
