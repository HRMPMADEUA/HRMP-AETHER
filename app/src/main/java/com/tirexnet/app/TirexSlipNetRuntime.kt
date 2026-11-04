package com.tirexnet.app

import android.content.Context
import android.content.SharedPreferences
import app.slipnet.data.export.ConfigImporter
import app.slipnet.data.export.ImportResult
import app.slipnet.data.local.datastore.BufferSize
import app.slipnet.data.local.datastore.DnsWorkerMode
import app.slipnet.data.local.datastore.DomainRoutingMode
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.data.local.datastore.SshCipher
import app.slipnet.data.local.datastore.SplitTunnelingMode
import app.slipnet.domain.model.CongestionControl
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.DnsResolver
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.ResolverMode
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.SshAuthType
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.service.VpnConnectionManager
import app.slipnet.util.LockPasswordUtil
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.util.Locale

sealed interface TirexSlipNetImportResult {
    data class Ready(val profileName: String, val importedCount: Int, val warnings: List<String>) : TirexSlipNetImportResult
    data object NeedsPassword : TirexSlipNetImportResult
    data class Failure(val message: String) : TirexSlipNetImportResult
}

/** In-process bridge to SlipNet's database, settings and VPN service. No SlipNet UI is launched. */
class TirexSlipNetRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val entryPoint: TirexSlipNetEntryPoint by lazy {
        EntryPointAccessors.fromApplication(appContext, TirexSlipNetEntryPoint::class.java)
    }
    private val manager: VpnConnectionManager get() = entryPoint.slipNetConnectionManager()
    private val importer: ConfigImporter get() = entryPoint.slipNetConfigImporter()
    private val profiles: ProfileRepository get() = entryPoint.slipNetProfileRepository()
    private val settings: PreferencesDataStore get() = entryPoint.slipNetPreferences()
    private val localPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("tirexnet", Context.MODE_PRIVATE)
    }
    private var preparedProfile: ServerProfile? = null

    val connectionState get() = manager.connectionState

    fun isConnected(): Boolean = manager.connectionState.value is ConnectionState.Connected

    fun isConnectedOrConnecting(): Boolean = when (manager.connectionState.value) {
        is ConnectionState.Connected, is ConnectionState.Connecting, is ConnectionState.Disconnecting -> true
        else -> false
    }

    suspend fun importAndSave(raw: String, bundlePassword: String? = null): TirexSlipNetImportResult {
        return when (val parsed = importer.parseAndImport(
            input = raw.trim(),
            localDeviceId = manager.getDeviceId(),
            bundlePassword = bundlePassword
        )) {
            ImportResult.NeedsPassword -> TirexSlipNetImportResult.NeedsPassword
            is ImportResult.Error -> TirexSlipNetImportResult.Failure(parsed.message)
            is ImportResult.Success -> {
                val stored = profiles.getAllProfiles().first().toMutableList()
                var firstSaved: ServerProfile? = null
                var importedCount = 0
                for (original in parsed.profiles) {
                    val incoming = applyProfileDefaults(original, localPrefs)
                    val existing = stored.firstOrNull { saved ->
                        incoming.copy(
                            id = saved.id,
                            isActive = saved.isActive,
                            createdAt = saved.createdAt,
                            updatedAt = saved.updatedAt,
                            lastConnectedAt = saved.lastConnectedAt,
                            sortOrder = saved.sortOrder,
                            isPinned = saved.isPinned
                        ) == saved
                    }
                    val savedProfile = if (existing != null) {
                        existing
                    } else {
                        val id = profiles.saveProfile(incoming.copy(isActive = false))
                        profiles.getProfileById(id) ?: incoming.copy(id = id, isActive = false)
                    }
                    if (existing == null) {
                        stored.add(savedProfile)
                        importedCount++
                    }
                    if (firstSaved == null) firstSaved = savedProfile
                }
                val selected = firstSaved
                    ?: return TirexSlipNetImportResult.Failure("No valid SlipNet profile was found.")
                preparedProfile = selected
                TirexSlipNetImportResult.Ready(selected.name, importedCount, parsed.warnings)
            }
        }
    }

    fun preparedProfileNeedsPassword(): Boolean = preparedProfile?.isLocked == true

    fun verifyPreparedProfilePassword(password: String): Boolean {
        val profile = preparedProfile ?: return false
        if (!profile.isLocked) return true
        if (password.isBlank() || profile.lockPasswordHash.isBlank()) return false
        return runCatching { LockPasswordUtil.verifyPassword(password, profile.lockPasswordHash) }.getOrDefault(false)
    }

    fun connectPrepared() {
        val profile = preparedProfile ?: error("No SlipNet profile has been prepared.")
        when (manager.connectionState.value) {
            is ConnectionState.Connected, is ConnectionState.Connecting -> manager.reconnect(profile)
            else -> manager.connect(profile)
        }
    }

    fun disconnect() = manager.disconnect()

    /** Sync TIREXNET's settings dialog with SlipNet's actual PreferencesDataStore. */
    suspend fun applySettings(source: SharedPreferences) {
        val store = settings
        store.setAutoConnectOnBoot(source.getBoolean("slipnet_auto_connect_boot", false))
        store.setDebugLogging(source.getBoolean("slipnet_debug_logging", false))
        store.setVpnMtu(source.getInt("slipnet_mtu", 1280))
        store.setDnsTimeout(source.getInt("slipnet_dns_timeout", 5000))
        store.setConnectionTimeout(source.getInt("slipnet_timeout", 20) * 1000)
        store.setConnectionPoolSize(source.getInt("slipnet_connection_pool_size", 16))

        val bufferKb = source.getString("slipnet_buffer_size", "128 KB").orEmpty()
            .substringBefore(' ').toIntOrNull() ?: 128
        store.setBufferSize(when {
            bufferKb <= 64 -> BufferSize.SMALL
            bufferKb <= 256 -> BufferSize.MEDIUM
            else -> BufferSize.LARGE
        })
        store.setUploadLimitKbps(source.getInt("slipnet_upload_limit", 0))
        store.setDownloadLimitKbps(source.getInt("slipnet_download_limit", 0))
        store.setDisableQuic(source.getBoolean("slipnet_disable_quic", true))
        store.setBlockIpv6(source.getBoolean("slipnet_block_ipv6", true))
        store.setDnsWorkerMode(DnsWorkerMode.fromValue(source.getString("slipnet_dns_workers", "per_query").orEmpty()))

        val cipher = when (source.getString("slipnet_ssh_cipher", "Auto").orEmpty().lowercase(Locale.US)) {
            "aes-128-gcm" -> "aes128-gcm"
            "chacha20-poly1305" -> "chacha20"
            "aes-128-ctr (legacy)" -> "aes128-ctr"
            else -> "auto"
        }
        store.setSshCipher(SshCipher.fromValue(cipher))
        store.setSshCompression(source.getBoolean("slipnet_ssh_compression", false))
        if (source.getBoolean("slipnet_ssh_channels_custom", false)) {
            store.setSshMaxChannels(source.getInt("slipnet_ssh_channels", 16))
        } else {
            store.resetSshMaxChannelsToAuto()
        }
        store.setPreventDnsFallback(source.getBoolean("slipnet_prevent_dns_fallback", true))
        store.setAutoReconnect(source.getBoolean("slipnet_auto_reconnect", true))

        store.setProxyOnlyMode(source.getBoolean("slipnet_proxy_only", false))
        store.setKillSwitch(source.getBoolean("slipnet_kill_switch", false))
        store.setShowNotificationTraffic(source.getBoolean("slipnet_notification_traffic", true))
        store.setProxyListenAddress(source.getString("slipnet_proxy_host", "127.0.0.1").orEmpty())
        store.setProxyListenPort(source.getInt("slipnet_proxy_port", 1080))
        store.setProxyAuthEnabled(source.getBoolean("slipnet_proxy_auth", false))
        store.setProxyAuthUsername(source.getString("slipnet_proxy_user", "").orEmpty())
        store.setProxyAuthPassword(source.getString("slipnet_proxy_pass", "").orEmpty())
        store.setHttpProxyEnabled(source.getBoolean("slipnet_http_proxy_enabled", false))
        store.setHttpProxyPort(source.getInt("slipnet_http_proxy_port", 8080))
        store.setAppendHttpProxyToVpn(source.getBoolean("slipnet_http_proxy_append", false))
        store.setSleepTimerMinutes(source.getInt("slipnet_sleep_timer", 0))

        val remoteMode = if (source.getString("slipnet_remote_dns_mode", "Default").equals("Custom", true)) "custom" else "default"
        store.setRemoteDnsMode(remoteMode)
        store.setCustomRemoteDns(source.getString("slipnet_remote_dns_primary", "").orEmpty())
        store.setCustomRemoteDnsFallback(source.getString("slipnet_remote_dns_fallback", "").orEmpty())

        store.setGlobalResolverEnabled(source.getBoolean("slipnet_global_resolver_enabled", false))
        store.setGlobalResolverList(source.getString("slipnet_global_resolver_list", "").orEmpty())
        store.setDnsPoolEnabled(source.getBoolean("slipnet_dns_pool_enabled", false))
        store.setDnsPoolText(source.getString("slipnet_dns_pool_text", "").orEmpty())
        store.setDnsPoolFullVerification(source.getBoolean("slipnet_dns_pool_full_verification", false))
        store.setSplitTunnelingEnabled(source.getBoolean("slipnet_split_tunnel", false))
        store.setSplitTunnelingMode(SplitTunnelingMode.ALLOW)

        store.setDomainRoutingEnabled(source.getBoolean("slipnet_domain_routing_enabled", false))
        val domainMode = if (
            source.getString("slipnet_domain_routing_mode", "Bypass listed domains")
                .orEmpty().contains("only", true)
        ) DomainRoutingMode.ONLY_VPN else DomainRoutingMode.BYPASS
        store.setDomainRoutingMode(domainMode)
        val domains = source.getString("slipnet_domain_routing_domains", "").orEmpty()
            .lineSequence().map { it.trim().removePrefix("*.") }.filter { it.isNotBlank() }.toSet()
        store.setDomainRoutingDomains(domains)

        store.setGeoBypassEnabled(source.getBoolean("slipnet_geo_bypass_enabled", false))
        store.setGeoBypassCountry(source.getString("slipnet_geo_bypass_country", "IR").orEmpty().lowercase(Locale.US))
    }

    private fun applyProfileDefaults(profile: ServerProfile, prefs: SharedPreferences): ServerProfile {
        val sshChain = prefs.getBoolean("slipnet_ssh_chain", false)
        val sshAuth = when (prefs.getString("slipnet_ssh_auth_type", "Auto").orEmpty().lowercase(Locale.US)) {
            "private key", "password + private key", "key" -> SshAuthType.KEY
            else -> profile.sshAuthType
        }
        val dnsTransport = prefs.getString("slipnet_dns_transport", "UDP").orEmpty().lowercase(Locale.US)
        val resolverMode = when (prefs.getString("slipnet_resolver_mode", "Reliable").orEmpty().lowercase(Locale.US)) {
            "fast", "roundrobin" -> ResolverMode.ROUND_ROBIN
            else -> ResolverMode.FANOUT
        }
        val congestion = CongestionControl.fromValue(
            prefs.getString("slipnet_congestion", profile.congestionControl.value).orEmpty().lowercase(Locale.US)
        )
        val vlessUuid = prefs.getString("slipnet_vless_uuid", "").orEmpty()
            .takeUnless { it.isBlank() || it.startsWith("xxxx", true) }
        val resolvers = if (profile.resolvers.isEmpty()) {
            parseResolverList(prefs.getString("slipnet_resolvers", "").orEmpty())
        } else profile.resolvers

        return profile.copy(
            resolvers = resolvers,
            keepAliveInterval = if (profile.keepAliveInterval == 5000) prefs.getInt("slipnet_keepalive_ms", 5000) else profile.keepAliveInterval,
            congestionControl = if (profile.congestionControl == CongestionControl.BBR) congestion else profile.congestionControl,
            gsoEnabled = profile.gsoEnabled || prefs.getBoolean("slipnet_gso", false),
            dnsTransport = if (profile.dnsTransport == DnsTransport.UDP) DnsTransport.fromValue(dnsTransport) else profile.dnsTransport,
            dohUrl = profile.dohUrl.ifBlank { prefs.getString("slipnet_doh_url", "").orEmpty() },
            resolverMode = profile.resolverMode.takeIf { it != ResolverMode.ROUND_ROBIN } ?: resolverMode,
            rrSpreadCount = if (profile.rrSpreadCount == 3) prefs.getInt("slipnet_resolver_spread", 3).coerceIn(1, 5) else profile.rrSpreadCount,
            dnsPayloadSize = if (profile.dnsPayloadSize == 0) prefs.getInt("slipnet_dns_payload", 0).coerceIn(0, 1400) else profile.dnsPayloadSize,
            dnsAutoTune = profile.dnsAutoTune || prefs.getBoolean("slipnet_dns_auto_tune", false),
            noizdnsStealth = profile.noizdnsStealth || prefs.getBoolean("slipnet_noiz_stealth", false),
            vaydnsDnsttCompat = profile.vaydnsDnsttCompat || prefs.getBoolean("slipnet_vay_compat", false),
            vaydnsRecordType = if (profile.vaydnsRecordType == "txt") prefs.getString("slipnet_vay_record_type", "txt").orEmpty().lowercase(Locale.US) else profile.vaydnsRecordType,
            vaydnsMaxQnameLen = if (profile.vaydnsMaxQnameLen == 101) prefs.getInt("slipnet_vay_qname", 101).coerceIn(64, 255) else profile.vaydnsMaxQnameLen,
            vaydnsRps = if (profile.vaydnsRps == 0.0) prefs.getString("slipnet_vay_rps", "0").orEmpty().toDoubleOrNull()?.coerceIn(0.0, 1000.0) ?: 0.0 else profile.vaydnsRps,
            vaydnsIdleTimeout = if (profile.vaydnsIdleTimeout == 0) prefs.getInt("slipnet_vay_idle", 0).coerceIn(0, 86400) else profile.vaydnsIdleTimeout,
            vaydnsKeepalive = if (profile.vaydnsKeepalive == 0) prefs.getInt("slipnet_vay_keepalive", 0).coerceIn(0, 3600) else profile.vaydnsKeepalive,
            vaydnsUdpTimeout = if (profile.vaydnsUdpTimeout == 0) prefs.getInt("slipnet_vay_udp_timeout", 0).coerceIn(0, 60000) else profile.vaydnsUdpTimeout,
            vaydnsMaxNumLabels = if (profile.vaydnsMaxNumLabels == 0) prefs.getInt("slipnet_vay_max_labels", 0).coerceIn(0, 128) else profile.vaydnsMaxNumLabels,
            vaydnsClientIdSize = if (profile.vaydnsClientIdSize == 0) prefs.getInt("slipnet_vay_client_id", 0).coerceIn(0, 32) else profile.vaydnsClientIdSize,
            soarDiscovery = profile.soarDiscovery || prefs.getBoolean("slipnet_soar_discovery", false),
            soarCountry = profile.soarCountry.ifBlank { prefs.getString("slipnet_soar_country", "").orEmpty().lowercase(Locale.US) },
            soarTcpMode = if (profile.soarTcpMode == "auto") prefs.getString("slipnet_soar_tcp_mode", "auto").orEmpty().lowercase(Locale.US) else profile.soarTcpMode,
            sshUsername = if (sshChain) prefs.getString("slipnet_ssh_username", "").orEmpty().ifBlank { profile.sshUsername } else profile.sshUsername,
            sshPassword = if (sshChain) prefs.getString("slipnet_ssh_password", "").orEmpty().ifBlank { profile.sshPassword } else profile.sshPassword,
            sshHost = if (sshChain) prefs.getString("slipnet_ssh_host", "").orEmpty().ifBlank { profile.sshHost } else profile.sshHost,
            sshPort = if (sshChain) prefs.getInt("slipnet_ssh_port", profile.sshPort).coerceIn(1, 65535) else profile.sshPort,
            sshPrivateKey = prefs.getString("slipnet_ssh_private_key", "").orEmpty().ifBlank { profile.sshPrivateKey },
            sshKeyPassphrase = prefs.getString("slipnet_ssh_key_passphrase", "").orEmpty().ifBlank { profile.sshKeyPassphrase },
            sshAuthType = sshAuth,
            sshTlsEnabled = profile.sshTlsEnabled || prefs.getBoolean("slipnet_ssh_tls", false),
            sshTlsSni = profile.sshTlsSni.ifBlank { prefs.getString("slipnet_ssh_tls_sni", "").orEmpty() },
            sshHttpProxyHost = profile.sshHttpProxyHost.ifBlank { prefs.getString("slipnet_ssh_http_proxy_host", "").orEmpty() },
            sshHttpProxyPort = if (profile.sshHttpProxyPort == 8080) prefs.getInt("slipnet_ssh_http_proxy_port", 8080).coerceIn(1, 65535) else profile.sshHttpProxyPort,
            sshHttpProxyCustomHost = profile.sshHttpProxyCustomHost.ifBlank { prefs.getString("slipnet_ssh_http_proxy_custom_host", "").orEmpty() },
            sshWsEnabled = profile.sshWsEnabled || prefs.getBoolean("slipnet_ssh_ws", false),
            sshWsPath = if (profile.sshWsPath == "/") prefs.getString("slipnet_ssh_ws_path", "/").orEmpty() else profile.sshWsPath,
            sshWsUseTls = profile.sshWsUseTls && prefs.getBoolean("slipnet_ssh_ws_tls", true),
            sshWsCustomHost = profile.sshWsCustomHost.ifBlank { prefs.getString("slipnet_ssh_ws_host", "").orEmpty() },
            sshPayload = profile.sshPayload.ifBlank { prefs.getString("slipnet_ssh_payload", "").orEmpty() },
            vlessUuid = profile.vlessUuid.ifBlank { vlessUuid.orEmpty() },
            vlessSecurity = if (profile.vlessSecurity == "tls") prefs.getString("slipnet_vless_security", "tls").orEmpty() else profile.vlessSecurity,
            vlessTransport = if (profile.vlessTransport == "ws") prefs.getString("slipnet_vless_transport", "ws").orEmpty() else profile.vlessTransport,
            vlessWsPath = if (profile.vlessWsPath == "/") prefs.getString("slipnet_vless_ws_path", "/").orEmpty() else profile.vlessWsPath,
            cdnIp = profile.cdnIp.ifBlank { prefs.getString("slipnet_vless_cdn_ip", "").orEmpty() },
            cdnPort = if (profile.cdnPort == 443) prefs.getInt("slipnet_vless_cdn_port", 443).coerceIn(1, 65535) else profile.cdnPort,
            sniFragmentEnabled = profile.sniFragmentEnabled && prefs.getBoolean("slipnet_vless_sni_fragment", true),
            sniFragmentStrategy = if (profile.sniFragmentStrategy == "micro") prefs.getString("slipnet_vless_sni_strategy", "micro").orEmpty() else profile.sniFragmentStrategy,
            sniFragmentDelayMs = if (profile.sniFragmentDelayMs == 300) prefs.getInt("slipnet_vless_sni_delay", 300).coerceIn(0, 5000) else profile.sniFragmentDelayMs,
            tcpMaxSeg = if (profile.tcpMaxSeg == 0) prefs.getInt("slipnet_vless_tcp_mss", 0).coerceIn(0, 1400) else profile.tcpMaxSeg,
            vlessSni = profile.vlessSni.ifBlank { prefs.getString("slipnet_vless_sni", "").orEmpty() },
            chPaddingEnabled = profile.chPaddingEnabled || prefs.getBoolean("slipnet_vless_ch_padding", false),
            wsHeaderObfuscation = profile.wsHeaderObfuscation && prefs.getBoolean("slipnet_vless_ws_obfuscation", true),
            wsPaddingEnabled = profile.wsPaddingEnabled || prefs.getBoolean("slipnet_vless_ws_padding", false),
            naivePort = if (profile.naivePort == 443) prefs.getInt("slipnet_naive_port", 443).coerceIn(1, 65535) else profile.naivePort,
            naiveUsername = profile.naiveUsername.ifBlank { prefs.getString("slipnet_naive_username", "").orEmpty() },
            naivePassword = profile.naivePassword.ifBlank { prefs.getString("slipnet_naive_password", "").orEmpty() }
        )
    }

    private fun parseResolverList(raw: String): List<DnsResolver> {
        return raw.split(',', '\n', ';').mapNotNull { item ->
            val value = item.trim()
            if (value.isBlank()) return@mapNotNull null
            val host: String
            val port: Int
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end <= 1) return@mapNotNull null
                host = value.substring(1, end)
                port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: 53
            } else if (value.count { it == ':' } == 1) {
                host = value.substringBefore(':')
                port = value.substringAfter(':').toIntOrNull() ?: 53
            } else {
                host = value
                port = 53
            }
            if (host.isBlank() || port !in 1..65535) null else DnsResolver(host, port)
        }.distinctBy { "${it.host}:${it.port}" }.take(100)
    }
}
