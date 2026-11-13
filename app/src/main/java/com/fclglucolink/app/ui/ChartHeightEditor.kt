package com.fclglucolink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

const val CHART_HEIGHT_DEFAULT_DP = 220
const val CHART_HEIGHT_MIN_DP = 150
const val CHART_HEIGHT_MAX_DP = 450
private const val CHART_HEIGHT_STEP_DP = 10

/** Potloodje bij een grafiek; opent een schuif om de hoogte in te stellen. */
@Composable
fun ChartHeightEditButton(currentDp: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(32.dp).clickable { open = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = tr("Chart height", "Grafiekhoogte"),
            modifier = Modifier.size(16.dp)
        )
    }
    if (open) {
        var value by remember { mutableStateOf(currentDp.coerceIn(CHART_HEIGHT_MIN_DP, CHART_HEIGHT_MAX_DP)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("Chart height", "Grafiekhoogte")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("$value dp", style = MaterialTheme.typography.headlineMedium)
                    Slider(
                        value = value.toFloat(),
                        onValueChange = {
                            value = ((it / CHART_HEIGHT_STEP_DP).roundToInt() * CHART_HEIGHT_STEP_DP)
                                .coerceIn(CHART_HEIGHT_MIN_DP, CHART_HEIGHT_MAX_DP)
                        },
                        valueRange = CHART_HEIGHT_MIN_DP.toFloat()..CHART_HEIGHT_MAX_DP.toFloat()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { value = CHART_HEIGHT_DEFAULT_DP }, modifier = Modifier.weight(1f)) {
                            Text(tr("Default", "Standaard"), maxLines = 1)
                        }
                        Button(onClick = { onChange(value); open = false }, modifier = Modifier.weight(1f)) {
                            Text(tr("Save", "Opslaan"), maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}
