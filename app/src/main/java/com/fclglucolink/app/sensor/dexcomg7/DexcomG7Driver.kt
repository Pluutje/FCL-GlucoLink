package com.fclglucolink.app.sensor.dexcomg7

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorDriver
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.ble.AapsSlotSchedule
import com.fclglucolink.app.sensor.ble.ActiveWorkWakeLock
import com.fclglucolink.app.sensor.ble.BondLossRecovery
import com.fclglucolink.app.sensor.ble.PredictiveReconnectAlarm
import com.fclglucolink.app.sensor.ble.ScanRateLimiter
import com.fclglucolink.app.sensor.dexcomg6.DexcomG6CalibrationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G7/ONE+-driver (RONDE 112)
 * ============================================================================
 *
 * 17/08/2026 (editor, RONDE 112, op verzoek: "wil ik graag verder met de
 * verdere implementatie van de dexcom g7 [...] code zover in orde brengen
 * dat zodra ik er eentje krijg ik gelijk kan beginnen met testen") — mirror
 * van DexcomG6Driver.kt's scan/verbind/backoff/BondLossRecovery-skelet (zie
 * dat bestand voor de herkomst van dat deel), maar met een volledig ANDER
 * koppel-/authenticatieprotocol erbovenop: G6 gebruikt een vaste, uit de
 * transmitter-ID afgeleide AES-sleutel (DexcomG6Crypto.kt); de G7 gebruikt
 * een EC-J-PAKE-handshake (DexcomG7Crypto.kt) — zie dat bestand se kdoc voor
 * de wiskundige achtergrond en DexcomG7Protocol.kt voor de exacte bytes/
 * opcodes/UUID's, allebei geport van xDrip+'s `libkeks`/`g5model`/`cgm/dex/
 * g7`-packages (broncode door de gebruiker aangeleverd, `uploads/
 * xDrip-2026.08.08.zip`) en kruisgecheckt tegen Juggluco's eigen
 * `DexGattCallback.java` (`uploads/Juggluco.zip`).
 *
 * BEWUSTE VEREENVOUDIGING t.o.v. xDrip+'s `jamorham.keks.Plugin` (een
 * generieke, herbruikbare event-gedreven state machine): hier een simpele,
 * LINEAIRE `suspend`-functie ([runPairingHandshake]) die de rondes/stappen
 * strikt na elkaar doorloopt (ronde 1 versturen -> wachten op de sensor se
 * ronde-1-antwoord -> ronde 2 versturen -> ... -> auth-aanvraag -> uitdaging-
 * antwoord -> bond-indien-nodig -> glucose opvragen). De bytes/volgorde OP DE
 * LUCHT zijn identiek aan xDrip+'s eigen protocol — alleen de interne Kotlin-
 * besturingsstructuur is simpeler dan xDrip+'s generieke FSM, wat voor een
 * eerste implementatie tegen een sensor die de gebruiker nog niet heeft de
 * veiligste keuze is (makkelijker te doorgronden/debuggen dan een generieke
 * event-machine over te zetten). Zie klasse-kdoc-vervolg hieronder voor wat
 * hierdoor NIET geport is.
 *
 * NIET GEPORT (bewust, zie DexcomG7Crypto.kt/DexcomG7Protocol.kt se kdoc's
 * voor de volledige onderbouwing per stuk):
 * - De QR-code-certificaat-koppelroute (xDrip+'s `SendCertificate*`-staten)
 *   — alleen relevant als de PIN-route ondanks succesvolle authenticatie
 *   geen OS-bond oplevert; xDrip+'s eigen bron behandelt dit al als
 *   duidelijk secundair pad.
 * - Sessie-hervatting via een eerder opgeslagen gedeelde sleutel
 *   (`context.savedKey`, xDrip+'s "RoundStart -> meteen RequestAuth"-
 *   snelpad) — elke verbinding doet hier de volledige 3-ronde-handshake
 *   opnieuw. Puur een performance-optimalisatie in xDrip+, geen
 *   correctheids-vereiste; kan later toegevoegd worden zodra de kern
 *   bewezen werkt tegen echte hardware.
 * - Backfill-data (opcode 0x59) — wordt herkend maar de INHOUD nog niet
 *   geparsed, zie DexcomG7Protocol.kt's kdoc: zelfs xDrip+'s eigen bron
 *   heeft dit nog niet volledig uitgeplozen ("// TODO more to parse here").
 * - Batterij-/versie-polling — geen bevestigde G7-opcode hiervoor gevonden
 *   in het onderzochte materiaal; simpelweg weggelaten i.p.v. te gokken.
 * - Sessie starten/stoppen — bewust NIET nodig: xDrip+'s eigen G7-
 *   documentatie is expliciet dat een G7/ONE+-sessie automatisch start
 *   zodra de sensor fysiek ingebracht wordt, zonder appcommando (in
 *   tegenstelling tot G6, waar FCLGlucoLink wél een expliciet sessie-start-
 *   bericht met fabriekscode moet sturen, zie DexcomG6CalibrationCode.kt).
 *
 * KOPPELCODE: de 4-cijferige code op de sensor-applicator (zie
 * ui/DexcomG7SetupScreen.kt) is TWEE dingen tegelijk: het J-PAKE-wachtwoord
 * (DexcomG7JpakeContext) ÉN (net als bij xDrip+'s eigen instructies) géén
 * onderdeel van de BLE-advertentienaam-filter — G7/ONE+-sensoren adverteren
 * onder een naam die met "DXCM"/"DX01"/"DX02" begint (bevestigd via xDrip+'s
 * eigen koppelhandleiding: "Forget all devices named DXCM**, DX01**,
 * DX02**"), zonder dat de laatste tekens daarvan überhaupt de koppelcode
 * zijn (anders dan G6's "Dexcom"+laatste-2-tekens-patroon) — zie
 * [buildPairingListFilter].
 *
 * KALIBRATIESTAAT-HERGEBRUIK: G7's glucose-antwoord bevat hetzelfde
 * "CalibrationState"-statusbyte als G5/G6 (`EGlucoseRxMessage.calibrationState()`
 * roept in xDrip+ letterlijk dezelfde `g5model.CalibrationState.parse()` aan
 * die ook `BaseGlucoseRxMessage` voor G5/G6 gebruikt) — vandaar het
 * hergebruik van [DexcomG6CalibrationState] hieronder i.p.v. een eigen,
 * losse G7-tabel: dat zou gewoon een kopie van dezelfde 34 regels zijn.
 *
 * Nog NIET tegen een echte G7/ONE+-sensor geverifieerd (de gebruiker heeft
 * er nog geen) — verwacht bijstellen na de eerste live-test, precies zoals
 * DexcomG6Driver.kt's eigen kdoc destijds al aangaf voor de G6 (en die
 * driver kreeg ook daadwerkelijk twee correctierondes ná de eerste live-test
 * — realistisch om hier hetzelfde te verwachten).
 */
// 27/08/2026 (editor, RONDE 138b, na live-build — "Call requires permission
// which may be rejected by user" x32 in dit bestand) — BLUETOOTH_SCAN/
// BLUETOOTH_CONNECT staan al in AndroidManifest.xml en worden vóór het
// starten van elke koppelpoging als runtime-permissie aangevraagd/
// gecontroleerd (buiten dit bestand, in het scherm dat de koppeling start —
// dat is precies waarom deze driver in de praktijk allang succesvol
// verbindt, zoals alle live-testlogs tot nu toe laten zien). Lint kan die
// controle in een ander bestand niet dataflow-volgen en markeert daarom
// elke los BluetoothDevice/BluetoothGatt-aanroep hier als "mogelijk
// ongeautoriseerd" — een bekende, onschuldige lint-beperking bij BLE-code
// die de permissiecontrole op een hoger niveau (UI-scherm) afhandelt i.p.v.
// vlak vóór elke los BLE-aanroep. @SuppressLint hier onderdrukt puur die
// statische waarschuwing; verandert niets aan runtime-gedrag.
@SuppressLint("MissingPermission")
class DexcomG7Driver(private val slot: SensorSlot) : SensorDriver {

    override val sensorType: SensorType = SensorType.DEXCOM_G7

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<GlucoseReading>(replay = 0, extraBufferCapacity = 8)
    override val readings: SharedFlow<GlucoseReading> = _readings.asSharedFlow()

    private var driverScope: CoroutineScope? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var userStopped = false
    private var appContext: Context? = null

    private var pairingCode: String = ""

    private var bondLossAutoRecoveryEnabled: Boolean = false

    private var charControl: BluetoothGattCharacteristic? = null
    private var charAuthentication: BluetoothGattCharacteristic? = null
    private var charExtraData: BluetoothGattCharacteristic? = null

    private var bondReceiver: BroadcastReceiver? = null
    private var connectScanCallback: ScanCallback? = null
    private var reconnectJob: Job? = null
    private var statusTickerJob: Job? = null

    private var lastSuccessfulConnectionAtMs: Long? = null
    private var cadenceAnchorAtMs: Long? = null
    private var sensorStartedAtMs: Long = 0L

    private var errorBackoffMs = 1_000L
    private val maxErrorBackoffMs = 10_000L

    // ---- Auth/pairing-sessiestatus (per verbindpoging). ----
    private var jpakeContext: DexcomG7JpakeContext? = null
    private var pendingAfterBond: (() -> Unit)? = null

    // 28/08/2026 (editor, RONDE 148) — in-memory cache van de afgeleide
    // 16-byte J-PAKE-gedeelde-sleutel, ZODAT een volgende reconnect naar
    // DEZELFDE (nog BLE-gebonden) sensor de volledige 3-ronde-handshake kan
    // overslaan — zie [DexcomG7Driver.GattCallback.runPairingHandshake]'s
    // kdoc voor het bewijs (rechtstreekse vergelijking met xDrip+'s EIGEN
    // HCI-capture van een succesvolle reconnect). Bewust simpel/in-memory
    // (geen DataStore-persistentie): geldig voor de levensduur van deze
    // driver-instantie, wat exact de 5-minuten-pollcyclus dekt waar dit
    // voor bedoeld is; bij een app-herstart valt de code gewoon terug op
    // een volledige handshake, wat altijd veilig is. `null` zodra de
    // sleutel ooit een mislukte auth-aanvraag oplevert (zie
    // runPairingHandshake) — dan doet de VOLGENDE poging weer de volle
    // handshake i.p.v. eindeloos met een mogelijk verouderde sleutel te
    // blijven proberen.
    private var savedSessionKey: ByteArray? = null
    private var savedSessionKeyDeviceAddress: String? = null

    // 17/08/2026 (editor, RONDE 112) — ExtraData-notificaties komen in
    // stukjes van hoogstens 20 bytes (BLE-MTU) binnen; deze accumulator plakt
    // ze aan elkaar tot een volledig J-PAKE-rondepakket (160 bytes) — mirror
    // van xDrip+'s Plugin.java se `accumulator`/`fill()`.
    private var extraDataAccumulator = ByteArray(0)
    private var pendingExtraDataPacketDeferred: CompletableDeferred<DexcomG7Packet?>? = null
    private var pendingAuthIndicationDeferred: CompletableDeferred<ByteArray?>? = null
    private var pendingGlucoseDeferred: CompletableDeferred<DexcomG7Protocol.GlucoseRx?>? = null
    // 28/08/2026 (editor, RONDE 150) — mirror van DexcomG6Driver.kt's
    // pendingBatteryDeferred/pendingVersionRequest2Deferred: dezelfde
    // Control-characteristic-deferred-aanpak, nu ook voor G7's batterij-/
    // firmwareversie-verzoek (zie DexcomG7Protocol.kt's kdoc bij
    // buildBatteryInfoRequest/buildFirmwareVersionRequest voor de
    // protocol-herkomst).
    private var pendingBatteryDeferred: CompletableDeferred<DexcomG7Protocol.BatteryInfoRx?>? = null
    private var pendingFirmwareDeferred: CompletableDeferred<DexcomG7Protocol.FirmwareVersionRx?>? = null
    // 28/08/2026 (editor, RONDE 141) — Ronde 139 voegde hier ooit een apart
    // `pendingWriteAckDeferred`/`awaitWriteAck()` toe (wachten op de eigen
    // Write Response van de TIME_EXTENDED-write). Een echte, geslaagde
    // xDrip+-koppeling met dezelfde sensor (HCI-snooplog uit de bugreport
    // van de gebruiker) bewees dat dát niet de juiste voorwaarde was: xDrip
    // wacht niet op zijn EIGEN write-ack, maar op een BINNENKOMENDE
    // indicatie van de SENSOR zelf ([isBondTrigger]). Die indicatie loopt
    // toch al via [pendingAuthIndicationDeferred] (dezelfde weg als alle
    // andere stappen in de handshake), dus de aparte deferred/helper is
    // weer verwijderd — zie [runPairingHandshake]'s `!status.isBonded`-tak.

    companion object {
        // 17/08/2026 (editor, RONDE 112) — zelfde cadans-aannames als
        // DexcomG6Driver.kt (G7 is, net als G6, een 5-minuten-sensor) — zie
        // dat bestand's kdoc bij computeReconnectCooldownMs() voor de
        // volledige analyse achter deze twee getallen.
        private const val SENSOR_PERIOD_MS = 300_000L
        private const val SCAN_START_MARGIN_MS = 60_000L

        // Zelfde waarde als DexcomG6Driver.kt/CareSensAirDriver.kt — zie die
        // bestanden's kdoc bij scheduleRearm() voor de herkomst-analyse
        // (geen G7-specifieke afweging, generiek BLE-scan-zelfherstel).
        private const val SCAN_REARM_INTERVAL_MS = 390_000L

        // 28/08/2026 (editor, RONDE 149) — zie kdoc bij `onScanFailed()`
        // hieronder voor het volledige bewijs: herhaalde "scan failed
        // code=2"-uitbarstingen (Android's `SCAN_FAILED_APPLICATION_
        // REGISTRATION_FAILED`, het systeembrede plafond op hoe vaak een app
        // mag scannen) die met de gewone, korte foutmarge (max 10s) alleen
        // maar in stand werden gehouden. 90 seconden ruim voorbij
        // `ScanRateLimiter`'s eigen 31-seconden-venster, om Android's
        // interne teller daadwerkelijk leeg te laten lopen.
        private const val SCAN_THROTTLE_BACKOFF_MS = 90_000L
        private const val RECONNECT_STATUS_WARNING_MINUTES = 7L

        // 17/08/2026 (editor, RONDE 112) — elke stap van de J-PAKE-handshake
        // (rondepakket versturen+antwoord afwachten, auth-aanvraag+antwoord,
        // uitdaging+status) krijgt dezelfde royale timeout; een falende stap
        // stroomt gewoon door naar de bestaande disconnect/backoff-logica in
        // onConnectionStateChange, geen aparte foutafhandeling nodig.
        private const val PAIRING_STEP_TIMEOUT_MS = 15_000L
        private const val GLUCOSE_TIMEOUT_MS = 20_000L

        // 28/08/2026 (editor, RONDE 150) — zelfde interval als
        // DexcomG6Driver.kt's BATTERY_QUERY_INTERVAL_MS (8 uur — batterij-
        // spanning verandert traag genoeg dat elke 5-minuten-cyclus
        // opnieuw vragen zinloze extra Control-verkeer zou zijn, zeker
        // vlak na Ronde 149's scan-throttle-fix). Firmwareversie verandert
        // nooit tussen verbindingen (vaste hardware-eigenschap) — vandaar
        // een veel ruimer interval, puur als "opnieuw proberen als het de
        // vorige keer nog niet lukte", niet als periodieke verversing.
        private const val BATTERY_QUERY_INTERVAL_MS = 8L * 60 * 60 * 1000
        private const val BATTERY_TIMEOUT_MS = 10_000L
        private const val FIRMWARE_QUERY_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
        private const val FIRMWARE_TIMEOUT_MS = 10_000L

        /** 28/08/2026 (editor, RONDE 152) — xDrip+'s eigen
         *  `requiredNextFirmwareDetailsType()`-prioriteitsvolgorde voor een
         *  transmitter met een 6-karakter-txid (G7's vorm): versie 1 eerst,
         *  dan 0, dan 2 — zie DexcomG7Protocol.buildFirmwareVersionRequest's
         *  kdoc en [queryFirmwareIfStale] voor de volledige onderbouwing. */
        private val FIRMWARE_REQUEST_VERSION_ORDER = listOf(1, 0, 2)

        // xDrip+'s eigen chunking voor de ExtraData-characteristic: stukjes
        // van hoogstens 20 bytes (BLE-MTU zonder onderhandeling), 40ms pauze
        // ertussen om de transmitter niet te overspoelen — zie Ob1G5StateMachine
        // .doNext()'s kdoc-citaat in DexcomG7Protocol.kt.
        private const val CHUNK_SIZE = 20
        private const val CHUNK_DELAY_MS = 40L

        // 27/08/2026 (editor, RONDE 128, na een live-test met een geleende
        // G7 die bij ELKE koppelpoging al bij ronde 1 vastliep — "write ok"
        // in onze eigen log, maar NOOIT een antwoord, en na ~10s
        // beëindigde de sensor ZELF de verbinding (status=19)) — vergeleken
        // met xDrip+'s echte GitHub-broncode (Ob1G5StateMachine.doNext(),
        // regels 220-248 van NightscoutFoundation/xdrip's
        // app/.../g5model/Ob1G5StateMachine.java) bleken twee concrete
        // verschillen met onze `writeChunked()`/`writeCharacteristic()`
        // hieronder:
        // 1. xDrip+ zet VLAK VOOR elke ExtraData-chunk-write expliciet
        //    `WRITE_TYPE_NO_RESPONSE` op die characteristic (regel 224) —
        //    onze `writeChunked()` gebruikte tot deze ronde altijd
        //    `WRITE_TYPE_DEFAULT` (een "Write Request", verwacht een ATT-
        //    antwoord). Dat verklaart het waargenomen symptoom precies:
        //    Android's EIGEN write-callback (`onCharacteristicWrite`) kan
        //    lokaal gewoon "success" melden zonder dat de transmitter-
        //    firmware de payload ooit aan zijn J-PAKE-handler doorgeeft als
        //    die alleen op de "geen-antwoord"-schrijfroute voor deze
        //    characteristic is aangesloten.
        // 2. xDrip+ wacht na de VOLLEDIGE chunk-reeks nog een extra 500ms
        //    (regel 238, letterlijk met het commentaar "TODO wait for
        //    completion?" in de originele broncode) vóórdat het de
        //    bijbehorende ronde-commando-byte naar Authentication schrijft
        //    — onze code deed dat tot deze ronde meteen aansluitend, zonder
        //    extra marge.
        // Beide nu overgenomen (zie writeChunked() hieronder) om exact
        // xDrip+'s timing/write-type te spiegelen — Authentication-writes
        // (het commando zelf, via writeCharacteristic() rechtstreeks
        // aangeroepen) blijven bewust WRITE_TYPE_DEFAULT, ongewijzigd, zie
        // xDrip+'s regel 243 die dat ook expliciet zo zet.
        // 28/08/2026 (editor, RONDE 146, na HCI-analyse van een verse
        // bugreport ná Ronde 145) — deze 500ms bleek gebaseerd op xDrip+'s
        // EIGEN "TODO wait for completion?"-onzekerheid (zie kdoc
        // hierboven), niet op een harde vereiste. Deze bugreport bevatte
        // VIER opeenvolgende verbindingspogingen in dezelfde sessie: de
        // TWEEDE (verse koppeling + certificaatuitwisseling, isBonded was
        // nog false) leverde voor het EERST een ECHTE glucosewaarde op —
        // de volledige pijplijn werkt dus end-to-end. Maar de DERDE en
        // VIERDE poging (reconnect naar een AL gebonden sensor, isBonded
        // meteen true, dus rechtstreeks naar [onAuthAndBondReady]) faalden
        // allebei — de sensor verbrak de verbinding (reden 0x13) op
        // opvallend CONSISTENTE tijdstippen: 4.307s en 4.301s na ons eigen
        // `LE_Start_Encryption`-commando (slechts 5ms uiteen!). Dat wijst
        // sterk op een vaste tijdslimiet aan sensorzijde voor deze
        // "reconnect naar bekende sensor"-route. In de GESLAAGDE tweede
        // poging duurde het maar 3.65s vanaf hetzelfde startpunt tot de
        // glucosewaarde binnen was — dus met ~650ms marge over. Alleen al
        // de drie [POST_CHUNK_SETTLE_MS]-pauzes ná ronde 1/2/3 (500ms ×
        // 3 = 1,5s) plus de acht CHUNK_DELAY_MS-pauzes per ronde (320ms ×
        // 3 = 0,96s) verbruiken ruim de helft van dat ~4,3s-budget, nog
        // vóór ronde 0, de auth-aanvraag/uitdaging, het inschakelen van
        // Control's CCCD en de glucose-aanvraag zelf aan bod komen. Dit is
        // een sterk vermoeden op basis van precieze, herhaalde timing —
        // GEEN zekerheid (geen directe xDrip-referentiecapture van precies
        // dit "snelle reconnect"-pad om 1-op-1 tegen te vergelijken) — maar
        // wel de meest concrete aanwijzing tot nu toe. Verlaagd naar 120ms
        // (nog altijd 3× de CHUNK_DELAY_MS-pauze, dus geen agressieve
        // nul-marge) om ~1,1s ruimte terug te winnen; de eerdere,
        // GESLAAGDE verse-koppelroute (16s totale sessieduur, geen
        // tijdsdruk) hoort hier niet negatief door te worden geraakt.
        private const val POST_CHUNK_SETTLE_MS = 120L

        // 27/08/2026 (editor, RONDE 132) — EARLY_BOND_TIMEOUT_MS (15s marge
        // voor een vroegtijdige createBond()-uitkomst) is in RONDE 133 weer
        // verwijderd samen met de vroegtijdige-createBond()-aanpak zelf, zie
        // onServicesDiscovered()'s kdoc: proactief createBond() aanroepen
        // bleek deze sensor juist de verbinding te doen afbreken.
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * 17/08/2026 (editor, RONDE 112) — zie klasse-kdoc: G7/ONE+ adverteert
     * onder een naam die met "DXCM", "DX01" of "DX02" begint, ONAFHANKELIJK
     * van de koppelcode (anders dan G6's "Dexcom"+laatste-2-tekens-patroon,
     * zie DexcomG6Driver.kt). Breed filter, met PairingScreen.kt's eigen
     * "toon alle apparaten"-schakelaar als terugvalpad mocht dit patroon
     * niet exact overeenkomen met wat een specifiek toestel adverteert.
     */
    override suspend fun buildPairingListFilter(context: Context): ((String?, String) -> Boolean)? {
        return { deviceName, _ ->
            deviceName != null && (
                deviceName.startsWith("DXCM", ignoreCase = true) ||
                    deviceName.startsWith("DX01", ignoreCase = true) ||
                    deviceName.startsWith("DX02", ignoreCase = true)
                )
        }
    }

    override fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth isn't available or is turned off.")
            return
        }
        leScanner = scanner
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = ConnectionState.Error("Scanning failed (code $errorCode).")
            }
        }
        scanCallback = callback
        _connectionState.value = ConnectionState.Scanning
        runCatching { scanner.startScan(emptyList(), scanSettings, callback) }
            .onFailure { _connectionState.value = ConnectionState.Error("Couldn't start scanning: ${it.message}") }
    }

    override fun stopPairing() {
        val callback = scanCallback ?: return
        runCatching { leScanner?.stopScan(callback) }
        scanCallback = null
    }

    override fun connect(context: Context, deviceAddress: String) {
        userStopped = false
        resetAuthState()
        lastSuccessfulConnectionAtMs = null
        cadenceAnchorAtMs = null
        errorBackoffMs = 1_000L
        val settings = AppSettings(context)
        val scope = CoroutineScope(SupervisorJob())
        driverScope = scope
        val appCtx = context.applicationContext
        appContext = appCtx

        scope.launch {
            pairingCode = settings.getDexcomG7PairingCodeOnce(slot).orEmpty()
            if (pairingCode.length != 4 || pairingCode.any { !it.isDigit() }) {
                _connectionState.value = ConnectionState.Error(
                    "No (valid) Dexcom G7 pairing code saved — pair the sensor again."
                )
                return@launch
            }
            sensorStartedAtMs = settings.getOrInitSensorStartedAtMs(slot)
            bondLossAutoRecoveryEnabled = settings.isBondLossAutoRecoveryEnabledOnce() &&
                settings.getDexcomG7LastConnectedAtMsOnce(slot) != null
        }

        val adapter = bluetoothAdapter(context)
        if (adapter == null || adapter.bluetoothLeScanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth isn't available on this device.")
            return
        }
        if (runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() == null) {
            _connectionState.value = ConnectionState.Error("Unknown Bluetooth address: $deviceAddress")
            return
        }
        _connectionState.value = ConnectionState.Connecting(deviceAddress)
        registerBondReceiver(appCtx, deviceAddress)
        scheduleScanAttempt(scope, appCtx, deviceAddress, settings, cooldownMs = 0L)

        statusTickerJob?.cancel()
        statusTickerJob = scope.launch {
            while (true) {
                delay(60_000L)
                if (_connectionState.value !is ConnectionState.Connected) {
                    updateConnectionStatusAfterDisconnect()
                }
            }
        }
    }

    private fun resetAuthState() {
        jpakeContext = null
        extraDataAccumulator = ByteArray(0)
        pendingExtraDataPacketDeferred?.complete(null)
        pendingAuthIndicationDeferred?.complete(null)
        pendingGlucoseDeferred?.complete(null)
        pendingBatteryDeferred?.complete(null)
        pendingFirmwareDeferred?.complete(null)
        pendingExtraDataPacketDeferred = null
        pendingAuthIndicationDeferred = null
        pendingGlucoseDeferred = null
        pendingBatteryDeferred = null
        pendingFirmwareDeferred = null
    }

    override fun disconnect() {
        userStopped = true
        AapsSlotSchedule.clear(slot)
        reconnectJob?.cancel()
        statusTickerJob?.cancel()
        appContext?.let { PredictiveReconnectAlarm.cancel(it) }
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        unregisterBondReceiver()
        driverScope?.cancel()
        driverScope = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // ============================================================
    // Scan-dan-verbind — identiek patroon aan DexcomG6Driver.kt/
    // CareSensAirDriver.kt, gedeelde ScanRateLimiter.
    // ============================================================

    /**
     * 28/08/2026 (editor, RONDE 155, KRITIEKE FIX — bevestigd via een door de
     * gebruiker specifiek voor dit doel verzamelde meerdere-uren-log, exact
     * de reden dat om die log gevraagd was) — deze functie rekende hier
     * voorheen met `Math.round((lastReadingAtMs - anchor) / SENSOR_PERIOD_MS)`
     * om te bepalen in welk vast 5-minuten-vak (sinds [cadenceAnchorAtMs]) de
     * laatste meting viel. De meegestuurde log (16:25–22:07, urenlang
     * stabiel) toonde `periodsElapsed` STEEDS in sprongen van 2 lopen
     * (0, 2, 4, 6, 8, ...), nooit 1 — de app verbond dus feitelijk elke 10
     * minuten, niet elke 5, exact de "10-minuten-cyclus" die de gebruiker
     * al sinds Ronde 151/152 vermoedde maar met te weinig data kon
     * bevestigen.
     *
     * Root cause: elke connectiecyclus kwam consistent ~2,57 minuten LATER
     * binnen dan zijn eigen beoogde tijdstip (BLE-scan-/verbindings-
     * overhead die kennelijk meer tijd kost dan [SCAN_START_MARGIN_MS]
     * ervoor inruimt). Zodra die vertraging over de helft van een vak
     * (2,5 min) heen ging, rondde `Math.round` naar het VOLGENDE vak i.p.v.
     * het vak waar de meting daadwerkelijk bij hoorde — de meting werd dan
     * toegeschreven aan vak N+1 i.p.v. vak N, waarna het volgende doel N+2
     * werd (10 min verder), wat op zijn beurt weer ~2,57 min te laat
     * binnenkwam en dus OPNIEUW naar het volgende vak afrondde. Eenmaal
     * over die afrondingsgrens heen, herhaalt de fout zichzelf daardoor
     * oneindig door (tot de eerstvolgende ankerreset via `connect()`) —
     * geen sensor-eigenaardigheid, een reproduceerbare rekenfout.
     *
     * Fix: `Math.floor` i.p.v. `Math.round`. Dat kent de meting toe aan het
     * LAATSTE vak dat al écht verstreken was op het moment van binnenkomst
     * (ook als de meting zelf laat was), en mikt het VOLGENDE doel exact 5
     * minuten daarna — een eenmalige vertraging van een paar minuten
     * schuift dan niet meer permanent een heel extra vak door, omdat elk
     * doel weer opnieuw wordt afgeleid van waar de vorige meting
     * daadwerkelijk (al is het laat) binnenkwam, in plaats van te
     * cumuleren. Met dezelfde ~2,57 min consistente vertraging per cyclus
     * geeft dit weer een echte 5-minuten-cadans tussen opeenvolgende
     * metingen, in plaats van steeds 10.
     */
    private fun computeReconnectCooldownMs(): Long {
        val lastReadingAtMs = lastSuccessfulConnectionAtMs ?: return SENSOR_PERIOD_MS - SCAN_START_MARGIN_MS
        val anchor = cadenceAnchorAtMs ?: lastReadingAtMs
        val periodsElapsed = Math.floor((lastReadingAtMs - anchor) / SENSOR_PERIOD_MS.toDouble()).toLong()
        val gridReadingAtMs = anchor + periodsElapsed * SENSOR_PERIOD_MS
        val predictedNextReadingAtMs = gridReadingAtMs + SENSOR_PERIOD_MS
        AapsSlotSchedule.publish(slot, predictedNextReadingAtMs)
        val remainingMs = predictedNextReadingAtMs - SCAN_START_MARGIN_MS - System.currentTimeMillis()
        val result = remainingMs.coerceAtLeast(0L)
        DiagnosticFileLogger.log(
            "DexcomG7: computeReconnectCooldownMs: lastReadingAt=$lastReadingAtMs anchor=$anchor gridReadingAt=$gridReadingAtMs -> cooldownMs=$result"
        )
        return result
    }

    private fun scheduleScanAttempt(
        scope: CoroutineScope, appCtx: Context, deviceAddress: String,
        settings: AppSettings, cooldownMs: Long
    ) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            awaitCooldown(appCtx, cooldownMs)
            val currentAapsSlot = settings.aapsActiveSlot.first()
            AapsSlotSchedule.publishAapsActiveSlot(currentAapsSlot)
            val isPriority = currentAapsSlot == slot
            if (!isPriority) {
                val guardDelay = AapsSlotSchedule.guardDelayMs(slot, System.currentTimeMillis())
                if (guardDelay > 0) {
                    DiagnosticFileLogger.log("DexcomG7: scheduleScanAttempt: wijk ${guardDelay}ms uit voor AAPS-slot")
                    delay(guardDelay)
                }
            }
            val throttleDelay = ScanRateLimiter.delayBeforeNextScanMs(isPriority)
            if (throttleDelay > 0) delay(throttleDelay)
            if (userStopped) return@launch
            val scanner = bluetoothAdapter(appCtx)?.bluetoothLeScanner
            if (scanner == null) {
                _connectionState.value = ConnectionState.Error("Bluetooth isn't available on this device.")
                return@launch
            }
            ActiveWorkWakeLock.keepAwake()
            ScanRateLimiter.recordScanStart(isPriority)
            startConnectScan(scope, appCtx, scanner, deviceAddress, settings)
        }
    }

    private suspend fun awaitCooldown(appCtx: Context, cooldownMs: Long) {
        if (cooldownMs <= 0) return
        val deferred = PredictiveReconnectAlarm.schedule(appCtx, cooldownMs)
        try {
            withTimeoutOrNull(cooldownMs + 30_000L) { deferred.await() }
        } finally {
            PredictiveReconnectAlarm.cancel(appCtx)
        }
    }

    private fun startConnectScan(
        scope: CoroutineScope, appCtx: Context, scanner: BluetoothLeScanner,
        deviceAddress: String, settings: AppSettings
    ) {
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanFilters = listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
        var resolved = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address == deviceAddress && !resolved) {
                    resolved = true
                    runCatching { scanner.stopScan(this) }
                    connectScanCallback = null
                    if (bondLossAutoRecoveryEnabled && BondLossRecovery.isBondMissing(result.device)) {
                        pendingAfterBond = { connectToDevice(scope, appCtx, result.device, settings) }
                        BondLossRecovery.attemptRecovery(result.device, "DexcomG7")
                        scope.launch {
                            delay(15_000L)
                            if (pendingAfterBond != null) {
                                pendingAfterBond = null
                                connectToDevice(scope, appCtx, result.device, settings)
                            }
                        }
                    } else {
                        connectToDevice(scope, appCtx, result.device, settings)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                DiagnosticFileLogger.log("DexcomG7: scan failed code=$errorCode")
                connectScanCallback = null
                // 28/08/2026 (editor, RONDE 149, na een diagnostieklog die de
                // gebruiker meestuurde naast een bugreport — "hij geeft nu 10
                // minuten later wel een nieuwe connectietijd door maar er komt
                // geen data mee" leidde naar deze vondst, niet naar de sensor
                // zelf) — het logbestand toonde herhaalde "scan failed
                // code=2"-regels (Android's `SCAN_FAILED_APPLICATION_
                // REGISTRATION_FAILED`, ~elke 5-10s gedurende een aaneen-
                // gesloten uitbarsting van ruim 2 minuten), gevolgd door
                // stiltes van 5-10 MINUTEN zonder ENIGE logregel. Code 2 is
                // Android's EIGEN, systeembrede plafond op hoe vaak een app
                // scans mag starten/stoppen binnen een venster — met de oude,
                // ongewijzigde `backoffAndRetry()` (marge 1-10s, hetzelfde
                // tempo als een gewone mislukte GATT-verbinding) probeert de
                // code binnen dat venster gewoon opnieuw, wat het plafond
                // ZELF in stand houdt/verlengt — een zelfopgelegde
                // deadlock: falen -> snel opnieuw proberen -> nog steeds
                // geblokkeerd -> falen -> ... De stille gaten van 5-10 minuten
                // zijn vermoedelijk het gevolg (ofwel Android's plafond blijft
                // ondertussen actief, ofwel [scheduleRearm]'s eigen
                // SCAN_REARM_INTERVAL_MS (390s) verloopt zonder dat
                // `onScanResult` ooit vuurt, wat GEEN logregel oplevert — een
                // stille wachttijd is dus, achteraf gezien, geen bewijs dat er
                // niets gebeurde, alleen dat er niets te loggen viel). Nieuwe,
                // veel langere marge specifiek voor deze foutcode(s) — geeft
                // Android's interne teller daadwerkelijk tijd om leeg te
                // lopen, i.p.v. 'm continu opnieuw te triggeren.
                if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ||
                    (Build.VERSION.SDK_INT >= 30 && errorCode == 6) // SCAN_FAILED_SCANNING_TOO_FREQUENTLY (API 30+, nog niet als publieke constante beschikbaar op compileSdk 34's stub op het moment van schrijven)
                ) {
                    DiagnosticFileLogger.log("DexcomG7: scan-plafond van Android geraakt (code=$errorCode) — wacht ${SCAN_THROTTLE_BACKOFF_MS}ms i.p.v. de korte foutmarge, om het plafond niet zelf in stand te houden")
                    scheduleScanAttempt(scope, appCtx, deviceAddress, settings, cooldownMs = SCAN_THROTTLE_BACKOFF_MS)
                } else {
                    backoffAndRetry(scope, appCtx, deviceAddress, settings)
                }
            }
        }
        connectScanCallback = callback
        runCatching { scanner.startScan(scanFilters, scanSettings, callback) }
            .onFailure {
                connectScanCallback = null
                backoffAndRetry(scope, appCtx, deviceAddress, settings)
            }
        scheduleRearm(scope, appCtx, scanner, callback, deviceAddress, settings) { resolved }
    }

    /** Zie DexcomG6Driver.kt's identiek-genaamde functie voor de volledige
     *  herkomst-analyse (Juggluco's w2.run()-geval / RONDE 76-melding) —
     *  hier ongewijzigd hergebruikt, generiek BLE-scan-zelfherstel. */
    private fun scheduleRearm(
        scope: CoroutineScope,
        appCtx: Context,
        scanner: BluetoothLeScanner,
        callback: ScanCallback,
        deviceAddress: String,
        settings: AppSettings,
        isResolved: () -> Boolean
    ) {
        scope.launch {
            delay(SCAN_REARM_INTERVAL_MS)
            if (!isResolved() && !userStopped) {
                runCatching { scanner.stopScan(callback) }
                connectScanCallback = null
                scheduleScanAttempt(scope, appCtx, deviceAddress, settings, cooldownMs = 0L)
            }
        }
    }

    private fun backoffAndRetry(scope: CoroutineScope, appCtx: Context, deviceAddress: String, settings: AppSettings) {
        val delayMs = errorBackoffMs
        if (errorBackoffMs < maxErrorBackoffMs) errorBackoffMs += 100
        scheduleScanAttempt(scope, appCtx, deviceAddress, settings, cooldownMs = delayMs)
    }

    private fun connectToDevice(scope: CoroutineScope, appCtx: Context, device: BluetoothDevice, settings: AppSettings) {
        _connectionState.value = ConnectionState.Connecting(device.address)
        val callback = GattCallback(scope, settings)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appCtx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appCtx, false, callback)
        }
        bluetoothGatt = gatt
    }

    // ============================================================
    // GATT-levenscyclus + J-PAKE-koppelhandshake.
    // ============================================================

    private inner class GattCallback(
        private val scope: CoroutineScope,
        private val settings: AppSettings
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 28/08/2026 (editor, RONDE 142, na een volledige byte-
                    // voor-byte vergelijking van de HCI-snooplog van een
                    // ECHTE geslaagde xDrip+-koppeling tegen onze eigen v156-
                    // capture — zie README) — de `gatt.requestMtu(185)`-
                    // aanroep die hier stond, stuurt een ATT Exchange MTU
                    // Request de lucht in ZODRA de verbinding opgaat. Dat
                    // bleek het ENIGE verschil in de hele sequentie (service-
                    // discovery, CCCD-volgorde/-waarden, ronde-commandobytes,
                    // chunking — alles matchte exact) tussen onze
                    // consequent falende pogingen en xDrip+'s bewezen
                    // werkende sessie: xDrip+ doet HELEMAAL geen MTU-
                    // onderhandeling, gaat rechtstreeks van verbinden naar
                    // `discoverServices()` op de standaard-MTU (23 bytes).
                    // Dat is bovendien consistent met onze EIGEN
                    // `CHUNK_SIZE`-constante hierboven, die letterlijk
                    // "BLE-MTU zonder onderhandeling" in de kdoc heeft staan
                    // — de MTU-aanvraag was dus sowieso al inconsistent met
                    // de rest van dit bestand. Nu: rechtstreeks
                    // discoverServices(), exact xDrip+'s bewezen volgorde.
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagnosticFileLogger.log("DexcomG7: STATE_DISCONNECTED status=$status device=${gatt.device.address}")
                    resetAuthState()
                    if (userStopped) {
                        runCatching { gatt.close() }
                        return
                    }
                    val address = gatt.device.address
                    val ctx = appContext
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    updateConnectionStatusAfterDisconnect()
                    if (ctx != null) {
                        val wasSuccessfulRead = lastSuccessfulConnectionAtMs != null &&
                            System.currentTimeMillis() - lastSuccessfulConnectionAtMs!! < 60_000L
                        val cooldown = if (wasSuccessfulRead) {
                            computeReconnectCooldownMs()
                        } else {
                            errorBackoffMs.also { if (errorBackoffMs < maxErrorBackoffMs) errorBackoffMs += 100 }
                        }
                        scheduleScanAttempt(scope, ctx, address, settings, cooldownMs = cooldown)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Couldn't discover services (status $status).")
                runCatching { gatt.disconnect() }
                return
            }
            val service = gatt.getService(DexcomG7Protocol.CGM_SERVICE)
            if (service == null) {
                _connectionState.value = ConnectionState.Error("This device doesn't look like a Dexcom G7/ONE+ transmitter.")
                runCatching { gatt.disconnect() }
                return
            }
            charControl = service.getCharacteristic(DexcomG7Protocol.CONTROL)
            charAuthentication = service.getCharacteristic(DexcomG7Protocol.AUTHENTICATION)
            charExtraData = service.getCharacteristic(DexcomG7Protocol.EXTRA_DATA)

            val authChar = charAuthentication
            val extraChar = charExtraData
            if (authChar == null || extraChar == null) {
                _connectionState.value = ConnectionState.Error("Authentication/ExtraData characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }
            // 27/08/2026 (editor, RONDE 131, na een ECHTE xDrip+-log op
            // dezelfde sensor) — VROEGTIJDIG createBond() aanroepen, VOORDAT
            // de J-PAKE-handshake start, i.p.v. pas erna, was het idee.
            //
            // 27/08/2026 (editor, RONDE 132) — v144's variant (createBond()
            // gelijktijdig met CCCD-writes) gaf status=19 binnen ~150ms; de
            // aanname was dat de GELIJKTIJDIGHEID de boosdoener was, dus v145
            // maakte het STRIKT SERIEEL (wachten op bond-uitkomst vóór ook
            // maar één CCCD-write).
            //
            // 27/08/2026 (editor, RONDE 133, na een live-test van v145 met
            // ~19 herhaalde verbindingspogingen over bijna 20 minuten) —
            // v145 loste het NIET op: OOK volledig serieel, zonder enige
            // gelijktijdige GATT-operatie, brak de sensor de verbinding
            // (status=19) binnen ~50-150ms na ELKE createBond()-aanroep, elke
            // keer opnieuw, zonder uitzondering. Dat weerlegt de v132-
            // aanname (gelijktijdige CCCD-writes) volledig — het is de
            // createBond()-aanroep ZELF die de sensor doet afhaken, ongeacht
            // timing. Gevolg was zelfs SLECHTER dan vóór ronde 131: geen
            // enkele verbinding overleefde nog lang genoeg om ronde 1 van de
            // handshake te proberen, en de 15s-timeoutfallback probeerde
            // daarna nog CCCD-writes op een allang gesloten gatt-object
            // ("setCharacteristicNotification failed").
            //
            // Conclusie: bij DEZE sensor lijkt bonden PERIFEER-geïnitieerd te
            // zijn (de sensor/Android start zelf een SMP-koppelverzoek zodra
            // dat nodig is, bv. via een "insufficient encryption"-GATT-
            // fout) — niet iets wat onze app zelf met createBond() moet
            // afdwingen vóór de handshake. xDrip+'s eigen log toonde wél
            // "Bond state Pairing"-overgangen, maar dat bewijst niet dat
            // xDrip zélf createBond() aanroept vóór de handshake; dat kan
            // net zo goed automatisch door Android/de sensor zijn gestart.
            //
            // Terug naar de aanpak van vóór ronde 131: HIER geen createBond()
            // meer aanroepen — gewoon direct doorgaan met CCCD-writes en de
            // handshake. Bonden gebeurt (zoals in de oorspronkelijke, Ronde
            // 112-opzet) pas NA succesvolle authenticatie, zie
            // runPairingHandshake()'s `if (!status.isBonded) { ...
            // gatt.device.createBond() }`-tak verderop in dit bestand. De
            // verbeterde bond-state-logging (bondStateName(), elke overgang
            // loggen) blijft wel staan — puur observationeel, onschadelijk,
            // en nuttig gebleken bij het analyseren van bovenstaande logs.
            enableNotify(gatt, extraChar, useIndication = false) {
                enableNotify(gatt, authChar, useIndication = true) {
                    scope.launch { runPairingHandshake(gatt, authChar, extraChar) }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticFileLogger.log("DexcomG7: CCCD write FAILED for ${descriptor.characteristic.uuid} status=$status")
            } else {
                DiagnosticFileLogger.log("DexcomG7: CCCD write ok for ${descriptor.characteristic.uuid}")
            }
            pendingAfterNotifyEnabled.remove(descriptor.characteristic.uuid)?.invoke()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticFileLogger.log("DexcomG7: write FAILED for ${characteristic.uuid} status=$status")
            } else {
                DiagnosticFileLogger.log("DexcomG7: write ok for ${characteristic.uuid}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(gatt, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(gatt, characteristic.uuid, characteristic.value ?: return)
        }

        private fun handleNotification(gatt: BluetoothGatt, uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                DexcomG7Protocol.AUTHENTICATION -> handleAuthNotification(value)
                DexcomG7Protocol.EXTRA_DATA -> handleExtraDataNotification(value)
                DexcomG7Protocol.CONTROL -> handleControlNotification(value)
                DexcomG7Protocol.PROBABLY_BACKFILL -> {
                    if (DexcomG7Protocol.isBackfillControlPacket(value)) {
                        DiagnosticFileLogger.log("DexcomG7: backfill control packet received (inhoud nog niet geparsed, zie DexcomG7Protocol.kt's kdoc)")
                    }
                }
            }
        }

        /** Authentication-kanaal tijdens de handshake: het bond-trigger-
         *  signaal ([DexcomG7Protocol.isBondTrigger]) wordt altijd apart
         *  herkend/gelogd; verder wordt élke binnenkomende indicatie simpelweg
         *  doorgegeven aan wie er op dit moment op dit kanaal wacht
         *  ([pendingAuthIndicationDeferred]) — [runPairingHandshake] bepaalt
         *  zelf, op basis van waar het in de sequentie is, hoe die bytes
         *  geïnterpreteerd moeten worden (auth-aanvraag-antwoord vs.
         *  status-antwoord). */
        private fun handleAuthNotification(value: ByteArray) {
            if (DexcomG7Protocol.isBondTrigger(value)) {
                DiagnosticFileLogger.log("DexcomG7: bond-trigger ontvangen op Authentication")
            }
            pendingAuthIndicationDeferred?.complete(value)
        }

        /** ExtraData-kanaal: accumuleert MTU-stukjes tot een volledig J-PAKE-
         *  pakket (160 bytes) — mirror van xDrip+'s Plugin.java se
         *  `fill()`/`accumulator`. */
        private fun handleExtraDataNotification(value: ByteArray) {
            extraDataAccumulator += value
            if (extraDataAccumulator.size >= DexcomG7Curve.packetSize) {
                val packet = DexcomG7Packet.parse(extraDataAccumulator)
                extraDataAccumulator = ByteArray(0)
                pendingExtraDataPacketDeferred?.complete(packet)
            }
        }

        private fun handleControlNotification(value: ByteArray) {
            val opcode = value.getOrNull(0)?.toInt()?.and(0xff)
            when (opcode) {
                0x4e -> pendingGlucoseDeferred?.complete(DexcomG7Protocol.parseGlucose(value))
                // 28/08/2026 (editor, RONDE 150) — zie DexcomG7Protocol.kt's
                // kdoc bij parseBatteryInfo/parseFirmwareVersion voor de
                // herkomst van deze opcodes (0x22/0x23 batterij, 0x21
                // firmware) — zelfde dispatch-patroon als
                // DexcomG6Driver.kt's handleControlNotification().
                0x22, 0x23 -> pendingBatteryDeferred?.complete(DexcomG7Protocol.parseBatteryInfo(value))
                0x21 -> pendingFirmwareDeferred?.complete(DexcomG7Protocol.parseFirmwareVersion(value))
                else -> {
                    // 28/08/2026 (editor, RONDE 151) — zie
                    // queryBatteryIfStale/queryFirmwareIfStale's kdoc: een
                    // onherkend antwoord (bv. de 2-byte "opcode 0x20 echo"
                    // die deze sensor als afwijzing terugstuurt op het
                    // firmwareverzoek) betekent bijna zeker "dit verzoek
                    // wordt niet begrepen/geaccepteerd" — fail-fast door de
                    // op dit moment wachtende deferred meteen met `null` af
                    // te ronden, i.p.v. de volle timeout te laten aflopen en
                    // zo onnodig verbindingstijd te verspillen (of de sensor
                    // zelf de kans te geven ongeduldig de verbinding te
                    // verbreken vóórdat het eigenlijke glucoseverzoek aan de
                    // beurt is).
                    when {
                        pendingFirmwareDeferred != null ->
                            DiagnosticFileLogger.log("DexcomG7: firmwareverzoek afgewezen/onherkend (opcode=$opcode bytes=${value.joinToString(",")}) — fail-fast")
                        pendingBatteryDeferred != null ->
                            DiagnosticFileLogger.log("DexcomG7: batterijverzoek afgewezen/onherkend (opcode=$opcode bytes=${value.joinToString(",")}) — fail-fast")
                        else ->
                            DiagnosticFileLogger.log("DexcomG7: unhandled Control opcode=$opcode bytes=${value.joinToString(",")}")
                    }
                    pendingFirmwareDeferred?.complete(null)
                    pendingBatteryDeferred?.complete(null)
                }
            }
        }

        /**
         * 17/08/2026 (editor, RONDE 112) — de volledige J-PAKE-koppel-
         * handshake, zie klasse-kdoc voor waarom dit een lineaire `suspend`-
         * functie is i.p.v. een geport generiek FSM. Elke stap: bouw het
         * pakket (DexcomG7Crypto.kt), schrijf het naar de juiste
         * characteristic(s) (via [writeChunked] voor ExtraData, rechtstreeks
         * voor Authentication — zie xDrip+'s `doNext()`-citaat in
         * DexcomG7Protocol.kt voor welke volgorde/welk kanaal), wacht op het
         * antwoord met een timeout, en stopt (disconnect, geen aparte
         * foutmelding-state) zodra iets misgaat — de bestaande backoff/
         * reconnect-logica in onConnectionStateChange handelt de rest af.
         */
        private suspend fun runPairingHandshake(
            gatt: BluetoothGatt,
            authChar: BluetoothGattCharacteristic,
            extraChar: BluetoothGattCharacteristic
        ) {
            val ctx = DexcomG7JpakeContext(pairingCode)
            jpakeContext = ctx

            // 28/08/2026 (editor, RONDE 148, na een bugreport die de
            // gebruiker van xDrip+ ZELF opnam terwijl v161 nog niet
            // getest was) — HCI-analyse van xDrip+'s eigen reconnects naar
            // een AL gebonden sensor toonde iets beslissends: TWEE
            // geslaagde reconnects, BEIDE met een hergebruikte LTK (`LE_
            // Start_Encryption` met een bestaande sleutel, binnen ~70ms na
            // de verbinding, GEEN verse SMP-onderhandeling zichtbaar) — en
            // in BEIDE gevallen sloeg xDrip+ de VOLLEDIGE ronde 0-3-
            // handshake hieronder over: de HCI-capture toont NUL schrijf-
            // acties naar ExtraData vóór de auth-aanvraag (opcode 0x02)
            // rechtstreeks op Authentication. Dat weerlegt Ronde 147's
            // hypothese volledig (die veronderstelde dat de sensor een
            // hergebruikte LTK juist NIET zou accepteren) — LTK-hergebruik
            // werkt prima, ALS de J-PAKE-rondes zelf ook worden overgeslagen.
            //
            // Dit is precies xDrip+'s eigen `Plugin.java`-gedrag
            // (`context.savedKey`/"RoundStart -> meteen RequestAuth", al in
            // Ronde 112's klasse-kdoc als bewust NIET geport genoteerd, toen
            // ingeschat als "puur een performance-optimalisatie, geen
            // correctheids-vereiste" — die inschatting blijkt nu FOUT: het
            // is kennelijk vereist voor de sensor om data vrij te geven op
            // een reconnect). `DexcomG7Crypto.kt`'s `calculateHash()` had
            // hier overigens AL ondersteuning voor (`context.savedKey ?:
            // getShortSharedKey(context)`), alleen nooit door de driver
            // hier gebruikt.
            //
            // [savedSessionKey]/[savedSessionKeyDeviceAddress] (klasse-kdoc
            // hierboven) worden na een VOLLEDIGE, geslaagde handshake
            // bewaard; hier wordt gecontroleerd of we voor DIT toestel al
            // een bruikbare sleutel hebben EN Android het toestel nog als
            // gebonden beschouwt (zonder geldige BLE-bond is een
            // hergebruikte sleutel zinloos — de sensor zou dan sowieso geen
            // versleutelde sessie herkennen).
            val cachedKey = savedSessionKey
            val canSkipJpake = cachedKey != null &&
                savedSessionKeyDeviceAddress == gatt.device.address &&
                gatt.device.bondState == BluetoothDevice.BOND_BONDED
            if (canSkipJpake) {
                ctx.savedKey = cachedKey
                DiagnosticFileLogger.log("DexcomG7: hergebruik opgeslagen sessiesleutel — ronde 0-3 overgeslagen (RONDE 148)")
            } else {
                // 27/08/2026 (editor, RONDE 128b, na live-test — ronde 1 kreeg nu
                // wél antwoord (dankzij de write-type-fix hierboven), maar
                // `validateRound1Packet` faalde alsnog met een 100% bevestigd
                // JUISTE koppelcode) — een subagent verifieerde xDrip+'s echte
                // ronde-sequencing tegen de vendored KEKS-broncode in
                // MTR93600/OpenApsAIMI (dev_OnePlusG7-branch,
                // plugins/libkeks/.../jamorham/keks/Plugin.java, zelf
                // rechtstreeks nagelezen — geciteerd `aNext()`/`sequencePacket()`/
                // `parameterFromState()`): de VOLGORDE hieronder was fundamenteel
                // verkeerd. `parameterFromState()` wordt in de echte state machine
                // pas gelezen NADAT `state` al is doorgeschoven naar de VOLGENDE
                // fase — het protocol is dus een vraag-antwoord-cyclus van VIER
                // stappen, niet drie:
                //   Stap A: een KALE `{0x0A,0x00}` naar Authentication, GEEN
                //           ExtraData-data — dit is puur een aftrap die de sensor
                //           vraagt om ZIJN EIGEN ronde-1-pakket te sturen.
                //   Stap B: pas nu sturen WIJ ons eigen ronde-1-pakket, getagd met
                //           param 1 (niet 0!).
                //   Stap C: ons ronde-2-pakket, getagd met param 2 (niet 1!).
                //   Stap D: ons ronde-3-pakket + de echte auth-aanvraag (ongewijzigd
                //           al correct, gebruikt sowieso geen KEYCMD-tag).
                // De oude code sloeg stap A helemaal over en stuurde in de eerste
                // uitwisseling al ons eigen ronde-1-pakket mee, getagd met param 0
                // — precies het patroon "data kwam wel terug, maar validatie
                // faalde" dat hier live werd waargenomen.
                val round1 = awaitExtraDataPacket {
                    writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildRoundCommand(0))
                } ?: return failHandshake(gatt, "geen antwoord op de koppel-aftrap")
                ctx.receivedRound1 = round1
                if (!DexcomG7Jpake.validateRound1Packet(ctx)) {
                    logRound1ValidationFailure(ctx, round1)
                    return failHandshake(gatt, "ronde 1: ongeldig bewijs (verkeerde koppelcode?)")
                }

                // ---- Stap B: ons ronde-1-pakket, param 1 ----
                val round2 = awaitExtraDataPacket {
                    writeChunked(gatt, extraChar, DexcomG7Jpake.getRound1Packet(ctx).output())
                    writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildRoundCommand(1))
                } ?: return failHandshake(gatt, "geen antwoord op ronde 2")
                ctx.receivedRound2 = round2
                if (!DexcomG7Jpake.validateRound2Packet(ctx)) return failHandshake(gatt, "ronde 2: ongeldig bewijs")

                // ---- Stap C: ons ronde-2-pakket, param 2 ----
                val round3 = awaitExtraDataPacket {
                    writeChunked(gatt, extraChar, DexcomG7Jpake.getRound2Packet(ctx).output())
                    writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildRoundCommand(2))
                } ?: return failHandshake(gatt, "geen antwoord op ronde 3")
                ctx.receivedRound3 = round3
                if (!DexcomG7Jpake.validateRound3Packet(ctx)) return failHandshake(gatt, "ronde 3: ongeldig bewijs (verkeerde koppelcode?)")
            }

            // ---- Stap D: (indien niet overgeslagen) ons ronde-3-pakket +
            // de echte auth-aanvraag (ongewijzigd al correct, gebruikt
            // sowieso geen KEYCMD-tag). RONDE 148: het ronde-3-pakket wordt
            // ALLEEN meegestuurd als we het deze sessie ook echt hebben
            // afgeleid — bij een hergebruikte sleutel bestaat er domweg
            // geen vers ronde-3-pakket (xDrip+'s eigen capture toont hier
            // dan ook GEEN ExtraData-schrijfactie, rechtstreeks de
            // auth-aanvraag). ----
            val token = DexcomG7Protocol.randomToken()
            val authResponseBytes = awaitAuthIndication {
                if (!canSkipJpake) {
                    writeChunked(gatt, extraChar, DexcomG7Jpake.getRound3Packet(ctx).output())
                }
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildAuthRequest(token))
            } ?: run {
                if (canSkipJpake) {
                    savedSessionKey = null
                    savedSessionKeyDeviceAddress = null
                    DiagnosticFileLogger.log("DexcomG7: geen antwoord op auth-aanvraag met hergebruikte sleutel — sleutel gewist, volgende poging doet de volledige handshake")
                }
                return failHandshake(gatt, "geen antwoord op auth-aanvraag")
            }
            val authResponse = DexcomG7Protocol.parseAuthRequestResponse(authResponseBytes)
                ?: return failHandshake(gatt, "auth-aanvraag-antwoord te kort")

            // Verifieer dat de sensor ONZE eigen challenge (het token dat we
            // net verstuurden) correct met de gedeelde sleutel kon hashen —
            // zie DexcomG7Crypto.DexcomG7Jpake.calculateHash's kdoc.
            ctx.challenge = token
            val expectedHash = DexcomG7Jpake.calculateHash(ctx)
            if (expectedHash == null || !expectedHash.contentEquals(authResponse.theirProofHash)) {
                if (canSkipJpake) {
                    savedSessionKey = null
                    savedSessionKeyDeviceAddress = null
                    DiagnosticFileLogger.log("DexcomG7: uitdaging-bewijs klopt niet met hergebruikte sleutel — sleutel gewist, volgende poging doet de volledige handshake")
                }
                return failHandshake(gatt, "uitdaging-bewijs klopt niet (verkeerde koppelcode?)")
            }

            // Beantwoord op onze beurt de NIEUWE uitdaging die de sensor
            // meestuurde.
            ctx.challenge = authResponse.theirChallenge
            val ourReplyHash = DexcomG7Jpake.calculateHash(ctx)
                ?: return failHandshake(gatt, "kon geen antwoord-hash berekenen")
            val statusBytes = awaitAuthIndication {
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildAuthChallenge(ourReplyHash))
            } ?: return failHandshake(gatt, "geen statusantwoord ontvangen")
            val status = DexcomG7Protocol.parseAuthStatus(statusBytes)
                ?: return failHandshake(gatt, "statusantwoord onherkenbaar")

            if (!status.isAuthenticated) {
                _connectionState.value = ConnectionState.Error("Dexcom G7 authentication failed — check the pairing code.")
                runCatching { gatt.disconnect() }
                return
            }

            // 28/08/2026 (editor, RONDE 148) — authenticatie bevestigd geslaagd:
            // als dit een VOLLEDIGE handshake was (niet de hergebruikte-sleutel-
            // kortsluiting hierboven), bewaar de zojuist afgeleide sleutel nu
            // voor een volgende reconnect naar dit toestel.
            if (!canSkipJpake) {
                val derivedKey = DexcomG7Jpake.getShortSharedKey(ctx)
                if (derivedKey != null) {
                    savedSessionKey = derivedKey
                    savedSessionKeyDeviceAddress = gatt.device.address
                    DiagnosticFileLogger.log("DexcomG7: sessiesleutel opgeslagen voor een snelle reconnect (RONDE 148)")
                }
            }

            // 28/08/2026 (editor, RONDE 148) — TERUGGEDRAAID naar de Ronde
            // 145-structuur (`if (!status.isBonded) { certExchange;
            // TIME_EXTENDED-wacht; createBond() } else { onAuthAndBondReady
            // (gatt) }`), NA Ronde 147's poging om dit altijd te laten lopen
            // plus een geforceerde `removeBond()`. xDrip+'s eigen HCI-
            // capture van een geslaagde reconnect (zie hierboven bij
            // [canSkipJpake]) toont GEEN TIME_EXTENDED-schrijfactie (opcode
            // 0x06) en GEEN `removeBond()`/hernieuwde SMP-onderhandeling
            // wanneer de sensor zichzelf al bonded meldt — rechtstreeks van
            // de AuthStatus-indicatie naar Control's CCCD. Ronde 147's
            // hypothese (sensor weigert hergebruikte LTK) is dus verworpen;
            // het WERKELIJKE probleem was het overbodig herhalen van de
            // volledige ronde 0-3-handshake (nu opgelost via
            // [savedSessionKey] hierboven), niet de bond-status-afhandeling
            // hieronder, die al klopte.
            if (!status.isBonded) {
                // 28/08/2026 (editor, RONDE 144) — op basis van xDrip+'s
                // eigen `Plugin.java`: `receivedResponse()`'s `ChallengeReply`-tak
                // schakelt PRECIES in dit geval — authenticatie gelukt, maar
                // nog niet gebonden — naar een aanvullende certificaat-
                // gebaseerde wederzijdse-authenticatiestap (SendCertificate0
                // t/m SendKeyChallengeOut) vóórdat TIME_EXTENDED geschreven
                // wordt. Ronde 141/142/143's live-tests bevestigen dit: de
                // sensor stuurde zijn bond-trigger-indicatie simpelweg nooit
                // zonder deze stap. Zie DexcomG7CertMaterial.kt's kdoc voor
                // de volledige herkomst van het benodigde sleutelmateriaal.
                if (!runCertificateExchange(gatt, authChar, extraChar)) {
                    return failHandshake(gatt, "certificaatuitwisseling mislukt")
                }

                // 28/08/2026 (editor, RONDE 141, op basis van een ECHTE
                // geslaagde koppeling — de gebruiker liet xDrip+ met dezelfde
                // sensor koppelen en leverde de HCI-snooplog uit DIE
                // bugreport aan). Dat bewijst wat Ronde 139's write-ack+vaste-
                // marge-aanpak nog miste: xDrip schrijft TIME_EXTENDED, wacht
                // op de Write Response — en wacht dan OOK nog op een
                // BINNENKOMENDE indicatie van de sensor zelf op hetzelfde
                // Authentication-kanaal (in de capture: bytes 06,00 — exact
                // [DexcomG7Protocol.isBondTrigger]'s TIME_EXTENDED_3-variant).
                // Pas 130ms NA die indicatie roept xDrip zijn koppelfunctie
                // aan. Dat komt letterlijk overeen met wat
                // `DexcomG7Protocol.TIME_EXTENDED`'s eigen kdoc al beschreef
                // ("de sensor vraagt om nu te bonden") maar wat nooit
                // daadwerkelijk afgewacht werd — de vaste 500ms-marge uit
                // Ronde 139 was een educated guess die de VERKEERDE
                // voorwaarde afwachtte (de eigen write-ack) i.p.v. het
                // ECHTE signaal (de sensor's eigen bond-trigger-indicatie).
                // Nu: dezelfde `awaitAuthIndication`-helper als alle andere
                // stappen in deze handshake, gewacht op de indicatie die
                // TIME_EXTENDED oplevert, en pas dan createBond().
                val bondTrigger = awaitAuthIndication {
                    writeCharacteristic(gatt, authChar, DexcomG7Protocol.TIME_EXTENDED)
                }
                when {
                    bondTrigger == null ->
                        DiagnosticFileLogger.log("DexcomG7: geen indicatie ontvangen na TIME_EXTENDED (timeout) — ga toch door met createBond(), maar dit wijkt af van het geverifieerde xDrip-patroon")
                    !DexcomG7Protocol.isBondTrigger(bondTrigger) ->
                        DiagnosticFileLogger.log("DexcomG7: onverwachte indicatie na TIME_EXTENDED (geen herkende bond-trigger): ${bondTrigger.joinToString(",")}")
                    else ->
                        DiagnosticFileLogger.log("DexcomG7: bond-trigger ontvangen, createBond() nu aanroepen")
                }
                pendingAfterBond = { onAuthAndBondReady(gatt) }
                // 27/08/2026 (editor, RONDE 138, na live-test — Bond state ging
                // steevast Unpaired -> Pairing -> ~250-300ms later status=19
                // (GATT_CONN_TERMINATE_PEER_USER), ZONDER dat ooit een
                // ACTION_PAIRING_REQUEST-broadcast binnenkwam, dus ook zonder dat
                // Android's systeemdialoog ooit verscheen — de sensor haakt zelf
                // af, diep in de BLE-koppelonderhandeling, ver vóór er sprake kan
                // zijn van een dialoog) — het probleem zat dus NIET bij het
                // onderdrukken/niet-onderdrukken van een dialoog (Ronde 136/137),
                // maar zit vóór dat punt.
                //
                // Onderzoek van xDrip+'s eigen G6-driver (Ob1G5CollectionService.
                // java, NightscoutFoundation/xDrip) bevestigt bovendien dat xDrip
                // op Android 8+ HELEMAAL geen ACTION_PAIRING_REQUEST-receiver meer
                // registreert ("Not registering pairing receiver on Android 8+")
                // — dat bevestigt onafhankelijk dat deze broadcast op moderne
                // Android sowieso niet (betrouwbaar) bij losse apps aankomt, wat
                // hier ook precies is waargenomen.
                //
                // De blijvende, plain `gatt.device.createBond()`-aanroep gebruikt
                // TRANSPORT_AUTO — op een device dat zowel classic (BR/EDR) als
                // LE ondersteunt kan Android dan een classic/dual koppelpoging
                // proberen naast/in plaats van LE, wat een LE-only accessoire als
                // de G7 (geen BR/EDR-radio) direct kan doen afhaken zodra de
                // koppelonderhandeling op dat verkeerde transport start — een
                // bekend Android BLE-koppelvalkuiltje, zie o.a. AOSP-issues en
                // meerdere community-rapporten met exact dit symptoom (BONDING ->
                // vrijwel onmiddellijke disconnect, geen dialoog).
                //
                // 27/08/2026 (editor, RONDE 138c, na build-fout — "Too many
                // arguments for 'fun createBond(): Boolean'") — CORRECTIE op
                // Ronde 138's kdoc: `createBond(int transport)` is WEL sinds
                // API 30 aanwezig in AOSP, maar staat gemarkeerd als
                // `@SystemApi` — hij zit dus niet in de publieke android.jar-
                // stub waartegen we compileren (compileSdk 34), vandaar de
                // compilerfout. Reflectie is de gangbare workaround hiervoor
                // (o.a. bij meerdere BLE-bibliotheken op GitHub) — de methode
                // zelf vereist geen system-permissie, ze is alleen uit de
                // publieke SDK-stub weggelaten. Met try/catch-fallback naar de
                // gewone, publieke `createBond()` als reflectie om wat voor
                // reden dan ook faalt (oudere Android-versie, hidden-API-
                // restrictie, fabrikant-afwijking, etc.) — nooit een harde
                // crash, hooguit terug naar het oude (TRANSPORT_AUTO-)gedrag.
                runCatching {
                    var usedReflectiveTransportLe = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        usedReflectiveTransportLe = runCatching {
                            val method = BluetoothDevice::class.java.getMethod(
                                "createBond",
                                Int::class.javaPrimitiveType
                            )
                            method.invoke(gatt.device, BluetoothDevice.TRANSPORT_LE)
                            true
                        }.getOrElse {
                            DiagnosticFileLogger.log("DexcomG7: reflectieve createBond(TRANSPORT_LE) faalde: ${it.message}")
                            false
                        }
                    }
                    if (!usedReflectiveTransportLe) {
                        gatt.device.createBond()
                    }
                }
            } else {
                onAuthAndBondReady(gatt)
            }
        }

        private fun failHandshake(gatt: BluetoothGatt, reason: String) {
            DiagnosticFileLogger.log("DexcomG7: pairing handshake mislukt: $reason")
            runCatching { gatt.disconnect() }
        }

        /**
         * 28/08/2026 (editor, RONDE 145, na de eerste ECHT geslaagde bonding
         * — bewijs: v158's bugreport toonde voor het eerst
         * `AuthStatusRx.isBonded == true` op een reconnect, dus de
         * certificaat-stap uit Ronde 144 werkt — maar de daaropvolgende
         * glucose-aanvraag kreeg nooit antwoord: de sensor verbrak de
         * verbinding (reden 0x13) ~200ms na de aanvraag, exact hetzelfde
         * "schrijf-ack, dan meteen weg"-patroon als de vele vorige rondes,
         * nu op een ANDERE plek in het protocol). Directe vergelijking met
         * xDrip+'s EIGEN bewezen geslaagde HCI-capture (dezelfde sessie als
         * Ronde 141-144's bewijs) op exact dit punt: xDrip+'s Control-
         * characteristic-CCCD-schrijf wordt gevolgd door een
         * `HandleValueInd` (INDICATIE) als antwoord op de glucose-aanvraag —
         * niet een `HandleValueNotif` (NOTIFICATIE). `useIndication = false`
         * hier was dus fout — de sensor verwacht dat wij indicaties
         * inschakelen op de Control-characteristic (net als op
         * Authentication), en verbreekt de verbinding zodra de aanvraag
         * binnenkomt op een kanaal dat daar niet klaar voor staat. */
        private fun onAuthAndBondReady(gatt: BluetoothGatt) {
            val controlChar = charControl
            if (controlChar == null) {
                _connectionState.value = ConnectionState.Error("Control characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }
            enableNotify(gatt, controlChar, useIndication = true) {
                scope.launch { requestGlucose(gatt, controlChar) }
            }
        }

        private suspend fun requestGlucose(gatt: BluetoothGatt, controlChar: BluetoothGattCharacteristic) {
            // 28/08/2026 (editor, RONDE 150, op verzoek — "geeft hij dan ook
            // de data als batterij en firmware version terug zoals xdrip
            // ook netjes doet") — batterij/firmware VÓÓR het glucoseverzoek
            // opgevraagd, precies dezelfde volgorde als DexcomG6Driver.kt's
            // runControlSequence() (mirror van xDrip+'s eigen
            // checkVersionAndBattery()-vóór-doGetData()-volgorde). Bewust NA
            // een geslaagde verbinding/CCCD-indicatie-inschakeling (deze
            // functie wordt pas aangeroepen vanuit onAuthAndBondReady()) en
            // bewust NIET blokkerend voor de glucose-uitwisseling zelf: een
            // timeout hier laat het glucoseverzoek gewoon doorgaan, zodat
            // dit nieuwe, nog niet tegen een echte G7 geverifieerde pad de
            // al bewezen kernfunctionaliteit niet in gevaar kan brengen.
            queryBatteryIfStale(gatt, controlChar)
            queryFirmwareIfStale(gatt, controlChar)

            val deferred = CompletableDeferred<DexcomG7Protocol.GlucoseRx?>()
            pendingGlucoseDeferred = deferred
            writeCharacteristic(gatt, controlChar, DexcomG7Protocol.buildGlucoseRequest())
            val result = withTimeoutOrNull(GLUCOSE_TIMEOUT_MS) { deferred.await() }
            pendingGlucoseDeferred = null
            if (result == null) {
                DiagnosticFileLogger.log("DexcomG7: geen glucose-antwoord binnen timeout")
                runCatching { gatt.disconnect() }
                return
            }
            handleGlucoseResult(gatt, result)
        }

        /** Zie [requestGlucose]'s kdoc voor de volgorde-overweging — letterlijk
         *  dezelfde staleness-cache-aanpak als DexcomG6Driver.kt's eigen
         *  batterijverzoek (BATTERY_QUERY_INTERVAL_MS, per-slot opgeslagen
         *  via AppSettings). Een timeout/`null`-antwoord wordt alleen gelogd
         *  — geen disconnect, geen invloed op de rest van deze cyclus. */
        /**
         * 28/08/2026 (editor, RONDE 151, CRITIEKE FIX — na live-test van
         * v164: "Hij geeft maar 1 keer bij opstarten connectie") — de
         * gebruiker's eigen diagnostiek-logbestand
         * (`fclglucolink_2026-08-28 17.58.txt`) laat exact zien wat er
         * misging: elke reconnect NA de eerste (die een volledige,
         * verse koppeling deed) schreef eerst het batterijverzoek
         * (oversloegen, want binnen 8u), toen het firmwareverzoek — de
         * sensor antwoordde daarop met `"unhandled Control opcode=32
         * bytes=32,2"` (opcode 0x20, een 2-byte echo van ONS EIGEN
         * opcode, GEEN opcode 0x21 met 18+ bytes zoals verwacht) — deze
         * sensor wijst het firmwareverzoek dus gewoon af. Omdat Ronde
         * 150's [queryFirmwareIfStale] de "laatst opgevraagd"-tijdstempel
         * ALLEEN bij een GESLAAGDE parse wegschreef (via
         * `setDexcomG7FirmwareInfo`), bleef die stempel voor deze sensor
         * voor altijd `null` — dus werd de AFGEWEZEN aanvraag bij ELKE
         * volgende reconnect herhaald, niet eens per 30 dagen. Erger: de
         * sensor bleek zelf, ~3,3s na die afwijzing, de verbinding te
         * verbreken (status=19) — VOORDAT het glucoseverzoek ooit
         * verstuurd werd. Resultaat: vrijwel elke reconnect na de eerste
         * strandde, precies het gemelde symptoom. Twee fixes hieronder:
         * (1) een APARTE "laatst GEPROBEERD"-tijdstempel (zie
         * AppSettings.setDexcomG7BatteryQueryAttemptAtMs/
         * setDexcomG7FirmwareQueryAttemptAtMs), geschreven VÓÓR de
         * schrijf-/wachtstap, dus onafhankelijk van succes — mirror van
         * DexcomG6Driver.kt's `setDexcomG6LastVersion2QueryAtMs`-patroon,
         * dat dit al goed deed; (2) [handleControlNotification] geeft nu
         * een ONHERKEND opcode meteen door aan een op dat moment
         * WACHTENDE batterij-/firmwaredeferred (fail-fast) i.p.v. de
         * volle 10-seconden-timeout te laten aflopen — zowel om geen
         * kostbare verbindingstijd te verspillen als om het risico te
         * verkleinen dat de sensor zelf ongeduldig wordt en de
         * verbinding verbreekt vóórdat het glucoseverzoek aan de beurt
         * is.
         */
        private suspend fun queryBatteryIfStale(gatt: BluetoothGatt, controlChar: BluetoothGattCharacteristic) {
            val lastAttempt = settings.getDexcomG7BatteryQueryAttemptAtMsOnce(slot)
            val stale = lastAttempt == null || System.currentTimeMillis() - lastAttempt > BATTERY_QUERY_INTERVAL_MS
            if (!stale) return
            settings.setDexcomG7BatteryQueryAttemptAtMs(slot, System.currentTimeMillis())
            val deferred = CompletableDeferred<DexcomG7Protocol.BatteryInfoRx?>()
            pendingBatteryDeferred = deferred
            writeCharacteristic(gatt, controlChar, DexcomG7Protocol.buildBatteryInfoRequest())
            val battery = withTimeoutOrNull(BATTERY_TIMEOUT_MS) { deferred.await() }
            pendingBatteryDeferred = null
            if (battery != null) {
                DiagnosticFileLogger.log("DexcomG7: battery voltageA=${battery.voltageA} voltageB=${battery.voltageB} temp=${battery.temperatureC}")
                settings.setDexcomG7BatteryInfo(slot, battery.voltageA, battery.voltageB, battery.temperatureC, System.currentTimeMillis())
            } else {
                DiagnosticFileLogger.log("DexcomG7: battery query timed out of niet ondersteund (pas over ${BATTERY_QUERY_INTERVAL_MS / 60_000}min weer geprobeerd)")
            }
        }

        /**
         * Zie [queryBatteryIfStale]'s kdoc voor de RONDE 151-fix
         * (attempt-tijdstempel, niet succes-tijdstempel). RONDE 152 voegt
         * hier bovenop: probeer xDrip+'s EIGEN prioriteitsvolgorde uit
         * `requiredNextFirmwareDetailsType()` — versie 1 (opcode 0x4A)
         * EERST, dan versie 0 (opcode 0x20, alleen relevant voor G7's
         * 6-karakter-txid-vorm), dan versie 2 (opcode 0x52) als laatste
         * redmiddel — i.p.v. Ronde 150's eenmalige (en, bleek uit een
         * live-test, verkeerd geraden) versie-0-poging. Dankzij Ronde 151's
         * fail-fast-dispatch resolvet een AFGEWEZEN variant vrijwel
         * onmiddellijk (geen 10s wachttijd meer per stap), dus drie
         * pogingen na elkaar proberen kost in de praktijk nauwelijks
         * verbindingstijd. Stopt bij de EERSTE geslaagde parse.
         */
        private suspend fun queryFirmwareIfStale(gatt: BluetoothGatt, controlChar: BluetoothGattCharacteristic) {
            val lastAttempt = settings.getDexcomG7FirmwareQueryAttemptAtMsOnce(slot)
            val stale = lastAttempt == null || System.currentTimeMillis() - lastAttempt > FIRMWARE_QUERY_INTERVAL_MS
            if (!stale) return
            settings.setDexcomG7FirmwareQueryAttemptAtMs(slot, System.currentTimeMillis())
            for (version in FIRMWARE_REQUEST_VERSION_ORDER) {
                val deferred = CompletableDeferred<DexcomG7Protocol.FirmwareVersionRx?>()
                pendingFirmwareDeferred = deferred
                writeCharacteristic(gatt, controlChar, DexcomG7Protocol.buildFirmwareVersionRequest(version))
                val firmware = withTimeoutOrNull(FIRMWARE_TIMEOUT_MS) { deferred.await() }
                pendingFirmwareDeferred = null
                if (firmware != null) {
                    DiagnosticFileLogger.log("DexcomG7: firmware=${firmware.firmwareVersion} bt=${firmware.bluetoothFirmwareVersion} hw=${firmware.hardwareVersion} (versie=$version)")
                    settings.setDexcomG7FirmwareInfo(
                        slot, firmware.firmwareVersion, firmware.bluetoothFirmwareVersion, firmware.hardwareVersion, System.currentTimeMillis()
                    )
                    return
                }
                DiagnosticFileLogger.log("DexcomG7: firmware-versie $version afgewezen/onherkend, volgende poging")
            }
            DiagnosticFileLogger.log("DexcomG7: firmware niet ondersteund/afgewezen na alle varianten (pas over ${FIRMWARE_QUERY_INTERVAL_MS / 86_400_000}d weer geprobeerd)")
        }

        /**
         * 27/08/2026 (editor, RONDE 130, na een live-test die ondanks de
         * Ronde 128/128b-fixes nog steeds "ronde 1: ongeldig bewijs" gaf,
         * ~250-400ms na de aftrap) — [trigger] wordt nu UITGEVOERD NADAT de
         * deferred al klaarstaat, niet ervoor. De oude volgorde (eerst
         * schrijven, dan pas de deferred aanmaken) had een — weliswaar smal —
         * race-venster: `writeCharacteristic()` keert meteen terug zodra de
         * OS-write is aangeboden, en de GATT-notificatie-callback loopt op
         * een ANDER thread (Binder-pool) dan deze coroutine. Kwam de
         * ExtraData-notificatie al binnen vóórdat
         * `pendingExtraDataPacketDeferred` gezet was, dan werd het pakket
         * stilzwijgend "verbruikt" door een lege deferred (no-op complete())
         * en verdween het uit de accumulator — een volgend, ONVOLLEDIG of
         * met een latere notificatie vermengd pakket kon dan alsnog de NIEUW
         * aangemaakte deferred vullen, met precies dit symptoom (snel, maar
         * ONGELDIG). Op zich een smal venster (BLE-rondetrip is normaliter
         * ruim boven de paar CPU-instructies ertussen), maar goedkoop en
         * correct om te sluiten, en niet uit te sluiten als (mede)oorzaak op
         * een trager/drukker toestel. Zie ook [logRound1ValidationFailure]
         * hieronder: mocht dit de oorzaak NIET blijken (ronde 1 faalt na deze
         * fix nog steeds), dan geeft die logging de ruwe bytes om het
         * daadwerkelijk te kunnen narekenen i.p.v. verder te gokken.
         */
        /**
         * 28/08/2026 (editor, RONDE 144) — xDrip+'s `Plugin.java`
         * `SendCertificate0` t/m `SendKeyChallengeOut`-toestandsketen,
         * letterlijk in volgorde gevolgd (zie DexcomG7CertMaterial.kt's kdoc
         * voor waarom dit nodig bleek en waar het sleutelmateriaal vandaan
         * komt):
         *  1. Kondig deel A aan (opcode 0x0b, which=0), wacht op de sensor se
         *     bevestigings-indicatie, stuur dan deel A zelf via ExtraData
         *     (chunked, net als de J-PAKE-rondepakketten hierboven).
         *  2. Zelfde voor deel B (which=1).
         *  3. Stuur een eigen willekeurige 16-byte "sign challenge" (opcode
         *     0x0c), de sensor se indicatie-antwoord bevat (na de eerste 2
         *     header-bytes) DIENS 16-byte uitdaging aan ONS terug — die
         *     ondertekenen we met de EC-privésleutel uit deel C
         *     ([DexcomG7Crypto.signWithCertPrivateKey]).
         *  4. Stuur die 64-byte handtekening via ExtraData, gevolgd door het
         *     vaste `CHALLENGE_OUT`-commando (opcode 0x0d) op Authentication,
         *     wacht op een indicatie (de inhoud wordt, precies zoals in
         *     xDrip+'s eigen `Plugin.java` — geen `case SendKeyChallengeOut`
         *     in `receivedResponse()` — niet geïnspecteerd, alleen als
         *     synchronisatiepunt gebruikt vóór de TIME_EXTENDED-stap
         *     hierna).
         * Geeft `false` terug (en logt) zodra een van de sensor-antwoorden
         * uitblijft — de aanroeper (`runPairingHandshake`) breekt dan af via
         * de bestaande `failHandshake`-route.
         */
        private suspend fun runCertificateExchange(
            gatt: BluetoothGatt,
            authChar: BluetoothGattCharacteristic,
            extraChar: BluetoothGattCharacteristic
        ): Boolean {
            awaitAuthIndication {
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildCertInfoRequest(0, DexcomG7CertMaterial.PART_A.size))
            } ?: run {
                DiagnosticFileLogger.log("DexcomG7: geen antwoord op certificaat-info-verzoek deel A")
                return false
            }
            writeChunked(gatt, extraChar, DexcomG7CertMaterial.PART_A)

            awaitAuthIndication {
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildCertInfoRequest(1, DexcomG7CertMaterial.PART_B.size))
            } ?: run {
                DiagnosticFileLogger.log("DexcomG7: geen antwoord op certificaat-info-verzoek deel B")
                return false
            }
            writeChunked(gatt, extraChar, DexcomG7CertMaterial.PART_B)

            val ourChallenge = DexcomG7Protocol.randomSignChallenge()
            val sensorChallenge = awaitAuthIndication {
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildSignChallenge(ourChallenge))
            }
            if (sensorChallenge == null || sensorChallenge.size < 18) {
                DiagnosticFileLogger.log("DexcomG7: geen (of te korte) sign-challenge-indicatie van de sensor")
                return false
            }
            val presponse = signWithCertPrivateKey(DexcomG7CertMaterial.PART_C, sensorChallenge.copyOfRange(2, 18))

            awaitAuthIndication {
                writeChunked(gatt, extraChar, presponse)
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.CHALLENGE_OUT)
            } ?: run {
                DiagnosticFileLogger.log("DexcomG7: geen antwoord op CHALLENGE_OUT")
                return false
            }

            DiagnosticFileLogger.log("DexcomG7: certificaatuitwisseling voltooid")
            return true
        }

        private suspend fun awaitExtraDataPacket(trigger: suspend () -> Unit): DexcomG7Packet? {
            val deferred = CompletableDeferred<DexcomG7Packet?>()
            pendingExtraDataPacketDeferred = deferred
            trigger()
            val result = withTimeoutOrNull(PAIRING_STEP_TIMEOUT_MS) { deferred.await() }
            pendingExtraDataPacketDeferred = null
            return result
        }

        private suspend fun awaitAuthIndication(trigger: suspend () -> Unit): ByteArray? {
            val deferred = CompletableDeferred<ByteArray?>()
            pendingAuthIndicationDeferred = deferred
            trigger()
            val result = withTimeoutOrNull(PAIRING_STEP_TIMEOUT_MS) { deferred.await() }
            pendingAuthIndicationDeferred = null
            return result
        }

        /**
         * 27/08/2026 (editor, RONDE 130) — de crypto zelf (DexcomG7Crypto.kt)
         * is deze ronde BYTE-VOOR-BYTE geverifieerd tegen de echte, vendored
         * xDrip+-broncode (MTR93600/OpenApsAIMI, dev_OnePlusG7-branch,
         * plugins/libkeks/.../jamorham/keks/{Config,Calc,Packet,JECPoint}.java
         * — rechtstreeks opgehaald en regel-voor-regel nagelopen, niet alleen
         * via een subagent-samenvatting) — inclusief de "alice"/"bob"
         * party-ID-hex-constanten (die op het eerste gezicht verdacht leken,
         * maar bleken byte-voor-byte te kloppen). Geen enkel verschil
         * gevonden. De race hierboven is een plausibele, maar niet zekere
         * verklaring. Om een VIERDE keer blind gokken te voorkomen: log de
         * ruwe hex van het ontvangen ronde-1-pakket + onze eigen "bob"
         * party-ID hier, zodat een volgende live-log daadwerkelijk narekenbaar
         * is (bv. handmatig de zero-knowledge-hash narekenen) i.p.v. opnieuw
         * te moeten raden.
         */
        private fun logRound1ValidationFailure(ctx: DexcomG7JpakeContext, round1: DexcomG7Packet) {
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
            DiagnosticFileLogger.log(
                "DexcomG7: ronde1-validatie MISLUKT — diagnose: " +
                    "bob=${hex(ctx.bob)} alice=${hex(ctx.alice)} pairingCodeLen=${ctx.passwordBytes.size} " +
                    "round1.hash=${round1.hash.toString(16)} " +
                    "round1.point1=${hex(round1.point1.getEncoded(false))} " +
                    "round1.point2=${hex(round1.point2.getEncoded(false))} " +
                    "ourKeyA.publicKey=${hex(ctx.keyA.publicKey.getEncoded(false))}"
            )
        }

        /**
         * 17/08/2026 (editor, RONDE 112) — zie DexcomG6Driver.kt's
         * `handleGlucoseResult()`-kdoc voor het volledige "niet elk getal is
         * een echte meting"-inzicht — hier hergebruikt via
         * [DexcomG6CalibrationState] (zie klasse-kdoc voor waarom dat
         * hergebruik correct is). BEWUST GEEN fallback-opwarmtijd-gate zoals
         * G6's Ronde 74 (nog geen bevestigd G7-signaal voor "sessie
         * bevestigd gestart" — sessies starten bij de G7 automatisch, zie
         * klasse-kdoc, dus dat concept is sowieso minder scherp gedefinieerd
         * dan bij G6's expliciete sessie-start-bericht) — puur de
         * transmitter se eigen statusbyte bepaalt hier bruikbaarheid.
         */
        private fun handleGlucoseResult(gatt: BluetoothGatt, rx: DexcomG7Protocol.GlucoseRx) {
            val nowMs = System.currentTimeMillis()
            lastSuccessfulConnectionAtMs = nowMs
            if (cadenceAnchorAtMs == null) cadenceAnchorAtMs = nowMs
            if (sensorStartedAtMs == 0L) sensorStartedAtMs = nowMs
            scope.launch { settings.setDexcomG7LastConnectedAtMs(slot, nowMs) }

            val calibrationState = DexcomG6CalibrationState.fromRaw(rx.calibrationStateRaw)
            val glucoseUsable = (calibrationState.usableGlucose() || calibrationState.insufficientCalibration()) &&
                !rx.glucoseIsDisplayOnly

            _connectionState.value = ConnectionState.Connected(gatt.device.address, gatt.device.name)
            if (glucoseUsable) {
                val reading = GlucoseReading(
                    glucoseMgdl = rx.glucoseMgdl.toDouble(),
                    trendMgdlPerMin = (rx.trendMgdlPerMin ?: 0.0).toFloat(),
                    timestampMs = nowMs,
                    sensorStartedAtMs = sensorStartedAtMs,
                    sensorType = SensorType.DEXCOM_G7
                )
                DiagnosticFileLogger.log(
                    "DexcomG7: glucose=${rx.glucoseMgdl} seq=${rx.sequence} display_only=${rx.glucoseIsDisplayOnly} state=$calibrationState"
                )
                scope.launch { _readings.emit(reading) }
            } else {
                DiagnosticFileLogger.log(
                    "DexcomG7: glucose value ${rx.glucoseMgdl} IGNORED (not a real measurement) — state=$calibrationState display_only=${rx.glucoseIsDisplayOnly}"
                )
            }

            // Mirror van DexcomG6Driver.kt/xDrip+'s eigen patroon: verbinding
            // actief sluiten na een geslaagde uitwisseling, voorspellend
            // terugkomen — zie klasse-kdoc.
            runCatching { gatt.disconnect() }
        }

        /** 17/08/2026 (editor, RONDE 112) — stuurt [data] in stukjes van
         *  hoogstens [CHUNK_SIZE] bytes met [CHUNK_DELAY_MS] pauze ertussen —
         *  mirror van xDrip+'s Ob1G5StateMachine.doNext()'s ExtraData-
         *  schrijflus (zie DexcomG7Protocol.kt's kdoc-citaat). */
        private suspend fun writeChunked(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK_SIZE, data.size)
                // RONDE 128: WRITE_TYPE_NO_RESPONSE, zie kdoc bij
                // POST_CHUNK_SETTLE_MS hierboven — mirror van xDrip+'s
                // ExtraData-writes tijdens de J-PAKE-rondes.
                writeCharacteristic(
                    gatt, characteristic, data.copyOfRange(offset, end),
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                offset = end
                if (offset < data.size) delay(CHUNK_DELAY_MS)
            }
            // RONDE 128: zelfde 500ms-marge als xDrip+'s doNext() na de
            // volledige chunk-reeks, vóórdat de aanroeper de bijbehorende
            // ronde-commando-byte naar Authentication schrijft.
            delay(POST_CHUNK_SETTLE_MS)
        }

        private val pendingAfterNotifyEnabled = mutableMapOf<java.util.UUID, () -> Unit>()

        private fun enableNotify(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            useIndication: Boolean,
            onDone: () -> Unit
        ) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                DiagnosticFileLogger.log("DexcomG7: setCharacteristicNotification failed for ${characteristic.uuid}")
            }
            val descriptor = characteristic.getDescriptor(DexcomG7Protocol.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                onDone()
                return
            }
            pendingAfterNotifyEnabled[characteristic.uuid] = onDone
            val enableValue = if (useIndication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, enableValue)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        // RONDE 128: writeType nu een parameter (default WRITE_TYPE_DEFAULT,
        // ongewijzigd voor alle bestaande Authentication-aanroepen) i.p.v.
        // hardcoded — zie kdoc bij POST_CHUNK_SETTLE_MS hierboven:
        // writeChunked() geeft hier voortaan expliciet WRITE_TYPE_NO_RESPONSE
        // mee voor ExtraData, mirror van xDrip+'s echte gedrag.
        private fun writeCharacteristic(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "Unpaired"
        BluetoothDevice.BOND_BONDING -> "Pairing"
        BluetoothDevice.BOND_BONDED -> "Paired"
        else -> "Unknown($state)"
    }

    private fun registerBondReceiver(context: Context, deviceAddress: String) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != deviceAddress) return

                // 27/08/2026 (editor, RONDE 136) — na een live-test die,
                // dankzij Ronde 134/135's fixes, voor het eerst de VOLLEDIGE
                // J-PAKE-handshake zag slagen (auth-aanvraag EN uitdaging-
                // antwoord beide correct), meldde de gebruiker daarna zelf
                // kort een Android-systeempopup "onjuiste koppelcode" te
                // hebben gezien. Eerste poging: hier `ACTION_PAIRING_REQUEST`
                // afvangen, zelf `device.setPin(...)` aanbieden, en Android's
                // eigen dialoog onderdrukken met `abortBroadcast()`.
                //
                // 27/08/2026 (editor, RONDE 137, direct daarna, op basis van
                // een verduidelijking van de gebruiker) — DIE `abortBroadcast
                // ()` was fout. xDrip+ onderdrukt Android's eigen koppel-
                // dialoog helemaal NIET: het is precies dát systeem-dialoog
                // (met het vinkje "maak deze koppeling permanent" en de optie
                // om Bluetooth-toegang tot contacten/telefoon te geven) dat
                // xDrip+'s gebruikers te zien krijgen en zelf bevestigen —
                // geen stille, app-aangestuurde PIN-invulling. Door
                // `abortBroadcast()` aan te roepen onderdrukten we dus precies
                // het dialoog dat de gebruiker nodig had om de koppeling te
                // voltooien — vandaar dat "de koppeling waarin het vinkje
                // wordt gezet dat de koppeling permanent is" nooit meer
                // voorbijkwam na Ronde 136. `abortBroadcast()` is nu
                // verwijderd: Android's eigen systeemdialoog krijgt weer de
                // kans om te verschijnen (en de gebruiker moet 'm zelf
                // bevestigen, zoals bij xDrip+). `setPin()` blijft staan als
                // onschadelijke, best-effort aanvulling (relevant als de
                // aangeboden variant daadwerkelijk PIN-invoer is i.p.v. een
                // simpele bevestiging) — dat vult höchstens een PIN-veld,
                // het beslist niet zelf en toont geen eigen UI.
                if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    DiagnosticFileLogger.log("DexcomG7: Pairing request ontvangen (variant=$variant) — Android's eigen dialoog NIET onderdrukt, bevestig 'm zelf op het toestel")
                    if (pairingCode.length == 4) {
                        val pinAccepted = runCatching {
                            device.setPin(pairingCode.toByteArray(Charsets.US_ASCII))
                        }.getOrElse {
                            DiagnosticFileLogger.log("DexcomG7: setPin() faalde: ${it.message}")
                            false
                        }
                        DiagnosticFileLogger.log("DexcomG7: setPin() resultaat=$pinAccepted")
                    } else {
                        DiagnosticFileLogger.log("DexcomG7: geen (geldige) koppelcode beschikbaar voor setPin()")
                    }
                    return
                }

                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                // 27/08/2026 (editor, RONDE 131) — nu ELKE bond-state-overgang
                // gelogd (niet alleen BOND_BONDED), mirror van xDrip+'s eigen
                // informatieve "Bond state N Naam bs: ... was ..."-logregels —
                // bleek in hun log cruciaal om te zien HOEVEEL pogingen (en
                // tussentijdse terugvallen naar Unpaired) er nodig waren
                // voordat het bonden daadwerkelijk lukte.
                DiagnosticFileLogger.log(
                    "DexcomG7: Bond state ${bondStateName(bondState)} (was ${bondStateName(previousBondState)})"
                )
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    DiagnosticFileLogger.log("DexcomG7: bonded, resuming after-bond action")
                    pendingAfterBond?.invoke()
                    pendingAfterBond = null
                }
            }
        }
        bondReceiver = receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            // Hoge prioriteit: onze best-effort setPin()-poging draait zo
            // vóór Android's eigen systeem-koppeldialoog. Sinds Ronde 137
            // wordt de broadcast NIET meer afgebroken — Android's eigen
            // dialoog (met het "permanent"-vinkje) moet gewoon verschijnen.
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        context.registerReceiver(receiver, filter)
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        runCatching { appContext?.unregisterReceiver(receiver) }
        bondReceiver = null
    }

    private fun updateConnectionStatusAfterDisconnect() {
        val staleSince = lastSuccessfulConnectionAtMs
        if (staleSince != null) {
            val minutesSince = (System.currentTimeMillis() - staleSince) / 60_000L
            if (minutesSince >= RECONNECT_STATUS_WARNING_MINUTES) {
                _connectionState.value = ConnectionState.Error(
                    "No connection for $minutesSince minute${if (minutesSince == 1L) "" else "s"} " +
                        "(still trying). Make sure the transmitter is nearby, awake, and not already " +
                        "connected to another phone or app (most CGM sensors only allow one " +
                        "active connection at a time)."
                )
                return
            }
        }
        if (_connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Connecting("")
        }
    }
}
