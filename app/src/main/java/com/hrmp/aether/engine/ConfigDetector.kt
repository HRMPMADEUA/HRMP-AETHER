package com.tirexnet.app.engine

enum class ConfigEngine { XRAY, WIREGUARD, AMNEZIAWG, OPENVPN, MHRV, DNS_TUNNEL, COTTENDNS, MASTERDNS, DNS_CHANGER, SNI_SPOOF, SLIPNET, UNKNOWN }

object ConfigDetector {
    fun detect(raw: String): ConfigEngine {
        val s = raw.trim()
        if (s.isEmpty()) return ConfigEngine.UNKNOWN
        val lower = s.lowercase()
        if (lower.startsWith("slipnet://") || lower.startsWith("slipnet-enc://") || lower.startsWith("slipnet-bundle-enc://")) {
            return if (EngineProfiles.decodeSlipNet(s) != null) ConfigEngine.SLIPNET else ConfigEngine.UNKNOWN
        }
        if (lower.startsWith("dnschanger://")) return if (DnsChangerProfiles.decode(s) != null) ConfigEngine.DNS_CHANGER else ConfigEngine.UNKNOWN
        if (lower.startsWith("snispoof://")) return if (EngineProfiles.decodeSniSpoof(s) != null) ConfigEngine.SNI_SPOOF else ConfigEngine.UNKNOWN
        if (lower.startsWith("mhrv-rs://")) return if (EngineProfiles.decodeMhrv(s) != null) ConfigEngine.MHRV else ConfigEngine.UNKNOWN
        if (lower.startsWith("cottendns://")) return if (EngineProfiles.decodeCottenDns(s) != null) ConfigEngine.COTTENDNS else ConfigEngine.UNKNOWN
        if (lower.startsWith("masterdns://")) return if (MasterDnsProfiles.decode(s) != null) ConfigEngine.MASTERDNS else ConfigEngine.UNKNOWN
        if (lower.startsWith("stormdns://")) return if (EngineProfiles.decodeStormDns(s) != null) ConfigEngine.DNS_TUNNEL else ConfigEngine.UNKNOWN
        if (lower.startsWith("vless://") || lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") || lower.startsWith("ss://") ||
            (s.startsWith("{") && lower.contains("\"outbounds\""))) return ConfigEngine.XRAY
        if (lower.contains("[interface]") && lower.contains("[peer]")) {
            val awg = Regex("(?im)^\\s*(jc|jmin|jmax|s1|s2|s3|s4|h1|h2|h3|h4|i1|i2|i3|i4|i5)\\s*=").containsMatchIn(s)
            return if (awg) ConfigEngine.AMNEZIAWG else ConfigEngine.WIREGUARD
        }
        // OpenVPN .ovpn: a remote line plus either "client"/"dev tun" or an inline <ca> block.
        if (Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(s) &&
            (Regex("(?im)^\\s*(client|dev\\s+tun\\d*|proto\\s+(udp|tcp))\\b").containsMatchIn(s) || lower.contains("<ca>"))
        ) return ConfigEngine.OPENVPN
        return ConfigEngine.UNKNOWN
    }
}
