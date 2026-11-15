package com.sauso.nightlight

import com.sauso.nightlight.LocalHttpServer.Companion.ascii
import com.sauso.nightlight.LocalHttpServer.Companion.reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.CookieHandler
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Issue #22: exercise the exact probes used by typed save and the launch gate on local sockets. */
class TypedAddressProbeTest {
    private val loopback = "127.0.0.1"
    private fun probe(deadlineMs: Long = HealthProbe.TOTAL_DEADLINE_MS) =
        HealthProbe(totalDeadlineMs = deadlineMs)
    private fun url(server: LocalHttpServer) = "http://$loopback:${server.port}"

    @Test
    fun `typed check needs ok and app but no announced id`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", "{\"ok\":true,\"app\":\"nightlight\"}") }.use { server ->
            assertEquals("{\"ok\":true,\"app\":\"nightlight\"}", probe().checkTypedUrl(url(server)))
        }
        for (body in listOf("{\"ok\":false,\"app\":\"nightlight\"}",
            "{\"ok\":true,\"app\":\"other\"}", "{\"ok\":true}")) {
            LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
                assertNull(probe().checkTypedUrl(url(server)))
            }
        }
    }

    @Test
    fun `typed save and launch refuse redirects without contacting the target`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", "{\"ok\":true,\"app\":\"nightlight\"}") }.use { target ->
            for (status in listOf("301 Moved Permanently", "302 Found")) {
                LocalHttpServer { out, _ ->
                    out.write(ascii("HTTP/1.1 $status\r\nLocation: ${url(target)}/api/health\r\nContent-Length: 0\r\n\r\n"))
                    out.flush()
                }.use { redirect ->
                    assertNull(probe().checkTypedUrl(url(redirect)))
                    assertFalse(probe().isReachableUrl(url(redirect)))
                }
            }
            Thread.sleep(100)
            assertEquals(0, target.hits.get())
        }
    }

    @Test
    fun `typed save and launch neither send nor store cookies`() {
        val gets = AtomicInteger()
        val puts = AtomicInteger()
        val old = CookieHandler.getDefault()
        CookieHandler.setDefault(object : CookieHandler() {
            override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> {
                gets.incrementAndGet()
                return mapOf("Cookie" to listOf("planted=1"))
            }
            override fun put(uri: URI, responseHeaders: Map<String, List<String>>) { puts.incrementAndGet() }
        })
        try {
            LocalHttpServer { out, _ -> reply(out, "200 OK", "{\"ok\":true,\"app\":\"nightlight\"}",
                "Set-Cookie: planted=2; Path=/\r\n") }.use { server ->
                assertTrue(probe().checkTypedUrl(url(server)) != null)
                assertTrue(probe().isReachableUrl(url(server)))
                assertEquals(2, server.hits.get())
                assertTrue(server.requests.all { !it.contains("Cookie:", ignoreCase = true) })
            }
            assertEquals(0, gets.get())
            assertEquals(0, puts.get())
        } finally { CookieHandler.setDefault(old) }
    }

    @Test
    fun `typed save stops at the body cap even when the server keeps sending`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.0 200 OK\r\n\r\n"))
            val junk = ByteArray(1024) { ' '.code.toByte() }
            try { repeat(100_000) { out.write(junk) } } catch (e: java.io.IOException) { /* Probe closed at the cap. */ }
        }.use { server ->
            val start = System.nanoTime()
            assertNull(probe().checkTypedUrl(url(server)))
            assertTrue((System.nanoTime() - start) / 1_000_000L < 1_500)
        }
    }

    @Test
    fun `silent server times out for both typed save and launch`() {
        LocalHttpServer { _, _ -> Thread.sleep(1_000) }.use { server ->
            val start = System.nanoTime()
            assertNull(probe(300).checkTypedUrl(url(server)))
            val typedMs = (System.nanoTime() - start) / 1_000_000L
            assertTrue("typed took $typedMs ms", typedMs in 200L..1_500L)
            val secondStart = System.nanoTime()
            assertFalse(probe(300).isReachableUrl(url(server)))
            val launchMs = (System.nanoTime() - secondStart) / 1_000_000L
            assertTrue("launch took $launchMs ms", launchMs in 200L..1_500L)
        }
    }

    @Test
    fun `bare typed address tries https then http and saves the first accepted URL`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", "{\"ok\":true,\"app\":\"nightlight\"}") }.use { server ->
            val candidates = TypedAddressProbe.candidates("$loopback:${server.port}")!!
            val tried = mutableListOf<String>()
            val accepted = TypedAddressProbe.firstAccepted(candidates) { candidate ->
                tried.add(candidate)
                if (candidate.startsWith("http://")) probe().checkTypedUrl(candidate) else null
            }
            assertEquals(listOf("https://$loopback:${server.port}", url(server)), tried)
            assertEquals(url(server), accepted?.first)
            assertTrue(accepted?.second?.contains("nightlight") == true)
            assertEquals(listOf(url(server)), TypedAddressProbe.candidates(url(server)))
        }
    }

    @Test
    fun `first accepted skips a throwing candidate and does not probe after success`() {
        // Review of #50: a failed HTTPS check must still allow HTTP, and acceptance stops the loop.
        val candidates = TypedAddressProbe.candidates("example.test:4000")!! + "http://unused.test"
        val tried = mutableListOf<String>()
        val accepted = TypedAddressProbe.firstAccepted(candidates) { candidate ->
            tried.add(candidate)
            if (candidate.startsWith("https://")) throw java.io.IOException("TLS failed")
            "accepted"
        }
        assertEquals(listOf("https://example.test:4000", "http://example.test:4000"), tried)
        assertEquals("http://example.test:4000" to "accepted", accepted)

        tried.clear()
        val secure = TypedAddressProbe.firstAccepted(candidates) { candidate ->
            tried.add(candidate)
            "secure"
        }
        assertEquals(listOf("https://example.test:4000"), tried)
        assertEquals("https://example.test:4000" to "secure", secure)
    }

    @Test
    fun `typed save accepts a health reply after two seconds`() {
        // Review of #50: the old 2 s probe rejected a server that answers in roughly 2.5 s.
        val body = "{\"ok\":true,\"app\":\"nightlight\"}"
        LocalHttpServer { out, _ ->
            Thread.sleep(2_300)
            reply(out, "200 OK", body)
        }.use { server ->
            assertEquals(url(server) to body, TypedAddressProbe.firstAccepted(
                listOf(url(server)), HealthProbe.forTypedAddress()::checkTypedUrl))
        }
    }

    @Test
    fun `launch reachability accepts a reply within the requested 2500 milliseconds`() {
        // Review of #50: MainActivity asks for 2.5 s, which the old 2 s clamp discarded.
        LocalHttpServer { out, _ ->
            Thread.sleep(2_250)
            reply(out, "200 OK", "{}")
        }.use { server ->
            assertTrue(HealthProbe.forReachability(2_500).isReachableUrl(url(server)))
        }
    }

    @Test
    fun `typed and launch silent servers end within their new bounds`() {
        LocalHttpServer { _, _ -> Thread.sleep(6_000) }.use { server ->
            val typedStart = System.nanoTime()
            assertNull(HealthProbe.forTypedAddress().checkTypedUrl(url(server)))
            val typedMs = (System.nanoTime() - typedStart) / 1_000_000L
            assertTrue("typed took $typedMs ms", typedMs in 4_500L..6_000L)

            val launchStart = System.nanoTime()
            assertFalse(HealthProbe.forReachability(2_500).isReachableUrl(url(server)))
            val launchMs = (System.nanoTime() - launchStart) / 1_000_000L
            assertTrue("launch took $launchMs ms", launchMs in 2_200L..3_500L)
        }
    }
}
