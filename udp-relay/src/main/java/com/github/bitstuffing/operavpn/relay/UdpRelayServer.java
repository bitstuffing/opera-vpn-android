package com.github.bitstuffing.operavpn.relay;

import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol;
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol.Datagram;
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol.Frame;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small UDP relay intended to be reached from the Android app through Opera's
 * HTTP CONNECT tunnel. The outer tunnel carries a framed TCP protocol; this
 * process performs the real UDP sends and returns replies as frames.
 */
public final class UdpRelayServer implements Closeable {
    private final ServerSocket serverSocket;
    private final String token;
    private final int maxSessions;
    private final long idleTimeoutMs;
    private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "udp-relay-client");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);

    public UdpRelayServer(InetAddress bindAddress, int port, String token,
                          int maxSessions, long idleTimeoutMs) throws IOException {
        this.serverSocket = new ServerSocket(port, 128, bindAddress);
        this.token = token;
        this.maxSessions = maxSessions;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    /** Accepts clients until the server is closed. */
    public void start() throws IOException {
        System.out.println("UDP relay listening on " + serverSocket.getLocalSocketAddress());
        while (running.get()) {
            final Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) System.err.println("Accept failed: " + e.getMessage());
                break;
            }
            ClientHandler handler = new ClientHandler(client);
            handlers.add(handler);
            workers.execute(handler);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
        for (ClientHandler handler : handlers.toArray(new ClientHandler[0])) {
            handler.close();
        }
        workers.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        String bind = environment("UDP_RELAY_BIND", "0.0.0.0");
        int port = Integer.parseInt(environment("UDP_RELAY_PORT", "1080"));
        String token = environment("UDP_RELAY_TOKEN", "change-me");
        int maxSessions = Integer.parseInt(environment("UDP_RELAY_MAX_SESSIONS", "512"));
        long timeoutSeconds = Long.parseLong(environment("UDP_RELAY_TIMEOUT", "120"));

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bind":
                    bind = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--token":
                    token = args[++i];
                    break;
                case "--max-sessions":
                    maxSessions = Integer.parseInt(args[++i]);
                    break;
                case "--timeout":
                    timeoutSeconds = Long.parseLong(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        UdpRelayServer server = new UdpRelayServer(
                InetAddress.getByName(bind), port, token, maxSessions, timeoutSeconds * 1000L
        );
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "udp-relay-shutdown"));
        server.start();
    }

    private static String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private final class ClientHandler implements Runnable, Closeable {
        private final Socket client;
        private final Object writeLock = new Object();
        private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Thread watchdog;

        private ClientHandler(Socket client) {
            this.client = client;
            this.watchdog = new Thread(this::watchdogLoop, "udp-relay-watchdog");
            this.watchdog.setDaemon(true);
            this.watchdog.start();
        }

        @Override
        public void run() {
            try {
                client.setTcpNoDelay(true);
                Frame first = UdpRelayProtocol.readFrame(client.getInputStream());
                byte[] expected = token.getBytes(StandardCharsets.UTF_8);
                if (first.type != UdpRelayProtocol.TYPE_AUTH
                        || !MessageDigest.isEqual(expected, first.payload)) {
                    writeFrame(UdpRelayProtocol.error("authentication failed"));
                    return;
                }

                while (active.get()) {
                    Frame frame = UdpRelayProtocol.readFrame(client.getInputStream());
                    switch (frame.type) {
                        case UdpRelayProtocol.TYPE_DATAGRAM:
                            handleDatagram(frame.payload);
                            break;
                        case UdpRelayProtocol.TYPE_CLOSE:
                            long closedId = UdpRelayProtocol.decodeSessionId(frame.payload);
                            Session old = sessions.remove(closedId);
                            if (old != null) old.close();
                            break;
                        default:
                            writeFrame(UdpRelayProtocol.error("unexpected frame type"));
                            break;
                    }
                }
            } catch (IOException e) {
                if (active.get()) System.err.println("Client disconnected: " + e.getMessage());
            } finally {
                close();
                handlers.remove(this);
            }
        }

        private void handleDatagram(byte[] payload) throws IOException {
            Datagram datagram = UdpRelayProtocol.decodeDatagram(payload);
            Session session;
            synchronized (sessions) {
                session = sessions.get(datagram.sessionId);
                if (session == null) {
                    if (sessions.size() >= maxSessions) {
                        writeFrame(UdpRelayProtocol.error("too many UDP sessions"));
                        return;
                    }
                    session = new Session(datagram.sessionId, datagram.address, datagram.port);
                    sessions.put(datagram.sessionId, session);
                }
            }
            session.touch();
            try {
                session.send(datagram.payload);
            } catch (IOException e) {
                session.close();
                throw e;
            }
        }

        private void writeFrame(byte[] frame) throws IOException {
            synchronized (writeLock) {
                if (!active.get()) throw new IOException("client closed");
                OutputStream output = client.getOutputStream();
                output.write(frame);
                output.flush();
            }
        }

        private void watchdogLoop() {
            while (active.get()) {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    break;
                }
                long now = System.currentTimeMillis();
                for (Session session : sessions.values().toArray(new Session[0])) {
                    if (now - session.lastActivity > idleTimeoutMs) session.close();
                }
            }
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) return;
            for (Session session : sessions.values().toArray(new Session[0])) {
                session.close();
            }
            try {
                client.close();
            } catch (IOException ignored) {
                // Best effort.
            }
            watchdog.interrupt();
        }

        private final class Session implements Closeable {
            private final long id;
            private final DatagramSocket socket;
            private final Thread reader;
            private final AtomicBoolean sessionActive = new AtomicBoolean(true);
            private volatile long lastActivity = System.currentTimeMillis();

            private Session(long id, byte[] address, int port) throws IOException {
                this.id = id;
                this.socket = new DatagramSocket();
                this.socket.setSoTimeout(1_000);
                this.socket.connect(InetAddress.getByAddress(address), port);
                this.reader = new Thread(this::readLoop, "udp-relay-upstream-" + id);
                this.reader.setDaemon(true);
                this.reader.start();
            }

            private void touch() {
                lastActivity = System.currentTimeMillis();
            }

            private void send(byte[] payload) throws IOException {
                socket.send(new DatagramPacket(payload, payload.length));
            }

            private void readLoop() {
                byte[] buffer = new byte[65_535];
                while (sessionActive.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    } catch (IOException e) {
                        if (sessionActive.get()) System.err.println("UDP socket failed: " + e.getMessage());
                        break;
                    }
                    if (!(packet.getAddress() instanceof Inet4Address) || packet.getLength() == 0) {
                        continue;
                    }
                    byte[] source = packet.getAddress().getAddress();
                    byte[] data = Arrays.copyOfRange(
                            packet.getData(), packet.getOffset(),
                            packet.getOffset() + packet.getLength()
                    );
                    touch();
                    try {
                        writeFrame(UdpRelayProtocol.datagram(id, source, packet.getPort(), data));
                    } catch (IOException e) {
                        break;
                    }
                }
                close();
            }

            @Override
            public void close() {
                if (!sessionActive.compareAndSet(true, false)) return;
                socket.close();
                reader.interrupt();
                sessions.remove(id, this);
            }
        }
    }
}
