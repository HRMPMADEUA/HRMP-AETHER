package com.tirexnet.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/** Tests DNS reachability on the active Android network. It never claims that a VPN tunnel was tested. */
object DnsResolverScanner {
    data class Result(
        val resolver: String,
        val udpReachable: Boolean,
        val tcpReachable: Boolean,
        val dotReachable: Boolean,
        val latencyMs: Long?,
        val packetLossPercent: Int,
        val responseValid: Boolean,
        val ednsSupported: Boolean,
        val nxdomainHijackSuspected: Boolean?,
        val dnsReachable: Boolean,
        val detail: String
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()

    fun scan(context: Context, resolvers: List<String>, callback: (List<Result>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = cm.activeNetwork
            val direct = resolvers.mapNotNull { parseTarget(it) }.distinctBy { "${it.first}:${it.second}" }
                .map { (host, port) -> testResolver(network, host, port) }
            val doh = resolvers.map { it.trim() }.filter { it.startsWith("https://", true) }
                .distinct().mapNotNull { endpoint -> testDoh(network, endpoint) }
            android.os.Handler(app.mainLooper).post { callback(direct + doh) }
        }
    }

    private fun parseTarget(value: String): Pair<String, Int>? {
        val clean = value.trim().removePrefix("udp://").removePrefix("tcp://")
        if (clean.isBlank() || clean.contains('/') || clean.contains('@')) return null
        val host: String
        val port: Int
        if (clean.startsWith("[")) {
            val end = clean.indexOf(']')
            if (end <= 1) return null
            host = clean.substring(1, end)
            port = clean.substring(end + 1).removePrefix(":").toIntOrNull() ?: 53
        } else {
            val pieces = clean.split(':')
            host = pieces.first()
            port = if (pieces.size > 1) pieces.last().toIntOrNull() ?: return null else 53
            if (pieces.size > 2) return null // IPv6 must use [address]:port notation.
        }
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    private fun testDoh(network: Network?, endpoint: String): Result? {
        if (network == null) return Result(endpoint, false, false, false, null, 100, false, false, null, false, "No active network")
        val start = System.nanoTime()
        val query = makeQuery("example.com", random.nextInt(65536), true)
        val response = runCatching {
            val url = URL(endpoint)
            require(url.protocol.equals("https", true) && !url.host.isNullOrBlank())
            val connection = network.openConnection(url) as HttpsURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            require(connection.responseCode in 200..299)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (output.size() < 4096) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 4096 - output.size()))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val valid = response != null && validResponse(query, response)
        if (!valid) return Result(endpoint, false, false, false, null, 100, false, false, null, false, "DoH did not return a valid DNS response")
        return Result(endpoint, false, false, false, elapsed, 0, true, hasOptRecord(response!!), null, true, "DNS over HTTPS Reachable")
    }

    private fun testResolver(network: Network?, host: String, port: Int): Result {
        if (network == null) return Result("$host:$port", false, false, false, null, 100, false, false, null, false, "No active network")
        var udpOk = 0; var tcpOk = 0; var valid = false; var edns = false
        val times = mutableListOf<Long>()
        repeat(2) {
            val query = makeQuery("example.com", random.nextInt(65536), true)
            val start = System.nanoTime()
            val response = udpQuery(network, host, port, query)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (response != null && validResponse(query, response)) {
                udpOk++; valid = true; edns = hasOptRecord(response); times += elapsed
            }
            val tcp = tcpQuery(network, host, port, query)
            if (tcp != null && validResponse(query, tcp)) tcpOk++
        }
        val nxQuery = makeQuery("tirexnet-${random.nextInt(1_000_000)}.invalid", random.nextInt(65536), true)
        val nxResponse = udpQuery(network, host, port, nxQuery)
        val nxHijack = nxResponse?.takeIf { validResponse(nxQuery, it) }?.let { response ->
            val rcode = response[3].toInt() and 0x0f
            val answers = (((response[6].toInt() and 0xff) shl 8) or (response[7].toInt() and 0xff))
            when { rcode == 3 -> false; answers > 0 -> true; else -> null }
        }
        val loss = (((2 - udpOk) * 100.0) / 2.0).roundToInt()
        val reachable = udpOk > 0 && valid
        val detail = when {
            reachable && tcpOk > 0 -> "DNS Reachable • UDP/TCP"
            reachable -> "DNS Reachable • UDP only"
            else -> "No valid DNS response"
        }
        val displayResolver = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return Result(displayResolver, udpOk > 0, tcpOk > 0, false,
            times.takeIf { it.isNotEmpty() }?.average()?.toLong(), loss, valid, edns, nxHijack,
            reachable, detail)
    }

    private fun udpQuery(network: Network, host: String, port: Int, query: ByteArray): ByteArray? = runCatching {
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = 1800
            socket.connect(InetSocketAddress(host, port))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        }
    }.getOrNull()

