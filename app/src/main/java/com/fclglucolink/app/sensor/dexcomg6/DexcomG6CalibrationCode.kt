package com.fclglucolink.app.sensor.dexcomg6

/**
 * ============================================================================
 * FCLGlucoLink — Dexcom G6 sensor-code -> fabriekskalibratie-parameters
 * ============================================================================
 *
 * 08/08/2026 (editor, RONDE 56) — NIET hetzelfde als de transmitter-ID
 * (DexcomG6Crypto/DexcomG6SetupScreen): dit is de 4-cijferige code die op de
 * VERPAKKING VAN ELKE INDIVIDUELE SENSOR staat (dezelfde code die je ook in
 * xDrip+/de officiële Dexcom-app intypt bij het starten van een NIEUWE
 * sensor). De G6 heeft geen vingerprik-kalibratie nodig, maar heeft deze
 * code WEL nodig om de ruwe signalen naar mg/dL te kunnen omrekenen — zonder
 * geldige code bij het starten van een nieuwe sensor komt er geen bruikbare
 * data uit. Dit stond in de vorige ronde onterecht op één hoop met de
 * (wél terecht weggelaten) vingerprik-kalibratie-terugstuurstap; zie
 * DexcomG6Protocol.kt's kdoc voor de correctie.
 *
 * Rechtstreeks geport van xDrip+'s `G6CalibrationParameters.java` (zelf een
 * publieke, al jarenlang bekende tabel — geen eigen reverse-engineering).
 * Elke code komt in twee varianten voor (twee sensoren uit dezelfde
 * fabricagebatch kunnen dezelfde paramA/paramB hebben); "0000" is de
 * speciale "null-code" (paramB=0) die xDrip+ ook apart behandelt — bij die
 * code wordt er GEEN paramA/paramB meegestuurd in het sessie-start-bericht,
 * zie DexcomG6Protocol.buildSessionStart()'s kdoc.
 */
data class DexcomG6CalibrationParams(val code: String, val paramA: Int, val paramB: Int) {
    val isValid: Boolean get() = paramA > 0
    val isNullCode: Boolean get() = isValid && paramB == 0
}

object DexcomG6CalibrationCode {

    private val table: Map<String, Pair<Int, Int>> = buildMap {
        put("0000", 1 to 0) // speciale null-code
        put("5915", 3100 to 3600); put("9759", 3100 to 3600)
        put("5917", 3000 to 3500); put("9357", 3000 to 3500)
        put("5931", 2900 to 3400); put("9137", 2900 to 3400)
        put("5937", 2800 to 3300); put("7197", 2800 to 3300)
        put("5951", 3100 to 3500); put("9517", 3100 to 3500)
        put("5955", 3000 to 3400); put("9179", 3000 to 3400)
        put("7171", 2700 to 3300); put("7539", 2700 to 3300)
        put("9117", 2700 to 3200); put("7135", 2700 to 3200)
        put("9159", 2600 to 3200); put("5397", 2600 to 3200)
        put("9311", 2600 to 3100); put("5391", 2600 to 3100)
        put("9371", 2500 to 3100); put("5375", 2500 to 3100)
        put("9515", 2500 to 3000); put("5795", 2500 to 3000)
        put("9551", 2400 to 3000); put("5317", 2400 to 3000)
        put("9577", 2400 to 2900); put("5177", 2400 to 2900)
        put("9713", 2300 to 2900); put("5171", 2300 to 2900)
    }

    fun lookup(code: String): DexcomG6CalibrationParams {
        val (a, b) = table[code] ?: (-1 to -1)
        return DexcomG6CalibrationParams(code, a, b)
    }

    fun checkCode(code: String): Boolean = lookup(code).isValid
}
