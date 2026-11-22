package com.github.bitstuffing.operavpn

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.github.bitstuffing.operavpn.dns.DnsLog
import com.github.bitstuffing.operavpn.dns.DnsSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DnsLogActivity : Activity() {

    private lateinit var logList: ListView
    private lateinit var emptyLogText: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var entries: List<DnsLog.Entry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DnsSettings.init(applicationContext)
        DnsLog.init(applicationContext)

        setContentView(R.layout.activity_dns_log)

        logList = findViewById(R.id.logList)
        emptyLogText = findViewById(R.id.emptyLogText)
        adapter = LogAdapter()
        logList.adapter = adapter
        logList.emptyView = emptyLogText
        logList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            showEntryActions(entries.getOrNull(position) ?: return@OnItemClickListener)
        }

        findViewById<Button>(R.id.refreshLogButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.logBackButton).setOnClickListener { finish() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = DnsLog.snapshot()
        adapter.clear()
        adapter.addAll(entries.map(::format))
        adapter.notifyDataSetChanged()
        emptyLogText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun format(entry: DnsLog.Entry): String {
        val time = synchronized(timeFormat) { timeFormat.format(Date(entry.time)) }
        return if (entry.detail.isBlank()) {
            getString(R.string.log_entry_no_detail, time, entry.domain, entry.type, entry.decision)
        } else {
            getString(R.string.log_entry, time, entry.domain, entry.type, entry.decision, entry.detail)
        }
    }

    private fun showEntryActions(entry: DnsLog.Entry) {
        val domain = entry.domain
        val actions = arrayOf(
            getString(R.string.block_domain),
            getString(R.string.allow_domain),
            getString(R.string.copy_domain),
            getString(R.string.cancel)
        )
        AlertDialog.Builder(this)
            .setTitle(domain)
            .setItems(actions) { dialog, which ->
                when (which) {
                    0 -> {
                        DnsSettings.addBlockRule(domain)
                        toast(getString(R.string.rule_added, domain))
                    }
                    1 -> {
                        DnsSettings.addAllowRule(domain)
                        toast(getString(R.string.rule_allowed, domain))
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label), domain))
                        toast(getString(R.string.domain_copied, domain))
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_log)
            .setMessage(R.string.confirm_clear_log)
            .setPositiveButton(R.string.clear) { _, _ ->
                DnsLog.clear()
                refresh()
                toast(getString(R.string.log_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class LogAdapter : ArrayAdapter<String>(
        this@DnsLogActivity,
        android.R.layout.simple_list_item_1
    ) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            (view as? TextView)?.setTextColor(COLOR_PRIMARY)
            return view
        }
    }

    private companion object {
        const val COLOR_PRIMARY = 0xFFFFFFFF.toInt()
    }
}
