package com.fclglucolink.app.sensor.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fclglucolink.app.MainActivity
import com.fclglucolink.app.alarm.AlarmMonitor
import com.fclglucolink.app.alarm.AlarmSoundPlayer
import com.fclglucolink.app.broadcast.XDripBroadcaster
import com.fclglucolink.app.calibration.CalibrationStore
import com.fclglucolink.app.calibration.computeCalibration
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.data.GlucoseReadingStore
import com.fclglucolink.app.data.SensorSwitchEventStore
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorDriver
import com.fclglucolink.app.sensor.SensorRegistry
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.simulator.PersistedSimulatorMode
import com.fclglucolink.app.sensor.simulator.SimulatorControlBridge
import com.fclglucolink.app.sensor.simulator.readMmolValuesFromUri
import com.fclglucolink.app.smoothing.KalmanSmoother
import com.fclglucolink.app.ui.formatForDisplayWithUnit
import com.fclglucolink.app.ui.mmolToMgdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp

/**
 * ============================================================================
 * FCLGlucoLink — BLE-koppelservice
 * ============================================================================
 *
 * 30/07/2026 (editor) — draait de actief gekozen SensorDriver los van de UI-
 * levenscyclus (foreground service, blijft dus ook door als het scherm dicht
 * is). Bewust GEEN sensor-specifieke code hier — deze service kent alleen
 * het SensorDriver-contract, precies zodat CareSens Air/G7/Accu-Chek elkaar
 * hier nooit in de weg zitten.
 *
 * Elke ontvangen meting gaat naar TWEE plekken, allebei hier en nergens
 * anders getriggerd (single source of truth, geen dubbele broadcasts vanuit
 * losse UI-code):
 *  1. GlucoseReadingStore — lokale opslag voor het status-/grafiekscherm.
 *  2. XDripBroadcaster — de daadwerkelijke koppeling naar AAPS.
 *
 * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur, op verzoek: "ik wil
 * nu verder met de 2 sensoren architectuur") — TOT vandaag hield deze klasse
 * precies ÉÉN actieve driver/connectie bij (activeDriver/connectionJob/
 * activeSensorType/activeDeviceAddress/smoother/lastConnectionState/
 * latestReadingNotificationText waren allemaal losse velden van de service
 * zelf). Vanaf vandaag: twee volledig onafhankelijke [SlotRuntime]-instanties
 * (SensorSlot.A/B, zie SensorDriver.kt's kdoc bij die enum) — elk met zijn
 * eigen driver/job/smoother/status, zodat bv. Dexcom G6 in Slot A en CareSens
 * Air in Slot B (of straks 2x dezelfde G6-transmitter tijdens een sensor-
 * wissel-overlap) daadwerkelijk GELIJKTIJDIG kunnen draaien zonder elkaar in
 * de weg te zitten. Welke van de twee (indien enige) naar AAPS zendt is een
 * aparte, wisselbare keuze (AppSettings.aapsActiveSlot, nullable — "ze
 * moeten ook beiden uit kunnen") die HIER wordt gecontroleerd vlak vóór de
 * broadcast, niet gekoppeld aan de slot-identiteit zelf. Zie AppSettings.kt's
 * kdoc bij de dual-slot-herschrijving voor de volledige achtergrond.
 *
 * Alle onderstaande kdoc's uit rondes 30 t/m 66 (mutex, stillWorking-check,
 * firstReadingThisSession, wakelock) blijven **ongewijzigd van toepassing**
 * — ze golden voorheen per service-instantie (er was toch maar één slot),
 * en gelden nu identiek PER SLOT (elke SlotRuntime doorloopt exact dezelfde
 * logica, onafhankelijk van de andere).
 *
 * 30/07/2026 (editor, na feedback: "moet 15 dagen onbeheerd door kunnen
 * draaien") — gevonden bug: als Android dit proces een keer stopt
 * (geheugendruk, of op sommige toestellen agressief batterijbeheer ondanks
 * de foreground-service-status — zie README) en START_STICKY 'm daarna
 * herstart, komt hier een VERSE SimulatorDriver terecht die keurig weer
 * naar commando's luistert, maar er komt er geen meer: het vorige
 * "genereer willekeurige/afgesproken waarden"-commando was een eenmalig
 * signaal vanuit het setup-scherm, niet iets dat vanzelf herhaalt. Resultaat
 * was precies het gerapporteerde symptoom: de app "leeft" nog (tijd-tekst
 * loopt door), maar er komt geen nieuwe data meer bij AAPS binnen.
 * resumeSimulatorIfNeeded() (onderaan) leest na elke connect() de laatst
 * actieve modus terug uit AppSettings en stuurt die zo nodig automatisch
 * opnieuw — zie ook PersistedSimulatorMode's kdoc.
 *
 * 10/08/2026 (editor, RONDE 79 — bekende beperking, NIET vandaag opgelost,
 * zie taak #312) — SimulatorControlBridge.commands/replayState zijn nog
 * altijd EEN globale, niet-slot-bewuste bridge (zie dat bestand's kdoc): als
 * beide slots tegelijk de simulator draaien, ontvangen ALLEBEI de
 * SimulatorDriver-instanties elk verstuurd commando. Onschadelijk zolang er
 * maar één slot tegelijk de simulator gebruikt (het huidige, enige geteste
 * scenario); wordt pas een echt probleem zodra de combi-UI-tests uit taak
 * #312 daadwerkelijk BEIDE slots met de simulator willen aansturen — dan
 * moet deze bridge ook per-slot gemaakt worden, net als ConnectionStatusBridge
 * vandaag al is geworden.
 *
 * 30/07/2026 (editor, kritieke bugfix na feedback: "3x dezelfde Bg-waarde
 * per update") — `onStartCommand()` wordt door Android bij ELKE aanroep van
 * `startService()`/`startForegroundService()` opnieuw uitgevoerd, ook als de
 * service al draait (MainActivity's herstart-check en het simulator-setup-
 * scherm roepen dat allebei aan). De vorige versie maakte dan gewoon een
 * NIEUWE SensorDriver aan zonder de oude eerst af te breken — beide (of
 * drie, bij een derde aanroep) dreven dan tegelijk door, luisterden
 * allemaal op dezelfde SimulatorControlBridge.commands, en schreven dus
 * allemaal onafhankelijk van elkaar naar opslag + AAPS-broadcast zodra er
 * één commando binnenkwam (zeker sinds resumeSimulatorIfNeeded() dat
 * commando nu ook nog actief herhaalt bij elke connect-poging). Vandaar de
 * 2-3 verschillende waarden op exact hetzelfde tijdstip. Fix: connectionJob
 * bijhouden en bij elke onStartCommand eerst de vorige driver+collectors
 * volledig afbreken vóór een nieuwe wordt opgezet — op elk moment is er dus
 * hoogstens één actieve driver (nu: hoogstens één actieve driver PER SLOT).
 *
 * 30/07/2026 (editor, na feedback: "lijkt of hij een nieuwe sensor start
 * bij het wisselen tussen AAPS en FCLGlucoLink") — bovenstaande fix loste de
 * TRIPLE-emissie op, maar de onderliggende oorzaak (onStartCommand() draait
 * ELKE keer de volledige teardown+heropbouw) bleef bestaan: Android mag een
 * backgrounded Activity op elk moment weggooien en later opnieuw aanmaken
 * (het proces + deze foreground service blijven gewoon draaien) — als de
 * gebruiker dan terugschakelt naar FCLGlucoLink, draait MainActivity's
 * LaunchedEffect(Unit) opnieuw, roept startBleConnectionService() aan, en
 * dat brak zonder enige echte noodzaak de actieve driver af en bouwde 'm
 * opnieuw op. Zichtbaar gevolg: een gloednieuwe SimulatorDriver-instantie
 * (met een verse `System.currentTimeMillis()`-starttijd), dus "Started"
 * sprong telkens naar "nu". Nu: activeSensorType/activeDeviceAddress
 * bijgehouden (per slot), en als die al overeenkomen met wat er aangevraagd
 * wordt EN de connectie nog actief is, gebeurt er simpelweg niets — geen
 * overbodige disconnect/reconnect (voor een echte sensor straks ook geen
 * onnodige BLE-herverbinding). De starttijd zelf staat sowieso niet meer in
 * het driver-object maar in AppSettings — zie getOrInitSensorStartedAtMs().
 *
 * 30/07/2026 (editor, na feedback: "update onregelmatig i.p.v. iedere 5
 * minuten met scherm dicht") — de simulator-replay/random-walk-lussen in
 * SimulatorDriver.kt draaien op `delay(intervalMs)` binnen een gewone
 * coroutine. Zo'n `delay()` is een monotone timer, geen alarm: als de CPU
 * van het toestel in slaap valt (scherm lang uit, Doze/App Standby, of een
 * fabrikant-specifiek "diepe slaap"-batterijbeheer bovenop kaal Android —
 * zie de eerdere README-notitie over Samsung/Xiaomi/Huawei), wordt de
 * onderliggende thread simpelweg niet gewekt op het geplande moment; de
 * `delay()` vuurt dan pas (veel) LATER af, zodra er toevallig weer iets de
 * CPU wekt. Precies het gerapporteerde patroon: eerst een tijdlang keurig
 * om de 5 minuten, dan een gat van 1-2+ uur, dan weer een korte reeks op
 * tijd. Een foreground-service-notificatie alleen is hier niet genoeg
 * tegen — dat voorkomt dat Android het PROCES stopt, niet dat de CPU in
 * slaap valt tussen scheduled werk door.
 *
 * Fix (destijds): een PARTIAL_WAKE_LOCK vastgehouden zolang deze service
 * draait (aangevraagd in onCreate, losgelaten in onDestroy) — dat hield de
 * CPU expliciet wakker, ook met het scherm uit, zodat `delay()`-timers wél
 * op hun geplande moment afgingen.
 *
 * 11/08/2026 (editor, RONDE 89 — op verzoek, na live-melding: "beduidend
 * sneller leeg lopen van de batterij... hoog batterijverbruik") — die
 * permanente wakelock (`acquire(20 dagen)`, nooit tussentijds losgelaten)
 * is vervangen door [ActiveWorkWakeLock] (zie dat bestand's kdoc voor de
 * volledige analyse): sinds Ronde 36 (04/08/2026) loopt de daadwerkelijke
 * herverbind-timing niet meer via kale `delay()`-wachttijden maar via
 * `PredictiveReconnectAlarm` (`AlarmManager.setExactAndAllowWhileIdle()`),
 * die de CPU zelf op tijd wekt, Doze of niet — de permanente wakelock loste
 * dus een probleem op dat voor het reguliere sensorpad allang anders is
 * opgelost, en hield de CPU intussen 24/7 wakker terwijl een volle dag
 * diagnostic-log laat zien dat maar ~10-15% van elke cyclus daadwerkelijk
 * actief BLE-werk is. Nieuw: geen wakelock tijdens de wachtperiodes, alleen
 * kort (zelf-verlopend, ruim boven het gedocumenteerde worst-case scanpad)
 * vastgehouden vlak vóór en tijdens het daadwerkelijke scanwerk, aangeroepen
 * vanuit beide drivers' `scheduleScanAttempt()`.
 */
