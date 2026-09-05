package com.fclglucolink.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.ui.theme.FCLGlucoLinkManualTheme

/**
 * ============================================================================
 * FCLGlucoLink — gebruiksaanwijzing (ronde 50, herstructureerd in ronde 52,
 * opmaak + inhoud verder verfijnd in ronde 53)
 * ============================================================================
 *
 * 06/08/2026 (editor, RONDE 50, op verzoek: "een 'info' knop [...] Die knop
 * moet een handleiding geven van de opties binnen de app") — geopend via
 * het info-knopje rechtsonder op StatusScreen.kt.
 *
 * 06/08/2026 (editor, RONDE 52, op verzoek: "een soort menu structuur [...]
 * met knoppen per onderdeel [...] ieder stukje zijn eigen pagina") — een
 * menu/index ([ManualScreen]) met een tikbare rij per onderwerp, die elk
 * naar [ManualTopicScreen] navigeren. Zie FclGlucoLinkNavHost.kt voor de
 * twee routes.
 *
 * 06/08/2026 (editor, RONDE 53, op verzoek: "een mooiere opmaak met bv een
 * kopje boven iedere paragraaf, nu leest het best lastig en misschien moet
 * het wel zwarte letters op witte achtergrond") — twee wijzigingen:
 * 1) [ManualSection]: elk onderwerp bestaat nu uit een lijst van
 *    (kopje, alinea)-paren i.p.v. losse alinea's zonder eigen titel — elke
 *    alinea krijgt zo een kort, scanbaar kopje erboven, i.p.v. één lange
 *    aaneengesloten bak tekst.
 * 2) [ManualScreen]/[ManualTopicScreen] wrappen hun hele `Scaffold` nu in
 *    [FCLGlucoLinkManualTheme] (zie Theme.kt/Color.kt) — een licht thema
 *    (zwarte tekst op witte/lichtgrijze achtergrond), NIET het donkere
 *    thema dat de rest van de app gebruikt. Bewust een geneste
 *    `MaterialTheme{}` i.p.v. de app-brede FCLGlucoLinkTheme aanpassen —
 *    dit raakt dus letterlijk alleen deze twee schermen.
 *
 * 06/08/2026 (editor, RONDE 53, op verzoek: "ik wil graag de 'about' knop
 * ergens anders [...] beter om het onder het laatste hoofdstuk te zetten
 * in de manual") — [ManualTopic.BEST_RESULTS] krijgt als enige
 * [showAboutLink] = true; [ManualTopicScreen] toont dan een extra tikbare
 * rij onderaan die [onOpenAbout] aanroept. SettingsScreen.kt's eigen
 * About-rij is in dezelfde ronde verwijderd, zie de kdoc daar.
 *
 * 10/08/2026 (editor, RONDE 77, op verzoek: "die link naar de knop moet dan
 * wel in de manual komen en niet in de andere interfaces want hij wordt
 * maar 1 malig gebruikt") — [ManualTopic.BEST_RESULTS] krijgt als enige
 * [showLocationPermissionLink] = true: een knop die de systeem-appinfo-
 * pagina van FCLGlucoLink opent (zie [LocationPermissionLinkRow]), zodat
 * een Android-11-gebruiker eenmalig "Altijd toestaan" voor locatie kan
 * aanzetten (nodig voor betrouwbare achtergrond-BLE-scans, zie de kdoc bij
 * ACCESS_BACKGROUND_LOCATION in AndroidManifest.xml). Bewust NIET als knop
 * op StatusScreen/SettingsScreen — dit is een eenmalige instelstap per
 * toestel, geen terugkerende actie, dus hoort thuis in de handleiding.
 *
 * Tekst bewust in het Engels (zie ronde-88's "vertaal alle
 * gebruikers-zichtbare tekst naar het Engels"-beslissing) — alleen de
 * code-commentaren/kdoc blijven Nederlands.
 *
 * 04/09/2026 (editor, RONDE 165, op verzoek: "de about knop [...] als
 * aparte knop onder 'getting the best results' ipv als onderdeel er van
 * wil hebben") — [AboutLinkRow] stond tot nu toe ALLEEN op
 * [ManualTopic.BEST_RESULTS]'s eigen inhoudspagina, onderaan, als link
 * binnen die pagina's content ([ManualTopicScreen]'s [showAboutLink]-blok).
 * Nu verplaatst naar [ManualScreen] zelf: een eigen, aparte rij in het
 * hoofdmenu, direct ONDER de "Getting the best results"-rij (die toch al
 * de laatste in [ManualTopic.entries] is) — je hoeft dus niet meer eerst
 * die pagina te openen om bij "About" te komen. [showAboutLink] op
 * [ManualTopic] is hiermee vervallen (was alleen BEST_RESULTS's eigen
 * vlag); [ManualTopicScreen] roept [onOpenAbout] niet langer aan.
 *
 * 05/09/2026 (editor, RONDE 170, op verzoek: "de manual weer een keer
 * doorlopen en die in lijn brengen met de huidige versie") — een aantal
 * instellingen die de afgelopen rondes zijn toegevoegd stonden nergens in
 * de handleiding: de mg/dL-vs-mmol/L-keuze (Ronde 104), Bg-voorspelling op
 * de grafiek (Ronde 160-162), de universele vertrouwde xDrip-broncode
 * (Ronde 115), automatisch opnieuw koppelen bij bond-verlies (Ronde 57),
 * en Expert mode's sensor-zichtbaarheid (Ronde 164) — alle vijf nu als
 * eigen sectie op SETTINGS, met tekst rechtstreeks overgenomen uit
 * SettingsScreen.kt's eigen omschrijvingen (i.p.v. uit het hoofd
 * herschreven, zelfde aanpak als Ronde 84's SENSORS-sectie hierboven).
 * HOME_SCREEN's "The chart"-sectie kreeg er een zin bij over de gestippelde
 * voorspellingslijnen die nu op de grafiek kunnen verschijnen. SENSORS'
 * "External list"-sectie noemde nog specifiek "mmol/L" alsof dat de enige
 * optie was — inmiddels unit-onafhankelijk geformuleerd. [AboutLinkRow]'s
 * subtitel noemt nu ook de nieuwe "What's new"-knop (zie AboutScreen.kt).
 *
 * 10/08/2026 (editor, RONDE 84, op verzoek: "kun je daarnaast ook de manual
 * aanpassen aan de nieuwe opties") — de tekst hieronder dateerde nog
 * volledig uit vóór de 2-sensoren-architectuur (Ronde 78+) en was op
 * meerdere punten feitelijk ACHTERHAALD, niet alleen onvolledig:
 *  - HOME_SCREEN beschreef één enkel thuisscherm; de app heeft sindsdien
 *    een tabbalk (per-slot tabs + een Combi-tab, zie CombiScreen.kt) — nu
 *    een nieuwe leidende sectie over de tabbalk, plus een nieuwe sectie
 *    over de Combi-tab zelf.
 *  - SENSORS noemde Dexcom G6 nog onder "Planned, not available yet" — dat
 *    was op het moment van schrijven correct, maar Dexcom G6 is sindsdien
 *    wél gebouwd (SensorType.DEXCOM_G6.implemented = true, zie
 *    SensorDriver.kt) en is precies de sensor waarmee de gebruiker deze
 *    handleiding-update aanvroeg. Gecorrigeerd, plus een nieuwe sectie over
 *    de twee onafhankelijke slots (Slot A/Slot B, elk met een eigen
 *    "Sensor"-knop op hun eigen tabblad, "None" om een slot leeg te maken).
 *  - SETTINGS beschreef nog een enkelvoudige "Send BG to AAPS"-schakelaar;
 *    die is vervangen door een Slot A/Slot B/Off-kiezer (zie
 *    SettingsScreen.kt's "Choose which slot's BG values are sent to AAPS"-
 *    kaart) — tekst hier nu letterlijk in lijn daarmee.
 *  - CALIBRATION vermeldde nog niet dat kalibratiemodus/-offset sinds Ronde
 *    81 PER SLOT staan (de "Enable calibration"-schakelaar zelf blijft wél
 *    één algemene aan/uit-knop, die op elk tabblad de Calibration-knop
 *    tevoorschijn haalt).
 *
 * 13/08/2026 (editor, RONDE 108, op verzoek: "kun je de manual nu ook weer
 * even doornemen zodat die weer in lijn is met de huidige code. Ik zag dat
 * de alarms er nog niet in stonden [...] het hoeft echter niet per type
 * heel uitgebreid want de namen spreken al voorzich, gewoon even algemeen
 * [...]") — nieuw [ManualTopic.ALARMS] (Ronde 106-108's alarmsysteem was
 * tot nu toe nergens in de handleiding terug te vinden). Bewust op het
 * gevraagde algemene niveau — GEEN opsomming van wat elk van de zeven
 * types precies doet (de namen spreken voor zich, letterlijk het
 * argument), wel de app-brede mechanismen die voor ALLE types gelden: de
 * hoofdschakelaar, dat instellingen per type bewaard blijven, het per-type
 * gekozen geluid + de Alarm/Vibrate/Both-keuze, en Stop/Snooze. SETTINGS's
 * "Calibration and smoothing"-sectie hieronder is ook uitgebreid met een
 * verwijzing naar Alarms, voor dezelfde reden als de andere twee: de knop
 * ernaartoe staat op dat scherm.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(onBack: () -> Unit, onOpenTopic: (ManualTopic) -> Unit, onOpenAbout: () -> Unit) {
    FCLGlucoLinkManualTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Manual") },
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
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Pick a topic below. If you're new to the app, start with " +
                        "\"Home screen\" and \"Sensors\" — the rest is a " +
                        "reference you can come back to any time.",
                    style = MaterialTheme.typography.bodyMedium
                )
                for (topic in ManualTopic.entries) {
                    ManualMenuRow(topic = topic, onClick = { onOpenTopic(topic) })
                }
                // 04/09/2026 (editor, RONDE 165) — zie kdoc bovenaan dit
                // bestand: eigen rij, direct onder de laatste topic-rij
                // ("Getting the best results"), i.p.v. een link binnen die
                // pagina's eigen content.
                AboutLinkRow(onClick = onOpenAbout)
            }
        }
    }
}

@Composable
private fun ManualMenuRow(topic: ManualTopic, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(topic.menuTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    topic.menuSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 52, uitgebreid RONDE 53) — de daadwerkelijke
 * inhoudspagina voor één [ManualTopic], geopend vanuit [ManualScreen]'s
 * menu. [onBack] gaat terug naar dat menu (popBackStack in
 * FclGlucoLinkNavHost.kt), niet in één keer door naar het thuisscherm.
 * 04/09/2026 (editor, RONDE 165) — [onOpenAbout]-parameter en het
 * [AboutLinkRow]-blok onderaan zijn vervallen: "About" is nu een eigen rij
 * in [ManualScreen]'s hoofdmenu i.p.v. een link binnen deze pagina's
 * content, zie kdoc bovenaan dit bestand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTopicScreen(topic: ManualTopic, onBack: () -> Unit) {
    val context = LocalContext.current
    FCLGlucoLinkManualTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(topic.menuTitle) },
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
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                for (section in topic.sections) {
                    ManualSectionBlock(section)
                }
                if (topic.showAapsWarning) {
                    WarningCard(topic)
                }
                if (topic.showLocationPermissionLink) {
                    LocationPermissionLinkRow(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    })
                }
            }
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 53) — het kopje-per-alinea dat gevraagd werd:
 * een korte, vette titel (titleSmall, primary-kleur voor wat meer
 * onderscheid van de gewone lopende tekst) direct boven elke alinea.
 */
