package com.tirexnet.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.tirexnet.app.MainActivity
import com.tirexnet.app.R

/**
 * One notification for everything: profile name (title), live speed (text), "Connected" (sub text)
 * and a Disconnect button. Used by both the Xray service and the WireGuard/AmneziaWG backend.
 *
 * The small icon is the monochrome mark in res/drawable/ic_stat_tirex.xml: Android only renders the
 * alpha channel of status-bar icons, so a coloured logo image would show up as a white square.
 */
object TirexNotifier {
    const val CHANNEL = "tirexnet_status"
    const val PREFS = "tirexnet"
    const val KEY_PROFILE_NAME = "active_profile_name"
    private const val TICK_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ticker: Runnable? = null
    private var monitor: TrafficMonitor? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "TIREXNET connection", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun profileName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE_NAME, "").orEmpty().ifBlank { "TIREXNET" }

    /** [speedLine] null = plain status text. */
    fun build(context: Context, title: String, text: String, connected: Boolean): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        builder.setSmallIcon(R.drawable.ic_stat_tirex)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 23) builder.setColor(0xFF00B8E6.toInt())
        if (connected) {
            builder.setSubText("Connected")
            val stop = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TirexActionReceiver::class.java).setAction(TirexActions.ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= 23) {
                builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_tirex), "Disconnect", stop).build())
            }
        }
        return builder.build()
    }

    /** Notification for a live connection: name + speed. */
    fun buildConnected(context: Context, speedText: String = "Connected"): Notification =
        build(context, profileName(context), speedText, true)

    /**
     * Refreshes notification [id] every couple of seconds with the real speed (same counters as the in-app
     * Live Speed). Stops by itself when the system notification disappears, so it can never resurrect a
     * notification the service already removed.
     */
    fun startUpdates(context: Context, id: Int) {
        val app = context.applicationContext
        stopUpdates()
        val m = TrafficMonitor().also { it.reset() }
        monitor = m
        val startedAt = SystemClock.elapsedRealtime()
        val nm = app.getSystemService(NotificationManager::class.java)
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val r = object : Runnable {
            override fun run() {
                if (ticker !== this) return
                val posted = try { nm.activeNotifications.any { it.id == id } } catch (_: Throwable) { true }
                if (!posted && SystemClock.elapsedRealtime() - startedAt > 5000L) { stopUpdates(); return }
                val s = m.sample() // keep the baseline moving even while the screen is off
                if (pm.isInteractive && posted) {
                    val text = if (s.available)
                        "↓ " + TrafficMonitor.formatSpeed(s.downBytesPerSec) + "   ↑ " + TrafficMonitor.formatSpeed(s.upBytesPerSec)
                    else "Connected"
                    runCatching { nm.notify(id, buildConnected(app, text)) }
                }
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        handler.postDelayed(r, 1000L)
    }

    fun stopUpdates() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        monitor = null
    }
}

/** Connect/disconnect helpers shared by the notification button and the Quick Settings tile. */
object TirexActions {
    const val ACTION_DISCONNECT = "com.tirexnet.app.ACTION_DISCONNECT"
    const val EXTRA_AUTO_CONNECT = "auto_connect"

    fun isConnected(): Boolean = XrayVpnService.isActive || WgTunnelEngine.isActive || OpenVpnEngine.isActive

    fun disconnect(context: Context) {
        val app = context.applicationContext
        TirexNotifier.stopUpdates()
        if (WgTunnelEngine.isActive) {
            WgTunnelEngine.stop { message, active -> publish(app, message, active) }
        }
        if (OpenVpnEngine.isActive) {
            OpenVpnEngine.stop { message, active -> publish(app, message, active) }
        }
        if (XrayVpnService.isActive) {
            app.startService(Intent(app, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
        }
    }

    /** Lets an open app window and the stored flags follow a change made from outside the app. */
    private fun publish(app: Context, message: String, active: Boolean) {
        app.getSharedPreferences(TirexNotifier.PREFS, Context.MODE_PRIVATE).edit()
            .putString("connection_message", message).putBoolean("connection_active", active)
            .putString("active_engine", if (active) "WG" else "").apply()
        app.sendBroadcast(
            Intent(XrayVpnService.ACTION_STATUS).setPackage(app.packageName)
                .putExtra(XrayVpnService.EXTRA_MESSAGE, message).putExtra(XrayVpnService.EXTRA_ACTIVE, active)
        )
    }
}

class TirexActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TirexActions.ACTION_DISCONNECT) TirexActions.disconnect(context)
    }
}