class BleConnectionService : Service() {

    private var serviceJob: Job = SupervisorJob()
    private lateinit var scope: CoroutineScope

    private lateinit var settings: AppSettings
    private lateinit var readingStore: GlucoseReadingStore
    private lateinit var calibrationStore: CalibrationStore
    // 09/08/2026 (editor, RONDE 64) — zie SensorSwitchEventEntity.kt's kdoc.
    private lateinit var switchEventStore: SensorSwitchEventStore
    // 11/08/2026 (editor, RONDE 89) — verplaatst naar [ActiveWorkWakeLock];
    // zie de klasse-kdoc hierboven en dat bestand's kdoc.

    /**
     * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — per-slot
     * tegenhanger van wat vóór vandaag de losse activeDriver/connectionJob/
     * activeSensorType/activeDeviceAddress/lastConnectionState/
     * latestReadingNotificationText-velden van deze klasse waren, plus een
     * eigen [KalmanSmoother]-instantie per slot (06/08/2026, RONDE 49) —
     * elke slot is een fysiek andere sensor met zijn eigen meetruis/
     * toestand, dus mag het filter van de ene slot nooit meelopen met de
     * metingen van de andere. [configuredSensorType] is bewust een los veld
     * van [activeSensorType]: dat laatste is alleen gezet zodra er
     * daadwerkelijk een driver/connectionJob voor draait (zie
     * ensureSlotConnected()'s stillWorking-vergelijking hieronder, exact
     * dezelfde rol als vóór vandaag), terwijl [configuredSensorType] gewoon
     * "welk sensortype staat er voor deze slot gekozen in AppSettings"
     * weergeeft, ook als er nog geen device-adres gekoppeld is — puur voor
     * een informatievere notificatietekst (refreshNotification()).
     */
    private class SlotRuntime {
        var driver: SensorDriver? = null
        var connectionJob: Job? = null
        var activeSensorType: SensorType? = null
        var activeDeviceAddress: String? = null
        var configuredSensorType: SensorType? = null
        var lastConnectionState: ConnectionState = ConnectionState.Disconnected
        var latestReadingNotificationText: String? = null
        val smoother = KalmanSmoother()
    }

