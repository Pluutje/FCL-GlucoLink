package com.fclglucolink.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * WhatsNewChecker — toont per versie wat er veranderd is, uit de release-tekst
 * op GitHub (RONDE 170, RONDE 240: van Drive-tekstbestanden naar GitHub Releases)
 * ============================================================================
 *
 * 05/09/2026 (editor, RONDE 170) — een "What's new"-knop die alleen de versies laat zien die
 * nieuwer zijn dan de geïnstalleerde (v180 geïnstalleerd, v181-183 klaar: alle drie; v182
 * geïnstalleerd: alleen v183).
 *
 * 18/10/2026 (editor, RONDE 240) — de tekst per versie staat nu in de release-tekst van de
 * GitHub Release zelf (zie GitHubApi.kt), in plaats van een los `_whatsnew.txt`-bestand op
 * Drive. De inhoud is dezelfde: een paar regels vanuit gebruikersperspectief. Een versie zonder
 * release-tekst wordt overgeslagen. [fetchSince] geeft alleen versies met een HOGERE
 * versionCode dan `sinceVersionCode` (de aanroeper geeft de geïnstalleerde versie door).
 */
object WhatsNewChecker {

    /** Eén versie's changelog-tekst, al getrimd. [versionCode] puur voor
     *  de kop ("Version 183") in de UI. */
    data class Entry(val versionCode: Int, val body: String)

    sealed class WhatsNewResult {
        data class Success(val entries: List<Entry>) : WhatsNewResult()
        data object NotConfigured : WhatsNewResult()
        data class Error(val message: String) : WhatsNewResult()
    }

    suspend fun fetchSince(context: Context, sinceVersionCode: Int): WhatsNewResult = withContext(Dispatchers.IO) {
        val releases = runCatching { GitHubApi.listReleases() }.getOrElse { e ->
            return@withContext WhatsNewResult.Error(e.message ?: "Network error")
        }
        val entries = releases
            .filter { !it.prerelease && it.versionCode > sinceVersionCode && it.body.isNotBlank() }
            .sortedBy { it.versionCode }
            .map { Entry(versionCode = it.versionCode, body = it.body) }
        WhatsNewResult.Success(entries)
    }
}
