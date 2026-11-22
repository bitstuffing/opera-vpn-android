package com.github.bitstuffing.operavpn.dns

import java.util.Locale

/**
 * Minimal DNS wire-format reader used at the TUN interception point.
 *
 * Only the question section is needed: the name decides the filter result and
 * an NXDOMAIN answer can be synthesised by echoing that section back.
 */
internal object DnsPacket {

    fun questionName(message: ByteArray): String? {
        if (message.size < 12 || u16(message, 4) < 1) return null
        return readName(message, 12)?.first
    }

    fun questionType(message: ByteArray): String {
        val end = questionNameEnd(message) ?: return "?"
        if (end + 4 > message.size) return "?"
        return when (val type = u16(message, end)) {
            1 -> "A"
            12 -> "PTR"
            15 -> "MX"
            16 -> "TXT"
            28 -> "AAAA"
            33 -> "SRV"
            65 -> "HTTPS"
            else -> "TYPE$type"
        }
    }

    /** A DNS answer with QR set, NXDOMAIN (rcode 3) and no resource records. */
    fun nxDomain(message: ByteArray): ByteArray? {
        val end = questionNameEnd(message) ?: return null
        if (end + 4 > message.size) return null

        val out = message.copyOf(end + 4)
        out[2] = (out[2].toInt() or 0x80).toByte() // QR = response
        out[3] = ((out[3].toInt() and 0xf0) or 0x03).toByte() // RCODE = NXDOMAIN
        // Anchor counts: the query may have carried EDNS in the additional
        // section, which is deliberately not copied.
        for (index in 6 until 12) out[index] = 0
        return out
    }

    private fun questionNameEnd(message: ByteArray): Int? {
        if (message.size < 12 || u16(message, 4) < 1) return null
        return readName(message, 12)?.second
    }

    private fun readName(message: ByteArray, start: Int): Pair<String, Int>? {
        var offset = start
        var afterName = -1
        var jumped = false
        var guard = 0
        val labels = mutableListOf<String>()

        while (true) {
            if (offset >= message.size || guard++ > MAX_POINTER_JUMPS) return null
            val length = message[offset].toInt() and 0xff
            offset++

            when {
                length == 0 -> {
                    if (!jumped) afterName = offset
                    break
                }
                length and 0xc0 == 0xc0 -> {
                    if (offset >= message.size) return null
                    val pointer = ((length and 0x3f) shl 8) or (message[offset].toInt() and 0xff)
                    if (!jumped) afterName = offset + 1
                    jumped = true
                    offset = pointer
                    if (offset >= message.size) return null
                }
                length and 0xc0 != 0 -> return null // reserved label kind
                else -> {
                    if (offset + length > message.size) return null
                    labels.add(String(message, offset, length, Charsets.US_ASCII))
                    offset += length
                }
            }
        }

        if (labels.isEmpty() || afterName < 0) return null
        val name = labels.joinToString(".").lowercase(Locale.ROOT).trimEnd('.')
        return name to afterName
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)

    private const val MAX_POINTER_JUMPS = 128
}
