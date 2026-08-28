package com.fclglucolink.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.ble.ConnectionStatusBridge
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.stopBleConnectionService
import kotlinx.coroutines.launch

// 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317) — elke
// route die ooit met ÉÉN vaste (Slot A) sensor werkte, is nu geparametriseerd
// met een `{slot}`-padargument (zie slotRoute()/slotArg() helpers hieronder)
// — CombiScreen.kt (het nieuwe startscherm, ROUTE_COMBI) heeft immers zowel
// Slot A als Slot B tegelijk op het scherm, en de "Sensor"/(i)-knoppen op elk
// tabblad moeten de JUISTE slot beheren/tonen, niet allebei stiekem Slot A
// (dat zou het "beide slots moeten onafhankelijk te beheren zijn"-vereiste
// breken). Voor elke voorheen ongeparametriseerde route X bestaat nu een
// BASE_X (de kale naam, voor het OPBOUWEN van een concrete navigate()-
// bestemming via slotRoute()) plus een ROUTE_X = "$BASE_X/{slot}" (het
// PATROON, voor het REGISTREREN bij composable() én voor popUpTo() — Compose
// Navigation matcht popUpTo(route) tegen het geregistreerde PATROON, niet
// tegen een opgeloste concrete waarde, dus popUpTo-aanroepen hieronder
// gebruiken nog steeds gewoon de ROUTE_X-constante, ongewijzigd).
private fun slotRoute(base: String, slot: SensorSlot) = "$base/${slot.name}"

private fun slotArg(backStackEntry: NavBackStackEntry): SensorSlot =
    backStackEntry.arguments?.getString("slot")?.let {
        runCatching { SensorSlot.valueOf(it) }.getOrNull()
    } ?: SensorSlot.A

