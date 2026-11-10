import Foundation

// EndpointChooserTests.swift: which address the app opens at launch, under Tailscale first (owner decision 2026-11-09): the
// Tailscale address when it answers, the saved address when it does not, with a short GRACE in which an answer from the saved
// address still waits for Tailscale. The iOS twin of android/app/src/test/java/com/sauso/nightlight/EndpointChooserTest.kt; the
// cases are the brief's table, the same on both platforms.
//
// Two kinds of test. The DECISION is a pure function (EndpointChooser.decide, inTime), so every row of the table, the exact
// boundaries included (an answer in exactly as the grace ends, at exactly the deadline), is driven with made-up times and no
// threads. The THREADED tests run the real chooser with fake probes that sleep, then answer; their margins are generous (a slow
// CI machine must not flake), but each bound is far from what the mutant it kills would take (named next to it).

enum ChooserTestError: Error {
    case unexpectedAddress
    case boom
}

/// Every address a probe was asked about, from the probe threads; locked.
final class SeenAddresses {
    private let lock = NSLock()
    private var urls: [String] = []

    func add(_ url: String) {
        lock.lock()
        urls.append(url)
        lock.unlock()
    }

    func all() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return urls
    }
}

/// A fake probe's answer: after `delayMs`, `ok`.
struct FakeAnswer {
    let delayMs: Int
    let ok: Bool
}

/// A probe that sleeps, then answers, per address. An address it was not given throws (Android's getValue throws too), which
/// the chooser counts as a failure.
func sleepingProbe(_ answers: [String: FakeAnswer]) -> (String) throws -> Bool {
    return { url in
        guard let answer = answers[url] else { throw ChooserTestError.unexpectedAddress }
        Thread.sleep(forTimeInterval: Double(answer.delayMs) / 1000.0)
        return answer.ok
    }
}

