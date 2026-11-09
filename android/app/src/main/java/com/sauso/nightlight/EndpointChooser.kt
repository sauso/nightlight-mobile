package com.sauso.nightlight

import java.util.concurrent.locks.ReentrantLock

/**
 * Which of a server's addresses to open at launch: the Tailscale one if it answers, else the saved one (owner decision
 * 2026-11-09, "we go Tailscale first"; it reverses the saved-first rule of 2026-11-06). Why: on a hotspot with Tailscale routing
 * the home network, the plain-http saved address still answered (through the tailnet), so the app stayed on it and two-way
 * talk, which needs HTTPS, could not work although the secure address was right there. The Tailscale address only counts when
 * the server there reports the id it was saved for; the caller's probe checks that (MainActivity.chooseAddress), so an
 * unverified Tailscale address is never opened. With no Tailscale address saved, only the saved one is probed; its caller
 * checks a known saved id before treating it as an answer.
 *
 * Both addresses are probed AT ONCE, under one deadline for the whole decision, so a launch waits once, not once per address.
 * [decide] is the rule, re-applied every time an answer arrives or a time limit is reached:
 *  1. Tailscale answered -> Tailscale, whatever the saved address said and whenever (before the deadline) it said it.
 *  2. The saved address answered -> saved, once Tailscale can no longer win: there is none, it FAILED, or the GRACE
 *     ([tailscaleGraceMs], counted from the start of the decision) is over. The grace is what stops a launch from waiting the
 *     whole deadline for a Tailscale that is switched off. A Tailscale failure (refused, DNS, TLS, an error status, a probe
 *     that throws) ends the grace at once.
 *  3. Out of time, or both failed -> nothing. Otherwise wait for the next answer, the end of the grace or the deadline.
 * So a saved answer that comes after Tailscale has failed still wins, and a Tailscale answer that comes after the saved address
 * failed or while it is silent still wins, as long as each comes before the deadline.
 *
 * Timing (Codex plan review of Tailscale first, 2026-11-09, which named this race): the clock starts BEFORE either probe starts;
 * an answer that completed after the deadline never counts; a probe that runs past the decision is abandoned (its thread is a
 * daemon) and its late answer changes nothing, the next decision included (each decision keeps its own answers).
 *
 * Pure Kotlin with no Android imports. The probe is injected: production passes an id-checked health read for the Tailscale
 * address and a strict id read for a saved address with a known id (legacy reachability otherwise). A probe that throws
 * counts as FAILED.
 */
class EndpointChooser(
    private val probe: (String) -> Boolean,
    private val deadlineMs: Long = DEADLINE_MS,
    private val tailscaleGraceMs: Long = EndpointRules.TAILSCALE_GRACE_MS,
) {
    companion object {
        // The same total the launch gate waited for before there were two addresses (MainActivity: join(3000)): the probes
        // themselves time out at 2.5 s, so this is the longest a launch waits for the server, once, whatever the number of
        // addresses. A judgement carried over, not a measurement.
        const val DEADLINE_MS = 3_000L

        /**
         * The decision, as a table the tests read directly. [graceOver] and [deadlineOver] say whether those times have been
         * reached. The ORDER of the checks is the rule (Codex plan review of Tailscale first, 2026-11-09): Tailscale is checked
         * first, so a Tailscale answer and the end of the grace seen at the same moment open Tailscale.
         */
        fun decide(saved: Answer, tailscale: Answer, graceOver: Boolean, deadlineOver: Boolean): Pick {
            // 1. Tailscale answered (with its id): it wins, even over a saved address that answered first.
            if (tailscale == Answer.OK) return Pick.TAILSCALE
            // 2. The saved address answered and Tailscale can no longer win. The deadline ends the grace too, whatever grace
            //    the caller passed, so a saved answer is never thrown away for a Tailscale that did not answer in time.
            if (saved == Answer.OK &&
                (tailscale == Answer.NONE || tailscale == Answer.FAILED || graceOver || deadlineOver)
            ) return Pick.SAVED
            // 3. Out of time, or nothing left that could answer.
            if (deadlineOver) return Pick.NOTHING
            if (saved == Answer.FAILED && (tailscale == Answer.FAILED || tailscale == Answer.NONE)) return Pick.NOTHING
            // 4. Something can still answer in time.
            return Pick.WAIT
        }
    }

    /**
     * One address's state. FAILED is ANY completed probe that did not succeed (refused, DNS, TLS, an error status, a throw);
     * NONE is a Tailscale address that is not saved at all.
     */
    enum class Answer { PENDING, OK, FAILED, NONE }

    /** What [decide] says to do now: open the saved address, open the Tailscale one, open nothing, or keep waiting. */
    enum class Pick { SAVED, TAILSCALE, NOTHING, WAIT }

    /** The address to open, or null when nothing answered in time (the caller then shows the setup page). */
    fun choose(local: String, tailscale: String?): String? {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        var savedState = Answer.PENDING
        var tailscaleState = if (tailscale == null) Answer.NONE else Answer.PENDING

        // The clock starts BEFORE either probe, so starting the threads cannot stretch the grace or the deadline.
        val started = System.nanoTime()
        val end = started + deadlineMs * 1_000_000L
        // Never past the deadline and never before the start: the grace can only shorten the decision, never lengthen it.
        val graceEnd = started + minOf(maxOf(tailscaleGraceMs, 0L), deadlineMs) * 1_000_000L

        fun start(url: String, record: (Answer) -> Unit) {
            val worker = Thread {
                val ok = try { probe(url) } catch (e: Exception) { false }
                // Read BEFORE taking the lock: an answer that completed after the deadline never counts, even when this worker
                // gets the lock before the waiting thread has noticed the deadline.
                val finishedAt = System.nanoTime()
                lock.lock()
                try {
                    if (finishedAt - end <= 0L) {
                        record(if (ok) Answer.OK else Answer.FAILED)
                        changed.signalAll()
                    }
                } finally {
                    lock.unlock()
                }
            }
            worker.isDaemon = true
            worker.start()
        }
        start(local) { savedState = it }
        if (tailscale != null) start(tailscale) { tailscaleState = it }

        lock.lock()
        try {
            while (true) {
                // nanoTime is compared by difference (its value may be negative or wrap; a difference does not).
                val now = System.nanoTime()
                when (decide(savedState, tailscaleState, graceOver = now - graceEnd >= 0L, deadlineOver = now - end >= 0L)) {
                    Pick.TAILSCALE -> return tailscale
                    Pick.SAVED -> return local
                    Pick.NOTHING -> return null
                    Pick.WAIT -> {}
                }
                // Wake for whichever comes first: an answer (signalAll above), the end of the grace (it only matters while the
                // saved address has answered and Tailscale is still running), or the deadline. WAIT is never returned once the
                // deadline has passed, nor with a saved answer once the grace has, so the wait below is always a positive time.
                val wakeAt = if (savedState == Answer.OK && tailscaleState == Answer.PENDING && now - graceEnd < 0L) graceEnd else end
                val leftNs = wakeAt - now
                if (leftNs > 0L) changed.awaitNanos(leftNs)
            }
        } finally {
            lock.unlock()
        }
    }
}
