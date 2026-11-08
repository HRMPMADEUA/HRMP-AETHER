package com.tirexnet.app.engine

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/** Conservative decoder. It refuses field editing when rebuilding could drop unknown vendor data. */
object ManualProfileDecoder {
    data class Decoded(
        val protocol: ManualProtocol,
        val values: Map<String, String>,
        val editableWithoutDroppingUnknownFields: Boolean,
        val note: String = ""
    )

    fun decode(rawValue: String): Decoded? {
        val raw = rawValue.trim()
        if (raw.isEmpty()) return null
        return runCatching {
            when {
                raw.startsWith("vless://", true) -> decodeUri(raw, ManualProtocol.VLESS)
                raw.startsWith("trojan://", true) -> decodeUri(raw, ManualProtocol.TROJAN)
                raw.startsWith("vmess://", true) -> decodeVmess(raw)
                raw.startsWith("ss://", true) -> decodeShadowsocks(raw)
                raw.startsWith("masterdns://", true) -> decodeMasterDns(raw)
                raw.startsWith("stormdns://", true) -> decodeStormDns(raw)
                raw.startsWith("cottendns://", true) -> decodeCottenDns(raw)
                raw.startsWith("mhrv-rs://", true) -> decodeMhrv(raw)
                raw.startsWith("dnschanger://", true) -> decodeDnsChanger(raw)
                raw.startsWith("slipnet://", true) -> decodeSlipNet(raw)
                raw.contains("[Interface]", true) && raw.contains("[Peer]", true) -> decodeWireGuard(raw)
                else -> null
            }
        }.getOrNull()
    }

    private fun decodeUri(raw: String, protocol: ManualProtocol): Decoded {
        val uri = Uri.parse(raw)
        val values = linkedMapOf<String, String>()
        values["name"] = ProfileParser.fragmentName(raw)
        values["address"] = uri.host.orEmpty()
        values["port"] = uri.port.takeIf { it > 0 }?.toString() ?: "443"
        if (protocol == ManualProtocol.VLESS) values["uuid"] = percent(uri.userInfo.orEmpty())
        else values["password"] = percent(uri.userInfo.orEmpty())
        val known = setOf("encryption", "type", "security", "path", "host", "sni", "fp", "alpn", "flow", "pbk", "sid", "serviceName")
        values["network"] = uri.getQueryParameter("type") ?: "tcp"
        values["path"] = uri.getQueryParameter("path").orEmpty()
        values["hostHeader"] = uri.getQueryParameter("host").orEmpty()
        values["security"] = uri.getQueryParameter("security") ?: "none"
        values["sni"] = uri.getQueryParameter("sni").orEmpty()
        values["fp"] = uri.getQueryParameter("fp") ?: "chrome"
        values["alpn"] = uri.getQueryParameter("alpn").orEmpty()
        values["flow"] = uri.getQueryParameter("flow").orEmpty()
        values["pbk"] = uri.getQueryParameter("pbk").orEmpty()
        values["sid"] = uri.getQueryParameter("sid").orEmpty()
        val unknown = uri.queryParameterNames.any { it !in known } ||
            (protocol == ManualProtocol.VLESS && uri.getQueryParameter("encryption").orEmpty() !in setOf("", "none"))
        return Decoded(protocol, values, !unknown, if (unknown) "Unknown query parameters are present; raw editing is safer to preserve them." else "")
    }

