package com.fclglucolink.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.alarm.AlarmAlertMode
import com.fclglucolink.app.alarm.AlarmCategory
import com.fclglucolink.app.alarm.AlarmEscalation
import com.fclglucolink.app.alarm.AlarmType
import com.fclglucolink.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FCLGlucoLink — alarminstellingen (RONDE 106, Fase 2 stap 1)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 106, op verzoek: "1 overal knop om in 1 keer
 * alle alarmen aan/uit te zetten [...] indien die is ingeschakeld dat dan
 * de afzonderlijke alarmen kunnen worden ingesteld maar ook ieder
 * afzonderlijk aan en uit kunnen waarbij de laatst ingestelde waarde wel
 * persistent over een restart dan wel app update blijven") — precies dat
 * model: [masterEnabled] bovenaan (de "overal knop"), daaronder een kaart
 * per alarmtype (zie alarm/AlarmType.kt) met een eigen aan/uit-schakelaar
 * plus, als die aan staat, de detailinstellingen (drempel/voorlooptijd/
 * geluid/trilling). ELKE schakelaar/instelling hieronder is gewoon een
 * AppSettings-DataStore-veld (zie AppSettings.kt's "Alarmen"-sectie) — dus
 * automatisch persistent over herstarts/updates, geen aparte opslaglogica
 * hier nodig.
 *
 * UI-gate: zolang [masterEnabled] uit staat, zijn alle per-type
 * schakelaars/instellingen hieronder zichtbaar maar NIET aanraakbaar
 * (`enabled = false` op elke Switch/IconButton/SegmentedButton/TextButton)
 * — precies het gevraagde "indien die is ingeschakeld dat dan de
 * afzonderlijke alarmen kunnen worden ingesteld". De onderliggende waarden
 * blijven gewoon staan (dus zichtbaar, alleen grijs) zodat de gebruiker in
 * één oogopslag ziet wat er geconfigureerd staat, ook met de
 * hoofdschakelaar uit.
 *
 * 13/08/2026 (editor, RONDE 106b, op verzoek: "ik wil echter per
 * alarmsoort een eigen geluid kunnen kiezen uit de geluiden op de
 * telefoon (zoals je ook een ringtone voor de telefoon kunt kiezen) dan
 * moet er per alarm gekozen kunnen worden of het alarm direct klinkt of
 * dat het langzaam opbouwt [...] de predict low en predictive high
 * moeten echter wel afzonderlijk ingesteld kunnen worden") — twee
 * wijzigingen t.o.v. RONDE 106: (1) het toenmalige, ene "Predictive"-type is
 * gesplitst in [AlarmType.PREDICTIVE_LOW]/[AlarmType.PREDICTIVE_HIGH], elk met een
 * eigen kaart/instellingen, exact zoals de andere vijf types; (2) het oude
 * "Urgent"/"Gentle"-geluidsprofiel is vervangen door [SoundPickerRow]
 * (Android's eigen ringtone-kiezer, RingtoneManager.ACTION_RINGTONE_PICKER
 * — hetzelfde systeemscherm als bij het kiezen van een beltoon) plus een
 * losse "When triggered"-keuze ([AlarmEscalation]: direct op volle sterkte,
 * of langzaam opbouwend) — de twee zijn nu onafhankelijk instelbaar per
 * type, in plaats van vast aan elkaar gekoppeld via één profiel.
 *
 * SCOPE: dit scherm bouwt/toont alleen de INSTELLINGEN. Er is bewust geen
 * enkele koppeling naar een achtergrond-alarm-motor, geluid-afspelen, of
 * een volledig-scherm-alarmweergave — die volgen in een latere ronde, zie
 * de toelichting onderaan dit scherm (en README's Ronde 106/106b-secties).
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val masterEnabled by settings.alarmsMasterEnabled.collectAsState(initial = false)
    val displayUnit by settings.displayUnit.collectAsState(initial = GlucoseUnit.MMOL)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alarms") },
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
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable alarms", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Master switch for every alarm below. Turning this off " +
                                "silences everything at once without losing any of " +
                                "your individual settings — turn it back on and " +
                                "they're exactly as you left them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = { enabled -> scope.launch { settings.setAlarmsMasterEnabled(enabled) } }
                    )
                }
            }

            Text("Alarm types", style = MaterialTheme.typography.titleMedium)

            AlarmType.entries.forEach { type ->
                AlarmTypeCard(
                    type = type,
                    settings = settings,
                    masterEnabled = masterEnabled,
                    unit = displayUnit,
                    scope = scope
                )
            }

            Text(
                "This screen configures thresholds, sounds, and vibration only. " +
                    "The actual alarm engine — background monitoring, playing a " +
                    "sound, and the stop/snooze screen — isn't wired up yet in " +
                    "this build; that's the next step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun AlarmTypeCard(
    type: AlarmType,
    settings: AppSettings,
    masterEnabled: Boolean,
    unit: GlucoseUnit,
    scope: CoroutineScope
) {
    val enabled by settings.alarmEnabled(type).collectAsState(initial = false)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(type.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Switch(
                    checked = enabled,
                    enabled = masterEnabled,
                    onCheckedChange = { value -> scope.launch { settings.setAlarmEnabled(type, value) } }
                )
            }

            if (enabled) {
                AlarmTypeDetailSettings(type = type, settings = settings, interactive = masterEnabled, unit = unit, scope = scope)
            }
        }
    }
}

