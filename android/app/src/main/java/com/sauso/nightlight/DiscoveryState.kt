package com.sauso.nightlight

/**
 * The bookkeeping behind "Look for my Nightlight" (#20): which announced services wait to be resolved, which
 * addresses wait to be probed, and which servers are verified and listed. Pure Kotlin, so the JVM tests drive the
 * rules that keep the work bounded and the list honest (DiscoveryStateTest).
 *
 * NOT thread-safe: ServerDiscovery calls every method with its one lock held.
 *
 * [R] is what a resolve needs (an NsdServiceInfo in the app, anything in a test).
 *
 * The rules, and where they came from (plan review of #20, Medium: "a flat cap of 16 ids lets 16 fakes starve a
 * real server"):
 *  - only VERIFIED servers count toward the [maxVerified] cap, so fakes that fail the probe never take a slot;
 *  - probes wait in one queue of at most [maxQueuedProbes], and when it is full the OLDEST is dropped, so a flood
 *    of announcements cannot keep a server that announces after it from being probed;
 *  - one batch of probes per service name at a time (a repeat announcement is ignored, not queued again), and a
 *    name is probed again at most once per [reprobeIntervalMs]. An answer that arrives inside that window is
 *    DEFERRED to the end of it, not dropped (code review of #20: a server that restarts quickly would otherwise lose
 *    its card until Search again); one deferred probe per name, the newest answer replacing an older one;
 *  - the private addresses of one announcement are picked FIRST and then at most [maxAddressesPerService] of them
 *    are probed, so public addresses announced first cannot push the real one out (code review of #20);
 *  - one entry per id: the first address that verifies wins, and a later announcement of a listed id does not
 *    replace it;
 *  - everything kept per service name (epochs, last probe times, deferred probes) holds at most [maxTrackedNames]
 *    names, the oldest forgotten first, and nothing is recorded for a name that was never announced (code review of
 *    #20: a flood of "lost" messages for invented names grew the epoch map without limit). Forgetting a name fails
 *    closed: its in-flight work is discarded when it reports, and it may be probed again sooner than the interval.
 * Known limits (stated, not built): a hostile device that answers the health check CORRECTLY for many invented ids
 * can still fill the list; one that announces many names that never resolve holds each resolve slot for the resolve
 * timeout (ServerDiscovery, 5 s) and can keep a real server waiting for a while. The typed address always works.
 *
 * The stop state machine (plan review, Medium): [start] begins a new generation, and every job and probe task
 * carries the generation it was made in. A result from an earlier generation, or one that arrives after [stop], is
 * discarded, so a late answer cannot put a card back on a page that has moved on. Each service name also has an
 * epoch, a value from one counter that is never reused, which is dropped when the service is lost, so a probe that
 * was already running when its service went away is discarded too.
 */
