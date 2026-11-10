import Foundation
import Network
import UIKit

/// Foreground-only check for a saved private IPv4 address. The worker queue owns the search and its state;
/// UIKit and the shared prompt are reached asynchronously. A lifecycle ticket invalidates a probe already in flight.
final class MovedServerCheck {
    static let shared = MovedServerCheck()

    private struct Offer {
        let old: String
        let wanted: String
        let pairUrl: String?
        let pairId: String?
        let newUrl: String
        let name: String
        let fingerprint: String
    }

    private let queue = DispatchQueue(label: "com.sauso.nightlight.moved-server")
    private let lifecycleLock = NSLock()
    private var active = false
    private var generation = 0
    private var refusedUrl: String?
    private var suppressNextLaunchRefusalForUrl: String?
    // Everything below is touched only by queue, except the lock-protected ticket above.
    private var running = false
    private var lastRunMs: Int64?
    private var monitor: NWPathMonitor?
    private var discovery: ServerDiscovery?

    private init() {}

    /// The launch gate reports its final choice on the main thread. An accepted move rebuilds through this same gate;
    /// its tap-time strict probe already verified the new address, so that rebuild must not create another urgent search.
    func launchChoiceFinished(saved: String?, requestsCheck: Bool) {
        lifecycleLock.lock()
        let suppressed = saved != nil && suppressNextLaunchRefusalForUrl == saved
        // Every gate result replaces the previous refusal, including a launch with no saved address.
        suppressNextLaunchRefusalForUrl = nil
        refusedUrl = requestsCheck && !suppressed ? saved : nil
        lifecycleLock.unlock()
    }

