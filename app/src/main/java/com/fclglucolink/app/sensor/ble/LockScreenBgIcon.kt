package com.fclglucolink.app.sensor.ble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * RONDE 229 — tekent de BG-cirkel van het hoofdscherm (StatusScreen.kt `BgRingDisplay`) als plaatje,
 * voor gebruik als groot pictogram in de lockscreen-melding: gekleurde ring (kleur naar bereik),
 * BG-waarde in het midden en een driehoekige trendpijl tegen de ring (hoek naar het verschil met
 * de vorige meting). Kleuren, bereiken en hoeken zijn dezelfde als op het hoofdscherm.
 */
object LockScreenBgIcon {

    private const val SIZE = 256
    private const val CENTER = SIZE / 2f
    private const val RING_RADIUS = 82f
    private const val RING_WIDTH = 12f
    // Op het hoofdscherm: ring 120 dp, rand 6 dp, driehoek 20 dp lang en 28 dp breed.
    private const val SCALE = RING_RADIUS / 60f

    private val GREEN = Color.parseColor("#FF4CAF50")
    private val AMBER = Color.parseColor("#FFFFA000")
    private val RED = Color.parseColor("#FFCF6679")
    private val BACKGROUND = Color.parseColor("#FF14181E")

    fun render(bgText: String, glucoseMgdl: Double, deltaMgdl: Double?): Bitmap {
        val color = when {
            glucoseMgdl in 70.0..180.0 -> GREEN
            glucoseMgdl in 54.0..252.0 -> AMBER
            else -> RED
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = BACKGROUND }
        canvas.drawCircle(CENTER, CENTER, RING_RADIUS, fill)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_WIDTH
            this.color = color
        }
        canvas.drawCircle(CENTER, CENTER, RING_RADIUS - RING_WIDTH / 2f, ring)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 96f
        }
        val maxWidth = RING_RADIUS * 2f - RING_WIDTH * 3f
        while (text.measureText(bgText) > maxWidth && text.textSize > 24f) text.textSize -= 4f
        val baseline = CENTER - (text.ascent() + text.descent()) / 2f
        canvas.drawText(bgText, CENTER, baseline, text)

        if (deltaMgdl != null) drawChevron(canvas, color, deltaMgdl / 18.0)
        return bitmap
    }

    /**
     * Klein, eenkleurig (wit op transparant) pictogram voor de statusbalk en het gedimde/vergrendelde
     * lockscreen: daar toont Android alleen het KLEINE pictogram als silhouet, het grote pictogram
     * (de gekleurde cirkel) verdwijnt. Ring + BG-waarde + een kleine trendpijl-driehoek; het systeem
     * kleurt het zelf in. Zelfde techniek als xDrip's BG-in-statusbalk.
     */
    fun renderSmall(bgText: String, deltaMgdl: Double?): Bitmap {
        val size = 96
        val c = size / 2f
        val radius = 40f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE
        }
        canvas.drawCircle(c, c, radius, ring)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER; textSize = 44f
        }
        val maxWidth = radius * 2f - 14f
        while (text.measureText(bgText) > maxWidth && text.textSize > 16f) text.textSize -= 2f
        canvas.drawText(bgText, c, c - (text.ascent() + text.descent()) / 2f, text)
        if (deltaMgdl != null) {
            val deltaMmol = deltaMgdl / 18.0
            val deg = when {
                deltaMmol >= 0.8 -> -90.0
                deltaMmol >= 0.5 -> -60.0
                deltaMmol >= 0.3 -> -45.0
                deltaMmol >= 0.1 -> -30.0
                deltaMmol > -0.1 -> 0.0
                deltaMmol > -0.3 -> 30.0
                deltaMmol > -0.5 -> 45.0
                deltaMmol > -0.8 -> 60.0
                else -> 90.0
            }
            val rad = Math.toRadians(deg)
            val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
            val ax = c + dx * (radius - 2f); val ay = c + dy * (radius - 2f)
            val path = Path().apply {
                moveTo(ax - dy * 9f, ay + dx * 9f)
                lineTo(ax + dx * 13f, ay + dy * 13f)
                lineTo(ax + dy * 9f, ay - dx * 9f)
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE })
        }
        return bitmap
    }

    /** Zelfde drempels (in mmol/L) en meetkunde als StatusScreen.kt `TrendChevronCanvas`. */
    private fun drawChevron(canvas: Canvas, color: Int, deltaMmol: Double) {
        val rotationDegrees = when {
            deltaMmol >= 0.8 -> -90.0
            deltaMmol >= 0.5 -> -60.0
            deltaMmol >= 0.3 -> -45.0
            deltaMmol >= 0.1 -> -30.0
            deltaMmol > -0.1 -> 0.0
            deltaMmol > -0.3 -> 30.0
            deltaMmol > -0.5 -> 45.0
            deltaMmol > -0.8 -> 60.0
            else -> 90.0
        }
        val rad = Math.toRadians(rotationDegrees)
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        val attachRadius = RING_RADIUS - 3f * SCALE
        val attachX = CENTER + dirX * attachRadius
        val attachY = CENTER + dirY * attachRadius
        val length = 20f * SCALE
        val halfBase = 14f * SCALE
        val path = Path().apply {
            moveTo(attachX - dirY * halfBase, attachY + dirX * halfBase)
            lineTo(attachX + dirX * length, attachY + dirY * length)
            lineTo(attachX + dirY * halfBase, attachY - dirX * halfBase)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        canvas.drawPath(path, paint)
    }
}
