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
            permissionDueAt = nil
            armTimer(search)
        case .waiting:
            // The Local Network prompt is asynchronous; Apple's TN3179 notes dns(-65570) while it is unanswered.
            // Give it 60 seconds (a judgement, unconfirmed on a phone) before offering the Settings route.
            if permissionDueAt == nil { permissionDueAt = nowMs() + 60_000 }
            armTimer(search)
        case .failed:
            fail("The search stopped. Try again.")
        case .cancelled:
            break
        default:
            break
        }
    }

    private func fail(_ message: String) {
        endSearch()
        serial += 1
        emit(.failed(message))
    }

    private func changed(_ changes: Set<NWBrowser.Result.Change>, search: Int, gen: Int) {
        guard serial == search, state.isCurrent(gen) else { return }
        for change in changes {
            switch change {
            case .added(let result):
                found(result, gen: gen)
            case .removed(let result):
                lost(result, gen: gen)
            case .changed(let old, let new, _):
                // Interface-only changes do not invalidate a card; a new endpoint or TXT record does.
                if old.endpoint != new.endpoint || old.metadata != new.metadata {
                    lost(old, gen: gen)
                    found(new, gen: gen)
                }
            default:
                break
            }
        }
        pumpResolves(search)
        armTimer(search)
    }

    private func coordinates(_ result: NWBrowser.Result) -> (name: String, type: String, domain: String)? {
        guard case let .service(name, type, domain, _) = result.endpoint else { return nil }
        return (name, type, domain)
    }

    private func found(_ result: NWBrowser.Result, gen: Int) {
        guard let c = coordinates(result), case let .bonjour(record) = result.metadata else { return }
        var attributes: [String: Data?] = [:]
        // entries preserves original key case and a valueless key, unlike dictionary's String values. An
        // ambiguous id/version or a valueless id fails closed in DiscoveryRules before any address is resolved.
        // unsure: Apple's current docs show entries and Entry.data, but do not state their iOS 15 availability.
        // updateValue preserves a nil Data? value; subscript assignment with nil would remove that key.
        for (key, entry) in record.entries { attributes.updateValue(entry.data, forKey: key) }
        guard let id = DiscoveryRules.txtId(from: attributes) else { return }
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
            let s = NetService(domain: job.payload.domain, type: job.payload.type, name: job.payload.name)
            // NetService gives sockaddr bytes without opening a TCP connection. A connection-based resolve
            // could reach a public address before we can apply the private IPv4 policy.
            s.delegate = self
            active[ObjectIdentifier(s)] = ActiveResolve(job: job, service: s)
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
        guard let item = active.removeValue(forKey: ObjectIdentifier(service)) else { return }
        close(service)
        let job = item.job
        guard state.isResolving(job) else { return }
        // Reading sockaddr_in never opens a connection or uses DNS. IPv6 and non-private results are ignored;
        // DiscoveryState applies the private-address policy, deduplication and eight-address cap.
        let hosts = addresses.compactMap { ipv4($0) }
        _ = state.onResolved(job, job.payload.txtId, hosts, port, nowMs())
        if state.deferredDueAt(job.name) != nil { deferredNames.insert(job.name) }
        pumpProbes(serial)
        pumpResolves(serial)
        armTimer(serial)
    }

    private func resolveFailed(_ service: NetService, code: Int?) {
        guard let item = active.removeValue(forKey: ObjectIdentifier(service)) else { return }
        close(service)
        // Only an actual timeout is retried. Not-found, cancellation and other errors are final. This
        // NetService error mapping and how often a phone times out are unconfirmed until device testing.
        let retry = code == NetService.ErrorCode.timeoutError.rawValue
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
                        let accepted = DiscoveryRules.matches(health, task.txtId) ? health : nil
                        if let card = self.state.onProbeDone(task, accepted) { self.emit(.found(card)) }
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
        let due = ([permissionDueAt, state.nextRetryDueAt()] + deferredNames.map { state.deferredDueAt($0) }).compactMap { $0 }.min()
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
}
