package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import com.fclglucolink.app.sensor.computeSensorStability
import com.fclglucolink.app.sensor.dexcomg7.DexcomG7Protocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+-specifiek status-/beheerscherm
 * ============================================================================
 *
 * 27/08/2026 (editor, RONDE 129, op verzoek voor een statusscherm
 * vergelijkbaar met de G6, maar zonder losse transmitter en losse sensor,
 * met een meegestuurde screenshot van xDrip+'s "Systeem status"-scherm als bron voor welke
 * velden zinvol zijn) — vóór deze ronde had G7 GEEN eigen statusscherm:
 * `FclGlucoLinkNavHost.kt`'s `statusRouteFor()` viel voor G7 terug op de
 * generieke `PairingScreen` (een device-ZOEKSCHERM), wat bij een tik op
 * de al-actieve G7-sensor voelde alsof de app "opnieuw wilde koppelen"
 * i.p.v. status te tonen (zie Ronde 127's kdoc).
 *
 * BEWUST ÉÉN VLAKKE TABEL (in tegenstelling tot DexcomG6StatusScreen.kt's
 * aparte Sensor-/Transmitter-tabellen): G7/ONE+ heeft, net als CareSens Air
 * (zie CareSensAirStatusScreen.kt's kdoc), geen voor de gebruiker relevant
 * onderscheid tussen "transmitter" en "sensor" — het is één wegwerpbaar
 * geheel, expliciet zo gevraagd.
 *
 * 27/08/2026 (editor, RONDE 130, op verzoek na een live-test van v142 met
 * vijf punten: het label "Transmitter" moest "Sensor" worden, de "Pairing
 * code"-rij toonde "Saved" i.p.v. de echte code zonder een manier om die
 * opnieuw in te voeren, de disconnect-knop liet geen weg terug naar
 * reconnecten zien, en een streepje-placeholder werd geprefereerd boven een
 * onzichtbare rij) — vijf gerichte
 * wijzigingen t.o.v. Ronde 129:
 * 1. "Transmitter" -> "Sensor" (titel van de tweede kaart).
 * 2. De "Pairing code"-rij toont nu de WERKELIJKE code (of "—"), niet meer
 *    het onbruikbare "Saved"/"Not saved"-onderscheid.
 * 3+4. De losse "Forget pairing code"-knop + bevestigingsdialoog (Ronde 129)
 *    is VERVANGEN door één altijd-zichtbare "Change pairing code"-knop die
 *    rechtstreeks naar `DexcomG7SetupScreen` navigeert (via
 *    `onChangePairingCode`, door NavHost gekoppeld aan
 *    `slotRoute(BASE_DEXCOM_G7_SETUP, slot)` — DEZELFDE, al werkende flow
 *    die "Switch transmitter"/"Start / switch sensor" elders in de app
 *    gebruiken: wist het device-adres, slaat de nieuwe code op, en
 *    navigeert meteen door naar het koppelscherm om opnieuw te verbinden).
 *    Dit lost TEGELIJK twee gemelde problemen op: (a) er was geen weg terug
 *    om een nieuwe/andere code in te voeren na "Forget", en (b) na
 *    "Disconnect" was er geen voor de hand liggende weg om weer te
 *    connecten zonder eerst een ANDERE sensor te kiezen en dan pas weer G7
 *    (de gemelde workaround) — deze knop is nu ALTIJD zichtbaar, ongeacht
 *    connectiestatus, en is zelf al de kortste weg terug naar een nieuwe
 *    koppelpoging. De oude "Forget, maar blijft op Saved/Connecting staan"-
 *    klacht bestaat hierdoor ook niet meer: er is geen tussentijdse
 *    "vergeten maar nog niet opnieuw gekoppeld"-status meer om in vast te
 *    lopen — de knop navigeert meteen weg van dit scherm.
 * 5. Extra rijen (Sensor Status, Brain State, Firmware Version, Battery
 *    Last queried, Transmitter Days, Voltage A, Voltage B) toegevoegd als
 *    "—"-placeholders, EXPLICIET op verzoek (een streepje-placeholder werd
 *    geprefereerd boven een onzichtbare rij) — dit vervangt Ronde 129's bewuste keuze om deze rijen helemaal
 *    weg te laten. Onze eigen `DexcomG7Driver.kt` doet nog GEEN batterij-/
 *    firmware-/brain-state-uitvraag (zie de kdoc van die klasse, "NIET GEPORT");
 *    zodra dat ooit toegevoegd wordt, hoeven alleen de databronnen van deze
 *    rijen aangepast te worden (nu allemaal hardcoded "—"), niet de rij-
 *    structuur zelf.
 *
 * 28/08/2026 (editor, RONDE 150, op verzoek om ook batterij- en firmware-
 * data terug te geven, net als xDrip netjes doet) —
 * punt 5 hierboven gedeeltelijk ingelost: "Firmware version", "Battery
 * last queried", "Voltage A" en "Voltage B" komen nu uit
 * AppSettings.dexcomG7BatteryInfo(slot)/dexcomG7FirmwareInfo(slot), gevuld
 * door DexcomG7Driver.kt's nieuwe queryBatteryIfStale()/
 * queryFirmwareIfStale() (zie DexcomG7Protocol.kt's kdoc bij
 * buildBatteryInfoRequest/buildFirmwareVersionRequest voor de protocol-
 * herkomst — het klassieke G5/G6-CRC16-envelop, hergebruikt over hetzelfde
 * Control-kanaal als het glucoseverzoek). "Sensor status", "Brain state"
 * en "Transmitter days" blijven bewust "—" — die horen niet bij dit
 * batterij-/firmwareverzoek. VERTROUWENSNIVEAU: architectuur-bewijs uit
 * xDrip+'s gedeelde broncode (dezelfde opcodes die al voor G6 bewezen
 * werken), NOG NIET HCI-bevestigd tegen een echte G7-sensor — de rijen
 * tonen gewoon "—" als de sensor niet reageert i.p.v. een foutmelding.
 *
 * 25/09/2026 (editor, RONDE 183, op verzoek na een live-koppeltest: "wat bij
 * de g7 op het hoofdscherm ontbreekt is de last connected tijd en de running
 * time... er staan behoorlijk veel niet ingevulde velden, een redelijk deel
 * daarvan kan beter weg") — drie wijzigingen:
 * 1. Negen structureel altijd-lege/weinigzeggende rijen verwijderd (BT
 *    firmware version, Hardware version, Other firmware version, ASIC,
 *    Build version, Version code, Inactive days, Max runtime days, Max
 *    inactive days) — zie [firmwareVersionText] e.a.'s kdoc hieronder voor de
 *    protocol-onderbouwing waarom deze niet zomaar "nog niet gevuld" zijn.
 * 2. De "Running"-rij van de Diagnostics-kaart (voorheen altijd leeg, zie
 *    [startedAtMs]'s kdoc) toont nu een looptijd.
 * 3. "Sensor status" toont nu ook de ruwe kalibratiestatus-code (hex) naast
 *    de platte-taal-tekst — zie [sensorStatusText]'s kdoc voor waarom dit
 *    GEEN "officiële Dexcom-foutcode" is (die vorm bleek niet te bestaan).
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomG7StatusScreen(
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onChangePairingCode: () -> Unit,
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    val connectionState by ConnectionStatusBridge.state(slot).collectAsState()
    val deviceAddress by settings.deviceAddress(slot).collectAsState(initial = null)
    val pairingCode by settings.dexcomG7PairingCode(slot).collectAsState(initial = null)
    val lastConnectedAtMs by settings.dexcomG7LastConnectedAtMs(slot).collectAsState(initial = null)
    // 28/08/2026 (editor, RONDE 150) — zie DexcomG7Driver.kt's
    // queryBatteryIfStale()/queryFirmwareIfStale() voor waar deze twee
    // vandaan komen; mirror van DexcomG6StatusScreen.kt's batteryInfo-regel.
    val batteryInfo by settings.dexcomG7BatteryInfo(slot).collectAsState(initial = null)
    val firmwareInfo by settings.dexcomG7FirmwareInfo(slot).collectAsState(initial = null)
    // 29/08/2026 (editor, RONDE 158) — zie AppSettings.kt's kdoc bij
    // setDexcomG7SensorStatus/setDexcomG7LastRawGlucose: gevuld door
    // DexcomG7Driver.kt's handleGlucoseResult() bij ELKE ontvangen meting,
    // los van de hoofdscherm-/AAPS-keten.
    val sensorStatusInfo by settings.dexcomG7SensorStatus(slot).collectAsState(initial = null)
    val lastRawGlucose by settings.dexcomG7LastRawGlucose(slot).collectAsState(initial = null)
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)
    // 24/09/2026 (editor, RONDE 181, op verzoek: dit ook op G7 tonen) — G7's
    // eigen sensor-specifieke melding staat al hierboven als "Sensor status"
    // (sensorStatusText) — niet verdubbeld in de Diagnostics-kaart.
    //
    // 25/09/2026 (editor, RONDE 183, CORRECTIE — was `runningSinceMs = null`
    // met als reden "G7 heeft nog geen eigen bevestigd sensor-startmoment")
    // — bleek bij het daadwerkelijk bouwen van de home-kaartje-fix niet
    // helemaal te kloppen: AppSettings.kt's generieke, per-slot fallback
    // (`effectiveSensorSessionStartedAtMs`, sinds Ronde 122) bestond al, en
    // DexcomG7Driver.kt vulde daarmee al die hele tijd al
    // `GlucoseReading.sensorStartedAtMs` (zie handleGlucoseResult()) — alleen
    // dit statusscherm vroeg 'm nooit op. Zie StatusScreen.kt's
    // CompactSensorSummary()-kdoc voor dezelfde toevoeging + de kanttekening
    // (eerste-koppel-moment, niet per se het fysieke sensor-startmoment; niet
    // opnieuw gezet bij een nieuwe fysieke G7-sensor in dezelfde slot).
    val startedAtMs by settings.effectiveSensorSessionStartedAtMsFlow(slot, SensorType.DEXCOM_G7)
        .collectAsState(initial = null)
    // 25/09/2026 (editor, RONDE 186) — zie DexcomG7Driver.kt's
    // handleGlucoseResult()-kdoc en StatusScreen.kt's sensorRuntimeText()-
    // kdoc: apparaat-onafhankelijke looptijd-schatting uit de sensor se
    // eigen klok, alleen zichtbaar in de "Running"-rij als deze significant
    // (>30 min) LATER uitkomt dan [startedAtMs] — bij een kleiner verschil,
    // of als de klok een LANGERE looptijd aangaf, is er niets te tonen (in
    // dat laatste geval heeft de driver [startedAtMs] zelf al gecorrigeerd).
    val clockEstimatedStartedAtMs by settings.dexcomG7ClockEstimatedStartedAtMs(slot)
        .collectAsState(initial = null)
    val readingStore = remember { GlucoseReadingStore(context) }
    val recentReadings by readingStore.recentReadings(hours = 2, slot = slot).collectAsState(initial = emptyList())
    val stability = remember(recentReadings) { computeSensorStability(recentReadings) }

    val dateFormat = SimpleDateFormat("dd-MM HH:mm", Locale.getDefault())
    val statusText = dexcomG7StatusText(connectionState, lastConnectedAtMs)
    val bluetoothLinkText = when (connectionState) {
        is ConnectionState.Scanning -> "Scanning"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Error -> "Error"
        ConnectionState.Disconnected -> "Disconnected"
    }
    val lastConnectedText = lastConnectedAtMs?.let { dateFormat.format(Date(it)) } ?: "—"
    // 28/08/2026 (editor, RONDE 150) — mirror van DexcomG6StatusScreen.kt's
    // batteryText-opbouw hierboven, zie klasse-kdoc onderaan dit bestand
    // (WERD BIJGEWERKT — zie punt 5) voor het vertrouwensniveau: dit toont
    // wat de driver daadwerkelijk terugkreeg, "—" zolang dat nog niets is
    // (nooit opgevraagd, timeout, of niet-ondersteund door deze specifieke
    // sensor).
    val firmwareVersionText = firmwareInfo?.firmwareVersion ?: "—"
    val batteryLastQueriedText = batteryInfo?.queriedAtMs?.let { dateFormat.format(Date(it)) } ?: "—"
    val voltageAText = batteryInfo?.voltageA?.let { "$it mV" } ?: "—"
    val voltageBText = batteryInfo?.voltageB?.let { "$it mV" } ?: "—"
    // 29/08/2026 (editor, RONDE 158) — [sensorStatusInfo] toont de sensor
    // eigen statusbyte van de sensor (bv. "SensorFailed7") zoals al die hele tijd al
    // in het diagnose-logboek stond, nu ook zichtbaar op dit scherm.
    //
    // 25/09/2026 (editor, RONDE 183, op verzoek: is het mogelijk om bij een
    // sensor-fout de ECHTE Dexcom-foutcode te tonen, zodat de juiste code
    // aan Dexcom doorgegeven kan worden?) — uitgezocht: Dexcom's eigen app
    // toont bij een kapotte sensor geen los alfanumeriek foutcodenummer,
    // alleen platte-taal-meldingen ("Sensor Failed", "Brief Sensor Issue") —
    // er is geen publiek bevestigde mapping naar zoiets als "E12" (zelfs
    // xDrip+'s eigen community heeft hier een open, onbeantwoorde vraag
    // over). Wat WEL bestaat: de ruwe kalibratiestatus-byte die de sensor
    // zelf meestuurt (DexcomG6CalibrationState, hergebruikt van G6) — dat is
    // exact waar "SensorFailed7" hierboven al uit voortkomt. Op verzoek
    // wordt het ruwe getal (hex, zoals ook in DexcomG6CalibrationState.kt's
    // eigen bron-tabel staat) nu naast de platte-taal-tekst getoond — geen
    // "officiële Dexcom-code", maar wel een precies technisch
    // referentiepunt mocht dat ooit nodig zijn. Zie AppSettings.kt's
    // DexcomG7SensorStatus.rawValue.
    val sensorStatusText = sensorStatusInfo?.let { info ->
        if (info.rawValue >= 0) {
            "${info.status} (code 0x${info.rawValue.toString(16).uppercase().padStart(2, '0')})"
        } else {
            info.status
        }
    } ?: "—"
    // "Last Bg" — BEWUST ongeacht geaccepteerd/genegeerd getoond (met een
    // duidelijk "(genegeerd)"-label als dat zo is), zie AppSettings.kt's
    // kdoc: dit is puur informatief voor dit scherm, de waarde stroomt
    // NOOIT door naar hoofdscherm/AAPS als ze genegeerd is — dat blijft
    // ongewijzigd via de bestaande [_readings]-keten geregeld.
    val lastBgText = lastRawGlucose?.let { raw ->
        val valueText = raw.mgdl.formatForDisplayWithUnit(displayUnit)
        val atText = dateFormat.format(Date(raw.atMs))
        if (raw.accepted) "$valueText ($atText)" else "$valueText — genegeerd ($atText)"
    } ?: "—"
    val lastBgIsRejected = lastRawGlucose?.accepted == false

    // 29/08/2026 (editor, RONDE 159, op verzoek om hier in principe alle
    // info te tonen die de sensor zelf teruggeeft) —
    // "—" overal waar het veld niet in het ontvangen antwoord aanwezig was
    // (-1/""), zelfde conventie als de rest van dit scherm.
    val transmitterStatusText = lastRawGlucose?.transmitterStatusText?.takeIf { it.isNotBlank() } ?: "—"
    val trendText = lastRawGlucose?.trendMgdlPerMin?.let { "%+.2f mg/dL/min".format(it) } ?: "—"
    val predictedGlucoseText = lastRawGlucose?.predictedGlucoseMgdl?.takeIf { it >= 0 }
        ?.let { it.toDouble().formatForDisplayWithUnit(displayUnit) } ?: "—"
    val sequenceText = lastRawGlucose?.sequence?.takeIf { it >= 0 }?.toString() ?: "—"

    val batteryStatusText = batteryInfo?.status?.takeIf { it >= 0 }
        ?.let { DexcomG7Protocol.transmitterStatusText(it) } ?: "—"
    val batteryResistanceText = batteryInfo?.resistance?.takeIf { it >= 0 }?.let { "$it Ω" } ?: "—"
    val transmitterDaysText = batteryInfo?.runtimeDays?.takeIf { it >= 0 }?.toString() ?: "—"

    // 25/09/2026 (editor, RONDE 183, op verzoek: "een redelijk deel van de
    // niet-ingevulde velden kan beter weg") — negen rijen VERWIJDERD i.p.v.
    // als "—"-placeholder laten staan: BT firmware version/Hardware version/
    // Other firmware version/ASIC/Inactive days/Max runtime days/Max
    // inactive days bleken bij nazoeken STRUCTUREEL leeg te blijven voor
    // deze sensor (en vermoedelijk elke G7) — niet "nog niet ingevuld", maar
    // horen simpelweg niet bij het antwoordformaat dat deze sensor gebruikt
    // (zie DexcomG7Protocol.kt's parseFirmwareVersion1()-kdoc: variant 1/
    // opcode 0x4A wordt ALTIJD als eerste geprobeerd en lukt hier, maar geeft
    // alleen firmwareVersion/buildVersion/versionCode/serial terug — de
    // andere velden bestaan letterlijk niet in dat antwoord, blijven dus de
    // volle 10 dagen op de "-1"/""-default staan). Build version en Version
    // code kregen wél een echte waarde, maar zijn op verzoek ook verwijderd:
    // een kale technische versiecode zegt een gewone gebruiker weinig.
    // Serial blijft WEL staan (ook een echte waarde, en wél iets dat
    // herkenbaar/te koppelen is aan de fysieke sensor).
    val serialText = firmwareInfo?.serial?.takeIf { it >= 0 }?.toString() ?: "—"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dexcom G7 / ONE+") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow(
                        "Status",
                        statusText,
                        valueColor = if (connectionState is ConnectionState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            null
                        }
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Sensor", style = MaterialTheme.typography.titleSmall)
                    InfoRow("Bluetooth link", bluetoothLinkText)
                    InfoRow("Address", deviceAddress ?: "—")
                    InfoRow("Pairing code", pairingCode ?: "—")
                    InfoRow("Last connected", lastConnectedText)
                    // 29/08/2026 (editor, RONDE 158/159) — "Sensor status"
                    // komt uit DexcomG7Driver.kt's handleGlucoseResult() (zie
                    // AppSettings.kt's kdoc bij setDexcomG7SensorStatus) —
                    // toont de eigen statusbyte van de sensor (bv.
                    // "SensorFailed7"), rood zolang die geen bruikbare
                    // meting toestaat. "Brain state" (Ronde 130) bleek bij
                    // nazoeken GEEN sensor-eigen gegeven te zijn — puur
                    // xDrip+'s eigen UI-label voor DEZELFDE calibratiestatus
                    // die hier al als "Sensor status" staat (zie
                    // `Ob1G5CollectionService.java`'s "Brain State"-regel,
                    // gebruikt exact dezelfde `state`) — op verzoek
                    // verwijderd i.p.v. als dubbele "—"-rij te laten staan.
                    InfoRow(
                        "Sensor status",
                        sensorStatusText,
                        valueColor = if (lastRawGlucose?.accepted == false) MaterialTheme.colorScheme.error else null
                    )
                    InfoRow(
                        "Last Bg",
                        lastBgText,
                        valueColor = if (lastBgIsRejected) MaterialTheme.colorScheme.error else null
                    )
                    InfoRow("Trend", trendText)
                    InfoRow("Predicted Bg", predictedGlucoseText)
                    InfoRow("Transmitter status", transmitterStatusText)
                    InfoRow("Sequence", sequenceText)
                    // RONDE 150: deze drie komen uit de driver, zie
                    // DexcomG7Driver.kt's queryBatteryIfStale()/
                    // queryFirmwareIfStale() en klasse-kdoc punt 5 voor het
                    // vertrouwensniveau (architectuur-bewijs uit xDrip+'s
                    // gedeelde broncode, nog niet HCI-bevestigd tegen een
                    // echte G7). RONDE 159: de rest van wat deze twee
                    // antwoorden al meegaven, nu ook zichtbaar.
                    InfoRow("Firmware version", firmwareVersionText)
                    InfoRow("Serial", serialText)
                    InfoRow("Battery last queried", batteryLastQueriedText)
                    InfoRow("Battery status", batteryStatusText)
                    InfoRow("Battery resistance", batteryResistanceText)
                    InfoRow("Transmitter days", transmitterDaysText)
                    InfoRow("Voltage A", voltageAText)
                    InfoRow("Voltage B", voltageBText)
                }
            }

            DiagnosticsCard(
                runningSinceMs = startedAtMs,
                stability = stability,
                runningSinceMsAlt = clockEstimatedStartedAtMs
            )

            // RONDE 130: altijd zichtbaar (niet meer beperkt tot pairingCode
            // != null) — zie klasse-kdoc punt 3+4 voor de volledige
            // onderbouwing (lost zowel "geen weg terug na Forget" als
            // "geen weg terug na Disconnect" in één keer op).
            OutlinedButton(onClick = onChangePairingCode, modifier = Modifier.fillMaxWidth()) {
                Text(if (pairingCode != null) "Change pairing code" else "Enter pairing code")
            }

            if (connectionState !is ConnectionState.Disconnected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            }
        }
    }
}

/**
 * 27/08/2026 (editor, RONDE 129) — zie dexcomG6StatusText()'s/
 * careSensAirCompactSummaryText()'s kdoc voor hetzelfde idee: bewust
 * eenvoudiger dan G6's variant (geen opwarm-/kalibratiestatussen — die
 * concepten bestaan voor G7 nog niet in deze driver, zie klasse-kdoc).
 */
fun dexcomG7StatusText(connectionState: ConnectionState, lastConnectedAtMs: Long?): String = when {
    connectionState is ConnectionState.Error -> connectionState.message
    connectionState is ConnectionState.Scanning -> "Searching for transmitter…"
    connectionState is ConnectionState.Connecting -> "Connecting…"
    lastConnectedAtMs != null ->
        "Last connected " + SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(lastConnectedAtMs))
    else -> "Not connected yet"
}
