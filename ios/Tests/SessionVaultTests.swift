import Foundation

// SessionVaultTests.swift: the iOS twin of android/app/src/test/java/com/sauso/nightlight/SessionVaultTest.kt, over a dictionary
// store behind the same SessionStore protocol the Keychain sits behind (KeychainLogicTests runs the vault over a fake Keychain
// too). What it pins: the entry round trips, is refused and deleted when it is in another address's slot, malformed or the wrong
// shape, is replaced and not duplicated, is removed by a clear that needs no network, and is removed on a stale token only when
// the token matches; and a store that is only BUSY deletes nothing and is never reported as "nothing there".
//
// Android cases DROPPED ON PURPOSE (Codex and Opus plan review of iOS phase 2, 2026-11-08), because iOS has no cipher of its own:
// "the saved address is the AAD on seal and open", "the plain text of the token is not in what is stored", "a fresh IV per
// encryption", "a blob with the wrong IV or tag size", "a lost key is replaced", "a key that stays unusable", "a replaced key
// makes old entries unreadable". The Keychain item is the protected storage, and the AAD's job (a blob moved to another slot is
// refused) is done by the stamp check, which "an entry copied to another address's slot" below pins. A blob tampered so that it
// still parses (a changed token) would be accepted: only this app can write its Keychain items, which is the protection there.

private enum VaultFixture {
    static let saved = "http://192.168.1.50:4000"
    static let other = "http://192.168.1.51:4000"
    static let ts = "https://nightlight.example.ts.net"
    static let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    static let token = "aaa.bbb.ccc"
}

// Android's `entry(address = saved, t = token)`.
private func vaultEntry(address: String = VaultFixture.saved, token: String = VaultFixture.token) -> SessionRules.Entry {
    return SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: address, tailscaleUrl: VaultFixture.ts, tailscaleId: VaultFixture.id))
}

// The token of a load that found an entry, else nil.
private func loadedToken(_ result: SessionVault.Load) -> String? {
    if case .found(let entry) = result { return entry.token }
    return nil
}

/// A dictionary behind the SessionStore protocol. Failures can be forced: a read that answers something other than what is
/// stored (busy, not text), a write that fails, a removal that fails. Removals are counted.
private final class MemorySessionStore: SessionStore {
    var slots: [String: String] = [:]
    var forcedRead: StoreRead? = nil
    var writeFails = false
    var forcedRemove: StoreRemove? = nil
    var removals = 0

    func read(_ slot: String) -> StoreRead {
        if let forced = forcedRead { return forced }
        guard let text = slots[slot] else { return .absent }
        return .value(text)
    }

    func write(_ slot: String, _ value: String) -> Bool {
        if writeFails { return false }
        slots[slot] = value
        return true
    }

    func remove(_ slot: String) -> StoreRemove {
        removals += 1
        if let forced = forcedRemove { return forced }
        if slots.removeValue(forKey: slot) == nil { return .absent }
        return .removed
    }
}

