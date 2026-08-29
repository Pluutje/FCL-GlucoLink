package com.fclglucolink.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.MotionEvent
import com.fclglucolink.app.data.SensorSwitchEvent
import com.fclglucolink.app.prediction.GlucosePredictionPoint
import com.fclglucolink.app.prediction.PREDICTION_HORIZON_MINUTES
import com.fclglucolink.app.prediction.computeGlucosePrediction
import com.fclglucolink.app.sensor.GlucoseReading
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IFillFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.utils.ColorTemplate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 13/08/2026 (editor, RONDE 104 — Fase 1 van 2, op verzoek: "een mg/dl vs
 * mmol/l knop [...] in de ui zou da weer gegeven Bg waarden dan moeten
 * kunnen veranderen") — vóór deze ronde plotte dit hele bestand rechtstreeks
 * in mmol/L (elke `Entry()` deed `.mgdlToMmol()`, de as-grenzen/bandgrenzen
 * waren letterlijke mmol-getallen: 2f/12f as, 4f/10f/12,5f/15f banden). Dat
 * zou een eenheid-toggle dwingen om ALLE schaal-/pan-/zoom-/granulariteits-
 * wiskunde in dit bestand (15+ rondes hard-bevochten geschiedenis, zie de
 * kdocs verderop) voor twee heel verschillende getallenschalen opnieuw te
 * valideren — onnodig risicovol.
 *
 * In plaats daarvan: de grafiek plot voortaan ALTIJD intern in mg/dL (geen
 * `.mgdlToMmol()` meer op de geplotte punten) — dat is nu de ENE canonieke
 * schaal voor alle as-/pan-/zoom-/bandlogica, ongeacht de weergave-eenheid.
 * Alleen de Y-as-TEKSTLABELS worden eenheid-bewust (een `ValueFormatter`,
 * exact hetzelfde patroon als de bestaande X-as-tijdlabel-formatter
 * hieronder) — de onderliggende schaal/coördinaten blijven dus altijd
 * mg/dL, wat betekent dat geen van de bestaande pan/zoom/granulariteit-fixes
 * geraakt wordt. De band-/kleurgrenzen (LOW_MGDL/HIGH_MGDL/...) zijn de
 * standaard klinische mg/dL-grenzen (70/180/225/270) — bewust NIET de
 * eerder in de code staande mmol-waarden×18,0182 (72,07/180,18/...), want
 * 70/180/225/270 zijn zelf al de erkende ronde mg/dL-drempels en round-tript
 * naar exact dezelfde 4,0/10,0/12,5/15,0 mmol/L-weergave als vóór deze
 * ronde (bv. 70 mg/dL -> 3,885 mmol/L -> "%.1f" -> "3,9", zichtbaar gelijk
 * aan de oude "4,0"-grens binnen de bestaande afrondingsmarge).
 */
private const val LOW_MGDL = 70f
private const val HIGH_MGDL = 180f
private const val VERY_HIGH_YELLOW_MGDL = 225f
private const val VERY_HIGH_ORANGE_MGDL = 270f

/** Vaste ondergrens van de Y-as (analoog aan de oude 2f mmol). */
private const val AXIS_FLOOR_MGDL = 40f

/** Vloer voor de DYNAMISCHE bovengrens (analoog aan de oude 12f mmol-vloer
 *  in [recomputeYAxisMax]). */
private const val AXIS_DYNAMIC_MAX_FLOOR_MGDL = 220f

/**
 * 29/08/2026 (editor, RONDE 160) — kleur voor de twee voorspellings-
 * grenslijnen (zie prediction/GlucosePrediction.kt): bewust een indigo/
 * blauwpaars, niet gebruikt door de bestaande bereikskleuring (groen/geel/
 * oranje/rood), de grijze raw-sensor-indicator, of de paarse/grijze
 * sensor-wisselmarkers — moet op het eerste gezicht duidelijk een ANDER
 * soort lijn zijn dan de echte meetdata. Halfdoorzichtig (alpha 190/255)
 * zodat de band duidelijk ondergeschikt oogt aan de echte BG-lijn.
 */
private val PREDICTION_BAND_COLOR_ARGB = android.graphics.Color.argb(190, 92, 107, 192)

/** Kleur voor de verticale "nu"-lijn die de echte data van de voorspelling
 *  scheidt — neutraal grijs, dashed, subtiel (net als de gelijksoortige
 *  sensor-wisselmarkers hierboven maar met een eigen, niet eerder gebruikte
 *  tint zodat 'm niet met een sensorwisseling verward wordt). */
private val PREDICTION_NOW_LINE_COLOR_ARGB = android.graphics.Color.argb(160, 120, 120, 120)

/** Eenheid-bewuste Y-as-tekstlabel — zie klasse-kdoc hierboven: de
 *  onderliggende as-WAARDEN blijven altijd mg/dL, dit zet alleen de TEKST
 *  om, exact zoals de bestaande X-as-tijdformatter dat al voor tijdstippen
 *  doet. */
private fun yAxisValueFormatter(unit: GlucoseUnit): ValueFormatter = object : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = when (unit) {
        GlucoseUnit.MGDL -> "%.0f".format(value)
        GlucoseUnit.MMOL -> value.toDouble().formatMmol()
    }
}

