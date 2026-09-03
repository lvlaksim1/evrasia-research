package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit boundary between the raw research archive and the correlated debugger view.
 * Raw records are preserved byte-for-byte at the JSON value level for exact research exports.
 * Request-header aliases are normalized only for the debugger copy so merged events retain
 * native browser headers without changing the captured archive.
 */
internal object NetworkRecordPipeline {
    fun appendRawAndDebug(rawRecords: JSONArray, record: JSONObject) {
        rawRecords.put(record)
        NetworkDebugStore.add(normalizeForDebugger(record))
    }

    fun addDebuggerOnly(record: JSONObject) {
        NetworkDebugStore.add(normalizeForDebugger(record))
    }

    fun clearDebugger() {
        NetworkDebugStore.clear()
    }

    private fun normalizeForDebugger(record: JSONObject): JSONObject {
        val copy = JSONObject(record.toString())
        val legacy = copy.optJSONObject("headers")
        val request = copy.optJSONObject("requestHeaders") ?: JSONObject()
        if (legacy != null) {
            val keys = legacy.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!hasHeader(request, key)) request.put(key, legacy.opt(key))
            }
        }
        if (request.length() > 0) copy.put("requestHeaders", request)
        return copy
    }

    private fun hasHeader(headers: JSONObject, name: String): Boolean {
        val keys = headers.keys()
        while (keys.hasNext()) if (keys.next().equals(name, true)) return true
        return false
    }
}
