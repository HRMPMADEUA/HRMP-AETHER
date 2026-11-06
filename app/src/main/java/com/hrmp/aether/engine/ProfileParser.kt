package com.tirexnet.app.engine

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Metadata extracted from a raw share link / config.
 *
 * [host] and [port] are kept ONLY for the engines and the latency probe.
 * They must never be rendered in the UI.
 */
data class ProfileMeta(
    val name: String,
    val host: String,
    val port: Int,
    val engine: ConfigEngine,
    /** OpenVPN only: true when the profile uses TCP (so the latency probe can use a TCP connect). */
    val tcp: Boolean = false
)

/**
 * Root-cause fix for "name shows the hostname":
 * the old code read `Uri.fragment` only, fell back to the URL scheme, and then
 * `serverDisplayName()` replaced that "generic" scheme with the hostname.
 * Here the real name is read per format, and a clean "Server N" is the only fallback.
 */
object ProfileParser {

    fun parse(rawValue: String): ProfileMeta {
        val raw = rawValue.trim()
        val engine = ConfigDetector.detect(raw)
        var name = ""
        var host = ""
        var port = 0
        var tcp = false
        val lower = raw.lowercase(Locale.US)
        try {
            when {
                engine == ConfigEngine.MHRV -> {
                    val profile = EngineProfiles.decodeMhrv(raw)
                    name = profile?.name.orEmpty().ifBlank { "MHRV" }
                    host = profile?.host.orEmpty()
                    port = 443
                }
                engine == ConfigEngine.DNS_TUNNEL -> {
                    val profile = EngineProfiles.decodeStormDns(raw)
                    name = profile?.name.orEmpty().ifBlank { "DNS Tunnel" }
                    host = profile?.host.orEmpty()
                    port = 53
                }
                engine == ConfigEngine.MASTERDNS -> {
                    val profile = MasterDnsProfiles.decode(raw)
                    name = profile?.name.orEmpty().ifBlank { "MasterDNS" }
                    host = profile?.host.orEmpty()
                    port = 53
                }
                engine == ConfigEngine.DNS_CHANGER -> {
                    name = DnsChangerProfiles.decode(raw)?.name.orEmpty().ifBlank { "DNS Profile" }
                    host = ""
                    port = 0
                }
                engine == ConfigEngine.SNI_SPOOF -> {
                    val profile = EngineProfiles.decodeSniSpoof(raw)
                    name = profile?.name.orEmpty().ifBlank { "Patterniha SNI" }
                    host = profile?.connectIp.orEmpty()
                    port = profile?.connectPort ?: 443
                }
                engine == ConfigEngine.SLIPNET -> {
                    val profile = EngineProfiles.decodeSlipNet(raw)
                    name = profile?.let { if (it.protocolName == "SlipNet profile") it.name else "${it.name} • ${it.protocolName}" }.orEmpty().ifBlank { "SlipNet profile" }
                    // SlipNet profiles may contain DNS resolver lists or a protocol chain;
                    // they do not necessarily have one TCP endpoint to ping from TIREXNET.
                    host = ""
                    port = 0
                }
                lower.startsWith("vmess://") -> {
                    val json = decodeBase64(raw.substringAfter("://").substringBefore('#'))
                    if (json != null && json.trim().startsWith("{")) {
                        name = jsonString(json, "ps")
                        host = jsonString(json, "add")
                        port = jsonString(json, "port").toIntOrNull() ?: 0
                    }
                    if (name.isBlank()) name = fragmentName(raw)
                }
                lower.startsWith("ss://") -> {
                    name = fragmentName(raw)
                    val hp = shadowsocksEndpoint(raw)
                    host = hp.first; port = hp.second
                }
                lower.startsWith("vless://") || lower.startsWith("trojan://") -> {
                    name = fragmentName(raw)
                    val hp = authorityEndpoint(raw)
                    host = hp.first; port = hp.second
                }
                raw.startsWith("{") -> {
                    name = jsonString(raw, "remarks").ifBlank { jsonString(raw, "ps") }
                    // For locally generated Xray variants, read the primary outbound endpoint.
                    val root = runCatching { org.json.JSONObject(raw) }.getOrNull()
                    val outs = root?.optJSONArray("outbounds")
                    if (outs != null) {
                        for (i in 0 until outs.length()) {
                            val out = outs.optJSONObject(i) ?: continue
                            if (out.optString("tag").lowercase(Locale.US) in setOf("direct", "block") || out.optString("protocol").lowercase(Locale.US) in setOf("freedom", "blackhole")) continue
                            val settings = out.optJSONObject("settings") ?: continue
                            val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                            val server = vnext ?: settings.optJSONArray("servers")?.optJSONObject(0)
                            if (server != null) {
                                host = server.optString("address")
                                port = server.optInt("port", 0)
                                break
                            }
                        }
                    }
                    if (host.isBlank()) host = jsonString(raw, "address")
                    if (port == 0) port = jsonString(raw, "port").toIntOrNull() ?: 0
                }
                engine == ConfigEngine.OPENVPN -> {
                    name = openVpnName(raw)
                    val m = Regex("(?im)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?(?:\\s+(\\S+))?").find(raw)
                    host = m?.groupValues?.get(1).orEmpty()
                    port = m?.groupValues?.get(2)?.toIntOrNull() ?: Regex("(?im)^\\s*port\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 1194
                    val proto = (m?.groupValues?.get(3).orEmpty().ifEmpty { Regex("(?im)^\\s*proto\\s+(\\S+)").find(raw)?.groupValues?.get(1).orEmpty() }).lowercase(Locale.US)
                    tcp = proto.startsWith("tcp")
                }
                engine == ConfigEngine.WIREGUARD || engine == ConfigEngine.AMNEZIAWG -> {
                    name = wireGuardName(raw)
                    val ep = Regex("(?im)^\\s*Endpoint\\s*=\\s*(\\S+)").find(raw)?.groupValues?.get(1).orEmpty()
                    val hp = splitHostPort(ep)
                    host = hp.first; port = hp.second
                }
            }
        } catch (_: Throwable) {
            // Malformed input: fall through with whatever was parsed so far.
        }
        return ProfileMeta(cleanName(name), host.trim(), port, engine, tcp)
    }

    /** Display name that never falls back to a hostname, IP, port, UUID or scheme. */
    fun displayName(candidate: String, fallbackIndex: Int): String {
        val v = cleanName(candidate)
        return if (v.isBlank() || looksTechnical(v)) "Server $fallbackIndex" else v
    }

    /** First candidate that is a real name; otherwise "Server N". Never a hostname/IP/UUID/scheme. */
    fun bestName(candidates: List<String>, fallbackIndex: Int): String {
        for (c in candidates) {
            val v = cleanName(c)
            if (v.isNotBlank() && !looksTechnical(v) && !isPlaceholder(v)) return v
        }
        return "Server $fallbackIndex"
    }

    /** Generic auto-numbered titles such as "Profile 3" or "Server 2" are not real names. */
    fun isPlaceholder(v: String): Boolean =
        Regex("(?i)^(profile|server|config|configuration|node|پروفایل|سرور|کانفیگ)\\s*[#-]?\\s*\\d*$").matches(v.trim())

    /** ISO country code of the first flag emoji (two regional-indicator symbols) in [name], or "". */
    fun flagCountryOf(name: String): String {
        var i = 0
        while (i < name.length) {
            val a = name.codePointAt(i)
            val n = Character.charCount(a)
            if (a in 0x1F1E6..0x1F1FF && i + n < name.length) {
                val b = name.codePointAt(i + n)
                if (b in 0x1F1E6..0x1F1FF) {
                    return String(charArrayOf(('A' + (a - 0x1F1E6)), ('A' + (b - 0x1F1E6))))
                }
            }
            i += n
        }
        return ""
    }

    /** True for values like "vless", "dd3.example.ir", "1.2.3.4:443", UUIDs, "Profile 3". */
    fun looksTechnical(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        if (Regex("(?i)^(profile|server|config)\\s*\\d*$").matches(v)) return false // our own fallbacks are fine
        if (Regex("(?i)^(vless|vmess|trojan|shadowsocks|wireguard|amneziawg|ss|ssr|xray|config)$").matches(v)) return true
        if (Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(v)) return true
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$").matches(v)) return true
        if (Regex("^\\[?[0-9a-fA-F:]+]?(:\\d+)?$").matches(v) && v.count { it == ':' } >= 2) return true
        // bare hostname: no spaces, has a dot, ascii letters/digits/hyphens only
        if (Regex("(?i)^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?$").matches(v)) return true
        return false
    }

    private fun cleanName(n: String): String =
        n.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim().take(80)

    // ---------------------------------------------------------------- helpers

    /** Name from `#fragment`, URL-decoded; '+' is kept literally, invalid escapes are tolerated. */
    fun fragmentName(raw: String): String {
        val i = raw.indexOf('#')
        if (i < 0 || i == raw.length - 1) return ""
        return percentDecode(raw.substring(i + 1))
    }

    fun percentDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Throwable) {
        s // malformed % sequence: keep the text as typed instead of losing the name
    }