/**
 * 30/07/2026 (editor) — replaced the original hand-rolled Canvas chart with
 * MPAndroidChart (see build.gradle.kts): the previous version plotted
 * readings at evenly-spaced positions regardless of their actual timestamps
 * (so the "last 6 hours" label never matched what the line's width actually
 * represented), and had no zoom/pan at all. MPAndroidChart's LineChart gives
 * a real time-based X-axis plus built-in pinch-zoom and drag/pan for free —
 * chosen over hand-rolling that gesture math ourselves (least code-sensitive
 * option, per the earlier discussion).
 *
 * X-axis values are minutes-since-the-oldest-reading-in-the-current-dataset
 * (not raw epoch milliseconds) — a plain Float can't represent an epoch
 * timestamp (~1.7e12 ms) with enough precision to tell two 5-minute-apart
 * readings apart, but small minute offsets are precise. The axis label
 * formatter converts that offset back to a real clock time for display.
 *
 * 30/07/2026 (editor, na feedback #2) — twee aanpassingen t.o.v. de vorige
 * versie:
 * - Y-as nu 2-12 mmol/L (was 3-20) met een echte GEVULDE groene band tussen
 *   4 en 10 (was: twee losse gestreepte LimitLines op 3.9/10.0).
 *   MPAndroidChart heeft geen directe "vul tussen twee Y-waarden"-optie,
 *   maar dit is wel te bereiken met een tweede, onzichtbare LineDataSet (een
 *   vlakke lijn op y=10 die over de volle breedte loopt) met setDrawFilled +
 *   een IFillFormatter die vast y=4 als ondergrens teruggeeft — dat is precies
 *   het effect van "gevulde band tussen 4 en 10". De echte BG-lijn wordt er
 *   bovenop getekend (tweede dataset in de LineData-lijst). Alleen de Y-as is
 *   nu bewust vast (setScaleYEnabled(false)) zodat die band-berekening niet
 *   ook nog met Y-zoom rekening hoeft te houden — zoomen/schuiven blijft wel
 *   gewoon werken, maar dan alleen horizontaal (tijd-as), wat ook is wat er
 *   gevraagd is.
 * - X-as toont standaard een vast venster van 4 uur (240 minuten) met de
 *   laatste meting tegen de rechterkant — niet enkel "zoom niet verder uit
 *   dan 120 min" zoals eerder: bij weinig data (bv. 2 metingen) trok
 *   MPAndroidChart de as toen alsnog naar de volle breedte van de data zelf,
 *   omdat xAxis.axisMinimum/axisMaximum nooit expliciet gezet waren (alleen
 *   een bovengrens voor de zichtbare breedte, die bij een smalle
 *   werkelijke databreedte niets afdwingt). Nu ZIJN axisMinimum/axisMaximum
 *   zelf het venster ("laatste punt min 4 uur" t/m "laatste punt"), los van
 *   hoe smal de echte data is — een ongezoomde weergave vult daardoor altijd
 *   dat venster. `fitScreen()` reset de zoom/schuifpositie naar dat venster
 *   bij elke dataverversing, dus een handmatige zoom/pan-positie springt bij
 *   een nieuwe meting (elke ~5 min) terug naar dit standaardvenster. Dat is
 *   een bewuste afweging — "onthoud de laatste handmatige positie tussen
 *   live updates door" is een aparte, grotere uitbreiding (gesture-listener
 *   + state) die nog niet gevraagd is. Metingen ouder dan 4 uur geleden
 *   blijven wel in de dataset staan, maar zijn buiten dit venster niet
 *   bereikbaar via schuiven (het venster schuift niet verder terug dan 4u).
 *
 * 30/07/2026 (editor, na feedback #3) — de tijdlabels onder de as stonden op
 * "rare" minuten (17:23, 17:43, …) i.p.v. ronde tijden: het ankerpunt voor
 * de X-as-waarden (baseTimestampMs) was gewoon de vroegste meting in de set,
 * een willekeurig moment. Nu wordt dat ankerpunt eerst afgerond naar het
 * begin van het uur — daardoor komt elke minuten-offset vanaf dat ankerpunt
 * exact overeen met de minuut-van-het-uur op de klok, en vallen rasterlijnen
 * op ronde tijden. Daarbovenop dwingt xAxis.granularity een vaste stapgrootte
 * af (10 min ingezoomd, 30 min op het standaardvenster, 60 min verder
 * uitgezoomd) i.p.v. MPAndroidChart's eigen "mooi getal"-algoritme te
 * vertrouwen (dat had evengoed 20 of 50 kunnen kiezen) — een
 * OnChartGestureListener herberekent die stapgrootte na elke zoom/schuifbeweging.
 *
 * 30/07/2026 (editor, na feedback #4: "wil tot zeker 24u, liever 48u terug
 * kunnen swipen") — de "4 uur"-beschrijving hierboven bij feedback #2 was
 * daarna een HARDE grens geworden: axisMinimum/axisMaximum ZIJN het venster,
 * dus verder terugswipen dan 4 uur kon toen letterlijk niet, wat dat ook was
 * in de dataset stond. Nu (samen met GlucoseReadingStore.kt en
 * StatusScreen.kt, die beide van 6-24u naar 48u zijn gegaan) is dat
 * losgekoppeld: axisMinimum bestrijkt de VOLLE geladen data terug (tot 48u,
 * met een vloer van 4u voor het geval er nog weinig data is), en de "4
 * uur"-limiet is verplaatst naar setVisibleXRangeMaximum(240f) — dat is nu
 * een ZOOM-limiet (nooit meer dan 4u tegelijk in beeld), geen pan-limiet.
 * chart.moveViewToX(latestX) positioneert de standaardweergave bij de
 * laatste meting; fitScreen() (dat het hele, nu 48u brede axisbereik in
 * beeld zou zetten) is daarom niet meer gebruikt.
 *
 * 06/08/2026 (editor, RONDE 44, op verzoek: "Op de Bg grafiek wil ik dat de
 * sensorwaarde ook in de grafiek als een grijze open cirkel zichtbaar
 * wordt") — zelfde idee als het kleine indicatortje op StatusScreen.kt's
 * BgRingDisplay (ronde 43): de RUWE sensorwaarde (GlucoseReading.
 * rawSensorMgdl) blijft ook hier zichtbaar naast de gekalibreerde lijn,
 * bewust ondergeschikt (lichtgrijs, open/lege cirkel — geen lijn) en alleen
 * getekend voor metingen waar kalibratie 'm daadwerkelijk veranderd heeft
 * (anders zou elk punt gewoon exact op de bestaande lijn liggen, puur
 * ruis toevoegend). "Open cirkel" is hier een gevulde cirkel met een
 * transparant gat in het midden (setDrawCircleHole + circleHoleColor =
 * TRANSPARENT) — MPAndroidChart heeft geen kant-en-klare "alleen een rand
 * tekenen"-modus voor punten.
 *
 * 09/08/2026 (editor, RONDE 64, op verzoek: "handig om er een sensor wissel
 * icoontje op de grafiek bij het wissel moment bij te plaatsen wat dan bv
 * binnen het zelfde sensor type minder opvallend van kleur is en bij een
 * sensortype wissel een wat opvallende kleur heeft") — [switchEvents]
 * (optioneel, standaard leeg zodat bestaande aanroepers niet breken) wordt
 * getekend als verticale streeplijnen via MPAndroidChart's X-as
 * `LimitLine`'s — dat is hier het "icoontje": een duidelijk herkenbare,
 * verticale markering op precies het tijdstip van de wissel, met twee vaste
 * kleuren voor de twee gevallen (zie SensorSwitchEvent.crossType's kdoc in
 * SensorSwitchEventStore.kt). Geen losse `Icon`/`Canvas`-tekening nodig —
 * `LimitLine` bestaat al kant-en-klaar in de gebruikte chart-library en
 * blijft correct gepositioneerd bij zoomen/pannen, precies zoals de rest van
 * deze grafiek dat al doet.
 */
