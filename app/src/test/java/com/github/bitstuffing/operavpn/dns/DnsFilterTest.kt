package com.github.bitstuffing.operavpn.dns

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DnsFilterTest {

    @AfterEach
    fun reset() {
        DnsSettings.resetForTest()
        DnsLists.resetForTest()
    }

    @Test
    fun `blocks default list and its subdomains`() {
        assertEquals(DnsFilter.Decision.BLOCK, DnsFilter.decide("doubleclick.net"))
        assertEquals(DnsFilter.Decision.BLOCK, DnsFilter.decide("ads.doubleclick.net"))
        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("example.com"))
    }

    @Test
    fun `allow rules take priority over block rules`() {
        DnsSettings.addBlockRule("ads.example.com")
        assertEquals(DnsFilter.Decision.BLOCK, DnsFilter.decide("ads.example.com"))
        DnsSettings.addAllowRule("ads.example.com")
        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("ads.example.com"))
    }

    @Test
    fun `normalizes rules with hosts and adblock syntax`() {
        DnsSettings.addBlockRule("||TRACKING.Example.COM^")
        assertEquals(
            DnsFilter.Decision.BLOCK,
            DnsFilter.decide("sub.tracking.example.com")
        )
        assertEquals("block rule: tracking.example.com", DnsFilter.reason("tracking.example.com"))
    }

    @Test
    fun `imports hosts list and counts only new unique domains`() {
        val hosts = """
            # comment
            0.0.0.0 ads.one.example ads.two.example
            127.0.0.1 tracking.example # inline
            localhost
        """.trimIndent()
        assertEquals(3, DnsSettings.importHosts(hosts))
        assertEquals(3, DnsSettings.blockRules.size)
        assertEquals(0, DnsSettings.importHosts(hosts)) // idempotent
    }

    @Test
    fun `with filter disabled all queries allowed except allow rules`() {
        DnsSettings.addBlockRule("ads.example.com")
        DnsSettings.adBlockEnabled = false
        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("ads.example.com"))
        assertEquals(DnsFilter.Decision.ALLOW, DnsFilter.decide("doubleclick.net"))
    }
}
