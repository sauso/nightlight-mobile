import Foundation

// KeychainLogicTests.swift: the decisions around the Keychain calls (KeychainLogic.swift), run against a fake that behaves like
// the Keychain where it matters: an ADD of an account that is already there is refused with errSecDuplicateItem and changes
// nothing, an UPDATE of an account that is not there answers errSecItemNotFound, and any call can be made to answer a chosen
// status. There is no Android twin (Android has no Keychain); these are the cases the Codex and Opus plan review of iOS phase 2
// (2026-11-08) asked for:
//  - the write is UPDATE then ADD only on errSecItemNotFound, because the web app saves twice per sign-in and a plain add would
//    fail the second time (the test "a second save REPLACES the first" fails against a plain-add write);
//  - errSecInteractionNotAllowed (-25308), errSecMissingEntitlement (-34018) and any other status are TRANSIENT: no session, and
//    nothing deleted;
//  - the fresh-install purge writes its marker only after the purge finished.
// The Security calls themselves (KeychainSessionStore.swift) cannot run here: only an iPhone proves them.

/// A Keychain in a dictionary, keyed by account, that records every call in order and can be told to answer a set status.
private final class FakeKeychain {
    var items: [String: Data] = [:]
    var steps: [String] = []
    var forcedCopy: Int32? = nil
    var forcedAdd: Int32? = nil
    var forcedUpdate: Int32? = nil
    var forcedDelete: Int32? = nil

    func table() -> KeychainCalls {
        return KeychainCalls(
            copy: { account in
                self.steps.append("copy")
                if let status = self.forcedCopy { return (status: status, data: nil) }
                if let data = self.items[account] { return (status: KeychainStatus.success, data: data) }
                return (status: KeychainStatus.itemNotFound, data: nil)
            },
            add: { account, data in
                self.steps.append("add")
                if let status = self.forcedAdd { return status }
                // As the Keychain: an add of an account that is already there is refused, and the item is left as it was.
                if self.items[account] != nil { return KeychainStatus.duplicateItem }
                self.items[account] = data
                return KeychainStatus.success
            },
            update: { account, data in
                self.steps.append("update")
                if let status = self.forcedUpdate { return status }
                if self.items[account] == nil { return KeychainStatus.itemNotFound }
                self.items[account] = data
                return KeychainStatus.success
            },
            delete: { account in
                self.steps.append("delete")
                if let status = self.forcedDelete { return status }
                if self.items.removeValue(forKey: account) == nil { return KeychainStatus.itemNotFound }
                return KeychainStatus.success
            }
        )
    }
}

/// What the purge did, step by step, with the marker and the delete's answer set by the test.
private final class PurgeRecorder {
    var marker = false
    var steps: [String] = []
    var deleteStatus: Int32 = KeychainStatus.success
    var deleteCount = 0

    func run() -> KeychainLogic.Purge {
        return KeychainLogic.purgeIfFreshInstall(
            markerPresent: { self.steps.append("read marker"); return self.marker },
            deleteAll: { self.steps.append("delete all"); return (status: self.deleteStatus, removed: self.deleteCount) },
            writeMarker: { self.steps.append("write marker"); self.marker = true }
        )
    }
}

