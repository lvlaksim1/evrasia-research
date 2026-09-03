package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.TimeZone

internal object PostmanEnvironmentSnapshot {
    private data class KnownValue(val value: String, val source: String)

    fun build(collectionJson: String, events: List<JSONObject>, initialAuthSource: String): String {
        val collection = JSONObject(collectionJson)
        val collectionVariables = collection.optJSONArray("variable") ?: JSONArray()
        val known = linkedMapOf<String, KnownValue>()

        for (index in 0 until collectionVariables.length()) {
            val variable = collectionVariables.optJSONObject(index) ?: continue
            val key = variable.optString("key", "").trim()
            if (key.isBlank()) continue
            val value = variable.optString("value", "")
            val description = variable.optString("description", "")
            known[key] = KnownValue(value, if (description.isBlank()) "collection" else "collection: $description")
        }

        val fields = capturedRequestFields(events)
        known.keys.toList().forEach { variable ->
            if (known[variable]?.value?.isNotBlank() == true) return@forEach
            credentialValue(variable, fields)?.let { (value, source) ->
                if (value.isNotBlank() && value != "[password]") known[variable] = KnownValue(value, source)
            }
        }

        extractorBindings(collection).forEach { (variable, sourceKey) ->
            val captured = findNamedValue(sourceKey, initialAuthSource, events)
            if (captured != null && captured.value.isNotBlank()) known[variable] = captured
        }

        val values = JSONArray()
        known.forEach { (key, item) ->
            values.put(
                JSONObject()
                    .put("key", key)
                    .put("value", item.value)
                    .put("type", "default")
                    .put("enabled", true)
                    .put("description", "Captured by web research · ${item.source}")
            )
        }

        val host = try {
            val base = known["base_url"]?.value.orEmpty()
            java.net.URL(base).host.ifBlank { "site" }
        } catch (_: Exception) { "site" }
        val stamp = utcStamp()
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("name", "web research · AUTH · $host · captured $stamp")
            .put("values", values)
            .put("_postman_variable_scope", "environment")
            .put("_postman_exported_at", stamp)
            .put("_postman_exported_using", "web research")
            .toString(2)
    }

    private fun capturedRequestFields(events: List<JSONObject>): Map<String, KnownValue> {
        val out = linkedMapOf<String, KnownValue>()
        events.forEach { event ->
            listOf("formFields", "_authFormFields").forEach { arrayName ->
                val fields = event.optJSONArray(arrayName) ?: return@forEach
                for (i in 0 until fields.length()) {
                    val field = fields.optJSONObject(i) ?: continue
                    val name = field.optString("name", field.optString("key", ""))
                    val value = field.optString("value", "")
                    if (name.isNotBlank() && value.isNotBlank() && value != "[password]") out[normalize(name)] = KnownValue(value, "request field $name")
                }
            }
            val body = event.optString("requestBody", "")
            parseUrlEncoded(body).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) out[normalize(name)] = KnownValue(value, "request body field $name")
            }
            if (body.trimStart().startsWith("{")) {
                try { collectJsonFields(JSONObject(body), out) } catch (_: Exception) {}
            }
        }
        return out
    }

    private fun collectJsonFields(value: Any?, out: MutableMap<String, KnownValue>, parent: String = "") {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child != null && child != JSONObject.NULL && child !is JSONObject && child !is JSONArray) {
                        val text = child.toString()
                        if (text.isNotBlank()) out[normalize(key)] = KnownValue(text, "JSON request field $key")
                    }
                    collectJsonFields(child, out, key)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJsonFields(value.opt(i), out, parent)
        }
    }

    private fun credentialValue(variable: String, fields: Map<String, KnownValue>): KnownValue? {
        val normalized = normalize(variable)
        val aliases = when (normalized) {
            "login" -> listOf("login", "username", "user_login", "email", "phone", "user", "identifier")
            "password" -> listOf("password", "user_password", "passwd", "pass", "pwd")
            "otp" -> listOf("otp", "code", "verification_code", "one_time_password", "totp")
            else -> listOf(normalized)
        }
        aliases.forEach { alias -> fields[alias]?.let { return it } }
        return null
    }

    private fun extractorBindings(collection: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val items = collection.optJSONArray("item") ?: return out
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val events = item.optJSONArray("event") ?: continue
            for (e in 0 until events.length()) {
                val event = events.optJSONObject(e) ?: continue
                val exec = event.optJSONObject("script")?.optJSONArray("exec") ?: continue
                val script = buildString { for (line in 0 until exec.length()) append(exec.optString(line, "")).append('\n') }
                val sourceKey = Regex("const\\s+targetKey\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(script)?.groupValues?.getOrNull(1) ?: continue
                Regex("pm\\.collectionVariables\\.set\\(\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE).findAll(script).forEach { match ->
                    val variable = match.groupValues[1]
                    if (variable.isNotBlank()) out[variable] = sourceKey
                }
            }
        }
        return out
    }

    private fun findNamedValue(key: String, initialAuthSource: String, events: List<JSONObject>): KnownValue? {
        extractNamedValue(key, initialAuthSource)?.let { return KnownValue(it, "initial HTML/JS key $key") }
        events.asReversed().forEach { event ->
            val content = event.optString("content", "")
            extractNamedValue(key, content)?.let { return KnownValue(it, "page source key $key") }
            val body = NetworkEventClassifier.responseBodyText(event)
            extractNamedValue(key, body)?.let { return KnownValue(it, "response key $key") }
            listOf("responseHeaders", "headers").forEach { headerName ->
                val headers = event.optJSONObject(headerName) ?: return@forEach
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val header = keys.next()
                    if (header.equals(key, true)) {
                        val value = headers.optString(header, "")
                        if (value.isNotBlank()) return KnownValue(value, "response header $header")
                    }
                }
            }
        }
        return null
    }

    private fun extractNamedValue(key: String, text: String): String? {
        if (text.isBlank()) return null
        val escaped = Regex.escape(key)
        val patterns = listOf(
            Regex("[\\\"']$escaped[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
            Regex("\\b$escaped\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
            Regex("name\\s*=\\s*[\\\"']$escaped[\\\"'][^>]*value\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
            Regex("value\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*name\\s*=\\s*[\\\"']$escaped[\\\"']", RegexOption.IGNORE_CASE),
            Regex("(?:name|property)\\s*=\\s*[\\\"']$escaped[\\\"'][^>]*content\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { pattern -> pattern.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return htmlDecode(it) } }
        return null
    }

    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> {
        if (raw.isBlank() || !raw.contains('=')) return emptyList()
        return raw.split('&').mapNotNull { part ->
            val split = part.indexOf('=')
            if (split <= 0) return@mapNotNull null
            try {
                URLDecoder.decode(part.substring(0, split), "UTF-8") to URLDecoder.decode(part.substring(split + 1), "UTF-8")
            } catch (_: Exception) { null }
        }
    }

    private fun htmlDecode(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun utcStamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
