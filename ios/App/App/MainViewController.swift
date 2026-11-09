import UIKit
import Capacitor
import Network

/// Root bridge view controller. Chooses which server to load at launch — the iOS
/// counterpart of Android's MainActivity reading the saved URL in onCreate:
///   - a saved server  -> point the bridge at the server's Tailscale address when one
///     is saved and it answers with the id it was saved for, else at the saved address
///     ("Tailscale first, saved is the fallback", owner decision 2026-11-09; chooseAddress
///     below, the twin of MainActivity.chooseAddress). With no Tailscale address saved,
///     the saved address as always, with no probe and no wait.
///   - no saved server -> leave the descriptor on the bundled web assets, so the
///     first-run setup page (public/index.html) loads instead.
///
/// `ServerConfigPlugin.restart()` rebuilds this controller so a newly saved choice
/// takes effect, and so does NetworkRoamWatcher when the network changes and the choice
/// would now open the other address (both through AppRestart.rebuildRoot). `errorPath`
/// (error.html) comes from capacitor.config.json and covers the case where a saved
/// server is unreachable at launch.
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
            descriptor.serverURL = MainViewController.chooseAddress(saved: saved)
        }
        return descriptor
    }

    // How long each launch probe may take, in seconds: Android's 2500 ms (MainActivity.chooseAddress), carried over as a
    // judgement, not a measurement. It sits inside EndpointChooser's 3 s deadline, so a probe that has not answered by then
    // is abandoned, never waited for.
    private static let probeTimeout: TimeInterval = 2.5

    /// Which of the server's addresses to open: the saved Tailscale address if it answers with the id it was saved for, else
    /// `saved` if it answers (Tailscale first, owner decision 2026-11-09: EndpointChooser says why and how long `saved` waits for
    /// Tailscale), else `saved` as before (its "Can't reach your server" page then shows). Deliberately different from Android,
    /// which opens the bundled setup page when neither answers: iOS has always opened the saved address and let errorPath catch
    /// it, and that is kept.
    ///
    /// Runs on the main thread, from instanceDescriptor, and BLOCKS for up to EndpointChooser.defaultDeadlineMs (3 s) behind
    /// the launch screen, as Android's launch gate does behind its splash screen. Only when a Tailscale address is saved for
    /// the saved server: with none there is nothing to choose between, and the launch is exactly as before (no probe, no wait).
    /// Chosen at each launch and each rebuild (ServerConfig.restart(), or a move between the addresses when the network
    /// changes: NetworkRoamWatcher, which runs the same choice through chooseAnswer below first).
    static func chooseAddress(saved: String) -> String {
        return MainViewController.chooseAnswer(saved: saved) ?? saved
    }

    /// The choice itself: the address that answered (the Tailscale address with the id it was saved for first, then `saved`,
    /// as EndpointChooser decides), or nil when NEITHER answered or no Tailscale address is saved for `saved` (then nothing is
    /// probed, nothing is read and it returns at once). Split out of chooseAddress (Codex and Opus plan review of iOS phase 2,
    /// 2026-11-08) because the network watcher must tell "nothing answered" (it checks again a few times,
    /// RoamRules.retryDelayAfter) from "the saved address answered", which chooseAddress's fallback to `saved` hides. The twin
    /// of Android's MainActivity.chooseAddress, which returns null in the same case.
    ///
    /// Blocks its caller for up to VpnSnapshot's 0.2 s plus EndpointChooser.defaultDeadlineMs (3 s): on the main thread only
    /// from the launch (chooseAddress); NetworkRoamWatcher calls it on its own queue. It touches no view and no instance state
    /// (UserDefaults, the Network framework reading and the probes are safe from any thread).
    static func chooseAnswer(saved: String) -> String? {
        guard let tailscale = ServerConfigStore.tailscaleURL(for: saved) else { return nil }
        let wantedId: String? = ServerConfigStore.tailscaleId(for: saved)
        let timeout = MainViewController.probeTimeout
        let probe: (String) throws -> Bool = { url in
            return EndpointProbe.addressAnswers(url, tailscale: tailscale, wantedId: wantedId, timeout: timeout)
        }
        // How long an answer from the saved address waits for the Tailscale one: 1 s, or 0.25 s when the phone positively
        // reports no VPN (EndpointRules.tailscaleGraceMs says why; the reading is a hint that can only shorten the wait).
        let grace: Int = EndpointRules.tailscaleGraceMs(vpnPresent: VpnSnapshot.vpnPresent())
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
