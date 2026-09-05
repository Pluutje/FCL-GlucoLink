package com.fclglucolink.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fclglucolink.app.BuildConfig
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.update.UpdateChecker
import com.fclglucolink.app.update.UpdateInstaller
import com.fclglucolink.app.update.WhatsNewChecker
import kotlinx.coroutines.launch

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
 * 04/09/2026 (editor, RONDE 165, op verzoek: "Ik kreeg het verzoek om te
 * onderzoeken of er in de app ook een mogelijkheid is om een melding te
 * krijgen als er een update beschikbaar is [...] Wat dan wel handig is als
 * de google drive link toch al in de app bekend is dat hij dan zelf op
 * verzoek kan updaten.") — nieuwe update-sectie onderaan: toont het laatst
 * bekende resultaat van de periodieke achtergrondcheck (zie
 * BleConnectionService.kt's kdoc + AppSettings.kt's
 * availableUpdateVersionCode e.a.), plus een "Check now" (handmatig,
 * meteen) en, alleen als er ECHT een nieuwere versie bekend is, een
 * "Update now"-knop die de nieuwe APK downloadt en Android's eigen
 * installatiebevestiging opent — zie update/UpdateChecker.kt en
 * update/UpdateInstaller.kt's kdocs voor het volledige ontwerp
 * (bestandsnaam-gebaseerde detectie i.p.v. datum, nooit automatisch/stil).
 *
 * 05/09/2026 (editor, RONDE 170, op verzoek: "als er een update beschikbaar
 * is een 'whats new' knop [...] die zou dan per versie moeten tonen wat er
 * is veranderd [...] alles wat er is aangepast sinds de versie die
 * gebruikt is") — nieuwe "What's new"-knop, alleen zichtbaar naast "Update
 * now" (dus alleen als [updateAvailable]). Haalt bij het tikken
 * WhatsNewChecker.kt's per-versie changelogs op (gefilterd op
 * `BuildConfig.VERSION_CODE`, dus altijd t.o.v. de HUIDIG geïnstalleerde
 * versie, nooit een apart bijgehouden "laatst geziene versie") en toont ze
 * in een simpele, scrollbare AlertDialog — bewust geen apart navigatiescherm
 * (zie ManualScreen.kt's kdoc-stijl-argument bij Expert mode voor dezelfde
 * afweging: dit hoort bij een bestaand scherm, geen eigen route nodig).
 * "Update now" zelf is ONGEWIJZIGD: downloadt/installeert altijd de
 * nieuwste versie, ongeacht wat er in de "What's new"-lijst staat.
 *
 * @OptIn(ExperimentalMaterial3Api::class) — zie kdoc bij PairingScreen.kt,
 * puur vanwege TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()

    val availableVersionCode by settings.availableUpdateVersionCode.collectAsState(initial = 0)
    val availableFileId by settings.availableUpdateFileId.collectAsState(initial = "")
    val availableFileName by settings.availableUpdateFileName.collectAsState(initial = "")

    var isChecking by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // 05/09/2026 (editor, RONDE 170) — zie de kdoc bovenaan dit bestand.
    var showWhatsNew by remember { mutableStateOf(false) }
    var isLoadingWhatsNew by remember { mutableStateOf(false) }
    var whatsNewEntries by remember { mutableStateOf<List<WhatsNewChecker.Entry>>(emptyList()) }
    var whatsNewMessage by remember { mutableStateOf<String?>(null) }

    val updateAvailable = availableVersionCode > BuildConfig.VERSION_CODE && availableFileId.isNotBlank()

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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Updates", style = MaterialTheme.typography.titleMedium)

                    if (updateAvailable) {
                        Text(
                            "A newer version is available: $availableFileName",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "You have the latest version that could be found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    statusMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = !isChecking && !isInstalling,
                            onClick = {
                                isChecking = true
                                statusMessage = null
                                scope.launch {
                                    when (val result = UpdateChecker.checkForUpdate(context)) {
                                        is UpdateChecker.UpdateCheckResult.UpdateAvailable -> {
                                            settings.setAvailableUpdate(
                                                result.versionCode,
                                                result.fileId,
                                                result.fileName
                                            )
                                            statusMessage = "Update found: ${result.fileName}"
                                        }
                                        is UpdateChecker.UpdateCheckResult.UpToDate -> {
                                            settings.clearAvailableUpdate()
                                            statusMessage = "You're up to date."
                                        }
                                        is UpdateChecker.UpdateCheckResult.NotConfigured ->
                                            statusMessage = "Update check isn't set up yet."
                                        is UpdateChecker.UpdateCheckResult.Error ->
                                            statusMessage = "Couldn't check for updates: ${result.message}"
                                    }
                                    settings.setLastUpdateCheckAt(System.currentTimeMillis())
                                    isChecking = false
                                }
                            }
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            }
                            Text("Check now")
                        }

                        if (updateAvailable) {
                            // 05/09/2026 (editor, RONDE 170) — zie de kdoc
                            // bovenaan dit bestand: alleen zichtbaar naast
                            // "Update now" (dus alleen als updateAvailable),
                            // haalt de per-versie changelogs pas op bij het
                            // tikken zelf, niet al bij het openen van dit
                            // scherm (geen ongevraagd extra netwerkverkeer).
                            OutlinedButton(
                                enabled = !isChecking && !isInstalling && !isLoadingWhatsNew,
                                onClick = {
                                    isLoadingWhatsNew = true
                                    whatsNewMessage = null
                                    whatsNewEntries = emptyList()
                                    showWhatsNew = true
                                    scope.launch {
                                        when (val result = WhatsNewChecker.fetchSince(context, BuildConfig.VERSION_CODE)) {
                                            is WhatsNewChecker.WhatsNewResult.Success -> {
                                                whatsNewEntries = result.entries
                                                if (result.entries.isEmpty()) {
                                                    whatsNewMessage = "No changelog available for this update yet."
                                                }
                                            }
                                            is WhatsNewChecker.WhatsNewResult.NotConfigured ->
                                                whatsNewMessage = "Update check isn't set up yet."
                                            is WhatsNewChecker.WhatsNewResult.Error ->
                                                whatsNewMessage = "Couldn't load what's new: ${result.message}"
                                        }
                                        isLoadingWhatsNew = false
                                    }
                                }
                            ) {
                                if (isLoadingWhatsNew) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                }
                                Text("What's new")
                            }
                        }

                        if (updateAvailable) {
                            Button(
                                enabled = !isChecking && !isInstalling,
                                onClick = {
                                    isInstalling = true
                                    statusMessage = null
                                    scope.launch {
                                        when (val result = UpdateInstaller.downloadAndLaunchInstall(context, availableFileId)) {
                                            is UpdateInstaller.InstallLaunchResult.Launched ->
                                                statusMessage = null
                                            is UpdateInstaller.InstallLaunchResult.Failed ->
                                                statusMessage = "Couldn't install the update: ${result.message}"
                                            is UpdateInstaller.InstallLaunchResult.NeedsInstallPermission -> {
                                                statusMessage = "Allow \"Install unknown apps\" for FCLGlucoLink, then try again."
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                        data = Uri.parse("package:${context.packageName}")
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            }
                                        }
                                        isInstalling = false
                                    }
                                }
                            ) {
                                if (isInstalling) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                }
                                Text("Update now")
                            }
                        }
                    }
                }
            }
        }
    }

    // 05/09/2026 (editor, RONDE 170) — zie de kdoc bovenaan dit bestand:
    // een simpele, scrollbare AlertDialog i.p.v. een apart navigatiescherm.
    // heightIn(max=...) voorkomt dat een lange, meerdere-versies-lijst de
    // dialog buiten het scherm laat groeien op een klein toestel.
    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            confirmButton = {
                TextButton(onClick = { showWhatsNew = false }) { Text("Close") }
            },
            title = { Text("What's new") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isLoadingWhatsNew) {
                        CircularProgressIndicator()
                    }
                    whatsNewMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                    whatsNewEntries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Version ${entry.versionCode}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        )
    }
}
