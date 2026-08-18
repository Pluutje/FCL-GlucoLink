package com.fclglucolink.app.sensor.ble

/**
 * ============================================================================
 * FCLGlucoLink — gedeeld, proces-breed BLE-scanplafond
 * ============================================================================
 *
 * 08/08/2026 (editor, RONDE 55 — bij het starten van de Dexcom G6-driver) —
 * VERPLAATST vanuit CareSensAirDriver.kt (was daar een top-level `private
 * object`) naar dit gedeelde bestand, precies zoals de kdoc op de oude plek
 * al aankondigde: "als een toekomstige BLE-sensor (Accu-Chek SmartGuide/
 * Dexcom G7) hier ooit bijkomt, moet die dit object hergebruiken i.p.v. een
 * eigen kopie te maken." De Dexcom G6-driver is die eerste nieuwe gebruiker.
 *
 * Mirror van Juggluco's `SensorBluetooth.q`/`k()` (zie CareSensAirDriver.kt's
 * klasse-kdoc voor de volledige aanleiding/decompile-geschiedenis): een
 * gedeeld plafond van maximaal 5 scan-STARTS binnen een glijdend venster van
 * 31 seconden — bewust proces-breed (niet per driver-instantie/sensor-type),
 * want het gaat om het totale BLE-scangebruik van de app tegenover Android's
 * eigen (ongedocumenteerde) achtergrond-scanquota, niet om één specifieke
 * sensor. Elke sensor-driver die zelf actief scant (in plaats van
 * `connectGatt()` rechtstreeks op een al bekend adres) moet hier vóór elke
 * `scanner.startScan()` [delayBeforeNextScanMs] respecteren en er vlak
 * ervoor [recordScanStart] op aanroepen.
 *
 * 10/08/2026 (editor, RONDE 83, op verzoek na live-melding — "wat ik in
 * ieder geval wil is dat de sensor die aan aaps is gekoppeld altijd de
 * voorrang krijgt en dus precies om de 5 minuten blijft data binnenhalen")
 * — vóór deze ronde telde elke scan-start hier ongeacht welke slot 'm
 * veroorzaakte gewoon mee tegen hetzelfde gedeelde plafond, dus de twee
 * slots concurreerden om exact hetzelfde budget. Zichtbaar in een live
 * diagnostic-log: zodra Dexcom G6 (slot B, niet de AAPS-bron) ook ging
 * scannen, schoof CareSens Air's (slot A, WEL de AAPS-bron) eigen
 * reconnect-cadans structureel van 5 naar 6 minuten — Dexcom's eigen
 * hertries (4x kort na elkaar bij een gemiste beacon) consumeerden het
 * grootste deel van het gedeelde budget, waardoor CareSens Air's
 * eerstvolgende scan-start moest wachten. Nu draagt elke boeking een
 * [isPriority]-vlag: een AAPS-actieve ("priority") aanroeper telt bij zijn
 * EIGEN plafond-check alleen andere priority-scans mee (niet-priority-
 * verkeer telt voor hem dus niet mee — hij wordt er nooit door opgehouden),
 * terwijl een niet-priority aanroeper (de andere slot) gewoon tegen de VOLLE
 * geschiedenis (priority + niet-priority) blijft toetsen — die wijkt dus
 * altijd uit zodra er krapte is. Het gedeelde plafond zelf (5 scans/31s,
 * Juggluco's mirror van Android's ongedocumenteerde achtergrondquota) blijft
 * ongewijzigd — dit verandert alleen WIE bij krapte moet wachten, niet HOEVEEL
 * er in totaal mag.
 */
object ScanRateLimiter {
    private data class ScanRecord(val atMs: Long, val isPriority: Boolean)

    private val recentScanStarts = ArrayDeque<ScanRecord>()
    private const val WINDOW_MS = 31_000L
    private const val MAX_SCANS_PER_WINDOW = 5

    /** Hoeveel ms te wachten vóór de volgende scan-START toegestaan is
     *  (0 = mag meteen). Ruimt daarbij ook meteen verlopen tijdstippen op.
     *
     *  [isPriority]: geef `true` mee voor de slot die momenteel naar AAPS
     *  zendt (`AppSettings.aapsActiveSlot`) — die toetst dan alleen tegen
     *  ANDERE priority-scans (in de praktijk vrijwel nooit een probleem,
     *  want die slot scant zelf maar eens per ~5 minuten), en wordt dus
     *  nooit opgehouden door de andere slot se scanverkeer. */
    @Synchronized
    fun delayBeforeNextScanMs(isPriority: Boolean = false): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        while (recentScanStarts.isNotEmpty() && now - recentScanStarts.first().atMs >= WINDOW_MS) {
            recentScanStarts.removeFirst()
        }
        val relevant = if (isPriority) recentScanStarts.filter { it.isPriority } else recentScanStarts
        return if (relevant.size >= MAX_SCANS_PER_WINDOW) {
            WINDOW_MS - (now - relevant.first().atMs)
        } else {
            0L
        }
    }

    /** Aanroepen vlak vóór (niet ná) een daadwerkelijke `scanner.startScan()`.
     *  Zelfde [isPriority]-betekenis als bij [delayBeforeNextScanMs] — geef
     *  hier dezelfde waarde mee als net daarvoor bij de bijbehorende
     *  `delayBeforeNextScanMs()`-aanroep. */
    @Synchronized
    fun recordScanStart(isPriority: Boolean = false) {
        recentScanStarts.addLast(ScanRecord(android.os.SystemClock.elapsedRealtime(), isPriority))
    }
}