    private fun tcpQuery(network: Network, host: String, port: Int, query: ByteArray): ByteArray? = runCatching {
        val socket = network.socketFactory.createSocket() as Socket
        socket.use {
            it.soTimeout = 2200
            it.connect(InetSocketAddress(host, port), 1800)
            val out = DataOutputStream(it.getOutputStream())
            out.writeShort(query.size); out.write(query); out.flush()
            val input = DataInputStream(it.getInputStream())
            val size = input.readUnsignedShort()
            require(size in 12..4096)
            val response = ByteArray(size)
            input.readFully(response)
            response
        }
    }.getOrNull()

    /** Optional explicit DoT probe, kept separate so a failed DoT check does not invalidate UDP DNS. */
    fun testDot(context: Context, resolverHost: String, callback: (Boolean) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val network = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork
            val tlsHost = when (resolverHost) {
                "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
                "8.8.8.8", "8.8.4.4" -> "dns.google"
                "9.9.9.9", "149.112.112.112" -> "dns.quad9.net"
                else -> resolverHost.takeUnless { it.matches(Regex("^[0-9.]+$") ) || it.contains(':') }
            }
            val ok = if (network == null || tlsHost.isNullOrBlank()) false else runCatching {
                val address = network.getAllByName(tlsHost).firstOrNull() ?: return@runCatching false
                val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
                socket.use {
                    network.bindSocket(it)
                    it.soTimeout = 2500
                    it.connect(InetSocketAddress(address, 853), 2000)
                    val parameters = it.sslParameters
                    parameters.serverNames = listOf(SNIHostName(tlsHost))
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    it.sslParameters = parameters
                    it.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(tlsHost, it.session)) return@runCatching false
                    val q = makeQuery("example.com", random.nextInt(65536), true)
                    val out = DataOutputStream(it.outputStream)
                    out.writeShort(q.size); out.write(q); out.flush()
                    val input = DataInputStream(it.inputStream)
                    val n = input.readUnsignedShort()
                    if (n !in 12..4096) return@runCatching false
                    val response = ByteArray(n)
                    input.readFully(response)
                    validResponse(q, response)
                }
            }.getOrDefault(false)
            android.os.Handler(app.mainLooper).post { callback(ok) }
        }
    }

    private fun makeQuery(domain: String, id: Int, edns: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add(((v ushr 8) and 0xff).toByte()); out.add((v and 0xff).toByte()) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(if (edns) 1 else 0)
        domain.split('.').forEach { label ->
            val b = label.toByteArray(Charsets.US_ASCII)
            out.add(b.size.toByte())
            for (byte in b) out.add(byte)
        }
        out.add(0); u16(1); u16(1)
        if (edns) { out.add(0); u16(41); u16(1232); out.add(0); out.add(0); u16(0); u16(0) }
        return out.toByteArray()
    }

    private fun validResponse(query: ByteArray, response: ByteArray): Boolean =
        response.size >= 12 && query.size >= 2 && response[0] == query[0] && response[1] == query[1] &&
            (response[2].toInt() and 0x80) != 0

    private fun hasOptRecord(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val ar = ((response[10].toInt() and 0xff) shl 8) or (response[11].toInt() and 0xff)
        return ar > 0 // OPT is in additional section; parser intentionally avoids trusting malformed offsets.
    }
}