    private fun decodeVmess(raw: String): Decoded {
        val encoded = raw.substringAfter("vmess://").substringBefore('#')
        val bytes = Base64.decode(encoded, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
        val obj = JSONObject(String(bytes, Charsets.UTF_8))
        val values = linkedMapOf(
            "name" to (obj.optString("ps").ifBlank { ProfileParser.fragmentName(raw) }),
            "address" to obj.optString("add"), "port" to obj.optString("port"),
            "uuid" to obj.optString("id"), "cipher" to obj.optString("scy", "auto"),
            "network" to obj.optString("net", "tcp"), "path" to obj.optString("path"),
            "hostHeader" to obj.optString("host"), "security" to if (obj.optString("tls") == "tls") "tls" else "none",
            "sni" to obj.optString("sni")
        )
        val known = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni")
        val unknown = obj.keys().asSequence().any { it !in known } || obj.optString("aid", "0") != "0" || obj.optString("type", "none") != "none"
        return Decoded(ManualProtocol.VMESS, values, !unknown, if (unknown) "Unknown VMess fields are present; raw editing is safer to preserve them." else "")
    }

    private fun decodeShadowsocks(raw: String): Decoded {
        if (raw.substringAfter('#', "").contains('?') || raw.substringBefore('#').contains('?')) {
            return Decoded(ManualProtocol.SHADOWSOCKS, emptyMap(), false, "Shadowsocks plugin/query parameters are present; raw editing preserves them.")
        }
        val body = raw.substringAfter("ss://").substringBefore('#').substringBefore('?')
        val at = body.lastIndexOf('@')
        if (at < 0) {
            val decoded = decodeBase64(body) ?: return Decoded(ManualProtocol.SHADOWSOCKS, emptyMap(), false, "Unsupported Shadowsocks encoding.")
            val split = decoded.lastIndexOf('@')
            if (split < 0) return Decoded(ManualProtocol.SHADOWSOCKS, emptyMap(), false, "Shadowsocks endpoint is missing.")
            val auth = Base64.encodeToString(decoded.substring(0, split).toByteArray(), Base64.NO_WRAP)
            return decodeShadowsocks("ss://$auth@${decoded.substring(split + 1)}#${ProfileParser.fragmentName(raw)}")
        }
        val user = decodeBase64(body.substring(0, at)) ?: percent(body.substring(0, at))
        val colon = user.indexOf(':')
        if (colon <= 0) return Decoded(ManualProtocol.SHADOWSOCKS, emptyMap(), false, "Shadowsocks method/password is invalid.")
        val uri = Uri.parse("ss://placeholder@${body.substring(at + 1)}")
        val values = linkedMapOf(
            "name" to ProfileParser.fragmentName(raw), "address" to uri.host.orEmpty(),
            "port" to uri.port.takeIf { it > 0 }?.toString().orEmpty(),
            "method" to user.substring(0, colon), "password" to user.substring(colon + 1)
        )
        return Decoded(ManualProtocol.SHADOWSOCKS, values, true)
    }

    private fun decodeWireGuard(raw: String): Decoded {
        val values = linkedMapOf<String, String>()
        values["name"] = ProfileParser.parse(raw).name
        val section = Regex("(?is)\\[Interface](.*?)(?:\\[Peer]|$)").find(raw)?.groupValues?.get(1).orEmpty()
        val peer = Regex("(?is)\\[Peer](.*)$").find(raw)?.groupValues?.get(1).orEmpty()
        fun field(block: String, key: String) = Regex("(?im)^\\s*${Regex.escape(key)}\\s*=\\s*(.+?)\\s*$").find(block)?.groupValues?.get(1).orEmpty()
        values["privateKey"] = field(section, "PrivateKey")
        values["localAddress"] = field(section, "Address")
        values["dns"] = field(section, "DNS")
        values["mtu"] = field(section, "MTU")
        values["publicKey"] = field(peer, "PublicKey")
        values["presharedKey"] = field(peer, "PresharedKey")
        values["allowedIps"] = field(peer, "AllowedIPs").ifBlank { "0.0.0.0/0, ::/0" }
        values["keepalive"] = field(peer, "PersistentKeepalive")
        val endpoint = field(peer, "Endpoint")
        val hp = splitEndpoint(endpoint)
        values["address"] = hp.first; values["port"] = hp.second
        val awgKeys = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4")
        awgKeys.forEach { values["awg$it"] = field(section, it) }
        val known = setOf("PrivateKey", "Address", "DNS", "MTU", "PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive") + awgKeys
        val keys = Regex("(?im)^\\s*([A-Za-z][A-Za-z0-9]*)\\s*=").findAll(raw).map { it.groupValues[1] }.toSet()
        val unknown = keys.any { it !in known }
        val proto = if (awgKeys.any { values["awg$it"].orEmpty().isNotBlank() }) ManualProtocol.AMNEZIAWG else ManualProtocol.WIREGUARD
        return Decoded(proto, values, !unknown, if (unknown) "Unknown WireGuard/AmneziaWG keys are present; use raw editing to preserve them." else "")
    }

    private fun decodeMasterDns(raw: String): Decoded {
        val obj = decodeWrapper(raw, "masterdns://") ?: throw IllegalArgumentException("Invalid MasterDNS payload")
        val values = linkedMapOf(
            "name" to obj.optString("name", "MasterDNS"),
            "domains" to obj.optString("domains"),
            "encryption" to obj.optInt("encryption_method", 1).toString(),
            "encryptionKey" to obj.optString("encryption_key"),
            "resolvers" to obj.optString("resolvers"),
            "listenPort" to obj.optInt("listen_port", 18000).toString(),
            "workers" to obj.optInt("workers", 4).toString(),
            "mtu" to obj.optInt("mtu", 1280).toString(),
            "logLevel" to obj.optString("log_level", "WARN")
        )
        val known = setOf("name", "domains", "encryption_method", "encryption_key", "resolvers", "listen_port", "workers", "mtu", "log_level")
        val unknown = obj.keys().asSequence().any { it !in known }
        return Decoded(ManualProtocol.MASTERDNS, values, !unknown, if (unknown) "Unknown MasterDNS fields are present; use raw editing to preserve them." else "")
    }

    private fun decodeStormDns(raw: String): Decoded {
        val p = EngineProfiles.decodeStormDns(raw) ?: return Decoded(ManualProtocol.STORMDNS, emptyMap(), false, "StormDNS profile could not be decoded.")
        val wrapper = decodeWrapper(raw, "stormdns://") ?: return Decoded(ManualProtocol.STORMDNS, emptyMap(), false, "StormDNS payload is invalid.")
        val config = wrapper.optString("client_config", p.clientConfig)
        val resolverText = wrapper.optString("resolvers", p.resolvers)
        val values = linkedMapOf(
            "name" to p.name, "domains" to tomlDomains(config),
            "encryption" to tomlValue(config, "DATA_ENCRYPTION_METHOD"),
            "encryptionKey" to tomlString(config, "ENCRYPTION_KEY"),
            "resolvers" to resolverText, "listenPort" to p.port.toString()
        )
        val allowed = setOf("DOMAINS", "DATA_ENCRYPTION_METHOD", "ENCRYPTION_KEY", "PROTOCOL_TYPE", "LISTEN_IP", "LISTEN_PORT", "SOCKS5_AUTH", "STARTUP_MODE", "MTU_TEST_PARALLELISM_RESOLVERS", "MTU_TEST_RETRIES_RESOLVERS", "MTU_TEST_TIMEOUT_RESOLVERS", "RECHECK_BATCH_SIZE", "RX_TX_WORKERS", "TUNNEL_PROCESS_WORKERS", "LOG_TO_FILE")
        val keys = Regex("(?im)^\\s*([A-Z0-9_]+)\\s*=").findAll(config).map { it.groupValues[1] }.toSet()
        val unknown = keys.any { it !in allowed } ||
            tomlString(config, "PROTOCOL_TYPE") != "SOCKS5" || tomlString(config, "LISTEN_IP") != "127.0.0.1" ||
            tomlValue(config, "SOCKS5_AUTH") != "false" || tomlString(config, "STARTUP_MODE") != "resolvers" ||
            tomlValue(config, "MTU_TEST_PARALLELISM_RESOLVERS") != "12" || tomlValue(config, "MTU_TEST_RETRIES_RESOLVERS") != "2" ||
            tomlValue(config, "MTU_TEST_TIMEOUT_RESOLVERS") != "1.5" || tomlValue(config, "RECHECK_BATCH_SIZE") != "8" ||
            tomlValue(config, "RX_TX_WORKERS") != "2" || tomlValue(config, "TUNNEL_PROCESS_WORKERS") != "2" || tomlValue(config, "LOG_TO_FILE") != "true"
        return Decoded(ManualProtocol.STORMDNS, values, !unknown, if (unknown) "Custom StormDNS TOML fields are present; use raw editing to preserve them." else "")
    }

    private fun decodeCottenDns(raw: String): Decoded {
        val p = EngineProfiles.decodeCottenDns(raw) ?: return Decoded(ManualProtocol.COTTENDNS, emptyMap(), false, "CottenDNS profile could not be decoded.")
        val wrapper = decodeWrapper(raw, "cottendns://") ?: return Decoded(ManualProtocol.COTTENDNS, emptyMap(), false, "CottenDNS payload is invalid.")
        val config = wrapper.optString("client_config", p.clientConfig)
        val resolverText = wrapper.optString("resolvers", p.resolvers)
        val values = linkedMapOf(
            "name" to p.name, "domains" to tomlDomains(config),
            "encryption" to tomlValue(config, "DATA_ENCRYPTION_METHOD"),
            "encryptionKey" to tomlString(config, "ENCRYPTION_KEY"),
            "resolvers" to resolverText, "tunnelMode" to tomlString(config, "TUNNEL_MODE").ifBlank { "legacy" },
            "resolverTransport" to tomlString(config, "RESOLVER_TRANSPORT").ifBlank { "auto" },
            "preset" to tomlString(config, "CONFIG_PRESET").ifBlank { "speed" },
            "listenPort" to p.port.toString()
        )
        val allowed = setOf("DOMAINS", "DATA_ENCRYPTION_METHOD", "ENCRYPTION_KEY", "PROTOCOL_TYPE", "LISTEN_IP", "LISTEN_PORT", "STARTUP_MODE", "CONFIG_PRESET", "RESOLVER_TRANSPORT", "TUNNEL_MODE")
        val keys = Regex("(?im)^\\s*([A-Z0-9_]+)\\s*=").findAll(config).map { it.groupValues[1] }.toSet()
        val unknown = keys.any { it !in allowed } || tomlString(config, "PROTOCOL_TYPE") != "SOCKS5" ||
            tomlString(config, "LISTEN_IP") != "127.0.0.1" || tomlString(config, "STARTUP_MODE") != "resolvers"
        return Decoded(ManualProtocol.COTTENDNS, values, !unknown, if (unknown) "Custom CottenDNS TOML fields are present; use raw editing to preserve them." else "")
    }

    private fun decodeMhrv(raw: String): Decoded {
        val wrapper = decodeWrapper(raw, "mhrv-rs://") ?: return Decoded(ManualProtocol.MHRV, emptyMap(), false, "MHRV profile could not be decoded.")
        val obj = wrapper.optJSONObject("config") ?: return Decoded(ManualProtocol.MHRV, emptyMap(), false, "MHRV config is missing.")
        val profileName = wrapper.optString("name", "MHRV")
        val ids = obj.optJSONArray("script_ids")
        val values = linkedMapOf(
            "name" to profileName, "mode" to obj.optString("mode", "apps_script"),
            "scriptUrl" to (ids?.optString(0).orEmpty().ifBlank { obj.optString("script_id", "") }),
            "authKey" to obj.optString("auth_key"), "googleIp" to obj.optString("google_ip", "216.239.38.120"),
            "frontDomain" to obj.optString("front_domain", "www.google.com"),
            "sniHosts" to obj.optJSONArray("sni_hosts")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }.orEmpty()
        )
        val known = setOf("mode", "listen_host", "listen_port", "socks5_port", "google_ip", "front_domain", "verify_ssl", "fetch_ips_from_api", "max_ips_to_scan", "script_ids", "script_id", "auth_key", "sni_hosts")
        val unknown = obj.keys().asSequence().any { it !in known } ||
            obj.optString("listen_host", "127.0.0.1") != "127.0.0.1" ||
            obj.optInt("listen_port", 8080) != 8080 || obj.optInt("socks5_port", 1081) != 1081
        return Decoded(ManualProtocol.MHRV, values, !unknown, if (unknown) "Unknown or custom MHRV runtime fields are present; use raw editing to preserve them." else "")
    }

