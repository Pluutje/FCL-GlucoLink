import java.util.Properties

// FCLGlucoLink — app module.
//
// 30/07/2026 (editor) — minSdk 26: nodig voor de moderne BLE-scanfilters en
// foreground-service-types die de sensor-koppeling gebruikt; vrijwel alle
// actieve Android-toestellen zitten daar inmiddels boven. compileSdk/
// targetSdk 34 — Android Studio zal bij het openen waarschijnlijk een AGP-
// upgrade voorstellen; dat is prima om te accepteren, deze versies zijn een
// veilig, actueel startpunt, geen harde eis.
//
// 01/08/2026 (editor) — NDK/CMake toegevoegd voor de CareSens Air-
// kalibratiebrug (taak "Design + build CareSens Air native calibration
// bridge" — zie README voor de volledige aanleiding en app/src/main/cpp/
// caresensair_bridge.cpp's kdoc voor de technische details). Bewust
// MINIMAAL: alleen abi arm64-v8a (dat is wat het toestel van de gebruiker gebruikt — de
// "Arm64 only (het gebruikelijke geval)"-Juggluco-apk was de bron van
// libCALCULATION.so), niet de volledige Juggluco-configuratie.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 04/09/2026 (editor, RONDE 165) — de Drive-map-ID en API-key voor de
// update-check (zie update/UpdateChecker.kt's kdoc) horen NIET in de
// broncode: local.properties staat al buiten versiebeheer (zie het bestand
// zelf, "must NOT be checked into Version Control Systems") — dus dat is
// ook de juiste plek voor deze twee, zelfde patroon als sdk.dir hierboven
// in datzelfde bestand. Bewust met lege string als fallback i.p.v. de build
// te laten falen als ze nog ontbreken — UpdateChecker.kt behandelt een lege
// key/ID gewoon als "update-check nu niet mogelijk", geen crash.
//
// 04/09/2026 (editor, bugfix na build-fout "Unresolved reference: util") —
// was `java.util.Properties()` los in de expressie, wat botst met AGP 8's
// eigen `java { ... }`-DSL-extensie (voor toolchain-configuratie) — die
// overschaduwt de kale `java`-pakketnaam op het top-level van dit script,
// waardoor Kotlin `.util` niet meer als onderdeel van het pakket herkent.
// Standaardfix: expliciete `import java.util.Properties` bovenaan dit
// bestand, hier alleen nog het ongekwalificeerde klassenaam gebruiken.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.fclglucolink.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fclglucolink.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 183
        versionName = "0.9.84-whats-new-and-manual-refresh"

        // 01/08/2026 (editor) — alleen arm64-v8a: libCALCULATION.so (in
        // app/src/main/jniLibs/arm64-v8a/) is alleen voor die ABI
        // geëxtraheerd. Het toestel is arm64 (de Juggluco-apk die als
        // bron diende was expliciet de arm64-only build). Als dit ooit op
        // een 32-bit/x86-toestel moet draaien: eerst de bijpassende
        // libCALCULATION.so voor die ABI uit de Arm/Arm64/x86/x86_64-variant
        // van de Juggluco-apk halen en hier het abiFilters-lijstje uitbreiden.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 04/09/2026 (editor, RONDE 165) — zie kdoc bij `localProperties`
        // hierboven en update/UpdateChecker.kt: BuildConfig.DRIVE_UPDATE_*
        // i.p.v. hardcoded in Kotlin, zodat de echte waarden nooit in
        // versiebeheer belanden. Waarden zelf vult de gebruiker aan in
        // local.properties: `driveUpdateFolderId=...` / `driveUpdateApiKey=...`.
        buildConfigField(
            "String",
            "DRIVE_UPDATE_FOLDER_ID",
            "\"${localProperties.getProperty("driveUpdateFolderId", "")}\""
        )
        buildConfigField(
            "String",
            "DRIVE_UPDATE_API_KEY",
            "\"${localProperties.getProperty("driveUpdateApiKey", "")}\""
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 01/08/2026 (editor, na live-test — "Couldn't load the CareSens Air
    // calibration library") — libCALCULATION.so wordt in
    // CareSensAirNative.kt zelf via dlopen() op een bestandspad geladen
    // (context.applicationInfo.nativeLibraryDir + "/libCALCULATION.so"),
    // exact zoals Juggluco's eigen C++-code dat ook doet. Standaard pakt
    // AGP native bibliotheken sinds een aantal jaar NIET meer uit naar
    // schijf bij installatie (ze blijven gecomprimeerd in de apk, en de
    // systeem-linker laadt ze rechtstreeks vanuit de apk voor gewone
    // System.loadLibrary()-aanroepen) — maar een eigen, handmatige
    // dlopen()-aanroep op een bestandspad heeft wél een ECHT bestand op
    // schijf nodig. Zonder deze instelling bestaat dat pad simpelweg niet,
    // en faalt dlopen() met "No such file or directory" — dat gaf de
    // foutmelding in de app. useLegacyPackaging=true forceert het oude
    // gedrag (altijd uitpakken naar schijf bij installatie) terug aan,
    // alleen voor native bibliotheken.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 31/07/2026 (editor) — nodig voor BuildConfig.VERSION_NAME op het
        // nieuwe About-scherm (ui/AboutScreen.kt). Sinds AGP 8 wordt
        // BuildConfig niet meer automatisch gegenereerd, moet expliciet aan.
        buildConfig = true
    }

    // 31/07/2026 (editor, na Android Studio-update — build faalde met
    // "Already disposed: MessageBus" in :app:lintVitalAnalyzeRelease) —
    // dat is een crash IN AGP's eigen lint-tooling zelf (UastEnvironment/
    // MessageBus-opruiming, com.intellij.* / org.jetbrains.uast.* in de
    // stacktrace, geen regel projectcode erin) — een bekend AGP-lint-bugje
    // dat na een Studio-update (nieuwere gebundelde JDK/AGP) naar boven
    // komt. "lintVitalRelease" is een verplichte, automatische lint-check
    // die AGP standaard vóór elke release-build (assembleRelease/
    // bundleRelease) aanroept; checkReleaseBuilds=false schakelt precies
    // dát automatische stapje uit. Gewone `./gradlew lint` (of Analyze >
    // Inspect Code in Android Studio) blijft gewoon apart bruikbaar.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Instellingen (gekozen sensor, gekoppeld device-adres) — simpel, geen
    // Room nodig voor zo weinig velden.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Recente-metingen-opslag voor de status/grafiek-schermen.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Chart: real time-axis + built-in pinch-zoom/pan (see ui/GlucoseChart.kt)
    // instead of a hand-rolled Canvas chart. View-based (needs an AndroidView
    // wrapper in Compose), but a very mature, stable, well-documented API —
    // preferred here over a newer Compose-native charting library for
    // exactly that stability, per the "least code-sensitive" choice.
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // 31/07/2026 (editor) — CareSens Air koppel-stap 1/4: de sensor draagt
    // een GS1-barcode i.p.v. dat 'm via een BLE-scanlijst gevonden wordt,
    // zie ui/CareSensAirScanScreen.kt. Google's kant-en-klare code-scanner
    // (i.p.v. zelf een CameraX-preview + ML Kit-detector optuigen) vraagt
    // GEEN camera-runtime-permissie aan DEZE app — het scannen gebeurt in
    // een geïsoleerd Play Services-proces, alleen het resultaat komt terug.
    // minSdk 26 hier ruim boven de vereiste 23.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // 17/08/2026 (editor, RONDE 112) — Dexcom G7/ONE+'s EC-J-PAKE-
    // koppelhandshake (sensor/dexcomg7/DexcomG7Crypto.kt), rechtstreekse
    // klasse-aanroepen (NOOIT via JCE-providerregistratie — zie dat
    // bestand's kdoc voor de Android-"BC"-provider-valkuil die dit bewust
    // vermijdt). Zelfde artefacten/versie als xDrip+'s eigen `libkeks`-
    // module (`uploads/xDrip-2026.08.08.zip`), waar dit van geport is.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
