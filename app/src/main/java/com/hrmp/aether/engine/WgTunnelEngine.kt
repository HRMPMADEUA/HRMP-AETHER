package com.tirexnet.app.engine

import android.content.Context
import android.content.Intent
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.TunnelBackend
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.AmneziaConfigNormalizer
import com.wgtunnel.parser.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WireGuard / AmneziaWG engine backed by wgtunnel.
 *
 * Root cause of "InternalError: Failed to acquire service" after disconnect:
 * wgtunnel's RuntimeManager waits for com.wgtunnel.backend.service.VpnService
 * (and Companion / TunnelService) to register themselves. After stop(), those
 * Android services can stay half-alive in the process; the next start() times
 * out waiting for a fresh onStartCommand. Force-stopping the three services
 * and recreating TunnelBackend between sessions fixes reconnect without
 * killing the whole app process.
 */
object WgTunnelEngine {

    private const val TUNNEL_ID = 1
    private const val HANDSHAKE_TIMEOUT_MS = 45_000L
    private const val POST_STOP_SETTLE_MS = 600L
    private const val PRE_START_SETTLE_MS = 350L
    private const val RETRY_SETTLE_MS = 900L
    private const val MAX_START_ATTEMPTS = 3

    private val WG_SERVICE_CLASSES = listOf(
        "com.wgtunnel.backend.service.VpnService",
        "com.wgtunnel.backend.service.VpnCompanionService",
        "com.wgtunnel.backend.service.TunnelService"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()

    @Volatile private var backend: TunnelBackend? = null
    @Volatile private var monitor: AndroidNetworkMonitor? = null
    @Volatile private var appContext: Context? = null

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private fun describeFailure(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val head = e.javaClass.simpleName + ": " +
            ConfigSanitizer.redact(e.message.orEmpty()).take(120)
        return if (root !== e) {
            head + " <- " + root.javaClass.simpleName + ": " +
                ConfigSanitizer.redact(root.message.orEmpty()).take(120)
        } else head
    }

    private fun isAcquireServiceFailure(e: Throwable): Boolean {
        return generateSequence(e) { it.cause }.any { t ->
            val msg = t.message.orEmpty()
            msg.contains("Failed to acquire service", ignoreCase = true) ||
                msg.contains("acquire service", ignoreCase = true) ||
                msg.contains("Unable to start", ignoreCase = true) ||
                (msg.contains("service", ignoreCase = true) &&
                    msg.contains("timeout", ignoreCase = true))
        }
    }

    /**
     * Hard-stop every wgtunnel Android service so the next start() gets a clean
     * onStartCommand / RuntimeManager registration.
     */
    private fun forceStopWgtunnelServices(app: Context) {
        for (name in WG_SERVICE_CLASSES) {
            runCatching {
                val cls = Class.forName(name)
                app.stopService(Intent(app, cls))
            }
        }
        // Also try ACTION_STOP style if any service defines it (harmless if ignored).
        for (name in WG_SERVICE_CLASSES) {
            runCatching {
                val cls = Class.forName(name)
                app.stopService(Intent(app, cls).setAction("STOP"))
            }
        }
    }

    private suspend fun teardownFully(app: Context, settleMs: Long) {
        val current = backend
        if (current != null) {
            runCatching { current.stop(TUNNEL_ID).getOrThrow() }
            runCatching {
                // Prefer stop-all if the API exists on this version.
                val m = current.javaClass.methods.firstOrNull {
                    it.name == "stopAllActiveTunnels" && it.parameterTypes.isEmpty()
                }
                m?.invoke(current)
            }
        }
        backend = null
        forceStopWgtunnelServices(app)
        isActive = false
        if (settleMs > 0) delay(settleMs)
    }

    private fun ensureBackend(app: Context): TunnelBackend {
        backend?.let { return it }
        val networkMonitor = monitor ?: AndroidNetworkMonitor(app).also { monitor = it }
        return TunnelBackend(
            scope,
            TirexApplicationProvider(app),
            networkMonitor
        ).also { backend = it }
    }

    private fun newTunnel(): Tunnel = object : Tunnel {
        override val id: Int = TUNNEL_ID
        override val name: String = "TIREXNET"
        override val isMetered: Boolean = false
        override val scriptsEnabled: Boolean = false
        override val ipStrategy: Tunnel.IpStrategy =
            Tunnel.IpStrategy.PreferIpv6(recoveryEnabled = true)
        override val features: Set<Tunnel.Feature> = emptySet()
        override fun updateState(state: Tunnel.State) = Unit
    }

    private suspend fun startOnce(app: Context, parsed: Config, amnezia: Boolean): Pair<Boolean, String> {
        // Drop any stale service bindings before touching the backend.
        forceStopWgtunnelServices(app)
        delay(PRE_START_SETTLE_MS)

        // Never reuse a backend that may still hold a dead RuntimeManager binding.
        backend = null
        val currentBackend = ensureBackend(app)
        isActive = false

        // If library still thinks a tunnel is up, stop it first.
        runCatching {
            val status = currentBackend.status.first()
            if (status.activeTunnels.containsKey(TUNNEL_ID)) {
                currentBackend.stop(TUNNEL_ID).getOrThrow()
                delay(POST_STOP_SETTLE_MS)
            }
        }

        currentBackend.start(
            tunnel = newTunnel(),
            mode = BackendMode.Vpn(parsed)
        ).getOrThrow()

        val finalStatus = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            currentBackend.status.first { status ->
                val state = status.activeTunnels[TUNNEL_ID]?.transportState
                state is Tunnel.State.Up ||
                    state is Tunnel.State.Up.HandshakeFailure ||
                    state is Tunnel.State.Down
            }
        }

        if (finalStatus == null) {
            runCatching { currentBackend.stop(TUNNEL_ID).getOrThrow() }
            isActive = false
            return false to "WireGuard did not receive a healthy handshake within 45 seconds."
        }

        return when (val finalState = finalStatus.activeTunnels[TUNNEL_ID]?.transportState) {
            is Tunnel.State.Up.HandshakeFailure -> {
                runCatching { currentBackend.stop(TUNNEL_ID).getOrThrow() }
                isActive = false
                false to "WireGuard handshake failed. Check endpoint, peer keys and server."
            }
            is Tunnel.State.Up -> {
                isActive = true
                true to if (amnezia) {
                    "AmneziaWG connected. Handshake confirmed."
                } else {
                    "WireGuard connected. Handshake confirmed."
                }
            }
            else -> {
                runCatching { currentBackend.stop(TUNNEL_ID).getOrThrow() }
                isActive = false
                false to "WireGuard did not receive a healthy handshake within 45 seconds."
            }
        }
    }

