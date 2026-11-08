package com.sauso.nightlight

/**
 * The rules for a server's second address: its secure Tailscale address, which the server tells the phone about (owner,
 * 2026-11-06). Since the owner decision of 2026-11-09 ("we go Tailscale first") the phone opens that address first when it
 * answers and uses the saved address when it does not (EndpointChooser); before that, the saved address came first.
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
     * Anything that does not parse is not local-only.
     * Nothing on Android calls this since the launch went Tailscale first (its short off-Wi-Fi wait for the saved address went);
     * it stays, with its tests, for the planned iPhone "Find server" port (Codex plan review of Tailscale first, 2026-11-09).
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

    // How long a saved address that has ANSWERED waits for the Tailscale one at launch (EndpointChooser's grace), counted from
    // the start of the decision. A judgement, not a measurement (owner decision 2026-11-09, Tailscale first): Tailscale switched
    // on and healthy answers in well under a second, so a second is enough for it to win; a Tailscale path that is cold (relayed,
    // or the first request after a while idle) can take longer, and then the launch opens the saved address and the next
    // foreground start moves it to Tailscale (RoamRules.target, the upgrade).
    const val TAILSCALE_GRACE_MS = 1_000L

    // The same grace when the phone positively has no VPN at all (VpnPresent), so a Tailscale answer is not expected. A judgement,
    // not a measurement: with Tailscale off, opening the saved address should not be held up by more than a quarter of a second.
    const val TAILSCALE_GRACE_NO_VPN_MS = 250L

    /**
     * The grace for this launch. The VPN reading is a hint that can only SHORTEN the wait (its failure reads as "present", the
     * full grace): if it is wrong, the worst case is that a slower but valid Tailscale answer loses to the saved address, which
     * is the saved-first behaviour the app had before; the app never opens an unverified Tailscale address because of it
     * (Codex plan review of Tailscale first, 2026-11-09).
     */
    fun tailscaleGraceMs(vpnPresent: Boolean): Long = if (vpnPresent) TAILSCALE_GRACE_MS else TAILSCALE_GRACE_NO_VPN_MS

    /**
     * Whether [url] answers as this server: the ONE rule the launch's choice (MainActivity.chooseAddress) and the network
     * watcher's second look (NetworkRoamWatcher) both use, so "answers" cannot mean two things (Codex code review of Tailscale
     * first, 2026-11-09). For the saved server's Tailscale address ([tailscale]) an answer counts only with the id that address
     * was saved for: [wantedId] must exist and [fetchId] must report exactly it. A different id, no id, or no saved id all say
     * no (fails closed), so an address saved for one server never opens another, and [reachable] is never asked about it. Any
     * other address counts when [reachable] says so. The two network reads are injected (production: EndpointProbe.fetchHealth's
     * id and ServerConfigPlugin.isReachable), so this stays pure and the JVM tests run it.
     */
    fun addressAnswers(
        url: String,
        tailscale: String?,
        wantedId: String?,
        fetchId: (String) -> String?,
        reachable: (String) -> Boolean,
    ): Boolean =
        if (url == tailscale) wantedId != null && fetchId(url) == wantedId else reachable(url)

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

    /**
     * Whether a server's display name may be remembered for the setup page's saved list (owner, 2026-11-06: show the name, with
     * the address below it in smaller text, instead of only the address). Only for a server this phone already remembers, so the
     * names can never grow past the saved list, and only a name the server actually sent (DiscoveryRules.cleanName has already
     * bounded it to a displayable length). The name comes from the server's public /api/health, so the page shows it as TEXT only.
     */
    fun mayRememberName(knownServers: List<String>, url: String, name: String?): Boolean =
        !name.isNullOrBlank() && url in knownServers

    /** The stored names that belong to a remembered server, in the saved list's order; a forgotten server's name is never shown. */
    fun namesForKnown(stored: Map<String, String>, knownServers: List<String>): Map<String, String> =
        knownServers.mapNotNull { url -> stored[url]?.takeIf { it.isNotBlank() }?.let { url to it } }.toMap()

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
     * so it could already show anything; what it cannot do is change the saved address. Since Tailscale first (owner decision
     * 2026-11-09) an accepted address is opened AHEAD of the saved one whenever it answers with that id, at home too, where
     * before it was opened only when the saved address did not answer: such a page's address therefore wins more launches
     * than it used to. A known limit, unchanged in kind: the page could already show anything at the saved address.
     */
    fun checkIdentity(reference: DiscoveryRules.Health?, candidate: DiscoveryRules.Health?): AddCheck {
        if (reference == null) return AddCheck.UNREADABLE_SERVER
        if (candidate == null) return AddCheck.UNREACHABLE_ADDRESS
        if (reference.id != candidate.id) return AddCheck.DIFFERENT_SERVER
        return AddCheck.OK
    }
}
