package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Local DNS Changer profile format. The profile is deliberately independent from
 * Xray/WireGuard/StormDNS and is consumed only by DnsChangerVpnService.
 */
data class DnsChangerProfile(
    val name: String,
    val ipv4Primary: String,
    val ipv4Secondary: String,
    val ipv6Primary: String,
    val ipv6Secondary: String
) {
    fun resolverAddresses(): List<InetAddress> = listOf(
        ipv4Primary, ipv4Secondary, ipv6Primary, ipv6Secondary
    ).mapNotNull { value -> value.takeIf { it.isNotBlank() }?.let { InetAddress.getByName(it) } }.distinctBy { it.hostAddress }
}

object DnsChangerProfiles {
    private const val PREFIX = "dnschanger://"

    fun encode(
        name: String,
        ipv4Primary: String,
        ipv4Secondary: String,
        ipv6Primary: String,
        ipv6Secondary: String
    ): String {
        val json = JSONObject()
            .put("version", 1)
            .put("name", name.trim().ifBlank { "DNS Profile" }.take(80))
            .put("ipv4_primary", ipv4Primary.trim())
            .put("ipv4_secondary", ipv4Secondary.trim())
            .put("ipv6_primary", ipv6Primary.trim())
            .put("ipv6_secondary", ipv6Secondary.trim())
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return PREFIX + encoded
    }

    fun decode(raw: String): DnsChangerProfile? = runCatching {
        val value = raw.trim()
        require(value.startsWith(PREFIX, ignoreCase = true))
        val payload = value.substring(PREFIX.length).substringBefore('#').trim()
        require(payload.isNotEmpty() && payload.length <= 32_000)
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(json.optInt("version", 0) == 1)
        val v4a = json.optString("ipv4_primary", "").trim()
        val v4b = json.optString("ipv4_secondary", "").trim()
        val v6a = json.optString("ipv6_primary", "").trim()
        val v6b = json.optString("ipv6_secondary", "").trim()
        require(listOf(v4a, v4b, v6a, v6b).any { it.isNotBlank() })
        require(v4a.isBlank() || isValidIpv4(v4a))
        require(v4b.isBlank() || isValidIpv4(v4b))
        require(v6a.isBlank() || isValidIpv6(v6a))
        require(v6b.isBlank() || isValidIpv6(v6b))
        DnsChangerProfile(
            name = json.optString("name", "DNS Profile").trim().ifBlank { "DNS Profile" }.take(80),
            ipv4Primary = v4a,
            ipv4Secondary = v4b,
            ipv6Primary = v6a,
            ipv6Secondary = v6b
        )
    }.getOrNull()

    fun isValidIpv4(value: String): Boolean {
        val parts = value.trim().split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                (part.toIntOrNull() ?: 256) in 0..255
        }
    }

    fun isValidIpv6(value: String): Boolean {
        val candidate = value.trim()
        if (!candidate.contains(':') || candidate.contains('%') || candidate.length > 45) return false
        return runCatching { InetAddress.getByName(candidate) is Inet6Address }.getOrDefault(false)
    }
}
