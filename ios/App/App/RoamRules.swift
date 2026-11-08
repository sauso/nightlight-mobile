import Foundation

// RoamRules.swift: the rules for moving the app to the server's other address when the network changes (owner, 2026-11-06 on
// Android: walk out of the house and Wi-Fi turns to mobile data, and the page sat on a dead home address until the app was
// restarted; iOS phase 2, part 2, 2026-11-08).
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/RoamRules.kt (RoamRules, RecreateLog, NetworkEventFilter),
// ported rule for rule with Android's constants and their reasons. Do not redesign it here: a change to a rule belongs in both
// apps.
//
// The idea is deliberately small: when the network changes, the app runs the SAME choice the launch runs
// (MainViewController.chooseAnswer: the saved address first, the Tailscale one as the fallback), and if that picks a different
// address from the one on screen, the root view controller is rebuilt (AppRestart.rebuildRoot, the same rebuild
// ServerConfig.restart() does), which re-runs the launch choice. No new way of choosing. What is new is WHEN a switch may
// happen and how often, which is all here.
//
// Foundation only, no Network, UIKit or Capacitor import, on purpose: CI compiles this file with swiftc and runs ios/Tests
// against exactly this code (build-ios.yml). The glue that feeds it (the path monitor, the main-thread reads, the rebuild) is
// NetworkRoamWatcher.swift, which only an iPhone can test. Every rule fails CLOSED: when in doubt, nothing moves.

/// Why an address check runs. Only a foreground start may move the app back home (see RoamRules.target).
enum RoamTrigger {
    case networkChange
    case foregroundStart
}

enum RoamRules {

    // How long a network event waits before the check runs, and how long after the app comes to the front the start check
    // runs. Android's SETTLE_MS, carried over as a judgement, not a measurement: a new network and the Tailscale tunnel need a
    // moment to settle before a probe means anything, and every event inside the wait restarts it, so a flapping network costs
    // one check, not one per event.
    static let settleMs: Int = 2_000

    /// Which of the server's two addresses the page the web view is really showing (`webViewUrl`) belongs to: `saved`,
    /// `tailscale`, or nil for anything else (the bundled setup page, the bundled "Can't reach your server" page, another port
    /// or scheme). Compared by origin (EndpointRules.sameOrigin, phase 1's one origin parser), so a route or hash on the page
    /// does not matter.
    ///
    /// The caller passes the web view's own URL, NOT the address the bridge was built with: the bridge keeps its remote address
    /// while a bundled error page is on screen, and the app must never "switch" a page that is not on either address (Codex
    /// review of the Android plan). On those pages nothing happens by itself: they already offer retry and the saved servers.
    static func currentAddress(webViewUrl: String?, saved: String, tailscale: String) -> String? {
        if EndpointRules.sameOrigin(webViewUrl, saved) { return saved }
        if EndpointRules.sameOrigin(webViewUrl, tailscale) { return tailscale }
        return nil
    }

    /// The address to switch to, or nil to stay.
    ///  - `current` is currentAddress's answer for the page on screen; `chosen` is what the launch's choice picked just now
    ///    (nil when neither address answered).
    ///  - FAIL-OVER (the saved address is on screen and only Tailscale answers now) is allowed at once, for either trigger and
    ///    even in Picture-in-Picture: the page on screen is already dead, so a reload loses nothing that still works.
    ///  - FAIL-BACK (Tailscale is on screen and the saved address answers again) is allowed ONLY at a foreground start, and not
    ///    in Picture-in-Picture (owner, 2026-11-06, refined after the Codex plan review). Tailscale keeps working over Wi-Fi, so
    ///    there is no hurry, and a reload while someone is looking at the app would cut a form, an upload or a video. A start
    ///    (the app opened again, or the screen unlocked with the app in front) is the moment nothing is lost. Picture-in-Picture
    ///    IS a case on an iPhone: the web app starts it from inside the app and the person can go Home with the video floating
    ///    (Codex and Opus plan review of iOS phase 2, 2026-11-08).
    ///  - Anything else stays: nothing chosen, the same address, a page on neither address, no Tailscale address saved.
    /// (The nil checks below are also implied by the two direction rules; they are written out so the rule reads as stated.
    /// The same-address check is not: it is what stops a saved address that equals its own Tailscale address from "switching"
    /// to itself.)
    static func target(trigger: RoamTrigger, saved: String, tailscale: String?, current: String?, chosen: String?, inPip: Bool) -> String? {
        guard let tailscaleAddress = tailscale, let here = current, let picked = chosen else { return nil }
        if picked == here { return nil }
        if here == saved && picked == tailscaleAddress { return picked }
        if here == tailscaleAddress && picked == saved && trigger == .foregroundStart && !inPip { return picked }
        return nil
    }

    // When the check finds NOTHING answering (the tunnel is not routing yet, the new network has no internet yet), it runs again
    // at about these times after the first check, then stops until the next network event. Android's RETRY_OFFSETS_MS, carried
    // over with its reason: the system does not promise another path update when an address starts answering later (Codex
    // review of the Android plan), so without this a phone that switched to mobile data a little before Tailscale reconnected
    // would stay on the dead page. A judgement, not a measurement: roughly doubling, covering the first half minute, and then
    // stopping so a phone with no way to the server is not probed forever. Each wait is counted from the END of the previous
    // check (which itself takes a few seconds), so the real times are a little later than these.
    static let retryOffsetsMs: [Int] = [3_000, 6_000, 12_000, 25_000]

