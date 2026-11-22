package com.github.bitstuffing.operavpn.dns

/**
 * A named block list. [url] is blank for a local list and non-blank for a
 * subscription that is refreshed from an AdAway-style hosts file.
 */
data class DnsList(
    val id: String,
    val name: String,
    val url: String = "",
    val enabled: Boolean = true,
    val domains: Set<String> = emptySet(),
    val updatedAt: Long = 0L,
    val error: String = ""
) {
    val isRemote: Boolean get() = url.isNotBlank()
}