@Composable
fun GlucoseChart(
    readings: List<GlucoseReading>,
    switchEvents: List<SensorSwitchEvent> = emptyList(),
    // 13/08/2026 (editor, RONDE 104) — zie klasse-kdoc bovenaan dit bestand:
    // beïnvloedt alleen de Y-as-TEKSTLABELS, niet de onderliggende (altijd
    // mg/dL) schaal/pan/zoom-wiskunde. Default MMOL zodat een eventuele
    // andere aanroeper die dit (nog) niet meegeeft niets ziet veranderen.
    unit: GlucoseUnit = GlucoseUnit.MMOL,
    // 29/08/2026 (editor, RONDE 160) — zie prediction/GlucosePrediction.kt
    // en de nieuwe "Bg-voorspelling"-instelling in SettingsScreen.kt.
    // Default false zodat een eventuele andere aanroeper die dit (nog) niet
    // meegeeft niets ziet veranderen (zelfde conventie als `unit` hierboven).
    predictionEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 31/07/2026 (editor, ronde 15, na controlevraag: "wordt de Bg-lijn
    // onder de 4 ook rood en boven de 10 ook geel?") — bleek nee: de lijn
    // (en de bolletjes erop) hadden altijd dezelfde vaste primary-kleur,
    // ongeacht de waarde. Nu drie kleuren, per meetpunt gekozen o.b.v. de
    // mmol-waarde — dezelfde grenzen (4/10 mmol) als de gevulde band
    // hieronder en als bgRangeColor() in StatusScreen.kt.
    //
    // 09/08/2026 (editor, RONDE 75, op verzoek — "In de Bg grafiek wil ik de
    // kleuren boven de 10 veranderen van 10 tot 12,5 moet hij geel worden
    // van 12,5 tot 15 oranje en boven de 15 rood onder de 4 kan zo blijven")
    // — het vroegere ÉÉN "boven bereik"-amber (10-oneindig) wordt nu drie
    // opeenvolgende bandjes: geel (10-12,5), oranje (12,5-15), rood (>15).
    // Rood hergebruikt bewust dezelfde kleur als de bestaande onder-bereik-
    // kleur (`belowRangeColorArgb`) — zowel een te lage als een te hoge
    // waarde is potentieel gevaarlijk, dezelfde alarmkleur voor beide is
    // consistent met hoe xDrip/AAPS dat ook doen.
    val inRangeColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val aboveRangeYellowColorArgb = android.graphics.Color.parseColor("#FDD835") // 10-12.5 mmol/L
    val aboveRangeOrangeColorArgb = android.graphics.Color.parseColor("#FFA000") // 12.5-15 mmol/L (was hét enige "boven bereik"-amber)
    val belowRangeColorArgb = MaterialTheme.colorScheme.error.toArgb()
    // >15 mmol/L — zelfde rode kleur als belowRangeColorArgb, zie kdoc
    // hierboven. Aparte naam puur voor leesbaarheid in de when-tak hieronder.
    val aboveRangeRedColorArgb = belowRangeColorArgb
    val gridColorArgb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f).toArgb()
    val textColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    // Let op: fillColor moet hier ONDOORZICHTIG zijn — de transparantie van
    // de band wordt hieronder apart via fillAlpha geregeld (MPAndroidChart
    // combineert een alpha-kanaal in de kleur zelf niet betrouwbaar met de
    // legacy fillAlpha-rendering-pad).
    val bandColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    // 06/08/2026 (editor, RONDE 44/45) — lichtgrijs, net als de open-cirkel-
    // indicator op StatusScreen.kt's ring, maar op verzoek ("iets minder
    // opvallende kleur") nu met een lagere alpha (~55%) i.p.v. volledig
    // dekkend — argb() i.p.v. parseColor() omdat parseColor() geen
    // alpha-component in een 6-cijferige hex-string accepteert.
    val rawIndicatorColorArgb = android.graphics.Color.argb(140, 170, 170, 170)
    // 09/08/2026 (editor, RONDE 64) — zie kdoc hierboven bij GlucoseChart:
    // subtiel (halfdoorzichtig grijs, lijkt op de rand van de kaarten
    // eromheen) vs. opvallend (een heldere, elders op deze grafiek niet
    // gebruikte paarse tint — de bestaande groen/amber/rood-kleuren zijn al
    // bezet door de BG-bereiksindeling, dus bewust een vierde, duidelijk
    // onderscheidbare kleur voor "dit is een ANDER sensor-type").
    val sameTypeSwitchColorArgb = android.graphics.Color.argb(130, 200, 200, 200)
    val crossTypeSwitchColorArgb = android.graphics.Color.parseColor("#BA68C8")

    val earliestReadingMs = readings.minOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
    // Afronden naar het begin van het uur (in epoch-ms, dus tijdzone-
    // onafhankelijk zolang de lokale UTC-offset een heel aantal uren is —
    // geldt voor NL) zodat minuten-offsets vanaf dit ankerpunt op ronde
    // klokminuten uitkomen. Zie kdoc hierboven.
    val baseTimestampMs = earliestReadingMs - (earliestReadingMs % 3_600_000L)
    val timeFormat = remember(Locale.getDefault()) { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setTouchEnabled(true)
                // Alleen de tijd-as (X) is zoombaar/schuifbaar — de Y-as
                // blijft vast op 2-12 mmol/L, zie kdoc hierboven.
                setPinchZoom(false)
                isDragEnabled = true
                setScaleEnabled(true)
                setScaleYEnabled(false)
                setScaleXEnabled(true)
                // 30/07/2026 (editor, na feedback: "bij aanraken verschijnt
                // een kruisdraad, dit maakt swipen/zoomen lastig") —
                // bandDataSet kreeg hieronder al setHighlightEnabled(false),
                // maar de ECHTE zichtbare lijn (dataSet) niet: die had dus
                // nog MPAndroidChart's standaard AAN staan, wat bij elke tap
                // die kruisdraad (verticale/horizontale highlight-lijnen)
                // tekent. Op chart-niveau ook expliciet uitzetten, niet
                // alleen per dataset — dat voorkomt ook dat een simpele tap
                // (i.p.v. een drag) een highlight-selectie triggert die met
                // de pan/zoom-gesture kan interfereren.
                setHighlightPerTapEnabled(false)
                setHighlightPerDragEnabled(false)
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(true)
                axisLeft.setDrawLimitLinesBehindData(true)
                // 02/08/2026 (editor, op verzoek: "wil graag dat de y-as van
                // de grafiek meeschaalt met de hoogste Bg ... minimum 2 tot
                // 12 maar als de Bg boven de 11 komt dan tot 13 en boven de
                // 12 tot 14") — deze twee waarden waren hier voorheen vast
                // (2/12, nooit aangepast); dat is nu alleen nog de
                // STARTWAARDE bij het aanmaken van de chart — het update-
                // blok hieronder herberekent axisMaximum bij elke
                // dataverversing (zie daar). axisMinimum blijft altijd 2 —
                // dat is niet gevraagd te veranderen.
                axisLeft.axisMinimum = AXIS_FLOOR_MGDL
                axisLeft.axisMaximum = AXIS_DYNAMIC_MAX_FLOOR_MGDL
                // setVisibleXRangeMinimum hoort pas ná het zetten van data
                // (in het update-blok) — vóór data is mAxisRange nog 0, dan
                // zou dit de zoom-limiet permanent op 0 vastzetten.

                // Herbereken de tijdlabel-stapgrootte (10/30/60 min) EN de
                // Y-as-bovengrens na elke zoom/schuifbeweging, zodat beide
                // het huidige zichtbare venster blijven volgen — zie kdoc
                // hierboven resp. bij recomputeYAxisMax(). Expliciete
                // setter i.p.v. property-vorm, zie eerdere ervaring met
                // highlightEnabled hierboven in dit bestand.
                setOnChartGestureListener(object : OnChartGestureListener {
                    override fun onChartGestureStart(
                        me: MotionEvent?,
                        lastPerformedGesture: ChartTouchListener.ChartGesture?
                    ) {}
                    override fun onChartGestureEnd(
                        me: MotionEvent?,
                        lastPerformedGesture: ChartTouchListener.ChartGesture?
                    ) {
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, SINGLE_CHART_Y_AXIS_LABELS)
                    }
                    override fun onChartLongPressed(me: MotionEvent?) {}
                    override fun onChartDoubleTapped(me: MotionEvent?) {}
                    override fun onChartSingleTapped(me: MotionEvent?) {}
                    override fun onChartFling(
                        me1: MotionEvent?,
                        me2: MotionEvent?,
                        velocityX: Float,
                        velocityY: Float
                    ) {}
                    override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {
                        // 31/07/2026 (editor) — geen permanente
                        // setVisibleXRangeMaximum-cap meer (zie update-blok
                        // hieronder: de 4u-standaardweergave wordt nu met een
                        // eenmalige, absolute zoom()-aanroep afgedwongen i.p.v.
                        // een blijvende min-schaal-grens) — dus hoeft hier ook
                        // niets meer opgerekt te worden vóór handmatig
                        // uitzoomen. axisMinimum/axisMaximum blijven de enige
                        // harde grens (nooit verder dan de geladen 48u).
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, SINGLE_CHART_Y_AXIS_LABELS)
                    }
                    override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, SINGLE_CHART_Y_AXIS_LABELS)
                    }
                })
            }
        },
        update = { chart ->
            chart.xAxis.textColor = textColorArgb
            chart.xAxis.gridColor = gridColorArgb
            chart.axisLeft.textColor = textColorArgb
            chart.axisLeft.gridColor = gridColorArgb
            chart.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ts = baseTimestampMs + (value * 60_000L).toLong()
                    return timeFormat.format(Date(ts))
                }
            }
            // 13/08/2026 (editor, RONDE 104) — zie klasse-kdoc bovenaan dit
            // bestand: alleen de tekst verandert, de as blijft mg/dL-schaal.
            chart.axisLeft.valueFormatter = yAxisValueFormatter(unit)

            if (readings.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }

            // 13/08/2026 (editor, RONDE 104) — geen `.mgdlToMmol()` meer, zie
            // klasse-kdoc bovenaan dit bestand: mg/dL is nu de enige interne
            // plotschaal.
            val entries = readings.map { r ->
                val minutesSinceBase = (r.timestampMs - baseTimestampMs) / 60_000f
                Entry(minutesSinceBase, r.glucoseMgdl.toFloat())
            }
            val latestX = entries.maxOf { it.x }
            val earliestX = entries.minOf { it.x }

            // 29/08/2026 (editor, RONDE 160) — zie prediction/
            // GlucosePrediction.kt's klasse-kdoc voor de volledige
            // berekeningsuitleg. `null` als de instelling uit staat OF er
            // simpelweg te weinig recente data is voor een zinvolle
            // regressie (computeGlucosePrediction() bewaakt dat zelf).
            val predictionPoints = if (predictionEnabled) computeGlucosePrediction(readings) else null
            // Rechter-asgrens: normaal de laatste meting, maar als er
            // daadwerkelijk een voorspelling getoond wordt, 1 uur verder
            // (PREDICTION_HORIZON_MINUTES) zodat die band niet meteen buiten
            // het geladen as-bereik valt (zie ook de zoom/pan-aanpassing
            // verderop, die dit nieuwe rechter-uiteinde ook standaard in
            // beeld brengt).
            val rightEdgeX = if (predictionPoints != null) latestX + PREDICTION_HORIZON_MINUTES else latestX

            // 30/07/2026 (editor, na feedback: "wil tot zeker 24u, liever 48u
            // terug kunnen swipen") — vorige opzet zette axisMinimum vast op
            // "laatste meting min 4 uur", wat een HARDE grens was: verder
            // terug swipen dan dat kon gewoon niet, ook al stond er (sinds
            // GlucoseReadingStore nu 48u bewaart en StatusScreen.kt ook 48u
            // opvraagt) veel meer data in de dataset. axisMinimum bestrijkt
            // nu de volle geladen data terug (tot 48u), met een vloer van 4
            // uur voor het geval er nog weinig data is (bv. vlak na een
            // herstart) — dat voorkomt de eerdere bug dat de as zich bij
            // schaarse data (bv. 2 metingen) volledig samentrekt op die
            // paar punten. axisMaximum blijft de laatste meting (of, sinds
            // RONDE 160, het einde van de voorspellingsband — zie
            // rightEdgeX hierboven).
            chart.xAxis.axisMinimum = minOf(earliestX, latestX - 240f)
            chart.xAxis.axisMaximum = rightEdgeX

            // Onzichtbare, ruim-buiten-het-venster-lopende lijn op y=10 met
            // een vulling naar y=4 toe — dat geeft het effect van een
            // gevulde band tussen 4 en 10 mmol/L (zie kdoc), ongeacht hoe
            // ver de gebruiker schuift/inzoomt binnen het venster.
            val bandDataSet = LineDataSet(
                listOf(Entry(latestX - 100_000f, HIGH_MGDL), Entry(latestX + 100_000f, HIGH_MGDL)),
                "target-range"
            ).apply {
                // Overal expliciete setters i.p.v. Kotlin-property-vorm: bij
                // highlightEnabled bleek de synthetische property niet op te
                // lossen ("Unresolved reference"), vermoedelijk omdat getter
                // (interface) en setter (implementatie) niet in dezelfde
                // klasse staan — setter-methoden werken hier altijd wel.
                setDrawCircles(false)
                setDrawValues(false)
                // 11/08/2026 (editor, RONDE 96 — CRITICAL crash-fix, zie
                // kdoc bij DualGlucoseChart's dataSets hieronder voor de
                // volledige uitleg: MPAndroidChart's `isDrawIconsEnabled()`
                // staat standaard op TRUE, ongeacht `setDrawValues(false)` —
                // zonder deze regel blijft `LineChartRenderer.drawValues()`
                // toch `Transformer.generateTransformedValuesLine()`
                // aanroepen, wat kan crashen (NegativeArraySizeException)
                // bij het pannen naar een gebied waar dit dataset weinig/geen
                // punten heeft.
                setDrawIcons(false)
                setColor(android.graphics.Color.TRANSPARENT)
                setLineWidth(0f)
                setDrawFilled(true)
                setFillColor(bandColorArgb)
                setFillAlpha(60) // 0-255, geeft de zachte groene band-tint
                setFillFormatter(IFillFormatter { _, _ -> LOW_MGDL })
                setHighlightEnabled(false)
            }

            // 31/07/2026 (editor, ronde 15) — per-punt kleur i.p.v. één vaste
            // lijnkleur, zie kdoc hierboven bij de *ColorArgb-variabelen.
            // MPAndroidChart kleurt een LIJNSEGMENT met de kleur op de index
            // van het startpunt van dat segment (setColors(lijst)); de
            // bolletjes (setCircleColors) krijgen gewoon de kleur van hun
            // eigen punt.
            // 13/08/2026 (editor, RONDE 104) — mg/dL-drempels i.p.v. de oude
            // mmol-getallen, zie klasse-kdoc bovenaan dit bestand.
            val segmentColors = entries.map { entry ->
                when {
                    entry.y < LOW_MGDL -> belowRangeColorArgb
                    entry.y <= HIGH_MGDL -> inRangeColorArgb
                    entry.y <= VERY_HIGH_YELLOW_MGDL -> aboveRangeYellowColorArgb
                    entry.y <= VERY_HIGH_ORANGE_MGDL -> aboveRangeOrangeColorArgb
                    else -> aboveRangeRedColorArgb
                }
            }
            val dataSet = LineDataSet(entries, "BG").apply {
                setColors(segmentColors)
                setDrawCircles(true)
                circleRadius = 3f
                setCircleColors(segmentColors)
                setDrawCircleHole(false)
                setDrawValues(false)
                setDrawIcons(false) // 11/08/2026 (editor, RONDE 96) — zie kdoc bij bandDataSet hierboven.
                lineWidth = 2f
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(false)
                // Zie kdoc in de factory hierboven — dit was de eigenlijke
                // oorzaak van de kruisdraad-bij-aanraken: bandDataSet had
                // setHighlightEnabled(false) al, maar deze ECHTE lijn niet.
                setHighlightEnabled(false)
            }

            // 06/08/2026 (editor, RONDE 44) — zie kdoc hierboven bij
            // GlucoseChart: alleen punten waar kalibratie de weergegeven
            // waarde daadwerkelijk veranderd heeft (anders identiek aan de
            // gekalibreerde lijn, dus geen zichtbare toevoeging).
            // 13/08/2026 (editor, RONDE 104) — geen `.mgdlToMmol()` meer, zie
            // klasse-kdoc bovenaan dit bestand.
            val rawEntries = readings
                .filter { kotlin.math.abs(it.rawSensorMgdl - it.glucoseMgdl) > 0.01 }
                .map { r ->
                    val minutesSinceBase = (r.timestampMs - baseTimestampMs) / 60_000f
                    Entry(minutesSinceBase, r.rawSensorMgdl.toFloat())
                }
            // 06/08/2026 (editor, RONDE 45, na live-test — "geen massieve
            // grijze stip maar alleen de open cirkel") — de vorige
            // circleHoleColor = TRANSPARENT (argb 0x00000000) loste dit niet
            // op: MPAndroidChart tekent het "gat" dan gewoon met een gewone
            // paint in die (onzichtbare) kleur BOVENOP de al getekende
            // volle cirkel — bij alpha=0 tekent die paint niets, dus de
            // volle buitenste cirkel bleef gewoon massief zichtbaar (precies
            // het gerapporteerde symptoom). ColorTemplate.COLOR_NONE is een
            // aparte sentinel-waarde die MPAndroidChart's renderer expliciet
            // herkent en dan met een PorterDuff.Mode.CLEAR-paint tekent — dat
            // ponst een ECHT transparant gat door de cirkel heen, i.p.v. een
            // onzichtbare kleur erover te leggen. Ook minder opvallend
            // gemaakt (lagere alpha, iets dunnere ring) op verzoek.
            val rawDataSet = LineDataSet(rawEntries, "raw-sensor").apply {
                lineWidth = 0f
                setColor(android.graphics.Color.TRANSPARENT)
                setDrawCircles(true)
                circleRadius = 3.2f
                setCircleColor(rawIndicatorColorArgb)
                setDrawCircleHole(true)
                circleHoleRadius = 1.9f
                circleHoleColor = ColorTemplate.COLOR_NONE
                setDrawValues(false)
                setDrawIcons(false) // 11/08/2026 (editor, RONDE 96) — zie kdoc bij bandDataSet hierboven.
                setHighlightEnabled(false)
            }

            // 29/08/2026 (editor, RONDE 160) — de twee divergerende
            // grenslijnen, zie prediction/GlucosePrediction.kt en de kdoc bij
            // `predictionPoints` hierboven. x-coördinaat = de as-positie van
            // de LAATSTE ECHTE meting (latestX) + het aantal minuten dat elk
            // berekend punt daar vandaan ligt — dat is precies "vanaf het
            // laatste punt doortekenen", en op minutesFromNow=0 vallen beide
            // lijnen samen met dat laatste echte punt ("vanuit de oorsprong
            // divergeren").
            val predictionDataSets: List<ILineDataSet> = if (predictionPoints != null) {
                val upperEntries = predictionPoints.map { Entry(latestX + it.minutesFromNow, it.upperMgdl) }
                val lowerEntries = predictionPoints.map { Entry(latestX + it.minutesFromNow, it.lowerMgdl) }
                val upperDataSet = LineDataSet(upperEntries, "prediction-upper").apply {
                    setColor(PREDICTION_BAND_COLOR_ARGB)
                    lineWidth = 1.6f
                    enableDashedLine(10f, 6f, 0f)
                    mode = LineDataSet.Mode.LINEAR
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawIcons(false) // zie kdoc bij bandDataSet hierboven.
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
                val lowerDataSet = LineDataSet(lowerEntries, "prediction-lower").apply {
                    setColor(PREDICTION_BAND_COLOR_ARGB)
                    lineWidth = 1.6f
                    enableDashedLine(10f, 6f, 0f)
                    mode = LineDataSet.Mode.LINEAR
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawIcons(false)
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
                listOf(upperDataSet, lowerDataSet)
            } else {
                emptyList()
            }

            chart.data = LineData(listOf<ILineDataSet>(bandDataSet, dataSet, rawDataSet) + predictionDataSets)

            // 09/08/2026 (editor, RONDE 64) — zie kdoc hierboven bij
            // GlucoseChart: één verticale streeplijn per wisselmoment,
            // opvallende kleur bij een type-wissel, subtiele kleur bij een
            // nieuwe sensor/transmitter binnen hetzelfde type.
            chart.xAxis.removeAllLimitLines()
            switchEvents.forEach { event ->
                val x = (event.timestampMs - baseTimestampMs) / 60_000f
                val limitLine = LimitLine(x).apply {
                    lineColor = if (event.crossType) crossTypeSwitchColorArgb else sameTypeSwitchColorArgb
                    lineWidth = if (event.crossType) 1.5f else 1f
                    enableDashedLine(12f, 8f, 0f)
                }
                chart.xAxis.addLimitLine(limitLine)
            }
            // 29/08/2026 (editor, RONDE 160) — verticale "nu"-lijn op de
            // grens tussen echte data en voorspelling, letterlijk gevraagd
            // ("op de grafiek een vertikale lijn plaatsen op de actuele
            // tijd"). Geankerd op latestX (de laatste ECHTE meting) i.p.v.
            // System.currentTimeMillis(): dat is exact waar de voorspelling
            // zelf ook vandaan vertrekt (zie predictionDataSets hierboven),
            // en wijkt in de praktijk hooguit een cyclus (~5 min) af van de
            // werkelijke kloktijd.
            if (predictionPoints != null) {
                chart.xAxis.addLimitLine(
                    LimitLine(latestX).apply {
                        lineColor = PREDICTION_NOW_LINE_COLOR_ARGB
                        lineWidth = 1.2f
                        enableDashedLine(6f, 6f, 0f)
                    }
                )
            }

            // Nooit verder inzoomen dan 15 minuten zichtbaar (moet ná het
            // zetten van data, anders is mAxisRange nog 0).
            chart.setVisibleXRangeMinimum(15f)

            // 31/07/2026 (editor, ronde 15, na feedback: "autozoom naar 4u
            // werkt nog niet") — de vorige combinatie
            // (setVisibleXRangeMaximum(240f) + moveViewToX(latestX)) bleek
            // ALSNOG onbetrouwbaar. Vermoedelijke oorzaak:
            // setVisibleXRangeMaximum zet alleen een ONDERGRENS op de
            // zoom-schaal (minScaleX) — die wordt pas bij de eerstvolgende
            // klaarteken-doorgang teruggeklemd als de HUIDIGE schaal er
            // beneden zit, en dat teruggeklem-moment t.o.v. moveViewToX()'s
            // eigen berekening bleek niet betrouwbaar op elkaar aan te
            // sluiten (afhankelijk van in welke zoom/pan-staat de gebruiker
            // de grafiek net daarvoor had achtergelaten).
            //
            // Deterministische aanpak i.p.v. daarop vertrouwen: eerst
            // fitScreen() — dat zet de zoom/pan-matrix terug naar een VAST
            // bekend startpunt (schaal=1, dus het VOLLEDIGE HUIDIGE
            // as-bereik in beeld; axisMinimum/axisMaximum staan hierboven
            // al op hun definitieve, 48u-brede waarden, geen tijdelijke
            // versmalling meer zoals in een eerdere, kapotte poging) — en
            // PAS DAARNA een absolute inzoom-factor toepassen vanaf dat
            // bekende startpunt (i.p.v. vanaf een onbekende, mogelijk al
            // scheefgezoomde staat) om precies op 240 minuten (4 uur) uit te
            // komen. chart.zoom() vermenigvuldigt de HUIDIGE schaal — omdat
            // die net door fitScreen() naar een bekende waarde (1) is
            // gereset, is het resultaat nu wél voorspelbaar, in
            // tegenstelling tot een eerdere poging die zoom() zonder die
            // reset toepaste (en daardoor op de vorige, onbekende zoomstand
            // stapelde).
            chart.fitScreen()
            val fullRangeMinutes = chart.xAxis.axisMaximum - chart.xAxis.axisMinimum
            if (fullRangeMinutes > 240f) {
                // 29/08/2026 (editor, RONDE 160) — anker nu op rightEdgeX
                // (was latestX): als er een voorspelling getoond wordt, moet
                // het STANDAARD 4-uur-venster eindigen bij het einde van die
                // band, niet bij de laatste echte meting — anders staat de
                // net toegevoegde band standaard buiten beeld en moet de
                // gebruiker eerst handmatig verder swipen om 'm ooit te zien.
                chart.zoom(fullRangeMinutes / 240f, 1f, rightEdgeX, 0f)
            }
            chart.moveViewToX(rightEdgeX)

            // Stapgrootte voor het standaardvenster (4 uur -> 30 min, zie
            // kdoc); daarna houdt de gesture-listener in de factory 'm bij.
            applyXAxisGranularity(chart)
            // 02/08/2026 (editor, na live-test — "als er in het zichtbare
            // deel geen waarden boven de 10 staan blijft hij toch op 14
            // staan") — de eerdere versie berekende de Y-as-bovengrens uit
            // de VOLLEDIG GELADEN 48u-dataset, niet uit wat daadwerkelijk in
            // het huidige zoom/pan-venster te zien is; expliciet gecorrigeerd
            // door de gebruiker: "meeschaalt met de hoogste Bg in het
            // weergave venster" bleek letterlijk het ZICHTBARE venster te
            // betekenen. recomputeYAxisMax() (zie kdoc daar) leest nu
            // chart.lowestVisibleX/highestVisibleX — precies dezelfde bron
            // als applyXAxisGranularity() hierboven al gebruikt — en wordt,
            // net als die functie, ook na elke pan/zoom-gebaar opnieuw
            // aangeroepen (zie de gesture-listener in de factory).
            recomputeYAxisMax(chart, SINGLE_CHART_Y_AXIS_LABELS)
            chart.invalidate()
        }
    )
}

