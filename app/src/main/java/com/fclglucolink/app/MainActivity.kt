package com.fclglucolink.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fclglucolink.app.data.AppSettings
import com.fclglucolink.app.sensor.ble.BleConnectionService
import com.fclglucolink.app.sensor.ble.ConnectionWatchdog
import com.fclglucolink.app.ui.FclGlucoLinkNavHost
import com.fclglucolink.app.ui.theme.FCLGlucoLinkTheme
import kotlinx.coroutines.flow.first

/**
 * 30/07/2026 (editor) — enige Activity van de app. Regelt alleen de BLE/
 * notificatie-runtime-permissies en start de NavHost — alle daadwerkelijke
 * schermlogica zit in ui/FclGlucoLinkNavHost.kt en de losse schermen.
 */
class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FCLGlucoLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionGate(requiredPermissions) {
                        // Permissies zijn hier verleend -> als er al een
                        // sensor+device geconfigureerd is (herstart-scenario),
                        // meteen de koppelservice (opnieuw) starten. Bij een
                        // verse installatie doet PairingScreen dit zelf zodra
                        // het koppelen daadwerkelijk lukt.
                        LaunchedEffect(Unit) {
                            // 10/08/2026 (editor, RONDE 79 — 2-sensoren-
                            // architectuur) — was een enkele selectedSensor/
                            // deviceAddress-check; nu "is er ergens (Slot A
                            // of B) iets geconfigureerd" (zie AppSettings.
                            // hasAnySlotConfigured()'s kdoc) — WELKE slot(s)
                            // dat precies zijn maakt hier niet uit,
                            // BleConnectionService.onStartCommand() verwerkt
                            // beide slots toch al onafhankelijk van elkaar.
                            val settings = AppSettings(this@MainActivity)
                            if (settings.hasAnySlotConfigured()) {
                                startBleConnectionService(this@MainActivity)
                            }
                        }
                        // 30/07/2026 (editor, na feedback: niet herhaaldelijk
                        // vragen om batterij-uitzondering, liever automatisch)
                        // — Android staat geen volledig stille/geforceerde
                        // uitzondering toe (vereist altijd een tik van de
                        // gebruiker op het systeemscherm).
                        //
                        // 02/08/2026 (editor, na live-test — "als het scherm
                        // op zwart gaat dat fclglucolink gaat lopen
                        // vertragen") — was: prompt precies ÉÉN keer ooit,
                        // daarna nooit meer gecontroleerd. Sommige
                        // toestelmerken (Samsung/Xiaomi/Huawei, zie
                        // BleConnectionService.kt) trekken een eerder
                        // verleende uitzondering na een app-update of
                        // periodieke "opschoning" stilzwijgend weer in — dat
                        // bleef zo onopgemerkt, en de app promptte dan ook
                        // nooit meer om 'm terug te vragen, wat precies dit
                        // soort schermuit-vertraging in de hand kan werken
                        // (achtergrond-BLE/CPU-beperkingen die de
                        // uitzondering nu net had moeten voorkomen). Nu:
                        // controleert bij ELKE start opnieuw de ECHTE
                        // uitzonderings-status (niet alleen "hebben we ooit
                        // gevraagd"), en prompt opnieuw als die is weggevallen
                        // — met een cooldown van 24 uur zodat een gebruiker
                        // die 'm bewust wegklikt niet bij elke app-start
                        // opnieuw lastiggevallen wordt.
                        LaunchedEffect(Unit) {
                            val settings = AppSettings(this@MainActivity)
                            if (!isIgnoringBatteryOptimizations(this@MainActivity)) {
                                val lastPromptedAtMs = settings.batteryOptimizationLastPromptedAtMs.first()
                                val nowMs = System.currentTimeMillis()
                                val cooldownMs = 24L * 60 * 60 * 1000
                                if (lastPromptedAtMs == null || nowMs - lastPromptedAtMs > cooldownMs) {
                                    requestIgnoreBatteryOptimizations(this@MainActivity)
                                    settings.setBatteryOptimizationLastPromptedAtMs(nowMs)
                                }
                            }
                        }
                        FclGlucoLinkNavHost()
                    }
                }
            }
        }
    }
}

/**
 * 30/07/2026 (editor) — helper zodat PairingScreen (na een geslaagde koppeling)
 * en MainActivity (bij herstart) exact dezelfde start-aanroep gebruiken.
 *
 * 03/08/2026 (editor, ronde 28) — plant ook ConnectionWatchdog's
 * AlarmManager-wekker in, zie die klasse's kdoc: een OS-gegarandeerde
 * herstart-tik die blijft werken ook als dit proces zelf ooit onder Doze/
 * agressief batterijbeheer volledig gestopt wordt — iets waar de gewone
 * coroutine-timers in BleConnectionService/CareSensAirDriver geen garantie
 * voor kunnen geven.
 */
fun startBleConnectionService(context: Context) {
    val intent = Intent(context, BleConnectionService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    ConnectionWatchdog.schedule(context)
}

/**
 * 30/07/2026 (editor) — voor de "Verbinding verbreken"-actie op het
 * statusscherm (ui/StatusScreen.kt). Stopt de service; de UI zet
 * ConnectionStatusBridge zelf direct op Disconnected (niet afhankelijk van
 * de timing van Service.onDestroy()) voor meteen zichtbare feedback.
 *
 * 03/08/2026 (editor, ronde 28) — annuleert ook ConnectionWatchdog's wekker
 * — zonder dit zou die de zojuist bewust gestopte service binnen 6 minuten
 * weer opnieuw opstarten.
 */
fun stopBleConnectionService(context: Context) {
    context.stopService(Intent(context, BleConnectionService::class.java))
    ConnectionWatchdog.cancel(context)
}

/** True als de gebruiker deze app al heeft uitgezonderd van batterijbeheer
 *  (via de eenmalige prompt hieronder, of handmatig in de
 *  systeeminstellingen). */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** 30/07/2026 (editor) — stuurt de gebruiker naar Android's eigen
 *  systeemscherm om FCLGlucoLink uit te zonderen van batterijbeheer.
 *  Vereist REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in het manifest. Android
 *  laat een app dit bewust niet volledig stil/geforceerd doen — het
 *  systeemscherm vraagt altijd een expliciete tik van de gebruiker, dat kan
 *  deze functie niet omzeilen. Zie MainActivity.onCreate() voor waarom dit
 *  maar één keer ooit aangeroepen wordt. */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(intent) }
}

@Composable
private fun PermissionGate(
    permissions: Array<String>,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> granted = results.values.all { it } }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "FCLGlucoLink needs Bluetooth and notification permissions " +
                    "to connect to your sensor and keep the link to AAPS " +
                    "active.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = { launcher.launch(permissions) },
                modifier = Modifier.padding(top = 16.dp)
            ) { Text("Grant permissions") }
        }
    }
}
