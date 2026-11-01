package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * TIREXNET's Psiphon-inspired palette: clear navy/blue surfaces, bright blue
 * primary actions and a restrained green/orange status system. The theme stays
 * centralized so changing it never leaves individual screens with old colors.
 */
object Palette {
    @Volatile var dark: Boolean = true

    private fun pick(d: Int, l: Int) = if (dark) d else l

    val bg0 get() = pick(Color.rgb(7, 17, 32), Color.rgb(245, 248, 252))
    val bg1 get() = pick(Color.rgb(10, 29, 52), Color.rgb(235, 242, 249))
    val bg2 get() = pick(Color.rgb(17, 43, 72), Color.rgb(223, 234, 245))

    // Primary action and interactive accents.
    val cyan get() = pick(Color.rgb(55, 151, 244), Color.rgb(25, 111, 205))
    val ice get() = pick(Color.rgb(157, 207, 250), Color.rgb(49, 112, 177))
    val blue get() = pick(Color.rgb(39, 123, 225), Color.rgb(30, 105, 205))
    val violet get() = pick(Color.rgb(105, 126, 205), Color.rgb(93, 112, 184))

    // Connection/status colors.
    val mint get() = pick(Color.rgb(52, 202, 151), Color.rgb(22, 158, 111))
    val amber get() = pick(Color.rgb(255, 164, 76), Color.rgb(209, 119, 30))
    val red get() = pick(Color.rgb(244, 105, 119), Color.rgb(207, 61, 78))

    val text get() = pick(Color.rgb(244, 248, 255), Color.rgb(21, 39, 59))
    val muted get() = pick(Color.rgb(169, 188, 210), Color.rgb(83, 105, 129))
    val dim get() = pick(Color.rgb(111, 137, 166), Color.rgb(126, 147, 168))

    val glassTop get() = pick(Color.argb(48, 86, 151, 215), Color.argb(246, 255, 255, 255))
    val glassBottom get() = pick(Color.argb(24, 28, 72, 122), Color.argb(220, 235, 243, 250))
    val stroke get() = pick(Color.argb(76, 113, 165, 216), Color.argb(94, 99, 139, 178))
    val strokeActive get() = pick(Color.argb(220, 58, 151, 242), Color.argb(225, 34, 119, 214))
    val fieldFill get() = pick(Color.argb(92, 4, 15, 29), Color.argb(255, 255, 255, 255))
    val onPrimary get() = Color.WHITE

    fun glass(
        ctx: Context,
        radiusDp: Float,
        strokeColor: Int = stroke,
        top: Int = glassTop,
        bottom: Int = glassBottom
    ): GradientDrawable {
        val density = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusDp * density
            setStroke(density.toInt().coerceAtLeast(1), strokeColor)
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
