package com.tirexnet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED, PERMISSION }

/** Large circular connection control. Everything is Canvas-drawn (GPU composited); loops stop when hidden. */
class ConnectOrb(ctx: Context) : View(ctx) {
    var onToggle: (() -> Unit)? = null

    var state: OrbState = OrbState.DISCONNECTED
        set(value) {
            if (field == value) return
            field = value
            startTransition()
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var fromColor = colorFor(OrbState.DISCONNECTED)
    private var toColor = fromColor
    private var fromI = intensityFor(OrbState.DISCONNECTED)
    private var toI = fromI
    private var curColor = fromColor
    private var curI = fromI
    private var transition: ValueAnimator? = null

    private var pressScale = 1f
    private var pressAnim: ValueAnimator? = null
    private var windowVisible = true

    private var glowShader: Shader? = null
    private var discShader: Shader? = null
    private var ticks = FloatArray(0)
    private var filterColor = 0
    private var filter: ColorFilter? = null
    private val dash = DashPathEffect(floatArrayOf(dpf(1.5f), dpf(10f)), 0f)

    private fun colorFor(s: OrbState): Int = when (s) {
        OrbState.DISCONNECTED -> Color.rgb(120, 170, 230)
        OrbState.CONNECTING -> Palette.cyan
        OrbState.CONNECTED -> Color.rgb(0, 238, 190)
        OrbState.DISCONNECTING -> Color.rgb(110, 200, 255)
        OrbState.FAILED -> Palette.red
        OrbState.PERMISSION -> Palette.amber
    }

    private fun intensityFor(s: OrbState): Float = when (s) {
        OrbState.DISCONNECTED -> 0.5f
        OrbState.CONNECTING -> 0.95f
        OrbState.CONNECTED -> 1f
        OrbState.DISCONNECTING -> 0.65f
        OrbState.FAILED -> 0.85f
        OrbState.PERMISSION -> 0.8f
    }

    private fun startTransition() {
        fromColor = curColor; fromI = curI
        toColor = colorFor(state); toI = intensityFor(state)
        transition?.cancel()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val m = it.animatedValue as Float
                curColor = Palette.lerp(fromColor, toColor, m)
                curI = fromI + (toI - fromI) * m
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = min(w, h) / 2f
        glowShader = RadialGradient(
            0f, 0f, r, intArrayOf(Color.WHITE, Color.argb(80, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0.30f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        discShader = RadialGradient(
            0f, -r * 0.18f, r * 0.62f, intArrayOf(Color.rgb(22, 44, 92), Color.rgb(6, 12, 30)),
            null, Shader.TileMode.CLAMP
        )
        val n = 72
        ticks = FloatArray(n * 4)
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n)
            val inner = if (i % 6 == 0) 0.935f else 0.955f
            ticks[i * 4] = (cos(a) * r * inner).toFloat()
            ticks[i * 4 + 1] = (sin(a) * r * inner).toFloat()
            ticks[i * 4 + 2] = (cos(a) * r * 0.985f).toFloat()
            ticks[i * 4 + 3] = (sin(a) * r * 0.985f).toFloat()
        }
    }

    private fun stroke(w: Float, color: Int, alpha: Int) {
        p.shader = null; p.colorFilter = null; p.pathEffect = null
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
        p.color = color
        p.alpha = alpha.coerceIn(0, 255)
    }

    private fun tailArc(c: Canvas, r: Float, start: Float, sweep: Float, w: Float, alpha: Int) {
        rect.set(-r, -r, r, r)
        val seg = 7
        val each = sweep / seg
        for (i in 0 until seg) {
            val a = alpha * (i + 1) / seg
            stroke(w, curColor, a)
            c.drawArc(rect, start - sweep + each * i, each + 0.6f, false, p)
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis() / 1000f
        val R = min(width, height) / 2f
        val i = curI
        val col = curColor
        c.save()
        c.translate(width / 2f, height / 2f)

        val breathe = when (state) {
            OrbState.CONNECTING -> 0.5f + 0.5f * sin(now * 5f)
            OrbState.CONNECTED -> 0.85f + 0.15f * sin(now * 1.6f)
            else -> 0.6f + 0.4f * sin(now * 1.1f)
        }

        // ambient glow
        if (col != filterColor || filter == null) {
            filterColor = col
            filter = PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN)
        }
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.FILL; p.shader = glowShader; p.colorFilter = filter
        p.alpha = (255 * i * (0.32f + 0.40f * breathe)).toInt().coerceIn(0, 255)
        c.drawCircle(0f, 0f, R, p)

        // tick ring
        val dir = if (state == OrbState.DISCONNECTING) -1f else 1f
        c.save(); c.rotate(now * 5f * dir)
        stroke(dpf(1f), col, (74 * i).toInt())
        c.drawLines(ticks, p)
        c.restore()

        // dashed ring
        val dashSpeed = when (state) { OrbState.CONNECTING -> -34f; OrbState.DISCONNECTING -> 28f; else -> -9f }
        c.save(); c.rotate(now * dashSpeed)
        stroke(dpf(1.6f), col, (120 * i).toInt())
        p.pathEffect = dash
        c.drawCircle(0f, 0f, R * 0.84f, p)
        c.restore()

        // base ring + sweeping arc
        stroke(dpf(1.2f), col, (46 * i).toInt())
        c.drawCircle(0f, 0f, R * 0.73f, p)
        when (state) {
            OrbState.CONNECTING -> tailArc(c, R * 0.73f, (now * 230f) % 360f, 110f, dpf(3.2f), (255 * i).toInt())
            OrbState.CONNECTED -> tailArc(c, R * 0.73f, (now * 42f) % 360f, 80f, dpf(3f), (230 * i).toInt())
            OrbState.DISCONNECTING -> tailArc(c, R * 0.73f, (360f - (now * 170f) % 360f), 90f, dpf(3f), (200 * i).toInt())
            OrbState.FAILED, OrbState.PERMISSION -> tailArc(c, R * 0.73f, -90f, 70f, dpf(3f), (210 * i).toInt())
            OrbState.DISCONNECTED -> Unit
        }

        // expanding pulse
        if (state == OrbState.CONNECTING || state == OrbState.CONNECTED) {
            val period = if (state == OrbState.CONNECTING) 1.1f else 3.2f
            val ph = (now % period) / period
            val a = (1f - ph) * (if (state == OrbState.CONNECTING) 0.75f else 0.32f)
            stroke(dpf(2f), col, (255 * a * i).toInt())
            c.drawCircle(0f, 0f, R * (0.55f + 0.40f * ph), p)
        }

        // core disc
        val rd = R * 0.52f * pressScale
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.FILL; p.shader = discShader
        c.drawCircle(0f, 0f, rd, p)
        stroke(dpf(7f), col, (34 * i * (0.6f + 0.4f * breathe)).toInt())
        c.drawCircle(0f, 0f, rd, p)
        stroke(dpf(2f), col, (230 * i).toInt())
        c.drawCircle(0f, 0f, rd, p)

        // power icon (soft halo pass + sharp pass)
        val s = rd * 0.42f
        stroke(s * 0.42f, col, (60 * i).toInt())
        Icons.draw(c, Icon.POWER, s, p)
        stroke(s * 0.17f, Palette.lerp(Color.WHITE, col, 0.35f), (255 * (0.55f + 0.45f * i)).toInt())
        Icons.draw(c, Icon.POWER, s, p)
        c.restore()

        if (isAttachedToWindow && windowVisible) {
            postInvalidateDelayed(
                when (state) {
                    OrbState.CONNECTING, OrbState.DISCONNECTING -> 16L
                    OrbState.CONNECTED -> 33L
                    else -> 50L
                }
            )
        }
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 130
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = min(width, height) / 2f * 0.66f
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        val inside = dx * dx + dy * dy <= r * r
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePress(0.93f)
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(1f)
                if (inside) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(1f)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (windowVisible) invalidate()
    }

    override fun onDetachedFromWindow() {
        transition?.cancel(); pressAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
