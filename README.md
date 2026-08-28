# FCLGlucoLink

Losstaande, minimale sensor-naar-AAPS-koppel-app. Ontstaan uit een gesprek
over Juggluco (te groot, te veel sensoren/build-varianten voor wat editor er
zelf uit gebruikt) — zie de kdoc's door de code heen voor de volledige
achtergrond en afwegingen.

## Status (30/07/2026)

**Werkt al (gebouwd, compileert in Android Studio):**
- Project-skelet: Gradle-configuratie, manifest, Compose/Material3-thema.
- Sensor-abstractie (`SensorDriver`) + registry — de architectuur waar elke
  sensor straks tegenaan gebouwd wordt.
- `BleConnectionService` — draait de actieve sensorkoppeling los van de UI,
  met notificatie.
- `XDripBroadcaster` — de daadwerkelijke koppeling naar AAPS (standaard
  xDrip-broadcast-intent, vast, niet instelbaar — zoekt zelf op welke
  geïnstalleerde app een ontvanger heeft, geen hardcoded pakketnaam).
- Compose-UI: **statusscherm is het startscherm** (BG-curve/status, ook
  leeg), met een menu (⋮ rechtsboven) voor "sensor kiezen/wisselen" en
  "verbinding verbreken" — geen automatische doorverwijzing naar koppelen
  meer bij opstarten (30/07/2026, na feedback: dat werd als opdringerig
  ervaren en verborg de BG-simulator-optie). Sensorkeuze en koppelscherm
  (generiek BLE-scan, voor sensoren die dat straks echt zo doen) blijven
  bereikbaar via dat menu.
- Lokale opslag (Room) voor de grafiek, instellingen (DataStore) voor
  gekozen sensor + gekoppeld device-adres.
- **BG-simulator** (`SensorType.SIMULATOR`, `sensor/simulator/`) — géén
  echte sensor, kiesbaar naast de drie echte sensoren in het sensorkeuzemenu.
  Loopt door dezelfde pijplijn (opslag + xDrip-broadcast) als een echte
  sensor. Drie manieren om waarden te versturen, via `ui/SimulatorSetupScreen.kt`:
  1. **Handmatige waarde** — één BG (mmol/L) intypen en versturen, optioneel
     elke 5 min laten herhalen. Bedoeld om het exportpad naar AAPS te testen
     op een reservetelefoon met virtuele pomp, zonder dat CareSens Air al
     werkt.
  2. **Willekeurige waarden** (`RandomBgGenerator.kt`) — genereert elke stap
     een nieuwe BG t.o.v. de vorige (geen losse toevalswaarden): meestal een
     kleine trek terug naar een baseline, af en toe een maaltijdachtige
     stijging-en-daling. Voor langere connectiviteitstests zonder zelf
     steeds waarden te hoeven verzinnen.
  3. **Externe lijst, loopt continu** — een bestand kiezen met één BG-waarde
     (mmol/L) per regel, chronologische volgorde (bv. geëxporteerd uit een
     eerdere FCLvNext-probleemepisode, bv. vanuit Documenten/AAPS-analyse).
     Speelt echt op 5-min-tempo af, of versneld (5 sec/waarde) om snel door
     te lopen; begint na de laatste waarde vanzelf weer vooraan totdat je
     zelf op Stop drukt. De keuze wordt onthouden (persistable
     leesrechten op de gekozen URI) — niet elke sessie opnieuw kiezen nodig.
     Zo kan een eerder problematische BG-reeks exact herhaald worden om een
     FCLvNext-fix te valideren vóórdat die live gaat. **Let op:** voor
     doseerbeslissingen-testen altijd "echte snelheid" gebruiken, nooit
     versneld — FCLvNext's IOB/taper-logica rekent met echt verstreken
     kloktijd, dus versneld afspelen geeft vertekende doseeradviezen (prima
     wel om alleen het exportpad/connectiviteit te controleren).

**Locatierechten (naar aanleiding van editor's vraag, 30/07/2026):** het
manifest vraagt bewust GEEN "Altijd toestaan" (`ACCESS_BACKGROUND_LOCATION`)
— "Alleen tijdens gebruik van de app" is voldoende. Reden: BLE blijft actief
draaien via `BleConnectionService`, een foreground-service die altijd
gestart wordt terwijl de app op de voorgrond is (bij koppelen, of bij
opstarten als er al een koppeling bestaat) — Android's exemptie voor
foreground-services zorgt ervoor dat BLE dan blijft werken zodra de app naar
de achtergrond gaat, zonder achtergrond-locatierechten. "Altijd toestaan"
zou bovendien een zwaardere, apart aan te vragen permissie zijn (Android
biedt 'm sinds Android 11 niet eens meer aan in de eerste pop-up) zonder dat
het hier iets toevoegt. Op API 31+ is dit sowieso geen probleem meer:
`BLUETOOTH_SCAN` staat met `android:usesPermissionFlags="neverForLocation"`
in het manifest, dus daar wordt om te beginnen al geen locatierechten meer
voor gevraagd. Het "alleen tijdens gebruik"-scherm dat editor ziet hoort bij
`ACCESS_FINE_LOCATION`, dat alleen tot en met Android 11 (API 30) nog
aangevraagd wordt.

**Nog TODO, in deze volgorde (zie de taken in het gesprek):**
1. CareSens Air native dependency-analyse (welke gedeelde headers uit
   Juggluco's `share`/`calibrate`/`meter`-mappen zijn nodig).
2. Minimale CMake/JNI-module die Juggluco's beproefde `air.hpp`/`java.cpp`-
   kalibratie-engine hergebruikt (bewust NIET met de hand overgezet, zie
   kdoc bij `CareSensAirDriver.kt`).
3. CareSens Air koppelcode (Kotlin) die op die native module aansluit.
   **Correctie (30/07/2026, na editor's feedback + geverifieerd in Juggluco's
   bron):** dit is GEEN BLE-scanlijst zoals het huidige generieke
   `PairingScreen.kt` — CareSens Air wordt gekoppeld door een foto van de
   QR-code op de sensor te maken (`PhotoScan`/`ZXing` → native barcode-decode
   `makeAirSensorindex(BarCode, ...)` in `sensoren.hpp`), pas daarna volgt de
   BLE-verbinding. Deze stap heeft dus een eigen camera/QR-koppelscherm nodig
   (ML Kit of ZXing), niet het generieke `SensorDriver.startPairing()`-
   contract met een `BluetoothDevice`-lijst.
4. Accu-Chek SmartGuide (pure Kotlin, eenvoudige al-gekalibreerde waarde).
5. Dexcom G7 (Kotlin pakketafhandeling + Bouncy Castle voor de EC-J-PAKE-
   koppelingshandshake i.p.v. de ruwe C++ overgezet).

`CareSensAirDriver.kt`/`SensorRegistry.kt` hebben nu duidelijke TODO's/
foutmeldingen op de plekken waar dit nog moet landen — de rest van de app
(UI, service, broadcaster, opslag) is er al klaar voor.

## Openen in Android Studio

- Het Gradle-wrapper-JAR (`gradle/wrapper/gradle-wrapper.jar`) kon niet als
  tekstbestand aangeleverd worden — Android Studio biedt bij het openen van
  een project zonder wrapper meestal vanzelf aan om die te genereren
  (of: `gradle wrapper` draaien als je zelf al een Gradle-installatie hebt).
  `gradle/wrapper/gradle-wrapper.properties` (de Gradle-VERSIE die gebruikt
  moet worden) is sinds 01/08/2026 wél meegeleverd, vastgezet op Gradle 8.7
  — nodig sinds de CareSens Air-kalibratiebrug (native/CMake-code) erbij
  kwam: zonder deze pin koos Android Studio zelf een te nieuwe Gradle-versie
  die niet meer compatibel is met hoe AGP 8.5.2 CMake-projecten configureert
  (`NoSuchMethodError` op `Project.exec(Action)` bij het syncen). Als
  Android Studio ondanks dit bestand toch een andere versie blijkt te
  gebruiken: Preferences/Settings → Build, Execution, Deployment → Gradle →
  Gradle-versie handmatig op 8.7 zetten.
- Er is geen echt app-icoon meegeleverd, alleen een simpel tijdelijk vector-
  icoon zodat het project meteen bouwt — vervang gerust via Android Studio's
  Image Asset Studio.
- `applicationId`/package: `com.fclglucolink.app` (30/07/2026: hernoemd vanaf
  `com.ecko.fclglucolink` — geen persoonsnaam meer in de package/mapstructuur).

## UI-stijl (30/07/2026, na feedback)

- **Alleen dark theme** — volgt niet langer het systeembrede licht/donker-
  thema (`isSystemInDarkTheme()` viel op een lichte telefoon terug op een
  kale Material3-lichtschema, vandaar de lichte lavendelkleurige eerdere
  screenshot — geen bug in de kleurwaarden zelf, gewoon nooit "dark-only"
  afgedwongen). Zie `ui/theme/Theme.kt`.
- **Volledig Engelstalige UI** — alle zichtbare teksten (knoppen, labels,
  schermtitels, foutmeldingen). Kdoc/commentaar en dit README blijven
  Nederlands (developer-facing, geen "UI").
- **Grafiek vervangen door MPAndroidChart** (`ui/GlucoseChart.kt`) i.p.v. de
  eigen Canvas-tekening: echte tijd-as (de lijn matcht nu daadwerkelijk het
  "laatste 6 uur"-label i.p.v. altijd de volle breedte te vullen ongeacht
  tijdspanne) plus ingebouwd pinch-zoom en schuiven — vandaar de nieuwe
  JitPack-repository in `settings.gradle.kts` en de MPAndroidChart-
  dependency in `app/build.gradle.kts`.
- **30/07/2026, na tweede feedbackronde:**
  - Kaartachtergrond (`SurfaceDark`) was te licht t.o.v. de zwarte
    achtergrond — nu maar één klein stapje lichter (`0xFF14181E` i.p.v.
    `0xFF1B2430`).
  - Y-as staat nu standaard op 2-12 mmol/L (was 3-20) met een echte
    GEVULDE groene band tussen 4 en 10 (was: twee losse gestreepte lijnen
    op 3.9/10.0) — via een tweede, onzichtbare `LineDataSet` met
    `setDrawFilled` + een `IFillFormatter` die vast 4 als ondergrens
    teruggeeft (MPAndroidChart heeft geen directe "vul tussen twee
    Y-waarden"-optie).
  - X-as toont nu standaard een vast venster van 4 uur met de laatste
    meting tegen de rechterkant, ook bij weinig data (bv. 2 metingen) —
    eerder trok de as zich dan alsnog samen op die paar punten, omdat
    `axisMinimum`/`axisMaximum` nooit expliciet gezet waren. Blijft
    zoombaar/schuifbaar (alleen horizontaal, de Y-as staat nu bewust
    vast). Metingen ouder dan 4 uur staan wel in de dataset maar zijn
    buiten dat venster niet bereikbaar via schuiven.
  - Kotlin-property-vorm (`highlightEnabled = false` e.d.) bleek voor
    sommige MPAndroidChart-setters niet op te lossen ("Unresolved
    reference") — overal in `GlucoseChart.kt` nu expliciete setter-
    aanroepen (`setHighlightEnabled(false)` e.d.) i.p.v. de property-vorm.
- **30/07/2026, na derde feedbackronde:**
  - Kaartachtergrond bleek in de praktijk lichter dan de ingestelde
    `SurfaceDark`-kleurwaarde deed vermoeden — Material3's `Card` past
    standaard een "tonal elevation"-overlay toe (mengt wat primary-groen
    door de container heen). Beide kaarten op `StatusScreen.kt` hebben nu
    `elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)`,
    zodat de rauwe kleurwaarde ongewijzigd getekend wordt.
  - "Just now"/"X minutes ago" werd alleen herberekend bij een nieuwe
    meting, niet doordat de klok doortikt — leek daardoor bevroren.
    `StatusScreen.kt` heeft nu een losse tick-state (elke 30s) die dat
    stukje UI laat herberekenen.
  - Onderzocht (geen codefout gevonden): SimulatorDriver/
    BleConnectionService/GlucoseReadingStore's dataflow ziet er correct
    uit (Room's Flow triggert vanzelf op elke insert, de simulator-loop
    gebruikt gewoon `delay(intervalMs)`) — het "ververst niet meer om de
    5 minuten"-gevoel kwam vermoedelijk van dezelfde bevroren-tekst-bug
    hierboven. Als na deze fix blijkt dat de BG-waarde zelf ook echt niet
    om de 5 minuten verandert, is een logcat-opname nodig om verder te
    zoeken.
  - Tijdlabels onder de grafiek stonden op willekeurige minuten (17:23,
    17:43, …) i.p.v. ronde tijden, omdat het ankerpunt voor de X-as-
    waarden gewoon de vroegste meting was. Nu afgerond naar het begin van
    het uur, plus een vaste stapgrootte (10 min ingezoomd, 30 min op het
    standaardvenster, 60 min verder uitgezoomd) die een
    `OnChartGestureListener` bijhoudt tijdens zoomen/schuiven.
- **30/07/2026, na vijfde feedbackronde — belangrijke betrouwbaarheidsfix:**
  bij langere achtergrond-tests (bv. terwijl AAPS op de voorgrond draaide)
  bleek de simulator op een gegeven moment te stoppen met data leveren aan
  AAPS, terwijl de rest van de app (tijd-tekst) nog wel gewoon leek te
  werken. Root cause: als Android het app-proces een keer stopt
  (geheugendruk, of op sommige toestellen agressief batterijbeheer ondanks
  de foreground-service-status) en START_STICKY de service daarna herstart,
  komt er een VERSE `SimulatorDriver` te staan die keurig naar commando's
  luistert — maar het "genereer willekeurige/afgesproken waarden"-commando
  was een eenmalig signaal vanuit het setup-scherm (een `SharedFlow` zonder
  replay-geheugen), dus komt dat na een herstart nooit opnieuw, en blijft de
  simulator stil hangen totdat iemand handmatig het setup-scherm heropent.
  Voor een sensor die 15 dagen onbeheerd moet doorlopen is dat een serieuze
  bug. Oplossing, drie nieuwe/gewijzigde stukken:
  1. **`PersistedSimulatorMode`** (`sensor/simulator/SimulatorControlBridge.kt`)
     — een klein "wat was er actief"-vlaggetje (Repeat/RandomWalk/
     ListReplay/None), opgeslagen in `AppSettings` (DataStore, overleeft
     proces-herstart) en bijgewerkt telkens als de gebruiker in
     `SimulatorSetupScreen.kt` een modus start of op Stop drukt.
  2. **`BleConnectionService.kt`** leest dit vlaggetje na elke
     `driver.connect()` (nieuwe `resumeSimulatorIfNeeded()`) en stuurt zo
     nodig automatisch het bijbehorende commando opnieuw naar
     `SimulatorControlBridge` — zonder dat het setup-scherm open hoeft te
     staan. Voor lijst-afspelen worden de waarden dan opnieuw uit het
     opgeslagen bestand gelezen (nieuwe gedeelde helper
     `sensor/simulator/SimulatorListFile.kt`, gebruikt door zowel het
     setup-scherm als deze hervat-logica).
  2. **Batterijbeheer-uitzondering**: een foreground-service met zichtbare
     notificatie hoort op kale Android al vrijgesteld te zijn van Doze/App
     Standby, maar sommige fabrikanten (Samsung/Xiaomi/Huawei e.d.) killen
     achtergrond-apps daarbovenop alsnog via eigen batterijbeheer — een
     bekend, veelvoorkomend probleem bij dit soort continu-draaiende
     sensor-apps (ook Juggluco/xDrip vragen hier expliciet om). Nieuwe
     menu-optie "Allow background running" (alleen zichtbaar zolang het nog
     niet aan staat) stuurt naar Android's officiële systeemscherm
     (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) om dat handmatig te
     bevestigen — vereist `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in het
     manifest, puur een verzoek, geen geforceerd gedrag.

  **Let op — nog niet volledig gedekt:** deze fix lost het "commando kwijt
  na herstart"-probleem op, maar garandeert niet dat Android de service
  NOOIT meer stopt — dat hangt af van toestel/fabrikant-instellingen. Zet
  daarom sowieso de nieuwe "Allow background running"-optie aan, en
  controleer op Samsung-toestellen ook los de instelling onder Instellingen
  → Apps → FCLGlucoLink → Batterij ("Onbeperkt" i.p.v. "Geoptimaliseerd").
- **30/07/2026, na zesde feedbackronde — kritieke bugfix + twee UX-fixes:**
  - **3x dezelfde meting op exact hetzelfde tijdstip** (zichtbaar in zowel de
    grafiek als xDrip+'s BG-geschiedenis in AAPS): `BleConnectionService.
    onStartCommand()` wordt door Android bij ELKE aanroep van
    `startService()`/`startForegroundService()` opnieuw uitgevoerd, ook als
    de service al draaide — zowel MainActivity's herstart-check als het
    simulator-setup-scherm roepen die aan. De vorige versie maakte dan
    telkens een NIEUWE `SensorDriver` aan zonder de vorige eerst af te
    breken; alle nog levende driver-instanties luisterden naar dezelfde
    `SimulatorControlBridge.commands` en schreven dus onafhankelijk van
    elkaar naar opslag + AAPS-broadcast zodra er één commando binnenkwam —
    verergerd doordat de vijfde-ronde-fix (automatisch hervatten na een
    herstart) dat commando nu ook actief herhaalt bij elke connect-poging.
    Fix: een `connectionJob` bijgehouden en bij elke `onStartCommand` eerst
    de vorige driver + collectors volledig afgebroken vóór een nieuwe wordt
    opgezet — op elk moment hoogstens één actieve driver.
  - **Batterij-uitzondering niet meer herhaaldelijk vragen**: Android staat
    geen volledig stille/geforceerde uitzondering toe (het systeemscherm
    vereist altijd een tik van de gebruiker, dat kan een app niet omzeilen)
    — het dichtstbijzijnde alternatief is nu geïmplementeerd: MainActivity
    opent dat systeemscherm automatisch, maar dan nog maar ÉÉN KEER OOIT
    (bijgehouden via `AppSettings.batteryOptimizationPrompted`), in plaats
    van de eerdere terugkerende menu-optie.
  - **Trendpijltjes uniform, AAPS-stijl**: de tekstuele Unicode-pijltjes
    (↓↓/↓/↘/→/↗/↑/↑↑) bleken op dit toestel deels als gekleurde emoji
    gerenderd te worden i.p.v. platte tekst, vandaar het rommelige
    uiterlijk. Vervangen door één vector-icoon (`Icons.Filled.ArrowUpward`)
    dat per trendtier geroteerd wordt — gegarandeerd consistent, en dat is
    ook hoe AAPS zijn eigen trendpijl tekent (één drawable, geroteerd i.p.v.
    losse plaatjes per richting). Alleen de twee uiterste standen (snel
    stijgend/dalend) gebruiken een apart dubbel-pijl-icoon.
- **30/07/2026, na vierde feedbackronde:** kaartachtergrond bleef te licht
  ondanks `elevation = 0.dp` — de echte oorzaak was dat Material3's `Card`
  standaard `colorScheme.surfaceVariant` gebruikt als achtergrondkleur,
  NIET `colorScheme.surface`. `surfaceVariant` stond nergens expliciet op
  `SurfaceDark` (zie `Theme.kt`), dus viel dat terug op Material3's eigen
  ingebouwde paarsgrijze standaardwaarde — los van alle eerdere
  `SurfaceDark`-aanpassingen. De titelbalk (`TopAppBar`) gebruikt wél
  `colorScheme.surface` standaard, vandaar dat die kleur daar al goed
  stond terwijl de kaarten nog te licht bleven. Nu `surfaceVariant =
  SurfaceDark` in `Theme.kt`, plus expliciete `containerColor` op beide
  kaarten in `StatusScreen.kt` als extra zekerheid.

- **30/07/2026, na zevende feedbackronde — weergave zoals in AAPS:**
  - **BG-weergave nu een ring, zoals in AAPS**: het platte "getal + los
    pijltje"-blok in `StatusScreen.kt` is vervangen door `BgRingDisplay`: een
    gekleurde cirkel (groen binnen 4-10 mmol/L, amber tot 3-14, anders rood
    — dezelfde grens als de groene band in de grafiek) met de delta
    bovenin, de BG-waarde in het midden en "Xm ago" onderin, plus de
    trendpijl als een "vlag" die net tegen de ring aan geplakt lijkt
    (`Modifier.offset`, niet `padding` — die laatste staat geen negatieve
    waarden toe). Bewust NIET gedaan: AAPS' voortschrijdende boog die laat
    zien hoe "vers" een meting is — dat vereist eigen Canvas-boogtekenwerk
    waarvan ik het resultaat hier niet kan voorvertonen, dus de ring is nu
    gewoon effen gekleurd. Ook de trend-"vlag" is een geroteerd
    driehoek-icoon (`PlayArrow`) i.p.v. AAPS' exacte vlagvorm, om dezelfde
    reden.
  - **"Sensor started" verhuisd naar een apart infoblok**: stond eerder
    los onderin de BG-kaart, nu samen met sensortype, status en (indien
    bekend) het gekoppelde device-adres in een nieuw `SensorInfoBlock`
    eronder. Sensor-nummer en einddatum staan er ook bij, maar het
    datamodel (`GlucoseReading`) heeft nog geen vaste-levensduur-veld per
    sensortype — dat kan pas zinvol ingevuld worden zodra een sensor met
    een echte, vaste looptijd (CareSens Air, G7, Accu-Chek) daadwerkelijk
    is aangesloten. Tot die tijd toont "End date" een "—"-plaatshouder.
  - Tijdnotatie "Xm ago" (i.p.v. "Just now"/"X minutes ago") sluit nu
    letterlijk aan bij hoe AAPS dat zelf toont.

- **30/07/2026, na achtste feedbackronde — twee bugs uit de nieuwe ring-weergave:**
  - **"Sensor start" leek te resetten bij wisselen tussen AAPS en
    FCLGlucoLink**: `sensorStartedAtMs` hoorde bij het driver-OBJECT (bv.
    SimulatorDriver's eigen `System.currentTimeMillis()`-veld), en dat
    object wordt opnieuw aangemaakt bij elke `BleConnectionService.
    onStartCommand()`-aanroep — óók als die aanroep alleen kwam doordat
    Android de (backgrounded) MainActivity had weggegooid en later
    opnieuw aanmaakte terwijl het proces + de foreground service gewoon
    doordraaiden. Twee fixes: (1) `onStartCommand()` breekt de actieve
    verbinding nu alleen nog af als sensor+device écht anders zijn dan wat
    al actief is — een redundante aanroep voor exact dezelfde sensor doet
    nu niets; (2) `sensorStartedAtMs` staat niet meer in het driver-object
    maar in `AppSettings` (`getOrInitSensorStartedAtMs()`), gekoppeld aan
    de gekozen sensor zelf, en overleeft dus zowel een overbodige als een
    echte herverbinding — wordt alleen gewist zodra je via het menu een
    ANDERE sensor kiest.
  - **Trendpijl (driehoek tegen de ring) wees de verkeerde kant op**: de
    vorige drempelwaarden waren omgedraaid — negatieve `trendMgdlPerMin`
    (dalend, per `SensorDriver.kt`'s eigen conventie) werd als "Rising"
    getoond en positief (stijgend) als "Falling". Rechtgezet in
    `TrendChevron` in `StatusScreen.kt`: positief = omhoog, negatief =
    omlaag.

- **30/07/2026, na negende feedbackronde — ring/driehoek polish:**
  - **Cirkel iets kleiner** (160dp -> 140dp).
  - **Delta en "Xm ago" nu wit** (`colorScheme.onSurface`) i.p.v. de delta in
    de bereikskleur en de tijd in grijs — alleen de grote BG-waarde blijft
    gekleurd (groen/amber/rood).
  - **Driehoek blijft nu echt tegen de ring geplakt, op 5 standen**: de
    eerdere versie gebruikte `Modifier.rotate()`, die om het MIDDEN van het
    icoon draait — bij elke hoek anders dan 0° zwaaide de rand die tegen de
    ring hoorde te zitten dus mee weg, precies het gerapporteerde probleem.
    Vervangen door `graphicsLayer(transformOrigin = TransformOrigin(0f,
    0.5f))`, die om de LINKERRAND-MIDDEN van het icoon zelf draait — dat
    punt staat (via de layout-volgorde) al precies op de rand van de
    cirkel en blijft daar nu ook staan tijdens het draaien, ongeacht de
    hoek. Vlak (0°), lichte stijging/daling (±30°), gemiddelde (±45°),
    grotere (±60°) en extreme (±90°) — allemaal dezelfde driehoek, geen
    aparte dubbele-pijl-iconen meer voor de uiterste standen.

- **30/07/2026, compile-fix — "Unresolved reference 'graphicsLayer'":** de
  vorige "draai om de linkerrand"-oplossing gebruikte `graphicsLayer(
  transformOrigin = ...)`, wat op deze build niet resolvede (mogelijk een
  dependency/toolchain-eigenaardigheid — `rotate()` uit dezelfde package
  werkt hier wel). Vervangen door een dubbelbrede, onzichtbare "pivot-doos"
  rond het icoon (icoon rechts uitgelijnd erin, doos naar links geschoven
  zodat zijn midden op de ringrand valt) die met het gewone `rotate()`
  wordt gedraaid — zelfde visuele resultaat, alleen bewezen werkende
  modifiers. Zie kdoc bij `TrendChevron` in `StatusScreen.kt`.

- **30/07/2026, na tiende feedbackronde — vier bugs:**
  - **Onregelmatige updates met scherm dicht** (soms 5 min, dan een gat van
    1-2+ uur): de simulator-lussen draaien op `delay(intervalMs)`, een
    monotone timer — geen alarm. Als de CPU in slaap valt (scherm lang uit,
    Doze/fabrikant-batterijbeheer), vuurt zo'n `delay()` pas af zodra er
    toevallig weer iets de CPU wekt, niet op het geplande moment. Een
    foreground-service-notificatie voorkomt alleen dat Android het PROCES
    stopt, niet dat de CPU tussen metingen in slaapt. Fix:
    `BleConnectionService` houdt nu een `PARTIAL_WAKE_LOCK` vast zolang hij
    draait (nieuwe `WAKE_LOCK`-permissie) — dat houdt de CPU wakker zodat
    `delay()`-timers wél op tijd afgaan. Kost meer batterij, bewuste
    afweging die past bij de eis "15 dagen onbeheerd door moeten draaien".
  - **Driehoek stond op een vaste plek en draaide alleen om zijn eigen as**
    (i.p.v. tegen de ring geplakt): de vorige "dubbelbrede pivot-doos"-truc
    bleek in de praktijk niet te werken. Vervangen door een simpelere opzet:
    de driehoek wordt EXPLICIET geplaatst (via `offset`) zodat zijn eigen
    midden al op de ringrand valt, en dan pas gedraaid met het gewone
    `rotate()` (draait om het eigen midden) — geen dubbele doos meer nodig.
  - **Kruisdraad bij aanraken blokkeerde swipen/zoomen**: de ECHTE
    zichtbare grafieklijn (`dataSet`) miste `setHighlightEnabled(false)` —
    alleen de onzichtbare band kreeg dat eerder. Nu ook op de lijn zelf
    gezet, plus `setHighlightPerTapEnabled(false)`/
    `setHighlightPerDragEnabled(false)` op de chart zelf als extra
    zekerheid.
  - **"Last 6 hours" boven de grafiek klopte niet** met het standaard
    zichtbare venster van 4 uur — titel aangepast naar "Glucose" (geen
    tijdsclaim meer die niet klopt met wat er zonder terugschuiven te zien
    is).

- **30/07/2026, na feedback "wil tot zeker 24u, liever 48u terug kunnen
  swipen" — geschiedenis uitgebreid naar 48u:** klopte, er zaten twee
  losse limieten in de weg. `GlucoseReadingStore.record()` ruimde alles
  ouder dan 24u meteen al uit de database op (nu 48u, met 1u marge = 49u
  in de opruimgrens). Los daarvan vroeg `StatusScreen.kt` sowieso maar 6u
  van die data op voor de grafiek (nu ook 48u). En de grafiek zelf zette
  `axisMinimum`/`axisMaximum` (de as-GRENZEN, niet alleen de standaard-
  weergave) vast op "laatste meting min 4 uur" — een HARDE limiet, verder
  terugswipen kon dus letterlijk niet, ongeacht wat er in de dataset stond.
  Losgekoppeld: `axisMinimum` bestrijkt nu de volle geladen data terug (tot
  48u), en de "4 uur"-grens is verplaatst naar
  `setVisibleXRangeMaximum(240f)` — dat is nu een ZOOM-limiet (nooit meer
  dan 4u tegelijk in beeld) in plaats van een pan-limiet, met
  `chart.moveViewToX(latestX)` om de standaardweergave bij de laatste
  meting te positioneren. Bij een meting elke 5 minuten is 48u ~576 rijen —
  verwaarloosbaar voor SQLite.

- **30/07/2026, na feedback "wil ook kunnen uitzoomen" + derde poging
  driehoek — twee fixes:**
  - **Uitzoomen voorbij 4 uur kon niet**: `setVisibleXRangeMaximum(240f)`
    stond vast als bijeffect van de truc om de STANDAARDweergave op 4u te
    krijgen, wat ook meteen de maximale uitzoom-breedte begrensde.
    Losgetrokken in `GlucoseChart.kt`: de max-uitzoom-grens is nu de volle
    geladen breedte (tot 48u), en de 4-uurs standaardweergave wordt apart
    afgedwongen via een expliciete `zoom()`-aanroep die toekomstig verder
    uitzoomen niet blokkeert.
  - **Driehoek nog steeds los van de ring, derde poging**: de eerste twee
    Modifier-gebaseerde pogingen (graphicsLayer, toen een dubbelbrede
    pivot-doos met offset+rotate) gaven allebei in de praktijk niet het
    bedoelde resultaat, ondanks kloppende geometrie op papier — er zit dus
    Compose Box/offset/rotate-samenstellingsgedrag in dat anders werkt dan
    aangenomen. Om niet nog een keer blind te gokken op niet-visueel-
    verifieerbaar Modifier-gedrag: de driehoek wordt nu in een `Canvas`
    getekend, met elk hoekpunt zelf via sin/cos uitgerekend t.o.v. het
    vaste aanhechtpunt op de ringrand — puur coördinatenwiskunde, geen
    impliciete laag-samenstelling meer. Zie kdoc bij `TrendChevronCanvas`
    in `StatusScreen.kt`.

- **30/07/2026, na feedback + schets ter bevestiging — vierde poging
  driehoek + betrouwbaardere 4u-standaardweergave:**
  - **Ring+driehoekje draaien nu als één star geheel**: bevestigd via een
    schets in het gesprek dat de vorige opzet (driehoekje blijft altijd op
    dezelfde plek — rechts — zitten, alleen de PUNT draait) niet was wat
    gevraagd werd. Het aanhechtpunt zelf schuift nu mee rond de rand van de
    ring naarmate de trendhoek verandert (rechts bij vlak, boven bij snel
    stijgend, onder bij snel dalend), met de BASIS van de driehoek (niet de
    punt) tegen de ring aan en de punt steeds radiaal naar buiten wijzend.
    Zie kdoc bij `TrendChevronCanvas`. De ring-Box en de Canvas kregen ook
    verticale marge (was alleen horizontaal) zodat de punt bij de 12/6-uur-
    standen niet buiten beeld valt.
  - **4u-standaardweergave onbetrouwbaar** (soms een venster van amper een
    uur i.p.v. 4): de vorige `chart.zoom(...)`-aanroep bleek niet
    betrouwbaar het gewenste resultaat te geven. Vervangen door een aanpak
    die hergebruikt wat al eerder bewezen wél goed werkte (`fitScreen()`
    toont altijd exact het huidige as-bereik): de as-grenzen tijdelijk smal
    zetten op precies het gewenste 4-uursvenster, `fitScreen()` aanroepen,
    en daarna de as-grenzen weer verbreden naar de volle 48u — de net
    ingestelde weergave blijft daarbij staan.

- **31/07/2026, na feedback (ronde 14) — 4u-standaardweergave écht hersteld
  + ring/driehoek-polijsting:**
  - **4u-standaardweergave weer kapot na de vorige "smal-dan-breed"-truc**:
    het idee was om de as-grenzen tijdelijk smal te zetten, `fitScreen()`
    aan te roepen, en daarna weer te verbreden — bleek in de praktijk niet
    te werken (schermafbeeldingen toonden een venster van ~13 uur i.p.v.
    4). Vermoedelijke oorzaak: MPAndroidChart leest `axisMinimum`/
    `axisMaximum` kennelijk live opnieuw in bij `invalidate()`, waardoor het
    weer verbreden van de as-ondergrens ná `fitScreen()` de zojuist
    ingestelde smalle weergave alsnog tenietdoet. Teruggegaan naar het
    mechanisme dat in een eerdere versie AANTOONBAAR wél betrouwbaar werkte:
    `setVisibleXRangeMaximum(240f)` + `chart.moveViewToX(latestX)`, bij elke
    nieuwe meting opnieuw toegepast. Het nadeel daarvan (uitzoomen voorbij
    4u geblokkeerd) wordt nu apart opgelost in de gesture-listener: zodra de
    gebruiker actief een zoom-gebaar maakt, wordt de cap tijdelijk verruimd
    naar het volledige geladen bereik; bij de eerstvolgende nieuwe meting
    zet het update-blok 'm vanzelf weer terug op 4u.
  - **Ring iets kleiner**: 140dp → 120dp, chevron-marge 36dp → 30dp.
  - **Zwarte kier tussen ring en driehoekbasis**: het aanhechtpunt van de
    driehoek zat exact op de rand van de ring, net buiten de 6dp-brede
    gekleurde rand. De straal van het aanhechtpunt is nu 3dp naar binnen
    getrokken, zodat de basis van de driehoek in de rand overlapt.
  - **Driehoekverhouding**: basis was gelijk aan de hoogte (26dp/26dp); nu
    hoogte 20dp en basis 28dp, dus basis merkbaar groter dan de hoogte.
  - **Rotatiehoek klopte niet met de getoonde delta** (bij +0,2 mmol al
    45° i.p.v. de verwachte 30°): de hoek werd gestuurd door
    `trendMgdlPerMin` (de sensor-driver's eigen per-MINUUT-genormaliseerde
    helling), een andere grootheid dan de getoonde delta-tekst (het rauwe
    mmol-verschil met de vorige meting) — bij de simulator, die vaker dan
    elke 5 minuten een meting lijkt te geven, versterkt die normalisatie
    een klein delta tot een grote hoek. De hoek wordt nu gestuurd door
    dezelfde delta (in mmol) als de tekst, met nieuwe drempels (0,1/0,3/
    0,5/0,8 mmol voor 30°/45°/60°/90°) zodat +0,2 mmol op 30° uitkomt.

- **31/07/2026, na feedback (ronde 15) — 4u-standaardweergave écht
  hersteld (tweede poging), simulator-interval versimpeld, testbestand met
  glucosecurve, Bg-lijnkleur per waarde:**
  - **4u-standaardweergave nóg steeds kapot na de vorige poging**: de
    `setVisibleXRangeMaximum(240f)` + `moveViewToX(latestX)`-combinatie
    (die eerder als "bewezen betrouwbaar" gold) bleek dat toch niet te
    zijn — `setVisibleXRangeMaximum` zet alleen een ondergrens op de
    zoom-schaal die pas bij de eerstvolgende klaartekenbeurt wordt
    afgedwongen, en dat teruggeklem-moment bleek niet betrouwbaar op
    `moveViewToX()`'s eigen berekening aan te sluiten. Nu een
    deterministische aanpak: `chart.fitScreen()` (reset de zoom/pan-matrix
    naar een vast bekend startpunt — schaal=1, dus het volledige geladen
    as-bereik in beeld, met `axisMinimum`/`axisMaximum` al op hun
    definitieve 48u-brede waarden), en pas daarna `chart.zoom(volleBereik
    / 240f, 1f, laatsteMeting, 0f)` — een absolute inzoomfactor vanaf dat
    zojuist bekende startpunt, i.p.v. vanaf een onbekende eerder
    ingestelde zoomstand (zoals een nog eerdere, ook gefaalde poging met
    `zoom()` deed). De permanente `setVisibleXRangeMaximum`-cap en de
    bijbehorende gesture-listener-uitzondering zijn verwijderd — niet meer
    nodig, `axisMinimum`/`axisMaximum` zijn nu de enige grens op hoe ver
    uitgezoomd kan worden.
  - **Simulator-interval versimpeld**: de "Accelerated"-snelheid voor
    zowel Random values als External list was 5 seconden, nu 1 minuut
    (real-time-optie blijft 5 minuten, standaard geselecteerd).
  - **Nieuw testbestand met een glucosecurve** (`glucose_test_curve.txt`,
    los aangeleverd, te kiezen via "External list"): begint op 5 mmol/L
    (2x stabiel), loopt vloeiend op naar 12 (2x stabiel op de top), zakt
    weer naar 3, en loopt weer op tot aansluitend bij de eerste 5,0 (voor
    een naadloze lus bij het herhalen). De op- en neergaande stukken
    doorlopen bewust alle delta-drempels die de driehoek-rotatiehoek
    bepalen (±0,1/±0,3/±0,5/±0,8 mmol per meting), zodat alle 5
    hoekstanden (0/30/45/60/90°) in beide richtingen te zien zijn.
  - **Bg-lijnkleur klopte niet**: de lijn (en de bolletjes erop) hadden
    altijd de vaste primary-kleur, ongeacht de waarde. Nu per meetpunt
    gekleurd: rood onder 4 mmol/L, groen 4-10, geel/amber boven 10 —
    dezelfde grenzen als de gevulde band en als `bgRangeColor()` in
    `StatusScreen.kt`.

- **31/07/2026, na een Android Studio-update — build faalt op
  `:app:lintVitalAnalyzeRelease` met "Already disposed: MessageBus":**
  de stacktrace zit volledig in AGP/IntelliJ's eigen lint-tooling
  (`com.intellij.*`, `org.jetbrains.uast.*`, `UastEnvironment`-opruiming),
  geen regel projectcode erin — een bekend AGP-lint-bugje dat na een
  Studio-update (nieuwere gebundelde JDK/AGP) naar boven komt.
  `lintVitalRelease` is een verplichte, automatische lint-check die AGP
  standaard vóór elke release-build (assembleRelease/bundleRelease)
  aanroept. In `app/build.gradle.kts` een `lint { checkReleaseBuilds =
  false; abortOnError = false }`-blok toegevoegd — schakelt precies dat
  automatische stapje uit. Gewone `./gradlew lint` (of Analyze > Inspect
  Code in Android Studio) blijft apart gewoon bruikbaar als je toch een
  keer lint-waarschuwingen wilt zien.

- **31/07/2026, na overleg over de menu-indeling (vóór verdere sensoren:
  CareSens Air/G7/Accu-Chek/FreeStyle Libre 2 & 3) — herstructurering:**
  gecheckt via twee keuzevragen wat precies gewenst was; bevestigd: (a)
  sensorbeheer als los navigatiescherm (net als PairingScreen/
  SimulatorSetupScreen), niet een popup, en (b) het ⋮-menu opent één
  "Settings"-scherm dat zowel de connectie-schakelaar als een link naar
  "About" bevat, i.p.v. Settings en About als twee losse menu-items.
  - **Nieuw `SensorManagementScreen.kt`**: alle sensor-communicatie
    (kiezen/wisselen, loskoppelen, volledige sensor-info — type/status/
    device/started/end date) samen op één scherm, geopend door op de
    sensorkaart op het statusscherm te tikken. Hergebruikt het bestaande
    `SensorInfoBlock` uit `StatusScreen.kt` (nu niet meer `private`, zodat
    een ander bestand in hetzelfde package het ook kan aanroepen — Kotlins
    top-level `private` is file-scoped, niet package-scoped).
  - **`StatusScreen.kt`**: het ⋮-menu (voorheen een dropdown met Kies/
    wissel sensor + Loskoppelen) is vervangen door een enkele knop die
    direct naar Settings navigeert. Het eerder volledig uitgeschreven
    sensor-infokaartje is vervangen door een compacte, klikbare
    `SensorSummaryCard` (sensortype + status) die naar
    SensorManagementScreen leidt.
  - **Nieuw `SettingsScreen.kt`**: een "Connection"-kaart met een
    aan/uit-schakelaar voor de xDrip-broadcast naar AAPS (nuttig om een
    andere sensor te testen terwijl een aparte xDrip-app AAPS
    daadwerkelijk aanstuurt — anders krijgt AAPS twee tegenstrijdige
    bronnen tegelijk, dezelfde reden waarom AAPS zelf een "exporteer
    BG"-schakelaar heeft), plus een link naar "About".
  - **Nieuw `AboutScreen.kt`**: korte appomschrijving, versienummer
    (`BuildConfig.VERSION_NAME` — vereiste `buildFeatures.buildConfig =
    true` in `app/build.gradle.kts`, sinds AGP 8 niet meer automatisch
    aan), en dank aan Juggluco (de xDrip-broadcast is een Kotlin-port van
    Juggluco's `SendLikexDrip.java`, en de nog te bouwen CareSens
    Air-koppeling hergebruikt Juggluco's native kalibratiemodule).
  - **`AppSettings.kt`**: nieuw `broadcastEnabled`-veld (standaard AAN,
    ontbrekende sleutel = true — geen breaking change voor bestaande
    installaties).
  - **`BleConnectionService.kt`**: de AAPS-broadcast zelf is nu
    voorwaardelijk (`if (settings.isBroadcastEnabled())`) — lokale opslag
    voor het status-/grafiekscherm gebeurt hoe dan ook altijd door.

- **31/07/2026 — CareSens Air, stap 1/4: barcode-scan-koppeling.** Bewust in
  4 stappen gebouwd met een tussentijdse check na elke stap (zie kdoc bovenin
  `CareSensAirDriver.kt` voor het volledige plan) — dit is stap 1, de
  BLE-verbinding zelf (stap 2), de native kalibratiemodule (stap 3) en het
  samenvoegen in `CareSensAirDriver` (stap 4) volgen pas na een akkoord op
  deze stap.
  - CareSens Air koppelt NIET via de generieke "scan Bluetooth-apparaten en
    kies er één"-lijst (`PairingScreen.kt`) zoals andere sensoren: de sensor
    draagt een GS1-barcode (sensorcode/PIN/serienummer/vervaldatum) die
    eerst gescand moet worden — exact zoals Juggluco dat ook doet (bevestigd
    in Juggluco's eigen bron: `PhotoScan.java` + `sensoren.hpp`'s
    `makeAirSensorindex()`).
  - **Nieuw `sensor/caresensair/CareSensAirBarcode.kt`**: een clean-room
    parser voor het GS1 Application-Identifier-formaat (publieke UDI-
    standaard voor medische verpakking, geen Juggluco-code overgenomen —
    bewust zo gedaan om deze parser NIET onder Juggluco's GPL-3 te hoeven
    zetten, in tegenstelling tot de nog te bouwen native kalibratiemodule in
    stap 3, die dat wél moet blijven). Ondersteunt de AI's die op de CareSens
    Air-verpakking voorkomen (GTIN, productie-/vervaldatum, lot, serienummer,
    PIN, sensorcode), inclusief de veelvoorkomende scanner-eigenaardigheid
    waarbij de GS1-groepsscheider als letterlijke tekst `^]` in plaats van
    het echte controleteken binnenkomt. Los geverifieerd met een Python-
    testharnas (inclusief de `^]`-normalisatie) vóór de Kotlin-versie
    geschreven werd.
  - **Nieuw `ui/CareSensAirScanScreen.kt`**: scanscherm dat automatisch
    start bij binnenkomst, gebruikt Google's kant-en-klare
    `play-services-code-scanner` (`GmsBarcodeScanning`) i.p.v. een eigen
    CameraX-preview — vraagt daardoor GEEN camera-runtime-permissie aan deze
    app: het scannen zelf gebeurt in een geïsoleerd Play Services-proces dat
    alleen het eindresultaat teruggeeft. Toont na een geslaagde scan
    sensorcode/serienummer/vervaldatum met een "Use this sensor"/"Scan
    again"-keuze.
  - **`AppSettings.kt`**: 4 nieuwe velden voor het scanresultaat
    (sensorcode/serienummer/PIN/vervaldatum) plus `saveCareSensAirScan()`/
    `careSensAirScan`; worden net als de andere sensor-specifieke velden
    gewist zodra er een andere sensor gekozen wordt.
  - **`FclGlucoLinkNavHost.kt`**: sensorkeuze stuurt CareSens Air nu naar dit
    nieuwe scanscherm i.p.v. de generieke koppelroute; na "Use this sensor"
    wordt het scanresultaat bewaard en de sensor geselecteerd — er wordt
    bewust NOG GEEN BLE-verbinding gestart (dat is stap 2, er is nog geen
    BLE-apparaatadres bekend).
  - **`SensorManagementScreen.kt`**: toont nu de echte vervaldatum uit de
    barcode-scan i.p.v. de vaste "—"-plaatshouder, zolang CareSens Air de
    geselecteerde sensor is.
  - **`SensorSelectionScreen.kt`**: CareSens Air-kaartje meldt nu expliciet
    "Pairs by scanning the barcode on the sensor".
  - **`app/build.gradle.kts`**: nieuwe dependency
    `play-services-code-scanner:16.1.0`; `AndroidManifest.xml` kreeg een
    optionele `meta-data` om de scanner-module vast te downloaden bij
    installatie (geen nieuwe permissies nodig).
  - Let op: na het scannen toont het sensorscherm de sensor als
    "geselecteerd" met de echte vervaldatum, maar de status blijft
    "Disconnected" — er is nog geen BLE-verbinding (stap 2). Dat is
    verwacht gedrag voor deze stap.

- **31/07/2026 — CareSens Air stap 1: bugfix na eerste praktijktest (echte
  sensor).** Eerste scan tegen een fysieke CareSens Air-sensor gaf "missing
  or malformed expiry date (AI 17)", terwijl de barcode zelf gewoon correct
  was. Root cause, gevonden via een tijdelijke debug-weergave (rauwe
  scandata + een "besturingstekens zichtbaar"-variant, toegevoegd aan het
  foutscherm van `CareSensAirScanScreen.kt` in dit rondje): Google's
  code-scanner geeft bij deze GS1-DataMatrix-code een GS-scheidingsteken
  (ASCII 0x1D) VÓÓR de allereerste AI-code terug (vermoedelijk de FNC1-vlag
  die aangeeft "dit is GS1-data"). `parseGs1Barcode()` in
  `CareSensAirBarcode.kt` hield alleen rekening met een GS ná een
  variabele-lengte-waarde, niet met een leidend GS-teken vóór de eerste
  AI — daardoor struikelde de parser meteen op positie 0 en bleef de HELE
  barcode ongelezen. Fix: leidende (en eventuele dubbele) GS-tekens worden
  nu overgeslagen vóórdat er een AI-code gezocht wordt. Geverifieerd tegen
  de exacte, echte scandata van Eckos sensor (alle velden — GTIN,
  vervaldatum, serienummer, PIN, sensorcode — parsen nu correct). De
  debug-weergave in het foutscherm blijft voorlopig staan (nuttig mocht een
  ander barcode-formaat nog een keer afwijken) maar is niet meer nodig voor
  déze bug.

- **31/07/2026 — CareSens Air stap 1: tweede bugfix (na succesvolle scan).**
  Na een geslaagde scan liet het statusscherm nog "Connected (BG simulator
  (testing))" zien i.p.v. "Not connected" — de oude BG-simulator-service
  bleef gewoon doordraaien omdat `onScanned` in `FclGlucoLinkNavHost.kt`
  alleen `settings.setSelectedSensor()`/`saveCareSensAirScan()` aanriep en,
  anders dan bij "Disconnect" op `SensorManagementScreen`, nergens de
  actieve service stopte of `ConnectionStatusBridge` terugzette. Fix:
  `onScanned` doet nu dezelfde drie stappen als "Disconnect"
  (`stopBleConnectionService()`, `ConnectionStatusBridge.update(Disconnected)`,
  `settings.clearDeviceAddress()`) vóórdat de nieuwe sensor bewaard wordt.
  Alleen relevant voor CareSens Air's koppel-stap 1 — de andere routes
  (simulator, generieke BLE-koppeling) hadden dit al goed doordat
  `startBleConnectionService()` daar de vorige driver zelf al opruimt.

- **31/07/2026 — CareSens Air stap 2/4: de echte BLE-verbinding.** Belangrijke
  correctie op de eerdere aanname in `CareSensAirDriver.kt`'s kdoc: er blijkt
  GEEN native kalibratie-module nodig. Verder onderzoek in Juggluco's bron
  wees uit dat de "air.hpp"-native-module (waar eerder naar verwezen werd)
  bij Juggluco's Freestyle-Libre-2/3-koppelpad hoort, NIET bij CareSens Air.
  CareSens Air (fabrikant i-SENS, zie het sensor-etiket) blijkt via
  `GlucoseMeterGatt.java`/`meter/java.cpp` te lopen — grotendeels de
  STANDAARD, publieke Bluetooth SIG "Glucose Profile" (service 0x1808,
  karakteristieken 0x2A18 metingen + 0x2A52 "Record Access Control Point",
  IEEE-11073-16-bit-SFLOAT-getallen). Veel minder GPL-voetafdruk dan gedacht:
  alleen een 4-byte commando-voorvoegsel voor de "CareSense time sync"-stap
  (niet-standaard karakteristiek 0xFFF1) is echt i-SENS-eigen/reverse-
  engineered door Juggluco, de rest is clean-room uit de publieke spec.
  - **Nieuw `sensor/caresensair/CareSensAirGattProtocol.kt`**: pure
    protocol-functies (geen Android-afhankelijkheden, dus makkelijk apart te
    verifiëren) — SFLOAT-decodering, de Glucose Measurement-byte-lay-out,
    het RACP-opvraagcommando (incrementeel: "vanaf sequentienummer X", zie
    hieronder), het RACP-antwoord, en het "CareSense time sync"-commando.
    Losse Python-simulatie van dezelfde logica gebruikt om de scaling-
    formules en byte-offsets te verifiëren vóór de Kotlin-versie geschreven
    werd (zelfde aanpak als bij de GS1-barcodeparser in stap 1).
  - **`CareSensAirDriver.kt` herschreven**: `startPairing()` scant nu
    daadwerkelijk naar BLE-apparaten (gefilterd op de standaard Glucose
    Service, geen ongefilterde lijst) — koppelen zelf hergebruikt gewoon het
    bestaande generieke `ui/PairingScreen.kt` (de barcode-scan levert geen
    BLE-MAC-adres, dat staat niet op het etiket, dus de gebruiker kiest zelf
    uit de — al beperkte — lijst). `connect()` implementeert de volledige
    handshake als een lineaire keten van GATT-callbacks: verbinden ->
    services ontdekken -> notificaties aan op de tijd-karakteristiek ->
    tijd-sync-commando schrijven -> notificaties aan op de meet-
    karakteristiek -> indicaties aan op de records-karakteristiek ->
    historie opvragen (alleen nieuwe records sinds de vorige koppeling) ->
    live metingen + historie komen als losse notificaties binnen, per stuk
    geparst en doorgestuurd naar `_readings`.
  - **`AppSettings.kt`**: nieuw `careSensAirNextSequence`-veld — onthoudt tot
    waar de historie al opgehaald is, zodat een herverbinding niet telkens
    ALLE ooit opgeslagen metingen opnieuw als "nieuw" naar AAPS zou sturen.
    Gewist bij het kiezen van een andere sensor, net als de rest van de
    CareSens-velden.
  - **`FclGlucoLinkNavHost.kt`**: na een geslaagde barcode-scan gaat de
    navigatie nu naar het generieke koppelscherm (`pairing/CARESENS_AIR`)
    i.p.v. direct terug naar het statusscherm — zie hierboven.
  - CareSens Air's stappenplan is hiermee compleet: geen aparte native-
    module-stap meer nodig, dus geen stap 3 in de eerdere zin — wat restte
    is stap 4 (langere-termijn robuustheid tegen bonding-edge-cases op
    verschillende toestellen, zie kdoc bij `CareSensAirDriver.kt`).

- **31/07/2026 — CareSens Air stap 2: bugfix na eerste praktijktest (crash
  bij scannen).** Eerste BLE-koppelpoging tegen de echte sensor crashte met
  `SecurityException: Need BLUETOOTH permission`, ondanks dat
  `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` al in het manifest stonden. Oorzaak:
  het simpelweg AANMAKEN van een `BluetoothAdapter`-referentie
  (`getSystemService(BluetoothManager)`/`getBluetoothLeScanner()`) loopt
  intern nog via een oudere AIDL-aanroep die de OUDE, "normale" (geen
  runtime-prompt) `android.permission.BLUETOOTH`-permissie controleert — een
  bekende Android-eigenaardigheid die ook op recentere Android-versies nog
  voor déze specifieke aanroep blijkt te gelden. Fix: `BLUETOOTH` en
  `BLUETOOTH_ADMIN` toegevoegd aan `AndroidManifest.xml` (onschadelijk om
  altijd te declareren, geen extra toestemmingsvraag aan de gebruiker).

- **31/07/2026 — CareSens Air stap 4: robuustheid (vooruitlopend gebouwd,
  nog niet tegen een fysieke sensor getest).** De eerste sensor bleek al
  actief gekoppeld aan Ecko's andere telefoon (nog een week levensduur) —
  CGM-sensoren staan doorgaans maar 1 actieve BLE-verbinding tegelijk toe,
  dus verder testen tegen déze sensor kon niet zonder de bestaande, live
  doseerlus te onderbreken. In plaats daarvan alvast twee stukken
  robuustheid gebouwd die bij de VOLGENDE sensorwissel gelijk klaarstaan:
  - **Verbindingstime-out** (`CareSensAirDriver.kt`, `CONNECT_TIMEOUT_MS` =
    25s): zonder dit bleef het koppelscherm eindeloos "Connecting…" tonen
    als de sensor niet reageert (precies wat er bij de test tegen de
    al-gekoppelde sensor gebeurde) — nu een duidelijke foutmelding die ook
    expliciet wijst op de "al aan een andere telefoon gekoppeld"-mogelijkheid.
  - **Optionele serienummer-controle**: als het gekozen BLE-apparaat de
    standaard "Serial Number String"-karakteristiek (0x2A25) aanbiedt, wordt
    die na het verbinden vergeleken met het serienummer uit de barcode-scan
    (stap 1) — vangt de vergissing af dat je per ongeluk het verkeerde
    apparaat uit de koppellijst kiest. Bewust een LOSSE substring-
    vergelijking i.p.v. exacte gelijkheid (onbekend of het precieze
    formaat/padding overeenkomt) en puur een extra veiligheidsnet: ontbreekt
    de karakteristiek, dan gaat de koppeling gewoon door zonder controle.
  - **`ui/PairingScreen.kt`**: na 15 seconden zoeken zonder resultaat
    verschijnt nu een hint die erop wijst dat de sensor mogelijk al aan een
    ander apparaat verbonden is — rechtstreeks geïnformeerd door het
    daadwerkelijke debuggen hierboven.
  Nog te doen zodra er weer een fysieke sensor beschikbaar is: dit
  daadwerkelijk verifiëren, met name of de serienummer-karakteristiek er
  überhaupt is en of de time-out/foutmeldingen goed aanvoelen in de praktijk.

- **01/08/2026 — CareSens Air stap 2: bugfix na de eerste échte live-test
  tegen hardware (BLE-scan vond de sensor nooit).** De sensor was inmiddels
  op dezelfde telefoon vrij gemaakt (Juggluco geforceerd gestopt — enkel de
  "Apparaten in de buurt"-permissie intrekken bleek NIET genoeg: dat blokkeert
  alleen nieuwe API-aanroepen, geen al openstaande GATT-verbinding, dus
  Juggluco's dienst hield de enige beschikbare verbindings-slot alsnog vast
  totdat het hele proces echt beëindigd werd). Toch bleef `PairingScreen.kt`
  eindeloos "Searching…" tonen, ook na 6+ minuten. Oorzaak gevonden:
  `startPairing()` filterde de BLE-scan op de standaard Glucose Service UUID
  (0x1808) via een `ScanFilter` — en CareSens Air blijkt die UUID niet (in
  elk geval niet herkenbaar voor Android's `ScanFilter`) in de advertentie
  zelf mee te sturen. Juggluco's eigen scherm vindt de sensor overduidelijk
  wel, en wel op NAAM ("Found CSAir 0224") — sterke aanwijzing dat Juggluco
  ongefilterd scant. Fix: `startPairing()` scant nu ook ongefilterd; de
  gebruiker kiest nog steeds zelf het juiste apparaat uit de lijst
  (naam+adres, dus "CSAir 0224" is te herkennen), en de bestaande
  serienummer-cross-check (stap 4) vangt een verkeerde keuze alsnog af.
  Kost een langere/rommeliger apparaatlijst (elk nabij BLE-apparaat, niet
  alleen glucosemeters) — geaccepteerd in ruil voor een daadwerkelijk
  werkende koppeling.
  - **`ui/StatusScreen.kt`** (`SensorInfoBlock`): toont het "Device"-adres nu
    ook al tijdens `Connecting`, niet pas na een geslaagde `Connected` — bij
    het debuggen hierboven kon niet gezien worden WELK adres de app eigenlijk
    probeerde, precies tijdens het probleem. Zelfde soort debug-transparantie
    als de ruwe-scan-info bij de barcode-scanfout in stap 1.
  - Nog niet bevestigd: of de koppeling na deze fix daadwerkelijk voorbij de
    apparaatlijst komt en de volledige handshake (inclusief de serienummer-
    vergelijking) doorloopt — dat was het volgende te testen moment.

- **01/08/2026 — CareSens Air stap 2: bugfix ronde 2, na live-test met "CSAir
  0224" bewust aangetikt (2x herhaald).** Met de scan-fix hierboven werkend
  ("CSAir 0224" zichtbaar en aantikbaar) bleven er twee nieuwe faalpatronen
  over, allebei met het BEVESTIGD juiste apparaat. Ecko's eigen Juggluco-
  broncode (GPL-3, apart aangeleverd) is dit keer letterlijk geraadpleegd
  i.p.v. alleen het eerder gemaakte onderzoek erover, om te zien wat daar
  wél/anders gebeurt:
  - **"Couldn't connect within 25 seconds"** (bestaande Android-bond nog
    intact, alleen Juggluco geforceerd gestopt): Juggluco's eigen
    `connectGatt()`-aanroep (`GlucoseMeterGatt.java`) geeft altijd expliciet
    `BluetoothDevice.TRANSPORT_LE` mee; deze driver deed dat niet — een
    bekende Android-BLE-eigenaardigheid waarbij de stack zonder die vlag de
    verkeerde transport kan kiezen, met name bij een apparaat dat al eerder
    gebonden was. Toegevoegd aan `connect()`.
  - **"This doesn't look like a CareSens Air sensor"** (na "Vergeet" in
    Juggluco, dus zonder bestaande Android-bond): de verwachte
    karakteristieken ontbraken al na de EERSTE `discoverServices()`-ronde.
    Juggluco registreert hiervoor een systeembrede `BroadcastReceiver` op
    `ACTION_BOND_STATE_CHANGED` (`BluetoothGlucoseMeter.java`) en herhaalt
    `discoverServices()` zodra de bond-status op `BOND_BONDED` springt —
    deze driver deed geen van beide. Nu toegevoegd: dezelfde ontvanger
    (`registerBondReceiver()`/`unregisterBondReceiver()`), plus als eigen,
    voorzichtige aanvulling een paar automatische herhalingen van
    `discoverServices()` zelf (met korte pauze, `MAX_SERVICE_DISCOVERY_RETRIES`
    = 4) vóórdat de harde foutmelding verschijnt, voor het geval het gewoon
    een kortstondige discovery-race was. Bewust GEEN expliciete
    `createBond()`-aanroep toegevoegd — die komt in Juggluco's bron alleen
    voor bij Libre (`AirGattCallback.java`) en Dexcom (`DexGattCallback.java`),
    niet bij het i-SENS/CareSens-pad zelf, waar Juggluco het bonden
    kennelijk aan Android's eigen automatische mechanisme overlaat (zichtbaar
    aan de bestaande `GATT_INSUFFICIENT_AUTHENTICATION`-retry op de
    records-schrijfactie, die deze driver al had).
  Nog niet bevestigd tegen echte hardware — dat is het volgende te testen
  moment.

- **01/08/2026 — CareSens Air stap 2: bugfix ronde 3, met logcat van de
  ronde-2-test.** Ecko stuurde een volledige logcat van de vorige poging
  mee. Daaruit bleek de ronde-2-aanname ("kortstondige discovery-race")
  fout: 5 losse `discoverServices()`-pogingen, allemaal binnen ~2,5
  seconden, allemaal status=0 (succes), maar nooit met de verwachte
  karakteristieken — geen race, aanhoudend afwezig. Geen enkel bonding-
  gerelateerd event zichtbaar in diezelfde periode (wel: de TRANSPORT_LE-
  fix uit ronde 2 werkte zichtbaar — de tweede connectiepoging in de logcat
  kwam nu wél tot `onClientConnectionState() status=0 connected=true`,
  waar de eerste, zonder die vlag, na 25s zonder ooit connected te zijn
  geweest afbrak).
  Herziene conclusie: de sensor toont de Glucose Service kennelijk pas NA
  een voltooide bond, en zonder bestaande bond (na "Vergeet" in Juggluco)
  start Android's stack die onderhandeling niet vanzelf — er werd nog
  niets "beschermds" gelezen/geschreven om dat impliciet te triggeren.
  - **`CareSensAirDriver.kt`**: het retry-venster fors verlengd (10 pogingen
    × 1,5s ≈ 15s i.p.v. 4 × 0,6s ≈ 2,4s, `MAX_SERVICE_DISCOVERY_RETRIES`/
    `SERVICE_DISCOVERY_RETRY_DELAY_MS`), zodat een eventuele verse bonding-
    onderhandeling ook echt de kans krijgt af te ronden binnen de bestaande
    25s-timeout. Plus alsnog een expliciete `device.createBond()`-nudge
    zodra de bond-status nog `BOND_NONE` is bij een ontbrekende
    karakteristiek — idempotent/veilig, negeert zichzelf als er al een
    bond(ing) loopt.
  Nog niet bevestigd tegen echte hardware.

- **01/08/2026 — CareSens Air stap 2: bugfix ronde 4, met een tweede
  logcat.** Ronde 3's fix veranderde niets: exact hetzelfde patroon (10
  herhaalde `discoverServices()`-pogingen over 15 volle seconden, allemaal
  status=0/succes, nooit de verwachte karakteristieken, geen zichtbare
  bonding-activiteit) — dit keer met een bond die (voor zover uit de
  logcat op te maken) al bestond, dus geen puur bonding-timingprobleem.
  Die volstrekte herhaalbaarheid — telkens hetzelfde antwoord, retries
  maken geen enkel verschil — is de bekende signatuur van Android's
  interne, PER-BLUETOOTH-ADRES GATT-servicecache: als die stale/
  onvolledig is (bv. nog van een eerdere koppeling via de officiële
  CareSens-app, Juggluco, of een eerdere FCLGlucoLink-poging op ditzelfde
  adres), geeft `discoverServices()` gewoon telkens diezelfde oude lijst
  terug, hoe vaak je 't ook probeert.
  - **`CareSensAirDriver.kt`**: nieuwe `refreshDeviceCache()`-helper —
    reflectie op het niet-publieke maar al meer dan tien jaar breed
    gebruikte `BluetoothGatt.refresh()` (Nordic's Android-BLE-Library,
    RxAndroidBle en talloze productie-apps gebruiken dezelfde truc), nu
    aangeroepen vóór de allereerste `discoverServices()`-poging na het
    verbinden. Faalt de reflectie (bv. op een toekomstige Android-versie),
    dan gebeurt er simpelweg niets — geen crash, terug bij het oude gedrag.

- **01/08/2026 — CareSens Air stap 2: ronde 4's fix TERUGGEDRAAID na
  live-test, plus koerswijziging.** De `refresh()`-fix uit ronde 4 bleek
  niet alleen niet te helpen, maar merkbaar erger te maken: beide nieuwe
  verbindingspogingen kwamen niet eens meer tot een verbinding (25s
  stilte, waar eerdere pogingen altijd wél verbonden raakten), én Juggluco
  zelf deed er daarna merkbaar langer (tot een minuut) over om de sensor
  terug op te pikken dan bij eerdere tests. Sterke aanwijzing dat deze
  niet-publieke, reflectie-gebaseerde aanroep de onderliggende Bluetooth-
  verbindings-/cachestatus breder verstoorde dan bedoeld (die is gedeeld op
  OS-niveau, niet per app) — met een reëel risico voor de live doseerlus.
  Volledig teruggedraaid: `refreshDeviceCache()` is verwijderd. De andere,
  niet-riskante fixes uit ronde 2/3 (TRANSPORT_LE, de bond-ontvanger, het
  langere retry-venster, de `createBond()`-nudge) blijven staan — die
  hebben geen enkele negatieve bijwerking laten zien.
  **Koerswijziging:** na vier rondes gokken-en-testen op precies dit ene
  probleem, en met een reëel risico voor Ecko's live doseerlus bij elke
  nieuwe test, is het volgende advies om EERST met een neutrale, gangbare
  BLE-tool (bv. "nRF Connect for Mobile" van Nordic Semiconductor, gratis,
  breed vertrouwd in de CGM-hobbyist-gemeenschap) rechtstreeks tegen
  "CSAir 0224" te verbinden en te kijken welke services/karakteristieken
  die daadwerkelijk aanbiedt — los van elke aanname in deze code of in
  Juggluco's bron. Dat geeft in één keer zekerheid (bevat het apparaat de
  standaard Glucose Service 0x1808 wel/niet, wel/niet afhankelijk van
  bond-status) i.p.v. nog een ronde giswerk met risico voor de live
  sensorkoppeling.

- **01/08/2026 — CareSens Air: grote koerscorrectie na nRF Connect-onderzoek
  + volledige herschrijving van de handshake.** Ecko heeft "nRF Connect for
  Mobile" (de neutrale BLE-tool geadviseerd in de vorige entry) tegen de
  echte sensor gebruikt en de service/karakteristiek-lijst gedeeld. Uitkomst:
  geen spoor van de standaard Bluetooth Glucose Service (0x1808) — in plaats
  daarvan drie propriëtaire services/elf karakteristieken met UUID's die
  LETTERLIJK overeenkomen met Juggluco's `AirGattCallback.java`
  (headercommentaar zegt "Freestyle Libre 2/3", maar `AppID = "csair"`
  daarin verraadt dat dit ook het CareSens Air-pad is). De eerdere aanname
  (standaard Glucose Profile, zie alle vorige rondes hierboven) was dus
  FOUT — dat verklaart in één klap waarom `discoverServices()` nooit de
  verwachte karakteristieken vond, ongeacht retries/refresh/bonding.
  Grotere ontdekking: CareSens Air stuurt geen kant-en-klare glucosewaarde
  over Bluetooth, maar RUWE elektrochemische sensordata — het omzetten
  daarvan naar mg/dL is zelf al een propriëtaire rekenstap
  (`air1_opcal4_algorithm`), niet iets dat met een simpele vaste factor kan.
  Juggluco heeft die rekenstap zelf ook niet: het laadt 'm dynamisch
  (dlopen/dlsym) uit een closed-source bestand (`libCALCULATION.so`),
  gebundeld in Juggluco's eigen apk. Ecko's Juggluco-apk (van
  sourceforge.net/projects/juggluco, arm64-build) geüpload, `libCALCULATION.so`
  eruit geëxtraheerd (656.872 bytes, geverifieerd: exporteert
  `air1_opcal4_algorithm` exact zoals verwacht) en overgenomen — dit is
  Ecko's eigen, al werkende kopie, geen nieuwe download of aanname.
  Vier nieuwe/herschreven bestanden:
  - **`app/src/main/cpp/air.hpp`** — LETTERLIJKE kopie (bestandskopie, niet
    met de hand overgetypt — voorkomt transcriptiefouten in een 300+ velden
    tellende status-struct) van Juggluco's eigen header (GPL-3). Bevat de
    exacte struct-layouts die `air1_opcal4_algorithm` verwacht.
  - **`app/src/main/cpp/caresens_wire.hpp`** — idem, het `SensorInfo`-
    draadformaat (ook letterlijk gekopieerd, uit `java.cpp`).
  - **`app/src/main/cpp/caresensair_bridge.cpp`** (NIEUW) — het eigen
    JNI-laagje: laadt `libCALCULATION.so` via dlopen/dlsym (zelfde aanpak
    als Juggluco, onafhankelijk van welke NDK-toolchain die bibliotheek ooit
    gebouwd heeft), en is verder een mirror van Juggluco's
    `airProcessData()`/`airSaveSensorInfo()`/`airSaveSensorInfo2()`/
    `airSaveStartSensor()` — MINUS Juggluco's eigen app-architectuur
    (mmap/SensorGlucoseData/askEarlier-heuristiek/mkres-dedupe). Bekende,
    gedocumenteerde vereenvoudigingen: geen CRC-verificatie op het
    kalibratieprofiel (Juggluco disconnect zelf ook pas bij twee mislukte
    crc-varianten), en historische backfill-records krijgen een minder
    precies tijdstip (raakt alleen data na een periode niet-verbonden zijn,
    niet de actuele/live waarde).
  - **`app/src/main/jniLibs/arm64-v8a/libCALCULATION.so`** (NIEUW) —
    Ecko's eigen, uit Juggluco geëxtraheerde bibliotheek. Alleen arm64-v8a
    (Ecko's telefoon-architectuur); zie `build.gradle.kts`'s `ndk.abiFilters`
    als dit ooit op een ander CPU-type moet draaien.
  - **`CareSensAirNative.kt`** (NIEUW) — dunne Kotlin/JNI-wrapper, plus
    bestandsgebaseerde persistentie van de kalibratiegeschiedenis (één
    bestand per sensor-serienummer in de app's eigen opslag) zodat een
    herstart van FCLGlucoLink niet betekent dat een sensor's opgebouwde
    kalibratiegeschiedenis kwijtraakt.
  - **`CareSensAirGattProtocol.kt`** (HERSCHREVEN) — alle oude
    standaard-Bluetooth-Glucose-Profile-code (UUID's, SFLOAT-decoder,
    RACP-opcodes) vervangen door de echte, propriëtaire CareSens Air-UUID's
    en commando's (AppID-handshake "csair", AES-versleutelde
    serienummer-authenticatie met een vaste, in Juggluco's bron gevonden
    sleutel, app-info/tijdsync/kalibratieprofiel-commando's) — 1-op-1 poort
    van `AirGattCallback.java`.
  - **`CareSensAirDriver.kt`** (VOLLEDIG HERSCHREVEN) — de GATT-
    handshake-volgorde ná verbinden is nu: serienummer + sw-revisie lezen →
    AppID-handshake → glucosedata-notificaties aanzetten → app-info-
    notificaties aanzetten → AES-handshake → (eerste keer) app-info zetten
    → kalibratieprofiel opvragen/ontvangen → tijdsync indien nodig →
    databehandeling starten. Koppel-stap 1 (barcode) en de
    verbindings-robuustheid uit eerdere rondes (TRANSPORT_LE, bond-
    ontvanger, connectie-timeout) blijven ongewijzigd geldig, alleen
    hergebruikt voor de nieuwe karakteristieken. Bewuste vereenvoudiging:
    alleen sensor-firmware ≥ "1.5" ondersteund (Ecko's sensor is een
    recente eenheid) — een oudere sensor geeft een duidelijke foutmelding
    i.p.v. een stil fout pad.
  `build.gradle.kts` uitgebreid met een minimaal `externalNativeBuild`/
  CMake-blok (eerste keer native code in dit project).
  **Nog NIET bevestigd tegen echte hardware** — dit is, net als alle
  vorige rondes, statisch geverifieerd (brace/paren-balans, geen
  compiler beschikbaar in deze sandbox) maar niet gecompileerd/getest.
  Android Studio zal bij het openen de NDK moeten downloaden (Gradle sync
  doet dat automatisch) vóórdat de app gebouwd kan worden. Dit is de EERSTE
  keer dat de kernrekenstap (ruwe data → mg/dL) daadwerkelijk de juiste,
  door de sensor zelf gebruikte code aanroept — een principieel andere,
  steviger basis dan alle eerdere rondes, die allemaal tegen het verkeerde
  protocol aan het debuggen waren.

## Waarom een aparte app, niet in FCLvNext/AAPS zelf

Zie de toelichting in `AndroidManifest.xml` — kort samengevat: AAPS
verwacht CGM-data toch al via een apart soort plugin (xDrip-achtige
broadcast), een BLE-crash hoort niet in hetzelfde proces als de dosing-loop,
en een losse APK houdt Juggluco bruikbaar als achterdeur bij een toekomstige
sensor-protocolwijziging.

## 01/08/2026 (editor) — koppellijst-filter voor sensoren met een gekende barcode/serienummer

Feature-verzoek na de eerste succesvolle build/opstart: de generieke
koppellijst (`PairingScreen.kt`) toonde tot nu toe **elk** gevonden
Bluetooth-apparaat in de buurt, ongefilterd — voor CareSens Air is dat
onnodig, want de barcode-scan (koppel-stap 1) heeft het serienummer al
vooraf vastgelegd.

- **`SensorDriver.kt`** — nieuwe optionele interfacemethode
  `buildPairingListFilter(context)`, default `null` (= geen filter, geen
  gedragsverandering voor sensoren die 'm niet overschrijven — Dexcom G7,
  Accu-Chek SmartGuide, Simulator).
- **`CareSensAirDriver.kt`** — overschrijft deze methode: een apparaat komt
  door het filter als de naam "CSAIR" bevat, óf als de naam eindigt op de
  laatste 3 of 4 cijfers van het eerder gescande serienummer (hoofdletter-
  ongevoelig).
- **`PairingScreen.kt`** — past het filter toe op de weergegeven lijst, en
  toont altijd een "Show all nearby devices"-schakelaar zodra een sensor een
  filter opgeeft — een naam-filter is een vuistregel (kan per
  firmware/regio verschillen), nooit een harde blokkade. Als er wél
  apparaten gevonden zijn maar geen enkele door het filter komt, wijst een
  aparte hint-tekst direct naar die schakelaar in plaats van de generieke
  "nog niets gevonden"-tekst te tonen.

versionCode 4, versionName `0.2.2-caresensair-pairing-filter`.

Nog steeds niet getest tegen echte hardware — de gebruiker zit middenin een
postprandiale stijging en heeft gevraagd om even te wachten met live testen.

## 01/08/2026 (editor, tweede live-test-ronde) — twee bugfixes: pairing-filter deed niets, sensor brak verbinding meteen af

De gebruiker testte de v47-build tegen de echte sensor. Logcat + drie
screenshots (koppelscherm mét/zonder "Show all"-schakelaar, en de status-
kaart met de foutmelding) leverden twee concrete, met broncode
onderbouwde bevindingen op:

- **Pairing-filter deed niets.** `CareSensAirDriver.buildPairingListFilter()`
  liet apparaten ZONDER advertentie-naam altijd door (`?: return@filter
  true`), bedoeld om de echte sensor nooit per ongeluk te verbergen. In de
  praktijk sturen veel nabije BLE-apparaten geen naam mee in hun
  advertentie, dus liet het filter bijna alle ruis alsnog door — de lijst
  werd niet korter. CareSens Air bleek in de logs altijd wél een naam mee
  te sturen ("CSAir 0224"). Fix: naamloze apparaten worden nu net als elk
  ander niet-matchend apparaat verborgen; de "Show all nearby
  devices"-schakelaar blijft de garantie dat niets definitief onbereikbaar
  wordt.
- **Verbinding brak meteen af na de eerste notificatie-descriptor-write**
  (logcat: `onClientConnectionState() status=19 connected=false`, ongeveer
  350ms na een geslaagde `onSearchComplete`/service-discovery, direct na
  `setCharacteristicNotification()` op charact22/AppID). Vergelijking met
  Juggluco's `AirGattCallback.java` (regels 194-215, 805-812) toonde een
  ontbrekende stap: Juggluco vraagt bij elke verbinding ALTIJD eerst een
  grotere ATT-MTU aan (`requestMtu(512)`) en roept `discoverServices()`
  pas aan ná een geslaagde `onMtuChanged`-callback — deze app riep
  `discoverServices()` meteen bij `STATE_CONNECTED` aan, zonder ooit een
  MTU-onderhandeling te doen (dus bleef de standaard 23-byte ATT-MTU
  gelden). Ook ontbrak een 100ms-vertraging die Juggluco zelf inlast vóór
  de allereerste notificatie-descriptor-write. Beide 1-op-1 overgenomen in
  `CareSensAirDriver.kt`:
  - `onConnectionStateChange` (STATE_CONNECTED) vraagt nu `requestMtu(512)`
    aan i.p.v. meteen `discoverServices()`.
  - Nieuwe `onMtuChanged`-override roept pas ná een geslaagde
    MTU-onderhandeling `discoverServices()` aan (en geeft een duidelijke
    foutmelding bij een mislukte onderhandeling, i.p.v. Juggluco's eigen
    stille falen).
  - `onIdentificationComplete` wacht nu 100ms vóór de eerste
    notificatie-descriptor-write (charact22/AppID), zoals Juggluco's eigen
    `afterReads()`.
  - `CONNECT_TIMEOUT_MS` 25s -> 40s: de volledige handshake (MTU, evt. een
    systeem-koppel-dialoog, AES-handshake, kalibratieprofiel-overdracht)
    kan samen meer tijd kosten dan alleen het BLE-niveau-verbinden.

  Bewust NIET aangepast: geen automatische herverbind-lus bovenop de
  bestaande logica. De logs suggereren dat de sensor niet continu
  adverteert (batterijbesparing) — de eerste koppelpoging kreeg in 25s
  helemaal geen BLE-verbindingsevent, de tweede (na "Search again") lukte
  meteen. Dat is een apart, nog onbevestigd verschijnsel t.o.v. de nu
  gefixte MTU-stap; een automatische retry-met-volledige-herverbinding zou
  kunnen interfereren met een legitiem trage handshake (bv. wachten op een
  systeem-koppel-dialoog) — bewust geen ongeteste heuristiek toegevoegd
  bovenop een al vermoede, brongecontroleerde fix. Als dit na de
  MTU-fix een terugkerend probleem blijft: apart traject.

versionCode 5, versionName `0.2.3-caresensair-connect-fix`.

**Nog steeds niet definitief bevestigd** — dit is de eerste keer dat de
verbinding daadwerkelijk tot voorbij service-discovery kwam (een
principiële stap verder dan alle eerdere rondes), maar de MTU-fix zelf is
nog niet tegen de echte sensor getest.

## 01/08/2026 (editor, derde live-test-ronde) — connectGatt() nooit meer "koud" op een los adres

Derde test: het pairing-filter werkte nu goed ("De filtering werkt nu wel",
bevestigd door de gebruiker). De verbinding zelf faalde weer, maar op een
NIEUWE manier t.o.v. de vorige ronde — deze keer geen enkel
`onConnectionStateChange`-event gedurende 30 volle seconden, gevolgd door
`status=147 connected=false` (een falen/timeout op BLE-controllerniveau,
vóórdat de app's eigen protocolcode — de net gefixte MTU-stap — ooit
bereikt werd).

Vergelijking met Juggluco's `SensorBluetooth.java`/`SuperGattCallback.java`
(methodes `checkdevice()`, `connectToActiveDevice()`, `connectDevice()`,
`getConnectDevice()`) toonde een structureel verschil: Juggluco roept
`connectGatt()` NOOIT koud aan op een los opgeslagen MAC-adres. Het
verbindt uitsluitend met een `BluetoothDevice`-object dat ZOJUIST via een
actieve BLE-scan gezien is. `CareSensAirDriver.connect()` deed tot nu toe
het tegenovergestelde: `adapter.getRemoteDevice(address)` gevolgd door
meteen `connectGatt()`, zonder eerst te checken of de sensor op dát moment
daadwerkelijk adverteert. Voor een sensor die (zoals deze, kennelijk
batterijbesparend) niet continu adverteert, is `connectGatt()` met
`autoConnect=false` op een willekeurig moment een gok — precies het
patroon dat de gebruiker zelf al vermoedde ("ik heb het idee dat de sensor
niet continu zijn signaal uitzendt... duurt soms tot een minuut voor hij
zichtbaar is").

Fix in `CareSensAirDriver.kt`: nieuwe `startConnectScan()`-functie
vervangt de rechtstreekse `connectGatt()`-aanroep in `connect()`. Deze
scant actief (dezelfde ongefilterde scan als `startPairing`) tot het
opgegeven adres langskomt, en verbindt pas dán — met het zojuist geziene
`BluetoothDevice`-object, niet een los opgehaald adres-object. De
40s-timeout (uit de vorige ronde) dekt nu het hele "wachten tot zichtbaar +
verbinden + handshake"-traject, niet alleen het verbinden. `disconnect()`
stopt deze scan netjes op als hij nog loopt.

versionCode 6, versionName `0.2.4-caresensair-scan-before-connect`.

Drie live-testrondes op rij hebben nu elk een concreet, bron-vergeleken
verschil met Juggluco blootgelegd (protocol -> kalibratiebibliotheek ->
MTU-onderhandeling -> nu: scan-vóór-connect) — geen van alle giswerk, elk
gevonden door de daadwerkelijke logcat-output te vergelijken met
Juggluco's bewezen-werkende broncode. Nog steeds niet definitief bevestigd
tegen een geslaagde eind-tot-eind meting.

## 01/08/2026 (editor, vierde live-test-ronde) — scan-vóór-connect werkt, AppID-handshake werd afgewezen door een verkeerde vlag

Vierde test: de scan-vóór-connect-fix (v49) werkte meteen goed — de sensor
werd binnen ~70ms gevonden en verbonden, MTU-onderhandeling en
service-discovery liepen vlekkeloos (`onConfigureMTU(...,251,0)`,
`onSearchComplete(...,0)`). Voor het eerst kwam de verbinding tot ná de
eerste GATT-notificatie-enable en (vermoedelijk) de eerste
protocolcommando-write. Ongeveer 600ms na het verbinden brak de verbinding
alsnog af — maar dit keer via een `disconnect()`-aanroep die de LOGS
tonen als door de APP zelf geïnitieerd (status=0 op de resulterende
state-change, "clean" disconnect, geen sensor-initiated status-19-patroon
zoals in ronde 2), gevolgd door de app's eigen retry-logica.

Vergelijking met Juggluco's `AirGattCallback.java` (regels 85-95, 160-176,
476-500) wees een concrete, tot nu toe over het hoofd geziene bug aan in
de AppID-handshake zelf (niet in de verbindingsinfrastructuur zoals de
vorige drie fixes): byte 34 van het 35-byte AppID-handshakecommando
("csair") moet 1 zijn als er voor deze sensor nog GEEN
kalibratiegeschiedenis bestaat (`Natives.airGetLast(dataptr)<=0` in
Juggluco — de Kotlin-tegenhanger is `CareSensAirNative.getLastSequence()`).
`buildAppIdHandshakeCommand()` liet deze byte altijd op 0 staan, ongeacht
of dit de allereerste koppeling met deze sensor was — voor DEZE test (de
allereerste keer dat FCLGlucoLink met deze sensor praat) claimde de app
daarmee ten onrechte een eerdere sessie te hebben, wat de sensor
kennelijk afwijst (Juggluco's eigen foutcodes hiervoor: "DEVICE MATCH
FAILED"/"APPID MATCH FAILED"/"RECONNECT FAILED", zie
`CareSensAirGattProtocol.kt`'s `AppIdOutcome`-enum, die al vóór deze fix
correct deze afwijzingen herkende en netjes disconnectte — de bug zat dus
puur in wat we VERSTUURDEN, niet in de responsverwerking).

Fix: `buildAppIdHandshakeCommand()` krijgt nu een `unusedSensor: Boolean`-
parameter; `CareSensAirDriver.kt` berekent die op basis van
`CareSensAirNative.getLastSequence(handle) <= 0` (dezelfde check die al
elders in het bestand voor eenzelfde doel gebruikt werd), vlak vóór het
commando verstuurd wordt.

versionCode 7, versionName `0.2.5-caresensair-unusedsensor-fix`.

Dit is de eerste van de vier rondes waarbij de bug in het PROTOCOL zelf
zat (niet in verbindingsinfrastructuur eromheen) — een teken dat de
onderliggende BLE-laag nu robuust genoeg is om daadwerkelijk protocol-
niveau problemen zichtbaar te maken. Nog niet bevestigd tegen een
geslaagde eind-tot-eind meting.

## 01/08/2026 (editor, vijfde live-test-ronde) — unusedSensor-fix loste het niet op; diagnostische logging toegevoegd i.p.v. verder gissen

Vijfde test (bevestigd: v50 met de unusedSensor-fix was echt geïnstalleerd,
via het About-scherm gecontroleerd) — exact hetzelfde afbreekpatroon als
ronde vier: verbinding, MTU, service-discovery en de eerste
notificatie-enable op de AppID-karakteristiek lopen goed, en dan volgt
weer een zelf-geïnitieerde disconnect ~190ms later. De unusedSensor-fix
was zelf een reële, bron-bevestigde correctie (zie vorige sectie), maar
loste dit specifieke afbreekpatroon dus niet op — de sensor wijst de
AppID-handshake kennelijk om een ANDERE reden af dan alleen die ene byte.

De meegestuurde Juggluco-logcat bleek geen bruikbare informatie te
bevatten (de capture ving alleen het opstarten van Juggluco zelf, geen
BLE-verkeer) — en Juggluco's eigen gedetailleerde protocol-logregels
staan sowieso achter een `doLog`-vlag die in een normale (niet-debug)
build uit staat, dus zelfs een langere Juggluco-capture zou vermoedelijk
niet veel meer tonen.

In plaats van blind een zesde theorie te proberen: `CareSensAirDriver.kt`
logt nu expliciet (tag `CareSensAirDriver`, niet achter een debug-vlag)
zowel het uitgaande AppID-handshakecommando (met de `unusedSensor`-waarde
en alle bytes) als het binnenkomende antwoord (de geparste `AppIdOutcome`
+ alle ruwe bytes). De volgende test-logcat toont daarmee direct de
werkelijke afwijzingsreden, zonder te hoeven raden.

versionCode 8, versionName `0.2.6-caresensair-appid-diagnostics`.

## 01/08/2026 (editor, zesde live-test-ronde) — RECONNECT_FAILED gevonden via de nieuwe logging; unusedSensor moet na een afwijzing wisselen

De nieuwe diagnostische logging (vorige sectie) gaf meteen het exacte
antwoord: `outcome=RECONNECT_FAILED` (laatste byte van de AppID-respons =
3). Het verstuurde commando zelf klopte (`unusedSensor=true`, byte 34 =
1, 35 bytes, "csair" op de juiste plek) — het probleem zat in wat er ná
een afwijzing moet gebeuren.

Vergelijking met `AirGattCallback.java`'s `onChar22Changed()` (regel
483-484) toonde de ontbrekende stap: Juggluco zet `unusedSensor=false`
vlak vóórdat het disconnect() aanroept in de afwijzings-tak — zodat de
eerstvolgende herverbinding een ANDER handshake-commando stuurt.
`CareSensAirDriver.kt` herberekende `unusedSensor` tot nu toe bij elke
herverbinding opnieuw vanuit de (nog altijd lege) kalibratiegeschiedenis,
dus bleef na een afwijzing gewoon hetzelfde `unusedSensor=true` sturen —
een oneindige lus van dezelfde `RECONNECT_FAILED`-afwijzing, tot de
40s-timeout het opgaf.

Fix: nieuw veld `appIdRejectedOnce` (mirror van Juggluco's
`unusedSensor`-veld) — gezet op `true` in de afwijzings-tak van
`handleAppIdNotification`, gebruikt om `unusedSensor` voor de rest van
deze koppelsessie geforceerd op `false` te houden, gereset in
`disconnect()` (nieuwe koppelsessie = weer vanaf de kalibratiegeschiedenis
afleiden, zoals Juggluco's eigen constructor-gedrag voor een nieuw
`AirGattCallback`-object).

versionCode 9, versionName `0.2.7-caresensair-appid-retry-fix`.

De nieuwe diagnostische logregels (tag `CareSensAirDriver`) blijven
staan — ze zijn niet duur en waren in één testronde al hun gewicht in
goud waard vergeleken met blind gissen.

## 01/08/2026 (editor, zevende live-test-ronde) — de appIdRejectedOnce-fix werkte nooit, want er kwam nooit een tweede poging

Zevende test: exact dezelfde `RECONNECT_FAILED`-afwijzing als de vorige
ronde, met `unusedSensor=true` (niet `false`) — de appIdRejectedOnce-fix
uit de vorige sectie werd dus kennelijk niet toegepast. Verklaring: er kwam
helemaal geen TWEEDE handshake-poging. Na de disconnect() logt Android
`unregisterApp()` (zichtbaar in beide logcat-opnames) — dat gebeurt
automatisch zodra een BluetoothGatt-cliënt na een disconnect wordt
opgeruimd. De bestaande `STATE_DISCONNECTED`-code riep daarna gewoon
`gatt.connect()` aan op HETZELFDE (inmiddels dus al opgeruimde) object —
een bekende, onbetrouwbare Android-BLE-valkuil: ná `unregisterApp()` doet
`gatt.connect()` op dat object vaak niets meer. Er was dus geen zichtbare
tweede poging in de logs, en de 40s-timeout gaf uiteindelijk op zonder dat
de appIdRejectedOnce-vlag ooit een kans kreeg.

Fix: `STATE_DISCONNECTED` gebruikt nu dezelfde scan-dan-verbind-aanpak
(`startConnectScan()`) als de allereerste connect() i.p.v. het
onbetrouwbare `gatt.connect()`-hergebruik — een VERSE `connectGatt()`-
aanroep op een net-geziene `BluetoothDevice`, precies zoals Juggluco's
eigen `connectToActiveDevice()`-pad. Om te voorkomen dat een blijvend
afwijzende sensor tot een oneindige stille herverbind-lus leidt, is er nu
een `MAX_RECONNECT_ATTEMPTS` (3) — daarna een duidelijke foutmelding i.p.v.
eindeloos doorproberen. `reconnectAttempts` reset bij een nieuwe
gebruikersinitiatieve `connect()`-aanroep en bij een geslaagde meting.
Ook `startConnectScan()` annuleert nu altijd eerst een eventuele vorige
`connectionTimeoutJob` (was eerder een latent risico: een verweesde timer
van een vervangen poging kon een nieuwe, nog lopende poging onderuit
halen).

versionCode 10, versionName `0.2.8-caresensair-reconnect-fix`.

Met deze fix zou de appIdRejectedOnce-vlag uit de vorige ronde nu
daadwerkelijk een tweede poging (met `unusedSensor=false`) moeten krijgen
— dat is nog niet bevestigd.

## 01/08/2026 (editor, achtste live-test-ronde) — de echte boosdoener: BleConnectionService gooide de driver telkens weg vóór de appIdRejectedOnce-fix ooit een kans kreeg

Achtste test: de scan-dan-verbind-herverbinding uit v53 werkte zichtbaar
(nieuwe scan startte netjes na elke afwijzing), maar `unusedSensor` bleef
bij ELKE poging `true` — ook bij pogingen die minutenlang na de vorige
afwijzing plaatsvonden, binnen dezelfde ononderbroken app-sessie (zelfde
process-ID de hele tijd). Dat wijst niet op de sensor-driver zelf, maar op
iets dat de driver van BUITENAF telkens vervangt.

Gevonden in `BleConnectionService.kt`: de guard tegen "onnodig opnieuw
opbouwen" checkte `connectionJob?.isActive == true`. Maar
`SensorDriver.connect()` is bewust NIET suspend — hij start z'n eigen
achtergrondwerk (scannen, GATT-callbacks) en keert METEEN terug. Daardoor
was de `launch { driver.connect(...) }`-job alweer "niet actief" vrijwel
onmiddellijk na de EERSTE `onStartCommand()`, ook al was de driver zelf nog
volop bezig met scannen/verbinden/herverbinden. Elke volgende
`onStartCommand()`-aanroep (bv. omdat MainActivity opnieuw opgebouwd wordt
bij het wisselen tussen schermen/apps — een normaal Android-verschijnsel,
zie de kdoc-geschiedenis in dit bestand) zag dus altijd "niet actief",
brak de nog lopende driver af, en bouwde een GLOEDNIEUWE
`CareSensAirDriver` op — wat automatisch `appIdRejectedOnce` (en alle
andere in-memory sessiestatus) terugzette naar de beginwaarde, vóórdat die
ooit een tweede kans kreeg.

Fix: de guard kijkt nu naar de ECHTE, actuele staat van de driver zelf
(`connectionState.value is Connected/Connecting/Scanning`) i.p.v. de
onbetrouwbare job-activiteit. Zolang een driver voor dezelfde sensor+adres
nog daadwerkelijk aan het werk is, breekt een herhaalde `onStartCommand()`
'm niet meer onnodig af.

versionCode 11, versionName `0.2.9-blesvc-driver-reuse-fix`.

Dit raakt niet alleen CareSens Air — elke toekomstige sensor-driver die
sessie-status bijhoudt (G7, Accu-Chek) profiteert hiervan net zo goed.

## 01/08/2026 (editor, negende live-test-ronde) — na de 40s-scantimeout gebeurde er niets meer

Negende test bevestigde het probleem exact zoals gemeld: "Dan krijg ik na
40 seconden de error en gebeurt er niks meer... Wachten nadat de 40
seconden timeout gebeurt levert niks op." De logcat laat zien waarom —
22:51:53.324 stuurt de AppID-handshake (`unusedSensor=true`), 22:51:53.422
komt `outcome=RECONNECT_FAILED` terug, de driver disconnect netjes en
start (dankzij de v54-fix hierboven) meteen een nieuwe scan
(`onScannerRegistered` om 22:51:53.447) — maar die scan vindt de sensor
deze keer niet, en om 22:52:33.449 (exact 40s later, `CONNECT_TIMEOUT_MS`)
gebeurt er niets zichtbaars meer. Root cause: `startConnectScan()`'s eigen
scan-timeoutafhandelaar bestond los van de retry-logica in
`STATE_DISCONNECTED` — een GATT-disconnect kreeg wél automatisch een
volgende poging, maar een scan die de sensor simpelweg niet zag binnen
40s was een doodlopend pad dat alleen een foutmelding toonde.

Fix: beide faalwijzen (scan-timeout én GATT-disconnect/afwijzing) lopen nu
door hetzelfde gedeelde pad, een nieuwe functie `scheduleReconnect()`. Die
telt `reconnectAttempts` op, toetst tegen `MAX_RECONNECT_ATTEMPTS` (3), en
start bij een nog beschikbare poging een nieuwe `startConnectScan()` — nu
na een korte pauze van `RECONNECT_GRACE_MS` (3s) in plaats van meteen
opnieuw, zodat de sensor na het net verbreken van de vorige verbinding
even de kans krijgt om weer verbindbaar/adverterend te worden. Pas als
alle pogingen op zijn, verschijnt de definitieve "Gave up after 3
reconnect attempts"-foutmelding. Dit maakt de twee eerder losstaande
retry-paden (`STATE_DISCONNECTED` en de scan-timeout in
`startConnectScan()`) tot één consistent geheel, en verwijdert het
doodlopende pad dat in deze test werd geraakt.

versionCode 12, versionName `0.3.0-scan-timeout-retry-fix`.

Nog niet bevestigd: of de sensor bij een volgende automatische poging
(met `unusedSensor=false`, na de eerdere afwijzing) daadwerkelijk
`outcome=OK` teruggeeft. Dat vereist dat er binnen de 3 pogingen ook echt
een succesvolle scan tussen zit — als de sensor structureel niet meer
adverteert na een afwijzing (bv. omdat hij zelf een langere pauze
inlast), is een grotere `RECONNECT_GRACE_MS` of meer pogingen mogelijk
nog nodig. Dat is met de huidige logs niet vast te stellen.

## 01/08/2026 (editor, tiende live-test-ronde) — retry-fix werkte perfect, maar een geslaagde handshake werd toch afgewezen (WRONG_APP_ID)

Tiende test bevestigt eerst dat de v55-fix uit de vorige sectie
daadwerkelijk werkt: na de eerste `RECONNECT_FAILED`-afwijzing om
23:03:50 begint de herverbind-scan netjes na de `RECONNECT_GRACE_MS`-
pauze (23:03:53, ~3s later, exact zoals bedoeld), en al kan die eerste
scanpoging de sensor niet vinden binnen de 40s, springt
`scheduleReconnect()` automatisch door naar een TWEEDE poging
(23:04:36) — die de sensor wél vindt en verbindt (23:04:47). Ook
`unusedSensor` staat dit keer correct op `false` (i.p.v. weer `true`),
wat bevestigt dat `appIdRejectedOnce` z'n werk doet. Kortom: het
"gebeurt er niks meer na 40s"-probleem uit de vorige ronde is opgelost.

Maar deze tweede, verder foutloze poging kreeg alsnog een afwijzing:
`outcome=WRONG_APP_ID`, ook al was de laatste byte van het antwoord (de
statuscode) 0 — wat júist "geslaagd" betekent volgens zowel Juggluco als
onze eigen [parseAppIdResponse]. De 32 appId-bytes bleken bij uitpakken
"csair" gevolgd door 27 nulbytes (0x00) te zijn — en daar zat de bug:
Juggluco's Java-code doet `new String(value,2,value.length-3).trim()`,
en Java's `String.trim()` knipt ELK teken met codepoint <= 0x20 weg
(dus ook NUL-bytes), niet alleen "echte" whitespace. Onze Kotlin-poort
gebruikte Kotlin's ingebouwde `.trim()`, die intern `Char.isWhitespace()`
gebruikt — en een NUL-teken telt daar NIET als whitespace. Het resultaat
bleef dus `"csair  ... "`, wat nooit gelijk kon zijn aan
`"csair"`. Een zuiver taalverschil tussen Java en Kotlin, niets aan het
sensor-protocol zelf — precies het soort subtiele 1-op-1-portagefout
waar de vergelijking met de echte Juggluco-bron voor bedoeld is.

Fix: nieuwe helper `javaStyleTrim()` in `CareSensAirGattProtocol.kt`
bootst Java's eigen trim()-regel exact na (strip elk teken met
codepoint <= 0x20 aan begin/eind) i.p.v. Kotlin's `.trim()` te
gebruiken.

versionCode 13, versionName `0.3.1-appid-trim-fix`.

Met deze fix zou de AppID-handshake nu daadwerkelijk `outcome=OK` moeten
opleveren zodra de sensor een geslaagd antwoord stuurt — dat is met deze
build nog niet getest.

## 01/08/2026 (editor, elfde live-test-ronde) — handshake lukte eindelijk (outcome=OK), maar de 40s-watchdog brak de gezonde verbinding alsnog af

Elfde test: de vorige trim()-fix werkt — `outcome=OK` verschijnt nu
daadwerkelijk in de logs, tot twee keer toe. Ná de geslaagde handshake
start de kalibratieprofiel-overdracht zichtbaar goed:
`nativeSaveStartSensor`, `nativeSaveSensorInfoChunk1` (lot=260226C002),
`nativeSaveSensorInfoChunk2` (twee keer, oplopend `ininfo`) — allemaal
"OK". Maar exact 40 seconden nadat de bijbehorende scanpoging begon,
verschijnt telkens `BluetoothLeScanner: could not find callback wrapper`
gevolgd door een geforceerde `disconnect()`/`close()`/`unregisterApp()`
— midden in een kerngezonde, actief communicerende verbinding. Dit
gebeurde twee keer na elkaar (23:38:37→23:39:17 en 23:39:20→23:40:00,
beide exact 40s), waarna `MAX_RECONNECT_ATTEMPTS` op was en de sessie
definitief opgaf — ondanks dat de sensor prima meewerkte.

Root cause, gevonden door Juggluco's eigen scan-timeout te vergelijken
(`SensorBluetooth.java`'s `mScanTimeoutRunnable`/`stopScan()`): die timer
begrenst bij Juggluco UITSLUITEND de scan-fase ("kon het toestel niet
vinden") — zodra het toestel gevonden is, annuleert `stopScan()` de timer
meteen, ruim vóórdat er ooit een handshake of kalibratie-overdracht
begint. In onze eigen `startConnectScan()` werd `connectionTimeoutJob`
echter pas geannuleerd bij de EERSTE VERWERKTE GLUCOSEWAARDE (in
`handleGlucoseDataNotification`) — dus de timer bleef gewoon doortikken
over de hele handshake + kalibratieprofiel-overdracht heen, die
makkelijk langer dan 40s kan duren voordat de sensor zijn eerste
periodieke glucosewaarde stuurt. Een zuivere scope-fout: de timer
bewaakte een veel groter stuk van het proces dan Juggluco's eigen
equivalent ooit deed.

Fix: `connectionTimeoutJob?.cancel()` verplaatst naar `onScanResult()` —
exact het moment waarop Juggluco's `stopScan()` zijn eigen timer ook
annuleert — i.p.v. te wachten op de eerste glucosewaarde. Na dit punt is
de connectie überhaupt niet meer "aan het zoeken", dus hoort de
scan-timeout er ook niet meer over te waken.

versionCode 14, versionName `0.3.2-scan-timeout-scope-fix`.

Dit zou de meest recente blokkade moeten wegnemen — een geslaagde
handshake + kalibratie-overdracht zou nu door moeten kunnen naar een
eerste glucosewaarde zonder tussentijds afgebroken te worden. Nog niet
bevestigd of er daadwerkelijk een glucosewaarde binnenkomt.

## 02/08/2026 (editor, twaalfde live-test-ronde) — ontbrekende requestConnectionPriority(HIGH); plus sensor-infoscherm uitgebreid (type/serienummer/start/einddatum/laatst verbonden)

Twaalfde test: de handshake lukte weer (`outcome=OK`), en de
kalibratieprofiel-overdracht kwam weer op gang — maar stokte na 2 van de
vele benodigde `0xC2/2`-brokken (het volledige profiel is een veel groter
struct dan de 434 bytes die binnenkwamen), waarna de SENSOR zelf de
verbinding verbrak (status 19, GATT_CONN_TERMINATE_PEER_USER) zo'n 28
seconden later. Root cause, gevonden door Juggluco's
`SuperGattCallback.java` te doorzoeken: Juggluco roept na ELKE
`connectGatt()` standaard `bluegatt.requestConnectionPriority
(CONNECTION_PRIORITY_HIGH)` aan (de "balanced"-modus is daar een opt-in
diagnostiek-schakelaar, UIT per standaard — dus HIGH geldt voor iedereen).
Onze poort deed dit nergens. De geobserveerde verbindingsinterval na
connect was 159 eenheden (~199ms, Android's trage standaardgedrag) i.p.v.
de veel snellere interval die CONNECTION_PRIORITY_HIGH afdwingt — bij een
kalibratieprofiel dat over meerdere pakketten verspreid binnenkomt, kan
een trage interval de sensor's eigen geduld-budget voor de overdracht
laten verlopen voordat alles is aangekomen.

Fix: `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` wordt nu direct
ná elke `connectGatt()` aangeroepen in `startConnectScan()`'s
`onScanResult`, precies zoals Juggluco het doet.

Daarnaast, op verzoek na een controlevraag over hoe de sensor-starttijd in
de logs tot stand kwam: het sensor-infoscherm (SensorManagementScreen via
StatusScreen.kt's SensorInfoBlock) toonde tot nu toe geen serienummer, een
"Started"-veld dat feitelijk de app-verbindpoging was (niet de echte
sensor-activatie) en pas zichtbaar werd ná een eerste meting, en een "End
date" die de fabrieks-/verpakkingsvervaldatum uit de barcode was (vóór
gebruik) — niet CareSens Air's eigen 15-dagen-draagtijd. Nu:
- **Serial number**: uit de barcode-scan (`AppSettings.careSensAirScan`).
- **Started**: het ECHTE fysieke activatiemoment van de sensor (dezelfde
  `nu - elapsedSecs`-berekening als de native laag, nu ook opgeslagen in
  AppSettings zodra de 0xC0/2-handshakestap slaagt — dus al zichtbaar
  ruim vóór de eerste meting, i.p.v. te wachten op GlucoseReading).
- **End date**: nu start + 15 dagen (CareSens Air's eigen sensorlevensduur)
  i.p.v. de barcode-vervaldatum.
- **Package expiry**: de oude barcode-vervaldatum, apart behouden als
  eigen regel (nog steeds nuttige info, alleen niet meer verward met "End
  date").
- **Last connected**: nieuw — moment van de laatste geslaagde
  BLE-verbinding (apart van "Xm ago" op het startscherm, dat over de
  leeftijd van de laatste MEETWAARDE gaat).

versionCode 15, versionName `0.3.3-connprio-sensorinfo`.

De connection-priority-fix is nog niet live getest — dat is de directe
volgende stap om te bevestigen dat de kalibratieprofiel-overdracht nu wel
volledig afrondt.

## 02/08/2026 (editor, dertiende live-test-ronde) — connection-priority-fix bleek NIET de oorzaak; diagnostische logging uitgebreid; sensor-info naar het startscherm verplaatst

Dertiende test: de v58-fix is aantoonbaar actief — `requestConnectionPriority()
- params: 1` verschijnt nu meteen na elke `connectGatt()`, en de
verbindingsinterval is direct na connect ook echt snel (`interval=39`,
~49ms, i.p.v. de eerdere ~199ms). Maar binnen ongeveer één seconde
renegotieert de verbinding zelf terug naar `interval=159` (~199ms) — dat
gebeurt zonder dat de app daar opnieuw om vraagt, dus kennelijk dicteert
de SENSOR zelf zijn voorkeursinterval zodra de verbinding eenmaal staat.
Het exacte stolp-patroon van de vorige ronde herhaalt zich vervolgens
1-op-1: kalibratieprofiel-overdracht komt op gang, stokt na precies 2
`0xC2/2`-brokken (434 van de vele benodigde bytes), en ~28,4 seconden
later verbreekt de sensor zelf de verbinding (status 19) — vrijwel
identiek aan de vorige ronde's ~28,2 seconden. Conclusie: de
connection-priority-fix was een reëel, in Juggluco-bron gegronde
verbetering (en blijft staan, want een snellere initiële verbinding is
sowieso beter), maar was NIET de daadwerkelijke oorzaak van deze stolp —
de sensor negeert 'm binnen een seconde toch weer.

Omdat de oorzaak nu écht onduidelijk is, en `handleAppInfoNotification`'s
`else`-tak (voor 198/1, 0xC6/1, 0xC6/2, EN elk ONBEKEND berichttype)
altijd stilzwijgend niets deed, is er een risico dat de sensor tussen de
2e chunk en de disconnect wél iets stuurt dat simpelweg niet exact matcht
met een van de bekende gevallen — en dus nooit gelogd werd. Fix (puur
diagnostisch, geen gedragsverandering): élke notificatie op élke
karakteristiek wordt nu eerst onvoorwaardelijk als ruwe bytes gelogd,
vóór de bestaande afhandeling — dezelfde aanpak die in de vijfde
testronde van dit traject een gok overbodig maakte. De volgende test moet
laten zien of de sensor in dat stille gat toch iets stuurt dat we nu wel
zien, of dat hij daadwerkelijk he-le-maal niets meer stuurt (wat op een
sensor/firmware-eigenaardigheid zou wijzen in plaats van een poortfout).

Los daarvan, op verzoek: de volledige sensor-info (type, serienummer,
status, start, einddatum, laatste verbinding) staat nu rechtstreeks op
het startscherm (StatusScreen.kt) in plaats van pas zichtbaar na een tik
naar SensorManagementScreen — dat scherm blijft bestaan voor de
koppel-acties (wisselen/loskoppelen), en tikken op de infokaart opent het
nog steeds. "Started"/"End date" tonen nu ook de tijd (niet alleen de
datum), zoals gevraagd.

versionCode 16, versionName `0.3.4-diag-log-home-sensorinfo`.

## 02/08/2026 (editor) — compile-fix: ontbrekende import voor de nieuwe raw-notificatie-logregel

v59 compileerde niet: `Unresolved reference 'Log'` op de nieuwe
onvoorwaardelijke logregel in `onCharacteristicChanged`. De rest van
`CareSensAirDriver.kt` gebruikt overal de volledig gekwalificeerde
`android.util.Log.i(...)` (geen aparte import), maar de nieuwe regel
gebruikte per ongeluk de korte vorm `Log.i(...)` zonder bijbehorende
import — simpele inconsistentie met de rest van het bestand, geen
functionele fout. Fix: dezelfde `android.util.Log.i(...)`-vorm als de
rest van het bestand.

versionCode 17, versionName `0.3.5-compile-fix-log-import`.

## 02/08/2026 (editor) — de echte oorzaak van de ~28s-stolp gevonden: ontbrekend "hoeveel nieuwe records?"-verzoek

Dankzij de onvoorwaardelijke ruwe-bytes-logging uit de vorige ronde liet
de volgende testronde eindelijk de VOLLEDIGE reeks zien: AppID-handshake
OK → kalibratiebrokken (0xC2/1, 0xC2/2 ×2) → **0xC2/3 (CRC-afronding)
komt wél degelijk binnen** (dat weerlegt het eerdere vermoeden dat er
brokken ontbraken) → de app schrijft correct `buildRequestDataCommand(0)`
(196,1,0,0,0,0) → de sensor antwoordt met een 4-byte notificatie
`bytes=196,1,0,0` → en dan **totale stilte**, gevolgd door de bekende
~28 seconden later de sensor die zelf de verbinding verbreekt
(status 19).

Vergelijking met Juggluco's `AirGattCallback.onChar11Changed()` (de
CHAR_GLUCOSE_DATA-notificatiehandler) legde de oorzaak bloot:

```java
long res = Natives.airProcessData(dataptr, value, timeptr);
if (res == 3L) {
    if (!noticedNumberRecords) {
        numberRecords(bluetoothGatt);   // schrijft 197,1 — "hoeveel nieuwe records?"
        noticedNumberRecords = true;
    }
    return;
}
```

Juggluco schrijft dit "hoeveel nieuwe records?"-verzoek (197,1)
ONVOORWAARDELIJK bij de EERSTE 0xC4-aankondiging in een koppelsessie —
afgeschermd door een simpele eenmalige vlag (`noticedNumberRecords`),
NIET door het aangekondigde aantal. Onze eigen `RecordCountAnnounced`-tak
in `handleGlucoseDataNotification` deed dit verzoek voorheen alleen als
`result.newRecords > 0` — en de 4-byte announcement die deze sensor
stuurt (`196,1,0,0`) parseert (zie `nativeProcessGlucoseData` in
`caresensair_bridge.cpp`, correct — geen bug) naar `numRecords=0`. Bij
`numRecords=0` deed onze code dus he-le-maal niets, terwijl de sensor
gewoon zat te wachten op het vervolgverzoek dat wij nooit stuurden —
totdat de sensor na zo'n 28 seconden geduld opgeeft en zelf de verbinding
verbreekt. Dit verklaart exact het identieke stolp-patroon van de laatste
drie testrondes (met én zonder de connection-priority-fix — die was dus
inderdaad een rode haring, zoals de vorige ronde al concludeerde).

Fix in `CareSensAirDriver.kt`:
- Nieuw sessie-scoped veld `noticedNumberRecords: Boolean`, exact naar
  Juggluco's eigen vlag gemodelleerd.
- `RecordCountAnnounced`-tak schrijft nu `197,1` (via
  `buildNumberRecordsCommand()`) zodra dit de EERSTE aankondiging in de
  sessie is, ongeacht het aangekondigde aantal.
- `noticedNumberRecords` wordt gereset naar `false` in zowel `connect()`
  als `disconnect()`, exact hetzelfde per-sessie-resetpatroon als de
  bestaande `appIdRejectedOnce`- en `reconnectAttempts`-velden in
  dezelfde klasse.

Dit is ook het directe antwoord op de vraag "wie geeft normaal gesproken
de trigger om een nieuwe waarde binnen te halen": de APP moet dat
initiatief nemen via dit "hoeveel nieuwe records?"-verzoek, niet de
sensor uit zichzelf — en dat verzoek ontbrak.

versionCode 18, versionName `0.3.6-numberrecords-trigger-fix`.

## 02/08/2026 (editor) — numberRecords-fix stond geïnstalleerd, maar exact dezelfde ~26s-stilte + disconnect bleef optreden

De volgende live-test (met v61, dus mét de fix van hierboven al actief)
liet EXACT hetzelfde patroon zien: `196,1,0,0` komt binnen, en dan 26,4
seconden stilte, gevolgd door disconnect status 19. Volledige
broncode-trace (C++ `nativeProcessGlucoseData` → Kotlin
`CareSensAirNative.processGlucoseData`'s mapping → `handleGlucoseData
Notification`'s dispatch) bevestigt dat de `RecordCountAnnounced`-tak
hier daadwerkelijk bereikt wordt, `charGlucoseData` niet null kan zijn,
en `retry()` de schrijfactie voor 197,1 dus daadwerkelijk zou moeten
aanroepen — de code zelf is, voor zover te herleiden, correct.

Bij het doorlichten van Juggluco's `AirGattCallback.java` viel wél iets
op wat we nog niet hadden overgenomen: Juggluco implementeert
`onCharacteristicWrite()` en gebruikt die callback als EIGEN
synchronisatiepunt (`receiveNotes=status==GATT_SUCCESS`, met een
`if(!receiveNotes) return;`-poort bovenaan `onCharacteristicChanged()`)
— dat is puur Juggluco's eigen interne boekhouding, geen vereiste van
het sensor-protocol zelf, maar het onthulde wel dat WIJ tot nu toe GEEN
enkele zichtbaarheid hadden op of een `WRITE_TYPE_NO_RESPONSE`-schrijf-
actie door Android's eigen Bluetooth-stack daadwerkelijk bevestigd werd
als verstuurd, of stil faalde (een bekende Android-BLE-valkuil: er mag
maar 1 GATT-operatie tegelijk in behandeling zijn; een volgende die te
vroeg verstuurd wordt kan zonder duidelijke foutmelding mislukken).

Drie zuiver diagnostische/defensieve wijzigingen in `CareSensAirDriver.kt`
(geen gedragsverandering aan WAT er verstuurd wordt):

1. Nieuwe `onCharacteristicWrite()`-override die voor ELKE
   schrijfactie op elke karakteristiek de UUID en de status (SUCCESS/
   FAILED) logt — voor het eerst directe zichtbaarheid op of de 197,1-
   schrijfactie de Bluetooth-stack daadwerkelijk verliet.
2. Expliciete "verzoek versturen"-logregel vlak vóór de 197,1-write,
   in dezelfde stijl als de bestaande AppID-handshake-log.
3. `retry()`'s schrijf-aanroep liep voorheen ONgevangen — een eventuele
   exception zou deze ene coroutine stil hebben beëindigd, zonder enige
   verklarende logregel. Nu in `runCatching` met expliciete foutlog.
4. Gevonden bij het doorlichten van Juggluco's `onConnectionStateChange`:
   die roept bij ELKE overgang weg van CONNECTED `resetValues()` aan
   (incl. `noticedNumberRecords=false`), niet alleen bij een volledige
   disconnect() op app-niveau. Onze eigen reset zat voorheen alleen in
   `connect()`/`disconnect()`, niet op dit fysieke GATT-niveau — bij een
   automatische reconnect via `scheduleReconnect()` zou de vlag hierdoor
   ten onrechte "waar" kunnen blijven staan uit een vorige, mislukte
   sessie. Niet bevestigd als oorzaak van DEZE specifieke test (dit was
   de eerste geslaagde aankondiging in dit procesleven), maar wel een
   echt gat t.o.v. Juggluco's bron — hier gedicht (reset toegevoegd aan
   `onConnectionStateChange`'s `STATE_DISCONNECTED`-tak).

Eerlijke stand van zaken: de daadwerkelijke oorzaak van de 26s-stilte is
nog niet gevonden. De volgende test moet, dankzij deze logging, voor het
eerst laten zien of de 197,1-schrijfactie de Bluetooth-stack echt
verlaat (en zo ja, met welke status) — dat sluit de laatste blinde vlek
in deze keten en bepaalt of het probleem in onze code zit, in Android's
BLE-stack, of in de sensor zelf (bv. die simpelweg geen vervolg geeft
aan een "0 nieuwe records"-aankondiging, ondanks Juggluco's eigen
onvoorwaardelijke gedrag).

versionCode 19, versionName `0.3.7-write-diag-logging`.

## 02/08/2026 (editor) — de echte oorzaak gevonden: -1 (sentinel "nog geen geschiedenis") sign-extend't naar 4294967295 in het "stuur data vanaf sequentienummer X"-commando

De diagnostische logging van hierboven gaf meteen het antwoord: de
197,1-schrijfactie werd wél degelijk bevestigd (`onCharacteristicWrite
uuid=c4de9b74... status=0 (SUCCESS)`), dus dat spoor liep dood — de
schrijfactie zelf was nooit het probleem. De gebruiker stelde daarna zelf
een scherpe, uiteindelijk cruciale vraag: moet de app bij de EERSTE keer
niet gewoon de actueel beschikbare data opvragen, in plaats van eerst te
checken op "nieuwe" records?

Dat bracht de aandacht terug naar het commando dat na de kalibratie-
afronding (0xC2/3) verstuurd wordt: `buildRequestDataCommand(lastSeq)`
("stuur data vanaf sequentienummer X"), waarbij `lastSeq` = `CareSensAir
Native.getLastSequence(handle)` — de native laag initialiseert dat veld
(`lastAir`) op **-1** als sentinel voor "nog nooit een record verwerkt
voor deze koppelsessie" (zie `caresensair_bridge.cpp`, mirror van
Juggluco's eigen aanpak). Bij een verse koppeling (zoals bij elke test
tot nu toe) is dat dus altijd -1.

`buildRequestDataCommand()` zette die -1 voorheen ONGEWIJZIGD om naar een
little-endian uint32:
```kotlin
val v = lastReceivedSequence.toLong() and 0xFFFFFFFFL   // (-1).toLong() and 0xFFFFFFFFL = 4294967295
```
Het daadwerkelijk verstuurde commando bij een verse koppeling was dus
**"196,1,255,255,255,255"** ("stuur alles NA sequentienummer
4294967295"), niet "196,1,0,0,0,0" ("stuur alles vanaf het begin") zoals
in de vorige rondes aangenomen zonder de exacte bytes gelogd te hebben.
Een sensor met dagenlange geschiedenis (in deze tests: >9 dagen, ruim
binnen de 15-daagse levensduur) heeft vanzelfsprekend niets ná het
maximaal mogelijke sequentienummer — de "196,1,0,0" (0 nieuwe
records)-aankondiging die de sensor daarop terugstuurde was dus in
werkelijkheid een CORRECT antwoord op een onbedoeld onzinnig verzoek,
geen sensor- of protocolfout. Juggluco's eigen `requestData()` beschermt
hiertegen met een expliciete `if(lastval<0) disconnect();`-check vóórdat
het wat dan ook verstuurt — onze code miste die vangrail.

Fix (`CareSensAirGattProtocol.kt`, `buildRequestDataCommand`): een
negatief/nog-onbekend sequentienummer wordt nu simpelweg als 0 behandeld
("stuur alles, dit is de eerste keer") — exact wat de gebruiker zelf al
aangaf als logisch verwachte gedrag voor de allereerste koppeling.

versionCode 20, versionName `0.3.8-lastsequence-signextend-fix`.

## 02/08/2026 (editor) — het werkt: echte data stroomt door. Twee restpunten opgelost: valse "Gave up"-melding en grafiek die oude simulator-data doorkruist

Bevestiging uit de live-test: na de vorige fix kwam de volledige
historie (189 records) binnen en bleven daarna live metingen elke
~60s binnenkomen zodra er nieuwe beschikbaar waren — de kern-bug is dus
opgelost. Twee restpunten, beide door de gebruiker zelf scherp
gediagnosticeerd:

**1. "Gave up after 3 reconnect attempts" ondanks een gezonde verbinding.**
CareSens Air blijkt maar heel kort verbonden te blijven per keer: hij
meldt "0 nieuwe records" (want er is nog geen 5 minuten voorbij sinds de
laatste meting) en hangt daarna zelf binnen ~30s weer op — volkomen
normaal, batterijsparend gedrag, geen storing. `scheduleReconnect()`
telde echter ELKE zo'n verwachte, gezonde disconnect mee als een
mislukte poging, en na 3 van die cycli (die geen van alle NIEUWE data
opleverden, simpelweg omdat de 5 minuten nog niet om waren) gaf de app
het dan onterecht op. Precies zoals de gebruiker zelf al vermoedde: "als
hij na 1 minuut 3 pogingen doet om iets nieuws binnen te halen dan zal
dat altijd stranden."

Fix (`CareSensAirDriver.kt`): `reconnectAttempts` wordt nu al
teruggezet naar 0 zodra de AppID-handshake slaagt (bewijst dat de sensor
bereikbaar is en de koppeling accepteert) — niet pas bij een NIEUWE
meting. De "Gave up"-vangrail blijft bestaan voor een écht aanhoudend
probleem (sensor blijft de handshake zelf afwijzen), maar activeert niet
meer bij dit volkomen normale duty-cycle-patroon.

**2. Statusweergave flipte tussen "Connected" en "Connecting..." tijdens elke herverbindpoging.**
Ook op verzoek: "als hij 1 maal connected is geweest dat hij dan
connected moet blijven staan ... ook als er op dat moment niet direct
een bluetooth verbinding in de lucht is." `scheduleReconnect()` zette de
zichtbare status voorheen altijd naar "Connecting..." bij elke
herverbindpoging, ook de routinematige. Fix: zodra de status al Connected
was, blijft die zichtbaar staan tijdens een achtergrond-herverbindpoging
— alleen bij de allereerste, nog nooit geslaagde verbinding verschijnt
nog "Connecting...".

**3. Grafiek toonde een schokkerig zaagtandpatroon (oranje/groen door elkaar).**
Oorzaak: metingen worden nergens naar sensor-type getagd opgeslagen, dus
bij het wisselen van sensor (simulator -> CareSens Air) bleven oude
simulator-metingen gewoon in de database staan en werden ze samen met de
nieuwe, echte historie getoond — precies wat de gebruiker zelf al
vermoedde ("de oude waarden van de virtuele sensor die daarvoor draaide
niet wist"). Fix: nieuwe `GlucoseReadingDao.deleteAll()` /
`GlucoseReadingStore.clearAll()`, aangeroepen bij elke sensorwissel
(zowel in `PairingScreen.kt` als `SimulatorSetupScreen.kt`, symmetrisch
in beide richtingen).

versionCode 21, versionName `0.3.9-reconnect-status-and-graph-fix`.

## 02/08/2026 (editor) — controlevraag: opruimen bij sensorwissel moet vanaf de eerste nieuwe meting, niet meteen alles wissen

Terechte vraag van de gebruiker over de vorige fix: "bij een normale
sensor wissel heeft de nieuwe sensor amper historische data. Hij zou dan
alleen de data uit het geheugen moeten wissen vanaf het tijdstip van de
eerste nieuwe sensor waarde zodat de historie wel zichtbaar blijft."

De vorige aanpak (`GlucoseReadingDao.deleteAll()`, aangeroepen meteen bij
het kiezen/koppelen van een sensor) loste het gerapporteerde probleem
(oude simulator-data door elkaar met echte historie) wel op, maar had een
onbedoelde bijwerking: bij een GEWONE sensorvervanging (bv. oude CareSens
Air op -> nieuwe CareSens Air van hetzelfde type) veegde het ook de nog
volkomen geldige, recente historie van de oude sensor weg — de grafiek
zou dan leeg beginnen tot de nieuwe sensor zijn eerste meting aanlevert,
terwijl die oude data prima had kunnen blijven staan voor een naadloze
aansluiting.

Herontworpen aanpak:
- `GlucoseReadingDao.deleteAll()` → vervangen door
  `deleteFrom(fromMs)` (`DELETE ... WHERE timestampMs >= :fromMs`).
- `GlucoseReadingStore.clearAll()` → vervangen door `trimFrom(timestampMs)`.
- De aanroep verhuisd van het koppel-/keuzemoment (PairingScreen.kt/
  SimulatorSetupScreen.kt — nu weer ongewijzigd op dat punt) naar
  `BleConnectionService.kt`: pas bij de EERSTE meting van een nieuw
  gestarte sensor-sessie wordt alles vanaf dat tijdstip opgeruimd. Een
  lokale `firstReadingThisSession`-vlag, gescopet aan het `connectionJob`
  dat alleen bij een daadwerkelijk NIEUWE sensor-/apparaatkeuze herstart
  (niet bij een gewone BLE-herverbinding binnen dezelfde sessie), zorgt
  dat dit precies één keer per sensorwissel gebeurt.

Resultaat: oudere, chronologisch eerdere historie van de vorige sensor
blijft gewoon zichtbaar (geen lege grafiek na het wisselen), en alleen
wat overlapt met of ná de nieuwe sensor z'n eigen eerste meting valt
wordt opgeruimd — precies zoals gevraagd.

versionCode 22, versionName `0.4.0-trim-from-first-reading`.

## 02/08/2026 (editor) — sensor-infokaart opgeschoond + herverbind-lus stopt niet meer voorgoed

Na bevestiging dat de kernpijplijn nu klopt ("Hij update nu iedere 5
minuten de Bg waarde"), vijf losse UI/UX-verzoeken over de sensor-
infokaart (`SensorInfoBlock`, `StatusScreen.kt`/`SensorManagementScreen.kt`):

**1. "Op het scherm staat nu geen start en eind tijd meer."**
Onderzocht in plaats van blind gefixt: bleek GEEN regressie. De volledige
handshake voor een sensor met historische data (identificatie -> AppID ->
notificaties aanzetten -> app-info zetten -> 0xC0/2-antwoord (dat
`sensorStartedAtMs` vult) -> kalibratieprofiel -> historie-backfill van
189 records) kost samen een merkbare hoeveelheid tijd. De vroege
screenshots in de laatste testronde waren gemaakt TERWIJL die handshake
nog liep (status "Connecting..."), dus vóór het 0xC0/2-antwoord
binnenkwam — de latere "Connected"-screenshot uit dezelfde testronde
toont Started/End date wél gewoon gevuld. Geen codewijziging nodig.

**2 + 3. Started en End date op 1 regel, jaartal weg bij End date.**
Twee losse `InfoRow`'s ("Started"/"End date", elk met een eigen
datum-tijd-formaat) samengevoegd tot één rij ("Started – End"). End
date's formaat was `dd-MM-yyyy HH:mm`; nu `dd-MM HH:mm`, hetzelfde
formaat als Started al gebruikte (`formatTime()`), zodat de nieuwe
gecombineerde regel consistent oogt.

**4. "Device" moet het serienummer tonen i.p.v. het BLE-adres/kanaal.**
De losse "Device"/"Device (connecting)"-rij (het ruwe Bluetooth-MAC-
adres, bv. `2C:D3:AD:54:BF:AA`) is vervallen — de "Serial number"-rij
erboven (het echte, op de sensor afgedrukte nummer, al eerder toegevoegd)
is het bruikbare identificerende veld voor de gebruiker; het technische
MAC-adres voegde daarnaast niets toe en blijft gewoon in logcat te vinden
voor het debuggen van koppelproblemen.

**5. Status: alleen "Connected", geen "(CareSens Air)"-toevoeging.**
`connectionStatusText()` gaf voorheen "Connected" + " ($deviceName)"
(-> "Connected (CareSens Air)"); het sensortype staat al apart op de
"Sensor type"-rij, dus die herhaling in de Status-rij is weggehaald.

**6. "Als er wel verbindingsproblemen zijn, laat dan zien wat het
probleem is: Bv 25 minuten geen verbinding of een andere sensor-error."**
Sensor-specifieke fouten (afgewezen handshake, einde levensduur,
transmitter-reset, firmware te oud) gaven al een concrete boodschap —
onveranderd. De generieke "Gave up after 3 reconnect attempts"-melding
gaf geen idee HOELANG het al mis was, en — belangrijker, tijdens het
uitzoeken hiervan ontdekt — `scheduleReconnect()` STOPTE de herverbind-lus
bij het overschrijden van `MAX_RECONNECT_ATTEMPTS` VOORGOED: een sensor
die bv. 10 minuten buiten bereik was (gebruiker liep even weg) kwam
daardoor nooit meer vanzelf terug, ook niet zodra hij weer in bereik
kwam — alleen een handmatige herkoppeling hielp dan nog. Dat paste niet
bij het "Bv 25 minuten"-voorbeeld uit het verzoek, wat een app impliceert
die gewoon blijft proberen en eerlijk laat zien hoelang dat al zonder
succes is.

Fix, twee delen:
- Nieuw in-memory veld `lastSuccessfulConnectionAtMs` in
  `CareSensAirDriver.kt` (apart van de persistente
  `AppSettings.careSensAirLastConnectedAtMs`, puur omdat een DataStore-Flow
  uitlezen asynchroon is en dit synchrone pad dat niet toestaat), gezet
  bij elke overgang naar Connected, gereset bij elke nieuwe
  connect()/disconnect()-sessie.
- `scheduleReconnect()`: het overschrijden van `MAX_RECONNECT_ATTEMPTS`
  `return`de voorheen VÓÓR de herverbind-scan onderaan de functie — nu
  valt dat gewoon door naar diezelfde scan, alleen de status-tekst
  verandert (van "Connecting..." naar bv. "No connection for 12 minutes
  (still trying). Make sure the sensor is nearby..."). De lus zelf stopt
  nooit meer vanzelf.

versionCode 23, versionName `0.4.1-sensor-info-cleanup`.

## 02/08/2026 (editor) — echte root-oorzaak: setSelectedSensor() wiste serienummer/startdatum bij elke herkoppeling + laatste-connectie-tijd bleef hangen + grafiek-Y-as meeschalen

Vervolgtest op v66 liet zien dat de vorige ronde (sensor-infokaart
opschonen) het onderliggende probleem niet had geraakt: "Hij geeft geen
serienr weer, hij geeft niet de start en eind datum, hij ververst de
laatste connectie tijd niet bij een nieuwe Bg." Ditmaal uitgezocht aan de
hand van de daadwerkelijke logcat + screenshots van DEZE test (niet van
een eerdere ronde) — drie aparte, nu wel gevonden oorzaken:

**1. Serienummer + start/einddatum: `AppSettings.setSelectedSensor()`**
wiste ONVOORWAARDELIJK het CareSens Air-serienummer, PIN, vervaldatum en
sensor-startmoment bij ELKE aanroep — ook wanneer gewoon hetzelfde
sensor-type opnieuw gekozen werd (bv. bij een herkoppelpoging na het
installeren van deze nieuwe APK-versie, zonder dat de fysieke sensor
zelf veranderde: PairingScreen.kt roept deze functie aan bij elke
device-keuze, niet alleen bij een échte wissel naar een ander
sensor-type). DataStore zelf overleeft een APK-update-installatie
gewoon (alleen een volledige DE-installatie wist 'm) — en dat verklaart
ook waarom de logs steeds `unusedSensor=false` en nooit een "eerste keer
ooit"-handshake meer lieten zien: de kalibratiegeschiedenis
(CareSensAirNative.kt, een APART opslagmechanisme) was intact gebleven.
Maar de HIER (in AppSettings) opgeslagen serienummer/startmoment-velden
werden bij die herkoppeling alsnog gewist — en omdat de sensor het
startmoment alleen opnieuw doorgeeft tijdens die overgeslagen "eerste
keer ooit"-stap, kon de UI dat veld daarna nooit meer vullen.
Fix: `setSelectedSensor()` wist deze velden nu alleen nog als het
sensor-TYPE daadwerkelijk verandert — bij het opnieuw kiezen van
hetzelfde type blijven serienummer/PIN/vervaldatum/startmoment/laatste-
verbinding gewoon staan.

**2. "Last connected" ververste niet bij een nieuwe Bg.**
Die update stond in `CareSensAirDriver.kt` binnen dezelfde
`_connectionState.value !is Connected`-conditie als de eenmalige
overgang naar de Connected-status — dus werd maar ÉÉN keer per sessie
bijgewerkt (bij de allereerste geslaagde meting), niet bij elke latere
meting binnen dezelfde sessie (CareSens Air blijft na de eerste keer
gewoon zichtbaar "Connected" staan, zie de eerdere v64/v65-fix). Fix:
losgekoppeld van die conditie — nu bij ELKE succesvol verwerkte meting
bijgewerkt, ongeacht of de zichtbare status al Connected was.

**3. Grafiek-Y-as meeschalen met de hoogste Bg.**
Op verzoek: "wil graag dat de y-as van de grafiek meeschaalt met de
hoogste Bg in het weergave venster. Dus minimum 2 tot 12 maar als de Bg
boven de 11 komt dan tot 13 en boven de 12 tot 14 laten lopen." De Y-as
stond hard vast op 2-12 (bewust, zie eerdere kdoc-geschiedenis in
`GlucoseChart.kt` — geen Y-zoom/pan). axisMinimum blijft 2; axisMaximum
wordt nu bij elke dataverversing herberekend uit de hoogste waarde in de
volledig geladen 48u-dataset (dezelfde set die de X-as al gebruikt voor
zijn eigen ondergrens) — 12 als standaard, 13 zodra een meting boven de
11 komt, 14 zodra een meting boven de 12 komt.

versionCode 24, versionName `0.4.2-persist-caresens-fields-fix`.

## 02/08/2026 (editor) — kernpijplijn bevestigd correct (Bg komt overeen met Juggluco, bereikt AAPS); drie resterende UI-punten

Gebruiker bevestigde dat de dataflow inmiddels volledig klopt: "Functioneel
lijkt alles te werken, de data komt bij aaps binnen en de Bg waarde is
gelijk als die in juggluco." Drie resterende punten:

**1. Started/End date nog steeds niet gevuld.**
De vorige ronde loste een ANDER probleem op (setSelectedSensor() die de
opgeslagen waarde onnodig wiste) — maar voor DEZE fysieke sensor bleek de
waarde nooit eerder succesvol vastgelegd te zijn: het 0xC0/2-antwoord
(dat elapsedSecs draagt, waaruit sensorStartedAtMs berekend wordt) komt
alleen binnen als reactie op een `buildSetAppInfoCommand()`-schrijfactie,
en die werd tot nu toe ALLEEN verstuurd bij "eerste keer ooit voor deze
sensor" (kalibratiegeschiedenis nog leeg). Voor deze sensor bestond die
geschiedenis al van vóór dit veld ooit werd toegevoegd, dus die tak sloeg
de hele testperiode al over. Fix: nieuw veld `sensorStartedAtMsUnknown`
in `CareSensAirDriver.kt`, eenmalig gelezen uit AppSettings bij het
opzetten van elke sessie — de 0xC0/1-tak stuurt nu de app-info-
schrijfactie (die het 0xC0/2-antwoord triggert) OOK wanneer we het
sensor-startmoment nog niet gecached hebben, los van de
kalibratiegeschiedenis-staat. Eenmaal ontvangen en opgeslagen blijft dit
zowel binnen de sessie als (via AppSettings) over app-herstarts heen
bekend, dus dit re-triggert niet blijvend.

**2. Package expiry-rij verwijderd.**
Op verzoek: "die is alleen interessant bij plaatsing sensor om te checken
maar dan lees je hem gewoon op de verpakking dus hij hoeft niet op het
scherm getoond te worden." De rij en de bijbehorende parameter/berekening
zijn volledig verwijderd uit `SensorInfoBlock`, `StatusScreen.kt` en
`SensorManagementScreen.kt` (blijft wel gewoon zichtbaar tijdens de
barcode-scanstap zelf in `CareSensAirScanScreen.kt`, waar het als
directe scan-bevestiging wél nuttig is).

**3. Y-as schaalde niet mee met het zichtbare venster.**
De vorige ronde's implementatie berekende de bovengrens uit de VOLLEDIG
GELADEN 48u-dataset, niet uit wat daadwerkelijk in het huidige zoom/pan-
venster te zien is — expliciet gecorrigeerd: "als er in het zichtbare
deel geen waarden boven de 10 staan blijft hij toch op 14 staan." Nieuwe
`recomputeYAxisMax()` in `GlucoseChart.kt` leest nu
`chart.lowestVisibleX`/`highestVisibleX` (dezelfde bron die
`applyXAxisGranularity()` al gebruikte voor de tijdlabel-stapgrootte) en
wordt, net als die functie, na elke pan/zoom-beweging opnieuw
aangeroepen — dus de Y-as volgt nu echt het huidige kijkvenster, niet de
volledige geladen historie.

versionCode 25, versionName `0.4.3-startdate-refetch-and-visible-yaxis`.

## 02/08/2026 (editor) — onderzoek: "als het scherm op zwart gaat gaat fclglucolink lopen vertragen" (screenshot xDrip+ BG-log: tot 4 min vertraging, hele cycli van 5 min gemist)

Nieuwe klacht, ditmaal geen protocol-bug maar een Android-achtergrond-
gedrag-vraag: "De waarden komen te onregelmatig bij aaps binnen. Het lijkt
er met name op dat als het scherm op zwart gaat dat dan fclglucolink gaat
lopen vertragen. Bij juggluco gebeurt dat absoluut niet, ook 's nachts
niet." Uitgezocht via een deelonderzoek (los agent-onderzoek, geen
codewijzigingen tijdens het uitzoeken zelf) naar wat er al aanwezig is en
wat er ontbreekt:

**Al aanwezig (geen probleem):** `BleConnectionService.kt` is een echte
foreground service (`startForeground()`, `START_STICKY`), houdt al een
partial wakelock vast (20 dagen, opgeruimd in `onDestroy()`),
`AndroidManifest.xml` heeft zowel `WAKE_LOCK` als
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` en de service is gedeclareerd met
`android:foregroundServiceType="connectedDevice"`. CPU-slaap-drift op de
`delay()`-timers (de eerste theorie) is dus al afgedekt.

**Wél een echt gat gevonden:** de batterij-uitzonderingsprompt in
`MainActivity.kt` was VUURDE-ÉÉNMALIG — een `batteryOptimizationPrompted`-
vlag werd na de EERSTE prompt-poging voorgoed op `true` gezet, en daarna
NOOIT meer gecontroleerd of de uitzondering nog daadwerkelijk actief was.
Sommige toestelmerken (Samsung/Xiaomi/Huawei — al genoemd in
`BleConnectionService.kt`'s eigen kdoc-geschiedenis) trekken een eerder
verleende uitzondering na een app-update of periodieke "opschoning"
stilzwijgend weer in; met de oude vlag merkte de app dat nooit, en
promptte dus ook nooit meer om 'm terug te vragen — precies het soort gat
dat achtergrond-BLE/CPU-beperkingen (die de uitzondering had moeten
voorkomen) weer kan laten optreden, specifiek zichtbaar zodra het scherm
uitgaat en de foreground-Activity verdwijnt.

Fix: `AppSettings.BATTERY_OPTIMIZATION_PROMPTED` (boolean) vervangen door
`BATTERY_OPTIMIZATION_LAST_PROMPTED_AT_MS` (tijdstip). `MainActivity.kt`
controleert nu bij ELKE app-start opnieuw de ECHTE uitzonderingsstatus
(`isIgnoringBatteryOptimizations()`), en prompt opnieuw als die is
weggevallen — met een cooldown van 24 uur, zodat een gebruiker die 'm
bewust wegklikt niet bij elke start opnieuw lastiggevallen wordt (dat was
precies de reden dat de eenmalige vlag er ooit kwam).

**Eerlijke caveat:** dit is één concreet, geverifieerd gat, geen
gegarandeerde volledige verklaring. Een andere, bewust NIET doorgevoerde
kandidaat-oorzaak: Android's achtergrond-BLE-scanthrottling (apps zonder
zichtbare Activity krijgen minder/vertraagde scanresultaten) — de
herverbind-lus doet elke ~30s een `startScan()`/`stopScan()`-paar
(CareSens Air's eigen 26-30s-duty-cycle). De voor de hand liggende fix
(`connectGatt(autoConnect=true)` i.p.v. steeds opnieuw scannen) is bewust
NIET doorgevoerd: Juggluco's eigen, eerder gereverse-engineerde gedrag
(zie de kdoc bij `startConnectScan()`) scant zelf ook expliciet vóór elke
verbinding i.p.v. `autoConnect=true` te gebruiken — dat ondermijnt het
vertrouwen dat dat de daadwerkelijke verklaring is, en `autoConnect=true`
staat bekend als onbetrouwbaar op sommige Android-versies/toestelmerken.
Een risicovolle wijziging aan dit al zo broze, veelbevochten
verbindingspad zonder sterker bewijs leek onverstandig. Als deze fix het
probleem niet (volledig) oplost: controleer ook de toestelspecifieke
achtergrond-instellingen (Samsung's "apps laten slapen"-lijst, Xiaomi's
Autostart-manager, Huawei's "beveiligde apps"), die vaak HANDMATIG
ingesteld moeten worden en niet via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
alleen af te dwingen zijn.

versionCode 26, versionName `0.4.4-recheck-battery-exemption`.

## 02/08/2026 (editor) — herontwerp herverbind-strategie: `connectGatt(autoConnect=true)` i.p.v. steeds opnieuw scannen (screen-off-vertraging blijft optreden, óók tijdens beweging)

De vorige ronde's fix (batterij-uitzondering opnieuw controleren) loste het
probleem NIET op. Nieuw bewijs via een wandeltest: "Zojuist een wandeling
gemaakt met de telefoon in mijn broekzak, maar dan valt hij regelmatig
langere tijd weg." Telefoon: Google Pixel 8 (stock Android, geen
fabrikant-specifieke achtergrond-app-killer zoals Samsung/Xiaomi/Huawei).
Ter vergelijking een screenshot van dezelfde toestel/situatie met Juggluco
actief: die bleef de hele tijd rond de 8-9s vertraging houden, geen enkele
langere onderbreking.

Dit bewijs verandert de conclusie van de vorige ronde. Een telefoon die
actief in een broekzak beweegt tijdens een wandeling kan niet in Doze-modus
zitten (Doze vereist dat het toestel stilligt) — dus die theorie valt af.
En een Pixel 8 met stock Android heeft geen van de agressieve
fabrikant-achtergrond-killers die Samsung/Xiaomi/Huawei-toestellen wel
hebben — dus die valt ook af. Wat overblijft, en wat wél bij dit exacte
patroon past (soms vlot, soms 10+ minuten stilte, specifiek zodra het
scherm uitgaat): Android's eigen achtergrond-BLE-scanbeperking. Apps zonder
zichtbare Activity krijgen minder/vertraagde scanresultaten van
`BluetoothLeScanner`, ONGEACHT of er een foreground-service of wakelock
actief is (die beschermen tegen CPU-slaap, niet tegen deze aparte
scanbeperking). De oude herverbind-lus deed bij ELKE herverbinding
(CareSens Air's eigen ~26-30s-duty-cycle, dus grofweg elke 30-90s continu
zodra het scherm uit is) een vers `startScan()`/`stopScan()`-paar — precies
het soort aanroep dat deze beperking treft.

**Wat er in de vorige ronde nog tegen pleitte:** Juggluco's eigen,
gereverse-engineerde `SensorBluetooth`/`SuperGattCallback` scant ook zelf
altijd expliciet vóór elke verbinding, i.p.v. `autoConnect=true` te
gebruiken. Dat gold als reden om deze wijziging toen NIET door te voeren.
Bij nader inzien verklaart dat niet waarom Juggluco zelf dit probleem niet
heeft: Juggluco draait zijn eigen herverbind-logica mogelijk anders genoeg
(andere scan-frequentie, andere achtergrond-strategie) dat het die
specifieke beperking niet op dezelfde manier raakt — en de wandeltest laat
zien dat FCLGlucoLink dat probleem WEL heeft, ongeacht wat Juggluco intern
doet. Met nieuw, sterker bewijs (beweging sluit Doze uit, Pixel sluit OEM-
killers uit) weegt dat zwaarder dan de eerdere terughoudendheid.

**De wijziging** (`CareSensAirDriver.kt`): de oude aanpak — bij elke
herverbinding een verse `scanner.startScan()`, wachten tot het opgeslagen
adres langskomt, dan pas `connectGatt(autoConnect=false)` — is volledig
verwijderd (`startConnectScan()`/`scheduleReconnect()`). Ervoor in de
plaats: `connectDirectly()` roept direct
`device.connectGatt(context, true, callback, TRANSPORT_LE)` aan, zonder
enige `startScan()`-aanroep. Bij een GATT-disconnect (`STATE_DISCONNECTED`)
wordt niet meer `gatt.close()` + een nieuwe scan gestart, maar simpelweg
`gatt.connect()` opnieuw aangeroepen op hetzelfde, nog geregistreerde
object — met `autoConnect=true` is dat de officieel bedoelde herverbind-
weg (in tegenstelling tot `autoConnect=false`, waarbij een eerdere test
liet zien dat Android de cliënt na een disconnect al opruimt en
`gatt.connect()`-hergebruik dan een no-op is — dat probleem is specifiek
aan `autoConnect=false` gekoppeld en geldt niet meer bij `autoConnect=
true`). Het "aantal mislukte pogingen"-concept (`MAX_RECONNECT_ATTEMPTS`,
`reconnectAttempts`) is vervangen door een periodieke, tijd-gebaseerde
statustekst-ticker (`statusTickerJob`, elke 60s): zolang er geen
succesvolle verbinding is, laat de status na
`RECONNECT_STATUS_WARNING_MINUTES` (3) minuten een concrete "No connection
for X minutes"-melding zien — de eigenlijke herverbind-poging zelf regelt
het Bluetooth-systeem nu volledig zelf, op de achtergrond, op laag
vermogen.

**Eerlijke caveat — dit is de risicovolste wijziging in dit hele traject.**
`autoConnect=true` staat bekend als minder consistent betrouwbaar op
sommige Android-versies/toestelmerken dan `autoConnect=false`, en dit raakt
het kernstuk van de verbindingslogica dat al meerdere rondes kostte om
stabiel te krijgen. Test dit ALSTUBLIEFT zowel 's nachts (stilliggend,
Doze) als tijdens een wandeling (bewegend, geen Doze) opnieuw — als deze
wijziging de screen-off-vertraging niet oplost of nieuwe problemen
introduceert (bijvoorbeeld helemaal geen herverbinding meer na een
disconnect), is dat cruciale informatie voor de volgende ronde.

versionCode 27, versionName `0.4.5-autoconnect-reconnect-redesign`.

## 02/08/2026 (editor, avond) — `autoConnect=true` bleek FOUT: sensor maakte bij uitgeschakeld scherm HELEMAAL geen contact meer — teruggedraaid op basis van de echte Juggluco-apk

Nieuwe test na v70 (`autoConnect=true`): "zodra het scherm sluit maakt hij
geen contact meer. Zodra ik het scherm ontgrendel ook zonder dat AAPS en
FCLGlucoLink geactiveerd zijn update hij binnen 1 minuut." Dat is
duidelijk SLECHTER dan vóór v70 (toen kwam er nog wel, zij het
onregelmatig, data binnen). Extra, belangrijk gegeven: op de testtelefoon
met de VIRTUELE sensor (simulator, geen echte BLE) bleef het de hele nacht
gewoon doorwerken — dat isoleert het probleem specifiek tot BLE-gedrag,
niet tot een algemenere achtergrond-executiebeperking (die zou de
simulator, die door dezelfde foreground-service loopt, ook geraakt
hebben). Conclusie: Android's OS-eigen achtergrond-herverbind-wacht van
`connectGatt(autoConnect=true)` doet op dit toestel kennelijk NIETS zolang
het scherm uit staat — die lijkt pas te "ontwaken" zodra het scherm aan
gaat, precies zoals het gerapporteerde patroon (ontgrendelen -> data
binnen 1 minuut, zonder de app zelfs maar te openen).

Op verzoek is de ECHTE Juggluco-apk (10.9.8, arm64, dezelfde bron als de
rest van dit CareSens Air-traject) opnieuw gedecompileerd — deze keer
specifiek om te zien hoe Juggluco zijn achtergrond-herverbinding daadwerkelijk
oplost, aangezien "Juggluco heeft dat probleem absoluut niet" expliciet
genoemd werd. Gereedschap: androguard (jadx/apktool niet beschikbaar in
deze omgeving, geen internettoegang tot GitHub-releases voor de
installatie ervan). Bevinding, in Juggluco's `SensorBluetooth`-klasse
(in de obfuscated apk terug te vinden als `bk0` — geïdentificeerd via de
nog aanwezige `Log.e("SensorBluetooth", ...)`-tags, die ProGuard/R8 vaak
onveranderd laat ook al wordt de klasse zelf hernoemd):

Juggluco gebruikt **helemaal geen** `autoConnect=true`. Het blijft, net
als FCLGlucoLink's ORIGINELE aanpak (vóór v70), gewoon scannen en dan pas
`connectGatt(device, false, ...)` aanroepen. Het verschil zit 'm niet in
autoConnect, maar in HOE Juggluco met scannen omgaat:

1. **Een zelfopgelegd plafond van 5 scan-starts per 31 seconden.**
Juggluco houdt een gedeelde wachtrij bij van scan-start-tijdstippen
(`bk0.q`) en telt vóór elke nieuwe scanpoging (`bk0.k()`) hoeveel daarvan
binnen de afgelopen 31 seconden vallen — zijn dat er al 5, dan wordt de
volgende poging UITGESTELD tot dat venster weer ruimte heeft. Dat getal
(5 per 31s) is duidelijk geen toeval: het blijft net onder Android's
eigen, ongedocumenteerde achtergrond-scanquota, zodat Juggluco's eigen
scans die strengere OS-throttling nooit triggeren.

2. **Een standaardpauze van minstens 60 seconden tussen scanpogingen.**
Na het stoppen van een scan (apparaat gevonden, óf een scan die niets
opleverde) wacht Juggluco standaard 60 seconden (`bk0.u()` ->
`this.s(60000)`) voordat de volgende scanpoging start — niet, zoals
FCLGlucoLink's originele aanpak, een verse scan bij vrijwel elke
herverbinding van de sensor zelf (die zijn eigen ~26-30s-duty-cycle
heeft). Pas als de data een tijd stil blijft, verdubbelt Juggluco die
pauze verder (vanaf een basis van 5 minuten).

Dat zo'n tragere, zelf-gepaceerde scanlus geen data mist, komt doordat
CareSens Air al een eigen sequence-/geschiedenis-mechanisme heeft (al in
dit bestand aanwezig: `CareSensAirNative.getLastSequence`/
`buildNumberRecordsCommand`) — een gemiste duty-cycle terwijl de app
geduldig op zijn beurt wacht, wordt bij de eerstvolgende geslaagde
verbinding gewoon ingehaald.

**De wijziging:** `autoConnect=true` (`connectDirectly()`) is volledig
teruggedraaid naar scan-dan-verbind (`startConnectScan()`,
`autoConnect=false`, identiek aan de aanpak van vóór v70), aangevuld met
Juggluco's exacte twee pace-regels:
- Nieuw `ScanRateLimiter`-object (proces-breed, mirror van `bk0.q`/
  `bk0.k()`): max. 5 scan-starts per glijdend venster van 31 seconden.
- Nieuwe constante `MIN_SCAN_COOLDOWN_MS` (60.000ms, mirror van Juggluco's
  standaard-pauze): minstens 60 seconden tussen het einde van de ene
  scanpoging en het begin van de volgende, zowel na een GATT-disconnect
  als na een scan die de sensor niet vond.
- Beide lopen nu via één gedeeld pad, `scheduleScanAttempt()`, aangeroepen
  vanuit `connect()` (meteen, geen pauze — eerste koppeling/app-herstart),
  vanuit `STATE_DISCONNECTED`, en vanuit `startConnectScan()`'s eigen
  scan-timeout.
- De tijdgebaseerde statusweergave uit ronde 23 (`updateConnectionStatus
  AfterDisconnect()`, "No connection for X minutes" i.p.v. permanent
  opgeven) blijft ONGEWIJZIGD — dat deel van ronde 23 was niet het
  probleem en blijft dus gewoon staan.

**Eerlijke afweging:** dit is nu de DERDE aanpak van de herverbind-
strategie in evenveel dagen. In tegenstelling tot de vorige twee rondes
is deze keer niet gegokt op een theorie, maar direct nagebootst wat een
apk die aantoonbaar WEL werkt (Juggluco, op dezelfde soort test) zelf
doet — dat geeft meer vertrouwen, maar de enige echte test is weer een
nacht (stilliggend) én een wandeling (bewegend) met het scherm uit.

versionCode 28, versionName `0.4.6-juggluco-paced-scan-reconnect`.

## 02/08/2026 (editor, later die avond) — v71 werkte, maar te traag/onregelmatig — scanvenster verbreed van 40s naar 90s

v71 herstelde een werkende verbinding (status ging van "No connection for
4 minutes" terug naar "Connected"), maar bleef onregelmatig: de xDrip+ BG-
log liet zien dat er WEL steeds elke 5 minuten een meting binnenkwam (geen
enkel record ontbrak — 6.3, 6.4, 6.3, 6.7, 7.3, 7.9, 8.2, 8.9, 9.5, 10.0,
10.2, 10.4, 10.8, 11.2, 11.3, 11.6, 11.8, allemaal netjes 5 minuten uit
elkaar), maar de vertraging waarmee elke meting daadwerkelijk aankwam
wisselde sterk: soms +7s, soms +67s/+71s, soms +2m, soms +5m/+6m. Data
ging dus niet verloren (CareSens Air's eigen sequence-/geschiedenis-
mechanisme haalde gemiste cycli netjes in, zoals bedoeld), maar kwam
regelmatig te laat aan om nog echt "actueel" te zijn.

De gebruiker wees op de vermoedelijke oorzaak: "als hij 4 minuten na de
laatste update begint te scannen/connecten en het dan totaal 60 à 90
seconden probeert dan zou hij toch een geslaagde verbinding op moeten
kunnen zetten." CareSens Air is kennelijk niet continu verbindbaar, maar
alleen tijdens korte, terugkerende vensters (samenhangend met zijn eigen
~26-90s-hercyclus). `SCAN_ATTEMPT_TIMEOUT_MS` stond op 40 seconden — een
scanpoging die toevallig NET buiten zo'n venster viel, miste 'm dan
volledig, en de eerstvolgende kans kwam pas na de volle
`MIN_SCAN_COOLDOWN_MS` (60s) opnieuw — bij een ongelukkige samenloop kon
dat een paar keer achter elkaar misgaan, precies zichtbaar als de
wisselende, soms minuten-lange vertragingen in de log.

Fix: `SCAN_ATTEMPT_TIMEOUT_MS` verhoogd van 40 naar 90 seconden — dat dekt
de volledige gerapporteerde bandbreedte van CareSens Air's hercyclus in
ÉÉN scanpoging, wat het aantal keer dat een venster gemist wordt (en dus
de kans op zo'n compounderende extra wachttijd) sterk moet verkleinen.
`MIN_SCAN_COOLDOWN_MS` (60s, mirror van Juggluco) en `ScanRateLimiter`
(5 scans/31s-plafond, ook mirror van Juggluco) blijven ongewijzigd — dit
raakt alleen hoe lang er per poging naar de sensor gezocht wordt, niet hoe
vaak.

versionCode 29, versionName `0.4.7-widen-scan-attempt-window`.

## 02/08/2026 (editor, nog later die avond) — een apart, echt gat gevonden: de service overleefde het wegvegen uit "Recents" niet

Bij het navertellen van de v72-test kwam een detail naar boven dat niet in
de eerdere screenshots stond: "Toen swipte ik de apps (fclGlucoLink en
AAPS) weg... 12 minuten na de laatste update opende ik het scherm en even
later ververste de Bg." Dat wegvegen uit het recente-apps-overzicht is een
HELE ANDERE actie dan "het scherm uitzetten" (waar alle vorige rondes over
gingen) — en bleek een reëel, tot nu toe onbesproken gat te zijn:

`BleConnectionService` (de foreground service die de actieve SensorDriver
draait) had geen `android:stopWithTask`-instelling in het manifest — die
staat standaard op `true`. Dat betekent dat Android deze service
AUTOMATISCH stopt zodra de gebruiker de app-kaart uit "Recents" wegveegt,
ONGEACHT `START_STICKY` of de partial wakelock die de service vasthoudt —
die twee beschermen tegen geheugendruk/CPU-slaap, niet tegen deze
expliciete, door de gebruiker geïnitieerde stop-actie. Wegvegen uit
Recents is voor veel gebruikers een gewoontehandeling ("even opruimen"),
niet bedoeld als "verbreek de sensor-koppeling" — maar deed dat hier
alsnog, onopgemerkt tot deze test.

Fix: `android:stopWithTask="false"` toegevoegd aan de service-declaratie
in `AndroidManifest.xml`. Dat ontkoppelt de service expliciet van de
taak-levenscyclus — wegvegen uit Recents onderbreekt de achtergrond-BLE-
koppeling dan niet meer. De "Verbinding verbreken"-knop op het
statusscherm blijft de bedoelde, expliciete manier om de koppeling wél te
stoppen.

**Eerlijke caveat:** dit verklaart mogelijk (een deel van) de eerdere
tests ook — als de app tijdens een van de voorgaande nachttests ooit
(bewust of per ongeluk) uit Recents is weggeveegd, dan was een deel van de
toen waargenomen stiltes misschien HELEMAAL NIET het scan-pacing-probleem
dat ronde 24/25 probeerden op te lossen, maar simpelweg een gestopte
service die pas bij de eerstvolgende handmatige actie (scherm aan, app
openen) weer opstartte. Dat maakt de eerdere test-resultaten niet ongeldig
(het scanvenster-probleem was zichtbaar via echte, niet-geswipete
periodes met alsnog wisselende vertraging), maar betekent wel dat een
schone volgende test — telefoon met rust laten, GEEN apps wegvegen, scherm
gewoon laten vergrendelen — nodig is om te zien wat er overblijft nu dit
gat ook gedicht is.

versionCode 30, versionName `0.4.8-survive-recents-swipe`.

## 02/08/2026 (editor, ronde 26) — de SCHONE test toonde: nog steeds hetzelfde probleem, dus terug naar Juggluco's decompile voor een tweede, dieper bewijs

Een test zonder Recents-swipe (v73's fix) EN met het verbrede 90s-
scanvenster (v72) liet exact hetzelfde onregelmatige vertragingspatroon
zien, puur van het scherm op zwart laten gaan: "Ik heb nu de apps niet weg
geswiped en nog steeds update hij niet. Alleen het scherm zwart laten
worden." Beide eerdere fixes waren dus terecht (ze losten reële problemen
op), maar niet de hoofdoorzaak.

De vorige 40s->90s-fix (ronde 25a) was gebaseerd op een redelijke
gok/hypothese, niet op bevestigde Juggluco-bytecode — `SCAN_ATTEMPT_
TIMEOUT_MS` als concept (scan een tijdje, geef dan op, wacht, probeer
opnieuw) was eigenlijk nooit letterlijk in Juggluco's `bk0`
(SensorBluetooth) teruggevonden. Een gerichte herdecompile (androguard,
ditmaal specifiek op de klasse `w2` — een gedeelde `Runnable` waar `bk0`
zelf naar verwijst via veld `e = new w2(8, this)`, en die in de eerste,
volledige decompile-poging kennelijk niet werd bereikt) toonde de
werkelijke logica: `w2.run()`, geval 8, doet ÉÉN `startScan()`-aanroep,
GEEN bijbehorende `stopScan()`, en plant zichzelf vervolgens gewoon 390
SECONDEN (6,5 minuut) later opnieuw in:
`Applic.t.schedule(v0_10.e, 390000, TimeUnit.MILLISECONDS)`.

Juggluco laat de scan dus gewoon continu doorlopen — er is helemaal geen
"geef na X seconden op"-cyclus. Die 390s-herplanning is puur een
zelf-herstellend veiligheidsnet voor het geval Android de langlopende
scan ondertussen stil beëindigd heeft, geen bewuste stop-en-wacht-actie.

Onze eigen aanpak deed het tegenovergestelde: na elke mislukte poging
(40s, later 90s) werd de scan ACTIEF gestopt, gevolgd door
`MIN_SCAN_COOLDOWN_MS` (60s) waarin er HELEMAAL NIET gescand werd. Bij een
sensor met korte, terugkerende advertentievensters is dat een herhaalde
dode periode waarin een langskomend venster gewoon gemist wordt — precies
wat de wisselende, soms minuten-lange vertragingen in alle voorgaande
tests verklaart. 90s-in-plaats-van-40s verkleinde het risico enigszins,
maar loste de dode periode zelf niet op.

Fix: `SCAN_ATTEMPT_TIMEOUT_MS` volledig verwijderd, vervangen door
`SCAN_REARM_INTERVAL_MS` (390s, Juggluco's eigen exacte getal) en een
nieuwe `scheduleRearm()`-functie: de scan blijft nu gewoon ACTIEF
doorlopen totdat het apparaat gevonden wordt, zonder tussentijdse stop —
alleen als er na 6,5 minuut nóg niets gevonden is, wordt de scan ververst
(stop+herstart, met `ScanRateLimiter` ertussen) als veiligheidsnet.
`MIN_SCAN_COOLDOWN_MS` en `ScanRateLimiter` blijven ongewijzigd gelden op
de twee plekken waar Juggluco ze zelf ook toepast: ná een echte
`onScanFailed()`, en ná een GATT-disconnect vóór de volgende poging.

**Eerlijke caveat:** dit is de derde poging om dit specifieke
screen-off-vertragingsprobleem op te lossen. Rondes 24 en 25a losten allebei
reële, bevestigde gedragsverschillen met Juggluco op, maar dit is de
EERSTE keer dat de daadwerkelijke scan-tijdslogica zelf (in plaats van een
aanname erover) rechtstreeks uit bytecode is bevestigd. Toch: dit is nog
niet live getest. Gezien de impact van een nachtelijke onderbreking
(gebruiker wil geen nacht zonder closed loop of AAPS-alarmen om de 30
minuten) verdient dit eerst een rustige DAGTEST voordat het 's nachts
vertrouwd wordt — zie ook de reactie in het gesprek zelf.

versionCode 31, versionName `0.4.9-continuous-scan-no-deadzone`.

## 02/08/2026 (editor, ronde 27) — v74 (continue scan) loste het NIET op — enige nog resterende, uit de decompile bekende verschil geprobeerd: scanmodus

v74 werd getest en bleek NIET beter — mogelijk zelfs slechter: de xDrip+-log
toonde een aaneengesloten black-out van circa 30 minuten (23:22 tot 23:52),
zichtbaar aan een kenmerkende vertragingsreeks die eerst opliep en daarna
weer afbouwde — 29m, 24m, 19m, 14m, 9m, 4m, 2m — precies het patroon van
een langere onderbreking gevolgd door het in één keer inhalen van een
opgestapelde achterstand via CareSens Air's eigen sequence-mechanisme.
Reactie van de gebruiker: "Het werkt niet."

Dat de continue-scanfix uit ronde 26 dit niet oploste is een belangrijk
gegeven: het probleem zit kennelijk NIET (meer) in hoe vaak of hoe lang we
een scan starten — dat mechanisme is nu al net zo geduldig als Juggluco's
eigen, bewezen-werkende gedrag (`SCAN_REARM_INTERVAL_MS`, geen dode
periodes meer). Het enige nog resterende, uit de decompile bekende
verschil met Juggluco's eigen scanaanroep zelf, tot nu toe genoteerd maar
nooit toegepast: Juggluco's `ScanSettings.Builder` roept NERGENS
`setScanMode()` aan (alleen `setReportDelay(0)`), en blijft dus op
Android's eigen standaardwaarde staan (`SCAN_MODE_LOW_POWER`).
`startConnectScan()` in dit bestand vroeg tot nu toe expliciet
`SCAN_MODE_LOW_LATENCY` aan — een "agressieve" modus die is bedoeld voor
een actief zichtbare app op de voorgrond, en die Android op de achtergrond
mogelijk juist STRENGER beperkt/onderdrukt dan de standaardmodus.

**Eerlijke caveat:** dit is nog niet met zekerheid bevestigd als DE
oorzaak (er was geen logcat van dit specifieke black-out-venster
beschikbaar om dat rechtstreeks te bevestigen) — het is wel het laatste,
concrete, uit de decompile bekende verschil dat nog niet geprobeerd was.
Mocht dit ook niet voldoende blijken, dan is een logcat-opname van een
vergelijkbaar black-out-venster de volgende stap, om rechtstreeks te zien
of `startScan()` daadwerkelijk (blijft) doorlopen tijdens zo'n gat, in
plaats van verder te blijven gissen op basis van alleen de xDrip+-log.

Fix: `setScanMode(SCAN_MODE_LOW_LATENCY)` verwijderd uit
`startConnectScan()`'s `ScanSettings.Builder` — nu identiek aan Juggluco's
eigen aanroep (alleen `setReportDelay(0)`). `startPairing()` (het
koppelscherm, scherm staat dan aan, gebruiker kijkt actief mee) blijft
bewust WEL `SCAN_MODE_LOW_LATENCY` gebruiken — dat is een ander, expliciet
voorgrond-scenario.

versionCode 32, versionName `0.4.10-drop-explicit-low-latency-scan-mode`.

## 03/08/2026 (editor, ronde 28) — v75 werkte ook niet (nog steeds gaten tot 8 minuten) — de ECHTE manifesten van AAPS/Juggluco/FCLGlucoLink naast elkaar gelegd

v75 (geen expliciete scanmodus meer) bleek ook niet genoeg: "Nee, dit werkt
ook niet. Ik zie op mijn horloge dat het tot 8 minuten duurt en er geen
update komt." Onderweg kwam ook een zijspoor langs — kan Juggluco, die de
gebruiker bij elke test met Force Stop stilzet, de sensor toch nog
bezethouden via een "spook"-BluetoothGatt-registratie die force-stop niet
opruimt? De gebruiker weerlegde dat zelf met een sterk argument: met het
scherm AAN werkt FCLGlucoLink foutloos elke 5 minuten — een blijvende
concurrerende verbinding zou zich niet aan schermstatus storen, dus dat
wees terug naar iets in Android's eigen achtergrondbeperking van
FCLGlucoLink zelf.

Op verzoek van de gebruiker zijn daarop de ECHTE, geëxporteerde
`AndroidManifest.xml`-bestanden van alle drie de betrokken apps naast
elkaar gelegd: AAPS (blijft ook altijd verbonden met de pomp), Juggluco
(werkt al jaren probleemloos met de sensor bij zwart scherm), en
FCLGlucoLink zelf. Dat leverde een verschil op dat NIETS met scannen te
maken heeft — de vier vorige rondes (24 t/m 27) probeerden allemaal iets
aan hoe/wanneer er gescand wordt, maar dit raakt een heel andere laag:

Juggluco's manifest bevat `RECEIVE_BOOT_COMPLETED`,
`SCHEDULE_EXACT_ALARM` (maxSdkVersion 32) ÉN `USE_EXACT_ALARM` (voor
API 33+), plus losse `<receiver>`-declaraties genaamd `.NumAlarm`
(BOOT_COMPLETED-ontvanger), `.Maintenance`, `.LossOfSensorAlarm` en
`.ConnectReceiver`. Dat is het patroon van een `AlarmManager`-gebaseerde
"wekker": een periodieke, exacte alarm die Doze/App Standby expliciet mag
doorbreken (`setExactAndAllowWhileIdle()`), en die zelfs het PROCES kan
herstarten als Android dat ondertussen volledig gestopt heeft — iets
wat `START_STICKY` en gewone coroutine-`delay()`-timers (zoals onze eigen
`scheduleRearm()`/`statusTickerJob`) geen van beide hard kunnen
garanderen: die zijn beide afhankelijk van dat het proces zelf nog leeft
en dat de CPU niet dieper slaapt dan onze partial wakelock toelaat.
FCLGlucoLink had tot nu toe geen enkel mechanisme dat het proces zelf,
onafhankelijk van de OS, weer tot leven kon wekken.

Fix: nieuw bestand `sensor/ble/ConnectionWatchdog.kt` met een
`AlarmManager`-wekker (elke 6 minuten, iets korter dan Juggluco's eigen
390s-scan-herplanning) die zichzelf steeds opnieuw inplant en
`BleConnectionService` herstart — onvoorwaardelijk veilig, want de
bestaande `onStartCommand()`-logica herkent een al werkende verbinding en
doet dan gewoon niets. Gekoppeld aan `startBleConnectionService()`/
`stopBleConnectionService()` in `MainActivity.kt` (expliciete
gebruikersintentie), NIET aan `Service.onCreate()`/`onDestroy()` — die
laatste twee vuren namelijk OOK als Android de service zelf killt, precies
het moment waarop deze wekker juist actief moet blijven. Erbij: een
`BootReceiver` (mirror van Juggluco's `.NumAlarm`) die de koppeling na een
telefoon-herstart automatisch hervat — tot nu toe bleef FCLGlucoLink na
een herstart stil staan totdat de gebruiker de app zelf weer opende, een
gat dat nooit apart gerapporteerd was (een herstart viel waarschijnlijk
nooit samen met een testperiode).

**Eerlijke caveat:** dit is de vijfde poging op rij voor hetzelfde
screen-off-probleem, en de eerste die NIET over scan-gedrag zelf gaat.
Nog niet live getest.

versionCode 33, versionName `0.4.11-alarmmanager-watchdog`.

## 03/08/2026 (editor, ronde 29) — v76 leverde eindelijk een ECHTE logcat op tijdens een schoon screen-off-gat — en die wijst weg van "proces dood" naar "Android levert scanresultaten niet af"

Gebruiker liet v76 draaien vanaf 13:33 (open scherm), scherm ging kort
daarna op zwart, en pas om 14:03 kwam er weer een meting binnen — 25
minuten stilte. De meegeleverde logcat over precies dat venster liet iets
heel specifieks zien: `BluetoothLeScanner onScannerRegistered(...)`-regels
op 13:39:21, 13:45:51 en 13:48:48 — alle opeenvolgende paren EXACT 390
seconden uit elkaar (`SCAN_REARM_INTERVAL_MS`, zie ronde 26). Dat bewijst
dat het PROCES niet dood was en onze eigen `scheduleRearm()`-timer keurig
op tijd `stopScan()`+`startScan()` bleef aanroepen — de v76-wekker
(AlarmManager, gericht op "proces dood") loste dus een probleem op dat
zich hier niet voordeed. Wat wél ontbrak: geen enkel `onScanResult()` in
al die tijd, ondanks dat de scan overduidelijk actief was.

De gebruiker noteerde er zelf iets cruciaals bij: "De reactie om 14:03
kwam direct nadat er een berichtje binnenkwam van de HBO-max app dat
triggerde pas de bluetooth scanner." Een classifier voor precies dit:
Android's Doze onderdrukt BLE-scanRESULTATEN voor achtergrond-apps (los
van of de scan zelf actief blijft draaien) en levert ze pas af tijdens
korte, onvoorspelbare "maintenance windows" — een binnenkomende
notificatie van een andere app kan zo'n venster triggeren.

Eerste hypothese: batterij-optimalisatie-uitzondering niet (meer) actief.
Gecontroleerd via screenshots van Instellingen > Batterij voor zowel
FCLGlucoLink als Juggluco: BEIDE stonden al op "Onbeperkt" — dat verschil
verklaart het dus niet.

Tweede, sterkere hypothese: Juggluco's decompiled scanlogica (`bk0`/`w2`,
zie de klasse-kdoc) bouwt voor de actieve sensor een `ScanFilter` op
service-UUID — dat maakt HARDWARE-offloaded scannen mogelijk (de
Bluetooth-chip zelf blijft matchen en wekt de AP alleen bij een treffer,
in plaats van dat de AP voortdurend software-side elk binnenkomend pakket
moet beoordelen). FCLGlucoLink's eigen `startConnectScan()` scant
ONGEFILTERD (`emptyList()`) — puur software-side, en dat is precies het
soort werk dat Doze voor achtergrond-apps onderdrukt. De bestaande kdoc
bij `CSAIR_SERVICE_1/2/3` in `CareSensAirGattProtocol.kt` concludeerde
ooit "adverteert geen matchbare service-UUID" — maar die conclusie
dateert van vóór de protocol-correctie (30/07, terwijl de ECHTE,
proprietaire service-UUID's pas op 01/08 gevonden zijn) en was dus
gebaseerd op een test met de VERKEERDE aanname (standaard Bluetooth
Glucose Profile 0x1808). Nooit herhaald met de nu bekende, echte UUID's.

Een `ScanFilter` blind toevoegen is te riskant: als de aanname mist, vindt
de scan dan HELEMAAL NIETS meer — een veel grotere regressie dan het
huidige probleem. Fix deze ronde: puur diagnostische logging (GEEN
gedragswijziging) in `startConnectScan()`'s `onScanResult` — logt bij een
match `result.scanRecord?.serviceUuids`/`deviceName`/
`manufacturerSpecificData`/de ruwe bytes, zodat de eerstvolgende geslaagde
reconnectie (hoeft geen lang screen-off-gat te zijn — elke gewone
herverbinding volstaat) definitief laat zien wat deze sensor daadwerkelijk
adverteert, vóórdat er een ScanFilter op gebouwd wordt.

versionCode 34, versionName `0.4.12-log-scan-record-diagnostic`.

## 03/08/2026 (editor, ronde 30) — bevestigd + gefixt: de sensor adverteert wél een matchbare service-UUID, ScanFilter toegevoegd voor hardware-offloaded scannen

Eerste test met v77 (open scherm, meteen na een verse QR-koppeling) leverde
meteen de bevestiging: `Scan-record voor 2C:D3:AD:54:BF:AA:
serviceUuids=[c4de9a20-5a9d-11e9-8647-d663bd873d93] deviceName=CSAir
0224 ...`. Die UUID is LETTERLIJK `CSAIR_SERVICE_2`, al aanwezig in
`CareSensAirGattProtocol.kt` sinds de protocol-correctie van 01/08 — de
sensor adverteert dus wél degelijk een matchbare service-UUID. De oude
conclusie ("adverteert geen matchbare service-UUID", uit de tijd vóór die
correctie) was verouderd/gebaseerd op een test met de verkeerde,
standaard-Bluetooth-Glucose-Profile-aanname.

Fix: `startConnectScan()`'s `scanner.startScan(...)`-aanroep gebruikt nu
een `ScanFilter`-lijst op alle drie bekende `CSAIR_SERVICE_1/2/3`-UUID's
(in plaats van `emptyList()`) — mirror van Juggluco's eigen decompiled
scanlogica (`bk0`/`w2`), die voor de actieve sensor ook altijd een
`ScanFilter` op service-UUID opbouwt. Dat maakt hardware-offloaded
scannen mogelijk: de Bluetooth-chip zelf blijft matchen en wekt de
telefoon pas bij een treffer, in plaats van dat de telefoon voortdurend
software-side elk binnenkomend advertentiepakket moet beoordelen — precies
het soort achtergrondwerk dat Android's Doze eerder leek te onderdrukken
(zie ronde 29: onze eigen 390s-herplanningstimer vuurde exact op tijd,
maar er kwam geen enkel scanresultaat binnen, tot een externe gebeurtenis
het kennelijk "loswrikte"). Alle drie UUID's meegeven (niet alleen de nu
bevestigde SERVICE_2) is een goedkope zekerheidsmarge: een apparaat
matcht al zodra ÉÉN filter in de lijst raak is.

De verouderde kdoc bij `CSAIR_SERVICE_1/2/3` in
`CareSensAirGattProtocol.kt` (die zei dat deze UUID's "niet actief
gebruikt" werden) is bijgewerkt om dit te weerspiegelen.

**Eerlijke caveat:** dit is de sterkste, meest rechtstreeks bevestigde
verklaring tot nu toe (gebaseerd op een ECHTE logregel, niet op een
aanname) — maar of dit het screen-off-vertragingsprobleem daadwerkelijk
oplost, moet een test met het scherm op zwart nog aantonen.

versionCode 35, versionName `0.4.13-scanfilter-hardware-offload`.

## 03/08/2026 (editor, ronde 31) — voorspellende herverbind-pauze (i.p.v. vlakke 60s) + waarschuwingsdrempel 3 -> 7 minuten

Test met v78 (ScanFilter/hardware-offload) bevestigde dat de sensor nu wél
betrouwbaar gevonden wordt — maar de gebruiker meldde een nieuw, ander
symptoom: "hij loopt regelmatig 2 minuten te laat... het lijkt wel dat hij
de 2 minuten te laat ook krijgt terwijl het scherm open staat", plus een
te vroeg afgaande "No connection for 4 minutes (still trying)"-melding.

De meegestuurde logcat (16:03-16:25, open scherm) liet het patroon zien:
connect -> handshake -> soms nieuwe data, soms niet -> disconnect
(sensor-geïnitieerd, ~30s verbonden) -> 60s cooldown -> scan -> herhaal.
De geslaagde-nieuwe-metingen kwamen onregelmatig binnen (5m59s, 3m1s,
4m59s, 7m5s) in plaats van een strak 5-minuten-ritme — precies wat je
verwacht als de vlakke `MIN_SCAN_COOLDOWN_MS` (60s) na ELKE disconnect
herhaaldelijk te vroeg opnieuw probeert te verbinden met een sensor die
zijn eigen ~5-minuten-cyclus nog niet heeft afgerond (`newRecords=0`),
wat de cumulatieve vertraging verklaart.

De gebruiker stelde zelf de exacte oplossing voor: "ik zit zelf te denken
of we het 5 minuten interval... niet kunnen gebruiken door bv 4 of 4,5
minuten na de laatste update pas weer een signaal te sturen en dat te
herhalen tot er een nieuwe waarde binnenkomt." Dat is nu geïmplementeerd:
een nieuwe `computeReconnectCooldownMs()` in `CareSensAirDriver.kt`
verankert de eerstvolgende scanpoging op `lastSuccessfulConnectionAtMs`
(de laatst geslaagde meting) plus `PREDICTIVE_RECONNECT_LEAD_MS` (4,5
minuten, het midden van de gebruiker's eigen "4 of 4,5 minuten"-marge) —
in plaats van altijd exact 60s na de disconnect. Zodra die voorspelde tijd
al voorbij is (of er nog geen geslaagde meting was), valt dit terug op de
gewone 60s-cooldown, zodat het bestaande "elke 60s opnieuw proberen tot de
sensor reageert"-gedrag voor het laatste stukje behouden blijft. Gebruikt
op beide plekken waar voorheen de vlakke cooldown gold: `onScanFailed()`
en de normale `STATE_DISCONNECTED`-afhandeling.

Daarnaast, op de gebruiker's letterlijke voorstel ("het zou logischer zijn
als die pas na bv 7 minuten komt"): `RECONNECT_STATUS_WARNING_MINUTES`
verhoogd van 3 naar 7 minuten — ruim boven de langst waargenomen normale
succesvolle-meting-interval (7m5s) in de logcat, zodat de "No connection"-
melding niet meer afgaat tijdens volkomen normaal gedrag.

**Eerlijke caveat:** de voorspellende timing gaat ervan uit dat de sensor
een redelijk voorspelbare ~5-minuten-cyclus aanhoudt (wat de logcat tot nu
toe steunt, zij het met wat spreiding: 3-7 minuten) — een volgende test
moet uitwijzen of 4,5 minuten de juiste voorspelling blijkt, of dat verdere
afstelling nodig is.

versionCode 36, versionName `0.4.14-predictive-reconnect-cooldown`.

## 03/08/2026 (editor, ronde 32) — voorspelling klopte, maar scan-naar-match nam nog ~93s: lead time bijgesteld van 4,5 naar 3,5 minuten

Eerste test met v79 (screen-off) leverde een schone logcat op om de nieuwe
voorspellende cooldown exact te controleren:

- Meting 1 om 16:58:49.917 (seq=3172), disconnect 29,5s later.
- Volgende scan geregistreerd om 17:03:19.936 — voorspeld was 17:03:19.917.
  Verschil: <20ms. De voorspelling zelf werkt dus precies zoals bedoeld.
- Maar van "scan geregistreerd" tot "sensor daadwerkelijk gevonden"
  (`onScanResult`-match) zat nog eens **1m33s** (17:03:19.936 ->
  17:04:53.386) — de tijd die nodig was om de sensor's eigen korte
  advertentie-venster te treffen.
- Meting 2 kwam daardoor pas om 17:04:54.441 binnen: **6m4,5s** na meting
  1, i.p.v. de sensor's eigen ~5 minuten.

Ter vergelijking: dezelfde logcat toont een EERDERE "geregistreerd ->
gevonden"-stap (vlak nadat het scherm net uitging) van maar ~28s — deze
duty-cycle-wachttijd is dus zelf variabel en neemt kennelijk toe naarmate
de telefoon langer/dieper met scherm uit zit. Niet volledig te elimineren
met de huidige aanpak (dat zou aparte verdere Doze-navorsing vergen), maar
wel te compenseren: `PREDICTIVE_RECONNECT_LEAD_MS` verlaagd van 270 000ms
(4,5 min) naar 210 000ms (3,5 min), zodat er structureel ~1-1,5 minuut
speling is ingebouwd voor die scan-naar-match-vertraging, en de totale
meting weer dichter bij de sensor's eigen ~5-minuten-cadans uitkomt.

**Eerlijke caveat:** dit is een kalibratie op basis van precies ÉÉN
gemeten duty-cycle-wachttijd (93s) plus één eerdere referentie (28s) — een
grove schatting van een reëel maar variabel fenomeen, geen exacte
wetenschap. Een volgende test moet uitwijzen of 3,5 minuten de totale
vertraging structureel dichter bij 5 minuten brengt, of dat verdere
bijstelling nodig blijft.

versionCode 37, versionName `0.4.15-recalibrate-reconnect-lead-time`.

## 03/08/2026 (editor, ronde 33) — CareSens Air: koppelen zonder barcode bij de hand

Op verzoek: soms is de barcode (thuis, op de sensorverpakking) niet
beschikbaar terwijl de sensor allang loopt (bijv. onderweg, of al gekoppeld
geweest aan een andere telefoon/app). Vóór de barcode-scan komt nu een
keuzescherm (`CareSensAirChooseScreen.kt`): "New sensor" (ongewijzigd, gaat
naar de barcode-scan) of "Already-running sensor" (nieuw — slaat de
barcode-scan volledig over, gaat rechtstreeks naar het generieke
koppelscherm met de Bluetooth-apparatenlijst).

Dit kon zonder wijzigingen aan de eigenlijke koppel-/verbindingslogica: het
serienummer wordt tijdens de GATT-handshake namelijk altijd al rechtstreeks
van de sensor zelf gelezen (`CHAR_SERIAL`-karakteristiek), nooit uit de
barcode — en de koppellijst-naamfilter (`buildPairingListFilter()`) matcht
al op "CSAIR" in de BLE-advertentienaam, óók zonder een eerder gescand
serienummer. De barcode's overige velden (PIN, vervaldatum) worden nergens
anders in de app gebruikt (PIN zat nooit in het GATT-protocol; de "Package
expiry"-rij is al op 02/08/2026 uit de UI verwijderd). Kortom: de barcode
was altijd al puur een gebruiksgemak om de koppellijst vast te verkleinen,
geen functionele vereiste — dit voegt alleen een expliciete manier toe om
dat gemak over te slaan.

versionCode 38, versionName `0.4.16-caresens-air-pair-existing-sensor`.

## 04/08/2026 (editor, ronde 34) — live-logcat-analyse bevestigt bekend mechanisme + nieuwe diagnostische logregels

Ecko heeft de test dit keer via een live, continu meelopende `adb logcat`
(Android Studio, geen achteraf-bugrapport) gedraaid — dat bleek nodig: een
eerder bugrapport bevatte vrijwel geen app-eigen logregels meer, omdat de
ringbuffer na verloop van tijd door systeemruis wordt verdrongen (alleen de
laatste paar minuten vóór het maken van het rapport overleven betrouwbaar).

Drie opeenvolgende reconnect-cycli in de verse logcat, exact getimed:

- 14:31:54.067 — scan-match, handshake, maar newRecords=0 (geen nieuwe meting).
- 14:33:52.526 — scan-match, newRecords=1. Gat t.o.v. vorige: 118,5s.
- 14:37:52.460 — scan-match, newRecords=1. Gat t.o.v. vorige: 239,9s.

Dit klopt volledig met het al bekende mechanisme uit ronde 31/32:
- Ná de LEGE cyclus (14:31:54) was er nog geen verse `lastSuccessfulConnection
  AtMs` om op te voorspellen, dus viel `computeReconnectCooldownMs()` terug op
  de vlakke `MIN_SCAN_COOLDOWN_MS` (60s) — plus de bekende variabele scan-tot-
  match-vertraging (~56s in dit geval) kwam dat uit op de geobserveerde 118,5s.
- Ná de GESLAAGDE cyclus (14:33:53) voorspelde de cooldown de volgende poging
  op 14:37:23,6 (3,5 min later) — de daadwerkelijke match kwam op 14:37:52,46
  binnen: **28,9s** later, ruim binnen de al eerder gedocumenteerde 28-93s
  scan-tot-match-tax-bandbreedte. De voorspelling zelf werkt dus nog steeds
  precies zoals bedoeld — de resterende onregelmatigheid zit 'm in die
  scan-tot-match-tax zelf, een Android-platform-eigenschap (Doze-diepte) die
  met app-code niet weg te nemen is, alleen te compenseren.

Tot nu toe was dit alleen indirect terug te rekenen uit de tijdstippen tussen
opeenvolgende "Scan-record voor"-regels — nooit rechtstreeks zichtbaar. Twee
nieuwe logregels maken dit voortaan direct afleesbaar:
- `onConnectionStateChange: STATE_DISCONNECTED status=... device=...` — het
  exacte disconnect-moment + de ruwe GATT-statuscode (was voorheen helemaal
  niet gelogd).
- `computeReconnectCooldownMs: ...` — de daadwerkelijk berekende cooldown per
  poging, inclusief of dit de voorspelde waarde was of de terugval-cooldown.

versionCode 39, versionName `0.4.17-reconnect-cooldown-diagnostics`.

## 04/08/2026 (editor, ronde 35) — diagnose-logboek naar bestand (op verzoek)

`adb logcat` (ronde 34) werkt goed maar vereist een aangesloten laptop de
hele testduur — onbruikbaar voor een test tijdens echt, regulier gebruik
over uren/dagen. Nieuw: `logging/DiagnosticFileLogger.kt`, een singleton die
dezelfde diagnostische regels die eerder alleen naar logcat gingen nu ook
naar een eigen tekstbestand schrijft, onafhankelijk van elke logcat-
ringbuffer of USB-verbinding.

- **Opslagplek:** `Android/data/com.fclglucolink.app/files/log/
  fclglucolink_yyyy-MM-dd.txt` — bewust `getExternalFilesDir()` i.p.v. een
  handmatig pad als `Interne opslag/aaps/fclglucolink/log`: dat laatste zou
  op Android 11+ de brede MANAGE_EXTERNAL_STORAGE-permissie vereisen, een
  zware permissie voor puur een debug-logboek. `getExternalFilesDir()` heeft
  geen extra permissie nodig en is met elke bestandsbeheerapp te vinden.
- **Eén bestand per dag:** voorkomt één onbeperkt groeiend bestand bij een
  meerdaagse test.
- **Standaard UIT, schakelaar in Instellingen** ("Debug" → "Diagnostic log
  to file") — voorkomt onnodige schrijf-I/O tijdens normaal gebruik, alleen
  aan tijdens een bewuste test. De schakelaar werkt meteen (in-memory
  vlaggetje, geen herstart nodig).
- Alle bestaande `android.util.Log.i("CareSensAirDriver", ...)`-aanroepen in
  `CareSensAirDriver.kt` (scan-records, handshake-stappen, karakteristiek-
  wijzigingen, de nieuwe ronde-34-cooldownregels) lopen nu via
  `DiagnosticFileLogger.log()`/`.logError()` — één plek i.p.v. dubbel
  onderhoud, en blijft ALTIJD ook naar logcat gaan (verwaarloosbare kost),
  voor het geval er toch een keer een live `adb logcat` meeloopt.

versionCode 40, versionName `0.4.18-diagnostic-log-to-file`.

## 04/08/2026 (editor, ronde 36) — AlarmManager-wekker i.p.v. coroutine-delay voor de voorspellende reconnect-cooldown

Vervolg op ronde 31/32 ("voorspellende reconnect-cooldown"). Op verzoek
onderzocht waarom Juggluco géén last heeft van het trimodale
vertragingspatroon (25-32s / 88-90s / 148-270s) dat de ronde-35-
logbestand-data liet zien, terwijl het onderliggende scan-/verbindings-
gedrag verder al vrijwel identiek gemirrord was (scanmodus, `ScanFilter`,
continu-scannen, ratelimiter).

Decompile van Juggluco's `AirGattCallback.onConnectionStateChange()` (dex-
variant, de bevestigde CareSens Air/Sibionics-klasse) liet twee paden zien
voor het inplannen van de volgende scan na een disconnect: (1) het
STANDAARDpad — meteen opnieuw scannen, delay=0, geen enkele lange getimede
sleep — en (2) een optionele, door de gebruiker nooit bewust aangezette
"alarm clock"-instelling die een `AlarmManager.setAlarmClock()`-wekker
plant. Bevestigd (gebruiker had de optionele instelling nooit geactiveerd)
dat bij hem dus het STANDAARDpad draait.

Onze eigen `computeReconnectCooldownMs()` deed qua BEDOELING iets dat op
pad 2 lijkt (voorspel de volgende meting, wacht dan pas), maar overbrugde
die wachttijd tot nu toe met een kale Kotlin-coroutine-`delay()` — geen
Doze-vrijstelling. Dat is vermoedelijk precies de verklaring voor het
trimodale patroon: Android's Doze-onderhoudsvensters schuiven op precies
die manier op (kort → oplopend) zodra het scherm langer uit staat, en een
kale `delay()` heeft geen enkele garantie om daar doorheen te breken.

**Fix:** `sensor/ble/PredictiveReconnectAlarm.kt` (nieuw) — plant een
`AlarmManager.setExactAndAllowWhileIdle()`-wekker (Doze-doorbrekend,
zonder de permanente wekker-icoontje-bijwerking van Juggluco's zwaardere
`setAlarmClock()`) op precies het door `computeReconnectCooldownMs()`
voorspelde tijdstip. `CareSensAirDriver.kt`'s `scheduleScanAttempt()` wacht
nu via de nieuwe `awaitCooldown()` op die wekker i.p.v. op een kale
`delay(cooldownMs)`, met een defensieve `withTimeoutOrNull()`-bovengrens
(cooldownMs + 30s) zodat een — zeldzame — niet-afgaande wekker nooit erger
uitpakt dan de oude situatie. Bestaande `ConnectionWatchdog.kt` (ronde 28,
elke 6 min, "leeft het proces nog?") blijft ongewijzigd als apart vangnet
ernaast bestaan.

**Noodgreep, standaard UIT:** `sensor/ble/AlwaysScanMode.kt` (nieuw) + een
tweede schakelaar in Instellingen → Debug ("Always rescan immediately") —
op verzoek, voor het geval de AlarmManager-wekker onverhoopt niet
consistent genoeg blijkt. Pas na een BEWUSTE tik op de schakelaar schakelt
`computeReconnectCooldownMs()` om naar Juggluco's eigen STANDAARDpad
(cooldownMs=0, meteen doorscannen na elke disconnect) — consistentere
timing, maar merkbaar meer batterijverbruik (de radio staat dan het
overgrote deel van elke 5-minuten-cyclus actief te scannen i.p.v. de
huidige ~3,5 minuten "slaap" ertussen).

versionCode 41, versionName `0.4.19-predictive-reconnect-alarm`.

## 04/08/2026 (editor, ronde 37) — scanmodus LOW_LATENCY + MATCH_MODE_AGGRESSIVE, op basis van de echte fabrikants-app

Vervolg op ronde 36. De gebruiker leverde de ECHTE officiële CareSens
Air-app aan (`com.isens.csair`, versie 1.2.14) — de eerste keer in dit
project dat de fabrikants-app zelf gedecompileerd kon worden, i.p.v.
alleen Juggluco (een derde-partij-app die CareSens Air niet eens officieel
ondersteunt).

Bevinding, rechtstreeks uit `BleService.l()` (de daadwerkelijke
scan-start-functie): de officiële app gebruikt

```java
new ScanSettings.Builder().setScanMode(2).setMatchMode(1).setReportDelay(0).build()
```

`setScanMode(2)` = `SCAN_MODE_LOW_LATENCY` (continu scannen, geen
duty-cycle), `setMatchMode(1)` = `MATCH_MODE_AGGRESSIVE` (rapporteert een
match zo snel mogelijk) — voor vrijwel alle toestellen (alleen Xiaomi
krijgt `SCAN_MODE_BALANCED`). Nergens `SCAN_MODE_LOW_POWER`, de modus die
wij (en Juggluco) gebruikten sinds ronde 27.

In de eigen `onConnectionStateChange()` van de officiële app (klasse
`cd.y0`, de bevestigde CGM-GATT-callback) staat bij een gewone,
sensor-geïnitieerde disconnect géén ingebouwde wachttijd vóór de volgende
scanpoging — vergelijkbaar met Juggluco's eigen standaardpad (zie ronde
36), maar Juggluco blijft daarbij wél op `LOW_POWER` scannen. De
fabrikants-app combineert dus twee dingen die we los al hadden getest,
maar nooit samen: geen bewuste sleep ÉN geen duty-cycled scanmodus. Ronde
36's "Always rescan"-test liet de scanmodus ongewijzigd op `LOW_POWER`
staan — het identieke trimodale vertragingspatroon dat daar gevonden werd
was dus geen bewijs tegen `LOW_LATENCY`, alleen tegen het weglaten van de
sleep op zich.

**Fix:** `startConnectScan()` in `CareSensAirDriver.kt` gebruikt nu
`SCAN_MODE_LOW_LATENCY` + `MATCH_MODE_AGGRESSIVE`, met de bevestigde
fabrikants-waarden i.p.v. de eerdere, inmiddels teruggedraaide gok uit
ronde 23-27. Bewust ALLEEN voor dit vangst-venster, ná de bestaande
voorspellende cooldown/AlarmManager-wekker uit ronde 31/36 — die blijft
ongewijzigd, dus de totale scan-aan-tijd per cyclus verandert niet
wezenlijk, alleen de duty-cycle-modus tijdens het venster zelf.

versionCode 42, versionName `0.4.20-low-latency-reconnect-scan`.

## 04/08/2026 (editor, ronde 38) — bevestiging ronde 37 + "Always rescan"-noodgreep weer verwijderd

De gebruiker liet FCLGlucoLink v42 (ronde 37's LOW_LATENCY-fix) vanaf
22:02 draaien met "Always rescan immediately" UIT — dus de gewone
voorspellende cooldown/AlarmManager-wekker, nu met de nieuwe scaninstellingen.
Vergelijking van de gemeten "tax" (scan-tot-match-tijd bovenop de geplande
cooldown) vóór/na 22:02, uit hetzelfde logbestand:

- **Vóór 22:02 (v41, `SCAN_MODE_LOW_POWER`):** n=133, gemiddeld 48,2s,
  spreiding van -94,6s tot 269,6s — het bekende trimodale patroon
  (25-33s/85-92s/147-152s/tot 270s uitschieters).
- **Na 22:02 (v42, `SCAN_MODE_LOW_LATENCY` + `MATCH_MODE_AGGRESSIVE`):**
  n=18, gemiddeld 28,9s, spreiding van 26,0s tot 29,7s — GEEN enkele
  waarneming meer boven de ~30s-baseline.

De 85-92s/147-152s/tot-270s-clusters zijn volledig verdwenen; wat overblijft
is een strakke ~26-30s-baseline (vermoedelijk gewoon de normale BLE-
verbindingsopbouw-overhead, niet langer een extra duty-cycle-straf
erbovenop). De aankomstintervallen van daadwerkelijke metingen in dat
venster wisselden voorspelbaar tussen 4 en 6 minuten (gemiddeld 5), in
plaats van de eerdere wilde spreiding van 3-8 minuten — een forse
verbetering in voorspelbaarheid.

Op die nieuwe, strakke baseline heeft "Always rescan immediately" (ronde
36's noodgreep — Juggluco's standaardpad, cooldownMs=0 na elke disconnect)
geen ruimte meer om nog iets te winnen: de scan is tijdens het vangst-venster
al continu en agressief. De schakelaar kostte dus alleen nog onnodig
batterijverbruik zonder enig voordeel — verwijderd uit `SettingsScreen.kt`,
`CareSensAirDriver.kt` (de `AlwaysScanMode`-check in
`computeReconnectCooldownMs()`) en `FclGlucoLinkApp.kt`.
`AlwaysScanMode.kt` zelf kon niet fysiek verwijderd worden (alleen
overschreven) — staat er nu leeg bij met een verwijzing hierheen; niets in
de codebase gebruikt het nog. De bijbehorende DataStore-sleutel
(`always_scan_after_disconnect`) is uit `AppSettings.kt` gehaald; een
eventuele oude waarde op een bestaand toestel wordt gewoon nergens meer
gelezen.

versionCode 43, versionName `0.4.21-remove-always-scan-fallback`.

## Ronde 39 (04/08/2026) — voorspellende lead time herijkt (4,5min → 3,5min → 4min40s)

Na ronde 38's bevestiging dat de scan-tot-match-tax nu een strakke ~26-30s-
baseline is, meldde de gebruiker een opvallend regelmatig afwisselend
"+7s"/"+67s"-vertragingspatroon in xDrip+'s eigen BG-lijst (metingen op
21:27-23:07). Logfile-analyse (`fclglucolink_2026-08-04 23.02.txt`,
22:00-23:04) bevestigde de oorzaak:

```
22:03:52  succesvolle meting
22:04:22  computeReconnectCooldownMs: cooldownMs=180699 (voorspeld, ~3,5min)
22:07:52  poging -> newRecords=1 -> SUCCES ("op tijd", +7s-achtig)
22:08:22  computeReconnectCooldownMs: cooldownMs=180772 (voorspeld)
22:11:52  poging -> newRecords=0 -> nog niets nieuws
22:12:22  computeReconnectCooldownMs: cooldownMs=60000 (terugval, voorspelde tijd al voorbij)
22:13:52  poging -> newRecords=1 -> SUCCES ("1 minuut te laat", +67s-achtig)
```

`PREDICTIVE_RECONNECT_LEAD_MS` (3,5 minuten, sinds ronde 32) plus de
verbindingsoverhead (~28s) komt uit op een eerste poging ~4 minuten na de
vorige meting — systematisch net vóór de sensor's eigen ~5-minuten-cadans.
Op de helft van de cycli is dat te vroeg (newRecords=0), wat dan pas op de
ÉÉN cyclus later (na nog eens `MIN_SCAN_COOLDOWN_MS`=60s terugval) alsnog
raak schiet. Omdat 4 en 6 minuten elkaar precies afwisselen (gemiddeld 5),
oogde het patroon zo regelmatig in plaats van willekeurig.

Ronde 32's keuze om de lead time van de gebruiker's oorspronkelijke 4,5-
minuten-voorstel te verkorten naar 3,5 minuten was destijds bewust bedoeld
als veiligheidsmarge voor een toen nog sterk WISSELENDE duty-cycle-
wachttijd (28s tot 93s+, vóór ronde 37's scanfix). Die marge is niet meer
nodig nu die wachttijd zelf al strak en voorspelbaar is (~26-30s) — sterker
nog, de te korte lead is nu zelf de bron van de "mis-en-terugval"-cyclus.

**Fix:** `PREDICTIVE_RECONNECT_LEAD_MS` opgehoogd van 210.000ms (3,5min)
naar 280.000ms (4 min 40s), zodat lead + tax (~280s + ~29s ≈ 309s) net ná
in plaats van vóór de sensor's ~5-minuten-cadans uitkomt. Doel: elke
poging in één keer raak, zonder de 60s-terugval-lus — het afwisselende
7s/67s-patroon zou moeten samenklappen tot een strakke, consistente band
van een paar tot ruim tien seconden. Bevestiging volgt uit de eerstvolgende
logfile-upload.

Alleen `CareSensAirDriver.kt` gewijzigd (de constante + bijbehorende kdoc);
geen ander bestand geraakt.

versionCode 44, versionName `0.4.22-recalibrate-predictive-lead`.

## Ronde 40 (05/08/2026) — klok-correctie: sensor's eigen klok vervangen door telefoonklok

Bevestiging vooraf uit `fclglucolink_2026-08-05.txt` dat ronde 39's fix
werkt: van 04:41 tot 07:55 (3,5 uur) slaagt letterlijk elke enkele
reconnect-poging meteen (`newRecords=1`, geen enkele terugval meer), met
tussenpozen die stuk voor stuk binnen 0,3s van precies 5 minuten vallen.
Het reconnect-ritme zelf is dus vlekkeloos.

De gebruiker signaleerde daarna een aparte, langzaam oplopende vertraging
(2-3 minuten) in xDrip+'s eigen BG-lijst, zichtbaar als een "doorgestreepte"
BG-waarde op het horloge in de laatste ~2 minuten van elke cyclus.
Uitgezocht in `caresensair_bridge.cpp`/`nativeProcessGlucoseData`: het
tijdstempel dat elke meting meekrijgt (`measurement_time_standard`, wat
uiteindelijk naar xDrip+/AAPS gebroadcast wordt) komt uit `air->time` — het
tijdveld dat de CareSens Air-sensor ZELF meestuurt, gebaseerd op zijn eigen
onboard-klok — niet van onze telefoon. Een goedkope BLE-transmitter-kristal
loopt doorgaans een paar seconden per uur weg; over meerdere uren telt dat
op tot minuten afwijking. De gebruiker vermoedde dat Juggluco de
telefoonklok gebruikt (en dus geen last heeft van dit effect, ondanks een
veel onregelmatiger verbindingsritme) — dat past bij deze verklaring.

**Fix:** `CareSensAirState` (caresensair_bridge.cpp) kreeg een nieuw
sessieveld `clockOffsetSecs`. Bij elk verwerkt record dat er "vers" uitziet
(zijn eigen tijd ligt binnen 10 minuten van onze telefoonklok — geldt sinds
ronde 39 vrijwel elke cyclus) wordt het verschil tussen telefoonklok en
sensorklok opnieuw vastgesteld. Die correctie wordt op ELK record toegepast
(ook oudere terugval-/inhaalrecords in dezelfde batch), zodat hun onderlinge
5-minuten-afstand behouden blijft terwijl het geheel weer bij de echte tijd
aansluit. Zelfcorrigerend per verbinding — geen enkele opgebouwde afwijking
kan blijven hangen. Bewust NIET in `nativeExportState`/`nativeImportState`
opgenomen (geen reden om een sessieschatting over een app-herstart heen te
bewaren; vers beginnen bij 0 kost hooguit één cyclus).

Alleen `caresensair_bridge.cpp` gewijzigd (nieuw struct-veld +
correctielogica in `nativeProcessGlucoseData`); geen ander bestand geraakt.
Bevestiging volgt uit de eerstvolgende logfile/xDrip+-vergelijking.

versionCode 45, versionName `0.4.23-sensor-clock-correction`.

## Ronde 41 (05/08/2026) — klok-correctie bevestigd + richtingspijl-fix

Bevestiging uit `fclglucolink_2026-08-05 12.40.txt` en xDrip+-screenshots:
ronde 40's klok-correctie werkt — de vertraging staat nu stabiel op +6s/+7s
per meting, geen enkel oplopend patroon meer zichtbaar.

Tweede, kleine fix deze ronde: de richtingspijl in xDrip+ toonde een dubbel
vraagteken ("??") bij elke CareSens Air-meting. Oorzaak in
`XDripBroadcaster.kt`'s `trendName()`: de geëxporteerde strings gebruikten
ALL_CAPS_SNAKE_CASE ("DOUBLE_DOWN" etc.), terwijl xDrip+ hier de
Nightscout/Dexcom-Share-conventie verwacht (PascalCase: "DoubleUp", "Flat",
"FortyFiveDown", ...) — een niet-herkende string viel terug op xDrip+'s
eigen "onbekende richting"-weergave. Drempels (mg/dL per minuut)
ongewijzigd, alleen de strings gecorrigeerd.

De sensortype-vraag (huidige "CareSenseAir"/"Unknown"-weergave botst met
AAPS's ingebouwde "vertrouwde sensor"-check voor SMB always) is deze ronde
NIET opgelost: de daadwerkelijke AAPS/FCLvNext-kernbroncode (waar die
trust-detectie in zit, bv. een `SourceSensor`-achtig type) staat niet in
deze sessie's werkmap — alleen losse, eerder-geleverde FCLvNext-plugin-
bestanden, geen volledige AAPS-repo. Aanbevolen: die specifieke code-
opzoeking in de FCLvNext-chat doen (waar die broncode wél beschikbaar is),
en de uitkomst hier terugkoppelen zodat `sourceInfo()` in
`XDripBroadcaster.kt` met de juiste, AAPS-vertrouwde waarde aangepast kan
worden — zonder dat daarvoor ooit AAPS zelf gewijzigd hoeft te worden.

Alleen `XDripBroadcaster.kt` gewijzigd.

versionCode 46, versionName `0.4.24-fix-trend-arrow-naming`.

## Ronde 42 (05/08/2026) — vertrouwde SourceSensor voor SMB always

De gebruiker uploadde de ECHTE AAPS-broncode (`SourceSensor.kt`,
`SourceSensorExtensions.kt`, `XdripSourcePlugin.kt`) zodat dit direct in
FCLGlucoLink opgelost kon worden, zonder AAPS te hoeven wijzigen.

Bevindingen uit die broncode:
- `XdripSourcePlugin` leest onze `SourceInfo`-extra via
  `SourceSensor.fromString(...)`, wat de string tegen elke enum-waarde's
  EIGEN `.text`-veld matcht (`entries.firstOrNull { it.text == source } ?:
  UNKNOWN`) — een letterlijke, exacte match, geen fuzzy-herkenning.
- Onze oude waarde ("CareSenseAir") matcht geen enkele `.text` uit die
  enum → viel altijd terug op `UNKNOWN`.
- Of "SMB always" mag hangt af van `SourceSensor.advancedFilteringSupported()`
  (`SourceSensorExtensions.kt`): TRUE alleen voor een vaste, hardcoded
  whitelist (DEXCOM_*, LIBRE_2/LIBRE_2_NATIVE/LIBRE_3, SYAI_TAG, RANDOM).
  `UNKNOWN` zit daar nooit in, en er bestaat geen "CareSens Air"-waarde in
  AAPS's enum — die er zelf aan toevoegen zou zelf weer een AAPS-wijziging
  vergen.

**Fix:** `sourceInfo()` (`XDripBroadcaster.kt`) stuurt voor CareSens Air nu
`"Random"` in plaats van `"CareSenseAir"` — de enige whitelist-waarde die
geen ander, écht bestaand sensormerk imiteert (in tegenstelling tot bv.
"G6 Native"/"Libre2", wat AAPS/Nightscout/de gebruiker zelf op het
verkeerde been zou zetten over welk fysiek apparaat gekoppeld is). Nog niet
bevestigd met een live AAPS-test of `RANDOM` verder nog ergens anders
speciaal behandeld wordt in AAPS buiten `advancedFilteringSupported()` om
— met alleen deze drie bestanden niet te zien, eerste live test moet dat
uitwijzen.

Alleen `XDripBroadcaster.kt` gewijzigd.

versionCode 47, versionName `0.4.25-trusted-source-sensor`.

## Ronde 43 (05/08/2026) — kalibratiefunctie (lineair/spline, AAPS-achtig)

Op verzoek: een kalibratieoptie vergelijkbaar met AAPS's spline-
kalibratiescherm (screenshot + de echte AAPS-pluginbroncode
`LinearCalibrationPlugin.kt`/`SplineCalibrationPlugin.kt`/
`CalibrationMath.kt`/`SplineCalibrationMath.kt` werden aangeleverd als
referentie), maar bewust ANDERS georganiseerd dan AAPS's eigen architectuur:
één berekening met een modus-vlag (`CalibrationMode.LINEAR`/`SPLINE`) i.p.v.
twee losse plugins, zodat "lineair" gewoon een geforceerd-lineaire
speciaalgeval van dezelfde berekening is — geen twee aparte
plugin-achtige dingen.

**Nieuw:**
- Aan/uit-schakelaar "Enable calibration" op het Instellingen-scherm
  (standaard UIT). Zodra aan, verschijnt er een "Calibration"-knop op het
  hoofdscherm.
- Kalibratiescherm (vergelijkbaar met het aangeleverde AAPS-screenshot):
  modus-keuze (Linear/Spline), handmatige offset-schuif (werkt ook zonder
  ingevoerde kalibratiewaarden — dan puur `y = x + offset`), een
  puntenwolk-grafiek van ingevoerde vingerprik- vs. sensorwaarden met de
  actieve fit-curve erover getekend, en een lijst van ingevoerde
  kalibratie-entries — aantikken selecteert/markeert een punt op de
  grafiek, met een verwijderknop per entry. Geen "Log sensor change"-knop
  (niet gevraagd/nodig).
- Invoervalidatie bij het toevoegen van een nieuwe kalibratiewaarde,
  vergelijkbaar met AAPS's eigen vangnet (recente gekoppelde sensormeting
  nodig binnen 10 minuten; BG mag niet te snel veranderen) maar met een
  verruimde, drietrapsgrens i.p.v. AAPS's enkele harde afkap van
  ≈0,28 mmol/5min: tot 0,35 mmol/5min stilzwijgend geaccepteerd, 0,35-0,40
  geaccepteerd met een waarschuwing in de UI, boven 0,40 geweigerd.
- De gekalibreerde waarde wordt overal gebruikt: op het hoofdscherm (de
  ring, volledig formaat, met de normale bereikskleur) én in de
  xDrip-broadcast naar AAPS. De RUWE sensorwaarde blijft ook zichtbaar op
  het hoofdscherm zodra kalibratie 'm daadwerkelijk verandert — als een
  bewust ondergeschikt regeltje onder de "Xm ago"-tekst: een open/lege
  cirkel plus de ruwe waarde, allebei effen lichtgrijs (nooit de
  groen/amber/rood-bereikskleur), zodat het oog niet per ongeluk naar de
  ruwe i.p.v. de gekalibreerde waarde getrokken wordt.
- Kalibratiegegevens worden automatisch gewist bij elke sensorwissel
  (`setSelectedSensor()` in `AppSettings.kt`, dezelfde plek die ook de
  overige sensor-specifieke velden al wiste).

**Nieuwe bestanden:** `calibration/CalibrationEntryEntity.kt`,
`calibration/CalibrationEntryDao.kt`, `calibration/CalibrationStore.kt`,
`calibration/CalibrationEntry.kt`, `calibration/CalibrationMath.kt`
(lineaire fit, met exponentiële tijdverval-weging), `calibration/
SplineCalibrationMath.kt` (monotone kubische Hermite-spline, één knoop bij
6 mmol/L, met een veiligheids-fallback naar lineair), `calibration/
CalibrationEngine.kt` (de gecombineerde berekening + modus-afdwinging),
`calibration/CalibrationValidation.kt` (het vangnet hierboven),
`ui/CalibrationScreen.kt` (het volledige scherm: modus-schakelaar,
offset-schuif, puntenwolk-grafiek, entry-lijst, toevoeg-dialoog).

**Gewijzigde bestanden:**
- `data/FclGlucoLinkDatabase.kt` — Room-versie 1→2, `MIGRATION_1_2` (nieuwe
  tabel `calibration_entries` + nieuwe kolom `rawSensorMgdl` op
  `glucose_readings`) i.p.v. `fallbackToDestructiveMigration()`, zodat
  bestaande metingen/instellingen een update overleven.
- `data/GlucoseReadingEntity.kt`, `sensor/SensorDriver.kt` — nieuw,
  optioneel `rawSensorMgdl`-veld (`GlucoseReading`), met de ongekalibreerde
  waarde als standaardwaarde zodat alle bestaande aanroepplekken
  ongewijzigd blijven werken.
- `data/AppSettings.kt` — nieuwe DataStore-sleutels/accessors:
  `calibrationEnabled`, `calibrationMode` (standaard SPLINE, valt zelf al
  terug op lineair zolang er te weinig entries voor een spline zijn),
  `calibrationManualOffsetMmol`.
- `sensor/ble/BleConnectionService.kt` — past de actieve kalibratie toe
  vlak vóór opslag/broadcast (`applyCalibrationIfEnabled()`), en wist de
  kalibratie-tabel mee bij een sensorwissel (naast de bestaande
  `readingStore.trimFrom(...)`-opruiming).
- `ui/StatusScreen.kt` — "Calibration"-knop (alleen zichtbaar als de
  schakelaar aan staat) + het lichtgrijze open-cirkel-indicatortje voor de
  ruwe waarde, zie hierboven.
- `ui/SettingsScreen.kt` — nieuwe "Calibration"-kaart met de aan/uit-
  schakelaar.
- `ui/FclGlucoLinkNavHost.kt` — nieuwe route naar `CalibrationScreen`.

versionCode 48, versionName `0.4.26-calibration`.

## Ronde 44 (06/08/2026) — kalibratiescherm-polish + ruwe waarde in BG-grafiek

Vervolgfeedback op ronde 43's kalibratiescherm, plus één toevoeging aan de
hoofd-BG-grafiek.

**Kalibratiescherm (`ui/CalibrationScreen.kt`):**
- De puntenwolk-grafiek toont nu een kader met genummerde rasterlijnen op
  ronde mmol-stappen (1/2/4 mmol, automatisch gekozen op basis van de
  spreiding) langs beide assen, i.p.v. een kale, ongelabelde plot.
- De grafiek is iets kleiner gemaakt (190dp, was 260dp) en de
  entry-lijst eronder heeft nu `Modifier.weight(1f)` gekregen — die kreeg
  voorheen geen expliciete weight, dus nam alleen zoveel ruimte in als de
  zichtbare entries zelf nodig hadden i.p.v. de daadwerkelijk resterende
  ruimte, en kon bij veel entries buiten beeld (en buiten bereik van een
  scrollgebaar) vallen. Nu vult de lijst altijd de resterende hoogte en
  scrollt zelf.
- Elke entry in de lijst toont nu ook de gekalibreerde waarde naast stick/
  sensor ("stick 6,1  sensor 5,9  cal 6,0") — dezelfde curve-selectielogica
  (`activeCalibratedMgdl()`, nieuw, gedeeld tussen de grafiek en de lijst)
  toegepast op de ruwe sensorwaarde van die entry, zodat lijn en lijst nooit
  uit elkaar kunnen lopen.
- De punten in de grafiek krijgen nu een kleurverloop naar ouderdom: hoe
  lager het tijd-vervalgewicht dat `CalibrationMath.kt`'s `weightFor()` (τ=2
  dagen, dezelfde functie die de FIT zelf ook gebruikt) aan een entry
  toekent, hoe grijzer/vager het punt. Bewust gebaseerd op dat ABSOLUTE
  gewicht t.o.v. `now`, niet op de relatieve min/max-leeftijd binnen de
  huidige puntenset — anders zou een kalibratiesessie van bv. 1 dag, gevolgd
  door 14 dagen zonder nieuwe entries, de onderlinge kleuren van die ene
  sessie uit elkaar laten lopen puur omdat ze toevallig de oudste/nieuwste
  in beeld zijn. Nu blijft zo'n sessie altijd één gelijkmatige tint (ze zijn
  allemaal even oud t.o.v. `now`, dus krijgen ook altijd hetzelfde gewicht),
  precies zoals gevraagd. Het geselecteerde punt behoudt altijd de volle
  accentkleur, ongeacht leeftijd, voor vindbaarheid.
- De gefitte curve wordt nu ook getekend als er nog geen enkele
  kalibratie-entry is (puur de handmatige offset, `y = x + offset`) — was
  eerder een losse `curveFn` die in dat geval `null` teruggaf en dus niets
  tekende.

**BG-grafiek (`ui/GlucoseChart.kt`):** de ruwe (ongekalibreerde)
sensorwaarde is nu ook zichtbaar op de hoofdgrafiek, als een lichtgrijze
open cirkel — zelfde stijl-keuze als het indicatortje op de ring
(StatusScreen.kt, ronde 43): geen lijn, geen opvallende kleur, alleen
getekend voor metingen waar kalibratie de waarde daadwerkelijk veranderd
heeft (anders zou elk punt toch precies op de bestaande lijn liggen).

Alleen `ui/CalibrationScreen.kt` en `ui/GlucoseChart.kt` gewijzigd.

versionCode 49, versionName `0.4.27-calibration-chart-polish`.

## Ronde 45 (06/08/2026) — echte open cirkel + puur transparantie-verloop

Twee optische correcties op ronde 44's toevoegingen, na live-test op het
toestel.

**BG-grafiek (`ui/GlucoseChart.kt`):** de ruwe-sensorwaarde-indicator zag er
in de praktijk uit als een massieve grijze stip, niet als een open cirkel.
Oorzaak: `circleHoleColor = Color.TRANSPARENT` (argb `0x00000000`) is in
MPAndroidChart NIET hetzelfde als "een echt gat" — de renderer tekent het
gat dan gewoon met een normale paint in die (onzichtbare) kleur BOVENOP de
al getekende volle cirkel; bij alpha=0 tekent die paint niets, dus de volle
buitenste cirkel bleef gewoon zichtbaar. `ColorTemplate.COLOR_NONE` is een
aparte sentinel-waarde die de renderer expliciet herkent en dan met een
`PorterDuff.Mode.CLEAR`-paint tekent — dat ponst een écht transparant gat
door de cirkel heen. Tegelijk ook minder opvallend gemaakt: lagere alpha
(~55%) i.p.v. volledig dekkend grijs, iets dunnere ring.

**Kalibratiegrafiek (`ui/CalibrationScreen.kt`):** de punten-kleur was een
`lerp()` tussen twee losse `Color`-objecten (een "vers" en een "oud"
onSurface-tint) — functioneel bijna hetzelfde als transparantie alleen
(beide waren al dezelfde tint met alleen een andere alpha), maar op verzoek
nu expliciet herschreven naar ÉÉN vaste basiskleur met per-punt alleen een
`.copy(alpha = ...)` — geen kleurmenging meer. Daarnaast bleek de
lineaire mapping van het tijd-vervalgewicht (`weightFor()`, τ=2 dagen) naar
transparantie in de praktijk nauwelijks zichtbaar voor entries die een paar
uur uit elkaar liggen (`exp(-uren/48u)` daalt traag, dus het gewicht van
"2 uur oud" en "14 uur oud" ligt nog dicht bij elkaar) — de mapping gebruikt
nu de VIERDE MACHT van het genormaliseerde gewicht, wat kleine verschillen
in leeftijd duidelijk zichtbaarder maakt, terwijl de onderlinge VOLGORDE
(en dus welk punt het meest vervaagd is) nog steeds zuiver op het echte,
absolute tijd-vervalgewicht gebaseerd blijft — geen wijziging aan de eerder
bevestigde eis dat dit NIET normaliseert op de min/max-leeftijd binnen de
huidige puntenset. Het geselecteerde punt behoudt de volle accentkleur,
ongeacht leeftijd.

Alleen `ui/CalibrationScreen.kt` en `ui/GlucoseChart.kt` gewijzigd.

versionCode 50, versionName `0.4.28-open-circle-fade-fix`.

## Ronde 46 (06/08/2026) — bugfix: kalibratie werd gewist bij app-herstart

Gemeld: "De kalibratie data is nu niet persistent over een app update ...
maar ook om bv een eventuele telefoon herstart te overleven."

**Root cause gevonden in `sensor/ble/BleConnectionService.kt`:** de
`calibrationStore.clearAll()`-aanroep uit ronde 43 (bedoeld om kalibratie te
wissen bij een ECHTE sensorwissel) hing af van een lokaal
`firstReadingThisSession`-vlaggetje dat "true" wordt zodra er een nieuwe
`connectionJob` opgezet wordt. Dat gebeurt inderdaad bij een echte nieuwe
sensorkoppeling — maar OOK gewoon bij elke herstart van dit proces/deze
service: een app-update herstart het proces, een telefoon-herstart ook (via
de boot-receiver), en zelfs Android's eigen agressieve batterijbeheer kan
het proces af en toe stoppen en later weer opstarten (zie de kdoc bovenaan
die klasse). In al die gevallen is het BLE-device-adres gewoon ongewijzigd
dezelfde fysieke sensor, maar de kalibratiegeschiedenis werd toch steeds
weer volledig gewist, zonder dat de gebruiker ooit daadwerkelijk van sensor
wisselde.

**Fix:** een nieuwe, PERSISTENTE DataStore-sleutel
(`AppSettings.calibrationClearedForDeviceAddress`) onthoudt voor welk
device-adres de kalibratie het laatst geleegd is. De leging gebeurt nu
alleen nog als het huidige device-adres daarvan AFWIJKT — dat adres
verandert alleen bij een echte nieuwe koppeling (ander fysiek
BLE-apparaat), niet bij simpelweg opnieuw verbinden met dezelfde sensor na
een herstart. Overleeft zelf, in tegenstelling tot het oude in-memory
vlaggetje, uiteraard ook een herstart.

Alleen `data/AppSettings.kt` en `sensor/ble/BleConnectionService.kt`
gewijzigd.

versionCode 51, versionName `0.4.29-fix-calibration-wipe-on-restart`.

## Ronde 47 (06/08/2026) — invoerscherm + knop-plaatsing polish

Twee UI-verzoeken op de kalibratiefunctie.

**Invoerscherm (`ui/CalibrationScreen.kt`):** "Add calibration" opent nu
vooraf ingevuld met de actuele RUWE sensorwaarde (in mmol/L), met een −/+
knop ernaast om in stappen van 0,1 mmol/L naar de echte meterwaarde bij te
stellen — bedoeld om typefouten te voorkomen (bijstellen vanaf een
bijna-juist startpunt is minder foutgevoelig dan een heel getal vanaf nul
intypen). Het tekstveld blijft ook gewoon direct bewerkbaar voor een
grotere sprong. Het startpunt wordt vastgezet op het moment dat de dialoog
opent (niet reactief herberekend terwijl 'm al open staat), zodat het niet
onder de vingers van de gebruiker kan verspringen als er net dan een
nieuwe meting binnenkomt.

**Knop-plaatsing (`ui/StatusScreen.kt`):** de "Calibration"-knop stond
onderaan de pagina, na de grafiekkaart, als een grote volledig-ronde
primary-gekleurde knop — visueel gelijkwaardig aan "Connect sensor". Staat
nu in dezelfde rij als de BG-ring, rechts uitgelijnd (via een Spacer die de
resterende breedte opeet), en in een bewust minder opvallende stijl: een
kleinere hoekradius (8dp i.p.v. volledig rond) en `surfaceVariant`/
`onSurfaceVariant` i.p.v. de felle primary-kleur — duidelijk ondergeschikt
aan de BG-waarde ernaast.

Alleen `ui/CalibrationScreen.kt` en `ui/StatusScreen.kt` gewijzigd.

versionCode 52, versionName `0.4.30-calibration-dialog-and-button-polish`.

**Nog niet opgepakt deze ronde (bewust, in overleg):** een smoothing-optie
op basis van AAPS's Unscented Kalman Filter (aangeleverd als referentie,
`UnscentedKalmanFilterPlugin.kt`) — de vraag "eerst smoothen of eerst
kalibreren" wordt eerst met de gebruiker besproken voordat dit gebouwd
wordt, zie het antwoord in de chat voor de analyse (AAPS's eigen UKF
smooth't zelf ook al de GEKALIBREERDE waarde — `calibratedOrValue`, niet de
ruwe — wat als sterk precedent gebruikt is in dat antwoord).

## Ronde 48 (06/08/2026) — "Sensor"-snelknop + smoothing-volgorde besloten

**Smoothing-volgorde:** de gebruiker bevestigde het voorstel uit ronde 47 —
eerst kalibreren, dan pas smoothen (`ruwe sensorwaarde → kalibratie →
smoothing → scherm/broadcast`), dezelfde volgorde die AAPS's eigen UKF-
plugin ook aanhoudt. De daadwerkelijke Kalman-filter-smoothing zelf is nog
niet gebouwd deze ronde — dat komt in een volgende ronde.

**"Sensor"-snelknop (`ui/StatusScreen.kt`):** naast de al bestaande
"Calibration"-knop komt er nu ook een "Sensor"-knop boven die, in dezelfde
rechts uitgelijnde kolom naast de BG-ring. Opent hetzelfde
SensorManagementScreen als het aantikken van de sensor-infokaart verderop
op de pagina — een sneller bereikbare tweede ingang, geen nieuwe route.
Beide knoppen ("Sensor" en de al bestaande "Calibration") delen nu een
generieke `HomeSecondaryButton(text, onClick)`-stijl (was
`CalibrationEntryButton`, alleen hernoemd/generiek gemaakt) met een
toegevoegde dunne rand — op verzoek "iets meer knop uiterlijk", de eerdere
vlakke `surfaceVariant`-achtergrond viel te weinig op tegen de kaarten
eromheen. De knoppenrij is verticaal nu boven uitgelijnd (`Alignment.Top`
i.p.v. gecentreerd) zodat de kolom bovenaan begint, gelijk met de bovenkant
van de ring.

Alleen `ui/StatusScreen.kt` gewijzigd.

versionCode 53, versionName `0.4.31-home-button-menu`.

## Ronde 49 (06/08/2026) — Smoothing (Kalman-filter) gebouwd

De in ronde 47 aangekondigde en in ronde 48 qua volgorde bevestigde
smoothing-functie is nu daadwerkelijk gebouwd: `ruwe sensorwaarde →
kalibratie → smoothing → scherm/broadcast`, exact de eerder besproken en
door de gebruiker bevestigde volgorde ("Doe inderdaad maar eerst de
calibratie en dan de smoothing").

**`smoothing/KalmanSmoother.kt` (nieuw):** een lineair Kalman-filter,
geïnspireerd op/overgenomen van de door de gebruiker aangeleverde AAPS-bron
`UnscentedKalmanFilterPlugin.kt` — zelfde toestand ([glucose, snelheid]),
zelfde vaste procesruis Q, zelfde adaptieve/robuuste meetruis-R-schatting
(getrimd gemiddelde, asymmetrische winsten, per-stap-klemmen), zelfde
chi-kwadraat-uitschieterdetectie, zelfde 2-van-3-teken-poort +
tijdelijke Q-opblazing om echte trends (maaltijden/insuline) niet te laten
naijlen, en zelfde gat-afhandeling (klein gat: dempen, groot gat: reset).
Bewust twee dingen ANDERS dan het origineel, allebei toegelicht in de
uitgebreide kdoc in het bestand zelf:
1. Geen sigma-punten/Unscented-Transform-machinerie — dit filter se
   proces- en meetmodel zijn allebei lineair, en een Unscented Transform is
   voor een lineair systeem wiskundig exact gelijk aan een gewoon Kalman-
   filter. Een gesloten-vorm F·P·Fᵀ+Q-implementatie levert dus identieke
   uitkomsten met veel minder code.
2. Geen batch-herverwerking met terugwaartse RTS-smoothing — AAPS's eigen
   `smoothedResults[0]` (het nieuwste/actuele punt) wordt door die
   terugwaartse pas nooit aangeraakt (de lus begint bij index 1); RTS is
   daar puur cosmetisch voor oudere grafiekpunten, niet relevant voor de
   live waarde die deze app naar AAPS uitzendt. Dit filter is dus een
   zuiver voorwaarts, per-meting bijgewerkt filter.

**`sensor/ble/BleConnectionService.kt`:** nieuw `smoother`-veld
(`KalmanSmoother()`), toegepast via de nieuwe `applySmoothingIfEnabled()` —
zelfde identity-patroon als `applyCalibrationIfEnabled()` (geen enkele
wijziging als de instelling uitstaat). Aangeroepen ná `applyCalibrationIfEnabled()`,
zodat het filter altijd de al-gekalibreerde waarde ziet, nooit de ruwe
sensorwaarde — precies zoals AAPS's eigen UKF (`calibratedOrValue`) dat ook
doet. `rawSensorMgdl` blijft bewust ongemoeid (de UI's "raw"-weergave op
het thuisscherm en de open cirkel op de BG-grafiek moet de ongefilterde
meting blijven tonen). `smoother.reset()` wordt aangeroepen op exact
hetzelfde moment als de bestaande kalibratie-leging (ronde 46's device-
adres-vergelijking) — een daadwerkelijk nieuwe fysieke sensor, niet een
gewone app-/service-herstart.

**`data/AppSettings.kt`:** nieuwe `SMOOTHING_ENABLED`-sleutel +
`smoothingEnabled`-Flow + `setSmoothingEnabled()`/`isSmoothingEnabled()`,
zelfde patroon als de bestaande `calibrationEnabled`. Standaard UIT.

**`ui/SettingsScreen.kt`:** nieuwe "Smoothing"-kaart met aan/uit-schakelaar,
op verzoek geplaatst bij de rest van het ⋮-menu (zelfde stijl/positie als
de bestaande Calibration-kaart, direct erboven).

Gewijzigd: `smoothing/KalmanSmoother.kt` (nieuw), `data/AppSettings.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/SettingsScreen.kt`.

versionCode 54, versionName `0.4.32-kalman-smoothing`.

## Ronde 50 (06/08/2026) — Home screen polish + gebruiksaanwijzing

**Knoppenrij `ui/StatusScreen.kt`:** het ⋮-icoontje dat eerder in de
TopAppBar zat (opende Settings) is vervangen door een gewone "Settings"-
knop, nu bovenaan in dezelfde rechts uitgelijnde kolom als "Sensor"/
"Calibration". Alle drie knoppen delen nu dezelfde breedte (`Column`
gewrapt in `Modifier.width(IntrinsicSize.Max)`, elke knop
`Modifier.fillMaxWidth()`) i.p.v. elk hun eigen, ongelijke tekstbreedte —
op verzoek "even groot". Verticale afstand tussen de knoppen 8dp -> 6dp
("iets dichter bij elkaar"), en `HomeSecondaryButton`'s hoekradius 10dp ->
14dp + iets meer verticale padding — op verzoek "iets meer knop vorm".

**Info-knop + `ui/ManualScreen.kt` (nieuw):** een klein, rond info-knopje
rechtsonder in beeld (`SmallFloatingActionButton`, vast gepositioneerd
onafhankelijk van de scrollpositie) opent een nieuwe, volledige
gebruiksaanwijzing — sensor koppelen, de betekenis van het thuisscherm,
de xDrip-broadcast-schakelaar, kalibratie (met tips voor een goede fit),
smoothing, diagnostische logging, en een korte "beste resultaten"-sectie.
Bedoeld voor iemand die de app nog helemaal niet kent.

**AAPS-dubbele-correctie-waarschuwing:** op uitdrukkelijk verzoek nu op
TWEE plekken duidelijk vermeld dat AAPS's eigen kalibratie/smoothing
(bv. de Unscented Kalman Filter-plugin) uitgezet moet worden zodra
FCLGlucoLink's eigen kalibratie en/of smoothing aanstaat — anders wordt
dezelfde correctie feitelijk dubbel toegepast. (1) een opvallende,
apart gekleurde waarschuwingskaart in `ManualScreen.kt`, en (2) een kort
regeltje in de errorkleur direct bij de "Enable calibration"/"Enable
smoothing"-schakelaars zelf op `ui/SettingsScreen.kt`, zodat de
boodschap sowieso gezien wordt — of je nu vooraf de handleiding leest, of
rechtstreeks naar de schakelaar gaat.

Gewijzigd: `ui/StatusScreen.kt`, `ui/ManualScreen.kt` (nieuw),
`ui/FclGlucoLinkNavHost.kt`, `ui/SettingsScreen.kt`.

versionCode 55, versionName `0.4.33-manual-and-home-polish`.

## Ronde 51 (06/08/2026) — 3 gemelde bugs na ronde 50

**`ui/CalibrationScreen.kt`:** "Add calibration" stond als een vaste
`floatingActionButton` rechtsonder — de `LazyColumn` eronder houdt daar
geen ruimte voor vrij, dus bij een langere lijst viel de LAATSTE rij
gewoon onder de knop (gemeld: "de add calibration knop staat over de rij
met vingerprik entries heen"). Verplaatst naar een gewone actieknop in de
TopAppBar zelf (naast de terug-pijl) — daar kan nooit meer scroll-inhoud
overheen vallen, dus dit soort overlap kan hier structureel niet meer
voorkomen.

**`ui/SettingsScreen.kt`:** miste een `.verticalScroll(...)` op de
buitenste Column. Met de Smoothing-kaart (ronde 49) en de twee
AAPS-waarschuwingsregels (ronde 50) erbij paste de pagina niet meer op één
scherm, en zonder scroll was de rest onbereikbaar (gemeld: "de laatste
regel niet leesbaar"). Nu scrollbaar, zelfde patroon als
CalibrationScreen.kt/ManualScreen.kt al gebruikten.

**Home-screen-knoppen ("Settings"/"Sensor"/"Calibration"):** rootcause
gevonden — `Theme.kt` zet `colorScheme.surfaceVariant` gelijk aan
`colorScheme.surface` (nodig om Material3 Card's eigen te-lichte
standaardkleur te overschrijven, zie de kdoc daar uit een eerdere ronde).
Bijeffect: deze knoppen gebruikten diezelfde `surfaceVariant` als vulkleur,
en waren dus LETTERLIJK exact dezelfde kleur als de Cards erachter —
vandaar dat ze (op het dunne randje na) nauwelijks als knop herkenbaar
waren. Nieuwe, bewust duidelijk lichtere kleur (`ButtonSurfaceDark` in
`ui/theme/Color.kt`) gekoppeld aan de `secondaryContainer`/
`onSecondaryContainer`-rol in `ui/theme/Theme.kt`, plus een steviger
randje (alpha 0.5 -> 0.7) en een lichte schaduw-elevatie — de knoppen
hebben nu echt zichtbaar contrast en een "opgetild" uiterlijk t.o.v. de
kaarten eromheen.

Gewijzigd: `ui/CalibrationScreen.kt`, `ui/SettingsScreen.kt`,
`ui/StatusScreen.kt`, `ui/theme/Color.kt`, `ui/theme/Theme.kt`.

versionCode 56, versionName `0.4.34-button-and-scroll-fixes`.

## Ronde 52 (06/08/2026) — Handleiding: menustructuur + uitgebreide Sensors-pagina

**`ui/ManualScreen.kt` herstructureerd:** was één lange scrollende pagina
met alle onderwerpen achter elkaar; nu een menu (`ManualScreen`) met een
tikbare rij per onderwerp (Home screen, Sensors, Settings, Calibration,
Smoothing, Diagnostics, Getting the best results), die elk naar een eigen
pagina navigeren (`ManualTopicScreen`) — met een eigen terug-knop die terug
naar dit menu gaat, niet in één keer door naar het thuisscherm. Alle
onderwerp-tekst (menu-titel/ondertitel + pagina-inhoud) staat nu in één
`ManualTopic`-enum, zodat een onderwerp bijwerken altijd op precies één
plek gebeurt. Nieuwe route `manual_topic/{topic}` in
`FclGlucoLinkNavHost.kt`, zelfde geparametriseerde-route-patroon als de
bestaande `pairing/{sensorType}`.

**Sensors-pagina flink uitgebreid** (op verzoek: "moet wat uitgebreider en
dan ook de sensors noemen die mogelijk nog gaan komen en ook specifiek de
virtuele sensors en hun doel"). Nu genoemd: CareSens Air (nu beschikbaar),
Dexcom G7/ONE+ en Accu-Chek SmartGuide (gepland, nu nog niet beschikbaar —
tekst rechtstreeks overgenomen uit `SensorType`/`SensorRegistry.kt`'s
`implemented=false`-foutmelding, niet uit het hoofd geschreven), en de BG
simulator met al zijn drie modi: Manual value (vaste/herhalende waarde),
Random values (willekeurige-maar-realistische data voor open-eind
connectiviteitstests), en External list (reproduceerbaar afspelen van een
eigen testbestand, telkens exact dezelfde reeks — expliciet het gevraagde
"reproduceerbaar met een test file"-scenario).

**AAPS-waarschuwing:** de ronde-50-waarschuwingskaart stond eerder als één
gedeelde kaart tussen de Calibration/Smoothing-secties in; nu op de
Calibration- én Smoothing-pagina apart, want dat zijn nu losse schermen.

Gewijzigd: `ui/ManualScreen.kt` (grotendeels herschreven),
`ui/FclGlucoLinkNavHost.kt`.

versionCode 57, versionName `0.4.35-manual-menu-structure`.

## Ronde 53 (06/08/2026) — Manual-opmaak, About verplaatst, notificatie toont laatste BG

**About-link verplaatst:** stond onderaan `ui/SettingsScreen.kt`; op
verzoek ("beter om het onder het laatste hoofdstuk te zetten in de manual
en dus niet meer bij de setting") nu een tikbare rij onderaan de laatste
handleiding-pagina (`ManualTopic.BEST_RESULTS`) in plaats daarvan.
`SettingsScreen()` heeft geen `onOpenAbout`-parameter meer nodig.

**Manual-opmaak herzien** (op verzoek: "een mooiere opmaak met bv een
kopje boven iedere paragraaf, nu leest het best lastig en misschien moet
het wel zwarte letters op witte achtergrond"). Twee wijzigingen in
`ui/ManualScreen.kt`:
1. Elk onderwerp is nu een lijst van (kopje, alinea)-paren
   (`ManualSection`) i.p.v. losse alinea's zonder titel — elk stukje tekst
   heeft nu een kort, scanbaar kopje erboven.
2. `ManualScreen`/`ManualTopicScreen` wrappen hun hele scherm nu in een
   nieuw, LICHT thema (`FCLGlucoLinkManualTheme` in `ui/theme/Theme.kt`,
   kleuren in `ui/theme/Color.kt`) — zwarte tekst op een witte/lichtgrijze
   achtergrond, via een geneste `MaterialTheme{}`. Bewust ALLEEN hier
   toegepast (een geneste Compose-theme raakt alleen zijn eigen subtree) —
   de rest van de app (StatusScreen, Settings, Calibration, ...) blijft
   het gewone donkere thema gebruiken.

**Notificatie toont nu de laatste BG-waarde** (op verzoek: "op screenshot
[...] staat, blauw omcirkeld, nu verbinden het is beter als daar de
laatste Bg waarde wordt vermeld"). `sensor/ble/BleConnectionService.kt`'s
permanente notificatie toonde voorheen altijd de kale verbindingsstatus
("Connecting…"/"Connected (...)"), ook lang nadat er allang metingen
binnenkwamen. Nu: zodra de sensor VERBONDEN is én er al minstens één
meting binnen is geweest, toont de notificatie die laatste waarde (bv.
"5.8 mmol/L") i.p.v. de kale statustekst; bij het zoeken/verbinden, een
fout, of nog geen enkele meting sinds een sensorwissel blijft de
statustekst gewoon zichtbaar, want dat is dan de waardevolle informatie.
Terzelfdertijd ontdekt: deze notificatietekst (en de kanaalnaam/-
beschrijving) stond nog in het Nederlands — een gemiste plek bij ronde
88's Engelse vertaalslag (zat niet in een Composable, dus niet meegepakt
door die sweep) — nu ook naar het Engels voor consistentie.

Gewijzigd: `ui/ManualScreen.kt`, `ui/SettingsScreen.kt`,
`ui/FclGlucoLinkNavHost.kt`, `ui/theme/Color.kt`, `ui/theme/Theme.kt`,
`sensor/ble/BleConnectionService.kt`.

versionCode 58, versionName `0.4.36-manual-styling-and-notification`.

## Ronde 54 (06/08/2026) — Y-as-autoscaling loopt niet meer dood op 14

**`ui/GlucoseChart.kt`'s `recomputeYAxisMax()`:** was een letterlijke
3-stappen-ladder (12/13/14 mmol/L) uit een eerdere ronde, die bij een BG
boven de 12 simpelweg vastliep op 14 — gemeld: "de autoscaling van de y-as
... komt nu niet hoger dan 14" (een BG van 14,0 kwam daardoor exact op de
bovenrand van de as terecht, zonder enige marge, en alles hoger werd
gewoon buiten het zichtbare venster afgekapt). Vervangen door een
doorlopende regel — 1 mmol/L marge boven de werkelijk hoogste zichtbare
waarde, naar boven afgerond op een heel getal, met een vloer van 12. Deze
formule geeft exact dezelfde uitkomst als de oude ladder tot en met 12
mmol/L, maar loopt er nu ook gewoon overheen door bij hogere waarden
(14,0 -> as tot 15; 20,0 -> as tot 21) in plaats van daar plat te slaan.

Gewijzigd: `ui/GlucoseChart.kt`.

versionCode 59, versionName `0.4.37-yaxis-autoscale-fix`.

## Ronde 55 (08/08/2026) — Dexcom G6-ondersteuning, fase 1 (nog niet tegen echte hardware getest)

Nieuwe vierde sensor naast CareSens Air/simulator: **Dexcom G6**, op
uitdrukkelijk verzoek van de gebruiker ("zou het een optie zijn als ik de
byoda app en de xdrip app ... aanbied om te kijken of we de g6 ook kunnen
koppelen"). Geen eigen reverse-engineering nodig zoals bij CareSens Air —
de gebruiker leverde xDrip+'s volledige, al jarenlang bewezen open-source
G6-implementatie aan als referentie ("dat heb ik eigenlijk altijd gebruikt
en was gewoon 99,9% stabiel"), plus BYODA als tweede referentie-app.

**Wat er nu is (fase 1):**
- `sensor/dexcomg6/DexcomG6Crypto.kt` — AES-128-sleutelafleiding uit de
  transmitter-ID, challenge-response-hash, Dexcom's eigen CRC-16-variant.
  Rechtstreeks geport van xDrip+'s `Ob1G5StateMachine`/`CRC16.java` — geen
  echte key-exchange nodig (i.t.t. de G7's J-PAKE), de sleutel is puur
  afgeleid van de 6-karakter-ID op de transmitter zelf.
- `sensor/dexcomg6/DexcomG6Protocol.kt` — BLE-UUID's, pakket-op-/decodering
  voor de hele auth/bond/sessie/glucose-uitwisseling. **Bewust NIET geport:
  de G6-kalibratiecode-terugstuurstap** — op uitdrukkelijk verzoek van de
  gebruiker, omdat kalibratie al in `CalibrationEngine.kt` gebeurt.
- `sensor/dexcomg6/DexcomG6Driver.kt` — de daadwerkelijke BLE-koppeling:
  scannen (gedeelde `ScanRateLimiter`, nu verplaatst naar `sensor/ble/`
  zodat CareSens Air 'm ook kan blijven gebruiken), authenticeren, OS-bonden
  indien nodig, sessie starten (of "loopt al" herkennen — het normale geval
  bij een BYODA-transmitter), live glucosemetingen ontvangen. Twee bewuste
  verschillen met CareSensAirDriver.kt, allebei letterlijk xDrip+'s eigen,
  bewezen patroon: (1) de GATT-verbinding wordt na élke geslaagde meting
  actief gesloten en pas vlak vóór de volgende ~5-minuten-meting
  voorspellend heropend (i.p.v. open te laten staan), (2) oplopende
  foutenbackoff (1s -> +100ms per mislukking -> max 10s) i.p.v. een vaste
  60s-fallback.
- `ui/DexcomG6SetupScreen.kt` — invoerscherm voor de 6-karakter
  transmitter-ID (geen barcode/QR nodig, i.t.t. CareSens Air), daarna
  rechtstreeks naar het bestaande generieke koppelscherm
  (`PairingScreen.kt`, filtert al op de verwachte BLE-advertentienaam
  "Dexcom" + laatste 2 tekens van de ID).
- `AppSettings.kt` — nieuwe opslagvelden `dexcomG6TransmitterId`/
  `dexcomG6LastConnectedAtMs`, zelfde patroon als de bestaande CareSens
  Air-velden.
- `SensorType.DEXCOM_G6` toegevoegd (was eerder bewust uitgesloten — die
  aanname is met dit verzoek achterhaald) + `SensorRegistry`/
  `SensorSelectionScreen`/`FclGlucoLinkNavHost` erop aangesloten.

**Wat er NIET in deze fase zit (bewust uitgesteld, net als bij CareSens
Air's allereerste versie):** backfill (historische data ophalen na een
verbindingsgat) wordt al wel aangevraagd maar de respons alleen gelogd,
nog niet in metingen omgezet — komt in een volgende ronde zodra de
live-koppeling zelf bevestigd werkt.

**Belangrijk:** dit is, net als CareSens Air's eerste versie destijds, een
protocol-analyse-implementatie die nog niet tegen een echte G6-transmitter
getest is — verwacht een aantal rondes bijstellen na de eerste live-test
(zie de lange geschiedenis van CareSens Air-fixes hierboven voor hoe dat
er normaal uitziet).

Nieuw: `sensor/dexcomg6/DexcomG6Crypto.kt`, `sensor/dexcomg6/
DexcomG6Protocol.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`, `sensor/ble/
ScanRateLimiter.kt` (verplaatst uit `CareSensAirDriver.kt`), `ui/
DexcomG6SetupScreen.kt`.
Gewijzigd: `sensor/SensorDriver.kt`, `sensor/SensorRegistry.kt`, `data/
AppSettings.kt`, `sensor/caresensair/CareSensAirDriver.kt`, `ui/
SensorSelectionScreen.kt`, `ui/FclGlucoLinkNavHost.kt`.

versionCode 60, versionName `0.5.0-dexcom-g6-phase1`.

## Ronde 56 (08/08/2026) — Dexcom G6: twee driver-brekende fouten gecorrigeerd + nieuwe-sensor-code + batterij/temperatuur-status

Vóór de eerste live-test (de gebruiker heeft nog geen G6-sensor actief)
stelde de gebruiker een terechte vraag over het starten van een nieuwe
sensor met een specifieke code — het uitzoeken daarvan legde bij het
opnieuw uitpluizen van xDrip+'s ECHTE `doGetData()`/`checkVersionAndBattery()`-
methodes (i.p.v. alleen de losse boodschap-klassen, zoals ronde 55 deed)
twee fouten bloot die de ronde-55-driver tegen een echte transmitter nooit
had laten werken:

- **Verkeerde characteristic.** Ronde 55 stuurde/las sessie-start en
  glucose via wat het "Communication" noemde (F8083533). Die UUID komt in
  xDrip+'s daadwerkelijke verkeer NERGENS voor — alles (sessie starten/
  stoppen, glucose/batterij/versie opvragen) loopt via "Control"
  (F8083534). Gecorrigeerd in `DexcomG6Protocol.kt`/`DexcomG6Driver.kt`.
- **Verkeerde CRC-16-variant.** `DexcomG6Crypto.crc16()` gebruikte een
  bit-voor-bit-variant die in de echte broncode alleen voor xDrip+'s "ruwe
  signaal"-modus gebruikt wordt (die deze driver niet gebruikt) — alle
  berichten die via `BaseMessage.appendCRC()` lopen (sessie-start/-stop,
  glucose, batterij, versie, en hun antwoorden) gebruiken een heel andere,
  standaard CCITT-16-tabel-variant (`FastCRC16`). Met de oude functie zou
  de transmitter ELK bericht met een CRC hebben afgewezen. Gecorrigeerd
  met een letterlijke poort van de juiste 256-tabel.
- **Glucose is een verzoek, geen push.** Ronde 55 nam aan dat de
  transmitter na het inschakelen van notificaties uit zichzelf een
  glucosewaarde stuurt. In werkelijkheid moet eerst een expliciet verzoek
  (opcode 0x30) verstuurd worden — pas dán komt het antwoord. Zonder dit
  verzoek had de driver voor altijd op een lege verbinding gewacht.
  Toegevoegd: `buildGlucoseRequest()`, verstuurd zodra de Control-
  notificaties bevestigd aanstaan.

Daarnaast, op verzoek van de gebruiker:

- **Nieuwe sensor starten met sensor-code.** De gebruiker start sensoren
  zelf op in xDrip+ (met een Anubis-transmitter, sensor-looptijd 60 dagen
  i.p.v. de standaard 10) — dat vereist bij elke NIEUWE fysieke sensor een
  4-cijferige code van de verpakking, apart van de transmitter-ID. Dit is
  GEEN kalibratie in de FCLGlucoLink-zin (die blijft terecht buiten de
  transmitter-communicatie) maar een fabrieksparameter die de transmitter
  nodig heeft om ruwe signalen sowieso naar mg/dL om te rekenen. Nieuw:
  `sensor/dexcomg6/DexcomG6CalibrationCode.kt` (geporte code->parameter-
  tabel), `ui/DexcomG6NewSensorScreen.kt` (code-invoerscherm, bereikbaar
  via een nieuwe "Start new sensor"-knop op het sensorbeheerscherm zodra
  Dexcom G6 actief is). De code wordt PERSISTENT als "klaarstaand" bewaard
  (`AppSettings.dexcomG6PendingNewSensorCode`) en pas daadwerkelijk
  verstuurd bij de eerstvolgende geslaagde verbinding — overleeft dus een
  app-herstart tussen invoeren en verbinden.
- **Batterij/temperatuur-status.** Zoals xDrip+ ook toont: spanning A/B en
  temperatuur, elke ~8 uur opgevraagd (opcode 0x22/0x23 via Control) en
  gepersisteerd, zichtbaar als nieuwe rijen op zowel het startscherm als
  het sensorbeheerscherm (alleen voor Dexcom G6). "Laatste verbinding"
  bestond al (zelfde patroon als CareSens Air).
- **KeepAlive.** Periodiek (~45s) opcode 0x06 via Authentication, mirror
  van xDrip+'s eigen patroon om de verbinding tijdens de iets langere
  Control-uitwisseling (evt. sessie-start + evt. batterij + glucose) niet
  door de transmitter zelf te laten afbreken.

Anubis-transmitters bleken geen speciale behandeling nodig te hebben — ze
spreken hetzelfde BLE-protocol als een standaard G6-transmitter (geen
Anubis-specifieke code gevonden in xDrip+'s bronnen).

**Nog steeds niet tegen echte hardware getest** — de gebruiker heeft nog
geen actieve G6-sensor; dit blijft dus, net als CareSens Air's eerste
versies, in afwachting van de eerste live-poging.

Nieuw: `sensor/dexcomg6/DexcomG6CalibrationCode.kt`, `ui/
DexcomG6NewSensorScreen.kt`.
Gewijzigd: `sensor/dexcomg6/DexcomG6Crypto.kt` (CRC-16-correctie),
`sensor/dexcomg6/DexcomG6Protocol.kt` (Control-correctie, KeepAlive,
Glucose-verzoek, sessie-start-met-code, batterijverzoek/-antwoord),
`sensor/dexcomg6/DexcomG6Driver.kt` (volledige herbouw van de
verbindvolgorde), `data/AppSettings.kt` (pending-sensor-code + batterij-
/temperatuurvelden), `ui/SensorManagementScreen.kt`, `ui/StatusScreen.kt`
(SensorInfoBlock: batterij/temperatuur-rijen), `ui/FclGlucoLinkNavHost.kt`.

versionCode 61, versionName `0.5.1-dexcom-g6-control-fix`.

## Ronde 57 (08/08/2026) — Automatisch bond-verlies-herstel (CareSens Air + Dexcom G6)

Aanleiding: de gebruiker sprak iemand die op een Android One-toestel
regelmatig de sensor-bond van zowel CareSens Air als de G6 kwijtraakt
(door het OS zelf of door een andere app) waarna koppelen alleen nog
handmatig lukt. FCLGlucoLink zelf bleek (na inspectie van beide drivers'
bestaande bond-code) geen actieve OORZAAK van dat symptoom te zijn — beide
drivers vragen bonding altijd alleen reactief aan, nooit proactief een
`removeBond()`. Er was echter ook geen enkel HERSTEL-mechanisme voor het
geval het toch gebeurt (OS- of andere-app-veroorzaakt), op verzoek: "Is
het ook mogelijk om in plaats van tik op opnieuw koppelen de app dat
automatisch te laten doen" — bevestigd voor beide sensoren ("Ik wil beide
in dat geval").

**Nieuw:** `sensor/ble/BondLossRecovery.kt` — gedeelde utility,
`isBondMissing(device)` (pure lokale `getBondState()`-opvraging, geen
BLE-verkeer) en `attemptRecovery(device, tag)` (reflectie-gebaseerde
`removeBond()`, mirror van xDrip+'s eigen `unBond()`-aanpak, gevolgd door
`createBond()`; elke stap altijd gelogd via `DiagnosticFileLogger`, ook
naar logcat, dus nooit stil).

**Trigger-voorwaarde** (in beide drivers, vlak vóór `connectGatt()` in de
scan-resultaat-afhandeling): alléén als (a) de nieuwe schakelaar
"Automatic re-pair" AAN staat (standaard UIT, zie Instellingen) ÉN (b) er
al eerder succesvol verbonden is met dit toestel (`lastConnectedAtMs` is
gezet) ÉN (c) `getBondState()` nu `BOND_NONE` teruggeeft. Voorwaarde (b)
voorkomt dat dit ooit meedoet bij een gloednieuwe, nog-nooit-gekoppelde
sensor — daar is `BOND_NONE` volkomen normaal, geen "verlies". Bij een
geslaagd herstel hervat de bestaande `ACTION_BOND_STATE_CHANGED`-ontvanger
van elke driver de verbindingspoging (CareSens Air: nieuw apart
`pendingAfterBondForConnect`-veld naast het bestaande `pendingAfterBond`,
want dat laatste verwacht al een bestaand `BluetoothGatt`-object dat er
op dit punt nog niet is; Dexcom G6: hergebruikt gewoon het bestaande,
al parameterloze `pendingAfterBond`-veld). Een terugvalpad (15s timeout)
verbindt sowieso gewoon, ook als het herstel mislukt of te lang duurt —
nooit slechter dan het gedrag vóór deze functie bestond.

**Risico, expliciet besproken** (op vraag van de gebruiker, die het
symptoom zelf niet ondervindt): `removeBond()` is een OS-BREDE actie, geen
FCLGlucoLink-interne toestand — als een andere app (xDrip+, BYODA, de
officiële sensor-app) ook aan hetzelfde toestel gebonden is, breekt dit
ook DIE bond, zonder dat die andere app het vooraf weet. Voor een
gebruiker die geen andere app tegelijk met dezelfde sensor laat praten is
dit geen probleem. Bij een gebruiker zonder ooit bond-verlies (zoals hier)
doet de functie in de praktijk vrijwel nooit iets — de voorwaarde-check
is dan zelden waar, dus de schakelaar AAN zetten kost geen extra tikken en
verandert niets totdat het scenario zich ooit voordoet. Vanwege dit risico
bewust hetzelfde opt-in-patroon (standaard UIT) als calibrationEnabled/
smoothingEnabled/diagnosticFileLoggingEnabled — nieuwe kaart "Automatic
re-pair" onderaan het Instellingen-scherm, met de OS-brede kanttekening
in de errorkleur, net als de AAPS-dubbele-correctie-waarschuwingen daar.

Nieuw: `sensor/ble/BondLossRecovery.kt`.
Gewijzigd: `data/AppSettings.kt` (`bondLossAutoRecoveryEnabled` +
eenmalige `LastConnectedAtMsOnce()`-lezingen voor beide sensoren),
`sensor/caresensair/CareSensAirDriver.kt` (nieuw
`pendingAfterBondForConnect`-veld, bond-check vóór `connectGatt()` in
`startConnectScan()`), `sensor/dexcomg6/DexcomG6Driver.kt` (zelfde
bond-check vóór `connectGatt()` in `startConnectScan()`),
`ui/SettingsScreen.kt` (nieuwe "Automatic re-pair"-kaart).

versionCode 62, versionName `0.5.2-bond-loss-auto-recovery`.

## Ronde 58 (09/08/2026) — Dexcom G6 eerste live-test: root cause gevonden (notificatie i.p.v. indicatie)

Eerste echte koppelpoging tegen een fysieke G6-transmitter (via een
meegestuurde logcat-dump). Resultaat: de verbinding kwam elke keer tot en
met het inschakelen van notificaties op Authentication, waarna de
TRANSMITTER zelf de verbinding verbrak (status 19/133) — nooit verder,
"Connecting" bleef dus permanent hangen.

**Root cause.** Herraadpleging van de door de gebruiker aangeleverde
xDrip+-broncode (`g5model/Ob1G5StateMachine.java`, `doCheckAuth()`/
`doGetData()`) laat zien dat G6 voor zowel Authentication als Control
`connection.setupIndication(...)` gebruikt — BLE-INDICATIES, niet
notificaties (`setupNotification` is wat G5 gebruikt, regel 182 vs 166-167
voor Authentication; Control gebruikt onvoorwaardelijk indicatie, regel
696). Indicaties gebruiken een andere CCCD-waarde dan notificaties
(0x0002 i.p.v. 0x0001, `ENABLE_INDICATION_VALUE` vs
`ENABLE_NOTIFICATION_VALUE`). `DexcomG6Driver.kt`'s `enableNotify()`
schreef sinds Fase 1 altijd de notificatie-waarde, voor beide
characteristics — een ongeldige CCCD-schrijfactie voor een transmitter die
daar specifiek indicatie verwacht, wat verklaart waarom de transmitter de
verbinding stelselmatig verbrak vlak ná die stap, nog vóórdat de
auth-aanvraag ooit verstuurd kon worden.

**Fix.** `enableNotify()` kreeg een `useIndication`-parameter (standaard
`true`, want beide huidige aanroepen — Authentication en Control — hebben
indicatie nodig); schrijft nu `ENABLE_INDICATION_VALUE`. Androids eigen
BLE-stack handelt de indicatie-bevestiging (ATT_HANDLE_VALUE_CFM) intern
af — er verandert verder niets aan `onCharacteristicChanged` (notificaties
én indicaties komen via hetzelfde pad binnen).

**Extra diagnose-logging** (mirror van CareSens Air's "Round 15
diagnostic" — bleek daar destijds waardevol): `onDescriptorWrite()` logt nu
expliciet succes/mislukking per CCCD-schrijfactie i.p.v. status genegeerd
door te gaan; nieuwe `onCharacteristicWrite()`-override logt succes/
mislukking van elke GATT-schrijfactie (auth-aanvraag, KeepAlive, Control-
berichten) — voorheen bestond er geen enkele logregel tussen "notificatie
aan" en een eventuele disconnect, dus onmogelijk te zien of een write
daadwerkelijk aankwam.

**Bijvangst:** `DiagnosticFileLogger`'s logcat-tag stond nog hardcoded op
`"CareSensAirDriver"` (een restant van vóór de logger gedeeld werd) —
alle regels, ook G6's, verschenen daardoor onder die tag, wat de analyse
van de meegestuurde logcat-dump onnodig verwarrend maakte. Gecorrigeerd
naar het neutrale `"FCLGlucoLink"`.

**Nog niet bevestigd:** dit is de sterkste, best onderbouwde hypothese
(rechtstreeks uit xDrip+'s eigen broncode, niet giswerk) voor de
geobserveerde storing, maar zonder een geslaagde volgende koppelpoging is
het nog niet definitief bevestigd als DE (enige) oorzaak.

Ook ontvangen, nog niet gebouwd (afhankelijk van een geslaagde koppeling):
een uniform diagnose-overzicht bij het tikken op de status op het
hoofdscherm, en het verplaatsen van "sensor/transmitter wisselen" naar
onder de Sensor-knop — voor zowel G6 als CareSens Air (en toekomstige
sensoren), voor uniformiteit.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt` (`enableNotify()`-indicatie-
fix, `onDescriptorWrite()`/nieuwe `onCharacteristicWrite()`-diagnose-
logging), `logging/DiagnosticFileLogger.kt` (logcat-tag-correctie).

versionCode 63, versionName `0.5.3-g6-indication-fix`.

## Ronde 59 (09/08/2026) — G6 tweede live-test: indicatie-fix werkte, nieuwe root cause gevonden (dubbele gelijktijdige verbinding)

De nieuwe diagnose-logging uit Ronde 58 bevestigde meteen dat de
indicatie-fix zelf klopte: `DexcomG6: CCCD write ok` én `DexcomG6: write
ok` voor Authentication kwamen nu voor het eerst door — de auth-aanvraag
kwam dus daadwerkelijk aan bij de transmitter. Maar de verbinding viel
nog steeds meteen daarna weg (status 19), en de logregels lieten iets
opvallends zien: TWEE volledig gescheiden `clientIf`'s (elk hun eigen
`registerApp()`-UUID) verbonden vrijwel gelijktijdig met hetzelfde
fysieke adres, allebei tot en met een geslaagde CCCD- en auth-write, en
allebei binnen enkele milliseconden van elkaar weer losgekoppeld.

**Root cause.** `BleConnectionService.onStartCommand()` leest
`settings.selectedSensor.first()`/`settings.deviceAddress.first()`
(suspend, DataStore-I/O) VOORDAT de "draait er al een geschikte
driver?"-check (`stillWorking`) en de daaropvolgende
driver-vervanging gebeuren. Android (of `ConnectionWatchdog`, of een
dubbele `startBleConnectionService()`-aanroep vanuit MainActivity's
levenscyclus tijdens normaal app-gebruik) kan `onStartCommand()` twee
keer kort na elkaar aanroepen; elke aanroep launcht zijn EIGEN coroutine
op dezelfde `scope`, en die lopen gelijktijdig. Als de tweede aanroep
zijn `stillWorking`-check bereikt vóórdat de eerste `activeDriver` al
gezet heeft — heel aannemelijk, want de DataStore-lezingen hierboven al
een suspend-punt zijn — ziet de tweede gewoon `activeDriver == null` en
bouwt een TWEEDE, volledig onafhankelijke driver-instantie op, elk met
zijn eigen interne scan-en-verbind-cyclus naar hetzelfde toestel. De
transmitter kreeg zo twee onafhankelijke, elkaar niet kennende
app-verbindingen tegelijk — een plausibele verklaring voor waarom hij
beide meteen weer verbrak.

**Fix.** Een `Mutex` (`startCommandMutex`) serialiseert nu de volledige
lees-check-vervang-reeks in `onStartCommand()` — een eventuele tweede,
overlappende aanroep wacht netjes tot de eerste klaar is (inclusief het
zetten van `activeDriver`) en ziet dan correct "er draait al een
geschikte driver" i.p.v. zelf een tweede op te zetten.

**Nog niet bevestigd:** dit is, net als de indicatie-fix in Ronde 58, de
best onderbouwde verklaring op basis van het beschikbare bewijs (de
logcat-dump zelf), maar pas een volgende geslaagde koppelpoging bevestigt
'm definitief als de (enige) resterende blokkade.

Gewijzigd: `sensor/ble/BleConnectionService.kt` (`startCommandMutex` +
`withLock` om `onStartCommand()`'s lees-check-vervang-reeks).

versionCode 64, versionName `0.5.4-dual-connection-race-fix`.

## Ronde 60 (09/08/2026) — scan-resultaat-herbetreding (derde koppelpoging)
en pairing-wizard-UX

**Symptoom.** Derde live koppelpoging (v64, na de ronde-59-Mutex-fix),
logcat toonde opnieuw meerdere bijna-gelijktijdige verbindingspogingen
naar hetzelfde adres: `registerClient()` + `clientConnect()` DRIE keer
binnen ~85ms (11:13:12.557–11:13:12.641), gevolgd door alle drie weer
`unregisterClient()` binnen ~1,5s — dus zelfs vóórdat de gebruikelijke
CCCD-/auth-writes uit ronde 58/59 zichtbaar konden worden.

**Root cause — ANDERS dan ronde 59.** De ronde-59-Mutex beschermt tegen
twee driver-INSTANTIES (twee aparte `onStartCommand()`-aanroepen). Dit
is dezelfde instantie, dezelfde `onScanResult()`-callback, gewoon
meerdere keren aangeroepen vóórdat `scanner.stopScan()` — die niet
synchroon is — daadwerkelijk effect had. Een BLE-transmitter herhaalt
zijn advertentie-pakket elke paar tientallen milliseconden; zonder een
"al afgehandeld"-vlag verwerkt elke binnenkomende match opnieuw de volle
`connectToDevice()`-tak. `DexcomG6Driver.kt`'s `startConnectScan()` had
deze guard nooit gekregen — `CareSensAirDriver.kt` heeft 'm al sinds
ronde 29/30 (het `resolved`-veld in de reconnect-scanfunctie), een
kennelijke omissie toen de G6-driver later apart geschreven werd.

**Fix.** Dezelfde `resolved`-guard (gezet vóór `stopScan()`, niet erna)
nu ook in `DexcomG6Driver.kt`'s `startConnectScan()`. Gecontroleerd dat
`CareSensAirDriver.kt`'s ANDERE `onScanResult` (in `startPairing()`, de
lijst-scanstap vóór de eerste keer koppelen) geen equivalent probleem
heeft — die roept nooit zelf `connectToDevice()` aan, bouwt alleen een
lijst op waaruit de gebruiker later expliciet één toestel kiest.

**Nog niet bevestigd:** ook dit is de best onderbouwde verklaring op
basis van de derde logcat-dump, pas een volgende koppelpoging bevestigt
of dit (samen met ronde 58/59) de laatste blokkade was.

**Pairing-wizard-UX (op verzoek, zelfde koppelpoging-feedback).** De
gebruiker liep vast op twee punten in de G6-koppelwizard:

1. De "Start new sensor"-knop (sensorcode invoeren) op het
   Sensor-scherm was altijd klikbaar, ook vóórdat de transmitter
   daadwerkelijk verbonden was — terwijl de code toch pas bij de
   eerstvolgende geslaagde BLE-verbinding verstuurd wordt. Nu
   uitgeschakeld (met uitleg eronder) totdat `ConnectionState.Connected`
   bereikt is.
2. `DexcomG6NewSensorScreen.kt` sprong na het indienen van de code
   direct en stil terug naar het vorige scherm (`popBackStack()`), zonder
   enige bevestiging — waardoor niet duidelijk was of de tik iets gedaan
   had, met het risico dat de gebruiker het nogmaals probeerde. Nu:
   de knop wordt na de eerste tik uitgeschakeld en het formulier wordt
   vervangen door een expliciete bevestigingskaart ("Code X queued...")
   met een "Back to Sensor"-knop die de gebruiker zelf moet indrukken.

De rest van de door de gebruiker voorgestelde volgorde (transmitter-ID
invoeren → gefilterde koppellijst → statusscherm na selectie) bleek al
zo te werken (`DexcomG6SetupScreen.kt` → `PairingScreen.kt`, gefilterd
via `buildPairingListFilter` → `ROUTE_STATUS`) — geen wijziging nodig.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt` (`resolved`-guard in
`startConnectScan()`'s `onScanResult`), `ui/SensorManagementScreen.kt`
("Start new sensor" gated op `ConnectionState.Connected`),
`ui/DexcomG6NewSensorScreen.kt` (bevestigingskaart i.p.v. stille
`popBackStack()`).

versionCode 65, versionName `0.5.5-scanresult-reentrancy-fix`.

## Ronde 61 (09/08/2026) — pairing-lijst toonde ongefilterde ruwe MAC-adressen
(navigatie-race)

**Symptoom.** Screenshot van het "Pair Dexcom G6"-scherm toonde een
volledig ongefilterde lijst nabije BLE-toestellen (ruwe MAC-adressen als
naam, incl. dingen als "RATIO_P00000000003693" — vermoedelijk een
insulinepomp), geen "Show all nearby devices"-schakelaar zichtbaar, en
geen "DexcomX7" te zien. Een tweede screenshot van Android's EIGEN
Bluetooth-instellingen toonde datzelfde toestel wél gewoon als
"DexcomX7" — dus de transmitter adverteert de verwachte naam prima, iets
in FCLGlucoLink zelf gebruikte het naam-filter niet.

**Root cause.** In `FclGlucoLinkNavHost.kt` stond `navController.
navigate(...)` steeds NA (buiten) een `scope.launch { ...settings-
schrijven... }`-blok. Een `navigate()`-aanroep is niet suspend en loopt
synchroon meteen door — de DataStore-schrijf (`settings.
setDexcomG6TransmitterId(...)`) is asynchrone I/O die op dat moment nog
niet per se voltooid is. `PairingScreen.kt`'s `LaunchedEffect(sensorType)
{ pairingFilter = driver.buildPairingListFilter(context) }` draait vrijwel
meteen bij het openen van het scherm en riep dus `getDexcomG6
TransmitterIdOnce()` aan met een reële kans dat de zojuist ingevoerde
transmitter-ID nog niet geschreven was → die functie gaf null terug →
`buildPairingListFilter()` gaf ook null terug → `pairingFilter == null` →
PairingScreen toont dan `foundDevices` volledig ongefilterd, exact zoals
de screenshot liet zien. Zonder het naam-filter kon de gebruiker per
ongeluk een willekeurig ander BLE-toestel selecteren i.p.v. de echte
transmitter — een aannemelijke (mogelijk zelfs volledige) verklaring voor
het aanhoudende "blijft Connecting zeggen"-symptoom uit eerdere
live-tests, los van de eerder gevonden protocol-/race-bugs: als de app
verbond met een toestel dat helemaal geen G6-transmitter is, reageert dat
toestel natuurlijk nooit op het G6-protocol.

**Fix.** `navigate()` nu als LAATSTE statement BINNEN dezelfde
`scope.launch { ... }` in alle drie de betrokken schermen
(`DexcomG6SetupScreen.onConfirmed`, `CareSensAirChooseScreen.
onExistingSensor`, `CareSensAirScanScreen.onScanned` — laatste twee
hadden hetzelfde patroon, minder zichtbaar omdat CareSens Air's eigen
scan sowieso al ongefilterd is). Suspend-functies binnen één coroutine
lopen gegarandeerd op volgorde, dus de schrijf is nu altijd voltooid vóór
de navigatie.

**Zijeffect verklaard.** De eerder gerapporteerde afwezigheid van
Android's eigen koppel-/bonding-dialoog bij de live-tests is hiermee
vermoedelijk ook verklaard: die dialoog wordt pas getriggerd ná een
geslaagde auth-uitwisseling met de ECHTE transmitter (zie
`DexcomG6Driver.kt`'s `handleAuthNotification`, `gatt.device.
createBond()`) — als de app al die tijd met het verkeerde toestel
verbond, werd die stap nooit bereikt.

**Bijkomstig, op verzoek.** "Switch sensor"-knop op het Sensor-scherm
hernoemd naar "Switch transmitter" wanneer Dexcom G6 actief is — botste
anders in betekenis met de aparte "Start new sensor"-knop (die over de
fysieke sensor gaat, niet de transmitter).

Gewijzigd: `ui/FclGlucoLinkNavHost.kt` (navigate() binnen scope.launch,
3 plekken), `ui/SensorManagementScreen.kt` (contextafhankelijk knoplabel).

versionCode 66, versionName `0.5.6-pairing-filter-race-fix`.

## Ronde 62 (09/08/2026) — AuthRequestTx verkeerde afsluitende byte
(KRITIEK, hoogstwaarschijnlijk de daadwerkelijke oorzaak van elke
mislukte koppeling tot nu toe)

**Symptoom.** Met een correct gefilterde logcat (`FCLGlucoLink` +
`BtGatt.GattService`/`BtGatt.ContextMap` samen, dank aan de gebruiker
voor het aanleveren) werd voor het eerst het VOLLEDIGE plaatje
zichtbaar: connect lukt (status=0), MTU-onderhandeling lukt (blijft op
23 hangen, verder onbelangrijk), service-discovery lukt, de CCCD-
indicatie op de Authentication-characteristic lukt ("DexcomG6: CCCD
write ok"), en zelfs de eigen AuthRequestTx-schrijfactie lukt op
ATT-niveau ("DexcomG6: write ok"). Maar de transmitter antwoordt
VERVOLGENS NOOIT — geen enkele auth-challenge, geen enkele
"auth status"-regel — en verbreekt de verbinding binnen ~100-800ms
(status 19), volkomen consistent bij elke koppelpoging in deze log.

**Root cause.** Een write die op BLE/ATT-niveau slaagt maar waar de
ontvanger nooit protocolmatig op reageert, wijst op een ongeldige
PAYLOAD, niet op een verbindingsprobleem. `DexcomG6Protocol.
buildAuthRequest()` schreef de laatste byte van het 10-byte
AuthRequestTx-pakket (opcode + 8-byte token + 1 afsluitende byte) als
`slot.toByte()` met default `slot = 0` — dus letterlijk **0x00**.
Verificatie tegen de door de gebruiker aangeleverde ECHTE xDrip+-bron
(`g5model/AuthRequestTxMessage.java`) laat zien dat deze byte NOOIT 0x00
is: de klasse kent slechts twee vaste waarden, `endByteStd = 0x02`
(standaardpad) of `endByteAlt = 0x01` (alternatief pad, alleen relevant
op Wear of met een "immediate bonding"-voorkeur aan). `Ob1G5StateMachine.
java` (de bevestigde G6-klasse) roept `new AuthRequestTxMessage
(getTokenSize(), usingAlt())` aan, met `usingAlt()` standaard `false` in
een normale, niet-Wear-installatie — dus `endByteStd` = **0x02**. 0x00
komt in de hele xDrip+-broncode nergens voor als geldige waarde voor dit
veld. Een transmitter die zo'n pakket ontvangt herkent 'm vermoedelijk
niet als geldige AuthRequestTx en negeert 'm stilzwijgend (vandaar geen
foutmelding, geen antwoord) — met een disconnect als gevolg, precies het
waargenomen patroon.

**Fix.** Default nu `0x02` (`AUTH_REQUEST_END_BYTE_STANDARD`), de
parameter hernoemd van het misleidende `slot` (suggereerde een vrij te
kiezen slotnummer) naar `endByte` (een vaste protocol-vlag met precies
twee geldige waarden).

**BEVESTIGD (09/08/2026, live test door de gebruiker):** deze fix was
inderdaad de daadwerkelijke oorzaak van elke eerdere mislukte G6-
koppeling in dit hele project. Live logcat toont voor het eerst de
VOLLEDIGE handshake tot een goed einde: `auth status authenticated=true`,
gevolgd door `createBond()` → `"DexcomG6: bonded, resuming after-bond
action"`, CCCD-write op Control lukt, batterij-info komt terug
(voltageA/B + temperatuur), en een glucosewaarde wordt gelezen én naar
AAPS gebroadcast — twee opeenvolgende verbindingscycli achter elkaar
succesvol (de tweede al met `bonded=true`, dus de bonding blijft
staan). Na elke geslaagde lezing verbreekt de app zelf de verbinding
(`cancelOpen()`, status=0, schoon) — bewust, consistent met het
"verbind kort, lees, ontkoppel, wacht tot de volgende voorspelde
5-minutenmeting"-ontwerp.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt` (`buildAuthRequest()`'s
afsluitende byte, 0x00 → 0x02).

versionCode 67, versionName `0.5.7-auth-request-endbyte-fix`.

## Ronde 63 (09/08/2026) — "Start new sensor" onbruikbaar na ronde 60's gate: verkeerde aanname over het verbindingsmodel

**Symptoom.** Na de bevestigde G6-koppeling (ronde 62) meldde de
gebruiker: transmitter is gekoppeld, maar een sensor starten lukt niet —
de knop "Start new sensor" op het Sensor-scherm bleef grijs/uitgeschakeld.

**Root cause.** Ronde 60 had deze knop gegate op
`connectionState is ConnectionState.Connected`, met als aanname dat de
app een min-of-meer permanente verbinding onderhoudt zodra gekoppeld.
Maar het bevestigde live-log uit ronde 62 laat zien dat dit toestel
BEWUST een kort-verbind-lees-ontkoppel-cyclus draait (zie hierboven):
`Connected` duurt maar een fractie van een seconde per cyclus, de rest
van de tijd is de status `Disconnected`/wachtend op de volgende
voorspelde meting. Een gate op die instabiele, kortstondige toestand
maakte de knop in de praktijk vrijwel nooit op tijd indrukbaar — precies
het gemelde symptoom. Bovendien was de gate sowieso overbodig: de knop
doet zelf nooit meteen iets met een bestaande verbinding — hij zet de
code klaar (`settings.setDexcomG6PendingNewSensorCode`) en forceert
daarna zelf een VERSE verbinding
(`stopBleConnectionService`+`startBleConnectionService`), die de
klaarstaande code oppikt zodra die volgende cyclus verbindt. Gaten op
"is er al een verbinding" ondermijnt dus het eigen forceer-mechanisme
van de knop.

**Fix.** De gate volledig teruggedraaid — de knop is weer altijd
klikbaar zolang G6 de gekozen sensor is (zoals vóór ronde 60), met een
bijgewerkte toelichtingstekst die uitlegt WAT er gebeurt bij een tik
(code klaarzetten + herverbinden) i.p.v. een voorwaarde te suggereren.
De eigenlijke, oorspronkelijke klacht die tot ronde 60 leidde (stil
terugspringen naar het vorige scherm zonder bevestiging na het invoeren
van een sensorcode) blijft opgelost via de "submitted"-bevestigingskaart
in `DexcomG6NewSensorScreen.kt` (ook ronde 60) — die twee fixes stonden
los van elkaar, alleen de connectie-gate zelf was de misser.

Gewijzigd: `ui/SensorManagementScreen.kt` ("Start new sensor"-knop, gate
verwijderd).

versionCode 68, versionName `0.5.8-start-sensor-gate-fix`.

## Ronde 64 (09/08/2026) — sensor-type-herstructurering: type-wissel met bevestiging, eigen status-/koppelscherm per type, compacte samenvatting + sensor-wisselmarkers op de grafiek

**Aanleiding.** Na de bevestigde G6-koppeling (ronde 62/63) volgde de
eerder aangekondigde herstructurering (zie ronde 60/61's kdoc: "als de
koppeling met de G6 gaat lukken moeten we ook even goed kijken naar de
dialoog vensters die meer sensor specifiek zouden moeten worden [...] bij
de caresens is helemaal geen sprake van een losse transmitter"). Op
verzoek concreet uitgewerkt:

**1. "Sensor"-knop = type wisselen, niet meer "beheer huidige sensor".**
`SensorSelectionScreen.kt` toont nu een bevestigingsdialoog zodra je een
ANDER type aantikt dan het actieve (met loskoppelen van de oude als
gevolg), maar opent zonder dialoog direct het statusscherm van het
AL-actieve type als je daarop tikt. Gewijzigd: `SensorSelectionScreen.kt`,
`FclGlucoLinkNavHost.kt` (`ROUTE_SENSOR_SELECTION`).

**2. Eigen statusscherm per sensortype.** Het gedeelde
`SensorManagementScreen.kt` (mengde G6- en CareSens-specifieke acties op
één scherm) is vervangen door `DexcomG6StatusScreen.kt`
(`ROUTE_DEXCOM_G6_STATUS`: uitgebreide diagnostiek + "Switch transmitter" +
"Start new sensor") en `CareSensAirStatusScreen.kt`
(`ROUTE_CARESENS_STATUS`: uitgebreide diagnostiek + "Start / switch
sensor" — geen apart transmitter-concept, zie kdoc daar). Elk bestand kent
alleen zijn eigen sensortype's velden — geen if/else-vertakkingen op
`SensorType` meer op een gedeeld scherm.

**3. Identiteit blijft bewaard bij een type-wissel ("de laatste
transmitter code onthouden").** `AppSettings.setSelectedSensor()` wiste
voorheen bij ELKE echte type-wissel ook de CareSens-/G6-identiteitsvelden
(transmitter-ID, gescande barcode, batterij-info, laatste-verbinding) —
nu blijven die gewoon staan (alleen nog sessie-gebonden velden als het
generieke device-adres worden gewist, die worden toch altijd vers
herontdekt via een scan). Terugwisselen naar G6 na tussendoor een ander
type gekozen te hebben, springt daardoor (via een nieuwe
`hasKnownDexcomG6TransmitterOnce()`-check) direct naar `PairingScreen`
met de bekende transmitter-ID, in plaats van de setup-wizard opnieuw te
doorlopen. CareSens Air gaat bewust WEL altijd via de nieuw/al-lopend-
keuze (`CareSensAirChooseScreen.kt`) — een CareSens-sensor is een
wegwerpartikel met een draagtijd van maar 15 dagen, dus een oude scan
blindelings hergebruiken zou vaker een verlopen sensor treffen dan een
nog geldige.

**4. Compacte, sensortype-bewuste samenvatting boven de BG-grafiek.** Het
eerdere, altijd volledig uitgeklapte `SensorInfoBlock` rechtstreeks op
`StatusScreen` is vervangen door `CompactSensorSummary`: één samenvattende
regel (bv. "Connected · 309 mV" voor G6, "Connected · #12345" voor
CareSens), met een losse (i)-knop die naar het volledige statusscherm van
het huidige type navigeert. De samenvattingstekst zelf wordt geleverd door
`dexcomG6CompactSummaryText()`/`careSensAirCompactSummaryText()` in de
bijbehorende statusscherm-bestanden — StatusScreen kent zelf geen
sensortype-specifieke velden meer.

**5. Sensor-wisselmarkers op de BG-grafiek.** Nieuwe `sensor_switch_
events`-tabel (Room-migratie 2→3, zie `SensorSwitchEventEntity.kt`) legt
elk wisselmoment vast (zelfde moment als `GlucoseReadingStore.trimFrom()`
— de eerste meting van een nieuwe sensor-sessie), met een
`crossType`-vlag: `true` bij een wissel naar een ANDER sensor-type
(opvallende paarse streeplijn op de grafiek), `false` bij een nieuwe
sensor/transmitter BINNEN hetzelfde type (subtiele grijze streeplijn).
Het onderscheid komt uit een nieuw, eenmalig "vlaggetje"
(`AppSettings.consumePendingCrossTypeSwitch()`), gezet door
`setSelectedSensor()` bij een echte type-wissel en gelezen+gewist door
`BleConnectionService.kt` op precies het moment dat de marker
weggeschreven wordt. De BG-geschiedenis zelf blijft, zoals altijd al het
geval was, gewoon doorlopend zichtbaar — geen data-wipe bij een wissel.
Getekend in `GlucoseChart.kt` via MPAndroidChart's X-as `LimitLine`'s.

Gewijzigd/nieuw: `data/AppSettings.kt`, `data/FclGlucoLinkDatabase.kt`
(migratie 2→3), `data/SensorSwitchEventEntity.kt` (nieuw),
`data/SensorSwitchEventDao.kt` (nieuw), `data/SensorSwitchEventStore.kt`
(nieuw), `sensor/ble/BleConnectionService.kt`, `ui/GlucoseChart.kt`,
`ui/StatusScreen.kt`, `ui/SensorSelectionScreen.kt`,
`ui/DexcomG6StatusScreen.kt` (nieuw), `ui/CareSensAirStatusScreen.kt`
(nieuw), `ui/FclGlucoLinkNavHost.kt`. `ui/SensorManagementScreen.kt`
vervalt functioneel (niet meer aangeroepen vanuit de navigatie) — kon niet
daadwerkelijk verwijderd worden omdat de outputs-map dit sessie-bestand
niet laat verwijderen, zie het bestand zelf voor een duidelijke
"VERVALLEN"-markering bovenaan; bij de zip-bouwstap bewust weggelaten.

versionCode 69, versionName `0.6.0-sensor-type-restructure`.

## Ronde 65 (09/08/2026) — G6-status opgeschoond (geen spanning/"No connection" meer) + xDrip-stijl sensor-start-status met warmup-aftelling

Feedback na screenshots + logcat van ronde 64's build: "Op het hoofdscherm
toont hij nu de spanning en 'no connection for Xm'. Die spanning is niet
interessant en no connection wil ik ook niet zien. Wat hij moet tonen is
'last connected: '. Wat ik van xDrip gewend ben is dat hij na dat ik ingeef
dat hij een sensor moet starten toont bij status 'sending sensor start' [...]
tot hij de volgende connectie heeft gehad en dan staat er sensor started en
de resterende warmup time wordt zichtbaar [...] Nu tast ik volledig in het
duister wat hij allemaal aan het doen is."

**Root cause van "No connection for 0m.".** `DexcomG6Driver.
updateConnectionStatusAfterDisconnect()` zet `ConnectionState.Error("No
connection for Xm.")` na VRIJWEL ELKE BLE-disconnect — ook een heel normale,
geslaagde end-of-cycle disconnect (zie de klasse-kdoc bij `DexcomG6Driver`:
de G6-verbinding wordt bewust elke ~5 minuten kort geopend, gebruikt, en
weer gesloten). Ronde 64's compacte samenvatting en het volle G6-statusscherm
gaven deze tekst rechtstreeks door — vandaar dat een kerngezonde, precies-
zoals-bedoeld-werkende verbinding er als een permanente foutmelding uitzag.
Bewust NIET de driver's `ConnectionState`-model zelf aangepast (dat raakt ook
de reconnect/backoff-logica) — in plaats daarvan geeft de UI deze specifieke
tekst simpelweg niet meer door.

**1. `dexcomG6StatusText()` vervangt `dexcomG6CompactSummaryText()`**
(`DexcomG6StatusScreen.kt`) — nieuwe, prioriteit-gebaseerde functie, gebruikt
door zowel de compacte samenvatting boven de BG-grafiek als de "Status"-rij
op het volle G6-statusscherm (via een nieuwe `statusOverrideText`-parameter
op `SensorInfoBlock`, alleen door G6 gebruikt — CareSens Air blijft op de
generieke tekst, die daar niet hetzelfde "elke disconnect = Error"-euvel
heeft). Batterijspanning is uit de compacte tekst verdwenen (blijft wel
zichtbaar als aparte "Battery voltage"-rij op het volle statusscherm, waar
het als diagnostische data thuishoort). Prioriteit:
1. een sensor-start-code staat klaar/is net verstuurd, nog niet bevestigd →
   "Sending sensor start…"
2. een sessie-start is bevestigd én de aangenomen warmup-duur (2 uur, xDrip+'s
   eigen aanname — de G6 zelf rapporteert geen warmup-duur terug) is nog niet
   verstreken → "Sensor started · Xh Ym warmup remaining"
3. actief aan het verbinden/zoeken → "Connecting…"/"Searching for
   transmitter…" (kort, onschuldig, geen foutmelding)
4. fallback → "Last connected <tijd>", of "Not connected yet" als dat nog
   nooit gebeurd is.

**2. Nieuw bevestigd-sessie-start-tijdstip.** `AppSettings.
setDexcomG6SessionStartConfirmedAtMs()`/`dexcomG6SessionStartConfirmedAtMs`
(nieuwe DataStore-sleutel) — gezet door `DexcomG6Driver.kt`'s
`runControlSequence()` op precies het moment dat de transmitter een
sessie-start-antwoord met `ok=true` terugstuurt (hetzelfde moment waarop de
klaarstaande code alweer gewist wordt). Een nieuwe "start new sensor"-
aanvraag (`setDexcomG6PendingNewSensorCode()`) wist dit tijdstip meteen weer
— anders zou een oude warmup-aftelling nog even doortellen terwijl er al een
nieuwe sensor onderweg is. `dexcomG6PendingNewSensorCode` kreeg ook een
Flow-variant (naast de bestaande eenmalige lezing) zodat de UI live
"Sending sensor start…" kan tonen.

**3. Live doortikkende aftelling.** Zowel `StatusScreen.kt` (bestaande
`nowTickMs`, nu doorgegeven aan `CompactSensorSummary`) als
`DexcomG6StatusScreen.kt` (nieuwe, eigen 30s-ticker) herberekenen de
resterende warmup-tijd periodiek, ook zonder nieuwe sensordata.

Gewijzigd: `data/AppSettings.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`ui/DexcomG6StatusScreen.kt`, `ui/StatusScreen.kt`.

versionCode 70, versionName `0.6.1-g6-status-warmup`.

## Ronde 66 (09/08/2026) — false-positive "nieuwe sensor" elke ~5min gefixt + echt stop/start-mechanisme + transmitter-gerapporteerde sensorstatus/warmup

Twee aanleidingen tegelijk. Eén: een screenshot met een dichte cluster
wisselmarkers en een afgekapte/glitchy grafiek, met het vermoeden "hij denkt
bij elke update dat er een nieuwe sensor is gestart". Twee: "als je nu zegt
start new sensor stopt hij dan automatisch de lopende? [...] je geeft aan dat
hij een warmup van 2h heeft, voor de default dexcom g6 transmitter klopt dat
maar een anubis transmitter [...] klopt dat niet. Xdrip hield hier rekening
mee, dus misschien is het goed dat je het hele start/stop-mechanisme [...]
nog eens nakijkt".

**1. Root cause false-positive "nieuwe sensor".** `ConnectionWatchdog` roept
elke 6 minuten onvoorwaardelijk `startForegroundService()` aan, als
noodgreep tegen een vastgelopen service. `BleConnectionService.
onStartCommand()`'s `stillWorking`-check herkende alleen `Connected`/
`Connecting`/`Scanning` als "gezond" — NIET `Error`, terwijl de G6-driver
(bewust, per ontwerp) na elke leescyclus disconnect en in die tussentijd
precies in die `Error`-status hangt (zie ronde 65's bevinding over
`updateConnectionStatusAfterDisconnect()`). Resultaat: de watchdog brak de
kerngezonde driver bijna elke cyclus onnodig af en bouwde 'm opnieuw op, wat
`firstReadingThisSession` resette en zo bij elke herverbinding opnieuw
`readingStore.trimFrom()` (afgekapte grafiek) + `switchEventStore.record()`
(valse wisselmarker) triggerde — precies wat de screenshot liet zien.
Twee-laags gefixt: (a) `stillWorking` telt nu ook `connectionJob?.isActive`
mee als "nog gezond" (structurele-concurrency-inzicht: een `Flow.collect` op
een lopende `StateFlow` maakt de parent-job's `isActive` betrouwbaar `true`
zolang de driver leeft, ongeacht welke coarse `ConnectionState` er net
toevallig geldt) — lost de oorzaak zelf op; (b) `trimFrom()`/
`switchEventStore.record()` zitten nu ook achter een nieuwe persistente
guard (`SENSOR_SESSION_STARTED_FOR_DEVICE_ADDRESS`), zelfde patroon als
ronde 46's kalibratie-fix — vangt het opnieuw op als er ooit een andere weg
naar dezelfde herstart-race leidt.

**2. Stop-lopende-sessie-vóór-nieuwe-start, met waarschuwing.** Tot deze
ronde stopte de app de lopende sessie nooit — een nieuwe sensor-code werd
gewoon los verstuurd, wat de transmitter afwees met infoCode 0x02 "already
started" zonder de nieuwe code toe te passen (bevestigd via xDrip+'s eigen
`SessionStartRxMessage`-logica en officiële "Stop Sensor, wacht 5 min, Start
Sensor"-procedure). Nu: `DexcomG6NewSensorScreen.kt` leest de laatst bekende,
transmitter-gerapporteerde sensorstatus (zie punt 3) — staat die niet op
gestopt/verlopen/mislukt, dan toont de "Start sensor"-knop eerst een
bevestigingsdialoog ("Sensor already active? [...] Stop old sensor and start
new one" / "Cancel"). Na bevestiging zet `AppSettings.
setDexcomG6PendingStopBeforeStart(true)` een vlaggetje dat `DexcomG6Driver.
kt`'s `runControlSequence()` binnen dezelfde verbindcyclus eerst laat
verwerken (nieuw `buildSessionStop()`/`SessionStopRx`-paar, opcode 0x28/0x29)
vóórdat de nieuwe sessie-start met code verstuurd wordt. Geen apart "Stop
sensor"-knop nodig — dekt precies het enige scenario waarin stoppen zinvol
is. Bijvangst: `DexcomG6Protocol.parseSessionStart()`'s `ok`-berekening nam
`info==0x02` (= "already started") ten onrechte mee als succes; dat gaf
eerder een vals-positieve "Sensor started"-melding als een sessie-start-
poging eigenlijk gewoon door de transmitter afgewezen werd. Gecorrigeerd
(inclusief de ontbrekende `sessionStartTime != 0`-check, ook uit xDrip+'s
eigen logica).

**3. Echte, transmitter-gerapporteerde sensorstatus i.p.v. giswerk.** Elk
glucose-antwoord van de transmitter bevat al een statusbyte
(`GlucoseRx.stateRaw`) — tot deze ronde wel gelezen maar nooit
geïnterpreteerd. Nieuw `sensor/dexcomg6/DexcomG6CalibrationState.kt`: volledige
enum-port van xDrip+'s `CalibrationState`-tabel (34 waarden, incl.
"WarmingUp", diverse "SensorFailed"-varianten, "Stopped", "Expired") met
predicaten (`warmingUp()`, `sensorFailed()`, `sensorStarted()`,
`shortUserText()`). `DexcomG6Driver.kt`'s `handleGlucoseResult()` persisteert
dit nu bij elke meting (`AppSettings.setDexcomG6LastCalibrationState()`). Dit
is het ENIGE signaal dat hardware-onafhankelijk werkt — een lokaal
aangenomen tijdsduur kan dat per definitie niet.

**4. Echte, per-transmitter opgevraagde warmup-duur i.p.v. vaste 2h-aanname.**
De vaste `G6_WARMUP_DURATION_MS = 2h` uit ronde 65 klopt voor een standaard
G6-transmitter, maar niet voor een gemodificeerde transmitter zoals Anubis
(~50 min warmup, geen 10-dagen-stoptijd) — xDrip+ vraagt dit dan ook nooit
lokaal aan, maar vraagt het rechtstreeks aan de transmitter op via een
`VersionRequest2`-berichtenpaar (opcode 0x52, zelfde opcode heen én terug).
Geport: `DexcomG6Protocol.kt`'s `buildVersionRequest2()`/
`parseVersionRequest2()` (levert `warmupSeconds` + `typicalSensorDays`);
`DexcomG6Driver.kt`'s `runControlSequence()` stuurt deze aanvraag zodra het
resultaat nog onbekend is (of elke 8 uur opnieuw als de transmitter niet
antwoordde — bijv. oudere firmware die dit bericht niet kent), en persisteert
het resultaat (`AppSettings.dexcomG6WarmupSeconds`).

**5. UI-laag herschreven op basis van 3+4.** `dexcomG6StatusText()`
(`DexcomG6StatusScreen.kt`, gebruikt door zowel het volle G6-statusscherm als
`StatusScreen.kt`'s compacte samenvatting) gooit de vaste 2h-aanname er
volledig uit. Nieuwe prioriteit: pending sensor-start-code → "Sending sensor
start…"; transmitter meldt gestopt/verlopen/mislukt →
`DexcomG6CalibrationState.shortUserText()` (bijv. "Sensor failed — replace
sensor"); transmitter meldt "aan het opwarmen" → "Sensor started" + "· Xh Ym
warmup remaining" MET de echte, opgevraagde `warmupSeconds` zodra bekend,
anders zonder aftelling (nooit meer een gegokt getal); overige gevallen
ongewijzigd (Connecting/Searching/Last connected/Not connected yet).

Gewijzigd: `sensor/ble/BleConnectionService.kt`, `data/AppSettings.kt`,
`sensor/dexcomg6/DexcomG6Protocol.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`ui/DexcomG6NewSensorScreen.kt`, `ui/DexcomG6StatusScreen.kt`,
`ui/StatusScreen.kt`. Nieuw: `sensor/dexcomg6/DexcomG6CalibrationState.kt`.

versionCode 71, versionName `0.6.2-g6-protocol-refresh`.

## Ronde 67 (09/08/2026) — transmitter-type/sensor-days/warmup zichtbaar op het G6-statusscherm

Feedback na ronde 66: "De info over de transmitter type en warmup en sensor
days (in geval van anubis max 60) is nu nog nergens zichtbaar. Het is goed om
bij de transmitter info niet alleen het id neer te zetten maar ook het type
(original vs anubis) en dan ook de sensor days (10 vs 60) en warmup (50
minuten vs 2 uur) zodat je bij het bestuderen van de status ook gelijk dat
overzicht hebt."

Ronde 66 vroeg `warmupSeconds`/`typicalSensorDays` al bij de transmitter zelf
op (via VersionRequest2) en persisteerde ze, maar gebruikte ze alleen intern
voor de warmup-aftelling — nergens zichtbaar als los overzicht.

**Nieuwe `dexcomG6TransmitterCapabilityText()`** (`DexcomG6StatusScreen.kt`),
getoond direct onder "Transmitter ID: …" op het volle G6-statusscherm.
Belangrijke kanttekening (ook in de kdoc): de transmitter rapporteert zelf
GEEN merk-/modelnaam terug — dat veld bestaat niet in het protocol. "Type" is
dus een heuristiek op basis van de daadwerkelijk opgevraagde
`typicalSensorDays`: ≤15 dagen → "original G6" (stock meldt doorgaans 10),
>15 dagen → "modified transmitter (Anubis-style)" (Anubis meldt tot 60).
Voorbeeldregel: `Type: modified transmitter (Anubis-style) · Sensor life: 60
days · Warmup: 50m`. Toont niets zolang VersionRequest2 nog geen antwoord
opleverde (bijv. vlak na eerste koppeling) — geen gegokte waarde.

**AppSettings.kt**: `dexcomG6TypicalSensorDays: Flow<Int?>` toegevoegd —
`typicalSensorDays` werd al opgeslagen (`setDexcomG6WarmupCapability()`, sinds
ronde 66) maar had nog geen eigen Flow om in de UI te lezen (alleen
`dexcomG6WarmupSeconds` had die al).

Gewijzigd: `data/AppSettings.kt`, `ui/DexcomG6StatusScreen.kt`.

versionCode 72, versionName `0.6.3-g6-transmitter-capability`.

## Ronde 68 (09/08/2026) — CRITICAL: VersionRequest2's "short form"-antwoord (opcode 0x53) werd genegeerd, waardoor transmitter-capability nooit verscheen

Feedback na live-test op v72: "Heb ik nu wel de correcte versie ik zie
namelijk geen info over de transmitter" — de About-schermafbeelding bevestigde
wel degelijk v72/`0.6.3-g6-transmitter-capability`, maar de nieuwe regel
onder "Transmitter ID" bleef leeg, ook na meerdere geslaagde herverbindingen
(battery/temperatuur/last-connected werkten intussen prima, dus de BLE-
verbinding zelf was gezond).

**Root cause.** Terug naar xDrip+'s eigen `VersionRequest2RxMessage.java`
(nogmaals rechtstreeks nagekeken): een 3-byte versie=2-aanvraag (opcode
0x52) kan door de transmitter op TWEE verschillende manieren beantwoord
worden — welke vorm gekozen wordt is blijkbaar hardware-/firmware-
afhankelijk, niet iets waar de aanvrager om vraagt:
- "long form" — antwoordt met opcode **0x52** (hetzelfde als de aanvraag),
  15 bytes: status + lifeSeconds + warmupSeconds + version1/2 +
  typicalSensorDaysRaw. Dit was tot ronde 67 de ENIGE vorm die de parser
  herkende (`packet[0] != 0x52` faalde anders de check).
- "short form" — antwoordt met een ANDER opcode, **0x53**, 9 bytes: status +
  typicalSensorDays (direct als 1 byte, geen lifeSeconds-omrekening nodig) +
  featureBits + warmupSeconds.

De transmitter in de live-test antwoordde blijkbaar met de short-form 0x53 —
een geldig, compleet antwoord, dat de parser tot ronde 67 gewoon weggooide
omdat het opcode niet matchte. Gevolg: `warmupSeconds`/`typicalSensorDays`
bleven permanent "nog nooit opgevraagd", en omdat de driver het
laatste-poging-tijdstip al VOOR het versturen vastlegt (om bij een echte
timeout niet elke verbindcyclus opnieuw te proberen), werd de eerstvolgende
poging ook meteen voor 8 uur geblokkeerd — precies het waargenomen "voor
altijd leeg" symptoom.

**Fix.** `DexcomG6Protocol.parseVersionRequest2()` herkent nu beide vormen
(op basis van het eerste byte + minimale lengte), en `DexcomG6Driver.kt`'s
`handleControlNotification()` dispatcht nu zowel 0x52 als 0x53 naar de
parser (was uitsluitend 0x52).

**Bijvangst — terugval-tijd verkort (8u → 15min).** Omdat de driver het
laatste-poging-tijdstip vastlegt VOOR het versturen (om een echt niet-
beantwoordende transmitter niet elke ~5 minuten lastig te vallen), zou de
mislukte poging uit de v72-test deze fix zelf nog tot 8 uur later laten
zien op het toestel van de gebruiker (het opgeslagen tijdstip overleeft een
app-update). 8 uur was oorspronkelijk bedoeld voor een transmitter die dit
verzoek ECHT niet ondersteunt — nu bekend is dat een deel van die
"timeouts" in werkelijkheid deze parserbug was, is 15 minuten een betere
balans: nog steeds geen queryspam per verbindcyclus, maar een bugfix zoals
deze wordt binnen een paar reconnects zichtbaar i.p.v. pas de volgende dag.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`.

versionCode 74, versionName `0.6.4-g6-version2-shortform-fix`.

## Ronde 69 (09/08/2026) — CRITICAL: BG-waarde bleef op 0,3 mmol/L hangen (foutcode i.p.v. echte meting), warmup-"0m"-fix, en volledige sensor/transmitter-infotabellen

Feedback na live-test op v74: "de BG wordt al geruime tijd op 0 of 0,3
weergegeven [...] gaat daar niet iets mis" + "de warmup wordt op 0m
weergegeven, Xdrip maakte standaard altijd melding van 50m" + "de weergave
is nu niet mooi [...] alle info netjes in een tabel [...] eerst de sensor
info [...] en dan daaronder [...] de transmitter info [...] niet op 1 regel
maar netjes in tabelvorm".

**1. CRITICAL: valse BG-waarden (0,3 mmol/L) — root cause gevonden in
xDrip+'s eigen bron.** `DexcomG6Driver.kt`'s `handleGlucoseResult()` sloeg
tot deze ronde `rx.glucoseMgdl` ALTIJD op/broadcastte het altijd, ongeacht
het kalibratiebyte. Rechtstreeks nagekeken in xDrip+'s
`BaseGlucoseRxMessage.usable()` en `Ob1G5StateMachine.
processGlucoseRxMessage()`: xDrip+ maakt UITSLUITEND een BgReading aan
wanneer de kalibratiestatus `Ok`, `NeedsCalibration` of
`InsufficientCalibration` is — in ELKE andere staat (WarmingUp,
NeedsFirstCalibration, een van de Failed/Stopped-varianten, ...) is het
meegestuurde glucosegetal GEEN echte meting. Dexcom's protocol gebruikt
namelijk zeer lage mg/dL-waarden (1, 2, 3, 5, 6, 9, 10, 12, 13) als
gereserveerde interne statuscodes — 0,3 mmol/L × 18,0182 ≈ 5,4 mg/dL, precies
in dat bereik. Onze app toonde die statuscode dus letterlijk als BG-waarde.
Fix: nieuwe `DexcomG6CalibrationState.usableGlucose()`/
`insufficientCalibration()` (exacte mirror van xDrip+'s eigen predicaten) +
een nieuwe gate in `handleGlucoseResult()` — buiten een bruikbare staat wordt
er simpelweg GEEN reading aangemaakt (net als xDrip+, dat bewust een gat in
de grafiek laat i.p.v. een foutief getal te tonen).

**2. Warmup "0m"-fix.** Root cause zat in dezelfde 0x53-"short form"
VersionRequest2-respons als ronde 68's fix, maar net iets subtieler: xDrip+'s
eigen `VersionRequest2RxMessage` markeert deze vorm alleen als betrouwbaar
voor `warmupSeconds` bij een EXACTE lengte van 9 bytes (het eigen commentaar
in de bron: "only valid in type 2", met "type2" gedefinieerd als
`packet.length == 9`, geen ondergrens). Bij een afwijkende lengte voor
hetzelfde opcode kan diezelfde byte-positie iets anders betekenen — precies
zoiets kan het waargenomen "0m" verklaard hebben. Fix: `VersionRequest2Rx.
warmupSeconds` is nu `Int?` — alleen gevuld bij een EXACTE 9-byte respons,
anders `null` ("nog niet betrouwbaar bekend", met een automatische
herhaalpoging via de bestaande 15-min-cyclus uit ronde 68, i.p.v. een
mogelijk foutief getal te tonen). `typicalSensorDays` blijft, zoals in
xDrip+'s eigen bron, wél altijd vertrouwd.

**3. Sensor-/transmitter-info als nette tabellen.** Volledige herschrijving
van `DexcomG6StatusScreen.kt`'s infoweergave: de gedeelde `SensorInfoBlock`
(ook door CareSens Air gebruikt) + twee losse prose-regels erna zijn
vervangen door twee eigen, uitgelijnde tabellen (nieuwe `DexcomG6InfoTable`-
component, alleen in dit bestand — CareSens Air ongewijzigd):
- **Sensor**: Started (bevestigd sessie-startmoment), End (est.) (Started +
  de echte, opgevraagde sensor-levensduur), Code (de laatst bevestigde
  sensor-code — nieuw persistent veld `AppSettings.
  dexcomG6LastConfirmedSensorCode`, blijft staan nadat de pending-code al
  gewist is).
- **Transmitter**: ID, Type (nu ÉÉN woord — "Anubis" of "Original", geen
  volzin meer, zelfde 15-dagen-heuristiek als ronde 67), Sensor life,
  Warmup, Last connected, Battery voltage, Temperature.
De dynamische statusregel (bv. "Sensor started · 2h 0m warmup remaining")
staat bewust los boven de tabellen — past niet netjes in een vaste
kolombreedte.

Gewijzigd: `sensor/dexcomg6/DexcomG6CalibrationState.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`, `sensor/dexcomg6/DexcomG6Protocol.kt`,
`data/AppSettings.kt`, `ui/DexcomG6StatusScreen.kt`.

versionCode 75, versionName `0.6.5-g6-glucose-usability-gate`.

## Ronde 70 (09/08/2026) — warmup "0m" bleef hangen na ronde 69's fix (stale persisted waarde), nieuwe "Stop sensor"-knop, en analyse van een gemelde connectiviteitsstilstand

Feedback na live-test op v75: "de warmup blijft overigens nog steeds op 0m
staan" + "misschien is het handig om toch een stop sensor knop te maken die
een stop signaal zend [...] de sensor stoppen, de transmitter verwijderen 5
minuten wachten en weer opstarten" + "rond 18:03 heb ik hem geïnstalleerd
met de nieuwe versie en sindsdien doet hij niks meer".

**1. Warmup "0m" — de ECHTE root cause.** Ronde 69's fix (alleen een EXACTE
9-byte 0x53-respons vertrouwen) was op zich correct, maar loste het
zichtbare probleem niet op: vóór ronde 69 kon een NIET-exact-9-byte
0x53-antwoord al een onbetrouwbare `warmupSeconds = 0` hebben weggeschreven
naar DataStore — en DataStore overleeft een app-update gewoon. De
retry-gate in `runControlSequence()` checkte tot deze ronde alleen
`== null`, dus die stale `0` (géén `null`) blokkeerde permanent elke
nieuwe opvraagpoging, met terugwerkende kracht ook na het installeren van
ronde 69's fix. Een échte warmup-duur is nooit 0 seconden (zelfs Anubis'
~50 minuten is nog honderden seconden) — de retry-gate checkt nu
`(warmupSeconds ?: 0) <= 0` i.p.v. alleen `== null`, en de UI's
`warmupText`/`warmupRemainingMs`-berekeningen (`DexcomG6StatusScreen.kt`)
kregen defensief dezelfde `> 0`-guard, zodat een stale 0 ook daar nooit
meer als "0m" getoond kan worden.

**2. Nieuwe "Stop sensor"-knop.** Losstaande, expliciete actie (met
bevestigingsdialoog, want destructief) op `DexcomG6StatusScreen.kt`, naast
de bestaande "Switch transmitter"/"Start new sensor". Stuurt hetzelfde
`SessionStop`-protocolbericht (opcode 0x28/0x29) als de bestaande
"stop-before-start"-combo die al binnen "Start new sensor" zit, maar hier
ALLEEN de stop — geen nieuwe sessie erna. Bedoeld voor precies het door de
gebruiker beschreven scenario: sensor stoppen, transmitter fysiek
verwijderen, ~5 minuten wachten, dan pas via "Start new sensor" met de code
opnieuw starten — met de zekerheid dat de stop daadwerkelijk verstuurd is
i.p.v. te gokken. Nieuw get-and-clear-vlaggetje
`AppSettings.DEXCOM_G6_PENDING_STOP_SENSOR_ONLY` (zelfde patroon als het
bestaande `DEXCOM_G6_PENDING_STOP_BEFORE_START`), nieuw handling-blok
bovenaan `DexcomG6Driver.kt`'s `runControlSequence()`, en forceert net als
"Start new sensor" een verse verbindpoging (stop+start de BLE-service)
i.p.v. tot 5 minuten op de voorspellende herverbind-cooldown te wachten.

**3. Analyse van de gemelde stilstand sinds 18:03.** De meegestuurde
logcat toont één volledig geslaagde BLE-cyclus om 18:03:13-14 (glucose=39
mg/dL, seq=39, correct doorgelaten door ronde 69's nieuwe usable-gate,
staat=Ok, succesvol naar AAPS gebroadcast, nette disconnect) — consistent
met een verse app-herstart na het installeren van v75. Daarna is in de
aangeleverde log-uitsnede nog een scanpoging zichtbaar om 18:07:54
(consistent met de normale ~5-minuten-herverbindcyclus), maar geen verdere
DexcomG6-regels meer tot 18:14:44. Geen van de code-wijzigingen uit ronde
69 (kalibratiestatus-predicaten, de glucose-gate, protocol-parsing,
AppSettings-toevoegingen, alleen-UI-wijzigingen) raakt de
BLE-herverbind-/scanplanning aan (`ConnectionWatchdog.kt`,
`BleConnectionService.kt` zijn in rondes 68-70 niet aangeraakt) — er is dus
geen aanwijzing dat dit een regressie is die door deze rondes veroorzaakt
is, al kan een stilstand op basis van een afgekapte log-uitsnede niet met
zekerheid uitgesloten worden. Geen losse codewijziging voor dit punt in
deze ronde; mocht het patroon terugkomen, dan is Instellingen >
Diagnostic File Logging (ronde 35) de beste bron voor een vollediger beeld
van precies dát moment.

Gewijzigd: `data/AppSettings.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`ui/DexcomG6StatusScreen.kt`.

versionCode 76, versionName `0.6.6-g6-stop-sensor-warmup-retry-fix`.

## Ronde 71 (09/08/2026) — CRITICAL: root cause van blijvend vastgelopen "Sending sensor start" gevonden en gefixt (retry-logica vergat de stop opnieuw te proberen)

Feedback na live-test: "Ik kan fclglucolink nergens aanzetten" (batterij-
instellingen — bleken bij nader onderzoek al correct: achtergrondactiviteit
toegestaan, batterijverbruik niet geoptimaliseerd, app stond niet in een
sluimerlijst) + "wat betreft het starten van de sensor staat hij nu al
geruime tijd op 'Sending sensor start' ruim 10 minuten [...] dan moet hij
uiteraard wel weten hoe lang de warmuptijd is die staat [...] nog steeds op
0m" — met een screenshot dat de status-rij in rood/foutkleur toonde.

**Root cause.** `runControlSequence()`'s "nieuwe sensor"-combo-flow
(stop-before-start) consumeert het `DEXCOM_G6_PENDING_STOP_BEFORE_START`-
vlaggetje maar ÉÉN keer (get-and-clear, met opzet — zie ronde 66). Als die
EERSTE stop+start-poging alsnog mislukt (transmitter meldt "already
started", infoCode 0x02 — bijvoorbeeld omdat de stop op BLE-niveau al
bevestigd was maar de transmitter's eigen interne statusmachine 'm nog niet
volledig verwerkt had), bleef het vlaggetje sindsdien UIT: elke volgende
verbindpoging probeerde daarna alleen nog een KALE `SessionStart`, zonder
ooit weer eerst te stoppen. Als de transmitter dan structureel "already
started" bleef melden, faalde dat voor altijd — precies de 10+ minuten
onveranderde "Sending sensor start…" die gerapporteerd werd. Dit was dus
geen BLE-/achtergrondprobleem (de batterij-instellingen bleken terecht al
in orde), maar een zuivere logicafout in de hertry-strategie.

**Fix.** Bij ELKE mislukte `SessionStart` wordt het stop-before-start-
vlaggetje nu opnieuw gezet (`settings.setDexcomG6PendingStopBeforeStart(true)`),
zodat de VOLGENDE poging automatisch weer eerst stopt i.p.v. blind te
blijven herhalen. Erbij: een bewuste 1500ms-pauze tussen de stop- en de
start-write binnen dezelfde combo (marge voor de transmitter om de stop
intern te verwerken vóór de start-aanvraag binnenkomt) en een nieuwe,
persistente faalteller (`DEXCOM_G6_SESSION_START_FAIL_COUNT`, gereset bij
succes of een verse code-invoer) — na 2+ mislukkingen toont de status-rij
nu "Sensor start rejected N× (transmitter may still see the old sensor as
active) — try 'Stop sensor', wait a bit, then retry" i.p.v. een eeuwige,
niets-zeggende "Sending sensor start…".

**Diagnostisch (voor de aanhoudende warmup-"0m"/"—").** Los van
bovenstaande: bij deze specifieke Anubis-transmitter komt `typicalSensorDays`
via `VersionRequest2` steeds wél binnen, maar `warmupSeconds` blijft
onbekend — ook na meerdere hertries. Beide velden komen uit HETZELFDE
0x52/0x53-antwoord, dus dit patroon wijst erop dat dit specifieke antwoord
structureel niet de exacte 9-byte lengte heeft die `parseVersionRequest2()`
als voorwaarde stelt voor een betrouwbare `warmupSeconds` (zie die functie's
kdoc). Om dit definitief vast te stellen i.p.v. te blijven gokken: elk
0x52/0x53-antwoord wordt nu vóór het parsen als ruwe lengte + bytes gelogd
(zie Instellingen > Diagnostic File Logging) — de eerstvolgende log geeft
het concrete bewijs voor een gerichte fix in een volgende ronde.

Gewijzigd: `data/AppSettings.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`ui/DexcomG6StatusScreen.kt`, `ui/StatusScreen.kt`.

versionCode 77, versionName `0.6.7-g6-session-start-retry-fix`.

## Ronde 72 (09/08/2026) — "Stop sensor" toonde meteen weer "Sending sensor start" (klaarstaande code overheerste de status)

Feedback na live-test op v77: "bij drukken op 'stop sensor' geeft hij direct
in beeld 'sending sensor start'. Als ik dan na een minuut op start sensor
druk geeft hij aan dat hij al is gestart en bij stoppen geeft hij gelijk
weer 'sending sensor start' [...] na 5 minuten wordt die tekst wel van wit
naar rood [...] ik heb ondertussen echter ook een keer de transmitter
ontkoppeld en weer gekoppeld en ook toen kwam hij gelijk weer met 'sending
sensor start'".

**Root cause.** `dexcomG6StatusText()` toont "Sending sensor start…" met de
hoogste prioriteit zodra er een klaarstaande "nieuwe sensor"-code
(`dexcomG6PendingNewSensorCode`) bestaat — volledig los van wat "Stop
sensor" zelf doet. Er stond hier nog een code klaar van een eerdere,
mislukte poging (zie ronde 71) — dus de status bleef die tekst tonen, ONGEACHT
of de stop zelf lukte, en de app bleef feitelijk gewoon de OUDE start-
poging herhalen bij elke volgende verbinding. Dat verklaart ook waarom
fysiek loskoppelen van de transmitter niets hielp: een klaarstaande code
staat in de app zelf (DataStore), niet in de transmitter — loskoppelen
wist 'm niet.

**Fix.** "Stop sensor" is nu een echte schone-lei-actie: wist ELKE
klaarstaande start-poging (de code zelf, het stop-before-start-vlaggetje,
de faalteller uit ronde 71) VOORDAT de stop naar de transmitter verstuurd
wordt. Na het gebruiken van deze knop toont de status daarna weer de
daadwerkelijke, door de transmitter gerapporteerde toestand, i.p.v. voor
altijd "Sending sensor start…" te blijven tonen voor een poging die er
allang niet meer toe doet.

**Voor de gebruiker: het advies blijft** — gebruik "Stop sensor", en geef
de transmitter daarna ECHT een paar minuten (idealiter de volle 5 minuten
die xDrip+ ook aanhoudt) voordat je "Start new sensor" met de code
gebruikt. De automatische 1500ms-pauze uit ronde 71 is een marge binnen
ÉÉN verbindcyclus, geen vervanging voor die langere, daadwerkelijke
wachttijd — als de transmitter een sessie structureel als actief blijft
zien, is dat mogelijk gewoon een echte, door Dexcom's eigen firmware
opgelegde afkoelperiode, geen appbug.

Gewijzigd: `ui/DexcomG6StatusScreen.kt`.

versionCode 78, versionName `0.6.8-g6-stop-sensor-clean-slate`.

## Ronde 73 (09/08/2026) — CRITICAL: de échte root cause van de eindeloze "sensor start mislukt"-lus, gevonden via de Diagnostic File Logging-export

Feedback: "Hij leek goed te gaan. Als ik dan vervolgens op stop sensor klik
komt er... sensor started - warming up en weer 5 minuten later komt er
sensor stopped. Ik heb dit echter al eerder gedaan als ik dan weer eentje
start begint de cycle opnieuw... Wat ik nog wel even wil weten is of hij de
starttijd wel binnenhaalt" — met de eerste keer een écht bruikbare export
van Instellingen > Diagnostic File Logging meegestuurd (in plaats van ruwe
Android-logcat) — precies wat er in ronde 71 al voor gevraagd werd, en het
maakte in één keer de echte oorzaak zichtbaar.

**Wat het log toonde.** Elke `new-sensor session start result=` regel gaf
exact hetzelfde beeld: `SessionStartRx(ok=false, infoCode=5, ...,
sessionStartTime=0, ...)`. `infoCode=5` zit keurig in xDrip+'s eigen
whitelist voor een geslaagde start (`0x01`/`0x05`/`0x06`, zie
`parseSessionStart()`'s kdoc sinds ronde 66) — maar onze `ok`-berekening
eiste DAARBOVENOP ook nog `sessionStartTime != 0`, en die bleef bij deze
Anubis-transmitter stelselmatig op 0 staan, ook bij een echte, geslaagde
start. Het bewijs daarvoor: de eerstvolgende glucose-poll in DEZELFDE
verbindcyclus liet keer op keer `state=WarmingUp` zien — via het volledig
onafhankelijke kalibratiebyte (zie DexcomG6CalibrationState.kt) — het
onmiskenbare signaal dat de sessie wél degelijk gestart was.

**Waarom dit zo schadelijk was.** Met `ok` altijd `false` werd de
klaarstaande code nooit gewist, en — sinds ronde 71's eigen fix — werd het
stop-before-start-vlaggetje bij elke "mislukking" juist opnieuw gezet. Het
gevolg: een oneindige, zelf-veroorzaakte lus waarbij de app elke ~5 minuten
een prima opwarmende sensor gewoon weer STOPTE en opnieuw startte —
precies de "cycle begint opnieuw"-klacht, en de reden dat Warmup nooit
verder kwam dan "—": de sensor kreeg letterlijk nooit de kans om lang
genoeg door te warmen, omdat de app 'm zelf steeds zelf onderbrak.

**Fix.** `sessionStartTime != 0` is geen harde eis meer voor `ok` —
`status == 0x00 && info in {0x01, 0x05, 0x06}` (xDrip+'s eigen
info-whitelist) is voldoende. `sessionStartTime` blijft gewoon bewaard in
de data class voor eventuele toekomstige diagnostiek. Zelfde categorie
firmware-eigenaardigheid als de VersionRequest2-velden
(warmupSeconds/typicalSensorDays) die deze transmitter ook niet altijd
volledig invult.

Let op: bij de eerste verbinding na deze update kan er nog één laatste,
onnodige stop-en-herstart plaatsvinden (het klaarstaande stop-before-
start-vlaggetje van vóór deze fix moet nog één keer "opgebruikt" worden) —
daarna zou een start in één keer moeten lukken en zou Warmup ook eindelijk
een echt getal moeten tonen zodra de sessie lang genoeg intact blijft.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt`.

versionCode 79, versionName `0.6.9-g6-session-start-ok-fix-critical`.

## Ronde 74 (09/08/2026) — Fallback-opwarmtijd (30 min Anubis / 60 min Original) + metingen onderdrukken tijdens dat venster

Feedback, n.a.v. een live-screenshot met een fysiek onwaarschijnlijke sprong
van ~2 naar 16 mmol/L amper 8 minuten na een bevestigde sensorstart: "Ik
bedoel de warmup tijd die de transmitter hanteert alvorens hij waarden gaat
doorgeven zichtbaar in het transmitter overzicht. Als die niet uit de
transmitter komt dan moet hij bij een anubis gewoon 50 minuten pakken en
anders 2 uur... Overigens geeft de app al gelijk data. Ik weet dus niet of
die 50 minuten en 2 uur een safety is of dat het echt fysiek zolang duurt...
omdat ik denk dat het een safety is wil ik hem voor anubis op 30 minuten
hebben en voor een originele op 1 uur mocht er geen waarde uit de
transmitter zelf komen. Dat betekent dus dat de waarden pas getoond mogen
worden resp. 30 en 60 minuten nadat de sensor cfm de info in het overzicht
is gestart."

**Achtergrond — dit is een bewuste, geïnformeerde gebruikerskeuze, geen
vaste fysieke waarheid.** xDrip+'s eigen documentatie noemt voor stock G6
een standaard opwarmtijd van 2 uur en voor Anubis-achtige mods vaak ~50
minuten. De gebruiker vermoedt dat dit een conservatieve veiligheidsmarge
is i.p.v. een harde technische ondergrens (zijn transmitter levert al
vrijwel meteen data) en kiest daarom bewust voor kortere, eigen waarden:
30 minuten voor Anubis, 60 minuten voor Original. Deze fallback wordt
UITSLUITEND gebruikt wanneer de transmitter zelf géén bruikbare
`warmupSeconds` teruggeeft via VersionRequest2 (zoals bij deze specifieke
Anubis-transmitter, zie ronde 71) — een ECHTE, door de transmitter
opgegeven waarde heeft altijd voorrang.

**Wat er nu gebeurt.**

1. Nieuwe, centrale `DexcomG6TransmitterType`-enum +
   `dexcomG6FallbackWarmupSeconds()`-functie in
   `sensor/dexcomg6/DexcomG6CalibrationState.kt` — dezelfde 15-dagen-
   Anubis/Original-heuristiek als ronde 67, nu op één plek i.p.v. los
   gedupliceerd in de UI, plus de fallback-opwarmtijd (30/60 min).
2. `DexcomG6Driver.kt`'s `handleGlucoseResult()` kreeg een TWEEDE,
   onafhankelijke onderdrukkingsgate bovenop de bestaande kalibratiebyte-
   gate (ronde 69): zelfs als de transmitter een kalibratiestaat als "Ok"/
   "NeedsCalibration" rapporteert, wordt een meting alsnog onderdrukt
   zolang de effectieve opwarmtijd (echte `warmupSeconds`, of anders de
   fallback) nog niet verstreken is sinds de bevestigde sessionStart. Een
   apart gemarkeerde diagnostic-logregel ("SUPPRESSED — within fallback
   warmup window...") maakt dit onderscheidbaar van de gewone
   kalibratiebyte-onderdrukking.
3. `dexcomG6StatusText()` (`DexcomG6StatusScreen.kt`) toont nu een
   "Xh Ym warmup remaining"-aftelling zolang het effectieve
   (echte-of-fallback) opwarmvenster nog loopt — dit is losgekoppeld van
   het kalibratiebyte zelf (dat bij deze transmitter soms al vroeg "Ok"
   rapporteert), zodat de gebruiker altijd kan zien hoe lang hij nog moet
   wachten voor de app daadwerkelijk data toont. Een schatting krijgt de
   suffix " (est.)" zodat die nooit met een echte transmitter-waarde te
   verwarren is — zelfde patroon als de bestaande "Warmup"-rij in de
   Transmitter-infotabel, die nu ook op de fallback terugvalt i.p.v. altijd
   "—" te tonen zolang de echte waarde onbekend blijft.
4. `StatusScreen.kt`'s compacte samenvattingskaartje geeft nu ook
   `typicalSensorDays` door aan `dexcomG6StatusText()`, zodat dat kaartje en
   het volle statusscherm exact dezelfde aftelling tonen.
5. `DexcomG6StatusScreen.kt`'s eigen "Type"-berekening (Anubis/Original)
   hergebruikt nu de nieuwe centrale `DexcomG6TransmitterType`-enum i.p.v.
   een eigen gedupliceerde `when`-blok.

Gewijzigd: `sensor/dexcomg6/DexcomG6CalibrationState.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`, `ui/DexcomG6StatusScreen.kt`,
`ui/StatusScreen.kt`, `data/AppSettings.kt` (2 nieuwe "once"-getters).

versionCode 80, versionName `0.7.0-g6-fallback-warmup-gate`.

## Ronde 75 (09/08/2026) — CareSens Air-kaartje toont "Last connected" i.p.v. serienummer, sensor-looptijd op beide kaartjes, BG-grafiek met 3 nieuwe kleurbanden boven 10 mmol/L

Feedback: "Bij de caresens staat er nu op het hoofdscherm connected en dan
het serienr dat moet worden de lastconnected info net als bij de dexcom en
dan wil ik bij beide (en ook de toekomstige) sensoren daar onder ook de
looptijd van de sensor. Dus de huidige tijd min de starttijd uitgedrukt in
dagen en uren. In de Bg grafiek wil ik de kleuren boven de 10 veranderen van
10 tot 12,5 moet hij geel worden van 12,5 tot 15 oranje en boven de 15 rood
onder de 4 kan zo blijven."

**1) CareSens Air-kaartje op het hoofdscherm.** `careSensAirCompactSummaryText()`
(`CareSensAirStatusScreen.kt`) toonde `"Connected · #<serienr>"` zolang
`ConnectionState.Connected` actief was — dat wisselde wat CareSens Air
periodiek verbindt/ontkoppelt (BLE-polling, net als de G6) en zei bovendien
iets anders dan het Dexcom-kaartje ernaast. Nu dezelfde prioriteit als
`dexcomG6StatusText()`: Scanning/Connecting eerst, anders altijd
"Last connected dd-MM HH:mm" — het serienummer verdwijnt van dit compacte
kaartje (blijft gewoon staan op het volle CareSens Air-statusscherm).

**2) Sensor-looptijd op beide kaartjes.** Nieuwe, herbruikbare
`sensorRuntimeText()`-helper in `StatusScreen.kt`: huidige tijd min de
bevestigde sensor-startdatum, getoond als "Running Xd Yh" — een derde regel
onder de bestaande statusregel op `CompactSensorSummary()` (het kaartje
boven de BG-grafiek), voor zowel Dexcom G6 (start = `dexcomG6SessionStart
ConfirmedAtMs`) als CareSens Air (start = `careSensAirSensorStartedAtMs`).
Bewust op één centrale plek gebouwd i.p.v. per sensortype gedupliceerd, zodat
toekomstige types (Accu-Chek SmartGuide/G7) 'm zo kunnen hergebruiken.

**3) BG-grafiek kleurbanden.** `GlucoseChart.kt`'s per-punt lijnkleur kende tot
nu toe maar drie banden (rood <4, groen 4-10, één vast amber >10). Nu vijf:
rood (<4, ongewijzigd), groen (4-10, ongewijzigd), geel (10-12,5), oranje
(12,5-15), rood (>15 — dezelfde kleur als de bestaande onder-bereik-rode
tint, aangezien zowel een te lage als een zeer hoge waarde gevaarlijk is).

Gewijzigd: `ui/CareSensAirStatusScreen.kt`, `ui/StatusScreen.kt`,
`ui/GlucoseChart.kt`.

versionCode 81, versionName `0.7.1-ui-runtime-lastconnected-chart-bands`.

## Ronde 76 (09/08/2026) — CRITICAL: G6 kon voor onbepaalde tijd (uren) permanent vastlopen na scherm-uit — scan-rearm-vangnet toegevoegd, mirror van CareSens Air's al bewezen aanpak

Feedback: "de samsung telefoon blijft hangen zodra het scherm zwart wordt en
hij gaat ook niet meer lopen" — met een meegestuurde diagnostic-log
(`fclglucolink_2026-08-09.txt`) die liet zien dat een volkomen gezonde G6-
verbindcyclus om 18:53 (nette connect, meting, schone disconnect) gevolgd
werd door TOTALE, urenlange stilte — geen enkele volgende regel, ook geen
mislukte poging.

**Root cause.** `DexcomG6Driver.kt`'s `startConnectScan()` startte één kale
`scanner.startScan(...)` en wachtte daarna simpelweg op `onScanResult()`/
`onScanFailed()` — geen van beide hoeft ooit te vuren als Android een
langlopende achtergrondscan stilzwijgend onderdrukt (bekend OS-gedrag bij
langdurig scherm-uit). Zonder een eigen vangnet bleef de onderliggende
coroutine dan voor altijd `isActive`, en omdat `BleConnectionService.
onStartCommand()`'s gedeelde `stillWorking`-check een actieve job als
"gezond, niet opnieuw starten" beschouwt, kon zelfs de AlarmManager-wekker
(`ConnectionWatchdog.kt`, elke 6 minuten) hier nooit doorheen breken — een
tijdelijke OS-scanonderdrukking werd zo een PERMANENTE stilstand.
`CareSensAirDriver.kt` had dit exacte probleem al sinds ronde 26/30 opgelost
met een eigen `scheduleRearm()`-vangnet; `DexcomG6Driver.kt` had dat nooit
gekregen — vermoedelijk een omissie toen dat bestand geschreven werd, net
als de vergelijkbare ronde-60-omissie (scan-race-guard).

**Fix — bewust ALLEEN in `DexcomG6Driver.kt`.** Exact hetzelfde, al
bewezen `scheduleRearm()`-patroon uit `CareSensAirDriver.kt` overgenomen
(letterlijk dezelfde 390s-`SCAN_REARM_INTERVAL_MS`, geen eigen gok): als de
transmitter na dat interval nog niet gevonden is, wordt de scan expliciet
gestopt en via `scheduleScanAttempt()` vers herstart — dat forceert een
nieuwe `scanner.startScan()`-aanroep, wat Android's eventuele stille
onderdrukking van de vorige, langlopende scansessie doorbreekt. De
gedeelde `stillWorking`-check in `BleConnectionService.kt` is BEWUST
ongewijzigd gelaten (op uitdrukkelijke controlevraag van de gebruiker: "raakt
deze fix ook de caresens code?") — CareSens Air had dit vangnet zelf al
nooit nodig gehad, dus dat bestand blijft volledig buiten schot.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt`.

versionCode 82, versionName `0.7.2-g6-scan-rearm-critical`.

## Ronde 77 (10/08/2026) — resterend BG-gat na de Ronde 76-fix opgelost: ontbrekende ACCESS_BACKGROUND_LOCATION-permissie op Android ≤11

Vervolg op Ronde 76: de gebruiker bevestigde met een nieuwe diagnostic-log
(`fclglucolink_2026-08-10.txt`, Samsung A40/Android 11) dat de scan-rearm
zelf perfect werkte (exact elke 390s: 08:17:55 → 08:24:25 → 08:30:55 →
08:37:25), maar dat ALLE scanpogingen tijdens ~20-25 minuten scherm-uit nul
resultaten opleverden — pas vlak nadat het scherm weer aanging kwam er weer
data binnen. Dus geen permanente hang meer (dat was Ronde 76), maar een
apart, resterend probleem: Android onderdrukt kennelijk de AFLEVERING van
scanresultaten tijdens langdurig scherm-uit, los van of de scan-coroutine
zelf gezond is.

**Onderzoek.** Samsung's OneUI "sluimerende apps"-mechanisme uitgesloten
(screenshots bevestigden: FCLGlucoLink staat al op "Niet geoptimaliseerd" /
achtergrondactiviteit toegestaan, en komt daarom niet eens voor in de
sluimerlijst-kiezer). Vervolgens viel op — gebruiker zelf, na eigen
onderzoek: "bij de locatie permissie aaps inderdaad op altijd toestaan
ingesteld moet worden om uitval te vermijden. Bij FCLGlucoLink is die optie
niet selecteerbaar" — dat FCLGlucoLink's locatiemachtiging-scherm geen
"Altijd toestaan"-optie toont, in tegenstelling tot AAPS op hetzelfde
toestel. Manifest-onderzoek bevestigde de oorzaak: `ACCESS_FINE_LOCATION`
was al gedeclareerd (`maxSdkVersion="30"`), maar `ACCESS_BACKGROUND_LOCATION`
nergens. Op Android ≤11 loopt BLE-scannen nog via het oude locatierechten-
model; zonder de achtergrond-variant van die permissie biedt Android
domweg geen "Altijd toestaan"-keuze aan, en kan achtergrond-scanaflevering
onbetrouwbaar worden zodra het scherm lang genoeg uit staat.

**Fix.**
1. `AndroidManifest.xml`: `ACCESS_BACKGROUND_LOCATION` toegevoegd, met
   dezelfde `maxSdkVersion="30"`-scope als `ACCESS_FINE_LOCATION` — op
   API≥31 dekt `BLUETOOTH_SCAN`/`neverForLocation` dit al af.
2. Declareren alleen is niet genoeg: sinds Android 10 kan deze permissie
   niet via een gewone in-app-prompt gevraagd worden, de gebruiker moet 'm
   zelf via de systeem-appinfo-pagina aanzetten. Op uitdrukkelijk verzoek
   ("die link naar de knop moet dan wel in de manual komen en niet in de
   andere interfaces want hij wordt maar 1 malig gebruikt") is dit BEWUST
   niet als knop op StatusScreen/SettingsScreen gezet, maar alleen in de
   handleiding: `ManualScreen.kt`'s `BEST_RESULTS`-onderwerp kreeg een
   nieuwe sectie ("Android 11 or older: background location") plus een
   knop (`LocationPermissionLinkRow`) die `Settings.
   ACTION_APPLICATION_DETAILS_SETTINGS` opent — vanwaar de gebruiker zelf
   naar Machtigingen > Locatie > "Altijd toestaan" navigeert.

Gewijzigd: `AndroidManifest.xml`, `ui/ManualScreen.kt`.

versionCode 83, versionName `0.7.3-background-location-permission`.

## Ronde 78 (10/08/2026) — G6 reconnect-scan naar hardware-offload ScanFilter (MAC-adres), na live-bewijs dat de chip-scan-dispatch zelf 117s werd uitgesteld tot scherm-aan, óók met achtergrondlocatie op "Altijd toestaan"

Vervolg op Ronde 77: de gebruiker bevestigde expliciet dat "Altijd toestaan"
voor locatie al aanstond, en leverde een nieuwe diagnostic-log
(`fclglucolink_2026-08-10.txt`) met scherm-uit/aan-tijden die de gebruiker
zelf meegaf (dicht 10:04, open 10:10). Precieze logcat-analyse liet iets
scherpers zien dan Ronde 76/77 al hadden blootgelegd: de app riep
`BluetoothLeScanner.startScan()` aan om 10:07:55.426 (meteen succesvol
geregistreerd, scannerId 7), maar het onderliggende chip-commando
(`[GSIM LOG] MESSAGE_SCAN_START` voor diezelfde scannerId) verscheen pas om
10:09:52.438 — 117 seconden later, en dat moment valt vrijwel exact samen
met het moment dat de gebruiker het scherm weer opende. Dus zelfs met de
achtergrondlocatie-permissie correct ingesteld (Ronde 77) werd de
daadwerkelijke scan-dispatch náár de Bluetooth-chip zelf uitgesteld tot
scherm-aan — dieper dan waar een locatie-permissie invloed op heeft.

**Historie-check vóór een voorstel.** `connectGatt(autoConnect=true)` is
voor exact dit symptoom (geen data tijdens scherm-uit, direct inhalen bij
scherm-aan) al eerder geprobeerd en teruggedraaid (zie Ronde 23/24 hierboven
in deze README) — dat pad is bewust NIET opnieuw ingeslagen.

**Root cause.** `DexcomG6Driver.kt`'s herverbind-scan gebruikte een
ONGEFILTERDE scan (`scanner.startScan(emptyList(), ...)`) — de Bluetooth-
chip moet dan ELKE advertentie in de buurt naar de hoofdprocessor
doorsturen om te filteren op MAC-adres. CareSens Air had een vergelijkbaar
screen-off-probleem en loste dat in Ronde 30 op met een hardware-offload
`ScanFilter` (op service-UUID, want het MAC-adres was daar op scanmoment nog
niet bekend) — dat liet de chip zélf matchen en de hoofdprocessor alleen
wakker maken bij een treffer. `DexcomG6Driver.kt` had dat equivalent nooit
gekregen.

**Fix.** In `startConnectScan()` (het herverbind-pad, waar het MAC-adres
van de gebonden transmitter altijd al bekend is — in tegenstelling tot de
initiële koppelscan, die bewust ongefilterd blijft) een
`ScanFilter.Builder().setDeviceAddress(deviceAddress).build()` toegevoegd
en meegegeven aan `scanner.startScan(scanFilters, ...)` i.p.v.
`emptyList()`. Zelfde hardware-offload-principe als CareSens Air's Ronde
30-fix, alleen op adres i.p.v. service-UUID.

**Zijspoor: xDrip+'s "Bluetooth Wakelocks"-instelling.** Op verzoek
onderzocht (xDrip+'s eigen documentatie/broncode/GitHub-discussies): dat is
een `PARTIAL_WAKE_LOCK` rond BLE-operaties voor toestellen die "agressief
BT-resetten" nodig hebben. `BleConnectionService.kt` houdt al sinds de
oorspronkelijke bouw een `PARTIAL_WAKE_LOCK` vast voor de VOLLEDIGE
levensduur van de service (niet alleen rond een scanpoging) — dus sterker
dan wat xDrip+ hier doet. Omdat het screen-off-probleem toch optrad ondanks
die al-aanwezige, permanente wakelock, is dat zelfs een aanwijzing dat het
knelpunt niet in het wakker houden van de hoofdprocessor zit (dat hebben we
al), maar dieper, in de Bluetooth-chip/firmware's eigen schermstaat-
gekoppelde energiebeheer — precies waar de ScanFilter-fix hierboven op
aangrijpt. Een extra xDrip-stijl-wakelock-instelling toevoegen zou dus naar
verwachting niets extra's opleveren; niet doorgevoerd.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt`.

versionCode 84, versionName `0.7.4-g6-scanfilter-hardware-offload`.

**Bevestigd werkend (10/08/2026, zelfde dag).** Nieuwe test: scherm dicht om
10:55, weer open om 11:05. Logcat toont tijdens dat hele venster twee
volledige scan-connect-lees-disconnect-cycli (10:57:55 en 11:03:01), en in
beide gevallen verscheen `MESSAGE_SCAN_START` binnen ~20-30ms na de
`startScan()`-aanroep — niet meer de 117s-vertraging van vóór deze fix.
Gebruiker bevestigde: "er zaten zo te zien geen gaten in de data." De
ScanFilter-hardware-offload was dus inderdaad de kern van het screen-off-
probleem op deze Samsung A40.

## Ronde 79 (10/08/2026) — 2-sensoren-architectuur: dual-slot data/verbinding + tab-gebaseerde Combi-UI

Grote, meerdaagse ronde op verzoek: "ik wil graag verder met de koppeling
van 2 sensoren binnen de app" — de app kan vanaf nu twee sensoren
(bijvoorbeeld Dexcom G6 + CareSens Air, of in de toekomst 2x hetzelfde
type) gelijktijdig gekoppeld en verbonden hebben, met een tab-gebaseerd
startscherm om ze te beheren en te kiezen welke er naar AAPS zendt.

**Kernvereiste (letterlijk):** "beide slots moeten kunnen zenden naar aaps
waarbij er uiteraard maar max 1 actief kan zijn, maar ze moeten ook beiden
uit kunnen." Dit is de rode draad door de hele ronde geweest.

**Data-/architectuurlaag:**
- `SensorSlot`-enum (`A`/`B`, `sensor/SensorDriver.kt`) — generieke,
  type-onafhankelijke slot-identiteit. Elke per-sensor `AppSettings`-functie
  neemt nu `slot: SensorSlot` als eerste parameter; een klein aantal
  instellingen (kalibratie, smoothing, bond-loss-herstel, diagnostic
  logging, batterij-optimalisatie-prompt) blijft bewust GLOBAAL, zie de
  kdoc bovenaan `AppSettings.kt`.
- `AppSettings.aapsActiveSlot: Flow<SensorSlot?>` vervangt de oude, globale
  `broadcastEnabled`-schakelaar volledig: `null` = niets zendt (beide uit
  kunnen), anders precies één van de twee slots (max 1 actief). Ingesteld
  via een 3-weg-keuze (Slot A / Slot B / Uit) op het Settings-scherm.
- `AppSettings.migrateLegacySingleSlotDataOnce()` — kopieert bestaande,
  vóór-deze-ronde instellingen éénmalig naar Slot A, zodat een bestaande
  installatie zijn gekoppelde sensor niet kwijtraakt bij het updaten.
  Draait synchroon (`runBlocking`) als allereerste statement in
  `FclGlucoLinkApp.onCreate()`.
- `BleConnectionService.kt` volledig herbouwd rond een `SlotRuntime`-klasse
  per slot (eigen driver-instantie, eigen `KalmanSmoother`, eigen
  verbindingsstatus) — beide slots draaien onafhankelijk, gelijktijdig,
  binnen dezelfde foreground-service.
- `DexcomG6Driver`/`CareSensAirDriver`/`SimulatorDriver` kregen een
  verplichte `slot`-constructorparameter: bleek nodig omdat deze drivers
  intern zelf óók vrijwel elk instellingenveld direct lazen/schreven (niet
  alleen `BleConnectionService`), dus zonder deze parameter zou een tweede
  gelijktijdige driver-instantie stiekem Slot A's data overschrijven.
- **Cross-slot databug gevonden en gefixt (proactief, vóór gebruikersmelding):**
  `CalibrationStore` had geen `sensorType`-scoping — een nieuwe-sensor-
  detectie op de ene slot kon de kalibratiedata van de ANDERE slot wissen,
  en kalibratiefits zouden vingerprikdata van twee verschillende fysieke
  sensoren door elkaar gebruiken. Gefixt via een Room-migratie
  (`MIGRATION_3_4`, database-versie 3→4, nullable `sensorType`-kolom) en
  een verplichte `sensorType`-parameter op alle `CalibrationStore`-functies.
- Zelfde soort bug gevonden in `GlucoseReadingStore`/`StatusScreen.kt`: het
  BG-scherm van elke slot las de ONGEFILTERDE, gecombineerde meetreeks van
  BEIDE slots door elkaar i.p.v. alleen zijn eigen sensor — `latestReading()`/
  `recentReadings()` kregen een `sensorType`-filterparameter, en elk
  UI-scherm dat een BG-waarde toont geeft nu expliciet zijn eigen slot's
  `selectedSensor` door.

**UI-laag — nieuw tab-gebaseerd startscherm (`ui/CombiScreen.kt`):**
letterlijk verzoek: "onderaan een rij met settings en info knop, daarboven
3 tabbladen die elk 1/3 van de breedte innemen: [sensor van slot A] /
[sensor van slot B] / Combi, tabblad-kleur groen als die slot naar AAPS
zendt, anders rood." Gebouwd exact zo — `CombiScreen` vervangt
`StatusScreen` als startscherm (`StatusScreen.kt` blijft als werkend,
op zichzelf staand scherm bestaan, alleen niet meer als route gebruikt).
De eigenlijke per-slot inhoud (BG-ring, Sensor/Calibration-knoppen,
compacte samenvatting, grafiek) is geëxtraheerd naar een nieuwe,
herbruikbare `SlotStatusContent()`-composable in `StatusScreen.kt`, zodat
zowel het losse `StatusScreen` als elk tabblad op `CombiScreen` 'm zonder
duplicatie hergebruiken. Het derde ("Combi") tabblad toont voorlopig twee
compacte kaartjes naast elkaar (type/status/laatste waarde/AAPS-indicator
per slot) — een echte samengevoegde grafiek met twee lijnen tegelijk is
bewust NIET in deze ronde gebouwd (`GlucoseChart.kt` is nu voor precies één
stream opgezet), een bekend vervolgpunt.

**Navigatie — alle koppel-/beheerroutes geparametriseerd per slot:**
vóór deze ronde bestond er maar één, altijd-op-Slot-A-vastgezet
koppel-/beheerpad. Om Slot A's en Slot B's tabblad elk een ECHT
onafhankelijk koppel-/beheerpad te geven (in plaats van dat Slot B's
"Sensor"-knop stiekem Slot A zou beheren), is `FclGlucoLinkNavHost.kt`
herbouwd: bijna elke route draagt nu een `{slot}`-padargument
(`slotRoute()`/`slotArg()`-helpers), en elke `navigate()`-aanroep binnen
een koppelflow (sensorkeuze, CareSens Air-scan/-keuze, G6-setup/nieuwe-
sensor, pairing) geeft de eigen slot door aan de volgende stap.

**Restant/bekende gaten (voor een volgende ronde):**
- `SimulatorControlBridge` is nog niet slot-bewust — de BG-simulator in
  Slot A en Slot B tegelijk testen (taak "Test combi UI met simulator in
  een/beide slots") is nog niet geverifieerd.
- `SensorSwitchEventStore` (de wisselmarkers op de grafiek) is nog niet
  per sensorType/slot gescoped — een marker van de andere slot kan in
  theorie op dit tabblad's grafiek verschijnen (kleine, cosmetische
  onvolkomenheid).
- Twee sensoren van HETZELFDE type tegelijk (bijvoorbeeld 2x Dexcom G6,
  voor een overlappende sensorwissel) is laagste prioriteit en nog niet
  apart getest.
- Deze hele ronde is via uitgebreide handmatige cross-controle
  (balance-check op haakjes/accolades + grep-gebaseerde signatuur-
  verificatie van elke aanroep tegen elke functiedefinitie) geverifieerd,
  NIET via een daadwerkelijke Kotlin/Gradle-compilatie — er was geen
  Android-toolchain beschikbaar in de werkomgeving van deze ronde. Een
  eerste `./gradlew assembleDebug` in Android Studio blijft dus de
  eigenlijke eerste harde compilatietest.

Gewijzigd/nieuw: `data/AppSettings.kt`, `sensor/SensorDriver.kt`,
`sensor/ble/BleConnectionService.kt`, `sensor/ble/ConnectionWatchdog.kt`,
`sensor/SensorRegistry.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `sensor/simulator/SimulatorDriver.kt`,
`calibration/CalibrationStore.kt`, `calibration/CalibrationEntryEntity.kt`,
`calibration/CalibrationEntryDao.kt`, `data/FclGlucoLinkDatabase.kt`,
`data/GlucoseReadingStore.kt`, `FclGlucoLinkApp.kt`, `MainActivity.kt`,
`ui/CombiScreen.kt` (nieuw), `ui/StatusScreen.kt`, `ui/FclGlucoLinkNavHost.kt`,
`ui/SettingsScreen.kt`, `ui/PairingScreen.kt`, `ui/SimulatorSetupScreen.kt`,
`ui/DexcomG6StatusScreen.kt`, `ui/CareSensAirStatusScreen.kt`,
`ui/DexcomG6NewSensorScreen.kt`, `ui/CalibrationScreen.kt`.

versionCode 85, versionName `0.8.0-dual-slot-combi-tabs`.

## Ronde 80 (10/08/2026) — live-testfeedback op de dual-slot Combi-UI (Ronde 79)

Directe follow-up op Ronde 79: de gebruiker testte de dual-slot-app live
(Dexcom G6 in Slot A, "Sending to AAPS"; BG-simulator in Slot B) en gaf
zeven concrete UI-/functionele verbeterpunten door. Alle zeven zijn deze
ronde afgehandeld.

**1) Bottom-right (i)-knop -> gelabelde "Manual"-knop.** `CombiScreen.kt`:
de icoon-only `IconButton` is vervangen door een `OutlinedButton` met
help-icoontje + "Manual"-tekst, naast de bestaande Settings-knop.

**2) "BG simulator"-tabbladtekst niet netjes gecentreerd.** Opgelost als
onderdeel van punt 3 hieronder (nieuwe chip-tekst gebruikt expliciet
`fillMaxWidth()` + `TextAlign.Center` + `maxLines = 1` +
`TextOverflow.Ellipsis`).

**3+4+6) Tabbladen herontworpen: losgekoppelde selectie-/AAPS-signalen,
afgeronde/vrijliggende chips.** De Material3 `TabRow`/`Tab` (met zijn
ingebouwde groene onderstrepings-indicator) is vervangen door een eigen
`Row` van 3 handgemaakte "chip"-composables (`CombiTabChip`) in
`CombiScreen.kt`:
- Afgeronde hoeken (`RoundedCornerShape(10.dp)`) + een klein gat tussen de
  chips (`Arrangement.spacedBy(6.dp)`) i.p.v. aaneengesloten segmenten.
- "Geselecteerd" en "zendt naar AAPS" zijn nu twee volledig gescheiden
  signalen: het GESELECTEERDE tabblad krijgt een helderdere/"oplichtende"
  achtergrond (`colorScheme.surfaceVariant` + volle tekstdekking); alle
  andere chips zijn bewust laag-contrast (`colorScheme.surface` + 55%
  tekst-alpha). De AAPS-zendstatus wordt getoond als een DUN gekleurd
  streepje (3dp, groen/rood) onderaan de chip — niet meer de hele
  achtergrond-tint van Ronde 79 (die botste visueel met de oude
  TabRow-onderstreping en met de nieuwe selectie-kleuring). Het
  Combi-tabblad heeft geen streepje (geen slot, dus geen AAPS-status).

**5) "None"-keuze bij sensorselectie (slot expliciet leegmaken).** Nieuw:
`AppSettings.clearSelectedSensor(slot)` verwijdert zowel de
sensor-TYPE-sleutel als het device-adres van die slot (zelfde
adres-wis-stap als de bestaande "Disconnect"-knoppen), en maakt
`aapsActiveSlot` ook leeg als die slot toevallig de zendende was. De
type-specifieke identiteitsvelden (G6-transmitter-ID, CareSens-scan)
blijven bewust staan, net als bij een gewone type-wissel.
`SensorSelectionScreen.kt` kreeg een vierde kaart ("None", bovenaan de
lijst) met dezelfde bevestigings-dialoog-logica als een gewone
sensorwissel (alleen nodig als er al een sensor actief was — er is dan
immers iets te ontkoppelen). `FclGlucoLinkNavHost.kt` wiert de nieuwe
`onClearSensor`-callback op dezelfde manier als `onSensorChosen` (BLE-
service stoppen, `ConnectionStatusBridge` bijwerken, dan de instelling
wissen en terugnavigeren).

**7) Calibratie per-slot + root-cause van de gemelde "-0,3 delta toont
4,6"-fout.** Twee delen:
- *Per-slot calibratie-instellingen.* `calibrationMode`/
  `calibrationManualOffsetMmol` in `AppSettings.kt` waren nog globaal
  (gedeeld tussen Slot A en B) — nu per-slot, zelfde
  `slotString`/`slotDouble`-sleutelfabriek als de rest. Omdat de
  hoofdmigratie uit Ronde 79 (`migrateLegacySingleSlotDataOnce()`) op het
  toestel van de gebruiker al gedraaid had (bevestigd via de live-
  screenshots), kon deze wijziging niet zomaar aan die functie worden
  toegevoegd — die vlag stond al op `true` en de functie zou meteen
  terugkeren. Een NIEUWE, apart bewaakte migratie
  (`migrateLegacyCalibrationToSlotAOnce()`, eigen
  `MIGRATION_CALIBRATION_PER_SLOT_DONE`-vlag) kopieert de oude globale
  waarden één keer naar Slot A, en wordt vanuit `FclGlucoLinkApp.onCreate()`
  ná de bestaande hoofdmigratie aangeroepen. `BleConnectionService.kt`'s
  `applyCalibrationIfEnabled()` (de ECHTE toepassing op inkomende
  metingen, niet alleen het UI-scherm) kreeg een `slot`-parameter erbij en
  leest nu ook de per-slot instellingen.
- *Root-cause van de gemelde delta-fout.* De gebruiker vermoedde zelf een
  mg/dL-vs-mmol-eenheidsbug; `calibration/CalibrationValidation.kt` is
  volledig nagelezen en de mmol-conversie bleek correct. De ECHTE oorzaak:
  `CalibrationScreen.kt` had nog TWEE ongefilterde
  `GlucoseReadingStore`-aanroepen (`latestReading()` op regel 134 en
  `recentReadings(hours = 1)` op regel 297) die bij de eerdere Ronde-79-
  fixronde gemist waren (een grep-patroon met een hardcoded `store.`-
  prefix matchte de lokale variabelenaam `readingStore` niet, vanwege de
  hoofdletter S). Met Slot A (Dexcom, destijds ~8,8 mmol/L) en Slot B
  (simulator, ~6,8 mmol/L) allebei gelijktijdig actief kregen het
  invulscherm-startpunt én de delta-validatie-gate hierdoor soms een
  meting van de VERKEERDE slot te zien — precies dezelfde bugklasse als de
  eerder gevonden en gefixte cross-slot-databugs, nu ook hier gevonden en
  gefixt (`sensorType = sensorType` toegevoegd aan beide aanroepen).

**8) Combi-tabblad: echte dual-line-grafiek.** Nieuwe `DualGlucoseChart()`
in `GlucoseChart.kt` — een bewust kleinere, aparte composable naast de
bestaande (single-stream) `GlucoseChart()`: dezelfde tijd-as/zoom/pan-
logica, maar twee losse `LineDataSet`s (één per slot) in vaste kleuren
i.p.v. de per-punt bereikskleuring/raw-cirkels/wisselmarkers van de
per-slot-grafiek (met 2 lijnen zou dat een onleesbare kleurenbrij worden).
De lijnkleuren zijn exact `colorA`/`colorB` uit `CombiScreen()` — dezelfde
groen/rood-AAPS-streepjeskleur als op de tabbladkopjes erboven, dus
letterlijk "de kleur van de lijn komt overeen met de kleur van de naam
balk erboven". Een klein kleurenlegenda-rijtje (`CombiChartLegend`, eigen
Compose-`Text`/`Box`-labels i.p.v. MPAndroidChart's ingebouwde legend, voor
stijlconsistentie) staat erboven. `CombiTabContent()` leest beide slots'
recente metingen zelf op (`GlucoseReadingStore.recentReadings(hours = 24,
sensorType = ...)`), met dezelfde `selectedSensorX?.let { ... } ?:
flowOf(emptyList())`-voorzorg als bij punt 7 hierboven om de
cross-slot-bugklasse hier niet opnieuw te introduceren.

**Verificatie:** zelfde methode als Ronde 79 — geen Kotlin/Gradle-
compiler beschikbaar in de werkomgeving; geverifieerd via de
brace/paren-balanschecker en exhaustieve grep-gebaseerde signatuur-
cross-referentie van elke gewijzigde/nieuwe aanroep tegen zijn
definitie. Een eerste `./gradlew assembleDebug` in Android Studio blijft
de eigenlijke eerste harde compilatietest.

Gewijzigd/nieuw: `data/AppSettings.kt`, `FclGlucoLinkApp.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/CalibrationScreen.kt`,
`ui/CombiScreen.kt`, `ui/GlucoseChart.kt`, `ui/SensorSelectionScreen.kt`,
`ui/FclGlucoLinkNavHost.kt`, `app/build.gradle.kts`.

versionCode 86, versionName `0.8.1-combi-tabs-polish`.

## Ronde 81 (10/08/2026) — live-testfeedback op Ronde 80: 2 kritieke bugs + Combi-tabelletje

Directe follow-up op Ronde 80, met screenshots. Twee gemelde bugs bleken
allebei echte, in deze ronde geïntroduceerde/blijvende cross-slot-fouten
(geen van beide een nieuw verzoek) plus één nieuwe UI-uitbreiding.

**Bug 1 (CRITICAL) — kalibraties van de ene slot verschenen ook bij de
andere.** Root cause: `ROUTE_CALIBRATION` in `FclGlucoLinkNavHost.kt` was
NOG STEEDS een kale, niet-slot-geparametriseerde route (`"calibration"`,
geen `{slot}`) — een overblijfsel van vóór Ronde 80 dat calibratie per-slot
maakte. Ronde 80 maakte wel `CalibrationScreen()`'s eigen `slot`-parameter
en `AppSettings`' kalibratiefuncties per-slot, maar verzuimde de
ROUTE/navigatie zelf mee te nemen: `CalibrationScreen(onBack = { ... })`
werd altijd zonder expliciete slot aangeroepen, dus ALTIJD met de default
`slot = SensorSlot.A` — ongeacht vanuit welk tabblad de "Calibration"-knop
was aangetikt. Gevolg: elke ingevoerde kalibratie werd feitelijk altijd
onder Slot A's sensor opgeslagen, ook als je 'm vanuit Slot B's tabblad
invoerde. Fix: `ROUTE_CALIBRATION` is nu `"calibration/{slot}"`,
`CombiScreen.kt`'s `onOpenCalibration` is nu `(SensorSlot) -> Unit` (net
als `onSwitchSensorType`/`onOpenSensorStatus`), en elk tabblad geeft zijn
eigen slot door.

**Bug 2 — een slot zonder gekozen sensor toonde toch de andere slot's
data.** Zichtbaar in de meegestuurde screenshot: Slot B's tabblad zei "No
sensor chosen yet" in tekst, maar de BG-ring/grafiek erboven toonden
gewoon Slot A's Dexcom-data. Root cause: overal waar `val selectedSensor
by settings.selectedSensor(slot)...` gevolgd werd door `store.
latestReading(sensorType = selectedSensor)` (of `recentReadings`), gold:
zodra [selectedSensor] zelf `null` is (nog geen sensor voor déze slot
gekozen), behandelt `GlucoseReadingStore` `sensorType = null` als "GEEN
filter" — dus de GECOMBINEERDE stream van BEIDE slots, niet "niets". De
Ronde-79-kdoc bij deze code beweerde zelfs expliciet (en ten onrechte) dat
dit geen probleem was. Gefixt op alle 4 plekken waar dit patroon
voorkwam: `StatusScreen.kt`'s `SlotStatusContent()` (latest + recent),
`CombiScreen.kt`'s `CombiSlotSummaryCard()`, en `CalibrationScreen.kt`'s
`latestReadingFlow` + de `recentRaw`-opvraging in de add-calibration-
bevestiging — nu overal `selectedSensor?.let { store.xxx(sensorType = it)
} ?: flowOf(null/emptyList())` i.p.v. de sensorType-waarde blind
doorgeven.

**Nieuw — Combi-tabblad: klein tabelletje boven de grafiek.** Op
letterlijk verzoek: een 2-koloms tabelletje (Slot A / Slot B) vóór
`DualGlucoseChart`, met per kolom (1) de groene/rode AAPS-stip + sensornaam,
(2) de laatste BG-waarde, (3) "Sending to AAPS" (alleen voor de daadwerkelijk
zendende slot, anders een lege, even-hoge plek zodat beide kolommen
uitgelijnd blijven). Nieuwe `CombiSlotTable`/`CombiSlotTableColumn`
composables in `CombiScreen.kt`, vervangt de kleinere kleurenlegenda van
Ronde 80 (die was feitelijk al de eerste rij van dit tabelletje).

**Verificatie:** zelfde methode als voorgaande rondes — geen Kotlin/Gradle-
compiler beschikbaar; balance-checker + exhaustieve grep-cross-referentie
van elke gewijzigde aanroep/signatuur, inclusief een aparte controle dat
GEEN `navigate(ROUTE_CALIBRATION)`-aanroep zonder slot is overgebleven.

Gewijzigd: `ui/FclGlucoLinkNavHost.kt`, `ui/CombiScreen.kt`,
`ui/CalibrationScreen.kt`, `ui/StatusScreen.kt`, `app/build.gradle.kts`.

versionCode 87, versionName `0.8.2-combi-tabs-slot-fixes`.

## Ronde 82 (10/08/2026) — live-testfeedback op Ronde 81: 3 polijstpunten

Derde live-testronde, ditmaal alle drie letterlijk cosmetische/UX-verzoeken
(geen functionele bugs meer — "functioneel lijkt alles nu te werken met 1
sensor en een virtuele in ieder geval").

**1. Geselecteerde tabblad-chip valt nu écht op.** Root cause: `CombiTabChip`
(Ronde 80) gebruikte `colorScheme.surfaceVariant` (geselecteerd) vs.
`colorScheme.surface` (niet geselecteerd) als achtergrond — maar
`Theme.kt`'s `DarkColors` zet `surfaceVariant` EXPLICIET gelijk aan
`SurfaceDark` (= `surface`), een bewuste keuze uit een eerdere ronde om
Material3 Card's eigen te-lichte standaard-surfaceVariant te overschrijven.
Gevolg: de "selectie-achtergrond" van Ronde 80 was in de praktijk exact
dezelfde kleur als een niet-geselecteerd tabblad — alleen de tekstkleur
verschilde, precies de gemelde klacht ("nu is alleen de titel witter
gekleurd"). Bewust GEEN groen/rood gebruikt (die kleuren betekenen al iets
anders: de AAPS-zend-status-streep onderaan elke chip) — nu een eigen,
duidelijk zichtbare blauwe accentkleur (`0xFF2C4F82` achtergrond,
`0xFFEAF1FF` tekst) voor de geselecteerde chip.

**2. Combi-tabblad: overbodige slotkaarten weg + tabelletje krijgt een
kopregel.** De twee losse `CombiSlotSummaryCard`-kaarten die sinds Ronde 79
onder de grafiek stonden (slotlabel, sensornaam, AAPS-status, laatste
waarde) waren sinds Ronde 81's nieuwe `CombiSlotTable` bovenaan feitelijk
dubbel — verwijderd uit `CombiTabContent()`, en omdat daarmee hun enige
aanroepplekken wegvielen ook de compleet ongebruikte `CombiSlotSummaryCard`-
en `combiConnectionStatusText`-functies zelf verwijderd (plus de daarmee
dan ongebruikte `ConnectionState`/`ConnectionStatusBridge`-imports).
`CombiSlotTable` kreeg een eigen kopregel met de vaste slotlabels
(`SensorSlot.A/B.displayLabel`, dus altijd "Slot A"/"Slot B" — bewust ANDERS
dan de kolom's eigen naam-rij eronder, die de GEKOZEN SENSOR toont, bv.
"Dexcom G6"), optisch gescheiden van de info eronder met een
`HorizontalDivider`, zodat het geheel leest als een echte tabel: kopregel,
dan content.

**3. Fix: Combi-grafiek schaalde de Y-as niet mee met de hoogste BG.**
Zichtbaar in het screenshot: de roze BG-simulator-lijn liep plat tegen de
bovenrand van de grafiek op 12 mmol/L. Root cause: `DualGlucoseChart`
(Ronde 80) zette `axisLeft.axisMinimum`/`axisMaximum` alleen éénmalig vast
op 2f/12f in de `factory`-blok, en riep — anders dan de per-slot
`GlucoseChart` — nergens de bestaande `recomputeYAxisMax()`-herberekening
aan. Die functie zocht bovendien specifiek naar een dataset met label "BG"
(de naam die alléén `GlucoseChart`'s eigen enkele lijn gebruikt);
`DualGlucoseChart`'s twee lijnen heten "slot-A"/"slot-B". Gefixt door
`recomputeYAxisMax()` generiek te maken (`labels: List<String>` i.p.v. de
vaste naam "BG", met `listOf("BG")` als standaardwaarde zodat alle
bestaande aanroepen ongewijzigd blijven werken) en `DualGlucoseChart` 'm nu
aan te roepen zowel na elke data-ververing als in de
`OnChartGestureListener` (net als `applyXAxisGranularity`), met een nieuwe
`DUAL_CHART_Y_AXIS_LABELS = listOf("slot-A", "slot-B")`.

**Verificatie:** zelfde methode als voorgaande rondes — geen Kotlin/Gradle-
compiler beschikbaar; balance-checker (haakjes/comments/strings-bewust) op
beide gewijzigde bestanden + grep-controle dat geen enkele referentie naar
de verwijderde `CombiSlotSummaryCard`/`combiConnectionStatusText` is
overgebleven.

Gewijzigd: `ui/CombiScreen.kt`, `ui/GlucoseChart.kt`, `app/build.gradle.kts`.

versionCode 88, versionName `0.8.3-combi-tabs-visual-polish`.

## Ronde 82b (10/08/2026) — compile-fix: dode SensorManagementScreen.kt

Live-melding: Android Studio gaf compile-errors in `SensorManagementScreen.kt`
("Function invocation 'state(...)' expected" e.d. op regels 76/77/81/91/92/
97/98). Root cause: dit bestand staat sinds Ronde 64 gemarkeerd als
"VERVALLEN, NIET MEER GEBRUIKT" (vervangen door `DexcomG6StatusScreen.kt`/
`CareSensAirStatusScreen.kt`) met een kdoc die claimde dat het "bewust NIET
meegenomen" werd bij het bouwen van de zip — maar de eenvoudige recursieve
`zip`-stap die deze sessie gebruikt sloot het niet expliciet uit, dus het
zat gewoon in de laatst geleverde zip. Gradle compileert domweg alle
.kt-bestanden onder `app/src/main/java`, dode code of niet, en dit bestand
riep nog de VOOR-dual-slot API's aan (`ConnectionStatusBridge.state`,
`settings.selectedSensor`, enz. zonder `slot`-argument) die tijdens de
dual-slot-migratie (Ronde 78/79) overal elders zijn omgezet naar
`state(slot)`/`selectedSensor(slot)` — dit bestand niet, want het gold toen
al als niet-meer-aangeroepen. Bevestigd met een projectbrede grep: nergens
een echte aanroep `SensorManagementScreen(...)`, alleen kdoc-vermeldingen
van de bestandsnaam elders ter documentatie. De samengestelde functie is nu
volledig verwijderd (alleen een korte toelichtende comment blijft, want de
schrijf-beveiligde outputs-map laat het bestand zelf niet verwijderen).

Gewijzigd: `ui/SensorManagementScreen.kt`, `app/build.gradle.kts`.

versionCode 89, versionName `0.8.4-dead-file-compile-fix`.

## Ronde 83 (10/08/2026) — fix: AAPS-slot krijgt voorrang op gedeeld scan-budget

Live-melding na de eerste keer dat beide sensoren (CareSens Air + Dexcom G6)
tegelijk gekoppeld waren: "de timing deugd nu niet meer", met een
diagnostic-logbestand als bewijs.

**Diagnose.** CareSens Air's eigen reconnect-cadans staat de hele dag (00:03
t/m 19:25) rotsvast op 5 minuten. Vanaf 19:33:50 — exact het moment dat de
Dexcom-koppeling actief werd — schuift die structureel naar 6 minuten
(19:38:48 → 19:44:48 → 19:50:48 → 19:56:48 → 20:02:48, steeds +1 minuut).
Root cause: `ScanRateLimiter` (sensor/ble/ScanRateLimiter.kt) is een
gedeeld, PROCES-BREED plafond van max. 5 BLE-scan-starts per 31 seconden
(mirror van Juggluco's aanpak tegen Android's ongedocumenteerde achtergrond-
scanquota) — bewust gedeeld, want die Android-limiet maakt zelf ook geen
onderscheid tussen sensoren. Tot deze ronde telde elke scan-start van BEIDE
slots gewoon mee tegen datzelfde budget. In het log vuurt Dexcom G6 bij een
gemiste beacon 4 scan-starts binnen ~17s af (retry-burst) — dat verbruikt in
één keer 80% van het hele gedeelde budget, waardoor CareSens Air's eigen
scan-start op dat moment moest wachten.

**Gevraagde fix, letterlijk verzoek — "de sensor die aan aaps is gekoppeld
altijd de voorrang [...] en dus precies om de 5 minuten blijft data
binnenhalen".** `ScanRateLimiter.delayBeforeNextScanMs()`/`recordScanStart()`
kregen een `isPriority`-parameter. Een priority-aanroep (de AAPS-actieve
slot, `AppSettings.aapsActiveSlot`) toetst zijn eigen plafond alleen tegen
ANDERE priority-scans — niet-priority-verkeer (de andere slot) telt voor
hem dus gewoon niet mee, dus wordt hij er nooit door opgehouden. Een
niet-priority-aanroep blijft toetsen tegen de VOLLE geschiedenis (priority +
niet-priority), dus die wijkt bij krapte altijd uit. Zowel
`CareSensAirDriver.scheduleScanAttempt()` als `DexcomG6Driver.scheduleScanAttempt()`
lezen `settings.aapsActiveSlot.first() == slot` vers bij ELKE scanpoging
(niet één keer gecached bij `connect()`) — dekt dus ook het geval dat de
AAPS-bron tussentijds naar de andere slot wordt omgezet. Het gedeelde
plafond zelf (5/31s) is ongewijzigd — dit verandert alleen wie bij krapte
moet wachten, niet hoeveel er in totaal mag (blijft dus even veilig t.o.v.
Android's eigen quota).

**Controlevraag van de gebruiker beantwoord (niet in code, maar hier
gedocumenteerd omdat het de aanleiding voor deze fix mede onderbouwt):** is
er een veilige minimale tijdsafstand tussen de twee sensoren se cycli om
"conflicten" te vermijden? Nee — en dat is precies waarom voorrang (i.p.v.
tijdspreiding) de juiste fix is. Dexcom's cyclus ligt vast in de
transmitter zelf (niet door de app te sturen); CareSens Air's cyclus wordt
berekend vanaf zijn eigen laatste geslaagde meting — de twee lopen dus
NIET gesynchroniseerd en hun onderlinge faseverschil drift over uren/dagen
vanzelf (de "2-3 minuten" van nu is toeval-van-het-moment, geen vaste
garantie). Bovendien is het sowieso geen fysiek radioprobleem (Android's
Bluetooth-controller multiplext gewoon meerdere gelijktijdige BLE-operaties
naar verschillende apparaten, zoals elke telefoon met oordopjes+horloge+scan
tegelijk al lang doet) — het knelpunt zat uitsluitend in dit gedeelde
software-budget, niet in de radio zelf.

**Verificatie:** balance-checker op de 3 gewijzigde bestanden; grep-controle
dat dit de enige twee aanroepplekken van `ScanRateLimiter` zijn.

Gewijzigd: `sensor/ble/ScanRateLimiter.kt`, `sensor/caresensair/CareSensAirDriver.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`, `app/build.gradle.kts`.

versionCode 90, versionName `0.8.5-scan-priority-for-aaps-slot`.

## Ronde 84 (10/08/2026) — fix: wisselmarker lekte naar andere slot + handleiding bijgewerkt

**Bug, live gemeld met screenshots.** Bij het activeren van een nieuwe
Dexcom G6-sensor (~19:40) verscheen de verwachte gestippelde wisselmarker-
lijn niet alleen op de Dexcom-tab, maar OOK op de CareSens Air-tab, die op
dat moment al dagenlang ongestoord liep.

**Root cause.** `sensor_switch_events` (de Room-tabel achter deze markers,
zie SensorSwitchEventEntity.kt) had helemaal geen `sensorType`/slot-kolom —
elke marker was dus letterlijk voor elke lezer zichtbaar, ongeacht welke
slot 'm veroorzaakte. Precies de bekende, in Ronde 79 bewust uitgestelde
onvolkomenheid ("SensorSwitchEventStore kent nog geen sensorType-kolom
[...] bewust niet in deze ronde opgelost") — nu wél aangepakt.

**Fix.** Nieuwe nullable `sensorType`-kolom op de entity (`MIGRATION_4_5`,
database-versie 4 -> 5, zelfde "ALTER TABLE i.p.v. destructive migration"-
aanpak als eerdere migraties). `SensorSwitchEventDao`/`Store` VERPLICHT
gescoped op `SensorType`, net als `GlucoseReadingStore`/`CalibrationStore`.
Aan de schrijfkant (`BleConnectionService.kt`, waar de marker wordt
vastgelegd bij de eerste meting van een nieuwe sensor-sessie) wordt nu het
al-bekende `sensorType` van die slot meegegeven. Aan de leeskant
(`StatusScreen.kt`'s `SlotStatusContent()`) hetzelfde null-guarded
scoping-patroon als `latestFlow`/`recentFlow` daar al gebruikten.

**Handleiding bijgewerkt (apart verzoek in dezelfde ronde).**
`ManualScreen.kt` dateerde nog grotendeels van vóór de 2-sensoren-
architectuur en bevatte een echte feitelijke fout: Dexcom G6 stond nog
vermeld onder "Planned, not available yet", terwijl die sensor al sinds
Ronde 55+ daadwerkelijk werkt (en precies de sensor is waarmee de
gebruiker leeft test). Bijgewerkt:
- HOME_SCREEN: nieuwe sectie over de tabbalk (per-slot tabs + Combi-tab,
  geselecteerd-vs-AAPS-zendend zijn twee losse signalen) en een nieuwe
  sectie over de Combi-tab zelf.
- SENSORS: Dexcom G6 verplaatst naar "beschikbaar nu"; nieuwe sectie over
  de twee onafhankelijke slots (elk met eigen "Sensor"-knop, "None" om
  leeg te maken, twee keer hetzelfde sensortype toegestaan).
- SETTINGS: de oude enkelvoudige "Send BG to AAPS"-schakelaar-tekst
  vervangen door de daadwerkelijke Slot A/Slot B/Off-kiezer (letterlijk in
  lijn met SettingsScreen.kt's eigen tekst).
- CALIBRATION: nieuwe sectie die verduidelijkt dat "Enable calibration"
  één algemene schakelaar is, maar de kalibratiedata zelf sinds Ronde 81
  per slot gescheiden is.

**Verificatie:** balance-checker op alle 6 gewijzigde bestanden; grep-
controle dat alle aanroepplekken van `SensorSwitchEventStore`/`Dao` het
nieuwe, verplichte `sensorType`-argument gebruiken.

Gewijzigd: `data/SensorSwitchEventEntity.kt`, `data/SensorSwitchEventDao.kt`,
`data/SensorSwitchEventStore.kt`, `data/FclGlucoLinkDatabase.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/StatusScreen.kt`,
`ui/ManualScreen.kt`, `app/build.gradle.kts`.

versionCode 91, versionName `0.8.6-switch-marker-fix-manual-update`.

## Ronde 85 (10/08/2026) — fix: Dexcom miste structureel om de andere cyclus een meting

**Live-log-analyse, op verzoek** ("dexcom zendt gewoon om de 5 minuten dus
als je afgerond zegt 5:11 en 9:48 dan valt er bij de 9:48 gewoon eentje
weg. Zal dit patroon nog veranderen als ik de dexcom als aaps sensor zou
zetten?"). Geanalyseerd: `fclglucolink_2026-08-10-c3960ea6.txt` (21
Dexcom-metingen sinds koppeling om 19:33) plus de door de gebruiker
geplakte logcat-regels tot 22:18:30. De letterlijk meegestuurde bijlage
van dit gesprek (`fclglucolink_2026-08-10.txt`, 8,8KB) bleek de oude,
stale ochtend-log (00:08–08:13) te zijn — niet bruikbaar, dus de al
eerder geüploade, grotere log is gebruikt.

**Bevinding.** Tijd- en seq-delta's per meting gecorreleerd: elke
~311s-tussenpoos hoort bij `seq+1` (gewoon de eerstvolgende 5-minuten-
uitzending gevangen), elke ~588s-tussenpoos hoort bij `seq+2` (één hele
5-minuten-uitzending overgeslagen — exact zoals de gebruiker terecht
concludeerde, geen "jitter" maar een echt gemiste meting). Dit patroon
was er al vanaf het koppelmoment (19:33), dus ruim vóór vanavonds
v91-herstart, en dus GEEN regressie van een eerdere ronde.

**Root cause.** `DexcomG6Driver.PREDICTIVE_RECONNECT_LEAD_MS` stond op
280 000ms (4m40s) — dat laat maar ~20s marge vóór de verwachte
300s-meetmarkering. Anders dan CareSens Air (adverteert vrijwel continu,
zie Ronde 31's kdoc) heeft de G6-transmitter maar een KORT verbindbaar
venster rond elke meting. Met slechts 20s marge miste een doodgewone
scan-dispatch-vertraging (zelfde soort OS-vertraging als eerder gemeten
bij de ScanFilter-fix, tot 117s) dat venster regelmatig — de scan ving
dan pas de VOLGENDE uitzending, 300s later, vandaar de 9:48-tussenpoos.

**Antwoord op de controlevraag.** Nee: dit patroon zit in de leadtime-
marge van de Dexcom-driver zelf, los van welke slot AAPS-actief is —
Ronde 83's scan-prioriteitsfix regelt alleen wie wint bij een
gedeeld-scan-budgetconflict tussen de twee slots, niet de eigen
verbind-timing van één sensor. Dexcom als AAPS-slot instellen zou dit
patroon dus NIET vanzelf hebben opgelost.

**Fix.** `PREDICTIVE_RECONNECT_LEAD_MS` verlaagd naar 240 000ms (4m00s),
dus 60s marge i.p.v. 20s — driemaal zoveel speling vóór het venster.
Zelfde soort aanpassing als CareSens Air destijds al kreeg (Ronde 32:
leadtime verkort voor meer marge). CareSens Air's eigen constante blijft
ongewijzigd (die haalt al ~93% exacte 5-minuten-cadans over 261 cycli op
de onderzochte dag — geen aanleiding om daar iets aan te veranderen).

**Verificatie:** balance-checker; dit is een gerichte, geïsoleerde
constante-wijziging (plus kdoc), geen bijkomende call-sites om te
controleren. Definitieve bevestiging vereist een langere vervolg-log
(een paar uur, bij voorkeur een keer 's nachts) om te zien of de
miss-rate daadwerkelijk daalt.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt`, `app/build.gradle.kts`.

versionCode 92, versionName `0.8.7-dexcom-leadtime-margin-fix`.

## Ronde 86 (10/08/2026) — fix: eenmalige scanbotsing verschoof CareSens Air's cadans permanent naar 6:00

**Live-log-melding, met verse bijlage** (`fclglucolink_2026-08-10 23.10.txt`,
tot 23:13): "Het lijkt wel of nu, sinds de laatste aanpassing van 22:40 de
care sense om de 6 minuten komt." Bevestigd met harde cijfers: sinds
~22:40 zat CareSens muurvast op 359,7–360,3s (6:00), terwijl Dexcom in
dezelfde periode bijna perfect op 299,6–300,2s (5:00) zat, zes cycli op
rij — precies wat Ronde 85 beoogde.

**Root cause.** `Scan-record voor 48:A3:BD:5A:3C:89` (CareSens) om
22:48:18.695 en `DexcomG6: CCCD write ok` om 22:48:18.653 — 42ms uit
elkaar: een echte botsing tussen de twee scans, ontstaan doordat Ronde
85's marge-verruiming Dexcom's scans nu vrijwel altijd raak laat zijn
(dus vaker een actieve scan heeft lopen om mee te botsen dan voorheen).
Die ÉÉN botsing verschoof CareSens Air's meting met 60s — en omdat
`computeReconnectCooldownMs()` de volgende voorspelling puur doorrekende
vanaf de LAATSTE (dus al verschoven) meting, zonder enig absoluut
referentiepunt, bleef die verschuiving daarna permanent hangen: geen
volgende cyclus corrigeerde ooit terug naar het oorspronkelijke
5-minuten-raster. Dit ontwerpkenmerk zat er al sinds Ronde 31 in beide
drivers (identiek patroon) — het is vanavond voor het eerst zichtbaar
"vastgeklikt" doordat botsingen nu vaker een reëel gevolg hebben.

**Gebruiker koos expliciet voor de structurele fix** (i.p.v. het zo laten
of alleen handmatig resetten).

**Fix, in beide drivers (CareSensAirDriver.kt + DexcomG6Driver.kt).**
Nieuw veld `cadenceAnchorAtMs`: één keer gezet bij de EERSTE geslaagde
meting van een connect()-sessie, gereset samen met
`lastSuccessfulConnectionAtMs`. `computeReconnectCooldownMs()` snapt de
laatste meting nu naar het dichtstbijzijnde veelvoud van
`SENSOR_PERIOD_MS` (300 000ms) sinds dat anker (via afronden), en mikt de
volgende poging op ÉÉN periode na dat rasterpunt. Een eenmalige
vertraging van bijvoorbeeld 60s wordt zo niet meegenomen naar de
volgende voorspelling — die mikt gewoon weer op het oorspronkelijke
tijdstip, en de eerstvolgende scanpoging wordt automatisch iets vroeger
gepland om dat verschil in te lopen. Bij een gemiste hele cyclus (~10
minuten) rondt de afronding naar het volgende (latere) rasterpunt i.p.v.
het vorige, wat correct is.

Als bijkomende, complementaire verbetering CareSens Air's eigen marge
ook opgetrokken van 20s naar 60s (nu gelijk aan Dexcom's Ronde-85-marge)
— gegeven dat botsingen tussen de twee nu vaker een reëel gevolg hebben,
is een even ruime marge aan beide kanten het meest consistente vangnet.

**Verificatie:** balance-checker op beide bestanden; grep-controle dat
geen stale verwijzingen naar de oude `PREDICTIVE_RECONNECT_LEAD_MS`-naam
overbleven, en dat `cadenceAnchorAtMs` op alle reset-/zet-punten
(`connect()`, volledige `disconnect()`, eerste geslaagde meting)
consistent is meegenomen in beide drivers.

Gewijzigd: `sensor/caresensair/CareSensAirDriver.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`, `app/build.gradle.kts`.

versionCode 93, versionName `0.8.8-self-correcting-cadence-anchor`.

## Ronde 87 (10/08/2026) — fix: Dexcom G6 stond in AAPS als "Unknown" i.p.v. herkend, dus geen SMB Always

**Live-melding:** "Kun je nog eens kijken welke string de descom g6
meestuurt naar AAPS. Als ik het goed zie geeft die 'unknown' binnen aaps
daar waar de caresense 'Random' geeft en dus wel smb always heeft."

**Bevestigd tegen AAPS's echte broncode** (`SourceSensor.kt`, eerder door
de gebruiker geüpload). `XDripBroadcaster.kt`'s `sourceInfo()` stuurde
voor Dexcom G6 de string `"G6"` mee via de `SourceInfo`-extra. AAPS'
`SourceSensor.fromString()` doet een EXACTE match tegen elke enum-
waarde's `.text`-veld; alleen G7 heeft een kale `"G7"`-waarde
(`DEXCOM_G7_XDRIP`) — voor G6 bestaat die niet, dus `"G6"` matchte niets
en viel terug op `UNKNOWN`. `UNKNOWN` zit nooit in de
`advancedFilteringSupported()`-whitelist (SourceSensorExtensions.kt),
dus geen SMB Always — precies het gemelde gedrag.

**Fix.** `"G6"` → `"G6 Native"`, exact `DEXCOM_G6_NATIVE_XDRIP`'s
`.text`-waarde — die staat wél in de whitelist. Anders dan bij CareSens
Air (waar bewust voor "Random" gekozen is om geen ander sensormerk te
imiteren, zie Ronde 42's kdoc) is hier geen omweg nodig: het IS
letterlijk een Dexcom G6, dus de eerlijke, exacte naam kan gewoon
gebruikt worden. G7's mapping ("G7") was al correct, ongewijzigd
gelaten.

**Verificatie:** balance-checker; dit is een geïsoleerde
string-wijziging in één `when`-tak, geen andere call-sites. Definitieve
bevestiging vereist een live AAPS-blik op het broncode-scherm ná de
eerstvolgende Dexcom-meting.

Gewijzigd: `broadcast/XDripBroadcaster.kt`, `app/build.gradle.kts`.

versionCode 94, versionName `0.8.9-dexcom-source-string-fix`.

## Ronde 88 (11/08/2026) — sync: XDripBroadcaster.kt naar de zelf-geteste, werkende waarde "AAPS-Dexcom"

**Live-melding:** "Ik heb in XDripBroadcaster.kt het sensortype van de g6
van 'G6 Native' veranderd in 'AAPS-Dexcom' met de native gaf hij in aaps
'unknown' aan en nu geeft hij 'AAPS-Dexcom'."

**Bevinding.** Ronde 87's `"G6 Native"` matcht exact
`DEXCOM_G6_NATIVE_XDRIP`'s `.text`-waarde in `SourceSensor.kt` en staat
in de `advancedFilteringSupported()`-whitelist — op papier had dat dus
moeten werken. Toch gaf het op het toestel zelf "Unknown". De
gebruikers eigen handmatige test met `"AAPS-Dexcom"`
(`DEXCOM_NATIVE_UNKNOWN`'s `.text`-waarde, ook in de whitelist) werkt
wél en toont nu correct in AAPS. Waarom `"G6 Native"` in de praktijk
niet aansloeg, kon ik niet volledig verklaren zonder `Intents.kt`
(de exacte extra-key-naam achter `Intents.XDRIP_DATA_SOURCE` is niet
onafhankelijk geverifieerd) of meer van AAPS' UI-laag — dat blijft een
open, niet volledig verklaard punt. Kanttekening: `"AAPS-Dexcom"`
representeert semantisch AAPS' eigen native Dexcom-plugin-pad, niet het
xDrip-broadcast-pad — dat heeft echter geen invloed op de
whitelist-check, en het toestel zelf is hier de doorslaggevende bron
van waarheid.

**Fix.** `sourceInfo()`'s `SensorType.DEXCOM_G6`-mapping van `"G6 Native"`
naar `"AAPS-Dexcom"`, in lijn met de gebruikers eigen geverifieerde
live-test.

**Verificatie:** balance-checker; geïsoleerde string-wijziging in
dezelfde `when`-tak als Ronde 87, geen andere call-sites.

Gewijzigd: `broadcast/XDripBroadcaster.kt`.

## Ronde 89 (11/08/2026) — fix: permanente wakelock overbodig sinds Ronde 36, dominante oorzaak hoog batterijverbruik

**Live-melding:** "Kun je dan ook eens zorgvuldig bestuderen of er nog
winst te behalen is in het batterijgebruik van FclGlucoLink. Ik bemerk
een beduidend sneller leeg lopen van de batterij en de telefoon geeft
ook aan dat FCLGlucoLink een hoog batterijverbruik heeft."

**Analyse.** Verse dag-log (`fclglucolink_2026-08-11.txt`, 00:00–17:43)
bevestigt eerst dat Ronde 85/86 goed werken: CareSens gemiddeld 298,9s,
Dexcom gemiddeld 302,9s per cyclus over 17,5 uur, met lage GATT-
foutratio (9 status=133 op ~420 verbindpogingen samen) — geen
regressies, geen actie nodig op de cadans zelf.

**Root cause batterij.** `BleConnectionService.kt` hield sinds
30/07/2026 een `PARTIAL_WAKE_LOCK` de VOLLEDIGE service-levensduur vast
(`acquire(20 dagen)`, pas losgelaten in `onDestroy()`). Die kwam er
destijds terecht omdat een kale coroutine-`delay()` in de simulator-
afspeellus te laat afvuurde zodra de CPU tijdens Doze in slaap viel.
Sinds Ronde 36 (04/08/2026) loopt de daadwerkelijke herverbind-timing
niet meer via zo'n kale `delay()`, maar via `PredictiveReconnectAlarm`
(`AlarmManager.setExactAndAllowWhileIdle()`) — dat wekt de CPU zelf op
het exacte moment, Doze of niet. De permanente wakelock was dus sinds
Ronde 36 functioneel overbodig geworden, maar nooit teruggedraaid.
Log-analyse laat zien dat van elke ~300s-cyclus maar ~10-15s
daadwerkelijk actief BLE-werk is — de overige ~95-97% hield de wakelock
de CPU dus voortdurend wakker zonder enig operationeel nut. Een
permanente `PARTIAL_WAKE_LOCK` schakelt Doze/App Standby voor de volle
looptijd van de service uit, ongeacht daadwerkelijke activiteit — dat
verklaart hoog batterijverbruik rechtstreeks.

**Fix.** Nieuwe gedeelde `ActiveWorkWakeLock`
(`sensor/ble/ActiveWorkWakeLock.kt`), gedeeld tussen beide sloten
(net als `ScanRateLimiter`/`PredictiveReconnectAlarm`). Geen wakelock
meer tijdens de wachtperiode tussen cycli; pas kort vastgehouden
(`keepAwake()`, 180 000ms, zelf-verlopend, geen losse `release()` nodig)
vlak vóór het daadwerkelijke scanwerk in beide drivers'
`scheduleScanAttempt()`, ruim boven het gedocumenteerde worst-case
scanpad (Ronde 34/35: tot 117s dispatch-vertraging in diepe Doze).
`BleConnectionService.onCreate()` roept nu `ActiveWorkWakeLock.ensure()`
aan (maakt de onderliggende `WakeLock` eenmalig aan, zonder 'm vast te
houden); `onDestroy()` roept `ActiveWorkWakeLock.releaseAll()` aan als
vangnet. `ConnectionWatchdog.kt` en `PredictiveReconnectAlarm` zelf
blijven ongewijzigd — die waren al AlarmManager-gebaseerd en nooit van
deze wakelock afhankelijk.

Bewust behoudend: de wakelock is verkleind, niet volledig verwijderd,
gegeven de uitgebreide eerdere geschiedenis (Ronde 20-38) van
hard-bevochten screen-off/Doze-BLE-betrouwbaarheidsfixes. Aanbevolen: de
komende dag of twee in de gaten houden of de oude meerurige-
reconnect-gap-bug (waarvoor de permanente wakelock oorspronkelijk kwam)
niet terugkeert.

Twee secundaire, kleinere batterijfactoren gesignaleerd maar bewust NIET
aangepast dit keer: `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`
in `CareSensAirDriver.kt` (aanraden ongewijzigd te laten, betrouwbaar-
heidsrisico) en `DiagnosticFileLogger`'s ongebufferde per-regel
bestands-I/O (opt-in, staat standaard uit — de moeite van het checken
waard als langdurig aan gelaten).

**Verificatie:** balance-checker op alle 4 gewijzigde/nieuwe bestanden;
grep-controle op stale verwijzingen naar het oude `wakeLock`-veld in
`BleConnectionService.kt` (geen gevonden) en op de nieuwe
`ActiveWorkWakeLock`-import/-aanroep in beide drivers (aanwezig in
beide).

Gewijzigd: `sensor/ble/ActiveWorkWakeLock.kt` (nieuw),
`sensor/ble/BleConnectionService.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `app/build.gradle.kts`.

versionCode 95, versionName `0.9.0-battery-wakelock-scope`.

## Ronde 90 (11/08/2026) — notificatiefix, combi-grafiek leesbaarheid, gedeelde vingerprik-database

Drie losse verzoeken in één ronde, na live-gebruik van v95.

**1) Fix: Dexcom G6 toonde "Error" in de systeemmelding terwijl er niets mis
was.** `DexcomG6Driver.updateConnectionStatusAfterDisconnect()` zette bij
ELKE `STATE_DISCONNECTED` (dus ook de heel normale, verwachte disconnect ná
elke geslaagde ~5-minuten-meting) meteen `ConnectionState.Error`, ongeacht
hoe kort geleden de laatste meting was. `BleConnectionService`'s
systeemmelding toont de laatste BG-waarde alleen zolang de status
`Connected` blijft — dus deze driver liet structureel "Error: No connection
for 0m."/"...4m." zien. CareSens Air had dit al niet (ronde 33: blijft
`Connected` staan tijdens een routinematige herverbinding, pas ná
`RECONNECT_STATUS_WARNING_MINUTES` (7 min) een echte foutmelding) — die
logica nu ook overgenomen in `DexcomG6Driver.kt`.

**2) Combi-grafiek: betere zichtbaarheid als beide curven nagenoeg
samenvallen.** Gevraagd om Slot B met driehoekjes i.p.v. rondjes te tonen —
MPAndroidChart's `LineChart` kan echter geen echte driehoekjes per punt
tekenen (dat vereist een `CombinedChart` met een aparte `ScatterDataSet`,
een grotere, risicovollere wijziging die hier niet zonder een echte
Android-build te verifiëren was). In plaats daarvan: Slot A behoudt de
normale massief-gevulde stip + doorgetrokken lijn; Slot B krijgt HOLLE
(ring-)stippen + een gestippelde lijn. Dat blijft altijd zichtbaar, ook
wanneer Slot A's massieve stip/lijn er precies overheen valt — de holle
ring laat Slot A's kleur er middenin doorheen zien, en de "gaten" in de
gestippelde lijn laten Slot A's lijn gewoon zien.

**3) Gedeelde vingerprik-database tussen beide slots.** Voorheen was elke
vingerprik impliciet eigendom van precies één sensor(type) — nu is elke
vingerprik één GEDEELDE rij, zichtbaar bij beide slots, met een los
aan/uitvinkje per sensor (`includedForOriginSensor`/`includedForOtherSensor`
op `CalibrationEntryEntity`, migratie 5->6). Bij het invoeren wordt
opportunistisch ook de op dat moment gelijktijdig actieve ANDERE slot's
ruwe sensorwaarde meegevangen (`otherSensorType`/`otherSensorMgdlAtPairing`)
— zonder die vastgelegde waarde kan die andere sensor de vingerprik nooit
gebruiken, ongeacht een eventueel aanvinkje. De herkomst-sensor staat
automatisch aangevinkt, de andere sensor komt wel in de lijst maar
standaard uitgevinkt (`CalibrationScreen.kt`'s rijlijst kreeg een
checkbox). Verwijderen (prullenbak) haalt de rij nu ALTIJD overal weg in
één moeite — er is nog maar één rij per vingerprik, dus "verwijderen" en
"overal weg" zijn automatisch hetzelfde.

Bijkomend gevolg: `calibrationStore.clearAll(sensorType)`, die voorheen bij
elke nieuwe fysieke sensor-sessie de kalibratiedata van het vorige
sensortype volledig wiste, wordt niet meer automatisch aangeroepen — met
een gedeelde rij zou dat een vingerprik kunnen wegvegen die de ANDERE,
gelijktijdig actieve slot nog gebruikt. Vervangen door een pure tijdfilter
aan de leeskant: `CalibrationScreen.kt`/`BleConnectionService.kt` filteren
nu op `timestampMs >= sensorStartedAtMs` van de HUIDIGE sessie — precies
zoals gevraagd ("bij de sensoren moeten alleen die vingerprikken getoond
worden die kwa tijd na de sensor start liggen"). De methode zelf blijft
beschikbaar voor een eventuele toekomstige "wis echt alles"-noodknop.

Als laatste: de aangevinkte vingerprikken van beide slots verschijnen nu
ook als losse markers (witte ring met donkere kern, neutrale kleur — geen
eigendom van één sensor) op de Combi-tab's grafiek, op hun eigen
(tijdstip, vingerprikwaarde)-positie.

**Verificatie:** balance-checker op alle 11 gewijzigde/nieuwe bestanden;
grep-controle op alle call-sites van `calibrationStore.entries()`/
`entriesOnce()`/`.add()` (3 gevonden, alle drie meegenomen: `CalibrationScreen.kt`
×2, `BleConnectionService.kt`'s `applyCalibrationIfEnabled()`) en van
`DualGlucoseChart(` (1 aanroeper, `fingerstickPoints` heeft een default dus
geen bestaande aanroep breekt). Definitieve bevestiging van punt 1 en 3
vereist een paar dagen live-gebruik.

Gewijzigd: `sensor/dexcomg6/DexcomG6Driver.kt`, `ui/GlucoseChart.kt`,
`ui/CombiScreen.kt`, `ui/CalibrationScreen.kt`,
`calibration/CalibrationEntryEntity.kt`, `calibration/CalibrationEntryDao.kt`,
`calibration/CalibrationEntry.kt`, `calibration/CalibrationStore.kt`,
`data/FclGlucoLinkDatabase.kt`, `data/AppSettings.kt`,
`sensor/ble/BleConnectionService.kt`, `app/build.gradle.kts`.

versionCode 96, versionName `0.9.1-notif-chart-calibration-share`.

## Ronde 91 (11/08/2026) — CRITICAL fix: v96 crashte direct bij opstarten (ontbrekende Room-migratie)

**Live-crashlog, direct na installeren van v96:** `IllegalStateException: A
migration from 5 to 6 was required but not found.` — app sloot zich
onmiddellijk af bij het eerste scherm.

**Root cause.** Ronde 90 verhoogde `@Database(version = ...)` naar 6 en
definieerde `MIGRATION_5_6` (nieuwe kolommen op `calibration_entries`, zie
Ronde 90's kdoc), maar de daadwerkelijke `.addMigrations(...)`-aanroep in
`Room.databaseBuilder()` werd niet bijgewerkt — die riep nog steeds alleen
`MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5` aan.
Room ziet dan een database die al op versie 5 staat, een class-definitie
die versie 6 verwacht, en geen geregistreerd migratiepad ertussen — vandaar
de harde crash bij de eerste databasetoegang (i.p.v. bijvoorbeeld een
stille schema-mismatch).

**Fix.** `MIGRATION_5_6` toegevoegd aan de `.addMigrations(...)`-lijst.
Puur een registratie-omissie — de migratie zelf (`ALTER TABLE`-statements)
was al correct.

**Verificatie:** balance-checker; grep-controle dat alle 6 migraties
(`MIGRATION_1_2` t/m `MIGRATION_5_6`) nu in de `.addMigrations(...)`-lijst
staan.

Gewijzigd: `data/FclGlucoLinkDatabase.kt`, `app/build.gradle.kts`.

versionCode 97, versionName `0.9.2-migration-crash-fix`.

## Ronde 92 (11/08/2026) — fix: fingerstick-markers op de combi-grafiek waren met een lijn verbonden

**Live-melding met screenshot:** "De vingerprik waarden moeten niet met een
lijn worden verbonden maar alleen als dot worden getoond." Screenshot
bevestigde een dun lijntje tussen de witte ring-stippen op de Combi-tab.

**Root cause.** Ronde 90's fingerstick-`LineDataSet` zette `lineWidth = 0f`
in de veronderstelling dat dit de verbindingslijn zou onderdrukken. Android's
`Paint.setStrokeWidth(0)` is echter speciaal behandeld als "hairline" — een
altijd-1-pixel-brede lijn, ongeacht de ingestelde breedte — dus `lineWidth
= 0f` betekent in de praktijk NIET "geen lijn", maar juist een dunne,
altijd zichtbare lijn.

**Fix.** De lijnkleur zelf volledig transparant gemaakt
(`setColor(Color.TRANSPARENT)`) i.p.v. op de breedte te vertrouwen — dat
maakt de lijn ONZICHTBAAR ongeacht de (hairline-)breedte, terwijl de losse
cirkel-stippen (die hun eigen kleur/straal via `setCircleColor()`/
`circleRadius` hebben, los van `setColor()`) gewoon zichtbaar blijven.

**Verificatie:** balance-checker; geïsoleerde wijziging in dezelfde
`apply`-blok als Ronde 90, geen andere call-sites.

Gewijzigd: `ui/GlucoseChart.kt`, `app/build.gradle.kts`.

versionCode 98, versionName `0.9.3-fingerstick-hairline-fix`.

## Ronde 93 (11/08/2026) — diagnostiek: nieuw kalibratiepunt verschijnt niet op de combi-grafiek

**Live-melding:** "De lijn is nu weg, maar ik heb een kalibratiepunt
toegevoegd, dit wordt echt niet ingebracht en getoond op de combi
grafiek." Een nieuwe, correct opgeslagen en aangevinkte vingerprik
(zichtbaar in CalibrationScreen.kt's eigen lijst) verschijnt niet als
marker op de Combi-tab's grafiek.

**Status: nog niet opgelost.** Uitgebreide statische code-review van
`CalibrationStore.kt`, `CombiScreen.kt`, `GlucoseChart.kt` en
`AppSettings.kt` leverde geen concrete, aanwijsbare bug op — exact
dezelfde `CalibrationStore.entries(sensorType, sinceMs)`-functie die
CalibrationScreen.kt's eigen lijst/grafiek correct vult voor deze exacte
entry, wordt met equivalente parameters aangeroepen vanuit
CombiScreen.kt om `fingerstickPoints` op te bouwen.

**Deze build is puur diagnostisch — geen gedragswijziging.** Twee
tijdelijke `Log.d("FCLFingerstickDebug", ...)`-regels toegevoegd om te
onderscheiden of het probleem in de data-fetch-laag zit (fingersticksA/B
leeg) of in de tekenlaag (fingerstickPoints niet-leeg, maar niet op de
grafiek getekend):

1. In `CombiScreen.kt`, direct na de `fingerstickPoints`-berekening: logt
   `selectedSensorA/B`, `sinceMsA/B`, `fingersticksA/B`-aantal en de
   uiteindelijke `fingerstickPoints`-lijst.
2. In `GlucoseChart.kt`'s `DualGlucoseChart`-`update`-lambda: logt het
   ontvangen `fingerstickPoints`-aantal, `baseTimestampMs`, het huidige
   X-as-venster (`axisMinimum`/`axisMaximum`) en de berekende
   `Entry`-coördinaten (`x`,`y`) — zodat ook zichtbaar wordt of de punten
   simpelweg buiten het zichtbare grafiekvenster vallen.

**Volgende stap:** een kalibratiepunt toevoegen, de Combi-tab openen, en
de `FCLFingerstickDebug`-logcat-regels delen zodat het exacte faalpunt
gevonden en in een volgende ronde opgelost kan worden.

Gewijzigd: `ui/CombiScreen.kt`, `ui/GlucoseChart.kt`, `app/build.gradle.kts`.

versionCode 99, versionName `0.9.4-fingerstick-combi-diagnostic`.

## Ronde 94 (11/08/2026) — echte fix: kalibratiepunt viel buiten de as-grenzen van de combi-grafiek

**Diagnostiek geslaagd.** De Ronde-93-logging liet zien dat `fingerstickPoints`
op elk render-moment gewoon correct gevuld was (o.a. 13 punten, met echte
tijdstempels en mmol-waarden) — de fetch-keten was dus nooit het probleem.
De log toonde ook de exacte oorzaak: sommige `fingerstickEntries`-x-waarden
lagen VOOR `axisMinimum` (bv. -41, -19, -74 terwijl axisMinimum ~23 was) en
een y-waarde van 11,9 lag boven de toen berekende Y-as-bovengrens.
MPAndroidChart tekent principieel niets buiten de ingestelde as-grenzen,
ook al staat het punt keurig in de dataset — vandaar dat de vingerprik
volledig onzichtbaar bleef.

**Root cause.** `DualGlucoseChart`'s asberekening hield alleen rekening met
de twee sensor-curven ("slot-A"/"slot-B"):
- X-as: `axisMinimum`/`axisMaximum` werden alleen op `entriesA`/`entriesB`
  gebaseerd, dus een kalibratie van vóór het huidige sensor-curve-venster
  viel links buiten de as.
- Y-as: `recomputeYAxisMax()` kreeg via `DUAL_CHART_Y_AXIS_LABELS` alleen de
  labels "slot-A"/"slot-B" mee, dus een vingerprik die (zoals normaal bij
  een kalibratie) HOGER lag dan de sensorwaarde, viel boven de as uit.

**Fix.**
- X-as: de linker-asgrens rekent nu ook de fingerstick-tijdstempels mee
  (`earliestX = min(earliestGlucoseX, fingerstickEntries.minOfOrNull{it.x})`).
- Y-as: `DUAL_CHART_Y_AXIS_LABELS` uitgebreid met `"fingersticks"`, zodat
  `recomputeYAxisMax()` de as ook omhoog uitrekt voor een kalibratiepunt
  boven de sensor-curve.
- De tijdelijke Ronde-93 `Log.d("FCLFingerstickDebug", ...)`-regels (in
  zowel `CombiScreen.kt` als `GlucoseChart.kt`) weer verwijderd, geen
  gedragswijziging daar.

**Verificatie:** balance-checker; herleid rechtstreeks uit de door de
gebruiker gedeelde logcat (geen giswerk) — de gerapporteerde x/y-waarden in
die logcat vielen exact buiten de toen geldende as-grenzen.

Gewijzigd: `ui/GlucoseChart.kt`, `ui/CombiScreen.kt`, `app/build.gradle.kts`.

versionCode 100, versionName `0.9.5-fingerstick-combi-axis-fix`.

## Ronde 95 (11/08/2026) — fix: kalibratiepunten los van de curve ("lijkt wel gisteren") + combi-venster 24u -> 48u

**Live-melding met 2 screenshots:** een nieuw kalibratiepunt was nu wel
zichtbaar (Ronde 94 werkte in die zin), maar stond als losse, contextloze
stip links van de curve, met een duidelijk gat ertussen — "de nieuwe punten
worden links op de grafiek getoond (dus eigenlijk gisteren)." De gebruiker
vermoedde terecht twee dingen: een datum/tijd-kwestie, en dat het
combi-venster 48 uur zou moeten zijn i.p.v. 24 uur.

**Root cause 1 — Ronde 94's eigen X-as-fix bleek te grof.**
`CalibrationStore.entries()` filtert alleen op "sinds sensor-start", niet op
leeftijd — bij een lang lopende sensor (CareSens Air ~14 dagen, Dexcom G6
~10 dagen) omvat dat dus ook kalibraties van dagen terug. Ronde 94 liet
ELKE fingerstick, hoe oud ook, de linker-asgrens verder naar links
uitrekken. Een oud punt trok de as zo ver open dat de echte, recente
curve-data samengeperst raakte in een klein stukje rechts, met het oude
punt als geïsoleerde stip ver links — precies het gemelde effect.

**Root cause 2 — bevestigd.** `CombiScreen.kt` haalde de sensor-curves op
met `store.recentReadings(hours = 24, ...)`, terwijl `StatusScreen.kt` (de
losse per-sensor schermen) en `GlucoseReadingStore.kt`'s eigen default al
langer `hours = 48` gebruiken. Gelijkgetrokken naar 48.

**Fix.**
- De linker-asgrens-verbreding uit Ronde 94 teruggedraaid: die is nu weer
  puur gebaseerd op de sensor-curves zelf (`earliestGlucoseX`), zoals vóór
  Ronde 94. Een kalibratiepunt van vóór het zichtbare curve-venster heeft
  daar toch geen BG-referentie naast staan, dus wordt 'm nu weer simpelweg
  niet getekend (geclipt aan de as) i.p.v. de hele grafiek open te trekken.
- De rechter-asgrens rekt wél nog mee met een fingerstick die net ná de
  laatst opgehaalde sensormeting valt (het normale geval vlak na het
  invoeren van een verse kalibratie) — grenst direct aan bestaande data,
  geen kunstmatig gat.
- De Y-as-fix uit Ronde 94 (`DUAL_CHART_Y_AXIS_LABELS` met `"fingersticks"`)
  blijft ongewijzigd staan, was niet de oorzaak van dit probleem.
- `CombiScreen.kt`: `hours = 24` -> `hours = 48` voor beide slots.

**Verificatie:** balance-checker; het scenario in de screenshots (twee
losse stippen ver links van een curve die pas later begint) matcht exact
"een oude kalibratie trekt de as open" — met de as weer strak om de
curve-data zelf, kunnen alleen kalibraties BINNEN het (nu 48u brede)
curve-venster nog verschijnen, direct naast de bijbehorende BG-punten.

Gewijzigd: `ui/GlucoseChart.kt`, `ui/CombiScreen.kt`, `app/build.gradle.kts`.

versionCode 101, versionName `0.9.6-fingerstick-orphan-fix-48h`.

## Ronde 96 (11/08/2026) — CRITICAL crash-fix: app crasht bij het pannen door de combi-grafiek

**Live-melding met crashlog:** de app crashte na een paar seconden pannen
(swipen) door de combi-grafiek, met:
```
java.lang.NegativeArraySizeException: -16
	at com.github.mikephil.charting.utils.Transformer.generateTransformedValuesLine
	at com.github.mikephil.charting.renderer.LineChartRenderer.drawValues
```

**Root cause.** MPAndroidChart's `LineChartRenderer.drawValues()` roept
`generateTransformedValuesLine()` aan voor elk dataset waarvoor
`isDrawValuesEnabled() || isDrawIconsEnabled()` true is. Dit hele bestand
zet overal netjes `setDrawValues(false)` — maar `isDrawIconsEnabled()`
staat in MPAndroidChart's `DataSet`-basisklasse standáárd op TRUE, en werd
nergens expliciet uitgezet. Het gevolg: die aanroep vond dus altijd al
plaats, voor ALLE datasets (band/slot-A/slot-B/fingersticks/BG/
raw-sensor) — puur toeval dat dit niet eerder crashte.
`generateTransformedValuesLine()` berekent intern een teken-bereik via een
binary-search-achtige opzoeking (`mXBounds`) op het huidige zichtbare
X-as-venster; bij een dataset met maar een handvol punten die ver van het
huidige panvenster af komen te liggen (typisch bij het pannen ver terug in
de tijd, met de sparse "fingersticks"-dataset of de brede-maar-dunne
"target-range"-band), kan die opzoeking een ongeldig (negatief) bereik
opleveren — vandaar `new float[-16]`.

**Fix.** `setDrawIcons(false)` toegevoegd aan ELKE `LineDataSet` in
`GlucoseChart.kt` (zowel de losse per-sensor grafiek als de combi-grafiek:
band/BG/raw-sensor/slot-A/slot-B/fingersticks — 7 datasets in totaal), zodat
`shouldDrawValues()` nu overal daadwerkelijk false teruggeeft en
`generateTransformedValuesLine()` helemaal niet meer aangeroepen wordt. De
app gebruikt sowieso nergens on-chart waarde-labels of icons, dus dit heeft
geen enkel visueel effect — puur de crash-trigger weggenomen.

**Verificatie:** balance-checker; grep bevestigt alle 7 datasets nu zowel
`setDrawValues(false)` als `setDrawIcons(false)` hebben.

Gewijzigd: `ui/GlucoseChart.kt`, `app/build.gradle.kts`.

versionCode 102, versionName `0.9.7-chart-pan-crash-fix`.

## Ronde 97 (12/08/2026) — diagnostiek: nieuw ingevoerde kalibraties verschijnen niet meer live op de combi-grafiek

**Live-melding met 3 screenshots:** een kalibratie ingevoerd bij zowel de
Dexcom G6 (06:18) als de CareSens Air verschijnt geen van beide op de
combi-grafiek. Belangrijk verschil met de vorige rondes: "Toen de laatste
fix van gister werd geïnstalleerd verschenen gelijk bij de eerste opstart
de toen reeds ingevoerde calibraties. Maar nu na invoeren verschijnen ze
niet meer" — d.w.z. bij een verse app-start werkt het (bestaande data komt
gewoon binnen), maar een LIVE toevoeging tijdens het draaien wordt niet
meer opgepikt.

**Status: nog niet opgelost.** Uitgebreide code-review van
`CalibrationStore.kt`'s `entries()` (een Room-`Flow` die normaal automatisch
opnieuw zou moeten vuren na een `insert()`, via Room's eigen
InvalidationTracker — geen custom code nodig) en `CombiScreen.kt`'s
`remember`/`collectAsState`-keten leverde ditmaal geen aanwijsbare bug op.
De gebruiker merkte ook op dat bij de Dexcom G6 geen sensor-Started-datum
geregistreerd staat (die transmitter liep al vóór de installatie van de
dual-sensor-versie) — dat is echter een ANDER veld
(`dexcomG6SessionStartConfirmedAtMs`, puur transmitter-sessie-bookkeeping)
dan het veld dat de combi-grafiek's tijdfilter bepaalt
(`sensor_started_at_ms`, generiek per slot, lazy geïnitialiseerd bij de
eerste `connect()` onder deze app-versie) — waarschijnlijk niet dezelfde
oorzaak, maar nog niet met zekerheid uit te sluiten.

**Deze build is puur diagnostisch — geen gedragswijziging.** Twee tijdelijke
`Log.d("FCLFingerstickDebug", ...)`-regels toegevoegd om precies te
lokaliseren OF Room's Flow na een `insert()` opnieuw vuurt, of dat de
update wél bij Room binnenkomt maar ergens tussen `CombiScreen.kt` en de
grafiek blijft steken:

1. `CalibrationStore.kt`'s `entries()` — logt bij ELKE her-emissie van de
   onderliggende Room-`Flow` (dus zowel bij de eerste collectie als bij elke
   volgende DB-wijziging) het sensorType, sinceMs, en de ruwe rij-ids.
2. `CombiScreen.kt` — een `LaunchedEffect(fingersticksA, fingersticksB)` die
   logt zodra de Compose-state daadwerkelijk verandert, met de opgehaalde
   entry-ids per slot en het resulterende `fingerstickPoints`-aantal.

**Volgende stap:** met deze build draaiend, blijf op de Combi-tab (of
navigeer er expliciet weer naartoe) na het invoeren van een nieuwe
vingerprik, en deel de `FCLFingerstickDebug`-logcat-regels. Verschijnt
regel 1 (CalibrationStore) opnieuw na de invoer maar regel 2 (CombiScreen)
niet (of met verouderde data) — dan zit het probleem in de Compose-laag.
Verschijnt regel 1 zelf al niet opnieuw — dan zit het in Room's
Flow-reactiviteit of ligt de invoer ergens anders vast dan verwacht.

Gewijzigd: `calibration/CalibrationStore.kt`, `ui/CombiScreen.kt`,
`app/build.gradle.kts`.

versionCode 103, versionName `0.9.8-live-fingerstick-diagnostic`.

## Ronde 98 (12/08/2026) — diagnostiek verlengd: asymmetrie Slot A (blijft weg) vs Slot B (verschijnt wel)

**Live-melding met logcat.** De Ronde-97-diagnostiek gaf al een deel van
het antwoord: een nieuw ingevoerde vingerprik bij Slot A (CareSens)
verscheen wél netjes en tijdig in `CombiScreen.kt`'s `fingerstickPoints`
(bijv. `fingerstickPoints=20` met de net toegevoegde id 61 erin) — de
fetch-/Compose-keten is dus AANTOONBAAR niet het probleem. Toch meldt de
gebruiker: bij invoeren op Slot A verschijnt de stip niet op de
combi-grafiek, bij Slot B (Dexcom) wél.

**Status: nog niet opgelost.** Omdat de data de tekenlaag (`GlucoseChart.kt`
's `DualGlucoseChart`) aantoonbaar wél correct bereikt, moet het verschil
in de as-berekening of het tekenen zelf zitten — maar zonder tekenlaag-
diagnostiek was dat tot nu toe niet zichtbaar in de logcat.

**Deze build is puur diagnostisch — geen gedragswijziging.** Eén regel
toegevoegd in `DualGlucoseChart`'s `update`-blok, na het vaststellen van
`axisMinimum`/`axisMaximum`: logt de tijdrange van `entriesA` en `entriesB`
apart (de twee sensor-curven), de uiteindelijke as-grenzen, en de exacte
berekende coördinaten van elk fingerstick-punt — zodat zichtbaar wordt of
Slot A's vingerprik buiten het venster valt terwijl Slot B's punt erbinnen
valt (of course een andere, nu wel zichtbare asymmetrie).

**Volgende stap:** dezelfde test herhalen (invoeren bij Slot A, dan bij
Slot B) en de volledige `FCLFingerstickDebug`-logcat delen, inclusief nu
ook de `DualGlucoseChart:`-regels.

Gewijzigd: `ui/GlucoseChart.kt`, `app/build.gradle.kts`.

versionCode 104, versionName `0.9.9-slot-asymmetry-diagnostic`.

## Ronde 99 (12/08/2026) — ECHTE fix: fingerstick-markers moeten x-gesorteerd zijn (MPAndroidChart-euvel)

**Live-melding met logcat.** De Ronde-97/98-diagnostiek gaf het laatste
puzzelstukje: een net ingevoerde CareSens-vingerprik zat aantoonbaar altijd
al correct in zowel `fingerstickPoints` (Compose) als `fingerstickEntries`
(de grafiek-coördinaten) — de data was dus nooit het probleem. Toch bleef
'm soms onzichtbaar, en het patroon (Dexcom-punten verschijnen betrouwbaar,
CareSens-punten wisselvallig, en een punt dat WEL zichtbaar was verdween
zodra het ook voor de andere sensor werd aangevinkt) wees op iets in de
tekenlaag zelf.

**Root cause.** MPAndroidChart vereist dat een `LineDataSet`'s punten
oplopend op x-waarde gesorteerd zijn — de renderer gebruikt intern een
binary-search-achtige opzoeking (`mXBounds`, dezelfde die de Ronde-96-crash
veroorzaakte) om te bepalen welke punten binnen het zichtbare venster
vallen. Bij een NIET-gesorteerde dataset kan die opzoeking simpelweg het
verkeerde antwoord geven — een punt kan dan onterecht als "buiten beeld"
worden behandeld en niet getekend worden, ook al staat het gewoon in de
dataset én binnen de as-grenzen.

`CombiScreen.kt`'s `fingerstickPoints = (fingersticksA + fingersticksB)
.distinctBy { it.id }.map { ... }` plakte simpelweg ALLE punten van slot A
vóór ALLE punten van slot B. Omdat beide slots dezelfde tijdsperiode
bestrijken (dezelfde 48 uur), is die concatenatie zo goed als nooit
chronologisch gesorteerd — ondanks dat `fingersticksA` en `fingersticksB`
elk afzonderlijk waarschijnlijk wél op tijd gesorteerd binnenkwamen.
Precies dit verklaart de wisselvalligheid: welk punt wel/niet getekend
werd, hing af van waar het toevallig in die ongesorteerde lijst terecht
kwam — en verklaart ook waarom het aanvinken van een bestaand (zichtbaar)
punt voor de andere sensor het soms deed VERDWIJNEN: de volgorde van de
samengevoegde lijst verschuift dan mee.

**Fix.** `.sortedBy { it.timestampMs }` toegevoegd na het samenvoegen/
ontdubbelen in `CombiScreen.kt`, plus defensief ook `.sortedBy { it.first }`
in `GlucoseChart.kt`'s eigen `fingerstickEntries`-opbouw (zodat deze functie
niet stilzwijgend op een gesorteerde caller hoeft te vertrouwen).

**Diagnostiek opgeruimd.** Alle tijdelijke `Log.d("FCLFingerstickDebug",
...)`-regels uit Ronde 93/97/98 zijn nu verwijderd (`CalibrationStore.kt`,
`CombiScreen.kt`, `GlucoseChart.kt`) — geen gedragswijziging daar verder.

**Verificatie:** balance-checker; de root cause is een gedocumenteerd
MPAndroidChart-vereiste (gesorteerde entries), rechtstreeks passend bij het
in de logcat waargenomen "data klopt, tekenen soms niet"-patroon.

Gewijzigd: `ui/CombiScreen.kt`, `ui/GlucoseChart.kt`,
`calibration/CalibrationStore.kt`, `app/build.gradle.kts`.

versionCode 105, versionName `0.9.10-fingerstick-sort-fix`.

## Ronde 100 (12/08/2026) — betrouwbaarheidsanalyse + fix: AAPS-slot krijgt altijd voorrang bij een scanbotsing, CareSens-marge getuned

**Live-melding.** "Volgens mij is gisteravond relatief laat een aanpassing
gedaan om de batterij te helpen besparen [Ronde 89]. Ik heb hierbij de log
vanaf 00:00 vandaag tot nu. Kun je die nu eens analyseren op de 5 minuten
betrouwbaarheid tussen 2 meetpunten steeds en zodra er afwijkingen zijn
kijken of daar nog wat aan te doen is en tevens kijken of dit nu batterij
technisch het optimaal is ingesteld zonder sensor uitval te krijgen."

**Analyse (`fclglucolink_2026-08-12.txt`, 00:00-14:31).** Dexcom G6: 170
metingen, mediaan-interval exact 300s, 97,7% van het verwachte aantal
binnen. 4 misten cycli (07:38, 11:33, 11:43, 13:03), alle 4 een dubbele
600s-gap die begint binnen ~1-60s na een CareSens-scanpoging — een
scanbotsing die Ronde 85/86 fors verkleinden maar niet volledig
elimineerden. Niet gerelateerd aan Ronde 89 (Dexcom draait op hetzelfde
wakelock-schema en blijft hier betrouwbaar). CareSens Air: gemiddelde
cadans klopt (~300-302s), maar met een steeds grover wordend afwisselend
patroon (240s/360s -> 180s/420s) plus 4 losse `newRecords=0`-pogingen. Dit
bleek een BEKEND, in Ronde 31/39 al eerder getunede patroon: de scan start
`SCAN_START_MARGIN_MS` (60s sinds Ronde 86) vóór de voorspelde metingstijd,
maar de duty-cycle-zoektijd is intussen maar ~26-30s (Ronde 37) — dus checkt
de app newRecords regelmatig ~30s te vroeg, krijgt 0, valt terug op de 60s-
cooldown en raakt pas de volgende cyclus. Geen echt dataverlies, wel nutteloos
extra BLE-verkeer.

**Fix 1 — CareSens `SCAN_START_MARGIN_MS`: 60s -> 30s.** Dichter bij de
beproefde Ronde-39-waarde, nu Ronde 86's oorspronkelijke tweede doel voor
deze marge (een generieke botsingsbuffer) gerichter wordt afgehandeld door
Fix 2 hieronder.

**Fix 2 — nieuw `sensor/ble/AapsSlotSchedule.kt`, op expliciet verzoek: "het
slot wat naar aaps zend ... altijd de voorkeur heeft en als dat tot gevolg
heeft dat het andere slot zo nu en dan een meting mist dan is dat maar zo.
Uiteraard moet als er maar 1 slot actief is dat ene slot ook streven naar
100% betrouwbaarheid."** Ronde 83's `ScanRateLimiter`-voorrang loste alleen
het gedeelde 5-scans-per-31s-budget op; de 4 Dexcom-missers in deze log
vielen daar niet onder (budget zat nooit vol op die momenten) — de botsing
zit dieper, op het niveau van de daadwerkelijke BLE-scanpoging zelf. Elke
driver publiceert nu bij elke `computeReconnectCooldownMs()`-berekening
onvoorwaardelijk zijn eigen voorspelde volgende-metingstijd
(`AapsSlotSchedule.publish()`). Vlak vóór een eigen scanpoging checkt de
NIET-AAPS-slot (`!isPriority`, ná Ronde 83's bestaande lezing van
`settings.aapsActiveSlot`) via `AapsSlotSchedule.guardDelayMs()` of die
voorspelling van de ANDERE (dus per definitie de AAPS-)slot nu binnen een
beschermd venster (±45s) ligt, en wijkt zo nodig uit tot erna. De AAPS-slot
zelf roept dit nooit op — wacht dus nooit op de ander. Bij maar 1 actieve
slot publiceert de andere driver nooit (of allang verlopen), dus levert dit
altijd 0 op: geen enkele extra wachttijd, zoals gevraagd.

**Verificatie:** balance-checker op alle 3 gewijzigde/nieuwe bestanden;
grep-controle op de nieuwe `AapsSlotSchedule`-aanroepen in beide drivers
(publish in `computeReconnectCooldownMs()`, guard-check in
`scheduleScanAttempt()`, symmetrisch in beide, alleen toegepast in de
niet-priority-tak).

Gewijzigd: `sensor/ble/AapsSlotSchedule.kt` (nieuw),
`sensor/caresensair/CareSensAirDriver.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`app/build.gradle.kts`.

versionCode 106, versionName `0.9.11-aaps-slot-scan-priority`.

## Ronde 101 (13/08/2026) — bugfix Ronde 100 + proactieve fix: CareSens Air wijkt zelf al vooraf van Dexcom's raster, i.p.v. pas reactief bij een botsing

**Live-melding, na een verse log met v106 al actief.** "Kun je de laatste
data weer eens analyseren op uitval. Wat mij opvalt is dat de dexcom (die de
waarden naar aaps stuurt) lijkt uit te vallen af en toe en dat het dan
visueel lijkt samen te vallen met een update van de caresens. [...] is het
dan geen optie om voor die gevallen waar de care sens en dexcom beiden
draaien de timing van de care sens zo te verschuiven dat hij of minimaal 1
minuut voor of na de door de transmitter bepaalde update van de dexcom valt
zodat ze elkaar dus nooit in de weg kunnen zitten. Als ik je goed heb
begrepen kan de caresens worden uitgevraagd wanneer je dat wilt en is de
dexcom alleen aan het zenden als de transmitter zich zelf opent."

**Analyse (`fclglucolink_2026-08-13 08.30.txt`, 00:00-08:30, v106).** Dexcom
G6: 97 metingen, 5 missers in slechts 8,5 uur (05:58, 06:48, 07:08, 07:18,
07:48) — allemaal geclusterd in een venster van ~2 uur, daarvoor (00:03-05:58,
6 uur) geen enkele misser. Rechtstreekse `Scan-record voor`-vergelijking:
elke misser valt samen met een CareSens-scan die nog geen 5-25 SECONDEN vóór
Dexcom's verwachte metingstijd start — een opvallend strak, herhaald
patroon, niet toevallige jitter.

**Bug gevonden in Ronde 100's eigen fix.** 0 "wijk ... uit"-logregels in de
hele log, terwijl er wél 5 botsingen waren — het vangnet vuurde dus geen
enkele keer. Oorzaak: `AapsSlotSchedule` hield [publishedSlot]/
[predictedReadingAtMs] als ÉÉN gedeeld, overschrijfbaar paar in plaats van
per slot. Omdat beide drivers onvoorwaardelijk publiceren, overschreef een
slot meestal zijn EIGEN vorige publicatie vlak voordat diezelfde slot zijn
eigen guard-check deed — de check zag dan zichzelf (`other == callerSlot`)
en leverde altijd 0 op. Fix: een map per slot (`ConcurrentHashMap<SensorSlot,
Long>`), zodat "de andere slot" bij 2 sloten altijd ondubbelzinnig is.

**Nieuw, proactief mechanisme — het eigenlijke gevraagde gedrag.** Naast de
(nu bug-fixed) reactieve guard uit Ronde 100 schuift `CareSensAirDriver.kt`'s
`computeReconnectCooldownMs()` zijn eigen scan-DOEL voortaan zelf al vooraf
weg als het binnen `AapsSlotSchedule.MIN_SEPARATION_MS` (60s, "minimaal 1
minuut") van de andere slot's rasterpunt zou vallen — nooit vroeger dan het
natuurlijke doel (zou Ronde 100's newRecords=0-tuning ondermijnen), altijd
naar `andereSlotRaster + 60s`, met een veiligheidscheck dat dit niet voorbij
de eigen volgende meting schuift (het apparaat bewaart de laatste meting tot
de volgende 'm overschrijft, dus een verschuiving van ~1-1,5 minuut binnen
een ~5-minuten-venster is ruim veilig). Bewust ALLEEN in
`CareSensAirDriver.kt`, niet in `DexcomG6Driver.kt` — precies de gebruiker's
eigen onderscheid: Dexcom's transmitter zendt op een tijdstip dat de app niet
kan sturen, CareSens Air kan wél op een zelfgekozen moment bevraagd worden.
Handmatig doorgerekend tegen de echte logcijfers van de 06:03-botsing
(CareSens' eigen rasterpunt lag toen 33s vóór Dexcom's rasterpunt): de fix
zou het scan-doel daar naar 60s ná Dexcom's rasterpunt hebben geschoven —
ruim binnen alle veiligheidsmarges.

**Verificatie:** balance-checker op alle 3 gewijzigde/nieuwe bestanden;
handmatige doorrekening van de fix tegen de echte `computeReconnectCooldownMs`-
logregels rond de 06:03-botsing (zowel CareSens' als Dexcom's kant) om te
bevestigen dat de nieuwe logica daadwerkelijk een veilige verschuiving
oplevert.

Gewijzigd: `sensor/ble/AapsSlotSchedule.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `app/build.gradle.kts`.

versionCode 107, versionName `0.9.12-caresens-proactive-yield`.

## Ronde 102 (13/08/2026) — fix: gestopte slot liet een spookraster achter voor de andere, nog draaiende slot

**Controlevraag van de gebruiker.** "Als alleen de caresens actief is dan
wordt die niet verschoven neem ik aan en blijft die gewoon netjes iedere 5
minuten een waarde produceren."

**Antwoord met een mits — bug gevonden bij het checken.** Klopt volledig
zolang de andere slot deze sessie NOOIT actief is geweest: `AapsSlotSchedule`
publiceert dan simpelweg niets voor die slot, dus `otherSlotPredictedReadingAtMs`
levert `null` en Ronde 101's proactieve verschuiving in
`CareSensAirDriver.kt` doet niets. Maar bij het narekenen bleek een gat voor
het praktisch waarschijnlijkere geval: als de gebruiker TUSSENTIJDS (zonder
de app te herstarten) van dual-slot terugschakelt naar alleen CareSens Air
actief, bleef Dexcom's LAATSTE gepubliceerde voorspelling gewoon in de
gedeelde map staan — niets ruimde 'm op. Ronde 100's reactieve
`guardDelayMs` is daar zelf ongevoelig voor (die vergelijkt tegen een
tijdvenster, dus een oude voorspelling valt vanzelf buiten bereik), maar
Ronde 101's proactieve rasterverschuiving snapt via modulo-rekenen naar het
DICHTSTBIJZIJNDE veelvoud van die voorspelling — een stokoud tijdstip van
een allang gestopte slot geeft daarbij nog steeds een geldig "huidig"
rasterpunt om omheen te blijven schuiven, voor onbepaalde tijd (tot een
app-herstart de in-memory singleton reset).

**Fix.** Nieuwe `AapsSlotSchedule.clear(slot)`, aangeroepen vanuit beide
drivers' `disconnect()` — specifiek het EXPLICIETE-stop-pad
(`userStopped = true`), niet de gewone tussentijdse reconnect-cyclus (die
blijft na een routinematige disconnect gewoon publiceren, zoals bedoeld).
Met deze fix klopt de controlevraag nu ook voor het "was actief, nu
gestopt"-geval: zodra de andere slot écht stopt, valt Ronde 101's
verschuiving voor de overblijvende slot per direct weg en produceert die
weer gewoon zijn eigen, ongeschoven ritme.

**Verificatie:** balance-checker op alle 3 gewijzigde bestanden; grep-
controle dat `AapsSlotSchedule.clear(slot)` in beide drivers' `disconnect()`
staat (en dus niet in de gewone per-cyclus disconnect-afhandeling, die een
andere functie is).

Gewijzigd: `sensor/ble/AapsSlotSchedule.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`app/build.gradle.kts`.

versionCode 108, versionName `0.9.13-aaps-slot-schedule-clear-on-stop`.

## Ronde 103 (13/08/2026) — fix: proactieve verschuiving volgt de AAPS-prioriteit i.p.v. altijd CareSens Air te laten wijken

**Controlevraag van de gebruiker.** "Dus als ik nu in mijn specifieke geval
de sensor van slot2 (huidig dexcom) op None of virueel zet dan draait de
caresens gewoon door zonder de blokkade [klopt, zie Ronde 102] en minstens
zo belangrijk als de caresens de aaps sensor wordt dan wordt het ook
uitgeschakeld en krijgt caresens wel altijd de voorrang (in dat laatste
geval is het namelijk niet belangrijk dat de dexcom zo nu en dan even een
cyclus overslaat want er wordt toch niet op gedoseerd)."

**Bug gevonden bij het checken.** Ronde 101's proactieve verschuiving in
`CareSensAirDriver.kt` was ONVOORWAARDELIJK — CareSens Air week altijd weg
van "de andere slot", ongeacht welke slot de AAPS-actieve is. Dat was correct
zolang Dexcom de AAPS-slot is (de huidige situatie), maar fout zodra de
gebruiker CareSens Air zelf als AAPS-slot instelt: dan zou CareSens Air voor
Dexcom blijven wijken terwijl dat averechts is — precies andersom van wat de
gebruiker vroeg.

**Fix.** Nieuwe `AapsSlotSchedule.isPrioritySlot(slot)`, gebaseerd op een
cache (`publishAapsActiveSlot`) die beide drivers al bij elke scanpoging vers
vullen (dezelfde `settings.aapsActiveSlot`-lezing die Ronde 83 al deed, hier
hergebruikt) — nodig omdat `computeReconnectCooldownMs()` zelf geen
suspend-functie is en op twee plekken wordt aangeroepen die dat ook niet zijn
(BLE-callback-methodes), dus geen verse Flow-lezing ter plekke kan doen.
CareSensAirDriver.kt slaat de hele proactieve verschuiving nu over zodra
`isPrioritySlot(slot)` waar is. Dexcom's REACTIEVE guard (Ronde 100/101,
`!isPriority`-tak) blijft ongewijzigd bestaan en gaat in dat geval juist wél
voor CareSens Air wijken — dus krijgt CareSens Air in die situatie zelfs
dubbele bescherming, terwijl Dexcom af en toe een cyclus mag overslaan
("er wordt toch niet op gedoseerd").

**Verificatie:** balance-checker op alle 3 gewijzigde bestanden; grep-
controle dat `publishAapsActiveSlot`/`isPrioritySlot` op de juiste, exact
symmetrische plekken in beide drivers staan.

Gewijzigd: `sensor/ble/AapsSlotSchedule.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`app/build.gradle.kts`.

versionCode 109, versionName `0.9.14-caresens-yield-follows-aaps-priority`.

## Ronde 103b (13/08/2026) — compile-fix: publishAapsActiveSlot verwachtte non-null, AppSettings.aapsActiveSlot is nullable

**Build-foutmelding.**
```
e: CareSensAirDriver.kt:537:52 Argument type mismatch: actual type is
   'com.fclglucolink.app.sensor.SensorSlot?', but 'com.fclglucolink.app.sensor.SensorSlot' was expected.
e: DexcomG6Driver.kt:487:52 Argument type mismatch: actual type is
   'com.fclglucolink.app.sensor.SensorSlot?', but 'com.fclglucolink.app.sensor.SensorSlot' was expected.
```

**Oorzaak.** Ronde 103's `AapsSlotSchedule.publishAapsActiveSlot(slot: SensorSlot)`
nam een non-null parameter aan, maar `AppSettings.aapsActiveSlot` is zelf
`Flow<SensorSlot?>` (nog geen AAPS-bron gekozen is een geldige toestand) —
`settings.aapsActiveSlot.first()` levert dus `SensorSlot?` op, niet
`SensorSlot`. Simpele oversight, geen logicafout: de bestaande
`isPriority = ... == slot`-vergelijking werkte al prima met een nullable
waarde (Kotlin's `==` accepteert dat), maar de NIEUWE cache-doorgeef-aanroep
niet.

**Fix.** `publishAapsActiveSlot` accepteert nu `slot: SensorSlot?` — `null`
maakt de cache leeg, waarna `isPrioritySlot()` voor beide sloten `false`
oplevert (dezelfde veilige koude-start-situatie die al gedocumenteerd stond
voor "cache nog nooit gevuld").

**Verificatie:** balance-checker op alle 3 gewijzigde bestanden; de twee
gerapporteerde regelnummers (537/487) komen exact overeen met de twee
`publishAapsActiveSlot(currentAapsSlot)`-aanroepen.

Gewijzigd: `sensor/ble/AapsSlotSchedule.kt`, `app/build.gradle.kts`.

versionCode 110, versionName `0.9.14-caresens-yield-follows-aaps-priority-b`.

## Ronde 104 (13/08/2026) — Fase 1: mg/dL vs mmol/L weergave-toggle

**Verzoek.** "Ik zit nog te denken om voor er eventueel andere sensoren in
komen om de app nog uit te breiden met een mg/dl vs mmol/l knop intern
hoeft er dan niks te veranderen maar in de ui zou da weer gegeven Bg waarden
dan moeten kunnen veranderen." Na een meedenk-ronde (zonder code) bleek de
app op dat moment mmol-only, hardcoded verspreid over ~6 schermen, met de
kleurbanden/grafiek-as ZELF ook als mmol-getallen in de code — dus niet
"waarde × 18 waar we al printen", maar een centrale format-laag + canonieke
mg/dL-drempels. Op verzoek van de gebruiker geldt de toggle ook voor alle
invoervelden (fingerstick, simulator).

**`AppSettings.displayUnit`** (nieuw, globaal — niet per-slot: een
weergave-voorkeur van de gebruiker, geen eigenschap van een fysieke sensor).
Default `MMOL`, dus een bestaande installatie ziet niets veranderen totdat
de gebruiker 'm expliciet omzet (Instellingen -> "Display").

**`ui/Units.kt`**: `GlucoseUnit` (MGDL/MMOL) + `formatForDisplay`/
`formatForDisplayWithUnit`/`String.parseToMgdl` — de ENE centrale plek die
weet hoe een mg/dL-waarde eruitziet in de gekozen eenheid, en omgekeerd.

**`GlucoseChart.kt` — grootste refactor.** In plaats van de eenheid-toggle
door de 15+ rondes hard-bevochten pan/zoom/granulariteit-wiskunde heen te
weven (hoog risico, zie de kdocs in dat bestand), plot de grafiek voortaan
ALTIJD intern in mg/dL (canonieke schaal, geen `.mgdlToMmol()` meer op
geplotte punten) — alleen de Y-as-tekstlabels zijn eenheid-bewust (een
`ValueFormatter`, hetzelfde patroon als de bestaande X-as-tijdformatter).
Band-/kleurgrenzen zijn nu de erkende ronde mg/dL-klinische-drempels
(70/180/225/270) i.p.v. de eerdere mmol×18,0182-omgerekende getallen —
round-tript naar exact dezelfde 4,0/10,0/12,5/15,0 mmol-weergave als vóór
deze ronde.

**Overige schermen**: StatusScreen (BG-ring, delta, raw-indicator,
`bgRangeColor` naar mg/dL-drempels), CombiScreen (2-slot-tabelletje +
`DualGlucoseChart`), BleConnectionService (statusbalk-notificatietekst),
CalibrationScreen (fingerstick-invoerdialoog + entry-lijst — de
kalibratie-WISKUNDE zelf, `OffsetSlider`/`CalibrationScatterChart`/
`manualOffsetMmol`'s opslag, blijft deze ronde bewust in mmol/L, zie
onderstaande scope-afweging), SimulatorSetupScreen (handmatige
waarde-invoer + statusregels — de externe-lijst-bestandsindeling blijft
bewust mmol/L, een opslagformaat-afspraak, geen live invoerveld),
SettingsScreen (nieuwe "Display"-kaart met de 2-standen-kiezer).

**Bewuste scope-grens.** CalibrationScreen.kt's diepere kalibratie-
wiskunde (handmatige offset-slider, de Canvas-kalibratiecurve, de
spline-"6,0 mmol/L"-referentie) is deze ronde NIET meegenomen — dat raakt
`CalibrationValidation.kt`/`fitLinearCalibration`/`fitSplineCalibration`,
code die expliciet als "nagerekend en correct" gedocumenteerd staat en
mede bepaalt wat naar AAPS gedoseerd wordt. Gegeven dat risico bewust
beperkt tot de expliciet gevraagde invoervelden en displays; een eventuele
latere ronde kan dit uitbreiden als gewenst.

**Verificatie:** balance-checker op alle 9 gewijzigde bestanden; grep-
controle dat geen ongecontroleerde `formatMmol()`/hardcoded "mmol/L"-tekst
meer overblijft buiten de bewust ongewijzigde plekken hierboven.

Gewijzigd: `ui/Units.kt`, `data/AppSettings.kt`, `ui/GlucoseChart.kt`,
`ui/StatusScreen.kt`, `ui/CombiScreen.kt`, `sensor/ble/BleConnectionService.kt`,
`ui/CalibrationScreen.kt`, `ui/SimulatorSetupScreen.kt`, `ui/SettingsScreen.kt`,
`app/build.gradle.kts`.

versionCode 111, versionName `0.9.15-display-unit-toggle`.

## Ronde 105 (13/08/2026) — mg/dL-toggle uitgebreid naar de kalibratie-schermdelen

**Verzoek.** "Nu graag verder met de calibratie die moet uiteraard ook de
waarden op het scherm in mg/dl weergeven als die in de settings is
gekozen." — de expliciet in Ronde 104 buiten scope gehouden delen van
CalibrationScreen.kt.

**Wat is meeverzet** (alleen presentatie/invoer, geen wiskunde/opslag):

- `OffsetSlider` — het "Manual offset"-getal naast de titel EN de
  Slider zelf (zichtbaar bereik ±1,5 mmol/L resp. het mg/dL-equivalent
  ±27,03 mg/dL, met een vergelijkbare stapgrootte: 0,05 mmol/L resp.
  1 mg/dL) tonen nu de gekozen eenheid. De OPSLAG
  (`settings.calibrationManualOffsetMmol`, wat rechtstreeks
  `activeCalibratedMgdl()` voedt) blijft ongewijzigd mmol-gebaseerd —
  `onChange` levert nog steeds mmol op, de omzetting gebeurt alleen aan
  de randen van deze ene Composable.
- `CalibrationScatterChart` — zelfde "plot altijd in mg/dL, alleen de
  as-TEKST zet om"-patroon als `GlucoseChart.kt`'s `yAxisValueFormatter`
  (zie Ronde 104): de plot-coördinaten (`xPx`/`yPx`, de gefitte curve,
  de scatterpunten) rekenden al in mg/dL en blijven ongewijzigd — alleen
  de rasterstap/-labels (voorheen altijd ronde mmol-stappen: 1/2/4)
  worden nu bepaald in de gekozen eenheid (mg/dL: 25/50/100).
- `StatusCard` — de spline-statusmelding "Spline calibration applied
  (knot at 6.0 mmol/L)" toont het vaste knikpunt nu in de gekozen
  eenheid. Het knikpunt zelf (6,0 mmol/L, een vaste parameter van
  `SplineCalibrationMath.kt`) verandert niet — alleen de tekst.
- Delta-validatiemeldingen (`AddCalibrationDialog`'s `onConfirm`,
  `evaluateNewCalibrationEntry`'s `warningMmolPer5Min`/
  `deltaMmolPer5Min`/`thresholdMmolPer5Min`) — nieuwe
  `formatRatePer5Min()`-helper toont deze nu ook in de gekozen eenheid
  (mg/dL per 5 min). Een snelheid/delta heeft geen offset (zuivere
  mmol↔mg/dL-schaalfactor, ~×18,0182), dus dezelfde
  `mmolToMgdl()`/`mgdlToMmol()`-extensies zijn hier net zo geldig als op
  een concentratie.

**Wat bewust NIET verandert**: `CalibrationValidation.kt`,
`fitLinearCalibration`, `fitSplineCalibration`, `activeCalibratedMgdl()`'s
wiskunde zelf, en de STORAGE-representatie van `calibrationManualOffsetMmol`
(blijft mmol) — exact dezelfde grens als Ronde 104, nu consequent
doorgetrokken: overal waar de kalibratie-WISKUNDE zit, blijft die
ongewijzigd; alleen wat er op het scherm STAAT volgt de toggle.

**Verificatie:** balance-checker op `CalibrationScreen.kt`; grep-controle
dat geen ongecontroleerde hardcoded "mmol/L"-tekst in gebruikersgerichte
strings overblijft (alleen nog in kdoc-commentaar en binnen expliciete
`when (unit)`-takken).

Gewijzigd: `ui/CalibrationScreen.kt`, `app/build.gradle.kts`.

versionCode 112, versionName `0.9.16-display-unit-calibration-screen`.

## Ronde 106 (13/08/2026) — Fase 2 stap 1: alarmen, de instellingen-laag

**Verzoek.** "Dan wil ik nu verder met het implementeren van de alarmen,
waarbij ik in ieder geval 1 overal knop wil om in 1 keer alle alarmen
aan/uit te zetten en indien die is ingeschakeld dat dan de afzonderlijke
alarmen kunnen worden ingesteld maar ook ieder afzonderlijk aan en uit
kunnen waarbij de laatst ingestelde waarde wel persistent over een restart
dan wel app update dan blijven." Voortbouwend op de eerdere meedenk-ronde
(bij het mg/dl-vs-mmol-verzoek) over een predictief alarm, stop/snooze,
per-alarm instelbare grenzen/geluid/vibratie, en een stale-data-alarm.

Voorafgaand aan de implementatie zijn drie ontwerpkeuzes met de gebruiker
afgestemd: (1) welke alarmtypes deze ronde al krijgen — gekozen: de volle
set van 6 (Urgent Low, Low, High, Urgent High, Predictive, Stale data);
(2) globaal vs. per-slot — gekozen: globaal, zelfde redenering als
displayUnit (Ronde 104): het AAPS-actieve slot bewaakt de alarmen, maar de
gevarengrenzen zijn een voorkeur van de gebruiker, geen eigenschap van een
fysieke sensor; (3) standaardstand van de hoofdschakelaar na deze update —
gekozen: UIT, een expliciete opt-in.

**`alarm/AlarmType.kt`** (nieuw pakket) — de zes typen als enum, elk met
een categorie (THRESHOLD_LOW/THRESHOLD_HIGH/PREDICTIVE/STALE_DATA, bepaalt
welke detailinstelling(en) relevant zijn), een standaarddrempel/
-voorlooptijd/-stale-minuten, en een standaard-geluidsprofiel
(`AlarmSoundProfile.URGENT` voor de vier drempelalarmen, `GENTLE_ESCALATING`
voor Predictive/Stale data — naar het letterlijke voorbeeld uit de
meedenk-ronde: "Bg<3 urgent gelijk echt alarm met stevig geluid maar voor
een voorspellend alarm een liefelijk vogelgeluidje wat bij negeren
langzaam aan een steeds iets hoger volume krijgt"). Predictive heeft bewust
GEEN eigen drempel — gebruikt de bestaande Low/High-drempels als
doellijnen voor de trendextrapolatie, zodat er niet drie plekken zijn waar
"wat is de gevarengrens" ingesteld kan worden.

**`AppSettings.kt` — nieuwe "Alarmen"-sectie.** Eén hoofdschakelaar
(`alarmsMasterEnabled`, default UIT) + per type (via een nieuwe
`alarmXxx(base, type)`-sleutelfabriek, hetzelfde patroon als de bestaande
`slotXxx()` voor sensoren): aan/uit, drempel (mg/dL), voorlooptijd
(Predictive), stale-minuten (Stale data), geluidsprofiel, trilling — elk
gewoon een DataStore-veld, dus automatisch persistent over herstarts/
app-updates zonder aparte opslaglogica. De hoofdschakelaar uitzetten wist
NIETS van de per-type-instellingen — precies het gevraagde gedrag ("de
laatst ingestelde waarde wel persistent").

**`ui/AlarmSettingsScreen.kt`** (nieuw scherm, bereikbaar via een nieuwe
"Alarms"-kaart op het instellingenscherm). Bovenaan de hoofdschakelaar
("Enable alarms"), daaronder een kaart per alarmtype met een eigen aan/
uit-schakelaar en, als die aan staat, de detailinstellingen (drempel/
voorlooptijd/geluid/trilling — drempels tonen/parsen in de gekozen
weergave-eenheid, zie Ronde 104/105). Zolang de hoofdschakelaar uit staat,
blijven alle losse schakelaars/instellingen ZICHTBAAR maar NIET
aanraakbaar (`enabled = false`) — in één oogopslag te zien wat
geconfigureerd staat, zonder dat er per ongeluk iets aangepast kan worden
terwijl alarmen toch uit staan.

**Bewuste scope-grens (expliciet op het scherm zelf vermeld).** Deze ronde
bouwt UITSLUITEND de instellingen-laag. Er is nog GEEN koppeling naar een
achtergrond-alarm-motor, geen daadwerkelijk geluid/trilling, geen
STREAM_ALARM-afspeellogica, geen escalerende-volume-gedrag, en geen
volledige-scherm-alarmweergave met stop/snooze — dat is allemaal Fase 2's
vervolgstap. Zetten van een schakelaar op dit scherm heeft dus nu nog geen
enkel voelbaar effect op het toestel; het scherm slaat alleen de
gekozen configuratie op zodat de evaluatie-motor er straks meteen mee kan
starten.

**Verificatie:** balance-checker op alle 5 gewijzigde/nieuwe bestanden;
gecontroleerd dat `SettingsScreen()` maar op één plek aangeroepen wordt
(FclGlucoLinkNavHost.kt) zodat de nieuwe verplichte `onOpenAlarms`-
parameter geen andere aanroep breekt.

Nieuw: `alarm/AlarmType.kt`, `ui/AlarmSettingsScreen.kt`.
Gewijzigd: `data/AppSettings.kt`, `ui/SettingsScreen.kt`,
`ui/FclGlucoLinkNavHost.kt`, `app/build.gradle.kts`.

versionCode 113, versionName `0.9.17-alarm-settings-layer`.

## Ronde 106b (13/08/2026) — compile-warning-fix: ontbrekende @OptIn

**Melding.** "AlarmSettingsScreen.kt geeft: This material API is
experimental and is likely to change or to be removed in the future. in de
regels: 245, 246 en 251" — de `SingleChoiceSegmentedButtonRow`/
`SegmentedButton`-geluidsprofiel-kiezer in `AlarmTypeDetailSettings()`.

**Root cause.** `@OptIn(ExperimentalMaterial3Api::class)` stond alleen op
`AlarmSettingsScreen()` zelf (nodig voor `TopAppBar`) — die opt-in dekt
alleen de body van DIE ene functie, niet functies die 'm aanroept. De
SegmentedButton-rij zit in `AlarmTypeDetailSettings()`, een aparte private
Composable, die zijn eigen opt-in miste.
`CalibrationScreen.kt`/`SettingsScreen.kt` hebben toevallig nooit dit
probleem gehad omdat hun SegmentedButton-gebruik daar rechtstreeks in de
al-geannoteerde top-level Composable staat, niet in een eigen sub-functie.

**Fix.** `@OptIn(ExperimentalMaterial3Api::class)` toegevoegd aan
`AlarmTypeDetailSettings()`. Puur een compiler-waarschuwing geweest (geen
harde build-fout), functioneel verandert er niets — Kotlin's eigen
`@OptIn`-mechanisme onderdrukt de waarschuwing per-functie, precies
bedoeld voor dit soort gevallen.

Gewijzigd: `ui/AlarmSettingsScreen.kt`, `app/build.gradle.kts`.

versionCode 114, versionName `0.9.17-alarm-settings-layer-b`.

## Ronde 106c (13/08/2026) — echte systeem-ringtonekiezer + Predictive Low/High gesplitst

**Verzoek.** "Het lijkt al heel redelijk. Ik wil echter per alarmsoort een
eigen geluid kunnen kiezen uit de geluiden op de telefoon (zoals je ook
een ringtone voor de telefoon kunt kiezen) dan moet er per alarm gekozen
kunnen worden of het alarm direct klinkt of dat het langzaam opbouwt
(daarbij hoeft de opbouw tempo niet instelbaar te zijn) de predict low en
predictive high moeten echter wel afzonderlijk ingesteld kunnen worden."

**Geluid ontkoppeld van opbouwgedrag.** Ronde 106's vaste "Urgent"/
"Gentle"-profiel (`AlarmSoundProfile`) is vervangen door twee losse,
per-type instelbare zaken:
1. Een daadwerkelijk GELUIDSBESTAND, gekozen via Android's eigen
   ringtone-kiezer (`RingtoneManager.ACTION_RINGTONE_PICKER`, type
   `TYPE_ALARM`) — hetzelfde systeemscherm als bij het kiezen van een
   beltoon. Nieuwe `AlarmSettingsScreen.kt`-composable `SoundPickerRow`
   toont de titel van het huidige geluid (of "Default" als er nog niets
   gekozen is) met een "Choose"-knop die de systeem-kiezer opent; de
   teruggekregen URI wordt als string opgeslagen (`AppSettings.
   alarmSoundUri(type)`/`setAlarmSoundUri`). Geen extra permissie nodig —
   de kiezer is een systeem-Activity.
2. [`AlarmEscalation`] (`IMMEDIATE`/`GRADUAL`, vervangt `AlarmSoundProfile`)
   — blijft een SegmentedButtonRow ("Immediately"/"Gradual"), nu als eigen
   rij ("When triggered") los van het geluid. Het opbouwtempo zelf is
   bewust NIET instelbaar (letterlijk verzoek) — puur een latere-ronde-
   implementatiedetail van de evaluatiemotor, geen instelling.

**Predictive Low/Predictive High gesplitst.** `AlarmType.PREDICTIVE`
(Ronde 106, bewaakte beide richtingen tegelijk met één voorlooptijd/
geluid) is vervangen door `AlarmType.PREDICTIVE_LOW` en `AlarmType.
PREDICTIVE_HIGH` — elk met een eigen aan/uit, voorlooptijd, geluid,
opbouwgedrag en trilinstelling, exact zoals de andere vijf alarmtypes.
`AlarmCategory` kreeg twee losse waarden (`PREDICTIVE_LOW`/
`PREDICTIVE_HIGH` i.p.v. één `PREDICTIVE`) zodat `AlarmSettingsScreen.kt`'s
detail-UI (dezelfde "voorlooptijd"-stepper) voor beide types blijft werken
zonder aparte code. Het totaal aantal alarmtypes is daarmee 6 -> 7.

**Verificatie:** balance-checker op alle drie gewijzigde bestanden;
grep bevestigt geen resterende code-referenties naar het oude
`AlarmSoundProfile`-type of naar `AlarmCategory.PREDICTIVE` (los, niet
_LOW/_HIGH) — alleen nog historische vermeldingen in kdoc-commentaar.

Gewijzigd: `alarm/AlarmType.kt`, `data/AppSettings.kt`,
`ui/AlarmSettingsScreen.kt`, `app/build.gradle.kts`.

versionCode 115, versionName `0.9.18-alarm-sound-picker-predictive-split`.

## Ronde 107 (13/08/2026) — Fase 2 stap 2: de alarm-evaluatiemotor

**Verzoek.** "Ja dit is correct zo. graag verder met de implementatie" —
na goedkeuring van de instellingen-laag (Ronde 106/106b), nu de
daadwerkelijke werking: achtergrondbewaking, geluid/trilling, en het
volledige-scherm-alarmscherm met Stop/Snooze.

**Nieuw pakket `alarm/`** (naast het al bestaande `AlarmType.kt`):
- `AlarmEvaluator.kt` — pure beslislogica, los van Android/DataStore. Drempel-
  alarmen: simpele vergelijking. Predictief: "eenvoudig model" zoals
  gevraagd — geen eigen regressie, maar `GlucoseReading.trendMgdlPerMin`
  (al berekend door elke sensor-driver, dezelfde eenheid als xDrip's
  "slope") rechtstreeks doorgetrokken: projectie = huidige waarde + trend ×
  voorlooptijd. Predictive Low/High vuurt alleen als de trend de juiste kant
  op gaat EN de huidige waarde nog niet zelf al over de Low/High-drempel
  heen is (voorkomt dubbel alarm met het drempel-alarm voor dezelfde
  situatie). Stale data: geen verse meting binnen de ingestelde minuten
  (of nog nooit een meting gehad). Bij meerdere gelijktijdig vurende typen
  klinkt er maar één, volgens een vaste prioriteit (Urgent Low/High eerst,
  dan Low/High, dan Predictive Low/High, dan Stale data).
- `AlarmSoundPlayer.kt` — speelt het per-type gekozen geluid af via
  `AudioAttributes.USAGE_ALARM` (de STREAM_ALARM-equivalent), niet de
  notificatiestroom. `Immediate` = volle sterkte direct; `Gradual` = begint
  op 15%, klimt elke 5s met 10% naar vol (tempo bewust hardcoded, zoals
  gevraagd). Trilling via `VibrationEffect.createWaveform` (herhalend
  500ms-aan/500ms-uit-patroon).
- `AlarmController.kt` — orkestreert: start geluid/trilling, toont de
  full-screen-notificatie die `AlarmActivity` opent, en verwerkt Stop/
  Snooze. **Stop vs. Snooze**: geen van beide is permanent stil — Stop zet
  een vast, per-categorie afkoelmoment (20 min voor drempelalarmen, 30 min
  voor predictief, 15 min voor stale data), Snooze laat de gebruiker zelf
  kiezen (15/30/60 min-knoppen op het alarmscherm). Bewust geen "voor
  altijd stil" — bij een aanhoudend kritieke waarde moet er sowieso na een
  tijdje opnieuw gewaarschuwd worden, ook na Stop.
- `AlarmMonitor.kt` — de periodieke check (elke 60s), aangeroepen vanuit
  een volledig LOSSTAANDE coroutine-lus in `BleConnectionService.onCreate()`
  — bewust NIET gekoppeld aan de aankomst van een nieuwe meting (dan zou
  het stale-data-alarm, dat júist moet afgaan als er GEEN nieuwe meting
  komt, nooit gecontroleerd worden), en bewust volledig los van de al zeer
  delicaat afgestemde scan-/verbindingstiming elders in dat bestand — geen
  enkele wijziging daar, alleen lezen (Room/DataStore), nooit schrijven
  naar sensor-gerelateerde state. Bewaakt alleen het AAPS-actieve slot
  (eerder afgestemd: "het aaps actieve slot bewaakt de alarmen").
- `AlarmActivity.kt` — het volledige-scherm-alarmscherm: alarmtype +
  omschrijving, laatste BG-waarde (in de gekozen weergave-eenheid), Stop-
  knop, drie Snooze-knoppen. Eigen Activity (niet een NavHost-scherm) —
  moet ook kunnen verschijnen als het toestel vergrendeld is of de app niet
  op de voorgrond staat (`setShowWhenLocked`/`setTurnScreenOn`,
  `requestDismissKeyguard`).

**Nieuwe permissies**: `VIBRATE` (normaal, geen prompt) en
`USE_FULL_SCREEN_INTENT` (nodig voor de scherm-doorbrekende notificatie).

**Bekende beperking, nog niet opgelost.** Op Android 14 (API 34) staat
`USE_FULL_SCREEN_INTENT` voor gewone apps niet meer automatisch aan — de
gebruiker moet dat mogelijk zelf via Instellingen > Apps > FCLGlucoLink >
Speciale toegang > Volledig scherm-meldingen inschakelen, anders valt het
terug op een gewone hoge-prioriteit-melding (nog steeds zichtbaar/hoorbaar,
alleen niet automatisch schermvullend als het toestel vergrendeld is). Een
knop die de gebruiker daar direct naartoe stuurt (zelfde patroon als de
bestaande batterij-optimalisatie-knop in de handleiding) is een logische
vervolgstap. Ook nog niet gebouwd: volledige garantie op Do Not Disturb-
doorbraak (`setBypassDnd(true)` staat al op het kanaal, maar Android
vereist daarnaast losse, door de gebruiker verleende "Do Not Disturb-
toegang" voor een harde garantie).

**Niet getest.** Deze ronde is met dezelfde balance-/consistentie-checks
geverifieerd als steeds (zie hieronder), maar is — net als elke ronde —
niet door een echte Gradle-build heen gehaald; dat gebeurt zoals gebruikelijk
pas bij de eerstvolgende build door de gebruiker zelf.

**Verificatie:** balance-checker op alle 7 nieuwe/gewijzigde Kotlin-
bestanden + een well-formedness-check op AndroidManifest.xml; elke
AppSettings-functienaam die de nieuwe alarm-bestanden aanroepen is
gecontroleerd tegen de daadwerkelijke functiedefinities (voorkomt een
"unresolved reference"-compileerfout door een typo).

Nieuw: `alarm/AlarmEvaluator.kt`, `alarm/AlarmRuntimeState.kt`,
`alarm/AlarmSoundPlayer.kt`, `alarm/AlarmController.kt`,
`alarm/AlarmMonitor.kt`, `alarm/AlarmActivity.kt`.
Gewijzigd: `data/AppSettings.kt` (alarmMutedUntilMs), `sensor/ble/
BleConnectionService.kt` (AlarmMonitor-lus + opruiming), `AndroidManifest.xml`
(permissies + AlarmActivity), `app/build.gradle.kts`.

versionCode 116, versionName `0.9.19-alarm-evaluation-engine`.

## Ronde 107b (13/08/2026) — compile-fix + Alarm/Vibrate/Both

**Melding.** "AlarmSoundPlayer.kt geeft: Unresolved reference 'cancel'. in
106. Bovendien wil ik per alarm kunnen kiezen tussen alarm of vibrate of
both. Het lijkt met het meest handig om de vibrator knop die nu overal
onderaanstaat te vervangen door alarm - vibrate - both knop."

**Compile-fix.** `CoroutineScope.cancel()` is een EXTENSION-functie uit
`kotlinx.coroutines` (geen member-functie van `CoroutineScope` zelf) —
moet net als `delay()`/`launch()` expliciet geïmporteerd worden. Die ene
`import kotlinx.coroutines.cancel`-regel ontbrak in `AlarmSoundPlayer.kt`,
vandaar de "Unresolved reference" op `playerScope?.cancel()`. Toegevoegd.

**Alarm/Vibrate/Both.** Nieuw `AlarmAlertMode`-enum (`alarm/AlarmType.kt`)
vervangt de losse aan/uit-vibratieschakelaar door één 3-standen-keuze per
alarmtype: alleen geluid, alleen trilling, of beide (default Both). Opslag
via `AppSettings.alarmAlertMode(type)` (vervangt `alarmVibrationEnabled`).
`AlarmSoundPlayer.start()` gebruikt dit om onnodig werk te vermijden —
bij `VIBRATE` wordt er geen MediaPlayer opgezet, bij `SOUND` geen Vibrator
aangesproken. Op `AlarmSettingsScreen.kt` verving een `SingleChoice
SegmentedButtonRow` met de labels "Alarm"/"Vibrate"/"Both" (letterlijk
gevraagde bewoording) de oude "Vibration"-schakelaar-rij, in dezelfde
positie (onderaan elk alarmtype-kaartje).

**Verificatie:** balance-checker op alle 5 gewijzigde bestanden; grep
bevestigt geen resterende code-referenties naar de oude
`alarmVibrationEnabled`/`setAlarmVibrationEnabled`-functies (alleen nog een
historische kdoc-vermelding).

Gewijzigd: `alarm/AlarmType.kt`, `alarm/AlarmSoundPlayer.kt`,
`alarm/AlarmController.kt`, `data/AppSettings.kt`,
`ui/AlarmSettingsScreen.kt`, `app/build.gradle.kts`.

## Ronde 108 (13/08/2026) — eigen streefwaarde per predictief alarm + handleiding bijgewerkt

**Verzoek.** "Kun je de predictive alarms nog zo zetten dat daar een Bg
waarde wordt ingevoerd ipv de koppeling aan low en high dat geeft meer
vrijheid. En kun je de manual nu ook weer even doornemen zodat die weer in
lijn is met de huidige code. Ik zag dat de alarms er nog niet in stonden
die moeten er wel in komen het hoeft echter niet per type heel uitgebreidt
want de namen spreken al voorzich gewoon even algemeen dat per type een
apart geluid en dat er voor allen viabratieof alleen geluid of beide
gekozennka worden."

**Predictieve alarmen — eigen streefwaarde.** Predictive Low/High
gebruikten tot deze ronde (sinds Ronde 106b) de streefwaarde van het
gekoppelde LOW/HIGH-alarm via een cross-lookup in `AlarmEvaluator.kt`
(`configs[AlarmType.LOW]`/`configs[AlarmType.HIGH]`). Dat gaf minder
vrijheid: Predictive Low kon nooit op een andere waarde staan dan Low
zelf. Nu heeft elk predictief type z'n EIGEN, onafhankelijke
`defaultThresholdMgdl` (`alarm/AlarmType.kt` — Predictive Low = 70.0,
Predictive High = 180.0, beide vrij aan te passen), en `AlarmEvaluator.kt`
se `predictiveLowFires`/`predictiveHighFires` gebruiken uitsluitend
`config.thresholdMgdl` van het predictieve type zelf — geen enkele
koppeling meer tussen de configs onderling. `AlarmSettingsScreen.kt` toont
voor de predictieve types nu zowel een streefwaarde-stepper ("Target") als
de bestaande voorlooptijd-stepper, in plaats van alleen de laatste.
Resultaat: Predictive Low kan bijvoorbeeld op 90 mg/dL gezet worden als
vroege waarschuwing, terwijl het eigenlijke Low-alarm gewoon op 70 blijft
staan — twee losse instellingen.

**Handleiding bijgewerkt.** `ui/ManualScreen.kt` miste een onderwerp over
de alarmen. Nieuw `ManualTopic.ALARMS`-item toegevoegd (tussen Smoothing
en Diagnostics), bewust op hoog niveau gehouden — geen aparte paragraaf
per van de zeven alarmtypes, want de namen spreken voor zich (letterlijk
verzoek) — met vijf secties: wat het systeem doet en waar het in te
stellen is, de hoofdschakelaar en dat per-type-instellingen daar los van
blijven bestaan, dat Predictive Low/High hun eigen streefwaarde hebben,
dat elk type een eigen gekozen geluid heeft plus een Alarm/Vibrate/Both-
keuze, en het Stop-vs-Snooze-gedrag van het volledige-scherm-alarm. De
Settings-onderwerp-kop is aangepast van "Calibration and smoothing" naar
"Calibration, smoothing, and alarms" zodat die ook naar het nieuwe
onderwerp verwijst.

**Verificatie:** balance-checker op alle drie gewijzigde Kotlin-bestanden;
grep bevestigt geen resterende `configs[AlarmType.LOW]`/
`configs[AlarmType.HIGH]`-referenties in `AlarmEvaluator.kt`.

Gewijzigd: `alarm/AlarmType.kt`, `alarm/AlarmEvaluator.kt`,
`ui/AlarmSettingsScreen.kt`, `ui/ManualScreen.kt`, `app/build.gradle.kts`.

versionCode 118, versionName "0.9.21-predictive-thresholds-manual".

## Ronde 109 (15/08/2026) — smoothing: onmiddellijke, symmetrische Q-trigger

**Verzoek.** "Ik las iets over de smoothing in AIMI. Iemand gaf aan dat de
adaptive smoothing een hele goed optie was om te selecteren. Kun je de
werkwijze van deze smoothing als mede die van de kalman uit AIMI eens
vergelijken met degene die wij gebruiken in FCLGlucoLink. En als er
voordelen te behalen zijn (belangrijk voordeel is snellere
stijgingsdetectie zonder gelijk meer ruis te krijgen) zou het een optie
zijn om die in onze versie over te nemen." Gevolgd door een expliciet
verzoek om dat verschil eerst goed door te rekenen, met bijzondere aandacht
voor of de afvlakking richting de piek ook eerder doorkomt (zodat het
downstream doseeralgoritme iets eerder kan afremmen).

**Vergelijking.** `smoothing/KalmanSmoother.kt` is al een directe,
wiskundig-equivalente vereenvoudiging van de basis-UKF die eerder is
aangeleverd (inclusief diens 2-van-3-teken-poort: pas bij 2 van de laatste
3 metingen die >2σ in dezelfde richting afwijken, wordt de procesruis Q
tijdelijk opgeblazen). AIMI's `AdaptiveSmoothingPlugin.kt` (los, nieuwer
plugin, niet dezelfde bron) vervangt die confirmatie-eis door een
onmiddellijke, single-sample trigger (>2,5σ) — maar UITSLUITEND bij
stijgingen (positieve afwijking); dalingen lopen bij AIMI via een aparte,
dosis-gerelateerde "kinetic hypo"-noodrem. Twee AIMI-onderdelen zijn niet
overdraagbaar: compressie-artefact-detectie (heeft IOB/ISF uit een
insulineprofiel nodig — FCLGlucoLink heeft geen doseeralgoritme/profiel) en
de RTS-backward-pass (herrekent alleen oudere grafiekpunten, raakt de live
waarde toch al niet aan — precies zoals onze eigen kdoc dat al beschrijft
voor de originele UKF). AIMI's eigen R-adaptatie (mediaan + vaste gain) is
bovendien eenvoudiger dan onze bestaande getrimde-gemiddelde/asymmetrische-
caps/EMA-aanpak — daar viel niets te halen.

**Doorrekening.** Een Python-simulatie (Kalman-wiskunde 1:1 overgenomen uit
`KalmanSmoother.kt`) vergeleek drie varianten — huidig (2-van-3), AIMI-stijl
(alleen stijgingen), en symmetrisch (beide richtingen) — op twee scenario's:
(1) een abrupt-instap-maaltijdcurve (200 ruisrealisaties) en (2) een
ruis-only Monte Carlo-test (500 realisaties, geen echte trend) voor
vals-triggerrisico. Bij een GELEIDELIJKE stijging (opbouw over 10+ minuten,
het gebruikelijke geval) bleek er geen enkel verschil — de gewone
Kalman-winst vangt dat al soepel op. Bij een echt ABRUPTE knik binnen één
5-minuten-sample: ~1 minuut eerdere detectie van zowel de stijging als de
afvlakking richting de top, en ~0,3-0,4 mg/dL minder overshoot rond de
piek — zonder de uitvoer merkbaar ruiziger te maken bij normale
sensorruis (0% valse triggers, identieke snelheids-RMSE bij σ=3 mg/dL; bij
een merkbaar ruizigere sensor σ=5 iets vaker een trigger, maar de
snelheids-RMSE bleef nagenoeg gelijk). De symmetrische variant scoorde op
alle metingen gelijk-aan-of-beter-dan de stijging-only-variant, precies
omdat de afvlakking zich toont als een NEGATIEVE afwijking t.o.v. de nog-
stijgende voorspelling — die wordt door een stijging-only-trigger genegeerd.

**Implementatie.** `KalmanSmoother.kt` kreeg een nieuwe, symmetrische
onmiddellijke trigger (`immediateTriggerThreshold` = 2,5σ,
`immediateQGlucoseScale` = ×2, `immediateQRateScale` = ×50) naast — niet in
plaats van — de bestaande 2-van-3-poort: `val prediction = when {
immediateTrigger -> ...; qScale > 1.0 -> ...; else -> basePrediction }`.
R-leren wordt tijdens deze trigger net als bij de bestaande poort
overgeslagen (`skipRUpdate` uitgebreid met `immediateTrigger ||`).
Bewust symmetrisch i.p.v. AIMI's stijging-only-asymmetrie: die asymmetrie
bestaat bij AIMI om te voorkomen dat een te snel reagerend filter een
AAPS-doseeralgoritme bij een daling laat overdoseren — FCLGlucoLink
doseert zelf niets, dus die reden gaat hier niet op, en de doorrekening
liet zien dat symmetrisch strikt beter scoort.

**Verificatie:** balance-checker op `KalmanSmoother.kt`; de geïmplementeerde
`prediction`-tak is regel-voor-regel gelijk aan de "symmetric"-variant uit
de simulatie die de cijfers hierboven opleverde.

Gewijzigd: `smoothing/KalmanSmoother.kt`, `app/build.gradle.kts`.

versionCode 119, versionName "0.9.22-smoothing-immediate-trigger".

## Ronde 110 (16/08/2026) — lege "ecko"-map + spline-kalibratiecurve-buiging

**Verzoek.** "Kun je eens kijken waarom iedere keer de lege map 'ecko'
terug komt. En kun je ook eens kijken naar de spline calibratie curve die
heeft bij sommige combinaties de neiging om rond de 5 tot 7 mmol een rare
buiging te krijgen. hij zou eigenlijk een lineair onderstuk en bovenstuk
moeten krijgen wat met 1 vloeiend verloop in elkaar overgaat."

**"ecko"-map.** Uitgezocht in de aangeleverde `com 16-8 21.15.zip`: dit is
een lege map `com/ecko` naast `com/fclglucolink`, zonder enige inhoud.
Grep over de hele FCLGlucoLink-broncode (en alle eerder geleverde zips)
levert nul treffers op voor "ecko" — dit bestand/deze map wordt door GEEN
enkel stukje code dat hier onderhouden wordt aangemaakt. Dit is dus geen
code-bug maar een lokaal Android Studio/IDE-artefact: zeer waarschijnlijk
een restant van het allereerste project-scherm (Android Studio stelt vaak
automatisch een pakketnaam voor gebaseerd op de systeem-gebruikersnaam —
en dat is hier letterlijk "Ecko"), van vóór het pakket werd hernoemd naar
`com.fclglucolink.app`. Dat "iedere keer terug komt" na verwijderen wijst
op de IDE se EIGEN projectcache (`.idea/`-bestanden, geen onderdeel van
deze broncode of van de geleverde zips) die de oude map nog als bekende
bron-map onthoudt en 'm bij een volgende Gradle-sync/project-herstart
gewoon opnieuw aanmaakt. Oplossing zit dus niet in de code: map verwijderen
via Android Studio se eigen Project-paneel (niet via Verkenner) zodat de
IDE 't ook uit z'n eigen model haalt, of desnoods de hele `.idea`-map
verwijderen en opnieuw laten opbouwen via Gradle-sync (die map is altijd
veilig weg te gooien, wordt automatisch geregenereerd).

**Spline-buiging.** `SplineCalibrationMath.kt` was conceptueel al precies
wat gevraagd werd — lineair onder `cx_low`, lineair boven `cx_high`, één
Hermite-stuk ertussen — maar `cx_low`/`cx_high` waren de RUWE, datazwevende
gewogen centroids van elk segment. Als de lage en hoge kalibratiepunten
toevallig ver uit elkaar liggen, spant het Hermite-stuk zich over die hele,
soms zeer brede afstand, en een cubic Hermite tussen twee ver-uit-elkaar-
liggende punten met verschillende raaklijnen buigt zichtbaar door t.o.v.
de rechte verbindingslijn — wiskundig geldt: afwijking op het midden ≈
(breedte × raaklijnverschil) / 8, schaalt dus lineair mee met hoe ver de
centroids uit elkaar liggen. Doorgerekend (20.000 willekeurige, legale
segment-combinaties): gemiddelde afwijking t.o.v. een rechte lijn op het
splitspunt was 4,5 mg/dL, met uitschieters tot 30+ mg/dL — precies de
gerapporteerde "rare buiging" rond 5-7 mmol/L (het splitspunt, 108 mg/dL =
6 mmol/L, ligt daar middenin).

**Fix.** Twee wijzigingen, zonder de fit-betrouwbaarheid te verlagen: (1)
de overgangsband rond het splitspunt wordt vastgeklemd op maximaal 27
mg/dL aan elke kant (`TRANSITION_CAP_MGDL`) — een centroid verder weg wordt
vervangen door hetzelfde segment se eigen rechte lijn, gewoon dichter bij
het splitspunt afgelezen (`pickTransitionAnchors()`); (2) binnen die band
worden de raaklijnen met de standaard Fritsch-Carlson-formule
teruggeschaald naar een strakkere grens (straal 1,5 i.p.v. de klassieke
3,0) i.p.v. de hele fit af te wijzen zodra ze te ver afwijken
(`rescaleTangents()`) — dat houdt de curve dichter bij de koorderichting.
Als vastklemmen de twee segmenten zou laten "kruisen" (zeldzaam), valt de
code terug op de volledige natuurlijke centroid-afstand met de klassieke
straal — exact het gedrag van vóór deze ronde, dus de fit-mislukt-kans
verandert niet. Doorgerekend resultaat: gemiddelde afwijking op het
splitspunt daalde van 4,5 naar ~2,0 mg/dL (p90 van 10,5 naar ~4,5 mg/dL);
het specifieke doorgerekende worst-case-scenario uit de analyse (centroids
op 75 en 190 mg/dL) daalde van +13,4 naar +7,1 mg/dL afwijking.

**Verificatie:** balance-checker op `SplineCalibrationMath.kt`; Python-
herimplementatie van de nieuwe logica bevestigt het verwachte gedrag op het
oorspronkelijke worst-case-scenario en op de bredere steekproef.

Gewijzigd: `calibration/SplineCalibrationMath.kt`, `app/build.gradle.kts`.

versionCode 120, versionName "0.9.23-spline-transition-fix".

## Ronde 111 (17/08/2026) — break-in filter voor nieuwe fysieke sensoren

**Verzoek.** "Ik lees nu dat er mensen zijn die bij de caresens de eerste
dag (max 2 dagen) de caresens springerig is. [...] zelf ervaar ik dat niet,
maar zou er een (instelbare filtering mogelijk zijn die de eerste 2 dagen
iets heftiger filtert en dan langzaam afbouwt gedurende de loop tijd."
Vervolgens verduidelijkt: "Wat ik met name wil voorkomen is dat
ruisgevoelige stijgingen het doseer algoritme onterecht triggert (ongeacht
of dat openapssmb is of fclvnext) dalingen zijn in mijn ogen dus minder van
belang. [...] een aan/uit knop en misschien een aantal dagen (of uren)
instelling dat het loopt [...] in principe heeft iedere sensor er last van
[...] een aflopende (lineair of logaritmische) correctie [...] de
calibratie kant er buiten [...] want niet iedereen zal calibratie
gebruiken." Tot slot de UI-plek: "Visueel bij de settings zie ik het onder
de knop 'enable smoothing' in het zelfde kader en als smoothing uit staat
beide uitgegrijsd."

**Ontwerp.** In tegenstelling tot Ronde 109's trigger (bewust symmetrisch,
zie die kdoc) is dit mechanisme bewust ASYMMETRISCH: het grijpt alleen in
bij metingen die HOGER zijn dan voorspeld (`normRaw > 0`, een stijging),
nooit bij dalingen — precies zoals gevraagd, omdat een onterecht getriggerd
SMB/dosis-signaal op basis van sensorruis bij een stijging het risico is
dat de gebruiker wil afdekken, niet bij een daling. Twee gekoppelde
ingrepen in `KalmanSmoother.smooth()`, beide geschaald met dezelfde
exponentieel-aflopende sterktefactor: (1) extra meetruis-variantie
(`breakInExtraRMgdlSq`, 80 mg/dL²) bovenop de normale adaptieve `rEff`,
wat de Kalman-gain voor stijgingen dempt; (2) een verhoogde drempel
specifiek voor Ronde 109's directe trigger bij stijgingen
(`breakInThresholdBoost`, tot ×3 bij volle sterkte) — nodig omdat die
trigger via Q-inflatie werkt, wat een R-verhoging alleen niet kan
compenseren. De sterktefactor komt van buiten (`smooth()`'s nieuwe
`breakInDecayFactor`-parameter, default 0.0 = uit) en volgt
`exp(-uren_sinds_start / τ)` met `τ = duur / 5` — na de ingestelde "duur"
in uren staat er dus nog ~0,7% van de correctie, wat een instelling van
bv. "24 uur" eerlijk interpreteerbaar maakt als "na 24 uur vrijwel
uitgewerkt", ondanks dat een exponent wiskundig nooit exact op nul komt.

**Bereik: globaal, niet kalibratie-afhankelijk, niet sensor-specifiek** —
exact zoals gevraagd. Eén aan/uit-schakelaar en één duur-instelling (in
hele uren, 1-72) gelden voor alle sensortypen; de kalibratielaag wordt hier
niet aangeraakt.

**Sensorleeftijd-signaal.** `computeBreakInDecayFactor()` in
`BleConnectionService.kt` gebruikt bij voorkeur het type-specifieke
"sessie gestart"-moment — `careSensAirSensorStartedAtMs` (komt uit de
CareSens Air se eigen 0xC0/2-protocol-tijdsveld) resp.
`dexcomG6SessionStartConfirmedAtMs` (gezet bij bevestigde nieuwe
Dexcom-sessie) — juist omdat deze per ECHTE nieuwe fysieke sensor
terugspringen naar nul, in tegenstelling tot de generieke
`getOrInitSensorStartedAtMs`-waarde die alleen bij een sensor-TYPE-wissel
wordt teruggezet (niet bij het vervangen van hetzelfde type sensor). Voor
de simulator (die geen van beide type-specifieke velden heeft) valt de
functie terug op die generieke waarde.

**UI.** Nieuwe "Break-in filter for new sensors"-schakelaar plus een
Slider voor de duur (1-72 uur, standaard 24), rechtstreeks onder "Enable
smoothing" in dezelfde Card/Column op `SettingsScreen.kt` — geen aparte
kaart. De schakelaar gebruikt `enabled = smoothingEnabled` (Material3's
automatische uitgrijs-gedrag, zelfde idioom als `AlarmSettingsScreen.kt`);
de bijbehorende labels en de Slider grijzen zelf mee via een expliciete
content-alpha (0,38, Material se standaard disabled-alpha), aangezien
Text/Slider dat niet automatisch doen zoals Switch dat wel doet. De
Slider zelf is bovendien ook uitgegrijsd/inactief zolang de break-in-
schakelaar zelf uit staat (een logische aanvulling op de letterlijke
vraag, niet een afwijking ervan: een duur instellen heeft geen effect
zolang de schakelaar uit staat).

**Verificatie:** balance-checker op `KalmanSmoother.kt`,
`AppSettings.kt`, `BleConnectionService.kt` en `SettingsScreen.kt`; grep
bevestigt dat `applySmoothingIfEnabled()`/`.smooth(`'s enige aanroeppunt in
het hele project is aangepast en niets anders in dat gevoelige BLE-bestand
is geraakt.

Gewijzigd: `smoothing/KalmanSmoother.kt`, `data/AppSettings.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/SettingsScreen.kt`,
`app/build.gradle.kts`.

versionCode 121, versionName "0.9.24-breakin-filter".

## Ronde 112 (17/08/2026) — Dexcom G7/ONE+-driver

**Verzoek.** Na de manual-update van Ronde 111: "dan wil ik graag verder met
de verdere implementatie van de dexcom g7. Ik heb nu nog geen sensor ter
beschikking maar wil wel alvast graag de code zover in orde brengen dat
zodra ik er eentje krijg ik gelijk kan beginnen met testen. Heb je daarvoor
nog de xdrip of juggluco code nodig om het beste van beide te combineren om
tot een zo stabiel mogelijke bluetooth verbinding te komen."

**Referentiemateriaal.** Geen nieuwe upload nodig — de al eerder aangeleverde
`uploads/xDrip-2026.08.08.zip` (dezelfde volledige xDrip+-broncode die ook
voor de G6-driver als referentie diende) bevat `libkeks/`, xDrip+'s eigen,
complete, pure-Java EC-J-PAKE-implementatie (auteur "JamOrHam", op Bouncy
Castle's lage-niveau EC-primitieven) — precies het stuk dat nodig was.
`uploads/Juggluco.zip` (native, closed-source) gaf een onafhankelijke
bevestiging: identieke BLE-UUID's/opcodes in `DexGattCallback.java`. Een
publieke GitHub-discussie (gui-dos/DiaBLE#17) bevestigde bovendien expliciet
dat xDrip+'s J-PAKE "succesvol" werkt.

**Waarom J-PAKE i.p.v. G6's vaste sleutel.** De G7 vereist een "Elliptic
Curve Password-Authenticated Key Exchange" (curve secp256r1): telefoon en
sensor bewijzen elkaar dat ze de 4-cijferige koppelcode kennen zonder die
ooit over de lucht te versturen, en leiden daarna een gedeelde AES-sleutel
af. Fundamenteel anders dan G6's simpele, uit de transmitter-ID afgeleide
vaste sleutel (DexcomG6Crypto.kt) — vandaar een volledig los pakket
(`sensor/dexcomg7/`) i.p.v. een uitbreiding van de bestaande G6-klassen.

**Gebouwd (nieuw pakket `sensor/dexcomg7/`):**
- `DexcomG7Crypto.kt` — Kotlin-vertaling van `libkeks`'s `Curve`/`KeyPair`/
  `JECPoint`/`Packet`/`Context`/`Calc`-klassen: de daadwerkelijke J-PAKE-
  wiskunde (drie ronden + zero-knowledge-bewijzen + sleutelafleiding).
  Bewust rechtstreekse Bouncy-Castle-klasse-aanroepen (nooit via JCE-
  providerregistratie) om Android's ingebouwde, naam-conflicterende eigen
  "BC"-provider te vermijden — hetzelfde patroon dat xDrip+ zelf gebruikt.
- `DexcomG7Protocol.kt` — BLE-UUID's (zelfde GATT-profiel als G5/G6, plus
  de nieuwe ExtraData-characteristic F8083538 voor de J-PAKE-rondepakketten),
  opcodes en pakket-op-/decodering voor de koppelhandshake en het
  glucoseverzoek/-antwoord (opcode 0x4E, letterlijk geport van xDrip+'s
  `EGlucoseRxMessage.java`).
- `DexcomG7Driver.kt` — implementeert `SensorDriver`, hergebruikt
  `DexcomG6Driver.kt`'s beproefde scan-/verbind-/backoff-skelet
  (`BondLossRecovery`, `ScanRateLimiter`, `PredictiveReconnectAlarm`,
  `AapsSlotSchedule`, dezelfde "sluit de verbinding na een geslaagde meting,
  kom voorspellend terug"-aanpak) en voegt de volledige J-PAKE-
  koppelhandshake toe als een lineaire `suspend`-functie (bewust eenvoudiger
  dan xDrip+'s generieke event-gedreven state machine — zelfde bytes op de
  lucht, simpeler Kotlin-controlestructuur, zie het bestand se kdoc voor de
  volledige afweging). Hergebruikt `DexcomG6CalibrationState` voor de
  glucose-bruikbaarheids-gate (G7's statusbyte deelt dezelfde onderliggende
  xDrip+-tabel als G5/G6).
- `ui/DexcomG7SetupScreen.kt` — invoerscherm voor de 4-cijferige koppelcode
  (mirror van `DexcomG6SetupScreen.kt`), plus navigatie-bedrading in
  `FclGlucoLinkNavHost.kt`/`SensorSelectionScreen.kt`.
- `data/AppSettings.kt` — nieuwe per-slot velden: koppelcode, laatst-
  verbonden-tijdstip.
- `sensor/SensorRegistry.kt`/`SensorDriver.kt` — `DEXCOM_G7` staat nu
  gewoon in `createDriver()` en heeft `implemented = true` — al vóór de
  eerste live-test, exact zoals destijds bij G6 (zie dat commentaar: "nog
  niet tegen een echte G6-transmitter geverifieerd — verwacht bijstellen na
  de eerste live-test"; die driver kreeg ook daadwerkelijk twee
  correctierondes ná de eerste live-test, dus realistisch om hier hetzelfde
  te verwachten).
- `app/build.gradle.kts` — `org.bouncycastle:bcprov-jdk15to18`/
  `bcpkix-jdk15to18:1.78.1` toegevoegd (dezelfde artefacten als xDrip+'s
  eigen `libkeks/build.gradle`).

**Bewust nog NIET geport** (zie de kdoc's in de genoemde bestanden voor de
volledige onderbouwing per stuk): de QR-code-certificaat-koppelroute
(alleen relevant als de PIN-route ondanks succesvolle authenticatie geen
OS-bond oplevert — xDrip+'s eigen bron behandelt dit al als secundair pad),
sessie-hervatting via een eerder opgeslagen gedeelde sleutel (performance-
optimalisatie, geen correctheids-vereiste), de inhoud van backfill-data na
opcode 0x59 (zelfs xDrip+'s eigen bron heeft dit nog niet volledig
uitgeplozen: letterlijk `// TODO more to parse here`), batterij-/versie-
polling (geen bevestigde G7-opcode gevonden), en een los G7-statusscherm
(tikken op de sensorkaart valt voorlopig terug op het sensorkeuzescherm).
Sessie starten/stoppen is bewust NIET nodig — G7/ONE+-sessies starten
automatisch bij het inbrengen van de sensor, zonder appcommando (xDrip+'s
eigen documentatie is hier expliciet over), in tegenstelling tot G6.

**Verificatie.** Balance-checker op alle nieuwe/gewijzigde bestanden. Elke
UUID/opcode/byte-indeling in `DexcomG7Protocol.kt` en de wiskunde in
`DexcomG7Crypto.kt` is regel-voor-regel geverifieerd tegen zowel xDrip+'s
`libkeks`/`g5model`/`cgm/dex/g7`-broncode als Juggluco's
`DexGattCallback.java` (twee onafhankelijke bronnen, dezelfde UUID's/
opcodes) — maar dit is, net als CareSens Air en G6 destijds, gebaseerd op
protocol-analyse en nog NIET tegen een echte G7/ONE+-sensor getest (de
gebruiker heeft er nog geen). Verwacht bijstelling na de eerste live-test.

Gewijzigd/nieuw: `sensor/dexcomg7/DexcomG7Crypto.kt`,
`sensor/dexcomg7/DexcomG7Protocol.kt`, `sensor/dexcomg7/DexcomG7Driver.kt`,
`ui/DexcomG7SetupScreen.kt`, `data/AppSettings.kt`,
`sensor/SensorRegistry.kt`, `sensor/SensorDriver.kt`,
`ui/FclGlucoLinkNavHost.kt`, `ui/SensorSelectionScreen.kt`,
`ui/ManualScreen.kt`, `app/build.gradle.kts`.

versionCode 122, versionName "0.9.25-dexcom-g7-driver".

## Ronde 113 (18/08/2026) — zichtbaarheid van de smoothing-pijplijn (raw/gekalibreerd/gefilterd)

**Aanleiding.** Een gesprek dat begon bij de vraag of de sterkte van de
break-in-filter/algemene Kalman-smoothing instelbaar moest worden (sterkte-
slider of "zwak"/"gemiddeld"/"sterk"-knoppen). Kernbezwaar tegen zo'n dial,
door de gebruiker zelf aangedragen: het effect ervan is nooit te beoordelen,
omdat er nergens op het scherm te zien is wat smoothing/kalibratie nu
eigenlijk met een meting doen. Dat werd het eigenlijke onderwerp: geen
sterkte-dial, maar zichtbaarheid van de bestaande verwerkingsstappen. Over
meerdere rondes heen is dit uitgekristalliseerd tot een concreet ontwerp
("Graag eerste je oordeel voor je code geeft", gevolgd door screenshot-
feedback en twee expliciete correcties, zie hieronder), afgesloten met "ja
graag".

**Ontwerp.**
- De bestaande raw-indicator in `BgRingDisplay` (kleine open cirkel + ruwe
  waarde in de grote cirkel, alleen zichtbaar bij `abs(raw - final) > 0.01`)
  is VERWIJDERD — dubbelop met de open-bolletjes-raw-lijn die
  `GlucoseChart.kt` al toont, én had een niet-instelbare, onzichtbare
  drempel.
- Nieuwe, generieke (sensor-onafhankelijke) regel onder de sensor-infokaart
  (`CompactSensorSummary`) toont ruw -> [gekalibreerd ->] gefilterd naast
  elkaar, links-naar-rechts = elke kolom het eindresultaat van weer één
  verwerkingsstap meer.
- Zichtbaarheid is UITSLUITEND gekoppeld aan een nieuwe Settings-schakelaar
  "Show filtered data on main screen", NIET aan of de waarden daadwerkelijk
  verschillen — een expliciete, bewuste afwijking van het "alleen tonen bij
  verschil"-patroon van de net verwijderde raw-indicator. Eerste voorstel
  (auto-tonen bij verschil) werd door de gebruiker afgewezen: dat zou
  precies dezelfde "onzichtbare drempel"-kritiek herintroduceren die eerder
  in hetzelfde gesprek al de kern van het bezwaar tegen een sterkte-slider
  was.
- De schakelaar is grijs/uit zodra smoothing zelf uit staat ("Als iemand
  smoothing uitzet dan moet het vinkje van het tonen ook gelijk grijs
  worden en moet hij uiteraard niet getoond worden") — zelfde
  uitgrijs-idioom (`enabled = smoothingEnabled` op de `Switch`, handmatige
  alpha op de labels) als Ronde 111's break-in-filter-schakelaar.
- Kalibreerd-kolom wordt alleen getoond als kalibratie ook daadwerkelijk aan
  staat (anders is die kolom toch gelijk aan de ruw-kolom, voegt niets toe).

**Architecturale gap die dit blootlegde.** `BleConnectionService.kt` paste
kalibratie en smoothing na elkaar toe via `.copy(glucoseMgdl = ...)`, zonder
de gekalibreerde-maar-nog-niet-gladgestreken tussenwaarde ooit te bewaren —
smoothing overschreef 'm gewoon. Nieuw veld `GlucoseReading.calibratedMgdl`
(zelfde default-patroon als het bestaande `rawSensorMgdl`) dicht dat gat:
`applyCalibrationIfEnabled()` zet het, `applySmoothingIfEnabled()` laat het
bewust ongemoeid staan.

**Gewijzigd:**
- `sensor/SensorDriver.kt` — nieuw veld `GlucoseReading.calibratedMgdl`.
- `sensor/ble/BleConnectionService.kt` — `applyCalibrationIfEnabled()` zet nu
  ook `calibratedMgdl`.
- `data/AppSettings.kt` — nieuwe app-brede (niet per-slot) toggle
  `showFilteredPipelineOnMainScreen` (`Keys.SMOOTHING_SHOW_PIPELINE_ON_MAIN_SCREEN`).
- `ui/SettingsScreen.kt` — nieuwe schakelaar "Show filtered data on main
  screen" in de Smoothing-kaart, uitgegrijsd zodra smoothing uit staat.
- `ui/StatusScreen.kt` — raw-indicator verwijderd uit `BgRingDisplay`, nieuwe
  `PipelineValuesRow`/`PipelineValueColumn`-composables, aangeroepen vanuit
  `SlotStatusContent` (dus automatisch ook op `CombiScreen.kt`'s tabbladen,
  die `SlotStatusContent` hergebruiken — geen aparte wijziging daar nodig).

**Verificatie.** Balance-checker op alle gewijzigde bestanden.

Gewijzigd: `sensor/SensorDriver.kt`, `sensor/ble/BleConnectionService.kt`,
`data/AppSettings.kt`, `ui/SettingsScreen.kt`, `ui/StatusScreen.kt`,
`app/build.gradle.kts`.

versionCode 123, versionName "0.9.26-pipeline-visibility".

## Ronde 114 (18/08/2026) — algemene smoothing-sterkte (zwak/gemiddeld/sterk)

**Verzoek.** Na Ronde 113's pijplijn-zichtbaarheid: "wat we nu nog niet
hebben is een algemene filtering sterkte 3 keuze schakelaar. onder de enable
smoothing die dan indien enable uitgeschakeld ook grijs wordt." — het idee
dat tijdens Ronde 113's ontwerpgesprek bewust was opengelaten (destijds
ontbrak nog een manier om het effect te beoordelen; die is er nu, met de
raw/gekalibreerd/gefilterd-regel).

**Ontwerp.** Nieuwe `enum class SmoothingStrength(qScale, displayLabel)` in
`smoothing/KalmanSmoother.kt`: WEAK (×1,8), MEDIUM (×1,0, default — exact het
bestaande gedrag), STRONG (×0,5). De schaal werkt in op de procesruis Q (niet
de meetruis R): hoger Q laat het filter een nieuwe meting sneller geloven
(minder gladstrijken), lager Q dwingt de toestand trager mee te bewegen (meer
gladstrijken). R bewust ongemoeid gelaten — R's grenzen/chi-kwadraat-drempel
zijn onderling afgestemd, en RONDE 111's inloop-demping werkt zelf al via R;
een schaal op Q blijft daar orthogonaal aan. Net als `breakInDecayFactor`
wordt de sterkte BUITEN de klasse gelezen (`AppSettings.smoothingStrength`)
en per meting aan `smooth()` meegegeven — een wijziging in Settings werkt zo
direct door, geen herkoppeling nodig.

**UI.** Nieuwe `SingleChoiceSegmentedButtonRow` "Filtering strength" direct
onder "Enable smoothing" in de Smoothing-kaart (zelfde opzet als
AlarmSettingsScreen.kt's escalatie-/alert-keuzes), `enabled = smoothingEnabled`
op elke `SegmentedButton` voor het gevraagde grijs-uit-gedrag.

**Gewijzigd:**
- `smoothing/KalmanSmoother.kt` — nieuwe `SmoothingStrength`-enum, `smooth()`
  krijgt een `strength`-parameter (default MEDIUM), past die toe op
  `qGlucose`/`qRate` op alle drie de plekken waar die gebruikt worden.
- `data/AppSettings.kt` — nieuwe app-brede toggle `smoothingStrength`
  (`Keys.SMOOTHING_STRENGTH`), zelfde onbekende-waarde-fallback-patroon als
  `calibrationMode`.
- `sensor/ble/BleConnectionService.kt` — `applySmoothingIfEnabled()` leest nu
  ook de sterkte en geeft die door.
- `ui/SettingsScreen.kt` — nieuwe segmented-button-rij.

**Verificatie.** Balance-checker op alle gewijzigde bestanden. Default MEDIUM
= schaal ×1,0, dus voor gebruikers die de knop niet aanraken verandert er
niets aan het al doorgerekende RONDE 109/111-gedrag.

Gewijzigd: `smoothing/KalmanSmoother.kt`, `data/AppSettings.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/SettingsScreen.kt`.

versionCode 124, versionName "0.9.27-smoothing-strength".

## Ronde 114b (18/08/2026) — verduidelijking "Filtering strength" op Settings

**Melding (met screenshot).** Na levering van Ronde 114: "nu kloppen de
titels en de begeleidende beschrijving niet, nu lijkt het net of de
filtering strength nieuwe sensors zwaarder filtert." Terecht — de nieuwe
"Filtering strength"-knoppenrij stond zonder eigen toelichting direct BOVEN
de bestaande break-in-filter-tekst ("Filters noisy rises more heavily right
after a new physical sensor is started..."), waardoor die tekst leek te
horen bij de sterkte-knoppen i.p.v. bij "Break-in filter for new sensors"
eronder.

**Fix.** `ui/SettingsScreen.kt`: een eigen toelichtende zin direct onder
"Filtering strength" ("How strongly ALL readings are smoothed, all the time
— separate from the break-in filter below...") plus een `HorizontalDivider()`
na de knoppenrij om de twee — verwante maar onafhankelijke — functies binnen
de Smoothing-kaart visueel te scheiden (zelfde idioom als CombiScreen.kt/
SimulatorSetupScreen.kt). Geen logica gewijzigd, puur de layout/tekst.

Gewijzigd: `ui/SettingsScreen.kt`.

versionCode 125, versionName "0.9.27b-smoothing-strength-clarity".

## Ronde 114c (18/08/2026) — consequente kopje/toelichting/switch-volgorde in de Smoothing-kaart

**Melding (met screenshot), vervolg op 114b.** "Het is een stuk verbeterd
maar kan volgens mij nog duidelijker als we de volgorde: Kopje (vet gedrukt),
uitleg en dan switch aanhouden. Het komt ook door de eerste woorden: 'Filters
noisy rise....' dat wekt de indruk dat het ergens op slaat wat daarvoor al
besproken is."

**Kern van het (herhaalde) probleem.** "Break-in filter for new sensors" en
"Show filtered data on main screen" toonden allebei hun toelichtende tekst
VÓÓR hun eigen kopje+switch-regel i.p.v. erna — dus leek de toelichting bij
het VORIGE blok te horen, exact dezelfde soort verwarring als 114b al
signaleerde bij "Filtering strength".

**Fix.** Alle drie de sub-secties in de Smoothing-kaart (Filtering strength /
Break-in filter for new sensors / Show filtered data on main screen) volgen
nu consequent dezelfde volgorde: vetgedrukt kopje (`fontWeight =
FontWeight.Bold`) -> toelichting -> schakelaar/besturing, elk gescheiden
door een `HorizontalDivider()`. De switch staat nu op een eigen regel
(rechts uitgelijnd), niet meer naast het kopje. "Enable smoothing" —de
kaart-brede hoofdschakelaar, geen van de drie sub-features— blijft bewust in
het bestaande kopje+switch-op-één-regel-patroon (zelfde als "Enable
calibration" in de Calibration-kaart), met zijn toelichting al gegeven via de
algemene kaart-intro bovenaan. Geen logica gewijzigd, puur layout/volgorde.

Gewijzigd: `ui/SettingsScreen.kt`.

versionCode 126, versionName "0.9.27c-smoothing-heading-order".

## Ronde 115 (20/08/2026) — universele, AAPS v3+v4-vertrouwde xDrip-broncode

**Verzoek + melding.** "Ik ben de app nu bij meer mensen aan het testen. Wat
nu opvalt is dat bij de v3 gebruikers de smb always niet werkt. Ik zit nu
zelf te denken om bij de settings een knop in te voeren die bij ingeschakeld
iedere sensor (ook de virtuele) een universele code mee geeft die zowel in
aaps 3 als 4 werkt (mag wat mij betreft gewoon een vertrouwde G6 code/
omschrijving zijn) en als hij is uitgeschakeld dan mag gewoon de best
kloppende omschrijving worden mee gestuurd." — met de aangeleverde
`uploads/XdripSourcePlugin AAPS V3.4.zip` en `... V4 dev.zip` (elk met
SourceSensor.kt, XdripSourcePlugin.kt, SafetyPlugin.kt, ConstraintsChecker.kt
e.a. — geen aannames, alle bestanden volledig gelezen) als bronmateriaal.

**Analyse.** "SMB Always" (`isAdvancedFilteringEnabled`) wordt in V3 en V4
op FUNDAMENTEEL verschillende manieren bepaald: V3's SafetyPlugin vraagt
`activePlugin.activeBgSource.advancedFilteringSupported()` op — bij
XdripSourcePlugin een gecachte boolean, gezet door `detectSource()` tegen
een hardcoded array in dat bestand zelf. V4's SafetyPlugin vraagt in plaats
daarvan `persistenceLayer.isAdvancedFilteringSupported()` op (V4's
XdripSourcePlugin heeft die gecachte boolean niet eens meer) — een
databank-brede check die de nieuwe, losstaande extensiefunctie
`SourceSensor.advancedFilteringSupported()` uit SourceSensorExtensions.kt
moet gebruiken. Ondanks dat mechanische verschil gaat het ons alleen om
WELKE `SourceSensor.text`-strings vertrouwd zijn: V3's hardcoded array
(AAPS-Dexcom/AAPS-DexcomG6/AAPS-DexcomG7/G6 Native/G7 Native/G7/Libre2/
Libre2 Native/Libre3) is een strikte deelverzameling van V4's set — dezelfde
negen, plus (nieuw) Syai Tag en Random.

Dat verklaart de melding exact: CareSens Air stuurde "Random" — in V4 sinds
kort wél vertrouwd, in V3 nooit, vandaar "random niet werkt" bij V3-
gebruikers. Accu-Chek/Simulator stuurden strings die in GEEN ENKELE
AAPS-versie een SourceSensor matchen (altijd UNKNOWN, nooit vertrouwd).
Alleen Dexcom G6 ("AAPS-Dexcom") en G7 ("G7") gebruikten al een waarde uit
de doorsnede van beide whitelists.

**Gekozen universele waarde: "AAPS-Dexcom".** Zit in de doorsnede van beide
whitelists, én is de enige van de negen kandidaten die al eerder live op een
toestel getest is en bevestigd werkte (RONDE 88) — een echte meting weegt
zwaarder dan de statische code-analyse alleen ("G6 Native" zag er op papier
ook goed uit maar gaf destijds op het toestel tóch "Unknown").

**Implementatie.** Nieuwe, app-brede Settings-schakelaar "Universal trusted
source code" in de Connection-kaart: AAN -> elke sensor (ook de simulator)
stuurt "AAPS-Dexcom"; UIT (default) -> ongewijzigd de bestaande, per-
sensortype best-passende omschrijving.

**Gewijzigd:**
- `data/AppSettings.kt` — nieuwe toggle `xdripUniversalSourceCodeEnabled`
  (`Keys.XDRIP_UNIVERSAL_SOURCE_CODE_ENABLED`).
- `broadcast/XDripBroadcaster.kt` — `sourceInfo()` krijgt een
  `universalSourceCode`-parameter, `broadcast()`/`buildBundle()` geven 'm
  door.
- `sensor/ble/BleConnectionService.kt` — leest de instelling en geeft 'm mee
  aan `XDripBroadcaster.broadcast()`.
- `ui/SettingsScreen.kt` — nieuwe schakelaar in de Connection-kaart, zelfde
  kopje/toelichting/switch-volgorde als RONDE 114c.

**Verificatie.** Balance-checker op alle gewijzigde bestanden. Nog niet
live getest tegen een echte AAPS v3.4-installatie — dat is aan de gebruiker
om te bevestigen bij de volgende testronde.

Gewijzigd: `data/AppSettings.kt`, `broadcast/XDripBroadcaster.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/SettingsScreen.kt`.

versionCode 127, versionName "0.9.28-xdrip-universal-source-code".

## Ronde 116 (20/08/2026) — duidelijke PIN-tekst bij CareSens Air-koppeling

**Melding.** Een tester (via de gebruiker doorgestuurde WhatsApp-schermen)
liep vast bij het koppelen van een nieuwe CareSens Air-sensor: Android's
EIGEN Bluetooth-koppelscherm suggereerde "probeer 0000 of 1234" als PIN,
maar dat is slechts een generieke gok van het besturingssysteem — de
daadwerkelijke PIN staat afgedrukt op de sensorverpakking ("PINCODE"/"CODE
PIN"). Meerdere mislukte pogingen, gevolgd door een deselecteer/opnieuw-
koppelen-cyclus, eindigden in een crash (nog niet gereproduceerd/opgelost,
apart traject — hier alleen de tekst-verduidelijking, letterlijk verzoek:
"pas in ieder geval de tekst maar aan dat die duidelijk naar de pincode op
de verpakking wijst").

**Bevinding.** De juiste PIN werd al uit de barcode gehaald bij het scannen
(`CareSensAirBarcode.kt`, AI 240) en opgeslagen in Settings
(`AppSettings.saveCareSensAirScan`) — maar nergens in de UI ooit getoond.
De gebruiker moest 'm dus zelf van het fysieke etiket aflezen, met alle
verwarring van dien tussen de PIN, de sensorcode en het serienummer die
allemaal op hetzelfde etiket staan.

**Fix (drie plekken, geen logica gewijzigd):**
- `ui/CareSensAirScanScreen.kt` — na een geslaagde scan wordt de PIN nu
  gewoon getoond (nieuwe "PIN code"-regel naast Sensor code/Serial/
  Expires), met een tekst die expliciet zegt: gebruik DEZE waarde in de
  komende stap, niet wat Android zelf voorstelt.
- `ui/PairingScreen.kt` — dezelfde herinnering verschijnt nogmaals vlak
  vóór de apparatenlijst, op het moment dat een tik op een apparaat
  Android's eigen koppeldialoog daadwerkelijk opent (alleen zichtbaar voor
  CareSens Air, alleen als er een scanresultaat voor deze slot bekend is).
- `ui/ManualScreen.kt` — de CareSens Air-alinea benoemt nu expliciet de
  PIN-stap en dezelfde waarschuwing.

**Verificatie.** Balance-checker op alle gewijzigde bestanden. De crash die
in dezelfde melding werd gerapporteerd is NIET onderdeel van deze ronde —
daarvoor is een stacktrace/logbestand van de tester nodig, nog niet
ontvangen.

Gewijzigd: `ui/CareSensAirScanScreen.kt`, `ui/PairingScreen.kt`,
`ui/ManualScreen.kt`.

versionCode 128, versionName "0.9.29-caresens-pin-clarity".

## Ronde 117 (20/08/2026) — opvallende PIN-kaart bij CareSens Air-koppeling

**Aanleiding.** De gebruiker kon de Ronde 116-tekst zelf niet testen (geen
nieuwe sensor voorhanden om te koppelen). Op verzoek eerst een mockup
getoond (huidige stand vs. een opvallender alternatief) — de gebruiker koos
voor het alternatief, op beide schermen.

**Fix.** De PIN stond in Ronde 116 als gewone tekstregel tussen de andere
velden (`CareSensAirScanScreen.kt`) resp. als kleine secondary-tekst boven
de apparatenlijst (`PairingScreen.kt`) — makkelijk over het hoofd te zien.
Beide plekken tonen de PIN nu in een eigen `tertiaryContainer`-kaart
(Material3's "let op dit"-kleur, past zich automatisch aan het dark theme
aan) met een sleutel-icoon en de PIN in `headlineSmall`-grootte, gevolgd
door dezelfde waarschuwingstekst als voorheen. Geen logica gewijzigd, puur
visuele nadruk.

**Verificatie.** Balance-checker op beide gewijzigde bestanden.

Gewijzigd: `ui/CareSensAirScanScreen.kt`, `ui/PairingScreen.kt`.

versionCode 129, versionName "0.9.30-caresens-pin-card".

## Ronde 118 (21/08/2026) — 2 decimalen op de pijplijn-rij

**Aanleiding.** Op de nieuwe telefoon viel op dat Raw/Calibrated/Filtered op
de pijplijn-rij (Ronde 113) continu identiek leken, terwijl de sensor
(bijna aan het einde van zijn levensduur) toch behoorlijk springt. Kalibratie
staat wel aan, maar zonder nieuwe vingerprik sinds herstart is Raw=Calibrated
inderdaad verwacht — de vraag ging over Calibrated vs. Filtered.

**Analyse.** Bij mmol/L is 1 decimaal ≈ 1,8 mg/dL per stap. Het Kalman-filter
corrigeert vaak in kleinere stappen dan dat, zeker bij "Medium" sterkte — het
verschil is er dus wellicht wél, maar wordt na afronding op 1 decimaal
onzichtbaar. Genoemd, maar vermoedelijk niet de hoofdoorzaak: de "sensor
gestart op"-tijdstempel voor het inloop-filter (Ronde 111) is na de
telefoonwissel gereset, waardoor die de laatste uren juist harder had moeten
dempen (het tegenovergestelde effect van "gelijk aan elkaar").

**Fix.** Nieuwe `Double.formatForDisplayPrecise()` in `ui/Units.kt`
(mg/dL: 1 decimaal, mmol/L: 2 decimalen) — uitsluitend gebruikt door
`StatusScreen.kt`'s `PipelineValueColumn`, de hoofdcirkel/grafiek/rest van de
app blijven op de bestaande, bewust afgeronde weergave.

**Verificatie.** Balance-checker op beide gewijzigde bestanden.

Gewijzigd: `ui/Units.kt`, `ui/StatusScreen.kt`.

versionCode 130, versionName "0.9.31-pipeline-precision".

## Ronde 119 (21/08/2026) — BUGFIX: "Calibrated" op de pijplijn-rij was in werkelijkheid altijd Filtered

**Melding.** Zelfs met 2 decimalen (Ronde 118) bleven Calibrated en Filtered
tot op de honderdste identiek, terwijl kalibratie aanstond. Screenshots van
het Calibration-scherm bevestigden: 0 vingerprik-entries, handmatige offset
+0,00 — kalibratie is in dit geval dus terecht een no-op (Calibrated hoort
gelijk te zijn aan Raw, niet aan Filtered).

**Root cause.** `GlucoseReading.calibratedMgdl` (Ronde 113) werd nooit
opgeslagen: `GlucoseReadingEntity` had er simpelweg geen kolom voor.
`GlucoseReadingStore` schrijft/leest élke weergegeven meting via Room (ook
"de laatste meting" op het startscherm) — bij het terugbouwen viel
`toReading()` daardoor steeds terug op `GlucoseReading`'s klasse-default
(`calibratedMgdl = glucoseMgdl`, oftewel de FINALE, al-gesmoothde waarde).
De "Calibrated"-kolom liet dus bij elke lezing gewoon Filtered nogmaals
zien onder het verkeerde label — vandaar dat ze nooit uit elkaar liepen,
ongeacht wat kalibratie/smoothing werkelijk deden. Het zichtbare verschil
tussen Raw en die kolom was in werkelijkheid het Raw-vs-Filtered-verschil
(dus door smoothing, niet door kalibratie).

**Fix.** Nieuwe nullable `calibratedMgdl`-kolom op `glucose_readings`
(`GlucoseReadingEntity.kt`, MIGRATION_6_7 in `FclGlucoLinkDatabase.kt`,
databaseversie 6 → 7 — zelfde "ALTER TABLE i.p.v. destructive migration"-
patroon als de eerdere migraties). `toEntity()`/`toReading()` geven het veld
nu door; bestaande rijen van vóór deze ronde krijgen `null` en vallen terug
op `glucoseMgdl` (functioneel identiek aan "geen kalibratie toegepast",
wat voor die oude rijen sowieso niet meer te reconstrueren was).

**Verificatie.** Balance-checker op beide gewijzigde bestanden. Room-DAO's
gebruiken overal `SELECT *`, dus geen query-wijzigingen nodig.

Gewijzigd: `data/GlucoseReadingEntity.kt`, `data/FclGlucoLinkDatabase.kt`.

versionCode 131, versionName "0.9.32-calibrated-column-fix".

## Ronde 120 (22/08/2026) — G6 "Start new sensor" blijft mislukken: verkeerde reden getoond + "blackbox"-klacht

**Melding.** "Start new sensor" op een Anubis-kloon-transmitter bleef
mislukken: telkens "Sensor already active?" → stop → "Sending sensor
start…" → na 2 pogingen weer de rejected-melding, twee keer herhaald zonder
succes. Verzoek: uitzoeken wat er misgaat, EN sowieso meer status-info
tonen ("het is nu een beetje een blackbox"). Later bleek ook: er kwamen
gewoon glucosewaarden binnen terwijl het scherm nog "Sending sensor
start…" toonde — verwarrend, leek tegenstrijdig.

**Bevinding 1 — de getoonde reden was feitelijk onjuist.** De vaste tekst
"transmitter may still see the old sensor as active" gaat uit van infoCode
0x02. Het meegestuurde logbestand liet zien dat de transmitter bij ELKE
poging infoCode **3** teruggaf. Geverifieerd tegen xDrip+'s eigen
`SessionStartRxMessage.message()` (`uploads/xDrip-2026.08.08.zip`): info
0x03 betekent **"Invalid"**, niet "already active" — een andere, tot nu toe
onvertaalde betekenis. Vermoedelijke praktische verklaring (niet 100%
zeker): de vaste 1500ms-pauze tussen stop en start (Ronde 71, getuned op
een andere transmitter) is voor deze Anubis-kloon te kort — het log laat
zien dat de transmitter, zonder ooit een geslaagde nieuwe start te
bevestigen, na verloop van tijd gewoon weer op eigen houtje metingen ging
rapporteren (mogelijk van de OUDE, nooit volledig afgesloten sessie).

**Bevinding 2 — "blackbox".** `runControlSequence()` vraagt ALTIJD een
glucosewaarde op, los van of de sessie-start-substap net gelukt/mislukt
is (zie DexcomG6Driver.kt's eigen kdoc: "...dan ALTIJD een glucosewaarde
opvragen"). Vandaar dat er data kan binnenkomen terwijl de "Sensor
start"-substap intern nog vastzit — geen tegenstrijdigheid, maar tot deze
ronde ook nergens zichtbaar gemaakt.

**Fix (geen wijziging aan de retry-timing zelf — te veel giswerk zonder
harder bewijs, wél alle beschikbare info zichtbaar):**
- `sensor/dexcomg6/DexcomG6Protocol.kt` — nieuwe `sessionStartInfoMessage(infoCode)`,
  dezelfde indeling als xDrip+'s `message()`.
- `data/AppSettings.kt` — nieuwe per-slot `dexcomG6LastSessionStartInfoCode`
  (gewist bij succes) en `dexcomG6LastSessionStartAttemptAtMs` (blijft
  staan, ook na succes).
- `sensor/dexcomg6/DexcomG6Driver.kt` — `runControlSequence()` schrijft nu
  bij elke sessie-start-poging (geslaagd of niet) het tijdstip weg, en bij
  een mislukking de rauwe infoCode.
- `ui/DexcomG6StatusScreen.kt` — `dexcomG6StatusText()` toont nu de ECHTE
  reden (via `sessionStartInfoMessage()`) + "last tried HH:mm", en — als
  van toepassing — "readings are still coming in from the transmitter's
  current session" wanneer er ná de laatste startpoging alsnog een echte
  meting binnenkwam (leest `GlucoseReadingStore.latestReading()`, nieuw
  hier gebruikt).

**Verificatie.** Balance-checker op alle vier gewijzigde bestanden.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt`, `data/AppSettings.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`, `ui/DexcomG6StatusScreen.kt`.

versionCode 132, versionName "0.9.33-g6-sensor-start-diagnostics".

## Ronde 121 (22/08/2026) — G6 stop/start opgesplitst in aparte verbindcycli + sessie-starttijd via de transmitter zelf

**Verzoek.** Twee vragen n.a.v. Ronde 120: (1) "of de stop knop niet
gewoon minder opvallend kan en dat we alleen een start sensor knop
gebruiken die gewoon eerst checkt of er een actieve sensor is [...] dan
zelf automatisch het stop commando zend. en bij de volgende cycles 5
minuten later het start commando"; (2) "of uit de transmitter ook het
start tijdstip valt af te leiden want dat staat nu weer [...] leeg".

**Onderzoek.** xDrip+'s eigen bronbestanden (`uploads/xDrip-2026.08.08.zip`)
kennen een derde, onafhankelijke opcode naast SessionStart/-Stop:
`TransmitterTimeTxMessage`/`-RxMessage` (0x24/0x25) — een simpele
klok-aanvraag die naast de HUIDIGE transmittertijd ook het startmoment van
een eventuele lopende sessie teruggeeft, LOS van of de vragende app die
sessie zelf ooit bevestigd kreeg. `Ob1G5StateMachine.java` gebruikt precies
dit mechanisme (`DexSessionKeeper.setStart(txtime.getRealSessionStartTime())`)
om een sessie-starttijd te herstellen — dezelfde, bewezen aanpak hier
overgenomen.

**Root cause (nu wél concreet, met bewijs).** De oude "stop-before-start"-
combo (Ronde 66/71/120) stuurde Stop ÉN Start binnen DEZELFDE BLE-
verbindcyclus met maar een willekeurige 1500ms-pauze ertussen — de meest
waarschijnlijke verklaring voor de herhaalde infoCode=3 "Invalid"-
afwijzingen op de Anubis-kloon-transmitter van de gebruiker.

**Fix.**
- `sensor/dexcomg6/DexcomG6Protocol.kt` — nieuw: `buildTransmitterTimeRequest()`
  (opcode 0x24), `TransmitterTimeRx` (met `sessionInProgress`/
  `realSessionStartAtMs()`) en `parseTransmitterTime()` (opcode 0x25).
- `sensor/dexcomg6/DexcomG6Driver.kt` — `runControlSequence()`
  herontworpen: vraagt nu, zodra een nieuwe-sensor-code klaarstaat ÓF de
  lokale sessie-starttijd nog onbekend is, ÉÉN TransmitterTime op per
  cyclus. Meldt de transmitter een lopende sessie: stuurt ALLEEN de Stop,
  verbreekt de verbinding, en verstuurt de Start BEWUST pas bij de
  eerstvolgende, natuurlijke ~5-minuten-herverbinding (zelf-corrigerend elke
  cyclus — geen los "moet ik nog stoppen?"-vlaggetje meer nodig, de oude
  PendingStopBeforeStart-mechaniek in AppSettings.kt blijft ongebruikt
  staan voor deze flow). Vult `sessionStartConfirmedAtMs` nu ook vanuit de
  transmitter's eigen klok, ongeacht of een eigen SessionStart ooit
  bevestigd werd — en wist 'm als de transmitter juist meldt dat er niets
  (meer) loopt (stale-detectie).
- `ui/DexcomG6NewSensorScreen.kt` — de handmatige "Sensor already
  active?"-bevestigingsdialoog is vervallen: de app checkt en stopt nu
  zelf automatisch, geen aparte gebruikersbevestiging meer nodig.
- `ui/DexcomG6StatusScreen.kt` — "Stop sensor" van `OutlinedButton` naar
  `TextButton` (minder opvallend — nu vooral een noodgreep, niet meer het
  normale pad om een nieuwe sensor te starten). Nieuwe tussenstatus
  "Automatically stopping the previous sensor session (stopped HH:mm) —
  the new sensor starts on the next connection (~5 min)" i.p.v. het
  misleidende "Sending sensor start…" tijdens dat tussenmoment (nieuw
  per-slot veld `dexcomG6LastAutoStopAtMs` in `data/AppSettings.kt`).
  "Started"/"End (est.)" op het sensor-infotabelletje profiteren
  automatisch mee van de rijkere `sessionStartConfirmedAtMs` — geen aparte
  UI-wijziging nodig. "Code" blijft bewust "—" zonder een geslaagde eigen
  SessionStart: de transmitter echoot de sensorcode nooit terug, dat is
  met geen enkele aanvraag te achterhalen.

**Verificatie.** Balance-checker op alle vijf gewijzigde bestanden.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
`data/AppSettings.kt`, `ui/DexcomG6NewSensorScreen.kt`, `ui/DexcomG6StatusScreen.kt`.

versionCode 133, versionName "0.9.34-g6-transmitter-time-split-start".

## Ronde 122 (22/08/2026) — CRITICAL: kalibratie-curve van de vorige sensor bleef actief na een nieuwe-sensor-start

**Melding.** "Het viel me op dat de calibratie curve van de vorige sensor
nog steeds actief was nadat deze was gestart. Wordt de calibratie wel
gereset na een stop en/of start van een nieuwe sensor?"

**Root cause.** `applyCalibrationIfEnabled()` (BleConnectionService.kt, de
LIVE pipeline die daadwerkelijk elke meting kalibreert) en
CalibrationScreen.kt's `sinceMs` gebruikten allebei
`settings.getOrInitSensorStartedAtMs(slot)` om te bepalen welke
vingerprikken meetellen voor de fit-curve. Die sleutel wordt echter
UITSLUITEND gewist bij een sensor-TYPE-wissel (bijv. CareSens Air → Dexcom
G6, zie `setSelectedSensor()`'s kdoc) — NIET bij het starten van een
nieuwe FYSIEKE sensor van hetzelfde type. Gevolg: na het stoppen van de
oude en starten van een nieuwe G6-sensor bleven vingerprikken (en dus de
fit-curve) van de VORIGE sensor gewoon meewegen — precies de gemelde bug.
Interessant genoeg had het inloopfilter (Ronde 111,
`computeBreakInDecayFactor()`) dit al wél goed: die gebruikte al bij
voorkeur de sensortype-specifieke, wél-per-fysieke-sensor herziene
starttijden (Dexcom G6's `dexcomG6SessionStartConfirmedAtMs`, CareSens
Air's `careSensAirSensorStartedAtMs`) — de kalibratie-toepassing zelf volgde
dat patroon alleen nooit.

**Fix.** Die voorkeursvolgorde gecentraliseerd in één nieuwe
`AppSettings.effectiveSensorSessionStartedAtMs(slot, sensorType)` (suspend)
+ `effectiveSensorSessionStartedAtMsFlow(slot, sensorType)` (passieve
Flow-variant): sensortype-specifieke starttijd als die bestaat (Dexcom
G6/CareSens Air), anders de generieke `getOrInitSensorStartedAtMs`-vangnet
(simulator, Dexcom G7 heeft nog geen eigen tracking). Gebruikt nu door:
- `sensor/ble/BleConnectionService.kt` — `applyCalibrationIfEnabled()`
  (de eigenlijke bugfix) én `computeBreakInDecayFactor()` (nu ontdubbeld
  i.p.v. de eigen inline-kopie van dezelfde logica).
- `ui/CalibrationScreen.kt` — `sinceMs` (zodat de rijlijst/fit-grafiek die
  de gebruiker ziet altijd matcht met wat er live wordt toegepast). Ook de
  verouderde kdoc gecorrigeerd die nog beweerde dat kalibratiedata
  automatisch gewist wordt bij een nieuwe sessie — dat is sinds Ronde 90
  niet meer zo (filteren op `sinceMs` i.p.v. wissen).
- `ui/CombiScreen.kt` — de fingerstick-markers op de combi-grafiek van
  beide slots, voor consistentie met de andere twee.

**Verificatie.** Balance-checker op alle vier gewijzigde bestanden.

Gewijzigd: `data/AppSettings.kt`, `sensor/ble/BleConnectionService.kt`,
`ui/CalibrationScreen.kt`, `ui/CombiScreen.kt`.

versionCode 134, versionName "0.9.35-calibration-stale-sensor-fix".

## Ronde 123 (22/08/2026) — CRITICAL: G6 auto-stop-detectie liep vast in een oneindige reconnect-storm

**Melding.** Direct na het uitbrengen van Ronde 121 (automatische stop/
start-detectie via TransmitterTime): "Ik kan niet zien wat er gebeurt,
maar werken doet het niet. [...] er komt nu al meer dan 25 minuten alleen
sending sensor start op het hoofdscherm en iedere keer [...] een stopped
tijd." Meegestuurde logcat (19:53:35–19:54:11) liet ELKE ~6-10 seconden
een volledige connect→auth→TransmitterTime→SessionStop→disconnect-cyclus
zien, telkens met `TransmitterTimeRx(currentTime=1159280..1159310,
sessionStartTime=0)` — ook meteen NA een bevestigd geslaagde
`SessionStopRx(ok=true, ...)`.

**Root cause 1.** `TransmitterTimeRx.sessionInProgress` (Ronde 121) gebruikte
`sessionStartTime != -1`, gekopieerd van xDrip+'s eigen `-1`-sentinel voor
"geen sessie". Deze specifieke Anubis-kloon-transmitter rapporteert echter
kennelijk **0**, niet -1, zodra er geen sessie loopt — met de oude check
werd dat gelezen als "sessie gestart op transmitter-tijdstip 0", altijd
ongelijk aan de grote, oplopende huidige transmitter-tijd, dus
`sessionInProgress` bleef voor eeuwig `true`. Gevolg: elke cyclus concludeerde
opnieuw "er loopt nog een sessie", stuurde opnieuw een Stop, en verbrak de
verbinding — ook al was de vorige sessie allang gestopt.

**Root cause 2 (versterkend).** De auto-stop-en-verbreek-tak keert terug
zonder ooit de verplichte glucose-aanvraag verderop in `runControlSequence()`
te bereiken, dus `lastSuccessfulConnectionAtMs` werd nooit bijgewerkt.
`onConnectionStateChange()`'s STATE_DISCONNECTED-afhandeling gebruikt die
waarde om te bepalen of de zojuist afgesloten cyclus een "succes"
(voorspellende ~5-minuten-cooldown) of een "mislukking" (oplopende
foutenbackoff, 1–10s) was — zonder de fix hieronder werd een BEWUSTE,
geslaagde auto-stop dus altijd als mislukking behandeld, wat de
waargenomen ~6-10s-reconnect-storm gaf i.p.v. de bedoelde ~5 minuten.

**Fix.**
- `sensor/dexcomg6/DexcomG6Protocol.kt` — `sessionInProgress` nu
  `sessionStartTime > 0 && currentTime != sessionStartTime` (sluit zowel
  -1 als 0 uit). Nog NIET geverifieerd of deze transmitter een zinvolle,
  positieve sessionStartTime teruggeeft wanneer er WEL een sessie loopt —
  dat vereist een volgende live-test met een daadwerkelijk actieve sessie.
  Als dat niet zo blijkt: onschadelijk gedrag (detectie werkt dan simpelweg
  niet, geen valse stops).
- `sensor/dexcomg6/DexcomG6Driver.kt` — de auto-stop-tak zet nu zelf
  `lastSuccessfulConnectionAtMs`/`cadenceAnchorAtMs` (exact zoals
  `handleGlucoseResult()` dat al deed), zodat zo'n cyclus als geslaagd
  meetelt voor de reconnect-cooldown i.p.v. als mislukking.

**Verificatie.** Balance-checker op beide gewijzigde bestanden. Root cause
1 is met hoge zekerheid vastgesteld uit de logcat (sessionStartTime=0 op
ELKE cyclus, ook direct na een bevestigde stop — kan onmogelijk een echte
sessie-start zijn); root cause 2 is afgeleid uit `onConnectionStateChange()`/
`computeReconnectCooldownMs()`'s eigen, hierboven aangehaalde logica.

Gewijzigd: `sensor/dexcomg6/DexcomG6Protocol.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`.

versionCode 135, versionName "0.9.36-g6-transmitter-time-zero-fix".

## Ronde 124 (22/08/2026) — G6: inconsistente statusteksten gefixt + Started/End-terugval + poging dexTime-fix

**Melding.** Na Ronde 123's fix liep de reconnect-cadans weer normaal
(bevestigd: `computeReconnectCooldownMs: [...] cooldownMs=240193`), maar
de daadwerkelijke SessionStart bleef falen met infoCode=3 "Invalid" — nu
zelfs op een verse poging, ZONDER dat er in diezelfde cyclus net een stop
gebeurd was (TransmitterTime meldde al vooraf "geen sessie"). Daarnaast:
"de info die terug komt klopt niet" — het compacte statuskaartje op
StatusScreen.kt toonde "no response from the transmitter (timeout)"
terwijl het volle G6-statusscherm gelijktijdig de ECHTE reden ("invalid")
toonde. En het expliciete verzoek: als de starttijd niet uit de
transmitter komt, gebruik dan de starttijd (en bijbehorende eindtijd) van
het moment waarop de sensorcode is ingevoerd.

**Bevinding 1 — inconsistente statusteksten, bug uit Ronde 120.** StatusScreen.kt's
compacte kaartje (`CompactSensorSummary`) miste de drie in Ronde 120
toegevoegde parameters (`lastSessionStartInfoCode`/
`lastSessionStartAttemptAtMs`/`lastRealReadingAtMs`) bij zijn eigen
`dexcomG6StatusText()`-aanroep — die vielen terug op hun `null`-default,
dus toonde dit kaartje altijd de generieke "timeout"-tekst, ongeacht de
ECHTE infoCode. Gefixt: dezelfde drie parameters nu ook hier doorgegeven
(mirror van DexcomG6StatusScreen.kt's eigen aanroep).

**Bevinding 2 — Started/End-terugval.** Nieuw: `AppSettings.
dexcomG6PendingNewSensorCodeQueuedAtMs` (per slot, gezet zodra een nieuwe
code klaargezet wordt, gewist bij succes/handmatig stoppen). DexcomG6StatusScreen.kt's
"Started"/"End (est.)" vallen nu op dit moment terug wanneer er nog geen
ECHTE (transmitter-bevestigde) starttijd is — duidelijk gelabeld
"(est., unconfirmed)". Bewust NIET gebruikt voor de warmup-aftelling of
het inloopfilter (die blijven op de bevestigde waarde varen, zie
DexcomG6StatusScreen.kt's kdoc voor de volledige afweging: een ongeldige
schatting mag geen harde tijdsberekening aansturen, vooral omdat de
onderliggende meetdata bij een mislukte start feitelijk nog van de OUDE
sensor komt).

**Bevinding 3 — mogelijke ECHTE oorzaak van infoCode=3, POGING (nog niet
bevestigd).** `buildSessionStart()`'s `dexTime`-parameter (transmitter-
relatieve tijd sinds activatie, NIET Unix-tijd) stond al sinds de
oorspronkelijke implementatie hardcoded op `0`. Deze transmitter loopt
inmiddels >1,1 miljoen seconden (~13 dagen) — een dexTime van 0 claimt dan
een sessie "vanaf transmitter-activatie", ver in het (transmitter-interne)
verleden, wat een plausibele verklaring is voor "Invalid". Omdat er
sowieso al een TransmitterTime-aanvraag vooraf gaat (Ronde 121), is de
ECHTE transmitter-relatieve tijd nu al beschikbaar zonder extra BLE-
round-trip — die wordt nu gebruikt i.p.v. de vaste 0 (terugval blijft 0 als
TransmitterTime geen antwoord geeft, geen regressie). Expliciet NIET als
zekere oplossing gepresenteerd — vereist een volgende live-test.

**Verificatie.** Balance-checker op alle vier gewijzigde bestanden.

Gewijzigd: `ui/StatusScreen.kt`, `data/AppSettings.kt`,
`ui/DexcomG6StatusScreen.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`.

versionCode 136, versionName "0.9.37-g6-dextime-and-started-fallback".

## Ronde 125 (24/08/2026) — break-out filter voor verouderende sensoren

**Aanleiding.** Community-meldingen dat CareSens Air-sensoren de laatste
dagen van hun looptijd weer instabiel worden. Verzoek: "een breakout filter
wat eigenlijk precies omgekeerd werkt tov de breakin [...] boven op de basis
(ongeacht welke stand gekozen is) en even sterk als break in dus in
principe een omgekeerde kopie." Na doorvragen over filterrichting: "Nu, dit
lezende denk ik toch alleen beide op de stijging, en de dalingen als die
verdacht ogen" — dus stijgingen altijd dempen (zoals break-in), dalingen
alleen zolang ze nog "verdacht" (onbevestigd) zijn, en die uitbreiding geldt
dan ook terug voor break-in zelf.

**KalmanSmoother.kt.** `smooth()` krijgt een tweede, gelijkwaardige
parameter `breakOutDecayFactor` naast het bestaande `breakInDecayFactor`.
Beide worden gecombineerd tot één `edgeStrength` (het maximum van de twee —
ze horen bij niet-overlappende delen van de looptijd, begin resp. eind) en
delen vanaf daar letterlijk dezelfde twee ingrepen
(`breakInExtraRMgdlSq`/`breakInThresholdBoost`) als de bestaande
inloop-demping — een spiegelkopie in tijd, geen los mechanisme met eigen
constantes. Tweede wijziging: naast stijgingen dempen beide edge-filters nu
ook "verdachte" dalingen — een dalende afwijking die de bestaande
2-van-3-tekenbevestiging (`sameSignCount`/`qInflateAllowed`, RONDE 109) nog
niet gehaald heeft. Zodra 2 van de laatste 3 grote afwijkingen dezelfde
dalende richting bevestigen, is de daling niet langer "verdacht" en loopt
'ie ongedempt door — een echte, aanhoudende daling (mogelijke hypo) wordt
zo nooit langer dan de eerste, nog onbevestigde meting vertraagd, wat de
SMB-veiligheidsafweging uit de klasse-kdoc intact houdt.

**AppSettings.kt.** Nieuwe, app-brede sleutels
`SMOOTHING_BREAK_OUT_FILTER_ENABLED`/`SMOOTHING_BREAK_OUT_FILTER_DURATION_HOURS`
(spiegelbeeld van de bestaande break-in-sleutels, default UIT / 48u). Nieuw,
PER-SLOT `dexcomG6ExpectedLifespanDays(slot)` (default 14 dagen) — alleen
relevant voor een G6 met Anubis-transmitter, waarvan de eigen
`typicalSensorDays` niet te vertrouwen is (kan tot 60 dagen melden).

**BleConnectionService.kt.** Nieuwe `computeBreakOutDecayFactor()`,
spiegelbeeld van `computeBreakInDecayFactor()`: telt uren TOT een geschat
einde i.p.v. uren SINDS de start, zelfde exponentiële opbouw (τ = duur/5).
De geschatte einddatum is per sensortype bepaald: CareSens Air vaste 15
dagen (zelfde constante als CareSensAirStatusScreen.kt's "End (est.)"),
Dexcom G6 Original het transmitter-eigen `typicalSensorDays` (betrouwbaar
voor die hardware), Dexcom G6 Anubis de nieuwe handmatig ingestelde
`dexcomG6ExpectedLifespanDays`. G7/ONE+ en de simulator bewust nog buiten
scope (expliciete keuze: "CareSens Air + G6").

**SettingsScreen.kt.** Nieuwe "Break-out filter for aging sensors"-sectie,
zelfde kopje/toelichting/switch/duur-opzet als break-in. Duration-Slider
(0-96u) bewust rechts-naar-links getekend via een
`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`
om ALLEEN de Slider (niet de labels ernaast) — op uitdrukkelijk verzoek,
zodat "langer maken" ook visueel naar links trekken is, als aanwijzing dat
deze duur vanaf het EINDE terugtelt i.p.v. vanaf het begin optelt.

**DexcomG6StatusScreen.kt.** Nieuw "Expected sensor lifespan"-veld (Slider,
7-30 dagen, default 14), alleen zichtbaar wanneer deze slot's transmitter
als Anubis herkend is (`DexcomG6TransmitterType.fromTypicalSensorDays()`).

**Verificatie.** Balance-checker op alle vijf gewijzigde bestanden. Enige
bestaande aanroeper van `KalmanSmoother.smooth()`
(BleConnectionService.kt) gecontroleerd en bijgewerkt naar de nieuwe
parametervolgorde.

Gewijzigd: `smoothing/KalmanSmoother.kt`, `data/AppSettings.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/SettingsScreen.kt`,
`ui/DexcomG6StatusScreen.kt`.

versionCode 137, versionName "0.9.38-breakout-filter".

## Ronde 126 (27/08/2026) — crash naar diagnose-logboek geschreven vóór proces-dood

**Aanleiding.** Analyse van drie door Rick gedeelde logbestanden (24/25/26-8)
na een melding "de telefoon gaf aan dat fclglucolink een bug bevat". Geen van
de drie bevatte ook maar één spoor van een crash — logisch, want een crash
killt het proces voordat het bestaande, `Instellingen -> Diagnose-logboek`-
schakelaar-afhankelijke logpad iets had kunnen wegschrijven. Het enige
zichtbare spoor in de logs was een gat in de tijdlijn: op 26-8 stopt alle
BLE-communicatie om 17:10:25 abrupt midden in een succesvolle lezing, zonder
de gebruikelijke `STATE_DISCONNECTED`-regel; om 18:02:18 hervat de
communicatie met een complete nieuwe scan/handshake — het patroon van een
proces dat crasht en herstart, niet van een normale BLE-hapering (die de app
altijd netjes zelf logt). Verzoek na deze analyse: "bouw dat maar."

**DiagnosticFileLogger.kt.** Nieuwe `logFatal(thread, throwable)` — schrijft
de VOLLEDIGE stacktrace naar hetzelfde dagbestand (`fclglucolink_yyyy-MM-
dd.txt`), bewust ONAFHANKELIJK van de `enabled`-schakelaar (in tegenstelling
tot `log()`/`logError()`): een crash is precies het soort gebeurtenis
waarvoor je de informatie wilt hebben, ook als de gebruiker het diagnose-
logboek nooit bewust heeft aangezet. In een `runCatching` — een fout hierin
mag nooit de eigenlijke crash-afhandeling blokkeren.

**FclGlucoLinkApp.kt.** Nieuwe `installCrashLogging()`, aangeroepen vanuit
`onCreate()`: registreert een globale `Thread.UncaughtExceptionHandler` die
bij een crash eerst `DiagnosticFileLogger.logFatal()` aanroept en dan ALTIJD
doorgeeft aan de oorspronkelijke handler (of, als die ontbreekt, het proces
alsnog netjes beëindigt) — de crash wordt dus nooit onderdrukt, alleen eerst
waargenomen, zodat Android's eigen crash-afhandeling normaal blijft werken.

**Zijvraag beantwoord (geen codewijziging).** Rick's logbestanden kwamen aan
als `.docx` met bestandsnamen als `orca_share_media<...>.docx` — inhoudelijk
wél hetzelfde soort regels (`TIMESTAMP bericht`, geschreven door dezelfde
DiagnosticFileLogger), maar zonder tag-voorvoegsel omdat CareSensAirDriver.kt
zijn `DiagnosticFileLogger.log(...)`-aanroepen (in tegenstelling tot
DexcomG6Driver.kt's "DexcomG6: ..."-aanroepen) geen vast voorvoegsel
meegeeft — dat is dus geen inconsistentie tussen gebruikers, puur een
verschil tussen de twee driver-bestanden. De afwijkende bestandsnaam/
`.docx`-verpakking komt niet van de app zelf (die exporteert altijd als
`fclglucolink_yyyy-MM-dd.txt`) maar van de deelmethode die Rick gebruikte —
een share-app die het platte tekstbestand kennelijk in een Word-document
verpakt en een eigen naam geeft bij het doorsturen.

**Verificatie.** Balance-checker op beide gewijzigde bestanden.

Gewijzigd: `logging/DiagnosticFileLogger.kt`, `FclGlucoLinkApp.kt`.

versionCode 138, versionName "0.9.39-crash-logging".

## Ronde 127 (27/08/2026) — G7-koppel-navigatiebug gevonden en gefixt + BLE-verbindingsafwijzing verklaard

**Aanleiding.** Live koppeltest met een geleende, nog bij de vorige eigenaar
verbonden G7-sensor: "als ik op het hoofdscherm op de status info klik komt
hij op de 'choose you sensor' pagina en klikt bij de g7 niet door naar de
extra info pagina", plus een meegestuurde logcat-uitsnede die een geslaagde
GATT-verbinding + CCCD-writes toont, gevolgd door een `status=19`-disconnect
~10 seconden later, nog vóór enige G7-authenticatie.

**Navigatiebug (gevonden en gefixt).** `FclGlucoLinkNavHost.kt`'s
`statusBaseFor(sensorType)` had géén eigen `SensorType.DEXCOM_G7`-tak en viel
daardoor altijd terug op `BASE_SENSOR_SELECTION` — vandaar dat "status info"
voor een G7-slot op de "choose your sensor"-pagina belandde i.p.v. ergens
G7-specifieks. Erger: `SensorSelectionScreen.kt`'s eigen
`sensor == activeSensor -> onReopenActive()`-tak roept dezelfde functie
opnieuw aan, dus tikken op de al-actieve G7-tegel navigeerde telkens naar
dezelfde route waar de gebruiker al stond — precies het "klikt niet door"-
gevoel uit de melding. Er bestaat geen eigen G7-statusscherm, dus de functie
(hernoemd naar `statusRouteFor(sensorType, slot)`, geeft nu de VOLLEDIGE
route inclusief slot terug i.p.v. alleen de basisnaam) stuurt G7 voortaan
rechtstreeks naar de generieke `PairingScreen` — hetzelfde patroon dat
`onSensorChosen`'s al-werkende G7-tak (Ronde 112) al gebruikte. Bewust geen
`hasKnownDexcomG7PairingCodeOnce()`-check hier: als G7 al de ACTIEVE sensor
van deze slot is, is de koppelcode per definitie al bekend.

**BLE-verbindingsafwijzing (verklaard, geen codewijziging — geen bug in de
app).** `DexcomG7Driver.kt`'s `GattCallback` gelezen: na een geslaagde
verbinding volgt MTU-onderhandeling, service-discovery, en dan CCCD-writes
op ExtraData en Authentication — dat zijn allemaal generieke GATT/ATT-
operaties die geen sensor-authenticatie vereisen, vandaar dat die in de
logcat gewoon lukten. Pas ná die twee CCCD-writes start
`runPairingHandshake()`, wat als EERSTE actie een write naar het ExtraData-
kanaal doet (ronde 1 van de J-PAKE-handshake) — die write zou een eigen
"write ok/FAILED"-regel loggen (`onCharacteristicWrite`), en het eigen
timeout-pad (`PAIRING_STEP_TIMEOUT_MS` = 15s) zou bij uitblijven een
expliciet "geen antwoord op ronde 1"-regel loggen vóór het zelf disconnect()
aanroept. Geen van beide verscheen in de meegestuurde logcat — de `status=19`
disconnect kwam ~10s ná de CCCD-writes, dus RUIM vóór de 15s-timeout, en
zonder dat onze eigen code ooit `disconnect()` aanriep. `status=19`
(`GATT_CONN_TERMINATE_PEER_USER`) betekent bovendien dat de SENSOR zelf de
verbinding beëindigde, niet onze telefoon. Dit patroon — generieke GATT-
operaties lukken, maar zodra de app een echt protocolpakket probeert te
sturen valt de sensor stil en breekt de verbinding zelf af — is precies wat
je verwacht van een Dexcom G7/ONE+ die nog een actieve, geauthenticeerde
sessie met een ANDERE telefoon heeft (xDrip+'s eigen documentatie: "only one
app can collect from the transmitter at any time"). Geen codewijziging hier:
dit is een sensor-kant-beperking, geen fout in deze app's BLE-code.

**Verificatie.** Balance-checker op het gewijzigde bestand. Beide
aanroeppunten van de hernoemde functie (CombiScreen's "status info"-knop en
SensorSelectionScreen's `onReopenActive`) gecontroleerd en bijgewerkt.

Gewijzigd: `ui/FclGlucoLinkNavHost.kt`.

versionCode 139, versionName "0.9.40-g7-status-nav-fix".

## Ronde 128 (27/08/2026) — G7 ronde-1-stilte verklaard en gefixt: verkeerd write-type

**Aanleiding.** Live vervolgtest met dezelfde geleende (kapotte) G7. Op
suggestie van de gebruiker xDrip+ ernaast laten draaien op hetzelfde
toestel — die kwam WEL door de volledige koppeling heen (Android-bonding,
authenticatie, zelfs een batterij-uitlezing), terwijl FCLGlucoLink al bij
ronde 1 van de J-PAKE-handshake vastliep: lokaal "write ok", maar nooit een
antwoord, en na ~10s beëindigde de sensor zelf de verbinding (`status=19`).
xDrip's eigen "Sensor Failed 7"-melding (een letterlijke Dexcom-statuscode,
niet een xDrip-fout) bevestigde bovendien dat de sensor zélf kapot is (geen
actieve sensor-sessie) — maar dat de transmitter-elektronica en het
BLE-kanaal wél volledig gezond zijn, dus een bruikbaar testobject voor de
handshake zelf.

**Root cause gevonden.** Een subagent vergeleek xDrip+'s echte, actuele
broncode (github.com/NightscoutFoundation/xdrip,
`Ob1G5StateMachine.doNext()`) met onze eigen `writeChunked()`/
`writeCharacteristic()`. Twee concrete verschillen: (1) xDrip+ zet vlak vóór
elke ExtraData-chunk-write expliciet `WRITE_TYPE_NO_RESPONSE` op die
characteristic — onze code gebruikte altijd `WRITE_TYPE_DEFAULT` (een ATT
"Write Request", verwacht een write-antwoord). Dat verklaart het waargenomen
symptoom precies: Android's eigen `onCharacteristicWrite`-callback kan lokaal
"success" melden zonder dat de transmitter-firmware de payload ooit aan zijn
J-PAKE-handler doorgeeft, als die characteristic daar alleen op de
geen-antwoord-route voor is aangesloten. (2) xDrip+ wacht na de volledige
chunk-reeks nog een extra 500ms vóór het de ronde-commandobyte naar
Authentication schrijft — onze code deed dat meteen aansluitend.

**DexcomG7Driver.kt.** `writeCharacteristic()` krijgt een `writeType`-
parameter (default ongewijzigd `WRITE_TYPE_DEFAULT` — alle Authentication-
writes blijven dus exact zoals ze waren). `writeChunked()` (alleen gebruikt
voor ExtraData tijdens de J-PAKE-rondes) geeft nu expliciet
`WRITE_TYPE_NO_RESPONSE` mee per chunk, en wacht na de volledige reeks
`POST_CHUNK_SETTLE_MS` (500ms) — beide mirrors van xDrip+'s regels 224/238/243
in `Ob1G5StateMachine.java`.

**Verificatie.** Balance-checker op het gewijzigde bestand. Kon niet
end-to-end tegen een levende sensor getest worden (de beschikbare G7 heeft
zelf geen actieve sessie meer, zie hierboven) — de fix is dus gegrond op een
geverifieerde, geciteerde broncode-vergelijking, niet op een geslaagde
live-koppeling. Nuttig om bij een volgende G7-test (kapot of werkend) in de
gaten te houden of ronde 1 nu wél een antwoord krijgt.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 140, versionName "0.9.41-g7-round1-write-type-fix".

## Ronde 128b (27/08/2026) — G7 ronde-sequencing gefixt: ontbrekende "aftrap"-stap

**Aanleiding.** Direct vervolg op Ronde 128: met de write-type-fix kreeg
ronde 1 nu wél een antwoord van de sensor, maar `validateRound1Packet` bleef
falen — ook nadat de gebruiker de koppelcode op slot 2 helemaal opnieuw en
met 100% zekerheid correct had ingevoerd. Dat sloot een verkeerde code
volledig uit.

**Root cause gevonden.** Op verzoek de daadwerkelijke G7/ONE+-koppelcode van
OpenApsAIMI (github.com/MTR93600/OpenApsAIMI, branch dev_OnePlusG7) laten
opzoeken. Die branch blijkt xDrip+'s eigen KEKS-crypto-module (dezelfde
module als waar onze eigen `DexcomG7Crypto.kt` ooit van geport is)
1-op-1 te vendoren — dus wél de échte, volledige J-PAKE-implementatie,
zelf rechtstreeks nagelezen op GitHub (`plugins/libkeks/.../jamorham/keks/
Plugin.java`). De crypto-wiskunde zelf bleek al correct geport (bevestigd,
`Calc`/`Context`/`Curve`/`KeyPair` komen vrijwel regel-voor-regel overeen).
De bug zat in de RONDE-SEQUENCING: `Plugin.java`'s `aNext()` schuift `state`
door VOORDAT `sequencePacket()` zijn parameter-tag bepaalt — het echte
protocol is daardoor een vraag-antwoord-cyclus van VIER stappen, niet drie:
(A) een KALE `{0x0A,0x00}`-aftrap naar Authentication zonder ExtraData-data
(vraagt de sensor om zíjn eigen ronde-1-pakket), (B) pas dán ons eigen
ronde-1-pakket, getagd met param 1, (C) ons ronde-2-pakket, getagd met
param 2, (D) ons ronde-3-pakket + de echte auth-aanvraag (ongewijzigd, geen
KEYCMD-tag). Onze code sloeg stap A over en stuurde in de EERSTE
uitwisseling al ons eigen ronde-1-pakket mee, getagd met param 0 — precies
verklarend waarom er wél een antwoord kwam (de sensor herkende het
aftrap-commando gewoon) maar de validatie faalde (het pakket dat we als
"sensor se ronde 1" behandelden was eigenlijk het antwoord op een
uitwisseling die de sensor niet als zodanig bedoeld had).

**DexcomG7Driver.kt.** `runPairingHandshake()` herschreven naar de correcte
vier-stappen-cyclus: kale aftrap → ons ronde-1 (param 1) → ons ronde-2
(param 2) → ons ronde-3 + auth-aanvraag (ongewijzigd). De crypto-aanroepen
zelf (`getRoundXPacket`/`validateRoundXPacket`) zijn NIET aangepast — alleen
WANNEER en met WELKE param-tag ze verstuurd worden.

**Verificatie.** Balance-checker op het gewijzigde bestand. Kon nog niet
end-to-end bevestigd worden (vereist een volgende live-test), maar is
gegrond op een rechtstreeks zelf nagelezen, geciteerde referentie-
implementatie — niet op giswerk.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 141, versionName "0.9.42-g7-round-sequencing-fix".

## Ronde 129 (27/08/2026) — eigen G7-statusscherm

**Aanleiding.** Op verzoek, direct na Ronde 128b: "Wat we in ieder geval
alvast kunnen doen is een status scherm maken vergelijkbaar met de g6 maar
dan niet met losse transmitter en losse sensor" — met een meegestuurde
screenshot van xDrip+'s "Systeem status"-scherm als richtlijn voor
zinvolle velden. Sinds Ronde 127 viel een "status info"-tik op een G7-slot
terug op de generieke PairingScreen (bij gebrek aan een eigen scherm) — dat
werkt, maar voelt niet als een statusoverzicht.

**DexcomG7StatusScreen.kt (nieuw).** Één vlakke tabel (bewust GEEN aparte
Sensor-/Transmitter-tabellen zoals G6 — G7/ONE+ heeft dat onderscheid niet,
net als CareSens Air), met: Status (bovenaan, los), Bluetooth link, Address,
Pairing code (Saved/Not saved), Last connected. Batterij/firmwareversie uit
de xDrip-screenshot bewust NIET meegenomen: onze driver doet daar nog geen
uitvraag naar, dus die rijen zouden alleen als permanente "—" staan — kan
een latere ronde worden. Nieuwe "Forget pairing code"-knop (met
bevestigingsdialoog): wist de opgeslagen G7-koppelcode voor deze slot, zodat
de volgende koppelpoging 'm gewoon opnieuw vraagt — ontstaan uit deze sessies
eigen live-tests, waarbij een opgeslagen code niet meer zichtbaar of
controleerbaar was.

**AppSettings.kt.** Nieuwe `clearDexcomG7PairingCode(slot)`, tegenhanger van
de al bestaande `setDexcomG7PairingCode`.

**FclGlucoLinkNavHost.kt.** `statusRouteFor()`'s DEXCOM_G7-tak (Ronde 127)
wijst nu naar het nieuwe `BASE_DEXCOM_G7_STATUS`-scherm i.p.v. naar
PairingScreen. Nieuw `ROUTE_DEXCOM_G7_STATUS`-composable, zelfde
onDisconnect-patroon als G6/CareSens (stopBleConnectionService +
ConnectionStatusBridge + clearDeviceAddress).

**Verificatie.** Balance-checker op alle drie gewijzigde/nieuwe bestanden.

Gewijzigd: `ui/FclGlucoLinkNavHost.kt`, `data/AppSettings.kt`. Nieuw:
`ui/DexcomG7StatusScreen.kt`.

versionCode 142, versionName "0.9.43-g7-status-screen".

## Ronde 130 (27/08/2026) — G7-statusscherm-UX-fixes + koppeling blijft
onderzocht

**Aanleiding.** Live-test van v142 (Ronde 129's statusscherm): koppeling
faalt nog steeds bij "ronde 1: ongeldig bewijs", ondanks de write-type- én
sequencing-fixes uit Ronde 128/128b (beide bevestigd structureel actief in
de log). Daarnaast een reeks concrete UX-klachten op het nieuwe
statusscherm zelf: "Wat niet goed is is dat hij tranmitter heet op het
status scherm, dat moet sensor worden. De forget pairing code knop werkt
wel maar dan is er nergens een knop om hem weer in te voeren [...] Ook de
disconnect knop werkt maar vervolgens kun je niet weer connecten [...] het
lijkt me handiger dat er een streepje staat tot hij ingevuld is dan dat hij
niet zichtbaar is."

**Koppeling — verder onderzoek, geen doorbraak.** De crypto
(`DexcomG7Crypto.kt`) is deze ronde BYTE-VOOR-BYTE geverifieerd tegen de
echte, vendored xDrip+-broncode (`MTR93600/OpenApsAIMI`,
`dev_OnePlusG7`-branch, `plugins/libkeks/.../jamorham/keks/{Config,Calc,
Packet,JECPoint}.java` — rechtstreeks opgehaald en zelf regel-voor-regel
nagelopen, niet alleen via een subagent-samenvatting). Ook de op het eerste
gezicht verdacht ogende "alice"/"bob" party-ID-hex-constanten bleken
byte-voor-byte te kloppen. Geen enkel verschil gevonden — de wiskunde zelf
is dus met vrij hoge zekerheid correct.

Wél gevonden: `DexcomG7Driver.kt`'s `awaitExtraDataPacket()`/
`awaitAuthIndication()` zetten hun `CompletableDeferred` klaar NADAT de
bijbehorende schrijfactie al was uitgevoerd — een smal race-venster tussen
het versturen van een commando en het "klaarzetten" om het antwoord op te
vangen (de write keert synchroon meteen terug, de GATT-notificatiecallback
loopt op een ander thread). Gefixt: beide functies nemen nu een
`trigger: suspend () -> Unit`-parameter en voeren die pas uit NADAT de
deferred al staat. Op zich een smal venster (een echte BLE-rondetrip duurt
normaliter veel langer dan de paar tussenliggende CPU-instructies), dus
geen garantie dat dit DE oorzaak is — maar correct en goedkoop om te
sluiten.

Om een vierde blinde gok te voorkomen als dit niet de oorzaak blijkt: een
nieuwe `logRound1ValidationFailure()` logt bij een mislukte ronde-1-
validatie de ruwe hex van het ontvangen pakket (`point1`/`point2`/`hash`)
plus onze eigen `bob`/`alice`/`keyA.publicKey`-waarden naar het
diagnose-logboek — genoeg om de zero-knowledge-hash desnoods handmatig na
te rekenen bij de eerstvolgende live-test, i.p.v. weer te moeten raden.

**DexcomG7StatusScreen.kt — vijf UX-fixes.**
1. "Transmitter"-titel → "Sensor".
2. "Pairing code"-rij toont nu de werkelijke code (of "—") i.p.v.
   "Saved"/"Not saved".
3. De losse "Forget pairing code"-knop + bevestigingsdialoog is vervangen
   door één ALTIJD zichtbare "Change pairing code"-knop (nieuwe
   `onChangePairingCode`-parameter) die rechtstreeks naar
   `DexcomG7SetupScreen` navigeert — dezelfde, al werkende flow die
   "Switch transmitter" elders gebruikt (wist device-adres, slaat nieuwe
   code op, navigeert door naar het koppelscherm). Lost in één keer op: (a)
   er was geen weg terug om een nieuwe code in te voeren na "Forget", en
   (b) na "Disconnect" was er geen voor de hand liggende weg om weer te
   connecten zonder eerst een andere sensor te kiezen en dan pas weer G7
   (de gemelde workaround) — de knop is nu altijd zichtbaar, ongeacht
   connectiestatus. De "blijft op Saved/Connecting staan na Forget"-klacht
   vervalt hiermee ook: er is geen tussentijdse "vergeten maar nog niet
   opnieuw gekoppeld"-status meer om in vast te lopen, de knop navigeert
   meteen weg van dit scherm.
4. Zeven extra rijen (Sensor status, Brain state, Firmware version, Battery
   last queried, Transmitter days, Voltage A, Voltage B) toegevoegd als
   "—"-placeholders, expliciet op verzoek — vervangt Ronde 129's bewuste
   keuze om ze weg te laten. Onze driver vraagt deze gegevens nog niet uit;
   de rij-structuur staat nu wel al klaar voor als dat ooit toegevoegd
   wordt.

**FclGlucoLinkNavHost.kt.** `ROUTE_DEXCOM_G7_STATUS`-composable geeft nu
ook `onChangePairingCode` door, gekoppeld aan
`navController.navigate(slotRoute(BASE_DEXCOM_G7_SETUP, slot))`.

**Verificatie.** Balance-checker (haakjes/accolades) op alle drie
gewijzigde bestanden.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `ui/DexcomG7StatusScreen.kt`,
`ui/FclGlucoLinkNavHost.kt`.

versionCode 143, versionName "0.9.44-g7-status-fixes-diagnostics".

## Ronde 131 (27/08/2026) — G7-koppeling: vroegtijdig createBond(), op basis
van een echte xDrip+-log op dezelfde sensor

**Aanleiding.** V143's diagnose-logging (Ronde 130) leverde 45 mislukte
ronde-1-pogingen op. Onafhankelijk in Python nagerekend (losse implementatie,
niet de Kotlin-code): de ontvangen punten liggen echt op de curve (geen
verminkte bytes), maar het zero-knowledge-bewijs klopt in GEEN van de 45
gevallen — met of zonder partij-ID's/punten omgewisseld. Belangrijk: deze
controle gebruikt de koppelcode niet eens, het is puur een zelfconsistentie-
check van de sensor. Dat zou bij gezonde communicatie altijd moeten slagen.

Op verzoek van de gebruiker is xDrip+ herinstalleerd en opnieuw verbonden met
DEZELFDE sensor. Diens log liet iets doorslaggevends zien: herhaalde
`Bond state 11 Pairing` / `Prompting user to notice pairing request with
sound` momenten (soms terugvallend naar `Bond state 10 Unpaired`, meerdere
"Error count reached"-pogingen) — en pas NA `Bond state 12 Paired` (na
meerdere pogingen) kwamen `VersionRX`/`BatteryRX` succesvol binnen. Ook trad
xDrip+'s eigen "Missing QR code"-uitzondering op (bevestigt dat xDrip+ WEL
door de volledige J-PAKE-authenticatie heen komt op deze sensor, vóórdat het
in een secundair pad struikelt) — dus de transmitter-hardware kán de
handshake overduidelijk aan.

**Conclusie.** FCLGlucoLink's driver riep `gatt.device.createBond()` tot nu
toe alleen aan NA een geslaagde J-PAKE-authenticatie (die nooit gehaald
werd) — het Android-systeem-koppelscherm kwam daardoor nooit tevoorschijn,
en de BLE-link bleef de hele sessie ongebonded/onversleuteld. xDrip+'s eigen
log suggereert sterk dat bonden VROEG (en soms pas na meerdere pogingen)
moet gebeuren, niet als laatste stap.

**DexcomG7Driver.kt.** `onServicesDiscovered()`: `createBond()` wordt nu
vroegtijdig aangeroepen (als de link nog niet gebonded is), vóórdat de
J-PAKE-handshake start — niet blokkerend, de handshake wordt gewoon meteen
geprobeerd terwijl bonden op de achtergrond kan lopen (mirror van xDrip+'s
eigen, niet-blokkerende gedrag). `registerBondReceiver()` logt nu ELKE
bond-state-overgang (niet alleen BOND_BONDED), mirror van xDrip+'s eigen
informatieve `Bond state N Naam bs: ... was ...`-logregels — nuttig voor een
volgende diagnoseronde als dit niet meteen de volledige fix blijkt.

**Verificatie.** Balance-checker op het gewijzigde bestand.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 144, versionName "0.9.45-g7-early-createbond".

## Ronde 132 (27/08/2026) — v144's vroege createBond() gefixt: seriёel i.p.v.
gelijktijdig met CCCD-writes

**Aanleiding.** Live-test van v144 leverde een regressie op: een raw
systeem-logcat toonde `status=19`-disconnect binnen ~150ms na verbinden —
zelfs VÓÓR ronde 1 ooit geprobeerd werd, iets wat zelfs vóór Ronde 131 nooit
gebeurde. Exacte volgorde uit de logcat: `createBond()` aangeroepen -> CCCD-
write voor ExtraData "ok" -> `Bond state Pairing` -> `setCharacteristic
Notification` voor Authentication -> `onConnectionUpdated` (een BLE-
verbindingsparameter-update, een typisch bijverschijnsel van het STARTEN
van SMP-bonding) -> binnen ~50ms daarna `status=19`-disconnect -> `Bond
state Unpaired`. De sensor lijkt de verbinding af te breken zodra er een
SMP-koppelverzoek binnenkomt TERWIJL er nog andere GATT-operaties (CCCD-
writes) in de pijplijn zitten — Ronde 131's `createBond()`-aanroep liep
namelijk GELIJKTIJDIG met de CCCD-writes, niet ervoor.

**DexcomG7Driver.kt.** `onServicesDiscovered()`: nu strikt serieel — als de
link nog niet gebonded is, wordt `createBond()` aangeroepen en wordt er
gewacht op een DEFINITIEVE uitkomst (via `pendingAfterBond`, hergebruikt
van de bestaande post-auth-bond-flow) vóórdat er ook maar één CCCD-write
gedaan wordt. Geen permanente blokkade: na een nieuwe
`EARLY_BOND_TIMEOUT_MS` (15s, zelfde marge als `PAIRING_STEP_TIMEOUT_MS`)
wordt gewoon doorgegaan met de handshake op de huidige (mogelijk nog
ongebonded) verbinding, zodat een langzaam/nooit-succesvol bondproces de
boel niet blijvend blokkeert — xDrip+'s eigen log liet zien dat bonden soms
meerdere pogingen over meerdere verbindingen kost; de bestaande reconnect-
lus vangt dat vanzelf op.

**Verificatie.** Balance-checker op het gewijzigde bestand.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 145, versionName "0.9.46-g7-serialized-createbond".

## Ronde 133 (27/08/2026) — vroegtijdig createBond() weer teruggedraaid: de
sensor breekt de verbinding af zodra we het zelf aanroepen

**Aanleiding.** Live-test van v145 (Ronde 132's strikt-seriële variant)
leverde een raw systeem-logcat op die bijna 20 minuten en ~19 herhaalde
verbindingspogingen besloeg (20:35–20:54), allemaal met exact hetzelfde
patroon: verbinden -> `createBond()` aangeroepen (onze eigen logregel
bevestigt: geen enkele CCCD-write is op dat moment nog gedaan) ->
`onConnectionUpdated` (bijverschijnsel van het starten van SMP) -> BINNEN
~50-150ms `status=19`-disconnect -> vervolgens, ~15s later, de
`EARLY_BOND_TIMEOUT_MS`-fallback die alsnog `setCharacteristicNotification`
probeert op de allang gesloten gatt, wat faalt.

Dit weerlegt Ronde 132's aanname rechtstreeks: het was NIET de
gelijktijdigheid met CCCD-writes die de disconnect veroorzaakte (v145 had
die gelijktijdigheid volledig weggehaald), maar de `createBond()`-aanroep
ZELF, ongeacht timing. Resultaat was zelfs slechter dan de situatie vóór
Ronde 131: geen enkele verbinding overleefde nog lang genoeg om ronde 1 van
de J-PAKE-handshake te proberen, laat staan te falen.

**Conclusie.** Bij deze specifieke sensor lijkt bonden PERIFEER-
geïnitieerd te zijn — de sensor (of Android, via bv. een "insufficient
encryption"-GATT-fout tijdens de handshake) start zelf een SMP-
koppelverzoek wanneer dat nodig is. xDrip+'s log uit Ronde 131 toonde wel
`Bond state Pairing`-overgangen, maar dat is geen bewijs dat xDrip zelf
`createBond()` aanroept vóór de handshake — dat kan net zo goed automatisch
zijn gestart. De broncode van xDrip+'s eigen bonding-trigger
(`Ob1G5CollectionService`) kon deze sessie niet gevonden worden (meerdere
fetch-pogingen op `raw.githubusercontent.com/NightscoutFoundation/xdrip`
leverden niets op), dus de "vroeg bonden"-aanpak van Ronde 131/132 was
gebaseerd op AFLEIDING uit het geobserveerde gedrag, niet op de daadwerkelijke
broncode — en die afleiding bleek dit keer verkeerd.

**DexcomG7Driver.kt.** `onServicesDiscovered()`: de vroegtijdige/seriële
`createBond()`-aanroep (Ronde 131/132) is volledig verwijderd. Terug naar de
oorspronkelijke (Ronde 112-)opzet: direct doorgaan met CCCD-writes en de
J-PAKE-handshake; `createBond()` wordt pas aangeroepen NA succesvolle
authenticatie, in `runPairingHandshake()`'s bestaande
`if (!status.isBonded) { ...; gatt.device.createBond() }`-tak — ongewijzigd
sinds Ronde 112. De ongebruikte `EARLY_BOND_TIMEOUT_MS`-constante is
verwijderd. De verbeterde bond-state-logging uit Ronde 131
(`bondStateName()`, elke overgang loggen, niet alleen BOND_BONDED) blijft
staan — puur observationeel, onschadelijk, en heeft dit keer net de
doorslaggevende diagnose geleverd.

**Belangrijk voorbehoud.** Dit lost de bonding-regressie van Ronde 131/132
op (terug naar het gedrag van vóór die experimenten), maar lost NIET de
onderliggende "ronde 1: ongeldig bewijs"-mysterie op — dat blijft, na
uitputtende verificatie tegen de echte referentie-implementatie én
onafhankelijke Python-validatie tegen 45 echte samples, een openstaande
vraag voor een volgende ronde. Het is inmiddels wel duidelijk vastgesteld
dat het GEEN cryptografie-/protocolvolgorde-bug in FCLGlucoLink's eigen
code is.

**Verificatie.** Balance-checker (haakjes/accolades) op het gewijzigde
bestand.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 146, versionName "0.9.47-g7-revert-early-bond".

## Ronde 134 (27/08/2026) — DE bug gevonden: partij-ID's "client"/"server"
werden met verkeerd-om genibbelde hex geparset

**Aanleiding.** Live-test van v146 (Ronde 133's revert) toonde iets heel
waardevols: de verbinding bleef nu STABIEL — 11 opeenvolgende
koppelpogingen (21:27–21:37) bereikten allemaal netjes ronde 1, schreven de
CCCD's, ontvingen een volledig 160-byte antwoordpakket, zonder ook maar één
`status=19`-disconnect. Maar ELKE keer faalde `ronde1-validatie` alsnog,
met exact hetzelfde patroon als in alle eerdere pogingen deze sessie.

Op verzoek van de gebruiker ("wil dit gewoon werkend krijgen [...] graag de
methode die de meeste kans op succes heeft") is de eerdere exhaustieve
verificatie (Calc/Packet/Context/Plugin/JECPoint/Digest/SHA256.java, allemaal
al gelezen en geverifieerd in eerdere rondes) nog één keer overgedaan, maar
dit keer inclusief een bestand dat nooit eerder was opgehaald:
`jamorham.keks.util.Util`. Daar bleek `hexStringToByteArray()` de twee
hex-cijfers van elk bytepaar VERWISSELD te decoderen:
`data[i/2] = (digit(str[i+1]) << 4) + digit(str[i])` — dus met de HOGE en
LAGE nibble omgedraaid t.o.v. standaard hex-parsing. Config.java's
`ALICE_B`/`BOB_B`-constanten (`"36C69656E647"`/`"375627675627"`) worden
UITSLUITEND via deze functie gedecodeerd — en met die verwisseling correct
toegepast blijken ze doodgewoon de ASCII-tekst **"client"** resp.
**"server"** te zijn: voor de hand liggende, betekenisvolle partij-ID's voor
een J-PAKE-koppeling tussen telefoon en sensor.

FCLGlucoLink's eigen `hexToBytes()` (Ronde 112) deed STANDAARD nibble-
parsing op diezelfde hex-strings, en produceerde dus 6 volkomen andere,
niet-ASCII bytes (`36 C6 96 56 E6 47` i.p.v. `63 6C 69 65 6E 74` voor
"alice"). Die partij-ID gaat rechtstreeks de zero-knowledge-hash in
(`getZeroKnowledgeHash`'s `party`-argument, gebruikt in ZOWEL het bouwen
als het valideren van elk rondepakket) — met de verkeerde bytes daar kan de
Schnorr-bewijsvergelijking (`g^r · publicKey^h =? gv`) nooit uitkomen,
hoe correct de rest van de wiskunde ook is. Dit verklaart in één keer:
waarom de twee ontvangen EC-punten in elk gefaald pakket altijd wél geldige
curvepunten waren (die decodering gebruikt de partij-ID-bytes niet); waarom
eerdere Python-kruisverificatie met alice/bob- en punt1/punt2-omwisselingen
niets vond (geen van die hypotheses raakte aan HOE de constanten zelf
gedecodeerd werden — alle vier de combinaties gebruikten nog steeds de
verkeerde onderliggende bytes); en waarom dit een 100%-reproduceerbare,
sensor-onafhankelijke fout was (een partij-ID-constante, geen sensor-
specifieke waarde).

**DexcomG7Crypto.kt.** `DexcomG7JpakeContext.alice`/`.bob` worden nu
rechtstreeks als ASCII-letterlijke tekst geschreven
(`"client".toByteArray(StandardCharsets.US_ASCII)` /
`"server".toByteArray(...)`) — geen hex-parsing meer nodig, dus de
(nu overbodige én foutgevoelige) `hexToBytes()`-hulpfunctie is verwijderd.

**Verificatie.** Onafhankelijk in Python nagerekend dat jamorham's
verwisselde nibble-parsing op beide hex-strings toegepast exact
`b"client"`/`b"server"` oplevert (zie sessie-transcript). Balance-checker
(haakjes/accolades) op het gewijzigde bestand.

Gewijzigd: `sensor/dexcomg7/DexcomG7Crypto.kt`.

versionCode 147, versionName "0.9.48-g7-party-id-nibble-fix".

## Ronde 135 (27/08/2026) — auth-aanvraag kreeg nooit antwoord: slotbyte
stond op 0 i.p.v. 2

**Aanleiding.** Live-test van v147 (Ronde 134's partij-ID-fix) was een
doorbraak: voor het eerst deze hele sessie slaagden ronde 1, ronde 2 ÉN
ronde 3 van de J-PAKE-handshake stuk voor stuk — geen enkele
`ronde1/2/3-validatie MISLUKT` meer, op 3 onafhankelijke verbindingen na
elkaar (22:31, 22:32, 22:33). Maar alle 3 liepen daarna vast op EXACT
dezelfde volgende stap: nadat de auth-aanvraag (opcode 0x02) naar de
Authentication-characteristic geschreven was, kwam er nooit een antwoord —
de sensor verbrak de verbinding (`status=19`) binnen ~200ms, en de handshake
faalde met "geen antwoord op auth-aanvraag".

**Oorzaak.** `buildAuthRequest()`'s laatste byte (het "slot"-veld) stond op
`0`. De echte bron
(`jamorham.keks.message.AuthRequestTxMessage2`, rechtstreeks opgehaald)
laat zien dat dat veld nooit 0 is:
```java
this(token_size, (alt ? endByteAlt : endByteStd)
        + (chal.length > 2 ? chal[2] : 0));
// endByteStd = 0x2, endByteAlt = 0x1
```
Bij een normale (eerste) koppelpoging is `alt` altijd `false` en `chal`
altijd leeg, dus de slotbyte is in de praktijk altijd gewoon `endByteStd`
= **2** — nooit 0. Vermoedelijk verwerpt de sensor een auth-aanvraag met een
onherkende slotwaarde stilzwijgend (geen antwoord, gewoon ophangen) —
precies het waargenomen symptoom. Ook `AuthChallengeTxMessage.java` en
`AuthStatusRxMessage.java` (de twee stappen erna) zijn ter controle
opgehaald en rechtstreeks vergeleken — die twee kloppen al exact.

**DexcomG7Protocol.kt.** `buildAuthRequest()`'s slotbyte: `0` → `2`.

**Verificatie.** Balance-checker (haakjes/accolades) op het gewijzigde
bestand. Byte-layout van `AuthRequestTxMessage2`/`BaseMessage.init()`
rechtstreeks nagelezen (opcode + 8-byte token + slotbyte = 10 bytes,
little-endian-allocatie maar byte-voor-byte puts — matcht onze
`ByteBuffer.allocate(10)`-opbouw al exact, alleen de slotwaarde was fout).

Gewijzigd: `sensor/dexcomg7/DexcomG7Protocol.kt`.

versionCode 148, versionName "0.9.49-g7-auth-request-slot-fix".

## Ronde 136 (27/08/2026) — Android's eigen koppeldialoog kreeg nooit onze
koppelcode: `ACTION_PAIRING_REQUEST` afgehandeld

**Aanleiding.** Live-test van v148 (Ronde 135's slotbyte-fix) was de
volgende doorbraak: voor het eerst zag de handshake de auth-aanvraag ÉN
het uitdaging-antwoord beide slagen (drie opeenvolgende geslaagde writes
naar de Authentication-characteristic, gevolgd door een `Bond state
Pairing`-overgang). De gebruiker meldde daarbij zelf: "ik zag wel een paar
keer heel kort een android popup voorbij komen waarin stond: 'onjuiste
koppel code'. De koppeling waarin het vinkje wordt gezet dat de koppeling
permanent is komt niet voorbij." — en de sensor verbrak steeds de
verbinding (`status=19`) binnen ~250ms na het begin van die `Pairing`-fase.

**Oorzaak.** Na een geslaagde J-PAKE-authenticatie vereist de G7 alsnog een
gewone OS-niveau Bluetooth-bonding (los van de J-PAKE-laag zelf — dat is
precies waar `gatt.device.createBond()` al voor bedoeld was, sinds Ronde
112). Maar onze app luisterde tot nu toe UITSLUITEND naar
`ACTION_BOND_STATE_CHANGED` (het RESULTAAT van een koppelpoging), nooit
naar `ACTION_PAIRING_REQUEST` (het VERZOEK om zelf een PIN aan te bieden).
Zonder een luisteraar die de PIN aanbiedt, probeert Android's eigen
systeem-koppeldialoog het zelf — kennelijk met een verkeerde/lege waarde —
en dat faalt binnen enkele honderden ms, precies het "heel kort voorbij
komen"-gedrag dat de gebruiker beschreef.

**DexcomG7Driver.kt.** `registerBondReceiver()`'s bestaande ontvanger
luistert nu OOK naar `ACTION_PAIRING_REQUEST`: bij ontvangst biedt hij de
opgeslagen (4-cijferige) koppelcode aan via `device.setPin(pairingCode.
toByteArray(Charsets.US_ASCII))`, en onderdrukt Android's eigen
systeemdialoog met `abortBroadcast()` — exact het patroon dat xDrip+/AAPS
voor dezelfde G6/G7-koppelstap gebruiken. De `IntentFilter` kreeg
`IntentFilter.SYSTEM_HIGH_PRIORITY` zodat onze ontvanger vóór Android's
eigen dialoog-handler draait en die daadwerkelijk kan onderdrukken.

**Verificatie.** Balance-checker (haakjes/accolades) op het gewijzigde
bestand.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 149, versionName "0.9.50-g7-os-pairing-request-pin".

## Ronde 137 (27/08/2026) — Ronde 136's `abortBroadcast()` weer verwijderd:
xDrip+ onderdrukt Android's eigen koppeldialoog helemaal niet

**Aanleiding.** Directe verduidelijking van de gebruiker na v149: "xdrip
roept wel androids eigen dialoog aan die waarbij je de koppeling permanent
zet en waarbij je de mogelijkheid hebt om de bluetooth toegang tot de
contacten en telefoon te geven." Dat is precies het systeem-dialoog dat
`abortBroadcast()` in Ronde 136 onderdrukte.

**Conclusie.** Ronde 136's aanname was fout: xDrip+ vult de koppelcode niet
stil in via `setPin()` + `abortBroadcast()` — het laat Android's EIGEN
koppeldialoog gewoon verschijnen (met het "maak deze koppeling
permanent"-vinkje en de contacten/telefoon-toegangsoptie), en de gebruiker
bevestigt die zelf. Door de broadcast af te breken onderdrukten we precies
dát dialoog — vandaar dat de gebruiker meldde dat "de koppeling waarin het
vinkje wordt gezet dat de koppeling permanent is" na v149 niet meer
voorbijkwam.

**DexcomG7Driver.kt.** `abortBroadcast()` verwijderd uit de
`ACTION_PAIRING_REQUEST`-afhandeling in `registerBondReceiver()`. Android's
eigen systeemdialoog krijgt nu weer de kans om te verschijnen; de gebruiker
moet 'm zelf bevestigen (zoals bij xDrip+). `setPin()` blijft staan als
onschadelijke best-effort aanvulling (relevant als de sensor daadwerkelijk
om PIN-invoer vraagt i.p.v. een simpele bevestiging) — dat vult höchstens
een veld, het toont geen eigen UI en blokkeert niets.

**Verificatie.** Balance-checker (haakjes/accolades) op het gewijzigde
bestand; expliciet gecontroleerd dat de daadwerkelijke `abortBroadcast()`-
aanroep weg is (alleen nog in commentaar-tekst, ter documentatie van de
fout).

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`.

versionCode 150, versionName "0.9.51-g7-dont-suppress-pairing-dialog".

## Ronde 138 (27/08/2026) — `createBond()` expliciet op TRANSPORT_LE: geen
dialoog-onderdrukking was het probleem, de OS-koppelpoging zelf faalde al

**Aanleiding.** Live-test met v150 (`fclglucolink_2026-08-27 23.27.txt`,
zes opeenvolgende verbindingen): het volledige J-PAKE-handshake + auth-
request/challenge/status-traject slaagt telkens (allemaal "write ok"), Bond
state gaat van Unpaired naar Pairing — en dan, zonder uitzondering, binnen
~250-300ms een status=19-disconnect (GATT_CONN_TERMINATE_PEER_USER — de
sensor zelf haakt af), gevolgd door Bond state terug naar Unpaired. Geen
enkele keer verscheen "Pairing request ontvangen" in de log — de
`ACTION_PAIRING_REQUEST`-broadcast kwam dus nooit binnen, met of zonder
Ronde 137's fix. De gebruiker vroeg terecht: hoe lukt dit Juggluco/xDrip/
AIMI/BYODA wel?

**Onderzoek.** xDrip+'s eigen open-source G6-driver opgezocht
(`NightscoutFoundation/xDrip`, `Ob1G5CollectionService.java`, via
grep.app-codesearch omdat GitHub's eigen API/zoekfunctie niet bereikbaar
was in deze omgeving). Cruciale vondst op regel 1168-1172:

```java
if (Build.VERSION.SDK_INT < 26) {
    registerReceiver(mPairingRequestRecevier, pairingRequestFilter);
} else {
    UserError.Log.d(TAG, "Not registering pairing receiver on Android 8+");
}
```

xDrip+ registreert zélf HELEMAAL GEEN `ACTION_PAIRING_REQUEST`-ontvanger
meer op Android 8+ — precies omdat die broadcast op moderne Android niet
(betrouwbaar) bij losse apps terechtkomt. Dat bevestigt onafhankelijk wat
onze eigen logs al lieten zien, en betekent dat Ronde 136/137's hele
dialoog-onderdrukkingshypothese een dood spoor was: het probleem zit vóór
elk moment waarop een dialoog ooit zou kunnen verschijnen. `setPin()`-
aanroepen bleven daarom feitelijk zonder effect.

**Conclusie/hypothese.** Onze blijvende `gatt.device.createBond()`-aanroep
gebruikt (net als xDrip+'s eigen G6-code overigens) het argumentloze
`TRANSPORT_AUTO`. Op een toestel dat zowel classic (BR/EDR) als LE
ondersteunt kan Android daarmee een classic/dual koppelpoging proberen
naast/in plaats van zuiver LE — een LE-only sensor als de G7 (geen BR/EDR-
radio) kan zo'n verkeerd-getransporteerde koppelpoging direct laten
mislukken/afhaken, precies het "Pairing -> ~250ms later status=19,
zonder dialoog"-patroon dat consequent in de logs staat. Dit is een bekend
Android-BLE-koppelvalkuiltje bij LE-only accessoires. Sinds API 30 (Android
11) is er een publieke `createBond(int transport)`-overload waarmee
`TRANSPORT_LE` expliciet afgedwongen kan worden.

**DexcomG7Driver.kt.** De `createBond()`-aanroep in de
`!status.isBonded`-tak (na een geslaagde auth) gebruikt nu, met SDK-check
en fallback voor minSdk 26:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    gatt.device.createBond(BluetoothDevice.TRANSPORT_LE)
} else {
    gatt.device.createBond()
}
```

**Verificatie.** Balance-checker (haakjes/accolades) op het gewijzigde
bestand: 199/199 accolades, 607/607 haakjes.

Dit is een gerichte, onderbouwde hypothese — geen 100%-bevestigde fix zoals
Ronde 134/135 (die waren byte-voor-byte tegen xDrip+'s brontekst
geverifieerd). Als v151 nog steeds op hetzelfde punt faalt, is een verse
log nodig om te zien of het status=19-patroon verandert (bijv. andere
timing, of alsnog een "Pairing request ontvangen"-regel).

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 151, versionName "0.9.52-g7-createbond-transport-le".

## Ronde 138b (27/08/2026) — 32x "Call requires permission" lintfout in
DexcomG7Driver.kt onderdrukt

**Aanleiding.** Na v151: "DexcomG7Driver.kt nu geeft hij: 32 keer deze
fout: Call requires permission which may be rejected by user: code should
explicitly check to see if permission is available (with `checkPermission`)
of expliciet een potentiële `SecurityException` afhandelen."

**Diagnose.** Dit is Android Lint's standaard `MissingPermission`-check op
alle `BluetoothDevice`/`BluetoothGatt`-methodes die met
`@RequiresPermission` zijn geannoteerd (BLUETOOTH_SCAN/BLUETOOTH_CONNECT,
sinds Android 12). `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` staan al in
`AndroidManifest.xml` en worden vóór elke koppelpoging als runtime-
permissie aangevraagd/gecontroleerd — dát is precies waarom deze driver in
de praktijk al talloze keren succesvol verbindt (zie alle live-testlogs tot
nu toe). Lint kan die controle, die in een ánder bestand gebeurt (het
scherm dat de koppeling start), niet dataflow-volgen, en markeert daarom
elke losse Bluetooth-aanroep in dit bestand als "mogelijk ongeautoriseerd"
— 32 keer, want dat is precies hoeveel losse `BluetoothDevice`/
`BluetoothGatt`-aanroepen dit bestand heeft (`connectGatt`,
`discoverServices`, `setCharacteristicNotification`,
`writeCharacteristic`, `createBond`, etc.), inclusief de nieuwe
`createBond(TRANSPORT_LE)`-aanroep uit Ronde 138. Puur een statische-
analyse-beperking, geen runtime-probleem — vandaar dat dit nu pas
opdook (na de wijziging in Ronde 138) terwijl de talrijke andere
Bluetooth-aanroepen in dit bestand al veel langer bestonden.

**DexcomG7Driver.kt.** `@SuppressLint("MissingPermission")` toegevoegd op
klasseniveau (`class DexcomG7Driver`) — dat dekt ook de geneste anonieme
`BluetoothGattCallback`. Puur een lint-onderdrukking met toelichtende kdoc;
geen enkele wijziging aan runtime-gedrag of de permissielogica zelf.

**Verificatie.** Balance-checker: 199/199 accolades, 611/611 haakjes.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 152, versionName "0.9.53-g7-suppress-missingpermission-lint".

## Ronde 138c (27/08/2026) — `createBond(TRANSPORT_LE)` compileerde niet:
via reflectie, met fallback

**Aanleiding.** Na v152: "Too many arguments for 'fun createBond(): Boolean'.
in 863" — de directe `gatt.device.createBond(BluetoothDevice.TRANSPORT_LE)`-
aanroep uit Ronde 138 compileert niet.

**Diagnose.** Correctie op Ronde 138's aanname: `createBond(int transport)`
bestaat weliswaar sinds API 30 in AOSP, maar staat gemarkeerd als
`@SystemApi` — hij zit dus niet in de publieke `android.jar`-stub waartegen
we compileren (compileSdk 34), vandaar de compilerfout "Too many
arguments". De methode zelf vereist geen system-permissie, ze is puur uit
de publieke SDK-stub weggelaten — reflectie is de gangbare workaround
hiervoor.

**DexcomG7Driver.kt.** De `createBond(TRANSPORT_LE)`-aanroep gaat nu via
reflectie (`BluetoothDevice::class.java.getMethod("createBond",
Int::class.javaPrimitiveType)`), met try/catch: als reflectie om wat voor
reden dan ook faalt (oudere Android-versie, hidden-API-restrictie,
fabrikant-afwijking), valt de code terug op de gewone publieke
`createBond()` (TRANSPORT_AUTO, het oude gedrag) — nooit een harde crash.

**Verificatie.** Balance-checker: 202/202 accolades, 622/622 haakjes.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 153, versionName "0.9.54-g7-createbond-transport-le-reflection".

## Ronde 139 (28/08/2026) — echte oorzaak gevonden via HCI-snooplog:
createBond() racete tegen onze eigen laatste GATT-write

**Aanleiding.** Na v153 (TRANSPORT_LE via reflectie) meldde de gebruiker,
terecht gefrustreerd, dat er al meerdere rondes waren geweest met
speculatieve fixes die telkens niets uithaalden, en vroeg direct: hoe komt
het dat xDrip/Juggluco/AIMI dit wél kunnen en wij niet. In plaats van nóg
een hypothese te bouwen is een volledig Android-bugreport opgevraagd (via
Instellingen → Ontwikkelaarsopties → Bugrapport, geen ADB nodig) en is de
daarin meegeleverde ruwe Bluetooth HCI-snooplog (`btsnooz_hci.log`,
1747 pakketten, 6 koppelpogingen) byte-voor-byte ontleed — grondwaarheid
i.p.v. nog een gok.

**Diagnose (deel 1 — SMP-laag).** In alle 6 pogingen: de telefoon stuurt
een SMP Pairing Request naar de G7; de sensor antwoordt NOOIT (geen
Pairing Response, geen Pairing Failed, geen Security Request — in de
volledige capture van 1747 pakketten komt geen van die drie ooit voor); na
~250-300ms verbreekt de sensor zelf de verbinding (HCI-reden 0x13, "remote
user terminated"). Dit weerlegde definitief de Ronde 138(c)-hypothese
(TRANSPORT_LE): v153 had die fix al, en het patroon was identiek aan
vóór de fix.

Belangrijke aanvullende aanwijzing van de gebruiker: bij xDrip verschijnt
voor dezelfde sensor wél het standaard Android-koppelscherm (hetzelfde als
bij bv. een auto), en een eenmaal ingevoerde koppelcode hoeft niet opnieuw.
Dat wijst erop dat de G7 voor xDrip WEL op de SMP-aanvraag reageert en tot
een echte OS-koppeling komt — het probleem zit dus specifiek in hoe/wanneer
ONZE app die aanvraag doet, niet in "de G7 accepteert nooit OS-bonding".

**Diagnose (deel 2 — ATT-laag, de daadwerkelijke oorzaak).** Uitbreiding
van de decoder naar de ATT/GATT-laag (naast de SMP-laag) legde het
volgende bloot, consistent in alle 3 gecontroleerde pogingen: de allerlaatste
schrijfactie vóór `createBond()` — de TIME_EXTENDED-write naar de
Authentication-characteristic — kreeg zijn eigen Write Response pas 15-70ms
NADAT de SMP Pairing Request al verstuurd was. M.a.w.: `createBond()` werd
aangeroepen terwijl de sensor onze vorige write nog aan het verwerken was.

In de code (`DexcomG7Driver.kt`) bleek dit exact te kloppen:
`writeCharacteristic()` is een gewone (niet-suspend) functie die terugkeert
zodra Android de write lokaal heeft aangenomen — niet zodra de sensor 'm
daadwerkelijk kreeg. De TIME_EXTENDED-write op deze plek was, anders dan
alle voorgaande stappen in de handshake, nooit in een await-helper
gewikkeld; `createBond()` volgde er zonder enig suspension-point direct op.
Dat is dus geen giswerk meer maar een race die letterlijk terug te zien is
in de ruwe HCI-trace.

(Secundaire observatie, niet geïmplementeerd: `DexcomG7Protocol.kt`'s eigen
kdoc bij `TIME_EXTENDED` beschrijft dat xDrip+ een INKOMENDE indicatie met
diezelfde bytes herkent als "sensor vraagt om nu te bonden" en dán pas de
eerste variant terugschrijft — onze code schrijft 'm nu altijd op eigen
initiatief, zonder op zo'n binnenkomend signaal te wachten. Dat kan een
verdere verfijning zijn als de onderstaande fix niet voldoende blijkt.)

**DexcomG7Driver.kt.** Nieuwe `pendingWriteAckDeferred`-veld +
`awaitWriteAck()`-helper (zelfde patroon als de bestaande
`awaitExtraDataPacket`/`awaitAuthIndication`): wacht op de echte
Write Response van `onCharacteristicWrite()` voordat de aanroepende code
verdergaat. De TIME_EXTENDED-write is hier nu in gewikkeld, gevolgd door
een `POST_CHUNK_SETTLE_MS` (500ms) marge — dezelfde marge die elders in dit
bestand al na `writeChunked()` gebruikt wordt — vóórdat `createBond()`
wordt aangeroepen. Bij timeout/mislukte ack: alleen loggen, niet hard
falen (createBond() wordt alsnog geprobeerd, met een duidelijke
diagnostische regel dat de ack ontbrak).

**Verificatie.** Balance-checker: 206/206 accolades, 651/651 haakjes.
Dit is de eerste fix in deze reeks die daadwerkelijk gebaseerd is op een
in de ruwe Bluetooth-trace waargenomen race-conditie i.p.v. een hypothese
over sensor-gedrag — vandaar de uitgebreidere onderbouwing hierboven. Nog
niet op een fysieke sensor getest; dat is de volgende stap.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 154, versionName "0.9.55-g7-await-write-ack-before-bond".

## Ronde 140 (28/08/2026) — G7-koppelcode werd NIET vergeten bij
"sensor op None zetten"; + duiding "onjuiste code"-melding

**Aanleiding.** Gebruiker, terecht en herhaald: als de sensor op "None"
gezet wordt, mag je aannemen dat alles gereset wordt — en toch hoefde de
koppelcode daarna nooit opnieuw ingevoerd te worden, ook niet na het
terugzetten naar G7. Dat is precies het soort mechanisme dat op den duur
tot foutieve-koppelcode-fouten leidt (een nieuwe G7-sensor heeft een NIEUWE
code op de applicator). De gebruiker gaf hierbij ook expliciet aan het
gevoel te hebben niet gehoord te worden op dit punt.

**Diagnose.** Klopte, en was zo bedoeld — maar verkeerd toegepast op G7.
`FclGlucoLinkNavHost.kt`'s `onSensorChosen`/`onClearSensor` onthouden
bewust de laatste Dexcom G6-transmitter-ID over een None-omweg heen (dat is
in Ronde 64/112 EXPLICIET zo gevraagd, want een G6-transmitter is
herbruikbare hardware — dezelfde code blijft geldig). Ronde 112 kopieerde
ditzelfde "onthoud 'm"-patroon 1-op-1 voor G7's koppelcode
(`hasKnownDexcomG7PairingCodeOnce` → sla setup over, koppel direct met de
opgeslagen code) — maar een G7-sensor is GEEN herbruikbare hardware, het is
een wegwerpartikel van ~10 dagen met een eigen code per sensor. Nergens
werd `AppSettings.clearDexcomG7PairingCode(slot)` aangeroepen bij het
verlaten van G7, dus de oude code bleef eeuwig staan en werd bij een nieuwe
sensor stilzwijgend (en dus fout) hergebruikt.

Aanvullend, om de "kan niet koppelen wegens onjuiste code"-melding zelf te
duiden: in de nieuwste bugreport van de gebruiker (01:19-capture) bereikt
de handshake gewoon "Bond state Pairing" — d.w.z. de 4-cijferige
sensorcode klopte, de J-PAKE-authenticatie is voor die poging gewoon
geslaagd (geen "ongeldig bewijs"/"authentication failed" in de log). Wat
daarna misgaat is dezelfde OS-koppelfout als in Ronde 139 onderzocht
(status=19). Android laat bij ELKE mislukte Bluetooth-koppelpoging
standaard een generieke systeemmelding zien in de trant van "Kan niet
koppelen... onjuiste pincode of sleutel" — dat is Android's eigen, vaste
tekst voor willekeurig welke SMP-koppeling faalt, NIET een melding van
onze app over de 4-cijferige sensorcode. Die twee "codes" (de
Dexcom-sensorcode vs. Android's eigen Bluetooth-koppelmechanisme) zien er
voor de gebruiker identiek uit maar zijn technisch volledig gescheiden —
een begrijpelijke bron van verwarring, geen fout van de gebruiker.

Kanttekening: deze specifieke bugreport-capture toont, qua timing, nog
geen spoor van Ronde 139's write-ack-wachtstap (writes volgen elkaar nog
in het oude, snelle ~45ms-ritme op, geen 500ms-marge zichtbaar vóór de
uiteindelijke disconnect) — waarschijnlijk dus nog vóór de installatie van
v154 vastgelegd, en zegt daarom nog niets over of Ronde 139's fix werkt.

**FclGlucoLinkNavHost.kt.** In zowel `onSensorChosen` (bij het kiezen van
een ANDER sensortype terwijl G7 actief was) als `onClearSensor` ("op None
zetten"): `settings.clearDexcomG7PairingCode(slot)` toegevoegd zodra het
vorige actieve type `DEXCOM_G7` was. G6's eigen "onthoud transmitter-ID"-
gedrag blijft ongewijzigd (dat is nog steeds correct voor herbruikbare
hardware).

**Verificatie.** Balance-checker: 162/162 accolades, 347/347 haakjes.

Gewijzigd: `ui/FclGlucoLinkNavHost.kt`, `app/build.gradle.kts`.

versionCode 155, versionName "0.9.56-g7-clear-pairing-code-on-type-switch".

## Ronde 141 (28/08/2026) — het echte antwoord: wachten op de
bond-trigger-indicatie van de sensor zelf, bewezen met een geslaagde
xDrip+-koppeling

**Aanleiding.** Na v155 bleef "kan niet koppelen wegens onjuiste code"
terugkomen. De gebruiker stelde voor: laat xDrip+ daadwerkelijk koppelen
met dezelfde sensor, en genereer DAARVAN een bugreport — grondwaarheid uit
een geslaagde koppeling in plaats van nog een analyse van onze eigen
mislukte pogingen. Uitstekend voorstel, uitgevoerd: de gebruiker koppelde
xDrip+ succesvol en leverde zowel xDrip+'s eigen debug-log als de bugreport
(met HCI-snooplog) van die sessie aan.

**Diagnose — twee bevestigingen in xDrip+'s eigen log.** Regel 21:
"Prompting user to notice pairing request with sound - On Android 8+ you
have to manually pair when requested" — xDrip+ waarschuwt de gebruiker
actief met een GELUID zodra de OS-koppeling begint, want op Android 8+
moet de gebruiker de systeemmelding zelf handmatig bevestigen. Regel 20 vs.
22: Bond state ging om 09:42:48 naar "Pairing" en pas 09:42:58 — een VOLLE
10 SECONDEN later — naar "Paired". Dat is precies de tijd die een mens
nodig heeft om die systeemmelding op te merken en te bevestigen.

**De HCI-snooplog van diezelfde sessie bevestigt dit tot op de byte.** De
SMP-uitwisseling verloopt bij xDrip+ volledig (Pairing Request → Response →
Public Key-uitwisseling → Confirm/Random → daarna een gat van 6,4 seconden
— exact het wachten op de gebruiker — → DHKey Check → geslaagd). Belangrijker
nog, op de ATT-laag vlak vóór de SMP Pairing Request:

```
07:42:47.754526 TX Write Req  → TIME_EXTENDED (06,19) naar Authentication
07:42:47.838358 RX Write Rsp  → (eigen write bevestigd)
07:42:47.839226 RX Handle Value Ind → 06,00 (=TIME_EXTENDED_3, dus isBondTrigger()==true!)
07:42:47.840776 TX Handle Value Conf
07:42:47.971450 TX SMP Pairing Request  → 130ms na de bond-trigger-indicatie
```

xDrip+ wacht dus niet op zijn EIGEN write-ack (wat Ronde 139 deed) maar op
een BINNENKOMENDE indicatie VAN DE SENSOR ZELF, met bytes die exact
overeenkomen met `DexcomG7Protocol.isBondTrigger()` se TIME_EXTENDED_3-
variant — dat mechanisme stond al beschreven in `TIME_EXTENDED`'s eigen
kdoc ("de sensor vraagt om nu te bonden"), maar werd in de code nooit
daadwerkelijk afgewacht. Ronde 139's vaste 500ms-marge na de eigen write-ack
was dus de VERKEERDE voorwaarde — educated guess, geen bewezen mechanisme.
Dit verklaart ook v155's HCI-capture waarin de verbinding al doodging vóór
de 500ms-marge zelfs maar afliep: we zaten op het verkeerde signaal te
wachten.

**DexcomG7Driver.kt.** Ronde 139's `pendingWriteAckDeferred`/
`awaitWriteAck()` + `delay(POST_CHUNK_SETTLE_MS)` volledig verwijderd.
Vervangen door dezelfde `awaitAuthIndication`-helper die de rest van de
handshake ook gebruikt: de TIME_EXTENDED-write wordt verstuurd, en er wordt
gewacht op de eerstvolgende indicatie op het Authentication-kanaal. Bij
timeout of een onherkende indicatie: alleen loggen (niet hard falen) —
`createBond()` wordt hoe dan ook geprobeerd, met een duidelijke
diagnostische regel die aangeeft of het geverifieerde xDrip-patroon wel of
niet gevolgd werd.

**Verificatie.** Balance-checker: 205/205 accolades, 643/643 haakjes. Dit
is de eerste fix in deze hele reeks die niet op onze eigen falende
captures gebaseerd is, maar op een daadwerkelijk geslaagde koppeling met
dezelfde sensor — het hoogste bewijsniveau tot nu toe. Nog niet zelf op de
sensor getest.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 156, versionName "0.9.57-g7-await-bond-trigger-indication".

## Ronde 142 (28/08/2026) — volledige byte-voor-byte vergelijking met xDrip+'s
sessie: het enige verschil was `requestMtu(185)`

**Aanleiding.** v156 getest: 6 pogingen, en in ELKE poging kwam nooit een
bond-trigger-indicatie terug van de sensor (`DiagnosticFileLogger`: "geen
indicatie ontvangen na TIME_EXTENDED (timeout)"). De gebruiker merkte
bovendien scherp op dat er bij xDrip+ een popup/systeemdialoog OVER het
xDrip-scherm heen verschijnt die hij handmatig moet bevestigen, en dat
FCLGlucoLink dat scherm nooit toont — alleen een korte "kan niet koppelen
wegens onjuiste code"-melding. Terechte observatie: die popup is Android's
ACTIONABLE bevestigingsdialoog, die alleen verschijnt als de SMP-
onderhandeling ver genoeg komt om bevestiging nodig te hebben. Onze
verbinding kapt al af vóórdat de sensor ook maar zijn bond-trigger-
indicatie stuurt — Android heeft dus letterlijk niets om een dialoog voor
te tonen, en toont in plaats daarvan zijn eigen generieke faal-toast.

**Diagnose.** In plaats van nog een hypothese: een volledige, letterlijke
byte-voor-byte vergelijking van xDrip+'s bewezen geslaagde HCI-sessie
(bugreport van de gebruiker, gehele sessie vanaf de verbinding) tegen onze
eigen v156-capture (bugreport 10:38:57), stap voor stap door de hele GATT-
opzet heen: service-discovery-volgorde, CCCD-schrijfvolgorde (ExtraData
eerst met NOTIFICATION, dan Authentication met INDICATION — identiek),
CCCD-waarden (identiek), de kale `{0x0A,0x00}`-aftrap naar Authentication
(identiek, zelfde bytes), de chunking naar ExtraData (identiek patroon en
timing). Alles kwam exact overeen — op ÉÉN ding na: onze app stuurt
meteen bij het verbinden een ATT Exchange MTU Request
(`gatt.requestMtu(185)` in `onConnectionStateChange`); xDrip+'s hele
sessie bevat GEEN ENKELE MTU-onderhandeling — gaat rechtstreeks van
verbinden naar service-discovery op de standaard-MTU (23 bytes).

Extra bevestiging dat dit al een inconsistentie in onze eigen code was:
`CHUNK_SIZE`'s eigen kdoc (Ronde 128) zegt letterlijk "BLE-MTU ZONDER
onderhandeling" — de rest van het bestand was dus al geschreven in de
aanname dat er geen MTU-onderhandeling plaatsvindt, terwijl
`onConnectionStateChange` die alsnog aanvroeg.

**DexcomG7Driver.kt.** `gatt.requestMtu(185)` verwijderd — nu meteen
`gatt.discoverServices()` bij `STATE_CONNECTED`, exact xDrip+'s bewezen
volgorde. De nu onbereikbare `onMtuChanged()`-override (riep toch alleen
`discoverServices()` aan) is ook verwijderd.

**Verificatie.** Balance-checker: 203/203 accolades, 645/645 haakjes. Dit
is de eerste fix in deze reeks gebaseerd op een VOLLEDIGE, systematische
sequentie-vergelijking i.p.v. een gedeeltelijke of speculatieve — het
enige overgebleven verschil met een bewezen werkende koppeling. Nog niet
zelf getest.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 157, versionName "0.9.58-g7-remove-mtu-request-match-xdrip".

## Ronde 143 (28/08/2026) — cooldown-hypothese getest en verworpen

**Aanleiding.** Voordat nog een fix werd geraden: de gebruiker liet
FCLGlucoLink 15-20+ minuten volledig met rust (Bluetooth uit, beide apps
afgesloten om onderlinge interferentie uit te sluiten), zette Bluetooth
daarna weer aan en deed precies één schone koppelpoging, ongeacht de
uitkomst gevolgd door een bugreport.

**Diagnose.** De EERSTE poging na die rustperiode faalde identiek aan alle
eerdere pogingen: TIME_EXTENDED-write bevestigd, ~196ms later disconnect
(reden 0x13), nooit een bond-trigger-indicatie ontvangen. Dat sluit de
hypothese "onze eigen agressieve reconnect-lus put het geduld van de
sensor uit" definitief uit — het is geen cooldown-/rate-limit-probleem.

**Verificatie.** Geen codewijziging deze ronde, puur een experiment met een
eenduidige negatieve uitkomst — de aanleiding voor de daadwerkelijke
doorbraak in Ronde 144 hieronder.

## Ronde 144 (28/08/2026) — de ontbrekende certificaat-koppelstap gevonden
en geïmplementeerd

**Aanleiding.** Na Ronde 143's negatieve resultaat: een grondige, met
werkelijke pakketgroottes (het `orig_len`-veld van elk HCI-snoop-record,
NIET het afgekapte `incl_len` — Android's btsnoop-logging knipt elk
pakket standaard af op de eerste ~15 bytes, wat eerdere sessies dit
onderzoek al één keer op het verkeerde been had gezet) herberekende
vergelijking tussen xDrip+'s bewezen geslaagde sessie en onze eigen
v157-capture. Na de AuthChallenge/AuthStatus-stap (die wij al correct
implementeren) voert xDrip+ VIER extra schrijf/indicatie-uitwisselingen
uit op het Authentication-kanaal, elk met een fikse ExtraData-databurst
ervoor, vóórdat `TIME_EXTENDED` geschreven wordt — iets wat onze
`runPairingHandshake()` tot nu toe helemaal niet deed.

**Diagnose.** Teruggevonden in xDrip+'s eigen `libkeks`-broncode
(`jamorham.keks.Plugin.java`): `receivedResponse()`'s `ChallengeReply`-tak
schakelt, zodra authenticatie lukt maar bonden niet, naar een aanvullende
certificaat-gebaseerde wederzijdse-authenticatiestap
(`SendCertificate0` → `SendCertificate1` → `SendCertificate1out` →
`SendCertificate2` → `SendCertificate2out` → `SendKeyChallenge` →
`SendKeyChallengeOut`). Opcodes en pakketgroottes kwamen exact overeen met
de herberekende HCI-capture: `CertInfoTxMessage`/`CertInfoRxMessage`
(opcode 0x0b, 6/7 bytes), `SignChallengeTxMessage` (opcode 0x0c, 17
bytes), en het vaste `CHALLENGE_OUT`-commando (opcode 0x0d — de bytes
`0d,00,02` waren zelfs in de afgekapte capture al zichtbaar en matchten
letterlijk).

Deze stap heeft drie stukken sleutelmateriaal nodig
(`context.partA`/`partB`/`partC`) die xDrip+ zelf uit lokale voorkeuren
(`keks_p1`/`keks_p2`/`keks_p3`) haalt. Onderzoek van xDrip+'s eigen
`Loader.java`/`Dialog.java` liet zien dat de externe, consent-gated
plugin-downloadroute (`askIfNeeded()`) in de broncode volledig
uitgecommentarieerd staat — dus geen netwerk nodig, bevestigt de
gebruiker se observatie dat koppelen zonder internet werkt. xDrip+ heeft
wél een ingebouwde exportfunctie voor precies dit doel: hoofdmenu →
"Share config via QR code" → "Export KEKS key to another phone". De
gebruiker heeft dat scherm op zijn eigen, al succesvol met deze sensor
koppelende xDrip+-installatie opgezocht en de resulterende QR-code
aangeleverd. Decodering (gzip + xDrip+'s eigen, publieke
`QRcodeUtils`-serialisatieformaat) en verificatie tegen de DER-structuur
bevestigden: deel A/B zijn X.509-certificaten (Dexcom se eigen
fabrieks-PKI, CN "DEX00PG1"/"DEX03PG1", CRL bij
`crl.dp.saas.primekey.com`) — naar hun aard openbaar bedoeld
materiaal — en deel C is een PKCS8-DER EC-privésleutel (secp256r1) om de
sensor se "sign challenge" mee te ondertekenen. Zie
`DexcomG7CertMaterial.kt`'s klasse-kdoc voor het volledige, letterlijke
feitenrelaas.

**Nieuw bestand `sensor/dexcomg7/DexcomG7CertMaterial.kt`.** De drie
hex-gecodeerde DER-blokken (`PART_A`/`PART_B`/`PART_C`), elk byte-voor-byte
tegen de aangeleverde QR-code geverifieerd, met volledige herkomst-kdoc.

**`sensor/dexcomg7/DexcomG7Protocol.kt`.** Nieuwe berichten:
`buildCertInfoRequest`/`parseCertInfoResponse` (opcode 0x0b),
`buildSignChallenge`/`randomSignChallenge` (opcode 0x0c), `CHALLENGE_OUT`
(opcode 0x0d, vaste bytes).

**`sensor/dexcomg7/DexcomG7Crypto.kt`.** Nieuwe functie
`signWithCertPrivateKey()` — poort van `DSAChallenger.response()`: SHA-256
over de 16-byte uitdaging, gewone (niet-deterministische) ECDSA-
handtekening via BouncyCastle's kale, niet-JCE-geregistreerde klassen
(`PrivateKeyFactory`/`ECDSASigner`/`SHA256Digest` — zelfde
"geen-provider-lookup"-patroon als de rest van dit bestand), r/s als twee
vast-lange 32-byte unsigned-big-endian waarden aan elkaar geplakt (64
bytes, GEEN DER-encodering — exact wat `DSAChallenger.sequenceToBytes()`
doet).

**`sensor/dexcomg7/DexcomG7Driver.kt`.** Nieuwe `runCertificateExchange()`,
aangeroepen in `runPairingHandshake()`'s `!status.isBonded`-tak, vóór de
bestaande TIME_EXTENDED-wacht (Ronde 141): kondig deel A aan, stuur 'm via
ExtraData; zelfde voor deel B; stuur een eigen 16-byte "sign challenge",
onderteken de sensor se uitdaging-antwoord met deel C; stuur die
handtekening + `CHALLENGE_OUT`, wacht op een indicatie. Faalt de
uitwisseling ergens, dan breekt de handshake af via de bestaande
`failHandshake()`-route — de bestaande reconnect-/backoff-logica handelt
de rest af.

**Verificatie.** Balance-checker: alle vier gewijzigde/nieuwe bestanden
sluitend (accolades en haakjes gelijk aan beide kanten) — één missende
sluithaakje in een nieuw kdoc-blok gevonden en gecorrigeerd tijdens het
checken. Alle drie hex-blokken in `DexcomG7CertMaterial.kt` zijn
programmatisch, byte-voor-byte tegen de brondata uit de QR-code
geverifieerd (geen handmatige overtypfout mogelijk gebleven). Geen
Gradle/Android-SDK beschikbaar in deze werkomgeving om een volledige
build te draaien — zorgvuldige handmatige review van importpaden,
functiesignaturen en het bestaande "geen JCE-provider-registratie"-patroon
i.p.v. compileren. Nog niet zelf op de sensor getest — dit is de eerste
ronde die een STRUCTURELE ontbrekende protocolstap aanpakt in plaats van
een timing-/volgorde-detail, dus een reëel volgende-stap-kandidaat, geen
garantie.

Gewijzigd: `sensor/dexcomg7/DexcomG7Protocol.kt`,
`sensor/dexcomg7/DexcomG7Crypto.kt`, `sensor/dexcomg7/DexcomG7Driver.kt`,
`app/build.gradle.kts`. Nieuw: `sensor/dexcomg7/DexcomG7CertMaterial.kt`.

versionCode 158, versionName "0.9.59-g7-certificate-pairing-stage".

## Ronde 145 (28/08/2026) — eerste echte bonding gelukt; Control-kanaal
verwachtte notificatie, sensor stuurt indicatie

**Aanleiding.** v158 getest: voor het eerst pairt/verbindt de sensor
daadwerkelijk (screenshot bevestigt Android's systeem-koppeldialoog en
"Last connected"-tijdstip) — Ronde 144's certificaatstap werkt dus. Maar
daarna komen geen periodieke updates meer binnen; de statuspagina toont
"No connection for 7 minutes (still trying)".

**Diagnose.** Bugreport (v158, bevestigd via dumpsys) geanalyseerd. Op elke
reconnect komt `AuthStatusRx.isBonded` nu terug als `true` — de
certificaatstap wordt dus terecht overgeslagen, precies zoals
`onAuthAndBondReady()` bedoeld is. Maar vlak na de aanvraag-cyclus
(Control-CCCD-schrijf, dan de 1-byte glucose-aanvraag opcode 0x4E) verbreekt
de sensor de verbinding (reden 0x13) binnen ~200ms, zonder ooit een
glucose-antwoord te sturen — hetzelfde "schrijf-ack, dan meteen weg"-
patroon als eerdere rondes, nu op een nieuwe plek.

Rechtstreekse vergelijking met xDrip+'s EIGEN bewezen geslaagde HCI-capture
(dezelfde referentiesessie als Ronde 141-144) op precies dit punt: xDrip+'s
glucose-antwoord komt terug als een `HandleValueInd` (INDICATIE) — niet als
`HandleValueNotif` (NOTIFICATIE). Onze `onAuthAndBondReady()` schakelde
echter `useIndication = false` (notificatie) in voor de Control-
characteristic. De sensor verwacht kennelijk indicaties op dit kanaal (net
als op Authentication) en breekt af zodra de aanvraag binnenkomt op een
kanaal dat daar niet voor is ingericht.

**`sensor/dexcomg7/DexcomG7Driver.kt`.** `onAuthAndBondReady()`:
`enableNotify(gatt, controlChar, useIndication = false)` →
`useIndication = true`. Eén regel, maar direct uit de bewezen referentie-
capture afgeleid, geen giswerk.

**Verificatie.** Balance-checker: 213/213 accolades, 691/691 haakjes. Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review i.p.v.
build. Nog niet zelf getest, maar dit is de eerste ronde waarbij het
GEHELE koppelproces (inclusief het nieuwe certificaatdeel uit Ronde 144)
al aantoonbaar werkte vóór deze laatste stap faalde — een goed teken.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 159, versionName "0.9.60-g7-control-channel-indication".

## Ronde 146 (28/08/2026) — ~4,3s tijdslimiet ontdekt bij reconnect naar
al-gebonden sensor; POST_CHUNK_SETTLE_MS verlaagd

**Aanleiding.** Na Ronde 145's indicatie-fix meldde de gebruiker: "hij
verbind nog steeds alleen de eerste keer en dan is het stil", met een
logcat-fragment dat 4 opeenvolgende verbindingspogingen toonde — elke
keer verbrak de sensor de verbinding kort nadat de glucose-aanvraag was
verstuurd. Op verzoek stuurde de gebruiker een verse bugreport
(14:24:21) direct na een nieuwe testronde, ditmaal MET een bruikbare
btsnoop-log die exact dat tijdvak dekte.

**Diagnose.** HCI-analyse (met dezelfde `orig_len`-correctie als eerdere
rondes) van deze bugreport liet VIER losse verbindingspogingen zien
binnen ~6 minuten:

1. 12:17:42 — mislukt vóór enige SMP/pairing, sensor verbreekt na 7s
   (reden 0x13), tijdens de J-PAKE-handshake zelf.
2. 12:18:38 — GESLAAGD: `isBonded` kwam terug als `false`, dus de volledige
   route liep (certificaatuitwisseling, verse SMP LE Secure Connections-
   koppeling met ECDH-sleuteluitwisseling, Control-CCCD, glucose-aanvraag)
   — en de sensor stuurde daadwerkelijk een geldige glucosewaarde terug
   via `HandleValueInd`! Dit is de EERSTE keer in dit hele traject dat een
   bugreport een daadwerkelijk ontvangen glucosewaarde bevestigt. Onze
   eigen code verbrak nadien zelf de verbinding (na gebruik, zoals
   bedoeld) — geen bug.
3. 12:22:55 — mislukt: `isBonded` kwam nu meteen `true` terug (reconnect
   naar de zojuist gebonden sensor, rechtstreeks naar
   `onAuthAndBondReady()`, geen certificaatstap nodig). CCCD- en
   glucose-aanvraagschrijven slaagden, maar de sensor verbrak de
   verbinding (reden 0x13, echt door de sensor geïnitieerd — geen "CMD
   Disconnect" van onze kant vooraf) vóórdat ooit een antwoord kwam.
4. 12:23:05 — identiek patroon als poging 3.

Het opvallendste: de tijd tussen ons eigen `LE_Start_Encryption`-commando
en de mislukking was in poging 3 en 4 bijna EXACT gelijk — 4,307s en
4,301s, slechts 5ms uiteen. In de GESLAAGDE poging 2 verstreek vanaf
hetzelfde startpunt maar 3,65s tot de glucosewaarde binnen was. Dat wijst
sterk op een vaste tijdslimiet aan sensorzijde specifiek voor de
"reconnect naar een al-bekende sensor"-route (poging 2's verse-
koppelroute liep in totaal 16s en had dus geen vergelijkbare tijdsdruk).

Alleen al de drie `POST_CHUNK_SETTLE_MS`-pauzes (500ms elk, ná de
ExtraData-chunkreeks van ronde 1/2/3) kostten samen 1,5s — plus 0,96s aan
`CHUNK_DELAY_MS`-pauzes tussen de acht chunks per ronde — ruim de helft
van het beschikbare ~4,3s-budget, nog vóór ronde 0, de auth-aanvraag/
-uitdaging, Control's CCCD en de glucose-aanvraag zelf aan de beurt komen.
`POST_CHUNK_SETTLE_MS`'s eigen herkomst-kdoc (Ronde 128) citeert xDrip+'s
broncode-commentaar "TODO wait for completion?" — dus zelfs xDrip+'s
eigen auteur was hier onzeker, dit was nooit een harde vereiste.

**Kanttekening.** Dit is een sterk vermoeden op basis van precieze,
tweemaal herhaalde timing-consistentie — geen 100% zekerheid, want er is
geen xDrip+-referentiecapture van precies dit "snelle reconnect"-pad om
1-op-1 tegen te vergelijken (de eerder gebruikte referentiesessie betrof
een verse koppeling, niet een reconnect naar een al-gebonden sensor).

**`sensor/dexcomg7/DexcomG7Driver.kt`.** `POST_CHUNK_SETTLE_MS`:
500L → 120L (nog altijd 3× zo lang als `CHUNK_DELAY_MS`, dus geen
nul-marge-gok) — geldt voor alle `writeChunked()`-aanroepen (ronde 1/2/3
én de certificaatstap), wint ~1,1s terug op het krappe pad zonder de al
bewezen werkende verse-koppelroute (die geen tijdsdruk had) negatief te
raken.

**Verificatie.** Balance-checker: 213/213 accolades, 702/702 haakjes.
Geen Gradle/Android-SDK beschikbaar om te compileren — handmatige review
i.p.v. build. Nog niet zelf getest.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 160, versionName "0.9.61-g7-reconnect-timing-margin".

## Ronde 147 (28/08/2026) — Ronde 146's fix hielp niet; sensor accepteert
kennelijk geen hergebruikte LTK, forceer verse SMP-koppeling elke keer

**Aanleiding.** Gebruiker testte v160 en meldde opnieuw geen succes, met
een nieuwe bugreport (15:09:48) — ditmaal met btsnoop-log uit hetzelfde
tijdvak als de test (v160 bevestigd via dumpsys).

**Diagnose.** HCI-analyse toonde 5 verbindingspogingen: poging 1 (verse
koppeling, `isBonded` was `false`) slaagde weer — glucosewaarde ontvangen.
Poging 2 t/m 5 (reconnect naar de al gebonden sensor) faalden allemaal,
mét hetzelfde "schrijf-ack, dan ~150-200ms later weg (reden 0x13)"-patroon
als vóór Ronde 146. Cruciaal: Ronde 146's verlaagde `POST_CHUNK_SETTLE_MS`
werkte wél zoals bedoeld — de sensor werd nu ~800ms-1s sneller bereikt
(bijv. van verbinding tot glucose-aanvraag-schrijf-ack in 3,34s i.p.v.
eerder ~4,2s) — maar dat loste niets op. De ~4,3s-tijdslimiet-hypothese uit
Ronde 146 was dus WEERLEGD: geen vaste tijd vanaf `LE_Start_Encryption`,
want de mislukkingen kwamen nu bij ~3,4-3,5s in plaats van ~4,3s, nog
steeds mislukt.

Het patroon dat WEL standhoudt over beide bugreports samen (6 mislukte
reconnect-pogingen, 2 geslaagde verse koppelingen): bij een geslaagde
poging deed Android altijd een VERSE SMP LE Secure Connections-koppeling
(zichtbaar in de HCI-capture: Pairing_Request/Response,
Pairing_Public_Key-uitwisseling met ECDH, Pairing_Confirm/Random,
Pairing_DHKey_Check) vóórdat de glucosewaarde binnenkwam. Bij elke
mislukte poging herkende de SENSOR zichzelf al als gebonden
(`AuthStatusRx.isBonded == true`), dus ging de code rechtstreeks naar
`onAuthAndBondReady()` — Android hergebruikte dan stilzwijgend de
OPGESLAGEN LTK (`LE_Start_Encryption` met een bestaande sleutel, GEEN
nieuwe SMP-onderhandeling zichtbaar) — en precies dan levert de sensor
nooit de glucose-indicatie af.

**Werkhypothese** (sterk vermoeden op basis van 100%-consistente
correlatie over 8 pogingen in 2 onafhankelijke bugreports, geen bevestigde
Dexcom-documentatie): de G7-sensor accepteert hergebruik van een gecachte
LTK niet voor het vrijgeven van een meting en verwacht bij elke verbinding
een verse SMP-koppeling — vermoedelijk een bewuste anti-replay-maatregel.

**`sensor/dexcomg7/DexcomG7Driver.kt`.** `runPairingHandshake()`:
de `if (!status.isBonded) { ... } else { onAuthAndBondReady(gatt) }`-
splitsing verwijderd. De certificaatuitwisseling (Ronde 144) blijft alleen
in de `!status.isBonded`-tak, maar de TIME_EXTENDED-wacht en
`createBond()`-stap (Ronde 138/141) lopen nu ALTIJD. Vlak vóór
`createBond()`: als `gatt.device.bondState == BOND_BONDED`, eerst
`removeBond()` aanroepen (via reflectie, net als `createBond
(TRANSPORT_LE)` hieronder — publieke maar niet in de SDK-stub
gedeclareerde methode) — anders is een hernieuwde `createBond()` een
no-op omdat Android het toestel al gebonden acht, en wordt er dus nooit
een verse SMP-onderhandeling geforceerd.

**Kanttekening.** Mogelijk neveneffect: het Android-systeemkoppeldialoog
kan nu bij elke reconnect terugkomen in plaats van alleen bij de
allereerste koppeling. Als dat gebeurt, bevestigt dat de hypothese
gedeeltelijk — ook al is voortdurend een dialoog moeten bevestigen geen
ideale eindtoestand; dat zou dan het volgende te onderzoeken punt zijn.

**Verificatie.** Balance-checker: 218/218 accolades, 735/735 haakjes.
Geen Gradle/Android-SDK beschikbaar om te compileren — handmatige review
i.p.v. build. Nog niet zelf getest.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 161, versionName "0.9.62-g7-force-fresh-repair".

## Ronde 148 (28/08/2026) — Ronde 147 verworpen; het echte probleem was de
overbodig herhaalde J-PAKE-handshake, niet de LTK-hergebruik-status

**Aanleiding.** De gebruiker had v161 gecompileerd maar nog niet getest,
en liet in de tussentijd xDrip+ zelf nog een keer koppelen/reconnecten
(om zeker te weten dat xDrip+'s eigen systeemdialoog maar één keer
verschijnt), met een bugreport tijdens die xDrip+-sessie. Op mijn verzoek
werd deze data eerst geanalyseerd, vóórdat v161 werd getest.

**Diagnose.** HCI-analyse van xDrip+'s EIGEN reconnects (4 verbindingen in
deze capture) liet iets doorslaggevends zien: 2 geslaagde reconnects,
BEIDE via een hergebruikte LTK (`LE_Start_Encryption` met een bestaande
sleutel, binnen ~70ms na verbinden, GEEN nieuwe SMP-onderhandeling
zichtbaar) — precies het scenario dat Ronde 147 als oorzaak van falen
aanwees. Dit weerlegt Ronde 147's hypothese volledig: LTK-hergebruik werkt
prima voor xDrip+.

Het cruciale verschil: in xDrip+'s geslaagde, snelle reconnect staan er
GEEN schrijfacties naar de ExtraData-characteristic vóór de auth-aanvraag
— xDrip+ slaat de VOLLEDIGE ronde 0-3 J-PAKE-handshake over en schrijft
rechtstreeks de auth-aanvraag (opcode 0x02) naar Authentication. Dit is
xDrip+'s eigen `Plugin.java`-gedrag (`context.savedKey`/"RoundStart ->
meteen RequestAuth"), dat al in Ronde 112's klasse-kdoc bewust NIET
geport was — toen ingeschat als "puur een performance-optimalisatie, geen
correctheids-vereiste". Die inschatting blijkt onjuist: het lijkt
vereist te zijn om binnen de tijd te blijven die de sensor toestaat
tussen verbinden en het vrijgeven van een meting (consistent met Ronde
146's timing-observatie, die de symptomen zag maar de verkeerde oorzaak
identificeerde).

`DexcomG7Crypto.kt`'s `calculateHash()` ondersteunde hergebruik van
`context.savedKey` overigens al (geport, maar nooit door de driver
gebruikt) — de driver deed altijd de volledige handshake.

**`sensor/dexcomg7/DexcomG7Driver.kt`.** Nieuwe velden `savedSessionKey`/
`savedSessionKeyDeviceAddress` (in-memory, geldig voor de levensduur van
de driver-instantie). `runPairingHandshake()`: als er voor dit toestel al
een opgeslagen sleutel is ÉN Android het toestel nog `BOND_BONDED` acht,
worden ronde 0-3 (Stap A/B/C) overgeslagen, `ctx.savedKey` gezet, en gaat
Stap D rechtstreeks de auth-aanvraag sturen (zonder het niet-bestaande
ronde-3-pakket). Bij een mislukte auth-aanvraag/uitdaging-verificatie met
een hergebruikte sleutel wordt de cache gewist, zodat de eerstvolgende
poging weer de volledige handshake doet. Na een geslaagde VOLLEDIGE
handshake wordt de afgeleide sleutel opgeslagen voor de volgende
reconnect.

Ronde 147's wijzigingen teruggedraaid: de `removeBond()`-reflectie-aanroep
verwijderd, en de TIME_EXTENDED-wacht/`createBond()`-stap weer terug
binnen de `if (!status.isBonded)`-tak (was: altijd) — xDrip+'s eigen
capture toont deze stap simpelweg niet op een geslaagde, snelle reconnect.

**Verificatie.** Balance-checker: 223/223 accolades, 745/745 haakjes. Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review i.p.v.
build. Nog niet zelf getest — v161 is NIET getest (op mijn advies eerst
deze analyse), v162 bevat zowel de terugdraai van Ronde 147 als de nieuwe
J-PAKE-sleutelhergebruik-fix.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 162, versionName "0.9.63-g7-jpake-session-key-reuse".

## Ronde 149 (28/08/2026) — echte oorzaak van de onregelmatige verbindings-
cadans: een zelfopgelegde Android-scanplafond-deadlock, niet de sensor

**Aanleiding.** Na Ronde 148 concludeerde ik te snel dat het software-deel
"klaar" was omdat de sensor consequent "Sensor Failed 7" rapporteerde —
de gebruiker wees er terecht op dat dit te makkelijk was: xDrip+ maakt wél
betrouwbaar elke 5 minuten opnieuw contact (en geeft dus consequent door
wat de sensor OOK aan xDrip+ meldt), terwijl FCLGlucoLink dat niet deed.
Dat is een aparte, legitieme bug, los van de kapotte sensor.

**Diagnose.** Met het door de gebruiker meegestuurde eigen diagnostiek-
logbestand (`fclglucolink_2026-08-28 16.40.txt`, niet alleen de bugreport)
kon de VOLLEDIGE verbindingsgeschiedenis van de dag gereconstrueerd
worden. Succesvolle uitwisselingen (elke keer met glucosewaarde 48,
state=SensorFailed7 — consistent, dus wél degelijk de sensor, geen bug in
de interpretatie daarvan) kwamen sterk onregelmatig binnen: 13:30, 13:57,
14:18, 15:01, 16:25, 16:32, 16:42 — gaten van 7 tot 84 minuten, in plaats
van de bedoelde 5.

Tussen de pogingen door stonden herhaalde `"scan failed code=2"`-regels
(Android's eigen `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` — het
systeembrede plafond op hoe vaak een app scans mag starten/stoppen binnen
een tijdvenster), in uitbarstingen van ruim 2 minuten, elke 5-10 seconden
herhaald. `onScanFailed()` riep echter dezelfde `backoffAndRetry()` aan als
een gewone mislukte GATT-verbinding (marge 1-10s) — veel te kort om
Android's eigen teller te laten leeglopen, dus de code probeerde binnen
hetzelfde plafond-venster gewoon opnieuw en hield het plafond zelf in
stand: falen -> snel opnieuw -> nog steeds geblokkeerd -> falen -> ...
Tussen zulke uitbarstingen door: complete stiltes van 5-10 minuten zonder
ENIGE logregel, vermoedelijk `scheduleRearm()`'s eigen 390-seconden-
wachttijd die afloopt zonder dat `onScanResult` ooit vuurt (Android's
achtergrond-scanbeperkingen leveren soms stilzwijgend geen resultaten,
zonder foutcode) — een stille periode betekent hier dus niet "er gebeurde
niets", maar "er was niets te loggen".

**`sensor/dexcomg7/DexcomG7Driver.kt`.** Nieuwe constante
`SCAN_THROTTLE_BACKOFF_MS = 90_000L` (ruim voorbij `ScanRateLimiter`'s
eigen 31-seconden-venster). `onScanFailed()`: bij foutcode 2
(`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`) of 6
(`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, API 30+) wordt nu deze veel
langere marge gebruikt i.p.v. de korte, gewone foutmarge — andere
scanfouten (bijvoorbeeld een tijdelijke hardware-fout) blijven de
bestaande korte marge gebruiken.

**Kanttekening.** Dit verklaart een deel van de waargenomen onregel-
matigheid met directe logbewijs; of dit de VOLLEDIGE verklaring is (met
name de stille 390-seconden-gaten, waar geen directe foutcode-logregel
voor bestaat) is aannemelijk maar niet 100% bevestigd. `DexcomG6Driver.kt`/
`CareSensAirDriver.kt` delen dezelfde `backoffAndRetry()`-aanpak bij
scanfouten en zijn hier bewust NIET meegenomen — dit was gericht op de
concrete G7-klacht, een vergelijkbare fix daar is een aparte, latere
afweging.

**Verificatie.** Balance-checker: 226/226 accolades, 762/762 haakjes. Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review i.p.v.
build. Nog niet zelf getest.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `app/build.gradle.kts`.

versionCode 163, versionName "0.9.64-g7-scan-throttle-backoff".

## Ronde 150 (28/08/2026) — G7 batterij-/firmwareversie-uitvraag (mirror van
xDrip+'s "Firmware Version"/"Voltage A"/"Voltage B"-velden)

**Aanleiding.** Vraag van de gebruiker: "en een aanvullende vraag als hij
dan verbind geeft hij dan ook de data als batterij en firmware version
terug zoals xdrip ook netjes doet ondanks een error in de Bg waarde van de
sensor die bij mij bekend is." — xDrip+'s eigen statusscherm toont voor
dezelfde sensor "Firmware Version: 32.192.109.40", "Voltage A: 286",
"Voltage B: 266", onafhankelijk van de bekende "Sensor Failed 7"-status.
FCLGlucoLink's G7-driver deed nog helemaal geen batterij-/firmware-
uitvraag (DexcomG7StatusScreen.kt toonde deze velden al wel als "—"-
placeholders sinds Ronde 130, met de aantekening "NIET GEPORT").

**Onderzoek.** xDrip+'s eigen broncode (`g5model/BatteryInfoTxMessage.
java`/`BatteryInfoRxMessage.java`/`VersionRequestTxMessage.java`/
`VersionRequestRxMessage.java`, plus `Ob1G5StateMachine.
checkVersionAndBattery()`) bevestigt dat G7 hiervoor het KLASSIEKE
G5/G6-berichtenstel hergebruikt: opcode(1) + CRC16(2, little-endian) over
hetzelfde `Control`-kanaal dat al voor het glucoseverzoek gebruikt wordt.
`checkVersionAndBattery()` is onvoorwaardelijk gedeeld tussen G5/G6/G7
(bevestigd via een nabijgelegen commentaar dat G5/G6/G7 onderscheidt via
`usingG6() ? (shortTxId() ? "G7" : "G6") : "G5"` — G7 is een `usingG6()`-
tak, geen aparte G7-only code). Dit is dus GEEN gok: hetzelfde patroon is
al bewezen werkend voor onze eigen G6-driver (`DexcomG6Protocol.kt`'s
`buildBatteryInfoRequest`/`parseBatteryInfo`, `DexcomG6Driver.kt`'s
batterij-uitvraag), inclusief de herbruikbare CRC16-implementatie
(`DexcomG6Crypto.crc16`, dezelfde CCITT-16-tabel).

**Wijziging.**

- `sensor/dexcomg7/DexcomG7Protocol.kt`: nieuwe sectie met een eigen
  `appendCrc()`-helper (hergebruikt `DexcomG6Crypto.crc16`, cross-package
  — bewust ANDERS dan de rest van dit bestand, dat voor de auth-handshake
  bewust GEEN CRC gebruikt, zie klasse-kdoc) plus
  `buildBatteryInfoRequest()`/`BatteryInfoRx`/`parseBatteryInfo()` (opcode
  0x22 aanvraag, 0x22/0x23 antwoord — letterlijke mirror van
  `DexcomG6Protocol.kt`) en `buildFirmwareVersionRequest()`/
  `FirmwareVersionRx`/`parseFirmwareVersion()` (opcode 0x20 aanvraag,
  0x21 antwoord — dotted-string-firmwarevelden, xDrip+'s "versie 0"-
  variant, ANDERS dan G6's bestaande `buildVersionRequest2()`/opcode 0x52,
  dat opwarmtijd/sensor-levensduur opvraagt, geen firmwarestring).
- `data/AppSettings.kt`: `setDexcomG7BatteryInfo`/`dexcomG7BatteryInfo`/
  `getDexcomG7LastBatteryQueryAtMsOnce` en `setDexcomG7FirmwareInfo`/
  `dexcomG7FirmwareInfo`/`getDexcomG7LastFirmwareQueryAtMsOnce` — letter-
  lijke mirror van de bestaande G6-tegenhangers, per-slot opgeslagen.
- `sensor/dexcomg7/DexcomG7Driver.kt`: twee nieuwe pending-deferred-velden
  (`pendingBatteryDeferred`/`pendingFirmwareDeferred`, opgeruimd in
  `resetAuthState()`), twee nieuwe opcodes in `handleControlNotification()`
  (0x22/0x23 -> batterij, 0x21 -> firmware), en `queryBatteryIfStale()`/
  `queryFirmwareIfStale()` — aangeroepen vanuit `requestGlucose()`, VÓÓR
  het glucoseverzoek (zelfde volgorde als `DexcomG6Driver.kt`'s
  `runControlSequence()`, mirror van xDrip+'s eigen
  checkVersionAndBattery()-vóór-doGetData()-volgorde). Batterij: elke 8
  uur opnieuw (`BATTERY_QUERY_INTERVAL_MS`, zelfde als G6). Firmware: elke
  30 dagen (`FIRMWARE_QUERY_INTERVAL_MS`) — verandert nooit tussen
  verbindingen, dus puur een "opnieuw proberen als het nog niet lukte"-
  interval, geen periodieke verversing. Beide zijn NIET blokkerend voor de
  glucose-uitwisseling: een timeout/`null`-antwoord wordt alleen gelogd,
  geen `gatt.disconnect()` — de nieuwe, nog ongeverifieerde code kan de
  net gestabiliseerde (Ronde 148/149) kernfunctionaliteit dus niet in
  gevaar brengen.
- `ui/DexcomG7StatusScreen.kt`: "Firmware version"/"Battery last
  queried"/"Voltage A"/"Voltage B" tonen nu de echte waarden i.p.v. altijd
  "—" (via de nieuwe `AppSettings`-Flows). "Sensor status"/"Brain
  state"/"Transmitter days" blijven bewust "—" — vallen buiten dit
  batterij-/firmwareverzoek.

**Vertrouwensniveau — EXPLICIET LAGER dan het glucoseverzoek zelf.** Dit
is architectuur-bewijs uit xDrip+'s gedeelde broncode (dezelfde opcodes/
CRC-envelop/Control-kanaal als het al meerdere keren in de gebruiker's
eigen bugreports teruggeziene glucoseverzoek) en hetzelfde patroon dat al
bewezen werkt voor G6 — maar NOG NIET byte-voor-byte bevestigd tegen een
echte G7-sensor via een HCI-capture. Als de sensor niet reageert (verkeerd
opcode, ander antwoordformaat) blijft de rij gewoon "—" staan — geen crash,
geen verbroken verbinding, geen effect op de glucosecyclus.

**Kanttekening.** Dit voegt twee extra schrijf-/wacht-rondes toe aan het
venster waarin de verbinding open moet blijven per cyclus (tot
`BATTERY_TIMEOUT_MS`/`FIRMWARE_TIMEOUT_MS` = 10s elk, alleen bij een
"stale" cache — de meeste cycli slaan dit dus over). In combinatie met
Ronde 149's nog niet zelf bevestigde cadans-fix is dit iets om in dezelfde
volgende testronde in de gaten te houden: mocht de cadans na deze ronde
juist weer onregelmatiger worden, is dit de eerste plek om te verdenken.

**Verificatie.** Balance-checker op alle vier gewijzigde bestanden: alle
accolades/haakjes in balans. Geen Gradle/Android-SDK beschikbaar om te
compileren (geen `kotlinc` in deze sandbox) — handmatige review van
signatures/aanroepen i.p.v. build. Nog niet zelf getest tegen een echte
G7-sensor.

Gewijzigd: `sensor/dexcomg7/DexcomG7Protocol.kt`,
`sensor/dexcomg7/DexcomG7Driver.kt`, `data/AppSettings.kt`,
`ui/DexcomG7StatusScreen.kt`, `app/build.gradle.kts`.

versionCode 164, versionName "0.9.65-g7-battery-firmware-query".

## Ronde 151 (28/08/2026) — KRITIEKE FIX: Ronde 150's firmwareverzoek maakte
vrijwel elke reconnect na de eerste kapot

**Aanleiding.** De gebruiker testte v164 en meldde: "Hij geeft maar 1 keer
bij opstarten connectie." Ik sloot niet te snel af dit keer — de
gebruiker stuurde meteen het eigen diagnostiek-logbestand mee
(`fclglucolink_2026-08-28 17.58.txt`), waarmee de oorzaak binnen enkele
minuten rechtstreeks uit de logregels af te lezen was.

**Diagnose — dit was MIJN fout, niet de sensor.** Het log toont het
volledige patroon:

```
17:47:54.025 battery voltageA=288 voltageB=268 temp=0        <- batterij werkte
17:47:54.107 write ok for f8083534-...                       <- firmwareverzoek verstuurd (opcode 0x20)
17:47:54.110 unhandled Control opcode=32 bytes=32,2           <- sensor wijst het af (2-byte echo, GEEN opcode 0x21)
17:48:04.057 firmware query timed out of niet ondersteund     <- volle 10s gewacht op een antwoord dat nooit kwam
17:48:04.157 write ok for f8083534-...                        <- pas NU het glucoseverzoek — dit keer nog op tijd
17:48:04.169 glucose value 48 IGNORED ... state=SensorFailed7 <- gelukt
17:48:04.189 STATE_DISCONNECTED status=0                      <- nette eigen disconnect
```

Deze specifieke G7-sensor accepteert het firmwareverzoek (opcode 0x20,
xDrip+'s "versie 0"-variant) dus simpelweg niet — antwoordt met een
2-byte echo van ons eigen opcode i.p.v. het verwachte opcode 0x21 met
18+ bytes. Op zich onschuldig (Ronde 150 se code disconnect't niet bij
een timeout), MAAR: Ronde 150's `queryFirmwareIfStale()` schreef de
"laatst opgevraagd"-tijdstempel ALLEEN weg bij een GESLAAGDE parse
(`setDexcomG7FirmwareInfo()`). Omdat deze sensor nooit een geldig
antwoord geeft, bleef die tijdstempel voor altijd `null` — dus werd de
afgewezen aanvraag bij ELKE volgende reconnect herhaald, in plaats van
eens per 30 dagen zoals bedoeld. Erger nog, in alle volgende cycli
(17:52-17:58) bleek de sensor zelf, ~3,3 seconden na de afwijzing, de
verbinding te verbreken (status=19) — VOORDAT het glucoseverzoek ooit
verstuurd kon worden:

```
17:52:38.668 unhandled Control opcode=32 bytes=32,2
17:52:41.950 STATE_DISCONNECTED status=19                     <- sensor verbreekt zelf, glucose nooit gevraagd
17:52:41.954 firmware query timed out of niet ondersteund
```

Dit herhaalde zich vrijwel elke cyclus tot het einde van het log —
precies het gemelde "verbindt maar 1x". De gebruiker's eigen observatie
("als dit de bluetooth communicatie nu niet in de weg zit hoef je het
niet gelijk aan te passen [...] als de communicatie wel correct
verloopt") was dus terecht een impliciete vraag: het zat wél in de weg,
dus meteen gefixt, niet uitgesteld.

**Wijziging.**

- `data/AppSettings.kt`: nieuwe, APARTE "laatst GEPROBEERD"-tijdstempels
  (`setDexcomG7BatteryQueryAttemptAtMs`/`getDexcomG7BatteryQueryAttemptAtMsOnce`,
  idem voor firmware) — onafhankelijk van of de uitvraag ooit slaagde.
  Mirror van `DexcomG6Driver.kt`'s `setDexcomG6LastVersion2QueryAtMs`-
  patroon, dat dit al goed deed (vóór de schrijf-/wachtstap gezet, niet
  pas na succes) — Ronde 150's G7-code miste dat onderscheid.
- `sensor/dexcomg7/DexcomG7Driver.kt`: `queryBatteryIfStale()`/
  `queryFirmwareIfStale()` toetsen nu tegen de nieuwe attempt-tijdstempel
  (geschreven vóór de write) i.p.v. de succes-tijdstempel. Daarnaast:
  `handleControlNotification()`'s `else`-tak geeft een onherkend opcode nu
  DIRECT door aan een op dat moment wachtende batterij-/firmware-deferred
  (`complete(null)`, fail-fast) i.p.v. de volle 10-seconden-timeout te
  laten aflopen — verkort de "verspilde" verbindingstijd bij een
  afwijzing van ~3-10s naar vrijwel 0, en verkleint het risico dat de
  sensor zelf ongeduldig de verbinding verbreekt vóórdat het
  glucoseverzoek aan de beurt is.

**Kanttekening.** Batterij werkte in het geanalyseerde log gewoon (1x
succesvol, daarna terecht 8u niet opnieuw geprobeerd) — dit blijft
ongewijzigd functioneel. Firmwareversie zelf blijft voor DEZE sensor
waarschijnlijk permanent "—" (de sensor accepteert opcode 0x20 niet) —
dat is een aanvaardbare, cosmetische beperking, geen bug meer: de
uitvraag gebeurt nu nog maar eens per 30 dagen, faalt vrijwel instant,
en kan de glucose-cyclus niet meer ophouden. Een andere
VersionRequestTxMessage-variant (xDrip+ kent er vier: opcodes
0x20/0x4A/0x52+3/0x52+4) proberen is een mogelijke toekomstige
verbetering, maar niet urgent nu het geen schade meer aanricht.

Ook meegenomen uit de gebruiker's melding, nog NIET onderzocht/
geïmplementeerd: het vermoeden dat de G7 (net als G6) op vaste,
activatietijdstip-gebonden zendmomenten werkt (waargenomen in xDrip als
verbindingstijden die steeds op :3 of :8 minuten eindigen, ongeacht de
koppeling zelf) — een interessant aanknopingspunt voor een preciezere
`computeReconnectCooldownMs()`-voorspelling, apart van deze fix.

**Verificatie.** Balance-checker: DexcomG7Driver.kt 245/245 accolades,
843/843 haakjes; AppSettings.kt 251/251 accolades, 796/796 haakjes. Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review.
Nog niet zelf getest (deze fix reageert rechtstreeks op v164's
testresultaat, dus het volgende log zal moeten bevestigen dat reconnects
nu weer normaal doorgaan).

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`, `data/AppSettings.kt`,
`app/build.gradle.kts`.

versionCode 165, versionName "0.9.66-g7-firmware-query-retry-storm-fix".

## Ronde 152 (28/08/2026) — echte xDrip-opcode-volgorde voor firmware +
batterij-/firmwarecache resetten bij nieuwe G7-sensor

**Aanleiding.** De gebruiker testte v165 en stelde drie scherpe vragen op
basis van het resultaat:
1. "Je geeft nu aan dat de firmware maar 1 keer per 30 dagen wordt
   gelezen, een g7 gaat 10 dagen mee, dat zou dus sowieso bij iedere
   nieuwe sensor start moeten worden uitgevraagd."
2. "Dan staat er ergens in de cache ook nog data want bij eerste opstart
   vult hij direct de batterij met ook de datum van de vorige test."
3. "Hoe kan het dat xdrip wel de frimware invult en dat fclglucolink een
   fout geeft [...] xdrip bij de koppeling ook nog niks maar wordt het
   pas gevuld bij de eerst verversing."

Ook meegestuurd: een fris logcat-fragment na v165, dat bevestigde dat
Ronde 151's fix werkt (batterij ok, firmwareverzoek faalt nu vrijwel
instant i.p.v. de verbinding op te houden, en wordt bij de eerstvolgende
reconnect terecht NIET herhaald) — maar de firmwaregegevens zelf bleven
"—".

**Diagnose (vraag 3 — waarom xDrip wél lukt).** Rechtstreeks nagelezen in
xDrip+'s eigen `Ob1G5StateMachine.requiredNextFirmwareDetailsType()`
(vendored bron, `uploads/xDrip-2026.08.08.zip`): xDrip+ probeert helemaal
niet "versie 0" (opcode 0x20) als eerste keus, zoals Ronde 150 aannam —
de ECHTE volgorde is **versie 1 (opcode 0x4A) altijd eerst**, voor élke
transmitter; pas als dat nog niet gelukt is EN de transmitter-ID 6 tekens
lang is (xDrip+'s eigen manier om een G7 te herkennen,
`txid.length() == 6`) volgt versie 0 (opcode 0x20) als tweede poging, en
versie 2 (opcode 0x52) als laatste redmiddel. Ronde 150's keuze voor
versie 0 als "eenvoudigste/meest-compatibele variant" was dus een
ongeverifieerde aanname — en precies de variant die xDrip+ zelf als
LAATST-KANS-tweede-poging behandelt, niet als eerste keus. Dat verklaart
waarschijnlijk waarom deze sensor 'm afwees terwijl xDrip+ (die versie 1
eerst probeert) wel data terugkrijgt.

**Diagnose (vraag 1+2 — stale cache).** Terechte constatering: de
batterij-/firmware-tijdstempels en -waarden worden per SLOT bijgehouden
in `AppSettings` (DataStore), niet per fysieke sensor. Zonder een reset
bij een nieuwe koppelcode zou (a) een gebruiker die een nieuwe G7 plakt de
batterij-/firmwaregegevens van de VORIGE sensor blijven zien totdat er
toevallig een nieuwe uitvraag plaatsvindt, en (b) firmware — met zijn
30-dagen-interval — voor de VOLLEDIGE levensduur van een nieuwe sensor
(10 dagen) nooit opnieuw uitgevraagd worden. Vraag 2's waarneming ("bij
eerste opstart vult hij direct de batterij [...] met de datum van de
vorige test") is deels verwacht gedrag (het statusscherm toont bewust de
laatst bekende waarde tijdens het herverbinden, zelfde patroon als
"Last connected" — dat is GEEN bug op zich) — maar wordt pas een
probleem in combinatie met (a): bij een sensorWISSEL hoort die
"laatst bekende waarde" niet de vorige sensor's waarde te zijn.

**Wijziging.**

- `sensor/dexcomg7/DexcomG7Protocol.kt`: `buildFirmwareVersionRequest()`
  krijgt een `version`-parameter (0/1/2, opcodes 0x20/0x4A/0x52) i.p.v.
  altijd opcode 0x20.
- `sensor/dexcomg7/DexcomG7Driver.kt`: nieuwe constante
  `FIRMWARE_REQUEST_VERSION_ORDER = listOf(1, 0, 2)` (xDrip+'s echte
  volgorde). `queryFirmwareIfStale()` probeert deze drie na elkaar,
  stopt bij de eerste geslaagde parse — dankzij Ronde 151's fail-fast-
  dispatch resolvet een afgewezen variant vrijwel instant, dus drie
  pogingen kosten in de praktijk nauwelijks extra verbindingstijd.
- `data/AppSettings.kt`: nieuwe `clearDexcomG7BatteryAndFirmwareInfo(slot)`
  — verwijdert alle 10 gerelateerde DataStore-sleutels (spanning/temp/
  firmwarevelden + beide "laatst geprobeerd"-tijdstempels).
- `ui/FclGlucoLinkNavHost.kt`: `ROUTE_DEXCOM_G7_SETUP`'s `onConfirmed`
  roept deze nieuwe functie nu aan, op dezelfde plek waar een nieuwe
  koppelcode al `clearDeviceAddress`/`setDexcomG7PairingCode` triggert —
  een nieuwe koppelcode betekent vrijwel altijd een nieuwe fysieke
  sensor.

**Over de gemelde "10-minuten-cadans".** Het meegestuurde logcat-fragment
toont twee opeenvolgende geslaagde cycli: 18:24:53 (disconnect, voorspelde
cooldown ~4min) en de volgende connectie pas om 18:32:37 — dat is 7:44
later dan voorspeld. Te weinig data (1 interval) om hier een harde
conclusie aan te verbinden; dit past zowel bij het al bekende, nog niet
volledig opgeloste cadans-vraagstuk (Ronde 149) als bij de eerder
geopperde hypothese van de gebruiker (G7 zendt mogelijk op vaste,
activatietijdstip-gebonden momenten, los van de pogingen van de app) —
geen van beide is met dit ene datapunt te bevestigen of te verwerpen.

**Verificatie.** Balance-checker op alle vier gewijzigde bestanden: alle
accolades/haakjes in balans. Geen Gradle/Android-SDK beschikbaar om te
compileren — handmatige review. Nog niet zelf getest (opcode 0x4A is,
net als indertijd 0x20, architectuur-bewijs uit xDrip+'s bron — nu wel
met bewijs dat het xDrip+'s EIGEN eerste keus is, maar nog niet
rechtstreeks tegen deze sensor bevestigd).

Gewijzigd: `sensor/dexcomg7/DexcomG7Protocol.kt`,
`sensor/dexcomg7/DexcomG7Driver.kt`, `data/AppSettings.kt`,
`ui/FclGlucoLinkNavHost.kt`, `app/build.gradle.kts`.

versionCode 166, versionName "0.9.67-g7-firmware-opcode-order-and-cache-reset".

## Ronde 153 (28/08/2026) — KRITIEKE FIX: twee gelijktijdig gekoppelde sensoren van hetzelfde type vloeiden samen in de grafiek

**Aanleiding.** Live-melding tijdens een tussentijdse test (de G7-
dataverzameling stond op dat moment bewust even stil — "ik wacht nog even
met meer data verzamelen met de g7 sensor"): "Iemand heeft 2 caresens air
sensoren simultaan gekoppeld ze updaten wel verschillend geven ook een
verschillende caresens nummer en ook verschillende Bg waarden maar in de
grafiek lijken de wwarden weer samen te vleien en er geen goede schijding
te zij tussen de beide slots, net zoals bij mij toen we met 2 slots
begonnen." Beide fysieke sensoren rapporteerden dus zichtbaar correcte,
onafhankelijke data (elk zijn eigen CareSens-serienummer, elk zijn eigen
BG-waarde) — het probleem zat in de app's eigen opslag/weergave, niet in
de sensoren of hun drivers.

**Diagnose.** Sinds "RONDE 79 — 2-sensoren-architectuur" (een veel eerdere
ronde) filterden `GlucoseReadingEntity`/`GlucoseReadingDao`/
`GlucoseReadingStore` per-slot grafiek-/statusdata uitsluitend op
`sensorType: String` (bv. "CARESENS_AIR", "DEXCOM_G6") — er bestond
HELEMAAL GEEN kolom die vastlegde uit welke fysieke `SensorSlot` (A/B) een
meting daadwerkelijk kwam. Dat werkte toevallig zolang de twee actieve
slots verschillende sensortypes draaiden (precies de oorspronkelijke
opstelling van de gebruiker zelf, G6 + CareSens Air) — `sensorType` was
dan toevallig ook altijd een geldige slot-discriminator. Zodra beide slots
HETZELFDE sensortype draaien (zoals hier: CareSens Air + CareSens Air),
komen beide fysieke sensoren se metingen onder EXACT dezelfde
`sensorType`-waarde binnen, en kan geen enkele sensorType-gefilterde query
ze nog uit elkaar houden — precies het gemelde "samen vloeien". Dit was
geen regressie van een recente ronde, maar een sinds Ronde 79 latent
aanwezige architecturale aanname (sensorType ≈ slot-identiteit) die pas nu,
met de eerste test van twee identieke sensortypes tegelijk, zichtbaar werd.

**Wijziging.**

- `data/GlucoseReadingEntity.kt`: nieuwe nullable kolom `slot: String?`.
  `GlucoseReading.toEntity()` vereist nu een `slot: SensorSlot`-parameter.
- `data/FclGlucoLinkDatabase.kt`: `MIGRATION_7_8` (versie 7 → 8) —
  `ALTER TABLE glucose_readings ADD COLUMN slot TEXT`, zelfde nullable-
  zonder-default-patroon als eerdere migraties (rawSensorMgdl/
  calibratedMgdl) — geen destructieve migratie.
- `data/GlucoseReadingDao.kt`: de oude `recentReadingsForSensorType()`/
  `latestReadingForSensorType()`/`deleteFromForSensorType()` (sensorType-
  gefilterd) zijn VERWIJDERD, vervangen door `recentReadingsForSlot()`/
  `latestReadingForSlot()`/`deleteFromForSlot()` (slot-gefilterd).
- `data/GlucoseReadingStore.kt`: `record()`/`recentReadings()`/
  `latestReading()`/`trimFrom()` nemen nu allemaal een `SensorSlot`-
  parameter i.p.v. `SensorType`.
- Alle aanroeppunten omgezet van `sensorType =`/`SensorType.X` naar
  `slot =`/`SensorSlot.X`: `sensor/ble/BleConnectionService.kt` (de
  `record()`- en `trimFrom()`-aanroep in de opslag-pijplijn),
  `ui/CombiScreen.kt` (4 plekken), `ui/StatusScreen.kt` (3 plekken),
  `ui/CalibrationScreen.kt` (3 plekken), `ui/CareSensAirStatusScreen.kt`,
  `ui/DexcomG6StatusScreen.kt`, `alarm/AlarmMonitor.kt`,
  `alarm/AlarmActivity.kt`.

**Migratie-afweging.** De nieuwe `slot`-kolom is nullable zonder default —
bestaande rijen van vóór deze migratie krijgen `NULL` (geen bekende slot)
i.p.v. te worden gewist. Omdat de nieuwe per-slot-queries exact op
`slot = :slot` filteren (NULL komt daar niet in mee), verdwijnen die oude
rijen simpelweg tijdelijk uit de per-slot-tabbladen (Slot A/Slot B) totdat
ze het bestaande 48-uurs-opruimvenster uitgroeien — de ongefilterde
"Combi"-tab blijft ze gewoon tonen. Een kortstondig, zichzelf herstellend
gat in de historie, geen blijvend dataverlies — zelfde soort afweging als
eerdere nullable-kolom-migraties in dit project (rawSensorMgdl,
calibratedMgdl).

**Bekende, NOG NIET gefixte follow-up.** `SensorSwitchEventEntity`/
`SensorSwitchEventStore` (de wisselmarkers die op de grafiek verschijnen
bij een nieuwe sensorsessie) hebben EXACT dezelfde architecturale
tekortkoming — alleen een `sensorType`-kolom, geen `slot`-kolom. Bewust
NIET meegenomen in deze ronde om de scope beperkt te houden tot de
daadwerkelijk gemelde bug (de BG-grafiek/status-data zelf); met twee
gelijktijdig actieve slots van hetzelfde sensortype zouden wisselmarkers
van de ene sensor dus in theorie ook op de andere sensor's grafiek kunnen
verschijnen. Opgemerkt als bekend openstaand punt voor een volgende ronde.

**Verificatie.** Alle 12 gewijzigde bestanden gecontroleerd met een
Python-tokenizer die string-literalen en comments correct negeert (i.p.v.
een simpele grep-telling, die door de vele Nederlandse toelichtingen met
haakjes onbetrouwbaar bleek) — alle accolades/haakjes in balans, geen
open constructies. Geen Gradle/Android-SDK beschikbaar om te compileren —
handmatige review. Nog niet door de gebruiker zelf getest tegen de
daadwerkelijke 2×-CareSens-Air-opstelling die de bug meldde.

Gewijzigd: `data/GlucoseReadingEntity.kt`, `data/GlucoseReadingDao.kt`,
`data/FclGlucoLinkDatabase.kt`, `data/GlucoseReadingStore.kt`,
`sensor/ble/BleConnectionService.kt`, `ui/CombiScreen.kt`,
`ui/StatusScreen.kt`, `ui/CalibrationScreen.kt`,
`ui/CareSensAirStatusScreen.kt`, `ui/DexcomG6StatusScreen.kt`,
`alarm/AlarmMonitor.kt`, `alarm/AlarmActivity.kt`, `app/build.gradle.kts`.

versionCode 167, versionName "0.9.68-glucose-slot-separation-fix".

## Ronde 154 (28/08/2026) — KRITIEKE FIX: CareSens Air toonde de start-/eindtijd van de vórige sensor na het koppelen van een nieuwe

**Aanleiding.** Live-melding: "bij het koppelen van een nieuwe caresens
sensor bakt hij de start en einde tijd van de oude vorige sensor nog op."
Gevraagd om dit eerst in de code te bevestigen vóór er iets gefixt werd.

**Diagnose (bevestigd).** `careSensAirSensorStartedAtMs(slot)` — de enige
bron voor zowel de Start-tijd als de afgeleide "End (est.)"-tijd (start +
15 dagen) op `CareSensAirStatusScreen.kt`/het compacte kaartje op
`StatusScreen.kt` — wordt uitsluitend geschreven vanuit
`CareSensAirDriver.kt`'s handler voor het 0xC0/2-antwoord
(StartSensorResponse), dus pas zodra de NIEUWE fysieke sensor
daadwerkelijk een live GATT-uitwisseling heeft voltooid. De koppel-/
wisselflow zelf (`FclGlucoLinkNavHost.kt`'s `ROUTE_CARESENS_AIR_CHOICE`/
`ROUTE_CARESENS_AIR_SCAN`) deed al wel een paar opruimstappen (oude BLE-
verbinding stoppen, status op Disconnected, device-adres wissen,
sensortype/scanresultaat vastleggen), maar riep nergens een reset aan
voor `careSensAirSensorStartedAtMs`/`careSensAirLastConnectedAtMs`. Dus
bleef de VORIGE sensor's Start-/End-tijd (en "Last connected") gewoon
zichtbaar vanaf het moment van koppelen tot de eerste geslaagde
GATT-uitwisseling met de nieuwe sensor — bij een haperende verbinding kan
dat een tijd aanhouden. Zelfde bugklasse als Dexcom G7's stale batterij-/
firmwarecache (Ronde 152), alleen was daar destijds al een proactieve
reset voor gebouwd en voor CareSens Air nog niet.

**Wijziging.**

- `data/AppSettings.kt`: nieuwe `clearCareSensAirSensorSession(slot)` —
  verwijdert de `caresens_sensor_started_at_ms`- en
  `caresens_last_connected_at_ms`-sleutels uit DataStore.
- `ui/FclGlucoLinkNavHost.kt`: beide CareSens Air-koppelpaden
  (`ROUTE_CARESENS_AIR_CHOICE`'s `onExistingSensor` én
  `ROUTE_CARESENS_AIR_SCAN`'s `onScanned`) roepen deze nieuwe functie nu
  aan, vóór de navigatie naar `PairingScreen` — zelfde plek/patroon als de
  bestaande `clearDeviceAddress(slot)`-aanroep ernaast.

**Bekend, NOG NIET meegenomen randgeval.** Bij de "Already-running
sensor"-route wordt `saveCareSensAirScan(slot, ...)` bewust NIET
opnieuw aangeroepen (er is geen barcode-scanresultaat) — het oude,
gescande serienummer (`scan?.serial`, getoond in `SensorInfoBlock` op
`CareSensAirStatusScreen.kt`) kan daardoor in theorie nog even de vorige
sensor's serienummer tonen totdat er iets anders het overschrijft. Dit
scherm toont in de praktijk vooral het via GATT rechtstreeks van de
sensor gelezen serienummer voor de actieve verbinding, dus de praktische
impact lijkt kleiner dan bij de Start-/End-tijd — niet in deze ronde
onderzocht/gefixt, genoteerd als mogelijk vervolgpunt.

**Verificatie.** Beide gewijzigde bestanden gecontroleerd met dezelfde
Python-tokenizer als Ronde 153 (negeert string-literalen/comments correct)
— accolades/haakjes in balans. Geen Gradle/Android-SDK beschikbaar om te
compileren — handmatige review. Nog niet door de gebruiker zelf getest
tegen een daadwerkelijke sensorwissel.

Gewijzigd: `data/AppSettings.kt`, `ui/FclGlucoLinkNavHost.kt`,
`app/build.gradle.kts`.

versionCode 168, versionName "0.9.69-caresens-stale-session-cache-fix".

## Ronde 155 (28/08/2026) — KRITIEKE FIX: reconnect-cadans liep vast op 10 minuten i.p.v. 5 (G6, G7 én CareSens Air) + app opent nu op de AAPS-zendende slot

**Aanleiding.** De gebruiker had specifiek gevraagd om de G7 een aantal uren
te laten draaien om de al sinds Ronde 149/151/152 vermoede "10-minuten-
cyclus" eindelijk met genoeg data te kunnen bevestigen of weerleggen — niet
om de sensor zelf te testen (die geeft al de hele dag geen echte BG-waarde
meer door, bekend en irrelevant voor deze test, de sensor bleef bruikbaar
genoeg om de verbinding zelf mee te meten). Een eerste analyse van de
meegestuurde log ging hier volledig aan voorbij en focuste per ongeluk op
een zijspoor (een CareSens-update die tegelijk was geïnstalleerd) — terecht
teruggefloten: de vraag zelf bevatte het antwoord al ("hij netjes 6 minuten
laat wachten" is per definitie fout voor een 5-minuten-sensor).

**Diagnose (bevestigd met uren data, 16:25–22:07, stabiele periode vóór de
CareSens-herstart).** De eigen diagnostiekregel `computeReconnectCooldownMs`
toont `periodsElapsed` steeds in sprongen van 2 (0, 2, 4, 6, 8, ...), nooit
1 — de app verbond dus feitelijk elke 10 minuten, niet elke 5.

Root cause, gevonden in `DexcomG7Driver.kt` (en identiek gekopieerd naar
`DexcomG6Driver.kt` en `CareSensAirDriver.kt`): `computeReconnectCooldownMs()`
rekende met `Math.round((laatste meting − anker) / 5 min)` om te bepalen in
welk vast 5-minuten-vak (sinds een sessie-anker) de laatste meting viel. Elke
connectiecyclus kwam consistent ~2,57 minuten LATER binnen dan zijn eigen
beoogde tijdstip (BLE-scan-/verbindingsoverhead die meer tijd kost dan de
marge ervoor). Zodra die vertraging over de helft van een vak (2,5 min) heen
ging, rondde `Math.round` naar het VOLGENDE vak i.p.v. het vak waar de
meting echt bij hoorde — het volgende doel werd dan 10 min verder i.p.v. 5,
wat op zijn beurt weer ~2,57 min te laat binnenkwam en dus OPNIEUW naar het
volgende vak afrondde. Eenmaal over die afrondingsgrens heen herhaalt de
fout zichzelf daardoor oneindig (tot de eerstvolgende ankerreset bij een
driver-herstart) — geen sensor-eigenaardigheid, een reproduceerbare
rekenfout, en aanwezig in alle drie de sensordrivers.

**Wijziging.**

- `sensor/dexcomg7/DexcomG7Driver.kt`, `sensor/dexcomg6/DexcomG6Driver.kt`,
  `sensor/caresensair/CareSensAirDriver.kt`: `computeReconnectCooldownMs()`'s
  `Math.round(...)` vervangen door `Math.floor(...)` — kent een late meting
  toe aan het LAATST al verstreken vak i.p.v. het dichtstbijzijnde, zodat
  een eenmalige (of structurele) vertraging van een paar minuten niet meer
  permanent een heel extra vak doorschuift. Bij CareSens Air is bewust
  ALLEEN deze aanroep aangepast — de losse `periodsFromOther`-berekening
  verderop in datzelfde bestand (voor de botsingsafstand tot de ANDERE
  slot's raster) hoort wél de dichtstbijzijnde afstand te zoeken, in beide
  richtingen, en is dus terecht op `Math.round` blijven staan.
- `ui/CombiScreen.kt`, op verzoek — "neem dan gelijk de aaps actieve sensor
  als open slot mee": het starttabblad (`tabIndex`) begint nu op een
  sentinel (-1) i.p.v. altijd 0 (Slot A); een nieuwe `LaunchedEffect(Unit)`
  zet 'm éénmalig, alleen bij een ECHTE koude start, op de AAPS-zendende
  slot (`AppSettings.getAapsActiveSlotOnce()`) — Slot B als díe zendt,
  anders Slot A (dus ook wanneer geen van beide zendt, zoals nu, blijft het
  vertrouwde Slot A-gedrag gewoon behouden). Bij rotatie of gewoon
  achtergrond/voorgrond binnen hetzelfde process staat `tabIndex` al vast
  (door deze effect zelf of een latere handmatige tik) en springt het
  scherm niet meer terug.

**Verificatie.** Alle vier gewijzigde bestanden gecontroleerd met dezelfde
Python-tokenizer als Ronde 153/154 — accolades/haakjes in balans. Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review. De
cadans-fix is met de bestaande, meegestuurde data doorgerekend (dezelfde
~2,57 min consistente vertraging per cyclus geeft met `floor` een echte
5-minuten-cadans i.p.v. 10) maar nog niet zelf tegen een levende sensor
getest — dat vergt een nieuwe, vergelijkbaar lange log.

Gewijzigd: `sensor/dexcomg7/DexcomG7Driver.kt`,
`sensor/dexcomg6/DexcomG6Driver.kt`,
`sensor/caresensair/CareSensAirDriver.kt`, `ui/CombiScreen.kt`,
`app/build.gradle.kts`.

versionCode 169, versionName "0.9.70-reconnect-cadence-rounding-fix".

## Ronde 156 (29/08/2026) — diagnostisch: proces-instantie-tag toegevoegd aan elke logregel (nog GEEN fix)

**Aanleiding.** Live-melding na het installeren van v169: "hij start wel op
en koppelt maar blijft vervolgens bijna een kwartier op connecting staan".
De meegestuurde log (`fclglucolink_2026-08-28 23.59.txt`) toont tussen
23:45:58 en 23:48:09 een korte, chaotische reeks mislukte herverbindingen
(waaronder een niet eerder geziene `status=133`), gevolgd door VOLLEDIGE
stilte — geen enkele `DexcomG7:`-regel meer, terwijl de disconnect-handler
in `DexcomG7Driver.kt` na ELKE disconnect onvoorwaardelijk een nieuwe
scanpoging inplant (`onConnectionStateChange`'s `STATE_DISCONNECTED`-tak
roept altijd `scheduleScanAttempt()` aan, tenzij de gebruiker zelf stopte).
Zo'n totale stilte, i.p.v. herhaalde foutregels, wijst eerder op een
plotseling gecancelde coroutine-scope dan op een normaal falende
retry-lus.

**Diagnose (hypothese, NIET bevestigd).** Het patroon lijkt sterk op een
eerder al bevestigd scenario, gedocumenteerd in `BleConnectionService.kt`'s
Ronde 59-kdoc: "TWEE gelijktijdige BluetoothGatt-verbindingen naar hetzelfde
toestel... transmitter raakte in de war, beide verbraken meteen weer". De
bestaande bescherming daartegen (`startCommandMutex` + de
`stillWorking`-check in `ensureSlotConnected()`) werkt overtuigend BINNEN
één service-/procesexemplaar, maar beschermt niet tegen het geval dat de
update-herstart kortstondig TWEE APARTE PROCESSEN met elk hun eigen
`BleConnectionService`-instantie oplevert (bv. een korte
PACKAGE_REPLACED-herstart náást het handmatig heropenen van de app) — elk
proces heeft dan zijn eigen mutex, driver en sessiesleutel, en beide zouden
onafhankelijk van elkaar naar dezelfde fysieke sensor proberen te
verbinden. Dit is vooralsnog NIET hard te bewijzen: de huidige diagnose-log
bevat nergens een proces-ID, dus twee gelijktijdige processen zijn met de
huidige logregels niet van gewoon-na-elkaar te onderscheiden.

**Wijziging (puur diagnostisch, geen gedragswijziging).**

- `logging/DiagnosticFileLogger.kt`: een `instanceTag` (proces-ID + korte
  random suffix) wordt precies één keer aangemaakt bij de eerste aanraking
  van dit object — in de praktijk dus hoogstens één keer per Android-proces
  (elk nieuw proces = een verse class-initialisatie van deze singleton).
  Deze tag wordt nu voorin elke geschreven logregel gezet (`writeLine()` en
  `logFatal()`), en dus automatisch meegenomen door ELKE bestaande
  `DiagnosticFileLogger.log(...)`/`logError(...)`-aanroep door de hele app
  heen — geen van de honderden bestaande aanroepen zelf hoefde aangepast.
  Ziet een volgende log twee verschillende tags door elkaar heen lopen
  binnen hetzelfde tijdsbestek, dan is het duale-proces-vermoeden bevestigd;
  blijft het overal dezelfde ene tag, dan ligt de oorzaak ergens anders en
  moet dat spoor losgelaten worden.

**Verificatie.** Gewijzigde bestand gecontroleerd met dezelfde
Python-tokenizer als voorgaande rondes — accolades/haakjes in balans (de
eerste run gaf een vals alarm door een los apostrof-teken in Nederlandse
kdoc-proza — "geen `'`-sluiting" — de tokenizer stript nu eerst
blok-/regelcommentaar vóór ze naar losse aanhalingstekens kijkt). Geen
Gradle/Android-SDK beschikbaar om te compileren — handmatige review. GEEN
enkele bestaande log-aanroep of gedragspad aangeraakt — dit is uitsluitend
extra informatie in de uitvoerregel zelf. Nog geen conclusie of fix voor de
"stuck on Connecting"-melding zelf — dat vergt een nieuwe log, gemaakt ná
deze wijziging, tijdens (of vlak na) eenzelfde soort update-herstart.

Gewijzigd: `logging/DiagnosticFileLogger.kt`, `app/build.gradle.kts`.

versionCode 170, versionName "0.9.71-diagnostic-instance-tag".

versionCode 117, versionName `0.9.20-alarm-alert-mode-fix`.
