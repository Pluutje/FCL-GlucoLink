package com.fclglucolink.app.sensor.ble

import android.bluetooth.BluetoothDevice
import com.fclglucolink.app.logging.DiagnosticFileLogger

/**
 * ============================================================================
 * FCLGlucoLink — automatisch herstel van een verloren BLE-bond (RONDE 57,
 * 08/08/2026)
 * ============================================================================
 *
 * AANLEIDING (op verzoek, na een gesprek over een Android One-toestel waarop
 * andere apps regelmatig een sensor-bond kwijtraakten: "Is het ook mogelijk
 * om in plaats van tik op opnieuw koppelen de app dat automatisch te laten
 * doen") — zonder dit mechanisme is de enige weg terug na een OS- of
 * andere-app-veroorzaakt bond-verlies: de gebruiker moet zelf handmatig
 * "opnieuw koppelen" tikken. Deze utility automatiseert precies dat ene
 * stapje — niets meer — voor beide drivers (CareSensAirDriver.kt en
 * DexcomG6Driver.kt), gedeeld hier zodat de aanpak (en de logregels) voor
 * beide identiek is.
 *
 * WANNEER dit aangeroepen wordt (zie beide drivers' scan-resultaat-
 * afhandeling, vlak vóór connectGatt()): alléén als (a) de nieuwe
 * Instellingen-schakelaar "Automatic re-pair" AAN staat (standaard UIT —
 * zie AppSettings.bondLossAutoRecoveryEnabled), ÉN (b) er al eerder
 * succesvol verbonden is met dit toestel (lastConnectedAtMs is gezet) —
 * dat laatste voorkomt dat dit ooit meedoet tijdens een gloednieuwe,
 * nog-nooit-gekoppelde sensor (daar is BOND_NONE volkomen normaal, geen
 * "verlies"), ÉN (c) BluetoothDevice.getBondState() nu BOND_NONE teruggeeft
 * — een pure lokale OS-opvraging, geen BLE-verkeer.
 *
 * RISICO, EXPLICIET (op verzoek: "Zijn er ook risico's aan verbonden zoals
 * in mijn geval waar er eigenlijk geen verlies optreedt") — twee dingen om
 * te weten:
 *
 * 1) removeBond() is een OS-BREDE actie, geen FCLGlucoLink-interne toestand.
 *    Als een ANDERE app (xDrip+, BYODA, de officiële CareSens/Dexcom-app)
 *    ook aan hetzelfde fysieke toestel gebonden is, breekt removeBond() hier
 *    OOK die bond — silent, zonder dat die andere app het vooraf weet. Voor
 *    een gebruiker die naast FCLGlucoLink geen andere app tegelijk met
 *    dezelfde sensor laat praten is dit geen probleem; met zo'n andere app
 *    WEL actief ernaast is dit een reëel risico.
 * 2) Bij een gebruiker die het gerapporteerde bond-verlies-symptoom zelf
 *    niet ondervindt (zoals hier), doet deze functie vrijwel nooit iets: de
 *    voorwaarde-check hierboven (getBondState()==BOND_NONE terwijl er al
 *    eerder verbonden werd) is dan zelden of nooit waar. Met de schakelaar
 *    AAN maar zonder ooit een echt bond-verlies is het effect dus in de
 *    praktijk nihil — het kost geen extra tikken, geen extra bond-cycli, er
 *    verandert simpelweg niks totdat het scenario zich ooit voordoet.
 *
 * removeBond() is GEEN publieke Android-API — reflectie is hier, net als in
 * xDrip+ zelf (Ob1G5StateMachine.doKeepAliveAndBondRequest() -> unBond() ->
 * instantCreateBondIfAllowed()), de gangbare, geaccepteerde aanpak.
 *
 * NOOIT STIL: elke stap hier logt via DiagnosticFileLogger — zowel naar
 * logcat (altijd) als naar het diagnose-logbestand (als die schakelaar ook
 * aan staat) — juist omdat dit een BG-data-relevante app is en de gebruiker
 * altijd moet kunnen nagaan of, wanneer en met welk resultaat automatisch
 * herstel geprobeerd is.
 */
object BondLossRecovery {

    /** Pure lokale OS-opvraging (geen BLE-verkeer) — true als dit toestel nu
     *  NIET gebonden is. */
    fun isBondMissing(device: BluetoothDevice): Boolean =
        device.bondState == BluetoothDevice.BOND_NONE

    /**
     * Probeert removeBond() (via reflectie) gevolgd door createBond() —
     * beide los in runCatching, zodat een falende removeBond() (bv. op een
     * OEM die de verborgen methode ooit dichtzet) createBond() niet blokkeert.
     * Het BOND_BONDED-resultaat komt asynchroon terug via de bestaande
     * ACTION_BOND_STATE_CHANGED-receiver in elke driver — deze functie wacht
     * daar zelf niet op, dat is aan de aanroeper (zie beide drivers'
     * pendingAfterBond-gebruik).
     *
     * @param tag korte drivernaam voor de logregel, bv. "CareSensAir" of "DexcomG6".
     */
    fun attemptRecovery(device: BluetoothDevice, tag: String) {
        DiagnosticFileLogger.log(
            "$tag: bond-loss recovery triggered for ${device.address} — bond state is " +
                "BOND_NONE while a previous successful connection is on record. Attempting " +
                "removeBond()+createBond() (same reflection-based approach xDrip+ itself uses). " +
                "NOTE: removeBond() is OS-wide, not per-app — if another app is also bonded to " +
                "this device, its bond breaks too."
        )
        val removeOk = runCatching {
            device.javaClass.getMethod("removeBond").invoke(device)
        }.onFailure {
            DiagnosticFileLogger.logError("$tag: bond-loss recovery — removeBond() failed: ${it.message}")
        }.isSuccess
        DiagnosticFileLogger.log("$tag: bond-loss recovery — removeBond() invoked, success=$removeOk")

        val createOk = runCatching { device.createBond() }
            .onFailure { DiagnosticFileLogger.logError("$tag: bond-loss recovery — createBond() failed: ${it.message}") }
            .getOrDefault(false)
        DiagnosticFileLogger.log("$tag: bond-loss recovery — createBond() invoked, result=$createOk")
    }
}
