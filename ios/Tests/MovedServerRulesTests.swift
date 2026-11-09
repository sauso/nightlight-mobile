import Foundation

func runMovedServerRulesTests() {
    let old = DiscoveryRules.serverUrl("192.168.1.50", 4000)
    let new = DiscoveryRules.serverUrl("192.168.1.51", 4000)
    let id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    let otherId = "5a2d3c8f-6b4e-4f32-8d7c-1e9f0a2b3c4d"
    let pair = "https://server.example.ts.net"

    test("decide: hostname saved skips even when a probe answers") {
        checkEqual(MovedServerRules.decide(savedUrl: "http://server.local:4000", storedId: nil, pairId: nil,
                                           probe: .answered(id: id)), .skip)
    }
    test("decide: https saved skips") {
        checkEqual(MovedServerRules.decide(savedUrl: "https://192.168.1.50:4000", storedId: id, pairId: nil,
                                           probe: .noAnswer), .skip)
    }
    test("decide: public IP saved skips") {
        checkEqual(MovedServerRules.decide(savedUrl: "http://8.8.8.8:4000", storedId: id, pairId: nil,
                                           probe: .noAnswer), .skip)
        checkEqual(MovedServerRules.decide(savedUrl: nil, storedId: id, pairId: nil, probe: .noAnswer), .skip)
    }
    test("decide: Answered nil stops") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: id,
                                           probe: .answered(id: nil)), .stop)
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: nil,
                                           probe: .answered(id: "not-a-uuid")), .stop)
    }
    test("decide: stored nil and Answered id learns the plausible id") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: nil,
                                           probe: .answered(id: id)), .learn(id))
    }
    test("decide: stored id equals answer stops") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: id, pairId: nil,
                                           probe: .answered(id: id)), .stop)
    }
    test("decide: stored id differs from answer, searches and never learns over it") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: id, pairId: nil,
                                           probe: .answered(id: otherId)), .search(id))
        check(MovedServerRules.decide(savedUrl: old, storedId: id, pairId: otherId,
                                      probe: .answered(id: otherId)) != .learn(otherId), "replaced the saved id")
    }
    test("decide: pair-only identity learns the same id but searches for a different one") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: id,
                                           probe: .answered(id: otherId)), .search(id))
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: id,
                                           probe: .answered(id: id)), .learn(id))
    }
    test("decide: NoAnswer with stored id searches for it") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: id, pairId: nil,
                                           probe: .noAnswer), .search(id))
    }
    test("decide: NoAnswer with only a stored pair id searches for it") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: id,
                                           probe: .noAnswer), .search(id))
    }
    test("decide: NoAnswer without either id stops") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: nil, pairId: nil,
                                           probe: .noAnswer), .stop)
    }
    test("decide: stored id wins over a different pair id") {
        checkEqual(MovedServerRules.decide(savedUrl: old, storedId: id, pairId: otherId,
                                           probe: .noAnswer), .search(id))
    }

    test("acceptCandidate: same id at a different private address is offered") {
        check(MovedServerRules.acceptCandidate(wantedId: id, savedUrl: old, foundId: id, foundUrl: new),
              "refused the moved server")
    }
    test("acceptCandidate: same URL is ignored") {
        check(!MovedServerRules.acceptCandidate(wantedId: id, savedUrl: old, foundId: id, foundUrl: old),
              "offered an unchanged address")
    }
    test("acceptCandidate: different id is ignored") {
        check(!MovedServerRules.acceptCandidate(wantedId: id, savedUrl: old, foundId: otherId, foundUrl: new),
              "offered another server")
    }
    test("acceptCandidate: non-private address is ignored") {
        check(!MovedServerRules.acceptCandidate(wantedId: id, savedUrl: old, foundId: id,
                                                foundUrl: "http://8.8.8.8:4000"), "offered a public address")
    }
    test("acceptCandidate: malformed address is ignored") {
        check(!MovedServerRules.acceptCandidate(wantedId: id, savedUrl: old, foundId: id,
                                                foundUrl: "http://192.168.1.51:4000/path"), "offered a path URL")
    }
    test("acceptCandidate: implausible id is ignored even when equal") {
        check(!MovedServerRules.acceptCandidate(wantedId: "invalid", savedUrl: old, foundId: "invalid",
                                                foundUrl: new), "offered an invalid id")
    }

    test("promptMessage: addresses, name, code and recognition line are plain text") {
        let code = DiscoveryRules.fingerprint(id)
        let message = MovedServerRules.promptMessage(oldUrl: old, newUrl: new, offeredName: "Garden",
                                                     storedName: "Garden", fingerprint: code)
        for part in [old, new, "Garden", code, "Only use it if you recognise this address (check your server or router)."] {
            check(message.contains(part), "missing " + part)
        }
        check(!message.contains("<") && !message.contains(">"), "added raw markup characters")
        check(!message.contains("The name is different"), "warned for an unchanged name")
        checkEqual(MovedServerRules.title, "Your server has a new address")
        checkEqual(MovedServerRules.notNowButton, "Not now")
        checkEqual(MovedServerRules.useNewAddressButton, "Use new address")
    }
    test("promptMessage: changed non-empty stored name is called out") {
        let message = MovedServerRules.promptMessage(oldUrl: old, newUrl: new, offeredName: "Garden",
                                                     storedName: "Kitchen", fingerprint: "4f1c2b9e")
        check(message.contains("The name is different from the one you saved (\"Kitchen\")."), "no rename warning")
    }
    test("promptMessage: absent offered name uses Nightlight") {
        let absent = MovedServerRules.promptMessage(oldUrl: old, newUrl: new, offeredName: nil,
                                                    storedName: nil, fingerprint: "4f1c2b9e")
        let empty = MovedServerRules.promptMessage(oldUrl: old, newUrl: new, offeredName: "",
                                                   storedName: "", fingerprint: "4f1c2b9e")
        check(absent.contains("Name: Nightlight"), "nil name did not fall back")
        check(empty.contains("Name: Nightlight"), "empty name did not fall back")
        check(!absent.contains("The name is different") && !empty.contains("The name is different"),
              "empty stored name caused a warning")
        let renamed = MovedServerRules.promptMessage(oldUrl: old, newUrl: new, offeredName: nil,
                                                     storedName: "Kitchen", fingerprint: "4f1c2b9e")
        check(renamed.contains("The name is different from the one you saved (\"Kitchen\")."),
              "missing rename warning when offered name is absent")
    }

    test("movePlan: all new data precedes saved URL, and old removal follows it") {
        let expected: [MovedServerRules.MoveStep] = [
            .addKnown(new), .setName(new, "Garden"), .setServerId(new, id), .carryPair(new, pair, id),
            .setSavedUrl(new), .removeOld(old)
        ]
        checkEqual(MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: "Garden",
                                             knownServers: [old], hasNewUrlLeftovers: false,
                                             pairUrl: pair, pairId: id), expected)
    }
    test("movePlan: CarryPair is absent without a pair URL") {
        let plan = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: nil,
                                             knownServers: [], hasNewUrlLeftovers: false, pairUrl: nil, pairId: id)
        check(!plan.contains(.carryPair(new, pair, id)), "carried a missing pair")
        checkEqual(plan, [.addKnown(new), .setServerId(new, id), .setSavedUrl(new), .removeOld(old)])
    }
    test("movePlan: CarryPair is absent when pair id differs") {
        let plan = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: nil,
                                             knownServers: [], hasNewUrlLeftovers: false, pairUrl: pair, pairId: otherId)
        checkEqual(plan, [.addKnown(new), .setServerId(new, id), .setSavedUrl(new), .removeOld(old)])
        let noId = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: nil,
                                              knownServers: [], hasNewUrlLeftovers: false, pairUrl: pair, pairId: nil)
        checkEqual(noId, plan)
    }
    test("movePlan: known destination is cleared then added again, with no empty name") {
        let plan = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: "",
                                             knownServers: [old, new], hasNewUrlLeftovers: false,
                                             pairUrl: nil, pairId: nil)
        checkEqual(plan, [.clearNew(new), .addKnown(new), .setServerId(new, id),
                          .setSavedUrl(new), .removeOld(old)])
    }
    test("movePlan: unlisted destination with leftovers is cleared before adding") {
        let plan = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: nil,
                                             knownServers: [old], hasNewUrlLeftovers: true,
                                             pairUrl: nil, pairId: nil)
        check(plan.first == MovedServerRules.MoveStep.clearNew(new), "did not clear the destination first")
        checkEqual(plan, [.clearNew(new), .addKnown(new), .setServerId(new, id),
                          .setSavedUrl(new), .removeOld(old)])
    }
    test("movePlan: fresh destination has no clear step") {
        let plan = MovedServerRules.movePlan(oldUrl: old, newUrl: new, id: id, name: nil,
                                             knownServers: [old], hasNewUrlLeftovers: false,
                                             pairUrl: nil, pairId: nil)
        check(!plan.contains(.clearNew(new)), "cleared an unused destination")
    }

    test("MoveGuard: matching old URL and id are still valid") {
        check(MovedServerRules.MoveGuard.stillValid(currentSavedUrl: old, currentStoredId: id,
                                                   expectedOldUrl: old, expectedId: id), "aborted an unchanged server")
    }
    test("MoveGuard: changed URL aborts even with matching id") {
        check(!MovedServerRules.MoveGuard.stillValid(currentSavedUrl: new, currentStoredId: id,
                                                    expectedOldUrl: old, expectedId: id), "missed a server switch")
    }
    test("MoveGuard: changed id aborts even with matching URL") {
        check(!MovedServerRules.MoveGuard.stillValid(currentSavedUrl: old, currentStoredId: otherId,
                                                    expectedOldUrl: old, expectedId: id), "missed an id change")
    }
    test("MoveGuard: changed URL and id abort") {
        check(!MovedServerRules.MoveGuard.stillValid(currentSavedUrl: new, currentStoredId: otherId,
                                                    expectedOldUrl: old, expectedId: id), "missed both changes")
        check(!MovedServerRules.MoveGuard.stillValid(currentSavedUrl: old, currentStoredId: nil,
                                                    expectedOldUrl: old, expectedId: id), "accepted an absent effective id")
        check(!MovedServerRules.MoveGuard.stillValid(currentSavedUrl: nil, currentStoredId: id,
                                                    expectedOldUrl: old, expectedId: id), "accepted an absent saved URL")
    }

    test("Throttle: no last run allows a check") {
        check(MovedServerRules.Throttle.mayRun(nowMs: 0, lastRunMs: nil), "blocked first check")
    }
    test("Throttle: matching refused and saved URLs skip once") {
        check(MovedServerRules.Throttle.skipOnceFor(refusedUrl: old, savedUrl: old),
              "matching refusal did not skip")
    }
    test("Throttle: a different saved URL cannot use another server's refusal") {
        check(!MovedServerRules.Throttle.skipOnceFor(refusedUrl: old, savedUrl: new),
              "different server inherited the bypass")
    }
    test("Throttle: no refused URL cannot skip") {
        check(!MovedServerRules.Throttle.skipOnceFor(refusedUrl: nil, savedUrl: old),
              "routine check bypassed the interval")
    }
    test("Throttle: no saved URL cannot skip") {
        check(!MovedServerRules.Throttle.skipOnceFor(refusedUrl: old, savedUrl: nil),
              "missing server inherited the bypass")
    }
    test("Throttle: just under five minutes blocks a check") {
        check(!MovedServerRules.Throttle.mayRun(nowMs: MovedServerRules.CHECK_INTERVAL_MS - 1,
                                                lastRunMs: 0), "ran early")
    }
    test("Throttle: a launch refusal skips the interval once") {
        check(MovedServerRules.Throttle.mayRun(nowMs: MovedServerRules.CHECK_INTERVAL_MS - 1,
                                               lastRunMs: 0, skipOnce: true), "refusal was throttled")
    }
    test("Throttle: no skip keeps the interval") {
        check(!MovedServerRules.Throttle.mayRun(nowMs: MovedServerRules.CHECK_INTERVAL_MS - 1,
                                                lastRunMs: 0, skipOnce: false), "routine check ran early")
    }
    test("Throttle: a launch refusal with no previous run is allowed") {
        check(MovedServerRules.Throttle.mayRun(nowMs: 0, lastRunMs: nil, skipOnce: true),
              "first refusal was blocked")
    }
    test("Throttle: exactly five minutes allows a check") {
        check(MovedServerRules.Throttle.mayRun(nowMs: MovedServerRules.CHECK_INTERVAL_MS,
                                               lastRunMs: 0), "blocked boundary")
    }
    test("limits: discovery and probe bounds and decline cap stay as specified") {
        checkEqual(MovedServerRules.SEARCH_BOUND_MS, 10_000)
        checkEqual(MovedServerRules.PROBE_TIMEOUT_MS, 2_000)
        checkEqual(MovedServerRules.MAX_DECLINES, 8)
        checkEqual(MovedServerRules.DECLINE_LIFETIME_MS, Int64(7 * 24 * 60 * 60_000))
    }

    test("Declines: a candidate is declined inside seven days and not at seven days") {
        let declined = MovedServerRules.Declines.record([:], candidateUrl: new, nowMs: 0)
        check(MovedServerRules.Declines.isDeclined(declined, candidateUrl: new,
                                                  nowMs: MovedServerRules.DECLINE_LIFETIME_MS - 1), "expired early")
        check(!MovedServerRules.Declines.isDeclined(declined, candidateUrl: new,
                                                   nowMs: MovedServerRules.DECLINE_LIFETIME_MS), "expired late")
        check(!MovedServerRules.Declines.isDeclined(declined, candidateUrl: old, nowMs: 1),
              "declined a different candidate")
    }
    test("Declines: recording nine keeps the eight newest") {
        var map: MovedServerRules.Declines.Map = [:]
        for index in 0..<9 {
            map = MovedServerRules.Declines.record(map, candidateUrl: "http://192.168.1.\(index + 50):4000",
                                                   nowMs: Int64(index))
        }
        checkEqual(map.count, 8)
        check(map["http://192.168.1.50:4000"] == nil, "kept the oldest")
        checkEqual(map["http://192.168.1.58:4000"], Int64(8))
    }
    test("Declines: recording prunes expired entries") {
        let map = MovedServerRules.Declines.record([old: 0], candidateUrl: new,
                                                    nowMs: MovedServerRules.DECLINE_LIFETIME_MS)
        checkEqual(map.count, 1)
        check(map[old] == nil, "kept an expired refusal")
        check(MovedServerRules.Declines.isDeclined(map, candidateUrl: new,
                                                  nowMs: MovedServerRules.DECLINE_LIFETIME_MS), "lost the new refusal")
    }
    test("Declines: future timestamps do not decline and a fresh record replaces an older one") {
        check(!MovedServerRules.Declines.isDeclined([new: 10], candidateUrl: new, nowMs: 9),
              "a future timestamp suppressed the candidate")
        let refreshed = MovedServerRules.Declines.record([new: 1], candidateUrl: new, nowMs: 100)
        checkEqual(refreshed[new], Int64(100))
    }
    test("Declines: garbage storage decodes to an empty map") {
        checkEqual(MovedServerRules.Declines.decode("not json"), [:])
        checkEqual(MovedServerRules.Declines.decode("{\"address\":\"wrong type\"}"), [:])
    }
    test("Declines: encode and decode round trip in a stable order") {
        let map: MovedServerRules.Declines.Map = [new: 200, old: 100]
        let reversed: MovedServerRules.Declines.Map = [old: 100, new: 200]
        let encoded = MovedServerRules.Declines.encode(map)
        checkEqual(MovedServerRules.Declines.decode(encoded), map)
        checkEqual(MovedServerRules.Declines.encode(reversed), encoded)
    }
}
