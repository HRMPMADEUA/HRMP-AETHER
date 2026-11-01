package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Horizontal slide-to-connect control with a separate settings shortcut. */
class SlideConnectControl(context: Context) : View(context) {
    var state: OrbState = OrbState.DISCONNECTED
        set(value) { field = value; invalidate() }
    var onToggle: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var dragging = false
    private var dragX = 0f
    private var thumbX = 0f
    private var settingsTouch = false
    private val density = resources.displayMetrics.density
    private fun d(v: Float) = v * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val gearCenter = width - d(38f)
        val trackRight = gearCenter - d(43f)
        val trackLeft = d(1f)
        val cy = h / 2f
        val radius = h * 0.47f
        track.set(trackLeft, cy - radius, trackRight, cy + radius)

        paint.style = Paint.Style.FILL
        paint.color = if (Palette.dark) 0xFF263449.toInt() else 0xFFECEDEF.toInt()
        canvas.drawRoundRect(track, radius, radius, paint)

        val isConnected = state == OrbState.CONNECTED
        val active = state == OrbState.CONNECTING || state == OrbState.DISCONNECTING
        if (isConnected) {
            paint.color = Palette.withAlpha(Palette.mint, if (Palette.dark) 52 else 38)
            canvas.drawRoundRect(track, radius, radius, paint)
        }

        val thumbRadius = radius * 0.88f
        val minX = track.left + radius
        val maxX = track.right - radius
        val settled = if (isConnected) maxX else minX
        val x = if (dragging && !active) dragX.coerceIn(minX, maxX) else settled
        thumbX = x

        paint.color = if (isConnected) Palette.mint else if (Palette.dark) 0xFFF9FBFF.toInt() else 0xFF050505.toInt()
        canvas.drawCircle(x, cy, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(2.6f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = if (isConnected) 0xFFFFFFFF.toInt() else if (Palette.dark) 0xFF111A29.toInt() else 0xFFFFFFFF.toInt()
        val arrow = Path().apply {
            if (isConnected) {
                moveTo(x + d(7f), cy - d(8f)); lineTo(x - d(2f), cy); lineTo(x + d(7f), cy + d(8f))
                moveTo(x - d(2f), cy); lineTo(x + d(12f), cy)
            } else {
                moveTo(x - d(8f), cy - d(8f)); lineTo(x + d(1f), cy); lineTo(x - d(8f), cy + d(8f))
                moveTo(x - d(12f), cy); lineTo(x + d(1f), cy)
            }
        }
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL

        val label = when (state) {
            OrbState.DISCONNECTED, OrbState.FAILED, OrbState.PERMISSION -> "Slide to turn on"
            OrbState.CONNECTED -> "Slide to turn off"
            OrbState.CONNECTING -> "Connecting…"
            OrbState.DISCONNECTING -> "Disconnecting…"
        }
        paint.color = if (Palette.dark) 0xFFE7EDF6.toInt() else 0xFF25272B.toInt()
        paint.textSize = d(13f)
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, (track.left + track.right) / 2f + if (isConnected) -d(8f) else d(8f), cy + d(4.5f), paint)

        // Separate round settings button at the right, matching Psiphon's layout.
        paint.color = if (Palette.dark) 0xFF111D30.toInt() else 0xFF050505.toInt()
        canvas.drawCircle(gearCenter, cy, radius, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(2.1f)
        paint.strokeCap = Paint.Cap.ROUND
        val gr = d(8.5f)
        canvas.drawCircle(gearCenter, cy, gr, paint)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4.0
            val x1 = gearCenter + kotlin.math.cos(a).toFloat() * d(10f)
            val y1 = cy + kotlin.math.sin(a).toFloat() * d(10f)
            val x2 = gearCenter + kotlin.math.cos(a).toFloat() * d(13f)
            val y2 = cy + kotlin.math.sin(a).toFloat() * d(13f)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cy = height / 2f
        val radius = height * 0.47f
        val gearCenter = width - d(38f)
        val trackRight = gearCenter - d(43f)
        val minX = d(1f) + radius
        val maxX = trackRight - radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == OrbState.CONNECTING || state == OrbState.DISCONNECTING) return false
                if (event.x >= gearCenter - radius - d(7f)) {
                    settingsTouch = true
                    return true
                }
                if (event.x < d(1f) || event.x > trackRight || event.y < cy - radius || event.y > cy + radius) return false
                settingsTouch = false
                dragging = true
                dragX = event.x.coerceIn(minX, maxX)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (settingsTouch) return true
                if (dragging) { dragX = event.x.coerceIn(minX, maxX); invalidate(); return true }
            }
            MotionEvent.ACTION_UP -> {
                if (settingsTouch) {
                    settingsTouch = false
                    if (event.x >= gearCenter - radius - d(7f)) onSettings?.invoke()
                    return true
                }
                if (dragging) {
                    val endX = event.x.coerceIn(minX, maxX)
                    val ratio = (endX - minX) / max(1f, maxX - minX)
                    val shouldToggle = if (state == OrbState.CONNECTED) ratio < 0.30f else ratio > 0.70f
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    if (shouldToggle) performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                settingsTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
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
}
