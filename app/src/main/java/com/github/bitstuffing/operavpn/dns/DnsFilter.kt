package com.github.bitstuffing.operavpn.dns

import java.util.Locale

/** Lower-cases a rule and removes decorations accepted in Adblock-style lists. */
internal fun normalizeDomainRule(raw: String): String = raw
    .trim()
    .lowercase(Locale.ROOT)
    .trimEnd('.')
    .removePrefix("||")
    .removeSuffix("^")
    .trim()

/**
 * DNS-level ad blocking. Allow rules always win so a user can unbreak a
 * false positive without editing the bundled list.
 */
object DnsFilter {

    enum class Decision { BLOCK, ALLOW }

    /** Conservative starting point; the real power is in the custom lists. */
    val defaultBlockedDomains: Set<String> = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "ads.google.com",
        "pagead2.googlesyndication.com",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "analytics.tiktok.com",
        "ads.facebook.com",
        "connect.facebook.net",
        "graph.facebook.com",
        "adnxs.com",
        "amazon-adsystem.com",
        "crashlytics.com",
        "firebaseio.com",
        "scores.pushaholic.com",
        "taboola.com",
        "outbrain.com",
        "pubmatic.com",
        "rubiconproject.com",
        "casalemedia.com",
        "openx.net",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "segment.io"
    )

    fun decide(domain: String): Decision {
        val name = normalizeDomainRule(domain)
        if (name.isEmpty()) return Decision.ALLOW
        if (DnsSettings.allowRules.any { matches(name, it) }) return Decision.ALLOW
        if (!DnsSettings.adBlockEnabled) return Decision.ALLOW
        if (DnsSettings.blockRules.any { matches(name, it) }) return Decision.BLOCK
        if (DnsLists.contains(name)) return Decision.BLOCK
        if (defaultBlockedDomains.any { matches(name, it) }) return Decision.BLOCK
        return Decision.ALLOW
    }

    /** Human-readable explanation written to the DNS log. */
    fun reason(domain: String): String {
        val name = normalizeDomainRule(domain)
        DnsSettings.allowRules.firstOrNull { matches(name, it) }?.let { return "allow rule: $it" }
        if (!DnsSettings.adBlockEnabled) return "filter disabled"
        DnsSettings.blockRules.firstOrNull { matches(name, it) }?.let { return "block rule: $it" }
        DnsLists.matchingList(name)?.let { return "list: $it" }
        defaultBlockedDomains.firstOrNull { matches(name, it) }?.let { return "default list: $it" }
        return "default allow"
    }

    private fun matches(domain: String, rawRule: String): Boolean {
        val rule = normalizeDomainRule(rawRule)
        if (rule.isEmpty()) return false
        return when {
            rule.startsWith("*.") -> {
                val suffix = rule.removePrefix("*.")
                domain == suffix || domain.endsWith(".$suffix")
            }
            rule.startsWith(".") -> {
                val suffix = rule.removePrefix(".")
                domain == suffix || domain.endsWith(rule)
            }
            // A plain rule blocks the domain and all of its subdomains, which
            // is what users expect from a hosts-file style entry.
            else -> domain == rule || domain.endsWith(".$rule")
        }
    }
}
