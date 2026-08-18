package com.fclglucolink.app.alarm

/**
 * ============================================================================
 * FCLGlucoLink — alarmtypes (RONDE 106-108, Fase 2: instellingen-laag + motor)
 * ============================================================================
 *
 * 13/08/2026 (editor, RONDE 106, vervolg op de meedenk-ronde bij het mg/dl-
 * vs-mmol-verzoek — "aan het inbouwen van alarmen met daarbij ook een
 * (eenvoudig model) voorspellend alarm [...] de alarmen moeten gestopt en
 * gesnoozed kunnen worden en de grenzen en de geluiden of vibratie moet
 * instelbaar zijn per alarm soort verschillend") en op het concrete verzoek
 * van deze ronde ("1 overal knop om in 1 keer alle alarmen aan/uit te
 * zetten [...] de afzonderlijke alarmen kunnen worden ingesteld maar ook
 * ieder afzonderlijk aan en uit [...] persistent") — zeven alarmtypes:
 *
 * - URGENT_LOW / LOW: BG onder een drempel (twee aparte niveaus, zodat een
 *   "let op"-melding bij bv. 70 mg/dL anders kan klinken dan een "dit is
 *   nu gevaarlijk"-melding bij bv. 55 mg/dL).
 * - HIGH / URGENT_HIGH: zelfde idee, maar dan BG boven een drempel.
 * - PREDICTIVE_LOW / PREDICTIVE_HIGH: twee losse voorspellende alarmen
 *   (RONDE 106b, op verzoek: "de predict low en predictive high moeten
 *   echter wel afzonderlijk ingesteld kunnen worden" — was in RONDE 106
 *   nog één gezamenlijk PREDICTIVE-type dat beide richtingen bewaakte,
 *   maar dat liet geen eigen voorlooptijd/geluid per richting toe). Elk
 *   gebruikt een lineaire trendextrapolatie om te waarschuwen VOORDAT een
 *   ingestelde streefwaarde bereikt wordt, met een eigen instelbare
 *   voorlooptijd.
 *   RONDE 108 (op verzoek: "Kun je de predictive alarms nog zo zetten dat
 *   daar een Bg waarde wordt ingevoerd ipv de koppeling aan low en high
 *   dat geeft meer vrijheid") — heeft dus sinds deze ronde een EIGEN,
 *   onafhankelijke drempelwaarde ([defaultThresholdMgdl] hieronder), NIET
 *   meer gekoppeld aan de eigen drempels van de LOW/HIGH-alarmen (dat was
 *   de RONDE-106b-opzet). Zo kan bv. Predictive Low op 90 mg/dL gezet worden
 *   als vroege waarschuwing, terwijl het eigenlijke Low-alarm zelf pas bij
 *   70 afgaat — twee volledig onafhankelijke instellingen, precies de
 *   gevraagde extra vrijheid. Voor alle zes drempel-/voorspellende typen
 *   geldt hetzelfde: [defaultThresholdMgdl] is puur een startwaarde, vrij
 *   aan te passen, zonder enige koppeling tussen de types onderling.
 * - STALE_DATA: geen alarm op de BG-waarde zelf, maar op het UITBLIJVEN
 *   van een verse meting — expliciet apart gevraagd ("een staledata alarm
 *   is inderdaad goed om te hebben").
 *
 * Elk type heeft z'n EIGEN aan/uit-stand, drempel/voorlooptijd (waar van
 * toepassing), geluid en trilinstelling — zie AppSettings.kt's "Alarmen"-
 * sectie voor de opslag (globaal, niet per-slot: het AAPS-actieve slot
 * bewaakt de alarmen zoals eerder bevestigd, maar de gevarengrenzen zelf
 * zijn een voorkeur van de gebruiker, geen eigenschap van een fysieke
 * sensor — zelfde redenering als displayUnit, zie ui/Units.kt).
 *
 * Geluid — RONDE 106b, op verzoek ("ik wil echter per alarmsoort een eigen
 * geluid kunnen kiezen uit de geluiden op de telefoon (zoals je ook een
 * ringtone voor de telefoon kunt kiezen) dan moet er per alarm gekozen
 * kunnen worden of het alarm direct klinkt of dat het langzaam opbouwt
 * (daarbij hoeft de opbouw tempo niet instelbaar te zijn)") — dit is nu
 * TWEE onafhankelijke instellingen per type i.p.v. het oude, gekoppelde
 * "Urgent"/"Gentle"-profiel uit RONDE 106:
 * 1. Een GELUIDSBESTAND, gekozen via Android's eigen ringtone-kiezer
 *    (RingtoneManager.ACTION_RINGTONE_PICKER — precies zoals je een
 *    beltoon voor de telefoon kiest), opgeslagen als URI-string. `null` =
 *    geen keuze gemaakt, dan geldt het systeem-standaardalarmgeluid.
 * 2. [AlarmEscalation]: direct op volle sterkte, of langzaam opbouwend.
 *    Het opbouwtempo zelf is bewust NIET instelbaar (letterlijk verzoek).
 * Beide zijn nu voor ELK type onafhankelijk te kiezen — geen vaste
 * koppeling meer tussen "welk type" en "welk geluidsgedrag" zoals in
 * RONDE 106 (toen bepaalde het type zelf al of het Urgent of Gentle was).
 * De DEFAULT-escalatie per type is nog wel zinvol vooringesteld (drempel-
 * alarmen -> direct, voorspellend/stale-data -> opbouwend), maar blijft nu
 * gewoon een startwaarde, geen vaste eigenschap van het type.
 *
 * BELANGRIJKE SCOPE-GRENS: dit bestand (samen met AppSettings.kt's
 * "Alarmen"-sectie en AlarmSettingsScreen.kt) bouwt alleen de
 * INSTELLINGEN-laag — welke alarmen aan staan, met welke drempels/geluiden.
 * De daadwerkelijke EVALUATIE (achtergrond-monitoring die de laatste BG-
 * waarden tegen deze instellingen legt), het daadwerkelijk AFSPELEN van het
 * gekozen geluid via STREAM_ALARM, de opbouw-logica, en het volledige-
 * scherm-alarmscherm met stop/snooze-knoppen zijn hier NOG NIET gebouwd —
 * dat is een aparte, latere ronde (zie README's Ronde 106/106b-secties).
 */
