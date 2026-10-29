package com.tirexnet.app.engine

import android.content.Context
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.TunnelBackend
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.AmneziaConfigNormalizer
import com.wgtunnel.parser.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses WG Tunnel's published userspace/JNI backend for standard WireGuard and AmneziaWG. */
object WgTunnelEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var backend: TunnelBackend? = null
    @Volatile private var monitor: AndroidNetworkMonitor? = null
    @Volatile var isActive: Boolean = false
        private set

    fun start(context: Context, raw: String, amnezia: Boolean, callback: (String, Boolean) -> Unit) {
        scope.launch {
            try {
                val app = context.applicationContext
                val parsed = buildConfig(raw, amnezia)
                if (backend == null) {
                    monitor = AndroidNetworkMonitor(app)
                    backend = TunnelBackend(scope, TirexApplicationProvider(app), monitor!!)
                }
                val tunnel = object : Tunnel {
                    override val id = 1
                    override val name = "TIREXNET"
                    override val isMetered = false
                    override val scriptsEnabled = false
                    override val ipStrategy: Tunnel.IpStrategy = Tunnel.IpStrategy.Ipv4Only
                    override val features: Set<Tunnel.Feature> = emptySet()
                    override fun updateState(state: Tunnel.State) = Unit
                }
                backend!!.start(tunnel = tunnel, mode = BackendMode.Vpn(parsed)).getOrThrow()
                isActive = true
                withContext(Dispatchers.Main) { callback(if (amnezia) "AmneziaWG connected." else "WireGuard connected.", true) }
            } catch (e: Throwable) {
                isActive = false
                withContext(Dispatchers.Main) { callback("WireGuard / AmneziaWG failed: " + ConfigSanitizer.redact(e.message ?: e.javaClass.simpleName), false) }
            }
        }
    }

    /**
     * Plain WireGuard is parsed untouched. AmneziaWG configs are sanitized first (invalid I1..I5
     * signature values are dropped). If the engine still reports INVALID_SIGNATURE_FORMAT after the
     * compatibility normalizer ran, the config is retried once without the normalizer so we can tell
     * whether the normalizer is the one introducing the value.
     */
    private fun buildConfig(raw: String, amnezia: Boolean): Config {
        if (!amnezia) return Config.parseQuickString(raw).also { it.validate() }
        val clean = ConfigSanitizer.sanitizeAmnezia(raw).text
        return try {
            AmneziaConfigNormalizer.ensureAmneziaCompatibility(Config.parseQuickString(clean)).also { it.validate() }
        } catch (e: Throwable) {
            if (e.message.orEmpty().contains("INVALID_SIGNATURE_FORMAT")) {
                Config.parseQuickString(clean).also { it.validate() }
            } else throw e
        }
    }

    fun stop(callback: (String, Boolean) -> Unit) {
        scope.launch {
            try {
                backend?.stopAllActiveTunnels()?.getOrThrow()
                isActive = false
                withContext(Dispatchers.Main) { callback("VPN disconnected.", false) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { callback("Disconnect failed: ${e.message ?: e.javaClass.simpleName}", isActive) }
            }
        }
    }
}
