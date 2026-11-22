
package com.github.bitstuffing.operavpn.legacy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Transporte saliente hacia el proxy de Opera.
 *
 * No abre puertos locales ni crea un segundo servidor.
 */
internal class OperaTransport(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String,
    private val password: String,
    private val countryCode: String
) : Closeable {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_HEADER_BYTES = 64 * 1024
    }

    private val upstreamFactory: SSLSocketFactory by lazy {
        // Se conserva deliberadamente el comportamiento de la
        // implementación original: no validar el certificado exterior.
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    emptyArray()

                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String
                ) = Unit
            }
        )

        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, java.security.SecureRandom())
        }.socketFactory
    }

    /**
     * Devuelve un socket TLS conectado al destino a través del
     * túnel autenticado de Opera.
     *
     * El llamador es responsable de cerrar el socket.
     */
    fun connect(destinationHost: String, destinationPort: Int): SSLSocket {
        val raw = Socket()

        try {
            raw.connect(
                InetSocketAddress(proxyHost, proxyPort),
                CONNECT_TIMEOUT_MS
            )
            raw.soTimeout = READ_TIMEOUT_MS

            val outer = upstreamFactory.createSocket(
                raw,
                "${countryCode.lowercase()}0.sec-tunnel.com",
                proxyPort,
                true
            ) as SSLSocket

            outer.soTimeout = READ_TIMEOUT_MS
            outer.startHandshake()

            val credentials = Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray(StandardCharsets.UTF_8)
            )

            val request = buildString {
                append("CONNECT $destinationHost:$destinationPort HTTP/1.1\r\n")
                append("Host: $destinationHost:$destinationPort\r\n")
                append("Proxy-Connection: keep-alive\r\n")
                append("Proxy-Authorization: Basic $credentials\r\n")
                append("\r\n")
            }

            val output = BufferedOutputStream(outer.outputStream)
            output.write(request.toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()

            val response = readHeaders(outer)

            if (!response.startsWith("HTTP/1.1 200") &&
                !response.startsWith("HTTP/1.0 200")
            ) {
                throw IOException(
                    "Opera CONNECT failed: ${response.lineSequence().firstOrNull()}"
                )
            }

            /*
             * El socket exterior transporta el túnel CONNECT.
             * Sobre él se inicia TLS para el servidor de destino.
             *
             * La validación del certificado del destino permanece
             * en el SSLSocketFactory predeterminado de Android.
             */
            val targetFactory = SSLSocketFactory.getDefault()
                    as SSLSocketFactory

            val target = targetFactory.createSocket(
                outer,
                destinationHost,
                destinationPort,
                true
            ) as SSLSocket

            target.soTimeout = READ_TIMEOUT_MS
            target.startHandshake()

            return target
        } catch (e: Exception) {
            try {
                raw.close()
            } catch (_: Exception) {
                // Conserva la excepción original.
            }
            throw e
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = BufferedInputStream(socket.getInputStream())
        val output = StringBuilder()
        var previous = 0
        var current: Int

        while (output.length < MAX_HEADER_BYTES) {
            current = input.read()
            if (current < 0) {
                throw IOException("Opera closed connection before CONNECT response")
            }

            output.append(current.toChar())

            if (previous == '\r'.code && current == '\n'.code) {
                val text = output.toString()
                if (text.endsWith("\r\n\r\n")) return text
            }

            previous = current
        }

        throw IOException("Opera CONNECT response headers too large")
    }

    override fun close() = Unit
}
