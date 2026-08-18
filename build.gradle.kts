// FCLGlucoLink — root build file.
//
// 30/07/2026 (editor) — losstaand project van FCLvNext/AndroidAPS, bewust: zie
// de toelichting in AndroidManifest.xml/README voor waarom dit GEEN AAPS-
// plugin is maar een eigen, zelfstandig installeerbare app die (net als
// Juggluco nu) alleen via de standaard xDrip-broadcast-intent met AAPS
// praat. Geen NDK/CMake op root-niveau — alleen de CareSens Air-module
// (app/src/main/cpp, zie later toe te voegen CMakeLists.txt) heeft native
// code nodig; de rest van de app is 100% Kotlin.
plugins {
    id("com.android.application") version "8.5.2" apply false
    // 30/07/2026 (editor, na build-fout) — was 1.9.24. De aparte
    // "org.jetbrains.kotlin.plugin.compose"-plugin hieronder bestaat pas
    // sinds Kotlin 2.0 (daarvoor liep de Compose-compiler via
    // composeOptions.kotlinCompilerExtensionVersion in de Android-plugin
    // zelf, geen eigen plugin-ID) — vandaar "was not found". Kotlin en de
    // compose-plugin-versie horen altijd gelijk te zijn.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // KSP i.p.v. kapt voor de Room-annotatieprocessor — sneller, en kapt is
    // toch op zijn retour. Versie-suffix (-1.0.28) moet bij de Kotlin-versie
    // hierboven passen; check https://github.com/google/ksp/releases als
    // Gradle over EEN mismatch klaagt (KSP geeft dan een duidelijke foutmelding
    // met welke suffix wél bij Kotlin 2.0.21 hoort).
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