// 13/08/2026 (editor, live-melding — "This material API is experimental"
// op de SingleChoiceSegmentedButtonRow/SegmentedButton hieronder) — de
// @OptIn op AlarmSettingsScreen() hierboven dekt alleen DIE functie's eigen
// body; deze private helper is een aparte functie en gebruikt zelf ook
// experimentele Material3-API's (het geluidsprofiel-kiezertje), dus heeft
// zijn eigen @OptIn nodig. Zelfde niet-schadelijke, stabiele-in-de-praktijk
// opt-in als overal elders in dit project (zie kdoc bij PairingScreen.kt) —
// CalibrationScreen.kt/SettingsScreen.kt ontliepen dit toevallig omdat hun
// SegmentedButton-gebruik daar rechtstreeks in de al-geannoteerde
// top-level Composable staat, niet in een eigen private sub-functie.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTypeDetailSettings(
    type: AlarmType,
    settings: AppSettings,
    interactive: Boolean,
    unit: GlucoseUnit,
    scope: CoroutineScope
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (type.category) {
            AlarmCategory.THRESHOLD_LOW, AlarmCategory.THRESHOLD_HIGH -> {
                val thresholdMgdl by settings.alarmThresholdMgdl(type).collectAsState(initial = type.defaultThresholdMgdl ?: 0.0)
                ThresholdStepper(
                    label = "Threshold",
                    valueMgdl = thresholdMgdl,
                    unit = unit,
                    interactive = interactive,
                    onChange = { newMgdl -> scope.launch { settings.setAlarmThresholdMgdl(type, newMgdl) } }
                )
            }
            // 13/08/2026 (editor, RONDE 108, op verzoek: "Kun je de
            // predictive alarms nog zo zetten dat daar een Bg waarde wordt
            // ingevoerd ipv de koppeling aan low en high dat geeft meer
            // vrijheid") — nu ZOWEL een eigen streefwaarde (net als de
            // drempel-alarmen hierboven) ALS de voorlooptijd, i.p.v. alleen
            // de voorlooptijd met een impliciete koppeling aan Low/High.
            AlarmCategory.PREDICTIVE_LOW, AlarmCategory.PREDICTIVE_HIGH -> {
                val thresholdMgdl by settings.alarmThresholdMgdl(type).collectAsState(initial = type.defaultThresholdMgdl ?: 0.0)
                ThresholdStepper(
                    label = "Target",
                    valueMgdl = thresholdMgdl,
                    unit = unit,
                    interactive = interactive,
                    onChange = { newMgdl -> scope.launch { settings.setAlarmThresholdMgdl(type, newMgdl) } }
                )
                val leadTimeMinutes by settings.alarmLeadTimeMinutes(type).collectAsState(initial = type.defaultLeadTimeMinutes ?: 15)
                MinutesStepper(
                    label = "Warn this many minutes ahead",
                    valueMinutes = leadTimeMinutes,
                    interactive = interactive,
                    step = 5,
                    minValue = 5,
                    onChange = { newMinutes -> scope.launch { settings.setAlarmLeadTimeMinutes(type, newMinutes) } }
                )
            }
            AlarmCategory.STALE_DATA -> {
                val staleMinutes by settings.alarmStaleMinutes(type).collectAsState(initial = type.defaultStaleMinutes ?: 20)
                MinutesStepper(
                    label = "Alert after no reading for",
                    valueMinutes = staleMinutes,
                    interactive = interactive,
                    step = 5,
                    minValue = 5,
                    onChange = { newMinutes -> scope.launch { settings.setAlarmStaleMinutes(type, newMinutes) } }
                )
            }
        }

        val soundUri by settings.alarmSoundUri(type).collectAsState(initial = null)
        SoundPickerRow(
            soundUri = soundUri,
            interactive = interactive,
            onSoundChosen = { newUri -> scope.launch { settings.setAlarmSoundUri(type, newUri) } }
        )

        val escalation by settings.alarmEscalation(type).collectAsState(initial = type.defaultEscalation)
        Text("When triggered", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AlarmEscalation.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = escalation == option,
                    enabled = interactive,
                    onClick = { scope.launch { settings.setAlarmEscalation(type, option) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AlarmEscalation.entries.size)
                ) {
                    Text(if (option == AlarmEscalation.IMMEDIATE) "Immediately" else "Gradual")
                }
            }
        }

        // 13/08/2026 (editor, RONDE 107b, op verzoek: "ik wil per alarm
        // kunnen kiezen tussen alarm of vibrate of both [...] de vibrator
        // knop die nu overal onderaan staat vervangen door alarm - vibrate
        // - both knop") — vervangt de vorige losse "Vibration"-schakelaar.
        val alertMode by settings.alarmAlertMode(type).collectAsState(initial = AlarmAlertMode.BOTH)
        Text("Alert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AlarmAlertMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = alertMode == option,
                    enabled = interactive,
                    onClick = { scope.launch { settings.setAlarmAlertMode(type, option) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AlarmAlertMode.entries.size)
                ) {
                    Text(option.displayName)
                }
            }
        }
    }
}