    fun start(
        context: Context,
        raw: String,
        amnezia: Boolean,
        callback: (String, Boolean) -> Unit
    ) {
        val app = context.applicationContext
        appContext = app

        scope.launch {
            operationMutex.withLock {
                var lastThrowable: Throwable? = null
                try {
                    val profileDns = ProfileSettingsStore(app).get(raw)
                    val selectedDns = (listOf(profileDns.selectedResolver) + profileDns.dnsResolvers)
                        .mapNotNull(::wireGuardDnsIp)
                        .distinct()
                    val configuredRaw =
                        if (selectedDns.isEmpty()) raw else applyProfileDns(raw, selectedDns)
                    val parsed = buildConfig(configuredRaw, amnezia)

                    var result: Pair<Boolean, String>? = null
                    for (attempt in 1..MAX_START_ATTEMPTS) {
                        try {
                            result = startOnce(app, parsed, amnezia)
                            break
                        } catch (e: Throwable) {
                            lastThrowable = e
                            lastError = describeFailure(e)
                            // Always full teardown before retry — especially on acquire-service.
                            teardownFully(app, if (isAcquireServiceFailure(e)) RETRY_SETTLE_MS else POST_STOP_SETTLE_MS)
                            if (attempt == MAX_START_ATTEMPTS) throw e
                        }
                    }

                    val (ok, message) = result
                        ?: (false to "WireGuard startup failed. Check the profile and try again.")
                    withContext(Dispatchers.Main) { callback(message, ok) }
                } catch (e: Throwable) {
                    lastThrowable = e
                    lastError = describeFailure(e)
                    teardownFully(app, POST_STOP_SETTLE_MS)

                    val root = generateSequence(e) { it.cause }.last()
                    val message = when {
                        e is SecurityException ->
                            "Android VPN permission is required."
                        e is UnsatisfiedLinkError || root is UnsatisfiedLinkError ->
                            "WireGuard startup failed: native tunnel library mismatch (${ConfigSanitizer.redact(root.message.orEmpty()).take(100)})."
                        isAcquireServiceFailure(e) ->
                            "WireGuard service was busy. Try connecting again in a moment."
                        else ->
                            "WireGuard startup failed (${lastError.take(180)}). Check the profile and try again."
                    }
                    withContext(Dispatchers.Main) { callback(message, false) }
                }
            }
        }
    }

