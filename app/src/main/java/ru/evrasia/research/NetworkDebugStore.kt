package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object NetworkDebugStore {
    @Volatile var recording: Boolean = true
    private val events = mutableListOf<JSONObject>()
    private val revision = AtomicLong(0)
    private const val MAX_EVENTS = 10000

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

    @Synchronized fun snapshot(): List<JSONObject> = events.map { JSONObject(it.toString()) }

    @Synchronized fun json(): JSONArray {
        val out = JSONArray()
        events.forEach { out.put(JSONObject(it.toString())) }
        return out
    }

    fun revision(): Long = revision.get()
}
