
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
import com.tirexnet.app.engine.ManualProfileDecoder
import com.tirexnet.app.engine.DnsResolverScanner
import com.tirexnet.app.engine.ProfileSettingsStore
import com.tirexnet.app.engine.TirexNetShare
import com.tirexnet.app.engine.MasterDnsProfiles
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
import com.tirexnet.app.engine.MasterDnsEngine
import com.tirexnet.app.engine.CottenDnsEngine
import com.tirexnet.app.engine.SniSpoofEngine
import com.tirexnet.app.engine.WgTunnelEngine
import com.tirexnet.app.engine.XrayVpnService
import com.tirexnet.app.ui.AmbientBackground
import com.tirexnet.app.ui.BrandMark
import com.tirexnet.app.ui.ConnectOrb
import com.tirexnet.app.ui.EmptyArt
import com.tirexnet.app.ui.FlagBadge
import com.tirexnet.app.ui.GlassCard
import com.tirexnet.app.ui.Kit
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val slipNetRuntime by lazy { TirexSlipNetRuntime(applicationContext) }
    private val slipNetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var slipNetObserver: Job? = null

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---- session / connection state
    // ---- session / connection state
private var token = ""

// Independent panel sessions
private var pasarguardToken = ""
private var alipanelToken = ""

private var pasarguardUsername = ""
private var alipanelUsername = ""

private var activePanelId = "pasarguard"

private fun restorePanelSessions() {
    val legacyToken = prefs.getString("token", "").orEmpty()
    val legacyPanel = prefs.getString("login_panel_id", "pasarguard").orEmpty()

    pasarguardToken = prefs.getString("session_pasarguard", "").orEmpty()
    alipanelToken = prefs.getString("session_alipanel", "").orEmpty()

    pasarguardUsername = prefs.getString("username_pasarguard", "").orEmpty()
    alipanelUsername = prefs.getString("username_alipanel", "").orEmpty()

    // Migrate the previous single-session login without losing it.
    if (legacyToken.isNotBlank() &&
        pasarguardToken.isBlank() &&
        alipanelToken.isBlank()
    ) {
        if (legacyPanel.equals("alipanel", true) || legacyPanel.equals("ali", true)) {
            alipanelToken = legacyToken
            alipanelUsername = prefs.getString("username", "").orEmpty()
            prefs.edit()
                .putString("session_alipanel", alipanelToken)
                .putString("username_alipanel", alipanelUsername)
                .apply()
        } else {
            pasarguardToken = legacyToken
            pasarguardUsername = prefs.getString("username", "").orEmpty()
            prefs.edit()
                .putString("session_pasarguard", pasarguardToken)
                .putString("username_pasarguard", pasarguardUsername)
                .apply()
        }
    }

    val savedActive = prefs.getString("active_panel_id", "").orEmpty()

    activePanelId = when {
        savedActive == "alipanel" && alipanelToken.isNotBlank() -> "alipanel"
        savedActive == "pasarguard" && pasarguardToken.isNotBlank() -> "pasarguard"
        pasarguardToken.isNotBlank() -> "pasarguard"
        alipanelToken.isNotBlank() -> "alipanel"
        else -> "pasarguard"
    }

    token = panelToken(activePanelId)

    prefs.edit()
        .putString("token", token)
        .putString("active_panel_id", activePanelId)
        .apply()
}

private fun panelToken(panelId: String): String {
    return when (panelId.lowercase()) {
        "ali", "alipanel" -> alipanelToken
        else -> pasarguardToken
    }
}

private fun savePanelSession(
    panelId: String,
    newToken: String,
    username: String
) {
    val editor = prefs.edit()

    when (panelId.lowercase()) {
        "ali", "alipanel" -> {
            alipanelToken = newToken
            alipanelUsername = username

            editor.putString("session_alipanel", newToken)
            editor.putString("username_alipanel", username)
        }

        else -> {
            pasarguardToken = newToken
            pasarguardUsername = username

            editor.putString("session_pasarguard", newToken)
            editor.putString("username_pasarguard", username)
        }
    }

    activePanelId = panelId.lowercase()
    token = newToken

    editor.putString("active_panel_id", activePanelId)
    editor.putString("token", newToken)
    editor.apply()
}
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
    private var manualGroupRow: LinearLayout? = null
    private var manualFieldsBox: LinearLayout? = null
    private var manualError: TextView? = null
    private var manualSaveBtn: TextView? = null
    private var rawInput: EditText? = null
    private var manualProtocol = ManualProtocol.VLESS
    private var manualCountry = ""
    private val manualValues = HashMap<String, String>()
    private val manualFieldViews = HashMap<String, EditText>()
    private var manualForm: Kit.FieldForm? = null

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
    private var addingPanelLogin = false
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

        restorePanelSessions()
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

        observeSlipNetConnection()
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
        slipNetObserver?.cancel()
        slipNetScope.cancel()
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

    private fun observeSlipNetConnection() {
        slipNetObserver?.cancel()
        slipNetObserver = slipNetScope.launch {
            slipNetRuntime.connectionState.collect { connection ->
                if (prefs.getString("active_engine", "") != "SLIPNET") {
                    return@collect
                }

                when (connection) {
                    is app.slipnet.domain.model.ConnectionState.Connected -> {
                        applyStatus("Connected.", true)
                    }

                    is app.slipnet.domain.model.ConnectionState.Connecting -> {
                        applyStatus("Starting SlipNet…", false)
                    }

                    is app.slipnet.domain.model.ConnectionState.Disconnecting -> {
                        applyStatus("Disconnecting…", false)
                    }

                    is app.slipnet.domain.model.ConnectionState.Error -> {
                        applyStatus("SlipNet error: ${connection.message}", false)
                        prefs.edit().putString("active_engine", "").apply()
                    }

                    is app.slipnet.domain.model.ConnectionState.Disconnected -> {
                        applyStatus("Disconnected.", false)
                        prefs.edit().putString("active_engine", "").apply()
                    }
                }
            }
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
        val slipNetActive = runCatching { slipNetRuntime.isConnected() }.getOrDefault(false)
        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || DnsChangerVpnService.isActive || slipNetActive
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

        if (token.isBlank() || addingPanelLogin) {
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
                box.addView(Kit.stateView(this, Kit.StateKind.ERROR, "Couldn't load configurations",
                    "Check your internet connection and try again.", "Retry") { loadServers() }, lp(MATCH, WRAP))
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
                    box.addView(Kit.stateView(this, Kit.StateKind.EMPTY, "No matching server", "Try another name or region."), lp(MATCH, WRAP, 10))
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

        val (sub, subBody) = Kit.card(this, "Subscription", Icon.PROFILE, subtitle = "Your account on the connected panel")
        profPanel = infoRow(subBody, "Panel user")
        profExpiry = infoRow(subBody, "Expires")
        profUsage = infoRow(subBody, "Data used")
        col.addView(sub, lp(MATCH, WRAP, 18))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ghostButton("Refresh account") { loadAccount(true) }, LinearLayout.LayoutParams(0, dpi(48), 1f).apply { rightMargin = dpi(6) })
        actions.addView(ghostButton("Log out") { confirmLogout() }, LinearLayout.LayoutParams(0, dpi(48), 1f).apply { leftMargin = dpi(6) })
        col.addView(actions, lp(MATCH, WRAP, 12))
        // Subscription panel management
val (panelCard, panelBody) = Kit.card(this, "Subscription panels", Icon.SERVERS, subtitle = "Xray and WireGuard accounts")

fun addPanelStatus(name: String, username: String, connected: Boolean) {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(10), 0, dpi(10))
    }

    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    info.addView(
        tv(name, 13f, Palette.text, 2)
    )

    info.addView(
        tv(
            if (connected) "Connected · $username" else "Not connected",
            10.5f,
            if (connected) Palette.mint else Palette.muted,
            0
        )
    )

    row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

    row.addView(Kit.pill(this, if (connected) "CONNECTED" else "OFFLINE", if (connected) Palette.mint else Palette.muted))

    panelBody.addView(row, lp(MATCH, WRAP))
}

addPanelStatus(
    "PasarGuard · Xray",
    pasarguardUsername,
    pasarguardToken.isNotBlank()
)

addPanelStatus(
    "AliPanel · WireGuard",
    alipanelUsername,
    alipanelToken.isNotBlank()
)

if (pasarguardToken.isBlank()) {
    panelBody.addView(
        primaryButton("Add Xray Profile") {
            openPanelLogin("pasarguard")
        },
        lp(MATCH, dpi(48), 8)
    )
}

if (alipanelToken.isBlank()) {
    panelBody.addView(
        primaryButton("Add WireGuard Profile") {
            openPanelLogin("alipanel")
        },
        lp(MATCH, dpi(48), 8)
    )
}

