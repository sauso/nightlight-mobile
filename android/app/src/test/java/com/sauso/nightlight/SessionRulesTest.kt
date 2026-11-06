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

    // ---- who may save ----

    @Test
    fun `a session may be saved from the saved address and from its Tailscale address only`() {
        assertTrue(SessionRules.maySave(saved, null, saved, token))
        assertTrue(SessionRules.maySave(saved, ts, saved, token))
        assertTrue(SessionRules.maySave(saved, ts, ts, token))
        assertFalse("the Tailscale address is only a page once it is saved", SessionRules.maySave(saved, null, ts, token))
        assertFalse(SessionRules.maySave(saved, ts, "https://evil.example", token))
        assertFalse(SessionRules.maySave(saved, ts, null, token))
        assertFalse(SessionRules.maySave(null, null, saved, token))
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
}
