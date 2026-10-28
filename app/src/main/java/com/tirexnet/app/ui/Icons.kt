package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Icon { HOME, SERVERS, STATS, PROFILE, POWER, REFRESH, GEAR, CHEVRON, SEARCH, LOGOUT, COPY, TRASH, CHECK, INFO, SHIELD }

/** Vector icons drawn with plain strokes. `s` is the half extent; paint must be STROKE with round caps. */
object Icons {
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, icon: Icon, s: Float, p: Paint) {
        val old = p.style
        when (icon) {
            Icon.HOME -> {
                path.reset()
                path.moveTo(-.95f * s, -.05f * s); path.lineTo(0f, -.9f * s); path.lineTo(.95f * s, -.05f * s)
                path.moveTo(-.65f * s, -.2f * s); path.lineTo(-.65f * s, .85f * s)
                path.lineTo(.65f * s, .85f * s); path.lineTo(.65f * s, -.2f * s)
                path.moveTo(-.2f * s, .85f * s); path.lineTo(-.2f * s, .3f * s)
                path.lineTo(.2f * s, .3f * s); path.lineTo(.2f * s, .85f * s)
                c.drawPath(path, p)
            }
            Icon.SERVERS -> {
                for (i in 0..2) {
                    val top = (-.95f + i * .68f) * s
                    rect.set(-.85f * s, top, .85f * s, top + .5f * s)
                    p.style = Paint.Style.STROKE
                    c.drawRoundRect(rect, .14f * s, .14f * s, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(-.5f * s, top + .25f * s, .07f * s, p)
                    c.drawCircle(-.28f * s, top + .25f * s, .07f * s, p)
                }
            }
            Icon.STATS -> {
                c.drawLine(-.95f * s, .9f * s, .95f * s, .9f * s, p)
                val xs = floatArrayOf(-.55f, 0f, .55f)
                val tops = floatArrayOf(.05f, -.85f, -.35f)
                for (i in 0..2) {
                    rect.set((xs[i] - .16f) * s, tops[i] * s, (xs[i] + .16f) * s, .72f * s)
                    c.drawRoundRect(rect, .08f * s, .08f * s, p)
                }
            }
            Icon.PROFILE -> {
                c.drawCircle(0f, -.4f * s, .42f * s, p)
                rect.set(-.85f * s, .15f * s, .85f * s, 1.75f * s)
                c.drawArc(rect, 180f, 180f, false, p)
            }
            Icon.POWER -> {
                rect.set(-.7f * s, -.7f * s, .7f * s, .7f * s)
                c.drawArc(rect, -55f, 290f, false, p)
                c.drawLine(0f, -.95f * s, 0f, -.12f * s, p)
            }
            Icon.REFRESH -> {
                rect.set(-.7f * s, -.7f * s, .7f * s, .7f * s)
                c.drawArc(rect, 30f, 290f, false, p)
                path.reset()
                path.moveTo(.9f * s, -.95f * s); path.lineTo(.9f * s, -.4f * s); path.lineTo(.35f * s, -.4f * s)
                c.drawPath(path, p)
            }
            Icon.GEAR -> {
                c.drawCircle(0f, 0f, .32f * s, p)
                c.drawCircle(0f, 0f, .62f * s, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    c.drawLine(ca * .62f * s, sa * .62f * s, ca * .92f * s, sa * .92f * s, p)
                }
            }
            Icon.CHEVRON -> {
                path.reset()
                path.moveTo(-.3f * s, -.6f * s); path.lineTo(.3f * s, 0f); path.lineTo(-.3f * s, .6f * s)
                c.drawPath(path, p)
            }
            Icon.SEARCH -> {
                c.drawCircle(-.15f * s, -.15f * s, .6f * s, p)
                c.drawLine(.3f * s, .3f * s, .9f * s, .9f * s, p)
            }
            Icon.LOGOUT -> {
                path.reset()
                path.moveTo(0f, -.9f * s); path.lineTo(-.8f * s, -.9f * s)
                path.lineTo(-.8f * s, .9f * s); path.lineTo(0f, .9f * s)
                path.moveTo(-.2f * s, 0f); path.lineTo(.9f * s, 0f)
                path.moveTo(.5f * s, -.4f * s); path.lineTo(.9f * s, 0f); path.lineTo(.5f * s, .4f * s)
                c.drawPath(path, p)
            }
            Icon.COPY -> {
                rect.set(-.3f * s, -.3f * s, .85f * s, .9f * s)
                c.drawRoundRect(rect, .12f * s, .12f * s, p)
                path.reset()
                path.moveTo(-.55f * s, .45f * s); path.lineTo(-.85f * s, .45f * s)
                path.lineTo(-.85f * s, -.85f * s); path.lineTo(.3f * s, -.85f * s); path.lineTo(.3f * s, -.55f * s)
                c.drawPath(path, p)
            }
            Icon.TRASH -> {
                c.drawLine(-.8f * s, -.5f * s, .8f * s, -.5f * s, p)
                path.reset()
                path.moveTo(-.25f * s, -.5f * s); path.lineTo(-.25f * s, -.8f * s)
                path.lineTo(.25f * s, -.8f * s); path.lineTo(.25f * s, -.5f * s)
                path.moveTo(-.6f * s, -.5f * s); path.lineTo(-.5f * s, .9f * s)
                path.lineTo(.5f * s, .9f * s); path.lineTo(.6f * s, -.5f * s)
                c.drawPath(path, p)
            }
            Icon.CHECK -> {
                path.reset()
                path.moveTo(-.7f * s, .05f * s); path.lineTo(-.2f * s, .55f * s); path.lineTo(.75f * s, -.5f * s)
                c.drawPath(path, p)
            }
            Icon.INFO -> {
                c.drawCircle(0f, 0f, .9f * s, p)
                c.drawLine(0f, -.05f * s, 0f, .45f * s, p)
                p.style = Paint.Style.FILL
                c.drawCircle(0f, -.38f * s, .07f * s, p)
            }
            Icon.SHIELD -> {
                path.reset()
                path.moveTo(0f, -.95f * s); path.lineTo(.8f * s, -.6f * s); path.lineTo(.7f * s, .3f * s)
                path.quadTo(.5f * s, .75f * s, 0f, .98f * s); path.quadTo(-.5f * s, .75f * s, -.7f * s, .3f * s)
                path.lineTo(-.8f * s, -.6f * s); path.close()
                c.drawPath(path, p)
            }
        }
        p.style = old
    }
}

