import Foundation
import Capacitor
import UIKit
import WebKit
import Network

/// iOS counterpart of `ServerConfigPlugin.kt`. Which Nightlight server the app talks
/// to is chosen at runtime (the Home Assistant-style first-run flow), not baked in.
///
/// From the web side (same plugin + method names as Android, so `nativeBridge.js`
/// works unchanged):
///
///   Capacitor.Plugins.ServerConfig.get()          -> { url: string | null, tailscale: string | null }
///   Capacitor.Plugins.ServerConfig.list()         -> { servers: string[], active: string | null,
///                                                      tailscale: string | null, names: { [url]: name } }
///   Capacitor.Plugins.ServerConfig.save({ url, expectId? }) -> validates + persists; a found card's id is re-checked
///                                                    at save, then resolves { url },
///                                                    or { url, via: "tailscale" } when it reconnected a remembered
///                                                    server through its Tailscale address (see save)
///   Capacitor.Plugins.ServerConfig.addEndpoint({ url })  -> { added }; saves the server's Tailscale address (asking the
///                                                          person first when it is new and the page is plain http)
///   Capacitor.Plugins.ServerConfig.removeEndpoint()      -> { removed }
///   Capacitor.Plugins.ServerConfig.forget({ url })        -> from the bundled setup page only
///   Capacitor.Plugins.ServerConfig.clear()
///   Capacitor.Plugins.ServerConfig.restart()      -> reloads the bridge so the current choice takes effect
///   Capacitor.Plugins.ServerConfig.saveSession({ token, tailscale })  -> { saved }; keeps the sign-in for the other address
///   Capacitor.Plugins.ServerConfig.getSession()   -> { token }, or { token: null } for EVERY refusal
///   Capacitor.Plugins.ServerConfig.clearSession({ token? })  -> { cleared }
///   Capacitor.Plugins.ServerConfig.startDiscovery() / stopDiscovery() -> look on the local Wi-Fi
///   events serverFound { id, name, url, fingerprint }, serverLost { id }, discoveryFailed { message },
///          discoveryStatus { text } (counts and numeric errors only)
///
/// The shapes are exactly Android's: the web app turns its Tailscale features on by whether `addEndpoint` and
/// `list().names` exist (nativeBridge.js), so the About card, the "Away from home" notice and the sync work here as
/// they do on Android. The same goes for one sign-in across both addresses (iOS phase 2, 2026-11-08): nativeBridge.js
/// `sessionPlugin()` uses it only when ALL THREE of saveSession / getSession / clearSession exist, so registering them
/// below is what switches it on. A found card only suggests an address; save({ url, expectId }) checks it again.
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
    private static let keyMovedDeclines = "moved_declines:"
    private static let keyServerId = "server_id:"

    // ONE first-in-first-out queue for every write that reads, changes and writes back (the saved address, the known list,
    // the names, the Tailscale pair), the twin of Android's session executor: two saves finishing together cannot lose each
    // other's list entry, and a write cannot overtake the one queued before it. Reads (get, list, the launch) read directly:
    // UserDefaults is safe to read from any thread. Every saved-sign-in call (SessionVault, the Keychain) and the fresh-install
    // purge run here too, so a save, a clear and the purge cannot reorder or race, and getSession checks the entry against the
    // bindings as they are at that moment, on the same queue that changes them.
    //
    // THE RULE (Codex and Opus plan review of iOS phase 2, 2026-11-08): the MAIN thread never `sync`s onto this queue or onto
    // any worker queue, and nothing waits for the main thread without a time limit (ServerConfigPlugin.shownPageURL waits at
    // most a second; getSession's last page check is handed to the main thread with `async` and waits for nothing). If the
    // main thread ever waited on a queue that was itself waiting on the main thread, the app would freeze. Nothing in the app
    // does: the launch choice reads UserDefaults directly, and every plugin method queues its work here with `async`.
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

    /// Drop every per-address item, including the sign-in slot. Used on the writes queue by both the bundled-page forget
    /// and the native move; the latter cannot call the page-gated plugin method.
    static func forget(_ url: String) {
        var list = knownServers
        list.removeAll { $0 == url }
        knownServers = list
        var names = storedNames
        names.removeValue(forKey: url)
        writeNames(names)
        forgetTailscale(for: url)
        clearDeclinedTailscale(for: url)
        clearServerId(for: url)
        defaults.removeObject(forKey: keyMovedDeclines + url)
        _ = KeychainSessionStore.vault.clear(url)
    }

    /// A move may land on a URL that belonged to another server. Check raw keys so even a stale or invalid pair/decline
    /// cannot survive the saved-URL flip; a matching stored id alone is safe to leave in place.
    static func hasMoveDestinationLeftovers(_ url: String, wantedId: String) -> Bool {
        if knownServers.contains(url) || storedNames[url] != nil ||
            defaults.object(forKey: EndpointRules.key(url)) != nil ||
            defaults.object(forKey: EndpointRules.idKey(url)) != nil ||
            defaults.object(forKey: EndpointRules.declinedKey(url)) != nil ||
            defaults.object(forKey: EndpointRules.declinedAtKey(url)) != nil ||
            defaults.object(forKey: keyMovedDeclines + url) != nil ||
            serverId(for: url).map({ $0 != wantedId }) == true { return true }
        // An unavailable Keychain read must still cause a clear attempt before the move can commit.
        return KeychainSessionStore.vault.load(url) != .nothing
    }

    /// Clear the destination before writing it. If its vault slot cannot be removed, keep the saved URL at the old
    /// address so launch cannot reuse the other server's sign-in.
    static func clearMoveDestination(_ url: String) -> Bool {
        guard KeychainSessionStore.vault.clear(url) != .unavailable else { return false }
        forget(url)
        return true
    }

    /// The id belongs to the exact address that answered a strict health probe. A later occupant must not replace it.
    /// Call setServerId only on writes, after that probe; reads may use UserDefaults from a worker thread.
    static func serverId(for url: String) -> String? {
        return defaults.string(forKey: keyServerId + url)
    }

    /// The per-address id wins; a stored Tailscale pair also identifies this saved address
    /// when it has not yet learned its own strict health id.
    static func expectedId(for url: String) -> String? {
        return serverId(for: url) ?? tailscaleId(for: url)
    }

    static func setServerId(_ id: String, for url: String) {
        guard EndpointProbe.isPlausibleId(id) else { return }
        if let existing = serverId(for: url), existing != id { return }
        defaults.set(id, forKey: keyServerId + url)
    }

    static func clearServerId(for url: String) {
        defaults.removeObject(forKey: keyServerId + url)
    }

    static func movedDeclines(for old: String) -> MovedServerRules.Declines.Map {
        guard let text = defaults.string(forKey: keyMovedDeclines + old) else { return [:] }
        return MovedServerRules.Declines.decode(text)
    }

    static func setMovedDeclines(_ map: MovedServerRules.Declines.Map, for old: String) {
        defaults.set(MovedServerRules.Declines.encode(map), forKey: keyMovedDeclines + old)
    }

    /// The secure address the person declined for `primary` (an explicit "Don't use it"), with when, or nil (none, or an
    /// unreadable entry, which then simply asks again). Kept beside the Tailscale pair; it is NOT removed with the pair
    /// (removeEndpoint, a stale pair dropped by save), only by forget(url) and by an accept: a page that removes the pair must not
    /// thereby wipe the person's "no" (addendum of 2026-11-09).
    static func declinedTailscale(for primary: String) -> PromptGate.DeclineRecord? {
        guard let candidate = defaults.string(forKey: EndpointRules.declinedKey(primary)) else { return nil }
        guard let at = defaults.object(forKey: EndpointRules.declinedAtKey(primary)) as? NSNumber else { return nil }
        return PromptGate.DeclineRecord(candidate: candidate, atWallMs: at.int64Value)
    }

    /// Stores (or replaces) the decline for `primary`. On the writes queue.
    static func setDeclinedTailscale(_ record: PromptGate.DeclineRecord, for primary: String) {
        defaults.set(record.candidate, forKey: EndpointRules.declinedKey(primary))
        defaults.set(NSNumber(value: record.atWallMs), forKey: EndpointRules.declinedAtKey(primary))
    }

    /// Removes the decline for `primary` (an accept, or forgetting the server). On the writes queue.
    static func clearDeclinedTailscale(for primary: String) {
        defaults.removeObject(forKey: EndpointRules.declinedKey(primary))
        defaults.removeObject(forKey: EndpointRules.declinedAtKey(primary))
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

    /// The saved address and the Tailscale address and id saved for it, right now; nil when nothing is saved. What a saved
    /// sign-in is stamped with and checked against (SessionRules.Bindings). The id is read only when an address is (a stray id
    /// with no address binds nothing), as Android's currentBindings.
    static func currentBindings() -> SessionRules.Bindings? {
        guard let primary = savedURL else { return nil }
        let tailscale: String? = tailscaleURL(for: primary)
        var id: String? = nil
        if tailscale != nil {
            id = tailscaleId(for: primary)
        }
        return SessionRules.Bindings(savedUrl: primary, tailscaleUrl: tailscale, tailscaleId: id)
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
        CAPPluginMethod(name: "startDiscovery", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopDiscovery", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addEndpoint", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeEndpoint", returnType: CAPPluginReturnPromise),
        // All three or none: the web app uses the saved sign-in only when every one exists (nativeBridge.js sessionPlugin).
        CAPPluginMethod(name: "saveSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearSession", returnType: CAPPluginReturnPromise),
    ]

    // The probe timeouts, in seconds, carried over from Android's ServerConfigPlugin (fetchHealth 4000 / 5000 / 3000 ms):
    // judgements, not measurements. The page's own server answers quickly (4 s); the offered Tailscale address may be a
    // relayed link on cellular (5 s); the id check at a home address that just answered is a second look (3 s).
    private static let pageProbeTimeout: TimeInterval = 4
    private static let tailscaleProbeTimeout: TimeInterval = 5
    private static let pairCheckTimeout: TimeInterval = 3
    // getSession's read of the Tailscale page's own live id: Android's 3000 ms. It has to fit, with the page reads and the
    // Keychain read, inside the web app's 6 s wait (nativeBridge.js SESSION_ADOPT_DEADLINE_MS). A judgement, not a measurement.
    private static let sessionProbeTimeout: TimeInterval = 3

    // Search and its path snapshot are owned on the main thread, where the committed main-frame URL can be checked
    // immediately before each event. A stale callback carries an old token and cannot report into a new search.
    private static weak var activeDiscoveryOwner: ServerConfigPlugin?
    private var discovery: ServerDiscovery?
    private var discoveryToken = UUID()
    private var pathCheck: NWPathMonitor?
    private var pathCheckSerial = 0
    private var pendingStartCall: CAPPluginCall?

    deinit {
        discovery?.stop()
        pathCheck?.cancel()
    }

    static func stopDiscoveryForBackground() {
        if Thread.isMainThread {
            activeDiscoveryOwner?.stopSearch()
        } else {
            DispatchQueue.main.async { activeDiscoveryOwner?.stopSearch() }
        }
    }

    private func stopSearch() {
        discoveryToken = UUID()
        pathCheckSerial += 1
        pathCheck?.cancel()
        pathCheck = nil
        pendingStartCall?.reject("Couldn't start looking for servers.")
        pendingStartCall = nil
        discovery?.stop()
        discovery = nil
        if ServerConfigPlugin.activeDiscoveryOwner === self { ServerConfigPlugin.activeDiscoveryOwner = nil }
    }

    private func deliverDiscovery(_ event: ServerDiscovery.Event, token: UUID) {
        // Never ask the writes queue for the page. This is already on main; after navigation, no found
        // address or permission message is delivered to a different page and the search is stopped.
        guard discoveryToken == token, discovery != nil else { return }
        guard callerIsCommittedBundledPage() else { stopSearch(); return }
        switch event {
        case .found(let card):
            notifyListeners("serverFound", data: ["id": card.id, "name": card.name,
                                                   "url": card.url, "fingerprint": card.fingerprint])
        case .lost(let id):
            notifyListeners("serverLost", data: ["id": id])
        case .failed(let message):
            notifyListeners("discoveryFailed", data: ["message": message])
            stopSearch()
        case .status(let text):
            // Use the same committed bundled-page gate as cards; diagnostics belong only on setup.
            notifyListeners("discoveryStatus", data: ["text": text])
        }
    }

    private func watchBundledPage(_ token: UUID) {
        // The page calls stop on pagehide. This bounded poll covers a navigation that never runs pagehide or
        // loses its JS listeners; an event also checks the page at delivery time.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self = self, self.discoveryToken == token, self.discovery != nil else { return }
            if !self.callerIsCommittedBundledPage() { self.stopSearch(); return }
            self.watchBundledPage(token)
        }
    }

    @objc func startDiscovery(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { call.reject("Couldn't start looking for servers."); return }
            guard self.callerIsCommittedBundledPage() else {
                call.reject(EndpointRules.refusal(.notThisPage))
                return
            }
            guard UIApplication.shared.applicationState == .active else {
                call.reject("Couldn't start looking for servers.")
                return
            }
            self.stopSearch()
            self.pathCheckSerial += 1
            let check = self.pathCheckSerial
            let monitor = NWPathMonitor()
            self.pathCheck = monitor
            self.pendingStartCall = call
            ServerConfigPlugin.activeDiscoveryOwner = self
            monitor.pathUpdateHandler = { [weak self] path in
                DispatchQueue.main.async { [weak self] in
                    self?.finishPathCheck(check, path: path, call: call)
                }
            }
            monitor.start(queue: DispatchQueue(label: "com.sauso.nightlight.discovery-path"))
            // NWPathMonitor's first update is normally immediate. A one-second bound avoids holding a plugin
            // call indefinitely if it does not arrive; an unknown path can be retried from the page.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                self?.finishPathCheck(check, path: nil, call: call)
            }
        }
    }

    private func finishPathCheck(_ check: Int, path: NWPath?, call: CAPPluginCall) {
        guard check == pathCheckSerial, pathCheck != nil else { return }
        pathCheck?.cancel()
        pathCheck = nil
        pendingStartCall = nil
        guard UIApplication.shared.applicationState == .active else {
            stopSearch()
            call.reject("Couldn't start looking for servers.")
            return
        }
        guard callerIsCommittedBundledPage() else {
            stopSearch()
            call.reject(EndpointRules.refusal(.notThisPage))
            return
        }
        guard let path = path else {
            stopSearch()
            // A slow first path update is transient. A rejection makes the setup page hide search for this visit.
            call.resolve(["started": false, "onWifi": false])
            return
        }
        let onWifi = path.status == .satisfied &&
            (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
        guard onWifi else {
            stopSearch()
            call.resolve(["started": false, "onWifi": false])
            return
        }
        let token = UUID()
        discoveryToken = token
        let search = ServerDiscovery { [weak self] event in
            DispatchQueue.main.async { [weak self] in self?.deliverDiscovery(event, token: token) }
        }
        discovery = search
        ServerConfigPlugin.activeDiscoveryOwner = self
        search.start()
        watchBundledPage(token)
        call.resolve(["started": true])
    }

    @objc func stopDiscovery(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.callerIsCommittedBundledPage() else {
                call.reject(EndpointRules.refusal(.notThisPage))
                return
            }
            self.stopSearch()
            call.resolve()
        }
    }

    // How long work off the main thread waits for the main thread to read the web view's URL (shownPageURL). Android's
    // PAGE_CHECK_TIMEOUT_MS: the main thread answers in microseconds unless it is busy, and if it is busy for a whole second the
    // call is refused (fails closed: no URL is "not the server's page"). The launch choice can hold the main thread for up to
    // 3 s (MainViewController.chooseAddress), so a call that lands during a restart is refused rather than kept waiting.
    private static let pageReadTimeout: TimeInterval = 1

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
    // unreadable config or an unreadable page URL is "not the bundled page". The rule itself is EndpointRules.isBundledPage
    // (pure, run by ios/Tests); the page URL is read only when the bridge is the bundled one.
    private func callerIsBundledPage() -> Bool {
        return bundledPage(using: shownPageURL)
    }

    // Codex code review of PR #39: WKWebView.url may show a destination before it commits, while the old
    // page still receives discovery events. Discovery uses isLoading and the current item when present; the
    // http(s)-only committedPageURL check cannot recognize capacitor://localhost. These callers run on main,
    // so readWebView reads directly and cannot wait on its own queue.
    private func callerIsCommittedBundledPage() -> Bool {
        guard let localText = bundledBridgeLocalURL() else { return false }
        let shownBundled = readWebView { (webView: WKWebView?) -> String? in
            guard let view = webView, let shown = view.url?.absoluteString else { return nil }
            let committed = view.backForwardList.currentItem?.url.absoluteString
            return EndpointRules.isCommittedBundledPage(shown: shown, committed: committed,
                                                         isLoading: view.isLoading, local: localText) ? shown : nil
        }
        return shownBundled != nil
    }

    private func bundledPage(using pageURL: () -> String?) -> Bool {
        guard let localText = bundledBridgeLocalURL() else { return false }
        return EndpointRules.isBundledPage(bridgeServerUrl: localText, bridgeLocalUrl: localText, shown: pageURL())
    }

    private func bundledBridgeLocalURL() -> String? {
        guard let config = bridge?.config else { return nil }
        let server: URL? = config.serverURL
        let local: URL? = config.localURL
        let serverText: String? = server?.absoluteString
        let localText: String? = local?.absoluteString
        if serverText == nil || serverText != localText { return nil }
        return localText
    }

    // The URL the web view's main frame is showing RIGHT NOW, or nil (no bridge, no web view, nothing loaded, or the main thread
    // did not answer within pageReadTimeout). WKWebView may only be read on the main thread. Plugin methods (the session calls
    // included) run on Capacitor's plugin queue and save's reconnect on a URLSession queue, so the read is handed to the main
    // thread with `async` and waited for with a time limit. When already on the main thread it is read directly (waiting on the
    // main thread for a block queued on the main thread would never end). Never called from ServerConfigStore.writes (the one
    // read from there is committedPageURL, in addEndpoint's write: see readWebView).
    //
    // WHY not `DispatchQueue.main.sync`, as phase 1 had (Codex and Opus plan review of iOS phase 2, 2026-11-08): a sync waits
    // without limit. Nothing on the main thread waits for the queues that call this today, so it could not deadlock, but that
    // would rest on every later change keeping it so (the network watcher, NetworkRoamWatcher, runs on its own queue); and a busy main
    // thread (the launch choice holds it for up to 3 s) held Capacitor's plugin queue, and every plugin call behind it, for as
    // long. With the time limit the worst case is a refused call, never a frozen app. See THE RULE at ServerConfigStore.writes.
    private func shownPageURL() -> String? {
        return readWebView { (webView: WKWebView?) -> String? in
            return webView?.url?.absoluteString
        }
    }

    // The URL of the page the web view has actually COMMITTED, for session calls and discovery (Opus code review of PR #35,
    // 2026-11-08, a suspicion about an Apple behaviour it was not sure of): `WKWebView.url` can show a navigation that
    // has STARTED and not finished (the known address-bar spoofing shape), while Capacitor's reply goes to the page that is
    // really showing. So the URL is returned only when the back-forward list's current item, which changes only once a new page
    // has committed, has the same ORIGIN; anything else (no current item either) is nil = "not the server's page", which refuses.
    // Fails closed, so if WebKit behaves otherwise than assumed the worst case is "sign in twice, as before", never a token for
    // the wrong page. Used by saveSession and getSession (the two that hand something to or take something from a page),
    // discovery, and addEndpoint's "ask once" decision and its last check before the write (Codex code review of the PR #38
    // prompt delta, High:
    // a hostile plain-http page could start a navigation to an https URL, so WKWebView.url read https and the prompt was skipped;
    // an unreadable or uncommitted page is nil here, which ASKS). NOT by clearSession (a delete should not be refused by an odd
    // page state) and not by phase 1's checkPage, which works on a device and is left as it is.
    private func committedPageURL() -> String? {
        return readWebView { (webView: WKWebView?) -> String? in
            return ServerConfigPlugin.committedURL(of: webView)
        }
    }

    private static func committedURL(of webView: WKWebView?) -> String? {
        guard let view = webView, let shown = view.url?.absoluteString else { return nil }
        guard let committed = view.backForwardList.currentItem?.url.absoluteString else { return nil }
        return EndpointRules.sameOrigin(shown, committed) ? shown : nil
    }

    // Runs `read` on the main thread (WKWebView may only be touched there) and waits for it up to pageReadTimeout, as described
    // at shownPageURL. Directly when already on the main thread. Called from ServerConfigStore.writes in ONE place only:
    // addEndpoint's last check just before its write (writeEndpoint, through committedPageURL; addendum review of 2026-11-09: a
    // prompt can stay up for two minutes, so the page must be checked where the write happens). That cannot deadlock because of
    // THE RULE (the main thread never waits for the writes queue); its cost is that other writes can wait up to pageReadTimeout.
    private func readWebView(_ read: @escaping (WKWebView?) -> String?) -> String? {
        guard let b = bridge else { return nil }
        if Thread.isMainThread {
            return read(b.webView)
        }
        let answer = ShownPageAnswer()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            answer.set(read(b.webView))
            done.signal()
        }
        if done.wait(timeout: .now() + ServerConfigPlugin.pageReadTimeout) == .timedOut {
            return nil
        }
        return answer.get()
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

    // Drop a server from the remembered list (does not touch the active choice). Its name and Tailscale address go with it, and
    // so does the sign-in saved for that address (it was only ever meaningful for that server), as on Android.
    //
    // ONLY from the bundled setup page (its ✕ is the one caller, www/index.html), checked before anything else (Codex code review of
    // the PR #38 prompt delta, Medium): forget also clears the remembered "Don't use it" for that server, so a plain-http page
    // that could call it on the active server and then offer the same secure address again would defeat the 7-day quiet period.
    // Refused elsewhere with the sentence the other page checks use.
    @objc func forget(_ call: CAPPluginCall) {
        if !callerIsBundledPage() {
            call.reject(EndpointRules.refusal(.notThisPage))
            return
        }
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
    // cannot answer on cellular. At launch a known id must match before the saved address opens. A save to an address
    // whose strict reply has a different id is refused before the pair can be dropped. If no strict reply arrives,
    // save keeps the earlier reconnect behaviour. forget(url), the explicit "forget this server", still removes it.
    @objc func clear(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in self?.stopSearch() }
        // On the writes queue, so a save queued just before this one has landed first; resolved after, so the restart the
        // page asks for next starts from the cleared state.
        ServerConfigStore.writes.async {
            // Read here, on the queue: a save queued just before this one has landed by now, so the right address is cleared.
            let leaving: String? = ServerConfigStore.savedURL
            ServerConfigStore.savedURL = nil
            // The sign-in saved for the server being left goes too, as on Android: "Change server" leaves that server, and the
            // next sign-in there saves it again.
            if let address = leaving {
                _ = KeychainSessionStore.vault.clear(address)
            }
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
    //   - ASK ONCE (addendum of 2026-11-09, the owner's choice; both Codex code reviews of PR #38 rated the risk High): when the
    //     address is NEW or CHANGED for this server and the page actually shown is not https (EndpointRules.needsConfirmation),
    //     the person is asked with a native prompt before anything is saved (PromptGate, AddressPrompt below). The id above is
    //     public, so a device answering at a plain-http saved address could otherwise plant its own secure address, which
    //     Tailscale first would then open first.
    // Resolves { added }: true when this is new or different, false when it was already saved (so the app tells the person
    // once). Nothing is switched or restarted here: the address is used the next time the app starts. The refusal sentences
    // are Android's, word for word (EndpointRules.refusal): the web app's About card reads them. The call is answered exactly
    // once (OnceReply), whichever of the write, the prompt, its timeout or a cancel finishes it.
    @objc func addEndpoint(_ call: CAPPluginCall) {
        let reply = OnceReply(call)
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
            reply.reject(EndpointRules.refusal(first == .ok ? .notThisPage : first))
            return
        }
        // Off the plugin queue: two health checks can take seconds, and other plugin calls must not wait behind them.
        DispatchQueue.global(qos: .userInitiated).async {
            let reference = EndpointProbe.fetchHealth(pageURL, timeout: ServerConfigPlugin.pageProbeTimeout)
            let candidate = EndpointProbe.fetchHealth(offered, timeout: ServerConfigPlugin.tailscaleProbeTimeout)
            let second = EndpointRules.checkIdentity(reference: reference, candidate: candidate)
            guard second == .ok, let confirmed = candidate else {
                reply.reject(EndpointRules.refusal(second == .ok ? .unreachableAddress : second))
                return
            }
            // The saved server's name, learned for free here: this runs at every start while signed in, so servers saved before
            // names were kept get theirs without any action (the page's server and the saved address are the same server).
            let pageName: String? = reference?.name
            // Every check above has passed; only now is the prompt considered, so a refused offer never asks. The page is read
            // again (the identity check took seconds) and the saved secure address as it is now. The COMMITTED page, not
            // WKWebView.url (Codex code review of the PR #38 prompt delta, High): a hostile plain-http page could start a
            // navigation to an https URL so that the URL read https while the http page still runs; committedPageURL is nil
            // then, which asks. The write repeats this decision (writeEndpoint), so a skipped prompt cannot be written anyway.
            let shownNow: String? = self.committedPageURL()
            let savedNow: String? = ServerConfigStore.tailscaleURL(for: home)
            if !EndpointRules.needsConfirmation(shownPageUrl: shownNow, savedTailscale: savedNow, candidate: offered) {
                self.writeEndpoint(reply, offered: offered, id: confirmed.id, home: home, pageName: pageName, acceptedInPrompt: false)
                return
            }
            self.askThenWrite(reply, offered: offered, id: confirmed.id, home: home, pageName: pageName)
        }
    }

    // The prompt step (not on the main thread). The gate is decided and the one prompt slot reserved in ONE locked step
    // (AddressPrompt.reserve), so two offers cannot both show a prompt. Busy or recently declined: refused at once, nothing stored.
    private func askThenWrite(_ reply: OnceReply, offered: String, id: String, home: String, pageName: String?) {
        let decline: PromptGate.DeclineRecord? = ServerConfigStore.declinedTailscale(for: home)
        let reservation: AddressPrompt.Reservation = AddressPrompt.shared.reserve(candidate: offered, decline: decline, completion: { (outcome: PromptGate.Outcome) -> Void in
            self.promptAnswered(outcome, reply: reply, offered: offered, id: id, home: home, pageName: pageName)
        })
        if let refusal = PromptGate.refusal(for: reservation.decision) {
            AddressPrompt.log(reservation.decision == .busy ? "not asked: a prompt is showing or was shown in the last 10 minutes" : "not asked: declined less than 7 days ago")
            reply.reject(EndpointRules.refusal(refusal))
            return
        }
        let message: String = AddressPrompt.message(home: home, offered: offered)
        let serial: Int = reservation.serial
        // Async onto the main thread, never sync (THE RULE at ServerConfigStore.writes).
        DispatchQueue.main.async {
            AddressPrompt.shared.present(serial, message: message)
        }
    }

    // How the prompt ended (main thread, called once per prompt by AddressPrompt.finish). An accept runs the write exactly as an
    // offer that needed no prompt does; an explicit "Don't use it" stores the decline (7 days, PromptGate); anything else
    // refuses and stores NOTHING (addendum review: a prompt that failed, timed out or was cancelled is not the person's "no").
    private func promptAnswered(_ outcome: PromptGate.Outcome, reply: OnceReply, offered: String, id: String, home: String, pageName: String?) {
        let change: PromptGate.DeclineChange = PromptGate.declineChange(after: outcome, candidate: offered, nowWallMs: AddressPrompt.wallClockMs())
        if let refusal = PromptGate.refusal(after: outcome) {
            AddressPrompt.log(outcome == .declined ? "the person declined the secure address" : "the prompt was not answered; nothing stored")
            ServerConfigStore.writes.async {
                if case .store(let record) = change {
                    ServerConfigStore.setDeclinedTailscale(record, for: home)
                }
                reply.reject(EndpointRules.refusal(refusal))
            }
            return
        }
        // Here only an accept is left (refusal(after:) is nil for nothing else). The write also clears the decline, as
        // PromptGate.declineChange says an accept does.
        writeEndpoint(reply, offered: offered, id: id, home: home, pageName: pageName, acceptedInPrompt: outcome == .accepted)
    }

    // The write, on the writes queue. FIRST, everything is checked once more right where the write happens (addendum review of
    // 2026-11-09; Codex code review of the PR #38 prompt delta): the prompt can stay up for two minutes and the identity check
    // takes seconds. The saved server must still be `home`; the web view's COMMITTED page must still be a trusted page of it
    // (EndpointRules.isTrustedPage, as checkPage); and the "ask once" decision is made AGAIN from that page and the saved secure
    // address as they are now (PromptGate.mayWrite): an offer that needs asking now but was not accepted in a prompt is refused
    // as not confirmed, with nothing written and no decline stored. Fails closed: an unreadable page refuses. Reading the page
    // from this queue is the one exception noted at readWebView (bounded at a second; THE RULE keeps it deadlock-free).
    private func writeEndpoint(_ reply: OnceReply, offered: String, id: String, home: String, pageName: String?, acceptedInPrompt: Bool) {
        ServerConfigStore.writes.async {
            let primaryNow: String? = ServerConfigStore.savedURL
            let savedTailscaleNow: String? = ServerConfigStore.tailscaleURL(for: home)
            // ONE read of the committed page, used for both checks below, so they judge the same page.
            let committedNow: String? = self.committedPageURL()
            let trusted: Bool = EndpointRules.isTrustedPage(primary: primaryNow, savedTailscale: savedTailscaleNow, page: self.pageServerURL(), shown: committedNow)
            guard primaryNow == home, trusted else {
                reply.reject(EndpointRules.refusal(.notThisPage))
                return
            }
            let needsNow: Bool = EndpointRules.needsConfirmation(shownPageUrl: committedNow, savedTailscale: savedTailscaleNow, candidate: offered)
            if !PromptGate.mayWrite(acceptedInPrompt: acceptedInPrompt, needsConfirmationNow: needsNow) {
                AddressPrompt.log("not written: it needs confirming now and was not accepted in a prompt")
                reply.reject(EndpointRules.refusal(.notConfirmed))
                return
            }
            let previous = ServerConfigStore.tailscaleURL(for: home)
            ServerConfigStore.setTailscale(offered, id: id, for: home)
            if acceptedInPrompt {
                ServerConfigStore.clearDeclinedTailscale(for: home)
            }
            ServerConfigStore.rememberName(home, pageName)
            reply.resolve(["added": previous != offered])
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
                // The saved sign-in was stamped with that address. It could never be handed out without it, but if the server
                // offers the same address again later it would match again, and a sign-out in between that could not reach the
                // server would be undone (agy review of Android phase D). So it goes with the address, as on Android.
                _ = KeychainSessionStore.vault.clear(home)
            }
            call.resolve(["removed": hadOne])
        }
    }

    // ---- One sign-in across the two addresses (iOS phase 2, 2026-11-08; a port of Android's plan phase D) ----
    //
    // The saved address and the Tailscale address are different web origins with separate storage, so the web app would ask for
    // a sign-in on each. The session token is therefore also kept in the iOS Keychain (SessionVault over KeychainSessionStore),
    // one entry per SAVED address, so the page on the server's Tailscale address can sign in without asking again. The rules are
    // in SessionRules (pure, run by ios/Tests); this is the glue. What the glue must keep (Android's reviews, and the Codex and
    // Opus plan review of this port):
    //  - a server's id is PUBLIC and proves nothing, so a session is handed ONLY to the page that IS the saved Tailscale address,
    //    never to the saved address or any other page, and only while everything the entry was stamped with still matches
    //    (SessionRules.checkDelivery);
    //  - the page cannot choose an id or an address: the bindings come from what the app itself saved;
    //  - save, clear, the purge and getSession's final check run on ONE first-in-first-out queue (ServerConfigStore.writes), so
    //    they cannot reorder or race;
    //  - every call is refused unless the web view is really showing the origin the bridge was built for (SessionRules.mayAct,
    //    mayClear, isTailscalePage), because Capacitor iOS lets the web view wander to a URL that merely starts with it.
    // The refusal shapes are Android's exactly: save and clear reject with "Not available on this page" / "Not a session token";
    // getSession answers { token: null } for EVERY refusal, so a page learns nothing about why.

    // saveSession({ token, tailscale }): the web app signed in (or confirmed its sign-in) and asks the app to keep the token. From
    // the saved address or its Tailscale address only, and only from the page the web view is really showing (SessionRules.mayAct);
    // the token must look like a session token. `tailscale` is the address this page's own server just offered (nil when not
    // known): the stamp keeps the Tailscale binding only when it equals the one saved (SessionRules.stampFor), so an address
    // planted by another page never gets a token. On the queue, the bindings must still be the ones the page was checked against
    // (SessionRules.stampOnQueue), else nothing is written and it resolves { saved: false }: a server switch in between must not
    // put this server's token in the other server's slot. The web app saves again after it has learned the Tailscale address
    // (App.jsx, after syncTailscaleEndpoint), so a refused or unbound first save is put right by the second. Resolves { saved }.
    @objc func saveSession(_ call: CAPPluginCall) {
        let token: String? = call.getString("token")
        let checked: SessionRules.Bindings? = ServerConfigStore.currentBindings()
        guard let before = checked, SessionRules.mayAct(before, bridgeUrl: pageServerURL(), webViewUrl: committedPageURL()) else {
            call.reject("Not available on this page")
            return
        }
        guard let accepted = token, SessionRules.isPlausibleToken(accepted) else {
            call.reject("Not a session token")
            return
        }
        let claimed: String? = call.getString("tailscale")
        ServerConfigStore.writes.async {
            // The first-start clean-up must have finished before any sign-in is read or written (Codex code review of PR #35): see
            // KeychainSessionStore.ensureReady. A failed one refuses this save; the web app saves again at its next start.
            guard KeychainSessionStore.ensureReady() else {
                call.resolve(["saved": false])
                return
            }
            let now: SessionRules.Bindings? = ServerConfigStore.currentBindings()
            guard let stamp = SessionRules.stampOnQueue(checked: before, now: now, claimedTailscale: claimed) else {
                call.resolve(["saved": false])
                return
            }
            let saved: Bool = KeychainSessionStore.vault.save(SessionRules.Entry(token: accepted, bindings: stamp))
            call.resolve(["saved": saved])
        }
    }

    // getSession(): the page asks for a saved session. Resolves { token } with the token, or { token: null } for EVERY refusal.
    // Only the page that is the saved Tailscale address, and is really showing it, can get one (SessionRules.isTailscalePage).
    // Its own live /api/health id is read first, OFF the queue (a slow network must not hold up saves and clears), then, on the
    // queue, the entry is read and checked against the bindings as they are at that moment (SessionRules.checkDelivery).
    //
    // Then one last look, on the main thread, immediately before the token is handed over (Opus plan review of iOS phase 2,
    // 2026-11-08): the web view must still be showing the bridge's origin. Capacitor delivers a reply to whatever page the web
    // view has loaded when the reply arrives, and the id check takes up to 3 s, long enough for a page at a lookalike address
    // (one that merely starts with the Tailscale address, which Capacitor iOS lets the web view open) to have navigated in. The
    // check and the resolve run in the SAME main-thread block, which leaves less room than reading the URL with shownPageURL and
    // resolving from another queue, and needs no time limit because nothing waits for it.
    // Remaining gap, stated: Capacitor's resolve itself queues the reply to the web view as another main-thread block, so a
    // navigation that commits in between those two main-thread blocks would still receive it. That is one turn of the main
    // thread after a 3 s window that is now checked at both ends; it is not closed. The frame limit at pageServerURL also applies.
    @objc func getSession(_ call: CAPPluginCall) {
        let none: [String: Any] = ["token": NSNull()]
        let current: SessionRules.Bindings? = ServerConfigStore.currentBindings()
        guard let page = pageServerURL(), SessionRules.isTailscalePage(current, bridgeUrl: page, webViewUrl: committedPageURL()) else {
            call.resolve(none)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let liveId: String? = EndpointProbe.fetchHealth(page, timeout: ServerConfigPlugin.sessionProbeTimeout)?.id
            ServerConfigStore.writes.async {
                // Nothing is read before the first-start clean-up has finished (see saveSession): answer "no session".
                guard KeychainSessionStore.ensureReady() else {
                    call.resolve(none)
                    return
                }
                let now: SessionRules.Bindings? = ServerConfigStore.currentBindings()
                var entry: SessionRules.Entry? = nil
                if let bindings = now, case .found(let stored) = KeychainSessionStore.vault.load(bindings.savedUrl) {
                    entry = stored
                }
                let verdict: SessionRules.Delivery = SessionRules.checkDelivery(page: page, current: now, entry: entry, liveId: liveId)
                guard verdict == .ok, let token = entry?.token else {
                    call.resolve(none)
                    return
                }
                // Hopping to the main thread leaves the FIFO queue, as Android's resolve does not: a removeEndpoint, forget, clear or
                // clearSession queued behind this one can finish before the token is delivered. The receiver is still the page that
                // passed every check, and a token that was signed out is refused by the server anyway (Opus code review of PR #35).
                DispatchQueue.main.async { [weak self] in
                    let shown: String? = ServerConfigPlugin.committedURL(of: self?.bridge?.webView)
                    if EndpointRules.sameOrigin(shown, page) {
                        call.resolve(["token": token])
                    } else {
                        call.resolve(none)
                    }
                }
            }
        }
    }

    // clearSession({ token? }): the web app signed out, or the server rejected a token. Needs no network. With a token, only if it
    // is the one stored (a rejection of an old token must not delete a newer sign-in). Allowed from any page that is really the
    // server's (SessionRules.mayClear), including a Tailscale page whose address was removed or replaced while it was open: a
    // sign-out there must still delete the entry. A Keychain that is busy deletes nothing and answers { cleared: false } (the
    // store logs its status); it is never mistaken for "nothing to clear" inside the vault. Resolves { cleared }.
    @objc func clearSession(_ call: CAPPluginCall) {
        let current: SessionRules.Bindings? = ServerConfigStore.currentBindings()
        guard let now = current, SessionRules.mayClear(now, bridgeUrl: pageServerURL(), webViewUrl: shownPageURL()) else {
            call.reject("Not available on this page")
            return
        }
        let token: String? = call.getString("token")
        ServerConfigStore.writes.async {
            let result: SessionVault.Clear = KeychainSessionStore.vault.clear(now.savedUrl, onlyIfToken: token)
            let cleared: Bool = (result == .cleared)
            call.resolve(["cleared": cleared])
        }
    }

    // Confirms the address actually hosts a reachable Nightlight server (its
    // unauthenticated /api/health endpoint) before persisting. Scheme handling
    // mirrors Android: an explicit scheme is respected; a bare address tries https
    // first then http (self-hosted LAN servers are commonly plain-http).
    @objc func save(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in self?.stopSearch() }
        let raw = (call.getString("url") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty {
            call.reject("Enter your server address")
            return
        }
        // Present but non-string must not silently take the typed-address route. A found URL is
        // checked by one no-redirect probe and never falls into validate or the Tailscale reconnect.
        if let value = call.options["expectId"] {
            guard let expectId = value as? String else {
                call.reject("That address is not the server we found. Search again.")
                return
            }
            saveFoundServer(raw, expectId: expectId, call: call)
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
    // who answers here now is read when an expected id exists. A different strict id refuses the save before any write;
    // an unreadable id preserves the earlier save and Tailscale reconnect behaviour. The name in the loose reply is kept
    // (nil when it sent none, or an older server with no id).
    private static func confirmSaved(_ url: String, body: Data, call: CAPPluginCall) {
        let hasExpectedId = ServerConfigStore.expectedId(for: url) != nil
        let liveId: String? = hasExpectedId ? EndpointProbe.fetchHealth(url, timeout: ServerConfigPlugin.pairCheckTimeout)?.id : nil
        let name: String? = EndpointProbe.parseHealth(body)?.name
        writeSaved(url, liveId: liveId, name: name, call: call)
    }

    private static func writeSaved(_ url: String, liveId: String?, name: String?, strictId: String? = nil, call: CAPPluginCall) {
        ServerConfigStore.writes.async {
            // Recheck stored identity on the writes queue, before the saved URL, pair, list or vault changes.
            if EndpointRules.identityVerdict(expectedId: ServerConfigStore.expectedId(for: url), liveId: liveId) == .different {
                call.reject("That address now belongs to a different server. Look for your Nightlight, or type its new address.")
                return
            }
            ServerConfigStore.savedURL = url
            // If the stored address id wins over a different pair id, the stale pair and its bound sign-in go.
            if !EndpointRules.pairStillValid(wantedId: ServerConfigStore.tailscaleId(for: url), liveId: liveId) {
                ServerConfigStore.forgetTailscale(for: url)
                _ = KeychainSessionStore.vault.clear(url)
            }
            ServerConfigStore.remember(url)
            ServerConfigStore.rememberName(url, name)
            // The typed path's loose "ok" reply is not an identity check. Only found-server's strict probe supplies this.
            if let id = strictId { ServerConfigStore.setServerId(id, for: url) }
            call.resolve(["url": url])
        }
    }

    private func saveFoundServer(_ url: String, expectId: String, call: CAPPluginCall) {
        guard let target = DiscoveryRules.parseFoundUrl(url), EndpointRules.isPrivateIpv4(target.host) else {
            call.reject("Couldn't reach a Nightlight server at that address")
            return
        }
        // The id is public, not authentication. Re-check it at the person's tap because the address
        // may have changed hands since the card appeared. The same reply supplies the saved name and
        // stale Tailscale-pair check: exactly one request, no redirect or second identity probe.
        DispatchQueue.global(qos: .userInitiated).async {
            let health = EndpointProbe.fetchHealth(url, timeout: 2)
            guard let live = health else {
                call.reject("Couldn't reach a Nightlight server at that address")
                return
            }
            guard DiscoveryRules.matches(live, expectId) else {
                call.reject("That address is not the server we found. Search again.")
                return
            }
            ServerConfigPlugin.writeSaved(url, liveId: live.id, name: live.name, strictId: live.id, call: call)
        }
    }

    // None answered. A REMEMBERED server that has a Tailscale address can still be reached through it: this is how "Change
    // server" gets back in from cellular, where the home address cannot answer (owner, 2026-11-06). The saved address stays the
    // one the person picked; the launch choice then opens the Tailscale address (which it tries first anyway since Tailscale
    // first, owner decision 2026-11-09; here the saved one is not answering either).
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
    // may wait up to 3 s for the server's addresses, as at launch). The rebuild is
    // AppRestart.rebuildRoot (NetworkRoamWatcher.swift), the one function a move between
    // the addresses uses too, so the two cannot drift; it also tells the network watcher,
    // so a check in flight cannot act on the page being replaced. Not counted towards the
    // watcher's reload limit: the person asked for this one (as on Android).
    @objc func restart(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in self?.stopSearch() }
        call.resolve()
        DispatchQueue.main.async { [weak self] in
            AppRestart.rebuildRoot(window: self?.bridge?.viewController?.view.window)
        }
    }
}

