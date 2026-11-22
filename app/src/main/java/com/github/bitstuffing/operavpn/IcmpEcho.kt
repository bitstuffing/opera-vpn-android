package com.github.bitstuffing.operavpn

import android.system.Os
import android.system.OsConstants.AF_INET
import android.system.OsConstants.IPPROTO_ICMP
import android.system.OsConstants.SOCK_DGRAM
import android.util.Log
import com.github.bitstuffing.operavpn.ip.Ipv4Packet
import com.github.bitstuffing.operavpn.ip.PROTOCOL_ICMP
import com.github.bitstuffing.operavpn.ip.buildIpv4
import com.github.bitstuffing.operavpn.ip.checksum
import com.github.bitstuffing.operavpn.ip.putU16
import com.github.bitstuffing.operavpn.ip.u16
import com.github.bitstuffing.operavpn.ip.u8
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Best-effort ICMP echo using an unprivileged Linux ping socket. This exists
 * so `ping 8.8.8.8` is a useful diagnostic: the echo requests are sent through
 * a protected socket, outside the TUN, and the replies are written back into
 * it. If the platform disallows ping sockets, the engine simply keeps running
 * without ICMP.
 */
internal class IcmpEcho private constructor(
    private val fd: FileDescriptor,
    private val onReply: (ByteArray) -> Unit
) : Closeable {

    private val running = AtomicBoolean(true)
    private val pending = ConcurrentHashMap<Int, Echo>()
    private val ids = AtomicInteger(1)
    private val sent = AtomicLong()
    private val received = AtomicLong()
    private val reader = Thread(::readLoop, "opera-vpn-icmp").apply { isDaemon = true }

    private data class Echo(
        val clientId: Int,
        val clientIp: ByteArray,
        val destination: ByteArray
    )

    fun start() {
        reader.start()
    }

    fun stats(): String = "icmp enviados=${sent.get()} recibidos=${received.get()}"

    fun sendEcho(ip: Ipv4Packet, bytes: ByteArray) {
        val icmp = bytes.copyOfRange(ip.payloadOffset, ip.payloadOffset + ip.payloadLength)
        if (icmp.size < 8 || u8(icmp, 0) != TYPE_ECHO_REQUEST) return

        val sequence = u16(icmp, 6)
        pending[sequence] = Echo(u16(icmp, 4), ip.source.copyOf(), ip.destination.copyOf())
        // The kernel fills in the identifier and checksum for ping sockets.
        putU16(icmp, 2, 0)
        putU16(icmp, 4, 0)
        try {
            Os.sendto(fd, icmp, 0, icmp.size, 0, InetAddress.getByAddress(ip.destination), 0)
            sent.incrementAndGet()
        } catch (e: Exception) {
            pending.remove(sequence)
            Log.w(TAG, "No se pudo enviar ICMP echo", e)
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(64 * 1024)
        val sender = InetSocketAddress(0)
        while (running.get()) {
            val count = try {
                Os.recvfrom(fd, buffer, 0, buffer.size, 0, sender)
            } catch (_: Exception) {
                break
            }
            if (count < 8 || u8(buffer, 0) != TYPE_ECHO_REPLY) continue

            val sequence = u16(buffer, 6)
            val echo = pending.remove(sequence) ?: continue
            val reply = buffer.copyOf(count)
            putU16(reply, 2, 0)
            putU16(reply, 4, echo.clientId)
            putU16(reply, 2, checksum(reply, 0, reply.size))

            onReply(
                buildIpv4(
                    protocol = PROTOCOL_ICMP,
                    source = echo.destination,
                    destination = echo.clientIp,
                    identification = ids.getAndIncrement() and 0xffff,
                    payload = reply
                )
            )
            received.incrementAndGet()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        pending.clear()
        try {
            Os.close(fd)
        } catch (_: Exception) {
            // Best effort.
        }
        reader.interrupt()
    }

    companion object {
        private const val TAG = "OperaVpnIcmp"
        private const val TYPE_ECHO_REQUEST = 8
        private const val TYPE_ECHO_REPLY = 0

        fun create(protectFd: (Int) -> Boolean, onReply: (ByteArray) -> Unit): IcmpEcho {
            val fd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
            try {
                val number = fdNumber(fd)
                    ?: throw IOException("No se pudo obtener el descriptor ICMP")
                if (!protectFd(number)) {
                    throw IOException("VpnService no pudo proteger el socket ICMP")
                }
                return IcmpEcho(fd, onReply)
            } catch (e: Exception) {
                try {
                    Os.close(fd)
                } catch (_: Exception) {
                    // Preserve the original error.
                }
                throw e
            }
        }

        private fun fdNumber(fd: FileDescriptor): Int? = try {
            val method = FileDescriptor::class.java.getDeclaredMethod("getInt$")
            method.isAccessible = true
            method.invoke(fd) as Int
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo leer el descriptor ICMP", e)
            null
        }
    }
}
