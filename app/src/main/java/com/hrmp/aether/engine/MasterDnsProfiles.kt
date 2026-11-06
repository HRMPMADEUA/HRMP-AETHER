package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Validated, app-private profile format for the real MasterDnsVPN Go client. */
data class MasterDnsProfile(
    val name: String,
    val clientConfig: String,
    val resolvers: String,
    val listenPort: Int,
    /** First resolver host, used only for display/ping metadata. */
    val host: String = ""
)

object MasterDnsProfiles {
    private const val PREFIX = "masterdns://"

    fun encode(
        name: String,
        domains: String,
        encryptionMethod: Int,
        encryptionKey: String,
        resolvers: String,
        listenPort: Int,
        workers: Int,
        mtu: Int,
        logLevel: String = "WARN"
    ): String {
        val json = JSONObject()
            .put("name", name.trim().take(80))
            .put("domains", domains)
            .put("encryption_method", encryptionMethod)
            .put("encryption_key", encryptionKey)
            .put("resolvers", resolvers)
            .put("listen_port", listenPort)
            .put("workers", workers)
            .put("mtu", mtu)
            .put("log_level", logLevel)
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return PREFIX + encoded
    }

    fun decode(raw: String): MasterDnsProfile? = runCatching {
        val value = raw.trim()
        require(value.startsWith(PREFIX, ignoreCase = true))
        val token = value.substring(PREFIX.length).trim()
        require(token.isNotEmpty() && token.length <= 160_000)
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))

        val name = json.optString("name", "MasterDNS").trim().take(80).ifBlank { "MasterDNS" }
        val domains = splitLines(json.optString("domains", ""))
        require(domains.isNotEmpty() && domains.size <= 32)
        require(domains.all(::isTunnelDomain))

        val method = json.optInt("encryption_method", -1)
        require(method in 0..5)
        val key = json.optString("encryption_key", "").trim()
        require(key.length <= 1024 && !key.contains('\n') && (method == 0 || key.isNotBlank()))

        val resolvers = splitLines(json.optString("resolvers", ""))
        require(resolvers.isNotEmpty() && resolvers.size <= 100)
        require(resolvers.all(::isResolverLine))

        val listenPort = json.optInt("listen_port", 18000)
        require(listenPort in 1024..65535)
        val workers = json.optInt("workers", 4)
        require(workers in 1..32)
        val mtu = json.optInt("mtu", 1280)
        require(mtu in 576..9000)

        val domainArray = domains.joinToString(", ") { tomlString(it) }
        val logLevel = json.optString("log_level", "WARN").uppercase()
        require(logLevel in setOf("ERROR", "WARN", "INFO", "DEBUG"))

        val config = """
DOMAINS = [$domainArray]
DATA_ENCRYPTION_METHOD = $method
ENCRYPTION_KEY = ${tomlString(key)}
PROTOCOL_TYPE = "SOCKS5"
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = $listenPort
SOCKS5_AUTH = false
RX_TX_WORKERS = $workers
TUNNEL_PROCESS_WORKERS = $workers
MIN_UPLOAD_MTU = $mtu
MAX_UPLOAD_MTU = $mtu
MIN_DOWNLOAD_MTU = $mtu
MAX_DOWNLOAD_MTU = $mtu
LOG_LEVEL = "$logLevel"
""".trim()

        MasterDnsProfile(
            name = name,
            clientConfig = config,
            resolvers = resolvers.joinToString("\n"),
            listenPort = listenPort,
            host = resolverHost(resolvers.first())
        )
    }.getOrNull()

    private fun resolverHost(value: String): String {
        val t = value.trim()
        if (t.startsWith("[")) return t.substringAfter('[').substringBefore(']')
        if (t.count { it == ':' } >= 2) return t.substringBefore('/')
        return t.substringBefore('/').substringBefore(':')
    }

    private fun splitLines(raw: String): List<String> = raw
        .lineSequence()
        .flatMap { it.split(',', ';').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .distinct()
        .toList()

    private fun isTunnelDomain(value: String): Boolean {
        if (value.length !in 1..253 || value.contains('/') || value.contains(':')) return false
        if (value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
        return value.split('.').all { part ->
            part.isNotEmpty() && part.length <= 63 &&
                part.first() != '-' && part.last() != '-' &&
                part.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    /** MasterDnsVPN resolver files accept IP, IP:port and CIDR forms, not DNS hostnames. */
    private fun isResolverLine(value: String): Boolean {
        if (value.length !in 3..160 || value.any { it.isWhitespace() }) return false
        val candidate = value.removeSurrounding("[").removeSurrounding("]")
        if (candidate.count { it == ':' } > 1) {
            return candidate.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '/' }
        }
        val hostPart = candidate.substringBefore('/')
            .substringBeforeLast(':')
        val ipv4 = hostPart.split('.')
        if (ipv4.size != 4 || ipv4.any { it.isEmpty() || (it.toIntOrNull()?.let { n -> n in 0..255 } != true) }) return false
        val cidr = candidate.substringAfter('/', "").substringBefore(':')
        if (candidate.contains('/') && (cidr.toIntOrNull() ?: -1) !in 0..32) return false
        val port = candidate.substringAfterLast(':', "").takeIf { candidate.count { c -> c == ':' } == 1 }
        return port == null || (port.toIntOrNull() ?: -1) in 1..65535
    }

    private fun tomlString(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n") + "\""
}
