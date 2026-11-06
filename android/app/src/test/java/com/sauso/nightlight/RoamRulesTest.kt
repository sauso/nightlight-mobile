package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app moves to the server's other address by itself (RoamRules, RecreateLog, NetworkEventFilter). Each rule has
 * its own test, so a dropped or flipped rule fails the test named for it.
 *
 * Not reachable here (Android glue, phone checks only): registering and unregistering the network callback, the generation
 * check that makes a late check dead on arrival, the main-thread reads of the page URL and Picture-in-Picture, the debounce
 * and retry timing on the worker thread, and the recreate itself (NetworkRoamWatcher, MainActivity).
 */
class RoamRulesTest {

    private val saved = "http://192.168.1.50:4000"
    private val tailscale = "https://nightlight.example.ts.net"
    private val start = RoamRules.Trigger.FOREGROUND_START
    private val change = RoamRules.Trigger.NETWORK_CHANGE

    // ---- currentAddress: which address the page on screen is on ----

    @Test
    fun `a page on the saved address is the saved address`() {
        assertEquals(saved, RoamRules.currentAddress("http://192.168.1.50:4000", saved, tailscale))
    }

    @Test
    fun `a page on the tailscale address is the tailscale address`() {
        assertEquals(tailscale, RoamRules.currentAddress("https://nightlight.example.ts.net", saved, tailscale))
    }

    @Test
    fun `a route, query or hash on the page does not matter`() {
        assertEquals(saved, RoamRules.currentAddress("http://192.168.1.50:4000/#/settings", saved, tailscale))
        assertEquals(tailscale, RoamRules.currentAddress("https://nightlight.example.ts.net/index.html?x=1#/live", saved, tailscale))
    }

    @Test
    fun `another port on the same host is neither address`() {
        assertNull(RoamRules.currentAddress("http://192.168.1.50:4001/", saved, tailscale))
        assertNull(RoamRules.currentAddress("https://nightlight.example.ts.net:8443/", saved, tailscale))
    }

    @Test
    fun `another scheme on the same host is neither address`() {
        assertNull(RoamRules.currentAddress("https://192.168.1.50:4000/", saved, tailscale))
    }

    @Test
    fun `the bundled setup and error pages are neither address`() {
        assertNull(RoamRules.currentAddress("https://localhost/", saved, tailscale))
        assertNull(RoamRules.currentAddress("https://localhost/error.html", saved, tailscale))
        assertNull(RoamRules.currentAddress("http://localhost/", saved, tailscale))
        assertNull(RoamRules.currentAddress("about:blank", saved, tailscale))
    }

    @Test
    fun `no page url is neither address`() {
        assertNull(RoamRules.currentAddress(null, saved, tailscale))
    }

    // ---- target: whether to switch, and in which direction ----

    @Test
    fun `fail-over happens on a network change`() {
        assertEquals(tailscale, RoamRules.target(change, saved, tailscale, current = saved, chosen = tailscale, inPip = false))
    }

    @Test
    fun `fail-over happens at a foreground start`() {
        assertEquals(tailscale, RoamRules.target(start, saved, tailscale, current = saved, chosen = tailscale, inPip = false))
    }

    @Test
    fun `fail-over happens in picture-in-picture too`() {
        // The page on screen is already dead: the PiP restriction is for fail-back only.
        assertEquals(tailscale, RoamRules.target(change, saved, tailscale, current = saved, chosen = tailscale, inPip = true))
    }

    @Test
    fun `fail-back happens at a foreground start`() {
        assertEquals(saved, RoamRules.target(start, saved, tailscale, current = tailscale, chosen = saved, inPip = false))
    }

    @Test
    fun `fail-back does not happen on a network change`() {
        assertNull(RoamRules.target(change, saved, tailscale, current = tailscale, chosen = saved, inPip = false))
    }

