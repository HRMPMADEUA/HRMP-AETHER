package com.tirexnet.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class GlassNavBar(ctx: Context) : LinearLayout(ctx) {

    private data class Tab(
        val icon: Icon,
        val title: String
    )

    private val tabs = listOf(
        Tab(Icon.HOME, "Home"),
        Tab(Icon.SERVERS, "Servers"),
        Tab(Icon.STATS, "Stats"),
        Tab(Icon.PROFILE, "Profile")
    )

    private val cells = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<IconView>()
    private val labels = mutableListOf<TextView>()

    private var selectedIndex = -1

    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        setPadding(
            ctx.dpi(7),
            ctx.dpi(7),
            ctx.dpi(7),
            ctx.dpi(7)
        )

        background = Palette.glass(
            ctx,
            22f,
            Palette.stroke
        )

        clipToPadding = false
        clipChildren = false

        tabs.forEachIndexed { index, tab ->

            val cell = LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER

                isClickable = true
                isFocusable = true

                setPadding(
                    ctx.dpi(2),
                    ctx.dpi(5),
                    ctx.dpi(2),
                    ctx.dpi(4)
                )

                setOnClickListener {
                    select(index)
                    onSelect?.invoke(index)
                }

                pressable(0.97f)
            }

            val iconView = IconView(
                ctx,
                tab.icon,
                Palette.muted,
                false
            )

            cell.addView(
                iconView,
                LayoutParams(
                    ctx.dpi(23),
                    ctx.dpi(23)
                )
            )

            val label = ctx.tv(
                tab.title,
                9f,
                Palette.muted,
                1
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }

            cell.addView(
                label,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                cell,
                LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
            )

            cells.add(cell)
            iconViews.add(iconView)
            labels.add(label)
        }

        select(0, false)
    }

    fun select(
        index: Int,
        animate: Boolean = true
    ) {
        if (tabs.isEmpty()) return

        val target = index.coerceIn(
            0,
            tabs.lastIndex
        )

        val changed = selectedIndex != target

        selectedIndex = target

        tabs.forEachIndexed { i, tab ->

            val active = i == target
            val cell = cells[i]

            cell.background = if (active) {
                Palette.glass(
                    context,
                    16f,
                    Palette.strokeActive,
                    Color.argb(35, 0, 224, 255),
                    Color.argb(18, 48, 118, 255)
                )
            } else {
                null
            }

            iconViews[i].set(
                tab.icon,
                if (active) {
                    Palette.cyan
                } else {
                    Palette.muted
                }
            )

            labels[i].setTextColor(
                if (active) {
                    Palette.text
                } else {
                    Palette.muted
                }
            )

            labels[i].typeface = Typeface.create(
                if (active) {
                    "sans-serif-medium"
                } else {
                    "sans-serif"
                },
                Typeface.NORMAL
            )

            if (animate && changed) {
                cell.animate()
                    .scaleX(if (active) 1f else 0.97f)
                    .scaleY(if (active) 1f else 0.97f)
                    .setDuration(160)
                    .start()
            } else {
                cell.scaleX = if (active) 1f else 0.97f
                cell.scaleY = if (active) 1f else 0.97f
            }
        }
    }
}
