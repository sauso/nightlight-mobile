package com.sauso.nightlight

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP server on 127.0.0.1 for HealthProbeTest, written on java.net.ServerSocket so a test controls every
 * byte (a reply that dribbles, never ends, redirects, or closes early).
 *
 * Not com.sun.net.httpserver, which the plan named: Android unit tests are compiled against android.jar, which has
 * java.net but not the JDK's jdk.httpserver module, so that import would not compile. java.net.ServerSocket is in
 * both, and a hand-written reply is a stricter test anyway.
 *
 * [handler] gets the accepted socket's output and the request text (headers, up to the blank line).
 */
class LocalHttpServer(private val handler: (OutputStream, String) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    /** Connections accepted. A probe that was refused before connecting leaves this at 0. */
    val hits = AtomicInteger()

    /** The raw request text of each connection, in order. */
    val requests = CopyOnWriteArrayList<String>()

    val port: Int get() = server.localPort

    private val acceptor = Thread { acceptLoop() }.apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            hits.incrementAndGet()
            Thread { serve(socket) }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5_000
                val request = readRequest(s)
                requests.add(request)
                handler(s.getOutputStream(), request)
            } catch (e: IOException) {
                // The probe hung up (as it should, on a reply it refuses). Nothing to do.
            } catch (e: InterruptedException) {
                // close() during a slow reply.
            }
        }
    }

    private fun readRequest(s: Socket): String {
        val input = s.getInputStream()
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            val bytes = buf.toByteArray()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
                bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    override fun close() {
        server.close()
    }

    companion object {
        fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.ISO_8859_1)

        /** A complete reply with a Content-Length. */
        fun reply(out: OutputStream, status: String, body: String, extraHeaders: String = "") {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            out.write(ascii("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n$extraHeaders\r\n"))
            out.write(bytes)
            out.flush()
        }
    }
}
