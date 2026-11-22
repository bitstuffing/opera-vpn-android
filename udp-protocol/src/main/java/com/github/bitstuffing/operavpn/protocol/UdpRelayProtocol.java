package com.github.bitstuffing.operavpn.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Wire format for carrying UDP datagrams inside the Opera CONNECT TCP stream.
 *
 * Frame: [4-byte big-endian length][1-byte type][payload]
 * A DATAGRAM payload is: [8-byte session id][4-byte IPv4][2-byte port][UDP payload]
 */
public final class UdpRelayProtocol {
    public static final byte TYPE_AUTH = 0;
    public static final byte TYPE_DATAGRAM = 1;
    public static final byte TYPE_CLOSE = 2;
    public static final byte TYPE_ERROR = 3;

    public static final int MAX_FRAME_SIZE = 65_535;

    private static final int ADDRESS_LENGTH = 4;
    private static final int DATAGRAM_HEADER = 8 + ADDRESS_LENGTH + 2;

    public static final int MAX_DATAGRAM_PAYLOAD = MAX_FRAME_SIZE - 1 - DATAGRAM_HEADER;

    private UdpRelayProtocol() {
    }

    public static final class Frame {
        public final byte type;
        public final byte[] payload;

        public Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    public static final class Datagram {
        public final long sessionId;
        public final byte[] address;
        public final int port;
        public final byte[] payload;

        public Datagram(long sessionId, byte[] address, int port, byte[] payload) {
            this.sessionId = sessionId;
            this.address = address;
            this.port = port;
            this.payload = payload;
        }
    }

    public static byte[] auth(String token) {
        return frame(TYPE_AUTH, token.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] datagram(long sessionId, byte[] address, int port, byte[] payload)
            throws IOException {
        if (address == null || address.length != ADDRESS_LENGTH) {
            throw new IOException("La dirección UDP debe ser IPv4");
        }
        if (port < 1 || port > 65_535) {
            throw new IOException("Puerto UDP inválido: " + port);
        }
        if (payload == null || payload.length > MAX_DATAGRAM_PAYLOAD) {
            throw new IOException("Payload UDP demasiado grande");
        }

        ByteBuffer buffer = ByteBuffer.allocate(DATAGRAM_HEADER + payload.length);
        buffer.putLong(sessionId);
        buffer.put(address);
        buffer.putShort((short) port);
        buffer.put(payload);
        return frame(TYPE_DATAGRAM, buffer.array());
    }

    public static byte[] close(long sessionId) {
        return frame(TYPE_CLOSE, ByteBuffer.allocate(8).putLong(sessionId).array());
    }

    public static byte[] error(String message) {
        return frame(TYPE_ERROR, message.getBytes(StandardCharsets.UTF_8));
    }

    public static Datagram decodeDatagram(byte[] payload) throws IOException {
        if (payload == null || payload.length < DATAGRAM_HEADER) {
            throw new IOException("Trama UDP truncada");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long sessionId = buffer.getLong();
        byte[] address = new byte[ADDRESS_LENGTH];
        buffer.get(address);
        int port = Short.toUnsignedInt(buffer.getShort());
        byte[] data = new byte[payload.length - DATAGRAM_HEADER];
        buffer.get(data);
        return new Datagram(sessionId, address, port, data);
    }

    public static long decodeSessionId(byte[] payload) throws IOException {
        if (payload == null || payload.length != 8) {
            throw new IOException("Trama CLOSE inválida");
        }
        return ByteBuffer.wrap(payload).getLong();
    }

    public static String decodeText(byte[] payload) {
        return new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8);
    }

    public static Frame readFrame(InputStream input) throws IOException {
        byte[] header = new byte[4];
        readFully(input, header, 0, header.length);
        int length = ByteBuffer.wrap(header).getInt();
        if (length < 1 || length > MAX_FRAME_SIZE) {
            throw new IOException("Longitud de trama inválida: " + length);
        }
        byte[] body = new byte[length];
        readFully(input, body, 0, length);
        return new Frame(body[0], Arrays.copyOfRange(body, 1, body.length));
    }

    private static byte[] frame(byte type, byte[] payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(1 + payload.length);
        body.write(type & 0xff);
        body.write(payload, 0, payload.length);
        byte[] bytes = body.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(4 + bytes.length);
        out.putInt(bytes.length);
        out.put(bytes);
        return out.array();
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length)
            throws IOException {
        while (length > 0) {
            int count = input.read(target, offset, length);
            if (count < 0) {
                throw new EOFException("El túnel UDP se cerró");
            }
            offset += count;
            length -= count;
        }
    }
}
