package com.github.bitstuffing.operavpn

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Traffic counters for the current VPN session.
 *
 * The engine reports every IP packet read from the TUN as upload and every
 * packet written back to the TUN as download, so the numbers include protocol
 * headers and local DNS replies, not just application payload. Speed is the
 * delta between two [snapshot] calls divided by the elapsed time; the UI asks
 * once a second. Sampling is idempotent enough for that single consumer, and
 * all counters are atomic because packets arrive on several threads.
 */
internal object TrafficStats {

    data class Snapshot(
        val downBytes: Long,
        val upBytes: Long,
        val downBps: Double,
        val upBps: Double
    )

    /** Avoid absurd peaks when the first snapshot follows a single packet. */
    private const val MIN_SAMPLE_NANOS = 250_000_000L

    private val downTotal = AtomicLong()
    private val upTotal = AtomicLong()
    private val sampledDown = AtomicLong()
    private val sampledUp = AtomicLong()

    /** Injectable for tests; declared before [sampledAt] so init can use it. */
    internal var clock: () -> Long = { System.nanoTime() }

    private val sampledAt = AtomicLong(now())

    private fun now(): Long = clock()

    fun onDownload(bytes: Int) {
        if (bytes > 0) downTotal.addAndGet(bytes.toLong())
    }

    fun onUpload(bytes: Int) {
        if (bytes > 0) upTotal.addAndGet(bytes.toLong())
    }

    /** Totals since the last [reset], plus current bytes/second. */
    fun snapshot(): Snapshot {
        val time = now()
        val previousTime = sampledAt.getAndSet(time)
        val down = downTotal.get()
        val up = upTotal.get()
        val previousDown = sampledDown.getAndSet(down)
        val previousUp = sampledUp.getAndSet(up)
        val elapsed = maxOf(time - previousTime, MIN_SAMPLE_NANOS).toDouble()
        val scale = 1_000_000_000.0 / elapsed
        return Snapshot(
            downBytes = down,
            upBytes = up,
            downBps = (down - previousDown) * scale,
            upBps = (up - previousUp) * scale
        )
    }

    fun downTotal(): Long = downTotal.get()

    fun upTotal(): Long = upTotal.get()

    /** Called when a new connection attempt starts. */
    fun reset() {
        downTotal.set(0)
        upTotal.set(0)
        sampledDown.set(0)
        sampledUp.set(0)
        sampledAt.set(now())
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatRate(bytesPerSecond: Double): String = formatBytes(bytesPerSecond.toLong())

    /** Test hook: restores the real clock and clears all counters. */
    internal fun resetForTest() {
        clock = { System.nanoTime() }
        reset()
    }
}
