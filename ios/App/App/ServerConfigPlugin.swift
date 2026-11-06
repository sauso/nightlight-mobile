import Foundation
import Capacitor
import UIKit
import WebKit

/// iOS counterpart of `ServerConfigPlugin.kt`. Which Nightlight server the app talks
/// to is chosen at runtime (the Home Assistant-style first-run flow), not baked in.
///
/// From the web side (same plugin + method names as Android, so `nativeBridge.js`
/// works unchanged):
///
///   Capacitor.Plugins.ServerConfig.get()          -> { url: string | null, tailscale: string | null }
///   Capacitor.Plugins.ServerConfig.list()         -> { servers: string[], active: string | null,
///                                                      tailscale: string | null, names: { [url]: name } }
///   Capacitor.Plugins.ServerConfig.save({ url })  -> validates + persists, rejects if unreachable; resolves { url },
///                                                    or { url, via: "tailscale" } when it reconnected a remembered
///                                                    server through its Tailscale address (see save)
///   Capacitor.Plugins.ServerConfig.addEndpoint({ url })  -> { added }; saves the server's Tailscale address
///   Capacitor.Plugins.ServerConfig.removeEndpoint()      -> { removed }
///   Capacitor.Plugins.ServerConfig.forget({ url })
///   Capacitor.Plugins.ServerConfig.clear()
///   Capacitor.Plugins.ServerConfig.restart()      -> reloads the bridge so the current choice takes effect
///
/// The shapes are exactly Android's: the web app turns its Tailscale features on by whether `addEndpoint` and
/// `list().names` exist (nativeBridge.js), so the About card, the "Away from home" notice and the sync work here as
/// they do on Android. NOT on iOS yet, deliberately absent so the web app skips them: saveSession / getSession /
/// clearSession (one sign-in across both addresses; `saveNativeSession` is then a no-op) and startDiscovery /
/// stopDiscovery ("Look for my Nightlight").
///
/// The launch-time "which address to open" lives in MainViewController.instanceDescriptor; this plugin owns the stored
/// values it reads.

/// Persisted server selection, shared between the plugin and the bridge view controller.
enum ServerConfigStore {
    private static let defaults = UserDefaults.standard
    private static let keyURL = "nightlight_server_url"
    private static let keyKnown = "nightlight_known_servers"
    // The display name each remembered server sent (its public /api/health `name`), as one JSON string { address: name }, so
    // the setup page can list "Name" with the address under it (owner, 2026-11-06). The same key and shape as Android's
    // KEY_NAMES. Only for remembered servers (EndpointRules.mayRememberName).
    private static let keyNames = "server_names"

    // ONE first-in-first-out queue for every write that reads, changes and writes back (the saved address, the known list,
    // the names, the Tailscale pair), the twin of Android's session executor: two saves finishing together cannot lose each
    // other's list entry, and a write cannot overtake the one queued before it. Reads (get, list, the launch) read directly:
    // UserDefaults is safe to read from any thread.
    static let writes = DispatchQueue(label: "com.sauso.nightlight.server-config")

    static var savedURL: String? {
        get {
            let value = defaults.string(forKey: keyURL)
            return (value?.isEmpty ?? true) ? nil : value
        }
        set {
            if let value = newValue, !value.isEmpty {
                defaults.set(value, forKey: keyURL)
            } else {
                defaults.removeObject(forKey: keyURL)
            }
        }
    }

    /// Every server that has ever validated, most-recently-used first.
    static var knownServers: [String] {
        get { defaults.stringArray(forKey: keyKnown) ?? [] }
        set { defaults.set(newValue, forKey: keyKnown) }
    }

    static func remember(_ url: String) {
        var list = knownServers
        list.removeAll { $0 == url }
        list.insert(url, at: 0)
        knownServers = list
    }

    /// Drop a server from the remembered list. Its name and its Tailscale address (with the id it was saved for) go with it,
    /// as on Android: they were only ever meaningful for that server.
    static func forget(_ url: String) {
        var list = knownServers
        list.removeAll { $0 == url }
        knownServers = list
        var names = storedNames
        names.removeValue(forKey: url)
        writeNames(names)
        forgetTailscale(for: url)
    }

    /// The server's Tailscale address that goes with the saved server `primary`, or nil. Re-checked on read: only an address
    /// of the one accepted shape ever reaches the launch choice or the page.
    static func tailscaleURL(for primary: String) -> String? {
        let value = defaults.string(forKey: EndpointRules.key(primary))
        return EndpointRules.isTailscaleUrl(value) ? value : nil
    }