/**
 * 10/08/2026 (editor, RONDE 80, letterlijk verzoek — "Op het combi tabblad
 * wil ik ook graag een grafiek waarin de beide data sets worden getoond met
 * ieder een eigen kleur") — de "bewust GEEN samengevoegde grafiek in deze
 * eerste versie"-kdoc die eerder bij CombiScreen.kt's CombiTabContent stond,
 * is hiermee ingelost. Bewust een LOSSE, eenvoudigere composable i.p.v.
 * [GlucoseChart] hierboven met een optionele tweede readings-lijst uit te
 * breiden: [GlucoseChart] heeft veel per-slot-specifieke features (de
 * gevulde 4-10-band, de per-punt bereikskleuring rood/geel/oranje, de RUWE-
 * sensorwaarde-cirkeltjes, sensor-wisselmarkers) die op een gecombineerd
 * 2-lijnen-overzicht niet allemaal zinvol/leesbaar zouden zijn (met 2
 * kleuren + eventueel ook nog bereikskleuring erbovenop zou het al snel een
 * onleesbare kleurenbrij worden) — hier dus bewust een kleinere set
 * features: alleen de 4-10 band (context, geen bereikskleuring per punt) en
 * twee vaste kleuren (één per slot, meegegeven door de aanroeper i.p.v. hier
 * zelf berekend — zie CombiScreen.kt's kdoc bij CombiTabContent voor waar
 * die kleur vandaan komt). Zelfde tijd-as-ankerpunt-, zoom/pan- en
 * granulariteitslogica als [GlucoseChart] (zie de functies onderaan dit
 * bestand, hier hergebruikt) voor een consistente gebruikerservaring tussen
 * de per-slot-tabbladen en dit gecombineerde overzicht.
 */
