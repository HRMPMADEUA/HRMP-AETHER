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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WireGuard and AmneziaWG engine for TIREXNET.
 *
 * Connection is considered successful only after the backend confirms
 * a healthy peer handshake.
 */
object WgTunnelEngine {

    private const val TUNNEL_ID = 1
    private const val HANDSHAKE_TIMEOUT_MS = 45_000L
    private const val PROBE_HOST = "connectivitycheck.gstatic.com"

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

                try {
                    val app = context.applicationContext

                    // Validate and parse before changing the running tunnel.
                    val parsed = buildConfig(raw, amnezia)

                    // Initialize the backend only once.
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
                        ?: throw IllegalStateException("WireGuard backend unavailable")

                    // Remove an old tunnel before loading another profile.
                    if (
                        currentBackend.status.value
                            .activeTunnels.containsKey(TUNNEL_ID)
                    ) {
                        currentBackend
                            .stop(TUNNEL_ID)
                            .getOrThrow()
                    }

                    isActive = false

                    val tunnel = object : Tunnel {

                        @Volatile
                        private var lastReportedState: Tunnel.State =
                            Tunnel.State.Down

                        override val id: Int = TUNNEL_ID

                        override val name: String = "TIREXNET"

                        override val isMetered: Boolean = false

                        override val scriptsEnabled: Boolean = false

                        // Prefer IPv6 endpoints when supported, with IPv4 fallback.
                        override val ipStrategy: Tunnel.IpStrategy =
                            Tunnel.IpStrategy.PreferIpv6(
                                recoveryEnabled = true
                            )

                        override val features: Set<Tunnel.Feature> =
                            emptySet()

                        override fun updateState(state: Tunnel.State) {
                            lastReportedState = state
                        }
                    }

                    // Start the actual VPN backend.
                    currentBackend
                        .start(
                            tunnel = tunnel,
                            mode = BackendMode.Vpn(parsed)
                        )
                        .getOrThrow()

                    // Generate initial DNS traffic so the peer can start
                    // the WireGuard handshake even before another app
                    // generates traffic.
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            InetAddress.getAllByName(PROBE_HOST)
                        }
                    }

                    // Do not mark the app connected just because start()
                    // returned. Wait for a real healthy handshake.
                    val healthy = withTimeoutOrNull(
                        HANDSHAKE_TIMEOUT_MS
                    ) {
                        currentBackend.status.first { status ->
                            status.activeTunnels[TUNNEL_ID]
                                ?.transportState is Tunnel.State.Up.Healthy
                        }

                        true
                    } ?: false

                    if (healthy) {
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
                        val finalState = currentBackend.status.value
                            .activeTunnels[TUNNEL_ID]
                            ?.transportState

                        // Do not leave a tunnel running when its handshake
                        // could not be confirmed.
                        runCatching {
                            currentBackend.stop(TUNNEL_ID).getOrThrow()
                        }

                        isActive = false

                        val message =
                            if (finalState is Tunnel.State.Up.HandshakeFailure) {
                                "WireGuard handshake failed. Check the endpoint, peer keys and server."
                            } else {
                                "WireGuard did not receive a healthy handshake within 45 seconds."
                            }

                        withContext(Dispatchers.Main) {
                            callback(message, false)
                        }
                    }

                } catch (e: Throwable) {

                    // Clean up a partially started tunnel.
                    if (currentBackend != null) {
                        runCatching {
                            currentBackend.stop(TUNNEL_ID).getOrThrow()
                        }
                    }

                    isActive = false

                    val message = when {
                        e.message.orEmpty().contains(
                            "INVALID_SIGNATURE_FORMAT",
                            ignoreCase = true
                        ) -> {
                            "AmneziaWG configuration contains an invalid signature format."
                        }

                        e is SecurityException -> {
                            "Android VPN permission is required."
                        }

                        else -> {
                            "WireGuard startup failed (${e.javaClass.simpleName}). Check the profile and try again."
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback(message, false)
                    }
                }
            }
        }
    }

    /**
     * Parse normal WireGuard configs without modifying them.
     *
     * AmneziaWG configs are normalized for compatibility.
     */
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
                    backend
                        ?.stop(TUNNEL_ID)
                        ?.getOrThrow()

                    isActive = false

                    withContext(Dispatchers.Main) {
                        callback("VPN disconnected.", false)
                    }

                } catch (e: Throwable) {

                    val stillActive = backend
                        ?.status
                        ?.value
                        ?.activeTunnels
                        ?.containsKey(TUNNEL_ID) == true

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
