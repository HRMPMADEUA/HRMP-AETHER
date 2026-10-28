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
            setConnectionStatus(msg, intent.getBooleanExtra(XrayVpnService.EXTRA_ACTIVE, false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        token = getSharedPreferences("tirexnet", MODE_PRIVATE).getString("token", "") ?: ""
        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        render()
        if (token.isNotBlank()) loadAccount()
    }

    private fun render() {
        val scroll = ScrollView(this).apply { setFillViewport(true); setBackgroundColor(bg) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(root)
        header()
        gap(root, 22)
        if (token.isBlank()) loginScreen() else dashboard()
        setContentView(scroll)
    }

    private fun header() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("✦", 28, mint, true).apply { gravity = Gravity.CENTER; background = shape(panel2, 16) }, lp(dp(52), dp(52)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brand.addView(text("TIREXNET", 22, white, true), lp(-1, dp(31)))
        brand.addView(text("PRIVATE NETWORK • MULTI-CORE", 9, muted, true), lp(-1, dp(22)))
        row.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(text("SECURE", 9, mint, true).apply { gravity = Gravity.CENTER; background = bordered(panel, 14, mint); setPadding(dp(9), 0, dp(9), 0) }, lp(-2, dp(30)))
        root.addView(row, lp(-1, dp(54)))
    }

    private fun loginScreen() {
        root.addView(text("WELCOME TO YOUR PRIVATE NETWORK", 10, mint, true), lp(-1, dp(25)))
        root.addView(text("Your internet.\nYour rules.", 35, white, true), lp(-1, dp(94)))
        root.addView(text("Fast, private and simple access — built around your subscription.", 14, muted, false), lp(-1, -2))
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = gradient(Color.rgb(20, 53, 69), Color.rgb(32, 30, 72), 23) }
        val hp = lp(-1, dp(120)); hp.topMargin = dp(20); root.addView(hero, hp)
        hero.addView(text("✧  SMART PROTOCOL SELECTION", 11, mint, true), lp(-1, dp(25)))
        hero.addView(text("One app. Your available protocols.", 18, white, true), lp(-1, dp(34)))
        hero.addView(text("XRAY  •  WIREGUARD  •  AMNEZIAWG", 11, muted, true), lp(-1, dp(25)))
        gap(root, 22)
        root.addView(text("SIGN IN", 12, white, true), lp(-1, dp(25)))
        usernameInput = input("Username"); root.addView(usernameInput, lp(-1, dp(54)))
        gap(root, 12)
        passwordInput = input("Password").apply { inputType = 129 }; root.addView(passwordInput, lp(-1, dp(54)))
        gap(root, 14)
        root.addView(button("Sign in  →", true).apply { setOnClickListener { login() } }, lp(-1, dp(56)))
        root.addView(text("Account access is authenticated by TIREXNET.", 11, muted, false).apply { gravity = Gravity.CENTER }, lp(-1, dp(42)))
        root.addView(text("TIREXNET  /  SECURE ACCESS", 10, muted, true).apply { gravity = Gravity.CENTER }, lp(-1, dp(30)))
    }

    private fun dashboard() {
        root.addView(text("CONTROL CENTER", 10, mint, true), lp(-1, dp(23)))
        root.addView(text("Your network", 31, white, true), lp(-1, dp(46)))
        root.addView(text("Your account, traffic and connection profile.", 13, muted, false), lp(-1, dp(25)))

        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = gradient(Color.rgb(17, 48, 61), Color.rgb(29, 28, 64), 22) }
        val hp = lp(-1, dp(136)); hp.topMargin = dp(14); root.addView(hero, hp)
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        badgeRow.addView(text("SUBSCRIPTION", 10, muted, true), LinearLayout.LayoutParams(0, dp(24), 1f))
        badgeRow.addView(text("● ACCOUNT", 9, mint, true).apply { gravity = Gravity.CENTER; background = shape(panel, 12); setPadding(dp(10), dp(5), dp(10), dp(5)) }, lp(-2, dp(26)))
        hero.addView(badgeRow, lp(-1, dp(28)))
        accountInfo = text("Loading subscription…", 22, white, true); hero.addView(accountInfo, lp(-1, dp(42)))
        hero.addView(text("Account status is separate from VPN tunnel status.", 11, muted, false), lp(-1, dp(24)))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = statCard("DATA USED", "Loading…", "Panel usage")
        val right = statCard("EXPIRES", "—", "Subscription date")
        val p1 = LinearLayout.LayoutParams(0, dp(104), 1f); p1.rightMargin = dp(6)
        val p2 = LinearLayout.LayoutParams(0, dp(104), 1f); p2.leftMargin = dp(6)
        stats.addView(left, p1); stats.addView(right, p2)
        val stp = lp(-1, dp(104)); stp.topMargin = dp(12); root.addView(stats, stp)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(panel, 20); setPadding(dp(16), dp(14), dp(16), dp(15)) }
        val bp = lp(-1, -2); bp.topMargin = dp(15); root.addView(box, bp)
        box.addView(text("CONNECTION PROFILE", 13, white, true), lp(-1, dp(28)))
        box.addView(text("Paste or import a profile. TIREXNET detects the matching engine.", 11, muted, false), lp(-1, dp(38)))
        configInput = EditText(this).apply {
            setTextColor(white); setHintTextColor(muted); hint = "Paste Xray link or WireGuard / AmneziaWG config"
            textSize = 12f; gravity = Gravity.TOP or Gravity.START; minLines = 5; maxLines = 9
            setPadding(dp(13), dp(12), dp(13), dp(12)); background = bordered(panel2, 14, Color.rgb(49, 65, 96))
        }
        box.addView(configInput, lp(-1, dp(145)))
        detectionView = text("DETECTED ENGINE: NONE", 10, mint, true)
        box.addView(detectionView, lp(-1, dp(28)))
        configInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val engine = ConfigDetector.detect(s?.toString().orEmpty())
                detectionView.text = "DETECTED ENGINE: " + when (engine) {
                    ConfigEngine.XRAY -> "XRAY"
                    ConfigEngine.WIREGUARD -> "WIREGUARD"
                    ConfigEngine.AMNEZIAWG -> "AMNEZIAWG"
                    else -> if (s.isNullOrBlank()) "NONE" else "UNKNOWN FORMAT"
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val fetchProfiles = button("Load subscription profiles", false)
        val fetchP = lp(-1, dp(44)); fetchP.topMargin = dp(4); box.addView(fetchProfiles, fetchP)
        fetchProfiles.setOnClickListener { loadProfiles() }
        gap(box, 10)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = button("Paste", false); val import = button("Import file", false)
        val a = LinearLayout.LayoutParams(0, dp(46), 1f); a.rightMargin = dp(5)
        val b = LinearLayout.LayoutParams(0, dp(46), 1f); b.leftMargin = dp(5)
        actions.addView(paste, a); actions.addView(import, b); box.addView(actions, lp(-1, dp(46)))
        paste.setOnClickListener { pasteClipboard() }; import.setOnClickListener { pickFile() }

        gap(root, 14)
        status = text("○  DISCONNECTED", 13, mint, true).apply { gravity = Gravity.CENTER; background = shape(panel, 15) }
        root.addView(status, lp(-1, dp(48)))
        gap(root, 10)
        root.addView(button("CONNECT  ↗", true).apply { setOnClickListener { connect() } }, lp(-1, dp(58)))
        val dis = button("Disconnect", false); val disP = lp(-1, dp(48)); disP.topMargin = dp(8); root.addView(dis, disP)
        dis.setOnClickListener { disconnect() }
        val refresh = button("Refresh subscription", false); val rp = lp(-1, dp(46)); rp.topMargin = dp(10); root.addView(refresh, rp)
        refresh.setOnClickListener { loadAccount() }
        val logout = button("Sign out", false); val lop = lp(-1, dp(46)); lop.topMargin = dp(8); root.addView(logout, lop)
        logout.setOnClickListener { logout() }
        root.addView(text("ENCRYPTED TUNNEL • ENGINE-BASED ROUTING", 9, muted, true).apply { gravity = Gravity.CENTER }, lp(-1, dp(34)))
        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        setConnectionStatus(prefs.getString("connection_message", "Disconnected.") ?: "Disconnected.", prefs.getBoolean("connection_active", false))
    }

    private fun statCard(title: String, initial: String, footer: String): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)); background = shape(panel, 18) }
        card.addView(text(title, 9, muted, true), lp(-1, dp(23)))
        card.addView(text(initial, 19, white, true).apply { tag = title }, lp(-1, dp(34)))
        card.addView(text(footer, 10, muted, false), lp(-1, dp(20)))
        return card
    }

    private fun connect() {
        val raw = configInput.text.toString().trim()
        val engine = ConfigDetector.detect(raw)
        if (engine == ConfigEngine.UNKNOWN) { toast("Unrecognized profile. Use a VLESS / VMess / Trojan / Shadowsocks link or a WireGuard .conf."); return }
        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        if (prefs.getBoolean("connection_active", false)) {
            toast("A VPN is already active. Disconnect it before switching engines.")
            return
        }
        pendingRaw = raw; pendingEngine = engine
        prefs.edit().putString("active_engine", engine.name).apply()
        val permission = VpnService.prepare(this)
        if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return }
        startSelectedEngine()
    }

    private fun startSelectedEngine() {
        when (pendingEngine) {
            ConfigEngine.XRAY -> {
                setConnectionStatus("Starting Xray core…", false)
                val intent = Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, pendingRaw)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> {
                setConnectionStatus("Starting ${if (pendingEngine == ConfigEngine.AMNEZIAWG) "AmneziaWG" else "WireGuard"} core…", false)
                WgTunnelEngine.start(this, pendingRaw, pendingEngine == ConfigEngine.AMNEZIAWG) { message, active ->
                    setConnectionStatus(message, active)
                    if (!active) toast(message)
                }
            }
            else -> toast("Choose a supported profile.")
        }
    }

    private fun disconnect() {
        val engine = getSharedPreferences("tirexnet", MODE_PRIVATE).getString("active_engine", "")
        if (engine == "WIREGUARD" || engine == "AMNEZIAWG" || WgTunnelEngine.isActive) {
            WgTunnelEngine.stop { message, active -> setConnectionStatus(message, active); toast(message) }
        }
        if (engine == "XRAY" || XrayVpnService.isActive) {
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
        setConnectionStatus("Disconnected.", false)
    }

    private fun setConnectionStatus(message: String, active: Boolean) {
        if (::status.isInitialized) {
            status.text = (if (active) "●  " else "○  ") + message
            status.setTextColor(if (active) mint else muted)
        }
        getSharedPreferences("tirexnet", MODE_PRIVATE).edit()
            .putString("connection_message", message).putBoolean("connection_active", active)
            .apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startSelectedEngine() else { setConnectionStatus("VPN permission was denied.", false); toast("VPN permission is required.") }
        } else if (requestCode == PICK_CONFIG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.openInputStream(uri)?.use { configInput.setText(readLimited(it)) }; toast("Profile imported.") }
            catch (e: Exception) { toast("Import failed: ${e.message ?: "unknown error"}") }
        }
    }

    private fun pasteClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = manager.primaryClip
            if (clip == null || clip.itemCount == 0) { toast("Clipboard is empty."); return }
            configInput.setText(clip.getItemAt(0).coerceToText(this)?.toString().orEmpty())
        } catch (_: Exception) { toast("Could not read clipboard.") }
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, PICK_CONFIG)
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0
        while (true) {
            val n = stream.read(buffer); if (n < 0) break
            total += n; require(total <= 1024 * 1024) { "File exceeds 1 MB." }; out.write(buffer, 0, n)
        }
        return out.toString("UTF-8").trim()
    }

    private fun login() {
        val user = usernameInput.text.toString().trim(); val pass = passwordInput.text.toString()
        if (user.isBlank() || pass.isBlank()) { toast("Enter your username and password."); return }
        toast("Signing in securely…")
        io.execute {
            try {
                val response = request("POST", "/api/login", null, JSONObject().put("username", user).put("password", pass))
                val newToken = response.optString("token")
                require(newToken.isNotBlank()) { response.optString("error", "Login token is missing.") }
                getSharedPreferences("tirexnet", MODE_PRIVATE).edit().putString("token", newToken).apply()
                token = newToken
                runOnUiThread { render(); loadAccount() }
            } catch (e: Exception) { runOnUiThread { toast("Sign-in failed: ${e.message ?: "network error"}") } }
        }
    }

    private fun loadAccount() {
        if (token.isBlank()) return
        io.execute {
            try {
                val data = request("GET", "/api/me", token, null)
                val sub = data.optJSONObject("subscription") ?: JSONObject()
                val appUser = data.optString("username", "Account")
                val panelUser = sub.optString("username", "—")
                val panelStatus = sub.optString("status", "Unknown")
                val used = formatBytes(sub.optLong("usedTraffic", 0L))
                val cap = sub.optLong("dataLimit", 0L)
                val expire = if (sub.isNull("expire")) "No expiry returned" else sub.optString("expire", "—")
                runOnUiThread {
                    if (::accountInfo.isInitialized) accountInfo.text = "$appUser  •  $panelStatus"
                    findTaggedText("DATA USED")?.text = if (cap > 0) "$used / ${formatBytes(cap)}" else used
                    findTaggedText("EXPIRES")?.text = if (expire.length > 14) expire.take(14) else expire
                    toast("Subscription refreshed for $panelUser")
                }
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("401")) {
                    getSharedPreferences("tirexnet", MODE_PRIVATE).edit().remove("token").apply(); token = ""
                    runOnUiThread { render(); toast("Session expired. Sign in again.") }
                } else runOnUiThread { toast("Could not refresh subscription: ${e.message ?: "network error"}") }
            }
        }
    }

    private fun loadProfiles() {
        if (token.isBlank()) { toast("Sign in first."); return }
        toast("Checking your subscription profile feed…")
        io.execute {
            try {
                val data = request("GET", "/api/me/configs", token, null)
                val arr = data.optJSONArray("configs") ?: data.optJSONArray("profiles") ?: data.optJSONArray("items")
                    ?: throw IllegalStateException("Worker response has no configs/profiles array.")
                val names = ArrayList<String>()
                val rawConfigs = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    val raw = when (item) {
                        is JSONObject -> item.optString("config", item.optString("uri", item.optString("raw", item.optString("content", item.optString("link", "")))))
                        is String -> item
                        else -> ""
                    }.trim()
                    if (raw.isNotBlank()) {
                        rawConfigs.add(raw)
                        val itemName = (item as? JSONObject)?.optString("name", "")?.takeIf { it.isNotBlank() }
                        val engine = when (ConfigDetector.detect(raw)) {
                            ConfigEngine.XRAY -> "Xray"
                            ConfigEngine.WIREGUARD -> "WireGuard"
                            ConfigEngine.AMNEZIAWG -> "AmneziaWG"
                            else -> "Unknown"
                        }
                        names.add((itemName ?: "Profile ${rawConfigs.size}") + "  •  " + engine)
                    }
                }
                runOnUiThread {
                    if (rawConfigs.isEmpty()) toast("No usable profiles were returned by the Worker.")
                    else AlertDialog.Builder(this).setTitle("Subscription profiles").setItems(names.toTypedArray()) { _, which ->
                        configInput.setText(rawConfigs[which])
                    }.setNegativeButton("Cancel", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("Profile feed is not available yet: ${e.message ?: "endpoint unavailable"}. Import a config instead.")
                }
            }
        }
    }

    private fun findTaggedText(tag: String): TextView? {
        fun walk(view: View): TextView? {
            if (view is TextView && view.tag == tag) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            return null
        }
        return if (::root.isInitialized) walk(root) else null
    }

    private fun request(method: String, path: String, bearer: String?, body: JSONObject?): JSONObject {
        val c = URL(api + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun logout() {
        val old = token; token = ""
        getSharedPreferences("tirexnet", MODE_PRIVATE).edit().remove("token").apply()
        io.execute { runCatching { request("POST", "/api/logout", old, JSONObject()) } }
        render()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 14f; setTextColor(white); setHintTextColor(muted); setSingleLine(true)
        setPadding(dp(15), 0, dp(15), 0); background = bordered(panel, 14, Color.rgb(47, 62, 90))
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label; setAllCaps(false); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) bg else white); background = shape(if (primary) mint else panel2, 15)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun text(value: String, sp: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = sp.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun gap(parent: LinearLayout, h: Int) { parent.addView(View(this), lp(-1, dp(h))) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun bordered(color: Int, radius: Int, stroke: Int) = shape(color, radius).apply { setStroke(dp(1), stroke) }
    private fun gradient(start: Int, end: Int, radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply { cornerRadius = dp(radius).toFloat() }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        io.shutdown()
        super.onDestroy()
    }

    companion object { private const val VPN_REQUEST = 71; private const val PICK_CONFIG = 72 }
}