/// Answers one plugin call EXACTLY once (addendum review of 2026-11-09): addEndpoint's call can now be finished by the write, the
/// prompt, its 120 s timeout or a cancel from a rebuild, and only the first of them may resolve or reject it. Thread-safe.
private final class OnceReply {
    private let lock = NSLock()
    private var done: Bool = false
    private let call: CAPPluginCall

    init(_ call: CAPPluginCall) {
        self.call = call
    }

    // True for the first caller only.
    private func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }

    func resolve(_ data: [String: Any]) {
        if claim() {
            call.resolve(data)
        }
    }

    func reject(_ message: String) {
        if claim() {
            call.reject(message)
        }
    }
}

/// The app's one on-screen address prompt. Process-wide, NOT per plugin: every rebuild registers a new ServerConfigPlugin.
/// Secure and moved offers share the alert slot but keep separate 10-minute cooldowns and decline stores. PromptGate's pure
/// rules guard the secure-address flow (ios/Tests); this class owns UIKit and the process-local state.
///
/// Threads: `reserve` runs off the main thread; `present`, `finish` and `cancelPending` run ON the main thread (UIKit, the
/// button handlers, the main-thread timeout, AppRestart.rebuildRoot). The state is behind one lock, never held while calling out.
/// The completion runs on the main thread and only queues work with `async` (THE RULE at ServerConfigStore.writes).
final class AddressPrompt {