class IconView(ctx: Context, var icon: Icon, var tint: Int = Palette.ice, private val glow: Boolean = false) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun set(newIcon: Icon, color: Int) { icon = newIcon; tint = color; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 2f * 0.66f
        c.save()
        c.translate(width / 2f, height / 2f)
        val w = (s * 0.16f).coerceAtLeast(dpf(1.4f))
        if (glow) {
            p.strokeWidth = w * 2.6f
            p.color = Palette.withAlpha(tint, 50)
            Icons.draw(c, icon, s, p)
        }
        p.strokeWidth = w
        p.color = tint
        Icons.draw(c, icon, s, p)
        c.restore()
    }
}

/** Static brand emblem: gradient hexagon with a stylised T. */
class BrandMark(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hex = Path()
    private var sweep: SweepGradient? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(Palette.cyan, Palette.violet, Palette.blue, Palette.cyan), null)
        val r = min(w, h) / 2f - dpf(2f)
        hex.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians(-90.0 + i * 60.0)
            val x = w / 2f + (r * cos(a)).toFloat(); val y = h / 2f + (r * sin(a)).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        p.style = Paint.Style.FILL; p.shader = null; p.color = Color.argb(150, 6, 14, 34)
        c.drawPath(hex, p)
        p.style = Paint.Style.STROKE; p.shader = sweep; p.strokeWidth = dpf(2.2f); p.strokeJoin = Paint.Join.ROUND
        c.drawPath(hex, p)
        p.shader = null; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * 0.17f
        p.color = Palette.withAlpha(Palette.cyan, 70); p.strokeWidth = r * 0.34f
        c.drawLine(cx - r * .38f, cy - r * .3f, cx + r * .38f, cy - r * .3f, p)
        c.drawLine(cx, cy - r * .3f, cx, cy + r * .42f, p)
        p.strokeWidth = r * 0.16f; p.color = Palette.text
        c.drawLine(cx - r * .38f, cy - r * .3f, cx + r * .38f, cy - r * .3f, p)
        c.drawLine(cx, cy - r * .3f, cx, cy + r * .42f, p)
    }
}