// 10/08/2026 (editor, RONDE 79) — de meeste BASE_X-constanten hieronder zijn
// nog wél `const val` (letterlijke strings, geen interpolatie), maar elke
// ROUTE_X die met "$BASE_X/..." is opgebouwd staat bewust als GEWONE `val`,
// niet `const val`: Kotlin's `const` vereist een compile-time-constante
// expressie, en de auteur wilde hier geen risico lopen op een grensgeval
// rond string-template-interpolatie van const vals zonder compiler bij de
// hand om het te verifiëren — een gewone top-level `val` wordt hier precies
// één keer, bij class-load, geëvalueerd en doet functioneel exact hetzelfde
// (alle gebruik hieronder is als functie-argument, nooit als annotatie-
// argument of `const`-vereisende context, dus dit heeft geen enkel nadeel).
private const val BASE_SENSOR_SELECTION = "sensor_selection"
private val ROUTE_SENSOR_SELECTION = "$BASE_SENSOR_SELECTION/{slot}"
private const val BASE_PAIRING = "pairing"
private val ROUTE_PAIRING = "$BASE_PAIRING/{sensorType}/{slot}"
private const val BASE_SIMULATOR_SETUP = "simulator_setup"
private val ROUTE_SIMULATOR_SETUP = "$BASE_SIMULATOR_SETUP/{slot}"
// 10/08/2026 (editor, RONDE 79) — vervangt ROUTE_STATUS (StatusScreen.kt
// rechtstreeks als startscherm): CombiScreen.kt is nu het startscherm, geen
// eigen slot-argument nodig (toont beide slots tegelijk, zie kdoc daar).
private const val ROUTE_COMBI = "combi"
// 09/08/2026 (editor, RONDE 64) — vervangt ROUTE_SENSOR_MANAGEMENT (het
// vroegere, gedeelde scherm): zie de uitgebreide kdoc bij
// FclGlucoLinkNavHost() hieronder voor het volledige herstructureringsverhaal.
private const val BASE_DEXCOM_G6_STATUS = "dexcom_g6_status"
private val ROUTE_DEXCOM_G6_STATUS = "$BASE_DEXCOM_G6_STATUS/{slot}"
private const val BASE_CARESENS_STATUS = "caresens_status"
private val ROUTE_CARESENS_STATUS = "$BASE_CARESENS_STATUS/{slot}"
// 27/08/2026 (editor, RONDE 129) — zie DexcomG7StatusScreen.kt's kdoc:
// vervangt de statusRouteFor()-fallback naar PairingScreen uit Ronde 127.
private const val BASE_DEXCOM_G7_STATUS = "dexcom_g7_status"
private val ROUTE_DEXCOM_G7_STATUS = "$BASE_DEXCOM_G7_STATUS/{slot}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"
// 13/08/2026 (editor, RONDE 106) — geen slot-argument nodig, zie
// AppSettings.kt's "Alarmen"-sectie: globale instellingen, niet gekoppeld
// aan één specifieke sensor-slot (zelfde reden als ROUTE_SETTINGS/
// ROUTE_ABOUT hierboven).
private const val ROUTE_ALARMS = "alarms"
private const val BASE_CARESENS_AIR_SCAN = "caresens_air_scan"
private val ROUTE_CARESENS_AIR_SCAN = "$BASE_CARESENS_AIR_SCAN/{slot}"
private const val BASE_CARESENS_AIR_CHOICE = "caresens_air_choice"
private val ROUTE_CARESENS_AIR_CHOICE = "$BASE_CARESENS_AIR_CHOICE/{slot}"
// 08/08/2026 (editor, RONDE 55) — zie DexcomG6SetupScreen.kt's kdoc.
private const val BASE_DEXCOM_G6_SETUP = "dexcom_g6_setup"
private val ROUTE_DEXCOM_G6_SETUP = "$BASE_DEXCOM_G6_SETUP/{slot}"
// 08/08/2026 (editor, RONDE 56) — zie DexcomG6NewSensorScreen.kt's kdoc.
private const val BASE_DEXCOM_G6_NEW_SENSOR = "dexcom_g6_new_sensor"
private val ROUTE_DEXCOM_G6_NEW_SENSOR = "$BASE_DEXCOM_G6_NEW_SENSOR/{slot}"
// 17/08/2026 (editor, RONDE 112) — zie DexcomG7SetupScreen.kt's kdoc, zelfde
// patroon als BASE_DEXCOM_G6_SETUP hierboven.
private const val BASE_DEXCOM_G7_SETUP = "dexcom_g7_setup"
private val ROUTE_DEXCOM_G7_SETUP = "$BASE_DEXCOM_G7_SETUP/{slot}"
// Geen slot-argument nodig voor onderstaande twee: app-brede
// instellingen/documentatie, niet gekoppeld aan één specifieke sensor-slot.
//
// 10/08/2026 (editor, RONDE 81, CRITICAL BUGFIX, live-melding — "kalibraties
// die bij het ene slot worden ingevoerd verschijnen ook bij de andere") —
// ROUTE_CALIBRATION was HIER nog steeds een kale, niet-slot-geparametriseerde
// route (`"calibration"`, geen `{slot}`), een overblijfsel van vóór taak
// #321 (RONDE 80) calibratie per-slot maakte — die ronde maakte wel
// CalibrationScreen()'s eigen `slot`-parameter en AppSettings' kalibratie-
// functies per-slot, maar vergat DEZE route/navigatie mee te nemen. Gevolg:
// `CalibrationScreen(onBack = { ... })` werd hieronder ALTIJD zonder
// expliciete slot aangeroepen, dus altijd met de default `slot =
// SensorSlot.A` — ongeacht vanuit welk tabblad (A of B) de "Calibration"-
// knop was aangetikt. Nu wél `{slot}`-geparametriseerd, exact hetzelfde
// patroon als alle andere per-slot routes hierboven.
private const val BASE_CALIBRATION = "calibration"
private val ROUTE_CALIBRATION = "$BASE_CALIBRATION/{slot}"
// 06/08/2026 (editor, RONDE 50) — zie ManualScreen.kt's kdoc.
private const val ROUTE_MANUAL = "manual"
// 06/08/2026 (editor, RONDE 52) — zie ManualScreen.kt's kdoc: één los
// onderwerp, zelfde geparametriseerde-route-patroon als ROUTE_PAIRING
// hieronder (`{topic}` -> ManualTopic.name, teruggeparsed bij de
// composable zelf).
private const val ROUTE_MANUAL_TOPIC = "manual_topic/{topic}"

/**
 * 30/07/2026 (editor, na feedback) — het statusscherm is nu het STARTSCHERM,
 * niet meer een auto-redirect die je meteen het koppelproces induwt. Reden:
 * editor wil bij het opstarten altijd eerst de BG-curve/status zien (ook als
 * die nog leeg is), en zelf kiezen om een sensor te (her)koppelen of de
 * verbinding te verbreken — niet gedwongen worden meteen te koppelen
 * voordat er iets te zien is. StatusScreen kan prima met "nog geen sensor
 * gekozen" omgaan (leeg grafiekje, duidelijke tekst), dus er is geen aparte
 * router/leesscherm meer nodig.
 *
 * 31/07/2026 (editor, na feedback over de menu-indeling) — twee nieuwe
 * routes: sensor_management (kiezen/wisselen/loskoppelen/sensor-info,
 * geopend via de sensorkaart op het statusscherm — was eerder het ⋮-menu
 * + los infokaartje) en settings/about (algemene instellingen — xDrip-
 * broadcast aan/uit — + appinfo, geopend via het ⋮-menu). Zie kdoc bij
 * StatusScreen.kt/SettingsScreen.kt.
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek — "de sensor knop naast de Bg
 * waarde is bedoeld om van sensor type te wisselen [...] Ieder sensor type
 * krijgt dan zijn eigen specifiek status/koppel/wissel scherm") — het
 * vroegere, ENE gedeelde ROUTE_SENSOR_MANAGEMENT (SensorManagementScreen.kt,
 * nu vervallen) mengde acties van verschillende sensortypes op één scherm.
 * StatusScreen's "Sensor"-knop opent het type-KEUZEMENU
 * (SensorSelectionScreen.kt) i.p.v. het beheerscherm; de compacte
 * samenvatting krijgt een (i)-knop die rechtstreeks naar het statusscherm
 * van het HUIDIGE type gaat.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, taak #317, op
 * oorspronkelijk verzoek — "beide slots moeten kunnen zenden naar aaps
 * waarbij er uiteraard maar max 1 actief kan zijn, maar ze moeten ook
 * beiden uit kunnen" + de tab-UI-spec, zie CombiScreen.kt's kdoc) — de
 * BELANGRIJKSTE wijziging deze ronde: CombiScreen (ROUTE_COMBI) vervangt
 * StatusScreen als startscherm, en zowat elke route hieronder is nu
 * geparametriseerd met `{slot}` (zie slotRoute()/slotArg() bovenaan dit
 * bestand) zodat Slot A's en Slot B's tabblad op CombiScreen elk hun EIGEN,
 * onafhankelijke koppel-/beheerpad doorlopen — vóór deze ronde bestond er
 * maar één, altijd-op-Slot-A-vastgezet pad (zie git-geschiedenis), wat
 * betekende dat Slot B's tabblad Slot A's sensor zou hebben beheerd als dit
 * niet was aangepast.
 */
