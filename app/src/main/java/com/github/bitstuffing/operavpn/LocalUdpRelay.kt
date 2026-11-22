package com.github.bitstuffing.operavpn

import android.util.Log
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal const val LOCAL_UDP_RELAY_TOKEN = "in-app-relay"

/**
 * In-app UDP relay, equivalent to the local proxy pattern used by
 * RethinkDNS/Orbot: VpnEngine talks to it on 127.0.0.1, and it performs real
 * UDP sends through protected DatagramSockets so packets exit directly
 * instead of being routed back into the TUN.
 */
internal class LocalUdpRelay(
    private val protect: (DatagramSocket) -> Boolean,
    private val onError: (Throwable) -> Unit
) : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    private val running = AtomicBoolean(true)
    private val failureReported = AtomicBoolean(false)
    private val clients = mutableSetOf<Client>()
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "opera-vpn-local-udp").apply { isDaemon = true }
    }
    private val acceptThread = Thread(::acceptLoop, "opera-vpn-local-udp-accept").apply {
        isDaemon = true
    }

    fun start() {
        acceptThread.start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "Error aceptando cliente UDP local", e)
                break
            }
            val client = Client(socket)
            synchronized(clients) { clients.add(client) }
            workers.execute(client)
        }
    }

    private fun reportFailure(error: Throwable) {
        if (failureReported.compareAndSet(false, true)) onError(error)
    }

    private inner class Client(private val socket: Socket) : Runnable, Closeable {
        private val writeLock = Any()
        private val sessions = ConcurrentHashMap<Long, Session>()
        private val active = AtomicBoolean(true)
        private val watchdog = Thread(::watchdogLoop, "opera-vpn-local-udp-watchdog").apply {
            isDaemon = true
        }

        override fun run() {
            watchdog.start()
            try {
                socket.tcpNoDelay = true
                val auth = UdpRelayProtocol.readFrame(socket.getInputStream())
                if (auth.type != UdpRelayProtocol.TYPE_AUTH ||
                    UdpRelayProtocol.decodeText(auth.payload) != LOCAL_UDP_RELAY_TOKEN
                ) {
                    writeFrame(UdpRelayProtocol.error("authentication failed"))
                    return
                }

                while (active.get()) {
                    val frame = UdpRelayProtocol.readFrame(socket.getInputStream())
                    when (frame.type) {
                        UdpRelayProtocol.TYPE_DATAGRAM -> {
                            val datagram = UdpRelayProtocol.decodeDatagram(frame.payload)
                            val session = sessions.getOrPut(datagram.sessionId) {
                                Session(datagram.sessionId, datagram.address, datagram.port)
                            }
                            session.touch()
                            session.send(datagram.payload)
                        }
                        UdpRelayProtocol.TYPE_CLOSE -> {
                            sessions.remove(UdpRelayProtocol.decodeSessionId(frame.payload))?.close()
                        }
                        else -> writeFrame(UdpRelayProtocol.error("unexpected frame"))
                    }
                }
            } catch (e: IOException) {
                if (active.get()) Log.w(TAG, "Cliente UDP local desconectado", e)
            } catch (e: Exception) {
                Log.w(TAG, "Trama UDP local inválida", e)
            } finally {
                close()
                synchronized(clients) { clients.remove(this) }
            }
        }

        private fun sendDatagram(
            sessionId: Long,
            address: ByteArray,
            port: Int,
            payload: ByteArray
        ) {
            writeFrame(UdpRelayProtocol.datagram(sessionId, address, port, payload))
        }

        private fun writeFrame(frame: ByteArray) {
            synchronized(writeLock) {
                if (!active.get()) return
                socket.outputStream.write(frame)
                socket.outputStream.flush()
            }
        }

        private fun watchdogLoop() {
            while (active.get()) {
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                sessions.values.toTypedArray().forEach { session ->
                    if (now - session.lastActivity > SESSION_TIMEOUT_MS) session.close()
                }
            }
        }

        override fun close() {
            if (!active.compareAndSet(true, false)) return
            sessions.values.toTypedArray().forEach { it.close() }
            try {
                socket.close()
            } catch (_: IOException) {
                // Best effort.
            }
            watchdog.interrupt()
        }

        private inner class Session(
            private val id: Long,
            address: ByteArray,
            port: Int
        ) : Closeable {
            private val socket = DatagramSocket()
            private val active = AtomicBoolean(true)
            @Volatile
            var lastActivity: Long = System.currentTimeMillis()
                private set
            private val reader = Thread(::readLoop, "opera-vpn-local-udp-$id").apply {
                isDaemon = true
            }

            init {
                try {
                    if (!protect(socket)) throw IOException("VpnService no pudo proteger el socket UDP")
                    socket.soTimeout = 1_000
                    socket.connect(InetAddress.getByAddress(address), port)
                    reader.start()
                } catch (e: Exception) {
                    reportFailure(e)
                    close()
                    throw e
                }
            }

            fun touch() {
                lastActivity = System.currentTimeMillis()
            }

            fun send(payload: ByteArray) {
                socket.send(DatagramPacket(payload, payload.size))
            }

            private fun readLoop() {
                val buffer = ByteArray(UdpRelayProtocol.MAX_DATAGRAM_PAYLOAD)
                while (active.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        if (active.get()) Log.w(TAG, "Error leyendo UDP local", e)
                        break
                    }
                    if (packet.length == 0 || packet.address !is Inet4Address) continue
                    val data = packet.data.copyOfRange(
                        packet.offset, packet.offset + packet.length
                    )
                    touch()
                    try {
                        sendDatagram(
                            id,
                            packet.address.address,
                            packet.port,
                            data
                        )
                    } catch (e: IOException) {
                        break
                    }
                }
                close()
            }

            override fun close() {
                if (!active.compareAndSet(true, false)) return
                socket.close()
                reader.interrupt()
                sessions.remove(id, this)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try {
            server.close()
        } catch (_: IOException) {
            // Best effort.
        }
        synchronized(clients) {
            clients.toList().forEach { it.close() }
            clients.clear()
        }
        workers.shutdownNow()
        acceptThread.interrupt()
    }

    private companion object {
        const val TAG = "LocalUdpRelay"
        const val SESSION_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
