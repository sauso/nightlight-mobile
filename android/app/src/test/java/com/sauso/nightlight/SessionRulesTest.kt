package com.sauso.nightlight

import com.sauso.nightlight.SessionRules.Bindings
import com.sauso.nightlight.SessionRules.Delivery
import com.sauso.nightlight.SessionRules.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may be handed the saved sign-in (plan phase D, reviewed by Codex, Opus and agy). Each refusal below is one condition of
 * [SessionRules.checkDelivery] broken on its own against a case that is accepted, so a rule dropped from the function fails
 * exactly one test. The public server id proves nothing, which is why the page and the bindings matter as much as the id.
 */
class SessionRulesTest {

    private val saved = "http://192.168.1.50:4000"
    private val ts = "https://nightlight.example.ts.net"
    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val token = "aaa.bbb.ccc"

    private val current = Bindings(saved, ts, id)
    private val entry = Entry(token, current)

    private fun delivery(
        page: String? = ts,
        now: Bindings? = current,
        stored: Entry? = entry,
        liveId: String? = id,
    ) = SessionRules.checkDelivery(page, now, stored, liveId)

    // ---- delivery ----

    @Test
    fun `the one good case is delivered`() {
        assertEquals(Delivery.OK, delivery())
    }

    @Test
    fun `the saved address is never handed a session, even plain http or https`() {
        assertEquals(Delivery.NOT_THE_TAILSCALE_PAGE, delivery(page = saved))
        val httpsSaved = "https://nightlight.example.com"
        val b = Bindings(httpsSaved, ts, id)
        assertEquals("an https saved address could have changed hands", Delivery.NOT_THE_TAILSCALE_PAGE,
            delivery(page = httpsSaved, now = b, stored = Entry(token, b)))
    }

    @Test
    fun `any other page is refused`() {
        assertEquals(Delivery.NOT_THE_TAILSCALE_PAGE, delivery(page = "https://other.example.ts.net"))
        assertEquals(Delivery.NOT_THE_TAILSCALE_PAGE, delivery(page = "https://evil.example"))
        assertEquals(Delivery.NOT_ACTIVE_PAGE, delivery(page = null))
        assertEquals(Delivery.NOT_ACTIVE_PAGE, delivery(now = null))
    }

    @Test
    fun `a Tailscale address that is not of the accepted shape is refused even if it is the saved one`() {
        val odd = "http://nightlight.example.ts.net"
        val b = Bindings(saved, odd, id)
        assertEquals(Delivery.NOT_THE_TAILSCALE_PAGE, delivery(page = odd, now = b, stored = Entry(token, b)))
    }

    @Test
    fun `no Tailscale address saved means nothing is handed out`() {
        val none = Bindings(saved, null, null)
        assertEquals(Delivery.NOT_THE_TAILSCALE_PAGE, delivery(now = none, stored = Entry(token, none)))
    }

    @Test
    fun `no stored entry is no session`() {
        assertEquals(Delivery.NO_ENTRY, delivery(stored = null))
    }

    @Test
    fun `an entry saved before the Tailscale address was learned is never handed out`() {
        assertEquals(Delivery.NO_TAILSCALE_BINDING, delivery(stored = Entry(token, Bindings(saved, null, null))))
        assertEquals(Delivery.NO_TAILSCALE_BINDING, delivery(stored = Entry(token, Bindings(saved, ts, null))))
    }

    @Test
    fun `no saved id for the Tailscale address now means nothing is handed out`() {
        val noId = Bindings(saved, ts, null)
        assertEquals(Delivery.NO_TAILSCALE_BINDING, delivery(now = noId))
    }

    @Test
    fun `changing the saved address, the Tailscale address or its id after the token was saved makes the entry fail closed`() {
        assertEquals("another saved address", Delivery.BINDINGS_CHANGED, delivery(stored = Entry(token, Bindings("http://192.168.1.99:4000", ts, id))))
        assertEquals("another Tailscale address", Delivery.BINDINGS_CHANGED, delivery(stored = Entry(token, Bindings(saved, "https://other.example.ts.net", id))))
        assertEquals("another Tailscale id", Delivery.BINDINGS_CHANGED, delivery(stored = Entry(token, Bindings(saved, ts, "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"))))
    }

