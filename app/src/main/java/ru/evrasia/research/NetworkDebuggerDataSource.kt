package ru.evrasia.research

import org.json.JSONObject
import java.util.LinkedHashMap

internal class NetworkDebuggerDataSource {
    data class RefreshResult(
        val revision: Long,
        val events: List<JSONObject>,
        val changed: Boolean
    )

    private val cache = LinkedHashMap<Long, JSONObject>()

    fun refresh(force: Boolean, lastRevision: Long): RefreshResult {
        val delta = NetworkDebugStore.delta(if (force) -1L else lastRevision)
        if (!force && delta.revision == lastRevision && delta.events.isEmpty()) {
            return RefreshResult(lastRevision, emptyList(), false)
        }

        if (delta.reset || force) cache.clear()
        delta.events.sortedBy { it.optLong("_storeId", Long.MAX_VALUE) }.forEach { event ->
            val id = event.optLong("_storeId", fallbackId(event))
            cache[id] = event
        }

        return RefreshResult(
            revision = delta.revision,
            events = cache.values.toList().asReversed(),
            changed = true
        )
    }

    private fun fallbackId(event: JSONObject): Long =
        event.optLong("time", 0L) xor event.toString().hashCode().toLong()
}
