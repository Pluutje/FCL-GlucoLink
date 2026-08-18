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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6CalibrationState
import com.fclglucolink.app.sensor.dexcomg6.dexcomG6FallbackWarmupSeconds
import com.fclglucolink.app.startBleConnectionService
import com.fclglucolink.app.stopBleConnectionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6-specifiek status-/beheerscherm
 * ============================================================================
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek — "Ieder sensor type krijgt dan
 * zijn eigen specifiek status/koppen/wissel scherm [...] bij de dexcom g6
 * dus ook om een andere transmitter te koppelen") — vervangt het G6-deel van
 * het vroegere, gedeelde SensorManagementScreen.kt (nu vervallen — zie
 * FclGlucoLinkNavHost.kt's kdoc voor het volledige herstructureringsverhaal).
 * Twee acties die bewust HIER staan en nergens anders:
 *  - "Switch transmitter" — een ANDERE fysieke transmitter koppelen (nieuwe
 *    ID intypen, zie DexcomG6SetupScreen.kt). Dit concept bestaat NIET bij
 *    CareSens Air (zie CareSensAirStatusScreen.kt) — vandaar dat dit niet
 *    langer op een gedeeld scherm stond, precies de eerdere klacht ("bij de
 *    caresens is helemaal geen sprake van een losse transmitter").
 *  - "Start new sensor" — een NIEUWE FYSIEKE SENSOR op de AL gekoppelde
 *    transmitter starten (zie DexcomG6NewSensorScreen.kt) — een apart
 *    concept van "switch transmitter" hierboven.
 *
 * Geopend vanaf StatusScreen.kt's (i)-knop op de compacte samenvatting
 * (alleen wanneer G6 al het actieve type is) — voor het WISSELEN naar G6 als
 * een ander type actief is, zie SensorSelectionScreen.kt.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomG6StatusScreen(
    onBack: () -> Unit,
    onSwitchTransmitter: () -> Unit,
    onStartNewSensor: () -> Unit,
    onDisconnect: () -> Unit,
    // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — nieuw, met
    // Slot A als standaard: zie PairingScreen.kt's identieke kdoc bij zijn
    // eigen [slot]-parameter.
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    // 09/08/2026 (editor, RONDE 70, op verzoek — "misschien is het handig
    // om toch een stop sensor knop te maken die een stop signaal zend [...]
    // dan de transmitter verwijderen 5 minuten wachten en weer opstarten")
    // — zie de kdoc bij het nieuwe DEXCOM_G6_PENDING_STOP_SENSOR_ONLY-
    // vlaggetje (AppSettings.kt) en het bijbehorende handling-blok in
    // DexcomG6Driver.kt's runControlSequence(): dit knop-alleen (géén nieuwe
    // sessie erna) i.t.t. de bestaande "stop-before-start"-combo die al
    // binnenin "Start new sensor" zit voor het andere scenario.
    var showStopSensorConfirm by remember { mutableStateOf(false) }
    val connectionState by ConnectionStatusBridge.state(slot).collectAsState()
    val transmitterId by settings.dexcomG6TransmitterId(slot).collectAsState(initial = null)
    val lastConnectedAtMs by settings.dexcomG6LastConnectedAtMs(slot).collectAsState(initial = null)
    val batteryInfo by settings.dexcomG6BatteryInfo(slot).collectAsState(initial = null)
    val pendingSensorStartCode by settings.dexcomG6PendingNewSensorCode(slot).collectAsState(initial = null)
    val sessionStartConfirmedAtMs by settings.dexcomG6SessionStartConfirmedAtMs(slot).collectAsState(initial = null)
    // 09/08/2026 (editor, RONDE 66) — vervangen de vaste 2h-aanname (zie
    // dexcomG6StatusText()'s kdoc hieronder): het door de transmitter zelf
    // gerapporteerde kalibratiebyte + de via VersionRequest2 opgevraagde
    // ECHTE opwarmduur, i.p.v. te gokken.
    val lastCalibrationStateRaw by settings.dexcomG6LastCalibrationState(slot).collectAsState(initial = null)
    val warmupSeconds by settings.dexcomG6WarmupSeconds(slot).collectAsState(initial = null)
    // 09/08/2026 (editor, RONDE 67, gebruikt sinds ronde 69 in de
    // Transmitter-infotabel hieronder) — warmupSeconds werd al hierboven
    // gelezen voor de opwarm-aftelling.
    val typicalSensorDays by settings.dexcomG6TypicalSensorDays(slot).collectAsState(initial = null)
    // 09/08/2026 (editor, RONDE 69) — zie Keys.DEXCOM_G6_LAST_CONFIRMED_SENSOR_CODE's
    // kdoc: voor de "Code"-rij in de nieuwe sensor-infotabel hieronder.
    val lastConfirmedSensorCode by settings.dexcomG6LastConfirmedSensorCode(slot).collectAsState(initial = null)
    // 09/08/2026 (editor, RONDE 71) — zie dexcomG6StatusText()'s kdoc: laat
    // een vastgelopen "Sending sensor start…" na een paar mislukkingen
    // duidelijk zien dat er iets misgaat.
    val sessionStartFailCount by settings.dexcomG6SessionStartFailCount(slot).collectAsState(initial = 0)

    // 09/08/2026 (editor, RONDE 65) — zie dexcomG6StatusText()'s kdoc: de
    // "Xh Ym warmup remaining"-aftelling moet blijven doortikken zolang dit
    // scherm open staat, ook zonder nieuwe data — zelfde 30s-tik-patroon als
    // StatusScreen.kt's nowTickMs.
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTickMs = System.currentTimeMillis()
        }
    }
    val statusText = dexcomG6StatusText(
        connectionState = connectionState,
        lastConnectedAtMs = lastConnectedAtMs,
        pendingSensorStartCode = pendingSensorStartCode,
        sessionStartConfirmedAtMs = sessionStartConfirmedAtMs,
        lastCalibrationStateRaw = lastCalibrationStateRaw,
        warmupSeconds = warmupSeconds,
        typicalSensorDays = typicalSensorDays,
        nowMs = nowTickMs,
        sessionStartFailCount = sessionStartFailCount
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dexcom G6") },
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
            // 09/08/2026 (editor, RONDE 69, op verzoek — "de weergave is nu
            // niet mooi [...] alle info netjes in een tabel [...] eerst de
            // sensor info [...] en dan daaronder netjes uitgelijnd de
            // transmitter info [...] niet op 1 regel maar netjes in
            // tabelvorm") — VOLLEDIGE herschrijving t.o.v. ronde 64-68: was
            // de gedeelde SensorInfoBlock (StatusScreen.kt, ook door
            // CareSens Air gebruikt) + twee losse prose-Text-regels erna
            // ("Transmitter ID: ..." en de "Type: ... · Sensor life: ... ·
            // Warmup: ..."-eenregelaar uit ronde 67). Nu: twee eigen,
            // nette tabellen — GEEN gedeeld component meer, precies volgens
            // dit bestand's eigen architectuurprincipe (zie klasse-kdoc
            // hierboven): alleen dit bestand hoeft te weten welke velden
            // voor G6 relevant zijn. De dynamische statusregel (kan een
            // lange, wisselende tekst zijn zoals "Sensor started · 2h 0m
            // warmup remaining") staat bewust LOS boven de tabellen, niet
            // als tabelrij — past niet netjes in een vaste kolombreedte.
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

            // "Started" = het moment dat de app zelf een bevestigde sessie-
            // start ontving (settings.dexcomG6SessionStartConfirmedAtMs, zie
            // ronde 65's kdoc) — geen apart van-de-transmitter-opgevraagd
            // dex-tijdstip nodig, dit wall-clock-moment is exact genoeg.
            // "End (est.)" = Started + de ECHTE, opgevraagde sensor-
            // levensduur (typicalSensorDays, VersionRequest2) — alleen
            // getoond zodra BEIDE bekend zijn, anders "—" i.p.v. een gok.
            // "Code" = settings.dexcomG6LastConfirmedSensorCode (ronde 69,
            // nieuw — blijft staan nadat de pending-code al gewist is).
            val dateFormat = SimpleDateFormat("dd-MM HH:mm", Locale.getDefault())
            val startedText = sessionStartConfirmedAtMs?.let { dateFormat.format(Date(it)) } ?: "—"
            val endText = if (sessionStartConfirmedAtMs != null && typicalSensorDays != null) {
                dateFormat.format(Date(sessionStartConfirmedAtMs!! + typicalSensorDays!!.toLong() * 24 * 60 * 60 * 1000))
            } else {
                "—"
            }
            DexcomG6InfoTable(
                title = "Sensor",
                rows = listOf(
                    "Started" to startedText,
                    "End (est.)" to endText,
                    "Code" to (lastConfirmedSensorCode ?: "—")
                )
            )

            // Transmitter-info: id + de heuristische, alleen-Anubis-of-
            // Original-labeling (zie DexcomG6CalibrationState.kt en ronde
            // 67's kdoc voor de 15-dagen-drempel-redenering — hier bewust
            // ÉÉN woord i.p.v. de eerdere volzin, op uitdrukkelijk verzoek)
            // + sensor life + warmup + verbindings-/batterijdiagnostiek.
            // 09/08/2026 (editor, RONDE 74) — hergebruikt nu
            // DexcomG6TransmitterType (DexcomG6CalibrationState.kt) i.p.v.
            // een eigen, hier gedupliceerde 15-dagen-`when` — zelfde
            // classificatie, nu op één centrale plek (ook gebruikt door de
            // nieuwe fallback-opwarmtijd hieronder).
            val typeText = when (com.fclglucolink.app.sensor.dexcomg6.DexcomG6TransmitterType.fromTypicalSensorDays(typicalSensorDays)) {
                com.fclglucolink.app.sensor.dexcomg6.DexcomG6TransmitterType.ANUBIS -> "Anubis"
                com.fclglucolink.app.sensor.dexcomg6.DexcomG6TransmitterType.ORIGINAL -> "Original"
                null -> "—"
            }
            val sensorLifeText = typicalSensorDays?.let { "$it days" } ?: "—"
            // 09/08/2026 (editor, RONDE 70) — zie de retry-gate-fix in
            // DexcomG6Driver.kt's runControlSequence(): een `warmupSeconds`
            // van 0 is nooit een echte waarde (ook Anubis' ~50 min is nog
            // honderden seconden) — `takeIf { it > 0 }` behandelt zo'n
            // stale/onbetrouwbare waarde (uit een oudere, gebugde ronde) hier
            // ook in de UI als "nog niet bekend", i.p.v. een verwarrende
            // "0m" te tonen tijdens het venster vóór de volgende requery.
            //
            // 09/08/2026 (editor, RONDE 74, op verzoek — "als die [warmupSeconds]
            // niet uit de transmitter komt dan moet hij bij een anubis gewoon
            // 30 minuten pakken en anders 1 uur") — wanneer de transmitter zelf
            // geen bruikbare waarde geeft, valt dit terug op de gebruiker-
            // gekozen schatting (dexcomG6FallbackWarmupSeconds(), zie die kdoc
            // voor de achtergrond) i.p.v. altijd "—" te tonen — duidelijk
            // gemarkeerd met "(est.)" zodat een ECHTE transmitter-waarde nooit
            // met een schatting te verwarren is.
            val realWarmupSeconds = warmupSeconds?.takeIf { it > 0 }
            val fallbackWarmupSecondsValue = if (realWarmupSeconds == null) {
                dexcomG6FallbackWarmupSeconds(typicalSensorDays)
            } else {
                null
            }
            val warmupText = (realWarmupSeconds ?: fallbackWarmupSecondsValue)?.let {
                val totalMin = it / 60
                val base = if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
                if (realWarmupSeconds == null) "$base (est.)" else base
            } ?: "—"
            val lastConnectedText = lastConnectedAtMs?.let { dateFormat.format(Date(it)) } ?: "—"
            val batteryText = if (batteryInfo != null) {
                "${batteryInfo!!.voltageA} mV / ${batteryInfo!!.voltageB} mV"
            } else {
                "—"
            }
            val temperatureText = batteryInfo?.temperatureC?.let { "$it °C" } ?: "—"
            DexcomG6InfoTable(
                title = "Transmitter",
                rows = listOf(
                    "ID" to (transmitterId ?: "—"),
                    "Type" to typeText,
                    "Sensor life" to sensorLifeText,
                    "Warmup" to warmupText,
                    "Last connected" to lastConnectedText,
                    "Battery voltage" to batteryText,
                    "Temperature" to temperatureText
                )
            )

            OutlinedButton(onClick = onSwitchTransmitter, modifier = Modifier.fillMaxWidth()) {
                Text("Switch transmitter")
            }

            OutlinedButton(onClick = onStartNewSensor, modifier = Modifier.fillMaxWidth()) {
                Text("Start new sensor")
            }
            Text(
                "Queues the code and reconnects to the transmitter to send it — " +
                    "no need to already be connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            // 09/08/2026 (editor, RONDE 70) — zie de klasse-brede kdoc
            // hierboven bij `showStopSensorConfirm`: expliciete, losstaande
            // stop-actie (destructief, dus met bevestiging) — voor de
            // gebruiker die zeker wil weten dat de sensor daadwerkelijk
            // gestopt is vóórdat ze de transmitter fysiek loskoppelen.
            OutlinedButton(
                onClick = { showStopSensorConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop sensor")
            }
            Text(
                "Sends a stop signal to the transmitter. Use this before " +
                    "physically removing the transmitter to restart a sensor — " +
                    "wait ~5 minutes after removal before reattaching, then use " +
                    "\"Start new sensor\" with the code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            if (connectionState !is ConnectionState.Disconnected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            }
        }
    }

    // 09/08/2026 (editor, RONDE 70) — destructief (beëindigt de lopende
    // sessie), dus met bevestiging, zelfde patroon als
    // DexcomG6NewSensorScreen.kt's showActiveSessionWarning-dialoog. Forceert
    // hier ook meteen een verse verbindpoging (stop+start de service) — zelfde
    // reden als queueSensorStart() daar: niet tot 5 minuten op de
    // voorspellende herverbind-cooldown laten wachten voor iets waar de
    // gebruiker expliciet NU op zit te wachten.
    //
    // 09/08/2026 (editor, RONDE 72, na live-test — "bij drukken op stop
    // sensor geeft hij direct in beeld 'sending sensor start'") — root
    // cause: `dexcomG6StatusText()` toont "Sending sensor start…" met de
    // HOOGSTE prioriteit zodra `pendingSensorStartCode` non-null is —
    // ONGEACHT wat "Stop sensor" zelf doet. Als er (zoals hier) nog een
    // klaarstaande "nieuwe sensor"-code hing van een eerdere, mislukte
    // poging, bleef die tekst het scherm dus volledig overheersen: de
    // gebruiker kon nooit zien of de stop zelf wél lukte, en de app bleef
    // — heel verwarrend — in feite gewoon de OUDE start-poging blijven
    // herhalen, ook na fysiek loskoppelen van de transmitter (dat wist een
    // in de app klaarstaande code namelijk niet — die staat los van de
    // transmitter zelf). Fix: "Stop sensor" is nu een ECHTE schone-lei-
    // actie — wist ELKE klaarstaande start-poging (code, stop-before-start-
    // vlaggetje, faalteller) VOORDAT de stop verstuurd wordt, zodat de
    // status daarna weer de daadwerkelijke, transmitter-gerapporteerde
    // toestand toont i.p.v. voor altijd "Sending sensor start…".
    if (showStopSensorConfirm) {
        AlertDialog(
            onDismissRequest = { showStopSensorConfirm = false },
            title = { Text("Stop sensor?") },
            text = {
                Text(
                    "Sends a stop signal to the transmitter for the current " +
                        "sensor session, and cancels any pending \"start new " +
                        "sensor\" code that hasn't gone through yet. Only do " +
                        "this if you're about to physically remove the " +
                        "transmitter from the sensor. This does not start a " +
                        "new sensor — use \"Start new sensor\" for that " +
                        "afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopSensorConfirm = false
                    scope.launch {
                        settings.clearDexcomG6PendingNewSensorCode(slot)
                        settings.consumeDexcomG6PendingStopBeforeStart(slot)
                        settings.resetDexcomG6SessionStartFailCount(slot)
                        settings.setDexcomG6PendingStopSensorOnly(slot, true)
                        stopBleConnectionService(context)
                        startBleConnectionService(context)
                    }
                }) {
                    Text("Stop sensor")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopSensorConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * 09/08/2026 (editor, RONDE 64/65) — één-regel-samenvatting voor
 * StatusScreen's compacte kaartje boven de BG-grafiek (zie kdoc daar,
 * CompactSensorSummary), EN de "Status"-rij op dit scherm zelf (zie de
 * Card met InfoRow("Status", ...) in DexcomG6StatusScreen() hierboven —
 * sinds ronde 69 niet meer via de gedeelde SensorInfoBlock). Bewust HIER (i.p.v. in
 * StatusScreen.kt zelf) — dat is precies het idee achter de
 * herstructurering: StatusScreen hoeft niet te weten WELKE velden voor G6
 * relevant zijn, dat weet alleen dit bestand.
 *
 * 09/08/2026 (editor, RONDE 65, op verzoek — "die spanning is niet
 * interessant en no connection wil ik ook niet zien. Wat hij moet tonen is
 * 'last connected: '. Wat ik van xdrip gewend ben is dat hij [...] toont bij
 * status 'sending sensor start' [...] tot hij de volgende connectie heeft
 * gehad en dan staat er sensor started en de resterende warmup time word
 * zichtbaar") — VOLLEDIGE herschrijving t.o.v. ronde 64's versie, die nog
 * rechtstreeks ConnectionState.Error's ruwe boodschap doorgaf (dat leverde
 * "No connection for 0m." op, zie DexcomG6Driver.
 * updateConnectionStatusAfterDisconnect()'s kdoc — gezet na ELKE disconnect,
 * ook een heel normale, geslaagde end-of-cycle disconnect, dus geen
 * betrouwbaar signaal) en de batterijspanning aanplakte. Prioriteit,
 * hoogste eerst:
 *  1) een sensor-start-code staat klaar om verstuurd te worden (of is net
 *     verstuurd maar nog niet bevestigd) — settings.
 *     dexcomG6PendingNewSensorCode is dan non-null, zie
 *     DexcomG6Driver.kt's runControlSequence(): pas gewist NA een bevestigd
 *     antwoord.
 *  2) de transmitter rapporteert zelf (via het kalibratiebyte in elk
 *     glucose-antwoord, zie DexcomG6CalibrationState.kt) dat de sensor
 *     gestopt/verlopen/mislukt is — toont die specifieke, betrouwbare tekst.
 *  3) de transmitter rapporteert zelf "aan het opwarmen" — toont de
 *     resterende tijd MET de ECHTE, per-transmitter opgevraagde warmup-duur
 *     (settings.dexcomG6WarmupSeconds, via VersionRequest2 — zie
 *     DexcomG6Driver.kt's runControlSequence()) als die al bekend is, anders
 *     gewoon "Sensor started" zonder aftelling i.p.v. een gegokt getal.
 *  4) simpelweg actief aan het verbinden/zoeken — kort, onschuldig
 *     transient signaal, geen "foutmelding".
 *  5) fallback: wanneer voor het laatst daadwerkelijk verbonden is, of "Not
 *     connected yet" als dat nog nooit gebeurd is. Dit vervangt ELKE
 *     rechtstreekse ConnectionState.Error-tekst — die staat hier bewust
 *     nergens meer in.
 *
 * 09/08/2026 (editor, RONDE 66, op verzoek — "je geeft aan dat hij een
 * warmup van 2h heeft [...] voor een anubis transmitter [...] klopt dat
 * niet") — VOLLEDIGE herschrijving van de opwarmlogica t.o.v. ronde 65: was
 * een vaste `G6_WARMUP_DURATION_MS = 2h`-aanname die voor een gemodificeerde
 * transmitter (bijv. Anubis, ~50 min warmup) domweg fout is. Nu: het
 * transmitter-gerapporteerde CalibrationState-byte bepaalt OF er opgewarmd
 * wordt (in plaats van een losse tijdsvergelijking), en de eveneens
 * transmitter-opgevraagde `warmupSeconds` (VersionRequest2) bepaalt HOE
 * LANG — beide werken identiek voor stock G6, G6+, én Anubis, zonder enige
 * hardcoded aanname in deze UI-laag.
 */
fun dexcomG6StatusText(
    connectionState: ConnectionState,
    lastConnectedAtMs: Long?,
    pendingSensorStartCode: String?,
    sessionStartConfirmedAtMs: Long?,
    lastCalibrationStateRaw: Int?,
    warmupSeconds: Int?,
    nowMs: Long,
    // 09/08/2026 (editor, RONDE 71, na live-test — "Sending sensor start"
    // bleef 10+ minuten onveranderd staan zonder enige aanwijzing dat er
    // iets mis was) — zie DexcomG6Driver.kt's runControlSequence()/
    // Keys.DEXCOM_G6_SESSION_START_FAIL_COUNT's kdoc: nu een herkenbare
    // hertry-loop mét zichtbare mislukkingen, i.p.v. een eeuwig identieke
    // "Sending sensor start…" die niet laat zien dat er al meerdere keren
    // geprobeerd én mislukt is. Default 0 houdt bestaande aanroepen
    // (StatusScreen.kt) werkend zonder de param verplicht door te geven.
    sessionStartFailCount: Int = 0,
    // 09/08/2026 (editor, RONDE 74, op verzoek — "Wat ik wel wil hebben bij
    // de opstart info bij de status zodat ik weet hoelang ik nog moet
    // wachten voor ik data krijg [...] als die [warmupSeconds] niet uit de
    // transmitter komt dan moet hij [...] gewoon 30/60 minuten pakken") —
    // nodig om, wanneer de transmitter zelf geen `warmupSeconds` teruggeeft,
    // de gebruiker-gekozen fallback-opwarmtijd te kunnen berekenen (zie
    // dexcomG6FallbackWarmupSeconds() in DexcomG6CalibrationState.kt).
    // Default `null` houdt bestaande aanroepen werkend (dan simpelweg geen
    // fallback-schatting mogelijk, exact het oude gedrag).
    typicalSensorDays: Int? = null
): String {
    val calibrationState = lastCalibrationStateRaw?.let { DexcomG6CalibrationState.fromRaw(it) }
    // 09/08/2026 (editor, RONDE 70) — `warmupSeconds > 0` erbij: zie de
    // retry-gate-fix in DexcomG6Driver.kt — een stale 0-waarde uit een
    // oudere, gebugde ronde mag hier geen (onzinnige) "0m resterend" tonen.
    //
    // 09/08/2026 (editor, RONDE 74) — `effectiveWarmupSeconds`/`isFallbackWarmup`:
    // bij ontbreken van een ECHTE, door de transmitter opgegeven waarde,
    // valt dit terug op de gebruiker-gekozen schatting. BELANGRIJK: deze
    // aftelling wordt hieronder NIET langer gegated op `calibrationState.
    // warmingUp()` — bij deze specifieke Anubis-transmitter bleek het
    // kalibratiebyte soms al vroeg "Ok"/bruikbaar te rapporteren (zie de
    // live-test met een implausibele 16.0 mmol/L-sprong ~8 min na start),
    // terwijl de gebruiker nog steeds wil weten hoelang de (door hem
    // ingestelde) veiligheidsmarge nog loopt — zie ook DexcomG6Driver.kt's
    // handleGlucoseResult()-gate, die metingen tijdens dit venster sowieso
    // onderdrukt ongeacht wat het kalibratiebyte zegt.
    val realWarmupSeconds = warmupSeconds?.takeIf { it > 0 }
    val effectiveWarmupSeconds = realWarmupSeconds ?: dexcomG6FallbackWarmupSeconds(typicalSensorDays)
    val isFallbackWarmup = realWarmupSeconds == null && effectiveWarmupSeconds != null
    val warmupRemainingMs = if (effectiveWarmupSeconds != null && sessionStartConfirmedAtMs != null) {
        effectiveWarmupSeconds * 1000L - (nowMs - sessionStartConfirmedAtMs)
    } else null
    return when {
        // 09/08/2026 (editor, RONDE 71) — na 2+ mislukte pogingen (getracked
        // in DexcomG6Driver.kt) is "Sending sensor start…" misleidend: er
        // wordt niet zomaar gewacht, er wordt herhaaldelijk geprobeerd én
        // afgewezen (meestal omdat de transmitter een vorige sessie nog als
        // actief beschouwt). Concrete suggestie i.p.v. stille herhaling.
        pendingSensorStartCode != null && sessionStartFailCount >= 2 ->
            "Sensor start rejected ${sessionStartFailCount}× (transmitter may still see the old sensor as active) — try \"Stop sensor\", wait a bit, then retry"
        pendingSensorStartCode != null -> "Sending sensor start…"
        calibrationState != null && calibrationState.sensorFailed() -> calibrationState.shortUserText()!!
        calibrationState != null && !calibrationState.warmingUp() && calibrationState.shortUserText() != null ->
            calibrationState.shortUserText()!!
        // 09/08/2026 (editor, RONDE 74) — deze tak vervangt/verbreedt de oude
        // `calibrationState?.warmingUp() == true`-voorwaarde: zolang het
        // (echte of fallback-)opwarmvenster nog niet verstreken is sinds de
        // bevestigde start, blijft de aftelling zichtbaar — ongeacht wat het
        // kalibratiebyte inmiddels al beweert (zie kdoc hierboven).
        warmupRemainingMs != null && warmupRemainingMs > 0 -> {
            val remainingMin = (warmupRemainingMs / 60_000L).coerceAtLeast(0)
            val suffix = if (isFallbackWarmup) " (est.)" else ""
            "Sensor started · ${remainingMin / 60}h ${remainingMin % 60}m warmup remaining$suffix"
        }
        calibrationState?.warmingUp() == true -> "Sensor started · warming up"
        connectionState is ConnectionState.Scanning -> "Searching for transmitter…"
        connectionState is ConnectionState.Connecting -> "Connecting…"
        lastConnectedAtMs != null ->
            "Last connected " + SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(lastConnectedAtMs))
        else -> "Not connected yet"
    }
}

/**
 * 09/08/2026 (editor, RONDE 69, op verzoek — "de weergave is nu niet mooi
 * [...] netjes in een tabel [...] type (en dan alleen Anubis of Original
 * vermelden en geen volzin) [...] maar niet op 1 regel maar netjes in
 * tabelvorm") — vervangt ronde 67's `dexcomG6TransmitterCapabilityText()`
 * (één samengestelde prose-regel). Simpele, herbruikbare label/waarde-tabel
 * — zelfde Card-stijl als de generieke `SensorInfoBlock` (StatusScreen.kt),
 * maar dit bestand bouwt 'm zelf op i.p.v. dat gedeelde component te
 * (mis)bruiken: dit scherm heeft twee VASTE, G6-specifieke tabellen nodig
 * (Sensor + Transmitter) i.p.v. één generieke rijenlijst, en de gedeelde
 * component kent bovendien geen "Code"/"End (est.)"-rijen die alleen voor
 * G6 relevant zijn.
 */
@Composable
private fun DexcomG6InfoTable(title: String, rows: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            rows.forEach { (label, value) -> InfoRow(label, value) }
        }
    }
}
