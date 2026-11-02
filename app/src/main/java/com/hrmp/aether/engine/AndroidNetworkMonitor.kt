package com.tirexnet.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wgtunnel.backend.system.NetworkMonitor
import com.wgtunnel.backend.system.NetworkSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet6Address

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
            linkProperties: android.net.LinkProperties
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

    @Synchronized
    private fun refresh() {
        try {
            val active = cm.activeNetwork
            val activeCandidate = active?.let { createCandidate(it) }

            if (activeCandidate != null) {
                mutableState.value = activeCandidate.snapshot
                return
            }

            val candidates = cm.allNetworks.mapNotNull {
                createCandidate(it)
            }

            val selected = candidates.maxWithOrNull(
                compareBy<Candidate> { it.validated }
                    .thenBy { it.priority }
            )

            mutableState.value = selected?.snapshot

        } catch (_: Throwable) {
            mutableState.value = null
        }
    }

    private fun createCandidate(network: Network): Candidate? {
        val capabilities = cm.getNetworkCapabilities(network)
            ?: return null

        if (!capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN
            )
        ) return null

        if (!capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        ) return null

        val links = cm.getLinkProperties(network)

        // NetworkSnapshot requires a non-null Boolean.
        val hasIpv6: Boolean =
            links?.linkAddresses?.any { link ->
                val address = link.address

                address is Inet6Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isMulticastAddress &&
                    !address.isAnyLocalAddress
            } ?: false

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

        return Candidate(snapshot, validated, priority)
    }
}
