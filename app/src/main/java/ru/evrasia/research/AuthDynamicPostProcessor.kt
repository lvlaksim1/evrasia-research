package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

internal object AuthDynamicPostProcessor {
    private data class Producer(
        val key: String,
        val value: String
    )

    fun enhance(
        result: AuthFlowAnalyzer.Result,
        pageHtml: String,
        pageUrl: String,
        finalUrl: String
    ): AuthFlowAnalyzer.Result {
        if (pageHtml.isBlank()) return addVerifyTests(result, pageUrl, finalUrl)

        val collection = JSONObject(result.collectionJson)
        val producers = collectProducers(pageHtml)
        if (producers.isEmpty()) return addVerifyTests(result, pageUrl, finalUrl)

        val items = collection.optJSONArray("item") ?: JSONArray()
        val variables = collection.optJSONArray("variable") ?: JSONArray().also { collection.put("variable", it) }
        val usedNames = linkedSetOf<String>()
        for (i in 0 until variables.length()) {
            variables.optJSONObject(i)?.optString("key", "")?.takeIf { it.isNotBlank() }?.let(usedNames::add)
        }

        val requestText = buildString {
            for (i in 0 until items.length()) {
                val request = items.optJSONObject(i)?.optJSONObject("request") ?: continue
                append(request.toString()).append('\n')
            }
        }

        val dynamic = producers
            .filter { producer -> reusable(producer.value) && !producer.value.contains("{{") && requestText.contains(producer.value) }
            .distinctBy { it.value }

        if (dynamic.isNotEmpty()) {
            val prepare = ensurePrepareItem(collection, pageUrl)
            dynamic.forEach { producer ->
                val variable = uniqueVariable(canonicalVariable(producer.key), usedNames)
                usedNames.add(variable)
                replaceInRequests(items, producer.value, "{{$variable}}")
                variables.put(
                    JSONObject()
                        .put("key", variable)
                        .put("value", "")
                        .put("type", "string")
                        .put("description", "Dynamically extracted from the authentication page response")
                )
                appendExtractor(prepare, producer.key, variable)
            }
        }

        addVerifyTests(collection, pageUrl, finalUrl)
        val description = collection.optJSONObject("info")?.optString("description", "").orEmpty()
        if (dynamic.isNotEmpty()) {
            collection.optJSONObject("info")?.put(
                "description",
                (description + "\n- Dynamically reconstructed ${dynamic.size} page → request dependencies.").trim()
            )
        }

        val notes = result.notes.toMutableList()
        if (dynamic.isNotEmpty()) notes.add("Dynamically reconstructed ${dynamic.size} page → request dependencies")
        return result.copy(collectionJson = collection.toString(2), notes = notes)
    }

    private fun addVerifyTests(result: AuthFlowAnalyzer.Result, pageUrl: String, finalUrl: String): AuthFlowAnalyzer.Result {
        val collection = JSONObject(result.collectionJson)
        addVerifyTests(collection, pageUrl, finalUrl)
        return result.copy(collectionJson = collection.toString(2))
    }

