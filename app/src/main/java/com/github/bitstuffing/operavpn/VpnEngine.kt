package com.github.bitstuffing.operavpn

import android.util.Log
import com.github.bitstuffing.operavpn.ip.ACK
import com.github.bitstuffing.operavpn.ip.FIN
import com.github.bitstuffing.operavpn.ip.advance
import com.github.bitstuffing.operavpn.ip.PSH
import com.github.bitstuffing.operavpn.ip.PROTOCOL_ICMP
import com.github.bitstuffing.operavpn.ip.PROTOCOL_TCP
import com.github.bitstuffing.operavpn.ip.PROTOCOL_UDP
import com.github.bitstuffing.operavpn.ip.RST
import com.github.bitstuffing.operavpn.ip.SYN
import com.github.bitstuffing.operavpn.ip.buildTcp
import com.github.bitstuffing.operavpn.ip.buildUdp
import com.github.bitstuffing.operavpn.ip.ipToString
import com.github.bitstuffing.operavpn.ip.mssOption
import com.github.bitstuffing.operavpn.ip.parseIpv4
import com.github.bitstuffing.operavpn.ip.parseTcp
import com.github.bitstuffing.operavpn.ip.parseUdpPorts
import com.github.bitstuffing.operavpn.ip.sequenceBefore
import com.github.bitstuffing.operavpn.dns.DnsFilter
import com.github.bitstuffing.operavpn.dns.DnsLog
import com.github.bitstuffing.operavpn.dns.DnsPacket
import com.github.bitstuffing.operavpn.legacy.OperaSeClient
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Small user-space TCP/IP stack for the TUN interface of VpnService.
 *
 * TCP is relayed through the authenticated Opera CONNECT tunnel. DNS uses
 * DNS-over-HTTPS in that tunnel. Other UDP is sent through an in-app local
 * relay with protected sockets, like RethinkDNS/Orbot, so it exits directly
 * instead of looping back into the TUN.
 */
