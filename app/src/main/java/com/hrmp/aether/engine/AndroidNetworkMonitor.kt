package com.tirexnet.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.wgtunnel.backend.system.NetworkMonitor
import com.wgtunnel.backend.system.NetworkSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet6Address

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableState = MutableStateFlow<NetworkSnapshot?>(null)
    override val networkState: StateFlow<NetworkSnapshot?> = mutableState

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)
        override fun onLost(network: Network) = refresh(cm.activeNetwork)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh(network)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh(network)
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        refresh(cm.activeNetwork)
    }

    private fun refresh(network: Network?) {
        if (network == null) { mutableState.value = null; return }
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val links = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val usable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val ipv6 = links?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLoopbackAddress } ?: false
        mutableState.value = NetworkSnapshot(network.toString(), ipv6, usable, network)
    }
}
