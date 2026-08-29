package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

object NetworkDebugStore {
    @Volatile var recording: Boolean = true
    private val events = mutableListOf<JSONObject>()
    private val revision = AtomicLong(0)
    private const val MAX_EVENTS = 10000
    private const val DEDUP_WINDOW = 32

    private val networkSources = setOf(
        "webview", "fetch", "xhr", "resource-copy", "navigation", "new-window",
        "websocket-open", "websocket-state", "websocket-send", "websocket-receive",
        "sse-open", "sse-state", "sse-message", "beacon", "source-map", "script-archive"
    )

    @Synchronized fun add(record: JSONObject) {
        if (!recording) return
        val source = record.optString("source", "")
        if (!networkSources.contains(source) && !record.has("url")) return
        events.add(JSONObject(record.toString()))
        if (events.size > MAX_EVENTS) events.removeAt(0)
        revision.incrementAndGet()
    }

    @Synchronized fun clear() {
        events.clear()
        revision.incrementAndGet()
    }

    @Synchronized fun snapshot(): List<JSONObject> {
        val out = ArrayList<JSONObject>(events.size)
        val recentQueue = ArrayDeque<String>(DEDUP_WINDOW)
        val recentSet = HashSet<String>(DEDUP_WINDOW * 2)

        events.forEach { event ->
            val fingerprint = displayFingerprint(event)
            if (recentSet.add(fingerprint)) {
                out.add(JSONObject(event.toString()))
                recentQueue.addLast(fingerprint)
                if (recentQueue.size > DEDUP_WINDOW) {
                    recentSet.remove(recentQueue.removeFirst())
                }
            }
        }
        return out
    }

    private fun displayFingerprint(event: JSONObject): String = buildString(192) {
        append(event.optString("source", "")); append('\u0001')
        append(event.optLong("time", Long.MIN_VALUE)); append('\u0001')
        append(event.optString("method", "")); append('\u0001')
        append(event.optString("url", "")); append('\u0001')
        append(event.optInt("status", Int.MIN_VALUE)); append('\u0001')
        append(event.optString("data", "")); append('\u0001')
        append(event.optString("message", "")); append('\u0001')
        append(event.optString("requestBody", "")); append('\u0001')
        append(event.optString("responseBody", "")); append('\u0001')
        append(event.optLong("responseSize", Long.MIN_VALUE)); append('\u0001')
        append(event.optLong("duration", Long.MIN_VALUE))
    }

    @Synchronized fun json(): JSONArray {
        val out = JSONArray()
        events.forEach { out.put(JSONObject(it.toString())) }
        return out
    }

    fun revision(): Long = revision.get()
}
