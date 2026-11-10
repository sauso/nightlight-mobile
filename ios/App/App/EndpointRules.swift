import Foundation

// EndpointRules.swift: the rules for a server's second address, a Tailscale or custom HTTPS address told to the phone
// (owner, 2026-11-06). Since the owner decision of 2026-11-09 ("Tailscale first") the app opens that secure address first when
// it answers, and the address it was set up with when that address passes the launch identity check.
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/EndpointRules.kt, ported rule for rule with the same names
// and the same refusal sentences, plus the two pieces of DiscoveryRules.kt these rules need (parseIpv4, isPrivateIpv4).
//
// Foundation only, no Capacitor or UIKit import, on purpose: CI compiles this file, EndpointProbe.swift and
// EndpointChooser.swift ALONE with swiftc and runs ios/Tests against exactly this code (build-ios.yml). The project has no
// Xcode test target, so this is the only way the rules are run before a phone does.
//
// Where it is stored: the saved server (ServerConfigStore.savedURL, the address the person typed or picked) stays the PRIMARY
// address and is never rewritten by this. The secure address is kept beside it in UserDefaults under key(primary), so the
// saved address and the saved list read exactly as before (and an older app version that finds these entries ignores them).

/// A `/api/health` reply that passed `EndpointProbe.parseHealth`: the server's id, and its display name if it sent a usable
/// one. The twin of Android's `DiscoveryRules.Health`.
struct ServerHealth: Equatable {
    let id: String
    let name: String?
}

enum EndpointRules {

    /// Launch opens a saved address with a known id only after its strict health reply matches.
    /// Without a known id, preserve the old immediate launch (and its error page on failure).
    enum LaunchVerdict {
        case sameServer
        case differentServer
        case noAnswer
        case unchecked
    }

    static func launchVerdict(expectedId: String?, liveId: String?) -> LaunchVerdict {
        guard let expected = expectedId else { return .unchecked }
        guard let live = liveId else { return .noAnswer }
        return live == expected ? .sameServer : .differentServer
    }

    static func launchChoice(saved: String, verdict: LaunchVerdict) -> String? {
        switch verdict {
        case .sameServer, .unchecked: return saved
        case .differentServer, .noAnswer: return nil
        }
    }

    /// Only a refused, id-checked saved server warrants bypassing the routine search throttle. A first-run setup page or
    /// an unchecked address does not establish that the person's server was refused.
    static func launchRefusalRequestsCheck(expectedIdPresent: Bool, verdict: LaunchVerdict,
                                           idCheckedAddress: Bool) -> Bool {
        guard expectedIdPresent, idCheckedAddress else { return false }
        switch verdict {
        case .differentServer, .noAnswer: return true
        case .sameServer, .unchecked: return false
        }
    }

    /// Only the canonical private IPv4 address that local discovery can identify gets a launch identity check. Typed
    /// hostnames, HTTPS and other URL forms keep their earlier reachability and save behaviour.
    static func idCheckedAddress(_ url: String?) -> Bool {
        guard let parsed = DiscoveryRules.parseFoundUrl(url) else { return false }
        return isPrivateIpv4(parsed.host)
    }

    static func expectedId(for url: String, storedId: String?, pairId: String?) -> String? {
        guard idCheckedAddress(url) else { return nil }
        return storedId ?? pairId
    }

    /// Forgetting the selected server must also remove the launch choice; forgetting another remembered server must not.
    static func forgetClearsActive(saved: String?, forgotten: String) -> Bool {
        return saved == forgotten
    }

    /// A save may proceed on no reply as before, but a different strict id must not discard
    /// this address's pair or sign-in and then reopen it without its expected identity.
    enum IdentityVerdict {
        case same
        case different
        case unknown
    }

    static func identityVerdict(expectedId: String?, liveId: String?) -> IdentityVerdict {
        guard let expected = expectedId, let live = liveId else { return .unknown }
        return expected == live ? .same : .different
    }

    // `https://<name>.ts.net`, nothing else: no port, no path, no credentials, no IP literal. The server only ever offers its
    // Tailscale name when that kind is selected, and Serve answers on 443. Lower case only, the form the server
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

