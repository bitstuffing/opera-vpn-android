package com.github.bitstuffing.operavpn.dns

/**
 * Shared parser for the hosts-format text used by custom rules and by
 * subscription lists. Accepts comments, bare domains and the usual
 * `0.0.0.0 domain domain` / `127.0.0.1 domain` lines.
 */
internal object HostsParser {

    fun parse(text: String): Set<String> {
        val result = mutableSetOf<String>()
        text.lineSequence().forEach { line ->
            val noComment = line.substringBefore('#').trim()
            if (noComment.isEmpty()) return@forEach
            val tokens = noComment.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val domains = if (tokens.firstOrNull().isIpAddress()) tokens.drop(1) else tokens
            domains.forEach { domain ->
                val value = normalizeDomainRule(domain)
                    .removePrefix("*.")
                    .removePrefix(".")
                if (isDomain(value)) result.add(value)
            }
        }
        return result
    }

    private fun isDomain(value: String): Boolean =
        value.length in 3..253 &&
            value.contains('.') &&
            value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '*' }

    private fun String?.isIpAddress(): Boolean {
        if (this == null) return false
        return contains('.') && all { it.isDigit() || it == '.' }
    }
}
