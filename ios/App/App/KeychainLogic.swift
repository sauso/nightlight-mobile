import Foundation

// KeychainLogic.swift: the decisions around the iOS Keychain calls that keep the saved sign-in (owner, 2026-11-08: iOS phase 2,
// part 1), as pure functions over an injected table of the four Keychain calls (copy, add, update, delete), each returning the
// Security framework's raw status. KeychainSessionStore.swift fills the table with SecItemCopyMatching / SecItemAdd /
// SecItemUpdate / SecItemDelete; ios/Tests fill it with a fake that behaves like the Keychain (it REFUSES an add of an account
// that is already there) and with the exact statuses the real one returns.
//
// Foundation only, no Security import, on purpose: CI compiles this file with swiftc and runs ios/Tests against exactly this
// code (build-ios.yml). That is why the statuses are plain numbers here (KeychainStatus).
//
// Why this split exists (Codex and Opus plan review of iOS phase 2, 2026-11-08): the obvious Keychain write, a plain SecItemAdd,
// fails with errSecDuplicateItem when the item is already there, and the web app ALWAYS saves twice (first right after sign-in
// with no Tailscale binding, then again once the server has offered its Tailscale address). With a plain add the second save
// would fail and the unbound first entry would stay forever, so the feature would never work. The mapping and the
// update-or-add sequence are therefore tested here, where a compiler and a test run exist, and the glue is only the calls.

/// The Security framework statuses this code acts on. Each value is the Apple constant named beside it (SecBase.h); they are
/// written out because this file must not import Security.
enum KeychainStatus {
    static let success: Int32 = 0                     // errSecSuccess
    static let itemNotFound: Int32 = -25300           // errSecItemNotFound
    static let duplicateItem: Int32 = -25299          // errSecDuplicateItem
    static let interactionNotAllowed: Int32 = -25308  // errSecInteractionNotAllowed (the phone has not been unlocked since it started)
    static let missingEntitlement: Int32 = -34018     // errSecMissingEntitlement (the app is not signed for a keychain access group)
    static let authFailed: Int32 = -25293             // errSecAuthFailed
    static let decode: Int32 = -26275                 // errSecDecode
}

/// The four Keychain calls, for ONE generic-password item named by its account (the vault's slot). Each returns the raw status.
struct KeychainCalls {
    /// SecItemCopyMatching for the item's data: the status, and the data when there was some.
    var copy: (_ account: String) -> (status: Int32, data: Data?)
    /// SecItemAdd of a new item with this data.
    var add: (_ account: String, _ data: Data) -> Int32
    /// SecItemUpdate of an existing item's data only.
    var update: (_ account: String, _ data: Data) -> Int32
    /// SecItemDelete of the item.
    var delete: (_ account: String) -> Int32
}

enum KeychainLogic {

    /// What a copy's status means for the vault. Only "not found" is "nothing there". EVERY other failure is TRANSIENT
    /// (`unavailable`), never "nothing there": errSecInteractionNotAllowed is the expected one (a locked phone before its first
    /// unlock), and an unknown status (errSecMissingEntitlement, errSecAuthFailed, errSecDecode, anything new) is treated the same
    /// way, so nothing is deleted on a status this code does not understand. Success with no data, or with data that is not
    /// UTF-8 text, is `unreadable`: something is there that this app did not write as it writes, and the vault deletes it.
    static func readResult(status: Int32, data: Data?) -> StoreRead {
        if status == KeychainStatus.itemNotFound { return .absent }
        if status != KeychainStatus.success { return .unavailable }
        guard let bytes = data, let text = String(data: bytes, encoding: .utf8) else { return .unreadable }
        return .value(text)
    }

