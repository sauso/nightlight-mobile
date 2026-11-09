package com.sauso.nightlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether this phone has a VPN up at all: any of its networks has TRANSPORT_VPN (the Tailscale app on Android runs as a VPN).
 * The launch gate (MainActivity.chooseAddress) uses it only to SHORTEN how long a saved address that has answered waits for the
 * Tailscale one (EndpointRules.tailscaleGraceMs): with no VPN, a Tailscale answer is not expected, so the saved address opens
 * after a quarter second instead of a second (owner decision 2026-11-09, Tailscale first).
 *
 * A hint, not proof. Any VPN counts, not only Tailscale: another VPN costs the full second, nothing more. Unconfirmed on a
 * phone: whether a VPN that leaves this app out (a per-app VPN, Tailscale's excluded apps) is listed here; if it is, the full
 * second is waited for nothing, and if it is not, Tailscale cannot answer this app anyway.
 *
 * FAILS OPEN (true, "present") when the check itself fails: that keeps the full grace, so a wrong reading can only delay the
 * saved address, never open a wrong address (a Tailscale answer still has to carry the id it was saved for). Needs
 * ACCESS_NETWORK_STATE (AndroidManifest.xml, already declared for LocalNetwork). Kept apart from LocalNetwork, which "Look for my
 * Nightlight" uses unchanged. Glue over ConnectivityManager: no JVM test reaches it; the rule it feeds is tested in
 * EndpointRulesTest.
 */
object VpnPresent {
    fun isOn(context: Context): Boolean {
        val cm = try {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        } ?: return true
        return try {
            // getAllNetworks is deprecated since API 31 in favour of callbacks; a one-off question needs no callback (as in
            // LocalNetwork).
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (e: Exception) {
            true
        }
    }
}
