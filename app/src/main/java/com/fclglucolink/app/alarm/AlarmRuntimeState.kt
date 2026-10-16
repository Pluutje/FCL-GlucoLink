package com.fclglucolink.app.alarm

/**
 * 13/08/2026 (editor, RONDE 107) — welk alarmtype er nu daadwerkelijk klinkt
 * (geluid + full-screen scherm), zodat AlarmMonitor.kt niet twee alarmen
 * tegelijk kan starten (zie AlarmEvaluator.PRIORITY_ORDER: er klinkt hooguit
 * ÉÉN alarm tegelijk) en AlarmActivity.kt weet welk type de Stop/Snooze-
 * knoppen moeten dempen. Bewust puur in-memory (niet in DataStore, in
 * tegenstelling tot AppSettings.alarmMutedUntilMs) — als het proces
 * herstart terwijl een alarm klinkt, is "opnieuw laten afgaan" de veilige
 * kant om op te falen (zie AlarmMonitor.kt's kdoc), niet "stil blijven
 * omdat er ooit iets klonk".
 */
object AlarmRuntimeState {
    @Volatile
    var currentlySoundingType: AlarmType? = null
}
