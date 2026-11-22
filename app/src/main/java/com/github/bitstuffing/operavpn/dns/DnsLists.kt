package com.github.bitstuffing.operavpn.dns

import android.content.Context
import android.content.SharedPreferences
import com.github.bitstuffing.operavpn.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Manages the named local and remote block lists used by [DnsFilter].
 *
 * Remote lists are downloaded on the caller's thread (the UI uses a worker),
 * parsed with [HostsParser] and cached in SharedPreferences, so they keep
 * working offline and after a restart. Downloads use the platform HTTP client;
 * while the VPN is active they are routed through the TUN like any other TCP
 * connection, and there is no WebKit/curl fetcher involved.
 */
object DnsLists {

    private const val PREFS = "opera_vpn_dns_lists"
    private const val KEY_LISTS = "lists"
    private const val MAX_LIST_BYTES = 5 * 1024 * 1024
    private const val MAX_LISTS = 32

    @Volatile
    private var prefs: SharedPreferences? = null
    @Volatile
    private var appContext: Context? = null

    private val lists = mutableListOf<DnsList>()
    private var nextId = 1L

    /** domain → list name; rebuilt whenever a list changes. */
    @Volatile
    private var domainToList: Map<String, String> = emptyMap()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            appContext = context.applicationContext
            val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            lists.clear()
            val raw = store.getString(KEY_LISTS, null)
            if (!raw.isNullOrBlank()) {
                try {
                    val array = JSONArray(raw)
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        lists.add(
                            DnsList(
                                id = id,
                                name = item.optString("name").ifBlank {
                                    appContext?.getString(R.string.list_default_name, id) ?: "List $id"
                                },
                                url = item.optString("url"),
                                enabled = item.optBoolean("enabled", true),
                                domains = item.optJSONArray("domains")
                                    ?.let { json -> (0 until json.length()).mapNotNull { json.optString(it).takeIf { v -> v.isNotBlank() } }.toSet() }
                                    ?: emptySet(),
                                updatedAt = item.optLong("updatedAt", 0L),
                                error = item.optString("error")
                            )
                        )
                        nextId = maxOf(nextId, id.removePrefix("list-").toLongOrNull()?.plus(1) ?: nextId)
                    }
                } catch (_: Exception) {
                    lists.clear()
                }
            }
            prefs = store
            recomputeLocked()
        }
    }

    fun snapshot(): List<DnsList> = synchronized(lists) { lists.toList() }

    fun enabledCount(): Int = synchronized(lists) { lists.count { it.enabled } }

    fun totalDomains(): Int = synchronized(lists) { lists.sumOf { it.domains.size } }

    fun addLocal(name: String): DnsList {
        val defaultName = appContext?.getString(R.string.list_default_local_name) ?: "Local list"
        val list = DnsList(
            id = newId(),
            name = name.trim().ifBlank { defaultName },
            enabled = true
        )
        synchronized(lists) {
            if (lists.size >= MAX_LISTS) {
                val err = appContext?.getString(R.string.list_error_too_many, MAX_LISTS)
                    ?: "Too many lists (maximum $MAX_LISTS)"
                throw IllegalStateException(err)
            }
            lists.add(list)
            persistLocked()
            recomputeLocked()
        }
        return list
    }

    fun addRemote(name: String, url: String): DnsList {
        val normalized = normalizeUrl(url)
        val defaultName = appContext?.getString(R.string.list_default_remote_name) ?: "Remote list"
        val list = DnsList(
            id = newId(),
            name = name.trim().ifBlank { defaultName },
            url = normalized,
            enabled = true
        )
        synchronized(lists) {
            if (lists.size >= MAX_LISTS) {
                val err = appContext?.getString(R.string.list_error_too_many, MAX_LISTS)
                    ?: "Too many lists (maximum $MAX_LISTS)"
                throw IllegalStateException(err)
            }
            lists.add(list)
            persistLocked()
            recomputeLocked()
        }
        return list
    }

    fun remove(id: String) {
        synchronized(lists) {
            if (lists.removeAll { it.id == id }) {
                persistLocked()
                recomputeLocked()
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lists) {
            replace(id) { it.copy(enabled = enabled) }
            persistLocked()
            recomputeLocked()
        }
    }

    /** Replaces the domains of a local list with the parsed text. */
    fun updateDomains(id: String, text: String): Int {
        val domains = HostsParser.parse(text)
        synchronized(lists) {
            replace(id) { it.copy(domains = domains, updatedAt = System.currentTimeMillis(), error = "") }
            persistLocked()
            recomputeLocked()
        }
        return domains.size
    }

    fun addDomains(id: String, text: String): Int {
        val parsed = HostsParser.parse(text)
        synchronized(lists) {
            replace(id) { it.copy(domains = it.domains + parsed, updatedAt = System.currentTimeMillis()) }
            persistLocked()
            recomputeLocked()
        }
        return parsed.size
    }

    fun removeDomain(id: String, domain: String) {
        synchronized(lists) {
            replace(id) { it.copy(domains = it.domains - normalizeDomainRule(domain)) }
            persistLocked()
            recomputeLocked()
        }
    }

    /** Downloads and caches one remote list. Returns an empty string on success. */
    fun refresh(id: String): String {
        val notFoundErr = appContext?.getString(R.string.list_error_not_found) ?: "List not found"
        val isLocalErr = appContext?.getString(R.string.list_error_is_local) ?: "List is local"
        val target = synchronized(lists) { lists.firstOrNull { it.id == id } }
            ?: return notFoundErr
        if (!target.isRemote) return isLocalErr
        return try {
            val text = fetch(target.url)
            val domains = HostsParser.parse(text)
            if (domains.isEmpty()) {
                val noDomainsErr = appContext?.getString(R.string.list_error_no_domains)
                    ?: "List contains no valid domains"
                throw IOException(noDomainsErr)
            }
            synchronized(lists) {
                replace(id) {
                    it.copy(
                        domains = domains,
                        updatedAt = System.currentTimeMillis(),
                        error = ""
                    )
                }
                persistLocked()
                recomputeLocked()
            }
            ""
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            synchronized(lists) {
                replace(id) { it.copy(error = message) }
                persistLocked()
            }
            message
        }
    }

    /** Downloads every enabled remote list; returns id → error (empty = ok). */
    fun refreshAll(): Map<String, String> {
        val targets = synchronized(lists) { lists.filter { it.isRemote }.map { it.id } }
        val result = mutableMapOf<String, String>()
        targets.forEach { result[it] = refresh(it) }
        return result
    }

    /** True if any enabled list contains the domain or one of its parents. */
    fun contains(domain: String): Boolean = lookup(domain) != null

    /** Name of the enabled list that matched, for the DNS log. */
    fun matchingList(domain: String): String? = lookup(domain)

    private fun lookup(domain: String): String? {
        val map = domainToList
        if (map.isEmpty()) return null
        map[domain]?.let { return it }
        var dot = domain.indexOf('.')
        while (dot >= 0) {
            map[domain.substring(dot + 1)]?.let { return it }
            dot = domain.indexOf('.', dot + 1)
        }
        return null
    }

    private inline fun replace(id: String, transform: (DnsList) -> DnsList) {
        val index = lists.indexOfFirst { it.id == id }
        if (index >= 0) lists[index] = transform(lists[index])
    }

    private fun newId(): String {
        val id = "list-${nextId++}"
        return if (lists.any { it.id == id }) newId() else id
    }

    private fun normalizeUrl(raw: String): String {
        val value = raw.trim()
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            val invalidUrlErr = appContext?.getString(R.string.list_error_invalid_url) ?: "Invalid URL"
            throw IllegalArgumentException(invalidUrlErr, e)
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            val schemeErr = appContext?.getString(R.string.list_error_scheme)
                ?: "URL must start with http:// or https://"
            throw IllegalArgumentException(schemeErr)
        }
        return value
    }

    private fun fetch(url: String): String {
        val protocolErr = appContext?.getString(R.string.list_error_protocol) ?: "Unsupported protocol"
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException(protocolErr)
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "OperaVPN/1.0 (DNS list subscription)")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_LIST_BYTES) {
                        val sizeErr = appContext?.getString(R.string.list_error_size)
                            ?: "List exceeds 5 MB limit"
                        throw IOException(sizeErr)
                    }
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun recomputeLocked() {
        val map = HashMap<String, String>()
        synchronized(lists) {
            lists.filter { it.enabled }.forEach { list ->
                list.domains.forEach { domain -> map.putIfAbsent(domain, list.name) }
            }
        }
        domainToList = map
    }

    private fun persistLocked() {
        val target = prefs ?: return
        val array = JSONArray()
        synchronized(lists) {
            lists.forEach { list ->
                val domains = JSONArray()
                list.domains.sorted().forEach { domains.put(it) }
                array.put(
                    JSONObject()
                        .put("id", list.id)
                        .put("name", list.name)
                        .put("url", list.url)
                        .put("enabled", list.enabled)
                        .put("domains", domains)
                        .put("updatedAt", list.updatedAt)
                        .put("error", list.error)
                )
            }
        }
        target.edit().putString(KEY_LISTS, array.toString()).apply()
    }

    /** Test hook: drops all in-memory lists and the derived lookup map. */
    internal fun resetForTest() {
        synchronized(lists) { lists.clear() }
        domainToList = emptyMap()
        nextId = 1L
    }
}
