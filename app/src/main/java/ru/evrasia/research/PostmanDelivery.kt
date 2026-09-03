package ru.evrasia.research

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object PostmanDelivery {
    fun deliver(activity: Activity, json: String, sourceUrl: String = "", environmentJson: String = "") {
        val host = try { java.net.URL(sourceUrl).host.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "request" } } catch (_: Exception) { "request" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val prepared = hardenAuthCollection(json)
        val isAuth = try { JSONObject(prepared).optJSONObject("info")?.optString("name", "")?.contains("AUTH", true) == true } catch (_: Exception) { false }
        val environment = if (environmentJson.isNotBlank()) environmentJson else if (isAuth) try { PostmanEnvironmentSnapshot.build(prepared, NetworkDebugStore.snapshot(), "") } catch (_: Exception) { "" } else ""
        if (environment.isBlank()) {
            ResultDelivery.deliverText(activity, "POSTMAN JSON", prepared, "postman-$host-$stamp.json", "application/json")
            return
        }
        ResultDelivery.deliverGeneratedFile(activity, "POSTMAN PACKAGE", "postman-$host-$stamp.zip", "application/zip") { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("postman-$host-$stamp.postman_collection.json")); zip.write(prepared.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("postman-$host-$stamp.postman_environment.json")); zip.write(environment.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }

    private fun hardenAuthCollection(json: String): String {
        return try {
            val root = JSONObject(json)
            val info = root.optJSONObject("info") ?: return json
            if (!info.optString("name", "").contains("AUTH", true) && !info.optString("description", "").contains("AUTH analyzer", true)) return json
            val items = root.optJSONArray("item") ?: return json
            val variables = root.optJSONArray("variable") ?: JSONArray()
            for (i in 0 until variables.length()) {
                val variable = variables.optJSONObject(i) ?: continue
                val key = variable.optString("key", "")
                if (key.isBlank() || !variable.optString("description", "").contains("Dynamically extracted AUTH value", true)) continue
                val producer = findVariableProducer(items, key) ?: continue
                if (!scriptContains(producer, "prerequest", "pm.collectionVariables.unset(${JSONObject.quote(key)})")) appendScript(producer, "prerequest", listOf("try { pm.collectionVariables.unset(${JSONObject.quote(key)}); } catch (e) {}"))
                if (!scriptContains(producer, "test", "AUTH dependency: $key extracted")) appendScript(producer, "test", listOf(
                    "pm.test(${JSONObject.quote("AUTH dependency: $key extracted")}, function () {",
                    "  const value = pm.collectionVariables.get(${JSONObject.quote(key)});",
                    "  pm.expect(value, ${JSONObject.quote("Required dynamic AUTH value '$key' was not extracted")}).to.not.be.undefined;",
                    "  pm.expect(String(value == null ? '' : value), ${JSONObject.quote("Required dynamic AUTH value '$key' is empty")}).to.not.equal('');",
                    "});"
                ))
            }
            findLoginItem(items)?.let { login -> if (!scriptContains(login, "test", "AUTH login: semantic success")) appendScript(login, "test", semanticLoginLines(login)) }
            root.toString(2)
        } catch (_: Exception) { json }
    }

    private fun semanticLoginLines(item: JSONObject): List<String> {
        val marker = observedSuccess(item)
        val lines = mutableListOf("pm.test(\"AUTH login: semantic success\", function () {", "  let payload;", "  try { payload = pm.response.json(); } catch (e) { payload = undefined; }")
        if (marker != null) { lines.add("  pm.expect(payload, \"Expected captured JSON authentication response\").to.not.be.undefined;"); lines.add("  pm.expect(JSON.stringify(payload)).to.include(${JSONObject.quote(marker)});") }
        else { lines.add("  if (payload !== undefined) {"); lines.add("    const text = JSON.stringify(payload).toLowerCase();"); lines.add("    pm.expect(/\\\"(?:status|result|state|outcome)\\\"\\s*:\\s*\\\"(?:error|failed|failure|unauthorized|forbidden|invalid|login_required|not_authenticated)\\\"/.test(text), \"Authentication response contains an explicit failure marker\").to.eql(false);"); lines.add("  }") }
        lines.add("});"); return lines
    }

    private fun observedSuccess(item: JSONObject): String? {
        val responses = item.optJSONArray("response") ?: return null
        for (i in 0 until responses.length()) {
            val body = responses.optJSONObject(i)?.optString("body", "")?.trim().orEmpty(); if (!body.startsWith("{")) continue
            try { val obj = JSONObject(body); val status = obj.optString("status", ""); if (status.lowercase(Locale.US) in setOf("success", "ok", "authenticated", "authorized")) return "\"status\":\"$status\""; for (key in listOf("success", "ok", "authenticated", "authorized", "valid")) if (obj.opt(key) == true) return "\"$key\":true" } catch (_: Exception) {}
        }; return null
    }

    private fun findVariableProducer(items: JSONArray, variable: String): JSONObject? { for (i in 0 until items.length()) { val item = items.optJSONObject(i) ?: continue; if (scriptContains(item, "test", "pm.collectionVariables.set(${JSONObject.quote(variable)}") || scriptContains(item, "test", "pm.collectionVariables.set('$variable'")) return item }; return null }
    private fun findLoginItem(items: JSONArray): JSONObject? { for (i in 0 until items.length()) { val item = items.optJSONObject(i) ?: continue; val name = item.optString("name", ""); if (name.contains(" Login · ") || name.startsWith("Login ·") || Regex("^\\d+\\s+Login\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return item }; return null }
    private fun appendScript(item: JSONObject, listen: String, lines: List<String>) { val events = item.optJSONArray("event") ?: JSONArray().also { item.put("event", it) }; var target: JSONObject? = null; for (i in 0 until events.length()) if (events.optJSONObject(i)?.optString("listen", "") == listen) { target = events.optJSONObject(i); break }; if (target == null) { target = JSONObject().put("listen", listen).put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray())); events.put(target) }; val script = target.optJSONObject("script") ?: JSONObject().also { target.put("script", it) }; script.put("type", "text/javascript"); val exec = script.optJSONArray("exec") ?: JSONArray().also { script.put("exec", it) }; lines.forEach(exec::put) }
    private fun scriptContains(item: JSONObject, listen: String, needle: String): Boolean { val events = item.optJSONArray("event") ?: return false; for (e in 0 until events.length()) { val event = events.optJSONObject(e) ?: continue; if (event.optString("listen", "") != listen) continue; val exec = event.optJSONObject("script")?.optJSONArray("exec") ?: continue; for (i in 0 until exec.length()) if (exec.optString(i, "").contains(needle)) return true }; return false }
}
