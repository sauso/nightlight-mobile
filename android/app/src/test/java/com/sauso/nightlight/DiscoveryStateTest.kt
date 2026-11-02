package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping that keeps discovery bounded and the list honest (#20 plan review: a flat cap lets fakes starve a
 * real server; late results must not repopulate a page that moved on). The payload type is a String here; in the
 * app it is an NsdServiceInfo.
 */
class DiscoveryStateTest {

    private val realId = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"

    private fun fakeId(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun health(id: String, name: String? = "Home") = DiscoveryRules.Health(id, name)

    /** Found -> resolved with [hosts]; returns the queued probe tasks. */
    private fun DiscoveryState<String>.announce(
        gen: Int,
        name: String,
        txtId: String?,
        hosts: List<String> = listOf("192.168.1.50"),
        port: Int = 4000,
        now: Long = 0L,
    ): List<DiscoveryState.ProbeTask> {
        assertTrue("found $name", onFound(gen, name, name))
        val job = nextResolve(slots = 1)
        assertNotNull("resolve job for $name", job)
        return onResolved(job!!, txtId, hosts, port, now)
    }

    /** Run every queued probe; [verifies] says which (txtId, host) answer correctly. */
    private fun DiscoveryState<String>.drain(verifies: (DiscoveryState.ProbeTask) -> Boolean): List<DiscoveryState.Found> {
        val found = mutableListOf<DiscoveryState.Found>()
        while (true) {
            val task = nextProbe() ?: break
            val result = onProbeDone(task, if (verifies(task)) health(task.txtId) else null)
            if (result != null) found.add(result)
        }
        return found
    }

    @Test
    fun `a verified server is listed with its name, url and fingerprint`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        val tasks = s.announce(gen, "Nightlight-4f1c2b9e", realId)
        assertEquals(1, tasks.size)
        val found = s.drain { true }
        assertEquals(
            listOf(DiscoveryState.Found(realId, "Home", "http://192.168.1.50:4000", "4f1c2b9e")),
            found,
        )
        assertTrue(s.isListed(realId))
    }

