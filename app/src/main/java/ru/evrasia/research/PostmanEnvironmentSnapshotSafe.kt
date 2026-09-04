package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal object PostmanEnvironmentSnapshotSafe {
    private data class KnownValue(val value: String, val source: String)
    private data class Marker(val name: String, val start: Int, val endExclusive: Int)

    fun build(collectionJson: String, events: List<JSONObject>, initialAuthSource: String): String {
        val collection = JSONObject(collectionJson)
        val variables = collection.optJSONArray("variable") ?: JSONArray()
        val known = linkedMapOf<String, KnownValue>()
        val runtimeDynamic = linkedSetOf<String>()

        for (index in 0 until variables.length()) {
            val variable = variables.optJSONObject(index) ?: continue
            val key = variable.optString("key", "").trim()
            if (key.isBlank()) continue
            val description = variable.optString("description", "").ifBlank { "collection variable" }
            known[key] = KnownValue(variable.optString("value", ""), description)
            if (description.contains("Automatically extracted from an earlier AUTH response", true) ||
                description.contains("Dynamically extracted AUTH value", true)) {
                runtimeDynamic.add(key)
            }
        }

        val items = collection.optJSONArray("item") ?: JSONArray()
        val baseUrl = known["base_url"]?.value.orEmpty()
        val mergedEvents = NetworkDisplayMerger.merge(events.map { JSONObject(it.toString()) })

        for (itemIndex in 0 until items.length()) {
            val request = items.optJSONObject(itemIndex)?.optJSONObject("request") ?: continue
            val captured = matchCapturedEvent(request, mergedEvents, baseUrl) ?: continue
            applyRequestVariables(request, captured, known, itemIndex)
        }

        val fields = collectRequestFields(mergedEvents)
        known.keys.toList().forEach { variable ->
            if (known[variable]?.value?.isNotBlank() == true) return@forEach
            credentialValue(variable, fields)?.let { value ->
                if (value.value.isNotBlank()) known[variable] = value
            }
        }

        known.keys.toList().forEach { variable ->
            if (known[variable]?.value?.isNotBlank() == true) return@forEach
            candidateKeys(variable).forEach { key ->
                val value = findNamedValue(key, initialAuthSource, mergedEvents)
                if (value != null && value.value.isNotBlank()) {
                    known[variable] = value
                    return@forEach
                }
            }
        }

        val values = JSONArray()
        known.forEach { (key, item) ->
            if (key in runtimeDynamic) return@forEach
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
        } catch (_: Exception) {
            "site"
        }
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

    private fun matchCapturedEvent(request: JSONObject, events: List<JSONObject>, baseUrl: String): JSONObject? {
        val method = request.optString("method", "GET").uppercase(Locale.US)
        val template = requestUrl(request).replace("{{base_url}}", baseUrl)
        val candidates = events.filter { event ->
            val eventMethod = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US)
            eventMethod == method && urlMatches(template, event.optString("url", ""))
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { event ->
            var score = 0
            val source = event.optString("source", "").lowercase(Locale.US)
            if (source in setOf("xhr", "fetch", "replay")) score += 8
            if (source == "webview") score += 4
            if (event.optInt("status", 0) > 0) score += 3
            score + requestFields(event).size.coerceAtMost(10)
        }
    }

    private fun applyRequestVariables(
        request: JSONObject,
        event: JSONObject,
        known: MutableMap<String, KnownValue>,
        itemIndex: Int
    ) {
        val capturedHeaders = requestHeaders(event)
        request.optJSONArray("header")?.let { headers ->
            for (index in 0 until headers.length()) {
                val header = headers.optJSONObject(index) ?: continue
                val key = header.optString("key", "")
                val captured = headerValue(capturedHeaders, key) ?: continue
                assignTemplateVariables(
                    header.optString("value", ""),
                    captured,
                    known,
                    "request #${itemIndex + 1} header $key"
                )
            }
        }

        val capturedFields = requestFields(event)
        request.optJSONObject("body")?.let { body ->
            listOf("formdata", "urlencoded").forEach { arrayName ->
                val array = body.optJSONArray(arrayName) ?: return@forEach
                for (index in 0 until array.length()) {
                    val field = array.optJSONObject(index) ?: continue
                    val key = field.optString("key", "")
                    val captured = fieldValue(capturedFields, key) ?: continue
                    assignTemplateVariables(
                        field.optString("value", ""),
                        captured,
                        known,
                        "request #${itemIndex + 1} body field $key"
                    )
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
            assignTemplateVariables(
                urlTemplate,
                capturedUrl,
                known,
                "request #${itemIndex + 1} URL",
                setOf("base_url")
            )
        }
    }

    private fun assignTemplateVariables(
        template: String,
        captured: String,
        known: MutableMap<String, KnownValue>,
        source: String,
        skip: Set<String> = emptySet()
    ) {
        val extracted = extractTemplateValues(template, captured) ?: return
        extracted.forEach { (name, value) ->
            if (name !in skip && value.isNotBlank()) known[name] = KnownValue(value, source)
        }
    }

    private fun extractTemplateValues(template: String, captured: String): Map<String, String>? {
        val markers = templateMarkers(template)
        if (markers.isEmpty()) return if (template == captured) emptyMap() else null
        val values = linkedMapOf<String, String>()
        var templateCursor = 0
        var capturedCursor = 0

        markers.forEachIndexed { index, marker ->
            val literalBefore = template.substring(templateCursor, marker.start)
            if (!captured.startsWith(literalBefore, capturedCursor)) return null
            capturedCursor += literalBefore.length

            val nextStart = markers.getOrNull(index + 1)?.start ?: template.length
            val literalAfter = template.substring(marker.endExclusive, nextStart)
            val valueEnd = when {
                literalAfter.isNotEmpty() -> captured.indexOf(literalAfter, capturedCursor).takeIf { it >= 0 } ?: return null
                index == markers.lastIndex -> captured.length
                else -> return null
            }
            values[marker.name] = captured.substring(capturedCursor, valueEnd)
            capturedCursor = valueEnd
            templateCursor = marker.endExclusive
        }

        val tail = template.substring(templateCursor)
        if (!captured.startsWith(tail, capturedCursor)) return null
        capturedCursor += tail.length
        return if (capturedCursor == captured.length) values else null
    }

    private fun templateMarkers(template: String): List<Marker> {
        val out = mutableListOf<Marker>()
        var cursor = 0
        while (cursor < template.length) {
            val start = template.indexOf("{{", cursor)
            if (start < 0) break
            val close = template.indexOf("}}", start + 2)
            if (close < 0) break
            val name = template.substring(start + 2, close).trim()
            if (name.isNotBlank()) out.add(Marker(name, start, close + 2))
            cursor = close + 2
        }
        return out
    }

    private fun urlMatches(template: String, captured: String): Boolean {
        if (template == captured) return true
        if (template.isBlank() || captured.isBlank()) return false
        if (templateMarkers(template).isNotEmpty() && extractTemplateValues(template, captured) != null) return true
        return try {
            val a = URL(template)
            val b = URL(captured)
            a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && a.port == b.port && a.path == b.path && a.query.orEmpty() == b.query.orEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun collectRequestFields(events: List<JSONObject>): Map<String, KnownValue> {
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
            for (index in 0 until fields.length()) {
                val field = fields.optJSONObject(index) ?: continue
                val name = field.optString("name", field.optString("key", ""))
                val value = field.optString("value", "")
                if (name.isNotBlank() && value.isNotBlank()) out[name] = value
            }
        }

        val body = event.optString("requestBody", "").trim()
        when {
            body.startsWith("[") -> try {
                val array = JSONArray(body)
                if (!collectPairArray(array, out)) collectJsonFields(array, out)
            } catch (_: Exception) {}
            body.startsWith("{") -> try {
                collectJsonFields(JSONObject(body), out)
            } catch (_: Exception) {}
            else -> parseUrlEncoded(body).forEach { (name, value) -> if (name.isNotBlank()) out[name] = value }
        }
        return out
    }

    private fun collectPairArray(array: JSONArray, out: MutableMap<String, String>): Boolean {
        if (array.length() == 0) return false
        var count = 0
        for (index in 0 until minOf(array.length(), 200)) {
            when (val item = array.opt(index)) {
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
                        out[key] = child.toString()
                    }
                    collectJsonFields(child, out)
                }
            }
            is JSONArray -> for (index in 0 until minOf(value.length(), 100)) collectJsonFields(value.opt(index), out)
        }
    }

    private fun credentialValue(variable: String, fields: Map<String, KnownValue>): KnownValue? {
        val aliases = when (normalize(variable)) {
            "login" -> listOf("login", "username", "user_login", "email", "phone", "user", "identifier")
            "password" -> listOf("password", "user_password", "passwd", "pass", "pwd")
            "otp" -> listOf("otp", "code", "verification_code", "one_time_password", "totp")
            else -> listOf(normalize(variable))
        }
        aliases.forEach { alias -> fields[alias]?.let { return it } }
        return null
    }

    private fun candidateKeys(variable: String): List<String> {
        val normalized = normalize(variable)
        return when {
            normalized.contains("csrf") || normalized.contains("xsrf") -> listOf(variable, "csrf", "xsrf", "sessid", "csrf_token", "xsrf_token")
            normalized.contains("access") && normalized.contains("token") -> listOf(variable, "access_token", "token", "jwt")
            normalized.contains("refresh") && normalized.contains("token") -> listOf(variable, "refresh_token")
            normalized.contains("session") -> listOf(variable, "session", "session_id", "session_token", "sessid")
            normalized.contains("nonce") -> listOf(variable, "nonce")
            normalized == "oauth_state" || normalized == "state" -> listOf(variable, "state")
            else -> listOf(variable)
        }
    }

    private fun findNamedValue(key: String, initialSource: String, events: List<JSONObject>): KnownValue? {
        extractNamedValue(key, initialSource)?.let { return KnownValue(it, "initial HTML/JS key $key") }
        events.asReversed().forEach { event ->
            val content = event.optString("content", "")
            extractNamedValue(key, content)?.let { return KnownValue(it, "page source key $key") }
            val body = NetworkEventClassifier.responseBodyText(event)
            extractNamedValue(key, body)?.let { return KnownValue(it, "response key $key") }
            listOf("responseHeaders", "headers", "requestHeaders").forEach { objectName ->
                val headers = event.optJSONObject(objectName) ?: return@forEach
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    if (name.equals(key, true)) {
                        val value = headers.optString(name, "")
                        if (value.isNotBlank()) return KnownValue(value, "header $name")
                    }
                }
            }
        }
        return null
    }

    private fun extractNamedValue(key: String, text: String): String? {
        if (key.isBlank() || text.isBlank()) return null
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val root: Any = if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
                findJsonValue(root, key)?.let { return it }
            } catch (_: Exception) {}
        }

        val lower = text.lowercase(Locale.US)
        val needle = key.lowercase(Locale.US)
        var cursor = 0
        while (cursor < lower.length) {
            val index = lower.indexOf(needle, cursor)
            if (index < 0) break
            val afterKey = index + needle.length
            val separator = findSeparator(text, afterKey)
            if (separator >= 0) {
                val value = readScalar(text, separator + 1)
                if (!value.isNullOrBlank()) return htmlDecode(value)
            }
            cursor = afterKey
        }
        return null
    }

    private fun findJsonValue(value: Any?, target: String): String? {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.equals(target, true) && child != null && child != JSONObject.NULL && child !is JSONObject && child !is JSONArray) return child.toString()
                    findJsonValue(child, target)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until minOf(value.length(), 100)) findJsonValue(value.opt(index), target)?.let { return it }
        }
        return null
    }

    private fun findSeparator(text: String, start: Int): Int {
        var index = start
        while (index < text.length && index - start <= 12) {
            val char = text[index]
            if (char == ':' || char == '=') return index
            if (!(char.isWhitespace() || char == '\'' || char == '"')) return -1
            index++
        }
        return -1
    }

    private fun readScalar(text: String, start: Int): String? {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) return null
        val quote = text[index].takeIf { it == '\'' || it == '"' }
        if (quote != null) {
            val end = text.indexOf(quote, index + 1)
            if (end > index + 1) return text.substring(index + 1, end)
            return null
        }
        val begin = index
        while (index < text.length && !text[index].isWhitespace() && text[index] !in charArrayOf(',', ';', '<', '>')) index++
        return text.substring(begin, index).takeIf { it.isNotBlank() }
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

    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> {
        if (raw.isBlank() || !raw.contains('=')) return emptyList()
        return raw.split('&').mapNotNull { part ->
            val split = part.indexOf('=')
            if (split <= 0) return@mapNotNull null
            try {
                URLDecoder.decode(part.substring(0, split), "UTF-8") to URLDecoder.decode(part.substring(split + 1), "UTF-8")
            } catch (_: Exception) {
                null
            }
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
