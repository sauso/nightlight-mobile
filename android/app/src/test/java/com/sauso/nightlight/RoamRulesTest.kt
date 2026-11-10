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
 * check that makes a late check dead on arrival, the main-thread reads of the page URL and Picture-in-Picture, the second look's
 * real network probe (its bound is tested here: boundedLook; its id rule in EndpointRulesTest: addressAnswers), the wiring of
 * the final main-thread check (its rule is tested here: heldBackAtLastStep), the debounce and retry timing on the worker
 * thread, and the recreate itself (NetworkRoamWatcher, MainActivity).
 */
class RoamRulesTest {

    private val saved = "http://192.168.1.50:4000"
    private val tailscale = "https://nightlight.example.ts.net"
    private val custom = "https://nightlight.example.com:8443"
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
    fun `a custom slot takes the same roaming paths as a tailscale slot`() {
        assertEquals(custom, RoamRules.currentAddress("$custom/#/live", saved, custom))
        assertEquals(RoamMove(custom, escape = true),
            RoamRules.target(change, saved, custom, saved, custom, false, false))
        assertEquals(RoamMove(custom, escape = false),
            RoamRules.target(start, saved, custom, saved, custom, false, true))
        assertEquals(RoamMove(saved, escape = true),
            RoamRules.target(change, saved, custom, custom, saved, false, false))
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

    // ---- target: whether to move, and how (owner decision 2026-11-09, Tailscale first; Codex plan review the same day) ----
    // currentAnswers is the watcher's second look at the page on screen: true = it answers, false = it is dead, null = the look
    // could not be taken.

    // Every trigger with and without Picture-in-Picture, for the rows that must hold in all of them.
    private val everyCase = listOf(start to false, start to true, change to false, change to true)
    private val answersOrUnknown = listOf<Boolean?>(true, null)

    @Test
    fun `a dead saved page escapes to tailscale on either trigger, even in picture-in-picture`() {
        // The page on screen already stopped answering: a reload loses nothing that still works.
        for ((trigger, inPip) in everyCase) {
            assertEquals("$trigger, in PiP $inPip", RoamMove(tailscale, escape = true),
                RoamRules.target(trigger, saved, tailscale, current = saved, chosen = tailscale, inPip = inPip, currentAnswers = false))
        }
    }

    @Test
    fun `a dead tailscale page escapes to the saved address on either trigger, even in picture-in-picture`() {
        // Tailscale switched off while its page is open: the page is dead, so the saved address that just answered is used.
        for ((trigger, inPip) in everyCase) {
            assertEquals("$trigger, in PiP $inPip", RoamMove(saved, escape = true),
                RoamRules.target(trigger, saved, tailscale, current = tailscale, chosen = saved, inPip = inPip, currentAnswers = false))
        }
    }

    @Test
    fun `a working saved page is upgraded to tailscale at a foreground start`() {
        assertEquals(RoamMove(tailscale, escape = false),
            RoamRules.target(start, saved, tailscale, current = saved, chosen = tailscale, inPip = false, currentAnswers = true))
    }

    @Test
    fun `a working saved page is not upgraded on a network change`() {
        // The saved page keeps working, so there is no hurry: a reload while someone is using the app would cut what they do.
        assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = tailscale, inPip = false, currentAnswers = true))
    }

    @Test
    fun `a working saved page is not upgraded in picture-in-picture`() {
        assertNull(RoamRules.target(start, saved, tailscale, current = saved, chosen = tailscale, inPip = true, currentAnswers = true))
    }

    @Test
    fun `a second look that could not be taken counts as working`() {
        // A doubt never reloads a page: the unknown case moves only where a working page would.
        assertEquals(RoamMove(tailscale, escape = false),
            RoamRules.target(start, saved, tailscale, current = saved, chosen = tailscale, inPip = false, currentAnswers = null))
        assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = tailscale, inPip = false, currentAnswers = null))
        assertNull(RoamRules.target(start, saved, tailscale, current = saved, chosen = tailscale, inPip = true, currentAnswers = null))
        for ((trigger, inPip) in everyCase) {
            assertNull("$trigger, in PiP $inPip",
                RoamRules.target(trigger, saved, tailscale, current = tailscale, chosen = saved, inPip = inPip, currentAnswers = null))
        }
    }

    @Test
    fun `a working tailscale page is never moved to the saved address`() {
        // The old fail-back at a foreground start is gone: Tailscale first means a working Tailscale page stays.
        for ((trigger, inPip) in everyCase) {
            assertNull("$trigger, in PiP $inPip",
                RoamRules.target(trigger, saved, tailscale, current = tailscale, chosen = saved, inPip = inPip, currentAnswers = true))
        }
    }

    @Test
    fun `nothing happens without a tailscale address`() {
        // Implied by the rules after it as well (the chosen address is then neither of the two): this pins the behaviour, and a
        // mutant that drops only the explicit null check is equivalent.
        for (answers in listOf<Boolean?>(false, true, null)) {
            assertNull("second look $answers", RoamRules.target(start, saved, null, current = saved, chosen = tailscale, inPip = false, currentAnswers = answers))
        }
    }

    @Test
    fun `nothing happens when the page is on neither address, even when it is dead`() {
        val other = "https://other.example"
        for (answers in listOf<Boolean?>(false, true, null)) {
            assertNull(RoamRules.target(change, saved, tailscale, current = null, chosen = tailscale, inPip = false, currentAnswers = answers))
            assertNull(RoamRules.target(start, saved, tailscale, current = null, chosen = saved, inPip = false, currentAnswers = answers))
            assertNull(RoamRules.target(change, saved, tailscale, current = other, chosen = tailscale, inPip = false, currentAnswers = answers))
            assertNull(RoamRules.target(start, saved, tailscale, current = other, chosen = saved, inPip = false, currentAnswers = answers))
        }
    }

    @Test
    fun `nothing happens when nothing answered, even from a dead page`() {
        // (The watcher retries on its own schedule in that case: RoamRules.retryDelayAfter.)
        for (answers in listOf<Boolean?>(false, true, null)) {
            assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = null, inPip = false, currentAnswers = answers))
            assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = null, inPip = false, currentAnswers = answers))
        }
    }

    @Test
    fun `nothing happens when the choice is the address already on screen, even a dead one`() {
        for (answers in listOf<Boolean?>(false, true, null)) {
            assertNull(RoamRules.target(change, saved, tailscale, current = saved, chosen = saved, inPip = false, currentAnswers = answers))
            assertNull(RoamRules.target(start, saved, tailscale, current = tailscale, chosen = tailscale, inPip = false, currentAnswers = answers))
        }
    }

    @Test
    fun `a saved address that is its own tailscale address never moves to itself`() {
        // The case where only the same-address check stops a move (saved == tailscale == current == chosen): as an escape from
        // a dead page, and as an upgrade at a start.
        assertNull(RoamRules.target(change, tailscale, tailscale, current = tailscale, chosen = tailscale, inPip = false, currentAnswers = false))
        for (answers in answersOrUnknown) {
            assertNull(RoamRules.target(start, tailscale, tailscale, current = tailscale, chosen = tailscale, inPip = false, currentAnswers = answers))
        }
    }

    @Test
    fun `an unrelated chosen address is never moved to, even from a dead page`() {
        val other = "https://other.example"
        for (answers in listOf<Boolean?>(false, true, null)) {
            for ((trigger, inPip) in everyCase) {
                assertNull(RoamRules.target(trigger, saved, tailscale, current = saved, chosen = other, inPip = inPip, currentAnswers = answers))
                assertNull(RoamRules.target(trigger, saved, tailscale, current = tailscale, chosen = other, inPip = inPip, currentAnswers = answers))
            }
        }
    }

    // ---- heldBackAtLastStep: the watcher's last check on the main thread (Codex code review of Tailscale first, 2026-11-09) ----

    @Test
    fun `an escape is never held back by picture-in-picture, in either direction`() {
        assertFalse("escape to Tailscale", RoamRules.heldBackAtLastStep(RoamMove(tailscale, escape = true), inPip = true))
        assertFalse("escape to the saved address", RoamRules.heldBackAtLastStep(RoamMove(saved, escape = true), inPip = true))
        assertFalse("escape, not in PiP", RoamRules.heldBackAtLastStep(RoamMove(saved, escape = true), inPip = false))
    }

    @Test
    fun `an upgrade is held back in picture-in-picture`() {
        assertTrue(RoamRules.heldBackAtLastStep(RoamMove(tailscale, escape = false), inPip = true))
    }

    @Test
    fun `an upgrade outside picture-in-picture is not held back`() {
        assertFalse(RoamRules.heldBackAtLastStep(RoamMove(tailscale, escape = false), inPip = false))
    }

    // ---- boundedLook: the second look's overall bound (Codex code review of Tailscale first, 2026-11-09) ----

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun `a second look that answers in time gives its own answer`() {
        assertEquals(true, RoamRules.boundedLook(2_000L) { true })
        assertEquals(false, RoamRules.boundedLook(2_000L) { false })
    }

    @Test
    fun `a second look with no answer by the bound counts as dead, at the bound`() {
        // Window: the 500 ms bound against the look's own answer (5 s, and a "yes"), which an unbounded look would wait for.
        // The lower bound holds by construction: a timed-out wait has waited at least the bound.
        val (answer, ms) = timed {
            RoamRules.boundedLook(500L) {
                Thread.sleep(5_000)
                true
            }
        }
        assertEquals(false, answer)
        assertTrue("took $ms ms, not the 500 ms bound", ms in 490L..2_500L)
    }

    @Test
    fun `a second look that throws could not be taken`() {
        // Null, not false: a look that could not be taken never makes a move an escape.
        assertNull(RoamRules.boundedLook(2_000L) { throw IllegalStateException("boom") })
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
