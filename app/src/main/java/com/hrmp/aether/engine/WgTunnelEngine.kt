package com.tirexnet.app.engine

import android.content.Context
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.TunnelBackend
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.AmneziaConfigNormalizer
import com.wgtunnel.parser.Config
import java.net.InetAddress
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
 * WireGuard / AmneziaWG engine.
 *
 * Important lifecycle note:
 * wgtunnel's TunnelBackend can leave a dead Android service binding after stop.
 * Reusing that instance causes "InternalError: Failed to acquire service" on the
 * next connect until the process is killed. This engine therefore always recreates
 * the backend after a full stop (or after an acquire-service failure).
 */
object WgTunnelEngine {

    private const val TUNNEL_ID = 1
    private const val HANDSHAKE_TIMEOUT_MS = 45_000L
    private const val PROBE_HOST = "connectivitycheck.gstatic.com"
    /** Give Android time to tear down the previous VpnService binding. */
    private const val POST_STOP_SETTLE_MS = 450L
    private const val ACQUIRE_RETRY_SETTLE_MS = 700L

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val operationMutex = Mutex()

    @Volatile
    private var backend: TunnelBackend? = null

    @Volatile
    private var monitor: AndroidNetworkMonitor? = null

    @Volatile
    var isActive: Boolean = false

    /** Redacted "Class: message <- root cause" of the last start/stop failure, for diagnostics. Never hidden. */
    @Volatile var lastError: String = ""
        private set

    private fun describeFailure(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val head = e.javaClass.simpleName + ": " + ConfigSanitizer.redact(e.message.orEmpty()).take(120)
        return if (root !== e) {
            head + " <- " + root.javaClass.simpleName + ": " +
                ConfigSanitizer.redact(root.message.orEmpty()).take(120)
        } else {
            head
        }
    }

