import Foundation

enum DiscoveryStateDefaults {
    static let maxVerified = 16
    static let maxQueuedProbes = 64
    static let maxQueuedResolves = 64
    static let maxAddressesPerService = 8
    static let reprobeIntervalMs: Int64 = 10_000
    static let maxResolveAttempts = 3
    static let retryDelayMs: Int64 = 500
    static let maxTrackedNames = 256
}

/// Bookkeeping behind "Look for my Nightlight": bounded resolve and probe queues, verified cards, and stale work.
/// Single-threaded by contract, like Android's lock-held state. Part B will call every method from one serial
/// DispatchQueue. This type has no timers or Dispatch work; callers supply the time so tests are deterministic.
///
/// The Android plan review found that a flat cap of 16 announced ids lets 16 fakes starve a real server. Only
/// verified servers count toward the card cap. Full work queues discard their oldest entry so a flood cannot
/// keep a later announcement from being tried. A name has one probe batch at a time; a repeat is deduplicated.
/// A quick return is deferred, not dropped, because a restart otherwise lost its card until a new search. Only
/// one deferral per name is kept, and its newest answer replaces the previous one so a changed address is tried.
/// Each resolve retry has a due time in a bounded list, rather than its own timer; an evicted retry never fires.
/// Private addresses are selected before the per-service cap. First verified address and first verified id win.
/// Per-name bookkeeping is bounded; a flood of loss events for invented names once grew the epoch map without
/// limit. Forgetting a name makes its in-flight work stale, though it may be probed again sooner. Generation and
/// non-reused epochs also discard replies after stop, a new search, or loss of a service.
///
/// The resolve queue was needed for Android's one-at-a-time NsdManager. On iOS these are NetService resolves; the
/// same queue and caps keep one reviewed state machine on both platforms. Part B decides which failures are genuinely
/// transient before asking this state machine to retry. A hostile device can still fill the verified list with
/// correct replies for invented ids or hold resolve slots until timeout; the typed address remains available.
final class DiscoveryState<R> {
    // The plan's numbers were judgements, not measurements on many servers. Sixteen verified cards is far past
    // ordinary use and still a short list; unauthenticated announcements alone cannot claim a slot.
    static var MAX_VERIFIED: Int { DiscoveryStateDefaults.maxVerified }
    static var MAX_QUEUED_PROBES: Int { DiscoveryStateDefaults.maxQueuedProbes }
    // A separate bounded queue limits work before any resolve costs a network request.
    static var MAX_QUEUED_RESOLVES: Int { DiscoveryStateDefaults.maxQueuedResolves }
    // A host can announce several network interfaces and bridge addresses; eight permits that without one
    // announcement filling the probe queue on its own.
    static var MAX_ADDRESSES_PER_SERVICE: Int { DiscoveryStateDefaults.maxAddressesPerService }
    static var REPROBE_INTERVAL_MS: Int64 { DiscoveryStateDefaults.reprobeIntervalMs }
    // Android retries a busy resolve at most three times. iOS retry eligibility belongs to Part B.
    static var MAX_RESOLVE_ATTEMPTS: Int { DiscoveryStateDefaults.maxResolveAttempts }
    // Android's older NsdManager could refuse while another resolve was active. Immediate retry burned all
    // attempts in milliseconds; 500 ms was a judgement, unverified on an older phone.
    static var RETRY_DELAY_MS: Int64 { DiscoveryStateDefaults.retryDelayMs }
    // More names than this service type should need, yet small enough to limit a flood's bookkeeping.
    static var MAX_TRACKED_NAMES: Int { DiscoveryStateDefaults.maxTrackedNames }

    struct ResolveJob {
        let generation: Int
        let name: String
        let epoch: Int
        let payload: R
        let attempt: Int
        let token: Int

        init(generation: Int, name: String, epoch: Int, payload: R, attempt: Int, token: Int = 0) {
            self.generation = generation
            self.name = name
            self.epoch = epoch
            self.payload = payload
            self.attempt = attempt
            self.token = token
        }

        func with(attempt: Int, token: Int) -> ResolveJob {
            return ResolveJob(generation: generation, name: name, epoch: epoch, payload: payload,
                              attempt: attempt, token: token)
        }
    }

    struct ProbeTask {
        let generation: Int
        let name: String
        let epoch: Int
        let txtId: String
        let host: String
        let port: Int
    }

    struct Found: Equatable {
        let id: String
        let name: String
        let url: String
        let fingerprint: String
    }

    private struct Deferred {
        let generation: Int
        let epoch: Int
        let txtId: String
        let targets: [String]
        let port: Int
        let dueAtMs: Int64
    }

    private struct WaitingRetry {
        let job: ResolveJob
        let dueAtMs: Int64
    }

    private struct Pending {
        let epoch: Int
        var count: Int
    }

