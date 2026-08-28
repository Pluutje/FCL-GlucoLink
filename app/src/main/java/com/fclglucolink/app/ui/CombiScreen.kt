package com.fclglucolink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.calibration.CalibrationStore
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.SensorSlot
import kotlinx.coroutines.flow.flowOf

/**
 * ============================================================================
 * FCLGlucoLink — CombiScreen: het nieuwe, tab-gebaseerde startscherm
 * ============================================================================
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317, op
 * oorspronkelijk verzoek — "onderaan een rij met settings en info knop,
 * daarboven 3 tabbladen die elk 1/3 van de breedte innemen: [sensor van
 * slot A] / [sensor van slot B] / Combi, tabblad-kleur groen als die slot
 * naar AAPS zendt, anders rood") — vervangt StatusScreen.kt als het
 * STARTSCHERM van de app (ROUTE_COMBI, zie FclGlucoLinkNavHost.kt).
 * StatusScreen.kt zelf blijft gewoon bestaan (op zichzelf staand, werkend
 * scherm — zie kdoc daar) — de eigenlijke per-slot inhoud die dit scherm
 * per tabblad toont is SlotStatusContent() uit dat bestand, hier hergebruikt
 * met [onOpenSettings] = null (Settings staat hier ÉÉN keer gedeeld in de
 * onderste rij, niet per tabblad — zie kdoc bij SlotStatusContent()).
 *
 * Tabblad-kleur: groen zodra die slot de AAPS-zendende slot is
 * (AppSettings.aapsActiveSlot, zie SettingsScreen.kt's 3-weg-keuze), anders
 * rood — ALLEBEI rood als er niets zendt (aapsActiveSlot == null). Het
 * "Combi"-tabblad zelf heeft bewust geen eigen groen/rood: dat tabblad
 * vertegenwoordigt geen ENKELE slot, dus de AAPS-indicator (die per
 * definitie over precies één van de twee slots gaat) is er niet op van
 * toepassing — neutrale kleur.
 *
 * [onSwitchSensorType]/[onOpenSensorStatus] nemen bewust een [SensorSlot] mee
 * — deze knoppen moeten immers de JUISTE slot's koppel-/statusscherm openen
 * (Slot A's tabblad -> Slot A's schermen, Slot B's tabblad -> Slot B's eigen
 * schermen), niet allebei stiekem Slot A beheren. Zie FclGlucoLinkNavHost.kt's
 * kdoc voor hoe de onderliggende routes nu ook per slot geparametriseerd zijn.
 *
 * 10/08/2026 (editor, RONDE 80, op verzoek na live-test met 2 sensoren) —
 * TWEE dingen herzien t.o.v. de eerste versie hierboven:
 *
 * 1) De onderste rij's ronde "(i)"-icoonknop is vervangen door een duidelijk
 *    gelabelde "Manual"-knop (`onOpenManual` hieronder) — zie CombiTab
 *    vervalt onder, dit betreft de rij met Settings/Manual.
 *
 * 2) Het hele tabblad-uiterlijk is herbouwd: de standaard Material3 `TabRow`/
 *    `Tab` (met zijn ingebouwde groene onderstreping voor "geselecteerd") is
 *    vervangen door een eigen rij van 3 afgeronde, licht-uit-elkaar-liggende
 *    "chips" (zie [CombiTabChip] hieronder) — reden: die ingebouwde groene
 *    streep botste visueel met/leek te veel op de groen/rood-AAPS-tint,
 *    waardoor niet meer duidelijk was welk tabblad ACTIEF (geselecteerd) was.
 *    Nu zijn de twee signalen bewust losgekoppeld: WELK tabblad geselecteerd
 *    is, wordt getoond door een helderdere/"oplichtende" chip-achtergrond;
 *    WELKE slot naar AAPS zendt, wordt getoond door een dun gekleurd streepje
 *    (groen/rood) onderaan die chip — niet meer de hele achtergrond-tint van
 *    de vorige versie. Tekst is nu ook expliciet gecentreerd met
 *    `fillMaxWidth()` + `TextAlign.Center` (loste tevens het gemelde "BG
 *    simulator"-uitlijnprobleem op — de oude versie liet Tab() zelf de
 *    breedte/uitlijning van zijn content bepalen, wat bij een langere
 *    tekst als "BG simulator" niet netjes gecentreerd uitkwam).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombiScreen(
    onSwitchSensorType: (SensorSlot) -> Unit,
    onOpenSensorStatus: (SensorSlot) -> Unit,
    onOpenSettings: () -> Unit,
    // 10/08/2026 (editor, RONDE 81, CRITICAL BUGFIX, live-melding —
    // "kalibraties die bij het ene slot worden ingevoerd verschijnen ook bij
    // de andere") — was `() -> Unit`, ÉÉN gedeelde callback voor beide
    // tabbladen, waardoor de "Calibration"-knop op ELK tabblad naar exact
    // dezelfde, niet-slot-bewuste route navigeerde (zie FclGlucoLinkNavHost.kt's
    // uitgebreide kdoc bij ROUTE_CALIBRATION voor de volledige root-cause).
    // Nu net als onSwitchSensorType/onOpenSensorStatus hierboven: neemt de
    // AANTIKKENDE slot mee.
    onOpenCalibration: (SensorSlot) -> Unit,
    onOpenManual: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val selectedSensorA by settings.selectedSensor(SensorSlot.A).collectAsState(initial = null)
    val selectedSensorB by settings.selectedSensor(SensorSlot.B).collectAsState(initial = null)
    val aapsActiveSlot by settings.aapsActiveSlot.collectAsState(initial = null)
    // rememberSaveable i.p.v. gewone remember: overleeft een configuratie-
    // wijziging (bv. rotatie) zonder terug te vallen naar tabblad 0.
    //
    // 28/08/2026 (editor, RONDE 155, op verzoek — "neem dan gelijk de aaps
    // actieve sensor als open slot mee") — start-sentinel -1 i.p.v.
    // meteen 0: op een ECHTE koude start (nieuw process, geen bewaarde
    // staat) triggert de LaunchedEffect hieronder dan éénmalig de opening
    // op de AAPS-zendende slot (Slot B als díe zendt, anders Slot A —
    // inclusief het geval dat geen van beide zendt, zoals nu). Bij een
    // configuratiewijziging (rotatie) of gewoon achtergrond/voorgrond
    // binnen hetzelfde process is [tabIndex] allang gezet (door deze
    // effect zelf of door een latere handmatige tik van de gebruiker), dus
    // blijft die staat gewoon behouden i.p.v. steeds terug te springen.
    var tabIndex by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        if (tabIndex == -1) {
            tabIndex = when (settings.getAapsActiveSlotOnce()) {
                SensorSlot.B -> 1
                else -> 0
            }
        }
    }

    val labelA = selectedSensorA?.displayName ?: SensorSlot.A.displayLabel
    val labelB = selectedSensorB?.displayName ?: SensorSlot.B.displayLabel
    val green = Color(0xFF4CAF50)
    val colorA = if (aapsActiveSlot == SensorSlot.A) green else MaterialTheme.colorScheme.error
    val colorB = if (aapsActiveSlot == SensorSlot.B) green else MaterialTheme.colorScheme.error

    Scaffold(
        topBar = { TopAppBar(title = { Text("FCLGlucoLink") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 10/08/2026 (editor, RONDE 80) — eigen rij i.p.v. Material3
            // TabRow, zie kdoc bij CombiScreen() hierboven. `weight(1f)` op
            // elke chip geeft nog steeds precies "elk 1/3 van de breedte",
            // nu met een klein gat ertussen i.p.v. aaneengesloten segmenten.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CombiTabChip(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = labelA,
                    stripeColor = colorA,
                    modifier = Modifier.weight(1f)
                )
                CombiTabChip(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = labelB,
                    stripeColor = colorB,
                    modifier = Modifier.weight(1f)
                )
                CombiTabChip(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = "Combi",
                    // Geen AAPS-streep: het Combi-tabblad vertegenwoordigt
                    // geen enkele slot, zie kdoc bij CombiScreen().
                    stripeColor = null,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tabIndex) {
                    0 -> SlotStatusContent(
                        slot = SensorSlot.A,
                        onSwitchSensorType = { onSwitchSensorType(SensorSlot.A) },
                        onOpenSensorStatus = { onOpenSensorStatus(SensorSlot.A) },
                        onOpenCalibration = { onOpenCalibration(SensorSlot.A) },
                        onOpenSettings = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> SlotStatusContent(
                        slot = SensorSlot.B,
                        onSwitchSensorType = { onSwitchSensorType(SensorSlot.B) },
                        onOpenSensorStatus = { onOpenSensorStatus(SensorSlot.B) },
                        onOpenCalibration = { onOpenCalibration(SensorSlot.B) },
                        onOpenSettings = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> CombiTabContent(
                        colorA = colorA,
                        colorB = colorB,
                        aapsActiveSlot = aapsActiveSlot,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 10/08/2026 (editor, RONDE 79, letterlijk verzoek: "onderaan een
            // rij met settings en info knop") — ÉÉN gedeelde rij, buiten de
            // per-tabblad-inhoud, zodat Settings/Manual niet per tabblad
            // gedupliceerd hoeven te worden (zie kdoc hierboven bij
            // CombiScreen()).
            //
            // 10/08/2026 (editor, RONDE 80, op verzoek — "Ik wil de i rechts
            // onder vervangen door een knop 'manual'") — was een ronde,
            // icoon-only IconButton (geen tekstlabel, dus niet vanzelf
            // duidelijk wat 'm doet); nu een gewone gelabelde OutlinedButton
            // met "Manual"-tekst (plus hetzelfde help-icoontje ervoor), zelfde
            // patroon als Settings' knop ernaast.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onOpenSettings) {
                    Text("Settings")
                }
                OutlinedButton(onClick = onOpenManual) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manual")
                }
            }
        }
    }
}

/**
 * 10/08/2026 (editor, RONDE 80) — vervangt de vorige `CombiTab` (die Tab()/
 * TabRow hergebruikte, zie kdoc bij CombiScreen() voor waarom die is
 * losgelaten). Een eigen, klein "chip"-composable: afgeronde hoeken
 * ([RoundedCornerShape]), een duidelijk ander (helderder) achtergrondkleurtje
 * zodra [selected] — dát is nu het ENIGE signaal voor "dit tabblad is actief"
 * — en een DUN gekleurd streepje onderaan voor [stripeColor] (groen/rood,
 * `null` = geen streepje) dat volledig los staat van de selectiekleur, precies
 * de gevraagde ontkoppeling tussen "geselecteerd" en "zendt naar AAPS".
 *
 * Tekst: `fillMaxWidth()` + `TextAlign.Center` + `maxLines = 1` +
 * `TextOverflow.Ellipsis` — lost tevens het gemelde "BG simulator"-
 * uitlijnprobleem op (zie kdoc bij CombiScreen()).
 */
