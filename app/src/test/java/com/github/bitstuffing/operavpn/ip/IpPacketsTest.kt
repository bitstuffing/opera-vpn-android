package com.github.bitstuffing.operavpn.ip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpPacketsTest {

    private val client = byteArrayOf(192.toByte(), 0, 2, 2)
    private val server = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    @Test
    fun `checksum de cabecera IPv4 es correcto`() {
        val packet = buildIpv4(PROTOCOL_TCP, client, server, 7, ByteArray(0))
        assertEquals(0, checksum(packet, 0, 20))
        assertEquals(0x4500, u16(packet, 0))
        assertEquals(20, u16(packet, 2))
    }

    @Test
    fun `parsea un paquete IPv4 construido`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = buildIpv4(PROTOCOL_UDP, client, server, 9, payload)

        val ip = parseIpv4(packet, packet.size)
        assertNotNull(ip)
        ip!!
        assertEquals(PROTOCOL_UDP, ip.protocol)
        assertEquals(ipToString(client), ipToString(ip.source))
        assertEquals(ipToString(server), ipToString(ip.destination))
        assertEquals(payload.size, ip.payloadLength)
    }

    @Test
    fun `parsea un segmento TCP con payload`() {
        val payload = "GET / HTTP/1.1\r\n".toByteArray()
        val packet = buildTcp(
            sourceIp = client,
            destinationIp = server,
            sourcePort = 45678,
            destinationPort = 443,
            sequence = 1234,
            acknowledgment = 5678,
            flags = PSH or ACK,
            identification = 11,
            payload = payload
        )

        val ip = parseIpv4(packet, packet.size)!!
        val tcp = parseTcp(ip)
        assertNotNull(tcp)
        tcp!!
        assertEquals(45678, tcp.sourcePort)
        assertEquals(443, tcp.destinationPort)
        assertEquals(1234L, tcp.sequence)
        assertEquals(5678L, tcp.acknowledgment)
        assertEquals(PSH or ACK, tcp.flags)
        assertEquals(payload.size, tcp.payloadLength)

        val segmentLength = 20 + payload.size
        val pseudo = ByteArray(12 + segmentLength)
        client.copyInto(pseudo, 0)
        server.copyInto(pseudo, 4)
        pseudo[9] = PROTOCOL_TCP.toByte()
        putU16(pseudo, 10, segmentLength)
        ip.bytes.copyInto(pseudo, 12, ip.payloadOffset, ip.payloadOffset + segmentLength)
        assertEquals(0, checksum(pseudo, 0, pseudo.size))
    }

    @Test
    fun `construye ICMP puerto inalcanzable con checksum valido`() {
        val original = buildUdp(
            sourceIp = client,
            destinationIp = server,
            sourcePort = 12345,
            destinationPort = 443,
            identification = 5,
            payload = byteArrayOf(1, 2, 3)
        )
        val reply = buildIcmpPortUnreachable(original, original.size, server, client, 6)
        val ip = parseIpv4(reply, reply.size)
        assertNotNull(ip)
        ip!!
        assertEquals(PROTOCOL_ICMP, ip.protocol)
        assertEquals(0, checksum(ip.bytes, ip.payloadOffset, ip.payloadLength))
        assertEquals(3, u8(ip.bytes, ip.payloadOffset))
        assertEquals(3, u8(ip.bytes, ip.payloadOffset + 1))

        for (index in 0 until minOf(original.size, 28)) {
            assertEquals(original[index], ip.bytes[ip.payloadOffset + 8 + index])
        }
    }

    @Test
    fun `comparacion de secuencias TCP con wrap`() {
        assertTrue(advance(0xffff_ffffL, 1) == 0L)
        assertTrue(sequenceBefore(0xffff_fffeL, 3L))
        assertTrue(!sequenceBefore(3L, 0xffff_fffeL))
    }
}
