package com.github.bitstuffing.operavpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.github.bitstuffing.operavpn.dns.DnsLists
import com.github.bitstuffing.operavpn.dns.DnsLog
import com.github.bitstuffing.operavpn.dns.DnsSettings

class MainActivity : Activity() {

    private lateinit var enableSwitch: Switch
    private lateinit var regionSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var trafficTotalsText: TextView
    private lateinit var trafficSpeedText: TextView
    private lateinit var statsText: TextView

    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficRunnable = object : Runnable {
        override fun run() {
            updateTraffic()
            trafficHandler.postDelayed(this, TRAFFIC_REFRESH_MS)
        }
    }

    private var selectedRegion: String = DEFAULT_REGION
    private var pendingAction: String = OperaVpnService.ACTION_CONNECT
    private var updatingUi = false
    private var updatingRegion = false

    private val stateListener: (VpnState.Status) -> Unit = { status ->
        runOnUiThread { render(status) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DnsSettings.init(applicationContext)
        DnsLists.init(applicationContext)
        DnsLog.init(applicationContext)

        setContentView(R.layout.activity_main)

        enableSwitch = findViewById(R.id.enableSwitch)
        regionSpinner = findViewById(R.id.regionSpinner)
        statusText = findViewById(R.id.statusText)
        trafficTotalsText = findViewById(R.id.trafficTotalsText)
        trafficSpeedText = findViewById(R.id.trafficSpeedText)
        statsText = findViewById(R.id.statsText)

        setupRegionSpinner()

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) requestStart() else stopVpn()
        }

        findViewById<Button>(R.id.reconnectButton).setOnClickListener { reconnect() }
        findViewById<Button>(R.id.dnsFilterButton).setOnClickListener {
            startActivity(Intent(this, DnsFilterActivity::class.java))
        }
        findViewById<Button>(R.id.dnsLogButton).setOnClickListener {
            startActivity(Intent(this, DnsLogActivity::class.java))
        }

