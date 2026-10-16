package com.fclglucolink.app.sensor.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.fclglucolink.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FCLGlucoLink — verbindings-"wekker" (AlarmManager-veiligheidsnet)
 * ============================================================================
 *
 * 03/08/2026 (editor, ronde 28 — na drie opeenvolgende builds (v73/v74/v75)
 * die het screen-off-vertragingsprobleem NIET oplosten, ondanks steeds
 * betere scan-pacing/scanmodus-mirroring van Juggluco's GATT/scan-gedrag)
 * — op verzoek van de gebruiker zijn de drie ECHTE manifesten (AAPS,
 * Juggluco, FCLGlucoLink) naast elkaar gelegd. Daaruit kwam een verschil
 * naar boven dat helemaal niets met scannen te maken heeft: Juggluco's
 * manifest bevat `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`
 * (maxSdkVersion 32) ÉN `USE_EXACT_ALARM` (voor API 33+), plus losse
 * `<receiver>`-declaraties genaamd `.Maintenance`, `.LossOfSensorAlarm`,
 * `.ConnectReceiver` en een boot-receiver `.NumAlarm` — stuk voor stuk
 * aanwijzingen voor een AlarmManager-gebaseerde "wekker" die Juggluco's
 * eigen verbindingslogica periodiek een garantie-tik geeft, LOS van de
 * gewone `Service`-levenscyclus.
 *
 * Dat verschil is belangrijk omdat het een heel andere categorie
 * betrouwbaarheid is dan alles wat tot nu toe geprobeerd is. Onze eigen
 * herstelmechanismen (`ScanRateLimiter`, `scheduleRearm()` in
 * CareSensAirDriver.kt, de `statusTickerJob`) zijn allemaal gewone
 * coroutine-`delay()`-timers, die AFHANKELIJK zijn van (a) dat het proces
 * zelf nog leeft, en (b) dat de CPU niet dieper in slaap zit dan de
 * PARTIAL_WAKE_LOCK in BleConnectionService.kt toelaat. Een `AlarmManager`-
 * wekker met `setExactAndAllowWhileIdle()` is daarentegen een OS-garantie:
 * die is expliciet ONTWORPEN om Doze/App Standby te doorbreken, ongeacht
 * wat er in het app-proces zelf gebeurt — inclusief het proces zelf weer
 * OPSTARTEN als Android het ondertussen volledig heeft gestopt (iets waar
 * `START_STICKY` geen harde garantie voor geeft, alleen een verzoek waar
 * Android zelf de timing van bepaalt).
 *
 * Dit object plant dus een herhalende, zichzelf-verlengende "tik" — elke
 * keer dat de wekker afgaat (`ConnectionWatchdogReceiver.onReceive()`),
 * wordt meteen de VOLGENDE ingepland (self-perpetuating chain, want een
 * exacte alarm is standaard eenmalig) én wordt `BleConnectionService`
 * opnieuw gestart. Die herstart is bewust ONVOORWAARDELIJK veilig: de
 * bestaande `onStartCommand()`-logica in BleConnectionService.kt herkent
 * een al werkende driver (`stillWorking`-check) en doet dan gewoon niets —
 * deze wekker kan dus nooit een gezonde, actieve verbinding verstoren,
 * alleen een gestopt/vastgelopen proces een garantie-duwtje geven.
 *
 * Bewust gekoppeld aan `startBleConnectionService()`/`stopBleConnectionService()`
 * in MainActivity.kt (expliciete gebruikersintentie), NIET aan
 * `BleConnectionService.onCreate()`/`onDestroy()` — die laatste twee vuren
 * namelijk OOK als Android de service zelf killt (precies het scenario
 * waarin we deze wekker juist actief willen houden om te herstellen), dus
 * daaraan koppelen zou 'm precies op het verkeerde moment kunnen annuleren.
 */
object ConnectionWatchdog {

    // 03/08/2026 (editor) — iets korter dan Juggluco's eigen 390s (zie
    // SCAN_REARM_INTERVAL_MS's kdoc in CareSensAirDriver.kt) zodat deze
    // wekker, als er iets grondig mis is (proces gestopt), altijd eerder
    // afgaat dan CareSensAirDriver's eigen interne veiligheidsnet dat zou
    // doen als het proces nog wél leefde.
    private const val INTERVAL_MS = 6 * 60_000L // 6 minuten
    private const val REQUEST_CODE = 4210

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConnectionWatchdogReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Aanroepen zodra de gebruiker een verbinding wil (zie
     *  startBleConnectionService() in MainActivity.kt) — idempotent, mag
     *  gerust vaker dan nodig aangeroepen worden. */
    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtElapsed = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = pendingIntent(context)
        // 03/08/2026 (editor) — vanaf Android 12 (API 31) moet expliciet
        // gecontroleerd worden of exacte alarms nog toegestaan zijn
        // (canScheduleExactAlarms()) — normaal automatisch toegestaan,
        // maar een gebruiker kan dit via Instellingen > Alarmen en
        // herinneringen alsnog intrekken. Bij twijfel gewoon de inexacte
        // variant (nog steeds Doze-doorbrekend via ...AllowWhileIdle, alleen
        // zonder harde tijdsgarantie) — nooit crashen op deze aanroep.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            }
        }
    }

    /** Aanroepen bij een expliciete gebruikers-disconnect (zie
     *  stopBleConnectionService() in MainActivity.kt) — zonder dit zou de
     *  wekker de zojuist bewust gestopte service steeds weer opnieuw
     *  opstarten. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }
}

/**
 * 03/08/2026 (editor) — de daadwerkelijke "tik": plant zichzelf meteen
 * opnieuw in (zie ConnectionWatchdog's kdoc — een exact alarm is
 * eenmalig, dus zonder deze regel zou er maar één herstelpoging ooit
 * plaatsvinden) en herstart dan `BleConnectionService` — onvoorwaardelijk
 * veilig, zie de kdoc bij `ConnectionWatchdog` hierboven.
 */
class ConnectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        ConnectionWatchdog.schedule(appCtx)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val settings = AppSettings(appCtx)
                // 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) —
                // was een enkele selectedSensor/deviceAddress-check; nu "is
                // er ergens (Slot A of B) iets geconfigureerd" (zie
                // AppSettings.hasAnySlotConfigured()'s kdoc) — anders zou
                // deze wekker een compleet ongeconfigureerde installatie
                // onnodig uit zijn slaap halen. BleConnectionService verwerkt
                // beide slots zelf onafhankelijk zodra 'm start.
                if (settings.hasAnySlotConfigured()) {
                    val serviceIntent = Intent(appCtx, BleConnectionService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(serviceIntent)
                    } else {
                        appCtx.startService(serviceIntent)
                    }
                }
            }
            pending.finish()
        }
    }
}

/**
 * 03/08/2026 (editor) — mirror van Juggluco's `.NumAlarm`
 * (BOOT_COMPLETED/QUICKBOOT_POWERON-ontvanger): zonder dit blijft
 * FCLGlucoLink na een telefoon-herstart stil staan totdat de gebruiker de
 * app zelf weer opent — een echt gat t.o.v. Juggluco dat tot nu toe nooit
 * apart gerapporteerd is (waarschijnlijk omdat een herstart zelden
 * samenviel met een testperiode), maar wel exact hetzelfde soort
 * "onbeheerd 15 dagen door moeten draaien"-eis raakt als de rest van dit
 * bestand.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                // 10/08/2026 (editor, RONDE 79) — zie kdoc bij
                // ConnectionWatchdogReceiver hierboven: zelfde
                // hasAnySlotConfigured()-check.
                val settings = AppSettings(appCtx)
                if (settings.hasAnySlotConfigured()) {
                    ConnectionWatchdog.schedule(appCtx)
                    val serviceIntent = Intent(appCtx, BleConnectionService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(serviceIntent)
                    } else {
                        appCtx.startService(serviceIntent)
                    }
                }
            }
            pending.finish()
        }
    }
}
