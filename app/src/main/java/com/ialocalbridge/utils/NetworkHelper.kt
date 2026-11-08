package com.ialocalbridge.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelper {
    /** IPv4 of an actual Wi-Fi network, not a cellular/VPN address or loopback. */
    fun getWifiIPAddress(context: Context): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.asSequence().filter { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.flatMap { network -> cm.getLinkProperties(network)?.linkAddresses?.asSequence() ?: emptySequence() }
            .map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    /** Used only as a compact status in the overlay; prefer Wi-Fi when available. */
    fun getIPAddress(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) { "127.0.0.1" }
}
