package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ManualProtocol(val label: String) {
    VLESS("VLESS"), VMESS("VMess"), TROJAN("Trojan"), SHADOWSOCKS("Shadowsocks"),
    WIREGUARD("WireGuard"), AMNEZIAWG("AmneziaWG")
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
    val showWhen: ((Map<String, String>) -> Boolean)? = null
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
        val address = v["address"].orEmpty()
        if (!isValidHost(address)) return ManualOutcome.Invalid("address", "Server address must be a domain name or an IP address.")
        val port = v["port"]?.toIntOrNull()
        if (port == null || port !in 1..65535) return ManualOutcome.Invalid("port", "Port must be a number from 1 to 65535.")
        val name = v["name"].orEmpty()

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
        }
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
