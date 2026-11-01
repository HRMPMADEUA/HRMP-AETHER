package com.tirexnet.app.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Rootless Patterniha/SNISPF runner.
 *
 * TIREXNET intentionally selects SNISPF's stream-fragmentation method. The
 * upstream wrong-sequence/raw-injection method needs CAP_NET_RAW/root, while
 * fragment-only operation does not. Xray connects to this loopback TCP relay;
 * its real TLS SNI is preserved in the base profile created by the wizard.
 */
object SniSpoofEngine {
    @Volatile private var engineProcess: Process? = null
    @Volatile private var activePort: Int = 40443
    private const val CONFIG_FILE = "patterniha-snispf.json"
    private const val LOG_FILE = "patterniha-snispf.log"

    @Synchronized
    fun start(context: Context, rawProfile: String): String? {
        val profile = EngineProfiles.decodeSniSpoof(rawProfile)
            ?: return "Patterniha profile is invalid. Recreate it from a TLS/REALITY server profile."

        val current = engineProcess
        if (current?.isAlive == true && activePort == profile.listenPort) return null
        stopLocked()

        return try {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libsnispf.so")
            if (!binary.isFile || !binary.canRead()) {
                return "Patterniha engine is missing from this APK. Rebuild with the SNISPF native step enabled."
            }

            val preferences = context.getSharedPreferences("tirexnet", Context.MODE_PRIVATE)
            val fragmentDelay = preferences.getString("sni_fragment_delay", "0.08")
                ?.toDoubleOrNull()?.coerceIn(0.01, 1.0) ?: 0.08
            val strategy = preferences.getString("sni_fragment_strategy", "sni_split")
                ?.takeIf { it in setOf("sni_split", "half", "multi", "tls_record_frag") } ?: "sni_split"

            val config = JSONObject()
                .put("LISTEN_HOST", "127.0.0.1")
                .put("LISTEN_PORT", profile.listenPort)
                .put("CONNECT_IP", profile.connectIp)
                .put("CONNECT_PORT", profile.connectPort)
                .put("FAKE_SNI", profile.fakeSni)
                // Rootless safe path. wrong_seq / combined raw injection are
                // deliberately not selected because they require root access.
                .put("BYPASS_METHOD", "fragment")
                .put("FRAGMENT_STRATEGY", strategy)
                .put("FRAGMENT_DELAY", fragmentDelay)
                .put("USE_TTL_TRICK", false)
                .put("INTERFACE", "")
                .put("UTLS", "firefox")
                .put("AUTOSTART", false)

            val app = context.applicationContext
            val configFile = File(app.filesDir, CONFIG_FILE)
            val logFile = File(app.filesDir, LOG_FILE)
            configFile.writeText(config.toString(2))
            logFile.delete()

            // The Go binary is built as an Android ARM64 PIE and packaged in
            // jniLibs. Android extracts native libraries as executable files.
            val process = ProcessBuilder(
                binary.absolutePath,
                "--config", configFile.absolutePath,
                "--no-raw",
                "--quiet"
            )
                .directory(app.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()

            engineProcess = process
            activePort = profile.listenPort
            // Wait for the local relay socket, not just the Go process. This
            // prevents Xray from racing ahead before SNISPF has bound its port.
            var listening = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline && process.isAlive && !listening) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", profile.listenPort), 180)
                    }
                    listening = true
                } catch (_: Throwable) {
                    Thread.sleep(80L)
                }
            }
            if (!process.isAlive || !listening) {
                val detail = runCatching { logFile.readText().takeLast(900).trim() }.getOrDefault("")
                stopLocked()
                return if (detail.isNotBlank()) {
                    "Patterniha could not start: $detail"
                } else if (listening) {
                    "Patterniha process exited during startup."
                } else {
                    "Patterniha did not open its local relay port. Check the native library and profile settings."
                }
            }
            null
        } catch (t: Throwable) {
            engineProcess = null
            "Patterniha rootless fragmentation engine failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val process = engineProcess
        engineProcess = null
        if (process != null) {
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
    }

    fun listenPort(): Int = activePort
}
