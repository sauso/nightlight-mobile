package com.sauso.nightlight

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Reads a server's `/api/health` and says who answered: its id and name, or null. Used to check that a Tailscale address the
 * server offered is the SAME server the app has open (ServerConfig.addEndpoint): the ids must be equal.
 *
 * Unlike HealthProbe (discovery, private IPv4 only, a raw socket), this one speaks to a name, and over https, so it uses
 * HttpURLConnection, with what it can switch off: redirects are NOT followed (the 200 must come from that address itself),
 * no proxy, and only the first [MAX_BODY_BYTES] of a body are read. Known limit, stated: Capacitor installs a process-wide
 * cookie handler that HttpURLConnection consults, so a Set-Cookie on the reply could be stored. Nightlight sets no cookies
 * (its sign-in is a header), and the addresses this reads are the server the app already shows and the Tailscale name that
 * server announced, never an address that came from the network.
 *
 * Never throws. Pure java.net, so a JVM test runs it against a local server.
 */
object EndpointProbe {
    const val MAX_BODY_BYTES = 4 * 1024

    fun fetchHealth(url: String, timeoutMs: Int): DiscoveryRules.Health? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$url/api/health").openConnection(Proxy.NO_PROXY) as HttpURLConnection)
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val declared = conn.contentLengthLong
            if (declared > MAX_BODY_BYTES) return null
            val buffer = ByteArray(MAX_BODY_BYTES + 1)
            var total = 0
            conn.inputStream.use { stream ->
                while (total < buffer.size) {
                    val n = stream.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
            }
            if (total > MAX_BODY_BYTES) return null
            DiscoveryRules.parseHealth(String(buffer, 0, total, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* nothing to do */ }
        }
    }
}