    private fun isAcquireServiceFailure(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause }.toList()
        return chain.any { t ->
            val msg = t.message.orEmpty()
            msg.contains("Failed to acquire service", ignoreCase = true) ||
                msg.contains("acquire service", ignoreCase = true) ||
                (t.javaClass.simpleName.contains("InternalError", ignoreCase = true) &&
                    msg.contains("service", ignoreCase = true))
        }
    }

    /** Stop tunnel if any, drop backend so the next start creates a fresh service binding. */
    private suspend fun resetBackend(settleMs: Long = POST_STOP_SETTLE_MS) {
        val current = backend
        if (current != null) {
            runCatching { current.stop(TUNNEL_ID).getOrThrow() }
            // Some backend builds expose no explicit close; nulling forces a new instance.
        }
        backend = null
        // Keep the network monitor; recreating it is optional and registering many callbacks is worse.
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
        override fun updateState(state: Tunnel.State) {
            // Backend owns transport-state tracking.
        }
    }

    private suspend fun startOnce(
        app: Context,
        parsed: Config,
        amnezia: Boolean
    ): Pair<Boolean, String> {
        // If a previous tunnel is still marked active on this backend, stop it first.
        val existing = backend
        if (existing != null) {
            val previousStatus = runCatching { existing.status.first() }.getOrNull()
            if (previousStatus?.activeTunnels?.containsKey(TUNNEL_ID) == true) {
                runCatching { existing.stop(TUNNEL_ID).getOrThrow() }
                delay(POST_STOP_SETTLE_MS)
            }
        }

        val currentBackend = ensureBackend(app)
        isActive = false

        currentBackend.start(
            tunnel = newTunnel(),
            mode = BackendMode.Vpn(parsed)
        ).getOrThrow()

        // Backend.status is Flow, not StateFlow — wait for Up or failure.
        val finalStatus = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            currentBackend.status.first { status ->
                val state = status.activeTunnels[TUNNEL_ID]?.transportState
                state is Tunnel.State.Up ||
                    state is Tunnel.State.Up.HandshakeFailure ||
                    state is Tunnel.State.Down
            }
        } ?: run {
            runCatching { currentBackend.stop(TUNNEL_ID).getOrThrow() }
            isActive = false
            return false to "WireGuard did not receive a healthy handshake within 45 seconds."
        }

        val finalState = finalStatus.activeTunnels[TUNNEL_ID]?.transportState
        return when (finalState) {
            is Tunnel.State.Up.HandshakeFailure -> {
                runCatching { currentBackend.stop(TUNNEL_ID).getOrThrow() }
                isActive = false
                false to "WireGuard handshake failed. Check endpoint, peer keys and server."
            }
            is Tunnel.State.Up -> {
                isActive = true
                val okMsg = if (amnezia) {
                    "AmneziaWG connected. Handshake confirmed."
                } else {
                    "WireGuard connected. Handshake confirmed."
                }
                true to okMsg
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
        scope.launch {
            operationMutex.withLock {
                try {
                    val app = context.applicationContext
                    val profileDns = ProfileSettingsStore(app).get(raw)
                    val selectedDns = (listOf(profileDns.selectedResolver) + profileDns.dnsResolvers)
                        .mapNotNull(::wireGuardDnsIp)
                        .distinct()
                    val configuredRaw = if (selectedDns.isEmpty()) raw else applyProfileDns(raw, selectedDns)
                    val parsed = buildConfig(configuredRaw, amnezia)

                    val first = try {
                        startOnce(app, parsed, amnezia)
                    } catch (e: Throwable) {
                        if (isAcquireServiceFailure(e)) {
                            // Dead service binding from previous session — drop backend and retry once.
                            lastError = describeFailure(e)
                            resetBackend(ACQUIRE_RETRY_SETTLE_MS)
                            try {
                                startOnce(app, parsed, amnezia)
                            } catch (e2: Throwable) {
                                throw e2
                            }
                        } else {
                            throw e
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback(first.second, first.first)
                    }
                } catch (e: Throwable) {
                    runCatching { backend?.stop(TUNNEL_ID)?.getOrThrow() }
                    // Always drop a failed backend so the next user attempt is clean.
                    backend = null
                    isActive = false

                    lastError = describeFailure(e)
                    val root = generateSequence(e) { it.cause }.last()
                    val message = when {
                        e is SecurityException ->
                            "Android VPN permission is required."
                        e is UnsatisfiedLinkError || root is UnsatisfiedLinkError ->
                            "WireGuard startup failed: native tunnel library mismatch (${ConfigSanitizer.redact(root.message.orEmpty()).take(100)})."
                        isAcquireServiceFailure(e) ->
                            "WireGuard startup failed (service busy). Wait a second and try again, or force-stop the app once."
                        else ->
                            "WireGuard startup failed (${lastError.take(180)}). Check the profile and try again."
                    }

                    withContext(Dispatchers.Main) {
                        callback(message, false)
                    }
                }
            }
        }
    }

    private fun wireGuardDnsIp(value: String): String? {
        val clean = value.trim()
        val host = if (clean.startsWith("[")) {
            clean.substringAfter("[").substringBefore("]")
        } else if (clean.count { it == ':' } == 1 && clean.substringAfter(":").toIntOrNull() != null) {
            clean.substringBefore(":")
        } else {
            clean
        }
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) &&
            host.split('.').all { it.toIntOrNull() in 0..255 }
        val ipv6 = host.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(host)
        if (!ipv4 && !ipv6) return null
        // Reject hostnames; WireGuard DNS must be an IP.
        return host
    }

    private fun applyProfileDns(raw: String, dns: List<String>): String {
        val line = "DNS = ${dns.joinToString(", ")}"
        val pattern = Regex("(?im)^\\s*DNS\\s*=.*$")
        if (pattern.containsMatchIn(raw)) return pattern.replaceFirst(raw, line)
        val header = Regex("(?im)^\\s*\\[Interface]\\s*$")
        return if (header.containsMatchIn(raw)) {
            header.replaceFirst(raw, "[Interface]\n$line")
        } else {
            raw
        }
    }

    private fun buildConfig(
        raw: String,
        amnezia: Boolean
    ): Config {
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
            } else {
                throw e
            }
        }
    }

    fun stop(callback: (String, Boolean) -> Unit) {
        TirexNotifier.stopUpdates()

        scope.launch {
            operationMutex.withLock {
                try {
                    // Full teardown: stop tunnel AND drop backend so reconnect is clean.
                    resetBackend(POST_STOP_SETTLE_MS)
                    withContext(Dispatchers.Main) {
                        callback("VPN disconnected.", false)
                    }
                } catch (t: Throwable) {
                    lastError = "stop: " + describeFailure(t)
                    // Still drop backend; a half-dead instance is worse than recreating.
                    backend = null
                    isActive = false
                    withContext(Dispatchers.Main) {
                        callback("VPN disconnected.", false)
                    }
                }
            }
        }
    }
}
