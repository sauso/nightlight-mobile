import Foundation
import Security

// KeychainSessionStore.swift: the Security-framework glue under the saved sign-in (owner, 2026-11-08: iOS phase 2, part 1). The
// iOS half of what Android's KeystoreSessionCipher + PrefsKeyValueStore do: the four Keychain calls (KeychainCalls), the vault
// the plugin uses, and the fresh-install purge. Every decision about what a status MEANS is in KeychainLogic.swift (pure,
// tested by ios/Tests); this file only makes the calls and logs.
//
// Glue, with no test: it imports Security, so CI's swiftc test step cannot compile it (the Simulator build does). Only an iPhone
// proves it, which the PR says (checks 1, 2 and 6 of the plan's phone list).
//
// THE ITEM. One generic-password item per saved address: service `com.sauso.nightlight.session`, account = the vault's slot
// (`s:<saved address>`), the data = the vault's JSON. Its attributes, each on purpose:
//  - kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly: readable once the phone has been unlocked after it started, so a page
//    that reloads while the screen is locked (background audio playing) can still be handed its sign-in; and "ThisDeviceOnly":
//    Apple documents that such an item does not migrate to a new device (a backup restored onto another phone, a transfer), the
//    iOS twin of Android keeping the vault out of cloud backup and device transfer (#31). Set on the ADD only: it is not part
//    of what identifies an item, so putting it in a search would make an item added with another value invisible to the
//    update, whose add would then fail as a duplicate for good.
//  - kSecAttrSynchronizable false, explicitly: never synced through iCloud Keychain. (Swift's `false` here bridges to
//    kCFBooleanFalse; Apple's own Keychain samples pass Swift booleans the same way.) It is also in every search, so the
//    item that is read, updated and deleted is exactly the one that was added.
//
// THE FRESH-INSTALL PURGE. Keychain items SURVIVE deleting the app; UserDefaults does not. Without the purge, deleting and
// reinstalling would come back already holding the old install's sign-in. So the first start of an install (the UserDefaults
// marker is absent) deletes every item under the service before any session call can run (AppDelegate queues it on
// ServerConfigStore.writes first). Unverified on a phone until check 6 of the plan's list ("delete and reinstall: signed out").
//
// Logging: the raw OSStatus of every failed call, through NSLog (Console.app on a Mac, or the device log). Never a token and
// never an address.

enum KeychainSessionStore {

    // The Keychain service every saved sign-in is filed under. The app's bundle id plus `.session`, so nothing else of this app
    // that might use the Keychain later is ever touched by the purge.
    static let service: String = "com.sauso.nightlight.session"

    // The fresh-install marker (UserDefaults, so a reinstall does not have it). Set once the purge has finished.
    private static let freshInstallMarkerKey: String = "nightlight_session_keychain_ready"

    /// The vault the plugin uses. Only ever touched on ServerConfigStore.writes (one FIFO queue), so its calls never overlap.
    static let vault: SessionVault = SessionVault(store: KeychainStore(calls: KeychainSessionStore.calls, logLine: KeychainSessionStore.logLine))

    /// The real Keychain calls.
    static let calls: KeychainCalls = KeychainCalls(
        copy: { account in KeychainSessionStore.copyItem(account) },
        add: { account, data in KeychainSessionStore.addItem(account, data) },
        update: { account, data in KeychainSessionStore.updateItem(account, data) },
        delete: { account in KeychainSessionStore.deleteItem(account) }
    )

    /// Run the purge if this is the first start of an install. Call it on ServerConfigStore.writes (AppDelegate does, at launch),
    /// so it runs before any session call queued after it. Logs what it did.
    static func purgeIfFreshInstall() {
        let defaults = UserDefaults.standard
        let outcome = KeychainLogic.purgeIfFreshInstall(
            markerPresent: { defaults.bool(forKey: KeychainSessionStore.freshInstallMarkerKey) },
            deleteAll: { KeychainSessionStore.deleteEverything() },
            writeMarker: { defaults.set(true, forKey: KeychainSessionStore.freshInstallMarkerKey) }
        )
        switch outcome {
        case .notNeeded:
            break
        case .purged(let removed):
            KeychainSessionStore.logLine("Nightlight session store: first start of this install, removed \(removed) saved sign-in item(s) left by an earlier install")
        case .failed(let status):
            KeychainSessionStore.logLine("Nightlight session store: first-start clean-up failed, status \(status); it runs again at the next start")
        }
    }

    private static func logLine(_ text: String) {
        NSLog("%@", text)
    }

    // What identifies ONE item: class, service, account, and not synchronizable. No accessibility (see the header).
    private static func itemQuery(_ account: String) -> [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainSessionStore.service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    private static func copyItem(_ account: String) -> (status: Int32, data: Data?) {
        var query = KeychainSessionStore.itemQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef? = nil
        let status: OSStatus = SecItemCopyMatching(query as CFDictionary, &result)
        let data: Data? = result as? Data
        return (status: status, data: data)
    }

    private static func addItem(_ account: String, _ data: Data) -> Int32 {
        var attributes = KeychainSessionStore.itemQuery(account)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        attributes[kSecValueData as String] = data
        return SecItemAdd(attributes as CFDictionary, nil)
    }

    // SecItemUpdate's second dictionary holds ONLY what changes, the data: the class, service, account and accessibility are not
    // attributes to update (the class may not appear there at all, and the others would be changes to the item's identity).
    private static func updateItem(_ account: String, _ data: Data) -> Int32 {
        let changes: [String: Any] = [kSecValueData as String: data]
        return SecItemUpdate(KeychainSessionStore.itemQuery(account) as CFDictionary, changes as CFDictionary)
    }

    private static func deleteItem(_ account: String) -> Int32 {
        return SecItemDelete(KeychainSessionStore.itemQuery(account) as CFDictionary)
    }

    // Every item under the service, synchronizable or not (kSecAttrSynchronizableAny: this app never makes a synced one, but the
    // purge should not depend on that), counted first for the log and then deleted. On iOS, SecItemDelete removes EVERY item the
    // query matches. The count is best effort: a listing that fails only makes the log say 0.
    private static func deleteEverything() -> (status: Int32, removed: Int) {
        let scope: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainSessionStore.service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
        ]
        var listing = scope
        listing[kSecMatchLimit as String] = kSecMatchLimitAll
        listing[kSecReturnAttributes as String] = true
        var found: CFTypeRef? = nil
        let listed: OSStatus = SecItemCopyMatching(listing as CFDictionary, &found)
        var count = 0
        if listed == errSecSuccess, let items = found as? NSArray {
            count = items.count
        }
        let status: OSStatus = SecItemDelete(scope as CFDictionary)
        return (status: status, removed: count)
    }
}