    @Test
    fun `fail-back does not happen in picture-in-picture`() {
        assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = saved, inPip = true))
    }

    @Test
    fun `nothing happens without a tailscale address`() {
        // Implied by the direction rules as well (with no Tailscale address there is nothing to fail over to): this pins the
        // behaviour, and a mutant that drops only the explicit null check is equivalent.
        assertNull(RoamRules.target(change, saved, null, current = saved, chosen = tailscale, inPip = false))
    }

    @Test
    fun `nothing happens when the page is on neither address`() {
        assertNull(RoamRules.target(change, saved, tailscale, current = null, chosen = tailscale, inPip = false))
        assertNull(RoamRules.target(start, saved, tailscale, current = null, chosen = saved, inPip = false))
    }

    @Test
    fun `nothing happens when nothing answered`() {
        assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = null, inPip = false))
        assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = null, inPip = false))
    }

    @Test
    fun `nothing happens when the choice is the address already on screen`() {
        assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = saved, inPip = false))
        assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = tailscale, inPip = false))
    }

    @Test
    fun `a saved address that is its own tailscale address never switches to itself`() {
        // The one case where only the same-address check stops a "fail-over" (saved == tailscale == current == chosen).
        assertNull(RoamRules.target(change, tailscale, tailscale, current = tailscale, chosen = tailscale, inPip = false))
    }

    @Test
    fun `an unrelated page address never switches`() {
        val other = "https://other.example"
        assertNull(RoamRules.target(change, saved, tailscale, current = other, chosen = tailscale, inPip = false))
        assertNull(RoamRules.target(start, saved, tailscale, current = other, chosen = saved, inPip = false))
    }

    @Test
    fun `an unrelated chosen address is never switched to`() {
        val other = "https://other.example"
        assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = other, inPip = false))
        assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = other, inPip = false))
    }

    // ---- RecreateLog: the shared loop guard ----

    @Test
    fun `the first load-error recreate is allowed, even soon after boot`() {
        // The clock counts from boot: "no recreate yet" must not be read as "a recreate at time zero".
        assertTrue(RecreateLog().mayErrorRecreate(1_000L))
    }

    @Test
    fun `the first roam is allowed, even soon after boot`() {
        assertTrue(RecreateLog().mayRoam(1_000L))
    }

    @Test
    fun `a load-error recreate waits 8 s after a load-error recreate`() {
        val log = RecreateLog()
        log.noteRecreate(100_000L)
        assertFalse(log.mayErrorRecreate(107_900L))
        assertTrue(log.mayErrorRecreate(108_100L))
    }

    @Test
    fun `a load-error recreate waits 8 s after a roam`() {
        val log = RecreateLog()
        log.noteRoam(100_000L)
        assertFalse(log.mayErrorRecreate(107_900L))
        assertTrue(log.mayErrorRecreate(108_100L))
    }

    @Test
    fun `a roam waits 20 s after a load-error recreate`() {
        val log = RecreateLog()
        log.noteRecreate(100_000L)
        assertFalse(log.mayRoam(119_900L))
        assertTrue(log.mayRoam(120_100L))
    }

    @Test
    fun `a roam waits 20 s after a roam`() {
        val log = RecreateLog()
        log.noteRoam(100_000L)
        assertFalse(log.mayRoam(119_900L))
        assertTrue(log.mayRoam(120_100L))
    }

    // The exact boundaries (Codex review: 7.9/8.1 s and 19.9/20.1 s did not tell ">=" from ">"). "At least" the gap is allowed.
    @Test
    fun `the 8 s and 20 s gaps are met at exactly the gap, not one millisecond after`() {
        val error = RecreateLog().also { it.noteRecreate(100_000L) }
        assertFalse(error.mayErrorRecreate(107_999L))
        assertTrue(error.mayErrorRecreate(108_000L))
        val roam = RecreateLog().also { it.noteRecreate(100_000L) }
        assertFalse(roam.mayRoam(119_999L))
        assertTrue(roam.mayRoam(120_000L))
    }

    @Test
    fun `load-error recreates do not count towards the roam limit`() {
        val log = RecreateLog()
        for (t in listOf(0L, 30_000L, 60_000L, 90_000L)) log.noteRecreate(t)
        assertTrue(log.mayRoam(120_000L))
    }

    // Three roams 30 s apart, each allowed when it is made.
    private fun logWithThreeRoams(): RecreateLog {
        val log = RecreateLog()
        for (t in listOf(0L, 30_000L, 60_000L)) {
            assertTrue("roam at $t ms", log.mayRoam(t))
            log.noteRoam(t)
        }
        return log
    }

    @Test
    fun `three roams are allowed and the fourth is refused`() {
        // 30 s after the third: the 20 s gap has passed, so only the count refuses it.
        assertFalse(logWithThreeRoams().mayRoam(90_000L))
    }

    @Test
    fun `the fourth roam is allowed once the oldest is out of the window`() {
        // The window rolls: roaming comes back by itself, it does not stay off until the app restarts. At 320 s only the roam
        // at 0 is older than five minutes; the other two still count, and two is under the limit.
        val log = logWithThreeRoams()
        assertFalse(log.mayRoam(90_000L))
        assertTrue(log.mayRoam(320_000L))
    }

    @Test
    fun `a roam counts until exactly five minutes have passed`() {
        // Literal times, so the five minutes the README states are what is pinned.
        val log = logWithThreeRoams()
        assertFalse(log.mayRoam(299_999L))
        assertTrue(log.mayRoam(300_000L))
    }

    // ---- retryDelayAfter: the bounded retry when nothing answered ----

    @Test
    fun `the retries come at about 3, 6, 12 and 25 s and then stop`() {
        val offsets = mutableListOf<Long>()
        var total = 0L
        var attempt = 0
        while (true) {
            val wait = RoamRules.retryDelayAfter(attempt) ?: break
            total += wait
            offsets.add(total)
            attempt++
            assertTrue("never stopped", attempt < 100)
        }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 25_000L), offsets)
    }

    @Test
    fun `each wait is the gap to the next offset`() {
        assertEquals(3_000L, RoamRules.retryDelayAfter(0))
        assertEquals(3_000L, RoamRules.retryDelayAfter(1))
        assertEquals(6_000L, RoamRules.retryDelayAfter(2))
        assertEquals(13_000L, RoamRules.retryDelayAfter(3))
    }

    @Test
    fun `there is no retry after the last one, or before the first`() {
        assertNull(RoamRules.retryDelayAfter(4))
        assertNull(RoamRules.retryDelayAfter(-1))
    }

    // ---- NetworkEventFilter: which callbacks are a real change ----

    @Test
    fun `registering reports the current network, and that is not a change`() {
        assertFalse(NetworkEventFilter("wifi").onAvailable("wifi"))
    }

    @Test
    fun `a new network after that is a change, even the same one again`() {
        val filter = NetworkEventFilter("wifi")
        filter.onAvailable("wifi")
        assertTrue(filter.onAvailable("cell"))
        assertTrue(filter.onAvailable("wifi"))
    }

    @Test
    fun `with no network at registration the first network to arrive is a change`() {
        // Airplane mode when the app came to the front: there is no echo to skip.
        assertTrue(NetworkEventFilter<String>(null).onAvailable("cell"))
    }

    @Test
    fun `a first report of a different network than the registered one is a change`() {
        val filter = NetworkEventFilter("wifi")
        assertTrue(filter.onAvailable("cell"))
        // And the echo is no longer expected.
        assertTrue(filter.onAvailable("wifi"))
    }

    @Test
    fun `losing the network is a change`() {
        assertTrue(NetworkEventFilter("wifi").onLost())
    }

    @Test
    fun `after a loss the registered network coming back is a change`() {
        val filter = NetworkEventFilter("wifi")
        filter.onLost()
        assertTrue(filter.onAvailable("wifi"))
    }

    @Test
    fun `the first transports reported are not a change`() {
        assertFalse(NetworkEventFilter("wifi").onTransports(1))
    }

    @Test
    fun `the same transports again are not a change`() {
        val filter = NetworkEventFilter("wifi")
        filter.onTransports(1)
        assertFalse(filter.onTransports(1))
    }

    @Test
    fun `different transports are a change, once`() {
        val filter = NetworkEventFilter("vpn")
        filter.onTransports(1 or 8)
        assertTrue(filter.onTransports(2 or 8))
        assertFalse(filter.onTransports(2 or 8))
    }

    @Test
    fun `after a loss the next transports are only recorded`() {
        val filter = NetworkEventFilter("wifi")
        filter.onTransports(1)
        filter.onLost()
        assertFalse(filter.onTransports(2))
    }
}
