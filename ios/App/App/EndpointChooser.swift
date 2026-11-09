import Foundation

// EndpointChooser.swift: which of a server's addresses to open at launch: the server's Tailscale address when it answers (with
// the id it was saved for), else the saved address ("Tailscale first", owner decision 2026-11-09; it replaced the saved-first
// rule of 2026-11-06, Connect & Discovery design section 9.2).
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/EndpointChooser.kt: the same decision, the same deadline and
// grace, with an NSCondition and two worker threads where Android has a ReentrantLock and two daemon threads.
// Foundation only, no Capacitor or UIKit import, so CI compiles it alone with swiftc and ios/Tests runs it with fake probes.
//
// WHY Tailscale first (owner decision 2026-11-09, after an iPad test that day): on a phone hotspot, with Tailscale routing the
// home network, the saved `http` address still answered (through the tailnet), so the old saved-first rule kept the app on plain
// http, About said "Using local address", and two-way talk (which needs HTTPS) could not work, although the secure address was
// right there. So the secure address is now preferred whenever it answers, and the saved address is the fallback.
//
// Both addresses are probed AT ONCE, under one deadline for the whole decision (3 s), so an address that cannot answer costs the
// wait once, not once per address. The decision (`decide`, a pure function the tests drive with no threads), in this order:
//  1. Tailscale answered -> Tailscale, whatever the saved address has said.
//  2. The saved address answered, and Tailscale is not saved, or has failed, or the GRACE is over -> the saved address.
//  3. The deadline has passed, or the saved address failed and Tailscale cannot answer any more -> nothing.
//  4. Otherwise wait: for the end of the grace while the saved address has answered and Tailscale is still running, else for
//     the deadline (an answer wakes the wait early either way).
// The GRACE (`tailscaleGraceMs`, counted from the start of the decision) is how long an answer from the saved address waits for a
// Tailscale one. Without it the saved address, which can answer first at home, would win those launches and "Tailscale first"
// would mean little; with no end to it, a phone with Tailscale off would wait the whole deadline for an address that cannot
// answer. The caller picks it (EndpointRules.tailscaleGraceMs: 1 s, or 0.25 s when the phone positively has no VPN).
// FAILED is any completed probe that did not succeed: refused, DNS, TLS, an error status, a thrown error. A failed Tailscale probe
// ends the grace at once (Codex plan review of Tailscale first, 2026-11-09).
//
// The timing, specified in that review because this race is what it worried about: the clock starts BEFORE either worker; only
// answers that completed by the deadline count (each worker stamps the time its probe returned, see `inTime`); the rules are
// applied again on every answer and at every time limit, in the order above, so a Tailscale answer that is in when the grace
// ends wins over a saved answer that is also in. A worker that finishes after the decision writes into state that nobody reads
// any more (each choice has its own), so it changes nothing.
//
// The probe is injected: production passes EndpointProbe.addressAnswers (with the id check for the Tailscale address), the
// tests pass fakes with set delays. A probe that runs past the deadline is simply abandoned (its thread finishes on its own and
// its late answer changes nothing). A probe that throws counts as a failure.

final class EndpointChooser {

    // The same total Android's launch gate waits (MainActivity: join(3000), then EndpointChooser.DEADLINE_MS). The probes
    // themselves time out at 2.5 s (MainViewController), so this is the longest a launch waits for the server, once, whatever
    // the number of addresses. A judgement carried over from Android, not a measurement.
    static let defaultDeadlineMs: Int = 3_000

    // How long one wait on the condition may last before the clock is read again. The limits are measured on the monotonic
    // clock, but NSCondition waits until a wall-clock Date, so a clock set back during a wait could stretch that one wait.
    // Waiting in slices bounds that to one slice. 100 ms is a judgement: a few dozen extra wake-ups in three seconds cost
    // nothing, and no decision here is finer than that.
    private static let maxSliceNs: UInt64 = 100_000_000

    /// Where one address's probe stands, as the decision sees it. Internal, not private, so ios/Tests can drive `decide`.
    ///  - pending: still running, or it finished only after the deadline (see `inTime`);
    ///  - ok: it answered (for the Tailscale address: with the id it was saved for, checked by the probe);
    ///  - failed: it completed without success (refused, DNS, TLS, an error status, a thrown error);
    ///  - none: no Tailscale address is saved, so there is nothing to probe.
    enum State {
        case pending
        case ok
        case failed
        case none
    }

    /// What `decide` says to do next: open one of the two, open neither, or wait until `untilNs` (or an answer, sooner).
    enum Decision: Equatable {
        case saved
        case tailscale
        case nothing
        case wait(untilNs: UInt64)
    }

    // The state both workers and the waiting caller share, guarded by its one NSCondition (every read and write holds it). One
    // per choice, so a late worker from an earlier choice can never write into a later one.
    private final class Shared {
        let condition = NSCondition()
        var local: State = .pending
        var localDoneNs: UInt64 = 0
        var tailscale: State = .none
        var tailscaleDoneNs: UInt64 = 0
    }

    private let probe: (String) throws -> Bool
    private let deadlineMs: Int
    private let tailscaleGraceMs: Int

    init(probe: @escaping (String) throws -> Bool, deadlineMs: Int = EndpointChooser.defaultDeadlineMs, tailscaleGraceMs: Int = EndpointRules.defaultTailscaleGraceMs) {
        self.probe = probe
        self.deadlineMs = deadlineMs
        self.tailscaleGraceMs = tailscaleGraceMs
    }

