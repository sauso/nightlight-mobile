package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules around the "Use this secure address?" prompt (PromptGate; Codex code review of PR #37, the plan's addendum and its
 * review, 2026-11-09). Each row is its own assertion with a message, so a dropped or flipped rule names itself.
 *
 * Not reachable here (Android glue, phone checks only): the dialog itself, its 120 s timeout, the dismissal and tap listeners,
 * cancelPending from MainActivity.onDestroy, and the re-check of the page on screen before the write (SecureAddressPrompt,
 * ServerConfigPlugin.addEndpoint).
 */
class PromptGateTest {

    private val candidate = "https://nightlight.example.ts.net"
    private val another = "https://other.example.ts.net"
    private val day = 24L * 60 * 60 * 1000
    private val week = 7 * day
    private val minute = 60_000L

    // A wall clock and a process clock far from zero, so no row depends on "time zero".
    private val wallNow = 1_800_000_000_000L
    private val now = 50_000_000L

    private fun decide(
        declined: String? = null,
        declinedAt: Long? = null,
        lastPromptAt: Long? = null,
        showing: Boolean = false,
    ) = PromptGate.decide(candidate, declined, declinedAt, wallNow, lastPromptAt, now, showing)

    private val ask = PromptGate.Decision.ASK
    private val quiet = PromptGate.Decision.REJECT_QUIETLY
    private val busy = PromptGate.Decision.BUSY

    @Test
    fun `the numbers are a week, ten minutes and two minutes`() {
        // Literal values, so the numbers the README states are what is pinned.
        assertEquals(604_800_000L, PromptGate.DECLINE_LIFETIME_MS)
        assertEquals(600_000L, PromptGate.COOLDOWN_MS)
        assertEquals(120_000L, PromptGate.ANSWER_TIMEOUT_MS)
    }

    @Test
    fun `with nothing in the way it asks`() {
        assertEquals(ask, decide())
    }

    @Test
    fun `one prompt at a time, whatever else holds`() {
        assertEquals(busy, decide(showing = true))
        assertEquals("showing beats a decline", busy, decide(declined = candidate, declinedAt = wallNow - minute, showing = true))
        assertEquals(busy, decide(lastPromptAt = now - minute, showing = true))
    }

    @Test
    fun `an address the person declined is not asked about again for a week`() {
        assertEquals("a minute ago", quiet, decide(declined = candidate, declinedAt = wallNow - minute))
        assertEquals("just inside the week", quiet, decide(declined = candidate, declinedAt = wallNow - week + 1))
        assertEquals("at exactly a week it has expired: asked once more", ask, decide(declined = candidate, declinedAt = wallNow - week))
        assertEquals("long ago", ask, decide(declined = candidate, declinedAt = wallNow - 30 * day))
    }

    @Test
    fun `a decline is for one address only`() {
        // A changed address is asked about: the decline was for another one.
        assertEquals(ask, PromptGate.decide(another, candidate, wallNow - minute, wallNow, null, now, false))
        assertEquals("no time stored with it", ask, decide(declined = candidate, declinedAt = null))
        assertEquals("no address stored with the time", ask, decide(declined = null, declinedAt = wallNow - minute))
    }

    @Test
    fun `a decline dated in the future does not apply`() {
        // The clock was set back: trusting it could stretch "a week at most" without bound.
        assertEquals(ask, decide(declined = candidate, declinedAt = wallNow + minute))
    }

    @Test
    fun `at most one prompt per ten minutes`() {
        assertEquals("a minute after the last", busy, decide(lastPromptAt = now - minute))
        assertEquals("just inside ten minutes", busy, decide(lastPromptAt = now - 10 * minute + 1))
        assertEquals("at exactly ten minutes", ask, decide(lastPromptAt = now - 10 * minute))
        assertEquals("a declined address is refused quietly, cooldown or not", quiet,
            decide(declined = candidate, declinedAt = wallNow - minute, lastPromptAt = now - minute))
    }

    @Test
    fun `only the two taps are an answer, and only the decline is remembered`() {
        assertEquals(PromptGate.Effect.SAVE, PromptGate.effectOf(PromptGate.Outcome.USE_IT))
        assertEquals(PromptGate.Effect.REMEMBER_DECLINE_AND_REFUSE, PromptGate.effectOf(PromptGate.Outcome.DONT_USE_IT))
        // Every ending the person did not choose refuses and stores nothing, so it can never silence the address for a week.
        val notAnAnswer = PromptGate.Outcome.values().filter { it != PromptGate.Outcome.USE_IT && it != PromptGate.Outcome.DONT_USE_IT }
        assertEquals(5, notAnAnswer.size)
        for (outcome in notAnAnswer) {
            assertEquals("$outcome", PromptGate.Effect.REFUSE, PromptGate.effectOf(outcome))
        }
    }

    @Test
    fun `reserving takes the one slot, and the cooldown starts only when a prompt is shown`() {
        val gate = PromptGate()
        assertEquals(ask, gate.reserve(candidate, null, null, wallNow, now))
        assertEquals("a second offer while the first is pending", busy, gate.reserve(another, null, null, wallNow, now + 1))
        gate.release()
        // Never shown (no activity, an error): no cooldown, the next offer is asked about.
        assertEquals("released without being shown", ask, gate.reserve(another, null, null, wallNow, now + 2))
        gate.shown(now + 3)
        gate.release()
        assertEquals("shown and released, still inside the cooldown", busy, gate.reserve(another, null, null, wallNow, now + 3 + minute))
        assertEquals("the cooldown counts from when it was shown", ask, gate.reserve(another, null, null, wallNow, now + 3 + 10 * minute))
    }

    @Test
    fun `the refusal sentences follow the iPhone app's mapping`() {
        assertEquals("a decline still in force", EndpointRules.AddCheck.NOT_ACCEPTED, PromptGate.refusalWhenNotAsked(quiet))
        assertEquals("a busy gate declined nothing", EndpointRules.AddCheck.NOT_CONFIRMED, PromptGate.refusalWhenNotAsked(busy))
        assertEquals(EndpointRules.AddCheck.OK, PromptGate.refusalWhenNotAsked(ask))
        assertEquals("the explicit tap", EndpointRules.AddCheck.NOT_ACCEPTED, PromptGate.refusalAfter(PromptGate.Effect.REMEMBER_DECLINE_AND_REFUSE))
        assertEquals("any other ending", EndpointRules.AddCheck.NOT_CONFIRMED, PromptGate.refusalAfter(PromptGate.Effect.REFUSE))
        assertEquals(EndpointRules.AddCheck.OK, PromptGate.refusalAfter(PromptGate.Effect.SAVE))
    }

    @Test
    fun `a refusal does not take the slot or start the cooldown`() {
        val gate = PromptGate()
        assertEquals(quiet, gate.reserve(candidate, candidate, wallNow - minute, wallNow, now))
        assertEquals("asked straight after the quiet refusal", ask, gate.reserve(another, candidate, wallNow - minute, wallNow, now + 1))
    }

    @Test
    fun `the prompt shows the address the offer came from and the whole secure name`() {
        val body = PromptGate.promptBody("http://192.168.1.50:4000", "https://nightlight.example-tailnet.ts.net")
        assertTrue(body, body.contains("http://192.168.1.50:4000"))
        assertTrue("the full host, never shortened: $body", body.contains("nightlight.example-tailnet.ts.net"))
    }
}
