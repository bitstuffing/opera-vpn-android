package com.github.bitstuffing.operavpn.ip

import java.net.InetAddress

internal const val PROTOCOL_TCP: Int = 6
internal const val PROTOCOL_UDP: Int = 17
internal const val PROTOCOL_ICMP: Int = 1

internal const val FIN: Int = 1
internal const val SYN: Int = 2
internal const val RST: Int = 4
internal const val PSH: Int = 8
internal const val ACK: Int = 16

internal data class Ipv4Packet(
    val bytes: ByteArray,
    val headerLength: Int,
    val totalLength: Int,
    val identification: Int,
    val protocol: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int
)

internal data class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Long,
    val acknowledgment: Long,
    val flags: Int,
    val window: Int,
    val payloadOffset: Int,
    val payloadLength: Int
)

internal fun u8(bytes: ByteArray, index: Int): Int =
    bytes[index].toInt() and 0xff

internal fun u16(bytes: ByteArray, index: Int): Int =
    (u8(bytes, index) shl 8) or u8(bytes, index + 1)

internal fun u32(bytes: ByteArray, index: Int): Long =
    (u16(bytes, index).toLong() shl 16) or u16(bytes, index + 2).toLong()

internal fun putU16(bytes: ByteArray, index: Int, value: Int) {
    bytes[index] = (value ushr 8).toByte()
    bytes[index + 1] = value.toByte()
}

internal fun putU32(bytes: ByteArray, index: Int, value: Long) {
    putU16(bytes, index, (value ushr 16).toInt())
    putU16(bytes, index + 2, value.toInt())
}

internal fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        sum += u16(bytes, index).toLong()
        index += 2
    }
    if (index < end) sum += u8(bytes, index).toLong() shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
    return (sum.inv() and 0xffffL).toInt()
}

internal fun parseIpv4(bytes: ByteArray, length: Int): Ipv4Packet? {
    if (length < 20) return null
    val versionAndIhl = u8(bytes, 0)
    if (versionAndIhl ushr 4 != 4) return null

    val headerLength = (versionAndIhl and 0x0f) * 4
    if (headerLength < 20 || headerLength > length) return null

    val totalLength = u16(bytes, 2)
    if (totalLength < headerLength || totalLength > length) return null

    return Ipv4Packet(
        bytes = bytes,
        headerLength = headerLength,
        totalLength = totalLength,
        identification = u16(bytes, 4),
        protocol = u8(bytes, 9),
        source = bytes.copyOfRange(12, 16),
        destination = bytes.copyOfRange(16, 20),
        payloadOffset = headerLength,
        payloadLength = totalLength - headerLength
    )
}

internal fun parseTcp(packet: Ipv4Packet): TcpSegment? {
    if (packet.payloadLength < 20) return null
    val base = packet.payloadOffset
    val dataOffset = (u8(packet.bytes, base + 12) ushr 4) * 4
    if (dataOffset < 20 || dataOffset > packet.payloadLength) return null

    return TcpSegment(
        sourcePort = u16(packet.bytes, base),
        destinationPort = u16(packet.bytes, base + 2),
        sequence = u32(packet.bytes, base + 4),
        acknowledgment = u32(packet.bytes, base + 8),
        flags = u8(packet.bytes, base + 13),
        window = u16(packet.bytes, base + 14),
        payloadOffset = base + dataOffset,
        payloadLength = packet.payloadLength - dataOffset
    )
}

internal fun parseUdpPorts(packet: Ipv4Packet): Pair<Int, Int>? {
    if (packet.payloadLength < 8) return null
    val base = packet.payloadOffset
    return u16(packet.bytes, base) to u16(packet.bytes, base + 2)
}

internal fun buildIpv4(
    protocol: Int,
    source: ByteArray,
    destination: ByteArray,
    identification: Int,
    payload: ByteArray
): ByteArray {
    require(source.size == 4 && destination.size == 4)
    val totalLength = 20 + payload.size
    require(totalLength <= 65535)

    val out = ByteArray(totalLength)
    out[0] = 0x45
    putU16(out, 2, totalLength)
    putU16(out, 4, identification)
    putU16(out, 6, 0x4000) // Don't fragment.
    out[8] = 64
    out[9] = protocol.toByte()
    source.copyInto(out, 12)
    destination.copyInto(out, 16)
    putU16(out, 10, checksum(out, 0, 20))
    payload.copyInto(out, 20)
    return out
}

