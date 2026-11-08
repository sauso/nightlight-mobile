import Foundation

// SessionVault.swift: where the session token is kept for the server's other address, one entry per SAVED address (owner,
// 2026-11-08: iOS phase 2, part 1). The iOS twin of android/app/src/main/java/com/sauso/nightlight/SessionVault.kt, over an
// injected store: production uses the iOS Keychain (KeychainStore in KeychainLogic.swift, with the Security calls in
// KeychainSessionStore.swift); ios/Tests use a dictionary.
//
// Foundation only, no Security, UIKit or Capacitor import, on purpose: CI compiles this file with swiftc and runs ios/Tests
// against exactly this code (build-ios.yml).
//
// One entry per saved address (slot `s:<saved address>`), so the number of entries is bounded by the saved-server list and a
// hostile page cannot make more. The entry is JSON `{token, savedUrl, tailscaleUrl, tailscaleId}`: the token and the bindings it
// was saved under (SessionRules.Entry).
//
// DROPPED ON PURPOSE from Android (Codex and Opus plan review of iOS phase 2, 2026-11-08): the AES-GCM sealing, its additional
// authenticated data, the IV and tag size checks and the lost-key reset, with their tests. They exist on Android because the
// entry sits in a SharedPreferences file and the key in the Android Keystore. On iOS the Keychain item IS the protected
// storage (encrypted by the system, readable only by this app, never synced: see KeychainSessionStore), and what Android's
// AAD was for (a blob moved to another address's slot must not be accepted) is done by the item's account (= the slot) plus
// this vault's own check that the stamped savedUrl equals the slot's address.
//
// Not thread safe on its own: every call goes through ONE first-in-first-out queue (ServerConfigStore.writes), so a save, a
// clear and the fresh-install purge can never reorder or race (as Android's single session executor).
//
// Failure rules:
//  - an entry that cannot be read back (not JSON, the wrong shape, not text at all) or whose stamped savedUrl is not the slot's
//    address is "no session" and is DELETED;
//  - a store that is only temporarily unavailable (the Keychain while the phone is locked before its first unlock, or busy)
//    deletes NOTHING and is never reported as "nothing there": the entry may be fine, so the call reports `unavailable` and a
//    later call can succeed (Opus plan review: a busy Keychain must never read as "nothing to clear").

/// What the store found in one slot.
enum StoreRead: Equatable {
    /// The text stored in the slot.
    case value(String)
    /// Nothing is stored there.
    case absent
    /// Something is stored there, but it is not text: not something this app wrote, so the vault deletes it, as a malformed
    /// entry. (A fourth case beside the three the plan names, so "not text" is never confused with "nothing there" or "busy".)
    case unreadable
    /// TRANSIENT: the store cannot be read right now. Nothing is known about the slot, so nothing is deleted.
    case unavailable
}

/// What a removal did.
enum StoreRemove: Equatable {
    case removed
    case absent
    /// TRANSIENT: it could not be removed right now; it may still be there.
    case unavailable
}

/// The key-value store the vault sits on: a string per slot. Production: the iOS Keychain (KeychainStore).
protocol SessionStore {
    func read(_ slot: String) -> StoreRead
    /// Save or REPLACE the slot's value. False when it could not be written.
    func write(_ slot: String, _ value: String) -> Bool
    func remove(_ slot: String) -> StoreRemove
}

final class SessionVault {

    /// What `load` found.
    enum Load: Equatable {
        case found(SessionRules.Entry)
        /// No session: nothing saved, or what was there could not be read (and was deleted).
        case nothing
        /// The store could not be read right now: no session for now, nothing deleted.
        case unavailable
    }

    /// What `clear` did. Only `cleared` removed an entry; the plugin answers `{cleared}` with exactly that.
    enum Clear: Equatable {
        case cleared
        /// There was nothing to clear (or an unreadable entry, which reading it has just deleted).
        case nothing
        /// A different token is stored: it is left alone (a rejection of an OLD token must not delete a newer sign-in).
        case otherToken
        /// The store could not be read or changed right now: nothing was deleted, and nothing is known.
        case unavailable
    }

    private let store: SessionStore

    init(store: SessionStore) {
        self.store = store
    }

    /// The slot of a saved address. The same `s:` prefix as Android's.
    static func slot(_ savedUrl: String) -> String {
        return "s:" + savedUrl
    }

    /// Save (or replace) the entry for its bindings' saved address. False when it could not be saved.
    func save(_ entry: SessionRules.Entry) -> Bool {
        guard let text = SessionVault.encode(entry) else { return false }
        return store.write(SessionVault.slot(entry.bindings.savedUrl), text)
    }