    static let shared = AddressPrompt()

    /// What reserve decided, and the prompt's number when it is to be shown (0 otherwise).
    struct Reservation {
        let decision: PromptGate.Decision
        let serial: Int
    }

    private enum Kind: Equatable { case secure, moved }

    // The prompt reserved or on screen. A number per prompt, so a late timeout or a late button can only ever end its own.
    private final class Pending {
        let serial: Int
        let kind: Kind
        let completion: (PromptGate.Outcome) -> Void
        let onShown: (() -> Void)?
        var alert: UIAlertController? = nil

        init(serial: Int, kind: Kind, onShown: (() -> Void)?, completion: @escaping (PromptGate.Outcome) -> Void) {
            self.serial = serial
            self.kind = kind
            self.onShown = onShown
            self.completion = completion
        }
    }

    static let title: String = "Use this secure address?"

    private let lock = NSLock()
    private var current: Pending? = nil
    // When a prompt was last SHOWN in this run (monotonic ms), for the 10-minute cooldown. Set when the alert is presented, not
    // when it is reserved, so a prompt that could not be shown (no screen, the app inactive) does not hold the next one back.
    private var lastSecureShownAtMs: Int64? = nil
    private var lastMovedShownAtMs: Int64? = nil
    private var nextSerial: Int = 0

