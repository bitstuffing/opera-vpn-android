package com.github.bitstuffing.operavpn.legacy

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class HttpResult(
    val status: Int,
    val body: ByteArray,
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun text(): String = body.toString(Charsets.UTF_8)

    fun header(name: String): String? =
        headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
}

interface HttpFetcher {
    val name: String
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 2 * 1024 * 1024
    ): HttpResult

    fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        maxBytes: Int = 2 * 1024 * 1024
    ): HttpResult = postBytes(url, headers, body.toByteArray(Charsets.UTF_8), maxBytes)

    fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Int = 2 * 1024 * 1024
    ): HttpResult = postBytes(url, headers, body, maxBytes)

    fun postBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray,
        maxBytes: Int = 4 * 1024 * 1024
    ): HttpResult
}

private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000

// Cabeceras que gestionamos nosotros (con un socket crudo no hay descompresión).
private val SKIPPED_HEADERS = setOf(
    "host",
    "connection",
    "proxy-authorization",
    "proxy-connection",
    "transfer-encoding",
    "content-length"
)

private fun cleanHeaders(headers: Map<String, String>) =
    headers.filterKeys { it.lowercase() !in SKIPPED_HEADERS }

internal object HttpIo {
    fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Fin de flujo inesperado en cabeceras HTTP")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16 * 1024) throw IOException("Línea HTTP demasiado larga")
        }
        return out.toString("US-ASCII")
    }

    fun readExact(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(result, offset, length - offset)
            if (n < 0) throw IOException("Cuerpo HTTP incompleto")
            offset += n
        }
        return result
    }

    fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Tamaño de chunk inválido")
            if (size == 0L) {
                while (readLine(input).isNotEmpty()) Unit
                break
            }
            if (size > maxBytes.toLong() - total) throw IOException("Respuesta supera el límite")
            val chunk = readExact(input, size.toInt())
            out.write(chunk)
            total += chunk.size
            if (readLine(input).isNotEmpty()) throw IOException("Chunk mal formado")
        }
        return out.toByteArray()
    }

    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > maxBytes - total) throw IOException("Respuesta supera $maxBytes bytes")
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

class DirectFetcher : HttpFetcher {
    override val name = "direct"

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResult {
        return request("GET", url, headers, byteArrayOf(), maxBytes)
    }

    override fun postBytes(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Int
    ): HttpResult {
        return request("POST", url, headers, body, maxBytes)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Int
    ): HttpResult {
        var current = url
        for (redirect in 0..MAX_REDIRECTS) {
            val c = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = CONNECT_TIMEOUT_MS
                c.readTimeout = READ_TIMEOUT_MS
                c.requestMethod = method
                c.instanceFollowRedirects = false
                cleanHeaders(headers).forEach { (k, v) -> c.setRequestProperty(k, v) }

                if (method == "POST") {
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }

                val status = c.responseCode
                if (status in 300..399 && redirect < MAX_REDIRECTS) {
                    val location = c.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        current = URI(current).resolve(location).toString()
                        continue
                    }
                }

                val responseHeaders = c.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                    .mapValues { it.value.joinToString(", ") }

                val stream = if (status in 200..299) c.inputStream else c.errorStream
                val respBody = stream?.use { HttpIo.readLimited(it, maxBytes) } ?: byteArrayOf()
                return HttpResult(status, respBody, current, responseHeaders)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Demasiadas redirecciones")
    }
}

class OperaFetcher(private val opera: OperaSeClient.OperaConnection) : HttpFetcher {
    override val name = "opera"

