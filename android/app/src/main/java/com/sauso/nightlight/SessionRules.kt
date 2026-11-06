package com.sauso.nightlight

/**
 * The rules for keeping ONE sign-in across a server's two addresses (owner, 2026-11-06; plan phase D, reviewed by Codex,
 * Opus and agy before it was built). The session token is also kept in app-private native storage ([SessionVault]) so the
 * page on the Tailscale address can sign in without asking again.
 *
 * Pure Kotlin with no Android imports, so the JVM unit tests run exactly this code. Every function fails CLOSED.
 *
 * THE THREAT THESE RULES ANSWER. A server's id is public (`/api/health`, the mDNS announcement), so an id proves nothing:
 * a hostile device at the saved plain-http address, or an https saved address on a hijacked domain, can repeat it. So the
 * stored session is handed ONLY to the page that is the saved TAILSCALE address (an `https://<name>.ts.net` name with a
 * certificate whose id was checked when it was added), and only while everything the entry was stamped with is still in
 * force (the saved address, the Tailscale address, the Tailscale id) AND that page's live id still equals it.
 */
object SessionRules {

    // A Nightlight session token is a JWT (three base64url parts). 4 KB is far past a real one (about 250 characters); it
    // only bounds what a page can make the app store.
    const val MAX_TOKEN_CHARS = 4096
    private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun isPlausibleToken(token: String?): Boolean =
        token != null && token.length <= MAX_TOKEN_CHARS && TOKEN_SHAPE.matches(token)

    /**
     * What an entry is stamped with when its token is saved: the saved address (also its key), and the Tailscale address
     * and id that were in force then (null when none was saved yet: such an entry is never handed out, and is stamped again
     * by the next save, which the web app makes after it has learned the Tailscale address).
     */
    data class Bindings(val savedUrl: String, val tailscaleUrl: String?, val tailscaleId: String?)

    /** What is stored (encrypted): the token and the bindings it was saved under. */
    data class Entry(val token: String, val bindings: Bindings)

    /**
     * The bindings a token is stamped with when the web app saves it. The web app says which Tailscale address ITS OWN SERVER
     * just offered ([claimedTailscale], read from the authenticated `GET /api/remote-access/tailscale/address`; null when it
     * could not be read or the server offers none). The stamp keeps the Tailscale address and id only when the two agree.
     *
     * WHY (Opus code review of phase D): `addEndpoint` can be called by the page at the SAVED address, and an id is public, so
     * a hostile device that answered there once could plant an attacker's `https://<x>.ts.net` as the saved Tailscale address.
     * If the person's next real sign-in were stamped with whatever is saved, the token would be handed to the attacker's page.
     * The real server never offers the planted address, so the claim differs, the stamp carries no Tailscale binding, and the
     * entry is never handed out (it fails closed; the next sign-in or start, once the real address has replaced the planted one,
     * saves it again). An older web app that sends no claim gets no binding either.
     */
    fun stampFor(current: Bindings, claimedTailscale: String?): Bindings {
        val claimed = claimedTailscale?.trim()?.trimEnd('/')
        return if (claimed != null && claimed == current.tailscaleUrl) current else Bindings(current.savedUrl, null, null)
    }

    /**
     * Whether a call from the page the WebView is really showing ([webViewUrl]) may SAVE a session or ASK for one: there is a
     * saved server, the bridge's server is the saved address or the Tailscale address saved for it ([bridgeUrl], exact string,
     * as EndpointRules.isActivePage), AND the page shown has that same origin (an old WebView's fallback bridge can be called
     * by any frame, and a redirect to another port is another origin). Review of the plan and of the code.
     */
    fun mayAct(current: Bindings?, bridgeUrl: String?, webViewUrl: String?): Boolean =
        current != null && EndpointRules.isActivePage(current.savedUrl, current.tailscaleUrl, bridgeUrl) && sameOrigin(webViewUrl, bridgeUrl)

    /**
     * Whether a call may CLEAR the saved session. Looser than [mayAct] on purpose: a clear only deletes (and needs the stored
     * token when one is given), and it must still work after the Tailscale address was removed or replaced while a page on it is
     * open, or a sign-out there would leave the entry behind (agy's review of the code). So: a saved server, a real server page
     * (the bridge was built for one, so the bundled setup page is refused) and a page with that same origin.
     */
    fun mayClear(current: Bindings?, bridgeUrl: String?, webViewUrl: String?): Boolean =
        current != null && bridgeUrl != null && sameOrigin(webViewUrl, bridgeUrl)

