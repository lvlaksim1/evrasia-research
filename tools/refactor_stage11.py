from pathlib import Path

activity_path = Path('app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt')
activity = activity_path.read_text()

old_field = '    private val storeCache = LinkedHashMap<Long, JSONObject>()\n'
new_field = '    private val dataSource = NetworkDebuggerDataSource()\n'
if old_field not in activity:
    raise SystemExit('store cache field not found')
activity = activity.replace(old_field, new_field, 1)

old_refresh = '''    private fun refreshIncremental(force:Boolean=false){
        val delta = NetworkDebugStore.delta(if(force)-1L else lastRevision)
        if(!force && delta.revision==lastRevision && delta.events.isEmpty())return
        if(delta.reset || force)storeCache.clear()
        delta.events.sortedBy{it.optLong("_storeId",Long.MAX_VALUE)}.forEach{event->
            val id=event.optLong("_storeId",fallbackId(event))
            storeCache[id]=event
        }
        lastRevision=delta.revision
        allItems.clear()
        allItems.addAll(storeCache.values.toList().asReversed())
        rebuildDynamicFilters()
        applyFilters()
    }
'''
new_refresh = '''    private fun refreshIncremental(force:Boolean=false){
        val result = dataSource.refresh(force, lastRevision)
        if(!result.changed)return
        lastRevision=result.revision
        allItems.clear()
        allItems.addAll(result.events)
        rebuildDynamicFilters()
        applyFilters()
    }
'''
if old_refresh not in activity:
    raise SystemExit('refreshIncremental block not found')
activity = activity.replace(old_refresh, new_refresh, 1)
activity_path.write_text(activity)

Path('app/src/main/java/ru/evrasia/research/NetworkDebuggerDataSource.kt').write_text('''package ru.evrasia.research

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
''')

print('stage11 refactor applied')
