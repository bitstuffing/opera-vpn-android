package com.github.bitstuffing.operavpn

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The Opera CONNECT tunnel is a byte stream to the destination. Port 443
 * expects TLS to the *destination*, so a second TLS layer is required before
 * speaking HTTP to the DoH server. This class owns that layer plus a small
 * keep-alive pool, so DNS does not open two handshakes per query.
 */
internal class DnsOverHttps(
    private val tunnel: SocketTunnel,
    private val wrapTls: (Socket, String, Int) -> Socket = ::wrapWithTls,
    private val onEvent: (String) -> Unit = { event -> Log.d(TAG, event) },
    poolSize: Int = DEFAULT_POOL_SIZE
) : Closeable {

    private val connections = Array(poolSize.coerceAtLeast(1)) { DohConnection() }
    private val slot = AtomicInteger()
    private val queries = AtomicLong()
    private val successes = AtomicLong()
    private val failures = AtomicLong()

    @Volatile
    var lastError: String = ""
        private set

    val queryCount: Long get() = queries.get()
    val successCount: Long get() = successes.get()
    val failureCount: Long get() = failures.get()

    fun diagnostics(): String =
        "DoH consultas=$queryCount ok=$successCount fallos=$failureCount último=$lastError"

    fun query(dnsMessage: ByteArray): ByteArray? {
        queries.incrementAndGet()
        if (dnsMessage.isEmpty() || dnsMessage.size > MAX_QUESTION) {
            report("consulta descartada (${dnsMessage.size} bytes)")
            return null
        }

        val connection = connections[Math.floorMod(slot.getAndIncrement(), connections.size)]
        val attempt: () -> ByteArray? = { connection.exchange(dnsMessage) }

        val first = try {
            attempt()
        } catch (e: Exception) {
            report("${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (first != null && first.isNotEmpty()) {
            successes.incrementAndGet()
            return first
        }
        if (first == null) report("respuesta vacía; reintentando")

        // A stale keep-alive socket can answer EOF instead of throwing; the
        // second attempt always opens a fresh one.
        val second = try {
            attempt()
        } catch (e: Exception) {
            report("fallo definitivo: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (second == null || second.isEmpty()) {
            if (second != null) report("respuesta vacía definitiva")
            return null
        }
        successes.incrementAndGet()
        return second
    }

    private fun report(reason: String) {
        failures.incrementAndGet()
        lastError = reason
        onEvent(reason)
    }

    private inner class DohConnection {
        private val lock = Any()
        private var socket: Socket? = null

        fun exchange(message: ByteArray): ByteArray? = synchronized(lock) {
            val active = ensureSocket()
            active.soTimeout = READ_TIMEOUT_MS
            try {
                val request = (
                    "POST /dns-query HTTP/1.1\r\n" +
                        "Host: $DOH_HOST\r\n" +
                        "Content-Type: application/dns-message\r\n" +
                        "Accept: application/dns-message\r\n" +
                        "Content-Length: ${message.size}\r\n" +
                        "\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)

                active.outputStream.write(request)
                active.outputStream.write(message)
                active.outputStream.flush()

                val answer = readAnswer(active.inputStream)
                if (answer == null) {
                    closeSocket()
                    throw IOException("DoH cerró la conexión sin respuesta")
                }
                answer
            } catch (e: IOException) {
                closeSocket()
                throw e
            }
        }

        private fun ensureSocket(): Socket {
            socket?.let { if (!it.isClosed) return it }
            val plain = tunnel.open(DOH_HOST, DOH_PORT)
            val secured = try {
                wrapTls(plain, DOH_HOST, DOH_PORT)
            } catch (e: Exception) {
                try {
                    plain.close()
                } catch (_: IOException) {
                    // Preserve the TLS error.
                }
                throw e
            }
            socket = secured
            return secured
        }

        fun close() = synchronized(lock) { closeSocket() }

        private fun closeSocket() {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Best effort.
            }
            socket = null
        }
    }

    private fun readAnswer(input: java.io.InputStream): ByteArray? {
        val status = readLine(input) ?: return null
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        if (code !in 200..299) throw IOException("HTTP $status")

        var contentLength = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (line.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                line.contains("chunked", ignoreCase = true)
            ) {
                chunked = true
            }
        }

        return when {
            chunked -> readChunked(input)
            contentLength in 0..MAX_ANSWER -> {
                val body = ByteArray(contentLength)
                readFully(input, body)
                body
            }
            else -> {
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (body.size() < MAX_ANSWER) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    body.write(buffer, 0, read)
                }
                body.toByteArray()
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val out = StringBuilder()
        while (out.length < MAX_HEADER_BYTES) {
            val byte = input.read()
            if (byte < 0) return if (out.isEmpty()) null else out.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.append(byte.toChar())
        }
        return out.toString()
    }

    private fun readChunked(input: java.io.InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (body.size() < MAX_ANSWER) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            if (size > MAX_ANSWER - body.size()) break
            val chunk = ByteArray(size)
            readFully(input, chunk)
            body.write(chunk)
            readLine(input)
        }
        return body.toByteArray()
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw IOException("Respuesta DoH truncada")
            offset += read
        }
    }

    override fun close() {
        connections.forEach { it.close() }
    }

    private companion object {
        const val TAG = "OperaVpnDns"
        const val DOH_HOST = "cloudflare-dns.com"
        const val DOH_PORT = 443
        const val DEFAULT_POOL_SIZE = 3
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_QUESTION = 4_096
        const val MAX_ANSWER = 64 * 1024

        fun wrapWithTls(plain: Socket, host: String, port: Int): Socket {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(plain, host, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            tls.soTimeout = READ_TIMEOUT_MS
            tls.startHandshake()
            return tls
        }
    }
}
