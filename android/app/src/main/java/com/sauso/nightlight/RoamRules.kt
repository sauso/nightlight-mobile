package com.sauso.nightlight

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The rules for moving the app to the server's other address when the network changes (owner, 2026-11-06: walk out of the
 * house and Wi-Fi turns to mobile data, and the page sat on a dead home address until the app was restarted).
 *
 * The idea is deliberately small: when the network changes, the app runs the SAME choice the launch runs
 * (MainActivity.chooseAddress: the Tailscale address first, the saved one when Tailscale does not answer; owner decision
 * 2026-11-09), and if that picks a different address from the one on screen, and [target] allows the move, the activity is
 * recreated, which re-runs the launch gate. No new way of choosing. What is new is WHEN a switch may happen and how often,
 * which is all here.
 *
 * Pure Kotlin with no Android imports, so the JVM unit tests (RoamRulesTest) run exactly this code. The glue that feeds it
 * (the network callback, the main-thread reads, the recreate) is NetworkRoamWatcher, which only a phone can test.
 */
object RoamRules {

    /** Why an address check runs. Only a foreground start may UPGRADE a working page to Tailscale (see [target]). */
    enum class Trigger { NETWORK_CHANGE, FOREGROUND_START }

    // How long a network event waits before the check runs, and how long after the app comes to the front the start check
    // runs. A judgement, not a measurement: a new network and the Tailscale tunnel need a moment to settle before a probe means
    // anything, and every event inside the wait restarts it, so a flapping network costs one check, not one per event.
    const val SETTLE_MS = 2_000L

    /**
     * Which of the server's two addresses the page the WebView is really showing ([webViewUrl]) belongs to: [saved],
     * [tailscale], or null for anything else (the bundled setup page, the bundled "Can't reach your server" page, another
     * port or scheme). Compared by origin, so a route or hash on the page does not matter.
     *
     * The caller passes the WebView's own URL, NOT the bridge's configured server: the bridge keeps its remote address while a
     * bundled error page is on screen, and the app must never "switch" a page that is not on either address (Codex review of
     * the plan). On those pages nothing happens by itself: they already offer retry and the saved servers.
     */
    fun currentAddress(webViewUrl: String?, saved: String, tailscale: String): String? = when {
        SessionRules.sameOrigin(webViewUrl, saved) -> saved
        SessionRules.sameOrigin(webViewUrl, tailscale) -> tailscale
        else -> null
    }

    /**
     * The move to make, or null to stay. Under Tailscale first (owner decision 2026-11-09) the launch's choice alone cannot say
     * whether a move is urgent: the choice can pick the saved address because Tailscale was a little slow, while the Tailscale
     * page on screen works perfectly. So the watcher takes a SECOND LOOK at the page on screen first, and this decides on both
     * (Codex plan review of Tailscale first, 2026-11-09):
     *  - [current] is [currentAddress]'s answer for the page on screen; [chosen] is what the launch's choice picked just now
     *    (null when neither address answered); [currentAnswers] is the second look at [current]: true = it answers, false =
     *    it is DEAD (a completed failed probe, including its timeout), null = the look could not be taken.
     *  - ESCAPE (the page on screen is dead): move to [chosen] at once, in either direction, for either trigger and even in
     *    Picture-in-Picture: the page is already dead, so a reload loses nothing that still works. Only to one of the two
     *    addresses.
     *  - UPGRADE (the saved address is on screen and works, and Tailscale answers): allowed ONLY at a foreground start, and not
     *    in Picture-in-Picture (owner, 2026-11-06, refined after the Codex plan review of the roaming plan; unchanged by Tailscale
     *    first). The saved page keeps working, so there is no hurry, and a reload while someone is looking at the app would cut a
     *    form, an upload or a video. A start (the app opened again, or the screen turned back on with the app in front) is the
     *    moment nothing is lost.
     *  - A WORKING Tailscale page is never moved to the saved address, and a working saved page is not moved on a network
     *    change. "Not known" (null) counts as working: a doubt never reloads a page.
     *  - Anything else stays: nothing chosen, the same address, a page on neither address, no Tailscale address saved, a chosen
     *    address that is neither of the two.
     * (The null checks below are also implied by the rules after them; they are written out so the rule reads as stated. The
     * same-address check is not: it is what stops a saved address that equals its own Tailscale address from "moving" to
     * itself. Neither is the check that the page is on one of the two addresses: [currentAddress] only ever gives one of them
     * or null, and this keeps it so if a caller passes anything else.)
     */
    fun target(
        trigger: Trigger,
        saved: String,
        tailscale: String?,
        current: String?,
        chosen: String?,
        inPip: Boolean,
        currentAnswers: Boolean?,
    ): RoamMove? {
        if (tailscale == null || current == null || chosen == null || chosen == current) return null
        if (current != saved && current != tailscale) return null
        if (currentAnswers == false) {
            return if (chosen == saved || chosen == tailscale) RoamMove(chosen, escape = true) else null
        }
        if (current == saved && chosen == tailscale && trigger == Trigger.FOREGROUND_START && !inPip) {
            return RoamMove(tailscale, escape = false)
        }
        return null
    }

