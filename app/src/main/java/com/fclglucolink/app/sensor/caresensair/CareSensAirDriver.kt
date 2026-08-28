package com.fclglucolink.app.sensor.caresensair

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.fclglucolink.app.logging.DiagnosticFileLogger
import android.os.Build
import android.os.ParcelUuid
import com.fclglucolink.app.data.AppSettings
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
 * FCLGlucoLink — CareSens Air driver
 * ============================================================================
 *
 * 01/08/2026 (editor) — VOLLEDIGE HERSCHRIJVING van de GATT-handshake/
 * -datastroom, na een grote koerscorrectie. Zie CareSensAirGattProtocol.kt's
 * kdoc en app/src/main/cpp/caresensair_bridge.cpp's kdoc voor de volledige
 * achtergrond — kort samengevat: eerdere versies van dit bestand gingen uit
 * van de standaard Bluetooth Glucose Profile (0x1808 e.a.). Live-onderzoek
 * met nRF Connect tegen een echte sensor toonde aan dat dat FOUT was:
 * CareSens Air praat een eigen, propriëtair protocol (UUID's die
 * overeenkomen met Juggluco's `AirGattCallback.java`), waarbij de sensor
 * ruwe elektrochemische signaaldata stuurt die pas via een closed-source
 * kalibratiebibliotheek (native laag, zie CareSensAirNative.kt) omgezet
 * wordt naar een echte mg/dL-waarde.
 *
 * De koppel-stappen 1 (barcode-scan) en 4 (verbindings-timeout,
 * TRANSPORT_LE, bond-status-ontvanger — zie eerdere kdoc-geschiedenis in
 * README) blijven ONGEWIJZIGD geldig en zijn hieronder overgenomen. Wat
 * volledig herschreven is: de GATT-handshake ná `onServicesDiscovered()` —
 * dat volgt nu, karakteristiek voor karakteristiek, Juggluco's
 * `AirGattCallback.java`.
 *
 * BEKENDE, BEWUSTE VEREENVOUDIGINGEN t.o.v. Juggluco (zie ook
 * CareSensAirGattProtocol.kt's kdoc):
 *  - Geen CRC-verificatie op het kalibratieprofiel — Juggluco's eigen
 *    disconnect-op-crc-fout is sowieso pas bij TWEE mislukte crc-varianten,
 *    dus een zachte controle, geen harde vereiste.
 *  - Alleen het handshake-pad voor swRevision >= "1.5" geport (moderne
 *    sensor-firmware) — een oudere sensor geeft een duidelijke foutmelding
 *    i.p.v. een stilzwijgend fout pad te volgen.
 *  - Geen `unbond()` op "sensor ended"/"transmitter reset"-berichten (komt
 *    in Juggluco's bron via een niet-publieke `removeBond()`-reflectie-
 *    aanroep — na de eerdere BluetoothGatt.refresh()-regressie in deze
 *    codebase bewust vermeden; gewoon disconnecten volstaat, Android's
 *    eigen bond blijft dan gewoon bestaan, wat geen probleem is).
 *  - Na een geslaagde meting blijft de verbinding OPEN (in plaats van
 *    Juggluco's eigen "kort verbinden, meting ophalen, disconnecten,
 *    5 minuten later opnieuw"-patroon) — dit is een aanname die de EERSTE
 *    live test moet bevestigen: als de sensor periodiek blijft
 *    doorsturen over een openstaande verbinding is dit prima, zo niet dan
 *    is dit de eerste plek om aan te passen (los van protocol-correctheid,
 *    een verbindings-levenscyclus-keuze).
 *
 * 02/08/2026 (editor, na live-test — "als het scherm op zwart gaat dan
 * gaat fclglucolink lopen vertragen ... juggluco heeft dat probleem
 * absoluut niet"; bevestigd met een tweede test — telefoon in de broekzak
 * tijdens een wandeling op een Google Pixel 8, dus GEEN Doze/stilstand-
 * scenario en GEEN fabrikant-specifieke achtergrond-app-killer) — EERSTE
 * herontwerp-poging: scan-dan-verbind vervangen door een directe
 * `connectGatt(autoConnect=true)`, in de veronderstelling dat Android's
 * achtergrond-scanbeperking de oorzaak was en dat de OS-eigen
 * achtergrond-herverbind-wacht van `autoConnect=true` daar niet onder zou
 * vallen. Zie de kdoc-geschiedenis (verwijderd in de ronde hieronder) voor
 * de volledige toenmalige redenering.
 *
 * 02/08/2026 (editor, TWEEDE live-test, avond — die poging bleek VERKEERD)
 * — met `autoConnect=true` maakte de sensor bij uitgeschakeld scherm
 * HELEMAAL geen contact meer, i.p.v. de eerdere onregelmatige maar wel
 * WERKENDE verbinding. Ontgrendelen van het scherm (zonder zelfs AAPS/
 * FCLGlucoLink te openen) liet 'm binnen 1 minuut weer bijwerken — een
 * sterke aanwijzing dat Android's OS-eigen `autoConnect=true`-achtergrond-
 * wacht op dit toestel simpelweg NIET actief scant zolang het scherm uit
 * staat (mogelijk fabrikant-/chipset-specifiek gedrag van die interne,
 * niet-publieke scanlus). Extra bevestiging: dezelfde testtelefoon met de
 * VIRTUELE sensor (simulator, geen BLE) bleef de hele nacht gewoon
 * doorwerken — dat isoleert het probleem specifiek tot BLE-gedrag, niet
 * tot een algemenere achtergrond-executieblokkade (die zou de simulator
 * óók geraakt hebben, aangezien beide door dezelfde foreground-service
 * lopen).
 *
 * Op verzoek van de gebruiker is daarop de ECHTE Juggluco-apk (10.9.8,
 * arm64, dezelfde bron als de rest van dit CareSens Air-traject) opnieuw
 * gedecompileerd (androguard, DAD-decompiler — jadx/apktool niet
 * beschikbaar in deze sandbox) specifiek om te zien hoe Juggluco's eigen
 * `SensorBluetooth`-klasse (in de obfuscated apk als `bk0` terug te vinden
 * — bevestigd via de nog aanwezige `Log.e("SensorBluetooth", ...)`-tags,
 * ProGuard/R8 laat dat soort log-tag-strings vaak intact ook al wordt de
 * klasse zelf hernoemd) dit daadwerkelijk oplost. Bevinding: Juggluco
 * gebruikt HELEMAAL GEEN `autoConnect=true` — het blijft, net als onze
 * ORIGINELE aanpak, gewoon scan-dan-verbind
 * (`connectGatt(device, false, ...)`). Het verschil zit 'm in HOE vaak en
 * hoe geduldig er gescand wordt:
 *
 *  1. Juggluco's `SensorBluetooth` (bk0) houdt een statische,
 *     gedeelde wachtrij bij van scan-START-tijdstippen (`bk0.q`,
 *     `ArrayDeque<Long>`) en telt vóór ELKE nieuwe scanpoging
 *     (`bk0.k()`) hoeveel daarvan binnen de afgelopen 31 SECONDEN
 *     vallen. Zijn dat er al 5, dan wordt de volgende scanpoging
 *     UITGESTELD tot dat venster weer ruimte heeft — in plaats van de
 *     scan gewoon te starten en te riskeren dat Android's eigen
 *     (ongedocumenteerde) achtergrond-scanbeperking daarna de
 *     scanresultaten zelf gaat vertragen/onderdrukken. Dit getal (5 per
 *     31s) is zichtbaar geen toeval — het is Juggluco's eigen,
 *     zelfopgelegde plafond dat NET onder Android's bekende
 *     achtergrond-scanquota blijft, zodat de eigen scans nooit de
 *     strengere OS-throttling triggeren.
 *  2. Na het STOPPEN van een scanpoging (apparaat gevonden-en-verbonden,
 *     Bluetooth Wachtrij ÓF een scan die niets opleverde) wacht Juggluco
 *     STANDAARD minstens 60 SECONDEN (`bk0.u()` -> `this.s(60000)")
 *     voordat de VOLGENDE scanpoging start — niet, zoals onze originele
 *     aanpak, een verse scan bij vrijwel elke herverbinding van de sensor
 *     zelf (die zijn eigen ~26-30s-duty-cycle heeft, dus grofweg elke
 *     30-90s). Pas als de data een tijd stil blijft, verdubbelt Juggluco
 *     die pauze verder (vanaf een basis van 5 minuten).
 *
 * Dat een tragere, zelf-gepaceerde scanlus geen data mist, komt doordat
 * CareSens Air zelf al een sequence-/geschiedenis-mechanisme heeft
 * (`CareSensAirNative.getLastSequence`/`buildNumberRecordsCommand`,
 * al aanwezig in dit bestand) — een gemiste duty-cycle terwijl de app
 * geduldig op zijn beurt wacht, wordt bij de eerstvolgende geslaagde
 * verbinding gewoon ingehaald. Het HERONTWERP hieronder combineert dus:
 * terug naar scan-dan-verbind (`autoConnect=false`, `startConnectScan()`
 * hersteld), plus Juggluco's exacte twee pace-regels (5 scans/31s-plafond
 * + 60s-minimumpauze tussen scanpogingen) — zie `ScanRateLimiter` en
 * `MIN_SCAN_COOLDOWN_MS` hieronder.
 *
 * 02/08/2026 (editor, RONDE 26 — na een SCHONE test met v72+v73 samen, dus
 * ZONDER Recents-swipe, waarbij het probleem toch identiek terugkwam: "Ik
 * heb nu de apps niet weg geswiped en nog steeds update hij niet. Alleen
 * het scherm zwart laten worden") — de vorige ronde (25a) verhoogde
 * `SCAN_ATTEMPT_TIMEOUT_MS` van 40s naar 90s op basis van de gebruiker's
 * eigen, redelijke hypothese ("60 à 90 seconden"), maar dat getal was
 * nooit met zekerheid uit Juggluco's bytecode bevestigd — puur een
 * schatting. Terug naar de decompile, dit keer specifiek op zoek naar de
 * daadwerkelijke scan-tijdslogica: die bleek niet in `bk0` (SensorBluetooth)
 * zelf te zitten, maar in een apart gedeeld `Runnable` (`w2`, via `bk0`'s
 * veld `e = new w2(8, this)`) dat pas zichtbaar werd na een TWEEDE, gerichte
 * decompile-poging (de eerste volledige decompile brak kennelijk af vóórdat
 * die klasse werd bereikt). `w2.run()`, geval 8, is ondubbelzinnig: één
 * `startScan()`-aanroep, GEEN bijbehorende `stopScan()`, en daarna plant
 * het zichzelf gewoon 390 SECONDEN (6,5 minuut) later opnieuw in
 * (`Applic.t.schedule(v0_10.e, 390000, MILLISECONDS)`) — niet 40-90
 * seconden. Juggluco laat de scan dus gewoon DOORLOPEN; die 390s-
 * herplanning is een zelf-herstellend veiligheidsnet (voor het geval
 * Android de langlopende scan ondertussen stil beëindigt heeft), geen
 * "geef op, wacht, probeer straks opnieuw"-cyclus.
 *
 * Onze eigen `SCAN_ATTEMPT_TIMEOUT_MS`-aanpak deed precies het
 * tegenovergestelde: na elke mislukte poging werd de scan ACTIEF gestopt,
 * gevolgd door `MIN_SCAN_COOLDOWN_MS` (60s) waarin er HELEMAAL NIET
 * gescand werd. Bij een sensor die maar korte, terugkerende
 * advertentievensters heeft, viel dat venster kennelijk vaak genoeg
 * precies in zo'n dode 60s-periode om de waargenomen onregelmatige
 * meerdere-minuten-vertraging te verklaren — en 90s-in-plaats-van-40s
 * verkleinde dat risico wel iets, maar loste de dode periode zelf niet op.
 *
 * `SCAN_ATTEMPT_TIMEOUT_MS` is dan ook VERWIJDERD. Vervangen door
 * `SCAN_REARM_INTERVAL_MS` (390s, exact Juggluco's eigen getal) en
 * `scheduleRearm()`: de scan blijft nu gewoon actief doorlopen totdat het
 * apparaat gevonden wordt, zonder onderbreking — `MIN_SCAN_COOLDOWN_MS`
 * blijft wél gelden op de twee plekken waar Juggluco 'm zelf ook toepast
 * (ná een echte `onScanFailed()`, en ná een GATT-disconnect).
 */
// 08/08/2026 (editor, RONDE 55) — ScanRateLimiter is VERPLAATST naar
// sensor/ble/ScanRateLimiter.kt (gedeeld, proces-breed) — de Dexcom
// G6-driver is de eerste nieuwe gebruiker naast deze. Zie dat bestand voor
// de volledige kdoc/aanleiding (mirror van Juggluco's SensorBluetooth.q/k()).

/**
 * 10/08/2026 (editor, RONDE 79 -- 2-sensoren-architectuur) -- zie
 * DexcomG6Driver.kt's identieke kdoc bij zijn [slot]-constructorparameter:
 * zelfde reden hier (sensor-start/last-connected-boekhouding leest/schrijft
 * deze driver zelf rechtstreeks via AppSettings).
 */
class CareSensAirDriver(private val slot: SensorSlot) : SensorDriver {

    override val sensorType: SensorType = SensorType.CARESENS_AIR

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<GlucoseReading>(replay = 0, extraBufferCapacity = 8)
    override val readings: SharedFlow<GlucoseReading> = _readings.asSharedFlow()

    private val driverStartedAtMs = System.currentTimeMillis()

    private var appSettings: AppSettings? = null
    private var driverScope: CoroutineScope? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var userStopped = false

    private var charSerial: BluetoothGattCharacteristic? = null
    private var charSwRevision: BluetoothGattCharacteristic? = null
    private var charGlucoseData: BluetoothGattCharacteristic? = null
    private var charAppInfo: BluetoothGattCharacteristic? = null
    private var charAppId: BluetoothGattCharacteristic? = null

    private var sensorSerial: String? = null
    private var swRevision: String? = null
    private var needsTimeSync: Boolean = false

    // 01/08/2026 (editor) — handle naar de native kalibratiestatus voor DEZE
    // verbinding — zie CareSensAirNative.kt's kdoc. Aangemaakt in
    // onServicesDiscovered (zodra we het serienummer kennen, voor
    // restore()), opgeruimd (na persist()) in disconnect().
    private var nativeStateHandle: Long? = null

    private var bondReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    // 02/08/2026 (editor) — de scan die een herverbindpoging opstart (zie
    // startConnectScan()) — vastgehouden zodat een lopende scan gestopt kan
    // worden zodra het apparaat gevonden is (of bij disconnect()).
    private var connectScanCallback: ScanCallback? = null

    // 02/08/2026 (editor) — de coroutine die (ScanRateLimiter-plafond +
    // MIN_SCAN_COOLDOWN_MS-pauze respecterend, zie scheduleScanAttempt())
    // op de volgende scanpoging wacht — geannuleerd bij disconnect() zodat
    // een uitgestelde poging niet alsnog na een expliciete stop vuurt.
    private var reconnectJob: Job? = null

    // 02/08/2026 (editor) — periodieke statustekst-ticker, gestart in
    // connect() en gestopt in disconnect(), zie
    // updateConnectionStatusAfterDisconnect(): houdt "No connection for X
    // minutes" actueel ook tijdens een lange scan-cooldown-pauze, wanneer er
    // even geen scan/connect-event is om de tekst aan op te hangen.
    private var statusTickerJob: Job? = null

    // 01/08/2026 (editor, na live-test — outcome=RECONNECT_FAILED, sensor
    // wees de AppID-handshake steeds opnieuw af) — mirror van Juggluco's
    // eigen `unusedSensor`-veld (AirGattCallback.java regel 484:
    // `unusedSensor=false;` vlak vóór de disconnect() in de
    // afwijzings-tak van onChar22Changed). `unusedSensor` in dit bestand
    // wordt bij ELKE herverbinding opnieuw uit de kalibratiegeschiedenis
    // afgeleid (CareSensAirNative.getLastSequence) — die geschiedenis
    // blijft leeg zolang er nooit succesvol data ontvangen is, dus zonder
    // deze vlag zou de app na een afwijzing steeds WEER hetzelfde
    // (kennelijk verkeerde) unusedSensor=true blijven sturen: een
    // oneindige lus van dezelfde afwijzing. Dit veld overschrijft dat:
    // ná een afwijzing wordt unusedSensor voor de rest van deze
    // koppelsessie geforceerd op false gehouden (gereset in disconnect()).
    private var appIdRejectedOnce: Boolean = false

    // 01/08/2026 (editor, na live-test — de app probeerde na een
    // AppID-afwijzing wél opnieuw te verbinden via `gatt.connect()`, maar
    // dat bleek een no-op: zodra Android een BluetoothGatt-cliënt na een
    // disconnect() "unregisterApp()"'t (zichtbaar in logcat, gebeurt
    // automatisch bij autoConnect=false), doet een hernieuwde
    // `gatt.connect()`-aanroep op datzelfde object niets meer.
    //
    // 02/08/2026 (editor, na een korte, WEER teruggedraaide `autoConnect=
    // true`-poging — zie de klasse-kdoc hierboven) — deze bevinding blijft
    // dus gewoon geldig: elke herverbindpoging gaat via een VERSE scan +
    // `connectGatt(device, false, ...)` op een NIEUW GattCallback-object
    // (zie startConnectScan()/scheduleScanAttempt()), nooit via
    // `gatt.connect()`-hergebruik op een oud object. Het "aantal mislukte
    // scan-cycli" dat hier ooit bijgehouden werd (`reconnectAttempts`) is
    // niet teruggezet — de status wordt sinds ronde 23 tijdgebaseerd
    // bepaald, zie updateConnectionStatusAfterDisconnect().

    // 02/08/2026 (editor, na live-test met de nieuwe raw-notificatielogging
    // — een 0xC4-aankondiging met numRecords=0 werd genegeerd, waarna de
    // sensor 28s later zelf de verbinding verbrak) — mirror van
    // AirGattCallback.java's `noticedNumberRecords`: Juggluco's
    // `onChar11Changed` schrijft bij de EERSTE 0xC4-aankondiging in een
    // koppelsessie ALTIJD `numberRecords()` (194... nee, 197,1 — "hoeveel
    // nieuwe records staan klaar?"), ONGEACHT het aangekondigde aantal:
    // `if(res==3L){ if(!noticedNumberRecords){ numberRecords(gatt);
    // noticedNumberRecords=true; } return; }`. Zie handleGlucoseData
    // Notification's RecordCountAnnounced-tak hieronder — dat schreef
    // voorheen ALLEEN bij `newRecords > 0`, wat het exacte scenario uit
    // deze test (aankondiging met newRecords=0) een dode eindtoestand
    // maakte i.p.v. het verwachte vervolgverzoek. Gereset in disconnect().
    private var noticedNumberRecords: Boolean = false

    // 02/08/2026 (editor, op verzoek: "wat wel hulpzaam is dat als er
    // wel verbindings problemen zijn dat hij dan bij status weer geeft
    // wat het probleem is: Bv 25 minuten geen verbinding") — apart van
    // AppSettings.careSensAirLastConnectedAtMs (persistent, voor de
    // "Last connected"-rij in de UI) houdt de driver dit ZELF ook
    // synchroon in het geheugen bij, puur om
    // updateConnectionStatusAfterDisconnect() hieronder een exacte "X
    // minuten geen verbinding"-tekst te kunnen opbouwen op het moment dat
    // hij het opgeeft — een DataStore-Flow uitlezen is asynchroon, dat past
    // niet in dat synchrone pad. Reset bij elke nieuwe connect()-sessie.
    private var lastSuccessfulConnectionAtMs: Long? = null

    // 10/08/2026 (editor, RONDE 86 — op verzoek, na live-log-melding: "sinds
    // 22:40 komt de caresens om de 6 minuten" — zie
    // computeReconnectCooldownMs()'s kdoc voor de volledige analyse) — het
    // vaste ankerpunt van deze verbind-sessie's 5-minuten-raster, ÉÉN keer
    // gezet bij de EERSTE geslaagde meting (net als lastSuccessfulConnectionAtMs
    // hierboven, dus ook gereset bij elke nieuwe connect()-sessie). Losstaand
    // van lastSuccessfulConnectionAtMs zelf, dat bij ELKE meting verandert —
    // computeReconnectCooldownMs() heeft juist een stabiel referentiepunt
    // nodig om na een eenmalige vertraging weer terug te kunnen "snappen"
    // naar het oorspronkelijke raster, in plaats van steeds vanaf de laatst
    // ontvangen (mogelijk al verschoven) meting door te rekenen.
    private var cadenceAnchorAtMs: Long? = null

    // 02/08/2026 (editor, na live-test — "de start en einddatum tijd ...
    // wordt nog niet gevuld") — het 0xC0/2-antwoord (dat elapsedSecs draagt,
    // waar sensorStartedAtMs uit afgeleid wordt) komt alleen binnen als
    // REACTIE op een `buildSetAppInfoCommand()`-schrijfactie, en die schreef
    // de handshake tot nu toe ALLEEN bij "eerste keer ooit voor deze sensor"
    // (native kalibratiegeschiedenis nog leeg, `getLastSequence()<=0`) — zie
    // de `firstByte==0xC0 && secondByte==1`-tak hieronder. Voor DEZE fysieke
    // sensor bleek die kalibratiegeschiedenis al vóór dit veld ooit bestond
    // te zijn opgebouwd (uit eerdere testrondes), dus die "eerste keer
    // ooit"-tak sloeg de hele testperiode al over — sensorStartedAtMs is
    // dan ook nooit ergens vastgelegd geweest, niet "gewist" zoals de vorige
    // ronde veronderstelde. Dit veld (eenmalig gevuld in connect(), zie
    // daar) laat de 0xC0/1-tak hieronder de app-info-schrijfactie OOK sturen
    // wanneer we het sensor-startmoment nog niet gecached hebben, LOS van
    // de kalibratiegeschiedenis-staat — dat re-triggert het 0xC0/2-antwoord
    // ongeacht of dit een "eerste keer ooit"-sessie is. Eenmaal gevuld (zie
    // de 0xC0/2-afhandeling) blijft dit false voor de rest van de
    // levensduur van de app (AppSettings.careSensAirSensorStartedAtMs is
    // dan al persistent gezet, dus een volgende app-start leest 'm meteen
    // als bekend).
    private var sensorStartedAtMsUnknown: Boolean = false

    // 01/08/2026 (editor) — de bond-status-ontvanger (koppel-stap 4, zie
    // README-geschiedenis) moest voorheen altijd hetzelfde doen
    // (discoverServices() herhalen). Nu de handshake meerdere stappen kent
    // die elk op een BOND_BONDED-onderbreking kunnen wachten (zowel de
    // AppID- als de app-info-notificatie-stap kunnen een createBond()
    // triggeren), onthoudt dit lambda-veld simpelweg "wat te doen zodra de
    // bond rond is" — ingesteld vlak vóór elke createBond()-aanroep.
    private var pendingAfterBond: ((BluetoothGatt) -> Unit)? = null

    // 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's kdoc: apart
    // veld naast pendingAfterBond hierboven, want dat verwacht een AL
    // BESTAANDE `bluetoothGatt` (het wordt gevuld ná een createBond() MIDDEN
    // in een lopende handshake). De nieuwe pre-connect bond-loss-check (zie
    // startConnectScan()) draait vóórdat connectGatt() ooit is aangeroepen —
    // er is dan nog geen gatt-object om aan door te geven, dus dit lambda
    // heeft geen parameter en roept simpelweg connectGatt() zelf aan.
    // registerBondReceiver()'s onReceive() roept, indien gezet, ALTIJD ook
    // dit veld aan (naast het bestaande pendingAfterBond), ongeacht of
    // bluetoothGatt al bestaat.
    private var pendingAfterBondForConnect: (() -> Unit)? = null

    /** 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's kdoc en
     *  AppSettings.bondLossAutoRecoveryEnabled's kdoc: eenmalig gelezen in
     *  connect(), gebruikt in startConnectScan()'s scan-resultaat vlak vóór
     *  connectGatt(). Blijft false zolang er nog nooit eerder succesvol
     *  verbonden is met dit toestel — zie de voorwaarde daar. */
    private var bondLossAutoRecoveryEnabled: Boolean = false

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

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
        // Ongefilterde scan — zie de vorige-versie-kdoc-geschiedenis in
        // README: een ScanFilter op een servicetype matcht deze sensor
        // nooit (adverteert kennelijk geen matchbare service-UUID in de
        // ruwe advertentie).
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
        // 02/08/2026 (editor) — zie noticedNumberRecords's kdoc bij het
        // klasse-veld: mirror van Juggluco's `noticedNumberRecords`, per
        // koppelsessie geldig (reset bij elke nieuwe connect()-aanroep).
        noticedNumberRecords = false
        lastSuccessfulConnectionAtMs = null
        cadenceAnchorAtMs = null
        val settings = AppSettings(context)
        appSettings = settings
        val scope = CoroutineScope(SupervisorJob())
        driverScope = scope
        val appCtx = context.applicationContext
        appContext = appCtx
        // 02/08/2026 (editor) — zie sensorStartedAtMsUnknown's kdoc bij het
        // klasse-veld: eenmalige, asynchrone cache-lezing bij het opzetten
        // van deze sessie — ruim op tijd klaar vóór de handshake de
        // 0xC0/1-tak bereikt (die pas na MTU-onderhandeling, service-
        // discovery, serienummer/sw-revisie-lezing en de AppID-handshake
        // komt, samen typisch al enkele honderden ms), dus geen suspend-call
        // nodig op de synchrone BLE-callback-thread zelf.
        scope.launch {
            sensorStartedAtMsUnknown = settings.careSensAirSensorStartedAtMs(slot).first() == null
            // 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's
            // kdoc: alleen "aan" als de gebruiker de schakelaar heeft
            // aangezet ÉN er al eerder succesvol verbonden is met dit
            // toestel (anders is een verse BOND_NONE gewoon normaal, geen
            // "verlies").
            bondLossAutoRecoveryEnabled = settings.isBondLossAutoRecoveryEnabledOnce() &&
                settings.getCareSensAirLastConnectedAtMsOnce(slot) != null
        }

        // 01/08/2026 (editor) — de kalibratiebibliotheek moet vóór de
        // eerste meting geladen zijn; dlopen/dlsym zijn goedkoop genoeg om
        // gewoon bij elke connect() (opnieuw) te proberen — nativeLoadCalculationLibrary()
        // is idempotent (zie caresensair_bridge.cpp: als al geladen, meteen
        // true terug).
        if (!CareSensAirNative.loadCalculationLibrary(appCtx)) {
            _connectionState.value = ConnectionState.Error(
                "Couldn't load the CareSens Air calibration library (libCALCULATION.so). " +
                    "This is a bundled file inside the app itself — if this keeps happening, " +
                    "the app installation may be corrupt."
            )
            return
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
        // 02/08/2026 (editor) — zie kdoc bij updateConnectionStatusAfterDisconnect():
        // houdt de "No connection for X minutes"-statustekst actueel ook
        // tijdens een lange scan-cooldown-pauze, wanneer er even geen
        // scan/connect-event is om de tekst aan op te hangen.
        statusTickerJob?.cancel()
        statusTickerJob = scope.launch {
            while (true) {
                delay(60_000L)
                if (_connectionState.value !is ConnectionState.Connected) {
                    updateConnectionStatusAfterDisconnect(deviceAddress)
                }
            }
        }
    }

    /**
     * 02/08/2026 (editor, herstel na de teruggedraaide `autoConnect=true`-
     * poging — zie de uitgebreide klasse-kdoc hierboven voor de volledige
     * Juggluco-decompile-bevinding) — het ENE gedeelde pad dat een
     * scanpoging inplant, aangeroepen vanuit `connect()` (meteen, geen
     * cooldown), vanuit `STATE_DISCONNECTED` (na `MIN_SCAN_COOLDOWN_MS`),
     * en vanuit `startConnectScan()`'s eigen scan-timeout (idem). Past
     * ALTIJD eerst `ScanRateLimiter` toe (Juggluco's 5-scans-per-31s-
     * plafond) bovenop de meegegeven `cooldownMs` (Juggluco's eigen
     * standaard-pauze van 60s tussen scanpogingen) — beide moeten toestaan
     * vóórdat er daadwerkelijk gescand wordt.
     */
    private fun scheduleScanAttempt(
        scope: CoroutineScope,
        appCtx: Context,
        deviceAddress: String,
        settings: AppSettings,
        cooldownMs: Long
    ) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            awaitCooldown(appCtx, cooldownMs)
            // 10/08/2026 (editor, RONDE 83) — zie ScanRateLimiter.kt's kdoc:
            // deze slot krijgt voorrang op het gedeelde scan-budget zodra
            // hij daadwerkelijk de AAPS-zendende slot is, zodat de andere
            // slot's scanverkeer deze reconnect-cadans nooit meer kan
            // vertragen. Verse lezing bij ELKE scanpoging (niet één keer
            // bij connect() gecached) — dekt ook het geval dat de gebruiker
            // de AAPS-bron tussentijds naar de andere slot omzet.
            val currentAapsSlot = settings.aapsActiveSlot.first()
            // 13/08/2026 (editor, RONDE 103) — zie AapsSlotSchedule.kt's
            // klasse-kdoc bij [publishAapsActiveSlot]: cachet deze toch al
            // verse lezing zodat computeReconnectCooldownMs() hierboven (die
            // zelf niet suspend is) 'm synchroon kan raadplegen.
            AapsSlotSchedule.publishAapsActiveSlot(currentAapsSlot)
            val isPriority = currentAapsSlot == slot
            // 12/08/2026 (editor, RONDE 100 — op verzoek: "het slot wat naar
            // aaps zend ... altijd de voorkeur heeft en als dat tot gevolg
            // heeft dat het andere slot zo nu en dan een meting mist dan is
            // dat maar zo") — zie AapsSlotSchedule.kt's klasse-kdoc. Alleen
            // de NIET-priority-slot wijkt hier ooit uit; de AAPS-slot vraagt
            // dit nooit op, dus wacht nooit op de andere slot. Bij maar 1
            // actieve slot heeft AapsSlotSchedule niets van de ander
            // gepubliceerd (of allang verlopen), dus levert dit altijd 0 op.
            //
            // 13/08/2026 (editor, RONDE 101) — sinds deze ronde is dit nog
            // maar het LAATSTE vangnet: computeReconnectCooldownMs()
            // hierboven (waar `cooldownMs` vandaan komt) schuift CareSens
            // Air's scan-DOEL zelf al proactief weg van de andere slot's
            // rasterpunt (zie dat bestand's RONDE-101-kdoc), dus deze
            // reactieve check zou er in de praktijk nog maar zelden aan te
            // pas hoeven komen.
            if (!isPriority) {
                val guardDelay = AapsSlotSchedule.guardDelayMs(slot, System.currentTimeMillis())
                if (guardDelay > 0) {
                    DiagnosticFileLogger.log(
                        "scheduleScanAttempt: AAPS-slot verwacht binnenkort een meting -> wijk ${guardDelay}ms uit"
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

    /**
     * 04/08/2026 (editor, RONDE 36 — op verzoek, "wat doet juggluco dan
     * anders" gevolgd door "implementeer dat") — vervangt de vroegere kale
     * `delay(cooldownMs)` hierboven. Zie PredictiveReconnectAlarm.kt's kdoc
     * voor de volledige achtergrond: een coroutine-`delay()` heeft geen
     * enkele garantie om op tijd af te gaan zodra Android's Doze-
     * onderhoudsvensters gaan schuiven — precies de vermoedelijke
     * verklaring voor het trimodale vertragingspatroon uit ronde 35. Deze
     * functie plant in plaats daarvan een `AlarmManager.setExactAndAllowWhileIdle()`-
     * wekker (Doze-doorbrekend, OS-gegarandeerd) en wacht op het signaal
     * daarvan, met een defensieve `withTimeoutOrNull()`-bovengrens
     * (cooldownMs + 30s) voor het zeldzame geval dat de wekker om wat voor
     * reden dan ook nooit afgaat — dan is het resultaat hooguit gelijk aan
     * de oude situatie, nooit slechter.
     *
     * `cooldownMs<=0` (het pad vanuit `connect()`) slaat de wekker helemaal
     * over — meteen doorgaan.
     */
    private suspend fun awaitCooldown(appCtx: Context, cooldownMs: Long) {
        if (cooldownMs <= 0) return
        val deferred = PredictiveReconnectAlarm.schedule(appCtx, cooldownMs)
        try {
            withTimeoutOrNull(cooldownMs + 30_000L) { deferred.await() }
        } finally {
            PredictiveReconnectAlarm.cancel(appCtx)
        }
    }

    /**
     * 03/08/2026 (editor, RONDE 31 — op verzoek van/voorstel door de
     * gebruiker, na v78-logcat-analyse: "ik zit zelf te denken of we het 5
     * minuten interval... niet kunnen gebruiken door bv 4 of 4,5 minuten na
     * de laatste update pas weer een signaal te sturen en dat te herhalen
     * tot er een nieuwe waarde binnenkomt") — vervangt de vlakke
     * `MIN_SCAN_COOLDOWN_MS` (60s) ná ELKE disconnect door een voorspelling:
     * als we weten wanneer de laatste geslaagde meting binnenkwam
     * (`lastSuccessfulConnectionAtMs`), plan de eerstvolgende scanpoging dan
     * pas `PREDICTIVE_RECONNECT_LEAD_MS` (4,5 min) daarna, in plaats van
     * meteen (na maar 60s) tegen een sensor te botsen die toch nog niets
     * nieuws heeft. Zodra die voorspelde tijd al voorbij is (of er nog
     * helemaal geen geslaagde meting was deze sessie) valt dit terug op de
     * gewone `MIN_SCAN_COOLDOWN_MS` — dat behoudt het bestaande "elke 60s
     * opnieuw proberen totdat de sensor daadwerkelijk reageert"-gedrag voor
     * het laatste stukje, precies zoals de gebruiker zelf voorstelde
     * ("...en dat te herhalen tot er een nieuwe waarde binnenkomt").
     *
     * 10/08/2026 (editor, RONDE 86 — op verzoek, na live-log-melding: "sinds
     * 22:40 komt de caresens om de 6 minuten") — de formule hieronder rekende
     * tot nu toe simpelweg door vanaf `lastReadingAtMs` ("volgende poging =
     * laatste meting + leadtime"). Dat is een KETTING zonder anker: als één
     * cyclus vertraagd binnenkomt (bijvoorbeeld door een scanbotsing met de
     * andere slot, zie ScanRateLimiter.kt), verschuift de HELE cadans vanaf
     * dat moment permanent mee, want de volgende voorspelling rekent gewoon
     * weer vanaf die (al verschoven) meting door — niets trekt 'm terug naar
     * het oorspronkelijke 5-minuten-raster. Live bevestigd: een botsing met
     * de Dexcom-scan om 22:48:18.6-18.7 (42ms uit elkaar, ná ronde 85's
     * marge-verruiming voor Dexcom, waardoor Dexcom's scans nu bijna altijd
     * raak zijn en dus vaker daadwerkelijk een actieve scan hebben lopen om
     * mee te botsen) verschoof CareSens Air's cadans in één klap van ~5:00
     * naar een kaarsrecht, maar 60s te laat, ~6:00 — en bleef daar staan.
     *
     * Fix: reken niet vanaf `lastReadingAtMs` zelf, maar vanaf een vast
     * ankerpunt (`cadenceAnchorAtMs`, gezet bij de eerste meting van deze
     * sessie) en "snap" `lastReadingAtMs` naar het dichtstbijzijnde
     * veelvoud van `SENSOR_PERIOD_MS` sinds dat anker (via afronden, niet
     * afkappen). Een eenmalige vertraging van bijvoorbeeld 60s wordt zo
     * NIET meegenomen naar de volgende voorspelling — die mikt gewoon weer
     * op het oorspronkelijke rasterpunt, en de eerstvolgende scanpoging
     * wordt dus automatisch iets vroeger gepland om dat verschil in te
     * lopen. Bij een gemiste hele cyclus (rond de 10 minuten) rondt de
     * afronding naar het volgende rasterpunt i.p.v. het vorige — dat is
     * precies gewenst, want dan hoort de meting echt bij die latere plek in
     * het raster.
     *
     * 28/08/2026 (editor, RONDE 155, KRITIEKE FIX — het hierboven beschreven
     * "afronden" bleek zelf de bug: zie DexcomG7Driver.kt's identieke kdoc
     * bij dezelfde functienaam voor het volledige, met een meerdere-uren-log
     * bevestigde bewijs) — `Math.round` bleek fragiel zodra een cyclus
     * CONSISTENT net over de helft van een vak (2,5 min) te laat binnenkwam
     * (bijvoorbeeld door BLE-scan-overhead, niet per se een botsing met de
     * andere slot) — dan rondde elke opeenvolgende cyclus opnieuw naar het
     * volgende rasterpunt, en bleef de cadans permanent op het dubbele
     * (10 min) steken, i.p.v. zich te herstellen naar 5. Vervangen door
     * `Math.floor`: kent een late meting toe aan het LAATST al verstreken
     * vak i.p.v. het dichtstbijzijnde — de "gemiste hele cyclus"-situatie
     * hierboven blijft daarbij nog steeds correct afgehandeld (zodra een
     * vertraging een VOL vak overschrijdt, schuift `floor` vanzelf ook door
     * naar het volgende rasterpunt), alleen niet meer bij een vertraging van
     * net iets meer dan de helft.
     */
    private fun computeReconnectCooldownMs(): Long {
        // 04/08/2026 (editor, RONDE 38) — de ronde-36-noodrem
        // (`AlwaysScanMode`) is hier weer verwijderd: ronde 37's
        // LOW_LATENCY+MATCH_MODE_AGGRESSIVE-scanfix bracht de tax al terug
        // naar een strakke ~26-30s-baseline (zie README.md), dus er was
        // geen ruimte meer om nog iets te winnen met "meteen doorscannen
        // zonder cooldown" — alleen onnodig batterijverbruik zonder
        // voordeel.
        val lastReadingAtMs = lastSuccessfulConnectionAtMs
        if (lastReadingAtMs == null) {
            // 04/08/2026 (editor, RONDE 34 — diagnostische logregel, op
            // verzoek na een live-logcat-test die liet zien dat een cyclus
            // zonder nieuwe meting (newRecords=0) de eerstvolgende
            // scanpoging terugval-cooldown (MIN_SCAN_COOLDOWN_MS) gaf i.p.v.
            // de voorspelde ~3,5 min — dit was tot nu toe alleen indirect af
            // te leiden uit de tijdstippen tussen twee "Scan-record voor"-
            // regels, nooit direct zichtbaar. Deze en de logregel hieronder
            // maken dat voortaan rechtstreeks afleesbaar.
            DiagnosticFileLogger.log(
                "computeReconnectCooldownMs: geen eerdere geslaagde meting deze sessie -> fallback MIN_SCAN_COOLDOWN_MS=${MIN_SCAN_COOLDOWN_MS}ms"
            )
            return MIN_SCAN_COOLDOWN_MS
        }
        val anchor = cadenceAnchorAtMs ?: lastReadingAtMs
        val periodsElapsed = Math.floor((lastReadingAtMs - anchor) / SENSOR_PERIOD_MS.toDouble()).toLong()
        val gridReadingAtMs = anchor + periodsElapsed * SENSOR_PERIOD_MS
        val predictedNextReadingAtMs = gridReadingAtMs + SENSOR_PERIOD_MS
        // 12/08/2026 (editor, RONDE 100) — onvoorwaardelijk publiceren, zie
        // AapsSlotSchedule.kt's klasse-kdoc: de niet-priority-kant van deze
        // check gebeurt pas in scheduleScanAttempt(), hier niet nodig.
        AapsSlotSchedule.publish(slot, predictedNextReadingAtMs)

        // 13/08/2026 (editor, RONDE 101 — op voorstel, na live-log-bewijs van
        // 5 Dexcom-missers die elk <5s vóór Dexcom's verwachte metingstijd
        // een CareSens-scan hadden: "is het dan geen optie om ... de timing
        // van de caresens zo te verschuiven dat hij minimaal 1 minuut voor
        // of na de door de transmitter bepaalde update van de dexcom valt
        // ... de caresens [kan] worden uitgevraagd wanneer je dat wilt en
        // ... de dexcom is alleen aan het zenden als de transmitter zich
        // zelf opent") — precies dat verschil (CareSens: wanneer WIJ willen,
        // Dexcom: wanneer de transmitter wil) is waarom alleen HIER, niet in
        // DexcomG6Driver.kt, geschoven wordt. Belangrijk: dit schuift alleen
        // het SCAN-DOEL (wanneer we gaan proberen op te halen), niet
        // [predictedNextReadingAtMs]/[gridReadingAtMs] zelf — die blijven de
        // waarheid over wanneer de sensor ECHT een nieuwe meting heeft, en
        // blijven dus ook ongewijzigd de basis voor het zelf-corrigerende
        // anker (Ronde 86) op de VOLGENDE cyclus. We mogen best wat later
        // ophalen dan gepland: het apparaat bewaart de laatste meting tot de
        // volgende (~SENSOR_PERIOD_MS later) 'm overschrijft, dus zolang de
        // verschuiving ruim binnen die marge blijft (zie de
        // veiligheidscheck hieronder) gaat er niets verloren. Nooit VROEGER
        // schuiven dan het natuurlijke doel — dat zou het pas in Ronde 100
        // getunede SCAN_START_MARGIN_MS-gedrag (voorkomen van premature
        // newRecords=0-missers) weer ondermijnen.
        //
        // 13/08/2026 (editor, RONDE 103 — op controlevraag: "als de caresens
        // de aaps sensor wordt dan wordt [de verschuiving] ook uitgeschakeld
        // en krijgt caresens wel altijd de voorrang ... want er wordt toch
        // niet op gedoseerd") — `!AapsSlotSchedule.isPrioritySlot(slot)`
        // hieronder: zodra CareSens Air ZELF de AAPS-slot is, slaat deze hele
        // verschuiving over. Dexcom's eigen (ongewijzigde, want niet door de
        // app te sturen) cadans kan dan gewoon af en toe tegen CareSens Air's
        // scan botsen zonder gevolgen — Dexcom's REACTIEVE guard
        // ([AapsSlotSchedule.guardDelayMs], `!isPriority`-tak in
        // DexcomG6Driver.kt's scheduleScanAttempt()) wijkt in dat geval juist
        // WEL voor CareSens Air, dus CareSens Air krijgt in die situatie
        // zelfs dubbele bescherming (geen eigen verschuiving nodig, én
        // Dexcom wijkt zelf al reactief).
        var scanTargetAtMs = predictedNextReadingAtMs - SCAN_START_MARGIN_MS
        val otherPredicted = if (AapsSlotSchedule.isPrioritySlot(slot)) {
            null
        } else {
            AapsSlotSchedule.otherSlotPredictedReadingAtMs(slot)
        }
        if (otherPredicted != null) {
            val periodsFromOther = Math.round((scanTargetAtMs - otherPredicted) / SENSOR_PERIOD_MS.toDouble())
            val nearestOtherGridMs = otherPredicted + periodsFromOther * SENSOR_PERIOD_MS
            val distanceMs = scanTargetAtMs - nearestOtherGridMs
            if (Math.abs(distanceMs) < AapsSlotSchedule.MIN_SEPARATION_MS) {
                val shiftedTargetAtMs = nearestOtherGridMs + AapsSlotSchedule.MIN_SEPARATION_MS
                // Veiligheidsmarge: nooit voorbij de eigen volgende meting
                // schuiven (anders overschrijft het apparaat de meting die
                // we nog niet hebben opgehaald).
                val latestSafeTargetAtMs = predictedNextReadingAtMs + SENSOR_PERIOD_MS - SCAN_START_MARGIN_MS
                if (shiftedTargetAtMs in scanTargetAtMs..latestSafeTargetAtMs) {
                    DiagnosticFileLogger.log(
                        "computeReconnectCooldownMs: scanTarget=$scanTargetAtMs ligt binnen ${AapsSlotSchedule.MIN_SEPARATION_MS}ms van de andere slot's raster ($nearestOtherGridMs) -> verschoven naar $shiftedTargetAtMs"
                    )
                    scanTargetAtMs = shiftedTargetAtMs
                }
            }
        }

        val remainingMs = scanTargetAtMs - System.currentTimeMillis()
        val result = if (remainingMs > MIN_SCAN_COOLDOWN_MS) remainingMs else MIN_SCAN_COOLDOWN_MS
        DiagnosticFileLogger.log(
            "computeReconnectCooldownMs: lastReadingAt=$lastReadingAtMs anchor=$anchor gridReadingAt=$gridReadingAtMs remainingMs=$remainingMs -> cooldownMs=$result" +
                (if (remainingMs <= MIN_SCAN_COOLDOWN_MS) " (fallback, voorspelde tijd al voorbij)" else " (voorspeld, zelf-corrigerend)")
        )
        return result
    }

    /**
     * 01/08/2026 (editor, na live-test — twee losse pogingen lieten
     * `connectGatt()` "koud" op een opgeslagen adres 30+ seconden hangen
     * zonder ook maar één verbindingsevent, status 147, met
     * `autoConnect=false`) — vergeleken met Juggluco's eigen
     * `SuperGattCallback`/`SensorBluetooth`: die scant destijds ook actief
     * vóór elke verbinding. Scant hier dus ALTIJD eerst tot het opgegeven
     * adres langskomt, en verbindt dan pas — i.p.v. te gokken op het
     * moment van de connectGatt()-aanroep zelf.
     *
     * 02/08/2026 (editor, hersteld ná de teruggedraaide `autoConnect=true`-
     * poging) — deze functie bestond identiek vóór die poging; enige
     * wijziging nu is dat een mislukte/verlopen scanpoging niet meer
     * rechtstreeks een nieuwe scan start, maar via `scheduleScanAttempt()`
     * loopt (die eerst `MIN_SCAN_COOLDOWN_MS` en `ScanRateLimiter`
     * respecteert — zie de klasse-kdoc voor waarom dat precies het verschil
     * met Juggluco's eigen, wél werkende gedrag bleek te zijn).
     *
     * 02/08/2026 (editor, RONDE 26 — na bevestiging uit `w2.run()`, geval 8,
     * dat Juggluco's eigen scan NOOIT op een korte timer gestopt-en-
     * herstart wordt, zie SCAN_REARM_INTERVAL_MS's kdoc voor het volledige
     * bewijs) — de vorige "geef na SCAN_ATTEMPT_TIMEOUT_MS op, stop de
     * scan, wacht MIN_SCAN_COOLDOWN_MS, probeer opnieuw"-lus is VERVANGEN
     * door `scheduleRearm()`: de scan blijft gewoon actief doorlopen totdat
     * het apparaat gevonden wordt (of totdat `disconnect()`/`userStopped`
     * 'm expliciet afbreekt) — geen dode periode zonder scan meer terwijl
     * we nog op de sensor wachten. `MIN_SCAN_COOLDOWN_MS` blijft wel gelden
     * op de twee plekken waar Juggluco 'm ZELF ook toepast: ná een echte
     * `onScanFailed()` en ná een GATT-disconnect, vóórdat de VOLGENDE
     * scanpoging start — dat is ongewijzigd.
     *
     * 02/08/2026 (editor, RONDE 27 — na een test met v74 (continue scan +
     * 390s-veiligheidsnet) die LATER, niet beter uitpakte: de xDrip+-log
     * toonde een aaneengesloten black-out van circa 30 minuten (23:22-
     * 23:52), zichtbaar aan een oplopende-dan-weer-afbouwende
     * vertragingsreeks — 29m, 24m, 19m, 14m, 9m, 4m, 2m — precies het
     * patroon van een lange onderbreking gevolgd door het in één keer
     * inhalen van een opgestapelde achterstand. Dat de continue-scanfix uit
     * ronde 26 dit niet oploste wijst erop dat het probleem NIET zit in
     * hoe vaak/hoe lang we een scan starten (dat mechanisme is nu al zo
     * geduldig als Juggluco's eigen bewezen gedrag), maar mogelijk in HOE
     * die scan zelf is aangevraagd. Al eerder genoteerd, nooit geverifieerd
     * (zie de klasse-kdoc's Juggluco-decompile-bevindingen): Juggluco roept
     * in zijn eigen `ScanSettings.Builder` NERGENS `setScanMode()` aan —
     * alleen `setReportDelay(0)` — en blijft dus op Android's eigen
     * standaardwaarde (`SCAN_MODE_LOW_POWER`) staan. Deze functie vroeg tot
     * nu toe expliciet `SCAN_MODE_LOW_LATENCY` aan, wat op de achtergrond
     * juist STRENGER door Android beperkt/onderdrukt kan worden (een
     * "agressieve" scanmodus is bedoeld voor een actief zichtbare app op de
     * voorgrond) — nog niet met zekerheid bevestigd als DE oorzaak, maar
     * wel het enige nog resterende, uit de decompile bekende verschil met
     * Juggluco's scanaanroep zelf. Hier verwijderd (dus terug naar Android's
     * eigen standaard, exact zoals Juggluco) — `startPairing()` hierboven
     * (het scherm staat dan aan, gebruiker kijkt actief mee) blijft bewust
     * WEL `SCAN_MODE_LOW_LATENCY` gebruiken, dat is een ander scenario.
     *
     * 04/08/2026 (editor, RONDE 37 — op verzoek, na het decompilen van de
     * ECHTE officiële CareSens Air-app zelf (com.isens.csair v1.2.14,
     * door de gebruiker geüpload) — dit is de eerste keer dat we niet
     * Juggluco (een derde-partij-app die de CareSens Air/Sibionics-sensor
     * NIET officieel ondersteunt) maar de fabrikants-app zelf konden
     * vergelijken. `BleService.l()` (de daadwerkelijke scan-start) blijkt
     * daar `new ScanSettings.Builder().setScanMode(2).setMatchMode(1).
     * setReportDelay(0).build()` te gebruiken — `setScanMode(2)` =
     * `SCAN_MODE_LOW_LATENCY`, `setMatchMode(1)` = `MATCH_MODE_AGGRESSIVE`
     * — op VRIJWEL ALLE toestellen (alleen Xiaomi krijgt daar
     * `SCAN_MODE_BALANCED`, nooit `LOW_POWER`). Bovendien: in hun eigen
     * `onConnectionStateChange()` (klasse `cd.y0`, de bevestigde CGM-GATT-
     * callback) staat bij een gewone sensor-initiated disconnect GEEN
     * ingebouwde wachttijd vóór de volgende scanpoging — vergelijkbaar met
     * Juggluco's eigen standaardpad (zie de klasse-kdoc's ronde-36-sectie),
     * maar Juggluco blijft daarbij wél op `LOW_POWER` scannen. De
     * fabrikants-app combineert dus BEIDE: geen bewuste sleep ÉN geen
     * duty-cycled scanmodus — dat tweede stuk hadden we nog nooit
     * getest (ronde 27's `LOW_LATENCY`-experiment was VOOR de
     * tax-meet-instrumentatie van ronde 34/35, en ronde-36's "Always
     * rescan"-test liet de scanmodus ongewijzigd op `LOW_POWER` staan —
     * z'n identieke trimodale patroon aan de voorspellende cooldown was
     * dus geen bewijs tegen `LOW_LATENCY`, alleen tegen het weglaten van
     * de sleep op zich).
     *
     * Hier dus opnieuw `SCAN_MODE_LOW_LATENCY` + `MATCH_MODE_AGGRESSIVE`
     * aangezet, ditmaal met de bevestigde fabrikants-waarden i.p.v. een
     * gok, en ALLEEN voor dit vangst-venster (ná de bestaande
     * batterijvriendelijke voorspellende cooldown/wekker uit ronde 31/36 —
     * die blijft ongewijzigd, dus de totale scan-aan-tijd per cyclus
     * verandert niet wezenlijk, alleen de duty-cycle-modus TIJDENS het
     * venster zelf).
     */
    private fun startConnectScan(
        scope: CoroutineScope,
        appCtx: Context,
        scanner: BluetoothLeScanner,
        deviceAddress: String,
        settings: AppSettings
    ) {
        // 04/08/2026 (editor, RONDE 37) — zie de kdoc hierboven: mirror van
        // de officiële CareSens Air-app's bevestigde ScanSettings, niet
        // langer van Juggluco's (die de sensor niet eens officieel
        // ondersteunt en zelf ook geen fabrikant is).
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        var resolved = false
        lateinit var callback: ScanCallback
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address != deviceAddress || resolved) return
                resolved = true
                // 03/08/2026 (editor, ronde 29 — puur diagnostisch, GEEN
                // gedragswijziging) — de kdoc bij CSAIR_SERVICE_1/2/3 in
                // CareSensAirGattProtocol.kt dateert van VÓÓR de eigenlijke
                // protocol-correctie (30/07, terwijl de echte proprietaire
                // service-UUID's pas op 01/08 gevonden zijn) en concludeerde
                // "adverteert geen matchbare service-UUID" — die conclusie
                // was gebaseerd op een ScanFilter-test met de OUDE, foutieve
                // aanname (standaard Bluetooth Glucose Profile 0x1808), nooit
                // herhaald met de nu bekende, echte CSAIR_SERVICE_1/2/3.
                //
                // 03/08/2026 (editor, ronde 30 — BEVESTIGD) — deze diagnostische
                // logregel toonde in de praktijk: `serviceUuids=
                // [c4de9a20-5a9d-11e9-8647-d663bd873d93]` — dat IS letterlijk
                // `CSAIR_SERVICE_2`. De sensor adverteert dus WEL degelijk een
                // matchbare service-UUID; de oude conclusie was simpelweg
                // verouderd. Zie `startScan()` verderop in deze functie: nu
                // met een `ScanFilter`, mirror van Juggluco's eigen
                // decompiled scanlogica (bk0/w2, zie de klasse-kdoc), die
                // voor de actieve sensor ook een `ScanFilter` op service-UUID
                // opbouwt — dat maakt hardware-offloaded scannen mogelijk (de
                // Bluetooth-chip zelf blijft matchen, ook als Doze de
                // achtergrond-scan verder zou onderdrukken), de sterkste
                // verklaring tot nu toe voor het bevestigde symptoom (onze
                // eigen 390s-tik vuurde precies op tijd, maar leverde geen
                // scanresultaat — puur software-side scannen bleek hier dus
                // te worden onderdrukt). Deze logregel blijft gewoon staan
                // (nu ter bevestiging/diagnose, niet meer om de hypothese te
                // toetsen).
                runCatching {
                    DiagnosticFileLogger.log(
                        "Scan-record voor $deviceAddress: serviceUuids=${result.scanRecord?.serviceUuids} " +
                            "deviceName=${result.scanRecord?.deviceName} " +
                            "manufacturerSpecificData=${result.scanRecord?.manufacturerSpecificData} " +
                            "bytes=${result.scanRecord?.bytes?.joinToString(",") { (it.toInt() and 0xFF).toString() }}"
                    )
                }
                runCatching { scanner.stopScan(callback) }
                connectScanCallback = null
                // 08/08/2026 (editor, RONDE 57) — de eigenlijke connectGatt()-
                // stap is uitgetrokken naar een lokale functie zodat 'm zowel
                // meteen (normale gang van zaken) als pas ná een geslaagd
                // bond-herstel (zie hieronder) aangeroepen kan worden, zonder
                // duplicatie.
                fun proceedToConnect() {
                    val gatt = result.device.connectGatt(
                        appCtx, false, GattCallback(scope, settings), BluetoothDevice.TRANSPORT_LE
                    )
                    bluetoothGatt = gatt
                    // 01/08/2026 (editor, na live-test — geslaagde handshake +
                    // kalibratieprofiel-overdracht stokte na 2 van de vele
                    // benodigde 0xC2/2-brokken, waarna de SENSOR zelf de
                    // verbinding verbrak (status 19), ~28s later) — mirror van
                    // Juggluco's `SuperGattCallback.setpriority()`, die na ELKE
                    // `connectGatt()` standaard `requestConnectionPriority
                    // (CONNECTION_PRIORITY_HIGH)` aanroept.
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                }
                // 08/08/2026 (editor, RONDE 57) — zie BondLossRecovery.kt's
                // kdoc: alleen relevant als de schakelaar aan staat EN we al
                // eerder succesvol verbonden waren (zie
                // bondLossAutoRecoveryEnabled's zetting in connect()).
                if (bondLossAutoRecoveryEnabled && BondLossRecovery.isBondMissing(result.device)) {
                    pendingAfterBondForConnect = { proceedToConnect() }
                    BondLossRecovery.attemptRecovery(result.device, "CareSensAir")
                    // Terugvalpad: als BOND_BONDED niet binnen 15s terugkomt
                    // (herstel mislukt/duurt te lang), alsnog gewoon
                    // verbinden — niet slechter dan het gedrag vóór deze
                    // functie bestond.
                    scope.launch {
                        delay(15_000L)
                        if (pendingAfterBondForConnect != null) {
                            pendingAfterBondForConnect = null
                            proceedToConnect()
                        }
                    }
                } else {
                    proceedToConnect()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (resolved) return
                resolved = true
                connectScanCallback = null
                _connectionState.value = ConnectionState.Error("Scanning for the sensor failed (code $errorCode).")
                // 03/08/2026 (editor, RONDE 31) — was een vlakke
                // MIN_SCAN_COOLDOWN_MS; zie computeReconnectCooldownMs()'s
                // kdoc voor de aanleiding (voorspellende, aan de laatste
                // geslaagde meting verankerde pauze i.p.v. te vroeg opnieuw
                // botsen tegen een sensor die nog niets nieuws heeft).
                scheduleScanAttempt(scope, appCtx, deviceAddress, settings, cooldownMs = computeReconnectCooldownMs())
            }
        }
        connectScanCallback = callback
        // 03/08/2026 (editor, ronde 30) — ScanFilter op alle drie bekende
        // CSAIR_SERVICE-UUID's (CareSensAirGattProtocol.kt) i.p.v. de
        // eerdere `emptyList()` — zie de kdoc bij `onScanResult` hierboven
        // voor het live-bevestigde bewijs (`serviceUuids=
        // [c4de9a20-...]` == CSAIR_SERVICE_2) dat deze sensor wél degelijk
        // een matchbare service-UUID adverteert. Alle drie meegeven (niet
        // alleen de bevestigde SERVICE_2) is een goedkope extra
        // zekerheidsmarge: `startScan(filters, ...)` matcht een apparaat
        // zodra ÉÉN filter in de lijst raak is, dus filters die toevallig
        // nooit matchen (bv. als een andere firmware-variant een van de
        // andere twee adverteert) doen geen kwaad.
        val scanFilters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CSAIR_SERVICE_1)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CSAIR_SERVICE_2)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CSAIR_SERVICE_3)).build()
        )
        runCatching { scanner.startScan(scanFilters, scanSettings, callback) }
            .onFailure {
                connectScanCallback = null
                _connectionState.value = ConnectionState.Error("Couldn't start scanning: ${it.message}")
            }
        scheduleRearm(scope, appCtx, scanner, callback, deviceAddress, settings) { resolved }
    }

    /**
     * 02/08/2026 (editor, RONDE 26) — mirror van `w2.run()` geval 8's eigen
     * herplanning (`Applic.t.schedule(v0_10.e, 390000, MILLISECONDS)`):
     * NIET "geef op en stop de scan", maar een self-healing veiligheidsnet
     * dat, als de sensor na `SCAN_REARM_INTERVAL_MS` nog steeds niet
     * gevonden is, de scan ververst (stop + herstart via
     * `scheduleScanAttempt()`, met `ScanRateLimiter` ertussen) voor het
     * geval Android de langlopende scan ondertussen stil heeft beëindigd —
     * en zichzelf daarna opnieuw inplant zolang er nog geen resultaat is.
     * Wordt gestopt zodra `resolved` waar is (apparaat gevonden) of
     * `userStopped`/`disconnect()` de sessie afbreekt.
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

    /**
     * 02/08/2026 (editor) — puur STATUSWEERGAVE, losgekoppeld van de
     * daadwerkelijke herverbind-actie (die loopt via scheduleScanAttempt()/
     * startConnectScan()): laat zien of we nog "Connecting…" zijn (nooit
     * een geslaagde verbinding deze sessie), nog gewoon "Connected" blijven
     * staan (routinematige herverbinding na een eerder succes, zie de
     * conditie hieronder), of — pas ná RECONNECT_STATUS_WARNING_MINUTES
     * minuten zonder succes — een concrete "No connection for X minutes"-
     * melding. Wordt aangeroepen vanuit STATE_DISCONNECTED, vanuit een
     * verlopen scan-timeout (startConnectScan()), én vanuit de periodieke
     * statusTickerJob (zie connect()), dus deze tekst blijft actueel ook
     * tijdens een lange scan-cooldown-pauze waarin er even geen nieuw
     * event is om 'm aan op te hangen.
     */
    private fun updateConnectionStatusAfterDisconnect(deviceAddress: String) {
        val staleSince = lastSuccessfulConnectionAtMs
        if (staleSince != null) {
            val minutesSince = (System.currentTimeMillis() - staleSince) / 60_000L
            if (minutesSince >= RECONNECT_STATUS_WARNING_MINUTES) {
                _connectionState.value = ConnectionState.Error(
                    "No connection for $minutesSince minute${if (minutesSince == 1L) "" else "s"} " +
                        "(still trying). Make sure the sensor is nearby, awake, and not already " +
                        "connected to another phone or app (most CGM sensors only allow one " +
                        "active connection at a time)."
                )
                return
            }
        }
        // 02/08/2026 (editor, op verzoek: "als hij 1 maal connected is
        // geweest dat hij dan connected moet blijven staan ... ook als er
        // op dat moment niet direct een bluetooth verbinding in de lucht
        // is") — CareSens Air verbindt kort, meldt eventueel geen nieuwe
        // data, en hangt zelf weer op — dat is NORMAAL gedrag (de sensor
        // levert toch maar elke ~5 minuten een nieuwe meting), geen
        // storing. Als de vorige status al Connected was, blijft die
        // status gewoon staan tijdens zo'n routinematige herverbinding op
        // de achtergrond — pas de foutmelding hierboven (bij een écht
        // aanhoudend probleem) of een geslaagde nieuwe AppID-handshake
        // (die 'm meteen weer expliciet op Connected zet) wijzigt de
        // zichtbare status nog. Alleen als er nog NOOIT een geslaagde
        // verbinding is geweest, tonen we "Connecting..." zoals voorheen.
        if (_connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Connecting(deviceAddress)
        }
    }

    override fun disconnect() {
        userStopped = true
        // 13/08/2026 (editor, RONDE 102) — zie AapsSlotSchedule.kt's
        // klasse-kdoc bij [clear]: zonder dit blijft een gepubliceerde
        // voorspelling van deze slot bestaan nadat de gebruiker 'm
        // tussentijds stopt, en zou de ANDERE slot (als die de AAPS-slot
        // is en dit de niet-priority-slot betreft) daar via de reactieve
        // guard nog even omheen kunnen blijven wijken. Voor CareSens Air
        // zelf (de proactief-schuivende kant, zie RONDE 101) is dit vooral
        // relevant voor het omgekeerde geval: als DEXCOM stopt terwijl
        // CareSens Air blijft draaien, moet CareSens Air weer gewoon zijn
        // eigen, ongeschoven 5-minuten-ritme aanhouden.
        AapsSlotSchedule.clear(slot)
        statusTickerJob?.cancel()
        statusTickerJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        val pendingScanCallback = connectScanCallback
        val ctxForScanStop = appContext
        if (pendingScanCallback != null && ctxForScanStop != null) {
            runCatching { bluetoothAdapter(ctxForScanStop)?.bluetoothLeScanner?.stopScan(pendingScanCallback) }
        }
        connectScanCallback = null
        driverScope?.cancel()
        driverScope = null
        persistNativeStateAndDestroy()
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        charSerial = null
        charSwRevision = null
        charGlucoseData = null
        charAppInfo = null
        charAppId = null
        sensorSerial = null
        swRevision = null
        pendingAfterBond = null
        pendingAfterBondForConnect = null
        appIdRejectedOnce = false
        noticedNumberRecords = false
        lastSuccessfulConnectionAtMs = null
        cadenceAnchorAtMs = null
        unregisterBondReceiver()
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Bewaar de kalibratiegeschiedenis (zie CareSensAirNative.kt's kdoc)
     *  vóórdat de native status wordt opgeruimd — een volgende connect()
     *  naar DEZELFDE sensor (zelfde serienummer) herstelt 'm weer. */
    private fun persistNativeStateAndDestroy() {
        val handle = nativeStateHandle ?: return
        val serial = sensorSerial
        val ctx = appContext
        if (serial != null && ctx != null) {
            CareSensAirNative.persist(ctx, handle, serial)
        }
        CareSensAirNative.destroyState(handle)
        nativeStateHandle = null
    }

    private fun registerBondReceiver(context: Context, deviceAddress: String) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device?.address != deviceAddress) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    bluetoothGatt?.let { gatt ->
                        pendingAfterBond?.invoke(gatt)
                        pendingAfterBond = null
                    }
                    // 08/08/2026 (editor, RONDE 57) — zie het veld-kdoc
                    // hierboven: onafhankelijk van bluetoothGatt, want dit
                    // draait vóór connectGatt() ooit is aangeroepen.
                    pendingAfterBondForConnect?.invoke()
                    pendingAfterBondForConnect = null
                }
            }
        }
        bondReceiver = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        }
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        val context = appContext ?: return
        runCatching { context.unregisterReceiver(receiver) }
    }

    /**
     * De volledige koppel-handshake, letterlijk gevolgd op Juggluco's
     * AirGattCallback.java — zie CareSensAirGattProtocol.kt voor alle
     * commando/antwoord-bytes, en de klasse-kdoc hierboven voor het overzicht
     * van bewuste vereenvoudigingen.
     */
    private inner class GattCallback(
        private val scope: CoroutineScope,
        private val settings: AppSettings
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 01/08/2026 (editor, na live-test — de sensor brak de
                    // verbinding af vlak na de allereerste notificatie-
                    // descriptor-write, status 19/GATT_CONN_TERMINATE_PEER_USER)
                    // — deze stap ontbrak: Juggluco's eigen AirGattCallback.java
                    // vraagt ALTIJD eerst een grotere ATT-MTU aan (512 bytes,
                    // zie onMtuChanged hieronder) vóórdat er iets anders
                    // gebeurt; discoverServices() volgt daar pas ná een
                    // geslaagde MTU-onderhandeling. Zonder deze stap blijft de
                    // standaard 23-byte ATT-MTU gelden (20 bruikbare bytes per
                    // pakket) — 1-op-1 de ontbrekende stap t.o.v. de
                    // bewezen-werkende volgorde, en de meest waarschijnlijke
                    // verklaring voor de vroegtijdige disconnect.
                    if (!gatt.requestMtu(512)) {
                        _connectionState.value = ConnectionState.Error("Couldn't negotiate the Bluetooth connection.")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 04/08/2026 (editor, RONDE 34) — tot nu toe logde deze
                    // functie het disconnect-moment zelf nergens (alleen de
                    // eerstvolgende "Scan-record voor"-regel was zichtbaar,
                    // dus het exacte moment/de reden van de disconnect moest
                    // steeds indirect worden teruggerekend). status is de
                    // ruwe GATT-statuscode (0=lokaal/normaal, 19=
                    // GATT_CONN_TERMINATE_PEER_USER, 8=GATT_CONN_TIMEOUT,
                    // 133=GATT_ERROR, etc.).
                    DiagnosticFileLogger.log(
                        "onConnectionStateChange: STATE_DISCONNECTED status=$status device=${gatt.device.address}"
                    )
                    // 02/08/2026 (editor) — Juggluco's `AirGattCallback.
                    // onConnectionStateChange()` roept bij ELKE overgang weg
                    // van CONNECTED `resetValues()` aan, wat o.a.
                    // `noticedNumberRecords=false` zet — niet alleen bij een
                    // volledige, door de gebruiker/app geïnitieerde
                    // disconnect(). Onze eigen reset zat voorheen ALLEEN in
                    // connect()/disconnect(), niet op dit fysieke
                    // GATT-niveau — bij een automatische herverbinding (dus
                    // zonder tussenkomst van onze eigen disconnect()) zou de
                    // vlag hierdoor onterecht "waar" kunnen blijven staan
                    // uit een vorige, mislukte sessie, en zou een latere
                    // 0xC4-aankondiging in de
                    // NIEUWE sessie geen vervolgverzoek meer krijgen. Nog
                    // niet bevestigd als oorzaak van deze specifieke test
                    // (dit was de eerste geslaagde aankondiging in dit
                    // procesleven), maar wel een echt gat t.o.v. Juggluco's
                    // bron — hier gedicht.
                    noticedNumberRecords = false
                    if (userStopped) {
                        runCatching { gatt.close() }
                        return
                    }
                    // 02/08/2026 (editor, hersteld ná de teruggedraaide
                    // `autoConnect=true`-poging — zie klasse-kdoc) —
                    // `gatt.connect()`-hergebruik op hetzelfde object werkt
                    // niet betrouwbaar ná een disconnect met
                    // `autoConnect=false` (zie de historische kdoc bij
                    // appIdRejectedOnce's klasse-veld) — dus weer close() +
                    // een VERSE scan-dan-verbind-poging, alleen nu via
                    // scheduleScanAttempt() met Juggluco's
                    // MIN_SCAN_COOLDOWN_MS-pauze in plaats van (bijna)
                    // meteen opnieuw scannen.
                    val address = gatt.device.address
                    val ctx = appContext
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    updateConnectionStatusAfterDisconnect(address)
                    if (ctx != null) {
                        // 03/08/2026 (editor, RONDE 31) — zie
                        // computeReconnectCooldownMs()'s kdoc: dit is het
                        // meest voorkomende herverbind-pad (normale,
                        // sensor-geïnitieerde disconnect na elke ~30s-
                        // verbinding), en dus ook de belangrijkste plek waar
                        // de vlakke 60s-cooldown voorheen herhaaldelijk
                        // "wasted" pogingen veroorzaakte.
                        scheduleScanAttempt(scope, ctx, address, settings, cooldownMs = computeReconnectCooldownMs())
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.discoverServices()) {
                    _connectionState.value = ConnectionState.Error("Couldn't start discovering services.")
                }
            } else {
                _connectionState.value = ConnectionState.Error("Couldn't negotiate the Bluetooth connection (MTU status $status).")
                runCatching { gatt.disconnect() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Discovering services failed (status $status).")
                return
            }
            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    when (characteristic.uuid) {
                        CHAR_SERIAL -> charSerial = characteristic
                        CHAR_SW_REVISION -> charSwRevision = characteristic
                        CHAR_GLUCOSE_DATA -> charGlucoseData = characteristic
                        CHAR_APP_INFO -> charAppInfo = characteristic
                        CHAR_APP_ID -> charAppId = characteristic
                    }
                }
            }
            val serialChar = charSerial
            if (serialChar == null || charSwRevision == null || charGlucoseData == null ||
                charAppInfo == null || charAppId == null
            ) {
                _connectionState.value = ConnectionState.Error(
                    "This doesn't look like a CareSens Air sensor " +
                        "(missing expected Bluetooth characteristics)."
                )
                return
            }
            retry(gatt) { gatt.readCharacteristic(serialChar) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Couldn't read sensor info (status $status).")
                return
            }
            when (characteristic.uuid) {
                CHAR_SERIAL -> {
                    sensorSerial = String(value).trim().trim(' ')
                    val swChar = charSwRevision ?: return
                    retry(gatt) { gatt.readCharacteristic(swChar) }
                }
                CHAR_SW_REVISION -> {
                    swRevision = String(value).trim().trim(' ')
                    onIdentificationComplete(gatt)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        /** Serienummer + sw-revisie zijn nu bekend — zie klasse-kdoc:
         *  alleen swRevision >= "1.5" is geport. */
        private fun onIdentificationComplete(gatt: BluetoothGatt) {
            val serial = sensorSerial
            val sw = swRevision
            if (serial == null || sw == null) return
            if (sw < "1.5") {
                _connectionState.value = ConnectionState.Error(
                    "This CareSens Air sensor reports firmware version $sw, which FCLGlucoLink " +
                        "doesn't support yet (only 1.5 and newer are implemented). Juggluco can " +
                        "still be used with this sensor."
                )
                return
            }
            // Serienummer bekend -> eerdere kalibratiegeschiedenis voor
            // DEZE sensor proberen te herstellen (zie CareSensAirNative.kt).
            val handle = CareSensAirNative.createState()
            nativeStateHandle = handle
            val ctx = appContext
            if (ctx != null) {
                CareSensAirNative.restore(ctx, handle, serial)
            }
            val appIdChar = charAppId ?: return
            // 01/08/2026 (editor) — Juggluco wacht hier zelf ook 100ms
            // (Applic.scheduler.schedule(..., 100, MILLISECONDS)) vóór de
            // eerste notificatie-descriptor-write — exact zo overgenomen
            // i.p.v. meteen, voor volledige gelijkenis met de
            // bewezen-werkende volgorde.
            scope.launch {
                delay(100L)
                retry(gatt) { enableNotification(gatt, appIdChar) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (descriptor.characteristic?.uuid) {
                CHAR_APP_ID -> {
                    // Notificatie op charact22 staat aan -> stuur de
                    // AppID-handshake ("csair").
                    //
                    // 01/08/2026 (editor, na live-test — de sensor wees de
                    // handshake meteen af) — `unusedSensor` (byte 34) moet
                    // 1 zijn zolang er nog GEEN kalibratiegeschiedenis voor
                    // deze sensor bestaat (native lastSequence <= 0, zie
                    // CareSensAirGattProtocol.kt's kdoc bij
                    // buildAppIdHandshakeCommand) — voorheen altijd 0,
                    // waarmee de app ten onrechte een eerdere sessie
                    // claimde en de sensor de verbinding afwees.
                    val c = charAppId ?: return
                    val handle = nativeStateHandle
                    val unusedSensor = !appIdRejectedOnce &&
                        (handle == null || CareSensAirNative.getLastSequence(handle) <= 0)
                    val cmd = buildAppIdHandshakeCommand(unusedSensor)
                    // 01/08/2026 (editor) — diagnostische logregel, bewust
                    // NIET achter een debug-vlag: de vorige twee live-tests
                    // (met/zonder unusedSensor-fix) toonden hetzelfde
                    // afbreekpatroon zonder dat we konden zien WAT de
                    // sensor precies terugstuurde. Deze en de logregel in
                    // handleAppIdNotification hieronder maken dat voor de
                    // volgende test direct zichtbaar in logcat (tag
                    // CareSensAirDriver), i.p.v. weer te moeten gissen.
                    DiagnosticFileLogger.log(
                        "AppID handshake versturen: unusedSensor=$unusedSensor bytes=${cmd.joinToString(",") { (it.toInt() and 0xFF).toString() }}"
                    )
                    retry(gatt) { writeCharacteristicCompat(gatt, c, cmd) }
                }
                CHAR_GLUCOSE_DATA -> {
                    // Notificatie op charact11 staat aan -> nu pas
                    // notificatie op charact21 aanzetten (mirror van
                    // Juggluco's exacte volgorde).
                    val c = charAppInfo ?: return
                    retry(gatt) { enableNotification(gatt, c) }
                }
                CHAR_APP_INFO -> {
                    // Notificatie op charact21 staat aan -> nu pas de
                    // AES-versleutelde serienummer-handshake sturen.
                    val serial = sensorSerial ?: return
                    val c = charAppInfo ?: return
                    val cmd = runCatching { buildAppInfoHandshakeCommand(serial) }.getOrNull()
                    if (cmd == null) {
                        _connectionState.value = ConnectionState.Error("Couldn't build the sensor handshake command.")
                        return
                    }
                    retry(gatt) { writeCharacteristicCompat(gatt, c, cmd) }
                }
            }
        }

        // 02/08/2026 (editor, diagnostisch — na live-test die liet zien dat
        // de kalibratieprofiel-overdracht en het "196,1,0,0"-antwoord op de
        // aankondiging correct binnenkomen, maar het vervolg-verzoek 197,1
        // (nu verzonden dankzij de noticedNumberRecords-fix hierboven)
        // opnieuw resulteert in ~26s stilte en disconnect status 19) — deze
        // override bestond hier niet: we hadden GEEN zichtbaarheid op of een
        // WRITE_TYPE_NO_RESPONSE-schrijfactie door Android's eigen
        // BLE-stack daadwerkelijk als voltooid/verstuurd werd bevestigd, of
        // stil mislukte (bv. een operatie-in-uitvoering-conflict — een
        // bekende Android-BLE-valkuil: er mag maar 1 GATT-operatie
        // tegelijk lopen). Juggluco's eigen `AirGattCallback.
        // onCharacteristicWrite()` gebruikt exact deze callback als eigen
        // synchronisatiepunt (`receiveNotes=status==GATT_SUCCESS`) vóór het
        // de volgende binnenkomende notificatie accepteert — puur
        // diagnostisch overgenomen hier (geen gedragsverandering, geen
        // gating), om voor de volgende test definitief zichtbaar te maken
        // of de 197,1-schrijfactie het apparaat daadwerkelijk bereikte.
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            DiagnosticFileLogger.log(
                "onCharacteristicWrite uuid=${characteristic.uuid} status=$status" +
                    (if (status == BluetoothGatt.GATT_SUCCESS) " (SUCCESS)" else " (FAILED)")
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // 02/08/2026 (editor, na live-test — kalibratieprofiel-overdracht
            // stokt keer op keer na exact 2 0xC2/2-brokken, daarna ~28s
            // stilte, dan disconnect status 19 — ONVERANDERD ondanks de
            // requestConnectionPriority-fix hierboven, wat erop wijst dat de
            // verbindingsinterval niet de echte oorzaak was) — dit was het
            // enige zwakke punt in de bestaande logging: handleAppInfoNotifi
            // cation's `else -> {}`-tak (198/1, 0xC6/1, 0xC6/2, EN elke
            // andere/onverwachte firstByte/secondByte-combinatie) logt
            // helemaal NIETS. Als de sensor iets stuurt dat niet exact
            // matcht met een van de bekende gevallen — bijvoorbeeld een
            // 0xC2/3 die er net iets anders uitziet dan verwacht, of een
            // heel ander berichttype — zou dat hier onopgemerkt
            // verdwijnen, precies in het gat waar nu niets zichtbaars
            // gebeurt. Elke notificatie op ELKE karakteristiek nu eerst
          // onvoorwaardelijk geraw-logd, vóór de bestaande
            // per-karakteristiek afhandeling — dezelfde diagnostische
            // aanpak die in ronde 5 van dit traject een gok overbodig
            // maakte.
            DiagnosticFileLogger.log(
                "onCharacteristicChanged uuid=${characteristic.uuid} bytes=${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}"
            )
            when (characteristic.uuid) {
                CHAR_APP_ID -> handleAppIdNotification(gatt, value)
                CHAR_APP_INFO -> handleAppInfoNotification(gatt, value)
                CHAR_GLUCOSE_DATA -> handleGlucoseDataNotification(gatt, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        private fun handleAppIdNotification(gatt: BluetoothGatt, value: ByteArray) {
            val outcome = parseAppIdResponse(value)
            // 01/08/2026 (editor) — zie de kdoc bij het versturen van dit
            // commando hierboven: dit maakt de exacte afwijzingsreden (en de
            // ruwe bytes, voor het geval parseAppIdResponse zelf ook iets
            // mist) direct zichtbaar in logcat.
            DiagnosticFileLogger.log(
                "AppID response ontvangen: outcome=$outcome bytes=${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}"
            )
            when (outcome) {
                AppIdOutcome.OK -> {
                    // 02/08/2026 (editor) — was ooit: reconnectAttempts
                    // hier terugzetten naar 0, zodat een normale, verwachte
                    // korte-verbinding-dan-zelf-weer-ophangen-cyclus
                    // (CareSens Air's eigen duty-cycle) niet ten onrechte
                    // als een mislukte poging meetelde. Die teller bestaat
                    // niet meer sinds ronde 23 — een geslaagde AppID-
                    // handshake zet de status via de Connected-melding
                    // elders vanzelf weer goed, ongeacht hoe vaak dit al
                    // is misgegaan.
                    val glucoseChar = charGlucoseData ?: return
                    if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                        retry(gatt) { enableNotification(gatt, glucoseChar) }
                    } else {
                        pendingAfterBond = { g -> retry(g) { enableNotification(g, glucoseChar) } }
                        runCatching { gatt.device.createBond() }
                    }
                }
                AppIdOutcome.TOO_SHORT -> {
                    // Onverwacht kort bericht — negeren, kan een ander
                    // soort notificatie zijn die toevallig op deze
                    // karakteristiek binnenkwam.
                }
                else -> {
                    // 01/08/2026 (editor, na live-test — outcome=
                    // RECONNECT_FAILED, sensor bleef dezelfde afwijzing
                    // geven) — mirror van AirGattCallback.java regel 484:
                    // Juggluco zet hier `unusedSensor=false` vóór de
                    // disconnect, zodat de eerstvolgende herverbinding een
                    // ANDER handshake-commando stuurt i.p.v. hetzelfde
                    // (blijkbaar verkeerde) commando te herhalen. Zie
                    // appIdRejectedOnce's kdoc bij het klasse-veld.
                    appIdRejectedOnce = true
                    _connectionState.value = ConnectionState.Error(
                        "CareSens Air rejected the connection (${outcome.name.lowercase().replace('_', ' ')})."
                    )
                    runCatching { gatt.disconnect() }
                }
            }
        }

        private fun handleAppInfoNotification(gatt: BluetoothGatt, value: ByteArray) {
            if (value.size < 2) return
            val firstByte = value[0].toInt() and 0xFF
            val secondByte = value[1].toInt() and 0xFF
            val appInfoChar = charAppInfo
            val glucoseChar = charGlucoseData
            when {
                firstByte == 0xC0 && secondByte == 1 -> {
                    val nowSecs = System.currentTimeMillis() / 1000L
                    val info = parseAppInfoResponse(value, nowSecs) ?: return
                    needsTimeSync = info.needsTimeSync
                    if (info.keyCheckFailed) {
                        _connectionState.value = ConnectionState.Error("CareSens Air key check failed.")
                        runCatching { gatt.disconnect() }
                        return
                    }
                    val handle = nativeStateHandle ?: return
                    val appInfoC = appInfoChar ?: return
                    if (CareSensAirNative.getLastSequence(handle) <= 0 || sensorStartedAtMsUnknown) {
                        // Eerste keer ooit voor deze sensor (kalibratie-
                        // geschiedenis nog leeg) OF we hebben het sensor-
                        // startmoment nog niet gecached -> app-info
                        // (opnieuw) zetten. Zie sensorStartedAtMsUnknown's
                        // kdoc bij het klasse-veld: dat re-triggert het
                        // 0xC0/2-antwoord (elapsedSecs) ongeacht of dit
                        // protocolgezien een "eerste keer ooit"-sessie is —
                        // nodig omdat een sensor waarvan de kalibratie-
                        // geschiedenis al bestond VOORDAT dit veld bestond
                        // anders nooit meer een kans krijgt om zijn
                        // startmoment door te geven.
                        retry(gatt) { writeCharacteristicCompat(gatt, appInfoC, buildSetAppInfoCommand()) }
                    } else if (needsTimeSync) {
                        retry(gatt) { writeCharacteristicCompat(gatt, appInfoC, buildSyncTimeCommand()) }
                    } else {
                        val glucoseC = glucoseChar ?: return
                        val lastSeq = CareSensAirNative.getLastSequence(handle)
                        retry(gatt) { writeCharacteristicCompat(gatt, glucoseC, buildRequestDataCommand(lastSeq)) }
                    }
                }
                firstByte == 0xC0 && secondByte == 2 -> {
                    val info = parseStartSensorResponse(value) ?: return
                    val handle = nativeStateHandle ?: return
                    CareSensAirNative.saveStartSensor(handle, info.eapp, info.vref, info.elapsedSecs)
                    // 02/08/2026 (editor, op verzoek: "type en nr sensor met
                    // start en einddatum") — dezelfde berekening als de
                    // native laag hierboven (`nu - elapsedSecs`, mirror van
                    // Juggluco's airSaveStartSensor), maar ook naar
                    // AppSettings weggeschreven zodat de UI (SensorInfoBlock/
                    // SensorManagementScreen) het ECHTE fysieke
                    // activatiemoment van de sensor kan tonen i.p.v. alleen
                    // wanneer de app voor het eerst verbond — zie kdoc bij
                    // AppSettings.Keys.CARESENS_SENSOR_STARTED_AT_MS.
                    val sensorStartedAtMs = System.currentTimeMillis() - info.elapsedSecs * 1000L
                    sensorStartedAtMsUnknown = false
                    scope.launch { settings.setCareSensAirSensorStartedAtMs(slot, sensorStartedAtMs) }
                    val appInfoC = appInfoChar ?: return
                    retry(gatt) { writeCharacteristicCompat(gatt, appInfoC, buildAskSensorInfoCommand()) }
                }
                firstByte == 0xC2 && secondByte == 1 -> {
                    val handle = nativeStateHandle ?: return
                    CareSensAirNative.saveSensorInfoChunk1(handle, value)
                    // Wacht op het vervolgbericht (0xC2/2) — geen commando
                    // hier nodig, de sensor stuurt vanzelf door.
                }
                firstByte == 0xC2 && secondByte == 2 -> {
                    val handle = nativeStateHandle ?: return
                    CareSensAirNative.saveSensorInfoChunk2(handle, value)
                }
                firstByte == 0xC2 && secondByte == 3 -> {
                    // Afronding van de kalibratieprofiel-overdracht (in
                    // Juggluco's bron: CRC-verificatie — bewust niet
                    // geport, zie klasse-kdoc). Rechtstreeks door naar de
                    // volgende stap.
                    if (needsTimeSync) {
                        val appInfoC = appInfoChar ?: return
                        retry(gatt) { writeCharacteristicCompat(gatt, appInfoC, buildSyncTimeCommand()) }
                    } else {
                        val handle = nativeStateHandle ?: return
                        val glucoseC = glucoseChar ?: return
                        val lastSeq = CareSensAirNative.getLastSequence(handle)
                        retry(gatt) { writeCharacteristicCompat(gatt, glucoseC, buildRequestDataCommand(lastSeq)) }
                    }
                }
                firstByte == 195 && secondByte == 2 -> {
                    val handle = nativeStateHandle ?: return
                    val glucoseC = glucoseChar ?: return
                    val lastSeq = CareSensAirNative.getLastSequence(handle)
                    retry(gatt) { writeCharacteristicCompat(gatt, glucoseC, buildRequestDataCommand(lastSeq)) }
                }
                firstByte == 204 && secondByte == 2 -> {
                    _connectionState.value = ConnectionState.Error("This CareSens Air sensor has ended its service life.")
                    runCatching { gatt.disconnect() }
                }
                firstByte == 205 && secondByte == 2 -> {
                    _connectionState.value = ConnectionState.Error("CareSens Air reported a transmitter reset.")
                    runCatching { gatt.disconnect() }
                }
                else -> {
                    // 198/1, 0xC6/1, 0xC6/2 en overige: geen actie nodig
                    // (mirror van Juggluco: puur informatief).
                }
            }
        }

        private fun handleGlucoseDataNotification(gatt: BluetoothGatt, value: ByteArray) {
            val handle = nativeStateHandle ?: return
            when (val result = CareSensAirNative.processGlucoseData(handle, value)) {
                is CareSensAirNative.GlucoseFrameResult.RecordCountAnnounced -> {
                    val glucoseC = charGlucoseData ?: return
                    // 02/08/2026 (editor, na live-test — zie
                    // noticedNumberRecords's kdoc bij het klasse-veld) —
                    // was `if (result.newRecords > 0)`: Juggluco's eigen
                    // `onChar11Changed` vraagt bij de EERSTE 0xC4-
                    // aankondiging in een sessie ALTIJD het exacte aantal
                    // op (197,1), ongeacht de aangekondigde waarde — een
                    // aankondiging met newRecords=0 kreeg hierdoor
                    // voorheen NOOIT een vervolgverzoek, precies het gat
                    // waar deze test op vastliep.
                    if (!noticedNumberRecords) {
                        noticedNumberRecords = true
                        // 02/08/2026 (editor, diagnostisch — zie kdoc bij
                        // onCharacteristicWrite hieronder) — expliciete
                        // logregel, dezelfde stijl als de AppID-handshake,
                        // zodat de volgende test definitief laat zien OF dit
                        // verzoek daadwerkelijk verstuurd wordt (i.p.v. dat
                        // pas af te leiden uit stilte erna).
                        DiagnosticFileLogger.log(
                            "numberRecords-verzoek versturen (197,1) na aankondiging newRecords=${result.newRecords}"
                        )
                        retry(gatt) { writeCharacteristicCompat(gatt, glucoseC, buildNumberRecordsCommand()) }
                    } else {
                        DiagnosticFileLogger.log(
                            "0xC4-aankondiging genegeerd: numberRecords al eerder gevraagd deze sessie (newRecords=${result.newRecords})"
                        )
                    }
                }
                is CareSensAirNative.GlucoseFrameResult.Processed -> {
                    val reading = result.reading
                    if (reading != null) {
                        if (_connectionState.value !is ConnectionState.Connected) {
                            _connectionState.value = ConnectionState.Connected(
                                deviceAddress = gatt.device.address,
                                deviceName = "CareSens Air"
                            )
                        }
                        // 02/08/2026 (editor, na live-test — "hij ververst
                        // de laatste connectie tijd niet bij een nieuwe
                        // Bg") — dit stond voorheen BINNEN de
                        // `_connectionState.value !is Connected`-guard
                        // hierboven, dus werd maar ÉÉN keer per sessie
                        // bijgewerkt (bij de EERSTE overgang naar Connected)
                        // — elke latere succesvolle meting binnen dezelfde
                        // sessie (CareSens Air blijft na de eerste keer
                        // gewoon "Connected" staan, zie de v64/v65-fix
                        // verderop in dit bestand) liet deze waarde
                        // ONGEWIJZIGD, ook al kwam er wél elke ~5 minuten
                        // een nieuwe, succesvol verwerkte meting binnen.
                        // Nu buiten die guard: elke geslaagd verwerkte
                        // meting (elke `reading != null`-doorgang hier, dus
                        // elke keer dat de sensor daadwerkelijk iets nieuws
                        // aanlevert) werkt "Last connected" bij, ongeacht of
                        // de zichtbare status al Connected was.
                        val connectedAtMs = System.currentTimeMillis()
                        lastSuccessfulConnectionAtMs = connectedAtMs
                        // 10/08/2026 (editor, RONDE 86) — ÉÉN keer gezet, bij
                        // de eerste geslaagde meting van deze connect()-
                        // sessie; zie het klasse-veld en
                        // computeReconnectCooldownMs()'s kdoc.
                        if (cadenceAnchorAtMs == null) cadenceAnchorAtMs = connectedAtMs
                        scope.launch { settings.setCareSensAirLastConnectedAtMs(slot, connectedAtMs) }
                        scope.launch {
                            _readings.emit(
                                GlucoseReading(
                                    glucoseMgdl = reading.glucoseMgdl,
                                    trendMgdlPerMin = (reading.trendMgdlPerMin ?: 0.0).toFloat(),
                                    timestampMs = reading.epochSecs * 1000L,
                                    sensorStartedAtMs = driverStartedAtMs,
                                    sensorType = SensorType.CARESENS_AIR
                                )
                            )
                        }
                        val serial = sensorSerial
                        val ctx = appContext
                        if (serial != null && ctx != null) {
                            CareSensAirNative.persist(ctx, handle, serial)
                        }
                    }
                    // reading == null: historisch vulrecord zonder
                    // bruikbare waarde — geen actie nodig, de sensor stuurt
                    // vanzelf het volgende record (of, bij het laatste
                    // record van een batch, weer een 0xC4-aankondiging).
                }
                CareSensAirNative.GlucoseFrameResult.SensorError -> {
                    // Mirror van Juggluco: bewust NIET disconnecten, gewoon
                    // blijven wachten — een sensorfoutmelding is vaak
                    // tijdelijk.
                }
                CareSensAirNative.GlucoseFrameResult.Ignored -> {}
            }
        }

        private fun retry(gatt: BluetoothGatt, op: () -> Boolean) {
            scope.launch {
                repeat(RETRY_ATTEMPTS) { attempt ->
                    // 02/08/2026 (editor, diagnostisch) — `op()` liep hier
                    // voorheen ONgevangen: een eventuele exception (bv. een
                    // BLE-statusfout van het onderliggende platform) zou
                    // stil deze specifieke coroutine hebben beëindigd, zonder
                    // enige logregel die dat verklaart — precies het soort
                    // stille falen dat de ~26s-stiltes tot nu toe zo lastig
                    // te doorgronden maakte. Nu altijd gelogd, ongeacht
                    // oorzaak.
                    val ok = runCatching { op() }.onFailure { e ->
                        DiagnosticFileLogger.logError(
                            "retry(): op() gooide een exception (poging ${attempt + 1}/$RETRY_ATTEMPTS): $e"
                        )
                    }.getOrDefault(false)
                    if (ok) return@launch
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * 01/08/2026 (editor, op verzoek) — de generieke koppellijst
     * (ui/PairingScreen.kt) toont standaard ELK nabij BLE-apparaat (zie de
     * kdoc bij startPairing hierboven: een ScanFilter matchte deze sensor
     * nooit, dus de scan zelf is bewust ongefilterd). Voor CareSens Air
     * weten we via de barcode-scan (koppel-stap 1, zie
     * CareSensAirBarcode.kt) al het serienummer VOORDAT er gescand wordt —
     * dat gebruiken we hier om de lijst te verkleinen tot apparaten die
     * plausibel de gezochte sensor zijn: "CSAir" in de naam (zoals op het
     * echte etiket, bv. "CSAir 0224"), of de laatste 3/4 cijfers van het
     * gescande serienummer ergens in de naam. Puur een vuistregel (de
     * exacte naamgeving kan per sensor-firmware/regio verschillen) — geen
     * harde blokkade: PairingScreen.kt biedt altijd een
     * "toon alle apparaten"-schakelaar om dit filter opzij te zetten. Een
     * apparaat zonder leesbare naam wordt (sinds de live-test-fix van
     * 01/08/2026) WEL verborgen zolang het filter aan staat — CareSens Air
     * bleek in de praktijk altijd een naam mee te sturen, en zonder deze
     * uitsluiting liet het filter alle naamloze ruisapparaten alsnog door.
     */
    override suspend fun buildPairingListFilter(context: Context): ((String?, String) -> Boolean)? {
        val serial = AppSettings(context).getCareSensAirScanOnce(slot)?.serial
        val suffixes = listOfNotNull(
            serial?.takeIf { it.length >= 4 }?.takeLast(4),
            serial?.takeIf { it.length >= 3 }?.takeLast(3)
        )
        // 01/08/2026 (editor, na live-test — "de filtering werkt niet") —
        // apparaten ZONDER naam werden voorheen altijd getoond ("return true"
        // bij een null-naam), met als bedoeling de echte sensor nooit per
        // ongeluk te verbergen. In de praktijk adverteren veel nabije
        // BLE-apparaten (koptelefoons, andere telefoons, anonieme beacons)
        // geen naam in hun advertentie, en CSAir zelf bleek in de logs
        // altijd wél gewoon een naam mee te sturen ("CSAir 0224") — het
        // gevolg was dat het filter de lijst niet verkleinde, omdat bijna
        // alle ruis toch doorgelaten werd. Naamloze apparaten worden nu
        // verborgen (zoals elk ander niet-matchend apparaat); de "Show all
        // nearby devices"-schakelaar in PairingScreen.kt blijft de garantie
        // dat een apparaat nooit definitief onbereikbaar wordt.
        return filter@{ deviceName, _ ->
            val name = deviceName ?: return@filter false
            val upper = name.uppercase()
            upper.contains("CSAIR") || suffixes.any { upper.contains(it) }
        }
    }

    companion object {
        private const val RETRY_ATTEMPTS = 8
        private const val RETRY_DELAY_MS = 150L

        // 02/08/2026 (editor, ronde 26 — na een SCHONE test, expliciet zonder
        // Recents-swipe, met v72's 90s-scanvenster EN v73's stopWithTask-fix,
        // waarbij het probleem toch identiek bleef: "Ik heb nu de apps niet
        // weg geswiped en nog steeds update hij niet. Alleen het scherm
        // zwart laten worden") — de vorige 40s->90s-fix loste het NIET op
        // omdat de aanname erachter fout was: er is HELEMAAL GEEN "scan een
        // tijdje, geef dan op, wacht MIN_SCAN_COOLDOWN_MS, probeer opnieuw"-
        // cyclus in Juggluco's eigen, bewezen-werkende gedrag. Dat was zelf
        // nooit met zekerheid uit de decompile bevestigd (`SCAN_ATTEMPT_
        // TIMEOUT_MS` was een AANNAME, gebaseerd op de gebruiker's eigen
        // 60-90s-hypothese, niet op bytecode) — bij navraag bleek de
        // eigenlijke scan-planningslogica niet in `bk0` (SensorBluetooth)
        // zelf te zitten maar in een aparte, gedeelde `Runnable`-klasse `w2`
        // (via `bk0`'s veld `e = new w2(8, this)`), pas gevonden na een
        // TWEEDE, gerichte decompile-poging (androguard -l "Lw2;.*", de
        // eerste volledige decompile brak kennelijk af vóórdat die klasse
        // bereikt werd). `w2.run()`, geval 8 (bk0's tak), bevestigt:
        // ÉÉN `startScan()`-aanroep, GEEN stopScan()-aanroep erna, en
        // vervolgens plant het zichzelf gewoon opnieuw in — 390 SECONDEN
        // (6,5 minuten) later, niet 40-90 seconden:
        //   v0_10.i = tk.glucodata.Applic.t.schedule(v0_10.e, 390000, TimeUnit.MILLISECONDS);
        // Met andere woorden: Juggluco start een scan en laat die gewoon
        // DOORLOPEN — de scan wordt nooit actief gestopt-en-herstart op een
        // korte timer. Die 390s-herplanning is puur een zelf-herstellend
        // veiligheidsnet (voor het geval Android de langlopende scan stil
        // beëindigt), GEEN "geef het op en probeer straks opnieuw"-cyclus.
        // Onze eigen 40s/90s-timeout deed precies het tegenovergestelde: na
        // elke mislukte poging stopte de scan ACTIEF, gevolgd door een
        // MIN_SCAN_COOLDOWN_MS (60s) dode periode zonder enige scan — en
        // precies in die dode periodes viel de sensor's eigen korte
        // advertentievenster kennelijk vaak genoeg om de waargenomen
        // onregelmatige vertraging te verklaren. SCAN_REARM_INTERVAL_MS
        // hieronder vervangt SCAN_ATTEMPT_TIMEOUT_MS: geen "geef op"-timer
        // meer, alleen een herstel-herplanning ver in de achtergrond — zie
        // startConnectScan()/scheduleRearm() voor de nieuwe, niet-
        // onderbrekende implementatie.
        private const val SCAN_REARM_INTERVAL_MS = 390_000L

        // 02/08/2026 (editor, na de teruggedraaide `autoConnect=true`-
        // poging — zie de uitgebreide klasse-kdoc hierboven voor de
        // Juggluco-decompile-bevinding) — mirror van Juggluco's eigen
        // `SensorBluetooth.u()`-standaardpauze (60000ms) tussen het einde
        // van een scanpoging (apparaat gevonden, ÓF een scan die niets
        // opleverde) en het begin van de volgende. Bewust GEEN poging om
        // elke individuele duty-cycle van de sensor zelf (~26-30s) bij te
        // benen — CareSens Air's eigen sequence-/geschiedenis-mechanisme
        // (CareSensAirNative.getLastSequence/buildNumberRecordsCommand)
        // haalt een gemiste cyclus vanzelf in bij de eerstvolgende
        // geslaagde verbinding. Dit getal, niet "zo snel mogelijk opnieuw
        // scannen", bleek in Juggluco's eigen, bewezen-werkende gedrag het
        // verschil te zijn — zie ScanRateLimiter voor de andere helft
        // (Juggluco's 5-scans-per-31s-plafond).
        private const val MIN_SCAN_COOLDOWN_MS = 60_000L

        // 03/08/2026 (editor, RONDE 31 — op basis van de gebruiker's eigen
        // logcat-analyse en voorstel, na v78: "hij loopt regelmatig 2
        // minuten te laat... ik zit zelf te denken of we (...) door bv 4 of
        // 4,5 minuten na de laatste update pas weer een signaal te sturen en
        // dat te herhalen tot er een nieuwe waarde binnenkomt") — de flat
        // MIN_SCAN_COOLDOWN_MS hierboven (60s) is Juggluco's eigen pauze
        // TUSSEN SCANPOGINGEN, en blijft daarvoor ook gelden (zie
        // computeReconnectCooldownMs() hieronder voor waar dit getal wél nog
        // gebruikt wordt). Maar toegepast als de ENIGE pauze ná elke
        // GATT-disconnect leidt tot herhaaldelijke "wasted" herverbind-
        // pogingen (newRecords=0) omdat de sensor zelf maar ~1x per 5
        // minuten een nieuwe meting heeft — veel korter dan de sensor's
        // eigen cadans. De echte logcat (16:03-16:25, v78, open scherm)
        // toonde reële succesvolle-metingen-intervallen van 5m59s, 3m1s,
        // 4m59s, 7m5s i.p.v. een strak 5-minuten-ritme, wat past bij dit
        // "vroegtijdig, herhaaldelijk botsen tegen een sensor die nog niets
        // nieuws heeft"-patroon. PREDICTIVE_RECONNECT_LEAD_MS hieronder is
        // de door de gebruiker zelf voorgestelde middenwaarde (4,5 minuten)
        // uit hun "4 of 4,5 minuten"-marge.
        //
        // 03/08/2026 (editor, RONDE 32 — bijgesteld ná de EERSTE test met
        // v79's voorspellende cooldown, screen-off, exacte logcat-tijden
        // uitgerekend) — de voorspelling zelf vuurde nagenoeg perfect op tijd
        // (scan geregistreerd om 17:03:19.936, voorspeld 17:03:19.917,
        // verschil <20ms) — maar tussen "scan geregistreerd" en "sensor
        // daadwerkelijk gevonden" (`onScanResult`/match) zat nog eens 1m33s
        // (17:03:19.936 -> 17:04:53.386): de tijd die de sensor's eigen korte
        // advertentie-duty-cycle nodig had om binnen het scanvenster te
        // vallen. Dat komt BOVENOP de 4,5 minuten lead, dus de meting kwam
        // in totaal 6m4,5s na de vorige binnen (in plaats van de sensor's
        // eigen ~5 minuten) — zichtbaar beter dan de voorgaande rondes
        // (tientallen minuten), maar nog steeds ~1 minuut later dan nodig.
        // Vergelijk met de EERSTE reconnectie in dezelfde logcat (nog vlak na
        // schermuit, minder diep in Doze): daar duurde diezelfde
        // "geregistreerd -> gevonden"-stap maar ~28s. Deze
        // duty-cycle-wachttijd is dus zelf ook variabel (neemt kennelijk toe
        // naarmate de telefoon langer met scherm uit/dieper in Doze zit) en
        // niet volledig te elimineren met deze aanpak — maar door de lead
        // time te verkorten naar 3,5 minuten bouwen we bewust ruimte in
        // (~1-1,5 minuut) voor die extra duty-cycle-wachttijd, zodat de
        // TOTALE meting weer dichter bij de sensor's eigen ~5 minuten-cadans
        // uitkomt, i.p.v. 4,5 minuten lead + de volle duty-cycle-wachttijd
        // erbovenop te laten optellen tot >6 minuten.
        //
        // 04/08/2026 (editor, RONDE 39 — op verzoek, "ik wil het liever zo
        // consistent mogelijk dus graag nog een optimalisatie", na de
        // ronde-38-log-analyse van het afwisselende "+7s/+67s"-patroon in
        // xDrip+) — de 3,5-minuten-lead hierboven was in RONDE 32 bewust
        // KORTER dan de gebruiker's oorspronkelijke 4,5-minuten-voorstel
        // gezet, specifiek om ruimte te laten voor de toen nog sterk
        // WISSELENDE "geregistreerd -> gevonden"-duty-cycle-wachttijd (28s
        // tot 93s+, zie de RONDE-32-paragraaf hierboven). Die aanname klopt
        // niet meer: RONDE 37's LOW_LATENCY+MATCH_MODE_AGGRESSIVE-scanfix
        // (op basis van de gedecompileerde officiële app) bracht die
        // wachttijd terug tot een strakke, betrouwbare ~26-30s (bevestigd in
        // README.md's ronde-37/38-metingen). Met een nu voorspelbare,
        // kleine "tax" is de korte 3,5-minuten-lead niet langer nodig als
        // veiligheidsmarge — sterker nog, hij is nu net te kort: eerste
        // poging valt op lastReadingAt+3,5min+~28s ≈ +4min, systematisch
        // ~1 minuut VÓÓR de sensor's eigen ~5-minuten-cadans, wat op de
        // helft van de cycli een newRecords=0-misser gaf (zie
        // computeReconnectCooldownMs()'s kdoc) — pas op de eerstvolgende
        // cyclus (nog eens MIN_SCAN_COOLDOWN_MS=60s later) alsnog raak,
        // precies het afwisselende 4min/6min- (dus xDrip's +7s/+67s-)
        // patroon dat de gebruiker signaleerde. Opgehoogd naar 4 minuten 40
        // seconden zodat lead+tax (~280s+~29s ≈ 309s) juist ná, in plaats
        // van vóór, de ~5-minuten-cadans uitkomt — elke poging zou daarmee
        // in één keer raak moeten zijn, zonder de 60s-terugval-lus, wat de
        // xDrip-vertraging van een afwisselend 7s/67s-patroon terug zou
        // moeten brengen naar een strakke, consistente band van een paar
        // tot ruim tien seconden. Bevestiging volgt uit de eerstvolgende
        // logfile.
        //
        // 10/08/2026 (editor, RONDE 86) — vervangen door twee losse
        // constanten (`SENSOR_PERIOD_MS`/`SCAN_START_MARGIN_MS`), zie
        // computeReconnectCooldownMs()'s kdoc voor de volledige aanleiding
        // (permanente faseverschuiving na een eenmalige scanbotsing met de
        // andere slot). Was hier 280 000ms lead (dus maar ~20s marge) —
        // tegelijk met Dexcom's zelfde soort marge-verruiming (Ronde 85,
        // daar naar 60s) ook hier naar 60s opgetrokken: nu Dexcom's eigen
        // scans vrijwel altijd raak zijn (dus vaker een actieve scan heeft
        // lopen om mee te botsen), is een even ruime marge hier het meest
        // consistente vangnet tegen de volgende botsing — het zelf-
        // corrigerende anker hierboven zorgt er daarnaast voor dat een
        // eventuele botsing sowieso niet meer permanent blijft hangen.
        private const val SENSOR_PERIOD_MS = 300_000L // 5 min — CareSens Air's eigen meetcadans.

        // 12/08/2026 (editor, RONDE 100 — op verzoek, na analyse van
        // fclglucolink_2026-08-12.txt: "kijken of daar nog wat aan te doen
        // is") — teruggezet van 60s naar 30s. De 60s hierboven (sinds Ronde
        // 86) diende TWEE doelen tegelijk: (1) genoeg lead-tijd dat de
        // ~26-30s duty-cycle-zoektijd (Ronde 37) een scanpoging niet vóór de
        // sensor's eigen meting laat afronden, en (2) een symmetrische
        // marge met Dexcom als generieke botsingsbuffer. Doel (2) wordt
        // sinds deze ronde expliciet en veel gerichter afgehandeld door
        // AapsSlotSchedule (zie sensor/ble/AapsSlotSchedule.kt) — dus hoeft
        // deze marge dat niet meer "toevallig" mee op te vangen. Met alleen
        // doel (1) overblijvend is 60s te ruim: de log toonde een
        // steeds-grover-wordend afwisselend "te-vroeg-newRecords=0 -> 60s
        // terugval -> alsnog raak"-patroon (180s/420s i.p.v. een strakke
        // 300s) — exact het patroon dat Ronde 39 destijds al identificeerde
        // en gericht wegtunede naar een marge van destijds ~20s (toen als
        // vaste totale lead-tijd geformuleerd, niet als deze losse
        // constante). 30s zit dicht bij die beproefde waarde, met een kleine
        // veiligheidsmarge boven de ~26-30s duty-cycle-baseline.
        private const val SCAN_START_MARGIN_MS = 30_000L // marge vóór het verwachte rasterpunt.

        // 03/08/2026 (editor, RONDE 31, zelfde aanleiding) — was 3 minuten;
        // de gebruiker wees er terecht op dat CareSens Air zelf maar 1x per
        // ~5-6 minuten een nieuwe meting oplevert, dus een waarschuwing na
        // exact 3 minuten vuurt structureel te vroeg af tijdens volkomen
        // normaal gedrag (zie het screenshot: "No connection for 4 minutes
        // (still trying)" tijdens een routinematige, korte herverbind-cyclus
        // zonder enig écht probleem). Letterlijk gebruikersvoorstel: "het
        // zou logischer zijn als die pas na bv 7 minuten komt" — 7 minuten
        // overgenomen, dat is ruim boven de langste waargenomen normale
        // succesvolle-meting-interval (7m5s in de logcat hierboven) maar nog
        // steeds kort genoeg om een écht probleem (sensor buiten bereik,
        // uit, of al verbonden met een ander toestel) tijdig te signaleren.
        private const val RECONNECT_STATUS_WARNING_MINUTES = 7L

        @Suppress("DEPRECATION")
        private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }

        @Suppress("DEPRECATION")
        private fun writeCharacteristicCompat(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }

        private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
            if (!gatt.setCharacteristicNotification(characteristic, true)) return false
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return false
            return writeDescriptorCompat(gatt, descriptor, ENABLE_NOTIFICATION_VALUE)
        }
    }
}
