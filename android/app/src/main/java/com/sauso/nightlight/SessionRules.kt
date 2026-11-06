package com.sauso.nightlight

import java.net.URI

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

    /** Whether a page may save a session: it must be an active page (the saved address or its Tailscale address). */
    fun maySave(primary: String?, savedTailscale: String?, page: String?, token: String?): Boolean =
        isPlausibleToken(token) && EndpointRules.isActivePage(primary, savedTailscale, page)

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
     * built with): scheme, host and port, the default port being 80 for http and 443 for https. A frame, or a page that the
     * address has redirected to on another port, is not the server the bridge was built for (review of phase D: the
     * fallback bridge on an old WebView can be called by any frame). Anything that does not parse is not the same origin.
     */
    fun sameOrigin(pageUrl: String?, serverUrl: String?): Boolean {
        val a = origin(pageUrl) ?: return false
        val b = origin(serverUrl) ?: return false
        return a == b
    }

    private fun origin(url: String?): Triple<String, String, Int>? {
        if (url == null) return null
        return try {
            val u = URI(url)
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase() ?: return null
            val port = if (u.port >= 0) u.port else when (scheme) { "http" -> 80; "https" -> 443; else -> return null }
            Triple(scheme, host, port)
        } catch (e: Exception) {
            null
        }
    }
}