@Composable
fun DualGlucoseChart(
    readingsA: List<GlucoseReading>,
    readingsB: List<GlucoseReading>,
    colorA: Color,
    colorB: Color,
    // 11/08/2026 (editor, RONDE 90, op verzoek — "de ingevoerde vingerprik
    // voor de calibraties ook zichtbaar te maken in de combi curve") —
    // (timestampMs, fingerstickMgdl)-paren, al voorgefilterd door de caller
    // (CombiScreen.kt: alleen AANGEVINKTE entries, per slot z'n eigen
    // sensor-starttijd) — deze functie doet zelf geen kalibratie-logica,
    // puur tekenen. Bewust NEUTRAAL gekleurd (niet slot-A/B-kleur) — een
    // vingerprik is de "grondwaarheid" waar BEIDE curven tegen afgezet
    // worden, geen eigendom van één sensor.
    fingerstickPoints: List<Pair<Long, Double>> = emptyList(),
    // 13/08/2026 (editor, RONDE 104) — zie GlucoseChart()'s zelfde parameter
    // en de klasse-kdoc bovenaan dit bestand.
    unit: GlucoseUnit = GlucoseUnit.MMOL,
    // 29/08/2026 (editor, RONDE 160) — zie GlucoseChart()'s zelfde parameter:
    // één globale instelling (geen per-slot toggle), hier toegepast op BEIDE
    // curven tegelijk — precies wat gevraagd is ("Dan als extra aanvulling
    // voor de beide slots").
    predictionEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorAArgb = colorA.toArgb()
    val colorBArgb = colorB.toArgb()
    val bandColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val gridColorArgb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f).toArgb()
    val textColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    val allReadings = readingsA + readingsB
    val earliestReadingMs = allReadings.minOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
    val baseTimestampMs = earliestReadingMs - (earliestReadingMs % 3_600_000L)
    val timeFormat = remember(Locale.getDefault()) { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setTouchEnabled(true)
                setPinchZoom(false)
                isDragEnabled = true
                setScaleEnabled(true)
                setScaleYEnabled(false)
                setScaleXEnabled(true)
                setHighlightPerTapEnabled(false)
                setHighlightPerDragEnabled(false)
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(true)
                axisLeft.setDrawLimitLinesBehindData(true)
                axisLeft.axisMinimum = AXIS_FLOOR_MGDL
                axisLeft.axisMaximum = AXIS_DYNAMIC_MAX_FLOOR_MGDL
                setOnChartGestureListener(object : OnChartGestureListener {
                    override fun onChartGestureStart(
                        me: MotionEvent?,
                        lastPerformedGesture: ChartTouchListener.ChartGesture?
                    ) {}
                    override fun onChartGestureEnd(
                        me: MotionEvent?,
                        lastPerformedGesture: ChartTouchListener.ChartGesture?
                    ) {
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, DUAL_CHART_Y_AXIS_LABELS)
                    }
                    override fun onChartLongPressed(me: MotionEvent?) {}
                    override fun onChartDoubleTapped(me: MotionEvent?) {}
                    override fun onChartSingleTapped(me: MotionEvent?) {}
                    override fun onChartFling(
                        me1: MotionEvent?,
                        me2: MotionEvent?,
                        velocityX: Float,
                        velocityY: Float
                    ) {}
                    override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, DUAL_CHART_Y_AXIS_LABELS)
                    }
                    override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {
                        applyXAxisGranularity(this@apply)
                        recomputeYAxisMax(this@apply, DUAL_CHART_Y_AXIS_LABELS)
                    }
                })
            }
        },
        update = { chart ->
            chart.xAxis.textColor = textColorArgb
            chart.xAxis.gridColor = gridColorArgb
            chart.axisLeft.textColor = textColorArgb
            chart.axisLeft.gridColor = gridColorArgb
            chart.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val ts = baseTimestampMs + (value * 60_000L).toLong()
                    return timeFormat.format(Date(ts))
                }
            }
            // 13/08/2026 (editor, RONDE 104) — zie klasse-kdoc bovenaan dit
            // bestand: alleen de tekst verandert, de as blijft mg/dL-schaal.
            chart.axisLeft.valueFormatter = yAxisValueFormatter(unit)

            if (allReadings.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }

            // 13/08/2026 (editor, RONDE 104) — geen `.mgdlToMmol()` meer, zie
            // klasse-kdoc bovenaan dit bestand.
            fun toEntries(readings: List<GlucoseReading>): List<Entry> = readings.map { r ->
                val minutesSinceBase = (r.timestampMs - baseTimestampMs) / 60_000f
                Entry(minutesSinceBase, r.glucoseMgdl.toFloat())
            }

            val entriesA = toEntries(readingsA)
            val entriesB = toEntries(readingsB)
            val allEntries = entriesA + entriesB
            val latestGlucoseX = allEntries.maxOf { it.x }
            val earliestGlucoseX = allEntries.minOf { it.x }

            // 11/08/2026 (editor, RONDE 94 — echte oorzaak gevonden via de
            // RONDE-93-diagnostiek: de logcat liet zien dat fingerstickEntries
            // met x-waarden zoals -41/-19/-74 (dus VOOR axisMinimum, dat op
            // basis van alleen de sensor-curves berekend werd) en y-waarden
            // zoals 11,9/11,3 (dus BOVEN de door recomputeYAxisMax() alleen op
            // "slot-A"/"slot-B" gebaseerde as-bovengrens) precies buiten het
            // getekende [axisMinimum, axisMaximum]x[axisLeft.axisMinimum,
            // axisLeft.axisMaximum]-venster vielen. MPAndroidChart tekent geen
            // enkel punt buiten de as-grenzen, ook al zit het gewoon correct in
            // de dataset — vandaar dat de kalibratie-stip volledig onzichtbaar
            // bleef terwijl 'm bij CalibrationScreen.kt (die de as WEL op de
            // eigen data afstemt) gewoon verscheen.
            //
            // 11/08/2026 (editor, RONDE 95 — BUGFIX na live-melding met
            // screenshots: "de nieuwe punten worden links op de grafiek
            // getoond (dus eigenlijk gisteren)") — Ronde 94's linker-asgrens-
            // verbreding (elke fingerstick liet de as, hoe oud ook, verder
            // naar links uitrekken) bleek een NIEUW, groter probleem te
            // veroorzaken. `CalibrationStore.entries()` filtert alleen op
            // "sinds sensor-start" — bij een lang lopende sensor (CareSens
            // Air ~14 dagen, Dexcom G6 ~10 dagen) omvat dat dus ook
            // kalibraties van dagen terug, ver buiten het [24h/48h]-venster
            // dat de curve zelf toont (readingsA/readingsB komen uit
            // `store.recentReadings(hours = ...)`, zie CombiScreen.kt). Zo'n
            // oud punt trok de hele as open, waardoor de ECHTE, recente
            // curve-data samengeperst raakte in een klein stukje rechts, en
            // het oude kalibratiepunt als een losse, contextloze stip ver
            // links verscheen — precies het "lijkt wel gisteren"-effect uit
            // de melding. Een kalibratiepunt heeft alleen betekenis náást de
            // sensor-curve waar het bij hoort; een punt van vóór het
            // zichtbare curve-venster heeft daar sowieso geen BG-referentie
            // naast staan, dus de linker-asgrens-verbreding is hier weer
            // teruggedraaid (terug naar puur `earliestGlucoseX`, zoals vóór
            // Ronde 94) — MPAndroidChart tekent zo'n te-oud punt vanzelf niet
            // (geclipt aan de as), en dat is hier precies het gewenste
            // gedrag. De rechter-asgrens (latestX) rekt WEL nog steeds mee
            // met een fingerstick ná de laatst opgehaalde sensormeting — het
            // normale geval vlak na het invoeren van een verse kalibratie,
            // vóórdat de eerstvolgende sensormeting is binnengekomen; dat
            // grenst direct aan bestaande data, geeft dus geen kunstmatige
            // gat/uitrekking.
            //
            // De Y-as-fix (DUAL_CHART_Y_AXIS_LABELS hieronder uitgebreid met
            // "fingersticks") blijft wél gewoon staan — die is niet geraakt
            // door dit probleem en nog steeds nodig.
            // 12/08/2026 (editor, RONDE 99 — ECHTE oorzaak gevonden, zie
            // CombiScreen.kt's RONDE-99-kdoc voor de volledige uitleg: de
            // Ronde-97/98-diagnostiek liet zien dat de data zelf altijd al
            // klopte, maar dat welk punt wel/niet getekend werd onvoorspelbaar
            // per slot verschilde — een bekend MPAndroidChart-euvel bij een
            // NIET-oplopend-gesorteerde dataset (de interne binary-search voor
            // "welke punten vallen in het zichtbare venster" gaat dan mis).
            // `fingerstickPoints` (nu al op timestampMs gesorteerd) hoeft hier
            // dus niet nogmaals gesorteerd te worden, maar `fingerstickEntries`
            // wordt hier lokaal opnieuw opgebouwd — vandaar defensief ook hier
            // een `sortedBy` (goedkoop, en maakt deze functie niet afhankelijk
            // van de aanname dat de caller altijd al sorteert).
            // 13/08/2026 (editor, RONDE 104) — geen `.mgdlToMmol()` meer, zie
            // klasse-kdoc bovenaan dit bestand.
            val fingerstickEntries = fingerstickPoints
                .sortedBy { it.first }
                .map { (timestampMs, fingerstickMgdl) ->
                    Entry((timestampMs - baseTimestampMs) / 60_000f, fingerstickMgdl.toFloat())
                }
            val latestX = maxOf(latestGlucoseX, fingerstickEntries.maxOfOrNull { it.x } ?: latestGlucoseX)

            // 29/08/2026 (editor, RONDE 160) — zie GlucoseChart()'s zelfde
            // berekening en prediction/GlucosePrediction.kt's klasse-kdoc.
            // Per slot apart berekend (elke curve heeft z'n eigen recente
            // historie/volatiliteit), en per slot op de as geplaatst t.o.v.
            // DIE slot's eigen laatste meting — niet t.o.v. het gedeelde
            // `latestX` hierboven (dat kan door de andere slot of een
            // fingerstick al verder naar rechts opgerekt zijn).
            val predictionPointsA = if (predictionEnabled) computeGlucosePrediction(readingsA) else null
            val predictionPointsB = if (predictionEnabled) computeGlucosePrediction(readingsB) else null
            val lastReadingXA = entriesA.maxOfOrNull { it.x }
            val lastReadingXB = entriesB.maxOfOrNull { it.x }
            val predictionRightEdge = listOfNotNull(
                if (predictionPointsA != null) (lastReadingXA ?: latestGlucoseX) + PREDICTION_HORIZON_MINUTES else null,
                if (predictionPointsB != null) (lastReadingXB ?: latestGlucoseX) + PREDICTION_HORIZON_MINUTES else null
            ).maxOrNull()
            val rightEdgeX = maxOf(latestX, predictionRightEdge ?: latestX)

            chart.xAxis.axisMinimum = minOf(earliestGlucoseX, latestX - 240f)
            chart.xAxis.axisMaximum = rightEdgeX

            val bandDataSet = LineDataSet(
                listOf(Entry(latestX - 100_000f, HIGH_MGDL), Entry(latestX + 100_000f, HIGH_MGDL)),
                "target-range"
            ).apply {
                setDrawCircles(false)
                setDrawValues(false)
                // 11/08/2026 (editor, RONDE 96 — CRITICAL crash-fix na
                // live-melding met crashlog: NegativeArraySizeException in
                // com.github.mikephil.charting.utils.Transformer.
                // generateTransformedValuesLine, tijdens het pannen door de
                // combi-grafiek) — MPAndroidChart's LineChartRenderer.
                // drawValues() roept generateTransformedValuesLine() aan voor
                // ELK dataset waarvoor `isDrawValuesEnabled() ||
                // isDrawIconsEnabled()` true is. `setDrawValues(false)` (al
                // overal in dit bestand aanwezig) zet alléén de eerste helft
                // uit — `isDrawIconsEnabled()` staat in MPAndroidChart's
                // `DataSet`-basisklasse standaard op TRUE, en werd hier
                // nergens expliciet uitgezet. Het gevolg: die aanroep vond
                // dus altijd al plaats, voor alle datasets (band/slot-A/
                // slot-B/fingersticks) — puur toeval dat dit tot nu toe niet
                // crashte. `generateTransformedValuesLine()` berekent intern
                // een bereik via een binary-search-achtige mXBounds-opzoeking
                // op de zichtbare X-as-vensterrand; bij een dataset met maar
                // een handvol punten die ver van het huidige panvenster af
                // liggen (typisch voor de sparse "fingersticks"-dataset, of
                // de brede maar dunne "target-range"-band), kan die opzoeking
                // een ongeldig (negatief) bereik opleveren — vandaar
                // `new float[-16]` -> NegativeArraySizeException. Fix:
                // `setDrawIcons(false)` op ELKE dataset in dit bestand (band/
                // slot-A/slot-B/fingersticks/BG/raw-sensor), zodat
                // `shouldDrawValues()` voortaan overal echt false teruggeeft
                // en generateTransformedValuesLine() helemaal niet meer
                // aangeroepen wordt — we gebruiken toch nergens on-chart
                // waarde-labels of icons.
                setDrawIcons(false)
                setColor(android.graphics.Color.TRANSPARENT)
                setLineWidth(0f)
                setDrawFilled(true)
                setFillColor(bandColorArgb)
                setFillAlpha(60)
                setFillFormatter(IFillFormatter { _, _ -> LOW_MGDL })
                setHighlightEnabled(false)
            }

            val dataSets = mutableListOf<ILineDataSet>(bandDataSet)

            if (entriesA.isNotEmpty()) {
                dataSets += LineDataSet(entriesA, "slot-A").apply {
                    setColor(colorAArgb)
                    // 11/08/2026 (editor, RONDE 90) — zie kdoc bij
                    // DualGlucoseChart hieronder: Slot A behoudt de
                    // "normale" massief-gevulde stip + doorgetrokken lijn
                    // als vaste referentiestijl; Slot B (hieronder) wordt
                    // juist visueel onderscheiden zodra beide curves
                    // nagenoeg samenvallen.
                    setDrawCircles(true)
                    circleRadius = 3f
                    setCircleColor(colorAArgb)
                    setDrawCircleHole(false)
                    setDrawValues(false)
                    setDrawIcons(false) // 11/08/2026 (editor, RONDE 96) — zie kdoc bij bandDataSet hierboven.
                    lineWidth = 2f
                    mode = LineDataSet.Mode.LINEAR
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
            }
            if (entriesB.isNotEmpty()) {
                dataSets += LineDataSet(entriesB, "slot-B").apply {
                    setColor(colorBArgb)
                    // 11/08/2026 (editor, RONDE 90 — na live-melding: "als
                    // de beide curves nagenoeg samen vallen [wil ik dat] ze
                    // allebei beter zichtbaar worden") — MPAndroidChart's
                    // LineChart kan geen echte driehoekjes per punt tekenen
                    // (dat vereist ScatterDataSet/CombinedChart, zie de
                    // chatreactie voor de afweging); binnen LineChart zelf
                    // zijn HOLLE (ring-)stippen + een gestippelde lijn de
                    // twee sterkste beschikbare onderscheidende kenmerken,
                    // en die blijven ALTIJD zichtbaar zelfs wanneer Slot A's
                    // massieve stip/lijn er precies overheen valt: een holle
                    // ring laat Slot A's kleur er middenin doorheen zien
                    // i.p.v. 'm volledig te bedekken, en een gestippelde
                    // lijn laat op de "gaten" ook gewoon Slot A's lijn zien.
                    setDrawCircles(true)
                    circleRadius = 3.6f
                    setCircleColor(colorBArgb)
                    setDrawCircleHole(true)
                    circleHoleRadius = 1.6f
                    setCircleHoleColor(android.graphics.Color.TRANSPARENT)
                    enableDashedLine(12f, 6f, 0f)
                    setDrawValues(false)
                    setDrawIcons(false) // 11/08/2026 (editor, RONDE 96) — zie kdoc bij bandDataSet hierboven.
                    lineWidth = 2f
                    mode = LineDataSet.Mode.LINEAR
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
            }

            // 11/08/2026 (editor, RONDE 90/92) — fingerstick-markers, ALS
            // LAATSTE dataset toegevoegd (dus bovenop de twee curven
            // getekend, zie MPAndroidChart's teken-volgorde: latere datasets
            // overlappen eerdere) — grotere straal + eigen markeringskleur
            // zodat ze niet met de sensorpunten verward worden.
            //
            // 11/08/2026 (editor, RONDE 92, BUGFIX na live-melding met
            // screenshot — "de vingerprik waarden moeten niet met een lijn
            // worden verbonden maar alleen als dot worden getoond") —
            // `lineWidth = 0f` bleek NIET "geen lijn" te betekenen: Android's
            // `Paint.setStrokeWidth(0)` is speciaal-behandeld als "hairline"
            // (een altijd-1-pixel-brede lijn, ongeacht de ingestelde breedte)
            // i.p.v. onzichtbaar — vandaar de dunne lijn die tussen de
            // fingerstick-stippen door liep in de screenshot. Een `lineWidth`
            // van 0 onderdrukt in MPAndroidChart de daadwerkelijke
            // lijntekening dus niet. Fix: de lijnkleur zelf volledig
            // transparant maken (`setColor(Color.TRANSPARENT)`) — dat maakt
            // de lijn ONZICHTBAAR ongeacht de (hairline-)breedte, terwijl de
            // losse cirkel-stippen (die een eigen kleur/straal hebben,
            // los van `setColor()`) gewoon zichtbaar blijven.
            if (fingerstickEntries.isNotEmpty()) {
                dataSets += LineDataSet(fingerstickEntries, "fingersticks").apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setDrawCircles(true)
                    circleRadius = 5f
                    setCircleColor(android.graphics.Color.WHITE)
                    setDrawCircleHole(true)
                    circleHoleRadius = 2.2f
                    setCircleHoleColor(android.graphics.Color.DKGRAY)
                    setDrawValues(false)
                    setDrawIcons(false) // 11/08/2026 (editor, RONDE 96) — zie kdoc bij bandDataSet hierboven.
                    setHighlightEnabled(false)
                }
            }

            // 29/08/2026 (editor, RONDE 160) — zelfde soort divergerende
            // grenslijnen als GlucoseChart() hierboven, nu per slot (elk
            // vanaf DIE slot's eigen laatste meting, zie predictionPointsA/B
            // hierboven), zodat bij twee actieve slots ook twee losse
            // voorspellingsbanden te zien zijn. Slot-specifieke labels
            // ("prediction-upper-A"/"-B") — zie DUAL_CHART_Y_AXIS_LABELS'
            // kdoc voor waarom dat nodig is (getDataSetByLabel vindt anders
            // maar één van de twee datasets terug).
            fun predictionDataSets(
                points: List<GlucosePredictionPoint>?,
                anchorX: Float?,
                suffix: String
            ): List<ILineDataSet> {
                if (points == null || anchorX == null) return emptyList()
                val upper = LineDataSet(
                    points.map { Entry(anchorX + it.minutesFromNow, it.upperMgdl) },
                    "prediction-upper-$suffix"
                ).apply {
                    setColor(PREDICTION_BAND_COLOR_ARGB)
                    lineWidth = 1.6f
                    enableDashedLine(10f, 6f, 0f)
                    mode = LineDataSet.Mode.LINEAR
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawIcons(false)
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
                val lower = LineDataSet(
                    points.map { Entry(anchorX + it.minutesFromNow, it.lowerMgdl) },
                    "prediction-lower-$suffix"
                ).apply {
                    setColor(PREDICTION_BAND_COLOR_ARGB)
                    lineWidth = 1.6f
                    enableDashedLine(10f, 6f, 0f)
                    mode = LineDataSet.Mode.LINEAR
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawIcons(false)
                    setDrawFilled(false)
                    setHighlightEnabled(false)
                }
                return listOf(upper, lower)
            }
            dataSets += predictionDataSets(predictionPointsA, lastReadingXA, "A")
            dataSets += predictionDataSets(predictionPointsB, lastReadingXB, "B")

            chart.data = LineData(dataSets)
            chart.xAxis.removeAllLimitLines()
            // 29/08/2026 (editor, RONDE 160) — verticale "nu"-lijn, zie
            // GlucoseChart()'s zelfde toevoeging hierboven voor de volledige
            // uitleg. Hier geankerd op latestGlucoseX (de meest recente ECHTE
            // meting over BEIDE slots samen, dus zonder de fingerstick-
            // verbreding die latestX kan hebben) — dat is de meest zinvolle
            // "grens tussen echt en voorspeld" op een gecombineerde grafiek.
            if (predictionPointsA != null || predictionPointsB != null) {
                chart.xAxis.addLimitLine(
                    LimitLine(latestGlucoseX).apply {
                        lineColor = PREDICTION_NOW_LINE_COLOR_ARGB
                        lineWidth = 1.2f
                        enableDashedLine(6f, 6f, 0f)
                    }
                )
            }

            chart.setVisibleXRangeMinimum(15f)
            chart.fitScreen()
            val fullRangeMinutes = chart.xAxis.axisMaximum - chart.xAxis.axisMinimum
            if (fullRangeMinutes > 240f) {
                chart.zoom(fullRangeMinutes / 240f, 1f, rightEdgeX, 0f)
            }
            chart.moveViewToX(rightEdgeX)

            applyXAxisGranularity(chart)
            // 10/08/2026 (editor, RONDE 82) — zie kdoc bij recomputeYAxisMax()
            // onderaan dit bestand: zonder deze aanroep bleef de Y-as hier op
            // de vaste 2f..12f uit de `factory`-blok staan, ongeacht de
            // werkelijk geladen data.
            recomputeYAxisMax(chart, DUAL_CHART_Y_AXIS_LABELS)
            chart.invalidate()
        }
    )
}

