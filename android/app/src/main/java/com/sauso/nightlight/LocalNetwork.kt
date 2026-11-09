package com.sauso.nightlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether this phone is on a local network at all: Wi-Fi or Ethernet. Mobile data only means a home-network address cannot
 * answer, which the "Look for my Nightlight" search uses. (The launch gate used it too until it went Tailscale first, owner
 * decision 2026-11-09; it now reads VpnPresent instead.)
 *
 * FAILS OPEN (true) when the check itself fails: it only chooses how long to wait or which message to show, and the
 * ordinary rules stand either way. Needs ACCESS_NETWORK_STATE (AndroidManifest.xml). Glue over ConnectivityManager: no JVM
 * test reaches it, the decision it feeds is tested in EndpointRulesTest.
 */
object LocalNetwork {
    fun isOn(context: Context): Boolean {
        val cm = try {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        } ?: return true
        return try {
            // getAllNetworks is deprecated since API 31 in favour of callbacks; a one-off question needs no callback.
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        } catch (e: Exception) {
            true
        }
    }
}
