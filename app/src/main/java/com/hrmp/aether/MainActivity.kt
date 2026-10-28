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
import android.util.Base64
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

    private val bg = Color.rgb(3, 7, 19)
    private val panel = Color.rgb(8, 17, 38)
    private val panel2 = Color.rgb(14, 27, 57)
    private val cyan = Color.rgb(0, 213, 255)
    private val mint = Color.rgb(0, 245, 177)
    private val blue = Color.rgb(39, 110, 255)
    private val purple = Color.rgb(135, 45, 255)
    private val white = Color.rgb(242, 247, 255)
    private val muted = Color.rgb(145, 163, 194)
    private val red = Color.rgb(255, 83, 118)

    private var token = ""
    private var pendingRaw = ""
    private var pendingEngine = ConfigEngine.UNKNOWN

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var configInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var accountInfo: TextView
    private lateinit var dataUsedValue: TextView
    private lateinit var expiryValue: TextView
    private lateinit var detectionView: TextView
    private lateinit var activeProfileInfo: TextView
    private lateinit var configEditor: LinearLayout
    private var powerControl: TextView? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(XrayVpnService.EXTRA_MESSAGE) ?: return
            setConnectionStatus(
                message,
                intent.getBooleanExtra(XrayVpnService.EXTRA_ACTIVE, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

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
        if (token.isNotBlank()) loadAccount()
    }

    private fun render() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(bg)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        scroll.addView(root, ScrollView.LayoutParams(-1, -2))
        header()
        gap(root, 13)

        if (token.isBlank()) {
            loginScreen()
        } else {
            dashboard()
        }

        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (token.isNotBlank()) {
            screen.addView(bottomNavigation(), lp(-1, dp(66)))
        }
        setContentView(screen)
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "T"
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blue, cyan, purple)
            ).apply {
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), Color.rgb(80, 185, 255))
            }
            elevation = dp(5).toFloat()
        }
        row.addView(logo, lp(dp(55), dp(55)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(5), 0)
        }
        brand.addView(text("TIREXNET", 22, white, true), lp(-1, dp(31)))
        brand.addView(
            text("FAST  •  SECURE  •  FREEDOM", 9, muted, true),
            lp(-1, dp(21))
        )
        row.addView(brand, LinearLayout.LayoutParams(0, dp(55), 1f))

        val secure = TextView(this).apply {
            text = "● امن"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mint)
            gravity = Gravity.CENTER
            setPadding(dp(11), 0, dp(11), 0)
            background = bordered(panel, 18, mint)
        }
        row.addView(secure, lp(-2, dp(33)))
        root.addView(row, lp(-1, dp(56)))

        val divider = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, cyan, blue, purple, Color.TRANSPARENT)
            )
        }
        val dividerParams = lp(-1, dp(1))
        dividerParams.topMargin = dp(13)
        root.addView(divider, dividerParams)
    }

    private fun loginScreen() {
        gap(root, 14)
        root.addView(text("ورود امن به TIREXNET", 12, cyan, true), lp(-1, dp(25)))
        root.addView(text("اینترنت تو.\nقوانین تو.", 34, white, true), lp(-1, dp(91)))
        root.addView(
            text("با اطلاعات حساب اشتراک، وارد پنل خودت شو و کانفیگ‌ها را مدیریت کن.", 13, muted, false),
            lp(-1, -2)
        )

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = gradient(Color.rgb(5, 49, 78), Color.rgb(30, 19, 76), 22)
        }
        val heroParams = lp(-1, dp(126))
        heroParams.topMargin = dp(20)
        root.addView(hero, heroParams)
        hero.addView(text("✦  انتخاب هوشمند پروتکل", 12, cyan, true), lp(-1, dp(25)))
        hero.addView(text("یک برنامه، چندین پروتکل", 19, white, true), lp(-1, dp(33)))
        hero.addView(text("XRAY  •  WIREGUARD  •  AMNEZIAWG", 10, muted, true), lp(-1, dp(22)))

        gap(root, 23)
        root.addView(text("اطلاعات حساب", 15, white, true), lp(-1, dp(28)))
        usernameInput = input("نام کاربری")
        root.addView(usernameInput, lp(-1, dp(53)))
        gap(root, 10)
        passwordInput = input("رمز عبور").apply {
            inputType = 129
        }
        root.addView(passwordInput, lp(-1, dp(53)))
        gap(root, 15)
        root.addView(
            button("ورود به حساب  ←", true).apply { setOnClickListener { login() } },
            lp(-1, dp(54))
        )
        root.addView(
            text("احراز هویت حساب از طریق سرویس TIREXNET انجام می‌شود.", 10, muted, false).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(44))
        )
    }

    private fun dashboard() {
        root.addView(text("CONTROL CENTER  /  مرکز کنترل", 10, cyan, true), lp(-1, dp(22)))
        root.addView(text("شبکه‌ی تو", 30, white, true), lp(-1, dp(43)))
        root.addView(
            text("اتصال امن و مدیریت اشتراک در یک نگاه", 12, muted, false),
            lp(-1, dp(26))
        )

        // Primary connection card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = bordered(
                gradient(Color.rgb(5, 48, 64), Color.rgb(16, 20, 63), 22),
                22,
                Color.rgb(27, 117, 210)
            )
            elevation = dp(3).toFloat()
        }
        val heroParams = lp(-1, -2)
        heroParams.topMargin = dp(8)
        root.addView(hero, heroParams)

        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(
            text("●  وضعیت اتصال", 11, mint, true),
            LinearLayout.LayoutParams(0, dp(27), 1f)
        )
        heroTop.addView(
            text("TIREXNET", 10, cyan, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = bordered(panel, 14, purple)
            },
            lp(-2, dp(29))
        )
        hero.addView(heroTop, lp(-1, dp(30)))

        val heroBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        powerControl = TextView(this).apply {
            text = "⏻"
            textSize = 39f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            background = powerBackground(false)
            setOnClickListener { toggleConnection() }
            contentDescription = "اتصال یا قطع اتصال"
        }
        val powerParams = lp(dp(78), dp(78))
        powerParams.rightMargin = dp(9)
        heroBody.addView(powerControl, powerParams)

        val serverInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        serverInfo.addView(text("پروفایل انتخاب‌شده", 10, muted, true), lp(-1, dp(22)))
        activeProfileInfo = text("هنوز کانفیگی انتخاب نشده است", 12, white, true).apply {
            maxLines = 3
        }
        serverInfo.addView(activeProfileInfo, lp(-1, -2))
        val serverParams = LinearLayout.LayoutParams(0, -2, 1f)
        serverParams.rightMargin = dp(5)
        heroBody.addView(serverInfo, serverParams)

        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bordered(panel, 14, Color.rgb(35, 75, 138))
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        statusColumn.addView(text("ENGINE", 8, muted, true).apply {
            gravity = Gravity.CENTER
        }, lp(-1, dp(18)))
        detectionView = text("NONE", 11, cyan, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        statusColumn.addView(detectionView, lp(-1, dp(27)))
        heroBody.addView(statusColumn, lp(dp(76), dp(66)))
        val bodyParams = lp(-1, -2)
        bodyParams.topMargin = dp(8)
        hero.addView(heroBody, bodyParams)

        status = text("○  اتصال برقرار نیست", 11, muted, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minHeight = dp(40)
            background = bordered(panel, 12, Color.rgb(42, 64, 102))
            maxLines = 4
        }
        val statusParams = lp(-1, -2)
        statusParams.topMargin = dp(11)
        hero.addView(status, statusParams)

        // Subscription details
        val accountCard = card()
        val accountParams = lp(-1, -2)
        accountParams.topMargin = dp(13)
        root.addView(accountCard, accountParams)

        val accountTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        accountTop.addView(text("اشتراک من", 15, white, true), LinearLayout.LayoutParams(0, dp(27), 1f))
        accountTop.addView(
            text("● حساب", 9, mint, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = bordered(panel, 12, mint)
            },
            lp(-2, dp(26))
        )
        accountCard.addView(accountTop, lp(-1, dp(29)))
        accountInfo = text("در حال دریافت اطلاعات اشتراک…", 15, white, true)
        val accountInfoParams = lp(-1, dp(34))
        accountInfoParams.topMargin = dp(3)
        accountCard.addView(accountInfo, accountInfoParams)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val usedCard = statCard("حجم مصرفی", "در حال بررسی…", "میزان ترافیک مصرف‌شده")
        val expiryCard = statCard("اعتبار اشتراک", "—", "تاریخ پایان سرویس")
        val stat1 = LinearLayout.LayoutParams(0, dp(96), 1f)
        stat1.rightMargin = dp(5)
        val stat2 = LinearLayout.LayoutParams(0, dp(96), 1f)
        stat2.leftMargin = dp(5)
        stats.addView(usedCard, stat1)
        stats.addView(expiryCard, stat2)
        val statsParams = lp(-1, dp(96))
        statsParams.topMargin = dp(9)
        accountCard.addView(stats, statsParams)

        // Four compact quick actions
        root.addView(text("دسترسی سریع", 14, white, true), lp(-1, dp(27)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val actionSpecs = listOf(
            Triple("⇩", "دریافت", "دریافت کانفیگ"),
            Triple("⎘", "چسباندن", "از کلیپ‌بورد"),
            Triple("▤", "فایل", "واردکردن فایل"),
            Triple("◉", "حساب", "اطلاعات اشتراک")
        )
        val callbacks: List<() -> Unit> = listOf(
            { loadProfiles() },
            { pasteClipboard() },
            { pickFile() },
            { loadAccount() }
        )
        for (i in actionSpecs.indices) {
            val spec = actionSpecs[i]
            val tile = actionTile(spec.first, spec.second, spec.third, callbacks[i])
            val params = LinearLayout.LayoutParams(0, dp(90), 1f)
            if (i != 0) params.leftMargin = dp(5)
            actions.addView(tile, params)
        }
        val actionParams = lp(-1, dp(90))
        actionParams.topMargin = dp(5)
        root.addView(actions, actionParams)

        // Active profile and config editor
        val configCard = card()
        val configParams = lp(-1, -2)
        configParams.topMargin = dp(14)
        root.addView(configCard, configParams)

        val configTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        configTop.addView(text("کانفیگ‌های من", 15, white, true), LinearLayout.LayoutParams(0, dp(29), 1f))
        configTop.addView(text("● مدیریت", 9, cyan, true), lp(-2, dp(25)))
        configCard.addView(configTop, lp(-1, dp(30)))
        configCard.addView(
            text("پروتکل به‌صورت خودکار شناسایی می‌شود؛ برای اتصال، یک پروفایل انتخاب کن.", 10, muted, false),
            lp(-1, dp(35))
        )

        val profileButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listButton = button("لیست کانفیگ‌ها", true)
        listButton.setOnClickListener { loadProfiles() }
        val editButton = button("نمایش / ویرایش", false)
        editButton.setOnClickListener { showConfigEditor(configEditor.visibility != View.VISIBLE) }
        val listParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        listParams.rightMargin = dp(5)
        val editParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        editParams.leftMargin = dp(5)
        profileButtons.addView(listButton, listParams)
        profileButtons.addView(editButton, editParams)
        configCard.addView(profileButtons, lp(-1, dp(44)))

        configEditor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        configEditor.addView(text("متن کانفیگ", 11, cyan, true), lp(-1, dp(25)))
        configInput = EditText(this).apply {
            setTextColor(white)
            setHintTextColor(muted)
            hint = "VLESS / VMess / Trojan / Shadowsocks یا کانفیگ WireGuard"
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 8
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = bordered(panel2, 14, Color.rgb(45, 89, 153))
            setText(getSharedPreferences("tirexnet", MODE_PRIVATE).getString("last_config", "") ?: "")
        }
        configEditor.addView(configInput, lp(-1, dp(122)))
        // Keep a separate profile summary in the hero and engine value in this editor.
        val editorEngine = text("موتور: —", 10, mint, true)
        editorEngine.tag = "EDITOR_ENGINE"
        val editorEngineParams = lp(-1, dp(25))
        editorEngineParams.topMargin = dp(3)
        configEditor.addView(editorEngine, editorEngineParams)

        configInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val raw = s?.toString().orEmpty()
                getSharedPreferences("tirexnet", MODE_PRIVATE).edit().putString("last_config", raw).apply()
                val engine = ConfigDetector.detect(raw)
                val engineText = engineName(engine)
                detectionView.text = engineText
                editorEngine.text = "موتور شناسایی‌شده: " + engineText
                activeProfileInfo.text = profileSummary(raw)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val saved = configInput.text.toString().trim()
        detectionView.text = engineName(ConfigDetector.detect(saved))
        editorEngine.text = "موتور شناسایی‌شده: " + engineName(ConfigDetector.detect(saved))
        activeProfileInfo.text = profileSummary(saved)

        val editorActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pasteButton = button("چسباندن", false).apply { setOnClickListener { pasteClipboard() } }
        val importButton = button("واردکردن فایل", false).apply { setOnClickListener { pickFile() } }
        val ep1 = LinearLayout.LayoutParams(0, dp(43), 1f).apply { rightMargin = dp(5) }
        val ep2 = LinearLayout.LayoutParams(0, dp(43), 1f).apply { leftMargin = dp(5) }
        editorActions.addView(pasteButton, ep1)
        editorActions.addView(importButton, ep2)
        val editorActionsParams = lp(-1, dp(43))
        editorActionsParams.topMargin = dp(7)
        configEditor.addView(editorActions, editorActionsParams)

        val connectButton = button("اتصال امن  ↗", true).apply {
            setOnClickListener { connect() }
        }
        val connectParams = lp(-1, dp(49))
        connectParams.topMargin = dp(8)
        configEditor.addView(connectButton, connectParams)
        configCard.addView(configEditor, lp(-1, -2))

        root.addView(
            text("ENCRYPTED TUNNEL  •  TIREXNET", 9, muted, true).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(36))
        )

        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        setConnectionStatus(
            prefs.getString("connection_message", "Disconnected.") ?: "Disconnected.",
            prefs.getBoolean("connection_active", false)
        )
    }

    private fun bottomNavigation(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(6, 15, 34), Color.rgb(5, 9, 24))
            ).apply {
                setStroke(dp(1), Color.rgb(19, 57, 112))
                cornerRadius = dp(17).toFloat()
            }
            elevation = dp(8).toFloat()
        }

        addNavItem(bar, "⌂", "خانه", false) { scroll.smoothScrollTo(0, 0) }
        addNavItem(bar, "☷", "کانفیگ‌ها", false) { loadProfiles() }
        addNavItem(bar, "⏻", "اتصال", true) { toggleConnection() }
        addNavItem(bar, "▥", "آمار", false) { loadAccount() }
        addNavItem(bar, "♙", "پروفایل", false) {
            AlertDialog.Builder(this)
                .setTitle("حساب TIREXNET")
                .setItems(arrayOf("به‌روزرسانی اشتراک", "خروج از حساب", "بستن")) { _, which ->
                    when (which) {
                        0 -> loadAccount()
                        1 -> logout()
                    }
                }
                .show()
        }
        return bar
    }

    private fun addNavItem(
        parent: LinearLayout,
        icon: String,
        label: String,
        central: Boolean,
        click: () -> Unit
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(1), dp(2), dp(1))
            isClickable = true
            isFocusable = true
        }
        val iconView = TextView(this).apply {
            text = icon
            textSize = if (central) 27f else 22f
            gravity = Gravity.CENTER
            setTextColor(if (central) cyan else muted)
            if (central) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(6, 53, 112), Color.rgb(34, 21, 111))
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), blue)
                }
                elevation = dp(3).toFloat()
            }
        }
        item.addView(iconView, lp(if (central) dp(45) else -1, if (central) dp(42) else dp(32)))
        item.addView(
            text(label, 9, if (central) cyan else muted, central).apply {
                gravity = Gravity.CENTER
            },
            lp(-1, dp(17))
        )
        item.setOnClickListener { click() }
        parent.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun actionTile(
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(6), dp(3), dp(5))
            background = bordered(panel, 15, Color.rgb(28, 63, 119))
            isClickable = true
            isFocusable = true
        }
        tile.addView(
            text(icon, 22, cyan, true).apply { gravity = Gravity.CENTER },
            lp(-1, dp(31))
        )
        tile.addView(
            text(title, 10, white, true).apply { gravity = Gravity.CENTER },
            lp(-1, dp(20))
        )
        tile.addView(
            text(subtitle, 8, muted, false).apply { gravity = Gravity.CENTER; maxLines = 1 },
            lp(-1, dp(16))
        )
        tile.setOnClickListener { action() }
        return tile
    }

    private fun statCard(title: String, initial: String, footer: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(7))
            background = bordered(panel, 15, Color.rgb(28, 56, 100))
        }
        card.addView(text(title, 9, muted, true), lp(-1, dp(20)))
        val value = text(initial, 15, white, true)
        if (title == "حجم مصرفی") dataUsedValue = value else expiryValue = value
        card.addView(value, lp(-1, dp(31)))
        card.addView(text(footer, 8, muted, false), lp(-1, dp(21)))
        return card
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = bordered(panel, 20, Color.rgb(24, 54, 100))
    }

    private fun showConfigEditor(show: Boolean) {
        if (!::configEditor.isInitialized) return
        configEditor.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            configInput.requestFocus()
            scroll.post { scroll.smoothScrollTo(0, configEditor.top + dp(180)) }
        }
    }

    private fun engineName(engine: ConfigEngine): String = when (engine) {
        ConfigEngine.XRAY -> "XRAY"
        ConfigEngine.WIREGUARD -> "WIREGUARD"
        ConfigEngine.AMNEZIAWG -> "AMNEZIAWG"
        else -> "NONE"
    }

    private fun profileSummary(rawValue: String): String {
        val raw = rawValue.trim()
        if (raw.isBlank()) return "کانفیگی انتخاب نشده است\nاز بخش کانفیگ‌ها، اشتراک را بارگذاری کن."

        val scheme = raw.substringBefore("://", "config").uppercase(Locale.US)
        var name = ""
        var host = ""
        var port = ""

        if (raw.startsWith("vmess://", true)) {
            val payload = raw.substringAfter("://").substringBefore("#").trim()
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = runCatching {
                String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrNull()
            val json = runCatching { JSONObject(decoded ?: "") }.getOrNull()
            if (json != null) {
                name = json.optString("ps", "")
                host = json.optString("add", "")
                port = json.optString("port", "")
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

        val label = name.takeIf { it.isNotBlank() } ?: scheme
        val server = host.takeIf { it.isNotBlank() } ?: "سرور"
        val portLine = if (port.isNotBlank()) "  •  پورت " + port else ""
        return label + "\n" + server + portLine
    }

    // ------------------------------------------------------------------
    // VPN engine selection
    // ------------------------------------------------------------------

    private fun toggleConnection() {
        val active = getSharedPreferences("tirexnet", MODE_PRIVATE)
            .getBoolean("connection_active", false)
        if (active) disconnect() else connect()
    }

    private fun connect() {
        val raw = if (::configInput.isInitialized) configInput.text.toString().trim() else ""
        if (raw.isBlank()) {
            toast("اول یک کانفیگ از بخش «لیست کانفیگ‌ها» انتخاب کن.")
            showConfigEditor(true)
            return
        }

        val engine = ConfigDetector.detect(raw)
        if (engine == ConfigEngine.UNKNOWN) {
            toast("فرمت کانفیگ شناسایی نشد. از لینک VLESS / VMess / Trojan / Shadowsocks یا فایل WireGuard استفاده کن.")
            showConfigEditor(true)
            return
        }

        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        if (prefs.getBoolean("connection_active", false)) {
            toast("یک اتصال فعال است؛ ابتدا آن را قطع کن.")
            return
        }

        pendingRaw = raw
        pendingEngine = engine
        prefs.edit().putString("active_engine", engine.name).apply()

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
                setConnectionStatus("در حال راه‌اندازی هسته Xray…", false)
                val intent = Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START)
                    .putExtra(XrayVpnService.EXTRA_CONFIG, pendingRaw)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> {
                val amnezia = pendingEngine == ConfigEngine.AMNEZIAWG
                setConnectionStatus(
                    if (amnezia) "در حال راه‌اندازی AmneziaWG…" else "در حال راه‌اندازی WireGuard…",
                    false
                )
                WgTunnelEngine.start(this, pendingRaw, amnezia) { message, active ->
                    runOnUiThread {
                        setConnectionStatus(message, active)
                        if (!active) toast(message)
                    }
                }
            }
            else -> toast("یک پروفایل پشتیبانی‌شده انتخاب کن.")
        }
    }

    private fun disconnect() {
        val engine = getSharedPreferences("tirexnet", MODE_PRIVATE)
            .getString("active_engine", "")
        if (engine == "WIREGUARD" || engine == "AMNEZIAWG" || WgTunnelEngine.isActive) {
            WgTunnelEngine.stop { message, active ->
                runOnUiThread {
                    setConnectionStatus(message, active)
                    toast(message)
                }
            }
        }
        if (engine == "XRAY" || XrayVpnService.isActive) {
            startService(
                Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)
            )
        }
        setConnectionStatus("Disconnected.", false)
    }

    private fun setConnectionStatus(message: String, active: Boolean) {
        val lower = message.lowercase(Locale.US)
        val failed = !active && listOf("failed", "error", "invalid", "denied", "could not", "ناموفق", "خطا").any {
            lower.contains(it)
        }
        if (::status.isInitialized) {
            status.text = (if (active) "●  " else if (failed) "⚠  " else "○  ") + message
            status.setTextColor(if (active) mint else if (failed) red else muted)
            status.background = bordered(
                panel,
                12,
                if (active) mint else if (failed) red else Color.rgb(42, 64, 102)
            )
        }
        powerControl?.let {
            it.setTextColor(if (active) mint else cyan)
            it.background = powerBackground(active)
        }
        getSharedPreferences("tirexnet", MODE_PRIVATE).edit()
            .putString("connection_message", message)
            .putBoolean("connection_active", active)
            .apply()
    }

    private fun powerBackground(active: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (active) intArrayOf(Color.rgb(0, 86, 75), Color.rgb(12, 30, 78))
            else intArrayOf(Color.rgb(5, 38, 83), Color.rgb(35, 19, 93))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), if (active) mint else blue)
        }

    // ------------------------------------------------------------------
    // Clipboard, file import, and permission result
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                startSelectedEngine()
            } else {
                setConnectionStatus("VPN permission was denied.", false)
                toast("اجازه VPN لازم است.")
            }
        } else if (requestCode == PICK_CONFIG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use {
                    configInput.setText(readLimited(it))
                }
                showConfigEditor(true)
                toast("کانفیگ وارد شد.")
            } catch (e: Exception) {
                toast("خطا در واردکردن فایل: " + (e.message ?: "unknown error"))
            }
        }
    }

    private fun pasteClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                toast("کلیپ‌بورد خالی است.")
                return
            }
            configInput.setText(clip.getItemAt(0).coerceToText(this)?.toString().orEmpty())
            showConfigEditor(true)
        } catch (_: Exception) {
            toast("خواندن کلیپ‌بورد ناموفق بود.")
        }
    }

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

    // ------------------------------------------------------------------
    // Login, account, and config subscription APIs
    // ------------------------------------------------------------------

    private fun login() {
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            toast("نام کاربری و رمز عبور را وارد کن.")
            return
        }
        toast("در حال ورود امن…")
        io.execute {
            try {
                val response = request(
                    "POST",
                    "/api/login",
                    null,
                    JSONObject().put("username", user).put("password", pass)
                )
                val newToken = response.optString("token")
                require(newToken.isNotBlank()) { response.optString("error", "Login token is missing.") }
                getSharedPreferences("tirexnet", MODE_PRIVATE).edit().putString("token", newToken).apply()
                token = newToken
                runOnUiThread {
                    render()
                    loadAccount()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("ورود ناموفق بود: " + (e.message ?: "network error")) }
            }
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
                val expire = if (sub.isNull("expire")) "بدون تاریخ پایان" else sub.optString("expire", "—")
                runOnUiThread {
                    if (::accountInfo.isInitialized) {
                        accountInfo.text = appUser + "  •  " + panelStatus
                    }
                    if (::dataUsedValue.isInitialized) {
                        dataUsedValue.text = if (cap > 0) used + " / " + formatBytes(cap) else used
                    }
                    if (::expiryValue.isInitialized) {
                        expiryValue.text = if (expire.length > 18) expire.take(18) else expire
                    }
                    if (panelUser.isNotBlank() && panelUser != "—") {
                        // Keep the panel username visible without showing a false connection state.
                        if (::accountInfo.isInitialized && appUser.isBlank()) accountInfo.text = panelUser + " • " + panelStatus
                    }
                }
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("401")) {
                    getSharedPreferences("tirexnet", MODE_PRIVATE).edit().remove("token").apply()
                    token = ""
                    runOnUiThread {
                        render()
                        toast("نشست منقضی شده؛ دوباره وارد شو.")
                    }
                } else {
                    runOnUiThread { toast("به‌روزرسانی اشتراک ناموفق بود: " + (e.message ?: "network error")) }
                }
            }
        }
    }

    private fun loadProfiles() {
        if (token.isBlank()) {
            toast("ابتدا وارد حساب شو.")
            return
        }
        toast("در حال دریافت کانفیگ‌های اشتراک…")
        io.execute {
            try {
                val data = request("GET", "/api/me/configs", token, null)
                val arr = data.optJSONArray("configs")
                    ?: data.optJSONArray("profiles")
                    ?: data.optJSONArray("items")
                    ?: throw IllegalStateException("Worker response has no configs/profiles array.")
                val names = ArrayList<String>()
                val configs = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    val raw = when (item) {
                        is JSONObject -> item.optString(
                            "config",
                            item.optString("uri", item.optString("raw", item.optString("content", item.optString("link", ""))))
                        )
                        is String -> item
                        else -> ""
                    }.trim()
                    if (raw.isNotBlank()) {
                        configs.add(raw)
                        val itemName = (item as? JSONObject)?.optString("name", "")?.takeIf { it.isNotBlank() }
                        val protocol = engineName(ConfigDetector.detect(raw))
                        names.add((itemName ?: "پروفایل " + configs.size) + "  •  " + protocol)
                    }
                }
                runOnUiThread {
                    if (configs.isEmpty()) {
                        toast("اشتراک هیچ کانفیگ قابل‌استفاده‌ای برنگرداند.")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("انتخاب کانفیگ")
                            .setItems(names.toTypedArray()) { _, which ->
                                configInput.setText(configs[which])
                                showConfigEditor(false)
                                scroll.smoothScrollTo(0, 0)
                                toast("کانفیگ انتخاب شد؛ روی دکمه اتصال بزن.")
                            }
                            .setNegativeButton("بستن", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("دریافت کانفیگ‌ها ناموفق بود: " + (e.message ?: "endpoint unavailable"))
                }
            }
        }
    }

    private fun request(method: String, path: String, bearer: String?, body: JSONObject?): JSONObject {
        val c = URL(api + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer " + bearer)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }
        val code = c.responseCode
        val responseText = (
            if (code in 200..299) c.inputStream else c.errorStream
        )?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP " + code + ": " + responseText)
        return JSONObject(responseText)
    }

    private fun logout() {
        val old = token
        token = ""
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

    // ------------------------------------------------------------------
    // Reusable visual helpers
    // ------------------------------------------------------------------

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(white)
        setHintTextColor(muted)
        setSingleLine(true)
        setPadding(dp(14), 0, dp(14), 0)
        background = bordered(panel, 14, Color.rgb(43, 76, 127))
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(42)
        setTextColor(if (primary) Color.rgb(1, 13, 30) else white)
        background = if (primary) {
            gradient(cyan, mint, 14)
        } else {
            bordered(panel2, 14, Color.rgb(40, 72, 125))
        }
        setPadding(dp(6), 0, dp(6), 0)
        stateListAnimator = null
        elevation = dp(1).toFloat()
    }

    private fun text(value: String, sp: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun gap(parent: LinearLayout, h: Int) {
        parent.addView(View(this), lp(-1, dp(h)))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun bordered(color: Int, radius: Int, stroke: Int) = shape(color, radius).apply {
        setStroke(dp(1), stroke)
    }

    private fun bordered(drawable: GradientDrawable, radius: Int, stroke: Int) = drawable.apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun gradient(start: Int, end: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(start, end)
    ).apply {
        cornerRadius = dp(radius).toFloat()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val VPN_REQUEST = 71
        private const val PICK_CONFIG = 72
    }
}