    /// The server id the saved Tailscale address was saved for (EndpointRules.idKey), or nil.
    static func tailscaleId(for primary: String) -> String? {
        return defaults.string(forKey: EndpointRules.idKey(primary))
    }

    static func setTailscale(_ url: String, id: String, for primary: String) {
        defaults.set(url, forKey: EndpointRules.key(primary))
        defaults.set(id, forKey: EndpointRules.idKey(primary))
    }

    /// The Tailscale address saved for `primary`, with the server id it was saved for.
    static func forgetTailscale(for primary: String) {
        defaults.removeObject(forKey: EndpointRules.key(primary))
        defaults.removeObject(forKey: EndpointRules.idKey(primary))
    }

    /// The stored names; a corrupt entry reads as no names (the list then falls back to addresses).
    static var storedNames: [String: String] {
        guard let text = defaults.string(forKey: keyNames), let data = text.data(using: .utf8) else { return [:] }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            return [:]
        }
        guard let dict = object as? [String: Any] else { return [:] }
        var out: [String: String] = [:]
        for (url, value) in dict {
            if let name = value as? String, !EndpointRules.isBlank(name) {
                out[url] = name
            }
        }
        return out
    }

    /// Remember the name a server sent. Called after remember(url), so the url is in the saved list; on the writes queue like
    /// the other writes (a read-modify-write of one JSON value).
    static func rememberName(_ url: String, _ name: String?) {
        if !EndpointRules.mayRememberName(knownServers: knownServers, url: url, name: name) { return }
        guard let value = name else { return }
        var names = storedNames
        names[url] = value
        writeNames(names)
    }

    private static func writeNames(_ names: [String: String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: names, options: []) else { return }
        guard let text = String(data: data, encoding: .utf8) else { return }
        defaults.set(text, forKey: keyNames)
    }
}

