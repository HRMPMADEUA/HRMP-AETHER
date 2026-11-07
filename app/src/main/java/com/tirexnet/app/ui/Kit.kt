package com.tirexnet.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tirexnet.app.engine.FieldKind
import com.tirexnet.app.engine.FieldSpec
import com.tirexnet.app.engine.ManualProfile
import com.tirexnet.app.engine.ManualProtocol

/**
 * UI-only design system shared by every screen, dialog and sheet.
 * Nothing in here touches profile, engine, DNS, login or backend logic.
 */
object Kit {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val TOAST_TAG = "tirex_status_toast"

    enum class Kind { INFO, SUCCESS, ERROR, LOADING }
    enum class FieldState { NORMAL, FOCUS, ERROR }

    fun lp(ctx: Context, w: Int, h: Int, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(ctx.dpi(left), ctx.dpi(top), ctx.dpi(right), ctx.dpi(bottom)) }

    fun accentFor(kind: Kind): Int = when (kind) {
        Kind.SUCCESS -> Palette.mint
        Kind.ERROR -> Palette.red
        Kind.LOADING -> Palette.amber
        Kind.INFO -> Palette.cyan
    }

    // ------------------------------------------------------------------
    // Status toast (replaces the stock Android Toast while the app is in front)
    // ------------------------------------------------------------------

    fun kindOf(message: String): Kind {
        val m = message.lowercase()
        return when {
            m.endsWith("…") || m.endsWith("...") -> Kind.LOADING
            listOf("could not", "couldn't", "failed", "invalid", "cannot", "can't", "unable", "error").any { m.contains(it) } -> Kind.ERROR
            listOf("saved", "applied", "copied", "added", "removed", "cleared", "duplicated").any { m.contains(it) } -> Kind.SUCCESS
            else -> Kind.INFO
        }
    }