    private init() {}

    var isBusy: Bool {
        lock.lock()
        defer { lock.unlock() }
        return current != nil
    }

    /// Decides (PromptGate.decide) and, on ask, reserves the one prompt slot, in ONE locked step, so two offers arriving
    /// together cannot both be asked about (addendum review: "the gate reserves atomically"). `completion` is kept only on ask.
    func reserve(candidate: String, decline: PromptGate.DeclineRecord?, completion: @escaping (PromptGate.Outcome) -> Void) -> Reservation {
        return reserve(kind: .secure, candidate: candidate, decline: decline, onShown: nil, completion: completion)
    }

    /// The moved-address flow has its own cooldown and decline map, but reserves the same on-screen slot.
    func reserveMoved(completion: @escaping (PromptGate.Outcome) -> Void) -> Reservation {
        return reserve(kind: .moved, candidate: "", decline: nil, onShown: nil, completion: completion)
    }

    private func reserve(kind: Kind, candidate: String, decline: PromptGate.DeclineRecord?, onShown: (() -> Void)?, completion: @escaping (PromptGate.Outcome) -> Void) -> Reservation {
        lock.lock()
        defer { lock.unlock() }
        let lastShown = kind == .secure ? lastSecureShownAtMs : lastMovedShownAtMs
        let decision: PromptGate.Decision = PromptGate.decide(candidate: candidate, decline: decline, nowWallMs: AddressPrompt.wallClockMs(),
                                                              lastPromptAtMs: lastShown, nowMs: AddressPrompt.monotonicMs(), showing: current != nil)
        if decision != .ask {
            return Reservation(decision: decision, serial: 0)
        }
        nextSerial += 1
        current = Pending(serial: nextSerial, kind: kind, onShown: onShown, completion: completion)
        return Reservation(decision: .ask, serial: nextSerial)
    }

