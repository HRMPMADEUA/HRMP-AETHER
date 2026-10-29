package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

object Palette {
    /** Single source of truth for the theme. Changed only by MainActivity.applyTheme(), which re-renders. */
    @Volatile var dark: Boolean = true

    private fun pick(d: Int, l: Int) = if (dark) d else l

    val bg0 get() = pick(Color.rgb(2, 5, 14), Color.rgb(244, 247, 253))
    val bg1 get() = pick(Color.rgb(5, 11, 28), Color.rgb(228, 236, 250))
    val bg2 get() = pick(Color.rgb(10, 20, 48), Color.rgb(214, 226, 247))
    val cyan get() = pick(Color.rgb(0, 224, 255), Color.rgb(0, 140, 190))
    val ice get() = pick(Color.rgb(160, 222, 255), Color.rgb(40, 96, 160))
    val violet get() = pick(Color.rgb(142, 96, 255), Color.rgb(104, 60, 220))
    val blue get() = pick(Color.rgb(48, 118, 255), Color.rgb(30, 90, 220))
    val mint get() = pick(Color.rgb(0, 238, 190), Color.rgb(0, 150, 118))
    val amber get() = pick(Color.rgb(255, 184, 77), Color.rgb(200, 120, 0))
    val red get() = pick(Color.rgb(255, 92, 120), Color.rgb(212, 40, 72))
    val text get() = pick(Color.rgb(238, 245, 255), Color.rgb(14, 24, 48))
    val muted get() = pick(Color.rgb(138, 157, 190), Color.rgb(84, 100, 130))
    val dim get() = pick(Color.rgb(86, 104, 138), Color.rgb(128, 142, 168))
    val glassTop get() = pick(Color.argb(52, 150, 200, 255), Color.argb(235, 255, 255, 255))
    val glassBottom get() = pick(Color.argb(16, 90, 120, 230), Color.argb(200, 238, 244, 255))
    val stroke get() = pick(Color.argb(64, 130, 190, 255), Color.argb(90, 70, 110, 180))
    val strokeActive get() = pick(Color.argb(190, 0, 224, 255), Color.argb(220, 0, 140, 190))
    /** Fill behind text inputs. */
    val fieldFill get() = pick(Color.argb(70, 6, 14, 34), Color.argb(255, 255, 255, 255))
    /** Text colour on top of the cyan->mint primary button. */
    val onPrimary get() = Color.rgb(2, 10, 24)

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