    private val slotRuntimes: Map<SensorSlot, SlotRuntime> =
        SensorSlot.entries.associateWith { SlotRuntime() }

    /**
     * 09/08/2026 (editor, RONDE 59 — live-test toonde TWEE gelijktijdige
     * BluetoothGatt-verbindingen naar hetzelfde toestel, elk met een eigen
     * clientIf, beide tot en met een geslaagde auth-write, waarna de
     * transmitter blijkbaar in de war raakte en BEIDE meteen weer verbrak)
     * — root cause: `onStartCommand()` hieronder leest de settings
     * (suspend, DataStore-I/O) VOORDAT de `stillWorking`-check en de
     * daaropvolgende `driver`-vervanging plaatsvinden. Als Android (of
     * ConnectionWatchdog, of een dubbele `startBleConnectionService()`-
     * aanroep vanuit MainActivity's levenscyclus) `onStartCommand()` twee
     * keer kort na elkaar aanroept, launcht elke aanroep zijn EIGEN
     * coroutine op dezelfde `scope` — die lopen gelijktijdig, niet na
     * elkaar.
     *
     * Fix: deze Mutex serialiseert de volledige lees-check-vervang-reeks
     * voor BEIDE slots samen (één mutex, niet één per slot — de twee slots
     * hierdoor na elkaar i.p.v. gelijktijdig verwerken kost niets noemens-
     * waardigs, elke stap is een snelle DataStore-lezing, en voorkomt een
     * hele nieuwe klasse race-conditions tussen de twee slots onderling).
     */
    private val startCommandMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        serviceJob = SupervisorJob()
        scope = CoroutineScope(serviceJob)
        settings = AppSettings(this)
        readingStore = GlucoseReadingStore(this)
        calibrationStore = CalibrationStore(this)
        switchEventStore = SensorSwitchEventStore(this)
        createNotificationChannel()

        // 11/08/2026 (editor, RONDE 89) — zie de klasse-kdoc en
        // ActiveWorkWakeLock.kt's kdoc: maakt de onderliggende WakeLock
        // eenmalig aan, houdt 'm verder NIET permanent vast.
        ActiveWorkWakeLock.ensure(this)

