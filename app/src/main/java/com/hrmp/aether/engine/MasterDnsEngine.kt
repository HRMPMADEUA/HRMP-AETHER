package com.tirexnet.app.engine

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Starts the actual MasterDnsVPN Android/ARM64 client and exposes its loopback SOCKS5 port. */
object MasterDnsEngine {
    @Volatile private var process: Process? = null
    @Volatile private var activePort: Int = 18000

    @Synchronized
    fun start(context: Context, rawProfile: String): String? {
        val current = process
        if (current != null && current.isAlive) return null

        val profile = MasterDnsProfiles.decode(rawProfile)
            ?: return "MasterDNS profile is invalid. Check its tunnel domains, encryption key and resolvers."
        val profileSettings = ProfileSettingsStore(context).get(rawProfile)
        val customResolvers = (listOf(profileSettings.selectedResolver) + profileSettings.dnsResolvers)
            .map { it.trim() }.filter { it.isNotEmpty() && EngineProfiles.isResolverEndpoint(it) }.distinct()
        val effectiveResolvers = if (customResolvers.isNotEmpty()) customResolvers.joinToString("\n") else profile.resolvers

        return try {
            val executable = File(context.applicationInfo.nativeLibraryDir, "libmasterdnsvpn.so")
            if (!executable.isFile) return "MasterDNS engine is missing from this APK. Rebuild TIREXNET with the MasterDNS runtime."
            executable.setExecutable(true, false)

            val workDir = File(context.filesDir, "masterdns").apply {
                if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create MasterDNS workspace")
            }
            val configFile = File(workDir, "client_config.toml")
            val resolversFile = File(workDir, "client_resolvers.txt")
            val consoleFile = File(workDir, "engine-console.log")
            configFile.writeText(profile.clientConfig)
            resolversFile.writeText(effectiveResolvers + "\n")
            activePort = profile.listenPort

            val child = ProcessBuilder(
                executable.absolutePath,
                "--config", configFile.absolutePath,
                "--resolvers", resolversFile.absolutePath
            ).directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(consoleFile)
                .apply {
                    environment()["HOME"] = workDir.absolutePath
                    environment()["TMPDIR"] = workDir.absolutePath
                }
                .start()

            process = child
            if (!waitForPort(profile.listenPort, 15_000L)) {
                val exitCode = runCatching { child.exitValue() }.getOrNull()
                stop()
                return if (exitCode != null) {
                    "MasterDNS exited during startup. Verify that the domains, resolver list and shared key match the server."
                } else {
                    "MasterDNS did not open its local SOCKS5 port. Check the profile and try again."
                }
            }
            null
        } catch (_: Throwable) {
            stop()
            "MasterDNS could not start on this Android device."
        }
    }

    @Synchronized
    fun stop() {
        val child = process
        process = null
        if (child != null) {
            runCatching { child.destroy() }
            try {
                if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
            } catch (_: Throwable) {
                runCatching { child.destroyForcibly() }
            }
        }
    }

    fun isActive(): Boolean = process?.isAlive == true
    fun socksPort(): Int = activePort

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val child = process ?: return false
            if (!child.isAlive) return false
            try {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (_: Throwable) {
                try { Thread.sleep(180L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
        return false
    }
}
