package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions for a moved saved LAN server. Fixtures use documentation LAN addresses and invented ids. */
class MovedServerRulesTest {
    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val otherId = "3a1b2c4d-5e6f-4789-abcd-0123456789ef"
    private val old = "http://192.168.1.50:4000"
    private val moved = "http://192.168.1.51:4000"
    private val answered = MovedServerRules.Probe.Answered(id)
    private val noAnswer = MovedServerRules.Probe.NoAnswer
    private val week = 7L * 24 * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun `constants pin the background bounds and decline limits`() {
        assertEquals(300_000L, MovedServerRules.CHECK_INTERVAL_MS)
        assertEquals(week, MovedServerRules.DECLINE_LIFETIME_MS)
        assertEquals(8, MovedServerRules.MAX_DECLINES)
        assertEquals(10_000L, MovedServerRules.SEARCH_BOUND_MS)
        assertEquals(2_000L, MovedServerRules.PROBE_TIMEOUT_MS)
    }

    @Test fun `hostname saved skips the move check`() {
        assertEquals(MovedServerRules.Step.Skip, MovedServerRules.decide("http://server.example.test:4000", id, null, noAnswer))
    }

    @Test fun `https saved skips the move check`() {
        assertEquals(MovedServerRules.Step.Skip, MovedServerRules.decide("https://192.168.1.50:4000", id, null, noAnswer))
    }

    @Test fun `public IP saved skips the move check`() {
        assertEquals(MovedServerRules.Step.Skip, MovedServerRules.decide("http://8.8.8.8:4000", id, null, noAnswer))
    }

    @Test fun `malformed saved URL skips the move check`() {
        assertEquals(MovedServerRules.Step.Skip, MovedServerRules.decide("$old/", id, null, noAnswer))
        assertEquals(MovedServerRules.Step.Skip, MovedServerRules.decide(null, id, null, noAnswer))
    }

    @Test fun `answered without an id stops`() {
        assertEquals(MovedServerRules.Step.Stop, MovedServerRules.decide(old, null, id, MovedServerRules.Probe.Answered(null)))
    }

    @Test fun `stored null and answered id learns the id`() {
        assertEquals(MovedServerRules.Step.Learn(id), MovedServerRules.decide(old, null, null, answered))
    }

    @Test fun `implausible answered id is not learned`() {
        assertEquals(MovedServerRules.Step.Stop, MovedServerRules.decide(old, null, null, MovedServerRules.Probe.Answered("wrong")))
    }

    @Test fun `stored id equal to answered id stops`() {
        assertEquals(MovedServerRules.Step.Stop, MovedServerRules.decide(old, id, null, answered))
    }

    @Test fun `stored id different from answered id searches and never learns a replacement`() {
        assertEquals(MovedServerRules.Step.Search(otherId), MovedServerRules.decide(old, otherId, null, answered))
    }

    @Test fun `pair only with a different answer searches for the stored pair id`() {
        assertEquals(MovedServerRules.Step.Search(otherId), MovedServerRules.decide(old, null, otherId, answered))
    }

    @Test fun `pair only with the same answer learns its id`() {
        assertEquals(MovedServerRules.Step.Learn(id), MovedServerRules.decide(old, null, id, answered))
    }

    @Test fun `no answer with stored id searches for it`() {
        assertEquals(MovedServerRules.Step.Search(id), MovedServerRules.decide(old, id, null, noAnswer))
    }

    @Test fun `no answer without stored id uses the stored pair id`() {
        assertEquals(MovedServerRules.Step.Search(id), MovedServerRules.decide(old, null, id, noAnswer))
    }

    @Test fun `no answer without either id stops`() {
        assertEquals(MovedServerRules.Step.Stop, MovedServerRules.decide(old, null, null, noAnswer))
    }

    @Test fun `stored id wins over pair id`() {
        assertEquals(MovedServerRules.Step.Search(id), MovedServerRules.decide(old, id, otherId, noAnswer))
    }

    @Test fun `same id at a different private URL is a candidate`() {
        assertTrue(MovedServerRules.acceptCandidate(id, old, id, moved))
    }

    @Test fun `same URL is not a moved candidate`() {
        assertFalse(MovedServerRules.acceptCandidate(id, old, id, old))
    }

    @Test fun `different id is not a candidate`() {
        assertFalse(MovedServerRules.acceptCandidate(id, old, otherId, moved))
        assertFalse(MovedServerRules.acceptCandidate(id, old, id.uppercase(), moved))
    }

