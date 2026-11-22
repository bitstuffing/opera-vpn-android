package com.github.bitstuffing.operavpn

import com.github.bitstuffing.operavpn.relay.UdpRelayServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UdpRelayClientTest {

    @Test
    fun `envia y recibe UDP por el relay local`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val upstream = UdpRelayServer(loopback, 0, "secret", 16, 60_000L)

        val echo = DatagramSocket(0, loopback)
        val echoThread = Thread {
            val buffer = ByteArray(4096)
            while (!echo.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    echo.receive(packet)
                    echo.send(
                        DatagramPacket(
                            packet.data,
                            packet.length,
                            packet.address,
                            packet.port
                        )
                    )
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true }
        echoThread.start()

        val serverThread = Thread {
            try {
                upstream.start()
            } catch (_: Exception) {
                // Closed by the test.
            }
        }.apply { isDaemon = true }
        serverThread.start()

        val localRelay = LocalUdpRelay(
            protect = { true },
            onError = { error -> throw AssertionError(error) }
        )
        val received = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)
        val client = UdpRelayClient(
            openTunnel = { Socket(loopback, localRelay.port) },
            onDatagram = { sessionId, _, _, payload ->
                assertEquals(99L, sessionId)
                received.set(payload)
                latch.countDown()
            },
            onError = { error -> throw AssertionError(error) }
        )

        try {
            localRelay.start()
            client.connect()
            client.send(99L, loopback.address, echo.localPort, "ping".toByteArray())
            assertTrue(latch.await(5, TimeUnit.SECONDS), "No llegó la respuesta UDP del relay local")
            assertArrayEquals("ping".toByteArray(), received.get())
        } finally {
            client.close()
            localRelay.close()
            upstream.close()
            echo.close()
        }
    }
}