    // The server sends a canonical HTTPS origin. Check the raw spelling: URL parsers can turn numeric DNS-looking hosts into
    // IPv4 addresses (the CUSTOM_URL parser's WHATWG edge case). Keep these patterns equivalent to Android's EndpointRules.
    private static let customRegex = EndpointRules.makeRegex("^https://([a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?::([1-9][0-9]*))?$")
    private static let dnsLabelRegex = EndpointRules.makeRegex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private static let numericFinalRegex = EndpointRules.makeRegex("^(?:[0-9]+|0x[0-9a-f]*)$")

    static func isCustomUrl(_ url: String?) -> Bool {
        guard let value = url, value.utf16.count <= maxUrlChars else { return false }
        let whole = NSRange(location: 0, length: value.utf16.count)
        guard let match = customRegex.firstMatch(in: value, options: [], range: whole),
              match.range.location == 0, match.range.length == whole.length else { return false }
        let ns = value as NSString
        let host = ns.substring(with: match.range(at: 1))
        if host.utf16.count > 253 || host == "ts.net" || host.hasSuffix(".ts.net") || host.hasSuffix(".local") { return false }
        let labels = host.components(separatedBy: ".")
        if labels.contains(where: { !wholeMatch(dnsLabelRegex, $0) }) || wholeMatch(numericFinalRegex, labels.last ?? "") { return false }
        let portRange = match.range(at: 2)
        if portRange.location == NSNotFound { return true }
        let portText = ns.substring(with: portRange)
        // The server strips :443; accepting it here would widen the announced-origin shape.
        guard portText != "443", let port = Int(portText) else { return false }
        return (1...65535).contains(port)
    }

    static func isSecureAddress(_ url: String?) -> Bool { isTailscaleUrl(url) || isCustomUrl(url) }
    /// Persisted values are rechecked before use; an old or corrupt slot must fail closed.
    static func validatedSlot(_ url: String?) -> String? { isSecureAddress(url) ? url : nil }
    static func addressKind(_ url: String?) -> String? {
        if isTailscaleUrl(url) { return "tailscale" }
        if isCustomUrl(url) { return "custom" }
        return nil
    }

    /// The UserDefaults key of the secure address (Tailscale or custom HTTPS) for `primary`. The same key as Android's
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

    /// The keys of the secure address the person DECLINED for the saved server `primary` (an explicit "Don't use it"), and of
    /// the wall-clock time they did, in milliseconds (PromptGate). Beside the Tailscale pair, removed with the server (forget).
    static func declinedKey(_ primary: String) -> String {
        return "tailscale_declined:" + primary
    }

    static func declinedAtKey(_ primary: String) -> String {
        return "tailscale_declined_at:" + primary
    }