    /// Shows prompt `serial` from the app window's root view controller. Main thread. When it cannot be shown (cancelled
    /// already, no window or root controller, another screen already on top, the app not active), it ends as not answered:
    /// refused, the slot released, NO decline stored (fail closed, addendum review).
    func present(_ serial: Int, message: String) {
        present(serial, title: AddressPrompt.title, message: message, declineButton: "Don't use it", acceptButton: "Use it")
    }

    func present(_ serial: Int, title: String, message: String, declineButton: String, acceptButton: String) {
        if !isCurrent(serial) { return }
        let delegate: AppDelegate? = UIApplication.shared.delegate as? AppDelegate
        let root: UIViewController? = delegate?.window?.rootViewController
        guard UIApplication.shared.applicationState == .active, let host = root, host.presentedViewController == nil else {
            AddressPrompt.log("not shown (no screen to show it on, another screen on top, or the app is not active); nothing stored")
            finish(serial, .notAnswered, dismissAlert: false)
            return
        }
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        // Both buttons are `.default`, with no `.cancel` one, on purpose: UIKit maps a `.cancel` action to the Escape key of an
        // iPad keyboard, and a decline is stored only for a real tap on "Don't use it" (addendum review). Unconfirmed on a device.
        alert.addAction(UIAlertAction(title: declineButton, style: .default, handler: { (_: UIAlertAction) -> Void in
            AddressPrompt.shared.finish(serial, .declined, dismissAlert: false)
        }))
        alert.addAction(UIAlertAction(title: acceptButton, style: .default, handler: { (_: UIAlertAction) -> Void in
            AddressPrompt.shared.finish(serial, .accepted, dismissAlert: false)
        }))
        markShown(serial, alert: alert)
        host.present(alert, animated: true, completion: nil)
        // The 120 s limit (PromptGate.answerTimeoutMs): unanswered by then, the prompt is withdrawn as not answered. A timer for
        // a prompt that has already ended does nothing (finish matches the number).
        let deadline: DispatchTime = DispatchTime.now() + DispatchTimeInterval.milliseconds(PromptGate.answerTimeoutMs)
        DispatchQueue.main.asyncAfter(deadline: deadline) {
            AddressPrompt.shared.finish(serial, .notAnswered, dismissAlert: true)
        }
    }

