import Foundation

// SessionRulesTests.swift: the iOS twin of android/app/src/test/java/com/sauso/nightlight/SessionRulesTest.kt, case for case:
// who may be handed the saved sign-in. Each refusal below is one condition of SessionRules.checkDelivery broken on its own
// against a case that is accepted, so a rule dropped from the function fails exactly one test. The public server id proves
// nothing, which is why the page and the bindings matter as much as the id.
//
// Added for iOS: the prefix-lookalike pages Capacitor iOS lets the web view open (review of PR #32), a userinfo page, and
// SessionRules.stampOnQueue (the re-check saveSession makes on the writes queue: Codex plan review of iOS phase 2, 2026-11-08).
// The origin cases are run against EndpointRules.sameOrigin, the one origin parser the session rules use.

private typealias Delivery = SessionRules.Delivery
private typealias Bindings = SessionRules.Bindings
private typealias Entry = SessionRules.Entry

private enum RulesFixture {
    static let saved = "http://192.168.1.50:4000"
    static let ts = "https://nightlight.example.ts.net"
    static let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    static let otherId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    static let token = "aaa.bbb.ccc"
    static let current = SessionRules.Bindings(savedUrl: RulesFixture.saved, tailscaleUrl: RulesFixture.ts, tailscaleId: RulesFixture.id)
    static let entry = SessionRules.Entry(token: RulesFixture.token, bindings: RulesFixture.current)
    static let savedOnly = SessionRules.Bindings(savedUrl: RulesFixture.saved, tailscaleUrl: nil, tailscaleId: nil)
}

// Android's `delivery(page = ts, now = current, stored = entry, liveId = id)`: the accepted case, with one field changed per test.
private func delivery(page: String? = RulesFixture.ts, now: SessionRules.Bindings? = RulesFixture.current,
                      stored: SessionRules.Entry? = RulesFixture.entry, liveId: String? = RulesFixture.id) -> SessionRules.Delivery {
    return SessionRules.checkDelivery(page: page, current: now, entry: stored, liveId: liveId)
}

