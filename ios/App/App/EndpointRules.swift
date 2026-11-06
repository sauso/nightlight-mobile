import Foundation

// EndpointRules.swift: the rules for a server's second address, the one the phone uses away from home (owner, 2026-11-06:
// the phone tries the local address first and falls back to the server's Tailscale address, which the server tells it about).
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/EndpointRules.kt, ported rule for rule with the same names
// and the same refusal sentences, plus the two pieces of DiscoveryRules.kt these rules need (parseIpv4, isPrivateIpv4).
//
// Foundation only, no Capacitor or UIKit import, on purpose: CI compiles this file, EndpointProbe.swift and
// EndpointChooser.swift ALONE with swiftc and runs ios/Tests against exactly this code (build-ios.yml). The project has no
// Xcode test target, so this is the only way the rules are run before a phone does.
//
// Where it is stored: the saved server (ServerConfigStore.savedURL, the address the person typed or picked) stays the PRIMARY
// address and is never rewritten by this. The Tailscale address is kept beside it in UserDefaults under key(primary), so the
// saved address and the saved list read exactly as before (and an older app version that finds these entries ignores them).

/// A `/api/health` reply that passed `EndpointProbe.parseHealth`: the server's id, and its display name if it sent a usable
/// one. The twin of Android's `DiscoveryRules.Health`.
struct ServerHealth: Equatable {
    let id: String
    let name: String?
}

enum EndpointRules {

    // `https://<name>.ts.net`, nothing else: no port, no path, no credentials, no IP literal. The server only ever offers its
    // Tailscale name (GET /api/remote-access/tailscale/address), and Serve answers on 443. Lower case only, the form the server
    // sends. 255 is the bound on a whole address, a maximum DNS name being 253. The same pattern as Android, character for
    // character.
    private static let tailscalePattern =
        "^https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.ts\\.net$"
    static let maxUrlChars: Int = 255

    private static let tailscaleRegex: NSRegularExpression = EndpointRules.makeRegex(EndpointRules.tailscalePattern)

    /// A regex from a FIXED pattern in this code. `try!` on purpose: a typo in a pattern fails the first CI test run, it can
    /// never depend on anything a server or a page sends.
    static func makeRegex(_ pattern: String) -> NSRegularExpression {
        return try! NSRegularExpression(pattern: pattern, options: [])
    }

    /// True only when the WHOLE of `text` matches `regex`. Kotlin's `Regex.matches` (what Android runs) needs the entire
    /// input to match; NSRegularExpression's `$` also matches just before a final line break, so `https://x.ts.net\n` would
    /// pass on the pattern alone. The match must therefore also cover every UTF-16 unit of the input.
    static func wholeMatch(_ regex: NSRegularExpression, _ text: String) -> Bool {
        let whole = NSRange(location: 0, length: text.utf16.count)
        guard let match = regex.firstMatch(in: text, options: [], range: whole) else { return false }
        return match.range.location == 0 && match.range.length == whole.length
    }

    /// True when `url` is exactly `https://<name>.ts.net` and at most `maxUrlChars` long (UTF-16 units, as Kotlin's length).
    static func isTailscaleUrl(_ url: String?) -> Bool {
        guard let value = url else { return false }
        if value.utf16.count > maxUrlChars { return false }
        return wholeMatch(tailscaleRegex, value)
    }

    /// The UserDefaults key of the Tailscale address that belongs to the saved server `primary`. The same key as Android's
    /// SharedPreferences key, so the two apps' stores read alike.
    static func key(_ primary: String) -> String {
        return "tailscale_for:" + primary
    }

    /// The key of the server id that Tailscale address was saved for. The address is only ever opened if the server answering
    /// there still reports THIS id (MainViewController), so an address saved for one server cannot end up opening another if
    /// the saved address is later handed to a different machine (found in the review of the Android Tailscale fallback: a
    /// stale entry plus a saved address that has gone quiet would otherwise open the old server).
    static func idKey(_ primary: String) -> String {
        return "tailscale_id:" + primary
    }

    /// `text` without any trailing slashes. Used for the address a page offers (addEndpoint) and for the address the bridge
    /// was built with, so `https://x.ts.net/` and `https://x.ts.net` are one address, as on Android (`trimEnd('/')`).
    static func withoutTrailingSlashes(_ text: String) -> String {
        var out = text
        while out.hasSuffix("/") {
            out.removeLast()
        }
        return out
    }