    @Test fun `non private URL is not a candidate`() {
        assertFalse(MovedServerRules.acceptCandidate(id, old, id, "http://8.8.8.8:4000"))
    }

    @Test fun `malformed URL is not a candidate`() {
        assertFalse(MovedServerRules.acceptCandidate(id, old, id, "$moved/"))
        assertFalse(MovedServerRules.acceptCandidate(id, old, id, "http://192.168.001.51:4000"))
    }

    @Test fun `implausible id is not a candidate even when equal`() {
        assertFalse(MovedServerRules.acceptCandidate("wrong", old, "wrong", moved))
    }

    @Test fun `prompt includes both addresses offered name code and recognition line`() {
        val body = MovedServerRules.promptMessage(old, moved, "Demo Server", "Demo Server", DiscoveryRules.fingerprint(id))
        assertTrue(body.contains(old))
        assertTrue(body.contains(moved))
        assertTrue(body.contains("Demo Server"))
        assertTrue(body.contains("4f1c2b9e"))
        assertTrue(body.contains("Only use it if you recognise this address (check your server or router)."))
        assertFalse(body.contains("The name is different"))
        assertEquals("Your server has a new address", MovedServerRules.PROMPT_TITLE)
        assertEquals("Not now", MovedServerRules.NOT_NOW_BUTTON)
        assertEquals("Use new address", MovedServerRules.USE_NEW_ADDRESS_BUTTON)
    }

    @Test fun `prompt calls out a different stored name only when it is nonempty`() {
        val different = MovedServerRules.promptMessage(old, moved, "New Name", "Old Name", "4f1c2b9e")
        assertTrue(different.contains("The name is different from the one you saved (\"Old Name\")."))
        assertFalse(MovedServerRules.promptMessage(old, moved, "New Name", "", "4f1c2b9e").contains("The name is different"))
        assertFalse(MovedServerRules.promptMessage(old, moved, "New Name", null, "4f1c2b9e").contains("The name is different"))
        assertTrue(MovedServerRules.promptMessage(old, moved, null, "Old Name", "4f1c2b9e")
            .contains("The name is different from the one you saved (\"Old Name\")."))
    }

    @Test fun `null or empty offered name displays Nightlight`() {
        assertTrue(MovedServerRules.promptMessage(old, moved, null, null, "4f1c2b9e").contains("Name: Nightlight"))
        assertTrue(MovedServerRules.promptMessage(old, moved, "", null, "4f1c2b9e").contains("Name: Nightlight"))
    }

    @Test fun `move plan writes new data before saved URL and removes old last`() {
        val plan = MovedServerRules.movePlan(old, moved, id, "Demo Server", listOf(old), false, "https://server.example.invalid", id)
        assertEquals(listOf(
            MovedServerRules.MoveStep.AddKnown(moved), MovedServerRules.MoveStep.SetName(moved, "Demo Server"),
            MovedServerRules.MoveStep.SetServerId(moved, id), MovedServerRules.MoveStep.CarryPair(moved, "https://server.example.invalid", id),
            MovedServerRules.MoveStep.SetSavedUrl(moved), MovedServerRules.MoveStep.RemoveOld(old),
        ), plan)
    }

    @Test fun `carry pair is absent without pair URL or when pair id differs`() {
        for ((pairUrl, pairId) in listOf(null to id, "https://server.example.invalid" to otherId, "https://server.example.invalid" to null)) {
            val plan = MovedServerRules.movePlan(old, moved, id, "Demo", listOf(old), false, pairUrl, pairId)
            assertFalse(plan.toString(), plan.any { it is MovedServerRules.MoveStep.CarryPair })
        }
    }

    @Test fun `empty name omits set name and remembered destination is cleared then added once`() {
        val plan = MovedServerRules.movePlan(old, moved, id, "", listOf(old, moved), false, null, null)
        assertEquals(listOf(MovedServerRules.MoveStep.ClearNew(moved), MovedServerRules.MoveStep.AddKnown(moved),
            MovedServerRules.MoveStep.SetServerId(moved, id),
            MovedServerRules.MoveStep.SetSavedUrl(moved), MovedServerRules.MoveStep.RemoveOld(old)), plan)
        assertFalse(MovedServerRules.movePlan(old, moved, id, null, listOf(old), false, null, null)
            .any { it is MovedServerRules.MoveStep.SetName })
    }

