import Foundation

// EndpointProbe.swift: reads a server's `/api/health` and says who answered (its id and name), or nil. Used to check that a
// Tailscale address the server offered is the SAME server the app has open (ServerConfig.addEndpoint): the ids must be equal,
// and at launch to check that the saved Tailscale address still answers with the id it was saved for (MainViewController).
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/EndpointProbe.kt, plus the pieces of DiscoveryRules.kt it
// needs (parseHealth, cleanName, isPlausibleId) and the launch liveness check that Android keeps in ServerConfigPlugin
// (isReachable). Foundation only, no Capacitor or UIKit import, so CI compiles it alone with swiftc (see EndpointRules.swift).
//
// The network part follows Android's rules with what URLSession can switch off: redirects are NOT followed (the 200 must come
// from that address itself), no proxy, no cookies stored or sent, no cache, and only the first maxBodyBytes of a body are
// kept. Android's stated cookie limit (Capacitor's process-wide cookie handler) does not exist here: an ephemeral session with
// no cookie storage stores nothing. The parsing is split from the network (acceptReply, parseHealth) so ios/Tests can run every
// rule without a network.
//
// Never throws; every failure is nil (or false).

enum EndpointProbe {

    // The same bound as Android: a health reply is a few hundred bytes, so 4 KB is far past any real one and small enough that
    // an address answering with something huge costs nothing.
    static let maxBodyBytes: Int = 4 * 1024

    // A display name comes from an unauthenticated reply, so it is cut to a length a card can show. 64 is a judgement carried
    // over from Android (#20), not a measurement: well past any real server name, short enough that a 10 000-character name
    // cannot push the rest of the row off the screen.
    static let maxNameChars: Int = 64

    // Combining marks stacked on one letter ("Zalgo" text) can draw far above and below the line and over the rest of the
    // row. Three is enough for any real script's accents; extra marks in a run are dropped (Android, code review of #20).
    private static let maxCombiningRun: Int = 3

    // Characters that are letters or symbols to Unicode but render as blank space, so a name made of them looks empty or pads
    // itself out (Android, code review of #20): Hangul fillers (U+115F, U+1160, U+3164, U+FFA0), the blank Braille pattern
    // (U+2800), the Khmer inherent vowels (U+17B4, U+17B5) and the Mongolian free variation selectors and vowel separator
    // (U+180B to U+180F).
    private static let blankLooking: Set<UInt32> = [0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F, 0x2800, 0x3164, 0xFFA0]

    // The server's id is `crypto.randomUUID()` (db.js). Anything that is not UUID-shaped did not come from a Nightlight server,
    // so it is never compared, let alone saved.
    private static let uuidRegex: NSRegularExpression =
        EndpointRules.makeRegex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /// True for a UUID-shaped string, the only form a server id takes. Whole-string match (a trailing line break is refused).
    static func isPlausibleId(_ id: String?) -> Bool {
        guard let value = id else { return false }
        return EndpointRules.wholeMatch(uuidRegex, value)
    }