    /// Whether addEndpoint must ASK the person before saving `candidate` (addendum of 2026-11-09, the owner's "ask once"; Codex
    /// code review of PR #38 rated the risk High). WHY: the id the identity check compares is PUBLIC, so a device that answers at a
    /// plain-http saved address (a network can put one there) can offer its own `https://<x>.ts.net` with the right id; under
    /// Tailscale first that address would then be opened FIRST, at home too, and could show a fake sign-in page. Asks when BOTH:
    ///  - the candidate differs from the secure address already saved for this server (a first learn, or a change; the same
    ///    address is offered again at every start and is never asked about), AND
    ///  - the page actually SHOWN is not https (addendum review: the shown page, not the address the bridge was built with). A
    ///    page on https, the page on the saved Tailscale address itself included, cannot be put there by the network, so it is
    ///    not asked. A shown page that cannot be read or parsed counts as plain http: it asks (fail closed).
    static func needsConfirmation(shownPageUrl: String?, savedTailscale: String?, candidate: String) -> Bool {
        if let saved = savedTailscale, saved == candidate { return false }
        guard let shown = origin(shownPageUrl) else { return true }
        return shown.scheme != "https"
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
    /// too. Anything that does not parse is not local-only.
    /// No caller since Tailscale first removed the launch's short off-Wi-Fi wait (localWaitMs, 2026-11-09); kept, with its IPv4
    /// rules and their tests, for the planned iPhone port of Find server (Codex plan review of Tailscale first, 2026-11-09).
    static func isLocalOnlyAddress(_ url: String?) -> Bool {
        guard let value = url else { return false }
        guard let host = URLComponents(string: value)?.host, !host.isEmpty else { return false }
        return isPrivateIpv4(host) || host.lowercased().hasSuffix(".local")
    }

    // ---- The secure-address grace (Tailscale first, owner decision 2026-11-09) ----
    //
    // How long a launch that already has an answer from the saved address still waits for the secure address
    // (EndpointChooser's grace, counted from the start of the choice). Both are JUDGEMENTS, not measurements: Tailscale on and
    // healthy answers in well under a second, so one second lets it win when it is there; and when the phone positively has no
    // VPN (so no Tailscale answer is expected), the saved address should not be held back by more than a quarter of a second.
    // A Tailscale address that answers later than the grace loses that launch to the saved address, and the next foreground
    // start moves the page over (RoamRules.target, the upgrade). Named differently from Android's TAILSCALE_GRACE_MS and
    // TAILSCALE_GRACE_NO_VPN_MS only so the constant and the function below do not share a Swift name.
    static let defaultTailscaleGraceMs: Int = 1_000
    static let noVpnTailscaleGraceMs: Int = 250

    /// The grace for this launch. A custom slot always gets the full second. For Tailscale, the VPN reading is a HINT:
    /// it is false only when the phone positively reports no VPN, and a reading that fails counts as a VPN (fail open = the
    /// full grace). So a wrong hint can at worst let a slower but valid Tailscale answer lose to the saved address (the old
    /// saved-first behaviour), never open an address that was not verified (the id check is the probe's, unchanged).
    static func tailscaleGraceMs(vpnPresent: Bool, slotUrl: String? = nil) -> Int {
        // A custom origin needs no VPN; the VPN hint can shorten only a Tailscale wait.
        return vpnPresent || isCustomUrl(slotUrl) ? defaultTailscaleGraceMs : noVpnTailscaleGraceMs
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
        if !isSecureAddress(tailscaleUrl) { return false }
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
    /// `notAccepted` and `notConfirmed` are the "ask once" prompt's (PromptGate, addendum of 2026-11-09).
    enum AddCheck {
        case ok
        case notATailscaleAddress
        case notThisPage
        case unreadableServer
        case unreachableAddress
        case differentServer
        case notAccepted
        case notConfirmed
    }

    /// The sentence addEndpoint rejects with. The legacy text says Tailscale even for a custom slot because the web app
    /// classifies these exact words. BYTE-IDENTICAL to Android's EndpointRules.refusal: the web app's About page
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
        // The two "ask once" refusals (addendum of 2026-11-09). Worded so the web app's classifyAddRefusal (nativeBridge.js, which
        // matches /couldn'?t reach/i and /different server/i) files them under its generic 'refused', the "did not accept" line:
        // neither may contain those words (ios/Tests pins it). New sentences, so Android's must be made the same word for word.
        case .notAccepted:
            // The person tapped "Don't use it", now or within the last 7 days for this same address.
            return "The secure address was not accepted on this phone"
        case .notConfirmed:
            // No answer: another prompt showing, a prompt shortly before, no screen to show it on, a timeout or a cancel.
            return "The secure address was not confirmed on this phone"
        case .ok:
            return ""
        }
    }

