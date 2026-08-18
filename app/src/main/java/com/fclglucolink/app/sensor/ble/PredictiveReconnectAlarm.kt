package com.fclglucolink.app.sensor.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred

/**
 * ============================================================================
 * FCLGlucoLink — voorspellende herverbind-wekker (ronde 36)
 * ============================================================================
 *
 * 04/08/2026 (editor, ronde 36 — op verzoek, na de vraag "wat doet juggluco
 * dan anders") — decompile van Juggluco's `AirGattCallback.onConnectionStateChange()`
 * (dex-variant, de bevestigde CareSens Air/Sibionics-klasse) liet twee paden
 * zien voor het inplannen van de volgende scan na een disconnect:
 *
 *  1. STANDAARD (de gebruiker heeft dit niet bewust aangezet, dus dit is
 *     wat bij hem draait): `connectToActiveDevice(this, 0)` — meteen
 *     opnieuw scannen, delay=0, geen enkele lange getimede sleep.
 *  2. OPTIONELE "alarm clock"-instelling (`getalarmclock()`): berekent de
 *     resterende tijd tot de voorspelde ~5-minuten-meting en plant een
 *     `AlarmManager.setAlarmClock()`-wekker (`DexGattCallback.setalarm()`,
 *     hergebruikt door AirGattCallback) — de zwaarste Doze-vrijstelling die
 *     Android kent.
 *
 * Onze eigen `computeReconnectCooldownMs()` (ronde 31/32) doet iets wat op
 * pad 2 lijkt qua BEDOELING (voorspel de volgende meting, wacht dan pas),
 * maar gebruikte tot nu toe een kale coroutine-`delay()` om die wachttijd
 * te overbruggen — GEEN Doze-vrijstelling. Dat is vermoedelijk precies de
 * verklaring voor het trimodale vertragingspatroon (25-32s / 88-90s /
 * 148-270s) uit de ronde-35-logbestand-data: Android's Doze-onderhouds-
 * vensters verschuiven precies zo (kort → oplopend), en een kale delay()
 * heeft geen enkele garantie om daar doorheen te breken, ook niet vanuit
 * een foreground service (die garandeert alleen dat het PROCES blijft
 * leven, niet dat een timer-callback op tijd afgaat).
 *
 * Dit object plant dus, i.p.v. Juggluco's `setAlarmClock()` letterlijk te
 * kopiëren (dat toont een permanent wekker-icoontje in de statusbalk — een
 * zichtbare bijwerking die niet past bij een op de achtergrond draaiende
 * sensor-app), een `setExactAndAllowWhileIdle()`-wekker: net iets minder
 * zwaar dan `setAlarmClock()`, maar nog altijd expliciet ONTWORPEN om
 * Doze/App Standby te doorbreken, zonder de statusbalk-bijwerking.
 *
 * Belangrijk verschil met `ConnectionWatchdog.kt` (ronde 28): die is een
 * generiek, herhalend "leeft het proces nog?"-veiligheidsnet elke 6
 * minuten. Dit hier is het PRIMAIRE, precies-getimede mechanisme dat de
 * daadwerkelijke eerstvolgende scanpoging inplant — vervangt dus de
 * `delay(cooldownMs)` in `scheduleScanAttempt()`, niet een aanvulling erop.
 *
 * Werking: `awaitCooldown()` (aangeroepen vanuit CareSensAirDriver.kt)
 * plant de wekker EN blijft binnen dezelfde coroutine wachten op een
 * `CompletableDeferred` die de `PredictiveReconnectAlarmReceiver` voltooit
 * zodra de wekker afgaat — dat werkt zolang het proces (de foreground
 * service) nog leeft, wat het overgrote deel van de tijd het geval is. Als
 * het proces ONDERTUSSEN toch gekild is (zeldzaam, agressieve OEM-
 * batterijbeheerders), dan bestaat deze in-memory `CompletableDeferred`
 * simpelweg niet meer in het nieuwe procesleven — daarvoor bestaat al
 * `ConnectionWatchdog` (elke 6 min, herstart de service onvoorwaardelijk
 * veilig) als vangnet; dat vangnet blijft ongewijzigd naast dit mechanisme
 * bestaan. Een defensieve `withTimeoutOrNull()`-bovengrens in
 * `awaitCooldown()` zelf (zie CareSensAirDriver.kt) zorgt er bovendien voor
 * dat een NIET-afgaande wekker (bv. gebruiker heeft "Exacte alarms"
 * losgekoppeld op API 31+) nooit erger uitpakt dan de oude situatie: na
 * cooldownMs + 30s wordt er linksom of rechtsom altijd verdergegaan.
 */
object PredictiveReconnectAlarm {

    private const val REQUEST_CODE = 4211

    // 04/08/2026 (editor) — enige in-memory koppeling tussen de
    // BroadcastReceiver (kan in theorie in een ander onderdeel van
    // hetzelfde proces afgaan) en de wachtende coroutine in
    // CareSensAirDriver.kt. Er is maar één actieve driver-instantie per
    // procesleven (zie BleConnectionService's singleton-gebruik), dus één
    // los veld hier is voldoende — geen aparte registratie-/afmeld-API
    // nodig zoals bij een echte listener-lijst.
    @Volatile
    private var pendingSignal: CompletableDeferred<Unit>? = null

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PredictiveReconnectAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Plant de wekker EN registreert een nieuw signaal — geeft dat signaal
     *  terug zodat de aanroeper er meteen op kan wachten. Overschrijft een
     *  eventueel nog openstaand vorig signaal bewust (er kan maar één
     *  actieve cooldown tegelijk zijn, zie `scheduleScanAttempt()`'s
     *  `reconnectJob?.cancel()`). */
    fun schedule(context: Context, cooldownMs: Long): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        pendingSignal = deferred
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            // Geen AlarmManager beschikbaar (zou nooit moeten gebeuren) —
            // laat awaitCooldown()'s eigen withTimeoutOrNull()-bovengrens
            // het overnemen.
            return deferred
        }
        val triggerAtElapsed = SystemClock.elapsedRealtime() + cooldownMs
        val pi = pendingIntent(context)
        // Zelfde API-31+-nuance als ConnectionWatchdog.schedule(): een
        // gebruiker kan exacte alarms via Instellingen intrekken — val dan
        // terug op de inexacte, nog altijd Doze-doorbrekende variant i.p.v.
        // te crashen.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
            }
        }
        return deferred
    }

    /** Aanroepen zodra het wachten voorbij is (op welke manier dan ook —
     *  wekker afgegaan, of de defensieve timeout in awaitCooldown()) zodat
     *  een inmiddels overbodige wekker niet alsnog een tweede scanpoging
     *  triggert bovenop de net al gestarte. */
    fun cancel(context: Context) {
        pendingSignal = null
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    /** Aangeroepen door PredictiveReconnectAlarmReceiver zodra de wekker
     *  daadwerkelijk afgaat. */
    fun onFired() {
        pendingSignal?.complete(Unit)
    }
}

/**
 * 04/08/2026 (editor, ronde 36) — de daadwerkelijke "tik": voltooit simpelweg
 * het openstaande signaal zodat de wachtende coroutine in
 * CareSensAirDriver.kt's `awaitCooldown()` meteen verdergaat. Bewust GEEN
 * eigen scan-/verbindingslogica hier — die staat al in CareSensAirDriver.kt
 * en blijft daar; deze ontvanger is puur de Doze-doorbrekende "wektik".
 */
class PredictiveReconnectAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PredictiveReconnectAlarm.onFired()
    }
}