@Composable
private fun ManualSectionBlock(section: ManualSection) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            section.heading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(section.body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 06/08/2026 (editor, RONDE 53) — verplaatst uit SettingsScreen.kt (zie de
 * kdoc daar).
 *
 * 04/09/2026 (editor, RONDE 165) — verplaatst NOGMAALS, dit keer van een
 * link onderaan [ManualTopic.BEST_RESULTS]'s eigen pagina naar een eigen
 * rij in [ManualScreen]'s hoofdmenu zelf (direct onder die topic-rij) —
 * zie kdoc bovenaan dit bestand. Nu ook een subtitel, in dezelfde
 * twee-regel-stijl als [ManualMenuRow], i.p.v. de vorige kale titel-only
 * rij (paste niet meer bij de andere menu-rijen eromheen nu dit er zelf
 * één is geworden).
 */
@Composable
private fun AboutLinkRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("About FCLGlucoLink", style = MaterialTheme.typography.titleMedium)
                Text(
                    "App info, credits, version, update check, and what's new",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 10/08/2026 (editor, RONDE 77) — zie de kdoc bovenaan dit bestand: opent
 * de systeem-appinfo-pagina voor FCLGlucoLink (Instellingen > Apps >
 * FCLGlucoLink), vanwaar de gebruiker zelf naar Machtigingen > Locatie >
 * "Altijd toestaan" navigeert. Er bestaat geen betrouwbare, OEM-onafhankelijke
 * intent die rechtstreeks naar die locatie-submachtiging springt, vandaar
 * de appinfo-pagina als stabiel startpunt plus uitleg in de bijbehorende
 * [ManualSection]-tekst.
 */
@Composable
private fun LocationPermissionLinkRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Open location permission settings", style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 06/08/2026 (editor, RONDE 50, verplaatst/hergebruikt in RONDE 52/53) —
 * zie de kdoc bovenaan dit bestand: bewust optisch afwijkend (foutkleur-
 * achtergrond) van de gewone lopende tekst, zodat deze boodschap niet als
 * "zomaar nog een alinea" wegleest. Dezelfde tekst-kern staat ook, korter,
 * op SettingsScreen.kt zelf naast de betreffende schakelaar.
 */
@Composable
private fun WarningCard(topic: ManualTopic) {
    val featureName = when (topic) {
        ManualTopic.CALIBRATION -> "calibration"
        ManualTopic.SMOOTHING -> "smoothing"
        else -> "this feature"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Important: don't double-correct the same values",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "If you enable $featureName here in FCLGlucoLink, make sure " +
                    "AAPS's own matching feature is switched OFF in AAPS " +
                    "(its calibration setting, or its smoothing/Unscented " +
                    "Kalman Filter plugin). FCLGlucoLink already sends the " +
                    "corrected value to AAPS — if AAPS then applies its own " +
                    "correction on top of that, the same adjustment " +
                    "effectively happens twice, which can distort the " +
                    "values AAPS bases dosing decisions on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 06/08/2026 (editor, RONDE 53) — (kopje, alinea)-paar, zie kdoc bovenaan
 *  dit bestand. */
data class ManualSection(val heading: String, val body: String)

/**
 * 06/08/2026 (editor, RONDE 52, sections-model toegevoegd RONDE 53) — één
 * enum-waarde per handleiding-onderwerp: [menuTitle]/[menuSubtitle] voor de
 * rij in [ManualScreen]'s menu, [sections] voor de daadwerkelijke
 * (kopje, alinea)-inhoud op [ManualTopicScreen]. [showAapsWarning] alleen
 * `true` voor Calibration/Smoothing (zie [WarningCard]).
 *
 * 04/09/2026 (editor, RONDE 165) — het vroegere [showAboutLink]-veld
 * (alleen `true` voor BEST_RESULTS) is vervallen: "About" is nu een eigen,
 * vaste rij in [ManualScreen]'s menu i.p.v. een per-topic vlag, zie
 * [AboutLinkRow]'s kdoc.
 *
 * SENSORS's inhoud (06/08/2026, op verzoek: "het stukje info over de
 * sensors moet wat uitgebreider [...] dan ook de sensors noemen die
 * mogelijk nog gaan komen en ook specifiek de virtuele sensors en hun doel
 * [...] willekeurige virtuele data maar ook reproduceerbaar met een test
 * file") — overgenomen uit de daadwerkelijke broncode i.p.v. uit het hoofd
 * geschreven: de drie simulator-modi komen rechtstreeks uit
 * `ui/SimulatorSetupScreen.kt`, en de twee nog-niet-beschikbare
 * sensortypes uit `sensor/SensorDriver.kt`'s `SensorType`-enum
 * (`implemented = false` voor Dexcom G7/ONE+ en Accu-Chek SmartGuide).
 */
enum class ManualTopic(
    val menuTitle: String,
    val menuSubtitle: String,
    val sections: List<ManualSection>,
    val showAapsWarning: Boolean = false,
    val showLocationPermissionLink: Boolean = false
) {
    HOME_SCREEN(
        menuTitle = "Home screen",
        menuSubtitle = "The tabs, the ring/chart, and the Combi overview",
        sections = listOf(
            ManualSection(
                "Two sensor tabs, plus Combi",
                "FCLGlucoLink can hold two sensors at once, each in its " +
                    "own independent \"slot\" — the tab bar at the top " +
                    "shows one tab per slot (labelled by whichever sensor " +
                    "is chosen there, e.g. \"CareSens Air\" or " +
                    "\"Dexcom G6\") plus a third \"Combi\" tab. Tap a tab " +
                    "to switch between them; the selected tab gets a " +
                    "clearly shaded blue background, while the thin " +
                    "green/red stripe under each tab's title shows " +
                    "whether that slot is currently the one sending to " +
                    "AAPS — those are two independent signals, so a tab " +
                    "can be selected (blue) without being the one " +
                    "actively feeding AAPS (red stripe), and vice versa."
            ),
            ManualSection(
                "The ring",
                "Shows your current BG value, the change since the last " +
                    "reading (top) and how long ago it was measured " +
                    "(bottom) — green/amber/red matches how far you are " +
                    "from a normal range."
            ),
            ManualSection(
                "The chart",
                "Shows recent history; pinch to zoom and swipe to look " +
                    "back up to 48 hours. If calibration changed a value, " +
                    "the raw sensor reading still appears as a small, grey " +
                    "open circle alongside the calibrated line, so you can " +
                    "always see both. A dashed vertical line marks the " +
                    "moment a new sensor session started on that slot — " +
                    "subtle for a same-type switch (e.g. a new CareSens " +
                    "sensor), more prominent for a switch between " +
                    "different sensor types. If \"Show Bg prediction\" is " +
                    "on (Settings), two diverging dashed lines continue " +
                    "past your last reading — a rough, short-term forecast " +
                    "of where the Bg could move, see \"Settings\" in this " +
                    "guide for what it's based on."
            ),
            ManualSection(
                "Quick-access buttons",
                "\"Settings\", \"Sensor\", and, once enabled, " +
                    "\"Calibration\" open the corresponding screens " +
                    "covered elsewhere in this guide — each acts only on " +
                    "the slot whose tab you're currently viewing."
            ),
            ManualSection(
                "The Combi tab",
                "A combined overview of both slots at once: a small " +
                    "table at the top shows each slot's sensor name, " +
                    "latest BG value, and whether it's currently sending " +
                    "to AAPS, followed by a single chart with both slots' " +
                    "readings overlaid in their own colour, auto-scaling " +
                    "to whichever value is highest. Use the per-slot tabs " +
                    "for pairing, calibration, or any other management — " +
                    "Combi is view-only."
            )
        )
    ),
    SENSORS(
        menuTitle = "Sensors",
        menuSubtitle = "Two independent slots, supported hardware, and the BG simulator",
        sections = listOf(
            ManualSection(
                "Two independent slots",
                "FCLGlucoLink can connect to two sensors at the same " +
                    "time, held in two independent \"slots\" (Slot A and " +
                    "Slot B — the tab bar shows one tab per slot, see " +
                    "\"Home screen\" in this guide). Each slot can hold " +
                    "any sensor type — including two of the same type at " +
                    "once, for example during an overlap while starting a " +
                    "new sensor a few days before the old one runs out. " +
                    "Open \"Sensor\" on a slot's own tab to choose, " +
                    "switch, or clear (\"None\") that slot — it never " +
                    "affects the other slot."
            ),
            ManualSection(
                "One shared connection interface",
                "Under the hood, every sensor type plugs into the same " +
                    "shared connection interface, so new sensor types can " +
                    "be added over time without changing how AAPS " +
                    "receives data."
            ),
            ManualSection(
                "Available now: CareSens Air, Dexcom G6, and Dexcom G7 / ONE+",
                "CareSens Air: scan the QR code printed on the sensor " +
                    "packaging, then pick it from the Bluetooth list. " +
                    "Android will then ask for a Bluetooth pairing PIN — " +
                    "use the PIN code shown on the scan-result screen (also " +
                    "printed on the sensor packaging as \"PINCODE\"/\"CODE " +
                    "PIN\"), not whatever Android itself suggests (e.g. " +
                    "\"try 0000 or 1234\") — that's just a generic guess " +
                    "and won't work. Dexcom G6: enter the transmitter ID " +
                    "printed on the transmitter itself; FCLGlucoLink then " +
                    "connects directly to that transmitter. Dexcom G7 / " +
                    "ONE+: enter the 4-digit pairing code printed on the " +
                    "sensor applicator, then pick it from the Bluetooth " +
                    "list — this is brand new and hasn't been tested " +
                    "against a real G7/ONE+ sensor yet, so expect the odd " +
                    "rough edge on the very first pairing attempt."
            ),
            ManualSection(
                "Planned, not available yet",
                "Accu-Chek SmartGuide. It already appears in the sensor " +
                    "picker so the eventual switch is easy, but choosing " +
                    "it today shows a message that its connection support " +
                    "hasn't been built yet — pick CareSens Air, Dexcom G6, " +
                    "Dexcom G7 / ONE+, or the BG simulator below in the " +
                    "meantime."
            ),
            ManualSection(
                "BG simulator (virtual sensor)",
                "No physical hardware at all. It sends fictitious values " +
                    "through exactly the same path a real sensor uses " +
                    "(local storage, plus the xDrip broadcast to AAPS), " +
                    "which makes it useful for checking that the " +
                    "connection to AAPS works, or for trying out " +
                    "calibration/smoothing, before a real sensor is ever " +
                    "paired."
            ),
            ManualSection(
                "Random, open-ended testing",
                "\"Manual value\" sends one number you type in, either " +
                    "once or repeating automatically every 5 minutes. " +
                    "\"Random values\" generates a new, realistic reading " +
                    "relative to the previous one at every step — mostly " +
                    "stable, occasionally a meal-like rise and fall — " +
                    "useful for open-ended connectivity testing with data " +
                    "that behaves plausibly without you having to make up " +
                    "numbers yourself."
            ),
            ManualSection(
                "External list (reproducible testing)",
                "Pick a text file with one BG value per line, in mmol/L " +
                    "regardless of your display unit setting (see " +
                    "\"Settings\" in this guide) — " +
                    "for example an earlier problem episode exported from " +
                    "your own logs — and the simulator replays it in that " +
                    "exact order, looping back to the start once it " +
                    "reaches the end. Because the same file always " +
                    "produces the same sequence, this is the way to " +
                    "replay a specific scenario exactly, rather than " +
                    "random data. Random values and the external list can " +
                    "both run at real-time speed (one value every 5 " +
                    "minutes, like a real sensor) or accelerated (every " +
                    "minute, for a quick test run)."
            ),
            ManualSection(
                "Switching sensors",
                "Calibration data is cleared automatically whenever you " +
                    "switch to a genuinely different physical sensor on " +
                    "that slot (not on an ordinary reconnect to the same " +
                    "one) — that includes switching between the simulator " +
                    "and a real sensor, so old fingerstick values never " +
                    "carry over to data they don't apply to. This is " +
                    "entirely per slot: switching the sensor on one tab " +
                    "never touches the other slot's own calibration data."
            )
        )
    ),
    SETTINGS(
        menuTitle = "Settings",
        menuSubtitle = "Which slot feeds AAPS, and what the other screens do",
        sections = listOf(
            ManualSection(
                "Sending BG to AAPS",
                "\"Send BG to AAPS from\" is a Slot A / Slot B / Off " +
                    "choice — at most one slot feeds AAPS via the xDrip " +
                    "protocol at any time, never both at once. Whichever " +
                    "slot is currently selected shows a green stripe " +
                    "under its tab title (and \"Sending to AAPS\" on that " +
                    "tab and on the Combi table); the other slot shows a " +
                    "red stripe instead."
            ),
            ManualSection(
                "When to turn it off",
                "Set it to \"Off\", or point it at the other slot, if " +
                    "you want to test a sensor here while a separate " +
                    "xDrip app is the one actually feeding AAPS — " +
                    "otherwise AAPS would receive conflicting values from " +
                    "two sources at once. Local storage (so each tab's " +
                    "ring/chart and the Combi tab keep working) happens " +
                    "regardless of this choice."
            ),
            ManualSection(
                "Calibration, smoothing, and alarms",
                "Those switches also live on this screen — see their own " +
                    "topics in this guide for what they do and how to set " +
                    "them up."
            ),
            ManualSection(
                "Display unit",
                "Choose mmol/L or mg/dL — controls charts, the status " +
                    "ring, fingerstick/simulator input, and the connection " +
                    "notification. Storage and the value sent to AAPS " +
                    "always stay mg/dL underneath, regardless of this " +
                    "setting, so switching it is purely cosmetic and never " +
                    "affects dosing."
            ),
            ManualSection(
                "Bg prediction",
                "\"Show Bg prediction\" adds a 1-hour forecast to the " +
                    "chart on the tab it's shown on (see \"Home screen\" " +
                    "in this guide) — based only on the recent trend and " +
                    "how much it's been varying, with no insulin-on-board " +
                    "or meal information involved. Treat it as a rough " +
                    "indication, not a precise prediction."
            ),
            ManualSection(
                "Universal trusted source code",
                "Makes every sensor (including the simulator) identify " +
                    "itself to AAPS with a single description that's " +
                    "trusted for \"SMB Always\" on both AAPS 3 and AAPS 4 " +
                    "— the trade-off is that AAPS/Nightscout then shows a " +
                    "generic Dexcom label instead of your actual sensor's " +
                    "name. Off sends the best-matching description per " +
                    "sensor instead, which may not enable SMB Always on " +
                    "every AAPS version."
            ),
            ManualSection(
                "Automatic re-pair",
                "If the phone's Bluetooth pairing with your sensor is " +
                    "unexpectedly lost after it worked before, FCLGlucoLink " +
                    "can try to silently re-pair instead of waiting for " +
                    "you to reconnect by hand. It only acts on a sensor " +
                    "it has successfully connected to before, never a " +
                    "brand-new one — but note that it removes and " +
                    "re-creates the phone's Bluetooth pairing, which " +
                    "affects the whole phone: if another app (e.g. xDrip+) " +
                    "is also paired with the same sensor, its pairing " +
                    "breaks too."
            ),
            ManualSection(
                "Expert mode",
                "Choose which sensor types show up in the sensor picker " +
                    "on each slot's own tab — for example, hide the BG " +
                    "simulator once you no longer need it for testing, so " +
                    "it can't be picked by accident. Every sensor type is " +
                    "visible by default."
            )
        )
    ),
    CALIBRATION(
        menuTitle = "Calibration",
        menuSubtitle = "Correcting sensor readings with your own fingerstick values",
        sections = listOf(
            ManualSection(
                "What it does",
                "If your sensor tends to read a bit high or low compared " +
                    "to a fingerstick meter, calibration corrects for " +
                    "that. Turn \"Enable calibration\" on in Settings, " +
                    "then open the \"Calibration\" button on a slot's own " +
                    "tab and add a few fingerstick readings over time — " +
                    "the entry screen pre-fills the current sensor value " +
                    "so you only need to adjust it to match your meter."
            ),
            ManualSection(
                "Per slot",
                "\"Enable calibration\" itself is a single switch that " +
                    "makes the \"Calibration\" button available on both " +
                    "tabs — but each slot's own fingerstick entries, fit, " +
                    "and manual offset are kept completely separate, so " +
                    "calibrating one sensor never affects the other."
            ),
            ManualSection(
                "Adding entries",
                "With two or more entries spread over time, FCLGlucoLink " +
                    "fits a curve through them and applies it to every new " +
                    "sensor reading — the more entries, spread across " +
                    "different BG levels, the better the fit. A single " +
                    "entry alone still works (a plain, fixed offset " +
                    "shift), but won't capture a sensor whose error " +
                    "changes at different glucose levels."
            ),
            ManualSection(
                "Switching sensors",
                "Calibration data is cleared automatically whenever you " +
                    "start a new physical sensor on that slot, so old " +
                    "fingerstick values never carry over to a sensor they " +
                    "don't apply to."
            )
        ),
        showAapsWarning = true
    ),
    SMOOTHING(
        menuTitle = "Smoothing",
        menuSubtitle = "Damping sensor noise with a Kalman filter",
        sections = listOf(
            ManualSection(
                "What it does",
                "Sensors occasionally report a single noisy or spiky " +
                    "value that doesn't reflect a real, fast change in " +
                    "glucose. Turn \"Enable smoothing\" on in Settings to " +
                    "apply a Kalman filter (the same family of technique " +
                    "AAPS itself uses) that damps out that kind of noise " +
                    "while still tracking genuine trends (meals, insulin) " +
                    "without meaningful extra delay."
            ),
            ManualSection(
                "Runs after calibration",
                "Smoothing always runs AFTER calibration, so it works on " +
                    "the already-corrected value — the two features are " +
                    "designed to be used together, in that order."
            ),
            ManualSection(
                "Break-in filter for new sensors",
                "A new physical sensor is often noisier than usual for " +
                    "its first hours or days. \"Break-in filter for new " +
                    "sensors\", right below \"Enable smoothing\", filters " +
                    "that extra noise more heavily right after a sensor " +
                    "starts, then eases off smoothly over the \"Duration\" " +
                    "you set (in hours) until it has no more effect. It " +
                    "only affects RISES, never falls — the goal is " +
                    "specifically to stop break-in noise from falsely " +
                    "triggering a dosing decision on the way up, not to " +
                    "dampen genuine falls. It applies to every sensor " +
                    "type the same way, regardless of whether you use " +
                    "calibration."
            )
        ),
        showAapsWarning = true
    ),
    ALARMS(
        menuTitle = "Alarms",
        menuSubtitle = "Low/high, predictive, and stale-data glucose alerts",
        sections = listOf(
            ManualSection(
                "What it does",
                "Seven independent alarm types — Urgent Low, Low, High, " +
                    "Urgent High, Predictive Low, Predictive High, and " +
                    "Stale data — each named clearly enough that you " +
                    "won't need this guide to know what it warns about. " +
                    "Open \"Configure alarms\" on the Settings screen to " +
                    "set them up."
            ),
            ManualSection(
                "Master switch",
                "One switch at the top turns every alarm on or off at " +
                    "once. Turning it off doesn't erase anything — each " +
                    "type's own settings (on/off, threshold, sound) stay " +
                    "exactly as you left them, ready to go the moment you " +
                    "switch alarms back on."
            ),
            ManualSection(
                "Predictive alarms have their own target",
                "Predictive Low and Predictive High each have their own " +
                    "BG target and lead time, completely independent from " +
                    "the plain Low/High alarms — for example, Predictive " +
                    "Low can warn you well before a Low alarm would " +
                    "actually fire, at whatever target and lead time you " +
                    "choose."
            ),
            ManualSection(
                "Sound and vibration, per type",
                "Each alarm type has its own sound, picked from the same " +
                    "chooser Android uses for ringtones, plus an " +
                    "Alarm / Vibrate / Both choice for how it gets your " +
                    "attention. A separate \"Immediately\" / \"Gradual\" " +
                    "choice controls whether it starts at full volume or " +
                    "eases in."
            ),
            ManualSection(
                "Stop and snooze",
                "A firing alarm opens a full-screen alert with the " +
                    "current BG value. \"Stop\" silences it for a while — " +
                    "it isn't permanent, so it comes back on its own if " +
                    "the situation hasn't improved. \"Snooze\" lets you " +
                    "pick 15, 30, or 60 minutes instead."
            )
        )
    ),
    DIAGNOSTICS(
        menuTitle = "Diagnostics",
        menuSubtitle = "Advanced: logging connection details to a file",
        sections = listOf(
            ManualSection(
                "Diagnostic log to file",
                "Writes detailed connection/scan information to a text " +
                    "file on the device, useful if you're troubleshooting " +
                    "a connection problem over several hours without a " +
                    "cable attached."
            ),
            ManualSection(
                "When to use it",
                "Leave this off during normal use — it's only meant to be " +
                    "switched on while actively investigating something."
            )
        )
    ),
    BEST_RESULTS(
        menuTitle = "Getting the best results",
        menuSubtitle = "A short checklist for day-to-day use",
        sections = listOf(
            ManualSection(
                "Battery optimization",
                "Grant the exemption when the app asks — it's needed to " +
                    "keep the Bluetooth connection alive with the screen " +
                    "off."
            ),
            ManualSection(
                "Android 11 or older: background location (one-time)",
                "On Android 11 and below, reliable Bluetooth scanning " +
                    "with the screen off also requires \"Allow all the " +
                    "time\" for location — not because FCLGlucoLink uses " +
                    "your location, but because that's how Android's " +
                    "older Bluetooth-scanning permission model works " +
                    "under the hood. If BG readings stop arriving after " +
                    "the screen has been off for a while, this is " +
                    "usually why. Use the button below once: it opens " +
                    "this app's system settings page — from there go to " +
                    "Permissions > Location and choose \"Allow all the " +
                    "time\" (not just \"Allow only while using the " +
                    "app\"). Not needed on Android 12 or newer."
            ),
            ManualSection(
                "Calibration entries",
                "Give calibration at least two or three fingerstick " +
                    "entries, spread across a low, a normal, and a high " +
                    "reading if possible, before expecting it to improve " +
                    "accuracy — one single entry only shifts the whole " +
                    "curve by a fixed offset."
            ),
            ManualSection(
                "Smoothing",
                "Leave it on for day-to-day looping; only turn it off " +
                    "temporarily if you specifically want to see " +
                    "completely raw sensor values (e.g. while comparing " +
                    "against a fingerstick reading)."
            ),
            ManualSection(
                "Avoid double-correcting in AAPS",
                "If either calibration or smoothing is on here, turn the " +
                    "matching feature off inside AAPS itself — see their " +
                    "topics in this guide."
            )
        ),
        showLocationPermissionLink = true
    )
}
