package com.github.bitstuffing.operavpn

import java.util.concurrent.CopyOnWriteArrayList

internal object VpnState {

    enum class Phase {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class Status(
        val phase: Phase = Phase.DISCONNECTED,
        val detail: String = ""
    )

    @Volatile
    private var current: Status = Status()

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    fun get(): Status = current

    fun publish(phase: Phase, detail: String = "") {
        current = Status(phase, detail)
        listeners.forEach { it(current) }
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}
