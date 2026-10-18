package com.fclglucolink.app.update

import android.content.Context
import com.fclglucolink.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * UpdateChecker — kijkt op GitHub Releases of er een nieuwere APK klaarstaat
 * dan de geïnstalleerde versie (RONDE 165, RONDE 240: van Google Drive naar GitHub)
 * ============================================================================
 *
 * 18/10/2026 (editor, RONDE 240) — de APK's en de whatsnew-teksten staan niet meer in een
 * Google Drive-map maar als Release op de openbare GitHub-repo, zie GitHubApi.kt. Zo hoeft er
 * geen API-sleutel of map-ID meer in de app, en loopt het downloadverkeer niet via een
 * privé-Drive. De logica is verder gelijk gebleven: pak de hoogste versionCode (uit de tag) en
 * vergelijk die met de geïnstalleerde. Pre-releases tellen niet mee.
 *
 * In [UpdateCheckResult.UpdateAvailable] heet het veld nog `fileId` (zo staat het in de
 * opgeslagen instellingen en in AboutScreen.kt); het bevat nu de downloadlink van de APK.
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

    /** Eén uitgave in de lijst "andere versies" (RONDE 240). */
    data class VersionOption(
        val releaseNumber: Int,
        val downloadUrl: String,
        val fileName: String,
        val notes: String,
        val published: String,
        val prerelease: Boolean,
        /** `null` = de release-tekst zegt het niet, zie [GitHubApi.Release.androidVersionCode]. */
        val androidVersionCode: Int?
    ) {
        /**
         * True als Android deze versie zeker niet over de geïnstalleerde heen laat: de uitgave heeft
         * een lagere Android-versionCode (zie build.gradle.kts). Een terugstap kan dan alleen na
         * deïnstalleren, en daarbij gaan de gegevens van de app verloren.
         */
        fun blockedByAndroid(installedVersionCode: Int): Boolean =
            androidVersionCode != null && androidVersionCode < installedVersionCode
    }

    sealed class VersionListResult {
        data class Success(val versions: List<VersionOption>) : VersionListResult()
        data class Error(val message: String) : VersionListResult()
    }

    /** Alle uitgaven op GitHub (ook pre-releases), nieuwste eerst, voor de handmatige keuze. */
    suspend fun listVersions(): VersionListResult = withContext(Dispatchers.IO) {
        val releases = runCatching { GitHubApi.listReleases() }.getOrElse { e ->
            return@withContext VersionListResult.Error(e.message ?: "Network error")
        }
        VersionListResult.Success(
            releases.map {
                VersionOption(it.versionCode, it.apkUrl, it.apkName, it.body, it.published, it.prerelease, it.androidVersionCode)
            }
        )
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val releases = runCatching { GitHubApi.listReleases() }.getOrElse { e ->
            return@withContext UpdateCheckResult.Error(e.message ?: "Network error")
        }

        // Alleen echte releases, en altijd de HOOGSTE versionCode — nooit zomaar de eerste uit
        // de lijst.
        val newest = releases.filter { !it.prerelease }.maxByOrNull { it.versionCode }
            ?: return@withContext UpdateCheckResult.Error("No release with an APK found on GitHub")

        if (newest.versionCode > BuildConfig.RELEASE_NUMBER) {
            UpdateCheckResult.UpdateAvailable(newest.versionCode, newest.apkUrl, newest.apkName)
        } else {
            UpdateCheckResult.UpToDate
        }
    }
}
