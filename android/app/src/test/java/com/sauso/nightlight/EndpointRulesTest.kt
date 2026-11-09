package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be saved as a server's Tailscale address. It arrives from the server the app has open, so the rule is
 * strict: exactly `https://<name>.ts.net`. Each refusal below is a near miss of the accepted shape.
 */
class EndpointRulesTest {

    @Test
    fun `a plain https ts-net name is accepted, with or without extra labels`() {
        assertTrue(EndpointRules.isTailscaleUrl("https://nightlight-1.example-tailnet.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://nightlight.tail1234.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://a.ts.net"))
    }

    @Test
    fun `anything that is not exactly that shape is refused`() {
        val refused = listOf(
            null,
            "",
            "http://nightlight.example.ts.net", // not https
            "https://nightlight.example.ts.net:8443", // a port
            "https://nightlight.example.ts.net/", // a path (even the root one)
            "https://nightlight.example.ts.net/api",
            "https://nightlight.example.ts.net?x=1",
            "https://nightlight.example.ts.net#frag",
            "https://user@nightlight.example.ts.net", // credentials
            "https://nightlight.example.ts.net.evil.example", // a longer name that merely contains ts.net
            "https://nightlight.example.ts.net.", // trailing dot
            "https://nots.net", // ends in ts.net but not .ts.net
            "https://ts.net", // no name before it
            "https://.ts.net",
            "https://-a.example.ts.net", // a label may not start or end with a hyphen
            "https://a-.example.ts.net",
            "https://100.64.0.1", // an address, not a name
            "https://[fd7a:115c:a1e0::1]",
            "https://NIGHTLIGHT.example.ts.net", // the server sends lower case
            "https://nightlight .example.ts.net",
            "https://night_light.example.ts.net",
            "HTTPS://nightlight.example.ts.net",
            " https://nightlight.example.ts.net",
        )
        for (value in refused) assertFalse("accepted: $value", EndpointRules.isTailscaleUrl(value))
    }

    @Test
    fun `a name longer than 255 characters is refused`() {
        val label = "a".repeat(63)
        val tooLong = "https://$label.$label.$label.$label.ts.net" // 8 + 4*64 + 6 = 270
        assertTrue(tooLong.length > 255)
        assertFalse(EndpointRules.isTailscaleUrl(tooLong))
        assertTrue(EndpointRules.isTailscaleUrl("https://$label.$label.ts.net"))
    }

    @Test
    fun `a label longer than 63 characters is refused`() {
        assertFalse(EndpointRules.isTailscaleUrl("https://${"a".repeat(64)}.ts.net"))
        assertTrue(EndpointRules.isTailscaleUrl("https://${"a".repeat(63)}.ts.net"))
    }

    @Test
    fun `each saved server has its own key, so one server's address is never read for another`() {
        assertEquals("tailscale_for:http://192.168.1.50:4000", EndpointRules.key("http://192.168.1.50:4000"))
        assertFalse(EndpointRules.key("http://192.168.1.50:4000") == EndpointRules.key("http://192.168.1.51:4000"))
        assertEquals("tailscale_id:http://192.168.1.50:4000", EndpointRules.idKey("http://192.168.1.50:4000"))
        assertFalse("the id and the address must not share a key", EndpointRules.key("x") == EndpointRules.idKey("x"))
    }

    private val home = "http://192.168.1.50:4000"
    private val ts = "https://nightlight.example.ts.net"
    private val other = "https://other.example.ts.net"

    @Test
    fun `the page asking must be the saved server or its saved Tailscale address`() {
        assertEquals(EndpointRules.AddCheck.OK, EndpointRules.checkPage(ts, home, null, home))
        assertEquals("the page is already on the saved Tailscale address", EndpointRules.AddCheck.OK, EndpointRules.checkPage(ts, home, ts, ts))
        assertEquals("a changed address, asked from the saved one", EndpointRules.AddCheck.OK, EndpointRules.checkPage(other, home, ts, home))
    }

