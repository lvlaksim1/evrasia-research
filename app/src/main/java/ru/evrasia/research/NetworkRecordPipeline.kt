package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit boundary between the raw research archive and the correlated debugger view.
 * Raw records are always appended before they are offered to NetworkDebugStore.
 */
internal object NetworkRecordPipeline {
    fun appendRawAndDebug(rawRecords: JSONArray, record: JSONObject) {
        rawRecords.put(record)
        NetworkDebugStore.add(record)
    }

    fun addDebuggerOnly(record: JSONObject) {
        NetworkDebugStore.add(record)
    }

    fun clearDebugger() {
        NetworkDebugStore.clear()
    }
}