    /// How long to wait before the next check, when `attempt` retries have already run after the first check (0 = only the
    /// first check has run), or nil when there are no retries left.
    static func retryDelayAfter(attempt: Int) -> Int? {
        if attempt < 0 || attempt >= retryOffsetsMs.count { return nil }
        let previous: Int = (attempt == 0) ? 0 : retryOffsetsMs[attempt - 1]
        return retryOffsetsMs[attempt] - previous
    }
}

/// Every rebuild the app makes ON ITS OWN to move between the addresses, so that a flapping network cannot turn into a reload
/// loop. One instance lives for the whole process (NetworkRoamWatcher.shared owns it), so it SURVIVES the rebuild it records.
/// No static state here: the clock is passed in (milliseconds on a monotonic clock), so the tests drive it.
///
/// ONLY the roam half of Android's RecreateLog is ported. Android's other half (noteRecreate / mayErrorRecreate, the 8 s
/// debounce of the reload after a main-frame load error) is DROPPED on purpose: the iOS app has no such reload (a server that
/// stops answering is caught by Capacitor's errorPath page, which offers retry), so there is nothing for it to coordinate
/// with. As on Android, a rebuild the person asks for (Connect, Change server: ServerConfig.restart) is not recorded: the person
/// is in charge of those, and they cannot loop by themselves.
///
/// Thread-safe (one lock): the roam's first look runs on the watcher's queue, its final step on the main thread.
final class RecreateLog {

    // How long after a roam another roam may happen. Android's ROAM_GAP_MS, a judgement, not a measurement: several times one
    // launch's own work (up to 3 s of probing, the page loading, a check 2 s after a start), so a roam's reload, and anything
    // it set off, has finished before another roam may begin. Measured from the last ROAM only, since that is the only rebuild
    // this log records on iOS (see the class comment).
    static let roamGapMs: Int64 = 20_000

    // At most this many roams in any rolling window of this length. Android's ROAM_MAX and ROAM_WINDOW_MS, a judgement, not a
    // measurement: a real walk out of the house and back is one or two switches; more than three in five minutes means the
    // network itself is flapping (a weak Wi-Fi edge, airplane mode toggled), and each switch reloads the page and restarts a
    // video. The window ROLLS: a roam older than it no longer counts, so roaming comes back on its own instead of staying off
    // until the app restarts.
    static let roamMax: Int = 3
    static let roamWindowMs: Int64 = 5 * 60_000

    private let lock = NSLock()

    // nil until the first roam: "none yet" is not "at time zero". The clock the watcher uses counts from the phone's start, so
    // with a 0 here an app opened in the first 20 seconds after the phone started could not roam (Android's reason, kept).
    private var lastRoamAtMs: Int64? = nil
    private var roamsAtMs: [Int64] = []

    init() {}

    /// A roam happened at `nowMs`.
    func noteRoam(nowMs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        lastRoamAtMs = nowMs
        forgetOldRoams(nowMs)
        roamsAtMs.append(nowMs)
    }

    /// Whether a roam may happen now: at least `roamGapMs` since the last roam, and fewer than `roamMax` roams in the window.
    func mayRoam(nowMs: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        forgetOldRoams(nowMs)
        if let last = lastRoamAtMs {
            // "At least" the gap is allowed (Android's boundary, pinned by the exact-gap test).
            if nowMs - last < RecreateLog.roamGapMs { return false }
        }
        return roamsAtMs.count < RecreateLog.roamMax
    }

    // A roam counts while it is younger than the window; at exactly the window's length it no longer does. Called with the lock
    // held.
    private func forgetOldRoams(_ nowMs: Int64) {
        roamsAtMs.removeAll { nowMs - $0 >= RecreateLog.roamWindowMs }
    }
}

/// What NetworkRoamWatcher compares between two path updates: the path's status, the set of interface types it uses or has
/// available, and the set of its gateways (the glue builds it from an NWPath; here it is plain values, so the tests can make
/// one). Including the gateways is what makes Wi-Fi to ANOTHER Wi-Fi count (the same interface type, a different router), and
/// including the status is what makes a path that stops being satisfied count (Codex and Opus plan review of iOS phase 2,
/// 2026-11-08). Sets, so the order the system lists them in does not matter.
struct NetworkSignature: Equatable {
    let status: String
    let interfaceTypes: Set<String>
    let gateways: Set<String>
}

/// Which path updates are a real change. The iOS twin, in spirit, of Android's NetworkEventFilter: an NWPathMonitor reports the
/// CURRENT path at once after it starts, and that echo is not a change. Counted as one, it would turn every foreground-start
/// check into a network-change check and the app would never move back home. So:
///  - the FIRST update a filter sees is the echo: it is recorded, and is not a change;
///  - afterwards, a change is a signature different from the last one recorded (it is then recorded);
///  - the same signature again is not a change (the system also reports updates for things the signature leaves out).
/// An app that starts with no network (airplane mode) gets an unsatisfied path as its echo, so the first network to arrive IS a
/// change, because its signature differs: the case Android's `atRegistration == null` covers. (Android's onAvailable / onLost /
/// onTransports split has no iOS counterpart: one path update carries all of it.)
///
/// Generic over the signature so the tests need no Network framework. One filter per monitor (a new monitor starts with a new
/// filter, so its first update is its own echo). Used from one queue (the watcher's), so it needs no lock.
struct NetworkEventFilter<Signature: Equatable> {
    private var last: Signature? = nil

    init() {}

    /// True when `signature` is a real change from the last update this filter saw.
    mutating func onUpdate(_ signature: Signature) -> Bool {
        guard let previous = last else {
            last = signature
            return false
        }
        last = signature
        return previous != signature
    }
}
