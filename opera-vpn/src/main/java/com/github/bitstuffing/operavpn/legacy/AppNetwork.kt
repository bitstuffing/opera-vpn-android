package com.github.bitstuffing.operavpn.legacy

import android.util.Log

object AppNetwork {
    private const val TAG = "AppNetwork"
    private const val COUNTRY = "EU"
    private const val TTL_MS = 30 * 60_000L
    private const val RETRY_AFTER_FAILURE_MS = 30_000L

    /** Lo actualizan los ajustes. */
    @Volatile
    var operaEnabled = false

    private val direct = DirectFetcher()
    private var cached: OperaSeClient.OperaConnection? = null
    private var cachedAt = 0L
    private var lastFailureAt = 0L

    /** Conexión de Opera (reutilizada), o null si está desactivado o falló. Bloquea hasta ~2 s la primera vez. */
    @Synchronized
    fun operaConnection(): OperaSeClient.OperaConnection? {
        if (!operaEnabled) return null

        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < TTL_MS }?.let { return it }
        if (now - lastFailureAt < RETRY_AFTER_FAILURE_MS) return null

        val connection = try {
            OperaSeClient(OperaSeClient.CLIENT_TYPE, OperaSeClient.CLIENT_PASSWORD)
                .initialize(COUNTRY)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando Opera", e)
            null
        }

        if (connection == null) {
            cached = null
            lastFailureAt = now
            Log.w(TAG, "Opera no disponible; se usa conexión directa")
        } else {
            cached = connection
            cachedAt = now
            Log.d(TAG, "Opera listo: ${connection.host}:${connection.port}")
        }
        return connection
    }

    /** Descarta la conexión (p. ej. tras un error) para que la siguiente petición registre una nueva. */
    @Synchronized
    fun invalidate() {
        cached = null
    }

    /** Fetcher que usa Opera solo para [operaHosts] y, si Opera está apagado o falla, todo directo. */
    fun fetcherFor(operaHosts: Set<String>): HttpFetcher {
        val connection = operaConnection() ?: return direct
        return RoutingFetcher(direct, OperaFetcher(connection), operaHosts)
    }
}