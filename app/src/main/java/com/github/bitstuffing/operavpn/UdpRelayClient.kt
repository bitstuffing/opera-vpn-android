package com.github.bitstuffing.operavpn

import android.util.Log
import com.github.bitstuffing.operavpn.protocol.UdpRelayProtocol
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface TunnelOpener {
    fun open(): Socket
}

/**
 * Client for the in-app local UDP relay. It carries framed datagrams over a
 * short loopback TCP connection; the relay performs the real protected UDP
 * sends.
 */
internal class UdpRelayClient(
    private val openTunnel: TunnelOpener,
    private val onDatagram: (Long, ByteArray, Int, ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {

    @Volatile
    private var socket: Socket? = null
    private val writeLock = Any()
    private val running = AtomicBoolean(true)
    private val reported = AtomicBoolean(false)
    private val reader = Thread(::readLoop, "opera-vpn-udp-relay-reader").apply {
        isDaemon = true
    }

    fun connect() {
        socket = openSocket()
        reader.start()
    }

    fun send(sessionId: Long, address: ByteArray, port: Int, payload: ByteArray) {
        writeFrame(UdpRelayProtocol.datagram(sessionId, address, port, payload))
    }

    fun closeSession(sessionId: Long) {
        try {
            writeFrame(UdpRelayProtocol.close(sessionId))
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo cerrar la sesión UDP $sessionId", e)
        }
    }

    private fun openSocket(): Socket {
        val opened = openTunnel.open()
        opened.tcpNoDelay = true
        try {
            writeFrame(
                UdpRelayProtocol.auth(LOCAL_UDP_RELAY_TOKEN),
                opened
            )
        } catch (e: Exception) {
            opened.closeQuietly()
            throw e
        }
        return opened
    }

    private fun writeFrame(frame: ByteArray, target: Socket? = socket) {
        val active = target ?: throw IOException("El relays UDP local no está conectado")
        synchronized(writeLock) {
            if (!running.get()) return
            active.outputStream.write(frame)
            active.outputStream.flush()
        }
    }

    private fun readLoop() {
        while (running.get()) {
            val current = socket ?: break
            try {
                val frame = UdpRelayProtocol.readFrame(current.inputStream)
                when (frame.type) {
                    UdpRelayProtocol.TYPE_DATAGRAM -> {
                        val datagram = UdpRelayProtocol.decodeDatagram(frame.payload)
                        onDatagram(
                            datagram.sessionId,
                            datagram.address,
                            datagram.port,
                            datagram.payload
                        )
                    }
                    UdpRelayProtocol.TYPE_ERROR ->
                        Log.w(TAG, "Relay UDP local: ${UdpRelayProtocol.decodeText(frame.payload)}")
                    else -> Log.w(TAG, "Trama UDP inesperada: ${frame.type}")
                }
            } catch (e: IOException) {
                if (!running.get()) break
                Log.w(TAG, "Conexión con el relay UDP local perdida", e)
                current.closeQuietly()
                socket = null
                var attempts = 0
                while (running.get() && socket == null) {
                    if (++attempts > MAX_RECONNECTS) {
                        reportFailure(e)
                        return
                    }
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS)
                        socket = openSocket()
                    } catch (_: InterruptedException) {
                        return
                    } catch (reconnectError: Exception) {
                        Log.w(TAG, "No se pudo reconectar al relay UDP local", reconnectError)
                        if (attempts == MAX_RECONNECTS) reportFailure(reconnectError)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Trama UDP inválida", e)
            }
        }
    }

    private fun reportFailure(error: Throwable) {
        if (reported.compareAndSet(false, true)) onError(error)
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Best effort.
        }
    }

    override fun close() {
        running.set(false)
        socket?.closeQuietly()
        socket = null
        reader.interrupt()
    }

    private companion object {
        const val TAG = "UdpRelayClient"
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
