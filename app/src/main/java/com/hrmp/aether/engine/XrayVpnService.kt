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
                    publishStatus("Disconnected.", false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG).orEmpty()

                startForegroundCompat()

                if (raw.isBlank()) {
                    publishStatus(
                        "Xray failed: configuration is empty.",
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
                            "Xray tunnel connected.",
                            true
                        )

                    } catch (e: Throwable) {
                        Log.e(TAG, "Xray start failed", e)

                        stopTunnel()

                        publishStatus(
                            "Xray failed: ${
                                e.message ?: e.javaClass.simpleName
                            }",
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

        val json = XrayConfigBuilder.build(raw)

        val builder = Builder()
            .setSession("TIREXNET • Xray")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addAddress("fd00:1::1", 126)
            .addRoute("::", 0)
            .addDnsServer("2606:4700:4700::1111")

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not exclude the app from its own tunnel: ${e.message}",
                e
            )
        }

        val pfd = builder.establish()
            ?: error("Android did not establish the VPN interface.")

        val assets = File(filesDir, "xray-assets")

        if (!assets.exists() && !assets.mkdirs()) {
            runCatching { pfd.close() }
            error("Could not create Xray asset directory.")
        }

        val latch = CountDownLatch(1)
        startedLatch = latch

        try {
            Seq.setContext(applicationContext)

            // Correct XUDP BaseKey:
            // URL-safe Base64 without padding, representing 32 bytes.
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
                        Log.i(
                            TAG,
                            "core shutdown callback (stopping=$stopping)"
                        )

                        if (!stopping && isActive) {
                            executor.execute {
                                stopTunnel()

                                publishStatus(
                                    "Xray failed: core stopped unexpectedly.",
                                    false
                                )

                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }

                        return 0L
                    }

                    override fun onEmitStatus(
                        l: Long,
                        s: String?
                    ): Long {
                        if (!s.isNullOrBlank()) {
                            Log.d(TAG, "core status: $s")
                        }

                        return 0L
                    }
                }
            )

            controller = core
            tun = pfd

            core.startLoop(json, pfd.fd)

            if (!latch.await(
                    STARTUP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            ) {
                throw IllegalStateException(
                    "core did not confirm startup within " +
                        "${STARTUP_TIMEOUT_SECONDS}s " +
                        "(check config/outbound)"
                )
            }

        } catch (e: Throwable) {
            controller = null
            tun = null

            runCatching { pfd.close() }

            throw IllegalStateException(
                "Xray core could not start: ${
                    e.message ?: e.javaClass.simpleName
                }",
                e
            )

        } finally {
            startedLatch = null
        }

        isActive = true
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

    private fun makeNotification(message: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("TIREXNET")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = makeNotification(
            "Starting secure Xray tunnel…"
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

        getSharedPreferences("tirexnet", MODE_PRIVATE)
            .edit()
            .putString("connection_message", message)
            .putBoolean("connection_active", active)
            .putString(
                "active_engine",
                if (active) "XRAY" else ""
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
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTIVE = "active"

        private const val CHANNEL = "tirexnet_xray"
        private const val NOTIFICATION_ID = 6021

        @Volatile
        var isActive: Boolean = false
            private set
    }
}
