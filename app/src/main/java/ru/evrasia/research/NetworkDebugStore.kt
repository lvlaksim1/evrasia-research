package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

object NetworkDebugStore {
    @Volatile var recording: Boolean = true
    private val events = mutableListOf<JSONObject>()
    private val revision = AtomicLong(0)
    private const val MAX_EVENTS = 10000
    private const val MATCH_WINDOW_MS = 5000L
    private const val MATCH_LOOKBACK = 64

    private val networkSources = setOf(
        "webview", "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta",
        "navigation", "navigation-timing", "new-window",
        "websocket-open", "websocket-state", "websocket-send", "websocket-receive",
        "sse-open", "sse-state", "sse-message", "beacon", "source-map", "script-archive"
    )

    private val mergeableSources = setOf(
        "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta", "navigation-timing"
    )

    @Synchronized fun add(record: JSONObject) {
        if (!recording) return
        val source = record.optString("source", "")
        if (!networkSources.contains(source) && !record.has("url")) return

        val copy = JSONObject(record.toString())
        val target = findMergeTarget(copy, source)
        if (target != null) {
            mergeInto(target, copy, source)
            revision.incrementAndGet()
            return
        }

        ensureSources(copy, source)
        events.add(copy)
        if (events.size > MAX_EVENTS) events.removeAt(0)
        revision.incrementAndGet()
    }

    private fun findMergeTarget(record: JSONObject, source: String): JSONObject? {
        if (source !in mergeableSources) return null
        val url = record.optString("url", "")
        if (url.isBlank()) return null
        val method = record.optString("method", "GET").ifBlank { "GET" }.uppercase()
        val time = record.optLong("time", 0L)
        var best: JSONObject? = null
        var bestDelta = Long.MAX_VALUE
        val start = (events.size - MATCH_LOOKBACK).coerceAtLeast(0)

        for (i in events.lastIndex downTo start) {
            val candidate = events[i]
            if (hasSource(candidate, source)) continue
            if (!compatibleSource(candidate, source)) continue
            if (!sameUrl(candidate.optString("url", ""), url)) continue
            val candidateMethod = candidate.optString("method", "GET").ifBlank { "GET" }.uppercase()
            if (candidateMethod != method) continue
            val candidateTime = candidate.optLong("time", 0L)
            val delta = if (time > 0L && candidateTime > 0L) abs(candidateTime - time) else 0L
            if (delta > MATCH_WINDOW_MS) continue
            if (delta < bestDelta) {
                best = candidate
                bestDelta = delta
            }
        }
        return best
    }

    private fun compatibleSource(candidate: JSONObject, incoming: String): Boolean = when (incoming) {
        "fetch" -> hasSource(candidate, "webview")
        "xhr" -> hasSource(candidate, "webview")
        "fetch-meta" -> hasSource(candidate, "fetch") || hasSource(candidate, "webview")
        "xhr-meta" -> hasSource(candidate, "xhr") || hasSource(candidate, "webview")
        "resource-copy", "resource-timing" -> hasSource(candidate, "webview") || hasSource(candidate, "fetch") || hasSource(candidate, "xhr")
        "navigation-timing" -> hasSource(candidate, "navigation") || hasSource(candidate, "webview")
        else -> false
    }

    private fun sameUrl(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isBlank() || b.isBlank()) return false
        fun comparable(value: String): String {
            return if (value.startsWith("http://") || value.startsWith("https://")) {
                try {
                    val u = URL(value)
                    buildString {
                        append(u.path.ifBlank { "/" })
                        if (!u.query.isNullOrBlank()) append('?').append(u.query)
                    }
                } catch (_: Exception) { value }
            } else {
                val clean = value.substringAfter('#').trim()
                if (clean.startsWith('/')) clean else "/$clean"
            }
        }
        return comparable(a) == comparable(b)
    }

    private fun hasSource(record: JSONObject, source: String): Boolean {
        if (record.optString("source", "") == source) return true
        val sources = record.optJSONArray("capturedSources") ?: return false
        for (i in 0 until sources.length()) if (sources.optString(i) == source) return true
        return false
    }

    private fun ensureSources(record: JSONObject, source: String): JSONArray {
        val sources = record.optJSONArray("capturedSources") ?: JSONArray().also { record.put("capturedSources", it) }
        var exists = false
        for (i in 0 until sources.length()) if (sources.optString(i) == source) exists = true
        if (!exists && source.isNotBlank()) sources.put(source)
        return sources
    }

    private fun mergeInto(target: JSONObject, incoming: JSONObject, source: String) {
        ensureSources(target, target.optString("source", ""))
        ensureSources(target, source)
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "source" || key == "url" || key == "time" || key == "method" || key == "capturedSources") continue
            val value = incoming.opt(key)
            if (value != null && value != JSONObject.NULL) target.put(key, value)
        }
    }

    @Synchronized fun clear() {
        events.clear()
        revision.incrementAndGet()
    }

    @Synchronized fun snapshot(): List<JSONObject> = events.map { JSONObject(it.toString()) }

    @Synchronized fun json(): JSONArray {
        val out = JSONArray()
        events.forEach { out.put(JSONObject(it.toString())) }
        return out
    }

    fun revision(): Long = revision.get()
}
