
package com.tirexnet.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
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
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.tirexnet.app.engine.ConfigDetector
import com.tirexnet.app.engine.EngineProfiles
import com.tirexnet.app.engine.ApiConfig
import com.tirexnet.app.engine.ApiHttpException
import com.tirexnet.app.engine.ApiUnavailableException
import com.tirexnet.app.engine.ConfigEngine
import com.tirexnet.app.engine.ConfigSanitizer
import com.tirexnet.app.engine.DnsChangerProfiles
import com.tirexnet.app.engine.DnsChangerVpnService
import com.tirexnet.app.engine.FieldKind
import com.tirexnet.app.engine.ManualOutcome
import com.tirexnet.app.engine.ManualProfile
import com.tirexnet.app.engine.ManualProtocol
import com.tirexnet.app.engine.MhrvEngine
import com.tirexnet.app.engine.MhrvCertificate
import com.tirexnet.app.engine.OpenVpnEngine
import com.tirexnet.app.engine.PingKind
import com.tirexnet.app.engine.PingResult
import com.tirexnet.app.engine.PingTester
import com.tirexnet.app.engine.ProfileMeta
import com.tirexnet.app.engine.ProfileParser
import com.tirexnet.app.engine.TirexActions
import com.tirexnet.app.engine.TirexNotifier
import com.tirexnet.app.engine.TrafficMonitor
import com.tirexnet.app.engine.StormDnsEngine
import com.tirexnet.app.engine.SniSpoofEngine
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
import com.tirexnet.app.ui.SlideConnectControl
import com.tirexnet.app.ui.UsageRing
import com.tirexnet.app.ui.dp
import com.tirexnet.app.ui.dpi
import com.tirexnet.app.ui.pressable
import com.tirexnet.app.ui.tv
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /**
     * [meta] holds the endpoint (host/port) that engines and the latency probe need.
     * It must never be rendered. [country] is an ISO-3166 alpha-2 code from trusted metadata, or "".
     */
    private data class ServerItem(
        val name: String,
        val raw: String,
        val engine: ConfigEngine,
        val meta: ProfileMeta,
        val local: Boolean = false,
        val country: String = "",
        val panelId: String = "",
        val panelName: String = ""
    )
    private class PingHolder(val text: TextView, val button: FrameLayout, val spinner: ProgressBar, val icon: IconView)
    private enum class ServersState { IDLE, LOADING, ERROR, READY }

    private val api = "https://netherrap.extraservices.ir"
    private val io = Executors.newSingleThreadExecutor()
    private val packageExecutor = Executors.newSingleThreadExecutor()
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
    private var sortByPing = false
    private var regionFilter = "ALL"
    private var smartConnectSwitch: Switch? = null
    private var smartConnectRunId = 0
    private var smartConnectPendingCount = 0
    private val smartConnectResults = HashMap<String, PingResult>()
    private var programmaticSmartSwitch = false
    private val connectionLogs = ArrayList<String>()
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
    private var connectSlider: SlideConnectControl? = null
    private var homeRegionLabel: TextView? = null
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
    private var loginPanelLabel: TextView? = null
    private var selectedLoginPanelId: String = "pasarguard"
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
        Palette.dark = prefs.getBoolean("theme_dark", false)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        token = prefs.getString("token", "") ?: ""
        statusMessage = safeSavedStatus(prefs.getString("connection_message", "").orEmpty())
        sortByPing = prefs.getBoolean("sort_by_ping", false)
        regionFilter = prefs.getString("region_filter", "ALL") ?: "ALL"
        connectionLogs.addAll(loadConnectionLogs())

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
        packageExecutor.shutdownNow()
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
            // Keep only a neutral marker in the UI; preserve the real crash for logcat diagnostics.
            runCatching { prefs.edit().putString("last_crash", "unexpected_shutdown").commit() }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showCrashScreen(@Suppress("UNUSED_PARAMETER") marker: String) {
        crashScreenShown = true
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24), dpi(56), dpi(24), dpi(24)) }
        col.addView(TextView(this).apply {
            text = "TIREXNET was closed unexpectedly"
            textSize = 19f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "The app has recovered. Continue to open it again. Your saved profiles and account remain on this device."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dpi(12), 0, dpi(20))
        })
        val go = TextView(this).apply {
            text = "Continue"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(30, 90, 150)); setPadding(0, dpi(14), 0, dpi(14))
            setOnClickListener { prefs.edit().remove("last_crash").commit(); recreate() }
        }
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
        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || DnsChangerVpnService.isActive
        if (real && state != OrbState.CONNECTED) {
            val message = prefs.getString("connection_message", "Connected.") ?: "Connected."
            applyStatus(message.ifBlank { "Connected." }, true)
        } else if (!real && state == OrbState.CONNECTED) {
            applyStatus("Disconnected.", false)
        } else if (!real && state == OrbState.CONNECTING && prefs.getString("active_engine", "") == "DNS_CHANGER") {
            val message = prefs.getString("connection_message", "DNS Changer failed to start.") ?: "DNS Changer failed to start."
            applyStatus(message, false)
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
        titles.addView(tv("PRIVATE CONNECTION", 8f, Palette.muted, 1).apply { letterSpacing = 0.18f }, lp(WRAP, WRAP, 3))
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (token.isNotBlank()) {
            row.addView(
                roundButton(if (Palette.dark) Icon.SUN else Icon.MOON, if (Palette.dark) "Switch to light theme" else "Switch to dark theme") { toggleTheme() },
                lp(dpi(38), dpi(38))
            )
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
        val frame = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = false
            setPadding(0, 0, 0, dpi(100))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(2), dpi(22), dpi(18))
        }
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // Large, soft gradient globe. Tap remains a shortcut; the slide control is the main action.
        val orbSize = minOf(dpi(236), resources.displayMetrics.widthPixels - dpi(88))
        // Put the globe directly in the vertical layout. No FrameLayout/card/halo
        // is placed behind it, so the home screen has only the round globe itself.
        val o = ConnectOrb(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            background = null
            clipToOutline = false
        }
        o.contentDescription = "Connection status globe"
        o.onToggle = { connectFromSlider() }
        orb = o
        col.addView(o, LinearLayout.LayoutParams(orbSize, orbSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val region = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(8), dpi(13), dpi(8))
            background = Palette.glass(this@MainActivity, 13f, Palette.stroke, Palette.glassTop, Palette.glassBottom)
            isClickable = true
            contentDescription = "Choose server region"
            setOnClickListener { showRegionPicker() }
            pressable(0.97f)
        }
        region.addView(IconView(this, Icon.GLOBE, Palette.text), lp(dpi(20), dpi(20)))
        homeRegionLabel = tv(regionLabel(), 12.5f, Palette.text, 1).apply { setPadding(dpi(8), 0, dpi(8), 0) }
        region.addView(homeRegionLabel, LinearLayout.LayoutParams(WRAP, WRAP, 1f))
        region.addView(tv("⌄", 18f, Palette.muted, 1), lp(WRAP, WRAP))
        col.addView(region, lp(WRAP, dpi(42), 2))

        val title = tv(homeHeadline(), 33f, Palette.text, 1).apply {
            gravity = Gravity.START
            letterSpacing = -0.025f
            maxLines = 2
        }
        statusTitle = title
        col.addView(title, lp(MATCH, WRAP, 28, 0, 0, 4))

        val desc = tv("Choose ALL or a country, then slide to connect.", 12.5f, Palette.muted, 0).apply {
            gravity = Gravity.START
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        statusDesc = desc
        col.addView(desc, lp(MATCH, WRAP, 8, 0, 0, 8))

        val timer = tv("00:00:00", 14f, Palette.cyan, 4).apply {
            gravity = Gravity.START
            letterSpacing = 0.10f
            visibility = View.GONE
        }
        timerView = timer
        col.addView(timer, lp(MATCH, WRAP, 4))

        // Keep an explicit server shortcut so users retain access to imported and subscription profiles.
        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(10), dpi(13), dpi(10))
            background = Palette.glass(this@MainActivity, 16f)
            isClickable = true
            setOnClickListener { showTab(1) }
            pressable(0.985f)
        }
        val hf = flagBox(42, 13f, "", Palette.cyan)
        homeFlag = hf
        serverCard.addView(hf, lp(dpi(44), dpi(44)))
        val sinfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(11), 0, dpi(8), 0) }
        sinfo.addView(tv("SELECTED SERVER", 8f, Palette.muted, 1).apply { letterSpacing = 0.13f })
        homeServerName = tv("—", 14f, Palette.text, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homeServerName!!, lp(MATCH, WRAP, 4))
        homePing = tv("", 10.5f, Palette.muted, 1).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        sinfo.addView(homePing!!, lp(MATCH, WRAP, 3))
        serverCard.addView(sinfo, LinearLayout.LayoutParams(0, WRAP, 1f))
        serverCard.addView(IconView(this, Icon.CHEVRON, Palette.muted), lp(dpi(20), dpi(20)))
        col.addView(serverCard, lp(MATCH, WRAP, 16))

        // Smart Connect remains available as a one-tap fastest-server option.
        val smartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            background = Palette.glass(this@MainActivity, 15f)
        }
        val smartText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        smartText.addView(tv("Smart Connect", 12.5f, Palette.text, 1))
        smartText.addView(tv("Choose the fastest reachable profile", 10f, Palette.muted, 0), lp(MATCH, WRAP, 2))
        smartRow.addView(smartText, LinearLayout.LayoutParams(0, WRAP, 1f))
        smartConnectSwitch = Switch(this).apply {
            showText = false
            isChecked = false
            contentDescription = "Connect to the fastest reachable server"
            setOnCheckedChangeListener { _, checked ->
                if (programmaticSmartSwitch) return@setOnCheckedChangeListener
                if (checked) startSmartConnect() else cancelSmartConnect(false)
            }
        }
        smartRow.addView(smartConnectSwitch, lp(WRAP, WRAP))
        col.addView(smartRow, lp(MATCH, WRAP, 12))

        col.addView(tv("TIREXNET", 8f, Palette.dim, 1).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.22f
        }, lp(MATCH, WRAP, 20))

        val slider = SlideConnectControl(this).apply {
            state = this@MainActivity.state
            onToggle = { connectFromSlider() }
            onSettings = { showTab(3); scrollToSettings() }
        }
        connectSlider = slider
        frame.addView(slider, FrameLayout.LayoutParams(MATCH, dpi(70), Gravity.BOTTOM).apply {
            leftMargin = dpi(18); rightMargin = dpi(18); bottomMargin = dpi(8)
        })
        ensureServersLoaded()
        return frame
    }

    private fun homeHeadline(): String = when (state) {
        OrbState.CONNECTED -> "Connection secured"
        OrbState.CONNECTING -> "Connecting…"
        OrbState.DISCONNECTING -> "Disconnecting…"
        OrbState.FAILED -> "Connection failed"
        OrbState.PERMISSION -> "Allow VPN access"
        OrbState.DISCONNECTED -> "Start your\nconnection"
    }

    private fun regionLabel(): String = if (regionFilter == "ALL") "ALL" else regionDisplayName(regionFilter)

    private fun regionDisplayName(code: String): String = runCatching {
        Locale("", code.uppercase(Locale.US)).getDisplayCountry(Locale.ENGLISH).ifBlank { code.uppercase(Locale.US) }
    }.getOrDefault(code.uppercase(Locale.US))

    private fun showRegionPicker() {
        if (serversState == ServersState.IDLE && token.isNotBlank()) ensureServersLoaded()
        var pending = regionFilter
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(10), dpi(20), dpi(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.bg0, Palette.bg1)).apply {
                cornerRadii = floatArrayOf(dpi(28).toFloat(), dpi(28).toFloat(), dpi(28).toFloat(), dpi(28).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dpi(1), Palette.stroke)
            }
        }
        val handle = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpi(5).toFloat(); setColor(if (Palette.dark) 0xFF53647A.toInt() else 0xFFD0D2D6.toInt()) }
        }
        panel.addView(handle, LinearLayout.LayoutParams(dpi(42), dpi(5)).apply { gravity = Gravity.CENTER; topMargin = dpi(3); bottomMargin = dpi(17) })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Region Selection", 20f, Palette.text, 1), LinearLayout.LayoutParams(0, WRAP, 1f))
        val apply = tv("Apply", 13f, Palette.cyan, 2).apply {
            setPadding(dpi(10), dpi(8), dpi(2), dpi(8))
            isClickable = true
            setOnClickListener {
                regionFilter = pending
                prefs.edit().putString("region_filter", regionFilter).apply()
                if (regionFilter != "ALL") {
                    val available = servers.firstOrNull { it.country.equals(regionFilter, true) && it.engine !in setOf(ConfigEngine.SLIPNET, ConfigEngine.DNS_CHANGER) }
                    val current = selectedItem()
                    if (available != null && current?.raw?.trim() != available.raw.trim()) setConfig(available.raw)
                }
                homeRegionLabel?.text = regionLabel()
                rebuildServerList(-1)
                bindServerSummary()
                dialog.dismiss()
            }
        }
        head.addView(apply)
        panel.addView(head, lp(MATCH, WRAP, 0, 16))
        panel.addView(tv("ALL keeps every available profile visible and eligible. Choose a country only when you want to filter the list.", 12.5f, Palette.muted, 0), lp(MATCH, WRAP, 0, 16))

        val listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(listScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fun addRegion(code: String, fastest: Boolean = false) {
            val selected = code == pending
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(56)
                setPadding(dpi(2), dpi(4), dpi(3), dpi(4))
                isClickable = true
                setOnClickListener {
                    pending = code
                    for (i in 0 until list.childCount) {
                        val v = list.getChildAt(i) as? LinearLayout ?: continue
                        val mark = v.getChildAt(v.childCount - 1) as? TextView
                        val key = v.tag as? String
                        mark?.text = if (key == pending) "✓" else ""
                        mark?.setTextColor(Palette.text)
                    }
                }
            }
            row.tag = code
            if (fastest) {
                val icon = FrameLayout(this).apply {
                    background = GradientDrawable().apply { cornerRadius = dpi(7).toFloat(); setColor(if (Palette.dark) 0xFF111821.toInt() else 0xFF08090B.toInt()) }
                    addView(IconView(this@MainActivity, Icon.GLOBE, Color.WHITE), FrameLayout.LayoutParams(dpi(27), dpi(27), Gravity.CENTER))
                }
                row.addView(icon, lp(dpi(42), dpi(42)))
            } else row.addView(flagBox(42, 8f, code, Palette.cyan), lp(dpi(42), dpi(42)))
            row.addView(tv(if (fastest) "ALL" else regionDisplayName(code), 14f, Palette.text, if (selected) 2 else 0).apply {
                setPadding(dpi(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(tv(if (selected) "✓" else "", 22f, Palette.text, 1).apply { gravity = Gravity.CENTER; minWidth = dpi(30) })
            val divider = View(this).apply { setBackgroundColor(Palette.stroke) }
            list.addView(row, lp(MATCH, dpi(58)))
            list.addView(divider, lp(MATCH, dpi(1)))
        }

        addRegion("ALL", true)
        val regionProfiles = if (servers.isNotEmpty()) servers else mergeSavedAndRemote(loadRemoteCache())
        val countries = regionProfiles.map { it.country.trim().uppercase(Locale.US) }
            .filter { it.matches(Regex("^[A-Z]{2}$")) }
            .distinct()
            .sortedBy { regionDisplayName(it) }
        if (countries.isEmpty()) {
            panel.addView(tv("Country information appears when profiles include region metadata. Choose ALL to show every available profile.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 12))
        } else countries.forEach { addRegion(it) }

        dialog.setContentView(panel)
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.42f)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            w.setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun connectFromSlider() {
        if (state != OrbState.CONNECTED && state != OrbState.CONNECTING && state != OrbState.DISCONNECTING) {
            if (regionFilter != "ALL") {
                val current = selectedItem()
                val candidates = servers.filter { it.country.equals(regionFilter, true) && it.engine !in setOf(ConfigEngine.SLIPNET, ConfigEngine.DNS_CHANGER) }
                if (candidates.isEmpty()) {
                    toast("No supported server is available in ${regionDisplayName(regionFilter)}.")
                    showTab(1)
                    return
                }
                if (candidates.none { it.raw.trim() == current?.raw?.trim() }) {
                    val bestCached = candidates.mapNotNull { item ->
                        val ping = PingTester.cached(item.raw.trim())
                        if (ping?.kind == PingKind.OK) item to ping.ms else null
                    }.minByOrNull { it.second }?.first ?: candidates.first()
                    setConfig(bestCached.raw)
                    bindServerSummary()
                    rebuildServerList(-1)
                }
            }
        }
        toggleConnection()
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
        head.addView(ghostButton(if (sortByPing) "Ping ↑" else "Sort by ping") {
            sortByPing = !sortByPing
            prefs.edit().putBoolean("sort_by_ping", sortByPing).apply()
            if (sortByPing) testAllPings() else rebuildServerList(-1)
        }.apply { textSize = 10.5f; setPadding(dpi(8), 0, dpi(8), 0) }, lp(dpi(82), dpi(40), 0, 0, 0, 6))
        head.addView(roundButton(Icon.BOLT, "Test ping of all servers") { testAllPings() }, lp(dpi(40), dpi(40), 0, 0, 0, 6))
        head.addView(roundButton(Icon.REFRESH, "Reload servers") { loadServers() }, lp(dpi(40), dpi(40)))
        col.addView(head, lp(MATCH, WRAP, 6))
        col.addView(ghostButton("Region · ${regionLabel()}   ▾") { showRegionPicker() }.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), 0, dpi(14), 0)
        }, lp(MATCH, dpi(42), 10))

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
                c.addView(tv("Check your internet connection and try again.", 11.5f, Palette.muted, 0).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }, lp(MATCH, WRAP, 6))
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
                val filtered = servers.filter { (regionFilter == "ALL" || it.country.equals(regionFilter, true)) &&
                    (q.isEmpty() || it.name.lowercase(Locale.US).contains(q)) }
                val list = if (sortByPing) filtered.sortedWith(
                    compareBy<ServerItem>({ item -> if (PingTester.cached(item.raw.trim())?.kind == PingKind.OK) 0 else 1 },
                        { item -> PingTester.cached(item.raw.trim())?.takeIf { it.kind == PingKind.OK }?.ms ?: Int.MAX_VALUE },
                        { item -> servers.indexOf(item) })
                ) else filtered
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
                        if (sel) background = Palette.glass(this@MainActivity, 18f, Palette.strokeActive, Palette.withAlpha(Palette.cyan, 54), Palette.withAlpha(Palette.ice, 18))
                        setOnClickListener { selectServer(item, idx) }
                        setOnLongClickListener {
                            if (item.local) showServerActions(item)
                            else toast("Subscription profiles are read-only. Add a local copy to customize it.")
                            true
                        }
                        pressable(0.98f)
                    }
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    // Country flag takes the place of the old XR/WG/AW badge. The engine is still detected internally.
                    row.addView(flagBox(46, 14f, item.country, if (sel) Palette.cyan else Palette.ice).also { it.setSelectedState(sel) }, lp(dpi(50), dpi(50)))
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12), 0, dpi(8), 0) }
                    info.addView(tv(item.name, 14.5f, Palette.text, 1).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    val sub = listOf(
                        if (sel) "Selected" else "",
                        item.panelName.ifBlank { if (item.panelId.equals("ali", true) || item.panelId.equals("alipanel", true)) "AliPanel" else "" },
                        if (item.local) "Saved on this phone" else ""
                    ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" • ")
                    if (sub.isNotEmpty()) info.addView(tv(sub, 10.5f, if (sel) Palette.cyan else Palette.muted, 0), lp(MATCH, WRAP, 4))
                    row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

                    if (item.engine == ConfigEngine.SLIPNET || item.engine == ConfigEngine.DNS_CHANGER) {
                        val slipBadge = if (item.engine == ConfigEngine.DNS_CHANGER) "DNS" else EngineProfiles.decodeSlipNet(item.raw)?.protocol?.let { if (it == "snowflake") "TOR" else "SLIPNET" } ?: "SLIPNET"
                        row.addView(tv(slipBadge, 9.5f, Palette.cyan, 2).apply {
                            gravity = Gravity.CENTER
                            setPadding(dpi(9), dpi(7), dpi(9), dpi(7))
                            background = Palette.glass(this@MainActivity, 12f, Palette.strokeActive)
                        }, lp(WRAP, WRAP, 0, 0, 0, 4))
                    } else {
                        val holder = buildPingControl(item)
                        pingHolders[key] = holder
                        row.addView(holder.text, lp(WRAP, WRAP, 0, 0, 0, 6))
                        row.addView(holder.button, lp(dpi(44), dpi(44)))
                        bindPing(key)
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
        if (item.engine == ConfigEngine.SLIPNET || item.meta.host.isBlank()) return
        val key = item.raw.trim()
        if (PingTester.isRunning(key)) return
        PingTester.test(key, item.meta, force) {
            if (isFinishing || isDestroyed) return@test
            bindPing(key)
            if (key == currentConfig().trim()) bindHomePing()
            if (sortByPing && serverListBox != null) rebuildServerList(-1)
        }
        bindPing(key)
    }

    private fun testAllPings() {
        val list = servers.filter { it.engine != ConfigEngine.SLIPNET && it.meta.host.isNotBlank() }
        if (list.isEmpty()) { toast("No compatible profiles are available for this check."); return }
        list.forEach { runPing(it, false) }
    }

    private fun measureSelectedPing(force: Boolean) {
        val item = selectedItem() ?: return
        if (item.engine == ConfigEngine.SLIPNET || item.meta.host.isBlank()) { bindHomePing(); return }
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
        if (ConfigDetector.detect(raw) == ConfigEngine.SLIPNET) {
            v.text = "Resolver scan unavailable"
            v.setTextColor(Palette.cyan)
            return
        }
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
        settingRow(g1, Icon.INFO, "Active engine", "Detected from the selected profile", engineLabelShort()) { }
        settingRow(g1, Icon.GLOBE, "Connection mode", "VPN or local SOCKS5 / HTTP proxy", connectionModeSummary()) { showConnectionModeDialog() }
        settingRow(g1, Icon.GLOBE, "SlipNet protocols & settings", "DNS tunnels, resolver pools, SSH chains, Slipstream, NaiveProxy and Tor", "Open") { showSlipNetDialog() }
        settingRow(g1, Icon.SHIELD, "Split tunneling", "Choose apps included in or excluded from Xray", splitSummary()) { showSplitTunnelDialog() }
        settingRow(g1, Icon.BOLT, "TLS server name", "Edit TLS / REALITY serverName on a local copy", null) { selectedItem()?.let { showSniDialog(it) } ?: toast("Select a server first.") }
        settingRow(g1, Icon.SHIELD, "Patterniha SNI fragmentation", "Rootless TLS fragmentation • creates a local profile", "Configure") { selectedItem()?.let { showPatternihaDialog(it) } ?: toast("Select a TLS/REALITY server first.") }
        settingRow(g1, Icon.GLOBE, "Website ping target", "HTTP response-time check for a URL you choose", "Change") { showPingUrlDialog() }
        settingRow(g1, Icon.SHIELD, "MHRV CA certificate", "Download it, then install it from Android Security settings", "Export") { exportMhrvCa() }
        settingRow(g1, Icon.COPY, "Connection logs", "Recent connection activity", "Open") { showConnectionLogs() }
        settingRow(g1, Icon.COPY, "Copy diagnostics", "Safe device and connection summary", null) { copyDiagnostics() }
        settingRow(g1, Icon.TRASH, "Clear saved config", "Remove the selected profile from this phone", null) { clearConfig() }
        col.addView(g1, lp(MATCH, WRAP))

        val g2 = GlassCard(this, 20f, pad = 4)
        settingRow(g2, Icon.SHIELD, "Privacy", "چه چیزی روی گوشی ذخیره می‌شود", null) { showPrivacy() }
        settingRow(g2, Icon.INFO, "App version", packageName, versionName()) { }
        settingRow(g2, Icon.REFRESH, "App updates", "بررسی نسخه جدید در GitHub و نصب APK", "Check") { checkForUpdates() }
        col.addView(g2, lp(MATCH, WRAP, 12))
        return sv
    }

    private fun showSlipNetDialog() {
        val selected = selectedItem()?.takeIf { it.engine == ConfigEngine.SLIPNET }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(10), dpi(16), dpi(8))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))

        fun heading(title: String, subtitle: String = "") {
            body.addView(tv(title.uppercase(Locale.US), 10f, Palette.cyan, 2).apply { letterSpacing = 0.13f }, lp(MATCH, WRAP, 14, 4))
            if (subtitle.isNotBlank()) body.addView(tv(subtitle, 10.5f, Palette.muted, 0), lp(MATCH, WRAP, 0, 6))
        }
        fun field(label: String, key: String, hint: String, default: String = "", multiline: Boolean = false, secret: Boolean = false, numeric: Boolean = false): EditText {
            body.addView(tv(label, 11.5f, Palette.text, 1), lp(MATCH, WRAP, 7, 3))
            return EditText(this).apply {
                setText(prefs.all[key]?.toString() ?: default)
                this.hint = hint
                textSize = 12.5f
                setTextColor(Palette.text)
                setHintTextColor(Palette.dim)
                background = fieldBackground(false)
                setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
                if (multiline) { minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START }
                else setSingleLine(true)
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                body.addView(this, lp(MATCH, if (multiline) dpi(84) else dpi(44), 0, 2))
            }
        }
        fun switch(label: String, key: String, default: Boolean): Switch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(3), 0, dpi(3))
            }
            row.addView(tv(label, 11.5f, Palette.text, 1), LinearLayout.LayoutParams(0, WRAP, 1f))
            val control = Switch(this).apply { showText = false; isChecked = prefs.getBoolean(key, default) }
            row.addView(control)
            body.addView(row, lp(MATCH, WRAP, 3))
            return control
        }
        fun spinner(label: String, key: String, entries: List<String>, default: String): Spinner {
            body.addView(tv(label, 11.5f, Palette.text, 1), lp(MATCH, WRAP, 7, 2))
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, entries)
                val saved = prefs.getString(key, default) ?: default
                setSelection(entries.indexOf(saved).coerceAtLeast(0))
                body.addView(this, lp(MATCH, dpi(44), 0, 2))
            }
        }

        heading("SlipNet", "All preferences below are saved in TIREXNET. Protocol execution still needs SlipNet's native engines to be embedded; this build will not hand off to a separate app or report a false connection.")
        val protocol = spinner("Preferred protocol", "slipnet_protocol", listOf("Auto detect", "DNSTT", "NoizDNS", "VayDNS", "Soar", "Slipstream", "SSH", "NaiveProxy", "VLESS", "DoH", "Tor"), "Auto detect")
        val mtu = field("VPN MTU (512–1500)", "slipnet_mtu", "1280", "1280", numeric = true)
        val maxTunnels = field("Max parallel tunnels (1–64)", "slipnet_max_tunnels", "4", "4", numeric = true)
        val timeout = field("Connection timeout (seconds)", "slipnet_timeout", "20", "20", numeric = true)
        val dnsTimeout = field("DNS timeout (milliseconds)", "slipnet_dns_timeout", "5000", "5000", numeric = true)
        val connectionPoolSize = field("Connection pool size (1–128)", "slipnet_connection_pool_size", "16", "16", numeric = true)
        val bufferSize = spinner("Network buffer size", "slipnet_buffer_size", listOf("32 KB", "64 KB", "128 KB", "256 KB", "512 KB"), "128 KB")
        val keepAlive = field("Keep-alive interval (milliseconds)", "slipnet_keepalive_ms", "5000", "5000", numeric = true)
        val dnsWorkers = spinner("DNS worker mode", "slipnet_dns_workers", listOf("Per-query (default)", "2 workers", "3 workers", "5 workers (fastest)"), "Per-query (default)")
        dnsWorkers.setSelection(when ((prefs.getString("slipnet_dns_workers", "per_query") ?: "per_query").lowercase(Locale.US)) {
            "two", "2", "2 workers" -> 1
            "three", "3", "3 workers" -> 2
            "five", "5", "5 workers (fastest)" -> 3
            else -> 0
        })
        val congestion = spinner("Congestion control", "slipnet_congestion", listOf("BBR", "DCUBIC"), "BBR")
        val uploadLimit = field("Upload limit (KB/s, 0 = unlimited)", "slipnet_upload_limit", "0", "0", numeric = true)
        val downloadLimit = field("Download limit (KB/s, 0 = unlimited)", "slipnet_download_limit", "0", "0", numeric = true)
        val gso = switch("Enable UDP GSO where supported", "slipnet_gso", false)
        val disableQuic = switch("Disable QUIC fallback", "slipnet_disable_quic", true)
        val blockIpv6 = switch("Block IPv6 to avoid leaks", "slipnet_block_ipv6", true)

        heading("DNS & resolvers", "Used by DNSTT, NoizDNS, VayDNS and Soar profiles.")
        val resolvers = field("Resolver pool (one IP:port per line)", "slipnet_resolvers", "1.1.1.1:53\n8.8.8.8:53", "1.1.1.1:53\n8.8.8.8:53", multiline = true)
        val dnsTransport = spinner("DNS transport", "slipnet_dns_transport", listOf("UDP", "TCP", "DoT", "DoH"), "UDP")
        val resolverMode = spinner("Resolver strategy", "slipnet_resolver_mode", listOf("Fast (round-robin)", "Reliable (fan-out)"), "Fast (round-robin)")
        val dohUrl = field("DoH URL", "slipnet_doh_url", "https://dns.google/dns-query")
        val autoResolver = switch("Automatically select working resolvers", "slipnet_auto_resolver", true)
        val globalResolverEnabled = switch("Override profile resolvers globally", "slipnet_global_resolver_enabled", false)
        val globalResolverList = field("Global resolver list (host:port, one per line)", "slipnet_global_resolver_list", "1.1.1.1:53\n8.8.8.8:53", "", multiline = true)
        val dnsPoolEnabled = switch("Enable DNS pool", "slipnet_dns_pool_enabled", false)
        val dnsPoolText = field("DNS pool entries (one per line)", "slipnet_dns_pool_text", "resolver.example:53", "", multiline = true)
        val dnsPoolFullVerification = switch("Fully verify DNS pool entries", "slipnet_dns_pool_full_verification", false)
        val resolverSpread = field("Round-robin spread count (1–5)", "slipnet_resolver_spread", "3", "3", numeric = true)
        val autoTune = switch("Auto-tune DNS payload and query rate", "slipnet_dns_auto_tune", false)
        val dnsPayload = field("DNS payload / KCP MTU (0 = auto)", "slipnet_dns_payload", "0", "0", numeric = true)
        val qname = field("VayDNS maximum QNAME length", "slipnet_vay_qname", "101", "101", numeric = true)
        val rps = field("VayDNS query rate limit (0 = unlimited)", "slipnet_vay_rps", "0", "0", numeric = true)
        val vayCompat = switch("VayDNS DNSTT wire-format compatibility", "slipnet_vay_compat", false)
        val vayRecordType = spinner("VayDNS DNS record type", "slipnet_vay_record_type", listOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv", "null", "caa"), "txt")
        val vayIdleTimeout = field("VayDNS idle timeout (seconds, 0 = default)", "slipnet_vay_idle", "0", "0", numeric = true)
        val vayKeepalive = field("VayDNS keep-alive (seconds, 0 = default)", "slipnet_vay_keepalive", "0", "0", numeric = true)
        val vayUdpTimeout = field("VayDNS UDP response timeout (ms, 0 = default)", "slipnet_vay_udp_timeout", "0", "0", numeric = true)
        val vayMaxLabels = field("VayDNS maximum data labels (0 = unlimited)", "slipnet_vay_max_labels", "0", "0", numeric = true)
        val vayClientId = field("VayDNS Client ID size (bytes, 0 = default)", "slipnet_vay_client_id", "0", "0", numeric = true)
        val noizStealth = switch("NoizDNS stealth mode", "slipnet_noiz_stealth", false)
        val soarDiscovery = switch("Soar resolver discovery", "slipnet_soar_discovery", false)
        val soarCountry = field("Soar resolver country (ISO code; blank = global)", "slipnet_soar_country", "IR", "")
        val soarTcpMode = spinner("Soar TCP/53 mode", "slipnet_soar_tcp_mode", listOf("auto", "on", "only", "off"), "auto")

        heading("SSH chain", "SSH authentication, ciphers and channel limits.")
        val sshChain = switch("Enable SSH chaining when profile supports it", "slipnet_ssh_chain", false)
        val sshHost = field("SSH host", "slipnet_ssh_host", "ssh.example.com")
        val sshPort = field("SSH port", "slipnet_ssh_port", "22", "22", numeric = true)
        val sshUser = field("SSH username", "slipnet_ssh_username", "username")
        val sshPassword = field("SSH password", "slipnet_ssh_password", "Password", secret = true)
        val sshKey = field("SSH private key (optional)", "slipnet_ssh_private_key", "-----BEGIN OPENSSH PRIVATE KEY-----", multiline = true)
        val sshKeyPassphrase = field("SSH private-key passphrase (optional)", "slipnet_ssh_key_passphrase", "Private-key passphrase", secret = true)
        val sshAuthType = spinner("SSH authentication method", "slipnet_ssh_auth_type", listOf("Auto", "Password", "Private key", "Password + private key"), "Auto")
        val sshChannels = field("SSH max channels", "slipnet_ssh_channels", "16", "16", numeric = true)
        val sshChannelsCustom = switch("Use a custom SSH channel limit (off = automatic)", "slipnet_ssh_channels_custom", false)
        val sshCipher = spinner("SSH cipher", "slipnet_ssh_cipher", listOf("Auto", "AES-128-GCM", "ChaCha20-Poly1305", "AES-128-CTR (Legacy)"), "Auto")
        val sshCompression = switch("SSH compression", "slipnet_ssh_compression", false)
        val preventDnsFallback = switch("Prevent DNS fallback outside the tunnel", "slipnet_prevent_dns_fallback", true)
        val sshTlsSni = field("SSH-over-TLS SNI", "slipnet_ssh_tls_sni", "front.example.com")
        val sshHttpProxyHost = field("SSH HTTP CONNECT proxy host", "slipnet_ssh_http_proxy_host", "proxy.example.com")
        val sshHttpProxyPort = field("SSH HTTP CONNECT proxy port", "slipnet_ssh_http_proxy_port", "8080", "8080", numeric = true)
        val sshHttpProxyCustomHost = field("SSH HTTP CONNECT Host header", "slipnet_ssh_http_proxy_custom_host", "")
        val sshWsPath = field("SSH WebSocket path", "slipnet_ssh_ws_path", "/")
        val sshWsHost = field("SSH WebSocket Host header", "slipnet_ssh_ws_host", "")
        val sshWsUseTls = switch("Use TLS for SSH WebSocket (wss://)", "slipnet_ssh_ws_tls", true)
        val sshPayload = field("SSH pre-handshake payload", "slipnet_ssh_payload", "", multiline = true)

        heading("App & local proxy", "SlipNet-style app behavior and local proxy preferences.")
        val autoConnectBoot = switch("Auto-connect on boot", "slipnet_auto_connect_boot", false)
        val proxyOnly = switch("Proxy-only mode", "slipnet_proxy_only", false)
        val killSwitch = switch("Kill switch", "slipnet_kill_switch", false)
        val notificationTraffic = switch("Show traffic in notification", "slipnet_notification_traffic", true)
        val proxyListenHost = field("Local proxy listen address", "slipnet_proxy_host", "127.0.0.1", "127.0.0.1")
        val proxyListenPort = field("Local SOCKS port", "slipnet_proxy_port", "1080", "1080", numeric = true)
        val proxyAuth = switch("Enable local proxy authentication", "slipnet_proxy_auth", false)
        val proxyUsername = field("Local proxy username", "slipnet_proxy_user", "")
        val proxyPassword = field("Local proxy password", "slipnet_proxy_pass", "", secret = true)
        val httpProxyEnabled = switch("Enable local HTTP proxy", "slipnet_http_proxy_enabled", false)
        val httpProxyPort = field("Local HTTP proxy port", "slipnet_http_proxy_port", "8080", "8080", numeric = true)
        val sleepTimer = field("Auto-disconnect timer (minutes, 0 = off)", "slipnet_sleep_timer", "0", "0", numeric = true)
        val remoteDnsMode = spinner("Remote DNS mode", "slipnet_remote_dns_mode", listOf("Default", "Custom"), "Default")
        val remoteDnsPrimary = field("Custom remote DNS", "slipnet_remote_dns_primary", "1.1.1.1")
        val remoteDnsFallback = field("Fallback remote DNS", "slipnet_remote_dns_fallback", "8.8.8.8")
        val domainRouting = switch("Enable domain routing", "slipnet_domain_routing_enabled", false)
        val domainRoutingMode = spinner("Domain routing mode", "slipnet_domain_routing_mode", listOf("Bypass listed domains", "Tunnel only listed domains"), "Bypass listed domains")
        val routedDomains = field("Domains (one hostname per line)", "slipnet_domain_routing_domains", "example.com", multiline = true)
        val geoBypass = switch("Enable geo-bypass", "slipnet_geo_bypass_enabled", false)
        val geoCountry = field("Geo-bypass country (ISO code)", "slipnet_geo_bypass_country", "IR", "IR")

        heading("Advanced protocol tuning", "Advanced per-device defaults; profile-specific values still take priority.")
        val vlessSni = field("VLESS default SNI", "slipnet_vless_sni", "")
        val sniFragmentEnabled = switch("Enable TLS SNI fragmentation", "slipnet_vless_sni_fragment", true)
        val sniFragmentStrategy = spinner("TLS fragmentation strategy", "slipnet_vless_sni_strategy", listOf("micro", "multi", "sni_split", "fake", "disorder"), "micro")
        val sniFragmentDelay = field("TLS fragment delay (ms)", "slipnet_vless_sni_delay", "300", "300", numeric = true)
        val tcpMaxSeg = field("TCP MSS cap (0 = auto)", "slipnet_vless_tcp_mss", "0", "0", numeric = true)
        val chPadding = switch("TLS ClientHello padding", "slipnet_vless_ch_padding", false)
        val wsObfuscation = switch("Obfuscate WebSocket headers", "slipnet_vless_ws_obfuscation", true)
        val wsPadding = switch("WebSocket cover ping frames", "slipnet_vless_ws_padding", false)
        val vlessUuid = field("VLESS UUID", "slipnet_vless_uuid", "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
        val vlessSecurity = spinner("VLESS security", "slipnet_vless_security", listOf("tls", "none"), "tls")
        val vlessTransport = spinner("VLESS transport", "slipnet_vless_transport", listOf("ws", "tcp", "grpc"), "ws")
        val vlessWsPath = field("VLESS WebSocket path", "slipnet_vless_ws_path", "/")
        val vlessCdnIp = field("VLESS CDN / edge IP", "slipnet_vless_cdn_ip", "104.16.0.1")
        val vlessCdnPort = field("VLESS CDN port", "slipnet_vless_cdn_port", "443", "443", numeric = true)
        val naivePort = field("NaiveProxy port", "slipnet_naive_port", "443", "443", numeric = true)
        val naiveUsername = field("NaiveProxy username", "slipnet_naive_username", "")
        val naivePassword = field("NaiveProxy password", "slipnet_naive_password", "", secret = true)
        val proxyHttpAppend = switch("Append local HTTP proxy to VPN", "slipnet_http_proxy_append", false)
        val debugLogging = switch("SlipNet diagnostic logging", "slipnet_debug_logging", false)
        val slipnetTheme = spinner("SlipNet appearance preference", "slipnet_dark_mode", listOf("System", "Light", "Dark"), "System")

        heading("Tor bridges", "Blank bridge lines use SlipNet's built-in Snowflake default. Custom lines support obfs4, webtunnel and meek.")
        val selectedTorProfile = selected?.let { EngineProfiles.decodeSlipNet(it.raw) }?.takeIf { it.protocol == "snowflake" }
        val selectedTorBridges = selectedTorProfile?.torBridgeLines.orEmpty()
        val storedTorBridges = prefs.getString("slipnet_tor_bridges", null)
        val torModes = listOf("Built-in Snowflake", "Direct", "Snowflake AMP", "Obfs4", "Meek Azure", "WebTunnel", "Smart", "Custom")
        val inferredTorMode = when (selectedTorBridges.trim()) {
            "" -> "Built-in Snowflake"
            "DIRECT" -> "Direct"
            "SNOWFLAKE_AMP" -> "Snowflake AMP"
            "SMART" -> "Smart"
            else -> when {
                selectedTorBridges.lineSequence().any { it.trim().startsWith("obfs4 ", true) } -> "Obfs4"
                selectedTorBridges.lineSequence().any { it.trim().startsWith("meek_lite ", true) } -> "Meek Azure"
                selectedTorBridges.lineSequence().any { it.trim().startsWith("webtunnel ", true) } -> "WebTunnel"
                else -> "Custom"
            }
        }
        val initialTorMode = if (selectedTorProfile != null) inferredTorMode else (prefs.getString("slipnet_tor_bridge_mode", "Built-in Snowflake") ?: "Built-in Snowflake")
        val torBridgeMode = spinner("Tor bridge preset", "slipnet_tor_bridge_mode", torModes, initialTorMode)
        if (selectedTorProfile != null) torBridgeMode.setSelection(torModes.indexOf(inferredTorMode).coerceAtLeast(0))
        val bridgePrefill = if (selectedTorProfile != null) selectedTorBridges else storedTorBridges.orEmpty()
        val torBridgeLines = field("Custom Tor bridge lines", "slipnet_tor_bridges", "obfs4 ...\nwebtunnel ...", bridgePrefill, multiline = true)
        body.addView(tv("PASTE BRIDGE LINES FROM CLIPBOARD", 10.5f, Palette.cyan, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(10), dpi(9), dpi(10), dpi(9))
            background = Palette.glass(this@MainActivity, 10f, Palette.stroke, Palette.glassTop, Palette.glassBottom)
            isClickable = true
            setOnClickListener {
                val clip = runCatching {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                }.getOrNull()
                val pasted = runCatching {
                    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@MainActivity).toString().trim() else ""
                }.getOrDefault("")
                if (pasted.isBlank()) toast("Clipboard has no Tor bridge lines.") else torBridgeLines.setText(pasted)
            }
        }, lp(MATCH, dpi(40), 0, 5))
        val torFallback = switch("Allow Tor fallback when profile supports it", "slipnet_tor_fallback", false)

        heading("Profile behavior", "Per-profile values in a SlipNet share URI remain preserved. These defaults are stored separately.")
        val sshTls = switch("Allow SSH over TLS", "slipnet_ssh_tls", false)
        val sshWs = switch("Allow SSH over WebSocket", "slipnet_ssh_ws", false)
        val dnsFallback = switch("Auto-reconnect after a dropped tunnel", "slipnet_auto_reconnect", true)
        val splitTunnel = switch("Enable per-app split tunneling", "slipnet_split_tunnel", false)
        body.addView(tv("Choose applications…", 11.5f, Palette.cyan, 2).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(12), dpi(11), dpi(12), dpi(11))
            background = Palette.glass(this@MainActivity, 12f, Palette.stroke, Palette.glassTop, Palette.glassBottom)
            isClickable = true
            setOnClickListener { showSplitTunnelDialog() }
        }, lp(MATCH, WRAP, 2, 4))

        val currentProtocol = selected?.let { EngineProfiles.decodeSlipNet(it.raw)?.protocol }.orEmpty()
        body.addView(tv(
            if (selected != null) "Selected profile: ${selected.name} • ${currentProtocol.ifBlank { "encrypted / auto" }}" else "No SlipNet profile selected. Import a slipnet:// profile from the Servers tab.",
            10.5f, Palette.muted, 0
        ), lp(MATCH, WRAP, 14, 12))
        root.addView(scroll, lp(MATCH, minOf(dpi(560), resources.displayMetrics.heightPixels * 3 / 4)))

        val dialog = dialogBuilder().setTitle("SlipNet • Complete settings")
            .setView(root).setNegativeButton("Close", null).setPositiveButton("Save settings", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun number(view: EditText, label: String, min: Int, max: Int): Int? {
                    val n = view.text.toString().trim().toIntOrNull()
                    if (n == null || n !in min..max) { view.error = "$label must be $min–$max"; view.requestFocus(); return null }
                    return n
                }
                val mtuValue = number(mtu, "MTU", 512, 1500) ?: return@setOnClickListener
                val tunnelValue = number(maxTunnels, "Max tunnels", 1, 64) ?: return@setOnClickListener
                val timeoutValue = number(timeout, "Timeout", 3, 180) ?: return@setOnClickListener
                val dnsTimeoutValue = number(dnsTimeout, "DNS timeout", 250, 120000) ?: return@setOnClickListener
                val connectionPoolValue = number(connectionPoolSize, "Connection pool", 1, 128) ?: return@setOnClickListener
                val keepAliveValue = number(keepAlive, "Keep-alive", 500, 60000) ?: return@setOnClickListener
                val uploadLimitValue = number(uploadLimit, "Upload limit", 0, 10_000_000) ?: return@setOnClickListener
                val downloadLimitValue = number(downloadLimit, "Download limit", 0, 10_000_000) ?: return@setOnClickListener
                val resolverSpreadValue = number(resolverSpread, "Resolver spread", 1, 5) ?: return@setOnClickListener
                val sshPortValue = number(sshPort, "SSH port", 1, 65535) ?: return@setOnClickListener
                val channelValue = number(sshChannels, "SSH channels", 1, 128) ?: return@setOnClickListener
                val payloadValue = number(dnsPayload, "DNS payload", 0, 1400) ?: return@setOnClickListener
                val qnameValue = number(qname, "QNAME", 64, 255) ?: return@setOnClickListener
                val vayIdleValue = number(vayIdleTimeout, "VayDNS idle timeout", 0, 86400) ?: return@setOnClickListener
                val vayKeepaliveValue = number(vayKeepalive, "VayDNS keep-alive", 0, 3600) ?: return@setOnClickListener
                val vayUdpTimeoutValue = number(vayUdpTimeout, "VayDNS UDP timeout", 0, 60000) ?: return@setOnClickListener
                val vayLabelsValue = number(vayMaxLabels, "VayDNS max labels", 0, 128) ?: return@setOnClickListener
                val vayClientIdValue = number(vayClientId, "VayDNS Client ID size", 0, 32) ?: return@setOnClickListener
                val vlessPortValue = number(vlessCdnPort, "VLESS CDN port", 1, 65535) ?: return@setOnClickListener
                val naivePortValue = number(naivePort, "NaiveProxy port", 1, 65535) ?: return@setOnClickListener
                val httpConnectPortValue = number(sshHttpProxyPort, "SSH HTTP proxy port", 1, 65535) ?: return@setOnClickListener
                val proxyPortValue = number(proxyListenPort, "Local proxy port", 1, 65535) ?: return@setOnClickListener
                val httpProxyPortValue = number(httpProxyPort, "HTTP proxy port", 1, 65535) ?: return@setOnClickListener
                val sleepTimerValue = number(sleepTimer, "Sleep timer", 0, 1440) ?: return@setOnClickListener
                val sniDelayValue = number(sniFragmentDelay, "TLS fragment delay", 0, 5000) ?: return@setOnClickListener
                val tcpMssValue = number(tcpMaxSeg, "TCP MSS", 0, 1400) ?: return@setOnClickListener
                val geoCode = geoCountry.text.toString().trim().uppercase(Locale.US)
                if (geoCode.length != 2 || geoCode.any { it !in 'A'..'Z' }) { geoCountry.error = "Use a two-letter ISO country code"; return@setOnClickListener }
                val rpsValue = rps.text.toString().trim().toDoubleOrNull()
                if (rpsValue == null || rpsValue !in 0.0..1000.0) { rps.error = "Enter 0–1000"; return@setOnClickListener }
                val country = soarCountry.text.toString().trim().uppercase(Locale.US)
                if (country.isNotEmpty() && !country.matches(Regex("^[A-Z]{2}$"))) { soarCountry.error = "Use a two-letter country code"; return@setOnClickListener }
                val portValue = sshPortValue
                val dnsWorkerValue = when (dnsWorkers.selectedItem.toString()) {
                    "2 workers" -> "two"
                    "3 workers" -> "three"
                    "5 workers (fastest)" -> "five"
                    else -> "per_query"
                }

                // If the selected item is an editable Tor share link, save a derived
                // local copy with bridge settings in field 26. Never rewrite a
                // subscription profile or an encrypted single-profile URI.
                val selectedTor = selectedTorProfile
                var derivedTorUri: String? = null
                var torEditUnavailable = false
                if (selectedTor != null) {
                    val bridgeText = torBridgeLines.text.toString().trim()
                    val bridgeValue = when (torBridgeMode.selectedItem.toString()) {
                        "Built-in Snowflake" -> ""
                        "Direct" -> "DIRECT"
                        "Snowflake AMP" -> "SNOWFLAKE_AMP"
                        "Smart" -> "SMART"
                        "Obfs4" -> bridgeText.takeIf { it.lineSequence().any { line -> line.trim().startsWith("obfs4 ", true) } }
                        "Meek Azure" -> bridgeText.takeIf { it.lineSequence().any { line -> line.trim().startsWith("meek_lite ", true) } }
                        "WebTunnel" -> bridgeText.takeIf { it.lineSequence().any { line -> line.trim().startsWith("webtunnel ", true) } }
                        else -> bridgeText.takeIf { it.isNotBlank() }
                    }
                    if (bridgeValue == null) {
                        toast("برای Obfs4، Meek یا WebTunnel باید bridge line معتبر وارد کنی.")
                        torBridgeLines.error = "Paste a valid bridge line"
                        torBridgeLines.requestFocus()
                        return@setOnClickListener
                    }
                    derivedTorUri = EngineProfiles.updateSlipNetTorBridges(selected!!.raw, bridgeValue)
                    torEditUnavailable = derivedTorUri == null
                }
                prefs.edit()
                    .putString("slipnet_protocol", protocol.selectedItem.toString())
                    .putInt("slipnet_mtu", mtuValue)
                    .putInt("slipnet_max_tunnels", tunnelValue)
                    .putInt("slipnet_timeout", timeoutValue)
                    .putInt("slipnet_dns_timeout", dnsTimeoutValue)
                    .putInt("slipnet_connection_pool_size", connectionPoolValue)
                    .putString("slipnet_buffer_size", bufferSize.selectedItem.toString())
                    .putInt("slipnet_keepalive_ms", keepAliveValue)
                    .putInt("slipnet_upload_limit", uploadLimitValue)
                    .putInt("slipnet_download_limit", downloadLimitValue)
                    .putString("slipnet_dns_workers", dnsWorkerValue)
                    .putString("slipnet_congestion", congestion.selectedItem.toString())
                    .putBoolean("slipnet_gso", gso.isChecked)
                    .putBoolean("slipnet_disable_quic", disableQuic.isChecked)
                    .putBoolean("slipnet_block_ipv6", blockIpv6.isChecked)
                    .putString("slipnet_resolvers", resolvers.text.toString().trim())
                    .putString("slipnet_dns_transport", dnsTransport.selectedItem.toString())
                    .putString("slipnet_resolver_mode", resolverMode.selectedItem.toString())
                    .putString("slipnet_doh_url", dohUrl.text.toString().trim())
                    .putBoolean("slipnet_auto_resolver", autoResolver.isChecked)
                    .putBoolean("slipnet_global_resolver_enabled", globalResolverEnabled.isChecked)
                    .putString("slipnet_global_resolver_list", globalResolverList.text.toString().trim())
                    .putBoolean("slipnet_dns_pool_enabled", dnsPoolEnabled.isChecked)
                    .putString("slipnet_dns_pool_text", dnsPoolText.text.toString().trim())
                    .putBoolean("slipnet_dns_pool_full_verification", dnsPoolFullVerification.isChecked)
                    .putInt("slipnet_resolver_spread", resolverSpreadValue)
                    .putBoolean("slipnet_dns_auto_tune", autoTune.isChecked)
                    .putInt("slipnet_dns_payload", payloadValue)
                    .putInt("slipnet_vay_qname", qnameValue)
                    .putString("slipnet_vay_rps", rpsValue.toString())
                    .putBoolean("slipnet_vay_compat", vayCompat.isChecked)
                    .putString("slipnet_vay_record_type", vayRecordType.selectedItem.toString())
                    .putInt("slipnet_vay_idle", vayIdleValue)
                    .putInt("slipnet_vay_keepalive", vayKeepaliveValue)
                    .putInt("slipnet_vay_udp_timeout", vayUdpTimeoutValue)
                    .putInt("slipnet_vay_max_labels", vayLabelsValue)
                    .putInt("slipnet_vay_client_id", vayClientIdValue)
                    .putBoolean("slipnet_noiz_stealth", noizStealth.isChecked)
                    .putBoolean("slipnet_soar_discovery", soarDiscovery.isChecked)
                    .putString("slipnet_soar_country", country)
                    .putString("slipnet_soar_tcp_mode", soarTcpMode.selectedItem.toString())
                    .putBoolean("slipnet_ssh_chain", sshChain.isChecked)
                    .putString("slipnet_ssh_host", sshHost.text.toString().trim())
                    .putInt("slipnet_ssh_port", portValue)
                    .putString("slipnet_ssh_username", sshUser.text.toString().trim())
                    .putString("slipnet_ssh_password", sshPassword.text.toString())
                    .putString("slipnet_ssh_private_key", sshKey.text.toString())
                    .putString("slipnet_ssh_key_passphrase", sshKeyPassphrase.text.toString())
                    .putString("slipnet_ssh_auth_type", sshAuthType.selectedItem.toString())
                    .putInt("slipnet_ssh_channels", channelValue)
                    .putBoolean("slipnet_ssh_channels_custom", sshChannelsCustom.isChecked)
                    .putString("slipnet_ssh_cipher", sshCipher.selectedItem.toString())
                    .putBoolean("slipnet_ssh_compression", sshCompression.isChecked)
                    .putBoolean("slipnet_prevent_dns_fallback", preventDnsFallback.isChecked)
                    .putString("slipnet_ssh_tls_sni", sshTlsSni.text.toString().trim())
                    .putString("slipnet_ssh_http_proxy_host", sshHttpProxyHost.text.toString().trim())
                    .putInt("slipnet_ssh_http_proxy_port", httpConnectPortValue)
                    .putString("slipnet_ssh_http_proxy_custom_host", sshHttpProxyCustomHost.text.toString().trim())
                    .putString("slipnet_ssh_ws_path", sshWsPath.text.toString().trim())
                    .putString("slipnet_ssh_ws_host", sshWsHost.text.toString().trim())
                    .putBoolean("slipnet_ssh_ws_tls", sshWsUseTls.isChecked)
                    .putString("slipnet_ssh_payload", sshPayload.text.toString())
                    .putBoolean("slipnet_auto_connect_boot", autoConnectBoot.isChecked)
                    .putBoolean("slipnet_proxy_only", proxyOnly.isChecked)
                    .putBoolean("slipnet_kill_switch", killSwitch.isChecked)
                    .putBoolean("slipnet_notification_traffic", notificationTraffic.isChecked)
                    .putString("slipnet_proxy_host", proxyListenHost.text.toString().trim())
                    .putInt("slipnet_proxy_port", proxyPortValue)
                    .putBoolean("slipnet_proxy_auth", proxyAuth.isChecked)
                    .putString("slipnet_proxy_user", proxyUsername.text.toString())
                    .putString("slipnet_proxy_pass", proxyPassword.text.toString())
                    .putBoolean("slipnet_http_proxy_enabled", httpProxyEnabled.isChecked)
                    .putInt("slipnet_http_proxy_port", httpProxyPortValue)
                    .putInt("slipnet_sleep_timer", sleepTimerValue)
                    .putString("slipnet_remote_dns_mode", remoteDnsMode.selectedItem.toString())
                    .putString("slipnet_remote_dns_primary", remoteDnsPrimary.text.toString().trim())
                    .putString("slipnet_remote_dns_fallback", remoteDnsFallback.text.toString().trim())
                    .putBoolean("slipnet_domain_routing_enabled", domainRouting.isChecked)
                    .putString("slipnet_domain_routing_mode", domainRoutingMode.selectedItem.toString())
                    .putString("slipnet_domain_routing_domains", routedDomains.text.toString().trim())
                    .putBoolean("slipnet_geo_bypass_enabled", geoBypass.isChecked)
                    .putString("slipnet_geo_bypass_country", geoCode)
                    .putBoolean("slipnet_vless_sni_fragment", sniFragmentEnabled.isChecked)
                    .putString("slipnet_vless_sni", vlessSni.text.toString().trim())
                    .putString("slipnet_vless_sni_strategy", sniFragmentStrategy.selectedItem.toString())
                    .putInt("slipnet_vless_sni_delay", sniDelayValue)
                    .putInt("slipnet_vless_tcp_mss", tcpMssValue)
                    .putBoolean("slipnet_vless_ch_padding", chPadding.isChecked)
                    .putBoolean("slipnet_vless_ws_obfuscation", wsObfuscation.isChecked)
                    .putBoolean("slipnet_vless_ws_padding", wsPadding.isChecked)
                    .putString("slipnet_vless_uuid", vlessUuid.text.toString().trim())
                    .putString("slipnet_vless_security", vlessSecurity.selectedItem.toString())
                    .putString("slipnet_vless_transport", vlessTransport.selectedItem.toString())
                    .putString("slipnet_vless_ws_path", vlessWsPath.text.toString().trim())
                    .putString("slipnet_vless_cdn_ip", vlessCdnIp.text.toString().trim())
                    .putInt("slipnet_vless_cdn_port", vlessPortValue)
                    .putInt("slipnet_naive_port", naivePortValue)
                    .putString("slipnet_naive_username", naiveUsername.text.toString())
                    .putString("slipnet_naive_password", naivePassword.text.toString())
                    .putBoolean("slipnet_http_proxy_append", proxyHttpAppend.isChecked)
                    .putBoolean("slipnet_debug_logging", debugLogging.isChecked)
                    .putString("slipnet_dark_mode", slipnetTheme.selectedItem.toString())
                    .putString("slipnet_tor_bridge_mode", torBridgeMode.selectedItem.toString())
                    .putString("slipnet_tor_bridges", torBridgeLines.text.toString().trim())
                    .putBoolean("slipnet_tor_fallback", torFallback.isChecked)
                    .putBoolean("slipnet_ssh_tls", sshTls.isChecked)
                    .putBoolean("slipnet_ssh_ws", sshWs.isChecked)
                    .putBoolean("slipnet_auto_reconnect", dnsFallback.isChecked)
                    .putBoolean("slipnet_split_tunnel", splitTunnel.isChecked)
                    .apply()

                var torProfileSaved = false
                derivedTorUri?.let { updatedRaw ->
                    val original = selected
                    if (original != null) {
                        val derivedName = "${original.name} • ${torBridgeMode.selectedItem}"
                        val derived = ServerItem(derivedName, updatedRaw, ConfigEngine.SLIPNET, ProfileParser.parse(updatedRaw), true, original.country)
                        val locals = loadLocalProfiles().filterNot { it.raw.trim() == updatedRaw.trim() }.toMutableList()
                        locals.add(derived)
                        if (persistLocalProfiles(locals)) {
                            servers = mergeSavedAndRemote(servers.filterNot { it.local })
                            serversState = ServersState.READY
                            setConfig(updatedRaw)
                            rebuildServerList(-1)
                            bindServerSummary()
                            torProfileSaved = true
                        } else {
                            toast("تنظیمات ذخیره شدند، اما ذخیره پروفایل محلی ناموفق بود.")
                        }
                    }
                }
                dialog.dismiss()
                when {
                    torProfileSaved -> toast("تنظیمات Tor در یک پروفایل محلی جداگانه ذخیره شد. موتور اتصال SlipNet هنوز داخل این APK نیست.")
                    torEditUnavailable -> toast("تنظیمات عمومی ذخیره شد؛ لینک Tor رمزگذاری‌شده یا قدیمی قابل ویرایش مستقیم نیست.")
                    else -> toast("SlipNet preferences saved. Native tunnel engines are not yet bundled in this APK.")
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showSlipNetRuntimeNotice() {
        val profile = selectedItem()?.takeIf { it.engine == ConfigEngine.SLIPNET }?.let { EngineProfiles.decodeSlipNet(it.raw) }
        val proto = profile?.protocol?.ifBlank { "encrypted profile" } ?: "SlipNet"
        val torDetails = profile?.takeIf { it.protocol == "snowflake" }?.let {
            if (it.torBridgeLines.isBlank()) "This Tor profile uses the built-in Snowflake bridge preset."
            else "Tor bridge configuration was decoded (${it.torBridgeLines.lineSequence().count { line -> line.isNotBlank() }} bridge line(s))."
        }.orEmpty()
        val specific = if (profile?.protocol == "snowflake") "Tor/Snowflake was imported and identified. $torDetails However, Tor's native runtime and pluggable-transport binaries are not bundled in this APK yet." else "The profile was imported and retained, but SlipNet's native DNS/QUIC/SSH/Tor runtime is not bundled in this APK yet."
        dialogBuilder()
            .setTitle("SlipNet runtime not included")
            .setMessage("$specific\n\nDetected profile: $proto. Its preferences can be edited inside TIREXNET. The app will not open SlipNet separately or show a false Connected state.")
            .setPositiveButton("SlipNet settings") { _, _ -> showSlipNetDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startAuxiliaryProxyEngine(engine: ConfigEngine, raw: String) {
        val label = when (engine) {
            ConfigEngine.MHRV -> "MHRV"
            ConfigEngine.DNS_TUNNEL -> "StormDNS"
            ConfigEngine.SNI_SPOOF -> "Patterniha SNI"
            else -> "Tunnel"
        }
        applyStatus("Starting $label engine…", false)
        io.execute {
            val error = when (engine) {
                ConfigEngine.MHRV -> MhrvEngine.start(this, raw)
                ConfigEngine.DNS_TUNNEL -> StormDnsEngine.start(this, raw)
                ConfigEngine.SNI_SPOOF -> SniSpoofEngine.start(this, raw)
                else -> "Unsupported auxiliary engine."
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    stopAuxiliaryEngine(engine)
                    return@runOnUiThread
                }
                if (error != null) {
                    applyStatus(error, false)
                    toast(error)
                    return@runOnUiThread
                }
                val intent = Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, raw)
                    .putExtra(XrayVpnService.EXTRA_ENGINE, engine.name)
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    // MHRV creates its local CA during the first successful native startup.
                    // Export it automatically so the user can install it from Android Settings.
                    if (engine == ConfigEngine.MHRV) {
                        io.execute {
                            val cert = runCatching { MhrvCertificate.exportToDownloads(this) }.getOrNull()
                            if (cert != null) runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    toast("MHRV certificate saved to ${cert.location}. Install it from Android Security settings.")
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    stopAuxiliaryEngine(engine)
                    applyStatus("The VPN bridge could not start.", false)
                }
            }
        }
    }

    private fun stopAuxiliaryEngine(engine: ConfigEngine) {
        when (engine) {
            ConfigEngine.MHRV -> MhrvEngine.stop()
            ConfigEngine.DNS_TUNNEL -> StormDnsEngine.stop()
            ConfigEngine.SNI_SPOOF -> SniSpoofEngine.stop()
            else -> Unit
        }
    }

    private fun exportMhrvCa() {
        toast("Preparing MHRV certificate…")
        io.execute {
            val result = runCatching { MhrvCertificate.exportToDownloads(this) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    dialogBuilder()
                        .setTitle("MHRV certificate not ready")
                        .setMessage("MHRV creates its local CA when the native engine starts. Connect an MHRV profile once, disconnect, then export the certificate again. Also check that this APK was built with the MHRV native libraries.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val trusted = MhrvCertificate.isInstalled(this, result.fingerprint)
                val message = buildString {
                    append("Saved to ${result.location}\n\n")
                    append("Certificate: ${result.subject}\n")
                    append("SHA-256: ${result.fingerprint}\n\n")
                    if (trusted) append("This certificate is already present in Android's user/system CA store.")
                    else append("To trust it on this phone:\n1. Open Android Settings.\n2. Search for ‘CA certificate’.\n3. Choose Install a certificate → CA certificate.\n4. Select TIREXNET-MHRV-CA.crt from Downloads and confirm the warning.")
                    append("\n\nNote: Android apps can choose not to trust user-installed certificates. This does not bypass app-level certificate pinning.")
                }
                dialogBuilder().setTitle(if (trusted) "MHRV certificate ready" else "MHRV certificate downloaded")
                    .setMessage(message)
                    .setNegativeButton("Done", null)
                    .setPositiveButton("Open Android Settings") { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }.show()
            }
        }
    }

    private fun showPingUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://www.gstatic.com/generate_204"
            setSingleLine(true)
            textSize = 13f
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            background = fieldBackground(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("ping_test_url", "https://www.gstatic.com/generate_204"))
            selectAll()
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(18), dpi(8), dpi(18), 0) }
        box.addView(tv("This check measures the response time of the website from the phone's normal internet connection. It is separate from each server's endpoint ping.", 11f, Palette.muted, 0), lp(MATCH, WRAP, 0, 10))
        box.addView(input, lp(MATCH, dpi(48), 6))
        val dialog = dialogBuilder().setTitle("WEBSITE PING TARGET").setView(box)
            .setNegativeButton("Cancel", null).setNeutralButton("Test now", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val value = input.text.toString().trim()
                if (!validPingUrl(value)) { input.background = fieldBackground(true); toast("Enter a valid HTTP or HTTPS URL."); return@setOnClickListener }
                prefs.edit().putString("ping_test_url", value).apply()
                testPingTarget(value)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (!validPingUrl(value)) { input.background = fieldBackground(true); toast("Enter a valid HTTP or HTTPS URL."); return@setOnClickListener }
                prefs.edit().putString("ping_test_url", value).apply()
                dialog.dismiss()
                toast("Website ping target saved.")
            }
        }
        dialog.show()
    }

    private fun validPingUrl(value: String): Boolean = runCatching {
        val u = URL(value)
        (u.protocol.equals("http", true) || u.protocol.equals("https", true)) && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun testPingTarget(value: String) {
        io.execute {
            val network = phoneNetwork()
            var resultText = "Website check failed."
            try {
                val url = URL(value)
                val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                connection.setRequestProperty("Range", "bytes=0-0")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.useCaches = false
                val started = SystemClock.elapsedRealtimeNanos()
                val code = connection.responseCode
                val elapsed = ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L).coerceAtLeast(1L)
                runCatching { (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.read() } }
                resultText = "Website response: ${elapsed} ms (HTTP $code)"
                connection.disconnect()
            } catch (_: Throwable) {
                resultText = "Website is unreachable from the phone's current internet connection."
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) toast(resultText) }
        }
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

    private data class SplitAppChoice(val packageName: String, val label: String)

    private fun showSplitTunnelDialog() {
        val selected = (prefs.getStringSet("split_packages", emptySet()) ?: emptySet()).toMutableSet()
        var mode = prefs.getString("split_mode", "exclude") ?: "exclude"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(8), dpi(16), dpi(4)) }
        root.addView(tv("Choose how apps use the VPN", 14f, Palette.text, 2), lp(MATCH, WRAP, 4))
        root.addView(tv("Choose which apps use the Xray VPN. Changes take effect after reconnecting.", 11f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun refreshModes() {
            modes.removeAllViews()
            fun button(label: String, value: String): TextView = tv(label, 10.5f, if (mode == value) Palette.ice else Palette.muted, 2).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dpi(12).toFloat(); setColor(if (mode == value) Palette.bg2 else Palette.bg1); setStroke(dpi(1), if (mode == value) Palette.cyan else Palette.stroke) }
                setPadding(dpi(8), dpi(11), dpi(8), dpi(11))
                setOnClickListener { mode = value; refreshModes() }
            }
            modes.addView(button("Only selected", "include"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dpi(5) })
            modes.addView(button("Exclude selected", "exclude"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(5) })
        }
        refreshModes()
        root.addView(modes, lp(MATCH, WRAP, 10))
        val search = EditText(this).apply { hint = "Search installed apps"; textSize = 13f; setSingleLine(true); setPadding(dpi(12), dpi(9), dpi(12), dpi(9)); background = fieldBackground(false) }
        root.addView(search, lp(MATCH, dpi(44), 8))
        val loading = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(loading, lp(dpi(32), dpi(32), 10).apply { gravity = Gravity.CENTER })
        val countLabel = tv("Loading apps…", 10f, Palette.muted, 0)
        root.addView(countLabel, lp(MATCH, WRAP, 4))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, dpi(300)))
        var apps: List<SplitAppChoice> = emptyList()
        var loaded = false
        fun renderApps(query: String) {
            if (!loaded) return
            list.removeAllViews()
            val filtered = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
            val visible = filtered.take(80)
            countLabel.text = if (filtered.size > visible.size) "Showing ${visible.size} of ${filtered.size} apps" else "${filtered.size} apps"
            visible.forEach { info ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(8), dpi(5), dpi(8), dpi(5)) }
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(6), 0, dpi(4), 0) }
                labels.addView(tv(info.label, 12.5f, Palette.text, 1))
                labels.addView(tv(info.packageName, 8.5f, Palette.dim, 0))
                row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
                val check = android.widget.CheckBox(this).apply {
                    buttonTintList = ColorStateList.valueOf(Palette.cyan)
                    isChecked = selected.contains(info.packageName)
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(info.packageName) else selected.remove(info.packageName) }
                }
                row.addView(check, lp(WRAP, WRAP))
                list.addView(row, lp(MATCH, WRAP, 1))
            }
            if (filtered.isEmpty()) list.addView(tv("No matching apps", 12f, Palette.muted, 0).apply { gravity = Gravity.CENTER }, lp(MATCH, dpi(48)))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val dialog = dialogBuilder().setTitle("SPLIT TUNNELING").setView(root)
            .setNegativeButton("Cancel", null).setNeutralButton("Clear", null).setPositiveButton("Save", null).create()
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
            packageExecutor.execute {
                val pm = packageManager
                val result = runCatching {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .asSequence()
                        .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                        .map { SplitAppChoice(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)) }
                        .sortedBy { it.label.lowercase(Locale.ROOT) }
                        .toList()
                }.getOrDefault(emptyList())
                runOnUiThread {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        apps = result
                        loaded = true
                        loading.visibility = View.GONE
                        renderApps(search.text?.toString().orEmpty())
                    }
                }
            }
        }
        dialog.show()
    }

    private fun friendlyConnectionError(raw: String): String {
        val v = raw.lowercase(Locale.US)
        return when {
            v.contains("permission") || v.contains("denied") || v.contains("revoked") -> "VPN permission was not granted. Please allow the connection request."
            v.contains("dns") || v.contains("network") || v.contains("timeout") || v.contains("unreachable") -> "The server did not respond. Check your internet connection or choose another server."
            v.contains("config") || v.contains("invalid") || v.contains("parse") || v.contains("json") -> "This profile could not be started. Edit it or choose another profile."
            v.contains("split") || v.contains("application") -> "App routing settings could not be applied. Reset Split tunneling and try again."
            else -> "Connection failed. Check the selected profile and try again."
        }
    }

    private fun appendConnectionLog(message: String) {
        val clean = ConfigSanitizer.redact(message).replace(Regex("\\s+"), " ").take(140)
        if (clean.isBlank() || connectionLogs.lastOrNull()?.endsWith(clean) == true) return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        connectionLogs.add("$stamp  $clean")
        while (connectionLogs.size > 100) connectionLogs.removeAt(0)
        val arr = JSONArray()
        connectionLogs.forEach { arr.put(it) }
        prefs.edit().putString("connection_logs", arr.toString()).apply()
    }

    private fun loadConnectionLogs(): List<String> {
        val arr = runCatching { JSONArray(prefs.getString("connection_logs", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { line -> line.isNotBlank() } }
    }

    private fun showConnectionLogs() {
        connectionLogs.clear()
        connectionLogs.addAll(loadConnectionLogs())
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14), dpi(6), dpi(14), dpi(6)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        fun populate() {
            body.removeAllViews()
            if (connectionLogs.isEmpty()) body.addView(tv("No connection activity yet.", 12f, Palette.muted, 0), lp(MATCH, WRAP, 12))
            else connectionLogs.asReversed().forEach { line -> body.addView(tv(line, 11f, Palette.text, 0), lp(MATCH, WRAP, 7)) }
        }
        populate()
        dialogBuilder().setTitle("CONNECTION LOGS").setView(scroll)
            .setNeutralButton("Clear", null).setPositiveButton("Done", null)
            .create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        connectionLogs.clear(); prefs.edit().remove("connection_logs").apply(); populate()
                    }
                }
                d.show()
            }
    }

    private fun connectionModeSummary(): String = when (prefs.getString("connection_mode", "vpn")) {
        "proxy" -> "127.0.0.1:10880"
        else -> "VPN mode"
    }

    private fun showConnectionModeDialog() {
        val currentMode = prefs.getString("connection_mode", "vpn") ?: "vpn"
        val currentProtocol = prefs.getString("proxy_protocol", "socks") ?: "socks"
        val options = arrayOf("VPN — all apps", "Local SOCKS5 proxy", "Local HTTP proxy")
        val checked = when {
            currentMode != "proxy" -> 0
            currentProtocol == "http" -> 2
            else -> 1
        }
        dialogBuilder().setTitle("CONNECTION MODE")
            .setSingleChoiceItems(options, checked) { d, which ->
                val mode = if (which == 0) "vpn" else "proxy"
                val proto = if (which == 2) "http" else "socks"
                prefs.edit().putString("connection_mode", mode).putString("proxy_protocol", proto).apply()
                d.dismiss()
                if (state == OrbState.CONNECTED || state == OrbState.CONNECTING) {
                    toast("Mode saved. Disconnect and reconnect to apply.")
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy address", null)
            .create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("TIREXNET local proxy", "127.0.0.1:10880"))
                        toast("Local proxy address copied.")
                    }
                }
                d.show()
            }
    }

    private fun configKey(raw: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(raw.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(raw.trim().hashCode().toString())

    private fun showServerActions(item: ServerItem) {
        // Subscription data is immutable: no edit/delete action may change or hide it.
        if (!item.local) {
            toast("Subscription profiles are read-only. Add a local copy to customize it.")
            return
        }
        val options = if (item.engine == ConfigEngine.XRAY) arrayOf("Edit profile", "Change TLS serverName", "Remove profile") else arrayOf("Edit profile", "Remove profile")
        dialogBuilder().setTitle(item.name).setItems(options) { _, which ->
            when (options[which]) {
                "Edit profile" -> editServerDialog(item)
                "Change TLS serverName" -> showSniDialog(item)
                "Remove profile" -> removeServerProfile(item)
            }
        }.show()
    }

    private fun editServerDialog(item: ServerItem) {
        if (!item.local) {
            toast("Subscription profiles cannot be edited. Import or duplicate one as a local profile first.")
            return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(8), dpi(16), dpi(4)) }
        val name = EditText(this).apply { setSingleLine(true); hint = "Profile name"; setText(item.name); textSize = 13f; background = fieldBackground(false); setPadding(dpi(10), dpi(8), dpi(10), dpi(8)) }
        val raw = EditText(this).apply { hint = "Paste or edit the profile"; setText(item.raw); textSize = 10.5f; minLines = 5; maxLines = 9; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; background = fieldBackground(false); setPadding(dpi(10), dpi(8), dpi(10), dpi(8)) }
        form.addView(tv("Profile name", 11f, Palette.muted, 0))
        form.addView(name, lp(MATCH, dpi(46), 4))
        form.addView(tv("Configuration", 11f, Palette.muted, 0), lp(MATCH, WRAP, 10))
        form.addView(raw, lp(MATCH, dpi(180), 4))
        val dialog = dialogBuilder().setTitle("EDIT PROFILE").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedRaw = raw.text.toString().trim()
                val engine = ConfigDetector.detect(updatedRaw)
                if (engine == ConfigEngine.UNKNOWN) { toast("This profile format is not supported."); return@setOnClickListener }
                val profileName = name.text.toString().trim().ifBlank { item.name }
                val meta = ProfileParser.parse(updatedRaw)
                val updated = ServerItem(profileName.take(80), updatedRaw, engine, meta, true, item.country)
                val locals = loadLocalProfiles().filterNot { it.raw.trim() == item.raw.trim() }.toMutableList()
                locals.add(updated)
                if (!persistLocalProfiles(locals)) { toast("Could not save this profile."); return@setOnClickListener }
                servers = mergeSavedAndRemote(servers.filterNot { it.local })
                serversState = ServersState.READY
                if (currentConfig().trim() == item.raw.trim()) setConfig(updatedRaw) else rebuildServerList(-1)
                dialog.dismiss()
                toast("Profile saved.")
            }
        }
        dialog.show()
    }

    private fun removeServerProfile(item: ServerItem) {
        if (!item.local) {
            toast("Subscription profiles cannot be removed from the subscription.")
            return
        }
        dialogBuilder().setTitle("Remove profile?").setMessage("This profile will be removed from your server list on this phone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                // This dialog can only be opened for a local profile. Remote
                // subscription items are never written to a hidden/deleted list.
                persistLocalProfiles(loadLocalProfiles().filterNot { it.raw.trim() == item.raw.trim() })
                if (currentConfig().trim() == item.raw.trim()) prefs.edit().remove("last_config").apply()
                servers = mergeSavedAndRemote(servers.filterNot { it.local }); serversState = ServersState.READY
                rebuildServerList(-1); bindServerSummary(); toast("Profile removed.")
            }.show()
    }

    private fun extractSni(raw: String): String {
        return runCatching {
            when {
                raw.startsWith("vless://") || raw.startsWith("trojan://") -> android.net.Uri.parse(raw).getQueryParameter("sni").orEmpty()
                raw.startsWith("vmess://") -> {
                    val encoded = raw.substringAfter("vmess://").substringBefore('#')
                    val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).toString(Charsets.UTF_8)
                    JSONObject(decoded).optString("sni", "")
                }
                raw.trimStart().startsWith("{") -> {
                    val obj = JSONObject(raw); val out = obj.optJSONArray("outbounds")?.optJSONObject(0)
                    val stream = out?.optJSONObject("streamSettings")
                    stream?.optJSONObject("tlsSettings")?.optString("serverName", "")
                        ?.ifBlank { stream.optJSONObject("realitySettings")?.optString("serverName", "").orEmpty() }.orEmpty()
                }
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun withSni(raw: String, sni: String): String? = runCatching {
        when {
            raw.startsWith("vless://") || raw.startsWith("trojan://") -> {
                val uri = android.net.Uri.parse(raw)
                val b = uri.buildUpon().clearQuery()
                uri.queryParameterNames.forEach { key ->
                    if (!key.equals("sni", true)) uri.getQueryParameters(key).forEach { v -> b.appendQueryParameter(key, v) }
                }
                if (sni.isNotBlank()) b.appendQueryParameter("sni", sni)
                b.build().toString()
            }
            raw.startsWith("vmess://") -> {
                val encoded = raw.substringAfter("vmess://").substringBefore('#')
                val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).toString(Charsets.UTF_8)
                val obj = JSONObject(decoded)
                if (sni.isBlank()) obj.remove("sni") else obj.put("sni", sni)
                val payload = android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                "vmess://$payload" + raw.substringAfter('#', "").let { if (it.isBlank()) "" else "#$it" }
            }
            raw.trimStart().startsWith("{") -> {
                val obj = JSONObject(raw)
                val arr = obj.optJSONArray("outbounds") ?: return null
                val out = arr.optJSONObject(0) ?: return null
                val stream = out.optJSONObject("streamSettings") ?: return null
                when (stream.optString("security")) {
                    "tls" -> { val tls = stream.optJSONObject("tlsSettings") ?: JSONObject().also { stream.put("tlsSettings", it) }; if (sni.isBlank()) tls.remove("serverName") else tls.put("serverName", sni) }
                    "reality" -> { val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }; if (sni.isBlank()) reality.remove("serverName") else reality.put("serverName", sni) }
                    else -> return null
                }
                obj.toString()
            }
            else -> null
        }
    }.getOrNull()

    private fun showPatternihaBasePicker() {
        val candidates = servers.filter { it.engine == ConfigEngine.XRAY && EngineProfiles.supportsPatterniha(it.raw) }
        if (candidates.isEmpty()) {
            toast("ابتدا یک کانفیگ Xray با TLS یا REALITY وارد کن؛ اطلاعات احراز هویت از همان پروفایل حفظ می‌شود.")
            return
        }
        if (candidates.size == 1) {
            showPatternihaDialog(candidates.first())
            return
        }
        val labels = candidates.map { item -> "${flagEmoji(item.country)}  ${item.name}" }.toTypedArray()
        dialogBuilder().setTitle("Choose Xray base profile")
            .setItems(labels) { _, which -> showPatternihaDialog(candidates[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPatternihaDialog(item: ServerItem) {
        if (item.engine != ConfigEngine.XRAY) {
            toast("Patterniha needs an Xray TLS/REALITY profile as the credential base.")
            return
        }
        if (!EngineProfiles.supportsPatterniha(item.raw)) {
            toast("Choose a VLESS, VMess, Trojan or Shadowsocks profile with TLS/REALITY first.")
            return
        }

        fun input(hintText: String, value: String = "", numeric: Boolean = false): EditText = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            textSize = 13f
            setText(value)
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            background = fieldBackground(false)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val profileName = input("Profile name", "${item.name} • Patterniha")
        val ip = input("Upstream IPv4 address", item.meta.host.takeIf { it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) }.orEmpty())
        val port = input("Port", item.meta.port.takeIf { it in 1..65535 }?.toString() ?: "443", true)
        val website = input("Website / real TLS SNI (e.g. cdn.example.com)", extractSni(item.raw))
        val strategy = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("SNI split (recommended)", "Half", "Multi-fragment", "TLS record fragments"))
            val saved = prefs.getString("sni_fragment_strategy", "sni_split") ?: "sni_split"
            setSelection(listOf("sni_split", "half", "multi", "tls_record_frag").indexOf(saved).coerceAtLeast(0))
        }
        val delay = input("Fragment delay in seconds (0.01–1.00)", prefs.getString("sni_fragment_delay", "0.08") ?: "0.08")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), dpi(6), dpi(18), 0)
        }
        box.addView(tv(
            "Root is not required. TIREXNET uses SNISPF's unprivileged TLS fragmentation mode; the raw wrong-sequence packet method is not enabled. Your selected Xray profile supplies the UUID/password/keys, and this creates a separate local profile with the IP and SNI you enter.",
            11f, Palette.muted, 0
        ), lp(MATCH, WRAP, 0, 12))
        fun addField(label: String, field: EditText) {
            box.addView(tv(label, 10f, Palette.muted, 1), lp(MATCH, WRAP, 4))
            box.addView(field, lp(MATCH, dpi(46), 3, 7))
        }
        addField("PROFILE NAME", profileName)
        addField("UPSTREAM IP", ip)
        addField("PORT", port)
        addField("WEBSITE / TLS SNI", website)
        box.addView(tv("FRAGMENTATION STRATEGY", 10f, Palette.muted, 1), lp(MATCH, WRAP, 4))
        box.addView(strategy, lp(MATCH, dpi(44), 2, 6))
        addField("FRAGMENT DELAY", delay)

        val dialog = dialogBuilder().setTitle("PATTERNIHA • ROOTLESS SNI")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null).setPositiveButton("Create local profile", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = profileName.text.toString().trim().ifBlank { "${item.name} • Patterniha" }
                val host = ip.text.toString().trim()
                val targetPort = port.text.toString().toIntOrNull() ?: 0
                val rawSite = website.text.toString().trim()
                val site = runCatching {
                    val uri = android.net.Uri.parse(if (rawSite.contains("://")) rawSite else "https://$rawSite")
                    (uri.host ?: rawSite.substringBefore('/')).trim().trimEnd('.').lowercase(Locale.US)
                }.getOrDefault("")
                val validIp = host.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) && host.split('.').all { part ->
                    val n = part.toIntOrNull(); n != null && n in 0..255 && (part == "0" || !part.startsWith('0'))
                }
                val validSite = site.length <= 253 && site.split('.').size >= 2 && site.split('.').all {
                    it.isNotBlank() && it.length <= 63 && it.matches(Regex("(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$"))
                }
                val fragmentDelay = delay.text.toString().trim().toDoubleOrNull()
                if (name.length !in 1..80 || !validIp || targetPort !in 1..65535 || !validSite || fragmentDelay == null || fragmentDelay !in 0.01..1.0) {
                    toast("Enter a profile name, valid IPv4, port, website/SNI and fragment delay.")
                    return@setOnClickListener
                }
                val baseWithSni = withSni(item.raw, site)
                if (baseWithSni.isNullOrBlank()) {
                    toast("Could not set the SNI on this profile. Use a supported TLS/REALITY Xray profile.")
                    return@setOnClickListener
                }
                val method = listOf("sni_split", "half", "multi", "tls_record_frag").getOrElse(strategy.selectedItemPosition) { "sni_split" }
                val raw = EngineProfiles.encodeSniSpoof(name, baseWithSni, host, targetPort, site)
                val newItem = ServerItem(name, raw, ConfigEngine.SNI_SPOOF, ProfileParser.parse(raw), true, item.country)
                val locals = loadLocalProfiles().filterNot { it.raw.trim() == raw.trim() }.toMutableList()
                locals.add(newItem)
                if (!persistLocalProfiles(locals)) {
                    toast("Could not save the local Patterniha profile.")
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("sni_fragment_strategy", method)
                    .putString("sni_fragment_delay", fragmentDelay.toString())
                    .apply()
                servers = mergeSavedAndRemote(servers.filterNot { it.local })
                serversState = ServersState.READY
                setConfig(raw)
                rebuildServerList(-1)
                bindServerSummary()
                dialog.dismiss()
                toast("Rootless Patterniha profile created and selected.")
            }
        }
        dialog.show()
    }

    private fun showSniDialog(item: ServerItem) {
        val edit = EditText(this).apply { hint = "SNI hostname"; setSingleLine(true); setText(extractSni(item.raw)); textSize = 13f; setPadding(dpi(12), dpi(8), dpi(12), dpi(8)); background = fieldBackground(false) }
        dialogBuilder().setTitle("TLS SERVER NAME")
            .setMessage("Sets the TLS / REALITY serverName for this Xray profile.")
            .setView(edit).setNegativeButton("Cancel", null).setPositiveButton("Save", null)
            .create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updatedRaw = withSni(item.raw, edit.text.toString().trim())
                        if (updatedRaw.isNullOrBlank()) { toast("This profile does not support a separate SNI setting."); return@setOnClickListener }
                        val locals = loadLocalProfiles().filterNot { it.raw.trim() == item.raw.trim() }.toMutableList()
                        locals.add(ServerItem(item.name, updatedRaw, ConfigDetector.detect(updatedRaw), ProfileParser.parse(updatedRaw), true, item.country))
                        if (!persistLocalProfiles(locals)) { toast("Could not save this profile."); return@setOnClickListener }
                        // A derived SNI profile is a new local profile. Keep the original
                        // subscription entry untouched and visible.
                        servers = mergeSavedAndRemote(servers.filterNot { it.local }); serversState = ServersState.READY
                        if (currentConfig().trim() == item.raw.trim()) setConfig(updatedRaw) else rebuildServerList(-1)
                        d.dismiss(); toast("SNI setting saved.")
                    }
                }
                d.show()
            }
    }

    private fun cancelSmartConnect(showMessage: Boolean) {
        smartConnectRunId++
        smartConnectPendingCount = 0
        smartConnectResults.clear()
        if (smartConnectSwitch?.isChecked == true) {
            programmaticSmartSwitch = true
            smartConnectSwitch?.isChecked = false
            programmaticSmartSwitch = false
        }
        if (showMessage) toast("Smart Connect cancelled.")
    }

    private fun startSmartConnect() {
        if (state == OrbState.CONNECTED || state == OrbState.CONNECTING || state == OrbState.DISCONNECTING) {
            cancelSmartConnect(false); toast("Disconnect before using Smart Connect."); return
        }
        if (serversState != ServersState.READY || servers.isEmpty()) {
            cancelSmartConnect(false); ensureServersLoaded(); toast("Load your servers first, then try Smart Connect."); return
        }
        val regionServers = servers.filter { regionFilter == "ALL" || it.country.equals(regionFilter, true) }
        val candidates = (if (prefs.getString("connection_mode", "vpn") == "proxy")
            regionServers.filter { it.engine in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.SNI_SPOOF) }
        else regionServers.filter { it.engine != ConfigEngine.SLIPNET && it.meta.host.isNotBlank() }).take(60)
        if (candidates.isEmpty()) {
            cancelSmartConnect(false)
            toast(if (prefs.getString("connection_mode", "vpn") == "proxy") "No supported local-proxy profile is available." else "No server is available.")
            return
        }
        smartConnectRunId++
        val runId = smartConnectRunId
        smartConnectResults.clear()
        smartConnectPendingCount = candidates.size
        appendConnectionLog("Smart Connect tested up to 60 profiles")
        candidates.forEach { item ->
            val key = item.raw.trim()
            PingTester.test(key, item.meta, true) { result ->
                if (runId != smartConnectRunId || isFinishing || isDestroyed) return@test
                smartConnectResults[key] = result
                bindPing(key)
                if (sortByPing) rebuildServerList(-1)
                smartConnectPendingCount--
                if (smartConnectPendingCount <= 0) finishSmartConnect(runId, candidates)
            }
        }
    }

    private fun finishSmartConnect(runId: Int, candidates: List<ServerItem>) {
        if (runId != smartConnectRunId) return
        val best = candidates.mapNotNull { item ->
            val result = smartConnectResults[item.raw.trim()]
            if (result?.kind == PingKind.OK) item to result.ms else null
        }.minByOrNull { it.second }?.first
        programmaticSmartSwitch = true
        smartConnectSwitch?.isChecked = false
        programmaticSmartSwitch = false
        smartConnectPendingCount = 0
        if (best == null) {
            appendConnectionLog("Smart Connect could not find a reachable server")
            toast("No reachable server found. Choose a server manually.")
            return
        }
        setConfig(best.raw)
        appendConnectionLog("Smart Connect selected ${best.name}")
        toast("Fastest server selected. Connecting…")
        connect()
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
                runOnUiThread { toast("Could not check for updates. Please try again later.") }
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
                if (connection.responseCode !in 200..299) error("download unavailable")
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
                runOnUiThread { toast("The update could not be installed. Please try again.") }
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
        selectedLoginPanelId = prefs.getString("login_panel_id", selectedLoginPanelId)?.let {
            if (it.equals("alipanel", true) || it.equals("ali", true)) "alipanel" else "pasarguard"
        } ?: "pasarguard"
        usernameInput = field("Username", false)
        passwordInput = field("Password", true)
        loginPanelLabel = tv("Panel: ${if (selectedLoginPanelId == "alipanel") "AliPanel · WireGuard" else "PasarGuard · Xray"}  ·  Change", 12.5f, Palette.cyan, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dpi(46)
            background = Palette.glass(this@MainActivity, 13f, Palette.stroke, Palette.fieldFill, Palette.fieldFill)
            setOnClickListener { showLoginPanelPicker() }
        }
        card.addView(loginPanelLabel, lp(MATCH, dpi(46)))
        card.addView(usernameInput!!, lp(MATCH, dpi(54), 10))
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

    private fun showLoginPanelPicker() {
        val labels = arrayOf("PasarGuard · Xray", "AliPanel · WireGuard")
        val checked = if (selectedLoginPanelId == "alipanel") 1 else 0
        dialogBuilder().setTitle("Select subscription panel")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectedLoginPanelId = if (which == 1) "alipanel" else "pasarguard"
                prefs.edit().putString("login_panel_id", selectedLoginPanelId).apply()
                loginPanelLabel?.text = "Panel: ${labels[which]}  ·  Change"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun primaryButton(label: String, onClick: () -> Unit): TextView = tv(label, 14f, Palette.onPrimary, 2).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.06f
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.blue, Palette.cyan)).apply { cornerRadius = dp(16f) }
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
            OrbState.DISCONNECTED -> {
                title = "Start your\nconnection"
                desc = if (ConfigDetector.detect(currentConfig()) == ConfigEngine.SLIPNET)
                    "SlipNet profile imported. Configure it inside TIREXNET; its native tunnel runtime is not packaged in this build yet."
                else "Choose ALL or a country, then slide to connect."
                color = Palette.muted; chip = "OFFLINE"
            }
            OrbState.CONNECTING -> { title = "Connecting"; desc = statusMessage.ifBlank { "Starting the VPN tunnel…" }; color = Palette.cyan; chip = "CONNECTING" }
            OrbState.CONNECTED -> { title = "Connected"; desc = "Your connection is active."; color = Palette.mint; chip = "SECURE" }
            OrbState.DISCONNECTING -> { title = "Disconnecting"; desc = "Closing the tunnel…"; color = Palette.ice; chip = "CLOSING" }
            OrbState.FAILED -> { title = "Connection failed"; desc = statusMessage.take(160); color = Palette.red; chip = "ERROR" }
            OrbState.PERMISSION -> { title = "Permission required"; desc = "Allow Android VPN access to continue."; color = Palette.amber; chip = "PERMISSION" }
        }
        orb?.state = state
        connectSlider?.state = state
        statusTitle?.apply { text = if (state == OrbState.DISCONNECTED) "Start your\nconnection" else title; setTextColor(if (state == OrbState.DISCONNECTED) Palette.text else color) }
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

    private fun safeSavedStatus(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        val technical = Regex("(?i)(http\\s*\\d{3}|errno\\s*[:=]?\\s*\\d+|exit\\s*code\\s*[:=]?\\s*\\d+|\\b[A-Z][A-Z0-9_]{7,}\\b)").containsMatchIn(raw)
        return when {
            technical || listOf("failed", "error", "invalid", "exception", "timeout", "ناموفق", "خطا").any { lower.contains(it) } -> "Connection failed. Check the selected profile and try again."
            lower.contains("permission") || lower.contains("denied") -> "VPN permission is required to connect."
            else -> ConfigSanitizer.redact(raw).take(100)
        }
    }

    private fun applyStatus(rawMessage: String, active: Boolean) {
        val lowerRaw = rawMessage.lowercase(Locale.US)
        val hasTechnicalCode = Regex("(?i)(http\\s*\\d{3}|errno\\s*[:=]?\\s*\\d+|exit\\s*code\\s*[:=]?\\s*\\d+|\\b[A-Z][A-Z0-9_]{7,}\\b)").containsMatchIn(rawMessage)
        val isError = hasTechnicalCode || listOf("failed", "error", "invalid", "could not", "ناموفق", "خطا", "timeout", "exception").any { lowerRaw.contains(it) }
        val isPermission = lowerRaw.contains("denied") || lowerRaw.contains("revoked") || lowerRaw.contains("permission")
        val message = when {
            active -> "Connected securely."
            isPermission -> "VPN permission is required to connect."
            isError -> friendlyConnectionError(rawMessage)
            else -> ConfigSanitizer.redact(rawMessage).take(100)
        }
        statusMessage = message
        val lower = lowerRaw
        val next = when {
            active -> OrbState.CONNECTED
            isError -> OrbState.FAILED
            isPermission -> OrbState.PERMISSION
            lower.contains("disconnected") -> OrbState.DISCONNECTED
            else -> OrbState.CONNECTING
        }
        if (next == OrbState.CONNECTED) {
            if (connectedAtRt == 0L) { connectedAtRt = SystemClock.elapsedRealtime(); traffic.reset() }
        } else connectedAtRt = 0L
        val changed = state != next
        state = next
        if (changed || connectionLogs.lastOrNull()?.endsWith(message) != true) appendConnectionLog(message)
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
        val selected = selectedItem()
        val detectedEngine = ConfigDetector.detect(raw)
        val engine = when {
            selected?.panelId.equals("ali", ignoreCase = true) || selected?.panelId.equals("alipanel", ignoreCase = true) -> ConfigEngine.WIREGUARD
            selected != null && selected.engine != ConfigEngine.UNKNOWN -> selected.engine
            else -> detectedEngine
        }
        if (engine == ConfigEngine.UNKNOWN) {
            toast("فرمت کانفیگ شناسایی نشد. Xray / WireGuard / OpenVPN / DNS Changer / SlipNet لازم است.")
            showTab(1)
            return
        }
        if (engine == ConfigEngine.SLIPNET) {
            showSlipNetRuntimeNotice()
            return
        }
        val proxyMode = prefs.getString("connection_mode", "vpn") == "proxy"
        if (proxyMode && engine !in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.SNI_SPOOF)) {
            toast("Local proxy mode supports Xray, MHRV, StormDNS and Patterniha profiles only.")
            return
        }
        pendingRaw = raw
        pendingEngine = engine
        // The notification and the Quick Settings tile show this name; they can run without the app window.
        prefs.edit().putString("active_engine", engine.name)
            .putString(TirexNotifier.KEY_PROFILE_NAME, selectedItem()?.name.orEmpty()).apply()

        val permission = if (proxyMode) null else VpnService.prepare(this)
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
                    .putExtra(XrayVpnService.EXTRA_ENGINE, pendingEngine.name)
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
            ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.SNI_SPOOF -> startAuxiliaryProxyEngine(pendingEngine, pendingRaw)
            ConfigEngine.SLIPNET -> showSlipNetRuntimeNotice()
            ConfigEngine.DNS_CHANGER -> {
                applyStatus("Starting DNS Changer…", false)
                val intent = Intent(this, DnsChangerVpnService::class.java)
                    .setAction(DnsChangerVpnService.ACTION_START)
                    .putExtra(DnsChangerVpnService.EXTRA_PROFILE, pendingRaw)
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    handler.postDelayed({ reconcileState() }, 1300L)
                    handler.postDelayed({ reconcileState() }, 2600L)
                    handler.postDelayed({ reconcileState() }, 5200L)
                } catch (e: Throwable) {
                    applyStatus("DNS Changer could not start.", false)
                    toast("راه‌اندازی موتور DNS ناموفق بود.")
                }
            }
            else -> toast("یک پروفایل پشتیبانی‌شده انتخاب کن.")
        }
    }

    private fun disconnect() {
        val engine = prefs.getString("active_engine", "") ?: ""
        state = OrbState.DISCONNECTING
        statusMessage = "Disconnecting…"
        appendConnectionLog("Disconnecting…")
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
        if (engine == "DNS_CHANGER" || DnsChangerVpnService.isActive) {
            issued = true
            startService(Intent(this, DnsChangerVpnService::class.java).setAction(DnsChangerVpnService.ACTION_STOP))
        }
        if (engine in setOf("XRAY", "MHRV", "DNS_TUNNEL", "SNI_SPOOF") || XrayVpnService.isActive) {
            issued = true
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
        // Xray's ACTION_STOP owns auxiliary teardown. This delayed cleanup is only a
        // fallback for cases where the service cannot receive the stop intent.
        handler.postDelayed({
            if (!XrayVpnService.isActive) {
                MhrvEngine.stop()
                StormDnsEngine.stop()
                SniSpoofEngine.stop()
            }
        }, 900L)
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
        // Subscription profiles are immutable and cannot be hidden/deleted.
        // Ignore stale hidden_remote_configs values written by older builds.
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

    private data class ImportOutcome(
        val added: List<ServerItem>,
        val duplicates: Int,
        val invalid: Int,
        val persisted: Boolean,
        val bundleFailures: Int = 0
    )

    /** One structured config stays whole; otherwise every non-empty line is a separate share link. */
    private fun splitConfigs(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("{") || t.contains("[interface]", ignoreCase = true) ||
            ConfigDetector.detect(t) == ConfigEngine.OPENVPN) return listOf(t)

        val lines = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val slipSchemes = listOf("slipnet://", "slipnet-enc://", "slipnet-bundle-enc://")
        fun isSlipStart(line: String) = slipSchemes.any { line.startsWith(it, true) }
        if (lines.firstOrNull()?.let(::isSlipStart) == true) {
            // A share URI may be wrapped over several lines by chat apps.
            // Preserve separate profiles when each line starts a new URI;
            // otherwise join the wrapped Base64 payload back into one link.
            return if (lines.drop(1).any(::isSlipStart)) lines
            else listOf(lines.joinToString("").replace(' ', '+'))
        }
        return lines
    }

    /** Parses, de-duplicates and saves. Runs off the main thread. Never logs or returns config content. */
    private fun doImport(text: String, existingRaws: Set<String>, country: String = "", bundlePassword: String = ""): ImportOutcome {
        val expanded = ArrayList<String>()
        var bundleFailures = 0
        for (candidate in splitConfigs(text)) {
            if (candidate.startsWith("slipnet-bundle-enc://", true)) {
                val unpacked = if (bundlePassword.isNotBlank()) EngineProfiles.decodeSlipNetBundle(candidate, bundlePassword) else null
                if (unpacked.isNullOrEmpty()) bundleFailures++ else expanded.addAll(unpacked)
            } else {
                expanded.add(candidate)
            }
        }
        val candidates = expanded
        val saved = loadLocalProfiles().toMutableList()
        val known = HashSet<String>(existingRaws)
        saved.forEach { known.add(it.raw.trim()) }
        val added = ArrayList<ServerItem>()
        var dup = 0
        var bad = bundleFailures
        for (c in candidates) {
            val meta = ProfileParser.parse(c)
            val structured = c.startsWith("{")
            val ok = when (meta.engine) {
                ConfigEngine.UNKNOWN -> false
                ConfigEngine.XRAY -> structured || meta.host.isNotBlank()
                ConfigEngine.SLIPNET -> EngineProfiles.decodeSlipNet(c) != null
                ConfigEngine.DNS_CHANGER -> DnsChangerProfiles.decode(c) != null
                else -> meta.host.isNotBlank()
            }
            if (!ok) { bad++; continue }
            if (!known.add(c.trim())) { dup++; continue }
            val name = ProfileParser.bestName(listOf(meta.name), saved.size + added.size + 1)
            added.add(ServerItem(name, c.trim(), meta.engine, meta, true, country.ifEmpty { ProfileParser.flagCountryOf(name) }))
        }
        if (added.isEmpty()) return ImportOutcome(emptyList(), dup, bad, true, bundleFailures)
        val persisted = persistLocalProfiles(added + saved)
        return ImportOutcome(if (persisted) added else emptyList(), dup, bad, persisted, bundleFailures)
    }

    private fun importText(text: String, country: String = "") {
        importWithOptionalSlipNetPassword(text, country)
    }

    private fun importUri(uri: Uri) {
        io.execute {
            val text = try {
                contentResolver.openInputStream(uri)?.use { readLimited(it) }.orEmpty()
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) importFailed("فایل انتخاب‌شده قابل خواندن نیست.") }
                return@execute
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) importWithOptionalSlipNetPassword(text, "")
            }
        }
    }

    private fun importWithOptionalSlipNetPassword(text: String, country: String) {
        val existing = servers.map { it.raw.trim() }.toSet()
        val containsBundle = splitConfigs(text).any { it.startsWith("slipnet-bundle-enc://", true) }
        if (containsBundle) {
            val password = EditText(this).apply {
                hint = "SlipNet bundle password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                textSize = 13f
                setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
                background = fieldBackground(false)
            }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(18), dpi(6), dpi(18), dpi(2))
                addView(tv("This is a SlipNet encrypted profile bundle. Enter its export password to import all profiles.", 11f, Palette.muted, 0), lp(MATCH, WRAP, 0, 8))
                addView(password, lp(MATCH, dpi(46), 0, 4))
            }
            val dialog = dialogBuilder().setTitle("Unlock SlipNet bundle")
                .setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Import", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = password.text.toString()
                    if (value.isBlank()) { password.error = "Password required"; return@setOnClickListener }
                    dialog.dismiss()
                    runSlipNetImport(text, existing, country, value)
                }
            }
            dialog.show()
        } else {
            runSlipNetImport(text, existing, country, "")
        }
    }

    private fun runSlipNetImport(text: String, existing: Set<String>, country: String, bundlePassword: String) {
        io.execute {
            val outcome = doImport(text, existing, country, bundlePassword)
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
            val msg = when {
                o.bundleFailures > 0 -> "رمز بسته SlipNet اشتباه است یا فایل bundle خراب شده است."
                o.duplicates > 0 && o.invalid == 0 -> "این کانفیگ قبلاً در لیست هست."
                else -> "کانفیگ پشتیبانی‌شده‌ای پیدا نشد. Xray / WireGuard / AmneziaWG / OpenVPN / MHRV / StormDNS / Patterniha / SlipNet لازم است."
            }
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
        if (o.bundleFailures > 0) msg += " • رمز یا ساختار SlipNet bundle نامعتبر بود"
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
        ConfigEngine.MHRV -> "MHRV"
        ConfigEngine.DNS_TUNNEL -> "STORMDNS"
        ConfigEngine.DNS_CHANGER -> "DNS CHANGER"
        ConfigEngine.SNI_SPOOF -> "PATTERNIHA SNI"
        ConfigEngine.SLIPNET -> "SLIPNET • RUNTIME REQUIRED"
        else -> "NONE"
    }

    /** Selected-server card: profile name, flag and ping only. No engine, host or port. */
    private fun bindServerSummary() {
        homeRegionLabel?.text = regionLabel()
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
            require(total <= 5 * 1024 * 1024) { "File exceeds 5 MB." }
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
                val response = request("POST", "/api/login", null, JSONObject().put("username", user).put("password", pass).put("panel", selectedLoginPanelId))
                val newToken = response.optString("token")
                require(newToken.isNotBlank()) { response.optString("error", "Login token is missing.") }
                prefs.edit().putString("token", newToken).putString("token_base", lastBase).putString("login_panel_id", selectedLoginPanelId).apply()
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
                    toast(if ((e.message ?: "").contains("401") || (e.message ?: "").contains("invalid", true)) "نام کاربری یا رمز عبور درست نیست." else "ورود انجام نشد. اتصال اینترنت را بررسی کن و دوباره تلاش کن.")
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
                    404, 405 -> "ثبت‌نام در حال حاضر فعال نیست."
                    409 -> "این نام کاربری قبلاً ثبت شده است."
                    400, 422 -> "اطلاعات واردشده معتبر نیست. نام کاربری و رمز را بررسی کن."
                    else -> "ثبت‌نام انجام نشد. اتصال اینترنت را بررسی کن و دوباره تلاش کن."
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
                        accountError = "unavailable"
                        bindAccount()
                        toast("اطلاعات اشتراک در دسترس نیست. دوباره تلاش کن.")
                    }
                }
            }
        }
    }

    /** Resolve engine from trusted API metadata before falling back to raw-text detection.
     * AliPanel profiles are guaranteed to be classic WireGuard by the server API contract.
     */
    private fun resolveApiEngine(
        engineValue: String,
        panelIdValue: String,
        panelNameValue: String,
        fallback: ConfigEngine
    ): ConfigEngine {
        val engine = engineValue.trim().uppercase(Locale.US).replace('-', '_').replace(' ', '_')
        val panelId = panelIdValue.trim().lowercase(Locale.US)
        val panelName = panelNameValue.trim().lowercase(Locale.US)
        if (panelId in setOf("ali", "alipanel", "wireguard") || panelName == "alipanel") {
            return ConfigEngine.WIREGUARD
        }
        return when (engine) {
            "WIREGUARD", "WIRE_GUARD", "WG", "WIREGUARD_TUNNEL" -> ConfigEngine.WIREGUARD
            "AMNEZIAWG", "AMNEZIA_WG", "AWG" -> ConfigEngine.AMNEZIAWG
            else -> fallback
        }
    }

    private fun configNameFromApi(
        item: JSONObject?,
        meta: ProfileMeta,
        panelId: String,
        index: Int,
        raw: String = ""
    ): String {
        // AliPanel's subscription text labels each individual WireGuard profile
        // with a canonical line such as "# Name = Turkey". Prefer that exact label
        // over a generic panel username or the endpoint hostname.
        if (panelId.equals("ali", true) || panelId.equals("alipanel", true)) {
            val aliCommentName = Regex("(?im)^\\s*#\\s*Name\\s*=\\s*(.+?)\\s*$")
                .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (aliCommentName.isNotBlank() && !ProfileParser.looksTechnical(aliCommentName)) return aliCommentName
        }
        // Prefer an actual tunnel/client/profile label over a generic account name.
        val keys = listOf(
            "wireguard_name", "wireguardName", "wg_name", "wgName",
            "config_name", "configName", "tunnel_name", "tunnelName",
            "profile_name", "profileName", "peer_name", "peerName",
            "client_name", "clientName", "display_name", "displayName",
            "remarks", "remark", "title", "label", "alias", "tag", "note",
            "name", "username", "user_name", "userName"
        )
        val apiNames = ArrayList<String>()
        fun collectNames(source: JSONObject?) {
            if (source == null) return
            keys.forEach { key ->
                val value = source.optString(key, "").trim()
                if (value.isNotBlank() && !value.equals("null", true)) apiNames.add(value)
            }
        }
        collectNames(item)
        // Some panel adapters nest the peer/client metadata instead of flattening it.
        if (item != null) {
            listOf("metadata", "meta", "client", "peer", "profile", "wireguard", "wg")
                .forEach { key -> collectNames(item.optJSONObject(key)) }
        }
        val preferred = apiNames.firstOrNull { !ProfileParser.looksTechnical(it) }
        if (!preferred.isNullOrBlank()) return preferred

        // Standard WireGuard files do not define a tunnel name. Some panels add a
        // harmless comment, which can preserve the original profile filename/label.
        val commentName = Regex("(?im)^\\s*#\\s*(?:name|profile|remark|tunnel)\\s*[:=]\\s*(.+?)\\s*$")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (commentName.isNotBlank() && !ProfileParser.looksTechnical(commentName)) return commentName

        val parsed = meta.name.trim()
        if (parsed.isNotBlank() && !ProfileParser.looksTechnical(parsed)) return parsed
        return if (panelId.equals("ali", true) || panelId.equals("alipanel", true)) {
            "WireGuard Profile $index"
        } else {
            ProfileParser.bestName(emptyList(), index)
        }
    }

    // Last successfully downloaded subscription list, kept on the phone for offline use.
    private fun saveRemoteCache(remote: List<ServerItem>) {
        val arr = JSONArray()
        remote.forEach { profile ->
            arr.put(JSONObject()
                .put("name", profile.name)
                .put("raw", profile.raw)
                .put("country", profile.country)
                .put("engine", profile.engine.name)
                .put("panelId", profile.panelId)
                .put("panelName", profile.panelName))
        }
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
            val panelId = o.optString("panelId", "")
            val panelName = o.optString("panelName", "")
            val engine = resolveApiEngine(o.optString("engine", ""), panelId, panelName, meta.engine)
            val name = configNameFromApi(o, meta, panelId, out.size + 1, raw)
            out.add(ServerItem(name, raw, engine, meta, false,
                normalizeCountry(o.optString("country", "")).ifEmpty { ProfileParser.flagCountryOf(name) },
                panelId, panelName))
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
                        val panelId = if (obj == null) "" else obj.optString("panelId", obj.optString("panel_id", ""))
                        val panelName = if (obj == null) "" else obj.optString("panelName", obj.optString("panel_name", ""))
                        val apiEngine = if (obj == null) "" else obj.optString("engine", obj.optString("protocol", ""))
                        val resolvedEngine = resolveApiEngine(apiEngine, panelId, panelName, meta.engine)

                        // The server's config name is authoritative. For AliPanel it is the
                        // client/peer label supplied by the panel adapter, not the endpoint host.
                        val displayName = configNameFromApi(obj, meta, panelId, remote.size + 1, raw)

                        // Country comes only from explicit panel metadata or a flag in the name.
                        val explicit = listOf("country_code", "countryCode", "cc", "country")
                            .map { k -> normalizeCountry(obj?.optString(k, "").orEmpty()) }
                            .firstOrNull { it.isNotEmpty() }.orEmpty()
                        val country = explicit.ifEmpty { ProfileParser.flagCountryOf(displayName) }
                        remote.add(ServerItem(displayName, raw, resolvedEngine, meta, false, country, panelId, panelName))
                    }
                }
                if (remote.isNotEmpty()) saveRemoteCache(remote) // keep for the next launch without internet
                runOnUiThread {
                    servers = mergeSavedAndRemote(remote)
                    serversState = ServersState.READY
                    serversError = ""
                    rebuildServerList(-1)
                    bindServerSummary()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // No internet / server unreachable: fall back to the saved list and local profiles.
                    val offline = mergeSavedAndRemote(loadRemoteCache())
                    if (offline.isNotEmpty()) {
                        servers = offline
                        serversState = ServersState.READY
                        serversError = "Connection unavailable"
                        rebuildServerList(-1)
                        bindServerSummary()
                        toast("اتصال به سرور برقرار نشد؛ لیست ذخیره‌شده‌ی قبلی نمایش داده شد.")
                    } else {
                        serversError = "Connection unavailable"
                        serversState = ServersState.ERROR
                        rebuildServerList(-1)
                        bindServerSummary()
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
        val tokenBase = prefs.getString("token_base", "").orEmpty().ifBlank { ApiConfig.WORKER_BASE }
        // Tokens issued by the retired Worker cannot authenticate against the VPS database.
        if (!bearer.isNullOrBlank() && tokenBase.isNotBlank() && tokenBase !in all) {
            prefs.edit().remove("token").remove("token_base").remove("remote_servers_cache").apply()
            throw ApiUnavailableException("سرور برنامه تغییر کرده؛ لطفاً دوباره وارد حساب شو.")
        }
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
        quick.addView(ghostButton("Patterniha • Rootless SNI profile") { showPatternihaBasePicker() }, lp(MATCH, dpi(46), 8))
        val status = tv("", 12f, Palette.muted, 1).apply { visibility = View.GONE }
        addStatus = status
        quick.addView(status, lp(MATCH, WRAP, 12))
        col.addView(quick, lp(MATCH, WRAP, 8))

        // --- 2. Manual entry
        val manual = GlassCard(this, 22f, pad = 16)
        manual.addView(tv("MANUAL ENTRY", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        manual.addView(tv("Choose a protocol; DNS Changer creates an independent DNS-only connection profile.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
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
        rawCard.addView(tv("Paste Xray, WireGuard, OpenVPN, MHRV, StormDNS, Patterniha or SlipNet share links. SlipNet links can be imported here; runtime availability is shown in SlipNet settings.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
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
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.blue, Palette.cyan)).apply { cornerRadius = dp(20f) }
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
                    setSingleLine(f.kind != FieldKind.MULTILINE)
                    if (f.kind == FieldKind.MULTILINE) {
                        minLines = 3
                        maxLines = 6
                        gravity = Gravity.TOP or Gravity.START
                    }
                    setPadding(dpi(14), dpi(if (f.kind == FieldKind.MULTILINE) 10 else 0), dpi(14), dpi(10))
                    background = fieldBackground(false)
                    inputType = when (f.kind) {
                        FieldKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                        FieldKind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        FieldKind.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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
                box.addView(et, lp(MATCH, dpi(if (f.kind == FieldKind.MULTILINE) 116 else 50), 6))
            }
        }
        if (manualProtocol == ManualProtocol.DNS_CHANGER) {
            box.addView(tv(
                "Dedicated DNS mode • forwards classic DNS (UDP/53) to the selected resolvers. Other traffic stays on the normal network. Android allows only one active VPN at a time, so disconnect another VPN before using this profile.",
                10.5f, Palette.cyan, 0
            ), lp(MATCH, WRAP, 12))
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