    /**
     * The watcher's LAST check, on the main thread just before the recreate: whether [move] is held back because the app is in
     * Picture-in-Picture now ([inPip], read there; it can start after [target] ran, while the activity stays started). Only an
     * UPGRADE is held back: it must never recreate a floating video that still plays (Codex review of the roaming code). An
     * ESCAPE never is: the floating video is already dead, and holding it back would trap the person on a dead page (Codex plan
     * review of Tailscale first, 2026-11-09). A pure rule so the JVM tests pin it (Codex code review, the same day).
     */
    fun heldBackAtLastStep(move: RoamMove, inPip: Boolean): Boolean = !move.escape && inPip

    /**
     * Runs the watcher's SECOND LOOK ([look]: does the page on screen still answer?) on its own daemon thread, with an OVERALL
     * bound of [timeoutMs] for the whole look (Codex code review of Tailscale first, 2026-11-09: the probes' own connect and
     * read timeouts do not bound a whole request, since a name lookup or a slowly arriving body can run past them, and the
     * watcher's single worker waits for the look). Returns:
     *  - the look's own answer when it comes in time (true = answers, false = a completed probe that failed);
     *  - false (DEAD) when no answer has come by [timeoutMs]: a page that cannot answer within the bound is treated as stopped,
     *    the known limit stated at NetworkRoamWatcher.STILL_ANSWERING_MS and in the README;
     *  - null (the look could not be taken) when the look threw or its thread could not be started: never a reason to move.
     * A look still running at the bound is abandoned: its thread is a daemon, its probe's own timeouts normally end it (a name
     * lookup is not bounded by them), and its answer is ignored. Pure Kotlin (java.util.concurrent only), so the JVM tests run
     * exactly this.
     */
    fun boundedLook(timeoutMs: Long, look: () -> Boolean): Boolean? {
        return try {
            val task = FutureTask(Callable { look() })
            val thread = Thread(task, "nightlight-roam-look")
            thread.isDaemon = true
            thread.start()
            try {
                task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                false
            }
        } catch (e: Exception) {
            // ExecutionException (the look threw), InterruptedException, or the thread could not start.
            null
        }
    }

    // When the check finds NOTHING answering (the tunnel is not routing yet, the new network has no internet yet), it runs again
    // at about these times after the first check, then stops until the next network event. Android does not promise another
    // callback when an address starts answering later (Codex review of the plan), so without this a phone that switched to
    // mobile data a little before Tailscale reconnected would stay on the dead page. A judgement, not a measurement: roughly
    // doubling, covering the first half minute, and then stopping so a phone with no way to the server is not probed forever.
    // Each wait is counted from the END of the previous check (which itself takes up to about 3 s), so the real times are a
    // little later than these.
    val RETRY_OFFSETS_MS = longArrayOf(3_000, 6_000, 12_000, 25_000)

    /**
     * How long to wait before the next check, when [attempt] retries have already run after the first check (0 = only the
     * first check has run), or null when there are no retries left.
     */
    fun retryDelayAfter(attempt: Int): Long? {
        if (attempt < 0 || attempt >= RETRY_OFFSETS_MS.size) return null
        val previous = if (attempt == 0) 0L else RETRY_OFFSETS_MS[attempt - 1]
        return RETRY_OFFSETS_MS[attempt] - previous
    }
}

/**
 * A move RoamRules.target allows: the [address] to open, and whether it is an [escape] from a page that stopped answering
 * (true, either direction) or the optional UPGRADE from a working saved page to Tailscale (false). The watcher's last checks on
 * the main thread hold back UPGRADES only (Codex plan review of Tailscale first, 2026-11-09): applied to every move, its
 * Picture-in-Picture check would trap a person on a dead Tailscale page.
 */
data class RoamMove(val address: String, val escape: Boolean)

/**
 * Every recreate() the app makes ON ITS OWN, so that two reasons to recreate cannot add up to a loop (Codex review of the plan:
 * one coordinator for the existing load-error recreate and the new roam). A recreate the person asks for (Connect, Change
 * server: ServerConfigPlugin.restart) is not recorded: the person is in charge of those, and they cannot loop by themselves.
 * One instance lives for the whole process
 * (MainActivity.recreateLog) so it SURVIVES the recreate it records. No static state here: the clock is passed in, so the
 * tests drive it.
 *
 * Thread-safe: the load-error path and the final step of a roam run on the main thread, the roam's first check on a worker.
 */
