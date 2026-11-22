package com.github.bitstuffing.operavpn

import java.net.Socket

/** Opens a protected byte stream to a destination through some upstream. */
internal fun interface SocketTunnel {
    fun open(host: String, port: Int): Socket
}
