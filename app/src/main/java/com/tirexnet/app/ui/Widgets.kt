package com.tirexnet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

open class GlassCard(
    ctx: Context,
    radius: Float = 20f,
    stroke: Int = Palette.stroke,
    pad: Int = 14
) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = Palette.glass(ctx, radius, stroke)
        val q = ctx.dpi(pad)
        setPadding(q, q, q, q)
    }
}

/** Shimmering placeholder shown while configurations load. */
class SkeletonView(ctx: Context, private val radiusDp: Float = 18f) : View(ctx) {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 120, 170, 230) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var shader: LinearGradient? = null
    private val rect = RectF()
    private var windowVisible = true

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(0, 150, 220, 255), Color.argb(54, 150, 220, 255), Color.argb(0, 150, 220, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        shine.shader = shader
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        val r = dpf(radiusDp)
        c.drawRoundRect(rect, r, r, base)
        val phase = (SystemClock.uptimeMillis() % 1400L) / 1400f
        m.setTranslate(-width + phase * width * 2f, 0f)
        shader?.setLocalMatrix(m)
        c.drawRoundRect(rect, r, r, shine)
        if (isAttachedToWindow && windowVisible) postInvalidateDelayed(33)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (windowVisible) invalidate()
    }
}

/** Custom empty-state graphic: orbits, satellites and a server glyph. Static, so it costs nothing. */
class EmptyArt(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f
        c.save(); c.translate(cx, cy)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        val radii = floatArrayOf(0.34f, 0.56f, 0.78f, 0.98f)
        for (i in radii.indices) {
            p.strokeWidth = dpf(1f); p.color = Palette.withAlpha(Palette.cyan, 70 - i * 14)
            c.drawCircle(0f, 0f, R * radii[i], p)
        }
        val dots = listOf(Pair(0.56f, 35.0), Pair(0.78f, 160.0), Pair(0.98f, 285.0), Pair(0.34f, 235.0))
        p.style = Paint.Style.FILL
        for ((i, d) in dots.withIndex()) {
            val a = Math.toRadians(d.second)
            val rr = R * d.first
            val px = (cos(a) * rr).toFloat(); val py = (sin(a) * rr).toFloat()
            p.color = Palette.withAlpha(if (i % 2 == 0) Palette.cyan else Palette.violet, 60)
            c.drawCircle(px, py, dpf(7f), p)
            p.color = if (i % 2 == 0) Palette.cyan else Palette.violet
            c.drawCircle(px, py, dpf(3f), p)
        }
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND
        val s = R * 0.17f
        p.strokeWidth = s * 0.5f; p.color = Palette.withAlpha(Palette.cyan, 50)
        Icons.draw(c, Icon.SERVERS, s, p)
        p.strokeWidth = s * 0.17f; p.color = Palette.ice
        Icons.draw(c, Icon.SERVERS, s, p)
        c.restore()
    }
}

/** Circular usage meter with an animated sweep. */
class UsageRing(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var sweep: Shader? = null
    private var shown = 0f
    private var hasFraction = false
    private var anim: ValueAnimator? = null
    private var center = "—"
    private var sub = ""

    fun update(fraction: Float?, centerText: String, subText: String) {
        center = centerText; sub = subText
        hasFraction = fraction != null
        val target = (fraction ?: 0f).coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(Palette.cyan, Palette.blue, Palette.violet, Palette.cyan), null)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val w = dpf(10f)
        val r = min(width, height) / 2f - w
        rect.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.shader = null; p.strokeWidth = w; p.color = Color.argb(34, 150, 200, 255)
        c.drawCircle(cx, cy, r, p)
        if (hasFraction && shown > 0.002f) {
            c.save(); c.rotate(-90f, cx, cy)
            p.shader = sweep
            p.strokeWidth = w
            c.drawArc(rect, 0f, 360f * shown, false, p)
            c.restore(); p.shader = null
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.text; textAlign = Paint.Align.CENTER; textSize = dpf(20f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        c.drawText(center, cx, cy + dpf(2f), big)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.muted; textAlign = Paint.Align.CENTER; textSize = dpf(10.5f)
        }
        c.drawText(sub, cx, cy + dpf(20f), small)
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}

/** Slim gradient progress bar. */
class LinearMeter(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private var shader: Shader? = null

    fun set(fraction: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, fraction.coerceIn(0f, 1f)).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Palette.cyan, Palette.violet, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.argb(34, 150, 200, 255)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, p)
        if (shown > 0.004f) {
            p.shader = shader
            rect.set(0f, 0f, (width * shown).coerceAtLeast(height.toFloat()), height.toFloat())
            c.drawRoundRect(rect, r, r, p)
            p.shader = null
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}
