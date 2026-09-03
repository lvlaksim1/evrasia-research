package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.TimeZone

internal object PostmanEnvironmentSnapshot {
    private data class KnownValue(val value: String, val source: String)
    private data class ExtractorBinding(val variable: String, val sourceKey: String, val itemIndex: Int)

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

        val items = collection.optJSONArray("item") ?: JSONArray()
        val baseUrl = known["base_url"]?.value.orEmpty()
        val usedEvents = mutableSetOf<Int>()
        val matchedEvents = linkedMapOf<Int, JSONObject>()

        for (itemIndex in 0 until items.length()) {
            val item = items.optJSONObject(itemIndex) ?: continue
            val request = item.optJSONObject("request") ?: continue
            val match = matchCapturedEvent(request, events, baseUrl, usedEvents) ?: continue
            matchedEvents[itemIndex] = match.second
            usedEvents.add(match.first)
            applyRequestVariables(request, match.second, known, itemIndex)
        }

        val fields = capturedRequestFields(events)
        known.keys.toList().forEach { variable ->
            if (known[variable]?.value?.isNotBlank() == true) return@forEach
            credentialValue(variable, fields)?.let { captured ->
                if (captured.value.isNotBlank()) known[variable] = captured
            }
        }

        extractorBindings(collection).forEach { binding ->
            if (known[binding.variable]?.value?.isNotBlank() == true) return@forEach
            val producer = matchedEvents[binding.itemIndex]
            val captured = if (producer != null) {
                findNamedValueInEvent(binding.sourceKey, producer)
            } else {
                extractNamedValue(binding.sourceKey, initialAuthSource)?.let { KnownValue(it, "initial HTML/JS key ${binding.sourceKey}") }
            }
            if (captured != null && captured.value.isNotBlank()) known[binding.variable] = captured
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
            URL(known["base_url"]?.value.orEmpty()).host.ifBlank { "site" }
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

    private fun matchCapturedEvent(
        request: JSONObject,
        events: List<JSONObject>,
        baseUrl: String,
        usedEvents: Set<Int>
    ): Pair<Int, JSONObject>? {
        val method = request.optString("method", "GET").uppercase(Locale.US)
        val templateUrl = requestUrl(request).replace("{{base_url}}", baseUrl)
        val candidates = events.withIndex().filter { indexed ->
            val event = indexed.value
            val eventMethod = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US)
            eventMethod == method && urlMatches(templateUrl, event.optString("url", ""))
        }
        if (candidates.isEmpty()) return null
        val preferred = candidates.filter { it.index !in usedEvents }.ifEmpty { candidates }
        val best = preferred.maxByOrNull { indexed -> matchScore(request, indexed.value) } ?: return null
        return best.index to best.value
    }

    private fun matchScore(request: JSONObject, event: JSONObject): Int {
        var score = 0
        val source = event.optString("source", "").lowercase(Locale.US)
        if (source in setOf("xhr", "fetch", "replay")) score += 8
        if (source == "webview") score += 4
        if (event.optInt("status", 0) > 0) score += 3
        val capturedHeaders = requestHeaders(event)
        val requestHeaders = request.optJSONArray("header")
        if (requestHeaders != null) {
            for (i in 0 until requestHeaders.length()) {
                val header = requestHeaders.optJSONObject(i) ?: continue
                val key = header.optString("key", "")
                if (headerValue(capturedHeaders, key) != null) score += 2
            }
        }
        val capturedFields = requestFields(event)
        val body = request.optJSONObject("body")
        if (body != null) {
            listOf("formdata", "urlencoded").forEach { name ->
                val array = body.optJSONArray(name) ?: return@forEach
                for (i in 0 until array.length()) {
                    val field = array.optJSONObject(i) ?: continue
                    if (fieldValue(capturedFields, field.optString("key", "")) != null) score += 2
                }
            }
        }
        return score
    }