    /** TLS contra el proxy de Opera (SNI sec-tunnel) + CONNECT autenticado. */
    private fun openTunnel(host: String, port: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(opera.host, opera.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, opera.sniHostname, opera.port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply {
                serverNames = listOf(SNIHostName(opera.sniHostname))
            }
            tls.startHandshake()

            val auth = Base64.getEncoder()
                .encodeToString("${opera.username}:${opera.password}".toByteArray())
            tls.outputStream.write(
                ("CONNECT $host:$port HTTP/1.1\r\n" +
                        "Host: $host:$port\r\n" +
                        "Proxy-Authorization: Basic $auth\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            tls.outputStream.flush()

            // Sin buffer: no debemos consumir bytes del túnel.
            val statusLine = HttpIo.readLine(tls.inputStream)
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Respuesta CONNECT inválida: $statusLine")
            while (HttpIo.readLine(tls.inputStream).isNotEmpty()) Unit
            if (code !in 200..299) throw IOException("CONNECT rechazado: $statusLine")
            return tls
        } catch (e: Exception) {
            try { raw.close() } catch (_: IOException) {}
            throw e
        }
    }

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResult {
        return request("GET", url, headers, body = byteArrayOf(), maxBytes)
    }

    override fun postBytes(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Int
    ): HttpResult {
        return request("POST", url, headers, body, maxBytes)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Int
    ): HttpResult {
        var current = url
        for (redirect in 0..MAX_REDIRECTS) {
            val target = URI(current)
            val scheme = target.scheme?.lowercase()
            val host = target.host ?: throw IOException("Falta el hostname")
            require(scheme == "http" || scheme == "https") { "Esquema no soportado: $scheme" }
            val port = when {
                target.port > 0 -> target.port
                scheme == "https" -> 443
                else -> 80
            }

            val tunnel = openTunnel(host, port)
            try {
                val upstream: Socket = if (scheme == "https") {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(tunnel, host, port, true) as SSLSocket
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(host))
                    }
                    tls.startHandshake()
                    tls
                } else tunnel
                upstream.soTimeout = READ_TIMEOUT_MS

                val input = BufferedInputStream(upstream.inputStream)
                val output = BufferedOutputStream(upstream.outputStream)

                val path = (target.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                        (target.rawQuery?.let { "?$it" } ?: "")
                val bodyBytes = if (method == "POST") body else byteArrayOf()
                val requestStr = buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: $host")
                    if (target.port > 0) append(":${target.port}")
                    append("\r\n")
                    cleanHeaders(headers).forEach { (k, v) -> append("$k: $v\r\n") }
                    if (method == "POST") {
                        append("Content-Length: ${bodyBytes.size}\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                output.write(requestStr.toByteArray(Charsets.ISO_8859_1))
                if (method == "POST" && bodyBytes.isNotEmpty()) {
                    output.write(bodyBytes)
                }
                output.flush()

                val status = HttpIo.readLine(input).split(" ").getOrNull(1)?.toIntOrNull()
                    ?: throw IOException("Respuesta HTTP inválida")

                val h = mutableMapOf<String, String>()
                while (true) {
                    val line = HttpIo.readLine(input)
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        h[k] = listOfNotNull(h[k], v).joinToString(", ")
                    }
                }

                val location = h["location"]
                if (status in 300..399 && !location.isNullOrBlank() && redirect < MAX_REDIRECTS) {
                    current = target.resolve(location).toString()
                    continue
                }

                val respBody = when {
                    status == 204 || status == 304 || status in 100..199 -> byteArrayOf()
                    h["transfer-encoding"]?.contains("chunked", true) == true ->
                        HttpIo.readChunked(input, maxBytes)
                    h["content-length"] != null -> {
                        val len = h["content-length"]!!.toLongOrNull()
                            ?: throw IOException("Content-Length inválido")
                        if (len < 0 || len > maxBytes) throw IOException("Respuesta supera el límite")
                        HttpIo.readExact(input, len.toInt())
                    }
                    else -> HttpIo.readLimited(input, maxBytes)
                }
                return HttpResult(status, respBody, current, h)
            } finally {
                try { tunnel.close() } catch (_: IOException) {}
            }
        }
        throw IOException("Demasiadas redirecciones")
    }
}

/** Decide por host: Opera para [operaHosts] (null = todo), directo para el resto. */
class RoutingFetcher(
    private val direct: HttpFetcher,
    private val opera: HttpFetcher?,
    private val operaHosts: Set<String>?
) : HttpFetcher {
    override val name = "routing"

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResult {
        val host = URI(url).host?.lowercase()
        val useOpera = opera != null && (operaHosts == null || host in operaHosts)
        val fetcher = if (useOpera) opera!! else direct
        Log.d("HttpFetcher", "GET $host via=${fetcher.name} with headers=$headers")
        val response = fetcher.get(url, headers, maxBytes)
        Log.d("HttpFetcher","GET $url via=${fetcher.name} -> ${response.status}\n ${response.text()}")
        return response
    }

    override fun postBytes(url: String, headers: Map<String, String>, body: ByteArray, maxBytes: Int): HttpResult {
        val host = URI(url).host?.lowercase()
        val useOpera = opera != null && (operaHosts == null || host in operaHosts)
        val fetcher = if (useOpera) opera!! else direct
        Log.d("HttpFetcher", "POST $host via=${fetcher.name}")
        return fetcher.postBytes(url, headers, body, maxBytes)
    }
}
