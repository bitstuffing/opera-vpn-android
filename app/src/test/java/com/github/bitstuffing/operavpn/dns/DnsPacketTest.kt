package com.github.bitstuffing.operavpn.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DnsPacketTest {

    /** Builds a minimal DNS query with one question. */
    private fun query(name: String, type: Int = 1): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        labels.forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(type shr 8)
        out.write(type and 0xff)
        out.write(0) // IN (high byte)
        out.write(1) // IN (low byte)
        return out.toByteArray()
    }

    @Test
    fun `lee el nombre y el tipo de la pregunta`() {
        val message = query("WWW.Example.COM")
        assertEquals("www.example.com", DnsPacket.questionName(message))
        assertEquals("A", DnsPacket.questionType(message))
        assertEquals("AAAA", DnsPacket.questionType(query("example.com", 28)))
        assertEquals("HTTPS", DnsPacket.questionType(query("example.com", 65)))
    }

    @Test
    fun `devuelve null para mensajes malformados`() {
        assertNull(DnsPacket.questionName(byteArrayOf(1, 2, 3)))
        assertNull(DnsPacket.questionName(byteArrayOf(0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 9)))
    }

    @Test
    fun `genera una respuesta NXDOMAIN con la pregunta original`() {
        val message = query("ads.example.com")
        val answer = DnsPacket.nxDomain(message)
        assertNotNull(answer)
        answer!!

        // QR=1, opcode preserved, rcode=3.
        assertEquals(0x81, answer[2].toInt() and 0xff)
        assertEquals(0x03, answer[3].toInt() and 0xff)
        // One question, zero answers/authority/additional.
        assertEquals(1, (answer[4].toInt() and 0xff) shl 8 or (answer[5].toInt() and 0xff))
        assertEquals(0, (answer[6].toInt() and 0xff) shl 8 or (answer[7].toInt() and 0xff))
        assertEquals("ads.example.com", DnsPacket.questionName(answer))
    }
}