/**
 * Kiest een vaste tijdlabel-stapgrootte (10/30/60 minuten) op basis van hoe
 * breed het huidige zichtbare venster is, en past 'm toe op de X-as — zie
 * kdoc bij GlucoseChart hierboven voor de reden (MPAndroidChart's eigen
 * "mooi getal"-algoritme kan ook op 20 of 50 uitkomen, dit dwingt de door
 * editor gevraagde 10/30/60-progressie af).
 */
private fun applyXAxisGranularity(chart: LineChart) {
    val visibleRangeMinutes = chart.highestVisibleX - chart.lowestVisibleX
    val granularity = when {
        visibleRangeMinutes <= 100f -> 10f
        visibleRangeMinutes <= 320f -> 30f
        else -> 60f
    }
    // Expliciete setters i.p.v. property-vorm — zie eerdere ervaring met
    // highlightEnabled hierboven.
    chart.xAxis.setGranularity(granularity)
    chart.xAxis.setGranularityEnabled(true)
    chart.invalidate()
}

/**
 * 02/08/2026 (editor, op verzoek: "wil graag dat de y-as van de grafiek
 * meeschaalt met de hoogste Bg in het weergave venster. Dus minimum 2 tot 12
 * maar als de Bg boven de 11 komt dan tot 13 en boven de 12 tot 14 laten
 * lopen", later gecorrigeerd: "als er in het zichtbare deel geen waarden
 * boven de 10 staan blijft hij toch op 14 staan") — leest de "BG"-dataset
 * rechtstreeks van de chart (i.p.v. de `entries`-lijst uit het update-blok
 * hierboven mee te geven, wat niet kan vanuit de gesture-listener in de
 * factory — die heeft alleen de chart zelf) en filtert op
 * chart.lowestVisibleX/highestVisibleX — exact hetzelfde "wat is nu
 * daadwerkelijk in beeld"-venster dat applyXAxisGranularity() hierboven ook
 * al gebruikt, dus bij pannen/zoomen blijven beide functies gegarandeerd
 * over hetzelfde venster praten. De onzichtbare band-dataset ("target-range",
 * vlak op y=10) telt bewust niet mee — vandaar het opzoeken op label "BG"
 * i.p.v. gewoon de eerste dataset te pakken.
 *
 * 06/08/2026 (editor, RONDE 54, na live-melding: "de autoscaling van de y-as
 * ... komt nu niet hoger dan 14") — de vorige versie was een letterlijke
 * 3-stappen-ladder (12/13/14) die bij >12 mmol/L simpelweg DOODLIEP op 14 —
 * een BG van 14,0 (zoals in de melding) kwam dus exact op de bovenrand van
 * de as terecht (geen enkele marge meer), en alles boven de 14 werd gewoon
 * afgekapt, onzichtbaar buiten het zichtbare venster. Vervangen door een
 * doorlopende regel i.p.v. een eindige ladder: 1 mmol/L marge boven de
 * werkelijk hoogste zichtbare waarde, naar boven afgerond op een heel getal
 * (zodat de as-labels nette hele getallen blijven, net als voorheen), met
 * een vloer van 12 (voor het bestaande, lage-BG-standaardgeval). Deze
 * formule reproduceert exact de oude 12/13/14-ladder voor waarden tot en
 * met 12 mmol/L (bv. 11,2 -> ceil(11,2)+1 = 13; 12,3 -> ceil(12,3)+1 = 14),
 * maar loopt er nu ook gewoon overheen door voor hogere waarden (14,0 ->
 * 15; 20,0 -> 21) i.p.v. daar plat te slaan.
 *
 * 10/08/2026 (editor, RONDE 82, na live-melding — "de grafiek op het combi
 * blad [schaalt] niet netjes mee met de hoogste Bg waarde zoals de
 * afzonderlijke grafieken dat wel doen") — [DualGlucoseChart] hierboven had
 * deze herberekening nooit gekregen: die zette axisLeft.axisMinimum/
 * axisMaximum alleen éénmalig vast in de `factory`-blok (2f..12f) en riep
 * deze functie nergens aan, dus een BG boven de 12 mmol/L liep gewoon tegen
 * de vaste bovenrand van de as aan (zichtbaar in het meegestuurde screenshot:
 * de roze BG-simulator-lijn precies plat tegen de rand van de grafiek). Nu
 * generiek gemaakt met een `labels`-parameter (i.p.v. de vaste, alléén op
 * [GlucoseChart]'s eigen dataset-naam "BG" gerichte lookup) zodat dezelfde
 * functie ook voor [DualGlucoseChart]'s TWEE datasets ("slot-A"/"slot-B")
 * herbruikt kan worden — het zichtbare venster moet daar over BEIDE lijnen
 * heen gemeten worden, niet over willekeurig welke van de twee toevallig als
 * eerste gevonden wordt.
 */
