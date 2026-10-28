package com.tirexnet.app.engine

enum class ConfigEngine { XRAY, WIREGUARD, AMNEZIAWG, UNKNOWN }

object ConfigDetector {
    fun detect(raw: String): ConfigEngine {
        val s = raw.trim()
        if (s.isEmpty()) return ConfigEngine.UNKNOWN
        val lower = s.lowercase()
        if (lower.startsWith("vless://") || lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") || lower.startsWith("ss://") ||
            (s.startsWith("{") && lower.contains("\"outbounds\""))) return ConfigEngine.XRAY
        if (lower.contains("[interface]") && lower.contains("[peer]")) {
            val awg = Regex("(?im)^\\s*(jc|jmin|jmax|s1|s2|s3|s4|h1|h2|h3|h4|i1|i2|i3|i4|i5)\\s*=").containsMatchIn(s)
            return if (awg) ConfigEngine.AMNEZIAWG else ConfigEngine.WIREGUARD
        }
        return ConfigEngine.UNKNOWN
    }
}
