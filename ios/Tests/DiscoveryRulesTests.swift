import Foundation

func runDiscoveryRulesTests() {
    let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    func txt(_ pairs: [(String, String?)]) -> [String: Data?] {
        var attributes: [String: Data?] = [:]
        for (key, value) in pairs { attributes[key] = .some(value.map { Data($0.utf8) }) }
        return attributes
    }

    test("matches needs the same id as the announcement") {
        let health = ServerHealth(id: id, name: "Sample")
        check(DiscoveryRules.matches(health, id), "same id")
        check(!DiscoveryRules.matches(health, "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3d"), "different id")
        check(!DiscoveryRules.matches(health, id.uppercased()), "case differs")
        check(!DiscoveryRules.matches(health, nil), "no TXT id")
        check(!DiscoveryRules.matches(nil, id), "no health")
    }

    test("matches refuses a txt id that is not UUID-shaped even when equal") {
        check(!DiscoveryRules.matches(ServerHealth(id: "not-a-uuid", name: nil), "not-a-uuid"), "invalid shape")
        check(!DiscoveryRules.matches(ServerHealth(id: "", name: nil), ""), "empty id")
    }

    test("fingerprint is the first 8 lower-case letters and digits, like the server's instance name") {
        checkEqual(DiscoveryRules.fingerprint(id), "4f1c2b9e")
        checkEqual(DiscoveryRules.fingerprint("ABCDEF12-3456-7890-abcd-ef0123456789"), "abcdef12")
        checkEqual(DiscoveryRules.fingerprint("a-b-c-d-e-f-g-h-i-j"), "abcdefgh")
        checkEqual(DiscoveryRules.fingerprint("12"), "12")
        checkEqual(DiscoveryRules.fingerprint("!!!---"), "")
        checkEqual(DiscoveryRules.fingerprint(""), "")
        checkEqual(DiscoveryRules.fingerprint(nil), "")
        // Only ASCII survives, as in the server's /[^a-z0-9]/ filter.
        checkEqual(DiscoveryRules.fingerprint("ÄBC12345678"), "bc123456")
        checkEqual(DiscoveryRules.FINGERPRINT_CHARS, 8)
    }

    test("txtId reads a v=1 record") {
        checkEqual(DiscoveryRules.txtId(from: txt([("v", "1"), ("id", id)])), id)
    }

    test("txtId reads keys in any case and tolerates a missing v") {
        checkEqual(DiscoveryRules.txtId(from: txt([("V", "1"), ("ID", id)])), id)
        checkEqual(DiscoveryRules.txtId(from: txt([("id", id)])), id)
    }

    test("txtId ignores a record format it does not know") {
        check(DiscoveryRules.txtId(from: txt([("v", "2"), ("id", id)])) == nil, "version 2")
        check(DiscoveryRules.txtId(from: txt([("v", nil), ("id", id)])) == nil, "valueless version")
        check(DiscoveryRules.txtId(from: txt([("v", "1"), ("V", "1"), ("id", id)])) == nil, "duplicate version")
    }

    test("txtId needs exactly one UUID-shaped id") {
        let bad: [[(String, String?)]] = [
            [("v", "1")], [("v", "1"), ("id", nil)], [("v", "1"), ("id", "")],
            [("v", "1"), ("id", "hello")], [("v", "1"), ("id", " " + id)],
            [("v", "1"), ("id", id + "-extra")],
            [("id", id), ("ID", "00000000-0000-0000-0000-000000000000")],
        ]
        for pairs in bad { check(DiscoveryRules.txtId(from: txt(pairs)) == nil, "accepted bad id record") }
        check(DiscoveryRules.txtId(from: nil) == nil, "nil attributes")
        check(DiscoveryRules.txtId(from: [:]) == nil, "empty attributes")
    }

    test("serverUrl is explicit http with the port") {
        checkEqual(DiscoveryRules.serverUrl("192.168.1.50", 4000), "http://192.168.1.50:4000")
    }

    test("NetService coordinates end in one dot") {
        checkEqual(DiscoveryRules.netServiceType("_nightlight._tcp"), "_nightlight._tcp.")
        checkEqual(DiscoveryRules.netServiceType("_nightlight._tcp."), "_nightlight._tcp.")
        checkEqual(DiscoveryRules.netServiceDomain("local"), "local.")
        checkEqual(DiscoveryRules.netServiceDomain("local."), "local.")
        checkEqual(DiscoveryRules.netServiceDomain(""), "local.")
    }

    // Kotlin currently has no parseFoundUrl unit test; these cover the pure parser being added for save-time use.
    test("parseFoundUrl accepts only a canonical IPv4 URL with an explicit port") {
        let found = DiscoveryRules.parseFoundUrl("http://192.168.1.50:4000")
        checkEqual(found?.host, "192.168.1.50")
        checkEqual(found?.port, 4000)
        checkEqual(DiscoveryRules.parseFoundUrl("http://8.8.8.8:1")?.port, 1,
                   "shape parser leaves address policy to the probe")
        checkEqual(DiscoveryRules.parseFoundUrl("http://10.0.0.1:65535")?.port, 65535)
        for invalid in ["https://192.168.1.50:4000", "HTTP://192.168.1.50:4000",
                        "http://192.168.001.50:4000", "http://192.168.1.50:04000",
                        "http://192.168.1.50:0", "http://192.168.1.50:65536",
                        "http://192.168.1.50:4000/", "http://192.168.1.50:4000/path",
                        "http://192.168.1.50:4000?x=1", "http://192.168.1.50:4000#x",
                        "http://user@192.168.1.50:4000", "http://[::1]:4000",
                        "http://192.168.1.50", "http://192.168.1.50:4x", ""] {
            check(DiscoveryRules.parseFoundUrl(invalid) == nil, "accepted " + invalid)
        }
        check(DiscoveryRules.parseFoundUrl(nil) == nil, "nil URL")
    }
}