class RecreateLog {

    companion object {
        // The load-error recreate's debounce, carried over unchanged from MainActivity (it was ERROR_RECREATE_DEBOUNCE_MS
        // there). A judgement, not a measurement: long enough that a server reachable enough to retry but failing to load
        // cannot spin the app. It is now measured from ANY recreate, a roam included, and reads "at least 8 s" where the old
        // check read "more than 8 s" (one millisecond, which nothing can notice).
        const val ERROR_GAP_MS = 8_000L

        // How long after ANY recreate a roam may recreate again. A judgement, not a measurement: several times one launch's
        // own work (up to 3 s of probing, the page loading, the start check 2 s after it), so a roam's reload, and the check
        // that follows it, have finished before another roam may begin.
        const val ROAM_GAP_MS = 20_000L

        // At most this many roams in any rolling window of this length. A judgement, not a measurement: a real walk out of the
        // house and back is one or two switches; more than three in five minutes means the network itself is flapping (a weak
        // Wi-Fi edge, airplane mode toggled), and each switch reloads the page and restarts a video. The window ROLLS: a roam
        // older than it no longer counts, so roaming comes back on its own instead of staying off until the app restarts.
        const val ROAM_MAX = 3
        const val ROAM_WINDOW_MS = 5 * 60_000L
    }

    // null until the first recreate: "none yet" is not "at time zero". The clock MainActivity uses starts at boot, and with a 0
    // here a phone that started the app in its first 8 seconds could not recover from a load error (the old field was 0 with a
    // wall clock, where that could not happen).
    private var lastRecreateAtMs: Long? = null
    private val roamsAtMs = ArrayList<Long>()

    /** A recreate that is not a roam (the load-error path). */
    @Synchronized
    fun noteRecreate(nowMs: Long) {
        lastRecreateAtMs = nowMs
    }

    /** A roam. It is also a recreate, so it holds back the load-error path too. */
    @Synchronized
    fun noteRoam(nowMs: Long) {
        lastRecreateAtMs = nowMs
        forgetOldRoams(nowMs)
        roamsAtMs.add(nowMs)
    }

    /** Whether the load-error path may recreate now: at least [ERROR_GAP_MS] since any recreate. */
    @Synchronized
    fun mayErrorRecreate(nowMs: Long): Boolean = gapPassed(nowMs, ERROR_GAP_MS)

    /** Whether a roam may recreate now: at least [ROAM_GAP_MS] since any recreate, and fewer than [ROAM_MAX] roams in the window. */
    @Synchronized
    fun mayRoam(nowMs: Long): Boolean {
        forgetOldRoams(nowMs)
        return gapPassed(nowMs, ROAM_GAP_MS) && roamsAtMs.size < ROAM_MAX
    }

    private fun gapPassed(nowMs: Long, gapMs: Long): Boolean {
        val last = lastRecreateAtMs ?: return true
        return nowMs - last >= gapMs
    }

    // A roam counts while it is younger than the window; at exactly the window's length it no longer does.
    private fun forgetOldRoams(nowMs: Long) {
        roamsAtMs.removeAll { nowMs - it >= ROAM_WINDOW_MS }
    }
}

/**
 * Which default-network callbacks are a real change. Registering a callback makes Android report the CURRENT network at
 * once (onAvailable, then onCapabilitiesChanged), and that echo is not a change: counted as one, it would turn every
 * foreground-start check into a network-change check and the app would never upgrade a working saved page to Tailscale.
 *  - onAvailable: the echo is the first one, for the network that was the default when the callback was registered
 *    ([atRegistration], read just before registering). When there was NO network then (airplane mode), there is no echo, so
 *    the first network to arrive IS a change (a plain "skip the first call" would miss exactly that one).
 *  - onCapabilitiesChanged ([onTransports]): a change only when the set of transports (Wi-Fi, mobile data, Ethernet, VPN)
 *    differs from the last one reported. The first report just records it (it is the echo, or follows an onAvailable that
 *    already counted). Signal strength and validation updates arrive constantly and are not a change.
 *  - onLost: always a change; afterwards there is no network to compare transports with.
 *
 * Generic over the network type so the JVM tests need no android.net.Network. Used from one thread (Android delivers a
 * callback's events in order, on one thread).
 */
class NetworkEventFilter<N>(private val atRegistration: N?) {
    private var echoPending = atRegistration != null
    private var lastTransports: Int? = null

    fun onAvailable(network: N): Boolean {
        val echo = echoPending && network == atRegistration
        echoPending = false
        return !echo
    }

    fun onLost(): Boolean {
        echoPending = false
        lastTransports = null
        return true
    }

    fun onTransports(transports: Int): Boolean {
        val previous = lastTransports
        lastTransports = transports
        return previous != null && previous != transports
    }
}
