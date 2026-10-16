package com.fclglucolink.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ============================================================================
 * FCLGlucoLink — Nederlandse vertaling met taalkeuze (RONDE 189)
 * ============================================================================
 *
 * 25/09/2026 (editor, RONDE 189, op verzoek van de gebruiker namens een
 * gezinslid: "of het mogelijk is [de] app ook in het Nederlands te maken")
 * — de gebruiker's eigen voorkeur, expliciet bevestigd: GEEN vaste
 * vertaling (Engels weg) en GEEN simultane weergave van beide talen op het
 * scherm, maar een taalkeuze-knop in de instellingen, zelfde patroon als de
 * bestaande mg/dL-vs-mmol/L-knop
 * (zie AppSettings.kt's `displayUnit`/ui/Units.kt's [GlucoseUnit]). Op
 * de gebruiker's eigen scope-aanwijzing blijven technische sensor-diagnostiek-termen
 * (sensor life, battery voltage, temperature e.d. — vooral op de status-
 * schermen) bewust ONVERTAALD; prioriteit ligt bij de Manual, de
 * Settings-pagina, en de schermen waarop de gebruiker daadwerkelijk een
 * keuze maakt (pairing, sensorkeuze, kalibratie, alarmen, setup-schermen).
 *
 * **Ontwerpkeuze — plain Kotlin i.p.v. Android resource-strings
 * (`values-nl/strings.xml`).** Bijna geen bestaande tekst in deze app zit al
 * in `strings.xml` (op één restje na is alles hardcoded `Text("...")` in de
 * Composables) — resource-extractie zou dus evenveel herstructureringswerk
 * kosten als deze aanpak, maar met het extra risico van XML-escaping-fouten
 * (aanhalingstekens, `%s`-placeholders) die in deze sandbox niet met een
 * echte Gradle-build te verifiëren zijn. [tr] is een gewone `@Composable`
 * Kotlin-functie: de Engelse en Nederlandse tekst staan letterlijk naast
 * elkaar op de aanroep-plek, dus altijd in één oogopslag te controleren of
 * ze nog bij elkaar horen, zonder los sleutel-/resource-bestand.
 *
 * **Waarom een [androidx.compose.runtime.CompositionLocal] i.p.v. een
 * parameter op elk scherm.** Zie `FclGlucoLinkNavHost()`'s kdoc: die functie
 * leest de opgeslagen taalvoorkeur ÉÉN keer, bovenaan de hele navigatieboom,
 * en biedt die aan via [LocalAppLanguage] — elk scherm daaronder (elke
 * `composable { ... }`-route) kan [tr] dus rechtstreeks aanroepen zonder dat
 * de taal apart als functie-parameter doorgegeven moet worden. Dat scheelt
 * niet alleen boilerplate, het voorkomt ook dat een toekomstig nieuw scherm
 * per ongeluk vergeet de taal door te geven.
 */
enum class AppLanguage(val displayName: String) {
    ENGLISH("English"),
    DUTCH("Nederlands")
}

/** Default `ENGLISH` — zelfde "bestaande installatie ziet niets veranderen
 *  totdat de gebruiker zelf iets omzet"-conventie als [GlucoseUnit.MMOL] in
 *  ui/Units.kt. Alleen als fallback bedoeld voor een Preview/test zonder
 *  [FclGlucoLinkNavHost]'s eigen `CompositionLocalProvider` — in de
 *  daadwerkelijke app wordt dit altijd overschreven met de opgeslagen
 *  voorkeur (zie AppSettings.kt's `appLanguage`). */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

/**
 * Centrale vertaal-helper. [en] is de bestaande Engelse tekst (ongewijzigd
 * gedrag bij [AppLanguage.ENGLISH], dus geen enkel risico voor een gebruiker
 * die de knop nooit aanraakt), [nl] de Nederlandse vertaling.
 *
 * Gebruik dit voor tekst die de gebruiker daadwerkelijk LEEST om iets te
 * begrijpen of te beslissen — labels, knoppen, dialogen, hulptekst, de
 * Manual. Gebruik dit NIET voor technische sensor-diagnostiek-termen (zie
 * klasse-kdoc hierboven) — die blijven op verzoek als losse
 * `Text("...")`-literal staan, onvertaald.
 */
@Composable
fun tr(en: String, nl: String): String = if (LocalAppLanguage.current == AppLanguage.DUTCH) nl else en
