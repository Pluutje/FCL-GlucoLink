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
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+-specifiek status-/beheerscherm
 * ============================================================================
 *
 * 27/08/2026 (editor, RONDE 129, op verzoek — "Wat we in ieder geval alvast
 * kunnen doen is een status scherm maken vergelijkbaar met de g6 maar dan
 * niet met losse transmitter en losse sensor" plus een meegestuurde
 * screenshot van xDrip+'s "Systeem status"-scherm als bron voor welke
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
 * 27/08/2026 (editor, RONDE 130, op verzoek na een live-test van v142 —
 * "Wat niet goed is is dat hij tranmitter heet op het status scherm, dat
 * moet sensor worden [...] op het status scherm staat trouwens saved ipv de
 * code zelf dit is niet handig [...] dan is er nergens een knop om hem weer
 * in te voeren [...] Ook de disconnect knop werkt maar vervolgens kun je
 * niet weer connecten [...] het lijkt me handiger dat er een streepje staat
 * tot hij ingevuld is dan dat hij niet zichtbaar is") — vijf gerichte
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
 *    "—"-placeholders, EXPLICIET op verzoek ("het lijkt me handiger dat er
 *    een streepje staat tot hij ingevuld is dan dat hij niet zichtbaar
 *    is") — dit vervangt Ronde 129's bewuste keuze om deze rijen helemaal
 *    weg te laten. Onze eigen `DexcomG7Driver.kt` doet nog GEEN batterij-/
 *    firmware-/brain-state-uitvraag (zie die klasse se kdoc, "NIET GEPORT");
 *    zodra dat ooit toegevoegd wordt, hoeven alleen de databronnen van deze
 *    rijen aangepast te worden (nu allemaal hardcoded "—"), niet de rij-
 *    structuur zelf.
 *
 * 28/08/2026 (editor, RONDE 150, op verzoek — "geeft hij dan ook de data
 * als batterij en firmware version terug zoals xdrip ook netjes doet") —
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
                    // RONDE 130: "Sensor status"/"Brain state"/"Transmitter
                    // days" nog niet door DexcomG7Driver.kt uitgevraagd (die
                    // horen niet bij dit batterij-/firmwareverzoek, zie
                    // klasse-kdoc punt 5) — bewust WEL getoond als "—"
                    // i.p.v. weggelaten, expliciet zo gevraagd.
                    InfoRow("Sensor status", "—")
                    InfoRow("Brain state", "—")
                    // RONDE 150: deze drie komen nu WEL uit de driver, zie
                    // DexcomG7Driver.kt's queryBatteryIfStale()/
                    // queryFirmwareIfStale() en klasse-kdoc punt 5 voor het
                    // vertrouwensniveau (architectuur-bewijs uit xDrip+'s
                    // gedeelde broncode, nog niet HCI-bevestigd tegen een
                    // echte G7).
                    InfoRow("Firmware version", firmwareVersionText)
                    InfoRow("Battery last queried", batteryLastQueriedText)
                    InfoRow("Transmitter days", "—")
                    InfoRow("Voltage A", voltageAText)
                    InfoRow("Voltage B", voltageBText)
                }
            }

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
