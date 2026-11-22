package com.github.bitstuffing.operavpn

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.github.bitstuffing.operavpn.dns.DnsList
import com.github.bitstuffing.operavpn.dns.DnsLists
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manage named local block lists and AdAway-style URL subscriptions. */
class DnsListsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ListAdapter
    private var entries: List<DnsList> = emptyList()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DnsLists.init(applicationContext)
        setContentView(R.layout.activity_dns_lists)

        listView = findViewById(R.id.listsList)
        adapter = ListAdapter()
        listView.adapter = adapter

        findViewById<Button>(R.id.addLocalListButton).setOnClickListener { promptLocal() }
        findViewById<Button>(R.id.addRemoteListButton).setOnClickListener { promptRemote() }
        findViewById<Button>(R.id.refreshAllButton).setOnClickListener { refreshAll() }
        findViewById<Button>(R.id.listsBackButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = DnsLists.snapshot()
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.listsList).visibility =
            if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun promptLocal() {
        val input = EditText(this).apply {
            hint = getString(R.string.list_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(COLOR_PRIMARY)
            setHintColor()
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_local_list)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                try {
                    val list = DnsLists.addLocal(input.text.toString())
                    toast(getString(R.string.list_created, list.name))
                    refresh()
                    showListActions(list)
                } catch (e: Exception) {
                    toast(e.message ?: getString(R.string.list_error_generic))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRemote() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.list_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(COLOR_PRIMARY)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.list_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(COLOR_PRIMARY)
        }
        container.addView(nameInput)
        container.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_remote_list)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                try {
                    val list = DnsLists.addRemote(
                        nameInput.text.toString(),
                        urlInput.text.toString()
                    )
                    toast(getString(R.string.list_created, list.name))
                    refresh()
                    refresh(list)
                } catch (e: Exception) {
                    toast(e.message ?: getString(R.string.list_error_generic))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showListActions(list: DnsList) {
        val actions = mutableListOf(
            getString(R.string.toggle_list),
            getString(R.string.delete_list)
        )
        if (list.isRemote) {
            actions.add(0, getString(R.string.update_list))
        } else {
            actions.add(0, getString(R.string.edit_domains))
        }
        AlertDialog.Builder(this)
            .setTitle(list.name)
            .setMessage(listInfo(list))
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    getString(R.string.edit_domains) -> promptDomains(list)
                    getString(R.string.update_list) -> refresh(list)
                    getString(R.string.toggle_list) -> {
                        DnsLists.setEnabled(list.id, !list.enabled)
                        refresh()
                    }
                    getString(R.string.delete_list) -> confirmDelete(list)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun promptDomains(list: DnsList) {
        val input = EditText(this).apply {
            hint = getString(R.string.list_domains_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(COLOR_PRIMARY)
            setText(list.domains.sorted().joinToString("\n"))
            gravity = Gravity.TOP
            minLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_domains))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val count = DnsLists.updateDomains(list.id, input.text.toString())
                toast(getString(R.string.list_domains_saved, count))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(list: DnsList) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_list)
            .setMessage(getString(R.string.confirm_delete_list, list.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                DnsLists.remove(list.id)
                toast(getString(R.string.list_deleted, list.name))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh(list: DnsList) {
        toast(getString(R.string.refreshing_list, list.name))
        Thread {
            val error = DnsLists.refresh(list.id)
            runOnUiThread {
                refresh()
                if (error.isEmpty()) {
                    toast(getString(R.string.list_updated, list.name))
                } else {
                    toast(getString(R.string.list_error, error))
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun refreshAll() {
        toast(getString(R.string.refreshing_all))
        Thread {
            val errors = DnsLists.refreshAll().filterValues { it.isNotEmpty() }
            runOnUiThread {
                refresh()
                if (errors.isEmpty()) {
                    toast(getString(R.string.all_lists_updated))
                } else {
                    toast(getString(R.string.list_error, errors.values.first()))
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun listInfo(list: DnsList): String = buildString {
        append(getString(R.string.list_domains_count, list.domains.size)).append('\n')
        if (list.isRemote) {
            append(list.url).append('\n')
        }
        if (list.updatedAt > 0) {
            append(getString(R.string.list_updated_at, timeFormat.format(Date(list.updatedAt))))
                .append('\n')
        }
        if (list.error.isNotBlank()) {
            append(getString(R.string.list_error, list.error))
        }
    }

    private fun EditText.setHintColor() {
        setHintTextColor(COLOR_SECONDARY)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class ListAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): DnsList = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val list = getItem(position)
            val row = LinearLayout(this@DnsListsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, 12, 4, 12)
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }
            val title = TextView(this@DnsListsActivity).apply {
                text = buildString {
                    append(if (list.enabled) "✓ " else "✗ ")
                    append(list.name)
                    if (list.isRemote) append(" · URL")
                }
                setTextColor(COLOR_PRIMARY)
                textSize = 16f
            }
            val detail = TextView(this@DnsListsActivity).apply {
                text = getString(
                    R.string.list_row_detail,
                    list.domains.size,
                    if (list.enabled) getString(R.string.list_state_on) else getString(R.string.list_state_off)
                )
                setTextColor(COLOR_SECONDARY)
                textSize = 12f
            }
            val actions = LinearLayout(this@DnsListsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val edit = Button(this@DnsListsActivity).apply {
                text = getString(if (list.isRemote) R.string.update_list else R.string.edit_domains)
                textSize = 11f
                minimumWidth = 0
                setPadding(8, 2, 8, 2)
                setOnClickListener {
                    if (list.isRemote) refresh(list) else promptDomains(list)
                }
            }
            val toggle = Button(this@DnsListsActivity).apply {
                text = getString(if (list.enabled) R.string.disable_list else R.string.enable_list)
                textSize = 11f
                minimumWidth = 0
                setPadding(8, 2, 8, 2)
                setOnClickListener {
                    DnsLists.setEnabled(list.id, !list.enabled)
                    refresh()
                }
            }
            val delete = Button(this@DnsListsActivity).apply {
                text = getString(R.string.delete)
                textSize = 11f
                minimumWidth = 0
                setPadding(8, 2, 8, 2)
                setOnClickListener { confirmDelete(list) }
            }
            actions.addView(edit)
            actions.addView(toggle)
            actions.addView(delete)
            row.addView(title)
            row.addView(detail)
            row.addView(actions)
            row.setOnClickListener { showListActions(list) }
            return row
        }
    }

    private companion object {
        const val COLOR_PRIMARY = 0xFFFFFFFF.toInt()
        const val COLOR_SECONDARY = 0xFFB0B0B8.toInt()
    }
}
