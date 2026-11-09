import Foundation

// EndpointProbeTests.swift: the pure half of EndpointProbe, with no network. Two Android files are mirrored here:
//   - DiscoveryRulesTest.kt's parseHealth, name-cleaning and isPlausibleId cases (one parser, fail closed; the fixtures are
//     hostile because a health reply can come from anything), and
//   - EndpointProbeTest.kt's status, body and size cases, through acceptReply (the decision fetchHealth makes on a finished
//     request), plus the "answered from the address asked" check that backs up the redirect refusal.
// NOT tested here, stated: the URLSession glue itself (that a redirect is really refused, that a silent server ends at the
// timeout, that nothing listening is a failure). Android tests those against a local socket server; on iOS they need the
// network and a phone, so they are part of the device checklist.

func runEndpointProbeTests() {
    let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"

    // `name: nil` leaves the field out; `name: "null"` sends JSON null. `idValue: nil` sends the fixture's id.
    func healthJson(ok: String = "true", app: String = "\"nightlight\"", idValue: String? = nil, name: String? = "\"Nursery\"") -> String {
        let idText: String = idValue ?? "\"\(id)\""
        var nameField = ""
        if let n = name {
            nameField = ",\"name\":\(n)"
        }
        return "{\"ok\":\(ok),\"app\":\(app),\"id\":\(idText)\(nameField)}"
    }

    func parse(_ body: String) -> ServerHealth? {
        return EndpointProbe.parseHealth(Data(body.utf8))
    }

    func scalars(_ text: String?) -> [UInt32] {
        guard let value = text else { return [] }
        return value.unicodeScalars.map { $0.value }
    }

    // ---- parseHealth (DiscoveryRulesTest.kt) ----

    test("a valid health reply parses") {
        checkEqual(parse(healthJson()), ServerHealth(id: id, name: "Nursery"))
    }

    test("extra unknown fields are allowed") {
        let body = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"" + id + "\",\"name\":\"Home\",\"version\":\"9.9\",\"extra\":{\"a\":[1,2]}}"
        checkEqual(parse(body), ServerHealth(id: id, name: "Home"))
    }

    test("ok must be the boolean true") {
        // "1" matters most on iOS: JSONSerialization gives true and 1 as the same NSNumber class, and Swift reads 1 as true.
        for ok in ["false", "\"true\"", "1", "0", "null", "{}"] {
            checkEqual(parse(healthJson(ok: ok)), nil, "ok = " + ok)
        }
        checkEqual(parse("{\"app\":\"nightlight\",\"id\":\"" + id + "\"}"), nil, "no ok")
    }

    test("app must be exactly nightlight") {
        for app in ["\"Nightlight\"", "\"nightlight \"", "\"other\"", "\"\"", "null", "1", "true", "[\"nightlight\"]"] {
            checkEqual(parse(healthJson(app: app)), nil, "app = " + app)
        }
        checkEqual(parse("{\"ok\":true,\"id\":\"" + id + "\"}"), nil, "no app")
    }

    test("id must be a non-empty string") {
        let wrongIds: [String] = ["\"\"", "null", "42", "true", "[\"\(id)\"]", "{\"id\":\"\(id)\"}"]
        for value in wrongIds {
            checkEqual(parse(healthJson(idValue: value)), nil, "id = " + value)
        }
        checkEqual(parse("{\"ok\":true,\"app\":\"nightlight\"}"), nil, "no id")
    }

    test("the old ok-only reply is not a Nightlight we can verify") {
        checkEqual(parse("{\"ok\":true}"), nil)
    }

    test("non-objects, nesting, truncation and empty bodies are refused") {
        let valid = healthJson()
        let bodies: [String] = [
            "[" + valid + "]",
            "null",
            "true",
            "\"" + id + "\"",
            "{\"health\":" + valid + "}",
            String(valid.dropLast()),
            "{\"ok\":true,\"app\":\"nightl",
            "",
            "   ",
            "<html><body>ok</body></html>",
        ]
        for body in bodies {
            checkEqual(parse(body), nil, "accepted: " + body)
        }
    }

    test("a deeply nested body is refused, not a crash") {
        // save() parses a reply of any size, so a deep `[[[[...` must be a refusal. JSONSerialization stops at its own nesting
        // limit with an error; if it ever recursed without one, this test would crash the runner (the RUN line names it).
        let deep = String(repeating: "[", count: 100_000) + String(repeating: "]", count: 100_000)
        checkEqual(parse(deep), nil, "a bare deep array")
        checkEqual(parse("{\"x\":" + deep + "}"), nil, "a deep array inside an object")
    }

    test("name is optional, and null or absent means no name") {
        checkEqual(parse(healthJson(name: nil)), ServerHealth(id: id, name: nil), "absent")
        checkEqual(parse(healthJson(name: "null")), ServerHealth(id: id, name: nil), "JSON null")
    }

    test("a name of the wrong type refuses the whole reply") {
        for name in ["42", "true", "[\"Home\"]", "{\"n\":\"Home\"}"] {
            checkEqual(parse(healthJson(name: name)), nil, "name = " + name)
        }
    }

    test("control and invisible characters are stripped from the name, then it is trimmed") {
        // \u0007 bell, \u001b escape, \u007f delete, \u0085 next-line (C1), \u202e right-to-left override (would make the name
        // read backwards), \u200b zero-width space, \u2028 line separator. Written as JSON escapes.
        let raw = "\"  Nu\\u0007rs\\u001bery\\u007f\\u0085\\u202e\\u200b\\u2028 \""
        checkEqual(parse(healthJson(name: raw))?.name, "Nursery")
    }

    test("a name that is only invisible characters means no name") {
        let blank = parse(healthJson(name: "\"\\u0000\\u202e \\t\""))
        check(blank != nil, "the reply itself was refused")
        checkEqual(blank?.name ?? "<nil>", "<nil>", "only invisible characters")
        let empty = parse(healthJson(name: "\"\""))
        check(empty != nil, "the reply itself was refused")
        checkEqual(empty?.name ?? "<nil>", "<nil>", "an empty name")
    }

    test("characters that render blank are dropped, and a name of only them means no name") {
        // U+3164 Hangul filler, U+2800 blank Braille, U+FFA0 halfwidth Hangul filler (Android, code review of #20).
        checkEqual(parse(healthJson(name: "\"Ho\\u3164me\\u2800\\uFFA0\""))?.name, "Home")
        let blank = parse(healthJson(name: "\"\\u3164\\u2800\\uFFA0\\u115F\\u1160\""))
        check(blank != nil, "the reply itself was refused")
        checkEqual(blank?.name ?? "<nil>", "<nil>")
    }

    test("a run of combining marks is cut to 3") {
        // "e" with five combining acute accents keeps three; separate letters keep their own marks. Compared scalar by scalar:
        // Swift's == on strings is canonical equivalence, which would hide a difference in the marks.
        let name = parse(healthJson(name: "\"e\\u0301\\u0301\\u0301\\u0301\\u0301x\\u0301\""))?.name
        checkEqual(scalars(name), [0x65, 0x301, 0x301, 0x301, 0x78, 0x301])
    }

    test("a 10 000-character name is cut to 64 characters") {
        let long = String(repeating: "N", count: 10_000)
        let name = parse(healthJson(name: "\"" + long + "\""))?.name
        checkEqual(scalars(name).count, EndpointProbe.maxNameChars)
        checkEqual(EndpointProbe.maxNameChars, 64)
    }

    test("the cut never splits a surrogate pair") {
        // 70 moons (U+1F319, two UTF-16 units each): 64 code points survive, 128 UTF-16 units, the last one a low surrogate.
        let moons = String(repeating: "\u{1F319}", count: 70)
        let name = parse(healthJson(name: "\"" + moons + "\""))?.name ?? ""
        checkEqual(name.unicodeScalars.count, 64)
        checkEqual(name.utf16.count, 128)
        let last: UInt16 = name.utf16.last ?? 0
        check(last >= 0xDC00 && last <= 0xDFFF, "the last UTF-16 unit is not a low surrogate")
    }

    test("a name exactly 64 characters long is kept whole") {
        let exact = String(repeating: "A", count: 63) + "Z"
        checkEqual(parse(healthJson(name: "\"" + exact + "\""))?.name, exact)
    }

    test("isPlausibleId accepts a UUID in either case and nothing else") {
        check(EndpointProbe.isPlausibleId(id), "lower case")
        check(EndpointProbe.isPlausibleId(id.uppercased()), "upper case")
        check(!EndpointProbe.isPlausibleId("4f1c2b9e7a3d4e219c5b6d8e0f1a2b3c"), "no dashes")
        check(!EndpointProbe.isPlausibleId("g763c3b6-1f2e-4d5c-9b8a-0123456789ab"), "a g")
        check(!EndpointProbe.isPlausibleId(id + "\n"), "a trailing line break")
        check(!EndpointProbe.isPlausibleId(nil), "nil")
    }

    // ---- acceptReply: the decision on a finished request (EndpointProbeTest.kt) ----

    let validBody = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"" + id + "\",\"name\":\"Nursery\"}"

    test("a healthy server answers with its id and name") {
        let health = EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(validBody.utf8))
        checkEqual(health?.id, id)
        checkEqual(health?.name, "Nursery")
    }

    test("any status but 200 is a failure, including a redirect that was not followed") {
        for status in [404, 500, 204, 401, 302, 301, 0] {
            checkEqual(EndpointProbe.acceptReply(status: status, sameAddress: true, body: Data(validBody.utf8)), nil, "status " + String(status))
        }
    }

    test("an answer from another address than the one asked is a failure (a redirect followed anyway)") {
        checkEqual(EndpointProbe.acceptReply(status: 200, sameAddress: false, body: Data(validBody.utf8)), nil)
    }

    test("not a nightlight reply is a failure") {
        for body in ["", "<html>router login</html>", "{\"ok\":true}", "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"not-a-uuid\"}"] {
            checkEqual(EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(body.utf8)), nil, "accepted: " + body)
        }
    }

    test("a body over 4 KB is refused, and one of exactly 4 KB is read") {
        checkEqual(EndpointProbe.maxBodyBytes, 4 * 1024)
        let big = validBody + String(repeating: " ", count: EndpointProbe.maxBodyBytes)
        checkEqual(EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(big.utf8)), nil, "over the bound")
        // White space after the object is still valid JSON, so the body can be padded to the exact bound (kills `>=` for `>`).
        let exact = validBody + String(repeating: " ", count: EndpointProbe.maxBodyBytes - validBody.utf8.count)
        checkEqual(exact.utf8.count, EndpointProbe.maxBodyBytes, "fixture length")
        checkEqual(EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(exact.utf8))?.id, id, "exactly at the bound")
        let oneOver = exact + " "
        checkEqual(EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(oneOver.utf8)), nil, "one byte over")
    }

    // ---- sameEndpoint: the backstop to the redirect refusal ----

    test("an answer counts as the asked address only when scheme, host, port and path agree") {
        let asked = URL(string: "https://nightlight.example.ts.net/api/health")!
        check(EndpointProbe.sameEndpoint(asked, asked), "the same URL")
        check(EndpointProbe.sameEndpoint(URL(string: "https://NightLight.Example.ts.net/api/health"), asked), "a host in other case is the same host")
        check(EndpointProbe.sameEndpoint(URL(string: "https://nightlight.example.ts.net:443/api/health"), asked), "the default port written out")
        check(!EndpointProbe.sameEndpoint(URL(string: "https://other.example.ts.net/api/health"), asked), "another host")
        check(!EndpointProbe.sameEndpoint(URL(string: "https://nightlight.example.ts.net/login"), asked), "another path")
        check(!EndpointProbe.sameEndpoint(URL(string: "http://nightlight.example.ts.net/api/health"), asked), "another scheme")
        check(!EndpointProbe.sameEndpoint(URL(string: "https://nightlight.example.ts.net:8443/api/health"), asked), "another port")
        check(!EndpointProbe.sameEndpoint(nil, asked), "no answer")
        let home = URL(string: "http://192.168.1.50:4000/api/health")!
        check(EndpointProbe.sameEndpoint(URL(string: "http://192.168.1.50:4000/api/health"), home), "a home address")
        check(!EndpointProbe.sameEndpoint(URL(string: "http://192.168.1.50/api/health"), home), "the port dropped is another port")
    }

    test("a tailscale address with no saved id never counts as answering") {
        // Tailscale first opens this address before the saved one, so the id check must fail CLOSED: with no id saved the answer
        // is false before any request is made. (A version that compared the live id with the missing one would read nil == nil
        // as a match here, since this name never answers.)
        let ts = "https://nightlight.example.ts.net"
        check(!EndpointProbe.addressAnswers(ts, tailscale: ts, wantedId: nil, expectedId: nil, timeout: 2.0), "a tailscale address with no saved id counted as answering")
    }

    // ---- addressAnswers with the network reads passed in (Codex code review of PR #38) ----
    // The replies go through acceptReply, so the real parsing decides what id (if any) a body carries; no network is used.

    let tsAddress = "https://nightlight.example.ts.net"
    let homeAddress = "http://192.168.1.50:4000"
    let otherId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

    // A health read that answers every address with what acceptReply makes of `body` (a 200 from the address asked).
    func replying(_ body: String) -> (String) -> ServerHealth? {
        return { (_: String) -> ServerHealth? in
            return EndpointProbe.acceptReply(status: 200, sameAddress: true, body: Data(body.utf8))
        }
    }

    test("addressAnswers: a tailscale address counts with the id it was saved for, and not with another one") {
        check(EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: id, expectedId: otherId, readHealth: replying(validBody), reachable: { (_: String) -> Bool in false }),
              "the right id was refused")
        // `reachable` says yes here, so a version that let the tailscale address through the plain path would pass it.
        check(!EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: replying(healthJson(idValue: "\"\(otherId)\"")),
                                            reachable: { (_: String) -> Bool in true }),
              "a reply with another server's id counted")
    }

    test("addressAnswers: a tailscale reply with a null or unreadable id, or no reply, never counts") {
        // `reachable` says yes throughout: only the id check may decide for the tailscale address.
        let alwaysReachable: (String) -> Bool = { (_: String) -> Bool in true }
        check(!EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: replying(healthJson(idValue: "null")), reachable: alwaysReachable),
              "a null id counted")
        check(!EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: replying("<html>router login</html>"), reachable: alwaysReachable),
              "an unreadable reply counted")
        check(!EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: { (_: String) -> ServerHealth? in nil }, reachable: alwaysReachable),
              "no reply counted")
    }

    test("addressAnswers: with no saved id the tailscale address never counts and is not even read") {
        var read = false
        let answered = EndpointProbe.addressAnswers(tsAddress, tailscale: tsAddress, wantedId: nil, expectedId: id,
                                                    readHealth: { (_: String) -> ServerHealth? in
                                                        read = true
                                                        return ServerHealth(id: id, name: nil)
                                                    },
                                                    reachable: { (_: String) -> Bool in true })
        check(!answered, "counted with no saved id")
        check(!read, "the tailscale address was read with no saved id")
    }

    test("addressAnswers: without an expected id the saved address uses reachable without a strict read") {
        var readCount = 0
        let countingRead: (String) -> ServerHealth? = { (_: String) -> ServerHealth? in
            readCount += 1
            return nil
        }
        check(EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: countingRead,
                                           reachable: { (address: String) -> Bool in address == homeAddress }),
              "a reachable saved address was refused")
        check(!EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: id, expectedId: nil, readHealth: countingRead,
                                            reachable: { (_: String) -> Bool in false }),
              "an unreachable saved address counted")
        check(EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: nil, expectedId: nil, readHealth: countingRead,
                                           reachable: { (_: String) -> Bool in true }),
              "the saved address needs no id: a missing one must not refuse it")
        checkEqual(readCount, 0, "the saved address went through the id check")
    }

    test("addressAnswers: known saved id requires the same strict reply") {
        let reachable: (String) -> Bool = { _ in true }
        check(EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: nil, expectedId: id,
                                           readHealth: replying(validBody), reachable: { _ in false }), "matching id refused")
        check(!EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: nil, expectedId: id,
                                            readHealth: replying(healthJson(idValue: "\"\(otherId)\"")), reachable: reachable),
              "different id accepted")
        check(!EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: nil, expectedId: id,
                                            readHealth: replying(healthJson(idValue: "null")), reachable: reachable),
              "reply without an id accepted")
        check(!EndpointProbe.addressAnswers(homeAddress, tailscale: tsAddress, wantedId: nil, expectedId: id,
                                            readHealth: { _ in nil }, reachable: reachable), "no reply accepted")
    }
}
