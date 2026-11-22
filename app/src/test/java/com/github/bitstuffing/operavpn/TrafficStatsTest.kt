package com.github.bitstuffing.operavpn

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrafficStatsTest {

    private var now = 1_000_000_000L

    @AfterEach
    fun tearDown() = TrafficStats.resetForTest()

    private fun setUpClock() {
        now = 1_000_000_000L
        TrafficStats.clock = { now }
        TrafficStats.reset()
    }

    @Test
    fun `format bytes`() {
        assertEquals("0 B", TrafficStats.formatBytes(0L))
        assertEquals("999 B", TrafficStats.formatBytes(999L))
        assertEquals("1.0 KB", TrafficStats.formatBytes(1024L))
        assertEquals("1.5 MB", TrafficStats.formatBytes(1536L * 1024L))
        assertEquals("1.00 GB", TrafficStats.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `counters accumulate and speed uses the delta since the last sample`() {
        setUpClock()

        TrafficStats.onDownload(1024)
        TrafficStats.onUpload(512)
        var snapshot = TrafficStats.snapshot()
        assertEquals(1024L, snapshot.downBytes)
        assertEquals(512L, snapshot.upBytes)
        // The first sample is clamped to 250 ms so one packet cannot look like a burst.
        assertEquals(4096.0, snapshot.downBps, 0.001)
        assertEquals(2048.0, snapshot.upBps, 0.001)

        now += 1_000_000_000L
        TrafficStats.onDownload(2048)
        snapshot = TrafficStats.snapshot()
        assertEquals(3072L, snapshot.downBytes)
        assertEquals(512L, snapshot.upBytes)
        assertEquals(2048.0, snapshot.downBps, 0.001)
        assertEquals(0.0, snapshot.upBps, 0.001)
    }

    @Test
    fun `rate sample is shared by multiple consumers`() {
        setUpClock()
        TrafficStats.onDownload(1024)
        val first = TrafficStats.snapshot()
        assertEquals(4096.0, first.downBps, 0.001)

        // The UI and the notification read once a second; a second reader in
        // the same window must reuse the rate, not consume a near-zero delta.
        val second = TrafficStats.snapshot()
        assertEquals(4096.0, second.downBps, 0.001)

        now += 1_000_000_000L
        TrafficStats.onDownload(2048)
        val third = TrafficStats.snapshot()
        assertEquals(3072L, third.downBytes)
        assertEquals(2048.0, third.downBps, 0.001)
    }

    @Test
    fun `reset starts a fresh session`() {
        setUpClock()
        TrafficStats.onDownload(4096)
        TrafficStats.onUpload(2048)

        TrafficStats.reset()
        val snapshot = TrafficStats.snapshot()
        assertEquals(0L, snapshot.downBytes)
        assertEquals(0L, snapshot.upBytes)
        assertEquals(0.0, snapshot.downBps, 0.001)
        assertEquals(0.0, snapshot.upBps, 0.001)
    }

    @Test
    fun `non positive sizes are ignored`() {
        setUpClock()
        TrafficStats.onDownload(0)
        TrafficStats.onUpload(-10)
        assertEquals(0L, TrafficStats.downTotal())
        assertEquals(0L, TrafficStats.upTotal())
    }
}
