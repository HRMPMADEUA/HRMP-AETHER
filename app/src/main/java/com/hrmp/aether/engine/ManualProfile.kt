package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ManualProtocol(val label: String, val group: String) {
    VLESS("VLESS", "Xray"), VMESS("VMess", "Xray"), TROJAN("Trojan", "Xray"), SHADOWSOCKS("Shadowsocks", "Xray"),
    WIREGUARD("WireGuard", "VPN"), AMNEZIAWG("AmneziaWG", "VPN"),
    SLIPNET_DNSTT("DNSTT", "SlipNet"), SLIPNET_DNSTT_SSH("DNSTT + SSH", "SlipNet"),
    SLIPNET_VAYDNS("VayDNS", "SlipNet"), SLIPNET_VAYDNS_SSH("VayDNS + SSH", "SlipNet"),
    SLIPNET_SOAR("Soar", "SlipNet"), SLIPNET_SOAR_SSH("Soar + SSH", "SlipNet"),
    MASTERDNS("MasterDNS", "DNS Tunnel"), STORMDNS("StormDNS", "DNS Tunnel"), COTTENDNS("CottenDNS", "DNS Tunnel"),
    MHRV("MHRV", "Other"), DNS_CHANGER("DNS Changer", "Other");

    val isSlipNet: Boolean get() = group == "SlipNet"

    companion object {
        /** Groups in display order. */
        val groups: List<String> get() = values().map { it.group }.distinct()
    }
}

enum class FieldKind { TEXT, NUMBER, SECRET, CHOICE, MULTILINE }

data class FieldSpec(
    val key: String,
    val label: String,
    val kind: FieldKind = FieldKind.TEXT,
    val required: Boolean = false,
    val hint: String = "",
    val choices: List<String> = emptyList(),
    val default: String = "",
    /** Only shown when this predicate over the current values is true (e.g. reality-only fields). */
    val showWhen: ((Map<String, String>) -> Boolean)? = null,
    /** Optional heading shown above the first field of a section. */
    val section: String = ""
)

sealed class ManualOutcome {
    data class Ok(val raw: String, val name: String) : ManualOutcome()
    /** [message] never contains the offending value, only what is wrong with it. */
    data class Invalid(val key: String, val message: String) : ManualOutcome()
}

/**
 * Builds the same raw formats the importers/engines already understand
 * (share links for Xray, wg-quick text for WireGuard/AmneziaWG), so nothing new is needed downstream.
 */
object ManualProfile {

    private val tlsOrReality: (Map<String, String>) -> Boolean = { it["security"] == "tls" || it["security"] == "reality" }

