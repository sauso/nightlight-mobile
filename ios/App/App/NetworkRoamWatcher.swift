import Foundation
import Network
import UIKit
import Capacitor
import WebKit

// NetworkRoamWatcher.swift: moves the app to the server's other address when the network changes (owner, 2026-11-06 on
// Android; iOS phase 2, part 2, 2026-11-08). The launch choice (MainViewController.instanceDescriptor) used to be the only place
// that chose between the saved address and the Tailscale one, so on an iPhone, Wi-Fi turning to mobile data left the page on a
// dead home address until the app was closed and opened again.
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/NetworkRoamWatcher.kt. The rules it applies are in
// RoamRules.swift (pure, run by ios/Tests in CI); this is the glue, which no test reaches: only an iPhone proves it (the PR's
// phone checklist).
//
// What it does, while the app is in the foreground:
//  - one NWPathMonitor (the Network framework, also used by MainViewController's VpnSnapshot) is started at the first
//    applicationDidBecomeActive after launch and after each return from the background, and cancelled in
//    applicationDidEnterBackground (AppDelegate). A NEW monitor is made each time: a cancelled one cannot be started again.
//    It keeps running through a short inactivity (Control Center, a call banner, the app switcher fire didBecomeActive without
//    a trip to the background), and a didBecomeActive while it runs does nothing at all, so those cannot reload a working page
//    for an upgrade in the middle of use (Codex and Opus plan review of iOS phase 2, 2026-11-08);
//  - a real change (NetworkEventFilter over NetworkSignature: path status + interface types + gateways) schedules a
//    NETWORK_CHANGE check; the first activation after launch and the first after a return from the background schedule one
//    FOREGROUND_START check, so an app brought back to the front after roaming in the background, or with Tailscale turned on
//    meanwhile, re-chooses too. Each waits RoamRules.settleMs, and a newer request replaces a waiting one (a flapping network
//    is one check);
//  - the check, on the watcher's queue: the page the web view is really showing must be on the saved address or its Tailscale
//    address (RoamRules.currentAddress), then the launch's own choice runs (MainViewController.chooseAnswer, so the two cannot
//    drift: Tailscale first, the saved address as the fallback, owner decision 2026-11-09); when it picks the OTHER address,
//    the page's own address gets a second look (does it still answer?), RoamRules.target says whether to move (an ESCAPE off a
//    dead page, or an UPGRADE of a working saved page at a start), and RecreateLog says whether a rebuild is allowed now;
//  - when nothing answered, it checks again a few times (RoamRules.retryDelayAfter) and then waits for the next event;
//  - the switch is the same root-view-controller rebuild ServerConfig.restart() does (AppRestart.rebuildRoot, ONE function so
//    the two cannot drift), on the main thread, after checking again that the page has not moved.
// Without a saved server, or without a Tailscale address saved for it, every check returns before any probe, so a person who
// does not use Tailscale sees no change.
//
// THE GENERATION. Every request carries the generation it was made under. didEnterBackground and every rebuild
// (rebuildStarted) move it on, which makes any check or retry still waiting or running dead on arrival: a late probe can never
// rebuild an app that went to the background or was rebuilt since. On Android a recreate stops the activity and so moves the
// generation; on iOS a rebuild is not an app lifecycle event, so the shared rebuild moves it itself (Codex and Opus plan review
// of iOS phase 2, 2026-11-08). It is the one value shared with the main thread, so it is behind a lock.
//
// THREADS, and THE RULE (the one PR 1 states at ServerConfigStore.writes): the MAIN thread never `sync`s onto this watcher's
// queue, onto ServerConfigStore.writes or onto any worker queue, and nothing waits for the main thread without a time limit.
// The lifecycle calls only queue work with `async` and take the generation lock for an instant. The checks run on ONE serial
// queue (`queue`), which also receives the path updates, so the scheduling state needs no lock. They read the web view on the
// main thread with `async` plus a one-second wait (the shape of ServerConfigPlugin.readWebView): if the main thread is busy
// (a rebuild's launch choice holds it for up to 3 s) the answer is "not known", which never moves anything. If the main thread
// ever waited on a queue that was itself waiting on the main thread, the app would freeze; nothing here does.
//
// Not covered, as on Android: the watcher runs only while the app is in the foreground. With audio playing in the background
// (screen off) on a network that has stopped working, nothing is rescued until the app is brought to the front again.

