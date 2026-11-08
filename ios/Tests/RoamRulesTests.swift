import Foundation

// RoamRulesTests.swift: the iOS twin of android/app/src/test/java/com/sauso/nightlight/RoamRulesTest.kt, case for case: when the
// app moves to the server's other address by itself (RoamRules, RecreateLog, NetworkEventFilter). Each rule has its own test, so a
// dropped or flipped rule fails the test named for it.
//
// Android cases DROPPED ON PURPOSE, because iOS has no reload after a main-frame load error (RecreateLog's header says why): "the
// first load-error recreate is allowed", "a load-error recreate waits 8 s after a load-error recreate / after a roam", "a roam
// waits 20 s after a load-error recreate", the 8 s half of "the 8 s and 20 s gaps are met at exactly the gap", and "load-error
// recreates do not count towards the roam limit". Android's NetworkEventFilter cases are ADAPTED: an iOS path update carries the
// whole state at once, so onAvailable / onLost / onTransports become one onUpdate over a signature (status, interface types,
// gateways), and the same situations are tested over it (the echo, a change, the same state again, no network at the start, a
// path that stops being satisfied, a change of Wi-Fi network). Added for iOS: the bundled `capacitor://` pages, a change of
// gateway alone, a change of status alone, the sets ignoring order, and a new filter (a new monitor) starting with its own echo.
//
// Not reachable here (glue, iPhone checks only): the path monitor and how an iPhone fills in a path's interfaces and gateways,
// the generation that makes a late check dead on arrival, the main-thread reads of the page URL and Picture-in-Picture, the
// settle and retry timing on the watcher's queue, the lifecycle calls, and the rebuild itself (NetworkRoamWatcher.swift).

private enum RoamFixture {
    static let saved = "http://192.168.1.50:4000"
    static let tailscale = "https://nightlight.example.ts.net"
}

// Three roams 30 s apart, each allowed when it is made.
private func logWithThreeRoams() -> RecreateLog {
    let roams = RecreateLog()
    let times: [Int64] = [0, 30_000, 60_000]
    for t in times {
        check(roams.mayRoam(nowMs: t), "the roam at \(t) ms was refused")
        roams.noteRoam(nowMs: t)
    }
    return roams
}

private func roamSignature(_ status: String, _ types: Set<String>, _ gateways: Set<String>) -> NetworkSignature {
    return NetworkSignature(status: status, interfaceTypes: types, gateways: gateways)
}