    @Test
    fun `a server with no name is listed as Nightlight`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId)
        val task = s.nextProbe()!!
        assertEquals("Nightlight", s.onProbeDone(task, health(realId, name = null))!!.name)
    }

    @Test
    fun `a failed probe lists nothing`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId)
        assertTrue(s.drain { false }.isEmpty())
        assertEquals(0, s.listedCount())
    }

    @Test
    fun `a reply whose id differs from the announcement lists nothing`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId)
        val task = s.nextProbe()!!
        assertNull(s.onProbeDone(task, health(fakeId(1))))
    }

    @Test
    fun `only verified servers count toward the cap of 16`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        // 20 fakes whose probes fail take no slot...
        for (i in 1..20) s.announce(gen, "fake-$i", fakeId(i), hosts = listOf("10.0.0.$i"))
        assertTrue(s.drain { false }.isEmpty())
        // ...so 16 real servers after them are all listed, and a 17th is not.
        for (i in 1..17) s.announce(gen, "real-$i", fakeId(100 + i), hosts = listOf("10.0.1.$i"))
        val found = s.drain { true }
        assertEquals(16, found.size)
        assertEquals(16, s.listedCount())
        assertEquals(16, DiscoveryState.MAX_VERIFIED)
    }

    @Test
    fun `when the list is full a new id is not even probed`() {
        val s = DiscoveryState<String>(maxVerified = 1)
        val gen = s.start()
        s.announce(gen, "a", fakeId(1))
        s.drain { true }
        assertTrue(s.announce(gen, "b", fakeId(2)).isEmpty())
    }

    @Test
    fun `a real server that announces after 50 fakes is still listed`() {
        // The fakes announce two addresses each (100 probes) and every probe fails. With the bounded queue dropping
        // the OLDEST, the real server, announced last, is still probed and listed.
        val s = DiscoveryState<String>()
        val gen = s.start()
        for (i in 1..50) s.announce(gen, "fake-$i", fakeId(i), hosts = listOf("10.0.$i.1", "10.0.$i.2"))
        s.announce(gen, "Nightlight-4f1c2b9e", realId, hosts = listOf("192.168.1.50"))
        assertEquals(DiscoveryState.MAX_QUEUED_PROBES, s.queuedProbeCount())
        val found = s.drain { it.txtId == realId }
        assertEquals(listOf(realId), found.map { it.id })
    }

    @Test
    fun `the probe queue is bounded at 64 and drops the oldest`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        for (i in 1..70) s.announce(gen, "svc-$i", fakeId(i), hosts = listOf("10.0.0.${i % 250 + 1}"))
        assertEquals(64, s.queuedProbeCount())
        assertEquals(64, DiscoveryState.MAX_QUEUED_PROBES)
        assertEquals(6, s.droppedProbes)
        // svc-1 .. svc-6 were dropped; the oldest left is svc-7.
        assertEquals("svc-7", s.nextProbe()!!.name)
    }

    @Test
    fun `a service whose probes were dropped can be announced again`() {
        val s = DiscoveryState<String>(maxQueuedProbes = 1, reprobeIntervalMs = 0)
        val gen = s.start()
        s.announce(gen, "first", fakeId(1))
        s.announce(gen, "second", fakeId(2)) // drops first's only probe
        assertEquals("second", s.nextProbe()!!.name)
        // first has nothing pending any more, so it is not "busy": a repeat announcement is accepted.
        assertTrue(s.onFound(gen, "first", "first"))
    }

    @Test
    fun `one entry per id, and a later announcement of a listed id does not replace it`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc-a", realId, hosts = listOf("192.168.1.50"))
        s.drain { true }
        // A second service (another name, another address) claiming the same id is not even probed.
        assertTrue(s.announce(gen, "svc-b", realId, hosts = listOf("192.168.1.99")).isEmpty())
        assertEquals(1, s.listedCount())
    }

    @Test
    fun `when two services race with the same id, the first that verifies wins`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc-a", realId, hosts = listOf("192.168.1.50"))
        s.announce(gen, "svc-b", realId, hosts = listOf("192.168.1.99"))
        val found = s.drain { true }
        assertEquals(1, found.size)
        assertEquals("http://192.168.1.50:4000", found[0].url)
    }

    @Test
    fun `all of a service's addresses are probed and the first that verifies wins`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        // The design's case: the real LAN address is not first (a Docker bridge gateway and a WSL address are).
        val tasks = s.announce(gen, "svc", realId, hosts = listOf("172.17.0.1", "172.20.96.1", "192.168.1.50"))
        assertEquals(3, tasks.size)
        val found = s.drain { it.host == "192.168.1.50" }
        assertEquals(listOf("http://192.168.1.50:4000"), found.map { it.url })
    }

    @Test
    fun `after one address verifies, the service's other queued addresses are dropped`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50", "192.168.1.51", "192.168.1.52"))
        val first = s.nextProbe()!!
        assertNotNull(s.onProbeDone(first, health(realId)))
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `only private addresses are probed, at most 8 per service, without duplicates`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        val tasks = s.announce(
            gen, "svc", realId,
            hosts = listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "fe80::1", "192.168.1.50", "192.168.1.50"),
        )
        assertEquals(listOf("192.168.1.50"), tasks.map { it.host })
        val many = s.announce(gen, "svc2", fakeId(2), hosts = (1..20).map { "10.0.0.$it" })
        assertEquals(8, many.size)
        assertEquals(8, DiscoveryState.MAX_ADDRESSES_PER_SERVICE)
    }

    @Test
    fun `an announcement with only public addresses queues nothing`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        assertTrue(s.announce(gen, "svc", realId, hosts = listOf("8.8.8.8", "1.1.1.1")).isEmpty())
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `an announcement with no usable id, a bad id or a bad port queues nothing`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        assertTrue(s.announce(gen, "a", null).isEmpty())
        assertTrue(s.announce(gen, "b", "not-a-uuid").isEmpty())
        assertTrue(s.announce(gen, "c", realId, port = 0).isEmpty())
        assertTrue(s.announce(gen, "d", realId, port = 70_000).isEmpty())
    }

    @Test
    fun `a repeat announcement of a service in progress is deduplicated, not queued again`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        assertTrue(s.onFound(gen, "svc", "svc"))
        assertFalse("queued for resolve", s.onFound(gen, "svc", "svc"))
        val job = s.nextResolve(1)!!
        assertFalse("being resolved", s.onFound(gen, "svc", "svc"))
        s.onResolved(job, realId, listOf("192.168.1.50"), 4000, 0L)
        assertFalse("probe pending", s.onFound(gen, "svc", "svc"))
        s.drain { true }
        assertFalse("listed", s.onFound(gen, "svc", "svc"))
    }

    @Test
    fun `a service that comes back inside 10 seconds is probed once when the window ends, not dropped`() {
        // Code review of #20: a server that restarts quickly used to lose its card until Search again.
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        // Back at 5 s: nothing yet, but a probe is waiting for 11 s (the first probe plus the interval).
        assertTrue(s.announce(gen, "svc", realId, now = 5_000L).isEmpty())
        assertEquals(11_000L, s.deferredDueAt("svc"))
        assertTrue("not before the window ends", s.runDeferred("svc", 10_999L).isEmpty())
        val tasks = s.runDeferred("svc", 11_000L)
        assertEquals(listOf("192.168.1.50"), tasks.map { it.host })
        assertNull("exactly one probe", s.deferredDueAt("svc"))
        assertTrue(s.runDeferred("svc", 20_000L).isEmpty())
        assertEquals(listOf(realId), s.drain { true }.map { it.id })
        assertEquals(10_000L, DiscoveryState.REPROBE_INTERVAL_MS)
    }

    @Test
    fun `a newer answer replaces the deferred one, and the probe that runs uses the newest address`() {
        // Second review of #20: a deferred name used to count as busy, so a later announcement with a new address
        // was refused and the stale first address was probed when the window ended. Kills "keep the first answer".
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50"), now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        assertTrue(s.announce(gen, "svc", realId, hosts = listOf("192.168.1.60"), now = 3_000L).isEmpty())
        // Announced again, no loss in between, at a new address: accepted, resolved, and it replaces the first.
        assertTrue(s.announce(gen, "svc", realId, hosts = listOf("192.168.1.70"), now = 6_000L).isEmpty())
        assertEquals("still one deferral, same due time", 11_000L, s.deferredDueAt("svc"))
        assertEquals(listOf("192.168.1.70"), s.runDeferred("svc", 11_000L).map { it.host })
        assertNull(s.deferredDueAt("svc"))
        assertEquals("exactly one probe, at the newest address", 1, s.queuedProbeCount())
    }

    @Test
    fun `a newer answer with no usable address drops the deferred probe instead of probing the old address`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50"), now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.60"), now = 3_000L)
        assertTrue(s.announce(gen, "svc", realId, hosts = listOf("8.8.8.8"), now = 6_000L).isEmpty())
        assertNull(s.deferredDueAt("svc"))
        assertTrue(s.runDeferred("svc", 11_000L).isEmpty())
    }

    @Test
    fun `a replaced deferral still runs nothing after a stop, a new search or a loss`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50"), now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.60"), now = 3_000L)
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.70"), now = 4_000L)
        s.onLost(gen, "svc")
        assertTrue("lost", s.runDeferred("svc", 11_000L).isEmpty())

        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.60"), now = 5_000L)
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.70"), now = 6_000L)
        s.stop()
        assertTrue("stopped", s.runDeferred("svc", 11_000L).isEmpty())
        val gen2 = s.start()
        assertTrue("new search", s.runDeferred("svc", 11_000L).isEmpty())
        assertFalse(s.isCurrent(gen))
        assertTrue(s.isCurrent(gen2))
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `after the window a returning service is probed at once`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        assertEquals(1, s.announce(gen, "svc", realId, now = 11_000L).size)
        assertNull(s.deferredDueAt("svc"))
    }

    @Test
    fun `a deferred probe is dropped by stop, by a new search and by losing the service`() {
        val s = DiscoveryState<String>()
        var gen = s.start()
        s.announce(gen, "svc", realId, now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        s.announce(gen, "svc", realId, now = 2_000L)
        s.onLost(gen, "svc")
        assertNull("lost", s.deferredDueAt("svc"))
        assertTrue(s.runDeferred("svc", 99_000L).isEmpty())

        s.announce(gen, "svc", realId, now = 3_000L)
        assertNotNull(s.deferredDueAt("svc"))
        s.stop()
        gen = s.start()
        assertNull("stopped", s.deferredDueAt("svc"))
        assertTrue(s.runDeferred("svc", 99_000L).isEmpty())
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `a deferred probe that loses to a listed id or a full list probes nothing`() {
        val s = DiscoveryState<String>(maxVerified = 1)
        val gen = s.start()
        s.announce(gen, "svc", realId, now = 1_000L)
        s.drain { false }
        s.onLost(gen, "svc")
        s.announce(gen, "svc", realId, now = 2_000L)
        s.announce(gen, "other", fakeId(9), now = 2_000L)
        s.drain { true } // "other" fills the one slot
        assertTrue(s.runDeferred("svc", 11_000L).isEmpty())
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `a stale generation's results are discarded`() {
        val s = DiscoveryState<String>()
        val gen1 = s.start()
        s.announce(gen1, "svc", realId)
        val oldTask = s.nextProbe()!!
        s.stop()
        val gen2 = s.start()
        assertTrue(gen2 > gen1)
        assertNull("an old search's probe must not list a server", s.onProbeDone(oldTask, health(realId)))
        assertEquals(0, s.listedCount())
        assertFalse(s.onFound(gen1, "other", "other"))
        assertNull(s.onLost(gen1, "svc"))
    }

    @Test
    fun `nothing is listed after stop, and stop forgets everything`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50", "192.168.1.51"))
        val running = s.nextProbe()!!
        s.stop()
        assertFalse(s.isCurrent(gen))
        assertNull(s.onProbeDone(running, health(realId)))
        assertNull(s.nextProbe())
        assertEquals(0, s.queuedProbeCount())
        assertFalse(s.onFound(gen, "svc2", "svc2"))
    }

    @Test
    fun `a stale resolve answer is discarded`() {
        val s = DiscoveryState<String>()
        val gen1 = s.start()
        s.onFound(gen1, "svc", "svc")
        val job = s.nextResolve(1)!!
        s.stop()
        s.start()
        assertTrue(s.onResolved(job, realId, listOf("192.168.1.50"), 4000, 0L).isEmpty())
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `losing a service removes its card and returns its id once`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId)
        s.drain { true }
        assertEquals(realId, s.onLost(gen, "svc"))
        assertFalse(s.isListed(realId))
        assertNull("a second loss reports nothing", s.onLost(gen, "svc"))
        assertNull("an unknown service", s.onLost(gen, "never-seen"))
    }

    @Test
    fun `a probe still running when its service was lost cannot bring the card back`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId)
        val running = s.nextProbe()!!
        s.onLost(gen, "svc")
        assertNull(s.onProbeDone(running, health(realId)))
        assertEquals(0, s.listedCount())
    }

    @Test
    fun `losing a service drops its queued resolve and probes`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "queued", "queued")
        s.onLost(gen, "queued")
        assertNull(s.nextResolve(1))
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50", "192.168.1.51"))
        s.onLost(gen, "svc")
        assertEquals(0, s.queuedProbeCount())
    }

    @Test
    fun `resolves run at most slots at a time`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "a", "a")
        s.onFound(gen, "b", "b")
        val a = s.nextResolve(1)!!
        assertNull("one at a time before API 34", s.nextResolve(1))
        assertNotNull("but more slots allow more", s.nextResolve(2))
        s.resolveFailed(a, retry = false)
        assertNull(s.nextResolve(1))
    }

    @Test
    fun `a busy resolve is retried, at most 3 attempts in all`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "svc", "svc")
        for (attempt in 1..3) {
            val job = s.nextResolve(1)!!
            assertEquals(attempt, job.attempt)
            assertTrue(s.resolveFailed(job, retry = true, nowMs = attempt * 1_000L))
            s.requeueDueRetries(attempt * 1_000L + 500L)
        }
        assertFalse(s.isWaitingRetry("svc"))
        assertNull("no fourth attempt", s.nextResolve(1))
        assertEquals(3, DiscoveryState.MAX_RESOLVE_ATTEMPTS)
    }

    @Test
    fun `a retry waits 500 ms outside the queue before it goes back`() {
        // Code review of #20: straight back in the queue, a busy refusal burned all three attempts in milliseconds.
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!!, retry = true, nowMs = 1_000L)
        assertTrue(s.isWaitingRetry("svc"))
        assertEquals(1_500L, s.nextRetryDueAt())
        assertNull("not resolvable while it waits", s.nextResolve(1))
        assertFalse("still busy while it waits", s.onFound(gen, "svc", "svc"))
        assertEquals("not before its time", 0, s.requeueDueRetries(1_499L))
        assertNull(s.nextResolve(1))
        assertEquals(1, s.requeueDueRetries(1_500L))
        assertNull("nothing left waiting", s.nextRetryDueAt())
        assertEquals(2, s.nextResolve(1)!!.attempt)
        assertEquals(500L, DiscoveryState.RETRY_DELAY_MS)
    }

    @Test
    fun `a waiting retry is dropped by losing the service or stopping`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!!, retry = true)
        s.onLost(gen, "svc")
        assertNull(s.nextRetryDueAt())
        assertEquals(0, s.requeueDueRetries(99_000L))
        assertNull(s.nextResolve(1))

        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!!, retry = true)
        s.stop()
        s.start()
        assertNull(s.nextRetryDueAt())
        assertEquals(0, s.requeueDueRetries(99_000L))
        assertNull(s.nextResolve(1))
    }

    @Test
    fun `a retry put back into a full resolve queue drops the oldest queued service`() {
        val s = DiscoveryState<String>(maxQueuedResolves = 2)
        val gen = s.start()
        s.onFound(gen, "a", "a")
        val a = s.nextResolve(1)!!
        s.onFound(gen, "b", "b")
        s.onFound(gen, "c", "c")
        s.resolveFailed(a, retry = true, nowMs = 0L)
        assertEquals(1, s.requeueDueRetries(500L)) // queue was [b, c]; now [c, a]
        assertEquals("c", s.nextResolve(1)!!.name)
        assertNull(s.nextResolve(1)) // one slot, held by c
    }

    @Test
    fun `when more services wait to retry than the cap, the oldest is dropped and never retried`() {
        // Second review of #20: a per-service retry timer outlived the waiting entry the cap dropped. Now each waiting
        // retry carries its due time, the dropped one has none, and nothing is left pending for it.
        val s = DiscoveryState<String>(maxQueuedResolves = 2)
        val gen = s.start()
        for ((i, name) in listOf("a", "b", "c").withIndex()) {
            s.onFound(gen, name, name)
            s.resolveFailed(s.nextResolve(1)!!, retry = true, nowMs = i * 100L)
        }
        assertFalse("a, the oldest, was dropped", s.isWaitingRetry("a"))
        assertEquals(2, s.waitingRetryCount())
        assertEquals("the earliest due is b's", 600L, s.nextRetryDueAt())
        assertEquals(2, s.requeueDueRetries(10_000L))
        assertEquals(listOf("b", "c"), listOfNotNull(s.nextResolve(9)?.name, s.nextResolve(9)?.name))
        assertNull(s.nextResolve(9))
        assertNull("nothing left pending", s.nextRetryDueAt())
        assertTrue("a is free to be announced again", s.onFound(gen, "a", "a"))
    }

    @Test
    fun `a waiting retry for a service forgotten by the name cap does not push a live one out of the queue`() {
        val s = DiscoveryState<String>(maxTrackedNames = 1, maxQueuedResolves = 1)
        val gen = s.start()
        s.onFound(gen, "a", "a")
        s.resolveFailed(s.nextResolve(1)!!, retry = true, nowMs = 0L)
        s.onFound(gen, "b", "b") // forgets a; b waits in the (full) queue
        assertEquals("a is dropped, not requeued", 0, s.requeueDueRetries(500L))
        assertEquals("b", s.nextResolve(1)!!.name)
    }

    @Test
    fun `the waiting-retry list never holds more than the cap, however many fail`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        for (i in 1..1_000) {
            s.onFound(gen, "svc-$i", "svc-$i")
            s.resolveFailed(s.nextResolve(1)!!, retry = true, nowMs = i.toLong())
        }
        assertEquals(DiscoveryState.MAX_QUEUED_RESOLVES, s.waitingRetryCount())
        assertEquals("the oldest still waiting is svc-937", 937L + 500L, s.nextRetryDueAt())
    }

    @Test
    fun `a failure that is not a busy refusal is not retried`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "svc", "svc")
        s.resolveFailed(s.nextResolve(1)!!, retry = false)
        assertNull(s.nextResolve(1))
    }

    @Test
    fun `a late report from an attempt that already ended changes nothing`() {
        // The timeout ends attempt 1; its NsdManager callback arrives afterwards, while attempt 2 is running.
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "svc", "svc")
        val first = s.nextResolve(1)!!
        assertTrue(s.resolveFailed(first, retry = true, nowMs = 0L)) // timeout, retried
        s.requeueDueRetries(500L)
        val second = s.nextResolve(1)!!
        assertFalse(s.resolveFailed(first, retry = true)) // late failure of attempt 1
        assertTrue(s.onResolved(first, realId, listOf("192.168.1.50"), 4000, 0L).isEmpty()) // late answer of attempt 1
        assertTrue(s.isResolving(second))
        assertEquals(1, s.onResolved(second, realId, listOf("192.168.1.50"), 4000, 0L).size)
    }

    @Test
    fun `the resolve queue is bounded and drops the oldest`() {
        val s = DiscoveryState<String>(maxQueuedResolves = 3)
        val gen = s.start()
        for (i in 1..5) s.onFound(gen, "svc-$i", "svc-$i")
        assertEquals("svc-3", s.nextResolve(9)!!.name)
        assertEquals(64, DiscoveryState.MAX_QUEUED_RESOLVES)
    }

    @Test
    fun `an empty or null service name is ignored`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        assertFalse(s.onFound(gen, null, "x"))
        assertFalse(s.onFound(gen, "", "x"))
    }

    // ---- From the code review of #20 ----

    @Test
    fun `a service lost while its resolve is in flight frees its slot, even with one legacy slot`() {
        // Kills "onLost no longer frees the resolve slot" (review mutant M39).
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.onFound(gen, "gone", "gone")
        s.onFound(gen, "waiting", "waiting")
        val inFlight = s.nextResolve(1)!!
        assertEquals("gone", inFlight.name)
        assertNull("the one slot is taken", s.nextResolve(1))
        s.onLost(gen, "gone")
        assertEquals("waiting", s.nextResolve(1)!!.name)
        // The lost service's own late answer changes nothing.
        assertTrue(s.onResolved(inFlight, realId, listOf("192.168.1.50"), 4000, 0L).isEmpty())
    }

    @Test
    fun `a service lost while its probe runs is not left busy and can be probed again`() {
        // Kills "onLost keeps the pending-probe count" (review mutant Z5): the service comes back while the old
        // probe is still running, and must be accepted at once rather than counted as busy.
        val s = DiscoveryState<String>(reprobeIntervalMs = 0)
        val gen = s.start()
        s.announce(gen, "svc", realId)
        val running = s.nextProbe()!!
        s.onLost(gen, "svc")
        assertEquals(1, s.announce(gen, "svc", realId).size)
        assertNull("the old probe's result is discarded", s.onProbeDone(running, health(realId)))
        assertEquals(listOf(realId), s.drain { true }.map { it.id })
    }

    @Test
    fun `a probe of a lost service that finishes late does not count down the service's new batch`() {
        val s = DiscoveryState<String>(reprobeIntervalMs = 0)
        val gen = s.start()
        s.announce(gen, "svc", realId, hosts = listOf("192.168.1.50"))
        val old = s.nextProbe()!!
        s.onLost(gen, "svc")
        // One address in the new batch, so a wrong count-down would empty it.
        assertEquals(1, s.announce(gen, "svc", realId, hosts = listOf("192.168.1.60")).size)
        s.onProbeDone(old, null)
        assertFalse("the new batch is still outstanding", s.onFound(gen, "svc", "svc"))
        assertEquals(1, s.queuedProbeCount())
        assertEquals(listOf("http://192.168.1.60:4000"), s.drain { true }.map { it.url })
    }

    @Test
    fun `a queued probe of a service forgotten by the name cap is skipped, not run`() {
        val s = DiscoveryState<String>(maxTrackedNames = 1)
        val gen = s.start()
        s.announce(gen, "a", fakeId(1), hosts = listOf("192.168.1.10"))
        s.announce(gen, "b", fakeId(2), hosts = listOf("192.168.1.20")) // forgets a
        assertEquals("b", s.nextProbe()!!.name)
        assertNull(s.nextProbe())
        assertTrue("a is not left busy", s.onFound(gen, "a", "a"))
    }

    @Test
    fun `a running probe of a service forgotten by the name cap still counts down, so it is not left busy`() {
        val s = DiscoveryState<String>(maxTrackedNames = 1)
        val gen = s.start()
        s.announce(gen, "a", fakeId(1), hosts = listOf("192.168.1.10"))
        val running = s.nextProbe()!!
        s.announce(gen, "b", fakeId(2), hosts = listOf("192.168.1.20")) // forgets a while its probe runs
        assertNull(s.onProbeDone(running, health(fakeId(1))))
        assertTrue("a can be announced again", s.onFound(gen, "a", "a"))
    }

    @Test
    fun `private addresses are picked before the cap of 8, so public ones listed first cannot push the real one out`() {
        // Kills "cap, then filter" (review mutant M41).
        val s = DiscoveryState<String>()
        val gen = s.start()
        val hosts = (1..8).map { "8.8.8.$it" } + "192.168.1.50"
        assertEquals(listOf("192.168.1.50"), s.announce(gen, "svc", realId, hosts = hosts).map { it.host })
    }

    @Test
    fun `a flood of losses for names never announced records nothing`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        for (i in 1..10_000) assertNull(s.onLost(gen, "invented-$i"))
        assertEquals(0, s.trackedNameCount())
    }

    @Test
    fun `per-name bookkeeping stays within 256 names however many are announced`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        for (i in 1..2_000) {
            s.onFound(gen, "svc-$i", "svc-$i")
            val job = s.nextResolve(1) ?: continue
            s.onResolved(job, fakeId(i), listOf("10.0.${i / 250}.${i % 250 + 1}"), 4000, i.toLong())
            s.drain { false }
        }
        assertTrue("tracked ${s.trackedNameCount()}", s.trackedNameCount() <= 256)
        assertEquals(256, DiscoveryState.MAX_TRACKED_NAMES)
    }

    @Test
    fun `a service forgotten by the name cap gives its resolve slot back and is not left busy`() {
        val s = DiscoveryState<String>(maxTrackedNames = 2)
        val gen = s.start()
        s.onFound(gen, "a", "a")
        val a = s.nextResolve(1)!!
        s.onFound(gen, "b", "b")
        s.onFound(gen, "c", "c") // forgets a, the oldest name
        assertTrue("its answer no longer counts", s.onResolved(a, realId, listOf("192.168.1.50"), 4000, 0L).isEmpty())
        assertEquals("b", s.nextResolve(1)!!.name)
        assertTrue("a can be announced again", s.onFound(gen, "a", "a"))
    }
}
