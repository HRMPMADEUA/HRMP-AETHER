package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Share-profile codec for the auxiliary upstream engines integrated by TIREXNET.
 * Secrets are kept inside the encoded profile and are never rendered in the server list.
 */
object EngineProfiles {
    private fun pack(prefix: String, json: JSONObject): String {
        val data = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$prefix$data"
    }

    private fun unpack(raw: String, prefix: String): JSONObject? = runCatching {
        require(raw.startsWith(prefix, true))
        val token = raw.substring(prefix.length).trim()
        require(token.isNotEmpty() && token.length < 160_000)
        val bytes = try {
            Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Throwable) {
            Base64.decode(token, Base64.DEFAULT)
        }
        JSONObject(String(bytes, StandardCharsets.UTF_8))
    }.getOrNull()


    /**
     * Validates official SlipNet share links without trying to reinterpret their
     * protocol payload. SlipNet owns the actual DNSTT/NoizDNS/VayDNS/Soar/
     * Slipstream/SSH/NaiveProxy/DoH/Tor implementations; TIREXNET hands the
     * original URI to the official SlipNet app so no fields are lost.
     */
    fun decodeSlipNet(raw: String): SlipNetShareProfile? {
        val value = raw.trim()
        val lower = value.lowercase()
        val scheme = when {
            lower.startsWith("slipnet-bundle-enc://") -> "slipnet-bundle-enc://"
            lower.startsWith("slipnet-enc://") -> "slipnet-enc://"
            lower.startsWith("slipnet://") -> "slipnet://"
            else -> return null
        }
        val payload = value.substring(scheme.length).substringBefore('#').trim()
        if (payload.isBlank() || payload.length > 350_000 || payload.any { it.isWhitespace() }) return null
        val bytes = runCatching {
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: runCatching {
            Base64.decode(payload, Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.size < 8) return null

        val bundle = scheme == "slipnet-bundle-enc://"
        val encrypted = scheme != "slipnet://"
        var profileName = when {
            bundle -> "SlipNet encrypted bundle"
            encrypted -> "Encrypted SlipNet profile"
            else -> "SlipNet profile"
        }
        if (!encrypted) {
            val decoded = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrDefault("")
            val fields = decoded.split('|')
            val version = fields.firstOrNull()?.toIntOrNull()
            // The official share codec uses integer versions (1..29+), not v1/v2 tags.
            // Every supported legacy/current shape contains at least fields 0..10.
            if (version == null || version < 1 || fields.size < 11) return null
            val candidate = fields.getOrNull(2)?.trim().orEmpty()
            if (candidate.isNotBlank()) profileName = candidate.take(80)
        }
        return SlipNetShareProfile(
            name = profileName,
            encrypted = encrypted,
            bundle = bundle,
            originalUri = value
        )
    }

    fun encodeMhrv(name: String, config: JSONObject): String =
        pack("mhrv-rs://", JSONObject().put("name", name.take(80)).put("config", config))

    fun decodeMhrv(raw: String): MhrvProfile? {
        val wrapper = unpack(raw, "mhrv-rs://") ?: return null
        val config = wrapper.optJSONObject("config") ?: return null
        val mode = config.optString("mode", "apps_script").lowercase()
        if (mode !in setOf("apps_script", "direct")) return null
        if (mode == "apps_script") {
            val ids = config.optJSONArray("script_ids")
            val one = config.optString("script_id", "")
            if ((ids == null || ids.length() == 0) && one.isBlank()) return null
            if (config.optString("auth_key").isBlank()) return null
        }
        config.put("listen_host", "127.0.0.1")
        config.put("listen_port", 8080)
        config.put("socks5_port", 1081)
        val host = config.optString("google_ip", "216.239.38.120").ifBlank { "216.239.38.120" }
        return MhrvProfile(
            name = wrapper.optString("name", "MHRV").ifBlank { "MHRV" }.take(80),
            configJson = config.toString(),
            host = host,
            socksPort = 1081
        )
    }

    fun encodeStormDns(name: String, clientConfig: String, resolvers: String): String =
        pack("stormdns://", JSONObject()
            .put("name", name.take(80))
            .put("client_config", clientConfig)
            .put("resolvers", resolvers))

    fun decodeStormDns(raw: String): StormDnsProfile? {
        val wrapper = unpack(raw, "stormdns://") ?: return null
        var config = wrapper.optString("client_config", "").trim()
        val resolvers = wrapper.optString("resolvers", "").trim()
        if (config.isBlank() || resolvers.isBlank()) return null
        if (!config.contains(Regex("(?im)^\\s*DOMAINS\\s*="))) return null
        if (!config.contains(Regex("(?im)^\\s*ENCRYPTION_KEY\\s*="))) return null
        if (!config.contains(Regex("(?im)^\\s*DATA_ENCRYPTION_METHOD\\s*="))) return null
        // Keep the profile's chosen local SOCKS port but never expose it to the LAN.
        val port = Regex("(?im)^\\s*LISTEN_PORT\\s*=\\s*(\\d+)").find(config)?.groupValues?.get(1)?.toIntOrNull() ?: 18000
        config = replaceTomlString(config, "LISTEN_IP", "127.0.0.1")
        config = replaceTomlString(config, "PROTOCOL_TYPE", "SOCKS5")
        config = replaceTomlString(config, "STARTUP_MODE", "resolvers")
        if (port !in 1024..65535) return null
        val resolverLines = resolvers.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (resolverLines.isEmpty() || resolverLines.size > 100) return null
        if (resolverLines.any { !isResolverEndpoint(it) }) return null
        return StormDnsProfile(
            name = wrapper.optString("name", "StormDNS").ifBlank { "StormDNS" }.take(80),
            clientConfig = config,
            resolvers = resolverLines.joinToString("\n"),
            host = resolverHost(resolverLines.first()),
            port = port
        )
    }


    fun encodeSniSpoof(name: String, baseProfile: String, connectIp: String, connectPort: Int, fakeSni: String, listenPort: Int = 40443): String =
        pack("snispoof://", JSONObject()
            .put("name", name.take(80))
            .put("base_profile", baseProfile.trim())
            .put("connect_ip", connectIp.trim())
            .put("connect_port", connectPort)
            .put("fake_sni", fakeSni.trim())
            .put("listen_port", listenPort))

    fun decodeSniSpoof(raw: String): SniSpoofProfile? {
        val obj = unpack(raw, "snispoof://") ?: return null
        val base = obj.optString("base_profile", "").trim()
        val ip = obj.optString("connect_ip", "").trim()
        val port = obj.optInt("connect_port", 443)
        val fakeSni = obj.optString("fake_sni", "").trim().lowercase()
        val listenPort = obj.optInt("listen_port", 40443)
        if (base.isBlank() || ConfigDetector.detect(base) != ConfigEngine.XRAY) return null
        if (!isIpv4(ip) || port !in 1..65535 || listenPort !in 1024..65535) return null
        if (!isHostname(fakeSni)) return null
        if (!supportsPatterniha(base)) return null
        return SniSpoofProfile(
            name = obj.optString("name", "Patterniha SNI").ifBlank { "Patterniha SNI" }.take(80),
            baseProfile = base,
            connectIp = ip,
            connectPort = port,
            fakeSni = fakeSni,
            listenPort = listenPort
        )
    }

    /** True only when the imported Xray profile has a real TLS/REALITY server outbound. */
    fun supportsPatterniha(baseProfile: String): Boolean {
        val config = runCatching { JSONObject(XrayConfigBuilder.build(baseProfile)) }.getOrNull() ?: return false
        val outbounds = config.optJSONArray("outbounds") ?: return false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val tag = outbound.optString("tag").lowercase()
            val protocol = outbound.optString("protocol").lowercase()
            if (tag in setOf("direct", "block", "freedom", "blackhole") || protocol in setOf("freedom", "blackhole")) continue
            val security = outbound.optJSONObject("streamSettings")?.optString("security", "none")?.lowercase()
            if (security == "tls" || security == "reality") return true
        }
        return false
    }

    internal fun isResolverEndpoint(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close <= 1) return false
            host = text.substring(1, close)
            val tail = text.substring(close + 1)
            if (tail.isNotEmpty() && (!tail.startsWith(":") || tail.length == 1)) return false
            portText = tail.removePrefix(":").takeIf { it.isNotEmpty() }
            if (!isIpv6Literal(host)) return false
        } else {
            val colonCount = text.count { it == ':' }
            if (colonCount >= 2) {
                host = text
                portText = null
                if (!isIpv6Literal(host)) return false
            } else {
                host = if (colonCount == 1) text.substringBefore(':') else text
                portText = if (colonCount == 1) text.substringAfter(':') else null
                if (!isIpv4(host)) return false
            }
        }
        val parsedPort = portText?.toIntOrNull()
        return portText == null || (parsedPort != null && parsedPort in 1..65535)
    }

    private fun isIpv6Literal(value: String): Boolean =
        value.contains(':') && runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)

    private fun resolverHost(value: String): String {
        val text = value.trim()
        if (text.startsWith("[")) return text.substringAfter('[').substringBefore(']')
        if (text.count { it == ':' } >= 2) return text
        return text.substringBefore(':')
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> val n = part.toIntOrNull(); n != null && n in 0..255 && (part == "0" || !part.startsWith('0')) }
    }

    private fun isHostname(value: String): Boolean {
        if (value.length !in 1..253 || value.startsWith('.') || value.endsWith('.')) return false
        return value.split('.').all { it.isNotEmpty() && it.length <= 63 && it.matches(Regex("(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")) }
    }

    private fun replaceTomlString(input: String, key: String, value: String): String {
        val line = Regex("(?im)^\\s*${Regex.escape(key)}\\s*=.*$")
        val replacement = "$key = \"$value\""
        return if (line.containsMatchIn(input)) line.replace(input, replacement) else "$input\n$replacement\n"
    }

    private fun replaceTomlInt(input: String, key: String, value: Int): String {
        val line = Regex("(?im)^\\s*${Regex.escape(key)}\\s*=.*$")
        val replacement = "$key = $value"
        return if (line.containsMatchIn(input)) line.replace(input, replacement) else "$input\n$replacement\n"
    }
}

data class MhrvProfile(val name: String, val configJson: String, val host: String, val socksPort: Int)
data class StormDnsProfile(val name: String, val clientConfig: String, val resolvers: String, val host: String, val port: Int)
data class SniSpoofProfile(val name: String, val baseProfile: String, val connectIp: String, val connectPort: Int, val fakeSni: String, val listenPort: Int)

data class SlipNetShareProfile(val name: String, val encrypted: Boolean, val bundle: Boolean, val originalUri: String)