    @Test fun `remembered destination starts with clear then add`() {
        val plan = MovedServerRules.movePlan(old, moved, id, "Demo", listOf(old, moved), false, null, null)
        assertEquals(listOf(MovedServerRules.MoveStep.ClearNew(moved), MovedServerRules.MoveStep.AddKnown(moved)),
            plan.take(2))
    }

    @Test fun `fresh destination has no clear step but leftover data requires one`() {
        val fresh = MovedServerRules.movePlan(old, moved, id, null, listOf(old), false, null, null)
        assertFalse(fresh.any { it is MovedServerRules.MoveStep.ClearNew })
        val leftover = MovedServerRules.movePlan(old, moved, id, null, listOf(old), true, null, null)
        assertEquals(MovedServerRules.MoveStep.ClearNew(moved), leftover.first())
    }

    @Test fun `move guard requires both the old URL and expected id`() {
        val guard = MovedServerRules.MoveGuard
        assertTrue(guard.stillValid(old, id, old, id))
        assertFalse(guard.stillValid(moved, id, old, id))
        assertFalse(guard.stillValid(old, otherId, old, id))
        assertFalse(guard.stillValid(moved, otherId, old, id))
        assertFalse(guard.stillValid(old, null, old, id))
    }

    @Test fun `throttle runs first check and again at five minutes exactly`() {
        val throttle = MovedServerRules.Throttle
        assertTrue(throttle.mayRun(now, null))
        assertFalse(throttle.mayRun(now, now - 300_000L + 1))
        assertTrue(throttle.mayRun(now, now - 300_000L))
        assertFalse(throttle.mayRun(now, now + 1))
    }

    @Test fun `launch refusal skips the throttle once inside the interval`() {
        val lastRun = now - 1_000L
        assertTrue(MovedServerRules.Throttle.mayRun(now, lastRun, skipOnce = true))
        assertFalse(MovedServerRules.Throttle.mayRun(now, lastRun, skipOnce = false))
    }

    @Test fun `launch refusal also permits a first check without a previous run`() {
        assertTrue(MovedServerRules.Throttle.mayRun(now, null, skipOnce = true))
    }

    @Test fun `decline applies before seven days and expires exactly at seven days`() {
        val map = mapOf(moved to (now - week + 1))
        assertTrue(MovedServerRules.Declines.isDeclined(map, moved, now))
        assertFalse(MovedServerRules.Declines.isDeclined(mapOf(moved to (now - week)), moved, now))
        assertFalse(MovedServerRules.Declines.isDeclined(map, old, now))
        assertFalse(MovedServerRules.Declines.isDeclined(mapOf(moved to (now + 1)), moved, now))
    }

    @Test fun `record keeps the eight newest and drops the oldest of nine`() {
        val urls = (51..59).map { "http://192.168.1.$it:4000" }
        var map: Map<String, Long> = emptyMap()
        for ((index, url) in urls.withIndex()) map = MovedServerRules.Declines.record(map, url, now + index)
        assertEquals(8, map.size)
        assertFalse(map.containsKey(urls.first()))
        assertTrue(map.containsKey(urls.last()))
    }

    @Test fun `record prunes expired and future dated declines`() {
        val map = MovedServerRules.Declines.record(
            mapOf(old to (now - week), "http://192.168.1.52:4000" to (now + 1)), moved, now)
        assertEquals(mapOf(moved to now), map)
    }

    @Test fun `decode of garbage fails closed to an empty map`() {
        for (bad in listOf(null, "", "garbage", "v2", "v1\n", "v1\n$moved\tnope",
            "v1\nhttp://8.8.8.8:4000\t1", "v1\n$moved\t1\n$moved\t2", "v1\n$moved\t-1")) {
            assertTrue("bad value $bad", MovedServerRules.Declines.decode(bad).isEmpty())
        }
    }

    @Test fun `encode decode round trip is stable regardless of map order`() {
        val map = mapOf(moved to now, old to (now - 100))
        val encoded = MovedServerRules.Declines.encode(map)
        assertEquals(map, MovedServerRules.Declines.decode(encoded))
        assertEquals(encoded, MovedServerRules.Declines.encode(map.toList().reversed().toMap()))
        assertEquals(emptyMap<String, Long>(), MovedServerRules.Declines.decode(MovedServerRules.Declines.encode(emptyMap())))
    }
}