        render(VpnState.get())
    }

    override fun onStart() {
        super.onStart()
        VpnState.addListener(stateListener)
        render(VpnState.get())
    }

    override fun onResume() {
        super.onResume()
        updateStats()
        updateTraffic()
        trafficHandler.postDelayed(trafficRunnable, TRAFFIC_REFRESH_MS)
    }

    override fun onPause() {
        trafficHandler.removeCallbacks(trafficRunnable)
        super.onPause()
    }

    override fun onStop() {
        VpnState.removeListener(stateListener)
        super.onStop()
    }

    private fun setupRegionSpinner() {
        val regions = DnsSettings.REGIONS.ifEmpty { listOf(DEFAULT_REGION) }
        val labels = regions.map { region ->
            getString(
                when (region) {
                    "AM" -> R.string.region_am
                    "AS" -> R.string.region_as
                    else -> R.string.region_eu
                }
            )
        }

        val adapter = RegionAdapter(labels)
        regionSpinner.adapter = adapter
        regionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                if (updatingRegion) return
                val region = regions.getOrNull(position) ?: return
                DnsSettings.region = region
                if (region == selectedRegion) return
                selectedRegion = region
                if (vpnActive()) prepareAndStart(OperaVpnService.ACTION_RECONNECT)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }

        val saved = DnsSettings.region
        val index = regions.indexOf(saved).let { if (it >= 0) it else regions.indexOf(DEFAULT_REGION).coerceAtLeast(0) }
        selectedRegion = regions[index]
        DnsSettings.region = selectedRegion
        updatingRegion = true
        regionSpinner.setSelection(index)
        updatingRegion = false
    }

    private fun vpnActive(): Boolean {
        val phase = VpnState.get().phase
        return phase == VpnState.Phase.CONNECTING || phase == VpnState.Phase.CONNECTED
    }

    private fun render(status: VpnState.Status) {
        statusText.text = when (status.phase) {
            VpnState.Phase.DISCONNECTED -> getString(R.string.status_disconnected)
            VpnState.Phase.CONNECTING -> getString(R.string.status_connecting)
            VpnState.Phase.CONNECTED ->
                if (status.detail.isBlank()) getString(R.string.status_connected)
                else "${getString(R.string.status_connected)} · ${status.detail}"
            VpnState.Phase.ERROR ->
                if (status.detail.isBlank()) getString(R.string.status_error)
                else "${getString(R.string.status_error)} · ${status.detail}"
        }

        val shouldBeOn = status.phase == VpnState.Phase.CONNECTING ||
                status.phase == VpnState.Phase.CONNECTED
        if (enableSwitch.isChecked != shouldBeOn) {
            updatingUi = true
            enableSwitch.isChecked = shouldBeOn
            updatingUi = false
        }
        updateStats()
    }

    private fun updateStats() {
        val stats = DnsLog.stats()
        statsText.text = getString(R.string.dns_stats, stats.blocked, stats.allowed)
    }

    private fun updateTraffic() {
        val traffic = TrafficStats.snapshot()
        trafficTotalsText.text = getString(
            R.string.traffic_totals,
            TrafficStats.formatBytes(traffic.downBytes),
            TrafficStats.formatBytes(traffic.upBytes)
        )
        trafficSpeedText.text = getString(
            R.string.traffic_speed,
            TrafficStats.formatRate(traffic.downBps),
            TrafficStats.formatRate(traffic.upBps)
        )
    }

    private fun requestStart() {
        pendingAction = OperaVpnService.ACTION_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
            return
        }
        prepareAndStart(pendingAction)
    }

    private fun reconnect() {
        if (vpnActive()) {
            prepareAndStart(OperaVpnService.ACTION_RECONNECT)
        } else {
            requestStart()
        }
    }

    private fun prepareAndStart(action: String) {
        pendingAction = action
        val preparation = VpnService.prepare(this)
        if (preparation != null) {
            startActivityForResult(preparation, REQUEST_VPN)
        } else {
            startVpn(action)
        }
    }

    private fun startVpn(action: String) {
        val intent = Intent(this, OperaVpnService::class.java)
            .setAction(action)
            .putExtra(OperaVpnService.EXTRA_COUNTRY, selectedRegion)
        startForegroundService(intent)
    }

    private fun stopVpn() {
        val stopIntent = Intent(this, OperaVpnService::class.java)
            .setAction(OperaVpnService.ACTION_STOP)
        startService(stopIntent)
        stopService(stopIntent)
        VpnState.publish(VpnState.Phase.DISCONNECTED)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) prepareAndStart(pendingAction)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            startVpn(pendingAction)
        } else {
            VpnState.publish(
                VpnState.Phase.ERROR,
                getString(R.string.permission_denied)
            )
        }
    }

    /** Keeps the dark/white look without depending on AppCompat or Material. */
    private inner class RegionAdapter(labels: List<String>) :
        ArrayAdapter<String>(this@MainActivity, 0, labels) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = configure(convertView as? TextView ?: TextView(context), dropdown = false)
            view.text = getItem(position)
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = configure(convertView as? TextView ?: TextView(context), dropdown = true)
            view.text = getItem(position)
            return view
        }

        private fun configure(view: TextView, dropdown: Boolean): TextView = view.apply {
            setTextColor(TEXT_COLOR)
            textSize = TEXT_SIZE_SP
            if (dropdown) {
                setBackgroundColor(DROPDOWN_BACKGROUND)
                val padding = (12 * context.resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            } else {
                setBackgroundColor(0x00000000)
            }
        }
    }

    private companion object {
        const val REQUEST_VPN = 1001
        const val REQUEST_NOTIFICATIONS = 1002
        const val TRAFFIC_REFRESH_MS = 1000L
        const val DEFAULT_REGION = "EU"
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val DROPDOWN_BACKGROUND = 0xFF22222B.toInt()
        const val TEXT_SIZE_SP = 16f
    }
}