    @Test
    fun `any other page is refused, including the bundled setup page which has no server`() {
        val refused = EndpointRules.AddCheck.NOT_THIS_PAGE
        assertEquals("another server", refused, EndpointRules.checkPage(ts, home, null, "https://elsewhere.example"))
        assertEquals("a Tailscale address that is not the saved one", refused, EndpointRules.checkPage(ts, home, ts, other))
        assertEquals("the setup page (no server)", refused, EndpointRules.checkPage(ts, home, null, null))
        assertEquals("nothing saved", refused, EndpointRules.checkPage(ts, null, null, home))
        assertEquals("the page is the Tailscale address, but none is saved for it", refused, EndpointRules.checkPage(other, home, null, ts))
    }

    @Test
    fun `an address of the wrong shape is refused before the page is even looked at`() {
        assertEquals(EndpointRules.AddCheck.NOT_A_TAILSCALE_ADDRESS, EndpointRules.checkPage("http://nightlight.example.ts.net", home, null, home))
        assertEquals(EndpointRules.AddCheck.NOT_A_TAILSCALE_ADDRESS, EndpointRules.checkPage(null, home, null, home))
    }

    @Test
    fun `isActivePage is what removeEndpoint relies on`() {
        assertTrue(EndpointRules.isActivePage(home, null, home))
        assertTrue(EndpointRules.isActivePage(home, ts, ts))
        assertFalse(EndpointRules.isActivePage(home, null, ts))
        assertFalse(EndpointRules.isActivePage(home, ts, null))
        assertFalse(EndpointRules.isActivePage(null, ts, ts))
        assertFalse("a null saved address must not match a null page", EndpointRules.isActivePage(null, null, null))
    }

    private val sameId = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val otherId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

    @Test
    fun `an address is accepted only when the two servers report the same id`() {
        val a = DiscoveryRules.Health(sameId, "Nursery")
        assertEquals(EndpointRules.AddCheck.OK, EndpointRules.checkIdentity(a, DiscoveryRules.Health(sameId, "A different name is fine")))
        assertEquals(EndpointRules.AddCheck.DIFFERENT_SERVER, EndpointRules.checkIdentity(a, DiscoveryRules.Health(otherId, "Nursery")))
    }

    @Test
    fun `either server not answering is its own refusal, and the reference is judged first`() {
        val a = DiscoveryRules.Health(sameId, "Nursery")
        assertEquals(EndpointRules.AddCheck.UNREADABLE_SERVER, EndpointRules.checkIdentity(null, a))
        assertEquals(EndpointRules.AddCheck.UNREACHABLE_ADDRESS, EndpointRules.checkIdentity(a, null))
        assertEquals(EndpointRules.AddCheck.UNREADABLE_SERVER, EndpointRules.checkIdentity(null, null))
    }

    @Test
    fun `home-network-only addresses are private IPv4 and dot-local names`() {
        for (url in listOf("http://192.168.1.50:4000", "http://10.0.0.5", "http://10.200.1.2", "http://10.255.255.254", "http://172.16.4.2:4000", "http://172.31.255.1", "http://nightlight.local:4000", "http://NIGHTLIGHT.LOCAL")) {
            assertTrue(url, EndpointRules.isLocalOnlyAddress(url))
        }
    }

    @Test
    fun `a public address, a tailnet address or anything unparseable is not local-only, so it gets the ordinary wait`() {
        for (url in listOf(null, "", "not a url", "https://nightlight.example.com", "https://nightlight.example.ts.net", "http://100.64.0.1:4000",
            "http://8.8.8.8", "http://172.32.0.1", "http://172.15.0.1", "http://192.169.1.1", "http://localhost:4000", "http://nightlight.local.evil.example")) {
            assertFalse("treated as local-only: $url", EndpointRules.isLocalOnlyAddress(url))
        }
    }

    // ---- The launch's grace for the Tailscale address (owner decision 2026-11-09, Tailscale first) ----

