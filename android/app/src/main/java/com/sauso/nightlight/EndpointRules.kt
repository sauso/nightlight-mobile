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

    /**
     * True when [url]'s host can only be reached from a local network: a private IPv4 address (10/8, 172.16/12, 192.168/16) or
     * a `.local` name. A public name or address (a domain with HTTPS, say) is NOT local-only: it answers on mobile data too.
     * Anything that does not parse is not local-only (so the ordinary wait applies).
     */
    fun isLocalOnlyAddress(url: String?): Boolean {
        if (url == null) return false
        val host = try {
            java.net.URI(url).host
        } catch (e: Exception) {
            null
        } ?: return false
        return DiscoveryRules.isPrivateIpv4(host) || host.lowercase().endsWith(".local")
    }

    // How long a launch waits for a saved address that says nothing, when the phone cannot be on its network (below). A
    // judgement, not a measurement: a reachable home server answers in milliseconds and even a slow Wi-Fi in well under a
    // second, so this is generous for a phone that merely just joined Wi-Fi, and short enough that the Tailscale fallback
    // opens in about a second instead of three.
    const val OFF_NETWORK_WAIT_MS = 1_000L

    /**
     * How long the launch gate waits for the saved address before it uses the Tailscale one. The short wait applies only when
     * ALL of: a Tailscale address is saved (otherwise there is nothing to switch to, so the saved address keeps its whole
     * chance), the phone is not on Wi-Fi or Ethernet, and the saved address is one only a local network can answer. Every other
     * case waits the full [fullMs]. Away from home on mobile data, a home address cannot answer: waiting three seconds for it
     * was the pause the owner saw on the first phone test (2026-11-06).
     */
    fun localWaitMs(saved: String?, tailscale: String?, onLocalNetwork: Boolean, fullMs: Long): Long =
        if (tailscale != null && !onLocalNetwork && isLocalOnlyAddress(saved)) minOf(OFF_NETWORK_WAIT_MS, fullMs) else fullMs

    /**
     * Whether `save` may take a REMEMBERED server whose own address did not answer, because the Tailscale address saved for it
     * did, and that address reported the id it was saved for. This is what lets "Change server" get back in from cellular
     * (owner, 2026-11-06): the setup page lists only the saved home address, which cannot answer there, so without this the only
     * way back was to type the Tailscale address by hand.
     *
     * Every condition must hold: the address is exactly a server this phone remembers (a typed address that merely resembles
     * one is not), it has a Tailscale address of the one accepted shape and a stored id, and the live id is that same id. An id
     * is public and proves nothing alone (see [checkIdentity]); what it adds here is that the address was learned from this
     * server earlier, and nothing is saved from it that was not already saved.
     */
    fun canReconnectViaTailscale(knownServers: List<String>, url: String?, tailscaleUrl: String?, wantedId: String?, liveId: String?): Boolean =
        url != null && url in knownServers && isTailscaleUrl(tailscaleUrl) && wantedId != null && liveId != null && liveId == wantedId

    /**
     * Whether a Tailscale address saved for [wantedId] still belongs to the server that now answers at the saved address
     * ([liveId], read when the saved address is next confirmed). A READABLE different id means another machine took over that
     * home address (a DHCP lease handed on, say), so the old Tailscale address would open the wrong server and must go. An
     * unreadable id keeps it: not knowing is not a reason to lose the way back in. (Change server used to drop the address
     * unconditionally to avoid exactly this; keeping it and checking here is what lets Change server work away from home.)
     */
    fun pairStillValid(wantedId: String?, liveId: String?): Boolean = wantedId == null || liveId == null || wantedId == liveId

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
