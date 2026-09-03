package ru.evrasia.research

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PostmanDelivery {
    private data class SuccessMarker(val path: List<String>, val expected: Any)

    fun deliver(activity: Activity, json: String, sourceUrl: String = "") {
        val host = try {
            java.net.URL(sourceUrl).host.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "request" }
        } catch (_: Exception) {
            "request"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val prepared = hardenAuthCollection(json)
        ResultDelivery.deliverText(activity, "POSTMAN JSON", prepared, "postman-$host-$stamp.json", "application/json")
    }

    private fun hardenAuthCollection(json: String): String {
        return try {
            val root = JSONObject(json)
            val info = root.optJSONObject("info") ?: return json
            val name = info.optString("name", "")
            val description = info.optString("description", "")
            if (!name.contains("AUTH", true) && !description.contains("AUTH analyzer", true)) return json

            val items = root.optJSONArray("item") ?: return json
            val variables = root.optJSONArray("variable") ?: JSONArray()
            val dynamicVariables = mutableListOf<String>()
            for (index in 0 until variables.length()) {
                val variable = variables.optJSONObject(index) ?: continue
                val key = variable.optString("key", "")
                val variableDescription = variable.optString("description", "")
                if (key.isNotBlank() && variableDescription.contains("Dynamically extracted AUTH value", true)) dynamicVariables.add(key)
            }

            dynamicVariables.distinct().forEach { variable ->
                val producer = findVariableProducer(items, variable) ?: return@forEach
                if (!scriptContains(producer, "prerequest", "pm.collectionVariables.unset(${JSONObject.quote(variable)})")) {
                    appendScript(
                        producer,
                        "prerequest",
                        listOf("try { pm.collectionVariables.unset(${JSONObject.quote(variable)}); } catch (e) {}")
                    )
                }
                if (!scriptContains(producer, "test", "AUTH dependency: $variable extracted")) {
                    appendScript(
                        producer,
                        "test",
                        listOf(
                            "pm.test(${JSONObject.quote("AUTH dependency: $variable extracted")}, function () {",
                            "  const value = pm.collectionVariables.get(${JSONObject.quote(variable)});",
                            "  pm.expect(value, ${JSONObject.quote("Required dynamic AUTH value '$variable' was not extracted")}).to.not.be.undefined;",
                            "  pm.expect(String(value == null ? '' : value), ${JSONObject.quote("Required dynamic AUTH value '$variable' is empty")}).to.not.equal('');",
                            "});"
                        )
                    )
                }
            }

            findLoginItem(items)?.let { login ->
                if (!scriptContains(login, "test", "AUTH login: semantic success")) {
                    val markers = observedSuccessMarkers(login)
                    appendScript(login, "test", semanticLoginLines(markers))
                }
            }

            root.toString(2)
        } catch (_: Exception) {
            json
        }
    }

    private fun findVariableProducer(items: JSONArray, variable: String): JSONObject? {
        val doubleNeedle = "pm.collectionVariables.set(${JSONObject.quote(variable)}"
        val singleNeedle = "pm.collectionVariables.set('$variable'"
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (scriptContains(item, "test", doubleNeedle) || scriptContains(item, "test", singleNeedle)) return item
        }
        return null
    }

    private fun findLoginItem(items: JSONArray): JSONObject? {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val name = item.optString("name", "")
            if (name.contains(" Login · ") || name.startsWith("Login ·") || Regex("^\\d+\\s+Login\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return item
        }
        return null
    }

    private fun observedSuccessMarkers(item: JSONObject): List<SuccessMarker> {
        val responses = item.optJSONArray("response") ?: return emptyList()
        for (index in 0 until responses.length()) {
            val body = responses.optJSONObject(index)?.optString("body", "")?.trim().orEmpty()
            if (!(body.startsWith("{") || body.startsWith("["))) continue
            try {
                val root: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
                val out = mutableListOf<SuccessMarker>()
                collectSuccessMarkers(root, emptyList(), out)
                if (out.isNotEmpty()) return out.take(3)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun collectSuccessMarkers(value: Any?, path: List<String>, out: MutableList<SuccessMarker>) {
        if (out.size >= 6) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext() && out.size < 6) {
                    val key = keys.next()
                    val child = value.opt(key)
                    val normalized = normalize(key)
                    val nextPath = path + key
                    when {
                        child is Boolean && child && normalized in setOf("success", "ok", "authenticated", "authorized", "valid", "logged_in") -> out.add(SuccessMarker(nextPath, true))
                        child is String && normalized in setOf("status", "result", "state", "outcome") && child.lowercase(Locale.US) in setOf("success", "ok", "authenticated", "authorized", "logged_in", "loggedin") -> out.add(SuccessMarker(nextPath, child))
                    }
                    collectSuccessMarkers(child, nextPath, out)
                }
            }
            is JSONArray -> for (index in 0 until minOf(value.length(), 20)) collectSuccessMarkers(value.opt(index), path + index.toString(), out)
        }
    }

    private fun semanticLoginLines(markers: List<SuccessMarker>): List<String> {
        val lines = mutableListOf<String>()
        lines.add("pm.test(\"AUTH login: semantic success\", function () {")
        lines.add("  let payload;")
        lines.add("  try { payload = pm.response.json(); } catch (e) { payload = undefined; }")
        if (markers.isNotEmpty()) {
            lines.add("  pm.expect(payload, \"Expected captured JSON authentication response\").to.not.be.undefined;")
            markers.forEachIndexed { index, marker ->
                val path = JSONArray()
                marker.path.forEach(path::put)
                lines.add("  let observed$index = payload;")
                lines.add("  for (const key of $path) observed$index = observed$index == null ? undefined : observed$index[key];")
                val expected = when (val value = marker.expected) {
                    is Boolean -> value.toString()
                    is Number -> value.toString()
                    else -> JSONObject.quote(value.toString())
                }
                lines.add("  pm.expect(observed$index, ${JSONObject.quote("Observed successful AUTH marker at ${marker.path.joinToString(".")}")}).to.eql($expected);")
            }
        } else {
            lines.add("  if (payload !== undefined) {")
            lines.add("    let explicitFailure = false;")
            lines.add("    const walk = function (value) {")
            lines.add("      if (value == null || explicitFailure) return;")
            lines.add("      if (Array.isArray(value)) { for (const item of value) walk(item); return; }")
            lines.add("      if (typeof value !== 'object') return;")
            lines.add("      for (const key of Object.keys(value)) {")
            lines.add("        const normalized = String(key).toLowerCase().replace(/[^a-z0-9]+/g, '_');")
            lines.add("        const child = value[key];")
            lines.add("        if (['success','ok','authenticated','authorized','valid','logged_in'].includes(normalized) && child === false) { explicitFailure = true; return; }")
            lines.add("        if (['status','result','state','outcome'].includes(normalized) && typeof child === 'string' && ['error','failed','failure','unauthorized','forbidden','invalid','invalid_credentials','login_required','not_authenticated'].includes(child.toLowerCase())) { explicitFailure = true; return; }")
            lines.add("        if (['error','errors'].includes(normalized) && child != null && child !== '' && !(Array.isArray(child) && child.length === 0) && !(typeof child === 'object' && !Array.isArray(child) && Object.keys(child).length === 0)) { explicitFailure = true; return; }")
            lines.add("        walk(child);")
            lines.add("      }")
            lines.add("    };")
            lines.add("    walk(payload);")
            lines.add("    pm.expect(explicitFailure, \"Authentication response contains an explicit failure marker\").to.eql(false);")
            lines.add("  }")
        }
        lines.add("});")
        return lines
    }

    private fun appendScript(item: JSONObject, listen: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val events = item.optJSONArray("event") ?: JSONArray().also { item.put("event", it) }
        var target: JSONObject? = null
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            if (event.optString("listen", "") == listen) {
                target = event
                break
            }
        }
        if (target == null) {
            target = JSONObject().put("listen", listen).put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray()))
            events.put(target)
        }
        val script = target.optJSONObject("script") ?: JSONObject().also { target.put("script", it) }
        script.put("type", "text/javascript")
        val exec = script.optJSONArray("exec") ?: JSONArray().also { script.put("exec", it) }
        lines.forEach(exec::put)
    }

    private fun scriptContains(item: JSONObject, listen: String, needle: String): Boolean {
        val events = item.optJSONArray("event") ?: return false
        for (eventIndex in 0 until events.length()) {
            val event = events.optJSONObject(eventIndex) ?: continue
            if (event.optString("listen", "") != listen) continue
            val exec = event.optJSONObject("script")?.optJSONArray("exec") ?: continue
            for (lineIndex in 0 until exec.length()) {
                if (exec.optString(lineIndex, "").contains(needle)) return true
            }
        }
        return false
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
