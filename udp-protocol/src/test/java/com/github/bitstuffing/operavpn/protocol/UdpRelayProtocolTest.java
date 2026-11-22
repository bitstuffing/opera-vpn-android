package com.github.bitstuffing.operavpn.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class UdpRelayProtocolTest {
    @Test
    void datagramFrameRoundTrip() throws IOException {
        byte[] address = new byte[] {93, (byte) 184, (byte) 216, 34};
        byte[] payload = "dns-query".getBytes();
        byte[] encoded = UdpRelayProtocol.datagram(0x1122334455667788L, address, 53, payload);

        UdpRelayProtocol.Frame frame = UdpRelayProtocol.readFrame(new ByteArrayInputStream(encoded));
        assertEquals(UdpRelayProtocol.TYPE_DATAGRAM, frame.type);

        UdpRelayProtocol.Datagram datagram = UdpRelayProtocol.decodeDatagram(frame.payload);
        assertEquals(0x1122334455667788L, datagram.sessionId);
        assertArrayEquals(address, datagram.address);
        assertEquals(53, datagram.port);
        assertArrayEquals(payload, datagram.payload);
    }

    @Test
    void authAndCloseFrames() throws IOException {
        UdpRelayProtocol.Frame auth = UdpRelayProtocol.readFrame(
                new ByteArrayInputStream(UdpRelayProtocol.auth("token")));
        assertEquals(UdpRelayProtocol.TYPE_AUTH, auth.type);
        assertEquals("token", UdpRelayProtocol.decodeText(auth.payload));

        UdpRelayProtocol.Frame close = UdpRelayProtocol.readFrame(
                new ByteArrayInputStream(UdpRelayProtocol.close(42L)));
        assertEquals(UdpRelayProtocol.TYPE_CLOSE, close.type);
        assertEquals(42L, UdpRelayProtocol.decodeSessionId(close.payload));
    }

    @Test
    void rejectsInvalidAddress() {
        assertThrows(IOException.class,
                () -> UdpRelayProtocol.datagram(1L, new byte[16], 53, new byte[0]));
    }
}