    /// The entry saved for `savedUrl`. An entry that cannot be read, or that is stamped for another address than its slot (a
    /// blob copied from one slot to another), is deleted and reported as `nothing`; a store that is busy is `unavailable`.
    func load(_ savedUrl: String) -> Load {
        let key = SessionVault.slot(savedUrl)
        switch store.read(key) {
        case .unavailable:
            return .unavailable
        case .absent:
            return .nothing
        case .unreadable:
            _ = store.remove(key)
            return .nothing
        case .value(let text):
            guard let entry = SessionVault.decode(text), entry.bindings.savedUrl == savedUrl else {
                _ = store.remove(key)
                return .nothing
            }
            return .found(entry)
        }
    }

    /// Remove the entry for `savedUrl`; with `onlyIfToken` set, only when the stored token is exactly that one (a 401 for a stale
    /// token must not delete a newer sign-in). Needs no network.
    ///
    /// Without a token the slot is removed directly, with no read first: Android reads first and treats "nothing read" as
    /// "nothing to clear", which on iOS would turn a busy Keychain into a sign-out that silently left the entry behind. The
    /// removal's own answer says whether anything was there.
    func clear(_ savedUrl: String, onlyIfToken: String? = nil) -> Clear {
        let key = SessionVault.slot(savedUrl)
        guard let wanted = onlyIfToken else {
            return SessionVault.clearResult(store.remove(key))
        }
        switch load(savedUrl) {
        case .unavailable:
            return .unavailable
        case .nothing:
            return .nothing
        case .found(let entry):
            if entry.token != wanted { return .otherToken }
            return SessionVault.clearResult(store.remove(key))
        }
    }

    private static func clearResult(_ removed: StoreRemove) -> Clear {
        switch removed {
        case .removed:
            return .cleared
        case .absent:
            return .nothing
        case .unavailable:
            return .unavailable
        }
    }

    // ---- The stored form: one JSON object, as Android's (before Android encrypts it) ----

    /// The entry as JSON text, or nil if it could not be made (never expected: every value is a string or null).
    static func encode(_ entry: SessionRules.Entry) -> String? {
        let b = entry.bindings
        let object: [String: Any] = [
            "token": entry.token,
            "savedUrl": b.savedUrl,
            "tailscaleUrl": SessionVault.jsonValue(b.tailscaleUrl),
            "tailscaleId": SessionVault.jsonValue(b.tailscaleId),
        ]
        // Checked first: JSONSerialization.data raises an Objective-C exception (a crash, not a Swift error) on an invalid object.
        if !JSONSerialization.isValidJSONObject(object) { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: []) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func jsonValue(_ text: String?) -> Any {
        if let value = text { return value }
        return NSNull()
    }

    /// The entry in `text`, or nil when it is not exactly the shape `encode` writes: a JSON object with a non-empty string
    /// `token` and `savedUrl`, and `tailscaleUrl` / `tailscaleId` each a string, JSON null or absent (an empty string reads as
    /// absent, as Android's `ifEmpty { null }`). Any other type in any of the four fields is the wrong shape, so the whole entry
    /// is refused (stricter than Android's org.json `optString`, which turns a number into text: fails closed). Unknown extra
    /// fields are ignored.
    static func decode(_ text: String) -> SessionRules.Entry? {
        guard let data = text.data(using: .utf8), !data.isEmpty else { return nil }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            return nil
        }
        guard let fields = object as? [String: Any] else { return nil }
        guard let token = fields["token"] as? String, !token.isEmpty else { return nil }
        guard let saved = fields["savedUrl"] as? String, !saved.isEmpty else { return nil }
        var wellFormed = true
        let tailscaleUrl: String? = SessionVault.optionalText(fields["tailscaleUrl"], wellFormed: &wellFormed)
        let tailscaleId: String? = SessionVault.optionalText(fields["tailscaleId"], wellFormed: &wellFormed)
        if !wellFormed { return nil }
        let bindings = SessionRules.Bindings(savedUrl: saved, tailscaleUrl: tailscaleUrl, tailscaleId: tailscaleId)
        return SessionRules.Entry(token: token, bindings: bindings)
    }

    // A field that may be absent: nil for absent, JSON null or an empty string; the text for a string; anything else marks the
    // entry as the wrong shape.
    private static func optionalText(_ value: Any?, wellFormed: inout Bool) -> String? {
        guard let present = value else { return nil }
        if present is NSNull { return nil }
        if let text = present as? String {
            return text.isEmpty ? nil : text
        }
        wellFormed = false
        return nil
    }
}
