package com.sauso.nightlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The pure rules behind "Look for my Nightlight" (#20). Everything these functions see came from some device on the
 * LAN, so the fixtures are hostile: each test names the mutation it is there to kill.
 */
class DiscoveryRulesTest {

    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"

    private fun healthJson(
        ok: String = "true",
        app: String = "\"nightlight\"",
        idValue: String = "\"$id\"",
        name: String? = "\"Nursery\"",
    ): String {
        val nameField = if (name == null) "" else ",\"name\":$name"
        return "{\"ok\":$ok,\"app\":$app,\"id\":$idValue$nameField}"
    }

    // ---- isPrivateIpv4: the RFC 1918 allowlist, edges both sides of every range ----

    @Test
    fun `accepts the edges of each private range`() {
        for (host in listOf(
            "10.0.0.0", "10.255.255.255", "10.1.2.3",
            "172.16.0.0", "172.31.255.255", "172.20.10.5",
            "192.168.0.0", "192.168.255.255", "192.168.1.50",
        )) {
            assertTrue(host, DiscoveryRules.isPrivateIpv4(host))
        }
    }

    @Test
    fun `refuses the addresses just outside each private range`() {
        // Kills a widened range: 9.x and 11.x around 10/8, 172.15 and 172.32 around 172.16/12, 192.167 and
        // 192.169 around 192.168/16.
        for (host in listOf(
            "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0", "193.168.1.1", "191.168.1.1",
        )) {
            assertFalse(host, DiscoveryRules.isPrivateIpv4(host))
        }
    }

    @Test
    fun `refuses loopback, link-local, carrier-grade NAT (Tailscale), public and special addresses`() {
        for (host in listOf(
            "127.0.0.1", "127.255.255.254",
            "169.254.1.1",
            "100.64.0.1", "100.100.100.100", "100.127.255.254", // 100.64/10 includes Tailscale's addresses
            "8.8.8.8", "1.1.1.1",
            "0.0.0.0", "255.255.255.255",
            "224.0.0.251", // the mDNS multicast group itself
        )) {
            assertFalse(host, DiscoveryRules.isPrivateIpv4(host))
        }
    }

    @Test
    fun `refuses leading zeros, signs, whitespace and other non-canonical forms`() {
        // Kills dropping the leading-zero check (some parsers read 010 as octal 8) and the digits-only check.
        for (host in listOf(
            "010.0.0.1", "192.168.001.001", "192.168.01.1", "00.0.0.0",
            "+10.0.0.1", "-10.0.0.1", "10.0.0.-1",
            " 10.0.0.1", "10.0.0.1 ", "10.0.0. 1", "10.0.0.1\n", "\t192.168.1.1",
            "10.0.0", "10.0.0.1.", ".10.0.0.1", "10..0.1", "10.0.0.1.2",
            "10.0.0.256", "192.168.1.999", "1e1.0.0.1", "0x0a.0.0.1",
            "١٠.0.0.1", // Arabic-Indic digits pass Char.isDigit; they must not pass here
        )) {
            assertFalse(host, DiscoveryRules.isPrivateIpv4(host))
        }
    }

    @Test
    fun `refuses IPv6 (including IPv4-mapped), hostnames, lookalike hostnames, empty and null`() {
        for (host in listOf(
            "::ffff:192.168.1.1", "[::ffff:192.168.1.1]", "fe80::1", "fd00::1", "::1",
            "nightlight.local", "localhost", "192.168.1.1.evil.example", "192.168.1.1:4000",
            "",
        )) {
            assertFalse(host, DiscoveryRules.isPrivateIpv4(host))
        }
        assertFalse(DiscoveryRules.isPrivateIpv4(null))
    }

    @Test
    fun `parseIpv4 returns the octets of a canonical literal`() {
        assertEquals(listOf(192, 168, 1, 50), DiscoveryRules.parseIpv4("192.168.1.50")!!.toList())
        assertEquals(listOf(0, 0, 0, 0), DiscoveryRules.parseIpv4("0.0.0.0")!!.toList())
        assertNull(DiscoveryRules.parseIpv4("192.168.1"))
    }

    // ---- parseHealth: one parser, fail closed ----

    @Test
    fun `a valid health reply parses`() {
        val h = DiscoveryRules.parseHealth(healthJson())
        assertEquals(DiscoveryRules.Health(id, "Nursery"), h)
    }

    @Test
    fun `extra unknown fields are allowed`() {
        val body = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"$id\",\"name\":\"Home\",\"version\":\"9.9\",\"extra\":{\"a\":[1,2]}}"
        assertEquals(DiscoveryRules.Health(id, "Home"), DiscoveryRules.parseHealth(body))
    }

    @Test
    fun `ok must be the boolean true`() {
        for (ok in listOf("false", "\"true\"", "1", "null", "{}")) {
            assertNull(ok, DiscoveryRules.parseHealth(healthJson(ok = ok)))
        }
        assertNull(DiscoveryRules.parseHealth("{\"app\":\"nightlight\",\"id\":\"$id\"}"))
    }

    @Test
    fun `app must be exactly nightlight`() {
        for (app in listOf("\"Nightlight\"", "\"nightlight \"", "\"other\"", "\"\"", "null", "1", "true", "[\"nightlight\"]")) {
            assertNull(app, DiscoveryRules.parseHealth(healthJson(app = app)))
        }
        assertNull(DiscoveryRules.parseHealth("{\"ok\":true,\"id\":\"$id\"}"))
    }

    @Test
    fun `id must be a non-empty string`() {
        for (value in listOf("\"\"", "null", "42", "true", "[\"$id\"]", "{\"id\":\"$id\"}")) {
            assertNull(value, DiscoveryRules.parseHealth(healthJson(idValue = value)))
        }
        assertNull(DiscoveryRules.parseHealth("{\"ok\":true,\"app\":\"nightlight\"}"))
    }

    @Test
    fun `the old ok-only reply is not a Nightlight we can verify`() {
        assertNull(DiscoveryRules.parseHealth("{\"ok\":true}"))
    }

    @Test
    fun `non-objects, nesting, truncation and empty bodies are refused`() {
        for (body in listOf(
            "[" + healthJson() + "]",
            "null",
            "true",
            "\"" + id + "\"",
            "{\"health\":" + healthJson() + "}",
            healthJson().dropLast(1),
            "{\"ok\":true,\"app\":\"nightl",
            "",
            "   ",
            "<html><body>ok</body></html>",
        )) {
            assertNull(body, DiscoveryRules.parseHealth(body))
        }
        assertNull(DiscoveryRules.parseHealth(null))
    }

    @Test
    fun `a deeply nested body is refused, not a crash`() {
        // Android's own org.json (what the phone runs) parses arrays recursively with no depth limit, so this
        // overflows the stack there, and the catch must turn that into "refused". The org.json library the CI
        // tests use stops at a nesting limit with a JSONException instead; the result must be the same either way.
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertNull(DiscoveryRules.parseHealth(deep))
        assertNull(DiscoveryRules.parseHealth("{\"x\":$deep}"))
    }

    @Test
    fun `name is optional, and null or absent means no name`() {
        assertEquals(DiscoveryRules.Health(id, null), DiscoveryRules.parseHealth(healthJson(name = null)))
        assertEquals(DiscoveryRules.Health(id, null), DiscoveryRules.parseHealth(healthJson(name = "null")))
    }

    @Test
    fun `a name of the wrong type refuses the whole reply`() {
        for (name in listOf("42", "true", "[\"Home\"]", "{\"n\":\"Home\"}")) {
            assertNull(name, DiscoveryRules.parseHealth(healthJson(name = name)))
        }
    }

    @Test
    fun `control and invisible characters are stripped from the name, then it is trimmed`() {
        // \u0007 bell, \u001b escape, \u007f delete, \u0085 next-line (C1), \u202e right-to-left override (would
        // make the name read backwards), \u200b zero-width space, \u2028 line separator.
        val raw = "\"  Nu\\u0007rs\\u001bery\\u007f\\u0085\\u202e\\u200b\\u2028 \""
        assertEquals("Nursery", DiscoveryRules.parseHealth(healthJson(name = raw))!!.name)
    }

    @Test
    fun `a name that is only invisible characters means no name`() {
        assertNull(DiscoveryRules.parseHealth(healthJson(name = "\"\\u0000\\u202e \\t\""))!!.name)
        assertNull(DiscoveryRules.parseHealth(healthJson(name = "\"\""))!!.name)
    }

    @Test
    fun `characters that render blank are dropped, and a name of only them means no name`() {
        // U+3164 Hangul filler, U+2800 blank Braille, U+FFA0 halfwidth Hangul filler (code review of #20).
        assertEquals("Home", DiscoveryRules.parseHealth(healthJson(name = "\"Ho\\u3164me\\u2800\\uFFA0\""))!!.name)
        assertNull(DiscoveryRules.parseHealth(healthJson(name = "\"\\u3164\\u2800\\uFFA0\\u115F\\u1160\""))!!.name)
    }

    @Test
    fun `a run of combining marks is cut to 3`() {
        // "e" with five combining acute accents keeps three; separate letters keep their own marks.
        val name = DiscoveryRules.parseHealth(healthJson(name = "\"e\\u0301\\u0301\\u0301\\u0301\\u0301x\\u0301\""))!!.name!!
        assertEquals("é́́x́", name)
    }

    @Test
    fun `a 10 000-character name is cut to 64 characters`() {
        val long = "N".repeat(10_000)
        val name = DiscoveryRules.parseHealth(healthJson(name = "\"$long\""))!!.name!!
        assertEquals(DiscoveryRules.MAX_NAME_CHARS, name.length)
        assertEquals(64, DiscoveryRules.MAX_NAME_CHARS)
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        // 70 moons (U+1F319, two UTF-16 units each): 64 code points survive, 128 chars, no lone surrogate.
        val moons = "\uD83C\uDF19".repeat(70)
        val name = DiscoveryRules.parseHealth(healthJson(name = "\"$moons\""))!!.name!!
        assertEquals(64, name.codePointCount(0, name.length))
        assertEquals(128, name.length)
        assertTrue(Character.isLowSurrogate(name.last()))
    }

    @Test
    fun `a name exactly 64 characters long is kept whole`() {
        val exact = "A".repeat(63) + "Z"
        assertEquals(exact, DiscoveryRules.parseHealth(healthJson(name = "\"$exact\""))!!.name)
    }

    // ---- matches and the save-time check ----

    @Test
    fun `matches needs the same id as the announcement`() {
        val h = DiscoveryRules.Health(id, "Home")
        assertTrue(DiscoveryRules.matches(h, id))
        assertFalse(DiscoveryRules.matches(h, "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3d"))
        assertFalse("case differs: not the same string the server sent", DiscoveryRules.matches(h, id.uppercase()))
        assertFalse(DiscoveryRules.matches(h, null))
        assertFalse(DiscoveryRules.matches(null, id))
    }

    @Test
    fun `matches refuses a txt id that is not UUID-shaped even when equal`() {
        val odd = "not-a-uuid"
        assertFalse(DiscoveryRules.matches(DiscoveryRules.Health(odd, null), odd))
        assertFalse(DiscoveryRules.matches(DiscoveryRules.Health("", null), ""))
    }

    // ---- fingerprint ----

    @Test
    fun `fingerprint is the first 8 lower-case letters and digits, like the server's instance name`() {
        assertEquals("4f1c2b9e", DiscoveryRules.fingerprint(id))
        assertEquals("abcdef12", DiscoveryRules.fingerprint("ABCDEF12-3456-7890-abcd-ef0123456789"))
        assertEquals("abcdefgh", DiscoveryRules.fingerprint("a-b-c-d-e-f-g-h-i-j"))
        assertEquals("12", DiscoveryRules.fingerprint("12"))
        assertEquals("", DiscoveryRules.fingerprint("!!!---"))
        assertEquals("", DiscoveryRules.fingerprint(""))
        assertEquals("", DiscoveryRules.fingerprint(null))
        // Non-ASCII letters are dropped, as the server's /[^a-z0-9]/ does.
        assertEquals("bc123456", DiscoveryRules.fingerprint("ÄBC12345678"))
        assertEquals(8, DiscoveryRules.FINGERPRINT_CHARS)
    }

    // ---- txtId: the TXT record, read only from a resolved service ----

    private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.associate { (k, v) -> k to v?.toByteArray(StandardCharsets.UTF_8) }

    @Test
    fun `txtId reads a v=1 record`() {
        assertEquals(id, DiscoveryRules.txtId(txt("v" to "1", "id" to id)))
    }

    @Test
    fun `txtId reads keys in any case and tolerates a missing v`() {
        assertEquals(id, DiscoveryRules.txtId(txt("V" to "1", "ID" to id)))
        assertEquals(id, DiscoveryRules.txtId(txt("id" to id)))
    }

    @Test
    fun `txtId ignores a record format it does not know`() {
        assertNull(DiscoveryRules.txtId(txt("v" to "2", "id" to id)))
        assertNull(DiscoveryRules.txtId(txt("v" to null, "id" to id)))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "V" to "1", "id" to id)))
    }

    @Test
    fun `txtId needs exactly one UUID-shaped id`() {
        assertNull(DiscoveryRules.txtId(txt("v" to "1")))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "id" to null)))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "id" to "")))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "id" to "hello")))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "id" to " $id")))
        assertNull(DiscoveryRules.txtId(txt("v" to "1", "id" to "$id-extra")))
        assertNull(DiscoveryRules.txtId(txt("id" to id, "ID" to "00000000-0000-0000-0000-000000000000")))
        assertNull(DiscoveryRules.txtId(null))
        assertNull(DiscoveryRules.txtId(emptyMap()))
    }

    @Test
    fun `isPlausibleId accepts a UUID in either case and nothing else`() {
        assertTrue(DiscoveryRules.isPlausibleId(id))
        assertTrue(DiscoveryRules.isPlausibleId(id.uppercase()))
        assertFalse(DiscoveryRules.isPlausibleId("4f1c2b9e7a3d4e219c5b6d8e0f1a2b3c"))
        assertFalse(DiscoveryRules.isPlausibleId("g763c3b6-1f2e-4d5c-9b8a-0123456789ab"))
        assertFalse(DiscoveryRules.isPlausibleId("$id\n"))
        assertFalse(DiscoveryRules.isPlausibleId(null))
    }

    // ---- isBundledPage: the gate for startDiscovery / stopDiscovery (plan decision 3a) ----

    @Test
    fun `the bundled setup page is allowed`() {
        assertTrue(DiscoveryRules.isBundledPage("https://localhost/", null, "https", "localhost"))
        assertTrue(DiscoveryRules.isBundledPage("https://localhost/index.html", null, "https", "localhost"))
        assertTrue(DiscoveryRules.isBundledPage("https://localhost", "", "https", "localhost"))
        assertTrue(DiscoveryRules.isBundledPage("HTTPS://LOCALHOST/", null, "https", "localhost"))
    }

    @Test
    fun `a loaded server's page is refused, even if its URL looked local`() {
        // A configured server URL means the bridge is showing a server's web app: refused whatever the URL says.
        assertFalse(DiscoveryRules.isBundledPage("http://192.168.1.50:4000/", "http://192.168.1.50:4000", "https", "localhost"))
        assertFalse(DiscoveryRules.isBundledPage("https://localhost/", "https://nightlight.example.com", "https", "localhost"))
    }

    @Test
    fun `other origins and lookalikes are refused`() {
        for (url in listOf(
            "http://localhost/", // scheme differs
            "https://localhost:8443/", // port
            "https://user@localhost/", // user info
            "https://localhost.evil.example/",
            "https://evil.example/localhost",
            "https://evil.example/?u=https://localhost/",
            "about:blank",
            "data:text/html,hi",
            "file:///android_asset/index.html",
            "not a url",
            "",
        )) {
            assertFalse(url, DiscoveryRules.isBundledPage(url, null, "https", "localhost"))
        }
        assertFalse(DiscoveryRules.isBundledPage(null, null, "https", "localhost"))
        assertFalse(DiscoveryRules.isBundledPage("https://localhost/", null, null, "localhost"))
        assertFalse(DiscoveryRules.isBundledPage("https://localhost/", null, "https", null))
    }

    @Test
    fun `serverUrl is explicit http with the port`() {
        assertEquals("http://192.168.1.50:4000", DiscoveryRules.serverUrl("192.168.1.50", 4000))
    }

}
