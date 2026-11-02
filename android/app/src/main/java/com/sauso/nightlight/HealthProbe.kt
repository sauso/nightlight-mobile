package com.sauso.nightlight

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * The one network request discovery makes on its own: `GET http://<ip>:<port>/api/health` to an address a device on
 * the LAN announced, to check that a Nightlight with the announced id really answers there (#20, design 7.5).
 *
 * java.net only, no Android imports, so the JVM unit tests run it against a real local socket server
 * (HealthProbeTest): a rule like "redirects are not followed" is only proven by a test that makes a request.
 *
 * Why a plain socket and not HttpURLConnection (a deviation from the plan, which named HttpURLConnection with
 * `instanceFollowRedirects = false`): Capacitor's built-in CapacitorCookies plugin, which loads in every app,
 * installs a process-wide java.net.CookieHandler backed by the WebView's cookie store
 * (CapacitorCookies.load -> CookieHandler.setDefault). HttpURLConnection consults that handler on every request:
 * it would SEND the WebView's cookies for that address, and STORE any Set-Cookie the reply carries, a second time
 * at the app's own origin when the cookie names no domain (CapacitorCookieManager.put). A probe of an address that
 * any device on the LAN can announce must do neither, and must not go through a system proxy either. A socket
 * opened with Proxy.NO_PROXY, writing a fixed request, has no cookie, credential, cache, proxy or redirect code
 * path at all; the tests plant a cookie handler and a proxy selector to hold that.
 *
 * Bounded work, because the other end may be hostile (design 7.5 step 2):
 *  - one TOTAL deadline ([totalDeadlineMs], 2 s) for connect, request, headers and body together. Each socket read
 *    waits at most the time left, and the deadline is checked after every read, so a server that dribbles one
 *    byte at a time cannot hold the probe past it (a per-read timeout alone would be reset by every byte);
 *  - at most [maxBodyBytes] (4 KB) of body and [MAX_HEADER_BYTES] of headers. A real reply is about 100 bytes;
 *  - no redirects: any status but 200 is a failure, decided as soon as the status line arrives;
 *  - HTTP/1.0, so a well-behaved server never answers chunked (Node, which serves the real reply, does not);
 *    a chunked reply is refused rather than decoded.
 *
 * [addressPolicy] decides which hosts may be contacted at all: the RFC 1918 allowlist in production, injectable
 * only so a test can allow its own loopback server. The address is built from the parsed octets, so no DNS lookup
 * is ever made.
 */
class HealthProbe(
    private val addressPolicy: (String) -> Boolean = DiscoveryRules::isPrivateIpv4,
    private val totalDeadlineMs: Long = TOTAL_DEADLINE_MS,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
) {
    companion object {
        // Design 7.5: "a ~2 s total deadline". The spike (design 15.1) measured the phone finding a server on the
        // same network almost at once, and a LAN health reply takes milliseconds, so 2 s is generous for a real
        // server and still short enough that probing every announced address in parallel never holds the page.
        const val TOTAL_DEADLINE_MS = 2_000L

        // Design 7.5: "a small maximum body size (e.g. 4 KB)". The real reply is `{ ok, app, id, name }`.
        const val MAX_BODY_BYTES = 4 * 1024

        // Node's own limit for request headers is 16 KB; a health reply's headers are a few hundred bytes.
        const val MAX_HEADER_BYTES = 8 * 1024

        private const val READ_CHUNK = 1024
        private val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }

    /** Why a probe ended. Reported so tests (and a debug log) can tell a timeout from a refusal. */
    enum class Outcome {
        VERIFIED,
        REFUSED_ADDRESS,
        BAD_PORT,
        CONNECT_FAILED,
        TIMED_OUT,
        NOT_OK_STATUS,
        TOO_LARGE,
        BAD_RESPONSE,
        NOT_NIGHTLIGHT,
        ID_MISMATCH,
    }

    data class Result(val outcome: Outcome, val health: DiscoveryRules.Health?)

    /** The verified health reply, or null for any failure. */
    fun probe(host: String, port: Int, expectedId: String): DiscoveryRules.Health? = check(host, port, expectedId).health

    /** Probe and say why it ended. Never throws. */
    fun check(host: String, port: Int, expectedId: String): Result {
        if (!addressPolicy(host)) return Result(Outcome.REFUSED_ADDRESS, null)
        if (port !in 1..65535) return Result(Outcome.BAD_PORT, null)
        val octets = DiscoveryRules.parseIpv4(host) ?: return Result(Outcome.REFUSED_ADDRESS, null)
        val address = try {
            InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
        } catch (e: Exception) {
            return Result(Outcome.REFUSED_ADDRESS, null)
        }
        val deadline = System.nanoTime() + totalDeadlineMs * 1_000_000L
        val socket = Socket(Proxy.NO_PROXY)
        try {
            val connectMs = remainingMs(deadline)
            if (connectMs <= 0) return Result(Outcome.TIMED_OUT, null)
            try {
                socket.connect(InetSocketAddress(address, port), connectMs.toInt())
            } catch (e: SocketTimeoutException) {
                return Result(Outcome.TIMED_OUT, null)
            } catch (e: IOException) {
                return Result(Outcome.CONNECT_FAILED, null)
            }
            // Nothing but these lines is ever sent: no Cookie, no Authorization, no body.
            val request = "GET /api/health HTTP/1.0\r\n" +
                "Host: $host:$port\r\n" +
                "Accept: application/json\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            val out = socket.getOutputStream()
            out.write(request.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            return readReply(socket, deadline, expectedId)
        } catch (e: SocketTimeoutException) {
            return Result(Outcome.TIMED_OUT, null)
        } catch (e: Exception) {
            return Result(Outcome.BAD_RESPONSE, null)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Nothing to do: the probe's result is already decided.
            }
        }
    }

    private class Head(val status: Int, val contentLength: Long?, val chunked: Boolean)

    private fun readReply(socket: Socket, deadline: Long, expectedId: String): Result {
        val input = socket.getInputStream()
        val received = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var bodyStart = -1
        var expectedTotal: Long? = null
        while (true) {
            // The TOTAL deadline: each read may wait only for the time that is left, and the loop ends when none
            // is left, however the bytes arrive.
            val left = remainingMs(deadline)
            if (left <= 0) return Result(Outcome.TIMED_OUT, null)
            socket.soTimeout = left.toInt()
            val n = input.read(chunk)
            if (n < 0) break
            received.write(chunk, 0, n)
            if (bodyStart < 0) {
                val bytes = received.toByteArray()
                val end = indexOf(bytes, HEADER_END)
                if (end < 0) {
                    if (bytes.size > MAX_HEADER_BYTES) return Result(Outcome.TOO_LARGE, null)
                    continue
                }
                if (end > MAX_HEADER_BYTES) return Result(Outcome.TOO_LARGE, null)
                val head = parseHead(String(bytes, 0, end, StandardCharsets.ISO_8859_1))
                    ?: return Result(Outcome.BAD_RESPONSE, null)
                // Decided on the status line alone: a redirect is a failure, never a new address to try.
                if (head.status != 200) return Result(Outcome.NOT_OK_STATUS, null)
                if (head.chunked) return Result(Outcome.BAD_RESPONSE, null)
                // Refuse a declared oversize body at once, instead of waiting for bytes that would be refused.
                if (head.contentLength != null && head.contentLength > maxBodyBytes) return Result(Outcome.TOO_LARGE, null)
                bodyStart = end + HEADER_END.size
                expectedTotal = head.contentLength?.let { bodyStart + it }
            }
            val bodyBytes = received.size() - bodyStart
            if (bodyBytes > maxBodyBytes) return Result(Outcome.TOO_LARGE, null)
            // With a Content-Length, stop as soon as the body is complete: a server that keeps the connection open
            // after a complete reply must not turn a good answer into a timeout.
            if (expectedTotal != null && received.size() >= expectedTotal) break
        }
        if (bodyStart < 0) return Result(Outcome.BAD_RESPONSE, null)
        val all = received.toByteArray()
        val end = expectedTotal?.toInt() ?: all.size
        if (end > all.size) return Result(Outcome.BAD_RESPONSE, null) // the connection closed before the body ended
        val body = String(all, bodyStart, end - bodyStart, StandardCharsets.UTF_8)
        val health = DiscoveryRules.parseHealth(body) ?: return Result(Outcome.NOT_NIGHTLIGHT, null)
        if (!DiscoveryRules.matches(health, expectedId)) return Result(Outcome.ID_MISMATCH, null)
        return Result(Outcome.VERIFIED, health)
    }

    // Status line `HTTP/1.x NNN reason`, then `Name: value` lines. Only what the probe needs is read: the status,
    // Content-Length (digits only; two different values are refused, the classic smuggling shape) and whether the
    // reply claims Transfer-Encoding.
    private fun parseHead(text: String): Head? {
        val lines = text.split("\r\n")
        val status = lines[0].split(' ', limit = 3)
        if (status.size < 2 || !status[0].startsWith("HTTP/1.")) return null
        val code = status[1]
        if (code.length != 3 || !code.all { it in '0'..'9' }) return null
        var contentLength: Long? = null
        var chunked = false
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                // More than 18 digits cannot be a body we would accept; say "too large" rather than overflow.
                if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
                val length = if (value.length > 18) Long.MAX_VALUE else value.toLong()
                if (contentLength != null && contentLength != length) return null
                contentLength = length
            } else if (name.equals("Transfer-Encoding", ignoreCase = true)) {
                chunked = true
            }
        }
        return Head(code.toInt(), contentLength, chunked)
    }

    private fun remainingMs(deadline: Long): Long = (deadline - System.nanoTime()) / 1_000_000L

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
