package com.fclglucolink.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.fclglucolink.app.BuildConfig
import com.fclglucolink.app.logging.DiagnosticFileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ============================================================================
 * UpdateInstaller — downloadt de nieuwe APK (op verzoek van de gebruiker) en
 * opent Android's eigen installatiebevestiging (RONDE 165)
 * ============================================================================
 *
 * 04/09/2026 (editor, op verzoek: "Hij hoeft denk ik niet automatisch te
 * updaten dat kunnen de gebruikers wel handmatig doen. Wat dan wel handig is
 * als de google drive link toch al in de app bekend is dat hij dan zelf op
 * verzoek kan updaten.") — dus BEWUST geen stille/automatische achtergrond-
 * update: [downloadAndLaunchInstall] wordt alleen aangeroepen vanuit een
 * expliciete "Update now"-knoptik op AboutScreen.kt, en het resultaat is
 * altijd Android's eigen systeem-installatiescherm (waar de gebruiker zelf
 * nogmaals op "Install" tikt) — nooit een install die zonder die twee
 * expliciete gebruikersacties gebeurt.
 *
 * Download via de Drive API's media-endpoint (`?alt=media`), NIET de
 * publieke `uc?export=download`-link — zie UpdateChecker.kt's kdoc punt (1):
 * die publieke link toont voor een bestand van deze grootte een "kan niet
 * scannen op virussen"-tussenpagina (HTML, geen APK-bytes) i.p.v. het
 * bestand zelf. Het media-endpoint met een API-key geeft altijd de ruwe
 * bytes terug, ongeacht bestandsgrootte.
 */
object UpdateInstaller {

    sealed class InstallLaunchResult {
        data object Launched : InstallLaunchResult()
        data class Failed(val message: String) : InstallLaunchResult()
        /**
         * Android vereist dat de gebruiker "Install unknown apps" voor deze
         * app zelf ooit aangezet heeft — als dat nog niet zo is, sturen we
         * de gebruiker EERST naar dat systeemscherm i.p.v. de installatie
         * blind te proberen starten (die zou anders gewoon falen/niets
         * doen zonder duidelijke reden zichtbaar in de UI).
         */
        data object NeedsInstallPermission : InstallLaunchResult()
    }

    /**
     * Downloadt [fileId] naar de eigen cache (zie file_paths.xml) en opent
     * daarna de systeem-installatiebevestiging. Suspend, draait het
     * netwerkwerk op Dispatchers.IO — de aanroeper (AboutScreen.kt) start dit
     * vanuit een gewone `scope.launch { }`, geen aparte achtergronddienst
     * nodig: dit is een kort, eenmalig, door de gebruiker zelf getriggerd
     * verzoek, geen lang lopende taak zoals BleConnectionService.
     */
    suspend fun downloadAndLaunchInstall(context: Context, fileId: String): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallLaunchResult.NeedsInstallPermission
        }

        val apiKey = BuildConfig.DRIVE_UPDATE_API_KEY
        if (apiKey.isBlank()) {
            return InstallLaunchResult.Failed("Update check is not configured (missing Drive API key)")
        }

        // 04/09/2026 (editor, RONDE 167, na live-melding: "Download failed"
        // zonder verdere info — het generieke `e.message ?: "Download
        // failed"`-vangnet liet zien dat de opgevangen exception zelf GEEN
        // bericht had, waardoor de echte oorzaak onzichtbaar bleef) — nu:
        // altijd het exception-KLASSE-NAAM meesturen (nooit leeg, ook als
        // .message zelf null is), plus wegschrijven naar de diagnostische
        // log (DiagnosticFileLogger, zelfde bestand als de rest van de
        // sensor-diagnostiek) zodat een volgende mislukking niet opnieuw
        // via giswerk hoeft te worden onderzocht.
        //
        // 04/09/2026 (editor, RONDE 168 — de daadwerkelijke oorzaak, die
        // deze diagnostiek meteen zichtbaar maakte: "UpdateInstaller:
        // download failed — NetworkOnMainThreadException: (no message)")
        // — `downloadApk()` is een gewone, BLOKKERENDE functie (HttpURL-
        // Connection, geen suspend); zonder expliciete `withContext(
        // Dispatchers.IO)` draaide die dus gewoon op de coroutine-scope
        // waarmee AboutScreen.kt dit aanroept (`rememberCoroutineScope()`,
        // standaard Dispatchers.Main) — Android's StrictMode verbiedt
        // netwerk-I/O op de hoofdthread en gooit dan deze exception. Precies
        // hetzelfde patroon als UpdateChecker.kt's `checkForUpdate()` (die
        // dit WEL al goed deed, zie daar) had hier ook moeten staan.
        val apkFile = withContext(Dispatchers.IO) {
            runCatching { downloadApk(context, fileId, apiKey) }
        }.getOrElse { e ->
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
            DiagnosticFileLogger.log("UpdateInstaller: download failed — $detail")
            return InstallLaunchResult.Failed(detail)
        }

        return runCatching {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.Launched
        }.getOrElse { e ->
            InstallLaunchResult.Failed(e.message ?: "Could not open the install screen")
        }
    }

    private fun downloadApk(context: Context, fileId: String, apiKey: String): File {
        val urlStr = "https://www.googleapis.com/drive/v3/files/" +
            URLEncoder.encode(fileId, "UTF-8") +
            "?alt=media&key=" + URLEncoder.encode(apiKey, "UTF-8")

        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            // 04/09/2026 (editor) — ruim boven de connectTimeout: dit is een
            // daadwerkelijke APK-download (tientallen MB's), geen kleine
            // metadata-aanroep zoals UpdateChecker.kt's listApkFiles().
            connection.readTimeout = 120_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 04/09/2026 (editor, RONDE 167) — Google's API-foutrespons
                // (JSON, bv. "API key not valid" of een permissie-melding)
                // zit in de ERROR-stream bij een non-200 status, niet in de
                // normale inputStream — zonder dit las de vorige versie
                // niets, en bleef de reden gokwerk.
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                throw IllegalStateException(
                    "Drive download returned HTTP $responseCode" +
                        (errorBody?.let { ": $it" } ?: "")
                )
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outputFile = File(updatesDir, "update.apk")
            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return outputFile
        } finally {
            connection.disconnect()
        }
    }
}
