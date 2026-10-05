package com.fclglucolink.app.logging

import android.content.Context
import com.fclglucolink.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
 * 11/09/2026 (editor, RONDE 174, op verzoek om de losse aan/uit-schakelaar
 * te laten vervallen en logging altijd te laten draaien) — was tot deze
 * ronde standaard UIT en alleen aan te zetten via een schakelaar op
 * SettingsScreen.kt; die schakelaar (en de bijbehorende AppSettings-sleutel)
 * is vervallen, [enabled] staat nu altijd op `true` (zie [init]'s aanroep in
 * FclGlucoLinkApp.kt). Schrijven-per-regel heeft altijd een kleine I/O-kost,
 * maar die is verwaarloosbaar bij de lage frequentie hier (een paar regels
 * per BLE-cyclus, hooguit eens per minuut). Om te voorkomen dat de logmap
 * ongelimiteerd doorgroeit nu dit niet meer uitgezet kan worden, ruimt
 * [pruneOldLogs] bestanden ouder dan een paar weken automatisch op — zie
 * die functie's kdoc.
 *
 * BEWUST ÉÉN BESTAND PER DAG (`fclglucolink_yyyy-MM-dd.txt`): een
 * meerdaagse test zou anders één onbeperkt groeiend bestand geven — met een
 * dagsplitsing blijft elk bestand overzichtelijk en makkelijk los te delen
 * (en te selecteren via SettingsScreen.kt's "Send log files", zie
 * [listLogFiles]).
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

    /**
     * 29/08/2026 (editor, RONDE 156 — puur diagnostisch, GEEN gedrags-
     * wijziging) — AANLEIDING: live-melding dat de app na het installeren
     * van v169 bijna een kwartier op "connecting" bleef hangen. De
     * meegestuurde log (fclglucolink_2026-08-28 23.59.txt) toont tussen
     * 23:45:58 en 23:48:09 een korte, chaotische reeks mislukte
     * herverbindingen (waaronder een niet eerder geziene status=133),
     * gevolgd door VOLLEDIGE stilte — geen enkele DexcomG7-regel meer,
     * terwijl de disconnect-handler in DexcomG7Driver.kt na ELKE disconnect
     * onvoorwaardelijk een nieuwe scanpoging inplant. Zo'n totale stilte
     * (i.p.v. herhaalde foutregels) past bij een eerder, soortgelijk
     * bevestigd scenario (BleConnectionService.kt's Ronde 59-kdoc: twee
     * gelijktijdige BluetoothGatt-verbindingen naar hetzelfde toestel
     * brachten de transmitter in de war, waarna beide meteen weer
     * verbraken) — het vermoeden is dat de update-herstart kortstondig TWEE APARTE
     * PROCESSEN met elk hun eigen BleConnectionService-instantie heeft
     * opgeleverd (elk met een eigen mutex/driver/sessiesleutel — de
     * bestaande startCommandMutex-bescherming werkt alleen BINNEN één
     * proces). Dit is NIET hard te bewijzen uit de huidige log: er staat
     * nergens een proces-ID bij.
     *
     * [instanceTag] hieronder is een korte, willekeurige tag die precies
     * ÉÉN keer wordt aangemaakt zodra dit object voor het eerst wordt
     * aangeraakt — en dat gebeurt in de praktijk hoogstens één keer per
     * proces (Kotlin `object`s zijn per-proces singletons; een nieuw
     * Android-proces = een nieuwe JVM/ART-instantie = een verse
     * class-initialisatie). Twee gelijktijdig actieve processen krijgen
     * dus gegarandeerd VERSCHILLENDE tags. Bevat ook het proces-ID zelf
     * (handig om rechtstreeks te correleren met een systeem-logcat-dump),
     * plus een korte random suffix als extra zekerheid tegen PID-hergebruik.
     * Toegevoegd in [writeLine] en [logFatal] — ÉÉN plek, dus geldt
     * automatisch voor elke bestaande `DiagnosticFileLogger.log(...)`-
     * aanroep door de hele app heen, zonder één van de honderden bestaande
     * aanroepen zelf te hoeven aanpassen.
     *
     * Ziet een volgende log twee VERSCHILLENDE tags door elkaar heen lopen
     * binnen hetzelfde tijdsbestek, dan is het duale-proces-vermoeden
     * bevestigd — blijft het overal dezelfde ene tag, dan ligt de oorzaak
     * ergens anders en moet dat spoor losgelaten worden.
     */
    private val instanceTag: String by lazy {
        val pid = android.os.Process.myPid()
        val suffix = (0..0xFFF).random().toString(16).padStart(3, '0')
        "$pid-$suffix"
    }

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

    /** Herkent `fclglucolink_yyyy-MM-dd.txt` en levert de datum als
     *  matchgroep — gedeeld door [listLogFiles]/[pruneOldLogs] zodat beide
     *  functies exact dezelfde bestandsnaamconventie hanteren. */
    private val logFileNameRegex = Regex("""fclglucolink_(\d{4}-\d{2}-\d{2})\.txt""")

    /** Logbestandsmap, alleen aangemaakt zodra er voor het eerst iets in
     *  weggeschreven wordt — geen overbodige lege map bij een schakelaar die
     *  toch nooit aangezet wordt. */
    private fun logDirOrNull(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.getExternalFilesDir(null), "log")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return null
        return dir
    }

    /**
     * 11/09/2026 (editor, RONDE 174) — bestaande dag-logbestanden, nieuwste
     * datum eerst, voor SettingsScreen.kt's "Send log files"-keuzelijst
     * (zie [LogUploader]). Sorteren op bestandsnaam volstaat: de
     * `yyyy-MM-dd`-datum in de naam sorteert lexicografisch al gelijk aan
     * chronologisch.
     */
    fun listLogFiles(): List<File> {
        val dir = logDirOrNull() ?: return emptyList()
        return dir.listFiles { file -> file.isFile && logFileNameRegex.matches(file.name) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * 13/09/2026 (editor, RONDE 177) — absoluut pad naar HET logbestand van
     * vandaag, exact dezelfde bestandsnaamconventie als [writeLine]/
     * [logFatal] gebruiken. Bestaat voor `CareSensAirNative.
     * installCrashHandler()`: de native crash-handler (caresensair_bridge.cpp)
     * kan op het moment dat hij daadwerkelijk afgaat geen JNIEnv/Context meer
     * aanraken (zie die kdoc), dus moet dit pad VOORAF, als kale string, al
     * bekend zijn. Retourneert null als de logmap niet aangemaakt kon worden
     * (bv. geen appContext) — de aanroeper slaat het installeren dan gewoon
     * over, net zoals [writeLine] in dat geval stilletjes niets doet.
     */
    fun todaysLogFilePath(): String? {
        val dir = logDirOrNull() ?: return null
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(dir, "fclglucolink_$dateStamp.txt").absolutePath
    }

    /**
     * 11/09/2026 (editor, RONDE 174, op verzoek om de logmap niet te laten
     * vollopen nu logging altijd aan staat — oudere bestanden zijn toch niet
     * zinvol meer om te bewaren) — verwijdert elk logbestand waarvan de
     * datum IN DE BESTANDSNAAM meer dan [maxAgeDays] dagen in het verleden
     * ligt. Bewust de datum uit de naam vergeleken, niet `lastModified()`:
     * een dag-log die nog actief bijgeschreven wordt, zou anders altijd
     * "recent" lijken ongeacht wat er in zijn eigen bestandsnaam staat, en
     * andersom zou een oud, nooit meer aangeraakt bestand z'n originele
     * `lastModified()`-datum al hebben, dus in de praktijk geeft dat hier
     * hetzelfde resultaat — de bestandsnaam is gewoon de expliciete,
     * ondubbelzinnige bron. Aangeroepen vanuit [init] (dus één keer per
     * app-start, zie FclGlucoLinkApp.kt) — geen I/O-kost op het hot path
     * van [writeLine].
     */
    fun pruneOldLogs(maxAgeDays: Int = 16) {
        runCatching {
            val dir = logDirOrNull() ?: return
            val cutoff = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -maxAgeDays)
            }.time
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            dir.listFiles()?.forEach { file ->
                val match = logFileNameRegex.matchEntire(file.name) ?: return@forEach
                val fileDate = runCatching { dateFormat.parse(match.groupValues[1]) }.getOrNull()
                    ?: return@forEach
                if (fileDate.before(cutoff)) {
                    file.delete()
                }
            }
        }
    }

    /**
     * 24/09/2026 (editor, RONDE 182, op verzoek — na een crash-loop-
     * caselog van een andere gebruiker waarbij achteraf niet was af te leiden of diens build de
     * Ronde 179-fix al had: "we zetten bij een volgende correctie sowieso
     * de versie in de log zodat het 100% duidelijk is") — schrijft één
     * regel met [BuildConfig.VERSION_NAME]/[BuildConfig.VERSION_CODE] vlak
     * VOORDAT het dagbestand voor het eerst iets anders bevat, zodat elk
     * meegestuurd logbestand ONDUBBELZINNIG laat zien welke build het was
     * — geen aparte instelling/toggle nodig, dit hangt gewoon aan de
     * bestaande "nieuwe dag = nieuw bestand"-conventie (zie [writeLine]):
     * een meerdaagse test krijgt zo bij elke dagwisseling opnieuw de
     * (mogelijk inmiddels bijgewerkte) versie te zien.
     *
     * 02/10/2026 (editor, RONDE 205, bugfix na live-controle) — de
     * `if (file.exists()) return`-gate hierboven betekende: binnen één
     * kalenderdag schrijft dit precies ÉÉN keer een banner, bij het EERSTE
     * schrijfmoment van die dag — niet bij elke app-herstart. Een
     * update/herinstallatie die later diezelfde dag gebeurt (bv. tijdens
     * een intensieve live-testsessie met meerdere builds per dag, precies
     * wat hier gebeurde) levert dan GEEN nieuwe banner op, ook al draait
     * er feitelijk een andere build — het logbestand suggereert dan ten
     * onrechte dat alles nog steeds dezelfde build is. Nu: een banner bij
     * ELKE app-start (zie [init] in FclGlucoLinkApp.kt), niet meer alleen
     * bij het eerste schrijfmoment van de dag — zo blijft het 100%
     * duidelijk welke build op welk moment daadwerkelijk draaide, ook bij
     * meerdere herinstallaties/herstarts binnen dezelfde dag.
     */
    @Volatile
    private var versionBannerWrittenThisProcess = false

    private fun writeVersionHeaderIfNeeded(file: File) {
        if (versionBannerWrittenThisProcess) return
        versionBannerWrittenThisProcess = true
        runCatching {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText(
                "$timeStamp [$instanceTag] === FCLGlucoLink ${BuildConfig.VERSION_NAME} " +
                    "(build ${BuildConfig.VERSION_CODE}, gebouwd ${BuildConfig.BUILD_TIME}) ===\n"
            )
        }
    }

    private fun writeLine(message: String) {
        if (!enabled) return
        runCatching {
            val dir = logDirOrNull() ?: return
            val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "fclglucolink_$dateStamp.txt")
            writeVersionHeaderIfNeeded(file)
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$timeStamp [$instanceTag] $message\n")
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

    /**
     * 27/08/2026 (editor, RONDE 126, na analyse van diagnostische logbestanden
     * van een gebruiker) — vóór deze ronde bevatte GEEN van de drie
     * diagnose-logbestanden ooit een spoor van een crash: de app-crash zelf
     * killt het proces voordat de gewone, `enabled`-afhankelijke [log]/
     * [logError] iets hadden kunnen wegschrijven — het enige wat zichtbaar
     * was, was een gat in de tijdlijn (BLE-communicatie stopt abrupt zonder
     * de gebruikelijke STATE_DISCONNECTED-regel, gevolgd door een verse
     * scan/reconnect als het proces herstart). Deze functie, aangeroepen
     * vanuit een globale [Thread.UncaughtExceptionHandler] (zie
     * FclGlucoLinkApp.kt's `onCreate()`), schrijft de VOLLEDIGE stacktrace
     * weg VOORDAT het proces sterft, zodat een volgende crash wél
     * herleidbaar is.
     *
     * Bewust ONAFHANKELIJK van [enabled] (in tegenstelling tot [writeLine]):
     * een crash is precies het soort gebeurtenis waarvoor je de informatie
     * wilt hebben, ook als de gebruiker het diagnose-logboek nooit bewust
     * heeft aangezet — hier direct naar het bestand geschreven i.p.v. via
     * [writeLine].
     *
     * Bewust in een `runCatching` (net als [writeLine]): dit draait op de
     * crashende thread, vlak vóór processterminatie — een fout HIERIN mag
     * nooit de eigenlijke crash-afhandeling (het doorgeven aan de vorige
     * handler, zie FclGlucoLinkApp.kt) blokkeren of zelf een tweede,
     * verwarrende crash veroorzaken.
     */
    fun logFatal(thread: Thread, throwable: Throwable) {
        val stackTrace = runCatching {
            val writer = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(writer))
            writer.toString()
        }.getOrElse { throwable.toString() }
        android.util.Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}:\n$stackTrace")
        runCatching {
            val ctx = appContext ?: return@runCatching
            val dir = File(ctx.getExternalFilesDir(null), "log")
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return@runCatching
            val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "fclglucolink_$dateStamp.txt")
            // 24/09/2026 (editor, RONDE 182, hernoemd RONDE 205) — zie
            // writeVersionHeaderIfNeeded()'s kdoc: ook hier, voor het
            // (zeldzame) geval dat een crash het allereerste is wat dit
            // proces naar het logbestand schrijft.
            writeVersionHeaderIfNeeded(file)
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText(
                "$timeStamp [$instanceTag] === UNCAUGHT EXCEPTION on thread ${thread.name} ===\n$stackTrace\n"
            )
        }
    }
}
