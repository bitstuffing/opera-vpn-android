package com.github.bitstuffing.operavpn

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Duration
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Verifies the bridge used by the VPN engine against the real Opera proxy. */
class OperaProxyTest {

    @Test
    fun `abre un CONNECT autenticado y deja pasar TLS al destino`() {
        val reachable = try {
            InetAddress.getByName("api2.sec-tunnel.com")
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue(reachable, "Sin DNS para api2.sec-tunnel.com")

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120)) {
            val proxy = OperaProxy { true }
            val connection = proxy.initialize("EU")

            proxy.openTunnel(connection, "example.com", 443).use { tunnel ->
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val target = factory.createSocket(
                    tunnel, "example.com", 443, true
                ) as SSLSocket
                target.sslParameters = target.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName("example.com"))
                }
                target.soTimeout = 20_000
                target.startHandshake()

                target.outputStream.write(
                    "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n".toByteArray()
                )
                target.outputStream.flush()
                val status = target.inputStream.bufferedReader().readLine()
                assertTrue(status?.startsWith("HTTP/") == true, "Respuesta inesperada: $status")
            }
        }
    }
}
