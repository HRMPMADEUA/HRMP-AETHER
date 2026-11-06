package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec

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
     * Slipstream/SSH/NaiveProxy/DoH/Tor implementations. TIREXNET preserves
     * the original URI as an imported profile until its native runtime is bundled.
     */
    fun decodeSlipNet(raw: String): SlipNetShareProfile? {
        val value = raw.trim().removeSurrounding("<", ">").removeSurrounding("\"", "\"").removeSurrounding("'", "'")
        val lower = value.lowercase()
        val scheme = when {
            lower.startsWith("slipnet-bundle-enc://") -> "slipnet-bundle-enc://"
            lower.startsWith("slipnet-enc://") -> "slipnet-enc://"
            lower.startsWith("slipnet://") -> "slipnet://"
            else -> return null
        }
        // Some QR/scanner apps wrap long Base64 values or turn '+' into a space.
        // The official exporter uses Base64.NO_WRAP (standard alphabet).
        val payload = value.substring(scheme.length).substringBefore('#')
            .trim()
            // Keep spaces as '+' because some messaging apps turn Base64 plus
            // signs into spaces. Only remove actual line-wrapping characters.
            .replace("\r", "").replace("\n", "").replace("\t", "").replace(' ', '+')
        if (payload.isBlank() || payload.length > 5_000_000) return null
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val bytes = runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (bytes.size < 8) return null

        val bundle = scheme == "slipnet-bundle-enc://"
        val encrypted = scheme != "slipnet://"
        var profileName = when {
            bundle -> "SlipNet encrypted bundle"
            encrypted -> "Encrypted SlipNet profile"
            else -> "SlipNet profile"
        }
        var protocol = ""
        var version: Int? = null
        var torBridgeLines = ""
        if (!encrypted) {
            val decoded = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrDefault("")
            val fields = decoded.split('|')
            version = fields.firstOrNull()?.toIntOrNull()
            // SlipNet currently exports v29; legacy v1-v28 use the same first 11 fields.
            if (version == null || version < 1 || fields.size < 11) return null
            protocol = fields.getOrNull(1)?.trim()?.lowercase().orEmpty()
            val knownProtocols = setOf(
                "ss", "slipstream", "slipstream_ssh", "dnstt", "dnstt_ssh",
                "sayedns", "sayedns_ssh", "noizdns", "noizdns_ssh", "ssh", "doh",
                "snowflake", "naive", "naive_ssh", "socks5", "vaydns", "vaydns_ssh",
                "vless", "soar", "soar_ssh"
            )
            if (protocol !in knownProtocols) return null
            val candidate = fields.getOrNull(2)?.trim().orEmpty()
            if (candidate.isNotBlank()) profileName = candidate.take(80)
            if (protocol == "snowflake") {
                val encodedBridges = fields.getOrNull(26).orEmpty()
                if (encodedBridges.isNotBlank()) {
                    torBridgeLines = runCatching {
                        String(Base64.decode(encodedBridges, Base64.DEFAULT), StandardCharsets.UTF_8)
                    }.getOrDefault("")
                }
            }
        }
        return SlipNetShareProfile(
            name = profileName,
            encrypted = encrypted,
            bundle = bundle,
            originalUri = value,
            protocol = protocol,
            version = version,
            torBridgeLines = torBridgeLines
        )
    }

    /**
     * Decrypts SlipNet's password-protected multi-profile export.
     * Wire format is version(1) + salt(16) + IV(12) + AES-GCM data;
     * PBKDF2-HMAC-SHA256 with 600,000 rounds derives the 256-bit key.
     * Single-profile slipnet-enc:// links use SlipNet's private native key
     * and intentionally cannot be decrypted by this codec.
     */
    fun decodeSlipNetBundle(raw: String, password: String): List<String>? = runCatching {
        require(password.isNotEmpty()) { "Bundle password is required." }
        val value = raw.trim().removeSurrounding("<", ">").removeSurrounding("\"", "\"")
        require(value.startsWith("slipnet-bundle-enc://", true))
        val payload = value.substringAfter("://").substringBefore('#')
            .replace("\r", "").replace("\n", "").replace("\t", "").replace(' ', '+')
        require(payload.isNotBlank() && payload.length <= 5_000_000)
        val token = payload + "=".repeat((4 - payload.length % 4) % 4)
        val data = decodeSlipNetBase64(token) ?: error("Invalid bundle Base64.")
        require(data.size >= 1 + 16 + 12 + 16 && data[0] == 0x01.toByte()) { "Unsupported or truncated bundle." }
        val salt = data.copyOfRange(1, 17)
        val iv = data.copyOfRange(17, 29)
        val cipherText = data.copyOfRange(29, data.size)
        val keySpec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        } finally {
            keySpec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        val profiles = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(profiles.isNotEmpty() && profiles.size <= 500) { "Bundle contains no valid profiles or too many profiles." }
        require(profiles.all { entry ->
            val profile = decodeSlipNet(entry)
            profile != null && !profile.encrypted && !profile.bundle
        }) { "Bundle contains an unsupported profile entry." }
        profiles
    }.getOrNull()

    private fun decodeSlipNetBase64(value: String): ByteArray? {
        val token = value.trim()
        if (token.isEmpty()) return null
        return runCatching { Base64.decode(token, Base64.NO_WRAP) }.getOrNull()
            ?: runCatching { Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            ?: runCatching { Base64.decode(token, Base64.DEFAULT) }.getOrNull()
    }

    /**
     * Applies a Tor bridge preset/bridge list to an unlocked v12+ SlipNet
     * share profile. The subscription profile is never mutated in-place;
     * callers should save the returned URI as a local derived profile.
     */
    fun updateSlipNetTorBridges(raw: String, bridges: String): String? = runCatching {
        val profile = decodeSlipNet(raw) ?: return null
        if (profile.encrypted || profile.bundle || profile.protocol != "snowflake" || (profile.version ?: 0) < 12) return null
        val value = raw.trim()
        val payload = value.substringAfter("://", "").substringBefore('#')
            .replace("\r", "").replace("\n", "").replace("\t", "").replace(' ', '+')
        val bytes = decodeSlipNetBase64(payload) ?: return null
        val fields = String(bytes, StandardCharsets.UTF_8).split('|').toMutableList()
        if (fields.size <= 26 || fields.firstOrNull()?.toIntOrNull() != profile.version) return null
        val clean = bridges.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(100).joinToString("\n")
        fields[26] = Base64.encodeToString(clean.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val updated = fields.joinToString("|")
        "slipnet://" + Base64.encodeToString(updated.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }.getOrNull()

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


    fun encodeCottenDns(name: String, clientConfig: String, resolvers: String): String =
        pack("cottendns://", JSONObject()
            .put("name", name.take(80))
            .put("client_config", clientConfig)
            .put("resolvers", resolvers))

    private val cottenTransports = setOf("auto", "udp", "tcp", "dot", "doh", "doh3", "doq")
    private val cottenModes = setOf("legacy", "cottenmax")

    fun decodeCottenDns(raw: String): CottenDnsProfile? {
        val wrapper = unpack(raw, "cottendns://") ?: return null
        var config = wrapper.optString("client_config", "").trim()
        val resolvers = wrapper.optString("resolvers", "").trim()
        if (config.isBlank() || resolvers.isBlank()) return null
        if (!config.contains(Regex("(?im)^\\s*DOMAINS\\s*="))) return null
        if (!config.contains(Regex("(?im)^\\s*ENCRYPTION_KEY\\s*="))) return null
        if (!config.contains(Regex("(?im)^\\s*DATA_ENCRYPTION_METHOD\\s*="))) return null
        // Validate the options that change runtime behaviour; unknown values are rejected, never passed on.
        val transport = Regex("(?im)^\\s*RESOLVER_TRANSPORT\\s*=\\s*\"([^\"]*)\"").find(config)?.groupValues?.get(1)?.lowercase()
        if (transport != null && transport !in cottenTransports) return null
        val mode = Regex("(?im)^\\s*TUNNEL_MODE\\s*=\\s*\"([^\"]*)\"").find(config)?.groupValues?.get(1)?.lowercase()
        if (mode != null && mode !in cottenModes) return null
        // DoH3/DoQ are CottenMax-only resolver transports.
        if ((transport == "doh3" || transport == "doq") && mode != "cottenmax") return null
        val port = Regex("(?im)^\\s*LISTEN_PORT\\s*=\\s*(\\d+)").find(config)?.groupValues?.get(1)?.toIntOrNull() ?: 18000
        if (port !in 1024..65535) return null
        // The local SOCKS5 listener is never exposed to the LAN and the scan always runs on start.
        config = replaceTomlString(config, "LISTEN_IP", "127.0.0.1")
        config = replaceTomlString(config, "PROTOCOL_TYPE", "SOCKS5")
        config = replaceTomlString(config, "STARTUP_MODE", "resolvers")
        val resolverLines = resolvers.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (resolverLines.isEmpty() || resolverLines.size > 100) return null
        if (resolverLines.any { !isResolverEndpoint(it) }) return null
        return CottenDnsProfile(
            name = wrapper.optString("name", "CottenDNS").ifBlank { "CottenDNS" }.take(80),
            clientConfig = config,
            resolvers = resolverLines.joinToString("\n"),
            host = resolverHost(resolverLines.first()),
            port = port,
            mode = mode ?: "legacy"
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

    /**
     * Creates the Xray-side configuration for a rootless Patterniha profile.
     * SNISPF provides the loopback TCP relay; this function redirects only the
     * selected TLS/REALITY outbound to that relay and preserves its credentials.
     */
    fun createRootlessSniProfile(
        baseProfile: String,
        profileName: String,
        connectIp: String,
        connectPort: Int,
        siteSni: String,
        localRelayPort: Int
    ): String? = runCatching {
        val host = connectIp.trim().removePrefix("[").removeSuffix("]")
        require(isIpv4(host) || isHostname(host) || isIpv6Literal(host)) { "Invalid upstream address." }
        val sni = siteSni.trim().let { value ->
            val candidate = if (value.contains("://")) runCatching { java.net.URI(value).host }.getOrNull().orEmpty()
                else value.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            candidate.trim().trimEnd('.').lowercase(Locale.US)
        }
        require(isHostname(sni)) { "Invalid SNI hostname." }
        require(connectPort in 1..65535)
        require(localRelayPort in 1024..65535)
        require(ConfigDetector.detect(baseProfile) == ConfigEngine.XRAY)
        val config = JSONObject(XrayConfigBuilder.build(baseProfile))
        val outbounds = config.optJSONArray("outbounds") ?: return null
        var changed = false
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val tag = outbound.optString("tag").lowercase(Locale.US)
            val protocol = outbound.optString("protocol").lowercase(Locale.US)
            if (tag in setOf("direct", "block", "freedom", "blackhole") || protocol in setOf("freedom", "blackhole")) continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            val security = stream.optString("security", "none").lowercase(Locale.US)
            if (security != "tls" && security != "reality") continue
            val settings = outbound.optJSONObject("settings") ?: continue
            val vnext = settings.optJSONArray("vnext")
            val server = if (vnext != null && vnext.length() > 0) vnext.optJSONObject(0)
                else settings.optJSONArray("servers")?.optJSONObject(0)
            if (server == null) continue
            // Xray must send its ClientHello to the local SNISPF relay. The
            // relay itself connects to connectIp:connectPort and performs the
            // rootless TLS fragmentation before forwarding upstream.
            server.put("address", "127.0.0.1").put("port", localRelayPort)
            val tlsKey = if (security == "reality") "realitySettings" else "tlsSettings"
            val tls = stream.optJSONObject(tlsKey) ?: JSONObject().also { stream.put(tlsKey, it) }
            tls.put("serverName", sni)
            changed = true
            break
        }
        require(changed) { "No TLS/REALITY outbound was found in this profile." }
        val cleanName = profileName.trim().ifBlank { "Patterniha SNI" }.take(80)
        config.put("remarks", cleanName).put("ps", cleanName)
        config.toString()
    }.getOrNull()

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
data class CottenDnsProfile(val name: String, val clientConfig: String, val resolvers: String, val host: String, val port: Int, val mode: String)
data class StormDnsProfile(val name: String, val clientConfig: String, val resolvers: String, val host: String, val port: Int)
data class SniSpoofProfile(val name: String, val baseProfile: String, val connectIp: String, val connectPort: Int, val fakeSni: String, val listenPort: Int)

data class SlipNetShareProfile(
    val name: String,
    val encrypted: Boolean,
    val bundle: Boolean,
    val originalUri: String,
    val protocol: String = "",
    val version: Int? = null,
    val torBridgeLines: String = ""
) {
    val protocolName: String
        get() = when (protocol.lowercase(Locale.US)) {
            "snowflake" -> "Tor"
            "ss", "slipstream" -> "Slipstream"
            "slipstream_ssh" -> "Slipstream + SSH"
            "dnstt" -> "DNSTT"
            "dnstt_ssh" -> "DNSTT + SSH"
            "sayedns", "noizdns" -> "NoizDNS"
            "sayedns_ssh", "noizdns_ssh" -> "NoizDNS + SSH"
            "vaydns" -> "VayDNS"
            "vaydns_ssh" -> "VayDNS + SSH"
            "soar" -> "Soar"
            "soar_ssh" -> "Soar + SSH"
            "ssh" -> "SSH"
            "naive" -> "NaiveProxy"
            "naive_ssh" -> "NaiveProxy + SSH"
            "vless" -> "VLESS"
            "doh" -> "DoH"
            "socks5" -> "SOCKS5"
            else -> "SlipNet profile"
        }
}
