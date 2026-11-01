package com.tirexnet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED, PERMISSION }

/** A soft, Psiphon-inspired gradient globe used as the main connection visual. */
class ConnectOrb(ctx: Context) : View(ctx) {
    var onToggle: (() -> Unit)? = null

    var state: OrbState = OrbState.DISCONNECTED
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var pulse = 0f
    private var visibleOnWindow = true

    init {
        contentDescription = "Connection status"
        isFocusable = true
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun colors(): IntArray = when (state) {
        OrbState.CONNECTED -> intArrayOf(Color.rgb(159, 213, 204), Color.rgb(194, 213, 230), Color.rgb(181, 190, 222))
        OrbState.CONNECTING -> intArrayOf(Color.rgb(167, 193, 228), Color.rgb(224, 199, 204), Color.rgb(246, 183, 126))
        OrbState.DISCONNECTING -> intArrayOf(Color.rgb(179, 195, 225), Color.rgb(218, 202, 211), Color.rgb(239, 190, 150))
        OrbState.FAILED, OrbState.PERMISSION -> intArrayOf(Color.rgb(194, 184, 207), Color.rgb(228, 188, 182), Color.rgb(238, 169, 151))
        OrbState.DISCONNECTED -> intArrayOf(Color.rgb(177, 190, 222), Color.rgb(218, 202, 210), Color.rgb(247, 181, 119))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val radius = side * 0.405f * pressScale
        val cx = width / 2f
        val cy = height / 2f
        val breathe = 1f + 0.012f * sin(pulse * Math.PI * 2.0).toFloat()

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(breathe, breathe)

        // Warm/cool ambient halo, deliberately very soft on the light theme.
        paint.shader = RadialGradient(
            0f, radius * 0.13f, radius * 1.8f,
            intArrayOf(Color.argb(40, 242, 174, 113), Color.argb(20, 153, 181, 226), Color.TRANSPARENT),
            floatArrayOf(0.25f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, 0f, radius * 1.8f, paint)

        // Main pastel sphere: cool lavender at the top-right, warm orange at the lower-left.
        val stop = colors()
        paint.shader = RadialGradient(
            radius * 0.50f, -radius * 0.54f, radius * 1.95f,
            intArrayOf(stop[0], stop[1], stop[2], Color.rgb(238, 206, 185)),
            floatArrayOf(0f, 0.47f, 0.79f, 1f), Shader.TileMode.CLAMP
        )
        oval.set(-radius, -radius, radius, radius)
        canvas.drawOval(oval, paint)

        // Very faint edge light gives the sphere depth without the old technical/radar rings.
        paint.shader = RadialGradient(
            -radius * 0.30f, -radius * 0.38f, radius * 1.25f,
            intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(oval, paint)

        // Connection activity is communicated through a subtle rim rather than a large power glyph.
        if (state == OrbState.CONNECTING || state == OrbState.DISCONNECTING) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(2.2f)
            paint.color = Color.argb(100, 54, 125, 204)
            canvas.drawOval(oval, paint)
        } else if (state == OrbState.CONNECTED) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(2f)
            paint.color = Color.argb(105, 35, 159, 120)
            canvas.drawOval(oval, paint)
        }

        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.FILL

        if (isAttachedToWindow && visibleOnWindow) postInvalidateDelayed(45L)
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 150L
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val radius = min(width, height) * 0.45f
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val inside = dx * dx + dy * dy <= radius * radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePress(0.97f)
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
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.invoke()
        return true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visibleOnWindow = visibility == VISIBLE
        if (visibleOnWindow) invalidate()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
