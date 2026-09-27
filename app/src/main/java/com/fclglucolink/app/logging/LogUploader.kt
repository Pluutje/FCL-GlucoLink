package com.fclglucolink.app.logging

import android.content.Context
import android.provider.Settings
import com.fclglucolink.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * LogUploader — stuurt een geselecteerd diagnostisch logbestand naar Google
 * Drive via een Google Apps Script Web App (RONDE 174, 11/09/2026)
 * ============================================================================
 *
 * Zelfde patroon als FCLvNext's eigen CSV-upload (een los Apps Script,
 * "Execute as: Me", dus geschreven met het account van de scripteigenaar,
 * geen OAuth-inlog nodig in deze app zelf) — maar bewust een NIEUW, apart
 * script + eigen Drive-map i.p.v. het bestaande FCLvNext-CSV-script
 * hergebruiken: dat script verwacht een `csvContent`-veld, forceert een
 * vaste `FCLvNext_Log_<id>_<tijdstip>.csv`-bestandsnaam, en vervangt bij elke
 * upload ALLE eerdere bestanden van dezelfde telefoon (bedoeld voor "de
 * laatste toestand", niet voor meerdere dag-logs naast elkaar). Zie
 * `FCLGlucoLink_LogUpload_AppsScript.gs.txt` (bij de projectbestanden) voor
 * de volledige scriptcode en deploy-instructies.
 *
 * REQUEST: JSON POST, velden `secret`/`deviceId`/`fileName`/`logContent` —
 * de bestandsinhoud gaat als platte UTF-8-tekst in `logContent` mee (geen
 * base64, geen multipart), rechtstreeks bruikbaar door het script via
 * `Utilities.newBlob(...)`. `deviceId` (Settings.Secure.ANDROID_ID) laat het
 * script bestanden van meerdere telefoons in dezelfde map uit elkaar houden;
 * `fileName` is het bestaande `fclglucolink_yyyy-MM-dd.txt` (zie
 * DiagnosticFileLogger.kt) — het script zet er zelf een korte apparaat-ID
 * voor, en overschrijft een eerdere upload met exact dezelfde naam (dus een
 * her-upload van dezelfde dag vervangt de vorige versie van die dag; andere
 * dagen blijven gewoon naast elkaar staan).
 *
 * REDIRECT-AFHANDELING: Apps Script Web Apps beantwoorden een POST altijd
 * met een 302 naar `script.googleusercontent.com` voor het eigenlijke
 * antwoord — `HttpURLConnection` volgt dat NIET automatisch bij een
 * cross-host-redirect, dus [postJson] zet `instanceFollowRedirects = false`,
 * herkent de 3xx zelf, en doet handmatig één GET op de meegestuurde
 * `Location`-header. Zonder deze stap blijft de aanroep hangen op een lege
 * of onbruikbare respons.
 */
object LogUploader {

    sealed class UploadResult {
        data class Success(val fileName: String) : UploadResult()
        data class Error(val message: String) : UploadResult()
        data object NotConfigured : UploadResult()
    }

    /** Suspend, draait op Dispatchers.IO — zelfde reden als
     *  UpdateInstaller.kt's downloadApk()'s kdoc: blokkerende
     *  HttpURLConnection-aanroepen mogen nooit op de aanroepende
     *  coroutine-scope (doorgaans Dispatchers.Main vanuit een Compose-
     *  scherm) draaien. */
    suspend fun upload(context: Context, file: File): UploadResult = withContext(Dispatchers.IO) {
        val url = BuildConfig.DRIVE_LOG_UPLOAD_URL
        val secret = BuildConfig.DRIVE_LOG_UPLOAD_SECRET
        if (url.isBlank() || secret.isBlank()) {
            return@withContext UploadResult.NotConfigured
        }

        val deviceId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "unknown"

        runCatching {
            val payload = JSONObject().apply {
                put("secret", secret)
                put("deviceId", deviceId)
                put("fileName", file.name)
                put("logContent", file.readText())
            }
            val json = JSONObject(postJson(url, payload.toString()))
            if (json.optBoolean("ok", false)) {
                UploadResult.Success(json.optString("fileName", file.name))
            } else {
                UploadResult.Error(json.optString("error", "Unknown error"))
            }
        }.getOrElse { e ->
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
            DiagnosticFileLogger.log("LogUploader: upload of ${file.name} failed — $detail")
            UploadResult.Error(detail)
        }
    }

    private fun postJson(urlStr: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        var connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.outputStream.use { it.write(bytes) }

            val responseCode = connection.responseCode
            val isRedirect = responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 307 ||
                responseCode == 308

            if (isRedirect) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without a Location header (HTTP $responseCode)")
                connection.disconnect()
                connection = URL(location).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                val redirectedCode = connection.responseCode
                if (redirectedCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Apps Script redirect returned HTTP $redirectedCode")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                throw IllegalStateException(
                    "Apps Script returned HTTP $responseCode" + (errorBody?.let { ": $it" } ?: "")
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
