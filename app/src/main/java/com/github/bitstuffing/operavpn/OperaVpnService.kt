package com.github.bitstuffing.operavpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.github.bitstuffing.operavpn.dns.DnsLists
import com.github.bitstuffing.operavpn.dns.DnsLog
import com.github.bitstuffing.operavpn.dns.DnsSettings
import java.io.IOException

/**
 * User-facing VPN service: Android owns the TUN interface, Opera carries TCP.
 *
 * The service is a single state machine. Every command goes through
 * [onStartCommand] on the main thread, and each attempt has a generation
 * number so a slow provisioning call cannot resurrect a tunnel that the user
 * has already replaced with a different region.
 */
class OperaVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null
    private var worker: Thread? = null

    /** Incremented by every teardown; stale callbacks/results are ignored. */
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        DnsSettings.init(this)
        DnsLists.init(this)
        DnsLog.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        val country = intent?.getStringExtra(EXTRA_COUNTRY)
            ?.takeIf { it.isNotBlank() }
            ?.trim()?.uppercase()
            ?: DnsSettings.region

        when (action) {
            ACTION_STOP -> {
                teardown()
                VpnState.publish(VpnState.Phase.DISCONNECTED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                // Drop the old tunnel first: provisioning must not go through
                // the interface we are about to replace.
                teardown()
                connect(country)
            }

            else -> {
                // ACTION_CONNECT, or a system restart with a null intent.
                if (tun != null || worker != null) return START_STICKY
                connect(country)
            }
        }
        return START_STICKY
    }

    private fun connect(country: String) {
        // Each connection attempt (including a region change) has its own
        // counter session; the UI keeps showing the last totals when stopped.
        TrafficStats.reset()
        val myGeneration = generation
        startForeground(NOTIFICATION_ID, notification(getString(R.string.status_connecting)))
        VpnState.publish(VpnState.Phase.CONNECTING, getString(R.string.status_connecting))

        worker = Thread({
            var relay: LocalUdpRelay? = null
            var descriptor: ParcelFileDescriptor? = null
            var handedOff = false
            try {
                // Provision before establishing the VPN so the API calls do not
                // enter the tunnel we are about to create.
                val proxy = OperaProxy { socket -> protect(socket) }
                val connection = proxy.initialize(country)
                if (myGeneration != generation) {
                    Log.d(TAG, "Conexión descartada: el usuario cambió de región")
                    relay?.close()
                    return@Thread
                }

                // Rethink-style local relay: the engine talks to it on
                // 127.0.0.1, and its DatagramSockets are protected so UDP goes
                // directly to the real network instead of looping into the TUN.
                val localRelay = LocalUdpRelay(
                    protect = { socket -> protect(socket) },
                    onError = { error -> fail(myGeneration, error) }
                )
                relay = localRelay

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(VPN_ADDRESS, 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .setMtu(MTU)

                val fd = builder.establish()
                    ?: throw IOException("Android no pudo crear la interfaz VPN")
                descriptor = fd
                if (myGeneration != generation) {
                    Log.d(TAG, "Túnel descartado antes de arrancar el motor")
                    try {
                        fd.close()
                    } catch (_: IOException) {
                        // Best effort.
                    }
                    return@Thread
                }

                val newEngine = VpnEngine(
                    tun = fd,
                    proxy = proxy,
                    connection = connection,
                    localRelay = localRelay,
                    protectFd = { value -> protect(value) }
                ) { error -> fail(myGeneration, error) }

                synchronized(this) {
                    if (myGeneration != generation) {
                        newEngine.close()
                        return@Thread
                    }
                    tun = fd
                    engine = newEngine
                    relay = null // Owned by the engine now.
                    handedOff = true
                }
                newEngine.start()

                val detail = "${connection.host}:${connection.port} · $country"
                VpnState.publish(VpnState.Phase.CONNECTED, detail)
                updateNotification("${getString(R.string.status_connected)} · $country")
            } catch (e: Exception) {
                relay?.close()
                if (!handedOff) {
                    try {
                        descriptor?.close()
                    } catch (_: IOException) {
                        // Best effort.
                    }
                }
                fail(myGeneration, e)
            } finally {
                if (worker === Thread.currentThread()) worker = null
            }
        }, "opera-vpn-connect").also { it.start() }
    }

    private fun teardown() {
        generation++
        worker?.interrupt()
        worker = null
        engine?.close()
        engine = null
        tun?.close()
        tun = null
    }

    private fun fail(attemptGeneration: Int, error: Throwable) {
        if (attemptGeneration != generation) return
        Log.e(TAG, "No se pudo iniciar la VPN", error)
        DnsLog.flush()
        VpnState.publish(
            VpnState.Phase.ERROR,
            error.message ?: getString(R.string.status_error)
        )
        updateNotification(getString(R.string.status_error))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        DnsLog.flush()
        teardown()
        if (VpnState.get().phase != VpnState.Phase.ERROR) {
            VpnState.publish(VpnState.Phase.DISCONNECTED)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val TAG = "OperaVpnService"
        const val ACTION_CONNECT = "com.github.bitstuffing.operavpn.CONNECT"
        const val ACTION_RECONNECT = "com.github.bitstuffing.operavpn.RECONNECT"
        const val ACTION_STOP = "com.github.bitstuffing.operavpn.STOP"
        const val EXTRA_COUNTRY = "country"
        const val DEFAULT_COUNTRY = "EU"
        const val VPN_ADDRESS = "192.0.2.2"
        const val MTU = 1500
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "opera_vpn"
    }
}
