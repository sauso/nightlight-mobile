import Foundation
import Network
import Darwin

/// Network glue for the single-threaded DiscoveryState. Bonjour announcements are untrusted: only a usable TXT id
/// reaches a resolve, only private IPv4 literals reach the probe, and a card needs a matching health reply.
final class ServerDiscovery: NSObject, NetServiceDelegate {
    enum Event {
        case found(DiscoveryState<Service>.Found)
        case lost(String)
        case failed(String)
        case status(String)
    }

    // R is the service coordinates plus the TXT id captured from the browse result. The health reply must still
    // match that announcement after resolve; a later TXT change invalidates the old job through onLost.
    struct Service {
        let name: String
        let type: String
        let domain: String
        let txtId: String
    }

    private struct ActiveResolve {
        let job: DiscoveryState<Service>.ResolveJob
        let service: NetService
        let search: Int
        let watchdogDueAt: Int64
        var settleDueAt: Int64?
        var hosts: [String]
        var seenHosts: Set<String>
        var sawNonIPv4: Bool
        var port: Int
    }

    // Only aggregate counts leave this queue. Never retain a service name, address, id or TXT value here.
    private struct Diagnostic {
        var browser = "setup"
        var added = 0
        var removed = 0
        var changed = 0
        var seen = 0
        var noTXT = 0
        var badTXT = 0
        var resolves = 0
        var resolvedIPv4 = 0
        var nonIPv4 = 0
        var noAddresses = 0
        var resolveFailed = 0
        var errorCodes: [Int] = []
        var privateIPv4 = 0
        var probes = 0
        var unreachable = 0
        var mismatch = 0
        var accepted = 0
        var cards = 0

        var summary: String {
            let codes = errorCodes.map { String($0) }.joined(separator: ",")
            return "browser \(browser) · added \(added) removed \(removed) changed \(changed) seen \(seen)"
                + " · no TXT \(noTXT) bad TXT \(badTXT)"
                + " · resolve \(resolves) ok \(resolvedIPv4) non-v4 \(nonIPv4) empty \(noAddresses)"
                + " failed \(resolveFailed) [\(codes)]"
                + " · private v4 \(privateIPv4) · probes \(probes) unreachable \(unreachable)"
                + " mismatch \(mismatch) ok \(accepted) · cards \(cards)"
        }
    }

    private let queue = DispatchQueue(label: "com.sauso.nightlight.discovery")
    private let state = DiscoveryState<Service>()
    private let emit: (Event) -> Void
    private var browser: NWBrowser?
    private var active: [ObjectIdentifier: ActiveResolve] = [:]
    private var deferredNames: Set<String> = []
    private var timer: DispatchSourceTimer?
    private var permissionDueAt: Int64?
    private var serial = 0
    private var activeProbes = 0
    private var diagnostic = Diagnostic()
    private var statusWork: DispatchWorkItem?
    private var lastStatusAt: Int64?

    // Android runs four probe workers. A queued task lives in DiscoveryState's 64-task cap, never in an
    // unbounded Dispatch queue. Two concurrent NetService resolves leave room for another announcement.
    private let resolveSlots = 2
    private let probeSlots = 4
    private let probeTimeout: TimeInterval = 2

    init(emit: @escaping (Event) -> Void) {
        self.emit = emit
        super.init()
    }

    func start() {
        queue.async { [self] in
            endSearch()
            serial += 1
            let search = serial
            let gen = state.start()
            diagnostic = Diagnostic()
            lastStatusAt = nil
            statusChanged()
            // A cancelled NWBrowser cannot restart. One new browser per search, on this state queue.
            let newBrowser = NWBrowser(for: .bonjourWithTXTRecord(type: "_nightlight._tcp", domain: nil),
                                       using: NWParameters())
            newBrowser.browseResultsChangedHandler = { [weak self] _, changes in
                self?.changed(changes, search: search, gen: gen)
            }
            newBrowser.stateUpdateHandler = { [weak self] browserState in
                self?.browserChanged(browserState, search: search)
            }
            browser = newBrowser
            newBrowser.start(queue: queue)
        }
    }

    func stop() {
        queue.async { [self] in
            serial += 1
            endSearch()
        }
    }

    private func endSearch() {
        statusWork?.cancel()
        statusWork = nil
        timer?.cancel()
        timer = nil
        permissionDueAt = nil
        browser?.cancel()
        browser = nil
        for item in active.values { close(item.service) }
        active.removeAll()
        deferredNames.removeAll()
        state.stop()
    }

