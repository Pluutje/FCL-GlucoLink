package com.fclglucolink.app.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.fclglucolink.app.logging.DiagnosticFileLogger

/**
 * Detectie van andere apps die met dezelfde sensor kunnen "vechten" om de
 * Bluetooth-verbinding. CareSens Air en Dexcom G6 praten maar met één
 * verbinding tegelijk; een tweede app (xDrip+, Juggluco, Diabox, de officiële
 * Dexcom-/CareSens-app, CamAPS FX, ...) die dezelfde sensor opent, zorgt voor
 * afgewezen of verbroken verbindingen aan beide kanten.
 *
 * Alleen lokale OS-opvragingen — geen BLE-verkeer, niets wordt aan de sensor
 * gewijzigd. Wordt door BleConnectionService alleen aangeroepen als er een
 * tijdje geen meting binnenkwam (zie daar).
 *
 * Twee signalen:
 *  1. Geïnstalleerde apps: bekende pakketnamen plus een trefwoordcontrole op
 *     alle geïnstalleerde pakketten (vereist QUERY_ALL_PACKAGES, anders ziet
 *     Android 11+ alleen een deel van de apps).
 *  2. "Door een andere app verbonden": BluetoothManager.getConnectedDevices(GATT)
 *     toont toestellen met een actieve GATT-verbinding van welke app dan ook.
 *     Staat onze sensor daarin terwijl onze eigen driver NIET verbonden is,
 *     dan houdt een andere app de sensor vast.
 */
object CompetingAppDetector {

    data class Result(
        /** Leesbare namen van gevonden apps die met de sensor kunnen concurreren. */
        val installedApps: List<String>,
        /** true = sensor heeft een GATT-verbinding terwijl wij niet verbonden zijn. */
        val sensorHeldByOther: Boolean,
        val bluetoothConnectGranted: Boolean
    ) {
        val suspicious: Boolean get() = sensorHeldByOther || installedApps.isNotEmpty()
    }

    /** Bekende apps die zelf met CGM-sensoren verbinden (pakketnaam -> weergavenaam). */
    private val KNOWN: Map<String, String> = linkedMapOf(
        "com.eveningoutpost.dexdrip" to "xDrip+",
        "tk.glucodata" to "Juggluco",
        "com.outshineiot.diabox" to "Diabox",
        "com.dexcom.g6" to "Dexcom G6",
        "com.dexcom.g6.region1.mmol" to "Dexcom G6",
        "com.dexcom.g6.region2.mgdl" to "Dexcom G6",
        "com.dexcom.g6.region3.mgdl" to "Dexcom G6",
        "com.dexcom.g6.region4.mmol" to "Dexcom G6",
        "com.dexcom.g6.region5.mmol" to "Dexcom G6",
        "com.dexcom.g6.region6.mgdl" to "Dexcom G6",
        "com.dexcom.g6.region7.mmol" to "Dexcom G6",
        "com.dexcom.g7" to "Dexcom G7",
        "com.dexcom.dexcomone" to "Dexcom ONE",
        "com.dexcom.dexcomone.mmol" to "Dexcom ONE",
        "com.dexcom.dexcomone.mgdl" to "Dexcom ONE",
        "com.isens.csair" to "CareSens Air",
        "com.isens.csair.nl" to "CareSens Air",
        "com.isens.caresensair" to "CareSens Air",
        "com.camdiab.fx_alert.mmoll" to "CamAPS FX",
        "com.camdiab.fx_alert.mgdl" to "CamAPS FX",
        "com.camdiab.fx_alert.hx.mmoll" to "CamAPS FX",
        "com.camdiab.fx_alert.hx.mgdl" to "CamAPS FX",
        "com.librelink.app" to "FreeStyle LibreLink",
        "com.freestylelibre3.app" to "FreeStyle Libre 3",
        "org.nativescript.diabox" to "Diabox",
        "com.eversense.app" to "Eversense",
        "com.senseonics.eversense" to "Eversense",
        "com.glimp.app" to "Glimp",
        "com.sibionics.sicgm" to "SiBionics",
        "com.tomatoapp.tomato" to "Tomato"
    )

    /** Trefwoorden in de pakketnaam; alleen gebruikt als extra vangnet. */
    private val KEYWORDS = listOf(
        "xdrip", "juggluco", "glucodata", "diabox", "caresens", "csair",
        "dexcom", "camdiab", "camaps", "eversense", "sibionics", "chimera",
        "byoda", "miaomiao", "bluecon", "tomato"
    )

    /** Verbinden niet zelf met de sensor (volgers; GlucoDataHandler leest alleen uitzendingen van andere apps). */
    private val IGNORE_SUBSTRINGS = listOf("follow", "clarity", "stelo", "glucodatahandler", "michelinside", "libreoffice", "documentfoundation")

    @SuppressLint("MissingPermission")
    fun check(
        context: Context,
        deviceAddress: String?,
        ownConnected: Boolean
    ): Result {
        val pm = context.packageManager
        val own = context.packageName

        val found = linkedSetOf<String>()
        val packages = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        for (info: ApplicationInfo in packages) {
            val pkg = info.packageName
            if (pkg == own) continue
            val lower = pkg.lowercase()
            if (IGNORE_SUBSTRINGS.any { lower.contains(it) }) continue
            val known = KNOWN[pkg]
            if (known != null) {
                found.add(known)
            } else if (KEYWORDS.any { lower.contains(it) }) {
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                found.add(if (label == pkg) pkg else "$label ($pkg)")
            }
        }
        // Vangnet voor het geval de lijst hierboven beperkt is (geen
        // QUERY_ALL_PACKAGES): probeer de bekende namen direct.
        for ((pkg, name) in KNOWN) {
            if (pkg == own || found.contains(name)) continue
            val installed = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }.isSuccess
            if (installed) found.add(name)
        }

        var granted = true
        var held = false
        if (!ownConnected && !deviceAddress.isNullOrBlank()) {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val connected = runCatching { bm?.getConnectedDevices(BluetoothProfile.GATT) }
                .onFailure { granted = false }
                .getOrNull().orEmpty()
            held = connected.any { it.address.equals(deviceAddress, ignoreCase = true) }
        }

        return Result(found.toList(), held, granted)
    }

    fun logResult(tag: String, r: Result) {
        DiagnosticFileLogger.log(
            "CompetingAppDetector($tag): andere apps=${r.installedApps.ifEmpty { listOf("geen") }} " +
                "sensorVastgehoudenDoorAndere=${r.sensorHeldByOther} " +
                "bluetoothRecht=${r.bluetoothConnectGranted}"
        )
    }
}
