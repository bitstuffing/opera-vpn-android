package com.github.bitstuffing.operavpn

import android.util.Log
import com.github.bitstuffing.operavpn.legacy.OperaSeClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Puente entre el código heredado de aprovisionamiento y el motor VPN.
 *
 * Sólo se usan las credenciales descubiertas por [OperaSeClient]; el
 * tráfico real se envía por el socket que crea Android VpnService.
 */
internal class OperaProxy(private val protect: (Socket) -> Boolean) {

    fun initialize(countryCode: String): OperaSeClient.OperaConnection {
        return OperaSeClient(
            OperaSeClient.CLIENT_TYPE,
            OperaSeClient.CLIENT_PASSWORD
        ).initialize(countryCode)
            ?: throw IOException("No se pudo inicializar el túnel de Opera ($countryCode)")
    }

    /**
     * TLS hasta el proxy de Opera, CONNECT autenticado y devolución del
     * socket en bruto. El llamador cierra el socket devuelto.
     */
    fun openTunnel(
        connection: OperaSeClient.OperaConnection,
        destinationHost: String,
        destinationPort: Int
    ): Socket {
        val raw = Socket()
        try {
            // VpnService.protect(Socket) works on the socket's file descriptor.
            // A fresh Socket() has no fd until it is bound or connected, so
            // binding first is required; connecting first would be too late
            // because the route would already be selected.
            raw.bind(InetSocketAddress("0.0.0.0", 0))
            if (!protect(raw)) {
                Log.w(TAG, "VpnService protect() devolvió false para el socket del túnel")
                throw IOException("VpnService no pudo proteger el socket (fd no enlazado)")
            }
            raw.connect(InetSocketAddress(connection.host, connection.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val outer = factory.createSocket(
                raw,
                connection.sniHostname,
                connection.port,
                true
            ) as SSLSocket
            outer.sslParameters = outer.sslParameters.apply {
                serverNames = listOf(SNIHostName(connection.sniHostname))
            }
            outer.soTimeout = READ_TIMEOUT_MS
            outer.startHandshake()

            val credentials = Base64.getEncoder().encodeToString(
                "${connection.username}:${connection.password}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            val request = buildString {
                append("CONNECT $destinationHost:$destinationPort HTTP/1.1\r\n")
                append("Host: $destinationHost:$destinationPort\r\n")
                append("Proxy-Authorization: Basic $credentials\r\n")
                append("Proxy-Connection: keep-alive\r\n")
                append("\r\n")
            }
            outer.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            outer.outputStream.flush()

            // Sin buffer: con un BufferedInputStream consumiríamos bytes del túnel.
            val status = readLine(outer)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Respuesta CONNECT inválida: $status")
            while (readLine(outer).isNotEmpty()) Unit
            if (code !in 200..299) {
                throw IOException(
                    "Opera rechazó CONNECT a $destinationHost:$destinationPort: $status"
                )
            }

            outer.soTimeout = 0 // The tunnel is long lived.
            return outer
        } catch (e: Exception) {
            try {
                raw.close()
            } catch (_: Exception) {
                // Preserve the original error.
            }
            throw e
        }
    }

    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val out = StringBuilder()
        while (out.length < MAX_HEADER_BYTES) {
            val byte = input.read()
            if (byte < 0) throw IOException("Opera cerró la conexión durante CONNECT")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.append(byte.toChar())
        }
        return out.toString()
    }

    private companion object {
        const val TAG = "OperaProxy"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