func runEndpointChooserTests() {
    let local = "http://192.168.1.50:4000"
    let tailscale = "https://nightlight.example.ts.net"

    func chooser(deadlineMs: Int = 2_000, graceMs: Int = 600, _ answers: [String: FakeAnswer]) -> EndpointChooser {
        return EndpointChooser(probe: sleepingProbe(answers), deadlineMs: deadlineMs, tailscaleGraceMs: graceMs)
    }

    // ---- The decision table, pure (grace ends at 1 000, deadline at 3 000, on a made-up clock) ----

    func decide(_ saved: EndpointChooser.State, _ ts: EndpointChooser.State, now: UInt64, graceEnd: UInt64 = 1_000, end: UInt64 = 3_000) -> EndpointChooser.Decision {
        return EndpointChooser.decide(saved: saved, tailscale: ts, nowNs: now, graceEndNs: graceEnd, endNs: end)
    }
    let pending = EndpointChooser.State.pending
    let ok = EndpointChooser.State.ok
    let failed = EndpointChooser.State.failed
    let noTailscale = EndpointChooser.State.none

    test("decide: a tailscale answer wins over every saved state, also exactly as the grace ends and at the deadline") {
        // Rule 1 comes first (Codex plan review of Tailscale first): a mutant that takes a saved answer first once the grace is
        // over returns .saved at 1 000 and after.
        for saved in [pending, ok, failed] {
            for now in [UInt64(0), 999, 1_000, 2_000, 3_000] {
                checkEqual(decide(saved, ok, now: now), EndpointChooser.Decision.tailscale, "saved \(saved), at \(now)")
            }
        }
    }

    test("decide: a saved answer waits for a running tailscale probe until the grace ends, and is taken AT its end") {
        checkEqual(decide(ok, pending, now: 0), EndpointChooser.Decision.wait(untilNs: 1_000), "waits for the grace's end")
        checkEqual(decide(ok, pending, now: 999), EndpointChooser.Decision.wait(untilNs: 1_000), "1 before the end")
        checkEqual(decide(ok, pending, now: 1_000), EndpointChooser.Decision.saved, "at exactly the end (kills > for >=)")
        checkEqual(decide(ok, pending, now: 1_500), EndpointChooser.Decision.saved, "after the end")
    }

    test("decide: a saved answer is taken at once when tailscale failed or none is saved") {
        checkEqual(decide(ok, failed, now: 0), EndpointChooser.Decision.saved, "a failed tailscale probe ends the grace")
        checkEqual(decide(ok, noTailscale, now: 0), EndpointChooser.Decision.saved, "no tailscale address: no grace")
    }

    test("decide: a grace longer than the deadline is cut to the deadline") {
        checkEqual(decide(ok, pending, now: 2_000, graceEnd: 5_000), EndpointChooser.Decision.wait(untilNs: 3_000), "waits for the deadline, not the grace")
        checkEqual(decide(ok, pending, now: 3_000, graceEnd: 5_000), EndpointChooser.Decision.saved, "the saved answer is taken at the deadline")
    }

    test("decide: a failed saved address waits for a running tailscale probe until the deadline, then gives nothing") {
        checkEqual(decide(failed, pending, now: 0), EndpointChooser.Decision.wait(untilNs: 3_000))
        checkEqual(decide(failed, pending, now: 2_999), EndpointChooser.Decision.wait(untilNs: 3_000))
        checkEqual(decide(failed, pending, now: 3_000), EndpointChooser.Decision.nothing)
    }

    test("decide: a failed tailscale probe waits for a saved address still running, until the deadline") {
        checkEqual(decide(pending, failed, now: 0), EndpointChooser.Decision.wait(untilNs: 3_000))
        checkEqual(decide(pending, failed, now: 3_000), EndpointChooser.Decision.nothing)
    }

    test("decide: nothing left that could answer gives nothing at once") {
        checkEqual(decide(failed, failed, now: 0), EndpointChooser.Decision.nothing, "both failed")
        checkEqual(decide(failed, noTailscale, now: 0), EndpointChooser.Decision.nothing, "the saved address failed, no tailscale address")
    }

    test("decide: nothing in yet waits for the deadline, with or without a tailscale address, then gives nothing") {
        checkEqual(decide(pending, pending, now: 0), EndpointChooser.Decision.wait(untilNs: 3_000))
        checkEqual(decide(pending, noTailscale, now: 0), EndpointChooser.Decision.wait(untilNs: 3_000))
        checkEqual(decide(pending, pending, now: 3_000), EndpointChooser.Decision.nothing)
        checkEqual(decide(pending, noTailscale, now: 3_000), EndpointChooser.Decision.nothing)
    }

    test("decide: it never asks to wait for a time that has already passed (the wait loop cannot spin)") {
        let states: [EndpointChooser.State] = [pending, ok, failed, noTailscale]
        let nows: [UInt64] = [0, 500, 999, 1_000, 1_001, 2_999, 3_000, 3_001, 9_000]
        let graceEnds: [UInt64] = [0, 1_000, 3_000, 5_000]
        for saved in states {
            for ts in states {
                for now in nows {
                    for graceEnd in graceEnds {
                        if case .wait(let untilNs) = decide(saved, ts, now: now, graceEnd: graceEnd) {
                            check(untilNs > now, "wait until \(untilNs) at \(now): saved \(saved), tailscale \(ts), grace end \(graceEnd)")
                        }
                    }
                }
            }
        }
    }

    test("inTime: an answer counts only if it came by the deadline; a later one reads as still pending") {
        checkEqual(EndpointChooser.inTime(ok, doneNs: 3_000, endNs: 3_000), ok, "at exactly the deadline it counts")
        checkEqual(EndpointChooser.inTime(ok, doneNs: 3_001, endNs: 3_000), pending, "a late answer")
        checkEqual(EndpointChooser.inTime(failed, doneNs: 3_000, endNs: 3_000), failed)
        checkEqual(EndpointChooser.inTime(failed, doneNs: 3_001, endNs: 3_000), pending, "a late failure")
        checkEqual(EndpointChooser.inTime(pending, doneNs: 9_999, endNs: 3_000), pending)
        checkEqual(EndpointChooser.inTime(noTailscale, doneNs: 9_999, endNs: 3_000), noTailscale, "no tailscale address stays none")
    }

    // ---- The real chooser, with fake probes ----
    //
    // How the time windows are set (Codex code review of PR #38: the first ones left a loaded CI runner a few hundred ms). A
    // sleep or a timed wait never ends EARLY, so the wrong chooser a window is there to catch can only decide at its fixture
    // time or later, never sooner. Each upper bound therefore sits a little under the EARLIEST time that wrong chooser could
    // decide (a grace's end, the deadline, a slow answer), and those times are set a second or more after the correct decision,
    // so the correct path has that much room on a slow runner. Lower bounds need no room: the chooser cannot decide before its
    // own grace or deadline, which start after the test's clock. Where an outcome itself depends on a race (an answer inside the
    // grace, say), the answer and the limit are far apart for the same reason.

    test("a tailscale answer that comes first wins at once, without waiting for the saved address") {
        // Correct: about 20 ms. A chooser that held Tailscale back until the grace's end (2 000) or the saved answer (2 500)
        // cannot decide before 2 000, so the top of the window (1 800) catches it and leaves the correct path 1.7 s.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 2_000, [local: FakeAnswer(delayMs: 2_500, ok: true), tailscale: FakeAnswer(delayMs: 20, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale, "the old saved-first rule opens the saved address here")
        check(ms < 1_800, "waited " + String(ms) + " ms for the saved address or the grace")
    }

    test("a saved answer that comes first still waits for a tailscale answer inside the grace, and tailscale wins") {
        // The tailscale answer (300) is 2.2 s inside the grace (2 500), so a slow runner cannot push it out of the grace.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 2_500, [local: FakeAnswer(delayMs: 20, ok: true), tailscale: FakeAnswer(delayMs: 300, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale, "a saved answer was taken without the grace")
        check(ms >= 250, "decided after " + String(ms) + " ms, before the tailscale answer")
    }

    test("a tailscale answer slower than the grace loses to the saved address, which is taken when the grace ends") {
        // Correct: at the grace's end (500). A chooser that ignored the grace's end decides at the tailscale answer (2 000, and
        // then picks Tailscale) or the deadline (4 000) at the earliest, so the top of the window (1 800) catches it and leaves
        // the correct path 1.3 s.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 500, [local: FakeAnswer(delayMs: 20, ok: true), tailscale: FakeAnswer(delayMs: 2_000, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local, "the grace did not end the wait")
        check(ms >= 450 && ms < 1_800, "decided after " + String(ms) + " ms, not at the end of the grace")
    }

    test("the grace is counted from the start of the choice, not from the saved answer") {
        // Saved at 1 500, grace 1 600: correct is about 1 600 ms. Counted from the saved answer the grace would end at 3 100 at
        // the earliest, so the top of the window (3 000) catches that and leaves the correct path 1.4 s. The deadline (5 000)
        // and the tailscale answer (6 000) come after both.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 5_000, graceMs: 1_600, [local: FakeAnswer(delayMs: 1_500, ok: true), tailscale: FakeAnswer(delayMs: 6_000, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local)
        check(ms >= 1_550 && ms < 3_000, "decided after " + String(ms) + " ms")
    }

    test("a saved answer with tailscale refused is taken at once: the failure ends the grace") {
        // Correct: about 150 ms. A chooser that ignored the failure waits for the grace (2 500).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 2_500, [local: FakeAnswer(delayMs: 20, ok: true), tailscale: FakeAnswer(delayMs: 150, ok: false)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local)
        check(ms < 2_000, "took " + String(ms) + " ms: the failed tailscale probe did not end the grace")
    }

    test("a saved answer with a tailscale probe that throws is taken at once: the failure ends the grace") {
        let throwing = EndpointChooser(probe: { url in
            if url == tailscale {
                Thread.sleep(forTimeInterval: 0.15)
                throw ChooserTestError.boom
            }
            Thread.sleep(forTimeInterval: 0.02)
            return true
        }, deadlineMs: 4_000, tailscaleGraceMs: 2_500)
        // Correct: about 150 ms. A chooser that ignored the thrown failure waits for the grace (2 500).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = throwing.choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local)
        check(ms < 2_000, "took " + String(ms) + " ms: the thrown probe did not end the grace")
    }

    test("tailscale failing first and the saved address answering later opens the saved address") {
        // Correct: at the saved answer, about 500 ms. A chooser that sat out the deadline first decides at 4 000 at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, [local: FakeAnswer(delayMs: 500, ok: true), tailscale: FakeAnswer(delayMs: 20, ok: false)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local, "gave up on the saved address when tailscale failed")
        check(ms >= 450 && ms < 3_500, "decided after " + String(ms) + " ms")
    }

    test("a failed saved address waits for a slow tailscale answer and takes it before the deadline") {
        // Correct: at the tailscale answer, about 700 ms, 3.3 s before the deadline (4 000), where a chooser that sat out the
        // deadline first would decide at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 300, [local: FakeAnswer(delayMs: 20, ok: false), tailscale: FakeAnswer(delayMs: 700, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale, "gave up when the saved address failed")
        check(ms >= 650 && ms < 3_500, "decided after " + String(ms) + " ms")
    }

    test("a silent saved address does not hold back a tailscale answer") {
        // Correct: about 200 ms. The old saved-first rule waited for the saved address until the deadline (4 000).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, [local: FakeAnswer(delayMs: 9_000, ok: true), tailscale: FakeAnswer(delayMs: 200, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale)
        check(ms < 3_000, "took " + String(ms) + " ms")
    }

    test("both failing gives nothing, at once") {
        // Correct: about 20 ms. A chooser that kept waiting with nothing left to answer decides at the deadline (4 000).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, [local: FakeAnswer(delayMs: 20, ok: false), tailscale: FakeAnswer(delayMs: 20, ok: false)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms < 3_000, "took " + String(ms) + " ms: waited for the deadline with nothing left to answer")
    }

    test("both silent gives nothing at the deadline, and never much later") {
        // Correct: at the deadline (600). A chooser that let the deadline pass waits for the answers (5 000) at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 600, [local: FakeAnswer(delayMs: 5_000, ok: true), tailscale: FakeAnswer(delayMs: 5_000, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms >= 550 && ms < 4_000, "decided after " + String(ms) + " ms, not at the deadline")
    }

    test("a tailscale answer after the deadline is not used") {
        // Correct: at the deadline (400), with nothing. A chooser that waited for the late answer decides at 3 000 at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let afterFailure = chooser(deadlineMs: 400, [local: FakeAnswer(delayMs: 20, ok: false), tailscale: FakeAnswer(delayMs: 3_000, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(afterFailure, nil, "after a failed saved address")
        check(ms < 2_500, "took " + String(ms) + " ms: waited for the late tailscale answer")
        let afterSilence = chooser(deadlineMs: 400, [local: FakeAnswer(delayMs: 5_000, ok: true), tailscale: FakeAnswer(delayMs: 3_000, ok: true)]).choose(local: local, tailscale: tailscale)
        checkEqual(afterSilence, nil, "after a silent saved address")
    }

    test("a saved answer is taken at the deadline even when the grace is longer than the deadline") {
        // Correct: the saved address at the deadline (1 500; the saved answer at 20 has 1.48 s to come in before it). A chooser
        // that waited for the grace (5 000) instead decides at the tailscale answer (3 000) at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 1_500, graceMs: 5_000, [local: FakeAnswer(delayMs: 20, ok: true), tailscale: FakeAnswer(delayMs: 3_000, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local, "a grace past the deadline lost the saved answer")
        check(ms >= 1_450 && ms < 2_800, "decided after " + String(ms) + " ms")
    }

    test("a probe that throws counts as failed: two throwing probes give nothing at once") {
        let throwing = EndpointChooser(probe: { _ in
            throw ChooserTestError.boom
        }, deadlineMs: 4_000, tailscaleGraceMs: 600)
        // Correct: at once. A chooser that took a throw for a probe still running decides at the deadline (4 000).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = throwing.choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil, "a thrown probe was taken as an answer")
        check(ms < 3_000, "took " + String(ms) + " ms: a thrown probe was treated as still running")
    }

    // ---- No tailscale address saved: exactly the chooser as it was with none ----

    test("with no tailscale address a saved answer is taken at once, with no grace") {
        // Correct: about 20 ms. A chooser that applied the grace with no tailscale address decides at 3 000 at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, graceMs: 3_000, [local: FakeAnswer(delayMs: 20, ok: true)]).choose(local: local, tailscale: nil)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local)
        check(ms < 2_500, "took " + String(ms) + " ms: waited for a grace with no tailscale address")
    }

    test("with no tailscale address a failed saved address gives nothing at once") {
        // Correct: about 20 ms. A chooser that kept waiting decides at the deadline (4 000).
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 4_000, [local: FakeAnswer(delayMs: 20, ok: false)]).choose(local: local, tailscale: nil)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms < 3_000, "took " + String(ms) + " ms")
    }

    test("with no tailscale address a silent saved address gives nothing at the deadline") {
        // Correct: at the deadline (600). A chooser that let the deadline pass waits for the answer (5 000) at the earliest.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 600, [local: FakeAnswer(delayMs: 5_000, ok: true)]).choose(local: local, tailscale: nil)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms >= 550 && ms < 4_000, "decided after " + String(ms) + " ms, not at the deadline")
    }

    test("tailscale is probed only when there is one, and each address once") {
        let seen = SeenAddresses()
        let counting = EndpointChooser(probe: { url in
            seen.add(url)
            return true
        }, deadlineMs: 1_000)
        checkEqual(counting.choose(local: local, tailscale: nil), local)
        Thread.sleep(forTimeInterval: 0.1)
        checkEqual(seen.all(), [local])
        let both = SeenAddresses()
        let countingBoth = EndpointChooser(probe: { url in
            both.add(url)
            return true
        }, deadlineMs: 1_000)
        checkEqual(countingBoth.choose(local: local, tailscale: tailscale), tailscale)
        Thread.sleep(forTimeInterval: 0.1)
        checkEqual(both.all().sorted(), [local, tailscale].sorted())
    }

    test("a late answer from an earlier choice changes nothing in the next one") {
        // The first choice ends at its deadline (300 ms) with nothing; its tailscale probe answers at 500 ms, about 200 ms into
        // the second choice on the same chooser. Each choice has its own state, so the second ends with nothing too.
        let reused = chooser(deadlineMs: 300, graceMs: 100, [local: FakeAnswer(delayMs: 10, ok: false), tailscale: FakeAnswer(delayMs: 500, ok: true)])
        let first = reused.choose(local: local, tailscale: tailscale)
        let second = reused.choose(local: local, tailscale: tailscale)
        checkEqual(first, nil)
        checkEqual(second, nil, "the first choice's late tailscale answer was taken by the second")
        // Let the abandoned workers finish before the next test (they write into state that nobody reads).
        Thread.sleep(forTimeInterval: 0.6)
    }

    test("the default grace is EndpointRules' 1 second: a tailscale answer at 0.35 s still beats a saved one at once") {
        // The tailscale answer (350) has 650 ms of room inside the default grace (1 000) on a slow runner, and comes after the
        // end of a 250 ms grace (the no-VPN value, the likeliest wrong default), where the saved address would already be taken.
        let byDefault = EndpointChooser(probe: sleepingProbe([local: FakeAnswer(delayMs: 10, ok: true), tailscale: FakeAnswer(delayMs: 350, ok: true)]))
        checkEqual(byDefault.choose(local: local, tailscale: tailscale), tailscale)
    }

    test("the default deadline is Android's 3 seconds") {
        checkEqual(EndpointChooser.defaultDeadlineMs, 3_000)
    }
}
