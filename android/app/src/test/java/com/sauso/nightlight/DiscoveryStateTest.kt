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
    fun `a service is probed again at most once per 10 seconds`() {
        val s = DiscoveryState<String>()
        val gen = s.start()
        s.announce(gen, "svc", realId, now = 1_000L)
        s.drain { false }
        // It goes and comes back within 10 s: not probed again.
        s.onLost(gen, "svc")
        assertTrue(s.announce(gen, "svc", realId, now = 10_999L).isEmpty())
        // After 10 s it is.
        s.onLost(gen, "svc")
        assertEquals(1, s.announce(gen, "svc", realId, now = 11_000L).size)
        assertEquals(10_000L, DiscoveryState.REPROBE_INTERVAL_MS)
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
            assertTrue(s.resolveFailed(job, retry = true))
        }
        assertNull("no fourth attempt", s.nextResolve(1))
        assertEquals(3, DiscoveryState.MAX_RESOLVE_ATTEMPTS)
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
        assertTrue(s.resolveFailed(first, retry = true)) // timeout, retried
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
}