final class NetworkRoamWatcher {

    /// The one watcher, for the life of the process. App-wide, NOT tied to a view controller: a rebuild replaces the view
    /// controller, and the watcher (and its RecreateLog) must outlive it.
    static let shared = NetworkRoamWatcher()

    // How long a check waits for the main thread to read the page's URL or the Picture-in-Picture state, in seconds. Android's
    // MAIN_THREAD_TIMEOUT_MS and ServerConfigPlugin.pageReadTimeout: the main thread answers in microseconds unless it is busy,
    // and an answer that does not come counts as "not known" (which never switches anything).
    private static let mainThreadTimeout: TimeInterval = 1

    // The second look at the address on screen before any move (see evaluate), in seconds. Android's STILL_ANSWERING_MS:
    // shorter than the launch's 2.5 s probe, because the choice's probes already waited and this one only has to tell a page
    // that works from one that has stopped. A judgement, not a measurement.
    private static let stillAnsweringTimeout: TimeInterval = 1.5

    // Whether a video is floating in Picture-in-Picture, read in the page. `document.pictureInPictureElement` is the standard
    // API the web app uses (CameraTile.jsx requestPictureInPicture); WebKit's own `webkitPresentationMode` is read as well, so a
    // video put into Picture-in-Picture by WebKit's own control also counts. Anything that throws answers null, which is
    // "not known" and counts as in Picture-in-Picture (see evaluate). Unconfirmed on a phone which of the two an iPhone reports.
    private static let pipScript: String = "(function () { try { if (document.pictureInPictureElement) { return true; } var videos = document.getElementsByTagName('video'); for (var i = 0; i < videos.length; i++) { if (videos[i].webkitPresentationMode === 'picture-in-picture') { return true; } } return false; } catch (e) { return null; } })()"

    // The interface types a path signature records, by whether the path uses them and whether it has them available.
    private static let interfaceKinds: [NWInterface.InterfaceType] = [.wifi, .cellular, .wiredEthernet, .loopback, .other]

    // ONE serial queue for the path updates, the scheduling, the waits and the checks. A check blocks it for a few seconds (the
    // main-thread reads, the up-to-3-s choice, the 1.5 s second look), which only delays the next one.
    private let queue = DispatchQueue(label: "com.sauso.nightlight.roam")

    // The generation (see the header). Read on the queue and on the main thread, so it is behind its own lock.
    private let generationLock = NSLock()
    private var generation: Int = 0

    // Every roam this app makes on its own (RoamRules.swift says why only roams are recorded on iOS). Lives as long as the
    // watcher, so it survives the rebuilds it records.
    private let roamLog = RecreateLog()

    // ---- Read and written ONLY on `queue` ----

    // The running monitor, or nil while the app is in the background.
    private var monitor: NWPathMonitor? = nil
    // Which monitor the current one is: a path update from a monitor that has since been cancelled (one already queued when it
    // was cancelled) carries an older number and is ignored.
    private var monitorSerial: Int = 0
    // One filter per monitor: a new monitor's first update is its own echo.
    private var filter = NetworkEventFilter<NetworkSignature>()
    // A FOREGROUND_START check is owed: at the first activation after launch, and after each return from the background
    // (willEnterForeground). Consumed when the check is scheduled.
    private var startOwed: Bool = true
    // True from the scheduling of a start check until the first check runs. Ported from Android: a start check still waiting
    // keeps its FOREGROUND_START trigger when a path update replaces it. Unlocking the phone usually brings a path update with
    // it (Wi-Fi coming back), and letting that turn the start check into a NETWORK_CHANGE check would never upgrade a working
    // saved page to the Tailscale address, so the person would stay on the saved address until the next time they opened the
    // app.
    private var startPending: Bool = false
    // The check or retry waiting to run, so a newer request (or the background) can cancel it, and the generation it was made
    // under (see rebuildStarted).
    private var waiting: DispatchWorkItem? = nil
    private var waitingGeneration: Int = 0

    private init() {}

    // ---- Lifecycle: called on the main thread (AppDelegate, AppRestart). Each only queues work with `async`. ----

