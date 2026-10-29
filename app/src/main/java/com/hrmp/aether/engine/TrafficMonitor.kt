package com.tirexnet.app.engine

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * Live speed from kernel byte counters of THIS app's UID (TrafficStats.getUidRx/TxBytes).
 *
 * Why this source: neither the Xray AAR nor the WG Tunnel backend exposes a stable per-tunnel
 * counter API in the code we can see, and both engines run inside this app's process. Every byte
 * the tunnel moves leaves/enters the device through this UID's sockets, so the UID counters
 * follow tunnel traffic.
 *
 * Scope / limits (documented, not hidden):
 *  - Values are encrypted on-the-wire bytes: they include protocol overhead and this app's own API calls.
 *  - Per-UID counters may be unsupported on some devices (TrafficStats.UNSUPPORTED) -> [Sample.available] = false.
 *  - Speed = counter delta / elapsed time. A decreasing counter (reset/reboot/reconnect) re-baselines
 *    instead of producing a negative or huge value.
 */
class TrafficMonitor {
    data class Sample(
        val available: Boolean,
        val downBytesPerSec: Double = 0.0,
        val upBytesPerSec: Double = 0.0,
        val totalDown: Long = 0L,
        val totalUp: Long = 0L
    )

    private val uid = Process.myUid()
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    /** Call when a session starts (connected). Totals restart from zero. */
    fun reset() {
        lastRx = TrafficStats.getUidRxBytes(uid)
        lastTx = TrafficStats.getUidTxBytes(uid)
        lastAt = SystemClock.elapsedRealtime()
        totalRx = 0L
        totalTx = 0L
    }

    fun sample(): Sample {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return Sample(false)
        val now = SystemClock.elapsedRealtime()
        if (lastRx < 0 || lastTx < 0) {
            lastRx = rx; lastTx = tx; lastAt = now
            return Sample(true, 0.0, 0.0, totalRx, totalTx)
        }
        val dtSec = (now - lastAt) / 1000.0
        var dRx = rx - lastRx
        var dTx = tx - lastTx
        if (dRx < 0) dRx = 0 // counter reset: re-baseline below
        if (dTx < 0) dTx = 0
        totalRx += dRx
        totalTx += dTx
        lastRx = rx; lastTx = tx; lastAt = now
        if (dtSec <= 0.05) return Sample(true, 0.0, 0.0, totalRx, totalTx)
        return Sample(true, dRx / dtSec, dTx / dtSec, totalRx, totalTx)
    }

    companion object {
        /** Bytes/s -> readable text. Mbps once the rate is large enough to read comfortably. */
        fun formatSpeed(bytesPerSec: Double): String {
            val bits = bytesPerSec * 8.0
            return when {
                bytesPerSec < 1024.0 -> String.format(java.util.Locale.US, "%.0f B/s", bytesPerSec)
                bits < 1_000_000.0 -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
                else -> String.format(java.util.Locale.US, "%.2f Mbps", bits / 1_000_000.0)
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