    private fun decodeSlipNet(raw: String): Decoded {
        val token = raw.substringAfter("slipnet://").substringBefore('#')
            .replace("\r", "").replace("\n", "").replace(' ', '+')
        val bytes = runCatching {
            Base64.decode(token, Base64.DEFAULT)
        }.getOrNull() ?: return Decoded(
            ManualProtocol.SLIPNET_SS, emptyMap(), false,
            "SlipNet payload is not valid Base64."
        )
        val split = String(bytes, Charsets.UTF_8).split('|')
        if (split.isEmpty() || split[0] != "30") {
            return Decoded(
                ManualProtocol.SLIPNET_SS, emptyMap(), false,
                "Only SlipNet v30 can be decoded safely; this profile remains editable as raw text."
            )
        }
        // Pad to 84 fields so index access is always safe for mixed-length exports.
        val f = split.toMutableList()
        while (f.size < 84) f += ""

        val protocol = when (f[1].lowercase()) {
            "ss", "slipstream" -> ManualProtocol.SLIPNET_SS
            "ss_ssh", "slipstream_ssh" -> ManualProtocol.SLIPNET_SS_SSH
            "dnstt" -> ManualProtocol.SLIPNET_DNSTT
            "dnstt_ssh" -> ManualProtocol.SLIPNET_DNSTT_SSH
            "vaydns" -> ManualProtocol.SLIPNET_VAYDNS
            "vaydns_ssh" -> ManualProtocol.SLIPNET_VAYDNS_SSH
            "soar" -> ManualProtocol.SLIPNET_SOAR
            "soar_ssh" -> ManualProtocol.SLIPNET_SOAR_SSH
            else -> return Decoded(
                ManualProtocol.SLIPNET_SS, emptyMap(), false,
                "This SlipNet protocol (${f[1]}) is not part of the editable form yet."
            )
        }

        fun on(index: Int) = if (f.getOrNull(index) == "1") "on" else "off"
        fun unb64(index: Int) = decodeBase64(f.getOrNull(index).orEmpty()).orEmpty()

        val resolvers = f[4].split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            when {
                parts.size >= 2 && parts[0].isNotBlank() -> "${parts[0]}:${parts[1]}"
                parts.size == 1 && parts[0].isNotBlank() -> "${parts[0]}:53"
                else -> null
            }
        }.joinToString("\n")

