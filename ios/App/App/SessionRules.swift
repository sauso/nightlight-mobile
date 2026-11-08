import Foundation

// SessionRules.swift: the rules for keeping ONE sign-in across a server's two addresses on an iPhone (owner, 2026-11-08: iOS
// phase 2, part 1). The session token is also kept in the iOS Keychain (SessionVault over KeychainSessionStore), so the page on
// the Tailscale address can sign in without asking again.
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/SessionRules.kt (Android plan phase D, reviewed by Codex, Opus
// and agy before it was built), ported rule for rule with the same seven delivery outcomes. Do not redesign it here: a change
// to a rule belongs in both apps.
//
// Foundation only, no Capacitor, UIKit or Security import, on purpose: CI compiles this file with swiftc and runs ios/Tests
// against exactly this code (build-ios.yml). Every function fails CLOSED.
//
// THE THREAT THESE RULES ANSWER. A server's id is public (`/api/health`, the mDNS announcement), so an id proves nothing: a
// hostile device at the saved plain-http address, or an https saved address on a hijacked domain, can repeat it. So the stored
// session is handed ONLY to the page that is the saved TAILSCALE address (an `https://<name>.ts.net` name with a certificate,
// whose id was checked when it was added), and only while everything the entry was stamped with is still in force (the saved
// address, the Tailscale address, the Tailscale id) AND that page's live id still equals it.
//
// ONE origin parser: the page checks use phase 1's EndpointRules.sameOrigin / isTrustedPage / isTailscaleUrl, never a second
// parser, so the session rules and addEndpoint can never disagree about what "the same page" is. (Android keeps its sameOrigin
// in SessionRules.kt; on iOS it was ported into EndpointRules.swift in phase 1, with the same pattern and the same tests.)

enum SessionRules {

    // A Nightlight session token is a JWT (three base64url parts). 4 KB is far past a real one (about 250 characters); it only
    // bounds what a page can make the app store. Counted in UTF-16 units, as Kotlin's String.length counts.
    static let maxTokenChars: Int = 4096

    private static let tokenRegex: NSRegularExpression =
        EndpointRules.makeRegex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /// True for something shaped like a session token: at most `maxTokenChars`, and exactly three base64url parts. The WHOLE
    /// string must match (EndpointRules.wholeMatch): NSRegularExpression's `$` also matches just before a final line break,
    /// Kotlin's `matches` does not, so `a.b.c\n` would otherwise pass here and fail on Android.
    static func isPlausibleToken(_ token: String?) -> Bool {
        guard let value = token else { return false }
        if value.utf16.count > maxTokenChars { return false }
        return EndpointRules.wholeMatch(tokenRegex, value)
    }

    /// What an entry is stamped with when its token is saved: the saved address (also its slot), and the Tailscale address and
    /// id that were in force then (nil when none was saved yet: such an entry is never handed out, and is stamped again by the
    /// next save, which the web app makes after it has learned the Tailscale address).
    struct Bindings: Equatable {
        let savedUrl: String
        let tailscaleUrl: String?
        let tailscaleId: String?
    }

    /// What is stored: the token and the bindings it was saved under.
    struct Entry: Equatable {
        let token: String
        let bindings: Bindings
    }

    /// The bindings a token is stamped with when the web app saves it. The web app says which Tailscale address ITS OWN SERVER
    /// just offered (`claimedTailscale`, read from the authenticated `GET /api/remote-access/tailscale/address`; nil when it could
    /// not be read or the server offers none). The stamp keeps the Tailscale address and id only when the two agree. The claim
    /// is read as addEndpoint reads an offered address (EndpointRules.offeredAddress: white space and trailing slashes removed),
    /// the twin of Android's `trim().trimEnd('/')`.
    ///
    /// WHY (Opus code review of Android phase D): `addEndpoint` can be called by the page at the SAVED address, and an id is
    /// public, so a hostile device that answered there once could plant an attacker's `https://<x>.ts.net` as the saved
    /// Tailscale address. If the person's next real sign-in were stamped with whatever is saved, the token would be handed to the
    /// attacker's page. The real server never offers the planted address, so the claim differs, the stamp carries no Tailscale
    /// binding, and the entry is never handed out (it fails closed; the next sign-in or start, once the real address has replaced
    /// the planted one, saves it again). An older web app that sends no claim gets no binding either.
    static func stampFor(_ current: Bindings, claimedTailscale: String?) -> Bindings {
        let claimed: String? = EndpointRules.offeredAddress(claimedTailscale)
        if let value = claimed, let saved = current.tailscaleUrl, value == saved {
            return current
        }
        return Bindings(savedUrl: current.savedUrl, tailscaleUrl: nil, tailscaleId: nil)
    }

    /// The stamp saveSession writes, decided ON the writes queue: `checked` is what the page check was made against BEFORE the
    /// call was queued, `now` is what is saved at the moment of the write. nil (do not save) unless the two are still equal.
    ///
    /// WHY (Codex plan review of iOS phase 2, 2026-11-08): a server switch landing between the check and the write would
    /// otherwise stamp one server's token into the OTHER server's slot (the slot is `now.savedUrl`). It could not leak (the entry
    /// would still have to pass checkDelivery for that server), but it would overwrite and so lose that server's real saved
    /// sign-in. The same gap exists on Android, where it is noted and not changed by this port.
    static func stampOnQueue(checked: Bindings, now: Bindings?, claimedTailscale: String?) -> Bindings? {
        guard let current = now, current == checked else { return nil }
        return stampFor(current, claimedTailscale: claimedTailscale)
    }

    /// Whether a call from the page the web view is really showing (`webViewUrl`) may SAVE a session or ASK for one: there is a
    /// saved server, the bridge's server is the saved address or the Tailscale address saved for it (`bridgeUrl`, exact string),
    /// AND the page shown has that same origin. Exactly phase 1's EndpointRules.isTrustedPage, which is Android's mayAct
    /// (isActivePage + sameOrigin); on iOS the shown-origin half is what stops the prefix navigation Capacitor iOS allows
    /// (`https://server.example.attacker.test` under a bridge built for `https://server.example`, review of PR #32).
    static func mayAct(_ current: Bindings?, bridgeUrl: String?, webViewUrl: String?) -> Bool {
        guard let now = current else { return false }
        return EndpointRules.isTrustedPage(primary: now.savedUrl, savedTailscale: now.tailscaleUrl, page: bridgeUrl, shown: webViewUrl)
    }

    /// Whether a call may CLEAR the saved session. Looser than `mayAct` on purpose: a clear only deletes (and needs the stored
    /// token when one is given), and it must still work after the Tailscale address was removed or replaced while a page on it is
    /// open, or a sign-out there would leave the entry behind (agy's review of Android phase D). So: a saved server, a real server
    /// page (the bridge was built for one, so the bundled setup page is refused) and a page with that same origin.
    static func mayClear(_ current: Bindings?, bridgeUrl: String?, webViewUrl: String?) -> Bool {
        guard current != nil, let bridge = bridgeUrl else { return false }
        return EndpointRules.sameOrigin(webViewUrl, bridge)
    }

    /// Whether a request for the session comes from the page that IS the saved Tailscale address (and is really showing it).
    /// Anything else is told "no session" without a network call. `checkDelivery` then decides on the entry itself.
    static func isTailscalePage(_ current: Bindings?, bridgeUrl: String?, webViewUrl: String?) -> Bool {
        guard let now = current, let bridge = bridgeUrl, let tailscale = now.tailscaleUrl else { return false }
        if bridge != tailscale { return false }
        if !EndpointRules.isTailscaleUrl(bridge) { return false }
        return EndpointRules.sameOrigin(webViewUrl, bridge)
    }

    /// Why a stored session was or was not handed to a page. Android's seven outcomes, so a test can tell the refusals apart.
    enum Delivery {
        case ok
        case notActivePage
        case notTheTailscalePage
        case noEntry
        case noTailscaleBinding
        case bindingsChanged
        case idMismatch
    }

    /// Whether the stored `entry` may be handed to `page`.
    ///  - `current` is the saved address and the Tailscale address and id saved for it RIGHT NOW (nil when nothing is saved);
    ///  - `liveId` is the id the page's own server just reported at `/api/health` (nil when it could not be read).
    /// Every condition must hold; the first that does not is the answer.
    static func checkDelivery(page: String?, current: Bindings?, entry: Entry?, liveId: String?) -> Delivery {
        guard let pageUrl = page, let now = current else { return .notActivePage }
        // Only the Tailscale address is ever handed a session: never the saved address (http, or an https one that could have
        // changed hands) and never anything else.
        guard let tailscale = now.tailscaleUrl, pageUrl == tailscale, EndpointRules.isTailscaleUrl(pageUrl) else {
            return .notTheTailscalePage
        }
        guard let stored = entry else { return .noEntry }
        // An entry saved before the Tailscale address was learned carries no binding, so it cannot be matched to anything.
        if stored.bindings.tailscaleUrl == nil || stored.bindings.tailscaleId == nil { return .noTailscaleBinding }
        guard let wantedId = now.tailscaleId else { return .noTailscaleBinding }
        // Changing the saved address or the Tailscale address (or its id) after the token was saved makes the entry fail closed:
        // only saveSession(token), which needs the token, can stamp it again.
        if stored.bindings != now { return .bindingsChanged }
        guard let live = liveId, live == wantedId else { return .idMismatch }
        return .ok
    }
}
