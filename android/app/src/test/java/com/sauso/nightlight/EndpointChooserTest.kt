package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which address the app opens at launch: Tailscale first, the saved address when Tailscale does not answer (owner decision
 * 2026-11-09). The probes are fakes with controllable delays; the margins are generous (a slow CI machine must not flake), but
 * each assertion is far from what the mutant it kills would take.
 *
 * Two threads cannot be made to finish at one exact instant, so the row "a Tailscale answer and the end of the grace are seen
 * at the same moment" is tested on the decision table itself (EndpointChooser.decide), which is the code that orders them.
 * Not tested (a race no fake can stage on demand): a probe that completes after the deadline but takes the lock before the
 * waiting thread notices the deadline (the worker's own time check).
 */
class EndpointChooserTest {

    private val local = "http://192.168.1.50:4000"
    private val tailscale = "https://nightlight.example.ts.net"

    // Each address answers `ok` after `delayMs`. Defaults: a 2 s deadline and a 500 ms grace; each test sets what it is about.
    private fun chooser(deadlineMs: Long = 2_000L, graceMs: Long = 500L, answers: Map<String, Pair<Long, Boolean>>) =
        EndpointChooser(
            probe = { url ->
                val (delayMs, ok) = answers.getValue(url)
                Thread.sleep(delayMs)
                ok
            },
            deadlineMs = deadlineMs,
            tailscaleGraceMs = graceMs,
        )

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000L
    }

    // ---- the rule, timed with real threads ----

    @Test
    fun `a tailscale answer is opened at once, without waiting for the saved address`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (1_200L to true), tailscale to (20L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("waited $ms ms for the saved address", ms < 800)
    }

    @Test
    fun `a tailscale answer inside the grace beats a saved answer that came first`() {
        val (chosen, ms) = timed {
            chooser(graceMs = 1_000L, answers = mapOf(local to (10L to true), tailscale to (250L to true))).choose(local, tailscale)
        }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms: it waited for the end of the grace instead of the answer", ms < 900)
    }

    @Test
    fun `a saved answer is opened at the end of the grace when tailscale is slower`() {
        val (chosen, ms) = timed {
            chooser(deadlineMs = 2_500L, graceMs = 300L, answers = mapOf(local to (10L to true), tailscale to (1_500L to true))).choose(local, tailscale)
        }
        assertEquals(local, chosen)
        assertTrue("decided after $ms ms, not at the end of the 300 ms grace", ms in 290L..1_200L)
    }

    @Test
    fun `tailscale refusing after a saved answer ends the grace at once`() {
        val (chosen, ms) = timed {
            chooser(deadlineMs = 2_500L, graceMs = 1_500L, answers = mapOf(local to (10L to true), tailscale to (100L to false))).choose(local, tailscale)
        }
        assertEquals(local, chosen)
        assertTrue("took $ms ms: it waited out the grace after Tailscale had failed", ms < 1_000)
    }

    @Test
    fun `a tailscale probe that throws after a saved answer ends the grace at once`() {
        val chooser = EndpointChooser(
            probe = { url ->
                if (url == local) {
                    Thread.sleep(10)
                    true
                } else {
                    Thread.sleep(100)
                    throw IllegalStateException("handshake failed")
                }
            },
            deadlineMs = 2_500L,
            tailscaleGraceMs = 1_500L,
        )
        val (chosen, ms) = timed { chooser.choose(local, tailscale) }
        assertEquals(local, chosen)
        assertTrue("took $ms ms: the throw was not counted as a failure", ms < 1_000)
    }

    @Test
    fun `a saved answer that comes after tailscale failed still wins, even after the grace`() {
        val (chosen, ms) = timed {
            chooser(graceMs = 300L, answers = mapOf(local to (700L to true), tailscale to (10L to false))).choose(local, tailscale)
        }
        assertEquals(local, chosen)
        assertTrue("took $ms ms", ms in 650L..1_800L)
    }

    @Test
    fun `a failed saved address waits for a slow tailscale, which still wins before the deadline`() {
        val (chosen, ms) = timed {
            chooser(deadlineMs = 2_500L, graceMs = 300L, answers = mapOf(local to (10L to false), tailscale to (900L to true))).choose(local, tailscale)
        }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms", ms in 850L..2_200L)
    }

    @Test
    fun `a silent saved address does not hold up a tailscale answer`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (5_000L to true), tailscale to (200L to true))).choose(local, tailscale) }
        assertEquals(tailscale, chosen)
        assertTrue("took $ms ms", ms < 1_200)
    }

    @Test
    fun `both failing gives nothing at once`() {
        val (chosen, ms) = timed { chooser(answers = mapOf(local to (10L to false), tailscale to (30L to false))).choose(local, tailscale) }
        assertNull(chosen)
        assertTrue("took $ms ms: it waited for the deadline with nothing left to answer", ms < 1_000)
    }

    @Test
    fun `both silent gives nothing at the deadline, and not much later`() {
        val (chosen, ms) = timed {
            chooser(deadlineMs = 600L, answers = mapOf(local to (5_000L to true), tailscale to (5_000L to true))).choose(local, tailscale)
        }
        assertNull(chosen)
        assertTrue("decided after $ms ms, not at the 600 ms deadline", ms in 590L..1_800L)
    }

    @Test
    fun `a tailscale answer after the deadline is not used`() {
        val (chosen, ms) = timed {
            chooser(deadlineMs = 500L, graceMs = 300L, answers = mapOf(local to (10L to false), tailscale to (2_000L to true))).choose(local, tailscale)
        }
        assertNull(chosen)
        assertTrue("decided after $ms ms, not at the 500 ms deadline", ms in 490L..1_500L)
    }

    @Test
    fun `the grace never stretches the decision past the deadline`() {
        // A grace longer than the whole decision ends at the deadline: the saved answer is opened then, not a Tailscale answer
        // that only comes later.
        val (chosen, ms) = timed {
            chooser(deadlineMs = 600L, graceMs = 5_000L, answers = mapOf(local to (10L to true), tailscale to (3_000L to true))).choose(local, tailscale)
        }
        assertEquals(local, chosen)
        assertTrue("decided after $ms ms, not at the 600 ms deadline", ms in 590L..1_800L)
    }

    @Test
    fun `a probe that throws counts as a failure`() {
        val chooser = EndpointChooser(probe = { _ -> throw IllegalStateException("boom") }, deadlineMs = 2_000L, tailscaleGraceMs = 500L)
        val (chosen, ms) = timed { chooser.choose(local, tailscale) }
        assertNull(chosen)
        assertTrue("took $ms ms: the throws were not counted as failures", ms < 1_000)
    }

    // ---- no Tailscale address saved: exactly the old single-address launch ----

    @Test
    fun `with no tailscale address a saved answer is opened at once, with no grace`() {
        val (chosen, ms) = timed { chooser(deadlineMs = 2_500L, graceMs = 1_500L, answers = mapOf(local to (10L to true))).choose(local, null) }
        assertEquals(local, chosen)
        assertTrue("took $ms ms: it waited for a Tailscale address that does not exist", ms < 1_000)
    }

    @Test
    fun `with no tailscale address a failed saved address gives nothing at once`() {
        val (chosen, ms) = timed { chooser(deadlineMs = 2_500L, answers = mapOf(local to (10L to false))).choose(local, null) }
        assertNull(chosen)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `with no tailscale address a silent saved address gives nothing at the deadline`() {
        val (chosen, ms) = timed { chooser(deadlineMs = 500L, answers = mapOf(local to (3_000L to true))).choose(local, null) }
        assertNull(chosen)
        assertTrue("decided after $ms ms, not at the 500 ms deadline", ms in 490L..2_000L)
    }

    @Test
    fun `tailscale is probed only when there is one, and each address once`() {
        val seen = CopyOnWriteArrayList<String>()
        val chooser = EndpointChooser(probe = { url -> seen.add(url); true }, deadlineMs = 1_000L)
        assertEquals(local, chooser.choose(local, null))
        Thread.sleep(100)
        assertEquals(listOf(local), seen.toList())
        seen.clear()
        assertEquals(tailscale, chooser.choose(local, tailscale))
        Thread.sleep(100)
        assertEquals(listOf(local, tailscale), seen.toList().sorted())
    }

    @Test
    fun `a probe that finishes after the decision changes nothing, not even the next decision`() {
        val savedCalls = AtomicInteger()
        val tailscaleCalls = AtomicInteger()
        val chooser = EndpointChooser(
            probe = { url ->
                if (url == local) {
                    val call = savedCalls.incrementAndGet()
                    Thread.sleep(10)
                    call == 1
                } else {
                    val call = tailscaleCalls.incrementAndGet()
                    Thread.sleep(if (call == 1) 600L else 900L)
                    call == 1
                }
            },
            deadlineMs = 2_000L,
            tailscaleGraceMs = 200L,
        )
        // First decision: the saved address answers at once and Tailscale only at 600 ms, after the 200 ms grace, so the saved
        // address is opened while that Tailscale probe is still running.
        assertEquals(local, chooser.choose(local, tailscale))
        // Second decision, straight away: the saved address fails at once and this decision's own Tailscale probe fails at
        // 900 ms. The first decision's Tailscale probe says "yes" about 400 ms into it: that answer belongs to a decision that
        // is over, and must not open anything here.
        val (second, ms) = timed { chooser.choose(local, tailscale) }
        assertNull("a late answer from the first decision was used", second)
        assertTrue("decided after $ms ms, not when its own Tailscale probe failed", ms in 850L..2_500L)
        assertEquals(2, tailscaleCalls.get())
    }

    // ---- the decision table (EndpointChooser.decide), row by row ----

    private val pending = EndpointChooser.Answer.PENDING
    private val ok = EndpointChooser.Answer.OK
    private val failed = EndpointChooser.Answer.FAILED
    private val none = EndpointChooser.Answer.NONE
    private val openSaved = EndpointChooser.Pick.SAVED
    private val openTailscale = EndpointChooser.Pick.TAILSCALE
    private val nothing = EndpointChooser.Pick.NOTHING
    private val wait = EndpointChooser.Pick.WAIT

    @Test
    fun `a tailscale answer seen at the very moment the grace ends opens tailscale`() {
        // The saved address answered first; the Tailscale answer and the end of the grace are seen together. Tailscale is
        // checked first, so it wins (Codex plan review of Tailscale first, 2026-11-09).
        assertEquals(openTailscale, EndpointChooser.decide(ok, ok, graceOver = true, deadlineOver = false))
        // And at the deadline itself (an answer is only recorded at all if it completed by then).
        assertEquals(openTailscale, EndpointChooser.decide(ok, ok, graceOver = true, deadlineOver = true))
    }

    @Test
    fun `tailscale answering wins whatever the saved address said`() {
        for (saved in listOf(pending, ok, failed)) {
            for (graceOver in listOf(false, true)) {
                assertEquals("saved $saved, grace over $graceOver", openTailscale, EndpointChooser.decide(saved, ok, graceOver, deadlineOver = false))
            }
        }
    }

    @Test
    fun `a saved answer waits for a running tailscale only until the grace or the deadline`() {
        assertEquals("inside the grace", wait, EndpointChooser.decide(ok, pending, graceOver = false, deadlineOver = false))
        assertEquals("the grace is over", openSaved, EndpointChooser.decide(ok, pending, graceOver = true, deadlineOver = false))
        assertEquals("the deadline ends the grace too", openSaved, EndpointChooser.decide(ok, pending, graceOver = false, deadlineOver = true))
        assertEquals("Tailscale failed: the grace ends at once", openSaved, EndpointChooser.decide(ok, failed, graceOver = false, deadlineOver = false))
        assertEquals("no Tailscale address: no grace at all", openSaved, EndpointChooser.decide(ok, none, graceOver = false, deadlineOver = false))
    }

    @Test
    fun `a failed or silent saved address leaves the decision to tailscale until the deadline`() {
        assertEquals("saved failed, Tailscale running: wait for it", wait, EndpointChooser.decide(failed, pending, graceOver = true, deadlineOver = false))
        assertEquals("saved failed, Tailscale still running at the deadline", nothing, EndpointChooser.decide(failed, pending, graceOver = true, deadlineOver = true))
        assertEquals("both failed", nothing, EndpointChooser.decide(failed, failed, graceOver = false, deadlineOver = false))
        assertEquals("saved failed, no Tailscale address", nothing, EndpointChooser.decide(failed, none, graceOver = false, deadlineOver = false))
        assertEquals("saved silent, Tailscale failed: a saved answer can still come", wait, EndpointChooser.decide(pending, failed, graceOver = true, deadlineOver = false))
        assertEquals("saved silent, no Tailscale address", wait, EndpointChooser.decide(pending, none, graceOver = true, deadlineOver = false))
        assertEquals("both still running", wait, EndpointChooser.decide(pending, pending, graceOver = true, deadlineOver = false))
        for (t in listOf(pending, failed, none)) {
            assertEquals("saved silent at the deadline, Tailscale $t", nothing, EndpointChooser.decide(pending, t, graceOver = true, deadlineOver = true))
        }
    }
}
