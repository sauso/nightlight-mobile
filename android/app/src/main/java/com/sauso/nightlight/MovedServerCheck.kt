package com.sauso.nightlight

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** One foreground check for a saved LAN address. Discovery callbacks only hand work to [worker]: they run
 * while ServerDiscovery holds its lock, so stopping discovery or showing UI there would deadlock or block NSD.
 * All network work and storage decisions stay off the main thread; the dialog and recreate use the activity thread.
 */
class MovedServerCheck(private val activity: MainActivity) {
    companion object {
        private val worker: Handler by lazy {
            val thread = HandlerThread("nightlight-moved")
            thread.isDaemon = true
            thread.start()
            Handler(thread.looper)
        }
        private val gate = PromptGate() // Its cooldown is separate from the secure-address question.
        private val runLock = Any()
        private var lastRunMs: Long? = null
        private var inFlight = false
        private val prompted = HashSet<String>()
    }

    private val generation = AtomicInteger()
    @Volatile private var started = false
    @Volatile private var pendingPrompt: SecureAddressPrompt? = null
    private var discovery: ServerDiscovery? = null // worker thread only
    private var running = false // worker thread only
    private var searchTimeout: Runnable? = null // worker thread only

    fun start() {
        if (started) return
        started = true
        val gen = generation.incrementAndGet()
        worker.post { check(gen) }
    }

    fun stop() {
        started = false
        generation.incrementAndGet()
        pendingPrompt?.finish(PromptGate.Outcome.CANCELLED)
        worker.post { endRun() }
    }

    private fun current(gen: Int) = started && generation.get() == gen && !activity.isFinishing && !activity.isDestroyed

    private fun check(gen: Int) {
        if (!current(gen) || PromptGate.isAnyShowing()) return
        val context = activity.applicationContext
        val old = ServerConfigPlugin.getSavedUrl(context) ?: return
        val parsed = DiscoveryRules.parseFoundUrl(old) ?: return
        if (!DiscoveryRules.isPrivateIpv4(parsed.first)) return
        if (!LocalNetwork.isOn(context)) return
        val d = ServerDiscovery(context)
        if (!d.isAvailable) { d.release(); return } // LocalNetwork fails open; NSD still must exist.
        synchronized(runLock) {
            val now = SystemClock.elapsedRealtime()
            if (inFlight || !MovedServerRules.Throttle.mayRun(now, lastRunMs)) { d.release(); return }
            inFlight = true
            lastRunMs = now
        }
        running = true
        discovery = d
        // The saved URL is probed strictly once. A different answering id ends the check without learning it.
        val health = EndpointProbe.fetchHealth(old, MovedServerRules.PROBE_TIMEOUT_MS.toInt())
        if (!current(gen)) { endRun(); return }
        val stored = ServerConfigPlugin.getServerId(context, old)
        val pairUrl = ServerConfigPlugin.getTailscaleUrl(context, old)
        val pairStoredId = ServerConfigPlugin.getTailscaleId(context, old)
        val pairVerified = if (health == null && stored == null && pairUrl != null && pairStoredId != null &&
            EndpointRules.addressAnswers(pairUrl, pairUrl, pairStoredId,
                fetchId = { EndpointProbe.fetchHealth(it, MovedServerRules.PROBE_TIMEOUT_MS.toInt())?.id },
                reachable = { false })) pairStoredId else null
        if (!current(gen)) { endRun(); return }
        val probe = if (health == null) MovedServerRules.Probe.NoAnswer else MovedServerRules.Probe.Answered(health.id)
        when (val step = MovedServerRules.decide(old, stored, pairVerified, probe)) {
            is MovedServerRules.Step.Learn -> {
                ServerConfigPlugin.setServerIdIfAbsent(context, old, step.id)
                endRun()
            }
            is MovedServerRules.Step.Search -> search(gen, old, step.wantedId, pairUrl.takeIf { pairVerified != null })
            MovedServerRules.Step.Stop, MovedServerRules.Step.Skip -> endRun()
        }
    }