    var hasLaunchRefusalRequest: Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return MovedServerRules.Throttle.skipOnceFor(refusedUrl: refusedUrl, savedUrl: ServerConfigStore.savedURL)
    }

    private func suppressRefusalFromAcceptedMove(to url: String) {
        lifecycleLock.lock()
        suppressNextLaunchRefusalForUrl = url
        lifecycleLock.unlock()
    }

    /// AppDelegate calls this on activation. The throttle is consumed only after all cheap gates pass.
    func runIfDue() {
        guard UIApplication.shared.applicationState == .active else { return }
        lifecycleLock.lock()
        active = true
        let ticket = generation
        lifecycleLock.unlock()
        queue.async { [self] in begin(ticket) }
    }

    /// Background and root rebuild make old callbacks inert immediately, even if the worker is in a bounded probe.
    func stop() {
        lifecycleLock.lock()
        active = false
        generation += 1
        lifecycleLock.unlock()
        queue.async { [self] in end() }
        if Thread.isMainThread {
            AddressPrompt.shared.cancelMovedPending()
        } else {
            DispatchQueue.main.async { AddressPrompt.shared.cancelMovedPending() }
        }
    }

    private func isCurrent(_ ticket: Int) -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return active && generation == ticket
    }

    private func begin(_ ticket: Int) {
        guard isCurrent(ticket), !running, !AddressPrompt.shared.isBusy,
              let old = ServerConfigStore.savedURL,
              let parsed = DiscoveryRules.parseFoundUrl(old), EndpointRules.isPrivateIpv4(parsed.host) else { return }
        let now = AddressPrompt.monotonicMs()
        lifecycleLock.lock()
        let skipOnce = MovedServerRules.Throttle.skipOnceFor(refusedUrl: refusedUrl, savedUrl: old)
        // A cheap early exit before this point (for example, another prompt showing) leaves this URL's refusal pending.
        // Once a run starts, discard it even if the saved URL changed; another server must not inherit its bypass.
        refusedUrl = nil
        lifecycleLock.unlock()
        // Consume as this run starts, even if the path is not Wi-Fi or the subsequent probe finds nothing.
        guard MovedServerRules.Throttle.mayRun(nowMs: now, lastRunMs: lastRunMs,
                                               skipOnce: skipOnce) else { return }
        running = true
        // One-shot path check, as in ServerConfigPlugin.startDiscovery. A missing first update ends quietly after one second.
        let pathMonitor = NWPathMonitor()
        monitor = pathMonitor
        pathMonitor.pathUpdateHandler = { [weak self] path in
            self?.queue.async { [weak self] in self?.pathAnswered(path, old: old, ticket: ticket) }
        }
        pathMonitor.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.pathAnswered(nil, old: old, ticket: ticket)
        }
    }

    private func pathAnswered(_ path: NWPath?, old: String, ticket: Int) {
        guard isCurrent(ticket), let pathMonitor = monitor else { return }
        pathMonitor.cancel()
        monitor = nil
        guard let path = path, path.status == .satisfied,
              path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) else { end(); return }
        lastRunMs = AddressPrompt.monotonicMs()
        // This is the only old-address request. fetchHealth refuses redirects and cookies and times out in two seconds.
        let stored = ServerConfigStore.serverId(for: old)
        let live = EndpointProbe.fetchHealth(old, timeout: TimeInterval(MovedServerRules.PROBE_TIMEOUT_MS) / 1000)
        guard isCurrent(ticket) else { end(); return }
        // The pair's stored id identifies this saved address even if Tailscale is offline now.
        // No second network request is needed to decide whether to search or learn.
        let pairUrl = ServerConfigStore.tailscaleURL(for: old)
        let pairId = ServerConfigStore.tailscaleId(for: old)
        let probe: MovedServerRules.Probe = live.map { .answered(id: $0.id) } ?? .noAnswer
        switch MovedServerRules.decide(savedUrl: old, storedId: stored, pairId: pairId, probe: probe) {
        case .learn(let id):
            ServerConfigStore.writes.async {
                // A switch between the reply and the write must not learn the id for a different active choice.
                if ServerConfigStore.savedURL == old, ServerConfigStore.serverId(for: old) == nil,
                   ServerConfigStore.expectedId(for: old).map({ $0 == id }) ?? true {
                    ServerConfigStore.setServerId(id, for: old)
                }
            }
            end()
        case .search(let wanted):
            startSearch(old: old, wanted: wanted, pairUrl: pairUrl, pairId: pairId, ticket: ticket)
        case .skip, .stop:
            end()
        }
    }

    private func startSearch(old: String, wanted: String, pairUrl: String?, pairId: String?, ticket: Int) {
        guard isCurrent(ticket) else { end(); return }
        let search = ServerDiscovery { [weak self] event in
            self?.queue.async { [weak self] in
                self?.found(event, old: old, wanted: wanted, pairUrl: pairUrl, pairId: pairId, ticket: ticket)
            }
        }
        discovery = search
        search.start()
        queue.asyncAfter(deadline: .now() + .milliseconds(MovedServerRules.SEARCH_BOUND_MS)) { [weak self] in
            guard let self = self, self.isCurrent(ticket), self.discovery === search else { return }
            self.end()
        }
    }

    private func found(_ event: ServerDiscovery.Event, old: String, wanted: String,
                       pairUrl: String?, pairId: String?, ticket: Int) {
        guard isCurrent(ticket), discovery != nil else { return }
        guard ServerConfigStore.savedURL == old else { end(); return }
        switch event {
        case .found(let card):
            guard MovedServerRules.acceptCandidate(wantedId: wanted, savedUrl: old,
                                                   foundId: card.id, foundUrl: card.url) else { return }
            // Discovery publishes one verified card per id. A declined first card ends this attempt quietly.
            let declines = ServerConfigStore.movedDeclines(for: old)
            if MovedServerRules.Declines.isDeclined(declines, candidateUrl: card.url,
                                                    nowMs: AddressPrompt.wallClockMs()) {
                end()
                return
            }
            discovery?.stop()
            discovery = nil
            let offer = Offer(old: old, wanted: wanted, pairUrl: pairUrl, pairId: pairId,
                              newUrl: card.url, name: card.name, fingerprint: card.fingerprint)
            show(offer, ticket: ticket)
        case .failed:
            end()
        case .lost, .status:
            break
        }
    }

    private func show(_ offer: Offer, ticket: Int) {
        guard isCurrent(ticket) else { end(); return }
        let message = MovedServerRules.promptMessage(oldUrl: offer.old, newUrl: offer.newUrl,
                                                     offeredName: offer.name,
                                                     storedName: ServerConfigStore.storedNames[offer.old],
                                                     fingerprint: offer.fingerprint)
        let reservation = AddressPrompt.shared.reserveMoved(completion: { [weak self] outcome in
            self?.queue.async { [weak self] in self?.answered(outcome, offer: offer, ticket: ticket) }
        })
        guard reservation.decision == .ask else { end(); return }
        DispatchQueue.main.async {
            guard UIApplication.shared.applicationState == .active,
                  ServerConfigStore.savedURL == offer.old else {
                AddressPrompt.shared.finish(reservation.serial, .notAnswered, dismissAlert: false)
                return
            }
            AddressPrompt.shared.present(reservation.serial, title: MovedServerRules.title, message: message,
                                         declineButton: MovedServerRules.notNowButton,
                                         acceptButton: MovedServerRules.useNewAddressButton)
        }
    }

    private func answered(_ outcome: PromptGate.Outcome, offer: Offer, ticket: Int) {
        guard isCurrent(ticket) else { end(); return }
        switch outcome {
        case .declined:
            ServerConfigStore.writes.async {
                let map = ServerConfigStore.movedDeclines(for: offer.old)
                ServerConfigStore.setMovedDeclines(MovedServerRules.Declines.record(map,
                    candidateUrl: offer.newUrl, nowMs: AddressPrompt.wallClockMs()), for: offer.old)
            }
            end()
        case .accepted:
            move(offer, ticket: ticket)
        case .notAnswered:
            end()
        }
    }

    private func move(_ offer: Offer, ticket: Int) {
        // Recheck the new address at the tap, off main. The offered name is replaced by this same strict reply's name.
        let live = EndpointProbe.fetchHealth(offer.newUrl,
            timeout: TimeInterval(MovedServerRules.PROBE_TIMEOUT_MS) / 1000)
        guard isCurrent(ticket), let health = live, health.id == offer.wanted else { end(); return }
        ServerConfigStore.writes.async { [weak self] in
            let currentId = ServerConfigStore.serverId(for: offer.old)
            let pairStillSaved = offer.pairId == offer.wanted &&
                ServerConfigStore.tailscaleURL(for: offer.old) == offer.pairUrl &&
                ServerConfigStore.tailscaleId(for: offer.old) == offer.pairId
            let effectiveId = currentId ?? (pairStillSaved ? offer.pairId : nil)
            guard MovedServerRules.MoveGuard.stillValid(currentSavedUrl: ServerConfigStore.savedURL,
                    currentStoredId: effectiveId, expectedOldUrl: offer.old, expectedId: offer.wanted),
                   self?.isCurrent(ticket) == true else {
                self?.queue.async { [weak self] in self?.end() }
                return
            }
            let plan = MovedServerRules.movePlan(oldUrl: offer.old, newUrl: offer.newUrl, id: offer.wanted,
                name: health.name, knownServers: ServerConfigStore.knownServers,
                hasNewUrlLeftovers: ServerConfigStore.hasMoveDestinationLeftovers(offer.newUrl, wantedId: offer.wanted),
                pairUrl: ServerConfigStore.tailscaleURL(for: offer.old), pairId: ServerConfigStore.tailscaleId(for: offer.old))
            for step in plan {
                switch step {
                case .clearNew(let url):
                    guard ServerConfigStore.clearMoveDestination(url) else {
                        self?.queue.async { [weak self] in self?.end() }
                        return
                    }
                case .addKnown(let url): ServerConfigStore.remember(url)
                case .setName(let url, let name): ServerConfigStore.rememberName(url, name)
                case .setServerId(let url, let id): ServerConfigStore.setServerId(id, for: url)
                case .carryPair(let url, let pairUrl, let id): ServerConfigStore.setTailscale(pairUrl, id: id, for: url)
                case .setSavedUrl(let url): ServerConfigStore.savedURL = url
                case .removeOld(let url): ServerConfigStore.forget(url)
                }
            }
            self?.queue.async { [weak self] in self?.end() }
            DispatchQueue.main.async {
                let window = (UIApplication.shared.delegate as? AppDelegate)?.window
                // The check's own accepted move can rebuild the root, but must not generate another urgent check.
                if window != nil { self?.suppressRefusalFromAcceptedMove(to: offer.newUrl) }
                AppRestart.rebuildRoot(window: window)
            }
        }
    }

    private func end() {
        monitor?.cancel()
        monitor = nil
        discovery?.stop()
        discovery = nil
        running = false
    }
}
