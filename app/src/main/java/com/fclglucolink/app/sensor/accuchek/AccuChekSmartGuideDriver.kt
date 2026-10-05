package com.fclglucolink.app.sensor.accuchek

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
import android.os.ParcelUuid
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.logging.DiagnosticFileLogger
import com.fclglucolink.app.sensor.ConnectionState
import com.fclglucolink.app.sensor.GlucoseReading
import com.fclglucolink.app.sensor.SensorDriver
import com.fclglucolink.app.sensor.SensorSlot
import com.fclglucolink.app.sensor.SensorType
import com.fclglucolink.app.sensor.TrendCalculator
import com.fclglucolink.app.sensor.ble.GattExclusivityGate
import com.fclglucolink.app.sensor.ble.PredictiveReconnectAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ============================================================================
 * FCLGlucoLink — Accu-Chek SmartGuide-driver (RONDE 198, CGM Service-herbouw)
 * ============================================================================
 *
 * 01/10/2026 (editor, RONDE 198) — VERVANGT de RONDE 197-implementatie
 * (standaard GLS-meterprofiel, 0x1808) door een implementatie op de echte
 * Bluetooth SIG "Continuous Glucose Monitoring Service" (CGM Service,
 * 0x181F) — zie AccuChekSmartGuideProtocol.kt's klasse-kdoc voor het
 * volledige "waarom" en README.md's Ronde 198-sectie.
 *
 * VOLGORDE PER VERBINDING (logica overgenomen uit xDrip+'s
 * `GluProBle.java`'s `InternalManager.initialize()`/`readStaticParameters()`,
 * herschreven in idiomatisch Kotlin — zie AccuChekSmartGuideProtocol.kt's
 * klasse-kdoc): connect -> discoverServices -> (indien nog niet gebonden:
 * wacht op Android's eigen bonding-/PIN-dialoog, zie
 * [handleInsufficientAuth]) -> Device Information lezen (firmware/
 * manufacturer/model/serial/hardware) -> CGM Status lezen (bepaalt
 * [sensorStartTimeMs]) -> CGM Session Run Time lezen -> notificaties aan
 * voor CGM Measurement -> indicaties aan voor RACP -> indicaties aan voor
 * CGM Specific Ops Control Point -> "Get Communication Interval" sturen;
 * als het antwoord niet al 5 is, EENMALIG "Set Communication Interval" naar
 * 5 sturen (zie klasse-kdoc punt 5 van de opdracht die tot dit bestand
 * leidde — DE kern-betrouwbaarheidseis deze ronde) -> RACP "Report Stored
 * Records >= volgende-sequence-nummer" versturen voor backfill van gemiste
 * metingen -> elke binnenkomende CGM Measurement-notificatie parsen/
 * doorgeven.
 *
 * BETROUWBAARHEID (zie klasse-kdoc van de opdracht, punt 5+6): naast de
 * expliciete 5-minuten-intervalschrijfactie hierboven wordt elke
 * herverbindingscooldown, net als bij G6/G7/CareSens Air, via
 * [PredictiveReconnectAlarm] (een Doze-doorbrekende `AlarmManager`-wekker)
 * overbrugd i.p.v. een kale coroutine-`delay()` — zie [awaitCooldown]. Dit
 * is een VEREENVOUDIGDE mirror van DexcomG6Driver.kt's eigen
 * `computeReconnectCooldownMs()`/`awaitCooldown()` (die ook vroege-aankomst-
 * tolerantie en kruis-slot-fasescheiding via `AapsSlotSchedule` kent, zie
 * RONDE 187/188) — die verfijningen zijn HIER NIET geport, zie de finale
 * rapportage van deze ronde voor de volledige eerlijkheid daarover.
 * `ConnectionWatchdog.kt` (het generieke "leeft het proces nog?"-
 * veiligheidsnet elke 6 minuten) hoeft NERGENS driver-specifiek aangeroepen
 * te worden — die kijkt alleen naar `AppSettings.hasAnySlotConfigured()` en
 * herstart dan onvoorwaardelijk `BleConnectionService`, dus dekt deze driver
 * al automatisch mee, zonder enige wijziging aan dit bestand.
 *
 * `implemented = true` vóór de eerste live-test tegen een echte SmartGuide-
 * sensor, exact dezelfde aanpak als destijds bij CareSens Air/G6/G7/de
 * RONDE 197-poging (zie SensorType.ACCUCHEK_SMARTGUIDE's eigen kdoc).
 */
class AccuChekSmartGuideDriver(private val slot: SensorSlot) : SensorDriver {

    override val sensorType: SensorType = SensorType.ACCUCHEK_SMARTGUIDE

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<GlucoseReading>(replay = 0, extraBufferCapacity = 8)
    override val readings: SharedFlow<GlucoseReading> = _readings.asSharedFlow()

    private var driverScope: CoroutineScope? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var connectScanCallback: ScanCallback? = null
    private var userStopped = false
    private var appContext: Context? = null
    private var reconnectJob: Job? = null

    private var sensorStartedAtMs: Long = 0L
    private var lastMeasurementAtMs: Long? = null
    private var errorBackoffMs = ERROR_BACKOFF_INITIAL_MS

    // 01/10/2026 (editor, RONDE 198) — bevestigd communicatie-interval van
    // DEZE sessie (null tot de eerste Get/Set-respons binnen is) — zie
    // [ensureFiveMinuteIntervalOnce]'s kdoc: wordt maar één keer per
    // verbind-sessie geschreven, niet bij elke herverbinding opnieuw (zie
    // klasse-kdoc van de opdracht die tot dit bestand leidde, punt 5).
    private var confirmedIntervalMinutes: Int? = null
    private var intervalWriteAttempted = false

    // 01/10/2026 (editor, RONDE 200, bugfix na live-test) — `true` tussen het
    // versturen van een "Set Communication Interval"-write en diens
    // write-ack (`onCharacteristicWrite`) — zuiver om die ene write te
    // kunnen onderscheiden van alle andere 0x2AAC-writes (de "Get" zelf, of
    // een retry na insufficient-authentication), zodat een mislukte write
    // alleen dán als "verwacht/geen echte fout" gelogd wordt — zie
    // [handleCgmOpsResponse]'s kdoc voor het volledige "waarom".
    private var intervalSetWriteInFlight = false

    private var nextSequenceNumber: Int = 0
    private var highestSequenceSeenThisSession: Int? = null
    private var previousReading: GlucoseReading? = null

    private var charCgmMeasurement: BluetoothGattCharacteristic? = null
    private var charRacp: BluetoothGattCharacteristic? = null
    private var charCgmSpecificOps: BluetoothGattCharacteristic? = null
    private var charCgmStatus: BluetoothGattCharacteristic? = null
    private var charCgmSessionRunTime: BluetoothGattCharacteristic? = null
    private var charFirmwareRevision: BluetoothGattCharacteristic? = null
    private var charManufacturerName: BluetoothGattCharacteristic? = null
    private var charModelNumber: BluetoothGattCharacteristic? = null
    private var charSerialNumber: BluetoothGattCharacteristic? = null
    private var charHardwareRevision: BluetoothGattCharacteristic? = null

    private var bondReceiver: BroadcastReceiver? = null
    private var pendingAfterBond: (() -> Unit)? = null

    companion object {
        private const val ERROR_BACKOFF_INITIAL_MS = 2_000L
        private const val ERROR_BACKOFF_MAX_MS = 60_000L

        // 01/10/2026 (editor, RONDE 198) — betrouwbaarheidseis deze ronde,
        // zie klasse-kdoc: de sensor moet elke 5 minuten een meting sturen,
        // deze driver dwingt dat expliciet af via de CGM Specific Ops
        // Control Point i.p.v. te vertrouwen op de sensor se eigen default.
        private const val TARGET_COMMUNICATION_INTERVAL_MINUTES = 5

        // 01/10/2026 (editor, RONDE 200, bugfix na live-test) — per de
        // Bluetooth CGM-spec betekent de waarde 0xFF (255) op "Get
        // Communication Interval" niet letterlijk "255 minuten" maar
        // "snelst door het apparaat ondersteunde interval" (zie
        // [AccuChekSmartGuideProtocol.buildSetCommunicationIntervalCommand]'s
        // eigen kdoc, die dit al voor de Set-kant beschreef) — zie
        // [handleCgmOpsResponse]'s kdoc voor waarom dit de "write FAILED
        // status=128 bij elke verbinding"-ruis verklaart en oplost.
        private const val COMMUNICATION_INTERVAL_FASTEST_SENTINEL = 0xFF

        // 01/10/2026 (editor, RONDE 200) — GEEN spec-waarde: Accu-Cheks eigen
        // documentatie is deze ronde niet (succesvol) geraadpleegd (de
        // `tools.accu-chek.com`-handleiding-URL uit de opdracht gaf geen
        // bruikbare inhoud terug) — dit is zuiver de gebruiker's eigen,
        // rechtstreeks geobserveerde tijdsduur: sensor-start-moment (uit CGM
        // Status) tot eerste echte meting was in de live-test EXACT 60
        // minuten (zie README.md's Ronde 199/200-secties). Te herzien zodra
        // een officiëlere bron of een afwijkende live-test iets anders
        // laat zien.
        const val WARMUP_DURATION_MINUTES = 60

        // 01/10/2026 (editor, RONDE 199, bugfix na live-test) — was
        // "ACCU-CHEK": de echte sensor adverteert zijn Bluetooth-naam als
        // "AC-<serienummer>" (bv. "AC-1R001475881"), bevat dus NERGENS de
        // tekst "ACCU-CHEK" — de filter matchte daardoor nooit, en de
        // gebruiker moest altijd "toon alle apparaten" aanzetten. "AC-" is
        // hier bewust gekozen i.p.v. het volledige serienummer-patroon
        // (zie [buildPairingListFilter]'s eigen kdoc in SensorDriver.kt: dit
        // is een vuistregel, geen harde eis — "toon alle apparaten" blijft
        // de garantie als een toekomstige firmware-versie weer een andere
        // naamnotatie gebruikt).
        private const val NAME_HINT = "AC-"
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override suspend fun buildPairingListFilter(context: Context): ((String?, String) -> Boolean)? =
        { deviceName, _ -> deviceName != null && deviceName.contains(NAME_HINT, ignoreCase = true) }

    override fun startPairing(context: Context, onDeviceFound: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth isn't available or is turned off.")
            return
        }
        leScanner = scanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 01/10/2026 (editor, RONDE 198) — ScanFilter op de ECHTE CGM
        // Service (0x181F) i.p.v. de RONDE 197-GLS-service (0x1808).
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(AccuChekSmartGuideProtocol.SERVICE_CGM)).build()
        )
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
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { _connectionState.value = ConnectionState.Error("Couldn't start scanning: ${it.message}") }
    }

    override fun stopPairing() {
        val callback = scanCallback ?: return
        runCatching { leScanner?.stopScan(callback) }
        scanCallback = null
    }

    override fun connect(context: Context, deviceAddress: String) {
        userStopped = false
        errorBackoffMs = ERROR_BACKOFF_INITIAL_MS
        previousReading = null
        confirmedIntervalMinutes = null
        intervalWriteAttempted = false
        intervalSetWriteInFlight = false
        val settings = AppSettings(context)
        val scope = CoroutineScope(SupervisorJob())
        driverScope = scope
        val appCtx = context.applicationContext
        appContext = appCtx

        scope.launch {
            sensorStartedAtMs = settings.getOrInitSensorStartedAtMs(slot)
            nextSequenceNumber = settings.getAccuChekSmartGuideNextSequenceOnce(slot) ?: 0
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
        scheduleScanAttempt(scope, appCtx, deviceAddress, cooldownMs = 0L)
    }

    override fun disconnect() {
        userStopped = true
        reconnectJob?.cancel()
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        unregisterBondReceiver()
        appContext?.let { PredictiveReconnectAlarm.cancel(it, slot) }
        driverScope?.cancel()
        driverScope = null
        GattExclusivityGate.release(slot)
        _connectionState.value = ConnectionState.Disconnected
    }

    // ============================================================
    // Scan-dan-verbind + Doze-doorbrekende herverbind-cooldown.
    // ============================================================

    private fun scheduleScanAttempt(scope: CoroutineScope, appCtx: Context, deviceAddress: String, cooldownMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (cooldownMs > 0) awaitCooldown(appCtx, cooldownMs)
            if (userStopped) return@launch
            val scanner = bluetoothAdapter(appCtx)?.bluetoothLeScanner
            if (scanner == null) {
                _connectionState.value = ConnectionState.Error("Bluetooth isn't available on this device.")
                return@launch
            }
            startConnectScan(scope, appCtx, scanner, deviceAddress)
        }
    }

    /**
     * 01/10/2026 (editor, RONDE 198) — zie klasse-kdoc en
     * PredictiveReconnectAlarm.kt's eigen klasse-kdoc: plant een Doze-
     * doorbrekende `AlarmManager`-wekker EN wacht binnen dezelfde coroutine
     * op het bijbehorende signaal, met een defensieve bovengrens
     * (cooldownMs + 30s) zodat een nooit-afgaande wekker (bv. gebruiker
     * heeft "Exacte alarms" losgekoppeld) nooit erger uitpakt dan een kale
     * `delay()` zou doen.
     */
    private suspend fun awaitCooldown(appCtx: Context, cooldownMs: Long) {
        val deferred = PredictiveReconnectAlarm.schedule(appCtx, slot, cooldownMs)
        try {
            withTimeoutOrNull(cooldownMs + 30_000L) { deferred.await() }
        } finally {
            PredictiveReconnectAlarm.cancel(appCtx, slot)
        }
    }

    /**
     * 01/10/2026 (editor, RONDE 198) — voorspelt de volgende meting op basis
     * van [lastMeasurementAtMs] + het bevestigde communicatie-interval
     * (standaard 5 minuten, zie [TARGET_COMMUNICATION_INTERVAL_MINUTES] —
     * zodra [confirmedIntervalMinutes] bekend is wordt DIE waarde gebruikt).
     * VEREENVOUDIGD t.o.v. DexcomG6Driver.kt's `computeReconnectCooldownMs()`
     * — zie klasse-kdoc voor wat hier bewust niet geport is.
     */
    private fun computeReconnectCooldownMs(): Long {
        val intervalMs = (confirmedIntervalMinutes ?: TARGET_COMMUNICATION_INTERVAL_MINUTES) * 60_000L
        val lastAt = lastMeasurementAtMs ?: return 0L
        val remaining = lastAt + intervalMs - System.currentTimeMillis()
        return remaining.coerceIn(0L, intervalMs)
    }

    private fun startConnectScan(scope: CoroutineScope, appCtx: Context, scanner: BluetoothLeScanner, deviceAddress: String) {
        val settingsObj = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanFilters = listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
        var resolved = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address == deviceAddress && !resolved) {
                    resolved = true
                    runCatching { scanner.stopScan(this) }
                    connectScanCallback = null
                    connectToDevice(scope, appCtx, result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: scan failed code=$errorCode")
                connectScanCallback = null
                backoffAndRetry(scope, appCtx, deviceAddress)
            }
        }
        connectScanCallback = callback
        runCatching { scanner.startScan(scanFilters, settingsObj, callback) }
            .onFailure {
                connectScanCallback = null
                backoffAndRetry(scope, appCtx, deviceAddress)
            }
    }

    private fun backoffAndRetry(scope: CoroutineScope, appCtx: Context, deviceAddress: String) {
        val delayMs = errorBackoffMs
        if (errorBackoffMs < ERROR_BACKOFF_MAX_MS) errorBackoffMs = (errorBackoffMs * 2).coerceAtMost(ERROR_BACKOFF_MAX_MS)
        scheduleScanAttempt(scope, appCtx, deviceAddress, cooldownMs = delayMs)
    }

    private fun connectToDevice(scope: CoroutineScope, appCtx: Context, device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting(device.address)
        scope.launch {
            GattExclusivityGate.acquire(slot)
            if (userStopped) {
                GattExclusivityGate.release(slot)
                return@launch
            }
            val callback = GattCallback(scope, device.address)
            // 01/10/2026 (editor, RONDE 201, bugfix na live-test) — was
            // `autoConnect=true`. Dat is Android's trage achtergrond-
            // verbindmechanisme, bedoeld om te wachten tot een AL BEKEND/
            // GEBOND toestel weer in bereik komt — niet voor een zojuist
            // gescand, nog-niet-gebonden toestel. Op de hoofdtelefoon van
            // de gebruiker (met de G7 ernaast actief) bleef de verbinding
            // daardoor oneindig op "Connecting" staan zonder ooit Android's
            // koppelscherm te tonen, en leek de G7 daarbij ook nog naar
            // "Connecting" te springen — want `autoConnect=true` laat
            // Android zelf een doorlopende achtergrondscan draaien om het
            // toestel te vinden, wat extra radiodrukte geeft naast de G7's
            // eigen actieve sessie. G6, G7 en CareSens Air gebruiken
            // allemaal al bewust `autoConnect=false` (zie met name
            // CareSensAirDriver.kt's klasse-kdoc, waar dit exact dezelfde
            // les was na een eerdere, losse live-test) — scan-dan-direct-
            // verbind op het zojuist gevonden adres, geen achtergrond-
            // mechaniek. Hier nu hetzelfde bewezen patroon toegepast.
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appCtx, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appCtx, false, callback)
            }
            bluetoothGatt = gatt
        }
    }

    // ============================================================
    // GATT-levenscyclus + CGM Service-afhandeling.
    // ============================================================

    private inner class GattCallback(
        private val scope: CoroutineScope,
        private val deviceAddress: String
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    DiagnosticFileLogger.log("AccuChekSmartGuide: STATE_DISCONNECTED status=$status device=${gatt.device.address}")
                    GattExclusivityGate.release(slot)
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    highestSequenceSeenThisSession = null
                    if (userStopped) return
                    _connectionState.value = ConnectionState.Connecting("")
                    val ctx = appContext ?: return
                    // 01/10/2026 (editor, RONDE 198) — bij een SCHONE
                    // disconnect ná minstens één meting, voorspel de
                    // volgende meting (zie klasse-kdoc); bij een disconnect
                    // zonder ooit een meting ontvangen te hebben (bv. eerste
                    // koppelpoging mislukt) blijft de gewone foutenbackoff
                    // gelden.
                    val predictedCooldown = computeReconnectCooldownMs()
                    val cooldown = if (lastMeasurementAtMs != null && predictedCooldown > 0L) {
                        predictedCooldown
                    } else {
                        errorBackoffMs.also {
                            if (errorBackoffMs < ERROR_BACKOFF_MAX_MS) errorBackoffMs = (errorBackoffMs * 2).coerceAtMost(ERROR_BACKOFF_MAX_MS)
                        }
                    }
                    scheduleScanAttempt(scope, ctx, deviceAddress, cooldownMs = cooldown)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Couldn't discover services (status $status).")
                runCatching { gatt.disconnect() }
                return
            }
            val cgmService = gatt.getService(AccuChekSmartGuideProtocol.SERVICE_CGM)
            if (cgmService == null) {
                _connectionState.value = ConnectionState.Error("This device doesn't look like an Accu-Chek SmartGuide (no CGM Service).")
                runCatching { gatt.disconnect() }
                return
            }
            charCgmMeasurement = cgmService.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_CGM_MEASUREMENT)
            charRacp = cgmService.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_RACP)
            charCgmSpecificOps = cgmService.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_CGM_SPECIFIC_OPS)
            charCgmStatus = cgmService.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_CGM_STATUS)
            charCgmSessionRunTime = cgmService.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_CGM_SESSION_RUN_TIME)

            val measurementChar = charCgmMeasurement
            val racpChar = charRacp
            val opsChar = charCgmSpecificOps
            if (measurementChar == null || racpChar == null || opsChar == null) {
                _connectionState.value = ConnectionState.Error("CGM Measurement/RACP/Specific Ops characteristic missing.")
                runCatching { gatt.disconnect() }
                return
            }

            val deviceInfoService = gatt.getService(AccuChekSmartGuideProtocol.SERVICE_DEVICE_INFORMATION)
            charFirmwareRevision = deviceInfoService?.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_FIRMWARE_REVISION)
            charManufacturerName = deviceInfoService?.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_MANUFACTURER_NAME)
            charModelNumber = deviceInfoService?.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_MODEL_NUMBER)
            charSerialNumber = deviceInfoService?.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_SERIAL_NUMBER)
            charHardwareRevision = deviceInfoService?.getCharacteristic(AccuChekSmartGuideProtocol.CHAR_HARDWARE_REVISION)

            // 01/10/2026 (editor, RONDE 198) — leesvolgorde: Device
            // Information (puur informatief) -> CGM Status (bepaalt
            // sensorStartTimeMs, dus MOET vóór de eerste meting binnenkomt)
            // -> CGM Session Run Time -> dan pas notificaties/indicaties aan.
            readQueue.clear()
            readQueue.addAll(
                listOfNotNull(
                    charManufacturerName, charModelNumber, charSerialNumber,
                    charFirmwareRevision, charHardwareRevision, charCgmStatus, charCgmSessionRunTime
                )
            )
            readNextInQueue(gatt)
        }

        private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

        private fun readNextInQueue(gatt: BluetoothGatt) {
            val next = readQueue.removeFirstOrNull()
            if (next == null) {
                enableCgmMeasurementNotify(gatt)
                return
            }
            if (!gatt.readCharacteristic(next)) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: readCharacteristic(${next.uuid}) failed to start")
                readNextInQueue(gatt)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (handleInsufficientAuth(gatt, status) { readNextInQueue(gatt) }) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleDeviceInfoOrStatusRead(characteristic.uuid, value)
            } else {
                DiagnosticFileLogger.log("AccuChekSmartGuide: read FAILED for ${characteristic.uuid} status=$status")
            }
            readNextInQueue(gatt)
        }

        private fun handleDeviceInfoOrStatusRead(uuid: java.util.UUID, value: ByteArray) {
            val ctx = appContext
            when (uuid) {
                AccuChekSmartGuideProtocol.CHAR_MANUFACTURER_NAME -> persistDeviceInfo(ctx) { it.copy(manufacturer = AccuChekSmartGuideProtocol.parseUtf8String(value)) }
                AccuChekSmartGuideProtocol.CHAR_MODEL_NUMBER -> persistDeviceInfo(ctx) { it.copy(model = AccuChekSmartGuideProtocol.parseUtf8String(value)) }
                AccuChekSmartGuideProtocol.CHAR_SERIAL_NUMBER -> persistDeviceInfo(ctx) { it.copy(serial = AccuChekSmartGuideProtocol.parseUtf8String(value)) }
                AccuChekSmartGuideProtocol.CHAR_FIRMWARE_REVISION -> persistDeviceInfo(ctx) { it.copy(firmwareRevision = AccuChekSmartGuideProtocol.parseUtf8String(value)) }
                AccuChekSmartGuideProtocol.CHAR_HARDWARE_REVISION -> persistDeviceInfo(ctx) { it.copy(hardwareRevision = AccuChekSmartGuideProtocol.parseUtf8String(value)) }
                AccuChekSmartGuideProtocol.CHAR_CGM_STATUS -> {
                    val cgmStatus = AccuChekSmartGuideProtocol.parseCgmStatus(value)
                    if (cgmStatus == null) {
                        DiagnosticFileLogger.log("AccuChekSmartGuide: unparseable CGM Status bytes=${value.joinToString(",")}")
                        return
                    }
                    val now = System.currentTimeMillis()
                    sensorStartTimeMs = now - cgmStatus.timeOffsetMinutes * 60_000L
                    DiagnosticFileLogger.log(
                        "AccuChekSmartGuide: CGM Status timeOffsetMinutes=${cgmStatus.timeOffsetMinutes} " +
                            "sensorStartTimeMs=$sensorStartTimeMs flags=${cgmStatus.flags} allClear=${cgmStatus.flags.isAllClear}"
                    )
                    if (ctx != null) {
                        scope.launch {
                            runCatching {
                                AppSettings(ctx).setAccuChekCgmStatus(
                                    slot,
                                    statusByteOf(cgmStatus.flags),
                                    calTempByteOf(cgmStatus.flags),
                                    warningByteOf(cgmStatus.flags),
                                    now
                                )
                                AppSettings(ctx).setAccuChekSessionStartAtMs(slot, sensorStartTimeMs)
                                // 02/10/2026 (editor, RONDE 208) — zie kdoc bij
                                // [handleRacpResponse]'s zelfde aanroep hieronder
                                // voor het volledige verhaal: deze CGM Status-
                                // uitlezing gebeurt bij ELKE (her)verbinding, ook
                                // als de sensorsessie al gestopt is, dus telt
                                // hier ook mee als bewijs van daadwerkelijk
                                // contact.
                                AppSettings(ctx).setAccuChekLastConnectedAtMs(slot, now)
                            }.onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting CGM Status failed: $it") }
                        }
                    }
                }
                AccuChekSmartGuideProtocol.CHAR_CGM_SESSION_RUN_TIME -> {
                    if (value.size >= 2) {
                        val runTimeMinutes = ((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)) * 60
                        DiagnosticFileLogger.log("AccuChekSmartGuide: CGM Session Run Time=$runTimeMinutes minutes")
                        if (ctx != null) {
                            scope.launch {
                                runCatching { AppSettings(ctx).setAccuChekSessionRunTimeMinutes(slot, runTimeMinutes) }
                                    .onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting run time failed: $it") }
                            }
                        }
                    }
                }
            }
        }

        private fun persistDeviceInfo(ctx: Context?, update: (AppSettings.AccuChekDeviceInfo) -> AppSettings.AccuChekDeviceInfo) {
            if (ctx == null) return
            scope.launch {
                runCatching {
                    val settings = AppSettings(ctx)
                    val empty = AppSettings.AccuChekDeviceInfo(null, null, null, null, null)
                    settings.setAccuChekDeviceInfo(slot, update(empty))
                }.onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting device info failed: $it") }
            }
        }

        private fun statusByteOf(f: AccuChekSmartGuideProtocol.CgmStatusFlags): Int =
            (if (f.sessionStopped) 0x01 else 0) or (if (f.deviceBatteryLow) 0x02 else 0) or
                (if (f.sensorTypeIncorrect) 0x04 else 0) or (if (f.sensorMalfunction) 0x08 else 0) or
                (if (f.deviceSpecificAlert) 0x10 else 0) or (if (f.generalDeviceFault) 0x20 else 0)

        private fun calTempByteOf(f: AccuChekSmartGuideProtocol.CgmStatusFlags): Int =
            (if (f.timeSyncRequired) 0x01 else 0) or (if (f.calibrationNotAllowed) 0x02 else 0) or
                (if (f.calibrationRecommended) 0x04 else 0) or (if (f.calibrationRequired) 0x08 else 0) or
                (if (f.sensorTemperatureTooHigh) 0x10 else 0) or (if (f.sensorTemperatureTooLow) 0x20 else 0)

        private fun warningByteOf(f: AccuChekSmartGuideProtocol.CgmStatusFlags): Int =
            (if (f.resultLowerThanDeviceCanProcess) 0x01 else 0) or (if (f.resultHigherThanDeviceCanProcess) 0x02 else 0) or
                (if (f.sensorRateOfDecreaseExceeded) 0x04 else 0) or (if (f.sensorRateOfIncreaseExceeded) 0x08 else 0) or
                (if (f.deviceResultLowerThanPatientLowLevel) 0x10 else 0) or (if (f.deviceResultHigherThanPatientHighLevel) 0x20 else 0)

        private fun enableCgmMeasurementNotify(gatt: BluetoothGatt) {
            val measurementChar = charCgmMeasurement ?: return
            enableNotify(gatt, measurementChar, useIndication = false) {
                enableRacpIndicate(gatt)
            }
        }

        private fun enableRacpIndicate(gatt: BluetoothGatt) {
            val racpChar = charRacp ?: return
            enableNotify(gatt, racpChar, useIndication = true) {
                enableCgmSpecificOpsIndicate(gatt)
            }
        }

        private fun enableCgmSpecificOpsIndicate(gatt: BluetoothGatt) {
            val opsChar = charCgmSpecificOps ?: return
            enableNotify(gatt, opsChar, useIndication = true) {
                requestCommunicationInterval(gatt)
            }
        }

        /**
         * 01/10/2026 (editor, RONDE 198) — betrouwbaarheidseis deze ronde:
         * eerst "Get" sturen (xDrip+'s `readOrChangeReportingPeriod()`-
         * logica), pas als het antwoord NIET al 5 minuten is een "Set"
         * sturen — zie [intervalWriteAttempted]'s kdoc voor waarom dit maar
         * één keer per sessie gebeurt, niet bij elke herverbinding.
         */
        private fun requestCommunicationInterval(gatt: BluetoothGatt) {
            val opsChar = charCgmSpecificOps ?: return
            writeCharacteristic(gatt, opsChar, AccuChekSmartGuideProtocol.buildGetCommunicationIntervalCommand())
        }

        private fun writeRacpRequest(gatt: BluetoothGatt) {
            val racpChar = charRacp ?: return
            val command = AccuChekSmartGuideProtocol.buildRacpReportRecordsGreaterOrEqual(nextSequenceNumber)
            DiagnosticFileLogger.log("AccuChekSmartGuide: requesting records >= seq=$nextSequenceNumber")
            writeCharacteristic(gatt, racpChar, command)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: CCCD write FAILED for ${descriptor.characteristic.uuid} status=$status")
            }
            pendingAfterNotifyEnabled.remove(descriptor.characteristic.uuid)?.invoke()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (handleInsufficientAuth(gatt, status) {
                    when (characteristic.uuid) {
                        AccuChekSmartGuideProtocol.CHAR_CGM_SPECIFIC_OPS -> requestCommunicationInterval(gatt)
                        AccuChekSmartGuideProtocol.CHAR_RACP -> writeRacpRequest(gatt)
                        else -> Unit
                    }
                }
            ) return
            // 01/10/2026 (editor, RONDE 200, bugfix na live-test) — zie
            // [intervalSetWriteInFlight]'s kdoc: een mislukte "Set
            // Communication Interval"-write op 0x2AAC is GEEN echte fout
            // wanneer het apparaat al een acceptabel interval rapporteerde
            // (precies 5 minuten, of de 0xFF-sentinel "snelst mogelijk" die
            // volgens de log-cadans in de praktijk ook 5 minuten betekent) —
            // dan is dit gewoon een write-protected control point dat niets
            // hoefde te veranderen. Alleen een write-FAILED loggen als dat
            // NIET het geval is, of voor elke andere characteristic.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == AccuChekSmartGuideProtocol.CHAR_CGM_SPECIFIC_OPS && intervalSetWriteInFlight) {
                    DiagnosticFileLogger.log(
                        "AccuChekSmartGuide: device already reports ${confirmedIntervalMinutes}min, " +
                            "write not needed/not permitted (status=$status) — leaving as-is"
                    )
                } else {
                    DiagnosticFileLogger.log("AccuChekSmartGuide: write FAILED for ${characteristic.uuid} status=$status")
                    // 02/10/2026 (editor, RONDE 202, bugfix na live-test) —
                    // dit kan ook de "Get Communication Interval"-write zelf
                    // zijn die mislukt (niet alleen de latere "Set"): een
                    // live log liet status=128 zien al bij de EERSTE write
                    // naar 0x2AAC, dus zonder ooit een "communication
                    // interval reported"-regel — deze sensor/firmware staat
                    // kennelijk HELEMAAL geen write naar dit control point
                    // toe (zelfs geen Get). Zonder deze fix bleef het de
                    // RACP-backfill-aanvraag (writeRacpRequest) hier
                    // simpelweg over — [onDescriptorWrite]'s keten riep die
                    // normaal pas aan ZODRA er een CGM Ops-respons
                    // binnenkwam, wat bij een mislukte write nooit gebeurt.
                    // Resultaat: geen enkele backfill van eventueel gemiste
                    // metingen sinds de vorige verbinding, stilzwijgend, de
                    // hele sessie lang — precies tegen de "zo betrouwbaar
                    // mogelijk"-eis in. Nu: bij een mislukte Get-write
                    // (dus NIET de Set-write, die staat los via
                    // [intervalSetWriteInFlight]) toch doorgaan naar de
                    // RACP-aanvraag, net zoals de wél-geslaagde paden dat
                    // altijd al deden. Communicatie-interval blijft dan
                    // terecht onbekend (toont "—" op het statusscherm) —
                    // dat is eerlijker dan een geraden waarde.
                    if (characteristic.uuid == AccuChekSmartGuideProtocol.CHAR_CGM_SPECIFIC_OPS && !intervalWriteAttempted) {
                        intervalWriteAttempted = true
                        writeRacpRequest(gatt)
                    }
                }
            }
            intervalSetWriteInFlight = false
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
                AccuChekSmartGuideProtocol.CHAR_CGM_MEASUREMENT -> handleCgmMeasurement(value)
                AccuChekSmartGuideProtocol.CHAR_RACP -> handleRacpResponse(value)
                AccuChekSmartGuideProtocol.CHAR_CGM_SPECIFIC_OPS -> handleCgmOpsResponse(gatt, value)
            }
        }

        private fun handleCgmOpsResponse(gatt: BluetoothGatt, value: ByteArray) {
            val response = AccuChekSmartGuideProtocol.parseCgmOpsResponse(value)
            when (response) {
                is AccuChekSmartGuideProtocol.CgmOpsResponse.CommunicationInterval -> {
                    DiagnosticFileLogger.log("AccuChekSmartGuide: communication interval reported=${response.minutes} min")
                    // 01/10/2026 (editor, RONDE 200, bugfix na live-test) —
                    // de live-test liet ELKE verbinding een "write FAILED
                    // status=128" voor 0x2AAC zien, ook al kwam de
                    // meetcadans in de log zelf precies op 5 minuten uit.
                    // Verklaring: deze sensor rapporteert zijn eigen,
                    // write-beschermde default via de CGM-spec's eigen
                    // 0xFF-sentinel ("snelst door het apparaat ondersteunde
                    // interval", zie [buildSetCommunicationIntervalCommand]'s
                    // kdoc hierboven), NIET letterlijk als het getal 5 — RONDE
                    // 198/199 behandelde alleen een letterlijke "5" als
                    // acceptabel, dus probeerde (zinloos) elke keer een Set
                    // te forceren naar een al-bereikte situatie. Beide nu
                    // als "al acceptabel" behandeld.
                    val alreadyAcceptable = response.minutes == TARGET_COMMUNICATION_INTERVAL_MINUTES ||
                        response.minutes == COMMUNICATION_INTERVAL_FASTEST_SENTINEL
                    val effectiveMinutes = if (alreadyAcceptable) TARGET_COMMUNICATION_INTERVAL_MINUTES else response.minutes
                    confirmedIntervalMinutes = effectiveMinutes
                    persistConfirmedInterval(effectiveMinutes)
                    if (!alreadyAcceptable && !intervalWriteAttempted) {
                        intervalWriteAttempted = true
                        val opsChar = charCgmSpecificOps
                        if (opsChar != null) {
                            DiagnosticFileLogger.log("AccuChekSmartGuide: forcing communication interval to $TARGET_COMMUNICATION_INTERVAL_MINUTES min")
                            intervalSetWriteInFlight = true
                            writeCharacteristic(gatt, opsChar, AccuChekSmartGuideProtocol.buildSetCommunicationIntervalCommand(TARGET_COMMUNICATION_INTERVAL_MINUTES))
                        }
                    } else if (!intervalWriteAttempted) {
                        // Al acceptabel (5 min, of 0xFF/"snelst mogelijk") —
                        // niets te forceren, meteen door naar backfill.
                        intervalWriteAttempted = true
                        DiagnosticFileLogger.log(
                            "AccuChekSmartGuide: device already reports ${response.minutes} min (acceptable, " +
                                "effectively $effectiveMinutes min) — write not needed, skipping straight to backfill"
                        )
                        writeRacpRequest(gatt)
                    }
                }
                is AccuChekSmartGuideProtocol.CgmOpsResponse.ResponseCode -> {
                    DiagnosticFileLogger.log(
                        "AccuChekSmartGuide: CGM Ops response requestOpCode=${response.requestOpCode} " +
                            "responseCode=${response.responseCode} isSuccess=${response.isSuccess}"
                    )
                    if (response.isSuccess) {
                        confirmedIntervalMinutes = TARGET_COMMUNICATION_INTERVAL_MINUTES
                        persistConfirmedInterval(TARGET_COMMUNICATION_INTERVAL_MINUTES)
                    }
                    // Of de "Set" nu gelukt is of niet: ga door naar backfill,
                    // een mislukte interval-forcering mag de rest van de
                    // sessie niet blokkeren.
                    writeRacpRequest(gatt)
                }
                null -> DiagnosticFileLogger.log("AccuChekSmartGuide: unparseable CGM Ops response bytes=${value.joinToString(",")}")
            }
        }

        private fun persistConfirmedInterval(minutes: Int) {
            val ctx = appContext ?: return
            scope.launch {
                runCatching { AppSettings(ctx).setAccuChekCommunicationIntervalConfirmedMinutes(slot, minutes) }
                    .onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting confirmed interval failed: $it") }
            }
        }

        /**
         * 01/10/2026 (editor, RONDE 200) — bovengrens/ondergrens puur als
         * veiligheidsnet (zie README.md's Ronde 200-sectie): een trend
         * buiten ongeveer ±30 mg/dL/min, of een kwaliteit buiten 0-100%, is
         * voor deze sensor fysiek onmogelijk — ongeacht of de RONDE 200-
         * veldvolgordefix hierboven de layout nu wél/nog niet helemaal
         * correct heeft. Discard in plaats van door te laten naar de UI/
         * AAPS-pijplijn.
         */
        private fun plausibleOrNull(value: Double?, min: Double, max: Double, label: String): Double? {
            if (value == null) return null
            if (value < min || value > max) {
                DiagnosticFileLogger.log(
                    "AccuChekSmartGuide: implausible $label value discarded: $value — flags-byte parsing may be wrong, see raw hex above"
                )
                return null
            }
            return value
        }

        private fun handleCgmMeasurement(value: ByteArray) {
            // 01/10/2026 (editor, RONDE 200) — raw-hex diagnostiek voor ELKE
            // meting (zelfde patroon als DexcomG7Driver.kt's
            // `logRound1ValidationFailure()`'s `hex()`-helper) — zodat een
            // volgende live-log capture de RONDE 200-veldvolgordefix exact
            // kan narekenen i.p.v. opnieuw te moeten gokken, zie README.md's
            // Ronde 200-sectie voor de volledige eerlijkheid daarover.
            val rawHex = value.joinToString("") { "%02x".format(it) }
            DiagnosticFileLogger.log("AccuChekSmartGuide: raw CGM measurement bytes=$rawHex")

            val measurement = AccuChekSmartGuideProtocol.parseCgmMeasurement(value, sensorStartTimeMs)
            if (measurement == null) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: unparseable CGM measurement bytes=$rawHex")
                return
            }
            // 02/10/2026 (editor, RONDE 204, bugfix na live-test — "levert
            // geen nieuwe data en ververst niet" na een heropgezette
            // koppeling) — [highestSequenceSeenThisSession] werd in deze
            // klasse tot nu toe NERGENS daadwerkelijk bijgewerkt, alleen
            // naar `null` GERESET bij elke disconnect (zie
            // [onConnectionStateChange]). Gevolg: [handleRacpResponse]'s
            // `if (highest != null)`-tak kwam NOOIT aan bod, dus
            // `nextSequenceNumber` werd NOOIT bijgewerkt/gepersisteerd, en
            // ELKE (her)verbinding — ook de volgende dag — vroeg gewoon
            // opnieuw de VOLLEDIGE geschiedenis op ("requesting records >=
            // seq=0"). Een live-log liet dit letterlijk zien: de exact-
            // identieke reeks glucosewaarden uit een eerdere sessie kwam
            // de volgende ochtend woordelijk opnieuw voorbij via RACP-
            // backfill, gevolgd door stilte — geen nieuwe/actuele data,
            // precies de klacht. Voor de CGM Service is het RACP-filter
            // trouwens sowieso op Time Offset gebaseerd (minuten sinds
            // sessiestart), niet op een letterlijk sequentienummer zoals
            // bij de oudere Glucose Profile (0x1808, de RONDE 197-
            // misgreep) — [nextSequenceNumber] was dus altijd al bedoeld
            // als "offset in minuten", alleen nooit gevuld. Nu: elke
            // succesvol geparste meting (ook een zonder bruikbare
            // glucosewaarde, bv. tijdens opwarmen — die telt immers ook
            // mee in de geschiedenis) werkt dit bij met zijn eigen
            // tijd-offset, zodat de volgende RACP-aanvraag écht alleen om
            // NIEUWE records vraagt. Dit verkleint bovendien de opzet-
            // burst bij elke herverbinding drastisch (was tot ~30
            // metingen, wordt typisch 1-2) — relevant voor Ronde 203's
            // GATT-exclusiviteitsvenster: hoe korter die burst, hoe
            // minder kans op radiocontentie met de G7 ernaast.
            val offsetMinutes = ((measurement.timestampMs - sensorStartTimeMs) / 60_000L)
                .toInt().coerceAtLeast(0)
            highestSequenceSeenThisSession = maxOf(highestSequenceSeenThisSession ?: -1, offsetMinutes)
            measurement.statusFlags?.let {
                if (!it.isAllClear) DiagnosticFileLogger.log("AccuChekSmartGuide: per-reading status flags=$it")
            }
            val plausibleTrend = plausibleOrNull(measurement.trendMgdlPerMin, -30.0, 30.0, "trend")
            val plausibleQuality = plausibleOrNull(measurement.qualityPercent, 0.0, 100.0, "quality")
            val ctx = appContext
            if (plausibleQuality != null && ctx != null) {
                scope.launch {
                    runCatching { AppSettings(ctx).setAccuChekLastQualityPercent(slot, plausibleQuality) }
                        .onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting quality failed: $it") }
                }
            }
            // 02/10/2026 (editor, RONDE 208, op verzoek na live-melding — het
            // statusscherm toonde bij "Last connected" een tijdstip van
            // uren terug, ook terwijl de app op de achtergrond gewoon
            // herhaaldelijk opnieuw verbond en de sensor bevroeg: de
            // gebruiker kon zo niet zien of de app daadwerkelijk nog iets
            // probeerde, of ergens muurvast zat) — voorheen werd
            // `setAccuChekLastConnectedAtMs()` pas HELEMAAL ONDERAAN deze
            // functie aangeroepen, ná de `glucoseMgdl == null`-early-return
            // hieronder — dus een meting ZONDER bruikbare glucosewaarde
            // (bv. tijdens opwarmen, of — zoals hier — elke meting ontvangen
            // tijdens een al langer actieve verbinding zonder dat de sensor
            // zelf iets nieuws oplevert) telde nooit mee als "contact".
            // Verplaatst naar vóór die early-return: elke succesvol
            // GEPARSEDE meting (ongeacht bruikbare waarde) bewijst dat de
            // sensor daadwerkelijk heeft geantwoord op dit moment.
            val ctxForContact = appContext
            if (ctxForContact != null) {
                scope.launch {
                    runCatching { AppSettings(ctxForContact).setAccuChekLastConnectedAtMs(slot, System.currentTimeMillis()) }
                        .onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting last-contact failed: $it") }
                }
            }
            val glucoseMgdl = measurement.glucoseMgdl
            if (glucoseMgdl == null) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: measurement has no usable glucose value — skipped.")
                return
            }
            val previous = previousReading
            val computedTrend = TrendCalculator.measuredMgdlPerMin(
                GlucoseReading(glucoseMgdl, 0f, measurement.timestampMs, sensorStartedAtMs, sensorType),
                previous
            ) ?: 0f
            // 01/10/2026 (editor, RONDE 198) — zie de opdracht die tot dit
            // bestand leidde, punt 7: als de sensor zelf al een CGM Trend
            // Information-veld meestuurt, gebruik DIE direct i.p.v. 'm zelf
            // via TrendCalculator te herberekenen. 01/10/2026 (editor, RONDE
            // 200) — nu via [plausibleTrend]: een door de plausibiliteitsklem
            // afgekeurde sensor-trend valt gewoon terug op [computedTrend],
            // precies zoals een ontbrekende sensor-trend dat al deed.
            val trend = (plausibleTrend?.toFloat()) ?: computedTrend
            val reading = GlucoseReading(
                glucoseMgdl = glucoseMgdl,
                trendMgdlPerMin = trend,
                timestampMs = measurement.timestampMs,
                sensorStartedAtMs = sensorStartedAtMs,
                sensorType = sensorType
            )
            previousReading = reading
            lastMeasurementAtMs = measurement.timestampMs
            errorBackoffMs = ERROR_BACKOFF_INITIAL_MS
            _connectionState.value = ConnectionState.Connected(deviceAddress, "Accu-Chek SmartGuide")
            _readings.tryEmit(reading)
            // RONDE 208 — de last-contact-persist hierboven (vóór de
            // glucoseMgdl-null-early-return) dekt dit moment al mee, dus
            // geen aparte tweede aanroep hier meer nodig.
            DiagnosticFileLogger.log("AccuChekSmartGuide: reading glucoseMgdl=$glucoseMgdl trend=$trend")
        }

        private fun handleRacpResponse(value: ByteArray) {
            val response = AccuChekSmartGuideProtocol.parseRacpResponse(value)
            if (response == null) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: unparseable RACP response bytes=${value.joinToString(",")}")
                return
            }
            DiagnosticFileLogger.log(
                "AccuChekSmartGuide: RACP response requestOpCode=${response.requestOpCode} " +
                    "responseCode=${response.responseCode} isSuccess=${response.isSuccess} " +
                    "isNoRecordsFound=${response.isNoRecordsFound}"
            )
            // 02/10/2026 (editor, RONDE 208) — dit RACP-antwoord is, naast de
            // CGM Status-uitlezing (zie [handleDeviceInfoOrStatusRead]'s
            // zelfde aanroep), het andere moment dat bij ELKE (her)verbinding
            // gegarandeerd gebeurt — ook als de sensorsessie al gestopt is
            // (dan komt hier `isNoRecordsFound=true` terug i.p.v. een
            // disconnect of stilte). Onvoorwaardelijk hier persisten, ook bij
            // een onverwachte foutcode: elk antwoord — succes, "geen nieuwe
            // records" of een echte fout — bewijst dat de app op dit moment
            // daadwerkelijk met de sensor heeft gecommuniceerd, precies het
            // bewijs dat tot nu toe ontbrak op het statusscherm (zie
            // [handleCgmMeasurement]'s kdoc voor het volledige verhaal).
            val ctxForRacpContact = appContext
            if (ctxForRacpContact != null) {
                scope.launch {
                    runCatching {
                        AppSettings(ctxForRacpContact).setAccuChekLastConnectedAtMs(slot, System.currentTimeMillis())
                    }.onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting last-contact (RACP) failed: $it") }
                }
            }
            // 02/10/2026 (editor, RONDE 203, bugfix na live-test met G7
            // ERNAAST actief) — dit RACP-antwoord is het einde van de
            // eenmalige opzet-burst na elke (her)verbinding (Device Info ->
            // CGM Status -> Session Run Time -> interval-check -> RACP-
            // backfill). ANDERS dan G6/G7/CareSens Air disconnect de
            // SmartGuide NA deze opzet bewust NIET — de CGM Service is
            // ontworpen om de verbinding gewoon open te houden en elke 5
            // minuten een meting te PUSHEN via notify (zie klasse-kdoc).
            // [GattExclusivityGate] werd tot nu toe pas bij een
            // daadwerkelijke disconnect losgelaten (zie [connectToDevice]'s
            // kdoc) — voor deze sensor betekende dat dus: de hele sessie
            // lang vastgehouden, soms urenlang. Een live-log liet zien dat
            // de G7 daardoor keer op keer de volle 60s (MAX_WAIT_MS) moest
            // wachten en er dan ONBESCHERMD (zonder exclusiviteit)
            // doorheen ging — en precies in dat venster viel de G7's
            // verbinding om (status=147, i.p.v. de normale schone
            // status=0), waarna de gebruiker de hele app moest herstarten
            // om de G7 weer aan de praat te krijgen. Nu: geef de
            // exclusiviteit hier, vlak na de opzet-burst, AL vrij — vanaf
            // dit punt is de verbinding alleen nog passief luisterend naar
            // periodieke notify-pushes, dat heeft geen exclusieve
            // GATT-toegang meer nodig. Bij een latere herverbinding wordt
            // de gate gewoon weer aangevraagd via [connectToDevice], voor
            // DIE nieuwe opzet-burst. Veilig om hier onvoorwaardelijk aan
            // te roepen, ook als deze respons een fout meldt — de burst is
            // sowieso voorbij, hem blijven vasthouden helpt dan niets.
            GattExclusivityGate.release(slot)
            if (!response.isSuccess && !response.isNoRecordsFound) return
            val highest = highestSequenceSeenThisSession
            if (highest != null) {
                val newNext = highest + 1
                nextSequenceNumber = newNext
                val ctx = appContext
                if (ctx != null) {
                    scope.launch {
                        runCatching { AppSettings(ctx).setAccuChekSmartGuideNextSequence(slot, newNext) }
                            .onFailure { DiagnosticFileLogger.log("AccuChekSmartGuide: persisting next sequence failed: $it") }
                    }
                }
            }
            if (_connectionState.value !is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Connected(deviceAddress, "Accu-Chek SmartGuide")
            }
        }

        private fun handleInsufficientAuth(gatt: BluetoothGatt, status: Int, retry: () -> Unit): Boolean {
            if (status != 15) return false
            DiagnosticFileLogger.log(
                "AccuChekSmartGuide: GATT_INSUFFICIENT_AUTHENTICATION (status=15) — waiting for the " +
                    "device's own pairing/bonding flow, will retry once bonded."
            )
            pendingAfterBond = retry
            return true
        }

        private val pendingAfterNotifyEnabled = mutableMapOf<java.util.UUID, () -> Unit>()

        private fun enableNotify(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            useIndication: Boolean,
            onDone: () -> Unit
        ) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                DiagnosticFileLogger.log("AccuChekSmartGuide: setCharacteristicNotification failed for ${characteristic.uuid}")
            }
            val descriptor = characteristic.getDescriptor(AccuChekSmartGuideProtocol.CLIENT_CHARACTERISTIC_CONFIG)
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

    /** Epoch-tijdstip waarop de huidige sensorsessie begon — afgeleid uit
     *  CGM Status's time-offset-veld (zie [GattCallback.handleDeviceInfoOrStatusRead]).
     *  `0L` tot die eerste lezing binnen is (dan valt elke CGM Measurement's
     *  timeOffsetMinutes gewoon terug op een epoch-tijdstip vlak na 1970 —
     *  onschadelijk zolang er geen metingen binnenkomen vóór CGM Status
     *  gelezen is, wat de leesvolgorde in [GattCallback.onServicesDiscovered]
     *  garandeert). */
    private var sensorStartTimeMs: Long = 0L

    private fun registerBondReceiver(context: Context, deviceAddress: String) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != deviceAddress) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    DiagnosticFileLogger.log("AccuChekSmartGuide: bonded, resuming after-bond action")
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
}