func runSessionRulesTests() {
    let saved = RulesFixture.saved
    let ts = RulesFixture.ts
    let id = RulesFixture.id
    let otherId = RulesFixture.otherId
    let token = RulesFixture.token
    let current = RulesFixture.current
    let savedOnly = RulesFixture.savedOnly

    // ---- delivery ----

    test("session: the one good case is delivered") {
        checkEqual(delivery(), Delivery.ok)
    }

    test("session: the saved address is never handed a session, even plain http or https") {
        checkEqual(delivery(page: saved), Delivery.notTheTailscalePage)
        let httpsSaved = "https://nightlight.example.com"
        let b = Bindings(savedUrl: httpsSaved, tailscaleUrl: ts, tailscaleId: id)
        checkEqual(delivery(page: httpsSaved, now: b, stored: Entry(token: token, bindings: b)), Delivery.notTheTailscalePage,
                   "an https saved address could have changed hands")
    }

    test("session: any other page is refused") {
        checkEqual(delivery(page: "https://other.example.ts.net"), Delivery.notTheTailscalePage)
        checkEqual(delivery(page: "https://evil.example"), Delivery.notTheTailscalePage)
        checkEqual(delivery(page: nil), Delivery.notActivePage)
        checkEqual(delivery(now: nil), Delivery.notActivePage)
    }

    test("session: a Tailscale address that is not of the accepted shape is refused even if it is the saved one") {
        let odd = "http://nightlight.example.ts.net"
        let b = Bindings(savedUrl: saved, tailscaleUrl: odd, tailscaleId: id)
        checkEqual(delivery(page: odd, now: b, stored: Entry(token: token, bindings: b)), Delivery.notTheTailscalePage)
    }

    test("session: no Tailscale address saved means nothing is handed out") {
        checkEqual(delivery(now: savedOnly, stored: Entry(token: token, bindings: savedOnly)), Delivery.notTheTailscalePage)
    }

    test("session: no stored entry is no session") {
        checkEqual(delivery(stored: nil), Delivery.noEntry)
    }

    test("session: an entry saved before the Tailscale address was learned is never handed out") {
        checkEqual(delivery(stored: Entry(token: token, bindings: savedOnly)), Delivery.noTailscaleBinding)
        checkEqual(delivery(stored: Entry(token: token, bindings: Bindings(savedUrl: saved, tailscaleUrl: ts, tailscaleId: nil))), Delivery.noTailscaleBinding)
    }

    test("session: no saved id for the Tailscale address now means nothing is handed out") {
        let noId = Bindings(savedUrl: saved, tailscaleUrl: ts, tailscaleId: nil)
        checkEqual(delivery(now: noId), Delivery.noTailscaleBinding)
    }

    test("session: changing the saved address, the Tailscale address or its id after the token was saved makes the entry fail closed") {
        // The first is also the hostile "entry stamped for another saved address" case: it is never handed to this one.
        checkEqual(delivery(stored: Entry(token: token, bindings: Bindings(savedUrl: "http://192.168.1.99:4000", tailscaleUrl: ts, tailscaleId: id))),
                   Delivery.bindingsChanged, "another saved address")
        checkEqual(delivery(stored: Entry(token: token, bindings: Bindings(savedUrl: saved, tailscaleUrl: "https://other.example.ts.net", tailscaleId: id))),
                   Delivery.bindingsChanged, "another Tailscale address")
        checkEqual(delivery(stored: Entry(token: token, bindings: Bindings(savedUrl: saved, tailscaleUrl: ts, tailscaleId: otherId))),
                   Delivery.bindingsChanged, "another Tailscale id")
    }

    test("session: the page's own server must still report the saved id") {
        checkEqual(delivery(liveId: otherId), Delivery.idMismatch)
        checkEqual(delivery(liveId: nil), Delivery.idMismatch, "an unreadable health reply is a refusal, not a pass")
    }

    // ---- who may act: the bridge's server AND the page really shown ----

    test("session: a session may be saved or asked for from the saved address and from its Tailscale address, shown as built") {
        check(SessionRules.mayAct(savedOnly, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:4000/#/live"), "the saved address, nothing else saved")
        check(SessionRules.mayAct(current, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:4000/"), "the saved address")
        check(SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/#/settings"), "the saved Tailscale address")
    }

    test("session: the Tailscale address is only a page once it is saved, and any other bridge server is refused") {
        check(!SessionRules.mayAct(savedOnly, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "a Tailscale page with none saved")
        check(!SessionRules.mayAct(current, bridgeUrl: "https://evil.example", webViewUrl: "https://evil.example/"), "another server")
        check(!SessionRules.mayAct(current, bridgeUrl: nil, webViewUrl: "http://192.168.1.50:4000/"), "no bridge server")
        check(!SessionRules.mayAct(nil, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:4000/"), "nothing saved")
    }

    test("session: the page really shown must have the origin the bridge was built for (a frame or another port is refused)") {
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net:8443/"), "another port of the same host")
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: "https://evil.example/"), "another host")
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: "http://nightlight.example.ts.net/"), "http for https")
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: nil), "the page could not be read")
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: "about:blank"), "about:blank")
        check(!SessionRules.isTailscalePage(current, bridgeUrl: ts, webViewUrl: "https://evil.example/"), "isTailscalePage, another host")
        check(!SessionRules.mayClear(current, bridgeUrl: ts, webViewUrl: "https://evil.example/"), "mayClear, another host")
        check(!SessionRules.mayClear(current, bridgeUrl: saved, webViewUrl: nil), "mayClear, the page could not be read")
    }

    test("session: a page that merely STARTS WITH the bridge's address is another origin (Capacitor iOS prefix navigation, PR #32)") {
        let lookalike = "https://nightlight.example.ts.net.attacker.test/"
        check(!SessionRules.mayAct(current, bridgeUrl: ts, webViewUrl: lookalike), "mayAct, a longer host")
        check(!SessionRules.isTailscalePage(current, bridgeUrl: ts, webViewUrl: lookalike), "isTailscalePage, a longer host")
        check(!SessionRules.mayClear(current, bridgeUrl: ts, webViewUrl: lookalike), "mayClear, a longer host")
        check(!SessionRules.mayAct(current, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:40001/"), "mayAct, a longer port")
        check(!SessionRules.mayClear(current, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:40001/"), "mayClear, a longer port")
        // A userinfo form: the host of `https://server.example@attacker.test` is attacker.test, not server.example.
        let httpsHome = "https://server.example"
        let b = Bindings(savedUrl: httpsHome, tailscaleUrl: nil, tailscaleId: nil)
        check(SessionRules.mayAct(b, bridgeUrl: httpsHome, webViewUrl: "https://server.example/#/live"), "the control: the real page is accepted")
        check(!SessionRules.mayAct(b, bridgeUrl: httpsHome, webViewUrl: "https://server.example@attacker.test/"), "mayAct, a userinfo page")
        check(!SessionRules.mayClear(b, bridgeUrl: httpsHome, webViewUrl: "https://server.example@attacker.test/"), "mayClear, a userinfo page")
    }

    test("session: only the page that IS the saved Tailscale address, really shown, is asked about a session") {
        check(SessionRules.isTailscalePage(current, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/#/live"), "the saved Tailscale address")
        check(!SessionRules.isTailscalePage(current, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:4000/"), "the saved address, http")
        check(!SessionRules.isTailscalePage(savedOnly, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "no Tailscale address saved")
        check(!SessionRules.isTailscalePage(current, bridgeUrl: "https://other.example.ts.net", webViewUrl: "https://other.example.ts.net/"), "a different Tailscale name")
        check(!SessionRules.isTailscalePage(nil, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "nothing saved")
        check(!SessionRules.isTailscalePage(current, bridgeUrl: nil, webViewUrl: "https://nightlight.example.ts.net/"), "no bridge server")
        let odd = Bindings(savedUrl: saved, tailscaleUrl: "http://nightlight.example.ts.net", tailscaleId: id)
        check(!SessionRules.isTailscalePage(odd, bridgeUrl: "http://nightlight.example.ts.net", webViewUrl: "http://nightlight.example.ts.net/"), "not the accepted shape")
    }

    test("session: a clear works from any real server page, even after the Tailscale address was removed or replaced under it") {
        check(SessionRules.mayClear(current, bridgeUrl: saved, webViewUrl: "http://192.168.1.50:4000/"), "the saved address")
        check(SessionRules.mayClear(current, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "the Tailscale address")
        check(SessionRules.mayClear(savedOnly, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "the Tailscale address was removed while its page is open")
        // iOS's bundled setup page: the bridge is built with no server (pageServerURL is nil), shown at capacitor://localhost.
        check(!SessionRules.mayClear(current, bridgeUrl: nil, webViewUrl: "capacitor://localhost/"), "the bundled setup page has no server")
        check(!SessionRules.mayClear(nil, bridgeUrl: ts, webViewUrl: "https://nightlight.example.ts.net/"), "nothing saved")
    }

    test("session: only something shaped like a session token is saved") {
        check(SessionRules.isPlausibleToken("eyJhbGciOiJIUzI1NiJ9.eyJpZCI6IjEifQ.c2lnLW5hdHVyZS1fLQ"), "a real-looking JWT was refused")
        let bad: [String?] = [nil, "", "abc", "a.b", "a.b.c.d", "a.b. c", "a..c", "a b.c.d", "a.b.c\n", "token with spaces.x.y", "a.b.c/d",
                              // iOS additions: a line break NSRegularExpression's `$` would let through, and non-JWT strings.
                              "a.b.c\r\n", "not-a-token", "<script>.x.y", "a.b.c=", "a.b.\u{0661}"]
        for value in bad {
            check(!SessionRules.isPlausibleToken(value), "accepted: " + (value ?? "<nil>"))
        }
    }

    test("session: a token over 4 KB is refused and one of exactly 4 KB is not") {
        let part = String(repeating: "a", count: (SessionRules.maxTokenChars - 2) / 3)
        let last = String(repeating: "a", count: SessionRules.maxTokenChars - 2 - 2 * part.count)
        let atLimit = part + "." + part + "." + last
        checkEqual(atLimit.utf16.count, SessionRules.maxTokenChars, "fixture length")
        checkEqual(SessionRules.maxTokenChars, 4096, "Android's bound")
        check(SessionRules.isPlausibleToken(atLimit), "a 4096-character token was refused")
        check(!SessionRules.isPlausibleToken(atLimit + "a"), "a 4097-character token was accepted")
    }

    // ---- the page the web view is really showing (EndpointRules.sameOrigin, the one origin parser) ----

    test("session: the page and the bridge must share an origin, with default ports filled in") {
        check(EndpointRules.sameOrigin("https://nightlight.example.ts.net/#/live", ts), "a path and fragment")
        check(EndpointRules.sameOrigin("https://nightlight.example.ts.net:443/", ts), "https's default port written out")
        check(EndpointRules.sameOrigin("HTTPS://NIGHTLIGHT.example.ts.net/x?y=1", ts), "case")
        check(EndpointRules.sameOrigin("http://192.168.1.50:4000/index.html", saved), "a home address")
        check(EndpointRules.sameOrigin("http://192.168.1.50:80/", "http://192.168.1.50"), "http's default port written out")
    }

    test("session: another port, host or scheme is another origin, and anything unparseable is refused") {
        check(!EndpointRules.sameOrigin("http://192.168.1.50:4001/", saved), "another port of the same host")
        check(!EndpointRules.sameOrigin("https://nightlight.example.ts.net:8443/", ts), "another port")
        check(!EndpointRules.sameOrigin("https://evil.example/", ts), "another host")
        check(!EndpointRules.sameOrigin("http://nightlight.example.ts.net/", ts), "http for https")
        check(!EndpointRules.sameOrigin(nil, ts), "no page")
        check(!EndpointRules.sameOrigin(ts, nil), "no bridge")
        check(!EndpointRules.sameOrigin("about:blank", ts), "about:blank")
        check(!EndpointRules.sameOrigin("file:///x", "file:///x"), "a file")
        check(!EndpointRules.sameOrigin("not a url", "not a url"), "not a URL")
    }

    test("session: an address java.net.URI cannot read is still a real origin") {
        check(EndpointRules.sameOrigin("http://my_server:4000/", "http://my_server:4000"), "an underscore host")
        check(EndpointRules.sameOrigin("http://192.168.1.50:4000/#/a|b{c}", saved), "odd characters in the fragment")
        check(EndpointRules.sameOrigin("http://[fd00::1]:4000/", "http://[fd00::1]:4000"), "an IPv6 literal")
        check(!EndpointRules.sameOrigin("http://user@192.168.1.50:4000/", saved), "a userinfo form is not an origin we accept")
        check(!EndpointRules.sameOrigin("http://192.168.1.50:99999/", "http://192.168.1.50:99999"), "a port past 65535")
    }

    // ---- what a token is stamped with ----

    test("session: a claim equal to the saved Tailscale address keeps the binding") {
        checkEqual(SessionRules.stampFor(current, claimedTailscale: ts), current)
        checkEqual(SessionRules.stampFor(current, claimedTailscale: " " + ts + "/ "), current, "a trailing slash and spaces are the same address")
    }

    test("session: no claim, a different claim or no saved Tailscale address stamps no binding, so the entry is never handed out") {
        checkEqual(SessionRules.stampFor(current, claimedTailscale: nil), savedOnly, "an older web app sends no claim")
        checkEqual(SessionRules.stampFor(current, claimedTailscale: ""), savedOnly, "the server did not offer one (409, error)")
        // The hostile case: an address planted by a page at the saved address, which the real server never offers.
        checkEqual(SessionRules.stampFor(current, claimedTailscale: "https://real.example.ts.net"), savedOnly, "a planted address the real server never offers")
        checkEqual(SessionRules.stampFor(savedOnly, claimedTailscale: ts), savedOnly, "no Tailscale address saved")
        // ...and such an entry is refused at the gate, even when the planted address is now the saved one.
        checkEqual(delivery(stored: Entry(token: token, bindings: SessionRules.stampFor(current, claimedTailscale: nil))), Delivery.noTailscaleBinding)
    }

    // ---- saveSession's re-check on the writes queue (iOS only) ----

    test("session: the stamp is written only while the bindings are still the ones the page was checked against") {
        checkEqual(SessionRules.stampOnQueue(checked: current, now: current, claimedTailscale: ts), current, "unchanged: stamped as stampFor")
        checkEqual(SessionRules.stampOnQueue(checked: current, now: current, claimedTailscale: nil), savedOnly,
                   "unchanged, with no claim: stamped with no binding, as stampFor")
        checkEqual(SessionRules.stampOnQueue(checked: current, now: nil, claimedTailscale: ts), nil, "the server was left (Change server)")
        checkEqual(SessionRules.stampOnQueue(checked: current, now: Bindings(savedUrl: "http://192.168.1.99:4000", tailscaleUrl: ts, tailscaleId: id), claimedTailscale: ts), nil,
                   "another server was saved in between: its slot must not get this token")
        checkEqual(SessionRules.stampOnQueue(checked: savedOnly, now: current, claimedTailscale: ts), nil,
                   "the Tailscale address was saved in between: the page was checked against the old bindings")
        checkEqual(SessionRules.stampOnQueue(checked: current, now: Bindings(savedUrl: saved, tailscaleUrl: ts, tailscaleId: otherId), claimedTailscale: ts), nil,
                   "the Tailscale id changed in between")
    }
}