    /// Ends prompt `serial` with `outcome`, ONCE: the first of the buttons, the timeout, a cancel or a failure to show wins and
    /// releases the slot; any later one for the same number finds nothing and returns. Main thread. A button tap needs no
    /// dismiss (the alert closes itself); a timeout or a cancel closes it here.
    func finish(_ serial: Int, _ outcome: PromptGate.Outcome, dismissAlert: Bool) {
        lock.lock()
        guard let pending = current, pending.serial == serial else {
            lock.unlock()
            return
        }
        current = nil
        let alert: UIAlertController? = pending.alert
        lock.unlock()
        if dismissAlert, let shown = alert, shown.presentingViewController != nil {
            shown.dismiss(animated: false, completion: nil)
        }
        pending.completion(outcome)
    }

    /// Withdraws a prompt reserved or on screen, as not answered (refused, slot released, no decline stored). Called by
    /// AppRestart.rebuildRoot, on the main thread, just BEFORE the root view controller is replaced (addendum review): the alert
    /// is presented by that root, and a call left waiting would hang the web app's start-up ask.
    func cancelPending() {
        // AppRestart calls this before every root rebuild, including an explicit restart. Stop a native search too.
        MovedServerCheck.shared.stop()
        lock.lock()
        let serial: Int? = current?.serial
        lock.unlock()
        if let pendingSerial = serial {
            AddressPrompt.log("withdrawn: the screen is being rebuilt; nothing stored")
            finish(pendingSerial, .notAnswered, dismissAlert: true)
        }
    }

