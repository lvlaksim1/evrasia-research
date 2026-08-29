package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

internal object NetworkEndpointAnalyzer {
    fun group(source: List<JSONObject>): List<JSONObject> {
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        val passthrough = mutableListOf<JSONObject>()
        source.forEach { event ->
            if (!isEligible(event)) {
                passthrough.add(event)
                return@forEach
            }
            val endpoint = normalize(event.optString("url", ""))
            val key = "${NetworkEventClassifier.methodOf(event)}\n$endpoint"
            groups.getOrPut(key) { mutableListOf() }.add(event)
        }

        val result = passthrough.toMutableList()
        groups.forEach { (_, members) ->
            if (members.size < 2) {
                result.add(members.first())
                return@forEach
            }
            val latest = members.maxByOrNull { it.optLong("time", 0L) } ?: members.first()
            val kinds = members.map(NetworkEventClassifier::responseKind).distinct()
            val events = JSONArray()
            members.sortedByDescending { it.optLong("time", 0L) }.forEach { events.put(JSONObject(it.toString())) }
            result.add(
                JSONObject()
                    .put("source", "endpoint-group")
                    .put("_endpointGroup", true)
                    .put("_groupMethod", NetworkEventClassifier.methodOf(latest))
                    .put("_groupCount", members.size)
                    .put("_groupEvents", events)
                    .put("_groupKind", if (kinds.size == 1) kinds.first() else "OTHER")
                    .put("url", normalize(latest.optString("url", "")))
                    .put("time", latest.optLong("time", 0L))
                    .put("status", latest.optInt("status", 0))
            )
        }
        return result.sortedByDescending { it.optLong("time", 0L) }
    }

    fun normalize(raw: String): String = try {
        val url = URL(raw)
        val path = url.path.ifBlank { "/" }
            .replace(Regex("/[0-9]{2,}(?=/|$)"), "/{id}")
            .replace(Regex("/[0-9a-fA-F]{8}-[0-9a-fA-F-]{20,}(?=/|$)"), "/{uuid}")
        "${url.host}$path"
    } catch (_: Exception) {
        raw.substringBefore('?')
    }

    private fun isEligible(event: JSONObject): Boolean {
        if (NetworkEventClassifier.isActionEvent(event) || NetworkEventClassifier.isRealtimeSession(event) || NetworkEventClassifier.isEndpointGroup(event)) return false
        val url = event.optString("url", "")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return NetworkEventClassifier.methodOf(event) in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
    }
}
