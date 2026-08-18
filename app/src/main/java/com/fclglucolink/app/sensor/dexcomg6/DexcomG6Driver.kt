package com.fclglucolink.app.sensor.dexcomg6

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
 * FCLGlucoLink — Dexcom G6-driver (RONDE 55/56)
 * ============================================================================
 *
 * 08/08/2026 (editor) — geport van xDrip+'s `Ob1G5CollectionService`/
 * `Ob1G5StateMachine` (broncode door de gebruiker aangeleverd — "dat heb ik
 * eigenlijk altijd gebruikt en was gewoon 99,9% stabiel"), plus de crypto/
 * pakket-lagen in DexcomG6Crypto.kt/DexcomG6Protocol.kt. Zelfde architectuur
 * als CareSensAirDriver.kt (scan-dan-verbind, gedeelde ScanRateLimiter,
 * PredictiveReconnectAlarm) — met twee bewuste, aan xDrip+ ontleende
 * verschillen t.o.v. CareSens Air:
 *
 * 1) NA elke geslaagde meting wordt de GATT-verbinding actief gesloten
 *    (`gatt.disconnect()`), in plaats van open te blijven staan — mirror van
 *    xDrip+'s eigen `prepareToWakeup()`/`stopConnect()`-patroon, en precies
 *    het gedrag dat de gebruiker als "99,9% stabiel" ervoer.
 * 2) Oplopende foutenbackoff (1s, +100ms per mislukking, tot 10s) i.p.v. een
 *    vaste 60s-fallback bij een mislukte verbindpoging.
 *
 * 08/08/2026 (editor, RONDE 56 — TWEE BELANGRIJKE CORRECTIES op ronde 55,
 * gevonden vóór de eerste live-test door dieper in xDrip+'s daadwerkelijke
 * `doGetData()`/`checkVersionAndBattery()`-methodes te kijken i.p.v. alleen
 * de boodschap-klassen zelf):
 *
 * A) VERKEERDE CHARACTERISTIC — ronde 55 stuurde/las sessie-start en
 *    glucose via wat het "Communication" noemde (F8083533). In xDrip+'s
 *    ECHTE verkeer wordt die UUID nergens gebruikt — alles (sessie starten/
 *    stoppen, glucose/batterij/versie opvragen) loopt via [DexcomG6Protocol.CONTROL]
 *    (F8083534). Tegen een echte transmitter zou ronde 55's driver dus
 *    nooit iets teruggekregen hebben.
 * B) GLUCOSE IS EEN VERZOEK, GEEN PUSH — ronde 55 nam aan dat de transmitter
 *    na het inschakelen van notificaties uit zichzelf een glucosewaarde
 *    stuurt. In werkelijkheid moet eerst een expliciet verzoek (opcode
 *    0x30, [DexcomG6Protocol.buildGlucoseRequest]) verstuurd worden — pas
 *    dán komt het antwoord (opcode 0x31). Zonder dit verzoek zou de driver
 *    voor altijd op een lege verbinding hebben zitten wachten.
 *
 * Verder deze ronde: sessie-start ondersteunt nu ook een sensor-code (nieuwe
 * fysieke sensor starten, zie DexcomG6CalibrationCode.kt) i.p.v. altijd het
 * "geen code"-pad, periodieke KeepAlive (opcode 0x06 via Authentication,
 * xDrip+'s eigen patroon om de verbinding tijdens een langere uitwisseling
 * open te houden), en batterij/temperatuur-polling (~8 uur).
 *
 * VOLGORDE PER VERBINDING (mirror van xDrip+'s doGetData/checkVersionAndBattery):
 *  connect -> discoverServices -> notify Authentication -> auth-handshake
 *  -> (bonden indien nodig) -> KeepAlive-taak start -> notify Control
 *  -> [ALLEEN als er een sensor-code klaarstaat: sessie-start-met-code
 *     versturen, antwoord afwachten] -> [ALLEEN als batterijdata >~8u oud
 *     is: batterijverzoek versturen, antwoord afwachten en bewaren]
 *  -> glucoseverzoek versturen -> glucose-antwoord afwachten -> meting
 *     doorgeven -> verbinding sluiten -> voorspellend opnieuw verbinden.
 *
 * FASE-SCOPE: backfill (historische data na een gat) wordt aangevraagd maar
 * de respons alleen gelogd, nog niet in metingen omgezet. G6-vingerprik-
 * kalibratie-terugsturen is expliciet NIET geport (kalibratie gebeurt al in
 * CalibrationEngine.kt) — zie DexcomG6Protocol.kt's kdoc voor het verschil
 * met de sensor-code, die WEL geport is.
 *
 * Dit is, zoals CareSens Air's eerste versie destijds, gebaseerd op
 * protocol-analyse en nog niet tegen een echte G6-transmitter geverifieerd
 * — verwacht bijstellen na de eerste live-test.
 */
/**
 * 10/08/2026 (editor, RONDE 79 -- 2-sensoren-architectuur) -- [slot] is nieuw:
 * deze driver leest/schrijft intern zelf al zijn eigen transmitter-ID,
 * sessie-start-boekhouding, warmup/batterij-pollingstatus en kalibratiestatus
 * rechtstreeks via AppSettings (zie de vele settings.*(slot, ...)-aanroepen
 * verderop) -- dat is dieper ingebed dan de "drivers weten niet in welke
 * slot ze draaien"-aanname uit SensorDriver.kt's kdoc bij SensorSlot. Elke
 * driver-INSTANTIE hoort dus bij precies één slot, meegegeven bij aanmaak
 * (SensorRegistry.createDriver(sensorType, slot)) -- niet per losse
 * connect()-aanroep, want ook buildPairingListFilter() (aangeroepen door
 * PairingScreen VOORDAT er ooit connect() is aangeroepen) heeft deze
 * identiteit al nodig om de juiste slot's opgeslagen transmitter-ID te lezen.
 */
class DexcomG6Driver(private val slot: SensorSlot) : SensorDriver {

    override val sensorType: SensorType = SensorType.DEXCOM_G6

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

    private var transmitterId: String = ""

    /** 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's kdoc en
     *  AppSettings.bondLossAutoRecoveryEnabled's kdoc: eenmalig gelezen in
     *  connect() (samen met transmitterId hieronder), gebruikt in
     *  startConnectScan()'s scan-resultaat vlak vóór connectGatt(). Al
     *  false als er nog nooit eerder succesvol verbonden is met dit
     *  toestel — zie de voorwaarde daar. */
    private var bondLossAutoRecoveryEnabled: Boolean = false

    private var charControl: BluetoothGattCharacteristic? = null
    private var charAuthentication: BluetoothGattCharacteristic? = null
    private var charBackfill: BluetoothGattCharacteristic? = null

    private var bondReceiver: BroadcastReceiver? = null
    private var connectScanCallback: ScanCallback? = null
    private var reconnectJob: Job? = null
    private var statusTickerJob: Job? = null
    private var keepAliveJob: Job? = null

    private var lastSuccessfulConnectionAtMs: Long? = null

    // 10/08/2026 (editor, RONDE 86 — mirror van CareSensAirDriver.kt's
    // zelfde-ronde-fix, zie dat bestand's kdoc bij computeReconnectCooldownMs()
    // voor de volledige analyse) — vast ankerpunt van deze connect()-sessie's
    // 5-minuten-raster, ÉÉN keer gezet bij de eerste geslaagde meting, reset
    // samen met lastSuccessfulConnectionAtMs. Voorkomt dat een eenmalige
    // vertraging (bv. een scanbotsing met de andere slot) de hele cadans
    // permanent laat verschuiven.
    private var cadenceAnchorAtMs: Long? = null
    private var sensorStartedAtMs: Long = 0L

    // 08/08/2026 (editor) — xDrip+'s eigen oplopende backoff
    // (`error_backoff_ms`, Ob1G5CollectionService.java) i.p.v. CareSens
    // Air's vaste 60s-fallback — zie klasse-kdoc.
    private var errorBackoffMs = 1_000L
    private val maxErrorBackoffMs = 10_000L

    // ---- Auth-sessiestatus (per verbindpoging, gereset in resetAuthState()). ----
    private var myAuthToken: ByteArray? = null
    private var authenticated = false
    private var bonded = false

    // 08/08/2026 (editor, RONDE 56) — CompletableDeferreds die de
    // sequentiële Control-uitwisseling (sessie-start? -> batterij? ->
    // glucose) koppelen aan de asynchrone GATT-notificaties — zie
    // handleControlNotification()/runControlSequence()'s kdoc.
    private var pendingSessionStartDeferred: CompletableDeferred<DexcomG6Protocol.SessionStartRx?>? = null
    private var pendingBatteryDeferred: CompletableDeferred<DexcomG6Protocol.BatteryInfoRx?>? = null
    private var pendingGlucoseDeferred: CompletableDeferred<DexcomG6Protocol.GlucoseRx?>? = null
    // 09/08/2026 (editor, RONDE 66) — zelfde patroon als de drie hierboven,
    // nu voor de nieuwe stop-voor-start- en versie2-uitwisselingen — zie
    // runControlSequence()'s kdoc.
    private var pendingSessionStopDeferred: CompletableDeferred<DexcomG6Protocol.SessionStopRx?>? = null
    private var pendingVersionRequest2Deferred: CompletableDeferred<DexcomG6Protocol.VersionRequest2Rx?>? = null

    companion object {
        // 10/08/2026 (editor, RONDE 85 — op verzoek, na live-log-analyse:
        // "dexcom zendt gewoon om de 5 minuten dus als je afgerond zegt
        // 5:11 en 9:48 dan valt er bij de 9:48 gewoon eentje weg") — WAS
        // 280_000L (4m40s, dus maar 20s marge vóór de verwachte 300s-markering).
        // Analyse van fclglucolink_2026-08-10-c3960ea6.txt (21 metingen sinds
        // koppeling 19:33) liet een strikt patroon zien: seq+1/~311s ("raak",
        // 8x, opvallend consistent 311,1–311,8s) afgewisseld met seq+2/~588s
        // ("mis" — een hele 5-minuten-uitzending overgeslagen, moet dan
        // wachten op de VOLGENDE, 300s later). Root cause: de G6-transmitter
        // heeft — anders dan CareSens Air, die vrijwel continu adverteert,
        // zie CareSensAirDriver.kt's Ronde-31-kdoc — maar een KORT
        // verbindbaar venster rond elke 5-minuten-meting; met maar 20s marge
        // ving een reguliere scan-dispatch-vertraging (zie ScanFilter-kdoc
        // hieronder: eerder tot 117s gemeten) dat venster regelmatig net niet.
        // Zelfde soort fix als CareSens Air destijds al kreeg (Ronde 32:
        // leadtime verkort om meer marge te geven) — hier verlaagd naar
        // 240_000L (4m00s), dus 60s marge i.p.v. 20s. Dit lost het
        // scan-budget-conflict tussen de twee slots (Ronde 83) NIET op — dat
        // is een apart mechanisme — maar juist DIT patroon (consistent, ook
        // ruim vóór de Dexcom-koppeling van vanavond al zo, onafhankelijk van
        // welke slot de AAPS-actieve is) zit in de leadtime-marge zelf.
        //
        // 10/08/2026 (editor, RONDE 86 — vervolg-melding, zelfde avond: "sinds
        // 22:40 komt de caresens om de 6 minuten") — vervangen door twee losse
        // constanten. Zie computeReconnectCooldownMs()'s kdoc hieronder en
        // CareSensAirDriver.kt's zelfde-ronde-kdoc voor de volledige analyse:
        // een eenmalige scanbotsing tussen de twee slots verschoof CareSens
        // Air's cadans permanent, omdat de oude formule ("laatste meting +
        // leadtime") geen absoluut anker had om weer naar terug te snappen.
        // 240 000L hierboven (60s marge, ongewijzigd als GETAL) wordt nu twee
        // constanten: SENSOR_PERIOD_MS (300 000L) en SCAN_START_MARGIN_MS
        // (60 000L) — 300 000-60 000=240 000, dus dezelfde marge als net
        // ingesteld, nu binnen een zelf-corrigerende formule.
        private const val SENSOR_PERIOD_MS = 300_000L // 5 min — Dexcom G6's eigen meetcadans.
        private const val SCAN_START_MARGIN_MS = 60_000L // marge vóór het verwachte rasterpunt.

        // 08/08/2026 (editor, RONDE 56, op verzoek — "de spanning van de
        // batterij, de temperatuur [...] worden een keer per 8 uur
        // opgevraagd") — xDrip+'s eigen standaardwaarde is 12 uur
        // (BATTERY_READ_PERIOD_MS), maar de gebruiker noemde expliciet 8 uur
        // als wat die gewend is te zien — dat aangehouden.
        private const val BATTERY_QUERY_INTERVAL_MS = 8L * 60 * 60 * 1000

        private const val KEEP_ALIVE_INTERVAL_MS = 45_000L
        private const val SESSION_START_TIMEOUT_MS = 15_000L
        private const val BATTERY_TIMEOUT_MS = 10_000L
        private const val GLUCOSE_TIMEOUT_MS = 20_000L
        // 09/08/2026 (editor, RONDE 66) — zelfde stijl timeout als de
        // sessie-start hierboven.
        private const val SESSION_STOP_TIMEOUT_MS = 15_000L
        private const val VERSION_REQUEST2_TIMEOUT_MS = 10_000L
        // 09/08/2026 (editor, RONDE 66, VERKORT IN RONDE 68) — als een
        // transmitter/firmware dit verzoek simpelweg niet beantwoordt, moet
        // dat niet elke ~5 minuten opnieuw geprobeerd worden. Was 8 uur
        // (zelfde interval als de batterij-polling) — na ronde 68's fix
        // (zie DexcomG6Protocol.parseVersionRequest2()'s kdoc: een 0x53-
        // "short form"-antwoord werd tot dan onterecht als "geen antwoord"
        // behandeld) bleek dat een groot deel van de vroegere "timeouts" in
        // werkelijkheid GEEN echte hardware-limitatie was maar een bug in
        // de parser — een 8-uurs terugval-tijd zou zo'n al opgeloste
        // situatie onnodig lang laten "vastzitten" op het oude, foutieve
        // resultaat (precies het probleem dat optrad na de live-test op
        // v72: de eerste, mislukte poging blokkeerde alle volgende pogingen
        // voor de rest van de dag). 15 minuten is nog steeds ruim genoeg om
        // een ECHT niet-ondersteunende transmitter niet elke verbindcyclus
        // (~5 min) te blijven lastigvallen, maar laat een bugfix als deze
        // wél binnen een paar reconnects zichtbaar worden i.p.v. pas de
        // volgende dag.
        private const val VERSION_REQUEST2_RETRY_INTERVAL_MS = 15L * 60 * 1000

        // 09/08/2026 (editor, RONDE 76, CRITICAL — na live-melding: "de
        // samsung telefoon blijft hangen zodra het scherm zwart wordt en hij
        // gaat ook niet meer lopen") — zie scheduleRearm()'s kdoc hieronder
        // voor de volledige analyse. Zelfde waarde (390s) als CareSens Air's
        // al langer bewezen SCAN_REARM_INTERVAL_MS (CareSensAirDriver.kt,
        // sinds ronde 26) — bewust hetzelfde getal overgenomen i.p.v. een
        // eigen gok, dit is geen Dexcom-specifieke afweging.
        private const val SCAN_REARM_INTERVAL_MS = 390_000L

        // 11/08/2026 (editor, RONDE 90 — na live-melding: "Bij de dexcom
        // staat er error terwijl er geen error is") — zelfde constante en
        // zelfde waarde als CareSensAirDriver.kt's
        // RECONNECT_STATUS_WARNING_MINUTES (sinds ronde 33): zie
        // updateConnectionStatusAfterDisconnect()'s kdoc hieronder voor de
        // volledige uitleg waarom deze driver nu ook pas ná deze drempel
        // een echte foutmelding toont i.p.v. bij elke routinematige
        // herverbinding.
        private const val RECONNECT_STATUS_WARNING_MINUTES = 7L
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun advertisedNameFor(transmitterId: String): String? {
        if (transmitterId.length != 6) return null
        return "Dexcom" + transmitterId.takeLast(2)
    }

    /**
     * 08/08/2026 (editor) — de G6 heeft geen barcode/QR zoals CareSens Air:
     * de gebruiker typt de 6-karakter transmitter-ID in (zie
     * ui/DexcomG6SetupScreen.kt), en dat bepaalt de verwachte BLE-
     * advertentienaam ("Dexcom" + laatste 2 tekens).
     */
    override suspend fun buildPairingListFilter(context: Context): ((String?, String) -> Boolean)? {
        val id = AppSettings(context).getDexcomG6TransmitterIdOnce(slot) ?: return null
        val expectedName = advertisedNameFor(id) ?: return null
        return { deviceName, _ -> deviceName != null && deviceName.equals(expectedName, ignoreCase = true) }
    }

    override fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth isn't available or is turned off.")
            return
        }
        leScanner = scanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
        runCatching { scanner.startScan(emptyList(), settings, callback) }
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
            transmitterId = settings.getDexcomG6TransmitterIdOnce(slot).orEmpty()
            if (transmitterId.length != 6) {
                _connectionState.value = ConnectionState.Error(
                    "No (valid) Dexcom G6 transmitter ID saved — pair the sensor again."
                )
                return@launch
            }
            // 08/08/2026 (editor) — zie AppSettings.kt's kdoc bij
            // getOrInitSensorStartedAtMs(): generieke fallback, net als
            // G7/SmartGuide zolang die niet geport zijn.
            sensorStartedAtMs = settings.getOrInitSensorStartedAtMs(slot)
            // 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's
            // kdoc: alleen "aan" als de gebruiker de schakelaar heeft
            // aangezet ÉN er al eerder succesvol verbonden is met dit
            // toestel (anders is een verse BOND_NONE gewoon normaal, geen
            // "verlies").
            bondLossAutoRecoveryEnabled = settings.isBondLossAutoRecoveryEnabledOnce() &&
                settings.getDexcomG6LastConnectedAtMsOnce(slot) != null
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
        myAuthToken = null
        authenticated = false
        bonded = false
        pendingSessionStartDeferred?.complete(null)
        pendingBatteryDeferred?.complete(null)
        pendingGlucoseDeferred?.complete(null)
        pendingSessionStopDeferred?.complete(null)
        pendingVersionRequest2Deferred?.complete(null)
        pendingSessionStartDeferred = null
        pendingBatteryDeferred = null
        pendingGlucoseDeferred = null
        pendingSessionStopDeferred = null
        pendingVersionRequest2Deferred = null
    }

    override fun disconnect() {
        userStopped = true
        // 13/08/2026 (editor, RONDE 102) — mirror van CareSensAirDriver.kt's
        // identieke toepassing, zie AapsSlotSchedule.kt's klasse-kdoc bij
        // [clear]: zonder dit blijft Dexcom's laatste voorspelling staan
        // nadat de gebruiker Dexcom tussentijds stopt, en zou een dan nog
        // draaiende CareSens Air (RONDE 101) voor altijd om een spookraster
        // blijven schuiven i.p.v. gewoon zijn eigen 5-minuten-ritme aan te
        // houden.
        AapsSlotSchedule.clear(slot)
        reconnectJob?.cancel()
        statusTickerJob?.cancel()
        keepAliveJob?.cancel()
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
    // Scan-dan-verbind — identiek patroon aan CareSensAirDriver.kt,
    // gedeelde ScanRateLimiter (zie sensor/ble/ScanRateLimiter.kt's kdoc).
    // ============================================================

    /**
     * 10/08/2026 (editor, RONDE 86 — mirror van CareSensAirDriver.kt's
     * zelfde-ronde-fix, zie dat bestand's kdoc bij deze zelfde functienaam
     * voor de volledige analyse) — anker-gebaseerde voorspelling i.p.v. kaal
     * "laatste meting + marge" doorrekenen. `lastSuccessfulConnectionAtMs`
     * wordt gesnapt naar het dichtstbijzijnde veelvoud van SENSOR_PERIOD_MS
     * sinds `cadenceAnchorAtMs` (afgerond, niet afgekapt) — zo trekt een
     * eenmalige vertraging (bijvoorbeeld door een scanbotsing met de andere
     * slot) de cadans niet permanent scheef; de eerstvolgende voorspelling
     * mikt gewoon weer op het oorspronkelijke rasterpunt.
     */
    private fun computeReconnectCooldownMs(): Long {
        val lastReadingAtMs = lastSuccessfulConnectionAtMs ?: return SENSOR_PERIOD_MS - SCAN_START_MARGIN_MS
        val anchor = cadenceAnchorAtMs ?: lastReadingAtMs
        val periodsElapsed = Math.round((lastReadingAtMs - anchor) / SENSOR_PERIOD_MS.toDouble())
        val gridReadingAtMs = anchor + periodsElapsed * SENSOR_PERIOD_MS
        val predictedNextReadingAtMs = gridReadingAtMs + SENSOR_PERIOD_MS
        // 12/08/2026 (editor, RONDE 100) — onvoorwaardelijk publiceren, zie
        // AapsSlotSchedule.kt's klasse-kdoc en CareSensAirDriver.kt's
        // identieke toepassing hiervan.
        AapsSlotSchedule.publish(slot, predictedNextReadingAtMs)
        val remainingMs = predictedNextReadingAtMs - SCAN_START_MARGIN_MS - System.currentTimeMillis()
        val result = remainingMs.coerceAtLeast(0L)
        DiagnosticFileLogger.log(
            "DexcomG6: computeReconnectCooldownMs: lastReadingAt=$lastReadingAtMs anchor=$anchor gridReadingAt=$gridReadingAtMs -> cooldownMs=$result (voorspeld, zelf-corrigerend)"
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
            // 10/08/2026 (editor, RONDE 83) — zie ScanRateLimiter.kt's kdoc
            // en CareSensAirDriver.kt's identieke toepassing hiervan: deze
            // slot krijgt voorrang op het gedeelde scan-budget zodra hij de
            // AAPS-zendende slot is. Verse lezing bij elke scanpoging.
            val currentAapsSlot = settings.aapsActiveSlot.first()
            // 13/08/2026 (editor, RONDE 103) — zie AapsSlotSchedule.kt's
            // klasse-kdoc bij [publishAapsActiveSlot] en
            // CareSensAirDriver.kt's identieke toepassing: cachet deze toch
            // al verse lezing zodat CareSensAirDriver.kt's (niet-suspend)
            // computeReconnectCooldownMs() weet of CareSens Air zelf de
            // AAPS-slot is (en dus zijn proactieve verschuiving moet
            // overslaan) zonder daar zelf een Flow te moeten lezen.
            AapsSlotSchedule.publishAapsActiveSlot(currentAapsSlot)
            val isPriority = currentAapsSlot == slot
            // 12/08/2026 (editor, RONDE 100 — mirror van
            // CareSensAirDriver.kt's identieke toepassing, zie dat bestand's
            // kdoc bij deze zelfde plek en AapsSlotSchedule.kt's klasse-kdoc
            // voor de volledige aanleiding) — alleen relevant als DEZE slot
            // ooit niet de AAPS-slot is (vandaag is dat Dexcom altijd wél,
            // maar de check is bewust symmetrisch, net als Ronde 83's
            // ScanRateLimiter-voorrang, voor het geval de AAPS-bron ooit
            // naar CareSens Air wordt omgezet).
            if (!isPriority) {
                val guardDelay = AapsSlotSchedule.guardDelayMs(slot, System.currentTimeMillis())
                if (guardDelay > 0) {
                    DiagnosticFileLogger.log(
                        "DexcomG6: scheduleScanAttempt: AAPS-slot verwacht binnenkort een meting -> wijk ${guardDelay}ms uit"
                    )
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
            // 11/08/2026 (editor, RONDE 89) — zie ActiveWorkWakeLock.kt's
            // kdoc: CPU pas kort wakker houden vlak vóór het daadwerkelijke
            // scanwerk, niet meer de hele wachtperiode ervoor.
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
        val settingsObj = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 10/08/2026 (editor, RONDE 78) — hardware-offload ScanFilter op het
        // bekende, gebonden transmitter-MAC-adres, mirror van CareSens Air's
        // Ronde 30-fix (daar op service-UUID i.p.v. adres, want dat kende
        // het adres op het scanmoment nog niet — hier is het adres, anders
        // dan bij een eerste koppeling, altijd al bekend). Aanleiding: een
        // live diagnostic-log (10/08/2026) toonde dat zelfs met
        // ACCESS_BACKGROUND_LOCATION op "Altijd toestaan" de daadwerkelijke
        // chip-scan-dispatch ("MESSAGE_SCAN_START" in logcat) 117s werd
        // uitgesteld tot het scherm weer aanging, ondanks een ongewijzigd
        // `scanner.startScan(emptyList(), ...)` hierboven — d.w.z. een
        // ONGEFILTERDE scan, die de chip verplicht om ELKE naburige
        // advertentie naar de hoofdprocessor door te sturen om te filteren.
        // Een ScanFilter laat de BLE-chip zelf matchen (hardware-offload) en
        // hoeft de hoofdprocessor dus alleen bij een daadwerkelijke treffer
        // wakker te maken — precies het mechanisme dat CareSens Air's
        // screen-off-probleem destijds oploste. Bewust NIET `autoConnect=
        // true` (die weg is al eerder geprobeerd en teruggedraaid, zie
        // README Ronde 23/24 — exact hetzelfde symptoom: geen data tijdens
        // scherm-uit, direct inhalen bij scherm-aan).
        val scanFilters = listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
        // 09/08/2026 (editor, RONDE 60 — na live-test, derde koppelpoging,
        // logcat toonde DRIE bijna-gelijktijdige registerClient()/
        // clientConnect()-aanroepen naar hetzelfde adres binnen ~85ms) —
        // root cause: een BLE-transmitter zendt zijn advertisement-pakket
        // elke paar tientallen milliseconden opnieuw uit, en
        // `scanner.stopScan()` is NIET synchroon — er kunnen dus, tussen het
        // moment dat de eerste match binnenkomt en het moment dat stopScan()
        // daadwerkelijk effect heeft, nog één of meer VOLGENDE
        // onScanResult()-aanroepen voor DEZELFDE advertentie binnenkomen. Elke
        // aanroep riep tot nu toe zonder enige guard opnieuw connectToDevice()
        // aan → meerdere concurrente connectGatt()-pogingen naar dezelfde
        // fysieke transmitter, die (net als bij de ronde-59-race) elkaar in
        // de weg zitten en de verbinding vermoedelijk laten mislukken. Dit is
        // een ANDER lek dan de ronde-59-fix (die beschermde tegen twee
        // driver-INSTANTIES; dit is dezelfde instantie, dezelfde callback,
        // gewoon opnieuw aangeroepen vóórdat stopScan() had kunnen ingrijpen).
        // CareSensAirDriver.kt had deze exacte "resolved"-guard al sinds
        // ronde 29/30 (zie verderop in dat bestand) — hier stond 'm nooit,
        // vermoedelijk een omissie toen deze functie geschreven werd. Fix:
        // dezelfde guard hier ook, gezet VÓÓR stopScan() zodat een racende
        // tweede aanroep meteen niets doet.
        var resolved = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address == deviceAddress && !resolved) {
                    resolved = true
                    runCatching { scanner.stopScan(this) }
                    connectScanCallback = null
                    // 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's
                    // kdoc: alleen relevant als de schakelaar aan staat EN we
                    // al eerder succesvol verbonden waren (zie
                    // bondLossAutoRecoveryEnabled's zetting in connect()).
                    if (bondLossAutoRecoveryEnabled && BondLossRecovery.isBondMissing(result.device)) {
                        pendingAfterBond = { connectToDevice(scope, appCtx, result.device, settings) }
                        BondLossRecovery.attemptRecovery(result.device, "DexcomG6")
                        // Terugvalpad: als BOND_BONDED niet binnen 15s
                        // terugkomt (herstel mislukt/duurt te lang), alsnog
                        // gewoon verbinden — niet slechter dan het gedrag
                        // vóór deze functie bestond.
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
                DiagnosticFileLogger.log("DexcomG6: scan failed code=$errorCode")
                connectScanCallback = null
                backoffAndRetry(scope, appCtx, deviceAddress, settings)
            }
        }
        connectScanCallback = callback
        runCatching { scanner.startScan(scanFilters, settingsObj, callback) }
            .onFailure {
                connectScanCallback = null
                backoffAndRetry(scope, appCtx, deviceAddress, settings)
            }
        // 09/08/2026 (editor, RONDE 76, CRITICAL) — zie scheduleRearm()'s
        // kdoc hieronder: zonder dit kon deze scan voor onbepaalde tijd
        // stilzwijgend niets meer doen (geen onScanResult, geen
        // onScanFailed), en dus permanent "actief" blijven volgens
        // BleConnectionService's stillWorking-check.
        scheduleRearm(scope, appCtx, scanner, callback, deviceAddress, settings) { resolved }
    }

    /**
     * 09/08/2026 (editor, RONDE 76, CRITICAL — na live-melding: "de samsung
     * telefoon blijft hangen zodra het scherm zwart wordt en hij gaat ook
     * niet meer lopen", met een meegestuurde diagnostic-log
     * (fclglucolink_2026-08-09.txt) die een volkomen gezonde cyclus om
     * 18:53 toont — nette connect, meting, schone disconnect — gevolgd door
     * TOTALE, urenlange stilte: geen enkele volgende regel, ook geen
     * mislukte poging) — dit bestand miste tot deze ronde het
     * self-healing-vangnet dat `CareSensAirDriver.kt` al sinds ronde 26/30
     * wél heeft (`scheduleRearm()` daar, letterlijk dezelfde naam/opzet,
     * hier ONVERANDERD overgenomen — zie dat bestand voor de volledige
     * herkomst-analyse via Juggluco's `w2.run()` geval 8).
     *
     * Het gat: `startConnectScan()` hierboven start één kale
     * `scanner.startScan(...)` en wacht daarna simpelweg op `onScanResult`/
     * `onScanFailed` — geen van beide hoeft ooit te vuren als Android een
     * langlopende achtergrondscan stilzwijgend onderdrukt (een bekend
     * OS-gedrag bij langdurig scherm-uit, op sommige toestellen/OEM-schillen
     * agressiever dan andere). Zonder deze functie bleef de onderliggende
     * coroutine dan voor altijd `isActive`, en omdat
     * `BleConnectionService.onStartCommand()`'s `stillWorking`-check een
     * actieve job als "gezond, niet opnieuw starten" beschouwt, kon zelfs de
     * AlarmManager-wekker (`ConnectionWatchdog.kt`, elke 6 minuten) hier
     * nooit doorheen breken — een tijdelijke OS-scanonderdrukking werd zo
     * een PERMANENTE stilstand.
     *
     * Fix: exact hetzelfde patroon als CareSens Air. Als de transmitter na
     * `SCAN_REARM_INTERVAL_MS` nog steeds niet gevonden is, wordt de scan
     * expliciet gestopt en via `scheduleScanAttempt()` vers herstart (met
     * `ScanRateLimiter` ertussen) — dat forceert een nieuwe
     * `scanner.startScan()`-aanroep, wat Android's eventuele stille
     * onderdrukking van de VORIGE, langlopende scansessie doorbreekt. Plant
     * zichzelf niet opnieuw in (in tegenstelling tot CareSens Air's versie
     * hoeft dat hier niet: `scheduleScanAttempt()` roept via
     * `startConnectScan()` deze functie toch weer opnieuw aan bij elke
     * nieuwe scanpoging, dus de keten blijft vanzelf doorlopen). Stopt
     * zichzelf zodra `resolved` waar is (apparaat gevonden) of
     * `userStopped`/`disconnect()` de sessie afbrak.
     *
     * Bewust ALLEEN hier toegevoegd, niet in `BleConnectionService.kt`'s
     * gedeelde `stillWorking`-check — zie de kdoc-discussie met de
     * gebruiker: CareSens Air heeft dit vangnet zelf al, dus die blijft
     * ongewijzigd en dus stabiel.
     */
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
    // GATT-levenscyclus + protocol-handshake.
    // ============================================================

    private inner class GattCallback(
        private val scope: CoroutineScope,
        private val settings: AppSettings
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.requestMtu(185)) {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagnosticFileLogger.log("DexcomG6: STATE_DISCONNECTED status=$status device=${gatt.device.address}")
                    keepAliveJob?.cancel()
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
                            // 08/08/2026 (editor) — mirror van xDrip+'s
                            // prepareToWakeup(): ná een geslaagde meting NIET
                            // opnieuw meteen scannen, maar voorspellend
                            // wachten tot vlak vóór de volgende ~5-minuten-
                            // meting.
                            // 10/08/2026 (editor, RONDE 86) — zie
                            // computeReconnectCooldownMs()'s kdoc: nu
                            // zelf-corrigerend i.p.v. een kale, permanent
                            // meeschuivende "laatste meting + leadtime"-som.
                            computeReconnectCooldownMs()
                        } else {
                            errorBackoffMs.also { if (errorBackoffMs < maxErrorBackoffMs) errorBackoffMs += 100 }
                        }
                        scheduleScanAttempt(scope, ctx, address, settings, cooldownMs = cooldown)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Couldn't discover services (status $status).")
                runCatching { gatt.disconnect() }
                return
            }
            val service = gatt.getService(DexcomG6Protocol.CGM_SERVICE)
            if (service == null) {
                _connectionState.value = ConnectionState.Error("This device doesn't look like a Dexcom G6 transmitter.")
                runCatching { gatt.disconnect() }
                return
            }
            charControl = service.getCharacteristic(DexcomG6Protocol.CONTROL)
            charAuthentication = service.getCharacteristic(DexcomG6Protocol.AUTHENTICATION)
            charBackfill = service.getCharacteristic(DexcomG6Protocol.BACKFILL)

            val authChar = charAuthentication
            if (authChar == null) {
                _connectionState.value = ConnectionState.Error("Authentication characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }
            enableNotify(gatt, authChar) { startAuth(gatt, authChar) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // 09/08/2026 (editor, RONDE 58) — was: status volledig genegeerd,
            // altijd doorgegaan. Nu zichtbaar gelogd (vooral bij een
            // mislukte schrijfactie) — helpt een volgende live-testronde
            // meteen te laten zien of de CCCD-schrijfactie zelf al faalde,
            // i.p.v. pas te zien dat de transmitter de verbinding daarna
            // verbrak zonder duidelijke reden.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticFileLogger.log(
                    "DexcomG6: CCCD write FAILED for ${descriptor.characteristic.uuid} status=$status"
                )
            } else {
                DiagnosticFileLogger.log("DexcomG6: CCCD write ok for ${descriptor.characteristic.uuid}")
            }
            pendingAfterNotifyEnabled.remove(descriptor.characteristic.uuid)?.invoke()
        }

        // 09/08/2026 (editor, RONDE 58) — mirror van CareSensAirDriver.kt's
        // "Round 15 diagnostic: onCharacteristicWrite logging" (zelfde
        // aanpak bleek daar destijds waardevol om een stille write-failure
        // zichtbaar te maken). Vult het gat dat de live-test net blootlegde:
        // zonder dit was er GEEN enkele logregel tussen "CCCD ok" en een
        // eventuele STATE_DISCONNECTED — dus onmogelijk te zien of de
        // auth-aanvraag (of KeepAlive/Control-schrijfacties) daadwerkelijk
        // aankwam bij de transmitter.
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticFileLogger.log("DexcomG6: write FAILED for ${characteristic.uuid} status=$status")
            } else {
                DiagnosticFileLogger.log("DexcomG6: write ok for ${characteristic.uuid}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(gatt, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Pre-API33 pad — value zit dan alleen in characteristic.value.
            handleNotification(gatt, characteristic.uuid, characteristic.value ?: return)
        }

        private fun handleNotification(gatt: BluetoothGatt, uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                DexcomG6Protocol.AUTHENTICATION -> handleAuthNotification(gatt, value)
                DexcomG6Protocol.CONTROL -> handleControlNotification(value)
                DexcomG6Protocol.BACKFILL -> {
                    // FASE 1 — zie klasse-kdoc: alleen loggen, nog niet verwerken.
                    DiagnosticFileLogger.log("DexcomG6: backfill bytes=${value.joinToString(",")}")
                }
            }
        }

        private fun handleAuthNotification(gatt: BluetoothGatt, value: ByteArray) {
            val opcode = value.getOrNull(0)?.toInt()?.and(0xff)
            when (opcode) {
                0x03 -> {
                    val challenge = DexcomG6Protocol.parseAuthChallenge(value) ?: return
                    val key = DexcomG6Crypto.deriveKey(transmitterId)
                    val hash = DexcomG6Crypto.calculateChallengeHash(challenge.challenge, key)
                    val response = DexcomG6Protocol.buildAuthChallengeResponse(hash)
                    writeCharacteristic(gatt, charAuthentication!!, response)
                }
                0x05 -> {
                    val status = DexcomG6Protocol.parseAuthStatus(value) ?: return
                    authenticated = status.authenticated
                    bonded = status.bonded
                    DiagnosticFileLogger.log("DexcomG6: auth status authenticated=$authenticated bonded=$bonded")
                    if (!authenticated) {
                        _connectionState.value = ConnectionState.Error("Dexcom G6 authentication failed — check the transmitter ID.")
                        runCatching { gatt.disconnect() }
                        return
                    }
                    if (!bonded) {
                        // Vraag de transmitter een OS-bond te initiëren — mirror
                        // van xDrip+'s PREBOND/BOND-staten.
                        writeCharacteristic(gatt, charAuthentication!!, DexcomG6Protocol.buildBondRequest())
                        pendingAfterBond = { onAuthAndBondReady(gatt) }
                        runCatching { gatt.device.createBond() }
                    } else {
                        onAuthAndBondReady(gatt)
                    }
                }
            }
        }

        /** 08/08/2026 (editor, RONDE 56) — start de KeepAlive-taak en de
         *  Control-uitwisseling zodra auth (en bonding, indien nodig) klaar
         *  is — dit vervangt ronde 55's directe `startSession()`-aanroep. */
        private fun onAuthAndBondReady(gatt: BluetoothGatt) {
            startKeepAliveLoop(gatt)
            val controlChar = charControl
            if (controlChar == null) {
                _connectionState.value = ConnectionState.Error("Control characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }
            enableNotify(gatt, controlChar) {
                scope.launch { runControlSequence(gatt, controlChar) }
            }
        }

        /** 08/08/2026 (editor, RONDE 56) — de kern van de correctie: eerst
         *  (optioneel) een nieuwe sessie starten met een klaarstaande
         *  sensor-code, dan (optioneel, ~elke 8u) de batterij opvragen, dan
         *  ALTIJD een glucosewaarde opvragen — elke stap wacht via een
         *  CompletableDeferred op het bijbehorende antwoord (voltooid door
         *  handleControlNotification), met een timeout die simpelweg naar
         *  disconnect() doorstroomt (de bestaande backoff/reconnect-logica
         *  in onConnectionStateChange handelt de rest af, geen aparte
         *  foutafhandeling hier nodig). */
        private suspend fun runControlSequence(gatt: BluetoothGatt, controlChar: BluetoothGattCharacteristic) {
            // 09/08/2026 (editor, RONDE 70, op verzoek — "een stop sensor
            // knop die een stop signaal zend... zodat ik de sensor kan
            // stoppen, de transmitter verwijderen 5 minuten wachten en weer
            // opstarten") — LOSSTAANDE actie, bewust vóór de pendingCode-
            // combo-flow hieronder: alleen stoppen, GEEN nieuwe sessie
            // starten in dezelfde cyclus (de gebruiker verwijdert de
            // transmitter fysiek en wacht zelf 5 minuten, dan pas een nieuwe
            // "Start new sensor" vanuit het bestaande scherm). Zelfde
            // buildSessionStop()/SessionStopRx-machinerie als de bestaande
            // "stop-before-start"-combo hierboven, alleen niet gevolgd door
            // een SessionStart.
            if (settings.consumeDexcomG6PendingStopSensorOnly(slot)) {
                val stopDeferred = CompletableDeferred<DexcomG6Protocol.SessionStopRx?>()
                pendingSessionStopDeferred = stopDeferred
                writeCharacteristic(gatt, controlChar, DexcomG6Protocol.buildSessionStop(0))
                val stopResult = withTimeoutOrNull(SESSION_STOP_TIMEOUT_MS) { stopDeferred.await() }
                pendingSessionStopDeferred = null
                DiagnosticFileLogger.log("DexcomG6: standalone stop-sensor result=$stopResult")
                if (stopResult?.ok == true) {
                    // Sessie-status leegmaken zodat de UI niet langer een
                    // (nu niet meer kloppende) "sensor loopt"-status/warmup-
                    // aftelling toont voor de zojuist gestopte sensor.
                    settings.clearDexcomG6SessionStartConfirmedAtMs(slot)
                }
                runCatching { gatt.disconnect() }
                return
            }

            val pendingCode = settings.getDexcomG6PendingNewSensorCodeOnce(slot)
            if (pendingCode != null) {
                // 09/08/2026 (editor, RONDE 66, op verzoek — "moet er een
                // waarschuwing komen of je de oude wel wilt stoppen") —
                // mirror van xDrip+'s eigen handmatige procedure
                // ("Restarting a Dexcom G6 Sensor": eerst STOP SENSOR,
                // wachten tot de queue leeg is, dan pas START SENSOR) — hier
                // binnen ÉÉN verbindcyclus afgehandeld i.p.v. de gebruiker
                // 5 minuten te laten wachten. Het vlaggetje is al vooraf
                // gezet door DexcomG6NewSensorScreen.kt (ná de "er loopt al
                // een sessie"-waarschuwing) — hier alleen consumeren en
                // uitvoeren. Bewust "best effort": als de stop mislukt/
                // timeout, gaat de code hieronder gewoon door — komt de
                // transmitter dan terug met infoCode 0x02 ("already
                // started", zie SessionStartRx.alreadyStarted), dan blijft
                // de code simpelweg staan voor een volgende poging (zie de
                // "mislukking"-afhandeling verderop) i.p.v. dat de app ten
                // onrechte "Sensor started" toont voor een sessie die niet
                // daadwerkelijk vernieuwd is.
                if (settings.consumeDexcomG6PendingStopBeforeStart(slot)) {
                    val stopDeferred = CompletableDeferred<DexcomG6Protocol.SessionStopRx?>()
                    pendingSessionStopDeferred = stopDeferred
                    // dexTime=0: zelfde bewuste vereenvoudiging als de
                    // sessie-start hieronder (die ook al langer met
                    // dexTime=0 werkt) — een striktere, transmitter-relatieve
                    // dex-tijd wordt hier evenmin apart bijgehouden.
                    writeCharacteristic(gatt, controlChar, DexcomG6Protocol.buildSessionStop(0))
                    val stopResult = withTimeoutOrNull(SESSION_STOP_TIMEOUT_MS) { stopDeferred.await() }
                    pendingSessionStopDeferred = null
                    DiagnosticFileLogger.log("DexcomG6: stop-before-new-sensor result=$stopResult")
                    // 09/08/2026 (editor, RONDE 71, na live-test — "Sending
                    // sensor start" bleef 10+ minuten hangen) — kleine,
                    // bewuste pauze VOORDAT de SessionStart hieronder
                    // verstuurd wordt: de transmitter bevestigt de stop op
                    // BLE-niveau (dit 0x29-antwoord) mogelijk vóórdat de
                    // eigen interne statusmachine 'm volledig verwerkt heeft
                    // — een SessionStart die daar vlak bovenop komt kan dan
                    // nog steeds op "already started" stuiten. 1500 ms is een
                    // bewuste, willekeurige-maar-royale marge (geen officiële
                    // Dexcom-timing bekend), goedkoop genoeg om altijd te
                    // nemen zonder de gebruiker merkbaar te laten wachten.
                    delay(1500L)
                }

                val deferred = CompletableDeferred<DexcomG6Protocol.SessionStartRx?>()
                pendingSessionStartDeferred = deferred
                val nowSec = (System.currentTimeMillis() / 1000L).toInt()
                val message = runCatching { DexcomG6Protocol.buildSessionStart(0, nowSec, pendingCode) }
                    .onFailure { DiagnosticFileLogger.log("DexcomG6: invalid pending sensor code, dropping it: $it") }
                    .getOrNull()
                if (message == null) {
                    settings.clearDexcomG6PendingNewSensorCode(slot)
                } else {
                    writeCharacteristic(gatt, controlChar, message)
                    val result = withTimeoutOrNull(SESSION_START_TIMEOUT_MS) { deferred.await() }
                    pendingSessionStartDeferred = null
                    DiagnosticFileLogger.log("DexcomG6: new-sensor session start result=$result")
                    if (result?.ok == true) {
                        settings.clearDexcomG6PendingNewSensorCode(slot)
                        // 09/08/2026 (editor, RONDE 65, op verzoek — xDrip-
                        // stijl "sensor started" + resterende warmup-tijd) —
                        // dit is het bevestigde moment, zie
                        // Keys.DEXCOM_G6_SESSION_START_CONFIRMED_AT_MS's kdoc
                        // en dexcomG6StatusText() in DexcomG6StatusScreen.kt.
                        settings.setDexcomG6SessionStartConfirmedAtMs(slot, System.currentTimeMillis())
                        // 09/08/2026 (editor, RONDE 69) — zie
                        // Keys.DEXCOM_G6_LAST_CONFIRMED_SENSOR_CODE's kdoc:
                        // bewaart de code van de HUIDIGE sensor voor de
                        // sensor-infotabel, ook nadat de pending-code
                        // hierboven al gewist is.
                        settings.setDexcomG6LastConfirmedSensorCode(slot, pendingCode)
                        settings.resetDexcomG6SessionStartFailCount(slot)
                    } else {
                        // 09/08/2026 (editor, RONDE 66, CORRECTIE — zie
                        // DexcomG6Protocol.parseSessionStart()'s kdoc) — bij
                        // een mislukking (inclusief `alreadyStarted`,
                        // infoCode 0x02) laten we de code bewust STAAN (niet
                        // wissen): bij een getypfout kan de gebruiker 'm zo
                        // gewoon herzien via hetzelfde scherm.
                        //
                        // 09/08/2026 (editor, RONDE 71, CORRECTIE — na live-
                        // test: "Sending sensor start" bleef 10+ minuten
                        // onveranderd staan) — TOT DEZE RONDE bleef het hier
                        // bij: het "stop-before-start"-vlaggetje was al
                        // hierboven geconsumeerd (get-and-clear, éénmalig),
                        // dus een `alreadyStarted`-mislukking hier betekende
                        // dat ELKE volgende verbindpoging alleen nog een KALE
                        // SessionStart probeerde — zonder ooit weer eerst te
                        // stoppen. Als de transmitter dan structureel bleef
                        // melden dat de sessie al actief was (bijv. omdat de
                        // eerste stop simpelweg niet aankwam/verwerkt werd),
                        // faalde dat voor altijd, met een eeuwige "Sending
                        // sensor start…" in de UI tot gevolg — precies wat
                        // gerapporteerd werd. Fix: bij ELKE mislukking het
                        // stop-before-start-vlaggetje opnieuw zetten, zodat
                        // de VOLGENDE poging automatisch weer eerst stopt in
                        // plaats van blind te blijven herhalen. Samen met de
                        // nieuwe faalteller (DEXCOM_G6_SESSION_START_FAIL_COUNT)
                        // kan dexcomG6StatusText() (DexcomG6StatusScreen.kt)
                        // na een paar mislukkingen ook duidelijk maken dat er
                        // iets misgaat, i.p.v. stilzwijgend te blijven
                        // "Sending sensor start…" tonen.
                        settings.setDexcomG6PendingStopBeforeStart(slot, true)
                        settings.incrementDexcomG6SessionStartFailCount(slot)
                    }
                }
            }

            // 09/08/2026 (editor, RONDE 66) — zie Keys.DEXCOM_G6_WARMUP_SECONDS's
            // kdoc: eenmalig (met een terughoudende hertry-interval bij geen
            // antwoord) de ECHTE opwarmtijd + sensor-levensduur bij de
            // transmitter zelf opvragen, i.p.v. een vaste aanname — werkt zo
            // voor elke transmitter-variant (standaard G6, G6+, een
            // getweakte Anubis).
            //
            // 09/08/2026 (editor, RONDE 70, CORRECTIE — na live-test: warmup
            // bleef "0m" tonen, ook na het installeren van ronde 69's fix) —
            // `== null` alleen was niet genoeg: vóór ronde 69 kon een NIET-
            // exact-9-byte 0x53-antwoord al een (onbetrouwbare) warmupSeconds
            // van 0 hebben weggeschreven — dat 0-getal overleeft een app-
            // update gewoon (DataStore, zie eerdere kdoc's over dit gedrag)
            // en werd door ronde 69's eigen fix niet met terugwerkende kracht
            // ongeldig gemaakt. Een ECHTE warmup-duur is nooit 0 seconden
            // (zelfs Anubis' ~50 minuten is nog altijd honderden seconden),
            // dus `<= 0` is een veilig, betrouwbaar signaal voor "dit is een
            // stale/onbetrouwbare waarde uit een vorige, gebugde ronde,
            // opnieuw opvragen" — zonder enige losse migratie-/versievlag
            // nodig te hebben.
            if ((settings.getDexcomG6WarmupSecondsOnce(slot) ?: 0) <= 0) {
                val lastQuery = settings.getDexcomG6LastVersion2QueryAtMsOnce(slot)
                val queryStale = lastQuery == null ||
                    System.currentTimeMillis() - lastQuery > VERSION_REQUEST2_RETRY_INTERVAL_MS
                if (queryStale) {
                    settings.setDexcomG6LastVersion2QueryAtMs(slot, System.currentTimeMillis())
                    val deferred = CompletableDeferred<DexcomG6Protocol.VersionRequest2Rx?>()
                    pendingVersionRequest2Deferred = deferred
                    writeCharacteristic(gatt, controlChar, DexcomG6Protocol.buildVersionRequest2())
                    val version2 = withTimeoutOrNull(VERSION_REQUEST2_TIMEOUT_MS) { deferred.await() }
                    pendingVersionRequest2Deferred = null
                    if (version2 != null) {
                        DiagnosticFileLogger.log(
                            "DexcomG6: version2 warmupSeconds=${version2.warmupSeconds} typicalSensorDays=${version2.typicalSensorDays}"
                        )
                        settings.setDexcomG6WarmupCapability(slot, version2.warmupSeconds, version2.typicalSensorDays)
                    } else {
                        DiagnosticFileLogger.log("DexcomG6: version2 request timed out or unsupported")
                    }
                }
            }

            val lastBatteryQuery = settings.getDexcomG6LastBatteryQueryAtMsOnce(slot)
            val batteryStale = lastBatteryQuery == null ||
                System.currentTimeMillis() - lastBatteryQuery > BATTERY_QUERY_INTERVAL_MS
            if (batteryStale) {
                val deferred = CompletableDeferred<DexcomG6Protocol.BatteryInfoRx?>()
                pendingBatteryDeferred = deferred
                writeCharacteristic(gatt, controlChar, DexcomG6Protocol.buildBatteryInfoRequest())
                val battery = withTimeoutOrNull(BATTERY_TIMEOUT_MS) { deferred.await() }
                pendingBatteryDeferred = null
                if (battery != null) {
                    DiagnosticFileLogger.log("DexcomG6: battery voltageA=${battery.voltageA} voltageB=${battery.voltageB} temp=${battery.temperatureC}")
                    settings.setDexcomG6BatteryInfo(slot, 
                        battery.voltageA, battery.voltageB, battery.temperatureC, System.currentTimeMillis()
                    )
                } else {
                    DiagnosticFileLogger.log("DexcomG6: battery query timed out")
                }
            }

            val glucoseDeferred = CompletableDeferred<DexcomG6Protocol.GlucoseRx?>()
            pendingGlucoseDeferred = glucoseDeferred
            writeCharacteristic(gatt, controlChar, DexcomG6Protocol.buildGlucoseRequest())
            val glucose = withTimeoutOrNull(GLUCOSE_TIMEOUT_MS) { glucoseDeferred.await() }
            pendingGlucoseDeferred = null
            if (glucose != null) {
                // 09/08/2026 (editor, RONDE 74, op verzoek — zie
                // DexcomG6CalibrationState.kt's dexcomG6FallbackWarmupSeconds()-
                // kdoc) — deze drie waarden hier ophalen (runControlSequence is
                // al een suspend fun, handleGlucoseResult zelf niet) en
                // doorgeven, i.p.v. handleGlucoseResult zelf suspend te maken.
                val warmupSecondsOnce = settings.getDexcomG6WarmupSecondsOnce(slot)
                val typicalSensorDaysOnce = settings.getDexcomG6TypicalSensorDaysOnce(slot)
                val sessionStartConfirmedAtMsOnce = settings.getDexcomG6SessionStartConfirmedAtMsOnce(slot)
                handleGlucoseResult(
                    gatt, glucose,
                    warmupSecondsOnce, typicalSensorDaysOnce, sessionStartConfirmedAtMsOnce
                )
            } else {
                DiagnosticFileLogger.log("DexcomG6: glucose request timed out")
                runCatching { gatt.disconnect() }
            }
        }

        private fun handleControlNotification(value: ByteArray) {
            val opcode = value.getOrNull(0)?.toInt()?.and(0xff)
            when (opcode) {
                0x27 -> pendingSessionStartDeferred?.complete(DexcomG6Protocol.parseSessionStart(value))
                0x29 -> pendingSessionStopDeferred?.complete(DexcomG6Protocol.parseSessionStop(value))
                0x31, 0x4f -> pendingGlucoseDeferred?.complete(DexcomG6Protocol.parseAnyGlucose(value))
                0x22, 0x23 -> pendingBatteryDeferred?.complete(DexcomG6Protocol.parseBatteryInfo(value))
                // 09/08/2026 (editor, RONDE 68, CORRECTIE — zie
                // DexcomG6Protocol.parseVersionRequest2()'s kdoc) — het
                // antwoord op de 0x52-aanvraag komt NIET betrouwbaar altijd
                // op hetzelfde opcode terug: sommige transmitters echoën
                // 0x52 ("long form", 15 bytes), andere antwoorden met 0x53
                // ("short form", 9 bytes) — allebei geldige antwoorden op
                // dezelfde aanvraag, alleen 0x52 werd tot ronde 67 herkend,
                // waardoor een 0x53-antwoord stilzwijgend genegeerd werd.
                0x52, 0x53 -> {
                    // 09/08/2026 (editor, RONDE 71, diagnostisch — na live-
                    // test: `warmupSeconds` blijft voor deze specifieke
                    // Anubis-transmitter onbekend, ook na meerdere
                    // hertry-pogingen, terwijl `typicalSensorDays` uit
                    // hetzelfde antwoord WEL steeds gevuld raakt) — dat
                    // patroon kan alleen als het 0x53-"short form"-antwoord
                    // structureel NIET de exacte 9-byte lengte heeft die
                    // parseVersionRequest2() als voorwaarde stelt (zie die
                    // functie's kdoc, xDrip+'s eigen "type2"-definitie). Ruwe
                    // lengte+bytes loggen (nog vóór het parsen) geeft de
                    // eerstvolgende diagnostic-log het concrete bewijs om dit
                    // definitief vast te stellen i.p.v. te blijven gokken.
                    DiagnosticFileLogger.log(
                        "DexcomG6: version2 raw opcode=0x${opcode?.toString(16)} len=${value.size} bytes=${value.joinToString(",") { (it.toInt() and 0xff).toString() }}"
                    )
                    pendingVersionRequest2Deferred?.complete(DexcomG6Protocol.parseVersionRequest2(value))
                }
                else -> DiagnosticFileLogger.log("DexcomG6: unhandled Control opcode=$opcode bytes=${value.joinToString(",")}")
            }
        }

        /**
         * 09/08/2026 (editor, RONDE 69, CRITICAL FIX — na live-test: BG bleef
         * op 0,3 mmol/L hangen, erratisch verloop, geen echte hypo) — tot
         * deze ronde werd `rx.glucoseMgdl` ONVOORWAARDELIJK opgeslagen/
         * gebroadcast, ongeacht `rx.stateRaw` (het kalibratiebyte). Terug
         * nagekeken in xDrip+'s eigen `BaseGlucoseRxMessage.usable()` en
         * `Ob1G5StateMachine.processGlucoseRxMessage()`: xDrip+ maakt ALLEEN
         * een BgReading aan wanneer `DexcomG6CalibrationState.usableGlucose()`
         * (staat == Ok of NeedsCalibration) of `insufficientCalibration()`
         * waar is — in ELKE andere staat (WarmingUp, NeedsFirstCalibration,
         * een van de Failed/Stopped-varianten, ...) is het meegestuurde getal
         * GEEN echte meting, vaak letterlijk een intern statuscode-getal (zie
         * DexcomG6CalibrationState.usableGlucose()'s kdoc). Nu: dezelfde
         * gate — buiten een bruikbare staat wordt er simpelweg GEEN reading
         * aangemaakt (net als xDrip+, dat hier bewust een gat in de
         * grafiek laat i.p.v. een verzonnen/foutief getal te tonen).
         *
         * 09/08/2026 (editor, RONDE 74, op verzoek — "de waarden mogen pas
         * getoond worden resp. 30 en 60 minuten nadat de sensor cfm de info
         * in het overzicht is gestart", n.a.v. een live-screenshot met een
         * fysiek onwaarschijnlijke sprong van ~2 naar 16 mmol/L amper 8
         * minuten na een bevestigde sensorstart) — BOVENOP de kalibratie-
         * byte-gate hierboven (ronde 69) komt nu een TWEEDE, onafhankelijke
         * gate: zelfs als de transmitter een kalibratiestaat als "Ok"/
         * "NeedsCalibration" rapporteert, wordt de meting alsnog onderdrukt
         * zolang de eigen, door de gebruiker gekozen fallback-opwarmtijd
         * (zie DexcomG6CalibrationState.kt's dexcomG6FallbackWarmupSeconds())
         * nog niet verstreken is SINDS de bevestigde sessionStart — maar
         * ALLEEN wanneer de transmitter zelf GEEN bruikbare `warmupSeconds`
         * teruggeeft (zoals bij deze specifieke Anubis-hardware, zie ronde
         * 71's kdoc). Komt er ooit wél een echte `warmupSeconds` uit de
         * transmitter, dan heeft die altijd voorrang en wordt deze fallback-
         * gate overgeslagen (`fallbackWarmupSeconds` wordt dan `null`).
         */
        private fun handleGlucoseResult(
            gatt: BluetoothGatt,
            rx: DexcomG6Protocol.GlucoseRx,
            warmupSeconds: Int?,
            typicalSensorDays: Int?,
            sessionStartConfirmedAtMs: Long?
        ) {
            val nowMs = System.currentTimeMillis()
            lastSuccessfulConnectionAtMs = nowMs
            // 10/08/2026 (editor, RONDE 86) — zie het klasse-veld en
            // CareSensAirDriver.kt's zelfde-ronde-kdoc.
            if (cadenceAnchorAtMs == null) cadenceAnchorAtMs = nowMs
            if (sensorStartedAtMs == 0L) sensorStartedAtMs = nowMs
            scope.launch { settings.setDexcomG6LastConnectedAtMs(slot, nowMs) }
            // 09/08/2026 (editor, RONDE 66) — zie DexcomG6CalibrationState.kt's
            // kdoc: dit is het ENIGE betrouwbare, door de transmitter zelf
            // gerapporteerde signaal voor "warmt deze sensor nog op / is
            // 'ie mislukt/verlopen/gestopt" — bewaard bij elke geslaagde
            // meting, gebruikt door dexcomG6StatusText() (DexcomG6StatusScreen.kt).
            scope.launch { settings.setDexcomG6LastCalibrationState(slot, rx.stateRaw) }

            val calibrationState = DexcomG6CalibrationState.fromRaw(rx.stateRaw)
            val calibrationUsable = calibrationState.usableGlucose() || calibrationState.insufficientCalibration()

            // RONDE 74: fallback-opwarmgate — alleen actief zonder een echte
            // warmupSeconds uit de transmitter zelf.
            val fallbackWarmupSeconds = if (warmupSeconds == null || warmupSeconds <= 0) {
                dexcomG6FallbackWarmupSeconds(typicalSensorDays)
            } else {
                null
            }
            val fallbackElapsedMs = sessionStartConfirmedAtMs?.let { nowMs - it }
            val withinFallbackWarmup = fallbackWarmupSeconds != null &&
                fallbackElapsedMs != null &&
                fallbackElapsedMs < fallbackWarmupSeconds * 1000L

            val glucoseUsable = calibrationUsable && !withinFallbackWarmup
            _connectionState.value = ConnectionState.Connected(gatt.device.address, gatt.device.name)
            if (glucoseUsable) {
                val glucoseMgdl = rx.glucoseMgdl.toDouble()
                val reading = GlucoseReading(
                    glucoseMgdl = glucoseMgdl,
                    trendMgdlPerMin = DexcomG6Protocol.trendByteToMgdlPerMin(rx.trendRaw),
                    timestampMs = nowMs,
                    sensorStartedAtMs = sensorStartedAtMs,
                    sensorType = SensorType.DEXCOM_G6
                )
                DiagnosticFileLogger.log("DexcomG6: glucose=$glucoseMgdl seq=${rx.sequence} display_only=${rx.glucoseIsDisplayOnly} state=$calibrationState")
                scope.launch { _readings.emit(reading) }
            } else if (calibrationUsable && withinFallbackWarmup) {
                DiagnosticFileLogger.log(
                    "DexcomG6: glucose value ${rx.glucoseMgdl} SUPPRESSED — within fallback warmup window " +
                        "(transmitter's own warmupSeconds unavailable, using ${fallbackWarmupSeconds}s estimate; " +
                        "${(fallbackElapsedMs ?: 0L) / 1000L}s elapsed since confirmed start)"
                )
            } else {
                DiagnosticFileLogger.log(
                    "DexcomG6: glucose value ${rx.glucoseMgdl} IGNORED (not a real measurement) — state=$calibrationState"
                )
            }

            // 08/08/2026 (editor) — mirror van xDrip+'s "verbinding sluiten na
            // een geslaagde meting, voorspellend terugkomen" — zie klasse-kdoc
            // punt 1.
            runCatching { gatt.disconnect() }
        }

        private fun startAuth(gatt: BluetoothGatt, authChar: BluetoothGattCharacteristic) {
            val token = DexcomG6Crypto.randomToken()
            myAuthToken = token
            val request = DexcomG6Protocol.buildAuthRequest(token)
            writeCharacteristic(gatt, authChar, request)
        }

        /** 08/08/2026 (editor, RONDE 56) — mirror van xDrip+'s doKeepAlive():
         *  stuurt om de ~45s een KeepAliveTxMessage (opcode 0x06) via
         *  Authentication zolang deze GATT-verbinding actief is, zodat een
         *  wat langere Control-uitwisseling (sessie-start + batterij +
         *  glucose, elk met een eigen antwoord-timeout) niet halverwege door
         *  de transmitter zelf wordt afgebroken. Gestopt in
         *  onConnectionStateChange's STATE_DISCONNECTED-tak. */
        private fun startKeepAliveLoop(gatt: BluetoothGatt) {
            keepAliveJob?.cancel()
            keepAliveJob = scope.launch {
                while (true) {
                    delay(KEEP_ALIVE_INTERVAL_MS)
                    val authChar = charAuthentication ?: break
                    runCatching { writeCharacteristic(gatt, authChar, DexcomG6Protocol.buildKeepAlive(60)) }
                        .onFailure { DiagnosticFileLogger.log("DexcomG6: keep-alive write failed: $it") }
                }
            }
        }

        private val pendingAfterNotifyEnabled = mutableMapOf<java.util.UUID, () -> Unit>()

        /**
         * 09/08/2026 (editor, RONDE 58 — live-test op echte hardware:
         * verbinding kwam elke keer tot en met het inschakelen van
         * Authentication-notificaties, waarna de TRANSMITTER de verbinding
         * zelf verbrak (status 19), keer op keer, nooit verder) — root
         * cause gevonden door de door de gebruiker aangeleverde xDrip+-
         * broncode (`g5model/Ob1G5StateMachine.java`) opnieuw te raadplegen:
         * `doCheckAuth()` gebruikt voor G6 `connection.setupIndication
         * (Authentication)` (regel 166-167), NIET `setupNotification` zoals
         * bij G5 (regel 182) — en `doGetData()` gebruikt voor Control
         * ONVOORWAARDELIJK `connection.setupIndication(Control)` (regel
         * 696). BLE-indicaties gebruiken een ANDERE CCCD-waarde dan
         * notificaties (0x0002 i.p.v. 0x0001) — deze functie schreef tot nu
         * toe altijd `ENABLE_NOTIFICATION_VALUE`, ongeacht welke
         * characteristic. Voor een G5/G6-transmitter, die op Authentication/
         * Control specifiek indicaties verwacht, is dat een ongeldige CCCD-
         * schrijfactie — precies verklarend waarom de transmitter de
         * verbinding stelselmatig verbrak vlak na deze stap, nog vóór de
         * auth-aanvraag ooit verstuurd kon worden. Android's eigen BLE-stack
         * handelt de indicatie-bevestiging (ATT_HANDLE_VALUE_CFM) intern af
         * — verder in de code (onCharacteristicChanged) verandert er niets,
         * notificaties én indicaties komen via hetzelfde callback-pad
         * binnen.
         *
         * @param useIndication true voor Authentication/Control (mirror van
         *   xDrip+'s `setupIndication`); zou false moeten zijn voor een
         *   toekomstige notificatie-only characteristic (bv. Backfill, zie
         *   xDrip+'s `setupNotification(ProbablyBackfill)`, regel 1974) —
         *   nog niet actief geabonneerd in deze driver (FASE 1, alleen
         *   loggen, zie klasse-kdoc).
         */
        private fun enableNotify(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            useIndication: Boolean = true,
            onDone: () -> Unit
        ) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                DiagnosticFileLogger.log("DexcomG6: setCharacteristicNotification failed for ${characteristic.uuid}")
            }
            val descriptor = characteristic.getDescriptor(DexcomG6Protocol.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                // Geen CCCD gevonden — ga toch door, sommige stacks vereisen
                // 'm niet strikt na setCharacteristicNotification().
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

        private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    // 08/08/2026 (editor) — wat te doen zodra de bond rond is (zie
    // registerBondReceiver()) — mirror van CareSensAirDriver.kt's
    // pendingAfterBond-veld.
    private var pendingAfterBond: (() -> Unit)? = null

    private fun registerBondReceiver(context: Context, deviceAddress: String) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != deviceAddress) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    DiagnosticFileLogger.log("DexcomG6: bonded, resuming after-bond action")
                    pendingAfterBond?.invoke()
                    pendingAfterBond = null
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        runCatching { appContext?.unregisterReceiver(receiver) }
        bondReceiver = null
    }

    /**
     * 11/08/2026 (editor, RONDE 90 — na live-melding: "Bij de dexcom staat
     * er error terwijl er geen error is, dit wil ik ook vervangen door de
     * laatste Bg waarde") — vóór deze ronde zette deze functie bij ELKE
     * STATE_DISCONNECTED (dus ook de heel normale, verwachte disconnect
     * na elke geslaagde ~5-minuten-meting) meteen `ConnectionState.Error`,
     * ongeacht hoe kort geleden de laatste meting was. `BleConnectionService
     * .refreshNotification()` toont de laatste BG-waarde alleen zolang de
     * status `Connected` is (zie die kdoc) — dus deze driver liet de
     * systeemmelding structureel "Error: No connection for 0m."/"...4m."
     * zien terwijl er niets mis was, puur omdat de transmitter tussen
     * metingen door telkens netjes de verbinding sluit. CareSens Air had
     * dit probleem niet: die kreeg destijds (ronde 33) al dezelfde
     * "routinematige herverbinding = blijf Connected staan"-logica hieronder
     * — nu overgenomen voor Dexcom G6.
     *
     * Nieuw gedrag: alleen een échte foutmelding (met minuten sinds de
     * laatste geslaagde meting) zodra dat langer dan
     * RECONNECT_STATUS_WARNING_MINUTES geleden is. Zolang dat niet zo is,
     * blijft een reeds-Connected status gewoon Connected staan (routine-
     * herverbinding op de achtergrond — de eerstvolgende geslaagde meting
     * zet 'm sowieso weer expliciet op Connected, zie regel ~1183). Alleen
     * als er nog nooit een geslaagde verbinding is geweest, blijft
     * "Connecting…" getoond worden zoals voorheen.
     */
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