    func cancelMovedPending() {
        lock.lock()
        let serial: Int? = current?.kind == .moved ? current?.serial : nil
        lock.unlock()
        if let pendingSerial = serial { finish(pendingSerial, .notAnswered, dismissAlert: true) }
    }

    private func isCurrent(_ serial: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return current?.serial == serial
    }

    private func markShown(_ serial: Int, alert: UIAlertController) {
        lock.lock()
        guard let pending = current, pending.serial == serial else { lock.unlock(); return }
        pending.alert = alert
        if pending.kind == .secure {
            lastSecureShownAtMs = AddressPrompt.monotonicMs()
        } else {
            lastMovedShownAtMs = AddressPrompt.monotonicMs()
        }
        let onShown = pending.onShown
        lock.unlock()
        onShown?()
    }

    /// The prompt's text: the saved server's own address the offer came from, the FULL secure host, and when to accept it
    /// (addendum). It can only SHOW the name and say when to accept; it cannot prove the name is the person's (README limit).
    static func message(home: String, offered: String) -> String {
        let host: String = URLComponents(string: offered)?.host ?? offered
        return "Your server at " + home + " offered this secure address:\n\n" + host
            + "\n\nOnly use it if you just turned on the secure address in your server's Settings \u{2192} Remote Access and this name matches."
    }

    // The wall clock, for the 7-day decline (it is stored and must survive restarts), in milliseconds.
    static func wallClockMs() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000)
    }

    // The monotonic clock (seconds since the phone started), for the 10-minute cooldown within one run, in milliseconds.
    static func monotonicMs() -> Int64 {
        return Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    // One line in the device log. Never an address and never a token.
    static func log(_ text: String) {
        NSLog("%@", "Nightlight address prompt: " + text)
    }
}

/// The web view's URL as the main thread read it for shownPageURL: written on the main thread and read on the waiting one, so
/// it is locked. A read that lands after the wait gave up writes into a box nobody reads any more, which is harmless.
private final class ShownPageAnswer {
    private let lock = NSLock()
    private var value: String? = nil

    func set(_ newValue: String?) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func get() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