func runRoamRulesTests() {
    let saved = RoamFixture.saved
    let tailscale = RoamFixture.tailscale
    let start = RoamTrigger.foregroundStart
    let change = RoamTrigger.networkChange

    // ---- currentAddress: which address the page on screen is on ----

    test("roam: a page on the saved address is the saved address") {
        checkEqual(RoamRules.currentAddress(webViewUrl: "http://192.168.1.50:4000", saved: saved, tailscale: tailscale), saved)
    }

    test("roam: a page on the tailscale address is the tailscale address") {
        checkEqual(RoamRules.currentAddress(webViewUrl: "https://nightlight.example.ts.net", saved: saved, tailscale: tailscale), tailscale)
    }

    test("roam: a route, query or hash on the page does not matter") {
        checkEqual(RoamRules.currentAddress(webViewUrl: "http://192.168.1.50:4000/#/settings", saved: saved, tailscale: tailscale), saved)
        checkEqual(RoamRules.currentAddress(webViewUrl: "https://nightlight.example.ts.net/index.html?x=1#/live", saved: saved, tailscale: tailscale), tailscale)
    }

    test("roam: another port on the same host is neither address") {
        check(RoamRules.currentAddress(webViewUrl: "http://192.168.1.50:4001/", saved: saved, tailscale: tailscale) == nil, "another port on the saved host")
        check(RoamRules.currentAddress(webViewUrl: "https://nightlight.example.ts.net:8443/", saved: saved, tailscale: tailscale) == nil, "another port on the Tailscale host")
    }

    test("roam: another scheme on the same host is neither address") {
        check(RoamRules.currentAddress(webViewUrl: "https://192.168.1.50:4000/", saved: saved, tailscale: tailscale) == nil, "https on the plain-http saved address")
    }

    test("roam: the bundled setup and error pages are neither address") {
        // capacitor://localhost is where iOS serves the bundled pages; the others are Android's cases, kept.
        check(RoamRules.currentAddress(webViewUrl: "capacitor://localhost/", saved: saved, tailscale: tailscale) == nil, "the bundled setup page")
        check(RoamRules.currentAddress(webViewUrl: "capacitor://localhost/error.html", saved: saved, tailscale: tailscale) == nil, "the bundled error page")
        check(RoamRules.currentAddress(webViewUrl: "https://localhost/", saved: saved, tailscale: tailscale) == nil, "https://localhost")
        check(RoamRules.currentAddress(webViewUrl: "https://localhost/error.html", saved: saved, tailscale: tailscale) == nil, "https://localhost/error.html")
        check(RoamRules.currentAddress(webViewUrl: "http://localhost/", saved: saved, tailscale: tailscale) == nil, "http://localhost")
        check(RoamRules.currentAddress(webViewUrl: "about:blank", saved: saved, tailscale: tailscale) == nil, "about:blank")
    }

    test("roam: no page url is neither address") {
        check(RoamRules.currentAddress(webViewUrl: nil, saved: saved, tailscale: tailscale) == nil, "nil")
    }

    // ---- target: whether to switch, and in which direction ----

    test("roam: fail-over happens on a network change") {
        checkEqual(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: saved, chosen: tailscale, inPip: false), tailscale)
    }

    test("roam: fail-over happens at a foreground start") {
        checkEqual(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: saved, chosen: tailscale, inPip: false), tailscale)
    }

    test("roam: fail-over happens in picture-in-picture too") {
        // The page on screen is already dead: the PiP restriction is for fail-back only.
        checkEqual(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: saved, chosen: tailscale, inPip: true), tailscale)
    }

    test("roam: fail-back happens at a foreground start") {
        checkEqual(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: tailscale, chosen: saved, inPip: false), saved)
    }

    test("roam: fail-back does not happen on a network change") {
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: tailscale, chosen: saved, inPip: false) == nil, "moved back home on a network change")
    }

    test("roam: fail-back does not happen in picture-in-picture") {
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: tailscale, chosen: saved, inPip: true) == nil, "moved back home under a floating video")
    }

    test("roam: nothing happens without a tailscale address") {
        // Implied by the direction rules as well (with no Tailscale address there is nothing to fail over to): this pins the
        // behaviour, and a mutant that drops only the explicit nil check is equivalent (as on Android).
        check(RoamRules.target(trigger: change, saved: saved, tailscale: nil, current: saved, chosen: tailscale, inPip: false) == nil, "switched with no Tailscale address")
    }

    test("roam: nothing happens when the page is on neither address") {
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: nil, chosen: tailscale, inPip: false) == nil, "fail-over from no page")
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: nil, chosen: saved, inPip: false) == nil, "fail-back from no page")
    }

    test("roam: nothing happens when nothing answered") {
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: saved, chosen: nil, inPip: false) == nil, "on the saved address")
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: tailscale, chosen: nil, inPip: false) == nil, "on the Tailscale address")
    }

    test("roam: nothing happens when the choice is the address already on screen") {
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: saved, chosen: saved, inPip: false) == nil, "saved stays saved")
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: tailscale, chosen: tailscale, inPip: false) == nil, "Tailscale stays Tailscale")
    }

    test("roam: a saved address that is its own tailscale address never switches to itself") {
        // The one case where only the same-address check stops a "fail-over" (saved == tailscale == current == chosen).
        check(RoamRules.target(trigger: change, saved: tailscale, tailscale: tailscale, current: tailscale, chosen: tailscale, inPip: false) == nil, "switched to itself")
    }

    test("roam: an unrelated page address never switches") {
        let other = "https://other.example"
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: other, chosen: tailscale, inPip: false) == nil, "fail-over from another page")
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: other, chosen: saved, inPip: false) == nil, "fail-back from another page")
    }

    test("roam: an unrelated chosen address is never switched to") {
        let other = "https://other.example"
        check(RoamRules.target(trigger: change, saved: saved, tailscale: tailscale, current: saved, chosen: other, inPip: false) == nil, "from the saved address")
        check(RoamRules.target(trigger: start, saved: saved, tailscale: tailscale, current: tailscale, chosen: other, inPip: false) == nil, "from the Tailscale address")
    }

    // ---- RecreateLog: the loop guard ----

    test("roam: the first roam is allowed, even soon after the phone started") {
        // The clock counts from the phone's start: "no roam yet" must not be read as "a roam at time zero".
        check(RecreateLog().mayRoam(nowMs: 1_000), "the first roam was refused")
    }

    test("roam: a roam waits 20 s after a roam") {
        let roams = RecreateLog()
        roams.noteRoam(nowMs: 100_000)
        check(!roams.mayRoam(nowMs: 119_900), "allowed 19.9 s after a roam")
        check(roams.mayRoam(nowMs: 120_100), "refused 20.1 s after a roam")
    }

    // The exact boundary (Codex review of the Android code: 19.9/20.1 s did not tell ">=" from ">"). "At least" the gap is allowed.
    test("roam: the 20 s gap is met at exactly the gap, not one millisecond after") {
        let roams = RecreateLog()
        roams.noteRoam(nowMs: 100_000)
        check(!roams.mayRoam(nowMs: 119_999), "allowed 1 ms before the gap")
        check(roams.mayRoam(nowMs: 120_000), "refused at exactly the gap")
    }

    test("roam: three roams are allowed and the fourth is refused") {
        // 30 s after the third: the 20 s gap has passed, so only the count refuses it.
        check(!logWithThreeRoams().mayRoam(nowMs: 90_000), "a fourth roam inside five minutes was allowed")
    }

    test("roam: the fourth roam is allowed once the oldest is out of the window") {
        // The window rolls: roaming comes back by itself, it does not stay off until the app restarts. At 320 s only the roam
        // at 0 is older than five minutes; the other two still count, and two is under the limit.
        let roams = logWithThreeRoams()
        check(!roams.mayRoam(nowMs: 90_000), "allowed at 90 s")
        check(roams.mayRoam(nowMs: 320_000), "still refused at 320 s")
    }

    test("roam: a roam counts until exactly five minutes have passed") {
        // Literal times, so the five minutes the README states are what is pinned.
        let roams = logWithThreeRoams()
        check(!roams.mayRoam(nowMs: 299_999), "the roam at 0 stopped counting before five minutes")
        check(roams.mayRoam(nowMs: 300_000), "the roam at 0 still counted at exactly five minutes")
    }

    // ---- retryDelayAfter: the bounded retry when nothing answered ----

    test("roam: the retries come at about 3, 6, 12 and 25 s and then stop") {
        var offsets: [Int] = []
        var total = 0
        var attempt = 0
        while let wait = RoamRules.retryDelayAfter(attempt: attempt) {
            total += wait
            offsets.append(total)
            attempt += 1
            if attempt >= 100 {
                check(false, "the retries never stopped")
                break
            }
        }
        checkEqual(offsets, [3_000, 6_000, 12_000, 25_000])
    }

    test("roam: each wait is the gap to the next offset") {
        checkEqual(RoamRules.retryDelayAfter(attempt: 0), 3_000)
        checkEqual(RoamRules.retryDelayAfter(attempt: 1), 3_000)
        checkEqual(RoamRules.retryDelayAfter(attempt: 2), 6_000)
        checkEqual(RoamRules.retryDelayAfter(attempt: 3), 13_000)
    }

    test("roam: there is no retry after the last one, or before the first") {
        check(RoamRules.retryDelayAfter(attempt: 4) == nil, "a fifth retry")
        check(RoamRules.retryDelayAfter(attempt: -1) == nil, "a retry before the first")
    }

    // ---- NetworkEventFilter: which path updates are a real change ----

    let wifiHome = roamSignature("satisfied", ["uses:wifi", "has:wifi", "has:cellular"], ["192.168.1.1"])
    let wifiElsewhere = roamSignature("satisfied", ["uses:wifi", "has:wifi", "has:cellular"], ["10.0.0.1"])
    let cellular = roamSignature("satisfied", ["uses:cellular", "has:cellular"], [])
    let noNetwork = roamSignature("unsatisfied", [], [])

    test("roam: the first update is the echo of the current path, and that is not a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        check(!filter.onUpdate(wifiHome), "the echo counted as a change")
    }

    test("roam: a different path after that is a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        check(filter.onUpdate(cellular), "Wi-Fi to mobile data was not a change")
    }

    test("roam: the same path again is not a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        check(!filter.onUpdate(wifiHome), "the same path counted as a change")
    }

    test("roam: a change counts once") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        check(filter.onUpdate(cellular), "the change was missed")
        check(!filter.onUpdate(cellular), "the same new path counted again")
    }

    test("roam: going back to the first path is a change too") {
        // Android: "a new network after that is a change, even the same one again".
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        _ = filter.onUpdate(cellular)
        check(filter.onUpdate(wifiHome), "coming back to the first path was not a change")
    }

    test("roam: with no network at the start the first network to arrive is a change") {
        // Airplane mode when the app came to the front: the echo is the unsatisfied path, so the first network differs from it.
        var filter = NetworkEventFilter<NetworkSignature>()
        check(!filter.onUpdate(noNetwork), "the unsatisfied echo counted as a change")
        check(filter.onUpdate(cellular), "the first network to arrive was not a change")
    }

    test("roam: a path that stops being satisfied is a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        check(filter.onUpdate(noNetwork), "losing the network was not a change")
    }

    test("roam: the status changing alone is a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        let sameButNotSatisfied = roamSignature("requiresConnection", ["uses:wifi", "has:wifi", "has:cellular"], ["192.168.1.1"])
        check(filter.onUpdate(sameButNotSatisfied), "a path that stopped being satisfied, with the same interfaces, was not a change")
    }

    test("roam: Wi-Fi to another Wi-Fi (the same interface types, another gateway) is a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(wifiHome)
        check(filter.onUpdate(wifiElsewhere), "another Wi-Fi network was not a change")
    }

    test("roam: the same interfaces and gateways listed in another order are not a change") {
        var filter = NetworkEventFilter<NetworkSignature>()
        _ = filter.onUpdate(roamSignature("satisfied", ["uses:wifi", "has:wifi", "has:cellular"], ["192.168.1.1", "fe80::1"]))
        check(!filter.onUpdate(roamSignature("satisfied", ["has:cellular", "has:wifi", "uses:wifi"], ["fe80::1", "192.168.1.1"])), "the order counted as a change")
    }

    test("roam: a new filter starts with its own echo") {
        // A new monitor (after each return from the background) gets a new filter: its first update is its own echo, whatever
        // the old one saw last.
        var first = NetworkEventFilter<NetworkSignature>()
        _ = first.onUpdate(wifiHome)
        var second = NetworkEventFilter<NetworkSignature>()
        check(!second.onUpdate(cellular), "a new filter's first update counted as a change")
    }
}