@Composable
fun FclGlucoLinkNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()

    /** 09/08/2026 (editor, RONDE 64) — gedeelde navigatie-functie: welk
     *  scherm hoort bij het HUIDIGE actieve sensor-type — gebruikt door
     *  zowel CombiScreen's (i)-knop als SensorSelectionScreen's
     *  onReopenActive (tikken op het al-actieve type). Geen popUpTo/
     *  inclusive-opruiming hier (dat verschilt per aanroeper — soms moet de
     *  vorige route in de terug-stapel blijven staan, soms niet), puur de
     *  routebepaling zelf.
     *
     *  10/08/2026 (editor, RONDE 79) — geeft nu de BASIS-routenaam terug
     *  (zonder slot), zie slotRoute() bovenaan dit bestand: de aanroeper
     *  plakt zelf de juiste slot erachter, want deze functie kent alleen
     *  het sensortype, niet voor welke slot 'm aangeroepen wordt.
     *
     *  27/08/2026 (editor, RONDE 127, bug gemeld tijdens een live G7-test —
     *  "als ik op het hoofdscherm op de status info klik komt hij op de
     *  'choose you sensor' pagina en klikt bij de g7 niet door naar de
     *  extra info pagina") — DEXCOM_G7 had hier GEEN eigen case, viel dus in
     *  de `else -> BASE_SENSOR_SELECTION`-val: een "status info"-tik voor
     *  een G7-slot landde daardoor altijd op SensorSelectionScreen i.p.v.
     *  ergens G7-specifieks. Erger nog: SensorSelectionScreen's eigen
     *  `sensor == activeSensor -> onReopenActive()`-tak (zie
     *  SensorSelectionScreen.kt) roept DEZE functie opnieuw aan — tikken op
     *  de dan al-actieve G7-tegel navigeerde dus telkens naar dezelfde
     *  BASE_SENSOR_SELECTION-route waar de gebruiker al stond, wat als een
     *  scherm voelt dat niets doet ("klikt niet door").
     *
     *  Tussenoplossing in RONDE 127: bij gebrek aan een eigen G7-
     *  statusscherm stuurde dit voor DEXCOM_G7 door naar de generieke
     *  PairingScreen. RONDE 129 (op verzoek — "wat we in ieder geval alvast
     *  kunnen doen is een statusscherm maken") bouwt dat eigen scherm alsnog
     *  (DexcomG7StatusScreen.kt), dus DEXCOM_G7 krijgt nu net als G6/
     *  CareSens/Simulator een eigen, echte case.
     */
    fun statusRouteFor(sensorType: SensorType?, slot: SensorSlot): String = when (sensorType) {
        SensorType.DEXCOM_G6 -> slotRoute(BASE_DEXCOM_G6_STATUS, slot)
        SensorType.CARESENS_AIR -> slotRoute(BASE_CARESENS_STATUS, slot)
        SensorType.SIMULATOR -> slotRoute(BASE_SIMULATOR_SETUP, slot)
        SensorType.DEXCOM_G7 -> slotRoute(BASE_DEXCOM_G7_STATUS, slot)
        else -> slotRoute(BASE_SENSOR_SELECTION, slot)
    }

    NavHost(navController = navController, startDestination = ROUTE_COMBI) {
        // 10/08/2026 (editor, RONDE 79) — het nieuwe startscherm, zie
        // CombiScreen.kt's kdoc. Leest hier ZELF selectedSensorA/B (i.p.v.
        // dat CombiScreen dat doet) puur om onOpenSensorStatus's
        // routebepaling (statusRouteFor) te kunnen doen — CombiScreen zelf
        // hoeft de resulterende ROUTE-naam niet te kennen, geeft alleen de
        // aangetikte SensorSlot door.
        composable(ROUTE_COMBI) {
            val selectedSensorA by settings.selectedSensor(SensorSlot.A).collectAsState(initial = null)
            val selectedSensorB by settings.selectedSensor(SensorSlot.B).collectAsState(initial = null)
            CombiScreen(
                onSwitchSensorType = { targetSlot ->
                    navController.navigate(slotRoute(BASE_SENSOR_SELECTION, targetSlot))
                },
                onOpenSensorStatus = { targetSlot ->
                    val activeSensor = if (targetSlot == SensorSlot.A) selectedSensorA else selectedSensorB
                    navController.navigate(statusRouteFor(activeSensor, targetSlot))
                },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                // 10/08/2026 (editor, RONDE 81, CRITICAL BUGFIX) — zie kdoc
                // bij ROUTE_CALIBRATION hierboven: nu `slotRoute()` net als
                // alle andere per-slot routes, i.p.v. de kale, altijd-Slot-A
                // route van vóór deze fix.
                onOpenCalibration = { targetSlot -> navController.navigate(slotRoute(BASE_CALIBRATION, targetSlot)) },
                onOpenManual = { navController.navigate(ROUTE_MANUAL) }
            )
        }

        // 05/08/2026 (editor, RONDE 43) — zie CalibrationScreen.kt's kdoc.
        //
        // 10/08/2026 (editor, RONDE 81, CRITICAL BUGFIX) — WEL een
        // slot-argument, zie kdoc bij ROUTE_CALIBRATION hierboven (deze regel
        // was de kern van de bug: riep CalibrationScreen() voorheen zonder
        // slot aan, dus altijd de default Slot A).
        composable(ROUTE_CALIBRATION) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            CalibrationScreen(onBack = { navController.popBackStack() }, slot = slot)
        }

        // 06/08/2026 (editor, RONDE 50, herstructureerd in RONDE 52) — zie
        // ManualScreen.kt's kdoc: dit is nu het MENU (lijst van
        // onderwerpen); elke rij navigeert naar ROUTE_MANUAL_TOPIC hieronder
        // voor de daadwerkelijke inhoud van dat ene onderwerp.
        composable(ROUTE_MANUAL) {
            ManualScreen(
                onBack = { navController.popBackStack() },
                onOpenTopic = { topic ->
                    navController.navigate("manual_topic/${topic.name}")
                }
            )
        }

        // 06/08/2026 (editor, RONDE 52) — zie ManualScreen.kt's kdoc.
        // onBack hier gaat terug naar ROUTE_MANUAL (het menu hierboven), niet
        // in één keer door naar het thuisscherm — gewoon een popBackStack()
        // op de normale navigatie-terugstapel, precies zoals overal elders
        // in deze NavHost.
        composable(ROUTE_MANUAL_TOPIC) { backStackEntry ->
            val topicName = backStackEntry.arguments?.getString("topic")
            val topic = topicName?.let {
                runCatching { ManualTopic.valueOf(it) }.getOrNull()
            } ?: ManualTopic.HOME_SCREEN
            ManualTopicScreen(
                topic = topic,
                onBack = { navController.popBackStack() },
                // 06/08/2026 (editor, RONDE 53) — zie ManualScreen.kt's
                // kdoc: alleen daadwerkelijk aangeroepen op de pagina die
                // ManualTopic.showAboutLink heeft (BEST_RESULTS).
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) }
            )
        }

        // 09/08/2026 (editor, RONDE 64) — zie DexcomG6StatusScreen.kt's kdoc:
        // vervangt het G6-deel van het vroegere ROUTE_SENSOR_MANAGEMENT.
        composable(ROUTE_DEXCOM_G6_STATUS) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            DexcomG6StatusScreen(
                slot = slot,
                onBack = { navController.popBackStack() },
                // "Switch transmitter" — zelfde bestemming als het kiezen
                // van G6 voor het EERST (nieuwe transmitter-ID intypen);
                // functioneel identiek, alleen nu bereikbaar zonder eerst
                // via het algemene sensorkeuzemenu te hoeven gaan.
                onSwitchTransmitter = {
                    navController.navigate(slotRoute(BASE_DEXCOM_G6_SETUP, slot))
                },
                onStartNewSensor = {
                    navController.navigate(slotRoute(BASE_DEXCOM_G6_NEW_SENSOR, slot))
                },
                onDisconnect = {
                    stopBleConnectionService(context)
                    // Niet wachten op Service.onDestroy() — direct zichtbare
                    // feedback, zie kdoc bij stopBleConnectionService().
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch { settings.clearDeviceAddress(slot) }
                }
            )
        }

        // 09/08/2026 (editor, RONDE 64) — zie CareSensAirStatusScreen.kt's
        // kdoc: vervangt het CareSens-deel van het vroegere
        // ROUTE_SENSOR_MANAGEMENT.
        composable(ROUTE_CARESENS_STATUS) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            CareSensAirStatusScreen(
                slot = slot,
                onBack = { navController.popBackStack() },
                // "Start / switch sensor" — dezelfde nieuw/al-lopend-keuze
                // als bij het EERST kiezen van CareSens Air, zie
                // CareSensAirChooseScreen.kt.
                onManageSensor = {
                    navController.navigate(slotRoute(BASE_CARESENS_AIR_CHOICE, slot))
                },
                onDisconnect = {
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch { settings.clearDeviceAddress(slot) }
                }
            )
        }

        // 27/08/2026 (editor, RONDE 129) — zie DexcomG7StatusScreen.kt's kdoc,
        // zelfde patroon als ROUTE_DEXCOM_G6_STATUS/ROUTE_CARESENS_STATUS
        // hierboven.
        composable(ROUTE_DEXCOM_G7_STATUS) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            DexcomG7StatusScreen(
                slot = slot,
                onBack = { navController.popBackStack() },
                onDisconnect = {
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch { settings.clearDeviceAddress(slot) }
                },
                // 27/08/2026 (editor, RONDE 130) — zie DexcomG7StatusScreen.kt's
                // kdoc punt 3+4: hergebruikt dezelfde route/flow als
                // SensorSelectionScreen's "Switch transmitter"-actie
                // (regel ~457 hieronder) — DexcomG7SetupScreen.kt's
                // onConfirmed wist zelf al het device-adres, slaat de nieuwe
                // code op en navigeert door naar het koppelscherm.
                onChangePairingCode = {
                    navController.navigate(slotRoute(BASE_DEXCOM_G7_SETUP, slot))
                }
            )
        }

        // 08/08/2026 (editor, RONDE 56) — zie DexcomG6NewSensorScreen.kt's kdoc.
        composable(ROUTE_DEXCOM_G6_NEW_SENSOR) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            DexcomG6NewSensorScreen(
                onBack = { navController.popBackStack() },
                onStarted = { navController.popBackStack() },
                slot = slot
            )
        }

        // 06/08/2026 (editor, RONDE 53) — SettingsScreen.kt's onOpenAbout-
        // parameter is vervallen; die link staat nu op de laatste pagina
        // van de handleiding, zie ROUTE_MANUAL_TOPIC hierboven.
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                // 13/08/2026 (editor, RONDE 106) — zie SettingsScreen.kt's
                // kdoc bij de nieuwe "Alarms"-kaart: alleen een link naar het
                // uitgebreide AlarmSettingsScreen hieronder, geen slot-
                // argument (globale instellingen).
                onOpenAlarms = { navController.navigate(ROUTE_ALARMS) }
            )
        }

        composable(ROUTE_ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        // 13/08/2026 (editor, RONDE 106) — zie AlarmSettingsScreen.kt's kdoc.
        composable(ROUTE_ALARMS) {
            AlarmSettingsScreen(onBack = { navController.popBackStack() })
        }

        // 09/08/2026 (editor, RONDE 64) — zie SensorSelectionScreen.kt's kdoc
        // en de uitgebreide kdoc bij FclGlucoLinkNavHost() hierboven voor het
        // volledige herstructureringsverhaal (type-KEUZE i.p.v. het vroegere
        // "koppel gewoon meteen"-gedrag).
        composable(ROUTE_SENSOR_SELECTION) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            val activeSensor by settings.selectedSensor(slot).collectAsState(initial = null)
            SensorSelectionScreen(
                activeSensor = activeSensor,
                onReopenActive = {
                    navController.navigate(statusRouteFor(activeSensor, slot)) {
                        popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                    }
                },
                onSensorChosen = { sensorType ->
                    // 09/08/2026 (editor, RONDE 64) — als er nog een ANDER
                    // type actief was (SensorSelectionScreen toonde dan al de
                    // bevestigingsdialoog vóór deze callback), eerst netjes
                    // loskoppelen — zelfde twee stappen als overal elders in
                    // deze NavHost bij een sensorwissel. Bij "nog niets
                    // actief" (activeSensor == null) zijn dit onschuldige
                    // no-ops (er is toch niets verbonden).
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    // BG-simulator heeft geen BLE-koppeling nodig -> eigen
                    // route, geen echte devices om te vinden (zie
                    // SimulatorSetupScreen). CareSens Air: altijd via de
                    // nieuw/al-lopend-keuze (CareSensAirChooseScreen.kt) — een
                    // CareSens-sensor is een wegwerpartikel met een korte
                    // draagtijd (15 dagen), dus een eerder gescande barcode
                    // blindelings hergebruiken zou vaker een VERLOPEN sensor
                    // treffen dan een nog geldige; geen "sla de keuze over"
                    // snelkoppeling zoals G6 hieronder. Dexcom G6: WEL een
                    // snelkoppeling als de transmitter-ID al bekend is (zie
                    // AppSettings.hasKnownDexcomG6TransmitterOnce()'s kdoc) —
                    // een transmitter is herbruikbare hardware, geen
                    // wegwerpartikel, dus "onthoud de laatste transmitter-
                    // code bij terugwisselen" (expliciet gevraagd) betekent
                    // hier: sla de setup-wizard over en ga direct naar
                    // PairingScreen.
                    scope.launch {
                        // 28/08/2026 (editor, RONDE 140, op melding van de
                        // gebruiker — zie README voor het volledige verhaal)
                        // — een Dexcom G7-koppelcode hoort bij de WEGWERP-
                        // SENSOR (elke nieuwe G7 heeft een eigen code op de
                        // applicator, sensor gaat ~10 dagen mee), niet bij
                        // herbruikbare hardware zoals G6's transmitter-ID
                        // (waarvoor "onthoud 'm bij terugwisselen" hierboven
                        // WEL expliciet gevraagd is, zie kdoc bij DEXCOM_G6).
                        // Wisselen weg van G7 naar een ander type moet de
                        // oude code dus vergeten — anders koppelt de app bij
                        // de volgende (nieuwe) G7-sensor stilzwijgend door
                        // met een inmiddels ONGELDIGE code, wat alleen tot
                        // een verwarrende "onjuiste koppelcode"-foutmelding
                        // kan leiden terwijl de gebruiker de nieuwe code
                        // (nog) niet eens had kunnen invoeren.
                        if (activeSensor == SensorType.DEXCOM_G7 && sensorType != SensorType.DEXCOM_G7) {
                            settings.clearDexcomG7PairingCode(slot)
                        }
                        when (sensorType) {
                            SensorType.SIMULATOR -> {
                                settings.setSelectedSensor(slot, sensorType)
                                navController.navigate(slotRoute(BASE_SIMULATOR_SETUP, slot)) {
                                    popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                }
                            }
                            SensorType.CARESENS_AIR -> {
                                navController.navigate(slotRoute(BASE_CARESENS_AIR_CHOICE, slot)) {
                                    popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                }
                            }
                            SensorType.DEXCOM_G6 -> {
                                if (settings.hasKnownDexcomG6TransmitterOnce(slot)) {
                                    settings.setSelectedSensor(slot, sensorType)
                                    navController.navigate(
                                        "$BASE_PAIRING/${SensorType.DEXCOM_G6.name}/${slot.name}"
                                    ) {
                                        popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                    }
                                } else {
                                    navController.navigate(slotRoute(BASE_DEXCOM_G6_SETUP, slot)) {
                                        popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                    }
                                }
                            }
                            // 17/08/2026 (editor, RONDE 112) — zelfde
                            // "al een code bekend? dan meteen koppelen, anders
                            // eerst de code vragen"-patroon als DEXCOM_G6
                            // hierboven, zie DexcomG7SetupScreen.kt's kdoc.
                            SensorType.DEXCOM_G7 -> {
                                if (settings.hasKnownDexcomG7PairingCodeOnce(slot)) {
                                    settings.setSelectedSensor(slot, sensorType)
                                    navController.navigate(
                                        "$BASE_PAIRING/${SensorType.DEXCOM_G7.name}/${slot.name}"
                                    ) {
                                        popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                    }
                                } else {
                                    navController.navigate(slotRoute(BASE_DEXCOM_G7_SETUP, slot)) {
                                        popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                                    }
                                }
                            }
                            else -> navController.navigate("$BASE_PAIRING/${sensorType.name}/${slot.name}") {
                                popUpTo(ROUTE_SENSOR_SELECTION) { inclusive = true }
                            }
                        }
                    }
                },
                // 10/08/2026 (editor, RONDE 80, taak #320) — zelfde
                // netjes-loskoppelen-eerst-stappen als onSensorChosen
                // hierboven bij een echte type-wissel, alleen eindigt dit pad
                // in settings.clearSelectedSensor(slot) i.p.v. een nieuwe
                // sensor te kiezen — daarna terug naar waar dit scherm vandaan
                // kwam (CombiScreen's tabblad voor deze slot), dat leest
                // selectedSensor(slot) zelf al reactief en toont dan
                // vanzelf de "No sensor chosen"-staat.
                onClearSensor = {
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch {
                        // RONDE 140 — zie kdoc hierboven bij onSensorChosen:
                        // zelfde reden, "op None zetten" is voor G7 ook een
                        // type-wissel (weg van DEXCOM_G7), dus ook hier de
                        // oude koppelcode vergeten i.p.v. laten staan.
                        if (activeSensor == SensorType.DEXCOM_G7) {
                            settings.clearDexcomG7PairingCode(slot)
                        }
                        settings.clearSelectedSensor(slot)
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(ROUTE_CARESENS_AIR_CHOICE) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            CareSensAirChooseScreen(
                onBack = { navController.popBackStack() },
                onNewSensor = { navController.navigate(slotRoute(BASE_CARESENS_AIR_SCAN, slot)) },
                onExistingSensor = {
                    // 03/08/2026 (editor) — zelfde opruimstappen als de
                    // barcode-scan hieronder normaal doet vóór het naar
                    // PairingScreen gaat (oude verbinding stoppen, sensortype
                    // vastleggen) — alleen zónder settings.saveCareSensAirScan(slot),
                    // want er is geen scanresultaat. Zie de uitgebreide kdoc
                    // bij CareSensAirChooseScreen.kt voor waarom dat veilig is:
                    // niets in de koppel-/verbindingslogica hangt af van de
                    // barcode-data zelf.
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    // 09/08/2026 (editor, RONDE 61 — zie DexcomG6SetupScreen's
                    // analoge fix hieronder voor de volledige kdoc) —
                    // navigate() stond hier voorheen NA de scope.launch{},
                    // dus buiten 'm — een niet-suspend aanroep die synchroon
                    // meteen doorging, vóórdat de DataStore-schrijven in de
                    // launch{} gegarandeerd voltooid waren. navigate() nu
                    // ALS LAATSTE statement BINNEN de launch{}, zodat
                    // PairingScreen's buildPairingListFilter()-lezing altijd
                    // de zojuist geschreven waarde ziet.
                    scope.launch {
                        settings.clearDeviceAddress(slot)
                        // 28/08/2026 (editor, RONDE 154, CRITIEKE FIX) — zie
                        // AppSettings.clearCareSensAirSensorSession()'s kdoc:
                        // zonder deze reset bleef de VORIGE sensor's Start-/
                        // End-tijd zichtbaar totdat de nieuwe sensor zijn
                        // eerste live GATT-antwoord had gestuurd.
                        settings.clearCareSensAirSensorSession(slot)
                        settings.setSelectedSensor(slot, SensorType.CARESENS_AIR)
                        navController.navigate("$BASE_PAIRING/${SensorType.CARESENS_AIR.name}/${slot.name}") {
                            popUpTo(ROUTE_CARESENS_AIR_CHOICE) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(ROUTE_CARESENS_AIR_SCAN) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            CareSensAirScanScreen(
                onBack = { navController.popBackStack() },
                onScanned = { result ->
                    // 31/07/2026 (editor) — koppel-stap 1/4: identiteit +
                    // gekozen sensor bewaren, oude verbinding (bv. de
                    // BG-simulator) netjes stoppen zodat het statusscherm
                    // straks geen verouderde "Connected (...)"-tekst meer
                    // toont — zelfde drie stappen als hierboven bij
                    // onExistingSensor.
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    // 09/08/2026 (editor, RONDE 61) — zie DexcomG6SetupScreen's
                    // onConfirmed hieronder voor de volledige kdoc: navigate()
                    // nu binnen de launch{}, als laatste statement, i.p.v.
                    // erna/erbuiten — anders geen garantie dat de
                    // settings.saveCareSensAirScan(slot)-schrijf al voltooid is
                    // tegen de tijd dat PairingScreen 'm leest.
                    scope.launch {
                        settings.clearDeviceAddress(slot)
                        // 28/08/2026 (editor, RONDE 154, CRITIEKE FIX) — zie
                        // AppSettings.clearCareSensAirSensorSession()'s kdoc/
                        // de identieke reset hierboven bij onExistingSensor.
                        settings.clearCareSensAirSensorSession(slot)
                        settings.setSelectedSensor(slot, SensorType.CARESENS_AIR)
                        settings.saveCareSensAirScan(slot, result)
                        // 31/07/2026 (editor) — koppel-stap 2/4: de barcode
                        // bevat geen BLE-MAC-adres (zie het echte sensor-
                        // etiket), dus hierna naar het bestaande, generieke
                        // koppelscherm (ui/PairingScreen.kt) —
                        // CareSensAirDriver.startPairing() filtert de
                        // BLE-scan daar al op de standaard Glucose Service,
                        // dus alleen relevante apparaten verschijnen in de
                        // lijst. popUpTo dit scanscherm: terug-knop vanuit
                        // de koppellijst hoeft niet terug naar een al
                        // afgeronde scan.
                        navController.navigate("$BASE_PAIRING/${SensorType.CARESENS_AIR.name}/${slot.name}") {
                            popUpTo(ROUTE_CARESENS_AIR_SCAN) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 08/08/2026 (editor, RONDE 55) — zie DexcomG6SetupScreen.kt's kdoc:
        // zelfde drie opruimstappen + popUpTo-patroon als
        // ROUTE_CARESENS_AIR_SCAN hierboven, alleen saveDexcomG6TransmitterId
        // i.p.v. saveCareSensAirScan.
        //
        // 09/08/2026 (editor, RONDE 61, op verzoek — gebruiker stuurde een
        // screenshot van "Pair Dexcom G6" met een VOLLEDIG ongefilterde
        // lijst ruwe MAC-adressen, geen "Show all nearby devices"-schakelaar
        // zichtbaar en geen "DexcomX7" te zien, terwijl een tweede
        // screenshot van Android's EIGEN Bluetooth-instellingen datzelfde
        // toestel wél gewoon als "DexcomX7" toonde) — root cause: navigate()
        // stond hieronder NA de `scope.launch { ... settings.
        // setDexcomG6TransmitterId(...) }` — dus BUITEN die coroutine. Een
        // niet-suspend `navController.navigate()`-aanroep loopt synchroon
        // meteen door, zonder op de launch{} te wachten; DataStore-schrijven
        // is asynchrone I/O. PairingScreen's `LaunchedEffect(sensorType) {
        // pairingFilter = driver.buildPairingListFilter(context) }` — die
        // vrijwel meteen bij het openen van het scherm draait — riep dus
        // `getDexcomG6TransmitterIdOnce()` aan met een reële kans dat de
        // zojuist ingevoerde transmitter-ID nog niet geschreven was. Die
        // functie geeft dan null terug, `buildPairingListFilter()` geeft
        // vervolgens ook null terug, en PairingScreen toont dan
        // `foundDevices` VOLLEDIG ongefilterd. Fix: `navigate()` nu ALS
        // LAATSTE statement BINNEN dezelfde `scope.launch { ... }` — suspend-
        // functies binnen één coroutine lopen gegarandeerd op volgorde, dus
        // de DataStore-schrijf is altijd voltooid vóórdat de navigatie (en
        // daarmee PairingScreen's lezing) plaatsvindt. Zelfde fix toegepast
        // op de twee CareSens Air-varianten hierboven.
        composable(ROUTE_DEXCOM_G6_SETUP) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            DexcomG6SetupScreen(
                onBack = { navController.popBackStack() },
                onConfirmed = { transmitterId ->
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch {
                        settings.clearDeviceAddress(slot)
                        settings.setSelectedSensor(slot, SensorType.DEXCOM_G6)
                        settings.setDexcomG6TransmitterId(slot, transmitterId)
                        navController.navigate("$BASE_PAIRING/${SensorType.DEXCOM_G6.name}/${slot.name}") {
                            popUpTo(ROUTE_DEXCOM_G6_SETUP) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 17/08/2026 (editor, RONDE 112) — zelfde drie opruimstappen +
        // popUpTo-patroon + "navigate() als laatste statement BINNEN de
        // launch{}"-fix als ROUTE_DEXCOM_G6_SETUP hierboven (zie de
        // uitgebreide kdoc daar/bij de CareSens Air-varianten voor de
        // volledige DataStore-timing-analyse), hier met
        // setDexcomG7PairingCode i.p.v. setDexcomG6TransmitterId.
        composable(ROUTE_DEXCOM_G7_SETUP) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            DexcomG7SetupScreen(
                onBack = { navController.popBackStack() },
                onConfirmed = { pairingCode ->
                    stopBleConnectionService(context)
                    ConnectionStatusBridge.update(slot, ConnectionState.Disconnected)
                    scope.launch {
                        settings.clearDeviceAddress(slot)
                        settings.setSelectedSensor(slot, SensorType.DEXCOM_G7)
                        settings.setDexcomG7PairingCode(slot, pairingCode)
                        // 28/08/2026 (editor, RONDE 152) — een nieuwe koppelcode
                        // betekent (vrijwel altijd) een NIEUWE fysieke sensor;
                        // zonder deze reset zou het statusscherm de batterij-/
                        // firmwaregegevens van de VORIGE sensor blijven tonen,
                        // en zou een nieuwe firmware-uitvraag tot 30 dagen
                        // uitgesteld blijven — zie AppSettings.
                        // clearDexcomG7BatteryAndFirmwareInfo's kdoc.
                        settings.clearDexcomG7BatteryAndFirmwareInfo(slot)
                        navController.navigate("$BASE_PAIRING/${SensorType.DEXCOM_G7.name}/${slot.name}") {
                            popUpTo(ROUTE_DEXCOM_G7_SETUP) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(ROUTE_SIMULATOR_SETUP) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            SimulatorSetupScreen(
                slot = slot,
                onDone = {
                    navController.navigate(ROUTE_COMBI) {
                        popUpTo(ROUTE_COMBI) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(ROUTE_PAIRING) { backStackEntry ->
            val slot = slotArg(backStackEntry)
            val sensorTypeName = backStackEntry.arguments?.getString("sensorType")
            val sensorType = sensorTypeName?.let {
                runCatching { SensorType.valueOf(it) }.getOrNull()
            } ?: SensorType.CARESENS_AIR
            PairingScreen(
                sensorType = sensorType,
                slot = slot,
                onPaired = {
                    navController.navigate(ROUTE_COMBI) {
                        popUpTo(ROUTE_COMBI) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
