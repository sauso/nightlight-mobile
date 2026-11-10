import UIKit
import Capacitor
import Network

/// Root bridge view controller. Chooses which server to load at launch — the iOS
/// counterpart of Android's MainActivity reading the saved URL in onCreate:
///   - a saved server  -> prefer its verified Tailscale address. Open the saved address
///     only after a matching strict reply when its id is known; otherwise load setup.
///     Without a known id, keep the earlier saved-address fallback.
///   - no saved server -> leave the descriptor on the bundled web assets, so the
///     first-run setup page (public/index.html) loads instead.
///
/// `ServerConfigPlugin.restart()` rebuilds this controller so a newly saved choice
/// takes effect, and so does NetworkRoamWatcher when the network changes and the choice
/// would now open the other address (both through AppRestart.rebuildRoot). `errorPath`
/// (error.html) comes from capacitor.config.json and covers an unreachable saved
/// server at launch only when its id is not yet known.
class MainViewController: CAPBridgeViewController {
    // Local app-target plugins aren't auto-discovered on iOS (unlike npm-package
    // plugins, and unlike Android's @CapacitorPlugin annotation) - they must be
    // registered explicitly here, or Capacitor.Plugins.* is undefined in the WebView.
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(ServerConfigPlugin())
        bridge?.registerPluginInstance(BackgroundAudioPlugin())
    }

    override func instanceDescriptor() -> InstanceDescriptor {
        let descriptor = super.instanceDescriptor()
        // serverURL is a String on iOS (the raw address), not a URL. The SAVED address is never rewritten by the choice:
        // only the address this bridge opens differs (and ServerConfigPlugin reads which one it was from bridge.config).
        if let saved = ServerConfigStore.savedURL {
            if let address = MainViewController.chooseAddress(saved: saved) {
                descriptor.serverURL = address
            } else {
                // Capacitor's nil serverURL uses bundled assets, as on the existing no-saved-address path.
                descriptor.serverURL = nil
            }
        } else {
            // A launch without a saved address is still a gate result; clear any refusal from the previous server.
            MovedServerCheck.shared.launchChoiceFinished(saved: nil, requestsCheck: false)
        }
        return descriptor
    }

    // How long each launch probe may take, in seconds: Android's 2500 ms (MainActivity.chooseAddress), carried over as a
    // judgement, not a measurement. It sits inside EndpointChooser's 3 s deadline, so a probe that has not answered by then
    // is abandoned, never waited for.
    private static let probeTimeout: TimeInterval = 2.5

    /// Which address to open. A known id requires a matching strict answer, so an address that changed hands cannot be
    /// opened through the saved fallback. With no known id, the old immediate fallback remains.
    ///
    /// Runs on the main thread, from instanceDescriptor, and BLOCKS for up to EndpointChooser.defaultDeadlineMs (3 s) behind
    /// the launch screen, as Android's launch gate does behind its splash screen. With no pair but a known id, one strict
    /// saved-address read is bounded to 1.5 s. With neither, there is still no probe or wait.
    /// Chosen at each launch and each rebuild (ServerConfig.restart(), or a move between the addresses when the network
    /// changes: NetworkRoamWatcher, which runs the same choice through chooseAnswer below first).
    static func chooseAddress(saved: String) -> String? {
        if let chosen = MainViewController.chooseAnswer(saved: saved) {
            MovedServerCheck.shared.launchChoiceFinished(saved: saved, requestsCheck: false)
            return chosen
        }
        // A dead saved address with a known id now loads setup instead of the error page. This cost prevents a failed
        // health check from being bypassed by a subsequent page request to a different device at that address.
        let expectedId = ServerConfigStore.expectedId(for: saved)
        let verdict: EndpointRules.LaunchVerdict = expectedId == nil ? .unchecked : .noAnswer
        // chooseAnswer collapses a mismatched strict reply and no reply into nil; both refuse the known saved server.
        MovedServerCheck.shared.launchChoiceFinished(saved: saved, requestsCheck: EndpointRules.launchRefusalRequestsCheck(
            expectedIdPresent: expectedId != nil, verdict: verdict,
            idCheckedAddress: EndpointRules.idCheckedAddress(saved)))
        return EndpointRules.launchChoice(saved: saved, verdict: verdict)
    }

    /// The choice itself: the address that answered (the Tailscale address with the id it was saved for first, then `saved`,
    /// as EndpointChooser decides), or nil when nothing answered. Without a pair, a known id gets one strict read; an
    /// unknown id returns nil without a probe. Split out of chooseAddress (Codex and Opus plan review of iOS phase 2,
    /// 2026-11-08) because the network watcher must tell "nothing answered" (it checks again a few times,
    /// RoamRules.retryDelayAfter) from "the saved address answered", which chooseAddress's fallback to `saved` hides. The twin
    /// of Android's MainActivity.chooseAddress, which returns null in the same case.
    ///
    /// Blocks its caller for up to VpnSnapshot's 0.2 s plus EndpointChooser.defaultDeadlineMs (3 s): on the main thread only
    /// from the launch (chooseAddress); NetworkRoamWatcher calls it on its own queue. It touches no view and no instance state
    /// (UserDefaults, the Network framework reading and the probes are safe from any thread).
    static func chooseAnswer(saved: String) -> String? {
        let expectedId = ServerConfigStore.expectedId(for: saved)
        guard let tailscale = ServerConfigStore.tailscaleURL(for: saved) else {
            guard let expected = expectedId else { return nil }
            // A judgement: a LAN reply is normally quick, while 1.5 s bounds an unreachable address at launch.
            let liveId = EndpointProbe.fetchHealth(saved, timeout: 1.5)?.id
            let verdict = EndpointRules.launchVerdict(expectedId: expected, liveId: liveId)
            return EndpointRules.launchChoice(saved: saved, verdict: verdict)
        }
        let wantedId: String? = ServerConfigStore.tailscaleId(for: saved)
        let timeout = MainViewController.probeTimeout
        let probe: (String) throws -> Bool = { url in
            return EndpointProbe.addressAnswers(url, tailscale: tailscale, wantedId: wantedId,
                                                expectedId: expectedId, timeout: timeout)
        }
        // A Tailscale slot without VPN gets the short grace; a custom HTTPS slot needs no VPN and gets the full second.
        let grace: Int = EndpointRules.tailscaleGraceMs(vpnPresent: VpnSnapshot.vpnPresent(), slotUrl: tailscale)
        let chooser = EndpointChooser(probe: probe, deadlineMs: EndpointChooser.defaultDeadlineMs, tailscaleGraceMs: grace)
        return chooser.choose(local: saved, tailscale: tailscale)
    }
}