    // LinkedHashMap's oldest-first eviction is represented explicitly: Swift Dictionary has no order contract.
    private struct OrderedMap<Value> {
        private var values: [String: Value] = [:]
        private var order: [String] = []

        subscript(_ key: String) -> Value? { values[key] }
        var count: Int { values.count }
        var keys: [String] { order }
        var entries: [(String, Value)] { order.compactMap { key in values[key].map { (key, $0) } } }

        mutating func set(_ key: String, _ value: Value, cap: Int) {
            remove(key)
            values[key] = value
            order.append(key)
            while order.count > cap { remove(order[0]) }
        }

        @discardableResult mutating func remove(_ key: String) -> Value? {
            if let index = order.firstIndex(of: key) { order.remove(at: index) }
            return values.removeValue(forKey: key)
        }

        mutating func clear() {
            values.removeAll()
            order.removeAll()
        }
    }

    private let maxVerified: Int
    private let maxQueuedProbes: Int
    private let maxQueuedResolves: Int
    private let maxAddressesPerService: Int
    private let reprobeIntervalMs: Int64
    private let maxResolveAttempts: Int
    private let maxTrackedNames: Int
    private let retryDelayMs: Int64
    private let addressPolicy: (String) -> Bool

    init(maxVerified: Int = DiscoveryStateDefaults.maxVerified,
         maxQueuedProbes: Int = DiscoveryStateDefaults.maxQueuedProbes,
         maxQueuedResolves: Int = DiscoveryStateDefaults.maxQueuedResolves,
         maxAddressesPerService: Int = DiscoveryStateDefaults.maxAddressesPerService,
         reprobeIntervalMs: Int64 = DiscoveryStateDefaults.reprobeIntervalMs,
         maxResolveAttempts: Int = DiscoveryStateDefaults.maxResolveAttempts,
         maxTrackedNames: Int = DiscoveryStateDefaults.maxTrackedNames,
         retryDelayMs: Int64 = DiscoveryStateDefaults.retryDelayMs,
         addressPolicy: @escaping (String) -> Bool = { EndpointRules.isPrivateIpv4($0) }) {
        self.maxVerified = maxVerified
        self.maxQueuedProbes = maxQueuedProbes
        self.maxQueuedResolves = maxQueuedResolves
        self.maxAddressesPerService = maxAddressesPerService
        self.reprobeIntervalMs = reprobeIntervalMs
        self.maxResolveAttempts = maxResolveAttempts
        self.maxTrackedNames = maxTrackedNames
        self.retryDelayMs = retryDelayMs
        self.addressPolicy = addressPolicy
    }

    private(set) var generation = 0
    private(set) var running = false
    // Oldest probes dropped by the full queue, for tests and a debug log; stop does not reset this counter.
    private(set) var droppedProbes = 0
    private var nextToken = 0
    private var nextEpoch = 0
    private var epochs = OrderedMap<Int>()
    private var resolveQueue: [ResolveJob] = []
    private var resolving: [String: Int] = [:]
    private var waitingRetry = OrderedMap<WaitingRetry>()
    private var probeQueue: [ProbeTask] = []
    // Counts only the current epoch, so a late probe of a lost service cannot count down a new batch.
    private var probesPending: [String: Pending] = [:]
    private var lastProbeAt = OrderedMap<Int64>()
    private var deferred = OrderedMap<Deferred>()
    private var listed: [String: Found] = [:]
    private var listedIdByName: [String: String] = [:]

    /// Begin a new generation and forget all work from the old one.
    @discardableResult func start() -> Int {
        generation += 1
        running = true
        forgetAll()
        return generation
    }

    /// End the search. Any result still in flight is discarded when it reports.
    func stop() {
        running = false
        forgetAll()
    }

    func isCurrent(_ gen: Int) -> Bool { running && gen == generation }

    @discardableResult func onFound(_ gen: Int, _ name: String?, _ payload: R) -> Bool {
        guard isCurrent(gen), let name = name, !name.isEmpty, !isBusy(name) else { return false }
        let epoch: Int
        if let existing = epochs[name] {
            epoch = existing
        } else {
            nextEpoch += 1
            epoch = nextEpoch
            epochs.set(name, epoch, cap: maxTrackedNames)
        }
        resolveQueue.append(ResolveJob(generation: gen, name: name, epoch: epoch, payload: payload, attempt: 1))
        while resolveQueue.count > maxQueuedResolves { resolveQueue.removeFirst() }
        return true
    }

    /// The next service to resolve when fewer than `slots` resolves are active.
    func nextResolve(_ slots: Int) -> ResolveJob? {
        if !running || resolving.count >= slots { return nil }
        while !resolveQueue.isEmpty {
            let queued = resolveQueue.removeFirst()
            // A name evicted by the cap is skipped rather than resolved for nothing.
            if !isLive(queued.generation, queued.name, queued.epoch) { continue }
            nextToken += 1
            let job = queued.with(attempt: queued.attempt, token: nextToken)
            resolving[job.name] = job.token
            return job
        }
        return nil
    }