internal fun buildTcp(
    sourceIp: ByteArray,
    destinationIp: ByteArray,
    sourcePort: Int,
    destinationPort: Int,
    sequence: Long,
    acknowledgment: Long,
    flags: Int,
    window: Int = 65_535,
    identification: Int,
    payload: ByteArray = ByteArray(0),
    options: ByteArray = ByteArray(0)
): ByteArray {
    val headerLength = 20 + options.size
    require(headerLength % 4 == 0)
    val tcp = ByteArray(headerLength + payload.size)

    putU16(tcp, 0, sourcePort)
    putU16(tcp, 2, destinationPort)
    putU32(tcp, 4, sequence)
    putU32(tcp, 8, acknowledgment)
    tcp[12] = ((headerLength / 4) shl 4).toByte()
    tcp[13] = flags.toByte()
    putU16(tcp, 14, window)
    putU16(tcp, 16, 0)
    options.copyInto(tcp, 20)
    payload.copyInto(tcp, headerLength)

    val pseudoHeader = ByteArray(12 + tcp.size)
    sourceIp.copyInto(pseudoHeader, 0)
    destinationIp.copyInto(pseudoHeader, 4)
    pseudoHeader[9] = PROTOCOL_TCP.toByte()
    putU16(pseudoHeader, 10, tcp.size)
    tcp.copyInto(pseudoHeader, 12)
    putU16(tcp, 16, checksum(pseudoHeader, 0, pseudoHeader.size))

    return buildIpv4(PROTOCOL_TCP, sourceIp, destinationIp, identification, tcp)
}

internal fun buildUdp(
    sourceIp: ByteArray,
    destinationIp: ByteArray,
    sourcePort: Int,
    destinationPort: Int,
    identification: Int,
    payload: ByteArray
): ByteArray {
    val udp = ByteArray(8 + payload.size)
    putU16(udp, 0, sourcePort)
    putU16(udp, 2, destinationPort)
    putU16(udp, 4, udp.size)
    putU16(udp, 6, 0) // IPv4 allows disabling the UDP checksum.
    payload.copyInto(udp, 8)
    return buildIpv4(PROTOCOL_UDP, sourceIp, destinationIp, identification, udp)
}

internal fun mssOption(mss: Int): ByteArray =
    byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte())

/** Type 3 (destination unreachable), code 3 (port unreachable), with the
 * original IP header plus 8 bytes as required by RFC 792. */
internal fun buildIcmpPortUnreachable(
    original: ByteArray,
    originalLength: Int,
    sourceIp: ByteArray,
    destinationIp: ByteArray,
    identification: Int
): ByteArray {
    val quoted = minOf(originalLength, 28)
    val icmp = ByteArray(8 + quoted)
    icmp[0] = 3
    icmp[1] = 3
    putU16(icmp, 4, 0)
    original.copyInto(icmp, 8, 0, quoted)
    putU16(icmp, 2, checksum(icmp, 0, icmp.size))
    return buildIpv4(PROTOCOL_ICMP, sourceIp, destinationIp, identification, icmp)
}

internal fun ipToString(address: ByteArray): String =
    address.joinToString(".") { (it.toInt() and 0xff).toString() }

internal fun parseIpv4Address(value: String): ByteArray? {
    val resolved = try {
        InetAddress.getByName(value)
    } catch (_: Exception) {
        return null
    }
    return if (resolved.address.size == 4) resolved.address else null
}

internal const val MASK32: Long = 0xffff_ffffL

internal fun advance(sequence: Long, amount: Long): Long =
    (sequence + amount) and MASK32

/** RFC 1982 serial-number comparison for 32-bit TCP sequence numbers. */
internal fun sequenceBefore(a: Long, b: Long): Boolean =
    ((a - b) and MASK32) >= 0x8000_0000L
