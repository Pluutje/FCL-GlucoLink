package com.fclglucolink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 30/07/2026 (editor) — dark-only, regardless of the device's system-wide
// light/dark setting. Previously this followed isSystemInDarkTheme(), which
// silently fell back to a plain Material3 light scheme on a phone set to
// light mode — that's the pale lavender look from the earlier screenshot,
// not a bug in the color values themselves. Decided (with the user) to keep
// this app dark-only, matching AAPS's own look, rather than maintain a
// second light palette nobody asked for.
// 30/07/2026 (editor, na feedback) — surfaceVariant hier ONTBRAK: Material3's
// Card gebruikt standaard colorScheme.surfaceVariant als achtergrondkleur,
// NIET colorScheme.surface — zonder dit expliciet te zetten viel dat terug
// op darkColorScheme()'s eigen ingebouwde paarsgrijze standaardwaarde, los
// van SurfaceDark hierboven. Precies de te lichte kaartachtergrond die
// bleef terugkomen ondanks het aanpassen van SurfaceDark zelf — de
// titelbalk (TopAppBar) gebruikt wél colorScheme.surface standaard, vandaar
// dat die kleur daar al goed stond.
// 06/08/2026 (editor, RONDE 51, na live-melding: "de knoppen [...] moeten
// echt meer het uiterlijk van een knop krijgen") — zie ButtonSurfaceDark's
// kdoc in Color.kt voor de rootcause (surfaceVariant hierboven al gelijk
// aan surface, dus knoppen die surfaceVariant als vulkleur gebruikten waren
// onzichtbaar tegen de Cards erachter). secondaryContainer/
// onSecondaryContainer stonden nog nergens expliciet gezet, vielen dus
// terug op darkColorScheme()'s eigen paarsgrijze standaardwaarde — nu
// gekoppeld aan de nieuwe, bewust duidelijk lichtere ButtonSurfaceDark, zodat
// StatusScreen.kt's HomeSecondaryButton simpelweg de normale
// MaterialTheme.colorScheme.secondaryContainer-rol kan gebruiken.
private val DarkColors = darkColorScheme(
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDark,
    primary = PrimaryGreen,
    onPrimary = OnPrimaryDark,
    error = ErrorRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextPrimary,
    secondary = TextSecondary,
    secondaryContainer = ButtonSurfaceDark,
    onSecondaryContainer = OnButtonSurfaceDark
)

@Composable
fun FCLGlucoLinkTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}

// 06/08/2026 (editor, RONDE 53) — zie ManualBackgroundLight's kdoc in
// Color.kt. Alleen `background`/`surface`/`surfaceVariant`/`onBackground`/
// `onSurface`/`onSurfaceVariant`/`secondary` expliciet overschreven (zelfde
// bewuste-minimale-aanpak als DarkColors hierboven) — alles wat hier niet
// genoemd wordt (bv. errorContainer/onErrorContainer, gebruikt door
// ManualScreen.kt's WarningCard) valt terug op Material3's eigen, voor een
// LICHT thema al goed-contrasterende standaardwaarden.
private val ManualLightColors = lightColorScheme(
    background = ManualBackgroundLight,
    surface = ManualSurfaceLight,
    surfaceVariant = ManualSurfaceLight,
    primary = PrimaryGreen,
    onBackground = ManualTextPrimary,
    onSurface = ManualTextPrimary,
    onSurfaceVariant = ManualTextPrimary,
    secondary = ManualTextSecondary
)

/**
 * 06/08/2026 (editor, RONDE 53, op verzoek: "een mooiere opmaak [...] en
 * misschien moet het wel zwarte letters op witte achtergrond (dat dan dus
 * alleen voor de manual)") — een GENEST `MaterialTheme(...)`-blok
 * (standaard Compose-patroon: alles in [content] gebruikt vanaf hier deze
 * kleuren i.p.v. de ambient FCLGlucoLinkTheme eromheen) — alleen gebruikt
 * door ManualScreen.kt/ManualTopicScreen, die er hun hele Scaffold
 * (inclusief TopAppBar) in wrappen. De rest van de app blijft ongewijzigd
 * donker; dit raakt letterlijk alleen de handleiding-schermen.
 */
@Composable
fun FCLGlucoLinkManualTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ManualLightColors,
        content = content
    )
}
