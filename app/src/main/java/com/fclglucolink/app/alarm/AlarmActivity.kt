package com.fclglucolink.app.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.fclglucolink.app.ui.GlucoseUnit
import com.fclglucolink.app.ui.formatForDisplayWithUnit
import com.fclglucolink.app.ui.theme.FCLGlucoLinkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FCLGlucoLink — het volledige-scherm-alarmscherm (RONDE 107)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 107, op verzoek: "de alarmen moeten gestopt en
 * gesnoozed kunnen worden") — geopend via AlarmController.kt's full-screen-
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpWakeScreenFlags()
        alarmType = parseAlarmType(intent)

        setContent {
            FCLGlucoLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmType = parseAlarmType(intent)
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
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        keyguardManager?.requestDismissKeyguard(this, null)
    }

    private fun parseAlarmType(intent: Intent?): AlarmType? {
        val raw = intent?.getStringExtra(EXTRA_ALARM_TYPE) ?: return null
        return runCatching { AlarmType.valueOf(raw) }.getOrNull()
    }

    companion object {
        const val EXTRA_ALARM_TYPE = "alarm_type"
    }
}

@Composable
private fun AlarmContent(type: AlarmType, onStop: () -> Unit, onSnooze: (Int) -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val readingStore = remember { GlucoseReadingStore(context) }
    var latestReading by remember { mutableStateOf<GlucoseReading?>(null) }
    var displayUnit by remember { mutableStateOf(GlucoseUnit.MMOL) }

    LaunchedEffect(type) {
        displayUnit = settings.getDisplayUnitOnce()
        val slot = settings.getAapsActiveSlotOnce()
        val sensorType = slot?.let { settings.getSelectedSensorOnce(it) }
        latestReading = readingStore.latestReading(sensorType = sensorType).first()
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
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        Spacer(Modifier.height(24.dp))
        Text("Snooze", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60).forEach { minutes ->
                OutlinedButton(onClick = { onSnooze(minutes) }, modifier = Modifier.weight(1f)) {
                    Text("$minutes min")
                }
            }
        }
    }
}