enum class AlarmType(
    val displayName: String,
    val description: String,
    val category: AlarmCategory,
    val defaultThresholdMgdl: Double? = null,
    val defaultLeadTimeMinutes: Int? = null,
    val defaultStaleMinutes: Int? = null,
    val defaultEscalation: AlarmEscalation
) {
    URGENT_LOW(
        displayName = "Urgent Low",
        description = "Immediate, urgent alert when BG drops below this level.",
        category = AlarmCategory.THRESHOLD_LOW,
        defaultThresholdMgdl = 55.0,
        defaultEscalation = AlarmEscalation.IMMEDIATE
    ),
    LOW(
        displayName = "Low",
        description = "Alert when BG drops below this level.",
        category = AlarmCategory.THRESHOLD_LOW,
        defaultThresholdMgdl = 70.0,
        defaultEscalation = AlarmEscalation.IMMEDIATE
    ),
    HIGH(
        displayName = "High",
        description = "Alert when BG rises above this level.",
        category = AlarmCategory.THRESHOLD_HIGH,
        defaultThresholdMgdl = 180.0,
        defaultEscalation = AlarmEscalation.IMMEDIATE
    ),
    URGENT_HIGH(
        displayName = "Urgent High",
        description = "Immediate, urgent alert when BG rises above this level.",
        category = AlarmCategory.THRESHOLD_HIGH,
        defaultThresholdMgdl = 250.0,
        defaultEscalation = AlarmEscalation.IMMEDIATE
    ),
    // 13/08/2026 (editor, RONDE 108) — defaultThresholdMgdl hier is een EIGEN,
    // onafhankelijke waarde (zie klasse-kdoc) — toevallig hetzelfde
    // startpunt als LOW's default (70.0), puur als een zinvol beginpunt;
    // de gebruiker kan 'm los van LOW aanpassen.
    PREDICTIVE_LOW(
        displayName = "Predictive Low",
        description = "Early warning before BG is projected to cross your chosen target, based on the recent trend.",
        category = AlarmCategory.PREDICTIVE_LOW,
        defaultThresholdMgdl = 70.0,
        defaultLeadTimeMinutes = 15,
        defaultEscalation = AlarmEscalation.GRADUAL
    ),
    PREDICTIVE_HIGH(
        displayName = "Predictive High",
        description = "Early warning before BG is projected to cross your chosen target, based on the recent trend.",
        category = AlarmCategory.PREDICTIVE_HIGH,
        defaultThresholdMgdl = 180.0,
        defaultLeadTimeMinutes = 15,
        defaultEscalation = AlarmEscalation.GRADUAL
    ),
    STALE_DATA(
        displayName = "Stale data",
        description = "Alert when no new sensor reading has come in for this long.",
        category = AlarmCategory.STALE_DATA,
        defaultStaleMinutes = 20,
        defaultEscalation = AlarmEscalation.GRADUAL
    )
}

