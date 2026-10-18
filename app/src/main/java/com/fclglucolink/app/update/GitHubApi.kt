package com.fclglucolink.app.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * GitHubApi — leest de Releases van de openbare FCL-GlucoLink-repo (RONDE 240)
 * ============================================================================
 *
 * 18/10/2026 (editor, op verzoek — meer mensen gaan de app gebruiken en het
 * downloadverkeer via Google Drive moest niet blijven oplopen) — vervangt
 * DriveApi.kt. Elke uitgebrachte versie is een GitHub Release met de APK als
 * bijlage en de whatsnew-tekst als release-tekst.
 *
 * Conventie: tag `v<versionCode>` (bv. `v227`) en een bijlage die op `.apk`
 * eindigt. Het versienummer komt uit de tag; ontbreekt dat, dan uit de
 * bestandsnaam van de bijlage (`_v<cijfers>`, zoals de oude Drive-bestanden).
 * Een Release als "pre-release" markeren houdt 'm uit de gewone
 * update-controle.
 *
 * Geen API-sleutel nodig: de openbare API staat zonder aanmelding 60
 * aanvragen per uur per IP toe. Een controle kost er één.
 *
 * Puur blocking I/O (`HttpURLConnection`), net als de rest van dit package —
 * de aanroepers zorgen voor `withContext(Dispatchers.IO)`.
 */
internal object GitHubApi {

    const val OWNER = "Pluutje"
    const val REPO = "FCL-GlucoLink"

    /** Alleen van deze plek wordt een APK gedownload (zie UpdateInstaller.kt). */
    const val DOWNLOAD_PREFIX = "https://github.com/$OWNER/$REPO/releases/download/"

    private const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"

    data class Release(
        val versionCode: Int,
        val tag: String,
        val apkName: String,
        val apkUrl: String,
        /** Release-tekst zonder de `versioncode=`-regel, klaar om aan de gebruiker te tonen. */
        val body: String,
        val prerelease: Boolean,
        /** Datum (JJJJ-MM-DD) van publicatie, leeg als onbekend. */
        val published: String,
        /**
         * Android-versionCode van deze APK, uit een regel `versioncode=<getal>` in de release-
         * tekst. Alleen zetten als dit getal omhoog gaat (bij een nieuwe Room-databaseversie):
         * Android laat dan geen terugstap naar een lagere versionCode toe. `null` = niet opgegeven.
         */
        val androidVersionCode: Int?
    )

    private val VERSION_PATTERN = Regex("[vV](\\d+)")
    private val VERSIONCODE_LINE = Regex("(?im)^[ \\t]*versioncode[ \\t]*=[ \\t]*(\\d+)[ \\t]*$")

    /** Puur tekst-parsing, geen netwerk. `null` als er geen versienummer in staat. */
    fun parseVersionCode(tag: String, apkName: String): Int? =
        VERSION_PATTERN.find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("_[vV](\\d+)").find(apkName)?.groupValues?.get(1)?.toIntOrNull()

    /** Alle (niet-concept) releases met een APK-bijlage, nieuwste versie eerst. */
    fun listReleases(): List<Release> {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FCLGlucoLink")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("GitHub returned HTTP $responseCode: ${errorBody ?: "no body"}")
            }

            val array = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val result = ArrayList<Release>()
            for (i in 0 until array.length()) {
                val release: JSONObject = array.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                var apkName: String? = null
                var apkUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkName == null || apkUrl.isNullOrBlank()) continue
                val tag = release.optString("tag_name")
                val code = parseVersionCode(tag, apkName) ?: continue
                val rawBody = release.optString("body")
                val androidCode = VERSIONCODE_LINE.find(rawBody)?.groupValues?.get(1)?.toIntOrNull()
                result.add(
                    Release(
                        versionCode = code,
                        tag = tag,
                        apkName = apkName,
                        apkUrl = apkUrl,
                        body = VERSIONCODE_LINE.replace(rawBody, "").trim(),
                        prerelease = release.optBoolean("prerelease", false),
                        published = release.optString("published_at").take(10),
                        androidVersionCode = androidCode
                    )
                )
            }
            return result.sortedByDescending { it.versionCode }
        } finally {
            connection.disconnect()
        }
    }
}