    fun fields(p: ManualProtocol): List<FieldSpec> {
        val name = FieldSpec("name", "Profile name", hint = "Shown in your server list")
        val address = FieldSpec("address", "Server address", required = true, hint = "domain or IP")
        val port = FieldSpec("port", "Port", FieldKind.NUMBER, required = true, hint = "443", default = "443")
        val transport = listOf(
            FieldSpec("network", "Network", FieldKind.CHOICE, choices = listOf("tcp", "ws", "grpc", "http"), default = "tcp"),
            FieldSpec("path", "Path / service name", hint = "/", showWhen = { it["network"] != "tcp" }),
            FieldSpec("hostHeader", "Host header", hint = "optional", showWhen = { it["network"] == "ws" || it["network"] == "http" })
        )
        val tls = listOf(
            FieldSpec("security", "Security", FieldKind.CHOICE, choices = listOf("none", "tls", "reality"), default = "tls"),
            FieldSpec("sni", "SNI / server name", hint = "optional", showWhen = tlsOrReality),
            FieldSpec("fp", "Fingerprint", FieldKind.CHOICE, choices = listOf("chrome", "firefox", "safari", "ios", "android", "random"), default = "chrome", showWhen = tlsOrReality),
            FieldSpec("alpn", "ALPN", hint = "h2,http/1.1 (optional)", showWhen = { it["security"] == "tls" }),
            FieldSpec("pbk", "Reality public key", FieldKind.SECRET, required = true, showWhen = { it["security"] == "reality" }),
            FieldSpec("sid", "Reality short ID", hint = "optional", showWhen = { it["security"] == "reality" })
        )
        return when (p) {
            ManualProtocol.VLESS -> listOf(name, address, port,
                FieldSpec("uuid", "UUID", FieldKind.SECRET, required = true, hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"),
                FieldSpec("flow", "Flow", FieldKind.CHOICE, choices = listOf("", "xtls-rprx-vision"), default = "")) + transport + tls
            ManualProtocol.VMESS -> listOf(name, address, port,
                FieldSpec("uuid", "UUID", FieldKind.SECRET, required = true),
                FieldSpec("cipher", "Encryption", FieldKind.CHOICE, choices = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none"), default = "auto")) + transport +
                listOf(FieldSpec("security", "TLS", FieldKind.CHOICE, choices = listOf("none", "tls"), default = "tls"),
                    FieldSpec("sni", "SNI / server name", hint = "optional", showWhen = { it["security"] == "tls" }))
            ManualProtocol.TROJAN -> listOf(name, address, port,
                FieldSpec("password", "Password", FieldKind.SECRET, required = true)) + transport +
                listOf(FieldSpec("security", "Security", FieldKind.CHOICE, choices = listOf("tls", "none"), default = "tls"),
                    FieldSpec("sni", "SNI / server name", hint = "optional", showWhen = { it["security"] == "tls" }),
                    FieldSpec("alpn", "ALPN", hint = "optional", showWhen = { it["security"] == "tls" }))
            ManualProtocol.SHADOWSOCKS -> listOf(name, address, port,
                FieldSpec("method", "Encryption method", FieldKind.CHOICE, required = true,
                    choices = listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"), default = "aes-256-gcm"),
                FieldSpec("password", "Password", FieldKind.SECRET, required = true))
            ManualProtocol.SLIPNET_DNSTT, ManualProtocol.SLIPNET_DNSTT_SSH,
            ManualProtocol.SLIPNET_VAYDNS, ManualProtocol.SLIPNET_VAYDNS_SSH,
            ManualProtocol.SLIPNET_SOAR, ManualProtocol.SLIPNET_SOAR_SSH ->
                SlipNetManualProfile.fields(p, name).map { it.copy(section = slipNetSection(it.key)) }
                    .sortedBy { SLIPNET_SECTION_ORDER.indexOf(it.section).let { i -> if (i < 0) 99 else i } }
            ManualProtocol.MASTERDNS -> listOf(
                name.copy(section = "Profile"),
                FieldSpec("domains", "Tunnel domains", FieldKind.MULTILINE, required = true, hint = "One domain per line; must match the MasterDNS server", section = "Server"),
                FieldSpec("encryption", "Data encryption", FieldKind.CHOICE, choices = listOf("0", "1", "2", "3", "4", "5"), default = "1"),
                FieldSpec("encryptionKey", "Shared encryption key", FieldKind.SECRET, required = true, showWhen = { it["encryption"] != "0" }),
                FieldSpec("resolvers", "DNS resolvers", FieldKind.MULTILINE, required = true, hint = "One IP, IP:port or CIDR per line", default = "1.1.1.1:53\n8.8.8.8:53", section = "DNS"),
                FieldSpec("listenPort", "Local SOCKS5 port", FieldKind.NUMBER, required = true, hint = "18000", default = "18000", section = "Performance"),
                FieldSpec("workers", "Workers", FieldKind.NUMBER, hint = "1-32", default = "4"),
                FieldSpec("mtu", "MTU", FieldKind.NUMBER, hint = "576-9000", default = "1280"),
                FieldSpec("logLevel", "Log level", FieldKind.CHOICE, choices = listOf("ERROR", "WARN", "INFO", "DEBUG"), default = "WARN")
            )
            ManualProtocol.MHRV -> listOf(
                name,
                FieldSpec("mode", "MHRV mode", FieldKind.CHOICE, choices = listOf("apps_script", "direct"), default = "apps_script"),
                FieldSpec("scriptUrl", "Google Apps Script deployment URL or ID", required = true, hint = "https://script.google.com/macros/s/.../exec", showWhen = { it["mode"] != "direct" }),
                FieldSpec("authKey", "MHRV authentication key", FieldKind.SECRET, required = true, showWhen = { it["mode"] != "direct" }),
                FieldSpec("googleIp", "Google edge IPv4", required = true, hint = "216.239.38.120", default = "216.239.38.120"),
                FieldSpec("frontDomain", "Front SNI domain", required = true, hint = "www.google.com", default = "www.google.com"),
                FieldSpec("sniHosts", "SNI rotation pool (optional)", hint = "www.google.com,mail.google.com")
            )
            ManualProtocol.STORMDNS -> listOf(
                name,
                FieldSpec("domains", "Tunnel domains", FieldKind.MULTILINE, required = true, hint = "One domain per line; must match the StormDNS server"),
                FieldSpec("encryption", "Data encryption", FieldKind.CHOICE, choices = listOf("0", "1", "2", "3", "4", "5"), default = "1"),
                FieldSpec("encryptionKey", "Shared encryption key", FieldKind.SECRET, required = true),
                FieldSpec("resolvers", "DNS resolvers", FieldKind.MULTILINE, required = true, hint = "One IP or IP:port per line", default = "1.1.1.1:53\n8.8.8.8:53"),
                FieldSpec("listenPort", "Local SOCKS5 port", FieldKind.NUMBER, required = true, hint = "18000", default = "18000")
            )
            ManualProtocol.COTTENDNS -> listOf(
                name,
                FieldSpec("domains", "Tunnel domains", FieldKind.MULTILINE, required = true, hint = "One domain per line; must match the CottenDNS server"),
                FieldSpec("encryption", "Data encryption (legacy mode)", FieldKind.CHOICE, choices = listOf("0", "1", "2", "3", "4", "5"), default = "3"),
                FieldSpec("encryptionKey", "Shared encryption key", FieldKind.SECRET, required = true),
                FieldSpec("resolvers", "DNS resolvers", FieldKind.MULTILINE, required = true, hint = "One IP or IP:port per line", default = "1.1.1.1:53\n8.8.8.8:53"),
                FieldSpec("tunnelMode", "Tunnel mode", FieldKind.CHOICE, choices = listOf("legacy", "cottenmax"), default = "legacy"),
                FieldSpec("resolverTransport", "Resolver transport", FieldKind.CHOICE, choices = listOf("auto", "udp", "tcp", "dot", "doh", "doh3", "doq"), default = "auto"),
                FieldSpec("preset", "Config preset", FieldKind.CHOICE, choices = listOf("speed", "survival", "tcp-survival", "low-bandwidth"), default = "speed"),
                FieldSpec("listenPort", "Local SOCKS5 port", FieldKind.NUMBER, required = true, hint = "18000", default = "18000")
            )
            ManualProtocol.DNS_CHANGER -> listOf(
                name,
                FieldSpec("ipv4Primary", "IPv4 Primary", hint = "1.1.1.1", default = "1.1.1.1"),
                FieldSpec("ipv4Secondary", "IPv4 Secondary", hint = "1.0.0.1", default = "1.0.0.1"),
                FieldSpec("ipv6Primary", "IPv6 Primary", hint = "2606:4700:4700::1111", default = "2606:4700:4700::1111"),
                FieldSpec("ipv6Secondary", "IPv6 Secondary", hint = "2606:4700:4700::1001", default = "2606:4700:4700::1001")
            )
            ManualProtocol.WIREGUARD, ManualProtocol.AMNEZIAWG -> {
                val base = listOf(name, address, port.copy(hint = "51820", default = "51820"),
                    FieldSpec("privateKey", "Interface private key", FieldKind.SECRET, required = true, hint = "base64, 44 characters"),
                    FieldSpec("localAddress", "Interface address", required = true, hint = "10.0.0.2/32"),
                    FieldSpec("dns", "DNS", hint = "1.1.1.1 (optional)"),
                    FieldSpec("mtu", "MTU", FieldKind.NUMBER, hint = "optional"),
                    FieldSpec("publicKey", "Peer public key", required = true, hint = "base64, 44 characters"),
                    FieldSpec("presharedKey", "Preshared key", FieldKind.SECRET, hint = "optional"),
                    FieldSpec("allowedIps", "Allowed IPs", hint = "0.0.0.0/0, ::/0", default = "0.0.0.0/0, ::/0"),
                    FieldSpec("keepalive", "Persistent keepalive", FieldKind.NUMBER, hint = "25 (optional)"))
                if (p == ManualProtocol.AMNEZIAWG) base + listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4")
                    .map { FieldSpec("awg$it", it, FieldKind.TEXT, hint = "optional") } else base
            }
        }
    }

    fun build(p: ManualProtocol, values: Map<String, String>): ManualOutcome {
        val v = values.mapValues { it.value.trim() }
        for (f in fields(p)) {
            if (f.showWhen != null && !f.showWhen.invoke(v)) continue
            val value = v[f.key].orEmpty()
            if (f.required && value.isEmpty()) return ManualOutcome.Invalid(f.key, "${f.label} is required.")
        }
        val name = v["name"].orEmpty()
        if (p.isSlipNet) return buildSlipNet(p, v, name)
        if (p == ManualProtocol.MASTERDNS) return buildMasterDns(v, name)
        if (p == ManualProtocol.MHRV) return buildMhrv(v, name)
        if (p == ManualProtocol.STORMDNS) return buildStormDns(v, name)
        if (p == ManualProtocol.COTTENDNS) return buildCottenDns(v, name)
        if (p == ManualProtocol.DNS_CHANGER) {
            val ipv4Primary = v["ipv4Primary"].orEmpty()
            val ipv4Secondary = v["ipv4Secondary"].orEmpty()
            val ipv6Primary = v["ipv6Primary"].orEmpty()
            val ipv6Secondary = v["ipv6Secondary"].orEmpty()
            if (ipv4Primary.isNotEmpty() && !DnsChangerProfiles.isValidIpv4(ipv4Primary)) return ManualOutcome.Invalid("ipv4Primary", "Enter a valid IPv4 address.")
            if (ipv4Secondary.isNotEmpty() && !DnsChangerProfiles.isValidIpv4(ipv4Secondary)) return ManualOutcome.Invalid("ipv4Secondary", "Enter a valid IPv4 address.")
            if (ipv6Primary.isNotEmpty() && !DnsChangerProfiles.isValidIpv6(ipv6Primary)) return ManualOutcome.Invalid("ipv6Primary", "Enter a valid IPv6 address.")
            if (ipv6Secondary.isNotEmpty() && !DnsChangerProfiles.isValidIpv6(ipv6Secondary)) return ManualOutcome.Invalid("ipv6Secondary", "Enter a valid IPv6 address.")
            if (listOf(ipv4Primary, ipv4Secondary, ipv6Primary, ipv6Secondary).none { it.isNotBlank() }) {
                return ManualOutcome.Invalid("ipv4Primary", "Enter at least one DNS server address.")
            }
            val profileName = name.ifBlank { "DNS Profile" }
            return ManualOutcome.Ok(DnsChangerProfiles.encode(profileName, ipv4Primary, ipv4Secondary, ipv6Primary, ipv6Secondary), profileName)
        }

        val address = v["address"].orEmpty()
        if (!isValidHost(address)) return ManualOutcome.Invalid("address", "Server address must be a domain name or an IP address.")
        val port = v["port"]?.toIntOrNull()
        if (port == null || port !in 1..65535) return ManualOutcome.Invalid("port", "Port must be a number from 1 to 65535.")

        return when (p) {
            ManualProtocol.VLESS -> {
                val uuid = v["uuid"].orEmpty()
                if (!UUID_RE.matches(uuid)) return ManualOutcome.Invalid("uuid", "UUID format is not valid.")
                val q = LinkedHashMap<String, String>()
                q["encryption"] = "none"
                streamQuery(q, v)
                if (v["flow"].orEmpty().isNotEmpty()) q["flow"] = v["flow"].orEmpty()
                if (v["security"] == "reality") {
                    val pbk = v["pbk"].orEmpty()
                    if (pbk.length < 20) return ManualOutcome.Invalid("pbk", "Reality public key looks too short.")
                    q["pbk"] = pbk
                    v["sid"]?.takeIf { it.isNotEmpty() }?.let { q["sid"] = it }
                }
                ManualOutcome.Ok("vless://$uuid@${hostForUri(address)}:$port?${queryString(q)}#${enc(name)}", name)
            }
            ManualProtocol.TROJAN -> {
                val q = LinkedHashMap<String, String>()
                streamQuery(q, v)
                ManualOutcome.Ok("trojan://${enc(v["password"].orEmpty())}@${hostForUri(address)}:$port?${queryString(q)}#${enc(name)}", name)
            }
            ManualProtocol.VMESS -> {
                val uuid = v["uuid"].orEmpty()
                if (!UUID_RE.matches(uuid)) return ManualOutcome.Invalid("uuid", "UUID format is not valid.")
                val net = v["network"] ?: "tcp"
                val json = JSONObject()
                    .put("v", "2").put("ps", name).put("add", address).put("port", port.toString())
                    .put("id", uuid).put("aid", "0").put("scy", v["cipher"] ?: "auto")
                    .put("net", net).put("type", "none")
                    .put("host", v["hostHeader"].orEmpty()).put("path", v["path"].orEmpty())
                    .put("tls", if (v["security"] == "tls") "tls" else "")
                    .put("sni", v["sni"].orEmpty())
                val b64 = Base64.encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                ManualOutcome.Ok("vmess://$b64", name)
            }
            ManualProtocol.SHADOWSOCKS -> {
                val userInfo = "${v["method"].orEmpty()}:${v["password"].orEmpty()}"
                val b64 = Base64.encodeToString(userInfo.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
                ManualOutcome.Ok("ss://$b64@${hostForUri(address)}:$port#${enc(name)}", name)
            }
            ManualProtocol.WIREGUARD, ManualProtocol.AMNEZIAWG -> buildWireGuard(p, v, address, port, name)
            else -> ManualOutcome.Invalid("name", "Profile settings are incomplete.")
        }
    }

    private val SLIPNET_SECTION_ORDER = listOf(
        "Profile", "Server key", "DNS", "Resolver discovery", "Tunnel tuning", "SSH account", "SSH transport"
    )

    /** Section headings for the SlipNet form, keyed by field. */
    private fun slipNetSection(key: String): String = when {
        key == "name" || key == "domain" -> "Profile"
        key == "publicKey" || key.startsWith("dnstt") -> "Server key"
        key.startsWith("soar") -> "Resolver discovery"
        key == "resolvers" || key == "dnsTransport" || key == "resolverMode" || key == "dnsPayloadSize" || key == "vaydnsRecordType" || key == "vaydnsMaxQname" -> "DNS"
        key.startsWith("vaydns") || key == "keepAlive" || key == "tcpListenPort" -> "Tunnel tuning"
        key == "sshTls" || key == "sshTlsSni" || key.startsWith("sshHttpProxy") || key.startsWith("sshWs") || key == "sshWebSocket" || key == "sshPayload" -> "SSH transport"
        key.startsWith("ssh") -> "SSH account"
        else -> ""
    }

    /** Builds an official SlipNet v30 profile and proves the in-app importer accepts it. */
    private fun buildSlipNet(p: ManualProtocol, v: Map<String, String>, name: String): ManualOutcome {
        val out = SlipNetManualProfile.build(p, v, name)
        if (out is ManualOutcome.Ok && EngineProfiles.decodeSlipNet(out.raw) == null) {
            return ManualOutcome.Invalid("domain", "The generated SlipNet profile was rejected by the importer. Check the fields and try again.")
        }
        return out
    }

    private fun buildMasterDns(v: Map<String, String>, name: String): ManualOutcome {
        val domains = v["domains"].orEmpty().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (domains.isEmpty() || domains.any { !isValidHost(it) || it.contains(':') || it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) }) {
            return ManualOutcome.Invalid("domains", "Enter tunnel domain names supplied by your MasterDNS server, not resolver IP addresses.")
        }
        val method = v["encryption"]?.toIntOrNull() ?: 1
        if (method !in 0..5) return ManualOutcome.Invalid("encryption", "Choose an encryption method from 0 to 5.")
        val key = v["encryptionKey"].orEmpty()
        if (method != 0 && (key.isBlank() || key.contains('\n') || key.length > 1024)) {
            return ManualOutcome.Invalid("encryptionKey", "Enter the shared encryption key from the MasterDNS server.")
        }
        val resolvers = v["resolvers"].orEmpty().split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        if (resolvers.isEmpty()) return ManualOutcome.Invalid("resolvers", "Add at least one DNS resolver.")
        val port = v["listenPort"]?.toIntOrNull() ?: 18000
        if (port !in 1024..65535) return ManualOutcome.Invalid("listenPort", "Local SOCKS5 port must be between 1024 and 65535.")
        val workers = v["workers"].orEmpty().ifBlank { "4" }.toIntOrNull()
        if (workers == null || workers !in 1..32) return ManualOutcome.Invalid("workers", "Workers must be between 1 and 32.")
        val mtu = v["mtu"].orEmpty().ifBlank { "1280" }.toIntOrNull()
        if (mtu == null || mtu !in 576..9000) return ManualOutcome.Invalid("mtu", "MTU must be between 576 and 9000.")
        val log = v["logLevel"].orEmpty().ifBlank { "WARN" }.uppercase()
        val profileName = name.ifBlank { "MasterDNS" }
        val raw = MasterDnsProfiles.encode(profileName, domains.joinToString("\n"), method, key, resolvers.joinToString("\n"), port, workers, mtu, log)
        // The same decoder the engine uses must accept the result, otherwise report the likely culprit.
        if (MasterDnsProfiles.decode(raw) == null) {
            return ManualOutcome.Invalid("resolvers", "Resolvers must be IPv4/IPv6 addresses (optionally :port or /CIDR), not host names.")
        }
        return ManualOutcome.Ok(raw, profileName)
    }

    private fun buildMhrv(v: Map<String, String>, name: String): ManualOutcome {
        val mode = v["mode"].orEmpty().ifBlank { "apps_script" }
        if (mode !in setOf("apps_script", "direct")) return ManualOutcome.Invalid("mode", "Choose a supported MHRV mode.")
        val googleIp = v["googleIp"].orEmpty()
        if (!isValidHost(googleIp) || googleIp.contains(':')) return ManualOutcome.Invalid("googleIp", "Enter a valid Google edge IPv4 address or host.")
        val front = v["frontDomain"].orEmpty()
        if (!isValidHost(front) || front.matches(Regex("^[0-9.]+$"))) return ManualOutcome.Invalid("frontDomain", "Enter a valid front SNI domain.")
        val config = JSONObject()
            .put("mode", mode)
            .put("listen_host", "127.0.0.1")
            .put("listen_port", 8080)
            .put("socks5_port", 1081)
            .put("google_ip", googleIp)
            .put("front_domain", front)
            .put("verify_ssl", true)
            .put("fetch_ips_from_api", false)
            .put("max_ips_to_scan", 20)
        if (mode == "apps_script") {
            val input = v["scriptUrl"].orEmpty().trim()
            val marker = "/macros/s/"
            val id = if (input.contains(marker)) input.substringAfter(marker).substringBefore('/').substringBefore('?') else input.substringBefore('/').substringBefore('?')
            if (id.isBlank() || !Regex("^[A-Za-z0-9_-]{10,}$").matches(id)) return ManualOutcome.Invalid("scriptUrl", "Enter a valid Apps Script deployment URL or deployment ID.")
            val key = v["authKey"].orEmpty()
            if (key.length < 16) return ManualOutcome.Invalid("authKey", "The MHRV authentication key must be at least 16 characters.")
            config.put("script_ids", org.json.JSONArray().put(id))
                .put("auth_key", key)
        }
        val snis = v["sniHosts"].orEmpty().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (snis.any { !isValidHost(it) || it.matches(Regex("^[0-9.]+$")) }) return ManualOutcome.Invalid("sniHosts", "SNI pool entries must be valid hostnames.")
        if (snis.isNotEmpty()) config.put("sni_hosts", org.json.JSONArray().apply { snis.forEach { put(it) } })
        val profileName = name.ifBlank { "MHRV" }
        return ManualOutcome.Ok(EngineProfiles.encodeMhrv(profileName, config), profileName)
    }

    private fun buildCottenDns(v: Map<String, String>, name: String): ManualOutcome {
        val domains = v["domains"].orEmpty().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (domains.isEmpty() || domains.any { !isValidHost(it) || it.contains(':') || it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) }) return ManualOutcome.Invalid("domains", "Enter tunnel domain names supplied by your CottenDNS server, not resolver IP addresses.")
        val key = v["encryptionKey"].orEmpty()
        if (key.isBlank() || key.contains('\n') || key.length > 512) return ManualOutcome.Invalid("encryptionKey", "Enter the shared encryption key from the CottenDNS server.")
        val encryption = v["encryption"]?.toIntOrNull() ?: 3
        if (encryption !in 0..5) return ManualOutcome.Invalid("encryption", "Choose an encryption method from 0 to 5.")
        val resolvers = v["resolvers"].orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (resolvers.isEmpty() || resolvers.any { !EngineProfiles.isResolverEndpoint(it) }) return ManualOutcome.Invalid("resolvers", "Enter valid IPv4 or IPv6 DNS resolver addresses, optionally followed by :port.")
        val mode = v["tunnelMode"].orEmpty().ifBlank { "legacy" }
        if (mode !in setOf("legacy", "cottenmax")) return ManualOutcome.Invalid("tunnelMode", "Choose Legacy or CottenMax.")
        val transport = v["resolverTransport"].orEmpty().ifBlank { "auto" }
        if (transport !in setOf("auto", "udp", "tcp", "dot", "doh", "doh3", "doq")) return ManualOutcome.Invalid("resolverTransport", "Choose a supported resolver transport.")
        if ((transport == "doh3" || transport == "doq") && mode != "cottenmax") return ManualOutcome.Invalid("resolverTransport", "HTTP/3 and DoQ resolver transports need CottenMax mode.")
        val preset = v["preset"].orEmpty().ifBlank { "speed" }
        if (preset !in setOf("speed", "survival", "tcp-survival", "low-bandwidth")) return ManualOutcome.Invalid("preset", "Choose a supported preset.")
        val listenPort = v["listenPort"]?.toIntOrNull() ?: 18000
        if (listenPort !in 1024..65535) return ManualOutcome.Invalid("listenPort", "Local SOCKS5 port must be between 1024 and 65535.")
        fun tomlString(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val domainArray = domains.joinToString(", ") { "\"${tomlString(it)}\"" }
        val config = """
DOMAINS = [$domainArray]
DATA_ENCRYPTION_METHOD = $encryption
ENCRYPTION_KEY = "${tomlString(key)}"
PROTOCOL_TYPE = "SOCKS5"
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = $listenPort
STARTUP_MODE = "resolvers"
CONFIG_PRESET = "$preset"
RESOLVER_TRANSPORT = "$transport"
TUNNEL_MODE = "$mode"
""".trim()
        val profileName = name.ifBlank { "CottenDNS" }
        return ManualOutcome.Ok(EngineProfiles.encodeCottenDns(profileName, config, resolvers.joinToString("\n")), profileName)
    }

    private fun buildStormDns(v: Map<String, String>, name: String): ManualOutcome {
        val domains = v["domains"].orEmpty().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (domains.isEmpty() || domains.any { !isValidHost(it) || it.contains(':') || it.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) }) return ManualOutcome.Invalid("domains", "Enter tunnel domain names supplied by your StormDNS server, not resolver IP addresses.")
        val key = v["encryptionKey"].orEmpty()
        if (key.isBlank() || key.contains('\n') || key.length > 512) return ManualOutcome.Invalid("encryptionKey", "Enter the shared encryption key from the StormDNS server.")
        val encryption = v["encryption"]?.toIntOrNull() ?: 1
        if (encryption !in 0..5) return ManualOutcome.Invalid("encryption", "Choose an encryption method from 0 to 5.")
        val resolvers = v["resolvers"].orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (resolvers.isEmpty() || resolvers.any { !EngineProfiles.isResolverEndpoint(it) }) return ManualOutcome.Invalid("resolvers", "Enter valid IPv4 or IPv6 DNS resolver addresses, optionally followed by :port.")
        val listenPort = v["listenPort"]?.toIntOrNull() ?: 18000
        if (listenPort !in 1024..65535) return ManualOutcome.Invalid("listenPort", "Local SOCKS5 port must be between 1024 and 65535.")
        fun tomlString(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val domainArray = domains.joinToString(", ") { "\"${tomlString(it)}\"" }
        val config = """
DOMAINS = [$domainArray]
DATA_ENCRYPTION_METHOD = $encryption
ENCRYPTION_KEY = "${tomlString(key)}"
PROTOCOL_TYPE = "SOCKS5"
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = $listenPort
SOCKS5_AUTH = false
STARTUP_MODE = "resolvers"
MTU_TEST_PARALLELISM_RESOLVERS = 12
MTU_TEST_RETRIES_RESOLVERS = 2
MTU_TEST_TIMEOUT_RESOLVERS = 1.5
RECHECK_BATCH_SIZE = 8
RX_TX_WORKERS = 2
TUNNEL_PROCESS_WORKERS = 2
LOG_TO_FILE = true
""".trim()
        val profileName = name.ifBlank { "DNS Tunnel" }
        return ManualOutcome.Ok(EngineProfiles.encodeStormDns(profileName, config, resolvers.joinToString("\n")), profileName)
    }

    private fun buildWireGuard(p: ManualProtocol, v: Map<String, String>, address: String, port: Int, name: String): ManualOutcome {
        if (!isWgKey(v["privateKey"].orEmpty())) return ManualOutcome.Invalid("privateKey", "Private key must be a 44-character base64 key.")
        if (!isWgKey(v["publicKey"].orEmpty())) return ManualOutcome.Invalid("publicKey", "Peer public key must be a 44-character base64 key.")
        val psk = v["presharedKey"].orEmpty()
        if (psk.isNotEmpty() && !isWgKey(psk)) return ManualOutcome.Invalid("presharedKey", "Preshared key must be a 44-character base64 key.")
        val addr = v["localAddress"].orEmpty()
        if (!Regex("^[0-9a-fA-F:.]+/\\d{1,3}(\\s*,\\s*[0-9a-fA-F:.]+/\\d{1,3})*$").matches(addr))
            return ManualOutcome.Invalid("localAddress", "Interface address must look like 10.0.0.2/32.")
        val mtu = v["mtu"].orEmpty()
        if (mtu.isNotEmpty() && (mtu.toIntOrNull() ?: 0) !in 576..9000) return ManualOutcome.Invalid("mtu", "MTU must be between 576 and 9000.")
        val ka = v["keepalive"].orEmpty()
        if (ka.isNotEmpty() && (ka.toIntOrNull() ?: -1) !in 0..65535) return ManualOutcome.Invalid("keepalive", "Keepalive must be a number from 0 to 65535.")

        val sb = StringBuilder()
        if (name.isNotEmpty()) sb.append("# Name = ").append(name.replace('\n', ' ')).append('\n')
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(v["privateKey"]).append('\n')
        sb.append("Address = ").append(addr).append('\n')
        v["dns"]?.takeIf { it.isNotEmpty() }?.let { sb.append("DNS = ").append(it).append('\n') }
        if (mtu.isNotEmpty()) sb.append("MTU = ").append(mtu).append('\n')
        if (p == ManualProtocol.AMNEZIAWG) {
            val anyAwg = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4").any { v["awg$it"].orEmpty().isNotEmpty() }
            if (!anyAwg) return ManualOutcome.Invalid("awgJc", "Enter the AmneziaWG parameters (Jc, Jmin, Jmax, S1, S2, H1-H4), or choose WireGuard.")
            for (k in listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4")) {
                val value = v["awg$k"].orEmpty()
                if (value.isEmpty()) continue
                if (!Regex("^[0-9]+(-[0-9]+)?$").matches(value)) return ManualOutcome.Invalid("awg$k", "$k must be a number${if (k.startsWith("H")) " or a range like 100-200" else ""}.")
                sb.append(k).append(" = ").append(value).append('\n')
            }
        }
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(v["publicKey"]).append('\n')
        if (psk.isNotEmpty()) sb.append("PresharedKey = ").append(psk).append('\n')
        sb.append("AllowedIPs = ").append(v["allowedIps"]?.ifEmpty { "0.0.0.0/0, ::/0" } ?: "0.0.0.0/0, ::/0").append('\n')
        sb.append("Endpoint = ").append(hostForUri(address)).append(':').append(port).append('\n')
        if (ka.isNotEmpty()) sb.append("PersistentKeepalive = ").append(ka).append('\n')
        return ManualOutcome.Ok(sb.toString().trim(), name)
    }

    private fun streamQuery(q: MutableMap<String, String>, v: Map<String, String>) {
        val net = v["network"] ?: "tcp"
        q["type"] = net
        val sec = v["security"] ?: "none"
        q["security"] = sec
        if (net != "tcp") v["path"]?.takeIf { it.isNotEmpty() }?.let { q["path"] = it }
        if (net == "ws" || net == "http") v["hostHeader"]?.takeIf { it.isNotEmpty() }?.let { q["host"] = it }
        if (sec == "tls" || sec == "reality") {
            v["sni"]?.takeIf { it.isNotEmpty() }?.let { q["sni"] = it }
            q["fp"] = v["fp"]?.ifEmpty { "chrome" } ?: "chrome"
        }
        if (sec == "tls") v["alpn"]?.takeIf { it.isNotEmpty() }?.let { q["alpn"] = it }
    }

    private fun queryString(q: Map<String, String>): String =
        q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun hostForUri(h: String): String = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

    private val UUID_RE = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun isWgKey(k: String): Boolean = Regex("^[A-Za-z0-9+/]{43}=$").matches(k)

    fun isValidHost(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)) return h.split('.').all { it.toInt() in 0..255 }
        if (h.contains(':')) return Regex("^[0-9a-fA-F:]+$").matches(h) && h.count { it == ':' } in 2..7
        return Regex("^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$").matches(h)
    }

    fun defaultsFor(p: ManualProtocol): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (f in fields(p)) m[f.key] = f.default
        return m
    }
}
