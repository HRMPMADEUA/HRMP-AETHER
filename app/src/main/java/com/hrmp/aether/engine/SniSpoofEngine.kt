package com.tirexnet.app.engine

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root-only runner for the Patterniha-derived SNISPF Android/Linux engine.
 * The upstream engine is a TCP forwarder, not a SOCKS proxy; Xray's selected
 * outbound is pointed at its loopback listener while its real TLS SNI remains
 * unchanged. Raw wrong-sequence packet injection requires root/CAP_NET_RAW.
 */
object SniSpoofEngine {
    private const val ROOT_DIR = "/data/local/tmp/tirexnet-snispf"
    private const val PID_FILE = "$ROOT_DIR/engine.pid"
    @Volatile private var activePort: Int = 40443

    @Synchronized
    fun start(context: Context, rawProfile: String): String? {
        val profile = EngineProfiles.decodeSniSpoof(rawProfile)
            ?: return "Patterniha SNI profile is invalid. Recreate it from a TLS/REALITY server profile."
        return try {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libsnispf.so")
            if (!binary.isFile) return "Patterniha engine is missing from this APK."
            val config = org.json.JSONObject()
                .put("LISTEN_HOST", "127.0.0.1")
                .put("LISTEN_PORT", profile.listenPort)
                .put("CONNECT_IP", profile.connectIp)
                .put("CONNECT_PORT", profile.connectPort)
                .put("FAKE_SNI", profile.fakeSni)
                .put("BYPASS_METHOD", "wrong_seq")
                .put("FRAGMENT_STRATEGY", "sni_split")
                .put("FRAGMENT_DELAY", 0.05)
                .put("INTERFACE", "auto")
                .put("UTLS", "firefox")
                .put("AUTOSTART", false)
            val appConfig = File(context.filesDir, "patterniha-snispf.json")
            appConfig.writeText(config.toString(2))
            activePort = profile.listenPort

            val dir = ROOT_DIR
            val binTarget = "$dir/snispf"
            val cfgTarget = "$dir/config.json"
            val logTarget = "$dir/engine.log"
            val pidTarget = PID_FILE
            val script = buildString {
                append("id -u | grep -qx 0 || exit 41; ")
                append("mkdir -p ").append(q(dir)).append(" || exit 42; ")
                append("cp ").append(q(binary.absolutePath)).append(' ').append(q(binTarget)).append(" || exit 43; ")
                append("cp ").append(q(appConfig.absolutePath)).append(' ').append(q(cfgTarget)).append(" || exit 44; ")
                append("chmod 700 ").append(q(binTarget)).append("; chmod 600 ").append(q(cfgTarget)).append("; ")
                append("if [ -f ").append(q(pidTarget)).append(" ]; then kill \$(cat ").append(q(pidTarget)).append(") 2>/dev/null || true; fi; ")
                append("nohup ").append(q(binTarget)).append(" --config ").append(q(cfgTarget))
                    .append(" --quiet > ").append(q(logTarget)).append(" 2>&1 < /dev/null & ")
                append("echo \$! > ").append(q(pidTarget)).append("; sleep 1; kill -0 \$(cat ").append(q(pidTarget)).append(")")
            }
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(500)
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return if (output.isNotBlank()) "Root launch failed. Grant TIREXNET root access and retry." else "Root access is required for Patterniha packet injection."
            }
            // Do not probe the listener with a TCP connect: SNISPF treats every
            // accepted connection as a real flow and would emit an unnecessary
            // fake ClientHello toward the upstream server. The root shell already
            // verifies that the sidecar PID survives startup.
            null
        } catch (_: Throwable) {
            "Patterniha SNI engine could not start. Root access and a supported kernel are required."
        }
    }

    @Synchronized
    fun stop() {
        runCatching {
            val script = "if [ -f '$PID_FILE' ]; then kill \$(cat '$PID_FILE') 2>/dev/null || true; rm -f '$PID_FILE'; fi"
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start().waitFor(4, TimeUnit.SECONDS)
        }
    }

    fun listenPort(): Int = activePort

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
