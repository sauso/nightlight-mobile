package com.sauso.nightlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id check `ServerConfig.save({ url, expectId })` makes before saving a server that discovery found
 * (DiscoveryRules.healthMatchesExpected). Why it exists (plan review of #20, High): without expectId, save accepts
 * any 200 reply that contains "ok", so an address that changed hands between the search and the tap could be saved.
 *
 * Only the pure check is tested here: the plugin method itself needs a Capacitor bridge, and this repo has no
 * instrumented-test harness yet. That the plugin calls this check, and only when expectId is given, is read in
 * review, not tested.
 */
class SaveExpectIdTest {

    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val reply = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"$id\",\"name\":\"Home\"}"

    @Test
    fun `the server we found is accepted`() {
        assertTrue(DiscoveryRules.healthMatchesExpected(reply, id))
    }

    @Test
    fun `another Nightlight at that address is refused`() {
        assertFalse(DiscoveryRules.healthMatchesExpected(reply, "00000000-1111-2222-3333-444444444444"))
    }

    @Test
    fun `a reply without an id is refused, including the old ok-only reply`() {
        assertFalse(DiscoveryRules.healthMatchesExpected("{\"ok\":true,\"app\":\"nightlight\"}", id))
        assertFalse(DiscoveryRules.healthMatchesExpected("{\"ok\":true}", id))
    }

    @Test
    fun `ok false is refused even with the right id`() {
        assertFalse(DiscoveryRules.healthMatchesExpected("{\"ok\":false,\"app\":\"nightlight\",\"id\":\"$id\"}", id))
    }

    @Test
    fun `a different app is refused even with the right id`() {
        assertFalse(DiscoveryRules.healthMatchesExpected("{\"ok\":true,\"app\":\"other\",\"id\":\"$id\"}", id))
    }

    @Test
    fun `a body that merely contains ok and the id is refused`() {
        // What the old check (`body.contains("\"ok\"")`) would have accepted.
        assertFalse(DiscoveryRules.healthMatchesExpected("<p>\"ok\" $id</p>", id))
    }

    @Test
    fun `an empty or malformed expectId never matches`() {
        assertFalse(DiscoveryRules.healthMatchesExpected(reply, ""))
        assertFalse(DiscoveryRules.healthMatchesExpected(reply, "4f1c2b9e"))
        assertFalse(DiscoveryRules.healthMatchesExpected(reply, null))
        assertFalse(DiscoveryRules.healthMatchesExpected(null, id))
    }
}