        // 13/08/2026 (editor, RONDE 107 — de alarm-EVALUATIEMOTOR) — een
        // volledig LOSSTAANDE, eigen periodieke lus (zie AlarmMonitor.kt's
        // kdoc voor waarom bewust niet gekoppeld aan de readings-collectors
        // hierboven/hieronder) — raakt geen enkel bestaand veld/
        // scan-tijdstip aan, leest alleen (read-only) uit Room/DataStore.
        // `onCreate()` draait precies één keer per service-instantie, dus
        // deze lus wordt nooit per ongeluk dubbel gestart door een
        // hernieuwde `onStartCommand()`-aanroep (die BOVEN al met opzet
        // idempotent is via startCommandMutex, maar dat mechanisme is hier
        // niet eens nodig).
        val alarmMonitor = AlarmMonitor(this)
        scope.launch {
            while (isActive) {
                runCatching { alarmMonitor.checkOnce() }
                delay(AlarmMonitor.CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        // 09/08/2026 (editor, RONDE 59) — zie startCommandMutex's kdoc
        // hierboven: serialiseert de hele lees-check-vervang-reeks tussen
        // eventueel overlappende onStartCommand()-aanroepen, nu voor beide
        // slots ná elkaar binnen dezelfde lock.
        scope.launch {
            startCommandMutex.withLock {
                for (slot in SensorSlot.entries) {
                    ensureSlotConnected(slot)
                }
            }
            // Eén keer aan het eind: dekt zowel "een slot is niet
            // geconfigureerd" (geen losse statusregel meer nodig, zie
            // slotStatusText()) als de normale eerste-opstart-tekst af,
            // zonder te wachten op de eerste connectionState-emissie.
            refreshNotification()
        }
        // START_STICKY: Android mag de service bij geheugendruk stoppen, maar
        // moet 'm dan weer opstarten — een BLE-koppelservice die zomaar
        // wegblijft is erger dan een keer opnieuw opstarten.
        return START_STICKY
    }

    /**
     * 10/08/2026 (editor, RONDE 79) — precies de lees-check-vervang-launch-
     * reeks die vóór vandaag rechtstreeks in `onStartCommand()` stond, nu
     * geherbruikt per slot. Alle kdoc's uit rondes 33/59/66 (hieronder nog
     * steeds aanwezig bij de exacte regels waar ze bij hoorden) blijven
     * onverkort van toepassing — enige verschil is dat elke lezing/
     * vergelijking/toewijzing nu tegen de [SlotRuntime] van dít [slot] gaat
     * i.p.v. tegen service-brede velden. Ontbreekt een sensorType/adres voor
     * deze slot, dan is dat een normale "niets te doen"-situatie (geen
     * fout) — de andere slot kan prima wél actief zijn.
     */
    private suspend fun CoroutineScope.ensureSlotConnected(slot: SensorSlot) {
        val runtime = slotRuntimes.getValue(slot)

        val sensorType = settings.selectedSensor(slot).first()
        runtime.configuredSensorType = sensorType
        if (sensorType == null) return
        val deviceAddress = settings.deviceAddress(slot).first() ?: return

        // 01/08/2026 (editor, na live-test — CareSens Air-koppelpogingen
        // startten telkens een GLOEDNIEUWE driver, ook binnen dezelfde
        // app-sessie zonder dat de gebruiker iets deed) — de oude check
        // (`connectionJob?.isActive == true`) was een verkeerde proxy:
        // `connect()` op een SensorDriver is bewust NIET suspend (start
        // z'n eigen async werk en keert meteen terug), dus de
        // `launch { ... driver.connect(...) }`-job hierboven is bijna
        // METEEN weer "niet actief" nadat onStartCommand voor het eerst
        // liep — ook al is de driver zelf nog volop aan het scannen/
        // verbinden/herverbinden. Elke volgende onStartCommand (bv. omdat
        // MainActivity opnieuw opgebouwd wordt bij het wisselen tussen
        // apps/schermen — zie kdoc bij deze class) zag dus altijd
        // "niet actief" en brak de nog lopende driver af voor een verse,
        // waardoor sensor-interne sessiestatus (zoals CareSens Air's
        // AppID-afwijzings-geheugen, zie CareSensAirDriver.kt) steeds
        // kwijtraakte vóór hij ooit gebruikt kon worden. Nu: kijk naar de
        // ECHTE, actuele staat van de driver zelf.
        val existingDriver = runtime.driver
        val driverLooksHealthy = existingDriver?.connectionState?.value?.let {
            it is ConnectionState.Connected || it is ConnectionState.Connecting || it is ConnectionState.Scanning
        } == true
        // 09/08/2026 (editor, RONDE 66, na live-melding met screenshot —
        // een dichte cluster sensor-wisselmarkers + een steeds
        // afgeknotte grafiek, ondanks maar één echte G6-sensor) — root
        // cause: ConnectionWatchdog.kt herstart deze service
        // ONVOORWAARDELIJK elke 6 minuten (bedoeld als veiligheidsnet
        // voor een écht vastgelopen proces), met als aanname dat de
        // `stillWorking`-check hierboven een gezonde, actieve driver
        // altijd zou herkennen en dan niets doet. Die aanname klopte
        // niet voor de G6: DexcomG6Driver verbreekt de verbinding
        // BEWUST na elke geslaagde meting (zie die klasse's kdoc) en
        // rapporteert in die normale, bedoelde tussenperiode
        // `ConnectionState.Error` (zie updateConnectionStatusAfterDisconnect()'s
        // kdoc — gezet na ELKE disconnect, ook een geslaagde) — geen van
        // de drie waarden die `driverLooksHealthy` hierboven als "nog
        // bezig" telt.
        //
        // Fix: `connectionJob` zelf is een betrouwbaardere graadmeter dan
        // de ruwe ConnectionState — de twee geneste `launch { ...collect
        // {...} }`-blokken verderop (state + readings) zijn kind-taken
        // van deze job, en zo'n kind-`Flow.collect{}` op een StateFlow/
        // SharedFlow eindigt nooit vanzelf — dus `connectionJob` blijft
        // `isActive` zolang er geen crash was, ONGEACHT welke
        // ConnectionState de driver op dat moment toevallig rapporteert.
        // Bewust een OR met de bestaande ConnectionState-check (niet een
        // vervanging).
        val jobStillAlive = runtime.connectionJob?.isActive == true
        val stillWorking = driverLooksHealthy || jobStillAlive
        if (stillWorking && runtime.activeSensorType == sensorType && runtime.activeDeviceAddress == deviceAddress) {
            return
        }

        // Zie kdoc bij deze class — voorkomt meerdere gelijktijdig
        // actieve drivers/collectors PER SLOT (de andere slot's eigen
        // driver/job blijft hier volledig buiten schot).
        runtime.connectionJob?.cancel()
        runtime.driver?.disconnect()
        runtime.driver = null
        runtime.activeSensorType = sensorType
        runtime.activeDeviceAddress = deviceAddress

        runtime.connectionJob = launch {
            val driver = SensorRegistry.createDriver(sensorType, slot)
            runtime.driver = driver

            // Statusupdates -> notificatietekst + ConnectionStatusBridge (UI).
            // 06/08/2026 (editor, RONDE 53) — was rechtstreeks
            // `updateNotification(describe(state))`; nu via
            // refreshNotification() (zie kdoc verderop), zodat een
            // eenmaal binnengekomen laatste meting de kale statustekst
            // kan blijven overschrijven zolang de verbinding actief
            // blijft.
            launch {
                driver.connectionState.collect { state ->
                    runtime.lastConnectionState = state
                    refreshNotification()
                    ConnectionStatusBridge.update(slot, state)
                }
            }
            // Elke meting -> opslag + AAPS-broadcast. sensorStartedAtMs
            // komt altijd uit AppSettings (stabiel per gekozen sensor,
            // nu per slot), niet uit het driver-object zelf — zie
            // getOrInitSensorStartedAtMs()'s kdoc.
            //
            // 02/08/2026 (editor, controlevraag van de gebruiker: "bij
            // een normale sensor wissel heeft de nieuwe sensor amper
            // historische data ... hij zou dan alleen de data uit het
            // geheugen moeten wissen vanaf het tijdstip van de eerste
            // nieuwe sensor waarde, zodat de historie wel zichtbaar
            // blijft") — dit `connectionJob`-blok (en dus deze
            // `readings.collect`) start alleen opnieuw bij een
            // daadwerkelijk NIEUWE sensor-/apparaatkeuze VOOR DEZE SLOT
            // (zie de early-return hierboven bij eenzelfde
            // sensorType+adres), dus `firstReadingThisSession` bakent
            // precies één sessie af. Bij de EERSTE meting van die sessie
            // ruimen we eerst alles vanaf dat tijdstip op, MAAR ALLEEN
            // VOOR DIT SENSORTYPE (zie GlucoseReadingStore.trimFrom()'s
            // kdoc, RONDE 79 — anders zou een trim op deze slot ook de
            // nog geldige, gelijktijdig lopende historie van de ANDERE
            // slot wegvegen) — de oudere, nog geldige historie blijft
            // gewoon staan voor een naadloze aansluiting in de grafiek.
            var firstReadingThisSession = true
            launch {
                driver.readings.collect { reading ->
                    val startedAtMs = settings.getOrInitSensorStartedAtMs(slot)
                    val stableReading = reading.copy(sensorStartedAtMs = startedAtMs)
                    if (firstReadingThisSession) {
                        firstReadingThisSession = false
                        // 09/08/2026 (editor, RONDE 66, na live-melding
                        // met screenshot — een dichte cluster
                        // sensor-wisselmarkers + een steeds afgeknotte
                        // grafiek) — zelfde bug-categorie als ronde 46's
                        // fix bij calibrationStore.clearAll() hieronder:
                        // `firstReadingThisSession` is een in-memory
                        // vlaggetje dat niet alleen bij een ECHTE nieuwe
                        // sensor op true staat, maar ook bij elke
                        // (voorheen te gretige, zie de `stillWorking`-fix
                        // hierboven) onnodige driver-herstart. Trim +
                        // wisselmarker horen alleen te gebeuren als dit
                        // device-adres daadwerkelijk NIEUW is t.o.v. de
                        // vorige keer dat deze twee al zijn uitgevoerd —
                        // net als bij kalibratie hieronder, persistent
                        // bijgehouden PER SLOT.
                        val alreadyHandledForThisDevice =
                            settings.getSensorSessionStartedForDeviceAddressOnce(slot) == deviceAddress
                        if (!alreadyHandledForThisDevice) {
                            readingStore.trimFrom(stableReading.timestampMs, sensorType)
                            // 09/08/2026 (editor, RONDE 64, op verzoek: "een
                            // sensor wissel icoontje op de grafiek [...] wat
                            // dan bv binnen het zelfde sensor type minder
                            // opvallend van kleur is en bij een sensortype
                            // wissel een wat opvallende kleur heeft") — zelfde
                            // moment als trimFrom() hierboven (eerste meting
                            // van een nieuwe sensor-sessie). consumePending
                            // CrossTypeSwitch() geeft true terug precies
                            // wanneer AppSettings.setSelectedSensor() sinds de
                            // vorige sessie VAN DEZE SLOT een ECHTE
                            // type-wissel zag (G6 <-> CareSens <-> Simulator)
                            // — anders (bv. gewoon een nieuwe G6-sensorcode of
                            // een nieuwe transmitter binnen hetzelfde type)
                            // false, dus de subtiele kleur. Zie
                            // SensorSwitchEventEntity.kt's kdoc voor het
                            // volledige verhaal.
                            //
                            // 10/08/2026 (editor, RONDE 84, BUGFIX) — `sensorType`
                            // hier nu meegegeven (zie SensorSwitchEventEntity.kt's
                            // RONDE-84-kdoc): zonder dit kon een wisselmarker van
                            // deze slot niet onderscheiden worden van de andere
                            // slot's markers, en verscheen 'm dus op BEIDE
                            // tabbladen's grafiek.
                            switchEventStore.record(
                                timestampMs = stableReading.timestampMs,
                                crossType = settings.consumePendingCrossTypeSwitch(slot),
                                sensorType = sensorType
                            )
                            settings.setSensorSessionStartedForDeviceAddress(slot, deviceAddress)
                        }
                        // 05/08/2026 (editor, RONDE 43, op verzoek: "bij
                        // iedere sensor wissel moet de kalibratie bij de
                        // vorige sensor behorende gegevens uiteraard wel
                        // gewist worden")
                        //
                        // 06/08/2026 (editor, RONDE 46, BUGFIX na
                        // live-melding: "de kalibratie data is nu niet
                        // persistent over een app update ... of een
                        // telefoon herstart") — alleen legen als het
                        // device-adres AFWIJKT van waarvoor de laatste keer
                        // al geleegd is (persistent bijgehouden PER SLOT,
                        // overleeft dus zelf ook een herstart) — dat adres
                        // verandert alleen bij een ECHTE nieuwe koppeling
                        // (ander fysiek BLE-apparaat), niet bij gewoon
                        // opnieuw verbinden met dezelfde sensor.
                        //
                        // 10/08/2026 (editor, RONDE 79) — `calibrationStore.
                        // clearAll()` wist voorheen de HELE tabel (er was
                        // toch maar één actieve sensor); nu VERPLICHT
                        // gescoped op [sensorType] (zie CalibrationStore.kt's
                        // kdoc) — anders zou een nieuwe-sensor-detectie op
                        // deze slot ook de nog geldige kalibratiedata van een
                        // gelijktijdig actieve ANDERE slot wegvegen, exact
                        // dezelfde bugklasse als GlucoseReadingStore hierboven.
                        val alreadyClearedForThisDevice =
                            settings.getCalibrationClearedForDeviceAddressOnce(slot) == deviceAddress
                        if (!alreadyClearedForThisDevice) {
                            // 11/08/2026 (editor, RONDE 90 — op verzoek: één
                            // gedeelde vingerprik-database tussen beide
                            // slots) — `calibrationStore.clearAll(sensorType)`
                            // stond hier voorheen: bij elke nieuwe fysieke
                            // sensor-sessie werd de kalibratiedata van het
                            // VORIGE sensortype volledig gewist. Met een
                            // gedeelde rij per vingerprik (i.p.v. impliciet
                            // "eigendom" van precies één sensor) zou dat nu
                            // een vingerprik kunnen wegvegen die de ANDERE,
                            // gelijktijdig actieve slot nog gebruikt — zie
                            // CalibrationStore.kt's klasse-kdoc. Vervangen
                            // door een pure tijdfilter aan de leeskant
                            // (CalibrationScreen.kt filtert al op
                            // `timestampMs >= sensorStartedAtMs` van DEZE
                            // sessie): een oude vingerprik van vóór deze
                            // sensorwissel wordt zo simpelweg niet meer
                            // OPGEHAALD, zonder 'm te hoeven wissen — geen
                            // functieverlies voor deze sessie, wel behoud
                            // voor de andere slot.
                            settings.setCalibrationClearedForDeviceAddress(slot, deviceAddress)
                            // 06/08/2026 (editor, RONDE 49) — zelfde
                            // moment als de kalibratie-leging hierboven:
                            // een daadwerkelijk nieuwe fysieke sensor
                            // start ook met een vers smoothing-filter
                            // (nu: dit slot's EIGEN smoother-instantie),
                            // zodat de oude sensor's geleerde meetruis/
                            // toestand niet blijft doorwerken op de
                            // eerste metingen van de nieuwe sensor.
                            runtime.smoother.reset()
                            // 06/08/2026 (editor, RONDE 53) — zelfde
                            // moment: een eventueel nog getoonde waarde
                            // van de VORIGE fysieke sensor hoort niet in
                            // de notificatie te blijven hangen terwijl er
                            // op de nieuwe nog niets binnen is.
                            runtime.latestReadingNotificationText = null
                        }
                    }
                    // 05/08/2026 (editor, RONDE 43) — kalibratie toepassen
                    // vóór opslag/broadcast, zodat de gekalibreerde
                    // waarde overal "telt" (thuisscherm, grafiek, én de
                    // xDrip-broadcast naar AAPS) — zie
                    // GlucoseReading.rawSensorMgdl's kdoc voor hoe de
                    // ruwe waarde ernaast bewaard blijft voor de UI.
                    //
                    // 06/08/2026 (editor, RONDE 49) — smoothing volgt HIERNA,
                    // niet ervoor: zie smoothing/KalmanSmoother.kt's kdoc
                    // en het overleg dat daaraan voorafging (bevestigd
                    // door de gebruiker: "Doe inderdaad maar eerst de
                    // calibratie en dan de smoothing") — het filter ziet
                    // dus steeds de al-gekalibreerde waarde, nooit de
                    // ruwe sensorwaarde, precies zoals AAPS's eigen UKF
                    // (`calibratedOrValue`) dat ook doet.
                    val calibratedReading = applyCalibrationIfEnabled(stableReading, sensorType, slot)
                    val smoothedReading = applySmoothingIfEnabled(calibratedReading, runtime.smoother, slot, sensorType)
                    // Lokale opslag (status-/grafiekscherm) gebeurt
                    // ALTIJD; alleen de broadcast naar AAPS zelf is
                    // voorwaardelijk.
                    //
                    // 10/08/2026 (editor, RONDE 79) — vervangt de oude,
                    // globale `settings.isBroadcastEnabled()`-schakelaar:
                    // op uitdrukkelijk verzoek ("beide slots moeten kunnen
                    // zenden naar aaps waarbij er uiteraard maar max 1
                    // actief kan zijn, maar ze moeten ook beiden uit
                    // kunnen") is er nu een nullable AAPS_ACTIVE_SLOT —
                    // deze slot zendt alleen als hij daadwerkelijk de
                    // gekozen actieve slot is; `null` (of de ANDERE slot)
                    // betekent gewoon niet zenden, zonder dat dat de lokale
                    // opslag/UI voor deze slot raakt.
                    readingStore.record(smoothedReading)
                    if (settings.getAapsActiveSlotOnce() == slot) {
                        // 20/08/2026 (editor, RONDE 115) — zie
                        // XDripBroadcaster.kt's kdoc bij sourceInfo(): AAN ->
                        // dezelfde AAPS-v3+v4-vertrouwde code voor elke
                        // sensor, UIT -> de bestaande per-sensor omschrijving.
                        val universalSourceCode = settings.isXdripUniversalSourceCodeEnabledOnce()
                        XDripBroadcaster.broadcast(this@BleConnectionService, smoothedReading, universalSourceCode)
                    }
                    // 06/08/2026 (editor, RONDE 53) — zie
                    // refreshNotification()'s kdoc verderop: laat de
                    // notificatie voortaan de laatste BG-waarde tonen
                    // i.p.v. alleen de kale verbindingsstatus.
                    // 13/08/2026 (editor, RONDE 104, Fase 1) — zie ui/Units.kt's
                    // [GlucoseUnit]-kdoc: notificatietekst volgt voortaan de
                    // gekozen weergave-eenheid i.p.v. hardcoded mmol/L.
                    runtime.latestReadingNotificationText =
                        smoothedReading.glucoseMgdl.formatForDisplayWithUnit(settings.getDisplayUnitOnce())
                    refreshNotification()
                }
            }

            driver.connect(this@BleConnectionService, deviceAddress)

            // Zie kdoc bij deze class — alleen relevant voor de
            // simulator, echte sensoren hebben hier geen "hervat
            // commando" nodig (die scannen/verbinden vanuit connect()
            // zelf opnieuw).
            if (sensorType == SensorType.SIMULATOR) {
                resumeSimulatorIfNeeded(slot)
            }
        }
    }

    override fun onDestroy() {
        for (runtime in slotRuntimes.values) {
            runtime.connectionJob?.cancel()
            runtime.driver?.disconnect()
            runtime.driver = null
            runtime.activeSensorType = null
            runtime.activeDeviceAddress = null
        }
        serviceJob.cancel()
        // 11/08/2026 (editor, RONDE 89) — zie ActiveWorkWakeLock.kt's kdoc.
        ActiveWorkWakeLock.releaseAll()
        // 13/08/2026 (editor, RONDE 107) — deze service stopt zelden (zie
        // android:stopWithTask="false"), maar als het toch gebeurt terwijl
        // er net een alarm klinkt, moet dat geluid niet eindeloos
        // doorspelen zonder dat AlarmMonitor.kt er nog iets aan kan doen
        // (die lus stopt immers mee met `serviceJob.cancel()` hierboven).
        AlarmSoundPlayer.stop()
        super.onDestroy()
    }

    /**
     * Zie kdoc bij deze class. Leest de laatst actieve simulator-modus terug
     * (AppSettings.readActiveSimulatorMode(slot)) en stuurt 'm zo nodig
     * opnieuw naar SimulatorControlBridge — de SimulatorDriver die net via
     * driver.connect() is opgezet luistert daar al op. Bewust GEEN commando
     * sturen als de modus None is (bv. gebruiker had zelf op Stop gedrukt) —
     * dan moet de simulator na een herstart ook stil blijven staan, niet
     * vanzelf weer beginnen.
     *
     * List-replay's waarden staan niet in de opgeslagen modus zelf — die
     * worden hier opnieuw uit AppSettings.externalListUri(slot) gelezen
     * (dezelfde bestandslees-helper als het setup-scherm, zie
     * sensor/simulator/SimulatorListFile.kt), zodat een ondertussen
     * gewijzigd bestand altijd de actuele inhoud gebruikt. Als het bestand
     * niet meer leesbaar is (bv. verplaatst/verwijderd), gebeurt er simpelweg
     * niets — geen crash, de gebruiker moet dan zelf opnieuw een bestand
     * kiezen via het setup-scherm.
     *
     * 10/08/2026 (editor, RONDE 79) — zie kdoc bij deze class: het commando
     * zelf gaat nog via de niet-slot-bewuste SimulatorControlBridge, dus dit
     * werkt vandaag alleen correct als er maar één slot tegelijk de
     * simulator gebruikt (het huidige, geteste scenario).
     */
    private suspend fun resumeSimulatorIfNeeded(slot: SensorSlot) {
        when (val mode = settings.readActiveSimulatorMode(slot)) {
            is PersistedSimulatorMode.None -> {}
            is PersistedSimulatorMode.Repeat ->
                SimulatorControlBridge.startRepeating(mode.glucoseMgdl, mode.intervalMs)
            is PersistedSimulatorMode.RandomWalk ->
                SimulatorControlBridge.startRandomWalk(mode.intervalMs)
            is PersistedSimulatorMode.ListReplay -> {
                val uriString = settings.externalListUri(slot).first() ?: return
                val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
                val mgdlValues = runCatching { readMmolValuesFromUri(this, uri) }
                    .getOrDefault(emptyList())
                    .map { it.mmolToMgdl() }
                if (mgdlValues.isNotEmpty()) {
                    SimulatorControlBridge.startListReplay(mgdlValues, mode.intervalMs)
                }
            }
        }
    }

    /**
     * 05/08/2026 (editor, RONDE 43) — past CalibrationEngine.computeCalibration()
     * toe op één binnenkomende meting. `reading.glucoseMgdl` blijft ONgewijzigd
     * als kalibratie uitstaat (identity — geen enkele overhead/gedragswijziging
     * voor gebruikers die de functie niet gebruiken). Zodra aan: `glucoseMgdl`
     * wordt de gekalibreerde waarde, `rawSensorMgdl` blijft de ruwe sensorwaarde
     * (zie GlucoseReading.rawSensorMgdl's kdoc).
     *
     * 10/08/2026 (editor, RONDE 79) — [sensorType] erbij: `calibrationStore.
     * entriesOnce()` is nu verplicht gescoped (zie CalibrationStore.kt's
     * kdoc), zodat de fit-wiskunde van deze slot nooit vingerprik-data van
     * een gelijktijdig actieve ANDERE slot meeweegt.
     *
     * 10/08/2026 (editor, RONDE 80, op verzoek na live-test — kalibratie-
     * offset bleek bij zowel slot A als B gebruikt te worden) — [slot] erbij:
     * modus/handmatige-offset zijn nu ook per-slot (zie AppSettings.kt's
     * kdoc), dus die moeten hier ook gescoped opgevraagd worden. `enabled`
     * (aan/uit) blijft bewust globaal, geen apart verzoek daarvoor.
     *
     * 11/08/2026 (editor, RONDE 90 — gedeelde vingerprik-database) —
     * `entriesOnce()` vereist nu ook [sinceMs] (zie CalibrationStore.kt's
     * kdoc): dezelfde sensor-start-tijd van deze sessie die
     * CalibrationScreen.kt gebruikt voor de rijlijst/fit, zodat de LIVE
     * kalibratie die hier op elke binnenkomende meting wordt toegepast
     * nooit een ANDERE verzameling vingerprikken gebruikt dan wat de
     * gebruiker op dat scherm daadwerkelijk ziet/aangevinkt heeft.
     *
     * 18/08/2026 (editor, RONDE 113) — zet nu ook [GlucoseReading.
     * calibratedMgdl] (zie dat veld's kdoc): dezelfde waarde als
     * `glucoseMgdl` hieronder, maar dit veld overleeft de daaropvolgende
     * applySmoothingIfEnabled()-stap ongewijzigd, zodat StatusScreen.kt's
     * pipeline-regel raw/gekalibreerd/gefilterd alle drie tegelijk kan tonen.
     */
    private suspend fun applyCalibrationIfEnabled(reading: GlucoseReading, sensorType: SensorType, slot: SensorSlot): GlucoseReading {
        if (!settings.isCalibrationEnabled()) return reading
        val sinceMs = settings.getOrInitSensorStartedAtMs(slot)
        val entries = calibrationStore.entriesOnce(sensorType, sinceMs)
        val mode = settings.getCalibrationModeOnce(slot)
        val manualOffsetMgdl = settings.getCalibrationManualOffsetMmolOnce(slot).mmolToMgdl()
        val result = computeCalibration(
            sensorMgdl = reading.glucoseMgdl,
            entries = entries,
            mode = mode,
            manualOffsetMgdl = manualOffsetMgdl,
            now = reading.timestampMs
        )
        return reading.copy(
            glucoseMgdl = result.calibratedMgdl,
            rawSensorMgdl = reading.glucoseMgdl,
            calibratedMgdl = result.calibratedMgdl
        )
    }

    /**
     * 06/08/2026 (editor, RONDE 49) — past het Kalman-filter (KalmanSmoother.kt)
     * toe op één binnenkomende (al-gekalibreerde) meting. Zelfde identity-
     * patroon als applyCalibrationIfEnabled hierboven: `reading.glucoseMgdl`
     * blijft ONgewijzigd als smoothing uitstaat. `rawSensorMgdl` (de ruwe
     * sensorwaarde, al dan niet via kalibratie aangepast — zie
     * applyCalibrationIfEnabled) wordt hier bewust NIET aangeraakt: de UI's
     * "raw"-weergave (StatusScreen.kt, GlucoseChart.kt's open cirkel) moet de
     * ongefilterde meting blijven tonen, alleen de hoofdwaarde wordt gladgestreken.
     *
     * 10/08/2026 (editor, RONDE 79) — [smoother] komt nu van de aanroeper
     * (dit slot's eigen `runtime.smoother`) i.p.v. een enkel, service-breed
     * gedeeld filter — zie SlotRuntime's kdoc.
     *
     * 16/08/2026 (editor, RONDE 111) — geeft nu ook een [breakInDecayFactor]
     * mee aan de smoother, zie [computeBreakInDecayFactor]'s kdoc en
     * KalmanSmoother.kt's klasse-kdoc voor het volledige mechanisme.
     *
     * 18/08/2026 (editor, RONDE 113) — [GlucoseReading.calibratedMgdl] wordt
     * hier bewust NIET aangeraakt (net als `rawSensorMgdl` hierboven): dat
     * veld moet de gekalibreerde-maar-nog-niet-gladgestreken tussenwaarde
     * blijven, zie dat veld's kdoc.
     *
     * 18/08/2026 (editor, RONDE 114) — leest nu ook AppSettings.
     * smoothingStrength en geeft die door aan [KalmanSmoother.smooth] — zie
     * [SmoothingStrength]'s kdoc voor waarom dit, net als
     * [breakInDecayFactor], per aanroep vers gelezen wordt i.p.v. eenmalig in
     * de smoother gebakken: een wijziging in Settings werkt zo direct door
     * op de eerstvolgende meting.
     */
    private suspend fun applySmoothingIfEnabled(
        reading: GlucoseReading,
        smoother: KalmanSmoother,
        slot: SensorSlot,
        sensorType: SensorType
    ): GlucoseReading {
        if (!settings.isSmoothingEnabled()) return reading
        val breakInDecayFactor = computeBreakInDecayFactor(reading.timestampMs, slot, sensorType)
        val strength = settings.getSmoothingStrengthOnce()
        val output = smoother.smooth(reading.glucoseMgdl, reading.timestampMs, breakInDecayFactor, strength)
        return reading.copy(glucoseMgdl = output.glucoseMgdl)
    }

    /**
     * 16/08/2026 (editor, RONDE 111) — berekent de sterkte (0..1) van
     * KalmanSmoother.kt's inloop-demping op dit moment, voor deze slot.
     * `0.0` (geen extra demping) als de instelling uitstaat of de duur op 0
     * staat. Gebruikt bij voorkeur de sensortype-specifieke, al bestaande
     * "sessie gestart op"-tijdstippen (CareSens Air/Dexcom G6 — die worden
     * daadwerkelijk opnieuw gezet bij elke NIEUWE fysieke sensor, zie hun
     * eigen aanroepsites in CareSensAirDriver.kt/DexcomG6Driver.kt), met
     * [AppSettings.getOrInitSensorStartedAtMs] als vangnet voor sensortypes
     * zonder eigen sessie-tracking (de simulator) — die generieke sleutel
     * wordt alleen bij een sensor-TYPE-wissel gewist (zie
     * `setSelectedSensor()`'s kdoc), dus voor eenzelfde type is 'ie voor dit
     * doel minder precies, vandaar de voorkeur voor de type-specifieke
     * waarde waar die bestaat.
     */
    private suspend fun computeBreakInDecayFactor(nowMs: Long, slot: SensorSlot, sensorType: SensorType): Double {
        if (!settings.isBreakInFilterEnabledOnce()) return 0.0
        val durationHours = settings.getBreakInFilterDurationHoursOnce()
        if (durationHours <= 0.0) return 0.0

        val typeSpecificStartedAtMs = when (sensorType) {
            SensorType.CARESENS_AIR -> settings.careSensAirSensorStartedAtMs(slot).first()
            SensorType.DEXCOM_G6 -> settings.dexcomG6SessionStartConfirmedAtMs(slot).first()
            else -> null
        }
        val startedAtMs = typeSpecificStartedAtMs ?: settings.getOrInitSensorStartedAtMs(slot)

        val hoursSinceStart = (nowMs - startedAtMs) / 3_600_000.0
        if (hoursSinceStart < 0.0) return 1.0 // klok-scheefstand: veiligst aannemen dat de sensor net gestart is.

        val tau = durationHours / 5.0 // zie KalmanSmoother.kt's klasse-kdoc: na "duur" nog ~0,7% over.
        return exp(-hoursSinceStart / tau).coerceIn(0.0, 1.0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 06/08/2026 (editor, RONDE 53, na live-melding: "op screenshot [...]
     * staat, blauw omcirkeld, nu verbinden het is beter als daar de laatste
     * Bg waarde wordt vermeld") — de notificatietekst toonde voorheen
     * ALTIJD de kale verbindingsstatus ([describe]), ook lang nadat de
     * sensor allang verbonden was en gewoon metingen binnenkwamen — voor
     * een langdurig draaiende achtergrondservice is dat minder nuttig dan
     * gewoon de laatste BG-waarde kunnen aflezen zonder de app te hoeven
     * openen.
     *
     * 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — was één
     * enkele statustekst voor "de" verbinding; nu een samenvatting van BEIDE
     * slots (zie slotStatusText() hieronder), gescheiden door " · ".
     * Een slot zonder gekozen sensortype wordt gewoon weggelaten (geen
     * "Slot B: niets gekozen"-ruis) — als GEEN van beide iets gekozen heeft,
     * valt terug op de oorspronkelijke "geen sensor gekozen"-tekst.
     */
    private fun refreshNotification() {
        val parts = SensorSlot.entries.mapNotNull { slot -> slotStatusText(slot) }
        val text = if (parts.isEmpty()) {
            "No sensor chosen — open the app to choose one."
        } else {
            parts.joinToString("  ·  ")
        }
        updateNotification(text)
    }

    /** 10/08/2026 (editor, RONDE 79) — zie refreshNotification()'s kdoc.
     *  `null` als deze slot nog geen sensortype gekozen heeft — die slot
     *  wordt dan simpelweg niet in de notificatie getoond. */
    private fun slotStatusText(slot: SensorSlot): String? {
        val runtime = slotRuntimes.getValue(slot)
        val type = runtime.configuredSensorType ?: return null
        val readingText = runtime.latestReadingNotificationText
        val body = if (runtime.lastConnectionState is ConnectionState.Connected && readingText != null) {
            readingText
        } else {
            describe(runtime.lastConnectionState)
        }
        return "${type.displayName}: $body"
    }

    // 06/08/2026 (editor, RONDE 53) — deze en de twee stukken tekst in
    // createNotificationChannel() hieronder stonden nog in het Nederlands,
    // een gemiste plek bij ronde 88's "vertaal alle gebruikers-zichtbare
    // tekst naar het Engels" (notificatietekst zat kennelijk niet in die
    // sweep, want die staat niet in een Composable maar in een gewone
    // Kotlin-string in deze Service-klasse) — nu ook naar het Engels, voor
    // consistentie met de rest van de app.
    private fun describe(state: ConnectionState): String = when (state) {
        is ConnectionState.Disconnected -> "Not connected"
        is ConnectionState.Scanning -> "Searching for sensor…"
        is ConnectionState.Connecting -> "Connecting to ${state.deviceAddress}…"
        is ConnectionState.Connected -> "Connected (${state.deviceName ?: state.deviceAddress})"
        is ConnectionState.Error -> "Error: ${state.message}"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of the active CGM sensor connection(s)"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FCLGlucoLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "fclglucolink_ble_status"
        private const val NOTIFICATION_ID = 1
    }
}