internal class VpnEngine(
    tun: android.os.ParcelFileDescriptor,
    private val proxy: OperaProxy,
    private val connection: OperaSeClient.OperaConnection,
    private val localRelay: LocalUdpRelay,
    private val protectFd: (Int) -> Boolean,
    private val onError: (Throwable) -> Unit
) : Closeable {

    private data class TcpKey(
        val clientIp: String,
        val clientPort: Int,
        val serverIp: String,
        val serverPort: Int
    )

    private data class UdpKey(
        val clientIp: String,
        val clientPort: Int,
        val destinationIp: String,
        val destinationPort: Int
    )

    private data class UdpSession(
        val key: UdpKey,
        val id: Long,
        val clientIp: ByteArray,
        val clientPort: Int,
        val destinationIp: ByteArray,
        val destinationPort: Int,
        var lastActivity: Long = System.currentTimeMillis()
    )

    private data class PendingSegment(
        val sequence: Long,
        val data: ByteArray,
        var sentAt: Long
    ) {
        val end: Long get() = advance(sequence, data.size.toLong())
    }

    private enum class TcpState { SYN_RECEIVED, CONNECTING, ESTABLISHED }

    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()
    private val ids = AtomicInteger(1)
    private val random = SecureRandom()
    private val running = AtomicBoolean(true)
    private val icmpDropped = AtomicLong()
    private val ticks = AtomicLong()

    private val tcpSessions = ConcurrentHashMap<TcpKey, TcpSession>()
    private val udpByKey = ConcurrentHashMap<UdpKey, UdpSession>()
    private val udpById = ConcurrentHashMap<Long, UdpSession>()
    private val dns = DnsOverHttps(
        tunnel = SocketTunnel { host, port -> proxy.openTunnel(connection, host, port) },
        onEvent = { event -> Log.d(TAG, event) }
    )
    // An unbounded queue with real workers: DNS queries are never silently
    // discarded when a page fires a burst of lookups.
    private val dnsExecutor = Executors.newFixedThreadPool(DOH_THREADS) { runnable ->
        Thread(runnable, "opera-vpn-doh").apply { isDaemon = true }
    }
    private val relay = UdpRelayClient(
        openTunnel = TunnelOpener { Socket("127.0.0.1", localRelay.port) },
        onDatagram = { id, address, port, payload -> handleRelayDatagram(id, address, port, payload) },
        onError = onError
    )
    private val icmp: IcmpEcho? = try {
        IcmpEcho.create(protectFd) { packet -> writePacket(packet) }
    } catch (e: Throwable) {
        Log.w(TAG, "ICMP echo no disponible en este dispositivo", e)
        null
    }

    private val readerThread = Thread(::readLoop, "opera-vpn-tun-reader")
    private val watchdogThread = Thread(::watchdogLoop, "opera-vpn-watchdog")

    fun start() {
        localRelay.start()
        relay.connect()
        icmp?.start()
        readerThread.start()
        watchdogThread.start()
    }

    private fun readLoop() {
        val buffer = ByteArray(32 * 1024)
        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running.get()) onError(e)
                break
            }
            if (length <= 0) continue
            // A TUN read is a packet leaving the device.
            TrafficStats.onUpload(length)
            try {
                handlePacket(buffer, length)
            } catch (e: Exception) {
                Log.w(TAG, "Paquete descartado", e)
            }
        }
    }

    private fun handlePacket(bytes: ByteArray, length: Int) {
        val ip = parseIpv4(bytes, length) ?: return
        val clientIp = ip.source
        val serverIp = ip.destination

        when (ip.protocol) {
            PROTOCOL_TCP -> {
                val tcp = parseTcp(ip) ?: return
                val key = TcpKey(
                    ipToString(clientIp), tcp.sourcePort,
                    ipToString(serverIp), tcp.destinationPort
                )

                val isSyn = tcp.flags and SYN != 0 && tcp.flags and ACK == 0
                if (isSyn) {
                    val existing = tcpSessions[key]
                    if (existing != null) {
                        existing.resendSynAck()
                    } else {
                        val session = TcpSession(
                            key, clientIp, serverIp,
                            tcp.sourcePort, tcp.destinationPort, tcp.sequence
                        )
                        tcpSessions[key] = session
                        session.sendSynAck()
                    }
                    return
                }

                tcpSessions[key]?.handle(tcp, bytes)
                    ?: sendRst(clientIp, serverIp, tcp.sourcePort, tcp.destinationPort, tcp.sequence)
            }

            PROTOCOL_UDP -> {
                val ports = parseUdpPorts(ip) ?: return
                val payload = ip.bytes.copyOfRange(
                    ip.payloadOffset + 8,
                    ip.payloadOffset + ip.payloadLength
                )
                if (ports.second == DNS_PORT) {
                    val query = payload
                    val clientPort = ports.first
                    dnsExecutor.execute {
                        val domain = DnsPacket.questionName(query) ?: "?"
                        val type = DnsPacket.questionType(query)
                        val reason = DnsFilter.reason(domain)

                        // Answer locally with NXDOMAIN for blocked queries. No
                        // packet leaves the device, so there is nothing to leak.
                        if (DnsFilter.decide(domain) == DnsFilter.Decision.BLOCK) {
                            val nx = DnsPacket.nxDomain(query)
                            if (nx != null) {
                                DnsLog.add(domain, type, DnsLog.DECISION_BLOCKED, reason)
                                writePacket(
                                    buildUdp(
                                        sourceIp = serverIp,
                                        destinationIp = clientIp,
                                        sourcePort = DNS_PORT,
                                        destinationPort = clientPort,
                                        identification = nextId(),
                                        payload = nx
                                    )
                                )
                                return@execute
                            }
                        }

                        val answer = dns.query(query)
                        if (answer != null) {
                            DnsLog.add(domain, type, DnsLog.DECISION_ALLOWED, reason)
                            writePacket(
                                buildUdp(
                                    sourceIp = serverIp,
                                    destinationIp = clientIp,
                                    sourcePort = DNS_PORT,
                                    destinationPort = clientPort,
                                    identification = nextId(),
                                    payload = answer
                                )
                            )
                        } else {
                            // DoH unavailable: use the protected local relay so
                            // DNS still resolves instead of failing silently.
                            DnsLog.add(
                                domain, type, DnsLog.DECISION_ERROR,
                                "${dns.lastError} → relay local"
                            )
                            val session = udpSession(clientIp, clientPort, serverIp, DNS_PORT)
                            try {
                                relay.send(session.id, serverIp, DNS_PORT, query)
                                session.lastActivity = System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.w(TAG, "No se pudo reenviar DNS por el relay local", e)
                            }
                        }
                    }
                } else {
                    val session = udpSession(clientIp, ports.first, serverIp, ports.second)
                    try {
                        relay.send(session.id, session.destinationIp, session.destinationPort, payload)
                        session.lastActivity = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo enviar UDP por el relay local", e)
                    }
                }
            }

            PROTOCOL_ICMP -> {
                if (icmp != null) {
                    icmp.sendEcho(ip, bytes)
                } else {
                    icmpDropped.incrementAndGet()
                }
            }
        }
    }

    private fun udpSession(
        clientIp: ByteArray,
        clientPort: Int,
        destinationIp: ByteArray,
        destinationPort: Int
    ): UdpSession {
        val key = UdpKey(
            ipToString(clientIp), clientPort,
            ipToString(destinationIp), destinationPort
        )
        udpByKey[key]?.let { return it }

        val id = newUdpSessionId()
        val session = UdpSession(
            key = key,
            id = id,
            clientIp = clientIp.copyOf(),
            clientPort = clientPort,
            destinationIp = destinationIp.copyOf(),
            destinationPort = destinationPort
        )
        udpByKey[key] = session
        udpById[id] = session
        return session
    }

    private fun newUdpSessionId(): Long {
        while (true) {
            val id = random.nextLong() and Long.MAX_VALUE
            if (id != 0L && !udpById.containsKey(id)) return id
        }
    }

    private fun handleRelayDatagram(
        sessionId: Long,
        sourceIp: ByteArray,
        sourcePort: Int,
        payload: ByteArray
    ) {
        val session = udpById[sessionId] ?: return
        session.lastActivity = System.currentTimeMillis()
        writePacket(
            buildUdp(
                sourceIp = sourceIp,
                destinationIp = session.clientIp,
                sourcePort = sourcePort,
                destinationPort = session.clientPort,
                identification = nextId(),
                payload = payload
            )
        )
    }

    private fun closeUdpSession(session: UdpSession) {
        if (udpById.remove(session.id, session)) {
            udpByKey.remove(session.key, session)
            relay.closeSession(session.id)
        }
    }

    private fun writePacket(packet: ByteArray) {
        synchronized(writeLock) {
            if (running.get()) {
                output.write(packet)
                output.flush()
                // A TUN write is a packet arriving from the network (or a
                // locally synthesised reply), counted as download.
                TrafficStats.onDownload(packet.size)
            }
        }
    }

    private fun nextId(): Int = ids.getAndIncrement() and 0xffff

    private fun watchdogLoop() {
        while (running.get()) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
                val now = System.currentTimeMillis()
                tcpSessions.values.forEach { it.onTick(now) }
                udpById.values.forEach { session ->
                    if (now - session.lastActivity > UDP_TIMEOUT_MS) closeUdpSession(session)
                }
                if (ticks.incrementAndGet() % (DIAGNOSTIC_INTERVAL_MS / WATCHDOG_INTERVAL_MS) == 0L) {
                    Log.i(
                        TAG,
                        "Diagnóstico: ${dns.diagnostics()} · tcp=${tcpSessions.size} " +
                            "udp=${udpById.size} icmpDescartados=${icmpDropped.get()} " +
                            "descargas=${TrafficStats.formatBytes(TrafficStats.downTotal())} " +
                            "subidas=${TrafficStats.formatBytes(TrafficStats.upTotal())} " +
                            "(${icmp?.stats() ?: "sin ICMP"})"
                    )
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Error en watchdog", e)
            }
        }
    }

    private fun sendRst(
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        sequence: Long
    ) {
        writePacket(
            buildTcp(
                sourceIp = serverIp,
                destinationIp = clientIp,
                sourcePort = serverPort,
                destinationPort = clientPort,
                sequence = 0,
                acknowledgment = advance(sequence, 1),
                flags = RST or ACK,
                identification = nextId()
            )
        )
    }

    private inner class TcpSession(
        private val key: TcpKey,
        private val clientIp: ByteArray,
        private val serverIp: ByteArray,
        private val clientPort: Int,
        private val serverPort: Int,
        peerInitialSequence: Long
    ) {
        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private val pending = LinkedBlockingQueue<ByteArray>()
        private val unacked = ConcurrentLinkedQueue<PendingSegment>()

        private var state = TcpState.SYN_RECEIVED
        private var synAcknowledged = false
        private var localSequence = randomSequence()
        private var peerSequence = advance(peerInitialSequence, 1)
        private var peerAck = localSequence
        private var peerSentFin = false
        private var weSentFin = false
        private var lastActivity = System.currentTimeMillis()

        private var upstream: Socket? = null
        private var upstreamOutput: OutputStream? = null
        private var writer: Thread? = null

        fun sendSynAck() {
            emit(SYN or ACK, ByteArray(0), 0, mssOption(MSS))
        }

        fun resendSynAck() {
            if (!synAcknowledged) sendSynAck()
        }

        fun handle(segment: com.github.bitstuffing.operavpn.ip.TcpSegment, bytes: ByteArray) {
            lastActivity = System.currentTimeMillis()
            var ackNeeded = false
            var startUpstream = false
            var closeSession = false

            if (segment.flags and RST != 0) {
                close(sendRst = false)
                return
            }

            synchronized(lock) {
                if (segment.flags and ACK != 0) {
                    val ack = segment.acknowledgment
                    if (!synAcknowledged && ack == advance(localSequence, 1)) {
                        synAcknowledged = true
                        localSequence = advance(localSequence, 1)
                        peerAck = ack
                        if (state == TcpState.SYN_RECEIVED) {
                            state = TcpState.CONNECTING
                            startUpstream = true
                        }
                    } else if (synAcknowledged && sequenceBefore(peerAck, ack)) {
                        peerAck = ack
                        pruneAcknowledged()
                    }
                }

                if (segment.payloadLength > 0) {
                    val payload = bytes.copyOfRange(
                        segment.payloadOffset,
                        segment.payloadOffset + segment.payloadLength
                    )
                    if (segment.sequence == peerSequence) {
                        peerSequence = advance(peerSequence, payload.size.toLong())
                        pending.put(payload)
                    }
                    ackNeeded = true
                }

                if (segment.flags and FIN != 0) {
                    if (!peerSentFin) {
                        peerSequence = advance(peerSequence, 1)
                        peerSentFin = true
                    }
                    ackNeeded = true
                    if (weSentFin) closeSession = true
                }
            }

            if (startUpstream) startUpstream()
            if (ackNeeded) emit(ACK, ByteArray(0), 0)
            if (closeSession) close(sendRst = false)
        }

        private fun startUpstream() {
            Thread({
                try {
                    val socket = proxy.openTunnel(
                        connection,
                        ipToString(serverIp),
                        serverPort
                    )
                    synchronized(lock) {
                        upstream = socket
                        upstreamOutput = BufferedOutputStream(socket.outputStream)
                        state = TcpState.ESTABLISHED
                    }
                    writer = Thread(::writeLoop, "opera-vpn-tcp-write").also { it.start() }
                    readUpstream(socket)
                    finishFromUpstream()
                } catch (e: Exception) {
                    Log.w(TAG, "Tunnel TCP fallido", e)
                    close(sendRst = true)
                }
            }, "opera-vpn-tcp-connect").start()
        }

        private fun writeLoop() {
            try {
                while (running.get() && !closed.get()) {
                    val data = pending.take()
                    val stream = upstreamOutput ?: break
                    stream.write(data)
                    stream.flush()
                }
            } catch (_: InterruptedException) {
                // Closed by VpnEngine.close().
            } catch (e: IOException) {
                Log.w(TAG, "Escritura en túnel fallida", e)
                close(sendRst = true)
            }
        }

        private fun readUpstream(socket: Socket) {
            val buffer = ByteArray(16 * 1024)
            val inputStream = socket.getInputStream()
            while (running.get() && !closed.get()) {
                val count = inputStream.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    val data = buffer.copyOfRange(0, count)
                    var offset = 0
                    while (offset < data.size) {
                        val size = minOf(MSS, data.size - offset)
                        val chunk = data.copyOfRange(offset, offset + size)
                        val sequence = emit(PSH or ACK, chunk, chunk.size)
                        synchronized(lock) {
                            unacked.add(PendingSegment(sequence, chunk, System.currentTimeMillis()))
                        }
                        offset += size
                    }
                }
            }
        }

        private fun finishFromUpstream() {
            if (closed.get()) return
            synchronized(lock) {
                if (!weSentFin) {
                    weSentFin = true
                    emit(FIN or ACK, ByteArray(0), 1)
                }
            }
            close(sendRst = false)
        }

        private fun emit(
            flags: Int,
            payload: ByteArray,
            sequenceIncrement: Int,
            options: ByteArray = ByteArray(0)
        ): Long {
            return synchronized(lock) {
                val sequence = localSequence
                val packet = buildTcp(
                    sourceIp = serverIp,
                    destinationIp = clientIp,
                    sourcePort = serverPort,
                    destinationPort = clientPort,
                    sequence = sequence,
                    acknowledgment = peerSequence,
                    flags = flags,
                    window = 65_535,
                    identification = nextId(),
                    payload = payload,
                    options = options
                )
                writePacket(packet)
                localSequence = advance(localSequence, sequenceIncrement.toLong())
                sequence
            }
        }

        private fun pruneAcknowledged() {
            while (true) {
                val first = unacked.peek() ?: break
                if (!sequenceBefore(first.end, peerAck) && first.end != peerAck) break
                unacked.poll()
            }
        }

        fun onTick(now: Long) {
            if (closed.get()) return
            if (now - lastActivity > IDLE_TIMEOUT_MS) {
                close(sendRst = true)
                return
            }
            synchronized(lock) {
                unacked.forEach { segment ->
                    if (!sequenceBefore(segment.end, peerAck) &&
                        segment.end != peerAck &&
                        now - segment.sentAt > RETRANSMIT_MS
                    ) {
                        writePacket(
                            buildTcp(
                                sourceIp = serverIp,
                                destinationIp = clientIp,
                                sourcePort = serverPort,
                                destinationPort = clientPort,
                                sequence = segment.sequence,
                                acknowledgment = peerSequence,
                                flags = PSH or ACK,
                                identification = nextId(),
                                payload = segment.data
                            )
                        )
                        segment.sentAt = now
                    }
                }
            }
        }

        fun close(sendRst: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            if (sendRst) emit(RST or ACK, ByteArray(0), 0)
            upstream?.close()
            writer?.interrupt()
            tcpSessions.remove(key, this)
        }
    }

    override fun close() {
        running.set(false)
        tcpSessions.values.toList().forEach { it.close(sendRst = false) }
        udpById.values.toList().forEach { closeUdpSession(it) }
        relay.close()
        localRelay.close()
        icmp?.close()
        dns.close()
        dnsExecutor.shutdownNow()
        readerThread.interrupt()
        watchdogThread.interrupt()
        try {
            input.close()
        } catch (_: IOException) {
        }
        try {
            output.close()
        } catch (_: IOException) {
        }
    }

    private fun randomSequence(): Long = random.nextInt().toLong() and 0xffff_ffffL

    companion object {
        private const val TAG = "VpnEngine"
        private const val DNS_PORT = 53
        private const val DOH_THREADS = 6
        private const val WATCHDOG_INTERVAL_MS = 250L
        private const val DIAGNOSTIC_INTERVAL_MS = 30_000L
        private const val MSS = 1360
        private const val RETRANSMIT_MS = 400L
        private const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L
        private const val UDP_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