    /// applicationDidBecomeActive. Starts a new monitor if none is running and schedules the start check if one is owed. While
    /// a monitor is running it does nothing (a Control Center pull, a call banner or the app switcher fire this without a
    /// trip to the background, and must not start a check that could reload a working page for an upgrade in the middle of
    /// use).
    func didBecomeActive() {
        queue.async {
            self.activate()
        }
    }

    /// applicationWillEnterForeground: a real return from the background, so the next activation owes a start check.
    func willEnterForeground() {
        queue.async {
            self.startOwed = true
        }
    }

    /// applicationDidEnterBackground: the generation moves on at once (a check in flight dies at its next look), then the
    /// monitor is cancelled and the waiting check removed.
    func didEnterBackground() {
        bumpGeneration()
        queue.async {
            self.deactivate()
        }
    }

    /// Called by AppRestart.rebuildRoot just before it replaces the root view controller (a roam, or ServerConfig.restart()):
    /// the generation moves on so a check in flight dies, and a check still waiting from before is removed. The monitor keeps
    /// running: the app is still in the foreground, and the rebuild itself re-runs the launch choice.
    func rebuildStarted() {
        bumpGeneration()
        queue.async {
            self.dropStaleWaiting()
        }
    }

    // ---- The generation ----

    private func bumpGeneration() {
        generationLock.lock()
        generation += 1
        generationLock.unlock()
    }

    private func currentGeneration() -> Int {
        generationLock.lock()
        defer { generationLock.unlock() }
        return generation
    }

    private func isCurrent(_ gen: Int) -> Bool {
        return currentGeneration() == gen
    }

    // ---- On `queue` ----

    private func activate() {
        if monitor != nil { return }
        monitorSerial += 1
        let serial = monitorSerial
        filter = NetworkEventFilter<NetworkSignature>()
        let newMonitor = NWPathMonitor()
        newMonitor.pathUpdateHandler = { (path: NWPath) in
            self.pathUpdated(path, serial: serial)
        }
        monitor = newMonitor
        // Its updates arrive on this same serial queue, so they never run at the same time as a check.
        newMonitor.start(queue: queue)
        if startOwed {
            startOwed = false
            startPending = true
            schedule(currentGeneration(), .foregroundStart, attempt: 0, delayMs: RoamRules.settleMs)
        }
    }

    private func deactivate() {
        dropWaiting()
        if let running = monitor {
            running.cancel()
        }
        monitor = nil
        monitorSerial += 1
    }

    private func dropWaiting() {
        waiting?.cancel()
        waiting = nil
        startPending = false
    }

    // After a rebuild: removes the waiting check only if it was made BEFORE the rebuild (an older generation). A path update
    // that reached this queue after the generation moved on was scheduled under the new one and is kept, so a network change
    // during a rebuild is not lost. A start check that dies here takes startPending with it: a dead check returns before it
    // clears the flag (evaluate), and a flag left on would let a later NETWORK_CHANGE check run as a start check, which may
    // reload a working page for an upgrade in the middle of use. iOS-only: on Android a recreate is followed by onStart, which schedules a
    // new start check and sets the flag again anyway.
    private func dropStaleWaiting() {
        if waitingGeneration == currentGeneration() { return }
        waiting?.cancel()
        waiting = nil
        startPending = false
    }

    private func pathUpdated(_ path: NWPath, serial: Int) {
        if monitor == nil || serial != monitorSerial { return }
        let signature: NetworkSignature = NetworkRoamWatcher.signature(of: path)
        if !filter.onUpdate(signature) { return }
        // A start check still waiting keeps its trigger (see startPending).
        let trigger: RoamTrigger = startPending ? .foregroundStart : .networkChange
        schedule(currentGeneration(), trigger, attempt: 0, delayMs: RoamRules.settleMs)
    }

    // Replaces whatever check or retry is waiting: a flapping network is one check, and a new network starts the retries from
    // the beginning.
    private func schedule(_ gen: Int, _ trigger: RoamTrigger, attempt: Int, delayMs: Int) {
        if !isCurrent(gen) { return }
        waiting?.cancel()
        let item = DispatchWorkItem {
            self.evaluate(gen, trigger, attempt: attempt)
        }
        waiting = item
        waitingGeneration = gen
        let deadline: DispatchTime = DispatchTime.now() + DispatchTimeInterval.milliseconds(delayMs)
        queue.asyncAfter(deadline: deadline, execute: item)
    }

