package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.BuildConfig

/**
 * 31/07/2026 (editor, na feedback over de menu-indeling) — kort infoscherm:
 * wat de app doet, het versienummer, en dank aan Juggluco. De xDrip-
 * broadcast in broadcast/XDripBroadcaster.kt is een Kotlin-port van
 * Juggluco's SendLikexDrip.java (zie de kdoc daar), en de nog te bouwen
 * CareSens Air-koppeling hergebruikt Juggluco's beproefde native
 * kalibratiemodule (zie README.md en sensor/caresensair/
 * CareSensAirDriver.kt) — die twee stukken hergebruik zijn de reden voor
 * deze credit.
 *
 * BuildConfig.VERSION_NAME vereist `buildFeatures { buildConfig = true }`
 * in app/build.gradle.kts (sinds AGP 8 niet meer automatisch aan) — zie
 * daar.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("FCLGlucoLink", style = MaterialTheme.typography.titleLarge)
            Text(
                "A small, standalone app that bridges a CGM sensor to AAPS via " +
                    "the xDrip broadcast intent — no dosing logic, no AAPS-plugin " +
                    "integration, just the sensor connection.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "The xDrip-broadcast implementation is a Kotlin port of " +
                    "Juggluco's SendLikexDrip.java, and the CareSens Air sensor " +
                    "support builds on Juggluco's native calibration code — " +
                    "thanks to the Juggluco project for that groundwork.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
