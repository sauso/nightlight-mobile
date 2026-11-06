package com.sauso.nightlight

/**
 * The rules for a server's second address, the one the phone uses away from home (owner, 2026-11-06: the phone tries the
 * local address first and falls back to the server's Tailscale address, which the server tells it about).
 *
 * Pure Kotlin with no Android imports, so the JVM unit tests run exactly this code.
 *
 * Where it is stored: the saved server (ServerConfig `server_url`, the address the person typed or picked) stays the PRIMARY
 * address and is never rewritten by this. The Tailscale address is kept beside it under [key], so the old saved-server list
 * and the old single saved URL read exactly as before (and an older app version that finds these entries ignores them).
 */
object EndpointRules {

    // `https://<name>.ts.net`, nothing else: no port, no path, no credentials, no IP literal. The server only ever offers
    // its Tailscale name (GET /api/remote-access/tailscale/address), and Serve answers on 443. Lower case only, the form the
    // server sends. 255 is the bound on a whole address, a maximum DNS name being 253.
    private val TAILSCALE_URL = Regex("^https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.ts\\.net$")
    private const val MAX_URL_CHARS = 255

    fun isTailscaleUrl(url: String?): Boolean =
        url != null && url.length <= MAX_URL_CHARS && TAILSCALE_URL.matches(url)

    /** The SharedPreferences key of the Tailscale address that belongs to the saved server [primary]. */
    fun key(primary: String): String = "tailscale_for:$primary"
}
