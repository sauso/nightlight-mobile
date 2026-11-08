package com.sauso.nightlight

import com.sauso.nightlight.LocalHttpServer.Companion.reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EndpointProbe against a real local server: the rules that make "is this Tailscale address the same server" safe are
 * network rules (no redirect is followed, the body is bounded), so they are tested by making requests.
 */
class EndpointProbeTest {

    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val validBody = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"$id\",\"name\":\"Nursery\"}"

    private fun url(server: LocalHttpServer) = "http://127.0.0.1:${server.port}"

    @Test
    fun `a healthy server answers with its id and name`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { server ->
            val health = EndpointProbe.fetchHealth(url(server), 2_000)
            assertNotNull(health)
            assertEquals(id, health!!.id)
            assertEquals("Nursery", health.name)
            assertTrue("the request goes to /api/health", server.requests.first().startsWith("GET /api/health "))
        }
    }

    @Test
    fun `a redirect is not followed, so the 200 must come from that address itself`() {
        LocalHttpServer { out, request ->
            if (request.contains("/api/health")) reply(out, "302 Found", "", "Location: /other\r\n") else reply(out, "200 OK", validBody)
        }.use { server ->
            assertNull(EndpointProbe.fetchHealth(url(server), 2_000))
            Thread.sleep(150)
            assertEquals("the redirect target was requested", 1, server.hits.get())
        }
    }

    @Test
    fun `any status but 200 is a failure`() {
        for (status in listOf("404 Not Found", "500 Internal Server Error", "204 No Content", "401 Unauthorized")) {
            LocalHttpServer { out, _ -> reply(out, status, validBody) }.use { server ->
                assertNull(status, EndpointProbe.fetchHealth(url(server), 2_000))
            }
        }
    }

    @Test
    fun `not a nightlight reply is a failure`() {
        for (body in listOf("", "<html>router login</html>", "{\"ok\":true}", "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"not-a-uuid\"}")) {
            LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
                assertNull(body, EndpointProbe.fetchHealth(url(server), 2_000))
            }
        }
    }

    @Test
    fun `a body over 4 KB is refused whether declared or not`() {
        val big = validBody + " ".repeat(EndpointProbe.MAX_BODY_BYTES)
        LocalHttpServer { out, _ -> reply(out, "200 OK", big) }.use { server ->
            assertNull(EndpointProbe.fetchHealth(url(server), 2_000))
        }
    }

    @Test
    fun `a server that never answers ends at the timeout`() {
        LocalHttpServer { _, _ -> Thread.sleep(5_000) }.use { server ->
            val start = System.nanoTime()
            assertNull(EndpointProbe.fetchHealth(url(server), 600))
            val ms = (System.nanoTime() - start) / 1_000_000L
            assertTrue("took $ms ms", ms < 3_000)
        }
    }

    @Test
    fun `nothing listening is a failure, not an exception`() {
        val port = LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { it.port }
        assertNull(EndpointProbe.fetchHealth("http://127.0.0.1:$port", 1_000))
    }
}
