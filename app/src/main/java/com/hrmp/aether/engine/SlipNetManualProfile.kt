package com.tirexnet.app.engine

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Creates official SlipNet v30 share profiles. TIREXNET imports these with the
 * existing in-process SlipNet runtime, so DNSTT/VayDNS/Soar use their real
 * native implementations rather than a generic DNS placeholder.
 */
object SlipNetManualProfile {
    private val dnsttModes = setOf(ManualProtocol.SLIPNET_DNSTT, ManualProtocol.SLIPNET_DNSTT_SSH)
    private val vayModes = setOf(ManualProtocol.SLIPNET_VAYDNS, ManualProtocol.SLIPNET_VAYDNS_SSH)
    private val soarModes = setOf(ManualProtocol.SLIPNET_SOAR, ManualProtocol.SLIPNET_SOAR_SSH)
    private val sshModes = setOf(
        ManualProtocol.SLIPNET_DNSTT_SSH,
        ManualProtocol.SLIPNET_VAYDNS_SSH,
        ManualProtocol.SLIPNET_SOAR_SSH
    )

    fun fields(protocol: ManualProtocol, name: FieldSpec): List<FieldSpec> {
        val isSsh = protocol in sshModes
        val isDnstt = protocol in dnsttModes
        val isVay = protocol in vayModes
        val isSoar = protocol in soarModes
        val base = mutableListOf(
            name,
            FieldSpec("domain", "Tunnel domain", required = true, hint = "Domain configured on the tunnel server"),
            FieldSpec("resolvers", "DNS resolver list", FieldKind.MULTILINE,
                required = false, hint = "One resolver IP:port per line", default = "1.1.1.1:53\n8.8.8.8:53"),
            FieldSpec("dnsTransport", "DNS transport", FieldKind.CHOICE,
                choices = listOf("udp", "tcp", "dot", "doh"), default = "udp"),
            FieldSpec("resolverMode", "Resolver strategy", FieldKind.CHOICE,
                choices = listOf("roundrobin", "fanout"), default = "roundrobin"),
            FieldSpec("keepAlive", "Keep-alive (ms)", FieldKind.NUMBER, hint = "5000", default = "5000"),
            FieldSpec("tcpListenPort", "Local TCP port", FieldKind.NUMBER, hint = "1080", default = "1080"),
            FieldSpec("dnsPayloadSize", "DNS payload size", FieldKind.NUMBER, hint = "0 = automatic", default = "0")
        )

        when {
            isDnstt -> {
                base += FieldSpec("publicKey", "DNSTT server public key", FieldKind.SECRET,
                    required = true, hint = "64 hexadecimal characters")
                base += FieldSpec("dnsttAuthoritative", "Authoritative DNS mode", FieldKind.CHOICE,
                    choices = listOf("off", "on"), default = "off")
            }
            isVay -> {
                base += FieldSpec("publicKey", "Server public key (when required)", FieldKind.SECRET,
                    required = false, hint = "Base64 or hexadecimal key")
                base += FieldSpec("vaydnsCompat", "DNSTT compatibility", FieldKind.CHOICE,
                    choices = listOf("off", "on"), default = "off")
                base += FieldSpec("vaydnsRecordType", "DNS record type", FieldKind.CHOICE,
                    choices = listOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv"), default = "txt")
                base += FieldSpec("vaydnsMaxQname", "Maximum QNAME length", FieldKind.NUMBER, hint = "101", default = "101")
                base += FieldSpec("vaydnsRps", "Queries per second (0 = auto)", FieldKind.NUMBER, hint = "0", default = "0")
                base += FieldSpec("vaydnsIdleTimeout", "Idle timeout (seconds)", FieldKind.NUMBER, hint = "0 = default", default = "0")
                base += FieldSpec("vaydnsKeepalive", "VayDNS keep-alive (seconds)", FieldKind.NUMBER, hint = "0 = default", default = "0")
                base += FieldSpec("vaydnsUdpTimeout", "UDP timeout (ms)", FieldKind.NUMBER, hint = "0 = default", default = "0")
                base += FieldSpec("vaydnsMaxLabels", "Maximum data labels", FieldKind.NUMBER, hint = "0 = default", default = "0")
                base += FieldSpec("vaydnsClientId", "Client ID size", FieldKind.NUMBER, hint = "0 = default", default = "0")
            }
            isSoar -> {
                base += FieldSpec("publicKey", "Soar Ed25519 public key", FieldKind.SECRET,
                    required = true, hint = "32-byte base64 or 64 hexadecimal characters")
                base += FieldSpec("soarDiscovery", "Resolver discovery", FieldKind.CHOICE,
                    choices = listOf("off", "on"), default = "off")
                base += FieldSpec("soarCountry", "Discovery country", FieldKind.CHOICE,
                    choices = listOf("", "IR", "RU", "CN"), default = "")
                base += FieldSpec("soarDiscoveryList", "Discovery source", FieldKind.CHOICE,
                    choices = listOf("soar", "app", "custom"), default = "soar")
                base += FieldSpec("soarDiscoveryCustom", "Custom resolver candidates", FieldKind.MULTILINE,
                    hint = "IPv4 or IPv4:port; one per line", showWhen = { it["soarDiscoveryList"] == "custom" })
                base += FieldSpec("soarTcpMode", "TCP port 53 mode", FieldKind.CHOICE,
                    choices = listOf("auto", "on", "only", "off"), default = "auto")
            }
        }

        if (isSsh) {
            base += FieldSpec("sshHost", "SSH server", required = true, hint = "SSH host or IP")
            base += FieldSpec("sshPort", "SSH port", FieldKind.NUMBER, required = true, hint = "22", default = "22")
            base += FieldSpec("sshUsername", "SSH username", required = true)
            base += FieldSpec("sshAuthType", "SSH authentication", FieldKind.CHOICE,
                choices = listOf("password", "key"), default = "password")
            base += FieldSpec("sshPassword", "SSH password", FieldKind.SECRET,
                required = false, showWhen = { it["sshAuthType"] != "key" })
            base += FieldSpec("sshPrivateKey", "SSH private key", FieldKind.MULTILINE,
                required = false, hint = "OpenSSH private key", showWhen = { it["sshAuthType"] == "key" })
            base += FieldSpec("sshKeyPassphrase", "SSH key passphrase", FieldKind.SECRET,
                showWhen = { it["sshAuthType"] == "key" })
            base += FieldSpec("sshTls", "SSH over TLS", FieldKind.CHOICE,
                choices = listOf("off", "on"), default = "off")
            base += FieldSpec("sshTlsSni", "SSH TLS SNI", hint = "Optional hostname", showWhen = { it["sshTls"] == "on" })
            base += FieldSpec("sshHttpProxyHost", "HTTP CONNECT proxy host", hint = "Optional")
            base += FieldSpec("sshHttpProxyPort", "HTTP CONNECT proxy port", FieldKind.NUMBER,
                hint = "8080", default = "8080")
            base += FieldSpec("sshHttpProxyCustomHost", "HTTP CONNECT Host header", hint = "Optional")
            base += FieldSpec("sshWebSocket", "SSH over WebSocket", FieldKind.CHOICE,
                choices = listOf("off", "on"), default = "off")
            base += FieldSpec("sshWsPath", "WebSocket path", hint = "/", default = "/",
                showWhen = { it["sshWebSocket"] == "on" })
            base += FieldSpec("sshWsTls", "WebSocket TLS", FieldKind.CHOICE,
                choices = listOf("off", "on"), default = "off", showWhen = { it["sshWebSocket"] == "on" })
            base += FieldSpec("sshWsHost", "WebSocket Host header", hint = "Optional",
                showWhen = { it["sshWebSocket"] == "on" })
            base += FieldSpec("sshPayload", "SSH payload prefix", FieldKind.MULTILINE,
                hint = "Optional raw payload sent before SSH")
        }
        return base
    }

    fun build(protocol: ManualProtocol, values: Map<String, String>, fallbackName: String): ManualOutcome {
        val mode = when (protocol) {
            ManualProtocol.SLIPNET_DNSTT -> "dnstt"
            ManualProtocol.SLIPNET_DNSTT_SSH -> "dnstt_ssh"
            ManualProtocol.SLIPNET_VAYDNS -> "vaydns"
            ManualProtocol.SLIPNET_VAYDNS_SSH -> "vaydns_ssh"
            ManualProtocol.SLIPNET_SOAR -> "soar"
            ManualProtocol.SLIPNET_SOAR_SSH -> "soar_ssh"
            else -> return ManualOutcome.Invalid("protocol", "Choose DNSTT, VayDNS or Soar.")
        }
        val useSsh = protocol in sshModes
        val isDnstt = protocol in dnsttModes
        val isVay = protocol in vayModes
        val isSoar = protocol in soarModes
        val name = fallbackName.ifBlank { protocol.label }
        val domain = values["domain"].orEmpty().trim().trimEnd('.')
        if (!ManualProfile.isValidHost(domain) || domain.contains(':') ||
            domain.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            return ManualOutcome.Invalid("domain", "Enter the tunnel domain configured on the server.")
        }

        val publicKey = values["publicKey"].orEmpty().trim()
        if (isDnstt && !Regex("(?i)^[0-9a-f]{64}$").matches(publicKey)) {
            return ManualOutcome.Invalid("publicKey", "DNSTT public key must contain exactly 64 hexadecimal characters.")
        }
        if (isSoar && !validSoarKey(publicKey)) {
            return ManualOutcome.Invalid("publicKey", "Soar key must be a 32-byte Ed25519 key in base64 or hexadecimal form.")
        }

        val resolvers = parseResolverEntries(values["resolvers"].orEmpty())
        val soarDiscovery = values["soarDiscovery"] == "on"
        if (isSoar) {
            val source = values["soarDiscoveryList"].orEmpty().ifBlank { "soar" }
            if (source !in setOf("soar", "app", "custom")) return ManualOutcome.Invalid("soarDiscoveryList", "Choose a valid Soar discovery source.")
            if (!soarDiscovery && resolvers.isEmpty()) return ManualOutcome.Invalid("resolvers", "Add at least one resolver or enable Soar resolver discovery.")
            if (source == "custom" && soarDiscovery) {
                val candidates = values["soarDiscoveryCustom"].orEmpty()
                if (!validCustomCandidates(candidates)) return ManualOutcome.Invalid("soarDiscoveryCustom", "Add valid resolver IP addresses to the custom discovery list.")
            }
        } else if (resolvers.isEmpty()) {
            return ManualOutcome.Invalid("resolvers", "Add at least one resolver IP:port entry.")
        }

        val dnsTransport = values["dnsTransport"].orEmpty().ifBlank { "udp" }.lowercase(Locale.US)
        if (dnsTransport !in setOf("udp", "tcp", "dot", "doh")) return ManualOutcome.Invalid("dnsTransport", "Choose UDP, TCP, DoT or DoH.")
        if (isDnstt && dnsTransport == "doh") return ManualOutcome.Invalid("dnsTransport", "DNSTT does not support DoH in the current SlipNet runtime. Choose UDP, TCP or DoT.")
        val resolverMode = values["resolverMode"].orEmpty().ifBlank { "roundrobin" }
        if (resolverMode !in setOf("roundrobin", "fanout")) return ManualOutcome.Invalid("resolverMode", "Choose a valid resolver strategy.")

        val localPort = number(values, "tcpListenPort", 1080, 1..65535)
            ?: return ManualOutcome.Invalid("tcpListenPort", "Local TCP port must be between 1 and 65535.")
        val keepAlive = number(values, "keepAlive", 5000, 250..120_000)
            ?: return ManualOutcome.Invalid("keepAlive", "Keep-alive must be between 250 and 120000 ms.")
        val payloadSize = number(values, "dnsPayloadSize", 0, 0..65535)
            ?: return ManualOutcome.Invalid("dnsPayloadSize", "DNS payload size must be between 0 and 65535.")

        val sshHost: String
        val sshPort: Int
        val sshUser: String
        val sshPassword: String
        val sshPrivateKey: String
        val sshPassphrase: String
        val sshAuth: String
        if (useSsh) {
            sshHost = values["sshHost"].orEmpty().trim()
            if (!ManualProfile.isValidHost(sshHost)) return ManualOutcome.Invalid("sshHost", "Enter a valid SSH host or IP address.")
            sshPort = number(values, "sshPort", 22, 1..65535)
                ?: return ManualOutcome.Invalid("sshPort", "SSH port must be between 1 and 65535.")
            sshUser = values["sshUsername"].orEmpty().trim()
            if (sshUser.isBlank() || sshUser.any { it == '|' || it == '\n' || it == '\r' }) {
                return ManualOutcome.Invalid("sshUsername", "Enter the SSH username.")
            }
            sshAuth = values["sshAuthType"].orEmpty().ifBlank { "password" }
            if (sshAuth !in setOf("password", "key")) return ManualOutcome.Invalid("sshAuthType", "Choose password or key authentication.")
            sshPassword = values["sshPassword"].orEmpty()
            sshPrivateKey = values["sshPrivateKey"].orEmpty().trim()
            sshPassphrase = values["sshKeyPassphrase"].orEmpty()
            if (sshAuth == "password" && sshPassword.isBlank()) return ManualOutcome.Invalid("sshPassword", "Enter the SSH password.")
            if (sshAuth == "key" && !sshPrivateKey.contains("BEGIN") && sshPrivateKey.length < 64) {
                return ManualOutcome.Invalid("sshPrivateKey", "Paste a valid OpenSSH private key.")
            }
        } else {
            sshHost = ""
            sshPort = 22
            sshUser = ""
            sshPassword = ""
            sshPrivateKey = ""
            sshPassphrase = ""
            sshAuth = "password"
        }

        val vaydnsRecordType = values["vaydnsRecordType"].orEmpty().ifBlank { "txt" }.lowercase(Locale.US)
        if (vaydnsRecordType !in setOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv")) {
            return ManualOutcome.Invalid("vaydnsRecordType", "Choose a supported VayDNS record type.")
        }
        val maxQname = number(values, "vaydnsMaxQname", 101, 64..255)
            ?: return ManualOutcome.Invalid("vaydnsMaxQname", "Maximum QNAME length must be between 64 and 255.")
        val rpsText = values["vaydnsRps"].orEmpty().ifBlank { "0" }
        val rps = rpsText.toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
            ?: return ManualOutcome.Invalid("vaydnsRps", "Queries per second must be between 0 and 1000.")
        val idleTimeout = number(values, "vaydnsIdleTimeout", 0, 0..86400)
            ?: return ManualOutcome.Invalid("vaydnsIdleTimeout", "Idle timeout must be between 0 and 86400 seconds.")
        val vayKeepAlive = number(values, "vaydnsKeepalive", 0, 0..3600)
            ?: return ManualOutcome.Invalid("vaydnsKeepalive", "VayDNS keep-alive must be between 0 and 3600 seconds.")
        val udpTimeout = number(values, "vaydnsUdpTimeout", 0, 0..60000)
            ?: return ManualOutcome.Invalid("vaydnsUdpTimeout", "UDP timeout must be between 0 and 60000 ms.")
        val maxLabels = number(values, "vaydnsMaxLabels", 0, 0..128)
            ?: return ManualOutcome.Invalid("vaydnsMaxLabels", "Maximum data labels must be between 0 and 128.")
        val clientIdSize = number(values, "vaydnsClientId", 0, 0..32)
            ?: return ManualOutcome.Invalid("vaydnsClientId", "Client ID size must be between 0 and 32.")

        val sshTls = useSsh && values["sshTls"] == "on"
        val sshTlsSni = values["sshTlsSni"].orEmpty().trim()
        if (sshTls && sshTlsSni.isNotBlank() && !ManualProfile.isValidHost(sshTlsSni)) {
            return ManualOutcome.Invalid("sshTlsSni", "Enter a valid SSH TLS SNI hostname.")
        }
        val sshWs = useSsh && values["sshWebSocket"] == "on"
        val sshWsPath = values["sshWsPath"].orEmpty().ifBlank { "/" }
        if (sshWsPath.length > 512 || sshWsPath.contains('|')) return ManualOutcome.Invalid("sshWsPath", "WebSocket path is too long or contains an invalid character.")

        val fields = MutableList(84) { "" }
        fun set(index: Int, value: String) { fields[index] = value.replace("|", "").replace('\r', ' ').replace('\n', ' ').take(4096) }
        set(0, "30")
        set(1, mode)
        set(2, name)
        set(3, domain)
        set(4, resolvers.joinToString(",") { "${it.first}:${it.second}:0" })
        set(5, "0")
        set(6, keepAlive.toString())
        set(7, "bbr")
        set(8, localPort.toString())
        set(9, "127.0.0.1")
        set(10, "0")
        set(11, publicKey)
        set(12, "")
        set(13, "")
        set(14, if (useSsh) "1" else "0")
        set(15, sshUser)
        set(16, sshPassword)
        set(17, sshPort.toString())
        set(18, "0")
        set(19, sshHost)
        set(20, "0")
        set(21, "https://dns.google/dns-query")
        set(22, dnsTransport)
        set(23, sshAuth)
        set(24, base64(sshPrivateKey))
        set(25, base64(sshPassphrase))
        set(26, "")
        set(27, if (isDnstt && values["dnsttAuthoritative"] == "on") "1" else "0")
        set(28, "443")
        set(29, "")
        set(30, "")
        set(31, "0")
        set(32, "")
        set(33, "0")
        set(34, "0")
        set(35, "")
        set(36, "0")
        set(37, "")
        set(38, "0")
        set(39, payloadSize.toString())
        set(40, localPort.toString())
        set(41, if (isVay && values["vaydnsCompat"] == "on") "1" else "0")
        set(42, if (isVay) vaydnsRecordType else "txt")
        set(43, if (isVay) maxQname.toString() else "101")
        set(44, if (isVay) rps.toString() else "0")
        set(45, if (isVay) idleTimeout.toString() else "0")
        set(46, if (isVay) vayKeepAlive.toString() else "0")
        set(47, if (isVay) udpTimeout.toString() else "0")
        set(48, if (isVay) maxLabels.toString() else "0")
        set(49, if (isVay) clientIdSize.toString() else "0")
        set(50, if (sshTls) "1" else "0")
        set(51, sshTlsSni)
        set(52, values["sshHttpProxyHost"].orEmpty())
        set(53, number(values, "sshHttpProxyPort", 8080, 1..65535)?.toString() ?: "8080")
        set(54, values["sshHttpProxyCustomHost"].orEmpty())
        set(55, if (sshWs) "1" else "0")
        set(56, sshWsPath)
        set(57, if (sshWs && values["sshWsTls"] == "on") "1" else "0")
        set(58, values["sshWsHost"].orEmpty())
        set(59, base64(values["sshPayload"].orEmpty()))
        set(60, resolverMode)
        set(61, "2")
        set(62, "")
        set(63, "tls")
        set(64, "ws")
        set(65, "/")
        set(66, "")
        set(67, "443")
        set(68, "1")
        set(69, "micro")
        set(70, "300")
        set(71, "")
        set(72, "0")
        set(73, "1")
        set(74, "0")
        set(75, "8")
        set(76, "")
        set(77, "0")
        set(78, "")
        set(79, if (isSoar && soarDiscovery) "1" else "0")
        set(80, if (isSoar) values["soarCountry"].orEmpty().uppercase(Locale.US) else "")
        set(81, if (isSoar) values["soarTcpMode"].orEmpty().ifBlank { "auto" } else "auto")
        set(82, if (isSoar) values["soarDiscoveryList"].orEmpty().ifBlank { "soar" } else "soar")
        set(83, if (isSoar) normalizeCustomCandidates(values["soarDiscoveryCustom"].orEmpty()) else "")

        val encoded = Base64.encodeToString(fields.joinToString("|").toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return ManualOutcome.Ok("slipnet://$encoded", name)
    }

    private fun number(values: Map<String, String>, key: String, default: Int, range: IntRange): Int? {
        val value = values[key].orEmpty().ifBlank { default.toString() }.toIntOrNull() ?: return null
        return value.takeIf { it in range }
    }

    private fun parseResolverEntries(raw: String): List<Pair<String, Int>> {
        return raw.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { entry ->
                if (entry.startsWith("[")) return@mapNotNull null
                val pieces = entry.split(':')
                if (pieces.size !in 1..2) return@mapNotNull null
                val ip = pieces[0].trim()
                val port = if (pieces.size == 2) pieces[1].toIntOrNull() ?: return@mapNotNull null else 53
                if (!isIpv4(ip) || port !in 1..65535) null else ip to port
            }
            .distinctBy { "${it.first}:${it.second}" }
            .take(100)
            .toList()
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull()?.let { n -> n in 0..255 } == true) }
    }

    private fun validCustomCandidates(raw: String): Boolean {
        val items = raw.split(',', ';', '\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        return items.isNotEmpty() && items.size <= 5000 && items.all { entry ->
            val host = entry.substringBefore(':')
            val port = entry.substringAfter(':', "53")
            isIpv4(host) && (port.toIntOrNull() ?: -1) in 1..65535
        }
    }

    private fun validSoarKey(value: String): Boolean {
        if (value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return true
        return runCatching {
            Base64.decode(value, Base64.NO_WRAP).size == 32
        }.getOrDefault(false)
    }

    private fun normalizeCustomCandidates(raw: String): String = raw
        .split(',', ';', '\n', '\r', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")

    private fun base64(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
    )
}
