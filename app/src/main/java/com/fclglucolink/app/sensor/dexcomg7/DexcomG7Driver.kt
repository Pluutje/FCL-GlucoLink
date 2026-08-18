package com.fclglucolink.app.sensor.dexcomg7

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

    // 17/08/2026 (editor, RONDE 112) — ExtraData-notificaties komen in
    // stukjes van hoogstens 20 bytes (BLE-MTU) binnen; deze accumulator plakt
    // ze aan elkaar tot een volledig J-PAKE-rondepakket (160 bytes) — mirror
    // van xDrip+'s Plugin.java se `accumulator`/`fill()`.
    private var extraDataAccumulator = ByteArray(0)
    private var pendingExtraDataPacketDeferred: CompletableDeferred<DexcomG7Packet?>? = null
    private var pendingAuthIndicationDeferred: CompletableDeferred<ByteArray?>? = null
    private var pendingGlucoseDeferred: CompletableDeferred<DexcomG7Protocol.GlucoseRx?>? = null

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
        private const val RECONNECT_STATUS_WARNING_MINUTES = 7L

        // 17/08/2026 (editor, RONDE 112) — elke stap van de J-PAKE-handshake
        // (rondepakket versturen+antwoord afwachten, auth-aanvraag+antwoord,
        // uitdaging+status) krijgt dezelfde royale timeout; een falende stap
        // stroomt gewoon door naar de bestaande disconnect/backoff-logica in
        // onConnectionStateChange, geen aparte foutafhandeling nodig.
        private const val PAIRING_STEP_TIMEOUT_MS = 15_000L
        private const val GLUCOSE_TIMEOUT_MS = 20_000L

        // xDrip+'s eigen chunking voor de ExtraData-characteristic: stukjes
        // van hoogstens 20 bytes (BLE-MTU zonder onderhandeling), 40ms pauze
        // ertussen om de transmitter niet te overspoelen — zie Ob1G5StateMachine
        // .doNext()'s kdoc-citaat in DexcomG7Protocol.kt.
        private const val CHUNK_SIZE = 20
        private const val CHUNK_DELAY_MS = 40L
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
        pendingExtraDataPacketDeferred = null
        pendingAuthIndicationDeferred = null
        pendingGlucoseDeferred = null
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

    private fun computeReconnectCooldownMs(): Long {
        val lastReadingAtMs = lastSuccessfulConnectionAtMs ?: return SENSOR_PERIOD_MS - SCAN_START_MARGIN_MS
        val anchor = cadenceAnchorAtMs ?: lastReadingAtMs
        val periodsElapsed = Math.round((lastReadingAtMs - anchor) / SENSOR_PERIOD_MS.toDouble())
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
                backoffAndRetry(scope, appCtx, deviceAddress, settings)
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
                    if (!gatt.requestMtu(185)) {
                        gatt.discoverServices()
                    }
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

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
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
            // 17/08/2026 (editor, RONDE 112) — volgorde exact zoals xDrip+'s
            // doCheckAuth2(): eerst ExtraData-NOTIFICATIES aanzetten, pas
            // daarna Authentication-INDICATIES (zie DexcomG7Protocol.kt's
            // kdoc-citaat) — daarna pas de handshake starten.
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
            if (opcode == 0x4e) {
                pendingGlucoseDeferred?.complete(DexcomG7Protocol.parseGlucose(value))
            } else {
                DiagnosticFileLogger.log("DexcomG7: unhandled Control opcode=$opcode bytes=${value.joinToString(",")}")
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

            // ---- Ronde 1 ---- (volgorde: ExtraData EERST, dan Authentication
            // — exact zoals xDrip+'s Ob1G5StateMachine.doNext() voor ELKE
            // {cmd, data}-tuple doet, zie DexcomG7Protocol.kt's kdoc-citaat;
            // bewust niet omgedraaid, ook al lijkt het commando logisch
            // "eerst" te horen.)
            writeChunked(gatt, extraChar, DexcomG7Jpake.getRound1Packet(ctx).output())
            writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildRoundCommand(0))
            val round1 = awaitExtraDataPacket() ?: return failHandshake(gatt, "geen antwoord op ronde 1")
            ctx.receivedRound1 = round1
            if (!DexcomG7Jpake.validateRound1Packet(ctx)) return failHandshake(gatt, "ronde 1: ongeldig bewijs (verkeerde koppelcode?)")

            // ---- Ronde 2 ----
            writeChunked(gatt, extraChar, DexcomG7Jpake.getRound2Packet(ctx).output())
            writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildRoundCommand(1))
            val round2 = awaitExtraDataPacket() ?: return failHandshake(gatt, "geen antwoord op ronde 2")
            ctx.receivedRound2 = round2
            if (!DexcomG7Jpake.validateRound2Packet(ctx)) return failHandshake(gatt, "ronde 2: ongeldig bewijs")

            // ---- Ronde 3 + auth-aanvraag (gecombineerd, zie xDrip+'s
            // Plugin.aNext() Round3-tak: ExtraData eerst, dan Authentication) ----
            val token = DexcomG7Protocol.randomToken()
            writeChunked(gatt, extraChar, DexcomG7Jpake.getRound3Packet(ctx).output())
            writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildAuthRequest(token))
            val round3 = awaitExtraDataPacket() ?: return failHandshake(gatt, "geen antwoord op ronde 3")
            ctx.receivedRound3 = round3
            if (!DexcomG7Jpake.validateRound3Packet(ctx)) return failHandshake(gatt, "ronde 3: ongeldig bewijs (verkeerde koppelcode?)")

            val authResponseBytes = awaitAuthIndication() ?: return failHandshake(gatt, "geen antwoord op auth-aanvraag")
            val authResponse = DexcomG7Protocol.parseAuthRequestResponse(authResponseBytes)
                ?: return failHandshake(gatt, "auth-aanvraag-antwoord te kort")

            // Verifieer dat de sensor ONZE eigen challenge (het token dat we
            // net verstuurden) correct met de gedeelde sleutel kon hashen —
            // zie DexcomG7Crypto.DexcomG7Jpake.calculateHash's kdoc.
            ctx.challenge = token
            val expectedHash = DexcomG7Jpake.calculateHash(ctx)
            if (expectedHash == null || !expectedHash.contentEquals(authResponse.theirProofHash)) {
                return failHandshake(gatt, "uitdaging-bewijs klopt niet (verkeerde koppelcode?)")
            }

            // Beantwoord op onze beurt de NIEUWE uitdaging die de sensor
            // meestuurde.
            ctx.challenge = authResponse.theirChallenge
            val ourReplyHash = DexcomG7Jpake.calculateHash(ctx)
                ?: return failHandshake(gatt, "kon geen antwoord-hash berekenen")
            writeCharacteristic(gatt, authChar, DexcomG7Protocol.buildAuthChallenge(ourReplyHash))

            val statusBytes = awaitAuthIndication() ?: return failHandshake(gatt, "geen statusantwoord ontvangen")
            val status = DexcomG7Protocol.parseAuthStatus(statusBytes)
                ?: return failHandshake(gatt, "statusantwoord onherkenbaar")

            if (!status.isAuthenticated) {
                _connectionState.value = ConnectionState.Error("Dexcom G7 authentication failed — check the pairing code.")
                runCatching { gatt.disconnect() }
                return
            }

            if (!status.isBonded) {
                writeCharacteristic(gatt, authChar, DexcomG7Protocol.TIME_EXTENDED)
                pendingAfterBond = { onAuthAndBondReady(gatt) }
                runCatching { gatt.device.createBond() }
            } else {
                onAuthAndBondReady(gatt)
            }
        }

        private fun failHandshake(gatt: BluetoothGatt, reason: String) {
            DiagnosticFileLogger.log("DexcomG7: pairing handshake mislukt: $reason")
            runCatching { gatt.disconnect() }
        }

        private fun onAuthAndBondReady(gatt: BluetoothGatt) {
            val controlChar = charControl
            if (controlChar == null) {
                _connectionState.value = ConnectionState.Error("Control characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }
            enableNotify(gatt, controlChar, useIndication = false) {
                scope.launch { requestGlucose(gatt, controlChar) }
            }
        }

        private suspend fun requestGlucose(gatt: BluetoothGatt, controlChar: BluetoothGattCharacteristic) {
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

        private suspend fun awaitExtraDataPacket(): DexcomG7Packet? {
            val deferred = CompletableDeferred<DexcomG7Packet?>()
            pendingExtraDataPacketDeferred = deferred
            val result = withTimeoutOrNull(PAIRING_STEP_TIMEOUT_MS) { deferred.await() }
            pendingExtraDataPacketDeferred = null
            return result
        }

        private suspend fun awaitAuthIndication(): ByteArray? {
            val deferred = CompletableDeferred<ByteArray?>()
            pendingAuthIndicationDeferred = deferred
            val result = withTimeoutOrNull(PAIRING_STEP_TIMEOUT_MS) { deferred.await() }
            pendingAuthIndicationDeferred = null
            return result
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
                writeCharacteristic(gatt, characteristic, data.copyOfRange(offset, end))
                offset = end
                if (offset < data.size) delay(CHUNK_DELAY_MS)
            }
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

    private fun registerBondReceiver(context: Context, deviceAddress: String) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != deviceAddress) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    DiagnosticFileLogger.log("DexcomG7: bonded, resuming after-bond action")
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
