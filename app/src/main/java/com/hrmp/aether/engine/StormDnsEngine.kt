package com.tirexnet.app.engine

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts the real StormDNS Go client (MIT upstream) as an Android ARM64 process.
 * Its local SOCKS5 listener is then used as the Xray TUN outbound.
 */
object StormDnsEngine {
    @Volatile private var process: Process? = null
    @Volatile private var activePort: Int = 18000

    @Synchronized
    fun start(context: Context, rawProfile: String): String? {
        val current = process
        if (current != null && current.isAlive) return null
        val profile = EngineProfiles.decodeStormDns(rawProfile) ?: return "StormDNS profile is invalid. Check the domain, encryption key and DNS resolvers."
        return try {
            val executable = File(context.applicationInfo.nativeLibraryDir, "libstormdns.so")
            if (!executable.isFile) return "StormDNS engine is missing from this APK."
            executable.setExecutable(true, false)
            val dir = File(context.filesDir, "stormdns").apply { if (!exists() && !mkdirs()) throw IllegalStateException("storage") }
            val configFile = File(dir, "client_config.toml")
            val resolversFile = File(dir, "client_resolvers.txt")
            val logFile = File(dir, "client.log")
            configFile.writeText(profile.clientConfig)
            resolversFile.writeText(profile.resolvers + "\n")
            activePort = profile.port
            val p = ProcessBuilder(
                executable.absolutePath,
                "--config", configFile.absolutePath,
                "--resolvers", resolversFile.absolutePath
            ).directory(dir).redirectErrorStream(true).redirectOutput(logFile).apply {
                environment()["HOME"] = dir.absolutePath
                environment()["TMPDIR"] = dir.absolutePath
            }.start()
            process = p
            if (!waitForPort("127.0.0.1", profile.port, 12_000L)) {
                val exit = runCatching { p.exitValue() }.getOrNull()
                stop()
                return if (exit != null) "StormDNS exited during startup. Check the tunnel domain, key and resolver list." else "StormDNS proxy did not become ready."
            }
            null
        } catch (_: Throwable) {
            stop()
            "StormDNS could not start on this device."
        }
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        if (p != null) {
            runCatching { p.destroy() }
            try {
                if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: Throwable) { runCatching { p.destroyForcibly() } }
        }
    }

    fun isActive(): Boolean = process?.isAlive == true
    fun socksPort(): Int = activePort

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val p = process ?: return false
            if (!p.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 350) }
                return true
            } catch (_: Throwable) { Thread.sleep(180L) }
        }
        return false
    }
}