    func isResolving(_ job: ResolveJob) -> Bool {
        return isLive(job.generation, job.name, job.epoch) && resolving[job.name] == job.token
    }

    // Free the slot even if the service was forgotten; otherwise that slot would never come back.
    private func endResolve(_ job: ResolveJob) -> Bool {
        guard resolving[job.name] == job.token else { return false }
        resolving.removeValue(forKey: job.name)
        return isLive(job.generation, job.name, job.epoch)
    }

    /// A failed or timed-out resolve may wait `retryDelayMs` before another attempt. The oldest waiting retry is
    /// dropped when the cap is exceeded; it then has no timer and the name is free to be announced again. A late
    /// report from an ended attempt changes nothing. Part B chooses whether `retry` is warranted.
    @discardableResult func resolveFailed(_ job: ResolveJob, retry: Bool, nowMs: Int64 = 0) -> Bool {
        if !endResolve(job) { return false }
        if retry && job.attempt < maxResolveAttempts {
            let waiting = WaitingRetry(job: job.with(attempt: job.attempt + 1, token: 0),
                                       dueAtMs: nowMs + retryDelayMs)
            waitingRetry.set(job.name, waiting, cap: maxQueuedResolves)
        }
        return true
    }

    func isWaitingRetry(_ name: String) -> Bool { waitingRetry[name] != nil }
    func waitingRetryCount() -> Int { waitingRetry.count }

    /// Earliest due time, so the caller needs only one retry timer for all waiting services.
    func nextRetryDueAt() -> Int64? { waitingRetry.entries.map { $0.1.dueAtMs }.min() }

    /// Put due, still-live retries at the end of the bounded resolve queue.
    @discardableResult func requeueDueRetries(_ nowMs: Int64) -> Int {
        var requeued = 0
        let due = waitingRetry.entries.filter { $0.1.dueAtMs <= nowMs }.map { $0.0 }
        for name in due {
            guard let job = waitingRetry.remove(name)?.job else { continue }
            if !isLive(job.generation, job.name, job.epoch) { continue }
            resolveQueue.append(job)
            requeued += 1
        }
        while resolveQueue.count > maxQueuedResolves { resolveQueue.removeFirst() }
        return requeued
    }

    /// An answer queues private addresses for probing, or defers them inside the re-probe window. Its newest
    /// content always replaces an old deferral, including an answer with no usable id or address.
    @discardableResult func onResolved(_ job: ResolveJob, _ txtId: String?, _ hosts: [String],
                                     _ port: Int, _ nowMs: Int64) -> [ProbeTask] {
        if !endResolve(job) { return [] }
        deferred.remove(job.name)
        guard let txtId = txtId, EndpointProbe.isPlausibleId(txtId), port >= 1, port <= 65535,
              listed[txtId] == nil, listed.count < maxVerified, probesPending[job.name] == nil else { return [] }
        // Filter first, then deduplicate and cap: public addresses first must not displace a private one.
        if maxAddressesPerService <= 0 { return [] }
        var targets: [String] = []
        for host in hosts where addressPolicy(host) {
            if !targets.contains(host) {
                targets.append(host)
                if targets.count == maxAddressesPerService { break }
            }
        }
        if targets.isEmpty { return [] }
        if let last = lastProbeAt[job.name], nowMs - last < reprobeIntervalMs {
            deferred.set(job.name, Deferred(generation: job.generation, epoch: job.epoch, txtId: txtId,
                                            targets: targets, port: port, dueAtMs: last + reprobeIntervalMs),
                         cap: maxTrackedNames)
            return []
        }
        return enqueueProbes(job.generation, job.name, job.epoch, txtId, targets, port, nowMs)
    }

    func deferredDueAt(_ name: String) -> Int64? { deferred[name]?.dueAtMs }

    /// A not-yet-due deferral remains waiting; stale, listed or full-list work is dropped.
    @discardableResult func runDeferred(_ name: String, _ nowMs: Int64) -> [ProbeTask] {
        guard let d = deferred[name] else { return [] }
        if !isLive(d.generation, name, d.epoch) {
            deferred.remove(name)
            return []
        }
        if nowMs < d.dueAtMs { return [] }
        deferred.remove(name)
        if listed[d.txtId] != nil || listed.count >= maxVerified || probesPending[name] != nil { return [] }
        return enqueueProbes(d.generation, name, d.epoch, d.txtId, d.targets, d.port, nowMs)
    }

    /// The caller could not schedule this deferral.
    func dropDeferred(_ name: String) { deferred.remove(name) }

