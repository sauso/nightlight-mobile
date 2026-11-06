package com.sauso.nightlight

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Which of a server's addresses to open at launch: the local one if it answers, else the Tailscale one
 * (owner, 2026-11-06; Connect & Discovery design section 9.2).
 *
 * Both are probed AT ONCE, under one deadline for the whole decision, so being away from home costs the wait for the
 * local address to give up once, not once per address. The rule that matters: a quick Tailscale answer must NOT beat a
 * local address that is merely a little slower, because at home the local path is the better one (no relay, no
 * dependence on Tailscale). So while the local probe is still running and the deadline has not passed, the chooser
 * keeps waiting for it, whatever Tailscale has said. It stops waiting for local only when local has FAILED (a refused or
 * erroring connection ends at once) or when the deadline arrives (a connect to an address that is not on this network
 * usually just goes quiet).
 *
 * Pure Kotlin with no Android imports. The probe is injected: production passes ServerConfigPlugin.isReachable.
 * A probe that runs past the deadline is simply abandoned (its thread is a daemon and its late answer is ignored).
 * A probe that throws counts as a failure.
 */
class EndpointChooser(
    private val probe: (String) -> Boolean,
    private val deadlineMs: Long = DEADLINE_MS,
) {
    companion object {
        // The same total the launch gate waited for before there were two addresses (MainActivity: join(3000)): the probes
        // themselves time out at 2.5 s, so this is the longest a launch waits for the server, once, whatever the number of
        // addresses. A judgement carried over, not a measurement.
        const val DEADLINE_MS = 3_000L
    }

    private enum class State { PENDING, OK, FAILED, NONE }

    /** The address to open, or null when neither answered in time (the caller then shows the setup page). */
    fun choose(local: String, tailscale: String?): String? {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        var localState = State.PENDING
        var tailscaleState = if (tailscale == null) State.NONE else State.PENDING

        fun start(url: String, finished: (Boolean) -> Unit) {
            val worker = Thread {
                val ok = try { probe(url) } catch (e: Exception) { false }
                lock.lock()
                try {
                    finished(ok)
                    changed.signalAll()
                } finally {
                    lock.unlock()
                }
            }
            worker.isDaemon = true
            worker.start()
        }
        start(local) { localState = if (it) State.OK else State.FAILED }
        if (tailscale != null) start(tailscale) { tailscaleState = if (it) State.OK else State.FAILED }

        val end = System.nanoTime() + deadlineMs * 1_000_000L
        lock.lock()
        try {
            while (true) {
                if (localState == State.OK) return local
                val timeUp = System.nanoTime() >= end
                if (localState == State.FAILED || timeUp) {
                    // Local is given up on (failed, or silent until the deadline).
                    if (tailscaleState == State.OK) return tailscale
                    if (tailscaleState != State.PENDING || timeUp) return null
                    // Tailscale is still running and there is time left: wait for it.
                }
                val leftNs = end - System.nanoTime()
                if (leftNs > 0) changed.awaitNanos(leftNs)
            }
        } finally {
            lock.unlock()
        }
    }
}
