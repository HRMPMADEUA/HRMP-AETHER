package com.tirexnet.app.engine

import android.content.Context
import android.util.Log
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.TunnelBackend
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.AmneziaConfigNormalizer
import com.wgtunnel.parser.Config
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object WgTunnelEngine {

    private const val TUNNEL_ID = 1
    private const val HANDSHAKE_TIMEOUT_MS = 45_000L
    private const val PROBE_HOST = "connectivitycheck.gstatic.com"
    private const val TAG = "TirexWg"

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val operationMutex = Mutex()

    @Volatile
    private var backend: TunnelBackend? = null

    @Volatile
    private var monitor: AndroidNetworkMonitor? = null

    @Volatile
    var isActive: Boolean = false
        private set

    fun start(
        context: Context,
        raw: String,
        amnezia: Boolean,
        callback: (String, Boolean) -> Unit
    ) {
        scope.launch {
            operationMutex.withLock {
                var currentBackend: TunnelBackend? = null
                // Diagnostics only: records which step was running if startup throws.
                var stage = "PARSE_CONFIG"
                val engineName = if (amnezia) "AMNEZIAWG" else "WIREGUARD"

                try {
                    val app = context.applicationContext
                    val profileDns = ProfileSettingsStore(app).get(raw)
                    val selectedDns = (listOf(profileDns.selectedResolver) + profileDns.dnsResolvers).mapNotNull(::wireGuardDnsIp).distinct()
                    val configuredRaw = if (selectedDns.isEmpty()) raw else applyProfileDns(raw, selectedDns)
                    val parsed = buildConfig(configuredRaw, amnezia)
                    Log.i(TAG, "engine=$engineName profile=${profileDns.profileId} config parsed OK, profileDnsOverride=${selectedDns.isNotEmpty()}")
                    stage = "BACKEND_INIT"

                    if (backend == null) {
                        val networkMonitor = AndroidNetworkMonitor(app)
                        monitor = networkMonitor

                        backend = TunnelBackend(
                            scope,
                            TirexApplicationProvider(app),
                            networkMonitor
                        )
                    }

                    currentBackend = backend
                        ?: throw IllegalStateException(
                            "WireGuard backend unavailable"
                        )

                    Log.i(TAG, "backend ready")
                    stage = "STOP_PREVIOUS"
                    val previousStatus = currentBackend.status.first()

                    if (
                        previousStatus.activeTunnels.containsKey(TUNNEL_ID)
                    ) {
                        currentBackend.stop(TUNNEL_ID).getOrThrow()
                    }

                    isActive = false

                    val tunnel = object : Tunnel {

                        override val id: Int = TUNNEL_ID
                        override val name: String = "TIREXNET"
                        override val isMetered: Boolean = false
                        override val scriptsEnabled: Boolean = false

                        override val ipStrategy: Tunnel.IpStrategy =
                            Tunnel.IpStrategy.PreferIpv6(
                                recoveryEnabled = true
                            )

                        override val features: Set<Tunnel.Feature> =
                            emptySet()

                        override fun updateState(state: Tunnel.State) {
                            // Backend owns transport-state tracking.
                        }
                    }

                    stage = "START_TUNNEL"
                    currentBackend.start(
                        tunnel = tunnel,
                        mode = BackendMode.Vpn(parsed)
                    ).getOrThrow()
                    Log.i(TAG, "tunnel start returned OK, waiting for handshake")
                    stage = "WAIT_HEALTHY"

                    // Trigger initial DNS traffic for handshake.
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            InetAddress.getAllByName(PROBE_HOST)
                        }
                    }

                    // Backend.status is Flow, not StateFlow.
                    val healthyStatus = withTimeoutOrNull(
                        HANDSHAKE_TIMEOUT_MS
                    ) {
                        currentBackend.status.first { status ->
                            status.activeTunnels[TUNNEL_ID]
                                ?.transportState is Tunnel.State.Up.Healthy
                        }
                    }

                    if (healthyStatus != null) {
                        isActive = true

                        val message = if (amnezia) {
                            "AmneziaWG connected. Handshake confirmed."
                        } else {
                            "WireGuard connected. Handshake confirmed."
                        }

                        withContext(Dispatchers.Main) {
                            callback(message, true)
                        }

                    } else {
                        val finalStatus =
                            currentBackend.status.first()

                        val finalState =
                            finalStatus.activeTunnels[TUNNEL_ID]
                                ?.transportState

                        runCatching {
                            currentBackend.stop(TUNNEL_ID).getOrThrow()
                        }

                        isActive = false
                        Log.w(TAG, "no healthy handshake within timeout, finalState=${finalState?.javaClass?.simpleName}")

                        val message =
                            if (finalState is Tunnel.State.Up.HandshakeFailure) {
                                "WireGuard handshake failed. Check endpoint, peer keys and server."
                            } else {
                                "WireGuard did not receive a healthy handshake within 45 seconds."
                            }

                        withContext(Dispatchers.Main) {
                            callback(message, false)
                        }
                    }

                } catch (e: Throwable) {

                    // Keep the real failure: log the whole chain (class + redacted message, no secrets).
                    val root = generateSequence(e) { it.cause }.last()
                    Log.e(TAG, "startup failed engine=$engineName stage=$stage ${describe(e, root)}")
                    Log.e(
                        TAG,
                        "root cause frames: " + root.stackTrace.take(8)
                            .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    )

                    if (currentBackend != null) {
                        val cleanup = runCatching {
                            currentBackend.stop(TUNNEL_ID).getOrThrow()
                        }
                        Log.i(TAG, "cleanup after failed startup: " + if (cleanup.isSuccess) "stopped" else "failed (${cleanup.exceptionOrNull()?.javaClass?.simpleName})")
                    }

                    isActive = false

                    val message = when {
                        e is SecurityException ->
                            "Android VPN permission is required."

                        else -> {
                            val chain = if (root === e) "" else " <- ${root.javaClass.simpleName}"
                            val reason = ConfigSanitizer.redact(root.message).take(60)
                            "WireGuard startup failed [$stage] ${e.javaClass.simpleName}$chain" +
                                (if (reason.isNotBlank()) ": $reason" else "") +
                                ". Check the profile and try again."
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback(message, false)
                    }
                }
            }
        }
    }

    private fun describe(e: Throwable, root: Throwable): String {
        val top = "${e.javaClass.name}: ${ConfigSanitizer.redact(e.message)}"
        return if (root === e) top else "$top | root=${root.javaClass.name}: ${ConfigSanitizer.redact(root.message)}"
    }

    private fun wireGuardDnsIp(value: String): String? {
        val clean = value.trim()
        val host = if (clean.startsWith("[")) clean.substringAfter("[").substringBefore("]")
            else if (clean.count { it == ':' } == 1 && clean.substringAfter(":").toIntOrNull() != null) clean.substringBefore(":") else clean
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) && host.split('.').all { it.toIntOrNull() in 0..255 }
        val ipv6 = host.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(host)
        return host.takeIf { ipv4 || ipv6 }
    }

    private fun applyProfileDns(raw: String, dns: List<String>): String {
        val line = "DNS = ${dns.joinToString(", ")}"
        val pattern = Regex("(?im)^\\s*DNS\\s*=.*$")
        if (pattern.containsMatchIn(raw)) return pattern.replaceFirst(raw, line)
        val header = Regex("(?im)^\\s*\\[Interface]\\s*$")
        return if (header.containsMatchIn(raw)) header.replaceFirst(raw, "[Interface]\n$line") else raw
    }

    private fun buildConfig(
        raw: String,
        amnezia: Boolean
    ): Config {

        require(raw.isNotBlank()) {
            "Configuration is empty"
        }

        if (!amnezia) {
            return Config.parseQuickString(raw).also {
                it.validate()
            }
        }

        val clean = ConfigSanitizer
            .sanitizeAmnezia(raw)
            .text

        return try {
            AmneziaConfigNormalizer
                .ensureAmneziaCompatibility(
                    Config.parseQuickString(clean)
                )
                .also {
                    it.validate()
                }

        } catch (e: Throwable) {

            if (
                e.message.orEmpty().contains(
                    "INVALID_SIGNATURE_FORMAT",
                    ignoreCase = true
                )
            ) {
                Config.parseQuickString(clean).also {
                    it.validate()
                }
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
                    backend?.stop(TUNNEL_ID)?.getOrThrow()

                    isActive = false

                    withContext(Dispatchers.Main) {
                        callback("VPN disconnected.", false)
                    }

                } catch (_: Throwable) {

                    val status = backend?.status?.first()
                    val stillActive =
                        status?.activeTunnels?.containsKey(TUNNEL_ID) == true

                    isActive = stillActive

                    withContext(Dispatchers.Main) {
                        callback(
                            "Disconnect could not be completed. Please try again.",
                            stillActive
                        )
                    }
                }
            }
        }
    }
}
