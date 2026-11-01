package com.tirexnet.app.engine

import android.content.Context
import com.therealaleph.mhrv.Native
import java.net.InetSocketAddress
import java.net.Socket

/** Runs the upstream MHRV Rust proxy and exposes its local SOCKS5 listener to the Xray TUN bridge. */
object MhrvEngine {
    @Volatile private var handle: Long = 0L

    @Synchronized
    fun start(context: Context, rawProfile: String): String? {
        if (handle > 0L) return null
        val profile = EngineProfiles.decodeMhrv(rawProfile) ?: return "MHRV profile is invalid or incomplete."
        return try {
            Native.setDataDir(context.applicationContext.filesDir.absolutePath)
            val started = Native.startProxy(profile.configJson)
            if (started <= 0L) return "MHRV could not start. Check its deployment URL, key and settings."
            handle = started
            val ready = waitForSocks("127.0.0.1", profile.socksPort, 8000L)
            if (!ready) {
                runCatching { Native.stopProxy(started) }
                handle = 0L
                "MHRV started but its local proxy did not become ready."
            } else null
        } catch (_: UnsatisfiedLinkError) {
            "The MHRV engine is not included in this build. Rebuild the APK with the native engine workflow."
        } catch (_: Throwable) {
            "MHRV could not start on this device."
        }
    }

    @Synchronized
    fun stop() {
        val old = handle
        handle = 0L
        if (old > 0L) runCatching { Native.stopProxy(old) }
    }

    fun isActive(): Boolean = handle > 0L

    private fun waitForSocks(host: String, port: Int, timeoutMs: Long): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 350) }
                return true
            } catch (_: Throwable) {
                Thread.sleep(180L)
            }
        }
        return false
    }
}