    /** OpenVPN has no name field: use `setenv FRIENDLY_NAME`, a "# Name = X" comment, or the first plain comment. */
    private fun openVpnName(raw: String): String {
        Regex("(?im)^\\s*setenv\\s+FRIENDLY_NAME\\s+\"?([^\"\\r\\n]+)\"?").find(raw)?.let { return it.groupValues[1].trim() }
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (!t.startsWith("#") && !t.startsWith(";")) break
            val body = t.trimStart('#', ';', ' ').trim()
            val m = Regex("(?i)^(name|title|remarks?|profile)\\s*[=:]\\s*(.+)$").find(body)
            if (m != null) return m.groupValues[2].trim()
        }
        return ""
    }

    private fun wireGuardName(raw: String): String {
        // Common conventions: "# Name = X", "#name: X", "# X" as the first comment line.
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) break
            if (!t.startsWith("#") && !t.startsWith(";")) continue
            val body = t.trimStart('#', ';', ' ').trim()
            val m = Regex("(?i)^(name|title|remarks?|profile)\\s*[=:]\\s*(.+)$").find(body)
            if (m != null) return m.groupValues[2].trim()
            if (body.isNotEmpty() && !body.contains('=')) return body
        }
        return ""
    }

    private fun authorityEndpoint(raw: String): Pair<String, Int> {
        var rest = raw.substringAfter("://")
        rest = rest.substringBefore('#').substringBefore('?').substringBefore('/')
        val hostPort = rest.substringAfterLast('@')
        return splitHostPort(hostPort)
    }

    private fun shadowsocksEndpoint(raw: String): Pair<String, Int> {
        val body = raw.substringAfter("://").substringBefore('#').substringBefore('?')
        if (body.contains('@')) return splitHostPort(body.substringAfterLast('@').substringBefore('/'))
        // legacy: ss://BASE64(method:password@host:port)
        val decoded = decodeBase64(body) ?: return "" to 0
        return splitHostPort(decoded.substringAfterLast('@'))
    }

    fun splitHostPort(value: String): Pair<String, Int> {
        val v = value.trim()
        if (v.isEmpty()) return "" to 0
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 0) return "" to 0
            val host = v.substring(1, end)
            val port = v.substring(end + 1).removePrefix(":").toIntOrNull() ?: 0
            return host to port
        }
        val idx = v.lastIndexOf(':')
        if (idx < 0 || v.count { it == ':' } > 1) return v to 0
        return v.substring(0, idx) to (v.substring(idx + 1).toIntOrNull() ?: 0)
    }

    private fun decodeBase64(input: String): String? {
        val s = input.trim().replace('-', '+').replace('_', '/')
        if (s.isEmpty()) return null
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** Minimal top-level / first-match string extraction, avoids org.json so it stays pure Kotlin. */
    private fun jsonString(json: String, key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+)").find(json) ?: return ""
        val quoted = m.groupValues[2]
        val v = if (m.groupValues[1].startsWith("\"")) unescape(quoted) else m.groupValues[1]
        return v
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        if (i + 6 <= s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6; continue }
                        }
                        sb.append('u')
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