    // The check. See the header for the order; the generation is looked at again after every step that can take time.
    private func evaluate(_ gen: Int, _ trigger: RoamTrigger, attempt: Int) {
        if !isCurrent(gen) { return }
        startPending = false
        // Without a saved address and its Tailscale address there is nothing to move between (UserDefaults is safe to read from
        // any thread).
        guard let saved = ServerConfigStore.savedURL else { return }
        guard let tailscale = ServerConfigStore.tailscaleURL(for: saved) else { return }
        // The page on screen must be one of the two (not the bundled setup or error page), or nothing is probed at all. No
        // answer from the main thread in time is no page.
        let shown: String? = readShownPage()
        guard let current = RoamRules.currentAddress(webViewUrl: shown, saved: saved, tailscale: tailscale) else { return }
        // The launch's own choice, off the main thread: Tailscale first, the saved address as the fallback (owner decision
        // 2026-11-09). Blocks this queue for up to 3 s (plus the VPN reading's 0.2 s at most).
        let chosen: String? = MainViewController.chooseAnswer(saved: saved)
        if !isCurrent(gen) { return }
        // THE SECOND LOOK (Codex plan review of Tailscale first, 2026-11-09). The choice now prefers Tailscale, so its picking the
        // other address no longer says that the page on screen is dead (a working saved page is passed over whenever Tailscale
        // answers). So, only when there is somewhere else to go, the page's OWN address is asked once more, for up to 1.5 s, with
        // the same check the choice uses (EndpointProbe.addressAnswers: the Tailscale address only counts with the id it was saved
        // for). It replaces the old second look, which asked the saved address again before a move to Tailscale only.
        // EndpointProbe never throws, so a look that is taken is true (works) or false (dead: a failed request, an error status,
        // a wrong id, or the timeout); nil means no look was taken, and target then stays anyway (nothing chosen, or the same
        // address). A slow page misjudged as dead moves to an address that has just answered: the risk the old fail-over took,
        // stated as a known limit in the README.
        var currentAnswers: Bool? = nil
        if let picked = chosen, picked != current {
            let wantedId: String? = ServerConfigStore.tailscaleId(for: saved)
            currentAnswers = EndpointProbe.addressAnswers(current, tailscale: tailscale, wantedId: wantedId, timeout: NetworkRoamWatcher.stillAnsweringTimeout)
            if !isCurrent(gen) { return }
        }
        // Read LAST before the decision, so nothing slow runs between this read and the rebuild (see finishRoam). Not known (the
        // main thread did not answer in time, or the page could not say) counts as in Picture-in-Picture: that only ever holds
        // back an upgrade, never an escape (Codex and Opus plan review of iOS phase 2, 2026-11-08, as Android).
        let inPip: Bool = readInPictureInPicture() ?? true
        guard let move = RoamRules.target(trigger: trigger, saved: saved, tailscale: tailscale, current: current, chosen: chosen, inPip: inPip, currentAnswers: currentAnswers) else {
            // Unchanged: only "nothing answered" retries. A page that works needs none (the next start check does the upgrade).
            if chosen == nil, let wait = RoamRules.retryDelayAfter(attempt: attempt) {
                schedule(gen, trigger, attempt: attempt + 1, delayMs: wait)
            }
            return
        }
        if !isCurrent(gen) { return }
        let which: String = (move.address == tailscale) ? "the Tailscale address" : "the saved address"
        let kind: String = move.escape ? "escape from a page that stopped answering" : "upgrade"
        let why: String = (trigger == .foregroundStart) ? "foreground start" : "network change"
        if !roamLog.mayRoam(nowMs: NetworkRoamWatcher.clockMs()) {
            // No retry: the next network change or the next start checks again. The loop guard holds back every move, an
            // escape too: a network that keeps flapping must not turn into a reload loop.
            NetworkRoamWatcher.logLine("Nightlight roam: would move to \(which) (\(kind), \(why)), held back by the reload limit")
            return
        }
        DispatchQueue.main.async {
            self.finishRoam(gen, saved: saved, tailscale: tailscale, current: current, move: move, which: which, kind: kind, why: why)
        }
    }

