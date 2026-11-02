package com.tirexnet.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wgtunnel.backend.system.NetworkMonitor
import com.wgtunnel.backend.system.NetworkSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet6Address

/**
 * Tracks the physical internet connection used by WireGuard.
 *
 * Important:
 * Android reports the active VPN as the default network after a VPN
 * has been established. Passing that VPN network back to WireGuard can
 * cause tunnel recursion and a connected-without-traffic condition.
 *
 * This monitor therefore accepts only networks with NET_CAPABILITY_NOT_VPN.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val appContext = context.applicationContext

    private val cm =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val mutableState = MutableStateFlow<NetworkSnapshot?>(null)

    override val networkState: StateFlow<NetworkSnapshot?> = mutableState

    private data class Candidate(
        val snapshot: NetworkSnapshot,
        val validated: Boolean,
        val priority: Int
    )

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            refresh()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
        ) {
            refresh()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        runCatching {
            cm.registerNetworkCallback(request, callback)
        }

        refresh()
    }

    /**
     * Selects the real underlying network.
     *
     * The current default network is preferred only if it is not a VPN.
     * Otherwise, the best available physical internet network is selected.
     */
    @Synchronized
    private fun refresh() {
        try {
            val active = runCatching {
                cm.activeNetwork
            }.getOrNull()

            // Prefer the current default network only when it is physical.
            val activeCandidate = active?.let { createCandidate(it) }

            if (activeCandidate != null) {
                mutableState.value = activeCandidate.snapshot
                return
            }

            // The default network may be the VPN itself.
            // Search all available networks and explicitly reject VPN networks.
            val candidates = runCatching {
                cm.allNetworks
                    .mapNotNull { network ->
                        createCandidate(network)
                    }
            }.getOrDefault(emptyList())

            val selected = candidates.maxWithOrNull(
                compareBy<Candidate> { it.validated }
                    .thenBy { it.priority }
            )

            mutableState.value = selected?.snapshot

        } catch (_: SecurityException) {
            mutableState.value = null
        } catch (_: Throwable) {
            mutableState.value = null
        }
    }

    private fun createCandidate(network: Network): Candidate? {
        val capabilities = runCatching {
            cm.getNetworkCapabilities(network)
        }.getOrNull() ?: return null

        // Never use a VPN network as the WireGuard underlay.
        if (!capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN
            )
        ) {
            return null
        }

        // The underlying network must support internet access.
        if (!capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        ) {
            return null
        }

        val links = runCatching {
            cm.getLinkProperties(network)
        }.getOrNull()

        val hasIpv6: Boolean? = links?.linkAddresses?.any { link ->
            val address = link.address

            address is Inet6Address &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isMulticastAddress &&
                !address.isAnyLocalAddress
        }

        val snapshot = NetworkSnapshot(
            network.toString(),
            hasIpv6,
            true,
            network
        )

        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )

        val priority = when {
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) -> 3

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ) -> 2

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) -> 1

            else -> 0
        }

        return Candidate(
            snapshot = snapshot,
            validated = validated,
            priority = priority
        )
    }
}