    /// Parse a `/api/health` reply. The twin of Android's DiscoveryRules.parseHealth: accepts only a JSON object with `ok`
    /// exactly `true` (the boolean, not the string or the number 1), `app` exactly `"nightlight"` and a non-empty string `id`
    /// (serverIdentity.js healthBody). `name` is optional: absent or JSON null means "no name"; a string is cleaned
    /// (`cleanName`); any other type means this is not a reply we understand, so the whole body is refused. Unknown extra
    /// fields are allowed, so a newer server that adds one is still recognised. Anything else (an array, a bare value, wrong
    /// types, the fields nested one level down, truncated JSON, an empty body) returns nil.
    ///
    /// JSONSerialization refuses a body nested deeper than its own limit with an error, which is a refusal here, never a crash
    /// (`save` reads a reply of any size). Its strictness differs from Android's org.json in places (single quotes, trailing
    /// junk); that is safe for the reason Android's note gives: a leniently parsed reply is still only accepted with `ok` the
    /// boolean true, `app` exactly "nightlight" and an id the caller then compares.
    static func parseHealth(_ data: Data) -> ServerHealth? {
        if data.isEmpty { return nil }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            return nil
        }
        guard let obj = object as? [String: Any] else { return nil }
        // JSONSerialization gives a JSON boolean and a JSON number both as NSNumber, and Swift would read the number 1 as true,
        // so the type is checked: only the CFBoolean that a JSON `true` produces is accepted (Android refuses `"ok": 1` too).
        guard let okValue = obj["ok"] as? NSNumber, isJSONBoolean(okValue), okValue.boolValue else { return nil }
        guard let app = obj["app"] as? String, app == "nightlight" else { return nil }
        guard let id = obj["id"] as? String, !id.isEmpty else { return nil }
        var name: String? = nil
        if let rawName = obj["name"] {
            if rawName is NSNull {
                name = nil
            } else if let text = rawName as? String {
                name = cleanName(text)
            } else {
                return nil
            }
        }
        return ServerHealth(id: id, name: name)
    }

    private static func isJSONBoolean(_ value: NSNumber) -> Bool {
        return CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
    }

    /// A display name made safe to show: control characters, invisible format characters (including the right-to-left
    /// override, which can make a name read backwards), line or paragraph separators and characters that render blank removed,
    /// runs of combining marks cut to three, white space trimmed, then cut to `maxNameChars` Unicode scalars (code points, as
    /// Android counts; never half a character's UTF-16 pair). Empty after cleaning means no name, and the saved list then shows
    /// the address. Display only: the page also sets it with textContent, never as HTML.
    static func cleanName(_ raw: String) -> String? {
        var kept = ""
        var combiningRun = 0
        for scalar in raw.unicodeScalars {
            if isHiddenScalar(scalar) || blankLooking.contains(scalar.value) { continue }
            if isCombiningMark(scalar) {
                combiningRun += 1
                if combiningRun > maxCombiningRun { continue }
            } else {
                combiningRun = 0
            }
            kept.unicodeScalars.append(scalar)
        }
        let trimmed = kept.trimmingCharacters(in: .whitespacesAndNewlines)
        var capped = ""
        var count = 0
        for scalar in trimmed.unicodeScalars {
            if count == maxNameChars { break }
            capped.unicodeScalars.append(scalar)
            count += 1
        }
        let result = capped.trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty ? nil : result
    }

    // Java's Character.isISOControl (U+0000 to U+001F, U+007F to U+009F) plus the format, line separator, paragraph separator
    // and surrogate categories, as Android's isHiddenCodePoint.
    private static func isHiddenScalar(_ scalar: Unicode.Scalar) -> Bool {
        let v = scalar.value
        if v <= 0x1F || (v >= 0x7F && v <= 0x9F) { return true }
        switch scalar.properties.generalCategory {
        case .format, .lineSeparator, .paragraphSeparator, .surrogate:
            return true
        default:
            return false
        }
    }

    private static func isCombiningMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .enclosingMark, .spacingMark:
            return true
        default:
            return false
        }
    }

    /// The pure decision on a finished request, so a test reaches every rule without a network: the status must be 200, the
    /// answer must have come from the address that was asked (`sameAddress`, see `sameEndpoint`), the body at most
    /// `maxBodyBytes`, a reply `parseHealth` accepts, and the id UUID-shaped (as in discovery: an id that is not is not a
    /// Nightlight server's).
    static func acceptReply(status: Int, sameAddress: Bool, body: Data) -> ServerHealth? {
        if status != 200 { return nil }
        if !sameAddress { return nil }
        if body.count > maxBodyBytes { return nil }
        guard let health = parseHealth(body) else { return nil }
        if !isPlausibleId(health.id) { return nil }
        return health
    }

    /// Whether the response came from the address that was asked. The redirect refusal (HealthReader) is the first defence;
    /// this is the second, so a redirect followed for any reason (a delegate method the system did not call) still cannot
    /// pass. Scheme and host are compared without regard to case and the default port is filled in, because the system may
    /// tidy the request's URL (a host typed in capitals) and that must not refuse the right server.
    static func sameEndpoint(_ answered: URL?, _ asked: URL) -> Bool {
        guard let a = answered else { return false }
        let schemeA = (a.scheme ?? "").lowercased()
        let schemeB = (asked.scheme ?? "").lowercased()
        if schemeA != schemeB { return false }
        if (a.host ?? "").lowercased() != (asked.host ?? "").lowercased() { return false }
        if effectivePort(a, scheme: schemeA) != effectivePort(asked, scheme: schemeB) { return false }
        return a.path == asked.path
    }

    private static func effectivePort(_ url: URL, scheme: String) -> Int {
        if let port = url.port { return port }
        return scheme == "https" ? 443 : 80
    }

    /// `GET <url>/api/health` and the server's id and name, or nil. Blocks the calling thread for at most `timeout` seconds:
    /// the request's own timeouts AND a semaphore wait both end it, and a request still running then is cancelled and
    /// abandoned. Never call it on the main thread except from the launch choice, which has always waited there.
    static func fetchHealth(_ url: String, timeout: TimeInterval) -> ServerHealth? {
        guard let asked = URL(string: url + "/api/health") else { return nil }
        var request = URLRequest(url: asked, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpShouldHandleCookies = false
        let config = URLSessionConfiguration.ephemeral
        // An empty proxy dictionary: no proxy, as Android's Proxy.NO_PROXY (nil would mean "the system's settings").
        config.connectionProxyDictionary = [:]
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.httpCookieAcceptPolicy = .never
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let reader = HealthReader(asked: asked)
        let session = URLSession(configuration: config, delegate: reader, delegateQueue: nil)
        let task = session.dataTask(with: request)
        task.resume()
        let finished = reader.finished.wait(timeout: .now() + timeout)
        // Always invalidated: a session keeps its delegate alive until then. A request still running is cancelled with it.
        session.invalidateAndCancel()
        if finished == .timedOut { return nil }
        return reader.result()
    }

    /// The launch check for the SAVED address, as Android's ServerConfigPlugin.isReachable: `GET <url>/api/health` answers 200.
    /// Like Android it follows redirects and reads no body (the web view is about to open this address anyway); the identity
    /// check is only for the Tailscale address. Ephemeral, so it stores no cookie. Blocks for at most `timeout` seconds.
    static func isReachable(_ url: String, timeout: TimeInterval) -> Bool {
        guard let asked = URL(string: url + "/api/health") else { return false }
        var request = URLRequest(url: asked, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = "GET"
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        let session = URLSession(configuration: config)
        let answer = ReachableAnswer()
        let done = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { _, response, error in
            let ok = error == nil && (response as? HTTPURLResponse)?.statusCode == 200
            answer.set(ok)
            done.signal()
        }
        task.resume()
        let finished = done.wait(timeout: .now() + timeout)
        session.invalidateAndCancel()
        if finished == .timedOut { return false }
        return answer.get()
    }

    /// Whether `url` answers, as the launch choice counts it: the Tailscale address only if the server answering there still
    /// has the id it was saved for (`wantedId`; none saved = it does not count), so an address saved for one server can never
    /// open another (a saved address handed to a different machine, say); any other address with a 200 from `/api/health`
    /// (isReachable). Blocks for at most `timeout` seconds; never throws. ONE function, used by the launch choice
    /// (MainViewController.chooseAnswer) and by NetworkRoamWatcher's second look at the page on screen (Tailscale first, Codex
    /// plan review 2026-11-09), so the two can never judge an address differently. Here, not in MainViewController, so the
    /// watcher's queue calls plain Foundation code, never a method of a UIKit class.
    static func addressAnswers(_ url: String, tailscale: String, wantedId: String?, timeout: TimeInterval) -> Bool {
        return addressAnswers(url, tailscale: tailscale, wantedId: wantedId,
                              readHealth: { (address: String) -> ServerHealth? in
                                  return EndpointProbe.fetchHealth(address, timeout: timeout)
                              },
                              reachable: { (address: String) -> Bool in
                                  return EndpointProbe.isReachable(address, timeout: timeout)
                              })
    }

    /// The same decision with the two network reads passed in, so ios/Tests drives it with replies built from fixture bodies
    /// and no network (the split acceptReply already makes: the decision apart from the request; Codex code review of PR #38).
    /// `readHealth` is fetchHealth for the Tailscale address, `reachable` is isReachable for any other address.
    static func addressAnswers(_ url: String, tailscale: String, wantedId: String?, readHealth: (String) -> ServerHealth?, reachable: (String) -> Bool) -> Bool {
        if url == tailscale {
            // No id saved: it never counts, and nothing is asked (fail closed; a missing id must not compare equal to a
            // missing reply).
            guard let wanted = wantedId else { return false }
            let live: String? = readHealth(url)?.id
            return live == wanted
        }
        return reachable(url)
    }
}

/// The answer of one isReachable request, written on the session's queue and read on the caller's, so it is locked.
final class ReachableAnswer {
    private let lock = NSLock()
    private var ok = false

    func set(_ value: Bool) {
        lock.lock()
        ok = value
        lock.unlock()
    }

    func get() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return ok
    }
}