    private fun applyRequestVariables(
        request: JSONObject,
        event: JSONObject,
        known: MutableMap<String, KnownValue>,
        itemIndex: Int
    ) {
        val headers = requestHeaders(event)
        val requestHeaderArray = request.optJSONArray("header")
        if (requestHeaderArray != null) {
            for (i in 0 until requestHeaderArray.length()) {
                val header = requestHeaderArray.optJSONObject(i) ?: continue
                val key = header.optString("key", "")
                val template = header.optString("value", "")
                val captured = headerValue(headers, key) ?: continue
                assignTemplateVariables(template, captured, known, "request #${itemIndex + 1} header $key")
            }
        }

        val fields = requestFields(event)
        val body = request.optJSONObject("body")
        if (body != null) {
            listOf("formdata", "urlencoded").forEach { name ->
                val array = body.optJSONArray(name) ?: return@forEach
                for (i in 0 until array.length()) {
                    val field = array.optJSONObject(i) ?: continue
                    val key = field.optString("key", "")
                    val template = field.optString("value", "")
                    val captured = fieldValue(fields, key) ?: continue
                    assignTemplateVariables(template, captured, known, "request #${itemIndex + 1} body field $key")
                }
            }
            val rawTemplate = body.optString("raw", "")
            val rawCaptured = event.optString("requestBody", "")
            if (rawTemplate.isNotBlank() && rawCaptured.isNotBlank()) {
                assignTemplateVariables(rawTemplate, rawCaptured, known, "request #${itemIndex + 1} raw body")
            }
        }

        val urlTemplate = requestUrl(request)
        val capturedUrl = event.optString("url", "")
        if (urlTemplate.isNotBlank() && capturedUrl.isNotBlank()) {
            assignTemplateVariables(urlTemplate, capturedUrl, known, "request #${itemIndex + 1} URL", setOf("base_url"))
        }
    }

    private fun assignTemplateVariables(
        template: String,
        captured: String,
        known: MutableMap<String, KnownValue>,
        source: String,
        skip: Set<String> = emptySet()
    ) {
        val variables = Regex("\\{\\{([^{}]+)}}").findAll(template).map { it.groupValues[1] }.distinct().toList()
        if (variables.isEmpty()) return
        if (variables.size == 1) {
            val variable = variables.first()
            if (variable in skip) return
            val marker = "{{$variable}}"
            val start = template.substringBefore(marker)
            val end = template.substringAfter(marker)
            if (captured.startsWith(start) && captured.endsWith(end) && captured.length >= start.length + end.length) {
                val value = captured.substring(start.length, captured.length - end.length)
                if (value.isNotBlank()) known[variable] = KnownValue(value, source)
            }
            return
        }

        var pattern = ""
        var cursor = 0
        val ordered = Regex("\\{\\{([^{}]+)}}").findAll(template).toList()
        ordered.forEach { match ->
            pattern += Regex.escape(template.substring(cursor, match.range.first))
            pattern += "(.*?)"
            cursor = match.range.last + 1
        }
        pattern += Regex.escape(template.substring(cursor))
        val result = Regex("^$pattern$", RegexOption.DOT_MATCHES_ALL).matchEntire(captured) ?: return
        ordered.forEachIndexed { index, match ->
            val variable = match.groupValues[1]
            if (variable in skip) return@forEachIndexed
            val value = result.groupValues.getOrNull(index + 1).orEmpty()
            if (value.isNotBlank()) known[variable] = KnownValue(value, source)
        }
    }

