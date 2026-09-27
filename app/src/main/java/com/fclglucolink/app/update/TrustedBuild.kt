package com.fclglucolink.app.update

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 27/09/2026 (editor, op verzoek — zelfde patroon als FclTrustedBuild.kt in de FCLvNext-app) —
 * herkent of deze APK ondertekend is met de eigen release-keystore van de FCLGlucoLink-auteur.
 * Bewust NIET gebruikt om de hele "Updates"-kaart te verbergen: "Check now" en "What's new"
 * moeten altijd blijven werken, ook op een zelfgebouwde apk. Alleen het daadwerkelijk
 * INSTALLEREN wordt geblokkeerd (zie de "Update now"-knop in AboutScreen.kt) met een duidelijke
 * foutmelding, want Android weigert een installatie met een andere handtekening toch altijd —
 * beter een nette melding vooraf (zonder eerst de officiële APK nodeloos te downloaden) dan een
 * cryptische Android-installatiefout achteraf.
 */
object TrustedBuild {

    private const val TRUSTED_SHA256_FINGERPRINT = "BDA0817C5A3C0B2934907A11C5BB7458E008E8EF42B4B877D5C875810561C0C2"

    /**
     * True als deze APK ondertekend is met [TRUSTED_SHA256_FINGERPRINT], of als die constante nog
     * leeg is. Ook true bij een onverwachte fout tijdens het uitlezen (fail-open): een fout hier
     * mag de update-functie niet permanent verbergen voor de echte, vertrouwde build.
     */
    fun isTrustedBuild(context: Context): Boolean {
        if (TRUSTED_SHA256_FINGERPRINT.isBlank()) return true
        val target = TRUSTED_SHA256_FINGERPRINT.replace(":", "").replace(" ", "").lowercase()
        return try {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val signatures = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
            signatures?.any { signature ->
                val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                digest.toHex() == target
            } ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xFF
            sb.append(hexChars[i shr 4])
            sb.append(hexChars[i and 0x0F])
        }
        return sb.toString()
    }
}
