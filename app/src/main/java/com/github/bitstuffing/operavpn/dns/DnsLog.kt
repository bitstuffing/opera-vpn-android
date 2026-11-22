package com.github.bitstuffing.operavpn.dns

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

/** Bounded, persistent record of DNS decisions (AdAway-style sniff log). */
object DnsLog {

    data class Entry(
        val id: Long,
        val time: Long,
        val domain: String,
        val type: String,
        val decision: String,
        val detail: String
    )

    data class Stats(val blocked: Int, val allowed: Int, val total: Int)

    const val DECISION_BLOCKED = "BLOCKED"
    const val DECISION_ALLOWED = "ALLOWED"
    const val DECISION_ERROR = "ERROR"

    private const val PREFS = "opera_vpn_dns_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300
    private const val FLUSH_EVERY = 16

    private val entries = Collections.synchronizedList(mutableListOf<Entry>())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    private var prefs: SharedPreferences? = null
    private var nextId = 1L
    private var sinceFlush = 0
    private var loaded = false

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                load()
            }
        }
    }

    fun add(domain: String, type: String, decision: String, detail: String = "") {
        synchronized(entries) {
            entries.add(
                0,
                Entry(nextId++, System.currentTimeMillis(), domain, type, decision, detail)
            )
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
            sinceFlush++
            if (sinceFlush >= FLUSH_EVERY) flushLocked()
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            flushLocked()
        }
    }

    fun stats(): Stats = synchronized(entries) {
        Stats(
            blocked = entries.count { it.decision == DECISION_BLOCKED },
            allowed = entries.count { it.decision == DECISION_ALLOWED },
            total = entries.size
        )
    }

    fun format(entry: Entry): String = buildString {
        append(timeFormat.format(entry.time)).append("  ")
        append(entry.domain).append("  [").append(entry.type).append("]  ")
        append(entry.decision)
        if (entry.detail.isNotBlank()) append("  ").append(entry.detail)
    }

    /** Call before the process goes away to avoid losing the last entries. */
    fun flush() {
        synchronized(entries) { flushLocked() }
    }

    private fun flushLocked() {
        val target = prefs ?: return
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            val item = JSONObject()
                .put("id", entry.id)
                .put("time", entry.time)
                .put("domain", entry.domain)
                .put("type", entry.type)
                .put("decision", entry.decision)
                .put("detail", entry.detail)
            array.put(item)
        }
        sinceFlush = 0
        target.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val raw = prefs?.getString(KEY_ENTRIES, null) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.optLong("id", nextId)
                entries.add(
                    Entry(
                        id = id,
                        time = item.optLong("time", 0L),
                        domain = item.optString("domain"),
                        type = item.optString("type", "?"),
                        decision = item.optString("decision"),
                        detail = item.optString("detail")
                    )
                )
                nextId = maxOf(nextId, id + 1)
            }
        } catch (_: Exception) {
            entries.clear()
        }
    }
}
