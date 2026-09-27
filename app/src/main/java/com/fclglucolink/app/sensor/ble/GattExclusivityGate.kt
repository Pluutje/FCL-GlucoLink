package com.fclglucolink.app.sensor.ble

import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.sensor.SensorSlot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ============================================================================
 * FCLGlucoLink — wederzijdse uitsluiting van ACTIEVE GATT-verbindingen
 * tussen de twee sloten
 * ============================================================================
 *
 * 25/09/2026 (editor, RONDE 187-vervolg, op verzoek na een meegestuurd
 * logbestand met Dexcom G6 (slot A, AAPS-actief) en Dexcom G7
 * (slot B) gelijktijdig actief op de hoofdtelefoon) — dat logbestand toonde
 * G7's sessiesleutel-hergebruik ÉLKE keer mislukken (het uitdaging-bewijs
 * klopte nooit) en een volledige handshake die wél slaagde (authenticatie +
 * bonding) maar daarna vrijwel altijd met status=19 (sensor breekt zelf af)
 * eindigde vóórdat er een meting binnenkwam — in datzelfde venster deed G6
 * zijn eigen normale scan/connect/lees-cyclus. G7's protocol staat al sinds
 * de vroegste rondes (zie DexcomG7Driver.kt's vele kdoc's, o.a. Ronde
 * 141-146) bekend als extreem timing-gevoelig — de sensor breekt zelf af
 * binnen ~200ms als een stap niet exact op tijd/in de juiste volgorde komt.
 * Twee gelijktijdige GATT-sessies op DEZELFDE fysieke Bluetooth-radio is de
 * meest voor de hand liggende verklaring voor deze nieuwe, dual-sensor-
 * specifieke storing — ScanRateLimiter (Ronde 55/83) en
 * AapsSlotSchedule.guardDelayMs (Ronde 100/101) beschermen allebei alleen
 * het moment van een SCAN-start, niet de duur van een al lopende, actieve
 * GATT-verbinding.
 *
 * **De gebruiker's eigen regel (letterlijk, al vastgesteld bij de introductie van
 * de 2-sloten-architectuur, Ronde 79 — nu voor het eerst ook daadwerkelijk
 * afgedwongen):** "de sensor die naar aaps zendt moet altijd voorrang
 * krijgen... in het geval de G7 niet kan wachten omdat hij anders niks meer
 * krijgt mag die voorrang [nemen], mits de G6 dan maar maximaal 1 minuut
 * later verbinding heeft en nooit 5 minuten hoeft te wachten op een andere
 * sensor."
 *
 * **Ontwerp — bewust simpel, GEEN prioriteitswachtrij in deze klasse zelf.**
 * [acquire] is een tijdgebonden mutex: wacht ten hoogste [MAX_WAIT_MS] (60s
 * — dezelfde grens als [AapsSlotSchedule.MIN_SEPARATION_MS], de gebruiker's
 * eigen "maximaal 1 minuut") op de andere slot. Lukt het niet binnen die tijd, dan
 * gaat de aanroeper ALSNOG door, zonder de lock — een korte overlap tussen
 * twee GATT-sessies is in de praktijk altijd beter dan een volledig gemiste
 * cyclus (en dus nooit de volle 5 minuten wachten), precies zoals gevraagd.
 * Deze grens geldt symmetrisch voor BEIDE sloten — dat is bewust: een echte
 * prioriteitswachtrij (die de niet-priority slot voor onbepaalde tijd zou
 * kunnen laten wachten zolang de priority-slot maar vaak genoeg opnieuw
 * aanvraagt) zou zelf weer een nieuwe manier zijn om G7 te laten
 * verhongeren.
 *
 * De VOORRANG voor de AAPS-actieve slot wordt dus niet in deze klasse
 * afgedwongen, maar zit al vóór deze klasse: de niet-priority driver roept
 * [acquire] pas aan NADAT hij via de bestaande [AapsSlotSchedule.guardDelayMs]
 * al heeft uitgeweken voor de priority-slot se eigen verwachte metingvenster
 * (zie `scheduleScanAttempt()` in beide drivers, ongewijzigd) — in de
 * praktijk wint de priority-slot daardoor bijna altijd de race om deze
 * lock, zonder dat [GattExclusivityGate] zelf enige kennis van "wie is
 * priority" nodig heeft. Alleen als de niet-priority slot zijn eigen
 * moment allang bereikt heeft (dus zelf niet langer kan wachten, anders
 * mist hij deze cyclus helemaal) roept hij [acquire] sowieso aan, en die
 * krijgt dan hoogstens [MAX_WAIT_MS] extra vertraging bovenop wat hij toch
 * al zou wachten.
 *
 * **Reikwijdte: alleen de daadwerkelijke GATT-verbinding, niet het
 * scannen.** [acquire] wordt pas aangeroepen vlak vóór `connectGatt()`
 * (nadat de scan het toestel al gevonden heeft) — scannen zelf mag voor
 * beide sloten gewoon blijven overlappen (dat regelt [ScanRateLimiter] al
 * apart, en scannen zelf gebruikt niet dezelfde radio-resources als een
 * actieve GATT-sessie met doorlopende data-uitwisseling).
 */
object GattExclusivityGate {
    private val mutex = Mutex()

    @Volatile
    private var heldBy: SensorSlot? = null

    /** Zelfde grens als [AapsSlotSchedule.MIN_SEPARATION_MS] — zie
     *  klasse-kdoc: de gebruiker's eigen "maximaal 1 minuut". */
    const val MAX_WAIT_MS = AapsSlotSchedule.MIN_SEPARATION_MS

    /**
     * Wacht tot [slot] als enige een GATT-verbinding mag openen, ten
     * hoogste [MAX_WAIT_MS] — daarna gaat de aanroeper ALSNOG door (zie
     * klasse-kdoc). Roep [release] onvoorwaardelijk aan zodra de
     * verbinding volledig is afgesloten (ongeacht of dit `true` of `false`
     * teruggaf, en ongeacht de reden) — [release] controleert zelf of
     * [slot] daadwerkelijk de huidige houder is, dus een overbodige aanroep
     * is altijd veilig.
     */
    suspend fun acquire(slot: SensorSlot): Boolean {
        val acquired = withTimeoutOrNull(MAX_WAIT_MS) { mutex.lock() } != null
        if (acquired) {
            heldBy = slot
        } else {
            DiagnosticFileLogger.log(
                "GattExclusivityGate: slot $slot kreeg de GATT-exclusiviteit niet binnen ${MAX_WAIT_MS}ms (andere slot nog bezig) — gaat door zonder exclusiviteit"
            )
        }
        return acquired
    }

    /** Veilig aan te roepen ongeacht of [acquire] eerder `true` of `false`
     *  gaf, en ongeacht hoe vaak — doet alleen iets als [slot] op dit
     *  moment daadwerkelijk de houder is. */
    fun release(slot: SensorSlot) {
        if (heldBy == slot) {
            heldBy = null
            if (mutex.isLocked) {
                runCatching { mutex.unlock() }
            }
        }
    }
}
