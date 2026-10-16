package com.fclglucolink.app.sensor.simulator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 30/07/2026 (editor) — losgetrokken uit ui/SimulatorSetupScreen.kt: deze
 * lees-logica is nodig op TWEE plekken. Eerst alleen in de setup-UI (bestand
 * kiezen, voorbeeld tonen, afspelen starten). Nu ook in
 * sensor/ble/BleConnectionService.kt, om een eerder actieve "externe lijst"-
 * afspeelmodus te kunnen HERSTARTEN na een service-herstart (zie kdoc daar en
 * bij AppSettings.readActiveSimulatorMode) — zonder dat de UI open hoeft te
 * staan. Bewust een los top-level bestand i.p.v. in SimulatorSetupScreen.kt
 * laten staan: dat bestand hoort bij de UI-laag, dit stukje niet.
 */

/** Leest "één BG-waarde (mmol/L) per regel" — negeert lege regels en regels
 *  die niet als getal parsen (bv. een headerregel). */
fun readMmolValuesFromUri(context: Context, uri: Uri): List<Double> {
    val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
    return input.bufferedReader().useLines { lines ->
        lines.mapNotNull { line -> line.trim().replace(',', '.').toDoubleOrNull() }.toList()
    }
}

/** Leesbare bestandsnaam voor een content-URI, voor UI-weergave. */
fun queryUriDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
