package com.fclglucolink.app.ui.theme

import androidx.compose.ui.graphics.Color

// 30/07/2026 (editor) — donker, groen accent: bewust in dezelfde sfeer als de
// AAPS-app zelf (zie de screenshots eerder in het gesprek), zodat
// FCLGlucoLink er als "bij elkaar horend" uitziet naast AAPS, zonder er
// letterlijk hetzelfde thema van over te nemen.
val BackgroundDark = Color(0xFF0E1116)
// 30/07/2026 (editor, na feedback) — was 0xFF1B2430: te groot contrast met
// de achtergrond, oogde als een aparte lichtgrijze balk i.p.v. "net iets
// lichter". Nu maar één klein stapje boven BackgroundDark.
val SurfaceDark = Color(0xFF14181E)
val PrimaryGreen = Color(0xFF4CAF50)
val OnPrimaryDark = Color(0xFF042100)
val ErrorRed = Color(0xFFCF6679)
val TextPrimary = Color(0xFFE7ECEF)
val TextSecondary = Color(0xFFA8B3BD)

// 06/08/2026 (editor, RONDE 51, na live-melding: "de knoppen settings,
// sensor en calibration moeten echt meer het uiterlijk van een knop
// krijgen") — root cause: Theme.kt zet colorScheme.surfaceVariant gelijk
// aan colorScheme.surface (beide SurfaceDark hierboven), nodig om Material3
// Card's eigen te-lichte standaard surfaceVariant te overschrijven (zie de
// kdoc daar). Bijeffect: StatusScreen.kt's secundaire knoppen (Settings/
// Sensor/Calibration) gebruikten diezelfde surfaceVariant als vulkleur —
// dus die knoppen waren letterlijk PRECIES dezelfde kleur als de Cards
// erachter, en oogden daardoor (op het dunne randje na) als vrijwel
// onzichtbaar/plat, niet als knop. Deze kleur is bewust duidelijk lichter
// dan zowel SurfaceDark als BackgroundDark — specifiek bedoeld voor dit
// soort "chip-knoppen" die zich moeten onderscheiden van de kaarten
// eromheen — gekoppeld aan de secondaryContainer-rol in Theme.kt zodat
// StatusScreen.kt gewoon de normale Material3-kleurnaam kan gebruiken i.p.v.
// deze losse constante rechtstreeks te moeten importeren.
val ButtonSurfaceDark = Color(0xFF262F3B)
val OnButtonSurfaceDark = TextPrimary

// 06/08/2026 (editor, RONDE 53, op verzoek: "misschien moet het wel zwarte
// letters op witte achtergrond (dat dan dus alleen voor de manual)") —
// bewust een APARTE, kleine set kleuren, alleen gebruikt door
// ui/theme/Theme.kt's FCLGlucoLinkManualTheme (op zijn beurt alleen
// gebruikt binnen ManualScreen.kt/ManualTopicScreen) — de rest van de app
// (StatusScreen, Settings, Calibration, ...) blijft het gewone donkere
// thema hierboven gebruiken. ManualSurfaceLight net iets grijziger dan
// zuiver wit, zodat de Card-onderwerprijen/-secties nog zichtbaar
// aftekenen tegen de achtergrond (zelfde reden als SurfaceDark hierboven
// net iets lichter is dan BackgroundDark, alleen dan in de lichte richting).
val ManualBackgroundLight = Color(0xFFFFFFFF)
val ManualSurfaceLight = Color(0xFFF2F2F2)
val ManualTextPrimary = Color(0xFF1A1A1A)
val ManualTextSecondary = Color(0xFF55565B)
