package com.github.bitstuffing.operavpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.github.bitstuffing.operavpn.dns.DnsFilter
import com.github.bitstuffing.operavpn.dns.DnsLists
import com.github.bitstuffing.operavpn.dns.DnsSettings

class DnsFilterActivity : Activity() {

    private lateinit var adBlockSwitch: Switch
    private lateinit var blockInput: EditText
    private lateinit var allowInput: EditText
    private lateinit var blockRulesContainer: LinearLayout
    private lateinit var allowRulesContainer: LinearLayout
    private lateinit var importInput: EditText
    private lateinit var listsSummaryText: TextView
    private lateinit var defaultsText: TextView
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DnsSettings.init(applicationContext)
        DnsLists.init(applicationContext)

        setContentView(R.layout.activity_dns_filter)

        adBlockSwitch = findViewById(R.id.adBlockSwitch)
        blockInput = findViewById(R.id.blockRuleInput)
        allowInput = findViewById(R.id.allowRuleInput)
        blockRulesContainer = findViewById(R.id.blockRulesContainer)
        allowRulesContainer = findViewById(R.id.allowRulesContainer)
        importInput = findViewById(R.id.importInput)
        listsSummaryText = findViewById(R.id.listsSummaryText)
        defaultsText = findViewById(R.id.defaultsText)

        updatingUi = true
        adBlockSwitch.isChecked = DnsSettings.adBlockEnabled
        updatingUi = false
        adBlockSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            DnsSettings.adBlockEnabled = checked
        }

        findViewById<Button>(R.id.addBlockButton).setOnClickListener { addRule(blockInput, block = true) }
        findViewById<Button>(R.id.addAllowButton).setOnClickListener { addRule(allowInput, block = false) }
        findViewById<Button>(R.id.importButton).setOnClickListener { importHosts() }
        findViewById<Button>(R.id.manageListsButton).setOnClickListener {
            startActivity(Intent(this, DnsListsActivity::class.java))
        }
        findViewById<Button>(R.id.filterLogButton).setOnClickListener {
            startActivity(Intent(this, DnsLogActivity::class.java))
        }
        findViewById<Button>(R.id.filterBackButton).setOnClickListener { finish() }

        refreshRules()
        refreshDefaults()
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    private fun refreshSummary() {
        listsSummaryText.text = getString(
            R.string.lists_summary,
            DnsLists.enabledCount(),
            DnsLists.totalDomains()
        )
    }

    private fun addRule(input: EditText, block: Boolean) {
        val rule = input.text?.toString()?.trim().orEmpty()
        if (rule.isEmpty()) {
            input.error = getString(R.string.rule_required)
            return
        }
        if (block) DnsSettings.addBlockRule(rule) else DnsSettings.addAllowRule(rule)
        input.text?.clear()
        refreshRules()
        val message = if (block) R.string.rule_added else R.string.rule_allowed
        Toast.makeText(this, getString(message, rule), Toast.LENGTH_SHORT).show()
    }

    private fun importHosts() {
        val count = DnsSettings.importHosts(importInput.text?.toString().orEmpty())
        importInput.text?.clear()
        refreshRules()
        Toast.makeText(this, getString(R.string.rules_imported, count), Toast.LENGTH_SHORT).show()
    }

    private fun refreshRules() {
        renderRules(blockRulesContainer, DnsSettings.blockRules.sorted(), block = true)
        renderRules(allowRulesContainer, DnsSettings.allowRules.sorted(), block = false)
    }

    private fun renderRules(container: LinearLayout, rules: List<String>, block: Boolean) {
        container.removeAllViews()
        if (rules.isEmpty()) {
            container.addView(emptyRow())
            return
        }
        rules.forEach { rule -> container.addView(ruleRow(rule, block)) }
    }

    private fun emptyRow(): TextView = TextView(this).apply {
        text = getString(R.string.no_rules)
        setTextColor(COLOR_SECONDARY)
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun ruleRow(rule: String, block: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(this).apply {
            text = rule
            setTextColor(COLOR_PRIMARY)
            textSize = 14f
            setPadding(0, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val remove = Button(this).apply {
            text = getString(R.string.remove)
            setTextColor(COLOR_PRIMARY)
            textSize = 12f
            minimumWidth = 0
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (block) DnsSettings.removeBlockRule(rule) else DnsSettings.removeAllowRule(rule)
                refreshRules()
                Toast.makeText(
                    this@DnsFilterActivity,
                    getString(R.string.rule_removed, rule),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        row.addView(label)
        row.addView(remove)
        return row
    }

    private fun refreshDefaults() {
        val defaults = DnsFilter.defaultBlockedDomains.sorted().take(DEFAULT_LIMIT)
        defaultsText.text = if (defaults.isEmpty()) {
            getString(R.string.defaults_empty)
        } else {
            defaults.joinToString("\n")
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 12
        const val COLOR_PRIMARY = 0xFFFFFFFF.toInt()
        const val COLOR_SECONDARY = 0xFFB0B0B8.toInt()
    }
}
