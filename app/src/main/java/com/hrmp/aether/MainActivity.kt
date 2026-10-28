package com.tirexnet.app

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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tirexnet.app.engine.ConfigDetector
import com.tirexnet.app.engine.ConfigEngine
import com.tirexnet.app.engine.WgTunnelEngine
import com.tirexnet.app.engine.XrayVpnService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val api = "https://hrmp.hrmpdownloader.kdns.fr"
    private val io = Executors.newSingleThreadExecutor()

    private val bg = Color.rgb(7, 11, 23)
    private val panel = Color.rgb(17, 25, 45)
    private val panel2 = Color.rgb(28, 39, 66)
    private val mint = Color.rgb(0, 235, 188)
    private val white = Color.rgb(245, 248, 255)
    private val muted = Color.rgb(148, 164, 191)
    private val blue = Color.rgb(80, 115, 255)
    private val red = Color.rgb(255, 96, 120)

    private var token = ""

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var configInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var accountInfo: TextView
    private lateinit var detectionView: TextView

    private var pendingRaw = ""
    private var pendingEngine = ConfigEngine.UNKNOWN

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(XrayVpnService.EXTRA_MESSAGE) ?: return
            setConnectionStatus(
                msg,
                intent.getBooleanExtra(XrayVpnService.EXTRA_ACTIVE, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        token = getSharedPreferences("tirexnet", MODE_PRIVATE)
            .getString("token", "") ?: ""

        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        render()

        if (token.isNotBlank()) {
            loadAccount()
        }
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(bg)
            clipToPadding = false
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }

        scroll.addView(root)

        header()
        gap(root, 24)

        if (token.isBlank()) {
            loginScreen()
        } else {
            dashboard()
        }

        setContentView(scroll)
    }

    // ---------------------------------------------------------
    // HEADER / TIREXNET BRAND
    // ---------------------------------------------------------

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "T"
            textSize = 29f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(bg)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mint, Color.rgb(45, 185, 255))
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }

        row.addView(logo, lp(dp(54), dp(54)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        brand.addView(
            text("TIREXNET", 22, white, true),
            lp(-1, dp(31))
        )

        brand.addView(
            text("PRIVATE NETWORK  •  MULTI-CORE", 9, muted, true),
            lp(-1, dp(22))
        )

        row.addView(
            brand,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        val secure = TextView(this).apply {
            text = "● SECURE"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mint)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = bordered(panel, 16, mint)
        }

        row.addView(secure, lp(-2, dp(31)))
        root.addView(row, lp(-1, dp(56)))

        val line = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    mint,
                    Color.rgb(50, 80, 180),
                    Color.TRANSPARENT
                )
            )
        }

        val lineParams = lp(-1, dp(1))
        lineParams.topMargin = dp(18)
        root.addView(line, lineParams)
    }

    // ---------------------------------------------------------
    // LOGIN SCREEN
    // ---------------------------------------------------------

    private fun loginScreen() {
        gap(root, 15)

        root.addView(
            text("WELCOME TO TIREXNET", 11, mint, true),
            lp(-1, dp(25))
        )

        root.addView(
            text("Your internet.\nYour rules.", 35, white, true),
            lp(-1, dp(94))
        )

        root.addView(
            text(
                "Fast, private and simple access — built around your subscription.",
                14,
                muted,
                false
            ),
            lp(-1, -2)
        )

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = gradient(
                Color.rgb(15, 63, 72),
                Color.rgb(30, 29, 75),
                23
            )
        }

        val heroParams = lp(-1, dp(128))
        heroParams.topMargin = dp(22)
        root.addView(hero, heroParams)

        hero.addView(
            text("✧  SMART PROTOCOL SELECTION", 11, mint, true),
            lp(-1, dp(25))
        )

        hero.addView(
            text("One app. Multiple protocols.", 18, white, true),
            lp(-1, dp(33))
        )

        hero.addView(
            text("XRAY  •  WIREGUARD  •  AMNEZIAWG", 11, muted, true),
            lp(-1, dp(25))
        )

        gap(root, 24)

        root.addView(
            text("SIGN IN", 12, white, true),
            lp(-1, dp(26))
        )

        usernameInput = input("Username")
        root.addView(usernameInput, lp(-1, dp(55)))

        gap(root, 12)

        passwordInput = input("Password").apply {
            inputType = 129
        }
        root.addView(passwordInput, lp(-1, dp(55)))

        gap(root, 16)

        root.addView(
            button("Sign in  →", true).apply {
                setOnClickListener { login() }
            },
            lp(-1, dp(56))
        )

        root.addView(
            text(
                "Your account is authenticated securely by TIREXNET.",
                11,
                muted,
                false
            ).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(48))
        )

        root.addView(
            text("TIREXNET  /  SECURE ACCESS", 10, muted, true).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(30))
        )
    }

    // ---------------------------------------------------------
    // MAIN DASHBOARD
    // ---------------------------------------------------------

    private fun dashboard() {
        root.addView(
            text("CONTROL CENTER", 10, mint, true),
            lp(-1, dp(23))
        )

        root.addView(
            text("Your network", 31, white, true),
            lp(-1, dp(46))
        )

        root.addView(
            text(
                "Manage your subscription and secure connection.",
                13,
                muted,
                false
            ),
            lp(-1, dp(27))
        )

        // Subscription card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(16))
            background = gradient(
                Color.rgb(12, 66, 68),
                Color.rgb(28, 31, 76),
                23
            )
        }

        val heroParams = lp(-1, dp(145))
        heroParams.topMargin = dp(13)
        root.addView(hero, heroParams)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        badgeRow.addView(
            text("SUBSCRIPTION", 10, muted, true),
            LinearLayout.LayoutParams(0, dp(25), 1f)
        )

        badgeRow.addView(
            text("● ACCOUNT", 9, mint, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = bordered(panel, 12, Color.rgb(30, 100, 105))
            },
            lp(-2, dp(27))
        )

        hero.addView(badgeRow, lp(-1, dp(29)))

        accountInfo = text("Loading subscription…", 21, white, true)
        hero.addView(accountInfo, lp(-1, dp(39)))

        hero.addView(
            text(
                "Your account status is separate from VPN status.",
                11,
                muted,
                false
            ),
            lp(-1, dp(24))
        )

        // Usage and expiration cards
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val left = statCard(
            "DATA USED",
            "Loading…",
            "Traffic consumption"
        )

        val right = statCard(
            "EXPIRES",
            "—",
            "Subscription date"
        )

        val p1 = LinearLayout.LayoutParams(0, dp(112), 1f)
        p1.rightMargin = dp(6)

        val p2 = LinearLayout.LayoutParams(0, dp(112), 1f)
        p2.leftMargin = dp(6)

        stats.addView(left, p1)
        stats.addView(right, p2)

        val statsParams = lp(-1, dp(112))
        statsParams.topMargin = dp(12)
        root.addView(stats, statsParams)

        // Configuration card
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(panel, 21)
            setPadding(dp(16), dp(15), dp(16), dp(16))
        }

        val boxParams = lp(-1, -2)
        boxParams.topMargin = dp(16)
        root.addView(box, boxParams)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            text("CONNECTION PROFILE", 13, white, true),
            LinearLayout.LayoutParams(0, dp(29), 1f)
        )

        titleRow.addView(
            text("● READY", 9, mint, true),
            lp(-2, dp(25))
        )

        box.addView(titleRow, lp(-1, dp(30)))

        box.addView(
            text(
                "Paste or import a profile. TIREXNET detects the protocol automatically.",
                11,
                muted,
                false
            ),
            lp(-1, dp(40))
        )

        configInput = EditText(this).apply {
            setTextColor(white)
            setHintTextColor(muted)
            hint = "Paste Xray link or WireGuard / AmneziaWG config"
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 9
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = bordered(
                panel2,
                14,
                Color.rgb(49, 65, 96)
            )
        }

        box.addView(configInput, lp(-1, dp(145)))

        detectionView = text(
            "DETECTED ENGINE: NONE",
            10,
            mint,
            true
        )

        box.addView(detectionView, lp(-1, dp(29)))

        configInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val engine = ConfigDetector.detect(
                    s?.toString().orEmpty()
                )

                detectionView.text = "DETECTED ENGINE: " + when (engine) {
                    ConfigEngine.XRAY -> "XRAY"
                    ConfigEngine.WIREGUARD -> "WIREGUARD"
                    ConfigEngine.AMNEZIAWG -> "AMNEZIAWG"
                    else -> if (s.isNullOrBlank()) {
                        "NONE"
                    } else {
                        "UNKNOWN FORMAT"
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        val fetchProfiles = button(
            "Load subscription profiles",
            false
        )

        val fetchParams = lp(-1, dp(46))
        fetchParams.topMargin = dp(6)
        box.addView(fetchProfiles, fetchParams)

        fetchProfiles.setOnClickListener {
            loadProfiles()
        }

        gap(box, 10)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val paste = button("Paste", false)
        val import = button("Import file", false)

        val pasteParams = LinearLayout.LayoutParams(0, dp(47), 1f)
        pasteParams.rightMargin = dp(5)

        val importParams = LinearLayout.LayoutParams(0, dp(47), 1f)
        importParams.leftMargin = dp(5)

        actions.addView(paste, pasteParams)
        actions.addView(import, importParams)

        box.addView(actions, lp(-1, dp(47)))

        paste.setOnClickListener { pasteClipboard() }
        import.setOnClickListener { pickFile() }

        // Connection status
        gap(root, 15)

        status = text(
            "○  DISCONNECTED",
            13,
            muted,
            true
        ).apply {
            gravity = Gravity.CENTER
            background = bordered(panel, 16, Color.rgb(44, 58, 83))
        }

        root.addView(status, lp(-1, dp(50)))

        gap(root, 10)

        root.addView(
            button("CONNECT  ↗", true).apply {
                setOnClickListener { connect() }
            },
            lp(-1, dp(59))
        )

        val disconnectButton = button("Disconnect", false)
        val disconnectParams = lp(-1, dp(48))
        disconnectParams.topMargin = dp(8)
        root.addView(disconnectButton, disconnectParams)

        disconnectButton.setOnClickListener {
            disconnect()
        }

        val refresh = button("Refresh subscription", false)
        val refreshParams = lp(-1, dp(47))
        refreshParams.topMargin = dp(11)
        root.addView(refresh, refreshParams)

        refresh.setOnClickListener {
            loadAccount()
        }

        val logoutButton = button("Sign out", false)
        val logoutParams = lp(-1, dp(47))
        logoutParams.topMargin = dp(8)
        root.addView(logoutButton, logoutParams)

        logoutButton.setOnClickListener {
            logout()
        }

        root.addView(
            text(
                "ENCRYPTED TUNNEL  •  TIREXNET",
                9,
                muted,
                true
            ).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(36))
        )

        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)

        setConnectionStatus(
            prefs.getString(
                "connection_message",
                "Disconnected."
            ) ?: "Disconnected.",
            prefs.getBoolean("connection_active", false)
        )
    }

    private fun statCard(
        title: String,
        initial: String,
        footer: String
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = bordered(
                panel,
                18,
                Color.rgb(34, 48, 75)
            )
        }

        card.addView(
            text(title, 9, muted, true),
            lp(-1, dp(23))
        )

        card.addView(
            text(initial, 17, white, true).apply {
                tag = title
            },
            lp(-1, dp(34))
        )

        card.addView(
            text(footer, 10, muted, false),
            lp(-1, dp(21))
        )

        return card
    }

    // ---------------------------------------------------------
    // VPN CONNECTION
    // ---------------------------------------------------------

    private fun connect() {
        val raw = configInput.text.toString().trim()
        val engine = ConfigDetector.detect(raw)

        if (engine == ConfigEngine.UNKNOWN) {
            toast(
                "Unrecognized profile. Use a VLESS / VMess / Trojan / Shadowsocks link or a WireGuard .conf."
            )
            return
        }

        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)

        if (prefs.getBoolean("connection_active", false)) {
            toast(
                "A VPN is already active. Disconnect it before switching engines."
            )
            return
        }

        pendingRaw = raw
        pendingEngine = engine

        prefs.edit()
            .putString("active_engine", engine.name)
            .apply()

        val permission = VpnService.prepare(this)

        if (permission != null) {
            startActivityForResult(permission, VPN_REQUEST)
            return
        }

        startSelectedEngine()
    }

    private fun startSelectedEngine() {
        when (pendingEngine) {
            ConfigEngine.XRAY -> {
                setConnectionStatus("Starting Xray core…", false)

                val intent = Intent(
                    this,
                    XrayVpnService::class.java
                ).setAction(XrayVpnService.ACTION_START)
                    .putExtra(
                        XrayVpnService.EXTRA_CONFIG,
                        pendingRaw
                    )

                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }

            ConfigEngine.WIREGUARD,
            ConfigEngine.AMNEZIAWG -> {
                val engineName =
                    if (pendingEngine == ConfigEngine.AMNEZIAWG) {
                        "AmneziaWG"
                    } else {
                        "WireGuard"
                    }

                setConnectionStatus(
                    "Starting $engineName core…",
                    false
                )

                WgTunnelEngine.start(
                    this,
                    pendingRaw,
                    pendingEngine == ConfigEngine.AMNEZIAWG
                ) { message, active ->
                    setConnectionStatus(message, active)

                    if (!active) {
                        toast(message)
                    }
                }
            }

            else -> {
                toast("Choose a supported profile.")
            }
        }
    }

    private fun disconnect() {
        val engine = getSharedPreferences(
            "tirexnet",
            MODE_PRIVATE
        ).getString("active_engine", "")

        if (
            engine == "WIREGUARD" ||
            engine == "AMNEZIAWG" ||
            WgTunnelEngine.isActive
        ) {
            WgTunnelEngine.stop { message, active ->
                setConnectionStatus(message, active)
                toast(message)
            }
        }

        if (
            engine == "XRAY" ||
            XrayVpnService.isActive
        ) {
            startService(
                Intent(
                    this,
                    XrayVpnService::class.java
                ).setAction(XrayVpnService.ACTION_STOP)
            )
        }

        setConnectionStatus("Disconnected.", false)
    }

    private fun setConnectionStatus(
        message: String,
        active: Boolean
    ) {
        if (::status.isInitialized) {
            status.text =
                (if (active) "●  " else "○  ") + message

            status.setTextColor(
                if (active) mint else muted
            )

            status.background = bordered(
                panel,
                16,
                if (active) mint else Color.rgb(44, 58, 83)
            )
        }

        getSharedPreferences("tirexnet", MODE_PRIVATE)
            .edit()
            .putString("connection_message", message)
            .putBoolean("connection_active", active)
            .apply()
    }

    // ---------------------------------------------------------
    // FILE IMPORT AND CLIPBOARD
    // ---------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                startSelectedEngine()
            } else {
                setConnectionStatus(
                    "VPN permission was denied.",
                    false
                )
                toast("VPN permission is required.")
            }
        } else if (
            requestCode == PICK_CONFIG &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return

            try {
                contentResolver.openInputStream(uri)?.use {
                    configInput.setText(readLimited(it))
                }

                toast("Profile imported.")
            } catch (e: Exception) {
                toast(
                    "Import failed: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private fun pasteClipboard() {
        try {
            val manager = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            val clip: ClipData? = manager.primaryClip

            if (clip == null || clip.itemCount == 0) {
                toast("Clipboard is empty.")
                return
            }

            configInput.setText(
                clip.getItemAt(0)
                    .coerceToText(this)
                    ?.toString()
                    .orEmpty()
            )
        } catch (_: Exception) {
            toast("Could not read clipboard.")
        }
    }

    private fun pickFile() {
        val intent = Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {
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

            require(total <= 1024 * 1024) {
                "File exceeds 1 MB."
            }

            out.write(buffer, 0, n)
        }

        return out.toString("UTF-8").trim()
    }

    // ---------------------------------------------------------
    // LOGIN / AUTHENTICATION
    // ---------------------------------------------------------

    private fun login() {
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()

        if (user.isBlank() || pass.isBlank()) {
            toast("Enter your username and password.")
            return
        }

        toast("Signing in securely…")

        io.execute {
            try {
                val response = request(
                    "POST",
                    "/api/login",
                    null,
                    JSONObject()
                        .put("username", user)
                        .put("password", pass)
                )

                val newToken = response.optString("token")

                require(newToken.isNotBlank()) {
                    response.optString(
                        "error",
                        "Login token is missing."
                    )
                }

                getSharedPreferences(
                    "tirexnet",
                    MODE_PRIVATE
                ).edit()
                    .putString("token", newToken)
                    .apply()

                token = newToken

                runOnUiThread {
                    render()
                    loadAccount()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(
                        "Sign-in failed: ${e.message ?: "network error"}"
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------
    // ACCOUNT AND SUBSCRIPTION
    // ---------------------------------------------------------

    private fun loadAccount() {
        if (token.isBlank()) return

        io.execute {
            try {
                val data = request(
                    "GET",
                    "/api/me",
                    token,
                    null
                )

                val sub = data.optJSONObject(
                    "subscription"
                ) ?: JSONObject()

                val appUser = data.optString(
                    "username",
                    "Account"
                )

                val panelUser = sub.optString(
                    "username",
                    "—"
                )

                val panelStatus = sub.optString(
                    "status",
                    "Unknown"
                )

                val used = formatBytes(
                    sub.optLong("usedTraffic", 0L)
                )

                val cap = sub.optLong(
                    "dataLimit",
                    0L
                )

                val expire = if (sub.isNull("expire")) {
                    "No expiry returned"
                } else {
                    sub.optString("expire", "—")
                }

                runOnUiThread {
                    if (::accountInfo.isInitialized) {
                        accountInfo.text =
                            "$appUser  •  $panelStatus"
                    }

                    findTaggedText("DATA USED")?.text =
                        if (cap > 0) {
                            "$used / ${formatBytes(cap)}"
                        } else {
                            used
                        }

                    findTaggedText("EXPIRES")?.text =
                        if (expire.length > 14) {
                            expire.take(14)
                        } else {
                            expire
                        }

                    toast(
                        "Subscription refreshed for $panelUser"
                    )
                }
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("401")) {
                    getSharedPreferences(
                        "tirexnet",
                        MODE_PRIVATE
                    ).edit()
                        .remove("token")
                        .apply()

                    token = ""

                    runOnUiThread {
                        render()
                        toast("Session expired. Sign in again.")
                    }
                } else {
                    runOnUiThread {
                        toast(
                            "Could not refresh subscription: ${e.message ?: "network error"}"
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // SUBSCRIPTION PROFILES
    // ---------------------------------------------------------

    private fun loadProfiles() {
        if (token.isBlank()) {
            toast("Sign in first.")
            return
        }

        toast("Checking your subscription profile feed…")

        io.execute {
            try {
                val data = request(
                    "GET",
                    "/api/me/configs",
                    token,
                    null
                )

                val arr =
                    data.optJSONArray("configs")
                        ?: data.optJSONArray("profiles")
                        ?: data.optJSONArray("items")
                        ?: throw IllegalStateException(
                            "Worker response has no configs/profiles array."
                        )

                val names = ArrayList<String>()
                val rawConfigs = ArrayList<String>()

                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)

                    val raw = when (item) {
                        is JSONObject -> {
                            item.optString(
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
                        }

                        is String -> item
                        else -> ""
                    }.trim()

                    if (raw.isNotBlank()) {
                        rawConfigs.add(raw)

                        val itemName =
                            (item as? JSONObject)
                                ?.optString("name", "")
                                ?.takeIf { it.isNotBlank() }

                        val engine = when (
                            ConfigDetector.detect(raw)
                        ) {
                            ConfigEngine.XRAY -> "Xray"
                            ConfigEngine.WIREGUARD -> "WireGuard"
                            ConfigEngine.AMNEZIAWG -> "AmneziaWG"
                            else -> "Unknown"
                        }

                        names.add(
                            (itemName
                                ?: "Profile ${rawConfigs.size}") +
                                "  •  " + engine
                        )
                    }
                }

                runOnUiThread {
                    if (rawConfigs.isEmpty()) {
                        toast(
                            "No usable profiles were returned by the Worker."
                        )
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Subscription profiles")
                            .setItems(names.toTypedArray()) { _, which ->
                                configInput.setText(
                                    rawConfigs[which]
                                )
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(
                        "Profile feed is not available yet: ${e.message ?: "endpoint unavailable"}. Import a config instead."
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------

    private fun findTaggedText(tag: String): TextView? {
        fun walk(view: View): TextView? {
            if (view is TextView && view.tag == tag) {
                return view
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i))?.let {
                        return it
                    }
                }
            }

            return null
        }

        return if (::root.isInitialized) {
            walk(root)
        } else {
            null
        }
    }

    private fun request(
        method: String,
        path: String,
        bearer: String?,
        body: JSONObject?
    ): JSONObject {
        val c = URL(api + path)
            .openConnection() as HttpURLConnection

        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 15000

        c.setRequestProperty(
            "Accept",
            "application/json"
        )

        if (!bearer.isNullOrBlank()) {
            c.setRequestProperty(
                "Authorization",
                "Bearer $bearer"
            )
        }

        if (body != null) {
            c.doOutput = true

            c.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            c.outputStream.use {
                it.write(
                    body.toString().toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            }
        }

        val code = c.responseCode

        val responseText = (
            if (code in 200..299) {
                c.inputStream
            } else {
                c.errorStream
            }
        )?.bufferedReader()?.use {
            it.readText()
        }.orEmpty()

        c.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException(
                "HTTP $code: $responseText"
            )
        }

        return JSONObject(responseText)
    }

    private fun logout() {
        val old = token
        token = ""

        getSharedPreferences(
            "tirexnet",
            MODE_PRIVATE
        ).edit()
            .remove("token")
            .apply()

        io.execute {
            runCatching {
                request(
                    "POST",
                    "/api/logout",
                    old,
                    JSONObject()
                )
            }
        }

        render()
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 ->
                "$bytes B"

            bytes < 1024L * 1024 ->
                String.format(
                    Locale.US,
                    "%.1f KB",
                    bytes / 1024.0
                )

            bytes < 1024L * 1024 * 1024 ->
                String.format(
                    Locale.US,
                    "%.2f MB",
                    bytes / (1024.0 * 1024)
                )

            else ->
                String.format(
                    Locale.US,
                    "%.2f GB",
                    bytes / (1024.0 * 1024 * 1024)
                )
        }

    private fun input(hintText: String) =
        EditText(this).apply {
            hint = hintText
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(15), 0, dp(15), 0)
            background = bordered(
                panel,
                14,
                Color.rgb(47, 62, 90)
            )
        }

    private fun button(
        label: String,
        primary: Boolean
    ) = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD

        setTextColor(
            if (primary) bg else white
        )

        background = if (primary) {
            gradient(mint, Color.rgb(40, 190, 205), 15)
        } else {
            bordered(
                panel2,
                15,
                Color.rgb(43, 60, 91)
            )
        }

        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun text(
        value: String,
        sp: Int,
        color: Int,
        bold: Boolean
    ) = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL

        if (bold) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun lp(
        w: Int,
        h: Int
    ) = LinearLayout.LayoutParams(w, h)

    private fun gap(
        parent: LinearLayout,
        h: Int
    ) {
        parent.addView(
            View(this),
            lp(-1, dp(h))
        )
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun bordered(
        color: Int,
        radius: Int,
        stroke: Int
    ) = shape(color, radius).apply {
        setStroke(dp(1), stroke)
    }

    private fun gradient(
        start: Int,
        end: Int,
        radius: Int
    ) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(start, end)
    ).apply {
        cornerRadius = dp(radius).toFloat()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(receiver)
        }

        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val VPN_REQUEST = 71
        private const val PICK_CONFIG = 72
    }
}