    /**
     * Whether a request for the session comes from the page that IS the saved Tailscale address (and is really showing it).
     * Anything else is told "no session" without a network call. [checkDelivery] then decides on the entry itself.
     */
    fun isTailscalePage(current: Bindings?, bridgeUrl: String?, webViewUrl: String?): Boolean =
        current != null && bridgeUrl != null && current.tailscaleUrl != null && bridgeUrl == current.tailscaleUrl &&
            EndpointRules.isTailscaleUrl(bridgeUrl) && sameOrigin(webViewUrl, bridgeUrl)

    enum class Delivery { OK, NOT_ACTIVE_PAGE, NOT_THE_TAILSCALE_PAGE, NO_ENTRY, NO_TAILSCALE_BINDING, BINDINGS_CHANGED, ID_MISMATCH }

    /**
     * Whether the stored [entry] may be handed to [page].
     *  - [current] is the saved address and the Tailscale address and id saved for it RIGHT NOW (null when nothing is saved);
     *  - [liveId] is the id the page's own server just reported at `/api/health` (null when it could not be read).
     * Every condition must hold; the first that does not is the answer, so a test can tell the refusals apart.
     */
    fun checkDelivery(page: String?, current: Bindings?, entry: Entry?, liveId: String?): Delivery {
        if (page == null || current == null) return Delivery.NOT_ACTIVE_PAGE
        // Only the Tailscale address is ever handed a session: never the saved address (http, or an https one that could
        // have changed hands) and never anything else.
        val ts = current.tailscaleUrl
        if (ts == null || page != ts || !EndpointRules.isTailscaleUrl(page)) return Delivery.NOT_THE_TAILSCALE_PAGE
        if (entry == null) return Delivery.NO_ENTRY
        // An entry saved before the Tailscale address was learned carries no binding, so it cannot be matched to anything.
        if (entry.bindings.tailscaleUrl == null || entry.bindings.tailscaleId == null) return Delivery.NO_TAILSCALE_BINDING
        if (current.tailscaleId == null) return Delivery.NO_TAILSCALE_BINDING
        // Changing the saved address or the Tailscale address (or its id) after the token was saved makes the entry fail
        // closed: only saveSession(token), which needs the token, can stamp it again.
        if (entry.bindings != current) return Delivery.BINDINGS_CHANGED
        if (liveId == null || liveId != current.tailscaleId) return Delivery.ID_MISMATCH
        return Delivery.OK
    }

    /**
     * Whether [pageUrl] (what the WebView is showing right now) is at the same origin as [serverUrl] (what the bridge was
     * built with): scheme, host and port, the default port being 80 for http and 443 for https. What this closes: a main frame
     * that has moved to another port or host since the bridge was built (a redirect, a link). It does NOT tell a child frame from
     * its parent: the old-WebView fallback bridge cannot say which frame called (`webView.url` is the top page), a stated limit.
     * Anything that does not parse is not the same origin.
     */
    fun sameOrigin(pageUrl: String?, serverUrl: String?): Boolean {
        val a = origin(pageUrl) ?: return false
        val b = origin(serverUrl) ?: return false
        return a == b
    }

    // Not java.net.URI: it returns no host for a name with an underscore (`http://my_server:4000`) and throws on a fragment with
    // `|`, `{` or `}`, and either would silently refuse every save and clear from a real saved address (Opus review). The
    // origin is the part before the first `/`, `?` or `#`: scheme, host (no `@`: a userinfo form is not an origin we accept) and
    // an optional port.
    private val ORIGIN_SHAPE = Regex("^(https?)://(\\[[0-9A-Fa-f:.]+\\]|[^/?#:@\\s]+)(?::(\\d{1,5}))?(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

    private fun origin(url: String?): Triple<String, String, Int>? {
        if (url == null) return null
        val m = ORIGIN_SHAPE.matchEntire(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: if (scheme == "http") 80 else 443
        if (port > 65535) return null
        return Triple(scheme, m.groupValues[2].lowercase(), port)
    }
}