    fun stop(callback: (String, Boolean) -> Unit) {
        TirexNotifier.stopUpdates()
        val app = appContext

        scope.launch {
            operationMutex.withLock {
                try {
                    if (app != null) {
                        teardownFully(app, POST_STOP_SETTLE_MS)
                    } else {
                        runCatching { backend?.stop(TUNNEL_ID)?.getOrThrow() }
                        backend = null
                        isActive = false
                    }
                    withContext(Dispatchers.Main) {
                        callback("VPN disconnected.", false)
                    }
                } catch (t: Throwable) {
                    lastError = "stop: " + describeFailure(t)
                    backend = null
                    isActive = false
                    app?.let { forceStopWgtunnelServices(it) }
                    withContext(Dispatchers.Main) {
                        // Always report disconnected so the UI can reconnect.
                        callback("VPN disconnected.", false)
                    }
                }
            }
        }
    }

    private fun wireGuardDnsIp(value: String): String? {
        val clean = value.trim()
        val host = when {
            clean.startsWith("[") ->
                clean.substringAfter("[").substringBefore("]")
            clean.count { it == ':' } == 1 &&
                clean.substringAfter(":").toIntOrNull() != null ->
                clean.substringBefore(":")
            else -> clean
        }
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) &&
            host.split('.').all { it.toIntOrNull() in 0..255 }
        val ipv6 = host.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(host)
        return if (ipv4 || ipv6) host else null
    }

    private fun applyProfileDns(raw: String, dns: List<String>): String {
        val line = "DNS = ${dns.joinToString(", ")}"
        val pattern = Regex("(?im)^\\s*DNS\\s*=.*$")
        if (pattern.containsMatchIn(raw)) return pattern.replaceFirst(raw, line)
        val header = Regex("(?im)^\\s*\\[Interface]\\s*$")
        return if (header.containsMatchIn(raw)) {
            header.replaceFirst(raw, "[Interface]\n$line")
        } else raw
    }

    private fun buildConfig(raw: String, amnezia: Boolean): Config {
        require(raw.isNotBlank()) { "Configuration is empty" }
        if (!amnezia) {
            return Config.parseQuickString(raw).also { it.validate() }
        }
        val clean = ConfigSanitizer.sanitizeAmnezia(raw).text
        return try {
            AmneziaConfigNormalizer
                .ensureAmneziaCompatibility(Config.parseQuickString(clean))
                .also { it.validate() }
        } catch (e: Throwable) {
            if (e.message.orEmpty().contains("INVALID_SIGNATURE_FORMAT", ignoreCase = true)) {
                Config.parseQuickString(clean).also { it.validate() }
            } else throw e
        }
    }
}
