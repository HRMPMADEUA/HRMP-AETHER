package com.tirexnet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Color
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max

class GlassNavBar(ctx: Context) : View(ctx) {
    private val items = listOf(
        Icon.HOME to "Home", Icon.SERVERS to "Servers", Icon.STATS to "Stats", Icon.PROFILE to "Profile"
    )
    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val bar = RectF()
    private var indicatorX = -1f
    private var anim: ValueAnimator? = null
    private var glow: Shader? = null

    init { contentDescription = "Navigation" }

    private fun itemW() = bar.width() / items.size
    private fun centerOf(i: Int) = bar.left + itemW() * (i + 0.5f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val mx = dpf(16f); val my = dpf(6f)
        bar.set(mx, my, w - mx, h - my)
        glow = RadialGradient(0f, 0f, dpf(44f), Palette.withAlpha(Palette.cyan, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        indicatorX = centerOf(selected)
    }

    fun select(i: Int, animate: Boolean = true) {
        if (i == selected && indicatorX >= 0f) return
        selected = i
        anim?.cancel()
        if (!animate || bar.isEmpty) { indicatorX = centerOf(i); invalidate(); return }
        anim = ValueAnimator.ofFloat(indicatorX, centerOf(i)).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { indicatorX = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val r = dpf(24f)
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.argb(214, 7, 15, 36)
        c.drawRoundRect(bar, r, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1f); p.color = Palette.stroke
        c.drawRoundRect(bar, r, r, p)

        val iy = bar.top + bar.height() * 0.40f
        // glow under the active item
        c.save(); c.translate(indicatorX, iy)
        p.style = Paint.Style.FILL; p.shader = glow
        c.drawCircle(0f, 0f, dpf(44f), p)
        c.restore(); p.shader = null
        // top indicator pill
        p.style = Paint.Style.FILL; p.color = Palette.cyan
        c.drawRoundRect(indicatorX - dpf(14f), bar.top, indicatorX + dpf(14f), bar.top + dpf(3f), dpf(2f), dpf(2f), p)

        val baseSize = dpf(11f)
        for (i in items.indices) {
            val cx = centerOf(i)
            val act = max(0f, 1f - abs(indicatorX - cx) / itemW())
            val color = Palette.lerp(Palette.muted, Palette.cyan, act)
            val size = baseSize * (1f + 0.14f * act)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            c.save(); c.translate(cx, iy - dpf(2f) * act)
            if (act > 0.05f) {
                p.strokeWidth = size * 0.36f; p.color = Palette.withAlpha(Palette.cyan, (54 * act).toInt())
                Icons.draw(c, items[i].first, size, p)
            }
            p.strokeWidth = size * 0.17f; p.color = color
            Icons.draw(c, items[i].first, size, p)
            c.restore()
            label.color = color
            c.drawText(items[i].second, cx, bar.top + bar.height() * 0.86f, label)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (bar.contains(e.x, e.y.coerceIn(bar.top, bar.bottom))) {
                    val idx = ((e.x - bar.left) / itemW()).toInt().coerceIn(0, items.size - 1)
                    if (idx != selected) { select(idx); onSelect?.invoke(idx) }
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
