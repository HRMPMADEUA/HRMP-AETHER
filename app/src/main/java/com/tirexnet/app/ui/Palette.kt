package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

object Palette {
    val bg0 = Color.rgb(2, 5, 14)
    val bg1 = Color.rgb(5, 11, 28)
    val bg2 = Color.rgb(10, 20, 48)
    val cyan = Color.rgb(0, 224, 255)
    val ice = Color.rgb(160, 222, 255)
    val violet = Color.rgb(142, 96, 255)
    val blue = Color.rgb(48, 118, 255)
    val mint = Color.rgb(0, 238, 190)
    val amber = Color.rgb(255, 184, 77)
    val red = Color.rgb(255, 92, 120)
    val text = Color.rgb(238, 245, 255)
    val muted = Color.rgb(138, 157, 190)
    val dim = Color.rgb(86, 104, 138)
    val glassTop = Color.argb(52, 150, 200, 255)
    val glassBottom = Color.argb(16, 90, 120, 230)
    val stroke = Color.argb(64, 130, 190, 255)
    val strokeActive = Color.argb(190, 0, 224, 255)

    fun glass(
        ctx: Context,
        radiusDp: Float,
        strokeColor: Int = stroke,
        top: Int = glassTop,
        bottom: Int = glassBottom
    ): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusDp * d
            setStroke(d.toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun lerp(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.argb(
            m(Color.alpha(a), Color.alpha(b)), m(Color.red(a), Color.red(b)),
            m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b))
        )
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dpf(v: Float): Float = context.dp(v)

/** Tiny press-scale feedback. Never consumes the touch, so clicks keep working. */
@Suppress("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.96f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        false
    }
}

/** weight: 0 regular, 1 medium, 2 bold, 3 light, 4 monospace */
fun Context.tv(value: CharSequence, sp: Float, color: Int, weight: Int = 0): TextView = TextView(this).apply {
    text = value
    textSize = sp
    setTextColor(color)
    includeFontPadding = false
    typeface = when (weight) {
        1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        2 -> Typeface.create("sans-serif", Typeface.BOLD)
        3 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        4 -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }
}
