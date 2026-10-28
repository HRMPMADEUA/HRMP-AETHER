package com.tirexnet.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tirexnet.app.engine.ConfigDetector
import com.tirexnet.app.engine.ConfigEngine
import com.tirexnet.app.engine.WgTunnelEngine
import com.tirexnet.app.engine.XrayVpnService
import com.tirexnet.app.ui.AmbientBackground
import com.tirexnet.app.ui.BrandMark
import com.tirexnet.app.ui.ConnectOrb
import com.tirexnet.app.ui.EmptyArt
import com.tirexnet.app.ui.GlassCard
import com.tirexnet.app.ui.GlassNavBar
import com.tirexnet.app.ui.Icon
import com.tirexnet.app.ui.IconView
import com.tirexnet.app.ui.LinearMeter
import com.tirexnet.app.ui.OrbState
import com.tirexnet.app.ui.Palette
import com.tirexnet.app.ui.SkeletonView
import com.tirexnet.app.ui.UsageRing
import com.tirexnet.app.ui.dp
import com.tirexnet.app.ui.dpi
import com.tirexnet.app.ui.pressable
import com.tirexnet.app.ui.tv
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class ServerItem(val name: String, val raw: String, val engine: ConfigEngine, val host: String, val port: String)
    private data class Endpoint(val name: String, val host: String, val port: String)
    private enum class ServersState { IDLE, LOADING, ERROR, READY }

    private val api = "https://hrmp.hrmpdownloader.kdns.fr"
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tirexnet", MODE_PRIVATE) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---- session / connection state
    private var token = ""
    private var pendingRaw = ""
    private var pendingEngine = ConfigEngine.UNKNOWN
    private var state = OrbState.DISCONNECTED
    private var statusMessage = ""
    private var connectedAtRt = 0L
    private var resumed = false
    private var currentTab = 0
    private val shownTabs = HashSet<Int>()

    // ---- account data (values come only from the backend)
    private var accUser = ""
    private var accStatus = ""
    private var accPanelUser = ""
    private var usedBytes = -1L
    private var limitBytes = 0L
    private var expireText = ""
    private var accountError: String? = null
    private var shownUsed = 0L

    // ---- servers
    private var servers: List<ServerItem> = emptyList()
    private var serversState = ServersState.IDLE
    private var serversError = ""
    private var serverQuery = ""
    private var settingConfig = false

    // ---- views (rebuilt by render())
    private var tabViews: List<View> = emptyList()
    private var contentFrame: FrameLayout? = null
    private var nav: GlassNavBar? = null
    private var orb: ConnectOrb? = null
    private var statusTitle: TextView? = null
    private var statusDesc: TextView? = null
    private var timerView: TextView? = null
    private var chipText: TextView? = null
    private var chipDot: View? = null
    private var homeServerName: TextView? = null
    private var homeServerMeta: TextView? = null
    private var homeAccName: TextView? = null
    private var homeAccStatus: TextView? = null
    private var homeExpiry: TextView? = null
    private var homeUsageText: TextView? = null
    private var homeUsageMeter: LinearMeter? = null
    private var statsRing: UsageRing? = null
    private var statsStatus: TextView? = null
    private var statsDuration: TextView? = null
    private var statsEngine: TextView? = null
    private var statsExpiry: TextView? = null
    private var statsLog: TextView? = null
    private var profAvatar: TextView? = null
    private var profName: TextView? = null
    private var profStatus: TextView? = null
    private var profPanel: TextView? = null
    private var profExpiry: TextView? = null
    private var profUsage: TextView? = null
    private var serverListBox: LinearLayout? = null
    private var editorBox: LinearLayout? = null
    private var configInput: EditText? = null
    private var editorEngine: TextView? = null
    private var settingsAnchor: View? = null
    private var profileScroll: ScrollView? = null
    private var usernameInput: EditText? = null
    private var passwordInput: EditText? = null
    private var loginButton: TextView? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(XrayVpnService.EXTRA_MESSAGE) ?: return
            applyStatus(message, intent.getBooleanExtra(XrayVpnService.EXTRA_ACTIVE, false))
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state == OrbState.CONNECTED && resumed) {
                val t = durationText()
                timerView?.text = t
                statsDuration?.text = t
                handler.postDelayed(this, 1000)
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        token = prefs.getString("token", "") ?: ""
        statusMessage = prefs.getString("connection_message", "") ?: ""

        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        reconcileState()
        render()
        if (token.isNotBlank()) loadAccount()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reconcileState()
        updateConnectionUi()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (token.isNotBlank() && currentTab != 0) showTab(0) else super.onBackPressed()
    }

    /** The only source of truth for "connected" is an engine that confirmed the tunnel. */
    private fun reconcileState() {
        val real = XrayVpnService.isActive || WgTunnelEngine.isActive
        if (real && state != OrbState.CONNECTED) {
            applyStatus(statusMessage.ifBlank { "Connected." }, true)
        } else if (!real && state == OrbState.CONNECTED) {
            applyStatus("Disconnected.", false)
        } else if (!real && prefs.getBoolean("connection_active", false)) {
            prefs.edit().putBoolean("connection_active", false).putString("active_engine", "").apply()
        }
    }

    // =====================================================================
    // Render
    // =====================================================================

    private fun render() {
        shownTabs.clear()
        val rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(Palette.bg0)
        rootFrame.addView(AmbientBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootFrame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(buildHeader(), lp(MATCH, WRAP))

        val content = FrameLayout(this)
        contentFrame = content
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (token.isBlank()) {
            nav = null
            tabViews = emptyList()
            content.addView(buildLogin(), FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            tabViews = listOf(buildHomeTab(), buildServersTab(), buildStatsTab(), buildProfileTab())
            for (v in tabViews) content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            val bar = GlassNavBar(this)
            bar.onSelect = { showTab(it) }
            nav = bar
            column.addView(bar, lp(MATCH, dpi(74)))
            showTab(currentTab.coerceIn(0, 3), false)
        }

        setContentView(rootFrame)
        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                l = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                t = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                r = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                b = insets.systemWindowInsetBottom
            }
            column.setPadding(l, t, r, b)
            insets
        }
        rootFrame.requestApplyInsets()

        bindAccount()
        bindServerSummary()
        updateConnectionUi()
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(10), dpi(16), dpi(6))
        }
        row.addView(BrandMark(this), lp(dpi(44), dpi(44)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), 0, 0, 0)
        }
        titles.addView(tv("TIREXNET", 18f, Palette.text, 2).apply { letterSpacing = 0.16f })
        titles.addView(tv("SECURE  •  FAST  •  FREE", 8f, Palette.muted, 1).apply { letterSpacing = 0.22f }, lp(WRAP, WRAP, 3))
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (token.isNotBlank()) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(10), dpi(6), dpi(11), dpi(6))
                background = Palette.glass(this@MainActivity, 16f)
            }
            val dot = View(this)
            chipDot = dot
            chip.addView(dot, lp(dpi(7), dpi(7)))
            val t = tv("OFFLINE", 9f, Palette.muted, 2).apply { letterSpacing = 0.12f }
            chipText = t
            chip.addView(t, lp(WRAP, WRAP, 0, 0, 7))
            row.addView(chip, lp(WRAP, WRAP, 0, 0, 0, 8))
            row.addView(roundButton(Icon.PROFILE) { showTab(3) }, lp(dpi(38), dpi(38), 0, 0, 0, 6))
            row.addView(roundButton(Icon.GEAR) { showTab(3); scrollToSettings() }, lp(dpi(38), dpi(38)))
        }
        return row
    }

    private fun roundButton(icon: Icon, onClick: () -> Unit): View {
        val f = FrameLayout(this).apply {
            background = Palette.glass(this@MainActivity, 19f)
            isClickable = true
            contentDescription = icon.name
            setOnClickListener { onClick() }
            pressable(0.92f)
        }
        f.addView(IconView(this, icon, Palette.ice), FrameLayout.LayoutParams(dpi(38), dpi(38)))
        return f
    }

    // =====================================================================
    // Tabs
    // =====================================================================

    private fun tabScroll(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(6), dpi(20), dpi(22))
        }
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv to col
    }

    private fun showTab(index: Int, animate: Boolean = true) {
        if (tabViews.isEmpty()) return
        val dir = if (index >= currentTab) 1 else -1
        currentTab = index
        tabViews.forEachIndexed { i, v ->
            if (i == index) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.translationX = dp(22f) * dir
                    v.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                } else { v.alpha = 1f; v.translationX = 0f }
                if (shownTabs.add(index)) {
                    val sv = v as? ScrollView
                    (sv?.getChildAt(0) as? ViewGroup)?.let { staggerIn(it) }
                }
            } else {
                v.animate().cancel()
                v.visibility = View.GONE
            }
        }
        nav?.select(index, animate)
        if (index == 1) ensureServersLoaded()
    }

    private fun staggerIn(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(16f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 50L).setDuration(340)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---------------------------------------------------------------- Home
    private fun buildHomeTab(): View {
        val (sv, col) = tabScroll()
        val orbSize = minOf(dpi(290), resources.displayMetrics.widthPixels - dpi(48))
        val o = ConnectOrb(this)
        o.contentDescription = "Connect or disconnect"
        o.onToggle = { toggleConnection() }
        orb = o
        val orbHolder = FrameLayout(this)
        orbHolder.addView(o, FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER))
        col.addView(orbHolder, lp(MATCH, orbSize, 4))

        val title = tv("Disconnected", 26f, Palette.muted, 2).apply { gravity = Gravity.CENTER; letterSpacing = 0.04f }
        statusTitle = title
        col.addView(title, lp(MATCH, WRAP, 6))

        val timer = tv("00:00:00", 17f, Palette.cyan, 4).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f; visibility = View.GONE }
        timerView = timer
        col.addView(timer, lp(MATCH, WRAP, 6))

        val desc = tv("", 12.5f, Palette.muted, 0).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        statusDesc = desc
        col.addView(desc, lp(MATCH, WRAP, 8, 0, 18, 18))

        // selected server
        val serverCard = GlassCard(this, 20f, pad = 14).apply { isClickable = true; setOnClickListener { showTab(1) }; pressable(0.98f) }
        val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        srow.addView(badge(Icon.SERVERS), lp(dpi(44), dpi(44)))
        val sinfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
        sinfo.addView(tv("SELECTED SERVER", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        homeServerName = tv("—", 15f, Palette.text, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homeServerName!!, lp(MATCH, WRAP, 4))
        homeServerMeta = tv("", 11f, Palette.muted, 0).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homeServerMeta!!, lp(MATCH, WRAP, 2))
        srow.addView(sinfo, LinearLayout.LayoutParams(0, WRAP, 1f))
        srow.addView(IconView(this, Icon.CHEVRON, Palette.muted), lp(dpi(22), dpi(22)))
        serverCard.addView(srow)
        col.addView(serverCard, lp(MATCH, WRAP, 4))

        // account + expiry
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accCard = GlassCard(this, 18f, pad = 13)
        accCard.addView(tv("ACCOUNT", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        homeAccName = tv("—", 15f, Palette.text, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        accCard.addView(homeAccName!!, lp(MATCH, WRAP, 6))
        homeAccStatus = tv("", 11f, Palette.mint, 1)
        accCard.addView(homeAccStatus!!, lp(MATCH, WRAP, 3))
        val expCard = GlassCard(this, 18f, pad = 13)
        expCard.addView(tv("EXPIRES", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        homeExpiry = tv("—", 13f, Palette.text, 1).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        expCard.addView(homeExpiry!!, lp(MATCH, WRAP, 6))
        two.addView(accCard, LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dpi(6) })
        two.addView(expCard, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dpi(6) })
        col.addView(two, lp(MATCH, WRAP, 12))

        // data usage
        val usage = GlassCard(this, 18f, pad = 14)
        val urow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urow.addView(tv("DATA USAGE", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        homeUsageText = tv("—", 13f, Palette.text, 1)
        urow.addView(homeUsageText!!)
        usage.addView(urow)
        homeUsageMeter = LinearMeter(this)
        usage.addView(homeUsageMeter!!, lp(MATCH, dpi(6), 10))
        col.addView(usage, lp(MATCH, WRAP, 12))

        col.addView(
            tv("ENCRYPTED TUNNEL  •  TIREXNET", 8.5f, Palette.dim, 1).apply { gravity = Gravity.CENTER; letterSpacing = 0.24f },
            lp(MATCH, WRAP, 22)
        )
        return sv
    }

    private fun badge(icon: Icon, accent: Int = Palette.cyan): View {
        val f = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.withAlpha(accent, 54), Palette.withAlpha(accent, 12))).apply {
                cornerRadius = dp(14f)
                setStroke(dpi(1), Palette.withAlpha(accent, 110))
            }
        }
        f.addView(IconView(this, icon, accent, true), FrameLayout.LayoutParams(dpi(44), dpi(44)))
        return f
    }

    // ------------------------------------------------------------- Servers
    private fun buildServersTab(): View {
        val (sv, col) = tabScroll()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(tv("Servers", 26f, Palette.text, 2))
        ht.addView(tv("کانفیگ‌های اشتراک تو", 12f, Palette.muted, 0), lp(WRAP, WRAP, 4))
        head.addView(ht, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(roundButton(Icon.REFRESH) { loadServers() }, lp(dpi(42), dpi(42)))
        col.addView(head, lp(MATCH, WRAP, 6))

        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.glass(this@MainActivity, 16f)
            setPadding(dpi(12), 0, dpi(12), 0)
        }
        search.addView(IconView(this, Icon.SEARCH, Palette.muted), lp(dpi(20), dpi(20)))
        val sInput = EditText(this).apply {
            hint = "Search servers"
            textSize = 14f
            setTextColor(Palette.text)
            setHintTextColor(Palette.dim)
            setSingleLine(true)
            background = null
            setPadding(dpi(10), dpi(14), dpi(4), dpi(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    serverQuery = s?.toString().orEmpty().trim()
                    rebuildServerList(-1)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        search.addView(sInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(search, lp(MATCH, dpi(48), 14))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serverListBox = box
        col.addView(box, lp(MATCH, WRAP, 10))

        // manual import
        val imp = GlassCard(this, 20f, pad = 14)
        imp.addView(tv("IMPORT CONFIG", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        val brow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        brow.addView(ghostButton("Paste") { pasteClipboard() }, LinearLayout.LayoutParams(0, dpi(44), 1f).apply { rightMargin = dpi(4) })
        brow.addView(ghostButton("File") { pickFile() }, LinearLayout.LayoutParams(0, dpi(44), 1f).apply { leftMargin = dpi(4); rightMargin = dpi(4) })
        brow.addView(ghostButton("Edit") { toggleEditor() }, LinearLayout.LayoutParams(0, dpi(44), 1f).apply { leftMargin = dpi(4) })
        imp.addView(brow, lp(MATCH, WRAP, 10))

        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dpi(10), 0, 0) }
        editorBox = editor
        val input = EditText(this).apply {
            hint = "VLESS / VMess / Trojan / Shadowsocks / WireGuard"
            textSize = 12f
            setTextColor(Palette.text)
            setHintTextColor(Palette.dim)
            gravity = Gravity.TOP or Gravity.START
            minLines = 4; maxLines = 8
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            background = Palette.glass(this@MainActivity, 14f, Palette.stroke, Color.argb(70, 6, 14, 34), Color.argb(70, 6, 14, 34))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(currentConfig())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    if (settingConfig) return
                    prefs.edit().putString("last_config", s?.toString().orEmpty()).apply()
                    onConfigChanged()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        configInput = input
        editor.addView(input, lp(MATCH, WRAP))
        editorEngine = tv("", 10.5f, Palette.mint, 1)
        editor.addView(editorEngine!!, lp(WRAP, WRAP, 8))
        imp.addView(editor)
        col.addView(imp, lp(MATCH, WRAP, 10))
        editorEngine?.text = engineLabel(currentConfig())

        rebuildServerList(-1)
        return sv
    }

    private fun toggleEditor() {
        val e = editorBox ?: return
        e.visibility = if (e.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (e.visibility == View.VISIBLE) configInput?.requestFocus()
    }

    private fun ensureServersLoaded() {
        if (serversState == ServersState.IDLE && token.isNotBlank()) loadServers()
    }

    private fun rebuildServerList(popIndex: Int) {
        val box = serverListBox ?: return
        box.removeAllViews()
        when (serversState) {
            ServersState.IDLE, ServersState.LOADING -> {
                for (i in 0 until 3) box.addView(SkeletonView(this), lp(MATCH, dpi(76), if (i == 0) 0 else 10))
            }
            ServersState.ERROR -> {
                val c = GlassCard(this, 20f, Palette.withAlpha(Palette.red, 120), pad = 16)
                c.addView(tv("Couldn't load configurations", 15f, Palette.text, 1))
                c.addView(tv(serversError, 11.5f, Palette.muted, 0).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }, lp(MATCH, WRAP, 6))
                c.addView(primaryButton("Retry") { loadServers() }, lp(MATCH, dpi(46), 12))
                box.addView(c, lp(MATCH, WRAP))
            }
            ServersState.READY -> {
                if (servers.isEmpty()) {
                    box.addView(EmptyArt(this), lp(dpi(190), dpi(190), 4).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    box.addView(tv("No configurations yet", 16f, Palette.text, 1).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 6))
                    box.addView(
                        tv("اشتراک هیچ کانفیگی برنگرداند. می‌توانی کانفیگ را دستی وارد کنی.", 12f, Palette.muted, 0).apply { gravity = Gravity.CENTER },
                        lp(MATCH, WRAP, 6, 0, 20, 20)
                    )
                    box.addView(ghostButton("Reload") { loadServers() }, lp(MATCH, dpi(46), 14, 6, 60, 60))
                    return
                }
                val q = serverQuery.lowercase(Locale.US)
                val list = servers.filter { q.isEmpty() || it.name.lowercase(Locale.US).contains(q) || it.host.lowercase(Locale.US).contains(q) }
                if (list.isEmpty()) {
                    box.addView(tv("No matching server", 12.5f, Palette.muted, 0).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 20, 10))
                    return
                }
                val cur = currentConfig().trim()
                for ((idx, item) in list.withIndex()) {
                    val sel = item.raw.trim() == cur
                    val card = GlassCard(this, 18f, if (sel) Palette.strokeActive else Palette.stroke, pad = 12).apply {
                        isClickable = true
                        if (sel) background = Palette.glass(this@MainActivity, 18f, Palette.strokeActive, Palette.withAlpha(Palette.cyan, 54), Palette.withAlpha(Palette.violet, 24))
                        setOnClickListener { selectServer(item, idx) }
                        pressable(0.98f)
                    }
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val accent = when (item.engine) {
                        ConfigEngine.XRAY -> Palette.cyan
                        ConfigEngine.WIREGUARD -> Palette.violet
                        ConfigEngine.AMNEZIAWG -> Palette.mint
                        else -> Palette.muted
                    }
                    val short = when (item.engine) { ConfigEngine.XRAY -> "XR"; ConfigEngine.WIREGUARD -> "WG"; ConfigEngine.AMNEZIAWG -> "AW"; else -> "?" }
                    row.addView(
                        tv(short, 13f, accent, 2).apply {
                            gravity = Gravity.CENTER
                            letterSpacing = 0.08f
                            background = GradientDrawable().apply {
                                cornerRadius = dp(14f); setColor(Palette.withAlpha(accent, 28)); setStroke(dpi(1), Palette.withAlpha(accent, 120))
                            }
                        },
                        lp(dpi(46), dpi(46))
                    )
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
                    info.addView(tv(item.name, 14.5f, Palette.text, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    val meta = listOf(engineName(item.engine), item.host, item.port).filter { it.isNotBlank() }.joinToString("  •  ")
                    info.addView(tv(meta, 10.5f, Palette.muted, 0).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(MATCH, WRAP, 4))
                    row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
                    if (sel) {
                        row.addView(
                            FrameLayout(this).apply {
                                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.withAlpha(Palette.cyan, 50)); setStroke(dpi(1), Palette.cyan) }
                                addView(IconView(this@MainActivity, Icon.CHECK, Palette.cyan), FrameLayout.LayoutParams(dpi(26), dpi(26)))
                            },
                            lp(dpi(26), dpi(26))
                        )
                    }
                    card.addView(row)
                    box.addView(card, lp(MATCH, WRAP, if (idx == 0) 0 else 10))
                    if (idx == popIndex) {
                        card.scaleX = 0.96f; card.scaleY = 0.96f
                        card.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
        }
    }

    private fun selectServer(item: ServerItem, idx: Int) {
        setConfig(item.raw)
        rebuildServerList(idx)
        toast("سرور انتخاب شد؛ روی دکمه‌ی اتصال بزن.")
    }

    // --------------------------------------------------------------- Stats
    private fun buildStatsTab(): View {
        val (sv, col) = tabScroll()
        col.addView(tv("Statistics", 26f, Palette.text, 2), lp(WRAP, WRAP, 6))
        col.addView(tv("فقط مقدارهایی که واقعاً از سرور یا اپ می‌رسد", 12f, Palette.muted, 0), lp(WRAP, WRAP, 4))

        val ringCard = GlassCard(this, 24f, pad = 16)
        val ring = UsageRing(this)
        statsRing = ring
        ringCard.addView(ring, LinearLayout.LayoutParams(dpi(190), dpi(190)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ringCard.addView(tv("DATA USAGE", 8.5f, Palette.muted, 1).apply { gravity = Gravity.CENTER; letterSpacing = 0.18f }, lp(MATCH, WRAP, 10))
        col.addView(ringCard, lp(MATCH, WRAP, 16))

        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsStatus = tv("—", 15f, Palette.text, 1)
        statsDuration = tv("—", 15f, Palette.text, 4)
        r1.addView(metricTile("STATUS", statsStatus!!), LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dpi(6) })
        r1.addView(metricTile("SESSION", statsDuration!!), LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dpi(6) })
        col.addView(r1, lp(MATCH, WRAP, 12))

        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsEngine = tv("—", 15f, Palette.text, 1)
        statsExpiry = tv("—", 13f, Palette.text, 1).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        r2.addView(metricTile("ENGINE", statsEngine!!), LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dpi(6) })
        r2.addView(metricTile("EXPIRES", statsExpiry!!), LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dpi(6) })
        col.addView(r2, lp(MATCH, WRAP, 12))

        val speed = GlassCard(this, 18f, pad = 14)
        speed.addView(tv("LIVE SPEED", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        speed.addView(tv("Not measured", 15f, Palette.text, 1), lp(WRAP, WRAP, 8))
        speed.addView(tv("سرعت لحظه‌ای در این نسخه اندازه‌گیری نمی‌شود، پس عددی نشان داده نمی‌شود.", 11f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        col.addView(speed, lp(MATCH, WRAP, 12))

        val log = GlassCard(this, 18f, pad = 14)
        val lrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lrow.addView(tv("LAST CONNECTION EVENT", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        lrow.addView(
            IconView(this, Icon.COPY, Palette.ice).apply { setOnClickListener { copyDiagnostics() }; contentDescription = "Copy diagnostics"; pressable(0.9f) },
            lp(dpi(28), dpi(28))
        )
        log.addView(lrow)
        statsLog = tv("—", 12f, Palette.text, 0).apply { maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
        log.addView(statsLog!!, lp(MATCH, WRAP, 8))
        col.addView(log, lp(MATCH, WRAP, 12))
        return sv
    }

    private fun metricTile(label: String, value: TextView): View {
        val c = GlassCard(this, 18f, pad = 13)
        c.addView(tv(label, 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        c.addView(value, lp(MATCH, WRAP, 8))
        return c
    }

    // ------------------------------------------------------------- Profile
    private fun buildProfileTab(): View {
        val (sv, col) = tabScroll()
        profileScroll = sv
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val av = tv("T", 30f, Palette.text, 2).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.blue, Palette.cyan, Palette.violet)).apply {
                shape = GradientDrawable.OVAL
            }
        }
        profAvatar = av
        top.addView(av, lp(dpi(78), dpi(78), 6))
        profName = tv("—", 20f, Palette.text, 2).apply { gravity = Gravity.CENTER }
        top.addView(profName!!, lp(WRAP, WRAP, 12))
        profStatus = tv("", 11f, Palette.mint, 1).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f }
        top.addView(profStatus!!, lp(WRAP, WRAP, 6))
        col.addView(top, lp(MATCH, WRAP))

        val sub = GlassCard(this, 20f, pad = 14)
        sub.addView(tv("SUBSCRIPTION", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        profPanel = infoRow(sub, "Panel user")
        profExpiry = infoRow(sub, "Expires")
        profUsage = infoRow(sub, "Data used")
        col.addView(sub, lp(MATCH, WRAP, 18))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ghostButton("Refresh account") { loadAccount(true) }, LinearLayout.LayoutParams(0, dpi(48), 1f).apply { rightMargin = dpi(6) })
        actions.addView(ghostButton("Log out") { confirmLogout() }, LinearLayout.LayoutParams(0, dpi(48), 1f).apply { leftMargin = dpi(6) })
        col.addView(actions, lp(MATCH, WRAP, 12))

        val anchor = tv("SETTINGS", 10f, Palette.cyan, 2).apply { letterSpacing = 0.22f }
        settingsAnchor = anchor
        col.addView(anchor, lp(WRAP, WRAP, 26, 8))

        val g1 = GlassCard(this, 20f, pad = 4)
        settingRow(g1, Icon.INFO, "Active engine", "تشخیص خودکار از روی کانفیگ", engineLabelShort()) { }
        settingRow(g1, Icon.COPY, "Copy diagnostics", "بدون رمز و توکن", null) { copyDiagnostics() }
        settingRow(g1, Icon.TRASH, "Clear saved config", "پاک‌کردن کانفیگ ذخیره‌شده روی گوشی", null) { clearConfig() }
        col.addView(g1, lp(MATCH, WRAP))

        val g2 = GlassCard(this, 20f, pad = 4)
        settingRow(g2, Icon.SHIELD, "Privacy", "چه چیزی روی گوشی ذخیره می‌شود", null) { showPrivacy() }
        settingRow(g2, Icon.INFO, "App version", packageName, versionName()) { }
        col.addView(g2, lp(MATCH, WRAP, 12))
        return sv
    }

    private fun infoRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(label, 12f, Palette.muted, 0), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = tv("—", 13f, Palette.text, 1).apply { gravity = Gravity.END; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1.4f))
        parent.addView(row, lp(MATCH, WRAP, 12))
        return v
    }

    private fun settingRow(parent: LinearLayout, icon: Icon, title: String, subtitle: String, value: String?, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), dpi(12), dpi(10), dpi(12))
            isClickable = true
            setOnClickListener { onClick() }
            pressable(0.985f)
        }
        row.addView(IconView(this, icon, Palette.ice), lp(dpi(24), dpi(24)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
        t.addView(tv(title, 14f, Palette.text, 1))
        t.addView(tv(subtitle, 10.5f, Palette.muted, 0).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(MATCH, WRAP, 3))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (value != null) row.addView(tv(value, 12f, Palette.cyan, 1))
        parent.addView(row, lp(MATCH, WRAP))
    }

    private fun scrollToSettings() {
        val sv = profileScroll ?: return
        val a = settingsAnchor ?: return
        sv.post { sv.smoothScrollTo(0, (a.top - dpi(12)).coerceAtLeast(0)) }
    }

    // --------------------------------------------------------------- Login
    private fun buildLogin(): View {
        val (sv, col) = tabScroll()
        col.addView(tv("SECURE ACCESS", 10f, Palette.cyan, 2).apply { letterSpacing = 0.26f }, lp(WRAP, WRAP, 22))
        col.addView(tv("Your internet.\nYour rules.", 36f, Palette.text, 3).apply { setLineSpacing(0f, 1.05f) }, lp(WRAP, WRAP, 10))
        col.addView(tv("با حساب اشتراک وارد شو و کانفیگ‌هایت را مدیریت کن.", 13f, Palette.muted, 0), lp(MATCH, WRAP, 12))

        val card = GlassCard(this, 24f, pad = 16)
        usernameInput = field("Username", false)
        passwordInput = field("Password", true)
        card.addView(usernameInput!!, lp(MATCH, dpi(54)))
        card.addView(passwordInput!!, lp(MATCH, dpi(54), 10))
        val btn = primaryButton("Sign in") { login() }
        loginButton = btn
        card.addView(btn, lp(MATCH, dpi(54), 16))
        col.addView(card, lp(MATCH, WRAP, 26))
        col.addView(
            tv("احراز هویت از طریق سرویس TIREXNET انجام می‌شود.", 10.5f, Palette.dim, 0).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP, 16)
        )
        return sv
    }

    private fun field(hintText: String, password: Boolean) = EditText(this).apply {
        hint = hintText
        textSize = 14.5f
        setTextColor(Palette.text)
        setHintTextColor(Palette.dim)
        setSingleLine(true)
        setPadding(dpi(16), 0, dpi(16), 0)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = Palette.glass(this@MainActivity, 16f, Palette.stroke, Color.argb(70, 6, 14, 34), Color.argb(70, 6, 14, 34))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView = tv(label, 14f, Color.rgb(2, 10, 24), 2).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.06f
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.cyan, Palette.mint)).apply { cornerRadius = dp(16f) }
        isClickable = true
        setOnClickListener { onClick() }
        pressable(0.97f)
    }

    private fun ghostButton(label: String, onClick: () -> Unit): TextView = tv(label, 12.5f, Palette.text, 1).apply {
        gravity = Gravity.CENTER
        background = Palette.glass(this@MainActivity, 14f)
        isClickable = true
        setOnClickListener { onClick() }
        pressable(0.96f)
    }

    // =====================================================================
    // Connection state UI
    // =====================================================================

    private fun updateConnectionUi() {
        val title: String; val desc: String; val color: Int; val chip: String
        when (state) {
            OrbState.DISCONNECTED -> { title = "Disconnected"; desc = "اتصال برقرار نیست. برای شروع، دکمه را بزن."; color = Palette.muted; chip = "OFFLINE" }
            OrbState.CONNECTING -> { title = "Connecting"; desc = statusMessage.ifBlank { "در حال راه‌اندازی تونل…" }; color = Palette.cyan; chip = "CONNECTING" }
            OrbState.CONNECTED -> { title = "Connected"; desc = "تونل توسط هسته‌ی VPN تأیید شد."; color = Palette.mint; chip = "SECURE" }
            OrbState.DISCONNECTING -> { title = "Disconnecting"; desc = "در حال آزادسازی تونل…"; color = Palette.ice; chip = "CLOSING" }
            OrbState.FAILED -> { title = "Connection failed"; desc = statusMessage.take(160); color = Palette.red; chip = "ERROR" }
            OrbState.PERMISSION -> { title = "Permission required"; desc = "برای ساخت تونل، اجازه‌ی VPN را تأیید کن."; color = Palette.amber; chip = "PERMISSION" }
        }
        orb?.state = state
        statusTitle?.apply { text = title; setTextColor(color) }
        statusDesc?.text = desc
        chipText?.apply { text = chip; setTextColor(color) }
        chipDot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statsStatus?.apply { text = title; setTextColor(color) }
        statsEngine?.text = engineLabelShort()
        statsLog?.text = statusMessage.ifBlank { "—" }
        timerView?.visibility = if (state == OrbState.CONNECTED) View.VISIBLE else View.GONE
        statsDuration?.text = if (state == OrbState.CONNECTED) durationText() else "—"
        handler.removeCallbacks(tick)
        if (state == OrbState.CONNECTED && resumed) {
            timerView?.text = durationText()
            handler.postDelayed(tick, 1000)
        }
    }

    private fun durationText(): String {
        val s = ((SystemClock.elapsedRealtime() - connectedAtRt) / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun engineLabelShort(): String {
        val e = prefs.getString("active_engine", "") ?: ""
        return if (e.isNotBlank()) e else engineName(ConfigDetector.detect(currentConfig()))
    }

    private fun applyStatus(message: String, active: Boolean) {
        statusMessage = message
        val lower = message.lowercase(Locale.US)
        val next = when {
            active -> OrbState.CONNECTED
            listOf("failed", "error", "invalid", "could not", "ناموفق", "خطا").any { lower.contains(it) } -> OrbState.FAILED
            lower.contains("denied") || lower.contains("revoked") -> OrbState.PERMISSION
            lower.contains("disconnected") -> OrbState.DISCONNECTED
            else -> OrbState.CONNECTING
        }
        if (next == OrbState.CONNECTED) {
            if (connectedAtRt == 0L) connectedAtRt = SystemClock.elapsedRealtime()
        } else connectedAtRt = 0L
        state = next
        prefs.edit().putString("connection_message", message).putBoolean("connection_active", active).apply()
        updateConnectionUi()
    }

    // =====================================================================
    // VPN engine selection (same engines and flow as before)
    // =====================================================================

    private fun toggleConnection() {
        when (state) {
            OrbState.CONNECTED -> disconnect()
            OrbState.CONNECTING, OrbState.DISCONNECTING -> toast("لطفاً چند لحظه صبر کن.")
            else -> connect()
        }
    }

    private fun connect() {
        val raw = currentConfig().trim()
        if (raw.isBlank()) {
            toast("اول یک کانفیگ انتخاب کن.")
            showTab(1)
            return
        }
        val engine = ConfigDetector.detect(raw)
        if (engine == ConfigEngine.UNKNOWN) {
            toast("فرمت کانفیگ شناسایی نشد. VLESS / VMess / Trojan / Shadowsocks یا WireGuard لازم است.")
            showTab(1)
            return
        }
        pendingRaw = raw
        pendingEngine = engine
        prefs.edit().putString("active_engine", engine.name).apply()

        val permission = VpnService.prepare(this)
        if (permission != null) {
            applyStatus("VPN permission is required.", false)
            state = OrbState.PERMISSION
            updateConnectionUi()
            @Suppress("DEPRECATION")
            startActivityForResult(permission, VPN_REQUEST)
            return
        }
        startSelectedEngine()
    }

    private fun startSelectedEngine() {
        when (pendingEngine) {
            ConfigEngine.XRAY -> {
                applyStatus("در حال راه‌اندازی هسته Xray…", false)
                val intent = Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, pendingRaw)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> {
                val amnezia = pendingEngine == ConfigEngine.AMNEZIAWG
                applyStatus(if (amnezia) "در حال راه‌اندازی AmneziaWG…" else "در حال راه‌اندازی WireGuard…", false)
                WgTunnelEngine.start(this, pendingRaw, amnezia) { message, active ->
                    runOnUiThread { applyStatus(message, active) }
                }
            }
            else -> toast("یک پروفایل پشتیبانی‌شده انتخاب کن.")
        }
    }

    private fun disconnect() {
        val engine = prefs.getString("active_engine", "") ?: ""
        state = OrbState.DISCONNECTING
        statusMessage = "Disconnecting…"
        updateConnectionUi()
        var issued = false
        if (engine == "WIREGUARD" || engine == "AMNEZIAWG" || WgTunnelEngine.isActive) {
            issued = true
            WgTunnelEngine.stop { message, active -> runOnUiThread { applyStatus(message, active) } }
        }
        if (engine == "XRAY" || XrayVpnService.isActive) {
            issued = true
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
        // Safety net so the UI can never hang in "Disconnecting".
        handler.postDelayed({
            if (state == OrbState.DISCONNECTING) applyStatus("Disconnected.", false)
        }, if (issued) 6000L else 300L)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startSelectedEngine()
            else {
                applyStatus("VPN permission was denied.", false)
                toast("اجازه‌ی VPN لازم است.")
            }
        } else if (requestCode == PICK_CONFIG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { setConfig(readLimited(it)) }
                toast("کانفیگ وارد شد.")
            } catch (e: Exception) {
                toast("خطا در واردکردن فایل: " + (e.message ?: "unknown error"))
            }
        }
    }

    // =====================================================================
    // Config helpers
    // =====================================================================

    private fun currentConfig(): String = prefs.getString("last_config", "") ?: ""

    private fun setConfig(raw: String) {
        prefs.edit().putString("last_config", raw).apply()
        settingConfig = true
        configInput?.setText(raw)
        settingConfig = false
        onConfigChanged()
        if (serversState == ServersState.READY) rebuildServerList(-1)
    }

    private fun onConfigChanged() {
        editorEngine?.text = engineLabel(currentConfig())
        bindServerSummary()
        statsEngine?.text = engineLabelShort()
    }

    private fun engineLabel(raw: String) = "Detected engine: " + engineName(ConfigDetector.detect(raw))

    private fun engineName(engine: ConfigEngine): String = when (engine) {
        ConfigEngine.XRAY -> "XRAY"
        ConfigEngine.WIREGUARD -> "WIREGUARD"
        ConfigEngine.AMNEZIAWG -> "AMNEZIAWG"
        else -> "NONE"
    }

    private fun bindServerSummary() {
        val raw = currentConfig().trim()
        if (raw.isBlank()) {
            homeServerName?.text = "No server selected"
            homeServerMeta?.text = "برای انتخاب سرور بزن"
            return
        }
        val ep = endpointOf(raw)
        homeServerName?.text = ep.name
        homeServerMeta?.text = listOf(engineName(ConfigDetector.detect(raw)), ep.host, ep.port).filter { it.isNotBlank() }.joinToString("  •  ")
    }

    private fun endpointOf(rawValue: String): Endpoint {
        val raw = rawValue.trim()
        val scheme = raw.substringBefore("://", "config").uppercase(Locale.US)
        var name = ""; var host = ""; var port = ""
        if (raw.startsWith("vmess://", true)) {
            val payload = raw.substringAfter("://").substringBefore("#").trim()
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = runCatching { String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8) }.getOrNull()
            val json = runCatching { JSONObject(decoded ?: "") }.getOrNull()
            if (json != null) {
                name = json.optString("ps", ""); host = json.optString("add", ""); port = json.optString("port", "")
            }
        } else {
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            host = uri?.host.orEmpty()
            if (uri != null && uri.port > 0) port = uri.port.toString()
            name = uri?.fragment.orEmpty()
        }
        if (host.isBlank()) {
            val endpoint = Regex("(?im)^\\s*Endpoint\\s*=\\s*([^\\s#]+)").find(raw)?.groupValues?.getOrNull(1)
            if (!endpoint.isNullOrBlank()) {
                val clean = endpoint.substringAfter("@", endpoint)
                host = clean.substringBeforeLast(":", clean)
                if (clean.contains(":")) port = clean.substringAfterLast(":")
            }
        }
        return Endpoint(name.ifBlank { scheme }, host, port)
    }

    private fun pasteClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = manager.primaryClip
            if (clip == null || clip.itemCount == 0) { toast("کلیپ‌بورد خالی است."); return }
            setConfig(clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim())
            toast("کانفیگ از کلیپ‌بورد وارد شد.")
        } catch (_: Exception) {
            toast("خواندن کلیپ‌بورد ناموفق بود.")
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, PICK_CONFIG)
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            require(total <= 1024 * 1024) { "File exceeds 1 MB." }
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8").trim()
    }

    private fun clearConfig() {
        AlertDialog.Builder(this)
            .setMessage("کانفیگ ذخیره‌شده روی این گوشی پاک شود؟")
            .setPositiveButton("پاک کن") { _, _ -> setConfig(""); toast("کانفیگ پاک شد.") }
            .setNegativeButton("لغو", null)
            .show()
    }

    // =====================================================================
    // Backend: login, account, configs (endpoints and formats unchanged)
    // =====================================================================

    private fun login() {
        val user = usernameInput?.text?.toString().orEmpty().trim()
        val pass = passwordInput?.text?.toString().orEmpty()
        if (user.isBlank() || pass.isBlank()) { toast("نام کاربری و رمز عبور را وارد کن."); return }
        loginButton?.apply { text = "Signing in…"; alpha = 0.6f; isEnabled = false }
        io.execute {
            try {
                val response = request("POST", "/api/login", null, JSONObject().put("username", user).put("password", pass))
                val newToken = response.optString("token")
                require(newToken.isNotBlank()) { response.optString("error", "Login token is missing.") }
                prefs.edit().putString("token", newToken).apply()
                token = newToken
                runOnUiThread {
                    currentTab = 0
                    serversState = ServersState.IDLE
                    servers = emptyList()
                    render()
                    loadAccount()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loginButton?.apply { text = "Sign in"; alpha = 1f; isEnabled = true }
                    toast("ورود ناموفق بود: " + (e.message ?: "network error"))
                }
            }
        }
    }

    private fun loadAccount(manual: Boolean = false) {
        if (token.isBlank()) return
        val t = token
        io.execute {
            try {
                val data = request("GET", "/api/me", t, null)
                val sub = data.optJSONObject("subscription") ?: JSONObject()
                val appUser = data.optString("username", "Account")
                val panelUser = sub.optString("username", "—")
                val panelStatus = sub.optString("status", "Unknown")
                val used = sub.optLong("usedTraffic", 0L)
                val cap = sub.optLong("dataLimit", 0L)
                val expire = if (sub.isNull("expire")) "بدون تاریخ پایان" else sub.optString("expire", "—")
                runOnUiThread {
                    accUser = appUser; accStatus = panelStatus; accPanelUser = panelUser
                    usedBytes = used; limitBytes = cap; expireText = expire
                    accountError = null
                    bindAccount()
                    if (manual) toast("اطلاعات حساب به‌روز شد.")
                }
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("401")) {
                    prefs.edit().remove("token").apply()
                    token = ""
                    runOnUiThread { render(); toast("نشست منقضی شده؛ دوباره وارد شو.") }
                } else {
                    runOnUiThread {
                        accountError = e.message ?: "network error"
                        bindAccount()
                        toast("به‌روزرسانی اشتراک ناموفق بود: " + accountError)
                    }
                }
            }
        }
    }

    private fun loadServers() {
        if (token.isBlank()) { toast("ابتدا وارد حساب شو."); return }
        serversState = ServersState.LOADING
        rebuildServerList(-1)
        val t = token
        io.execute {
            try {
                val data = request("GET", "/api/me/configs", t, null)
                val arr = data.optJSONArray("configs")
                    ?: data.optJSONArray("profiles")
                    ?: data.optJSONArray("items")
                    ?: throw IllegalStateException("Worker response has no configs/profiles array.")
                val out = ArrayList<ServerItem>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    val raw = when (item) {
                        is JSONObject -> item.optString("config", item.optString("uri", item.optString("raw", item.optString("content", item.optString("link", "")))))
                        is String -> item
                        else -> ""
                    }.trim()
                    if (raw.isNotBlank()) {
                        val ep = endpointOf(raw)
                        val given = (item as? JSONObject)?.optString("name", "")?.takeIf { it.isNotBlank() }
                        out.add(ServerItem(given ?: ep.name.ifBlank { "Profile " + (out.size + 1) }, raw, ConfigDetector.detect(raw), ep.host, ep.port))
                    }
                }
                runOnUiThread { servers = out; serversState = ServersState.READY; rebuildServerList(-1) }
            } catch (e: Exception) {
                runOnUiThread { serversError = e.message ?: "endpoint unavailable"; serversState = ServersState.ERROR; rebuildServerList(-1) }
            }
        }
    }

    private fun request(method: String, path: String, bearer: String?, body: JSONObject?): JSONObject {
        val c = URL(api + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = c.responseCode
            val responseText = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $responseText")
            return JSONObject(responseText)
        } finally {
            c.disconnect()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage("از حساب خارج می‌شوی؟")
            .setPositiveButton("خروج") { _, _ -> logout() }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun logout() {
        val old = token
        token = ""
        prefs.edit().remove("token").apply()
        accUser = ""; accStatus = ""; accPanelUser = ""; usedBytes = -1L; limitBytes = 0L; expireText = ""
        servers = emptyList(); serversState = ServersState.IDLE; currentTab = 0
        io.execute { runCatching { request("POST", "/api/logout", old, JSONObject()) } }
        render()
    }

    // =====================================================================
    // Account binding
    // =====================================================================

    private fun bindAccount() {
        val unavailable = if (accountError != null) "Unavailable" else "—"
        val name = accUser.ifBlank { unavailable }
        homeAccName?.text = name
        homeAccStatus?.text = accStatus.ifBlank { "" }
        val exp = if (expireText.isBlank()) unavailable else expireText.take(24)
        homeExpiry?.text = exp
        statsExpiry?.text = exp
        profExpiry?.text = exp
        profName?.text = name
        profAvatar?.text = (accUser.firstOrNull()?.uppercaseChar() ?: 'T').toString()
        profStatus?.text = accStatus.uppercase(Locale.US)
        profPanel?.text = accPanelUser.ifBlank { unavailable }
        val usageStr = when {
            usedBytes < 0 -> unavailable
            limitBytes > 0 -> formatBytes(usedBytes) + " / " + formatBytes(limitBytes)
            else -> formatBytes(usedBytes)
        }
        profUsage?.text = usageStr
        if (usedBytes >= 0) animateUsage(usageStr) else homeUsageText?.text = unavailable
        val fraction = if (usedBytes >= 0 && limitBytes > 0) (usedBytes.toDouble() / limitBytes).toFloat() else null
        homeUsageMeter?.set(fraction ?: 0f)
        statsRing?.update(
            fraction,
            if (usedBytes >= 0) formatBytes(usedBytes) else unavailable,
            if (usedBytes < 0) "" else if (limitBytes > 0) "of " + formatBytes(limitBytes) else "no limit reported"
        )
    }

    private fun animateUsage(finalText: String) {
        val tvv = homeUsageText ?: return
        val target = usedBytes
        val start = shownUsed
        shownUsed = target
        if (start == target) { tvv.text = finalText; return }
        ValueAnimator.ofFloat(start.toFloat(), target.toFloat()).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = (it.animatedValue as Float).toLong()
                tvv.text = if (limitBytes > 0) formatBytes(v) + " / " + formatBytes(limitBytes) else formatBytes(v)
            }
            start()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    // =====================================================================
    // Settings actions
    // =====================================================================

    private fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    }.getOrDefault("—")

    private fun copyDiagnostics() {
        val text = "TIREXNET " + versionName() + "\n" +
            "state=" + state.name + "\n" +
            "engine=" + engineLabelShort() + "\n" +
            "message=" + statusMessage + "\n" +
            "android=" + Build.VERSION.SDK_INT + " device=" + Build.MODEL
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TIREXNET diagnostics", text))
        toast("اطلاعات عیب‌یابی کپی شد.")
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle("Privacy")
            .setMessage(
                "• فقط توکن نشست و کانفیگ انتخاب‌شده روی همین گوشی ذخیره می‌شود.\n" +
                    "• رمز عبور هرگز ذخیره نمی‌شود.\n" +
                    "• اطلاعات عیب‌یابی شامل رمز یا توکن نیست."
            )
            .setPositiveButton("باشه", null)
            .show()
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dpi(left), dpi(top), dpi(right), dpi(bottom)) }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val VPN_REQUEST = 71
        private const val PICK_CONFIG = 72
    }
}