    private fun addVerifyTests(collection: JSONObject, pageUrl: String, finalUrl: String) {
        val items = collection.optJSONArray("item") ?: return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.optString("name", "").contains("Verify authenticated session", true)) continue
            val lines = mutableListOf<String>()
            lines.add("pm.test('Authenticated session: HTTP success', function () { pm.expect(pm.response.code).to.be.within(200, 399); });")
            if (pageUrl.startsWith("http") && finalUrl.startsWith("http") && normalizeUrl(pageUrl) != normalizeUrl(finalUrl)) {
                val login = JSONObject.quote(normalizeUrl(pageUrl))
                lines.add("try {")
                lines.add("  const finalUrl = pm.response.url ? pm.response.url.toString().split('#')[0] : '';")
                lines.add("  const loginUrl = pm.variables.replaceIn($login).split('#')[0];")
                lines.add("  pm.test('Authenticated session: did not return to login page', function () { if (finalUrl) pm.expect(finalUrl).not.eql(loginUrl); });")
                lines.add("} catch (e) {}")
            }
            appendTestLines(item, lines)
            return
        }
    }

    private fun collectProducers(html: String): List<Producer> {
        val out = mutableListOf<Producer>()

        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).take(300).forEach { match ->
            val attrs = attributes(match.value)
            val key = attrs["name"].orEmpty().ifBlank { attrs["id"].orEmpty() }
            val value = attrs["value"].orEmpty()
            if (key.isNotBlank() && reusable(value)) out.add(Producer(key, value))
        }

        Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).take(200).forEach { match ->
            val attrs = attributes(match.value)
            val key = attrs["name"].orEmpty().ifBlank { attrs["property"].orEmpty() }.ifBlank { attrs["id"].orEmpty() }
            val value = attrs["content"].orEmpty()
            if (key.isNotBlank() && reusable(value)) out.add(Producer(key, value))
        }

        val objectPair = Regex("[\\\"']([A-Za-z_$][A-Za-z0-9_$.:\\-]{1,100})[\\\"']\\s*:\\s*[\\\"']([^\\\"'\\r\\n]{4,4096})[\\\"']")
        objectPair.findAll(html).take(1500).forEach { match ->
            val key = match.groupValues[1]
            val value = decodeHtml(match.groupValues[2])
            if (interestingKey(key) || tokenLike(value)) out.add(Producer(key, value))
        }

        val assignment = Regex("(?:var|let|const)?\\s*([A-Za-z_$][A-Za-z0-9_$.:\\-]{1,100})\\s*=\\s*[\\\"']([^\\\"'\\r\\n]{4,4096})[\\\"']")
        assignment.findAll(html).take(1000).forEach { match ->
            val key = match.groupValues[1]
            val value = decodeHtml(match.groupValues[2])
            if (interestingKey(key) || tokenLike(value)) out.add(Producer(key, value))
        }

        return out.distinctBy { "${it.key.lowercase(Locale.US)}|${it.value}" }
    }

    private fun ensurePrepareItem(collection: JSONObject, pageUrl: String): JSONObject {
        val items = collection.optJSONArray("item") ?: JSONArray().also { collection.put("item", it) }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val request = item.optJSONObject("request") ?: continue
            if (request.optString("method", "").equals("GET", true) && item.optString("name", "").contains("Prepare", true)) return item
        }
        val baseUrl = collection.optJSONArray("variable")?.let { variables ->
            var base = ""
            for (i in 0 until variables.length()) {
                val variable = variables.optJSONObject(i) ?: continue
                if (variable.optString("key", "") == "base_url") base = variable.optString("value", "")
            }
            base
        }.orEmpty()
        val portable = if (baseUrl.isNotBlank() && pageUrl.startsWith(baseUrl)) "{{base_url}}" + pageUrl.substring(baseUrl.length) else pageUrl
        val item = JSONObject()
            .put("name", "00 Prepare authentication page")
            .put("request", JSONObject().put("method", "GET").put("header", JSONArray()).put("url", portable).put("description", "Fetch the captured authentication page and establish dynamic page state."))
        val rebuilt = JSONArray().put(item)
        for (i in 0 until items.length()) rebuilt.put(items.opt(i))
        collection.put("item", rebuilt)
        return item
    }

    private fun appendExtractor(item: JSONObject, key: String, variable: String) {
        val qKey = JSONObject.quote(key)
        val qVar = JSONObject.quote(variable)
        val lines = listOf(
            "try {",
            "  const text = pm.response.text();",
            "  const key = $qKey;",
            "  const escaped = key.replace(/[.*+?^${'$'}{}()|[\\]\\\\]/g, '\\\\$&');",
            "  const patterns = [",
            "    new RegExp('[\\\"\\\']' + escaped + '[\\\"\\\']\\\\s*:\\s*[\\\"\\\']([^\\\"\\\']+)', 'i'),",
            "    new RegExp('(?:var|let|const)?\\\\s*' + escaped + '\\s*=\\s*[\\\"\\\']([^\\\"\\\']+)', 'i'),",
            "    new RegExp('name=[\\\"\\\']' + escaped + '[\\\"\\\'][^>]*value=[\\\"\\\']([^\\\"\\\']+)', 'i'),",
            "    new RegExp('value=[\\\"\\\']([^\\\"\\\']+)[\\\"\\\'][^>]*name=[\\\"\\\']' + escaped + '[\\\"\\\']', 'i'),",
            "    new RegExp('(?:name|property)=[\\\"\\\']' + escaped + '[\\\"\\\'][^>]*content=[\\\"\\\']([^\\\"\\\']+)', 'i'),",
            "    new RegExp('content=[\\\"\\\']([^\\\"\\\']+)[\\\"\\\'][^>]*(?:name|property)=[\\\"\\\']' + escaped + '[\\\"\\\']', 'i')",
            "  ];",
            "  let value = null;",
            "  for (const pattern of patterns) { const match = text.match(pattern); if (match && match[1]) { value = match[1]; break; } }",
            "  if (value !== null) pm.collectionVariables.set($qVar, value);",
            "} catch (e) {}"
        )
        appendTestLines(item, lines)
    }

    private fun appendTestLines(item: JSONObject, lines: List<String>) {
        if (lines.isEmpty()) return
        val events = item.optJSONArray("event") ?: JSONArray().also { item.put("event", it) }
        var testEvent: JSONObject? = null
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("listen", "") == "test") {
                testEvent = event
                break
            }
        }
        if (testEvent == null) {
            testEvent = JSONObject().put("listen", "test").put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray()))
            events.put(testEvent)
        }
        val script = testEvent.optJSONObject("script") ?: JSONObject().also { testEvent.put("script", it) }
        val exec = script.optJSONArray("exec") ?: JSONArray().also { script.put("exec", it) }
        lines.forEach(exec::put)
    }

    private fun replaceInRequests(items: JSONArray, raw: String, marker: String) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            item.optJSONObject("request")?.let { replaceRecursive(it, raw, marker) }
            item.optJSONArray("response")?.let { responses ->
                for (r in 0 until responses.length()) responses.optJSONObject(r)?.optJSONObject("originalRequest")?.let { replaceRecursive(it, raw, marker) }
            }
        }
    }

    private fun replaceRecursive(value: Any?, raw: String, marker: String) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key ->
                    val child = value.opt(key)
                    when (child) {
                        is String -> if (child.contains(raw)) value.put(key, child.replace(raw, marker))
                        is JSONObject, is JSONArray -> replaceRecursive(child, raw, marker)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val child = value.opt(i)
                    when (child) {
                        is String -> if (child.contains(raw)) value.put(i, child.replace(raw, marker))
                        is JSONObject, is JSONArray -> replaceRecursive(child, raw, marker)
                    }
                }
            }
        }
    }

    private fun attributes(tag: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\\\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(tag).forEach {
            out[it.groupValues[1].lowercase(Locale.US)] = decodeHtml(it.groupValues[3])
        }
        return out
    }

    private fun decodeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun interestingKey(key: String): Boolean {
        val value = normalizeField(key)
        return value.contains("token") || value.contains("csrf") || value.contains("xsrf") || value.contains("session") || value.contains("nonce") || value in setOf("jwt", "authorization", "code", "state", "sessid", "authenticity_token", "requestverificationtoken")
    }

    private fun canonicalVariable(key: String): String {
        val value = normalizeField(key)
        return when {
            value.contains("refresh") && value.contains("token") -> "refresh_token"
            value.contains("access") && value.contains("token") -> "access_token"
            value.contains("id_token") || value == "idtoken" -> "id_token"
            value.contains("csrf") || value.contains("xsrf") || value == "sessid" || value == "authenticity_token" || value == "requestverificationtoken" -> "csrf_token"
            value.contains("session") && value.contains("token") -> "session_token"
            value == "state" -> "oauth_state"
            value == "code" -> "authorization_code"
            value.contains("nonce") -> "nonce"
            value in setOf("jwt", "authorization", "token") -> "access_token"
            value.isNotBlank() -> value.take(48)
            else -> "auth_value"
        }
    }

    private fun tokenLike(value: String): Boolean {
        if (value.length < 16 || value.length > 4096 || value.any { it.isWhitespace() }) return false
        if (value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }) return true
        return value.length >= 24 && value.toSet().size >= 10 && value.all { it.isLetterOrDigit() || it in "-_=.~" }
    }

    private fun reusable(value: String): Boolean {
        if (value.length !in 4..4096) return false
        if (value.lowercase(Locale.US) in setOf("true", "false", "null", "undefined", "success", "error")) return false
        return true
    }

    private fun uniqueVariable(base: String, used: Set<String>): String {
        if (base !in used) return base
        var index = 2
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    private fun normalizeField(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun normalizeUrl(raw: String): String = try {
        val url = URL(raw)
        val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        "${url.protocol.lowercase(Locale.US)}://${url.host.lowercase(Locale.US)}$port${url.path.ifBlank { "/" }}" + if (url.query.isNullOrBlank()) "" else "?${url.query}"
    } catch (_: Exception) {
        raw.substringBefore('#')
    }
}
