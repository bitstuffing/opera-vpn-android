package com.github.bitstuffing.operavpn.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol;
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol.Datagram;
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol.Frame;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UdpRelayServerTest {
    @Test
    void relaysUdpDatagramsBothWays() throws Exception {
        byte[] address = InetAddress.getByName("127.0.0.1").getAddress();
        UdpRelayServer server = new UdpRelayServer(
                InetAddress.getByName("127.0.0.1"), 0, "secret", 16, 60_000L
        );
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        DatagramSocket echo = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        Thread echoThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (!echo.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    echo.receive(packet);
                    echo.send(new DatagramPacket(
                            packet.getData(), packet.getLength(), packet.getAddress(), packet.getPort()
                    ));
                } catch (Exception e) {
                    return;
                }
            }
        });
        echoThread.setDaemon(true);
        echoThread.start();

        try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
            client.getOutputStream().write(UdpRelayProtocol.auth("secret"));
            client.getOutputStream().flush();

            long sessionId = 1234L;
            byte[] payload = "ping".getBytes();
            client.getOutputStream().write(UdpRelayProtocol.datagram(
                    sessionId, address, echo.getLocalPort(), payload
            ));
            client.getOutputStream().flush();

            Frame reply = UdpRelayProtocol.readFrame(client.getInputStream());
            assertEquals(UdpRelayProtocol.TYPE_DATAGRAM, reply.type);
            Datagram datagram = UdpRelayProtocol.decodeDatagram(reply.payload);
            assertEquals(sessionId, datagram.sessionId);
            assertArrayEquals(address, datagram.address);
            assertEquals(echo.getLocalPort(), datagram.port);
            assertArrayEquals(payload, datagram.payload);

            client.getOutputStream().write(UdpRelayProtocol.close(sessionId));
            client.getOutputStream().flush();
        } finally {
            server.close();
            echo.close();
        }

        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
