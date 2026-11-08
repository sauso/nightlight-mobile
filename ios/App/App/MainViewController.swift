import UIKit
import Capacitor
import Network

/// Root bridge view controller. Chooses which server to load at launch — the iOS
/// counterpart of Android's MainActivity reading the saved URL in onCreate:
///   - a saved server  -> point the bridge at it (loads the remote web UI), or at the
///     server's Tailscale address when one is saved and the saved address does not
///     answer (chooseAddress below, the twin of MainActivity.chooseAddress)
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

    /// Which of the server's addresses to open: `saved` if it answers, else the saved Tailscale address if THAT answers with
    /// the id it was saved for, else `saved` as before (its "Can't reach your server" page then shows). Deliberately different
    /// from Android, which opens the bundled setup page when neither answers: iOS has always opened the saved address and let
    /// errorPath catch it, and that is kept.
    ///
    /// Runs on the main thread, from instanceDescriptor, and BLOCKS for up to EndpointChooser.defaultDeadlineMs (3 s) behind
    /// the launch screen, as Android's launch gate does behind its splash screen. Only when a Tailscale address is saved for
    /// the saved server: with none there is nothing to choose between, and the launch is exactly as before (no probe, no wait).
    /// Chosen at each launch and each rebuild (ServerConfig.restart(), or a move between the addresses when the network
    /// changes: NetworkRoamWatcher, which runs the same choice through chooseAnswer below first).
    static func chooseAddress(saved: String) -> String {
        return MainViewController.chooseAnswer(saved: saved) ?? saved
    }

    /// The choice itself: the address that answered (`saved` first, then the Tailscale address with the id it was saved for),
    /// or nil when NEITHER answered or no Tailscale address is saved for `saved` (then nothing is probed and it returns at
    /// once). Split out of chooseAddress (Codex and Opus plan review of iOS phase 2, 2026-11-08) because the network watcher
    /// must tell "nothing answered" (it checks again a few times, RoamRules.retryDelayAfter) from "the saved address answered",
    /// which chooseAddress's fallback to `saved` hides. The twin of Android's MainActivity.chooseAddress, which returns null in
    /// the same case.
    ///
    /// Blocks its caller for up to EndpointChooser.defaultDeadlineMs (3 s): on the main thread only from the launch
    /// (chooseAddress); NetworkRoamWatcher calls it on its own queue. It touches no view and no instance state (UserDefaults,
    /// the Network framework reading and the probes are safe from any thread).
    static func chooseAnswer(saved: String) -> String? {
        guard let tailscale = ServerConfigStore.tailscaleURL(for: saved) else { return nil }
        // The Tailscale address only counts if the server answering there still has the id it was saved for, so an address
        // saved for one server can never open another (a saved address handed to a different machine, say).
        let wantedId: String? = ServerConfigStore.tailscaleId(for: saved)
        let timeout = MainViewController.probeTimeout
        let probe: (String) throws -> Bool = { url in
            if url == tailscale {
                guard let wanted = wantedId else { return false }
                let live: String? = EndpointProbe.fetchHealth(url, timeout: timeout)?.id
                return live == wanted
            }
            return EndpointProbe.isReachable(url, timeout: timeout)
        }
        // Off Wi-Fi with a home-network-only saved address, that address cannot answer, so it is waited for only briefly
        // (EndpointRules.localWaitMs); otherwise the full 3 s.
        let onLocalNetwork = LocalNetworkSnapshot.isOnLocalNetwork()
        let localWait = EndpointRules.localWaitMs(saved: saved, tailscale: tailscale, onLocalNetwork: onLocalNetwork, fullMs: EndpointChooser.defaultDeadlineMs)
        let chooser = EndpointChooser(probe: probe, deadlineMs: EndpointChooser.defaultDeadlineMs, localDeadlineMs: localWait)
        return chooser.choose(local: saved, tailscale: tailscale)
    }
}

/// Whether this phone is on a local network at all: Wi-Fi or Ethernet, read once per choice (at launch, and in each of the
/// network watcher's checks, which run the same choice; it is not the watcher's own long-running monitor). The iOS twin of Android's
/// LocalNetwork.isOn, and like it, it FAILS OPEN (true) when the reading cannot be had: it only chooses how long the launch
/// waits for the saved address (EndpointRules.localWaitMs), and the ordinary rules stand either way.
///
/// NWPathMonitor gives the current path in its first update, delivered just after start, so one reading is a start, a short
/// wait and a cancel. A path counts as local when it uses Wi-Fi or wired Ethernet, or lists such an interface among the ones
/// available (with a VPN such as Tailscale on, the path itself may run over the VPN's own interface). Glue over the Network
/// framework: no test reaches it; the decision it feeds is tested in ios/Tests (localWaitMs).
enum LocalNetworkSnapshot {
    // How long to wait for the first reading, in seconds. A judgement, not a measurement: the first update normally comes
    // within milliseconds, and this keeps a slow one from adding more than a fifth of a second to a launch that may then wait
    // three. No reading in time means the full wait (fail open).
    private static let firstReadingWait: TimeInterval = 0.2

    static func isOnLocalNetwork() -> Bool {
        let monitor = NWPathMonitor()
        let reading = PathReading()
        let first = DispatchSemaphore(value: 0)
        monitor.pathUpdateHandler = { path in
            let listed = path.availableInterfaces.contains { $0.type == .wifi || $0.type == .wiredEthernet }
            let local = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) || listed
            reading.set(local)
            first.signal()
        }
        monitor.start(queue: DispatchQueue(label: "com.sauso.nightlight.local-network"))
        let answered = first.wait(timeout: .now() + firstReadingWait)
        monitor.cancel()
        if answered == .timedOut { return true }
        return reading.get() ?? true
    }
}

/// The reading the path monitor writes on its own queue and isOnLocalNetwork reads on the main thread, so it is locked.
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