class DiscoveryState<R>(
    private val maxVerified: Int = MAX_VERIFIED,
    private val maxQueuedProbes: Int = MAX_QUEUED_PROBES,
    private val maxQueuedResolves: Int = MAX_QUEUED_RESOLVES,
    private val maxAddressesPerService: Int = MAX_ADDRESSES_PER_SERVICE,
    private val reprobeIntervalMs: Long = REPROBE_INTERVAL_MS,
    private val maxResolveAttempts: Int = MAX_RESOLVE_ATTEMPTS,
    private val maxTrackedNames: Int = MAX_TRACKED_NAMES,
    private val addressPolicy: (String) -> Boolean = DiscoveryRules::isPrivateIpv4,
) {
    companion object {
        // The plan's numbers (#20). None was measured on many servers: a home has one Nightlight, perhaps two
        // (staging beside production). 16 cards is far past any real household and still a short list.
        const val MAX_VERIFIED = 16
        const val MAX_QUEUED_PROBES = 64
        // Resolves wait in their own queue, bounded the same way, so a flood of announcements is bounded before
        // any of them costs a network request.
        const val MAX_QUEUED_RESOLVES = 64
        // A host announces every non-internal IPv4 address it has (design 7.5 step 5: the spike's Windows PC sent
        // three; a Docker host adds one per bridge network). Eight covers a host with several Docker networks
        // while stopping one announcement from filling the probe queue on its own.
        const val MAX_ADDRESSES_PER_SERVICE = 8
        const val REPROBE_INTERVAL_MS = 10_000L
        // A resolve refused because another is still active (FAILURE_ALREADY_ACTIVE, the API < 34 one-at-a-time
        // rule) is tried again, at most this many times in all; ServerDiscovery waits a moment before each retry.
        const val MAX_RESOLVE_ATTEMPTS = 3
        // Code review of #20 (suggested 256). Far more names than any real network announces for this one service
        // type; small enough that a flood costs a few kilobytes.
        const val MAX_TRACKED_NAMES = 256
    }

    /** A service waiting to be resolved, or being resolved. [token] is unique per attempt (0 until it starts). */
    data class ResolveJob<R>(
        val generation: Int,
        val name: String,
        val epoch: Int,
        val payload: R,
        val attempt: Int,
        val token: Int = 0,
    )

    /** One address of one resolved service, waiting to be probed. */
    data class ProbeTask(
        val generation: Int,
        val name: String,
        val epoch: Int,
        val txtId: String,
        val host: String,
        val port: Int,
    )

    /** A verified server, as the page is told about it (the `serverFound` event). */
    data class Found(val id: String, val name: String, val url: String, val fingerprint: String)

    // An answer that came inside the re-probe window, waiting for it to end.
    private data class Deferred(
        val generation: Int,
        val epoch: Int,
        val txtId: String,
        val targets: List<String>,
        val port: Int,
        val dueAtMs: Long,
    )

    var generation = 0
        private set
    var running = false
        private set

    /** Probes dropped because the queue was full (oldest first); for tests and a debug log. */
    var droppedProbes = 0
        private set

    private var nextToken = 0
    private var nextEpoch = 0
    private val epochs = LinkedHashMap<String, Int>() // service name -> epoch, oldest first; bounded
    private val resolveQueue = ArrayDeque<ResolveJob<R>>()
    private val resolving = HashMap<String, Int>() // service name -> token of the attempt in progress
    private val waitingRetry = LinkedHashMap<String, ResolveJob<R>>() // refused as busy, waiting to go back in the queue
    private val probeQueue = ArrayDeque<ProbeTask>()
    // Probes queued or running, per service name, for ONE epoch: a probe of a lost service that finishes after the
    // service came back must not count down the new batch.
    private class Pending(val epoch: Int, var count: Int)
    private val probesPending = HashMap<String, Pending>()
    private val lastProbeAt = LinkedHashMap<String, Long>() // bounded
    private val deferred = LinkedHashMap<String, Deferred>() // bounded
    private val listed = LinkedHashMap<String, Found>() // id -> card, in the order found
    private val listedIdByName = HashMap<String, String>()

    /** Begin a new search. Everything from the previous one is forgotten. Returns the new generation. */
    fun start(): Int {
        generation++
        running = true
        forgetAll()
        return generation
    }

    /** End the search. Every queued job is dropped; anything still running is discarded when it reports. */
    fun stop() {
        running = false
        forgetAll()
    }

    fun isCurrent(gen: Int): Boolean = running && gen == generation

    /** A service was announced. Returns true if it was queued for resolving. */
    fun onFound(gen: Int, name: String?, payload: R): Boolean {
        if (!isCurrent(gen) || name.isNullOrEmpty()) return false
        if (isBusy(name)) return false
        val epoch = epochs[name] ?: (++nextEpoch).also { putBounded(epochs, name, it) }
        resolveQueue.addLast(ResolveJob(gen, name, epoch, payload, attempt = 1))
        while (resolveQueue.size > maxQueuedResolves) resolveQueue.removeFirst()
        return true
    }

    /** The next service to resolve, if fewer than [slots] resolves are in progress. */
    fun nextResolve(slots: Int): ResolveJob<R>? {
        if (!running || resolving.size >= slots) return null
        while (true) {
            val queued = resolveQueue.removeFirstOrNull() ?: return null
            // A service forgotten by the name cap while it waited is skipped, not resolved for nothing.
            if (!isLive(queued.generation, queued.name, queued.epoch)) continue
            val job = queued.copy(token = ++nextToken)
            resolving[job.name] = job.token
            return job
        }
    }

    /** True while [job] is still the attempt in progress for its service in the current search. */
    fun isResolving(job: ResolveJob<R>): Boolean = isLive(job.generation, job.name, job.epoch) && resolving[job.name] == job.token

    // End [job]'s attempt: its resolve slot is freed whenever it still holds it, even if the service has since been
    // forgotten (otherwise the slot would never come back). Returns true if the answer still counts.
    private fun endResolve(job: ResolveJob<R>): Boolean {
        if (resolving[job.name] != job.token) return false
        resolving.remove(job.name)
        return isLive(job.generation, job.name, job.epoch)
    }

    /**
     * A resolve ended without an answer (failed or timed out). With [retry], the service waits for another attempt
     * (see [isWaitingRetry] and [requeueResolve]) unless it has had [maxResolveAttempts]; it is NOT put straight back
     * in the queue, because an immediate retry of a "busy" refusal would burn all its attempts in milliseconds (code
     * review of #20). Returns true if [job] was the attempt in progress (a late report from an attempt that already
     * ended changes nothing).
     */
    fun resolveFailed(job: ResolveJob<R>, retry: Boolean): Boolean {
        if (!endResolve(job)) return false
        if (retry && job.attempt < maxResolveAttempts) {
            putBounded(waitingRetry, job.name, job.copy(attempt = job.attempt + 1, token = 0), maxQueuedResolves)
        }
        return true
    }

    fun isWaitingRetry(name: String): Boolean = waitingRetry.containsKey(name)

    /**
     * Put a service that is waiting to retry back at the end of the resolve queue (the oldest queued service is
     * dropped if that makes the queue too long). Returns true if it went back; false if it was lost, forgotten, or
     * belongs to an earlier search.
     */
    fun requeueResolve(name: String): Boolean {
        val job = waitingRetry.remove(name) ?: return false
        if (!isLive(job.generation, job.name, job.epoch)) return false
        resolveQueue.addLast(job)
        while (resolveQueue.size > maxQueuedResolves) resolveQueue.removeFirst()
        return true
    }

    /**
     * A resolve answered: the TXT id, the announced addresses and the port. Returns the probe tasks queued for it.
     * Empty when there is nothing to probe now: a stale or repeated answer, no usable id, the id already listed, the
     * list full, a probe of this service already pending, no private address, or an answer inside the re-probe
     * window, which is deferred ([deferredDueAt], [runDeferred]).
     */
    fun onResolved(job: ResolveJob<R>, txtId: String?, hosts: List<String>, port: Int, nowMs: Long): List<ProbeTask> {
        if (!endResolve(job)) return emptyList()
        if (txtId == null || !DiscoveryRules.isPlausibleId(txtId)) return emptyList()
        if (port !in 1..65535) return emptyList()
        if (listed.containsKey(txtId) || listed.size >= maxVerified) return emptyList()
        if (probesPending.containsKey(job.name)) return emptyList()
        // Private addresses first, THEN the cap: the other order lets an announcement that lists eight public
        // addresses before the real one push the real one out.
        val targets = hosts.filter(addressPolicy).distinct().take(maxAddressesPerService)
        if (targets.isEmpty()) return emptyList()
        val last = lastProbeAt[job.name]
        if (last != null && nowMs - last < reprobeIntervalMs) {
            putBounded(deferred, job.name, Deferred(job.generation, job.epoch, txtId, targets, port, last + reprobeIntervalMs))
            return emptyList()
        }
        return enqueueProbes(job.generation, job.name, job.epoch, txtId, targets, port, nowMs)
    }

    /** When the deferred probe of [name] may run, or null if none is waiting. */
    fun deferredDueAt(name: String): Long? = deferred[name]?.dueAtMs

    /**
     * Run the deferred probe of [name] if its time has come. Returns the probe tasks queued (empty if none was
     * waiting, it belongs to an earlier search or a lost service, it is not due yet, or there is nothing to probe).
     * A probe that is not due yet stays waiting.
     */
    fun runDeferred(name: String, nowMs: Long): List<ProbeTask> {
        val d = deferred[name] ?: return emptyList()
        if (!isLive(d.generation, name, d.epoch)) {
            deferred.remove(name)
            return emptyList()
        }
        if (nowMs < d.dueAtMs) return emptyList()
        deferred.remove(name)
        if (listed.containsKey(d.txtId) || listed.size >= maxVerified) return emptyList()
        if (probesPending.containsKey(name)) return emptyList()
        return enqueueProbes(d.generation, name, d.epoch, d.txtId, d.targets, d.port, nowMs)
    }

    /** Forget the deferred probe of [name] (ServerDiscovery could not schedule it). */
    fun dropDeferred(name: String) {
        deferred.remove(name)
    }

    /** The next address to probe, oldest first. A task whose service was forgotten meanwhile is skipped. */
    fun nextProbe(): ProbeTask? {
        if (!running) return null
        while (true) {
            val task = probeQueue.removeFirstOrNull() ?: return null
            if (isLive(task.generation, task.name, task.epoch)) return task
            releasePending(task.name, task.epoch)
        }
    }

    fun queuedProbeCount(): Int = probeQueue.size

    /**
     * A probe ended; [health] is the verified reply, or null. Returns the server to list, or null if nothing
     * changes (a failed probe, a stale task, an id already listed, or the list full).
     */
    fun onProbeDone(task: ProbeTask, health: DiscoveryRules.Health?): Found? {
        if (!isCurrent(task.generation)) return null
        // Counted down even when the service was forgotten meanwhile, so it is not left "busy" for ever; a lost
        // service's count was already dropped, and a newer batch has a different epoch, so neither is touched.
        releasePending(task.name, task.epoch)
        if (!isLive(task.generation, task.name, task.epoch)) return null
        if (health == null || !DiscoveryRules.matches(health, task.txtId)) return null
        if (listed.containsKey(health.id) || listed.size >= maxVerified) return null
        if (listedIdByName.containsKey(task.name)) return null
        val found = Found(
            id = health.id,
            name = health.name ?: DiscoveryRules.DEFAULT_NAME,
            url = DiscoveryRules.serverUrl(task.host, task.port),
            fingerprint = DiscoveryRules.fingerprint(health.id),
        )
        listed[health.id] = found
        listedIdByName[task.name] = health.id
        // The first address that verifies wins (design 7.5 step 5): the same service's other queued addresses are
        // no longer worth a request.
        val before = probeQueue.size
        probeQueue.removeAll { it.name == task.name }
        repeat(before - probeQueue.size) { releasePending(task.name, task.epoch) }
        return found
    }

    /** A service went away. Returns the id of its card, if it had one, so the page can remove it. */
    fun onLost(gen: Int, name: String?): String? {
        if (!isCurrent(gen) || name.isNullOrEmpty()) return null
        // Dropping the epoch (rather than recording a new one) is what makes a "lost" for a name nobody announced
        // cost nothing; a probe or resolve still running carries the old epoch, which can never match again.
        epochs.remove(name)
        resolveQueue.removeAll { it.name == name }
        resolving.remove(name) // frees its resolve slot at once
        waitingRetry.remove(name)
        probeQueue.removeAll { it.name == name }
        probesPending.remove(name) // so it is not left "busy" by a probe whose result will be discarded
        deferred.remove(name)
        // lastProbeAt is kept: a service that goes and comes back is still probed at most once per interval (a
        // quick return is deferred, not dropped).
        val id = listedIdByName.remove(name) ?: return null
        listed.remove(id)
        return id
    }

    fun isListed(id: String): Boolean = listed.containsKey(id)

    fun listedCount(): Int = listed.size

    /** How many service names have per-name bookkeeping; for the test that holds the bound. */
    fun trackedNameCount(): Int = maxOf(epochs.size, lastProbeAt.size, deferred.size, waitingRetry.size)

    private fun enqueueProbes(gen: Int, name: String, epoch: Int, txtId: String, targets: List<String>, port: Int, nowMs: Long): List<ProbeTask> {
        putBounded(lastProbeAt, name, nowMs)
        probesPending[name] = Pending(epoch, targets.size)
        val tasks = targets.map { ProbeTask(gen, name, epoch, txtId, it, port) }
        probeQueue.addAll(tasks)
        while (probeQueue.size > maxQueuedProbes) {
            val dropped = probeQueue.removeFirst()
            releasePending(dropped.name, dropped.epoch)
            droppedProbes++
        }
        return tasks
    }

    private fun isLive(gen: Int, name: String, epoch: Int): Boolean = isCurrent(gen) && epochs[name] == epoch

    // A service is already being handled if it waits to be resolved or retried, is being resolved, has probes
    // outstanding or deferred, or is listed: a repeat announcement of it is deduplicated, not queued again.
    private fun isBusy(name: String): Boolean =
        resolving.containsKey(name) ||
            resolveQueue.any { it.name == name } ||
            waitingRetry.containsKey(name) ||
            probesPending.containsKey(name) ||
            deferred.containsKey(name) ||
            listedIdByName.containsKey(name)

    private fun releasePending(name: String, epoch: Int) {
        val pending = probesPending[name] ?: return
        if (pending.epoch != epoch) return
        pending.count--
        if (pending.count <= 0) probesPending.remove(name)
    }

    // Insert (moving an existing key to the newest end) and forget the oldest entries beyond [cap].
    private fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V, cap: Int = maxTrackedNames) {
        map.remove(key)
        map[key] = value
        while (map.size > cap) map.remove(map.keys.first())
    }

    private fun forgetAll() {
        epochs.clear()
        resolveQueue.clear()
        resolving.clear()
        waitingRetry.clear()
        probeQueue.clear()
        probesPending.clear()
        lastProbeAt.clear()
        deferred.clear()
        listed.clear()
        listedIdByName.clear()
    }
}
