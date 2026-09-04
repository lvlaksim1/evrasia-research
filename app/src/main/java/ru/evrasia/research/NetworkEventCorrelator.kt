package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import kotlin.math.abs

internal object NetworkEventCorrelator {
    private const val MATCH_WINDOW_MS = 5000L
    private const val MATCH_LOOKBACK = 96

    private val mergeableSources = setOf(
        "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta", "navigation-timing"
    )

    fun isMirroredReplay(events: List<JSONObject>, record: JSONObject, source: String): Boolean {
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

    fun findMergeTarget(events: List<JSONObject>, record: JSONObject, source: String): JSONObject? {
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
            if (!requestFingerprintsCompatible(candidate, record)) continue
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

    fun prepareNewRecord(record: JSONObject, source: String) {
        ensureSources(record, source)
        ensureFieldSources(record, source)
    }

    fun mergeInto(target: JSONObject, incoming: JSONObject, source: String) {
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
                val current = target.opt(key)
                if (key in setOf("requestHeaders", "headers", "responseHeaders") && current is JSONObject && value is JSONObject) {
                    mergeObjects(current, value)
                } else {
                    target.put(key, value)
                }
                val fieldSource = incomingFields?.optString(key, "").orEmpty().ifBlank { source }
                if (fieldSource.isNotBlank()) targetFields.put(key, fieldSource)
            }
        }
    }

    private fun mergeObjects(target: JSONObject, incoming: JSONObject) {
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = incoming.opt(key)
            if (value != null && value != JSONObject.NULL) target.put(key, value)
        }
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

    private fun requestFingerprint(event: JSONObject): String? {
        val raw = event.optString("requestBody", "").trim()
        if (raw.isBlank() || raw in setOf("[FormData]", "[unavailable]", "[binary]")) return null
        val mime = event.optString("requestMimeType", "").lowercase()
        return if (mime.contains("x-www-form-urlencoded") || (!raw.startsWith("{") && !raw.startsWith("[") && raw.contains('='))) {
            raw.split('&').filter { it.isNotBlank() }.sorted().joinToString("&")
        } else {
            raw
        }
    }

    private fun requestFingerprintsCompatible(first: JSONObject, second: JSONObject): Boolean {
        val firstFingerprint = requestFingerprint(first)
        val secondFingerprint = requestFingerprint(second)
        return firstFingerprint == null || secondFingerprint == null || firstFingerprint == secondFingerprint
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
}