// 13/08/2026 (editor, RONDE 104) — mg/dL-schaal i.p.v. mmol, zie klasse-kdoc
// bovenaan dit bestand. Vertaling van de oude formule ("naar boven afgeronde
// hele mmol + 1 mmol marge, vloer 12 mmol") naar mg/dL: 1 mmol ≈ 18 mg/dL,
// dus "afronden op een heel getal" wordt hier "afronden op een tiental" en de
// marge wordt ~20 mg/dL i.p.v. 1 mmol — geeft dezelfde soort nette,
// ronde as-labels op de nieuwe schaal, met AXIS_DYNAMIC_MAX_FLOOR_MGDL (220,
// analoog aan de oude vloer van 12 mmol) als ondergrens voor de bovengrens.
private const val AXIS_MAX_ROUNDING_STEP_MGDL = 10f
private const val AXIS_MAX_MARGIN_MGDL = 20f

private fun recomputeYAxisMax(chart: LineChart, labels: List<String> = listOf("BG")) {
    val lowX = chart.lowestVisibleX
    val highX = chart.highestVisibleX
    var highestVisibleMgdl = 0f
    for (label in labels) {
        val dataSet = chart.data?.getDataSetByLabel(label, false) ?: continue
        for (i in 0 until dataSet.entryCount) {
            val entry = dataSet.getEntryForIndex(i)
            if (entry.x in lowX..highX && entry.y > highestVisibleMgdl) {
                highestVisibleMgdl = entry.y
            }
        }
    }
    val roundedUp = kotlin.math.ceil(highestVisibleMgdl / AXIS_MAX_ROUNDING_STEP_MGDL) * AXIS_MAX_ROUNDING_STEP_MGDL
    chart.axisLeft.axisMinimum = AXIS_FLOOR_MGDL
    chart.axisLeft.axisMaximum = maxOf(AXIS_DYNAMIC_MAX_FLOOR_MGDL, roundedUp + AXIS_MAX_MARGIN_MGDL)
}

