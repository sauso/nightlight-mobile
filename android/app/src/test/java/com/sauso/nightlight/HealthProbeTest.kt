package com.sauso.nightlight

import com.sauso.nightlight.HealthProbe.Outcome
import com.sauso.nightlight.LocalHttpServer.Companion.ascii
import com.sauso.nightlight.LocalHttpServer.Companion.reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.CookieHandler
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * HealthProbe against real sockets (plan review of #20: "remove instanceFollowRedirects = false" would pass every
 * pure-rule test, so the network rules are tested by making requests). The address policy is injected to allow the
 * test's own 127.0.0.1 server ONLY; production refuses loopback, which the first test holds.
 *
 * Timing assertions use generous margins (a slow CI machine must not flake), but each one is far from what the
 * mutant it kills would take: a per-read timeout instead of the total deadline takes 10 s here, not 2.
 */
class HealthProbeTest {

    companion object {
        // When a probe that runs into the 2 s deadline must end: not much before it, not long after it.
        private val DEADLINE_WINDOW = 1_700L..3_500L
    }

    private val id = "4f1c2b9e-7a3d-4e21-9c5b-6d8e0f1a2b3c"
    private val validBody = "{\"ok\":true,\"app\":\"nightlight\",\"id\":\"$id\",\"name\":\"Nursery\"}"
    private val loopback = "127.0.0.1"
    private fun localProbe() = HealthProbe(addressPolicy = { it == loopback })

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000L
    }

    @Test
    fun `the production policy refuses loopback and makes no connection`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { server ->
            val result = HealthProbe().check(loopback, server.port, id)
            assertEquals(Outcome.REFUSED_ADDRESS, result.outcome)
            Thread.sleep(100)
            assertEquals("nothing may connect to a refused address", 0, server.hits.get())
        }
    }

    @Test
    fun `public and non-literal hosts are refused before any network use`() {
        val probe = HealthProbe()
        for (host in listOf("8.8.8.8", "100.64.0.1", "localhost", "nightlight.local", "::1")) {
            assertEquals(host, Outcome.REFUSED_ADDRESS, probe.check(host, 4000, id).outcome)
        }
    }

    @Test
    fun `a port outside 1 to 65535 is refused`() {
        assertEquals(Outcome.BAD_PORT, localProbe().check(loopback, 0, id).outcome)
        assertEquals(Outcome.BAD_PORT, localProbe().check(loopback, 65_536, id).outcome)
        assertEquals(Outcome.BAD_PORT, localProbe().check(loopback, -1, id).outcome)
    }

    @Test
    fun `a valid reply verifies, and returns as soon as the body is complete`() {
        // The server keeps the connection open after a complete reply: Content-Length must end the read, or a
        // good answer would become a timeout.
        LocalHttpServer { out, _ ->
            reply(out, "200 OK", validBody)
            Thread.sleep(3_000)
        }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.VERIFIED, result.outcome)
            assertEquals(DiscoveryRules.Health(id, "Nursery"), result.health)
            assertTrue("took $ms ms", ms < 1_500)
        }
    }

    @Test
    fun `a reply without Content-Length that ends by closing verifies`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n"))
            out.write(validBody.toByteArray())
            out.flush()
        }.use { server ->
            assertEquals(Outcome.VERIFIED, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `the request is a plain GET of the health path with no cookie and no credentials`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { server ->
            assertEquals(Outcome.VERIFIED, localProbe().check(loopback, server.port, id).outcome)
            val request = server.requests.single()
            assertTrue(request, request.startsWith("GET /api/health HTTP/1.0\r\n"))
            assertTrue(request, request.contains("\r\nHost: 127.0.0.1:${server.port}\r\n"))
            assertFalse(request, request.contains("cookie", ignoreCase = true))
            assertFalse(request, request.contains("authorization", ignoreCase = true))
        }
    }

    @Test
    fun `the process-wide cookie handler and proxy selector are never consulted`() {
        // Capacitor installs a process-wide CookieHandler backed by the WebView's cookies (CapacitorCookies), which
        // HttpURLConnection would use to send cookies AND to store the reply's Set-Cookie. Plant both a handler and
        // a proxy selector that count their use: the probe must touch neither, and send no Cookie header. Kills
        // switching the probe to HttpURLConnection or to a Socket() without Proxy.NO_PROXY.
        val cookieGets = AtomicInteger()
        val cookiePuts = AtomicInteger()
        val proxySelects = AtomicInteger()
        val oldCookies = CookieHandler.getDefault()
        val oldProxies = ProxySelector.getDefault()
        CookieHandler.setDefault(object : CookieHandler() {
            override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> {
                cookieGets.incrementAndGet()
                return mapOf("Cookie" to listOf("planted=1"))
            }

            override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
                cookiePuts.incrementAndGet()
            }
        })
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                proxySelects.incrementAndGet()
                return listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        })
        try {
            LocalHttpServer { out, _ -> reply(out, "200 OK", validBody, "Set-Cookie: evil=1; Path=/\r\n") }.use { server ->
                assertEquals(Outcome.VERIFIED, localProbe().check(loopback, server.port, id).outcome)
                assertFalse(server.requests.single().contains("planted"))
            }
            assertEquals("cookie handler asked for cookies", 0, cookieGets.get())
            assertEquals("cookie handler given the reply's Set-Cookie", 0, cookiePuts.get())
            assertEquals("proxy selector consulted", 0, proxySelects.get())
        } finally {
            CookieHandler.setDefault(oldCookies)
            ProxySelector.setDefault(oldProxies)
        }
    }

    @Test
    fun `a redirect is not followed`() {
        // Server B is a perfectly valid Nightlight; server A redirects to it. Following the redirect would verify
        // B, so B must see no request at all.
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { b ->
            LocalHttpServer { out, _ ->
                out.write(ascii("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${b.port}/api/health\r\nContent-Length: 0\r\n\r\n"))
                out.flush()
            }.use { a ->
                val result = localProbe().check(loopback, a.port, id)
                assertEquals(Outcome.NOT_OK_STATUS, result.outcome)
                assertNull(result.health)
                Thread.sleep(200)
                assertEquals("the redirect target was contacted", 0, b.hits.get())
            }
        }
    }

    @Test
    fun `any status but 200 fails, even with a valid body`() {
        for (status in listOf("201 Created", "204 No Content", "301 Moved Permanently", "404 Not Found", "500 Internal Server Error")) {
            LocalHttpServer { out, _ -> reply(out, status, validBody) }.use { server ->
                assertEquals(status, Outcome.NOT_OK_STATUS, localProbe().check(loopback, server.port, id).outcome)
            }
        }
    }

    @Test
    fun `an endless body is refused at 4 KB and well inside the deadline`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n"))
            val junk = ByteArray(1024) { ' '.code.toByte() }
            val stopAt = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < stopAt) out.write(junk) // until the probe hangs up
        }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.TOO_LARGE, result.outcome)
            assertTrue("took $ms ms", ms < 1_500)
        }
    }

    // The 4 KB cap, pinned from both sides: a body of exactly MAX_BODY_BYTES is read, one byte more is refused.
    // JSON allows trailing whitespace, so a valid reply is padded to the size wanted.
    private fun paddedBody(size: Int): String = validBody + " ".repeat(size - validBody.length)

    @Test
    fun `a body of exactly 4 KB is accepted, by Content-Length and by close`() {
        assertEquals(4096, HealthProbe.MAX_BODY_BYTES)
        val body = paddedBody(4096)
        LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
            assertEquals(Outcome.VERIFIED, localProbe().check(loopback, server.port, id).outcome)
        }
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.0 200 OK\r\n\r\n"))
            out.write(body.toByteArray())
            out.flush()
        }.use { server ->
            assertEquals(Outcome.VERIFIED, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `a body of 4 KB plus one byte is refused, by Content-Length and by close`() {
        val body = paddedBody(4097)
        LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
            assertEquals(Outcome.TOO_LARGE, localProbe().check(loopback, server.port, id).outcome)
        }
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.0 200 OK\r\n\r\n"))
            out.write(body.toByteArray())
            out.flush()
        }.use { server ->
            assertEquals(Outcome.TOO_LARGE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `a declared oversize body is refused at once, without waiting for it`() {
        // The server declares a megabyte and then sends nothing. Refusing on the header takes milliseconds;
        // waiting for the bytes would take the whole deadline.
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n"))
            out.flush()
            Thread.sleep(5_000)
        }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.TOO_LARGE, result.outcome)
            assertTrue("took $ms ms", ms < 1_000)
        }
    }

    @Test
    fun `a reply that dribbles past the total deadline is cut off at the deadline`() {
        // One byte every 50 ms: each read returns quickly, so a per-read timeout would never fire and the probe
        // would take the full 10 s the dribble lasts. The TOTAL deadline (2 s, the production value) must end it.
        assertEquals(2_000L, HealthProbe.TOTAL_DEADLINE_MS)
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\n"))
            out.flush()
            repeat(200) {
                out.write(' '.code)
                out.flush()
                Thread.sleep(50)
            }
        }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.TIMED_OUT, result.outcome)
            assertTrue("took $ms ms", ms in DEADLINE_WINDOW)
        }
    }

    @Test
    fun `headers that dribble are cut off at the deadline too`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.1 200 OK\r\n"))
            repeat(200) {
                out.write(ascii("X: y\r\n"))
                out.flush()
                Thread.sleep(50)
            }
        }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.TIMED_OUT, result.outcome)
            assertTrue("took $ms ms", ms in DEADLINE_WINDOW)
        }
    }

    @Test
    fun `a server that never answers times out at the deadline`() {
        LocalHttpServer { _, _ -> Thread.sleep(5_000) }.use { server ->
            val (result, ms) = timed { localProbe().check(loopback, server.port, id) }
            assertEquals(Outcome.TIMED_OUT, result.outcome)
            assertTrue("took $ms ms", ms in DEADLINE_WINDOW)
        }
    }

    @Test
    fun `a wrong app or ok false is not a Nightlight`() {
        for (body in listOf(
            "{\"ok\":true,\"app\":\"other\",\"id\":\"$id\"}",
            "{\"ok\":false,\"app\":\"nightlight\",\"id\":\"$id\"}",
            "{\"ok\":true}",
            "<html>ok</html>",
            "",
        )) {
            LocalHttpServer { out, _ -> reply(out, "200 OK", body) }.use { server ->
                assertEquals(body, Outcome.NOT_NIGHTLIGHT, localProbe().check(loopback, server.port, id).outcome)
            }
        }
    }

    @Test
    fun `a Nightlight with a different id than announced is refused`() {
        val other = "00000000-1111-2222-3333-444444444444"
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { server ->
            val result = localProbe().check(loopback, server.port, other)
            assertEquals(Outcome.ID_MISMATCH, result.outcome)
            assertNull(result.health)
        }
    }

    @Test
    fun `a chunked reply is refused rather than decoded`() {
        LocalHttpServer { out, _ ->
            val bytes = validBody.toByteArray()
            out.write(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"))
            out.write(ascii(Integer.toHexString(bytes.size) + "\r\n"))
            out.write(bytes)
            out.write(ascii("\r\n0\r\n\r\n"))
            out.flush()
        }.use { server ->
            assertEquals(Outcome.BAD_RESPONSE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `two different Content-Length values are refused`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nContent-Length: ${validBody.length}\r\n\r\n$validBody"))
            out.flush()
        }.use { server ->
            assertEquals(Outcome.BAD_RESPONSE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `a body cut short of its Content-Length is refused`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: ${validBody.length + 10}\r\n\r\n$validBody"))
            out.flush()
        }.use { server ->
            assertEquals(Outcome.BAD_RESPONSE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `not HTTP at all is refused`() {
        LocalHttpServer { out, _ ->
            out.write(ascii("SSH-2.0-OpenSSH_9.0\r\n\r\n$validBody"))
            out.flush()
        }.use { server ->
            assertEquals(Outcome.BAD_RESPONSE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `a closed port fails to connect, quickly`() {
        val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        val (result, ms) = timed { localProbe().check(loopback, port, id) }
        assertEquals(Outcome.CONNECT_FAILED, result.outcome)
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test
    fun `a server that accepts and hangs up without a reply is refused`() {
        LocalHttpServer { _, _ -> }.use { server ->
            assertEquals(Outcome.BAD_RESPONSE, localProbe().check(loopback, server.port, id).outcome)
        }
    }

    @Test
    fun `probe returns the health only when verified`() {
        LocalHttpServer { out, _ -> reply(out, "200 OK", validBody) }.use { server ->
            assertEquals(DiscoveryRules.Health(id, "Nursery"), localProbe().probe(loopback, server.port, id))
            assertNull(localProbe().probe(loopback, server.port, "00000000-1111-2222-3333-444444444444"))
        }
    }
}
