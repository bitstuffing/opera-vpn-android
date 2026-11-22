package com.github.bitstuffing.operavpn

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exercises the real DoH HTTP exchange against a local server. Before the TLS
 * fix the app sent plaintext HTTP to port 443, so every query failed silently;
 * these tests cover that path, the HTTP framing and the retry behaviour.
 */
class DnsOverHttpsTest {

    private val answer = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(), 0x9a.toByte())

    private enum class Mode { LENGTH, CHUNKED, BAD_STATUS, CLOSE_AFTER_ONE, NO_LENGTH }

    private class FakeDohServer(
        private val mode: Mode,
        private val answer: ByteArray
    ) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val running = AtomicBoolean(true)

        fun start() {
            Thread(::acceptLoop, "fake-doh-accept").apply { isDaemon = true; start() }
        }

        private fun acceptLoop() {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                Thread { handle(socket) }.apply { isDaemon = true; start() }
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 2_000
            val input = socket.inputStream
            val output = socket.outputStream
            try {
                while (running.get()) {
                    val header = readHeader(input) ?: break
                    val length = contentLength(header)
                    if (length !in 0..65_536) break
                    val body = ByteArray(length)
                    readFully(input, body)
                    requests.add("$header BODY=${body.joinToString(" ") { (it.toInt() and 0xff).toString() }}")
                    output.write(response())
                    output.flush()
                    if (mode == Mode.CLOSE_AFTER_ONE || mode == Mode.NO_LENGTH) break
                }
            } catch (_: Exception) {
                // The client closed the connection.
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                    // Best effort.
                }
            }
        }

        private fun response(): ByteArray {
            val head = when (mode) {
                Mode.BAD_STATUS -> "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"
                Mode.CHUNKED -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                Mode.NO_LENGTH -> "HTTP/1.1 200 OK\r\n\r\n"
                else -> "HTTP/1.1 200 OK\r\nContent-Length: ${answer.size}\r\n\r\n"
            }.toByteArray(Charsets.US_ASCII)
            return when (mode) {
                Mode.CHUNKED -> head +
                        "${answer.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII) +
                        answer + "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                Mode.BAD_STATUS -> head
                else -> head + answer
            }
        }

        private fun readHeader(input: InputStream): String? {
            val out = StringBuilder()
            while (out.length < 8_192) {
                val byte = input.read()
                if (byte < 0) return null
                out.append(byte.toChar())
                if (out.endsWith("\r\n\r\n")) return out.toString()
            }
            return null
        }

        private fun contentLength(header: String): Int {
            header.lineSequence().forEach { line ->
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    return line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            return 0
        }

        private fun readFully(input: InputStream, target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                val read = input.read(target, offset, target.size - offset)
                if (read < 0) throw IOException("BODY truncado")
                offset += read
            }
        }

        override fun close() {
            running.set(false)
            try {
                server.close()
            } catch (_: IOException) {
                // Best effort.
            }
        }
    }

    private fun doh(
        server: FakeDohServer,
        events: MutableList<String> = mutableListOf(),
        tunnelHook: (() -> Unit)? = null,
        wrapHook: ((String, Int) -> Unit)? = null
    ): DnsOverHttps = DnsOverHttps(
        tunnel = SocketTunnel { host, port ->
            assertEquals("cloudflare-dns.com", host)
            assertEquals(443, port)
            tunnelHook?.invoke()
            Socket("127.0.0.1", server.port)
        },
        // The real wrapper adds TLS to cloudflare-dns.com; the local server
        // speaks plain HTTP so the HTTP framing can be tested directly.
        wrapTls = { socket, host, port ->
            wrapHook?.invoke(host, port)
            socket
        },
        onEvent = { events.add(it) },
        poolSize = 1
    )

    @Test
    fun `envia la consulta y parsea respuesta con content-length`() {
        val server = FakeDohServer(Mode.LENGTH, answer).also { it.start() }
        val events = mutableListOf<String>()
        val doh = doh(server, events)
        try {
            assertArrayEquals(answer, doh.query(byteArrayOf(1, 2, 3, 4)))
            assertEquals(1, server.requests.size)
            val request = server.requests[0]
            assertTrue(request.contains("POST /dns-query HTTP/1.1"), request)
            assertTrue(request.contains("Host: cloudflare-dns.com"), request)
            assertTrue(request.contains("Content-Type: application/dns-message"), request)
            assertTrue(request.contains("Content-Length: 4"), request)
            assertTrue(request.contains("BODY=1 2 3 4"), request)
            assertTrue(events.isEmpty(), events.toString())
            assertEquals(1, doh.successCount)
            assertEquals(0, doh.failureCount)
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `parsea respuesta chunked`() {
        val server = FakeDohServer(Mode.CHUNKED, answer).also { it.start() }
        val doh = doh(server)
        try {
            assertArrayEquals(answer, doh.query(byteArrayOf(9)))
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `lee el cuerpo hasta eof si no hay content-length`() {
        val server = FakeDohServer(Mode.NO_LENGTH, answer).also { it.start() }
        val doh = doh(server)
        try {
            assertArrayEquals(answer, doh.query(byteArrayOf(7)))
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `devuelve null y deja constancia del error ante un 502`() {
        val server = FakeDohServer(Mode.BAD_STATUS, answer).also { it.start() }
        val events = mutableListOf<String>()
        val doh = doh(server, events)
        try {
            assertEquals(null, doh.query(byteArrayOf(1)))
            assertTrue(doh.failureCount >= 1)
            assertTrue(doh.lastError.contains("502"), doh.lastError)
            assertTrue(events.isNotEmpty())
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `registra el fallo si el tunel no se puede abrir`() {
        var opens = 0
        val events = mutableListOf<String>()
        val doh = DnsOverHttps(
            tunnel = SocketTunnel { _, _ ->
                opens++
                throw IOException("sin ruta al proxy")
            },
            wrapTls = { socket, _, _ -> socket },
            onEvent = { events.add(it) },
            poolSize = 1
        )
        assertEquals(null, doh.query(byteArrayOf(1)))
        assertEquals(2, opens, "Debe reintentar una vez con un túnel nuevo")
        assertTrue(doh.lastError.contains("sin ruta al proxy"), doh.lastError)
        assertTrue(events.isNotEmpty())
        doh.close()
    }

    @Test
    fun `reintenta cuando el servidor cierra la conexion reutilizada`() {
        val server = FakeDohServer(Mode.CLOSE_AFTER_ONE, answer).also { it.start() }
        var opens = 0
        val doh = doh(server, tunnelHook = { opens++ })
        try {
            assertArrayEquals(answer, doh.query(byteArrayOf(1)))
            assertArrayEquals(answer, doh.query(byteArrayOf(2)))
            assertTrue(opens >= 2, "Se esperaba una conexión nueva tras el cierre, hubo $opens")
            assertEquals(2, doh.successCount)
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `envuelve el tunel en tls con el host del dns`() {
        val server = FakeDohServer(Mode.LENGTH, answer).also { it.start() }
        val calls = mutableListOf<Pair<String, Int>>()
        val doh = doh(server, wrapHook = { host, port -> calls.add(host to port) })
        try {
            doh.query(byteArrayOf(1))
            assertEquals(listOf("cloudflare-dns.com" to 443), calls)
        } finally {
            doh.close()
            server.close()
        }
    }

    @Test
    fun `rechaza consultas vacias o demasiado grandes sin abrir tunel`() {
        val server = FakeDohServer(Mode.LENGTH, answer).also { it.start() }
        var opens = 0
        val doh = doh(server, tunnelHook = { opens++ })
        try {
            assertEquals(null, doh.query(byteArrayOf()))
            assertEquals(null, doh.query(ByteArray(5_000)))
            assertEquals(0, opens)
            assertEquals(2, doh.failureCount)
        } finally {
            doh.close()
            server.close()
        }
    }
}
