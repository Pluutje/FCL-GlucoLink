package com.fclglucolink.app.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * FCLGlucoLink — diagnose-logboek naar bestand (RONDE 35, 04/08/2026)
 * ============================================================================
 *
 * AANLEIDING (op verzoek, na ronde 34): `adb logcat` is alleen bruikbaar
 * zolang de telefoon aan een laptop hangt (of, via een bugrapport, alleen de
 * laatste paar minuten vóór het maken ervan — de rest verdrinkt in
 * systeemruis, zie README ronde 34). Voor een test tijdens ECHT, regulier
 * gebruik van de telefoon (geen kabel, uren/dagen lang) is geen van beide
 * bruikbaar. Deze singleton schrijft dezelfde diagnostische regels die
 * eerder alleen naar logcat gingen nu (ook) naar een eigen tekstbestand,
 * ONAFHANKELIJK van elke logcat-ringbuffer.
 *
 * BEWUST GEKOZEN OPSLAGPLEK: `context.getExternalFilesDir(null)` (resulteert
 * in `.../Android/data/com.fclglucolink.app/files/log/`), NIET een
 * handmatig pad als `Interne opslag/aaps/fclglucolink/log` — dat laatste
 * zou op Android 11+ (scoped storage) de brede MANAGE_EXTERNAL_STORAGE-
 * permissie vereisen (een zware permissie voor puur een debug-logboek).
 * `getExternalFilesDir()` heeft GEEN extra permissie nodig, blijft na een
 * app-update behouden (alleen een volledige DE-installatie wist 'm), en is
 * gewoon met elke bestandsbeheerapp te vinden en te delen.
 *
 * BEWUST UIT (standaard): schrijven-per-regel heeft altijd een kleine
 * I/O-kost — verwaarloosbaar bij de lage frequentie hier (een paar regels
 * per BLE-cyclus, hooguit eens per minuut), maar toch alleen actief
 * wanneer bewust aangezet via Instellingen -> Diagnose-logboek (zie
 * SettingsScreen.kt), zodat het normale gebruik nooit onnodig belast wordt.
 *
 * BEWUST ÉÉN BESTAND PER DAG (`fclglucolink_yyyy-MM-dd.txt`): een
 * meerdaagse test zou anders één onbeperkt groeiend bestand geven — met een
 * dagsplitsing blijft elk bestand overzichtelijk en makkelijk los te delen.
 *
 * BEWUST OOK NAAR LOGCAT (via `log()`/`logError()`, ongeacht de schakelaar):
 * kost vrijwel niets, en blijft nuttig voor het geval er toch een keer een
 * live `adb logcat`-sessie meeloopt — dat verving eerder de losse
 * `android.util.Log.i("CareSensAirDriver", ...)`-aanroepen door de driver
 * heen (nu allemaal via hier, één plek i.p.v. dubbel onderhoud).
 */
object DiagnosticFileLogger {
    // 09/08/2026 (editor, RONDE 58) — was hardcoded "CareSensAirDriver",
    // een restant van vóórdat deze logger gedeeld werd door meerdere
    // drivers. Alle logregels (ook Dexcom G6's) verschenen daardoor in
    // logcat onder die ene, misleidende tag — verwarrend bij het
    // analyseren van een G6-logcat-dump (de regels zeiden "DexcomG6: ..."
    // in de boodschap zelf, maar de logcat-tag ernaast zei
    // "CareSensAirDriver"). Elke driver zet zijn eigen naam al vooraan in
    // de boodschap zelf (zie alle `DiagnosticFileLogger.log("DexcomG6:
    // ...")`/`("CareSensAir: ...")`-aanroepen), dus deze tag hoeft alleen
    // nog de bron van het LOGSYSTEEM zelf aan te duiden.
    private const val TAG = "FCLGlucoLink"

    @Volatile private var enabled: Boolean = false
    @Volatile private var appContext: Context? = null

    /** Aanroepen bij app-start (zie FclGlucoLinkApp.kt), met de destijds
     *  opgeslagen schakelaarstand — zie kdoc bij setEnabled() voor waarom
     *  dit een los, in-memory vlaggetje is i.p.v. steeds DataStore te lezen. */
    fun init(context: Context, initiallyEnabled: Boolean) {
        appContext = context.applicationContext
        enabled = initiallyEnabled
    }

    /**
     * 04/08/2026 — los, in-memory `@Volatile`-vlaggetje i.p.v. bij elke
     * logregel een (suspend) DataStore-lezing te doen: dit wordt aangeroepen
     * vanuit elke BLE-callback (GATT-thread), en een blokkerende/suspend-
     * lezing daar zou precies het soort timing-verstoring riskeren die dit
     * hele logboek juist NIET mag veroorzaken. SettingsScreen.kt roept dit
     * rechtstreeks aan zodra de gebruiker de schakelaar omzet (naast het
     * los persisteren via AppSettings, voor de volgende app-start).
     */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /** Logbestandsmap, alleen aangemaakt zodra er voor het eerst iets in
     *  weggeschreven wordt — geen overbodige lege map bij een schakelaar die
     *  toch nooit aangezet wordt. */
    private fun logDirOrNull(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.getExternalFilesDir(null), "log")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return null
        return dir
    }

    private fun writeLine(message: String) {
        if (!enabled) return
        runCatching {
            val dir = logDirOrNull() ?: return
            val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "fclglucolink_$dateStamp.txt")
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$timeStamp $message\n")
        }
    }

    fun log(message: String) {
        android.util.Log.i(TAG, message)
        writeLine(message)
    }

    fun logError(message: String) {
        android.util.Log.e(TAG, message)
        writeLine("ERROR: $message")
    }
}