/** 10/08/2026 (editor, RONDE 82) — vaste labelset voor [DualGlucoseChart]'s
 *  twee lijnen, zie kdoc bij [recomputeYAxisMax] hierboven.
 *  11/08/2026 (editor, RONDE 94) — "fingersticks" toegevoegd: zie de
 *  RONDE-94-kdoc bij de axis-berekening in [DualGlucoseChart] hierboven —
 *  zonder dit label werd de Y-as-bovengrens alleen op de twee sensor-curven
 *  afgestemd, waardoor een kalibratiepunt dat (zoals gebruikelijk) hoger
 *  lag dan de sensorwaarde simpelweg boven de zichtbare as uit viel.
 *  29/08/2026 (editor, RONDE 160) — "prediction-upper-A"/"prediction-upper-B"
 *  toegevoegd: zonder dit label kon een stijgende voorspellingsband boven de
 *  as uit lopen (afgekapt), precies dezelfde soort clipping-bug als bij de
 *  fingersticks hierboven. Onschadelijk als de datasets niet bestaan
 *  (voorspelling uit, of te weinig data) — [recomputeYAxisMax] slaat een
 *  ontbrekend label gewoon over. */
private val DUAL_CHART_Y_AXIS_LABELS =
    listOf("slot-A", "slot-B", "fingersticks", "prediction-upper-A", "prediction-upper-B")

/** 29/08/2026 (editor, RONDE 160) — analoog aan [DUAL_CHART_Y_AXIS_LABELS]
 *  hierboven, maar voor [GlucoseChart]'s enkele curve. Vervangt de losse
 *  default `listOf("BG")` die [recomputeYAxisMax] eerder had — zie kdoc
 *  daar. */
private val SINGLE_CHART_Y_AXIS_LABELS = listOf("BG", "prediction-upper")
