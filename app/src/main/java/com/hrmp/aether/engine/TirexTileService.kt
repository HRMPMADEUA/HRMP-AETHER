package com.tirexnet.app.engine

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tirexnet.app.MainActivity
import com.tirexnet.app.R

/**
 * Quick Settings tile ("the button next to Wi-Fi / Bluetooth"). Users add it from the edit mode of the
 * quick settings panel. Tap = connect with the last selected profile / disconnect.
 */
class TirexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (TirexActions.isConnected()) {
            TirexActions.disconnect(this)
            // Optimistic: flip now, real state is re-read next time the panel opens.
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val prefs = getSharedPreferences(TirexNotifier.PREFS, MODE_PRIVATE)
        val raw = prefs.getString("last_config", "").orEmpty().trim()
        val engine = ConfigDetector.detect(raw)
        val canStartHere = raw.isNotEmpty() && engine == ConfigEngine.XRAY && VpnService.prepare(this) == null
        if (canStartHere) {
            try {
                val i = Intent(this, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_START).putExtra(XrayVpnService.EXTRA_CONFIG, raw)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
                return
            } catch (_: Throwable) {
                // Background start not allowed on this device: fall through and let the app do it.
            }
        }
        // WireGuard/AmneziaWG, a missing VPN permission or no saved profile: open the app and let it connect.
        openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TirexActions.EXTRA_AUTO_CONNECT, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = TirexActions.isConnected()
        tile.label = "TIREXNET"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_tirex)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) TirexNotifier.profileName(this) else "Off"
        tile.updateTile()
    }
}
