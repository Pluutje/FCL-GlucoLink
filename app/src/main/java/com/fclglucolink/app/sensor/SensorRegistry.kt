package com.fclglucolink.app.sensor

import com.fclglucolink.app.sensor.accuchek.AccuChekSmartGuideDriver
import com.fclglucolink.app.sensor.caresensair.CareSensAirDriver
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6Driver
import com.fclglucolink.app.sensor.dexcomg7.DexcomG7Driver
import com.fclglucolink.app.sensor.simulator.SimulatorDriver

/**
 * 30/07/2026 (editor) — één plek die SensorType koppelt aan een daadwerkelijke
 * SensorDriver-implementatie. Een nieuwe sensor toevoegen (of Accu-Chek
 * straks afmaken) betekent: een driver-klasse schrijven + hier één regel
 * toevoegen — verder raakt niets in UI/BleConnectionService dit bestand.
 *
 * 17/08/2026 (editor, RONDE 112) — DEXCOM_G7 staat vanaf nu WEL in `drivers`
 * (zie DexcomG7Driver.kt's kdoc en SensorType.DEXCOM_G7's kdoc bij
 * `implemented` voor waarom dat, net als destijds bij G6, al vóór de eerste
 * live-test tegen een echte sensor gebeurt).
 *
 * 01/10/2026 (editor, RONDE 197) — ACCUCHEK_SMARTGUIDE staat nu ook WEL in
 * `drivers` — zie AccuChekSmartGuideDriver.kt's/AccuChekSmartGuideProtocol.kt's
 * kdoc en README.md's Ronde 197-sectie: de vroegere `error(...)` hier (zie
 * git-geschiedenis) is vervangen door een echte implementatie van het
 * standaard Bluetooth SIG Glucose Profile, net als G6/G7 destijds al vóór de
 * eerste live-test tegen een echte sensor.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — [slot] is nieuw
 * en VERPLICHT: elke driver-instantie leest/schrijft intern zelf al zijn
 * eigen slot-gescoopte AppSettings-velden (transmitter-ID, sessie-
 * boekhouding, warmup/batterij-status, ...) — zie DexcomG6Driver.kt/
 * CareSensAirDriver.kt's kdoc bij hun eigen `slot`-constructorparameter.
 * Elke aanroeper (BleConnectionService per slot, PairingScreen via de
 * nieuwe slot-route-parameter) moet dus altijd weten voor welke slot 'm een
 * driver aanmaakt.
 */
object SensorRegistry {

    fun createDriver(sensorType: SensorType, slot: SensorSlot): SensorDriver = when (sensorType) {
        SensorType.CARESENS_AIR -> CareSensAirDriver(slot)
        SensorType.DEXCOM_G6 -> DexcomG6Driver(slot)
        SensorType.DEXCOM_G7 -> DexcomG7Driver(slot)
        SensorType.ACCUCHEK_SMARTGUIDE -> AccuChekSmartGuideDriver(slot)
        SensorType.SIMULATOR -> SimulatorDriver(slot)
    }
}
