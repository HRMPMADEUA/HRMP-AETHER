package com.tirexnet.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wgtunnel.backend.AndroidApplicationProvider
import com.wgtunnel.backend.state.BackendStatus
import com.tirexnet.app.MainActivity

class TirexApplicationProvider(context: Context) : AndroidApplicationProvider {
    override val context: Context = context.applicationContext
    private val channel = "tirexnet_wg"

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channel, "TIREXNET VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override val vpnNotificationId: Int = 6022
    override val proxyNotificationId: Int = 6023
    override val vpnInitNotification: Notification get() = notification("Starting WireGuard / AmneziaWG…")
    override val proxyInitNotification: Notification get() = notification("Preparing secure connection…")

    override fun createVpnConfigurePendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 13, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override suspend fun buildVpnPersistentNotification(status: BackendStatus): Notification =
        TirexNotifier.buildConnected(context).also { TirexNotifier.startUpdates(context, vpnNotificationId) }

    override suspend fun buildProxyPersistentNotification(status: BackendStatus): Notification = notification("TIREXNET secure tunnel")
    override fun refreshStatusUi() = Unit

    private fun notification(message: String): Notification = TirexNotifier.build(context, "TIREXNET", message, false)
}