        val values = linkedMapOf(
            "name" to f[2],
            "domain" to f[3],
            "resolvers" to resolvers,
            "dnsTransport" to f[22].ifBlank { "udp" },
            "resolverMode" to f[60].ifBlank { "roundrobin" },
            "keepAlive" to f[6].ifBlank { "5000" },
            "tcpListenPort" to f[8].ifBlank { "1080" },
            "dnsPayloadSize" to f.getOrNull(39).orEmpty().ifBlank { "0" },
            // Slipstream SOCKS credentials (fields 12/13)
            "socksUsername" to f[12],
            "socksPassword" to f[13],
            // DNSTT / Soar public key
            "publicKey" to f[11],
            "dnsttAuthoritative" to on(27),
            // SSH
            "sshUsername" to f[15],
            "sshPassword" to f[16],
            "sshPort" to f[17].ifBlank { "22" },
            "sshHost" to f[19],
            "sshAuthType" to f[23].ifBlank { "password" },
            "sshPrivateKey" to unb64(24),
            "sshKeyPassphrase" to unb64(25),
            "sshTls" to on(50),
            "sshTlsSni" to f[51],
            "sshHttpProxyHost" to f[52],
            "sshHttpProxyPort" to f[53].ifBlank { "8080" },
            "sshHttpProxyCustomHost" to f[54],
            "sshWebSocket" to on(55),
            "sshWsPath" to f[56].ifBlank { "/" },
            "sshWsTls" to on(57),
            "sshWsHost" to f[58],
            "sshPayload" to unb64(59),
            // VayDNS
            "vaydnsCompat" to on(41),
            "vaydnsRecordType" to f[42].ifBlank { "txt" },
            "vaydnsMaxQname" to f[43].ifBlank { "101" },
            "vaydnsRps" to f[44].ifBlank { "0" },
            "vaydnsIdleTimeout" to f[45].ifBlank { "0" },
            "vaydnsKeepalive" to f[46].ifBlank { "0" },
            "vaydnsUdpTimeout" to f[47].ifBlank { "0" },
            "vaydnsMaxLabels" to f[48].ifBlank { "0" },
            "vaydnsClientId" to f[49].ifBlank { "0" },
            // Soar
            "soarDiscovery" to on(79),
            "soarCountry" to f[80],
            "soarTcpMode" to f[81].ifBlank { "auto" },
            "soarDiscoveryList" to f[82].ifBlank { "soar" },
            "soarDiscoveryCustom" to f[83]
        )
        // Always editable: lock was already stripped by SlipNetDecodeFallback for enc imports.
        return Decoded(protocol, values, true)
    }

    private fun decodeDnsChanger(raw: String): Decoded {
        val p = DnsChangerProfiles.decode(raw) ?: throw IllegalArgumentException("Invalid DNS Changer profile")
        return Decoded(ManualProtocol.DNS_CHANGER, mapOf(
            "name" to p.name, "ipv4Primary" to p.ipv4Primary, "ipv4Secondary" to p.ipv4Secondary,
            "ipv6Primary" to p.ipv6Primary, "ipv6Secondary" to p.ipv6Secondary
        ), true)
    }

    private fun decodeWrapper(raw: String, prefix: String): JSONObject? = runCatching {
        val token = raw.trim().substring(prefix.length).substringBefore('#').trim()
        require(token.isNotEmpty())
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        JSONObject(String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    }.getOrNull()

    private fun tomlValue(config: String, key: String): String = Regex("(?im)^\\s*${Regex.escape(key)}\\s*=\\s*([^\\r\\n]+)").find(config)?.groupValues?.get(1)?.trim().orEmpty()
    private fun tomlString(config: String, key: String): String = Regex("(?im)^\\s*${Regex.escape(key)}\\s*=\\s*\"((?:\\\\.|[^\"])*)\"").find(config)?.groupValues?.get(1)?.replace("\\\\\"", "\"")?.replace("\\\\\\\\", "\\\\").orEmpty()
    private fun tomlDomains(config: String): String {
        val line = Regex("(?im)^\\s*DOMAINS\\s*=\\s*\\[(.*?)]\\s*$").find(config)?.groupValues?.get(1).orEmpty()
        return Regex("\"([^\"]+)\"").findAll(line).joinToString("\\n") { it.groupValues[1] }
    }

    private fun splitEndpoint(value: String): Pair<String, String> {
        val v = value.trim()
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end > 0) return v.substring(1, end) to v.substring(end + 1).removePrefix(":")
        }
        val i = v.lastIndexOf(':')
        return if (i > 0) v.substring(0, i) to v.substring(i + 1) else v to "51820"
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()
    private fun percent(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
