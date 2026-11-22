package com.github.bitstuffing.operavpn.dns

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsListsTest {

    @AfterEach
    fun reset() {
        DnsSettings.resetForTest()
        DnsLists.resetForTest()
    }

    @Test
    fun `a local list blocks domain and its subdomains`() {
        val list = DnsLists.addLocal("Advertising")
        DnsLists.updateDomains(list.id, "ads.example.com\n0.0.0.0 tracking.example.net")

        assertEquals(2, DnsLists.snapshot().first().domains.size)
        assertTrue(DnsLists.contains("ads.example.com"))
        assertTrue(DnsLists.contains("sub.ads.example.com"))
        assertTrue(DnsLists.contains("tracking.example.net"))
        assertFalse(DnsLists.contains("example.com"))
        assertEquals(DnsFilter.Decision.BLOCK, DnsFilter.decide("sub.ads.example.com"))
        assertEquals("list: Advertising", DnsFilter.reason("ads.example.com"))
    }

    @Test
    fun `disabling a list stops blocking while preserving domains`() {
        val list = DnsLists.addLocal("Temporary")
        DnsLists.addDomains(list.id, "ads.example.org")

        DnsLists.setEnabled(list.id, false)
        assertFalse(DnsLists.contains("ads.example.org"))
        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("ads.example.org"))
        assertEquals(1, DnsLists.snapshot().first().domains.size)

        DnsLists.setEnabled(list.id, true)
        assertTrue(DnsLists.contains("ads.example.org"))
    }

    @Test
    fun `editing a list replaces domains and allows removing domain`() {
        val list = DnsLists.addLocal("Edited")
        DnsLists.updateDomains(list.id, "a.example\nb.example")
        assertEquals(1, DnsLists.updateDomains(list.id, "c.example"))
        assertFalse(DnsLists.contains("a.example"))
        assertTrue(DnsLists.contains("c.example"))
        DnsLists.removeDomain(list.id, "c.example")
        assertFalse(DnsLists.contains("c.example"))
    }

    @Test
    fun `allow rules take priority over a list`() {
        val list = DnsLists.addLocal("With exception")
        DnsLists.addDomains(list.id, "ads.example.com")
        DnsSettings.addAllowRule("ads.example.com")

        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("ads.example.com"))
        assertEquals("allow rule: ads.example.com", DnsFilter.reason("ads.example.com"))
        assertTrue(DnsLists.contains("ads.example.com"))
    }

    @Test
    fun `parser ignores comments and IP addresses`() {
        val parsed = HostsParser.parse(
            """
            # header
            0.0.0.0 ads.one.example ads.two.example
            127.0.0.1 tracking.example # inline comment
            localhost
            ||malicious.example^
            *.commercial.example
        """.trimIndent()
        )
        assertEquals(5, parsed.size)
        assertTrue(parsed.containsAll(
            listOf(
                "ads.one.example", "ads.two.example", "tracking.example",
                "malicious.example", "commercial.example"
            )
        ))
    }

    @Test
    fun `only accepts http and https URLs`() {
        val list = DnsLists.addRemote("Official", "https://example.com/hosts.txt")
        assertTrue(list.isRemote)
        assertEquals("https://example.com/hosts.txt", list.url)
        assertThrows(IllegalArgumentException::class.java) {
            DnsLists.addRemote("Invalid", "ftp://example.com/hosts.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsLists.addRemote("Invalid", "not-a-url")
        }
    }
}
