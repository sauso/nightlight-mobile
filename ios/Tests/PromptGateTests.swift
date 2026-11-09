import Foundation

// PromptGateTests.swift: the "ask once" rules (addendum of 2026-11-09, the owner's choice after both Codex code reviews of PR #38
// rated a planted secure address High): when addEndpoint must ask (EndpointRules.needsConfirmation), and the gate in front of the
// prompt (PromptGate: one at a time, a 7-day memory of an explicit decline, at most one prompt per 10 minutes, what each outcome
// stores, and which refusal each refusal path gives). Every row is a table entry with made-up times: no clock, no UIKit.
//
// Not reachable here (glue, iPhone checks only): the alert itself, its 120 s timer, the cancel from a rebuild, the main-thread
// read of the shown page, the last check before the write and the stores (AddressPrompt and addEndpoint, ServerConfigPlugin.swift).

// The made-up "now" of the gate tests: a wall clock in 2027 and an uptime of about 14 hours. File-level, so the helper's default
// arguments can name them (a local function's defaults are kept to constants that need no capture).
private let promptTestWallNow: Int64 = 1_800_000_000_000
private let promptTestMonoNow: Int64 = 50_000_000

func runPromptGateTests() {
    let home = "http://192.168.1.50:4000"
    let httpsHome = "https://nightlight.example.com"
    let saved = "https://nightlight.example.ts.net"
    let changed = "https://other.example.ts.net"

    // ---- needsConfirmation: new / changed / same address x the shown page (http, https, unreadable, the Tailscale page) ----

    test("prompt: a NEW address offered from a plain-http page is asked about") {
        check(EndpointRules.needsConfirmation(shownPageUrl: home + "/#/live", savedTailscale: nil, candidate: saved), "a first learn on http")
    }

    test("prompt: a CHANGED address offered from a plain-http page is asked about") {
        check(EndpointRules.needsConfirmation(shownPageUrl: home + "/", savedTailscale: saved, candidate: changed), "a change on http")
    }

    test("prompt: the SAME address is never asked about, whatever the page") {
        // The web app offers the saved address again at every start: asking each time would be a nag.
        let pages: [String?] = [home + "/", httpsHome + "/", saved + "/#/about", nil, "about:blank"]
        for page in pages {
            check(!EndpointRules.needsConfirmation(shownPageUrl: page, savedTailscale: saved, candidate: saved), "asked again on: " + (page ?? "<nil>"))
        }
    }

    test("prompt: an https page is not asked, for a new or a changed address") {
        // A network cannot put a page on https (addendum): only a plain-http caller is asked.
        check(!EndpointRules.needsConfirmation(shownPageUrl: httpsHome + "/#/live", savedTailscale: nil, candidate: saved), "new, on https")
        check(!EndpointRules.needsConfirmation(shownPageUrl: httpsHome + "/", savedTailscale: saved, candidate: changed), "changed, on https")
        check(!EndpointRules.needsConfirmation(shownPageUrl: "HTTPS://Nightlight.Example.com/", savedTailscale: nil, candidate: saved), "https in capitals")
    }

    test("prompt: the page on the saved tailscale address itself is not asked, even about a change") {
        check(!EndpointRules.needsConfirmation(shownPageUrl: saved + "/#/about", savedTailscale: saved, candidate: changed), "a change offered from the secure page")
    }

    test("prompt: an unreadable or unparseable shown page counts as plain http, so it asks (fail closed)") {
        let unreadable: [String?] = [nil, "", "about:blank", "capacitor://localhost/", "not a url"]
        for page in unreadable {
            check(EndpointRules.needsConfirmation(shownPageUrl: page, savedTailscale: nil, candidate: saved), "new, not asked on: " + (page ?? "<nil>"))
            check(EndpointRules.needsConfirmation(shownPageUrl: page, savedTailscale: saved, candidate: changed), "changed, not asked on: " + (page ?? "<nil>"))
        }
    }

    // ---- PromptGate.decide (wall clock for the decline, monotonic for the cooldown; both made up here) ----

    let day: Int64 = 24 * 60 * 60 * 1_000
    let wallNow: Int64 = promptTestWallNow
    let monoNow: Int64 = promptTestMonoNow

    func decide(_ candidate: String, decline: PromptGate.DeclineRecord? = nil, wall: Int64 = promptTestWallNow, last: Int64? = nil, mono: Int64 = promptTestMonoNow, showing: Bool = false) -> PromptGate.Decision {
        return PromptGate.decide(candidate: candidate, decline: decline, nowWallMs: wall, lastPromptAtMs: last, nowMs: mono, showing: showing)
    }

    test("prompt gate: nothing in the way asks") {
        checkEqual(decide(saved), PromptGate.Decision.ask)
    }

    test("prompt gate: a prompt showing makes every other offer busy, declined or not, cooled down or not") {
        let fresh = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - 1_000)
        checkEqual(decide(saved, showing: true), PromptGate.Decision.busy, "nothing else in the way")
        checkEqual(decide(saved, decline: fresh, showing: true), PromptGate.Decision.busy, "with a decline in force: showing comes first")
        checkEqual(decide(saved, last: monoNow - 1_000, showing: true), PromptGate.Decision.busy, "inside the cooldown")
    }

    test("prompt gate: an address declined less than 7 days ago is refused quietly, from the moment of the decline") {
        checkEqual(decide(saved, decline: PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow)), PromptGate.Decision.rejectQuietly, "declined just now")
        checkEqual(decide(saved, decline: PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - 3 * day)), PromptGate.Decision.rejectQuietly, "3 days ago")
        let edge = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - PromptGate.declineLifetimeMs + 1)
        checkEqual(decide(saved, decline: edge), PromptGate.Decision.rejectQuietly, "1 ms before the 7 days are up")
    }

    test("prompt gate: a decline expires at exactly 7 days, and the person is asked again") {
        let atEdge = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - PromptGate.declineLifetimeMs)
        checkEqual(decide(saved, decline: atEdge), PromptGate.Decision.ask, "at exactly 7 days (kills < for <=)")
        let older = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - 30 * day)
        checkEqual(decide(saved, decline: older), PromptGate.Decision.ask, "30 days ago")
    }

    test("prompt gate: a decline of ANOTHER address does not silence this one") {
        let otherDeclined = PromptGate.DeclineRecord(candidate: changed, atWallMs: wallNow - 1_000)
        checkEqual(decide(saved, decline: otherDeclined), PromptGate.Decision.ask)
    }

    test("prompt gate: a decline dated in the future (the clock was set back) is not in force, so it cannot last past a week") {
        let future = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow + day)
        checkEqual(decide(saved, decline: future), PromptGate.Decision.ask)
    }

    test("prompt gate: at most one prompt per 10 minutes, counted from the last one shown") {
        checkEqual(decide(saved, last: monoNow - 1_000), PromptGate.Decision.busy, "1 s after a prompt")
        checkEqual(decide(saved, last: monoNow - PromptGate.cooldownMs + 1), PromptGate.Decision.busy, "1 ms before the 10 minutes are up")
        checkEqual(decide(saved, last: monoNow - PromptGate.cooldownMs), PromptGate.Decision.ask, "at exactly 10 minutes (kills < for <=)")
        checkEqual(decide(saved, last: nil), PromptGate.Decision.ask, "no prompt yet in this run")
    }

    test("prompt gate: an expired decline inside the cooldown is busy, not a quiet refusal") {
        let expired = PromptGate.DeclineRecord(candidate: saved, atWallMs: wallNow - PromptGate.declineLifetimeMs)
        checkEqual(decide(saved, decline: expired, last: monoNow - 1_000), PromptGate.Decision.busy)
    }

    test("prompt gate: the judgements are 7 days, 10 minutes and 120 seconds") {
        checkEqual(PromptGate.declineLifetimeMs, 7 * day)
        checkEqual(PromptGate.cooldownMs, Int64(10 * 60 * 1_000))
        checkEqual(PromptGate.answerTimeoutMs, 120_000)
    }

    // ---- What each outcome stores ----

    test("prompt outcome: only an explicit decline stores a decline, with the address and the time") {
        checkEqual(PromptGate.declineChange(after: .declined, candidate: changed, nowWallMs: wallNow),
                   PromptGate.DeclineChange.store(PromptGate.DeclineRecord(candidate: changed, atWallMs: wallNow)))
    }

    test("prompt outcome: a prompt not answered (failed, timed out, cancelled) stores nothing and keeps any old decline") {
        checkEqual(PromptGate.declineChange(after: .notAnswered, candidate: changed, nowWallMs: wallNow), PromptGate.DeclineChange.keep)
    }

    test("prompt outcome: an accept clears the decline") {
        checkEqual(PromptGate.declineChange(after: .accepted, candidate: changed, nowWallMs: wallNow), PromptGate.DeclineChange.clear)
    }

    // ---- Which refusal each path gives, and that the web app files both under "refused" ----

    test("prompt refusal: the gate's and the outcomes' refusals") {
        check(PromptGate.refusal(for: .ask) == nil, "the prompt is shown, not refused")
        checkEqual(PromptGate.refusal(for: .rejectQuietly), EndpointRules.AddCheck.notAccepted, "declined within 7 days")
        checkEqual(PromptGate.refusal(for: .busy), EndpointRules.AddCheck.notConfirmed, "busy")
        check(PromptGate.refusal(after: .accepted) == nil, "an accept writes, it is not refused")
        checkEqual(PromptGate.refusal(after: .declined), EndpointRules.AddCheck.notAccepted, "an explicit decline")
        checkEqual(PromptGate.refusal(after: .notAnswered), EndpointRules.AddCheck.notConfirmed, "no answer")
    }

    test("prompt refusal: both sentences fall to the web app's generic 'refused' (neither 'couldn't reach' nor 'different server')") {
        // nativeBridge.js classifyAddRefusal: /couldn'?t reach/i -> unreachable, /different server/i -> different, else refused.
        for check1 in [EndpointRules.AddCheck.notAccepted, EndpointRules.AddCheck.notConfirmed] {
            let sentence = EndpointRules.refusal(check1).lowercased()
            check(!sentence.isEmpty, "an empty refusal")
            check(!sentence.contains("couldn't reach") && !sentence.contains("couldnt reach"), "would read as unreachable: " + sentence)
            check(!sentence.contains("different server"), "would read as a different server: " + sentence)
        }
        checkEqual(EndpointRules.refusal(.notAccepted), "The secure address was not accepted on this phone")
        checkEqual(EndpointRules.refusal(.notConfirmed), "The secure address was not confirmed on this phone")
    }

    test("prompt store keys: the decline has its own keys per saved server, apart from the tailscale pair") {
        let keys: [String] = [EndpointRules.key(home), EndpointRules.idKey(home), EndpointRules.declinedKey(home), EndpointRules.declinedAtKey(home)]
        checkEqual(Set(keys).count, 4, "two of the four keys are the same")
        check(EndpointRules.declinedKey(home) != EndpointRules.declinedKey(httpsHome), "two servers share a decline key")
    }
}
