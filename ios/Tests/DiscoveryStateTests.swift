import Foundation

private let discoveryRealId = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"

private func discoveryFakeId(_ n: Int) -> String {
    let digits = String(n)
    return "00000000-0000-4000-8000-" + String(repeating: "0", count: max(0, 12 - digits.count)) + digits
}

private func discoveryHealth(_ id: String, name: String? = "Sample") -> ServerHealth {
    return ServerHealth(id: id, name: name)
}

/// Found -> resolved with hosts, exactly as the Kotlin test helper drives the state.
@discardableResult private func discoveryAnnounce(_ state: DiscoveryState<String>, _ gen: Int, _ name: String,
                               _ txtId: String?, hosts: [String] = ["192.168.1.50"],
                               port: Int = 4000, now: Int64 = 0) -> [DiscoveryState<String>.ProbeTask] {
    check(state.onFound(gen, name, name), "found " + name)
    guard let job = state.nextResolve(1) else {
        check(false, "resolve job for " + name)
        return []
    }
    return state.onResolved(job, txtId, hosts, port, now)
}

/// Run every queued probe; the closure chooses which address answers with the announced id.
private func discoveryDrain(_ state: DiscoveryState<String>,
                            _ verifies: (DiscoveryState<String>.ProbeTask) -> Bool) -> [DiscoveryState<String>.Found] {
    var found: [DiscoveryState<String>.Found] = []
    while let task = state.nextProbe() {
        let result = state.onProbeDone(task, verifies(task) ? discoveryHealth(task.txtId) : nil)
        if let result = result { found.append(result) }
    }
    return found
}

