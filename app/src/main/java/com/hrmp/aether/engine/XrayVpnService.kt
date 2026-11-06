package com.tirexnet.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tirexnet.app.MainActivity
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class XrayVpnService : VpnService() {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var controller: CoreController? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var startedLatch: CountDownLatch? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var requestedEngine = "XRAY"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        clearStoredActiveFlag()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                executor.execute {
                    stopTunnel()
                    stopAuxiliaryEngines()
                    publishStatus("Disconnected.", false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                requestedEngine = intent.getStringExtra(EXTRA_ENGINE)?.takeIf { it.isNotBlank() } ?: "XRAY"

                startForegroundCompat()

                if (raw.isBlank()) {
                    publishStatus(
                        "Connection failed. Add a valid profile and try again.",
                        false
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                executor.execute {
                    try {
                        startTunnel(raw)

                        publishStatus(
                            "TIREXNET tunnel connected.",
                            true
                        )

                    } catch (e: Throwable) {
                        Log.e(TAG, "Xray start failed", e)

                        stopTunnel()
                        stopAuxiliaryEngines()

                        publishStatus(
                            "Connection failed. Check the selected profile and try again.",
                            false
                        )

                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startTunnel(raw: String) {
        stopTunnel()
        stopping = false

        val prefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
        val proxyMode = prefs.getString("connection_mode", "vpn") == "proxy"
        val proxyProtocol = prefs.getString("proxy_protocol", "socks") ?: "socks"
        val json = if (proxyMode) XrayConfigBuilder.buildProxy(raw, proxyProtocol) else XrayConfigBuilder.build(raw)

        var pfd: ParcelFileDescriptor? = null
        if (!proxyMode) {
            val builder = Builder()
                .setSession("TIREXNET • Xray")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addAddress("fd00:1::1", 126)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")

            val selected = prefs.getStringSet("split_packages", emptySet()).orEmpty()
            val mode = prefs.getString("split_mode", "exclude") ?: "exclude"
            val slipNetSplitEnabled = prefs.getBoolean("slipnet_split_tunnel", false)
            // Preserve TIREXNET's original split-tunnel behavior when a user has
            // selected apps in the existing screen, and also honor the SlipNet
            // settings toggle. With split tunneling disabled, only TIREXNET itself
            // stays outside the VPN so account/API controls remain reachable.
            val useSplitTunneling = slipNetSplitEnabled || selected.isNotEmpty() || mode == "include"
            if (useSplitTunneling && mode == "include") {
                var applied = 0
                selected.forEach { pkg ->
                    if (pkg != packageName) {
                        val exists = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
                        if (exists && runCatching { builder.addAllowedApplication(pkg) }.isSuccess) applied++
                    }
                }
                if (applied == 0) throw IllegalStateException("Select at least one installed app for Include mode.")
                runCatching { builder.addDisallowedApplication(packageName) }
            } else {
                // Keep TIREXNET outside its own tunnel so ping, API and local controls stay reachable.
                runCatching { builder.addDisallowedApplication(packageName) }
                if (useSplitTunneling) {
                    selected.forEach { pkg ->
                        if (pkg != packageName) {
                            val exists = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
                            if (exists) runCatching { builder.addDisallowedApplication(pkg) }
                        }
                    }
                }
            }
            pfd = builder.establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface.")
        }

        val assets = File(filesDir, "xray-assets")
        if (!assets.exists() && !assets.mkdirs()) {
            runCatching { pfd?.close() }
            throw IllegalStateException("Xray storage is unavailable.")
        }

        val latch = CountDownLatch(1)
        startedLatch = latch
        try {
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(
                assets.absolutePath,
                "VElSRVhORVQtWFVEUC1CQVNFLUtFWS0yMDI2LTAwMDE"
            )
            val core = Libv2ray.newCoreController(
                object : CoreCallbackHandler {
                    override fun startup(): Long {
                        Log.i(TAG, "core startup callback")
                        latch.countDown()
                        return 0L
                    }

                    override fun shutdown(): Long {
                        Log.i(TAG, "core shutdown callback (stopping=$stopping)")
                        if (!stopping && isActive) {
                            executor.execute {
                                stopTunnel()
                                publishStatus("Connection ended.", false)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        return 0L
                    }

                    override fun onEmitStatus(l: Long, s: String?): Long {
                        if (!s.isNullOrBlank()) Log.d(TAG, "core status received")
                        return 0L
                    }
                }
            )

            controller = core
            tun = pfd
            // libv2ray defines descriptor 0 as "run without an Android TUN".
            // This is used for loopback SOCKS5/HTTP proxy mode.
            core.startLoop(json, pfd?.fd ?: 0)
            if (!latch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException("Xray startup timed out.")
            }
        } catch (e: Throwable) {
            controller = null
            tun = null
            runCatching { pfd?.close() }
            throw IllegalStateException("Xray could not start.", e)
        } finally {
            startedLatch = null
        }
        isActive = true
    }

    private fun stopAuxiliaryEngines() {
        MhrvEngine.stop()
        StormDnsEngine.stop()
        CottenDnsEngine.stop()
    }

    private fun stopTunnel() {
        stopping = true

        try {
            controller?.stopLoop()
        } catch (e: Throwable) {
            Log.w(TAG, "stopLoop failed", e)
        }

        controller = null

        try {
            tun?.close()
        } catch (e: Exception) {
            Log.w(TAG, "tun close failed", e)
        }

        tun = null
        isActive = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "TIREXNET VPN",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun makeNotification(message: String): Notification =
        TirexNotifier.build(this, "TIREXNET", message, false)

    private fun startForegroundCompat() {
        val notification = makeNotification(
            if (getSharedPreferences("tirexnet", MODE_PRIVATE).getString("connection_mode", "vpn") == "proxy")
                "Starting local proxy…" else "Starting secure VPN…"
        )

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun clearStoredActiveFlag() {
        getSharedPreferences("tirexnet", MODE_PRIVATE)
            .edit()
            .putBoolean("connection_active", false)
            .putString("active_engine", "")
            .apply()
    }

    private fun publishStatus(
        message: String,
        active: Boolean
    ) {
        isActive = active
        if (active) {
            // Same notification id as the foreground one: name + live speed + Disconnect, refreshed in place.
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, TirexNotifier.buildConnected(this))
            }
            TirexNotifier.startUpdates(this, NOTIFICATION_ID)
        } else {
            TirexNotifier.stopUpdates()
        }

        getSharedPreferences("tirexnet", MODE_PRIVATE)
            .edit()
            .putString("connection_message", message)
            .putBoolean("connection_active", active)
            .putString(
                "active_engine",
                if (active) requestedEngine else ""
            )
            .apply()

        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_ACTIVE, active)
        )
    }

    override fun onRevoke() {
        executor.execute {
            stopTunnel()
            stopAuxiliaryEngines()

            publishStatus(
                "VPN permission was revoked.",
                false
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        stopAuxiliaryEngines()
        clearStoredActiveFlag()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TirexXray"

        private const val STARTUP_TIMEOUT_SECONDS = 10L

        const val ACTION_START =
            "com.tirexnet.app.XRAY_START"

        const val ACTION_STOP =
            "com.tirexnet.app.XRAY_STOP"

        const val ACTION_STATUS =
            "com.tirexnet.app.CONNECTION_STATUS"

        const val EXTRA_CONFIG = "config"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTIVE = "active"

        private const val CHANNEL = "tirexnet_xray"
        private const val NOTIFICATION_ID = 6021

        @Volatile
        var isActive: Boolean = false
            private set
    }
}
