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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.accuchek.AccuChekSmartGuideDriver
import com.fclglucolink.app.sensor.accuchek.AccuChekSmartGuideProtocol
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import com.fclglucolink.app.sensor.computeSensorStability
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — Accu-Chek SmartGuide-specifiek status-/beheerscherm
 * ============================================================================
 *
 * 01/10/2026 (editor, RONDE 198) — nieuw, onderdeel van de CGM Service-
 * herbouw (zie AccuChekSmartGuideProtocol.kt/AccuChekSmartGuideDriver.kt's
 * klasse-kdoc en README.md's Ronde 198-sectie). Structuur/compositiepatroon
 * bewust 1-op-1 gemirrord van DexcomG7StatusScreen.kt (zelfde platte
 * InfoRow-tabel, zelfde Scaffold/TopAppBar-opzet, zelfde gebruik van de
 * generieke [DiagnosticsCard] onderaan) — op verzoek: "alle echt nuttige
 * info" tonen, net als het G7-scherm al doet.
 *
 * Toont, bovenop wat G7's scherm al laat zien: fabrikant/model/serienummer/
 * firmware-/hardwarerevisie (Device Information Service), sessie-startmoment
 * en totale sessieduur (CGM Session Run Time, 0x2AAB), het bevestigde
 * communicatie-interval (zie AccuChekSmartGuideDriver.kt's klasse-kdoc punt
 * 5 — dit is precies wat laat zien dat de 5-minuten-betrouwbaarheidseis
 * daadwerkelijk actief is), en de volledige gedecodeerde CGM Status-
 * vlaggenset (kalibratie/batterij/temperatuur/sensorfout/buiten-bereik).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuChekSmartGuideStatusScreen(
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    slot: SensorSlot = SensorSlot.A
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    val connectionState by ConnectionStatusBridge.state(slot).collectAsState()
    val deviceAddress by settings.deviceAddress(slot).collectAsState(initial = null)
    val lastConnectedAtMs by settings.accuChekLastConnectedAtMs(slot).collectAsState(initial = null)
    val deviceInfo by settings.accuChekDeviceInfo(slot).collectAsState(
        initial = AppSettings.AccuChekDeviceInfo(null, null, null, null, null)
    )
    val cgmStatus by settings.accuChekCgmStatus(slot).collectAsState(initial = null)
    val sessionStartAtMs by settings.accuChekSessionStartAtMs(slot).collectAsState(initial = null)
    val sessionRunTimeMinutes by settings.accuChekSessionRunTimeMinutes(slot).collectAsState(initial = null)
    val confirmedIntervalMinutes by settings.accuChekCommunicationIntervalConfirmedMinutes(slot).collectAsState(initial = null)
    val lastQualityPercent by settings.accuChekLastQualityPercent(slot).collectAsState(initial = null)

    val readingStore = remember { GlucoseReadingStore(context) }
    val recentReadings by readingStore.recentReadings(hours = 2, slot = slot).collectAsState(initial = emptyList())
    val stability = remember(recentReadings) { computeSensorStability(recentReadings) }
    // 02/10/2026 (editor, RONDE 202, bugfix na live-test) — "Last read"
    // gebruikte tot nu toe cgmStatus?.atMs: het tijdstip van de ÉÉNMALIGE
    // CGM Status-characteristic-read bij het opzetten van de verbinding,
    // NIET het tijdstip van de laatst ontvangen ECHTE meting. Daardoor
    // bleef deze rij hangen op het connectiemoment, terwijl de grote
    // glucosewaarde en grafiek intussen gewoon elke 5 minuten bijwerkten —
    // precies wat de gebruiker meldde. Zelfde patroon als
    // DexcomG6StatusScreen.kt's `lastRealReading`/`lastRealReadingAtMs`.
    //
    // 02/10/2026 (editor, RONDE 208, op verzoek na live-melding) — "Last
    // read" (hierboven) en "Last connected" (hieronder, via
    // [lastConnectedAtMs]) zijn bewust TWEE verschillende dingen: "Last
    // read" is het tijdstip van de laatst ONTVANGEN BRUIKBARE glucosewaarde
    // (blijft dus terecht hangen zodra de sensorsessie stopt — zie
    // AccuChekSmartGuideDriver.kt's klasse-kdoc), "Last connected" is sinds
    // deze ronde het tijdstip van het laatst bevestigde, daadwerkelijke
    // CONTACT met de sensor (CGM Status-uitlezing of RACP-antwoord, bij
    // ELKE herverbinding, ongeacht of dat contact nieuwe data opleverde).
    // Een "Last connected"-tijdstip dat dicht bij nu ligt, terwijl "Last
    // read" en "Session stopped: Yes" allebei stil blijven, bewijst dus dat
    // de app niet is vastgelopen maar daadwerkelijk herhaaldelijk opnieuw
    // verbindt en een (terecht lege) respons van de sensor terugkrijgt —
    // precies het onderscheid dat de gebruiker hier miste. Zie
    // AccuChekSmartGuideDriver.kt's `handleDeviceInfoOrStatusRead()` en
    // `handleRacpResponse()` voor de twee plekken die dit bijwerken.
    val lastRealReading by readingStore.latestReading(slot = slot).collectAsState(initial = null)

    val dateFormat = SimpleDateFormat("dd-MM HH:mm", Locale.getDefault())
    val statusText = accuChekStatusText(connectionState, lastConnectedAtMs)
    val bluetoothLinkText = when (connectionState) {
        is ConnectionState.Scanning -> "Scanning"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Error -> "Error"
        ConnectionState.Disconnected -> "Disconnected"
    }
    val lastConnectedText = lastConnectedAtMs?.let { dateFormat.format(Date(it)) } ?: "—"

    val manufacturerText = deviceInfo.manufacturer ?: "—"
    val modelText = deviceInfo.model ?: "—"
    val serialText = deviceInfo.serial ?: "—"
    val firmwareText = deviceInfo.firmwareRevision ?: "—"
    val hardwareText = deviceInfo.hardwareRevision ?: "—"

    val sessionStartText = sessionStartAtMs?.let { dateFormat.format(Date(it)) } ?: "—"
    val sessionRunTimeText = sessionRunTimeMinutes?.let { "${it / 60 / 24} d ${(it / 60) % 24} h" } ?: "—"
    val sessionRemainingText = if (sessionStartAtMs != null && sessionRunTimeMinutes != null) {
        val elapsedMinutes = ((System.currentTimeMillis() - sessionStartAtMs!!) / 60_000L).toInt()
        val remainingMinutes = (sessionRunTimeMinutes!! - elapsedMinutes).coerceAtLeast(0)
        "${remainingMinutes / 60 / 24} d ${(remainingMinutes / 60) % 24} h"
    } else {
        "—"
    }

    // 01/10/2026 (editor, RONDE 198) — bevestigt dat de 5-minuten-eis
    // (AccuChekSmartGuideDriver.kt's klasse-kdoc punt 5) daadwerkelijk
    // actief is — rood zolang dat nog niet (of niet meer) het geval is.
    val intervalText = confirmedIntervalMinutes?.let { "$it min" } ?: "—"
    val intervalIsTarget = confirmedIntervalMinutes == 5
    val qualityText = lastQualityPercent?.let { "%.0f%%".format(it) } ?: "—"

    val statusFlags = cgmStatus?.let {
        AccuChekSmartGuideProtocol.decodeStatusFlags(it.statusByte, it.calTempByte, it.warningByte)
    }
    // RONDE 202: "Last read" toont nu het tijdstip van de laatst ontvangen
    // ECHTE meting (lastRealReading), niet meer cgmStatus?.atMs — zie kdoc
    // hierboven bij [lastRealReading].
    val lastReadText = lastRealReading?.timestampMs?.let { dateFormat.format(Date(it)) } ?: "—"

    // 01/10/2026 (editor, RONDE 200) — zelfde 30s-tik-patroon als
    // DexcomG6StatusScreen.kt's `nowTickMs` (zie dat scherm's eigen kdoc):
    // de "Warming up — Xh Ym remaining"-aftelling moet blijven doortikken
    // zolang dit scherm open staat, ook zonder nieuwe metingen.
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTickMs = System.currentTimeMillis()
        }
    }
    val warmupRemainingMs = sessionStartAtMs?.let {
        AccuChekSmartGuideDriver.WARMUP_DURATION_MINUTES * 60_000L - (nowTickMs - it)
    }
    val isWarmingUp = warmupRemainingMs != null && warmupRemainingMs > 0
    val warmupRemainingText = warmupRemainingMs?.takeIf { it > 0 }?.let {
        val remainingMin = (it / 60_000L).coerceAtLeast(0)
        "${remainingMin / 60}h ${remainingMin % 60}m remaining"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accu-Chek SmartGuide") },
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
                        valueColor = if (connectionState is ConnectionState.Error) MaterialTheme.colorScheme.error else null
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sensor", style = MaterialTheme.typography.titleSmall)
                    InfoRow("Bluetooth link", bluetoothLinkText)
                    InfoRow("Address", deviceAddress ?: "—")
                    InfoRow("Last connected", lastConnectedText)
                    InfoRow("Manufacturer", manufacturerText)
                    InfoRow("Model", modelText)
                    InfoRow("Serial", serialText)
                    InfoRow("Firmware revision", firmwareText)
                    InfoRow("Hardware revision", hardwareText)
                    InfoRow("Session started", sessionStartText)
                    InfoRow("Session total length", sessionRunTimeText)
                    InfoRow("Session time remaining", sessionRemainingText)
                    InfoRow(
                        "Communication interval",
                        intervalText,
                        valueColor = if (confirmedIntervalMinutes != null && !intervalIsTarget) MaterialTheme.colorScheme.error else null
                    )
                    InfoRow("Last reading quality", qualityText)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sensor status", style = MaterialTheme.typography.titleSmall)
                    InfoRow("Last read", lastReadText)
                    // 01/10/2026 (editor, RONDE 200) — de opwarm-aftelling
                    // staat bewust LOS, bóven de ruwe vlaggenlijst — zelfde
                    // geest als DexcomG6StatusScreen.kt (zie dat scherm's
                    // eigen "Sensor started · ... warmup remaining"-regel):
                    // neutrale/informatieve tekst i.p.v. de hieronder
                    // mogelijk alarmerend rode "Malfunction"-regel, zolang
                    // de sensor nog binnen zijn bekende opwarmvenster zit.
                    if (warmupRemainingText != null) {
                        InfoRow("Warming up", warmupRemainingText)
                    }
                    if (statusFlags == null) {
                        InfoRow("Flags", "—")
                    } else if (statusFlags.isAllClear) {
                        InfoRow("Flags", "All clear")
                    } else {
                        if (statusFlags.sessionStopped) InfoRow("Session stopped", "Yes", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.deviceBatteryLow) InfoRow("Battery", "Low", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.sensorTypeIncorrect) InfoRow("Sensor type", "Incorrect", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.sensorMalfunction) {
                            // 01/10/2026 (editor, RONDE 200, bugfix na
                            // live-test) — tijdens het bekende opwarmvenster
                            // is dit zeer waarschijnlijk gewoon de sensor's
                            // eigen "nog niet klaar"-bit, geen echte
                            // malfunctie (xDrip toont in diezelfde periode
                            // een nette aftelling, geen foutmelding) — toon
                            // dan neutraal i.p.v. alarmerend rood.
                            if (isWarmingUp) {
                                InfoRow("Sensor", "Warming up (not ready yet)")
                            } else {
                                InfoRow("Sensor", "Malfunction", valueColor = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (statusFlags.deviceSpecificAlert) InfoRow("Device alert", "Yes", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.generalDeviceFault) InfoRow("Device fault", "Yes", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.timeSyncRequired) InfoRow("Time sync", "Required")
                        // 01/10/2026 (editor, RONDE 200, aangevuld na gebruikersinput) —
                        // "Not allowed" + "Required" tegelijk is per spec plausibel
                        // (nog niet toegestaan, bv. nog aan het stabiliseren, maar zal
                        // straks wel vereist zijn) — geen decodeerfout, maar oogt
                        // zonder context tegenstrijdig. Eén gecombineerde regel
                        // wanneer beide gezet zijn.
                        //
                        // De gebruiker wist waar dit vandaan komt: de officiële
                        // Accu-Chek/MySugr-app blokkeert zelf kalibratie-invoer
                        // gedurende de eerste ~12 uur, om de sensor te laten
                        // stabiliseren — vermoedelijk is dat precies deze
                        // sensor-gerapporteerde vlag. Dit is dus puur
                        // INFORMATIEF — de vlag wordt nergens in deze codebase
                        // gebruikt om kalibratie-invoer te blokkeren
                        // (ui/CalibrationScreen.kt kent deze vlag niet en heeft
                        // geen enkele sensor-type-specifieke gate). FCLGlucoLink
                        // legt die beperking van de officiële app bewust NIET op
                        // — de gebruiker kan hier gewoon altijd een kalibratie
                        // invoeren, ook tijdens dit venster.
                        if (statusFlags.calibrationNotAllowed && statusFlags.calibrationRequired) {
                            InfoRow(
                                "Calibration",
                                "Not allowed yet by sensor (stabilizing — informational only, FCLGlucoLink doesn't block entry)"
                            )
                        } else {
                            if (statusFlags.calibrationNotAllowed) InfoRow("Calibration", "Not allowed")
                            if (statusFlags.calibrationRequired) InfoRow("Calibration", "Required", valueColor = MaterialTheme.colorScheme.error)
                        }
                        if (statusFlags.calibrationRecommended) InfoRow("Calibration", "Recommended")
                        if (statusFlags.sensorTemperatureTooHigh) InfoRow("Temperature", "Too high", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.sensorTemperatureTooLow) InfoRow("Temperature", "Too low", valueColor = MaterialTheme.colorScheme.error)
                        if (statusFlags.resultLowerThanDeviceCanProcess) InfoRow("Result", "Below device range")
                        if (statusFlags.resultHigherThanDeviceCanProcess) InfoRow("Result", "Above device range")
                        if (statusFlags.sensorRateOfDecreaseExceeded) InfoRow("Rate of decrease", "Exceeded")
                        if (statusFlags.sensorRateOfIncreaseExceeded) InfoRow("Rate of increase", "Exceeded")
                        if (statusFlags.deviceResultLowerThanPatientLowLevel) InfoRow("Result", "Below your low level")
                        if (statusFlags.deviceResultHigherThanPatientHighLevel) InfoRow("Result", "Above your high level")
                    }
                }
            }

            DiagnosticsCard(
                runningSinceMs = sessionStartAtMs,
                stability = stability,
                runningSinceMsAlt = null
            )

            if (connectionState !is ConnectionState.Disconnected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            }
        }
    }
}

/**
 * 01/10/2026 (editor, RONDE 198) — mirror van dexcomG7StatusText()'s kdoc:
 * bewust zonder opwarm-/kalibratiestatussen op dit hoogste niveau (die
 * staan hier al uitgesplitst in de aparte "Sensor status"-kaart hierboven).
 */
fun accuChekStatusText(connectionState: ConnectionState, lastConnectedAtMs: Long?): String = when {
    connectionState is ConnectionState.Error -> connectionState.message
    connectionState is ConnectionState.Scanning -> "Searching for sensor…"
    connectionState is ConnectionState.Connecting -> "Connecting…"
    lastConnectedAtMs != null ->
        "Last connected " + SimpleDateFormat("dd-MM HH:mm", Locale.getDefault()).format(Date(lastConnectedAtMs))
    else -> "Not connected yet"
}
