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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WireGuard / AmneziaWG engine (wgtunnel).
 *
 * IMPORTANT — do NOT call Context.stopService() on
 * com.wgtunnel.backend.service.VpnService. Android then runs onDestroy →
 * stopHevSocks5Bridge → TProxyStopService() JNI. If the hev native lib is
 * not loaded on that path, the main thread throws UnsatisfiedLinkError and
 * the whole process dies. Teardown must go only through TunnelBackend.stop().
 *
 * Reconnect strategy:
 * - stop(): backend.stop(tunnel) + drop the TunnelBackend reference
 * - start(): always create a fresh TunnelBackend; retry on "acquire service"
 *   with delays (no stopService)
 */
object WgTunnelEngine {

    private const val TUNNEL_ID = 1
    private const val HANDSHAKE_TIMEOUT_MS = 45_000L
    private const val POST_STOP_SETTLE_MS = 700L
    private const val PRE_START_SETTLE_MS = 300L
    private const val RETRY_SETTLE_MS = 1200L
    private const val MAX_START_ATTEMPTS = 3

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

    private fun isNativeLinkFailure(e: Throwable): Boolean {
        return generateSequence(e) { it.cause }.any {
            it is UnsatisfiedLinkError ||
                it.message.orEmpty().contains("No implementation found", ignoreCase = true) ||
                it.message.orEmpty().contains("TProxyStopService", ignoreCase = true)
        }
    }

    /** Official tunnel stop only. Never Context.stopService on VpnService. */
    private suspend fun stopTunnelQuietly() {
        val current = backend ?: return
        runCatching { current.stop(TUNNEL_ID).getOrThrow() }
            .onFailure { t ->
                lastError = "stop: " + describeFailure(t)
            }
    }

    private fun dropBackend() {
        backend = null
        isActive = false
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

    private suspend fun startOnce(
        app: Context,
        parsed: Config,
        amnezia: Boolean
    ): Pair<Boolean, String> {
        // Drop any previous backend instance so RuntimeManager is recreated.
        stopTunnelQuietly()
        dropBackend()
        delay(PRE_START_SETTLE_MS)

        val currentBackend = ensureBackend(app)
        isActive = false

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
            stopTunnelQuietly()
            isActive = false
            return false to "WireGuard did not receive a healthy handshake within 45 seconds."
        }

        return when (finalStatus.activeTunnels[TUNNEL_ID]?.transportState) {
            is Tunnel.State.Up.HandshakeFailure -> {
                stopTunnelQuietly()
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
                stopTunnelQuietly()
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
                            lastError = describeFailure(e)
                            stopTunnelQuietly()
                            dropBackend()
                            // Wait longer when the VPN service binding is still settling.
                            delay(
                                when {
                                    isAcquireServiceFailure(e) -> RETRY_SETTLE_MS
                                    isNativeLinkFailure(e) -> RETRY_SETTLE_MS
                                    else -> POST_STOP_SETTLE_MS
                                }
                            )
                            if (attempt == MAX_START_ATTEMPTS) throw e
                        }
                    }

                    val (ok, message) = result
                        ?: (false to "WireGuard startup failed. Check the profile and try again.")
                    withContext(Dispatchers.Main) { callback(message, ok) }
                } catch (e: Throwable) {
                    lastError = describeFailure(e)
                    runCatching { stopTunnelQuietly() }
                    dropBackend()

                    val root = generateSequence(e) { it.cause }.last()
                    val message = when {
                        e is SecurityException ->
                            "Android VPN permission is required."
                        isNativeLinkFailure(e) || e is UnsatisfiedLinkError || root is UnsatisfiedLinkError ->
                            "WireGuard native library error. Force-stop the app once, then try again."
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

    /**
     * Disconnect. Must never kill the process.
     * Only TunnelBackend.stop — never Context.stopService(VpnService).
     */
    fun stop(callback: (String, Boolean) -> Unit) {
        TirexNotifier.stopUpdates()

        scope.launch {
            operationMutex.withLock {
                try {
                    stopTunnelQuietly()
                    delay(POST_STOP_SETTLE_MS)
                } catch (t: Throwable) {
                    lastError = "stop: " + describeFailure(t)
                } finally {
                    dropBackend()
                    withContext(Dispatchers.Main) {
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
