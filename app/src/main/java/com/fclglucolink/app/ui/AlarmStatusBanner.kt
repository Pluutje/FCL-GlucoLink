package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.alarm.AlarmType
import com.fclglucolink.app.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val PAUSE_STEP_MIN = 15
private const val PAUSE_MAX_MIN = 240

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h u"
        else -> "$h u $m min"
    }
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * Statusregels bovenaan het hoofdscherm: alarmpauze (met knop "Opheffen") en per
 * alarm dat gesluimerd of gestopt is hoe lang dat nog loopt. Toont niets als er
 * niets gedempt is.
 */
@Composable
fun AlarmStatusBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    val pausedUntil by settings.alarmsPausedUntilMs.collectAsState(initial = null)
    val lines = mutableListOf<Pair<String, AlarmType>>()
    AlarmType.entries.forEach { type ->
        val muted by settings.alarmMutedUntilMs(type).collectAsState(initial = null)
        val snooze by settings.alarmSnoozeActive(type).collectAsState(initial = false)
        val stopped by settings.alarmStoppedEpisode(type).collectAsState(initial = false)
        val remainingMin = muted?.let { (((it - now) + 59_999L) / 60_000L).toInt() } ?: 0
        when {
            snooze && remainingMin > 0 ->
                lines += Pair(tr("${type.displayName} snoozed — ${formatDuration(remainingMin)} left",
                    "${type.displayName} gesluimerd — nog ${formatDuration(remainingMin)}"), type)
            stopped ->
                lines += Pair(tr("${type.displayName} stopped until recovered",
                    "${type.displayName} gestopt tot herstel"), type)
            remainingMin > 0 ->
                lines += Pair(tr("${type.displayName} stopped — may return in ${formatDuration(remainingMin)}",
                    "${type.displayName} gestopt — kan over ${formatDuration(remainingMin)} terugkomen"), type)
        }
    }
    val pauseRemaining = pausedUntil?.takeIf { it > now }
    if (pauseRemaining == null && lines.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pauseRemaining != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pauseRemaining != null) {
                val min = (((pauseRemaining - now) + 59_999L) / 60_000L).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("Alarms paused — ${formatDuration(min)} left (until ${clock(pauseRemaining)})",
                            "Alarmen gepauzeerd — nog ${formatDuration(min)} (tot ${clock(pauseRemaining)})"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { scope.launch { settings.setAlarmsPausedUntilMs(null) } }) {
                        Text(tr("End", "Opheffen"))
                    }
                }
            }
            lines.forEach { (text, type) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            settings.clearAlarmMutedUntilMs(type)
                            settings.setAlarmSnoozeActive(type, false)
                            settings.setAlarmStoppedEpisode(type, false)
                        }
                    }) { Text(tr("End", "Opheffen")) }
                }
            }
        }
    }
}

/** Schuif (15 min tot 4 u) om alle alarmen te pauzeren. */
@Composable
fun AlarmPauseDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var minutes by remember { mutableStateOf(60) }
    LaunchedEffect(Unit) {
        minutes = settings.getAlarmPauseLastMinutesOnce().coerceIn(PAUSE_STEP_MIN, PAUSE_MAX_MIN)
    }
    val until = System.currentTimeMillis() + minutes * 60_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pause all alarms", "Alle alarmen pauzeren")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(formatDuration(minutes), style = MaterialTheme.typography.headlineMedium)
                Text(tr("until ${clock(until)}", "tot ${clock(until)}"), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = {
                        minutes = ((it / PAUSE_STEP_MIN).roundToInt() * PAUSE_STEP_MIN).coerceIn(PAUSE_STEP_MIN, PAUSE_MAX_MIN)
                    },
                    valueRange = PAUSE_STEP_MIN.toFloat()..PAUSE_MAX_MIN.toFloat(),
                    steps = PAUSE_MAX_MIN / PAUSE_STEP_MIN - 2
                )
                Text(
                    tr("No alarm will sound. Only Urgent Low vibrates briefly (4 pulses, no sound), repeating every 5 minutes while it lasts.",
                        "Geen enkel alarm klinkt. Alleen Urgent Low trilt kort (4 pulsen, geen geluid), elke 5 minuten zolang het duurt."),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(tr("Cancel", "Annuleren"), maxLines = 1)
                    }
                    Button(onClick = { onConfirm(minutes); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text(tr("Pause", "Pauzeren"), maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
