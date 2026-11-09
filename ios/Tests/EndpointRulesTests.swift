import Foundation

// EndpointRulesTests.swift: the iOS twin of android/app/src/test/java/com/sauso/nightlight/EndpointRulesTest.kt, case for
// case: what may be saved as a server's Tailscale address (it arrives from the server the app has open, so the rule is strict:
// exactly `https://<name>.ts.net`; each refusal is a near miss of the accepted shape), which page may save it, the Change-server
// reconnect, the names, and the Tailscale grace (Tailscale first, 2026-11-09; it replaced the off-Wi-Fi wait and its tests).
// Added for iOS: the refusal sentences word for word (the web app's About card
// matches on them), the trailing-line-break case NSRegularExpression would otherwise let through, the address trimming
// addEndpoint does, and the IPv4 rules isLocalOnlyAddress needs (from DiscoveryRulesTest.kt).

func runEndpointRulesTests() {
    let home = "http://192.168.1.50:4000"
    let ts = "https://nightlight.example.ts.net"
    let other = "https://other.example.ts.net"
    let sameId = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    let otherId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    let known: [String] = [home, "https://nightlight.example.com"]

    test("a plain https ts-net name is accepted, with or without extra labels") {
        check(EndpointRules.isTailscaleUrl("https://nightlight-1.example-tailnet.ts.net"), "two labels")
        check(EndpointRules.isTailscaleUrl("https://nightlight.tail1234.ts.net"), "a tailnet name")
        check(EndpointRules.isTailscaleUrl("https://a.ts.net"), "one letter")
    }

    test("anything that is not exactly that shape is refused") {
        let refused: [String?] = [
            nil,
            "",
            "http://nightlight.example.ts.net", // not https
            "https://nightlight.example.ts.net:8443", // a port
            "https://nightlight.example.ts.net/", // a path (even the root one)
            "https://nightlight.example.ts.net/api",
            "https://nightlight.example.ts.net?x=1",
            "https://nightlight.example.ts.net#frag",
            "https://user@nightlight.example.ts.net", // credentials
            "https://nightlight.example.ts.net.evil.example", // a longer name that merely contains ts.net
            "https://nightlight.example.ts.net.", // trailing dot
            "https://nots.net", // ends in ts.net but not .ts.net
            "https://ts.net", // no name before it
            "https://.ts.net",
            "https://-a.example.ts.net", // a label may not start or end with a hyphen
            "https://a-.example.ts.net",
            "https://100.64.0.1", // an address, not a name
            "https://[fd7a:115c:a1e0::1]",
            "https://NIGHTLIGHT.example.ts.net", // the server sends lower case
            "https://nightlight .example.ts.net",
            "https://night_light.example.ts.net",
            "HTTPS://nightlight.example.ts.net",
            " https://nightlight.example.ts.net",
            // iOS only: NSRegularExpression's `$` matches before a final line break; the whole-match check must refuse it.
            "https://nightlight.example.ts.net\n",
            "https://nightlight.example.ts.net\r\n",
        ]
        for value in refused {
            check(!EndpointRules.isTailscaleUrl(value), "accepted: " + (value ?? "<nil>"))
        }
    }

    test("a name longer than 255 characters is refused") {
        let label = String(repeating: "a", count: 63)
        let tooLong = "https://\(label).\(label).\(label).\(label).ts.net" // 8 + 4 * 63 + 3 dots + 7 (".ts.net") = 270
        check(tooLong.utf16.count > 255, "the fixture is longer than the bound")
        check(!EndpointRules.isTailscaleUrl(tooLong), "a 270-character address was accepted")
        check(EndpointRules.isTailscaleUrl("https://\(label).\(label).ts.net"), "a 142-character address was refused")
        // The exact bound: 255 accepted, 256 refused (kills a `>=` for `>`). "https://" is 8 and ".ts.net" is 7, so
        // 8 + 63 + 1 + 63 + 1 + 63 + 1 + 48 + 7 = 255 (both lengths counted with node when the fixtures were written; the
        // checkEqual lines below fail first if either is ever off).
        let label48 = String(repeating: "b", count: 48)
        let exactly255 = "https://\(label).\(label).\(label).\(label48).ts.net"
        checkEqual(exactly255.utf16.count, 255, "fixture length")
        check(EndpointRules.isTailscaleUrl(exactly255), "a 255-character address was refused")
        let label49 = String(repeating: "b", count: 49)
        let exactly256 = "https://\(label).\(label).\(label).\(label49).ts.net"
        checkEqual(exactly256.utf16.count, 256, "fixture length")
        check(!EndpointRules.isTailscaleUrl(exactly256), "a 256-character address was accepted")
    }

    test("a label longer than 63 characters is refused") {
        check(!EndpointRules.isTailscaleUrl("https://" + String(repeating: "a", count: 64) + ".ts.net"), "64")
        check(EndpointRules.isTailscaleUrl("https://" + String(repeating: "a", count: 63) + ".ts.net"), "63")
    }

    test("each saved server has its own key, so one server's address is never read for another") {
        checkEqual(EndpointRules.key("http://192.168.1.50:4000"), "tailscale_for:http://192.168.1.50:4000")
        check(EndpointRules.key("http://192.168.1.50:4000") != EndpointRules.key("http://192.168.1.51:4000"), "two servers, one key")
        checkEqual(EndpointRules.idKey("http://192.168.1.50:4000"), "tailscale_id:http://192.168.1.50:4000")
        check(EndpointRules.key("x") != EndpointRules.idKey("x"), "the id and the address must not share a key")
    }

    // `shown` is the web view's current URL: on these pages it is the bridge's own address with a path, as a real page has.
    test("the page asking must be the saved server or its saved Tailscale address") {
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: home, shown: home + "/#/live"), EndpointRules.AddCheck.ok)
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: ts, page: ts, shown: ts + "/#/about"), EndpointRules.AddCheck.ok,
                   "the page is already on the saved Tailscale address")
        checkEqual(EndpointRules.checkPage(url: other, primary: home, savedTailscale: ts, page: home, shown: home + "/"), EndpointRules.AddCheck.ok,
                   "a changed address, asked from the saved one")
    }

    test("any other page is refused, including the bundled setup page which has no server") {
        let refused = EndpointRules.AddCheck.notThisPage
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: "https://elsewhere.example", shown: "https://elsewhere.example/"), refused, "another server")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: ts, page: other, shown: other + "/"), refused, "a Tailscale address that is not the saved one")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: nil, shown: "capacitor://localhost/"), refused, "the setup page (no server)")
        checkEqual(EndpointRules.checkPage(url: ts, primary: nil, savedTailscale: nil, page: home, shown: home + "/"), refused, "nothing saved")
        checkEqual(EndpointRules.checkPage(url: other, primary: home, savedTailscale: nil, page: ts, shown: ts + "/"), refused, "the page is the Tailscale address, but none is saved for it")
    }

    test("the right bridge is refused when the web view is showing another origin (prefix navigation, PR #32 review)") {
        let refused = EndpointRules.AddCheck.notThisPage
        // Capacitor iOS lets the web view open, in the app, any URL that STARTS WITH the bridge's server URL.
        checkEqual(EndpointRules.checkPage(url: ts, primary: "https://server.example", savedTailscale: nil, page: "https://server.example",
                                           shown: "https://server.example.attacker.test/"), refused, "a longer host that starts with the saved one")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: home, shown: "http://192.168.1.50:40001/"), refused,
                   "a longer port that starts with the saved one")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: ts, page: ts, shown: "https://nightlight.example.ts.net.attacker.test/"), refused,
                   "a longer host that starts with the saved Tailscale address")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: home, shown: nil), refused, "the page URL could not be read")
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: nil, page: home, shown: "about:blank"), refused, "nothing loaded")
        // The page shown must be the BRIDGE's origin, not merely one of the two addresses (Android's mayAct).
        checkEqual(EndpointRules.checkPage(url: ts, primary: home, savedTailscale: ts, page: home, shown: ts + "/"), refused,
                   "the bridge is the saved address but the web view shows the Tailscale one")
    }

    test("isTrustedPage is what removeEndpoint relies on: the bridge's page AND the shown origin") {
        check(EndpointRules.isTrustedPage(primary: home, savedTailscale: nil, page: home, shown: home + "/index.html"), "the saved address")
        check(EndpointRules.isTrustedPage(primary: home, savedTailscale: ts, page: ts, shown: "HTTPS://Nightlight.Example.ts.net:443/#/live"), "the saved Tailscale address")
        check(!EndpointRules.isTrustedPage(primary: home, savedTailscale: nil, page: home, shown: "http://192.168.1.50:4000.attacker.test/"), "a prefix host")
        check(!EndpointRules.isTrustedPage(primary: home, savedTailscale: nil, page: ts, shown: ts + "/"), "a Tailscale page with none saved")
        check(!EndpointRules.isTrustedPage(primary: nil, savedTailscale: nil, page: nil, shown: nil), "nothing at all")
    }

    test("an address of the wrong shape is refused before the page is even looked at") {
        checkEqual(EndpointRules.checkPage(url: "http://nightlight.example.ts.net", primary: home, savedTailscale: nil, page: home, shown: home + "/"), EndpointRules.AddCheck.notATailscaleAddress)
        checkEqual(EndpointRules.checkPage(url: nil, primary: home, savedTailscale: nil, page: home, shown: home + "/"), EndpointRules.AddCheck.notATailscaleAddress)
        // Both wrong: the shape is judged first, as on Android.
        checkEqual(EndpointRules.checkPage(url: "nonsense", primary: nil, savedTailscale: nil, page: nil, shown: nil), EndpointRules.AddCheck.notATailscaleAddress)
    }

    // ---- sameOrigin (the twin of SessionRulesTest.kt's origin cases) and isOnBundledOrigin ----

    test("sameOrigin: the same scheme, host and port, with case ignored, default ports filled in and paths ignored") {
        check(EndpointRules.sameOrigin("https://server.example", "https://server.example"), "the exact origin")
        check(EndpointRules.sameOrigin("https://server.example/", "https://server.example"), "a trailing slash")
        check(EndpointRules.sameOrigin("https://server.example/#/live?x=1", "https://server.example"), "a path, query and fragment")
        check(EndpointRules.sameOrigin("https://server.example:443/", "https://server.example"), "https's default port written out")
        check(EndpointRules.sameOrigin("http://192.168.1.50:80/", "http://192.168.1.50"), "http's default port written out")
        check(EndpointRules.sameOrigin("HTTPS://SERVER.Example/", "https://server.example"), "case")
        check(EndpointRules.sameOrigin("http://192.168.1.50:4000/index.html", home), "a home address")
        check(EndpointRules.sameOrigin("http://my_server:4000/", "http://my_server:4000"), "an underscore host (Android: URI refuses it)")
        check(EndpointRules.sameOrigin("http://192.168.1.50:4000/#/a|b{c}", home), "odd characters in the fragment")
        check(EndpointRules.sameOrigin("http://[fd00::1]:4000/", "http://[fd00::1]:4000"), "an IPv6 literal")
        check(EndpointRules.sameOrigin(" https://server.example/ ", "https://server.example"), "white space around it")
    }

    test("sameOrigin: another host, scheme or port is another origin, and anything unparseable is refused") {
        check(!EndpointRules.sameOrigin("https://server.example.attacker.test/", "https://server.example"), "a longer host that starts with it")
        check(!EndpointRules.sameOrigin("https://server.examples/", "https://server.example"), "one letter longer")
        check(!EndpointRules.sameOrigin("http://server.example/", "https://server.example"), "http for https")
        check(!EndpointRules.sameOrigin("https://server.example:8443/", "https://server.example"), "another port")
        check(!EndpointRules.sameOrigin("http://192.168.1.50:40001/", home), "a longer port that starts with it")
        check(!EndpointRules.sameOrigin("http://192.168.1.50/", home), "the port dropped")
        check(!EndpointRules.sameOrigin(nil, "https://server.example"), "no page")
        check(!EndpointRules.sameOrigin("https://server.example", nil), "no bridge address")
        check(!EndpointRules.sameOrigin(nil, nil), "nil is never the same origin as nil")
        check(!EndpointRules.sameOrigin("about:blank", "about:blank"), "about:blank")
        check(!EndpointRules.sameOrigin("capacitor://localhost/", "capacitor://localhost"), "not http(s)")
        check(!EndpointRules.sameOrigin("file:///x", "file:///x"), "a file")
        check(!EndpointRules.sameOrigin("not a url", "not a url"), "not a URL")
        check(!EndpointRules.sameOrigin("http://user@192.168.1.50:4000/", home), "a userinfo form is not an origin accepted here")
        check(!EndpointRules.sameOrigin("http://192.168.1.50:99999/", "http://192.168.1.50:99999"), "a port past 65535")
    }

    test("origin reads scheme, host and port, lower-cased, with the default port") {
        checkEqual(EndpointRules.origin("HTTP://192.168.1.50:4000/x"), EndpointRules.Origin(scheme: "http", host: "192.168.1.50", port: 4000))
        checkEqual(EndpointRules.origin("https://Nightlight.Example.ts.net"), EndpointRules.Origin(scheme: "https", host: "nightlight.example.ts.net", port: 443))
        checkEqual(EndpointRules.origin("http://server.example"), EndpointRules.Origin(scheme: "http", host: "server.example", port: 80))
        checkEqual(EndpointRules.origin("https://server.example\n/evil"), nil, "a line break inside")
    }

    test("isOnBundledOrigin: the bundled scheme and host only") {
        let local = "capacitor://localhost"
        check(EndpointRules.isOnBundledOrigin("capacitor://localhost/", local: local), "the setup page")
        check(EndpointRules.isOnBundledOrigin("capacitor://localhost/index.html", local: local), "with a path")
        check(EndpointRules.isOnBundledOrigin("CAPACITOR://LOCALHOST/", local: local), "case")
        check(!EndpointRules.isOnBundledOrigin("capacitor://localhost.attacker.test/", local: local), "a longer host")
        check(!EndpointRules.isOnBundledOrigin("capacitor://localhost:8080/", local: local), "a port")
        check(!EndpointRules.isOnBundledOrigin("capacitor://user@localhost/", local: local), "user info")
        check(!EndpointRules.isOnBundledOrigin("https://localhost/", local: local), "another scheme")
        check(!EndpointRules.isOnBundledOrigin("http://192.168.1.50:4000/", local: local), "a server page")
        check(!EndpointRules.isOnBundledOrigin(nil, local: local), "no page URL")
        check(!EndpointRules.isOnBundledOrigin("capacitor://localhost/", local: nil), "no bundled origin")
    }

    test("isBundledPage: a bundled bridge showing the bundled origin, and nothing else (save's reconnect and forget)") {
        // forget is allowed only here since the Codex code review of the PR #38 prompt delta: it clears a stored decline.
        let local = "capacitor://localhost"
        check(EndpointRules.isBundledPage(bridgeServerUrl: local, bridgeLocalUrl: local, shown: "capacitor://localhost/"), "the setup page")
        check(EndpointRules.isBundledPage(bridgeServerUrl: local, bridgeLocalUrl: local, shown: "capacitor://localhost/index.html"), "with a path")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: "http://192.168.1.50:4000", bridgeLocalUrl: local, shown: "capacitor://localhost/"),
              "a bridge built for a server, even showing the bundled origin")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: local, bridgeLocalUrl: local, shown: "http://192.168.1.50:4000/"), "a bundled bridge showing a server page")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: local, bridgeLocalUrl: local, shown: nil), "the page URL could not be read")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: nil, bridgeLocalUrl: local, shown: "capacitor://localhost/"), "no server URL read")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: local, bridgeLocalUrl: nil, shown: "capacitor://localhost/"), "no bundled URL read")
        check(!EndpointRules.isBundledPage(bridgeServerUrl: nil, bridgeLocalUrl: nil, shown: nil), "nothing at all")
    }

    test("isActivePage, the bridge half of isTrustedPage") {
        check(EndpointRules.isActivePage(primary: home, savedTailscale: nil, page: home), "the saved address")
        check(EndpointRules.isActivePage(primary: home, savedTailscale: ts, page: ts), "the saved Tailscale address")
        check(!EndpointRules.isActivePage(primary: home, savedTailscale: nil, page: ts), "a Tailscale page with none saved")
        check(!EndpointRules.isActivePage(primary: home, savedTailscale: ts, page: nil), "the setup page")
        check(!EndpointRules.isActivePage(primary: nil, savedTailscale: ts, page: ts), "nothing saved")
        check(!EndpointRules.isActivePage(primary: nil, savedTailscale: nil, page: nil), "a nil saved address must not match a nil page")
    }

    test("an address is accepted only when the two servers report the same id") {
        let a = ServerHealth(id: sameId, name: "Nursery")
        checkEqual(EndpointRules.checkIdentity(reference: a, candidate: ServerHealth(id: sameId, name: "A different name is fine")), EndpointRules.AddCheck.ok)
        checkEqual(EndpointRules.checkIdentity(reference: a, candidate: ServerHealth(id: otherId, name: "Nursery")), EndpointRules.AddCheck.differentServer)
    }

    test("either server not answering is its own refusal, and the reference is judged first") {
        let a = ServerHealth(id: sameId, name: "Nursery")
        checkEqual(EndpointRules.checkIdentity(reference: nil, candidate: a), EndpointRules.AddCheck.unreadableServer)
        checkEqual(EndpointRules.checkIdentity(reference: a, candidate: nil), EndpointRules.AddCheck.unreachableAddress)
        checkEqual(EndpointRules.checkIdentity(reference: nil, candidate: nil), EndpointRules.AddCheck.unreadableServer)
    }

    test("the refusal sentences are Android's, word for word (the web app reads them)") {
        checkEqual(EndpointRules.refusal(.notATailscaleAddress), "Not a Tailscale address")
        checkEqual(EndpointRules.refusal(.notThisPage), "Not available on this page")
        checkEqual(EndpointRules.refusal(.unreadableServer), "Couldn't read this server's identity")
        checkEqual(EndpointRules.refusal(.unreachableAddress), "Couldn't reach that Tailscale address")
        checkEqual(EndpointRules.refusal(.differentServer), "That address is a different server")
        checkEqual(EndpointRules.refusal(.ok), "")
        // What nativeBridge.js classifyAddRefusal looks for: /couldn'?t reach/i and /different server/i, with an ASCII
        // apostrophe (a typographic one would not match).
        check(EndpointRules.refusal(.unreachableAddress).lowercased().hasPrefix("couldn't reach"), "unreachable must read \"Couldn't reach\"")
        check(EndpointRules.refusal(.differentServer).lowercased().contains("different server"), "different must read \"different server\"")
        check(!EndpointRules.refusal(.unreadableServer).lowercased().contains("couldn't reach"), "unreadable must not be read as unreachable")
    }

    test("the offered address loses white space and trailing slashes, as on Android") {
        checkEqual(EndpointRules.offeredAddress("  https://nightlight.example.ts.net/ "), "https://nightlight.example.ts.net")
        checkEqual(EndpointRules.offeredAddress("https://nightlight.example.ts.net///"), "https://nightlight.example.ts.net")
        checkEqual(EndpointRules.offeredAddress("https://nightlight.example.ts.net"), "https://nightlight.example.ts.net")
        checkEqual(EndpointRules.offeredAddress(nil), nil)
        checkEqual(EndpointRules.withoutTrailingSlashes("http://192.168.1.50:4000/"), "http://192.168.1.50:4000")
    }

    test("home-network-only addresses are private IPv4 and dot-local names") {
        let local = ["http://192.168.1.50:4000", "http://10.0.0.5", "http://10.200.1.2", "http://10.255.255.254", "http://172.16.4.2:4000",
                     "http://172.31.255.1", "http://nightlight.local:4000", "http://NIGHTLIGHT.LOCAL"]
        for url in local {
            check(EndpointRules.isLocalOnlyAddress(url), "not treated as local-only: " + url)
        }
    }

    test("a public address, a tailnet address or anything unparseable is not local-only, so it gets the ordinary wait") {
        let notLocal: [String?] = [nil, "", "not a url", "https://nightlight.example.com", "https://nightlight.example.ts.net", "http://100.64.0.1:4000",
                                   "http://8.8.8.8", "http://172.32.0.1", "http://172.15.0.1", "http://192.169.1.1", "http://localhost:4000",
                                   "http://nightlight.local.evil.example"]
        for url in notLocal {
            check(!EndpointRules.isLocalOnlyAddress(url), "treated as local-only: " + (url ?? "<nil>"))
        }
    }

    // ---- The Tailscale grace (Tailscale first, owner decision 2026-11-09) ----

    test("the tailscale grace is 1 second, or a quarter second when the phone positively has no VPN") {
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: true), 1_000, "a VPN, or a reading that failed (fail open)")
        checkEqual(EndpointRules.tailscaleGraceMs(vpnPresent: false), 250, "positively no VPN")
        checkEqual(EndpointRules.defaultTailscaleGraceMs, 1_000, "the judgement for a phone with a VPN")
        checkEqual(EndpointRules.noVpnTailscaleGraceMs, 250, "the judgement for a phone with none")
    }

    test("the VPN hint can only shorten the grace, and the grace ends before the launch's deadline") {
        check(EndpointRules.tailscaleGraceMs(vpnPresent: false) < EndpointRules.tailscaleGraceMs(vpnPresent: true), "no VPN must not wait longer than a VPN")
        check(EndpointRules.defaultTailscaleGraceMs < EndpointChooser.defaultDeadlineMs, "a grace as long as the deadline would hold every launch with Tailscale off for the whole deadline")
    }

    // ---- Change server from the Tailscale address (owner, 2026-11-06) ----

    test("a remembered server whose Tailscale address answers with its saved id can be reconnected through it") {
        check(EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: ts, wantedId: sameId, liveId: sameId), "refused")
    }

    test("each missing or wrong piece refuses the reconnect") {
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: "http://192.168.1.99:4000", tailscaleUrl: ts, wantedId: sameId, liveId: sameId), "not a remembered server")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: nil, tailscaleUrl: ts, wantedId: sameId, liveId: sameId), "no url")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: nil, wantedId: sameId, liveId: sameId), "no Tailscale address saved")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: "https://evil.example.com", wantedId: sameId, liveId: sameId), "a saved address that is not a Tailscale one")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: ts, wantedId: nil, liveId: sameId), "no id was saved with it")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: ts, wantedId: sameId, liveId: nil), "the live id could not be read")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: ts, wantedId: sameId, liveId: otherId), "another server answers there")
        check(!EndpointRules.canReconnectViaTailscale(knownServers: [], url: home, tailscaleUrl: ts, wantedId: sameId, liveId: sameId), "nothing remembered at all")
    }

    // ---- The server's name in the saved list (owner, 2026-11-06) ----

    test("a name is remembered only for a remembered server and only when the server sent one") {
        check(EndpointRules.mayRememberName(knownServers: known, url: home, name: "Nightlight"), "refused a remembered server's name")
        check(!EndpointRules.mayRememberName(knownServers: known, url: "http://192.168.1.99:4000", name: "Nightlight"), "not a remembered server")
        check(!EndpointRules.mayRememberName(knownServers: known, url: home, name: nil), "no name sent")
        check(!EndpointRules.mayRememberName(knownServers: known, url: home, name: "   "), "a blank name")
        check(!EndpointRules.mayRememberName(knownServers: [], url: home, name: "Nightlight"), "nothing remembered")
    }

    test("only the names of remembered servers are listed, and a blank one is dropped") {
        let stored: [String: String] = [home: "Home", "https://nightlight.example.com": "  ", "http://192.168.1.99:4000": "A forgotten server"]
        checkEqual(EndpointRules.namesForKnown(stored: stored, knownServers: known), [home: "Home"])
        checkEqual(EndpointRules.namesForKnown(stored: [:], knownServers: known), [:])
        // Android also pins the ORDER here; a Swift dictionary has none and the page reads names by address, so iOS pins the
        // content only.
        checkEqual(EndpointRules.namesForKnown(stored: [home: "Home", "https://nightlight.example.com": "Away"], knownServers: ["https://nightlight.example.com", home]),
                   [home: "Home", "https://nightlight.example.com": "Away"])
    }

    test("a saved Tailscale address is kept when the home address reports its id or none, and dropped when another server does") {
        check(EndpointRules.pairStillValid(wantedId: sameId, liveId: sameId), "same server")
        check(EndpointRules.pairStillValid(wantedId: sameId, liveId: nil), "home id unreadable: not knowing is not a reason to lose the way back in")
        check(EndpointRules.pairStillValid(wantedId: nil, liveId: otherId), "no id was saved")
        check(!EndpointRules.pairStillValid(wantedId: sameId, liveId: otherId), "another machine took over the home address")
    }

    // ---- The IPv4 rules behind isLocalOnlyAddress (from DiscoveryRulesTest.kt) ----

    test("accepts the edges of each private range") {
        for host in ["10.0.0.0", "10.255.255.255", "10.1.2.3", "172.16.0.0", "172.31.255.255", "172.20.10.5", "192.168.0.0", "192.168.255.255", "192.168.1.50"] {
            check(EndpointRules.isPrivateIpv4(host), "refused: " + host)
        }
    }

    test("refuses the addresses just outside each private range") {
        for host in ["9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "193.168.1.1", "191.168.1.1"] {
            check(!EndpointRules.isPrivateIpv4(host), "accepted: " + host)
        }
    }

    test("refuses loopback, link-local, carrier-grade NAT (Tailscale), public and special addresses") {
        for host in ["127.0.0.1", "169.254.1.1", "100.64.0.1", "100.100.100.100", "100.127.255.254", "8.8.8.8", "0.0.0.0", "255.255.255.255", "224.0.0.251"] {
            check(!EndpointRules.isPrivateIpv4(host), "accepted: " + host)
        }
    }

    test("refuses leading zeros, signs, whitespace and other non-canonical forms") {
        let odd: [String] = [
            "010.0.0.1", "192.168.001.001", "192.168.01.1", "00.0.0.0",
            "+10.0.0.1", "-10.0.0.1", "10.0.0.-1",
            " 10.0.0.1", "10.0.0.1 ", "10.0.0. 1", "10.0.0.1\n", "\t192.168.1.1",
            "10.0.0", "10.0.0.1.", ".10.0.0.1", "10..0.1", "10.0.0.1.2",
            "10.0.0.256", "192.168.1.999", "1e1.0.0.1", "0x0a.0.0.1",
            "\u{0661}\u{0660}.0.0.1", // Arabic-Indic digits for "10": they must not pass as digits
        ]
        for host in odd {
            check(!EndpointRules.isPrivateIpv4(host), "accepted: " + host)
        }
        check(!EndpointRules.isPrivateIpv4(nil), "accepted nil")
        for host in ["::ffff:192.168.1.1", "fe80::1", "nightlight.local", "localhost", "192.168.1.1.evil.example", "192.168.1.1:4000", ""] {
            check(!EndpointRules.isPrivateIpv4(host), "accepted: " + host)
        }
    }

    test("parseIpv4 returns the octets of a canonical literal") {
        checkEqual(EndpointRules.parseIpv4("192.168.1.50"), [192, 168, 1, 50])
        checkEqual(EndpointRules.parseIpv4("0.0.0.0"), [0, 0, 0, 0])
        checkEqual(EndpointRules.parseIpv4("192.168.1"), nil)
    }
}
