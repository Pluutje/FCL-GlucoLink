package com.fclglucolink.app.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ============================================================================
 * DriveApi — kleine, gedeelde Google Drive-hulpfuncties (RONDE 170)
 * ============================================================================
 *
 * 05/09/2026 (editor, RONDE 170) — losgetrokken uit UpdateChecker.kt's
 * private `listApkFiles()`/`DriveFile` (Ronde 165), zodat het nieuwe
 * WhatsNewChecker.kt (zie dat bestand's kdoc) dezelfde bestandenlijst-
 * aanroep kan hergebruiken i.p.v. de HTTP/JSON-boilerplate te kopiëren.
 * [downloadTextFile] is nieuw: hetzelfde media-download-endpoint als
 * UpdateInstaller.kt's APK-download, maar hier direct als String
 * teruggegeven i.p.v. weggeschreven naar een cache-bestand — de content
 * die dit ophaalt (een korte "_whatsnew.txt") is klein en wordt alleen
 * getoond, nooit geïnstalleerd.
 *
 * Puur blocking I/O (`HttpURLConnection`), net als de rest van dit
 * package — de aanroepers zelf zorgen voor `withContext(Dispatchers.IO)`,
 * zie UpdateChecker.kt/WhatsNewChecker.kt.
 */
internal object DriveApi {
    data class DriveFile(val id: String, val name: String)

    fun listFiles(folderId: String, apiKey: String): List<DriveFile> {
        val query = "'$folderId' in parents and trashed=false"
        val urlStr = "https://www.googleapis.com/drive/v3/files" +
            "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&fields=" + URLEncoder.encode("files(id,name)", "UTF-8") +
            "&key=" + URLEncoder.encode(apiKey, "UTF-8")

        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("Drive API returned HTTP $responseCode: ${errorBody ?: "no body"}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val filesArray = json.optJSONArray("files") ?: return emptyList()
            return buildList {
                for (i in 0 until filesArray.length()) {
                    val obj = filesArray.getJSONObject(i)
                    add(DriveFile(id = obj.getString("id"), name = obj.getString("name")))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadTextFile(fileId: String, apiKey: String): String {
        val urlStr = "https://www.googleapis.com/drive/v3/files/" +
            URLEncoder.encode(fileId, "UTF-8") +
            "?alt=media&key=" + URLEncoder.encode(apiKey, "UTF-8")

        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException(
                    "Drive download returned HTTP $responseCode" +
                        (errorBody?.let { ": $it" } ?: "")
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