/// Whether the phone has a VPN up, as a HINT for the launch's Tailscale grace (EndpointRules.tailscaleGraceMs), read once per
/// choice (at launch, and in each of the network watcher's checks, which run the same choice; it is not the watcher's own
/// long-running monitor). It replaced the Wi-Fi-or-Ethernet reading the old short off-Wi-Fi wait used (Tailscale first, owner
/// decision 2026-11-09), with the same shape. The iOS twin of Android's VpnPresent.
///
/// A VPN counts as present when the path lists an interface of type `.other` among the ones available: Tailscale's iOS app is a
/// VPN, and its tunnel is not Wi-Fi, mobile data, Ethernet or loopback. Apple defines `.other` as "virtual or unknown", so this
/// is a hint, not proof: another virtual interface would read as a VPN too, which costs the full 1 s grace and nothing else.
/// UNCONFIRMED ON A PHONE that a Tailscale tunnel shows up this way (Codex plan review of Tailscale first, 2026-11-09). It FAILS
/// OPEN: no reading in time, or none at all, counts as a VPN present, which gives the full grace, so a failed reading can only
/// cost time, never lose a Tailscale answer that would have come in the full grace.
///
/// NWPathMonitor gives the current path in its first update, delivered just after start, so one reading is a start, a short
/// wait and a cancel. Glue over the Network framework: no test reaches it; the grace it feeds is tested in ios/Tests
/// (EndpointRulesTests, tailscaleGraceMs).
enum VpnSnapshot {
    // How long to wait for the first reading, in seconds. A judgement, not a measurement (kept from the reading it replaced):
    // the first update normally comes within milliseconds, and this keeps a slow one from adding more than a fifth of a second
    // to a launch that may then wait three. No reading in time means a VPN is assumed (fail open).
    private static let firstReadingWait: TimeInterval = 0.2

    static func vpnPresent() -> Bool {
        let monitor = NWPathMonitor()
        let reading = PathReading()
        let first = DispatchSemaphore(value: 0)
        monitor.pathUpdateHandler = { path in
            let present: Bool = path.availableInterfaces.contains { $0.type == .other }
            reading.set(present)
            first.signal()
        }
        monitor.start(queue: DispatchQueue(label: "com.sauso.nightlight.vpn-hint"))
        let answered = first.wait(timeout: .now() + firstReadingWait)
        monitor.cancel()
        if answered == .timedOut { return true }
        return reading.get() ?? true
    }
}

/// The reading the path monitor writes on its own queue and vpnPresent reads on its caller's thread, so it is locked.
private final class PathReading {
    private let lock = NSLock()
    private var value: Bool? = nil

    func set(_ newValue: Bool) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func get() -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
