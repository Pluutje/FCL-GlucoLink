package com.fclglucolink.app.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.ui.AppLanguage
import com.fclglucolink.app.ui.GlucoseUnit
import com.fclglucolink.app.ui.LocalAppLanguage
import com.fclglucolink.app.ui.formatForDisplayWithUnit
import com.fclglucolink.app.ui.theme.FCLGlucoLinkTheme
import com.fclglucolink.app.ui.tr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * ============================================================================
 * FCLGlucoLink — het volledige-scherm-alarmscherm (RONDE 107)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107) — laat alarmen stoppen en snoozen; geopend
 * via AlarmController.kt's full-screen-
 * notificatie (`setFullScreenIntent`), niet als gewone in-app-navigatie —
 * een EIGEN Activity (niet een scherm binnen FclGlucoLinkNavHost) juist
 * omdat dit ook moet kunnen verschijnen als de app niet op de voorgrond
 * staat of het toestel vergrendeld is (zie setUpWakeScreenFlags()).
 *
 * Toont: het alarmtype + omschrijving, de laatste bekende BG-waarde (in de
 * gekozen weergave-eenheid, zie displayUnit), een "Stop"-knop (vast, per-
 * categorie afkoelmoment — zie AlarmController.stop()'s kdoc) en drie
 * "Snooze"-knoppen (15/30/60 minuten, door de gebruiker zelf te kiezen).
 * `launchMode="singleTop"` in het manifest + [onNewIntent] hieronder: als
 * er ondertussen een ANDER alarmtype afgaat terwijl dit scherm al open
 * staat, wordt dezelfde Activity-instantie hergebruikt met de nieuwe
 * gegevens, i.p.v. dat er een stapel losse alarmschermen ontstaat.
 */
class AlarmActivity : ComponentActivity() {

    private var alarmType by mutableStateOf<AlarmType?>(null)
    private var openSnoozePicker by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpWakeScreenFlags()
        alarmType = parseAlarmType(intent)
        openSnoozePicker = intent?.getBooleanExtra(EXTRA_OPEN_SNOOZE, false) == true

        setContent {
            // 01/10/2026 (editor, RONDE 196, op verzoek — een alarm moet
            // echt geaccordeerd of gesnoozed worden, niet per ongeluk weg te
            // krijgen) — [BackHandler] hieronder consumeert de terug-knop/
            // -gebaar zonder iets te doen: de enige manier om dit scherm te
            // sluiten is via onStop/onSnooze. `AlarmActivity` is bewust een
            // EIGEN `ComponentActivity` (zie klasse-kdoc), dus zit NIET
            // binnen `FclGlucoLinkNavHost()`'s `CompositionLocalProvider`
            // voor [LocalAppLanguage] — zonder de eigen provider hieronder
            // zou [tr] hier altijd stilzwijgend op Engels terugvallen,
            // ongeacht de door de gebruiker gekozen taal.
            var language by remember { mutableStateOf(AppLanguage.ENGLISH) }
            LaunchedEffect(Unit) {
                language = AppSettings(this@AlarmActivity).getAppLanguageOnce()
            }
            CompositionLocalProvider(LocalAppLanguage provides language) {
                FCLGlucoLinkTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        BackHandler(enabled = true) { /* bewust genegeerd, zie kdoc hierboven */ }
                        val type = alarmType
                        if (type == null) {
                            // Geen (geldig) alarmtype meegegeven — kan in de
                            // praktijk niet gebeuren via de normale
                            // AlarmController-route, maar defensief: gewoon
                            // meteen sluiten i.p.v. een leeg scherm tonen.
                            LaunchedEffect(Unit) { finish() }
                        } else {
                            AlarmContent(
                                type = type,
                                openSnoozeInitially = openSnoozePicker,
                                onStop = {
                                    lifecycleScope.launch {
                                        AlarmController.stop(this@AlarmActivity, type)
                                        finish()
                                    }
                                },
                                onSnooze = { minutes ->
                                    lifecycleScope.launch {
                                        AlarmController.snooze(this@AlarmActivity, type, minutes)
                                        finish()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmType = parseAlarmType(intent)
        openSnoozePicker = intent.getBooleanExtra(EXTRA_OPEN_SNOOZE, false)
    }

    /** Zie klasse-kdoc. `setShowWhenLocked`/`setTurnScreenOn` (API 27+) zijn
     *  de moderne vervangers van de gelijknamige WindowManager-flags — deze
     *  app's minSdk is 26, dus de oude flags blijven als fallback voor
     *  precies die ene API-versie. */
    private fun setUpWakeScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // RONDE 229 — GEEN requestDismissKeyguard(): dat dwong een ontgrendeling af (PIN-scherm, geen
        // vingerafdruk). Het alarmscherm staat al boven het vergrendelscherm; Stop/Snooze werken zo.
    }

    private fun parseAlarmType(intent: Intent?): AlarmType? {
        val raw = intent?.getStringExtra(EXTRA_ALARM_TYPE) ?: return null
        return runCatching { AlarmType.valueOf(raw) }.getOrNull()
    }

    companion object {
        const val EXTRA_ALARM_TYPE = "alarm_type"
        const val EXTRA_OPEN_SNOOZE = "open_snooze"
    }
}

@Composable
private fun AlarmContent(type: AlarmType, openSnoozeInitially: Boolean, onStop: () -> Unit, onSnooze: (Int) -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val readingStore = remember { GlucoseReadingStore(context) }
    var latestReading by remember { mutableStateOf<GlucoseReading?>(null) }
    var displayUnit by remember { mutableStateOf(GlucoseUnit.MMOL) }
    var showSnoozeDialog by remember(openSnoozeInitially) { mutableStateOf(openSnoozeInitially) }
    var snoozeMinutes by remember { mutableStateOf(15) }

    LaunchedEffect(type) {
        displayUnit = settings.getDisplayUnitOnce()
        snoozeMinutes = settings.getAlarmLastSnoozeMinutesOnce().coerceIn(15, AlarmController.maxSnoozeMinutes(type))
        val slot = settings.getAapsActiveSlotOnce()
        // 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — was
        // `sensorType = sensorType` (sensorType hier alleen nog gebruikt
        // geweest als tussenwaarde voor die aanroep, verder ongebruikt): zie
        // GlucoseReadingStore.kt's kdoc bij latestReading() voor de
        // volledige analyse. `slot = slot` behoudt hetzelfde `null`-gedrag
        // (geen actieve AAPS-slot bekend -> ongefilterde/gecombineerde
        // laatste meting, zelfde fallback als voorheen).
        latestReading = readingStore.latestReading(slot = slot).first()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            type.displayName,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(type.description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val reading = latestReading
        if (reading != null) {
            Text(reading.glucoseMgdl.formatForDisplayWithUnit(displayUnit), style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(40.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(tr("Stop", "Stoppen")) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showSnoozeDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Snooze…", "Sluimeren…"))
        }
        if (showSnoozeDialog) {
            SnoozeDialogHost(
                type = type,
                minutes = snoozeMinutes,
                onMinutesChange = { snoozeMinutes = it },
                onDismiss = { showSnoozeDialog = false },
                onConfirm = { showSnoozeDialog = false; onSnooze(snoozeMinutes) }
            )
        }
    }
}

/** Schuif met stappen van 15 min; de bevestigknop toont de gekozen tijd. */
@Composable
private fun SnoozeDialogHost(
    type: AlarmType,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val step = AlarmController.SNOOZE_STEP_MINUTES
    val max = AlarmController.maxSnoozeMinutes(type)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Snooze for how long?", "Hoe lang sluimeren?")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("$minutes min", style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { onMinutesChange(((it / step).roundToInt() * step).coerceIn(step, max)) },
                    valueRange = step.toFloat()..max.toFloat(),
                    steps = ((max - step) / step - 1).coerceAtLeast(0)
                )
                if (type == AlarmType.URGENT_LOW) {
                    Text(
                        tr("Urgent Low can be snoozed for 30 minutes at most.", "Urgent Low kan maximaal 30 minuten gesluimerd worden."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(tr("Cancel", "Annuleren"), maxLines = 1)
                    }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                        Text(tr("Snooze", "Sluimeren"), maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