/**
 * Bepaalt welke instelling(en) relevant zijn voor een [AlarmType] —
 * AlarmSettingsScreen.kt gebruikt dit om per type de juiste detail-UI te
 * tonen (drempel vs. voorlooptijd vs. stale-minuten), i.p.v. een aparte
 * boolean-vlag per instellingsoort.
 */
enum class AlarmCategory {
    THRESHOLD_LOW,
    THRESHOLD_HIGH,
    PREDICTIVE_LOW,
    PREDICTIVE_HIGH,
    STALE_DATA
}

/**
 * 13/08/2026 (editor, RONDE 106b) — zie [AlarmType]'s klasse-kdoc
 * ("Geluid"-alinea) voor de volledige aanleiding: het geluidsBESTAND zelf
 * is nu een losse, per-type gekozen systeem-ringtone (AlarmSettingsScreen.kt),
 * dit enum regelt alleen nog HOE dat geluid wordt afgespeeld zodra het
 * alarm afgaat. GRADUAL's daadwerkelijke opbouw-logica (met welk tempo,
 * over hoeveel seconden) is nog niet geïmplementeerd — hoort bij de latere
 * alarm-EVALUATIE-motor — dit is voorlopig alleen de gekozen VOORKEUR.
 */
enum class AlarmEscalation(val displayName: String) {
    IMMEDIATE("Sounds immediately, at full volume"),
    GRADUAL("Starts quiet, gradually gets louder if ignored")
}

/**
 * 13/08/2026 (editor, RONDE 107b, op verzoek: "ik wil per alarm kunnen
 * kiezen tussen alarm of vibrate of both [...] de vibrator knop die nu
 * overal onderaan staat vervangen door alarm - vibrate - both knop") —
 * vervangt de losse `alarmVibrationEnabled`-schakelaar (Ronde 106/107, een
 * simpele aan/uit náást het altijd-aan-verondersteld geluid) door één
 * 3-standen-keuze per type: alleen geluid, alleen trilling, of beide. Zie
 * AlarmSoundPlayer.kt's `start()`: [SOUND] slaat het opzetten van de
 * Vibrator helemaal over, [VIBRATE] slaat MediaPlayer helemaal over — geen
 * van beide draait dus onnodig als de gebruiker 'm niet wil. Default
 * [BOTH] — meest opvallend, zelfde bedoeling als de oude vibratie-default
 * (AAN) plus het al-altijd-actieve geluid vóór deze ronde.
 */
enum class AlarmAlertMode(val displayName: String) {
    SOUND("Alarm"),
    VIBRATE("Vibrate"),
    BOTH("Both")
}
