package com.fclglucolink.app.sensor.ble

import com.fclglucolink.app.sensor.SensorSlot
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * FCLGlucoLink — gedeelde "wijk voor de andere slot"-planning
 * ============================================================================
 *
 * 12/08/2026 (editor, RONDE 100 — op verzoek, na analyse van
 * `fclglucolink_2026-08-12.txt`: "wat ik wel belangrijk vind is dat het slot
 * wat naar aaps zend ... altijd de voorkeur heeft en als dat tot gevolg heeft
 * dat het andere slot zo nu en dan een meting mist dan is dat maar zo.
 * Uiteraard moet als er maar 1 slot actief is dat ene slot ook streven naar
 * 100% betrouwbaarheid.").
 *
 * **Waarom dit BOVENOP Ronde 83's `ScanRateLimiter`-voorrang nodig is.**
 * Ronde 83 loste alleen het GEDEELDE 5-scans-per-31s software-budget op. De
 * `fclglucolink_2026-08-12.txt`-log liet 4 Dexcom-missers zien die stuk voor
 * stuk samenvielen (binnen ~1-60s) met een CareSens-scanpoging, terwijl het
 * gedeelde budget daarbij nooit vol zat — Ronde 83's mechanisme greep dus
 * niet in. De botsing zit dieper (BLE-radio/GATT-resourcecontentie op het
 * moment zelf), niet in het teller-budget.
 *
 * 13/08/2026 (editor, RONDE 101 — BUGFIX + op voorstel na
 * `fclglucolink_2026-08-13 08.30.txt`: "is het dan geen optie om ... de
 * timing van de caresens zo te verschuiven dat hij minimaal 1 minuut voor of
 * na de door de transmitter bepaalde update van de dexcom valt ... de
 * caresens [kan] worden uitgevraagd wanneer je dat wilt en ... de dexcom is
 * alleen aan het zenden als de transmitter zich zelf opent").
 *
 * **Gevonden bug.** [publishedSlot]/[predictedReadingAtMs] waren tot deze
 * ronde EEN gedeeld, overschrijfbaar paar — niet per slot. Omdat BEIDE
 * drivers onvoorwaardelijk publiceren (zie [publish]'s kdoc), overschreef
 * elke driver's EIGEN publicatie meestal zijn eigen vorige waarde vlak
 * voordat diezelfde driver zijn eigen [guardDelayMs]-check deed — met als
 * gevolg dat de check zichzelf zag (`other == callerSlot`) en altijd 0
 * opleverde. De `fclglucolink_2026-08-13 08.30.txt`-log bevestigt dit: 0
 * "wijk ... uit"-logregels, terwijl er wél 5 Dexcom-missers waren, elke keer
 * een CareSens-scan nog geen 5 SECONDEN vóór Dexcom's verwachte
 * metingstijd. Fix: [predictedReadingAtMs] is nu een map PER slot (2
 * sloten, dus altijd eenduidig welke waarde bij "de andere slot" hoort).
 *
 * **Twee aanvullende mechanismen, samen.**
 * 1. (Reactief, Ronde 100, nu bug-fixed) [guardDelayMs] — de NIET-priority-
 *    slot wijkt vlak vóór een eigen scanpoging uit als de AAPS-slot's
 *    voorspelde tijd toevallig al heel dichtbij is. Blijft bestaan als
 *    laatste vangnet (dekt ook het geval dat de AAPS-bron ooit naar
 *    CareSens Air wordt omgezet).
 * 2. (Proactief, Ronde 101, NIEUW — het eigenlijke, robuustere voorstel)
 *    CareSensAirDriver.kt's `computeReconnectCooldownMs()` gebruikt
 *    [otherSlotPredictedReadingAtMs] om zijn EIGEN scan-DOELtijd (niet de
 *    onderliggende cadans-voorspelling — die blijft ongemoeid, zie dat
 *    bestand) desnoods een stukje later te plannen, zodat hij nooit binnen
 *    [MIN_SEPARATION_MS] van Dexcom's rasterpunt uitkomt. Alleen op
 *    CareSensAirDriver.kt toegepast — Dexcom's cadans ligt vast in de
 *    transmitter zelf (niet door de app te sturen), CareSens Air's cadans
 *    wordt elke keer opnieuw berekend vanaf zijn eigen laatste geslaagde
 *    meting en kan dus wél naar een later moment binnen zijn eigen
 *    ~5-minuten-venster geschoven worden zonder de meting te verliezen (het
 *    apparaat blijft de laatste meting bewaren tot de VOLGENDE binnenkomt).
 *
 * **Waarom dit vanzelf goed valt bij maar 1 actieve slot.** Als de andere
 * driver niet draait, publiceert die ook nooit — [otherSlotPredictedReadingAtMs]
 * levert dan `null`, dus zowel [guardDelayMs] als CareSensAirDriver.kt's
 * proactieve verschuiving doen niets. Geen enkele extra wachttijd voor de
 * enige actieve slot.
 *
 * **Zelf-opruimend.** Een verlopen (stokoude) publicatie van een
 * inmiddels-gestopte andere slot levert bij [guardDelayMs] vanzelf 0 op
 * zodra `nowMs` buiten het venster valt — geen aparte "is dit nog
 * vers"-boekhouding nodig. CareSensAirDriver.kt's grid-snap-logica
 * (`Math.round(...)`) is om dezelfde reden ongevoelig voor een stokoude
 * absolute waarde: die snapt altijd naar het DICHTSTBIJZIJNDE veelvoud
 * vanaf die waarde, dus een oud tijdstip geeft nog steeds een correct
 * "huidig" rasterpunt zolang het onderliggende ritme (elke
 * `SENSOR_PERIOD_MS`) nog klopt.
 */
object AapsSlotSchedule {
    private val predictedReadingAtMs = ConcurrentHashMap<SensorSlot, Long>()

    /** Minimale afstand die CareSensAirDriver.kt's proactieve verschuiving
     *  aanhoudt tot Dexcom's rasterpunt ("minimaal 1 minuut voor of na",
     *  letterlijk gebruikersvoorstel). Ook hergebruikt als [guardDelayMs]'s
     *  reactieve beschermvenster, voor consistentie. */
    const val MIN_SEPARATION_MS = 60_000L

    // 13/08/2026 (editor, RONDE 103 — op controlevraag: "als de caresens de
    // aaps sensor wordt dan wordt [de verschuiving] ook uitgeschakeld en
    // krijgt caresens wel altijd de voorrang (in dat laatste geval is het
    // namelijk niet belangrijk dat de dexcom zo nu en dan even een cyclus
    // overslaat want er wordt toch niet op gedoseerd)") — Ronde 101's
    // proactieve verschuiving in CareSensAirDriver.kt was ONVOORWAARDELIJK
    // (elke keer weg van "de andere slot", ongeacht wie de AAPS-slot is) —
    // dat klopte dus NIET meer zodra CareSens Air zelf de AAPS-slot wordt.
    // `computeReconnectCooldownMs()` (waar de verschuiving gebeurt) is zelf
    // GEEN suspend-functie en wordt bovendien vanuit twee plekken aangeroepen
    // die zelf ook geen suspend-context zijn (BLE-callback-methodes:
    // `onScanFailed`/`onConnectionStateChange`), dus kan daar niet zomaar
    // even vers `settings.aapsActiveSlot.first()` (een suspend Flow-lezing)
    // opvragen. In plaats daarvan cachet [publishAapsActiveSlot] de waarde
    // die `scheduleScanAttempt()` (WEL een suspend-coroutine) toch al bij
    // elke scanpoging vers ophaalt (zie Ronde 83) — dus op zijn laatst één
    // scancyclus (~5 min) achter de waarheid aan, ruim vers genoeg voor deze
    // instelling die de gebruiker niet elke minuut zal wijzigen.
    @Volatile private var cachedAapsActiveSlot: SensorSlot? = null

    /** Aangeroepen vanuit beide drivers' `scheduleScanAttempt()`, meteen
     *  nadat die zelf al vers `settings.aapsActiveSlot.first()` heeft
     *  opgehaald voor de eigen `isPriority`-check — hier gewoon hergebruikt
     *  i.p.v. nogmaals gelezen. [slot] is nullable: `AppSettings.aapsActiveSlot`
     *  is zelf `Flow<SensorSlot?>` (nog geen keuze gemaakt is een geldige
     *  toestand) — `null` doorgeven maakt de cache leeg, waarna
     *  [isPrioritySlot] voor BEIDE sloten `false` oplevert (de veilige kant,
     *  zie die functie's kdoc). */
    fun publishAapsActiveSlot(slot: SensorSlot?) {
        cachedAapsActiveSlot = slot
    }

    /** Is [slot], voor zover laatst bekend, de AAPS-actieve slot? Bij een
     *  nog lege cache (vlak na app-start, vóór de eerste scanpoging van
     *  welke driver dan ook) levert dit `false` op voor BEIDE sloten — de
     *  veilige kant, want dat laat CareSensAirDriver.kt's proactieve
     *  verschuiving gewoon actief (wijk voor de ander), i.p.v. per ongeluk
     *  te denken dat CareSens Air al priority is terwijl dat nog niet vast-
     *  gesteld is. */
    fun isPrioritySlot(slot: SensorSlot): Boolean = cachedAapsActiveSlot == slot

    /** Onvoorwaardelijk aangeroepen door `computeReconnectCooldownMs()` in
     *  beide drivers, ongeacht of [slot] op dit moment de AAPS-slot is —
     *  scheelt een suspend/Flow-lezing op een plek die zelf niet suspend is.
     *  De priority-/wie-wijkt-voor-wie-logica zit aan de LEZERS-kant (zie
     *  [guardDelayMs] en CareSensAirDriver.kt). */
    fun publish(slot: SensorSlot, predictedNextReadingAtMs: Long) {
        predictedReadingAtMs[slot] = predictedNextReadingAtMs
    }

    /** De laatst gepubliceerde voorspelde volgende-metingstijd van de ANDERE
     *  slot (bij 2 sloten totaal ondubbelzinnig), of `null` als die nooit
     *  gepubliceerd heeft — of niet meer, zie [clear]. */
    fun otherSlotPredictedReadingAtMs(callerSlot: SensorSlot): Long? =
        predictedReadingAtMs.entries.firstOrNull { it.key != callerSlot }?.value

    /**
     * 13/08/2026 (editor, RONDE 102 — op controlevraag: "als alleen de
     * caresens actief is dan wordt die niet verschoven neem ik aan en blijft
     * die gewoon netjes iedere 5 minuten een waarde produceren") — klopt
     * ALLEEN als de andere slot deze sessie nooit gepubliceerd heeft. Zonder
     * deze functie bleef een publicatie van een slot die de gebruiker
     * TUSSENTIJDS stopt (bijv. van dual-slot terug naar alleen CareSens Air,
     * zonder de app te herstarten) gewoon in de map staan — [guardDelayMs]
     * ruimt een verlopen publicatie vanzelf op via zijn tijdvenster-check,
     * maar CareSensAirDriver.kt's proactieve rasterverschuiving (RONDE 101)
     * snapt via modulo-rekenen naar het DICHTSTBIJZIJNDE veelvoud, dus zou
     * een stokoude publicatie van een allang gestopte slot voor altijd
     * blijven volgen totdat de app herstart. Aangeroepen vanuit beide
     * drivers' `disconnect()` — dat is specifiek het EXPLICIETE-stop-pad
     * (`userStopped = true`), niet de gewone tussentijdse reconnect-cyclus
     * (die blijft gewoon publiceren, zie `computeReconnectCooldownMs()`).
     */
    fun clear(slot: SensorSlot) {
        predictedReadingAtMs.remove(slot)
    }

    /**
     * Door de NIET-priority-slot vlak vóór een eigen scanpoging aan te
     * roepen (dus alleen nadat `isPriority == false` is vastgesteld). Levert
     * 0 op als er niets te wijken valt; anders het aantal ms wachten tot na
     * afloop van de andere slot's beschermde meetvenster.
     */
    fun guardDelayMs(callerSlot: SensorSlot, nowMs: Long): Long {
        val target = otherSlotPredictedReadingAtMs(callerSlot) ?: return 0L
        val guardStart = target - MIN_SEPARATION_MS
        val guardEnd = target + MIN_SEPARATION_MS
        if (nowMs < guardStart || nowMs > guardEnd) return 0L
        return guardEnd - nowMs
    }
}