/// The delegate of one fetchHealth request: refuses every redirect, stops reading past maxBodyBytes, and records how the
/// request ended. Its callbacks run on the session's own serial queue and `result()` on the caller's thread, so everything it
/// keeps is behind one lock.
final class HealthReader: NSObject, URLSessionDataDelegate {
    let finished = DispatchSemaphore(value: 0)
    private let asked: URL
    private let lock = NSLock()
    private var body = Data()
    private var tooBig = false
    private var status = 0
    private var sameAddress = false
    private var failed = false
    private var done = false

    init(asked: URL) {
        self.asked = asked
        super.init()
    }

    // nil: the redirect is not followed. The task then ends with the 3xx answer itself, which acceptReply refuses (not 200).
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    // Stops at once on anything but a 200, or a declared length past the bound, instead of reading a body that would be
    // refused anyway. (If this callback did not run, the checks in didReceive data and acceptReply still refuse.)
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let declared: Int64 = response.expectedContentLength
        if code != 200 || declared > Int64(EndpointProbe.maxBodyBytes) {
            completionHandler(.cancel)
        } else {
            completionHandler(.allow)
        }
    }

    // Keeps at most maxBodyBytes + 1 chunk: past the bound the body is dropped, marked too big, and the request cancelled.
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if !tooBig {
            body.append(data)
            if body.count > EndpointProbe.maxBodyBytes {
                tooBig = true
                body = Data()
            }
        }
        let stop = tooBig
        lock.unlock()
        if stop {
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let http = task.response as? HTTPURLResponse
        lock.lock()
        status = http?.statusCode ?? 0
        sameAddress = EndpointProbe.sameEndpoint(http?.url, asked)
        failed = error != nil
        done = true
        lock.unlock()
        finished.signal()
    }

    func result() -> ServerHealth? {
        lock.lock()
        defer { lock.unlock() }
        if !done || failed || tooBig { return nil }
        return EndpointProbe.acceptReply(status: status, sameAddress: sameAddress, body: body)
    }
}
