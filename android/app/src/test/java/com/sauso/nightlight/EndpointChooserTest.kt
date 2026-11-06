package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which address the app opens at launch. The probes are fakes with controllable delays; the margins are generous (a
 * slow CI machine must not flake), but each assertion is far from what the mutant it kills would take.
 *
 * The one rule worth a test of its own: a FAST Tailscale answer must not beat a SLOW local one.
 */
class EndpointChooserTest {

    private val local = "http://192.168.1.50:4000"
    private val tailscale = "https://nightlight.example.ts.net"

    private fun chooser(deadlineMs: Long = 1_500L, answers: Map<String, Pair<Long, Boolean>>) =
        EndpointChooser(
            probe = { url ->
                val (delayMs, ok) = answers.getValue(url)
                Thread.sleep(delayMs)
                ok
            },
            deadlineMs = deadlineMs,
        )

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun `local answering wins, and does not wait for tailscale`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (20L to true), tailscale to (1_200L to true))).choose(local, tailscale) }
        assertEquals(local, chosen)
        assertTrue("waited $ms ms for the Tailscale probe", ms < 800)
    }

    @Test
    fun `a FAST tailscale answer does not beat a slower local one`() {
        // Tailscale says yes at once; local says yes after 400 ms. At home the local path is the right one.
        val chosen = chooser(answers = mapOf(local to (400L to true), tailscale to (10L to true))).choose(local, tailscale)
        assertEquals(local, chosen)
    }

    @Test
    fun `local failing fast falls to tailscale as soon as tailscale answers`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (10L to false), tailscale to (200L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `local that goes quiet is given up at the deadline and tailscale is used`() {
        // Local never answers within the deadline (an address that is not on this network); tailscale answered long ago.
        val (chosen, ms) = timed { chooser(deadlineMs = 600L, answers = mapOf(local to (5_000L to true), tailscale to (10L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("decided after $ms ms, not at the deadline", ms in 500L..1_800L)
    }

    @Test
    fun `a local answer that arrives after the deadline is ignored`() {
        val chosen = chooser(deadlineMs = 300L, answers = mapOf(local to (700L to true), tailscale to (10L to false))).choose(local, tailscale)
        assertNull(chosen)
    }

    @Test
    fun `neither answering gives nothing`() {
        assertNull(chooser(answers = mapOf(local to (10L to false), tailscale to (10L to false))).choose(local, tailscale))
    }

    @Test
    fun `with no tailscale address only local counts, and its failure ends the wait at once`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (10L to false))).choose(local, null) }
        assertNull(chosen)
        assertTrue("took $ms ms", ms < 800)
        assertEquals(local, chooser(answers = mapOf(local to (10L to true))).choose(local, null))
    }

    @Test
    fun `a probe that throws counts as a failure`() {
        val chooser = EndpointChooser(probe = { url -> if (url == local) throw IllegalStateException("boom") else true }, deadlineMs = 1_000L)
        assertEquals(tailscale, chooser.choose(local, tailscale))
    }

    @Test
    fun `local failing and tailscale still running waits for tailscale within the deadline`() {
        val chosen = chooser(deadlineMs = 1_500L, answers = mapOf(local to (10L to false), tailscale to (500L to true))).choose(local, tailscale)
        assertEquals(tailscale, chosen)
    }

    @Test
    fun `tailscale is probed only when there is one, and each address once`() {
        val calls = AtomicInteger()
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val chooser = EndpointChooser(probe = { url -> calls.incrementAndGet(); seen.add(url); true }, deadlineMs = 1_000L)
        assertEquals(local, chooser.choose(local, null))
        Thread.sleep(100)
        assertEquals(listOf(local), seen.toList())
    }

    // The saved address's OWN shorter wait (off Wi-Fi, a home-network-only address): see EndpointRules.localWaitMs.
    private fun withLocalWait(localWaitMs: Long, deadlineMs: Long, answers: Map<String, Pair<Long, Boolean>>) =
        EndpointChooser(
            probe = { url ->
                val (delayMs, ok) = answers.getValue(url)
                Thread.sleep(delayMs)
                ok
            },
            deadlineMs = deadlineMs,
            localDeadlineMs = localWaitMs,
        )

    @Test
    fun `a silent saved address is given up after its own short wait, not the whole deadline`() {
        val (chosen, ms) = timed { withLocalWait(300L, 2_500L, mapOf(local to (5_000L to true), tailscale to (10L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms: it waited for the whole deadline", ms in 250L..1_500L)
    }

    @Test
    fun `a saved address that answers inside its short wait still wins over a quicker tailscale`() {
        val chosen = withLocalWait(800L, 2_500L, mapOf(local to (300L to true), tailscale to (10L to true))).choose(local, tailscale)
        assertEquals(local, chosen)
    }

    @Test
    fun `after its short wait a saved address that answers before the deadline still beats a slow tailscale`() {
        // Local gives up at 200 ms, tailscale answers at 900 ms, local answers at 500 ms: nothing has been chosen yet, so local wins.
        val chosen = withLocalWait(200L, 2_500L, mapOf(local to (500L to true), tailscale to (900L to true))).choose(local, tailscale)
        assertEquals(local, chosen)
    }

    @Test
    fun `the short wait cannot make the decision later than the whole deadline`() {
        // A short wait longer than the deadline is clamped to it: the whole decision still ends at the deadline.
        val (chosen, ms) = timed { withLocalWait(10_000L, 400L, mapOf(local to (5_000L to true), tailscale to (10L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms", ms in 300L..1_500L)
    }

    @Test
    fun `with no tailscale address a short wait still gives up on the saved address when it expires`() {
        // (MainActivity never passes a short wait without a tailscale address; the chooser itself still ends there.)
        val (chosen, ms) = timed { withLocalWait(300L, 2_500L, mapOf(local to (5_000L to true))).choose(local, null) }
        assertNull(chosen)
        assertTrue("took $ms ms", ms < 1_500)
    }
}
