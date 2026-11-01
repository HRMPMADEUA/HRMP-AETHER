package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Slow drifting light blobs and twinkling points. ~25 fps, paused when not visible. */
class AmbientBackground(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgShader: Shader? = null
    private var windowVisible = true

    private class Blob(val color: Int, val fx: Float, val fy: Float, val px: Float, val py: Float, val size: Float) {
        var shader: Shader? = null
    }

    private val blobs = listOf(
        Blob(Palette.cyan, 0.032f, 0.026f, 0.0f, 1.3f, 0.72f),
        Blob(Palette.violet, 0.024f, 0.031f, 2.1f, 0.4f, 0.78f),
        Blob(Palette.blue, 0.021f, 0.028f, 4.2f, 2.6f, 0.62f)
    )

    private val starX = FloatArray(22)
    private val starY = FloatArray(34)
    private val starPh = FloatArray(34)

    init {
        var seed = 7919L
        fun next(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return seed / 2147483647f }
        for (i in starX.indices) { starX[i] = next(); starY[i] = next(); starPh[i] = next() * 6.28f }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Palette.bg1, Palette.bg0, Palette.bg0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val m = min(w, h).toFloat()
        for (b in blobs) {
            val r = m * b.size
            b.shader = RadialGradient(0f, 0f, r, Palette.withAlpha(b.color, 38), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        p.shader = bgShader
        p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        for (b in blobs) {
            p.shader = b.shader
            val x = width * (0.5f + 0.38f * sin(t * b.fx * 6.28f + b.px))
            val y = height * (0.5f + 0.34f * cos(t * b.fy * 6.28f + b.py))
            c.save(); c.translate(x, y)
            c.drawCircle(0f, 0f, min(width, height) * b.size, p)
            c.restore()
        }
        p.shader = null
        for (i in starX.indices) {
            val a = (34 + 34 * sin(t * 0.8f + starPh[i])).toInt()
            p.color = Palette.withAlpha(Palette.ice, (a * 0.72f).toInt())
            c.drawCircle(starX[i] * width, starY[i] * height, dpf(if (i % 5 == 0) 1.3f else 0.8f), p)
        }
        if (isAttachedToWindow && windowVisible) postInvalidateDelayed(40)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (windowVisible) invalidate()
    }
}
