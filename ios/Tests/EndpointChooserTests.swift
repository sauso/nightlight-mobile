import Foundation

// EndpointChooserTests.swift: the iOS twin of android/app/src/test/java/com/sauso/nightlight/EndpointChooserTest.kt, case for
// case and with the same delays and margins: which address the app opens at launch. The probes are fakes with set delays and
// no network; the margins are generous (a slow CI machine must not flake), but each assertion is far from what the mutant it
// kills would take.
//
// The one rule worth a test of its own: a FAST Tailscale answer must not beat a SLOW local one.

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

    func chooser(deadlineMs: Int = 1_500, _ answers: [String: FakeAnswer]) -> EndpointChooser {
        return EndpointChooser(probe: sleepingProbe(answers), deadlineMs: deadlineMs)
    }

    // The saved address's OWN shorter wait (off Wi-Fi, a home-network-only address): see EndpointRules.localWaitMs.
    func withLocalWait(_ localWaitMs: Int, _ deadlineMs: Int, _ answers: [String: FakeAnswer]) -> EndpointChooser {
        return EndpointChooser(probe: sleepingProbe(answers), deadlineMs: deadlineMs, localDeadlineMs: localWaitMs)
    }

    test("local answering wins, and does not wait for tailscale") {
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser([local: FakeAnswer(delayMs: 20, ok: true), tailscale: FakeAnswer(delayMs: 1_200, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, local)
        check(ms < 800, "waited " + String(ms) + " ms for the Tailscale probe")
    }

    test("a FAST tailscale answer does not beat a slower local one") {
        // Tailscale says yes at once; local says yes after 400 ms. At home the local path is the right one.
        let chosen = chooser([local: FakeAnswer(delayMs: 400, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, local)
    }

    test("local failing fast falls to tailscale as soon as tailscale answers") {
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser([local: FakeAnswer(delayMs: 10, ok: false), tailscale: FakeAnswer(delayMs: 200, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale)
        check(ms < 1_000, "took " + String(ms) + " ms")
    }

    test("local that goes quiet is given up at the deadline and tailscale is used") {
        // Local never answers within the deadline (an address that is not on this network); tailscale answered long ago.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser(deadlineMs: 600, [local: FakeAnswer(delayMs: 5_000, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale)
        check(ms >= 500 && ms <= 1_800, "decided after " + String(ms) + " ms, not at the deadline")
    }

    test("a local answer that arrives after the deadline is ignored") {
        let chosen = chooser(deadlineMs: 300, [local: FakeAnswer(delayMs: 700, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: false)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, nil)
    }

    test("neither answering gives nothing") {
        let chosen = chooser([local: FakeAnswer(delayMs: 10, ok: false), tailscale: FakeAnswer(delayMs: 10, ok: false)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, nil)
    }

    test("with no tailscale address only local counts, and its failure ends the wait at once") {
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = chooser([local: FakeAnswer(delayMs: 10, ok: false)]).choose(local: local, tailscale: nil)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms < 800, "took " + String(ms) + " ms")
        checkEqual(chooser([local: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: nil), local)
    }

    test("a probe that throws counts as a failure") {
        let throwing = EndpointChooser(probe: { url in
            if url == local { throw ChooserTestError.boom }
            return true
        }, deadlineMs: 1_000)
        checkEqual(throwing.choose(local: local, tailscale: tailscale), tailscale)
    }

    test("local failing and tailscale still running waits for tailscale within the deadline") {
        let chosen = chooser(deadlineMs: 1_500, [local: FakeAnswer(delayMs: 10, ok: false), tailscale: FakeAnswer(delayMs: 500, ok: true)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, tailscale)
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
    }

    test("a silent saved address is given up after its own short wait, not the whole deadline") {
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = withLocalWait(300, 2_500, [local: FakeAnswer(delayMs: 5_000, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale)
        check(ms >= 250 && ms <= 2_000, "took " + String(ms) + " ms: it waited for the whole deadline")
    }

    test("a saved address that answers inside its short wait still wins over a quicker tailscale") {
        let chosen = withLocalWait(800, 2_500, [local: FakeAnswer(delayMs: 300, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, local)
    }

    test("after its short wait a saved address that answers before the deadline still beats a slow tailscale") {
        // Local gives up at 200 ms, tailscale answers at 900 ms, local answers at 500 ms: nothing has been chosen yet, so local wins.
        let chosen = withLocalWait(200, 2_500, [local: FakeAnswer(delayMs: 500, ok: true), tailscale: FakeAnswer(delayMs: 900, ok: true)]).choose(local: local, tailscale: tailscale)
        checkEqual(chosen, local)
    }

    test("the short wait cannot make the decision later than the whole deadline") {
        // A short wait longer than the deadline is clamped to it: the whole decision still ends at the deadline.
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = withLocalWait(10_000, 400, [local: FakeAnswer(delayMs: 5_000, ok: true), tailscale: FakeAnswer(delayMs: 10, ok: true)]).choose(local: local, tailscale: tailscale)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, tailscale)
        check(ms >= 300 && ms <= 2_500, "took " + String(ms) + " ms")
    }

    test("with no tailscale address a short wait still gives up on the saved address when it expires") {
        // (MainViewController never passes a short wait without a tailscale address; the chooser itself still ends there.)
        let start = DispatchTime.now().uptimeNanoseconds
        let chosen = withLocalWait(300, 5_000, [local: FakeAnswer(delayMs: 5_000, ok: true)]).choose(local: local, tailscale: nil)
        let ms = elapsedMs(since: start)
        checkEqual(chosen, nil)
        check(ms < 3_000, "took " + String(ms) + " ms")
    }

    test("the default deadline is Android's 3 seconds") {
        checkEqual(EndpointChooser.defaultDeadlineMs, 3_000)
    }
}