    /// The checks that need no network: the address has the one accepted shape, and the page asking is trusted
    /// (`isTrustedPage`: the bridge was built for the saved server or the Tailscale address already saved for it, AND the web
    /// view is really showing that origin, `shown`). The bundled setup page has no server (`page` nil) and is refused, as is
    /// any other address.
    static func checkPage(url: String?, primary: String?, savedTailscale: String?, page: String?, shown: String?) -> AddCheck {
        if !isSecureAddress(url) { return .notATailscaleAddress }
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

    /// Whether the page calling is the bundled setup page (public/index.html), from what the plugin can read: the bridge was
    /// built with NO server URL (Capacitor then reports the bundled URL, `bridgeLocalUrl`, as its server URL too: a fact about
    /// the whole bridge that nothing a page does can fake) AND the web view is really showing the bundled origin (`shown`, see
    /// isOnBundledOrigin). Anything missing is "not the bundled page". The pure half of ServerConfigPlugin.callerIsBundledPage,
    /// used by save's reconnect and, since the Codex code review of the PR #38 prompt delta, by forget.
    static func isBundledPage(bridgeServerUrl: String?, bridgeLocalUrl: String?, shown: String?) -> Bool {
        guard let server = bridgeServerUrl, let local = bridgeLocalUrl else { return false }
        if server != local { return false }
        return isOnBundledOrigin(shown, local: local)
    }

    /// Whether `shown` (the web view's current URL) is on the bundled origin `local` (Capacitor's `capacitor://localhost` by
    /// default): the same scheme and host, no port, no user info. The second half of Android's DiscoveryRules.isBundledPage,
    /// used for the bundled-page-only paths (save's reconnect through the Tailscale address, and forget), so a page the bundled
    /// bridge might show anywhere else cannot use them either. nil or unparseable is "not the bundled page".
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

    /// Discovery may run only while the bundled page is committed. WKWebView.url can show a navigation's destination before
    /// it commits (the earlier addEndpoint High finding); isLoading stays true while that navigation is pending, so this
    /// guard does not depend on the back-forward list. Check its current item when WebKit gives one. The first bundled load
    /// may have no item, so nil is allowed only with the isLoading guard; every other missing or non-bundled value refuses.
    static func isCommittedBundledPage(shown: String?, committed: String?, isLoading: Bool, local: String?) -> Bool {
        if isLoading || !isOnBundledOrigin(shown, local: local) { return false }
        return committed == nil || isOnBundledOrigin(committed, local: local)
    }

    /// The check that needs the network's answers: the server the page is on (`reference`) and the offered address
    /// (`candidate`) both reported an id, and it is the same one. This is an identity CLAIM, not proof: /api/health is public,
    /// so a page that is itself hostile can name an address that repeats the id. That page is the server the person chose to
    /// use, so it could already show anything; what it cannot do is change the saved address. Since Tailscale first (owner
    /// decision 2026-11-09) the address it names is opened BEFORE the saved one whenever it answers with that id, where it used
    /// to be opened only when the saved one was not answering: still a validated secure origin offered by the server the person
    /// chose. A saved sign-in goes only to a Tailscale page, never a custom one (SessionRules). Because a
    /// plain-http page can be put there by the network, a NEW or CHANGED address offered from one is saved only after the person
    /// confirms it (needsConfirmation and PromptGate in this file; addendum of 2026-11-09, Codex code reviews of PR #38).
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

/// The gate in front of the "Use this secure address?" prompt (addendum of 2026-11-09 and its review). Pure: the glue
/// (AddressPrompt, ServerConfigPlugin.swift) holds the state, reads the clocks and shows the alert; every rule it applies is here,
/// so ios/Tests runs each row. Only reached when EndpointRules.needsConfirmation says to ask.
enum PromptGate {

    /// The full host is the one thing the person can compare. A custom domain is absent from Settings, so its instruction
    /// differs from the Tailscale instruction. This is pure so the swiftc rules runner checks the wording UIKit shows.
    static func promptBody(savedAddress: String, candidate: String) -> String {
        let host = candidate.hasPrefix("https://") ? String(candidate.dropFirst("https://".count)) : candidate
        let instruction: String
        if EndpointRules.isCustomUrl(candidate) {
            instruction = "This is your own address for this server. Continue only if you set up this address yourself."
        } else {
            instruction = "Only use it if you have just turned on the secure address in your server's Settings → Remote Access " +
                "and the name matches the one shown there."
        }
        return "Your server at \(savedAddress) offered this secure address:\n\n\(host)\n\n\(instruction)"
    }

    /// What to do with an offer that needs confirming.
    ///  - ask: show the prompt (the glue reserves the one prompt slot in the same locked step as this decision);
    ///  - rejectQuietly: refuse without asking, because the person declined this same address less than 7 days ago;
    ///  - busy: refuse without asking, because a prompt is showing now, or one was shown less than 10 minutes ago.
    /// Both refusals store nothing; the web app offers the address again at its next start.
    enum Decision {
        case ask
        case rejectQuietly
        case busy
    }

    /// How a prompt ended. ONLY `declined` is the person's explicit "Don't use it" tap. `notAnswered` is everything else: no
    /// window or root controller to show it on, another screen already on top, the app not active, a rebuild replacing the root
    /// under it (cancelPending), the 120 s timeout, or any other failure. A non-explicit outcome must never be remembered as a
    /// decline (addendum review): otherwise a hostile page that made the prompt fail could silence the real address.
    enum Outcome {
        case accepted
        case declined
        case notAnswered
    }

    /// A stored decline: the secure address the person declined for one saved server, and when (wall clock, milliseconds).
    struct DeclineRecord: Equatable {
        let candidate: String
        let atWallMs: Int64
    }

    /// What a prompt's outcome does to the stored decline: an accept clears it, an explicit decline stores (or replaces) it,
    /// anything else leaves it as it was.
    enum DeclineChange: Equatable {
        case keep
        case clear
        case store(DeclineRecord)
    }

    // How long an explicit decline keeps the SAME address from being asked about again: 7 days, on the WALL clock because it is
    // persisted and must survive restarts (a monotonic clock starts again at every boot). A judgement, not a measurement
    // (addendum review): the web app offers the address at every start, so without a memory a "no" would be asked again every
    // time; with a memory that never ended, a hostile page that got the REAL address declined first could silence it for good.
    // Seven days bounds that to a week, after which the person is asked once more.
    static let declineLifetimeMs: Int64 = 7 * 24 * 60 * 60 * 1_000

    // At most one prompt per this long in one run of the app, counted from when a prompt was SHOWN, on the monotonic clock. A
    // judgement, not a measurement (addendum): a hostile page that offers a new address every second must not be able to storm
    // the person with prompts; a refused offer is only "not saved", and the next start offers it again.
    static let cooldownMs: Int64 = 10 * 60 * 1_000

    // How long a prompt waits for an answer before it is withdrawn as not answered (no decline stored). A judgement, not a
    // measurement (addendum review): long enough to read it and check the server's Settings, short enough that the web app's
    // call (and the About card that waits for it) does not wait for ever.
    static let answerTimeoutMs: Int = 120_000

    /// Whether `decline` is a decline of THIS candidate that is still in force at `nowWallMs`: at least 0 and less than
    /// `declineLifetimeMs` old. A decline time in the future (the wall clock was set back) is not in force, so a clock change
    /// can only end a decline early (the person is asked again), never stretch it past a week.
    static func declineInForce(_ decline: DeclineRecord?, candidate: String, nowWallMs: Int64) -> Bool {
        guard let record = decline, record.candidate == candidate else { return false }
        let age: Int64 = nowWallMs - record.atWallMs
        return age >= 0 && age < declineLifetimeMs
    }

    /// The gate, in this order: a prompt showing -> busy; this same address declined less than 7 days ago -> rejectQuietly; a
    /// prompt shown less than 10 minutes ago (`lastPromptAtMs`, monotonic, nil = none yet in this run) -> busy; otherwise ask.
    /// The cooldown counts as busy, not as a quiet "no": nothing was declined, the person simply is not asked twice in ten minutes.
    static func decide(candidate: String, decline: DeclineRecord?, nowWallMs: Int64, lastPromptAtMs: Int64?, nowMs: Int64, showing: Bool) -> Decision {
        if showing { return .busy }
        if declineInForce(decline, candidate: candidate, nowWallMs: nowWallMs) { return .rejectQuietly }
        if let last = lastPromptAtMs, nowMs - last < cooldownMs { return .busy }
        return .ask
    }

    /// What `outcome` does to the stored decline for `candidate` (see DeclineChange). Only the explicit tap stores one.
    static func declineChange(after outcome: Outcome, candidate: String, nowWallMs: Int64) -> DeclineChange {
        switch outcome {
        case .accepted:
            return .clear
        case .declined:
            return .store(DeclineRecord(candidate: candidate, atWallMs: nowWallMs))
        case .notAnswered:
            return .keep
        }
    }

    /// The decision REPEATED at the write, on the writes queue, against the committed page and the saved secure address as they
    /// are then (Codex code review of the PR #38 prompt delta, High: the first decision could be made from a page the web view
    /// had only started to load, and the write re-checked the page's origin but not whether the offer needed asking). The write
    /// may go ahead when the person accepted it in a prompt, or when it does not need asking now. An offer that needs asking now
    /// but went through no accepted prompt is refused (notConfirmed: nothing written, no decline stored).
    static func mayWrite(acceptedInPrompt: Bool, needsConfirmationNow: Bool) -> Bool {
        return acceptedInPrompt || !needsConfirmationNow
    }

    /// The refusal for a gate decision, or nil when the prompt is to be shown.
    static func refusal(for decision: Decision) -> EndpointRules.AddCheck? {
        switch decision {
        case .ask:
            return nil
        case .rejectQuietly:
            return .notAccepted
        case .busy:
            return .notConfirmed
        }
    }

    /// The refusal for a prompt's outcome, or nil when the person accepted (the write then runs, as before the prompt existed).
    static func refusal(after outcome: Outcome) -> EndpointRules.AddCheck? {
        switch outcome {
        case .accepted:
            return nil
        case .declined:
            return .notAccepted
        case .notAnswered:
            return .notConfirmed
        }
    }
}
