package com.sauso.nightlight

import com.sauso.nightlight.HealthProbe.Outcome
import com.sauso.nightlight.LocalHttpServer.Companion.ascii
import com.sauso.nightlight.LocalHttpServer.Companion.reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The check `ServerConfig.save({ url, expectId })` makes before saving a server that discovery found:
 * HealthProbe.checkUrl. Why it exists (plan review of #20, High): without expectId, save accepts any 200 reply that
 * contains "ok", so an address that changed hands between the search and the tap could be saved. Why it is the probe
 * and not HttpURLConnection (code review of #20): HttpURLConnection follows redirects, so a redirect to the real
 * server could pass while the original address was saved.
 *
 * Tested against real sockets. The address policy is opened to the test's own 127.0.0.1 only. What is NOT tested
 * (there is no instrumented-test harness yet): that the plugin calls this check when, and only when, expectId is
 * given, and maps its outcome to the right message; that is read in review.
 */
class SaveExpectIdTest {

    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val other = "00000000-1111-2222-3333-444444444444"
    private val replyBody = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"$id\",\"name\":\"Home\"}"
    private fun localProbe() = HealthProbe(addressPolicy = { it == "127.0.0.1" })
    private fun urlOf(server: LocalHttpServer) = "http://127.0.0.1:${server.port}"

    private fun outcomeFor(body: String, expectId: String = id): Outcome =
        LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
            localProbe().checkUrl(urlOf(server), expectId).outcome
        }

    @Test
    fun `the server we found is accepted`() {
        assertEquals(Outcome.VERIFIED, outcomeFor(replyBody))
    }

    @Test
    fun `another Nightlight at that address is refused`() {
        assertEquals(Outcome.ID_MISMATCH, outcomeFor(replyBody, expectId = other))
    }

    @Test
    fun `a reply without an id is refused, including the old ok-only reply`() {
        assertEquals(Outcome.NOT_NIGHTLIGHT, outcomeFor("{\"ok\":true,\"app\":\"nightlight\"}"))
        assertEquals(Outcome.NOT_NIGHTLIGHT, outcomeFor("{\"ok\":true}"))
    }

    @Test
    fun `ok false or a different app is refused even with the right id`() {
        assertEquals(Outcome.NOT_NIGHTLIGHT, outcomeFor("{\"ok\":false,\"app\":\"nightlight\",\"id\":\"$id\"}"))
        assertEquals(Outcome.NOT_NIGHTLIGHT, outcomeFor("{\"ok\":true,\"app\":\"other\",\"id\":\"$id\"}"))
    }

    @Test
    fun `a body that merely contains ok and the id is refused`() {
        // What the typed-address check (`body.contains("\"ok\"")`) would have accepted.
        assertEquals(Outcome.NOT_NIGHTLIGHT, outcomeFor("<p>\"ok\" $id</p>"))
    }

    @Test
    fun `a redirect to the real server is not followed, so the original address is not saved`() {
        // Code review of #20: the real server (b) answers correctly; the tapped address (a) only redirects to it.
        // HttpURLConnection would follow and pass the id check while saving a's address. b must see nothing.
        LocalHttpServer { out, _ -> reply(out, "200 OK", replyBody) }.use { b ->
            LocalHttpServer { out, _ ->
                out.write(ascii("HTTP/1.1 307 Temporary Redirect\r\nLocation: ${urlOf(b)}/api/health\r\nContent-Length: 0\r\n\r\n"))
                out.flush()
            }.use { a ->
                val result = localProbe().checkUrl(urlOf(a), id)
                assertEquals(Outcome.NOT_OK_STATUS, result.outcome)
                assertNull(result.health)
                Thread.sleep(200)
                assertEquals("the redirect target was contacted", 0, b.hits.get())
            }
        }
    }

    @Test
    fun `an unreachable address is reported as unreachable, not as another server`() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        assertEquals(Outcome.CONNECT_FAILED, localProbe().checkUrl("http://127.0.0.1:$port", id).outcome)
    }

    @Test
    fun `anything but the exact found-server URL form is refused without a request`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", replyBody) }.use { server ->
            val p = server.port
            for (url in listOf(
                "https://127.0.0.1:$p", "http://127.0.0.1:$p/", "http://127.0.0.1:$p/api", "http://127.0.0.1",
                "http://user@127.0.0.1:$p", "http://localhost:$p", "http://127.0.0.1:0$p", "127.0.0.1:$p",
                "http://127.0.0.1:$p?x=1", " http://127.0.0.1:$p", "", "http://127.0.0.1:99999",
            )) {
                assertEquals(url, Outcome.REFUSED_ADDRESS, localProbe().checkUrl(url, id).outcome)
            }
            assertEquals(Outcome.REFUSED_ADDRESS, localProbe().checkUrl(null, id).outcome)
            Thread.sleep(100)
            assertEquals(0, server.hits.get())
        }
    }

    @Test
    fun `the production policy refuses a public or loopback found-server URL`() {
        assertEquals(Outcome.REFUSED_ADDRESS, HealthProbe().checkUrl("http://127.0.0.1:4000", id).outcome)
        assertEquals(Outcome.REFUSED_ADDRESS, HealthProbe().checkUrl("http://8.8.8.8:4000", id).outcome)
    }

    @Test
    fun `parseFoundUrl reads exactly what serverUrl writes`() {
        assertEquals(Pair("192.168.1.50", 4000), DiscoveryRules.parseFoundUrl(DiscoveryRules.serverUrl("192.168.1.50", 4000)))
        assertEquals(Pair("10.0.0.1", 1), DiscoveryRules.parseFoundUrl("http://10.0.0.1:1"))
        assertEquals(Pair("10.0.0.1", 65535), DiscoveryRules.parseFoundUrl("http://10.0.0.1:65535"))
        // "04000" is five digits and 4000 as a number: only the exact-form check refuses it.
        for (url in listOf("http://10.0.0.1:04000", "http://10.0.0.1:0", "http://10.0.0.1:65536", "http://010.0.0.1:4000", "http://10.0.0.1:+4000",
            "http://10.0.0.1:4000 ", "HTTP://10.0.0.1:4000", "http://[::1]:4000", "http://10.0.0.1:")) {
            assertNull(url, DiscoveryRules.parseFoundUrl(url))
        }
    }
}
