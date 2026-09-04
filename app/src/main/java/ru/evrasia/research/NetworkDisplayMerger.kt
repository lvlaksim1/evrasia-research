package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object NetworkDisplayMerger {
    private val mergeSources = setOf("webview", "resource-copy", "resource-timing", "fetch", "fetch-meta", "xhr", "xhr-meta")
    private val sourceOrder = listOf("webview", "fetch", "xhr", "resource-timing", "resource-copy", "replay", "fetch-meta", "xhr-meta")
    private const val MERGE_WINDOW_MS = 1200L
    private const val STRONG_DUPLICATE_WINDOW_MS = 90L

    fun merge(source: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val buckets = HashMap<String, MutableList<JSONObject>>()
        source.forEach { original ->
            val event = JSONObject(original.toString())
            if (!isMergeable(event)) {
                out.add(event)
                return@forEach
            }
            val time = event.optLong("time", 0L)
            val key = mergeKey(event)
            val candidates = buckets.getOrPut(key) { mutableListOf() }
            if (time > 0L) {
                candidates.removeAll { candidate ->
                    val candidateTime = candidate.optLong("time", 0L)
                    candidateTime > 0L && abs(candidateTime - time) > MERGE_WINDOW_MS
                }
            }
            val incomingSources = NetworkEventClassifier.eventSources(event)
            val target = candidates
                .filter { candidate -> NetworkEventClassifier.eventSources(candidate).intersect(incomingSources).isEmpty() }
                .filter { candidate -> requestFingerprintsCompatible(candidate, event) }
                .minByOrNull { candidate ->
                    val candidateTime = candidate.optLong("time", 0L)
                    if (time > 0L && candidateTime > 0L) abs(candidateTime - time) else Long.MAX_VALUE
                }
            if (target != null && timesCompatible(target, event)) {
                val targetTime = target.optLong("time", 0L)
                val delta = if (time > 0L && targetTime > 0L) abs(time - targetTime) else MERGE_WINDOW_MS
                mergeInto(target, event, delta)
            } else {
                if (incomingSources.size > 1) event.put("_displaySources", orderedSourceLabel(incomingSources))
                out.add(event)
                candidates.add(event)
            }
        }
        return collapseStrongDuplicates(out)
    }

    fun sourceSummary(event: JSONObject, mergeMode: Boolean): String {
        val sources = NetworkEventClassifier.eventSources(event)
        return if (mergeMode && sources.size > 1) "${sources.size} src" else displaySource(event)
    }

    private fun collapseStrongDuplicates(source: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val recent = HashMap<String, MutableList<JSONObject>>()
        source.forEach { event ->
            if (!isMergeable(event)) {
                out.add(event)
                return@forEach
            }
            val key = mergeKey(event)
            val time = event.optLong("time", 0L)
            val candidates = recent.getOrPut(key) { mutableListOf() }
            if (time > 0L) {
                candidates.removeAll { candidate ->
                    val candidateTime = candidate.optLong("time", 0L)
                    candidateTime > 0L && abs(candidateTime - time) > STRONG_DUPLICATE_WINDOW_MS
                }
            }
            val target = candidates.minByOrNull { candidate ->
                val candidateTime = candidate.optLong("time", 0L)
                if (time > 0L && candidateTime > 0L) abs(candidateTime - time) else Long.MAX_VALUE
            }
            if (target != null && isStrongDuplicate(target, event)) {
                val delta = abs(target.optLong("time", 0L) - time)
                mergeInto(target, event, delta)
                target.put("_deduplicated", true)
            } else {
                out.add(event)
                candidates.add(event)
            }
        }
        return out
    }

    private fun isStrongDuplicate(first: JSONObject, second: JSONObject): Boolean {
        val firstTime = first.optLong("time", 0L)
        val secondTime = second.optLong("time", 0L)
        if (firstTime <= 0L || secondTime <= 0L || abs(firstTime - secondTime) > STRONG_DUPLICATE_WINDOW_MS) return false
        val firstStatus = first.optInt("status", 0)
        val secondStatus = second.optInt("status", 0)
        if (firstStatus > 0 && secondStatus > 0 && firstStatus != secondStatus) return false
        val firstSources = NetworkEventClassifier.eventSources(first)
        val secondSources = NetworkEventClassifier.eventSources(second)
        if (firstSources == secondSources || firstSources.intersect(secondSources).isEmpty()) return false
        if (!requestFingerprintsCompatible(first, second)) return false

        var evidence = 0
        val firstSize = bestResponseSize(first)
        val secondSize = bestResponseSize(second)
        if (firstSize > 0L && secondSize > 0L) {
            if (firstSize != secondSize) return false else evidence++
        }
        val firstDuration = first.optDouble("duration", -1.0)
        val secondDuration = second.optDouble("duration", -1.0)
        if (firstDuration >= 0.0 && secondDuration >= 0.0) {
            if (abs(firstDuration - secondDuration) > 3.0) return false else evidence++
        }
        val firstBody = NetworkEventClassifier.responseBodyText(first).takeIf { it.isNotBlank() && it != "[binary]" && it != "[non-text response]" }
        val secondBody = NetworkEventClassifier.responseBodyText(second).takeIf { it.isNotBlank() && it != "[binary]" && it != "[non-text response]" }
        if (firstBody != null && secondBody != null) {
            if (firstBody != secondBody) return false else evidence += 2
        }
        val firstKind = NetworkEventClassifier.responseKind(first)
        val secondKind = NetworkEventClassifier.responseKind(second)
        if (firstKind != "OTHER" && secondKind != "OTHER") {
            if (firstKind != secondKind) return false else evidence++
        }
        val subset = firstSources.containsAll(secondSources) || secondSources.containsAll(firstSources)
        return evidence >= 2 || (subset && evidence >= 1 && abs(firstTime - secondTime) <= 25L)
    }

    private fun bestResponseSize(event: JSONObject): Long {
        listOf("responseSize", "decodedBodySize", "encodedBodySize", "transferSize").forEach { key ->
            if (event.has(key)) {
                val value = event.optLong(key, -1L)
                if (value > 0L) return value
            }
        }
        return -1L
    }

    private fun isMergeable(event: JSONObject): Boolean {
        if (event.optString("url", "").isBlank()) return false
        return NetworkEventClassifier.eventSources(event).any { it in mergeSources }
    }

    private fun mergeKey(event: JSONObject): String = "${NetworkEventClassifier.methodOf(event)}\n${event.optString("url", "")}"

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

    private fun timesCompatible(first: JSONObject, second: JSONObject): Boolean {
        val firstTime = first.optLong("time", 0L)
        val secondTime = second.optLong("time", 0L)
        if (firstTime <= 0L || secondTime <= 0L || abs(firstTime - secondTime) > MERGE_WINDOW_MS) return false
        val firstStatus = first.optInt("status", 0)
        val secondStatus = second.optInt("status", 0)
        return firstStatus <= 0 || secondStatus <= 0 || firstStatus == secondStatus
    }

    private fun orderedSourceLabel(sources: Set<String>): String {
        val ordered = sourceOrder.filter { it in sources }.toMutableList()
        sources.filter { it !in ordered }.sorted().forEach(ordered::add)
        return ordered.joinToString(" + ")
    }

    private fun displaySource(event: JSONObject): String = event.optString("_displaySources", event.optString("source", ""))

    private fun mergeInto(target: JSONObject, incoming: JSONObject, delta: Long) {
        val targetSnapshot = JSONObject(target.toString()).apply {
            remove("_mergedEvents")
            remove("_displaySources")
            remove("_mergeConfidence")
        }
        val raw = target.optJSONArray("_mergedEvents") ?: JSONArray().also {
            it.put(targetSnapshot)
            target.put("_mergedEvents", it)
        }
        val incomingRaw = incoming.optJSONArray("_mergedEvents")
        if (incomingRaw != null) {
            for (index in 0 until incomingRaw.length()) {
                incomingRaw.optJSONObject(index)?.let { raw.put(JSONObject(it.toString())) }
            }
        } else {
            raw.put(JSONObject(incoming.toString()).apply {
                remove("_displaySources")
                remove("_mergeConfidence")
            })
        }

        val sources = NetworkEventClassifier.eventSources(target).apply { addAll(NetworkEventClassifier.eventSources(incoming)) }
        val captured = JSONArray()
        sources.forEach(captured::put)
        target.put("capturedSources", captured)
        target.put("_displaySources", orderedSourceLabel(sources))
        val confidence = if (delta <= 250L) "HIGH" else "MEDIUM"
        val oldConfidence = target.optString("_mergeConfidence", "")
        target.put("_mergeConfidence", if (oldConfidence == "MEDIUM" || confidence == "MEDIUM") "MEDIUM" else "HIGH")

        val targetFields = target.optJSONObject("_fieldSources") ?: JSONObject().also { target.put("_fieldSources", it) }
        val incomingFields = incoming.optJSONObject("_fieldSources")
        val incomingSource = incoming.optString("source", "")

        val targetTime = target.optLong("time", 0L)
        val incomingTime = incoming.optLong("time", 0L)
        if (incomingTime > 0L && (targetTime <= 0L || incomingTime < targetTime)) {
            target.put("time", incomingTime)
            targetFields.put("time", incomingFields?.optString("time", incomingSource).orEmpty().ifBlank { incomingSource })
        }

        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in setOf("source", "url", "method", "time", "capturedSources", "_displaySources", "_mergedEvents", "_mergeConfidence", "_fieldSources", "_storeId", "_storeRevision")) continue
            val value = incoming.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            val current = target.opt(key)
            if (current is JSONObject && value is JSONObject) {
                mergeJsonObjects(current, value)
                continue
            }
            if (!meaningful(current) || betterNumeric(current, value)) {
                target.put(key, value)
                val origin = incomingFields?.optString(key, "").orEmpty().ifBlank { incomingSource }
                if (origin.isNotBlank()) targetFields.put(key, origin)
            }
        }
    }

    private fun mergeJsonObjects(target: JSONObject, incoming: JSONObject) {
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = incoming.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            val current = target.opt(key)
            if (current is JSONObject && value is JSONObject) mergeJsonObjects(current, value)
            else if (!meaningful(current) || betterNumeric(current, value)) target.put(key, value)
        }
    }

    private fun meaningful(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is String -> value.isNotBlank() && value != "—"
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    private fun betterNumeric(current: Any?, incoming: Any?): Boolean =
        current is Number && incoming is Number && current.toDouble() == 0.0 && incoming.toDouble() != 0.0
}