    @Test
    fun `the grace is a second, or a quarter second when the phone has no VPN`() {
        // Literal values, so the numbers the README states are what is pinned.
        assertEquals(1_000L, EndpointRules.TAILSCALE_GRACE_MS)
        assertEquals(250L, EndpointRules.TAILSCALE_GRACE_NO_VPN_MS)
    }

    @Test
    fun `a VPN on the phone keeps the full grace, and only no VPN at all shortens it`() {
        assertEquals("a VPN is up (or the reading failed, which reads as present)", 1_000L, EndpointRules.tailscaleGraceMs(vpnPresent = true))
        assertEquals("positively no VPN", 250L, EndpointRules.tailscaleGraceMs(vpnPresent = false))
    }

    @Test
    fun `the grace is shorter than the whole launch decision`() {
        // Otherwise a saved address that answered would wait the whole deadline for Tailscale, as if there were no grace.
        assertTrue(EndpointRules.TAILSCALE_GRACE_MS < EndpointChooser.DEADLINE_MS)
        assertTrue(EndpointRules.TAILSCALE_GRACE_NO_VPN_MS < EndpointRules.TAILSCALE_GRACE_MS)
    }

    // ---- refusal: the sentences addEndpoint rejects with, word for word ----

    @Test
    fun `every refusal sentence is pinned, byte-identical to the iPhone app's`() {
        // The web app's About card reads these words (nativeBridge.js classifyAddRefusal), and ios/Tests pins the same sentences:
        // a sentence reworded on one platform would show a different reason there.
        assertEquals("Not a Tailscale address", EndpointRules.refusal(EndpointRules.AddCheck.NOT_A_TAILSCALE_ADDRESS))
        assertEquals("Not available on this page", EndpointRules.refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
        assertEquals("Couldn't read this server's identity", EndpointRules.refusal(EndpointRules.AddCheck.UNREADABLE_SERVER))
        assertEquals("Couldn't reach that Tailscale address", EndpointRules.refusal(EndpointRules.AddCheck.UNREACHABLE_ADDRESS))
        assertEquals("That address is a different server", EndpointRules.refusal(EndpointRules.AddCheck.DIFFERENT_SERVER))
        assertEquals("The secure address was not accepted on this phone", EndpointRules.refusal(EndpointRules.AddCheck.NOT_ACCEPTED))
        assertEquals("The secure address was not confirmed on this phone", EndpointRules.refusal(EndpointRules.AddCheck.NOT_CONFIRMED))
        assertEquals("", EndpointRules.refusal(EndpointRules.AddCheck.OK))
    }

    @Test
    fun `the prompt's refusals read as refused on the web, never as unreachable or another server`() {
        for (check in listOf(EndpointRules.AddCheck.NOT_ACCEPTED, EndpointRules.AddCheck.NOT_CONFIRMED)) {
            val sentence = EndpointRules.refusal(check)
            assertFalse(sentence, Regex("couldn'?t reach", RegexOption.IGNORE_CASE).containsMatchIn(sentence))
            assertFalse(sentence, Regex("different server", RegexOption.IGNORE_CASE).containsMatchIn(sentence))
        }
    }

    // ---- needsConfirmation: when a secure address must be confirmed by the person (Codex code review of PR #37) ----

    @Test
    fun `the decline keys are per server and never the address's own keys`() {
        assertEquals("tailscale_declined:$home", EndpointRules.declinedKey(home))
        assertEquals("tailscale_declined_at:$home", EndpointRules.declinedAtKey(home))
        assertFalse(EndpointRules.declinedKey(home) == EndpointRules.declinedKey("http://192.168.1.51:4000"))
        val keys = setOf(EndpointRules.key(home), EndpointRules.idKey(home), EndpointRules.declinedKey(home), EndpointRules.declinedAtKey(home))
        assertEquals("four different keys", 4, keys.size)
    }

    @Test
    fun `a new or changed address is confirmed unless the page shown is https`() {
        val httpPage = "http://192.168.1.50:4000/#/live"
        val httpsPage = "https://nightlight.example.com/#/live"
        val tailscalePage = "$ts/#/live"
        // Rows: (saved secure address, page shown) -> asks? The candidate is always `ts`.
        val rows = listOf(
            Triple(null, httpPage, true), // first learn, from http
            Triple(other, httpPage, true), // a change, from http
            Triple(null, null, true), // first learn, the page could not be read: asks (fails closed)
            Triple(other, null, true),
            Triple(null, "about:blank", true), // not https
            Triple(null, "http://192.168.1.50:4000/?next=https://x.example", true), // "https://" later in an http address
            Triple(null, httpsPage, false), // an https page cannot be spoofed by the network
            Triple(other, httpsPage, false),
            Triple(other, "$other/#/live", false), // the page on the (old) saved secure address itself
            Triple(null, tailscalePage, false),
            Triple(ts, httpPage, false), // already saved: re-offered at every start, never asked again
            Triple(ts, null, false),
            Triple(ts, httpsPage, false),
            Triple(ts, tailscalePage, false),
        )
        for ((savedTailscale, shown, asks) in rows) {
            assertEquals("saved $savedTailscale, shown $shown", asks, EndpointRules.needsConfirmation(shown, savedTailscale, ts))
        }
    }

    @Test
    fun `the write decides again, and only a confirmed write ignores what the page shows now`() {
        // (accepted, needs confirmation now) -> may write. Codex code review of the PR #37 prompt delta.
        assertTrue("accepted, not needed now", EndpointRules.mayWriteAddress(accepted = true, needsConfirmationNow = false))
        assertTrue("not asked, not needed now", EndpointRules.mayWriteAddress(accepted = false, needsConfirmationNow = false))
        assertFalse("not asked, but needed now: refused", EndpointRules.mayWriteAddress(accepted = false, needsConfirmationNow = true))
        assertTrue("accepted, and needed now: the person already said yes", EndpointRules.mayWriteAddress(accepted = true, needsConfirmationNow = true))
    }

    @Test
    fun `a page that turned http before the write needs confirmation again`() {
        // The case the re-check exists for: decided on an https page, written while an http page shows.
        val decidedOn = "https://nightlight.example.com/#/live"
        val shownAtWrite = "http://192.168.1.50:4000/#/live"
        assertFalse(EndpointRules.needsConfirmation(decidedOn, null, ts))
        assertFalse(EndpointRules.mayWriteAddress(accepted = false, needsConfirmationNow = EndpointRules.needsConfirmation(shownAtWrite, null, ts)))
        assertFalse("unreadable at the write", EndpointRules.mayWriteAddress(accepted = false, needsConfirmationNow = EndpointRules.needsConfirmation(null, null, ts)))
    }

    // ---- addressAnswers: what "answers" means, for the launch's choice and the watcher's second look alike ----

    // Network reads that must not happen: each fails the test if it is called.
    private val reachableNeverAsked: (String) -> Boolean = { throw AssertionError("reachable was asked about $it") }
    private val idNeverRead: (String) -> String? = { throw AssertionError("an id was read for $it") }

    @Test
    fun `only exact private IPv4 URLs have a saved id check`() {
        assertTrue(EndpointRules.idCheckedAddress("http://192.168.1.50:4000"))
        for (url in listOf(null, "http://server.example.test:4000", "https://192.168.1.50:4000",
            "http://192.168.1.50", "http://8.8.8.8:4000", "http://user@192.168.1.50:4000",
            "http://192.168.1.50:4000/path")) {
            assertFalse("id checked $url", EndpointRules.idCheckedAddress(url))
        }
    }

    @Test
    fun `saved id accessor scopes both stored and pair ids to private IPv4 URLs`() {
        assertEquals(sameId, EndpointRules.expectedIdForAddress(home, sameId, otherId))
        assertEquals(otherId, EndpointRules.expectedIdForAddress(home, null, otherId))
        assertEquals(null, EndpointRules.expectedIdForAddress("http://server.example.test:4000", sameId, otherId))
        assertEquals(null, EndpointRules.expectedIdForAddress("https://192.168.1.50:4000", null, otherId))
    }

    @Test
    fun `forget clears only the active saved address`() {
        assertTrue(EndpointRules.forgetClearsActive(home, home))
        assertFalse(EndpointRules.forgetClearsActive(home, "http://192.168.1.51:4000"))
        assertFalse(EndpointRules.forgetClearsActive(null, home))
    }

    @Test
    fun `the tailscale address answers when it reports the id it was saved for`() {
        assertTrue(EndpointRules.addressAnswers(ts, ts, sameId, otherId, fetchId = { sameId }, reachable = reachableNeverAsked))
    }

    @Test
    fun `a wrong id, no id or no saved id is not an answer from the tailscale address`() {
        assertFalse("another server's id", EndpointRules.addressAnswers(ts, ts, sameId, null, fetchId = { otherId }, reachable = reachableNeverAsked))
        assertFalse("no id read", EndpointRules.addressAnswers(ts, ts, sameId, null, fetchId = { null }, reachable = reachableNeverAsked))
        assertFalse("no saved id, the address reports one", EndpointRules.addressAnswers(ts, ts, null, null, fetchId = { sameId }, reachable = reachableNeverAsked))
        // The one a dropped "wantedId != null" would let through: null equals null.
        assertFalse("no saved id and none read", EndpointRules.addressAnswers(ts, ts, null, null, fetchId = { null }, reachable = reachableNeverAsked))
    }

    @Test
    fun `the tailscale address is never judged by reachability`() {
        // Up, but another server: reachable would say yes, and the rule must not fall back to it.
        assertFalse(EndpointRules.addressAnswers(ts, ts, sameId, null, fetchId = { otherId }, reachable = { true }))
    }

    @Test
    fun `the saved address without an expected id uses reachability and never fetches an id`() {
        assertTrue(EndpointRules.addressAnswers(home, ts, sameId, null, fetchId = idNeverRead, reachable = { it == home }))
        assertFalse(EndpointRules.addressAnswers(home, ts, sameId, null, fetchId = idNeverRead, reachable = { false }))
        assertTrue("no Tailscale address saved", EndpointRules.addressAnswers(home, null, null, null, fetchId = idNeverRead, reachable = { true }))
    }

    @Test
    fun `non matching saved URL uses legacy reachability even with a pair id`() {
        val hostname = "http://server.example.test:4000"
        assertTrue(EndpointRules.addressAnswers(hostname, ts, sameId, sameId,
            fetchId = idNeverRead, reachable = { it == hostname }))
        assertFalse(EndpointRules.addressAnswers(hostname, ts, sameId, sameId,
            fetchId = idNeverRead, reachable = { false }))
    }

    @Test
    fun `saved address with expected id answers only for that id`() {
        assertTrue(EndpointRules.addressAnswers(home, ts, sameId, sameId, fetchId = { sameId }, reachable = reachableNeverAsked))
        assertFalse(EndpointRules.addressAnswers(home, ts, sameId, sameId, fetchId = { otherId }, reachable = { true }))
        assertFalse("reply without id", EndpointRules.addressAnswers(home, ts, sameId, sameId, fetchId = { null }, reachable = { true }))
        assertFalse("no reply", EndpointRules.addressAnswers(home, null, null, sameId, fetchId = { null }, reachable = { true }))
    }

    @Test
    fun `identity verdict rejects only a readable different id`() {
        val verdict = EndpointRules::identityVerdict
        assertEquals(EndpointRules.IdentityVerdict.SAME, verdict(sameId, sameId))
        assertEquals(EndpointRules.IdentityVerdict.DIFFERENT, verdict(sameId, otherId))
        assertEquals(EndpointRules.IdentityVerdict.UNKNOWN, verdict(sameId, null))
        assertEquals(EndpointRules.IdentityVerdict.UNKNOWN, verdict(null, otherId))
        assertEquals(EndpointRules.IdentityVerdict.UNKNOWN, verdict(null, null))
        val storedId: String? = null
        val pairId: String? = sameId
        assertEquals("pair-only expected id refuses a different live id", EndpointRules.IdentityVerdict.DIFFERENT,
            verdict(storedId ?: pairId, otherId))
    }

    // ---- Change server from the Tailscale address (owner, 2026-11-06) ----

    private val known = listOf(home, "https://nightlight.example.com")

    @Test
    fun `a remembered server whose Tailscale address answers with its saved id can be reconnected through it`() {
        assertTrue(EndpointRules.canReconnectViaTailscale(known, home, ts, sameId, sameId))
    }

    @Test
    fun `each missing or wrong piece refuses the reconnect`() {
        assertFalse("not a remembered server", EndpointRules.canReconnectViaTailscale(known, "http://192.168.1.99:4000", ts, sameId, sameId))
        assertFalse("no url", EndpointRules.canReconnectViaTailscale(known, null, ts, sameId, sameId))
        assertFalse("no Tailscale address saved", EndpointRules.canReconnectViaTailscale(known, home, null, sameId, sameId))
        assertFalse("a saved address that is not a Tailscale one", EndpointRules.canReconnectViaTailscale(known, home, "https://evil.example.com", sameId, sameId))
        assertFalse("no id was saved with it", EndpointRules.canReconnectViaTailscale(known, home, ts, null, sameId))
        assertFalse("the live id could not be read", EndpointRules.canReconnectViaTailscale(known, home, ts, sameId, null))
        assertFalse("another server answers there", EndpointRules.canReconnectViaTailscale(known, home, ts, sameId, otherId))
        assertFalse("nothing remembered at all", EndpointRules.canReconnectViaTailscale(emptyList(), home, ts, sameId, sameId))
    }

    // ---- The server's name in the saved list (owner, 2026-11-06) ----

    @Test
    fun `a name is remembered only for a remembered server and only when the server sent one`() {
        assertTrue(EndpointRules.mayRememberName(known, home, "Nightlight"))
        assertFalse("not a remembered server", EndpointRules.mayRememberName(known, "http://192.168.1.99:4000", "Nightlight"))
        assertFalse("no name sent", EndpointRules.mayRememberName(known, home, null))
        assertFalse("a blank name", EndpointRules.mayRememberName(known, home, "   "))
        assertFalse("nothing remembered", EndpointRules.mayRememberName(emptyList(), home, "Nightlight"))
    }

    @Test
    fun `only the names of remembered servers are listed, in the saved list's order, and a blank one is dropped`() {
        val stored = mapOf(home to "Home", "https://nightlight.example.com" to "  ", "http://192.168.1.99:4000" to "A forgotten server")
        assertEquals(mapOf(home to "Home"), EndpointRules.namesForKnown(stored, known))
        assertEquals(emptyMap<String, String>(), EndpointRules.namesForKnown(emptyMap(), known))
        assertEquals(listOf("https://nightlight.example.com", home), EndpointRules.namesForKnown(
            mapOf(home to "Home", "https://nightlight.example.com" to "Away"), listOf("https://nightlight.example.com", home)).keys.toList())
    }

    @Test
    fun `a saved Tailscale address is kept when the home address reports its id or none, and dropped when another server does`() {
        assertTrue("same server", EndpointRules.pairStillValid(sameId, sameId))
        assertTrue("home id unreadable: not knowing is not a reason to lose the way back in", EndpointRules.pairStillValid(sameId, null))
        assertTrue("no id was saved", EndpointRules.pairStillValid(null, otherId))
        assertFalse("another machine took over the home address", EndpointRules.pairStillValid(sameId, otherId))
    }
}
