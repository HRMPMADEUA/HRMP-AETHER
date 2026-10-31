package com.tirexnet.app.engine

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Converts common share links into Xray JSON
 * and adds the TUN inbound used by VpnService.
 */
object XrayConfigBuilder {

    fun build(input: String): String {
        val raw = input.trim()

        require(raw.isNotEmpty()) {
            "Configuration is empty."
        }

        if (raw.startsWith("{")) {
            val json = JSONObject(raw)

            require(json.has("outbounds")) {
                "JSON configuration has no outbounds array."
            }

            addTunIfMissing(json)

            if (!json.has("log")) {
                json.put(
                    "log",
                    JSONObject().put("loglevel", "warning")
                )
            }

            return json.toString()
        }

        val lower = raw.lowercase()

        val outbound = when {
            lower.startsWith("vless://") -> vless(raw)
            lower.startsWith("vmess://") -> vmess(raw)
            lower.startsWith("trojan://") -> trojan(raw)
            lower.startsWith("ss://") -> shadowsocks(raw)

            else -> throw IllegalArgumentException(
                "Supported links: VLESS, VMess, Trojan and Shadowsocks."
            )
        }

        return JSONObject()
            .put(
                "log",
                JSONObject().put("loglevel", "warning")
            )
            .put(
                "inbounds",
                JSONArray().put(tunInbound())
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(
                        JSONObject()
                            .put("tag", "direct")
                            .put("protocol", "freedom")
                    )
                    .put(
                        JSONObject()
                            .put("tag", "block")
                            .put("protocol", "blackhole")
                    )
            )
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", JSONArray())
            )
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put("1.1.1.1")
                            .put("8.8.8.8")
                    )
            )
            .toString()
    }

    private fun tunInbound() =
        JSONObject()
            .put("tag", "tun")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "xray0")
                    .put("MTU", 1500)
                    .put("userLevel", 8)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put(
                        "destOverride",
                        JSONArray()
                            .put("http")
                            .put("tls")
                            .put("quic")
                    )
            )

    private fun addTunIfMissing(json: JSONObject) {
        var arr = json.optJSONArray("inbounds")

        if (arr == null) {
            arr = JSONArray()
            json.put("inbounds", arr)
        }

        for (i in 0 until arr.length()) {
            if (
                arr.optJSONObject(i)
                    ?.optString("protocol") == "tun"
            ) {
                return
            }
        }

        arr.put(tunInbound())
    }

    private fun vless(raw: String): JSONObject {
        val uri = Uri.parse(raw)

        val host = requireNotNull(uri.host) {
            "VLESS host is missing."
        }

        val q = Query(uri)

        val user = JSONObject()
            .put(
                "id",
                requireNotNull(uri.userInfo) {
                    "VLESS UUID is missing."
                }
            )
            .put(
                "encryption",
                q.get("encryption", "none")
            )

        q.get("flow")
            .takeIf { it.isNotBlank() }
            ?.let { user.put("flow", it) }

        val settings = JSONObject()
            .put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", host)
                        .put(
                            "port",
                            if (uri.port > 0) uri.port else 443
                        )
                        .put(
                            "users",
                            JSONArray().put(user)
                        )
                )
            )

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", settings)
            .put(
                "streamSettings",
                stream(uri, host, q)
            )
    }

    private fun trojan(raw: String): JSONObject {
        val uri = Uri.parse(raw)

        val host = requireNotNull(uri.host) {
            "Trojan host is missing."
        }

        val password = requireNotNull(uri.userInfo) {
            "Trojan password is missing."
        }

        val q = Query(uri)

        val server = JSONObject()
            .put("address", host)
            .put(
                "port",
                if (uri.port > 0) uri.port else 443
            )
            .put("password", password)

        q.get("flow")
            .takeIf { it.isNotBlank() }
            ?.let { server.put("flow", it) }

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(server)
                )
            )
            .put(
                "streamSettings",
                stream(uri, host, q, "tls")
            )
    }

    private fun vmess(raw: String): JSONObject {
        val encoded = raw
            .substringAfter("vmess://")
            .substringBefore('#')
            .trim()

        val data = decodeBase64(encoded)
        val vm = JSONObject(data)

        val host = vm.optString("add")

        require(host.isNotBlank()) {
            "VMess host is missing."
        }

        val id = vm.optString("id")

        require(id.isNotBlank()) {
            "VMess ID is missing."
        }

        val user = JSONObject()
            .put("id", id)
            .put("alterId", vm.optInt("aid", 0))
            .put(
                "security",
                vm.optString("scy", "auto")
                    .ifBlank { "auto" }
            )

        val settings = JSONObject()
            .put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", host)
                        .put(
                            "port",
                            vm.optString("port")
                                .toIntOrNull() ?: 443
                        )
                        .put(
                            "users",
                            JSONArray().put(user)
                        )
                )
            )

        val network = vm.optString("net", "tcp")
            .ifBlank { "tcp" }

        val stream = JSONObject()
            .put("network", network)

        when (network) {
            "ws" -> {
                stream.put(
                    "wsSettings",
                    JSONObject()
                        .put(
                            "path",
                            vm.optString("path", "/")
                        )
                        .put(
                            "headers",
                            JSONObject().put(
                                "Host",
                                vm.optString("host", host)
                            )
                        )
                )
            }

            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject().put(
                        "serviceName",
                        vm.optString("path", "")
                    )
                )
            }

            "http" -> {
                stream.put(
                    "httpSettings",
                    JSONObject()
                        .put(
                            "path",
                            vm.optString("path", "/")
                        )
                        .put(
                            "host",
                            JSONArray().put(
                                vm.optString("host", host)
                            )
                        )
                )
            }
        }

        if (vm.optString("tls").equals("tls", true)) {
            val tls = JSONObject()

            val sni = vm.optString(
                "sni",
                vm.optString("host", host)
            )

            if (sni.isNotBlank()) {
                tls.put("serverName", sni)
            }

            if (vm.optString("fp").isNotBlank()) {
                tls.put(
                    "fingerprint",
                    vm.optString("fp")
                )
            }

            stream
                .put("security", "tls")
                .put("tlsSettings", tls)
        }

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", settings)
            .put("streamSettings", stream)
    }

    private fun shadowsocks(raw: String): JSONObject {
        var body = raw
            .substringAfter("ss://")
            .substringBefore('#')
            .substringBefore('?')

        val auth: String
        val endpoint: String

        if (body.contains('@')) {
            val at = body.lastIndexOf('@')

            var creds = body.substring(0, at)
            endpoint = body.substring(at + 1)

            if (!creds.contains(':')) {
                creds = runCatching {
                    decodeBase64(creds)
                }.getOrDefault(creds)
            }

            auth = creds
        } else {
            body = decodeBase64(body)

            require(body.contains('@')) {
                "Unsupported Shadowsocks URI."
            }

            auth = body.substringBeforeLast('@')
            body = body.substringAfterLast('@')

            return ssOutbound(auth, body)
        }

        return ssOutbound(auth, endpoint)
    }

    private fun ssOutbound(
        auth: String,
        endpoint: String
    ): JSONObject {
        val method = auth.substringBefore(':')
        val password = auth.substringAfter(':', "")

        require(
            method.isNotBlank() &&
                password.isNotBlank()
        ) {
            "Shadowsocks method/password is missing."
        }

        val host: String
        val port: Int

        if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')

            require(close > 0) {
                "Invalid IPv6 Shadowsocks host."
            }

            host = endpoint.substring(1, close)

            port = endpoint
                .substring(close + 1)
                .removePrefix(":")
                .toIntOrNull() ?: 443

        } else {
            host = endpoint.substringBeforeLast(':')

            port = endpoint
                .substringAfterLast(':')
                .toIntOrNull() ?: 443
        }

        require(host.isNotBlank()) {
            "Shadowsocks host is missing."
        }

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("method", method)
                            .put("password", password)
                    )
                )
            )
    }

    private fun stream(
        uri: Uri,
        host: String,
        q: Query,
        defaultSecurity: String = "none"
    ): JSONObject {
        val network = q.get(
            "type",
            q.get("network", "tcp")
        ).ifBlank { "tcp" }

        val stream = JSONObject()
            .put("network", network)

        // Empty query values such as security= must be treated as
        // an explicit request for no transport security.
        val rawSecurity = q.get("security")
        val security = when {
            rawSecurity.isBlank() && uri.toString().contains("security=") -> "none"
            rawSecurity.isBlank() && q.get("tls").equals("tls", true) -> "tls"
            rawSecurity.isBlank() -> defaultSecurity
            else -> rawSecurity.trim().lowercase()
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject()
                    .put(
                        "allowInsecure",
                        q.get("allowInsecure") in
                            listOf("1", "true")
                    )

                val sni = q.get(
                    "sni",
                    q.get("host", host)
                )

                if (sni.isNotBlank()) {
                    tls.put("serverName", sni)
                }

                q.get(
                    "fp",
                    q.get("fingerprint")
                ).takeIf { it.isNotBlank() }
                    ?.let {
                        tls.put("fingerprint", it)
                    }

                q.get("alpn")
                    .takeIf { it.isNotBlank() }
                    ?.let { value ->
                        tls.put(
                            "alpn",
                            JSONArray().apply {
                                value.split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .forEach { put(it) }
                            }
                        )
                    }

                stream
                    .put("security", "tls")
                    .put("tlsSettings", tls)
            }

            "reality" -> {
                stream
                    .put("security", "reality")
                    .put(
                        "realitySettings",
                        JSONObject()
                            .put("show", false)
                            .put(
                                "fingerprint",
                                q.get("fp", "chrome")
                            )
                            .put(
                                "serverName",
                                q.get("sni", q.get("host", host))
                            )
                            .put(
                                "publicKey",
                                q.get(
                                    "pbk",
                                    q.get("publicKey")
                                )
                            )
                            .put("shortId", q.get("sid"))
                            .put("spiderX", q.get("spx", "/"))
                    )
            }
        }

        when (network) {
            "ws" -> {
                stream.put(
                    "wsSettings",
                    JSONObject()
                        .put("path", q.get("path", "/"))
                        .put(
                            "headers",
                            JSONObject().put(
                                "Host",
                                q.get("host", host)
                            )
                        )
                )
            }

            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject()
                        .put(
                            "serviceName",
                            q.get(
                                "serviceName",
                                q.get("path", "")
                            )
                        )
                        .put(
                            "multiMode",
                            q.get("mode") == "multi"
                        )
                )
            }

            "http" -> {
                stream.put(
                    "httpSettings",
                    JSONObject()
                        .put("path", q.get("path", "/"))
                        .put(
                            "host",
                            JSONArray().put(q.get("host", host))
                        )
                )
            }

            "tcp" -> {
                // Legacy VLESS links may carry headerType=http while
                // network remains tcp. Xray expects tcpSettings.header.
                val headerType = q.get("headerType", q.get("header_type"))
                    .trim().lowercase()
                if (headerType == "http") {
                    val headerHost = q.get("host", host).trim()
                    val headers = JSONObject()
                        .put("Host", JSONArray().put(headerHost))
                        .put("User-Agent", JSONArray().put("Mozilla/5.0"))
                        .put("Accept", JSONArray().put("*/*"))
                        .put("Connection", JSONArray().put("keep-alive"))

                    val request = JSONObject()
                        .put("version", "1.1")
                        .put("method", "GET")
                        .put("path", JSONArray().put("/"))
                        .put("headers", headers)

                    stream.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject()
                                .put("type", "http")
                                .put("request", request)
                        )
                    )
                }
            }
        }

        // Be explicit: Xray's default for VLESS TCP without TLS/Reality
        // is security=none. This also handles links containing security=.
        stream.put("security", security.ifBlank { "none" })

        return stream
    }

    private class Query(
        private val uri: Uri
    ) {
        fun get(
            name: String,
            fallback: String = ""
        ): String {
            return try {
                uri.getQueryParameter(name) ?: fallback
            } catch (_: Exception) {
                fallback
            }
        }
    }

    private fun decodeBase64(value: String): String {
        val normalized = value
            .replace('-', '+')
            .replace('_', '/') +
            "=".repeat((4 - value.length % 4) % 4)

        val bytes = try {
            Base64.decode(
                normalized,
                Base64.DEFAULT
            )
        } catch (_: Exception) {
            Base64.decode(
                normalized,
                Base64.URL_SAFE or Base64.NO_WRAP
            )
        }

        return String(
            bytes,
            StandardCharsets.UTF_8
        )
    }
}
