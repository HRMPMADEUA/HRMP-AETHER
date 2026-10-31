
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
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.res.ColorStateList
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tirexnet.app.engine.ConfigDetector
import com.tirexnet.app.engine.ApiConfig
import com.tirexnet.app.engine.ApiHttpException
import com.tirexnet.app.engine.ApiUnavailableException
import com.tirexnet.app.engine.ConfigEngine
import com.tirexnet.app.engine.ConfigSanitizer
import com.tirexnet.app.engine.FieldKind
import com.tirexnet.app.engine.ManualOutcome
import com.tirexnet.app.engine.ManualProfile
import com.tirexnet.app.engine.ManualProtocol
import com.tirexnet.app.engine.OpenVpnEngine
import com.tirexnet.app.engine.PingKind
import com.tirexnet.app.engine.PingResult
import com.tirexnet.app.engine.PingTester
import com.tirexnet.app.engine.ProfileMeta
import com.tirexnet.app.engine.ProfileParser
import com.tirexnet.app.engine.TirexActions
import com.tirexnet.app.engine.TirexNotifier
import com.tirexnet.app.engine.TrafficMonitor
import com.tirexnet.app.engine.WgTunnelEngine
import com.tirexnet.app.engine.XrayVpnService
import com.tirexnet.app.ui.AmbientBackground
import com.tirexnet.app.ui.BrandMark
import com.tirexnet.app.ui.ConnectOrb
import com.tirexnet.app.ui.EmptyArt
import com.tirexnet.app.ui.FlagBadge
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /**
     * [meta] holds the endpoint (host/port) that engines and the latency probe need.
     * It must never be rendered. [country] is an ISO-3166 alpha-2 code from trusted metadata, or "".
     */
    private data class ServerItem(val name: String, val raw: String, val engine: ConfigEngine, val meta: ProfileMeta, val local: Boolean = false, val country: String = "")
    private class PingHolder(val text: TextView, val button: FrameLayout, val spinner: ProgressBar, val icon: IconView)
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
    private var addOverlay: View? = null
    private var rootRef: FrameLayout? = null
    private var insetBox = intArrayOf(0, 0, 0, 0)
    private val pingHolders = HashMap<String, PingHolder>()
    private var homePing: TextView? = null
    private var homeFlag: FlagBadge? = null
    private var speedDown: TextView? = null
    private var speedUp: TextView? = null
    private var speedTotals: TextView? = null
    private var speedNote: TextView? = null
    private val traffic = TrafficMonitor()
    private var addStatus: TextView? = null
    private var addScroll: ScrollView? = null
    private var manualProtoRow: LinearLayout? = null
    private var manualFieldsBox: LinearLayout? = null
    private var manualError: TextView? = null
    private var manualSaveBtn: TextView? = null
    private var rawInput: EditText? = null
    private var manualProtocol = ManualProtocol.VLESS
    private var manualCountry = ""
    private val manualValues = HashMap<String, String>()
    private val manualFieldViews = HashMap<String, EditText>()

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
    private var settingsAnchor: View? = null
    private var profileScroll: ScrollView? = null
    private var usernameInput: EditText? = null
    private var passwordInput: EditText? = null
    private var loginButton: TextView? = null
    private var confirmInput: EditText? = null
    private var registerMode = false

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

    /** Live speed: runs only while connected, resumed and the Statistics tab is visible. */
    private val speedTick = object : Runnable {
        override fun run() {
            if (state == OrbState.CONNECTED && resumed && currentTab == 2) {
                bindSpeed(traffic.sample())
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** Connection ping: one probe right after connecting, then at most once a minute while visible. */
    private val pingTick = object : Runnable {
        override fun run() {
            if (state == OrbState.CONNECTED && resumed) {
                measureSelectedPing(force = true)
                handler.postDelayed(this, 60_000L)
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        PingTester.networkProvider = { phoneNetwork() }
        val lastCrash = prefs.getString("last_crash", "").orEmpty()
        if (lastCrash.isNotBlank()) { showCrashScreen(lastCrash); return }
        // Theme is restored before any view is built, so Activity re-creation never resets it.
        Palette.dark = prefs.getBoolean("theme_dark", true)
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
        applySystemBars() // after setContentView: the decor view exists now
        if (intent?.getBooleanExtra(TirexActions.EXTRA_AUTO_CONNECT, false) == true) {
            intent.removeExtra(TirexActions.EXTRA_AUTO_CONNECT)
            handler.postDelayed({ if (state == OrbState.DISCONNECTED) connect() }, 600L)
        }
        if (token.isNotBlank()) loadAccount()
    }

    override fun onResume() {
        super.onResume()
        if (crashScreenShown) return
        resumed = true
        reconcileState()
        updateConnectionUi()
        refreshPolling()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(pingTick)
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
        when {
            addOverlay != null -> closeAddServer()
            token.isNotBlank() && currentTab != 0 -> showTab(0)
            else -> super.onBackPressed()
        }
    }

    // =====================================================================
    // Theme
    // =====================================================================

    // =====================================================================
    // Crash recorder: stores a redacted stack trace so the next launch can show it
    // instead of silently closing. Nothing is sent anywhere.
    // =====================================================================

    private var crashScreenShown = false

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(error)
                    .lineSequence().take(18).joinToString("\n") { ConfigSanitizer.redact(it) }
                prefs.edit().putString("last_crash", trace).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showCrashScreen(trace: String) {
        crashScreenShown = true
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(48), dpi(20), dpi(20)) }
        col.addView(TextView(this).apply {
            text = "The app closed unexpectedly last time"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(Color.rgb(255, 190, 120))
            setTextIsSelectable(true)
            setPadding(0, dpi(12), 0, dpi(12))
        })
        val copy = TextView(this).apply {
            text = "Copy error"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(30, 70, 140)); setPadding(0, dpi(14), 0, dpi(14))
            setOnClickListener {
                val m = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                m.setPrimaryClip(ClipData.newPlainText("TIREXNET error", trace))
            }
        }
        val go = TextView(this).apply {
            text = "Continue to app"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(40, 40, 60)); setPadding(0, dpi(14), 0, dpi(14))
            setOnClickListener { prefs.edit().remove("last_crash").commit(); recreate() }
        }
        col.addView(copy, lp(MATCH, WRAP, 4))
        col.addView(go, lp(MATCH, WRAP, 12))
        val sv = ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 12, 24)); addView(col) }
        setContentView(sv)
    }

    private fun toggleTheme() {
        Palette.dark = !Palette.dark
        prefs.edit().putBoolean("theme_dark", Palette.dark).apply()
        applySystemBars()
        render() // every view reads Palette at build time; connection state lives in fields and survives
    }

    /** Dark icons on light backgrounds and vice versa. */
    private fun applySystemBars() {
        runCatching { applySystemBarsUnsafe() }
    }

    private fun applySystemBarsUnsafe() {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (Palette.dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (Palette.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    /** (Re)starts or stops the periodic jobs according to state, visibility and tab. */
    private fun refreshPolling() {
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(pingTick)
        if (state == OrbState.CONNECTED && resumed) {
            handler.postDelayed(pingTick, 300L)
            if (currentTab == 2) {
                traffic.sample() // totals keep accumulating; the speed shown starts from the next sample
                handler.postDelayed(speedTick, 1000L)
            }
        }
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
        addOverlay = null
        pingHolders.clear()
        val rootFrame = FrameLayout(this)
        rootRef = rootFrame
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
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
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
            insetBox = intArrayOf(l, t, r, b)
            addOverlay?.setPadding(l, t, r, b)
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
            row.addView(
                roundButton(if (Palette.dark) Icon.SUN else Icon.MOON, if (Palette.dark) "Switch to light theme" else "Switch to dark theme") { toggleTheme() },
                lp(dpi(38), dpi(38), 0, 0, 0, 6)
            )
            row.addView(roundButton(Icon.GEAR, "Settings") { showTab(3); scrollToSettings() }, lp(dpi(38), dpi(38)))
        }
        return row
    }

    private fun roundButton(icon: Icon, description: String = icon.name, onClick: () -> Unit): View {
        val f = FrameLayout(this).apply {
            background = Palette.glass(this@MainActivity, 19f)
            isClickable = true
            contentDescription = description
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
        refreshPolling()
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
        val hf = flagBox(44, 14f, "", Palette.cyan)
        homeFlag = hf
        srow.addView(hf, lp(dpi(50), dpi(50)))
        val sinfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
        sinfo.addView(tv("SELECTED SERVER", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        homeServerName = tv("—", 15f, Palette.text, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homeServerName!!, lp(MATCH, WRAP, 4))
        // Ping is its own line and is independent from the VPN connection status above.
        homePing = tv("", 11f, Palette.muted, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homePing!!, lp(MATCH, WRAP, 3))
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
        ht.addView(tv("کانفیگ‌های اشتراک و پروفایل‌های ذخیره‌شده", 12f, Palette.muted, 0), lp(WRAP, WRAP, 4))
        head.addView(ht, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(roundButton(Icon.BOLT, "Test ping of all servers") { testAllPings() }, lp(dpi(42), dpi(42), 0, 0, 0, 8))
        head.addView(roundButton(Icon.REFRESH, "Reload servers") { loadServers() }, lp(dpi(42), dpi(42)))
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

        // Add / import: Paste and File save automatically; Manual opens the Add Server page.
        val imp = GlassCard(this, 20f, pad = 14)
        imp.addView(tv("ADD SERVER", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        val brow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        brow.addView(ghostButton("Paste") { pasteClipboard() }, LinearLayout.LayoutParams(0, dpi(46), 1f).apply { rightMargin = dpi(4) })
        brow.addView(ghostButton("File") { pickFile() }, LinearLayout.LayoutParams(0, dpi(46), 1f).apply { leftMargin = dpi(4); rightMargin = dpi(4) })
        brow.addView(ghostButton("Manual") { openAddServer() }, LinearLayout.LayoutParams(0, dpi(46), 1f).apply { leftMargin = dpi(4) })
        imp.addView(brow, lp(MATCH, WRAP, 10))
        imp.addView(tv("کانفیگ پیست‌شده یا واردشده از فایل خودکار ذخیره می‌شود.", 10.5f, Palette.muted, 0), lp(MATCH, WRAP, 10))
        col.addView(imp, lp(MATCH, WRAP, 10))

        rebuildServerList(-1)
        return sv
    }

    private fun ensureServersLoaded() {
        if (serversState == ServersState.IDLE && token.isNotBlank()) loadServers()
    }

    private fun rebuildServerList(popIndex: Int) {
        val box = serverListBox ?: return
        box.removeAllViews()
        pingHolders.clear()
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
                        tv("اشتراک هیچ کانفیگی برنگرداند. می‌توانی کانفیگ را پیست کنی، از فایل وارد کنی یا دستی اضافه کنی.", 12f, Palette.muted, 0).apply { gravity = Gravity.CENTER },
                        lp(MATCH, WRAP, 6, 0, 20, 20)
                    )
                    box.addView(primaryButton("Add server") { openAddServer() }, lp(MATCH, dpi(46), 14, 6, 60, 60))
                    return
                }
                val q = serverQuery.lowercase(Locale.US)
                // Search matches the visible name only; endpoints are never searchable or shown.
                val list = servers.filter { q.isEmpty() || it.name.lowercase(Locale.US).contains(q) }
                if (list.isEmpty()) {
                    box.addView(tv("No matching server", 12.5f, Palette.muted, 0).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 20, 10))
                    return
                }
                val cur = currentConfig().trim()
                for ((idx, item) in list.withIndex()) {
                    val sel = item.raw.trim() == cur
                    val key = item.raw.trim()
                    val card = GlassCard(this, 18f, if (sel) Palette.strokeActive else Palette.stroke, pad = 12).apply {
                        isClickable = true
                        contentDescription = item.name
                        if (sel) background = Palette.glass(this@MainActivity, 18f, Palette.strokeActive, Palette.withAlpha(Palette.cyan, 54), Palette.withAlpha(Palette.violet, 24))
                        setOnClickListener { selectServer(item, idx) }
                        pressable(0.98f)
                    }
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    // Country flag takes the place of the old XR/WG/AW badge. The engine is still detected internally.
                    row.addView(flagBox(46, 14f, item.country, if (sel) Palette.cyan else Palette.ice).also { it.setSelectedState(sel) }, lp(dpi(50), dpi(50)))
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
                    info.addView(tv(item.name, 14.5f, Palette.text, 1).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    val sub = if (sel) "Selected" else if (item.local) "Saved on this phone" else ""
                    if (sub.isNotEmpty()) info.addView(tv(sub, 10.5f, if (sel) Palette.cyan else Palette.muted, 0), lp(MATCH, WRAP, 4))
                    row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

                    val holder = buildPingControl(item)
                    pingHolders[key] = holder
                    row.addView(holder.text, lp(WRAP, WRAP, 0, 0, 0, 6))
                    row.addView(holder.button, lp(dpi(44), dpi(44)))
                    bindPing(key)
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

    // ---------------------------------------------------------- Flag + ping UI

    /** Rounded square with the country flag, or a neutral globe when the country is unknown. */
    private fun flagBox(sizeDp: Int, radiusDp: Float, country: String, accent: Int): FlagBadge =
        FlagBadge(this).also { it.setAccent(accent); it.setCountry(country) }

    private fun fillFlag(box: FlagBadge?, country: String, sizeDp: Int) {
        box?.setCountry(country)
    }

    private fun buildPingControl(item: ServerItem): PingHolder {
        val text = tv("", 12.5f, Palette.muted, 1).apply { gravity = Gravity.END; minWidth = dpi(52) }
        val icon = IconView(this, Icon.REFRESH, Palette.ice)
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Palette.cyan)
            visibility = View.GONE
        }
        val button = FrameLayout(this).apply {
            background = Palette.glass(this@MainActivity, 14f)
            isClickable = true
            contentDescription = "Test ping for " + item.name
            setOnClickListener { runPing(item, true) }
            pressable(0.92f)
            addView(icon, FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
            addView(spinner, FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        }
        return PingHolder(text, button, spinner, icon)
    }

    /** Fast < 100 ms, moderate < 250 ms, slow otherwise; unreachable/timeout are red. */
    private fun pingStyle(r: PingResult?): Pair<String, Int> = when {
        r == null -> "" to Palette.muted
        r.kind == PingKind.OK -> "${r.ms} ms" to when {
            r.ms < 100 -> Palette.mint
            r.ms < 250 -> Palette.cyan
            else -> Palette.amber
        }
        r.kind == PingKind.TIMEOUT -> "Timeout" to Palette.red
        r.kind == PingKind.FAILED && r.method == PingTester.METHOD_OFFLINE -> "No network" to Palette.muted
        r.kind == PingKind.FAILED -> "Unreachable" to Palette.red
        else -> "N/A" to Palette.muted
    }

    private fun bindPing(key: String) {
        val h = pingHolders[key] ?: return
        if (PingTester.isRunning(key)) {
            h.spinner.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
            h.text.text = ""
            h.button.isEnabled = false
        } else {
            h.spinner.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.button.isEnabled = true
            val (t, c) = pingStyle(PingTester.cached(key))
            h.text.text = t
            h.text.setTextColor(c)
        }
    }

    /** One probe per profile at a time; a fresh cached value is reused unless [force] (manual retest). */
    private fun runPing(item: ServerItem, force: Boolean) {
        val key = item.raw.trim()
        if (PingTester.isRunning(key)) return
        PingTester.test(key, item.meta, force) {
            if (isFinishing || isDestroyed) return@test
            bindPing(key)
            if (key == currentConfig().trim()) bindHomePing()
        }
        bindPing(key)
    }

    private fun testAllPings() {
        val list = servers
        if (list.isEmpty()) { toast("سروری برای تست وجود ندارد."); return }
        list.forEach { runPing(it, false) }
    }

    private fun measureSelectedPing(force: Boolean) {
        val item = selectedItem() ?: return
        val key = item.raw.trim()
        if (key.isEmpty()) return
        PingTester.test(key, item.meta, force) {
            if (isFinishing || isDestroyed) return@test
            bindHomePing()
            bindPing(key)
        }
        bindHomePing()
    }

    private fun bindHomePing() {
        val v = homePing ?: return
        val raw = currentConfig().trim()
        if (raw.isEmpty()) { v.text = ""; return }
        if (PingTester.isRunning(raw)) { v.text = "Ping: measuring…"; v.setTextColor(Palette.muted); return }
        val r = PingTester.cached(raw)
        if (r == null) { v.text = if (state == OrbState.CONNECTED) "Ping: measuring…" else "Ping: not tested"; v.setTextColor(Palette.muted); return }
        val (t, c) = pingStyle(r)
        v.text = when (r.kind) {
            PingKind.OK -> "Ping: $t"
            PingKind.UNSUPPORTED -> "Ping: not available"
            else -> "Ping: $t"
        }
        v.setTextColor(c)
    }

    private fun selectedItem(): ServerItem? {
        val raw = currentConfig().trim()
        if (raw.isEmpty()) return null
        servers.firstOrNull { it.raw.trim() == raw }?.let { return it }
        val meta = ProfileParser.parse(raw)
        val nm = if (meta.name.isBlank() || ProfileParser.looksTechnical(meta.name)) "Selected server" else meta.name
        return ServerItem(nm, raw, meta.engine, meta, false, ProfileParser.flagCountryOf(nm))
    }

    /** The phone's real internet connection (Wi-Fi, mobile data, ethernet): never the VPN. Null = no internet. */
    @Suppress("DEPRECATION")
    private fun phoneNetwork(): android.net.Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return null
        var best: android.net.Network? = null
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
            if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n
            if (best == null) best = n
        }
        return best
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
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedDown = tv("↓ —", 19f, Palette.mint, 2)
        speedUp = tv("↑ —", 19f, Palette.cyan, 2)
        speedRow.addView(speedDown!!, LinearLayout.LayoutParams(0, WRAP, 1f))
        speedRow.addView(speedUp!!, LinearLayout.LayoutParams(0, WRAP, 1f))
        speed.addView(speedRow, lp(MATCH, WRAP, 10))
        speedTotals = tv("", 11.5f, Palette.muted, 0)
        speed.addView(speedTotals!!, lp(MATCH, WRAP, 8))
        speedNote = tv("", 10f, Palette.dim, 0)
        speed.addView(speedNote!!, lp(MATCH, WRAP, 6))
        col.addView(speed, lp(MATCH, WRAP, 12))
        bindSpeed(null)

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

    /** null = not connected. Everything shown comes from TrafficMonitor counters, never from estimates. */
    private fun bindSpeed(sample: TrafficMonitor.Sample?) {
        if (sample == null) {
            speedDown?.text = "↓ —"; speedUp?.text = "↑ —"
            speedTotals?.text = ""
            speedNote?.text = "Connect to measure live speed."
            return
        }
        if (!sample.available) {
            speedDown?.text = "↓ —"; speedUp?.text = "↑ —"
            speedTotals?.text = ""
            speedNote?.text = "Traffic counters are not supported on this device, so speed cannot be shown."
            return
        }
        speedDown?.text = "↓ " + TrafficMonitor.formatSpeed(sample.downBytesPerSec)
        speedUp?.text = "↑ " + TrafficMonitor.formatSpeed(sample.upBytesPerSec)
        speedTotals?.text = "Downloaded " + TrafficMonitor.formatBytes(sample.totalDown) + "  •  Uploaded " + TrafficMonitor.formatBytes(sample.totalUp)
        speedNote?.text = "Measured from this app's network counters this session (encrypted traffic, includes protocol overhead)."
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
        settingRow(g1, Icon.SHIELD, "Split tunneling", "انتخاب برنامه‌های داخل یا خارج تونل • Xray", splitSummary()) { showSplitTunnelDialog() }
        settingRow(g1, Icon.COPY, "Copy diagnostics", "بدون رمز و توکن", null) { copyDiagnostics() }
        settingRow(g1, Icon.TRASH, "Clear saved config", "پاک‌کردن کانفیگ ذخیره‌شده روی گوشی", null) { clearConfig() }
        col.addView(g1, lp(MATCH, WRAP))

        val g2 = GlassCard(this, 20f, pad = 4)
        settingRow(g2, Icon.SHIELD, "Privacy", "چه چیزی روی گوشی ذخیره می‌شود", null) { showPrivacy() }
        settingRow(g2, Icon.INFO, "App version", packageName, versionName()) { }
        settingRow(g2, Icon.REFRESH, "App updates", "بررسی نسخه جدید در GitHub و نصب APK", "Check") { checkForUpdates() }
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

    private fun splitSummary(): String {
        val count = prefs.getStringSet("split_packages", emptySet())?.size ?: 0
        return if (count == 0) "All apps • default" else "$count selected"
    }

    private fun showSplitTunnelDialog() {
        val pm = packageManager
        val selected = (prefs.getStringSet("split_packages", emptySet()) ?: emptySet()).toMutableSet()
        var mode = prefs.getString("split_mode", "exclude") ?: "exclude"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(10), dpi(16), dpi(4)) }
        root.addView(tv("Choose how apps use the VPN", 14f, Palette.text, 2), lp(MATCH, WRAP, 4))
        root.addView(tv("Include mode: only selected apps use the tunnel. Exclude mode: selected apps bypass it. Currently applied to Xray profiles; the WireGuard/AmneziaWG backend does not expose per-app routing.", 11f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeButton(label: String, value: String): TextView = tv(label, 11f, if (mode == value) Palette.ice else Palette.muted, 2).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpi(12).toFloat(); setColor(if (mode == value) Palette.bg2 else Palette.bg1) ; setStroke(dpi(1), if (mode == value) Palette.cyan else Palette.stroke) }
            setPadding(dpi(10), dpi(11), dpi(10), dpi(11))
            setOnClickListener { mode = value; modes.removeAllViews(); modes.addView(modeButton("Only selected apps", "include"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dpi(5) }); modes.addView(modeButton("Exclude selected", "exclude"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(5) }) }
        }
        modes.addView(modeButton("Only selected apps", "include"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dpi(5) })
        modes.addView(modeButton("Exclude selected", "exclude"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(5) })
        root.addView(modes, lp(MATCH, WRAP, 12, 10))
        val search = EditText(this).apply { hint = "Search installed apps"; textSize = 13f; singleLine = true; setPadding(dpi(12), dpi(9), dpi(12), dpi(9)); background = fieldBackground(false) }
        root.addView(search, lp(MATCH, dpi(46), 2, 8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, dpi(350)))
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA).filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }.sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT) } }.getOrDefault(emptyList())
        fun renderApps(query: String) {
            list.removeAllViews()
            val filtered = apps.filter { pm.getApplicationLabel(it).toString().contains(query, true) }
            filtered.forEach { info ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(8), dpi(7), dpi(8), dpi(7)); background = GradientDrawable().apply { cornerRadius = dpi(13).toFloat(); setColor(Palette.bg1) } }
                val icon = ImageView(this).apply { setImageDrawable(runCatching { pm.getApplicationIcon(info) }.getOrNull()); scaleType = ImageView.ScaleType.FIT_CENTER }
                row.addView(icon, lp(dpi(38), dpi(38)))
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(10), 0, dpi(4), 0) }
                labels.addView(tv(pm.getApplicationLabel(info).toString(), 12.5f, Palette.text, 1))
                labels.addView(tv(info.packageName, 9f, Palette.muted, 0))
                row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
                val check = android.widget.CheckBox(this).apply { buttonTintList = ColorStateList.valueOf(Palette.cyan); isChecked = selected.contains(info.packageName); setOnCheckedChangeListener { _, checked -> if (checked) selected.add(info.packageName) else selected.remove(info.packageName) } }
                row.addView(check, lp(WRAP, WRAP))
                list.addView(row, lp(MATCH, WRAP, 3, 3))
            }
            if (filtered.isEmpty()) list.addView(tv("No matching apps", 12f, Palette.muted, 0).apply { gravity = Gravity.CENTER }, lp(MATCH, dpi(50)))
        }
        renderApps("")
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps(s?.toString().orEmpty()) }; override fun afterTextChanged(s: Editable?) {} })
        dialogBuilder().setTitle("SPLIT TUNNELING").setView(root)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .setPositiveButton("Save", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        prefs.edit().putStringSet("split_packages", selected.toSet()).putString("split_mode", mode).apply()
                        dialog.dismiss()
                        toast("Split tunneling saved. Reconnect to apply.")
                        render()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        selected.clear(); mode = "exclude"
                        prefs.edit().remove("split_packages").putString("split_mode", "exclude").apply()
                        dialog.dismiss(); toast("Split tunneling reset."); render()
                    }
                }
                dialog.show()
            }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        fun parts(value: String): List<Int> = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
        val a = parts(remote); val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return remote != current
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun checkForUpdates() {
        toast("Checking GitHub releases…")
        io.execute {
            try {
                val url = URL("https://api.github.com/repos/HRMPMADEUA/TIREXNET/releases/latest")
                val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 15000; setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "TIREXNET-Android") }
                val body = connection.inputStream.bufferedReader().use { it.readText() }; connection.disconnect()
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "").removePrefix("v")
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                var apkName = "TIREXNET.apk"
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", true)) { apkUrl = asset.optString("browser_download_url", ""); apkName = name; break }
                }
                runOnUiThread {
                    if (tag.isBlank()) toast("GitHub release version was not found.")
                    else if (!isNewerVersion(tag, versionName())) toast("TIREXNET is up to date (${versionName()}).")
                    else if (apkUrl.isBlank()) toast("Release $tag found, but no APK asset was uploaded.")
                    else dialogBuilder().setTitle("TIREXNET update available")
                        .setMessage("Installed: ${versionName()}\nLatest: $tag\n\nDownload and install $apkName?")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(apkUrl, apkName) }.show()
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Update check failed: ${e.message ?: "GitHub unavailable"}") }
            }
        }
    }

    private fun downloadAndInstall(apkUrl: String, apkName: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow TIREXNET to install updates, then tap Check again.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        toast("Downloading update…")
        io.execute {
            var sessionId = -1
            try {
                val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; setRequestProperty("User-Agent", "TIREXNET-Android") }
                if (connection.responseCode !in 200..299) error("Download HTTP ${connection.responseCode}")
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, -1).use { output -> connection.inputStream.use { input -> input.copyTo(output) }; session.fsync(output) }
                    val intent = Intent(this, AppInstallReceiver::class.java).setAction(AppInstallReceiver.ACTION_RESULT)
                    val pending = PendingIntent.getBroadcast(this, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pending.intentSender)
                }
                connection.disconnect()
                runOnUiThread { toast("Download complete. Android installer opened.") }
            } catch (e: Throwable) {
                if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
                runOnUiThread { toast("Update install failed: ${e.message ?: "unknown error"}") }
            }
        }
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
        if (registerMode) {
            confirmInput = field("Confirm password", true)
            card.addView(confirmInput!!, lp(MATCH, dpi(54), 10))
        }
        val btn = primaryButton(if (registerMode) "Create account" else "Sign in") { if (registerMode) register() else login() }
        loginButton = btn
        card.addView(btn, lp(MATCH, dpi(54), 16))
        col.addView(card, lp(MATCH, WRAP, 26))
        col.addView(
            tv(if (registerMode) "حساب داری؟ وارد شو" else "حساب نداری؟ ثبت‌نام کن", 13.5f, Palette.cyan, 1).apply {
                gravity = Gravity.CENTER
                minHeight = dpi(48)
                isClickable = true
                setOnClickListener { registerMode = !registerMode; render() }
            },
            lp(MATCH, WRAP, 10)
        )
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
        background = Palette.glass(this@MainActivity, 16f, Palette.stroke, Palette.fieldFill, Palette.fieldFill)
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

    private fun applyStatus(rawMessage: String, active: Boolean) {
        // Engine errors may echo parts of the config (keys, hosts, links): scrub before storing or showing.
        val message = ConfigSanitizer.redact(rawMessage)
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
            if (connectedAtRt == 0L) { connectedAtRt = SystemClock.elapsedRealtime(); traffic.reset() }
        } else connectedAtRt = 0L
        val changed = state != next
        state = next
        prefs.edit().putString("connection_message", message).putBoolean("connection_active", active).apply()
        updateConnectionUi()
        if (changed) {
            if (next != OrbState.CONNECTED) bindSpeed(null)
            bindHomePing()
            refreshPolling()
        }
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
            toast("فرمت کانفیگ شناسایی نشد. VLESS / VMess / Trojan / Shadowsocks / WireGuard یا OpenVPN لازم است.")
            showTab(1)
            return
        }
        pendingRaw = raw
        pendingEngine = engine
        // The notification and the Quick Settings tile show this name; they can run without the app window.
        prefs.edit().putString("active_engine", engine.name)
            .putString(TirexNotifier.KEY_PROFILE_NAME, selectedItem()?.name.orEmpty()).apply()

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
            ConfigEngine.OPENVPN -> {
                applyStatus("در حال راه‌اندازی OpenVPN…", false)
                OpenVpnEngine.start(this, pendingRaw) { message, active ->
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
        if (engine == "OPENVPN" || OpenVpnEngine.isActive) {
            issued = true
            OpenVpnEngine.stop { message, active -> runOnUiThread { applyStatus(message, active) } }
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
            setAddStatus("Importing…", false)
            importUri(uri)
        }
    }

    // =====================================================================
    // Config helpers
    // =====================================================================

    private fun currentConfig(): String = prefs.getString("last_config", "") ?: ""

    // Local profiles are kept on this device and remain available after app restarts.
    private fun loadLocalProfiles(): List<ServerItem> {
        val savedText = prefs.getString("saved_server_profiles", "[]") ?: "[]"
        val array = runCatching { JSONArray(savedText) }.getOrElse { JSONArray() }
        val result = ArrayList<ServerItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val raw = obj.optString("raw", "").trim()
            if (raw.isBlank()) continue
            val meta = ProfileParser.parse(raw)
            // Older versions saved the hostname as the name. Repair those on load and never display a hostname.
            val name = ProfileParser.bestName(listOf(obj.optString("name", ""), meta.name), result.size + 1)
            result.add(ServerItem(name, raw, meta.engine, meta, true,
                normalizeCountry(obj.optString("country", "")).ifEmpty { ProfileParser.flagCountryOf(name) }))
        }
        return result
    }

    /** Returns false when Android could not write the profiles, so callers never claim a save that failed. */
    private fun persistLocalProfiles(profiles: List<ServerItem>): Boolean {
        val array = JSONArray()
        profiles.filter { it.local }.forEach { profile ->
            array.put(JSONObject().put("name", profile.name).put("raw", profile.raw).put("country", profile.country))
        }
        return prefs.edit().putString("saved_server_profiles", array.toString()).commit()
    }

    private fun mergeSavedAndRemote(remote: List<ServerItem>): List<ServerItem> {
        val merged = ArrayList<ServerItem>()
        val seen = HashSet<String>()
        for (profile in loadLocalProfiles()) {
            if (seen.add(profile.raw.trim())) merged.add(profile)
        }
        for (profile in remote) {
            if (seen.add(profile.raw.trim())) merged.add(profile)
        }
        return merged
    }

    private fun normalizeCountry(v: String): String {
        val c = v.trim().uppercase(Locale.US)
        return if (c.length == 2 && c.all { it in 'A'..'Z' }) c else ""
    }

    private fun flagEmoji(countryCode: String): String {
        val code = countryCode.uppercase(Locale.US)
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🌐"
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(first) + String(second)
    }

    // ---------------------------------------------------------------- Import

    private data class ImportOutcome(val added: List<ServerItem>, val duplicates: Int, val invalid: Int, val persisted: Boolean)

    /** One structured config stays whole; otherwise every non-empty line is a separate share link. */
    private fun splitConfigs(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("{") || t.contains("[interface]", ignoreCase = true) ||
            ConfigDetector.detect(t) == ConfigEngine.OPENVPN) return listOf(t)
        return t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    /** Parses, de-duplicates and saves. Runs off the main thread. Never logs or returns config content. */
    private fun doImport(text: String, existingRaws: Set<String>, country: String = ""): ImportOutcome {
        val candidates = splitConfigs(text)
        val saved = loadLocalProfiles().toMutableList()
        val known = HashSet<String>(existingRaws)
        saved.forEach { known.add(it.raw.trim()) }
        val added = ArrayList<ServerItem>()
        var dup = 0
        var bad = 0
        for (c in candidates) {
            val meta = ProfileParser.parse(c)
            val structured = c.startsWith("{")
            val ok = when (meta.engine) {
                ConfigEngine.UNKNOWN -> false
                ConfigEngine.XRAY -> structured || meta.host.isNotBlank()
                else -> meta.host.isNotBlank()
            }
            if (!ok) { bad++; continue }
            if (!known.add(c.trim())) { dup++; continue }
            val name = ProfileParser.bestName(listOf(meta.name), saved.size + added.size + 1)
            added.add(ServerItem(name, c.trim(), meta.engine, meta, true, country.ifEmpty { ProfileParser.flagCountryOf(name) }))
        }
        if (added.isEmpty()) return ImportOutcome(emptyList(), dup, bad, true)
        val persisted = persistLocalProfiles(added + saved)
        return ImportOutcome(if (persisted) added else emptyList(), dup, bad, persisted)
    }

    private fun importText(text: String, country: String = "") {
        val existing = servers.map { it.raw.trim() }.toSet()
        io.execute {
            val outcome = doImport(text, existing, country)
            runOnUiThread { if (!isFinishing && !isDestroyed) applyImport(outcome) }
        }
    }

    private fun importUri(uri: Uri) {
        val existing = servers.map { it.raw.trim() }.toSet()
        io.execute {
            val text = try {
                contentResolver.openInputStream(uri)?.use { readLimited(it) }.orEmpty()
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) importFailed("فایل خوانده نشد: " + ConfigSanitizer.redact(e.message)) }
                return@execute
            }
            val outcome = doImport(text, existing)
            runOnUiThread { if (!isFinishing && !isDestroyed) applyImport(outcome) }
        }
    }

    private fun importFailed(message: String) {
        toast(message)
        setAddStatus(message, true)
    }

    private fun applyImport(o: ImportOutcome) {
        if (!o.persisted) {
            importFailed("ذخیره‌ی پروفایل ناموفق بود. حافظه‌ی برنامه قابل نوشتن نیست.")
            return
        }
        if (o.added.isEmpty()) {
            val msg = if (o.duplicates > 0 && o.invalid == 0) "این کانفیگ قبلاً در لیست هست."
            else "کانفیگ پشتیبانی‌شده‌ای پیدا نشد. VLESS / VMess / Trojan / Shadowsocks / WireGuard / AmneziaWG / OpenVPN لازم است."
            importFailed(msg)
            return
        }
        servers = mergeSavedAndRemote(servers.filterNot { it.local })
        serversState = ServersState.READY
        serversError = ""
        if (currentConfig().isBlank()) setConfig(o.added[0].raw) else rebuildServerList(-1)
        var msg = if (o.added.size == 1) "پروفایل «" + o.added[0].name + "» اضافه شد." else "${o.added.size} پروفایل اضافه شد."
        if (o.duplicates > 0) msg += " (${o.duplicates} تکراری رد شد)"
        if (o.invalid > 0) msg += " (${o.invalid} مورد نامعتبر)"
        toast(msg)
        if (addOverlay != null) { closeAddServer(); showTab(1) }
    }

    // ---------------------------------------------------------------- Selection

    private fun setConfig(raw: String) {
        prefs.edit().putString("last_config", raw).apply()
        onConfigChanged()
        if (serversState == ServersState.READY) rebuildServerList(-1)
    }

    private fun onConfigChanged() {
        bindServerSummary()
        statsEngine?.text = engineLabelShort()
    }

    private fun engineName(engine: ConfigEngine): String = when (engine) {
        ConfigEngine.XRAY -> "XRAY"
        ConfigEngine.WIREGUARD -> "WIREGUARD"
        ConfigEngine.AMNEZIAWG -> "AMNEZIAWG"
        ConfigEngine.OPENVPN -> "OPENVPN"
        else -> "NONE"
    }

    /** Selected-server card: profile name, flag and ping only. No engine, host or port. */
    private fun bindServerSummary() {
        val item = selectedItem()
        if (item == null) {
            homeServerName?.text = "No server selected"
            homePing?.text = "برای انتخاب سرور بزن"
            homePing?.setTextColor(Palette.muted)
            fillFlag(homeFlag, "", 44)
            return
        }
        homeServerName?.text = item.name
        fillFlag(homeFlag, item.country, 44)
        bindHomePing()
    }

    private fun pasteClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = manager.primaryClip
            if (clip == null || clip.itemCount == 0) { importFailed("کلیپ‌بورد خالی است."); return }
            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
            if (text.isEmpty()) { importFailed("کلیپ‌بورد خالی است."); return }
            setAddStatus("Importing…", false)
            importText(text)
        } catch (_: Exception) {
            importFailed("خواندن کلیپ‌بورد ناموفق بود.")
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
        dialogBuilder()
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
                prefs.edit().putString("token", newToken).putString("token_base", lastBase).apply()
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

    /** Creates an account on the first reachable server (Iranian server, then Worker) and signs in. */
    private fun register() {
        val user = usernameInput?.text?.toString().orEmpty().trim()
        val pass = passwordInput?.text?.toString().orEmpty()
        val confirm = confirmInput?.text?.toString().orEmpty()
        if (!Regex("^[A-Za-z0-9_.-]{3,32}$").matches(user)) { toast("نام کاربری ۳ تا ۳۲ حرف انگلیسی، عدد یا _ . - باشد."); return }
        if (pass.length < 8) { toast("رمز عبور حداقل ۸ کاراکتر باشد."); return }
        if (pass != confirm) { toast("تکرار رمز عبور یکسان نیست."); return }
        loginButton?.apply { text = "Creating…"; alpha = 0.6f; isEnabled = false }
        io.execute {
            try {
                val body = JSONObject().put("username", user).put("password", pass)
                var response = request("POST", "/api/register", null, body)
                var newToken = response.optString("token")
                if (newToken.isBlank()) { // server created the account but did not return a token: sign in with it
                    response = request("POST", "/api/login", null, body)
                    newToken = response.optString("token")
                }
                require(newToken.isNotBlank()) { "Token is missing." }
                prefs.edit().putString("token", newToken).putString("token_base", lastBase).apply()
                token = newToken
                runOnUiThread {
                    registerMode = false
                    currentTab = 0
                    serversState = ServersState.IDLE
                    servers = emptyList()
                    render()
                    loadAccount()
                    toast("حساب ساخته شد.")
                }
            } catch (e: Exception) {
                val msg = when ((e as? ApiHttpException)?.code) {
                    404, 405 -> "ثبت‌نام روی این سرور فعال نیست."
                    409 -> "این نام کاربری قبلاً ثبت شده است."
                    null -> "ثبت‌نام ناموفق بود: " + ConfigSanitizer.redact(e.message ?: "network error")
                    else -> "ثبت‌نام ناموفق بود (HTTP ${(e as ApiHttpException).code})."
                }
                runOnUiThread {
                    loginButton?.apply { text = "Create account"; alpha = 1f; isEnabled = true }
                    toast(msg)
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
                    prefs.edit().remove("token").remove("token_base").remove("remote_servers_cache").apply()
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

    // Last successfully downloaded subscription list, kept on the phone for offline use.
    private fun saveRemoteCache(remote: List<ServerItem>) {
        val arr = JSONArray()
        remote.forEach { arr.put(JSONObject().put("name", it.name).put("raw", it.raw).put("country", it.country)) }
        prefs.edit().putString("remote_servers_cache", arr.toString()).apply()
    }

    private fun loadRemoteCache(): List<ServerItem> {
        val text = prefs.getString("remote_servers_cache", "[]") ?: "[]"
        val arr = runCatching { JSONArray(text) }.getOrElse { JSONArray() }
        val out = ArrayList<ServerItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("raw", "").trim()
            if (raw.isEmpty()) continue
            val meta = ProfileParser.parse(raw)
            val name = ProfileParser.bestName(listOf(o.optString("name", ""), meta.name), out.size + 1)
            out.add(ServerItem(name, raw, meta.engine, meta, false,
                normalizeCountry(o.optString("country", "")).ifEmpty { ProfileParser.flagCountryOf(name) }))
        }
        return out
    }

    private fun loadServers() {
        if (token.isBlank()) {
            servers = loadLocalProfiles()
            serversState = ServersState.READY
            rebuildServerList(-1)
            if (servers.isEmpty()) toast("ابتدا وارد حساب شو یا یک پروفایل محلی ذخیره کن.")
            return
        }
        // Show the last downloaded list right away (works with no internet / without a VPN),
        // then refresh it in the background. The skeleton is only used on the very first load.
        val cached = loadRemoteCache()
        if (cached.isNotEmpty() && servers.isEmpty()) {
            servers = mergeSavedAndRemote(cached)
            serversState = ServersState.READY
        } else if (servers.isEmpty()) {
            serversState = ServersState.LOADING
        }
        rebuildServerList(-1)
        val t = token
        io.execute {
            try {
                val data = request("GET", "/api/me/configs", t, null)
                val arr = data.optJSONArray("configs")
                    ?: data.optJSONArray("profiles")
                    ?: data.optJSONArray("items")
                    ?: throw IllegalStateException("Worker response has no configs/profiles array.")
                val remote = ArrayList<ServerItem>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    val raw = when (item) {
                        is JSONObject -> item.optString("config", item.optString("uri", item.optString("raw", item.optString("content", item.optString("link", "")))))
                        is String -> item
                        else -> ""
                    }.trim()
                    if (raw.isNotBlank()) {
                        val meta = ProfileParser.parse(raw)
                        val obj = item as? JSONObject
                        // Subscription display name first, then the name inside the config itself.
                        // A hostname is never used as a title.
                        val keys = listOf("name", "remarks", "remark", "title", "ps", "label", "display_name", "displayName")
                        val extra = ArrayList<String>()
                        obj?.keys()?.forEach { k ->
                            val kl = k.lowercase(Locale.US)
                            if (k !in keys && listOf("name", "remark", "title", "label", "tag", "note", "desc").any { kl.contains(it) }) extra.add(k)
                        }
                        val given = (keys + extra).map { k -> obj?.optString(k, "").orEmpty() }
                        val displayName = ProfileParser.bestName(given + meta.name, remote.size + 1)
                        // Country only from explicit metadata supplied with the profile.
                        val explicit = listOf("country_code", "countryCode", "cc", "country")
                            .map { k -> normalizeCountry(obj?.optString(k, "").orEmpty()) }
                            .firstOrNull { it.isNotEmpty() }.orEmpty()
                        // No explicit field: use the flag already written in the profile name.
                        val country = explicit.ifEmpty { ProfileParser.flagCountryOf(displayName) }
                        remote.add(ServerItem(displayName, raw, meta.engine, meta, false, country))
                    }
                }
                if (remote.isNotEmpty()) saveRemoteCache(remote) // keep for the next launch without internet
                runOnUiThread {
                    servers = mergeSavedAndRemote(remote)
                    serversState = ServersState.READY
                    serversError = ""
                    rebuildServerList(-1)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // No internet / server unreachable: fall back to the saved list and local profiles.
                    val offline = mergeSavedAndRemote(loadRemoteCache())
                    if (offline.isNotEmpty()) {
                        servers = offline
                        serversState = ServersState.READY
                        serversError = ConfigSanitizer.redact(e.message ?: "endpoint unavailable")
                        rebuildServerList(-1)
                        toast("اتصال به سرور برقرار نشد؛ لیست ذخیره‌شده‌ی قبلی نمایش داده شد.")
                    } else {
                        serversError = ConfigSanitizer.redact(e.message ?: "endpoint unavailable")
                        serversState = ServersState.ERROR
                        rebuildServerList(-1)
                    }
                }
            }
        }
    }

    @Volatile private var lastBase = ""
    @Volatile private var iranDownUntil = 0L

    /**
     * Tries the Iranian server first and falls back to the Worker when it cannot be reached (network error,
     * timeout, 502-504). A token only works on the server that issued it, so authenticated calls go to that
     * server first, and a 401 from any other server is never treated as "session expired".
     */
    private fun request(method: String, path: String, bearer: String?, body: JSONObject?): JSONObject {
        val all = listOf(ApiConfig.IRAN_BASE, ApiConfig.WORKER_BASE).filter { it.isNotBlank() }
        val tokenBase = prefs.getString("token_base", "").orEmpty().ifBlank { ApiConfig.WORKER_BASE } // old tokens came from the Worker
        val order = if (!bearer.isNullOrBlank() && tokenBase in all) listOf(tokenBase) + all.filter { it != tokenBase } else all
        var last: Exception? = null
        for ((i, base) in order.withIndex()) {
            val hasNext = i < order.lastIndex
            val isIran = base == ApiConfig.IRAN_BASE
            if (isIran && hasNext && SystemClock.elapsedRealtime() < iranDownUntil) continue
            try {
                val r = requestOnce(base, method, path, bearer, body, if (hasNext) ApiConfig.FAST_FAIL_TIMEOUT_MS else ApiConfig.NORMAL_TIMEOUT_MS)
                if (isIran) iranDownUntil = 0L
                lastBase = base
                return r
            } catch (e: ApiHttpException) {
                if (e.code in 502..504 && hasNext) { if (isIran) iranDownUntil = SystemClock.elapsedRealtime() + ApiConfig.IRAN_RETRY_AFTER_MS; last = e; continue }
                if (e.code == 401 && !bearer.isNullOrBlank() && base != tokenBase) { last = ApiUnavailableException("Session belongs to another server."); continue }
                throw e
            } catch (e: java.io.IOException) {
                if (isIran) iranDownUntil = SystemClock.elapsedRealtime() + ApiConfig.IRAN_RETRY_AFTER_MS
                last = e
            }
        }
        throw (last as? java.io.IOException) ?: ApiUnavailableException(last?.message ?: "No server available.")
    }

    private fun requestOnce(base: String, method: String, path: String, bearer: String?, body: JSONObject?, timeoutMs: Int): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = ApiConfig.NORMAL_TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = c.responseCode
            val responseText = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiHttpException(code, responseText)
            return JSONObject(responseText)
        } finally {
            c.disconnect()
        }
    }

    private fun confirmLogout() {
        dialogBuilder()
            .setMessage("از حساب خارج می‌شوی؟")
            .setPositiveButton("خروج") { _, _ -> logout() }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun logout() {
        val old = token
        token = ""
        prefs.edit().remove("token").remove("token_base").remove("remote_servers_cache").apply()
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
        dialogBuilder()
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
    // Add Server page (overlay): quick import, manual entry, raw editor
    // =====================================================================

    private fun openAddServer() {
        val root = rootRef ?: return
        if (addOverlay != null) return
        manualValues.clear()
        manualValues.putAll(ManualProfile.defaultsFor(manualProtocol))
        manualCountry = ""

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg0)
            isClickable = true // swallow touches so the page underneath is inert
            setPadding(insetBox[0], insetBox[1], insetBox[2], insetBox[3])
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(10), dpi(16), dpi(8))
        }
        bar.addView(roundButton(Icon.BACK, "Back") { closeAddServer() }, lp(dpi(44), dpi(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, 0, 0) }
        titles.addView(tv("Add server", 22f, Palette.text, 2))
        titles.addView(tv("Import a config or enter one manually", 12f, Palette.muted, 0), lp(WRAP, WRAP, 3))
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        page.addView(bar, lp(MATCH, WRAP))

        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(6), dpi(20), dpi(28)) }
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        page.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addScroll = sv

        // --- 1. Quick import (saved automatically)
        val quick = GlassCard(this, 22f, pad = 16)
        quick.addView(tv("QUICK IMPORT", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        quick.addView(tv("Valid configs are added to your list immediately. No extra confirmation.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(actionTile(Icon.PASTE, "Paste", "From clipboard") { pasteClipboard() },
            LinearLayout.LayoutParams(0, dpi(92), 1f).apply { rightMargin = dpi(5) })
        tiles.addView(actionTile(Icon.FILE, "File", "Import from file") { pickFile() },
            LinearLayout.LayoutParams(0, dpi(92), 1f).apply { leftMargin = dpi(5) })
        quick.addView(tiles, lp(MATCH, WRAP, 14))
        val status = tv("", 12f, Palette.muted, 1).apply { visibility = View.GONE }
        addStatus = status
        quick.addView(status, lp(MATCH, WRAP, 12))
        col.addView(quick, lp(MATCH, WRAP, 8))

        // --- 2. Manual entry
        val manual = GlassCard(this, 22f, pad = 16)
        manual.addView(tv("MANUAL ENTRY", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        manual.addView(tv("Choose a protocol; only its fields are shown.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val protoScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val protoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        protoScroll.addView(protoRow, FrameLayout.LayoutParams(WRAP, WRAP))
        manualProtoRow = protoRow
        manual.addView(protoScroll, lp(MATCH, WRAP, 12))
        val fieldsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        manualFieldsBox = fieldsBox
        manual.addView(fieldsBox, lp(MATCH, WRAP))
        val err = tv("", 12f, Palette.red, 1).apply { visibility = View.GONE }
        manualError = err
        manual.addView(err, lp(MATCH, WRAP, 12))
        val save = primaryButton("Save server") { saveManual() }
        manualSaveBtn = save
        manual.addView(save, lp(MATCH, dpi(52), 14))
        col.addView(manual, lp(MATCH, WRAP, 14))

        // --- 3. Raw editor
        val rawCard = GlassCard(this, 22f, pad = 16)
        rawCard.addView(tv("RAW CONFIG", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        rawCard.addView(tv("For advanced options: paste Xray JSON, a share link or a full WireGuard / AmneziaWG config.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val raw = EditText(this).apply {
            hint = "vless://…  |  { \"outbounds\": … }  |  [Interface] …"
            textSize = 12f
            setTextColor(Palette.text)
            setHintTextColor(Palette.dim)
            gravity = Gravity.TOP or Gravity.START
            minLines = 4; maxLines = 9
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
            background = Palette.glass(this@MainActivity, 14f, Palette.stroke, Palette.fieldFill, Palette.fieldFill)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            contentDescription = "Raw configuration"
        }
        rawInput = raw
        rawCard.addView(raw, lp(MATCH, WRAP, 12))
        rawCard.addView(ghostButton("Validate & save") {
            val t = rawInput?.text?.toString().orEmpty().trim()
            if (t.isEmpty()) importFailed("چیزی برای ذخیره وارد نشده است.")
            else { setAddStatus("Importing…", false); importText(t) }
        }, lp(MATCH, dpi(48), 12))
        col.addView(rawCard, lp(MATCH, WRAP, 14))

        buildProtocolChips()
        rebuildManualFields()

        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        addOverlay = page
        page.alpha = 0f
        page.animate().alpha(1f).setDuration(200).start()
        staggerIn(col)
    }

    private fun closeAddServer() {
        val v = addOverlay ?: return
        addOverlay = null
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
        addStatus = null; manualFieldsBox = null; manualProtoRow = null; manualError = null
        manualSaveBtn = null; rawInput = null; addScroll = null
        manualFieldViews.clear()
        v.animate().alpha(0f).setDuration(160).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }

    private fun actionTile(icon: Icon, label: String, sub: String, onClick: () -> Unit): View {
        val c = GlassCard(this, 18f, pad = 10).apply {
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            pressable(0.96f)
        }
        c.addView(IconView(this, icon, Palette.cyan), LinearLayout.LayoutParams(dpi(28), dpi(28)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(tv(label, 14f, Palette.text, 2).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 8))
        c.addView(tv(sub, 10.5f, Palette.muted, 0).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, 2))
        return c
    }

    /** Progress and error line for imports. Success closes the page and the list shows the new profile. */
    private fun setAddStatus(message: String, error: Boolean) {
        val s = addStatus ?: return
        s.text = message
        s.setTextColor(if (error) Palette.red else Palette.cyan)
        s.visibility = View.VISIBLE
        manualSaveBtn?.apply {
            isEnabled = error
            alpha = if (error) 1f else 0.6f
            text = if (error) "Save server" else "Saving…"
        }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        tv(label, 12.5f, if (selected) Palette.onPrimary else Palette.text, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dpi(40)
            setPadding(dpi(14), dpi(9), dpi(14), dpi(9))
            background = if (selected)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.cyan, Palette.mint)).apply { cornerRadius = dp(20f) }
            else Palette.glass(this@MainActivity, 20f)
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            pressable(0.95f)
        }

    private fun buildProtocolChips() {
        val row = manualProtoRow ?: return
        row.removeAllViews()
        for (p in ManualProtocol.values()) {
            row.addView(chip(p.label, p == manualProtocol) {
                if (p != manualProtocol) {
                    val name = manualValues["name"].orEmpty()
                    val address = manualValues["address"].orEmpty()
                    manualProtocol = p
                    manualValues.clear()
                    manualValues.putAll(ManualProfile.defaultsFor(p))
                    manualValues["name"] = name
                    manualValues["address"] = address
                    manualError?.visibility = View.GONE
                    buildProtocolChips()
                    rebuildManualFields()
                }
            }, lp(WRAP, WRAP, 0, 0, 0, 8))
        }
    }

    private fun fieldBackground(error: Boolean) =
        Palette.glass(this, 14f, if (error) Palette.red else Palette.stroke, Palette.fieldFill, Palette.fieldFill)

    private fun rebuildManualFields() {
        val box = manualFieldsBox ?: return
        box.removeAllViews()
        manualFieldViews.clear()
        for (f in ManualProfile.fields(manualProtocol)) {
            val cond = f.showWhen
            if (cond != null && !cond(manualValues)) continue
            box.addView(tv(f.label + if (f.required) " *" else "", 11f, Palette.muted, 1), lp(MATCH, WRAP, 12))
            if (f.kind == FieldKind.CHOICE) {
                val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                hs.addView(r, FrameLayout.LayoutParams(WRAP, WRAP))
                val current = manualValues[f.key].orEmpty()
                for (c in f.choices) {
                    r.addView(chip(if (c.isEmpty()) "none" else c, c == current) {
                        manualValues[f.key] = c
                        rebuildManualFields() // dependent fields (TLS, Reality, transport) appear/disappear
                    }, lp(WRAP, WRAP, 0, 0, 0, 8))
                }
                box.addView(hs, lp(MATCH, WRAP, 6))
            } else {
                val et = EditText(this).apply {
                    hint = f.hint
                    textSize = 14f
                    setTextColor(Palette.text)
                    setHintTextColor(Palette.dim)
                    setSingleLine(true)
                    setPadding(dpi(14), 0, dpi(14), 0)
                    background = fieldBackground(false)
                    inputType = when (f.kind) {
                        FieldKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                        FieldKind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG // Persian and English both render correctly
                    contentDescription = f.label
                    setText(manualValues[f.key].orEmpty())
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) { manualValues[f.key] = s?.toString().orEmpty() }
                    })
                }
                manualFieldViews[f.key] = et
                box.addView(et, lp(MATCH, dpi(50), 6))
            }
        }
        // Optional flag source for manual profiles (the only reliable country metadata we have for them).
        box.addView(tv("Country code (optional)", 11f, Palette.muted, 1), lp(MATCH, WRAP, 12))
        val cc = EditText(this).apply {
            hint = "e.g. DE, US, TR"
            textSize = 14f
            setTextColor(Palette.text)
            setHintTextColor(Palette.dim)
            setSingleLine(true)
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(2))
            setPadding(dpi(14), 0, dpi(14), 0)
            background = fieldBackground(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "Country code"
            setText(manualCountry)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { manualCountry = s?.toString().orEmpty() }
            })
        }
        box.addView(cc, lp(MATCH, dpi(50), 6))
    }

    private fun saveManual() {
        val values = HashMap(manualValues)
        for ((_, v) in manualFieldViews) v.background = fieldBackground(false)
        when (val out = ManualProfile.build(manualProtocol, values)) {
            is ManualOutcome.Invalid -> {
                manualError?.apply { text = out.message; visibility = View.VISIBLE }
                manualFieldViews[out.key]?.let { it.background = fieldBackground(true); it.requestFocus() }
            }
            is ManualOutcome.Ok -> {
                manualError?.visibility = View.GONE
                setAddStatus("Saving…", false)
                importText(out.raw, normalizeCountry(manualCountry))
            }
        }
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dpi(left), dpi(top), dpi(right), dpi(bottom)) }

    /** Dialogs follow the in-app theme instead of always using the system dark one. */
    private fun dialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(
        this,
        if (Palette.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    )

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val VPN_REQUEST = 71
        private const val PICK_CONFIG = 72
    }
}
