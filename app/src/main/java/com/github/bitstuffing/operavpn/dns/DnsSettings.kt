package com.github.bitstuffing.operavpn.dns

import android.content.Context
import android.content.SharedPreferences

/** Persisted user choices shared by the UI, the service and the filter. */
object DnsSettings {

    val REGIONS: List<String> = listOf("AM", "EU", "AS")

    private const val PREFS = "opera_vpn_dns_settings"
    private const val KEY_REGION = "region"
    private const val KEY_ENABLED = "ad_block_enabled"
    private const val KEY_BLOCK = "block_rules"
    private const val KEY_ALLOW = "allow_rules"

    @Volatile
    private var prefs: SharedPreferences? = null

    // In-memory defaults mean the filter works even if init() has not run yet
    // (for example in a unit test or very early in service startup).
    @Volatile
    private var regionValue: String = DEFAULT_REGION
    @Volatile
    private var enabledValue: Boolean = true
    @Volatile
    private var blockValue: Set<String> = emptySet()
    @Volatile
    private var allowValue: Set<String> = emptySet()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val store = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            regionValue = store.getString(KEY_REGION, DEFAULT_REGION)
                ?.takeIf { it in REGIONS } ?: DEFAULT_REGION
            enabledValue = store.getBoolean(KEY_ENABLED, true)
            blockValue = store.getStringSet(KEY_BLOCK, emptySet()) ?: emptySet()
            allowValue = store.getStringSet(KEY_ALLOW, emptySet()) ?: emptySet()
            prefs = store
        }
    }

    var region: String
        get() = regionValue
        set(value) {
            val normalized = if (value in REGIONS) value else DEFAULT_REGION
            regionValue = normalized
            prefs?.edit()?.putString(KEY_REGION, normalized)?.apply()
        }

    var adBlockEnabled: Boolean
        get() = enabledValue
        set(value) {
            enabledValue = value
            prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    var blockRules: Set<String>
        get() = blockValue
        set(value) {
            val rules = value.toSet()
            blockValue = rules
            prefs?.edit()?.putStringSet(KEY_BLOCK, rules)?.apply()
        }

    var allowRules: Set<String>
        get() = allowValue
        set(value) {
            val rules = value.toSet()
            allowValue = rules
            prefs?.edit()?.putStringSet(KEY_ALLOW, rules)?.apply()
        }

    fun addBlockRule(rule: String) {
        val value = normalizeDomainRule(rule)
        if (value.isNotEmpty()) blockRules = blockRules + value
    }

    fun removeBlockRule(rule: String) {
        blockRules = blockRules - normalizeDomainRule(rule)
    }

    fun addAllowRule(rule: String) {
        val value = normalizeDomainRule(rule)
        if (value.isNotEmpty()) allowRules = allowRules + value
    }

    fun removeAllowRule(rule: String) {
        allowRules = allowRules - normalizeDomainRule(rule)
    }

    /** Resets in-memory rules; useful for tests and for a clean profile. */
    internal fun resetForTest() {
        regionValue = DEFAULT_REGION
        enabledValue = true
        blockValue = emptySet()
        allowValue = emptySet()
    }

    /**
     * Imports AdAway-style hosts lines: comments, bare domains, and lines such
     * as `0.0.0.0 ads.example.com tracking.example.net`.
     */
    fun importHosts(text: String): Int {
        val before = blockRules.size
        val imported = HostsParser.parse(text)
        if (imported.isNotEmpty()) blockRules = blockRules + imported
        return blockRules.size - before
    }

    private const val DEFAULT_REGION = "EU"
}
