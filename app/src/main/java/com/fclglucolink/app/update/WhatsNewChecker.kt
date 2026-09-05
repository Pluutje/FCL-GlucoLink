package com.fclglucolink.app.update

import android.content.Context
import com.fclglucolink.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * WhatsNewChecker — leest per-versie "wat is er veranderd"-samenvattingen uit
 * dezelfde gedeelde Google Drive-map als UpdateChecker.kt (RONDE 170)
 * ============================================================================
 *
 * 05/09/2026 (editor, op verzoek: "als er een update beschikbaar is een
 * 'whats new' knop [...] die zou dan per versie moeten tonen wat er is
 * veranderd/aangepast/toegevoegd [...] Hij moet alles wat er is aangepast
 * sinds de versie die gebruikt is moeten tonen. Dus stel iemand gebruikt
 * v180 en in de drive staan v181, v182 en v183 klaar dan moeten de
 * aanpassingen van 181,182 en 183 worden getoond, maar zit iemand al op
 * 182 dan moet alleen 183 worden getoond.") —
 *
 * ONTWERP: UpdateChecker.kt's bestandsnaam-detectie (Ronde 165) kan alleen
 * zeggen DAT er een nieuwere versie is, niet WAT daarin veranderd is — die
 * inhoud bestaat simpelweg nog niet in de huidige, geïnstalleerde APK (een
 * app die nu v180 draait, kan onmogelijk uit haar eigen gecompileerde code
 * weten wat v181/182/183 straks bevatten). De enige plek waar die info wél
 * al bestaat op het moment dat een oudere app hem nodig heeft, is dezelfde
 * gedeelde Drive-map als de APK's zelf.
 *
 * BESTANDSCONVENTIE: net als de APK's zelf (Ronde 165: "_v<cijfers>" in de
 * bestandsnaam), krijgt elke uitgebrachte versie een eigen klein tekst-
 * bestand `FCLGlucoLink_v<versionCode>_whatsnew.txt` in dezelfde Drive-map
 * — een paar regels platte tekst, geschreven vanuit gebruikersperspectief
 * (het gaat hier NIET om de uitgebreide README.md-rondeteksten, die zijn
 * ontwikkelaarsgericht/Nederlands/technisch; dit is bewust kort, Engels,
 * en gericht op "wat verandert er voor mij"). Bewust een NIEUW bestand PER
 * versie (append-only, past bij hoe er toch al één nieuwe APK per release
 * wordt toegevoegd) i.p.v. één gedeeld, telkens te bewerken bestand — dat
 * laatste zou bij een vergeten update-stap stilzwijgend een oudere versie
 * tonen zonder dat iemand het merkt.
 *
 * FILTERING: [fetchSince] geeft alleen versies terug met een HOGERE
 * versionCode dan het meegegeven `sinceVersionCode` (de AANROEPER geeft
 * hier altijd `BuildConfig.VERSION_CODE` van de huidig geïnstalleerde app
 * door, zie AboutScreen.kt) — exact het "v180 ziet 181+182+183, v182 ziet
 * alleen 183"-gedrag uit het verzoek hierboven, zonder enige aparte
 * "laatste geziene versie"-status te hoeven bijhouden: de vergelijking is
 * altijd tegen wat er NU daadwerkelijk draait.
 *
 * Ontbreekt een `_whatsnew.txt` voor een bepaalde tussenliggende versie
 * (bv. nog niet geüpload, of een oudere versie van vóór deze functie
 * bestond) dan wordt die versie simpelweg overgeslagen — nooit een fout,
 * gewoon een kortere lijst. [AboutScreen.kt] toont een nette
 * "geen changelog beschikbaar"-tekst als de lijst na filtering leeg blijkt.
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

    // Matcht bv. "FCLGlucoLink_v183_whatsnew.txt" — hetzelfde "_v<cijfers>"-
    // patroon als UpdateChecker.VERSION_PATTERN; hier lokaal herhaald i.p.v.
    // gedeeld, want de twee bestandsextensies/-suffixen zijn verder niet
    // hetzelfde (".apk" vs. "_whatsnew.txt") en de regex zelf is triviaal.
    private val VERSION_PATTERN = Regex("_[vV](\\d+)")

    /** Puur bestandsnaam-parsing, geen netwerk — zelfde opzet als
     *  UpdateChecker.parseVersionCode(). */
    fun parseVersionCode(fileName: String): Int? {
        if (!fileName.endsWith("_whatsnew.txt", ignoreCase = true)) return null
        return VERSION_PATTERN.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun fetchSince(context: Context, sinceVersionCode: Int): WhatsNewResult = withContext(Dispatchers.IO) {
        val folderId = BuildConfig.DRIVE_UPDATE_FOLDER_ID
        val apiKey = BuildConfig.DRIVE_UPDATE_API_KEY
        if (folderId.isBlank() || apiKey.isBlank()) {
            return@withContext WhatsNewResult.NotConfigured
        }

        val files = runCatching { DriveApi.listFiles(folderId, apiKey) }.getOrElse { e ->
            return@withContext WhatsNewResult.Error(e.message ?: "Network error")
        }

        val relevant = files
            .mapNotNull { file -> parseVersionCode(file.name)?.let { code -> Triple(code, file.id, file.name) } }
            .filter { (code, _, _) -> code > sinceVersionCode }
            .sortedBy { (code, _, _) -> code }

        // Elk relevant bestand is klein (een paar regels), dus gewoon
        // sequentieel downloaden — geen parallelle Dispatchers.IO-fan-out
        // nodig voor een handjevol bestanden die toch al zelden meer dan
        // een paar versies uit elkaar liggen.
        val entries = relevant.mapNotNull { (code, fileId, _) ->
            val text = runCatching { DriveApi.downloadTextFile(fileId, apiKey) }.getOrNull()
                ?: return@mapNotNull null
            Entry(versionCode = code, body = text.trim())
        }
        WhatsNewResult.Success(entries)
    }
}