    @Test
    fun `the page's own server must still report the saved id`() {
        assertEquals(Delivery.ID_MISMATCH, delivery(liveId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"))
        assertEquals("an unreadable health reply is a refusal, not a pass", Delivery.ID_MISMATCH, delivery(liveId = null))
    }

    // ---- who may act: the bridge's server AND the page really shown ----

    private val savedOnly = Bindings(saved, null, null)

    @Test
    fun `a session may be saved or asked for from the saved address and from its Tailscale address, shown as built`() {
        assertTrue(SessionRules.mayAct(savedOnly, saved, "http://192.168.1.50:4000/#/live"))
        assertTrue(SessionRules.mayAct(current, saved, "http://192.168.1.50:4000/"))
        assertTrue(SessionRules.mayAct(current, ts, "https://nightlight.example.ts.net/#/settings"))
    }

    @Test
    fun `the Tailscale address is only a page once it is saved, and any other bridge server is refused`() {
        assertFalse(SessionRules.mayAct(savedOnly, ts, "https://nightlight.example.ts.net/"))
        assertFalse(SessionRules.mayAct(current, "https://evil.example", "https://evil.example/"))
        assertFalse(SessionRules.mayAct(current, null, "http://192.168.1.50:4000/"))
        assertFalse("nothing saved", SessionRules.mayAct(null, saved, "http://192.168.1.50:4000/"))
    }

    @Test
    fun `the page really shown must have the origin the bridge was built for (a frame or another port is refused)`() {
        assertFalse("another port of the same host", SessionRules.mayAct(current, ts, "https://nightlight.example.ts.net:8443/"))
        assertFalse("another host", SessionRules.mayAct(current, ts, "https://evil.example/"))
        assertFalse("http for https", SessionRules.mayAct(current, ts, "http://nightlight.example.ts.net/"))
        assertFalse("the page could not be read", SessionRules.mayAct(current, ts, null))
        assertFalse("about:blank", SessionRules.mayAct(current, ts, "about:blank"))
        assertFalse(SessionRules.isTailscalePage(current, ts, "https://evil.example/"))
        assertFalse(SessionRules.mayClear(current, ts, "https://evil.example/"))
        assertFalse(SessionRules.mayClear(current, saved, null))
    }

    @Test
    fun `only the page that IS the saved Tailscale address, really shown, is asked about a session`() {
        assertTrue(SessionRules.isTailscalePage(current, ts, "https://nightlight.example.ts.net/#/live"))
        assertFalse("the saved address, http", SessionRules.isTailscalePage(current, saved, "http://192.168.1.50:4000/"))
        assertFalse("no Tailscale address saved", SessionRules.isTailscalePage(savedOnly, ts, "https://nightlight.example.ts.net/"))
        assertFalse("a different Tailscale name", SessionRules.isTailscalePage(current, "https://other.example.ts.net", "https://other.example.ts.net/"))
        assertFalse(SessionRules.isTailscalePage(null, ts, "https://nightlight.example.ts.net/"))
        assertFalse(SessionRules.isTailscalePage(current, null, "https://nightlight.example.ts.net/"))
        val odd = Bindings(saved, "http://nightlight.example.ts.net", id)
        assertFalse("not the accepted shape", SessionRules.isTailscalePage(odd, "http://nightlight.example.ts.net", "http://nightlight.example.ts.net/"))
    }

    @Test
    fun `a clear works from any real server page, even after the Tailscale address was removed or replaced under it`() {
        assertTrue(SessionRules.mayClear(current, saved, "http://192.168.1.50:4000/"))
        assertTrue(SessionRules.mayClear(current, ts, "https://nightlight.example.ts.net/"))
        assertTrue("the Tailscale address was removed while its page is open", SessionRules.mayClear(savedOnly, ts, "https://nightlight.example.ts.net/"))
        assertFalse("the bundled setup page has no server", SessionRules.mayClear(current, null, "file:///android_asset/public/index.html"))
        assertFalse("nothing saved", SessionRules.mayClear(null, ts, "https://nightlight.example.ts.net/"))
    }

    @Test
    fun `only something shaped like a session token is saved`() {
        assertTrue(SessionRules.isPlausibleToken("eyJhbGciOiJIUzI1NiJ9.eyJpZCI6IjEifQ.c2lnLW5hdHVyZS1fLQ"))
        for (bad in listOf(null, "", "abc", "a.b", "a.b.c.d", "a.b. c", "a..c", "a b.c.d", "a.b.c\n", "token with spaces.x.y", "a.b.c/d")) {
            assertFalse("accepted: $bad", SessionRules.isPlausibleToken(bad))
        }
    }

    @Test
    fun `a token over 4 KB is refused and one of exactly 4 KB is not`() {
        val part = "a".repeat((SessionRules.MAX_TOKEN_CHARS - 2) / 3)
        val atLimit = "$part.$part.${"a".repeat(SessionRules.MAX_TOKEN_CHARS - 2 - 2 * part.length)}"
        assertEquals(SessionRules.MAX_TOKEN_CHARS, atLimit.length)
        assertTrue(SessionRules.isPlausibleToken(atLimit))
        assertFalse(SessionRules.isPlausibleToken(atLimit + "a"))
    }

    // ---- the page the WebView is really showing ----

    @Test
    fun `the page and the bridge must share an origin, with default ports filled in`() {
        assertTrue(SessionRules.sameOrigin("https://nightlight.example.ts.net/#/live", ts))
        assertTrue(SessionRules.sameOrigin("https://nightlight.example.ts.net:443/", ts))
        assertTrue(SessionRules.sameOrigin("HTTPS://NIGHTLIGHT.example.ts.net/x?y=1", ts))
        assertTrue(SessionRules.sameOrigin("http://192.168.1.50:4000/index.html", saved))
        assertTrue(SessionRules.sameOrigin("http://192.168.1.50:80/", "http://192.168.1.50"))
    }

    @Test
    fun `another port, host or scheme is another origin, and anything unparseable is refused`() {
        assertFalse("another port of the same host", SessionRules.sameOrigin("http://192.168.1.50:4001/", saved))
        assertFalse(SessionRules.sameOrigin("https://nightlight.example.ts.net:8443/", ts))
        assertFalse("another host", SessionRules.sameOrigin("https://evil.example/", ts))
        assertFalse("http for https", SessionRules.sameOrigin("http://nightlight.example.ts.net/", ts))
        assertFalse(SessionRules.sameOrigin(null, ts))
        assertFalse(SessionRules.sameOrigin(ts, null))
        assertFalse(SessionRules.sameOrigin("about:blank", ts))
        assertFalse(SessionRules.sameOrigin("file:///x", "file:///x"))
        assertFalse(SessionRules.sameOrigin("not a url", "not a url"))
    }

    @Test
    fun `an address java net URI cannot read is still a real origin`() {
        // java.net.URI gives no host for an underscore name and throws on a fragment with | { } (Opus review): every save and clear
        // from such a saved address was refused silently, so an offline sign-out there left the entry behind.
        assertTrue(SessionRules.sameOrigin("http://my_server:4000/", "http://my_server:4000"))
        assertTrue(SessionRules.sameOrigin("http://192.168.1.50:4000/#/a|b{c}", saved))
        assertTrue(SessionRules.sameOrigin("http://[fd00::1]:4000/", "http://[fd00::1]:4000"))
        assertFalse("a userinfo form is not an origin we accept", SessionRules.sameOrigin("http://user@192.168.1.50:4000/", saved))
        assertFalse("a port past 65535", SessionRules.sameOrigin("http://192.168.1.50:99999/", "http://192.168.1.50:99999"))
    }

    // ---- what a token is stamped with ----

    @Test
    fun `a claim equal to the saved Tailscale address keeps the binding`() {
        assertEquals(current, SessionRules.stampFor(current, ts))
        assertEquals("a trailing slash and spaces are the same address", current, SessionRules.stampFor(current, " $ts/ "))
    }

    @Test
    fun `no claim, a different claim or no saved Tailscale address stamps no binding, so the entry is never handed out`() {
        val none = Bindings(saved, null, null)
        assertEquals("an older web app sends no claim", none, SessionRules.stampFor(current, null))
        assertEquals("the server did not offer one (409, error)", none, SessionRules.stampFor(current, ""))
        assertEquals("a planted address the real server never offers", none, SessionRules.stampFor(current, "https://real.example.ts.net"))
        assertEquals(none, SessionRules.stampFor(none, ts))
        // ...and such an entry is refused at the gate, even when the planted address is now the saved one.
        assertEquals(Delivery.NO_TAILSCALE_BINDING, delivery(stored = Entry(token, SessionRules.stampFor(current, null))))
    }
}
