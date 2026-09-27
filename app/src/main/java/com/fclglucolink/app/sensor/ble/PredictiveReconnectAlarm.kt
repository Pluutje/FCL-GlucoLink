package com.fclglucolink.app.sensor.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.fclglucolink.app.sensor.SensorSlot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * ============================================================================
 * FCLGlucoLink — voorspellende herverbind-wekker (ronde 36)
 * ============================================================================
 *
 * 04/08/2026 (editor, ronde 36 — na de vraag wat Juggluco anders doet) —
 * decompile van Juggluco's `AirGattCallback.onConnectionStateChange()`
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
 * Werking: `awaitCooldown()` (aangeroepen vanuit elke driver) plant de
 * wekker EN blijft binnen dezelfde coroutine wachten op een
 * `CompletableDeferred` die de `PredictiveReconnectAlarmReceiver` voltooit
 * zodra de wekker afgaat — dat werkt zolang het proces (de foreground
 * service) nog leeft, wat het overgrote deel van de tijd het geval is. Als
 * het proces ONDERTUSSEN toch gekild is (zeldzaam, agressieve OEM-
 * batterijbeheerders), dan bestaat deze in-memory `CompletableDeferred`
 * simpelweg niet meer in het nieuwe procesleven — daarvoor bestaat al
 * `ConnectionWatchdog` (elke 6 min, herstart de service onvoorwaardelijk
 * veilig) als vangnet; dat vangnet blijft ongewijzigd naast dit mechanisme
 * bestaan. Een defensieve `withTimeoutOrNull()`-bovengrens in
 * `awaitCooldown()` zelf (zie elke driver) zorgt er bovendien voor dat een
 * NIET-afgaande wekker (bv. gebruiker heeft "Exacte alarms" losgekoppeld op
 * API 31+) nooit erger uitpakt dan de oude situatie: na cooldownMs + 30s
 * wordt er linksom of rechtsom altijd verdergegaan.
 *
 * 26/09/2026 (editor, RONDE 192 — BUGFIX, na analyse van
 * `607307_fclglucolink_2026-09-26.txt` op verzoek: "ik denk dat de naar
 * AAPS zendende slot (dexcom g6) te veel cycli overslaat") — dit object was
 * tot deze ronde EEN gedeeld, ongekeyed geheel: ÉÉN `pendingSignal`-veld en
 * ÉÉN vaste `REQUEST_CODE`, dus ÉÉN AlarmManager-wekker in totaal, gedeeld
 * door ALLE drivers (`DexcomG6Driver.kt`, `DexcomG7Driver.kt`,
 * `CareSensAirDriver.kt` roepen allemaal dezelfde `schedule()`/`cancel()`
 * aan). De oude kdoc hierboven zegt zelf al waarom dat ooit leek te
 * kloppen: "er is maar één actieve driver-INSTANTIE per procesleven" — waar
 * dat inderdaad bij precies ÉÉN sensor-type actief. Bij Combi-gebruik
 * (twee sloten gelijktijdig, bijvoorbeeld G6+G7 — exact de situatie in het
 * meegestuurde logbestand) roepen TWEE drivers dit ONAFHANKELIJK van elkaar
 * aan, elk op hun eigen ~5-minuten-cadans: zodra de tweede driver
 * [schedule] aanroept, overschrijft die zowel [pendingSignal] als de
 * ALARMMANAGER-wekker zelf (dezelfde `PendingIntent`-identiteit via het
 * vaste `REQUEST_CODE`) — de EERSTE driver's exacte, Doze-vaste wekker gaat
 * dan nooit meer voor HEM af. Die driver valt terug op zijn eigen
 * `withTimeoutOrNull(cooldownMs + 30_000L)`-bovengrens in `awaitCooldown()`
 * — een kale coroutine-wachttijd zonder Doze-vrijstelling, precies wat
 * RONDE 36 hierboven had willen vermijden. Zodra het toestel na een tijdje
 * echt in een dieper Doze-onderhoudsvenster komt (in het logbestand: na
 * exact 75 minuten, precies het patroon dat hier al eerder als "kort →
 * oplopend" wordt beschreven) kan die kale wachttijd net lang genoeg
 * uitlopen om het ~1-minuut-scanvenster van de eigen sensor te missen —
 * volkomen stil, want geen enkel foutpad (`onScanFailed`, backoff) wordt
 * hierdoor aangeroepen, het scannen start gewoon iets te laat. Nog
 * vervelender: de "verloren" driver's eigen `cancel()` (in zijn
 * `awaitCooldown()`'s `finally`-blok, aangeroepen zodra ZIJN timeout
 * verstrijkt) annuleert daarmee de facto de wekker die op dat moment
 * eigenlijk voor de ANDERE driver bedoeld was — als die nog niet was
 * afgegaan, verliest die op zijn beurt OOK zijn Doze-vrije wekker, en valt
 * op zijn beurt terug op zijn eigen kale timeout. Dat verklaart waarom het
 * probleem, eenmaal begonnen, niet vanzelf herstelt maar voor de rest van
 * het logbestand blijft doorlopen.
 *
 * **Uitgesloten alternatieve verklaring, met cijfers.** Eerste vermoeden was
 * een radiobotsing tussen de twee sloten (G7's korte GATT-sessie net vóór
 * G6's scanvenster) — zie `GattExclusivityGate.kt`/`AapsSlotSchedule.kt`
 * voor die eerdere, WEL bevestigde bugklasse (Ronde 100/101/188). Voor DIT
 * logbestand klopt die verklaring niet: de afstand tussen G6's
 * verwachte rasterpunt en de dichtstbijzijnde G7-GATT-sessie is voor
 * GEMISTE en voor GELUKTE G6-cycli statistisch identiek (~61-63s,
 * ruim buiten `AapsSlotSchedule.MIN_SEPARATION_MS`) — als het een
 * radiobotsing was, zou je dat verschil wél zien bij de missers.
 *
 * **Fix.** [pendingSignal] wordt [pendingSignals], een `ConcurrentHashMap`
 * PER [SensorSlot] (zelfde per-slot-patroon als `AapsSlotSchedule.kt` al
 * gebruikt), en het vaste `REQUEST_CODE` wordt PER SLOT afgeleid
 * (`REQUEST_CODE_BASE + slot.ordinal`) — dus een eigen, onafhankelijke
 * `PendingIntent`-identiteit en dus een eigen AlarmManager-wekker per slot.
 * Slot A en slot B kunnen elkaars wekker niet meer overschrijven of
 * annuleren, ongeacht welke twee sensor-types er in draaien. De
 * `PredictiveReconnectAlarmReceiver` krijgt het slot mee als Intent-extra
 * (`EXTRA_SLOT_ORDINAL`) zodat hij weet welk van de (nu twee) signalen hij
 * moet voltooien.
 */
object PredictiveReconnectAlarm {

    private const val REQUEST_CODE_BASE = 4211

    // 26/09/2026 (editor, RONDE 192) — één signaal PER SLOT i.p.v. één
    // gedeeld signaal, zie klasse-kdoc hierboven voor het volledige "waarom".
    // Geen @Volatile nodig (dat vereist een `var`, niet een `val`) — de kaart
    // zelf (ConcurrentHashMap) is al thread-safe voor gelijktijdige
    // put/remove-aanroepen vanuit meerdere sloten.
    private val pendingSignals = ConcurrentHashMap<SensorSlot, CompletableDeferred<Unit>>()

    private fun requestCodeFor(slot: SensorSlot): Int = REQUEST_CODE_BASE + slot.ordinal

    private fun pendingIntent(context: Context, slot: SensorSlot): PendingIntent {
        val intent = Intent(context, PredictiveReconnectAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT_ORDINAL, slot.ordinal)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Plant de wekker EN registreert een nieuw signaal — geeft dat signaal
     *  terug zodat de aanroeper er meteen op kan wachten. Overschrijft een
     *  eventueel nog openstaand vorig signaal VAN DITZELFDE [slot] bewust
     *  (er kan maar één actieve cooldown per slot tegelijk zijn, zie
     *  `scheduleScanAttempt()`'s `reconnectJob?.cancel()`) — een signaal
     *  van het ANDERE slot blijft, sinds RONDE 192, gewoon ongemoeid. */
    fun schedule(context: Context, slot: SensorSlot, cooldownMs: Long): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        pendingSignals[slot] = deferred
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            // Geen AlarmManager beschikbaar (zou nooit moeten gebeuren) —
            // laat awaitCooldown()'s eigen withTimeoutOrNull()-bovengrens
            // het overnemen.
            return deferred
        }
        val triggerAtElapsed = SystemClock.elapsedRealtime() + cooldownMs
        val pi = pendingIntent(context, slot)
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

    /** Aanroepen zodra het wachten voor [slot] voorbij is (op welke manier
     *  dan ook — wekker afgegaan, of de defensieve timeout in
     *  awaitCooldown()) zodat een inmiddels overbodige wekker niet alsnog
     *  een tweede scanpoging triggert bovenop de net al gestarte. Raakt,
     *  sinds RONDE 192, uitsluitend [slot]'s EIGEN wekker/signaal — nooit
     *  meer die van het andere slot. */
    fun cancel(context: Context, slot: SensorSlot) {
        pendingSignals.remove(slot)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(pendingIntent(context, slot)) }
    }

    /** Aangeroepen door PredictiveReconnectAlarmReceiver zodra de wekker
     *  voor [slotOrdinal] daadwerkelijk afgaat. */
    fun onFired(slotOrdinal: Int) {
        val slot = SensorSlot.entries.getOrNull(slotOrdinal) ?: return
        pendingSignals.remove(slot)?.complete(Unit)
    }
}

// 26/09/2026 (editor, RONDE 192) — file-private i.p.v. binnen het object,
// zodat zowel [PredictiveReconnectAlarm] als [PredictiveReconnectAlarmReceiver]
// (allebei in dit bestand) de sleutel kunnen gebruiken zonder een publieke
// constante op het object te hoeven zetten.
private const val EXTRA_SLOT_ORDINAL = "slotOrdinal"

/**
 * 04/08/2026 (editor, ronde 36) — de daadwerkelijke "tik": voltooit simpelweg
 * het openstaande signaal (sinds RONDE 192: van het juiste SLOT, meegegeven
 * als Intent-extra) zodat de wachtende coroutine in de aanroepende driver's
 * `awaitCooldown()` meteen verdergaat. Bewust GEEN eigen scan-/
 * verbindingslogica hier — die staat al in de drivers zelf en blijft daar;
 * deze ontvanger is puur de Doze-doorbrekende "wektik".
 */
class PredictiveReconnectAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slotOrdinal = intent.getIntExtra(EXTRA_SLOT_ORDINAL, -1)
        if (slotOrdinal >= 0) {
            PredictiveReconnectAlarm.onFired(slotOrdinal)
        }
    }
}