func runKeychainLogicTests() {
    let saved = "http://192.168.1.50:4000"
    let slot = "s:" + saved
    let ts = "https://nightlight.example.ts.net"
    let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    let token = "aaa.bbb.ccc"
    let first = Data("first".utf8)
    let second = Data("second".utf8)
    let unbound = SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: saved, tailscaleUrl: nil, tailscaleId: nil))
    let bound = SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: saved, tailscaleUrl: ts, tailscaleId: id))

    // The vault over the store over the fake: what the plugin runs, minus the Security calls.
    func vaultOver(_ fake: FakeKeychain) -> SessionVault {
        return SessionVault(store: KeychainStore(calls: fake.table(), logLine: { _ in }))
    }

    // ---- the write: update, then add only on errSecItemNotFound ----

    test("keychain: a write to an empty Keychain updates, is told the item is not there, and adds") {
        let fake = FakeKeychain()
        checkEqual(KeychainLogic.write(slot, first, calls: fake.table()), KeychainStatus.success)
        checkEqual(fake.steps, ["update", "add"])
        checkEqual(fake.items[slot], first)
    }

    test("keychain: a write over an existing item updates it once and never adds") {
        let fake = FakeKeychain()
        fake.items[slot] = first
        checkEqual(KeychainLogic.write(slot, second, calls: fake.table()), KeychainStatus.success)
        checkEqual(fake.steps, ["update"], "an add was attempted after a successful update")
        checkEqual(fake.items[slot], second)
    }

    test("keychain: a plain add over an existing item is refused (errSecDuplicateItem), which is why the write never starts with one") {
        let fake = FakeKeychain()
        fake.items[slot] = first
        checkEqual(fake.table().add(slot, second), KeychainStatus.duplicateItem, "the fake must refuse a duplicate add, as the Keychain does")
        checkEqual(fake.items[slot], first, "a refused add changed the item")
        fake.steps = []
        _ = KeychainLogic.write(slot, second, calls: fake.table())
        check(!fake.steps.contains("add"), "the write reached an add on an existing item: " + fake.steps.joined(separator: ","))
        checkEqual(fake.items[slot], second)
    }

    test("keychain: an update that fails for any other reason is returned as it is, with no add") {
        let statuses: [Int32] = [KeychainStatus.interactionNotAllowed, KeychainStatus.missingEntitlement, KeychainStatus.authFailed,
                                 KeychainStatus.decode, KeychainStatus.duplicateItem]
        for status in statuses {
            let fake = FakeKeychain()
            fake.forcedUpdate = status
            checkEqual(KeychainLogic.write(slot, first, calls: fake.table()), status, "status " + String(status))
            checkEqual(fake.steps, ["update"], "an add followed update status " + String(status))
            check(fake.items[slot] == nil, "something was stored after update status " + String(status))
        }
    }

    test("keychain: an add that fails after a not-found update is a failed write") {
        let fake = FakeKeychain()
        fake.forcedAdd = KeychainStatus.missingEntitlement
        checkEqual(KeychainLogic.write(slot, first, calls: fake.table()), KeychainStatus.missingEntitlement)
        check(!vaultOver(fake).save(bound), "a failed add was reported as saved")
    }

    test("keychain: a second save REPLACES the first, on a Keychain that refuses a duplicate add (the web app saves twice)") {
        let fake = FakeKeychain()
        let vault = vaultOver(fake)
        // Right after sign-in: no Tailscale binding yet. Then again once the server has offered its address.
        check(vault.save(unbound), "the first save failed")
        check(vault.save(bound), "the second save failed: a plain add would fail here with errSecDuplicateItem")
        checkEqual(vault.load(saved), SessionVault.Load.found(bound), "the first, unbound entry is still the one stored")
        checkEqual(fake.items.count, 1)
    }

    // ---- the read: only not-found is "nothing there" ----

    test("keychain: a read's status maps to value, absent, unreadable or TRANSIENT, never absent for a failure") {
        checkEqual(KeychainLogic.readResult(status: KeychainStatus.success, data: Data("text".utf8)), StoreRead.value("text"))
        checkEqual(KeychainLogic.readResult(status: KeychainStatus.success, data: nil), StoreRead.unreadable, "success with no data")
        checkEqual(KeychainLogic.readResult(status: KeychainStatus.itemNotFound, data: nil), StoreRead.absent)
        checkEqual(KeychainLogic.readResult(status: KeychainStatus.itemNotFound, data: Data("text".utf8)), StoreRead.absent, "not found wins over stray data")
        let transient: [Int32] = [KeychainStatus.interactionNotAllowed, KeychainStatus.missingEntitlement, KeychainStatus.authFailed,
                                  KeychainStatus.decode, KeychainStatus.duplicateItem, -1, 1]
        for status in transient {
            checkEqual(KeychainLogic.readResult(status: status, data: Data("text".utf8)), StoreRead.unavailable, "status " + String(status))
        }
    }

    test("keychain: a locked phone (-25308) on a read is no session for now, and nothing is deleted") {
        let fake = FakeKeychain()
        let vault = vaultOver(fake)
        _ = vault.save(bound)
        fake.forcedCopy = KeychainStatus.interactionNotAllowed
        fake.steps = []
        checkEqual(vault.load(saved), SessionVault.Load.unavailable)
        checkEqual(vault.clear(saved, onlyIfToken: token), SessionVault.Clear.unavailable, "a busy read reported as something else")
        check(!fake.steps.contains("delete"), "a delete followed a busy read: " + fake.steps.joined(separator: ","))
        check(fake.items[slot] != nil, "the entry is gone")
        fake.forcedCopy = nil
        checkEqual(vault.load(saved), SessionVault.Load.found(bound), "the entry did not come back once the phone was unlocked")
    }

    test("keychain: a missing entitlement (-34018) fails closed: no session, nothing deleted, no save") {
        let fake = FakeKeychain()
        let vault = vaultOver(fake)
        _ = vault.save(bound)
        fake.forcedCopy = KeychainStatus.missingEntitlement
        fake.forcedUpdate = KeychainStatus.missingEntitlement
        fake.steps = []
        checkEqual(vault.load(saved), SessionVault.Load.unavailable)
        check(!vault.save(unbound), "a save was reported through a missing entitlement")
        check(!fake.steps.contains("delete") && !fake.steps.contains("add"), "a delete or an add followed: " + fake.steps.joined(separator: ","))
        fake.forcedCopy = nil
        checkEqual(vault.load(saved), SessionVault.Load.found(bound), "the stored entry changed")
    }

    test("keychain: an item that is not UTF-8 text is deleted like a malformed entry") {
        let fake = FakeKeychain()
        fake.items[slot] = Data([0xC3, 0x28]) // an invalid UTF-8 sequence
        checkEqual(vaultOver(fake).load(saved), SessionVault.Load.nothing)
        check(fake.items[slot] == nil, "not deleted")
    }

    // ---- the delete ----

    test("keychain: a delete's status maps to removed, absent or could-not") {
        checkEqual(KeychainLogic.removeResult(status: KeychainStatus.success), StoreRemove.removed)
        checkEqual(KeychainLogic.removeResult(status: KeychainStatus.itemNotFound), StoreRemove.absent)
        checkEqual(KeychainLogic.removeResult(status: KeychainStatus.interactionNotAllowed), StoreRemove.unavailable)
        checkEqual(KeychainLogic.removeResult(status: KeychainStatus.missingEntitlement), StoreRemove.unavailable)
        checkEqual(KeychainLogic.removeResult(status: -1), StoreRemove.unavailable)
    }

    test("keychain: a sign-out the Keychain refuses is not reported as cleared, and one it does is") {
        let fake = FakeKeychain()
        let vault = vaultOver(fake)
        _ = vault.save(bound)
        fake.forcedDelete = KeychainStatus.interactionNotAllowed
        checkEqual(vault.clear(saved), SessionVault.Clear.unavailable)
        check(fake.items[slot] != nil, "the forced failure did not keep the item")
        fake.forcedDelete = nil
        checkEqual(vault.clear(saved), SessionVault.Clear.cleared)
        checkEqual(vault.clear(saved), SessionVault.Clear.nothing, "a second sign-out finds nothing")
    }

    // ---- the log: the raw status, never the address or the token ----

    test("keychain: a failed call is logged with its raw status and never with the address or the token") {
        let fake = FakeKeychain()
        var lines: [String] = []
        let store = KeychainStore(calls: fake.table(), logLine: { line in lines.append(line) })
        let vault = SessionVault(store: store)
        _ = vault.save(bound)
        checkEqual(lines.count, 0, "a successful save logged something")
        fake.forcedCopy = KeychainStatus.interactionNotAllowed
        fake.forcedUpdate = KeychainStatus.missingEntitlement
        fake.forcedDelete = KeychainStatus.authFailed
        _ = vault.load(saved)
        _ = vault.save(bound)
        _ = vault.clear(saved)
        checkEqual(lines.count, 3, "one line per failed call: " + lines.joined(separator: " | "))
        let all = lines.joined(separator: "\n")
        check(all.contains("-25308") && all.contains("-34018") && all.contains("-25293"), "a raw status is missing: " + all)
        check(!all.contains("192.168.1.50") && !all.contains("ts.net") && !all.contains(token), "the log carries an address or the token: " + all)
    }

    // ---- the fresh-install purge ----

    test("keychain: on a fresh install (no marker) the purge runs and the marker is written AFTER it finished") {
        let recorder = PurgeRecorder()
        recorder.deleteCount = 2
        checkEqual(recorder.run(), KeychainLogic.Purge.purged(2))
        checkEqual(recorder.steps, ["read marker", "delete all", "write marker"])
        check(recorder.marker, "the marker was not written")
    }

    test("keychain: a purge that finds nothing (errSecItemNotFound) still counts as finished") {
        let recorder = PurgeRecorder()
        recorder.deleteStatus = KeychainStatus.itemNotFound
        recorder.deleteCount = 3 // a count that came with not-found is not believed
        checkEqual(recorder.run(), KeychainLogic.Purge.purged(0))
        check(recorder.marker, "the marker was not written")
    }

    test("keychain: a purge that fails does NOT write the marker, so the next start runs it again") {
        let recorder = PurgeRecorder()
        for status in [KeychainStatus.interactionNotAllowed, KeychainStatus.missingEntitlement, KeychainStatus.authFailed] {
            recorder.deleteStatus = status
            recorder.steps = []
            checkEqual(recorder.run(), KeychainLogic.Purge.failed(status))
            checkEqual(recorder.steps, ["read marker", "delete all"], "status " + String(status))
            check(!recorder.marker, "the marker was written after a failed purge, status " + String(status))
        }
        // The next start, with the Keychain available again: it runs, and only now is the marker written.
        recorder.deleteStatus = KeychainStatus.success
        recorder.deleteCount = 1
        checkEqual(recorder.run(), KeychainLogic.Purge.purged(1))
        check(recorder.marker, "the marker was not written once the purge finished")
    }

    test("keychain: the session store is usable after a finished or unneeded purge and NOT after a failed one") {
        check(KeychainLogic.isReady(KeychainLogic.Purge.notNeeded), "a start that needed no purge refused the store")
        check(KeychainLogic.isReady(KeychainLogic.Purge.purged(0)), "a finished purge that found nothing refused the store")
        check(KeychainLogic.isReady(KeychainLogic.Purge.purged(2)), "a finished purge refused the store")
        for status in [KeychainStatus.interactionNotAllowed, KeychainStatus.missingEntitlement, KeychainStatus.authFailed] {
            check(!KeychainLogic.isReady(KeychainLogic.Purge.failed(status)), "a failed purge (" + String(status) + ") let the store be used")
        }
    }

    test("keychain: with the marker present nothing is deleted") {
        let recorder = PurgeRecorder()
        recorder.marker = true
        checkEqual(recorder.run(), KeychainLogic.Purge.notNeeded)
        checkEqual(recorder.steps, ["read marker"], "a delete ran on an install that already had the marker")
    }

    test("keychain: the status numbers are Apple's") {
        // Each is the value of the Security constant it names (SecBase.h). Pinned so a typo cannot quietly turn a locked phone
        // into "not found" (which would make a busy Keychain read as "signed out").
        checkEqual(KeychainStatus.success, 0)
        checkEqual(KeychainStatus.itemNotFound, -25300)
        checkEqual(KeychainStatus.duplicateItem, -25299)
        checkEqual(KeychainStatus.interactionNotAllowed, -25308)
        checkEqual(KeychainStatus.missingEntitlement, -34018)
        checkEqual(KeychainStatus.authFailed, -25293)
        checkEqual(KeychainStatus.decode, -26275)
    }
}