    /// Save or replace one item: UPDATE first, and ADD only when the update says the item is not there (errSecItemNotFound). Any
    /// other update failure is returned as it is, with no add: an add is only right when the item is KNOWN not to be there, and
    /// after, say, errSecInteractionNotAllowed nothing is known. Returns the final status; errSecSuccess means written.
    ///
    /// Update first, not add first: the item is usually there already (the web app saves twice per sign-in), and an add-first
    /// that then updated on errSecDuplicateItem would be one more call and one more status to get wrong. Everything runs on one
    /// serial queue, so nothing can add the item between the update and the add.
    static func write(_ account: String, _ data: Data, calls: KeychainCalls) -> Int32 {
        let updated = calls.update(account, data)
        if updated != KeychainStatus.itemNotFound { return updated }
        return calls.add(account, data)
    }

    /// What a delete's status means: removed, nothing was there, or (any other status) could not be done right now.
    static func removeResult(status: Int32) -> StoreRemove {
        if status == KeychainStatus.success { return .removed }
        if status == KeychainStatus.itemNotFound { return .absent }
        return .unavailable
    }

    /// What the fresh-install purge did, for the log.
    enum Purge: Equatable {
        /// The marker was there: not a fresh install, nothing deleted.
        case notNeeded
        /// The purge ran and finished: it removed this many items (0 when there were none), and the marker is now written.
        case purged(Int)
        /// The purge could not finish (this status): the marker is NOT written, so it runs again at the next launch.
        case failed(Int32)
    }

    /// The fresh-install purge. Keychain items SURVIVE deleting the app; UserDefaults does not. So a marker kept in UserDefaults
    /// that is absent means "this is the first start of a new install" (or the first start of the first version that has this
    /// purge), and every item under the session service is deleted, so a reinstall starts signed out, as an Android uninstall
    /// does. The marker is written ONLY after the purge returned errSecSuccess or errSecItemNotFound (Codex and Opus plan review
    /// of iOS phase 2, 2026-11-08): a purge that failed (a locked phone, say) leaves the marker unwritten and simply runs again
    /// at the next launch, rather than leaving an earlier install's sign-in in place for good.
    ///
    /// `deleteAll` deletes every item under the service and says how many there were (counted before the delete, for the log).
    static func purgeIfFreshInstall(markerPresent: () -> Bool, deleteAll: () -> (status: Int32, removed: Int), writeMarker: () -> Void) -> Purge {
        if markerPresent() { return .notNeeded }
        let result = deleteAll()
        if result.status == KeychainStatus.success || result.status == KeychainStatus.itemNotFound {
            writeMarker()
            return .purged(result.status == KeychainStatus.success ? result.removed : 0)
        }
        return .failed(result.status)
    }
}

/// The vault's store over the Keychain calls. Every failure is logged with its raw status (never a token or an address: the
/// log line names the operation only), so a phone's log says what the Keychain answered.
final class KeychainStore: SessionStore {
    private let calls: KeychainCalls
    // Not named `log`: that is also Darwin's logarithm, and a reader should not have to know which one a bare call finds.
    private let logLine: (String) -> Void

    init(calls: KeychainCalls, logLine: @escaping (String) -> Void) {
        self.calls = calls
        self.logLine = logLine
    }

    func read(_ slot: String) -> StoreRead {
        let answer = calls.copy(slot)
        if answer.status != KeychainStatus.success && answer.status != KeychainStatus.itemNotFound {
            self.logLine("Nightlight session store: read failed, status \(answer.status)")
        }
        return KeychainLogic.readResult(status: answer.status, data: answer.data)
    }

    func write(_ slot: String, _ value: String) -> Bool {
        let status = KeychainLogic.write(slot, Data(value.utf8), calls: calls)
        if status != KeychainStatus.success {
            self.logLine("Nightlight session store: write failed, status \(status)")
        }
        return status == KeychainStatus.success
    }

    func remove(_ slot: String) -> StoreRemove {
        let status = calls.delete(slot)
        if status != KeychainStatus.success && status != KeychainStatus.itemNotFound {
            self.logLine("Nightlight session store: delete failed, status \(status)")
        }
        return KeychainLogic.removeResult(status: status)
    }
}