    private fun search(gen: Int, old: String, wanted: String, verifiedPairUrl: String?) {
        val d = discovery ?: run { endRun(); return }
        if (!current(gen)) { endRun(); return }
        val listener = object : ServerDiscovery.Listener {
            override fun onServerFound(generation: Int, found: DiscoveryState.Found) {
                worker.post { onFoundCard(gen, old, wanted, verifiedPairUrl, found) }
            }
            override fun onServerLost(generation: Int, id: String) {}
            override fun onDiscoveryFailed(generation: Int, message: String) {
                worker.post { if (current(gen) && discovery != null) endRun() }
            }
        }
        if (d.start(listener) == null) { endRun(); return }
        val timeout = Runnable { if (current(gen)) endRun() }
        searchTimeout = timeout
        worker.postDelayed(timeout, MovedServerRules.SEARCH_BOUND_MS)
    }

    private fun onFoundCard(gen: Int, old: String, wanted: String, verifiedPairUrl: String?, card: DiscoveryState.Found) {
        if (!current(gen) || !running || discovery == null) return
        if (!MovedServerRules.acceptCandidate(wanted, old, card.id, card.url)) return
        val context = activity.applicationContext
        if (MovedServerRules.Declines.isDeclined(ServerConfigPlugin.getMovedDeclines(context, old), card.url,
                System.currentTimeMillis())) return
        synchronized(runLock) { if (card.url in prompted) return }
        stopSearch()
        if (gate.reserve(card.url, null, null, System.currentTimeMillis(), SystemClock.elapsedRealtime()) != PromptGate.Decision.ASK) {
            endRun()
            return
        }
        synchronized(runLock) { prompted.add(card.url) }
        val message = MovedServerRules.promptMessage(old, card.url, card.name,
            ServerConfigPlugin.getStoredName(context, old), card.fingerprint)
        val prompt = SecureAddressPrompt(onShown = { gate.shown(SystemClock.elapsedRealtime()) }) { result ->
            pendingPrompt = null
            gate.release()
            worker.post { handleOutcome(gen, old, wanted, verifiedPairUrl, card, result) }
        }
        pendingPrompt = prompt
        prompt.show(activity, message, MovedServerRules.PROMPT_TITLE,
            MovedServerRules.USE_NEW_ADDRESS_BUTTON, MovedServerRules.NOT_NOW_BUTTON, requireResumed = true)
    }

    private fun handleOutcome(gen: Int, old: String, wanted: String, verifiedPairUrl: String?,
        card: DiscoveryState.Found, result: PromptGate.Outcome) {
        if (!current(gen)) { endRun(); return }
        when (result) {
            PromptGate.Outcome.DONT_USE_IT -> {
                ServerConfigPlugin.recordMovedDecline(activity.applicationContext, old, card.url)
                endRun()
            }
            PromptGate.Outcome.USE_IT -> {
                // Re-check at the tap. Discovery's earlier card is only a suggestion, and its address may have changed hands.
                val live = EndpointProbe.fetchHealth(card.url, MovedServerRules.PROBE_TIMEOUT_MS.toInt())
                if (!current(gen) || live?.id != wanted) { endRun(); return }
                ServerConfigPlugin.moveServer(activity.applicationContext, old, card.url, wanted, live.name,
                    verifiedPairUrl, mayCommit = { current(gen) }) { moved ->
                    if (moved) activity.runOnUiThread {
                        if (current(gen)) {
                            MainActivity.recreateLog.noteRecreate(MainActivity.recreateClockMs())
                            activity.recreate()
                        }
                    }
                    worker.post { endRun() }
                }
            }
            else -> endRun() // Timeout, dismissal, no window, cancellation and failure store no decline.
        }
    }

    private fun stopSearch() {
        searchTimeout?.let { worker.removeCallbacks(it) }
        searchTimeout = null
        discovery?.release()
        discovery = null
    }

    private fun endRun() {
        stopSearch()
        if (running) {
            running = false
            synchronized(runLock) { inFlight = false }
        }
    }
}