col.addView(panelCard, lp(MATCH, WRAP, 14))

        val anchor = tv("SETTINGS", 10f, Palette.cyan, 2).apply { letterSpacing = 0.22f }
        settingsAnchor = anchor
        col.addView(anchor, lp(WRAP, WRAP, 26, 8))

        // General settings first; specialist tools live in the collapsible Advanced card.
        val (gConn, gConnBody) = Kit.card(this, "Connection", Icon.POWER, subtitle = "Engine, mode and app routing")
        settingRow(gConnBody, Icon.INFO, "Active engine", "Detected from the selected profile", engineLabelShort()) { }
        settingRow(gConnBody, Icon.GLOBE, "Connection mode", "VPN or local SOCKS5 / HTTP proxy", connectionModeSummary()) { showConnectionModeDialog() }
        settingRow(gConnBody, Icon.SHIELD, "Split tunneling", "Choose apps included in or excluded from Xray", splitSummary()) { showSplitTunnelDialog() }
        col.addView(gConn, lp(MATCH, WRAP))

        val (gNet, gNetBody) = Kit.card(this, "Network & DNS", Icon.GLOBE, subtitle = "Tunnels, resolvers and latency checks")
        settingRow(gNetBody, Icon.GLOBE, "SlipNet protocols & settings", "DNS tunnels, resolver pools, SSH chains, Slipstream, NaiveProxy and Tor", "Open") { showSlipNetDialog() }
        settingRow(gNetBody, Icon.GLOBE, "Website ping target", "HTTP response-time check for a URL you choose", "Change") { showPingUrlDialog() }
        col.addView(gNet, lp(MATCH, WRAP, 12))

        val (gAdv, gAdvBody) = Kit.advancedCard(this, "TLS name, SNI fragmentation and certificates")
        settingRow(gAdvBody, Icon.BOLT, "TLS server name", "Edit TLS / REALITY serverName on a local copy", null) { selectedItem()?.let { showSniDialog(it) } ?: toast("Select a server first.") }
        settingRow(gAdvBody, Icon.SHIELD, "Patterniha SNI fragmentation", "Rootless TLS fragmentation • creates a local profile", "Configure") { selectedItem()?.let { showPatternihaDialog(it) } ?: toast("Select a TLS/REALITY server first.") }
        settingRow(gAdvBody, Icon.SHIELD, "MHRV CA certificate", "Download it, then install it from Android Security settings", "Export") { exportMhrvCa() }
        col.addView(gAdv, lp(MATCH, WRAP, 12))

        val (gData, gDataBody) = Kit.card(this, "Data & diagnostics", Icon.FILE, subtitle = "Logs, diagnostics and saved config")
        settingRow(gDataBody, Icon.COPY, "Connection logs", "Recent connection activity", "Open") { showConnectionLogs() }
        settingRow(gDataBody, Icon.COPY, "Copy diagnostics", "Safe device and connection summary", null) { copyDiagnostics() }
        Kit.settingRow(this, gDataBody, Icon.TRASH, "Clear saved config", "Remove the selected profile from this phone", null, Palette.red) { clearConfig() }
        col.addView(gData, lp(MATCH, WRAP, 12))

        val (gAbout, gAboutBody) = Kit.card(this, "About", Icon.INFO, subtitle = "Privacy, version and updates")
        settingRow(gAboutBody, Icon.SHIELD, "Privacy", "چه چیزی روی گوشی ذخیره می‌شود", null) { showPrivacy() }
        settingRow(gAboutBody, Icon.INFO, "App version", packageName, versionName()) { }
        settingRow(gAboutBody, Icon.REFRESH, "App updates", "بررسی نسخه جدید در GitHub و نصب APK", "Check") { checkForUpdates() }
        col.addView(gAbout, lp(MATCH, WRAP, 12))
        return sv
    }

    private fun showSlipNetDialog() {
        // Global SlipNet tunnel settings only. Protocol/profile-specific options live in Edit Profile.
        // Sections that previously lived after "DNS & resolvers" were removed from this dialog.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(10), dpi(16), dpi(8))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))

        var cur: LinearLayout = body
        fun heading(title: String, subtitle: String = "", icon: Icon = Icon.GLOBE) {
            val (c, b) = Kit.card(this, title, icon, subtitle = subtitle.ifBlank { null })
            body.addView(c, lp(MATCH, WRAP, 12))
            cur = b
        }
        fun field(label: String, key: String, hint: String, default: String = "", multiline: Boolean = false, secret: Boolean = false, numeric: Boolean = false): EditText {
            cur.addView(tv(label, 11.5f, Palette.muted, 1), lp(MATCH, WRAP, 10, 3))
            return EditText(this).apply {
                setText(prefs.all[key]?.toString() ?: default)
                this.hint = hint
                Kit.styleInput(this@MainActivity, this, multiline)
                if (multiline) { minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START }
                else setSingleLine(true)
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                cur.addView(this, lp(MATCH, if (multiline) dpi(84) else dpi(48), 0, 2))
            }
        }
        fun switch(label: String, key: String, default: Boolean, desc: String? = null): Switch =
            Kit.switchRow(this, cur, label, desc, prefs.getBoolean(key, default))
        fun spinner(label: String, key: String, options: List<String>, default: String): Spinner {
            cur.addView(tv(label, 11.5f, Palette.muted, 1), lp(MATCH, WRAP, 10, 3))
            val sp = Kit.spinner(this, options)
            val current = prefs.getString(key, default) ?: default
            val idx = options.indexOfFirst { it.equals(current, true) }.takeIf { it >= 0 }
                ?: options.indexOf(default).coerceAtLeast(0)
            sp.setSelection(idx)
            cur.addView(sp, lp(MATCH, dpi(48), 0, 2))
            return sp
        }

        // General: what most people touch.
        heading("SlipNet", "Global tunnel defaults applied by the SlipNet runtime. Per-profile protocol options are edited on each profile.", Icon.GLOBE)
        val protocol = spinner("Preferred protocol", "slipnet_protocol", listOf("Auto detect", "DNSTT", "NoizDNS", "VayDNS", "Soar", "Slipstream", "SSH", "NaiveProxy", "VLESS", "DoH", "Tor"), "Auto detect")
        val maxTunnels = field("Max parallel tunnels (1–64)", "slipnet_max_tunnels", "4", "4", numeric = true)
        val timeout = field("Connection timeout (seconds)", "slipnet_timeout", "20", "20", numeric = true)

        heading("Leak protection", "Switches that keep traffic inside the tunnel.", Icon.SHIELD)
        val disableQuic = switch("Disable QUIC fallback", "slipnet_disable_quic", true, "Stops apps from bypassing the tunnel over QUIC")
        val blockIpv6 = switch("Block IPv6 to avoid leaks", "slipnet_block_ipv6", true, "Prevents IPv6 traffic from leaving outside the tunnel")

        // Advanced: specialist tuning, collapsed by default.
        val (advCard, advBody) = Kit.advancedCard(this, "MTU, pools, buffers, limits and DNS workers")
        body.addView(advCard, lp(MATCH, WRAP, 12))
        cur = advBody
        val mtu = field("VPN MTU (512–1500)", "slipnet_mtu", "1280", "1280", numeric = true)
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
        val gso = switch("Enable UDP GSO where supported", "slipnet_gso", false, "Faster UDP on supported devices")

        body.addView(Kit.noteCard(this, "DNS resolvers and protocol-specific options are configured per profile in Edit Profile."), lp(MATCH, WRAP, 14, 8))

        root.addView(scroll, lp(MATCH, minOf(dpi(560), resources.displayMetrics.heightPixels * 3 / 4)))
        val dialog = dialogBuilder().setTitle("SlipNet settings")
            .setView(root).setNegativeButton("Close", null).setPositiveButton("Save settings", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun number(et: EditText, label: String, min: Int, max: Int): Int? {
                    val v = et.text.toString().trim().toIntOrNull()
                    if (v == null || v < min || v > max) { et.error = "$label must be $min–$max"; return null }
                    return v
                }
                val mtuValue = number(mtu, "MTU", 512, 1500) ?: return@setOnClickListener
                val maxTunnelsValue = number(maxTunnels, "Max tunnels", 1, 64) ?: return@setOnClickListener
                val timeoutValue = number(timeout, "Timeout", 5, 120) ?: return@setOnClickListener
                val dnsTimeoutValue = number(dnsTimeout, "DNS timeout", 500, 60000) ?: return@setOnClickListener
                val poolValue = number(connectionPoolSize, "Pool size", 1, 128) ?: return@setOnClickListener
                val keepAliveValue = number(keepAlive, "Keep-alive", 1000, 120000) ?: return@setOnClickListener
                val upValue = number(uploadLimit, "Upload limit", 0, 100000) ?: return@setOnClickListener
                val downValue = number(downloadLimit, "Download limit", 0, 100000) ?: return@setOnClickListener
                val workersKey = when (dnsWorkers.selectedItemPosition) {
                    1 -> "two"; 2 -> "three"; 3 -> "five"; else -> "per_query"
                }
                prefs.edit()
                    .putString("slipnet_protocol", protocol.selectedItem.toString())
                    .putInt("slipnet_mtu", mtuValue)
                    .putInt("slipnet_max_tunnels", maxTunnelsValue)
                    .putInt("slipnet_timeout", timeoutValue)
                    .putInt("slipnet_dns_timeout", dnsTimeoutValue)
                    .putInt("slipnet_connection_pool_size", poolValue)
                    .putString("slipnet_buffer_size", bufferSize.selectedItem.toString())
                    .putInt("slipnet_keepalive_ms", keepAliveValue)
                    .putString("slipnet_dns_workers", workersKey)
                    .putString("slipnet_congestion", congestion.selectedItem.toString())
                    .putInt("slipnet_upload_limit", upValue)
                    .putInt("slipnet_download_limit", downValue)
                    .putBoolean("slipnet_gso", gso.isChecked)
                    .putBoolean("slipnet_disable_quic", disableQuic.isChecked)
                    .putBoolean("slipnet_block_ipv6", blockIpv6.isChecked)
                    .apply()
                dialog.dismiss()
                toast("تنظیمات SlipNet ذخیره شد.")
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun startAuxiliaryProxyEngine(engine: ConfigEngine, raw: String) {
        val label = when (engine) {
            ConfigEngine.MHRV -> "MHRV"
            ConfigEngine.DNS_TUNNEL -> "StormDNS"
            ConfigEngine.MASTERDNS -> "MasterDNS"
            ConfigEngine.COTTENDNS -> "CottenDNS"
            ConfigEngine.SNI_SPOOF -> "Patterniha SNI"
            else -> "Tunnel"
        }
        applyStatus("Starting $label engine…", false)
        io.execute {
            val error = when (engine) {
                ConfigEngine.MHRV -> MhrvEngine.start(this, raw)
                ConfigEngine.DNS_TUNNEL -> StormDnsEngine.start(this, raw)
                ConfigEngine.MASTERDNS -> MasterDnsEngine.start(this, raw)
                ConfigEngine.COTTENDNS -> CottenDnsEngine.start(this, raw)
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
            ConfigEngine.MASTERDNS -> MasterDnsEngine.stop()
            ConfigEngine.COTTENDNS -> CottenDnsEngine.stop()
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
        box.addView(Kit.noteCard(this, "This check measures the response time of the website from the phone's normal internet connection. It is separate from each server's endpoint ping."), lp(MATCH, WRAP, 0, 10))
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
        Kit.settingRow(this, parent, icon, title, subtitle, value, onClick = onClick)
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
            modes.addView(
                Kit.segmented(this, listOf("include" to "Only selected", "exclude" to "Exclude selected"), mode) { mode = it; refreshModes() },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
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
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(8), dpi(7), dpi(8), dpi(7))
                    background = Palette.glass(this@MainActivity, 14f)
                }
                row.addView(tv(info.label.take(1).uppercase(Locale.US), 14f, Palette.cyan, 2).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Palette.withAlpha(Palette.cyan, 40)) }
                }, lp(dpi(36), dpi(36), 0, 0, 0, 4))
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
                row.setOnClickListener { check.toggle() }
                list.addView(row, lp(MATCH, WRAP, 6))
            }
            if (filtered.isEmpty()) list.addView(Kit.stateView(this, Kit.StateKind.EMPTY, "No matching apps", "Try a different name."), lp(MATCH, WRAP, 6))
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
            if (connectionLogs.isEmpty()) body.addView(Kit.stateView(this, Kit.StateKind.EMPTY, "No connection activity yet", "Connection events will appear here."), lp(MATCH, WRAP, 12))
            else connectionLogs.asReversed().forEach { line ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
                    background = Palette.glass(this@MainActivity, 12f)
                }
                row.addView(tv(line.take(8), 10.5f, Palette.cyan, 4), lp(WRAP, WRAP, 1, 0, 0, 10))
                row.addView(tv(line.drop(8).trim(), 11.5f, Palette.text, 0), LinearLayout.LayoutParams(0, WRAP, 1f))
                body.addView(row, lp(MATCH, WRAP, 6))
            }
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(8), dpi(16), dpi(4)) }
        box.addView(Kit.noteCard(this, "VPN routes the whole device. The proxy modes only expose a local address at 127.0.0.1:10880 for apps you configure."), lp(MATCH, WRAP, 4, 4))
        var dlg: AlertDialog? = null
        val items = listOf(
            Triple(Icon.SHIELD, options[0], "Routes all apps through the tunnel"),
            Triple(Icon.GLOBE, options[1], "Local proxy at 127.0.0.1:10880"),
            Triple(Icon.GLOBE, options[2], "Local proxy at 127.0.0.1:10880")
        )
        items.forEachIndexed { which, (icon, title, desc) ->
            box.addView(Kit.optionCard(this, icon, title, desc, which == checked) {
                val mode = if (which == 0) "vpn" else "proxy"
                val proto = if (which == 2) "http" else "socks"
                prefs.edit().putString("connection_mode", mode).putString("proxy_protocol", proto).apply()
                dlg?.dismiss()
                if (state == OrbState.CONNECTED || state == OrbState.CONNECTING) {
                    toast("Mode saved. Disconnect and reconnect to apply.")
                }
                render()
            }, lp(MATCH, WRAP, 8))
        }
        dialogBuilder().setTitle("CONNECTION MODE")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy address", null)
            .create().also { d ->
                dlg = d
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
        val settings = ProfileSettingsStore(this).get(item.raw)
        val origin = settings.origin
        val canShare = TirexNetShare.canShare(origin, item.local, item.panelId)
        val sheet = Kit.Sheet(
            this, item.name,
            "${engineName(item.engine)} • Local profile",
            flagBox(44, 14f, item.country, Palette.cyan)
        )
        sheet.action(Icon.EDIT, "Edit profile", "Change name, fields and DNS resolvers") { editServerDialog(item) }
        sheet.action(Icon.COPY, "Duplicate profile", "Create an independent local copy") { duplicateServerProfile(item) }
        if (canShare) sheet.action(Icon.GLOBE, "Share (TIREXNET)", "Send this profile to another TIREXNET user") { shareTirexNetProfile(item) }
        if (item.engine == ConfigEngine.XRAY) sheet.action(Icon.SHIELD, "Change TLS serverName", "Set the SNI used for the TLS handshake") { showSniDialog(item) }
        sheet.action(Icon.TRASH, "Remove profile", "Delete this profile from your device", danger = true) { removeServerProfile(item) }
        sheet.show()
    }

    private fun shareTirexNetProfile(item: ServerItem) {
        val store = ProfileSettingsStore(this)
        val settings = store.get(item.raw)
        if (!TirexNetShare.canShare(settings.origin, item.local, item.panelId)) {
            toast("Panel subscription profiles cannot be shared directly.")
            return
        }
        try {
            val result = TirexNetShare.encode(
                name = item.name,
                engine = item.engine,
                raw = item.raw,
                origin = if (settings.origin == TirexNetShare.ProfileOrigin.UNKNOWN)
                    TirexNetShare.ProfileOrigin.MANUAL else settings.origin,
                dnsResolvers = settings.dnsResolvers,
                selectedResolver = settings.selectedResolver,
                customSettings = settings.customSettings,
                country = item.country
            )
            showShareSheet(item.name, result.uri)
        } catch (e: TirexNetShare.ShareError.TooLarge) {
            toast(e.message ?: "URI too large for QR")
        } catch (e: Exception) {
            toast("Share failed: ${e.message ?: "unknown"}")
        }
    }

    private fun showShareSheet(title: String, uri: String) {
        val sheet = Kit.Sheet(this, title, "Encrypted TIREXNET share")
        sheet.content(Kit.noteCard(this, "Sensitive fields are encrypted. Casual readers cannot see host/port/credentials."))
        val preview = EditText(this).apply {
            setText(uri)
            textSize = 10f
            minLines = 3
            maxLines = 4
            isFocusable = false
            isClickable = false
            setTextColor(Palette.muted)
            background = fieldBackground(false)
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
        }
        sheet.content(preview, 10)
        sheet.action(Icon.COPY, "Copy", "Copy the encrypted share link") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tirexnet-share", uri))
            toast("Copied")
        }
        sheet.action(Icon.GLOBE, "Android Share", "Send it with any installed app") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
            startActivity(Intent.createChooser(intent, "Share profile"))
        }
        sheet.action(Icon.FILE, "QR", "Show it as a QR code through a QR app") { showShareQrDialog(uri) }
        sheet.show()
    }

    private fun showShareQrDialog(uri: String) {
        if (uri.length > 2800) {
            toast("URI too large for reliable QR code")
            return
        }
        try {
            // Lightweight QR via Android system share of text; full on-device QR bitmap
            // would need a library. We generate a simple instruction + copy fallback.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri)
            }
            // Prefer external QR apps if present
            val qrIntent = Intent("com.google.zxing.client.android.ENCODE").apply {
                putExtra("ENCODE_TYPE", "TEXT_TYPE")
                putExtra("ENCODE_DATA", uri)
            }
            if (qrIntent.resolveActivity(packageManager) != null) {
                startActivity(qrIntent)
            } else {
                toast("Install a QR generator app, or use Copy / Android Share")
                startActivity(Intent.createChooser(intent, "Share for QR"))
            }
        } catch (e: Exception) {
            toast("QR display failed: ${e.message}")
        }
    }


    private fun editServerDialog(item: ServerItem) {
        if (!item.local) {
            toast("Subscription profiles cannot be edited. Import or duplicate one as a local profile first.")
            return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16), dpi(8), dpi(16), dpi(4)) }
        val name = EditText(this).apply {
            setSingleLine(true); hint = "Profile name"; setText(item.name)
            Kit.styleInput(this@MainActivity, this)
        }
        val profileSettings = ProfileSettingsStore(this).get(item.raw)
        val decoded = ManualProfileDecoder.decode(item.raw)

        val (nameCard, nameBody) = Kit.card(this, "Profile", Icon.PROFILE, subtitle = "How this server appears in your list")
        nameBody.addView(tv("Profile name", 11.5f, Palette.muted, 1), lp(MATCH, WRAP, 8))
        nameBody.addView(name, lp(MATCH, dpi(50), 6))
        form.addView(nameCard, lp(MATCH, WRAP, 0))

        // Decoded structured fields (no raw URI shown)
        val decodedForm: Kit.FieldForm? = if (decoded != null && decoded.values.isNotEmpty()) {
            if (!decoded.editableWithoutDroppingUnknownFields) {
                form.addView(Kit.noteCard(this, decoded.note.ifBlank { "Some unknown fields are preserved in the internal config and will not be dropped on save." }), lp(MATCH, WRAP, 12))
            }
            Kit.FieldForm(this, decoded.protocol, decoded.values.toMutableMap(), fillDefaults = true).also {
                form.addView(it.root, lp(MATCH, WRAP, 2))
            }
        } else {
            form.addView(Kit.noteCard(this, "This profile format has no structured decoder. Name and DNS can still be changed; the internal configuration is preserved."), lp(MATCH, WRAP, 12))
            null
        }

        // Multi-select DNS resolvers
        val (dnsCard, dnsBody) = Kit.card(this, "DNS resolvers", Icon.GLOBE, subtitle = "This profile only • scan, select, set primary. Long-press a result to test DNS-over-TLS.")
        dnsBody.addView(dnsCard, lp(MATCH, WRAP, 14))
        val resolverSeed = EditText(this).apply {
            hint = "1.1.1.1:53\n8.8.8.8:53 or https://dns.google/dns-query"
            setText(profileSettings.dnsResolvers.joinToString("\n"))
            textSize = 12f; minLines = 2; maxLines = 4; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Palette.text); setHintTextColor(Palette.dim); background = fieldBackground(false)
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
        }
        dnsBody.addView(resolverSeed, lp(MATCH, dpi(80), 6))
        val selectedCountTv = tv("Selected: 0", 11f, Palette.cyan, 1)
        dnsBody.addView(selectedCountTv, lp(MATCH, WRAP, 4))
        val scanResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedResolvers = linkedSetOf<String>() // preserve order
        // pre-select existing
        profileSettings.dnsResolvers.forEach { selectedResolvers.add(it) }
        selectedCountTv.text = "Selected: ${selectedResolvers.size}"
        var primaryResolver = profileSettings.selectedResolver.ifBlank { selectedResolvers.firstOrNull().orEmpty() }

        fun refreshSelectedLabel() {
            selectedCountTv.text = "Selected: ${selectedResolvers.size}" + if (primaryResolver.isNotBlank()) " • primary: $primaryResolver" else ""
        }
        refreshSelectedLabel()

        fun addResolverRow(label: String, endpoint: String, detail: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(4), dpi(6), dpi(4), dpi(6))
            }
            val cb = android.widget.CheckBox(this).apply {
                isChecked = selectedResolvers.contains(endpoint)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedResolvers.add(endpoint)
                        if (primaryResolver.isBlank()) primaryResolver = endpoint
                    } else {
                        selectedResolvers.remove(endpoint)
                        if (primaryResolver == endpoint || primaryResolver !in selectedResolvers) {
                            primaryResolver = selectedResolvers.firstOrNull().orEmpty()
                        }
                    }
                    refreshSelectedLabel()
                }
            }
            val text = TextView(this).apply {
                this.text = "$label\n$detail"
                textSize = 11.5f
                setTextColor(Palette.text)
                setPadding(dpi(6), 0, 0, 0)
                setOnLongClickListener {
                    val host = endpoint.substringBefore(':').substringAfter("://").substringBefore('/')
                    if (host.isNotBlank()) {
                        DnsResolverScanner.testDot(this@MainActivity, host) { ok ->
                            runOnUiThread { this.text = "$label\n$detail\nDNS-over-TLS: ${if (ok) "reachable" else "not verified"}" }
                        }
                    }
                    true
                }
                setOnClickListener {
                    // set as primary when already selected
                    if (selectedResolvers.contains(endpoint)) {
                        primaryResolver = endpoint
                        refreshSelectedLabel()
                        toast("Primary resolver: $endpoint")
                    }
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
            scanResults.addView(row, lp(MATCH, WRAP, 2))
        }

        // show current selections as rows even before scan
        selectedResolvers.forEach { ep -> addResolverRow(ep, ep, "saved") }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(ghostButton("Scan") {
            val candidates = resolverSeed.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (candidates.isEmpty()) { toast("Enter at least one DNS resolver."); return@ghostButton }
            scanResults.removeAllViews()
            toast("Scanning…")
            DnsResolverScanner.scan(this, candidates) { results ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    scanResults.removeAllViews()
                    if (results.isEmpty()) {
                        dnsBody.addView(tv("No results", 11f, Palette.muted, 0))
                        return@runOnUiThread
                    }
                    results.forEach { r ->
                        val ep = r.resolver
                        val ok = r.dnsReachable || r.udpReachable || r.tcpReachable
                        val detail = buildString {
                            if (r.udpReachable) append("UDP ")
                            if (r.tcpReachable) append("TCP ")
                            if (r.dotReachable) append("DoT ")
                            if (r.latencyMs != null) append("${r.latencyMs}ms ")
                            if (r.detail.isNotBlank()) append(r.detail)
                            if (!ok && r.detail.isBlank()) append("failed")
                        }.trim().ifBlank { "checked" }
                        addResolverRow(ep, ep, detail)
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dpi(42), 1f).apply { rightMargin = dpi(4) })
        actionRow.addView(ghostButton("Select All") {
            for (i in 0 until scanResults.childCount) {
                val row = scanResults.getChildAt(i) as? LinearLayout ?: continue
                (row.getChildAt(0) as? android.widget.CheckBox)?.isChecked = true
            }
        }, LinearLayout.LayoutParams(0, dpi(42), 1f).apply { rightMargin = dpi(4) })
        actionRow.addView(ghostButton("Clear") {
            selectedResolvers.clear()
            primaryResolver = ""
            for (i in 0 until scanResults.childCount) {
                val row = scanResults.getChildAt(i) as? LinearLayout ?: continue
                (row.getChildAt(0) as? android.widget.CheckBox)?.isChecked = false
            }
            refreshSelectedLabel()
        }, LinearLayout.LayoutParams(0, dpi(42), 1f).apply { rightMargin = dpi(4) })
        actionRow.addView(ghostButton("Apply Selected") {
            val settingsStore = ProfileSettingsStore(this)
            val list = selectedResolvers.toList()
            val primary = when {
                list.isEmpty() -> ""
                primaryResolver.isNotBlank() && primaryResolver in list -> primaryResolver
                else -> list.firstOrNull().orEmpty()
            }
            primaryResolver = primary
            // Persist DNS only for this profile; keep origin/customSettings; migrate hash if needed.
            settingsStore.save(item.raw, list, primary, profileSettings.customSettings, profileSettings.origin)
            refreshSelectedLabel()
            toast(
                if (list.isEmpty()) "DNS resolvers cleared for this profile."
                else "Applied ${list.size} DNS resolver(s). Primary: ${primary.ifBlank { "none" }}"
            )
        }, LinearLayout.LayoutParams(0, dpi(42), 1f))
        dnsBody.addView(actionRow, lp(MATCH, WRAP, 6))
        dnsBody.addView(scanResults, lp(MATCH, WRAP, 4))

        val dialog = dialogBuilder().setTitle("EDIT PROFILE")
            .setView(ScrollView(this).apply { isFillViewport = true; addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profileName = name.text.toString().trim().ifBlank { item.name }
                var updatedRaw = item.raw
                if (decoded != null && decodedForm != null && decoded.editableWithoutDroppingUnknownFields) {
                    val newValues = linkedMapOf<String, String>()
                    // keep any keys not shown
                    decoded.values.forEach { (k, v) -> newValues[k] = v }
                    decodedForm.collect().forEach { (k, v) -> newValues[k] = v }
                    newValues["name"] = profileName
                    when (val outcome = ManualProfile.build(decoded.protocol, newValues)) {
                        is ManualOutcome.Ok -> updatedRaw = outcome.raw
                        is ManualOutcome.Invalid -> {
                            toast("${outcome.key}: ${outcome.message}")
                            return@setOnClickListener
                        }
                    }
                } else if (profileName != item.name) {
                    // name-only update helpers for known schemes without full decode rebuild
                    updatedRaw = tryRenameRaw(item, profileName) ?: item.raw
                }
                val settingsStore = ProfileSettingsStore(this)
                settingsStore.migrate(item.raw, updatedRaw)
                // Empty selection must stay empty — never fall back to resolverSeed text.
                val resolverList = selectedResolvers.toList()
                val primary = when {
                    resolverList.isEmpty() -> ""
                    primaryResolver.isNotBlank() && primaryResolver in resolverList -> primaryResolver
                    else -> resolverList.first()
                }
                settingsStore.save(updatedRaw, resolverList, primary, profileSettings.customSettings, profileSettings.origin)
                val meta = ProfileParser.parse(updatedRaw)
                val updated = ServerItem(profileName.take(80), updatedRaw, meta.engine, meta, true, item.country, item.panelId, item.panelName)
                val locals = loadLocalProfiles().filterNot { it.raw.trim() == item.raw.trim() }.toMutableList()
                locals.add(0, updated)
                if (!persistLocalProfiles(locals)) { toast("Could not save this profile."); return@setOnClickListener }
                servers = mergeSavedAndRemote(servers.filterNot { it.local })
                serversState = ServersState.READY
                rebuildServerList(-1)
                if (selectedItem()?.raw?.trim() == item.raw.trim()) setConfig(updatedRaw)
                dialog.dismiss()
                toast("Profile saved.")
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Best-effort rename without exposing or rewriting unknown raw structure. */
    private fun tryRenameRaw(item: ServerItem, newName: String): String? = runCatching {
        when (item.engine) {
            ConfigEngine.XRAY -> when {
                item.raw.startsWith("vmess://", true) -> {
                    val payload = item.raw.substringAfter("vmess://").substringBefore('#')
                    val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                    val obj = JSONObject(String(bytes, Charsets.UTF_8)).put("ps", newName)
                    "vmess://" + android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                }
                item.raw.startsWith("vless://", true) || item.raw.startsWith("trojan://", true) || item.raw.startsWith("ss://", true) ->
                    Uri.parse(item.raw).buildUpon().fragment(newName).build().toString()
                item.raw.trimStart().startsWith("{") -> JSONObject(item.raw).put("remarks", newName).toString()
                else -> null
            }
            ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> {
                val clean = item.raw.replace(Regex("(?im)^\\s*#\\s*Name\\s*=.*(?:\\r?\\n)?"), "")
                "# Name = $newName\n$clean"
            }
            ConfigEngine.MHRV -> EngineProfiles.decodeMhrv(item.raw)?.let { EngineProfiles.encodeMhrv(newName, JSONObject(it.configJson)) }
            ConfigEngine.DNS_TUNNEL -> EngineProfiles.decodeStormDns(item.raw)?.let { EngineProfiles.encodeStormDns(newName, it.clientConfig, it.resolvers) }
            ConfigEngine.MASTERDNS -> MasterDnsProfiles.decode(item.raw)?.let { p ->
                val obj = JSONObject(item.raw.substringAfter("://").let { String(android.util.Base64.decode(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING), Charsets.UTF_8) }).put("name", newName)
                "masterdns://" + android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            }
            ConfigEngine.COTTENDNS -> EngineProfiles.decodeCottenDns(item.raw)?.let { EngineProfiles.encodeCottenDns(newName, it.clientConfig, it.resolvers) }
            ConfigEngine.DNS_CHANGER -> DnsChangerProfiles.decode(item.raw)?.let { DnsChangerProfiles.encode(newName, it.ipv4Primary, it.ipv4Secondary, it.ipv6Primary, it.ipv6Secondary) }
            else -> null
        }
    }.getOrNull()

    private fun duplicateServerProfile(item: ServerItem) {
        val newName = (item.name + " (copy)").take(80)
        val copiedRaw = runCatching {
            when (item.engine) {
                ConfigEngine.XRAY -> when {
                    item.raw.startsWith("vmess://", true) -> {
                        val payload = item.raw.substringAfter("vmess://").substringBefore('#')
                        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                        val obj = JSONObject(String(bytes, Charsets.UTF_8)).put("ps", newName)
                        "vmess://" + android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    }
                    item.raw.startsWith("vless://", true) || item.raw.startsWith("trojan://", true) || item.raw.startsWith("ss://", true) ->
                        Uri.parse(item.raw).buildUpon().fragment(newName).build().toString()
                    item.raw.trimStart().startsWith("{") -> JSONObject(item.raw).put("remarks", newName).toString()
                    else -> null
                }
                ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> {
                    val clean = item.raw.replace(Regex("(?im)^\\s*#\\s*Name\\s*=.*(?:\\r?\\n)?"), "")
                    "# Name = $newName\n$clean"
                }
                ConfigEngine.MHRV -> EngineProfiles.decodeMhrv(item.raw)?.let { EngineProfiles.encodeMhrv(newName, JSONObject(it.configJson)) }
                ConfigEngine.DNS_TUNNEL -> EngineProfiles.decodeStormDns(item.raw)?.let { EngineProfiles.encodeStormDns(newName, it.clientConfig, it.resolvers) }
                ConfigEngine.MASTERDNS -> MasterDnsProfiles.decode(item.raw)?.let { p ->
                    val obj = JSONObject(item.raw.substringAfter("://").let { String(android.util.Base64.decode(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING), Charsets.UTF_8) }).put("name", newName)
                    "masterdns://" + android.util.Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                }
                ConfigEngine.COTTENDNS -> EngineProfiles.decodeCottenDns(item.raw)?.let { EngineProfiles.encodeCottenDns(newName, it.clientConfig, it.resolvers) }
                ConfigEngine.DNS_CHANGER -> DnsChangerProfiles.decode(item.raw)?.let { DnsChangerProfiles.encode(newName, it.ipv4Primary, it.ipv4Secondary, it.ipv6Primary, it.ipv6Secondary) }
                ConfigEngine.SNI_SPOOF -> EngineProfiles.decodeSniSpoof(item.raw)?.let { EngineProfiles.encodeSniSpoof(newName, it.baseProfile, it.connectIp, it.connectPort, it.fakeSni, it.listenPort) }
                else -> null
            }
        }.getOrNull()
        if (copiedRaw.isNullOrBlank() || ConfigDetector.detect(copiedRaw) == ConfigEngine.UNKNOWN) { toast("This profile format cannot be safely duplicated yet."); return }
        val locals = loadLocalProfiles()
        if (locals.any { it.raw.trim() == copiedRaw.trim() }) { toast("A copy of this profile already exists."); return }
        val meta = ProfileParser.parse(copiedRaw)
        val duplicate = ServerItem(newName, copiedRaw, meta.engine, meta, true, item.country)
        if (!persistLocalProfiles(listOf(duplicate) + locals)) { toast("Could not save the duplicated profile."); return }
        ProfileSettingsStore(this).duplicate(item.raw, copiedRaw, TirexNetShare.ProfileOrigin.LOCAL_DUPLICATE)
        servers = mergeSavedAndRemote(servers.filterNot { it.local }); serversState = ServersState.READY
        rebuildServerList(-1); toast("Profile duplicated.")
    }

    private fun showDecodedProfileEditor(rawTarget: EditText) {
        // Structured field editor — never displays the raw share URI.
        val decoded = ManualProfileDecoder.decode(rawTarget.text.toString())
        if (decoded == null) {
            toast("This format has no safe field decoder.")
            return
        }
        if (!decoded.editableWithoutDroppingUnknownFields) {
            dialogBuilder().setTitle("Limited edit")
                .setMessage(decoded.note.ifBlank { "Unknown fields are present. Only safe fields are shown; the rest stay in the internal config." })
                .setPositiveButton("OK", null).show()
        }
        val values = decoded.values.toMutableMap()
        // Same renderer as Manual Config: grouped cards, General vs Advanced, chip selectors.
        val ff = Kit.FieldForm(this, decoded.protocol, values, fillDefaults = true)
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dpi(14), dpi(4), dpi(14), dpi(10))
            addView(ff.root)
        }
        dialogBuilder().setTitle("Edit fields • ${decoded.protocol.label}")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                if (!decoded.editableWithoutDroppingUnknownFields) {
                    toast("Changes applied only to safe fields; unknown data kept internally.")
                }
                val newValues = linkedMapOf<String, String>()
                decoded.values.forEach { (k, v) -> newValues[k] = v }
                ff.collect().forEach { (k, v) -> newValues[k] = v }
                when (val outcome = ManualProfile.build(decoded.protocol, newValues)) {
                    is ManualOutcome.Ok -> {
                        // Write reconstructed raw back into the hidden target (not shown in UI).
                        rawTarget.setText(outcome.raw)
                        toast("Fields applied.")
                    }
                    is ManualOutcome.Invalid -> toast("${outcome.key}: ${outcome.message}")
                }
            }.show()
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
            regionServers.filter { it.engine in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.MASTERDNS, ConfigEngine.COTTENDNS, ConfigEngine.SNI_SPOOF) }
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
    private fun openPanelLogin(panelId: String) {
    selectedLoginPanelId = panelId
    addingPanelLogin = true
    registerMode = false
    prefs.edit()
        .putString("login_panel_id", panelId)
        .apply()
    render()
    }
private fun buildLogin(): View {
        val (sv, col) = tabScroll()
        col.addView(tv("SECURE ACCESS", 10f, Palette.cyan, 2).apply { letterSpacing = 0.26f }, lp(WRAP, WRAP, 22))
        if (addingPanelLogin && token.isNotBlank()) {
    col.addView(
        ghostButton("← Back to profile") {
            addingPanelLogin = false
            render()
        },
        lp(MATCH, dpi(44), 8)
    )
        }
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
        setOnFocusChangeListener { v, focus ->
            v.background = Palette.glass(this@MainActivity, 16f, if (focus) Palette.strokeActive else Palette.stroke, Palette.fieldFill, Palette.fieldFill)
        }
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
                    "SlipNet is ready. Connect directly from TIREXNET."
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
            lower.contains("disconnecting") -> OrbState.DISCONNECTING
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
        val proxyMode = prefs.getString("connection_mode", "vpn") == "proxy"
        if (
            proxyMode &&
            engine !in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.MASTERDNS, ConfigEngine.COTTENDNS, ConfigEngine.SNI_SPOOF) &&
            !(engine == ConfigEngine.SLIPNET && prefs.getBoolean("slipnet_proxy_only", false))
        ) {
            toast("Local proxy mode supports Xray, MHRV, StormDNS, MasterDNS, CottenDNS, Patterniha SNI, or SlipNet proxy-only profiles.")
            return
        }
        pendingRaw = raw
        pendingEngine = engine
        // The notification and the Quick Settings tile show this name; they can run without the app window.
        prefs.edit().putString("active_engine", engine.name)
            .putString(TirexNotifier.KEY_PROFILE_NAME, selectedItem()?.name.orEmpty()).apply()

        val permission = if (proxyMode && engine != ConfigEngine.SLIPNET) null else VpnService.prepare(this)
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

    private fun startSlipNetRuntime(raw: String, bundlePassword: String? = null) {
        val config = raw.trim()

        if (config.isBlank()) {
            toast("Select a SlipNet profile first.")
            return
        }

        pendingRaw = config
        pendingEngine = ConfigEngine.SLIPNET

        prefs.edit()
            .putString("active_engine", "SLIPNET")
            .putString(TirexNotifier.KEY_PROFILE_NAME, selectedItem()?.name.orEmpty())
            .apply()

        applyStatus("Preparing SlipNet profile…", false)

        slipNetScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    slipNetRuntime.importAndSave(config, bundlePassword)
                }
            } catch (error: Throwable) {
                TirexSlipNetImportResult.Failure(
                    error.message ?: "SlipNet profile could not be imported."
                )
            }

            when (result) {
                is TirexSlipNetImportResult.NeedsPassword -> {
                    requestSlipNetBundlePassword(config)
                }

                is TirexSlipNetImportResult.Failure -> {
                    prefs.edit().putString("active_engine", "").apply()
                    applyStatus("SlipNet error: ${result.message}", false)
                    toast(result.message)
                }

                is TirexSlipNetImportResult.Ready -> {
                    prefs.edit()
                        .putString(TirexNotifier.KEY_PROFILE_NAME, result.profileName)
                        .apply()

                    if (slipNetRuntime.preparedProfileNeedsPassword()) {
                        requestSlipNetProfilePassword()
                    } else {
                        connectPreparedSlipNet()
                    }
                }
            }
        }
    }

    private fun requestSlipNetBundlePassword(raw: String) {
        val input = EditText(this).apply {
            hint = "Bundle password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 13f
            setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
            background = fieldBackground(false)
        }

        val dialog = dialogBuilder()
            .setTitle("Unlock SlipNet bundle")
            .setMessage("Enter the password used when this SlipNet bundle was exported.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancelSlipNetStart() }
            .setPositiveButton("Continue", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()

                if (password.isBlank()) {
                    input.error = "Password required"
                    return@setOnClickListener
                }

                dialog.dismiss()
                startSlipNetRuntime(raw, password)
            }
        }

        dialog.show()
    }

    private fun requestSlipNetProfilePassword() {
        val input = EditText(this).apply {
            hint = "Profile password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 13f
            setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
            background = fieldBackground(false)
        }

        val dialog = dialogBuilder()
            .setTitle("Unlock SlipNet profile")
            .setMessage("This profile is protected by its owner. Enter its profile password to connect.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancelSlipNetStart() }
            .setPositiveButton("Unlock", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()

                if (!slipNetRuntime.verifyPreparedProfilePassword(password)) {
                    input.error = "Incorrect profile password"
                    return@setOnClickListener
                }

                dialog.dismiss()
                connectPreparedSlipNet()
            }
        }

        dialog.show()
    }

    private fun connectPreparedSlipNet() {
        slipNetScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    slipNetRuntime.applySettings(prefs)
                }

                prefs.edit().putString("active_engine", "SLIPNET").apply()
                applyStatus("Starting SlipNet…", false)
                slipNetRuntime.connectPrepared()

            } catch (error: Throwable) {
                prefs.edit().putString("active_engine", "").apply()
                applyStatus(
                    "SlipNet error: ${error.message ?: "connection failed"}",
                    false
                )
                toast("SlipNet could not start. Check the profile and try again.")
            }
        }
    }

    private fun cancelSlipNetStart() {
        prefs.edit().putString("active_engine", "").apply()
        applyStatus("Disconnected.", false)
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
            ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.MASTERDNS, ConfigEngine.COTTENDNS, ConfigEngine.SNI_SPOOF -> startAuxiliaryProxyEngine(pendingEngine, pendingRaw)
            ConfigEngine.SLIPNET -> startSlipNetRuntime(pendingRaw)
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
        if (engine == "SLIPNET" || slipNetRuntime.isConnectedOrConnecting()) {
            issued = true
            slipNetRuntime.disconnect()
        }
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
        if (engine in setOf("XRAY", "MHRV", "DNS_TUNNEL", "MASTERDNS", "COTTENDNS", "SNI_SPOOF") || XrayVpnService.isActive) {
            issued = true
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
        // Xray's ACTION_STOP owns auxiliary teardown. This delayed cleanup is only a
        // fallback for cases where the service cannot receive the stop intent.
        handler.postDelayed({
            if (!XrayVpnService.isActive) {
                MhrvEngine.stop()
                StormDnsEngine.stop()
                MasterDnsEngine.stop()
                CottenDnsEngine.stop()
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
        if (TirexNetShare.isTirexNetShare(t)) return listOf(t)
        if (t.startsWith("{") || t.contains("[interface]", ignoreCase = true) ||
            ConfigDetector.detect(t) == ConfigEngine.OPENVPN) return listOf(t)

        val lines = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val slipSchemes = listOf("slipnet://", "slipnet-enc://", "slipnet-bundle-enc://", "tirexnet://share/")
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
        val tirexPayloads = ArrayList<TirexNetShare.SharePayload>()
        for (candidate in splitConfigs(text)) {
            if (candidate.startsWith("slipnet-bundle-enc://", true)) {
                val unpacked = if (bundlePassword.isNotBlank()) EngineProfiles.decodeSlipNetBundle(candidate, bundlePassword) else null
                if (unpacked.isNullOrEmpty()) bundleFailures++ else expanded.addAll(unpacked)
            } else if (TirexNetShare.isTirexNetShare(candidate)) {
                try {
                    val decoded = TirexNetShare.decode(candidate)
                    tirexPayloads.add(decoded.payload)
                    expanded.add(decoded.payload.raw)
                } catch (e: TirexNetShare.ShareError) {
                    bundleFailures++
                } catch (_: Exception) {
                    bundleFailures++
                }
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
        val store = ProfileSettingsStore(this)
        var tirexIdx = 0
        for (c in candidates) {
            val meta = ProfileParser.parse(c)
            val structured = c.startsWith("{")
            val ok = when (meta.engine) {
                ConfigEngine.UNKNOWN -> false
                ConfigEngine.XRAY -> structured || meta.host.isNotBlank()
                ConfigEngine.SLIPNET -> EngineProfiles.decodeSlipNet(c) != null
                ConfigEngine.DNS_CHANGER -> DnsChangerProfiles.decode(c) != null
                else -> meta.host.isNotBlank() || meta.engine != ConfigEngine.UNKNOWN
            }
            if (!ok) { bad++; continue }
            if (!known.add(c.trim())) { dup++; continue }
            val fromTirex = tirexPayloads.getOrNull(tirexIdx)
            if (fromTirex != null && fromTirex.raw.trim() == c.trim()) tirexIdx++
            val displayName = when {
                fromTirex != null -> fromTirex.name.ifBlank { "Imported profile" }
                else -> ProfileParser.bestName(listOf(meta.name), saved.size + added.size + 1)
            }
            val item = ServerItem(
                displayName,
                c.trim(),
                meta.engine,
                meta,
                true,
                country.ifEmpty { fromTirex?.country?.ifEmpty { ProfileParser.flagCountryOf(displayName) } ?: ProfileParser.flagCountryOf(displayName) }
            )
            added.add(item)
            // Apply origin + DNS from opaque share
            if (fromTirex != null) {
                store.save(
                    c.trim(),
                    fromTirex.dnsResolvers,
                    fromTirex.selectedResolver,
                    fromTirex.customSettings,
                    TirexNetShare.ProfileOrigin.IMPORTED_TIREXNET
                )
            } else {
                // Mark pasted imports when origin still unknown
                val existing = store.get(c.trim())
                if (existing.origin == TirexNetShare.ProfileOrigin.UNKNOWN) {
                    store.setOrigin(c.trim(), TirexNetShare.ProfileOrigin.PASTED)
                }
            }
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
                else -> "کانفیگ پشتیبانی‌شده‌ای پیدا نشد. Xray / WireGuard / AmneziaWG / OpenVPN / MHRV / StormDNS / MasterDNS / CottenDNS / Patterniha / SlipNet لازم است."
            }
            importFailed(msg)
            return
        }
        val profileSettingsStore = ProfileSettingsStore(this)
        o.added.forEach { profileSettingsStore.get(it.raw) }
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
        ConfigEngine.MASTERDNS -> "MASTERDNS"
        ConfigEngine.COTTENDNS -> "COTTENDNS"
        ConfigEngine.DNS_CHANGER -> "DNS CHANGER"
        ConfigEngine.SNI_SPOOF -> "PATTERNIHA SNI"
        ConfigEngine.SLIPNET -> "SLIPNET"
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

    if (user.isBlank() || pass.isBlank()) {
        toast("نام کاربری و رمز عبور را وارد کن.")
        return
    }

    val panelId = selectedLoginPanelId

    loginButton?.apply {
        text = "Signing in…"
        alpha = 0.6f
        isEnabled = false
    }

    io.execute {
        try {
            val body = JSONObject()
                .put("username", user)
                .put("password", pass)
                .put("panel", panelId)

            val response = request("POST", "/api/login", null, body)
            val newToken = response.optString("token")

            require(newToken.isNotBlank()) {
                response.optString("error", "Login token is missing.")
            }

            savePanelSession(panelId, newToken, user)

            prefs.edit()
                .putString("token_base", lastBase)
                .putString("login_panel_id", panelId)
                .apply()

            runOnUiThread {
    addingPanelLogin = false
    currentTab = 3
    serversState = ServersState.IDLE
    servers = emptyList()

    render()
    loadAccount()
    loadServers()
            }

        } catch (e: Exception) {
            runOnUiThread {
                loginButton?.apply {
                    text = "Sign in"
                    alpha = 1f
                    isEnabled = true
                }

                toast(
                    if (
                        (e.message ?: "").contains("401") ||
                        (e.message ?: "").contains("invalid", true)
                    ) {
                        "نام کاربری یا رمز عبور درست نیست."
                    } else {
                        "ورود انجام نشد. اتصال اینترنت را بررسی کن و دوباره تلاش کن."
                    }
                )
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
    fun validName(value: String): Boolean {
        val v = value.trim()
        return v.isNotBlank() &&
            !ProfileParser.looksTechnical(v) &&
            !ProfileParser.isPlaceholder(v) &&
            !Regex("^\\d+$").matches(v)
    }

    // AliPanel: نام اصلی داخل کامنت کانفیگ
    if (panelId.equals("ali", true) || panelId.equals("alipanel", true)) {
        val name = Regex("(?im)^\\s*#\\s*Name\\s*=\\s*(.+?)\\s*$")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        if (validName(name)) return name
    }

    // اولویت با اسم واقعی داخل خود کانفیگ
    if (validName(meta.name)) return meta.name.trim()

    val keys = listOf(
        "wireguard_name", "wireguardName",
        "wg_name", "wgName",
        "config_name", "configName",
        "tunnel_name", "tunnelName",
        "profile_name", "profileName",
        "peer_name", "peerName",
        "client_name", "clientName",
        "display_name", "displayName",
        "filename", "file_name",
        "remarks", "remark",
        "title", "label", "alias", "tag", "note",
        "name", "username", "user_name", "userName"
    )

    val names = ArrayList<String>()

    fun collect(source: JSONObject?) {
        if (source == null) return

        for (key in keys) {
            val value = source.optString(key, "").trim()
            if (validName(value)) names.add(value)
        }
    }

    collect(item)

    if (item != null) {
        listOf(
            "metadata", "meta", "client",
            "peer", "profile", "wireguard", "wg"
        ).forEach {
            collect(item.optJSONObject(it))
        }
    }

    names.firstOrNull()?.let { return it }

    val commentName = Regex(
        "(?im)^\\s*#\\s*(?:name|profile|remark|tunnel)\\s*[:=]\\s*(.+?)\\s*$"
    ).find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    if (validName(commentName)) return commentName

    return if (
        panelId.equals("ali", true) ||
        panelId.equals("alipanel", true)
    ) {
        "WireGuard Profile $index"
    } else {
        "Server $index"
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
    val sessions = listOf(
        Triple("pasarguard", pasarguardToken, "PasarGuard"),
        Triple("alipanel", alipanelToken, "AliPanel")
    ).filter { it.second.isNotBlank() }

    if (sessions.isEmpty()) {
        servers = loadLocalProfiles()
        serversState = ServersState.READY
        rebuildServerList(-1)
        if (servers.isEmpty()) {
            toast("ابتدا وارد یکی از پنل‌ها شو یا یک پروفایل محلی ذخیره کن.")
        }
        return
    }

    val cached = loadRemoteCache()
    val successfulPanelIds = HashSet<String>()
    if (cached.isNotEmpty() && servers.isEmpty()) {
        servers = mergeSavedAndRemote(cached)
        serversState = ServersState.READY
    } else if (servers.isEmpty()) {
        serversState = ServersState.LOADING
    }

    rebuildServerList(-1)

    io.execute {
        val remote = ArrayList<ServerItem>()
        var successfulPanels = 0
        val errors = ArrayList<String>()

        for ((panelId, panelTokenValue, panelNameValue) in sessions) {
            try {
                val data = request(
                    "GET",
                    "/api/me/configs",
                    panelTokenValue,
                    null
                )

                val arr = data.optJSONArray("configs")
                    ?: data.optJSONArray("profiles")
                    ?: data.optJSONArray("items")
                    ?: throw IllegalStateException(
                        "$panelNameValue response has no configs array"
                    )

                successfulPanels++
                successfulPanelIds.add(panelId.lowercase())

                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)

                    val raw = when (item) {
                        is JSONObject -> item.optString(
                            "config",
                            item.optString(
                                "uri",
                                item.optString(
                                    "raw",
                                    item.optString(
                                        "content",
                                        item.optString("link", "")
                                    )
                                )
                            )
                        )
                        is String -> item
                        else -> ""
                    }.trim()

                    if (raw.isBlank()) continue

                    val meta = ProfileParser.parse(raw)
                    val obj = item as? JSONObject

                    val apiPanelId = obj?.optString(
                        "panelId",
                        obj.optString("panel_id", "")
                    ).orEmpty().ifBlank { panelId }

                    val apiPanelName = obj?.optString(
                        "panelName",
                        obj.optString("panel_name", "")
                    ).orEmpty().ifBlank { panelNameValue }

                    val apiEngine = obj?.optString(
                        "engine",
                        obj.optString("protocol", "")
                    ).orEmpty()

                    val resolvedEngine = resolveApiEngine(
                        apiEngine,
                        apiPanelId,
                        apiPanelName,
                        meta.engine
                    )

                    val displayName = configNameFromApi(
                        obj,
                        meta,
                        apiPanelId,
                        remote.size + 1,
                        raw
                    )

                    val explicitCountry = listOf(
                        "country_code",
                        "countryCode",
                        "cc",
                        "country"
                    ).map { key ->
                        normalizeCountry(
                            obj?.optString(key, "").orEmpty()
                        )
                    }.firstOrNull { it.isNotEmpty() }.orEmpty()

                    val country = explicitCountry.ifEmpty {
                        ProfileParser.flagCountryOf(displayName)
                    }

                    remote.add(
                        ServerItem(
                            name = displayName,
                            raw = raw,
                            engine = resolvedEngine,
                            meta = meta,
                            local = false,
                            country = country,
                            panelId = apiPanelId,
                            panelName = apiPanelName
                        )
                    )
                }
            } catch (e: Exception) {
                errors.add("$panelNameValue: ${e.message ?: "Connection failed"}")
            }
        }

        if (successfulPanels > 0) {
            val updatedRemote = ArrayList<ServerItem>()
updatedRemote.addAll(remote)

val knownRaw = updatedRemote
    .map { it.raw.trim() }
    .toHashSet()

cached.forEach { old ->
    val oldPanel = old.panelId.lowercase()

    if (
        oldPanel !in successfulPanelIds &&
        knownRaw.add(old.raw.trim())
    ) {
        updatedRemote.add(old)
    }
}

saveRemoteCache(updatedRemote)

            runOnUiThread {
                servers = mergeSavedAndRemote(updatedRemote)
                serversState = ServersState.READY
                serversError = ""

                rebuildServerList(-1)
                bindServerSummary()

                if (errors.isNotEmpty()) {
                    toast("اطلاعات یکی از پنل‌ها دریافت نشد؛ پنل دیگر بارگذاری شد.")
                }
            }
        } else {
            runOnUiThread {
                val offline = mergeSavedAndRemote(loadRemoteCache())

                if (offline.isNotEmpty()) {
                    servers = offline
                    serversState = ServersState.READY
                    serversError = "Connection unavailable"

                    rebuildServerList(-1)
                    bindServerSummary()

                    toast("اتصال به پنل‌ها برقرار نشد؛ لیست ذخیره‌شده نمایش داده شد.")
                } else {
                    serversError = "Connection unavailable"
                    serversState = ServersState.ERROR

                    rebuildServerList(-1)
                    bindServerSummary()

                    toast("دریافت کانفیگ‌ها ناموفق بود.")
                }
            }
        }
    }
    }

@Volatile private var lastBase = ""

private fun request(
    method: String,
    path: String,
    bearer: String?,
    body: JSONObject?
): JSONObject {
    val base = api.trimEnd('/')

    return try {
        val result = requestOnce(
            base,
            method,
            path,
            bearer,
            body,
            ApiConfig.NORMAL_TIMEOUT_MS
        )

        lastBase = base
        result
    } catch (e: ApiHttpException) {
        throw e
    } catch (e: Exception) {
        throw ApiUnavailableException(
            "ارتباط با سرور برنامه برقرار نشد: ${e.message}"
        )
    }
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
    val panelId = activePanelId
    val oldToken = panelToken(panelId)

    when (panelId.lowercase()) {
        "alipanel" -> {
            alipanelToken = ""
            alipanelUsername = ""
        }
        else -> {
            pasarguardToken = ""
            pasarguardUsername = ""
        }
    }

    prefs.edit().apply {
        when (panelId.lowercase()) {
            "alipanel" -> {
                remove("session_alipanel")
                remove("username_alipanel")
            }
            else -> {
                remove("session_pasarguard")
                remove("username_pasarguard")
            }
        }
        remove("token")
        remove("token_base")
        remove("remote_servers_cache")
        apply()
    }

    activePanelId = when {
        pasarguardToken.isNotBlank() -> "pasarguard"
        alipanelToken.isNotBlank() -> "alipanel"
        else -> "pasarguard"
    }

    token = panelToken(activePanelId)

    prefs.edit()
        .putString("active_panel_id", activePanelId)
        .putString("token", token)
        .apply()

    accUser = ""
    accStatus = ""
    accPanelUser = ""
    usedBytes = -1L
    limitBytes = 0L
    expireText = ""

    servers = emptyList()
    serversState = ServersState.IDLE
    currentTab = 0

    io.execute {
        if (oldToken.isNotBlank()) {
            runCatching {
                request("POST", "/api/logout", oldToken, JSONObject())
            }
        }
    }

    render()

    if (token.isNotBlank()) {
        loadAccount()
        loadServers()
    }
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
        manual.addView(tv("Pick a family, then a protocol. Only the fields that protocol uses are shown.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
        val groupScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val groupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupScroll.addView(groupRow, FrameLayout.LayoutParams(WRAP, WRAP))
        manualGroupRow = groupRow
        manual.addView(groupScroll, lp(MATCH, WRAP, 12))
        val protoScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val protoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        protoScroll.addView(protoRow, FrameLayout.LayoutParams(WRAP, WRAP))
        manualProtoRow = protoRow
        manual.addView(protoScroll, lp(MATCH, WRAP, 2))
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
        rawCard.addView(tv("IMPORT CONFIG", 8.5f, Palette.muted, 1).apply { letterSpacing = 0.18f })
        rawCard.addView(tv("Paste Xray, WireGuard, OpenVPN, MHRV, StormDNS, MasterDNS (masterdns://), CottenDNS, Patterniha or SlipNet (slipnet://) share links. SlipNet links can be imported here; runtime availability is shown in SlipNet settings.", 11.5f, Palette.muted, 0), lp(MATCH, WRAP, 6))
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
        addStatus = null; manualFieldsBox = null; manualProtoRow = null; manualGroupRow = null; manualError = null
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

    private fun selectManualProtocol(p: ManualProtocol) {
        if (p == manualProtocol) return
        val name = manualValues["name"].orEmpty()
        val address = manualValues["address"].orEmpty()
        manualProtocol = p
        manualValues.clear()
        manualValues.putAll(ManualProfile.defaultsFor(p))
        manualValues["name"] = name
        if (ManualProfile.fields(p).any { it.key == "address" }) manualValues["address"] = address
        manualError?.visibility = View.GONE
        buildProtocolChips()
        rebuildManualFields()
    }

    private fun buildProtocolChips() {
        val groupRow = manualGroupRow
        val row = manualProtoRow ?: return
        groupRow?.let { gr ->
            gr.removeAllViews()
            for (g in ManualProtocol.groups) {
                gr.addView(chip(g, g == manualProtocol.group) {
                    if (g != manualProtocol.group) {
                        ManualProtocol.values().firstOrNull { it.group == g }?.let { selectManualProtocol(it) }
                    }
                }, lp(WRAP, WRAP, 0, 0, 0, 8))
            }
        }
        row.removeAllViews()
        for (p in ManualProtocol.values().filter { it.group == manualProtocol.group }) {
            row.addView(chip(p.label, p == manualProtocol) { selectManualProtocol(p) }, lp(WRAP, WRAP, 0, 0, 0, 8))
        }
    }

    private fun fieldBackground(error: Boolean) =
        Palette.glass(this, 14f, if (error) Palette.red else Palette.stroke, Palette.fieldFill, Palette.fieldFill)

    private fun rebuildManualFields() {
        val box = manualFieldsBox ?: return
        box.removeAllViews()
        manualFieldViews.clear()
        // Same renderer as the decoded editor: grouped cards, General vs Advanced, chip selectors.
        val form = Kit.FieldForm(this, manualProtocol, manualValues)
        manualForm = form
        box.addView(form.root, lp(MATCH, WRAP))
        manualFieldViews.putAll(form.inputs)
        val notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (manualProtocol == ManualProtocol.DNS_CHANGER) {
            notes.addView(Kit.noteCard(this,
                "Dedicated DNS mode • forwards classic DNS (UDP/53) to the selected resolvers. Other traffic stays on the normal network. Android allows only one active VPN at a time, so disconnect another VPN before using this profile."
            ), lp(MATCH, WRAP, 12))
        }
        if (manualProtocol.isSlipNet) {
            notes.addView(Kit.noteCard(this,
                "Saved as an official SlipNet profile and run by the in-app SlipNet engine (DNSTT / VayDNS / Soar and optional SSH)."
            ), lp(MATCH, WRAP, 12))
        } else if (manualProtocol == ManualProtocol.MASTERDNS) {
            notes.addView(Kit.noteCard(this,
                "Runs the MasterDnsVPN client locally (SOCKS5 on 127.0.0.1) and routes the whole device through it. Domains, key and encryption method must match the server."
            ), lp(MATCH, WRAP, 12))
        }
        box.addView(notes, lp(MATCH, WRAP))
        // Optional flag source for manual profiles (the only reliable country metadata we have for them).
        val (ccCard, ccBody) = Kit.card(this, "Flag", Icon.GLOBE, subtitle = "Optional country shown next to this server")
        Kit.labeledInput(this, ccBody, "Country code (optional)") {
            hint = "e.g. DE, US, TR"
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(2))
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
        box.addView(ccCard, lp(MATCH, WRAP, 14))
    }

    private fun saveManual() {
        val values = HashMap(manualValues)
        manualForm?.clearErrors()
        when (val out = ManualProfile.build(manualProtocol, values)) {
            is ManualOutcome.Invalid -> {
                manualError?.apply { text = out.message; visibility = View.VISIBLE }
                manualForm?.markError(out.key) // opens Advanced automatically if the bad field lives there
                manualForm?.let { manualFieldViews.clear(); manualFieldViews.putAll(it.inputs) }
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
        if (Palette.dark) com.tirexnet.app.R.style.TirexDialogDark else com.tirexnet.app.R.style.TirexDialogLight
    )

    private fun toast(message: String) {
        Kit.toast(this, message)
    }

    companion object {
        private const val VPN_REQUEST = 71
        private const val PICK_CONFIG = 72
    }
}