    // ---- On the main thread ----

    // The last step of a roam. Everything is asked again HERE, on the main thread, immediately before the rebuild: the app may
    // have gone to the background or been rebuilt (the generation), and the page may have moved while the probes ran (only
    // switch away from the page the decision was about).
    //
    // One difference from Android, stated: Android also reads Picture-in-Picture again here. On iOS that state can only be read
    // through the page (evaluateJavaScript, which answers later, on a later turn of the main thread), and a rebuild from inside
    // WebKit's own callback would tear down the web view that is calling back; so it is read once, on the watcher's queue, just
    // before the decision. For an upgrade (the only move Picture-in-Picture holds back) nothing slow runs between that read and
    // this step (the second look runs before it), so the gap is one hop to the main thread.
    private func finishRoam(_ gen: Int, saved: String, tailscale: String, current: String, move: RoamMove, which: String, kind: String, why: String) {
        if !isCurrent(gen) { return }
        let window: UIWindow? = NetworkRoamWatcher.appWindow()
        let shown: String? = NetworkRoamWatcher.webView(in: window)?.url?.absoluteString
        if RoamRules.currentAddress(webViewUrl: shown, saved: saved, tailscale: tailscale) != current { return }
        // An UPGRADE waits for the app to be active; an ESCAPE does not, in either direction (RoamRules.heldBackAtLastStep says
        // why, and ios/Tests pins it). A held upgrade is not retried: the next start checks again.
        let appActive: Bool = (UIApplication.shared.applicationState == .active)
        if RoamRules.heldBackAtLastStep(move: move, appActive: appActive) {
            NetworkRoamWatcher.logLine("Nightlight roam: would move to \(which) (\(kind), \(why)), held back because the app is not active")
            return
        }
        // Asked again where it is noted, so the check and the note are one step. It applies to every move, escapes included.
        let now: Int64 = NetworkRoamWatcher.clockMs()
        if !roamLog.mayRoam(nowMs: now) { return }
        roamLog.noteRoam(nowMs: now)
        NetworkRoamWatcher.logLine("Nightlight roam: moving to \(which) (\(kind), \(why))")
        // The rebuild re-runs the launch choice (MainViewController.instanceDescriptor), which makes the same choice and points
        // the bridge at it, holding the main thread for up to 3 s as at launch. The saved address is never rewritten: only the
        // address the bridge opens differs.
        AppRestart.rebuildRoot(window: window)
    }

    // The app's window. Main thread only. The watcher is not given a view controller (it outlives every rebuild), so it asks the
    // app delegate, which owns the one window this app has (no scenes: Info.plist declares none).
    private static func appWindow() -> UIWindow? {
        let delegate: AppDelegate? = UIApplication.shared.delegate as? AppDelegate
        return delegate?.window
    }

    // The web view of the bridge in `window`, or nil. Main thread only.
    private static func webView(in window: UIWindow?) -> WKWebView? {
        let root: CAPBridgeViewController? = window?.rootViewController as? CAPBridgeViewController
        return root?.bridge?.webView
    }

    // ---- Main-thread reads from the queue (async + a time limit; see THE RULE in the header) ----