/**
 * 13/08/2026 (editor, RONDE 106b, op verzoek: "ik wil echter per
 * alarmsoort een eigen geluid kunnen kiezen uit de geluiden op de
 * telefoon (zoals je ook een ringtone voor de telefoon kunt kiezen)") —
 * Android's EIGEN ringtone-kiezer (RingtoneManager.ACTION_RINGTONE_PICKER
 * — hetzelfde systeemscherm als bij het kiezen van een beltoon/
 * meldingsgeluid), type TYPE_ALARM (logisch alvast te kiezen, ook al
 * speelt dit geluid pas in een latere ronde daadwerkelijk af via
 * STREAM_ALARM — zie het eerder afgestemde ontwerp). [soundUri] is `null`
 * zolang de gebruiker nog geen keuze gemaakt heeft; de kiezer toont dan
 * het systeem-standaardalarmgeluid als voorgeselecteerd
 * (EXTRA_RINGTONE_EXISTING_URI), en de getoonde titel hieronder valt
 * terug op datzelfde standaardgeluid via RingtoneManager.getRingtone().
 * Geen extra permissie nodig — de kiezer is een systeem-Activity die
 * Android zelf beheert, precies zoals bij een gewone beltoonkeuze.
 */
@Composable
private fun SoundPickerRow(
    soundUri: String?,
    interactive: Boolean,
    onSoundChosen: (String?) -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onSoundChosen(result.data?.getRingtoneUriCompat()?.toString())
        }
    }
    val soundTitle = remember(soundUri) {
        val uri = soundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: "Default"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Sound", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(soundTitle, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            enabled = interactive,
            onClick = {
                val existingUri = soundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
                }
                launcher.launch(intent)
            }
        ) {
            Text("Choose")
        }
    }
}

/** `Intent.getParcelableExtra(String)` (enkel argument) is deprecated sinds
 *  API 33 (Tiramisu) ten gunste van de type-veilige tweeargumentsvariant —
 *  dit dekt beide paden zonder een deprecation-warning op minSdk 26. */
@Suppress("DEPRECATION")
private fun Intent.getRingtoneUriCompat(): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

/** Zelfde +/- stapgrootte-afweging als AddCalibrationDialog's adjust()
 *  (zie CalibrationScreen.kt): 2 mg/dL resp. 0,1 mmol/L omgerekend naar het
 *  mg/dL-equivalent — ondergrens 40 mg/dL (onder de laagste zinvolle
 *  alarmdrempel), bovengrens 400 mg/dL. */
@Composable
private fun ThresholdStepper(
    label: String,
    valueMgdl: Double,
    unit: GlucoseUnit,
    interactive: Boolean,
    onChange: (Double) -> Unit
) {
    val stepMgdl = if (unit == GlucoseUnit.MGDL) 2.0 else 0.1.mmolToMgdl()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = interactive,
                onClick = { onChange((valueMgdl - stepMgdl).coerceIn(40.0, 400.0)) }
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(valueMgdl.formatForDisplayWithUnit(unit), style = MaterialTheme.typography.bodyMedium)
            IconButton(
                enabled = interactive,
                onClick = { onChange((valueMgdl + stepMgdl).coerceIn(40.0, 400.0)) }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
private fun MinutesStepper(
    label: String,
    valueMinutes: Int,
    interactive: Boolean,
    step: Int,
    minValue: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = interactive,
                onClick = { onChange((valueMinutes - step).coerceAtLeast(minValue)) }
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text("$valueMinutes min", style = MaterialTheme.typography.bodyMedium)
            IconButton(
                enabled = interactive,
                onClick = { onChange(valueMinutes + step) }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}
