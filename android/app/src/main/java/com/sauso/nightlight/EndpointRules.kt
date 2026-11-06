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

    /**
     * The key of the server id that Tailscale address was saved for. The address is only ever opened if the server answering
     * there still reports THIS id (MainActivity), so an address saved for one server cannot end up opening another if the saved
     * address is later handed to a different machine (found in the review of the Tailscale fallback: a stale entry plus a saved
     * address that has gone quiet would otherwise open the old server).
     */
    fun idKey(primary: String): String = "tailscale_id:$primary"

    /** Why addEndpoint accepted or refused a request. Reported so a test (and a log line) can tell the refusals apart. */
    enum class AddCheck { OK, NOT_A_TAILSCALE_ADDRESS, NOT_THIS_PAGE, UNREADABLE_SERVER, UNREACHABLE_ADDRESS, DIFFERENT_SERVER }

    /**
     * The checks that need no network: the address has the one accepted shape, and the page asking is the saved server or the
     * Tailscale address already saved for it ([page] is what the bridge was built with). The bundled setup page has no server
     * ([page] null) and is refused, as is any other address.
     */
    fun checkPage(url: String?, primary: String?, savedTailscale: String?, page: String?): AddCheck {
        if (!isTailscaleUrl(url)) return AddCheck.NOT_A_TAILSCALE_ADDRESS
        if (!isActivePage(primary, savedTailscale, page)) return AddCheck.NOT_THIS_PAGE
        return AddCheck.OK
    }

    /** True when [page] (what the bridge was built with) is the saved server or the Tailscale address saved for it. */
    fun isActivePage(primary: String?, savedTailscale: String?, page: String?): Boolean {
        if (primary == null || page == null) return false
        return page == primary || (savedTailscale != null && page == savedTailscale)
    }

    /**
     * The check that needs the network's answers: the server the page is on ([reference]) and the offered address
     * ([candidate]) both reported an id, and it is the same one. This is an identity CLAIM, not proof: /api/health is public, so
     * a page that is itself hostile can name an address that repeats the id. That page is the server the person chose to use,
     * so it could already show anything; what it cannot do is change the saved address, and the address is opened only when
     * the saved one is not answering.
     */
    fun checkIdentity(reference: DiscoveryRules.Health?, candidate: DiscoveryRules.Health?): AddCheck {
        if (reference == null) return AddCheck.UNREADABLE_SERVER
        if (candidate == null) return AddCheck.UNREACHABLE_ADDRESS
        if (reference.id != candidate.id) return AddCheck.DIFFERENT_SERVER
        return AddCheck.OK
    }
}