    /// The address addEndpoint was offered, read as Android reads it (`trim().trimEnd('/')`): white space around it and every
    /// trailing slash removed. nil stays nil. Whether it is acceptable is still `checkPage`'s decision.
    static func offeredAddress(_ raw: String?) -> String? {
        guard let value = raw else { return nil }
        return withoutTrailingSlashes(value.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// True when `url`'s host can only be reached from a local network: a private IPv4 address (10/8, 172.16/12, 192.168/16)
    /// or a `.local` name. A public name or address (a domain with HTTPS, say) is NOT local-only: it answers on mobile data
    /// too. Anything that does not parse is not local-only (so the ordinary wait applies).
    static func isLocalOnlyAddress(_ url: String?) -> Bool {
        guard let value = url else { return false }
        guard let host = URLComponents(string: value)?.host, !host.isEmpty else { return false }
        return isPrivateIpv4(host) || host.lowercased().hasSuffix(".local")
    }

    // How long a launch waits for a saved address that says nothing, when the phone cannot be on its network (below). A
    // judgement carried over from Android (#26), not a measurement: a reachable home server answers in milliseconds and even a
    // slow Wi-Fi in well under a second, so this is generous for a phone that merely just joined Wi-Fi, and short enough that
    // the Tailscale fallback opens in about a second instead of three.
    static let offNetworkWaitMs: Int = 1_000

    /// How long the launch waits for the saved address before it uses the Tailscale one. The short wait applies only when ALL
    /// of: a Tailscale address is saved (otherwise there is nothing to switch to, so the saved address keeps its whole chance),
    /// the phone is not on Wi-Fi or Ethernet, and the saved address is one only a local network can answer. Every other case
    /// waits the full `fullMs`. Away from home on mobile data, a home address cannot answer: waiting three seconds for it was
    /// the pause the owner saw on the first Android phone test (2026-11-06).
    static func localWaitMs(saved: String?, tailscale: String?, onLocalNetwork: Bool, fullMs: Int) -> Int {
        if tailscale != nil && !onLocalNetwork && isLocalOnlyAddress(saved) {
            return min(offNetworkWaitMs, fullMs)
        }
        return fullMs
    }

    /// Whether `save` may take a REMEMBERED server whose own address did not answer, because the Tailscale address saved for
    /// it did, and that address reported the id it was saved for. This is what lets "Change server" get back in from cellular
    /// (owner, 2026-11-06): the setup page lists only the saved home address, which cannot answer there, so without this the
    /// only way back was to type the Tailscale address by hand.
    ///
    /// Every condition must hold: the address is exactly a server this phone remembers (a typed address that merely resembles
    /// one is not), it has a Tailscale address of the one accepted shape and a stored id, and the live id is that same id. An
    /// id is public and proves nothing alone (see `checkIdentity`); what it adds here is that the address was learned from
    /// this server earlier, and nothing is saved from it that was not already saved.
    static func canReconnectViaTailscale(knownServers: [String], url: String?, tailscaleUrl: String?, wantedId: String?, liveId: String?) -> Bool {
        guard let address = url, knownServers.contains(address) else { return false }
        if !isTailscaleUrl(tailscaleUrl) { return false }
        guard let wanted = wantedId, let live = liveId else { return false }
        return live == wanted
    }

    /// Whether a Tailscale address saved for `wantedId` still belongs to the server that now answers at the saved address
    /// (`liveId`, read when the saved address is next confirmed). A READABLE different id means another machine took over that
    /// home address (a DHCP lease handed on, say), so the old Tailscale address would open the wrong server and must go. An
    /// unreadable id keeps it: not knowing is not a reason to lose the way back in. (Change server used to drop the address
    /// unconditionally to avoid exactly this; keeping it and checking here is what lets Change server work away from home.)
    static func pairStillValid(wantedId: String?, liveId: String?) -> Bool {
        guard let wanted = wantedId, let live = liveId else { return true }
        return wanted == live
    }

    /// True when `text` is empty or only white space (Kotlin's isBlank).
    static func isBlank(_ text: String) -> Bool {
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Whether a server's display name may be remembered for the setup page's saved list (owner, 2026-11-06: show the name,
    /// with the address below it in smaller text, instead of only the address). Only for a server this phone already
    /// remembers, so the names can never grow past the saved list, and only a name the server actually sent
    /// (EndpointProbe.cleanName has already bounded it to a displayable length). The name comes from the server's public
    /// /api/health, so the page shows it as TEXT only.
    static func mayRememberName(knownServers: [String], url: String, name: String?) -> Bool {
        guard let value = name, !isBlank(value) else { return false }
        return knownServers.contains(url)
    }

    /// The stored names that belong to a remembered server; a forgotten server's name is never shown. Deliberately a plain
    /// dictionary, not Android's ordered map: a Swift dictionary has no order, and the page looks each name up by address.
    static func namesForKnown(stored: [String: String], knownServers: [String]) -> [String: String] {
        var out: [String: String] = [:]
        for url in knownServers {
            if let name = stored[url], !isBlank(name) {
                out[url] = name
            }
        }
        return out
    }

    /// Why addEndpoint accepted or refused a request. Reported so a test (and the page) can tell the refusals apart.
    enum AddCheck {
        case ok
        case notATailscaleAddress
        case notThisPage
        case unreadableServer
        case unreachableAddress
        case differentServer
    }

    /// The sentence addEndpoint rejects with. BYTE-IDENTICAL to Android's ServerConfigPlugin.refusal: the web app's About page
    /// reads the reason from these words (nativeBridge.js classifyAddRefusal matches "Couldn't reach" and "different server"),
    /// so a reworded sentence here would show the generic reason on an iPhone only. ios/Tests pins each one.
    static func refusal(_ check: AddCheck) -> String {
        switch check {
        case .notATailscaleAddress:
            return "Not a Tailscale address"
        case .notThisPage:
            return "Not available on this page"
        case .unreadableServer:
            return "Couldn't read this server's identity"
        case .unreachableAddress:
            return "Couldn't reach that Tailscale address"
        case .differentServer:
            return "That address is a different server"
        case .ok:
            return ""
        }
    }

    /// The checks that need no network: the address has the one accepted shape, and the page asking is trusted
    /// (`isTrustedPage`: the bridge was built for the saved server or the Tailscale address already saved for it, AND the web
    /// view is really showing that origin, `shown`). The bundled setup page has no server (`page` nil) and is refused, as is
    /// any other address.
    static func checkPage(url: String?, primary: String?, savedTailscale: String?, page: String?, shown: String?) -> AddCheck {
        if !isTailscaleUrl(url) { return .notATailscaleAddress }
        if !isTrustedPage(primary: primary, savedTailscale: savedTailscale, page: page, shown: shown) { return .notThisPage }
        return .ok
    }

    /// True when `page` (what the bridge was built with) is the saved server or the Tailscale address saved for it.
    static func isActivePage(primary: String?, savedTailscale: String?, page: String?) -> Bool {
        guard let home = primary, let current = page else { return false }
        if current == home { return true }
        if let tailscale = savedTailscale, current == tailscale { return true }
        return false
    }

    /// The page rule addEndpoint and removeEndpoint use: `isActivePage` (the bridge was built for the saved server or its
    /// saved Tailscale address) AND `sameOrigin(shown, page)`, where `shown` is the web view's CURRENT main-frame URL. The
    /// twin of Android's SessionRules.mayAct ("every call is refused unless the WebView's real URL has the origin the bridge
    /// was built for").
    ///
    /// WHY the second half is needed on iOS (review of PR #32, High): Capacitor iOS lets the web view navigate, inside the
    /// app, to any URL whose text merely STARTS WITH the bridge's server URL. A bridge built for `https://server.example`
    /// therefore also shows `https://server.example.attacker.test/`, and one built for `http://192.168.1.50:4000` also shows
    /// `http://192.168.1.50:40001/`: other machines, which could then call the plugin as if they were the saved server.
    /// What the bridge was built with says nothing about where the page went since; the page's own origin does.
    static func isTrustedPage(primary: String?, savedTailscale: String?, page: String?, shown: String?) -> Bool {
        if !isActivePage(primary: primary, savedTailscale: savedTailscale, page: page) { return false }
        return sameOrigin(shown, page)
    }

    // ---- Origins (the twin of Android's SessionRules.sameOrigin) ----

    /// A web origin: lower-case scheme and host, and the port with the default filled in (80 for http, 443 for https).
    struct Origin: Equatable {
        let scheme: String
        let host: String
        let port: Int
    }

    // The origin is the part before the first `/`, `?` or `#`: scheme (http or https only), host (no `@`: a userinfo form is
    // not an origin accepted here; a bracketed IPv6 literal is) and an optional port. Android's pattern, character for
    // character, and for its reason: a URL parser that refuses an underscore host or a `|` in a fragment would silently refuse
    // every call from a real saved address (Opus review of Android phase D). Case is ignored in the pattern and lowered after.
    private static let originPattern = "^(https?)://(\\[[0-9A-Fa-f:.]+\\]|[^/?#:@\\s]+)(?::(\\d{1,5}))?(?:[/?#].*)?$"
    private static let originRegex: NSRegularExpression = EndpointRules.makeCaseInsensitiveRegex(EndpointRules.originPattern)

    /// Like makeRegex, ignoring case. Also a FIXED pattern from this code, so `try!` cannot depend on any input.
    static func makeCaseInsensitiveRegex(_ pattern: String) -> NSRegularExpression {
        return try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    /// The origin of `url`, or nil when it is not an http(s) URL of the accepted shape (white space around it is ignored, as
    /// Android trims). A port past 65535 is no origin.
    static func origin(_ url: String?) -> Origin? {
        guard let raw = url else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let whole = NSRange(location: 0, length: text.utf16.count)
        guard let match = originRegex.firstMatch(in: text, options: [], range: whole) else { return nil }
        if match.range.location != 0 || match.range.length != whole.length { return nil }
        let ns = text as NSString
        let scheme = ns.substring(with: match.range(at: 1)).lowercased()
        let host = ns.substring(with: match.range(at: 2)).lowercased()
        var port: Int = (scheme == "http") ? 80 : 443
        let portRange = match.range(at: 3)
        if portRange.location != NSNotFound {
            guard let value = Int(ns.substring(with: portRange)) else { return nil }
            port = value
        }
        if port > 65535 { return nil }
        return Origin(scheme: scheme, host: host, port: port)
    }

    /// Whether `a` and `b` have the same origin: scheme, host and port, case ignored, default ports filled in, any path, query
    /// or fragment ignored. Anything that does not parse (nil, `about:blank`, a `capacitor://` page) is never the same origin.
    static func sameOrigin(_ a: String?, _ b: String?) -> Bool {
        guard let left = origin(a), let right = origin(b) else { return false }
        return left == right
    }

    /// Whether `shown` (the web view's current URL) is on the bundled origin `local` (Capacitor's `capacitor://localhost` by
    /// default): the same scheme and host, no port, no user info. The second half of Android's DiscoveryRules.isBundledPage,
    /// used for the one bundled-page-only path (save's reconnect through the Tailscale address), so a page the bundled bridge
    /// might show anywhere else cannot use it either. nil or unparseable is "not the bundled page".
    static func isOnBundledOrigin(_ shown: String?, local: String?) -> Bool {
        guard let shownText = shown, let localText = local else { return false }
        guard let page = URLComponents(string: shownText), let bundled = URLComponents(string: localText) else { return false }
        guard let pageScheme = page.scheme, let bundledScheme = bundled.scheme else { return false }
        if pageScheme.lowercased() != bundledScheme.lowercased() { return false }
        guard let pageHost = page.host, let bundledHost = bundled.host, !bundledHost.isEmpty else { return false }
        if pageHost.lowercased() != bundledHost.lowercased() { return false }
        if page.port != nil || page.user != nil || page.password != nil { return false }
        return true
    }

    /// The check that needs the network's answers: the server the page is on (`reference`) and the offered address
    /// (`candidate`) both reported an id, and it is the same one. This is an identity CLAIM, not proof: /api/health is public,
    /// so a page that is itself hostile can name an address that repeats the id. That page is the server the person chose to
    /// use, so it could already show anything; what it cannot do is change the saved address, and the address is opened only
    /// when the saved one is not answering.
    static func checkIdentity(reference: ServerHealth?, candidate: ServerHealth?) -> AddCheck {
        guard let ref = reference else { return .unreadableServer }
        guard let cand = candidate else { return .unreachableAddress }
        if ref.id != cand.id { return .differentServer }
        return .ok
    }

    // ---- From DiscoveryRules.kt: the IPv4 rules isLocalOnlyAddress needs ----

    /// The four octets of a strict dotted-quad IPv4 literal, or nil. Strict on purpose: exactly four parts, each one to three
    /// ASCII digits, no leading zero (so `010.0.0.1`, which some parsers read as octal, is refused), no sign, no white space,
    /// each 0 to 255. A hostname, an IPv6 literal and `192.168.1.1.evil.example` are all refused. Empty parts are KEPT by the
    /// split (`omittingEmptySubsequences: false`), as Kotlin's split keeps them, so `10..0.1` has four parts and is refused for
    /// the empty one rather than read as three.
    static func parseIpv4(_ host: String?) -> [Int]? {
        guard let text = host, !text.isEmpty else { return nil }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count != 4 { return nil }
        var octets: [Int] = []
        for part in parts {
            let bytes: [UInt8] = Array(part.utf8)
            if bytes.isEmpty || bytes.count > 3 { return nil }
            // ASCII '0' to '9' only, by byte: other scripts' digits (Arabic-Indic and so on) must not pass.
            for byte in bytes {
                if byte < 0x30 || byte > 0x39 { return nil }
            }
            if bytes.count > 1 && bytes[0] == 0x30 { return nil }
            var value = 0
            for byte in bytes {
                value = value * 10 + Int(byte - 0x30)
            }
            if value > 255 { return nil }
            octets.append(value)
        }
        return octets
    }

    /// True only for an IPv4 literal in the three RFC 1918 private ranges: 10/8, 172.16/12, 192.168/16. 100.64/10 (carrier-grade
    /// NAT, which includes Tailscale's own addresses) is NOT private here: a tailnet address is not "on this Wi-Fi".
    static func isPrivateIpv4(_ host: String?) -> Bool {
        guard let o = parseIpv4(host) else { return false }
        if o[0] == 10 { return true }
        if o[0] == 172 && o[1] >= 16 && o[1] <= 31 { return true }
        if o[0] == 192 && o[1] == 168 { return true }
        return false
    }
}