    private func browserChanged(_ newState: NWBrowser.State, search: Int) {
        guard serial == search, state.running else { return }
        switch newState {
        case .ready:
            diagnostic.browser = "ready"
            permissionDueAt = nil
            armTimer(search)
        case .waiting(let error):
            // NWError prose could carry network details. A numeric code describes the wait without leaking them.
            let code = (error as NSError).code
            diagnostic.browser = String("waiting \(code)".prefix(60))
            // The Local Network prompt is asynchronous; Apple's TN3179 notes dns(-65570) while it is unanswered.
            // Give it 60 seconds (a judgement, unconfirmed on a phone) before offering the Settings route.
            if permissionDueAt == nil { permissionDueAt = nowMs() + 60_000 }
            armTimer(search)
        case .failed:
            diagnostic.browser = "failed"
            fail("The search stopped. Try again.")
        case .cancelled:
            diagnostic.browser = "cancelled"
            break
        default:
            break
        }
        statusChanged()
    }

    private func fail(_ message: String) {
        // Finish with the latest counts before the failure event makes the plugin release this search.
        // Holding that event for at most 250 ms preserves the status rate limit and changes no state transition.
        let finalStatus = diagnostic.summary
        let now = nowMs()
        let delay = max(Int64(0), (lastStatusAt ?? (now - 250)) + 250 - now)
        endSearch()
        serial += 1
        let failedSearch = serial
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay))) { [weak self] in
            guard let self = self, self.serial == failedSearch else { return }
            self.emit(.status(finalStatus))
            self.emit(.failed(message))
        }
    }

    private func changed(_ changes: Set<NWBrowser.Result.Change>, search: Int, gen: Int) {
        guard serial == search, state.isCurrent(gen) else { return }
        for change in changes {
            switch change {
            case .added(let result):
                diagnostic.added += 1
                found(result, gen: gen)
            case .removed(let result):
                diagnostic.removed += 1
                lost(result, gen: gen)
            case .changed(let old, let new, _):
                diagnostic.changed += 1
                // Interface-only changes do not invalidate a card; a new endpoint or TXT record does.
                if old.endpoint != new.endpoint || old.metadata != new.metadata {
                    lost(old, gen: gen)
                    found(new, gen: gen)
                }
            default:
                break
            }
        }
        statusChanged()
        pumpResolves(search)
        armTimer(search)
    }

    private func coordinates(_ result: NWBrowser.Result) -> (name: String, type: String, domain: String)? {
        guard case let .service(name, type, domain, _) = result.endpoint else { return nil }
        return (name, type, domain)
    }

    private func found(_ result: NWBrowser.Result, gen: Int) {
        guard let c = coordinates(result) else { return }
        diagnostic.seen += 1
        guard case let .bonjour(record) = result.metadata else {
            diagnostic.noTXT += 1
            return
        }
        var attributes: [String: Data?] = [:]
        // `dictionary` (iOS 13) is the accessor the deployment target allows: CI's first build showed that
        // NWTXTRecord.entries needs iOS 26.4 and Entry.data iOS 16. It gives String values, so a key with no value
        // is missing or empty (both fail closed in DiscoveryRules: an empty `id` is not UUID-shaped, an empty `v` is
        // not "1"), and keys that differ only in case may be merged by the framework (unsure). That loses nothing
        // that protects the person: whatever id is read here must STILL equal the id in the server's own health
        // reply (DiscoveryRules.matches) and again at save (expectId).
        for (key, value) in record.dictionary { attributes[key] = Data(value.utf8) }
        guard let id = DiscoveryRules.txtId(from: attributes) else {
            diagnostic.badTXT += 1
            return
        }
        _ = state.onFound(gen, c.name, Service(name: c.name, type: c.type, domain: c.domain, txtId: id))
    }

    private func lost(_ result: NWBrowser.Result, gen: Int) {
        guard let c = coordinates(result) else { return }
        if let id = state.onLost(gen, c.name) { emit(.lost(id)) }
        deferredNames.remove(c.name)
        // NetService can still call back after stop; its delegate callback checks the search and job token.
        let old = active.filter { $0.value.job.name == c.name }.map { $0.key }
        for key in old {
            if let item = active.removeValue(forKey: key) { close(item.service) }
        }
    }

    private func pumpResolves(_ search: Int) {
        guard serial == search else { return }
        while let job = state.nextResolve(resolveSlots) {
            // NWBrowser's type/domain may be undotted; NetService requires the trailing dot to resolve.
            let s = NetService(domain: DiscoveryRules.netServiceDomain(job.payload.domain),
                               type: DiscoveryRules.netServiceType(job.payload.type), name: job.payload.name)
            diagnostic.resolves += 1
            statusChanged()
            // NetService gives sockaddr bytes without opening a TCP connection. A connection-based resolve
            // could reach a public address before we can apply the private IPv4 policy.
            s.delegate = self
            // NetService's five-second timeout may never call back. Eight seconds is a judgement, unverified
            // on a phone; the watchdog must free one of the two resolve slots even without a delegate call.
            active[ObjectIdentifier(s)] = ActiveResolve(job: job, service: s, search: search,
                                                        watchdogDueAt: nowMs() + 8_000, settleDueAt: nil,
                                                        hosts: [], seenHosts: [], sawNonIPv4: false, port: 0)
            // NetService needs a running run loop. All its operations and delegate calls use the main run loop;
            // only immutable callback data is moved onto the discovery queue before touching DiscoveryState.
            DispatchQueue.main.async { [weak s] in
                guard let s = s else { return }
                s.schedule(in: .main, forMode: .common)
                s.resolve(withTimeout: 5)
                // A stop queued after this start queues close on main after this block.
            }
        }
    }

    private func close(_ service: NetService) {
        DispatchQueue.main.async {
            service.stop()
            service.delegate = nil
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let addresses = sender.addresses ?? []
        let port = sender.port
        queue.async { [weak self] in self?.resolved(sender, addresses: addresses, port: port) }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        let code = errorDict["errorCode"]?.intValue
        queue.async { [weak self] in self?.resolveFailed(sender, code: code) }
    }

    private func resolved(_ service: NetService, addresses: [Data], port: Int) {
        let key = ObjectIdentifier(service)
        guard var item = active[key], item.search == serial else { return }
        // Apple may deliver more than one didResolve callback. A 500 ms settle window after the first is a
        // judgement, unverified on a device; an IPv6-only first answer must not discard a later IPv4 answer.
        if item.settleDueAt == nil { item.settleDueAt = nowMs() + 500 }
        // Bound even a hostile responder's unique addresses before handing them to DiscoveryState's smaller
        // private-address cap. Keep arrival order so a later callback can contribute new hosts.
        let limit = DiscoveryState<Service>.MAX_ADDRESSES_PER_SERVICE * 4
        for address in addresses {
            if item.hosts.count >= limit { break }
            guard let host = ipv4(address) else {
                item.sawNonIPv4 = true
                continue
            }
            guard item.seenHosts.insert(host).inserted else { continue }
            item.hosts.append(host)
        }
        if port >= 1 && port <= 65535 { item.port = port }
        active[key] = item
        armTimer(serial)
    }

    private func finishResolved(_ service: NetService) {
        guard let item = active.removeValue(forKey: ObjectIdentifier(service)), item.search == serial else { return }
        close(service)
        let job = item.job
        guard state.isResolving(job) else { return }
        if item.hosts.isEmpty {
            if item.sawNonIPv4 { diagnostic.nonIPv4 += 1 }
            else { diagnostic.noAddresses += 1 }
        } else {
            diagnostic.resolvedIPv4 += 1
        }
        diagnostic.privateIPv4 += item.hosts.filter { EndpointRules.isPrivateIpv4($0) }.count
        statusChanged()
        // Reading sockaddr_in never opens a connection or uses DNS. IPv6 and non-private results are ignored;
        // DiscoveryState applies the private-address policy, deduplication and eight-address cap.
        _ = state.onResolved(job, job.payload.txtId, item.hosts, item.port, nowMs())
        if state.deferredDueAt(job.name) != nil { deferredNames.insert(job.name) }
        pumpProbes(serial)
        pumpResolves(serial)
        armTimer(serial)
    }

    private func resolveFailed(_ service: NetService, code: Int?) {
        guard let item = active[ObjectIdentifier(service)], item.search == serial else { return }
        let timedOut = code == NetService.ErrorCode.timeoutError.rawValue
        // A timeout can follow a useful address callback. Use those addresses once instead of throwing
        // them away; other NetService failures retain their existing final-failure behaviour.
        if timedOut && !item.hosts.isEmpty { finishResolved(service); return }
        finishFailed(service, retry: timedOut, code: code)
    }

    private func finishFailed(_ service: NetService, retry: Bool, code: Int? = nil) {
        guard let item = active.removeValue(forKey: ObjectIdentifier(service)), item.search == serial else { return }
        close(service)
        diagnostic.resolveFailed += 1
        if let code = code, !diagnostic.errorCodes.contains(code), diagnostic.errorCodes.count < 5 {
            diagnostic.errorCodes.append(code)
        }
        statusChanged()
        // Only an actual timeout is retried. Not-found, cancellation and other errors are final. This
        // NetService error mapping and how often a phone times out are unconfirmed until device testing.
        _ = state.resolveFailed(item.job, retry: retry, nowMs: nowMs())
        pumpResolves(serial)
        armTimer(serial)
    }

    private func ipv4(_ address: Data) -> String? {
        guard address.count >= MemoryLayout<sockaddr_in>.size else { return nil }
        var socket = sockaddr_in()
        _ = withUnsafeMutableBytes(of: &socket) { bytes in
            address.prefix(MemoryLayout<sockaddr_in>.size).copyBytes(to: bytes)
        }
        guard Int32(socket.sin_family) == AF_INET else { return nil }
        var value = socket.sin_addr
        var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &value, &text, socklen_t(text.count)) != nil else { return nil }
        return String(cString: text)
    }

    private func pumpProbes(_ search: Int) {
        guard serial == search else { return }
        while activeProbes < probeSlots, let task = state.nextProbe() {
            activeProbes += 1
            diagnostic.probes += 1
            statusChanged()
            // fetchHealth blocks, so never use the main or discovery queue. Its ephemeral URLSession refuses
            // redirects, proxies and cookies, caps the body at 4 KB, and has a 2 s total bound. It has no
            // separate 1.5 s connect bound like Android's socket; the 2 s total is the tighter outer bound.
            DispatchQueue.global(qos: .utility).async { [weak self] in
                guard let self = self else { return }
                // Only a worker may sync onto the discovery queue, never the main thread. A stop that
                // arrived before this worker begins prevents even the probe request.
                let current = self.queue.sync { self.serial == search && self.state.running }
                let health = current ? EndpointProbe.fetchHealth(DiscoveryRules.serverUrl(task.host, task.port),
                                                                 timeout: self.probeTimeout) : nil
                self.queue.async { [weak self] in
                    guard let self = self else { return }
                    self.activeProbes -= 1
                    if self.serial == search {
                        if health == nil { self.diagnostic.unreachable += 1 }
                        else if !DiscoveryRules.matches(health, task.txtId) { self.diagnostic.mismatch += 1 }
                        else { self.diagnostic.accepted += 1 }
                        let accepted = DiscoveryRules.matches(health, task.txtId) ? health : nil
                        if let card = self.state.onProbeDone(task, accepted) {
                            self.diagnostic.cards += 1
                            self.emit(.found(card))
                        }
                        self.statusChanged()
                    }
                    // A previous search's workers may have held all four slots when this one began.
                    self.pumpProbes(self.serial)
                }
            }
        }
    }

    private func armTimer(_ search: Int) {
        timer?.cancel()
        timer = nil
        guard serial == search, state.running else { return }
        // DiscoveryState evicts the oldest deferral at its cap; forget that name here as well so this
        // timer's index cannot grow beyond the state machine's bounded list under an announcement flood.
        deferredNames = Set(deferredNames.filter { state.deferredDueAt($0) != nil })
        let due = ([permissionDueAt, state.nextRetryDueAt()] +
                   deferredNames.map { state.deferredDueAt($0) } +
                   active.values.flatMap { [$0.settleDueAt, Optional($0.watchdogDueAt)] }).compactMap { $0 }.min()
        guard let when = due else { return }
        let delay = max(Int64(0), when - nowMs())
        let next = DispatchSource.makeTimerSource(queue: queue)
        next.schedule(deadline: .now() + .milliseconds(Int(delay)))
        next.setEventHandler { [weak self] in self?.tick(search) }
        timer = next
        next.resume()
    }

    private func tick(_ search: Int) {
        guard serial == search, state.running else { return }
        timer?.cancel()
        timer = nil
        let now = nowMs()
        if let due = permissionDueAt, now >= due {
            fail("Turn on Local Network for Nightlight in Settings > Privacy & Security > Local Network, then search again.")
            return
        }
        // The first callback starts a fixed settle deadline. The watchdog uses the same timeout outcome as a
        // NetService timeout: collected IPv4 addresses resolve; no addresses release the slot and retry.
        let dueServices = active.values.filter {
            ($0.settleDueAt.map { now >= $0 } ?? false) || now >= $0.watchdogDueAt
        }.map { $0.service }
        for service in dueServices {
            guard let item = active[ObjectIdentifier(service)], item.search == search else { continue }
            if item.hosts.isEmpty { finishFailed(service, retry: true) }
            else { finishResolved(service) }
        }
        _ = state.requeueDueRetries(now)
        for name in deferredNames {
            if let due = state.deferredDueAt(name), now >= due {
                _ = state.runDeferred(name, now)
                deferredNames.remove(name)
            } else if state.deferredDueAt(name) == nil {
                deferredNames.remove(name)
            }
        }
        pumpResolves(search)
        pumpProbes(search)
        armTimer(search)
    }

    private func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private func statusChanged() {
        // A trailing work item sends the latest counts at most every 250 ms during a busy browse.
        // It is canceled with the search, so a late status cannot outlive it.
        guard state.running, statusWork == nil else { return }
        let now = nowMs()
        let delay = max(Int64(0), (lastStatusAt ?? (now - 250)) + 250 - now)
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.state.running else { return }
            self.statusWork = nil
            self.lastStatusAt = self.nowMs()
            self.emit(.status(self.diagnostic.summary))
        }
        statusWork = work
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay)), execute: work)
    }
}