    // The URL of the page the web view is really showing, or nil (no web view, nothing loaded, or no answer in time).
    private func readShownPage() -> String? {
        let answer = RoamReadAnswer<String>()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            let webView: WKWebView? = NetworkRoamWatcher.webView(in: NetworkRoamWatcher.appWindow())
            answer.set(webView?.url?.absoluteString)
            done.signal()
        }
        if done.wait(timeout: .now() + NetworkRoamWatcher.mainThreadTimeout) == .timedOut {
            return nil
        }
        return answer.get()
    }

    // Whether a video floats in Picture-in-Picture, or nil when not known (no web view, a script error, an answer that is not a
    // boolean, or no answer in time). evaluateJavaScript is asynchronous and calls back on the main thread; the wait here is on
    // this queue, never on the main thread.
    private func readInPictureInPicture() -> Bool? {
        let answer = RoamReadAnswer<Bool>()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            guard let webView = NetworkRoamWatcher.webView(in: NetworkRoamWatcher.appWindow()) else {
                done.signal()
                return
            }
            webView.evaluateJavaScript(NetworkRoamWatcher.pipScript) { (result: Any?, error: Error?) in
                if error == nil, let value = result as? Bool {
                    answer.set(value)
                }
                done.signal()
            }
        }
        if done.wait(timeout: .now() + NetworkRoamWatcher.mainThreadTimeout) == .timedOut {
            return nil
        }
        return answer.get()
    }

    // ---- Helpers ----

    // What a path update is compared by (NetworkSignature says why each part is there). The interface types are recorded both
    // as used by the path and as available to it: with a VPN such as Tailscale on, the path itself may run over the VPN's own
    // interface, so Wi-Fi giving way to mobile data may show only in what is available. How an
    // iPhone fills these in with Tailscale on is unconfirmed on a phone; a signature that changes more often than needed costs
    // one check each (the check decides whether anything moves, and RecreateLog bounds the reloads). The gateways are kept in
    // memory only and never logged.
    private static func signature(of path: NWPath) -> NetworkSignature {
        var status: String = "unsatisfied"
        if path.status == .satisfied {
            status = "satisfied"
        } else if path.status == .requiresConnection {
            status = "requiresConnection"
        }
        var types = Set<String>()
        for kind in NetworkRoamWatcher.interfaceKinds {
            if path.usesInterfaceType(kind) {
                types.insert("uses:" + NetworkRoamWatcher.kindName(kind))
            }
        }
        for available in path.availableInterfaces {
            types.insert("has:" + NetworkRoamWatcher.kindName(available.type))
        }
        var gateways = Set<String>()
        for gateway in path.gateways {
            gateways.insert(String(describing: gateway))
        }
        return NetworkSignature(status: status, interfaceTypes: types, gateways: gateways)
    }

    private static func kindName(_ kind: NWInterface.InterfaceType) -> String {
        if kind == .wifi { return "wifi" }
        if kind == .cellular { return "cellular" }
        if kind == .wiredEthernet { return "wiredEthernet" }
        if kind == .loopback { return "loopback" }
        return "other"
    }

    // The clock RecreateLog is read with: seconds since the phone started, as milliseconds. Monotonic, so a clock set back
    // cannot hold the roams off, or set forward let them repeat (Android's reason for elapsedRealtime). Unlike Android's clock
    // it pauses while the phone sleeps, which can only make the limit stricter, and roams happen only while the app is in the
    // foreground anyway.
    private static func clockMs() -> Int64 {
        return Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    // One line in the device log. Never an address and never a token.
    private static func logLine(_ text: String) {
        NSLog("%@", text)
    }
}

/// The rebuild that makes the app open its server again: the iOS equivalent of Android recreating the activity. ONE function,
/// used by ServerConfig.restart() (Connect, Change server) and by NetworkRoamWatcher (a roam), so the two cannot drift.
enum AppRestart {

    /// Replaces the root view controller with a new one from the storyboard, which re-runs the bridge's server choice
    /// (MainViewController.instanceDescriptor, which may wait up to 3 s for the server's addresses, as at launch). Main thread
    /// only. Does nothing without a window. Tells the network watcher first, so a check in flight cannot act on the old page.
    static func rebuildRoot(window: UIWindow?) {
        guard let target = window else { return }
        NetworkRoamWatcher.shared.rebuildStarted()
        // A "Use this secure address?" prompt is presented by the root about to be replaced: withdraw it first, as not answered
        // (refused, nothing stored), so the web app's call is not left waiting (addendum review of 2026-11-09).
        AddressPrompt.shared.cancelPending()
        let storyboard = UIStoryboard(name: "Main", bundle: nil)
        target.rootViewController = storyboard.instantiateInitialViewController()
    }
}

/// One answer read on the main thread and waited for on the watcher's queue: written on one, read on the other, so it is
/// locked. A read that lands after the wait gave up writes into a box nobody reads any more, which is harmless.
private final class RoamReadAnswer<T> {
    private let lock = NSLock()
    private var value: T? = nil

    func set(_ newValue: T?) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func get() -> T? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