@objc(ServerConfigPlugin)
public class ServerConfigPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ServerConfigPlugin"
    public let jsName = "ServerConfig"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "get", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "save", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "forget", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clear", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restart", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addEndpoint", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeEndpoint", returnType: CAPPluginReturnPromise),
    ]

    // The probe timeouts, in seconds, carried over from Android's ServerConfigPlugin (fetchHealth 4000 / 5000 / 3000 ms):
    // judgements, not measurements. The page's own server answers quickly (4 s); the offered Tailscale address may be a
    // relayed link on cellular (5 s); the id check at a home address that just answered is a second look (3 s).
    private static let pageProbeTimeout: TimeInterval = 4
    private static let tailscaleProbeTimeout: TimeInterval = 5
    private static let pairCheckTimeout: TimeInterval = 3

    // The address the page calling us was loaded from, as far as iOS can say: the server URL this bridge was BUILT with
    // (MainViewController.instanceDescriptor sets it, and bridge.config keeps it), or nil for the bundled setup page, which is
    // built with none (Capacitor then reports the bundled origin as config.serverURL, the same URL as config.localURL). The
    // twin of Android's `bridge.serverUrl`. nil too when there is no bridge, which every rule treats as "not the server's page".
    //
    // NOT enough on its own (review of PR #32, High): Capacitor iOS lets the web view navigate inside the app to any URL that
    // merely STARTS WITH this server URL (`https://server.example.attacker.test` for `https://server.example`), so what the
    // bridge was built with is not where the page is now. Every trust decision therefore also checks the web view's current
    // URL (shownPageURL, EndpointRules.isTrustedPage / isOnBundledOrigin).
    //
    // Known limit, stated: Capacitor iOS cannot tell WHICH FRAME called a plugin method (the plugin is given the call, not the
    // frame it came from), so a child frame under an accepted page counts as that page. Nightlight's own pages use no frames
    // and its CSP forbids a cross-origin one, so this is a conditional gap (README, as for Android's old-WebView bridge).
    //
    // The URLs are read into optionals on purpose: the assignment compiles whether Capacitor declares them optional or not.
    private func pageServerURL() -> String? {
        guard let config = bridge?.config else { return nil }
        let server: URL? = config.serverURL
        let local: URL? = config.localURL
        guard let serverText = server?.absoluteString, !serverText.isEmpty else { return nil }
        if let localText = local?.absoluteString, localText == serverText { return nil }
        return EndpointRules.withoutTrailingSlashes(serverText)
    }

    // Whether the page calling us is the bundled setup page (public/index.html). Both must hold, as in Android's
    // DiscoveryRules.isBundledPage: (1) the bridge was built with no server URL, a fact about the whole bridge that nothing a
    // page does can fake; (2) the web view is really showing the bundled origin (EndpointRules.isOnBundledOrigin), so the
    // prefix navigation described at pageServerURL cannot lend this path to another page either. Fails closed: no bridge, an
    // unreadable config or an unreadable page URL is "not the bundled page".
    private func callerIsBundledPage() -> Bool {
        guard let config = bridge?.config else { return false }
        let server: URL? = config.serverURL
        let local: URL? = config.localURL
        guard let serverText = server?.absoluteString, let localText = local?.absoluteString else { return false }
        if serverText != localText { return false }
        return EndpointRules.isOnBundledOrigin(shownPageURL(), local: localText)
    }

    // The URL the web view's main frame is showing RIGHT NOW, or nil (no bridge, no web view, nothing loaded). WKWebView may
    // only be read on the main thread. Plugin methods run on Capacitor's plugin queue and save's reconnect on a URLSession
    // queue, so the read is handed to the main thread with a sync; when already on the main thread it is read directly, never
    // through a sync from the main thread onto itself (that would deadlock). Nothing here waits on the plugin queue from the
    // main thread, so the sync cannot deadlock; at worst it waits for a launch choice in progress (up to 3 s).
    private func shownPageURL() -> String? {
        guard let b = bridge else { return nil }
        if Thread.isMainThread {
            return b.webView?.url?.absoluteString
        }
        let read: String? = DispatchQueue.main.sync { () -> String? in
            return b.webView?.url?.absoluteString
        }
        return read
    }

    // `url` is the saved (primary) address; `tailscale` is the Tailscale address the server told this phone about, or null.
    // Which one the page was loaded from is NOT said here: the page knows its own origin.
    @objc func get(_ call: CAPPluginCall) {
        let saved = ServerConfigStore.savedURL
        var urlValue: Any = NSNull()
        var tailscaleValue: Any = NSNull()
        if let primary = saved {
            urlValue = primary
            if let tailscale = ServerConfigStore.tailscaleURL(for: primary) {
                tailscaleValue = tailscale
            }
        }
        call.resolve(["url": urlValue, "tailscale": tailscaleValue])
    }

    // Previously used servers plus which one is currently active, for the setup screen's one-tap list. `names` and
    // `tailscale` are added alongside, as on Android (an older page ignores them): only remembered servers' names, so a
    // forgotten server's name is never listed.
    @objc func list(_ call: CAPPluginCall) {
        let known = ServerConfigStore.knownServers
        let names = EndpointRules.namesForKnown(stored: ServerConfigStore.storedNames, knownServers: known)
        let active = ServerConfigStore.savedURL
        var activeValue: Any = NSNull()
        var tailscaleValue: Any = NSNull()
        if let primary = active {
            activeValue = primary
            if let tailscale = ServerConfigStore.tailscaleURL(for: primary) {
                tailscaleValue = tailscale
            }
        }
        call.resolve([
            "servers": known,
            "names": names,
            "active": activeValue,
            "tailscale": tailscaleValue,
        ])
    }

    // Drop a server from the remembered list (does not touch the active choice). Its name and Tailscale address go with it.
    @objc func forget(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else {
            call.resolve()
            return
        }
        ServerConfigStore.writes.async {
            ServerConfigStore.forget(url)
            call.resolve()
        }
    }

    // Clears only the ACTIVE choice, deliberately keeping the remembered list -
    // "Change server" should land on a screen offering your other servers.
    //
    // The Tailscale address is KEPT (owner, 2026-11-06, as on Android). Change server, used while on the Tailscale address
    // (away from home), would otherwise throw away the only way back in: the setup page lists the saved HOME address, which
    // cannot answer on cellular. The stale-address risk is closed where it arises instead: save() compares the id at the home
    // address with the one the Tailscale address was saved for and drops the pair when another server answers there
    // (EndpointRules.pairStillValid). Known limit, as on Android: that runs on a save from the setup screen, not at every
    // launch, so a home address that changed hands can still open the old server through Tailscale until it is next connected
    // to there. forget(url), the explicit "forget this server", still removes it.
    @objc func clear(_ call: CAPPluginCall) {
        // On the writes queue, so a save queued just before this one has landed first; resolved after, so the restart the
        // page asks for next starts from the cleared state.
        ServerConfigStore.writes.async {
            ServerConfigStore.savedURL = nil
            call.resolve()
        }
    }

    // addEndpoint({ url }): the server on screen has said "you can also reach me at <url>" (the web app read it from
    // GET /api/remote-access/tailscale/address while signed in). Saved only when ALL of these hold, each fails closed (the
    // decisions are EndpointRules.checkPage and checkIdentity, run by ios/Tests in CI):
    //   - the url is exactly `https://<name>.ts.net`: no port, path, credentials or IP;
    //   - the bridge was built for the saved server or the Tailscale address already saved for it (pageServerURL), AND the web
    //     view is really showing that origin (shownPageURL; EndpointRules.isTrustedPage says why prefix navigation makes this
    //     necessary), so the bundled setup page, a page that wandered to another host or port, and anything else are refused;
    //   - the page's server and the url answer /api/health with the SAME id (EndpointProbe): an address that merely looks right
    //     is not saved for a server that is not this one. The id is public, so this is a claim, not proof (see checkIdentity);
    //     it is saved with the address and re-checked each time the address is opened.
    // Resolves { added }: true when this is new or different, false when it was already saved (so the app tells the person
    // once). Nothing is switched or restarted here: the address is used the next time the app starts. The refusal sentences
    // are Android's, word for word (EndpointRules.refusal): the web app's About card reads them.
    @objc func addEndpoint(_ call: CAPPluginCall) {
        let url = EndpointRules.offeredAddress(call.getString("url"))
        let primary = ServerConfigStore.savedURL
        let page = pageServerURL()
        let shown = shownPageURL()
        var savedTailscale: String? = nil
        if let home = primary {
            savedTailscale = ServerConfigStore.tailscaleURL(for: home)
        }
        let first = EndpointRules.checkPage(url: url, primary: primary, savedTailscale: savedTailscale, page: page, shown: shown)
        guard first == .ok, let offered = url, let home = primary, let pageURL = page else {
            call.reject(EndpointRules.refusal(first == .ok ? .notThisPage : first))
            return
        }
        // Off the plugin queue: two health checks can take seconds, and other plugin calls must not wait behind them.
        DispatchQueue.global(qos: .userInitiated).async {
            let reference = EndpointProbe.fetchHealth(pageURL, timeout: ServerConfigPlugin.pageProbeTimeout)
            let candidate = EndpointProbe.fetchHealth(offered, timeout: ServerConfigPlugin.tailscaleProbeTimeout)
            let second = EndpointRules.checkIdentity(reference: reference, candidate: candidate)
            guard second == .ok, let confirmed = candidate else {
                call.reject(EndpointRules.refusal(second == .ok ? .unreachableAddress : second))
                return
            }
            ServerConfigStore.writes.async {
                let previous = ServerConfigStore.tailscaleURL(for: home)
                ServerConfigStore.setTailscale(offered, id: confirmed.id, for: home)
                // The saved server's name, learned for free here: this runs at every start while signed in, so servers saved
                // before names were kept get theirs without any action (the page's server and the saved address are the same
                // server).
                let pageName: String? = reference?.name
                ServerConfigStore.rememberName(home, pageName)
                call.resolve(["added": previous != offered])
            }
        }
    }

    // removeEndpoint(): the server has said it no longer offers a Tailscale address (Tailscale was turned off there). Same
    // page rule as addEndpoint (EndpointRules.isTrustedPage, the web view's current origin included). Resolves { removed }.
    @objc func removeEndpoint(_ call: CAPPluginCall) {
        let primary = ServerConfigStore.savedURL
        var saved: String? = nil
        if let home = primary {
            saved = ServerConfigStore.tailscaleURL(for: home)
        }
        let trusted = EndpointRules.isTrustedPage(primary: primary, savedTailscale: saved, page: pageServerURL(), shown: shownPageURL())
        guard let home = primary, trusted else {
            call.reject(EndpointRules.refusal(.notThisPage))
            return
        }
        let hadOne = saved != nil
        ServerConfigStore.writes.async {
            if hadOne {
                ServerConfigStore.forgetTailscale(for: home)
            }
            call.resolve(["removed": hadOne])
        }
    }

    // Confirms the address actually hosts a reachable Nightlight server (its
    // unauthenticated /api/health endpoint) before persisting. Scheme handling
    // mirrors Android: an explicit scheme is respected; a bare address tries https
    // first then http (self-hosted LAN servers are commonly plain-http).
    @objc func save(_ call: CAPPluginCall) {
        let raw = (call.getString("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty {
            call.reject("Enter your server address")
            return
        }
        var bare = raw
        while bare.hasSuffix("/") { bare.removeLast() }

        let candidates: [String]
        if bare.contains("://") {
            guard bare.hasPrefix("https://") || bare.hasPrefix("http://") else {
                call.reject("The address must start with https:// or http://")
                return
            }
            candidates = [bare]
        } else {
            candidates = ["https://\(bare)", "http://\(bare)"]
        }

        validate(candidates, index: 0, call: call)
    }

    private func validate(_ candidates: [String], index: Int, call: CAPPluginCall) {
        if index >= candidates.count {
            reconnectOrRefuse(candidates, call: call)
            return
        }
        let base = candidates[index]
        guard let url = URL(string: "\(base)/api/health") else {
            validate(candidates, index: index + 1, call: call)
            return
        }
        var request = URLRequest(url: url, timeoutInterval: 5)
        request.httpMethod = "GET"
        URLSession.shared.dataTask(with: request) { [weak self] data, response, _ in
            let ok = (response as? HTTPURLResponse)?.statusCode == 200
                && (data.flatMap { String(data: $0, encoding: .utf8) }?.contains("\"ok\"") ?? false)
            if ok {
                let body: Data = data ?? Data()
                // Off the session's queue: the pair check below can wait seconds for a second request.
                DispatchQueue.global(qos: .userInitiated).async {
                    ServerConfigPlugin.confirmSaved(base, body: body, call: call)
                }
            } else {
                // Try the next candidate scheme, if any.
                self?.validate(candidates, index: index + 1, call: call)
            }
        }.resume()
    }

    // The address answered: save it. A Tailscale address saved for this address belongs to the server it was saved for, so
    // who answers here now is read (only when there is a pair to check) and the pair is dropped when it is ANOTHER server
    // (EndpointRules.pairStillValid: a readable different id only). The name the server sent in the same reply is kept
    // (nil when it sent none, or an older server with no id).
    private static func confirmSaved(_ url: String, body: Data, call: CAPPluginCall) {
        let hasPair = ServerConfigStore.tailscaleId(for: url) != nil
        let liveId: String? = hasPair ? EndpointProbe.fetchHealth(url, timeout: ServerConfigPlugin.pairCheckTimeout)?.id : nil
        let name: String? = EndpointProbe.parseHealth(body)?.name
        ServerConfigStore.writes.async {
            ServerConfigStore.savedURL = url
            if !EndpointRules.pairStillValid(wantedId: ServerConfigStore.tailscaleId(for: url), liveId: liveId) {
                ServerConfigStore.forgetTailscale(for: url)
            }
            ServerConfigStore.remember(url)
            ServerConfigStore.rememberName(url, name)
            call.resolve(["url": url])
        }
    }

    // None answered. A REMEMBERED server that has a Tailscale address can still be reached through it: this is how "Change
    // server" gets back in from cellular, where the home address cannot answer (owner, 2026-11-06). The saved address stays the
    // one the person picked; the launch choice then opens the Tailscale address because the saved one is not answering.
    // ONLY from the bundled setup page, where Change server lands: any page can call save, and this path lets a call switch the
    // active server to a remembered one it could not reach before, so a page on the current server must not have it (Codex
    // review, Android). Such a page could already switch to a remembered server that answers; this does not widen that.
    private func reconnectOrRefuse(_ candidates: [String], call: CAPPluginCall) {
        let known = ServerConfigStore.knownServers
        let remembered: String? = candidates.first(where: { known.contains($0) })
        let tailscale: String? = remembered.flatMap { ServerConfigStore.tailscaleURL(for: $0) }
        guard let home = remembered, let viaTailscale = tailscale, callerIsBundledPage() else {
            call.reject("Couldn't reach a Nightlight server at that address")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let live = EndpointProbe.fetchHealth(viaTailscale, timeout: ServerConfigPlugin.tailscaleProbeTimeout)
            let wanted = ServerConfigStore.tailscaleId(for: home)
            let liveId: String? = live?.id
            let liveName: String? = live?.name
            if EndpointRules.canReconnectViaTailscale(knownServers: known, url: home, tailscaleUrl: viaTailscale, wantedId: wanted, liveId: liveId) {
                ServerConfigStore.writes.async {
                    ServerConfigStore.savedURL = home
                    ServerConfigStore.remember(home)
                    ServerConfigStore.rememberName(home, liveName)
                    call.resolve(["url": home, "via": "tailscale"])
                }
            } else {
                call.reject("Couldn't reach a Nightlight server at that address, or through its Tailscale address")
            }
        }
    }

    // Reloads the app so the current saved choice takes effect - the iOS equivalent
    // of Android recreating the activity. Rebuilding the root view controller re-runs
    // the bridge's server-URL selection (MainViewController.instanceDescriptor, which
    // may wait up to 3 s for the server's addresses, as at launch).
    @objc func restart(_ call: CAPPluginCall) {
        call.resolve()
        DispatchQueue.main.async { [weak self] in
            guard let window = self?.bridge?.viewController?.view.window else { return }
            let storyboard = UIStoryboard(name: "Main", bundle: nil)
            window.rootViewController = storyboard.instantiateInitialViewController()
        }
    }
}