    fun toast(activity: Activity, message: String, kind: Kind = kindOf(message)) {
        // A dialog/sheet has the window focus; an overlay inside the activity would hide behind it.
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        if (host == null || activity.isFinishing || !activity.window.decorView.hasWindowFocus()) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }
        host.findViewWithTag<View>(TOAST_TAG)?.let { host.removeView(it) }
        val accent = accentFor(kind)
        val card = LinearLayout(activity).apply {
            tag = TOAST_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.glass(activity, 18f, Palette.withAlpha(accent, 170), Palette.bg2, Palette.bg1)
            elevation = activity.dp(10f)
            setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(16), activity.dpi(10))
            alpha = 0f
            translationY = activity.dp(24f)
            isClickable = true
        }
        val icon = when (kind) {
            Kind.SUCCESS -> Icon.CHECK
            Kind.ERROR -> Icon.INFO
            Kind.LOADING -> Icon.REFRESH
            Kind.INFO -> Icon.INFO
        }
        card.addView(badge(activity, icon, accent, 32), lp(activity, activity.dpi(32), activity.dpi(32), right = 10))
        card.addView(activity.tv(message, 13f, Palette.text, 1).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        host.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(activity.dpi(16), 0, activity.dpi(16), activity.dpi(96))
        })
        card.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        val hide = Runnable {
            card.animate().alpha(0f).translationY(activity.dp(16f)).setDuration(200)
                .withEndAction { (card.parent as? ViewGroup)?.removeView(card) }.start()
        }
        card.setOnClickListener { card.removeCallbacks(hide); hide.run() }
        card.postDelayed(hide, if (kind == Kind.ERROR) 4600L else 3000L)
    }

    // ------------------------------------------------------------------
    // Small building blocks
    // ------------------------------------------------------------------

    fun badge(ctx: Context, icon: Icon, tint: Int, sizeDp: Int = 40): View {
        val box = FrameLayout(ctx)
        box.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(sizeDp * 0.32f)
            setColor(Palette.withAlpha(tint, 40))
            setStroke(ctx.dpi(1), Palette.withAlpha(tint, 100))
        }
        val s = ctx.dpi((sizeDp * 0.56f).toInt())
        box.addView(IconView(ctx, icon, tint), FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        return box
    }

    fun chip(ctx: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        ctx.tv(label, 12.5f, if (selected) Palette.onPrimary else Palette.text, 1).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dpi(40)
            setPadding(ctx.dpi(14), ctx.dpi(9), ctx.dpi(14), ctx.dpi(9))
            background = if (selected)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.blue, Palette.cyan)).apply { cornerRadius = ctx.dp(20f) }
            else Palette.glass(ctx, 20f)
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            pressable(0.95f)
        }

    fun fieldBg(ctx: Context, state: FieldState): GradientDrawable = when (state) {
        FieldState.NORMAL -> Palette.glass(ctx, 14f, Palette.stroke, Palette.fieldFill, Palette.fieldFill)
        FieldState.FOCUS -> Palette.glass(ctx, 14f, Palette.strokeActive, Palette.fieldFill, Palette.fieldFill)
        FieldState.ERROR -> Palette.glass(ctx, 14f, Palette.red, Palette.fieldFill, Palette.fieldFill)
    }

    fun styleInput(ctx: Context, et: EditText, multiline: Boolean = false) {
        et.setTextColor(Palette.text)
        et.setHintTextColor(Palette.dim)
        et.textSize = 14f
        et.background = fieldBg(ctx, FieldState.NORMAL)
        et.setPadding(ctx.dpi(14), ctx.dpi(if (multiline) 10 else 0), ctx.dpi(14), ctx.dpi(if (multiline) 10 else 0))
        et.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        et.setOnFocusChangeListener { v, focus ->
            if (v.tag != FieldState.ERROR) v.background = fieldBg(ctx, if (focus) FieldState.FOCUS else FieldState.NORMAL)
        }
    }

    /** Card with an icon header. Returns (card, body) — add children to body. */
    fun card(ctx: Context, title: String, icon: Icon, tint: Int = Palette.cyan, subtitle: String? = null): Pair<LinearLayout, LinearLayout> {
        val card = GlassCard(ctx, 22f, pad = 16)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(ctx, icon, tint, 36), lp(ctx, ctx.dpi(36), ctx.dpi(36), right = 12))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.tv(title, 15f, Palette.text, 2))
        if (!subtitle.isNullOrBlank()) t.addView(ctx.tv(subtitle, 11f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 2))
        head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(head, lp(ctx, MATCH, WRAP))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body, lp(ctx, MATCH, WRAP, 4))
        return card to body
    }

    fun noteCard(ctx: Context, text: String, accent: Int = Palette.cyan): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14f)
                setColor(Palette.withAlpha(accent, 26))
                setStroke(ctx.dpi(1), Palette.withAlpha(accent, 80))
            }
        }
        row.addView(IconView(ctx, Icon.INFO, accent), lp(ctx, ctx.dpi(18), ctx.dpi(18), top = 1, right = 10))
        row.addView(ctx.tv(text, 11.5f, Palette.muted, 0).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    /** Label + styled input, used for the few standalone fields outside [FieldForm]. */
    fun labeledInput(ctx: Context, parent: LinearLayout, label: String, configure: EditText.() -> Unit): EditText {
        parent.addView(ctx.tv(label, 11.5f, Palette.muted, 1), lp(ctx, MATCH, WRAP, 12))
        val et = EditText(ctx)
        styleInput(ctx, et)
        et.setSingleLine(true)
        et.configure()
        parent.addView(et, lp(ctx, MATCH, ctx.dpi(50), 6))
        return et
    }

    // ------------------------------------------------------------------
    // Phase 2: settings controls, states, option cards
    // ------------------------------------------------------------------

    fun sectionHeader(ctx: Context, title: String, subtitle: String? = null): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ctx.tv(title.uppercase(), 10f, Palette.cyan, 2).apply { letterSpacing = 0.18f })
        if (!subtitle.isNullOrBlank()) box.addView(ctx.tv(subtitle, 11f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 3))
        return box
    }

    /** Small status pill (ACTIVE / OFFLINE / value tags). */
    fun pill(ctx: Context, text: String, color: Int): TextView =
        ctx.tv(text, 9.5f, color, 2).apply {
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(10), ctx.dpi(4), ctx.dpi(10), ctx.dpi(4))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12f)
                setColor(Palette.withAlpha(color, 34))
                setStroke(ctx.dpi(1), Palette.withAlpha(color, 110))
            }
        }

    /** Modern switch colours. Keeps the platform Switch so existing isChecked logic is untouched. */
    fun styleSwitch(sw: android.widget.Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, Palette.muted))
        sw.trackTintList = android.content.res.ColorStateList(
            states, intArrayOf(Palette.withAlpha(Palette.cyan, 230), Palette.withAlpha(Palette.stroke, 160))
        )
        sw.setTextColor(Palette.text)
    }

    /** Switch inside a rounded row with an optional description. Returns the Switch. */
    fun switchRow(ctx: Context, parent: LinearLayout, label: String, desc: String?, checked: Boolean): android.widget.Switch {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(8), ctx.dpi(10))
            background = Palette.glass(ctx, 16f)
        }
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.tv(label, 13f, Palette.text, 1))
        if (!desc.isNullOrBlank()) t.addView(ctx.tv(desc, 10.5f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 2))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = android.widget.Switch(ctx).apply { isChecked = checked; styleSwitch(this) }
        row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
        row.setOnClickListener { sw.toggle() }
        parent.addView(row, lp(ctx, MATCH, WRAP, 8))
        return sw
    }

    /** Themed dropdown. Returns a normal Spinner so selectedItem / setSelection keep working. */
    fun spinner(ctx: Context, options: List<String>): android.widget.Spinner {
        val sp = android.widget.Spinner(ctx)
        sp.adapter = object : android.widget.ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, options) {
            private fun paint(v: View, dropdown: Boolean): View {
                (v as? TextView)?.apply {
                    setTextColor(Palette.text)
                    textSize = 13.5f
                    setPadding(ctx.dpi(14), ctx.dpi(if (dropdown) 14 else 0), ctx.dpi(14), ctx.dpi(if (dropdown) 14 else 0))
                    gravity = Gravity.CENTER_VERTICAL
                    if (!dropdown) minHeight = ctx.dpi(48)
                }
                return v
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getView(position, convertView, parent), false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getDropDownView(position, convertView, parent), true)
        }
        sp.background = fieldBg(ctx, FieldState.NORMAL)
        sp.setPopupBackgroundDrawable(Palette.glass(ctx, 16f, Palette.stroke, Palette.bg2, Palette.bg1))
        return sp
    }

    /** Collapsible "Advanced Settings" card. Returns (card, body); body is hidden until tapped. */
    fun advancedCard(ctx: Context, subtitle: String): Pair<LinearLayout, LinearLayout> {
        val card = GlassCard(ctx, 22f, stroke = Palette.withAlpha(Palette.violet, 150), pad = 0)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
            isClickable = true
            contentDescription = "Advanced Settings"
            pressable(0.99f)
        }
        head.addView(badge(ctx, Icon.GEAR, Palette.violet, 36), lp(ctx, ctx.dpi(36), ctx.dpi(36), right = 12))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.tv("Advanced Settings", 15f, Palette.text, 2))
        t.addView(ctx.tv(subtitle, 11f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 2))
        head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val chevron = IconView(ctx, Icon.CHEVRON, Palette.violet)
        head.addView(chevron, LinearLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)))
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(16))
            visibility = View.GONE
        }
        card.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 90f else 0f).setDuration(160).start()
        }
        return card to body
    }

    /** Tappable list row for settings pages: icon badge, title, description, optional value pill. */
    fun settingRow(ctx: Context, parent: LinearLayout, icon: Icon, title: String, desc: String, value: String?, tint: Int = Palette.cyan, onClick: () -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(4), ctx.dpi(10), ctx.dpi(4), ctx.dpi(10))
            isClickable = true
            contentDescription = title
            setOnClickListener { onClick() }
            pressable(0.985f)
        }
        row.addView(badge(ctx, icon, tint, 38), lp(ctx, ctx.dpi(38), ctx.dpi(38), right = 12))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.tv(title, 14f, Palette.text, 1))
        t.addView(ctx.tv(desc, 10.5f, Palette.muted, 0).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(ctx, MATCH, WRAP, 2))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (value != null) row.addView(pill(ctx, value, tint), lp(ctx, WRAP, WRAP, left = 8))
        row.addView(IconView(ctx, Icon.CHEVRON, Palette.dim), lp(ctx, ctx.dpi(16), ctx.dpi(16), left = 6))
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private val ENCODED_SCHEMES = setOf(
        "slipnet", "slipnet-enc", "slipnet-bundle-enc", "tirexnet", "vless", "vmess", "trojan", "ss",
        "masterdns", "stormdns", "cottendns", "mhrv", "dnstt", "sni"
    )

    /** True for long encoded share/config URIs (e.g. slipnet://MzB8c29h…) that must never be shown as text. */
    fun looksEncoded(value: String): Boolean {
        val t = value.trim()
        if (t.length < 80 || t.any { it.isWhitespace() }) return false
        return t.substringBefore("://", "").lowercase() in ENCODED_SCHEMES
    }

    fun divider(ctx: Context): View = View(ctx).apply { setBackgroundColor(Palette.withAlpha(Palette.stroke, 70)) }

    /** Selectable option card (radio style) used for mode pickers. */
    fun optionCard(ctx: Context, icon: Icon, title: String, desc: String, selected: Boolean, onClick: () -> Unit): View {
        val tint = if (selected) Palette.cyan else Palette.muted
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
            background = if (selected)
                Palette.glass(ctx, 18f, Palette.strokeActive, Palette.withAlpha(Palette.cyan, 54), Palette.withAlpha(Palette.ice, 18))
            else Palette.glass(ctx, 18f)
            isClickable = true
            contentDescription = title
            setOnClickListener { onClick() }
            pressable(0.98f)
        }
        row.addView(badge(ctx, icon, tint, 40), lp(ctx, ctx.dpi(40), ctx.dpi(40), right = 12))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.tv(title, 14.5f, Palette.text, 2))
        t.addView(ctx.tv(desc, 11f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 2))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(Palette.cyan) else setColor(Color.TRANSPARENT)
                setStroke(ctx.dpi(2), if (selected) Palette.cyan else Palette.stroke)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)))
        return row
    }

    /** Two or three-way segmented selector. [onSelect] gets the chosen value. */
    fun segmented(ctx: Context, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Palette.glass(ctx, 16f)
            setPadding(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4))
        }
        for ((value, label) in options) {
            val on = value == selected
            bar.addView(ctx.tv(label, 12f, if (on) Palette.onPrimary else Palette.muted, 2).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dpi(40)
                setPadding(ctx.dpi(6), ctx.dpi(8), ctx.dpi(6), ctx.dpi(8))
                background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.blue, Palette.cyan)).apply { cornerRadius = ctx.dp(12f) } else null
                isClickable = true
                setOnClickListener { onSelect(value) }
                pressable(0.97f)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return bar
    }

    enum class StateKind { LOADING, EMPTY, ERROR }

    /** One consistent Loading / Empty / Error block for lists and dialogs. */
    fun stateView(ctx: Context, kind: StateKind, title: String, message: String, actionLabel: String? = null, action: (() -> Unit)? = null): View {
        val accent = when (kind) { StateKind.LOADING -> Palette.amber; StateKind.EMPTY -> Palette.cyan; StateKind.ERROR -> Palette.red }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(20), ctx.dpi(22), ctx.dpi(20), ctx.dpi(22))
            background = Palette.glass(ctx, 22f, Palette.withAlpha(accent, 110))
        }
        if (kind == StateKind.LOADING) {
            box.addView(android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }, lp(ctx, ctx.dpi(34), ctx.dpi(34)))
        } else {
            box.addView(badge(ctx, if (kind == StateKind.ERROR) Icon.INFO else Icon.SERVERS, accent, 52), lp(ctx, ctx.dpi(52), ctx.dpi(52)))
        }
        box.addView(ctx.tv(title, 15f, Palette.text, 2).apply { gravity = Gravity.CENTER }, lp(ctx, MATCH, WRAP, 12))
        box.addView(ctx.tv(message, 11.5f, Palette.muted, 0).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }, lp(ctx, MATCH, WRAP, 6))
        if (actionLabel != null && action != null) {
            box.addView(ctx.tv(actionLabel, 14f, Palette.onPrimary, 2).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.blue, Palette.cyan)).apply { cornerRadius = ctx.dp(16f) }
                isClickable = true
                setOnClickListener { action() }
                pressable(0.97f)
            }, lp(ctx, MATCH, ctx.dpi(46), 14))
        }
        return box
    }

    // ------------------------------------------------------------------
    // Bottom sheet
    // ------------------------------------------------------------------

    private class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }

    class Sheet(private val activity: Activity, title: CharSequence, subtitle: CharSequence? = null, leading: View? = null) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        private val root = FrameLayout(activity)
        private val scrim = View(activity)
        private val sheet = LinearLayout(activity)
        private val list = LinearLayout(activity)
        private var closing = false

        init {
            scrim.setBackgroundColor(Color.argb(if (Palette.dark) 170 else 110, 2, 8, 18))
            scrim.setOnClickListener { close() }
            root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

            val r = activity.dp(28f)
            sheet.orientation = LinearLayout.VERTICAL
            sheet.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.bg2, Palette.bg1)).apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(activity.dpi(1), Palette.stroke)
            }
            sheet.elevation = activity.dp(16f)
            sheet.setPadding(activity.dpi(16), activity.dpi(10), activity.dpi(16), activity.dpi(22))
            sheet.isClickable = true // swallow taps so they never reach the scrim

            val handle = View(activity).apply {
                background = GradientDrawable().apply { cornerRadius = activity.dp(3f); setColor(Palette.withAlpha(Palette.ice, 90)) }
            }
            sheet.addView(handle, LinearLayout.LayoutParams(activity.dpi(40), activity.dpi(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = activity.dpi(14)
            })

            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (leading != null) head.addView(leading, lp(activity, activity.dpi(44), activity.dpi(44), right = 12))
            val t = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            t.addView(activity.tv(title, 17f, Palette.text, 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (!subtitle.isNullOrBlank()) t.addView(activity.tv(subtitle, 11.5f, Palette.muted, 0).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, lp(activity, MATCH, WRAP, 3))
            head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            sheet.addView(head, lp(activity, MATCH, WRAP, bottom = 12))

            val divider = View(activity).apply { setBackgroundColor(Palette.withAlpha(Palette.stroke, 90)) }
            sheet.addView(divider, LinearLayout.LayoutParams(MATCH, activity.dpi(1)))

            list.orientation = LinearLayout.VERTICAL
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.68f).toInt()
            val scroll = MaxHeightScroll(activity, maxH).apply { isVerticalScrollBarEnabled = false; addView(list) }
            sheet.addView(scroll, lp(activity, MATCH, WRAP, 8))

            root.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            sheet.visibility = View.INVISIBLE
        }

        /** One tappable operation: icon badge, title, short description. */
        fun action(icon: Icon, title: String, desc: String, danger: Boolean = false, onClick: () -> Unit): Sheet {
            val tint = if (danger) Palette.red else Palette.cyan
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dpi(10), activity.dpi(11), activity.dpi(10), activity.dpi(11))
                background = Palette.glass(
                    activity, 18f,
                    if (danger) Palette.withAlpha(Palette.red, 90) else Palette.stroke,
                    if (danger) Palette.withAlpha(Palette.red, 26) else Palette.glassTop,
                    if (danger) Palette.withAlpha(Palette.red, 14) else Palette.glassBottom
                )
                isClickable = true
                contentDescription = title
                setOnClickListener { closeThen(onClick) }
                pressable(0.98f)
            }
            row.addView(badge(activity, icon, tint, 42), lp(activity, activity.dpi(42), activity.dpi(42), right = 12))
            val t = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            t.addView(activity.tv(title, 15f, if (danger) Palette.red else Palette.text, 2))
            t.addView(activity.tv(desc, 11.5f, Palette.muted, 0).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(activity, MATCH, WRAP, 2))
            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(IconView(activity, Icon.CHEVRON, Palette.dim), LinearLayout.LayoutParams(activity.dpi(18), activity.dpi(18)))
            list.addView(row, lp(activity, MATCH, WRAP, 8))
            return this
        }

        /** Adds arbitrary content (notes, previews) above or between action rows. */
        fun content(view: View, topDp: Int = 8): Sheet {
            list.addView(view, lp(activity, MATCH, WRAP, topDp))
            return this
        }

        fun show() {
            if (activity.isFinishing || activity.isDestroyed) return
            dialog.setContentView(root)
            dialog.window?.apply {
                setLayout(MATCH, MATCH)
                setGravity(Gravity.BOTTOM)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0f)
                setWindowAnimations(0)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            dialog.setOnCancelListener { /* back button: dismiss immediately */ }
            dialog.show()
            scrim.alpha = 0f
            root.post {
                sheet.translationY = sheet.height.toFloat()
                sheet.visibility = View.VISIBLE
                scrim.animate().alpha(1f).setDuration(220).start()
                sheet.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }

        fun close() = closeThen(null)

        private fun closeThen(after: (() -> Unit)?) {
            if (closing) return
            closing = true
            scrim.animate().alpha(0f).setDuration(180).start()
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(190).withEndAction {
                runCatching { dialog.dismiss() }
                after?.invoke()
            }.start()
        }
    }

    // ------------------------------------------------------------------
    // Unified field form — used by Manual Config AND Decoded Config / Edit Profile
    // ------------------------------------------------------------------

    /**
     * Renders every field of [protocol] with one visual language: grouped cards, icons,
     * modern inputs and chip selectors. Fields that only specialists touch live in a collapsible
     * "Advanced Settings" card; nothing is removed — all fields stay in [values] and are collected.
     *
     * [values] is updated live. [fillDefaults] seeds blank visible fields with their spec default
     * (the decoded-editor behaviour); the manual form pre-seeds defaults itself.
     */
    class FieldForm(
        private val ctx: Context,
        private val protocol: ManualProtocol,
        val values: MutableMap<String, String>,
        private val fillDefaults: Boolean = false
    ) {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val inputs = HashMap<String, EditText>()
        private val shown = mutableListOf<FieldSpec>()
        private var advancedOpen = false

        init { rebuild() }

        private fun isAdvanced(s: FieldSpec): Boolean =
            !s.required && (s.key in ADVANCED_CATEGORY || s.key.startsWith("awg") || s.section == "Performance")

        private fun advancedCategory(s: FieldSpec): String =
            ADVANCED_CATEGORY[s.key] ?: if (s.key.startsWith("awg")) "Obfuscation" else if (s.section == "Performance") "Performance" else "Other"

        /** Values of the fields currently on screen (trimmed like the old editors did). */
        fun collect(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (s in shown) {
                val v = values[s.key].orEmpty()
                out[s.key] = if (s.kind == FieldKind.SECRET || s.kind == FieldKind.MULTILINE) v else v.trim()
            }
            return out
        }

        fun clearErrors() {
            for ((_, et) in inputs) { et.tag = null; et.background = fieldBg(ctx, FieldState.NORMAL) }
        }

        fun markError(key: String) {
            val spec = ManualProfile.fields(protocol).firstOrNull { it.key == key }
            if (spec != null && isAdvanced(spec) && !advancedOpen) { advancedOpen = true; rebuild() }
            inputs[key]?.let { it.tag = FieldState.ERROR; it.background = fieldBg(ctx, FieldState.ERROR); it.requestFocus() }
        }

        fun rebuild() {
            root.removeAllViews()
            inputs.clear()
            shown.clear()
            val specs = ManualProfile.fields(protocol).filter { it.showWhen == null || it.showWhen.invoke(values) }
            if (fillDefaults) specs.forEach { s -> if (values[s.key].isNullOrBlank() && s.default.isNotBlank()) values[s.key] = s.default }
            shown.addAll(specs)

            // ----- General settings -----
            val general = specs.filterNot { isAdvanced(it) }
            var cur: LinearLayout? = null
            var title = ""
            for (s in general) {
                if (cur == null || (s.section.isNotBlank() && s.section != title)) {
                    title = s.section.ifBlank { protocol.label }
                    val (c, body) = card(ctx, title, iconForSection(title))
                    root.addView(c, lp(ctx, MATCH, WRAP, 12))
                    cur = body
                }
                addField(cur!!, s)
            }

            // ----- Advanced settings (collapsed by default) -----
            val advanced = specs.filter { isAdvanced(it) }
            if (advanced.isNotEmpty()) root.addView(advancedCard(advanced), lp(ctx, MATCH, WRAP, 14))
        }

        private fun iconForSection(t: String): Icon = when (t) {
            "Profile" -> Icon.PROFILE
            "Server" -> Icon.SERVERS
            "DNS" -> Icon.GLOBE
            "Performance" -> Icon.BOLT
            "Security", "TLS" -> Icon.SHIELD
            else -> if (t == protocol.label) Icon.SERVERS else Icon.GEAR
        }

        private fun advancedCard(advanced: List<FieldSpec>): View {
            val card = GlassCard(ctx, 22f, stroke = Palette.withAlpha(Palette.violet, 150), pad = 0)
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
                isClickable = true
                contentDescription = "Advanced Settings"
                pressable(0.99f)
            }
            head.addView(badge(ctx, Icon.GEAR, Palette.violet, 36), lp(ctx, ctx.dpi(36), ctx.dpi(36), right = 12))
            val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            t.addView(ctx.tv("Advanced Settings", 15f, Palette.text, 2))
            t.addView(ctx.tv("${advanced.size} specialist option${if (advanced.size == 1) "" else "s"} • ${protocol.label}", 11f, Palette.muted, 0), lp(ctx, MATCH, WRAP, 2))
            head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            val chevron = IconView(ctx, Icon.CHEVRON, Palette.violet)
            chevron.rotation = if (advancedOpen) 90f else 0f
            head.addView(chevron, LinearLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)))
            card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(16))
                visibility = if (advancedOpen) View.VISIBLE else View.GONE
            }
            advanced.groupBy { advancedCategory(it) }.forEach { (cat, specs) ->
                content.addView(ctx.tv(cat.uppercase(), 9f, Palette.violet, 1).apply { letterSpacing = 0.16f }, lp(ctx, MATCH, WRAP, 14))
                specs.forEach { addField(content, it) }
            }
            content.addView(ctx.tv("Back to General settings", 13f, Palette.text, 1).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dpi(44)
                background = Palette.glass(ctx, 16f)
                isClickable = true
                setOnClickListener { advancedOpen = false; rebuild() }
                pressable(0.97f)
            }, lp(ctx, MATCH, ctx.dpi(44), 18))
            card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))

            head.setOnClickListener {
                advancedOpen = !advancedOpen
                content.visibility = if (advancedOpen) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (advancedOpen) 90f else 0f).setDuration(160).start()
            }
            return card
        }

        private fun addField(parent: LinearLayout, f: FieldSpec) {
            val label = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            label.addView(ctx.tv(f.label, 11.5f, Palette.muted, 1))
            if (f.required) label.addView(ctx.tv(" *", 12f, Palette.amber, 2))
            parent.addView(label, lp(ctx, MATCH, WRAP, 12))

            if (f.kind == FieldKind.CHOICE) {
                val options = f.choices.toMutableList()
                val current = values[f.key].orEmpty()
                if (current.isNotBlank() && current !in options) options.add(0, current) // keep non-standard value
                val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                hs.addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
                for (c in options) {
                    row.addView(chip(ctx, if (c.isEmpty()) "none" else c, c == current) {
                        values[f.key] = c
                        rebuild() // dependent fields (TLS, Reality, transport) appear/disappear
                    }, lp(ctx, WRAP, WRAP, 0, 0, 0, 8))
                }
                parent.addView(hs, lp(ctx, MATCH, WRAP, 6))
                return
            }

            val current = values[f.key].orEmpty()
            if (Kit.looksEncoded(current)) {
                // Never show an encoded config as text. The value stays in [values] untouched.
                parent.addView(noteCard(ctx, "Encoded configuration hidden (${current.length} characters). It is kept unchanged when you save.", Palette.violet), lp(ctx, MATCH, WRAP, 6))
                return
            }
            val multi = f.kind == FieldKind.MULTILINE
            val et = EditText(ctx).apply {
                hint = f.hint
                styleInput(ctx, this, multi)
                setSingleLine(!multi)
                if (multi) { minLines = 3; maxLines = 6; gravity = Gravity.TOP or Gravity.START }
                inputType = when (f.kind) {
                    FieldKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                    FieldKind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    FieldKind.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                imeOptions = EditorInfo.IME_ACTION_NEXT
                contentDescription = f.label
                setText(values[f.key].orEmpty())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) { values[f.key] = s?.toString().orEmpty() }
                })
            }
            inputs[f.key] = et
            parent.addView(et, lp(ctx, MATCH, ctx.dpi(if (multi) 116 else 50), 6))
        }

        companion object {
            /** Specialist options. Required fields are never advanced (see [isAdvanced]). */
            private val ADVANCED_CATEGORY = mapOf(
                "fp" to "TLS & Security", "alpn" to "TLS & Security", "flow" to "TLS & Security",
                "presharedKey" to "TLS & Security", "sniHosts" to "TLS & Security",
                "hostHeader" to "Transport",
                "mtu" to "Performance", "keepalive" to "Performance", "workers" to "Performance", "logLevel" to "Performance",
                "listenPort" to "Local Network", "resolverTransport" to "Local Network", "tunnelMode" to "Local Network"
            )
        }
    }
}