@Composable
private fun CombiTabChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    stripeColor: Color?,
    modifier: Modifier = Modifier
) {
    // Bewust laag-contrast/"weinig opvallend" wanneer NIET geselecteerd (op
    // verzoek: "de tabbladkopjes kunnen dan allemaal weinig opvallend
    // behalve de geselecteerde die dan juist oplicht") — gewone surface +
    // gedimde tekst voor de rest.
    //
    // 10/08/2026 (editor, RONDE 82, BUGFIX na live-melding — "het
    // geselecteerde tabblad mag nog wel iets meer opvallen [...] een veel
    // duidelijkere accent kleur") — de EERSTE versie (RONDE 80) gebruikte
    // hier `colorScheme.surfaceVariant` voor geselecteerd vs. `colorScheme.
    // surface` voor de rest, in de veronderstelling dat dat twee verschillende
    // kleuren waren. Bleek niet zo: Theme.kt's DarkColors zet `surfaceVariant
    // = SurfaceDark` EXPLICIET GELIJK aan `surface` (zie de kdoc daar, puur
    // om Material3 Card's eigen te-lichte standaard-surfaceVariant te
    // overschrijven) — dus de "selectie-achtergrond" van RONDE 80 was in de
    // praktijk precies dezelfde kleur als een niet-geselecteerd tabblad, en
    // alleen de tekstkleur verschilde (exact de gemelde klacht: "nu is
    // alleen de titel witter gekleurd"). Nu een ECHTE, duidelijk zichtbare
    // accentkleur — bewust GEEN groen/rood (die betekenen hier al iets
    // anders: de AAPS-zend-status-streep hieronder, zie kdoc bij
    // CombiScreen()) — een neutrale blauwe accent, zodat "geselecteerd" een
    // derde, ondubbelzinnige kleur-signaal blijft t.o.v. "zendt naar AAPS".
    val selectedAccent = Color(0xFF2C4F82)
    val backgroundColor = if (selected) {
        selectedAccent
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (selected) {
        Color(0xFFEAF1FF)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
    ) {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // AAPS-zend-indicator: dun streepje, niet de hele achtergrond (was
        // voorheen een 25%-alpha-tint over het hele tabblad — dat botste
        // visueel met de TabRow-onderstreping en met de selectie-kleuring
        // hierboven). `null` (het Combi-tabblad) krijgt een even hoge, maar
        // transparante Spacer, zodat alle 3 chips exact even hoog blijven.
        if (stripeColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(stripeColor)
            )
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

/**
 * 10/08/2026 (editor, RONDE 79) — het derde tabblad ("Combi"): een
 * gecombineerd overzicht van BEIDE slots naast elkaar, ÉÉN oogopslag i.p.v.
 * heen-en-weer tikken tussen de eerste twee tabbladen.
 *
 * 10/08/2026 (editor, RONDE 80, letterlijk verzoek — "Op het combi tabblad
 * wil ik ook graag een grafiek waarin de beide data sets worden getoond met
 * ieder een eigen kleur de kleur van de lijn kan dan overeen komen met de
 * kleur van de naam balk er boven") — het "bekende, interim-gat" dat hier tot
 * nu stond, is nu gedicht: [DualGlucoseChart] (nieuw, in GlucoseChart.kt)
 * tekent beide slots als twee los ingekleurde lijnen in ÉÉN grafiek, boven de
 * bestaande samenvattingskaarten. [colorA]/[colorB] komen rechtstreeks van
 * CombiScreen() over (dezelfde groen/rood-AAPS-streepjeskleur als op de
 * tabbladkopjes hierboven, zie kdoc bij CombiScreen()) — dat is letterlijk de
 * gevraagde "kleur van de lijn komt overeen met de kleur van de naam balk
 * erboven".
 *
 * Zie CalibrationScreen.kt's kdoc (RONDE 80) voor de reden dat hier expliciet
 * `selectedSensorX?.let { store.recentReadings(slot = SensorSlot.X) } ?: flowOf(
 * emptyList())` staat i.p.v. onvoorwaardelijk te queryen: als er voor deze
 * slot geen sensor gekozen is, is `selectedSensorX` `null`, en moet dat een
 * lege lijst geven i.p.v. de query alsnog uit te voeren — exact dezelfde
 * bugklasse die eerder deze ronde in CalibrationScreen.kt gevonden is. Hier
 * expliciet vermeden.
 *
 * 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — de query zelf is nu
 * gescoped op `slot = SensorSlot.A`/`SensorSlot.B` i.p.v. `sensorType = it`
 * — zie GlucoseReadingStore.kt's kdoc bij recentReadings()/latestReading()
 * voor de volledige analyse (twee gelijktijdig gekoppelde sensoren van
 * HETZELFDE type konden hun metingen anders niet meer uit elkaar houden).
 *
 * 10/08/2026 (editor, RONDE 81, letterlijk verzoek — "het combi tabblad mag
 * boven de grafiek wel een tabelletje krijgen met de volgende data: slot A /
 * slot B daaronder de groene of rode stip met sensor naam, daaronder de
 * laatste Bg waarde en daaronder sending to aaps (voor de sensor die
 * aanstaat)") — nieuw [CombiSlotTable] hieronder, een 2-koloms tabelletje
 * (Slot A/Slot B) BOVEN [DualGlucoseChart], met precies de 3 gevraagde rijen
 * per kolom. Vervangt de eerdere, kleinere [CombiChartLegend] (kleurstipje +
 * sensornaam) van RONDE 80 — die was feitelijk al de eerste rij van dit
 * tabelletje, dus geen aparte legenda meer nodig ernaast.
 */
@Composable
private fun CombiTabContent(
    colorA: Color,
    colorB: Color,
    aapsActiveSlot: SensorSlot?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val store = remember { GlucoseReadingStore(context) }
    val calibrationStore = remember { CalibrationStore(context) }
    val selectedSensorA by settings.selectedSensor(SensorSlot.A).collectAsState(initial = null)
    val selectedSensorB by settings.selectedSensor(SensorSlot.B).collectAsState(initial = null)
    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
    // [GlucoseUnit]-kdoc.
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)

    // 11/08/2026 (editor, RONDE 90, op verzoek: "de ingevoerde vingerprik
    // voor de calibraties ook zichtbaar te maken in de combi curve") —
    // AANGEVINKTE fingerstick-entries van beide slots (elk gescoped op zijn
    // eigen sensor-start-tijd, zie CalibrationStore.kt/AppSettings.kt's
    // RONDE-90-kdoc's), samengevoegd en op `id` ontdubbeld — een vingerprik
    // die voor BEIDE sensoren aangevinkt staat, komt op deze combi-grafiek
    // sowieso maar ÉÉN keer voor (het is dezelfde meting op hetzelfde
    // tijdstip). [sensorStartedAtMsFlow] i.p.v. de initialiserende
    // [getOrInitSensorStartedAtMs] — dit scherm is puur weergave, ook als
    // (nog) geen van beide slots ooit een sensor-sessie had.
    //
    // 22/08/2026 (editor, RONDE 122, CRITICAL FIX — zie
    // AppSettings.effectiveSensorSessionStartedAtMs()'s kdoc) — was
    // `settings.sensorStartedAtMsFlow(...)`: de generieke, NOOIT-per-
    // fysieke-sensor-herziene sleutel, waardoor deze combi-grafiek na een
    // nieuwe-sensor-start fingerstick-markers van een VORIGE fysieke sensor
    // bleef tonen. `effectiveSensorSessionStartedAtMsFlow` is dezelfde
    // passieve, niet-initialiserende Flow-variant, nu wél sensortype-bewust
    // — vandaar de extra `selectedSensorA`/`selectedSensorB`-parameter.
    val sinceMsA by remember(settings, selectedSensorA) {
        selectedSensorA?.let { settings.effectiveSensorSessionStartedAtMsFlow(SensorSlot.A, it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val sinceMsB by remember(settings, selectedSensorB) {
        selectedSensorB?.let { settings.effectiveSensorSessionStartedAtMsFlow(SensorSlot.B, it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val fingersticksAFlow = remember(calibrationStore, selectedSensorA, sinceMsA) {
        val type = selectedSensorA
        val since = sinceMsA
        if (type != null && since != null) calibrationStore.entries(type, since) else flowOf(emptyList())
    }
    val fingersticksBFlow = remember(calibrationStore, selectedSensorB, sinceMsB) {
        val type = selectedSensorB
        val since = sinceMsB
        if (type != null && since != null) calibrationStore.entries(type, since) else flowOf(emptyList())
    }
    val fingersticksA by fingersticksAFlow.collectAsState(initial = emptyList())
    val fingersticksB by fingersticksBFlow.collectAsState(initial = emptyList())
    // 12/08/2026 (editor, RONDE 99 — ECHTE oorzaak gevonden na de Ronde-97/
    // 98-diagnostiek: de logcat liet zien dat de data zelf altijd al klopte
    // (het juiste punt zat gewoon in fingerstickPoints/fingerstickEntries),
    // maar dat de asymmetrie tussen Slot A/B — en het feit dat een net
    // aangevinkt "ook voor de andere sensor"-punt soms verdween — precies
    // matcht met een BEKEND MPAndroidChart-euvel: een LineDataSet's entries
    // moeten oplopend op x-waarde gesorteerd zijn, anders geeft de interne
    // binary-search die de zichtbare-punten-index bepaalt (dezelfde
    // mXBounds-opzoeking als bij de Ronde-96-crash) onbetrouwbare/verkeerde
    // resultaten — een punt kan dan zomaar NIET getekend worden, ook al zit
    // het gewoon in de dataset en binnen de as-grenzen. `(fingersticksA +
    // fingersticksB)` plakt simpelweg ALLE punten van slot A vóór ALLE
    // punten van slot B — beide slots overlappen in tijd, dus die concat is
    // vrijwel nooit chronologisch gesorteerd, ondanks dat fingersticksA en
    // fingersticksB elk afzonderlijk waarschijnlijk wel gesorteerd binnenkomen.
    // Fix: expliciet op timestampMs sorteren NA het samenvoegen/ontdubbelen.
    val fingerstickPoints = remember(fingersticksA, fingersticksB) {
        (fingersticksA + fingersticksB).distinctBy { it.id }
            .sortedBy { it.timestampMs }
            .map { it.timestampMs to it.fingerstickMgdl }
    }
    // 11/08/2026 (editor, RONDE 93 -> RONDE 94) — de tijdelijke Ronde-93-
    // diagnostiek (Log.d met tag FCLFingerstickDebug) die hier stond, heeft
    // z'n werk gedaan: de logcat liet zien dat fingerstickPoints hier altijd
    // al correct gevuld was (de fetch-keten was dus nooit het probleem) — de
    // echte bug bleek in GlucoseChart.kt's DualGlucoseChart te zitten (de
    // as-autoscale hield alleen rekening met de twee sensor-curven, niet met
    // de vingerprik-punten zelf). Zie de RONDE-94-kdoc daar voor de fix.
    // Diagnostiek hier weer verwijderd, geen gedragswijziging in dit bestand.
    // 11/08/2026 (editor, RONDE 95 — na live-melding: "Ik meen me overigens
    // te herinneren dat de x-as over 48 uur verschoven moest kunnen worden
    // en niet 24 uur zoals nu.") — klopte: StatusScreen.kt (de losse
    // per-sensor schermen) gebruikt hier al `hours = 48`
    // (GlucoseReadingStore.kt's eigen default is ook 48), de Combi-tab
    // stond nog op de oudere `hours = 24`. Gelijkgetrokken.
    // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — was `sensorType = it`:
    // zie GlucoseReadingStore.kt's kdoc bij recentReadings()/latestReading()
    // voor de volledige analyse. De null-guard op selectedSensorA/B blijft
    // ONGEWIJZIGD nodig — "geen sensor gekozen voor deze slot" moet nog
    // steeds een lege lijst geven i.p.v. de ongefilterde combinatie van
    // beide slots (slot = null).
    val readingsAFlow = remember(store, selectedSensorA) {
        selectedSensorA?.let { store.recentReadings(hours = 48, slot = SensorSlot.A) } ?: flowOf(emptyList())
    }
    val readingsBFlow = remember(store, selectedSensorB) {
        selectedSensorB?.let { store.recentReadings(hours = 48, slot = SensorSlot.B) } ?: flowOf(emptyList())
    }
    val readingsA by readingsAFlow.collectAsState(initial = emptyList())
    val readingsB by readingsBFlow.collectAsState(initial = emptyList())
    // 10/08/2026 (editor, RONDE 81) — het tabelletje's BG-waarde-rij wil de
    // ECHTE laatste meting, niet zomaar de laatste van [readingsA]/[readingsB]
    // (die zijn niet gegarandeerd op volgorde) — rechtstreeks
    // store.latestReading(sensorType=...) opvragen is hier duidelijker.
    // Zelfde null-guard-reden als hierboven bij readingsAFlow/readingsBFlow.
    val latestAFlow = remember(store, selectedSensorA) {
        selectedSensorA?.let { store.latestReading(slot = SensorSlot.A) } ?: flowOf(null)
    }
    val latestBFlow = remember(store, selectedSensorB) {
        selectedSensorB?.let { store.latestReading(slot = SensorSlot.B) } ?: flowOf(null)
    }
    val latestA by latestAFlow.collectAsState(initial = null)
    val latestB by latestBFlow.collectAsState(initial = null)

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Combined view of both slots. Open the \"${SensorSlot.A.displayLabel}\"/" +
                "\"${SensorSlot.B.displayLabel}\" tabs above for full detail, calibration, " +
                "and management of each sensor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        CombiSlotTable(
            labelA = selectedSensorA?.displayName ?: SensorSlot.A.displayLabel,
            labelB = selectedSensorB?.displayName ?: SensorSlot.B.displayLabel,
            colorA = colorA,
            colorB = colorB,
            bgTextA = latestA?.let { it.glucoseMgdl.formatForDisplayWithUnit(displayUnit) } ?: "—",
            bgTextB = latestB?.let { it.glucoseMgdl.formatForDisplayWithUnit(displayUnit) } ?: "—",
            isSendingA = aapsActiveSlot == SensorSlot.A,
            isSendingB = aapsActiveSlot == SensorSlot.B
        )
        if (readingsA.isNotEmpty() || readingsB.isNotEmpty()) {
            DualGlucoseChart(
                readingsA = readingsA,
                readingsB = readingsB,
                colorA = colorA,
                colorB = colorB,
                fingerstickPoints = fingerstickPoints,
                unit = displayUnit,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 10/08/2026 (editor, RONDE 81) — zie kdoc bij CombiTabContent() hierboven:
 * het letterlijk gevraagde 2-koloms tabelletje boven de grafiek. Eén Card
 * met een Row van twee gelijk-brede kolommen ([CombiSlotTableColumn]) i.p.v.
 * een "echte" Compose-tabel-component (die bestaat hier nergens anders in de
 * app en zou voor precies 2 vaste kolommen overkill zijn).
 *
 * 10/08/2026 (editor, RONDE 82, na live-testfeedback — "boven de tabel moet
 * dan nog slot 1 en slot 2 worden ingevoerd. Als de tabel dan nog wat meer
 * tabel uiterlijk krijgt met een kopje [...] en daar onder de info ziet het
 * er net iets netter uit") — tot nu toe begon deze kaart direct met
 * [CombiSlotTableColumn]'s eigen eerste rij (stip + sensornaam), zonder
 * aparte kop; de twee losse CombiSlotSummaryCard-kaartjes eronder (RONDE 79,
 * inmiddels overbodig na dit tabelletje) waren het enige dat "Slot A"/"Slot
 * B" letterlijk toonde — nu verwijderd (zie CombiTabContent()). Toegevoegd:
 * een eigen kopregel met de VASTE slotlabels (SensorSlot.*.displayLabel,
 * dus altijd "Slot A"/"Slot B" — bewust ANDERS dan [CombiSlotTableColumn]'s
 * eigen naam-rij, die de GEKOZEN SENSOR toont, bv. "Dexcom G6"), optisch
 * gescheiden van de info eronder met een HorizontalDivider — zodat het geheel
 * leest als een echte tabel: kopregel, dan content.
 */
@Composable
private fun CombiSlotTable(
    labelA: String,
    labelB: String,
    colorA: Color,
    colorB: Color,
    bgTextA: String,
    bgTextB: String,
    isSendingA: Boolean,
    isSendingB: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    SensorSlot.A.displayLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    SensorSlot.B.displayLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                CombiSlotTableColumn(
                    label = labelA,
                    color = colorA,
                    bgText = bgTextA,
                    sending = isSendingA,
                    modifier = Modifier.weight(1f)
                )
                CombiSlotTableColumn(
                    label = labelB,
                    color = colorB,
                    bgText = bgTextB,
                    sending = isSendingB,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CombiSlotTableColumn(
    label: String,
    color: Color,
    bgText: String,
    sending: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Rij 1: groene/rode stip + sensornaam.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Rij 2: laatste BG-waarde.
        Text(
            bgText,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Rij 3: "Sending to AAPS" — alleen voor de daadwerkelijk zendende
        // slot; anders een even hoge, lege Spacer zodat beide kolommen
        // uitgelijnd blijven ongeacht welke van de twee (of geen van beide)
        // zendt.
        if (sending) {
            Text(
                "Sending to AAPS",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50)
            )
        } else {
            // Vaste hoogte i.p.v. sp->dp-omrekening van labelSmall's
            // lineHeight: eenvoudiger en robuuster (geen afhankelijkheid van
            // LocalDensity/TextUnit-edge-cases), benadert die regelhoogte op
            // de meeste standaard Material3-typografieën ruim voldoende voor
            // dit doel (alleen kolommen visueel gelijk houden).
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

