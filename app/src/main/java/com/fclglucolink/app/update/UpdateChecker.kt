package com.fclglucolink.app.update

import android.content.Context
import com.fclglucolink.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * UpdateChecker — leest de gedeelde Google Drive-map uit om te bepalen of er
 * een nieuwere APK klaarstaat dan de geïnstalleerde versie (RONDE 165)
 * ============================================================================
 *
 * 04/09/2026 (editor, op verzoek: "Ik kreeg het verzoek om te onderzoeken of
 * er in de app ook een mogelijkheid is om een melding te krijgen als er een
 * update beschikbaar is. Ik stel de apk beschikbaar als download via mijn
 * google drive in een gedeelde map [...]") — het oorspronkelijke voorstel
 * ("kijk of het apk-bestand in die map een nieuwere DATUM heeft dan de
 * geïnstalleerde versie") bleek om twee redenen fragiel: (1) Drive's publieke
 * downloadlink toont voor grotere bestanden (en een APK van deze app zit al
 * snel op enkele tientallen MB's, zie build.gradle.kts's dependencies) een
 * "kan niet scannen op virussen"-tussenpagina i.p.v. het bestand zelf, wat
 * een simpele datum/HTTP-header-check om zeep helpt; (2) een bestandsdatum
 * kan ook zonder een ECHTE nieuwe versie veranderen (Drive raakt 'm intern
 * aan). Gekozen alternatief, ná overleg — zie de bijbehorende
 * AskUserQuestion-ronde en de gebruiker's eigen vervolgvoorstel: de
 * gebruiker zet het versienummer al in de BESTANDSNAAM (bv.
 * "FCLGlucoLink_v178.apk" — exact het patroon dat de zip-leveringen van dit
 * project toch al gebruiken), en deze klasse leest alleen de BESTANDSNAMEN in
 * de map uit (via de Drive API's `files.list`, dus geen download van de
 * daadwerkelijke (grote) APK-bytes nodig voor de CHECK zelf — dat gebeurt pas
 * in UpdateInstaller.kt, en dan via de Drive API's media-download-endpoint
 * i.p.v. de publieke downloadlink, wat de tussenpagina van punt (1) ook daar
 * vermijdt).
 *
 * Vereist een Drive API-key + de map-ID, zie BuildConfig.DRIVE_UPDATE_* (uit
 * local.properties, NOOIT hardcoded — zie build.gradle.kts's kdoc). Zonder
 * die twee (leeg gelaten) geeft [checkForUpdate] gewoon [NotConfigured] terug
 * — geen crash, de app werkt verder exact zoals voorheen (volledig offline
 * op deze ene, optionele check na).
 *
 * 05/09/2026 (editor, RONDE 170) — de eigen private `listApkFiles()`/
 * `DriveFile` van hiervoor zijn losgetrokken naar DriveApi.kt (zie dat
 * bestand's kdoc), zodat het nieuwe WhatsNewChecker.kt dezelfde
 * bestandenlijst-aanroep kan hergebruiken i.p.v. de HTTP/JSON-boilerplate
 * te kopiëren — puur een verhuizing, geen gedragswijziging.
 */
object UpdateChecker {

    /**
     * Resultaat van één check. Bewust een sealed class i.p.v. een nullable
     * Int: [Error] en [NotConfigured] moeten expliciet ANDERS afgehandeld
     * worden dan "geen update beschikbaar" (zie AboutScreen.kt) — bij een
     * netwerkfout wil je bijvoorbeeld GEEN "je hebt de nieuwste versie"-tekst
     * tonen, want dat weet de app dan feitelijk niet.
     */
    sealed class UpdateCheckResult {
        data object UpToDate : UpdateCheckResult()
        data class UpdateAvailable(
            val versionCode: Int,
            val fileId: String,
            val fileName: String
        ) : UpdateCheckResult()
        data object NotConfigured : UpdateCheckResult()
        data class Error(val message: String) : UpdateCheckResult()
    }

    // Matcht bv. "FCLGlucoLink_v178.apk" of "FCLGlucoLink_v178_r165.apk" —
    // het eerste "_v<cijfers>"-stukje in de bestandsnaam. Bewust NIET
    // versionName (bv. "0.9.78-...") parsen: dat is vrije tekst, versionCode
    // is het enige veld dat gegarandeerd een simpel, oplopend geheel getal
    // is en dus betrouwbaar > geeft.
    private val VERSION_PATTERN = Regex("_[vV](\\d+)")

    /**
     * Puur bestandsnaam-parsing, geen netwerk — apart getest/aanroepbaar
     * i.p.v. verstopt in de netwerk-functie hieronder.
     */
    fun parseVersionCode(fileName: String): Int? {
        if (!fileName.endsWith(".apk", ignoreCase = true)) return null
        return VERSION_PATTERN.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val folderId = BuildConfig.DRIVE_UPDATE_FOLDER_ID
        val apiKey = BuildConfig.DRIVE_UPDATE_API_KEY
        if (folderId.isBlank() || apiKey.isBlank()) {
            return@withContext UpdateCheckResult.NotConfigured
        }

        val files = runCatching { DriveApi.listFiles(folderId, apiKey) }.getOrElse { e ->
            return@withContext UpdateCheckResult.Error(e.message ?: "Network error")
        }

        // Meerdere .apk-bestanden kunnen in de map staan (oudere builds die
        // niet opgeruimd zijn) — pak altijd de HOOGSTE versionCode erin,
        // nooit zomaar de eerste/laatst-toegevoegde uit de lijst.
        val newest = files
            .mapNotNull { file -> parseVersionCode(file.name)?.let { code -> code to file } }
            .maxByOrNull { (code, _) -> code }

        if (newest == null) {
            return@withContext UpdateCheckResult.Error("No recognizable APK filename found in the Drive folder")
        }

        val (newestCode, newestFile) = newest
        if (newestCode > BuildConfig.VERSION_CODE) {
            UpdateCheckResult.UpdateAvailable(newestCode, newestFile.id, newestFile.name)
        } else {
            UpdateCheckResult.UpToDate
        }
    }
}
