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
import com.tirexnet.app.MainActivity
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.Executors

class XrayVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var controller: CoreController? = null
    @Volatile private var tun: ParcelFileDescriptor? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> executor.execute { stopTunnel(); publishStatus("Disconnected.", false); stopSelf(startId) }
            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                if (raw.isBlank()) { publishStatus("Xray configuration is empty.", false); stopSelf(startId); return START_NOT_STICKY }
                startForegroundCompat()
                executor.execute {
                    try {
                        startTunnel(raw)
                        publishStatus("Xray tunnel connected.", true)
                    } catch (e: Throwable) {
                        stopTunnel()
                        publishStatus("Xray failed: ${e.message ?: e.javaClass.simpleName}", false)
                        stopSelf(startId)
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(raw: String) {
        stopTunnel()
        val json = XrayConfigBuilder.build(raw)
        val builder = Builder().setSession("TIREXNET • Xray").setMtu(1500)
            .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1")
            .addAddress("fd00:1::1", 126).addRoute("::", 0).addDnsServer("2606:4700:4700::1111")
        runCatching { builder.addDisallowedApplication(packageName) }
        val pfd = builder.establish() ?: error("Android did not establish the VPN interface.")
        val assets = File(filesDir, "xray-assets")
        if (!assets.exists() && !assets.mkdirs()) { pfd.close(); error("Could not create Xray asset directory.") }
        try {
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(assets.absolutePath, "TIREXNET-XUDP-BASE-KEY-2026-0001")
            val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(l: Long, s: String?): Long = 0L
            })
            core.startLoop(json, pfd.fd)
            controller = core
            tun = pfd
            isActive = true
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            throw IllegalStateException("Xray core could not start: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun stopTunnel() {
        try { controller?.stopLoop() } catch (_: Throwable) { }
        controller = null
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        isActive = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "TIREXNET VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun makeNotification(message: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("TIREXNET").setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(pending).setOngoing(true).build()
    }

    private fun startForegroundCompat() {
        val notification = makeNotification("Starting secure Xray tunnel…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun publishStatus(message: String, active: Boolean) {
        isActive = active
        getSharedPreferences("tirexnet", MODE_PRIVATE).edit().putString("connection_message", message)
            .putBoolean("connection_active", active).putString("active_engine", if (active) "XRAY" else "").apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_ACTIVE, active))
    }

    override fun onRevoke() {
        executor.execute { stopTunnel(); publishStatus("VPN permission was revoked.", false); stopSelf() }
        super.onRevoke()
    }

    override fun onDestroy() { stopTunnel(); executor.shutdown(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.tirexnet.app.XRAY_START"
        const val ACTION_STOP = "com.tirexnet.app.XRAY_STOP"
        const val ACTION_STATUS = "com.tirexnet.app.CONNECTION_STATUS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTIVE = "active"
        private const val CHANNEL = "tirexnet_xray"
        private const val NOTIFICATION_ID = 6021
        @Volatile var isActive: Boolean = false
            private set
    }
}
