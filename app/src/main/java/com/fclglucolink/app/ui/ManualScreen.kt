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
 * 06/08/2026 (editor, RONDE 50, op verzoek voor een info-knop die een
 * handleiding geeft van de opties binnen de app) — geopend via
 * het info-knopje rechtsonder op StatusScreen.kt.
 *
 * 06/08/2026 (editor, RONDE 52, op verzoek voor een menustructuur met een
 * knop per onderdeel en een eigen pagina per stukje) — een
 * menu/index ([ManualScreen]) met een tikbare rij per onderwerp, die elk
 * naar [ManualTopicScreen] navigeren. Zie FclGlucoLinkNavHost.kt voor de
 * twee routes.
 *
 * 06/08/2026 (editor, RONDE 53, op verzoek voor een mooiere opmaak met een
 * kopje boven iedere paragraaf en zwarte letters op een witte achtergrond,
 * omdat het tot dan toe lastig leesbaar was) — twee wijzigingen:
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
 * 06/08/2026 (editor, RONDE 53, op verzoek om de "about"-knop elders te
 * plaatsen, bij voorkeur onder het laatste hoofdstuk in de manual) —
 * [ManualTopic.BEST_RESULTS] krijgt als enige
 * [showAboutLink] = true; [ManualTopicScreen] toont dan een extra tikbare
 * rij onderaan die [onOpenAbout] aanroept. SettingsScreen.kt's eigen
 * About-rij is in dezelfde ronde verwijderd, zie de kdoc daar.
 *
 * 10/08/2026 (editor, RONDE 77, op verzoek om deze link in de manual te
 * plaatsen en niet in de andere interfaces, omdat hij maar eenmalig
 * gebruikt wordt) — [ManualTopic.BEST_RESULTS] krijgt als enige
 * [showLocationPermissionLink] = true: een knop die de systeem-appinfo-
 * pagina van FCLGlucoLink opent (zie [LocationPermissionLinkRow]), zodat
 * een Android-11-gebruiker eenmalig "Altijd toestaan" voor locatie kan
 * aanzetten (nodig voor betrouwbare achtergrond-BLE-scans, zie de kdoc bij
 * ACCESS_BACKGROUND_LOCATION in AndroidManifest.xml). Bewust NIET als knop
 * op StatusScreen/SettingsScreen — dit is een eenmalige instelstap per
 * toestel, geen terugkerende actie, dus hoort thuis in de handleiding.
 *
 * 04/09/2026 (editor, RONDE 165, op verzoek om de about-knop als aparte
 * knop onder "Getting the best results" te plaatsen i.p.v. als onderdeel
 * daarvan) — [AboutLinkRow] stond tot nu toe ALLEEN op
 * [ManualTopic.BEST_RESULTS]'s eigen inhoudspagina, onderaan, als link
 * binnen die pagina's content ([ManualTopicScreen]'s [showAboutLink]-blok),
 * en verhuisde in deze ronde naar een eigen rij in [ManualScreen]'s
 * hoofdmenu zelf.
 *
 * 11/09/2026 (editor, RONDE 174, op verzoek om de update-knop weer terug
 * naar Settings te verplaatsen) — [AboutLinkRow] en de bijbehorende
 * [onOpenAbout]-parameter zijn hiermee VERVALLEN: de link naar het
 * About-scherm (app-info, versie, update-check) staat nu op
 * SettingsScreen.kt's eigen "Support"-kaart, samen met de nieuwe
 * "Send log files"-knop, zie de kdoc daar. De handleiding zelf verwijst
 * er nog wel naar (zie [ManualTopic.DIAGNOSTICS]'s sectie hieronder), maar
 * heeft zelf geen navigatie er meer naartoe nodig.
 *
 * 05/09/2026 (editor, RONDE 170, op verzoek om de manual weer eens door te
 * lopen en in lijn te brengen met de huidige versie) — een aantal
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
 * 10/08/2026 (editor, RONDE 84, op verzoek om de manual ook aan te passen
 * aan de nieuwe opties) — de tekst hieronder dateerde nog
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
 * 13/08/2026 (editor, RONDE 108, op verzoek om de manual weer bij te
 * werken naar de huidige code — de alarms ontbraken nog, maar hoefden niet
 * per type uitgebreid beschreven te worden, de namen spreken al redelijk
 * voor zich, dus een algemene beschrijving volstaat) — nieuw [ManualTopic.ALARMS] (Ronde 106-108's alarmsysteem was
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
 * 25/09/2026 (editor, RONDE 189, op verzoek van de gebruiker namens een
 * gezinslid voor een Nederlandse vertaling met taalkeuze — zie ui/Localization.kt's
 * klasse-kdoc) — de aanname uit de eerdere kdoc-regel hieronder ("tekst
 * bewust in het Engels ... alle gebruikers-zichtbare tekst") geldt niet
 * meer: [ManualSection] en [ManualTopic] draaien nu elk een EN- en een
 * NL-variant van hun tekst mee (`...En`/`...Nl`-velden), en de
 * schermfuncties hieronder kiezen daaruit via [tr] op basis van de
 * opgeslagen taalvoorkeur (zie SettingsScreen.kt's nieuwe taalkaart). De
 * Engelse tekst is dus ONGEWIJZIGD blijven staan als de `En`-variant van
 * elk veld — een gebruiker die Engels gekozen heeft (of nooit iets
 * aanraakt, want dat is de default) ziet dus letterlijk precies dezelfde
 * tekst als vóór deze ronde. De Nederlandse vertaling behoudt overal de
 * Engelse merk-/protocolnamen en technische termen (CareSens Air, Dexcom
 * G6/G7/ONE+, Bluetooth, AAPS, Nightscout, SMB Always, Kalman-filter,
 * Slot A/Slot B, mmol/L, mg/dL) onvertaald — dat zijn eigennamen/
 * vaktermen, geen lopende tekst.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(onBack: () -> Unit, onOpenTopic: (ManualTopic) -> Unit) {
    FCLGlucoLinkManualTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tr("Manual", "Handleiding")) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Terug"))
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
                    tr(
                        "Pick a topic below. If you're new to the app, start with " +
                            "\"Home screen\" and \"Sensors\" — the rest is a " +
                            "reference you can come back to any time.",
                        "Kies hieronder een onderwerp. Ben je nieuw in de app, " +
                            "begin dan met \"Hoofdscherm\" en \"Sensoren\" — de rest " +
                            "is een naslagwerk waar je altijd op terug kunt komen."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                for (topic in ManualTopic.entries) {
                    ManualMenuRow(topic = topic, onClick = { onOpenTopic(topic) })
                }
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
                Text(tr(topic.menuTitleEn, topic.menuTitleNl), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr(topic.menuSubtitleEn, topic.menuSubtitleNl),
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
                    title = { Text(tr(topic.menuTitleEn, topic.menuTitleNl)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Terug"))
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
 *
 * 25/09/2026 (editor, RONDE 189) — kiest via [tr] tussen [ManualSection]'s
 * En/Nl-varianten, zie de klasse-kdoc bovenaan dit bestand.
 */
@Composable
private fun ManualSectionBlock(section: ManualSection) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tr(section.headingEn, section.headingNl),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(tr(section.bodyEn, section.bodyNl), style = MaterialTheme.typography.bodyMedium)
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
            Text(
                tr("Open location permission settings", "Locatiemachtiging-instellingen openen"),
                style = MaterialTheme.typography.bodyMedium
            )
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
 *
 * 25/09/2026 (editor, RONDE 189) — [featureNameEn]/[featureNameNl] geven
 * elk de taal-eigen invulling voor de zin hieronder mee, zie [tr].
 */
@Composable
private fun WarningCard(topic: ManualTopic) {
    val featureNameEn = when (topic) {
        ManualTopic.CALIBRATION -> "calibration"
        ManualTopic.SMOOTHING -> "smoothing"
        else -> "this feature"
    }
    val featureNameNl = when (topic) {
        ManualTopic.CALIBRATION -> "kalibratie"
        ManualTopic.SMOOTHING -> "filtering"
        else -> "deze functie"
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
                tr("Important: don't double-correct the same values", "Belangrijk: corrigeer dezelfde waarden niet dubbel"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                tr(
                    "If you enable $featureNameEn here in FCLGlucoLink, make sure " +
                        "AAPS's own matching feature is switched OFF in AAPS " +
                        "(its calibration setting, or its smoothing/Unscented " +
                        "Kalman Filter plugin). FCLGlucoLink already sends the " +
                        "corrected value to AAPS — if AAPS then applies its own " +
                        "correction on top of that, the same adjustment " +
                        "effectively happens twice, which can distort the " +
                        "values AAPS bases dosing decisions on.",
                    "Als je hier in FCLGlucoLink $featureNameNl inschakelt, zorg " +
                        "dan dat AAPS' eigen overeenkomstige functie in AAPS " +
                        "UITGESCHAKELD is (de kalibratie-instelling, of de " +
                        "filtering-/Unscented Kalman Filter-plugin). " +
                        "FCLGlucoLink stuurt de gecorrigeerde waarde al naar " +
                        "AAPS — als AAPS daar dan zelf nog een eigen correctie " +
                        "op toepast, gebeurt dezelfde aanpassing feitelijk twee " +
                        "keer, wat de waarden waarop AAPS doseerbeslissingen " +
                        "baseert kan verstoren."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 06/08/2026 (editor, RONDE 53) — (kopje, alinea)-paar, zie kdoc bovenaan
 *  dit bestand.
 *
 *  25/09/2026 (editor, RONDE 189) — elk kopje/elke alinea heeft nu een
 *  En- en een Nl-variant i.p.v. één enkele tekst, zie [ManualSectionBlock]
 *  en de klasse-kdoc bovenaan dit bestand. */
data class ManualSection(val headingEn: String, val headingNl: String, val bodyEn: String, val bodyNl: String)

/**
 * 06/08/2026 (editor, RONDE 52, sections-model toegevoegd RONDE 53) — één
 * enum-waarde per handleiding-onderwerp: [menuTitleEn]/[menuTitleNl]/
 * [menuSubtitleEn]/[menuSubtitleNl] voor de rij in [ManualScreen]'s menu,
 * [sections] voor de daadwerkelijke (kopje, alinea)-inhoud op
 * [ManualTopicScreen]. [showAapsWarning] alleen `true` voor
 * Calibration/Smoothing (zie [WarningCard]).
 *
 * 04/09/2026 (editor, RONDE 165) — het vroegere [showAboutLink]-veld
 * (alleen `true` voor BEST_RESULTS) is vervallen: "About" is nu een eigen,
 * vaste rij in [ManualScreen]'s menu i.p.v. een per-topic vlag, zie
 * [AboutLinkRow]'s kdoc.
 *
 * 25/09/2026 (editor, RONDE 189) — elk EN-veld (`menuTitleEn`/
 * `menuSubtitleEn`, en de `headingEn`/`bodyEn` van elke sectie) is de
 * ONGEWIJZIGDE tekst van vóór deze ronde; elk `...Nl`-veld is de nieuwe
 * vertaling. Zie ui/Localization.kt's klasse-kdoc en de eigen klasse-kdoc
 * van dit bestand bovenaan voor het volledige ontwerp.
 *
 * 26/09/2026 (editor, RONDE 190, op verzoek na review door een gezinslid) — alle
 * `...Nl`-velden hadden op zo'n 12 plekken "X se Y" als bezitsvorm (een
 * calque van het Afrikaans, geen correct Nederlands) — overal herschreven
 * naar "Y van X" of een vergelijkbare Nederlandse constructie. Zie ook de
 * kdoc-instances hierboven in dit bestand en in andere ui/-bestanden, die
 * dezelfde fout bevatten en tegelijk gecorrigeerd zijn.
 *
 * SENSORS's inhoud (06/08/2026, op verzoek om het stukje info over de
 * sensors uit te breiden met mogelijk nog komende sensoren en specifiek de
 * virtuele sensors en hun doel, zowel willekeurige virtuele data als
 * reproduceerbaar via een testbestand) — overgenomen uit de daadwerkelijke broncode i.p.v. uit het hoofd
 * geschreven: de drie simulator-modi komen rechtstreeks uit
 * `ui/SimulatorSetupScreen.kt`, en de twee nog-niet-beschikbare
 * sensortypes uit `sensor/SensorDriver.kt`'s `SensorType`-enum
 * (`implemented = false` voor Dexcom G7/ONE+ en Accu-Chek SmartGuide).
 */
enum class ManualTopic(
    val menuTitleEn: String,
    val menuTitleNl: String,
    val menuSubtitleEn: String,
    val menuSubtitleNl: String,
    val sections: List<ManualSection>,
    val showAapsWarning: Boolean = false,
    val showLocationPermissionLink: Boolean = false
) {
    HOME_SCREEN(
        menuTitleEn = "Home screen",
        menuTitleNl = "Hoofdscherm",
        menuSubtitleEn = "The tabs, the ring/chart, and the Combi overview",
        menuSubtitleNl = "De tabs, de cirkel/grafiek, en het Combi-overzicht",
        sections = listOf(
            ManualSection(
                headingEn = "Two sensor tabs, plus Combi",
                headingNl = "Twee sensor-tabs, plus Combi",
                bodyEn = "FCLGlucoLink can hold two sensors at once, each in its " +
                    "own independent \"slot\" — the tab bar at the top " +
                    "shows one tab per slot (labelled by whichever sensor " +
                    "is chosen there, e.g. \"CareSens Air\" or " +
                    "\"Dexcom G6\") plus a third \"Combi\" tab.\n\n" +
                    "Tap a tab " +
                    "to switch between them; the selected tab gets a " +
                    "clearly shaded blue background, while the thin " +
                    "green/red stripe under each tab's title shows " +
                    "whether that slot is currently the one sending to " +
                    "AAPS — those are two independent signals, so a tab " +
                    "can be selected (blue) without being the one " +
                    "actively feeding AAPS (red stripe), and vice versa.",
                bodyNl = "FCLGlucoLink kan twee sensoren tegelijk vasthouden, " +
                    "elk in een eigen onafhankelijk \"slot\" — de tabbalk " +
                    "boven toont één tab per slot (met het label van de " +
                    "sensor die daar gekozen is, bv. \"CareSens Air\" of " +
                    "\"Dexcom G6\") plus een derde \"Combi\"-tab.\n\n" +
                    "Tik op een " +
                    "tab om te wisselen; de geselecteerde tab krijgt een " +
                    "duidelijk blauw gearceerde achtergrond, terwijl de dunne " +
                    "groen/rode streep onder de titel van elke tab laat zien " +
                    "of dat slot momenteel naar AAPS zendt — dat zijn twee " +
                    "onafhankelijke signalen, dus een tab kan geselecteerd " +
                    "zijn (blauw) zonder dat die tab ook actief AAPS voedt " +
                    "(rode streep), en omgekeerd."
            ),
            ManualSection(
                headingEn = "The ring",
                headingNl = "De cirkel",
                bodyEn = "Shows your current BG value, the change since the last " +
                    "reading (top) and how long ago it was measured " +
                    "(bottom) — green/amber/red matches how far you are " +
                    "from a normal range.",
                bodyNl = "Toont je huidige BG-waarde, de verandering sinds de " +
                    "laatste meting (boven) en hoe lang geleden die gemeten " +
                    "is (onder) — groen/oranje/rood komt overeen met hoe ver " +
                    "je van een normaal bereik verwijderd bent."
            ),
            ManualSection(
                headingEn = "The chart",
                headingNl = "De grafiek",
                bodyEn = "Shows recent history; zoom and swipe to look " +
                    "back up to 48 hours. If calibration changed a value, " +
                    "the raw sensor reading still appears as a small, grey " +
                    "open circle alongside the calibrated line, so you can " +
                    "always see both.\n\nA dashed vertical line marks the " +
                    "moment a new sensor session started on that slot — " +
                    "subtle for a same-type switch (e.g. a new CareSens " +
                    "sensor), more prominent for a switch between " +
                    "different sensor types. If \"Show Bg prediction\" is " +
                    "on (Settings), two diverging dashed lines continue " +
                    "past your last reading — a rough, short-term forecast " +
                    "of where the Bg could move, see \"Settings\" in this " +
                    "guide for what it's based on.",
                bodyNl = "Toont recente geschiedenis; zoom en swipe om tot 48 " +
                    "uur terug te kijken. Als kalibratie een " +
                    "waarde veranderd heeft, verschijnt de ruwe sensormeting " +
                    "nog steeds als een klein, grijs open cirkeltje naast de " +
                    "gekalibreerde lijn, zodat je altijd beide kunt zien.\n\n" +
                    "Een gestippelde verticale lijn markeert het moment waarop " +
                    "een nieuwe sensorsessie op dat slot begon — subtiel bij " +
                    "een wissel binnen hetzelfde type (bv. een nieuwe " +
                    "CareSens-sensor), duidelijker bij een wissel tussen " +
                    "verschillende sensortypes. Als \"Toon Bg-voorspelling\" " +
                    "aan staat (Instellingen), lopen er twee uiteenlopende " +
                    "gestippelde lijnen door na je laatste meting — een " +
                    "ruwe, kortetermijnschatting van waar de Bg naartoe kan " +
                    "bewegen, zie \"Instellingen\" in deze handleiding voor " +
                    "waar dat op gebaseerd is."
            ),
            ManualSection(
                headingEn = "Quick-access buttons",
                headingNl = "Snelkoppelingen",
                bodyEn = "\"Settings\", \"Sensor\", and, once enabled, " +
                    "\"Calibration\" open the corresponding screens " +
                    "covered elsewhere in this guide — each acts only on " +
                    "the slot whose tab you're currently viewing.",
                bodyNl = "\"Settings\", \"Sensor\", en, eenmaal ingeschakeld, " +
                    "\"Calibration\" openen de bijbehorende schermen die " +
                    "elders in deze handleiding beschreven staan — elke knop " +
                    "werkt alleen op het slot van de tab die je op dat " +
                    "moment bekijkt."
            ),
            ManualSection(
                headingEn = "The Combi tab",
                headingNl = "Het Combi-tabblad",
                bodyEn = "A combined overview of both slots at once: a small " +
                    "table at the top shows each slot's sensor name, " +
                    "latest BG value, and whether it's currently sending " +
                    "to AAPS, followed by a single chart with both slots' " +
                    "readings overlaid in their own colour, auto-scaling " +
                    "to whichever value is highest. Use the per-slot tabs " +
                    "for pairing, calibration, or any other management — " +
                    "Combi is view-only.",
                bodyNl = "Een gecombineerd overzicht van beide sloten tegelijk: " +
                    "een klein tabelletje boven toont per slot de " +
                    "sensornaam, de laatste BG-waarde, en of dat slot " +
                    "momenteel naar AAPS zendt, gevolgd door één grafiek met " +
                    "de metingen van beide sloten over elkaar in hun eigen " +
                    "kleur, die automatisch schaalt op de hoogste waarde. " +
                    "Gebruik de eigen tabs van de sloten voor koppelen, " +
                    "kalibratie, of ander beheer — Combi is alleen-lezen."
            )
        )
    ),
    SENSORS(
        menuTitleEn = "Sensors",
        menuTitleNl = "Sensoren",
        menuSubtitleEn = "Two independent slots, supported hardware, and the BG simulator",
        menuSubtitleNl = "Twee onafhankelijke sloten, ondersteunde hardware, en de BG-simulator",
        sections = listOf(
            ManualSection(
                headingEn = "Two independent slots",
                headingNl = "Twee onafhankelijke sloten",
                bodyEn = "FCLGlucoLink can connect to two sensors at the same " +
                    "time, held in two independent \"slots\" (Slot A and " +
                    "Slot B — the tab bar shows one tab per slot, see " +
                    "\"Home screen\" in this guide). Each slot can hold " +
                    "any sensor type — including two of the same type at " +
                    "once, for example during an overlap while starting a " +
                    "new sensor a few days before the old one runs out.\n\n" +
                    "Open \"Sensor\" on a slot's own tab to choose, " +
                    "switch, or clear (\"None\") that slot — it never " +
                    "affects the other slot.",
                bodyNl = "FCLGlucoLink kan tegelijk met twee sensoren " +
                    "verbinden, elk in een eigen onafhankelijk \"slot\" " +
                    "(Slot A en Slot B — de tabbalk toont één tab per slot, " +
                    "zie \"Home screen\" in deze handleiding). Elk slot kan " +
                    "elk sensortype bevatten — ook twee van hetzelfde type " +
                    "tegelijk, bijvoorbeeld tijdens een overlap terwijl je " +
                    "een nieuwe sensor start een paar dagen voordat de oude " +
                    "opraakt.\n\n" +
                    "Open \"Sensor\" op de eigen tab van een slot om " +
                    "dat slot te kiezen, te wisselen, of leeg te maken " +
                    "(\"None\") — dit heeft nooit invloed op het andere " +
                    "slot."
            ),
            ManualSection(
                headingEn = "One shared connection interface",
                headingNl = "Eén gedeelde verbindingsinterface",
                bodyEn = "Under the hood, every sensor type plugs into the same " +
                    "shared connection interface, so new sensor types can " +
                    "be added over time without changing how AAPS " +
                    "receives data.",
                bodyNl = "Intern sluit elk sensortype aan op " +
                    "dezelfde gedeelde verbindingsinterface, zodat er in de " +
                    "toekomst nieuwe sensortypes toegevoegd kunnen worden " +
                    "zonder dat verandert hoe AAPS data ontvangt."
            ),
            ManualSection(
                headingEn = "Available now: CareSens Air, Dexcom G6, and Dexcom G7 / ONE+",
                headingNl = "Nu beschikbaar: CareSens Air, Dexcom G6, en Dexcom G7 / ONE+",
                bodyEn = "CareSens Air: scan the QR code printed on the sensor " +
                    "packaging, then pick it from the Bluetooth list. " +
                    "Android will then ask for a Bluetooth pairing PIN — " +
                    "use the PIN code shown on the scan-result screen (also " +
                    "printed on the sensor packaging as \"PINCODE\"/\"CODE " +
                    "PIN\"), not whatever Android itself suggests (e.g. " +
                    "\"try 0000 or 1234\") — that's just a generic guess " +
                    "and won't work.\n\n" +
                    "Dexcom G6: enter the transmitter ID " +
                    "printed on the transmitter itself; FCLGlucoLink then " +
                    "connects directly to that transmitter.\n\n" +
                    "Dexcom G7 / " +
                    "ONE+: enter the 4-digit pairing code printed on the " +
                    "sensor applicator, then pick it from the Bluetooth " +
                    "list.",
                bodyNl = "CareSens Air: scan de QR-code die op de " +
                    "sensorverpakking staat, en kies 'm dan uit de " +
                    "Bluetooth-lijst. Android vraagt daarna om een " +
                    "Bluetooth-koppel-PIN — gebruik de PIN-code die op het " +
                    "scanresultaat-scherm getoond wordt (ook op de " +
                    "sensorverpakking afgedrukt als \"PINCODE\"/\"CODE " +
                    "PIN\"), niet wat Android zelf voorstelt (bv. \"probeer " +
                    "0000 of 1234\") — dat is slechts een generieke gok en " +
                    "werkt niet.\n\n" +
                    "Dexcom G6: voer de transmitter-ID in die op " +
                    "de transmitter zelf staat; FCLGlucoLink verbindt dan " +
                    "rechtstreeks met die transmitter.\n\n" +
                    "Dexcom G7 / ONE+: " +
                    "voer de 4-cijferige koppelcode in die op de " +
                    "sensorapplicator staat, en kies 'm dan uit de " +
                    "Bluetooth-lijst."
            ),
            ManualSection(
                headingEn = "Planned, not available yet",
                headingNl = "Gepland, nog niet beschikbaar",
                bodyEn = "Accu-Chek SmartGuide. It already appears in the sensor " +
                    "picker so the eventual switch is easy, but choosing " +
                    "it today shows a message that its connection support " +
                    "hasn't been built yet — pick CareSens Air, Dexcom G6, " +
                    "Dexcom G7 / ONE+, or the BG simulator below in the " +
                    "meantime.",
                bodyNl = "Accu-Chek SmartGuide. Die staat al in de sensorkeuze " +
                    "zodat de uiteindelijke overstap makkelijk is, maar hem " +
                    "nu kiezen toont een melding dat de " +
                    "verbindingsondersteuning nog niet gebouwd is — kies in " +
                    "de tussentijd CareSens Air, Dexcom G6, Dexcom G7 / " +
                    "ONE+, of de BG-simulator hieronder."
            ),
            ManualSection(
                headingEn = "BG simulator (virtual sensor)",
                headingNl = "BG-simulator (virtuele sensor)",
                bodyEn = "No physical hardware at all. It sends fictitious values " +
                    "through exactly the same path a real sensor uses " +
                    "(local storage, plus the xDrip broadcast to AAPS), " +
                    "which makes it useful for checking that the " +
                    "connection to AAPS works, or for trying out " +
                    "calibration/smoothing, before a real sensor is ever " +
                    "paired.",
                bodyNl = "Helemaal geen fysieke hardware. Stuurt fictieve " +
                    "waarden via precies hetzelfde pad dat een echte sensor " +
                    "gebruikt (lokale opslag, plus de xDrip-uitzending naar " +
                    "AAPS), wat 'm handig maakt om te controleren of de " +
                    "verbinding met AAPS werkt, of om kalibratie/filtering " +
                    "uit te proberen, voordat er ooit een echte sensor " +
                    "gekoppeld wordt."
            ),
            ManualSection(
                headingEn = "Random, open-ended testing",
                headingNl = "Willekeurig, doorlopend testen",
                bodyEn = "\"Manual value\" sends one number you type in, either " +
                    "once or repeating automatically every 5 minutes.\n\n" +
                    "\"Random values\" generates a new, realistic reading " +
                    "relative to the previous one at every step — mostly " +
                    "stable, occasionally a meal-like rise and fall — " +
                    "useful for open-ended connectivity testing (it just " +
                    "keeps generating plausible-looking data for as long " +
                    "as you leave it running, with no fixed script or end " +
                    "point) without you having to make up numbers yourself.",
                bodyNl = "\"Manual value\" (handmatige waarde) stuurt één " +
                    "getal dat je zelf intypt, ofwel eenmalig of automatisch " +
                    "herhalend elke 5 minuten.\n\n" +
                    "\"Random values\" " +
                    "(willekeurige waarden) genereert bij elke stap een " +
                    "nieuwe, realistische meting relatief aan de vorige — " +
                    "meestal stabiel, af en toe een maaltijd-achtige " +
                    "stijging en daling — handig voor verbindingstests " +
                    "zonder vast script of eindpunt (het blijft gewoon " +
                    "geloofwaardige data genereren zolang je de simulator " +
                    "laat draaien) zonder dat je zelf getallen hoeft te " +
                    "verzinnen."
            ),
            ManualSection(
                headingEn = "External list (reproducible testing)",
                headingNl = "Extern bestand (reproduceerbaar testen)",
                bodyEn = "Pick a text file with one BG value per line, in mmol/L " +
                    "regardless of your display unit setting (see " +
                    "\"Settings\" in this guide) — " +
                    "for example an earlier problem episode exported from " +
                    "your own logs — and the simulator replays it in that " +
                    "exact order, looping back to the start once it " +
                    "reaches the end. Because the same file always " +
                    "produces the same sequence, this is the way to " +
                    "replay a specific scenario exactly, rather than " +
                    "random data.\n\n" +
                    "Random values and the external list can " +
                    "both run at real-time speed (one value every 5 " +
                    "minutes, like a real sensor) or accelerated (every " +
                    "minute, for a quick test run).",
                bodyNl = "Kies een tekstbestand met één BG-waarde per regel, " +
                    "in mmol/L ongeacht je weergave-eenheid-instelling (zie " +
                    "\"Settings\" in deze handleiding) — bijvoorbeeld een " +
                    "eerdere probleemepisode geëxporteerd uit je eigen " +
                    "logs — en de simulator speelt die in exact die " +
                    "volgorde af, en begint weer van voren zodra hij het " +
                    "einde bereikt. Omdat hetzelfde bestand altijd dezelfde " +
                    "reeks oplevert, is dit de manier om een specifiek " +
                    "scenario exact te herhalen, in plaats van willekeurige " +
                    "data.\n\n" +
                    "Willekeurige waarden en het externe bestand " +
                    "kunnen beide op realtime-snelheid draaien (één waarde " +
                    "elke 5 minuten, zoals een echte sensor) of versneld " +
                    "(elke minuut, voor een snelle testrun)."
            ),
            ManualSection(
                headingEn = "Switching sensors",
                headingNl = "Sensoren wisselen",
                bodyEn = "Calibration data is cleared automatically whenever you " +
                    "switch to a genuinely different physical sensor on " +
                    "that slot (not on an ordinary reconnect to the same " +
                    "one) — that includes switching between the simulator " +
                    "and a real sensor, so old fingerstick values never " +
                    "carry over to data they don't apply to. This is " +
                    "entirely per slot: switching the sensor on one tab " +
                    "never touches the other slot's own calibration data.",
                bodyNl = "Kalibratiegegevens worden automatisch gewist zodra " +
                    "je op dat slot overstapt naar een écht andere fysieke " +
                    "sensor (niet bij een gewone herverbinding met dezelfde) " +
                    "— dat geldt ook voor het wisselen tussen de simulator " +
                    "en een echte sensor, zodat oude vingerprikwaarden nooit " +
                    "meegenomen worden naar data waar ze niet op slaan. Dit " +
                    "is volledig per slot: de sensor op één tab wisselen " +
                    "raakt nooit de eigen kalibratiegegevens van het " +
                    "andere slot."
            )
        )
    ),
    SETTINGS(
        menuTitleEn = "Settings",
        menuTitleNl = "Instellingen",
        menuSubtitleEn = "Which slot feeds AAPS, and what the other screens do",
        menuSubtitleNl = "Welk slot AAPS voedt, en wat de andere schermen doen",
        sections = listOf(
            ManualSection(
                headingEn = "Sending BG to AAPS",
                headingNl = "BG naar AAPS zenden",
                bodyEn = "\"Send BG to AAPS from\" is a Slot A / Slot B / Off " +
                    "choice — at most one slot feeds AAPS via the xDrip " +
                    "protocol at any time, never both at once. Whichever " +
                    "slot is currently selected shows a green stripe " +
                    "under its tab title (and \"Sending to AAPS\" on that " +
                    "tab and on the Combi table); the other slot shows a " +
                    "red stripe instead.",
                bodyNl = "\"Send BG to AAPS from\" is een keuze tussen Slot " +
                    "A / Slot B / Off — maximaal één slot voedt AAPS via " +
                    "het xDrip-protocol tegelijk, nooit beide tegelijk. " +
                    "Welk slot momenteel geselecteerd is, toont een groene " +
                    "streep onder zijn tab-titel (en \"Sending to AAPS\" op " +
                    "die tab en op de Combi-tabel); het andere slot toont " +
                    "in plaats daarvan een rode streep."
            ),
            ManualSection(
                headingEn = "When to turn it off",
                headingNl = "Wanneer uitzetten",
                bodyEn = "Set it to \"Off\", or point it at the other slot, if " +
                    "you want to test a sensor here while a separate " +
                    "xDrip app is the one actually feeding AAPS — " +
                    "otherwise AAPS would receive conflicting values from " +
                    "two sources at once. Local storage (so each tab's " +
                    "ring/chart and the Combi tab keep working) happens " +
                    "regardless of this choice.",
                bodyNl = "Zet 'm op \"Off\", of wijs het andere slot aan, als " +
                    "je hier een sensor wilt testen terwijl een aparte " +
                    "xDrip-app AAPS daadwerkelijk voedt — anders krijgt AAPS " +
                    "tegenstrijdige waarden van twee bronnen tegelijk. " +
                    "Lokale opslag (zodat de cirkel/grafiek van elke tab en " +
                    "de Combi-tab blijven werken) gebeurt ongeacht deze " +
                    "keuze."
            ),
            ManualSection(
                headingEn = "Calibration, smoothing, and alarms",
                headingNl = "Kalibratie, filtering, en alarmen",
                bodyEn = "Those switches also live on this screen — see their own " +
                    "topics in this guide for what they do and how to set " +
                    "them up.",
                bodyNl = "Die schakelaars staan ook op dit scherm — zie hun " +
                    "eigen onderwerpen in deze handleiding voor wat ze doen " +
                    "en hoe je ze instelt."
            ),
            ManualSection(
                headingEn = "Display unit",
                headingNl = "Weergave-eenheid",
                bodyEn = "Choose mmol/L or mg/dL — controls charts, the status " +
                    "ring, fingerstick/simulator input, and the connection " +
                    "notification. Storage and the value sent to AAPS " +
                    "always stay mg/dL underneath, regardless of this " +
                    "setting, so switching it is purely cosmetic and never " +
                    "affects dosing.",
                bodyNl = "\"Kies mmol/L of mg/dL\" bepaalt grafieken, de " +
                    "statuscirkel, vingerprik-/simulatorinvoer, en de " +
                    "verbindingsmelding. Opslag en de naar AAPS verstuurde " +
                    "waarde blijven intern altijd mg/dL, ongeacht deze " +
                    "instelling, dus 'm omzetten is puur cosmetisch en " +
                    "heeft nooit invloed op doseren."
            ),
            ManualSection(
                headingEn = "Bg prediction",
                headingNl = "Bg-voorspelling",
                bodyEn = "\"Show Bg prediction\" adds a 1-hour forecast to the " +
                    "chart on the tab it's shown on (see \"Home screen\" " +
                    "in this guide) — based only on the recent trend and " +
                    "how much it's been varying, with no insulin-on-board " +
                    "or meal information involved. Treat it as a rough " +
                    "indication, not a precise prediction.",
                bodyNl = "\"Show Bg prediction\" voegt een 1-uursvoorspelling " +
                    "toe aan de grafiek op de tab waar het getoond wordt " +
                    "(zie \"Home screen\" in deze handleiding) — uitsluitend " +
                    "gebaseerd op de recente trend en hoeveel die " +
                    "geschommeld heeft, zonder insuline-aan-boord- of " +
                    "maaltijdinformatie. Behandel het als een ruwe " +
                    "indicatie, niet als een precieze voorspelling."
            ),
            ManualSection(
                headingEn = "Universal trusted source code",
                headingNl = "Universele vertrouwde broncode",
                bodyEn = "Makes every sensor (including the simulator) identify " +
                    "itself to AAPS with a single description that's " +
                    "trusted for \"SMB Always\" on both AAPS 3 and AAPS 4 " +
                    "— the trade-off is that AAPS/Nightscout then shows a " +
                    "generic Dexcom label instead of your actual sensor's " +
                    "name.\n\n" +
                    "Off sends the best-matching description per " +
                    "sensor instead, which may not enable SMB Always on " +
                    "every AAPS version.",
                bodyNl = "Laat elke sensor (inclusief de simulator) zich bij " +
                    "AAPS identificeren met één omschrijving die vertrouwd " +
                    "wordt voor \"SMB Always\" op zowel AAPS 3 als AAPS 4 " +
                    "— de afweging is dat AAPS/Nightscout dan een generiek " +
                    "Dexcom-label toont in plaats van de naam van je echte " +
                    "sensor.\n\n" +
                    "Staat dit uit, dan stuurt FCLGlucoLink in " +
                    "plaats daarvan per sensor de best passende " +
                    "omschrijving, wat mogelijk niet op elke AAPS-versie " +
                    "SMB Always inschakelt."
            ),
            ManualSection(
                headingEn = "Automatic re-pair",
                headingNl = "Automatisch opnieuw koppelen",
                bodyEn = "If the phone's Bluetooth pairing with your sensor is " +
                    "unexpectedly lost after it worked before, FCLGlucoLink " +
                    "can try to silently re-pair instead of waiting for " +
                    "you to reconnect by hand.\n\n" +
                    "It only acts on a sensor " +
                    "it has successfully connected to before, never a " +
                    "brand-new one — but note that it removes and " +
                    "re-creates the phone's Bluetooth pairing, which " +
                    "affects the whole phone: if another app (e.g. xDrip+) " +
                    "is also paired with the same sensor, its pairing " +
                    "breaks too.",
                bodyNl = "Als de Bluetooth-koppeling van de telefoon met je " +
                    "sensor onverwacht verdwijnt nadat die eerder werkte, " +
                    "kan FCLGlucoLink proberen stilletjes opnieuw te " +
                    "koppelen in plaats van te wachten tot jij handmatig " +
                    "opnieuw verbindt.\n\n" +
                    "Het grijpt alleen in bij een sensor " +
                    "waarmee eerder al succesvol verbonden is, nooit bij " +
                    "een gloednieuwe — maar let op: het verwijdert en " +
                    "herbouwt de Bluetooth-koppeling van de telefoon, wat de " +
                    "hele telefoon beïnvloedt: als een andere app (bv. " +
                    "xDrip+) ook gekoppeld is met dezelfde sensor, breekt " +
                    "die koppeling ook."
            ),
            ManualSection(
                headingEn = "Expert mode",
                headingNl = "Expertmodus",
                bodyEn = "Choose which sensor types show up in the sensor picker " +
                    "on each slot's own tab — for example, hide the BG " +
                    "simulator once you no longer need it for testing, so " +
                    "it can't be picked by accident. Every sensor type is " +
                    "visible by default.",
                bodyNl = "Kies welke sensortypes verschijnen in de sensorkeuze " +
                    "op de eigen tab van elk slot — verberg bijvoorbeeld de " +
                    "BG-simulator zodra je die niet meer nodig hebt om te " +
                    "testen, zodat hij niet per ongeluk gekozen kan worden. " +
                    "Elk sensortype is standaard zichtbaar."
            )
        )
    ),
    CALIBRATION(
        menuTitleEn = "Calibration",
        menuTitleNl = "Kalibratie",
        menuSubtitleEn = "Correcting sensor readings with your own fingerstick values",
        menuSubtitleNl = "Sensormetingen corrigeren met je eigen vingerprikwaarden",
        sections = listOf(
            ManualSection(
                headingEn = "What it does",
                headingNl = "Wat het doet",
                bodyEn = "If your sensor tends to read a bit high or low compared " +
                    "to a fingerstick meter, calibration corrects for " +
                    "that. Turn \"Enable calibration\" on in Settings, " +
                    "then open the \"Calibration\" button on a slot's own " +
                    "tab and add a few fingerstick readings over time — " +
                    "the entry screen pre-fills the current sensor value " +
                    "so you only need to adjust it to match your meter.",
                bodyNl = "Als je sensor de neiging heeft iets hoger of lager " +
                    "te meten dan een vingerprikmeter, corrigeert kalibratie " +
                    "daarvoor. Zet \"Enable calibration\" aan in Settings, " +
                    "open dan de \"Calibration\"-knop op de eigen tab van " +
                    "een slot en voeg na verloop van tijd een paar " +
                    "vingerprikmetingen toe — het invoerscherm vult de " +
                    "huidige sensorwaarde al in, zodat je die alleen nog " +
                    "hoeft aan te passen aan je meter."
            ),
            ManualSection(
                headingEn = "Per slot",
                headingNl = "Per slot",
                bodyEn = "\"Enable calibration\" itself is a single switch that " +
                    "makes the \"Calibration\" button available on both " +
                    "tabs — but each slot's own fingerstick entries, fit, " +
                    "and manual offset are kept completely separate, so " +
                    "calibrating one sensor never affects the other.",
                bodyNl = "\"Enable calibration\" zelf is één schakelaar die de " +
                    "\"Calibration\"-knop op beide tabs beschikbaar maakt — " +
                    "maar de eigen vingerprikwaarden, curve, en handmatige " +
                    "offset van elk slot worden volledig los van elkaar " +
                    "bewaard, zodat het kalibreren van de ene sensor nooit " +
                    "de andere beïnvloedt."
            ),
            ManualSection(
                headingEn = "Adding entries",
                headingNl = "Waarden toevoegen",
                bodyEn = "With two or more entries spread over time, FCLGlucoLink " +
                    "fits a curve through them and applies it to every new " +
                    "sensor reading — the more entries, spread across " +
                    "different BG levels, the better the fit. A single " +
                    "entry alone still works (a plain, fixed offset " +
                    "shift), but won't capture a sensor whose error " +
                    "changes at different glucose levels.",
                bodyNl = "Met twee of meer waarden verspreid over de tijd past " +
                    "FCLGlucoLink er een curve doorheen en past die toe op " +
                    "elke nieuwe sensormeting — hoe meer waarden, verspreid " +
                    "over verschillende BG-niveaus, hoe beter de curve past. " +
                    "Eén enkele waarde werkt ook nog (een simpele, vaste " +
                    "offset-verschuiving), maar vangt geen sensor op " +
                    "waarvan de fout verschilt per glucoseniveau."
            ),
            ManualSection(
                headingEn = "Switching sensors",
                headingNl = "Sensoren wisselen",
                bodyEn = "Calibration data is cleared automatically whenever you " +
                    "start a new physical sensor on that slot, so old " +
                    "fingerstick values never carry over to a sensor they " +
                    "don't apply to.",
                bodyNl = "Kalibratiegegevens worden automatisch gewist zodra " +
                    "je op dat slot een nieuwe fysieke sensor start, zodat " +
                    "oude vingerprikwaarden nooit meegenomen worden naar een " +
                    "sensor waar ze niet op slaan."
            )
        ),
        showAapsWarning = true
    ),
    SMOOTHING(
        menuTitleEn = "Smoothing",
        menuTitleNl = "Filtering",
        menuSubtitleEn = "Damping sensor noise with a Kalman filter",
        menuSubtitleNl = "Sensorruis dempen met een Kalman-filter",
        sections = listOf(
            ManualSection(
                headingEn = "What it does",
                headingNl = "Wat het doet",
                bodyEn = "Sensors occasionally report a single noisy or spiky " +
                    "value that doesn't reflect a real, fast change in " +
                    "glucose. Turn \"Enable smoothing\" on in Settings to " +
                    "apply a Kalman filter (the same family of technique " +
                    "AAPS itself uses) that damps out that kind of noise " +
                    "while still tracking genuine trends (meals, insulin) " +
                    "without meaningful extra delay.",
                bodyNl = "Sensoren melden af en toe één ruizige of piekende " +
                    "waarde die geen echte, snelle verandering in glucose " +
                    "weerspiegelt. Zet \"Enable smoothing\" aan in Settings " +
                    "om een Kalman-filter toe te passen (dezelfde familie " +
                    "techniek die AAPS zelf gebruikt) die dat soort ruis " +
                    "dempt terwijl echte trends (maaltijden, insuline) " +
                    "zonder noemenswaardige extra vertraging gevolgd " +
                    "blijven worden."
            ),
            ManualSection(
                headingEn = "Runs after calibration",
                headingNl = "Werkt na kalibratie",
                bodyEn = "Smoothing always runs AFTER calibration, so it works on " +
                    "the already-corrected value — the two features are " +
                    "designed to be used together, in that order.",
                bodyNl = "Filtering werkt altijd NA kalibratie, dus op de al " +
                    "gecorrigeerde waarde — de twee functies zijn ontworpen " +
                    "om samen, in die volgorde, gebruikt te worden."
            ),
            ManualSection(
                headingEn = "Break-in filter for new sensors",
                headingNl = "Inloopfilter voor nieuwe sensoren",
                bodyEn = "A new physical sensor is often noisier than usual for " +
                    "its first hours or days. \"Break-in filter for new " +
                    "sensors\", right below \"Enable smoothing\", filters " +
                    "that extra noise more heavily right after a sensor " +
                    "starts, then eases off smoothly over the \"Duration\" " +
                    "you set (in hours) until it has no more effect.\n\n" +
                    "It only affects RISES, never falls — the goal is " +
                    "specifically to stop break-in noise from falsely " +
                    "triggering a dosing decision on the way up, not to " +
                    "dampen genuine falls. It applies to every sensor " +
                    "type the same way, regardless of whether you use " +
                    "calibration.",
                bodyNl = "Een nieuwe fysieke sensor is geeft vaak meer ruis " +
                    "dan normaal tijdens de eerste uren of dagen. \"Break-in " +
                    "filter for new sensors\", direct onder \"Enable " +
                    "smoothing\", filtert die extra ruis zwaarder vlak na " +
                    "het starten van een sensor, en bouwt dat daarna " +
                    "geleidelijk af over de \"Duration\" die je instelt (in " +
                    "uren) totdat het geen effect meer heeft.\n\n" +
                    "Het werkt alleen op STIJGINGEN, nooit op dalingen — het doel is " +
                    "specifiek om te voorkomen dat inloopruis ten onrechte " +
                    "een doseerbeslissing triggert op de weg omhoog, niet " +
                    "om echte dalingen te dempen. Het geldt voor elk " +
                    "sensortype op dezelfde manier, ongeacht of je " +
                    "kalibratie gebruikt."
            )
        ),
        showAapsWarning = true
    ),
    ALARMS(
        menuTitleEn = "Alarms",
        menuTitleNl = "Alarmen",
        menuSubtitleEn = "Low/high, predictive, and stale-data glucose alerts",
        menuSubtitleNl = "Lage/hoge, voorspellende, en verouderde-data glucosemeldingen",
        sections = listOf(
            ManualSection(
                headingEn = "What it does",
                headingNl = "Wat het doet",
                bodyEn = "Seven independent alarm types — Urgent Low, Low, High, " +
                    "Urgent High, Predictive Low, Predictive High, and " +
                    "Stale data — each named clearly enough that you " +
                    "won't need this guide to know what it warns about. " +
                    "Open \"Configure alarms\" on the Settings screen to " +
                    "set them up.",
                bodyNl = "Zeven onafhankelijke alarmtypes — Urgent Low, Low, " +
                    "High, Urgent High, Predictive Low, Predictive High, en " +
                    "Stale data — elk duidelijk genoeg genoemd zodat je deze " +
                    "handleiding niet nodig hebt om te weten waarvoor het " +
                    "waarschuwt. Open \"Configure alarms\" op het Settings-" +
                    "scherm om ze in te stellen."
            ),
            ManualSection(
                headingEn = "Master switch",
                headingNl = "Hoofdschakelaar",
                bodyEn = "One switch at the top turns every alarm on or off at " +
                    "once. Turning it off doesn't erase anything — each " +
                    "type's own settings (on/off, threshold, sound) stay " +
                    "exactly as you left them, ready to go the moment you " +
                    "switch alarms back on.",
                bodyNl = "Één schakelaar boven zet alle alarmen in één keer " +
                    "aan of uit. Uitzetten wist niets — de eigen " +
                    "instellingen van elk type (aan/uit, drempel, geluid) " +
                    "blijven precies " +
                    "zoals je ze achterliet, klaar om direct te werken " +
                    "zodra je alarmen weer aanzet."
            ),
            ManualSection(
                headingEn = "Predictive alarms have their own target",
                headingNl = "Voorspellende alarmen hebben hun eigen streefwaarde",
                bodyEn = "Predictive Low and Predictive High each have their own " +
                    "BG target and lead time, completely independent from " +
                    "the plain Low/High alarms — for example, Predictive " +
                    "Low can warn you well before a Low alarm would " +
                    "actually fire, at whatever target and lead time you " +
                    "choose.",
                bodyNl = "Predictive Low en Predictive High hebben elk hun " +
                    "eigen BG-streefwaarde en voorlooptijd, volledig los van " +
                    "de gewone Low/High-alarmen — Predictive Low kan je " +
                    "bijvoorbeeld ruim voordat een Low-alarm daadwerkelijk " +
                    "zou afgaan al waarschuwen, op de streefwaarde en " +
                    "voorlooptijd die jij instelt."
            ),
            ManualSection(
                headingEn = "Sound and vibration, per type",
                headingNl = "Geluid en trilling, per type",
                bodyEn = "Each alarm type has its own sound, picked from the same " +
                    "chooser Android uses for ringtones, plus an " +
                    "Alarm / Vibrate / Both choice for how it gets your " +
                    "attention. A separate \"Immediately\" / \"Gradual\" " +
                    "choice controls whether it starts at full volume or " +
                    "eases in.",
                bodyNl = "Elk alarmtype heeft zijn eigen geluid, gekozen uit " +
                    "dezelfde kiezer die Android voor ringtones gebruikt, " +
                    "plus een Alarm / Vibrate / Both-keuze voor hoe het je " +
                    "aandacht trekt. Een apart \"Immediately\" / " +
                    "\"Gradual\"-keuze bepaalt of het op volledig volume " +
                    "begint of geleidelijk opbouwt."
            ),
            ManualSection(
                headingEn = "Stop and snooze",
                headingNl = "Stoppen en snoozen",
                bodyEn = "A firing alarm opens a full-screen alert with the " +
                    "current BG value. \"Stop\" silences it for a while — " +
                    "it isn't permanent, so it comes back on its own if " +
                    "the situation hasn't improved. \"Snooze\" lets you " +
                    "pick 15, 30, or 60 minutes instead.",
                bodyNl = "Een afgaand alarm opent een volledig-scherm-melding " +
                    "met de huidige BG-waarde. \"Stop\" zet het een tijdje " +
                    "stil — dat is niet permanent, dus het komt vanzelf " +
                    "terug als de situatie niet verbeterd is. \"Snooze\" " +
                    "laat je in plaats daarvan 15, 30, of 60 minuten kiezen."
            )
        )
    ),
    DIAGNOSTICS(
        menuTitleEn = "Diagnostics",
        menuTitleNl = "Diagnostiek",
        menuSubtitleEn = "Log files, and sending one for support",
        menuSubtitleNl = "Logbestanden, en er één versturen voor ondersteuning",
        sections = listOf(
            ManualSection(
                headingEn = "Diagnostic log to file",
                headingNl = "Diagnostieklog naar bestand",
                bodyEn = "FCLGlucoLink always writes detailed connection/scan " +
                    "information to a text file on the device (one per " +
                    "day) — useful if you're troubleshooting a connection " +
                    "problem over several hours without a cable attached. " +
                    "Files older than 16 days are deleted automatically, " +
                    "so this never needs any manual cleanup.",
                bodyNl = "FCLGlucoLink schrijft altijd gedetailleerde " +
                    "verbindings-/scaninformatie naar een tekstbestand op " +
                    "het toestel (één per dag) — handig als je een " +
                    "verbindingsprobleem meerdere uren zonder kabel wilt " +
                    "uitzoeken. Bestanden ouder dan 16 dagen worden " +
                    "automatisch verwijderd, dus dit vraagt nooit handmatig " +
                    "opruimen."
            ),
            ManualSection(
                headingEn = "Sending a log file",
                headingNl = "Een logbestand versturen",
                bodyEn = "On the Settings screen, \"Send log files\" lets you pick " +
                    "one or more of these day-files and upload them, for " +
                    "example when asked to while troubleshooting an issue.",
                bodyNl = "Op het Settings-scherm kun je met \"Send log " +
                    "files\" één of meer van deze dag-bestanden kiezen en " +
                    "uploaden, bijvoorbeeld als daarom gevraagd wordt bij " +
                    "het oplossen van een probleem."
            )
        )
    ),
    BEST_RESULTS(
        menuTitleEn = "Getting the best results",
        menuTitleNl = "Het beste resultaat behalen",
        menuSubtitleEn = "A short checklist for day-to-day use",
        menuSubtitleNl = "Een korte checklist voor dagelijks gebruik",
        sections = listOf(
            ManualSection(
                headingEn = "Battery optimization",
                headingNl = "Batterijoptimalisatie",
                bodyEn = "Grant the exemption when the app asks — it's needed to " +
                    "keep the Bluetooth connection alive with the screen " +
                    "off.",
                bodyNl = "Verleen de uitzondering zodra de app erom vraagt — " +
                    "nodig om de Bluetooth-verbinding actief te houden als " +
                    "het scherm uit staat."
            ),
            ManualSection(
                headingEn = "Android 11 or older: background location (one-time)",
                headingNl = "Android 11 of ouder: achtergrondlocatie (eenmalig)",
                bodyEn = "On Android 11 and below, reliable Bluetooth scanning " +
                    "with the screen off also requires \"Allow all the " +
                    "time\" for location — not because FCLGlucoLink uses " +
                    "your location, but because that's how Android's " +
                    "older Bluetooth-scanning permission model works " +
                    "under the hood. If BG readings stop arriving after " +
                    "the screen has been off for a while, this is " +
                    "usually why.\n\n" +
                    "Use the button below once: it opens " +
                    "this app's system settings page — from there go to " +
                    "Permissions > Location and choose \"Allow all the " +
                    "time\" (not just \"Allow only while using the " +
                    "app\"). Not needed on Android 12 or newer.",
                bodyNl = "Op Android 11 en ouder vraagt betrouwbaar " +
                    "Bluetooth-scannen met het scherm uit ook \"Allow all " +
                    "the time\" voor locatie — niet omdat FCLGlucoLink je " +
                    "locatie gebruikt, maar omdat dat intern is hoe het " +
                    "oudere Bluetooth-scanmachtigingsmodel van Android " +
                    "werkt. Als BG-metingen stoppen na een tijdje met het " +
                    "scherm uit, is dit meestal de reden.\n\n" +
                    "Gebruik de knop " +
                    "hieronder eenmalig: die opent de systeeminstellingen-" +
                    "pagina van deze app — ga van daaruit naar Permissions " +
                    "> Location en kies \"Allow all the time\" (niet alleen " +
                    "\"Allow only while using the app\"). Niet nodig op " +
                    "Android 12 of nieuwer."
            ),
            ManualSection(
                headingEn = "Calibration entries",
                headingNl = "Kalibratiewaarden",
                bodyEn = "Give calibration at least two or three fingerstick " +
                    "entries, spread across a low, a normal, and a high " +
                    "reading if possible, before expecting it to improve " +
                    "accuracy — one single entry only shifts the whole " +
                    "curve by a fixed offset.",
                bodyNl = "Geef kalibratie minstens twee of drie " +
                    "vingerprikwaarden, verspreid over een lage, een " +
                    "normale, en een hoge meting indien mogelijk, voordat " +
                    "je verwacht dat het de nauwkeurigheid verbetert — één " +
                    "enkele waarde verschuift alleen de hele curve met een " +
                    "vaste offset."
            ),
            ManualSection(
                headingEn = "Smoothing",
                headingNl = "Filtering",
                bodyEn = "Leave it on for day-to-day looping; only turn it off " +
                    "temporarily if you specifically want to see " +
                    "completely raw sensor values (e.g. while comparing " +
                    "against a fingerstick reading).",
                bodyNl = "Laat het aan staan voor dagelijks looping; zet het " +
                    "alleen tijdelijk uit als je specifiek volledig ruwe " +
                    "sensorwaarden wilt zien (bv. bij het vergelijken met " +
                    "een vingerprikmeting)."
            ),
            ManualSection(
                headingEn = "Avoid double-correcting in AAPS",
                headingNl = "Voorkom dubbele correctie in AAPS",
                bodyEn = "If either calibration or smoothing is on here, turn the " +
                    "matching feature off inside AAPS itself — see their " +
                    "topics in this guide.",
                bodyNl = "Als kalibratie of filtering hier aan staat, zet dan " +
                    "de bijbehorende functie in AAPS zelf uit — zie hun " +
                    "eigen onderwerpen in deze handleiding."
            )
        ),
        showLocationPermissionLink = true
    )
}
