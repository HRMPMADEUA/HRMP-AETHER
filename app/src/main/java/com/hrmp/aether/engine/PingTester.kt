package com.tirexnet.app.engine

import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class PingKind { OK, TIMEOUT, FAILED, UNSUPPORTED }

data class PingResult(
    val kind: PingKind,
    val ms: Int = -1,
    val method: String = "",
    val at: Long = SystemClock.elapsedRealtime()
)

/**
 * Real, off-main-thread latency measurement. Nothing here is simulated.
 *
 * Methods
 *  - Xray (VLESS/VMess/Trojan/Shadowsocks): TCP connect time to the server endpoint
 *    (best of up to 2 attempts). This proves the endpoint is reachable and measures one
 *    round trip to it. It does NOT prove the proxy handshake or the proxy path work, and it is
 *    not in-game latency.
 *  - WireGuard / AmneziaWG (UDP, no TCP listener): system `ping` (ICMP echo). Many servers drop
 *    ICMP, so "Timeout" can mean "ICMP blocked" rather than "down".
 *
 * While a VPN is connected the probe goes through whatever route the OS gives this app. The Xray
 * service excludes this app from its own tunnel, so the Xray number is a direct measurement.
 *
 * Battery/data: one small probe per user action, results cached for [TTL_MS], at most one in-flight
 * probe per profile, max 3 concurrent probes.
 */
object PingTester {
    private const val TTL_MS = 60_000L
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val RESOLVE_TIMEOUT_MS = 3_000L

    private val pool = Executors.newFixedThreadPool(3)
    private val resolver = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, PingResult>()
    private val waiting = HashMap<String, MutableList<(PingResult) -> Unit>>()

    /**
     * Supplies the phone's own internet connection (Wi-Fi/cellular, never the VPN), so a server can be
     * tested without being connected and while the VPN is on. Set once by the Activity.
     */
    @Volatile var networkProvider: (() -> Network?)? = null

    /** Returned when the phone has no usable internet connection at all. */
    const val METHOD_OFFLINE = "offline"

    fun cached(key: String): PingResult? = cache[key]

    fun isRunning(key: String): Boolean = synchronized(waiting) { waiting.containsKey(key) }

    fun isFresh(r: PingResult?): Boolean = r != null && SystemClock.elapsedRealtime() - r.at < TTL_MS

    /** [onResult] is always invoked on the main thread. A fresh cached result is reused unless [force]. */
    fun test(key: String, meta: ProfileMeta, force: Boolean, onResult: (PingResult) -> Unit) {
        val hit = cache[key]
        if (!force && isFresh(hit)) {
            main.post { onResult(hit!!) }
            return
        }
        val network = try { networkProvider?.invoke() } catch (_: Throwable) { null }
        synchronized(waiting) {
            val list = waiting[key]
            if (list != null) { list.add(onResult); return } // duplicate request: join the running one
            waiting[key] = mutableListOf(onResult)
        }
        pool.execute {
            val result = try { measure(meta, network, networkProvider != null) } catch (_: Throwable) { PingResult(PingKind.FAILED, method = "error") }
            if (result.method == METHOD_OFFLINE) cache.remove(key) else cache[key] = result
            val callbacks = synchronized(waiting) { waiting.remove(key) ?: mutableListOf() }
            main.post { callbacks.forEach { it(result) } }
        }
    }

    private fun measure(meta: ProfileMeta, network: Network?, needNetwork: Boolean): PingResult {
        if (meta.host.isBlank()) return PingResult(PingKind.FAILED, method = "no endpoint")
        if (needNetwork && network == null) return PingResult(PingKind.FAILED, method = METHOD_OFFLINE)
        return when (meta.engine) {
            ConfigEngine.XRAY -> tcpConnect(meta, network)
            ConfigEngine.WIREGUARD, ConfigEngine.AMNEZIAWG -> icmp(meta.host)
            else -> PingResult(PingKind.UNSUPPORTED)
        }
    }

    private fun resolve(host: String, network: Network?): InetAddress? {
        val f = resolver.submit(Callable<InetAddress?> {
            try { if (network != null) network.getByName(host) else InetAddress.getByName(host) } catch (_: Throwable) { null }
        })
        return try { f.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { f.cancel(true); null } catch (_: Throwable) { null }
    }

    private fun tcpConnect(meta: ProfileMeta, network: Network?): PingResult {
        val port = if (meta.port in 1..65535) meta.port else 443
        val addr = resolve(meta.host, network) ?: return PingResult(PingKind.FAILED, method = "dns")
        var best = Long.MAX_VALUE
        var timedOut = false
        for (attempt in 0 until 2) {
            val s = Socket()
            try {
                network?.bindSocket(s) // leave through the phone's own connection, not through the VPN tunnel
                val t0 = SystemClock.elapsedRealtimeNanos()
                s.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
                val dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
                if (dt < best) best = dt
            } catch (_: java.net.SocketTimeoutException) {
                timedOut = true; break
            } catch (_: Throwable) {
                if (best == Long.MAX_VALUE) return PingResult(PingKind.FAILED, method = "tcp")
                break
            } finally {
                runCatching { s.close() }
            }
        }
        return when {
            best != Long.MAX_VALUE -> PingResult(PingKind.OK, best.toInt().coerceAtLeast(1), "tcp")
            timedOut -> PingResult(PingKind.TIMEOUT, method = "tcp")
            else -> PingResult(PingKind.FAILED, method = "tcp")
        }
    }

    private fun icmp(host: String): PingResult {
        // Never build a shell string: host goes in as a single argv element.
        if (!Regex("^[A-Za-z0-9._:-]+$").matches(host)) return PingResult(PingKind.FAILED, method = "icmp")
        return try {
            val p = ProcessBuilder("ping", "-c", "2", "-W", "3", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return PingResult(PingKind.TIMEOUT, method = "icmp") }
            val times = Regex("time[=<]([0-9.]+)\\s*ms").findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            if (times.isEmpty()) PingResult(PingKind.TIMEOUT, method = "icmp")
            else PingResult(PingKind.OK, Math.round(times.min()).toInt().coerceAtLeast(1), "icmp")
        } catch (_: Throwable) {
            PingResult(PingKind.UNSUPPORTED, method = "icmp")
        }
    }
}
