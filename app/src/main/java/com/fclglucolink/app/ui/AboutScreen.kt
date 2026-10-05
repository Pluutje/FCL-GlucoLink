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
import com.fclglucolink.app.update.TrustedBuild
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
 * 04/09/2026 (editor, RONDE 165, op verzoek om een melding bij een
 * beschikbare update, met automatisch updaten via de al bekende Google
 * Drive-link) — nieuwe update-sectie onderaan: toont het laatst
 * bekende resultaat van de periodieke achtergrondcheck (zie
 * BleConnectionService.kt's kdoc + AppSettings.kt's
 * availableUpdateVersionCode e.a.), plus een "Check now" (handmatig,
 * meteen) en, alleen als er ECHT een nieuwere versie bekend is, een
 * "Update now"-knop die de nieuwe APK downloadt en Android's eigen
 * installatiebevestiging opent — zie update/UpdateChecker.kt en
 * update/UpdateInstaller.kt's kdocs voor het volledige ontwerp
 * (bestandsnaam-gebaseerde detectie i.p.v. datum, nooit automatisch/stil).
 *
 * 05/09/2026 (editor, RONDE 170, op verzoek om bij een beschikbare update
 * ook een "What's new"-knop te tonen die per versie laat zien wat er is
 * aangepast sinds de geïnstalleerde versie) — nieuwe "What's new"-knop,
 * alleen zichtbaar naast "Update now" (dus alleen als [updateAvailable]).
 * Haalt bij het tikken
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

    // 26/09/2026 (editor, RONDE 191, vertaling van dit scherm) — zelfde
    // trNow-patroon als SettingsScreen.kt's SendLogFilesDialog en
    // CalibrationScreen.kt's CalibrationScreen(): de status-/whatsnew-
    // meldingen hieronder worden gezet vanuit `scope.launch { }`, geen
    // @Composable-context, dus `tr()` zelf is daar niet aanroepbaar. Deze
    // lokale `trNow()` sluit de taal af op het moment van aanroep.
    val language = LocalAppLanguage.current
    fun trNow(en: String, nl: String): String = if (language == AppLanguage.DUTCH) nl else en

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
                title = { Text(tr("About", "Over")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Terug"))
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
                tr(
                    "A small, standalone app that bridges a CGM sensor to AAPS via " +
                        "the xDrip broadcast intent — no dosing logic, no AAPS-plugin " +
                        "integration, just the sensor connection.",
                    "Een kleine, zelfstandige app die een CGM-sensor koppelt aan " +
                        "AAPS via de xDrip-broadcast — geen doseerlogica, geen " +
                        "AAPS-plugin-integratie, alleen de sensorverbinding."
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                tr("Version", "Versie") + " ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium
            )
            // 02/10/2026 (editor, RONDE 205, op verzoek) — zie
            // build.gradle.kts' kdoc bij BUILD_TIME: versionName wordt
            // bewust alleen op expliciet verzoek gebumpt, dus tijdens een
            // reeks snelle testrondes kan dezelfde versietekst dagenlang
            // ongewijzigd blijven staan terwijl de onderliggende code wél
            // verandert. BUILD_TIME is automatisch gevuld op het moment
            // van bouwen (nooit handmatig bij te werken, dus nooit
            // "vergeten") en geeft zo altijd een uniek, controleerbaar
            // moment — kleiner/secundair getoond, de versieregel blijft
            // het primaire label.
            Text(
                tr("Built", "Gebouwd op") + " ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                tr(
                    "The xDrip-broadcast implementation is a Kotlin port of " +
                        "Juggluco's SendLikexDrip.java, and the CareSens Air sensor " +
                        "support builds on Juggluco's native calibration code — " +
                        "thanks to the Juggluco project for that groundwork.",
                    "De xDrip-broadcast-implementatie is een Kotlin-port van " +
                        "Juggluco's SendLikexDrip.java, en de CareSens Air-" +
                        "sensorondersteuning bouwt voort op Juggluco's native " +
                        "kalibratiecode — dank aan het Juggluco-project voor dat " +
                        "voorwerk."
                ),
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
                    Text(tr("Updates", "Updates"), style = MaterialTheme.typography.titleMedium)

                    if (updateAvailable) {
                        Text(
                            tr(
                                "A newer version is available: $availableFileName",
                                "Er is een nieuwere versie beschikbaar: $availableFileName"
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            tr(
                                "You have the latest version that could be found.",
                                "Je hebt de nieuwste versie die gevonden kon worden."
                            ),
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

                    // 26/09/2026 (editor, RONDE 190, bug gemeld na eigen
                    // test — op het toestel van een gezinslid (groter
                    // systeemlettertype) toonde deze rij alleen "Check now"/
                    // "What's new", de groene "Update now"-knop was
                    // onzichtbaar; op een toestel met normale lettergrootte
                    // stonden alle 3 knoppen er wel).
                    // Zelfde onderliggende bugklasse als taak #180 (zie
                    // ui/SettingsScreen.kt's kdoc daarbij): een Row zonder
                    // breedte-begrenzing op de kinderen duwt overtollige
                    // knoppen simpelweg van het scherm af zodra de
                    // gecombineerde (tekst-afhankelijke) breedte de
                    // schermbreedte overschrijdt — bij groter lettertype of
                    // een smaller toestel dan waarop dit getest was. Hier
                    // waren het drie knoppen zonder gewicht, dus de derde
                    // ("Update now") was de eerste die van het scherm afviel.
                    // Fix: elke knop krijgt `Modifier.weight(1f)`, zodat de
                    // rij haar breedte altijd eerlijk verdeelt over de
                    // zichtbare knoppen (2 of 3, afhankelijk van
                    // [updateAvailable]) — een knoptekst die niet past,
                    // loopt dan gewoon door naar een tweede regel in plaats
                    // van van het scherm te verdwijnen.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
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
                                            statusMessage = trNow("Update found: ${result.fileName}", "Update gevonden: ${result.fileName}")
                                        }
                                        is UpdateChecker.UpdateCheckResult.UpToDate -> {
                                            settings.clearAvailableUpdate()
                                            statusMessage = trNow("You're up to date.", "Je hebt de nieuwste versie.")
                                        }
                                        is UpdateChecker.UpdateCheckResult.NotConfigured ->
                                            statusMessage = trNow("Update check isn't set up yet.", "Update-controle is nog niet ingesteld.")
                                        is UpdateChecker.UpdateCheckResult.Error ->
                                            statusMessage = trNow("Couldn't check for updates: ${result.message}", "Kon niet op updates controleren: ${result.message}")
                                    }
                                    settings.setLastUpdateCheckAt(System.currentTimeMillis())
                                    isChecking = false
                                }
                            }
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            }
                            Text(tr("Check now", "Nu controleren"))
                        }

                        if (updateAvailable) {
                            // 05/09/2026 (editor, RONDE 170) — zie de kdoc
                            // bovenaan dit bestand: alleen zichtbaar naast
                            // "Update now" (dus alleen als updateAvailable),
                            // haalt de per-versie changelogs pas op bij het
                            // tikken zelf, niet al bij het openen van dit
                            // scherm (geen ongevraagd extra netwerkverkeer).
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
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
                                                    whatsNewMessage = trNow("No changelog available for this update yet.", "Nog geen changelog beschikbaar voor deze update.")
                                                }
                                            }
                                            is WhatsNewChecker.WhatsNewResult.NotConfigured ->
                                                whatsNewMessage = trNow("Update check isn't set up yet.", "Update-controle is nog niet ingesteld.")
                                            is WhatsNewChecker.WhatsNewResult.Error ->
                                                whatsNewMessage = trNow("Couldn't load what's new: ${result.message}", "Kon 'what's new' niet laden: ${result.message}")
                                        }
                                        isLoadingWhatsNew = false
                                    }
                                }
                            ) {
                                if (isLoadingWhatsNew) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                }
                                Text(tr("What's new", "Wat is er nieuw"))
                            }
                        }

                        if (updateAvailable) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !isChecking && !isInstalling,
                                onClick = {
                                    // 27/09/2026 (editor, op verzoek — zelfde patroon als
                                    // FCLvNext's FclTrustedBuild-check bij de "Installeren"-
                                    // knop) — "Check now" en "What's new" hierboven blijven
                                    // altijd werken, ook op een zelfgebouwde apk; alleen het
                                    // daadwerkelijk installeren wordt hier geblokkeerd, met een
                                    // duidelijke melding i.p.v. de download te starten en dan
                                    // pas op een cryptische Android-installatiefout te stuiten
                                    // (zie TrustedBuild.kt's kdoc).
                                    if (!TrustedBuild.isTrustedBuild(context)) {
                                        statusMessage = trNow(
                                            "wrong keystore — this build isn't signed with the official keystore and can't auto-update",
                                            "verkeerde keystore — deze build is niet ondertekend met de officiële keystore en kan niet automatisch updaten"
                                        )
                                        return@Button
                                    }
                                    isInstalling = true
                                    statusMessage = null
                                    scope.launch {
                                        when (val result = UpdateInstaller.downloadAndLaunchInstall(context, availableFileId)) {
                                            is UpdateInstaller.InstallLaunchResult.Launched ->
                                                statusMessage = null
                                            is UpdateInstaller.InstallLaunchResult.Failed ->
                                                statusMessage = trNow("Couldn't install the update: ${result.message}", "Kon de update niet installeren: ${result.message}")
                                            is UpdateInstaller.InstallLaunchResult.NeedsInstallPermission -> {
                                                statusMessage = trNow(
                                                    "Allow \"Install unknown apps\" for FCLGlucoLink, then try again.",
                                                    "Sta \"Install unknown apps\" toe voor FCLGlucoLink, en probeer het dan opnieuw."
                                                )
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
                                Text(tr("Update now", "Nu updaten"))
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
    // dialog buiten het scherm laat groeien op een klein toestel.  weergave-eenheid
    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            confirmButton = {
                TextButton(onClick = { showWhatsNew = false }) { Text(tr("Close", "Sluiten")) }
            },
            title = { Text(tr("What's new", "Wat is er nieuw")) },
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
                                tr("Version", "Versie") + " ${entry.versionCode}",
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