    /// The address to open, or nil when nothing answered in time (the caller then opens the saved address, as before).
    func choose(local: String, tailscale: String?) -> String? {
        let shared = Shared()
        shared.tailscale = (tailscale == nil) ? State.none : State.pending
        // The clock starts BEFORE either worker (Codex plan review of Tailscale first, 2026-11-09), so a worker that is slow to
        // start cannot stretch the grace or the deadline.
        let startedNs: UInt64 = DispatchTime.now().uptimeNanoseconds
        let endNs: UInt64 = startedNs + EndpointChooser.nanoseconds(deadlineMs)
        let graceEndNs: UInt64 = startedNs + EndpointChooser.nanoseconds(tailscaleGraceMs)
        start(local, shared: shared, isLocal: true)
        if let other = tailscale {
            start(other, shared: shared, isLocal: false)
        }

        var answer: String? = nil
        var decided = false
        shared.condition.lock()
        while !decided {
            let nowNs: UInt64 = DispatchTime.now().uptimeNanoseconds
            let savedState: State = EndpointChooser.inTime(shared.local, doneNs: shared.localDoneNs, endNs: endNs)
            let tailscaleState: State = EndpointChooser.inTime(shared.tailscale, doneNs: shared.tailscaleDoneNs, endNs: endNs)
            let decision: Decision = EndpointChooser.decide(saved: savedState, tailscale: tailscaleState, nowNs: nowNs, graceEndNs: graceEndNs, endNs: endNs)
            switch decision {
            case .tailscale:
                answer = tailscale
                decided = true
            case .saved:
                answer = local
                decided = true
            case .nothing:
                answer = nil
                decided = true
            case .wait(let untilNs):
                // decide never returns a wait that has already ended; the check is only so a mistake there cannot underflow.
                if untilNs > nowNs {
                    let sliceNs: UInt64 = min(untilNs - nowNs, EndpointChooser.maxSliceNs)
                    let until = Date(timeIntervalSinceNow: Double(sliceNs) / 1_000_000_000.0)
                    _ = shared.condition.wait(until: until)
                }
            }
        }
        shared.condition.unlock()
        return answer
    }

    /// The decision table (see the header), pure so ios/Tests can drive every row with no threads and no real clock. `saved` and
    /// `tailscale` are each address's state as it COUNTS (an answer that came after the deadline already reads as `.pending`,
    /// see `inTime`); `nowNs`, `graceEndNs` and `endNs` are on one clock. It never returns a wait that has already ended, so the
    /// loop in `choose` cannot spin.
    static func decide(saved: State, tailscale: State, nowNs: UInt64, graceEndNs: UInt64, endNs: UInt64) -> Decision {
        // 1. A Tailscale answer wins, whatever the saved address said, and also when it is in exactly as the grace ends (the
        //    owner decision of 2026-11-09 this chooser exists for; the order of these rules is the Codex plan review's).
        if tailscale == .ok { return .tailscale }
        let timeUp: Bool = nowNs >= endNs
        // The grace is over AT its end, and never later than the deadline (a grace longer than the deadline is cut to it).
        let graceOver: Bool = timeUp || nowNs >= graceEndNs
        // 2. The saved address answered: take it once nothing better can come (no Tailscale address saved, or its probe failed,
        //    which ends the grace at once), or once the grace is over.
        // (`State.none` written out, so it can never be read as Optional's `.none`.)
        if saved == .ok && (tailscale == State.none || tailscale == .failed || graceOver) { return .saved }
        // 3. Out of time, or nothing left that could still answer.
        if timeUp { return .nothing }
        if saved == .failed && tailscale != .pending { return .nothing }
        // 4. Wait for the next answer or the next time limit. If the saved address has answered, rule 2 did not take it, so
        //    Tailscale is still running inside the grace: the end of the grace is the next limit. Otherwise the deadline: a
        //    failed saved address waits for a Tailscale probe still running, and a failed Tailscale probe waits for a saved
        //    address still running (it can still answer before the deadline, and then rule 2 takes it).
        if saved == .ok { return .wait(untilNs: min(graceEndNs, endNs)) }
        return .wait(untilNs: endNs)
    }

    /// An answer counts only if its probe returned by the deadline (Codex plan review of Tailscale first, 2026-11-09): a later one
    /// reads as still pending. Without this, a wait that woke a little after the deadline could still take an answer that came
    /// in after it.
    static func inTime(_ state: State, doneNs: UInt64, endNs: UInt64) -> State {
        if (state == .ok || state == .failed) && doneNs > endNs { return .pending }
        return state
    }

    // One worker per address. A Thread, not a pooled queue, so an abandoned probe holds no shared worker: it finishes on its
    // own (its request has its own timeout) and the state it then writes is read by nobody.
    private func start(_ url: String, shared: Shared, isLocal: Bool) {
        let probe = self.probe
        let worker = Thread {
            let ok: Bool = (try? probe(url)) ?? false
            // Stamped when the probe returned, before the lock is taken, so waiting for the lock cannot make an answer late.
            let doneNs: UInt64 = DispatchTime.now().uptimeNanoseconds
            shared.condition.lock()
            if isLocal {
                shared.local = ok ? .ok : .failed
                shared.localDoneNs = doneNs
            } else {
                shared.tailscale = ok ? .ok : .failed
                shared.tailscaleDoneNs = doneNs
            }
            shared.condition.broadcast()
            shared.condition.unlock()
        }
        worker.qualityOfService = .userInitiated
        worker.start()
    }

    private static func nanoseconds(_ ms: Int) -> UInt64 {
        return UInt64(max(0, ms)) * 1_000_000
    }
}
