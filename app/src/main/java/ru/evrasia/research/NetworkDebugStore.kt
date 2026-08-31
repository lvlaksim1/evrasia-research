package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object NetworkDebugStore {
    data class Delta(val revision: Long, val reset: Boolean, val events: List<JSONObject>)

    @Volatile var recording: Boolean = true
    private val events = mutableListOf<JSONObject>()
    private val revision = AtomicLong(0)
    private var nextStoreId = 1L
    private var resetRevision = 0L

    private const val MAX_EVENTS = 10000

    private val networkSources = setOf(
        "webview", "fetch", "xhr", "resource-copy", "resource-timing", "fetch-meta", "xhr-meta",
        "navigation", "navigation-timing", "new-window", "user-action", "history",
        "websocket-open", "websocket-state", "websocket-send", "websocket-receive",
        "sse-open", "sse-state", "sse-message", "beacon", "source-map", "script-archive", "replay"
    )

    @Synchronized fun add(record: JSONObject) {
        if (!recording) return
        val source = record.optString("source", "")
        if (!networkSources.contains(source) && !record.has("url")) return

        val copy = JSONObject(record.toString())
        if (NetworkEventCorrelator.isMirroredReplay(events, copy, source)) return

        val explicitTarget = copy.optLong("_mergeTargetId", -1L)
        if (explicitTarget > 0L) {
            val target = events.firstOrNull { it.optLong("_storeId", -1L) == explicitTarget }
            if (target != null) {
                copy.remove("_mergeTargetId")
                NetworkEventCorrelator.mergeInto(target, copy, source)
                touch(target)
                return
            }
        }

        val target = NetworkEventCorrelator.findMergeTarget(events, copy, source)
        if (target != null) {
            NetworkEventCorrelator.mergeInto(target, copy, source)
            touch(target)
            return
        }

        NetworkEventCorrelator.prepareNewRecord(copy, source)
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