    /// Oldest queued address; a name evicted meanwhile is skipped and its pending count released.
    func nextProbe() -> ProbeTask? {
        if !running { return nil }
        while !probeQueue.isEmpty {
            let task = probeQueue.removeFirst()
            if isLive(task.generation, task.name, task.epoch) { return task }
            releasePending(task.name, task.epoch)
        }
        return nil
    }

    func queuedProbeCount() -> Int { probeQueue.count }

    /// A verified reply may list a card. Failed, stale, duplicate and over-cap replies list nothing.
    @discardableResult func onProbeDone(_ task: ProbeTask, _ health: ServerHealth?) -> Found? {
        if !isCurrent(task.generation) { return nil }
        // Even forgotten work counts down its old batch; a lost service's count was already dropped, and its
        // newer batch has another epoch, so a late old probe cannot touch it.
        releasePending(task.name, task.epoch)
        guard isLive(task.generation, task.name, task.epoch), let health = health,
              DiscoveryRules.matches(health, task.txtId), listed[health.id] == nil,
              listed.count < maxVerified, listedIdByName[task.name] == nil else { return nil }
        let found = Found(id: health.id, name: health.name ?? DiscoveryRules.DEFAULT_NAME,
                          url: DiscoveryRules.serverUrl(task.host, task.port),
                          fingerprint: DiscoveryRules.fingerprint(health.id))
        listed[health.id] = found
        listedIdByName[task.name] = health.id
        // First address that verifies wins; the same service's other queued requests are no longer useful.
        let before = probeQueue.count
        probeQueue.removeAll { $0.name == task.name }
        for _ in 0..<(before - probeQueue.count) { releasePending(task.name, task.epoch) }
        return found
    }

    /// Loss frees its resolve slot and removes its card once. Unknown names record no new epoch.
    @discardableResult func onLost(_ gen: Int, _ name: String?) -> String? {
        guard isCurrent(gen), let name = name, !name.isEmpty else { return nil }
        // Drop, never replace, the epoch: in-flight work cannot match a later announcement of this name.
        epochs.remove(name)
        resolveQueue.removeAll { $0.name == name }
        resolving.removeValue(forKey: name)
        waitingRetry.remove(name)
        probeQueue.removeAll { $0.name == name }
        probesPending.removeValue(forKey: name)
        deferred.remove(name)
        // Keep lastProbeAt: a quick return is deferred instead of probed a second time inside the interval.
        guard let id = listedIdByName.removeValue(forKey: name) else { return nil }
        listed.removeValue(forKey: id)
        return id
    }

    func isListed(_ id: String) -> Bool { listed[id] != nil }
    func listedCount() -> Int { listed.count }
    func trackedNameCount() -> Int {
        return max(max(epochs.count, lastProbeAt.count), max(deferred.count, waitingRetry.count))
    }

    private func enqueueProbes(_ gen: Int, _ name: String, _ epoch: Int, _ txtId: String,
                               _ targets: [String], _ port: Int, _ nowMs: Int64) -> [ProbeTask] {
        lastProbeAt.set(name, nowMs, cap: maxTrackedNames)
        probesPending[name] = Pending(epoch: epoch, count: targets.count)
        let tasks = targets.map { ProbeTask(generation: gen, name: name, epoch: epoch,
                                            txtId: txtId, host: $0, port: port) }
        probeQueue.append(contentsOf: tasks)
        while probeQueue.count > maxQueuedProbes {
            let dropped = probeQueue.removeFirst()
            releasePending(dropped.name, dropped.epoch)
            droppedProbes += 1
        }
        return tasks
    }

    private func isLive(_ gen: Int, _ name: String, _ epoch: Int) -> Bool {
        return isCurrent(gen) && epochs[name] == epoch
    }

    // A queued, resolving, retrying, probing or listed name is busy. A deferred name is deliberately not busy:
    // a newer answer replaces the stale one, but still leaves only one deferral and timer for that name.
    private func isBusy(_ name: String) -> Bool {
        return resolving[name] != nil || resolveQueue.contains(where: { $0.name == name }) ||
            waitingRetry[name] != nil || probesPending[name] != nil || listedIdByName[name] != nil
    }

    private func releasePending(_ name: String, _ epoch: Int) {
        guard var pending = probesPending[name], pending.epoch == epoch else { return }
        pending.count -= 1
        if pending.count <= 0 {
            probesPending.removeValue(forKey: name)
        } else {
            probesPending[name] = pending
        }
    }

    private func forgetAll() {
        epochs.clear()
        resolveQueue.removeAll()
        resolving.removeAll()
        waitingRetry.clear()
        probeQueue.removeAll()
        probesPending.removeAll()
        lastProbeAt.clear()
        deferred.clear()
        listed.removeAll()
        listedIdByName.removeAll()
    }
}
