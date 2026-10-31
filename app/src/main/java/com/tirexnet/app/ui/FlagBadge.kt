package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import java.util.Locale
import kotlin.math.min

/**
 * Round country badge: the flag fills a glass disc, framed by a gradient ring with a soft glow and a
 * glossy highlight. Unknown country = outlined globe. Selected profiles get a brighter ring and a check dot.
 * Everything is Canvas-drawn; shaders are rebuilt only when size, accent or state change (never in onDraw).
 */
class FlagBadge(ctx: Context) : View(ctx) {
    private var emoji = ""
    private var accent = Palette.cyan
    private var selectedState = false

    private val d = ctx.resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val check = Path()

    fun setCountry(code: String) {
        val c = code.trim().uppercase(Locale.US)
        emoji = if (c.length == 2 && c.all { it in 'A'..'Z' }) {
            String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        } else ""
        contentDescription = if (emoji.isEmpty()) "Country unknown" else "Country $c"
        invalidate()
    }

    fun setAccent(color: Int) { accent = color; rebuild(); invalidate() }

    fun setSelectedState(on: Boolean) { selectedState = on; rebuild(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuild() }

    private fun rebuild() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f - 3f * d
        glow.shader = RadialGradient(cx, cy, min(w, h) / 2f,
            intArrayOf(Palette.withAlpha(accent, if (selectedState) 120 else 70), Palette.withAlpha(accent, 0)),
            floatArrayOf(r / (min(w, h) / 2f) * 0.85f, 1f), Shader.TileMode.CLAMP)
        disc.color = Palette.fieldFill
        ring.strokeWidth = 2.2f * d
        ring.shader = SweepGradient(cx, cy,
            intArrayOf(accent, Palette.violet, Palette.withAlpha(accent, 150), accent), floatArrayOf(0f, 0.4f, 0.75f, 1f))
        shine.shader = LinearGradient(0f, cy - r, 0f, cy, Color.argb(if (Palette.dark) 70 else 110, 255, 255, 255),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        clip.reset(); clip.addCircle(cx, cy, r - 1.4f * d, Path.Direction.CW)
        text.textSize = r * 1.5f
        iconPaint.color = Palette.muted; iconPaint.strokeWidth = 1.6f * d
        dotFill.color = Palette.mint
        dotStroke.color = Palette.bg0; dotStroke.strokeWidth = 1.6f * d
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f - 3f * d
        c.drawCircle(cx, cy, min(w, h) / 2f, glow)
        c.drawCircle(cx, cy, r, disc)
        c.save()
        c.clipPath(clip)
        if (emoji.isNotEmpty()) {
            val fm = text.fontMetrics
            c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2f, text)
        } else {
            c.save(); c.translate(cx, cy); Icons.draw(c, Icon.GLOBE, r * 0.5f, iconPaint); c.restore()
        }
        c.drawCircle(cx, cy, r, shine)
        c.restore()
        c.drawCircle(cx, cy, r, ring)
        if (selectedState) {
            val dx = cx + r * 0.72f; val dy = cy + r * 0.72f; val dr = r * 0.3f
            c.drawCircle(dx, dy, dr, dotFill)
            c.drawCircle(dx, dy, dr, dotStroke)
            check.reset()
            check.moveTo(dx - dr * 0.45f, dy); check.lineTo(dx - dr * 0.1f, dy + dr * 0.38f); check.lineTo(dx + dr * 0.5f, dy - dr * 0.34f)
            iconPaint.color = Palette.onPrimary; c.drawPath(check, iconPaint); iconPaint.color = Palette.muted
        }
    }
}