    private fun capturedRequestFields(events: List<JSONObject>): Map<String, KnownValue> {
        val out = linkedMapOf<String, KnownValue>()
        events.forEach { event ->
            requestFields(event).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) out[normalize(name)] = KnownValue(value, "request field $name")
            }
        }
        return out
    }

    private fun requestFields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        listOf("formFields", "_authFormFields").forEach { arrayName ->
            val fields = event.optJSONArray(arrayName) ?: return@forEach
            for (i in 0 until fields.length()) {
                val field = fields.optJSONObject(i) ?: continue
                val name = field.optString("name", field.optString("key", ""))
                val value = field.optString("value", "")
                if (name.isNotBlank() && value.isNotBlank()) out[name] = value
            }
        }

        val body = event.optString("requestBody", "").trim()
        if (body.startsWith("[")) {
            try {
                val array = JSONArray(body)
                if (!collectPairArray(array, out)) collectJsonFields(array, out)
            } catch (_: Exception) {}
        } else if (body.startsWith("{")) {
            try { collectJsonFields(JSONObject(body), out) } catch (_: Exception) {}
        } else {
            parseUrlEncoded(body).forEach { (name, value) -> if (name.isNotBlank()) out[name] = value }
        }
        return out
    }

    private fun collectPairArray(array: JSONArray, out: MutableMap<String, String>): Boolean {
        if (array.length() == 0) return false
        var count = 0
        for (i in 0 until minOf(array.length(), 200)) {
            val item = array.opt(i)
            when (item) {
                is JSONArray -> {
                    val key = item.optString(0, "")
                    if (key.isBlank() || item.length() < 2) return false
                    out[key] = scalar(item.opt(1))
                    count++
                }
                is JSONObject -> {
                    val key = item.optString("name", item.optString("key", ""))
                    if (key.isBlank() || !item.has("value")) return false
                    out[key] = scalar(item.opt("value"))
                    count++
                }
                else -> return false
            }
        }
        return count > 0
    }

    private fun collectJsonFields(value: Any?, out: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child != null && child != JSONObject.NULL && child !is JSONObject && child !is JSONArray) {
                        val text = child.toString()
                        if (text.isNotBlank()) out[key] = text
                    }
                    collectJsonFields(child, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJsonFields(value.opt(i), out)
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

    private fun extractorBindings(collection: JSONObject): List<ExtractorBinding> {
        val out = mutableListOf<ExtractorBinding>()
        val items = collection.optJSONArray("item") ?: return out
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val itemEvents = item.optJSONArray("event") ?: continue
            for (e in 0 until itemEvents.length()) {
                val event = itemEvents.optJSONObject(e) ?: continue
                val exec = event.optJSONObject("script")?.optJSONArray("exec") ?: continue
                val script = buildString { for (line in 0 until exec.length()) append(exec.optString(line, "")).append('\n') }
                val sourceKey = Regex("const\\s+targetKey\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(script)?.groupValues?.getOrNull(1) ?: continue
                Regex("pm\\.collectionVariables\\.set\\(\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE).findAll(script).forEach { match ->
                    val variable = match.groupValues[1]
                    if (variable.isNotBlank()) out.add(ExtractorBinding(variable, sourceKey, i))
                }
            }
        }
        return out.distinctBy { "${it.variable}|${it.sourceKey}|${it.itemIndex}" }
    }

    private fun findNamedValueInEvent(key: String, event: JSONObject): KnownValue? {
        val content = event.optString("content", "")
        extractNamedValue(key, content)?.let { return KnownValue(it, "producer page source key $key") }
        val body = NetworkEventClassifier.responseBodyText(event)
        extractNamedValue(key, body)?.let { return KnownValue(it, "producer response key $key") }
        listOf("responseHeaders", "headers", "requestHeaders").forEach { headerName ->
            val headers = event.optJSONObject(headerName) ?: return@forEach
            val keys = headers.keys()
            while (keys.hasNext()) {
                val header = keys.next()
                if (header.equals(key, true)) {
                    val value = headers.optString(header, "")
                    if (value.isNotBlank()) return KnownValue(value, "producer header $header")
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

    private fun requestHeaders(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        listOf("headers", "requestHeaders").forEach { objectName ->
            val headers = event.optJSONObject(objectName) ?: return@forEach
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.opt(key)
                if (value != null && value != JSONObject.NULL) out[key] = value.toString()
            }
        }
        return out
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    private fun fieldValue(fields: Map<String, String>, name: String): String? = fields.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun requestUrl(request: JSONObject): String {
        val value = request.opt("url")
        return when (value) {
            is JSONObject -> value.optString("raw", value.toString())
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
    }

    private fun urlMatches(template: String, captured: String): Boolean {
        if (template == captured) return true
        if (template.isBlank() || captured.isBlank()) return false
        if (template.contains("{{")) {
            var pattern = ""
            var cursor = 0
            val matches = Regex("\\{\\{[^{}]+}}").findAll(template).toList()
            matches.forEach { match ->
                pattern += Regex.escape(template.substring(cursor, match.range.first))
                pattern += ".+?"
                cursor = match.range.last + 1
            }
            pattern += Regex.escape(template.substring(cursor))
            if (Regex("^$pattern$").matches(captured)) return true
        }
        return try {
            val a = URL(template)
            val b = URL(captured)
            a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && a.port == b.port && a.path == b.path && a.query.orEmpty() == b.query.orEmpty()
        } catch (_: Exception) { false }
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

    private fun scalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
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
