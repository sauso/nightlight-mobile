import Foundation

// EndpointChooser.swift: which of a server's addresses to open at launch: the saved one if it answers, else the Tailscale one
// (owner, 2026-11-06; Connect & Discovery design section 9.2).
//
// The iOS twin of android/app/src/main/java/com/sauso/nightlight/EndpointChooser.kt: the same decision, the same deadlines,
// the same state machine, with an NSCondition and two worker threads where Android has a ReentrantLock and two daemon threads.
// Foundation only, no Capacitor or UIKit import, so CI compiles it alone with swiftc and ios/Tests runs it with fake probes.
//
// Both addresses are probed AT ONCE, under one deadline for the whole decision, so being away from home costs the wait for
// the saved address to give up once, not once per address. The rule that matters: a quick Tailscale answer must NOT beat a
// saved address that is merely a little slower, because at home the saved path is the better one (no relay, no dependence on
// Tailscale). So while the saved address is still being probed and its own deadline has not passed, the chooser keeps waiting
// for it, whatever Tailscale has said. It stops waiting for it only when it has FAILED (a refused or erroring connection ends
// at once) or when its deadline arrives (a connect to an address that is not on this network usually just goes quiet).
//
// `localDeadlineMs` is how long a SILENT saved address is waited for. It is the whole `deadlineMs` unless the caller knows
// better: a phone that is not on Wi-Fi or Ethernet cannot reach a home-network-only address, so it passes a short value
// (EndpointRules.localWaitMs) and the Tailscale address is used after that. A saved address that answers after that point but
// before `deadlineMs` still wins if Tailscale has not already been chosen.
//
// The probe is injected: production passes EndpointProbe (MainViewController), the tests pass fakes with set delays. A probe
// that runs past the deadline is simply abandoned (its thread finishes on its own and its late answer changes nothing). A
// probe that throws counts as a failure.

final class EndpointChooser {

    // The same total Android's launch gate waits (MainActivity: join(3000), then EndpointChooser.DEADLINE_MS). The probes
    // themselves time out at 2.5 s (MainViewController), so this is the longest a launch waits for the server, once, whatever
    // the number of addresses. A judgement carried over from Android, not a measurement.
    static let defaultDeadlineMs: Int = 3_000

    // How long one wait on the condition may last before the clocks are read again. The deadlines are measured on the
    // monotonic clock, but NSCondition waits until a wall-clock Date, so a clock set back during a wait could stretch that one
    // wait. Waiting in slices bounds that to one slice. 100 ms is a judgement: a few dozen extra wake-ups in three seconds cost
    // nothing, and no decision here is finer than that.
    private static let maxSliceNs: UInt64 = 100_000_000

    private enum State {
        case pending
        case ok
        case failed
        case none
    }

    // The state both workers and the waiting caller share, guarded by its one NSCondition (every read and write holds it).
    private final class Shared {
        let condition = NSCondition()
        var local: State = .pending
        var tailscale: State = .none
    }

    private let probe: (String) throws -> Bool
    private let deadlineMs: Int
    private let localDeadlineMs: Int

    init(probe: @escaping (String) throws -> Bool, deadlineMs: Int = EndpointChooser.defaultDeadlineMs, localDeadlineMs: Int? = nil) {
        self.probe = probe
        self.deadlineMs = deadlineMs
        self.localDeadlineMs = localDeadlineMs ?? deadlineMs
    }

    /// The address to open, or nil when neither answered in time (the caller then opens the saved address as before).
    func choose(local: String, tailscale: String?) -> String? {
        let shared = Shared()
        shared.tailscale = (tailscale == nil) ? .none : .pending
        start(local, shared: shared, isLocal: true)
        if let other = tailscale {
            start(other, shared: shared, isLocal: false)
        }

        let startedNs: UInt64 = DispatchTime.now().uptimeNanoseconds
        let endNs: UInt64 = startedNs + EndpointChooser.nanoseconds(deadlineMs)
        // Never later than the total: a saved address cannot be waited for longer than the whole decision.
        let localEndNs: UInt64 = startedNs + EndpointChooser.nanoseconds(min(localDeadlineMs, deadlineMs))

        var answer: String? = nil
        var decided = false
        shared.condition.lock()
        while !decided {
            if shared.local == .ok {
                answer = local
                decided = true
                continue
            }
            let nowNs: UInt64 = DispatchTime.now().uptimeNanoseconds
            let timeUp = nowNs >= endNs
            let localGivenUp = shared.local == .failed || nowNs >= localEndNs
            if localGivenUp {
                if shared.tailscale == .ok {
                    answer = tailscale
                    decided = true
                    continue
                }
                if shared.tailscale != .pending || timeUp {
                    answer = nil
                    decided = true
                    continue
                }
                // Tailscale is still running and there is time left: wait for it (a saved answer can still arrive).
            }
            // Wake for whichever comes first: an answer, the saved address's own deadline, or the total deadline.
            let wakeNs: UInt64 = (shared.local == .pending && nowNs < localEndNs) ? localEndNs : endNs
            if wakeNs > nowNs {
                let sliceNs: UInt64 = min(wakeNs - nowNs, EndpointChooser.maxSliceNs)
                let until = Date(timeIntervalSinceNow: Double(sliceNs) / 1_000_000_000.0)
                _ = shared.condition.wait(until: until)
            }
        }
        shared.condition.unlock()
        return answer
    }

    // One worker per address. A Thread, not a pooled queue, so an abandoned probe holds no shared worker: it finishes on its
    // own (its request has its own timeout) and the state it then writes is read by nobody.
    private func start(_ url: String, shared: Shared, isLocal: Bool) {
        let probe = self.probe
        let worker = Thread {
            let ok: Bool = (try? probe(url)) ?? false
            shared.condition.lock()
            if isLocal {
                shared.local = ok ? .ok : .failed
            } else {
                shared.tailscale = ok ? .ok : .failed
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