func runSessionVaultTests() {
    let saved = VaultFixture.saved
    let other = VaultFixture.other
    let ts = VaultFixture.ts
    let id = VaultFixture.id
    let token = VaultFixture.token
    let savedSlot = "s:" + saved
    let otherSlot = "s:" + other

    test("vault: an entry round trips with everything it was stamped with") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        check(vault.save(vaultEntry()), "the save failed")
        checkEqual(vault.load(saved), SessionVault.Load.found(vaultEntry()))
    }

    test("vault: an entry saved with no Tailscale binding round trips as it was, with the nulls") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        let bare = SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: saved, tailscaleUrl: nil, tailscaleId: nil))
        check(vault.save(bare), "the save failed")
        checkEqual(vault.load(saved), SessionVault.Load.found(bare))
    }

    test("vault: nothing saved is no session") {
        let vault = SessionVault(store: MemorySessionStore())
        checkEqual(vault.load(saved), SessionVault.Load.nothing)
    }

    test("vault: the slot is the saved address with Android's s: prefix, so one address has one entry") {
        checkEqual(SessionVault.slot(saved), "s:http://192.168.1.50:4000")
        let store = MemorySessionStore()
        _ = SessionVault(store: store).save(vaultEntry())
        checkEqual(Array(store.slots.keys), [savedSlot])
    }

    test("vault: an entry copied from one address's slot to another's is refused, and is deleted") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry(address: saved))
        store.slots[otherSlot] = store.slots[savedSlot] // the attack: move the entry under another address
        checkEqual(vault.load(other), SessionVault.Load.nothing)
        check(store.slots[otherSlot] == nil, "the moved entry was not deleted")
        checkEqual(vault.load(saved), SessionVault.Load.found(vaultEntry()), "the real one must be untouched")
    }

    test("vault: an entry stamped with a different address than its slot is deleted even though it reads") {
        let stamped = SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: other, tailscaleUrl: nil, tailscaleId: nil))
        guard let text = SessionVault.encode(stamped) else {
            check(false, "the fixture could not be encoded")
            return
        }
        check(SessionVault.decode(text) != nil, "the fixture must READ, so the refusal below is the stamp check and not the decode")
        let store = MemorySessionStore()
        store.slots[savedSlot] = text
        checkEqual(SessionVault(store: store).load(saved), SessionVault.Load.nothing)
        check(store.slots[savedSlot] == nil, "not deleted")
    }

    test("vault: a tampered, truncated, malformed or wrong-shape entry is no session and is deleted") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry())
        let good: String = store.slots[savedSlot] ?? ""
        check(!good.isEmpty, "the fixture was not saved")
        let bad: [String] = [
            String(good.dropLast(2)), // truncated
            String(good.dropFirst()), // the opening brace gone
            "[" + String(good.dropFirst()), // an array where the object was
            "",
            "garbage",
            "v1:00:00", // Android's stored form: not what this vault writes
            "null",
            "[]",
            "{}",
            "{\"token\":\"aaa.bbb.ccc\"}", // no savedUrl
            "{\"savedUrl\":\"http://192.168.1.50:4000\"}", // no token
            "{\"token\":\"\",\"savedUrl\":\"http://192.168.1.50:4000\"}", // an empty token
            "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":\"\"}", // an empty saved address
            "{\"token\":5,\"savedUrl\":\"http://192.168.1.50:4000\"}", // a number for the token
            "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":null}",
            "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":\"http://192.168.1.50:4000\",\"tailscaleUrl\":5,\"tailscaleId\":null}",
            "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":\"http://192.168.1.50:4000\",\"tailscaleUrl\":null,\"tailscaleId\":true}",
            "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":\"http://192.168.1.50:4000\",\"tailscaleUrl\":[\"https://nightlight.example.ts.net\"],\"tailscaleId\":null}",
        ]
        for value in bad {
            store.slots[savedSlot] = value
            checkEqual(vault.load(saved), SessionVault.Load.nothing, "accepted: " + value)
            check(store.slots[savedSlot] == nil, "not deleted: " + value)
        }
    }

    test("vault: the stored form is one JSON object with the four fields, nulls for no binding") {
        let bare = SessionRules.Entry(token: token, bindings: SessionRules.Bindings(savedUrl: saved, tailscaleUrl: nil, tailscaleId: nil))
        guard let text = SessionVault.encode(bare), let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
            check(false, "not a JSON object")
            return
        }
        checkEqual(object.keys.sorted(), ["savedUrl", "tailscaleId", "tailscaleUrl", "token"])
        checkEqual(object["token"] as? String, token)
        checkEqual(object["savedUrl"] as? String, saved)
        check(object["tailscaleUrl"] is NSNull, "tailscaleUrl must be JSON null")
        check(object["tailscaleId"] is NSNull, "tailscaleId must be JSON null")
        // An empty string reads as no binding (Android's ifEmpty { null }), and unknown extra fields are ignored.
        let lenient = "{\"token\":\"aaa.bbb.ccc\",\"savedUrl\":\"http://192.168.1.50:4000\",\"tailscaleUrl\":\"\",\"extra\":1}"
        checkEqual(SessionVault.decode(lenient), bare)
        checkEqual(SessionVault.decode(SessionVault.encode(vaultEntry()) ?? ""), vaultEntry(), "a bound entry round trips through the text")
    }

    test("vault: one entry per saved address, so a second save replaces and two addresses are independent") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry(address: saved, token: "one.two.three"))
        _ = vault.save(vaultEntry(address: saved, token: "four.five.six"))
        _ = vault.save(vaultEntry(address: other, token: "seven.eight.nine"))
        checkEqual(store.slots.count, 2)
        checkEqual(loadedToken(vault.load(saved)), "four.five.six")
        checkEqual(loadedToken(vault.load(other)), "seven.eight.nine")
    }

    test("vault: clear removes only that address and needs nothing else") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry(address: saved))
        _ = vault.save(vaultEntry(address: other))
        checkEqual(vault.clear(saved), SessionVault.Clear.cleared)
        checkEqual(vault.load(saved), SessionVault.Load.nothing)
        checkEqual(loadedToken(vault.load(other)), token)
        checkEqual(vault.clear(saved), SessionVault.Clear.nothing, "nothing left to clear")
    }

    test("vault: clear with a token removes the entry only when it is the stored token") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry(token: "current.token.value"))
        checkEqual(vault.clear(saved, onlyIfToken: "old.token.value"), SessionVault.Clear.otherToken,
                   "a rejection of an OLD token must not delete a newer login")
        checkEqual(loadedToken(vault.load(saved)), "current.token.value")
        checkEqual(vault.clear(saved, onlyIfToken: "current.token.value"), SessionVault.Clear.cleared)
        checkEqual(vault.load(saved), SessionVault.Load.nothing)
    }

    test("vault: clear with a token on an unreadable entry removes it and reports nothing cleared") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry())
        store.slots[savedSlot] = "v1:00:00"
        checkEqual(vault.clear(saved, onlyIfToken: token), SessionVault.Clear.nothing)
        check(store.slots[savedSlot] == nil, "not deleted")
    }

    test("vault: a store that fails to write fails the save and deletes nothing, not even the old entry") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry(token: "kept.token.value"))
        store.writeFails = true
        check(!vault.save(vaultEntry(token: "new.token.value")), "a failed write was reported as saved")
        store.writeFails = false
        checkEqual(loadedToken(vault.load(saved)), "kept.token.value")
        checkEqual(store.removals, 0, "nothing may be removed on a failed write")
    }

    test("vault: a busy store on a read is no session for now, but the entry is kept") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry())
        store.forcedRead = .unavailable
        checkEqual(vault.load(saved), SessionVault.Load.unavailable)
        check(store.slots[savedSlot] != nil, "a transient failure must not delete a good entry")
        checkEqual(store.removals, 0, "nothing may even be asked to be removed")
        store.forcedRead = nil
        checkEqual(vault.load(saved), SessionVault.Load.found(vaultEntry()))
    }

    test("vault: a busy store is never reported as nothing to clear, and deletes nothing (iOS)") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry())
        // A sign-out after a 401 (with the token): the entry cannot be read, so it cannot be compared, so it is kept.
        store.forcedRead = .unavailable
        checkEqual(vault.clear(saved, onlyIfToken: token), SessionVault.Clear.unavailable)
        check(store.slots[savedSlot] != nil, "a busy read deleted the entry")
        checkEqual(store.removals, 0, "a busy read must not lead to a removal")
        // A plain sign-out (no token) removes directly, with no read first; a removal the store cannot do is reported as such.
        store.forcedRemove = .unavailable
        checkEqual(vault.clear(saved), SessionVault.Clear.unavailable)
        check(store.slots[savedSlot] != nil, "the forced failure did not keep the entry")
        // ...and a plain sign-out does not depend on reading at all: with reads still busy, the removal works.
        store.forcedRemove = nil
        checkEqual(vault.clear(saved), SessionVault.Clear.cleared, "a busy READ must not stop a plain sign-out from removing the entry")
        check(store.slots[savedSlot] == nil, "not removed")
    }

    test("vault: something that is not text in the slot is deleted, like a malformed entry (iOS)") {
        let store = MemorySessionStore()
        let vault = SessionVault(store: store)
        _ = vault.save(vaultEntry())
        store.forcedRead = .unreadable
        checkEqual(vault.load(saved), SessionVault.Load.nothing)
        check(store.slots[savedSlot] == nil, "not deleted")
    }

    test("vault: any other failure to write is a failed save, not a crash") {
        let store = MemorySessionStore()
        store.writeFails = true
        check(!SessionVault(store: store).save(vaultEntry()), "a failed write was reported as saved")
        check(store.slots.isEmpty, "something was stored")
    }

    test("vault: the fixtures are what they say") {
        // Guards the tests above against a fixture typo that would make two addresses or two ids the same.
        check(saved != other, "two addresses")
        check(EndpointRules.isTailscaleUrl(ts), "the Tailscale fixture")
        check(EndpointProbe.isPlausibleId(id), "the id fixture")
        check(SessionRules.isPlausibleToken(token), "the token fixture")
    }
}
