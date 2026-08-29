package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

object NetworkDebugStore {
    data class Delta(val revision: Long, val reset: Boolean, val events: List<JSONObject>)

    @Volatile var recording: Boolean = true
    private val events = mutableListOf<JSONObject>()
    private val revision = AtomicLong(0)
    private var nextStoreId = 1L
    private var resetRevision = 0L

    private const val MAX_EVENTS = 10000
    private const val MATCH_WINDOW_MS = 5000L
    private const val MATCH_LOOKBACK = 96

    private val networkSources = setOf(
        "webview", "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta",
        "navigation", "navigation-timing", "new-window", "user-action", "history",
        "websocket-open", "websocket-state", "websocket-send", "websocket-receive",
        "sse-open", "sse-state", "sse-message", "beacon", "source-map", "script-archive", "replay"
    )

    private val mergeableSources = setOf(
        "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta", "navigation-timing"
    )

    @Synchronized fun add(record: JSONObject) {
        if (!recording) return
        val source = record.optString("source", "")
        if (!networkSources.contains(source) && !record.has("url")) return

        val copy = JSONObject(record.toString())
        if (isMirroredReplay(copy, source)) return

        val explicitTarget = copy.optLong("_mergeTargetId", -1L)
        if (explicitTarget > 0L) {
            val target = events.firstOrNull { it.optLong("_storeId", -1L) == explicitTarget }
            if (target != null) {
                copy.remove("_mergeTargetId")
                mergeInto(target, copy, source)
                touch(target)
                return
            }
        }

        val target = findMergeTarget(copy, source)
        if (target != null) {
            mergeInto(target, copy, source)
            touch(target)
            return
        }

        ensureSources(copy, source)
        ensureFieldSources(copy, source)
        copy.put("_storeId", nextStoreId++)
        events.add(copy)
        touch(copy)
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
            resetRevision = revision.get()
        }
    }

    private fun touch(record: JSONObject) {
        val rev = revision.incrementAndGet()
        record.put("_storeRevision", rev)
    }

    private fun isMirroredReplay(record: JSONObject, source: String): Boolean {
        val start = (events.size - MATCH_LOOKBACK).coerceAtLeast(0)
        for (i in events.lastIndex downTo start) {
            val candidate = events[i]
            if (candidate.optString("source", "") != source) continue
            if (candidate.optLong("time", Long.MIN_VALUE) != record.optLong("time", Long.MAX_VALUE)) continue
            if (candidate.optString("url", "") != record.optString("url", "")) continue
            if (candidate.optString("method", "") != record.optString("method", "")) continue
            var same = true
            val keys = record.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in setOf("capturedSources", "_fieldSources", "_storeId", "_storeRevision")) continue
                val a = record.opt(key)
                val b = candidate.opt(key)
                if ((a == null) != (b == null) || (a != null && a.toString() != b?.toString())) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
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

    private fun ensureFieldSources(record: JSONObject, source: String): JSONObject {
        val map = record.optJSONObject("_fieldSources") ?: JSONObject().also { record.put("_fieldSources", it) }
        val keys = record.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("_") || key in setOf("source", "capturedSources")) continue
            if (!map.has(key) && source.isNotBlank()) map.put(key, source)
        }
        return map
    }

    private fun mergeInto(target: JSONObject, incoming: JSONObject, source: String) {
        ensureSources(target, target.optString("source", ""))
        ensureSources(target, source)
        val targetFields = ensureFieldSources(target, target.optString("source", ""))
        val incomingFields = incoming.optJSONObject("_fieldSources")
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in setOf("source", "url", "time", "method", "capturedSources", "_fieldSources", "_storeId", "_storeRevision")) continue
            val value = incoming.opt(key)
            if (value != null && value != JSONObject.NULL) {
                target.put(key, value)
                val fieldSource = incomingFields?.optString(key, "").orEmpty().ifBlank { source }
                if (fieldSource.isNotBlank()) targetFields.put(key, fieldSource)
            }
        }
    }

    @Synchronized fun clear() {
        events.clear()
        val rev = revision.incrementAndGet()
        resetRevision = rev
    }

    @Synchronized fun snapshot(): List<JSONObject> = events.map { JSONObject(it.toString()) }

    @Synchronized fun delta(afterRevision: Long): Delta {
        val current = revision.get()
        if (afterRevision < 0L || afterRevision < resetRevision) {
            return Delta(current, true, events.map { JSONObject(it.toString()) })
        }
        if (afterRevision == current) return Delta(current, false, emptyList())
        val changed = events.filter { it.optLong("_storeRevision", 0L) > afterRevision }.map { JSONObject(it.toString()) }
        return Delta(current, false, changed)
    }

    @Synchronized fun json(): JSONArray {
        val out = JSONArray()
        events.forEach { out.put(JSONObject(it.toString())) }
        return out
    }

    fun revision(): Long = revision.get()
}