func runDiscoveryStateTests() {
    test("a verified server is listed with its name, url and fingerprint") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        let tasks = discoveryAnnounce(s, gen, "Nightlight-4f1c2b9e", discoveryRealId)
        checkEqual(tasks.count, 1)
        let found = discoveryDrain(s) { _ in true }
        checkEqual(found, [DiscoveryState<String>.Found(id: discoveryRealId, name: "Sample",
                                                        url: "http://192.168.1.50:4000", fingerprint: "4f1c2b9e")])
        check(s.isListed(discoveryRealId), "listed")
    }

    test("a server with no name is listed as Nightlight") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        let task = s.nextProbe()!
        checkEqual(s.onProbeDone(task, discoveryHealth(discoveryRealId, name: nil))?.name, "Nightlight")
    }

    test("a failed probe lists nothing") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        check(discoveryDrain(s) { _ in false }.isEmpty, "failed probe listed")
        checkEqual(s.listedCount(), 0)
    }

    test("a reply whose id differs from the announcement lists nothing") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        check(s.onProbeDone(s.nextProbe()!, discoveryHealth(discoveryFakeId(1))) == nil, "mismatched id listed")
    }

    test("only verified servers count toward the cap of 16") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        // Failed probes take no card slot, even when twenty false announcements arrive first.
        for i in 1...20 { discoveryAnnounce(s, gen, "fake-\(i)", discoveryFakeId(i), hosts: ["10.0.0.\(i)"]) }
        check(discoveryDrain(s) { _ in false }.isEmpty, "false cards")
        for i in 1...17 { discoveryAnnounce(s, gen, "real-\(i)", discoveryFakeId(100 + i), hosts: ["10.0.1.\(i)"]) }
        checkEqual(discoveryDrain(s) { _ in true }.count, 16)
        checkEqual(s.listedCount(), 16)
        checkEqual(DiscoveryState<String>.MAX_VERIFIED, 16)
    }

    test("when the list is full a new id is not even probed") {
        let s = DiscoveryState<String>(maxVerified: 1)
        let gen = s.start()
        discoveryAnnounce(s, gen, "a", discoveryFakeId(1))
        _ = discoveryDrain(s) { _ in true }
        check(discoveryAnnounce(s, gen, "b", discoveryFakeId(2)).isEmpty, "probed over cap")
    }

    test("a real server that announces after 50 fakes is still listed") {
        // The oldest of 100 failing probes are dropped, leaving the last announcement a chance.
        let s = DiscoveryState<String>()
        let gen = s.start()
        for i in 1...50 {
            discoveryAnnounce(s, gen, "fake-\(i)", discoveryFakeId(i), hosts: ["10.0.\(i).1", "10.0.\(i).2"])
        }
        discoveryAnnounce(s, gen, "Nightlight-4f1c2b9e", discoveryRealId)
        checkEqual(s.queuedProbeCount(), DiscoveryState<String>.MAX_QUEUED_PROBES)
        checkEqual(discoveryDrain(s) { $0.txtId == discoveryRealId }.map { $0.id }, [discoveryRealId])
    }

    test("the probe queue is bounded at 64 and drops the oldest") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        for i in 1...70 {
            discoveryAnnounce(s, gen, "svc-\(i)", discoveryFakeId(i), hosts: ["10.0.0.\(i % 250 + 1)"])
        }
        checkEqual(s.queuedProbeCount(), 64)
        checkEqual(DiscoveryState<String>.MAX_QUEUED_PROBES, 64)
        checkEqual(s.droppedProbes, 6)
        checkEqual(s.nextProbe()?.name, "svc-7")
    }

    test("a service whose probes were dropped can be announced again") {
        let s = DiscoveryState<String>(maxQueuedProbes: 1, reprobeIntervalMs: 0)
        let gen = s.start()
        discoveryAnnounce(s, gen, "first", discoveryFakeId(1))
        discoveryAnnounce(s, gen, "second", discoveryFakeId(2))
        checkEqual(s.nextProbe()?.name, "second")
        check(s.onFound(gen, "first", "first"), "dropped service still busy")
    }

    test("one entry per id, and a later announcement of a listed id does not replace it") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc-a", discoveryRealId, hosts: ["192.168.1.50"])
        _ = discoveryDrain(s) { _ in true }
        check(discoveryAnnounce(s, gen, "svc-b", discoveryRealId, hosts: ["192.168.1.99"]).isEmpty, "duplicate id probed")
        checkEqual(s.listedCount(), 1)
    }

    test("when two services race with the same id, the first that verifies wins") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc-a", discoveryRealId, hosts: ["192.168.1.50"])
        discoveryAnnounce(s, gen, "svc-b", discoveryRealId, hosts: ["192.168.1.99"])
        let found = discoveryDrain(s) { _ in true }
        checkEqual(found.count, 1)
        checkEqual(found.first?.url, "http://192.168.1.50:4000")
    }

    test("all of a service's addresses are probed and the first that verifies wins") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        let tasks = discoveryAnnounce(s, gen, "svc", discoveryRealId,
                                      hosts: ["172.17.0.1", "172.20.96.1", "192.168.1.50"])
        checkEqual(tasks.count, 3)
        checkEqual(discoveryDrain(s) { $0.host == "192.168.1.50" }.map { $0.url }, ["http://192.168.1.50:4000"])
    }

    test("after one address verifies, the service's other queued addresses are dropped") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId,
                          hosts: ["192.168.1.50", "192.168.1.51", "192.168.1.52"])
        let first = s.nextProbe()!
        check(s.onProbeDone(first, discoveryHealth(discoveryRealId)) != nil, "first address not listed")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("only private addresses are probed, at most 8 per service, without duplicates") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        let tasks = discoveryAnnounce(s, gen, "svc", discoveryRealId,
            hosts: ["8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "fe80::1",
                    "192.168.1.50", "192.168.1.50"])
        checkEqual(tasks.map { $0.host }, ["192.168.1.50"])
        let many = discoveryAnnounce(s, gen, "svc2", discoveryFakeId(2),
                                     hosts: (1...20).map { "10.0.0.\($0)" })
        checkEqual(many.count, 8)
        checkEqual(DiscoveryState<String>.MAX_ADDRESSES_PER_SERVICE, 8)
    }

    test("an announcement with only public addresses queues nothing") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        check(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["8.8.8.8", "1.1.1.1"]).isEmpty,
              "public address probed")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("an announcement with no usable id, a bad id or a bad port queues nothing") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        check(discoveryAnnounce(s, gen, "a", nil).isEmpty, "nil id")
        check(discoveryAnnounce(s, gen, "b", "not-a-uuid").isEmpty, "bad id")
        check(discoveryAnnounce(s, gen, "c", discoveryRealId, port: 0).isEmpty, "zero port")
        check(discoveryAnnounce(s, gen, "d", discoveryRealId, port: 70_000).isEmpty, "high port")
    }

    test("a repeat announcement of a service in progress is deduplicated, not queued again") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        check(s.onFound(gen, "svc", "svc"), "initial")
        check(!s.onFound(gen, "svc", "svc"), "queued")
        let job = s.nextResolve(1)!
        check(!s.onFound(gen, "svc", "svc"), "resolving")
        s.onResolved(job, discoveryRealId, ["192.168.1.50"], 4000, 0)
        check(!s.onFound(gen, "svc", "svc"), "probing")
        _ = discoveryDrain(s) { _ in true }
        check(!s.onFound(gen, "svc", "svc"), "listed")
    }

    test("a service that comes back inside 10 seconds is probed once when the window ends, not dropped") {
        // A quick restart used to lose the card until a new search; the answer now waits for the interval's end.
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        check(discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 5_000).isEmpty, "too early")
        checkEqual(s.deferredDueAt("svc"), 11_000)
        check(s.runDeferred("svc", 10_999).isEmpty, "ran before due")
        checkEqual(s.runDeferred("svc", 11_000).map { $0.host }, ["192.168.1.50"])
        check(s.deferredDueAt("svc") == nil, "more than one deferral")
        check(s.runDeferred("svc", 20_000).isEmpty, "ran twice")
        checkEqual(discoveryDrain(s) { _ in true }.map { $0.id }, [discoveryRealId])
        checkEqual(DiscoveryState<String>.REPROBE_INTERVAL_MS, 10_000)
    }

    test("a newer answer replaces the deferred one, and the probe that runs uses the newest address") {
        // A deferred name remains available for a newer announcement, so an old address is not tried later.
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50"], now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        check(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.60"], now: 3_000).isEmpty, "too early")
        check(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.70"], now: 6_000).isEmpty, "too early")
        checkEqual(s.deferredDueAt("svc"), 11_000)
        checkEqual(s.runDeferred("svc", 11_000).map { $0.host }, ["192.168.1.70"])
        check(s.deferredDueAt("svc") == nil, "deferral not removed")
        checkEqual(s.queuedProbeCount(), 1)
    }

    test("a newer answer with no usable address drops the deferred probe instead of probing the old address") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50"], now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.60"], now: 3_000)
        check(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["8.8.8.8"], now: 6_000).isEmpty, "public")
        check(s.deferredDueAt("svc") == nil, "old deferral remains")
        check(s.runDeferred("svc", 11_000).isEmpty, "old address probed")
    }

    test("a replaced deferral still runs nothing after a stop, a new search or a loss") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50"], now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.60"], now: 3_000)
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.70"], now: 4_000)
        s.onLost(gen, "svc")
        check(s.runDeferred("svc", 11_000).isEmpty, "lost")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.60"], now: 5_000)
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.70"], now: 6_000)
        s.stop()
        check(s.runDeferred("svc", 11_000).isEmpty, "stopped")
        let gen2 = s.start()
        check(s.runDeferred("svc", 11_000).isEmpty, "new search")
        check(!s.isCurrent(gen), "old generation current")
        check(s.isCurrent(gen2), "new generation not current")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("after the window a returning service is probed at once") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        checkEqual(discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 11_000).count, 1)
        check(s.deferredDueAt("svc") == nil, "deferred at boundary")
    }

    test("a deferred probe is dropped by stop, by a new search and by losing the service") {
        let s = DiscoveryState<String>()
        var gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 2_000)
        s.onLost(gen, "svc")
        check(s.deferredDueAt("svc") == nil, "loss retained deferral")
        check(s.runDeferred("svc", 99_000).isEmpty, "lost work ran")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 3_000)
        check(s.deferredDueAt("svc") != nil, "no deferral")
        s.stop()
        gen = s.start()
        check(s.deferredDueAt("svc") == nil, "stop retained deferral")
        check(s.runDeferred("svc", 99_000).isEmpty, "stopped work ran")
        checkEqual(s.queuedProbeCount(), 0)
        check(s.isCurrent(gen), "new generation")
    }

    test("a deferred probe that loses to a listed id or a full list probes nothing") {
        let s = DiscoveryState<String>(maxVerified: 1)
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 1_000)
        _ = discoveryDrain(s) { _ in false }
        s.onLost(gen, "svc")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, now: 2_000)
        discoveryAnnounce(s, gen, "other", discoveryFakeId(9), now: 2_000)
        _ = discoveryDrain(s) { _ in true }
        check(s.runDeferred("svc", 11_000).isEmpty, "full list probed")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("a stale generation's results are discarded") {
        let s = DiscoveryState<String>()
        let gen1 = s.start()
        discoveryAnnounce(s, gen1, "svc", discoveryRealId)
        let oldTask = s.nextProbe()!
        s.stop()
        let gen2 = s.start()
        check(gen2 > gen1, "generation not advanced")
        check(s.onProbeDone(oldTask, discoveryHealth(discoveryRealId)) == nil, "old probe listed")
        checkEqual(s.listedCount(), 0)
        check(!s.onFound(gen1, "other", "other"), "old found accepted")
        check(s.onLost(gen1, "svc") == nil, "old loss changed list")
    }

    test("nothing is listed after stop, and stop forgets everything") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50", "192.168.1.51"])
        let running = s.nextProbe()!
        s.stop()
        check(!s.isCurrent(gen), "still running")
        check(s.onProbeDone(running, discoveryHealth(discoveryRealId)) == nil, "late probe listed")
        check(s.nextProbe() == nil, "queued after stop")
        checkEqual(s.queuedProbeCount(), 0)
        check(!s.onFound(gen, "svc2", "svc2"), "found after stop")
    }

    test("a stale resolve answer is discarded") {
        let s = DiscoveryState<String>()
        let gen1 = s.start()
        s.onFound(gen1, "svc", "svc")
        let job = s.nextResolve(1)!
        s.stop()
        s.start()
        check(s.onResolved(job, discoveryRealId, ["192.168.1.50"], 4000, 0).isEmpty, "stale answer queued")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("losing a service removes its card and returns its id once") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        _ = discoveryDrain(s) { _ in true }
        checkEqual(s.onLost(gen, "svc"), discoveryRealId)
        check(!s.isListed(discoveryRealId), "card still listed")
        check(s.onLost(gen, "svc") == nil, "second loss returned id")
        check(s.onLost(gen, "never-seen") == nil, "unknown loss returned id")
    }

    test("a probe still running when its service was lost cannot bring the card back") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        let running = s.nextProbe()!
        s.onLost(gen, "svc")
        check(s.onProbeDone(running, discoveryHealth(discoveryRealId)) == nil, "lost service listed")
        checkEqual(s.listedCount(), 0)
    }

    test("losing a service drops its queued resolve and probes") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "queued", "queued")
        s.onLost(gen, "queued")
        check(s.nextResolve(1) == nil, "lost resolve queued")
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50", "192.168.1.51"])
        s.onLost(gen, "svc")
        checkEqual(s.queuedProbeCount(), 0)
    }

    test("resolves run at most slots at a time") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "a", "a")
        s.onFound(gen, "b", "b")
        let a = s.nextResolve(1)!
        check(s.nextResolve(1) == nil, "one slot exceeded")
        check(s.nextResolve(2) != nil, "second slot unused")
        s.resolveFailed(a, retry: false)
        check(s.nextResolve(1) == nil, "unexpected resolve")
    }

    test("a busy resolve is retried, at most 3 attempts in all") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "svc", "svc")
        for attempt in 1...3 {
            let job = s.nextResolve(1)!
            checkEqual(job.attempt, attempt)
            check(s.resolveFailed(job, retry: true, nowMs: Int64(attempt * 1_000)), "failed attempt not ended")
            s.requeueDueRetries(Int64(attempt * 1_000 + 500))
        }
        check(!s.isWaitingRetry("svc"), "fourth retry")
        check(s.nextResolve(1) == nil, "fourth attempt")
        checkEqual(DiscoveryState<String>.MAX_RESOLVE_ATTEMPTS, 3)
    }

    test("a retry waits 500 ms outside the queue before it goes back") {
        // An immediate retry burned all three attempts while another resolve was still active.
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!, retry: true, nowMs: 1_000)
        check(s.isWaitingRetry("svc"), "retry not waiting")
        checkEqual(s.nextRetryDueAt(), 1_500)
        check(s.nextResolve(1) == nil, "resolved while waiting")
        check(!s.onFound(gen, "svc", "svc"), "waiting name accepted")
        checkEqual(s.requeueDueRetries(1_499), 0)
        check(s.nextResolve(1) == nil, "early retry")
        checkEqual(s.requeueDueRetries(1_500), 1)
        check(s.nextRetryDueAt() == nil, "retry still waiting")
        checkEqual(s.nextResolve(1)?.attempt, 2)
        checkEqual(DiscoveryState<String>.RETRY_DELAY_MS, 500)
    }

    test("a waiting retry is dropped by losing the service or stopping") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!, retry: true)
        s.onLost(gen, "svc")
        check(s.nextRetryDueAt() == nil, "lost retry waiting")
        checkEqual(s.requeueDueRetries(99_000), 0)
        check(s.nextResolve(1) == nil, "lost retry queued")
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!, retry: true)
        s.stop()
        s.start()
        check(s.nextRetryDueAt() == nil, "stopped retry waiting")
        checkEqual(s.requeueDueRetries(99_000), 0)
        check(s.nextResolve(1) == nil, "stopped retry queued")
    }

    test("a retry put back into a full resolve queue drops the oldest queued service") {
        let s = DiscoveryState<String>(maxQueuedResolves: 2)
        let gen = s.start()
        s.onFound(gen, "a", "a")
        let a = s.nextResolve(1)!
        s.onFound(gen, "b", "b")
        s.onFound(gen, "c", "c")
        s.resolveFailed(a, retry: true, nowMs: 0)
        checkEqual(s.requeueDueRetries(500), 1)
        checkEqual(s.nextResolve(1)?.name, "c")
        check(s.nextResolve(1) == nil, "slot exceeded")
    }

    test("when more services wait to retry than the cap, the oldest is dropped and never retried") {
        // Due times live with entries; an evicted service must have no pending timer.
        let s = DiscoveryState<String>(maxQueuedResolves: 2)
        let gen = s.start()
        for (i, name) in ["a", "b", "c"].enumerated() {
            s.onFound(gen, name, name)
            s.resolveFailed(s.nextResolve(1)!, retry: true, nowMs: Int64(i * 100))
        }
        check(!s.isWaitingRetry("a"), "oldest still waiting")
        checkEqual(s.waitingRetryCount(), 2)
        checkEqual(s.nextRetryDueAt(), 600)
        checkEqual(s.requeueDueRetries(10_000), 2)
        checkEqual([s.nextResolve(9)?.name, s.nextResolve(9)?.name].compactMap { $0 }, ["b", "c"])
        check(s.nextResolve(9) == nil, "extra resolve")
        check(s.nextRetryDueAt() == nil, "pending retry")
        check(s.onFound(gen, "a", "a"), "evicted name busy")
    }

    test("a waiting retry for a service forgotten by the name cap does not push a live one out of the queue") {
        let s = DiscoveryState<String>(maxQueuedResolves: 1, maxTrackedNames: 1)
        let gen = s.start()
        s.onFound(gen, "a", "a")
        s.resolveFailed(s.nextResolve(1)!, retry: true, nowMs: 0)
        s.onFound(gen, "b", "b")
        checkEqual(s.requeueDueRetries(500), 0)
        checkEqual(s.nextResolve(1)?.name, "b")
    }

    test("the waiting-retry list never holds more than the cap, however many fail") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        for i in 1...1_000 {
            s.onFound(gen, "svc-\(i)", "svc-\(i)")
            s.resolveFailed(s.nextResolve(1)!, retry: true, nowMs: Int64(i))
        }
        checkEqual(s.waitingRetryCount(), DiscoveryState<String>.MAX_QUEUED_RESOLVES)
        checkEqual(s.nextRetryDueAt(), 937 + 500)
    }

    test("a failure that is not a busy refusal is not retried") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!, retry: false)
        check(s.nextResolve(1) == nil, "nontransient failure retried")
    }

    test("a late report from an attempt that already ended changes nothing") {
        // The timeout ends attempt 1; its callback arrives after attempt 2 has started.
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "svc", "svc")
        let first = s.nextResolve(1)!
        check(s.resolveFailed(first, retry: true, nowMs: 0), "attempt 1")
        s.requeueDueRetries(500)
        let second = s.nextResolve(1)!
        check(!s.resolveFailed(first, retry: true), "late failure affected state")
        check(s.onResolved(first, discoveryRealId, ["192.168.1.50"], 4000, 0).isEmpty, "late answer queued")
        check(s.isResolving(second), "attempt 2 ended")
        checkEqual(s.onResolved(second, discoveryRealId, ["192.168.1.50"], 4000, 0).count, 1)
    }

    test("the resolve queue is bounded and drops the oldest") {
        let s = DiscoveryState<String>(maxQueuedResolves: 3)
        let gen = s.start()
        for i in 1...5 { s.onFound(gen, "svc-\(i)", "svc-\(i)") }
        checkEqual(s.nextResolve(9)?.name, "svc-3")
        checkEqual(DiscoveryState<String>.MAX_QUEUED_RESOLVES, 64)
    }

    test("an empty or null service name is ignored") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        check(!s.onFound(gen, nil, "x"), "nil name")
        check(!s.onFound(gen, "", "x"), "empty name")
    }

    // The following cases come from the Android code review's state-machine regressions.
    test("a service lost while its resolve is in flight frees its slot, even with one legacy slot") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        s.onFound(gen, "gone", "gone")
        s.onFound(gen, "waiting", "waiting")
        let inFlight = s.nextResolve(1)!
        checkEqual(inFlight.name, "gone")
        check(s.nextResolve(1) == nil, "slot not held")
        s.onLost(gen, "gone")
        checkEqual(s.nextResolve(1)?.name, "waiting")
        check(s.onResolved(inFlight, discoveryRealId, ["192.168.1.50"], 4000, 0).isEmpty,
              "lost service's late answer queued")
    }

    test("a service lost while its probe runs is not left busy and can be probed again") {
        let s = DiscoveryState<String>(reprobeIntervalMs: 0)
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId)
        let running = s.nextProbe()!
        s.onLost(gen, "svc")
        checkEqual(discoveryAnnounce(s, gen, "svc", discoveryRealId).count, 1)
        check(s.onProbeDone(running, discoveryHealth(discoveryRealId)) == nil, "old probe listed")
        checkEqual(discoveryDrain(s) { _ in true }.map { $0.id }, [discoveryRealId])
    }

    test("a probe of a lost service that finishes late does not count down the service's new batch") {
        let s = DiscoveryState<String>(reprobeIntervalMs: 0)
        let gen = s.start()
        discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.50"])
        let old = s.nextProbe()!
        s.onLost(gen, "svc")
        checkEqual(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: ["192.168.1.60"]).count, 1)
        s.onProbeDone(old, nil)
        check(!s.onFound(gen, "svc", "svc"), "new batch no longer busy")
        checkEqual(s.queuedProbeCount(), 1)
        checkEqual(discoveryDrain(s) { _ in true }.map { $0.url }, ["http://192.168.1.60:4000"])
    }

    test("a queued probe of a service forgotten by the name cap is skipped, not run") {
        let s = DiscoveryState<String>(maxTrackedNames: 1)
        let gen = s.start()
        discoveryAnnounce(s, gen, "a", discoveryFakeId(1), hosts: ["192.168.1.10"])
        discoveryAnnounce(s, gen, "b", discoveryFakeId(2), hosts: ["192.168.1.20"])
        checkEqual(s.nextProbe()?.name, "b")
        check(s.nextProbe() == nil, "forgotten probe ran")
        check(s.onFound(gen, "a", "a"), "forgotten name busy")
    }

    test("a running probe of a service forgotten by the name cap still counts down, so it is not left busy") {
        let s = DiscoveryState<String>(maxTrackedNames: 1)
        let gen = s.start()
        discoveryAnnounce(s, gen, "a", discoveryFakeId(1), hosts: ["192.168.1.10"])
        let running = s.nextProbe()!
        discoveryAnnounce(s, gen, "b", discoveryFakeId(2), hosts: ["192.168.1.20"])
        check(s.onProbeDone(running, discoveryHealth(discoveryFakeId(1))) == nil, "forgotten probe listed")
        check(s.onFound(gen, "a", "a"), "forgotten name busy")
    }

    test("private addresses are picked before the cap of 8, so public ones listed first cannot push the real one out") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        let hosts = (1...8).map { "8.8.8.\($0)" } + ["192.168.1.50"]
        checkEqual(discoveryAnnounce(s, gen, "svc", discoveryRealId, hosts: hosts).map { $0.host }, ["192.168.1.50"])
    }

    test("a flood of losses for names never announced records nothing") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        for i in 1...10_000 { check(s.onLost(gen, "invented-\(i)") == nil, "unknown loss") }
        checkEqual(s.trackedNameCount(), 0)
    }

    test("per-name bookkeeping stays within 256 names however many are announced") {
        let s = DiscoveryState<String>()
        let gen = s.start()
        for i in 1...2_000 {
            s.onFound(gen, "svc-\(i)", "svc-\(i)")
            guard let job = s.nextResolve(1) else { continue }
            s.onResolved(job, discoveryFakeId(i), ["10.0.\(i / 250).\(i % 250 + 1)"], 4000, Int64(i))
            _ = discoveryDrain(s) { _ in false }
        }
        check(s.trackedNameCount() <= 256, "tracked \(s.trackedNameCount())")
        checkEqual(DiscoveryState<String>.MAX_TRACKED_NAMES, 256)
    }

    test("a service forgotten by the name cap gives its resolve slot back and is not left busy") {
        let s = DiscoveryState<String>(maxTrackedNames: 2)
        let gen = s.start()
        s.onFound(gen, "a", "a")
        let a = s.nextResolve(1)!
        s.onFound(gen, "b", "b")
        s.onFound(gen, "c", "c")
        check(s.onResolved(a, discoveryRealId, ["192.168.1.50"], 4000, 0).isEmpty,
              "forgotten answer queued")
        checkEqual(s.nextResolve(1)?.name, "b")
        check(s.onFound(gen, "a", "a"), "forgotten name busy")
    }
}
