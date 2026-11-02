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
 *    name is probed again at most once per [reprobeIntervalMs];
 *  - at most [maxAddressesPerService] addresses of one announcement are probed, and only private ones
 *    ([addressPolicy]);
 *  - one entry per id: the first address that verifies wins, and a later announcement of a listed id does not
 *    replace it.
 * A hostile device that answers the health check CORRECTLY for many invented ids can still fill the list: a stated
 * known limit (the typed address always works).
 *
 * The stop state machine (plan review, Medium): [start] begins a new generation, and every job and probe task
 * carries the generation it was made in. A result from an earlier generation, or one that arrives after [stop], is
 * discarded, so a late answer cannot put a card back on a page that has moved on. Each service name also has an
 * epoch, bumped when it is lost, so a probe that was already running when its service went away is discarded too.
 */
class DiscoveryState<R>(
    private val maxVerified: Int = MAX_VERIFIED,
    private val maxQueuedProbes: Int = MAX_QUEUED_PROBES,
    private val maxQueuedResolves: Int = MAX_QUEUED_RESOLVES,
    private val maxAddressesPerService: Int = MAX_ADDRESSES_PER_SERVICE,
    private val reprobeIntervalMs: Long = REPROBE_INTERVAL_MS,
    private val maxResolveAttempts: Int = MAX_RESOLVE_ATTEMPTS,
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
        // rule) is tried again, at most this many times in all, from the back of the queue.
        const val MAX_RESOLVE_ATTEMPTS = 3
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

    var generation = 0
        private set
    var running = false
        private set

    /** Probes dropped because the queue was full (oldest first); for tests and a debug log. */
    var droppedProbes = 0
        private set

    private var nextToken = 0
    private val epochs = HashMap<String, Int>()
    private val resolveQueue = ArrayDeque<ResolveJob<R>>()
    private val resolving = HashMap<String, Int>() // service name -> token of the attempt in progress
    private val probeQueue = ArrayDeque<ProbeTask>()
    private val probesPending = HashMap<String, Int>() // service name -> probes queued or running
    private val lastProbeAt = HashMap<String, Long>()
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
        resolveQueue.addLast(ResolveJob(gen, name, epochOf(name), payload, attempt = 1))
        while (resolveQueue.size > maxQueuedResolves) resolveQueue.removeFirst()
        return true
    }

    /** The next service to resolve, if fewer than [slots] resolves are in progress. */
    fun nextResolve(slots: Int): ResolveJob<R>? {
        if (!running || resolving.size >= slots) return null
        val queued = resolveQueue.removeFirstOrNull() ?: return null
        val job = queued.copy(token = ++nextToken)
        resolving[job.name] = job.token
        return job
    }

    /** True while [job] is still the attempt in progress for its service in the current search. */
    fun isResolving(job: ResolveJob<R>): Boolean = isLive(job.generation, job.name, job.epoch) && resolving[job.name] == job.token

    /**
     * A resolve ended without an answer (failed or timed out). With [retry], the service goes to the back of the
     * queue for another attempt unless it has had [maxResolveAttempts]. Returns true if [job] was the attempt in
     * progress (a late report from an attempt that already ended changes nothing).
     */
    fun resolveFailed(job: ResolveJob<R>, retry: Boolean): Boolean {
        if (!isResolving(job)) return false
        resolving.remove(job.name)
        if (retry && job.attempt < maxResolveAttempts) {
            resolveQueue.addLast(job.copy(attempt = job.attempt + 1, token = 0))
            while (resolveQueue.size > maxQueuedResolves) resolveQueue.removeFirst()
        }
        return true
    }

    /**
     * A resolve answered: the TXT id, the announced addresses and the port. Returns the probe tasks queued for it
     * (empty when there is nothing to probe: a stale or repeated answer, no usable id, the id already listed, the
     * list full, a probe of this service already pending or too recent, or no private address).
     */
    fun onResolved(job: ResolveJob<R>, txtId: String?, hosts: List<String>, port: Int, nowMs: Long): List<ProbeTask> {
        if (!isResolving(job)) return emptyList()
        resolving.remove(job.name)
        if (txtId == null || !DiscoveryRules.isPlausibleId(txtId)) return emptyList()
        if (port !in 1..65535) return emptyList()
        if (listed.containsKey(txtId) || listed.size >= maxVerified) return emptyList()
        if ((probesPending[job.name] ?: 0) > 0) return emptyList()
        val last = lastProbeAt[job.name]
        if (last != null && nowMs - last < reprobeIntervalMs) return emptyList()
        val targets = hosts.filter(addressPolicy).distinct().take(maxAddressesPerService)
        if (targets.isEmpty()) return emptyList()
        lastProbeAt[job.name] = nowMs
        probesPending[job.name] = targets.size
        val tasks = targets.map { ProbeTask(job.generation, job.name, job.epoch, txtId, it, port) }
        probeQueue.addAll(tasks)
        while (probeQueue.size > maxQueuedProbes) {
            releasePending(probeQueue.removeFirst().name)
            droppedProbes++
        }
        return tasks
    }

    /** The next address to probe, oldest first. */
    fun nextProbe(): ProbeTask? = if (running) probeQueue.removeFirstOrNull() else null

    fun queuedProbeCount(): Int = probeQueue.size

    /**
     * A probe ended; [health] is the verified reply, or null. Returns the server to list, or null if nothing
     * changes (a failed probe, a stale task, an id already listed, or the list full).
     */
    fun onProbeDone(task: ProbeTask, health: DiscoveryRules.Health?): Found? {
        if (!isLive(task.generation, task.name, task.epoch)) return null
        releasePending(task.name)
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
        repeat(before - probeQueue.size) { releasePending(task.name) }
        return found
    }

    /** A service went away. Returns the id of its card, if it had one, so the page can remove it. */
    fun onLost(gen: Int, name: String?): String? {
        if (!isCurrent(gen) || name.isNullOrEmpty()) return null
        // A probe or resolve of this service that is still running reports with the old epoch and is discarded.
        epochs[name] = epochOf(name) + 1
        resolveQueue.removeAll { it.name == name }
        resolving.remove(name)
        probeQueue.removeAll { it.name == name }
        probesPending.remove(name)
        // lastProbeAt is kept: a service that goes and comes back is still probed at most once per interval.
        val id = listedIdByName.remove(name) ?: return null
        listed.remove(id)
        return id
    }

    fun isListed(id: String): Boolean = listed.containsKey(id)

    fun listedCount(): Int = listed.size

    private fun epochOf(name: String): Int = epochs[name] ?: 0

    private fun isLive(gen: Int, name: String, epoch: Int): Boolean = isCurrent(gen) && epochOf(name) == epoch

    // A service is already being handled if it waits to be resolved, is being resolved, has probes outstanding, or
    // is listed: a repeat announcement of it is deduplicated, not queued again.
    private fun isBusy(name: String): Boolean =
        resolving.containsKey(name) ||
            resolveQueue.any { it.name == name } ||
            (probesPending[name] ?: 0) > 0 ||
            listedIdByName.containsKey(name)

    private fun releasePending(name: String) {
        val left = (probesPending[name] ?: 0) - 1
        if (left <= 0) probesPending.remove(name) else probesPending[name] = left
    }

    private fun forgetAll() {
        epochs.clear()
        resolveQueue.clear()
        resolving.clear()
        probeQueue.clear()
        probesPending.clear()
        lastProbeAt.clear()
        listed.clear()
        listedIdByName.clear()
    }
}
